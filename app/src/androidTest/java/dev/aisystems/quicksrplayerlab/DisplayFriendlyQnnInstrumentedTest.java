package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.zip.CRC32;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/** Strict-QNN probe for the optional uint8 NHWC RGB graph output candidate. */
@RunWith(AndroidJUnit4.class)
public final class DisplayFriendlyQnnInstrumentedTest {
    private static final String TAG = "QuickSRDisplayProbe";
    private static final String INPUT_NAME = "image";
    private static final String OUTPUT_NAME = "upscaled_image__display_u8_nhwc";
    private static final long[] INPUT_SHAPE = {1, 3, 360, 640};
    private static final long[] OUTPUT_SHAPE = {1, 1080, 1920, 3};
    private static final int INPUT_VALUES = 3 * 360 * 640;
    private static final int OUTPUT_VALUES = 1080 * 1920 * 3;
    private static final int WARMUP_RUNS = 2;
    private static final int MEASURED_RUNS = 5;
    private static final OutputSpec U8_NHWC = new OutputSpec(
            "uint8_nhwc",
            BuildConfig.QUICKSR_DISPLAY_FRIENDLY_MODEL_FILE,
            OUTPUT_NAME,
            OUTPUT_SHAPE,
            OnnxJavaType.UINT8,
            OUTPUT_VALUES);

    @Test
    public void displayFriendlyRgb8GraphRunsOnStrictQnnHtp() throws Exception {
        assumeTrue(
                "Build with -PquickSrDisplayFriendlyModelPath=<generated model>",
                BuildConfig.QUICKSR_DISPLAY_FRIENDLY_MODEL_AVAILABLE);
        Context context = ApplicationProvider.getApplicationContext();
        byte[] model = readAsset(
                context,
                BuildConfig.QUICKSR_DISPLAY_FRIENDLY_MODEL_FILE);
        assertEquals(BuildConfig.QUICKSR_DISPLAY_FRIENDLY_MODEL_BYTES, model.length);
        assertEquals(
                BuildConfig.QUICKSR_DISPLAY_FRIENDLY_MODEL_SHA256,
                sha256(model));
        float[] input = deterministicInput();
        byte[] cpuOutput = runCpu(model, input);
        QnnResult qnn = runQnn(context, model, input, U8_NHWC);

        int mismatches = 0;
        int maxAbsoluteError = 0;
        long absoluteErrorSum = 0L;
        for (int index = 0; index < OUTPUT_VALUES; index++) {
            int expected = cpuOutput[index] & 0xff;
            int actual = qnn.output[index] & 0xff;
            int error = Math.abs(expected - actual);
            if (error != 0) {
                mismatches++;
            }
            maxAbsoluteError = Math.max(maxAbsoluteError, error);
            absoluteErrorSum += error;
        }
        double meanAbsoluteError = absoluteErrorSum / (double) OUTPUT_VALUES;
        assertTrue(qnn.strictEvidence.optBoolean("strictReady", false));
        assertTrue("QNN output must not be all zero", hasNonZero(qnn.output));

        Log.i(TAG, "{\"scope\":\"physical_qnn_htp_strict_no_cpu_fallback\""
                + ",\"output\":\"uint8_nhwc_rgb_1920x1080\""
                + ",\"warmupRuns\":" + WARMUP_RUNS
                + ",\"measuredRuns\":" + MEASURED_RUNS
                + ",\"qnnOrtP50Ms\":" + nanosToMillis(median(qnn.runTimesNs))
                + ",\"cpuCrc32\":\"" + crc32(cpuOutput) + "\""
                + ",\"qnnCrc32\":\"" + crc32(qnn.output) + "\""
                + ",\"cpuQnnMismatchCount\":" + mismatches
                + ",\"cpuQnnMaxAbsoluteError\":" + maxAbsoluteError
                + ",\"cpuQnnMeanAbsoluteError\":"
                + Math.round(meanAbsoluteError * 1_000_000.0) / 1_000_000.0
                + ",\"strictReady\":true}");
    }

