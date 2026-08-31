package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public final class Fixed512ModelVariantTest {
    @Test
    public void fixed512VariantMatchesStagedBuildIdentity() {
        ModelVariant variant = ModelVariant.FIXED512_DCR_FULL;

        assertEquals("fixed512-dcr-full", variant.id());
        assertEquals(BuildConfig.DCR512_MODEL_FILE, variant.asset());
        assertEquals("upscaled_image", variant.outputName());
        assertArrayEquals(new long[]{1, 3, 512, 512}, variant.sessionInputShape());
        assertArrayEquals(new long[]{1, 3, 1024, 1024}, variant.sessionOutputShape());
        assertEquals(512, variant.inputSide());
        assertEquals(1024, variant.outputSide());
        assertEquals(3 * 512 * 512, variant.inputValueCount());
        assertEquals(3 * 1024 * 1024, variant.outputValueCount());
        assertFalse(variant.requiresCrdPixelShuffle());
        assertEquals(BuildConfig.DCR512_MODEL_BYTES, variant.expectedBytes());
        assertEquals(BuildConfig.DCR512_MODEL_SHA256, variant.expectedSha256());
    }
}
