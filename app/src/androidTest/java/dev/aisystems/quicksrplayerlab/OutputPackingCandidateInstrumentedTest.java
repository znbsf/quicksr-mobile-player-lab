package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import android.os.Build;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Correctness plus directional device timings for output-packing architecture candidates.
 *
 * <p>The timings deliberately have no pass/fail threshold: they are microbenchmarks, not
 * end-to-end playback throughput. Byte-for-byte equality is the test gate.
 */
@RunWith(AndroidJUnit4.class)
public final class OutputPackingCandidateInstrumentedTest {
    private static final String TAG = "QuickSRPackProbe";
    private static final int INPUT_WIDTH = 640;
    private static final int INPUT_HEIGHT = 360;
    private static final int OUTPUT_WIDTH = 1920;
    private static final int OUTPUT_HEIGHT = 1080;
    private static final int WARMUP = 2;
    private static final int ITERATIONS = 5;
    private static volatile Object benchmarkSink;

    @Test
    public void candidatesMatchCurrentJavaPathAt1080p() throws Exception {
        int outputPixels = OUTPUT_WIDTH * OUTPUT_HEIGHT;
        float[] tensor = deterministicTensor(outputPixels);
        float[] nhwcTensor = toNhwc(tensor, outputPixels);
        FloatBuffer directTensor = ByteBuffer.allocateDirect(tensor.length * Float.BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        directTensor.put(tensor).rewind();
        byte[] inputRgba = deterministicInputRgba();
        byte[] opaqueInputRgba = inputRgba.clone();
        forceOpaqueAlpha(opaqueInputRgba);
        byte[] heapRgba = new byte[outputPixels * 4];
        ByteBuffer directRgba = direct(heapRgba.length);
        byte[] heapRgb = new byte[outputPixels * 3];
        ByteBuffer directRgb = direct(heapRgb.length);
        int[] alphaXOffsets = alphaXOffsets();
        int[] alphaRowOffsets = alphaRowOffsets();
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            packCurrent(tensor, inputRgba, heapRgba, directRgba);
            byte[] expected = bytes(directRgba);
            byte[] expectedRgb = stripAlpha(expected);
            packCurrent(tensor, opaqueInputRgba, heapRgba, directRgba);
            byte[] expectedOpaque = bytes(directRgba);

            packDirectInt(tensor, inputRgba, directRgba, 1, workers);
            assertArrayEquals(expected, bytes(directRgba));
            packHeapParallel(tensor, inputRgba, heapRgba, directRgba, 2, workers);
            assertArrayEquals(expected, bytes(directRgba));
            packHeapParallel(tensor, inputRgba, heapRgba, directRgba, 4, workers);
            assertArrayEquals(expected, bytes(directRgba));
            packDirectInt(tensor, inputRgba, directRgba, 2, workers);
            assertArrayEquals(expected, bytes(directRgba));
            packDirectInt(tensor, inputRgba, directRgba, 4, workers);
            assertArrayEquals(expected, bytes(directRgba));
            packRgbParallel(tensor, heapRgb, directRgb, 1, workers);
            assertArrayEquals(expectedRgb, bytes(directRgb));
            packRgbParallel(tensor, heapRgb, directRgb, 2, workers);
            assertArrayEquals(expectedRgb, bytes(directRgb));
            packRgbParallel(tensor, heapRgb, directRgb, 4, workers);
            assertArrayEquals(expectedRgb, bytes(directRgb));
            packFloatBufferParallel(
                    directTensor, inputRgba, heapRgba, directRgba, 1, workers);
            assertArrayEquals(expected, bytes(directRgba));
            packFloatBufferParallel(
                    directTensor, inputRgba, heapRgba, directRgba, 4, workers);
            assertArrayEquals(expected, bytes(directRgba));
            packMappedAlphaParallel(
                    tensor,
                    inputRgba,
                    heapRgba,
                    directRgba,
                    alphaXOffsets,
                    alphaRowOffsets,
                    1,
                    workers);
            assertArrayEquals(expected, bytes(directRgba));
            packNhwcMappedAlpha(
                    nhwcTensor,
                    inputRgba,
                    heapRgba,
                    directRgba,
                    alphaXOffsets,
                    alphaRowOffsets,
                    1,
                    workers);
            assertArrayEquals(expected, bytes(directRgba));
            packNhwcMappedAlpha(
                    nhwcTensor,
                    inputRgba,
                    heapRgba,
                    directRgba,
                    alphaXOffsets,
                    alphaRowOffsets,
                    2,
                    workers);
            assertArrayEquals(expected, bytes(directRgba));
            packNhwcMappedAlpha(
                    nhwcTensor,
                    inputRgba,
                    heapRgba,
                    directRgba,
                    alphaXOffsets,
                    alphaRowOffsets,
                    4,
                    workers);
            assertArrayEquals(expected, bytes(directRgba));
            packMappedAlphaParallel(
                    tensor,
                    inputRgba,
                    heapRgba,
                    directRgba,
                    alphaXOffsets,
                    alphaRowOffsets,
                    4,
                    workers);
            assertArrayEquals(expected, bytes(directRgba));
            packOpaqueRgbaParallel(tensor, heapRgba, directRgba, 1, workers);
            assertArrayEquals(expectedOpaque, bytes(directRgba));
            packOpaqueRgbaParallel(tensor, heapRgba, directRgba, 4, workers);
            assertArrayEquals(expectedOpaque, bytes(directRgba));

            long currentNs = benchmark(() -> packCurrent(
                    tensor, inputRgba, heapRgba, directRgba));
            long currentOpaqueNs = benchmark(() -> packCurrent(
                    tensor, opaqueInputRgba, heapRgba, directRgba));
            long directIntNs = benchmark(() -> packDirectInt(
                    tensor, inputRgba, directRgba, 1, workers));
            long heap2Ns = benchmark(() -> packHeapParallel(
                    tensor, inputRgba, heapRgba, directRgba, 2, workers));
            long heap4Ns = benchmark(() -> packHeapParallel(
                    tensor, inputRgba, heapRgba, directRgba, 4, workers));
            long direct2Ns = benchmark(() -> packDirectInt(
                    tensor, inputRgba, directRgba, 2, workers));
            long direct4Ns = benchmark(() -> packDirectInt(
                    tensor, inputRgba, directRgba, 4, workers));
            long rgb1Ns = benchmark(() -> packRgbParallel(
                    tensor, heapRgb, directRgb, 1, workers));
            long rgb2Ns = benchmark(() -> packRgbParallel(
                    tensor, heapRgb, directRgb, 2, workers));
            long rgb4Ns = benchmark(() -> packRgbParallel(
                    tensor, heapRgb, directRgb, 4, workers));
            long directTensor1Ns = benchmark(() -> packFloatBufferParallel(
                    directTensor, inputRgba, heapRgba, directRgba, 1, workers));
            long directTensor4Ns = benchmark(() -> packFloatBufferParallel(
                    directTensor, inputRgba, heapRgba, directRgba, 4, workers));
            long mappedAlpha1Ns = benchmark(() -> packMappedAlphaParallel(
                    tensor,
                    inputRgba,
                    heapRgba,
                    directRgba,
                    alphaXOffsets,
                    alphaRowOffsets,
                    1,
                    workers));
            long mappedAlpha4Ns = benchmark(() -> packMappedAlphaParallel(
                    tensor,
                    inputRgba,
                    heapRgba,
                    directRgba,
                    alphaXOffsets,
                    alphaRowOffsets,
                    4,
                    workers));
            long nhwcMappedAlpha1Ns = benchmark(() -> packNhwcMappedAlpha(
                    nhwcTensor,
                    inputRgba,
                    heapRgba,
                    directRgba,
                    alphaXOffsets,
                    alphaRowOffsets,
                    1,
                    workers));
            long nhwcMappedAlpha2Ns = benchmark(() -> packNhwcMappedAlpha(
                    nhwcTensor,
                    inputRgba,
                    heapRgba,
                    directRgba,
                    alphaXOffsets,
                    alphaRowOffsets,
                    2,
                    workers));
            long nhwcMappedAlpha4Ns = benchmark(() -> packNhwcMappedAlpha(
                    nhwcTensor,
                    inputRgba,
                    heapRgba,
                    directRgba,
                    alphaXOffsets,
                    alphaRowOffsets,
                    4,
                    workers));
            long opaqueRgba1Ns = benchmark(() -> packOpaqueRgbaParallel(
                    tensor, heapRgba, directRgba, 1, workers));
            long opaqueRgba4Ns = benchmark(() -> packOpaqueRgbaParallel(
                    tensor, heapRgba, directRgba, 4, workers));

            packMappedAlphaParallel(
                    tensor,
                    inputRgba,
                    heapRgba,
                    directRgba,
                    alphaXOffsets,
                    alphaRowOffsets,
                    1,
                    workers);
            String outputCrc = QuickSrVideoEffect.crc32Hex(directRgba);
            assertEquals(QuickSrVideoEffect.crc32Hex(expected), outputCrc);
            Log.i(TAG, "{\"scope\":\"" + probeScope() + "\""
                    + ",\"output\":\"1920x1080\""
                    + ",\"iterations\":" + ITERATIONS
                    + ",\"currentHeapThenDirectP50Ms\":" + millis(currentNs)
                    + ",\"currentOpaqueInputP50Ms\":" + millis(currentOpaqueNs)
                    + ",\"directIntP50Ms\":" + millis(directIntNs)
                    + ",\"heapParallel2P50Ms\":" + millis(heap2Ns)
                    + ",\"heapParallel4P50Ms\":" + millis(heap4Ns)
                    + ",\"directParallel2P50Ms\":" + millis(direct2Ns)
                    + ",\"directParallel4P50Ms\":" + millis(direct4Ns)
                    + ",\"rgbHeap1P50Ms\":" + millis(rgb1Ns)
                    + ",\"rgbHeap2P50Ms\":" + millis(rgb2Ns)
                    + ",\"rgbHeap4P50Ms\":" + millis(rgb4Ns)
                    + ",\"rgbUploadBytes\":" + directRgb.remaining()
                    + ",\"directTensorHeap1P50Ms\":" + millis(directTensor1Ns)
                    + ",\"directTensorHeap4P50Ms\":" + millis(directTensor4Ns)
                    + ",\"mappedAlphaHeap1P50Ms\":" + millis(mappedAlpha1Ns)
                    + ",\"mappedAlphaHeap4P50Ms\":" + millis(mappedAlpha4Ns)
                    + ",\"nhwcMappedAlphaHeap1P50Ms\":" + millis(nhwcMappedAlpha1Ns)
                    + ",\"nhwcMappedAlphaHeap2P50Ms\":" + millis(nhwcMappedAlpha2Ns)
                    + ",\"nhwcMappedAlphaHeap4P50Ms\":" + millis(nhwcMappedAlpha4Ns)
                    + ",\"opaqueRgbaHeap1P50Ms\":" + millis(opaqueRgba1Ns)
                    + ",\"opaqueRgbaHeap4P50Ms\":" + millis(opaqueRgba4Ns)
                    + ",\"crc32\":\"" + outputCrc + "\"}");
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    public void logsPreprocessCopyAndFullFrameScanCosts() throws Exception {
        int outputPixels = OUTPUT_WIDTH * OUTPUT_HEIGHT;
        float[] outputTensor = deterministicTensor(outputPixels);
        byte[] inputRgba = deterministicInputRgba();
        float[] inputTensor = new float[INPUT_WIDTH * INPUT_HEIGHT * 3];
        byte[] heapRgba = new byte[outputPixels * 4];
        ByteBuffer directRgba = direct(heapRgba.length);
        QuickSrVideoEffect.packNchwToRgba(
                outputTensor,
                inputRgba,
                INPUT_WIDTH,
                INPUT_HEIGHT,
                OUTPUT_WIDTH,
                OUTPUT_HEIGHT,
                heapRgba);
        directRgba.put(heapRgba);
        directRgba.flip();

        long preprocessNs = benchmark(() -> QuickSrVideoEffect.rgbaToNchw(
                inputRgba, inputTensor, INPUT_WIDTH, INPUT_HEIGHT));
        long outputPackNs = benchmark(() -> QuickSrVideoEffect.packNchwToRgba(
                outputTensor,
                inputRgba,
                INPUT_WIDTH,
                INPUT_HEIGHT,
                OUTPUT_WIDTH,
                OUTPUT_HEIGHT,
                heapRgba));
        long directCopyNs = benchmark(() -> {
            directRgba.clear();
            directRgba.put(heapRgba);
            directRgba.flip();
        });
        long inputCrcNs = benchmark(() ->
                benchmarkSink = QuickSrVideoEffect.crc32Hex(inputRgba));
        long outputHeapCrcNs = benchmark(() ->
                benchmarkSink = QuickSrVideoEffect.crc32Hex(heapRgba));
        long outputDirectCrcNs = benchmark(() ->
                benchmarkSink = QuickSrVideoEffect.crc32Hex(directRgba));
        long finiteScanNs = benchmark(() -> {
            int nonFinite = 0;
            for (float value : outputTensor) {
                if (!Float.isFinite(value)) {
                    nonFinite++;
                }
            }
            benchmarkSink = nonFinite;
        });

        Log.i(TAG, "{\"scope\":\"" + probeScope() + "\""
                + ",\"probe\":\"full_frame_scan_costs\""
                + ",\"preprocessP50Ms\":" + millis(preprocessNs)
                + ",\"outputPackOnlyP50Ms\":" + millis(outputPackNs)
                + ",\"heapToDirectCopyP50Ms\":" + millis(directCopyNs)
                + ",\"inputCrcP50Ms\":" + millis(inputCrcNs)
                + ",\"outputHeapCrcP50Ms\":" + millis(outputHeapCrcNs)
                + ",\"outputDirectCrcP50Ms\":" + millis(outputDirectCrcNs)
                + ",\"floatOutputFiniteScanP50Ms\":" + millis(finiteScanNs)
                + "}");
    }

    @Test
    public void mappedAlphaPackerAbbaMatchesLegacyAt1080p() throws Exception {
        int outputPixels = OUTPUT_WIDTH * OUTPUT_HEIGHT;
        float[] tensor = deterministicTensor(outputPixels);
        byte[] inputRgba = deterministicInputRgba();
        byte[] heapRgba = new byte[outputPixels * 4];
        ByteBuffer directRgba = direct(heapRgba.length);
        int[] alphaXOffsets = alphaXOffsets();
        int[] alphaRowOffsets = alphaRowOffsets();
        ExecutorService unusedForSingleStripe = Executors.newSingleThreadExecutor();
        try {
            packCurrent(tensor, inputRgba, heapRgba, directRgba);
            byte[] expected = bytes(directRgba);
            packMappedAlphaParallel(
                    tensor,
                    inputRgba,
                    heapRgba,
                    directRgba,
                    alphaXOffsets,
                    alphaRowOffsets,
                    1,
                    unusedForSingleStripe);
            assertArrayEquals(expected, bytes(directRgba));

            for (int index = 0; index < 5; index++) {
                packCurrent(tensor, inputRgba, heapRgba, directRgba);
                packMappedAlphaParallel(
                        tensor,
                        inputRgba,
                        heapRgba,
                        directRgba,
                        alphaXOffsets,
                        alphaRowOffsets,
                        1,
                        unusedForSingleStripe);
            }
            long legacyA1Ns = benchmark(() -> packCurrent(
                    tensor, inputRgba, heapRgba, directRgba));
            long mappedB1Ns = benchmark(() -> packMappedAlphaParallel(
                    tensor,
                    inputRgba,
                    heapRgba,
                    directRgba,
                    alphaXOffsets,
                    alphaRowOffsets,
                    1,
                    unusedForSingleStripe));
            long mappedB2Ns = benchmark(() -> packMappedAlphaParallel(
                    tensor,
                    inputRgba,
                    heapRgba,
                    directRgba,
                    alphaXOffsets,
                    alphaRowOffsets,
                    1,
                    unusedForSingleStripe));
            long legacyA2Ns = benchmark(() -> packCurrent(
                    tensor, inputRgba, heapRgba, directRgba));
            double legacyMeanNs = (legacyA1Ns + legacyA2Ns) / 2.0;
            double mappedMeanNs = (mappedB1Ns + mappedB2Ns) / 2.0;
            double gainPercent = (legacyMeanNs / mappedMeanNs - 1.0) * 100.0;

            Log.i(TAG, "{\"scope\":\"" + probeScope() + "\""
                    + ",\"probe\":\"mapped_alpha_abba\""
                    + ",\"legacyA1P50Ms\":" + millis(legacyA1Ns)
                    + ",\"mappedB1P50Ms\":" + millis(mappedB1Ns)
                    + ",\"mappedB2P50Ms\":" + millis(mappedB2Ns)
                    + ",\"legacyA2P50Ms\":" + millis(legacyA2Ns)
                    + ",\"mappedGainPercent\":"
                    + Math.round(gainPercent * 100.0) / 100.0
                    + "}");
        } finally {
            unusedForSingleStripe.shutdownNow();
        }
    }

    private static String probeScope() {
        String fingerprint = Build.FINGERPRINT == null ? "" : Build.FINGERPRINT;
        String hardware = Build.HARDWARE == null ? "" : Build.HARDWARE;
        String model = Build.MODEL == null ? "" : Build.MODEL;
        boolean emulator = fingerprint.startsWith("generic")
                || fingerprint.contains("emulator")
                || hardware.contains("goldfish")
                || hardware.contains("ranchu")
                || model.contains("Emulator");
        if (emulator) {
            return "emulator_directional_microbenchmark";
        }
        boolean arm64 = Build.SUPPORTED_ABIS.length > 0
                && "arm64-v8a".equals(Build.SUPPORTED_ABIS[0]);
        return arm64
                ? "physical_arm64_directional_microbenchmark"
                : "physical_device_directional_microbenchmark";
    }

    private static float[] deterministicTensor(int outputPixels) {
        float[] output = new float[outputPixels * 3];
        for (int index = 0; index < output.length; index++) {
            int value = (index * 1103515245 + 12345) >>> 16;
            output[index] = (value & 0xffff) / 65535.0f * 1.2f - 0.1f;
        }
        float[] boundaries = {
                Float.NaN,
                Float.NEGATIVE_INFINITY,
                -0.0f,
                0.0f,
                0.001f,
                0.5f,
                0.501f,
                1.0f,
                Float.POSITIVE_INFINITY
        };
        for (int plane = 0; plane < 3; plane++) {
            System.arraycopy(boundaries, 0, output, plane * outputPixels, boundaries.length);
        }
        return output;
    }

    private static float[] toNhwc(float[] nchw, int outputPixels) {
        float[] nhwc = new float[nchw.length];
        for (int pixel = 0; pixel < outputPixels; pixel++) {
            nhwc[pixel * 3] = nchw[pixel];
            nhwc[pixel * 3 + 1] = nchw[outputPixels + pixel];
            nhwc[pixel * 3 + 2] = nchw[outputPixels * 2 + pixel];
        }
        return nhwc;
    }

    private static byte[] deterministicInputRgba() {
        byte[] input = new byte[INPUT_WIDTH * INPUT_HEIGHT * 4];
        for (int pixel = 0; pixel < INPUT_WIDTH * INPUT_HEIGHT; pixel++) {
            int offset = pixel * 4;
            input[offset] = (byte) (pixel * 3 + 1);
            input[offset + 1] = (byte) (pixel * 5 + 2);
            input[offset + 2] = (byte) (pixel * 7 + 3);
            input[offset + 3] = (byte) (pixel * 11 + 4);
        }
        return input;
    }

    private static void packCurrent(
            float[] tensor,
            byte[] inputRgba,
            byte[] heapRgba,
            ByteBuffer directRgba) {
        QuickSrVideoEffect.packNchwToRgba(
                tensor,
                inputRgba,
                INPUT_WIDTH,
                INPUT_HEIGHT,
                OUTPUT_WIDTH,
                OUTPUT_HEIGHT,
                heapRgba);
        directRgba.clear();
        directRgba.put(heapRgba);
        directRgba.flip();
    }

    private static void packHeapParallel(
            float[] tensor,
            byte[] inputRgba,
            byte[] heapRgba,
            ByteBuffer directRgba,
            int stripes,
            ExecutorService workers) throws Exception {
        runStripes(stripes, workers, (startY, endY) -> packHeapRows(
                tensor, inputRgba, heapRgba, startY, endY));
        directRgba.clear();
        directRgba.put(heapRgba);
        directRgba.flip();
    }

    private static void packHeapRows(
            float[] tensor,
            byte[] inputRgba,
            byte[] heapRgba,
            int startY,
            int endY) {
        int outputPixels = OUTPUT_WIDTH * OUTPUT_HEIGHT;
        int greenPlane = outputPixels;
        int bluePlane = outputPixels * 2;
        for (int y = startY; y < endY; y++) {
            int inputY = Math.min(INPUT_HEIGHT - 1, y * INPUT_HEIGHT / OUTPUT_HEIGHT);
            int inputAlphaRow = inputY * INPUT_WIDTH * 4;
            int outputPixel = y * OUTPUT_WIDTH;
            int rgbaOffset = outputPixel * 4;
            for (int x = 0; x < OUTPUT_WIDTH; x++, outputPixel++) {
                heapRgba[rgbaOffset++] = normalizedToByte(tensor[outputPixel]);
                heapRgba[rgbaOffset++] = normalizedToByte(tensor[greenPlane + outputPixel]);
                heapRgba[rgbaOffset++] = normalizedToByte(tensor[bluePlane + outputPixel]);
                int inputX = Math.min(INPUT_WIDTH - 1, x * INPUT_WIDTH / OUTPUT_WIDTH);
                heapRgba[rgbaOffset++] = inputRgba[inputAlphaRow + inputX * 4 + 3];
            }
        }
    }

    private static void packDirectInt(
            float[] tensor,
            byte[] inputRgba,
            ByteBuffer directRgba,
            int stripes,
            ExecutorService workers) throws Exception {
        directRgba.clear();
        IntBuffer pixels = directRgba.order(ByteOrder.nativeOrder()).asIntBuffer();
        runStripes(stripes, workers, (startY, endY) -> packDirectRows(
                tensor, inputRgba, pixels.duplicate(), startY, endY));
        directRgba.position(0);
        directRgba.limit(directRgba.capacity());
    }

    private static void packRgbParallel(
            float[] tensor,
            byte[] heapRgb,
            ByteBuffer directRgb,
            int stripes,
            ExecutorService workers) throws Exception {
        runStripes(stripes, workers, (startY, endY) ->
                packRgbRows(tensor, heapRgb, startY, endY));
        directRgb.clear();
        directRgb.put(heapRgb);
        directRgb.flip();
    }

    private static void packMappedAlphaParallel(
            float[] tensor,
            byte[] inputRgba,
            byte[] heapRgba,
            ByteBuffer directRgba,
            int[] alphaXOffsets,
            int[] alphaRowOffsets,
            int stripes,
            ExecutorService workers) throws Exception {
        if (stripes == 1) {
            QuickSrVideoEffect.packNchwToRgbaWithAlphaMaps(
                    tensor,
                    inputRgba,
                    INPUT_WIDTH,
                    INPUT_HEIGHT,
                    OUTPUT_WIDTH,
                    OUTPUT_HEIGHT,
                    heapRgba,
                    alphaXOffsets,
                    alphaRowOffsets);
        } else {
            runStripes(stripes, workers, (startY, endY) -> packMappedAlphaRows(
                    tensor,
                    inputRgba,
                    heapRgba,
                    alphaXOffsets,
                    alphaRowOffsets,
                    startY,
                    endY));
        }
        directRgba.clear();
        directRgba.put(heapRgba);
        directRgba.flip();
    }

    private static void packNhwcMappedAlpha(
            float[] tensor,
            byte[] inputRgba,
            byte[] heapRgba,
            ByteBuffer directRgba,
            int[] alphaXOffsets,
            int[] alphaRowOffsets,
            int stripes,
            ExecutorService workers) throws Exception {
        QuickSrVideoEffect.packNhwcToRgbaWithAlphaMapsParallel(
                tensor,
                inputRgba,
                INPUT_WIDTH,
                INPUT_HEIGHT,
                OUTPUT_WIDTH,
                OUTPUT_HEIGHT,
                heapRgba,
                alphaXOffsets,
                alphaRowOffsets,
                stripes,
                workers);
        directRgba.clear();
        directRgba.put(heapRgba);
        directRgba.flip();
    }

    private static void packOpaqueRgbaParallel(
            float[] tensor,
            byte[] heapRgba,
            ByteBuffer directRgba,
            int stripes,
            ExecutorService workers) throws Exception {
        runStripes(stripes, workers, (startY, endY) ->
                packOpaqueRgbaRows(tensor, heapRgba, startY, endY));
        directRgba.clear();
        directRgba.put(heapRgba);
        directRgba.flip();
    }

    private static void packFloatBufferParallel(
            FloatBuffer tensor,
            byte[] inputRgba,
            byte[] heapRgba,
            ByteBuffer directRgba,
            int stripes,
            ExecutorService workers) throws Exception {
        runStripes(stripes, workers, (startY, endY) -> packFloatBufferRows(
                tensor.duplicate(), inputRgba, heapRgba, startY, endY));
        directRgba.clear();
        directRgba.put(heapRgba);
        directRgba.flip();
    }

    private static void packFloatBufferRows(
            FloatBuffer tensor,
            byte[] inputRgba,
            byte[] heapRgba,
            int startY,
            int endY) {
        int outputPixels = OUTPUT_WIDTH * OUTPUT_HEIGHT;
        int greenPlane = outputPixels;
        int bluePlane = outputPixels * 2;
        for (int y = startY; y < endY; y++) {
            int inputY = Math.min(INPUT_HEIGHT - 1, y * INPUT_HEIGHT / OUTPUT_HEIGHT);
            int inputAlphaRow = inputY * INPUT_WIDTH * 4;
            int outputPixel = y * OUTPUT_WIDTH;
            int rgbaOffset = outputPixel * 4;
            for (int x = 0; x < OUTPUT_WIDTH; x++, outputPixel++) {
                heapRgba[rgbaOffset++] = normalizedToByte(tensor.get(outputPixel));
                heapRgba[rgbaOffset++] = normalizedToByte(tensor.get(greenPlane + outputPixel));
                heapRgba[rgbaOffset++] = normalizedToByte(tensor.get(bluePlane + outputPixel));
                int inputX = Math.min(INPUT_WIDTH - 1, x * INPUT_WIDTH / OUTPUT_WIDTH);
                heapRgba[rgbaOffset++] = inputRgba[inputAlphaRow + inputX * 4 + 3];
            }
        }
    }

    private static void packRgbRows(
            float[] tensor,
            byte[] heapRgb,
            int startY,
            int endY) {
        int outputPixels = OUTPUT_WIDTH * OUTPUT_HEIGHT;
        int greenPlane = outputPixels;
        int bluePlane = outputPixels * 2;
        for (int y = startY; y < endY; y++) {
            int outputPixel = y * OUTPUT_WIDTH;
            int rgbOffset = outputPixel * 3;
            for (int x = 0; x < OUTPUT_WIDTH; x++, outputPixel++) {
                heapRgb[rgbOffset++] = normalizedToByte(tensor[outputPixel]);
                heapRgb[rgbOffset++] = normalizedToByte(tensor[greenPlane + outputPixel]);
                heapRgb[rgbOffset++] = normalizedToByte(tensor[bluePlane + outputPixel]);
            }
        }
    }

    private static void packMappedAlphaRows(
            float[] tensor,
            byte[] inputRgba,
            byte[] heapRgba,
            int[] alphaXOffsets,
            int[] alphaRowOffsets,
            int startY,
            int endY) {
        int outputPixels = OUTPUT_WIDTH * OUTPUT_HEIGHT;
        int greenPlane = outputPixels;
        int bluePlane = outputPixels * 2;
        for (int y = startY; y < endY; y++) {
            int inputAlphaRow = alphaRowOffsets[y];
            int outputPixel = y * OUTPUT_WIDTH;
            int rgbaOffset = outputPixel * 4;
            for (int x = 0; x < OUTPUT_WIDTH; x++, outputPixel++) {
                heapRgba[rgbaOffset++] = normalizedToByte(tensor[outputPixel]);
                heapRgba[rgbaOffset++] = normalizedToByte(tensor[greenPlane + outputPixel]);
                heapRgba[rgbaOffset++] = normalizedToByte(tensor[bluePlane + outputPixel]);
                heapRgba[rgbaOffset++] = inputRgba[inputAlphaRow + alphaXOffsets[x]];
            }
        }
    }

    private static void packOpaqueRgbaRows(
            float[] tensor,
            byte[] heapRgba,
            int startY,
            int endY) {
        int outputPixels = OUTPUT_WIDTH * OUTPUT_HEIGHT;
        int greenPlane = outputPixels;
        int bluePlane = outputPixels * 2;
        for (int y = startY; y < endY; y++) {
            int outputPixel = y * OUTPUT_WIDTH;
            int rgbaOffset = outputPixel * 4;
            for (int x = 0; x < OUTPUT_WIDTH; x++, outputPixel++) {
                heapRgba[rgbaOffset++] = normalizedToByte(tensor[outputPixel]);
                heapRgba[rgbaOffset++] = normalizedToByte(tensor[greenPlane + outputPixel]);
                heapRgba[rgbaOffset++] = normalizedToByte(tensor[bluePlane + outputPixel]);
                heapRgba[rgbaOffset++] = (byte) 0xff;
            }
        }
    }

    private static void packDirectRows(
            float[] tensor,
            byte[] inputRgba,
            IntBuffer destination,
            int startY,
            int endY) {
        int outputPixels = OUTPUT_WIDTH * OUTPUT_HEIGHT;
        int greenPlane = outputPixels;
        int bluePlane = outputPixels * 2;
        boolean littleEndian = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
        for (int y = startY; y < endY; y++) {
            int inputY = Math.min(INPUT_HEIGHT - 1, y * INPUT_HEIGHT / OUTPUT_HEIGHT);
            int inputAlphaRow = inputY * INPUT_WIDTH * 4;
            int outputPixel = y * OUTPUT_WIDTH;
            for (int x = 0; x < OUTPUT_WIDTH; x++, outputPixel++) {
                int red = normalizedToByte(tensor[outputPixel]) & 0xff;
                int green = normalizedToByte(tensor[greenPlane + outputPixel]) & 0xff;
                int blue = normalizedToByte(tensor[bluePlane + outputPixel]) & 0xff;
                int inputX = Math.min(INPUT_WIDTH - 1, x * INPUT_WIDTH / OUTPUT_WIDTH);
                int alpha = inputRgba[inputAlphaRow + inputX * 4 + 3] & 0xff;
                int packed = littleEndian
                        ? red | green << 8 | blue << 16 | alpha << 24
                        : red << 24 | green << 16 | blue << 8 | alpha;
                destination.put(outputPixel, packed);
            }
        }
    }

    private static void runStripes(
            int stripes,
            ExecutorService workers,
            RowTask task) throws Exception {
        if (stripes <= 0 || stripes > OUTPUT_HEIGHT) {
            throw new IllegalArgumentException("Invalid stripe count: " + stripes);
        }
        if (stripes == 1) {
            task.run(0, OUTPUT_HEIGHT);
            return;
        }
        List<Callable<Void>> calls = new ArrayList<>(stripes);
        for (int stripe = 0; stripe < stripes; stripe++) {
            int startY = stripe * OUTPUT_HEIGHT / stripes;
            int endY = (stripe + 1) * OUTPUT_HEIGHT / stripes;
            calls.add(() -> {
                task.run(startY, endY);
                return null;
            });
        }
        List<Future<Void>> futures = workers.invokeAll(calls);
        for (Future<Void> future : futures) {
            future.get();
        }
    }

    private static long benchmark(ThrowingRunnable action) throws Exception {
        for (int index = 0; index < WARMUP; index++) {
            action.run();
        }
        long[] elapsed = new long[ITERATIONS];
        for (int index = 0; index < ITERATIONS; index++) {
            long started = System.nanoTime();
            action.run();
            elapsed[index] = System.nanoTime() - started;
        }
        Arrays.sort(elapsed);
        return elapsed[elapsed.length / 2];
    }

    private static byte normalizedToByte(float value) {
        if (!(value > 0.0f)) {
            return 0;
        }
        if (value >= 1.0f) {
            return (byte) 0xff;
        }
        return (byte) ((int) (value * 255.0f + 0.5f));
    }

    private static ByteBuffer direct(int byteCount) {
        return ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder());
    }

    private static byte[] bytes(ByteBuffer source) {
        ByteBuffer copy = source.duplicate();
        copy.position(0);
        byte[] value = new byte[copy.remaining()];
        copy.get(value);
        return value;
    }

    private static byte[] stripAlpha(byte[] rgba) {
        byte[] rgb = new byte[rgba.length / 4 * 3];
        int target = 0;
        for (int source = 0; source < rgba.length; source += 4) {
            rgb[target++] = rgba[source];
            rgb[target++] = rgba[source + 1];
            rgb[target++] = rgba[source + 2];
        }
        return rgb;
    }

    private static int[] alphaXOffsets() {
        return QuickSrVideoEffect.createAlphaXOffsets(INPUT_WIDTH, OUTPUT_WIDTH);
    }

    private static int[] alphaRowOffsets() {
        return QuickSrVideoEffect.createAlphaRowOffsets(
                INPUT_WIDTH, INPUT_HEIGHT, OUTPUT_HEIGHT);
    }

    private static void forceOpaqueAlpha(byte[] rgba) {
        for (int offset = 3; offset < rgba.length; offset += 4) {
            rgba[offset] = (byte) 0xff;
        }
    }

    private static double millis(long nanos) {
        return Math.round(nanos / 10_000.0) / 100.0;
    }

    private interface RowTask {
        void run(int startY, int endY) throws Exception;
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
