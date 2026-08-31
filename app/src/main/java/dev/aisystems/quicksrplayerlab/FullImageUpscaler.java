package dev.aisystems.quicksrplayerlab;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;

import java.util.Arrays;
import java.util.List;

final class FullImageUpscaler {
    static final int MAX_OUTPUT_PIXELS = 16_777_216;

    interface ProgressListener {
        void onProgress(int completedTiles, int totalTiles);
    }

    static final class Result {
        final Bitmap bitmap;
        final String backend;
        final int tileCount;
        final int runCount;
        final long elapsedMs;

        Result(Bitmap bitmap, String backend, int tileCount, int runCount, long elapsedMs) {
            this.bitmap = bitmap;
            this.backend = backend;
            this.tileCount = tileCount;
            this.runCount = runCount;
            this.elapsedMs = elapsedMs;
        }
    }

    private FullImageUpscaler() {
    }

    static Result upscale(
            Context context,
            Bitmap source,
            QuickSrSession.Mode mode,
            ProgressListener progressListener) throws Exception {
        Result result = null;
        boolean qnnLocked = mode == QuickSrSession.Mode.QNN_HTP;
        if (qnnLocked) {
            QnnPluginRuntime.lockProcess();
        }
        try (QuickSrSession session = QuickSrSession.open(
                context,
                mode,
                ReceiptStore.newRunId())) {
            result = upscaleWithOpenSession(source, session, progressListener);
            return result;
        } catch (Throwable failure) {
            if (result != null && !result.bitmap.isRecycled()) {
                result.bitmap.recycle();
            }
            if (failure instanceof Exception) {
                throw (Exception) failure;
            }
            throw new RuntimeException(failure);
        } finally {
            if (qnnLocked) {
                QnnPluginRuntime.unlockProcess();
            }
        }
    }

    /**
     * Processes one bitmap with a caller-owned session. Callers using QNN must hold the
     * process-wide QNN lock for the complete lifetime of the session.
     */
    static Result upscaleWithOpenSession(
            Bitmap source,
            QuickSrSession session,
            ProgressListener progressListener) throws Exception {
        int outputWidth = Math.multiplyExact(source.getWidth(), TilePlan.SCALE);
        int outputHeight = Math.multiplyExact(source.getHeight(), TilePlan.SCALE);
        long outputPixels = (long) outputWidth * outputHeight;
        if (outputPixels > MAX_OUTPUT_PIXELS) {
            throw new IllegalArgumentException(
                    "2x output is too large for this build: " + outputWidth + "x" + outputHeight);
        }

        List<TilePlan.Tile> plan = TilePlan.create(source.getWidth(), source.getHeight());
        Bitmap destination = Bitmap.createBitmap(
                outputWidth,
                outputHeight,
                Bitmap.Config.ARGB_8888);
        destination.setHasAlpha(true);
        int[] tileArgb = new int[TilePlan.TILE_SIZE * TilePlan.TILE_SIZE];
        float[] inputNchw = new float[3 * TilePlan.TILE_SIZE * TilePlan.TILE_SIZE];
        int tileOutputSide = TilePlan.TILE_SIZE * TilePlan.SCALE;
        float[] outputNchw = new float[3 * tileOutputSide * tileOutputSide];
        int coreOutputSide = TilePlan.CORE_SIZE * TilePlan.SCALE;
        int[] coreArgb = new int[coreOutputSide * coreOutputSide];

        long startedMs = SystemClock.elapsedRealtime();
        int startingRunCount = session.runCount();
        try {
            int completed = 0;
            for (TilePlan.Tile tile : plan) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Image upscaling was cancelled");
                }
                readTile(source, tile, tileArgb);
                argbToNchw(tileArgb, inputNchw);
                session.infer(inputNchw, outputNchw);
                writeCore(
                        destination,
                        tile,
                        tileArgb,
                        outputNchw,
                        coreArgb,
                        coreOutputSide);
                completed++;
                if (progressListener != null) {
                    progressListener.onProgress(completed, plan.size());
                }
            }
            return new Result(
                    destination,
                    session.backendLabel(),
                    plan.size(),
                    session.runCount() - startingRunCount,
                    SystemClock.elapsedRealtime() - startedMs);
        } catch (Throwable failure) {
            destination.recycle();
            if (failure instanceof Exception) {
                throw (Exception) failure;
            }
            throw new RuntimeException(failure);
        }
    }

    private static void readTile(Bitmap source, TilePlan.Tile tile, int[] tileArgb) {
        Arrays.fill(tileArgb, 0xff000000);
        int sampleX = tile.sampleX();
        int sampleY = tile.sampleY();
        int sourceLeft = Math.max(0, sampleX);
        int sourceTop = Math.max(0, sampleY);
        int sourceRight = Math.min(source.getWidth(), sampleX + TilePlan.TILE_SIZE);
        int sourceBottom = Math.min(source.getHeight(), sampleY + TilePlan.TILE_SIZE);
        int copyWidth = sourceRight - sourceLeft;
        int copyHeight = sourceBottom - sourceTop;
        if (copyWidth <= 0 || copyHeight <= 0) {
            return;
        }
        int destinationX = sourceLeft - sampleX;
        int destinationY = sourceTop - sampleY;
        source.getPixels(
                tileArgb,
                destinationY * TilePlan.TILE_SIZE + destinationX,
                TilePlan.TILE_SIZE,
                sourceLeft,
                sourceTop,
                copyWidth,
                copyHeight);
    }

    private static void argbToNchw(int[] argb, float[] result) {
        int plane = TilePlan.TILE_SIZE * TilePlan.TILE_SIZE;
        for (int index = 0; index < plane; index++) {
            int pixel = argb[index];
            result[index] = ((pixel >>> 16) & 0xff) / 255.0f;
            result[plane + index] = ((pixel >>> 8) & 0xff) / 255.0f;
            result[plane * 2 + index] = (pixel & 0xff) / 255.0f;
        }
    }

    private static void writeCore(
            Bitmap destination,
            TilePlan.Tile tile,
            int[] tileArgb,
            float[] output,
            int[] coreArgb,
            int coreStride) {
        int outputSide = TilePlan.TILE_SIZE * TilePlan.SCALE;
        int plane = outputSide * outputSide;
        int localStartX = tile.localCoreX() * TilePlan.SCALE;
        int localStartY = tile.localCoreY() * TilePlan.SCALE;
        int copyWidth = tile.outputWidth();
        int copyHeight = tile.outputHeight();
        for (int y = 0; y < copyHeight; y++) {
            int sourceRow = (localStartY + y) * outputSide + localStartX;
            int destinationRow = y * coreStride;
            for (int x = 0; x < copyWidth; x++) {
                int sourceIndex = sourceRow + x;
                int red = normalizedToByte(output[sourceIndex]);
                int green = normalizedToByte(output[plane + sourceIndex]);
                int blue = normalizedToByte(output[plane * 2 + sourceIndex]);
                int rgb = (red << 16) | (green << 8) | blue;
                coreArgb[destinationRow + x] = AlphaUpsampler.applyNearest2x(
                        rgb,
                        tileArgb,
                        TilePlan.TILE_SIZE,
                        tile.localCoreX(),
                        tile.localCoreY(),
                        x,
                        y);
            }
        }
        destination.setPixels(
                coreArgb,
                0,
                coreStride,
                tile.outputX(),
                tile.outputY(),
                copyWidth,
                copyHeight);
    }

    private static int normalizedToByte(float value) {
        float clamped = Math.max(0.0f, Math.min(1.0f, value));
        return Math.max(0, Math.min(255, Math.round(clamped * 255.0f)));
    }
}
