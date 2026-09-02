package dev.aisystems.quicksrplayerlab;

import android.content.Context;
import android.system.Os;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtEpDevice;
import ai.onnxruntime.OrtHardwareDevice;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.qnnpluginep.QnnPluginEpLibraryKt;

final class QnnPluginRuntime {
    static final String VIDEO_STRICT_EVIDENCE_SCOPE =
            "SESSION_CONFIGURATION_NOT_PER_NODE_PLACEMENT_PROOF";
    private static final ReentrantLock PROCESS_LOCK = new ReentrantLock(true);
    private static final String[] REQUIRED_NATIVE_LIBRARIES = new String[]{
            "libonnxruntime_providers_qnn.so",
            "libQnnHtp.so",
            "libQnnSystem.so",
            "libQnnHtpV73Stub.so",
            "libQnnHtpV73Skel.so"
    };

    private QnnPluginRuntime() {
    }

    static void lockProcess() throws InterruptedException {
        PROCESS_LOCK.lockInterruptibly();
    }

    static void unlockProcess() {
        PROCESS_LOCK.unlock();
    }

    static void prepareProcessEnvironment(Context context, JSONObject evidence) throws Exception {
        String nativeLibraryDirectory = context.getApplicationInfo().nativeLibraryDir;
        String existing = Os.getenv("ADSP_LIBRARY_PATH");
        boolean existingPresent = existing != null && !existing.isBlank();
        boolean alreadyIncluded = existingPresent
                && java.util.Arrays.asList(existing.split(";"))
                .contains(nativeLibraryDirectory);
        String configured = alreadyIncluded
                ? existing
                : nativeLibraryDirectory + (existingPresent ? ";" + existing : "");
        Os.setenv("ADSP_LIBRARY_PATH", configured, true);
        String observed = Os.getenv("ADSP_LIBRARY_PATH");
        boolean configuredByApp = observed != null
                && java.util.Arrays.asList(observed.split(";"))
                .contains(nativeLibraryDirectory);
        evidence.put("adspLibraryPathConfiguredByApp", configuredByApp);
        evidence.put("adspLibraryPathExistingValuePresent", existingPresent);
        evidence.put("adspLibraryPathAppDirectoryPrepended", !alreadyIncluded);
        evidence.put("adspLibraryPathAbsoluteValueCaptured", false);
        evidence.put("adspLibraryPathScope", "APP_PROCESS_ONLY");
        if (!configuredByApp) {
            throw new IllegalStateException(
                    "App native library directory was not added to ADSP_LIBRARY_PATH");
        }
    }

    static Registration registerAndConfigure(
            Context context,
            OrtEnvironment environment,
            OrtSession.SessionOptions options,
            String runId,
            String backendName,
            boolean disableCpuFallback,
            JSONObject evidence) throws Exception {
        return registerAndConfigure(
                context,
                environment,
                options,
                runId,
                backendName,
                disableCpuFallback,
                evidence,
                true,
                QuickSrSession.Tuning.BASELINE);
    }

    static Registration registerForInference(
            Context context,
            OrtEnvironment environment,
            OrtSession.SessionOptions options,
            String runId,
            JSONObject evidence) throws Exception {
        return registerForInference(
                context,
                environment,
                options,
                runId,
                evidence,
                QuickSrSession.Tuning.BASELINE);
    }

    static Registration registerForInference(
            Context context,
            OrtEnvironment environment,
            OrtSession.SessionOptions options,
            String runId,
            JSONObject evidence,
            QuickSrSession.Tuning tuning) throws Exception {
        return registerAndConfigure(
                context,
                environment,
                options,
                runId,
                "QNN_HTP_FULL_IMAGE",
                true,
                evidence,
                false,
                tuning);
    }

