package dev.aisystems.quicksrplayerlab;

final class ImageTensorCodec {
    static final int INPUT_SIZE = 64;
    static final int OUTPUT_SIZE = 128;

    private ImageTensorCodec() {
    }

    static int[] downsample2xAverage(int[] sourceArgb, int sourceWidth, int sourceHeight) {
        if (sourceWidth != OUTPUT_SIZE || sourceHeight != OUTPUT_SIZE
                || sourceArgb.length != sourceWidth * sourceHeight) {
            throw new IllegalArgumentException("Expected one 128x128 ARGB reference crop");
        }
        int[] result = new int[INPUT_SIZE * INPUT_SIZE];
        for (int y = 0; y < INPUT_SIZE; y++) {
            for (int x = 0; x < INPUT_SIZE; x++) {
                int top = (y * 2) * sourceWidth + x * 2;
                int p00 = sourceArgb[top];
                int p01 = sourceArgb[top + 1];
                int p10 = sourceArgb[top + sourceWidth];
                int p11 = sourceArgb[top + sourceWidth + 1];
                int red = roundedAverage4(red(p00), red(p01), red(p10), red(p11));
                int green = roundedAverage4(green(p00), green(p01), green(p10), green(p11));
                int blue = roundedAverage4(blue(p00), blue(p01), blue(p10), blue(p11));
                result[y * INPUT_SIZE + x] = argb(red, green, blue);
            }
        }
        return result;
    }

    static int[] upscale2xBilinear(int[] sourceArgb, int sourceWidth, int sourceHeight) {
        if (sourceWidth != INPUT_SIZE || sourceHeight != INPUT_SIZE
                || sourceArgb.length != sourceWidth * sourceHeight) {
            throw new IllegalArgumentException("Expected one 64x64 ARGB low-resolution input");
        }
        int[] result = new int[OUTPUT_SIZE * OUTPUT_SIZE];
        for (int y = 0; y < OUTPUT_SIZE; y++) {
            float sourceY = clampCoordinate(((y + 0.5f) / 2.0f) - 0.5f, sourceHeight);
            int y0 = (int) Math.floor(sourceY);
            int y1 = clampIndex(y0 + 1, sourceHeight);
            float fy = sourceY - y0;
            for (int x = 0; x < OUTPUT_SIZE; x++) {
                float sourceX = clampCoordinate(
                        ((x + 0.5f) / 2.0f) - 0.5f,
                        sourceWidth);
                int x0 = (int) Math.floor(sourceX);
                int x1 = clampIndex(x0 + 1, sourceWidth);
                float fx = sourceX - x0;
                int p00 = sourceArgb[y0 * sourceWidth + x0];
                int p01 = sourceArgb[y0 * sourceWidth + x1];
                int p10 = sourceArgb[y1 * sourceWidth + x0];
                int p11 = sourceArgb[y1 * sourceWidth + x1];
                int red = bilinearChannel(red(p00), red(p01), red(p10), red(p11), fx, fy);
                int green = bilinearChannel(
                        green(p00), green(p01), green(p10), green(p11), fx, fy);
                int blue = bilinearChannel(blue(p00), blue(p01), blue(p10), blue(p11), fx, fy);
                result[y * OUTPUT_SIZE + x] = argb(red, green, blue);
            }
        }
        return result;
    }

    static float[] argbToNchw(int[] argb, int width, int height) {
        if (width <= 0 || height <= 0 || argb.length != width * height) {
            throw new IllegalArgumentException("ARGB dimensions do not match the payload");
        }
        int plane = width * height;
        float[] result = new float[plane * 3];
        for (int index = 0; index < plane; index++) {
            int pixel = argb[index];
            result[index] = red(pixel) / 255.0f;
            result[plane + index] = green(pixel) / 255.0f;
            result[plane * 2 + index] = blue(pixel) / 255.0f;
        }
        return result;
    }

    static int[] nchwToArgb(float[] nchw, int width, int height) {
        int plane = width * height;
        if (width <= 0 || height <= 0 || nchw.length != plane * 3) {
            throw new IllegalArgumentException("NCHW dimensions do not match the payload");
        }
        int[] result = new int[plane];
        for (int index = 0; index < plane; index++) {
            int red = normalizedToByte(nchw[index]);
            int green = normalizedToByte(nchw[plane + index]);
            int blue = normalizedToByte(nchw[plane * 2 + index]);
            result[index] = argb(red, green, blue);
        }
        return result;
    }

    static double psnrRgb8(int[] referenceArgb, int[] candidateArgb) {
        if (referenceArgb.length == 0 || referenceArgb.length != candidateArgb.length) {
            throw new IllegalArgumentException("PSNR inputs must have the same non-zero length");
        }
        double squaredError = 0.0;
        for (int index = 0; index < referenceArgb.length; index++) {
            int reference = referenceArgb[index];
            int candidate = candidateArgb[index];
            squaredError += square(red(reference) - red(candidate));
            squaredError += square(green(reference) - green(candidate));
            squaredError += square(blue(reference) - blue(candidate));
        }
        double mse = squaredError / (referenceArgb.length * 3.0);
        return mse == 0.0 ? Double.POSITIVE_INFINITY : 10.0 * Math.log10(255.0 * 255.0 / mse);
    }

    private static int roundedAverage4(int a, int b, int c, int d) {
        return (a + b + c + d + 2) / 4;
    }

    private static int bilinearChannel(int p00, int p01, int p10, int p11, float fx, float fy) {
        float top = p00 + (p01 - p00) * fx;
        float bottom = p10 + (p11 - p10) * fx;
        return clampByte(Math.round(top + (bottom - top) * fy));
    }

    private static int normalizedToByte(float value) {
        if (!Float.isFinite(value)) {
            return 0;
        }
        return clampByte(Math.round(clamp01(value) * 255.0f));
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static int clampIndex(int value, int size) {
        return Math.max(0, Math.min(size - 1, value));
    }

    private static float clampCoordinate(float value, int size) {
        return Math.max(0.0f, Math.min(size - 1.0f, value));
    }

    private static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static int argb(int red, int green, int blue) {
        return 0xff000000 | (red << 16) | (green << 8) | blue;
    }

    private static int red(int argb) {
        return (argb >>> 16) & 0xff;
    }

    private static int green(int argb) {
        return (argb >>> 8) & 0xff;
    }

    private static int blue(int argb) {
        return argb & 0xff;
    }

    private static double square(int value) {
        return (double) value * value;
    }
}
