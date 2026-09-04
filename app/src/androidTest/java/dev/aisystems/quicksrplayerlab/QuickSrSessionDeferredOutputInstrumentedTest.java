package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Device-side ownership and correctness check for the double-buffered ORT output lease. */
@RunWith(AndroidJUnit4.class)
public final class QuickSrSessionDeferredOutputInstrumentedTest {
    @Test
    public void deferredCopyMatchesSynchronousPinnedOutput() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        ModelVariant model = ModelVariant.FIXED64_DCR_FULL;
        float[] input = new float[model.inputValueCount()];
        for (int index = 0; index < input.length; index++) {
            input[index] = ((index * 37) % 251) / 250.0f;
        }
        float[] synchronous = new float[model.outputValueCount()];
        float[] deferred = new float[model.outputValueCount()];
        QuickSrSession.RunTimings synchronousTimings = new QuickSrSession.RunTimings();
        QuickSrSession.RunTimings deferredTimings = new QuickSrSession.RunTimings();

        try (QuickSrSession session = QuickSrSession.open(
                context,
                QuickSrSession.Mode.CPU,
                "deferred-output-instrumented",
                model,
                QuickSrSession.Tuning.BASELINE,
                2)) {
            session.infer(input, synchronous, synchronousTimings);
            assertEquals(1, session.runCount());
            try (QuickSrSession.DeferredOutput lease =
                    session.inferDeferred(input, deferredTimings)) {
                deferredTimings.outputCopyNs = lease.copyTo(deferred);
            }
            assertEquals(2, session.runCount());
            assertFalse(deferredTimings.finiteScanExecuted);
        }

        assertArrayEquals(synchronous, deferred, 0.0f);
    }
}