    private static Registration registerAndConfigure(
            Context context,
            OrtEnvironment environment,
            OrtSession.SessionOptions options,
            String runId,
            String backendName,
            boolean disableCpuFallback,
            JSONObject evidence,
            boolean captureDetailedEvidence,
            QuickSrSession.Tuning tuning) throws Exception {
        String epName = QnnPluginEpLibraryKt.getEpName();
        String libraryPath = QnnPluginEpLibraryKt.getLibraryPath();
        evidence.put("pluginVersion", BuildConfig.QNN_PLUGIN_VERSION);
        evidence.put("runtimeVersion", BuildConfig.QNN_RUNTIME_VERSION);
        evidence.put("registrationName", epName);
        evidence.put("registrationLibrary", new File(libraryPath).getName());
        evidence.put("backendType", "htp");
        evidence.put("cpuEpFallbackDisabled", disableCpuFallback);
        evidence.put("diagnosticOnly", !disableCpuFallback);
        evidence.put(
                "captureMode",
                captureDetailedEvidence ? "DETAILED_DIAGNOSTIC" : "FUNCTIONAL_INFERENCE");
        evidence.put("registrationStatus", "PENDING");
        evidence.put("npuSelectionStatus", "PENDING");
        evidence.put("providerConfigurationStatus", "PENDING");
        if (!evidence.optBoolean("adspLibraryPathConfiguredByApp", false)) {
            throw new IllegalStateException(
                    "QNN process environment was not prepared before ORT initialization");
        }

        File nativeLibraryDirectory = new File(context.getApplicationInfo().nativeLibraryDir);
        JSONArray nativeLibraryInventory = new JSONArray();
        for (String libraryName : REQUIRED_NATIVE_LIBRARIES) {
            File library = new File(nativeLibraryDirectory, libraryName);
            JSONObject item = artifact(library);
            item.put("required", true);
            nativeLibraryInventory.put(item);
            if (!library.isFile()) {
                evidence.put("nativeLibraryInventory", nativeLibraryInventory);
                throw new IllegalStateException(
                        "Required packaged QNN library is missing: " + libraryName);
            }
        }
        evidence.put("nativeLibraryInventory", nativeLibraryInventory);

        boolean registered = false;
        try {
            environment.registerExecutionProviderLibrary(epName, libraryPath);
            registered = true;
            evidence.put("registrationStatus", "PASS");

            List<OrtEpDevice> allDevices = environment.getEpDevices();
            JSONArray enumeratedDevices = new JSONArray();
            List<OrtEpDevice> selectedNpuDevices = new ArrayList<>();
            for (OrtEpDevice epDevice : allDevices) {
                OrtHardwareDevice device = epDevice.getDevice();
                JSONObject item = new JSONObject();
                item.put("epName", epDevice.getEpName());
                item.put("epVendor", epDevice.getEpVendor());
                item.put("hardwareType", device.getType().name());
                item.put("hardwareVendor", device.getVendor());
                item.put("hardwareVendorId", device.getVendorId());
                item.put("hardwareDeviceId", device.getDeviceId());
                enumeratedDevices.put(item);
                if (isTargetNpu(epDevice)) {
                    selectedNpuDevices.add(epDevice);
                }
            }
            evidence.put("enumeratedEpDevices", enumeratedDevices);
            evidence.put("selectedNpuDeviceCount", selectedNpuDevices.size());
            if (selectedNpuDevices.isEmpty()) {
                evidence.put("npuSelectionStatus", "FAIL");
                throw new IllegalStateException(
                        "QNN plugin registered, but no QNNExecutionProvider NPU device was enumerated");
            }
            evidence.put("npuSelectionStatus", "PASS");

            String safeStem = (runId + "-" + backendName)
                    .replaceAll("[^A-Za-z0-9._-]", "_");
            File qnnProfileFile = null;
            File qnnTraceDirectory = null;
            if (captureDetailedEvidence) {
                File qnnProfileDirectory = requireDirectory(
                        new File(context.getFilesDir(), "qnn-profiles"));
                qnnTraceDirectory = requireDirectory(
                        new File(new File(context.getFilesDir(), "qnn-traces"), safeStem));
                qnnProfileFile = new File(qnnProfileDirectory, safeStem + ".csv");
            }

            Map<String, String> providerOptions = new LinkedHashMap<>();
            providerOptions.put("backend_type", "htp");
            providerOptions.put("offload_graph_io_quantization", "0");
            providerOptions.put("enable_htp_fp16_precision", "0");
            if (tuning != QuickSrSession.Tuning.BASELINE) {
                providerOptions.put("htp_graph_finalization_optimization_mode", "3");
            }
            if (captureDetailedEvidence) {
                providerOptions.put("profiling_level", "detailed");
                providerOptions.put("profiling_file_path", qnnProfileFile.getAbsolutePath());
                providerOptions.put("enable_framework_op_trace", "1");
                providerOptions.put("framework_op_trace_dir", qnnTraceDirectory.getAbsolutePath());
                providerOptions.put("dump_qnn_ep_input_graph", "1");
                providerOptions.put("dump_qnn_ep_input_graph_dir", qnnTraceDirectory.getAbsolutePath());
            }

            if (disableCpuFallback) {
                Backend.disableCpuFallback(options);
            }
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            options.addExecutionProvider(selectedNpuDevices, providerOptions);

            JSONObject publicProviderOptions = new JSONObject();
            publicProviderOptions.put("backend_type", "htp");
            publicProviderOptions.put("offload_graph_io_quantization", "0");
            publicProviderOptions.put("enable_htp_fp16_precision", "0");
            if (tuning != QuickSrSession.Tuning.BASELINE) {
                publicProviderOptions.put("htp_graph_finalization_optimization_mode", "3");
            }
            if (captureDetailedEvidence) {
                publicProviderOptions.put("profiling_level", "detailed");
                publicProviderOptions.put(
                        "profiling_file_path",
                        "qnn-profiles/" + qnnProfileFile.getName());
                publicProviderOptions.put("enable_framework_op_trace", "1");
                publicProviderOptions.put(
                        "framework_op_trace_dir",
                        "qnn-traces/" + qnnTraceDirectory.getName());
                publicProviderOptions.put("dump_qnn_ep_input_graph", "1");
                publicProviderOptions.put(
                        "dump_qnn_ep_input_graph_dir",
                        "qnn-traces/" + qnnTraceDirectory.getName());
            }
            evidence.put("providerOptions", publicProviderOptions);
            evidence.put("providerConfigurationStatus", "PASS");
            return new Registration(
                    environment,
                    epName,
                    qnnProfileFile,
                    qnnTraceDirectory,
                    evidence,
                    captureDetailedEvidence);
        } catch (Throwable failure) {
            evidence.put("providerConfigurationStatus", "FAIL");
            if (registered) {
                try {
                    environment.unregisterExecutionProviderLibrary(epName);
                    evidence.put("unregisterStatus", "PASS_AFTER_CONFIGURATION_FAILURE");
                } catch (Throwable unregisterFailure) {
                    evidence.put("unregisterStatus", "FAIL_AFTER_CONFIGURATION_FAILURE");
                    failure.addSuppressed(unregisterFailure);
                }
            }
            if (failure instanceof Exception) {
                throw (Exception) failure;
            }
            throw new RuntimeException(failure);
        }
    }

