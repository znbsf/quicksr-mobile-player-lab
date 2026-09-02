package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VideoEvidenceStoreTest {
    @Test
    public void captureSelectorIsExplicitAndFailsWhenTheRequestedPointIsSkipped() {
        VideoEvidenceStore.CaptureSpec frame = VideoEvidenceStore.CaptureSpec.forFrame(3);
        VideoEvidenceStore.CaptureSpec pts =
                VideoEvidenceStore.CaptureSpec.forPresentationTimeUs(41_667L);

        assertTrue(frame.isRequested());
        assertTrue(frame.matches(3, 0L));
        assertFalse(frame.matches(2, 0L));
        assertTrue(frame.hasBeenMissedBy(4, 0L));
        assertEquals("frame", frame.telemetryKind());

        assertTrue(pts.matches(1, 41_667L));
        assertFalse(pts.matches(1, 41_666L));
        assertTrue(pts.hasBeenMissedBy(1, 41_668L));
        assertEquals("ptsUs", pts.telemetryKind());
    }

    @Test
    public void artifactNamingIsRelativeAndSafeForAdbRetrieval() {
        String runId = "1080p-primary_20260902-01";
        assertEquals("video-evaluations/" + runId,
                VideoEvidenceStore.relativeDirectory(runId));
        assertEquals("video-evaluations/" + runId + "/input.f32le",
                VideoEvidenceStore.relativeTensorPath(runId, VideoEvidenceStore.INPUT_FILE));
        assertEquals("video-evaluations/" + runId + "/output.f32le",
                VideoEvidenceStore.relativeTensorPath(runId, VideoEvidenceStore.OUTPUT_FILE));
        assertFalse(VideoEvidenceStore.relativeDirectory(runId).contains("/data/"));
        assertFalse(VideoEvidenceStore.relativeDirectory(runId).contains("ADSP_LIBRARY_PATH"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void captureFrameMustBeOneBased() {
        VideoEvidenceStore.CaptureSpec.forFrame(0);
    }
}
