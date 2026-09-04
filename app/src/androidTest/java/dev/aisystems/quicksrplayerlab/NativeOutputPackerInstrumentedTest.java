package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class NativeOutputPackerInstrumentedTest {
    @Test
    public void testMatchesJavaForBoundariesAndRandomValues() {
        int inputWidth = 7;
        int inputHeight = 3;
        int outputWidth = 23;
        int outputHeight = 11;
        int outputPixels = outputWidth * outputHeight;
        byte[] inputRgba = new byte[inputWidth * inputHeight * 4];
        new Random(0x51a7L).nextBytes(inputRgba);
        float[] output = new float[outputPixels * 3];
        Random random = new Random(0x4e454f4eL);
        for (int index = 0; index < output.length; index++) {
            output[index] = random.nextFloat() * 1.5f - 0.25f;
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
        System.arraycopy(boundaries, 0, output, 0, boundaries.length);

        byte[] expected = new byte[outputPixels * 4];
        QuickSrVideoEffect.packNchwToRgba(
                output,
                inputRgba,
                inputWidth,
                inputHeight,
                outputWidth,
                outputHeight,
                expected);
        ByteBuffer actual = direct(expected.length);
        NativeOutputPacker.pack(
                output,
                inputRgba,
                inputWidth,
                inputHeight,
                outputWidth,
                outputHeight,
                actual);

        assertEquals(0, actual.position());
        assertEquals(expected.length, actual.limit());
        assertTrue(Arrays.equals(expected, bytes(actual)));
    }

    @Test
    public void testRectangularThreeAndFourScalePreservesEveryNearestAlpha() {
        for (int scale : new int[]{3, 4}) {
            int inputWidth = 5;
            int inputHeight = 3;
            int outputWidth = inputWidth * scale;
            int outputHeight = inputHeight * scale;
            byte[] input = new byte[inputWidth * inputHeight * 4];
            for (int pixel = 0; pixel < inputWidth * inputHeight; pixel++) {
                input[pixel * 4 + 3] = (byte) (pixel * 13 + 7);
            }
            float[] output = new float[outputWidth * outputHeight * 3];
            ByteBuffer packed = direct(outputWidth * outputHeight * 4);

            NativeOutputPacker.pack(
                    output,
                    input,
                    inputWidth,
                    inputHeight,
                    outputWidth,
                    outputHeight,
                    packed);

            for (int y = 0; y < outputHeight; y++) {
                for (int x = 0; x < outputWidth; x++) {
                    int inputX = x * inputWidth / outputWidth;
                    int inputY = y * inputHeight / outputHeight;
                    int expectedAlpha = input[(inputY * inputWidth + inputX) * 4 + 3] & 0xff;
                    assertEquals(expectedAlpha, packed.get((y * outputWidth + x) * 4 + 3) & 0xff);
                }
            }
        }
    }

    @Test
    public void testCallerOwnsBuffersAcrossRepeatedCallsAndRejectedHeapBuffer() {
        int inputWidth = 2;
        int inputHeight = 1;
        int outputWidth = 4;
        int outputHeight = 2;
        byte[] input = new byte[inputWidth * inputHeight * 4];
        input[3] = 17;
        input[7] = 93;
        float[] firstTensor = new float[outputWidth * outputHeight * 3];
        Arrays.fill(firstTensor, 1.0f);
        ByteBuffer first = direct(outputWidth * outputHeight * 4);
        NativeOutputPacker.pack(
                firstTensor, input, inputWidth, inputHeight, outputWidth, outputHeight, first);
        byte[] firstSnapshot = bytes(first);

        float[] secondTensor = new float[firstTensor.length];
        ByteBuffer second = direct(first.capacity());
        for (int iteration = 0; iteration < 64; iteration++) {
            NativeOutputPacker.pack(
                    secondTensor, input, inputWidth, inputHeight, outputWidth, outputHeight, second);
        }
        assertTrue(Arrays.equals(firstSnapshot, bytes(first)));
        assertFalse(Arrays.equals(firstSnapshot, bytes(second)));

        try {
            NativeOutputPacker.pack(
                    secondTensor,
                    input,
                    inputWidth,
                    inputHeight,
                    outputWidth,
                    outputHeight,
                    ByteBuffer.allocate(second.capacity()));
            fail("heap output must be rejected before JNI");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("ownership"));
        }
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
}
