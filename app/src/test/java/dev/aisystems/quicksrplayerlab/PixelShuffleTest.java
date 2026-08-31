package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public final class PixelShuffleTest {
    @Test
    public void crdChannelPhasesMapToExpectedSpatialOffsets() {
        float[] input = new float[]{
                10.0f, 11.0f,
                20.0f, 21.0f,
                30.0f, 31.0f,
                40.0f, 41.0f
        };
        float[] output = QuickSrEngine.pixelShuffleCrd(input, 1, 1, 2, 2);
        assertArrayEquals(
                new float[]{
                        10.0f, 20.0f, 11.0f, 21.0f,
                        30.0f, 40.0f, 31.0f, 41.0f
                },
                output,
                0.0f);
    }
}
