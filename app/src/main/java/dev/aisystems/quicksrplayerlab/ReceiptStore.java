package dev.aisystems.quicksrplayerlab;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

final class ReceiptStore {
    private ReceiptStore() {
    }

    static String newRunId() {
        String time = new SimpleDateFormat("yyyyMMdd'T'HHmmss.SSSZ", Locale.US).format(new Date());
        return time + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    static File write(Context context, ProbeResult result) throws IOException, JSONException {
        JSONObject receipt = result.receipt();
        File directory = new File(context.getFilesDir(), "receipts");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create receipt directory: " + directory.getAbsolutePath());
        }

        String runId = receipt.getString("runId");
        String backend = receipt.optString("backendRequested", "unknown");
        String status = receipt.optString("status", "unknown");
        String safeName = (runId + "-" + backend + "-" + status).replaceAll("[^A-Za-z0-9._-]", "_");
        File target = new File(directory, safeName + ".json");
        File temporary = new File(directory, safeName + ".tmp");
        byte[] outputBytes = result.outputFloat32LittleEndian();
        File outputTarget = new File(directory, safeName + ".output.f32le");
        File outputTemporary = new File(directory, safeName + ".output.tmp");
        if (target.exists() || temporary.exists() || outputTarget.exists() || outputTemporary.exists()) {
            throw new IOException("Refusing to overwrite an existing receipt: " + target.getAbsolutePath());
        }

        if (outputBytes != null) {
            JSONObject artifact = new JSONObject();
            artifact.put("file", outputTarget.getName());
            artifact.put("bytes", outputBytes.length);
            artifact.put("sha256", sha256(outputBytes));
            artifact.put("dtype", "float32");
            artifact.put("byteOrder", "little-endian");
            artifact.put("shape", receipt.getJSONObject("structuralSanityValidation").getJSONArray("shape"));
            receipt.put("outputArtifact", artifact);
        }
        receipt.put("receiptFile", target.getName());
        byte[] bytes = receipt.toString(2).getBytes(StandardCharsets.UTF_8);
        if (outputBytes != null) {
            Files.write(outputTemporary.toPath(), outputBytes,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
        Files.write(temporary.toPath(), bytes,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            if (outputBytes != null) {
                moveAtomically(outputTemporary, outputTarget);
            }
            moveAtomically(temporary, target);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(outputTemporary.toPath());
            Files.deleteIfExists(outputTarget.toPath());
            Files.deleteIfExists(temporary.toPath());
            throw failure;
        }
        return target;
    }

    private static void moveAtomically(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source.toPath(), target.toPath());
        }
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                value.append(String.format(Locale.US, "%02x", item));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }
}
