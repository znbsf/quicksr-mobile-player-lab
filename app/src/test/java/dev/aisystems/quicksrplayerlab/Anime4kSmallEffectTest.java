package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.util.Size;
import androidx.media3.common.util.GlUtil;
import androidx.media3.effect.DefaultVideoFrameProcessor;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;

public final class Anime4kSmallEffectTest {
    private static final Path SHADER_SOURCE = Path.of(
            "src",
            "main",
            "assets",
            "anime4k",
            "Anime4K_Upscale_CNN_x2_S.txt");

    @Test
    public void pinnedOfficialSourceBuildsCompleteFivePassChain() throws IOException {
        String source = new String(Files.readAllBytes(SHADER_SOURCE), StandardCharsets.UTF_8);
        List<String> fragments = Anime4kSmallEffect.buildFragmentShaders(source);

        assertEquals(Anime4kSmallEffect.PASS_COUNT, fragments.size());
        for (String fragment : fragments) {
            assertTrue(fragment.startsWith("#version 100"));
            assertTrue(fragment.contains("void main() { gl_FragColor = hook(); }"));
        }
        assertTrue(fragments.get(0).contains("#define MAIN_texOff(offset)"));
        assertTrue(fragments.get(1).contains("#define conv2d_tf_texOff(offset)"));
        assertTrue(fragments.get(2).contains("#define conv2d_1_tf_texOff(offset)"));
        assertTrue(fragments.get(3).contains("#define conv2d_2_tf_texOff(offset)"));
        assertTrue(fragments.get(4).contains("packedIndex"));
        assertTrue(fragments.get(4).contains("uOriginalSampler"));
        assertFalse(fragments.get(4).contains("[i0.y * 2 + i0.x]"));
    }

    @Test
    public void defaultWorkingColorKeepsElectricalMidGrayOutOfTransferFunctions()
            throws IOException {
        String source = new String(Files.readAllBytes(SHADER_SOURCE), StandardCharsets.UTF_8);
        String chain = String.join("\n", Anime4kSmallEffect.buildFragmentShaders(source));

        assertTrue(chain.contains("vec4 value = texture2D(uTexSampler, position);"));
        assertTrue(chain.contains("vec3 result = max(baseColor.rgb + vec3(c0), vec3(0.0));"));
        assertFalse(chain.contains("linearToSrgb"));
        assertFalse(chain.contains("srgbToLinear"));
        assertFalse(chain.contains("pow("));

        double electricalMidGray = 0.5;
        double mistakenSrgbOetf = 1.055 * Math.pow(electricalMidGray, 1.0 / 2.4) - 0.055;
        assertEquals(0.5, electricalMidGray, 0.0);
        assertTrue(Math.abs(mistakenSrgbOetf - electricalMidGray) > 0.2);
    }

    @Test
    public void capabilityGateRequiresEs3AndHalfFloatColorBufferExtension() {
        assertNotNull(Anime4kSmallEffect.modelCapabilityFailure(
                2, "GL_EXT_color_buffer_half_float"));
        assertNotNull(Anime4kSmallEffect.modelCapabilityFailure(3, "GL_OES_texture_float"));
        assertNull(Anime4kSmallEffect.modelCapabilityFailure(
                3, "GL_EXT_color_buffer_half_float GL_OES_texture_float"));
        assertNull(Anime4kSmallEffect.modelCapabilityFailure(
                3, "GL_EXT_color_buffer_float"));
        assertNotNull(Anime4kSmallEffect.modelCapabilityFailure(
                3, "GL_EXT_color_buffer_half_float_suffix"));
    }

    @Test
    public void playerFactoryPinsDefaultNonlinearSdrWorkingColor() {
        assertEquals(
                DefaultVideoFrameProcessor.WORKING_COLOR_SPACE_DEFAULT,
                DefaultWorkingColorRenderersFactory.SDR_WORKING_COLOR_SPACE);
    }

