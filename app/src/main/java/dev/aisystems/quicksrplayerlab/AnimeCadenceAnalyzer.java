package dev.aisystems.quicksrplayerlab;

/**
 * Deterministic, low-cost input signal for experimental anime hold reuse.
 *
 * <p>The analyzer samples a fixed 16x9 grid and scans the bottom third densely for short subtitle
 * strokes. It never uses a frame-number modulo: reuse is based on exact input identity or measured
 * luma/edge stability, with scene, subtitle and motion guards. State is generation-scoped and the
 * hard staleness limit permits at most two consecutive reuses.
 */
final class AnimeCadenceAnalyzer {
    static final String VERSION = "anime-cadence-analyzer-v1";
    enum Mode {
        OFF,
        CONTENT_AWARE_V1
    }

    enum Decision {
        PROCESS,
        REUSE
    }

    enum Reason {
        DISABLED,
        GENERATION_START,
        NO_INFERRED_REFERENCE,
        EXACT_INPUT,
        SCENE_CUT,
        SUBTITLE_GUARD,
        MOTION,
        SMALL_CHANGE,
        CACHE_NOT_READY,
        STREAM_BOUNDARY,
        MAX_STALENESS
    }

    static final int MAX_REUSE_STREAK = 2;
    private static final int GRID_COLUMNS = 16;
    private static final int GRID_ROWS = 9;
    private static final int GRID_CELLS = GRID_COLUMNS * GRID_ROWS;
    private static final int SAMPLES_PER_AXIS = 4;
    private static final float SCENE_CUT_THRESHOLD = 0.28f;
    private static final float MOTION_THRESHOLD = 0.001f;
    static final int DENSE_SUBTITLE_LUMA_DELTA = 48;
    static final int DENSE_SUBTITLE_EDGE_DELTA = 32;
    static final int DENSE_SUBTITLE_EDGE_CONTRAST = 24;
    // Subtitle safety is intentionally conservative: a single dense high-contrast change wins
    // over reuse. False positives only spend an inference; false negatives can freeze text.
    static final int DENSE_SUBTITLE_CHANGED_PIXELS = 1;
    static final int DENSE_SUBTITLE_HIGH_CONTRAST_PIXELS = 1;

    static final class Result {
        final Decision decision;
        final Reason reason;
        final int reuseStreak;
        final float sceneScore;
        final float subtitleScore;
        final float motionScore;

        Result(
                Decision decision,
                Reason reason,
                int reuseStreak,
                float sceneScore,
                float subtitleScore,
                float motionScore) {
            this.decision = decision;
            this.reason = reason;
            this.reuseStreak = reuseStreak;
            this.sceneScore = sceneScore;
            this.subtitleScore = subtitleScore;
            this.motionScore = motionScore;
        }
    }

    private static final class Signature {
        String crc32;
        final int[] luma = new int[GRID_CELLS];
        final int[] edge = new int[GRID_CELLS];
        final int width;
        final int bottomTop;
        final int bottomRows;
        final byte[] bottomLuma;

        Signature(int width, int height) {
            this.width = width;
            bottomTop = (height * 2) / 3;
            bottomRows = height - bottomTop;
            bottomLuma = new byte[Math.multiplyExact(width, bottomRows)];
        }
    }

    private long generation = Long.MIN_VALUE;
    private Signature previous;
    private Signature signatureA;
    private Signature signatureB;
    private boolean inferredReferenceAvailable;
    private int reuseStreak;

    synchronized Result analyze(
            Mode mode,
            byte[] rgba,
            int width,
            int height,
            String crc32,
            long frameGeneration) {
        if (rgba == null || rgba.length != Math.multiplyExact(
                Math.multiplyExact(width, height), 4)) {
            throw new IllegalArgumentException("RGBA input does not match dimensions");
        }
        if (width <= 0 || height <= 0 || crc32 == null) {
            throw new IllegalArgumentException("Cadence analysis requires dimensions and CRC");
        }
        if (mode == Mode.OFF) {
            return result(Decision.PROCESS, Reason.DISABLED, 0.0f, 0.0f, 0.0f);
        }
        boolean generationChanged = frameGeneration != generation;
        if (generationChanged) {
            resetLocked(frameGeneration);
        }
        Signature current = signature(rgba, width, height, crc32);
        Signature prior = previous;
        previous = current;
        if (generationChanged || prior == null) {
            reuseStreak = 0;
            return result(Decision.PROCESS, Reason.GENERATION_START, 0.0f, 0.0f, 0.0f);
        }
        Scores scores = compare(prior, current);
        if (!inferredReferenceAvailable) {
            reuseStreak = 0;
            return result(
                    Decision.PROCESS,
                    Reason.NO_INFERRED_REFERENCE,
                    scores.scene,
                    scores.subtitle,
                    scores.motion);
        }
        if (reuseStreak >= MAX_REUSE_STREAK) {
            reuseStreak = 0;
            return result(
                    Decision.PROCESS,
                    Reason.MAX_STALENESS,
                    scores.scene,
                    scores.subtitle,
                    scores.motion);
        }
        if (prior.crc32.equals(current.crc32)) {
            reuseStreak++;
            return result(
                    Decision.REUSE,
                    Reason.EXACT_INPUT,
                    scores.scene,
                    scores.subtitle,
                    scores.motion);
        }
        if (scores.scene >= SCENE_CUT_THRESHOLD) {
            reuseStreak = 0;
            return result(
                    Decision.PROCESS,
                    Reason.SCENE_CUT,
                    scores.scene,
                    scores.subtitle,
                    scores.motion);
        }
        if (scores.subtitleGuard) {
            reuseStreak = 0;
            return result(
                    Decision.PROCESS,
                    Reason.SUBTITLE_GUARD,
                    scores.scene,
                    scores.subtitle,
                    scores.motion);
        }
        if (scores.motion >= MOTION_THRESHOLD) {
            reuseStreak = 0;
            return result(
                    Decision.PROCESS,
                    Reason.MOTION,
                    scores.scene,
                    scores.subtitle,
                    scores.motion);
        }
        reuseStreak++;
        return result(
                Decision.REUSE,
                Reason.SMALL_CHANGE,
                scores.scene,
                scores.subtitle,
                scores.motion);
    }

