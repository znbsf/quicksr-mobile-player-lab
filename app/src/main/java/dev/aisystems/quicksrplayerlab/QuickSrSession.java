package dev.aisystems.quicksrplayerlab;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

final class QuickSrSession implements AutoCloseable {
    private static final int MAX_OUTPUT_SLOT_COUNT = 2;

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

    private static final class OutputSlot {
        final OnnxTensor tensor;
        final FloatBuffer buffer;
        final Map<String, OnnxTensor> outputMap;

        OutputSlot(ModelVariant modelVariant, OnnxTensor tensor, FloatBuffer buffer) {
            this.tensor = tensor;
            this.buffer = buffer;
            this.outputMap = Collections.singletonMap(modelVariant.outputName(), tensor);
        }
    }

    /**
     * Owns one pinned ORT output slot until the postprocess stage has copied it. The lease is
     * deliberately package-private so the video pipeline can move only the bulk copy, never ORT
     * execution, onto its second worker.
     */
    final class DeferredOutput implements AutoCloseable {
        private OutputSlot slot;

        private DeferredOutput(OutputSlot slot) {
            this.slot = slot;
        }

        synchronized long copyTo(float[] output) {
            if (slot == null) {
                throw new IllegalStateException("QuickSR deferred output slot is already closed");
            }
            return copyOutput(slot, output);
        }

        @Override
        public void close() {
            OutputSlot owned;
            synchronized (this) {
                owned = slot;
                slot = null;
            }
            if (owned != null) {
                releaseOutputSlot(owned);
            }
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
    private final OutputSlot[] outputSlots;
    private final ArrayBlockingQueue<OutputSlot> availableOutputSlots;
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
            List<OutputSlot> outputSlots,
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
        this.outputSlots = outputSlots.toArray(new OutputSlot[0]);
        this.availableOutputSlots = new ArrayBlockingQueue<>(this.outputSlots.length, true);
        this.availableOutputSlots.addAll(outputSlots);
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
        return open(context, mode, runId, modelVariant, tuning, 1);
    }

