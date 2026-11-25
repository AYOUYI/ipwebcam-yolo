package com.example.ipwebcamapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/**
 * Runs a YOLO model exported to ONNX on JPEG frames. All calls must come from one thread.
 *
 * <p>The bundled COCO model is used unless {@code yolo/model.onnx} exists in the app's files
 * directory (external, or internal), optionally with a {@code yolo/classes.txt} holding one label
 * per line. A {@code yolo/runtime.txt} of {@code key=value} lines can set {@code threads} (how
 * many CPU threads inference may use) and {@code ep} ({@code cpu} or {@code xnnpack}).
 */
final class YoloDetector implements Closeable {

    private static final String TAG = "IPWebcamApp";
    private static final String BUNDLED_MODEL = "yolo11n_416.onnx";
    private static final String CUSTOM_DIR = "yolo";
    private static final String RUNTIME_OPTIONS = "runtime.txt";
    // Two threads was as fast as four on a 2-big-core + 6-little-core phone (2.6 runs/s either
    // way) while using half the CPU, and left more of it to the camera pipeline
    private static final int DEFAULT_THREADS = 2;
    private static final String DEFAULT_PROVIDER = "cpu";
    private static final int PAD_COLOR = 0xFF727272;  // Ultralytics pads with grey 114

    /** What one run found. Boxes are in frame pixels, in the frame the JPEG was taken from. */
    static final class Result {
        final List<Detection> detections;
        final long latencyMs;

        Result(List<Detection> detections, long latencyMs) {
            this.detections = detections;
            this.latencyMs = latencyMs;
        }
    }

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;
    private final String modelName;
    private final String provider;
    private final int threads;
    private final String[] labels;
    private final int inputSize;
    private final float[] networkInput;   // reused NCHW buffer
    private final int[] pixels;           // reused letterboxed pixel buffer
    private float[] output;               // reused once the output size is known

