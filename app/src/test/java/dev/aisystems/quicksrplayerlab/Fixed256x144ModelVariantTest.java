package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public final class Fixed256x144ModelVariantTest {
    @Test
    public void fixed256x144VariantHasExpectedStaticShapes() {
        ModelVariant variant = ModelVariant.FIXED256X144_DCR_FULL;

        assertArrayEquals(new long[]{1, 3, 144, 256}, variant.sessionInputShape());
        assertArrayEquals(new long[]{1, 3, 288, 512}, variant.sessionOutputShape());
        assertEquals(256, variant.inputWidth());
        assertEquals(144, variant.inputHeight());
        assertEquals(512, variant.outputWidth());
        assertEquals(288, variant.outputHeight());
        assertEquals(3 * 144 * 256, variant.inputValueCount());
        assertEquals(3 * 288 * 512, variant.outputValueCount());
        assertFalse(variant.requiresCrdPixelShuffle());
    }
}
