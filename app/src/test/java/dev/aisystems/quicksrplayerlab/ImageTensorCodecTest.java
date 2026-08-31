package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ImageTensorCodecTest {
    @Test
    public void argbAndNchwRoundTripPreservesRgb8() {
        int[] pixels = new int[]{0xffff0000, 0xff00ff00, 0xff0000ff, 0xff7f4020};
        float[] tensor = ImageTensorCodec.argbToNchw(pixels, 2, 2);
        assertArrayEquals(pixels, ImageTensorCodec.nchwToArgb(tensor, 2, 2));
    }

    @Test
    public void averageDownsampleUsesEveryPixelInEachTwoByTwoCell() {
        int[] source = new int[ImageTensorCodec.OUTPUT_SIZE * ImageTensorCodec.OUTPUT_SIZE];
        source[0] = 0xff000000;
        source[1] = 0xffff0000;
        source[ImageTensorCodec.OUTPUT_SIZE] = 0xff00ff00;
        source[ImageTensorCodec.OUTPUT_SIZE + 1] = 0xff0000ff;
        int[] actual = ImageTensorCodec.downsample2xAverage(
                source,
                ImageTensorCodec.OUTPUT_SIZE,
                ImageTensorCodec.OUTPUT_SIZE);
        assertEquals(0xff404040, actual[0]);
    }

    @Test
    public void bilinearUpscalePreservesAConstantImage() {
        int[] source = new int[ImageTensorCodec.INPUT_SIZE * ImageTensorCodec.INPUT_SIZE];
        java.util.Arrays.fill(source, 0xff123456);
        int[] actual = ImageTensorCodec.upscale2xBilinear(
                source,
                ImageTensorCodec.INPUT_SIZE,
                ImageTensorCodec.INPUT_SIZE);
        for (int pixel : actual) {
            assertEquals(0xff123456, pixel);
        }
    }

    @Test
    public void bilinearUpscaleUsesHalfPixelCoordinatesAndClampedBorders() {
        int[] source = new int[ImageTensorCodec.INPUT_SIZE * ImageTensorCodec.INPUT_SIZE];
        for (int y = 0; y < ImageTensorCodec.INPUT_SIZE; y++) {
            for (int x = 0; x < ImageTensorCodec.INPUT_SIZE; x++) {
                source[y * ImageTensorCodec.INPUT_SIZE + x] = 0xff000000 | (x * 4 << 16);
            }
        }
        int[] actual = ImageTensorCodec.upscale2xBilinear(
                source,
                ImageTensorCodec.INPUT_SIZE,
                ImageTensorCodec.INPUT_SIZE);
        assertEquals(0, (actual[0] >>> 16) & 0xff);
        assertEquals(1, (actual[1] >>> 16) & 0xff);
        assertEquals(3, (actual[2] >>> 16) & 0xff);
        assertEquals(251, (actual[126] >>> 16) & 0xff);
        assertEquals(252, (actual[127] >>> 16) & 0xff);
    }

    @Test
    public void nchwConversionKeepsChannelOrderAndClampsInvalidValues() {
        int[] source = new int[]{0x00112233};
        float[] tensor = ImageTensorCodec.argbToNchw(source, 1, 1);
        assertEquals(0x11 / 255.0f, tensor[0], 0.0f);
        assertEquals(0x22 / 255.0f, tensor[1], 0.0f);
        assertEquals(0x33 / 255.0f, tensor[2], 0.0f);
        assertEquals(0xff112233, ImageTensorCodec.nchwToArgb(tensor, 1, 1)[0]);

        float[] invalid = new float[]{Float.NaN, -1.0f, 2.0f};
        assertEquals(0xff0000ff, ImageTensorCodec.nchwToArgb(invalid, 1, 1)[0]);
    }

    @Test
    public void psnrIsInfiniteOnlyForExactRgbMatch() {
        int[] reference = new int[]{0xff000000, 0xffffffff};
        assertTrue(Double.isInfinite(ImageTensorCodec.psnrRgb8(reference, reference)));
        double changed = ImageTensorCodec.psnrRgb8(
                reference,
                new int[]{0xff000000, 0xfffefefe});
        assertTrue(Double.isFinite(changed));
        assertTrue(changed > 40.0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidTensorShapeFailsClosed() {
        ImageTensorCodec.argbToNchw(new int[3], 2, 2);
    }
}
