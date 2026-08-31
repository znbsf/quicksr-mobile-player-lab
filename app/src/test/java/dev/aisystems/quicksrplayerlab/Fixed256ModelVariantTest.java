package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public final class Fixed256ModelVariantTest {
    @Test
    public void fixed256VariantMatchesStagedBuildIdentity() {
        ModelVariant variant = ModelVariant.FIXED256_DCR_FULL;

        assertEquals("fixed256-dcr-full", variant.id());
        assertEquals(BuildConfig.DCR256_MODEL_FILE, variant.asset());
        assertEquals("upscaled_image", variant.outputName());
        assertArrayEquals(new long[]{1, 3, 256, 256}, variant.sessionInputShape());
        assertArrayEquals(new long[]{1, 3, 512, 512}, variant.sessionOutputShape());
        assertEquals(256, variant.inputSide());
        assertEquals(512, variant.outputSide());
        assertEquals(3 * 256 * 256, variant.inputValueCount());
        assertEquals(3 * 512 * 512, variant.outputValueCount());
        assertFalse(variant.requiresCrdPixelShuffle());
        assertEquals(BuildConfig.DCR256_MODEL_BYTES, variant.expectedBytes());
        assertEquals(BuildConfig.DCR256_MODEL_SHA256, variant.expectedSha256());
    }
}