    @Test
    public void fallbackShaderUsesPortableMediumpPrecision() {
        assertTrue(Anime4kSmallEffect.FALLBACK_FRAGMENT_SHADER.contains(
                "precision mediump float;"));
        assertFalse(Anime4kSmallEffect.FALLBACK_FRAGMENT_SHADER.contains(
                "precision highp float;"));
    }

    @Test
    public void intermediatePairAllocationRollsBackEveryCompletedResource() {
        for (int failingOperation = 1; failingOperation <= 4; failingOperation++) {
            FakeAllocator allocator = new FakeAllocator(failingOperation);
            assertThrows(
                    GlUtil.GlException.class,
                    () -> Anime4kSmallEffect.allocateIntermediateResources(
                            640, 360, allocator));
            assertTrue("leaked resources at operation " + failingOperation,
                    allocator.liveResources.isEmpty());
        }
    }

    @Test
    public void sourceIdentityAcceptsCheckoutLineEndingsButRejectsMutation() throws IOException {
        String source = new String(Files.readAllBytes(SHADER_SOURCE), StandardCharsets.UTF_8);
        assertEquals(
                Anime4kSmallEffect.PASS_COUNT,
                Anime4kSmallEffect.buildFragmentShaders(source.replace("\n", "\r\n")).size());
        assertThrows(
                IllegalArgumentException.class,
                () -> Anime4kSmallEffect.buildFragmentShaders(source.replace(
                        "-0.0057322932",
                        "-0.0057322931")));
    }

    @Test
    public void x2SizingIsExactAndFailsBeforeTextureOverflow() {
        Size output = Anime4kSmallEffect.checkedOutputSize(640, 360, 4096);
        assertEquals(1280, output.getWidth());
        assertEquals(720, output.getHeight());
        assertThrows(
                IllegalArgumentException.class,
                () -> Anime4kSmallEffect.checkedOutputSize(1280, 720, 2048));
        assertThrows(
                IllegalArgumentException.class,
                () -> Anime4kSmallEffect.checkedOutputSize(0, 360, 4096));
    }

    @Test
    public void targetContractUsesHalfSizeGpuInputAndPinnedEffectIdentity() {
        int[] input = Anime4kSmallEffect.inputSizeForTarget(1920, 1080);
        assertEquals(960, input[0]);
        assertEquals(540, input[1]);
        assertThrows(
                IllegalArgumentException.class,
                () -> Anime4kSmallEffect.inputSizeForTarget(1919, 1080));
        assertEquals(
                "GPU_ANIME4K:1920x1080:" + Anime4kSmallEffect.UPSTREAM_COMMIT,
                SuperResolutionActivity.anime4kVideoEffectKey(1920, 1080));
        assertEquals(
                "GPU_ANIME4K_APP_FALLBACK_LANCZOS:1920x1080",
                SuperResolutionActivity.anime4kAppFallbackKey(1920, 1080));
    }

    private static final class FakeAllocator implements Anime4kSmallEffect.IntermediateAllocator {
        private final int failingOperation;
        private int operation;
        private int nextTexture = 100;
        private int nextFramebuffer = 200;
        final List<Integer> liveResources = new ArrayList<>();

        FakeAllocator(int failingOperation) {
            this.failingOperation = failingOperation;
        }

        @Override
        public int createHalfFloatTexture(int width, int height) throws GlUtil.GlException {
            failIfRequested();
            int id = nextTexture++;
            liveResources.add(id);
            return id;
        }

        @Override
        public int createCompleteFramebuffer(int textureId) throws GlUtil.GlException {
            failIfRequested();
            int id = nextFramebuffer++;
            liveResources.add(id);
            return id;
        }

        @Override
        public void deleteTexture(int textureId) {
            liveResources.remove(Integer.valueOf(textureId));
        }

        @Override
        public void deleteFramebuffer(int framebufferId) {
            liveResources.remove(Integer.valueOf(framebufferId));
        }

        private void failIfRequested() throws GlUtil.GlException {
            operation++;
            if (operation == failingOperation) {
                throw new GlUtil.GlException("synthetic allocation failure " + operation);
            }
        }
    }
}
