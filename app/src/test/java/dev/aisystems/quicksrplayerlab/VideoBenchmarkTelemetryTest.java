package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public final class VideoBenchmarkTelemetryTest {
    @Test
    public void configurationPinsRequestedNeuralAndCanvasDimensions() {
        String value = VideoBenchmarkTelemetry.configurationJson("run-1080p", "QUICKSR_QNN",
                QuickSrSession.Tuning.SUSTAINED, QuickSrVideoEffect.Profile.FULL_1080P_3X, true);
        assertTrue(value.contains("\"schemaVersion\":1"));
        assertTrue(value.contains("\"event\":\"configuration\""));
        assertTrue(value.contains("\"runId\":\"run-1080p\""));
        assertTrue(value.contains("\"mode\":\"QUICKSR_QNN\""));
        assertTrue(value.contains("\"tuning\":\"SUSTAINED\""));
        assertTrue(value.contains("\"profile\":\"FULL_1080P_3X\""));
        assertTrue(value.contains("\"modelInputWidth\":640"));
        assertTrue(value.contains("\"modelInputHeight\":360"));
        assertTrue(value.contains("\"modelOutputWidth\":1920"));
        assertTrue(value.contains("\"modelOutputHeight\":1080"));
        assertTrue(value.contains("\"canvasWidth\":1920"));
        assertTrue(value.contains("\"canvasHeight\":1080"));
        assertTrue(value.contains("\"qnnRuntimeExpected\":true"));
    }

    @Test
    public void frameBatchKeepsRawStageTimingsAndMonotonicObservation() {
        String value = VideoBenchmarkTelemetry.frameBatchJson("run-frames",
                Arrays.asList(stats(1, 0L, 17L, 9L), stats(2, 41_667L, 18L, 10L)));
        assertTrue(value.contains("\"event\":\"frame_batch\""));
        assertTrue(value.contains("\"mode\":\"QNN_HTP\""));
        assertTrue(value.contains("\"tuning\":\"SUSTAINED\""));
        assertTrue(value.contains("\"profile\":\"FULL_1080P_3X\""));
        assertTrue(value.contains("\"frame\":1,\"ptsUs\":0,\"observedNs\":"));
        assertTrue(value.contains("\"totalProcessingMs\":17"));
        assertTrue(value.contains("\"ortRunMs\":9"));
        assertTrue(value.contains("\"frame\":2,\"ptsUs\":41667"));
        assertTrue(value.contains("\"finiteScanExecuted\":false"));
    }

    @Test
    public void errorJsonEscapesLogContent() {
        String value = VideoBenchmarkTelemetry.errorJson("run-error", "player", "bad \"frame\"\nretry");
        assertTrue(value.contains("\\\"frame\\\""));
        assertTrue(value.contains("\\" + "nretry"));
        assertFalse(value.contains("bad \"frame\"\nretry"));
    }

    @Test
    public void runIdContractRejectsUnsafeOrAmbiguousValues() {
        assertTrue(SuperResolutionActivity.validBenchmarkRunId("1080p-primary_20260901-01"));
        assertFalse(SuperResolutionActivity.validBenchmarkRunId(""));
        assertFalse(SuperResolutionActivity.validBenchmarkRunId("contains spaces"));
        assertFalse(SuperResolutionActivity.validBenchmarkRunId(".." + "/escape"));
    }

    private static QuickSrVideoEffect.FrameStats stats(int frame, long ptsUs, long totalMs, long ortMs) {
        return new QuickSrVideoEffect.FrameStats(QuickSrSession.Mode.QNN_HTP,
                QuickSrSession.Tuning.SUSTAINED, QuickSrVideoEffect.Profile.FULL_1080P_3X,
                frame, 1920, 1080, 1L, 2L, 3L, 4L, 12L, 1L, ortMs, 2L, 0L,
                false, 5L, totalMs, ptsUs);
    }
}
