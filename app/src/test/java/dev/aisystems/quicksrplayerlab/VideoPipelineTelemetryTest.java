package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VideoPipelineTelemetryTest {
    @Test
    public void recordsBoundedQueueCountersAndPtsWallClockDrift() {
        VideoPipelineTelemetry telemetry = new VideoPipelineTelemetry();
        VideoPipelineTelemetry.FrameToken first = telemetry.accept(0L, 1_000_000_000L);
        telemetry.claimReadback(0L, 1_001_000_000L);
        VideoPipelineTelemetry.Completion firstCompletion = telemetry.markProcessed(
                first,
                1_030_000_000L,
                0);

        VideoPipelineTelemetry.FrameToken second = telemetry.accept(40_000L, 1_040_000_000L);
        telemetry.claimReadback(40_000L, 1_041_000_000L);
        telemetry.observeQueueDepth(VideoPipelineTelemetry.WORKER_QUEUE_CAPACITY);
        VideoPipelineTelemetry.Completion secondCompletion = telemetry.markProcessed(
                second,
                1_095_000_000L,
                0);

        assertTrue(firstCompletion.processed);
        assertFalse(firstCompletion.late);
        assertEquals(30_000_000L, firstCompletion.ptsWallClockDriftNs);
        assertTrue(secondCompletion.processed);
        assertTrue(secondCompletion.late);
        assertEquals(55_000_000L, secondCompletion.ptsWallClockDriftNs);
        assertEquals(2L, secondCompletion.snapshot.accepted);
        assertEquals(2L, secondCompletion.snapshot.processed);
        assertEquals(1L, secondCompletion.snapshot.late);
        assertEquals(0L, secondCompletion.snapshot.dropped);
        assertEquals(0L, secondCompletion.snapshot.bypassed);
        assertEquals(VideoPipelineTelemetry.WORKER_QUEUE_CAPACITY,
                secondCompletion.snapshot.maxQueueDepth);
    }

    @Test
    public void flushAdvancesGenerationAndDropsOldAcceptedFrames() {
        VideoPipelineTelemetry telemetry = new VideoPipelineTelemetry();
        VideoPipelineTelemetry.FrameToken old = telemetry.accept(2_000_000L, 10_000L);

        VideoPipelineTelemetry.Snapshot flushed = telemetry.flush(20_000L, 0);
        VideoPipelineTelemetry.FrameToken current = telemetry.accept(100_000L, 30_000L);
        telemetry.claimReadback(100_000L, 31_000L);

        assertEquals(1, flushed.generation);
        assertEquals(1L, flushed.flushCount);
        assertEquals(1L, flushed.seekProxyCount);
        assertEquals(1L, flushed.dropped);
        assertFalse(telemetry.isCurrent(old));
        assertTrue(telemetry.isCurrent(current));
        assertEquals(1, current.generation);
        assertEquals(1L, current.generationFrameId);
        VideoPipelineTelemetry.Completion stale = telemetry.markProcessed(old, 40_000L, 0);
        assertFalse(stale.processed);
        assertEquals(1L, stale.snapshot.dropped);
    }

    @Test(expected = IllegalStateException.class)
    public void acceptedReadbackLedgerCannotExceedPinnedMedia3Capacity() {
        VideoPipelineTelemetry telemetry = new VideoPipelineTelemetry();
        for (int index = 0; index <= VideoPipelineTelemetry.MEDIA3_EFFECT_QUEUE_CAPACITY; index++) {
            telemetry.accept(index, 1_000L + index);
        }
    }

    @Test
    public void releaseDropsAcceptedFramesWithoutClaimingSeekOrFlush() {
        VideoPipelineTelemetry telemetry = new VideoPipelineTelemetry();
        telemetry.accept(10L, 1_000L);

        VideoPipelineTelemetry.Snapshot released = telemetry.release(2_000L, 0);

        assertEquals(1L, released.accepted);
        assertEquals(1L, released.dropped);
        assertEquals(0L, released.flushCount);
        assertEquals(0L, released.seekProxyCount);
    }

    @Test(expected = IllegalStateException.class)
    public void readbackIdentityMismatchFailsClosed() {
        VideoPipelineTelemetry telemetry = new VideoPipelineTelemetry();
        telemetry.accept(10L, 1_000L);
        telemetry.claimReadback(11L, 2_000L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void queueDepthCannotExceedDeclaredCapacity() {
        VideoPipelineTelemetry telemetry = new VideoPipelineTelemetry();
        telemetry.observeQueueDepth(VideoPipelineTelemetry.WORKER_QUEUE_CAPACITY + 1);
    }
}
