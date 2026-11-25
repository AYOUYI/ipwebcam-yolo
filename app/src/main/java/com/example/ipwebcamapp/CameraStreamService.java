package com.example.ipwebcamapp;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.ImageFormat;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import fi.iki.elonen.NanoHTTPD;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import static fi.iki.elonen.NanoHTTPD.newChunkedResponse;
import static fi.iki.elonen.NanoHTTPD.newFixedLengthResponse;

/**
 * Runs the camera and the HTTP server as a camera-type foreground service, so the stream keeps
 * going while the screen is off and the process is kept alive in preference to ordinary apps.
 */
public class CameraStreamService extends Service {

    private static final String TAG = "IPWebcamApp";
    private static final String CHANNEL_ID = "ipwebcam";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREFS_NAME = "AppPrefs";
    private static final String KEY_PORT = "port";
    private static final String KEY_PORTRAIT = "portrait";
    private static final String KEY_YOLO = "yolo";
    private static final String KEY_DRAW_BOXES = "draw_boxes";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_PORTRAIT = "portrait";
    public static final String EXTRA_YOLO = "yolo";
    public static final String EXTRA_DRAW_BOXES = "draw_boxes";
    public static final String ACTION_STOP = "com.example.ipwebcamapp.STOP";
    private static final String BOUNDARY = "myboundary";
    private static final int DEFAULT_PORT = 8080;
    private static final int MAX_FRAME_WIDTH = 1280;
    private static final int MAX_FRAME_PIXELS = 1280 * 720;
    private static final long RECONNECT_DELAY_MS = 3000;

    private HandlerThread cameraThread;
    private Handler cameraHandler;   // every camera call and callback runs here
    private HandlerThread imageThread;
    private Handler imageHandler;    // frame conversion and JPEG compression
    private volatile CameraDevice cameraDevice;
    private boolean openingCamera;
    private boolean reconnectPending;  // guards against stacking up reconnect attempts
    private ImageReader imageReader;
    private NanoHTTPD server;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private Size frameSize = new Size(640, 480);
    private int sensorOrientation = 90;   // how far the sensor picture is turned from an upright phone
    private volatile int port = DEFAULT_PORT;
    private long startedElapsed;  // SystemClock.elapsedRealtime() when the service was created
    private volatile boolean portraitStream;  // send frames upright for a phone standing on its short end
    private volatile boolean drawBoxes;       // only meaningful while YOLO is on

    // Guarded by this: the newest JPEG frame and a counter that changes each time it does.
    private byte[] currentFrame;
    private long frameSequence;
    private volatile int frameWidth;          // pixel size of the frames in currentFrame
    private volatile int frameHeight;

    private YoloEngine yolo;

    // Hands the newest frame to YOLO without exposing the lock
    private final YoloEngine.FrameSource frameSource = new YoloEngine.FrameSource() {
        @Override
        public byte[] latestFrame(long[] sequenceOut) {
            synchronized (CameraStreamService.this) {
                sequenceOut[0] = frameSequence;
                return currentFrame;
            }
        }

        @Override
        public int frameWidth() {
            return frameWidth;
        }

        @Override
        public int frameHeight() {
            return frameHeight;
        }
    };

    private final LocalBinder binder = new LocalBinder();

