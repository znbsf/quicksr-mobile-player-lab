package dev.aisystems.quicksrplayerlab;

import android.content.Context;
import android.opengl.GLES20;
import android.os.SystemClock;

import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlRect;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.ByteBufferGlEffect;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Experimental per-frame neural video effect with an explicit model-resolution profile.
 *
 * <p>Media3 scales each SDR effect input to the selected static model input. One QuickSRNet
 * inference produces an RGBA result and uploads it back to the GL pipeline. The activity places
 * a {@code Presentation} effect before this effect so the GL output texture already has the neural
 * output dimensions; this avoids shrinking a 720p neural result back into a genuine 360p source
 * texture. Profiles include square compatibility experiments and aspect-preserving 16:9 paths up
 * through true 1080p and 1440p neural textures. A separate 4K display fallback scales a 1080p
 * neural texture into a 4K effect canvas; it is not native 4K neural inference. {@link
 * Profile#FAST_64} remains a performance fallback. No profile is a tiled full-resolution path.
 */
@UnstableApi
final class QuickSrVideoEffect implements GlEffect {
    enum Profile {
        FAST_64("64x64 -> 128x128", ModelVariant.FIXED64_DCR_FULL, 64, 64, 128, 128),
        REALTIME_256X144(
                "实时 16:9 · 256x144 -> 512x288",
                ModelVariant.FIXED256X144_DCR_FULL,
                256,
                144,
                512,
                288),
        HIGH_256("256x256 -> 512x512", ModelVariant.FIXED256_DCR_FULL, 256, 256, 512, 512),
        HIGH_512X288(
                "高分 16:9 · 512x288 -> 1024x576",
                ModelVariant.FIXED512X288_DCR_FULL,
                512,
                288,
                1024,
                576),
        FULL_720P(
                "720p 神经输出 · 640x360 -> 1280x720",
                ModelVariant.FIXED640X360_DCR_FULL,
                640,
                360,
                1280,
                720),
        FULL_1080P_3X(
                "1080p 神经输出 · 640x360 -> 1920x1080",
                ModelVariant.FIXED640X360_3X_FULL,
                640,
                360,
                1920,
                1080),
        FULL_1440P_4X(
                "1440p 神经输出 · 640x360 -> 2560x1440",
                ModelVariant.FIXED640X360_4X_FULL,
                640,
                360,
                2560,
                1440),
        DISPLAY_4K_FROM_1080P_3X(
                "4K 显示保底 · 640x360 -> 神经1080p -> GPU 4K",
                ModelVariant.FIXED640X360_3X_FULL,
                640,
                360,
                1920,
                1080,
                3840,
                2160),
        ULTRA_512(
                "512x512 -> 1024x1024",
                ModelVariant.FIXED512_DCR_FULL,
                512,
                512,
                1024,
                1024);

        private final String label;
        private final ModelVariant modelVariant;
        private final int inputWidth;
        private final int inputHeight;
        private final int outputWidth;
        private final int outputHeight;
        private final int canvasWidth;
        private final int canvasHeight;

        Profile(
                String label,
                ModelVariant modelVariant,
                int inputWidth,
                int inputHeight,
                int outputWidth,
                int outputHeight) {
            this(
                    label,
                    modelVariant,
                    inputWidth,
                    inputHeight,
                    outputWidth,
                    outputHeight,
                    outputWidth,
                    outputHeight);
        }

        Profile(
                String label,
                ModelVariant modelVariant,
                int inputWidth,
                int inputHeight,
                int outputWidth,
                int outputHeight,
                int canvasWidth,
                int canvasHeight) {
            this.label = label;
            this.modelVariant = modelVariant;
            this.inputWidth = inputWidth;
            this.inputHeight = inputHeight;
            this.outputWidth = outputWidth;
            this.outputHeight = outputHeight;
            this.canvasWidth = canvasWidth;
            this.canvasHeight = canvasHeight;
        }

        ModelVariant modelVariant() {
            return modelVariant;
        }

        int inputSide() {
            if (inputWidth != inputHeight) {
                throw new IllegalStateException("Profile input is not square: " + this);
            }
            return inputWidth;
        }

        int outputSide() {
            if (outputWidth != outputHeight) {
                throw new IllegalStateException("Profile output is not square: " + this);
            }
            return outputWidth;
        }

        int inputWidth() {
            return inputWidth;
        }

        int inputHeight() {
            return inputHeight;
        }

        int outputWidth() {
            return outputWidth;
        }

        int outputHeight() {
            return outputHeight;
        }

        int canvasWidth() {
            return canvasWidth;
        }

        int canvasHeight() {
            return canvasHeight;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Legacy listener kept so the existing 64x64 call site remains source-compatible. */
    interface Listener {
        void onFrameProcessed(
                QuickSrSession.Mode mode,
                int frameNumber,
                long inferenceMs,
                long presentationTimeUs);
    }

    interface StatsListener {
        void onFrameProcessed(FrameStats stats);

        default void onQnnStrictEvidence(JSONObject qnnStrict) {
        }

        default void onEvidenceCaptured(JSONObject evidence) {
        }

        default void onProcessingError(String stage, Throwable failure) {
        }
    }

    static final class FrameStats {
        final QuickSrSession.Mode mode;
        final QuickSrSession.Tuning tuning;
        final Profile profile;
        final int frameNumber;
        final int effectInputWidth;
        final int effectInputHeight;
        final int modelInputSide;
        final int modelOutputSide;
        final int modelInputWidth;
        final int modelInputHeight;
        final int modelOutputWidth;
        final int modelOutputHeight;
        final long copyMs;
        final long queueMs;
        final long inputConversionMs;
        final long sessionSetupMs;
        final long inferenceMs;
        final long tensorInputCopyMs;
        final long ortRunMs;
        final long tensorOutputCopyMs;
        final long finiteScanMs;
        final boolean finiteScanExecuted;
        final long outputConversionMs;
        final long totalProcessingMs;
        final long presentationTimeUs;
        final long observedMonotonicNs;

        FrameStats(
                QuickSrSession.Mode mode,
                Profile profile,
                int frameNumber,
                int effectInputWidth,
                int effectInputHeight,
                long copyMs,
                long queueMs,
                long inputConversionMs,
                long sessionSetupMs,
                long inferenceMs,
                long outputConversionMs,
                long totalProcessingMs,
                long presentationTimeUs) {
            this(
                    mode,
                    QuickSrSession.Tuning.BASELINE,
                    profile,
                    frameNumber,
                    effectInputWidth,
                    effectInputHeight,
                    copyMs,
                    queueMs,
                    inputConversionMs,
                    sessionSetupMs,
                    inferenceMs,
                    0L,
                    inferenceMs,
                    0L,
                    0L,
                    false,
                    outputConversionMs,
                    totalProcessingMs,
                    presentationTimeUs);
        }

        FrameStats(
                QuickSrSession.Mode mode,
                QuickSrSession.Tuning tuning,
                Profile profile,
                int frameNumber,
                int effectInputWidth,
                int effectInputHeight,
                long copyMs,
                long queueMs,
                long inputConversionMs,
                long sessionSetupMs,
                long inferenceMs,
                long tensorInputCopyMs,
                long ortRunMs,
                long tensorOutputCopyMs,
                long finiteScanMs,
                boolean finiteScanExecuted,
                long outputConversionMs,
                long totalProcessingMs,
                long presentationTimeUs) {
            this.mode = mode;
            this.tuning = tuning;
            this.profile = profile;
            this.frameNumber = frameNumber;
            this.effectInputWidth = effectInputWidth;
            this.effectInputHeight = effectInputHeight;
            this.modelInputWidth = profile.inputWidth();
            this.modelInputHeight = profile.inputHeight();
            this.modelOutputWidth = profile.outputWidth();
            this.modelOutputHeight = profile.outputHeight();
            this.modelInputSide = modelInputWidth == modelInputHeight ? modelInputWidth : -1;
            this.modelOutputSide = modelOutputWidth == modelOutputHeight ? modelOutputWidth : -1;
            this.copyMs = copyMs;
            this.queueMs = queueMs;
            this.inputConversionMs = inputConversionMs;
            this.sessionSetupMs = sessionSetupMs;
            this.inferenceMs = inferenceMs;
            this.tensorInputCopyMs = tensorInputCopyMs;
            this.ortRunMs = ortRunMs;
            this.tensorOutputCopyMs = tensorOutputCopyMs;
            this.finiteScanMs = finiteScanMs;
            this.finiteScanExecuted = finiteScanExecuted;
            this.outputConversionMs = outputConversionMs;
            this.totalProcessingMs = totalProcessingMs;
            this.presentationTimeUs = presentationTimeUs;
            this.observedMonotonicNs = System.nanoTime();
        }
    }

    private static final Profile DEFAULT_PROFILE = Profile.FAST_64;
    private static final int RGBA_BYTES_PER_PIXEL = 4;
    private static final int MAX_POOLED_INPUT_BUFFERS = 8;
    private static final int MAX_POOLED_OUTPUT_BUFFERS = 3;
    private static final long RELEASE_TIMEOUT_SECONDS = 30L;
    private final ProcessorImpl processor;

    QuickSrVideoEffect(
            Context context,
            QuickSrSession.Mode mode,
            Listener listener) {
        this(
                context,
                mode,
                DEFAULT_PROFILE,
                stats -> {
                    if (listener != null) {
                        listener.onFrameProcessed(
                                stats.mode,
                                stats.frameNumber,
                                stats.inferenceMs,
                                stats.presentationTimeUs);
                    }
                });
    }

    QuickSrVideoEffect(
            Context context,
            QuickSrSession.Mode mode,
            Profile profile,
            StatsListener listener) {
        this(
                context,
                mode,
                profile,
                QuickSrSession.Tuning.BASELINE,
                null,
                VideoEvidenceStore.CaptureSpec.none(),
                listener);
    }

    QuickSrVideoEffect(
            Context context,
            QuickSrSession.Mode mode,
            Profile profile,
            QuickSrSession.Tuning tuning,
            StatsListener listener) {
        this(
                context,
                mode,
                profile,
                tuning,
                null,
                VideoEvidenceStore.CaptureSpec.none(),
                listener);
    }

    QuickSrVideoEffect(
            Context context,
            QuickSrSession.Mode mode,
            Profile profile,
            QuickSrSession.Tuning tuning,
            String benchmarkRunId,
            VideoEvidenceStore.CaptureSpec captureSpec,
            StatsListener listener) {
        processor = new ProcessorImpl(
                context.getApplicationContext(),
                mode,
                profile,
                tuning,
                benchmarkRunId,
                captureSpec,
                listener);
    }

    @Override
    public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr)
            throws VideoFrameProcessingException {
        GlShaderProgram delegate =
                new ByteBufferGlEffect<FrameResult>(processor)
                        .toGlShaderProgram(context, useHdr);
        return new ProcessorReleasingGlShaderProgram(delegate, processor);
    }

    static final class FrameResult {
        interface Recycler {
            void recycle(FrameResult result);
        }

        final ByteBuffer rgba;
        private final Recycler recycler;
        private final AtomicBoolean recycled = new AtomicBoolean();

        FrameResult(ByteBuffer rgba, Recycler recycler) {
            this.rgba = rgba;
            this.recycler = recycler;
        }

        void recycle() {
            if (recycled.compareAndSet(false, true)) {
                recycler.recycle(this);
            }
        }

        boolean isRecycled() {
            return recycled.get();
        }
    }

    /**
     * Media3 1.11.0 does not forward ByteBufferConcurrentEffect.release() to Processor.release().
     * Keep the pinned library's public contract by closing our ORT session after its shader has
     * cancelled frames and unmapped all pixel-buffer objects.
     */
    private static final class ProcessorReleasingGlShaderProgram implements GlShaderProgram {
        private final GlShaderProgram delegate;
        private final ProcessorImpl processor;

        ProcessorReleasingGlShaderProgram(
                GlShaderProgram delegate,
                ProcessorImpl processor) {
            this.delegate = delegate;
            this.processor = processor;
        }

        @Override
        public void setInputListener(InputListener inputListener) {
            delegate.setInputListener(inputListener);
        }

        @Override
        public void setOutputListener(OutputListener outputListener) {
            delegate.setOutputListener(outputListener);
        }

        @Override
        public void setErrorListener(Executor executor, ErrorListener errorListener) {
            delegate.setErrorListener(executor, errorListener);
        }

        @Override
        public void queueInputFrame(
                GlObjectsProvider glObjectsProvider,
                GlTextureInfo inputTexture,
                long presentationTimeUs) {
            delegate.queueInputFrame(glObjectsProvider, inputTexture, presentationTimeUs);
        }

        @Override
        public void releaseOutputFrame(GlTextureInfo outputTexture) {
            delegate.releaseOutputFrame(outputTexture);
        }

        @Override
        public void signalEndOfCurrentInputStream() {
            delegate.signalEndOfCurrentInputStream();
        }

        @Override
        public void flush() {
            try {
                delegate.flush();
            } finally {
                // QueuingGlShaderProgram drops completed futures during flush without invoking
                // finishProcessingAndBlend. Reclaim their direct buffers after the delegate has
                // removed every queued frame.
                processor.recycleOutstandingFrameResults();
            }
        }

        @Override
        public void release() throws VideoFrameProcessingException {
            Throwable failure = null;
            try {
                delegate.release();
            } catch (Throwable caught) {
                failure = caught;
            }
            try {
                processor.release();
            } catch (Throwable caught) {
                failure = append(failure, caught);
            }
            if (failure != null) {
                if (failure instanceof VideoFrameProcessingException) {
                    throw (VideoFrameProcessingException) failure;
                }
                throw new VideoFrameProcessingException(
                        "Unable to release the QuickSR video shader",
                        failure);
            }
        }
    }

    private static final class ProcessorImpl
            implements ByteBufferGlEffect.Processor<FrameResult> {
        private final Context context;
        private final QuickSrSession.Mode mode;
        private final Profile profile;
        private final QuickSrSession.Tuning tuning;
        private final String benchmarkRunId;
        private final VideoEvidenceStore.CaptureSpec captureSpec;
        private final StatsListener listener;
        private final ExecutorService inferenceExecutor;
        private final Object inputBufferPoolLock = new Object();
        private final ArrayDeque<byte[]> inputBufferPool = new ArrayDeque<>();
        private final Object outputBufferPoolLock = new Object();
        private final ArrayDeque<ByteBuffer> outputBufferPool = new ArrayDeque<>();
        private final Set<FrameResult> outstandingFrameResults =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private final Object lifecycleLock = new Object();

        private volatile boolean released;
        private volatile Thread inferenceThread;
        private boolean waitingForQnnLock;
        private int inputWidth;
        private int inputHeight;
        private QuickSrSession session;
        private float[] inputTensorScratch;
        private float[] outputTensorScratch;
        private byte[] outputRgbaScratch;
        private final QuickSrSession.RunTimings runTimings = new QuickSrSession.RunTimings();
        private boolean qnnLockHeld;
        private boolean qnnStrictEvidenceReported;
        private boolean evidenceCaptureReserved;
        private int frameNumber;
        private int neuralTextureId;
        private int neuralFboId;

        ProcessorImpl(
                Context context,
                QuickSrSession.Mode mode,
                Profile profile,
                StatsListener listener) {
            this(
                    context,
                    mode,
                    profile,
                    QuickSrSession.Tuning.BASELINE,
                    null,
                    VideoEvidenceStore.CaptureSpec.none(),
                    listener);
        }

        ProcessorImpl(
                Context context,
                QuickSrSession.Mode mode,
                Profile profile,
                QuickSrSession.Tuning tuning,
                String benchmarkRunId,
                VideoEvidenceStore.CaptureSpec captureSpec,
                StatsListener listener) {
            if (captureSpec == null) {
                captureSpec = VideoEvidenceStore.CaptureSpec.none();
            }
            if (captureSpec.isRequested()) {
                if (mode != QuickSrSession.Mode.QNN_HTP) {
                    throw new IllegalArgumentException(
                            "Video tensor capture is restricted to the QNN HTP benchmark path");
                }
                if (!VideoEvidenceStore.isSafeRunId(benchmarkRunId)) {
                    throw new IllegalArgumentException(
                            "Video tensor capture requires a safe benchmark run id");
                }
            }
            this.context = context;
            this.mode = mode;
            this.profile = profile;
            this.tuning = tuning;
            this.benchmarkRunId = benchmarkRunId;
            this.captureSpec = captureSpec;
            this.listener = listener;
            this.inferenceExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "QuickSR-video-inference");
                thread.setPriority(Thread.NORM_PRIORITY);
                inferenceThread = thread;
                return thread;
            });
        }

        @Override
        public Size configure(int inputWidth, int inputHeight)
                throws VideoFrameProcessingException {
            if (inputWidth <= 0 || inputHeight <= 0) {
                throw new VideoFrameProcessingException("Invalid effect input dimensions");
            }
            this.inputWidth = inputWidth;
            this.inputHeight = inputHeight;
            return new Size(profile.inputWidth(), profile.inputHeight());
        }

        @Override
        public GlRect getScaledRegion(long presentationTimeUs) {
            // Media3 scales the complete output-sized effect canvas to the static model input.
            // The processor later writes the 2x neural result back into that canvas.
            return new GlRect(inputWidth, inputHeight);
        }

        @Override
        public ListenableFuture<FrameResult> processImage(
                ByteBufferGlEffect.Image image,
                long presentationTimeUs) {
            SettableFuture<FrameResult> resultFuture = SettableFuture.create();
            if (released) {
                resultFuture.setException(new IllegalStateException("QuickSR video effect released"));
                return resultFuture;
            }
            int inputWidth = profile.inputWidth();
            int inputHeight = profile.inputHeight();
            if (image.width != inputWidth || image.height != inputHeight) {
                resultFuture.setException(new IllegalArgumentException(
                        "QuickSR video input changed: " + image.width + "x" + image.height));
                return resultFuture;
            }

            // Copy synchronously before returning. Media3 may recycle/unmap Image.pixelBuffer as
            // soon as the returned future is cancelled or completed.
            long receivedNs = SystemClock.elapsedRealtimeNanos();
            int inputPixels = checkedPixels(inputWidth, inputHeight);
            byte[] rgba = acquireInputBuffer(inputPixels * RGBA_BYTES_PER_PIXEL);
            try {
                ByteBuffer source = image.pixelBuffer.duplicate();
                source.position(0);
                source.get(rgba);
            } catch (Throwable failure) {
                recycleInputBuffer(rgba);
                resultFuture.setException(failure);
                return resultFuture;
            }
            long copiedNs = SystemClock.elapsedRealtimeNanos();
            try {
                inferenceExecutor.execute(() -> processCopiedFrame(
                        rgba,
                        presentationTimeUs,
                        receivedNs,
                        copiedNs,
                        resultFuture));
            } catch (RejectedExecutionException failure) {
                recycleInputBuffer(rgba);
                resultFuture.setException(failure);
            }
            return resultFuture;
        }

        private void processCopiedFrame(
                byte[] rgba,
                long presentationTimeUs,
                long receivedNs,
                long copiedNs,
                SettableFuture<FrameResult> resultFuture) {
            try {
                if (released) {
                    throw new IllegalStateException("QuickSR video effect released");
                }
                long taskStartedNs = SystemClock.elapsedRealtimeNanos();
                int inputPixels = checkedPixels(profile.inputWidth(), profile.inputHeight());
                int outputPixels = checkedPixels(profile.outputWidth(), profile.outputHeight());
                if (inputTensorScratch == null) {
                    inputTensorScratch = new float[3 * inputPixels];
                    outputTensorScratch = new float[3 * outputPixels];
                    outputRgbaScratch = new byte[outputPixels * RGBA_BYTES_PER_PIXEL];
                }
                rgbaToNchw(
                        rgba,
                        inputTensorScratch,
                        profile.inputWidth(),
                        profile.inputHeight());
                long inputConvertedNs = SystemClock.elapsedRealtimeNanos();
                boolean sessionWasReady = session != null;
                QuickSrSession activeSession = ensureSession();
                long sessionReadyNs = SystemClock.elapsedRealtimeNanos();
                if (released) {
                    throw new InterruptedException("QuickSR video effect released before inference");
                }
                int candidateFrame = frameNumber + 1;
                if (captureSpec.isRequested()
                        && !evidenceCaptureReserved
                        && captureSpec.hasBeenMissedBy(candidateFrame, presentationTimeUs)) {
                    throw new IllegalStateException(
                            "Requested video evidence selector was not observed before frame "
                                    + candidateFrame + " at ptsUs=" + presentationTimeUs);
                }
                long inferenceStartedNs = SystemClock.elapsedRealtimeNanos();
                activeSession.infer(inputTensorScratch, outputTensorScratch, runTimings);
                long inferenceFinishedNs = SystemClock.elapsedRealtimeNanos();
                if (captureSpec.isRequested()
                        && !evidenceCaptureReserved
                        && captureSpec.matches(candidateFrame, presentationTimeUs)) {
                    evidenceCaptureReserved = true;
                    JSONObject evidence = VideoEvidenceStore.write(
                            context,
                            benchmarkRunId,
                            captureSpec,
                            candidateFrame,
                            presentationTimeUs,
                            profile,
                            inputTensorScratch,
                            outputTensorScratch,
                            activeSession.qnnStrictEvidence());
                    if (listener != null) {
                        listener.onEvidenceCaptured(evidence);
                    }
                }
                packNchwToRgba(
                        outputTensorScratch,
                        rgba,
                        profile.inputWidth(),
                        profile.inputHeight(),
                        profile.outputWidth(),
                        profile.outputHeight(),
                        outputRgbaScratch);
                ByteBuffer directRgba = acquireOutputBuffer();
                directRgba.put(outputRgbaScratch);
                directRgba.flip();
                FrameResult frameResult = new FrameResult(
                        directRgba,
                        this::recycleFrameResult);
                synchronized (outputBufferPoolLock) {
                    outstandingFrameResults.add(frameResult);
                }
                long completedNs = SystemClock.elapsedRealtimeNanos();
                int completedFrame = ++frameNumber;
                if (!resultFuture.set(frameResult)) {
                    frameResult.recycle();
                    return;
                }
                if (listener != null) {
                    listener.onFrameProcessed(new FrameStats(
                            mode,
                            tuning,
                            profile,
                            completedFrame,
                            inputWidth,
                            inputHeight,
                            nanosToMs(copiedNs - receivedNs),
                            nanosToMs(taskStartedNs - copiedNs),
                            nanosToMs(inputConvertedNs - taskStartedNs),
                            sessionWasReady ? 0L : nanosToMs(sessionReadyNs - inputConvertedNs),
                            nanosToMs(inferenceFinishedNs - inferenceStartedNs),
                            nanosToMs(runTimings.inputCopyNs),
                            nanosToMs(runTimings.ortRunNs),
                            nanosToMs(runTimings.outputCopyNs),
                            nanosToMs(runTimings.finiteScanNs),
                            runTimings.finiteScanExecuted,
                            nanosToMs(completedNs - inferenceFinishedNs),
                            nanosToMs(completedNs - receivedNs),
                            presentationTimeUs));
                }
            } catch (Throwable failure) {
                if (listener != null) {
                    try {
                        listener.onProcessingError("video-inference", failure);
                    } catch (Throwable ignored) {
                        // Preserve the original inference failure as the Media3 error.
                    }
                }
                resultFuture.setException(failure);
            } finally {
                recycleInputBuffer(rgba);
            }
        }

        private byte[] acquireInputBuffer(int byteCount) {
            synchronized (inputBufferPoolLock) {
                byte[] pooled = inputBufferPool.pollFirst();
                if (pooled != null && pooled.length == byteCount) {
                    return pooled;
                }
            }
            return new byte[byteCount];
        }

        private void recycleInputBuffer(byte[] buffer) {
            int expectedByteCount = profile.inputWidth()
                    * profile.inputHeight()
                    * RGBA_BYTES_PER_PIXEL;
            if (buffer == null || buffer.length != expectedByteCount) {
                return;
            }
            synchronized (inputBufferPoolLock) {
                if (!released && inputBufferPool.size() < MAX_POOLED_INPUT_BUFFERS) {
                    inputBufferPool.addFirst(buffer);
                }
            }
        }

        private ByteBuffer acquireOutputBuffer() {
            int byteCount = profile.outputWidth()
                    * profile.outputHeight()
                    * RGBA_BYTES_PER_PIXEL;
            synchronized (outputBufferPoolLock) {
                ByteBuffer pooled = outputBufferPool.pollFirst();
                if (pooled != null) {
                    pooled.clear();
                    return pooled;
                }
            }
            return ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder());
        }

        private void recycleOutputBuffer(ByteBuffer buffer) {
            if (buffer == null || buffer.capacity() != profile.outputWidth()
                    * profile.outputHeight() * RGBA_BYTES_PER_PIXEL) {
                return;
            }
            synchronized (outputBufferPoolLock) {
                if (!released && outputBufferPool.size() < MAX_POOLED_OUTPUT_BUFFERS) {
                    buffer.clear();
                    outputBufferPool.addFirst(buffer);
                }
            }
        }

        private void recycleFrameResult(FrameResult result) {
            synchronized (outputBufferPoolLock) {
                outstandingFrameResults.remove(result);
            }
            recycleOutputBuffer(result.rgba);
        }

        private void recycleOutstandingFrameResults() {
            FrameResult[] snapshot;
            synchronized (outputBufferPoolLock) {
                snapshot = outstandingFrameResults.toArray(new FrameResult[0]);
            }
            for (FrameResult result : snapshot) {
                result.recycle();
            }
        }

        private QuickSrSession ensureSession() throws Exception {
            if (session != null) {
                return session;
            }
            if (released) {
                throw new InterruptedException("QuickSR video effect released before session setup");
            }
            if (mode == QuickSrSession.Mode.QNN_HTP) {
                acquireQnnProcessLock();
            }
            try {
                // Release may race with lock acquisition. Never initialize ORT/QNN after the
                // effect has already been disconnected from the Media3 graph.
                if (released) {
                    throw new InterruptedException(
                            "QuickSR video effect released after QNN lock acquisition");
                }
                String sessionRunId = VideoEvidenceStore.isSafeRunId(benchmarkRunId)
                        ? benchmarkRunId
                        : "video-" + ReceiptStore.newRunId();
                QuickSrSession openedSession = QuickSrSession.open(
                        context,
                        mode,
                        sessionRunId,
                        profile.modelVariant(),
                        tuning);
                if (openedSession.inputWidth() != profile.inputWidth()
                        || openedSession.inputHeight() != profile.inputHeight()
                        || openedSession.outputWidth() != profile.outputWidth()
                        || openedSession.outputHeight() != profile.outputHeight()) {
                    openedSession.close();
                    throw new IllegalStateException(
                            "QuickSR video profile/session dimension mismatch: "
                                    + profile + " vs "
                                    + openedSession.inputWidth() + "x"
                                    + openedSession.inputHeight() + "->"
                                    + openedSession.outputWidth() + "x"
                                    + openedSession.outputHeight());
                }
                if (mode == QuickSrSession.Mode.QNN_HTP) {
                    JSONObject qnnStrict = openedSession.qnnStrictEvidence();
                    if (qnnStrict == null || !qnnStrict.optBoolean("strictReady", false)) {
                        openedSession.close();
                        throw new IllegalStateException(
                                "QNN video session did not establish strict HTP registration evidence");
                    }
                    if (listener != null
                            && VideoEvidenceStore.isSafeRunId(benchmarkRunId)
                            && !qnnStrictEvidenceReported) {
                        try {
                            listener.onQnnStrictEvidence(qnnStrict);
                            qnnStrictEvidenceReported = true;
                        } catch (Throwable callbackFailure) {
                            try {
                                openedSession.close();
                            } catch (Throwable closeFailure) {
                                callbackFailure.addSuppressed(closeFailure);
                            }
                            throw new IllegalStateException(
                                    "Could not publish strict QNN video evidence",
                                    callbackFailure);
                        }
                    }
                }
                session = openedSession;
                return session;
            } catch (Throwable failure) {
                if (qnnLockHeld) {
                    qnnLockHeld = false;
                    QnnPluginRuntime.unlockProcess();
                }
                if (failure instanceof Exception) {
                    throw (Exception) failure;
                }
                throw new RuntimeException(failure);
            }
        }

        private void acquireQnnProcessLock() throws InterruptedException {
            synchronized (lifecycleLock) {
                if (released) {
                    throw new InterruptedException(
                            "QuickSR video effect released before QNN lock wait");
                }
                waitingForQnnLock = true;
            }
            boolean acquired = false;
            try {
                QnnPluginRuntime.lockProcess();
                acquired = true;
            } finally {
                synchronized (lifecycleLock) {
                    waitingForQnnLock = false;
                }
            }
            if (released || Thread.currentThread().isInterrupted()) {
                if (acquired) {
                    QnnPluginRuntime.unlockProcess();
                }
                throw new InterruptedException(
                        "QuickSR video effect released while waiting for QNN lock");
            }
            qnnLockHeld = true;
        }

        @Override
        public void finishProcessingAndBlend(
                GlTextureInfo outputFrame,
                long presentationTimeUs,
                FrameResult result) throws VideoFrameProcessingException {
            try {
                ensureNeuralTexture();
                ByteBuffer pixels = result.rgba.duplicate();
                pixels.position(0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, neuralTextureId);
                GLES20.glTexSubImage2D(
                        GLES20.GL_TEXTURE_2D,
                        0,
                        0,
                        0,
                        profile.outputWidth(),
                        profile.outputHeight(),
                        GLES20.GL_RGBA,
                        GLES20.GL_UNSIGNED_BYTE,
                        pixels);
                GlUtil.checkGlError();
                GlUtil.blitFrameBuffer(
                        neuralFboId,
                        new GlRect(profile.outputWidth(), profile.outputHeight()),
                        outputFrame.fboId,
                        new GlRect(outputFrame.width, outputFrame.height));
            } catch (GlUtil.GlException failure) {
                throw new VideoFrameProcessingException(
                        "Unable to upload the QuickSR video frame",
                        failure,
                        presentationTimeUs);
            } finally {
                result.recycle();
            }
        }

        private void ensureNeuralTexture() throws GlUtil.GlException {
            if (neuralTextureId != 0) {
                return;
            }
            neuralTextureId = GlUtil.createTexture(
                    profile.outputWidth(),
                    profile.outputHeight(),
                    false);
            try {
                neuralFboId = GlUtil.createFboForTexture(neuralTextureId);
            } catch (GlUtil.GlException failure) {
                GlUtil.deleteTexture(neuralTextureId);
                neuralTextureId = 0;
                throw failure;
            }
        }

        @Override
        public synchronized void release() throws VideoFrameProcessingException {
            Thread qnnLockWaiter;
            synchronized (lifecycleLock) {
                if (released) {
                    return;
                }
                released = true;
                qnnLockWaiter = waitingForQnnLock ? inferenceThread : null;
            }
            if (qnnLockWaiter != null) {
                // QnnPluginRuntime uses lockInterruptibly(). Wake the worker so cleanup queued on
                // the same executor is not trapped behind another long-running QNN owner.
                qnnLockWaiter.interrupt();
            }
            recycleOutstandingFrameResults();
            synchronized (inputBufferPoolLock) {
                inputBufferPool.clear();
            }
            synchronized (outputBufferPoolLock) {
                outputBufferPool.clear();
            }
            Throwable failure = null;
            if (neuralFboId != 0) {
                try {
                    GlUtil.deleteFbo(neuralFboId);
                } catch (Throwable caught) {
                    failure = caught;
                }
                neuralFboId = 0;
            }
            if (neuralTextureId != 0) {
                try {
                    GlUtil.deleteTexture(neuralTextureId);
                } catch (Throwable caught) {
                    failure = append(failure, caught);
                }
                neuralTextureId = 0;
            }

            Future<?> cleanupFuture = null;
            try {
                cleanupFuture = inferenceExecutor.submit(() -> {
                    closeSessionOnExecutor();
                    return null;
                });
            } catch (Throwable caught) {
                failure = append(failure, caught);
            } finally {
                inferenceExecutor.shutdown();
            }
            if (cleanupFuture != null) {
                try {
                    cleanupFuture.get(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException caught) {
                    Thread.currentThread().interrupt();
                    failure = append(failure, caught);
                } catch (ExecutionException caught) {
                    failure = append(failure,
                            caught.getCause() == null ? caught : caught.getCause());
                } catch (TimeoutException caught) {
                    // Leave cleanupFuture queued: it still owns responsibility for closing the
                    // session and unlocking QNN if the native inference eventually returns.
                    Thread worker = inferenceThread;
                    if (worker != null) {
                        worker.interrupt();
                    }
                    failure = append(failure, caught);
                }
            }
            // Cover a worker that completed concurrently with the first reclamation pass.
            recycleOutstandingFrameResults();
            if (failure != null) {
                throw new VideoFrameProcessingException(
                        "Unable to release the QuickSR video effect",
                        failure);
            }
        }

        private void closeSessionOnExecutor() throws Exception {
            Throwable failure = null;
            try {
                if (session != null) {
                    session.close();
                    session = null;
                }
            } catch (Throwable caught) {
                failure = caught;
            } finally {
                if (qnnLockHeld) {
                    qnnLockHeld = false;
                    QnnPluginRuntime.unlockProcess();
                }
                inputTensorScratch = null;
                outputTensorScratch = null;
                outputRgbaScratch = null;
            }
            if (failure instanceof Exception) {
                throw (Exception) failure;
            }
            if (failure != null) {
                throw new RuntimeException(failure);
            }
        }
    }

    static void rgbaToNchw(byte[] rgba, float[] output) {
        rgbaToNchw(
                rgba,
                output,
                DEFAULT_PROFILE.inputWidth(),
                DEFAULT_PROFILE.inputHeight());
    }

    static void rgbaToNchw(byte[] rgba, float[] output, int inputSide) {
        rgbaToNchw(rgba, output, inputSide, inputSide);
    }

    static void rgbaToNchw(
            byte[] rgba,
            float[] output,
            int inputWidth,
            int inputHeight) {
        int inputPixels = checkedPixels(inputWidth, inputHeight);
        if (rgba.length != inputPixels * RGBA_BYTES_PER_PIXEL ||
                output.length != 3 * inputPixels) {
            throw new IllegalArgumentException("QuickSR video input buffer length mismatch");
        }
        for (int pixel = 0; pixel < inputPixels; pixel++) {
            int base = pixel * RGBA_BYTES_PER_PIXEL;
            output[pixel] = (rgba[base] & 0xff) / 255.0f;
            output[inputPixels + pixel] = (rgba[base + 1] & 0xff) / 255.0f;
            output[inputPixels * 2 + pixel] = (rgba[base + 2] & 0xff) / 255.0f;
        }
    }

    static ByteBuffer nchwToRgba(float[] output, byte[] inputRgba) {
        return nchwToRgba(
                output,
                inputRgba,
                DEFAULT_PROFILE.inputWidth(),
                DEFAULT_PROFILE.inputHeight(),
                DEFAULT_PROFILE.outputWidth(),
                DEFAULT_PROFILE.outputHeight());
    }

    static ByteBuffer nchwToRgba(
            float[] output,
            byte[] inputRgba,
            int inputSide,
            int outputSide) {
        return nchwToRgba(
                output,
                inputRgba,
                inputSide,
                inputSide,
                outputSide,
                outputSide);
    }

    static ByteBuffer nchwToRgba(
            float[] output,
            byte[] inputRgba,
            int inputWidth,
            int inputHeight,
            int outputWidth,
            int outputHeight) {
        int outputPixels = checkedPixels(outputWidth, outputHeight);
        byte[] packedRgba = new byte[outputPixels * RGBA_BYTES_PER_PIXEL];
        packNchwToRgba(
                output,
                inputRgba,
                inputWidth,
                inputHeight,
                outputWidth,
                outputHeight,
                packedRgba);
        ByteBuffer rgba = ByteBuffer.allocateDirect(packedRgba.length)
                .order(ByteOrder.nativeOrder());
        rgba.put(packedRgba);
        rgba.flip();
        return rgba;
    }

    static void packNchwToRgba(
            float[] output,
            byte[] inputRgba,
            int inputSide,
            int outputSide,
            byte[] packedRgba) {
        packNchwToRgba(
                output,
                inputRgba,
                inputSide,
                inputSide,
                outputSide,
                outputSide,
                packedRgba);
    }

    static void packNchwToRgba(
            float[] output,
            byte[] inputRgba,
            int inputWidth,
            int inputHeight,
            int outputWidth,
            int outputHeight,
            byte[] packedRgba) {
        int inputPixels = checkedPixels(inputWidth, inputHeight);
        int outputPixels = checkedPixels(outputWidth, outputHeight);
        if (output.length != 3 * outputPixels
                || inputRgba.length != inputPixels * RGBA_BYTES_PER_PIXEL
                || packedRgba.length != outputPixels * RGBA_BYTES_PER_PIXEL) {
            throw new IllegalArgumentException("QuickSR video output buffer length mismatch");
        }
        int greenPlane = outputPixels;
        int bluePlane = outputPixels * 2;
        int rgbaOffset = 0;
        for (int y = 0; y < outputHeight; y++) {
            int inputY = Math.min(inputHeight - 1, y * inputHeight / outputHeight);
            int inputAlphaRow = inputY * inputWidth * RGBA_BYTES_PER_PIXEL;
            int outputPixel = y * outputWidth;
            for (int x = 0; x < outputWidth; x++) {
                packedRgba[rgbaOffset++] = normalizedToByte(output[outputPixel]);
                packedRgba[rgbaOffset++] = normalizedToByte(output[greenPlane + outputPixel]);
                packedRgba[rgbaOffset++] = normalizedToByte(output[bluePlane + outputPixel]);
                int inputX = Math.min(inputWidth - 1, x * inputWidth / outputWidth);
                packedRgba[rgbaOffset++] = inputRgba[
                        inputAlphaRow + inputX * RGBA_BYTES_PER_PIXEL + 3];
                outputPixel++;
            }
        }
    }

    private static int checkedPixels(int side) {
        return checkedPixels(side, side);
    }

    private static int checkedPixels(int width, int height) {
        if (width <= 0 || width > 4096 || height <= 0 || height > 4096) {
            throw new IllegalArgumentException(
                    "Invalid QuickSR video dimensions: " + width + "x" + height);
        }
        return Math.multiplyExact(width, height);
    }

    private static long nanosToMs(long nanos) {
        return Math.max(0L, nanos / 1_000_000L);
    }

    private static byte normalizedToByte(float value) {
        // The !(value > 0) form intentionally maps NaN to zero, matching Math.round(NaN).
        if (!(value > 0.0f)) {
            return 0;
        }
        if (value >= 1.0f) {
            return (byte) 0xff;
        }
        return (byte) ((int) (value * 255.0f + 0.5f));
    }

    private static Throwable append(Throwable current, Throwable next) {
        if (current == null) {
            return next;
        }
        if (current != next) {
            current.addSuppressed(next);
        }
        return current;
    }
}
