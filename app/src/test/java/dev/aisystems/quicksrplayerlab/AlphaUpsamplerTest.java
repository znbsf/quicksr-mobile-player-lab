package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class AlphaUpsamplerTest {
    @Test
    public void nearest2xReplicatesEachSourceAlphaIntoTwoByTwoBlock() {
        int[] source = new int[]{
                0x00112233, 0x40112233,
                0x80112233, 0xff112233
        };
        int[] expectedAlpha = new int[]{
                0x00, 0x00, 0x40, 0x40,
                0x00, 0x00, 0x40, 0x40,
                0x80, 0x80, 0xff, 0xff,
                0x80, 0x80, 0xff, 0xff
        };

        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                int result = AlphaUpsampler.applyNearest2x(
                        0x00abcdef,
                        source,
                        2,
                        0,
                        0,
                        x,
                        y);
                assertEquals(expectedAlpha[y * 4 + x], result >>> 24);
                assertEquals(0x00abcdef, result & 0x00ffffff);
            }
        }
    }

    @Test
    public void nearest2xHonorsTileCoreOrigin() {
        int[] tile = new int[64 * 64];
        tile[11 * 64 + 7] = 0x22123456;
        tile[11 * 64 + 8] = 0xdd123456;

        assertEquals(
                0x22112233,
                AlphaUpsampler.applyNearest2x(
                        0x00112233, tile, 64, 7, 11, 1, 0));
        assertEquals(
                0xdd112233,
                AlphaUpsampler.applyNearest2x(
                        0x00112233, tile, 64, 7, 11, 2, 0));
    }
}
