package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class QuickSrVideoOutputLayoutTest {
    @Test
    public void default1080pUsesNhwcWhileCaptureAndNativePackerRemainNchw() {
        assertEquals(
                ModelVariant.FIXED640X360_3X_F32_NHWC,
                QuickSrVideoEffect.sessionModelVariant(
                        QuickSrVideoEffect.Profile.FULL_1080P_3X,
                        false,
                        false));
        assertEquals(
                ModelVariant.FIXED640X360_3X_FULL,
                QuickSrVideoEffect.sessionModelVariant(
                        QuickSrVideoEffect.Profile.FULL_1080P_3X,
                        true,
                        false));
        assertEquals(
                ModelVariant.FIXED640X360_3X_FULL,
                QuickSrVideoEffect.sessionModelVariant(
                        QuickSrVideoEffect.Profile.FULL_1080P_3X,
                        false,
                        true));
    }

    @Test
    public void parallelPackingIsScopedToNhwcOutput() {
        assertEquals(
                4,
                QuickSrVideoEffect.effectivePackStripeCount(
                        ModelVariant.FIXED640X360_3X_F32_NHWC));
        assertEquals(
                1,
                QuickSrVideoEffect.effectivePackStripeCount(
                        ModelVariant.FIXED640X360_3X_FULL));
        assertEquals(
                1,
                QuickSrVideoEffect.effectivePackStripeCount(
                        ModelVariant.FIXED640X360_4X_FULL));
    }
}
