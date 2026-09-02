package dev.aisystems.quicksrplayerlab;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.os.Build;

import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.FloatBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * GPU-resident Android adaptation of Anime4K v4.0.1 Upscale CNN x2 Small.
 *
 * <p>The five upstream passes and embedded coefficients are loaded from the exact MIT-licensed
 * upstream text asset pinned by {@link #UPSTREAM_COMMIT} and {@link #UPSTREAM_SHA256}. The first
 * four convolution passes ping-pong between two RGBA16F textures so signed feature values survive;
 * the fifth depth-to-space pass reads the original input texture for the residual. No CPU readback
 * or neural-runtime round trip occurs.
 *
 * <p>The app explicitly uses Media3 1.11.0 {@code WORKING_COLOR_SPACE_DEFAULT}. For SDR input,
 * intermediate effects therefore receive the original nonlinear electrical RGB values. The
 * adapter passes those values to the upstream CNN without another OETF/EOTF. Alpha is preserved
 * from the original frame rather than modified by the replicated residual channel. HDR is
 * deliberately routed to the GPU bilinear fallback until a separately validated BT.2020/HDR
 * contract exists.
 */
@UnstableApi
public final class Anime4kSmallEffect implements GlEffect {
    static final String UPSTREAM_VERSION = "v4.0.1";
    static final String UPSTREAM_COMMIT = "4029bf701ecaa15f163cdc49cffe5501c1acf410";
    static final String UPSTREAM_SHA256 =
            "4c53ec2e287908f7ee7bcb266b0170421626d663576468b7d7dafc62962649a4";
    static final String ASSET_PATH = "anime4k/Anime4K_Upscale_CNN_x2_S.txt";
    static final int PASS_COUNT = 5;

    interface StatusListener {
        void onStatus(boolean modelActive, String detail);

        default void onFatalFallbackFailure(String detail) { }
    }

    private static final StatusListener NO_OP_STATUS_LISTENER = (active, detail) -> { };

    private final StatusListener statusListener;

    Anime4kSmallEffect() {
        this(NO_OP_STATUS_LISTENER);
    }

    Anime4kSmallEffect(StatusListener statusListener) {
        this.statusListener = statusListener == null ? NO_OP_STATUS_LISTENER : statusListener;
    }

    @Override
    public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr)
            throws VideoFrameProcessingException {
        return Anime4kSmallShaderProgram.create(context, useHdr, statusListener);
    }

    @Override
    public boolean isNoOp(int inputWidth, int inputHeight) {
        return false;
    }

    static Size checkedOutputSize(int inputWidth, int inputHeight, int maxTextureSize) {
        if (inputWidth <= 0 || inputHeight <= 0) {
            throw new IllegalArgumentException("Anime4K input dimensions must be positive");
        }
        if (maxTextureSize <= 0) {
            throw new IllegalArgumentException("GL_MAX_TEXTURE_SIZE must be positive");
        }
        long outputWidth = (long) inputWidth * 2L;
        long outputHeight = (long) inputHeight * 2L;
        if (outputWidth > maxTextureSize || outputHeight > maxTextureSize) {
            throw new IllegalArgumentException(
                    "Anime4K x2 output " + outputWidth + "x" + outputHeight
                            + " exceeds GL_MAX_TEXTURE_SIZE " + maxTextureSize);
        }
        return new Size((int) outputWidth, (int) outputHeight);
    }

    static int[] inputSizeForTarget(int targetWidth, int targetHeight) {
        if (targetWidth <= 0 || targetHeight <= 0
                || (targetWidth & 1) != 0 || (targetHeight & 1) != 0) {
            throw new IllegalArgumentException("Anime4K target must have positive even dimensions");
        }
        return new int[]{targetWidth / 2, targetHeight / 2};
    }

    static List<String> buildFragmentShaders(String upstreamSource) {
        String normalized = normalizeLineEndings(upstreamSource);
        String observedHash = sha256(normalized.getBytes(StandardCharsets.UTF_8));
        if (!UPSTREAM_SHA256.equals(observedHash)) {
            throw new IllegalArgumentException(
                    "Anime4K source identity mismatch: expected " + UPSTREAM_SHA256
                            + ", observed " + observedHash);
        }

        List<String> blocks = splitPassBlocks(normalized);
        if (blocks.size() != PASS_COUNT) {
            throw new IllegalArgumentException(
                    "Expected " + PASS_COUNT + " Anime4K passes, observed " + blocks.size());
        }

        List<String> fragments = new ArrayList<>(PASS_COUNT);
        fragments.add(buildConvolutionFragment(blocks.get(0), "MAIN"));
        fragments.add(buildConvolutionFragment(blocks.get(1), "conv2d_tf"));
        fragments.add(buildConvolutionFragment(blocks.get(2), "conv2d_1_tf"));
        fragments.add(buildConvolutionFragment(blocks.get(3), "conv2d_2_tf"));
        fragments.add(buildDepthToSpaceFragment(blocks.get(4)));
        return Collections.unmodifiableList(fragments);
    }

    private static List<String> splitPassBlocks(String source) {
        List<String> blocks = new ArrayList<>();
        String marker = "//!DESC ";
        int start = source.indexOf(marker);
        while (start >= 0) {
            int next = source.indexOf(marker, start + marker.length());
            blocks.add(source.substring(start, next < 0 ? source.length() : next));
            start = next;
        }
        return blocks;
    }

    private static String buildConvolutionFragment(
            String block,
            String upstreamSamplerPrefix) {
        int bodyStart = block.indexOf("#define go_0");
        if (bodyStart < 0) {
            throw new IllegalArgumentException("Anime4K convolution pass has no go_0 macro");
        }
        String body = block.substring(bodyStart).trim();
        StringBuilder shader = new StringBuilder(8192);
        shader.append(FRAGMENT_HEADER)
                .append("uniform sampler2D uTexSampler;\n")
                .append("uniform vec2 uTexelSize;\n")
                .append("varying vec2 vTexSamplingCoord;\n");
        shader.append("vec4 sampleFeature(vec2 offset) {\n")
                .append("  vec2 halfTexel = 0.5 * uTexelSize;\n")
                .append("  vec2 position = clamp(vTexSamplingCoord + offset * uTexelSize, ")
                .append("halfTexel, vec2(1.0) - halfTexel);\n")
                .append("  vec4 value = texture2D(uTexSampler, position);\n");
        shader.append("  return value;\n")
                .append("}\n")
                .append("#define ").append(upstreamSamplerPrefix)
                .append("_texOff(offset) sampleFeature(offset)\n")
                .append(body).append('\n')
                .append("void main() { gl_FragColor = hook(); }\n");
        return shader.toString();
    }

    private static String buildDepthToSpaceFragment(String block) {
        int bodyStart = block.indexOf("vec4 hook()");
        if (bodyStart < 0) {
            throw new IllegalArgumentException("Anime4K depth-to-space pass has no hook");
        }
        String body = block.substring(bodyStart).trim();
        body = replaceLineContaining(
                body,
                "float c0 = conv2d_last_tf_tex",
                "    vec4 packedResidual = conv2d_last_tf_tex("
                        + "(vec2(0.5) - f0) * conv2d_last_tf_pt + conv2d_last_tf_pos);\n"
                        + "    int packedIndex = i0.y * 2 + i0.x;\n"
                        + "    float c0 = packedIndex == 0 ? packedResidual.x"
                        + " : (packedIndex == 1 ? packedResidual.y"
                        + " : (packedIndex == 2 ? packedResidual.z : packedResidual.w));");
        body = replaceLineContaining(
                body,
                "return vec4(c0, c1, c2, c3) + MAIN_tex(MAIN_pos);",
                "    vec4 baseColor = MAIN_tex(MAIN_pos);\n"
                        + "    vec3 result = max(baseColor.rgb + vec3(c0), vec3(0.0));\n"
                        + "    return vec4(result, baseColor.a);");

        return FRAGMENT_HEADER
                + "uniform sampler2D uFeatureSampler;\n"
                + "uniform sampler2D uOriginalSampler;\n"
                + "uniform vec2 uFeatureSize;\n"
                + "uniform vec2 uFeatureTexelSize;\n"
                + "varying vec2 vTexSamplingCoord;\n"
                + "vec4 samplePackedFeature(vec2 position) {\n"
                + "  vec2 halfTexel = 0.5 * uFeatureTexelSize;\n"
                + "  return texture2D(uFeatureSampler, clamp(position, halfTexel, "
                + "vec2(1.0) - halfTexel));\n"
                + "}\n"
                + "vec4 sampleOriginal(vec2 position) {\n"
                + "  vec4 value = texture2D(uOriginalSampler, clamp(position, 0.0, 1.0));\n"
                + "  return value;\n"
                + "}\n"
                + "#define conv2d_last_tf_pos vTexSamplingCoord\n"
                + "#define conv2d_last_tf_size uFeatureSize\n"
                + "#define conv2d_last_tf_pt uFeatureTexelSize\n"
                + "#define conv2d_last_tf_tex(position) samplePackedFeature(position)\n"
                + "#define MAIN_pos vTexSamplingCoord\n"
                + "#define MAIN_tex(position) sampleOriginal(position)\n"
                + body + '\n'
                + "void main() { gl_FragColor = hook(); }\n";
    }

    private static String replaceLineContaining(String source, String marker, String replacement) {
        int markerIndex = source.indexOf(marker);
        if (markerIndex < 0) {
            throw new IllegalArgumentException("Missing expected Anime4K source line: " + marker);
        }
        int lineStart = source.lastIndexOf('\n', markerIndex);
        lineStart = lineStart < 0 ? 0 : lineStart + 1;
        int lineEnd = source.indexOf('\n', markerIndex);
        lineEnd = lineEnd < 0 ? source.length() : lineEnd;
        return source.substring(0, lineStart) + replacement + source.substring(lineEnd);
    }

    private static String normalizeLineEndings(String source) {
        return source.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            char[] hex = new char[digest.length * 2];
            char[] alphabet = "0123456789abcdef".toCharArray();
            for (int i = 0; i < digest.length; i++) {
                int unsigned = digest[i] & 0xff;
                hex[i * 2] = alphabet[unsigned >>> 4];
                hex[i * 2 + 1] = alphabet[unsigned & 0x0f];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String loadUpstreamSource(Context context) throws IOException {
        try (InputStream input = context.getAssets().open(ASSET_PATH);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String conciseMessage(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return failure.getClass().getSimpleName();
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    private static final String VERTEX_SHADER =
            "#version 100\n"
                    + "attribute vec4 aFramePosition;\n"
                    + "varying vec2 vTexSamplingCoord;\n"
                    + "void main() {\n"
                    + "  gl_Position = aFramePosition;\n"
                    + "  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;\n"
                    + "}\n";

    private static final String FRAGMENT_HEADER =
            "#version 100\n"
                    + "precision highp float;\n";

    static final String FALLBACK_FRAGMENT_SHADER =
            "#version 100\n"
                    + "precision mediump float;\n"
                    + "uniform sampler2D uTexSampler;\n"
                    + "varying vec2 vTexSamplingCoord;\n"
                    + "void main() { gl_FragColor = texture2D(uTexSampler, vTexSamplingCoord); }\n";

    private static final class OwnedGlProgram {
        private final int programId;
        private final int framePositionLocation;
        private final FloatBuffer framePositionBuffer;
        private boolean deleted;

        static OwnedGlProgram create(String vertexShader, String fragmentShader)
                throws GlUtil.GlException {
            int programId = 0;
            int vertexShaderId = 0;
            int fragmentShaderId = 0;
            try {
                programId = GLES20.glCreateProgram();
                GlUtil.checkGlError();
                if (programId == 0) {
                    throw new GlUtil.GlException("glCreateProgram returned 0");
                }
                vertexShaderId = compileShader(GLES20.GL_VERTEX_SHADER, vertexShader);
                fragmentShaderId = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader);
                GLES20.glAttachShader(programId, vertexShaderId);
                GLES20.glAttachShader(programId, fragmentShaderId);
                GLES20.glLinkProgram(programId);
                int[] linkStatus = new int[1];
                GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0);
                GlUtil.checkGlError();
                if (linkStatus[0] != GLES20.GL_TRUE) {
                    throw new GlUtil.GlException(
                            "Unable to link Anime4K program: "
                                    + GLES20.glGetProgramInfoLog(programId));
                }
                deleteShader(vertexShaderId);
                vertexShaderId = 0;
                deleteShader(fragmentShaderId);
                fragmentShaderId = 0;
                int framePositionLocation = GLES20.glGetAttribLocation(
                        programId, "aFramePosition");
                GlUtil.checkGlError();
                if (framePositionLocation < 0) {
                    throw new GlUtil.GlException("aFramePosition attribute is unavailable");
                }
                return new OwnedGlProgram(programId, framePositionLocation);
            } catch (GlUtil.GlException | RuntimeException failure) {
                deleteShaderQuietly(vertexShaderId, failure);
                deleteShaderQuietly(fragmentShaderId, failure);
                deleteProgramQuietly(programId, failure);
                throw failure;
            }
        }

        private OwnedGlProgram(int programId, int framePositionLocation) {
            this.programId = programId;
            this.framePositionLocation = framePositionLocation;
            framePositionBuffer = GlUtil.createBuffer(GlUtil.getNormalizedCoordinateBounds());
        }

        private static int compileShader(int type, String source) throws GlUtil.GlException {
            int shaderId = GLES20.glCreateShader(type);
            try {
                GlUtil.checkGlError();
                if (shaderId == 0) {
                    throw new GlUtil.GlException("glCreateShader returned 0");
                }
                GLES20.glShaderSource(shaderId, source);
                GLES20.glCompileShader(shaderId);
                int[] compileStatus = new int[1];
                GLES20.glGetShaderiv(shaderId, GLES20.GL_COMPILE_STATUS, compileStatus, 0);
                GlUtil.checkGlError();
                if (compileStatus[0] != GLES20.GL_TRUE) {
                    throw new GlUtil.GlException(
                            "Unable to compile Anime4K shader: "
                                    + GLES20.glGetShaderInfoLog(shaderId));
                }
                return shaderId;
            } catch (GlUtil.GlException | RuntimeException failure) {
                deleteShaderQuietly(shaderId, failure);
                throw failure;
            }
        }

        void use() throws GlUtil.GlException {
            GLES20.glUseProgram(programId);
            GlUtil.checkGlError();
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
            framePositionBuffer.position(0);
            GLES20.glVertexAttribPointer(
                    framePositionLocation,
                    GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
                    GLES20.GL_FLOAT,
                    false,
                    0,
                    framePositionBuffer);
            GLES20.glEnableVertexAttribArray(framePositionLocation);
            GlUtil.checkGlError();
        }

        void bindTexture(String uniformName, int textureId, int textureUnit)
                throws GlUtil.GlException {
            int location = requireUniform(uniformName);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + textureUnit);
            GlUtil.checkGlError();
            GlUtil.bindTexture(GLES20.GL_TEXTURE_2D, textureId, GLES20.GL_LINEAR);
            GLES20.glUniform1i(location, textureUnit);
            GlUtil.checkGlError();
        }

        void setVec2(String uniformName, float x, float y) throws GlUtil.GlException {
            GLES20.glUniform2f(requireUniform(uniformName), x, y);
            GlUtil.checkGlError();
        }

        private int requireUniform(String name) throws GlUtil.GlException {
            int location = GLES20.glGetUniformLocation(programId, name);
            GlUtil.checkGlError();
            if (location < 0) {
                throw new GlUtil.GlException("Required uniform is unavailable: " + name);
            }
            return location;
        }

        void delete() throws GlUtil.GlException {
            if (deleted) {
                return;
            }
            if (!shouldSkipProgramDeletion(Build.VERSION.SDK_INT)) {
                GLES20.glDeleteProgram(programId);
                GlUtil.checkGlError();
            }
            deleted = true;
        }

        private static void deleteShader(int shaderId) throws GlUtil.GlException {
            GLES20.glDeleteShader(shaderId);
            GlUtil.checkGlError();
        }

        private static void deleteShaderQuietly(int shaderId, Throwable failure) {
            if (shaderId == 0) {
                return;
            }
            try {
                deleteShader(shaderId);
            } catch (GlUtil.GlException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }

        private static void deleteProgramQuietly(int programId, Throwable failure) {
            if (programId == 0 || shouldSkipProgramDeletion(Build.VERSION.SDK_INT)) {
                return;
            }
            try {
                GLES20.glDeleteProgram(programId);
                GlUtil.checkGlError();
            } catch (GlUtil.GlException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    /** Mirrors the Media3 1.11.0 GlProgram workaround for API 28 driver crashes. */
    static boolean shouldSkipProgramDeletion(int sdkInt) {
        return sdkInt == 28;
    }

    interface IntermediateAllocator {
        int createHalfFloatTexture(int width, int height) throws GlUtil.GlException;

        int createCompleteFramebuffer(int textureId) throws GlUtil.GlException;

        void deleteTexture(int textureId) throws GlUtil.GlException;

        void deleteFramebuffer(int framebufferId) throws GlUtil.GlException;
    }

    static final class IntermediateResources {
        final int textureA;
        final int framebufferA;
        final int textureB;
        final int framebufferB;

        IntermediateResources(int textureA, int framebufferA, int textureB, int framebufferB) {
            this.textureA = textureA;
            this.framebufferA = framebufferA;
            this.textureB = textureB;
            this.framebufferB = framebufferB;
        }
    }

    static String modelCapabilityFailure(long contextMajorVersion, String extensions) {
        if (contextMajorVersion < 3) {
            return "OpenGL ES 3.0 or newer is required for RGBA16F intermediates";
        }
        if (!hasExtension(extensions, "GL_EXT_color_buffer_half_float")
                && !hasExtension(extensions, "GL_EXT_color_buffer_float")) {
            return "half-float color-buffer renderability extension is unavailable";
        }
        return null;
    }

    private static boolean hasExtension(String extensions, String expected) {
        if (extensions == null || extensions.isEmpty()) {
            return false;
        }
        for (String extension : extensions.trim().split("\\s+")) {
            if (expected.equals(extension)) {
                return true;
            }
        }
        return false;
    }

    static IntermediateResources allocateIntermediateResources(
            int width,
            int height,
            IntermediateAllocator allocator) throws GlUtil.GlException {
        int textureA = 0;
        int framebufferA = 0;
        int textureB = 0;
        int framebufferB = 0;
        try {
            textureA = allocator.createHalfFloatTexture(width, height);
            framebufferA = allocator.createCompleteFramebuffer(textureA);
            textureB = allocator.createHalfFloatTexture(width, height);
            framebufferB = allocator.createCompleteFramebuffer(textureB);
            return new IntermediateResources(textureA, framebufferA, textureB, framebufferB);
        } catch (GlUtil.GlException failure) {
            cleanupIntermediateResources(
                    allocator,
                    new IntermediateResources(textureA, framebufferA, textureB, framebufferB),
                    failure);
            throw failure;
        }
    }

    private static void cleanupIntermediateResources(
            IntermediateAllocator allocator,
            IntermediateResources resources,
            GlUtil.GlException failure) {
        for (int framebuffer : new int[]{resources.framebufferB, resources.framebufferA}) {
            if (framebuffer == 0) {
                continue;
            }
            try {
                allocator.deleteFramebuffer(framebuffer);
            } catch (GlUtil.GlException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
        for (int texture : new int[]{resources.textureB, resources.textureA}) {
            if (texture == 0) {
                continue;
            }
            try {
                allocator.deleteTexture(texture);
            } catch (GlUtil.GlException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    private static final class RuntimeIntermediateAllocator implements IntermediateAllocator {
        @Override
        public int createHalfFloatTexture(int width, int height) throws GlUtil.GlException {
            int textureId = GlUtil.generateTexture();
            try {
                GlUtil.bindTexture(GLES20.GL_TEXTURE_2D, textureId, GLES20.GL_LINEAR);
                GLES20.glTexImage2D(
                        GLES20.GL_TEXTURE_2D,
                        0,
                        GLES30.GL_RGBA16F,
                        width,
                        height,
                        0,
                        GLES20.GL_RGBA,
                        GLES30.GL_HALF_FLOAT,
                        null);
                GlUtil.checkGlError();
                return textureId;
            } catch (GlUtil.GlException failure) {
                try {
                    GlUtil.deleteTexture(textureId);
                } catch (GlUtil.GlException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        @Override
        public int createCompleteFramebuffer(int textureId) throws GlUtil.GlException {
            int[] framebufferId = new int[1];
            try {
                GLES20.glGenFramebuffers(1, framebufferId, 0);
                GlUtil.checkGlError();
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebufferId[0]);
                GlUtil.checkGlError();
                GLES20.glFramebufferTexture2D(
                        GLES20.GL_FRAMEBUFFER,
                        GLES20.GL_COLOR_ATTACHMENT0,
                        GLES20.GL_TEXTURE_2D,
                        textureId,
                        0);
                GlUtil.checkGlError();
                int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
                GlUtil.checkGlError();
                if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    throw new GlUtil.GlException(
                            "RGBA16F framebuffer incomplete: 0x" + Integer.toHexString(status));
                }
                return framebufferId[0];
            } catch (GlUtil.GlException failure) {
                if (framebufferId[0] != 0) {
                    try {
                        GlUtil.deleteFbo(framebufferId[0]);
                    } catch (GlUtil.GlException cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                throw failure;
            }
        }

        @Override
        public void deleteTexture(int textureId) throws GlUtil.GlException {
            GlUtil.deleteTexture(textureId);
        }

        @Override
        public void deleteFramebuffer(int framebufferId) throws GlUtil.GlException {
            GlUtil.deleteFbo(framebufferId);
        }
    }

    private static final class Anime4kSmallShaderProgram extends BaseGlShaderProgram {
        private static final IntermediateAllocator INTERMEDIATE_ALLOCATOR =
                new RuntimeIntermediateAllocator();

        private final StatusListener statusListener;
        private final OwnedGlProgram fallbackProgram;
        private final ArrayList<OwnedGlProgram> modelPrograms;

        private boolean fallbackActive;
        private boolean fallbackReported;
        private boolean fatalFallbackReported;
        private boolean activeReported;
        private int inputWidth;
        private int inputHeight;
        private int outputWidth;
        private int outputHeight;
        private int featureTextureA;
        private int featureTextureB;
        private int featureFboA;
        private int featureFboB;
        private int featureWidth;
        private int featureHeight;

        static Anime4kSmallShaderProgram create(
                Context context,
                boolean useHdr,
                StatusListener statusListener) throws VideoFrameProcessingException {
            final OwnedGlProgram fallback;
            try {
                fallback = OwnedGlProgram.create(VERTEX_SHADER, FALLBACK_FRAGMENT_SHADER);
            } catch (GlUtil.GlException failure) {
                throw new VideoFrameProcessingException(failure);
            }

            if (useHdr) {
                Anime4kSmallShaderProgram program = new Anime4kSmallShaderProgram(
                        statusListener,
                        fallback,
                        Collections.emptyList(),
                        true,
                        true);
                program.reportFallback(
                        "HDR input is not supported by the Anime4K nonlinear SDR adapter");
                return program;
            }

            List<OwnedGlProgram> compiled = new ArrayList<>(PASS_COUNT);
            try {
                for (String fragment : buildFragmentShaders(loadUpstreamSource(context))) {
                    compiled.add(OwnedGlProgram.create(VERTEX_SHADER, fragment));
                }
                return new Anime4kSmallShaderProgram(
                        statusListener, fallback, compiled, false, false);
            } catch (IOException | IllegalArgumentException | GlUtil.GlException failure) {
                List<OwnedGlProgram> unreleasedPrograms =
                        deleteProgramsRetainingFailures(compiled);
                Anime4kSmallShaderProgram program = new Anime4kSmallShaderProgram(
                        statusListener,
                        fallback,
                        unreleasedPrograms,
                        true,
                        false);
                program.reportFallback("Anime4K shader unavailable: " + conciseMessage(failure));
                return program;
            }
        }

        private Anime4kSmallShaderProgram(
                StatusListener statusListener,
                OwnedGlProgram fallbackProgram,
                List<OwnedGlProgram> modelPrograms,
                boolean fallbackActive,
                boolean useHighPrecisionOutput) {
            // Signed CNN features need RGBA16F, but the final SDR output and fallback can use the
            // regular Media3 RGBA8 pool. This keeps the fallback viable on GPUs that cannot render
            // to half-float intermediate textures. HDR fallback retains Media3's high precision.
            super(useHighPrecisionOutput, /* texturePoolCapacity= */ 1);
            this.statusListener = statusListener;
            this.fallbackProgram = fallbackProgram;
            this.modelPrograms = new ArrayList<>(modelPrograms);
            this.fallbackActive = fallbackActive;
        }

        @Override
        public Size configure(int inputWidth, int inputHeight)
                throws VideoFrameProcessingException {
            try {
                int[] maxTextureSize = new int[1];
                GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0);
                GlUtil.checkGlError();
                Size outputSize = checkedOutputSize(inputWidth, inputHeight, maxTextureSize[0]);
                this.inputWidth = inputWidth;
                this.inputHeight = inputHeight;
                outputWidth = outputSize.getWidth();
                outputHeight = outputSize.getHeight();
                if (!fallbackActive) {
                    try {
                        requireModelCapabilities();
                        ensureIntermediateTextures(inputWidth, inputHeight);
                    } catch (GlUtil.GlException allocationFailure) {
                        activateFallback(
                                "Anime4K RGBA16F intermediates unavailable: "
                                        + conciseMessage(allocationFailure));
                    }
                }
                return outputSize;
            } catch (IllegalArgumentException | GlUtil.GlException failure) {
                throw new VideoFrameProcessingException(failure);
            }
        }

        @Override
        public void drawFrame(int inputTexId, long presentationTimeUs)
                throws VideoFrameProcessingException {
            if (fallbackActive) {
                drawFallback(inputTexId, presentationTimeUs);
                return;
            }

            int[] outputFbo = new int[1];
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, outputFbo, 0);
            try {
                GlUtil.checkGlError();
                drawConvolution(modelPrograms.get(0), inputTexId, featureFboA);
                drawConvolution(modelPrograms.get(1), featureTextureA, featureFboB);
                drawConvolution(modelPrograms.get(2), featureTextureB, featureFboA);
                drawConvolution(modelPrograms.get(3), featureTextureA, featureFboB);
                GlUtil.focusFramebufferUsingCurrentContext(outputFbo[0], outputWidth, outputHeight);
                GlUtil.clearFocusedBuffers();
                drawDepthToSpace(modelPrograms.get(4), featureTextureB, inputTexId);
                reportActive();
            } catch (GlUtil.GlException modelFailure) {
                activateFallback("Anime4K draw failed: " + conciseMessage(modelFailure));
                try {
                    GlUtil.focusFramebufferUsingCurrentContext(
                            outputFbo[0], outputWidth, outputHeight);
                    GlUtil.clearFocusedBuffers();
                    drawFallbackProgram(inputTexId);
                } catch (GlUtil.GlException fallbackFailure) {
                    fallbackFailure.addSuppressed(modelFailure);
                    throw new VideoFrameProcessingException(fallbackFailure, presentationTimeUs);
                }
            }
        }

        private void drawConvolution(OwnedGlProgram program, int textureId, int outputFbo)
                throws GlUtil.GlException {
            GlUtil.focusFramebufferUsingCurrentContext(outputFbo, inputWidth, inputHeight);
            GlUtil.clearFocusedBuffers();
            program.use();
            program.bindTexture("uTexSampler", textureId, 0);
            program.setVec2("uTexelSize", 1.0f / inputWidth, 1.0f / inputHeight);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GlUtil.checkGlError();
        }

        private void drawDepthToSpace(
                OwnedGlProgram program,
                int featureTextureId,
                int originalTextureId) throws GlUtil.GlException {
            program.use();
            program.bindTexture("uFeatureSampler", featureTextureId, 0);
            program.bindTexture("uOriginalSampler", originalTextureId, 1);
            program.setVec2("uFeatureSize", inputWidth, inputHeight);
            program.setVec2(
                    "uFeatureTexelSize", 1.0f / inputWidth, 1.0f / inputHeight);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GlUtil.checkGlError();
        }

        private void drawFallback(int inputTexId, long presentationTimeUs)
                throws VideoFrameProcessingException {
            try {
                drawFallbackProgram(inputTexId);
            } catch (GlUtil.GlException failure) {
                reportFatalFallbackFailure(
                        "GPU bilinear fallback draw failed: " + conciseMessage(failure));
                throw new VideoFrameProcessingException(failure, presentationTimeUs);
            }
        }

        private void drawFallbackProgram(int inputTexId) throws GlUtil.GlException {
            fallbackProgram.use();
            fallbackProgram.bindTexture("uTexSampler", inputTexId, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GlUtil.checkGlError();
        }

        private void ensureIntermediateTextures(int width, int height)
                throws GlUtil.GlException {
            if (featureTextureA != 0 && featureWidth == width && featureHeight == height) {
                return;
            }
            releaseIntermediateTextures();
            IntermediateResources resources = allocateIntermediateResources(
                    width, height, INTERMEDIATE_ALLOCATOR);
            featureTextureA = resources.textureA;
            featureFboA = resources.framebufferA;
            featureTextureB = resources.textureB;
            featureFboB = resources.framebufferB;
            featureWidth = width;
            featureHeight = height;
        }

        private static void requireModelCapabilities() throws GlUtil.GlException {
            long contextMajorVersion = GlUtil.getContextMajorVersion();
            String extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS);
            GlUtil.checkGlError();
            String failure = modelCapabilityFailure(contextMajorVersion, extensions);
            if (failure != null) {
                throw new GlUtil.GlException(failure);
            }
        }

        private void activateFallback(String reason) {
            fallbackActive = true;
            reportFallback(reason);
            try {
                releaseIntermediateTextures();
            } catch (GlUtil.GlException ignored) {
                // The GL context teardown still owns any resource the driver refused to delete.
            }
            List<OwnedGlProgram> unreleasedPrograms =
                    deleteProgramsRetainingFailures(modelPrograms);
            modelPrograms.clear();
            modelPrograms.addAll(unreleasedPrograms);
        }

        private void reportFallback(String reason) {
            if (!fallbackReported) {
                fallbackReported = true;
                statusListener.onStatus(false, reason);
            }
        }

        private void reportActive() {
            if (!activeReported) {
                activeReported = true;
                statusListener.onStatus(
                        true,
                        "five-pass GPU texture path produced its first frame");
            }
        }

        private void reportFatalFallbackFailure(String detail) {
            if (!fatalFallbackReported) {
                fatalFallbackReported = true;
                statusListener.onFatalFallbackFailure(detail);
            }
        }

        @Override
        public void release() throws VideoFrameProcessingException {
            Exception failure = null;
            try {
                super.release();
            } catch (Exception caught) {
                failure = caught;
            }
            try {
                releaseIntermediateTextures();
            } catch (Exception caught) {
                failure = combine(failure, caught);
            }
            for (OwnedGlProgram program : modelPrograms) {
                try {
                    program.delete();
                } catch (Exception caught) {
                    failure = combine(failure, caught);
                }
            }
            modelPrograms.clear();
            try {
                fallbackProgram.delete();
            } catch (Exception caught) {
                failure = combine(failure, caught);
            }
            if (failure != null) {
                throw new VideoFrameProcessingException(failure);
            }
        }

        private void releaseIntermediateTextures() throws GlUtil.GlException {
            GlUtil.GlException failure = null;
            int oldFboA = featureFboA;
            int oldFboB = featureFboB;
            int oldTextureA = featureTextureA;
            int oldTextureB = featureTextureB;
            featureFboA = 0;
            featureFboB = 0;
            featureTextureA = 0;
            featureTextureB = 0;
            featureWidth = 0;
            featureHeight = 0;
            for (int fbo : new int[]{oldFboA, oldFboB}) {
                if (fbo == 0) {
                    continue;
                }
                try {
                    GlUtil.deleteFbo(fbo);
                } catch (GlUtil.GlException caught) {
                    failure = combineGl(failure, caught);
                }
            }
            for (int texture : new int[]{oldTextureA, oldTextureB}) {
                if (texture == 0) {
                    continue;
                }
                try {
                    GlUtil.deleteTexture(texture);
                } catch (GlUtil.GlException caught) {
                    failure = combineGl(failure, caught);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private static List<OwnedGlProgram> deleteProgramsRetainingFailures(
                List<OwnedGlProgram> programs) {
            List<OwnedGlProgram> unreleasedPrograms = new ArrayList<>();
            for (OwnedGlProgram program : programs) {
                try {
                    program.delete();
                } catch (GlUtil.GlException ignored) {
                    unreleasedPrograms.add(program);
                }
            }
            return unreleasedPrograms;
        }

        private static Exception combine(Exception first, Exception next) {
            if (first == null) {
                return next;
            }
            first.addSuppressed(next);
            return first;
        }

        private static GlUtil.GlException combineGl(
                GlUtil.GlException first,
                GlUtil.GlException next) {
            if (first == null) {
                return next;
            }
            first.addSuppressed(next);
            return first;
        }
    }
}