    static YoloDetector load(Context context) throws OrtException, IOException {
        File customDir = customDir(context);
        File customModel = new File(customDir, "model.onnx");
        boolean custom = customModel.isFile();
        byte[] model = readAll(custom ? new FileInputStream(customModel) : context.getAssets().open(BUNDLED_MODEL));
        String[] labels = readLabels(new File(customDir, "classes.txt"));
        String modelName = custom ? customModel.getName() : BUNDLED_MODEL;
        Map<String, String> runtime = readOptions(new File(customDir, RUNTIME_OPTIONS));
        int threads = positiveInt(runtime.get("threads"), DEFAULT_THREADS);
        String provider = runtime.getOrDefault("ep", DEFAULT_PROVIDER);
        Log.i(TAG, "YoloDetector.load: Loading " + (custom ? "custom model" : "bundled model") + " " + modelName
                + " (" + model.length / 1024 / 1024 + " MB, " + labels.length + " classes), "
                + provider + ", " + threads + " threads");

        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(threads);
            if ("xnnpack".equals(provider)) {
                try {
                    options.addXnnpack(Collections.<String, String>emptyMap());
                } catch (OrtException e) {
                    Log.w(TAG, "YoloDetector.load: XNNPACK is not available in this build, using the CPU instead");
                    provider = DEFAULT_PROVIDER;
                }
            }
            OrtSession session = environment.createSession(model, options);
            return new YoloDetector(environment, session, modelName, provider, threads, labels);
        }
    }

    private YoloDetector(OrtEnvironment environment, OrtSession session, String modelName, String provider,
                         int threads, String[] labels) throws OrtException {
        this.environment = environment;
        this.session = session;
        this.modelName = modelName;
        this.provider = provider;
        this.threads = threads;
        this.labels = labels;

        inputName = session.getInputNames().iterator().next();
        NodeInfo inputInfo = session.getInputInfo().get(inputName);
        long[] inputShape = ((TensorInfo) inputInfo.getInfo()).getShape();
        if (inputShape.length != 4 || inputShape[2] <= 0 || inputShape[3] <= 0) {
            throw new OrtException("The model needs a fixed square input, its input shape is "
                    + Arrays.toString(inputShape));
        }
        inputSize = (int) inputShape[2];
        networkInput = new float[3 * inputSize * inputSize];
        pixels = new int[inputSize * inputSize];
        Log.i(TAG, "YoloDetector: Ready, input " + inputSize + "x" + inputSize + ", output shape "
                + Arrays.toString(outputShape()));
    }

    /** Short description for the API and the log, such as "yolo11n_416.onnx (416, cpu, 4 threads)". */
    String describe() {
        return modelName + " (" + inputSize + ", " + provider + ", " + threads + " threads)";
    }

    private long[] outputShape() throws OrtException {
        String firstOutput = session.getOutputNames().iterator().next();
        return ((TensorInfo) session.getOutputInfo().get(firstOutput).getInfo()).getShape();
    }

    /** Finds the objects in a JPEG frame. {@code frameWidth} and {@code frameHeight} are its pixel size. */
    Result detect(byte[] jpeg, int frameWidth, int frameHeight) throws OrtException {
        long started = SystemClock.elapsedRealtime();

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(frameWidth, frameHeight);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap frame = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
        if (frame == null) {
            throw new IllegalStateException("Could not decode the frame");
        }

        // Letterbox: shrink the frame keeping its aspect ratio, then centre it on grey padding
        YoloDecoder.Letterbox letterbox = new YoloDecoder.Letterbox(inputSize, frame.getWidth(), frame.getHeight());
        Bitmap scaled = Bitmap.createScaledBitmap(frame, letterbox.scaledWidth(), letterbox.scaledHeight(), true);
        if (scaled != frame) {
            frame.recycle();
        }
        Arrays.fill(pixels, PAD_COLOR);
        scaled.getPixels(pixels, letterbox.padY * inputSize + letterbox.padX, inputSize, 0, 0,
                letterbox.scaledWidth(), letterbox.scaledHeight());
        scaled.recycle();

        int plane = inputSize * inputSize;
        for (int i = 0; i < plane; i++) {
            int pixel = pixels[i];
            networkInput[i] = ((pixel >> 16) & 0xFF) / 255f;
            networkInput[plane + i] = ((pixel >> 8) & 0xFF) / 255f;
            networkInput[2 * plane + i] = (pixel & 0xFF) / 255f;
        }

        List<Detection> detections;
        long[] inputShape = {1, 3, inputSize, inputSize};
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(networkInput), inputShape);
             OrtSession.Result result = session.run(Collections.singletonMap(inputName, tensor))) {
            OnnxValue value = result.get(0);
            FloatBuffer buffer = ((OnnxTensor) value).getFloatBuffer();
            float[] raw = copyOf(buffer);
            detections = YoloDecoder.decode(raw, ((OnnxTensor) value).getInfo().getShape(), labels,
                    inputSize, YoloDecoder.DEFAULT_CONFIDENCE);
        }

        List<Detection> inFrame = new ArrayList<>(detections.size());
        for (Detection detection : detections) {
            inFrame.add(letterbox.toFrame(detection));
        }
        return new Result(inFrame, SystemClock.elapsedRealtime() - started);
    }

    private float[] copyOf(FloatBuffer buffer) {
        int size = buffer.remaining();
        if (output == null || output.length != size) {
            output = new float[size];
        }
        buffer.get(output);
        return output;
    }

    /** Largest power-of-two reduction that still leaves the short side no smaller than the input. */
    private int sampleSize(int frameWidth, int frameHeight) {
        int sampleSize = 1;
        while (Math.min(frameWidth, frameHeight) / (sampleSize * 2) >= inputSize) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    /** The first existing {@code yolo/} directory, preferring the external one; never null. */
    private static File customDir(Context context) {
        File external = context.getExternalFilesDir(null);
        if (external != null && new File(external, CUSTOM_DIR).isDirectory()) {
            return new File(external, CUSTOM_DIR);
        }
        return new File(context.getFilesDir(), CUSTOM_DIR);
    }

    private static Map<String, String> readOptions(File file) throws IOException {
        Map<String, String> options = new HashMap<>();
        if (!file.isFile()) {
            return options;
        }
        for (String line : new String(readAll(new FileInputStream(file)), StandardCharsets.UTF_8).split("\\r?\\n")) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                options.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
            }
        }
        return options;
    }

    private static int positiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String[] readLabels(File classesFile) throws IOException {
        if (!classesFile.isFile()) {
            return CocoLabels.NAMES;
        }
        List<String> labels = new ArrayList<>();
        for (String line : new String(readAll(new FileInputStream(classesFile)), StandardCharsets.UTF_8).split("\\r?\\n")) {
            if (!line.trim().isEmpty()) {
                labels.add(line.trim());
            }
        }
        return labels.toArray(new String[0]);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = stream.read(buffer)) != -1; ) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    @Override
    public void close() {
        try {
            session.close();
            environment.close();
        } catch (OrtException e) {
            Log.e(TAG, "close: " + e.getMessage());
        }
    }
}
