package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public final class Fixed512x288ModelVariantTest {
    @Test
    public void fixed512x288VariantHasExpectedStaticShapes() {
        ModelVariant variant = ModelVariant.FIXED512X288_DCR_FULL;

        assertArrayEquals(new long[]{1, 3, 288, 512}, variant.sessionInputShape());
        assertArrayEquals(new long[]{1, 3, 576, 1024}, variant.sessionOutputShape());
        assertEquals(512, variant.inputWidth());
        assertEquals(288, variant.inputHeight());
        assertEquals(1024, variant.outputWidth());
        assertEquals(576, variant.outputHeight());
        assertEquals(3 * 288 * 512, variant.inputValueCount());
        assertEquals(3 * 576 * 1024, variant.outputValueCount());
        assertFalse(variant.requiresCrdPixelShuffle());
    }
}
