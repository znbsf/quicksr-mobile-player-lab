package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class QuickSrVideoEffectTest {
    private static final int INPUT_SIDE = 64;
    private static final int OUTPUT_SIDE = 128;

    @Test
    public void profilesBindExpectedFixedShapeModels() {
        assertSame(
                ModelVariant.FIXED64_DCR_FULL,
                QuickSrVideoEffect.Profile.FAST_64.modelVariant());
        assertEquals(64, QuickSrVideoEffect.Profile.FAST_64.inputSide());
        assertEquals(128, QuickSrVideoEffect.Profile.FAST_64.outputSide());

        assertSame(
                ModelVariant.FIXED256X144_DCR_FULL,
                QuickSrVideoEffect.Profile.REALTIME_256X144.modelVariant());
        assertEquals(256, QuickSrVideoEffect.Profile.REALTIME_256X144.inputWidth());
        assertEquals(144, QuickSrVideoEffect.Profile.REALTIME_256X144.inputHeight());
        assertEquals(512, QuickSrVideoEffect.Profile.REALTIME_256X144.outputWidth());
        assertEquals(288, QuickSrVideoEffect.Profile.REALTIME_256X144.outputHeight());

        assertSame(
                ModelVariant.FIXED256_DCR_FULL,
                QuickSrVideoEffect.Profile.HIGH_256.modelVariant());
        assertEquals(256, QuickSrVideoEffect.Profile.HIGH_256.inputSide());
        assertEquals(512, QuickSrVideoEffect.Profile.HIGH_256.outputSide());

        assertSame(
                ModelVariant.FIXED512X288_DCR_FULL,
                QuickSrVideoEffect.Profile.HIGH_512X288.modelVariant());
        assertEquals(512, QuickSrVideoEffect.Profile.HIGH_512X288.inputWidth());
        assertEquals(288, QuickSrVideoEffect.Profile.HIGH_512X288.inputHeight());
        assertEquals(1024, QuickSrVideoEffect.Profile.HIGH_512X288.outputWidth());
        assertEquals(576, QuickSrVideoEffect.Profile.HIGH_512X288.outputHeight());

        assertSame(
                ModelVariant.FIXED640X360_DCR_FULL,
                QuickSrVideoEffect.Profile.FULL_720P.modelVariant());
        assertEquals(640, QuickSrVideoEffect.Profile.FULL_720P.inputWidth());
        assertEquals(360, QuickSrVideoEffect.Profile.FULL_720P.inputHeight());
        assertEquals(1280, QuickSrVideoEffect.Profile.FULL_720P.outputWidth());
        assertEquals(720, QuickSrVideoEffect.Profile.FULL_720P.outputHeight());
        assertEquals(1280, QuickSrVideoEffect.Profile.FULL_720P.canvasWidth());
        assertEquals(720, QuickSrVideoEffect.Profile.FULL_720P.canvasHeight());

        assertSame(
                ModelVariant.FIXED640X360_3X_FULL,
                QuickSrVideoEffect.Profile.FULL_1080P_3X.modelVariant());
        assertEquals(1920, QuickSrVideoEffect.Profile.FULL_1080P_3X.outputWidth());
        assertEquals(1080, QuickSrVideoEffect.Profile.FULL_1080P_3X.outputHeight());
        assertEquals(1920, QuickSrVideoEffect.Profile.FULL_1080P_3X.canvasWidth());
        assertEquals(1080, QuickSrVideoEffect.Profile.FULL_1080P_3X.canvasHeight());

        assertSame(
                ModelVariant.FIXED640X360_4X_FULL,
                QuickSrVideoEffect.Profile.FULL_1440P_4X.modelVariant());
        assertEquals(2560, QuickSrVideoEffect.Profile.FULL_1440P_4X.outputWidth());
        assertEquals(1440, QuickSrVideoEffect.Profile.FULL_1440P_4X.outputHeight());

        assertSame(
                ModelVariant.FIXED640X360_3X_FULL,
                QuickSrVideoEffect.Profile.DISPLAY_4K_FROM_1080P_3X.modelVariant());
        assertEquals(1920, QuickSrVideoEffect.Profile.DISPLAY_4K_FROM_1080P_3X.outputWidth());
        assertEquals(1080, QuickSrVideoEffect.Profile.DISPLAY_4K_FROM_1080P_3X.outputHeight());
        assertEquals(3840, QuickSrVideoEffect.Profile.DISPLAY_4K_FROM_1080P_3X.canvasWidth());
        assertEquals(2160, QuickSrVideoEffect.Profile.DISPLAY_4K_FROM_1080P_3X.canvasHeight());

        assertSame(
                ModelVariant.FIXED512_DCR_FULL,
                QuickSrVideoEffect.Profile.ULTRA_512.modelVariant());
        assertEquals(512, QuickSrVideoEffect.Profile.ULTRA_512.inputSide());
        assertEquals(1024, QuickSrVideoEffect.Profile.ULTRA_512.outputSide());
    }

    @Test
    public void rgbaToNchw_readsGlRgbaBytesWithoutChangingRowOrder() {
        int inputPixels = INPUT_SIDE * INPUT_SIDE;
        byte[] rgba = new byte[inputPixels * 4];
        rgba[0] = (byte) 255;
        rgba[1] = (byte) 128;
        rgba[2] = (byte) 64;
        int last = (inputPixels - 1) * 4;
        rgba[last] = (byte) 32;
        rgba[last + 1] = (byte) 16;
        rgba[last + 2] = (byte) 8;

        float[] nchw = new float[inputPixels * 3];
        QuickSrVideoEffect.rgbaToNchw(rgba, nchw);

        assertEquals(1.0f, nchw[0], 0.0001f);
        assertEquals(128 / 255.0f, nchw[inputPixels], 0.0001f);
        assertEquals(64 / 255.0f, nchw[inputPixels * 2], 0.0001f);
        assertEquals(32 / 255.0f, nchw[inputPixels - 1], 0.0001f);
        assertEquals(16 / 255.0f, nchw[inputPixels * 2 - 1], 0.0001f);
        assertEquals(8 / 255.0f, nchw[inputPixels * 3 - 1], 0.0001f);
    }

    @Test
    public void nchwToRgba_clampsChannelsAndUpscalesAlphaNearest() {
        int inputPixels = INPUT_SIDE * INPUT_SIDE;
        int outputPixels = OUTPUT_SIDE * OUTPUT_SIDE;
        byte[] inputRgba = new byte[inputPixels * 4];
        int inputX = 3;
        int inputY = 2;
        inputRgba[(inputY * INPUT_SIDE + inputX) * 4 + 3] = (byte) 77;
        float[] output = new float[outputPixels * 3];
        Arrays.fill(output, 0, outputPixels, 1.2f);
        Arrays.fill(output, outputPixels, outputPixels * 2, -0.2f);
        Arrays.fill(output, outputPixels * 2, outputPixels * 3, 0.5f);

        ByteBuffer rgba = QuickSrVideoEffect.nchwToRgba(output, inputRgba);

        assertEquals(outputPixels * 4, rgba.remaining());
        int outputX = inputX * 2 + 1;
        int outputY = inputY * 2 + 1;
        int base = (outputY * OUTPUT_SIDE + outputX) * 4;
        assertEquals(255, rgba.get(base) & 0xff);
        assertEquals(0, rgba.get(base + 1) & 0xff);
        assertEquals(128, rgba.get(base + 2) & 0xff);
        assertEquals(77, rgba.get(base + 3) & 0xff);
    }

    @Test
    public void packNchwToRgba_mapsAlphaForThreeXAndFourXOutputs() {
        for (int scale : new int[]{3, 4}) {
            int inputWidth = 4;
            int inputHeight = 2;
            int outputWidth = inputWidth * scale;
            int outputHeight = inputHeight * scale;
            byte[] inputRgba = new byte[inputWidth * inputHeight * 4];
            inputRgba[3] = (byte) 11;
            inputRgba[inputRgba.length - 1] = (byte) 99;
            float[] output = new float[outputWidth * outputHeight * 3];
            byte[] packed = new byte[outputWidth * outputHeight * 4];

            QuickSrVideoEffect.packNchwToRgba(
                    output,
                    inputRgba,
                    inputWidth,
                    inputHeight,
                    outputWidth,
                    outputHeight,
                    packed);

            assertEquals(11, packed[3] & 0xff);
            assertEquals(99, packed[packed.length - 1] & 0xff);
        }
    }

    @Test
    public void high256ConversionUsesFullInputAnd512Output() {
        int inputSide = QuickSrVideoEffect.Profile.HIGH_256.inputSide();
        int outputSide = QuickSrVideoEffect.Profile.HIGH_256.outputSide();
        int inputPixels = inputSide * inputSide;
        int outputPixels = outputSide * outputSide;
        byte[] inputRgba = new byte[inputPixels * 4];
        int inputX = inputSide - 1;
        int inputY = inputSide - 1;
        int inputBase = (inputY * inputSide + inputX) * 4;
        inputRgba[inputBase] = (byte) 11;
        inputRgba[inputBase + 1] = (byte) 22;
        inputRgba[inputBase + 2] = (byte) 33;
        inputRgba[inputBase + 3] = (byte) 44;

        float[] input = new float[inputPixels * 3];
        QuickSrVideoEffect.rgbaToNchw(inputRgba, input, inputSide);
        assertEquals(11 / 255.0f, input[inputPixels - 1], 0.0001f);
        assertEquals(22 / 255.0f, input[inputPixels * 2 - 1], 0.0001f);
        assertEquals(33 / 255.0f, input[inputPixels * 3 - 1], 0.0001f);

        float[] output = new float[outputPixels * 3];
        output[outputPixels - 1] = 1.0f;
        output[outputPixels * 2 - 1] = 0.25f;
        output[outputPixels * 3 - 1] = 0.0f;
        ByteBuffer rgba = QuickSrVideoEffect.nchwToRgba(
                output,
                inputRgba,
                inputSide,
                outputSide);

        assertEquals(outputPixels * 4, rgba.remaining());
        int outputBase = (outputPixels - 1) * 4;
        assertEquals(255, rgba.get(outputBase) & 0xff);
        assertEquals(64, rgba.get(outputBase + 1) & 0xff);
        assertEquals(0, rgba.get(outputBase + 2) & 0xff);
        assertEquals(44, rgba.get(outputBase + 3) & 0xff);
    }

    @Test
    public void realtimeRectangularConversionPreservesLastPixelAndAlpha() {
        int inputWidth = 256;
        int inputHeight = 144;
        int outputWidth = 512;
        int outputHeight = 288;
        int inputPixels = inputWidth * inputHeight;
        int outputPixels = outputWidth * outputHeight;
        byte[] inputRgba = new byte[inputPixels * 4];
        int inputBase = (inputPixels - 1) * 4;
        inputRgba[inputBase] = 12;
        inputRgba[inputBase + 1] = 34;
        inputRgba[inputBase + 2] = 56;
        inputRgba[inputBase + 3] = 78;

        float[] input = new float[inputPixels * 3];
        QuickSrVideoEffect.rgbaToNchw(inputRgba, input, inputWidth, inputHeight);
        assertEquals(12 / 255.0f, input[inputPixels - 1], 0.0001f);
        assertEquals(34 / 255.0f, input[inputPixels * 2 - 1], 0.0001f);
        assertEquals(56 / 255.0f, input[inputPixels * 3 - 1], 0.0001f);

        float[] output = new float[outputPixels * 3];
        output[outputPixels - 1] = 1.0f;
        output[outputPixels * 2 - 1] = 0.5f;
        output[outputPixels * 3 - 1] = 0.25f;
        ByteBuffer rgba = QuickSrVideoEffect.nchwToRgba(
                output,
                inputRgba,
                inputWidth,
                inputHeight,
                outputWidth,
                outputHeight);

        int outputBase = (outputPixels - 1) * 4;
        assertEquals(255, rgba.get(outputBase) & 0xff);
        assertEquals(128, rgba.get(outputBase + 1) & 0xff);
        assertEquals(64, rgba.get(outputBase + 2) & 0xff);
        assertEquals(78, rgba.get(outputBase + 3) & 0xff);
    }

    @Test
    public void packedConversionPreservesClampingRoundingAndEveryNearestAlpha() {
        int inputSide = 2;
        int outputSide = 4;
        int inputPixels = inputSide * inputSide;
        int outputPixels = outputSide * outputSide;
        byte[] inputRgba = new byte[inputPixels * 4];
        inputRgba[3] = 11;
        inputRgba[7] = 22;
        inputRgba[11] = 33;
        inputRgba[15] = 44;
        float[] boundaryValues = new float[]{
                Float.NaN,
                Float.NEGATIVE_INFINITY,
                -0.1f,
                0.0f,
                0.001f,
                0.25f,
                0.5f,
                0.501f,
                0.75f,
                0.999f,
                1.0f,
                1.1f,
                Float.POSITIVE_INFINITY
        };
        float[] output = new float[outputPixels * 3];
        for (int i = 0; i < output.length; i++) {
            output[i] = boundaryValues[i % boundaryValues.length];
        }
        byte[] packed = new byte[outputPixels * 4];

        QuickSrVideoEffect.packNchwToRgba(
                output,
                inputRgba,
                inputSide,
                outputSide,
                packed);

        for (int y = 0; y < outputSide; y++) {
            for (int x = 0; x < outputSide; x++) {
                int pixel = y * outputSide + x;
                int rgbaBase = pixel * 4;
                int inputPixel = (y / 2) * inputSide + x / 2;
                assertEquals(referenceNormalizedToByte(output[pixel]), packed[rgbaBase] & 0xff);
                assertEquals(
                        referenceNormalizedToByte(output[outputPixels + pixel]),
                        packed[rgbaBase + 1] & 0xff);
                assertEquals(
                        referenceNormalizedToByte(output[outputPixels * 2 + pixel]),
                        packed[rgbaBase + 2] & 0xff);
                assertEquals(
                        inputRgba[inputPixel * 4 + 3] & 0xff,
                        packed[rgbaBase + 3] & 0xff);
            }
        }
    }

    @Test
    public void ultra512ConversionUsesFullInputAnd1024Output() {
        int inputSide = QuickSrVideoEffect.Profile.ULTRA_512.inputSide();
        int outputSide = QuickSrVideoEffect.Profile.ULTRA_512.outputSide();
        int inputPixels = inputSide * inputSide;
        int outputPixels = outputSide * outputSide;
        byte[] inputRgba = new byte[inputPixels * 4];
        int inputBase = (inputPixels - 1) * 4;
        inputRgba[inputBase + 3] = (byte) 93;
        float[] output = new float[outputPixels * 3];
        output[outputPixels - 1] = 0.25f;
        output[outputPixels * 2 - 1] = 0.5f;
        output[outputPixels * 3 - 1] = 1.0f;

        ByteBuffer rgba = QuickSrVideoEffect.nchwToRgba(
                output,
                inputRgba,
                inputSide,
                outputSide);

        assertEquals(outputPixels * 4, rgba.remaining());
        int outputBase = (outputPixels - 1) * 4;
        assertEquals(64, rgba.get(outputBase) & 0xff);
        assertEquals(128, rgba.get(outputBase + 1) & 0xff);
        assertEquals(255, rgba.get(outputBase + 2) & 0xff);
        assertEquals(93, rgba.get(outputBase + 3) & 0xff);
    }

    @Test
    public void frameResultRecycleIsIdempotent() {
        AtomicInteger recycleCount = new AtomicInteger();
        QuickSrVideoEffect.FrameResult result = new QuickSrVideoEffect.FrameResult(
                ByteBuffer.allocateDirect(16),
                ignored -> recycleCount.incrementAndGet());

        assertFalse(result.isRecycled());
        result.recycle();
        result.recycle();

        assertTrue(result.isRecycled());
        assertEquals(1, recycleCount.get());
    }

    @Test(timeout = 5_000L)
    public void releaseInterruptsInferenceWorkerWaitingForQnnLock() throws Exception {
        Class<?> processorClass = null;
        for (Class<?> nested : QuickSrVideoEffect.class.getDeclaredClasses()) {
            if (nested.getSimpleName().equals("ProcessorImpl")) {
                processorClass = nested;
                break;
            }
        }
        assertTrue(processorClass != null);
        Constructor<?> constructor = processorClass.getDeclaredConstructor(
                Context.class,
                QuickSrSession.Mode.class,
                QuickSrVideoEffect.Profile.class,
                QuickSrVideoEffect.StatsListener.class);
        constructor.setAccessible(true);
        Object processor = constructor.newInstance(
                null,
                QuickSrSession.Mode.QNN_HTP,
                QuickSrVideoEffect.Profile.FAST_64,
                null);
        Field executorField = processorClass.getDeclaredField("inferenceExecutor");
        executorField.setAccessible(true);
        ExecutorService executor = (ExecutorService) executorField.get(processor);
        Method acquireMethod = processorClass.getDeclaredMethod("acquireQnnProcessLock");
        acquireMethod.setAccessible(true);
        Method releaseMethod = processorClass.getDeclaredMethod("release");
        releaseMethod.setAccessible(true);
        Field lifecycleLockField = processorClass.getDeclaredField("lifecycleLock");
        lifecycleLockField.setAccessible(true);
        Object lifecycleLock = lifecycleLockField.get(processor);
        Field waitingField = processorClass.getDeclaredField("waitingForQnnLock");
        waitingField.setAccessible(true);

        QnnPluginRuntime.lockProcess();
        try {
            Future<Throwable> waitOutcome = executor.submit(() -> {
                try {
                    acquireMethod.invoke(processor);
                    return null;
                } catch (InvocationTargetException failure) {
                    return failure.getCause();
                }
            });
            long waitDeadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            boolean waiting = false;
            while (System.nanoTime() < waitDeadlineNs) {
                synchronized (lifecycleLock) {
                    waiting = waitingField.getBoolean(processor);
                }
                if (waiting) {
                    break;
                }
                Thread.yield();
            }
            assertTrue("processor never entered the QNN lock wait", waiting);

            long releaseStartedNs = System.nanoTime();
            releaseMethod.invoke(processor);
            long releaseElapsedMs = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - releaseStartedNs);

            assertTrue("release remained blocked for " + releaseElapsedMs + " ms",
                    releaseElapsedMs < 2_000L);
            assertTrue(waitOutcome.get(1, TimeUnit.SECONDS) instanceof InterruptedException);
        } finally {
            QnnPluginRuntime.unlockProcess();
            executor.shutdownNow();
        }
    }

    @Test
    public void frameStatsReportProfileAndPipelineTiming() {
        QuickSrVideoEffect.FrameStats stats = new QuickSrVideoEffect.FrameStats(
                QuickSrSession.Mode.QNN_HTP,
                QuickSrVideoEffect.Profile.HIGH_256,
                7,
                1920,
                1080,
                1,
                2,
                3,
                600,
                41,
                5,
                52,
                123_456L);

        assertSame(QuickSrVideoEffect.Profile.HIGH_256, stats.profile);
        assertEquals(256, stats.modelInputSide);
        assertEquals(512, stats.modelOutputSide);
        assertEquals(1920, stats.effectInputWidth);
        assertEquals(1080, stats.effectInputHeight);
        assertEquals(600, stats.sessionSetupMs);
        assertEquals(41, stats.inferenceMs);
        assertEquals(52, stats.totalProcessingMs);
    }

    @Test
    public void processorReusesReleasedInputRgbaBuffer() throws Exception {
        Class<?> processorClass = Arrays.stream(QuickSrVideoEffect.class.getDeclaredClasses())
                .filter(item -> item.getSimpleName().equals("ProcessorImpl"))
                .findFirst()
                .orElseThrow();
        Constructor<?> constructor = processorClass.getDeclaredConstructor(
                Context.class,
                QuickSrSession.Mode.class,
                QuickSrVideoEffect.Profile.class,
                QuickSrVideoEffect.StatsListener.class);
        constructor.setAccessible(true);
        Object processor = constructor.newInstance(
                null,
                QuickSrSession.Mode.CPU,
                QuickSrVideoEffect.Profile.FAST_64,
                null);
        Method acquire = processorClass.getDeclaredMethod("acquireInputBuffer", int.class);
        Method recycle = processorClass.getDeclaredMethod("recycleInputBuffer", byte[].class);
        Method release = processorClass.getDeclaredMethod("release");
        acquire.setAccessible(true);
        recycle.setAccessible(true);
        release.setAccessible(true);
        int bytes = INPUT_SIDE * INPUT_SIDE * 4;

        byte[] first = (byte[]) acquire.invoke(processor, bytes);
        recycle.invoke(processor, (Object) first);
        byte[] second = (byte[]) acquire.invoke(processor, bytes);

        assertSame(first, second);
        release.invoke(processor);
    }

    private static int referenceNormalizedToByte(float value) {
        float clamped = Math.max(0.0f, Math.min(1.0f, value));
        return Math.max(0, Math.min(255, Math.round(clamped * 255.0f)));
    }
}
