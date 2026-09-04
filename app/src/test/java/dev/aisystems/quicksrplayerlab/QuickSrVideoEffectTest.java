package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
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
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

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
    public void crc32IdentityIsStableAndChangesWithFrameBytes() {
        assertEquals("b63cfbcd", QuickSrVideoEffect.crc32Hex(new byte[]{1, 2, 3, 4}));
        assertFalse(QuickSrVideoEffect.crc32Hex(new byte[]{1, 2, 3, 4})
                .equals(QuickSrVideoEffect.crc32Hex(new byte[]{1, 2, 3, 5})));
    }

    @Test
    public void directCadenceCacheCopyPreservesCallerPositionAndReusesOwnedArray() {
        ByteBuffer source = ByteBuffer.allocateDirect(8);
        source.put(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        source.flip();
        source.position(3);
        byte[] existing = new byte[8];

        byte[] copied = QuickSrVideoEffect.copyIntoCadenceCache(source, existing);

        assertSame(existing, copied);
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, copied);
        assertEquals(3, source.position());
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
        Field cleanupCountField = processorClass.getDeclaredField("workerCleanupRunCount");
        cleanupCountField.setAccessible(true);
        Field cleanupCompletedField = processorClass.getDeclaredField("workerCleanupCompleted");
        cleanupCompletedField.setAccessible(true);

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
            assertEquals(1, ((AtomicInteger) cleanupCountField.get(processor)).get());
            assertTrue(cleanupCompletedField.getBoolean(processor));
        } finally {
            QnnPluginRuntime.unlockProcess();
            executor.shutdownNow();
        }
    }

    @Test(timeout = 10_000L)
    public void releaseQueuesWorkerCleanupBehindFullQueueWithoutDiscardingLeases()
            throws Exception {
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
                QuickSrSession.Mode.QNN_HTP,
                QuickSrVideoEffect.Profile.FAST_64,
                null);

        Field executorField = processorClass.getDeclaredField("inferenceExecutor");
        executorField.setAccessible(true);
        ThreadPoolExecutor executor = (ThreadPoolExecutor) executorField.get(processor);
        Method acquireMethod = processorClass.getDeclaredMethod("acquireQnnProcessLock");
        acquireMethod.setAccessible(true);
        Method releaseMethod = processorClass.getDeclaredMethod("release");
        releaseMethod.setAccessible(true);
        Field cleanupQueuedField = processorClass.getDeclaredField("workerCleanupQueued");
        cleanupQueuedField.setAccessible(true);
        Field cleanupCountField = processorClass.getDeclaredField("workerCleanupRunCount");
        cleanupCountField.setAccessible(true);
        Field cleanupCompletedField = processorClass.getDeclaredField("workerCleanupCompleted");
        cleanupCompletedField.setAccessible(true);
        Field releaseTimeoutField = processorClass.getDeclaredField("releaseTimeoutMs");
        releaseTimeoutField.setAccessible(true);
        releaseTimeoutField.setLong(processor, 150L);
        Field outstandingField = processorClass.getDeclaredField("outstandingFrameResults");
        outstandingField.setAccessible(true);
        Field outputLockField = processorClass.getDeclaredField("outputBufferPoolLock");
        outputLockField.setAccessible(true);

        CountDownLatch activeHasQnnLock = new CountDownLatch(1);
        CountDownLatch allowActiveToFinish = new CountDownLatch(1);
        AtomicInteger taskTerminalCount = new AtomicInteger();
        AtomicInteger bufferRecycleCount = new AtomicInteger();
        AtomicInteger resultRecycleCount = new AtomicInteger();
        AtomicBoolean activeTerminated = new AtomicBoolean();
        AtomicBoolean firstQueuedTerminated = new AtomicBoolean();
        AtomicBoolean secondQueuedTerminated = new AtomicBoolean();

        Future<?> active = executor.submit(() -> {
            try {
                acquireMethod.invoke(processor);
                activeHasQnnLock.countDown();
                boolean allowedToFinish = false;
                while (!allowedToFinish) {
                    try {
                        allowedToFinish = allowActiveToFinish.await(50, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignored) {
                        // Model a native inference that does not return when release interrupts it.
                    }
                }
                assertTrue(activeTerminated.compareAndSet(false, true));
                taskTerminalCount.incrementAndGet();
                bufferRecycleCount.incrementAndGet();
            } catch (InvocationTargetException failure) {
                throw new RuntimeException(failure.getCause());
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        });
        assertTrue("active worker never acquired the QNN process lock",
                activeHasQnnLock.await(2, TimeUnit.SECONDS));

        Runnable firstQueued = () -> {
            assertTrue(firstQueuedTerminated.compareAndSet(false, true));
            taskTerminalCount.incrementAndGet();
            bufferRecycleCount.incrementAndGet();
        };
        Runnable secondQueued = () -> {
            assertTrue(secondQueuedTerminated.compareAndSet(false, true));
            taskTerminalCount.incrementAndGet();
            bufferRecycleCount.incrementAndGet();
        };
        Future<?> firstQueuedFuture = executor.submit(firstQueued);
        Future<?> secondQueuedFuture = executor.submit(secondQueued);
        assertEquals(VideoPipelineTelemetry.WORKER_QUEUE_CAPACITY, executor.getQueue().size());

        QuickSrVideoEffect.FrameResult outstanding = new QuickSrVideoEffect.FrameResult(
                ByteBuffer.allocateDirect(16),
                ignored -> resultRecycleCount.incrementAndGet());
        @SuppressWarnings("unchecked")
        Set<QuickSrVideoEffect.FrameResult> outstandingResults =
                (Set<QuickSrVideoEffect.FrameResult>) outstandingField.get(processor);
        Object outputLock = outputLockField.get(processor);
        synchronized (outputLock) {
            outstandingResults.add(outstanding);
        }

        FutureTask<Throwable> releaseOutcome = new FutureTask<>(() -> {
            try {
                releaseMethod.invoke(processor);
                return null;
            } catch (InvocationTargetException failure) {
                return failure.getCause();
            }
        });
        Thread releaseThread = new Thread(releaseOutcome, "QuickSR-release-full-queue-test");
        long releaseStartedNs = System.nanoTime();
        releaseThread.start();
        long enqueueDeadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!cleanupQueuedField.getBoolean(processor)
                && System.nanoTime() < enqueueDeadlineNs) {
            Thread.yield();
        }
        assertTrue("worker cleanup was not queued in its reserved slot",
                cleanupQueuedField.getBoolean(processor));
        assertEquals(
                VideoPipelineTelemetry.WORKER_QUEUE_CAPACITY
                        + VideoPipelineTelemetry.WORKER_CLEANUP_RESERVED_SLOTS,
                executor.getQueue().size());
        Throwable releaseFailure = releaseOutcome.get(2, TimeUnit.SECONDS);
        long releaseElapsedMs = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - releaseStartedNs);
        assertTrue("release did not report its bounded timeout: " + releaseFailure,
                releaseFailure instanceof androidx.media3.common.VideoFrameProcessingException
                        && releaseFailure.getCause() instanceof java.util.concurrent.TimeoutException);
        assertTrue("queue-full release exceeded its test timeout bound: " + releaseElapsedMs,
                releaseElapsedMs < 1_500L);
        assertFalse("cleanup ran while the active native task was still blocked",
                cleanupCompletedField.getBoolean(processor));

        allowActiveToFinish.countDown();
        active.get(1, TimeUnit.SECONDS);
        firstQueuedFuture.get(1, TimeUnit.SECONDS);
        secondQueuedFuture.get(1, TimeUnit.SECONDS);

        assertEquals(3, taskTerminalCount.get());
        assertEquals(3, bufferRecycleCount.get());
        assertEquals(1, resultRecycleCount.get());
        outstanding.recycle();
        assertEquals("FrameResult was recycled more than once", 1, resultRecycleCount.get());
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        assertEquals(1, ((AtomicInteger) cleanupCountField.get(processor)).get());
        assertTrue(cleanupCompletedField.getBoolean(processor));

        ExecutorService lockProbe = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> qnnLockReleased = lockProbe.submit(() -> {
                QnnPluginRuntime.lockProcess();
                try {
                    return true;
                } finally {
                    QnnPluginRuntime.unlockProcess();
                }
            });
            assertTrue("worker cleanup did not release the QNN process lock",
                    qnnLockReleased.get(1, TimeUnit.SECONDS));
        } finally {
            lockProbe.shutdownNow();
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
    public void overlapMemoryBoundIsExactlyOneAdditionalOutputTensor() {
        assertEquals(0, QuickSrVideoEffect.postprocessQueueCapacity(false));
        assertEquals(1, QuickSrVideoEffect.postprocessQueueCapacity(true));
        assertEquals(1, QuickSrVideoEffect.outputTensorSlotCount(false));
        assertEquals(2, QuickSrVideoEffect.outputTensorSlotCount(true));
        assertEquals(
                11_059_200L,
                QuickSrVideoEffect.outputTensorBytesPerSlot(
                        QuickSrVideoEffect.Profile.FULL_720P));
        assertEquals(
                24_883_200L,
                QuickSrVideoEffect.additionalOverlapTensorBytes(
                        QuickSrVideoEffect.Profile.FULL_1080P_3X,
                        true));
    }

    @Test
    public void cadenceReuseWaitsForTheExactCompletedInferenceReference() {
        assertFalse(QuickSrVideoEffect.cadenceCacheMatches(0, 0L, 1L, 0, 0L, 2L));
        assertTrue(QuickSrVideoEffect.cadenceCacheMatches(0, 0L, 2L, 0, 0L, 2L));
        assertFalse(QuickSrVideoEffect.cadenceCacheMatches(0, 0L, 2L, 1, 0L, 2L));
        assertFalse(QuickSrVideoEffect.cadenceCacheMatches(0, 0L, 2L, 0, 1L, 2L));
    }

    @Test
    public void cadencePixelCacheIsAllocatedOnceAndOverwrittenInPlace() {
        byte[] firstSource = {1, 2, 3, 4};
        byte[] cache = QuickSrVideoEffect.copyIntoCadenceCache(firstSource, null);
        byte[] secondSource = {5, 6, 7, 8};
        byte[] reused = QuickSrVideoEffect.copyIntoCadenceCache(secondSource, cache);

        assertSame(cache, reused);
        assertTrue(Arrays.equals(secondSource, reused));
    }

    @Test
    public void flushAndReleaseClearCadenceCacheAndReferenceIdentity() throws Exception {
        Class<?> processorClass = Arrays.stream(QuickSrVideoEffect.class.getDeclaredClasses())
                .filter(item -> item.getSimpleName().equals("ProcessorImpl"))
                .findFirst()
                .orElseThrow();
        Constructor<?> processorConstructor = processorClass.getDeclaredConstructor(
                Context.class,
                QuickSrSession.Mode.class,
                QuickSrVideoEffect.Profile.class,
                QuickSrVideoEffect.StatsListener.class);
        processorConstructor.setAccessible(true);
        Object processor = processorConstructor.newInstance(
                null,
                QuickSrSession.Mode.CPU,
                QuickSrVideoEffect.Profile.FAST_64,
                null);
        Class<?> cacheClass = Arrays.stream(processorClass.getDeclaredClasses())
                .filter(item -> item.getSimpleName().equals("CachedSrOutput"))
                .findFirst()
                .orElseThrow();
        Constructor<?> cacheConstructor = cacheClass.getDeclaredConstructor(
                byte[].class, int.class, long.class, long.class, String.class);
        cacheConstructor.setAccessible(true);
        Field cacheField = processorClass.getDeclaredField("cachedSrOutput");
        Field cachePixelsField = processorClass.getDeclaredField("cadenceCachePixels");
        Field latestGeneration = processorClass.getDeclaredField("latestInferredGeneration");
        Field latestStreamEpoch = processorClass.getDeclaredField("latestInferredStreamEpoch");
        Field latestFrame = processorClass.getDeclaredField("latestInferredFrameId");
        cacheField.setAccessible(true);
        cachePixelsField.setAccessible(true);
        latestGeneration.setAccessible(true);
        latestStreamEpoch.setAccessible(true);
        latestFrame.setAccessible(true);
        Method flush = processorClass.getDeclaredMethod("advanceGenerationForFlush");
        flush.setAccessible(true);
        Method release = processorClass.getDeclaredMethod("release");
        release.setAccessible(true);

        Object cache = cacheConstructor.newInstance(new byte[16], 0, 0L, 7L, "crc");
        cacheField.set(processor, cache);
        cachePixelsField.set(processor, new byte[16]);
        latestGeneration.setInt(processor, 0);
        latestStreamEpoch.setLong(processor, 0L);
        latestFrame.setLong(processor, 7L);
        flush.invoke(processor);

        assertEquals(null, cacheField.get(processor));
        assertEquals(null, cachePixelsField.get(processor));
        assertEquals(Integer.MIN_VALUE, latestGeneration.getInt(processor));
        assertEquals(-1L, latestStreamEpoch.getLong(processor));
        assertEquals(-1L, latestFrame.getLong(processor));

        cacheField.set(processor, cacheConstructor.newInstance(
                new byte[16], 1, 1L, 8L, "crc2"));
        cachePixelsField.set(processor, new byte[16]);
        release.invoke(processor);
        assertEquals(null, cacheField.get(processor));
        assertEquals(null, cachePixelsField.get(processor));
        release.invoke(processor);
        assertEquals(null, cacheField.get(processor));
    }

    @Test
    public void serialInputStreamBoundaryForcesFirstFrameToProcessWithoutFlush() throws Exception {
        assertInputStreamBoundaryIsolation(false);
    }

    @Test
    public void overlapInputStreamBoundaryForcesFirstFrameToProcessWithoutFlush() throws Exception {
        assertInputStreamBoundaryIsolation(true);
    }

    @Test
    public void overlapTensorAllocationFailureReturnsSemaphorePermit() throws Exception {
        Object processor = newOverlapProcessor();
        Class<?> processorClass = processor.getClass();
        Method acquire = processorClass.getDeclaredMethod("acquireOverlapOutputTensor", int.class);
        acquire.setAccessible(true);
        Field slotsField = processorClass.getDeclaredField("outputTensorSlots");
        slotsField.setAccessible(true);
        Semaphore slots = (Semaphore) slotsField.get(processor);

        try {
            acquire.invoke(processor, -1);
        } catch (InvocationTargetException failure) {
            assertTrue(failure.getCause() instanceof NegativeArraySizeException);
        }
        assertEquals(QuickSrVideoEffect.OVERLAP_OUTPUT_TENSOR_SLOTS, slots.availablePermits());
        processorClass.getDeclaredMethod("release").invoke(processor);
    }

    @Test
    public void fullPostprocessQueueDoesNotHoldLifecycleLockDuringRelease() throws Exception {
        Object processor = newOverlapProcessor();
        Class<?> processorClass = processor.getClass();
        Method submit = processorClass.getDeclaredMethod("submitPostprocessTask", Runnable.class);
        submit.setAccessible(true);
        Method release = processorClass.getDeclaredMethod("release");
        release.setAccessible(true);
        Field releasedField = processorClass.getDeclaredField("released");
        releasedField.setAccessible(true);
        Field timeoutField = processorClass.getDeclaredField("releaseTimeoutMs");
        timeoutField.setAccessible(true);
        timeoutField.setLong(processor, 1_000L);

        CountDownLatch activeStarted = new CountDownLatch(1);
        CountDownLatch allowActiveToFinish = new CountDownLatch(1);
        Runnable blocker = () -> {
            activeStarted.countDown();
            try {
                allowActiveToFinish.await();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
        };
        submit.invoke(processor, blocker);
        assertTrue(activeStarted.await(1, TimeUnit.SECONDS));
        submit.invoke(processor, (Runnable) () -> { });

        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<?> blockedSubmit = callers.submit(() -> {
                try {
                    submit.invoke(processor, (Runnable) () -> { });
                } catch (IllegalAccessException | InvocationTargetException expected) {
                    // Release wins the submit/shutdown race.
                }
            });
            Thread.sleep(150L);
            assertFalse(blockedSubmit.isDone());
            Future<?> releaseCall = callers.submit(() -> {
                try {
                    release.invoke(processor);
                } catch (IllegalAccessException | InvocationTargetException failure) {
                    throw new RuntimeException(failure);
                }
            });
            blockedSubmit.get(1, TimeUnit.SECONDS);
            assertTrue(releasedField.getBoolean(processor));
            allowActiveToFinish.countDown();
            releaseCall.get(1, TimeUnit.SECONDS);
        } finally {
            allowActiveToFinish.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    public void releaseKeepsOutputScratchUntilActivePostprocessStops() throws Exception {
        Object processor = newOverlapProcessor();
        Class<?> processorClass = processor.getClass();
        Method submit = processorClass.getDeclaredMethod("submitPostprocessTask", Runnable.class);
        submit.setAccessible(true);
        Method release = processorClass.getDeclaredMethod("release");
        release.setAccessible(true);
        Field scratchField = processorClass.getDeclaredField("outputRgbaScratch");
        scratchField.setAccessible(true);
        byte[] scratch = new byte[32];
        scratchField.set(processor, scratch);

        CountDownLatch activeStarted = new CountDownLatch(1);
        CountDownLatch allowActiveToFinish = new CountDownLatch(1);
        submit.invoke(processor, (Runnable) () -> {
            activeStarted.countDown();
            try {
                allowActiveToFinish.await();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(activeStarted.await(1, TimeUnit.SECONDS));

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> releaseCall = caller.submit(() -> {
                try {
                    release.invoke(processor);
                } catch (IllegalAccessException | InvocationTargetException failure) {
                    throw new RuntimeException(failure);
                }
            });
            Thread.sleep(150L);
            assertFalse(releaseCall.isDone());
            assertSame(scratch, scratchField.get(processor));
            allowActiveToFinish.countDown();
            releaseCall.get(1, TimeUnit.SECONDS);
            assertSame(null, scratchField.get(processor));
        } finally {
            allowActiveToFinish.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    public void rejectedHandlerRemovesTaskOfferedAfterExecutorShutdown() throws Exception {
        Object processor = newOverlapProcessor();
        Field executorField = processor.getClass().getDeclaredField("postprocessExecutor");
        executorField.setAccessible(true);
        ThreadPoolExecutor processorExecutor = (ThreadPoolExecutor) executorField.get(processor);
        RejectedExecutionHandler handler = processorExecutor.getRejectedExecutionHandler();
        ThreadPoolExecutor stoppedTarget = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1));
        stoppedTarget.shutdown();
        Runnable task = () -> { };

        boolean rejected = false;
        try {
            handler.rejectedExecution(task, stoppedTarget);
        } catch (RejectedExecutionException expected) {
            rejected = true;
        }
        assertTrue(rejected);
        assertTrue(stoppedTarget.getQueue().isEmpty());
        processor.getClass().getDeclaredMethod("release").invoke(processor);
    }

    private static Object newOverlapProcessor() throws Exception {
        Class<?> processorClass = Arrays.stream(QuickSrVideoEffect.class.getDeclaredClasses())
                .filter(item -> item.getSimpleName().equals("ProcessorImpl"))
                .findFirst()
                .orElseThrow();
        Constructor<?> constructor = processorClass.getDeclaredConstructor(
                Context.class,
                QuickSrSession.Mode.class,
                QuickSrVideoEffect.Profile.class,
                QuickSrSession.Tuning.class,
                String.class,
                VideoEvidenceStore.CaptureSpec.class,
                boolean.class,
                QuickSrVideoEffect.StatsListener.class,
                LongSupplier.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                null,
                QuickSrSession.Mode.CPU,
                QuickSrVideoEffect.Profile.FAST_64,
                QuickSrSession.Tuning.BASELINE,
                null,
                VideoEvidenceStore.CaptureSpec.none(),
                true,
                null,
                (LongSupplier) System::nanoTime);
    }

    private static void assertInputStreamBoundaryIsolation(boolean overlap) throws Exception {
        Class<?> processorClass = Arrays.stream(QuickSrVideoEffect.class.getDeclaredClasses())
                .filter(item -> item.getSimpleName().equals("ProcessorImpl"))
                .findFirst()
                .orElseThrow();
        Constructor<?> constructor = processorClass.getDeclaredConstructor(
                Context.class,
                QuickSrSession.Mode.class,
                QuickSrVideoEffect.Profile.class,
                QuickSrSession.Tuning.class,
                String.class,
                VideoEvidenceStore.CaptureSpec.class,
                boolean.class,
                AnimeCadenceAnalyzer.Mode.class,
                QuickSrVideoEffect.StatsListener.class,
                LongSupplier.class);
        constructor.setAccessible(true);
        Object processor = constructor.newInstance(
                null,
                QuickSrSession.Mode.CPU,
                QuickSrVideoEffect.Profile.FAST_64,
                QuickSrSession.Tuning.BASELINE,
                null,
                VideoEvidenceStore.CaptureSpec.none(),
                overlap,
                AnimeCadenceAnalyzer.Mode.CONTENT_AWARE_V1,
                null,
                (LongSupplier) System::nanoTime);
        Field analyzerField = processorClass.getDeclaredField("cadenceAnalyzer");
        analyzerField.setAccessible(true);
        AnimeCadenceAnalyzer analyzer = (AnimeCadenceAnalyzer) analyzerField.get(processor);
        byte[] rgba = new byte[INPUT_SIDE * INPUT_SIDE * 4];
        AnimeCadenceAnalyzer.Result first = analyzer.analyze(
                AnimeCadenceAnalyzer.Mode.CONTENT_AWARE_V1,
                rgba,
                INPUT_SIDE,
                INPUT_SIDE,
                "same",
                0L);
        analyzer.markInferenceAvailable(0L);
        AnimeCadenceAnalyzer.Result held = analyzer.analyze(
                AnimeCadenceAnalyzer.Mode.CONTENT_AWARE_V1,
                rgba,
                INPUT_SIDE,
                INPUT_SIDE,
                "same",
                0L);
        assertEquals(AnimeCadenceAnalyzer.Decision.PROCESS, first.decision);
        assertEquals(AnimeCadenceAnalyzer.Decision.REUSE, held.decision);

        Class<?> cacheClass = Arrays.stream(processorClass.getDeclaredClasses())
                .filter(item -> item.getSimpleName().equals("CachedSrOutput"))
                .findFirst()
                .orElseThrow();
        Constructor<?> cacheConstructor = cacheClass.getDeclaredConstructor(
                byte[].class, int.class, long.class, long.class, String.class);
        cacheConstructor.setAccessible(true);
        Field cacheField = processorClass.getDeclaredField("cachedSrOutput");
        Field cachePixelsField = processorClass.getDeclaredField("cadenceCachePixels");
        Field latestGeneration = processorClass.getDeclaredField("latestInferredGeneration");
        Field latestStreamEpoch = processorClass.getDeclaredField("latestInferredStreamEpoch");
        Field latestFrame = processorClass.getDeclaredField("latestInferredFrameId");
        cacheField.setAccessible(true);
        cachePixelsField.setAccessible(true);
        latestGeneration.setAccessible(true);
        latestStreamEpoch.setAccessible(true);
        latestFrame.setAccessible(true);
        byte[] cachedPixels = new byte[16];
        cacheField.set(processor, cacheConstructor.newInstance(
                cachedPixels, 0, 0L, 1L, "cached"));
        cachePixelsField.set(processor, cachedPixels);
        latestGeneration.setInt(processor, 0);
        latestStreamEpoch.setLong(processor, 0L);
        latestFrame.setLong(processor, 1L);

        Method boundary = processorClass.getDeclaredMethod("advanceCadenceStreamBoundary");
        boundary.setAccessible(true);
        boundary.invoke(processor);

        Field epochField = processorClass.getDeclaredField("cadenceStreamEpoch");
        epochField.setAccessible(true);
        long nextEpoch = epochField.getLong(processor);
        AnimeCadenceAnalyzer.Result nextStreamFirst = analyzer.analyze(
                AnimeCadenceAnalyzer.Mode.CONTENT_AWARE_V1,
                rgba,
                INPUT_SIDE,
                INPUT_SIDE,
                "same",
                nextEpoch);
        Field telemetryField = processorClass.getDeclaredField("pipelineTelemetry");
        telemetryField.setAccessible(true);
        VideoPipelineTelemetry.Snapshot snapshot = ((VideoPipelineTelemetry) telemetryField.get(
                processor)).snapshot(System.nanoTime(), 0);

        assertEquals(1L, nextEpoch);
        assertEquals(AnimeCadenceAnalyzer.Decision.PROCESS, nextStreamFirst.decision);
        assertEquals(AnimeCadenceAnalyzer.Reason.GENERATION_START, nextStreamFirst.reason);
        assertEquals(0, snapshot.generation);
        assertEquals(0L, snapshot.flushCount);
        assertEquals(null, cacheField.get(processor));
        assertEquals(null, cachePixelsField.get(processor));
        assertEquals(Integer.MIN_VALUE, latestGeneration.getInt(processor));
        assertEquals(-1L, latestStreamEpoch.getLong(processor));
        assertEquals(-1L, latestFrame.getLong(processor));
        processorClass.getDeclaredMethod("release").invoke(processor);
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