    @Test
    public void strictQnnOutputOperatorVariantsAreProfiledSeparately() throws Exception {
        assumeTrue(
                "Build with all three optional output-variant model paths",
                BuildConfig.QUICKSR_DISPLAY_FRIENDLY_MODEL_AVAILABLE
                        && BuildConfig.QUICKSR_DISPLAY_U8_NCHW_MODEL_AVAILABLE
                        && BuildConfig.QUICKSR_DISPLAY_F32_NHWC_MODEL_AVAILABLE);
        Context context = ApplicationProvider.getApplicationContext();
        float[] input = deterministicInput();
        OutputSpec source = new OutputSpec(
                "source_float32_nchw",
                BuildConfig.FIXED640X360_3X_MODEL_FILE,
                "upscaled_image",
                new long[]{1, 3, 1080, 1920},
                OnnxJavaType.FLOAT,
                OUTPUT_VALUES * Float.BYTES);
        OutputSpec floatNhwc = new OutputSpec(
                "float32_nhwc_transpose",
                BuildConfig.QUICKSR_DISPLAY_F32_NHWC_MODEL_FILE,
                "upscaled_image__display_f32_nhwc",
                OUTPUT_SHAPE,
                OnnxJavaType.FLOAT,
                OUTPUT_VALUES * Float.BYTES);
        OutputSpec u8Nchw = new OutputSpec(
                "uint8_nchw_cast",
                BuildConfig.QUICKSR_DISPLAY_U8_NCHW_MODEL_FILE,
                "upscaled_image__display_u8_nchw",
                new long[]{1, 3, 1080, 1920},
                OnnxJavaType.UINT8,
                OUTPUT_VALUES);
        OutputSpec[] variants = {source, floatNhwc, u8Nchw, U8_NHWC, floatNhwc, source};
        int successfulRuns = 0;
        int u8NchwUnsupportedRuns = 0;
        for (OutputSpec variant : variants) {
            byte[] model = readAsset(context, variant.asset);
            try {
                QnnResult qnn = runQnn(context, model, input, variant);
                assertTrue(qnn.strictEvidence.optBoolean("strictReady", false));
                assertTrue("QNN output must not be all zero for " + variant.id,
                        hasNonZero(qnn.output));
                successfulRuns++;
                Log.i(TAG, "{\"scope\":\"physical_qnn_htp_strict_no_cpu_fallback\""
                        + ",\"probe\":\"output_operator_split\""
                        + ",\"variant\":\"" + variant.id + "\""
                        + ",\"status\":\"PASS\""
                        + ",\"outputBytes\":" + variant.outputBytes
                        + ",\"qnnOrtP50Ms\":" + nanosToMillis(median(qnn.runTimesNs))
                        + ",\"qnnCrc32\":\"" + crc32(qnn.output) + "\""
                        + ",\"strictReady\":true}");
            } catch (Exception failure) {
                if (!"uint8_nchw_cast".equals(variant.id)) {
                    throw failure;
                }
                u8NchwUnsupportedRuns++;
                Log.i(TAG, "{\"scope\":\"physical_qnn_htp_strict_no_cpu_fallback\""
                        + ",\"probe\":\"output_operator_split\""
                        + ",\"variant\":\"" + variant.id + "\""
                        + ",\"status\":\"UNSUPPORTED_WITH_CPU_FALLBACK_DISABLED\""
                        + ",\"failureType\":\"" + failure.getClass().getSimpleName() + "\"}");
            }
        }
        assertEquals(5, successfulRuns);
        assertEquals(1, u8NchwUnsupportedRuns);
    }

