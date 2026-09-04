package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class QuickSrSessionFiniteValidationTest {
    @Test
    public void fullFiniteValidationRunsOnlyBeforeFirstCompletedInference() {
        assertTrue(QuickSrSession.shouldValidateFiniteOutput(0));
        assertFalse(QuickSrSession.shouldValidateFiniteOutput(1));
        assertFalse(QuickSrSession.shouldValidateFiniteOutput(120));
        assertFalse(QuickSrSession.shouldValidateFiniteOutput(240));
    }

    @Test
    public void negativeCompletedRunCountIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> QuickSrSession.shouldValidateFiniteOutput(-1));
    }

    @Test
    public void deferredCopyRequiresValidatedFirstRunAndDoubleBuffer() {
        assertFalse(QuickSrSession.canDeferOutputCopy(0, 2));
        assertFalse(QuickSrSession.canDeferOutputCopy(1, 1));
        assertTrue(QuickSrSession.canDeferOutputCopy(1, 2));
        assertTrue(QuickSrSession.canDeferOutputCopy(120, 2));
    }

    @Test
    public void deferredCopyRejectsInvalidCounts() {
        assertThrows(
                IllegalArgumentException.class,
                () -> QuickSrSession.canDeferOutputCopy(-1, 2));
        assertThrows(
                IllegalArgumentException.class,
                () -> QuickSrSession.canDeferOutputCopy(1, 3));
    }
}
