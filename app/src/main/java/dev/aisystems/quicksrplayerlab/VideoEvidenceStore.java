package dev.aisystems.quicksrplayerlab;

import android.content.Context;

import androidx.media3.common.util.UnstableApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * Writes one explicitly requested video-frame tensor pair into app-private storage.
 *
 * <p>This class deliberately has no MediaStore or shared-storage path. The caller must opt in
 * through a benchmark capture selector, and the resulting directory can be retrieved from a
 * debuggable build with {@code adb exec-out run-as} without exposing a private URI in telemetry.
 */
@UnstableApi
final class VideoEvidenceStore {
    static final String ROOT_DIRECTORY = "video-evaluations";
    static final String INPUT_FILE = "input.f32le";
    static final String OUTPUT_FILE = "output.f32le";
    static final String METADATA_FILE = "metadata.json";

    private VideoEvidenceStore() {
    }

    static final class CaptureSpec {
        enum Kind {
            NONE,
            FRAME,
            PTS_US
        }

        private static final CaptureSpec NONE = new CaptureSpec(Kind.NONE, -1L);

        private final Kind kind;
        private final long value;

        private CaptureSpec(Kind kind, long value) {
            this.kind = kind;
            this.value = value;
        }

        static CaptureSpec none() {
            return NONE;
        }

        static CaptureSpec forFrame(int frame) {
            if (frame < 1) {
                throw new IllegalArgumentException("capture frame must be one-based and positive");
            }
            return new CaptureSpec(Kind.FRAME, frame);
        }

        static CaptureSpec forPresentationTimeUs(long presentationTimeUs) {
            if (presentationTimeUs < 0L) {
                throw new IllegalArgumentException("capture presentation time must be non-negative");
            }
            return new CaptureSpec(Kind.PTS_US, presentationTimeUs);
        }

        boolean isRequested() {
            return kind != Kind.NONE;
        }

        boolean matches(int frame, long presentationTimeUs) {
            return (kind == Kind.FRAME && value == frame)
                    || (kind == Kind.PTS_US && value == presentationTimeUs);
        }

        boolean hasBeenMissedBy(int frame, long presentationTimeUs) {
            return (kind == Kind.FRAME && frame > value)
                    || (kind == Kind.PTS_US && presentationTimeUs > value);
        }

        String telemetryKind() {
            switch (kind) {
                case FRAME:
                    return "frame";
                case PTS_US:
                    return "ptsUs";
                case NONE:
                default:
                    return "none";
            }
        }

        long value() {
            return value;
        }

        JSONObject toJson() throws Exception {
            JSONObject result = new JSONObject();
            switch (kind) {
                case FRAME:
                    result.put("kind", "frame");
                    result.put("value", value);
                    break;
                case PTS_US:
                    result.put("kind", "ptsUs");
                    result.put("value", value);
                    break;
                case NONE:
                default:
                    result.put("kind", "none");
                    break;
            }
            return result;
        }
    }

    static boolean isSafeRunId(String value) {
        return value != null && value.length() >= 1 && value.length() <= 80
                && value.matches("[A-Za-z0-9._-]+");
    }

    static String relativeDirectory(String runId) {
        if (!isSafeRunId(runId)) {
            throw new IllegalArgumentException("video evidence requires a safe benchmark run id");
        }
        return ROOT_DIRECTORY + "/" + runId;
    }

    static String relativeTensorPath(String runId, String fileName) {
        if (!INPUT_FILE.equals(fileName) && !OUTPUT_FILE.equals(fileName)) {
            throw new IllegalArgumentException("unsupported video evidence tensor file");
        }
        return relativeDirectory(runId) + "/" + fileName;
    }

