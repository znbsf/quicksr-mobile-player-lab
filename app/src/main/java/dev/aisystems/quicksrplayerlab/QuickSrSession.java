package dev.aisystems.quicksrplayerlab;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

final class QuickSrSession implements AutoCloseable {
    enum Mode {
        QNN_HTP("QNN HTP · QuickSRNet"),
        CPU("CPU · QuickSRNet");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    enum Tuning {
        BASELINE("HTP 默认", null),
        BURST("HTP Burst · 低延迟短测", "burst"),
        SUSTAINED("HTP Sustained · 连续播放", "sustained_high_performance");

        private final String label;
        private final String performanceMode;

        Tuning(String label, String performanceMode) {
            this.label = label;
            this.performanceMode = performanceMode;
        }

        boolean usesRunOptions() {
            return performanceMode != null;
        }

        void configure(OrtSession.RunOptions runOptions) throws Exception {
            if (performanceMode == null) {
                return;
            }
            runOptions.addRunConfigEntry("qnn.perf_mode", performanceMode);
            runOptions.addRunConfigEntry("qnn.rpc_control_latency", "100");
        }

        @Override
        public String toString() {
            return label;
        }
    }

    static final class RunTimings {
        long inputCopyNs;
        long ortRunNs;
        long outputCopyNs;
        long finiteScanNs;
        boolean finiteScanExecuted;

        void reset() {
            inputCopyNs = 0L;
            ortRunNs = 0L;
            outputCopyNs = 0L;
            finiteScanNs = 0L;
            finiteScanExecuted = false;
        }
    }

    private static final String INPUT_NAME = "image";

    private final Mode mode;
    private final ModelVariant modelVariant;
    private final OrtSession.SessionOptions options;
    private final OrtSession session;
    private final OnnxTensor inputTensor;
    private final FloatBuffer inputBuffer;
    private final Map<String, OnnxTensor> inputMap;
    private final OnnxTensor outputTensor;
    private final FloatBuffer outputBuffer;
    private final Map<String, OnnxTensor> outputMap;
    private final OrtSession.RunOptions runOptions;
    private final Tuning tuning;
    private final QnnPluginRuntime.Registration qnnRegistration;
    private final int inputValues;
    private final int outputValues;
    private int runCount;
    private boolean closed;

    private QuickSrSession(
            Mode mode,
            ModelVariant modelVariant,
            OrtSession.SessionOptions options,
            OrtSession session,
            OnnxTensor inputTensor,
            FloatBuffer inputBuffer,
            OnnxTensor outputTensor,
            FloatBuffer outputBuffer,
            OrtSession.RunOptions runOptions,
            Tuning tuning,
            QnnPluginRuntime.Registration qnnRegistration) {
        this.mode = mode;
        this.modelVariant = modelVariant;
        this.options = options;
        this.session = session;
        this.inputTensor = inputTensor;
        this.inputBuffer = inputBuffer;
        this.inputMap = Collections.singletonMap(INPUT_NAME, inputTensor);
        this.outputTensor = outputTensor;
        this.outputBuffer = outputBuffer;
        this.outputMap = Collections.singletonMap(modelVariant.outputName(), outputTensor);
        this.runOptions = runOptions;
        this.tuning = tuning;
        this.qnnRegistration = qnnRegistration;
        this.inputValues = modelVariant.inputValueCount();
        this.outputValues = modelVariant.outputValueCount();
    }

    static QuickSrSession open(Context context, Mode mode, String runId) throws Exception {
        return open(context, mode, runId, ModelVariant.FIXED64_DCR_FULL, Tuning.BASELINE);
    }

    static QuickSrSession open(
            Context context,
            Mode mode,
            String runId,
            ModelVariant modelVariant) throws Exception {
        return open(context, mode, runId, modelVariant, Tuning.BASELINE);
    }

    static QuickSrSession open(
            Context context,
            Mode mode,
            String runId,
            ModelVariant modelVariant,
            Tuning tuning) throws Exception {
        Context appContext = context.getApplicationContext();
        if (modelVariant.requiresCrdPixelShuffle()) {
            throw new IllegalArgumentException(
                    "QuickSrSession requires a model with direct RGB output: "
                            + modelVariant.id());
        }
        long[] inputShape = modelVariant.sessionInputShape();
        long[] outputShape = modelVariant.sessionOutputShape();
        if (inputShape[0] != 1 || inputShape[1] != 3
                || outputShape[0] != 1 || outputShape[1] != 3) {
            throw new IllegalArgumentException(
                    "QuickSrSession requires NCHW RGB input and output tensors");
        }
        JSONObject qnnEvidence = new JSONObject();
        if (mode == Mode.QNN_HTP) {
            QnnPluginRuntime.prepareProcessEnvironment(appContext, qnnEvidence);
        }

        byte[] modelBytes = readAsset(appContext, modelVariant.asset());
        if (modelBytes.length != modelVariant.expectedBytes()) {
            throw new IllegalStateException("QuickSR model byte count changed");
        }
        String modelHash = sha256(modelBytes);
        if (!modelVariant.expectedSha256().equalsIgnoreCase(modelHash)) {
            throw new IllegalStateException("QuickSR model hash changed");
        }

        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = null;
        QnnPluginRuntime.Registration registration = null;
        OrtSession session = null;
        OnnxTensor inputTensor = null;
        OnnxTensor outputTensor = null;
        OrtSession.RunOptions runOptions = null;
        try {
            options = new OrtSession.SessionOptions();
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
            if (mode == Mode.QNN_HTP) {
                registration = QnnPluginRuntime.registerForInference(
                        appContext,
                        environment,
                        options,
                        runId,
                        qnnEvidence,
                        tuning);
            } else {
                options.setIntraOpNumThreads(4);
                options.setInterOpNumThreads(1);
            }

            session = environment.createSession(modelBytes, options);
            FloatBuffer inputBuffer = ByteBuffer.allocateDirect(
                            modelVariant.inputValueCount() * Float.BYTES)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            inputTensor = OnnxTensor.createTensor(
                    environment,
                    inputBuffer,
                    inputShape);
            FloatBuffer outputBuffer = ByteBuffer.allocateDirect(
                            modelVariant.outputValueCount() * Float.BYTES)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            outputTensor = OnnxTensor.createTensor(
                    environment,
                    outputBuffer,
                    outputShape);
            if (mode == Mode.QNN_HTP && tuning.usesRunOptions()) {
                runOptions = new OrtSession.RunOptions();
                tuning.configure(runOptions);
            }
            return new QuickSrSession(
                    mode,
                    modelVariant,
                    options,
                    session,
                    inputTensor,
                    inputBuffer,
                    outputTensor,
                    outputBuffer,
                    runOptions,
                    tuning,
                    registration);
        } catch (Throwable failure) {
            Throwable combined = closeAll(
                    failure,
                    session,
                    inputTensor,
                    outputTensor,
                    runOptions,
                    options,
                    registration);
            throw asException(combined);
        }
    }

