package com.example.ipwebcamapp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Turns the raw output of an Ultralytics YOLO ONNX export into detections. Plain Java with no
 * Android or ONNX dependencies, so it can be unit tested on a desktop JVM.
 *
 * <p>Two output layouts are recognised:
 * <ul>
 *   <li>YOLOv8, YOLO11 and later: {@code [1, 4 + classes, anchors]}, channels first. Every anchor
 *       holds cx, cy, w, h in input-image pixels followed by one probability per class.</li>
 *   <li>YOLOv5: {@code [1, anchors, 5 + classes]}, anchors first. Every anchor holds cx, cy, w, h,
 *       an objectness score and one score per class.</li>
 * </ul>
 */
final class YoloDecoder {

    static final float DEFAULT_CONFIDENCE = 0.30f;
    static final float DEFAULT_IOU = 0.45f;
    static final int MAX_DETECTIONS = 30;

    private YoloDecoder() {
    }

    enum Layout {
        CHANNELS_FIRST,
        ANCHORS_FIRST
    }

    static Layout layoutOf(long[] shape) {
        return shape[1] < shape[2] ? Layout.CHANNELS_FIRST : Layout.ANCHORS_FIRST;
    }

    static int classCount(long[] shape) {
        return layoutOf(shape) == Layout.CHANNELS_FIRST ? (int) shape[1] - 4 : (int) shape[2] - 5;
    }

    /**
     * Decodes one output tensor. Boxes come back in input-image pixels, clipped to the input and
     * ordered by falling confidence.
     */
    static List<Detection> decode(float[] raw, long[] shape, String[] labels, int inputSize, float confidenceThreshold) {
        boolean channelsFirst = layoutOf(shape) == Layout.CHANNELS_FIRST;
        int channels = (int) shape[1];
        int anchors = (int) shape[2];
        int classes = classCount(shape);

        List<Detection> candidates = new ArrayList<>();
        for (int anchor = 0; anchor < anchors; anchor++) {
            int bestClass = -1;
            float bestScore = 0;
            for (int cls = 0; cls < classes; cls++) {
                float score = channelsFirst ? raw[(4 + cls) * anchors + anchor] : raw[anchor * channels + 5 + cls];
                if (!channelsFirst) {
                    score *= raw[anchor * channels + 4];  // objectness
                }
                if (score > bestScore) {
                    bestScore = score;
                    bestClass = cls;
                }
            }
            if (bestScore < confidenceThreshold) {
                continue;
            }
            // cx, cy, w, h sit `stride` apart; channels first they are one value per channel,
            // anchors first one value per field of this anchor
            int base = channelsFirst ? anchor : anchor * channels;
            int stride = channelsFirst ? anchors : 1;
            float cx = raw[base];
            float cy = raw[base + stride];
            float w = raw[base + 2 * stride];
            float h = raw[base + 3 * stride];
            Detection detection = new Detection(bestClass, label(labels, bestClass), bestScore,
                    cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
            candidates.add(detection.clippedTo(inputSize, inputSize));
        }
        return nms(candidates, DEFAULT_IOU, MAX_DETECTIONS);
    }

    private static String label(String[] labels, int classId) {
        return classId < labels.length ? labels[classId] : "class " + classId;
    }

    /** Keeps the best boxes while dropping weaker boxes of the same class that overlap a kept one. */
    static List<Detection> nms(List<Detection> candidates, float iouThreshold, int limit) {
        List<Detection> sorted = new ArrayList<>(candidates);
        Collections.sort(sorted, (a, b) -> Float.compare(b.confidence, a.confidence));
        List<Detection> kept = new ArrayList<>();
        for (Detection candidate : sorted) {
            boolean overlapping = false;
            for (Detection other : kept) {
                if (other.classId == candidate.classId && other.intersectionOverUnion(candidate) > iouThreshold) {
                    overlapping = true;
                    break;
                }
            }
            if (!overlapping) {
                kept.add(candidate);
                if (kept.size() == limit) {
                    break;
                }
            }
        }
        return kept;
    }

    /**
     * How a frame was shrunk and padded into the model's square input: the frame keeps its aspect
     * ratio and is centred, mirroring the letterbox the model was trained with.
     */
    static final class Letterbox {

        final int inputSize;
        final int frameWidth;
        final int frameHeight;
        final float scale;      // frame pixels -> input pixels
        final int padX;         // padding on the left, the same amount goes on the right
        final int padY;         // padding on the top, the same amount goes on the bottom

        Letterbox(int inputSize, int frameWidth, int frameHeight) {
            this.inputSize = inputSize;
            this.frameWidth = frameWidth;
            this.frameHeight = frameHeight;
            scale = Math.min((float) inputSize / frameWidth, (float) inputSize / frameHeight);
            padX = (inputSize - scaledWidth()) / 2;
            padY = (inputSize - scaledHeight()) / 2;
        }

        int scaledWidth() {
            return Math.round(frameWidth * scale);
        }

        int scaledHeight() {
            return Math.round(frameHeight * scale);
        }

        /** The same detection with its box moved from input pixels back to frame pixels. */
        Detection toFrame(Detection detection) {
            return new Detection(detection.classId, detection.label, detection.confidence,
                    (detection.x1 - padX) / scale,
                    (detection.y1 - padY) / scale,
                    (detection.x2 - padX) / scale,
                    (detection.y2 - padY) / scale).clippedTo(frameWidth, frameHeight);
        }
    }
}