    static QuickSrSession open(
            Context context,
            Mode mode,
            String runId,
            ModelVariant modelVariant,
            Tuning tuning,
            int outputSlotCount) throws Exception {
        if (outputSlotCount < 1 || outputSlotCount > MAX_OUTPUT_SLOT_COUNT) {
            throw new IllegalArgumentException(
                    "QuickSR output slot count must be between 1 and "
                            + MAX_OUTPUT_SLOT_COUNT);
        }
        Context appContext = context.getApplicationContext();
        if (modelVariant.requiresCrdPixelShuffle()) {
            throw new IllegalArgumentException(
                    "QuickSrSession requires a model with direct RGB output: "
                            + modelVariant.id());
        }
        long[] inputShape = modelVariant.sessionInputShape();
        long[] outputShape = modelVariant.sessionOutputShape();
        boolean outputIsRgb = outputShape.length == 4
                && outputShape[0] == 1
                && (modelVariant.outputNhwc()
                        ? outputShape[3] == 3
                        : outputShape[1] == 3);
        if (inputShape.length != 4
                || inputShape[0] != 1
                || inputShape[1] != 3
                || !outputIsRgb) {
            throw new IllegalArgumentException(
                    "QuickSrSession requires NCHW RGB input and NCHW/NHWC RGB output tensors");
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
        List<OutputSlot> outputSlots = new ArrayList<>(outputSlotCount);
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
            for (int slotIndex = 0; slotIndex < outputSlotCount; slotIndex++) {
                FloatBuffer outputBuffer = ByteBuffer.allocateDirect(
                                modelVariant.outputValueCount() * Float.BYTES)
                        .order(ByteOrder.nativeOrder())
                        .asFloatBuffer();
                OnnxTensor outputTensor = OnnxTensor.createTensor(
                        environment,
                        outputBuffer,
                        outputShape);
                outputSlots.add(new OutputSlot(modelVariant, outputTensor, outputBuffer));
            }
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
                    outputSlots,
                    runOptions,
                    tuning,
                    registration);
        } catch (Throwable failure) {
            Throwable combined = closeSessionResources(
                    failure,
                    session,
                    inputTensor,
                    outputSlots,
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
        validateBuffers(input, output);
        OutputSlot slot = acquireOutputSlot();
        try {
            runIntoSlot(input, slot, timings);
            long outputCopyNs = copyOutput(slot, output);
            if (timings != null) {
                timings.outputCopyNs = outputCopyNs;
            }
            long outputCopiedNs = System.nanoTime();

            // A full 1080p tensor contains more than six million floats. Scanning it on the
            // inference thread every 120 frames introduced a reproducible 330-350 ms stall roughly
            // every five seconds at 24 fps. The first successful output still gets a complete
            // fail-closed validation, which catches model/runtime/layout failures before the
            // session is considered usable, without putting a diagnostic sweep in steady state.
            boolean validateFinite = shouldValidateFiniteOutput(runCount);
            if (validateFinite) {
                for (float value : output) {
                    if (!Float.isFinite(value)) {
                        throw new IllegalStateException(
                                "QuickSR output contains a non-finite value");
                    }
                }
                if (timings != null) {
                    timings.finiteScanExecuted = true;
                    timings.finiteScanNs = System.nanoTime() - outputCopiedNs;
                }
            }
            runCount++;
        } finally {
            releaseOutputSlot(slot);
        }
    }

    DeferredOutput inferDeferred(float[] input, RunTimings timings) throws Exception {
        if (!canDeferOutputCopy(runCount, outputSlots.length)) {
            throw new IllegalStateException(
                    "QuickSR deferred output requires a validated first run and two output slots");
        }
        validateInput(input);
        OutputSlot slot = acquireOutputSlot();
        boolean ownershipTransferred = false;
        try {
            runIntoSlot(input, slot, timings);
            runCount++;
            DeferredOutput output = new DeferredOutput(slot);
            ownershipTransferred = true;
            return output;
        } finally {
            if (!ownershipTransferred) {
                releaseOutputSlot(slot);
            }
        }
    }

    static boolean shouldValidateFiniteOutput(int completedRunCount) {
        if (completedRunCount < 0) {
            throw new IllegalArgumentException("completed run count must not be negative");
        }
        return completedRunCount == 0;
    }

    static boolean canDeferOutputCopy(int completedRunCount, int outputSlotCount) {
        if (completedRunCount < 0) {
            throw new IllegalArgumentException("completed run count must not be negative");
        }
        if (outputSlotCount < 1 || outputSlotCount > MAX_OUTPUT_SLOT_COUNT) {
            throw new IllegalArgumentException("output slot count is out of range");
        }
        return completedRunCount > 0 && outputSlotCount >= 2;
    }

    int outputSlotCount() {
        return outputSlots.length;
    }

    private void validateInput(float[] input) {
        if (closed) {
            throw new IllegalStateException("QuickSR session is closed");
        }
        if (input.length != inputValues) {
            throw new IllegalArgumentException(
                    "QuickSR input length mismatch for " + modelVariant.id()
                            + ": expected " + inputValues + ", observed " + input.length);
        }
    }

    private void validateBuffers(float[] input, float[] output) {
        validateInput(input);
        if (output.length != outputValues) {
            throw new IllegalArgumentException(
                    "QuickSR output length mismatch for " + modelVariant.id()
                            + ": expected " + outputValues + ", observed " + output.length);
        }
    }

    private OutputSlot acquireOutputSlot() throws InterruptedException {
        return availableOutputSlots.take();
    }

    private void releaseOutputSlot(OutputSlot slot) {
        if (!availableOutputSlots.offer(slot)) {
            throw new IllegalStateException("QuickSR output slot was returned more than once");
        }
    }

    private void runIntoSlot(float[] input, OutputSlot slot, RunTimings timings) throws Exception {
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

        slot.buffer.clear();
        if (runOptions == null) {
            try (OrtSession.Result ignored = session.run(inputMap, slot.outputMap)) {
                // The result does not own the pinned output tensor.
            }
        } else {
            try (OrtSession.Result ignored = session.run(
                    inputMap,
                    Collections.emptySet(),
                    slot.outputMap,
                    runOptions)) {
                // The result does not own the pinned output tensor.
            }
        }
        if (timings != null) {
            timings.ortRunNs = System.nanoTime() - inputCopiedNs;
        }
    }

    private long copyOutput(OutputSlot slot, float[] output) {
        if (output.length != outputValues) {
            throw new IllegalArgumentException(
                    "QuickSR output length mismatch for " + modelVariant.id()
                            + ": expected " + outputValues + ", observed " + output.length);
        }
        long startedNs = System.nanoTime();
        FloatBuffer copy = slot.buffer.duplicate();
        copy.rewind();
        if (copy.remaining() != outputValues) {
            throw new IllegalStateException("QuickSR pinned output element count changed");
        }
        copy.get(output);
        return System.nanoTime() - startedNs;
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
        boolean interrupted = false;
        for (int slotIndex = 0; slotIndex < outputSlots.length; slotIndex++) {
            while (true) {
                try {
                    availableOutputSlots.take();
                    break;
                } catch (InterruptedException failure) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        Throwable failure = closeSessionResources(
                interrupted
                        ? new InterruptedException(
                                "Interrupted while waiting for QuickSR output leases")
                        : null,
                session,
                inputTensor,
                java.util.Arrays.asList(outputSlots),
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

    private static Throwable closeSessionResources(
            Throwable primary,
            OrtSession session,
            OnnxTensor inputTensor,
            List<OutputSlot> outputSlots,
            OrtSession.RunOptions runOptions,
            OrtSession.SessionOptions options,
            QnnPluginRuntime.Registration registration) {
        Throwable combined = closeAll(primary, session, inputTensor);
        for (OutputSlot slot : outputSlots) {
            combined = closeAll(combined, slot.tensor);
        }
        return closeAll(combined, runOptions, options, registration);
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
