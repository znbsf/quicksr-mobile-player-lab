package dev.aisystems.quicksrplayerlab;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

final class QuickSrEngine {
    private static final String INPUT_NAME = "image";
    private static final String OUTPUT_NAME = "upscaled_image";
    private static final int INPUT_WIDTH = 64;
    private static final int INPUT_HEIGHT = 64;
    private static final int SCALE = 2;
    private static final int WARMUP_RUNS = 5;
    private static final int MEASURED_RUNS = 30;

    ProbeResult run(Context context, Backend backend, String runId) {
        return run(
                context,
                backend,
                runId,
                DeterministicInputs.rgbGradientNchw(INPUT_WIDTH, INPUT_HEIGHT),
                "DeterministicInputs.rgbGradientNchw-v1",
                null);
    }

    ProbeResult run(
            Context context,
            Backend backend,
            String runId,
            float[] suppliedInputValues,
            String inputGenerator,
            JSONObject inputMetadata) {
        JSONObject receipt = new JSONObject();
        List<Double> latenciesMs = new ArrayList<>();
        float[] durableOutput = null;
        ModelVariant modelVariant = backend.modelVariant();
        int warmupsAttempted = 0;
        int warmupsCompleted = 0;
        int measuredAttempted = 0;
        int measuredCompleted = 0;
        double sessionCreateMs = -1.0;
        boolean backendConfigured = false;
        boolean sessionCreated = false;
        long runStartNs = SystemClock.elapsedRealtimeNanos();

        try {
            receipt.put("schemaVersion", backend.usesQnnPlugin() ? "1.1.0" : "1.0.0");
            receipt.put("runId", runId);
            receipt.put("startedAt", Instant.now().toString());
            receipt.put("status", "RUNNING");
            receipt.put("integrationLayer", "java-onnxruntime");
            receipt.put("gateScope", "android-integration-and-structural-sanity");
            receipt.put("backendRequested", backend.name());
            receipt.put("cpuEpFallbackDisabled", backend.isCpuFallbackDisabled());
            receipt.put("providerAssignmentVerified", false);
            receipt.put("providerFallbackTraceCaptured", false);
            receipt.put("correctnessReferenceCompared", false);
            receipt.put(
                    "planSha256",
                    backend.usesQnnPlugin()
                            ? BuildConfig.QNN_PLAN_SHA256
                            : BuildConfig.PLAN_SHA256);
            JSONObject appBuild = new JSONObject();
            appBuild.put("prototypeBuildId", BuildConfig.PROTOTYPE_BUILD_ID);
            appBuild.put("sourceIdentitySha256", BuildConfig.APP_SOURCE_SHA256);
            appBuild.put("versionCode", BuildConfig.VERSION_CODE);
            appBuild.put("versionName", BuildConfig.VERSION_NAME);
            appBuild.put("debug", BuildConfig.DEBUG);
            receipt.put("appBuild", appBuild);
            receipt.put("ortDependencyVersion", BuildConfig.ORT_DEPENDENCY_VERSION);
            receipt.put("qnnPluginVersion", BuildConfig.QNN_PLUGIN_VERSION);
            receipt.put("qnnRuntimeVersion", BuildConfig.QNN_RUNTIME_VERSION);
            JSONObject qnnEvidence = null;
            if (backend.usesQnnPlugin()) {
                qnnEvidence = new JSONObject();
                receipt.put("qnnPlugin", qnnEvidence);
                QnnPluginRuntime.prepareProcessEnvironment(context, qnnEvidence);
            }
            OrtEnvironment environment = OrtEnvironment.getEnvironment();
            receipt.put("ortRuntimeVersion", environment.getVersion());
            receipt.put("availableProviders", availableProviders());
            receipt.put("deviceBefore", DeviceSnapshot.capture(context));
            receipt.put("timingBoundary", timingBoundary(modelVariant));

            byte[] modelBytes = readAsset(context, modelVariant.asset());
            String observedModelSha = sha256(modelBytes);
            JSONObject model = new JSONObject();
            model.put("variant", modelVariant.id());
            model.put("derived", modelVariant != ModelVariant.CANONICAL);
            model.put("asset", modelVariant.asset());
            model.put("expectedBytes", modelVariant.expectedBytes());
            model.put("observedBytes", modelBytes.length);
            model.put("expectedSha256", modelVariant.expectedSha256());
            model.put("observedSha256", observedModelSha);
            model.put("canonicalSourceSha256", BuildConfig.MODEL_SHA256);
            if (modelVariant != ModelVariant.CANONICAL) {
                model.put(
                        "derivationManifestSha256",
                        BuildConfig.DERIVED_MODELS_MANIFEST_SHA256);
            }
            receipt.put("model", model);
            if (modelBytes.length != modelVariant.expectedBytes()) {
                throw new IllegalStateException("Runtime model byte mismatch");
            }
            if (!modelVariant.expectedSha256().equalsIgnoreCase(observedModelSha)) {
                throw new IllegalStateException("Runtime model SHA-256 mismatch");
            }

            if (suppliedInputValues == null
                    || suppliedInputValues.length != 3 * INPUT_WIDTH * INPUT_HEIGHT) {
                throw new IllegalArgumentException(
                        "Input must contain exactly 3*64*64 float32 values");
            }
            if (inputGenerator == null || inputGenerator.isBlank()) {
                throw new IllegalArgumentException("Input generator label must be present");
            }
            float[] inputValues = suppliedInputValues.clone();
            for (float value : inputValues) {
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException("Input contains a non-finite value");
                }
            }
            long[] inputShape = new long[]{1, 3, INPUT_HEIGHT, INPUT_WIDTH};
            JSONObject inputIdentity = new JSONObject();
            inputIdentity.put("generator", inputGenerator);
            inputIdentity.put("shape", longs(inputShape));
            inputIdentity.put("elementCount", inputValues.length);
            inputIdentity.put("sha256LittleEndianFloat32", sha256Float32LittleEndian(inputValues));
            inputIdentity.put("sourceUriCaptured", false);
            if (inputMetadata != null) {
                inputIdentity.put("metadata", inputMetadata);
            }
            receipt.put("inputIdentity", inputIdentity);
            FloatBuffer inputBuffer = ByteBuffer.allocateDirect(inputValues.length * Float.BYTES)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            inputBuffer.put(inputValues);
            inputBuffer.rewind();

            QnnPluginRuntime.Registration qnnRegistration = null;
            try {
                try (OrtSession.SessionOptions options = new OrtSession.SessionOptions();
                     OnnxTensor inputTensor = OnnxTensor.createTensor(environment, inputBuffer, inputShape)) {
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
                options.setSymbolicDimensionValue("batch", 1);
                options.setSymbolicDimensionValue("height", INPUT_HEIGHT);
                options.setSymbolicDimensionValue("width", INPUT_WIDTH);
                options.setSymbolicDimensionValue("scaled_height", INPUT_HEIGHT * SCALE);
                options.setSymbolicDimensionValue("scaled_width", INPUT_WIDTH * SCALE);
                JSONObject dimensionOverrides = new JSONObject();
                dimensionOverrides.put("batch", 1);
                dimensionOverrides.put("height", INPUT_HEIGHT);
                dimensionOverrides.put("width", INPUT_WIDTH);
                dimensionOverrides.put("scaled_height", INPUT_HEIGHT * SCALE);
                dimensionOverrides.put("scaled_width", INPUT_WIDTH * SCALE);
                receipt.put("symbolicDimensionOverrides", dimensionOverrides);
                File profileDirectory = new File(context.getFilesDir(), "profiles");
                if (!profileDirectory.isDirectory() && !profileDirectory.mkdirs()) {
                    throw new IOException("Could not create profiling directory");
                }
                String profileStem = (runId + "-" + backend.name())
                        .replaceAll("[^A-Za-z0-9._-]", "_");
                options.enableProfiling(new File(profileDirectory, profileStem).getAbsolutePath());
                receipt.put("profilingEnabled", true);
                if (backend.usesQnnPlugin()) {
                    qnnRegistration = QnnPluginRuntime.registerAndConfigure(
                            context,
                            environment,
                            options,
                            runId,
                            backend.name(),
                            backend.isCpuFallbackDisabled(),
                            qnnEvidence);
                } else {
                    backend.configure(options);
                }
                backendConfigured = true;
                receipt.put("backendConfigured", true);

                long sessionStartNs = SystemClock.elapsedRealtimeNanos();
                try (OrtSession session = environment.createSession(modelBytes, options)) {
                    sessionCreated = true;
                    receipt.put("sessionCreated", true);
                    sessionCreateMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - sessionStartNs);
                    receipt.put("sessionCreateMs", sessionCreateMs);
                    receipt.put("sessionContract", validateSessionContract(session, modelVariant));

                    Map<String, OnnxTensor> inputMap = Collections.singletonMap(INPUT_NAME, inputTensor);
                    for (int i = 0; i < WARMUP_RUNS; i++) {
                        warmupsAttempted++;
                        try (OrtSession.Result ignored = session.run(inputMap)) {
                            warmupsCompleted++;
                        }
                    }

                    float[] finalOutput = null;
                    long[] finalShape = null;
                    for (int i = 0; i < MEASURED_RUNS; i++) {
                        measuredAttempted++;
                        long inferenceStartNs = SystemClock.elapsedRealtimeNanos();
                        try (OrtSession.Result result = session.run(inputMap)) {
                            double elapsedMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - inferenceStartNs);
                            latenciesMs.add(elapsedMs);
                            measuredCompleted++;
                            if (i == MEASURED_RUNS - 1) {
                                OnnxTensor outputTensor = requireTensor(result, modelVariant.outputName());
                                TensorInfo outputInfo = outputTensor.getInfo();
                                finalShape = outputInfo.getShape();
                                finalOutput = copyFloatTensor(outputTensor);
                            }
                        }
                    }

                    JSONObject modelOutputIdentity = validateModelOutput(
                            finalShape,
                            finalOutput,
                            modelVariant.sessionOutputShape());
                    receipt.put("modelOutputIdentity", modelOutputIdentity);
                    if (modelVariant.requiresCrdPixelShuffle()) {
                        long postprocessStartNs = SystemClock.elapsedRealtimeNanos();
                        finalOutput = pixelShuffleCrd(finalOutput, 3, INPUT_HEIGHT, INPUT_WIDTH, SCALE);
                        double postprocessMs = nanosToMs(
                                SystemClock.elapsedRealtimeNanos() - postprocessStartNs);
                        finalShape = new long[]{1, 3, INPUT_HEIGHT * SCALE, INPUT_WIDTH * SCALE};
                        JSONObject postprocess = new JSONObject();
                        postprocess.put("kind", "application-crd-pixel-shuffle");
                        postprocess.put("includedInOrtRunLatency", false);
                        postprocess.put("singleCapturedOutputLatencyMs", postprocessMs);
                        postprocess.put("inputShape", longs(modelVariant.sessionOutputShape()));
                        postprocess.put("outputShape", longs(finalShape));
                        receipt.put("applicationPostprocess", postprocess);
                    }
                    JSONObject validation = validateOutput(finalShape, finalOutput);
                    receipt.put("structuralSanityValidation", validation);
                    if (!validation.getBoolean("pass")) {
                        throw new IllegalStateException("Output validation failed");
                    }
                    durableOutput = finalOutput;

                    File profileFile = new File(session.endProfiling());
                    String profileRoot = profileDirectory.getCanonicalPath() + File.separator;
                    String profilePath = profileFile.getCanonicalPath();
                    if (!profilePath.startsWith(profileRoot) || !profileFile.isFile()) {
                        throw new IOException("ORT profiling output is missing or outside the app profile directory");
                    }
                    byte[] profileBytes = java.nio.file.Files.readAllBytes(profileFile.toPath());
                    JSONObject profilingArtifact = new JSONObject();
                    profilingArtifact.put("file", profileFile.getName());
                    profilingArtifact.put("bytes", profileBytes.length);
                    profilingArtifact.put("sha256", sha256(profileBytes));
                    profilingArtifact.put("providerAssignmentClaim", "host-parser-pending");
                    receipt.put("profilingArtifact", profilingArtifact);
                }
                }
            } finally {
                if (qnnRegistration != null) {
                    qnnRegistration.captureAndUnregister();
                }
            }
            if (backend.usesQnnPlugin()
                    && sessionCreated
                    && qnnRegistration != null
                    && !qnnRegistration.isEvidenceComplete()) {
                throw new IllegalStateException(
                        "QNN execution returned, but strict profiling evidence is incomplete");
            }

            receipt.put("status", "PASS");
            receipt.put("error", JSONObject.NULL);
        } catch (Throwable error) {
            try {
                receipt.put("status", "FAIL");
                receipt.put("backendConfigured", backendConfigured);
                receipt.put("sessionCreated", sessionCreated);
                receipt.put("error", errorJson(error));
            } catch (JSONException jsonFailure) {
                return ProbeResult.receiptOnly(emergencyFailure(runId, backend, error, jsonFailure));
            }
        }

        try {
            receipt.put("warmup", attemptSummary(WARMUP_RUNS, warmupsAttempted, warmupsCompleted));
            receipt.put("measured", attemptSummary(MEASURED_RUNS, measuredAttempted, measuredCompleted));
            receipt.put("rawRunLatenciesMs", doubles(latenciesMs));
            if (!latenciesMs.isEmpty()) {
                receipt.put("p50RunLatencyMs", Stats.nearestRankPercentile(latenciesMs, 0.50));
                receipt.put("p95RunLatencyMs", Stats.nearestRankPercentile(latenciesMs, 0.95));
            }
            if (sessionCreateMs < 0.0) {
                receipt.put("sessionCreateMs", JSONObject.NULL);
            }
            receipt.put("totalProbeWallMs", nanosToMs(SystemClock.elapsedRealtimeNanos() - runStartNs));
            receipt.put("deviceAfter", DeviceSnapshot.capture(context));
            receipt.put("finishedAt", Instant.now().toString());
            return ProbeResult.withOutput(receipt, durableOutput);
        } catch (Throwable finalizeFailure) {
            try {
                receipt.put("status", "FAIL");
                receipt.put("receiptFinalizationError", errorJson(finalizeFailure));
                receipt.put("finishedAt", Instant.now().toString());
                return ProbeResult.receiptOnly(receipt);
            } catch (Throwable secondaryFailure) {
                return ProbeResult.receiptOnly(
                        emergencyFailure(runId, backend, finalizeFailure, secondaryFailure));
            }
        }
    }

    private static JSONArray availableProviders() {
        JSONArray array = new JSONArray();
        for (Object provider : OrtEnvironment.getAvailableProviders()) {
            array.put(String.valueOf(provider));
        }
        return array;
    }

    private static JSONObject timingBoundary(ModelVariant modelVariant) throws JSONException {
        JSONObject timing = new JSONObject();
        timing.put("sessionCreate", "OrtEnvironment.createSession over already-read model bytes");
        timing.put("runLatency", "OrtSession.run only, with an existing direct FloatBuffer-backed input tensor");
        JSONArray excluded = new JSONArray();
        excluded.put("asset read");
        excluded.put("input generation and tensor creation");
        excluded.put("output copy, validation, and hash");
        excluded.put("receipt serialization and storage");
        excluded.put("decode, colorspace conversion, render, and encode");
        if (modelVariant.requiresCrdPixelShuffle()) {
            excluded.put("application CRD pixel shuffle (recorded separately)");
        }
        timing.put("excludedFromRunLatency", excluded);
        return timing;
    }

    private static JSONObject validateSessionContract(
            OrtSession session,
            ModelVariant modelVariant) throws Exception {
        NodeInfo input = session.getInputInfo().get(INPUT_NAME);
        NodeInfo output = session.getOutputInfo().get(modelVariant.outputName());
        if (input == null || output == null) {
            throw new IllegalStateException("Expected model input/output names are absent");
        }
        if (!(input.getInfo() instanceof TensorInfo) || !(output.getInfo() instanceof TensorInfo)) {
            throw new IllegalStateException("Expected tensor input and output");
        }
        long[] inputShape = ((TensorInfo) input.getInfo()).getShape();
        long[] outputShape = ((TensorInfo) output.getInfo()).getShape();
        requireCompatibleShape(inputShape, new long[]{1, 3, INPUT_HEIGHT, INPUT_WIDTH}, "input");
        requireCompatibleShape(outputShape, modelVariant.sessionOutputShape(), "output");

        JSONObject contract = new JSONObject();
        contract.put("inputName", INPUT_NAME);
        contract.put("inputShape", longs(inputShape));
        contract.put("outputName", modelVariant.outputName());
        contract.put("outputShape", longs(outputShape));
        return contract;
    }

    private static void requireCompatibleShape(long[] observed, long[] expected, String label) {
        if (observed.length != expected.length) {
            throw new IllegalStateException(label + " rank mismatch");
        }
        for (int i = 0; i < observed.length; i++) {
            if (observed[i] > 0 && observed[i] != expected[i]) {
                throw new IllegalStateException(label + " dimension " + i + " mismatch: " + observed[i]);
            }
        }
    }

    private static OnnxTensor requireTensor(OrtSession.Result result, String name) throws Exception {
        Optional<OnnxValue> value = result.get(name);
        if (!value.isPresent() || !(value.get() instanceof OnnxTensor)) {
            throw new IllegalStateException("Missing float tensor output: " + name);
        }
        return (OnnxTensor) value.get();
    }

    private static float[] copyFloatTensor(OnnxTensor tensor) throws Exception {
        FloatBuffer output = tensor.getFloatBuffer();
        if (output == null) {
            throw new IllegalStateException("Output tensor does not expose a float buffer");
        }
        FloatBuffer buffer = output.duplicate();
        buffer.rewind();
        float[] values = new float[buffer.remaining()];
        buffer.get(values);
        return values;
    }

    private static JSONObject validateOutput(long[] shape, float[] values) throws Exception {
        long[] expectedShape = new long[]{1, 3, INPUT_HEIGHT * SCALE, INPUT_WIDTH * SCALE};
        requireCompatibleShape(shape, expectedShape, "runtime output");
        int expectedValues = 3 * INPUT_HEIGHT * SCALE * INPUT_WIDTH * SCALE;
        if (values == null || values.length != expectedValues) {
            throw new IllegalStateException("Output element count mismatch");
        }

        int finiteCount = 0;
        int rangeViolationCount = 0;
        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        for (float value : values) {
            if (Float.isFinite(value)) {
                finiteCount++;
                min = Math.min(min, value);
                max = Math.max(max, value);
                if (value < -1.0e-5f || value > 1.00001f) {
                    rangeViolationCount++;
                }
            }
        }

        JSONObject validation = new JSONObject();
        validation.put("shape", longs(shape));
        validation.put("elementCount", values.length);
        validation.put("finiteCount", finiteCount);
        validation.put("rangeViolationCount", rangeViolationCount);
        validation.put("min", finiteCount > 0 ? min : JSONObject.NULL);
        validation.put("max", finiteCount > 0 ? max : JSONObject.NULL);
        validation.put("sha256LittleEndianFloat32", sha256Float32LittleEndian(values));
        validation.put("validationKind", "shape-finite-range-and-identity-hash-only");
        validation.put("referenceCompared", false);
        validation.put("correctnessClaim", false);
        validation.put("pass", finiteCount == values.length && rangeViolationCount == 0);
        return validation;
    }

    private static JSONObject validateModelOutput(
            long[] shape,
            float[] values,
            long[] expectedShape) throws Exception {
        requireCompatibleShape(shape, expectedShape, "model output");
        int expectedValues = 1;
        for (long dimension : expectedShape) {
            expectedValues = Math.multiplyExact(expectedValues, Math.toIntExact(dimension));
        }
        if (values == null || values.length != expectedValues) {
            throw new IllegalStateException("Model output element count mismatch");
        }
        JSONObject identity = new JSONObject();
        identity.put("shape", longs(shape));
        identity.put("elementCount", values.length);
        identity.put("sha256LittleEndianFloat32", sha256Float32LittleEndian(values));
        return identity;
    }

    static float[] pixelShuffleCrd(
            float[] input,
            int outputChannels,
            int inputHeight,
            int inputWidth,
            int scale) {
        int phaseCount = Math.multiplyExact(scale, scale);
        int inputChannels = Math.multiplyExact(outputChannels, phaseCount);
        int inputPlane = Math.multiplyExact(inputHeight, inputWidth);
        int expectedInputValues = Math.multiplyExact(inputChannels, inputPlane);
        if (input.length != expectedInputValues) {
            throw new IllegalArgumentException("CRD pixel shuffle input length mismatch");
        }
        int outputHeight = Math.multiplyExact(inputHeight, scale);
        int outputWidth = Math.multiplyExact(inputWidth, scale);
        int outputPlane = Math.multiplyExact(outputHeight, outputWidth);
        float[] output = new float[Math.multiplyExact(outputChannels, outputPlane)];
        for (int channel = 0; channel < outputChannels; channel++) {
            for (int y = 0; y < inputHeight; y++) {
                for (int x = 0; x < inputWidth; x++) {
                    int spatialInputOffset = y * inputWidth + x;
                    for (int phaseY = 0; phaseY < scale; phaseY++) {
                        for (int phaseX = 0; phaseX < scale; phaseX++) {
                            int phase = phaseY * scale + phaseX;
                            int inputChannel = channel * phaseCount + phase;
                            int inputOffset = inputChannel * inputPlane + spatialInputOffset;
                            int outputY = y * scale + phaseY;
                            int outputX = x * scale + phaseX;
                            int outputOffset = channel * outputPlane + outputY * outputWidth + outputX;
                            output[outputOffset] = input[inputOffset];
                        }
                    }
                }
            }
        }
        return output;
    }

    private static JSONObject attemptSummary(int planned, int attempted, int succeeded) throws JSONException {
        JSONObject value = new JSONObject();
        value.put("planned", planned);
        value.put("attempted", attempted);
        value.put("succeeded", succeeded);
        value.put("failedAttempts", attempted - succeeded);
        value.put("unattempted", planned - attempted);
        return value;
    }

    private static JSONObject errorJson(Throwable error) throws JSONException {
        JSONObject value = new JSONObject();
        value.put("type", error.getClass().getName());
        value.put("message", String.valueOf(error.getMessage()));
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        String stack = writer.toString();
        value.put("stackExcerpt", stack.substring(0, Math.min(stack.length(), 4000)));
        return value;
    }

    private static JSONObject emergencyFailure(
            String runId,
            Backend backend,
            Throwable primary,
            Throwable secondary) {
        JSONObject value = new JSONObject();
        try {
            value.put("schemaVersion", "1.0.0");
            value.put("runId", runId);
            value.put("status", "FAIL");
            value.put("backendRequested", backend.name());
            value.put("providerAssignmentVerified", false);
            value.put("errorType", primary.getClass().getName());
            value.put("errorMessage", String.valueOf(primary.getMessage()));
            if (secondary != null) {
                value.put("receiptFinalizationError", secondary.getClass().getName());
            }
        } catch (JSONException impossible) {
            throw new IllegalStateException("Could not create emergency failure receipt", impossible);
        }
        return value;
    }

    private static JSONArray doubles(List<Double> values) {
        JSONArray array = new JSONArray();
        for (Double value : values) {
            array.put(value);
        }
        return array;
    }

    private static JSONArray longs(long[] values) {
        JSONArray array = new JSONArray();
        for (long value : values) {
            array.put(value);
        }
        return array;
    }

    private static byte[] readAsset(Context context, String name) throws IOException {
        try (InputStream input = context.getAssets().open(name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            while (true) {
                int read = input.read(buffer);
                if (read < 0) {
                    break;
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String sha256Float32LittleEndian(float[] values) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (float value : values) {
            int bits = Float.floatToIntBits(value);
            digest.update((byte) (bits & 0xff));
            digest.update((byte) ((bits >>> 8) & 0xff));
            digest.update((byte) ((bits >>> 16) & 0xff));
            digest.update((byte) ((bits >>> 24) & 0xff));
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value & 0xff));
        }
        return builder.toString();
    }

    private static double nanosToMs(long nanos) {
        return nanos / 1_000_000.0;
    }
}
