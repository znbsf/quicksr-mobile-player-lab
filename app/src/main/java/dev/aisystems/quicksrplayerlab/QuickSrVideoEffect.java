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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.zip.CRC32;

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
    enum PostprocessMode {
        SERIAL,
        OVERLAP
    }

    enum OutputPackerMode {
        JAVA,
        NATIVE_NEON
    }

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

        default void onPipelineSnapshot(
                VideoPipelineTelemetry.Snapshot snapshot,
                String reason) {
        }
    }

    static final class FrameStats {
        final QuickSrSession.Mode mode;
        final QuickSrSession.Tuning tuning;
        final Profile profile;
        final PostprocessMode postprocessMode;
        final OutputPackerMode outputPackerMode;
        final AnimeCadenceAnalyzer.Mode cadenceMode;
        final AnimeCadenceAnalyzer.Decision cadenceDecision;
        final AnimeCadenceAnalyzer.Reason cadenceReason;
        final long cadenceStreamEpoch;
        final int cadenceReferenceGeneration;
        final long cadenceReferenceStreamEpoch;
        final long cadenceReferenceFrameId;
        final int reuseStreak;
        final long cadenceAnalysisNs;
        final float cadenceSceneScore;
        final float cadenceSubtitleScore;
        final float cadenceMotionScore;
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
        final long frameId;
        final int generation;
        final long generationFrameId;
        final String inputCrc32;
        final String outputCrc32;
        final boolean late;
        final long ptsWallClockDriftNs;
        final long acceptedNs;
        final long readbackReadyProxyNs;
        final long inputCopyStartedNs;
        final long inputCopiedNs;
        final long inputHashStartedNs;
        final long inputHashFinishedNs;
        final long workerStartedNs;
        final long outputTensorAcquireStartedNs;
        final long outputTensorSlotAcquiredNs;
        final long outputTensorReadyNs;
        final long preprocessFinishedNs;
        final long sessionReadyNs;
        final long inferenceStartedNs;
        final long inferenceFinishedNs;
        final long outputPackStartedNs;
        final long outputPackFinishedNs;
        final long outputHashStartedNs;
        final long outputHashFinishedNs;
        final long directBufferCopyStartedNs;
        final long directBufferCopyFinishedNs;
        final long outputReadyNs;
        final long glUploadStartedNs;
        final long glUploadFinishedNs;
        final long outputSubmittedProxyNs;
        final long tensorInputCopyNs;
        final long ortRunNs;
        final long tensorOutputCopyNs;
        final long finiteScanNs;
        final long acceptedCount;
        final long processedCount;
        final long cadenceProcessedCount;
        final long cadenceReusedCount;
        final long lateCount;
        final long droppedCount;
        final long bypassedCount;
        final int currentQueueDepth;
        final int maxQueueDepth;
        final long flushCount;
        final long seekProxyCount;

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
            this.postprocessMode = PostprocessMode.SERIAL;
            this.outputPackerMode = OutputPackerMode.JAVA;
            this.cadenceMode = AnimeCadenceAnalyzer.Mode.OFF;
            this.cadenceDecision = AnimeCadenceAnalyzer.Decision.PROCESS;
            this.cadenceReason = AnimeCadenceAnalyzer.Reason.DISABLED;
            this.cadenceStreamEpoch = 0L;
            this.cadenceReferenceGeneration = -1;
            this.cadenceReferenceStreamEpoch = -1L;
            this.cadenceReferenceFrameId = -1L;
            this.reuseStreak = 0;
            this.cadenceAnalysisNs = 0L;
            this.cadenceSceneScore = 0.0f;
            this.cadenceSubtitleScore = 0.0f;
            this.cadenceMotionScore = 0.0f;
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
            this.frameId = frameNumber;
            this.generation = 0;
            this.generationFrameId = frameNumber;
            this.inputCrc32 = "unavailable";
            this.outputCrc32 = "unavailable";
            this.late = false;
            this.ptsWallClockDriftNs = Long.MIN_VALUE;
            this.acceptedNs = -1L;
            this.readbackReadyProxyNs = -1L;
            this.inputCopyStartedNs = -1L;
            this.inputCopiedNs = -1L;
            this.inputHashStartedNs = -1L;
            this.inputHashFinishedNs = -1L;
            this.workerStartedNs = -1L;
            this.outputTensorAcquireStartedNs = -1L;
            this.outputTensorSlotAcquiredNs = -1L;
            this.outputTensorReadyNs = -1L;
            this.preprocessFinishedNs = -1L;
            this.sessionReadyNs = -1L;
            this.inferenceStartedNs = -1L;
            this.inferenceFinishedNs = -1L;
            this.outputPackStartedNs = -1L;
            this.outputPackFinishedNs = -1L;
            this.outputHashStartedNs = -1L;
            this.outputHashFinishedNs = -1L;
            this.directBufferCopyStartedNs = -1L;
            this.directBufferCopyFinishedNs = -1L;
            this.outputReadyNs = -1L;
            this.glUploadStartedNs = -1L;
            this.glUploadFinishedNs = -1L;
            this.outputSubmittedProxyNs = -1L;
            this.tensorInputCopyNs = TimeUnit.MILLISECONDS.toNanos(tensorInputCopyMs);
            this.ortRunNs = TimeUnit.MILLISECONDS.toNanos(ortRunMs);
            this.tensorOutputCopyNs = TimeUnit.MILLISECONDS.toNanos(tensorOutputCopyMs);
            this.finiteScanNs = TimeUnit.MILLISECONDS.toNanos(finiteScanMs);
            this.acceptedCount = frameNumber;
            this.processedCount = frameNumber;
            this.cadenceProcessedCount = frameNumber;
            this.cadenceReusedCount = 0L;
            this.lateCount = 0L;
            this.droppedCount = 0L;
            this.bypassedCount = 0L;
            this.currentQueueDepth = 0;
            this.maxQueueDepth = 0;
            this.flushCount = 0L;
            this.seekProxyCount = 0L;
        }

        FrameStats(
                QuickSrSession.Mode mode,
                QuickSrSession.Tuning tuning,
                Profile profile,
                PostprocessMode postprocessMode,
                OutputPackerMode outputPackerMode,
                int effectInputWidth,
                int effectInputHeight,
                FrameTimings timings,
                VideoPipelineTelemetry.Completion completion) {
            this.mode = mode;
            this.tuning = tuning;
            this.profile = profile;
            this.postprocessMode = postprocessMode;
            this.outputPackerMode = outputPackerMode;
            this.cadenceMode = timings.cadenceMode;
            this.cadenceDecision = timings.cadenceDecision;
            this.cadenceReason = timings.cadenceReason;
            this.cadenceStreamEpoch = timings.token.cadenceStreamEpoch;
            this.cadenceReferenceGeneration = timings.cadenceReferenceGeneration;
            this.cadenceReferenceStreamEpoch = timings.cadenceReferenceStreamEpoch;
            this.cadenceReferenceFrameId = timings.cadenceReferenceFrameId;
            this.reuseStreak = timings.reuseStreak;
            this.cadenceAnalysisNs = timings.cadenceAnalysisFinishedNs
                    - timings.cadenceAnalysisStartedNs;
            this.cadenceSceneScore = timings.cadenceSceneScore;
            this.cadenceSubtitleScore = timings.cadenceSubtitleScore;
            this.cadenceMotionScore = timings.cadenceMotionScore;
            this.frameNumber = Math.toIntExact(timings.token.frameId);
            this.effectInputWidth = effectInputWidth;
            this.effectInputHeight = effectInputHeight;
            this.modelInputWidth = profile.inputWidth();
            this.modelInputHeight = profile.inputHeight();
            this.modelOutputWidth = profile.outputWidth();
            this.modelOutputHeight = profile.outputHeight();
            this.modelInputSide = modelInputWidth == modelInputHeight ? modelInputWidth : -1;
            this.modelOutputSide = modelOutputWidth == modelOutputHeight ? modelOutputWidth : -1;
            this.frameId = timings.token.frameId;
            this.generation = timings.token.generation;
            this.generationFrameId = timings.token.generationFrameId;
            this.presentationTimeUs = timings.token.presentationTimeUs;
            this.inputCrc32 = timings.inputCrc32;
            this.outputCrc32 = timings.outputCrc32;
            this.late = completion.late;
            this.ptsWallClockDriftNs = completion.ptsWallClockDriftNs;
            this.acceptedNs = timings.token.acceptedNs;
            this.readbackReadyProxyNs = timings.token.readbackReadyProxyNs;
            this.inputCopyStartedNs = timings.inputCopyStartedNs;
            this.inputCopiedNs = timings.inputCopiedNs;
            this.inputHashStartedNs = timings.inputHashStartedNs;
            this.inputHashFinishedNs = timings.inputHashFinishedNs;
            this.workerStartedNs = timings.workerStartedNs;
            this.outputTensorAcquireStartedNs = timings.outputTensorAcquireStartedNs;
            this.outputTensorSlotAcquiredNs = timings.outputTensorSlotAcquiredNs;
            this.outputTensorReadyNs = timings.outputTensorReadyNs;
            this.preprocessFinishedNs = timings.preprocessFinishedNs;
            this.sessionReadyNs = timings.sessionReadyNs;
            this.inferenceStartedNs = timings.inferenceStartedNs;
            this.inferenceFinishedNs = timings.inferenceFinishedNs;
            this.outputPackStartedNs = timings.outputPackStartedNs;
            this.outputPackFinishedNs = timings.outputPackFinishedNs;
            this.outputHashStartedNs = timings.outputHashStartedNs;
            this.outputHashFinishedNs = timings.outputHashFinishedNs;
            this.directBufferCopyStartedNs = timings.directBufferCopyStartedNs;
            this.directBufferCopyFinishedNs = timings.directBufferCopyFinishedNs;
            this.outputReadyNs = timings.outputReadyNs;
            this.glUploadStartedNs = timings.glUploadStartedNs;
            this.glUploadFinishedNs = timings.glUploadFinishedNs;
            this.outputSubmittedProxyNs = timings.outputSubmittedProxyNs;
            this.observedMonotonicNs = timings.outputSubmittedProxyNs;
            this.tensorInputCopyNs = timings.tensorInputCopyNs;
            this.ortRunNs = timings.ortRunNs;
            this.tensorOutputCopyNs = timings.tensorOutputCopyNs;
            this.finiteScanNs = timings.finiteScanNs;
            this.finiteScanExecuted = timings.finiteScanExecuted;
            this.copyMs = nanosToMs(timings.inputCopiedNs - timings.inputCopyStartedNs);
            this.queueMs = nanosToMs(timings.workerStartedNs - timings.inputHashFinishedNs);
            this.inputConversionMs = nanosToMs(
                    timings.preprocessFinishedNs - timings.outputTensorReadyNs);
            this.sessionSetupMs = nanosToMs(
                    timings.sessionReadyNs - timings.preprocessFinishedNs);
            this.inferenceMs = nanosToMs(
                    timings.inferenceFinishedNs - timings.inferenceStartedNs);
            this.tensorInputCopyMs = nanosToMs(timings.tensorInputCopyNs);
            this.ortRunMs = nanosToMs(timings.ortRunNs);
            this.tensorOutputCopyMs = nanosToMs(timings.tensorOutputCopyNs);
            this.finiteScanMs = nanosToMs(timings.finiteScanNs);
            this.outputConversionMs = nanosToMs(
                    timings.directBufferCopyFinishedNs - timings.outputPackStartedNs);
            this.totalProcessingMs = nanosToMs(
                    timings.outputSubmittedProxyNs - timings.token.acceptedNs);
            VideoPipelineTelemetry.Snapshot snapshot = completion.snapshot;
            this.acceptedCount = snapshot.accepted;
            this.processedCount = snapshot.processed;
            this.cadenceProcessedCount = timings.cadenceProcessedCount;
            this.cadenceReusedCount = timings.cadenceReusedCount;
            this.lateCount = snapshot.late;
            this.droppedCount = snapshot.dropped;
            this.bypassedCount = snapshot.bypassed;
            this.currentQueueDepth = snapshot.currentQueueDepth;
            this.maxQueueDepth = snapshot.maxQueueDepth;
            this.flushCount = snapshot.flushCount;
            this.seekProxyCount = snapshot.seekProxyCount;
        }
    }

    static final class FrameTimings {
        final VideoPipelineTelemetry.FrameToken token;
        String inputCrc32;
        String outputCrc32;
        long inputCopyStartedNs;
        long inputCopiedNs;
        long inputHashStartedNs;
        long inputHashFinishedNs;
        long workerStartedNs;
        long outputTensorAcquireStartedNs;
        long outputTensorSlotAcquiredNs;
        long outputTensorReadyNs;
        long preprocessFinishedNs;
        long sessionReadyNs;
        long inferenceStartedNs;
        long inferenceFinishedNs;
        long outputPackStartedNs;
        long outputPackFinishedNs;
        long outputHashStartedNs;
        long outputHashFinishedNs;
        long directBufferCopyStartedNs;
        long directBufferCopyFinishedNs;
        long outputReadyNs;
        long glUploadStartedNs;
        long glUploadFinishedNs;
        long outputSubmittedProxyNs;
        long tensorInputCopyNs;
        long ortRunNs;
        long tensorOutputCopyNs;
        long finiteScanNs;
        boolean finiteScanExecuted;
        AnimeCadenceAnalyzer.Mode cadenceMode = AnimeCadenceAnalyzer.Mode.OFF;
        AnimeCadenceAnalyzer.Decision cadenceDecision = AnimeCadenceAnalyzer.Decision.PROCESS;
        AnimeCadenceAnalyzer.Reason cadenceReason = AnimeCadenceAnalyzer.Reason.DISABLED;
        int cadenceReferenceGeneration = -1;
        long cadenceReferenceStreamEpoch = -1L;
        long cadenceReferenceFrameId = -1L;
        int reuseStreak;
        long cadenceAnalysisStartedNs;
        long cadenceAnalysisFinishedNs;
        float cadenceSceneScore;
        float cadenceSubtitleScore;
        float cadenceMotionScore;
        long cadenceProcessedCount;
        long cadenceReusedCount;

        FrameTimings(VideoPipelineTelemetry.FrameToken token) {
            this.token = token;
        }
    }

    private static final Profile DEFAULT_PROFILE = Profile.FAST_64;
    private static final int RGBA_BYTES_PER_PIXEL = 4;
    private static final int MAX_POOLED_INPUT_BUFFERS = 8;
    private static final int MAX_POOLED_OUTPUT_BUFFERS = 3;
    static final int OVERLAP_POSTPROCESS_QUEUE_CAPACITY = 1;
    static final int OVERLAP_OUTPUT_TENSOR_SLOTS = 2;
    private static final long RELEASE_TIMEOUT_SECONDS = 30L;
    private final ProcessorImpl processor;

    static int postprocessQueueCapacity(boolean overlap) {
        return overlap ? OVERLAP_POSTPROCESS_QUEUE_CAPACITY : 0;
    }

    static int outputTensorSlotCount(boolean overlap) {
        return overlap ? OVERLAP_OUTPUT_TENSOR_SLOTS : 1;
    }

    static long outputTensorBytesPerSlot(Profile profile) {
        return Math.multiplyExact(
                Math.multiplyExact(
                        (long) profile.outputWidth(),
                        (long) profile.outputHeight()),
                3L * Float.BYTES);
    }

    static long additionalOverlapTensorBytes(Profile profile, boolean overlap) {
        return overlap ? outputTensorBytesPerSlot(profile) : 0L;
    }

    static boolean cadenceCacheMatches(
            int cacheGeneration,
            long cacheStreamEpoch,
            long cacheSourceFrameId,
            int expectedGeneration,
            long expectedStreamEpoch,
            long expectedSourceFrameId) {
        return cacheGeneration == expectedGeneration
                && cacheStreamEpoch == expectedStreamEpoch
                && cacheSourceFrameId == expectedSourceFrameId;
    }

    static byte[] copyIntoCadenceCache(byte[] source, byte[] existing) {
        if (source == null) {
            throw new IllegalArgumentException("Cadence cache source is required");
        }
        byte[] target = existing != null && existing.length == source.length
                ? existing
                : new byte[source.length];
        System.arraycopy(source, 0, target, 0, source.length);
        return target;
    }

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
        this(
                context,
                mode,
                profile,
                tuning,
                benchmarkRunId,
                captureSpec,
                false,
                listener);
    }

    QuickSrVideoEffect(
            Context context,
            QuickSrSession.Mode mode,
            Profile profile,
            QuickSrSession.Tuning tuning,
            String benchmarkRunId,
            VideoEvidenceStore.CaptureSpec captureSpec,
            boolean postprocessOverlap,
            StatsListener listener) {
        this(
                context,
                mode,
                profile,
                tuning,
                benchmarkRunId,
                captureSpec,
                postprocessOverlap,
                false,
                AnimeCadenceAnalyzer.Mode.OFF,
                listener);
    }

    QuickSrVideoEffect(
            Context context,
            QuickSrSession.Mode mode,
            Profile profile,
            QuickSrSession.Tuning tuning,
            String benchmarkRunId,
            VideoEvidenceStore.CaptureSpec captureSpec,
            boolean postprocessOverlap,
            AnimeCadenceAnalyzer.Mode cadenceMode,
            StatsListener listener) {
        this(
                context,
                mode,
                profile,
                tuning,
                benchmarkRunId,
                captureSpec,
                postprocessOverlap,
                false,
                cadenceMode,
                listener);
    }

    QuickSrVideoEffect(
            Context context,
            QuickSrSession.Mode mode,
            Profile profile,
            QuickSrSession.Tuning tuning,
            String benchmarkRunId,
            VideoEvidenceStore.CaptureSpec captureSpec,
            boolean postprocessOverlap,
            boolean nativeOutputPacker,
            AnimeCadenceAnalyzer.Mode cadenceMode,
            StatsListener listener) {
        processor = new ProcessorImpl(
                context.getApplicationContext(),
                mode,
                profile,
                tuning,
                benchmarkRunId,
                captureSpec,
                postprocessOverlap,
                nativeOutputPacker,
                cadenceMode,
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
        final FrameTimings timings;
        private final Recycler recycler;
        private final AtomicBoolean recycled = new AtomicBoolean();

        FrameResult(ByteBuffer rgba, Recycler recycler) {
            this(rgba, null, recycler);
        }

        FrameResult(ByteBuffer rgba, FrameTimings timings, Recycler recycler) {
            this.rgba = rgba;
            this.timings = timings;
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
            VideoPipelineTelemetry.FrameToken token = processor.onFrameAccepted(presentationTimeUs);
            try {
                delegate.queueInputFrame(glObjectsProvider, inputTexture, presentationTimeUs);
            } catch (Throwable failure) {
                processor.onFrameAcceptanceFailed(token);
                throw failure;
            }
        }

        @Override
        public void releaseOutputFrame(GlTextureInfo outputTexture) {
            delegate.releaseOutputFrame(outputTexture);
        }

        @Override
        public void signalEndOfCurrentInputStream() {
            delegate.signalEndOfCurrentInputStream();
            processor.advanceCadenceStreamBoundary();
            processor.publishPipelineSnapshot("end_of_input_stream");
        }

        @Override
        public void flush() {
            processor.advanceGenerationForFlush();
            try {
                delegate.flush();
            } finally {
                // QueuingGlShaderProgram drops completed futures during flush without invoking
                // finishProcessingAndBlend. Reclaim their direct buffers after the delegate has
                // removed every queued frame.
                processor.recycleOutstandingFrameResults();
                processor.publishPipelineSnapshot("flush_seek_proxy");
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
        private final PostprocessMode postprocessMode;
        private final OutputPackerMode outputPackerMode;
        private final AnimeCadenceAnalyzer.Mode cadenceMode;
        private final StatsListener listener;
        private final LongSupplier monotonicClock;
        private final ThreadPoolExecutor inferenceExecutor;
        private final ThreadPoolExecutor postprocessExecutor;
        private final Semaphore frameQueueSlots = new Semaphore(
                VideoPipelineTelemetry.WORKER_QUEUE_CAPACITY,
                true);
        private final VideoPipelineTelemetry pipelineTelemetry = new VideoPipelineTelemetry();
        private final Object inputBufferPoolLock = new Object();
        private final ArrayDeque<byte[]> inputBufferPool = new ArrayDeque<>();
        private final Object outputBufferPoolLock = new Object();
        private final ArrayDeque<ByteBuffer> outputBufferPool = new ArrayDeque<>();
        private final Object outputTensorPoolLock = new Object();
        private final ArrayDeque<float[]> outputTensorPool = new ArrayDeque<>();
        private final Semaphore outputTensorSlots = new Semaphore(
                OVERLAP_OUTPUT_TENSOR_SLOTS,
                true);
        private final Set<FrameResult> outstandingFrameResults =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private final Object lifecycleLock = new Object();
        private final AnimeCadenceAnalyzer cadenceAnalyzer = new AnimeCadenceAnalyzer();
        private final Object cadenceCacheLock = new Object();

        private static final class CachedSrOutput {
            final byte[] pixels;
            final int generation;
            final long streamEpoch;
            final long sourceFrameId;
            final String outputCrc32;

            CachedSrOutput(
                    byte[] pixels,
                    int generation,
                    long streamEpoch,
                    long sourceFrameId,
                    String outputCrc32) {
                this.pixels = pixels;
                this.generation = generation;
                this.streamEpoch = streamEpoch;
                this.sourceFrameId = sourceFrameId;
                this.outputCrc32 = outputCrc32;
            }
        }

        private volatile boolean released;
        private volatile Thread inferenceThread;
        private volatile Thread postprocessThread;
        private volatile boolean workerCleanupQueued;
        private volatile boolean workerCleanupCompleted;
        private final AtomicInteger workerCleanupRunCount = new AtomicInteger();
        private long releaseTimeoutMs = TimeUnit.SECONDS.toMillis(RELEASE_TIMEOUT_SECONDS);
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
        private long cadenceProcessedCount;
        private long cadenceReusedCount;
        private volatile int latestInferredGeneration = Integer.MIN_VALUE;
        private volatile long latestInferredStreamEpoch = -1L;
        private volatile long latestInferredFrameId = -1L;
        private volatile long cadenceStreamEpoch;
        private byte[] cadenceCachePixels;
        private CachedSrOutput cachedSrOutput;
        private int neuralTextureId;
        private int neuralFboId;

        /** Cleanup marker that must run after every already-accepted frame task on the worker. */
        private final class WorkerCleanupTask extends FutureTask<Void> {
            WorkerCleanupTask() {
                super(() -> {
                    closeSessionOnExecutor();
                    return null;
                });
            }
        }

        /** Releases the frame-only queue permit as soon as the worker dequeues this task. */
        private final class FrameWorkerTask implements Runnable {
            private final Runnable delegate;
            private final AtomicBoolean queueSlotReleased = new AtomicBoolean();

            FrameWorkerTask(Runnable delegate) {
                this.delegate = delegate;
            }

            @Override
            public void run() {
                releaseQueueSlot();
                delegate.run();
            }

            void releaseQueueSlot() {
                if (queueSlotReleased.compareAndSet(false, true)) {
                    frameQueueSlots.release();
                }
            }
        }

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
                    false,
                    false,
                    AnimeCadenceAnalyzer.Mode.OFF,
                    listener,
                    System::nanoTime);
        }

        ProcessorImpl(
                Context context,
                QuickSrSession.Mode mode,
                Profile profile,
                QuickSrSession.Tuning tuning,
                String benchmarkRunId,
                VideoEvidenceStore.CaptureSpec captureSpec,
                boolean postprocessOverlap,
                StatsListener listener) {
            this(
                    context,
                    mode,
                    profile,
                    tuning,
                    benchmarkRunId,
                    captureSpec,
                    postprocessOverlap,
                    false,
                    AnimeCadenceAnalyzer.Mode.OFF,
                    listener);
        }

        ProcessorImpl(
                Context context,
                QuickSrSession.Mode mode,
                Profile profile,
                QuickSrSession.Tuning tuning,
                String benchmarkRunId,
                VideoEvidenceStore.CaptureSpec captureSpec,
                boolean postprocessOverlap,
                AnimeCadenceAnalyzer.Mode cadenceMode,
                StatsListener listener) {
            this(
                    context,
                    mode,
                    profile,
                    tuning,
                    benchmarkRunId,
                    captureSpec,
                    postprocessOverlap,
                    false,
                    cadenceMode,
                    listener,
                    SystemClock::elapsedRealtimeNanos);
        }

        private ProcessorImpl(
                Context context,
                QuickSrSession.Mode mode,
                Profile profile,
                QuickSrSession.Tuning tuning,
                String benchmarkRunId,
                VideoEvidenceStore.CaptureSpec captureSpec,
                boolean postprocessOverlap,
                boolean nativeOutputPacker,
                AnimeCadenceAnalyzer.Mode cadenceMode,
                StatsListener listener) {
            this(
                    context,
                    mode,
                    profile,
                    tuning,
                    benchmarkRunId,
                    captureSpec,
                    postprocessOverlap,
                    nativeOutputPacker,
                    cadenceMode,
                    listener,
                    SystemClock::elapsedRealtimeNanos);
        }

        private ProcessorImpl(
                Context context,
                QuickSrSession.Mode mode,
                Profile profile,
                QuickSrSession.Tuning tuning,
                String benchmarkRunId,
                VideoEvidenceStore.CaptureSpec captureSpec,
                boolean postprocessOverlap,
                StatsListener listener,
                LongSupplier monotonicClock) {
            this(
                    context,
                    mode,
                    profile,
                    tuning,
                    benchmarkRunId,
                    captureSpec,
                    postprocessOverlap,
                    false,
                    AnimeCadenceAnalyzer.Mode.OFF,
                    listener,
                    monotonicClock);
        }

        private ProcessorImpl(
                Context context,
                QuickSrSession.Mode mode,
                Profile profile,
                QuickSrSession.Tuning tuning,
                String benchmarkRunId,
                VideoEvidenceStore.CaptureSpec captureSpec,
                boolean postprocessOverlap,
                AnimeCadenceAnalyzer.Mode cadenceMode,
                StatsListener listener,
                LongSupplier monotonicClock) {
            this(
                    context,
                    mode,
                    profile,
                    tuning,
                    benchmarkRunId,
                    captureSpec,
                    postprocessOverlap,
                    false,
                    cadenceMode,
                    listener,
                    monotonicClock);
        }

        private ProcessorImpl(
                Context context,
                QuickSrSession.Mode mode,
                Profile profile,
                QuickSrSession.Tuning tuning,
                String benchmarkRunId,
                VideoEvidenceStore.CaptureSpec captureSpec,
                boolean postprocessOverlap,
                boolean nativeOutputPacker,
                AnimeCadenceAnalyzer.Mode cadenceMode,
                StatsListener listener,
                LongSupplier monotonicClock) {
            if (captureSpec == null) {
                captureSpec = VideoEvidenceStore.CaptureSpec.none();
            }
            if (cadenceMode == null) {
                cadenceMode = AnimeCadenceAnalyzer.Mode.OFF;
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
            this.postprocessMode = postprocessOverlap
                    ? PostprocessMode.OVERLAP
                    : PostprocessMode.SERIAL;
            this.outputPackerMode = nativeOutputPacker
                    ? OutputPackerMode.NATIVE_NEON
                    : OutputPackerMode.JAVA;
            this.cadenceMode = cadenceMode;
            this.listener = listener;
            this.monotonicClock = monotonicClock;
            ThreadPoolExecutor executor = new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    // One non-frame slot is reserved for WorkerCleanupTask. FrameWorkerTask
                    // admission remains capped by frameQueueSlots at WORKER_QUEUE_CAPACITY.
                    new ArrayBlockingQueue<>(
                            VideoPipelineTelemetry.WORKER_QUEUE_CAPACITY
                                    + VideoPipelineTelemetry.WORKER_CLEANUP_RESERVED_SLOTS),
                    runnable -> {
                Thread thread = new Thread(runnable, "QuickSR-video-inference");
                thread.setPriority(Thread.NORM_PRIORITY);
                inferenceThread = thread;
                return thread;
            });
            executor.setRejectedExecutionHandler((task, target) -> {
                throw new RejectedExecutionException(
                        "QuickSR worker queue invariant failed or executor stopped");
            });
            this.inferenceExecutor = executor;
            if (postprocessOverlap) {
                ThreadPoolExecutor postprocess = new ThreadPoolExecutor(
                        1,
                        1,
                        0L,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(OVERLAP_POSTPROCESS_QUEUE_CAPACITY),
                        runnable -> {
                            Thread thread = new Thread(runnable, "QuickSR-video-postprocess");
                            thread.setPriority(Thread.NORM_PRIORITY);
                            postprocessThread = thread;
                            return thread;
                        });
                postprocess.setRejectedExecutionHandler((task, target) -> {
                    try {
                        // A single queued frame plus the active frame bounds overlap memory while
                        // preserving FIFO order when postprocess becomes the slower stage. Poll so
                        // release can break backpressure without waiting for the native timeout.
                        while (true) {
                            if (target.getQueue().offer(task, 100L, TimeUnit.MILLISECONDS)) {
                                if ((released || target.isShutdown())
                                        && target.getQueue().remove(task)) {
                                    // shutdown may have raced the successful offer after the last
                                    // worker exited. Remove the orphan so the caller retains buffer
                                    // ownership and can fail/recycle it normally.
                                    throw new RejectedExecutionException(
                                            "QuickSR postprocess executor stopped");
                                }
                                // If remove failed, a worker already owns the task and its finally
                                // block remains responsible for all buffers and permits.
                                return;
                            }
                            if (released || target.isShutdown()) {
                                throw new RejectedExecutionException(
                                        "QuickSR postprocess executor stopped");
                            }
                        }
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new RejectedExecutionException(
                                "Interrupted while applying postprocess backpressure",
                                failure);
                    }
                });
                this.postprocessExecutor = postprocess;
            } else {
                this.postprocessExecutor = null;
            }
        }

        private long nowNs() {
            return monotonicClock.getAsLong();
        }

        private void enqueueFrameTask(Runnable task) {
            boolean permitAcquired = false;
            FrameWorkerTask frameTask = null;
            try {
                while (!permitAcquired) {
                    if (released) {
                        throw new RejectedExecutionException(
                                "QuickSR video effect released while applying backpressure");
                    }
                    permitAcquired = frameQueueSlots.tryAcquire(100L, TimeUnit.MILLISECONDS);
                }
                frameTask = new FrameWorkerTask(task);
                synchronized (lifecycleLock) {
                    if (released) {
                        throw new RejectedExecutionException(
                                "QuickSR video effect released before frame enqueue");
                    }
                    inferenceExecutor.execute(frameTask);
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException(
                        "Interrupted while applying bounded QuickSR backpressure",
                        failure);
            } catch (RuntimeException failure) {
                if (frameTask != null) {
                    frameTask.releaseQueueSlot();
                } else if (permitAcquired) {
                    frameQueueSlots.release();
                }
                throw failure;
            }
        }

        VideoPipelineTelemetry.FrameToken onFrameAccepted(long presentationTimeUs) {
            if (released) {
                throw new IllegalStateException("QuickSR video effect released");
            }
            VideoPipelineTelemetry.FrameToken token = pipelineTelemetry.accept(
                    presentationTimeUs,
                    nowNs());
            token.cadenceStreamEpoch = cadenceStreamEpoch;
            return token;
        }

        void onFrameAcceptanceFailed(VideoPipelineTelemetry.FrameToken token) {
            pipelineTelemetry.cancelAcceptance(
                    token,
                    nowNs(),
                    workerQueueDepth());
        }

        void advanceGenerationForFlush() {
            pipelineTelemetry.flush(
                    nowNs(),
                    workerQueueDepth());
            cadenceStreamEpoch++;
            cadenceAnalyzer.reset(cadenceStreamEpoch);
            latestInferredGeneration = Integer.MIN_VALUE;
            latestInferredStreamEpoch = -1L;
            latestInferredFrameId = -1L;
            clearCadenceCache();
        }

        void advanceCadenceStreamBoundary() {
            cadenceStreamEpoch++;
            cadenceAnalyzer.reset(cadenceStreamEpoch);
            latestInferredGeneration = Integer.MIN_VALUE;
            latestInferredStreamEpoch = -1L;
            latestInferredFrameId = -1L;
            clearCadenceCache();
        }

        private void clearCadenceCache() {
            synchronized (cadenceCacheLock) {
                cachedSrOutput = null;
                cadenceCachePixels = null;
            }
        }

        void publishPipelineSnapshot(String reason) {
            if (listener != null) {
                listener.onPipelineSnapshot(
                        pipelineTelemetry.snapshot(
                                nowNs(),
                                workerQueueDepth()),
                        reason);
            }
        }

        private int workerQueueDepth() {
            return VideoPipelineTelemetry.WORKER_QUEUE_CAPACITY
                    - frameQueueSlots.availablePermits();
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
            long receivedNs = nowNs();
            VideoPipelineTelemetry.FrameToken token;
            try {
                token = pipelineTelemetry.claimReadback(presentationTimeUs, receivedNs);
            } catch (Throwable failure) {
                resultFuture.setException(failure);
                return resultFuture;
            }
            int inputWidth = profile.inputWidth();
            int inputHeight = profile.inputHeight();
            if (image.width != inputWidth || image.height != inputHeight) {
                pipelineTelemetry.markDropped(
                        token,
                        nowNs(),
                        workerQueueDepth());
                resultFuture.setException(new IllegalArgumentException(
                        "QuickSR video input changed: " + image.width + "x" + image.height));
                return resultFuture;
            }

            // Copy synchronously before returning. Media3 may recycle/unmap Image.pixelBuffer as
            // soon as the returned future is cancelled or completed.
            FrameTimings timings = new FrameTimings(token);
            timings.inputCopyStartedNs = receivedNs;
            int inputPixels = checkedPixels(inputWidth, inputHeight);
            byte[] rgba = acquireInputBuffer(inputPixels * RGBA_BYTES_PER_PIXEL);
            try {
                ByteBuffer source = image.pixelBuffer.duplicate();
                source.position(0);
                source.get(rgba);
                timings.inputCopiedNs = nowNs();
                timings.inputHashStartedNs = timings.inputCopiedNs;
                timings.inputCrc32 = crc32Hex(rgba);
                timings.inputHashFinishedNs = nowNs();
            } catch (Throwable failure) {
                recycleInputBuffer(rgba);
                pipelineTelemetry.markDropped(
                        token,
                        nowNs(),
                        workerQueueDepth());
                resultFuture.setException(failure);
                return resultFuture;
            }
            try {
                enqueueFrameTask(() -> processCopiedFrame(
                        rgba,
                        timings,
                        resultFuture));
                pipelineTelemetry.observeQueueDepth(workerQueueDepth());
            } catch (RejectedExecutionException failure) {
                recycleInputBuffer(rgba);
                pipelineTelemetry.markDropped(
                        token,
                        nowNs(),
                        workerQueueDepth());
                resultFuture.setException(failure);
            }
            return resultFuture;
        }

        private void processCopiedFrame(
                byte[] rgba,
                FrameTimings timings,
                SettableFuture<FrameResult> resultFuture) {
            float[] outputTensor = null;
            boolean overlapTensorLeased = false;
            boolean handedToPostprocess = false;
            try {
                if (released || !pipelineTelemetry.isCurrent(timings.token)) {
                    pipelineTelemetry.markDropped(
                            timings.token,
                            nowNs(),
                            workerQueueDepth());
                    resultFuture.cancel(false);
                    return;
                }
                timings.workerStartedNs = nowNs();
                pipelineTelemetry.observeQueueDepth(workerQueueDepth());
                int inputPixels = checkedPixels(profile.inputWidth(), profile.inputHeight());
                int outputPixels = checkedPixels(profile.outputWidth(), profile.outputHeight());
                timings.cadenceMode = cadenceMode;
                timings.cadenceAnalysisStartedNs = nowNs();
                boolean currentCadenceStream = timings.token.cadenceStreamEpoch
                        == cadenceStreamEpoch;
                AnimeCadenceAnalyzer.Result cadence = currentCadenceStream
                        ? cadenceAnalyzer.analyze(
                                cadenceMode,
                                rgba,
                                profile.inputWidth(),
                                profile.inputHeight(),
                                timings.inputCrc32,
                                timings.token.cadenceStreamEpoch)
                        : cadenceAnalyzer.processWithoutState(
                                AnimeCadenceAnalyzer.Reason.STREAM_BOUNDARY);
                timings.cadenceAnalysisFinishedNs = nowNs();
                if (!pipelineTelemetry.isCurrent(timings.token)) {
                    pipelineTelemetry.markDropped(
                            timings.token,
                            timings.cadenceAnalysisFinishedNs,
                            workerQueueDepth());
                    resultFuture.cancel(false);
                    return;
                }
                if (cadence.decision == AnimeCadenceAnalyzer.Decision.REUSE) {
                    long expectedReferenceFrameId = latestInferredFrameId;
                    int expectedReferenceGeneration = latestInferredGeneration;
                    long expectedReferenceStreamEpoch = latestInferredStreamEpoch;
                    CachedSrOutput availableCache;
                    synchronized (cadenceCacheLock) {
                        availableCache = cachedSrOutput;
                    }
                    if (expectedReferenceFrameId < 0
                            || expectedReferenceGeneration != timings.token.generation
                            || expectedReferenceStreamEpoch
                                    != timings.token.cadenceStreamEpoch
                            || availableCache == null
                            || !cadenceCacheMatches(
                                    availableCache.generation,
                                    availableCache.streamEpoch,
                                    availableCache.sourceFrameId,
                                    expectedReferenceGeneration,
                                    expectedReferenceStreamEpoch,
                                    expectedReferenceFrameId)) {
                        cadence = cadenceAnalyzer.forceProcess(
                                cadence,
                                AnimeCadenceAnalyzer.Reason.CACHE_NOT_READY);
                    } else {
                        timings.cadenceDecision = cadence.decision;
                        timings.cadenceReason = cadence.reason;
                        timings.reuseStreak = cadence.reuseStreak;
                        timings.cadenceSceneScore = cadence.sceneScore;
                        timings.cadenceSubtitleScore = cadence.subtitleScore;
                        timings.cadenceMotionScore = cadence.motionScore;
                        timings.cadenceReferenceGeneration = expectedReferenceGeneration;
                        timings.cadenceReferenceStreamEpoch = expectedReferenceStreamEpoch;
                        timings.cadenceReferenceFrameId = expectedReferenceFrameId;
                        cadenceReusedCount++;
                        timings.cadenceProcessedCount = cadenceProcessedCount;
                        timings.cadenceReusedCount = cadenceReusedCount;
                        if (postprocessMode == PostprocessMode.OVERLAP) {
                            submitPostprocessTask(() -> completeReuse(
                                    rgba,
                                    timings,
                                    resultFuture,
                                    availableCache));
                        } else {
                            completeReuse(
                                rgba,
                                timings,
                                resultFuture,
                                availableCache);
                        }
                        handedToPostprocess = true;
                        return;
                    }
                }
                timings.cadenceDecision = cadence.decision;
                timings.cadenceReason = cadence.reason;
                timings.reuseStreak = cadence.reuseStreak;
                timings.cadenceSceneScore = cadence.sceneScore;
                timings.cadenceSubtitleScore = cadence.subtitleScore;
                timings.cadenceMotionScore = cadence.motionScore;
                if (inputTensorScratch == null) {
                    inputTensorScratch = new float[3 * inputPixels];
                    if (outputPackerMode == OutputPackerMode.JAVA) {
                        outputRgbaScratch = new byte[outputPixels * RGBA_BYTES_PER_PIXEL];
                    }
                }
                timings.outputTensorAcquireStartedNs = nowNs();
                if (postprocessMode == PostprocessMode.OVERLAP) {
                    outputTensor = acquireOverlapOutputTensor(3 * outputPixels, timings);
                    overlapTensorLeased = true;
                } else {
                    timings.outputTensorSlotAcquiredNs =
                            timings.outputTensorAcquireStartedNs;
                    if (outputTensorScratch == null) {
                        outputTensorScratch = new float[3 * outputPixels];
                    }
                    outputTensor = outputTensorScratch;
                }
                timings.outputTensorReadyNs = nowNs();
                rgbaToNchw(
                        rgba,
                        inputTensorScratch,
                        profile.inputWidth(),
                        profile.inputHeight());
                timings.preprocessFinishedNs = nowNs();
                QuickSrSession activeSession = ensureSession();
                timings.sessionReadyNs = nowNs();
                if (released || !pipelineTelemetry.isCurrent(timings.token)) {
                    pipelineTelemetry.markDropped(
                            timings.token,
                            timings.sessionReadyNs,
                            workerQueueDepth());
                    resultFuture.cancel(false);
                    return;
                }
                int candidateFrame = frameNumber + 1;
                if (captureSpec.isRequested()
                        && !evidenceCaptureReserved
                        && captureSpec.hasBeenMissedBy(
                                candidateFrame,
                                timings.token.presentationTimeUs)) {
                    throw new IllegalStateException(
                            "Requested video evidence selector was not observed before frame "
                                    + candidateFrame + " at ptsUs="
                                    + timings.token.presentationTimeUs);
                }
                timings.inferenceStartedNs = nowNs();
                activeSession.infer(inputTensorScratch, outputTensor, runTimings);
                timings.inferenceFinishedNs = nowNs();
                timings.tensorInputCopyNs = runTimings.inputCopyNs;
                timings.ortRunNs = runTimings.ortRunNs;
                timings.tensorOutputCopyNs = runTimings.outputCopyNs;
                timings.finiteScanNs = runTimings.finiteScanNs;
                timings.finiteScanExecuted = runTimings.finiteScanExecuted;
                if (!pipelineTelemetry.isCurrent(timings.token)) {
                    pipelineTelemetry.markDropped(
                            timings.token,
                            timings.inferenceFinishedNs,
                            workerQueueDepth());
                    resultFuture.cancel(false);
                    return;
                }
                if (captureSpec.isRequested()
                        && !evidenceCaptureReserved
                        && captureSpec.matches(
                                candidateFrame,
                                timings.token.presentationTimeUs)) {
                    evidenceCaptureReserved = true;
                    JSONObject evidence = VideoEvidenceStore.write(
                            context,
                            benchmarkRunId,
                            captureSpec,
                            candidateFrame,
                            timings.token.presentationTimeUs,
                            profile,
                            inputTensorScratch,
                            outputTensor,
                            activeSession.qnnStrictEvidence());
                    if (listener != null) {
                        listener.onEvidenceCaptured(evidence);
                    }
                }
                frameNumber = candidateFrame;
                cadenceProcessedCount++;
                timings.cadenceProcessedCount = cadenceProcessedCount;
                timings.cadenceReusedCount = cadenceReusedCount;
                if (timings.token.cadenceStreamEpoch == cadenceStreamEpoch) {
                    latestInferredGeneration = timings.token.generation;
                    latestInferredStreamEpoch = timings.token.cadenceStreamEpoch;
                    latestInferredFrameId = timings.token.frameId;
                    cadenceAnalyzer.markInferenceAvailable(timings.token.cadenceStreamEpoch);
                }
                if (postprocessMode == PostprocessMode.OVERLAP) {
                    float[] leasedOutputTensor = outputTensor;
                    submitPostprocessTask(() -> completePostprocess(
                            leasedOutputTensor,
                            rgba,
                            timings,
                            resultFuture,
                            true));
                    handedToPostprocess = true;
                } else {
                    completePostprocess(
                            outputTensor,
                            rgba,
                            timings,
                            resultFuture,
                            false);
                    handedToPostprocess = true;
                }
            } catch (Throwable failure) {
                if ((failure instanceof RejectedExecutionException
                        || failure instanceof InterruptedException)
                        && (released || !pipelineTelemetry.isCurrent(timings.token))) {
                    pipelineTelemetry.markDropped(
                            timings.token,
                            nowNs(),
                            workerQueueDepth());
                    resultFuture.cancel(false);
                } else {
                    failFrame("video-inference", timings, resultFuture, failure);
                }
            } finally {
                if (!handedToPostprocess) {
                    recycleInputBuffer(rgba);
                    if (overlapTensorLeased) {
                        recycleOverlapOutputTensor(outputTensor);
                    }
                }
            }
        }

        private void submitPostprocessTask(Runnable task) {
            ThreadPoolExecutor target;
            synchronized (lifecycleLock) {
                if (released || postprocessExecutor == null) {
                    throw new RejectedExecutionException(
                            "QuickSR video effect released before postprocess enqueue");
                }
                target = postprocessExecutor;
            }
            // execute() may apply bounded backpressure. It must not run under lifecycleLock,
            // otherwise release cannot set released or shut down a full postprocess queue.
            target.execute(task);
        }

        private void completeReuse(
                byte[] rgba,
                FrameTimings timings,
                SettableFuture<FrameResult> resultFuture,
                CachedSrOutput cached) {
            try {
                if (released || !pipelineTelemetry.isCurrent(timings.token)) {
                    pipelineTelemetry.markDropped(
                            timings.token,
                            nowNs(),
                            workerQueueDepth());
                    resultFuture.cancel(false);
                    return;
                }
                if (cached.generation != timings.token.generation
                        || cached.generation != timings.cadenceReferenceGeneration
                        || cached.streamEpoch != timings.token.cadenceStreamEpoch
                        || cached.streamEpoch != timings.cadenceReferenceStreamEpoch
                        || cached.sourceFrameId != timings.cadenceReferenceFrameId) {
                    pipelineTelemetry.markDropped(
                            timings.token,
                            nowNs(),
                            workerQueueDepth());
                    resultFuture.cancel(false);
                    return;
                }
                long zeroStageNs = nowNs();
                timings.outputTensorAcquireStartedNs = zeroStageNs;
                timings.outputTensorSlotAcquiredNs = zeroStageNs;
                timings.outputTensorReadyNs = zeroStageNs;
                timings.preprocessFinishedNs = zeroStageNs;
                timings.sessionReadyNs = zeroStageNs;
                timings.inferenceStartedNs = zeroStageNs;
                timings.inferenceFinishedNs = zeroStageNs;
                timings.outputPackStartedNs = zeroStageNs;
                timings.outputPackFinishedNs = zeroStageNs;
                timings.outputHashStartedNs = zeroStageNs;
                timings.outputHashFinishedNs = zeroStageNs;
                timings.outputCrc32 = cached.outputCrc32;
                timings.directBufferCopyStartedNs = nowNs();
                ByteBuffer directRgba = acquireOutputBuffer();
                directRgba.put(cached.pixels);
                directRgba.flip();
                timings.directBufferCopyFinishedNs = nowNs();
                timings.outputReadyNs = timings.directBufferCopyFinishedNs;
                if (released || !pipelineTelemetry.isCurrent(timings.token)) {
                    recycleOutputBuffer(directRgba);
                    pipelineTelemetry.markDropped(
                            timings.token,
                            timings.outputReadyNs,
                            workerQueueDepth());
                    resultFuture.cancel(false);
                    return;
                }
                FrameResult frameResult = new FrameResult(
                        directRgba,
                        timings,
                        this::recycleFrameResult);
                synchronized (outputBufferPoolLock) {
                    outstandingFrameResults.add(frameResult);
                }
                if (!resultFuture.set(frameResult)) {
                    frameResult.recycle();
                }
            } catch (Throwable failure) {
                failFrame("video-cadence-reuse", timings, resultFuture, failure);
            } finally {
                recycleInputBuffer(rgba);
            }
        }

        private void completePostprocess(
                float[] outputTensor,
                byte[] rgba,
                FrameTimings timings,
                SettableFuture<FrameResult> resultFuture,
                boolean recycleOutputTensor) {
            ByteBuffer directRgba = null;
            try {
                if (released || !pipelineTelemetry.isCurrent(timings.token)) {
                    pipelineTelemetry.markDropped(
                            timings.token,
                            nowNs(),
                            workerQueueDepth());
                    resultFuture.cancel(false);
                    return;
                }
                timings.outputPackStartedNs = nowNs();
                if (outputPackerMode == OutputPackerMode.NATIVE_NEON) {
                    directRgba = acquireOutputBuffer();
                    NativeOutputPacker.pack(
                            outputTensor,
                            rgba,
                            profile.inputWidth(),
                            profile.inputHeight(),
                            profile.outputWidth(),
                            profile.outputHeight(),
                            directRgba);
                } else {
                    packNchwToRgba(
                            outputTensor,
                            rgba,
                            profile.inputWidth(),
                            profile.inputHeight(),
                            profile.outputWidth(),
                            profile.outputHeight(),
                            outputRgbaScratch);
                }
                timings.outputPackFinishedNs = nowNs();
                timings.outputHashStartedNs = timings.outputPackFinishedNs;
                timings.outputCrc32 = outputPackerMode == OutputPackerMode.NATIVE_NEON
                        ? crc32Hex(directRgba)
                        : crc32Hex(outputRgbaScratch);
                timings.outputHashFinishedNs = nowNs();
                timings.directBufferCopyStartedNs = timings.outputHashFinishedNs;
                if (outputPackerMode == OutputPackerMode.JAVA) {
                    directRgba = acquireOutputBuffer();
                    directRgba.put(outputRgbaScratch);
                    directRgba.flip();
                }
                timings.directBufferCopyFinishedNs = nowNs();
                timings.outputReadyNs = timings.directBufferCopyFinishedNs;
                if (released || !pipelineTelemetry.isCurrent(timings.token)) {
                    // A release/flush may race a CPU pack already in progress. Recheck after the
                    // last expensive stage so an old generation can never reach Media3 at a new
                    // playback position.
                    recycleOutputBuffer(directRgba);
                    directRgba = null;
                    pipelineTelemetry.markDropped(
                            timings.token,
                            timings.outputReadyNs,
                            workerQueueDepth());
                    resultFuture.cancel(false);
                    return;
                }
                if (cadenceMode != AnimeCadenceAnalyzer.Mode.OFF
                        && timings.token.cadenceStreamEpoch == cadenceStreamEpoch) {
                    synchronized (cadenceCacheLock) {
                        cadenceCachePixels = copyIntoCadenceCache(
                                directRgba,
                                cadenceCachePixels);
                        cachedSrOutput = new CachedSrOutput(
                                cadenceCachePixels,
                                timings.token.generation,
                                timings.token.cadenceStreamEpoch,
                                timings.token.frameId,
                                timings.outputCrc32);
                    }
                }
                FrameResult frameResult = new FrameResult(
                        directRgba,
                        timings,
                        this::recycleFrameResult);
                directRgba = null;
                synchronized (outputBufferPoolLock) {
                    outstandingFrameResults.add(frameResult);
                }
                if (!resultFuture.set(frameResult)) {
                    frameResult.recycle();
                }
            } catch (Throwable failure) {
                failFrame("video-postprocess", timings, resultFuture, failure);
            } finally {
                recycleOutputBuffer(directRgba);
                recycleInputBuffer(rgba);
                if (recycleOutputTensor) {
                    recycleOverlapOutputTensor(outputTensor);
                }
            }
        }

        private void failFrame(
                String stage,
                FrameTimings timings,
                SettableFuture<FrameResult> resultFuture,
                Throwable failure) {
            pipelineTelemetry.markDropped(
                    timings.token,
                    nowNs(),
                    workerQueueDepth());
            if (listener != null) {
                try {
                    listener.onProcessingError(stage, failure);
                } catch (Throwable ignored) {
                    // Preserve the original pipeline failure as the Media3 error.
                }
            }
            resultFuture.setException(failure);
        }

        private float[] acquireOverlapOutputTensor(int valueCount) throws InterruptedException {
            return acquireOverlapOutputTensor(valueCount, null);
        }

        private float[] acquireOverlapOutputTensor(
                int valueCount,
                FrameTimings timings) throws InterruptedException {
            boolean acquired = false;
            while (!acquired) {
                if (released) {
                    throw new InterruptedException(
                            "QuickSR video effect released while waiting for output tensor");
                }
                acquired = outputTensorSlots.tryAcquire(100L, TimeUnit.MILLISECONDS);
            }
            if (timings != null) {
                timings.outputTensorSlotAcquiredNs = nowNs();
            }
            boolean ownershipTransferred = false;
            try {
                synchronized (outputTensorPoolLock) {
                    float[] pooled = outputTensorPool.pollFirst();
                    float[] result = pooled != null && pooled.length == valueCount
                            ? pooled
                            : new float[valueCount];
                    ownershipTransferred = true;
                    return result;
                }
            } finally {
                // The caller owns the permit only after a tensor is returned. Allocation failure
                // (including OOM) must not permanently consume an overlap slot.
                if (!ownershipTransferred) {
                    outputTensorSlots.release();
                }
            }
        }

        private void recycleOverlapOutputTensor(float[] outputTensor) {
            if (outputTensor == null) {
                return;
            }
            synchronized (outputTensorPoolLock) {
                if (!released && outputTensorPool.size() < OVERLAP_OUTPUT_TENSOR_SLOTS) {
                    outputTensorPool.addFirst(outputTensor);
                }
            }
            outputTensorSlots.release();
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
            if (result.timings != null) {
                pipelineTelemetry.markDropped(
                        result.timings.token,
                        nowNs(),
                        workerQueueDepth());
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
                if (result.timings != null
                        && !pipelineTelemetry.isCurrent(result.timings.token)) {
                    pipelineTelemetry.markDropped(
                            result.timings.token,
                            nowNs(),
                            workerQueueDepth());
                    throw new VideoFrameProcessingException(
                            "Refusing stale QuickSR output from generation "
                                    + result.timings.token.generation,
                            null,
                            presentationTimeUs);
                }
                if (result.timings != null
                        && result.timings.token.presentationTimeUs != presentationTimeUs) {
                    pipelineTelemetry.markDropped(
                            result.timings.token,
                            nowNs(),
                            workerQueueDepth());
                    throw new VideoFrameProcessingException(
                            "QuickSR output PTS identity mismatch: token="
                                    + result.timings.token.presentationTimeUs
                                    + ", callback=" + presentationTimeUs,
                            null,
                            presentationTimeUs);
                }
                if (result.timings != null) {
                    result.timings.glUploadStartedNs = nowNs();
                }
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
                if (result.timings != null) {
                    result.timings.glUploadFinishedNs = nowNs();
                    // Returning from this callback is only a Media3 output-submit proxy. It does
                    // not measure GPU completion, SurfaceFlinger latch or final display.
                    result.timings.outputSubmittedProxyNs =
                            result.timings.glUploadFinishedNs;
                    VideoPipelineTelemetry.Completion completion =
                            pipelineTelemetry.markProcessed(
                                    result.timings.token,
                                    result.timings.outputSubmittedProxyNs,
                                    workerQueueDepth());
                    if (!completion.processed) {
                        return;
                    }
                    if (listener != null) {
                        listener.onFrameProcessed(new FrameStats(
                                mode,
                                tuning,
                                profile,
                                postprocessMode,
                                outputPackerMode,
                                inputWidth,
                                inputHeight,
                                result.timings,
                                completion));
                    }
                }
            } catch (GlUtil.GlException failure) {
                if (result.timings != null) {
                    pipelineTelemetry.markDropped(
                            result.timings.token,
                            nowNs(),
                            workerQueueDepth());
                }
                if (listener != null) {
                    listener.onProcessingError("gl-upload-output-submit-proxy", failure);
                }
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
            long releaseStartedNs = System.nanoTime();
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
            pipelineTelemetry.release(
                    nowNs(),
                    workerQueueDepth());
            recycleOutstandingFrameResults();
            synchronized (inputBufferPoolLock) {
                inputBufferPool.clear();
            }
            synchronized (outputBufferPoolLock) {
                outputBufferPool.clear();
            }
            synchronized (outputTensorPoolLock) {
                outputTensorPool.clear();
            }
            clearCadenceCache();
            latestInferredGeneration = Integer.MIN_VALUE;
            latestInferredStreamEpoch = -1L;
            latestInferredFrameId = -1L;
            boolean postprocessTerminated = postprocessExecutor == null;
            if (postprocessExecutor != null) {
                postprocessExecutor.shutdown();
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
                if (Thread.currentThread() == inferenceThread) {
                    // Defensive path: avoid self-deadlock if a future Media3 version releases its
                    // processor from the worker. Queued frame tasks will observe released=true.
                    closeSessionOnExecutor();
                } else {
                    WorkerCleanupTask cleanupTask = new WorkerCleanupTask();
                    inferenceExecutor.execute(cleanupTask);
                    workerCleanupQueued = true;
                    cleanupFuture = cleanupTask;
                }
            } catch (Throwable caught) {
                failure = append(failure, caught);
            } finally {
                // Graceful shutdown keeps accepted frame tasks and the cleanup marker in FIFO
                // order. Every frame task remains responsible for its own future and buffers.
                inferenceExecutor.shutdown();
            }
            if (cleanupFuture != null) {
                try {
                    cleanupFuture.get(releaseTimeoutMs, TimeUnit.MILLISECONDS);
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
            if (postprocessExecutor != null) {
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - releaseStartedNs);
                long remainingMs = Math.max(1L, releaseTimeoutMs - elapsedMs);
                try {
                    postprocessTerminated = postprocessExecutor.awaitTermination(
                            remainingMs,
                            TimeUnit.MILLISECONDS);
                    if (!postprocessTerminated) {
                        failure = append(
                                failure,
                                new TimeoutException(
                                        "QuickSR postprocess executor did not terminate"));
                        Thread worker = postprocessThread;
                        if (worker != null) {
                            worker.interrupt();
                        }
                    }
                } catch (InterruptedException caught) {
                    Thread.currentThread().interrupt();
                    failure = append(failure, caught);
                }
            }
            if (postprocessTerminated) {
                // An active postprocess task may already be inside packNchwToRgba when release
                // flips released=true. Keep its shared scratch alive until the executor stops.
                outputRgbaScratch = null;
            }
            // Cover a worker that completed concurrently with the first reclamation pass.
            recycleOutstandingFrameResults();
            try {
                publishPipelineSnapshot("release");
            } catch (Throwable caught) {
                failure = append(failure, caught);
            }
            if (failure != null) {
                throw new VideoFrameProcessingException(
                        "Unable to release the QuickSR video effect",
                        failure);
            }
        }

        private void closeSessionOnExecutor() throws Exception {
            workerCleanupRunCount.incrementAndGet();
            Throwable failure = null;
            try {
                if (session != null) {
                    session.close();
                    session = null;
                }
            } catch (Throwable caught) {
                failure = caught;
            }
            try {
                if (qnnLockHeld) {
                    QnnPluginRuntime.unlockProcess();
                }
            } catch (Throwable caught) {
                failure = append(failure, caught);
            } finally {
                qnnLockHeld = false;
                inputTensorScratch = null;
                outputTensorScratch = null;
                synchronized (outputTensorPoolLock) {
                    outputTensorPool.clear();
                }
                workerCleanupCompleted = true;
            }
            try {
                publishPipelineSnapshot("worker_cleanup_complete");
            } catch (Throwable caught) {
                failure = append(failure, caught);
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

    static String crc32Hex(byte[] value) {
        CRC32 crc32 = new CRC32();
        crc32.update(value, 0, value.length);
        return String.format("%08x", crc32.getValue());
    }

    static String crc32Hex(ByteBuffer value) {
        ByteBuffer bytes = value.duplicate();
        bytes.position(0);
        CRC32 crc32 = new CRC32();
        crc32.update(bytes);
        return String.format("%08x", crc32.getValue());
    }

    static byte[] copyIntoCadenceCache(ByteBuffer source, byte[] existing) {
        if (source == null) {
            throw new IllegalArgumentException("Cadence cache source is required");
        }
        ByteBuffer bytes = source.duplicate();
        bytes.position(0);
        byte[] target = existing != null && existing.length == bytes.remaining()
                ? existing
                : new byte[bytes.remaining()];
        bytes.get(target);
        return target;
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
