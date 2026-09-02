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
    static final int FRAME_BATCH_SIZE = 10;

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
        StringBuilder value = envelope("configuration", runId);
        stringField(value, "mode", mode);
        stringField(value, "tuning", tuning.name());
        stringField(value, "profile", profile.name());
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
        numberField(value, "effectInputWidth", first.effectInputWidth);
        numberField(value, "effectInputHeight", first.effectInputHeight);
        numberField(value, "modelInputWidth", first.modelInputWidth);
        numberField(value, "modelInputHeight", first.modelInputHeight);
        numberField(value, "modelOutputWidth", first.modelOutputWidth);
        numberField(value, "modelOutputHeight", first.modelOutputHeight);
        value.append(",\"samples\":[");
        for (int index = 0; index < samples.size(); index++) {
            QuickSrVideoEffect.FrameStats sample = samples.get(index);
            if (sample.mode != first.mode || sample.tuning != first.tuning || sample.profile != first.profile) {
                throw new IllegalArgumentException("benchmark frame batch mixes mode, tuning or profile");
            }
            if (index > 0) value.append(',');
            value.append('{');
            bareNumberField(value, "frame", sample.frameNumber);
            numberField(value, "ptsUs", sample.presentationTimeUs);
            numberField(value, "observedNs", sample.observedMonotonicNs);
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
        bareNumberField(value, "schemaVersion", 1);
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
