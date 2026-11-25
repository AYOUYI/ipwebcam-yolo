package com.example.ipwebcamapp;

/** One detected object, in pixel coordinates of the frame it was found in (origin top-left). */
final class Detection {

    final int classId;
    final String label;
    final float confidence;
    final float x1;
    final float y1;
    final float x2;
    final float y2;

    Detection(int classId, String label, float confidence, float x1, float y1, float x2, float y2) {
        this.classId = classId;
        this.label = label;
        this.confidence = confidence;
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
    }

    float intersectionOverUnion(Detection other) {
        float left = Math.max(x1, other.x1);
        float top = Math.max(y1, other.y1);
        float right = Math.min(x2, other.x2);
        float bottom = Math.min(y2, other.y2);
        float intersection = Math.max(0, right - left) * Math.max(0, bottom - top);
        float union = area() + other.area() - intersection;
        return union <= 0 ? 0 : intersection / union;
    }

    private float area() {
        return Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
    }

    private Detection movedTo(float newX1, float newY1, float newX2, float newY2) {
        return new Detection(classId, label, confidence, newX1, newY1, newX2, newY2);
    }

    /** The same object with its box clipped to the given image size. */
    Detection clippedTo(int width, int height) {
        return movedTo(clamp(x1, width), clamp(y1, height), clamp(x2, width), clamp(y2, height));
    }

    private static float clamp(float value, int limit) {
        return Math.max(0, Math.min(value, limit));
    }
}
