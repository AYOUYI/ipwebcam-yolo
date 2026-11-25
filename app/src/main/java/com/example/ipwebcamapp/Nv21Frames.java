package com.example.ipwebcamapp;

/**
 * Rotating NV21 frames: a full-resolution Y plane followed by interleaved VU pairs at half
 * resolution. Plain array code with no Android dependencies, so it can be unit tested on a
 * desktop JVM.
 */
final class Nv21Frames {

    private Nv21Frames() {
    }

    /**
     * Turns a {@code width} x {@code height} frame clockwise by 0, 90, 180 or 270 degrees.
     * With the camera's sensor orientation as {@code degrees}, the result is upright when the
     * phone is in its natural (portrait) orientation.
     */
    static byte[] rotateClockwise(byte[] nv21, int width, int height, int degrees) {
        switch (normalize(degrees)) {
            case 90:
                return turnQuarter(nv21, width, height, true);
            case 180:
                return turnHalf(nv21, width, height);
            case 270:
                return turnQuarter(nv21, width, height, false);
            default:
                return nv21;
        }
    }

    /** Width of the frame after {@link #rotateClockwise} by {@code degrees}. */
    static int rotatedWidth(int width, int height, int degrees) {
        return isQuarter(degrees) ? height : width;
    }

    /** Height of the frame after {@link #rotateClockwise} by {@code degrees}. */
    static int rotatedHeight(int width, int height, int degrees) {
        return isQuarter(degrees) ? width : height;
    }

    private static int normalize(int degrees) {
        return ((degrees % 360) + 360) % 360;
    }

    private static boolean isQuarter(int degrees) {
        int normalized = normalize(degrees);
        return normalized == 90 || normalized == 270;
    }

    // A quarter turn moves (x, y) to (height - 1 - y, x) clockwise and to (y, width - 1 - x)
    // the other way. Chroma pairs sit on a half-resolution grid that turns with the luma.
    private static byte[] turnQuarter(byte[] nv21, int width, int height, boolean clockwise) {
        byte[] rotated = new byte[nv21.length];
        int turnedWidth = height;
        int ySize = width * height;
        int chromaWidth = width / 2;
        int chromaHeight = height / 2;
        int turnedChromaWidth = chromaHeight;

        for (int y = 0; y < height; y++) {
            int srcRow = y * width;
            int dstX = clockwise ? height - 1 - y : y;
            for (int x = 0; x < width; x++) {
                int dstY = clockwise ? x : width - 1 - x;
                rotated[dstY * turnedWidth + dstX] = nv21[srcRow + x];
            }
        }

        for (int cy = 0; cy < chromaHeight; cy++) {
            int srcRow = ySize + cy * width;
            int dstX = clockwise ? chromaHeight - 1 - cy : cy;
            for (int cx = 0; cx < chromaWidth; cx++) {
                int dstY = clockwise ? cx : chromaWidth - 1 - cx;
                int dst = ySize + (dstY * turnedChromaWidth + dstX) * 2;
                int src = srcRow + cx * 2;
                rotated[dst] = nv21[src];
                rotated[dst + 1] = nv21[src + 1];
            }
        }
        return rotated;
    }

    // Half a turn moves (x, y) to (width - 1 - x, height - 1 - y)
    private static byte[] turnHalf(byte[] nv21, int width, int height) {
        byte[] rotated = new byte[nv21.length];
        int ySize = width * height;
        int chromaWidth = width / 2;
        int chromaHeight = height / 2;

        for (int y = 0; y < height; y++) {
            int srcRow = y * width;
            int dstRow = (height - 1 - y) * width;
            for (int x = 0; x < width; x++) {
                rotated[dstRow + width - 1 - x] = nv21[srcRow + x];
            }
        }

        for (int cy = 0; cy < chromaHeight; cy++) {
            int srcRow = ySize + cy * width;
            int dstRow = ySize + (chromaHeight - 1 - cy) * width;
            for (int cx = 0; cx < chromaWidth; cx++) {
                int dst = dstRow + (chromaWidth - 1 - cx) * 2;
                int src = srcRow + cx * 2;
                rotated[dst] = nv21[src];
                rotated[dst + 1] = nv21[src + 1];
            }
        }
        return rotated;
    }
}
