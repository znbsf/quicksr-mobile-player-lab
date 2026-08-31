package dev.aisystems.quicksrplayerlab;

import java.util.HashMap;
import java.util.EnumSet;
import java.util.Map;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.providers.NNAPIFlags;

enum Backend {
    CPU("CPU (default)", false) {
        @Override
        void configure(OrtSession.SessionOptions options) throws OrtException {
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
        }
    },
    XNNPACK("XNNPACK", true) {
        @Override
        void configure(OrtSession.SessionOptions options) throws OrtException {
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            options.addConfigEntry("session.intra_op.allow_spinning", "0");
            disableCpuFallback(options);
            Map<String, String> providerOptions = new HashMap<>();
            providerOptions.put("intra_op_num_threads", "4");
            options.addXnnpack(providerOptions);
        }
    },
    NNAPI("NNAPI", true) {
        @Override
        void configure(OrtSession.SessionOptions options) throws OrtException {
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            disableCpuFallback(options);
            options.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED));
        }
    },
    XNNPACK_HYBRID("XNNPACK + explicit CPU fallback", false) {
        @Override
        void configure(OrtSession.SessionOptions options) throws OrtException {
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            options.addConfigEntry("session.intra_op.allow_spinning", "0");
            Map<String, String> providerOptions = new HashMap<>();
            providerOptions.put("intra_op_num_threads", "4");
            options.addXnnpack(providerOptions);
        }
    },
    NNAPI_HYBRID("NNAPI + explicit CPU fallback", false) {
        @Override
        void configure(OrtSession.SessionOptions options) throws OrtException {
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            options.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED));
        }
    },
    XNNPACK_CORE_STRICT(
            "XNNPACK fixed core + app CRD pixel shuffle (strict)",
            true,
            ModelVariant.FIXED64_PRE_SHUFFLE_CORE) {
        @Override
        void configure(OrtSession.SessionOptions options) throws OrtException {
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            options.addConfigEntry("session.intra_op.allow_spinning", "0");
            disableCpuFallback(options);
            Map<String, String> providerOptions = new HashMap<>();
            providerOptions.put("intra_op_num_threads", "4");
            options.addXnnpack(providerOptions);
        }
    },
    XNNPACK_CORE_HYBRID(
            "XNNPACK fixed core diagnostic + app CRD pixel shuffle",
            false,
            ModelVariant.FIXED64_PRE_SHUFFLE_CORE) {
        @Override
        void configure(OrtSession.SessionOptions options) throws OrtException {
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            options.addConfigEntry("session.intra_op.allow_spinning", "0");
            Map<String, String> providerOptions = new HashMap<>();
            providerOptions.put("intra_op_num_threads", "4");
            options.addXnnpack(providerOptions);
        }
    },
    NNAPI_DCR_STRICT(
            "NNAPI fixed DCR full graph (strict, NNAPI CPU disabled)",
            true,
            ModelVariant.FIXED64_DCR_FULL) {
        @Override
        void configure(OrtSession.SessionOptions options) throws OrtException {
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            disableCpuFallback(options);
            options.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED));
        }
    },
    NNAPI_DCR_HYBRID(
            "NNAPI fixed DCR diagnostic (NNAPI CPU disabled)",
            false,
            ModelVariant.FIXED64_DCR_FULL) {
        @Override
        void configure(OrtSession.SessionOptions options) throws OrtException {
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            options.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED));
        }
    },
    QNN_HTP_DCR_STRICT(
            "QNN plugin HTP fixed DCR full graph (strict)",
            true,
            ModelVariant.FIXED64_DCR_FULL) {
        @Override
        void configure(OrtSession.SessionOptions options) throws OrtException {
            throw new IllegalStateException(
                    "QNN plugin backends must be configured with QnnPluginRuntime");
        }
    },
    QNN_HTP_DCR_DIAGNOSTIC(
            "QNN plugin HTP fixed DCR diagnostic (CPU fallback visible)",
            false,
            ModelVariant.FIXED64_DCR_FULL) {
        @Override
        void configure(OrtSession.SessionOptions options) {
            throw new IllegalStateException(
                    "QNN plugin backends must be configured with QnnPluginRuntime");
        }
    };

    private final String label;
    private final boolean cpuFallbackDisabled;
    private final ModelVariant modelVariant;

    Backend(String label, boolean cpuFallbackDisabled) {
        this(label, cpuFallbackDisabled, ModelVariant.CANONICAL);
    }

    Backend(String label, boolean cpuFallbackDisabled, ModelVariant modelVariant) {
        this.label = label;
        this.cpuFallbackDisabled = cpuFallbackDisabled;
        this.modelVariant = modelVariant;
    }

    abstract void configure(OrtSession.SessionOptions options) throws OrtException;

    boolean isCpuFallbackDisabled() {
        return cpuFallbackDisabled;
    }

    ModelVariant modelVariant() {
        return modelVariant;
    }

    boolean usesQnnPlugin() {
        return this == QNN_HTP_DCR_STRICT || this == QNN_HTP_DCR_DIAGNOSTIC;
    }

    static void disableCpuFallback(OrtSession.SessionOptions options) throws OrtException {
        options.addConfigEntry("session.disable_cpu_ep_fallback", "1");
    }

    @Override
    public String toString() {
        return label;
    }
}