    final class LocalBinder extends Binder {
        CameraStreamService getService() {
            return CameraStreamService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    /**
     * Returns the newest frame if it is different from the one last handed out through
     * {@code sequence}, otherwise null. The returned array is never modified afterwards.
     */
    byte[] latestFrameSince(long[] sequence) {
        synchronized (this) {
            if (currentFrame == null || frameSequence == sequence[0]) {
                return null;
            }
            sequence[0] = frameSequence;
            return currentFrame;
        }
    }

    boolean isCameraOpen() {
        return cameraDevice != null;
    }

    int getPort() {
        return port;
    }

    boolean isPortraitStream() {
        return portraitStream;
    }

    boolean isYoloEnabled() {
        return yolo != null && yolo.isEnabled();
    }

    boolean isDrawBoxesEnabled() {
        return drawBoxes && isYoloEnabled();
    }

    /** What YOLO last found; its status is "off" while YOLO is switched off. */
    YoloEngine.State yoloState() {
        return yolo == null ? YoloEngine.State.OFF : yolo.state();
    }

    int getFrameWidth() {
        return frameWidth;
    }

    int getFrameHeight() {
        return frameHeight;
    }

    private void setYoloEnabled(boolean value) {
        if (yolo == null) {
            return;
        }
        yolo.setEnabled(value);
        if (!value) {
            drawBoxes = false;  // boxes only exist while YOLO is producing them
        }
    }

    String getStreamUrl() {
        String ipAddress = getLocalIpAddress();
        return "http://" + (ipAddress == null ? "<phone-ip>" : ipAddress) + ":" + port + "/video";
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "service onCreate");
        startedElapsed = SystemClock.elapsedRealtime();
        port = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(KEY_PORT, DEFAULT_PORT);
        portraitStream = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_PORTRAIT, false);
        Log.d(TAG, "service onCreate: portrait stream " + (portraitStream ? "on" : "off"));