    static boolean isTargetNpu(OrtEpDevice epDevice) {
        return QnnPluginEpLibraryKt.getEpName().equals(epDevice.getEpName())
                && epDevice.getDevice().getType()
                == OrtHardwareDevice.OrtHardwareDeviceType.NPU;
    }

    /**
     * Produces the small, path-free subset of QNN registration evidence needed by video gates.
     *
     * <p>The detailed object can contain app-private trace locations and hardware enumeration
     * details, so it must never be placed directly in Logcat or a frame-evidence manifest.
     */
    static JSONObject strictEvidenceSnapshot(JSONObject evidence) throws Exception {
        JSONObject result = new JSONObject();
        String registrationStatus = evidence.optString("registrationStatus", "MISSING");
        String npuSelectionStatus = evidence.optString("npuSelectionStatus", "MISSING");
        String providerConfigurationStatus = evidence.optString(
                "providerConfigurationStatus", "MISSING");
        String backendType = evidence.optString("backendType", "MISSING");
        boolean cpuFallbackDisabled = evidence.optBoolean("cpuEpFallbackDisabled", false);
        boolean diagnosticOnly = evidence.optBoolean("diagnosticOnly", true);
        int selectedNpuDeviceCount = evidence.optInt("selectedNpuDeviceCount", 0);
        boolean strictReady = "PASS".equals(registrationStatus)
                && "PASS".equals(npuSelectionStatus)
                && "PASS".equals(providerConfigurationStatus)
                && "htp".equals(backendType)
                && cpuFallbackDisabled
                && !diagnosticOnly
                && selectedNpuDeviceCount > 0;

        result.put("registrationStatus", registrationStatus);
        result.put("npuSelectionStatus", npuSelectionStatus);
        result.put("providerConfigurationStatus", providerConfigurationStatus);
        result.put("backendType", backendType);
        result.put("cpuEpFallbackDisabled", cpuFallbackDisabled);
        result.put("diagnosticOnly", diagnosticOnly);
        result.put("selectedNpuDeviceCount", selectedNpuDeviceCount);
        result.put("pluginVersion", evidence.optString("pluginVersion", "MISSING"));
        result.put("runtimeVersion", evidence.optString("runtimeVersion", "MISSING"));
        // Session creation with the CPU EP disabled is a strict configuration gate, but it is
        // not a per-node execution-placement trace. Preserve that boundary for downstream
        // validators rather than allowing a registration receipt to be misread as a placement
        // proof.
        result.put("providerAssignmentVerified", false);
        result.put("providerFallbackTraceCaptured", false);
        result.put("evidenceScope", VIDEO_STRICT_EVIDENCE_SCOPE);
        result.put("strictReady", strictReady);
        return result;
    }

