package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.util.Size;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        assertTrue(fragments.get(0).contains("linearToSrgb"));
        assertTrue(fragments.get(0).contains("#define MAIN_texOff(offset)"));
        assertTrue(fragments.get(1).contains("#define conv2d_tf_texOff(offset)"));
        assertTrue(fragments.get(2).contains("#define conv2d_1_tf_texOff(offset)"));
        assertTrue(fragments.get(3).contains("#define conv2d_2_tf_texOff(offset)"));
        assertTrue(fragments.get(4).contains("packedIndex"));
        assertTrue(fragments.get(4).contains("srgbToLinear"));
        assertTrue(fragments.get(4).contains("uOriginalSampler"));
        assertFalse(fragments.get(4).contains("[i0.y * 2 + i0.x]"));
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
    }
}