        cameraThread = new HandlerThread("Camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        // Display priority: the camera pipeline is the app's main job and must not be held up by YOLO
        imageThread = new HandlerThread("ImageProcessor", Process.THREAD_PRIORITY_DISPLAY);
        imageThread.start();
        imageHandler = new Handler(imageThread.getLooper());

        yolo = new YoloEngine(this, frameSource);
        if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_YOLO, false)) {
            setYoloEnabled(true);
            drawBoxes = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_DRAW_BOXES, false);
        }
        Log.d(TAG, "service onCreate: YOLO " + (isYoloEnabled() ? "on" : "off"));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification());
        }

        // Keep the CPU and Wi-Fi up while streaming with the screen off
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IPWebCam:stream");
        wakeLock.acquire();
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        int wifiMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
        wifiLock = wifiManager.createWifiLock(wifiMode, "IPWebCam:wifi");
        wifiLock.acquire();

        startServer(port);
        cameraHandler.post(this::startCamera);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Log.d(TAG, "onStartCommand: Stop requested");
            stopSelf();
            return START_NOT_STICKY;
        }
        int requestedPort = intent == null ? port : intent.getIntExtra(EXTRA_PORT, port);
        if (intent != null && intent.hasExtra(EXTRA_PORTRAIT)) {
            portraitStream = intent.getBooleanExtra(EXTRA_PORTRAIT, portraitStream);
            Log.d(TAG, "onStartCommand: Portrait stream " + (portraitStream ? "on" : "off"));
        }
        if (intent != null && intent.hasExtra(EXTRA_YOLO)) {
            setYoloEnabled(intent.getBooleanExtra(EXTRA_YOLO, isYoloEnabled()));
            Log.d(TAG, "onStartCommand: YOLO " + (isYoloEnabled() ? "on" : "off"));
        }
        if (intent != null && intent.hasExtra(EXTRA_DRAW_BOXES)) {
            drawBoxes = intent.getBooleanExtra(EXTRA_DRAW_BOXES, drawBoxes) && isYoloEnabled();
            Log.d(TAG, "onStartCommand: Draw boxes " + (drawBoxes ? "on" : "off"));
        }
        if (requestedPort != port) {
            port = requestedPort;
            Log.d(TAG, "onStartCommand: Port updated to " + port);
            startServer(port);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, buildNotification());
        }
        // Ask the system to bring the service back if the process is ever killed
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "service onDestroy");
        cameraHandler.post(() -> {
            closeCamera();
            cameraHandler.removeCallbacksAndMessages(null);
        });
        cameraThread.quitSafely();
        imageThread.quitSafely();
        if (yolo != null) {
            yolo.shutdown();
        }
        if (server != null) {
            server.stop();
            Log.d(TAG, "onDestroy: NanoHTTPD server stopped");
        }
        if (wakeLock != null) {
            wakeLock.release();
        }
        if (wifiLock != null) {
            wifiLock.release();
        }
        super.onDestroy();
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        PendingIntent openApp = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopStream = PendingIntent.getService(this, 1, new Intent(this, CameraStreamService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getStreamUrl())
                .setContentIntent(openApp)
                .setOngoing(true)
                .addAction(0, getString(R.string.notification_stop), stopStream)
                .build();
    }

    // ---------------------------------------------------------------- camera

    private void startCamera() {
        if (cameraDevice != null || openingCamera) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "startCamera: Camera permission not granted, waiting");
            return;
        }
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            String[] cameraIds = manager.getCameraIdList();
            if (cameraIds.length == 0) {
                // The system reports no cameras while access is blocked (e.g. ColorOS hides them
                // after a cold start with the screen off); treat it like any other failure
                Log.w(TAG, "startCamera: No camera IDs yet, retrying");
                scheduleReconnect();
                return;
            }
            String cameraId = cameraIds[0];
            Log.d(TAG, "startCamera: Camera ID " + cameraId);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = orientation == null ? 90 : orientation;
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size chosen = map == null ? null : chooseFrameSize(map.getOutputSizes(ImageFormat.YUV_420_888));
            if (chosen == null) {
                Log.w(TAG, "startCamera: No usable frame sizes reported yet, retrying");
                scheduleReconnect();
                return;
            }
            frameSize = chosen;
            Log.d(TAG, "startCamera: Frame size set to " + frameSize);

            imageReader = ImageReader.newInstance(frameSize.getWidth(), frameSize.getHeight(), ImageFormat.YUV_420_888, 2);
            Log.d(TAG, "startCamera: ImageReader created with size " + frameSize);
            imageReader.setOnImageAvailableListener(reader -> {
                Image image = reader.acquireLatestImage();
                if (image != null) {
                    try {
                        int width = image.getWidth();
                        int height = image.getHeight();
                        byte[] nv21 = yuv420ToNV21(image);
                        if (portraitStream) {
                            // Stand the frame upright for a phone in its natural (portrait) orientation
                            int turnedWidth = Nv21Frames.rotatedWidth(width, height, sensorOrientation);
                            int turnedHeight = Nv21Frames.rotatedHeight(width, height, sensorOrientation);
                            nv21 = Nv21Frames.rotateClockwise(nv21, width, height, sensorOrientation);
                            width = turnedWidth;
                            height = turnedHeight;
                        }
                        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
                        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                        yuvImage.compressToJpeg(new android.graphics.Rect(0, 0, width, height), 80, outputStream);
                        byte[] jpegBytes = outputStream.toByteArray();
                        frameWidth = width;
                        frameHeight = height;
                        synchronized (this) {
                            currentFrame = jpegBytes;
                            frameSequence++;
                            notifyAll();  // Wake up anyone waiting for the next frame
                        }
                        yolo.requestInference();
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing image", e);
                    } finally {
                        image.close();
                    }
                }
            }, imageHandler);

            openingCamera = true;
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    Log.d(TAG, "onOpened: Camera opened");
                    openingCamera = false;
                    cameraDevice = camera;
                    startCapture(camera);
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    Log.w(TAG, "onDisconnected: Camera disconnected");
                    openingCamera = false;
                    camera.close();
                    cameraDevice = null;
                    scheduleReconnect();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "onError: Camera error " + error);
                    openingCamera = false;
                    camera.close();
                    cameraDevice = null;
                    scheduleReconnect();
                }
            }, cameraHandler);
        } catch (CameraAccessException | RuntimeException e) {
            // Some OEM builds throw an unchecked exception straight out of openCamera when the
            // camera is policy-blocked; retrying keeps the service alive either way
            openingCamera = false;
            Log.e(TAG, "startCamera: Could not open the camera, retrying", e);
            scheduleReconnect();
        }
    }

    private void startCapture(CameraDevice camera) {
        try {
            Surface imageReaderSurface = imageReader.getSurface();
            Log.d(TAG, "startCapture: ImageReader surface ready");

            CaptureRequest.Builder captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(imageReaderSurface);
            Log.d(TAG, "startCapture: Target surface added to CaptureRequest");

            // Enable auto-focus
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            Log.d(TAG, "startCapture: Auto-focus mode set");

            camera.createCaptureSession(Arrays.asList(imageReaderSurface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    Log.d(TAG, "onConfigured: Capture session configured, streaming " + frameSize);
                    try {
                        session.setRepeatingRequest(captureRequestBuilder.build(), null, cameraHandler);
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "onConfigured: CameraAccessException during capture session setup", e);
                        closeCamera();
                        scheduleReconnect();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "onConfigureFailed: Capture session configuration failed");
                    closeCamera();
                    scheduleReconnect();
                }
            }, cameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "startCapture: CameraAccessException during capture setup", e);
            closeCamera();
            scheduleReconnect();
        }
    }

    // The system takes the camera away in some situations (another camera app, policy changes);
    // the stream resumes by itself once it can be opened again.
    private void scheduleReconnect() {
        if (reconnectPending) {
            return;  // one attempt can trip several failure callbacks; a pile-up of retries would spin this thread
        }
        reconnectPending = true;
        Log.d(TAG, "scheduleReconnect: Retrying in " + RECONNECT_DELAY_MS + " ms");
        cameraHandler.postDelayed(() -> {
            reconnectPending = false;
            startCamera();
        }, RECONNECT_DELAY_MS);
    }

    private void closeCamera() {
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
            Log.d(TAG, "closeCamera: Camera device closed");
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    // The largest entry can be the full sensor size (4000x3000 on some phones), which is far too
    // heavy to convert and compress for every frame, so use the largest size up to about 720p.
    private static Size chooseFrameSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) {
            return null;  // the caller retries instead of crashing on an empty list
        }
        Size best = null;
        long bestPixels = 0;
        for (Size size : sizes) {
            long pixels = (long) size.getWidth() * size.getHeight();
            if (size.getWidth() <= MAX_FRAME_WIDTH && pixels <= MAX_FRAME_PIXELS && pixels > bestPixels) {
                best = size;
                bestPixels = pixels;
            }
        }
        return best != null ? best : sizes[sizes.length - 1];
    }

    private byte[] yuv420ToNV21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();

        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

        int yRowStride = image.getPlanes()[0].getRowStride();
        int uvRowStride = image.getPlanes()[1].getRowStride();
        int uvPixelStride = image.getPlanes()[1].getPixelStride();

        byte[] nv21 = new byte[width * height * 3 / 2];

        // Copy Y plane
        int pos = 0;
        for (int row = 0; row < height; row++) {
            int yRowStart = row * yRowStride;
            yBuffer.position(yRowStart);
            yBuffer.get(nv21, pos, width);
            pos += width;
        }

        // Interleave VU (not UV) for NV21
        int chromaHeight = height / 2;
        int chromaWidth = width / 2;
        for (int row = 0; row < chromaHeight; row++) {
            int uvRowStart = row * uvRowStride;
            for (int col = 0; col < chromaWidth; col++) {
                int index = uvRowStart + col * uvPixelStride;
                vBuffer.position(index);
                uBuffer.position(index);
                nv21[pos++] = vBuffer.get(); // V
                nv21[pos++] = uBuffer.get(); // U
            }
        }

        return nv21;
    }

    // ---------------------------------------------------------------- server

    private void startServer(int requestedPort) {
        if (server != null) {
            server.stop();
            Log.d(TAG, "startServer: NanoHTTPD server stopped");
        }

        server = new NanoHTTPD(requestedPort) {
            @Override
            public Response serve(IHTTPSession session) {
                // Each request runs on a pooled server thread: keep requests responsive even while YOLO is busy
                Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
                Log.d(TAG, "serve: Received request with URI " + session.getUri());
                switch (session.getUri()) {
                    case "/video":
                        return streamVideo();  // Call once per request
                    case "/snapshot":
                        return snapshotResponse(session);
                    case "/detections":
                        return detectionsResponse();
                    case "/settings":
                        return settingsResponse(session);
                    case "/docs":
                        return docsResponse();
                    case "/status":
                        return statusResponse();
                    default:
                        String response = "IP Webcam Running!\n/video       MJPEG stream\n"
                                + "/snapshot    latest frame as JPEG\n/detections  what YOLO last found, as JSON\n"
                                + "/settings    read the settings with GET, change them with POST\n"
                                + "/docs        the API document, as markdown\n"
                                + "/status      battery, screen, network and service status, as JSON\n";
                        return newFixedLengthResponse(response);
                }
            }
        };

        try {
            server.start();
            Log.d(TAG, "startServer: NanoHTTPD server started on port " + requestedPort);
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, "startServer: Failed to start NanoHTTPD server", e);
        }
    }

    private static final String SETTINGS_HELP = "the settings are portrait, yolo and draw_boxes, each true or false";

    /** The settings as JSON; a POST changes them first. The port is reported but can never be changed here. */
    private NanoHTTPD.Response settingsResponse(NanoHTTPD.IHTTPSession session) {
        NanoHTTPD.Method method = session.getMethod();
        if (NanoHTTPD.Method.OPTIONS == method) {
            // A browser asks this before a POST from a web page on another origin
            NanoHTTPD.Response preflight = newFixedLengthResponse(NanoHTTPD.Response.Status.NO_CONTENT, "text/plain", "");
            preflight.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            preflight.addHeader("Access-Control-Allow-Headers", "Content-Type");
            preflight.addHeader("Access-Control-Max-Age", "3600");
            return preflight;
        }
        String error = null;
        if (NanoHTTPD.Method.POST == method) {
            error = applySettings(session);
        } else if (NanoHTTPD.Method.GET != method) {
            error = "use GET to read the settings or POST to change them";
        }

        YoloEngine.State state = yoloState();
        String body;
        try {
            body = new JSONObject()
                    .put("port", port)
                    .put("portrait", portraitStream)
                    .put("yolo", isYoloEnabled())
                    .put("draw_boxes", drawBoxes)
                    .put("yolo_status", state.status)
                    .put("model", yolo == null ? "" : yolo.model())
                    .put("error", error == null ? JSONObject.NULL : error)
                    .toString();
        } catch (JSONException e) {
            Log.e(TAG, "settingsResponse: Could not build the JSON", e);
            body = "{\"error\": \"could not build the response\"}";
            error = body;
        }
        NanoHTTPD.Response response = newFixedLengthResponse(
                error == null ? NanoHTTPD.Response.Status.OK : NanoHTTPD.Response.Status.BAD_REQUEST,
                "application/json", body);
        response.addHeader("Cache-Control", "no-store");
        response.addHeader("Access-Control-Allow-Origin", "*");
        return response;
    }

    /**
     * Applies the settings in a POST and returns null, or a message saying what is wrong. Query
     * parameters, a form body and a JSON body are all accepted. Nothing is changed unless the
     * whole request is valid.
     */
    private String applySettings(NanoHTTPD.IHTTPSession session) {
        Map<String, String> values = new HashMap<>(session.getParms());  // starts with the query string
        try {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);  // adds form fields to the parameters
            values.putAll(session.getParms());
            String json = files.get("postData");
            if (json != null && !json.trim().isEmpty()) {
                JSONObject requestBody = new JSONObject(json.trim());
                Iterator<String> keys = requestBody.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    values.put(key, String.valueOf(requestBody.get(key)));
                }
            }
        } catch (IOException | JSONException | NanoHTTPD.ResponseException | RuntimeException e) {
            return "could not read the request body: " + e.getMessage();
        }

        // Check everything before changing anything
        Boolean portraitWanted = null;
        Boolean yoloWanted = null;
        Boolean drawBoxesWanted = null;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            Boolean value = parseBoolean(entry.getValue());
            switch (entry.getKey()) {
                case "port":
                    if (!entry.getValue().trim().equals(String.valueOf(port))) {
                        return "port cannot be changed through /settings, it stays " + port;
                    }
                    break;
                case "portrait":
                    if (value == null) {
                        return notBoolean(entry);
                    }
                    portraitWanted = value;
                    break;
                case "yolo":
                    if (value == null) {
                        return notBoolean(entry);
                    }
                    yoloWanted = value;
                    break;
                case "draw_boxes":
                    if (value == null) {
                        return notBoolean(entry);
                    }
                    drawBoxesWanted = value;
                    break;
                default:
                    return "unknown setting '" + entry.getKey() + "'; " + SETTINGS_HELP;
            }
        }
        boolean nextYolo = yoloWanted != null ? yoloWanted : isYoloEnabled();
        // Boxes only exist while YOLO runs, so they go off together with it
        boolean nextDrawBoxes = drawBoxesWanted != null ? drawBoxesWanted : drawBoxes && nextYolo;
        if (nextDrawBoxes && !nextYolo) {
            return "draw_boxes needs yolo switched on";
        }

        if (portraitWanted != null) {
            portraitStream = portraitWanted;
        }
        setYoloEnabled(nextYolo);
        drawBoxes = nextDrawBoxes;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_PORTRAIT, portraitStream)
                .putBoolean(KEY_YOLO, nextYolo)
                .putBoolean(KEY_DRAW_BOXES, nextDrawBoxes)
                .apply();
        Log.d(TAG, "applySettings: portrait stream " + (portraitStream ? "on" : "off") + ", YOLO "
                + (nextYolo ? "on" : "off") + ", draw boxes " + (nextDrawBoxes ? "on" : "off"));
        return null;
    }

    private static String notBoolean(Map.Entry<String, String> entry) {
        return "'" + entry.getValue() + "' is not a value for " + entry.getKey() + "; " + SETTINGS_HELP;
    }

    /** true / 1 / on / yes and false / 0 / off / no, or null when the text is none of them. */
    private static Boolean parseBoolean(String text) {
        switch (text.trim().toLowerCase(Locale.US)) {
            case "true":
            case "1":
            case "on":
            case "yes":
                return true;
            case "false":
            case "0":
            case "off":
            case "no":
                return false;
            default:
                return null;
        }
    }

    /** The newest frame as JPEG. With {@code ?inferred} it is the frame YOLO's last result came from. */
    private NanoHTTPD.Response snapshotResponse(NanoHTTPD.IHTTPSession session) {
        byte[] frame;
        long sequence;
        if (session.getParameters().containsKey("inferred")) {
            YoloEngine.State state = yoloState();
            frame = state.frame;
            sequence = state.frameSequence;
        } else {
            synchronized (this) {
                frame = currentFrame;
                sequence = frameSequence;
            }
        }
        if (frame == null) {
            return newFixedLengthResponse(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, "text/plain", "No frame yet");
        }
        NanoHTTPD.Response response = newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "image/jpeg",
                new ByteArrayInputStream(frame), frame.length);
        response.addHeader("X-Frame-Seq", String.valueOf(sequence));
        response.addHeader("Cache-Control", "no-store");
        response.addHeader("Access-Control-Allow-Origin", "*");
        return response;
    }

    /** What YOLO last found. Boxes are in frame pixels, so they line up with /video and /snapshot. */
    private NanoHTTPD.Response detectionsResponse() {
        YoloEngine.State state = yoloState();
        String body;
        try {
            JSONObject root = new JSONObject();
            root.put("enabled", isYoloEnabled());
            root.put("status", state.status);
            root.put("detail", state.detail);
            root.put("model", yolo == null ? "" : yolo.model());
            root.put("timestamp", System.currentTimeMillis());
            root.put("frame_seq", state.frameSequence);
            root.put("width", frameWidth);
            root.put("height", frameHeight);
            root.put("latency_ms", state.latencyMs);
            root.put("fps", (double) state.inferencesPerSecond);
            JSONArray detections = new JSONArray();
            for (Detection detection : state.detections) {
                detections.put(new JSONObject()
                        .put("class_id", detection.classId)
                        .put("label", detection.label)
                        .put("confidence", (double) detection.confidence)
                        .put("box", new JSONArray(new double[]{detection.x1, detection.y1, detection.x2, detection.y2})));
            }
            root.put("detections", detections);
            body = root.toString();
        } catch (JSONException e) {
            Log.e(TAG, "detectionsResponse: Could not build the JSON", e);
            body = "{\"status\": \"error\", \"detail\": \"could not build the response\"}";
        }
        NanoHTTPD.Response response = newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", body);
        response.addHeader("Cache-Control", "no-store");
        response.addHeader("Access-Control-Allow-Origin", "*");
        return response;
    }

    private NanoHTTPD.Response streamVideo() {
        Log.d(TAG, "streamVideo: Streaming video");
        return newChunkedResponse(NanoHTTPD.Response.Status.OK, "multipart/x-mixed-replace; boundary=" + BOUNDARY, new MjpegStream());
    }

    /** The API documentation, bundled from the repo's docs/API.md at build time. */
    private NanoHTTPD.Response docsResponse() {
        byte[] markdown;
        try (InputStream input = getAssets().open("API.md")) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = input.read(chunk)) > 0) {
                buffer.write(chunk, 0, count);
            }
            markdown = buffer.toByteArray();
        } catch (IOException e) {
            Log.e(TAG, "docsResponse: Could not read the bundled API.md", e);
            return newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain",
                    "the API document is not bundled in this build");
        }
        NanoHTTPD.Response response = newFixedLengthResponse(NanoHTTPD.Response.Status.OK,
                "text/markdown; charset=utf-8", new ByteArrayInputStream(markdown), markdown.length);
        response.addHeader("Access-Control-Allow-Origin", "*");
        return response;
    }

    /** Device and service state as JSON: battery, charging, temperature, screen, Wi-Fi, uptime. */
    private NanoHTTPD.Response statusResponse() {
        JSONObject body = new JSONObject();
        try {
            JSONObject device = new JSONObject()
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL)
                    .put("android", Build.VERSION.RELEASE)
                    .put("sdk", Build.VERSION.SDK_INT);

            // The sticky battery broadcast is the system's latest state; no receiver needed
            Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            JSONObject batteryJson = new JSONObject();
            if (battery != null) {
                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int temperature = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
                batteryJson.put("percent", level >= 0 && scale > 0 ? Math.round(level * 100.0f / scale) : JSONObject.NULL)
                        .put("charging", battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                                == BatteryManager.BATTERY_STATUS_CHARGING)
                        .put("status", batteryStatusName(battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)))
                        .put("plugged", pluggedName(battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)))
                        .put("health", batteryHealthName(battery.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)))
                        .put("temperature_c", temperature < 0 ? JSONObject.NULL : temperature / 10.0)
                        .put("voltage_mv", battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1))
                        .put("technology", battery.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY));
            }

            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            JSONObject thermal = new JSONObject().put("status",
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            ? thermalStatusName(powerManager.getCurrentThermalStatus()) : "unknown");

            JSONObject wifi = new JSONObject().put("ip", getLocalIpAddress());
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            WifiInfo info = wifiManager == null ? null : wifiManager.getConnectionInfo();
            if (info != null) {
                wifi.put("rssi_dbm", info.getRssi())
                        .put("link_speed_mbps", info.getLinkSpeed())
                        .put("frequency_mhz", info.getFrequency());
            }

            long sequence;
            synchronized (this) {
                sequence = frameSequence;
            }

            body.put("device", device)
                    .put("battery", batteryJson)
                    .put("thermal", thermal)
                    .put("screen", powerManager.isInteractive() ? "on" : "off")
                    .put("wifi", wifi)
                    .put("uptime_s", SystemClock.elapsedRealtime() / 1000)
                    .put("service", new JSONObject()
                            .put("port", port)
                            .put("uptime_s", (SystemClock.elapsedRealtime() - startedElapsed) / 1000)
                            .put("frames_captured", sequence)
                            .put("frame_width", frameWidth)
                            .put("frame_height", frameHeight))
                    .put("timestamp", System.currentTimeMillis());
        } catch (JSONException e) {
            Log.e(TAG, "statusResponse: Could not build the JSON", e);
            try {
                body = new JSONObject().put("error", "could not build the response");
            } catch (JSONException ignored) {
            }
        }
        NanoHTTPD.Response response = newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", body.toString());
        response.addHeader("Cache-Control", "no-store");
        response.addHeader("Access-Control-Allow-Origin", "*");
        return response;
    }

    private static String batteryStatusName(int status) {
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "charging";
            case BatteryManager.BATTERY_STATUS_FULL: return "full";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "discharging";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "not charging";
            default: return "unknown";
        }
    }

    private static String pluggedName(int plugged) {
        switch (plugged) {
            case BatteryManager.BATTERY_PLUGGED_AC: return "ac";
            case BatteryManager.BATTERY_PLUGGED_USB: return "usb";
            case BatteryManager.BATTERY_PLUGGED_WIRELESS: return "wireless";
            case BatteryManager.BATTERY_PLUGGED_DOCK: return "dock";
            default: return "none";
        }
    }

    private static String batteryHealthName(int health) {
        switch (health) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "good";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "overheat";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "dead";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "over voltage";
            case BatteryManager.BATTERY_HEALTH_COLD: return "cold";
            default: return "unknown";  // UNSPECIFIED_FAILURE and anything else
        }
    }

    private static String thermalStatusName(int status) {
        switch (status) {
            case PowerManager.THERMAL_STATUS_NONE: return "none";
            case PowerManager.THERMAL_STATUS_LIGHT: return "light";
            case PowerManager.THERMAL_STATUS_MODERATE: return "moderate";
            case PowerManager.THERMAL_STATUS_SEVERE: return "severe";
            case PowerManager.THERMAL_STATUS_CRITICAL: return "critical";
            case PowerManager.THERMAL_STATUS_EMERGENCY: return "emergency";
            case PowerManager.THERMAL_STATUS_SHUTDOWN: return "shutdown";
            default: return "unknown";
        }
    }

    /**
     * Serves the stream to one viewer. NanoHTTPD pulls from this on its own client thread, so
     * read() blocks until a frame that this viewer has not seen yet has been captured, and is
     * woken up as soon as that happens. While the camera is unavailable nothing is sent, rather
     * than repeating a stale frame.
     */
    private final class MjpegStream extends InputStream {
        private static final long WAIT_MILLIS = 1000;  // safety net in case a wake-up is missed

        private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
        private int chunkOffset;
        private long sentSequence;  // 0 = nothing seen yet; frame numbers start at 1
        private boolean closed;     // guarded by the service lock

        @Override
        public int read() throws IOException {
            byte[] oneByte = new byte[1];
            int count = read(oneByte, 0, 1);
            return count < 0 ? -1 : oneByte[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            try {
                if (chunks.isEmpty()) {
                    byte[] frame = awaitNextFrame();
                    if (frame == null) {
                        return -1;  // The viewer has gone away
                    }
                    queue(frame);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
            byte[] chunk = chunks.getFirst();
            int count = Math.min(length, chunk.length - chunkOffset);
            System.arraycopy(chunk, chunkOffset, buffer, offset, count);
            chunkOffset += count;
            if (chunkOffset == chunk.length) {
                chunks.removeFirst();
                chunkOffset = 0;
            }
            return count;
        }

        @Override
        public int available() {
            return chunks.isEmpty() ? 0 : chunks.getFirst().length - chunkOffset;
        }

        @Override
        public void close() {
            synchronized (CameraStreamService.this) {
                closed = true;
                CameraStreamService.this.notifyAll();
            }
        }

        /** Waits for the newest frame this viewer has not been sent, or null once it is closed. */
        private byte[] awaitNextFrame() throws InterruptedException {
            synchronized (CameraStreamService.this) {
                while (frameSequence == sentSequence) {
                    if (closed) {
                        return null;
                    }
                    CameraStreamService.this.wait(WAIT_MILLIS);
                }
                sentSequence = frameSequence;
                return currentFrame;
            }
        }

        private void queue(byte[] frame) {
            chunks.addLast(("--" + BOUNDARY + "\r\n").getBytes());
            chunks.addLast("Content-Type: image/jpeg\r\n".getBytes());
            chunks.addLast(("Content-Length: " + frame.length + "\r\n").getBytes());
            chunks.addLast("\r\n".getBytes());
            chunks.addLast(frame);
            chunks.addLast("\r\n".getBytes());
        }
    }

    private String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface networkInterface = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = networkInterface.getInetAddresses();
                     enumIpAddr.hasMoreElements(); ) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress.getHostAddress().length() < 16) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
            Log.e(TAG, "getLocalIpAddress: SocketException", e);
        }
        return null;
    }
}
