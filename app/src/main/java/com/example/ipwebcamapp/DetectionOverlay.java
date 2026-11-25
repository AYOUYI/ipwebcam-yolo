package com.example.ipwebcamapp;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.util.List;

/**
 * Draws detection boxes and their labels onto a copy of the on-screen preview. The preview shows
 * the whole frame, so a box only needs scaling from frame pixels to preview pixels.
 */
final class DetectionOverlay {

    private static final int[] PALETTE = {
            0xFFEF4444, 0xFF22C55E, 0xFF3B82F6, 0xFFF59E0B, 0xFFA855F7, 0xFF06B6D4, 0xFFEC4899, 0xFF84CC16};

    private final Paint boxPaint = new Paint();
    private final Paint labelPaint = new Paint();
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    DetectionOverlay() {
        boxPaint.setStyle(Paint.Style.STROKE);
        textPaint.setColor(Color.WHITE);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
    }

    /** Returns the preview with boxes drawn on it, or the preview itself when there is nothing to draw. */
    Bitmap draw(Bitmap preview, List<Detection> detections, int frameWidth, int frameHeight, float density) {
        if (detections.isEmpty()) {
            return preview;
        }

        Bitmap result = preview.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(result);
        float scaleX = result.getWidth() / (float) frameWidth;
        float scaleY = result.getHeight() / (float) frameHeight;

        float padding = 4 * density;
        boxPaint.setStrokeWidth(2 * density);
        textPaint.setTextSize(13 * density);

        for (Detection detection : detections) {
            float left = detection.x1 * scaleX;
            float top = detection.y1 * scaleY;
            float right = detection.x2 * scaleX;
            float bottom = detection.y2 * scaleY;

            int color = PALETTE[detection.classId % PALETTE.length];
            boxPaint.setColor(color);
            labelPaint.setColor(color);
            canvas.drawRect(left, top, right, bottom, boxPaint);

            String text = detection.label + " " + Math.round(detection.confidence * 100) + "%";
            float textHeight = textPaint.getTextSize() + 2 * padding;
            // The label sits just above the box, or inside it when the box touches the top edge
            float labelTop = top - textHeight < 0 ? top : top - textHeight;
            canvas.drawRect(left, labelTop, left + textPaint.measureText(text) + 2 * padding, labelTop + textHeight, labelPaint);
            canvas.drawText(text, left + padding, labelTop + textHeight - padding * 1.3f, textPaint);
        }
        return result;
    }
}
