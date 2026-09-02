package dev.aisystems.quicksrplayerlab;

import androidx.media3.common.util.UnstableApi;

import org.json.JSONObject;

import java.util.List;

@UnstableApi
final class VideoBenchmarkTelemetry {
    static final String TAG = "QuickSRBenchmark";
    static final String EXTRA_RUN_ID = "dev.aisystems.quicksrplayerlab.extra.BENCHMARK_RUN_ID";
    static final String EXTRA_VIDEO_MODE = "dev.aisystems.quicksrplayerlab.extra.VIDEO_MODE";
    static final String EXTRA_VIDEO_PROFILE = "dev.aisystems.quicksrplayerlab.extra.VIDEO_PROFILE";
    static final String EXTRA_VIDEO_TUNING = "dev.aisystems.quicksrplayerlab.extra.VIDEO_TUNING";
    static final String EXTRA_CAPTURE_FRAME = "dev.aisystems.quicksrplayerlab.extra.CAPTURE_FRAME";
    static final String EXTRA_CAPTURE_PTS_US = "dev.aisystems.quicksrplayerlab.extra.CAPTURE_PTS_US";
    // Android's logger truncates messages around 4 KiB. One fully instrumented frame is already
    // roughly 1.5 KiB, so emit it alone and fail closed if future schema growth crosses the guard.
    static final int FRAME_BATCH_SIZE = 1;
    static final int MAX_LOGCAT_MESSAGE_CHARS = 3_800;

    private VideoBenchmarkTelemetry() {}

    static String configurationJson(String runId, String mode, QuickSrSession.Tuning tuning,
            QuickSrVideoEffect.Profile profile, boolean qnnRuntimeExpected) {
        return configurationJson(
                runId,
                mode,
                tuning,
                profile,
                qnnRuntimeExpected,
                VideoEvidenceStore.CaptureSpec.none());
    }

    static String configurationJson(String runId, String mode, QuickSrSession.Tuning tuning,
            QuickSrVideoEffect.Profile profile, boolean qnnRuntimeExpected,
            VideoEvidenceStore.CaptureSpec captureSpec) {
        return configurationJson(
                runId,
                mode,
                tuning,
                profile,
                qnnRuntimeExpected,
                captureSpec,
                false);
    }

    static String configurationJson(String runId, String mode, QuickSrSession.Tuning tuning,
            QuickSrVideoEffect.Profile profile, boolean qnnRuntimeExpected,
            VideoEvidenceStore.CaptureSpec captureSpec, boolean postprocessOverlap) {
        StringBuilder value = envelope("configuration", runId);
        stringField(value, "mode", mode);
        stringField(value, "tuning", tuning.name());
        stringField(value, "profile", profile.name());
        stringField(
                value,
                "postprocessMode",
                postprocessOverlap ? "OVERLAP" : "SERIAL");
        numberField(
                value,
                "postprocessQueueCapacity",
                QuickSrVideoEffect.postprocessQueueCapacity(postprocessOverlap));
        numberField(
                value,
                "outputTensorSlotCount",
                QuickSrVideoEffect.outputTensorSlotCount(postprocessOverlap));
        numberField(
                value,
                "outputTensorBytesPerSlot",
                QuickSrVideoEffect.outputTensorBytesPerSlot(profile));
        numberField(
                value,
                "additionalOverlapTensorBytes",
                QuickSrVideoEffect.additionalOverlapTensorBytes(profile, postprocessOverlap));
        booleanField(value, "qnnRuntimeExpected", qnnRuntimeExpected);
        booleanField(value, "qnnStrictRequired", "QUICKSR_QNN".equals(mode));
        if (captureSpec != null && captureSpec.isRequested()) {
            stringField(value, "captureSelectorKind", captureSpec.telemetryKind());
            numberField(value, "captureSelectorValue", captureSpec.value());
        }
        numberField(value, "modelInputWidth", profile.inputWidth());
        numberField(value, "modelInputHeight", profile.inputHeight());
        numberField(value, "modelOutputWidth", profile.outputWidth());
        numberField(value, "modelOutputHeight", profile.outputHeight());
        numberField(value, "canvasWidth", profile.canvasWidth());
        numberField(value, "canvasHeight", profile.canvasHeight());
        stringField(value, "modelVariant", profile.modelVariant().id());
        stringField(value, "modelSha256", profile.modelVariant().expectedSha256());
        stringField(value, "sourceIdentitySha256", BuildConfig.APP_SOURCE_SHA256);
        stringField(value, "prototypeBuildId", BuildConfig.PROTOTYPE_BUILD_ID);
        stringField(value, "targetAbi", BuildConfig.TARGET_ABI);
        stringField(value, "queuePolicy", VideoPipelineTelemetry.QUEUE_POLICY);
        numberField(
                value,
                "workerQueueCapacity",
                VideoPipelineTelemetry.WORKER_QUEUE_CAPACITY);
        numberField(
                value,
                "workerCleanupReservedSlots",
                VideoPipelineTelemetry.WORKER_CLEANUP_RESERVED_SLOTS);
        numberField(
                value,
                "media3EffectQueueCapacity",
                VideoPipelineTelemetry.MEDIA3_EFFECT_QUEUE_CAPACITY);
        numberField(
                value,
                "media3PendingPboQueueCapacity",
                VideoPipelineTelemetry.MEDIA3_PENDING_PBO_QUEUE_CAPACITY);
        stringField(value, "workerQueueDepthMeasurement", "measured_frame_admission_queue");
        stringField(
                value,
                "media3QueueDepthMeasurement",
                "unmeasured_fixed_library_internal_queue");
        stringField(value, "acceptedMeasurement", "measured_queue_input_callback");
        stringField(value, "readbackMeasurement", "proxy_process_image_callback_after_media3_readback");
        stringField(value, "preprocessMeasurement", "measured_cpu_elapsed_realtime_ns");
        stringField(value, "outputTensorSlotWaitMeasurement", "measured_bounded_semaphore_wait");
        stringField(value, "outputTensorPrepareMeasurement", "measured_pool_or_allocation_time");
        stringField(value, "ortMeasurement", "measured_caller_wall_ns_not_npu_kernel");
        stringField(value, "outputPackMeasurement", "measured_cpu_elapsed_realtime_ns");
        stringField(value, "directBufferCopyMeasurement", "measured_cpu_elapsed_realtime_ns");
        stringField(value, "glUploadMeasurement", "proxy_cpu_gl_submission_not_gpu_completion");
        stringField(value, "outputSubmitMeasurement", "proxy_finish_processing_callback");
        stringField(value, "seekMeasurement", "proxy_media3_flush");
        stringField(
                value,
                "ptsWallClockDriftMeasurement",
                "proxy_generation_relative_to_first_accepted_frame");
        stringField(value, "surfaceFlingerLatchMeasurement", "unmeasured");
        stringField(value, "finalDisplayMeasurement", "unmeasured");
        return finish(value);
    }

