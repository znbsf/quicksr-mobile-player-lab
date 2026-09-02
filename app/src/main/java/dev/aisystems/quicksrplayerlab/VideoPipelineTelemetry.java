package dev.aisystems.quicksrplayerlab;

import java.util.ArrayDeque;

/**
 * Thread-safe lifecycle accounting for the bounded QuickSR video pipeline.
 *
 * <p>This class deliberately contains no Android dependencies so generation, flush, queue and
 * late-frame semantics can be exercised by host unit tests. A Media3 {@code flush()} is the
 * public-API proxy used for seek isolation; it is not a direct seek callback.
 */
final class VideoPipelineTelemetry {
    static final String QUEUE_POLICY = "bounded_blocking_backpressure";
    static final int WORKER_QUEUE_CAPACITY = 2;
    static final int WORKER_CLEANUP_RESERVED_SLOTS = 1;
    static final int MEDIA3_EFFECT_QUEUE_CAPACITY = 6;
    static final int MEDIA3_PENDING_PBO_QUEUE_CAPACITY = 1;

    static final class FrameToken {
        final long frameId;
        final int generation;
        final long generationFrameId;
        final long presentationTimeUs;
        final long acceptedNs;
        final long frameBudgetNs;
        long cadenceStreamEpoch;
        long readbackReadyProxyNs;
        private boolean terminal;

        FrameToken(
                long frameId,
                int generation,
                long generationFrameId,
                long presentationTimeUs,
                long acceptedNs,
                long frameBudgetNs) {
            this.frameId = frameId;
            this.generation = generation;
            this.generationFrameId = generationFrameId;
            this.presentationTimeUs = presentationTimeUs;
            this.acceptedNs = acceptedNs;
            this.frameBudgetNs = frameBudgetNs;
        }
    }

    static final class Snapshot {
        final long observedNs;
        final int generation;
        final long accepted;
        final long processed;
        final long late;
        final long dropped;
        final long bypassed;
        final int currentQueueDepth;
        final int maxQueueDepth;
        final long flushCount;
        final long seekProxyCount;

        Snapshot(
                long observedNs,
                int generation,
                long accepted,
                long processed,
                long late,
                long dropped,
                long bypassed,
                int currentQueueDepth,
                int maxQueueDepth,
                long flushCount,
                long seekProxyCount) {
            this.observedNs = observedNs;
            this.generation = generation;
            this.accepted = accepted;
            this.processed = processed;
            this.late = late;
            this.dropped = dropped;
            this.bypassed = bypassed;
            this.currentQueueDepth = currentQueueDepth;
            this.maxQueueDepth = maxQueueDepth;
            this.flushCount = flushCount;
            this.seekProxyCount = seekProxyCount;
        }
    }

    static final class Completion {
        final boolean processed;
        final boolean late;
        final long ptsWallClockDriftNs;
        final Snapshot snapshot;

        Completion(
                boolean processed,
                boolean late,
                long ptsWallClockDriftNs,
                Snapshot snapshot) {
            this.processed = processed;
            this.late = late;
            this.ptsWallClockDriftNs = ptsWallClockDriftNs;
            this.snapshot = snapshot;
        }
    }

    private final ArrayDeque<FrameToken> acceptedAwaitingReadback = new ArrayDeque<>();
    private int generation;
    private long nextFrameId;
    private long nextGenerationFrameId;
    private long accepted;
    private long processed;
    private long late;
    private long dropped;
    private long bypassed;
    private int currentQueueDepth;
    private int maxQueueDepth;
    private long flushCount;
    private long seekProxyCount;
    private long generationOriginPtsUs = Long.MIN_VALUE;
    private long generationOriginAcceptedNs;
    private long previousPtsUs = Long.MIN_VALUE;

    synchronized FrameToken accept(long presentationTimeUs, long acceptedNs) {
        if (presentationTimeUs < 0 || acceptedNs <= 0) {
            throw new IllegalArgumentException("Frame acceptance requires non-negative PTS and positive time");
        }
        if (acceptedAwaitingReadback.size() >= MEDIA3_EFFECT_QUEUE_CAPACITY) {
            throw new IllegalStateException(
                    "Accepted/readback queue exceeded pinned Media3 capacity "
                            + MEDIA3_EFFECT_QUEUE_CAPACITY);
        }
        long budgetNs = 0L;
        if (previousPtsUs != Long.MIN_VALUE && presentationTimeUs > previousPtsUs) {
            budgetNs = Math.multiplyExact(presentationTimeUs - previousPtsUs, 1_000L);
        }
        if (generationOriginPtsUs == Long.MIN_VALUE) {
            generationOriginPtsUs = presentationTimeUs;
            generationOriginAcceptedNs = acceptedNs;
        }
        previousPtsUs = presentationTimeUs;
        FrameToken token = new FrameToken(
                ++nextFrameId,
                generation,
                ++nextGenerationFrameId,
                presentationTimeUs,
                acceptedNs,
                budgetNs);
        accepted++;
        acceptedAwaitingReadback.addLast(token);
        return token;
    }

