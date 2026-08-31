package dev.aisystems.quicksrplayerlab;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class TilePlan {
    static final int TILE_SIZE = 64;
    static final int HALO = 4;
    static final int CORE_SIZE = TILE_SIZE - HALO * 2;
    static final int SCALE = 2;
    static final int MIN_CANONICAL_EDGE = HALO * 2;

    private TilePlan() {
    }

    static List<Tile> create(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Image dimensions must be positive");
        }
        if (width < MIN_CANONICAL_EDGE || height < MIN_CANONICAL_EDGE) {
            throw new IllegalArgumentException(
                    "Fixed64 canonical edge alignment requires both dimensions to be at least "
                            + MIN_CANONICAL_EDGE + " pixels");
        }
        List<AxisSpan> horizontal = createAxis(width);
        List<AxisSpan> vertical = createAxis(height);
        List<Tile> tiles = new ArrayList<>();
        for (AxisSpan y : vertical) {
            for (AxisSpan x : horizontal) {
                tiles.add(new Tile(
                        x.coreStart,
                        y.coreStart,
                        x.coreLength,
                        y.coreLength,
                        x.sampleStart,
                        y.sampleStart));
            }
        }
        return Collections.unmodifiableList(tiles);
    }

    private static List<AxisSpan> createAxis(int length) {
        List<AxisSpan> spans = new ArrayList<>();
        if (length < TILE_SIZE) {
            int split = length / 2;
            spans.add(new AxisSpan(0, split, 0));
            spans.add(new AxisSpan(split, length - split, length - TILE_SIZE));
            return spans;
        }
        int maximumSampleStart = length - TILE_SIZE;
        for (int coreStart = 0; coreStart < length; coreStart += CORE_SIZE) {
            int coreLength = Math.min(CORE_SIZE, length - coreStart);
            int sampleStart = Math.max(
                    0,
                    Math.min(coreStart - HALO, maximumSampleStart));
            spans.add(new AxisSpan(coreStart, coreLength, sampleStart));
        }
        return spans;
    }

    private static final class AxisSpan {
        final int coreStart;
        final int coreLength;
        final int sampleStart;

        AxisSpan(int coreStart, int coreLength, int sampleStart) {
            this.coreStart = coreStart;
            this.coreLength = coreLength;
            this.sampleStart = sampleStart;
        }
    }

    static final class Tile {
        final int coreX;
        final int coreY;
        final int coreWidth;
        final int coreHeight;
        private final int sampleX;
        private final int sampleY;

        Tile(
                int coreX,
                int coreY,
                int coreWidth,
                int coreHeight,
                int sampleX,
                int sampleY) {
            this.coreX = coreX;
            this.coreY = coreY;
            this.coreWidth = coreWidth;
            this.coreHeight = coreHeight;
            this.sampleX = sampleX;
            this.sampleY = sampleY;
        }

        int sampleX() {
            return sampleX;
        }

        int sampleY() {
            return sampleY;
        }

        int localCoreX() {
            return coreX - sampleX;
        }

        int localCoreY() {
            return coreY - sampleY;
        }

        int outputX() {
            return coreX * SCALE;
        }

        int outputY() {
            return coreY * SCALE;
        }

        int outputWidth() {
            return coreWidth * SCALE;
        }

        int outputHeight() {
            return coreHeight * SCALE;
        }
    }
}
