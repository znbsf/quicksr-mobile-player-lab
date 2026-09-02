package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class AnimeCadenceAnalyzerTest {
    private static final int WIDTH = 64;
    private static final int HEIGHT = 36;

    @Test
    public void oneOnOneProcessesEveryMateriallyDifferentDrawing() {
        AnimeCadenceAnalyzer analyzer = enabledAnalyzer(solid(20), "a", 0);

        AnimeCadenceAnalyzer.Result second = analyze(analyzer, solid(110), "b", 0);
        analyzer.markInferenceAvailable(0);
        AnimeCadenceAnalyzer.Result third = analyze(analyzer, solid(220), "c", 0);

        assertEquals(AnimeCadenceAnalyzer.Decision.PROCESS, second.decision);
        assertEquals(AnimeCadenceAnalyzer.Reason.SCENE_CUT, second.reason);
        assertEquals(AnimeCadenceAnalyzer.Decision.PROCESS, third.decision);
    }

    @Test
    public void oneOnTwoReusesEachExactHeldFrame() {
        AnimeCadenceAnalyzer analyzer = new AnimeCadenceAnalyzer();
        List<AnimeCadenceAnalyzer.Decision> decisions = new ArrayList<>();
        byte[][] frames = {solid(20), solid(20), solid(120), solid(120)};
        String[] crc = {"a", "a", "b", "b"};
        for (int index = 0; index < frames.length; index++) {
            AnimeCadenceAnalyzer.Result result = analyze(analyzer, frames[index], crc[index], 0);
            decisions.add(result.decision);
            if (result.decision == AnimeCadenceAnalyzer.Decision.PROCESS) {
                analyzer.markInferenceAvailable(0);
            }
        }

        assertEquals(List.of(
                AnimeCadenceAnalyzer.Decision.PROCESS,
                AnimeCadenceAnalyzer.Decision.REUSE,
                AnimeCadenceAnalyzer.Decision.PROCESS,
                AnimeCadenceAnalyzer.Decision.REUSE), decisions);
    }

    @Test
    public void oneOnThreeReusesTwoFramesWithoutModuloScheduling() {
        AnimeCadenceAnalyzer analyzer = new AnimeCadenceAnalyzer();
        AnimeCadenceAnalyzer.Result first = analyze(analyzer, solid(80), "same", 0);
        analyzer.markInferenceAvailable(0);
        AnimeCadenceAnalyzer.Result second = analyze(analyzer, solid(80), "same", 0);
        AnimeCadenceAnalyzer.Result third = analyze(analyzer, solid(80), "same", 0);

        assertEquals(AnimeCadenceAnalyzer.Decision.PROCESS, first.decision);
        assertEquals(AnimeCadenceAnalyzer.Reason.EXACT_INPUT, second.reason);
        assertEquals(1, second.reuseStreak);
        assertEquals(AnimeCadenceAnalyzer.Reason.EXACT_INPUT, third.reason);
        assertEquals(2, third.reuseStreak);
    }

    @Test
    public void slowPanIsTreatedAsMotion() {
        AnimeCadenceAnalyzer analyzer = enabledAnalyzer(gradient(0), "pan-0", 0);
        AnimeCadenceAnalyzer.Result first = analyze(analyzer, gradient(1), "pan-1", 0);
        analyzer.markInferenceAvailable(0);
        AnimeCadenceAnalyzer.Result second = analyze(analyzer, gradient(2), "pan-2", 0);

        assertEquals(AnimeCadenceAnalyzer.Decision.PROCESS, first.decision);
        assertEquals(AnimeCadenceAnalyzer.Reason.MOTION, first.reason);
        assertEquals(AnimeCadenceAnalyzer.Decision.PROCESS, second.decision);
        assertEquals(AnimeCadenceAnalyzer.Reason.MOTION, second.reason);
    }

    @Test
    public void nearZeroCodecNoiseCanReuse() {
        byte[] base = solid(60);
        AnimeCadenceAnalyzer analyzer = enabledAnalyzer(base, "base", 0);
        byte[] noisy = base.clone();
        setGray(noisy, WIDTH / 2, HEIGHT / 2, 61);

        AnimeCadenceAnalyzer.Result result = analyze(analyzer, noisy, "noise", 0);

        assertEquals(AnimeCadenceAnalyzer.Decision.REUSE, result.decision);
        assertEquals(AnimeCadenceAnalyzer.Reason.SMALL_CHANGE, result.reason);
    }

    @Test
    public void maximumStalenessForcesFourthIdenticalInputToProcess() {
        AnimeCadenceAnalyzer analyzer = enabledAnalyzer(solid(90), "same", 0);
        analyze(analyzer, solid(90), "same", 0);
        analyze(analyzer, solid(90), "same", 0);

        AnimeCadenceAnalyzer.Result forced = analyze(analyzer, solid(90), "same", 0);

        assertEquals(AnimeCadenceAnalyzer.Decision.PROCESS, forced.decision);
        assertEquals(AnimeCadenceAnalyzer.Reason.MAX_STALENESS, forced.reason);
    }

    @Test
    public void hardCutNeverReuses() {
        AnimeCadenceAnalyzer analyzer = enabledAnalyzer(solid(0), "black", 0);
        AnimeCadenceAnalyzer.Result cut = analyze(analyzer, solid(255), "white", 0);

        assertEquals(AnimeCadenceAnalyzer.Decision.PROCESS, cut.decision);
        assertEquals(AnimeCadenceAnalyzer.Reason.SCENE_CUT, cut.reason);
        assertTrue(cut.sceneScore >= 0.28f);
    }

    @Test
    public void subtitleOnlyChangeTriggersHighContrastGuard() {
        byte[] base = solid(64);
        AnimeCadenceAnalyzer analyzer = enabledAnalyzer(base, "base", 0);
        byte[] subtitle = base.clone();
        for (int y = HEIGHT * 2 / 3; y < HEIGHT; y++) {
            for (int x = WIDTH / 4; x < WIDTH * 3 / 4; x++) {
                if (((x / 2) + (y / 2)) % 2 == 0) {
                    setGray(subtitle, x, y, 255);
                }
            }
        }

        AnimeCadenceAnalyzer.Result result = analyze(analyzer, subtitle, "subtitle", 0);

        assertEquals(AnimeCadenceAnalyzer.Decision.PROCESS, result.decision);
        assertEquals(AnimeCadenceAnalyzer.Reason.SUBTITLE_GUARD, result.reason);
        assertTrue(result.subtitleScore >= 0.04f);
    }

    @Test
    public void oneBottomGridCellHighContrastChangeTriggersSubtitleGuard() {
        byte[] base = solid(64);
        AnimeCadenceAnalyzer analyzer = enabledAnalyzer(base, "base", 0);
        byte[] subtitle = base.clone();
        for (int y = 32; y < 36; y++) {
            for (int x = 32; x < 36; x++) {
                if ((x + y) % 2 == 0) {
                    setGray(subtitle, x, y, 255);
                }
            }
        }

        AnimeCadenceAnalyzer.Result result = analyze(analyzer, subtitle, "one-cell", 0);

        assertEquals(AnimeCadenceAnalyzer.Decision.PROCESS, result.decision);
        assertEquals(AnimeCadenceAnalyzer.Reason.SUBTITLE_GUARD, result.reason);
        assertTrue(result.subtitleScore >= 1.0f / 48.0f);
    }

    @Test
    public void shortSubtitleStrokeBetweenSparseSamplesTriggersDenseGuard() {
        int width = 640;
        int height = 360;
        byte[] base = solid(width, height, 64);
        AnimeCadenceAnalyzer analyzer = new AnimeCadenceAnalyzer();
        AnimeCadenceAnalyzer.Result first = analyzer.analyze(
                AnimeCadenceAnalyzer.Mode.CONTENT_AWARE_V1,
                base,
                width,
                height,
                "base",
                0L);
        analyzer.markInferenceAvailable(0L);
        byte[] subtitle = base.clone();
        // Cell (0, 8) samples x/y at 5, 15, 25 and 35 within its 40-pixel bounds.
        // This 4x4 stroke at x=8..11/y=328..331 is intentionally between those points.
        for (int y = 328; y < 332; y++) {
            for (int x = 8; x < 12; x++) {
                setGray(subtitle, width, x, y, 255);
            }
        }

        AnimeCadenceAnalyzer.Result result = analyzer.analyze(
                AnimeCadenceAnalyzer.Mode.CONTENT_AWARE_V1,
                subtitle,
                width,
                height,
                "short-subtitle",
                0L);

        assertEquals(AnimeCadenceAnalyzer.Decision.PROCESS, first.decision);
        assertEquals(AnimeCadenceAnalyzer.Decision.PROCESS, result.decision);
        assertEquals(AnimeCadenceAnalyzer.Reason.SUBTITLE_GUARD, result.reason);
        assertTrue(result.subtitleScore > 0.0f);
    }

    @Test
    public void singleHighContrastBottomPixelBetweenSparseSamplesFailsTowardProcessing() {
        int width = 640;
        int height = 360;
        byte[] base = solid(width, height, 64);
        AnimeCadenceAnalyzer analyzer = new AnimeCadenceAnalyzer();
        analyzer.analyze(
                AnimeCadenceAnalyzer.Mode.CONTENT_AWARE_V1,
                base,
                width,
                height,
                "base",
                0L);
        analyzer.markInferenceAvailable(0L);
        byte[] changed = base.clone();
        setGray(changed, width, 9, 329, 255);

        AnimeCadenceAnalyzer.Result result = analyzer.analyze(
                AnimeCadenceAnalyzer.Mode.CONTENT_AWARE_V1,
                changed,
                width,
                height,
                "one-pixel",
                0L);

        assertEquals(AnimeCadenceAnalyzer.Decision.PROCESS, result.decision);
        assertEquals(AnimeCadenceAnalyzer.Reason.SUBTITLE_GUARD, result.reason);
    }

    @Test
    public void lowContrastBottomChangeBelowDenseThresholdMayReuse() {
        int width = 640;
        int height = 360;
        byte[] base = solid(width, height, 64);
        AnimeCadenceAnalyzer analyzer = new AnimeCadenceAnalyzer();
        analyzer.analyze(
                AnimeCadenceAnalyzer.Mode.CONTENT_AWARE_V1,
                base,
                width,
                height,
                "base",
                0L);
        analyzer.markInferenceAvailable(0L);
        byte[] changed = base.clone();
        setGray(changed, width, 9, 329, 95);

        AnimeCadenceAnalyzer.Result result = analyzer.analyze(
                AnimeCadenceAnalyzer.Mode.CONTENT_AWARE_V1,
                changed,
                width,
                height,
                "low-contrast",
                0L);

        assertEquals(AnimeCadenceAnalyzer.Decision.REUSE, result.decision);
        assertEquals(AnimeCadenceAnalyzer.Reason.SMALL_CHANGE, result.reason);
    }

    @Test
    public void flushAndSeekRequireNewGenerationInference() {
        AnimeCadenceAnalyzer analyzer = enabledAnalyzer(solid(30), "same", 0);
        AnimeCadenceAnalyzer.Result beforeFlush = analyze(analyzer, solid(30), "same", 0);
        analyzer.reset(1);
        AnimeCadenceAnalyzer.Result afterFlush = analyze(analyzer, solid(30), "same", 1);

        assertEquals(AnimeCadenceAnalyzer.Decision.REUSE, beforeFlush.decision);
        assertEquals(AnimeCadenceAnalyzer.Decision.PROCESS, afterFlush.decision);
        assertEquals(AnimeCadenceAnalyzer.Reason.GENERATION_START, afterFlush.reason);
    }

    @Test
    public void oldGenerationInferenceCannotEnableNewGenerationReuse() {
        AnimeCadenceAnalyzer analyzer = enabledAnalyzer(solid(40), "same", 0);
        analyzer.reset(1);
        analyzer.markInferenceAvailable(0);
        AnimeCadenceAnalyzer.Result first = analyze(analyzer, solid(40), "same", 1);
        AnimeCadenceAnalyzer.Result second = analyze(analyzer, solid(40), "same", 1);

        assertEquals(AnimeCadenceAnalyzer.Reason.GENERATION_START, first.reason);
        assertEquals(AnimeCadenceAnalyzer.Decision.PROCESS, second.decision);
        assertEquals(AnimeCadenceAnalyzer.Reason.NO_INFERRED_REFERENCE, second.reason);
    }

    @Test
    public void disabledModeAlwaysProcessesAndReportsActualMode() {
        AnimeCadenceAnalyzer analyzer = new AnimeCadenceAnalyzer();
        AnimeCadenceAnalyzer.Result first = analyzer.analyze(
                AnimeCadenceAnalyzer.Mode.OFF, solid(10), WIDTH, HEIGHT, "a", 0);
        analyzer.markInferenceAvailable(0);
        AnimeCadenceAnalyzer.Result second = analyzer.analyze(
                AnimeCadenceAnalyzer.Mode.OFF, solid(10), WIDTH, HEIGHT, "a", 0);

        assertEquals(AnimeCadenceAnalyzer.Reason.DISABLED, first.reason);
        assertEquals(AnimeCadenceAnalyzer.Decision.PROCESS, second.decision);
        assertEquals(AnimeCadenceAnalyzer.Reason.DISABLED, second.reason);
    }

    private static AnimeCadenceAnalyzer enabledAnalyzer(byte[] first, String crc, int generation) {
        AnimeCadenceAnalyzer analyzer = new AnimeCadenceAnalyzer();
        AnimeCadenceAnalyzer.Result result = analyze(analyzer, first, crc, generation);
        assertEquals(AnimeCadenceAnalyzer.Decision.PROCESS, result.decision);
        analyzer.markInferenceAvailable(generation);
        return analyzer;
    }

    private static AnimeCadenceAnalyzer.Result analyze(
            AnimeCadenceAnalyzer analyzer,
            byte[] rgba,
            String crc,
            int generation) {
        return analyzer.analyze(
                AnimeCadenceAnalyzer.Mode.CONTENT_AWARE_V1,
                rgba,
                WIDTH,
                HEIGHT,
                crc,
                generation);
    }

    private static byte[] solid(int value) {
        return solid(WIDTH, HEIGHT, value);
    }

    private static byte[] solid(int width, int height, int value) {
        byte[] rgba = new byte[width * height * 4];
        for (int offset = 0; offset < rgba.length; offset += 4) {
            rgba[offset] = (byte) value;
            rgba[offset + 1] = (byte) value;
            rgba[offset + 2] = (byte) value;
            rgba[offset + 3] = (byte) 255;
        }
        return rgba;
    }

    private static byte[] gradient(int shift) {
        byte[] rgba = new byte[WIDTH * HEIGHT * 4];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                setGray(rgba, x, y, Math.min(255, 40 + x + shift));
            }
        }
        return rgba;
    }

    private static void setGray(byte[] rgba, int x, int y, int value) {
        setGray(rgba, WIDTH, x, y, value);
    }

    private static void setGray(byte[] rgba, int width, int x, int y, int value) {
        int offset = (y * width + x) * 4;
        rgba[offset] = (byte) value;
        rgba[offset + 1] = (byte) value;
        rgba[offset + 2] = (byte) value;
        rgba[offset + 3] = (byte) 255;
    }
}