    static JSONObject write(
            Context context,
            String runId,
            CaptureSpec selector,
            int frame,
            long presentationTimeUs,
            QuickSrVideoEffect.Profile profile,
            float[] input,
            float[] output,
            JSONObject qnnStrict) throws Exception {
        if (!isSafeRunId(runId)) {
            throw new IllegalArgumentException("video evidence requires a safe benchmark run id");
        }
        if (selector == null || !selector.isRequested()) {
            throw new IllegalArgumentException("video evidence requires an explicit capture selector");
        }
        if (!selector.matches(frame, presentationTimeUs)) {
            throw new IllegalArgumentException("video evidence selector does not match captured frame");
        }
        if (qnnStrict == null || !qnnStrict.optBoolean("strictReady", false)) {
            throw new IllegalStateException("video evidence requires QNN strict registration evidence");
        }
        verifyTensorLength(input, profile.inputWidth(), profile.inputHeight(), "input");
        verifyTensorLength(output, profile.outputWidth(), profile.outputHeight(), "output");

        File root = new File(context.getFilesDir(), ROOT_DIRECTORY);
        File directory = new File(root, runId);
        File pendingDirectory = new File(root, "." + runId + ".pending");
        requireContained(root, directory, pendingDirectory);
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IOException("Could not create app-private video evidence root");
        }
        if (directory.exists() || pendingDirectory.exists()) {
            throw new IOException("Refusing to overwrite video evidence for run " + runId);
        }
        if (!pendingDirectory.mkdir()) {
            throw new IOException("Could not create pending video evidence directory");
        }