    static String frameBatchJson(String runId, List<QuickSrVideoEffect.FrameStats> samples) {
        if (samples.isEmpty()) {
            throw new IllegalArgumentException("benchmark frame batch must not be empty");
        }
        QuickSrVideoEffect.FrameStats first = samples.get(0);
        StringBuilder value = envelope("frame_batch", runId);
        stringField(value, "mode", first.mode.name());
        stringField(value, "tuning", first.tuning.name());
        stringField(value, "profile", first.profile.name());
        stringField(value, "postprocessMode", first.postprocessMode.name());
        numberField(value, "effectInputWidth", first.effectInputWidth);
        numberField(value, "effectInputHeight", first.effectInputHeight);
        numberField(value, "modelInputWidth", first.modelInputWidth);
        numberField(value, "modelInputHeight", first.modelInputHeight);
        numberField(value, "modelOutputWidth", first.modelOutputWidth);
        numberField(value, "modelOutputHeight", first.modelOutputHeight);
        value.append(",\"samples\":[");
        for (int index = 0; index < samples.size(); index++) {
            QuickSrVideoEffect.FrameStats sample = samples.get(index);
            if (sample.mode != first.mode || sample.tuning != first.tuning
                    || sample.profile != first.profile
                    || sample.postprocessMode != first.postprocessMode) {
                throw new IllegalArgumentException(
                        "benchmark frame batch mixes mode, tuning, profile or postprocess mode");
            }
            if (index > 0) value.append(',');
            value.append('{');
            bareNumberField(value, "frame", sample.frameNumber);
            numberField(value, "frameId", sample.frameId);
            numberField(value, "generation", sample.generation);
            numberField(value, "generationFrameId", sample.generationFrameId);
            numberField(value, "ptsUs", sample.presentationTimeUs);
            stringField(value, "inputCrc32", sample.inputCrc32);
            stringField(value, "outputCrc32", sample.outputCrc32);
            booleanField(value, "late", sample.late);
            numberField(value, "ptsWallClockDriftNs", sample.ptsWallClockDriftNs);
            numberField(value, "observedNs", sample.observedMonotonicNs);
            numberField(value, "acceptedNs", sample.acceptedNs);
            numberField(value, "readbackReadyProxyNs", sample.readbackReadyProxyNs);
            numberField(value, "inputCopyStartedNs", sample.inputCopyStartedNs);
            numberField(value, "inputCopiedNs", sample.inputCopiedNs);
            numberField(value, "inputHashStartedNs", sample.inputHashStartedNs);
            numberField(value, "inputHashFinishedNs", sample.inputHashFinishedNs);
            numberField(value, "workerStartedNs", sample.workerStartedNs);
            numberField(value, "outputTensorAcquireStartedNs", sample.outputTensorAcquireStartedNs);
            numberField(value, "outputTensorSlotAcquiredNs", sample.outputTensorSlotAcquiredNs);
            numberField(value, "outputTensorReadyNs", sample.outputTensorReadyNs);
            numberField(value, "preprocessFinishedNs", sample.preprocessFinishedNs);
            numberField(value, "sessionReadyNs", sample.sessionReadyNs);
            numberField(value, "inferenceStartedNs", sample.inferenceStartedNs);
            numberField(value, "inferenceFinishedNs", sample.inferenceFinishedNs);
            numberField(value, "outputPackStartedNs", sample.outputPackStartedNs);
            numberField(value, "outputPackFinishedNs", sample.outputPackFinishedNs);
            numberField(value, "outputHashStartedNs", sample.outputHashStartedNs);
            numberField(value, "outputHashFinishedNs", sample.outputHashFinishedNs);
            numberField(value, "directBufferCopyStartedNs", sample.directBufferCopyStartedNs);
            numberField(value, "directBufferCopyFinishedNs", sample.directBufferCopyFinishedNs);
            numberField(value, "outputReadyNs", sample.outputReadyNs);
            numberField(value, "glUploadStartedNs", sample.glUploadStartedNs);
            numberField(value, "glUploadFinishedNs", sample.glUploadFinishedNs);
            numberField(value, "outputSubmittedProxyNs", sample.outputSubmittedProxyNs);
            numberField(value, "tensorInputCopyNs", sample.tensorInputCopyNs);
            numberField(value, "ortRunNs", sample.ortRunNs);
            numberField(value, "tensorOutputCopyNs", sample.tensorOutputCopyNs);
            numberField(value, "finiteScanNs", sample.finiteScanNs);
            numberField(value, "acceptedCount", sample.acceptedCount);
            numberField(value, "processedCount", sample.processedCount);
            numberField(value, "lateCount", sample.lateCount);
            numberField(value, "droppedCount", sample.droppedCount);
            numberField(value, "bypassedCount", sample.bypassedCount);
            numberField(value, "currentQueueDepth", sample.currentQueueDepth);
            numberField(value, "maxQueueDepth", sample.maxQueueDepth);
            numberField(value, "flushCount", sample.flushCount);
            numberField(value, "seekProxyCount", sample.seekProxyCount);
            numberField(value, "sessionSetupMs", sample.sessionSetupMs);
            numberField(value, "copyMs", sample.copyMs);
            numberField(value, "queueMs", sample.queueMs);
            numberField(value, "inputConversionMs", sample.inputConversionMs);
            numberField(value, "inferenceMs", sample.inferenceMs);
            numberField(value, "tensorInputCopyMs", sample.tensorInputCopyMs);
            numberField(value, "ortRunMs", sample.ortRunMs);
            numberField(value, "tensorOutputCopyMs", sample.tensorOutputCopyMs);
            numberField(value, "finiteScanMs", sample.finiteScanMs);
            booleanField(value, "finiteScanExecuted", sample.finiteScanExecuted);
            numberField(value, "outputConversionMs", sample.outputConversionMs);
            numberField(value, "totalProcessingMs", sample.totalProcessingMs);
            value.append('}');
        }
        value.append(']');
        String json = finish(value);
        if (json.length() > MAX_LOGCAT_MESSAGE_CHARS) {
            throw new IllegalStateException(
                    "benchmark frame event exceeds the safe logcat payload: " + json.length());
        }
        return json;
    }

