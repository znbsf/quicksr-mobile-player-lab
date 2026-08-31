package dev.aisystems.quicksrplayerlab;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure-Java validation and normalization for decoded video presentation timestamps. */
final class VideoFrameTimeline {
    private VideoFrameTimeline() {
    }

    static long[] normalize(List<Long> rawPresentationTimesUs, int metadataFrameCount) {
        if (metadataFrameCount <= 0) {
            throw new IllegalArgumentException("Video frame count is unavailable");
        }
        if (rawPresentationTimesUs.size() != metadataFrameCount) {
            throw new IllegalArgumentException(
                    "Video sample/frame count mismatch: samples=" +
                            rawPresentationTimesUs.size() +
                            ", frames=" + metadataFrameCount);
        }
        List<Long> sorted = new ArrayList<>(rawPresentationTimesUs.size());
        for (Long timestamp : rawPresentationTimesUs) {
            if (timestamp == null || timestamp < 0) {
                throw new IllegalArgumentException("Video timestamp is missing or negative");
            }
            sorted.add(timestamp);
        }
        Collections.sort(sorted);
        long originUs = sorted.get(0);
        long[] result = new long[sorted.size()];
        long previous = -1;
        for (int index = 0; index < sorted.size(); index++) {
            long normalized = sorted.get(index) - originUs;
            if (normalized <= previous) {
                normalized = Math.addExact(previous, 1L);
            }
            result[index] = normalized;
            previous = normalized;
        }
        return result;
    }

    static int estimateFrameRate(long[] presentationTimesUs) {
        if (presentationTimesUs.length <= 1) {
            return 1;
        }
        long spanUs = presentationTimesUs[presentationTimesUs.length - 1] -
                presentationTimesUs[0];
        if (spanUs <= 0) {
            return 1;
        }
        double framesPerSecond =
                (presentationTimesUs.length - 1) * 1_000_000.0 / spanUs;
        return Math.max(1, Math.min(60, (int) Math.round(framesPerSecond)));
    }
}