        try {
            TensorArtifact inputArtifact = writeTensor(
                    pendingDirectory, INPUT_FILE, input, inputShape(profile));
            TensorArtifact outputArtifact = writeTensor(
                    pendingDirectory, OUTPUT_FILE, output, outputShape(profile));
            JSONObject metadata = metadata(
                    runId,
                    selector,
                    frame,
                    presentationTimeUs,
                    profile,
                    inputArtifact,
                    outputArtifact,
                    qnnStrict);
            writeBytesAtomically(
                    pendingDirectory,
                    METADATA_FILE,
                    (metadata.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
            if (!pendingDirectory.renameTo(directory)) {
                throw new IOException("Could not atomically publish video evidence");
            }
            return metadata;
        } catch (Throwable failure) {
            cleanupPendingDirectory(pendingDirectory);
            if (failure instanceof Exception) {
                throw (Exception) failure;
            }
            throw new RuntimeException(failure);
        }
    }

    static JSONObject metadata(
            String runId,
            CaptureSpec selector,
            int frame,
            long presentationTimeUs,
            QuickSrVideoEffect.Profile profile,
            TensorArtifact input,
            TensorArtifact output,
            JSONObject qnnStrict) throws Exception {
        String relativeDirectory = relativeDirectory(runId);
        JSONObject result = new JSONObject();
        result.put("schemaVersion", 1);
        result.put("kind", "quicksr-video-frame-evidence");
        result.put("storage", "APP_PRIVATE_NO_UPLOAD");
        result.put("createdAt", Instant.now().toString());
        result.put("runId", runId);
        result.put("relativeDirectory", relativeDirectory);

        JSONObject capture = new JSONObject();
        capture.put("selector", selector.toJson());
        capture.put("frame", frame);
        capture.put("ptsUs", presentationTimeUs);
        result.put("capture", capture);

        ModelVariant model = profile.modelVariant();
        JSONObject profileJson = new JSONObject();
        profileJson.put("name", profile.name());
        profileJson.put("modelVariant", model.id());
        profileJson.put("modelAsset", model.asset());
        profileJson.put("modelSha256", model.expectedSha256());
        profileJson.put("modelBytes", model.expectedBytes());
        profileJson.put("inputShape", toJsonArray(inputShape(profile)));
        profileJson.put("outputShape", toJsonArray(outputShape(profile)));
        profileJson.put("canvasWidth", profile.canvasWidth());
        profileJson.put("canvasHeight", profile.canvasHeight());
        result.put("profile", profileJson);

        JSONObject tensors = new JSONObject();
        tensors.put("input", input.toJson(relativeDirectory));
        tensors.put("output", output.toJson(relativeDirectory));
        result.put("tensors", tensors);

        JSONObject appBuild = new JSONObject();
        appBuild.put("versionCode", BuildConfig.VERSION_CODE);
        appBuild.put("versionName", BuildConfig.VERSION_NAME);
        appBuild.put("sourceIdentitySha256", BuildConfig.APP_SOURCE_SHA256);
        appBuild.put("prototypeBuildId", BuildConfig.PROTOTYPE_BUILD_ID);
        result.put("appBuild", appBuild);

        // Reparse so the caller cannot mutate the persisted object after this method returns.
        result.put("qnnStrict", new JSONObject(qnnStrict.toString()));
        return result;
    }

    private static TensorArtifact writeTensor(
            File directory,
            String name,
            float[] values,
            long[] shape) throws Exception {
        File target = new File(directory, name);
        File temporary = new File(directory, "." + name + ".tmp");
        if (target.exists() || temporary.exists()) {
            throw new IOException("Refusing to overwrite pending video tensor: " + name);
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long bytes = 0L;
        ByteBuffer buffer = ByteBuffer.allocate(16 * 1024).order(ByteOrder.LITTLE_ENDIAN);
        try (FileOutputStream stream = new FileOutputStream(temporary, false)) {
            for (float value : values) {
                if (buffer.remaining() < Float.BYTES) {
                    bytes += writeBuffer(stream, digest, buffer);
                }
                buffer.putFloat(value);
            }
            bytes += writeBuffer(stream, digest, buffer);
            stream.getFD().sync();
        }
        if (!temporary.renameTo(target)) {
            throw new IOException("Could not finalize video tensor: " + name);
        }
        return new TensorArtifact(name, shape, values.length, bytes, hex(digest.digest()));
    }

    private static long writeBuffer(FileOutputStream stream, MessageDigest digest, ByteBuffer buffer)
            throws IOException {
        int length = buffer.position();
        if (length == 0) {
            return 0L;
        }
        stream.write(buffer.array(), 0, length);
        digest.update(buffer.array(), 0, length);
        buffer.clear();
        return length;
    }

    private static void writeBytesAtomically(File directory, String name, byte[] bytes)
            throws IOException {
        File target = new File(directory, name);
        File temporary = new File(directory, "." + name + ".tmp");
        if (target.exists() || temporary.exists()) {
            throw new IOException("Refusing to overwrite pending video evidence: " + name);
        }
        try (FileOutputStream stream = new FileOutputStream(temporary, false)) {
            stream.write(bytes);
            stream.getFD().sync();
        }
        if (!temporary.renameTo(target)) {
            throw new IOException("Could not finalize video evidence: " + name);
        }
    }

    private static void requireContained(File root, File directory, File pendingDirectory)
            throws IOException {
        String rootPath = root.getCanonicalPath() + File.separator;
        if (!directory.getCanonicalPath().startsWith(rootPath)
                || !pendingDirectory.getCanonicalPath().startsWith(rootPath)) {
            throw new IOException("Video evidence directory escaped app-private storage");
        }
    }

    private static void verifyTensorLength(float[] values, int width, int height, String label) {
        int expected = Math.multiplyExact(3, Math.multiplyExact(width, height));
        if (values == null || values.length != expected) {
            throw new IllegalArgumentException(
                    "Unexpected video " + label + " tensor length: expected " + expected);
        }
    }

    private static long[] inputShape(QuickSrVideoEffect.Profile profile) {
        return new long[]{1L, 3L, profile.inputHeight(), profile.inputWidth()};
    }

    private static long[] outputShape(QuickSrVideoEffect.Profile profile) {
        return new long[]{1L, 3L, profile.outputHeight(), profile.outputWidth()};
    }

    private static JSONArray toJsonArray(long[] values) {
        JSONArray result = new JSONArray();
        for (long value : values) {
            result.put(value);
        }
        return result;
    }

    private static void cleanupPendingDirectory(File directory) {
        deleteIfFile(new File(directory, INPUT_FILE));
        deleteIfFile(new File(directory, OUTPUT_FILE));
        deleteIfFile(new File(directory, METADATA_FILE));
        deleteIfFile(new File(directory, "." + INPUT_FILE + ".tmp"));
        deleteIfFile(new File(directory, "." + OUTPUT_FILE + ".tmp"));
        deleteIfFile(new File(directory, "." + METADATA_FILE + ".tmp"));
        directory.delete();
    }

    private static void deleteIfFile(File file) {
        if (file.isFile()) {
            file.delete();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    static final class TensorArtifact {
        private final String file;
        private final long[] shape;
        private final int elementCount;
        private final long bytes;
        private final String sha256;

        TensorArtifact(String file, long[] shape, int elementCount, long bytes, String sha256) {
            this.file = file;
            this.shape = shape.clone();
            this.elementCount = elementCount;
            this.bytes = bytes;
            this.sha256 = sha256;
        }

        JSONObject toJson(String relativeDirectory) throws Exception {
            JSONObject result = new JSONObject();
            result.put("file", file);
            result.put("relativePath", relativeDirectory + "/" + file);
            result.put("dtype", "float32");
            result.put("byteOrder", "little-endian");
            result.put("shape", toJsonArray(shape));
            result.put("elementCount", elementCount);
            result.put("bytes", bytes);
            result.put("sha256LittleEndianFloat32", sha256);
            return result;
        }
    }
}
