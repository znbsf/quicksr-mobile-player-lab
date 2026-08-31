package dev.aisystems.quicksrplayerlab;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Slow, frame-accurate QuickSR 2x export for local videos.
 *
 * <p>The first implementation deliberately emits a video-only MP4. It never labels audio as
 * preserved because source-track passthrough has not yet been qualified.</p>
 */
final class OfflineQuickSrVideoExporter {
    interface CancellationSignal {
        boolean isCancelled();
    }

    interface ProgressListener {
        void onProgress(int completedFrames, int totalFrames, int completedTiles, int totalTiles);
    }

    static final class Result {
        final int inputWidth;
        final int inputHeight;
        final int outputWidth;
        final int outputHeight;
        final int frameCount;
        final int quickSrRunCount;
        final long elapsedMs;
        final String backend;
        final boolean audioPreserved;

        Result(
                int inputWidth,
                int inputHeight,
                int outputWidth,
                int outputHeight,
                int frameCount,
                int quickSrRunCount,
                long elapsedMs,
                String backend) {
            this.inputWidth = inputWidth;
            this.inputHeight = inputHeight;
            this.outputWidth = outputWidth;
            this.outputHeight = outputHeight;
            this.frameCount = frameCount;
            this.quickSrRunCount = quickSrRunCount;
            this.elapsedMs = elapsedMs;
            this.backend = backend;
            this.audioPreserved = false;
        }
    }

    private static final String OUTPUT_MIME = "video/avc";
    private static final long CODEC_TIMEOUT_US = 10_000L;
    private static final long EOS_DRAIN_TIMEOUT_MS = 30_000L;

    private OfflineQuickSrVideoExporter() {
    }

