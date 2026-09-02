package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.Player;

import org.junit.Test;

import java.util.Arrays;

public final class VideoBenchmarkTelemetryTest {
    @Test
    public void configurationPinsRequestedNeuralAndCanvasDimensions() {
        String value = VideoBenchmarkTelemetry.configurationJson("run-1080p", "QUICKSR_QNN",
                QuickSrSession.Tuning.SUSTAINED, QuickSrVideoEffect.Profile.FULL_1080P_3X, true);
        assertTrue(value.contains("\"schemaVersion\":2"));
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
        assertTrue(value.contains("\"queuePolicy\":\"bounded_blocking_backpressure\""));
        assertTrue(value.contains("\"workerQueueCapacity\":2"));
        assertTrue(value.contains("\"workerCleanupReservedSlots\":1"));
        assertTrue(value.contains("\"postprocessMode\":\"SERIAL\""));
        assertTrue(value.contains("\"postprocessQueueCapacity\":0"));
        assertTrue(value.contains("\"outputTensorSlotCount\":1"));
        assertTrue(value.contains("\"outputTensorBytesPerSlot\":24883200"));
        assertTrue(value.contains("\"additionalOverlapTensorBytes\":0"));
        assertTrue(value.contains("\"workerQueueDepthMeasurement\":"
                + "\"measured_frame_admission_queue\""));
        assertTrue(value.contains("\"readbackMeasurement\":"
                + "\"proxy_process_image_callback_after_media3_readback\""));
        assertTrue(value.contains("\"surfaceFlingerLatchMeasurement\":\"unmeasured\""));
        assertTrue(value.contains("\"finalDisplayMeasurement\":\"unmeasured\""));
    }

    @Test
    public void frameBatchKeepsRawStageTimingsAndMonotonicObservation() {
        String value = VideoBenchmarkTelemetry.frameBatchJson("run-frames",
                Arrays.asList(stats(1, 0L, 17L, 9L), stats(2, 41_667L, 18L, 10L)));
        assertEquals(1, VideoBenchmarkTelemetry.FRAME_BATCH_SIZE);
        assertTrue(value.length() <= VideoBenchmarkTelemetry.MAX_LOGCAT_MESSAGE_CHARS);
        assertTrue(value.contains("\"event\":\"frame_batch\""));
        assertTrue(value.contains("\"mode\":\"QNN_HTP\""));
        assertTrue(value.contains("\"tuning\":\"SUSTAINED\""));
        assertTrue(value.contains("\"profile\":\"FULL_1080P_3X\""));
        assertTrue(value.contains("\"postprocessMode\":\"SERIAL\""));
        assertTrue(value.contains("\"frame\":1,\"frameId\":1"));
        assertTrue(value.contains("\"ptsUs\":0"));
        assertTrue(value.contains("\"totalProcessingMs\":17"));
        assertTrue(value.contains("\"ortRunMs\":9"));
        assertTrue(value.contains("\"frame\":2,\"frameId\":2"));
        assertTrue(value.contains("\"ptsUs\":41667"));
        assertTrue(value.contains("\"finiteScanExecuted\":false"));
        assertTrue(value.contains("\"acceptedNs\":-1"));
        assertTrue(value.contains("\"acceptedCount\":1"));
        assertTrue(value.contains("\"bypassedCount\":0"));
    }

    @Test
    public void pipelineSnapshotCarriesAllTerminalCountersAndGenerationEvents() {
        VideoPipelineTelemetry telemetry = new VideoPipelineTelemetry();
        telemetry.accept(0L, 1_000L);
        VideoPipelineTelemetry.Snapshot snapshot = telemetry.flush(2_000L, 0);

        String value = VideoBenchmarkTelemetry.pipelineSnapshotJson(
                "run-snapshot",
                snapshot,
                "flush_seek_proxy");

        assertTrue(value.contains("\"event\":\"pipeline_snapshot\""));
        assertTrue(value.contains("\"generation\":1"));
        assertTrue(value.contains("\"acceptedCount\":1"));
        assertTrue(value.contains("\"droppedCount\":1"));
        assertTrue(value.contains("\"bypassedCount\":0"));
        assertTrue(value.contains("\"flushCount\":1"));
        assertTrue(value.contains("\"seekProxyCount\":1"));
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

    @Test
    public void benchmarkPlaybackRepeatModeNeverLeaksToNormalPlayback() {
        assertEquals(Player.REPEAT_MODE_ONE, SuperResolutionActivity.repeatModeForBenchmark(true));
        assertEquals(Player.REPEAT_MODE_OFF, SuperResolutionActivity.repeatModeForBenchmark(false));
    }

    @Test
    public void identicalVideoEffectConfigurationIsNotAppliedTwice() {
        assertTrue(SuperResolutionActivity.shouldApplyVideoEffect(null, "QNN:720p:run-1"));
        assertFalse(SuperResolutionActivity.shouldApplyVideoEffect(
                "QNN:720p:run-1",
                "QNN:720p:run-1"));
        assertTrue(SuperResolutionActivity.shouldApplyVideoEffect(
                "QNN:720p:run-1",
                "QNN:1080p:run-1"));
        assertTrue(SuperResolutionActivity.shouldApplyVideoEffect(
                "QNN:720p:run-1",
                "QNN:720p:run-2"));
        assertEquals(5_000L, SuperResolutionActivity.PLAYER_RELEASE_TIMEOUT_MS);
    }

    @Test
    public void qnnStrictTelemetryCarriesFailClosedRegistrationFields() throws Exception {
        String configuration = VideoBenchmarkTelemetry.configurationJson(
                "run-qnn-strict",
                "QUICKSR_QNN",
                QuickSrSession.Tuning.SUSTAINED,
                QuickSrVideoEffect.Profile.FULL_1080P_3X,
                true,
                VideoEvidenceStore.CaptureSpec.forFrame(2));
        String event = VideoBenchmarkTelemetry.qnnStrictJson(
                "run-qnn-strict",
                QuickSrVideoEffect.Profile.FULL_1080P_3X,
                "{\"registrationStatus\":\"PASS\",\"npuSelectionStatus\":\"PASS\","
                        + "\"providerConfigurationStatus\":\"PASS\",\"backendType\":\"htp\","
                        + "\"cpuEpFallbackDisabled\":true,\"strictReady\":true}");

        assertTrue(configuration.contains("\"qnnStrictRequired\":true"));
        assertTrue(configuration.contains("\"captureSelectorKind\":\"frame\""));
        assertTrue(event.contains("\"event\":\"qnn_strict\""));
        assertTrue(event.contains("\"modelVariant\":\"fixed640x360-3x-full\""));
        assertTrue(event.contains("\"cpuEpFallbackDisabled\":true"));
        assertTrue(event.contains("\"providerAssignmentVerified\":false"));
        assertTrue(event.contains("\"providerFallbackTraceCaptured\":false"));
        assertTrue(event.contains(
                "\"evidenceScope\":\"SESSION_CONFIGURATION_NOT_PER_NODE_PLACEMENT_PROOF\""));
    }

    private static QuickSrVideoEffect.FrameStats stats(int frame, long ptsUs, long totalMs, long ortMs) {
        return new QuickSrVideoEffect.FrameStats(QuickSrSession.Mode.QNN_HTP,
                QuickSrSession.Tuning.SUSTAINED, QuickSrVideoEffect.Profile.FULL_1080P_3X,
                frame, 1920, 1080, 1L, 2L, 3L, 4L, 12L, 1L, ortMs, 2L, 0L,
                false, 5L, totalMs, ptsUs);
    }
}
