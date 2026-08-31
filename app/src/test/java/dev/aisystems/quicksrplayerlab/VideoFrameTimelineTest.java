package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;

public final class VideoFrameTimelineTest {
    @Test
    public void normalizesPresentationOrderAndOrigin() {
        long[] result = VideoFrameTimeline.normalize(
                Arrays.asList(1_100_000L, 1_000_000L, 1_050_000L),
                3);

        assertArrayEquals(new long[]{0L, 50_000L, 100_000L}, result);
        assertEquals(20, VideoFrameTimeline.estimateFrameRate(result));
    }

    @Test
    public void makesDuplicatePresentationTimesStrictlyIncreasing() {
        long[] result = VideoFrameTimeline.normalize(
                Arrays.asList(9L, 9L, 9L),
                3);

        assertArrayEquals(new long[]{0L, 1L, 2L}, result);
        assertEquals(60, VideoFrameTimeline.estimateFrameRate(result));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsSampleAndFrameCountMismatch() {
        VideoFrameTimeline.normalize(Arrays.asList(0L, 33_333L), 3);
    }
}
