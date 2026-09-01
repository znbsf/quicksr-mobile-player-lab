package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public final class HighResolutionModelVariantTest {
    @Test
    public void fixed640x360ThreeXContractIsStatic1080p() {
        ModelVariant variant = ModelVariant.FIXED640X360_3X_FULL;
        assertEquals("fixed640x360-3x-full", variant.id());
        assertArrayEquals(new long[]{1, 3, 360, 640}, variant.sessionInputShape());
        assertArrayEquals(new long[]{1, 3, 1080, 1920}, variant.sessionOutputShape());
        assertEquals(1920, variant.outputWidth());
        assertEquals(1080, variant.outputHeight());
        assertFalse(variant.requiresCrdPixelShuffle());
    }

    @Test
    public void fixed640x360FourXContractIsStatic1440p() {
        ModelVariant variant = ModelVariant.FIXED640X360_4X_FULL;
        assertEquals("fixed640x360-4x-full", variant.id());
        assertArrayEquals(new long[]{1, 3, 360, 640}, variant.sessionInputShape());
        assertArrayEquals(new long[]{1, 3, 1440, 2560}, variant.sessionOutputShape());
        assertEquals(2560, variant.outputWidth());
        assertEquals(1440, variant.outputHeight());
        assertFalse(variant.requiresCrdPixelShuffle());
    }
}
