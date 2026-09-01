package dev.aisystems.quicksrplayerlab;

enum ModelVariant {
    CANONICAL(
            "canonical-dynamic-with-fixed-session-symbols",
            "quicksrnet-small-2x-opset17.onnx",
            "upscaled_image",
            new long[]{1, 3, 64, 64},
            new long[]{1, 3, 128, 128},
            false),
    FIXED64_PRE_SHUFFLE_CORE(
            "fixed64-pre-shuffle-core",
            "quicksrnet-small-2x-fixed64-core.onnx",
            "pre_shuffle_output",
            new long[]{1, 3, 64, 64},
            new long[]{1, 12, 64, 64},
            true),
    FIXED64_DCR_FULL(
            "fixed64-dcr-full",
            "quicksrnet-small-2x-fixed64-dcr.onnx",
            "upscaled_image",
            new long[]{1, 3, 64, 64},
            new long[]{1, 3, 128, 128},
            false),
    FIXED256_DCR_FULL(
            "fixed256-dcr-full",
            "quicksrnet-small-2x-fixed256-dcr.onnx",
            "upscaled_image",
            new long[]{1, 3, 256, 256},
            new long[]{1, 3, 512, 512},
            false),
    FIXED256X144_DCR_FULL(
            "fixed256x144-dcr-full",
            "quicksrnet-small-2x-fixed256x144-dcr.onnx",
            "upscaled_image",
            new long[]{1, 3, 144, 256},
            new long[]{1, 3, 288, 512},
            false),
    FIXED512X288_DCR_FULL(
            "fixed512x288-dcr-full",
            "quicksrnet-small-2x-fixed512x288-dcr.onnx",
            "upscaled_image",
            new long[]{1, 3, 288, 512},
            new long[]{1, 3, 576, 1024},
            false),
    FIXED640X360_DCR_FULL(
            "fixed640x360-dcr-full",
            "quicksrnet-small-2x-fixed640x360-dcr.onnx",
            "upscaled_image",
            new long[]{1, 3, 360, 640},
            new long[]{1, 3, 720, 1280},
            false),
    FIXED640X360_3X_FULL(
            "fixed640x360-3x-full",
            "quicksrnet-small-3x-fixed640x360.onnx",
            "upscaled_image",
            new long[]{1, 3, 360, 640},
            new long[]{1, 3, 1080, 1920},
            false),
    FIXED640X360_4X_FULL(
            "fixed640x360-4x-full",
            "quicksrnet-small-4x-fixed640x360.onnx",
            "upscaled_image",
            new long[]{1, 3, 360, 640},
            new long[]{1, 3, 1440, 2560},
            false),
    FIXED512_DCR_FULL(
            "fixed512-dcr-full",
            "quicksrnet-small-2x-fixed512-dcr.onnx",
            "upscaled_image",
            new long[]{1, 3, 512, 512},
            new long[]{1, 3, 1024, 1024},
            false);

    private final String id;
    private final String asset;
    private final String outputName;
    private final long[] sessionInputShape;
    private final long[] sessionOutputShape;
    private final boolean crdPixelShuffleRequired;

    ModelVariant(
            String id,
            String asset,
            String outputName,
            long[] sessionInputShape,
            long[] sessionOutputShape,
            boolean crdPixelShuffleRequired) {
        this.id = id;
        this.asset = asset;
        this.outputName = outputName;
        this.sessionInputShape = sessionInputShape.clone();
        this.sessionOutputShape = sessionOutputShape.clone();
        this.crdPixelShuffleRequired = crdPixelShuffleRequired;
    }

    String id() {
        return id;
    }

    String asset() {
        return asset;
    }

    String outputName() {
        return outputName;
    }

    long[] sessionInputShape() {
        return sessionInputShape.clone();
    }

    long[] sessionOutputShape() {
        return sessionOutputShape.clone();
    }

    int inputSide() {
        return squareSpatialSide(sessionInputShape, "input");
    }

    int outputSide() {
        return squareSpatialSide(sessionOutputShape, "output");
    }

    int inputWidth() {
        return spatialDimension(sessionInputShape, 3, "input width");
    }