    synchronized FrameToken claimReadback(long presentationTimeUs, long readbackReadyProxyNs) {
        FrameToken token = acceptedAwaitingReadback.pollFirst();
        if (token == null) {
            throw new IllegalStateException(
                    "Media3 readback callback has no matching accepted frame at ptsUs="
                            + presentationTimeUs);
        }
        if (token.presentationTimeUs != presentationTimeUs || token.generation != generation) {
            markDroppedLocked(token);
            throw new IllegalStateException(
                    "Media3 frame identity mismatch: accepted frameId=" + token.frameId
                            + " generation=" + token.generation
                            + " ptsUs=" + token.presentationTimeUs
                            + ", callback generation=" + generation
                            + " ptsUs=" + presentationTimeUs);
        }
        if (readbackReadyProxyNs < token.acceptedNs) {
            markDroppedLocked(token);
            throw new IllegalArgumentException("Readback proxy time precedes frame acceptance");
        }
        token.readbackReadyProxyNs = readbackReadyProxyNs;
        return token;
    }

    synchronized boolean isCurrent(FrameToken token) {
        return token != null && !token.terminal && token.generation == generation;
    }

    synchronized Snapshot cancelAcceptance(FrameToken token, long observedNs, int queueDepth) {
        observeQueueDepth(queueDepth);
        acceptedAwaitingReadback.remove(token);
        markDroppedLocked(token);
        return snapshotLocked(observedNs);
    }

    synchronized void observeQueueDepth(int queueDepth) {
        if (queueDepth < 0 || queueDepth > WORKER_QUEUE_CAPACITY) {
            throw new IllegalArgumentException(
                    "Worker queue depth is outside the bounded contract: " + queueDepth);
        }
        currentQueueDepth = queueDepth;
        maxQueueDepth = Math.max(maxQueueDepth, queueDepth);
    }

    synchronized Completion markProcessed(
            FrameToken token,
            long outputSubmittedProxyNs,
            int queueDepth) {
        observeQueueDepth(queueDepth);
        if (token == null || token.terminal) {
            return new Completion(false, false, Long.MIN_VALUE, snapshotLocked(outputSubmittedProxyNs));
        }
        if (token.generation != generation) {
            markDroppedLocked(token);
            return new Completion(false, false, Long.MIN_VALUE, snapshotLocked(outputSubmittedProxyNs));
        }
        long expectedWallNs = Math.addExact(
                generationOriginAcceptedNs,
                Math.multiplyExact(token.presentationTimeUs - generationOriginPtsUs, 1_000L));
        long driftNs = outputSubmittedProxyNs - expectedWallNs;
        boolean frameLate = token.frameBudgetNs > 0 && driftNs > token.frameBudgetNs;
        token.terminal = true;
        processed++;
        if (frameLate) {
            late++;
        }
        return new Completion(true, frameLate, driftNs, snapshotLocked(outputSubmittedProxyNs));
    }

    synchronized Snapshot markDropped(FrameToken token, long observedNs, int queueDepth) {
        observeQueueDepth(queueDepth);
        if (token != null) {
            markDroppedLocked(token);
        }
        return snapshotLocked(observedNs);
    }

    synchronized Snapshot flush(long observedNs, int queueDepth) {
        observeQueueDepth(queueDepth);
        flushCount++;
        seekProxyCount++;
        while (!acceptedAwaitingReadback.isEmpty()) {
            markDroppedLocked(acceptedAwaitingReadback.removeFirst());
        }
        generation++;
        nextGenerationFrameId = 0L;
        generationOriginPtsUs = Long.MIN_VALUE;
        generationOriginAcceptedNs = 0L;
        previousPtsUs = Long.MIN_VALUE;
        return snapshotLocked(observedNs);
    }

    synchronized Snapshot release(long observedNs, int queueDepth) {
        observeQueueDepth(queueDepth);
        while (!acceptedAwaitingReadback.isEmpty()) {
            markDroppedLocked(acceptedAwaitingReadback.removeFirst());
        }
        return snapshotLocked(observedNs);
    }

    synchronized Snapshot snapshot(long observedNs, int queueDepth) {
        observeQueueDepth(queueDepth);
        return snapshotLocked(observedNs);
    }

    private void markDroppedLocked(FrameToken token) {
        if (!token.terminal) {
            token.terminal = true;
            dropped++;
        }
    }

    private Snapshot snapshotLocked(long observedNs) {
        return new Snapshot(
                observedNs,
                generation,
                accepted,
                processed,
                late,
                dropped,
                bypassed,
                currentQueueDepth,
                maxQueueDepth,
                flushCount,
                seekProxyCount);
    }
}
