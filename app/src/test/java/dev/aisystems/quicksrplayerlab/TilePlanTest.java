package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.List;

public final class TilePlanTest {
    @Test
    public void boundarySizesCoverEachInputPixelExactlyOnce() {
        int[] sizes = new int[]{8, 9, 55, 56, 57, 63, 64, 65, 113};
        for (int width : sizes) {
            for (int height : sizes) {
                assertExactCoverage(width, height, TilePlan.create(width, height));
            }
        }
    }

    @Test
    public void sub64ImageUsesOppositeBoundaryAlignedWindows() {
        List<TilePlan.Tile> tiles = TilePlan.create(57, 60);
        assertEquals(4, tiles.size());

        TilePlan.Tile first = tiles.get(0);
        assertEquals(0, first.sampleX());
        assertEquals(0, first.sampleY());
        assertEquals(56, first.outputWidth());
        assertEquals(60, first.outputHeight());
        assertEquals(0, first.localCoreX());
        assertEquals(0, first.localCoreY());

        TilePlan.Tile last = tiles.get(3);
        assertEquals(-7, last.sampleX());
        assertEquals(-4, last.sampleY());
        assertEquals(58, last.outputWidth());
        assertEquals(60, last.outputHeight());
        assertEquals(64 - (57 - 28), last.localCoreX());
        assertEquals(64 - (60 - 30), last.localCoreY());
        assertEquals(56, last.outputX());
        assertEquals(60, last.outputY());
    }

    @Test
    public void everySelectedPixelHasCanonicalBoundaryOrFullHalo() {
        int[] sizes = new int[]{8, 9, 17, 57, 63, 64, 65, 112, 113, 1000};
        for (int width : sizes) {
            for (int height : sizes) {
                List<TilePlan.Tile> tiles = TilePlan.create(width, height);
                for (TilePlan.Tile tile : tiles) {
                    assertAxisAlignment(
                            width,
                            tile.coreX,
                            tile.coreWidth,
                            tile.sampleX());
                    assertAxisAlignment(
                            height,
                            tile.coreY,
                            tile.coreHeight,
                            tile.sampleY());
                }
            }
        }
    }

    @Test
    public void dimensionsTooSmallForBothCanonicalEdgesAreRejected() {
        assertRejected(7, 64);
        assertRejected(64, 7);
    }

    private static void assertExactCoverage(
            int width,
            int height,
            List<TilePlan.Tile> tiles) {
        int[] coverage = new int[width * height];
        for (TilePlan.Tile tile : tiles) {
            for (int y = tile.coreY; y < tile.coreY + tile.coreHeight; y++) {
                for (int x = tile.coreX; x < tile.coreX + tile.coreWidth; x++) {
                    coverage[y * width + x]++;
                }
            }
        }
        for (int count : coverage) {
            assertEquals(1, count);
        }
    }

    private static void assertAxisAlignment(
            int imageLength,
            int coreStart,
            int coreLength,
            int sampleStart) {
        for (int coordinate = coreStart; coordinate < coreStart + coreLength; coordinate++) {
            int local = coordinate - sampleStart;
            int globalBefore = coordinate;
            int globalAfter = imageLength - 1 - coordinate;
            int tileBefore = local;
            int tileAfter = TilePlan.TILE_SIZE - 1 - local;
            assertTrue(local >= 0 && local < TilePlan.TILE_SIZE);
            if (globalBefore < TilePlan.HALO) {
                assertEquals(globalBefore, tileBefore);
            } else {
                assertTrue(tileBefore >= TilePlan.HALO);
            }
            if (globalAfter < TilePlan.HALO) {
                assertEquals(globalAfter, tileAfter);
            } else {
                assertTrue(tileAfter >= TilePlan.HALO);
            }
        }
    }

    private static void assertRejected(int width, int height) {
        try {
            TilePlan.create(width, height);
            fail("Expected dimensions to be rejected: " + width + "x" + height);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("at least 8"));
        }
    }
}
