package dev.aisystems.quicksrplayerlab;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQUEST_SELECT_IMAGE = 4101;
    private static final int MAX_DECODE_DIMENSION = 2048;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final QuickSrEngine engine = new QuickSrEngine();

    private Spinner backendSpinner;
    private Button runButton;
    private Button selectImageButton;
    private TextView outputText;
    private ImageView referenceImage;
    private ImageView baselineImage;
    private ImageView qnnImage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContent());
        if (!prepareQnnProcessBeforeAnyOrtUse()) {
            return;
        }
        if (savedInstanceState == null && getIntent().getBooleanExtra("autorun", false)) {
            String requested = getIntent().getStringExtra("backend");
            if (requested != null) {
                try {
                    Backend backend = Backend.valueOf(requested);
                    backendSpinner.setSelection(backend.ordinal());
                    backendSpinner.post(this::runSelectedBackend);
                } catch (IllegalArgumentException invalidBackend) {
                    outputText.setText("拒绝未知 backend：" + requested);
                }
            }
        }
    }

    private View createContent() {
        int padding = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("QuickSR Mobile Player Lab · M0 真实图片 ROI");
        title.setTextSize(22);
        root.addView(title);

        TextView scope = new TextView(this);
        scope.setText(
                "当前可验证功能：从系统选图器读取图片，取中心 128×128 参考块，" +
                        "冻结下采样到 64×64，再比较普通双线性与 QuickSRNetSmall ×2。\n" +
                        "NPU 路径固定为 QNN HTP strict，禁止 CPU EP fallback。" +
                        "这仍是单块诊断，不代表全图、播放器、实时 FPS 或画质门禁已通过。"
        );
        scope.setPadding(0, padding / 2, 0, padding / 2);
        root.addView(scope);

        selectImageButton = new Button(this);
        selectImageButton.setText("选择真实图片并用 HTP 评测");
        selectImageButton.setOnClickListener(ignored -> openImagePicker());
        root.addView(selectImageButton);

        addImagePanel(root, "原始 HR 参考（中心 128×128）", 0);
        addImagePanel(root, "普通双线性基线（64→128）", 1);
        addImagePanel(root, "QuickSRNet · QNN HTP strict", 2);

        TextView probeTitle = new TextView(this);
        probeTitle.setText("底层探针（保留用于回归与失败诊断）");
        probeTitle.setTextSize(18);
        probeTitle.setPadding(0, padding, 0, padding / 4);
        root.addView(probeTitle);

        backendSpinner = new Spinner(this);
        backendSpinner.setAdapter(new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                Backend.values()
        ));
        root.addView(backendSpinner);

        runButton = new Button(this);
        runButton.setText("运行固定输入探针并保存回执");
        runButton.setOnClickListener(ignored -> runSelectedBackend());
        root.addView(runButton);

        outputText = new TextView(this);
        outputText.setText(
                "尚未运行。选图 URI、EXIF 和原图不会写入回执；仅在应用私有目录保存" +
                        "裁剪后的四张小图与哈希。当前质量分数是诊断值，human review 仍为 PENDING。"
        );
        outputText.setTextIsSelectable(true);
        outputText.setPadding(0, padding / 2, 0, padding);
        root.addView(outputText);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void addImagePanel(LinearLayout root, String label, int index) {
        TextView heading = new TextView(this);
        heading.setText(label);
        heading.setPadding(0, dp(12), 0, dp(4));
        root.addView(heading);

        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setMinimumHeight(dp(160));
        image.setContentDescription(label);
        root.addView(image, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(192)
        ));
        if (index == 0) {
            referenceImage = image;
        } else if (index == 1) {
            baselineImage = image;
        } else {
            qnnImage = image;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_SELECT_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SELECT_IMAGE || resultCode != RESULT_OK) {
            return;
        }
        Uri uri = data == null ? null : data.getData();
        if (uri == null) {
            outputText.setText("系统选图器没有返回可读图片。");
            return;
        }
        runSelectedImage(uri);
    }

    private void runSelectedImage(Uri uri) {
        setControlsEnabled(false);
        outputText.setText("正在本机解码中心 ROI，并运行 QNN HTP strict；请保持应用在前台……");

        executor.execute(() -> {
            Bitmap decoded = null;
            Bitmap reference = null;
            Bitmap lowResolution = null;
            Bitmap baseline = null;
            Bitmap qnn = null;
            boolean uiOwnershipTransferred = false;
            ProbeResult activeResult = null;
            String runId = ReceiptStore.newRunId();
            String failurePhase = "create-preflight-receipt";
            try {
                JSONObject preflightReceipt = new JSONObject();
                preflightReceipt.put("schemaVersion", "0.1.0");
                preflightReceipt.put("runId", runId);
                preflightReceipt.put("startedAt", Instant.now().toString());
                preflightReceipt.put("status", "RUNNING");
                preflightReceipt.put("backendRequested", Backend.QNN_HTP_DCR_STRICT.name());
                preflightReceipt.put("gateScope", "diagnostic-real-image-roi");
                preflightReceipt.put("runtimePlanSha256", BuildConfig.QNN_PLAN_SHA256);
                preflightReceipt.put("workloadPlanSha256", BuildConfig.P4_PLAN_SHA256);
                preflightReceipt.put("p4GateEligible", false);
                preflightReceipt.put("sourceUriCaptured", false);
                preflightReceipt.put("sourceExifCaptured", false);
                activeResult = ProbeResult.receiptOnly(preflightReceipt);

                failurePhase = "decode-selected-image";
                decoded = decodeSelectedBitmap(uri);
                int originalWidth = decoded.getWidth();
                int originalHeight = decoded.getHeight();
                CropResult crop = centerReferenceCrop(decoded);
                reference = crop.bitmap;

                failurePhase = "preprocess-and-baseline";
                int[] referenceArgb = bitmapPixels(reference);
                int[] lowResolutionArgb = ImageTensorCodec.downsample2xAverage(
                        referenceArgb,
                        ImageTensorCodec.OUTPUT_SIZE,
                        ImageTensorCodec.OUTPUT_SIZE);
                int[] baselineArgb = ImageTensorCodec.upscale2xBilinear(
                        lowResolutionArgb,
                        ImageTensorCodec.INPUT_SIZE,
                        ImageTensorCodec.INPUT_SIZE);
                float[] inputNchw = ImageTensorCodec.argbToNchw(
                        lowResolutionArgb,
                        ImageTensorCodec.INPUT_SIZE,
                        ImageTensorCodec.INPUT_SIZE);

                lowResolution = bitmapFromPixels(
                        lowResolutionArgb,
                        ImageTensorCodec.INPUT_SIZE,
                        ImageTensorCodec.INPUT_SIZE);
                baseline = bitmapFromPixels(
                        baselineArgb,
                        ImageTensorCodec.OUTPUT_SIZE,
                        ImageTensorCodec.OUTPUT_SIZE);

                JSONObject metadata = new JSONObject();
                metadata.put("sourceType", "system-selected-image");
                metadata.put("sourceUriCaptured", false);
                metadata.put("sourceExifCaptured", false);
                metadata.put("originalDecodedWidth", originalWidth);
                metadata.put("originalDecodedHeight", originalHeight);
                metadata.put("decodeMaxDimension", MAX_DECODE_DIMENSION);
                metadata.put("decodeTargetColorSpace", "sRGB");
                metadata.put("decodedBitmapConfig", String.valueOf(decoded.getConfig()));
                metadata.put("referenceCrop", "center-square-to-128x128");
                metadata.put("referenceCropSourceSide", crop.sourceSide);
                metadata.put("referenceCropResized", crop.resized);
                metadata.put("downsample", "rgb8-2x2-rounded-average-v1");
                metadata.put("alpha", "discarded-and-made-opaque");
                metadata.put("tensorLayout", "NCHW-RGB-float32-0-to-1");
                metadata.put(
                        "colorNormalization",
                        "decoder-output-used-as-RGB8; explicit-profile-normalization-pending");

                failurePhase = "qnn-htp-runtime";
                ProbeResult result = engine.run(
                        getApplicationContext(),
                        Backend.QNN_HTP_DCR_STRICT,
                        runId,
                        inputNchw,
                        "selected-image-center-roi-average-downsample-v1",
                        metadata);
                activeResult = result;
                JSONObject receipt = result.receipt();
                receipt.put("runtimePlanSha256", receipt.optString("planSha256"));
                receipt.put("workloadPlanSha256", BuildConfig.P4_PLAN_SHA256);
                receipt.put("p4GateEligible", false);
                if (!"PASS".equals(receipt.optString("status"))) {
                    failurePhase = "persist-runtime-failure-receipt";
                    File receiptFile = ReceiptStore.write(getApplicationContext(), result);
                    postTextIfAlive(
                            "HTP 严格运行失败，失败回执已保存：" + receiptFile.getAbsolutePath() +
                                    "\n\n" + receipt.toString(2));
                    return;
                }

                failurePhase = "materialize-and-score-output";
                float[] outputNchw = result.outputFloat32Values();
                if (outputNchw == null) {
                    throw new IllegalStateException("HTP run passed without a materialized output tensor");
                }
                int[] qnnArgb = ImageTensorCodec.nchwToArgb(
                        outputNchw,
                        ImageTensorCodec.OUTPUT_SIZE,
                        ImageTensorCodec.OUTPUT_SIZE);
                qnn = bitmapFromPixels(
                        qnnArgb,
                        ImageTensorCodec.OUTPUT_SIZE,
                        ImageTensorCodec.OUTPUT_SIZE);

                double baselinePsnr = ImageTensorCodec.psnrRgb8(referenceArgb, baselineArgb);
                double qnnPsnr = ImageTensorCodec.psnrRgb8(referenceArgb, qnnArgb);
                JSONObject evaluation = new JSONObject();
                evaluation.put("schemaVersion", "0.1.0");
                evaluation.put("p4PlanSha256", BuildConfig.P4_PLAN_SHA256);
                evaluation.put("p4PlanStatus", "FROZEN_THRESHOLDS_INPUT_HASHES_PENDING");
                evaluation.put("p4GateEligible", false);
                evaluation.put("scope", "diagnostic-real-image-roi-not-frozen-p4-gate");
                evaluation.put("runtimeStatusBeforeImageEvaluation", "PASS");
                evaluation.put("baseline", "deterministic-half-pixel-bilinear-rgb8-v1");
                putFiniteOrString(evaluation, "baselinePsnrRgb8Db", baselinePsnr);
                putFiniteOrString(evaluation, "qnnPsnrRgb8Db", qnnPsnr);
                if (Double.isFinite(baselinePsnr) && Double.isFinite(qnnPsnr)) {
                    evaluation.put("qnnMinusBaselinePsnrDb", qnnPsnr - baselinePsnr);
                } else {
                    evaluation.put("qnnMinusBaselinePsnrDb", JSONObject.NULL);
                }
                evaluation.put("correctnessPromotionAllowed", false);
                evaluation.put("humanReview", "PENDING");
                evaluation.put(
                        "interpretation",
                        "diagnostic-only; frozen P4 thresholds and PC reference are still required");
                evaluation.put(
                        "imageArtifacts",
                        ImageEvidenceStore.write(
                                getApplicationContext(),
                                runId,
                                reference,
                                lowResolution,
                                baseline,
                                qnn));
                receipt.put("imageEvaluation", evaluation);

                failurePhase = "persist-success-receipt";
                File receiptFile = ReceiptStore.write(getApplicationContext(), result);
                String summary = String.format(
                        Locale.US,
                        "M0 真实图片 ROI 诊断已返回（不等于 P4 正确性通过）。\n" +
                                "普通双线性 PSNR: %s dB\nQNN HTP PSNR: %s dB\n" +
                                "human review: PENDING\n回执：%s\n\n%s",
                        metricText(baselinePsnr),
                        metricText(qnnPsnr),
                        receiptFile.getAbsolutePath(),
                        receipt.toString(2));
                postImageResultIfAlive(reference, baseline, qnn, summary);
                uiOwnershipTransferred = true;
            } catch (Throwable failure) {
                String failureText =
                        "真实图片 ROI 流程失败；未把它计为有效结果。\n" +
                                failure.getClass().getName() + ": " + failure.getMessage();
                if (activeResult != null) {
                    JSONObject failedReceipt = activeResult.receipt();
                    try {
                        String runtimeStatus = failedReceipt.optString("status", "UNKNOWN");
                        JSONObject pipelineError = new JSONObject();
                        pipelineError.put("type", failure.getClass().getName());
                        pipelineError.put("message", String.valueOf(failure.getMessage()));
                        failedReceipt.put("runtimeStatusBeforeImagePipelineFailure", runtimeStatus);
                        failedReceipt.put("imagePipelineStatus", "FAIL");
                        failedReceipt.put("failurePhase", failurePhase);
                        failedReceipt.put("imagePipelineError", pipelineError);
                        failedReceipt.put("status", "FAIL");
                        failedReceipt.put("finishedAt", Instant.now().toString());
                        File failureReceipt = ReceiptStore.write(
                                getApplicationContext(), activeResult);
                        failureText += "\n失败回执：" + failureReceipt.getAbsolutePath();
                    } catch (Throwable storageFailure) {
                        failureText += "\n失败回执也未能持久化：" +
                                storageFailure.getClass().getName() + ": " +
                                storageFailure.getMessage();
                    }
                }
                postTextIfAlive(failureText);
            } finally {
                recycle(decoded);
                recycle(lowResolution);
                if (!uiOwnershipTransferred) {
                    recycle(reference);
                    recycle(baseline);
                    recycle(qnn);
                }
            }
        });
    }

    private Bitmap decodeSelectedBitmap(Uri uri) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
            Bitmap decoded = ImageDecoder.decodeBitmap(source, (decoder, info, ignored) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
                int width = info.getSize().getWidth();
                int height = info.getSize().getHeight();
                int largest = Math.max(width, height);
                if (largest > MAX_DECODE_DIMENSION) {
                    decoder.setTargetSampleSize(
                            Math.max(1, (int) Math.ceil(largest / (double) MAX_DECODE_DIMENSION)));
                }
            });
            return requireArgb8888(decoded);
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream stream = requireImageStream(uri)) {
            BitmapFactory.decodeStream(stream, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("Selected image dimensions could not be decoded");
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (options.inSampleSize * 2)
                >= MAX_DECODE_DIMENSION) {
            options.inSampleSize *= 2;
        }
        try (InputStream stream = requireImageStream(uri)) {
            Bitmap value = BitmapFactory.decodeStream(stream, null, options);
            if (value == null) {
                throw new IOException("Selected image payload could not be decoded");
            }
            return requireArgb8888(value);
        }
    }

    private static Bitmap requireArgb8888(Bitmap value) throws IOException {
        if (value.getConfig() == Bitmap.Config.ARGB_8888) {
            return value;
        }
        Bitmap converted = value.copy(Bitmap.Config.ARGB_8888, false);
        if (converted == null) {
            throw new IOException("Selected image could not be converted to ARGB_8888");
        }
        value.recycle();
        return converted;
    }

    private InputStream requireImageStream(Uri uri) throws IOException {
        InputStream stream = getContentResolver().openInputStream(uri);
        if (stream == null) {
            throw new IOException("Selected image stream is unavailable");
        }
        return stream;
    }

    private static CropResult centerReferenceCrop(Bitmap source) {
        if (source.getWidth() < ImageTensorCodec.OUTPUT_SIZE
                || source.getHeight() < ImageTensorCodec.OUTPUT_SIZE) {
            throw new IllegalArgumentException(
                    "Selected image must be at least 128x128 after bounded decoding");
        }
        int sourceSide = ImageTensorCodec.OUTPUT_SIZE;
        int left = (source.getWidth() - sourceSide) / 2;
        int top = (source.getHeight() - sourceSide) / 2;
        Bitmap crop = Bitmap.createBitmap(source, left, top, sourceSide, sourceSide);
        if (crop == source) {
            crop = source.copy(Bitmap.Config.ARGB_8888, false);
        }
        return new CropResult(crop, sourceSide, false);
    }

    private static int[] bitmapPixels(Bitmap bitmap) {
        int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(
                pixels,
                0,
                bitmap.getWidth(),
                0,
                0,
                bitmap.getWidth(),
                bitmap.getHeight());
        return pixels;
    }

    private static Bitmap bitmapFromPixels(int[] pixels, int width, int height) {
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    private static void putFiniteOrString(JSONObject target, String key, double value)
            throws Exception {
        target.put(key, Double.isFinite(value) ? value : metricText(value));
    }

    private static String metricText(double value) {
        if (Double.isInfinite(value)) {
            return value > 0.0 ? "Infinity" : "-Infinity";
        }
        if (Double.isNaN(value)) {
            return "NaN";
        }
        return String.format(Locale.US, "%.4f", value);
    }

    private void runSelectedBackend() {
        Backend backend = (Backend) backendSpinner.getSelectedItem();
        setControlsEnabled(false);
        outputText.setText("正在运行 " + backend.name() + "；请保持应用在前台……");

        executor.execute(() -> {
            String runId = ReceiptStore.newRunId();
            ProbeResult result = engine.run(getApplicationContext(), backend, runId);
            JSONObject receipt = result.receipt();
            try {
                File file = ReceiptStore.write(getApplicationContext(), result);
                String text = "已保存：" + file.getAbsolutePath() + "\n\n" + receipt.toString(2);
                postTextIfAlive(text);
            } catch (Throwable storageFailure) {
                String text = "推理已返回，但回执写入失败；该次运行不能算有效证据。\n" +
                        storageFailure.getClass().getName() + ": " + storageFailure.getMessage() +
                        "\n\n未持久化内容：\n" + receipt;
                postTextIfAlive(text);
            }
        });
    }

    private boolean prepareQnnProcessBeforeAnyOrtUse() {
        try {
            QnnPluginRuntime.prepareProcessEnvironment(
                    getApplicationContext(), new JSONObject());
            return true;
        } catch (Throwable failure) {
            setControlsEnabled(false);
            outputText.setText(
                    "QNN 进程环境无法在 ORT 初始化前准备；已禁用运行，避免先初始化 CPU " +
                            "路径后产生不可归因的 HTP 结果。\n" +
                            failure.getClass().getName() + ": " + failure.getMessage());
            return false;
        }
    }

    private void postImageResultIfAlive(
            Bitmap reference,
            Bitmap baseline,
            Bitmap qnn,
            String text) {
        if (isFinishing() || isDestroyed()) {
            recycle(reference);
            recycle(baseline);
            recycle(qnn);
            return;
        }
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                recycle(reference);
                recycle(baseline);
                recycle(qnn);
                return;
            }
            referenceImage.setImageBitmap(reference);
            baselineImage.setImageBitmap(baseline);
            qnnImage.setImageBitmap(qnn);
            finishRun(text);
        });
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    private void postTextIfAlive(String text) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) {
                finishRun(text);
            }
        });
    }

    private void finishRun(String text) {
        outputText.setText(text);
        setControlsEnabled(true);
    }

    private void setControlsEnabled(boolean enabled) {
        runButton.setEnabled(enabled);
        selectImageButton.setEnabled(enabled);
        backendSpinner.setEnabled(enabled);
    }

    @Override
    protected void onDestroy() {
        // Preserve an in-flight inference and its receipt write. A destroyed Activity no longer
        // receives UI updates, but the single worker may finish safely in app-private storage.
        executor.shutdown();
        super.onDestroy();
    }

    private static final class CropResult {
        final Bitmap bitmap;
        final int sourceSide;
        final boolean resized;

        CropResult(Bitmap bitmap, int sourceSide, boolean resized) {
            this.bitmap = bitmap;
            this.sourceSide = sourceSide;
            this.resized = resized;
        }
    }
}