    static String pipelineSnapshotJson(
            String runId,
            VideoPipelineTelemetry.Snapshot snapshot,
            String reason) {
        StringBuilder value = envelope("pipeline_snapshot", runId);
        stringField(value, "reason", reason);
        numberField(value, "observedNs", snapshot.observedNs);
        numberField(value, "generation", snapshot.generation);
        numberField(value, "acceptedCount", snapshot.accepted);
        numberField(value, "processedCount", snapshot.processed);
        numberField(value, "lateCount", snapshot.late);
        numberField(value, "droppedCount", snapshot.dropped);
        numberField(value, "bypassedCount", snapshot.bypassed);
        numberField(value, "currentQueueDepth", snapshot.currentQueueDepth);
        numberField(value, "maxQueueDepth", snapshot.maxQueueDepth);
        numberField(value, "flushCount", snapshot.flushCount);
        numberField(value, "seekProxyCount", snapshot.seekProxyCount);
        return finish(value);
    }

    static String errorJson(String runId, String stage, String message) {
        StringBuilder value = envelope("error", runId);
        stringField(value, "stage", stage);
        stringField(value, "message", message);
        return finish(value);
    }

    static String qnnStrictJson(
            String runId,
            QuickSrVideoEffect.Profile profile,
            JSONObject qnnStrict) {
        if (qnnStrict == null) {
            throw new IllegalArgumentException("QNN strict evidence is required");
        }
        return qnnStrictJson(
                runId,
                profile,
                qnnStrict.toString(),
                qnnStrict.optBoolean("providerAssignmentVerified", false),
                qnnStrict.optBoolean("providerFallbackTraceCaptured", false),
                qnnStrict.optString(
                        "evidenceScope",
                        QnnPluginRuntime.VIDEO_STRICT_EVIDENCE_SCOPE));
    }