    void infer(float[] input, float[] output) throws Exception {
        infer(input, output, null);
    }

    void infer(float[] input, float[] output, RunTimings timings) throws Exception {
        if (closed) {
            throw new IllegalStateException("QuickSR session is closed");
        }
        if (input.length != inputValues || output.length != outputValues) {
            throw new IllegalArgumentException(
                    "QuickSR buffer length mismatch for " + modelVariant.id()
                            + ": expected " + inputValues + " input and " + outputValues
                            + " output values, observed " + input.length + " and "
                            + output.length);
        }
        if (timings != null) {
            timings.reset();
        }
        long startedNs = System.nanoTime();
        inputBuffer.position(0);
        inputBuffer.put(input);
        inputBuffer.rewind();
        long inputCopiedNs = System.nanoTime();
        if (timings != null) {
            timings.inputCopyNs = inputCopiedNs - startedNs;
        }

        outputBuffer.clear();
        if (runOptions == null) {
            try (OrtSession.Result ignored = session.run(inputMap, outputMap)) {
                // The result does not own the pinned output tensor.
            }
        } else {
            try (OrtSession.Result ignored = session.run(
                    inputMap,
                    Collections.emptySet(),
                    outputMap,
                    runOptions)) {
                // The result does not own the pinned output tensor.
            }
        }
        long runFinishedNs = System.nanoTime();
        if (timings != null) {
            timings.ortRunNs = runFinishedNs - inputCopiedNs;
        }
        FloatBuffer copy = outputBuffer.duplicate();
        copy.rewind();
        if (copy.remaining() != outputValues) {
            throw new IllegalStateException("QuickSR pinned output element count changed");
        }
        copy.get(output);
        long outputCopiedNs = System.nanoTime();
        if (timings != null) {
            timings.outputCopyNs = outputCopiedNs - runFinishedNs;
        }

        boolean validateFinite = runCount == 0 || runCount % 120 == 0;
        if (validateFinite) {
            for (float value : output) {
                if (!Float.isFinite(value)) {
                    throw new IllegalStateException("QuickSR output contains a non-finite value");
                }
            }
            if (timings != null) {
                timings.finiteScanExecuted = true;
                timings.finiteScanNs = System.nanoTime() - outputCopiedNs;
            }
        }
        runCount++;
    }

    int runCount() {
        return runCount;
    }

    int inputWidth() {
        return modelVariant.inputWidth();
    }

    int inputHeight() {
        return modelVariant.inputHeight();
    }

    int outputWidth() {
        return modelVariant.outputWidth();
    }

    int outputHeight() {
        return modelVariant.outputHeight();
    }

    int inputValueCount() {
        return inputValues;
    }

    int outputValueCount() {
        return outputValues;
    }

    String modelVariantId() {
        return modelVariant.id();
    }

    String backendLabel() {
        return mode == Mode.QNN_HTP ? mode + " · " + tuning : mode.toString();
    }

    JSONObject qnnStrictEvidence() throws Exception {
        if (mode != Mode.QNN_HTP || qnnRegistration == null) {
            return null;
        }
        return qnnRegistration.strictEvidenceSnapshot();
    }

    @Override
    public void close() throws Exception {
        if (closed) {
            return;
        }
        closed = true;
        Throwable failure = closeAll(
                null,
                session,
                inputTensor,
                outputTensor,
                runOptions,
                options,
                qnnRegistration);
        if (failure != null) {
            throw asException(failure);
        }
    }

    static Throwable closeAll(Throwable primary, AutoCloseable... resources) {
        Throwable combined = primary;
        for (AutoCloseable resource : resources) {
            if (resource == null) {
                continue;
            }
            try {
                resource.close();
            } catch (Throwable failure) {
                combined = append(combined, failure);
            }
        }
        return combined;
    }

    private static Throwable append(Throwable current, Throwable next) {
        if (current == null) {
            return next;
        }
        if (current != next) {
            try {
                current.addSuppressed(next);
            } catch (Throwable ignored) {
                // Never replace the primary failure while reporting cleanup failures.
            }
        }
        return current;
    }

    private static Exception asException(Throwable failure) {
        if (failure instanceof Exception) {
            return (Exception) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        return new RuntimeException(failure);
    }

    private static byte[] readAsset(Context context, String name) throws Exception {
        try (InputStream input = context.getAssets().open(name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    output.write(buffer, 0, read);
                }
            }
            return output.toByteArray();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }
}
