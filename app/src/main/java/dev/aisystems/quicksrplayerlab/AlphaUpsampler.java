package dev.aisystems.quicksrplayerlab;

final class AlphaUpsampler {
    private AlphaUpsampler() {
    }

    static int applyNearest2x(
            int rgb,
            int[] sourceArgb,
            int sourceStride,
            int sourceOriginX,
            int sourceOriginY,
            int outputX,
            int outputY) {
        int sourceX = sourceOriginX + outputX / TilePlan.SCALE;
        int sourceY = sourceOriginY + outputY / TilePlan.SCALE;
        int alpha = sourceArgb[sourceY * sourceStride + sourceX] & 0xff000000;
        return alpha | (rgb & 0x00ffffff);
    }
}
