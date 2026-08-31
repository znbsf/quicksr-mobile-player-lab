package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public final class Fixed640x360ModelVariantTest {
    @Test
    public void fixed640x360VariantHasExpectedStaticShapes() {
        ModelVariant variant = ModelVariant.FIXED640X360_DCR_FULL;

        assertArrayEquals(new long[]{1, 3, 360, 640}, variant.sessionInputShape());
        assertArrayEquals(new long[]{1, 3, 720, 1280}, variant.sessionOutputShape());
        assertEquals(640, variant.inputWidth());
        assertEquals(360, variant.inputHeight());
        assertEquals(1280, variant.outputWidth());
        assertEquals(720, variant.outputHeight());
        assertEquals(3 * 360 * 640, variant.inputValueCount());
        assertEquals(3 * 720 * 1280, variant.outputValueCount());
        assertFalse(variant.requiresCrdPixelShuffle());
    }
}
