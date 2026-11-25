package com.example.ipwebcamapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class YoloDecoderTest {

    private static final String[] LABELS = {"person", "chair"};
    private static final int INPUT = 416;
    private static final int ANCHORS = 64;
    private static final long[] SHAPE_V11 = {1, 6, ANCHORS};   // 2 classes, channels first
    private static final long[] SHAPE_V5 = {1, ANCHORS, 7};    // 2 classes, anchors first
    private static final float DELTA = 1e-4f;

    /** Each row is one anchor: cx, cy, w, h, then the class probabilities. Unused anchors stay 0. */
    private static float[] channelsFirst(float[]... anchors) {
        float[] raw = new float[6 * ANCHORS];
        for (int a = 0; a < anchors.length; a++) {
            for (int c = 0; c < 6; c++) {
                raw[c * ANCHORS + a] = anchors[a][c];
            }
        }
        return raw;
    }

    /** Each row is one anchor: cx, cy, w, h, objectness, then the class scores. */
    private static float[] anchorsFirst(float[]... anchors) {
        float[] raw = new float[ANCHORS * 7];
        for (int a = 0; a < anchors.length; a++) {
            System.arraycopy(anchors[a], 0, raw, a * 7, 7);
        }
        return raw;
    }

    private static List<Detection> decodeV11(float[]... anchors) {
        return YoloDecoder.decode(channelsFirst(anchors), SHAPE_V11, LABELS, INPUT, YoloDecoder.DEFAULT_CONFIDENCE);
    }

    private static void assertBox(Detection detection, float x1, float y1, float x2, float y2) {
        assertEquals(x1, detection.x1, DELTA);
        assertEquals(y1, detection.y1, DELTA);
        assertEquals(x2, detection.x2, DELTA);
        assertEquals(y2, detection.y2, DELTA);
    }

    @Test
    public void recognisesTheOutputLayout() {
        assertEquals(YoloDecoder.Layout.CHANNELS_FIRST, YoloDecoder.layoutOf(new long[]{1, 84, 8400}));
        assertEquals(80, YoloDecoder.classCount(new long[]{1, 84, 8400}));
        assertEquals(YoloDecoder.Layout.ANCHORS_FIRST, YoloDecoder.layoutOf(new long[]{1, 25200, 85}));
        assertEquals(80, YoloDecoder.classCount(new long[]{1, 25200, 85}));
    }

    @Test
    public void decodesTheBoxAndTheBestClass() {
        List<Detection> detections = decodeV11(new float[]{100, 100, 40, 60, 0.9f, 0.1f});

        assertEquals(1, detections.size());
        assertEquals(0, detections.get(0).classId);
        assertEquals("person", detections.get(0).label);
        assertEquals(0.9f, detections.get(0).confidence, DELTA);
        assertBox(detections.get(0), 80, 70, 120, 130);
    }

    @Test
    public void picksTheClassWithTheHighestProbability() {
        List<Detection> detections = decodeV11(new float[]{100, 100, 40, 60, 0.2f, 0.7f});

        assertEquals(1, detections.size());
        assertEquals(1, detections.get(0).classId);
        assertEquals("chair", detections.get(0).label);
        assertEquals(0.7f, detections.get(0).confidence, DELTA);
    }

    @Test
    public void dropsAnchorsBelowTheConfidenceThreshold() {
        assertTrue(decodeV11(new float[]{100, 100, 40, 60, 0.29f, 0.1f}).isEmpty());
    }

    @Test
    public void suppressesWeakerOverlappingBoxesOfTheSameClass() {
        List<Detection> detections = decodeV11(
                new float[]{100, 100, 40, 60, 0.9f, 0.1f},
                new float[]{102, 101, 40, 60, 0.8f, 0.1f});

        assertEquals(1, detections.size());
        assertEquals(0.9f, detections.get(0).confidence, DELTA);
    }

    @Test
    public void keepsOverlappingBoxesOfDifferentClasses() {
        List<Detection> detections = decodeV11(
                new float[]{100, 100, 40, 60, 0.9f, 0.1f},
                new float[]{102, 101, 40, 60, 0.1f, 0.7f});

        assertEquals(2, detections.size());
        assertEquals("person", detections.get(0).label);
        assertEquals("chair", detections.get(1).label);
    }

    @Test
    public void clipsBoxesToTheInputImage() {
        List<Detection> detections = decodeV11(
                new float[]{5, 5, 40, 40, 0.9f, 0.1f},
                new float[]{410, 410, 40, 40, 0.9f, 0.1f});

        assertBox(detections.get(0), 0, 0, 25, 25);
        assertBox(detections.get(1), 390, 390, INPUT, INPUT);
    }

    @Test
    public void decodesYoloV5OutputUsingObjectness() {
        List<Detection> detections = YoloDecoder.decode(
                anchorsFirst(new float[]{100, 100, 40, 60, 0.9f, 0.8f, 0.1f}),
                SHAPE_V5, LABELS, INPUT, YoloDecoder.DEFAULT_CONFIDENCE);

        assertEquals(1, detections.size());
        assertEquals("person", detections.get(0).label);
        assertEquals(0.72f, detections.get(0).confidence, DELTA);
        assertBox(detections.get(0), 80, 70, 120, 130);
    }

    @Test
    public void ordersByConfidence() {
        List<Detection> detections = decodeV11(
                new float[]{50, 50, 20, 20, 0.5f, 0f},
                new float[]{200, 200, 20, 20, 0.9f, 0f},
                new float[]{350, 350, 20, 20, 0.7f, 0f});

        assertEquals(3, detections.size());
        assertEquals(0.9f, detections.get(0).confidence, DELTA);
        assertEquals(0.7f, detections.get(1).confidence, DELTA);
        assertEquals(0.5f, detections.get(2).confidence, DELTA);
    }

    @Test
    public void capsTheNumberOfDetections() {
        float[][] anchors = new float[40][6];
        for (int a = 0; a < 40; a++) {
            anchors[a] = new float[]{10 + (a % 8) * 50, 10 + (a / 8) * 50, 20, 20, 0.5f, 0f};
        }

        assertEquals(YoloDecoder.MAX_DETECTIONS, decodeV11(anchors).size());
    }

    @Test
    public void letterboxesALandscapeFrameWithPaddingAboveAndBelow() {
        YoloDecoder.Letterbox letterbox = new YoloDecoder.Letterbox(INPUT, 1280, 720);

        assertEquals(0.325f, letterbox.scale, DELTA);
        assertEquals(416, letterbox.scaledWidth());
        assertEquals(234, letterbox.scaledHeight());
        assertEquals(0, letterbox.padX);
        assertEquals(91, letterbox.padY);
    }

    @Test
    public void letterboxesAPortraitFrameWithPaddingOnTheSides() {
        YoloDecoder.Letterbox letterbox = new YoloDecoder.Letterbox(INPUT, 720, 1280);

        assertEquals(234, letterbox.scaledWidth());
        assertEquals(416, letterbox.scaledHeight());
        assertEquals(91, letterbox.padX);
        assertEquals(0, letterbox.padY);
    }

    @Test
    public void mapsABoxBackIntoTheFrame() {
        YoloDecoder.Letterbox letterbox = new YoloDecoder.Letterbox(INPUT, 1280, 720);
        // The middle of the letterboxed input: x = 208, y = 91 + 117
        Detection inInput = new Detection(0, "person", 0.9f, 188, 188, 228, 228);

        Detection inFrame = letterbox.toFrame(inInput);

        assertEquals("person", inFrame.label);
        assertEquals(640f, (inFrame.x1 + inFrame.x2) / 2, 0.01f);
        assertEquals(360f, (inFrame.y1 + inFrame.y2) / 2, 0.01f);
        assertEquals(40 / 0.325f, inFrame.x2 - inFrame.x1, 0.01f);
    }

    @Test
    public void padsAnEdgeBoxBackInsideTheFrame() {
        YoloDecoder.Letterbox letterbox = new YoloDecoder.Letterbox(INPUT, 1280, 720);
        // Reaches into the padding above the image, where the model never sees anything
        Detection inInput = new Detection(0, "person", 0.9f, 188, 80, 228, 130);

        Detection inFrame = letterbox.toFrame(inInput);

        assertEquals(0f, inFrame.y1, DELTA);
        assertEquals((130 - 91) / 0.325f, inFrame.y2, 0.01f);
    }
}