    static Result export(
            Context context,
            Uri inputUri,
            Uri outputUri,
            QuickSrSession.Mode mode,
            CancellationSignal cancellationSignal,
            ProgressListener progressListener) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            throw new UnsupportedOperationException("QuickSR video export requires Android 9+");
        }
        Context appContext = context.getApplicationContext();
        checkCancelled(cancellationSignal);
        long[] presentationTimesUs = inspectTimeline(appContext, inputUri);
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Bitmap firstFrame = null;
        boolean qnnLocked = mode == QuickSrSession.Mode.QNN_HTP;
        long startedMs = SystemClock.elapsedRealtime();
        try {
            retriever.setDataSource(appContext, inputUri);
            int metadataFrameCount = parsePositiveInt(
                    retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT),
                    "video frame count");
            presentationTimesUs = VideoFrameTimeline.normalize(
                    toBoxedList(presentationTimesUs),
                    metadataFrameCount);
            final int totalFrameCount = presentationTimesUs.length;

            MediaMetadataRetriever.BitmapParams bitmapParams =
                    new MediaMetadataRetriever.BitmapParams();
            bitmapParams.setPreferredConfig(Bitmap.Config.ARGB_8888);
            firstFrame = requireArgb8888(
                    requireFrame(retriever.getFrameAtIndex(0, bitmapParams), 0));
            int inputWidth = firstFrame.getWidth();
            int inputHeight = firstFrame.getHeight();
            int outputWidth = Math.multiplyExact(inputWidth, TilePlan.SCALE);
            int outputHeight = Math.multiplyExact(inputHeight, TilePlan.SCALE);
            long outputPixels = (long) outputWidth * outputHeight;
            if (outputPixels > FullImageUpscaler.MAX_OUTPUT_PIXELS) {
                throw new IllegalArgumentException(
                        "2x video output is too large for this build: " +
                                outputWidth + "x" + outputHeight);
            }

            if (qnnLocked) {
                QnnPluginRuntime.lockProcess();
            }
            try (QuickSrSession session = QuickSrSession.open(
                    appContext,
                    mode,
                    ReceiptStore.newRunId());
                 EncoderSink encoder = EncoderSink.open(
                         appContext,
                         outputUri,
                         outputWidth,
                         outputHeight,
                         VideoFrameTimeline.estimateFrameRate(presentationTimesUs))) {
                int totalRuns = 0;
                for (int frameIndex = 0;
                     frameIndex < presentationTimesUs.length;
                     frameIndex++) {
                    checkCancelled(cancellationSignal);
                    Bitmap decoded = frameIndex == 0
                            ? firstFrame
                            : requireArgb8888(requireFrame(
                                    retriever.getFrameAtIndex(frameIndex, bitmapParams),
                                    frameIndex));
                    if (frameIndex == 0) {
                        firstFrame = null;
                    }
                    Bitmap normalized = normalizeFrameSize(decoded, inputWidth, inputHeight);
                    try {
                        final int currentFrame = frameIndex;
                        FullImageUpscaler.Result upscaled =
                                FullImageUpscaler.upscaleWithOpenSession(
                                        normalized,
                                        session,
                                        (completedTiles, totalTiles) -> {
                                            if (progressListener != null) {
                                                progressListener.onProgress(
                                                        currentFrame,
                                                        totalFrameCount,
                                                        completedTiles,
                                                        totalTiles);
                                            }
                                        });
                        try {
                            checkCancelled(cancellationSignal);
                            encoder.addFrame(
                                    upscaled.bitmap,
                                    presentationTimesUs[frameIndex]);
                            totalRuns += upscaled.runCount;
                        } finally {
                            upscaled.bitmap.recycle();
                        }
                    } finally {
                        normalized.recycle();
                    }
                    if (progressListener != null) {
                        progressListener.onProgress(
                                frameIndex + 1,
                                totalFrameCount,
                                0,
                                0);
                    }
                }
                encoder.finish();
                return new Result(
                        inputWidth,
                        inputHeight,
                        outputWidth,
                        outputHeight,
                        presentationTimesUs.length,
                        totalRuns,
                        SystemClock.elapsedRealtime() - startedMs,
                        session.backendLabel());
            } finally {
                if (qnnLocked) {
                    QnnPluginRuntime.unlockProcess();
                }
            }
        } finally {
            if (firstFrame != null && !firstFrame.isRecycled()) {
                firstFrame.recycle();
            }
            retriever.release();
        }
    }

    private static long[] inspectTimeline(Context context, Uri inputUri) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, inputUri, null);
            int videoTrack = -1;
            for (int index = 0; index < extractor.getTrackCount(); index++) {
                MediaFormat format = extractor.getTrackFormat(index);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrack = index;
                    break;
                }
            }
            if (videoTrack < 0) {
                throw new IllegalArgumentException("Selected document has no video track");
            }
            extractor.selectTrack(videoTrack);
            List<Long> timestamps = new ArrayList<>();
            while (true) {
                long sampleTimeUs = extractor.getSampleTime();
                if (sampleTimeUs < 0) {
                    break;
                }
                timestamps.add(sampleTimeUs);
                if (!extractor.advance()) {
                    break;
                }
            }
            if (timestamps.isEmpty()) {
                throw new IllegalArgumentException("Video track contains no decodable samples");
            }
            long[] result = new long[timestamps.size()];
            for (int index = 0; index < timestamps.size(); index++) {
                result[index] = timestamps.get(index);
            }
            return result;
        } finally {
            extractor.release();
        }
    }

    private static List<Long> toBoxedList(long[] values) {
        List<Long> result = new ArrayList<>(values.length);
        for (long value : values) {
            result.add(value);
        }
        return result;
    }

    private static Bitmap requireFrame(Bitmap frame, int index) {
        if (frame == null) {
            throw new IllegalStateException("Decoder returned no bitmap for frame " + index);
        }
        return frame;
    }

    private static Bitmap requireArgb8888(Bitmap source) {
        if (source.getConfig() == Bitmap.Config.ARGB_8888) {
            return source;
        }
        Bitmap converted = source.copy(Bitmap.Config.ARGB_8888, false);
        source.recycle();
        if (converted == null) {
            throw new IllegalStateException("Could not convert decoded frame to ARGB_8888");
        }
        return converted;
    }

    private static Bitmap normalizeFrameSize(Bitmap source, int width, int height) {
        if (source.getWidth() == width && source.getHeight() == height) {
            return source;
        }
        Bitmap resized = Bitmap.createScaledBitmap(source, width, height, true);
        if (resized != source) {
            source.recycle();
        }
        return resized;
    }

    private static int parsePositiveInt(String value, String label) {
        if (value == null) {
            throw new IllegalArgumentException("Missing " + label + " metadata");
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new NumberFormatException("not positive");
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid " + label + ": " + value, failure);
        }
    }

    private static void checkCancelled(CancellationSignal cancellationSignal)
            throws InterruptedException {
        if (Thread.currentThread().isInterrupted() ||
                (cancellationSignal != null && cancellationSignal.isCancelled())) {
            throw new InterruptedException("QuickSR video export was cancelled");
        }
    }

    private static final class EncoderSink implements AutoCloseable {
        private final ParcelFileDescriptor outputDescriptor;
        private final MediaMuxer muxer;
        private final MediaCodec codec;
        private final Surface codecSurface;
        private final EglBitmapRenderer renderer;
        private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        private boolean codecStarted;
        private boolean muxerStarted;
        private boolean finished;
        private int muxerTrack = -1;

        static EncoderSink open(
                Context context,
                Uri outputUri,
                int width,
                int height,
                int frameRate) throws Exception {
            ParcelFileDescriptor descriptor = context.getContentResolver()
                    .openFileDescriptor(outputUri, "rwt");
            if (descriptor == null) {
                throw new IOException("Could not open the destination MP4");
            }
            MediaMuxer muxer = null;
            MediaCodec codec = null;
            Surface surface = null;
            EglBitmapRenderer renderer = null;
            try {
                muxer = new MediaMuxer(
                        descriptor.getFileDescriptor(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                MediaFormat format = MediaFormat.createVideoFormat(OUTPUT_MIME, width, height);
                format.setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                long requestedBitrate = Math.max(
                        1_500_000L,
                        Math.min(80_000_000L, (long) width * height * 4L));
                format.setInteger(MediaFormat.KEY_BIT_RATE, (int) requestedBitrate);
                format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                codec = MediaCodec.createEncoderByType(OUTPUT_MIME);
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                surface = codec.createInputSurface();
                codec.start();
                renderer = new EglBitmapRenderer(surface, width, height);
                EncoderSink result = new EncoderSink(
                        descriptor,
                        muxer,
                        codec,
                        surface,
                        renderer);
                result.codecStarted = true;
                return result;
            } catch (Throwable failure) {
                if (renderer != null) {
                    renderer.close();
                }
                if (surface != null) {
                    surface.release();
                }
                if (codec != null) {
                    try {
                        codec.stop();
                    } catch (Throwable ignored) {
                    }
                    codec.release();
                }
                if (muxer != null) {
                    muxer.release();
                }
                descriptor.close();
                throw failure;
            }
        }

        private EncoderSink(
                ParcelFileDescriptor outputDescriptor,
                MediaMuxer muxer,
                MediaCodec codec,
                Surface codecSurface,
                EglBitmapRenderer renderer) {
            this.outputDescriptor = outputDescriptor;
            this.muxer = muxer;
            this.codec = codec;
            this.codecSurface = codecSurface;
            this.renderer = renderer;
        }

        void addFrame(Bitmap bitmap, long presentationTimeUs) {
            if (finished) {
                throw new IllegalStateException("Encoder is already finished");
            }
            drain(false);
            renderer.draw(bitmap, presentationTimeUs);
            drain(false);
        }

        void finish() {
            if (finished) {
                return;
            }
            codec.signalEndOfInputStream();
            drain(true);
            finished = true;
        }

        private void drain(boolean waitForEndOfStream) {
            long deadlineMs = SystemClock.elapsedRealtime() + EOS_DRAIN_TIMEOUT_MS;
            while (true) {
                int status = codec.dequeueOutputBuffer(
                        bufferInfo,
                        waitForEndOfStream ? CODEC_TIMEOUT_US : 0L);
                if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!waitForEndOfStream) {
                        return;
                    }
                    if (SystemClock.elapsedRealtime() >= deadlineMs) {
                        throw new IllegalStateException("Timed out draining the video encoder");
                    }
                    continue;
                }
                if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) {
                        throw new IllegalStateException("Encoder output format changed twice");
                    }
                    muxerTrack = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                    continue;
                }
                if (status == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    continue;
                }
                if (status < 0) {
                    continue;
                }
                ByteBuffer encoded = codec.getOutputBuffer(status);
                if (encoded == null) {
                    codec.releaseOutputBuffer(status, false);
                    throw new IllegalStateException("Encoder output buffer is null");
                }
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0;
                }
                if (bufferInfo.size > 0) {
                    if (!muxerStarted) {
                        codec.releaseOutputBuffer(status, false);
                        throw new IllegalStateException("Encoder sample arrived before format");
                    }
                    encoded.position(bufferInfo.offset);
                    encoded.limit(bufferInfo.offset + bufferInfo.size);
                    muxer.writeSampleData(muxerTrack, encoded, bufferInfo);
                }
                boolean endOfStream =
                        (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                codec.releaseOutputBuffer(status, false);
                if (endOfStream) {
                    return;
                }
            }
        }

        @Override
        public void close() throws Exception {
            Throwable failure = null;
            try {
                renderer.close();
            } catch (Throwable caught) {
                failure = caught;
            }
            codecSurface.release();
            if (codecStarted) {
                try {
                    codec.stop();
                } catch (Throwable caught) {
                    failure = appendFailure(failure, caught);
                }
            }
            codec.release();
            if (muxerStarted) {
                try {
                    muxer.stop();
                } catch (Throwable caught) {
                    failure = appendFailure(failure, caught);
                }
            }
            muxer.release();
            try {
                outputDescriptor.close();
            } catch (Throwable caught) {
                failure = appendFailure(failure, caught);
            }
            if (failure != null) {
                if (failure instanceof Exception) {
                    throw (Exception) failure;
                }
                throw new RuntimeException(failure);
            }
        }

        private static Throwable appendFailure(Throwable current, Throwable next) {
            if (current == null) {
                return next;
            }
            if (current != next) {
                current.addSuppressed(next);
            }
            return current;
        }
    }

    private static final class EglBitmapRenderer implements AutoCloseable {
        private static final float[] VERTICES = new float[]{
                -1.0f, -1.0f,
                1.0f, -1.0f,
                -1.0f, 1.0f,
                1.0f, 1.0f
        };
        // Android Bitmap row zero is the image top; flip the uploaded texture vertically.
        private static final float[] TEX_COORDS = new float[]{
                0.0f, 1.0f,
                1.0f, 1.0f,
                0.0f, 0.0f,
                1.0f, 0.0f
        };
        private static final String VERTEX_SHADER =
                "attribute vec4 aPosition;\n" +
                        "attribute vec2 aTexCoord;\n" +
                        "varying vec2 vTexCoord;\n" +
                        "void main() {\n" +
                        "  gl_Position = aPosition;\n" +
                        "  vTexCoord = aTexCoord;\n" +
                        "}\n";
        private static final String FRAGMENT_SHADER =
                "precision mediump float;\n" +
                        "uniform sampler2D uTexture;\n" +
                        "varying vec2 vTexCoord;\n" +
                        "void main() {\n" +
                        "  gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
                        "}\n";

        private final int width;
        private final int height;
        private final FloatBuffer vertexBuffer = floatBuffer(VERTICES);
        private final FloatBuffer textureBuffer = floatBuffer(TEX_COORDS);
        private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        private EGLContext context = EGL14.EGL_NO_CONTEXT;
        private EGLSurface surface = EGL14.EGL_NO_SURFACE;
        private int program;
        private int texture;
        private int positionLocation;
        private int textureCoordinateLocation;
        private int samplerLocation;

        EglBitmapRenderer(Surface encoderSurface, int width, int height) {
            this.width = width;
            this.height = height;
            try {
                initializeEgl(encoderSurface);
                initializeGl();
            } catch (Throwable failure) {
                close();
                throw failure;
            }
        }

        void draw(Bitmap bitmap, long presentationTimeUs) {
            if (bitmap.getWidth() != width || bitmap.getHeight() != height) {
                throw new IllegalArgumentException("Encoded bitmap dimensions changed");
            }
            GLES20.glViewport(0, 0, width, height);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            GLES20.glUniform1i(samplerLocation, 0);
            vertexBuffer.position(0);
            GLES20.glVertexAttribPointer(
                    positionLocation,
                    2,
                    GLES20.GL_FLOAT,
                    false,
                    0,
                    vertexBuffer);
            GLES20.glEnableVertexAttribArray(positionLocation);
            textureBuffer.position(0);
            GLES20.glVertexAttribPointer(
                    textureCoordinateLocation,
                    2,
                    GLES20.GL_FLOAT,
                    false,
                    0,
                    textureBuffer);
            GLES20.glEnableVertexAttribArray(textureCoordinateLocation);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            checkGlError("draw QuickSR frame");
            if (!EGLExt.eglPresentationTimeANDROID(
                    display,
                    surface,
                    Math.multiplyExact(presentationTimeUs, 1_000L))) {
                throw new IllegalStateException("eglPresentationTimeANDROID failed");
            }
            if (!EGL14.eglSwapBuffers(display, surface)) {
                throw new IllegalStateException(
                        "eglSwapBuffers failed: 0x" +
                                Integer.toHexString(EGL14.eglGetError()));
            }
        }

        private void initializeEgl(Surface encoderSurface) {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == EGL14.EGL_NO_DISPLAY) {
                throw new IllegalStateException("No EGL display");
            }
            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                throw new IllegalStateException("Could not initialize EGL");
            }
            int[] configAttributes = new int[]{
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGLExt.EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] configCount = new int[1];
            if (!EGL14.eglChooseConfig(
                    display,
                    configAttributes,
                    0,
                    configs,
                    0,
                    configs.length,
                    configCount,
                    0) || configCount[0] == 0) {
                throw new IllegalStateException("No recordable EGL config");
            }
            int[] contextAttributes = new int[]{
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE
            };
            context = EGL14.eglCreateContext(
                    display,
                    configs[0],
                    EGL14.EGL_NO_CONTEXT,
                    contextAttributes,
                    0);
            checkEglObject(context != EGL14.EGL_NO_CONTEXT, "eglCreateContext");
            int[] surfaceAttributes = new int[]{EGL14.EGL_NONE};
            surface = EGL14.eglCreateWindowSurface(
                    display,
                    configs[0],
                    encoderSurface,
                    surfaceAttributes,
                    0);
            checkEglObject(surface != EGL14.EGL_NO_SURFACE, "eglCreateWindowSurface");
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                throw new IllegalStateException("eglMakeCurrent failed");
            }
        }

        private void initializeGl() {
            int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
            int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertexShader);
            GLES20.glAttachShader(program, fragmentShader);
            GLES20.glLinkProgram(program);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
            GLES20.glDeleteShader(vertexShader);
            GLES20.glDeleteShader(fragmentShader);
            if (linkStatus[0] == 0) {
                String log = GLES20.glGetProgramInfoLog(program);
                GLES20.glDeleteProgram(program);
                program = 0;
                throw new IllegalStateException("Could not link bitmap shader: " + log);
            }
            positionLocation = GLES20.glGetAttribLocation(program, "aPosition");
            textureCoordinateLocation = GLES20.glGetAttribLocation(program, "aTexCoord");
            samplerLocation = GLES20.glGetUniformLocation(program, "uTexture");
            int[] textureIds = new int[1];
            GLES20.glGenTextures(1, textureIds, 0);
            texture = textureIds[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_NEAREST);
            GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_NEAREST);
            GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE);
            int[] maximumTextureSize = new int[1];
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maximumTextureSize, 0);
            if (width > maximumTextureSize[0] || height > maximumTextureSize[0]) {
                throw new IllegalArgumentException(
                        "QuickSR output exceeds GPU texture limit " + maximumTextureSize[0]);
            }
            checkGlError("initialize bitmap renderer");
        }

        private static int compileShader(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] status = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                String log = GLES20.glGetShaderInfoLog(shader);
                GLES20.glDeleteShader(shader);
                throw new IllegalStateException("Could not compile bitmap shader: " + log);
            }
            return shader;
        }

        private static FloatBuffer floatBuffer(float[] values) {
            FloatBuffer buffer = ByteBuffer
                    .allocateDirect(values.length * Float.BYTES)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            buffer.put(values);
            buffer.position(0);
            return buffer;
        }

        private static void checkEglObject(boolean success, String operation) {
            if (!success) {
                throw new IllegalStateException(
                        operation + " failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
            }
        }

        private static void checkGlError(String operation) {
            int error = GLES20.glGetError();
            if (error != GLES20.GL_NO_ERROR) {
                throw new IllegalStateException(
                        operation + " failed: 0x" + Integer.toHexString(error));
            }
        }

        @Override
        public void close() {
            if (display == EGL14.EGL_NO_DISPLAY) {
                return;
            }
            if (context != EGL14.EGL_NO_CONTEXT && surface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(display, surface, surface, context);
                if (texture != 0) {
                    GLES20.glDeleteTextures(1, new int[]{texture}, 0);
                    texture = 0;
                }
                if (program != 0) {
                    GLES20.glDeleteProgram(program);
                    program = 0;
                }
            }
            EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            if (surface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, surface);
                surface = EGL14.EGL_NO_SURFACE;
            }
            if (context != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, context);
                context = EGL14.EGL_NO_CONTEXT;
            }
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(display);
            display = EGL14.EGL_NO_DISPLAY;
        }
    }
}
