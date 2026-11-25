package com.example.ipwebcamapp;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import ai.onnxruntime.OrtException;

/**
 * Runs YOLO on the newest frame whenever the previous run has finished, and publishes what the
 * last run found. All inference happens on one background thread, so while a run is in progress
 * new frames are dropped rather than queued and the results never fall behind the camera.
 */
final class YoloEngine {

    private static final String TAG = "IPWebcamApp";
    private static final long MIN_INTERVAL_MS = 150;   // caps the rate near 6 runs per second
    private static final long RETRY_DELAY_MS = 3000;

    static final String STATUS_OFF = "off";
    static final String STATUS_LOADING = "loading";
    static final String STATUS_RUNNING = "running";
    static final String STATUS_ERROR = "error";

    /** Supplies the frame to run on. Safe to call from any thread. */
    interface FrameSource {
        /** The newest frame, writing its sequence number into {@code sequenceOut[0]}; null if there is none yet. */
        byte[] latestFrame(long[] sequenceOut);

        int frameWidth();

        int frameHeight();
    }

    /** What YOLO last reported. Replaced wholesale, never modified after it is published. */
    static final class State {
        static final State OFF = new State(STATUS_OFF, "", Collections.<Detection>emptyList(), 0, 0, 0, null, 0);

        final String status;
        final String detail;               // the error message when the status is "error"
        final List<Detection> detections;  // in frame pixels
        final long frameSequence;          // the frame the detections belong to
        final long finishedAt;             // elapsedRealtime() when the run finished, 0 if it failed
        final long latencyMs;
        final byte[] frame;                // that frame, as JPEG
        final float inferencesPerSecond;

        State(String status, String detail, List<Detection> detections, long frameSequence, long finishedAt,
              long latencyMs, byte[] frame, float inferencesPerSecond) {
            this.status = status;
            this.detail = detail;
            this.detections = detections;
            this.frameSequence = frameSequence;
            this.finishedAt = finishedAt;
            this.latencyMs = latencyMs;
            this.frame = frame;
            this.inferencesPerSecond = inferencesPerSecond;
        }
    }

    private final Context context;
    private final FrameSource source;
    private final HandlerThread thread;
    private final Handler handler;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile boolean enabled;
    private volatile State state = State.OFF;
    private volatile String model = "";       // which model and runtime the results come from
    private volatile YoloDetector detector;   // loaded, used and closed only on the YOLO thread
    private boolean loading;
    private long lastFinishedAt;
    private float intervalMs;                 // smoothed time between runs

    YoloEngine(Context context, FrameSource source) {
        this.context = context.getApplicationContext();
        this.source = source;
        // Background priority: the camera pipeline and the web server keep the CPU first, and
        // inference uses what is left. ONNX Runtime's own worker threads inherit this priority.
        thread = new HandlerThread("Yolo", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    boolean isEnabled() {
        return enabled;
    }

    State state() {
        return state;
    }

    /** Which model and runtime is in use, for example "yolo11n_416.onnx (416, cpu, 4 threads)". */
    String model() {
        return model;
    }

    void setEnabled(boolean value) {
        enabled = value;
        if (!value) {
            state = State.OFF;
            Log.d(TAG, "YoloEngine: Disabled");
            return;
        }
        Log.d(TAG, "YoloEngine: Enabled");
        if (state.status.equals(STATUS_OFF)) {
            state = new State(STATUS_LOADING, "", Collections.<Detection>emptyList(), 0, 0, 0, null, 0);
        }
        if (detector == null && !loading) {
            loading = true;
            handler.post(this::load);
        }
    }

    private void load() {
        try {
            long started = SystemClock.elapsedRealtime();
            detector = YoloDetector.load(context);
            model = detector.describe();
            Log.i(TAG, "YoloEngine: Model ready in " + (SystemClock.elapsedRealtime() - started) + " ms: " + model);
        } catch (OrtException | IOException | RuntimeException e) {
            Log.e(TAG, "YoloEngine: Could not load the model", e);
            state = new State(STATUS_ERROR, String.valueOf(e.getMessage()),
                    Collections.<Detection>emptyList(), 0, 0, 0, null, 0);
        } finally {
            loading = false;
        }
    }

    /** Called whenever a new frame is ready; starts a run unless one is already going. */
    void requestInference() {
        if (!enabled || detector == null || busy.get()
                || SystemClock.elapsedRealtime() - lastFinishedAt < MIN_INTERVAL_MS) {
            return;
        }
        if (busy.compareAndSet(false, true)) {
            handler.post(this::infer);
        }
    }

    private void infer() {
        try {
            long[] sequence = new long[1];
            byte[] frame = source.latestFrame(sequence);
            if (frame == null) {
                return;
            }
            YoloDetector.Result result = detector.detect(frame, source.frameWidth(), source.frameHeight());
            if (!enabled) {
                return;  // switched off while this run was going: leave the "off" state in place
            }
            long finished = SystemClock.elapsedRealtime();
            if (lastFinishedAt > 0) {
                float interval = finished - lastFinishedAt;
                intervalMs = intervalMs == 0 ? interval : intervalMs * 0.8f + interval * 0.2f;
            }
            lastFinishedAt = finished;
            state = new State(STATUS_RUNNING, "", result.detections, sequence[0], finished, result.latencyMs,
                    frame, intervalMs == 0 ? 0 : 1000f / intervalMs);
        } catch (OrtException | RuntimeException e) {
            Log.e(TAG, "infer: Inference failed", e);
            state = new State(STATUS_ERROR, String.valueOf(e.getMessage()),
                    Collections.<Detection>emptyList(), 0, 0, 0, null, 0);
            lastFinishedAt = SystemClock.elapsedRealtime() + RETRY_DELAY_MS;  // give it a while before trying again
        } finally {
            busy.set(false);
        }
    }

    void shutdown() {
        handler.post(() -> {
            if (detector != null) {
                detector.close();
                detector = null;
            }
        });
        thread.quitSafely();
    }
}
