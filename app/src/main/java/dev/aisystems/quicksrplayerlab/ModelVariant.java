package dev.aisystems.quicksrplayerlab;

enum ModelVariant {
    CANONICAL(
            "canonical-dynamic-with-fixed-session-symbols",
            "quicksrnet-small-2x-opset17.onnx",
            "upscaled_image",
            new long[]{1, 3, 128, 128},
            false),
    FIXED64_PRE_SHUFFLE_CORE(
            "fixed64-pre-shuffle-core",
            "quicksrnet-small-2x-fixed64-core.onnx",
            "pre_shuffle_output",
            new long[]{1, 12, 64, 64},
            true),
    FIXED64_DCR_FULL(
            "fixed64-dcr-full",
            "quicksrnet-small-2x-fixed64-dcr.onnx",
            "upscaled_image",
            new long[]{1, 3, 128, 128},
            false);

    private final String id;
    private final String asset;
    private final String outputName;
    private final long[] sessionOutputShape;
    private final boolean crdPixelShuffleRequired;

    ModelVariant(
            String id,
            String asset,
            String outputName,
            long[] sessionOutputShape,
            boolean crdPixelShuffleRequired) {
        this.id = id;
        this.asset = asset;
        this.outputName = outputName;
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

    long[] sessionOutputShape() {
        return sessionOutputShape.clone();
    }

    boolean requiresCrdPixelShuffle() {
        return crdPixelShuffleRequired;
    }

    long expectedBytes() {
        switch (this) {
            case CANONICAL:
                return BuildConfig.MODEL_BYTES;
            case FIXED64_PRE_SHUFFLE_CORE:
                return BuildConfig.CORE_MODEL_BYTES;
            case FIXED64_DCR_FULL:
                return BuildConfig.DCR_MODEL_BYTES;
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
            default:
                throw new IllegalStateException("Unhandled model variant: " + this);
        }
    }
}