    private static byte[] runCpu(byte[] model, float[] input) throws Exception {
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
            options.setIntraOpNumThreads(4);
            options.setInterOpNumThreads(1);
            try (OrtSession session = environment.createSession(model, options)) {
                return runOnce(environment, session, null, input, null, U8_NHWC);
            }
        }
    }

    private static QnnResult runQnn(
            Context context,
            byte[] model,
            float[] input,
            OutputSpec outputSpec)
            throws Exception {
        QnnPluginRuntime.lockProcess();
        try {
            JSONObject evidence = new JSONObject();
            QnnPluginRuntime.prepareProcessEnvironment(context, evidence);
            OrtEnvironment environment = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            QnnPluginRuntime.Registration registration = null;
            OrtSession session = null;
            OrtSession.RunOptions runOptions = null;
            Throwable failure = null;
            try {
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
                registration = QnnPluginRuntime.registerForInference(
                        context,
                        environment,
                        options,
                        "output-variant-" + outputSpec.id,
                        evidence,
                        QuickSrSession.Tuning.SUSTAINED);
                session = environment.createSession(model, options);
                runOptions = new OrtSession.RunOptions();
                QuickSrSession.Tuning.SUSTAINED.configure(runOptions);
                BoundRunResult bound = runBound(
                        environment, session, runOptions, input, outputSpec);
                return new QnnResult(
                        bound.output,
                        bound.runTimesNs,
                        registration.strictEvidenceSnapshot());
            } catch (Throwable caught) {
                failure = caught;
                if (caught instanceof Exception) {
                    throw (Exception) caught;
                }
                if (caught instanceof Error) {
                    throw (Error) caught;
                }
                throw new RuntimeException(caught);
            } finally {
                Throwable cleanup = QuickSrSession.closeAll(
                        failure,
                        session,
                        runOptions,
                        options,
                        registration);
                if (failure == null && cleanup != null) {
                    if (cleanup instanceof Exception) {
                        throw (Exception) cleanup;
                    }
                    throw new RuntimeException(cleanup);
                }
            }
        } finally {
            QnnPluginRuntime.unlockProcess();
        }
    }

    private static BoundRunResult runBound(
            OrtEnvironment environment,
            OrtSession session,
            OrtSession.RunOptions runOptions,
            float[] input,
            OutputSpec outputSpec) throws Exception {
        FloatBuffer inputBuffer = ByteBuffer.allocateDirect(INPUT_VALUES * Float.BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        inputBuffer.put(input).rewind();
        ByteBuffer outputBuffer = ByteBuffer.allocateDirect(outputSpec.outputBytes)
                .order(ByteOrder.nativeOrder());
        OnnxTensor inputTensor = OnnxTensor.createTensor(
                environment, inputBuffer, INPUT_SHAPE);
        OnnxTensor outputTensor = createOutputTensor(environment, outputBuffer, outputSpec);
        try (inputTensor; outputTensor) {
            Map<String, OnnxTensor> inputs = Collections.singletonMap(INPUT_NAME, inputTensor);
            Map<String, OnnxTensor> outputs = Collections.singletonMap(
                    outputSpec.outputName, outputTensor);
            for (int run = 0; run < WARMUP_RUNS; run++) {
                execute(session, runOptions, inputs, outputs);
            }
            long[] runTimesNs = new long[MEASURED_RUNS];
            byte[] reference = null;
            for (int run = 0; run < MEASURED_RUNS; run++) {
                long startedNs = System.nanoTime();
                execute(session, runOptions, inputs, outputs);
                runTimesNs[run] = System.nanoTime() - startedNs;
                byte[] output = snapshot(outputBuffer, outputSpec.outputBytes);
                if (reference == null) {
                    reference = output;
                } else {
                    assertArrayEquals("QNN output changed between runs", reference, output);
                }
            }
            return new BoundRunResult(reference, runTimesNs);
        }
    }

    private static byte[] runOnce(
            OrtEnvironment environment,
            OrtSession session,
            OrtSession.RunOptions runOptions,
            float[] input,
            long[] ortRunNs,
            OutputSpec outputSpec) throws Exception {
        FloatBuffer inputBuffer = ByteBuffer.allocateDirect(INPUT_VALUES * Float.BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        inputBuffer.put(input).rewind();
        ByteBuffer outputBuffer = ByteBuffer.allocateDirect(outputSpec.outputBytes)
                .order(ByteOrder.nativeOrder());
        OnnxTensor inputTensor = OnnxTensor.createTensor(
                environment, inputBuffer, INPUT_SHAPE);
        OnnxTensor outputTensor = createOutputTensor(environment, outputBuffer, outputSpec);
        try (inputTensor; outputTensor) {
            Map<String, OnnxTensor> inputs = Collections.singletonMap(INPUT_NAME, inputTensor);
            Map<String, OnnxTensor> outputs = Collections.singletonMap(
                    outputSpec.outputName, outputTensor);
            long startedNs = System.nanoTime();
            execute(session, runOptions, inputs, outputs);
            if (ortRunNs != null) {
                ortRunNs[0] = System.nanoTime() - startedNs;
            }
            return snapshot(outputBuffer, outputSpec.outputBytes);
        }
    }

    private static OnnxTensor createOutputTensor(
            OrtEnvironment environment,
            ByteBuffer outputBuffer,
            OutputSpec outputSpec) throws Exception {
        return outputSpec.type == OnnxJavaType.FLOAT
                ? OnnxTensor.createTensor(
                        environment,
                        outputBuffer.asFloatBuffer(),
                        outputSpec.shape)
                : OnnxTensor.createTensor(
                        environment,
                        outputBuffer,
                        outputSpec.shape,
                        outputSpec.type);
    }

    private static void execute(
            OrtSession session,
            OrtSession.RunOptions runOptions,
            Map<String, OnnxTensor> inputs,
            Map<String, OnnxTensor> outputs) throws Exception {
        if (runOptions == null) {
            try (OrtSession.Result ignored = session.run(inputs, outputs)) {
                // The result does not own the pinned tensors.
            }
        } else {
            try (OrtSession.Result ignored = session.run(
                    inputs,
                    Collections.emptySet(),
                    outputs,
                    runOptions)) {
                // The result does not own the pinned tensors.
            }
        }
    }

    private static byte[] snapshot(ByteBuffer outputBuffer, int outputBytes) {
        byte[] output = new byte[outputBytes];
        ByteBuffer copy = outputBuffer.duplicate();
        copy.rewind();
        copy.get(output);
        return output;
    }

    private static float[] deterministicInput() {
        float[] input = new float[INPUT_VALUES];
        for (int index = 0; index < input.length; index++) {
            input[index] = ((index * 37L + index / 97L + 11L) % 1024L) / 1023.0f;
        }
        return input;
    }

    private static byte[] readAsset(Context context, String name) throws Exception {
        try (InputStream input = context.getAssets().open(name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte item : digest) {
            result.append(String.format("%02x", item & 0xff));
        }
        return result.toString();
    }

    private static String crc32(byte[] value) {
        CRC32 crc = new CRC32();
        crc.update(value, 0, value.length);
        return String.format("%08x", crc.getValue());
    }

    private static boolean hasNonZero(byte[] value) {
        for (byte item : value) {
            if (item != 0) {
                return true;
            }
        }
        return false;
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static double nanosToMillis(long nanos) {
        return Math.round(nanos / 10_000.0) / 100.0;
    }

    private static final class QnnResult {
        final byte[] output;
        final long[] runTimesNs;
        final JSONObject strictEvidence;

        QnnResult(byte[] output, long[] runTimesNs, JSONObject strictEvidence) {
            this.output = output;
            this.runTimesNs = runTimesNs;
            this.strictEvidence = strictEvidence;
        }
    }

    private static final class BoundRunResult {
        final byte[] output;
        final long[] runTimesNs;

        BoundRunResult(byte[] output, long[] runTimesNs) {
            this.output = output;
            this.runTimesNs = runTimesNs;
        }
    }

    private static final class OutputSpec {
        final String id;
        final String asset;
        final String outputName;
        final long[] shape;
        final OnnxJavaType type;
        final int outputBytes;

        OutputSpec(
                String id,
                String asset,
                String outputName,
                long[] shape,
                OnnxJavaType type,
                int outputBytes) {
            this.id = id;
            this.asset = asset;
            this.outputName = outputName;
            this.shape = shape.clone();
            this.type = type;
            this.outputBytes = outputBytes;
        }
    }
}