    synchronized void markInferenceAvailable(long frameGeneration) {
        if (generation == frameGeneration) {
            inferredReferenceAvailable = true;
            reuseStreak = 0;
        }
    }

    synchronized Result processWithoutState(Reason reason) {
        return new Result(Decision.PROCESS, reason, 0, 0.0f, 0.0f, 0.0f);
    }

    synchronized Result forceProcess(Result original, Reason reason) {
        if (original == null || reason == null) {
            throw new IllegalArgumentException("Forced cadence process requires a result and reason");
        }
        reuseStreak = 0;
        return result(
                Decision.PROCESS,
                reason,
                original.sceneScore,
                original.subtitleScore,
                original.motionScore);
    }

    synchronized void reset(long nextGeneration) {
        resetLocked(nextGeneration);
    }

    private void resetLocked(long nextGeneration) {
        generation = nextGeneration;
        previous = null;
        inferredReferenceAvailable = false;
        reuseStreak = 0;
    }

    private Result result(
            Decision decision,
            Reason reason,
            float sceneScore,
            float subtitleScore,
            float motionScore) {
        return new Result(
                decision,
                reason,
                reuseStreak,
                sceneScore,
                subtitleScore,
                motionScore);
    }

    private Signature signature(byte[] rgba, int width, int height, String crc32) {
        if (signatureA == null
                || signatureA.width != width
                || signatureA.bottomRows != height - ((height * 2) / 3)) {
            signatureA = new Signature(width, height);
            signatureB = new Signature(width, height);
            previous = null;
        }
        Signature result = previous == signatureA ? signatureB : signatureA;
        result.crc32 = crc32;
        for (int gridY = 0; gridY < GRID_ROWS; gridY++) {
            int top = gridY * height / GRID_ROWS;
            int bottom = Math.max(top + 1, (gridY + 1) * height / GRID_ROWS);
            for (int gridX = 0; gridX < GRID_COLUMNS; gridX++) {
                int left = gridX * width / GRID_COLUMNS;
                int right = Math.max(left + 1, (gridX + 1) * width / GRID_COLUMNS);
                int lumaSum = 0;
                int edgeSum = 0;
                int samples = 0;
                for (int sampleY = 0; sampleY < SAMPLES_PER_AXIS; sampleY++) {
                    int y = Math.min(
                            height - 1,
                            top + ((2 * sampleY + 1) * (bottom - top))
                                    / (2 * SAMPLES_PER_AXIS));
                    for (int sampleX = 0; sampleX < SAMPLES_PER_AXIS; sampleX++) {
                        int x = Math.min(
                                width - 1,
                                left + ((2 * sampleX + 1) * (right - left))
                                        / (2 * SAMPLES_PER_AXIS));
                        int center = lumaAt(rgba, width, x, y);
                        int horizontal = lumaAt(rgba, width, Math.min(width - 1, x + 1), y);
                        int vertical = lumaAt(rgba, width, x, Math.min(height - 1, y + 1));
                        lumaSum += center;
                        edgeSum += Math.max(
                                Math.abs(center - horizontal),
                                Math.abs(center - vertical));
                        samples++;
                    }
                }
                int index = gridY * GRID_COLUMNS + gridX;
                result.luma[index] = lumaSum / samples;
                result.edge[index] = edgeSum / samples;
            }
        }
        int denseOffset = 0;
        for (int y = result.bottomTop; y < height; y++) {
            for (int x = 0; x < width; x++) {
                result.bottomLuma[denseOffset++] = (byte) lumaAt(rgba, width, x, y);
            }
        }
        return result;
    }

