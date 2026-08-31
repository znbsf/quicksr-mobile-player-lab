package dev.aisystems.quicksrplayerlab;

final class DeterministicInputs {
    private DeterministicInputs() {
    }

    static float[] rgbGradientNchw(int width, int height) {
        if (width < 2 || height < 2) {
            throw new IllegalArgumentException("width and height must both be at least 2");
        }
        int plane = Math.multiplyExact(width, height);
        float[] values = new float[Math.multiplyExact(3, plane)];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int offset = y * width + x;
                values[offset] = (float) x / (float) (width - 1);
                values[plane + offset] = (float) y / (float) (height - 1);
                values[(2 * plane) + offset] = (float) (x + y) / (float) (width + height - 2);
            }
        }
        return values;
    }
}