    private static File requireDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create app-private QNN evidence directory");
        }
        return directory;
    }

    private static JSONObject artifact(File file) throws Exception {
        JSONObject result = new JSONObject();
        result.put("file", file.getName());
        result.put("present", file.isFile());
        if (file.isFile()) {
            result.put("bytes", file.length());
            result.put("sha256", sha256(file));
        }
        return result;
    }

    private static String sha256(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    private static void putCleanupField(JSONObject target, String key, Object value) {
        try {
            target.put(key, value);
        } catch (Throwable ignored) {
            // Cleanup evidence must never replace the primary inference failure.
        }
    }

    static final class Registration implements AutoCloseable {
        private final OrtEnvironment environment;
        private final String epName;
        private final File profileFile;
        private final File traceDirectory;
        private final JSONObject evidence;
        private final boolean captureDetailedEvidence;
        private boolean finalized;
        private boolean evidenceComplete;

        private Registration(
                OrtEnvironment environment,
                String epName,
                File profileFile,
                File traceDirectory,
                JSONObject evidence,
                boolean captureDetailedEvidence) {
            this.environment = environment;
            this.epName = epName;
            this.profileFile = profileFile;
            this.traceDirectory = traceDirectory;
            this.evidence = evidence;
            this.captureDetailedEvidence = captureDetailedEvidence;
        }

        void captureAndUnregister() throws Exception {
            if (finalized) {
                return;
            }
            finalized = true;
            Throwable artifactFailure = null;
            if (captureDetailedEvidence) {
                try {
                    JSONObject profileArtifact = artifact(profileFile);
                    evidence.put("qnnProfilingArtifact", profileArtifact);
                    if (!profileFile.isFile() || profileFile.length() == 0L) {
                        throw new IllegalStateException(
                                "QNN profiling CSV is missing or empty after the session closed");
                    }
                    JSONArray traceArtifacts = new JSONArray();
                    File[] files = traceDirectory.listFiles(File::isFile);
                    if (files != null) {
                        java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));
                        for (File file : files) {
                            traceArtifacts.put(artifact(file));
                        }
                    }
                    evidence.put("frameworkOpTraceArtifacts", traceArtifacts);
                    if (traceArtifacts.length() == 0) {
                        throw new IllegalStateException(
                                "QNN framework op trace is missing after the session closed");
                    }
                } catch (Throwable failure) {
                    artifactFailure = failure;
                    putCleanupField(evidence, "artifactCaptureStatus", "FAIL");
                }
            } else {
                putCleanupField(evidence, "artifactCaptureStatus", "SKIPPED_FUNCTIONAL_MODE");
            }

            Throwable unregisterFailure = null;
            try {
                environment.unregisterExecutionProviderLibrary(epName);
                putCleanupField(evidence, "unregisterStatus", "PASS");
            } catch (Throwable failure) {
                unregisterFailure = failure;
                putCleanupField(evidence, "unregisterStatus", "FAIL");
                if (artifactFailure == null) {
                    artifactFailure = failure;
                } else {
                    artifactFailure.addSuppressed(failure);
                }
            }
            if (artifactFailure == null) {
                if (captureDetailedEvidence) {
                    putCleanupField(evidence, "artifactCaptureStatus", "PASS");
                }
                evidenceComplete = true;
            } else {
                try {
                    JSONObject error = new JSONObject();
                    error.put("type", artifactFailure.getClass().getName());
                    error.put("message", artifactFailure.getMessage());
                    evidence.put("artifactCaptureError", error);
                } catch (Throwable ignored) {
                    // The primary session or run failure must never be replaced by cleanup reporting.
                }
            }
            if (!captureDetailedEvidence && unregisterFailure != null) {
                throw asException(unregisterFailure);
            }
        }

        @Override
        public void close() throws Exception {
            captureAndUnregister();
        }

        boolean isEvidenceComplete() {
            return evidenceComplete;
        }

        JSONObject strictEvidenceSnapshot() throws Exception {
            return QnnPluginRuntime.strictEvidenceSnapshot(evidence);
        }

        private static Exception asException(Throwable failure) {
            if (failure instanceof Exception) {
                return (Exception) failure;
            }
            if (failure instanceof Error) {
                throw (Error) failure;
            }
            return new RuntimeException(failure);
        }
    }
}
