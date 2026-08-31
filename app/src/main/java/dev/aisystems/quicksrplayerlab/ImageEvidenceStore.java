package dev.aisystems.quicksrplayerlab;

import android.content.Context;
import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;

final class ImageEvidenceStore {
    private ImageEvidenceStore() {
    }

    static JSONArray write(
            Context context,
            String runId,
            Bitmap reference,
            Bitmap lowResolution,
            Bitmap bilinear,
            Bitmap qnnOutput) throws Exception {
        String safeRunId = runId.replaceAll("[^A-Za-z0-9._-]", "_");
        File root = new File(context.getFilesDir(), "image-evaluations");
        File directory = new File(root, safeRunId);
        File pendingDirectory = new File(root, "." + safeRunId + ".pending");
        String canonicalRoot = root.getCanonicalPath() + File.separator;
        String canonicalDirectory = directory.getCanonicalPath();
        String canonicalPending = pendingDirectory.getCanonicalPath();
        if (!canonicalDirectory.startsWith(canonicalRoot)
                || !canonicalPending.startsWith(canonicalRoot)) {
            throw new IOException("Image evidence directory escaped app-private storage");
        }
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IOException("Could not create app-private image evidence root");
        }
        if (directory.exists() || pendingDirectory.exists()) {
            throw new IOException("Refusing to overwrite image evidence for run " + safeRunId);
        }
        if (!pendingDirectory.mkdir()) {
            throw new IOException("Could not create app-private image evidence directory");
        }

        try {
            JSONArray artifacts = new JSONArray();
            artifacts.put(writePng(
                    pendingDirectory, "reference-hr-128.png", reference));
            artifacts.put(writePng(
                    pendingDirectory, "input-lr-64.png", lowResolution));
            artifacts.put(writePng(
                    pendingDirectory, "baseline-bilinear-128.png", bilinear));
            artifacts.put(writePng(
                    pendingDirectory, "qnn-htp-128.png", qnnOutput));
            if (!pendingDirectory.renameTo(directory)) {
                throw new IOException("Could not atomically publish the image evidence set");
            }
            return artifacts;
        } catch (Throwable failure) {
            cleanupPendingDirectory(pendingDirectory);
            if (failure instanceof Exception) {
                throw (Exception) failure;
            }
            throw new RuntimeException(failure);
        }
    }

    private static JSONObject writePng(File directory, String name, Bitmap bitmap)
            throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
            throw new IOException("Bitmap PNG encoding failed: " + name);
        }
        byte[] bytes = output.toByteArray();
        File target = new File(directory, name);
        File temporary = new File(directory, "." + name + ".tmp");
        if (target.exists() || temporary.exists()) {
            throw new IOException("Refusing to overwrite pending image evidence: " + name);
        }
        try (FileOutputStream stream = new FileOutputStream(temporary, false)) {
            stream.write(bytes);
            stream.getFD().sync();
        }
        if (!temporary.renameTo(target)) {
            throw new IOException("Could not finalize image evidence: " + name);
        }

        JSONObject artifact = new JSONObject();
        artifact.put("file", name);
        artifact.put("bytes", bytes.length);
        artifact.put("sha256", hex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        artifact.put("width", bitmap.getWidth());
        artifact.put("height", bitmap.getHeight());
        artifact.put("format", "PNG");
        artifact.put("storage", "APP_PRIVATE_NO_UPLOAD");
        return artifact;
    }

    private static void cleanupPendingDirectory(File directory) {
        File[] children = directory.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isFile()) {
                    child.delete();
                }
            }
        }
        directory.delete();
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }
}
