package com.example.ipwebcamapp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class Nv21FramesTest {

    private static final int WIDTH = 8;
    private static final int HEIGHT = 6;

    /** Where a sample ends up after a turn, given where it started on its grid. */
    private interface Mapping {
        int[] dst(int x, int y, int gridWidth, int gridHeight);
    }

    private static final Mapping CLOCKWISE = (x, y, gridWidth, gridHeight) -> new int[]{gridHeight - 1 - y, x};
    private static final Mapping COUNTER_CLOCKWISE = (x, y, gridWidth, gridHeight) -> new int[]{y, gridWidth - 1 - x};
    private static final Mapping HALF_TURN = (x, y, gridWidth, gridHeight) -> new int[]{gridWidth - 1 - x, gridHeight - 1 - y};

    // 72 samples with step 37: every byte in the frame differs from every other one
    private static byte[] sampleFrame(int width, int height) {
        byte[] nv21 = new byte[width * height * 3 / 2];
        for (int i = 0; i < nv21.length; i++) {
            nv21[i] = (byte) (i * 37 + 11);
        }
        return nv21;
    }

    private static int lumaIndex(int width, int x, int y) {
        return y * width + x;
    }

    // VU pairs start right after the luma; a chroma row is half as many pairs, so it still
    // spans frameWidth bytes
    private static int chromaIndex(int area, int frameWidth, int cx, int cy) {
        return area + cy * frameWidth + cx * 2;
    }

    private static void assertEverySampleMoved(byte[] frame, int width, int height, int degrees, Mapping mapping) {
        byte[] turned = Nv21Frames.rotateClockwise(frame, width, height, degrees);
        int turnedWidth = Nv21Frames.rotatedWidth(width, height, degrees);
        int turnedHeight = Nv21Frames.rotatedHeight(width, height, degrees);
        int area = width * height;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int[] dst = mapping.dst(x, y, width, height);
                assertEquals("luma sample (" + x + ", " + y + ")",
                        frame[lumaIndex(width, x, y)], turned[lumaIndex(turnedWidth, dst[0], dst[1])]);
            }
        }
        for (int cy = 0; cy < height / 2; cy++) {
            for (int cx = 0; cx < width / 2; cx++) {
                int[] dst = mapping.dst(cx, cy, width / 2, height / 2);
                int src = chromaIndex(area, width, cx, cy);
                int target = chromaIndex(area, turnedWidth, dst[0], dst[1]);
                assertEquals("V sample (" + cx + ", " + cy + ")", frame[src], turned[target]);
                assertEquals("U sample (" + cx + ", " + cy + ")", frame[src + 1], turned[target + 1]);
            }
        }
    }

    @Test
    public void quarterTurnClockwiseMovesEverySample() {
        assertEverySampleMoved(sampleFrame(WIDTH, HEIGHT), WIDTH, HEIGHT, 90, CLOCKWISE);
        assertEverySampleMoved(sampleFrame(WIDTH, HEIGHT), WIDTH, HEIGHT, 450, CLOCKWISE);
    }

    @Test
    public void quarterTurnCounterClockwiseMovesEverySample() {
        assertEverySampleMoved(sampleFrame(WIDTH, HEIGHT), WIDTH, HEIGHT, 270, COUNTER_CLOCKWISE);
        assertEverySampleMoved(sampleFrame(WIDTH, HEIGHT), WIDTH, HEIGHT, -90, COUNTER_CLOCKWISE);
    }

    @Test
    public void halfTurnMovesEverySample() {
        assertEverySampleMoved(sampleFrame(WIDTH, HEIGHT), WIDTH, HEIGHT, 180, HALF_TURN);
        assertEverySampleMoved(sampleFrame(WIDTH, HEIGHT), WIDTH, HEIGHT, -180, HALF_TURN);
    }

    @Test
    public void fourQuarterTurnsRestoreTheFrame() {
        byte[] frame = sampleFrame(WIDTH, HEIGHT);
        byte[] turned = frame;
        int width = WIDTH;
        int height = HEIGHT;
        for (int i = 0; i < 4; i++) {
            int newWidth = Nv21Frames.rotatedWidth(width, height, 90);
            int newHeight = Nv21Frames.rotatedHeight(width, height, 90);
            turned = Nv21Frames.rotateClockwise(turned, width, height, 90);
            width = newWidth;
            height = newHeight;
        }
        assertArrayEquals(frame, turned);
        assertEquals(WIDTH, width);
        assertEquals(HEIGHT, height);
    }

    @Test
    public void clockwiseThenCounterClockwiseRestoresTheFrame() {
        byte[] frame = sampleFrame(WIDTH, HEIGHT);
        byte[] turned = Nv21Frames.rotateClockwise(
                Nv21Frames.rotateClockwise(frame, WIDTH, HEIGHT, 90), HEIGHT, WIDTH, 270);
        assertArrayEquals(frame, turned);
    }

    @Test
    public void zeroDegreesLeavesTheFrameAlone() {
        byte[] frame = sampleFrame(WIDTH, HEIGHT);
        assertSame(frame, Nv21Frames.rotateClockwise(frame, WIDTH, HEIGHT, 0));
        assertArrayEquals(frame, Nv21Frames.rotateClockwise(frame, WIDTH, HEIGHT, 360));
    }

    @Test
    public void sizeFollowsTheTurn() {
        assertEquals(WIDTH, Nv21Frames.rotatedWidth(WIDTH, HEIGHT, 0));
        assertEquals(HEIGHT, Nv21Frames.rotatedHeight(WIDTH, HEIGHT, 0));
        assertEquals(HEIGHT, Nv21Frames.rotatedWidth(WIDTH, HEIGHT, 90));
        assertEquals(WIDTH, Nv21Frames.rotatedHeight(WIDTH, HEIGHT, 90));
        assertEquals(WIDTH, Nv21Frames.rotatedWidth(WIDTH, HEIGHT, 180));
        assertEquals(HEIGHT, Nv21Frames.rotatedHeight(WIDTH, HEIGHT, 180));
        assertEquals(HEIGHT, Nv21Frames.rotatedWidth(WIDTH, HEIGHT, 270));
        assertEquals(WIDTH, Nv21Frames.rotatedHeight(WIDTH, HEIGHT, 270));
    }
}