    static String qnnStrictJson(
            String runId,
            QuickSrVideoEffect.Profile profile,
            String qnnStrictJson) {
        if (qnnStrictJson == null || qnnStrictJson.isBlank()) {
            throw new IllegalArgumentException("QNN strict evidence is required");
        }
        return qnnStrictJson(
                runId,
                profile,
                qnnStrictJson,
                false,
                false,
                QnnPluginRuntime.VIDEO_STRICT_EVIDENCE_SCOPE);
    }

    private static String qnnStrictJson(
            String runId,
            QuickSrVideoEffect.Profile profile,
            String qnnStrictJson,
            boolean providerAssignmentVerified,
            boolean providerFallbackTraceCaptured,
            String evidenceScope) {
        StringBuilder value = envelope("qnn_strict", runId);
        stringField(value, "mode", QuickSrSession.Mode.QNN_HTP.name());
        stringField(value, "profile", profile.name());
        stringField(value, "modelVariant", profile.modelVariant().id());
        booleanField(value, "providerAssignmentVerified", providerAssignmentVerified);
        booleanField(value, "providerFallbackTraceCaptured", providerFallbackTraceCaptured);
        stringField(value, "evidenceScope", evidenceScope);
        jsonField(value, "qnnStrict", qnnStrictJson);
        return finish(value);
    }

    static String evidenceCaptureJson(String runId, JSONObject evidence) {
        StringBuilder value = envelope("evidence_capture", runId);
        jsonField(value, "evidence", evidence);
        return finish(value);
    }

    static String terminalJson(String runId, String status, String stage, JSONObject qnnStrict) {
        StringBuilder value = envelope("terminal", runId);
        stringField(value, "status", status);
        stringField(value, "stage", stage);
        if (qnnStrict != null) {
            booleanField(
                    value,
                    "providerAssignmentVerified",
                    qnnStrict.optBoolean("providerAssignmentVerified", false));
            booleanField(
                    value,
                    "providerFallbackTraceCaptured",
                    qnnStrict.optBoolean("providerFallbackTraceCaptured", false));
            stringField(
                    value,
                    "evidenceScope",
                    qnnStrict.optString(
                            "evidenceScope",
                            QnnPluginRuntime.VIDEO_STRICT_EVIDENCE_SCOPE));
            jsonField(value, "qnnStrict", qnnStrict);
        }
        return finish(value);
    }

    private static StringBuilder envelope(String event, String runId) {
        StringBuilder value = new StringBuilder(512);
        value.append('{');
        bareNumberField(value, "schemaVersion", 2);
        stringField(value, "event", event);
        stringField(value, "runId", runId);
        return value;
    }

    private static String finish(StringBuilder value) { return value.append('}').toString(); }

    private static void stringField(StringBuilder value, String name, String fieldValue) {
        separator(value); quoted(value, name); value.append(':'); quoted(value, fieldValue);
    }

    private static void numberField(StringBuilder value, String name, long fieldValue) {
        separator(value); quoted(value, name); value.append(':').append(fieldValue);
    }

    private static void bareNumberField(StringBuilder value, String name, long fieldValue) {
        quoted(value, name); value.append(':').append(fieldValue);
    }

    private static void booleanField(StringBuilder value, String name, boolean fieldValue) {
        separator(value); quoted(value, name); value.append(':').append(fieldValue);
    }

    private static void jsonField(StringBuilder value, String name, JSONObject fieldValue) {
        jsonField(value, name, fieldValue.toString());
    }

    private static void jsonField(StringBuilder value, String name, String fieldValue) {
        separator(value); quoted(value, name); value.append(':').append(fieldValue);
    }

    private static void separator(StringBuilder value) {
        if (value.charAt(value.length() - 1) != '{') value.append(',');
    }

    private static void quoted(StringBuilder value, String text) {
        value.append('"');
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '"': value.append("\\\""); break;
                case '\\': value.append("\\\\"); break;
                case '\b': value.append("\\b"); break;
                case '\f': value.append("\\f"); break;
                case '\n': value.append("\\n"); break;
                case '\r': value.append("\\r"); break;
                case '\t': value.append("\\t"); break;
                default:
                    if (character < 0x20) value.append(String.format("\\u%04x", (int) character));
                    else value.append(character);
            }
        }
        value.append('"');
    }
}