    private static int lumaAt(byte[] rgba, int width, int x, int y) {
        int offset = (y * width + x) * 4;
        int red = rgba[offset] & 0xff;
        int green = rgba[offset + 1] & 0xff;
        int blue = rgba[offset + 2] & 0xff;
        return (77 * red + 150 * green + 29 * blue + 128) >> 8;
    }

    private static final class Scores {
        final float scene;
        final float subtitle;
        final float motion;
        final boolean subtitleGuard;

        Scores(float scene, float subtitle, float motion, boolean subtitleGuard) {
            this.scene = scene;
            this.subtitle = subtitle;
            this.motion = motion;
            this.subtitleGuard = subtitleGuard;
        }
    }

    private static Scores compare(Signature previous, Signature current) {
        long lumaDeltaSum = 0L;
        long edgeDeltaSum = 0L;
        int largeLumaChanges = 0;
        int globalHighContrastChanges = 0;
        int bottomHighContrastChanges = 0;
        int bottomCells = 0;
        int bottomStartRow = (GRID_ROWS * 2) / 3;
        for (int index = 0; index < GRID_CELLS; index++) {
            int lumaDelta = Math.abs(current.luma[index] - previous.luma[index]);
            int edgeDelta = Math.abs(current.edge[index] - previous.edge[index]);
            lumaDeltaSum += lumaDelta;
            edgeDeltaSum += edgeDelta;
            if (lumaDelta >= 48) {
                largeLumaChanges++;
            }
            boolean highContrastChanged = Math.max(
                    current.edge[index],
                    previous.edge[index]) >= 24
                    && (edgeDelta >= 12 || lumaDelta >= 24);
            if (highContrastChanged) {
                globalHighContrastChanges++;
            }
            if (index / GRID_COLUMNS >= bottomStartRow) {
                bottomCells++;
                if (highContrastChanged) {
                    bottomHighContrastChanges++;
                }
            }
        }
        float lumaDelta = lumaDeltaSum / (255.0f * GRID_CELLS);
        float edgeDelta = edgeDeltaSum / (255.0f * GRID_CELLS);
        float largeChangeFraction = largeLumaChanges / (float) GRID_CELLS;
        float scene = 0.6f * lumaDelta + 0.4f * largeChangeFraction;
        int denseChangedPixels = 0;
        int denseHighContrastPixels = 0;
        for (int y = 0; y < current.bottomRows; y++) {
            for (int x = 0; x < current.width; x++) {
                int index = y * current.width + x;
                int priorLuma = previous.bottomLuma[index] & 0xff;
                int currentLuma = current.bottomLuma[index] & 0xff;
                int denseDelta = Math.abs(currentLuma - priorLuma);
                if (denseDelta >= DENSE_SUBTITLE_LUMA_DELTA) {
                    denseChangedPixels++;
                }
                if (denseDelta >= DENSE_SUBTITLE_EDGE_DELTA
                        && Math.max(
                                denseContrast(previous.bottomLuma, previous.width, x, y,
                                        previous.bottomRows),
                                denseContrast(current.bottomLuma, current.width, x, y,
                                        current.bottomRows)) >= DENSE_SUBTITLE_EDGE_CONTRAST) {
                    denseHighContrastPixels++;
                }
            }
        }
        int densePixels = current.bottomLuma.length;
        float subtitle = Math.max(
                Math.max(
                        globalHighContrastChanges / (float) GRID_CELLS,
                        bottomHighContrastChanges / (float) bottomCells),
                Math.max(
                        denseChangedPixels / (float) densePixels,
                        denseHighContrastPixels / (float) densePixels));
        float motion = 0.7f * lumaDelta + 0.3f * edgeDelta;
        boolean subtitleGuard = bottomHighContrastChanges > 0
                || globalHighContrastChanges >= 6
                || (denseChangedPixels >= DENSE_SUBTITLE_CHANGED_PIXELS
                        && denseHighContrastPixels
                                >= DENSE_SUBTITLE_HIGH_CONTRAST_PIXELS);
        return new Scores(scene, subtitle, motion, subtitleGuard);
    }

    private static int denseContrast(byte[] luma, int width, int x, int y, int rows) {
        int center = luma[y * width + x] & 0xff;
        int contrast = 0;
        if (x > 0) {
            contrast = Math.max(contrast, Math.abs(center - (luma[y * width + x - 1] & 0xff)));
        }
        if (x + 1 < width) {
            contrast = Math.max(contrast, Math.abs(center - (luma[y * width + x + 1] & 0xff)));
        }
        if (y > 0) {
            contrast = Math.max(contrast, Math.abs(center - (luma[(y - 1) * width + x] & 0xff)));
        }
        if (y + 1 < rows) {
            contrast = Math.max(contrast, Math.abs(center - (luma[(y + 1) * width + x] & 0xff)));
        }
        return contrast;
    }
}
