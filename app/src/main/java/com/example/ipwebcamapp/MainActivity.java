package com.example.ipwebcamapp;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "IPWebcamApp";
    private static final int CAMERA_REQUEST_CODE = 50;
    private static final int NOTIFICATION_REQUEST_CODE = 60;
    private static final int SETTINGS_REQUEST_CODE = 100; // Unique request code for settings activity
    private static final long PREVIEW_INTERVAL_MS = 100;

    private ImageView previewView;
    private TextView ipTextView;
    private TextView yoloStatusView;
    private final Handler previewHandler = new Handler(Looper.getMainLooper());
    private final long[] previewSequence = {-1};
    private final DetectionOverlay overlay = new DetectionOverlay();
    private String lastStatus;
    private String lastYoloStatus;

    private CameraStreamService streamService;
    private boolean bound;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            streamService = ((CameraStreamService.LocalBinder) service).getService();
            Log.d(TAG, "onServiceConnected: Bound to the stream service");
            updateStatus();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            streamService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.d(TAG, "onCreate: Activity created");

        previewView = findViewById(R.id.previewView);
        ipTextView = findViewById(R.id.ipPortTextView);
        yoloStatusView = findViewById(R.id.yoloStatusView);

        Button settingsButton = findViewById(R.id.settingsButton);
        settingsButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivityForResult(intent, SETTINGS_REQUEST_CODE);
        });

        Button stopButton = findViewById(R.id.stopButton);
        stopButton.setOnClickListener(v -> {
            stopService(new Intent(MainActivity.this, CameraStreamService.class));
            Log.d(TAG, "Stop button: Stream service stopped");
        });

        checkCameraPermission();
        Log.d(TAG, "onCreate: Checked camera permissions");
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindStreamService();
        previewHandler.post(showFrame);
    }

    @Override
    protected void onStop() {
        previewHandler.removeCallbacks(showFrame);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        streamService = null;
        super.onDestroy();
    }

    // The camera, the server and YOLO live in CameraStreamService, which keeps working whether or
    // not this screen is visible; the activity only starts it and shows what it is producing.
    private void bindStreamService() {
        // Flags 0: attach to the service if it is running, but never create it from here
        bound = bindService(new Intent(this, CameraStreamService.class), connection, 0);
        Log.d(TAG, "bindStreamService: " + (bound ? "bound" : "service not running"));
    }

    private void startStreamService() {
        ContextCompat.startForegroundService(this, new Intent(this, CameraStreamService.class));
        Log.d(TAG, "startStreamService: Stream service started");
        if (!bound) {
            bindStreamService();
        }
        requestNotificationPermission();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST_CODE);
        }
    }

    private void checkCameraPermission() {
        Log.d(TAG, "checkCameraPermission: Checking camera permissions");
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "checkCameraPermission: Camera permission not granted, requesting permission");
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST_CODE);
        } else {
            Log.d(TAG, "checkCameraPermission: Camera permission already granted");
            startStreamService();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Log.d(TAG, "onRequestPermissionsResult: Received result for request code " + requestCode);
        if (requestCode == CAMERA_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "onRequestPermissionsResult: Camera permission granted");
                startStreamService();
            } else {
                Log.e(TAG, "onRequestPermissionsResult: Camera permission denied");
                ipTextView.setText(R.string.camera_permission_denied);
            }
        }
    }

    private final Runnable showFrame = new Runnable() {
        @Override
        public void run() {
            if (!bound) {
                bindStreamService();
            }
            CameraStreamService service = streamService;
            if (service != null) {
                byte[] frame = service.latestFrameSince(previewSequence);
                if (frame != null) {
                    List<Detection> detections = service.isDrawBoxesEnabled()
                            ? service.yoloState().detections
                            : Collections.<Detection>emptyList();
                    showPreview(frame, detections, service);
                }
                updateStatus();
                updateYoloStatus(service);
            }
            previewHandler.postDelayed(this, PREVIEW_INTERVAL_MS);
        }
    };

    /**
     * Shows the frame exactly as it is streamed, so what is on screen has the same orientation as
     * /video, /snapshot and the detection boxes, whichever way the stream setting is set.
     */
    private void showPreview(byte[] frame, List<Detection> detections, CameraStreamService service) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 2;  // A quarter of the pixels is plenty for the on-screen preview
        Bitmap bitmap = BitmapFactory.decodeByteArray(frame, 0, frame.length, options);
        if (bitmap == null) {
            return;
        }
        bitmap = overlay.draw(bitmap, detections, service.getFrameWidth(), service.getFrameHeight(),
                getResources().getDisplayMetrics().density);
        previewView.setImageBitmap(bitmap);
    }

    private void updateStatus() {
        String status = getString(R.string.connect_to, streamService.getStreamUrl());
        if (!streamService.isCameraOpen()) {
            status += " " + getString(R.string.camera_off);
        }
        if (!status.equals(lastStatus)) {
            lastStatus = status;
            ipTextView.setText(status);
            Log.d(TAG, "updateStatus: Displayed as " + status);
        }
    }

    /** One line under the address while YOLO is switched on: loading, how fast it runs, or the error. */
    private void updateYoloStatus(CameraStreamService service) {
        if (!service.isYoloEnabled()) {
            if (lastYoloStatus != null) {
                lastYoloStatus = null;
                yoloStatusView.setVisibility(View.GONE);
            }
            return;
        }
        YoloEngine.State state = service.yoloState();
        String status;
        switch (state.status) {
            case YoloEngine.STATUS_LOADING:
                status = getString(R.string.yolo_status_loading);
                break;
            case YoloEngine.STATUS_RUNNING:
                status = getString(R.string.yolo_status_running, (double) state.inferencesPerSecond,
                        state.latencyMs, state.detections.size());
                break;
            case YoloEngine.STATUS_ERROR:
                status = getString(R.string.yolo_status_error, state.detail);
                break;
            default:
                return;
        }
        if (!status.equals(lastYoloStatus)) {
            lastYoloStatus = status;
            yoloStatusView.setText(status);
            yoloStatusView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SETTINGS_REQUEST_CODE && resultCode == RESULT_OK) {
            boolean portrait = streamService != null && streamService.isPortraitStream();
            boolean yolo = streamService != null && streamService.isYoloEnabled();
            boolean drawBoxes = streamService != null && streamService.isDrawBoxesEnabled();
            Log.d(TAG, "onActivityResult: Settings saved, YOLO " + (yolo ? "on" : "off")
                    + ", draw boxes " + (drawBoxes ? "on" : "off"));
            ContextCompat.startForegroundService(this, new Intent(this, CameraStreamService.class)
                    .putExtra(CameraStreamService.EXTRA_PORT, data.getIntExtra("port", 0))
                    .putExtra(CameraStreamService.EXTRA_PORTRAIT, data.getBooleanExtra(CameraStreamService.EXTRA_PORTRAIT, portrait))
                    .putExtra(CameraStreamService.EXTRA_YOLO, data.getBooleanExtra(CameraStreamService.EXTRA_YOLO, yolo))
                    .putExtra(CameraStreamService.EXTRA_DRAW_BOXES, data.getBooleanExtra(CameraStreamService.EXTRA_DRAW_BOXES, drawBoxes)));
        }
    }
}