    int inputHeight() {
        return spatialDimension(sessionInputShape, 2, "input height");
    }

    int outputWidth() {
        return spatialDimension(sessionOutputShape, 3, "output width");
    }

    int outputHeight() {
        return spatialDimension(sessionOutputShape, 2, "output height");
    }

    int inputValueCount() {
        return elementCount(sessionInputShape, "input");
    }

    int outputValueCount() {
        return elementCount(sessionOutputShape, "output");
    }

    boolean requiresCrdPixelShuffle() {
        return crdPixelShuffleRequired;
    }

    private static int squareSpatialSide(long[] shape, String tensorKind) {
        if (shape.length != 4 || shape[0] != 1 || shape[2] <= 0 || shape[2] != shape[3]) {
            throw new IllegalStateException(
                    "QuickSR " + tensorKind + " shape must be static square NCHW");
        }
        return Math.toIntExact(shape[2]);
    }

    private static int spatialDimension(long[] shape, int index, String tensorKind) {
        if (shape.length != 4 || shape[0] != 1 || shape[index] <= 0) {
            throw new IllegalStateException(
                    "QuickSR " + tensorKind + " must be static NCHW");
        }
        return Math.toIntExact(shape[index]);
    }

    private static int elementCount(long[] shape, String tensorKind) {
        long values = 1;
        for (long dimension : shape) {
            if (dimension <= 0) {
                throw new IllegalStateException(
                        "QuickSR " + tensorKind + " shape must be fully static");
            }
            values = Math.multiplyExact(values, dimension);
        }
        return Math.toIntExact(values);
    }

    long expectedBytes() {
        switch (this) {
            case CANONICAL:
                return BuildConfig.MODEL_BYTES;
            case FIXED64_PRE_SHUFFLE_CORE:
                return BuildConfig.CORE_MODEL_BYTES;
            case FIXED64_DCR_FULL:
                return BuildConfig.DCR_MODEL_BYTES;
            case FIXED256_DCR_FULL:
                return BuildConfig.DCR256_MODEL_BYTES;
            case FIXED256X144_DCR_FULL:
                return BuildConfig.DCR256X144_MODEL_BYTES;
            case FIXED512X288_DCR_FULL:
                return BuildConfig.DCR512X288_MODEL_BYTES;
            case FIXED640X360_DCR_FULL:
                return BuildConfig.DCR640X360_MODEL_BYTES;
            case FIXED640X360_3X_FULL:
                return BuildConfig.FIXED640X360_3X_MODEL_BYTES;
            case FIXED640X360_4X_FULL:
                return BuildConfig.FIXED640X360_4X_MODEL_BYTES;
            case FIXED512_DCR_FULL:
                return BuildConfig.DCR512_MODEL_BYTES;
            default:
                throw new IllegalStateException("Unhandled model variant: " + this);
        }
    }

    String expectedSha256() {
        switch (this) {
            case CANONICAL:
                return BuildConfig.MODEL_SHA256;
            case FIXED64_PRE_SHUFFLE_CORE:
                return BuildConfig.CORE_MODEL_SHA256;
            case FIXED64_DCR_FULL:
                return BuildConfig.DCR_MODEL_SHA256;
            case FIXED256_DCR_FULL:
                return BuildConfig.DCR256_MODEL_SHA256;
            case FIXED256X144_DCR_FULL:
                return BuildConfig.DCR256X144_MODEL_SHA256;
            case FIXED512X288_DCR_FULL:
                return BuildConfig.DCR512X288_MODEL_SHA256;
            case FIXED640X360_DCR_FULL:
                return BuildConfig.DCR640X360_MODEL_SHA256;
            case FIXED640X360_3X_FULL:
                return BuildConfig.FIXED640X360_3X_MODEL_SHA256;
            case FIXED640X360_4X_FULL:
                return BuildConfig.FIXED640X360_4X_MODEL_SHA256;
            case FIXED512_DCR_FULL:
                return BuildConfig.DCR512_MODEL_SHA256;
            default:
                throw new IllegalStateException("Unhandled model variant: " + this);
        }
    }
}
