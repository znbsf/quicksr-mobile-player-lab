package dev.aisystems.quicksrplayerlab;

import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/** Stateless JNI bridge that retains no Java arrays or direct-buffer addresses after a call. */
@UnstableApi
final class NativeOutputPacker {
    private static final String LIBRARY_NAME = "quicksr_output_packer";
    private static final AtomicBoolean VERIFIED = new AtomicBoolean();

    static {
        System.loadLibrary(LIBRARY_NAME);
    }

    private NativeOutputPacker() {}

    static void verifyImplementation() {
        if (VERIFIED.get()) {
            return;
        }
        synchronized (VERIFIED) {
            if (VERIFIED.get()) {
                return;
            }
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
            ByteBuffer first = ByteBuffer.allocateDirect(expected.length)
                    .order(ByteOrder.nativeOrder());
            pack(
                    output,
                    inputRgba,
                    inputWidth,
                    inputHeight,
                    outputWidth,
                    outputHeight,
                    first);
            byte[] firstSnapshot = toByteArray(first);
            if (!Arrays.equals(expected, firstSnapshot)) {
                throw new IllegalStateException("QuickSR native packer differs from Java reference");
            }
            Arrays.fill(output, 0.0f);
            ByteBuffer second = ByteBuffer.allocateDirect(expected.length)
                    .order(ByteOrder.nativeOrder());
            pack(
                    output,
                    inputRgba,
                    inputWidth,
                    inputHeight,
                    outputWidth,
                    outputHeight,
                    second);
            if (!Arrays.equals(firstSnapshot, toByteArray(first))) {
                throw new IllegalStateException("QuickSR native packer retained output ownership");
            }
            if (Arrays.equals(firstSnapshot, toByteArray(second))) {
                throw new IllegalStateException("QuickSR native packer lifecycle probe was ineffective");
            }
            VERIFIED.set(true);
        }
    }

    static void pack(
            float[] output,
            byte[] inputRgba,
            int inputWidth,
            int inputHeight,
            int outputWidth,
            int outputHeight,
            ByteBuffer packedRgba) {
        int inputPixels = checkedPixels(inputWidth, inputHeight);
        int outputPixels = checkedPixels(outputWidth, outputHeight);
        int outputBytes = Math.multiplyExact(outputPixels, 4);
        if (output == null
                || output.length != Math.multiplyExact(outputPixels, 3)
                || inputRgba == null
                || inputRgba.length != Math.multiplyExact(inputPixels, 4)
                || packedRgba == null
                || !packedRgba.isDirect()
                || packedRgba.isReadOnly()
                || packedRgba.capacity() != outputBytes) {
            throw new IllegalArgumentException("QuickSR native output buffer ownership mismatch");
        }
        nativePack(
                output,
                inputRgba,
                inputWidth,
                inputHeight,
                outputWidth,
                outputHeight,
                packedRgba);
        packedRgba.position(0);
        packedRgba.limit(outputBytes);
    }

    private static int checkedPixels(int width, int height) {
        if (width <= 0 || width > 4096 || height <= 0 || height > 4096) {
            throw new IllegalArgumentException(
                    "Invalid QuickSR native output dimensions: " + width + "x" + height);
        }
        return Math.multiplyExact(width, height);
    }

    private static byte[] toByteArray(ByteBuffer source) {
        ByteBuffer copy = source.duplicate();
        copy.position(0);
        byte[] value = new byte[copy.remaining()];
        copy.get(value);
        return value;
    }

    private static native void nativePack(
            float[] output,
            byte[] inputRgba,
            int inputWidth,
            int inputHeight,
            int outputWidth,
            int outputHeight,
            ByteBuffer packedRgba);
}
