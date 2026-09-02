package dev.aisystems.quicksrplayerlab;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.LanczosResample;
import androidx.media3.effect.Presentation;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@UnstableApi
public final class SuperResolutionActivity extends Activity {
    private static final int REQUEST_IMAGE = 5101;
    private static final int REQUEST_SAVE_IMAGE = 5102;
    private static final int REQUEST_VIDEO = 5103;
    static final long PLAYER_RELEASE_TIMEOUT_MS = 5_000L;
    private static final String PLAYER_PREFERENCES = "quicksr-player";
    private static final String LAST_VIDEO_URI = "last-video-uri";
    private static final String LAST_VIDEO_NAME = "last-video-name";
    private static final int[] IMAGE_LIMITS = new int[]{960, 1440, 1920};
    private static final int[][] VIDEO_TARGETS = new int[][]{
            {1280, 720},
            {1920, 1080},
            {2560, 1440}
    };

    private enum VideoMode {
        QUICKSR_QNN,
        QUICKSR_CPU,
        GPU_ANIME4K,
        GPU_LANCZOS,
        ORIGINAL;

        VideoMode next() {
            VideoMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    private final ExecutorService imageExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor();
    private final Object bitmapOwnershipLock = new Object();
    private final Object benchmarkStatsLock = new Object();
    private final ArrayList<QuickSrVideoEffect.FrameStats> benchmarkStatsBatch =
            new ArrayList<>();

    private Spinner imageBackendSpinner;
    private Spinner imageSizeSpinner;
    private Button chooseImageButton;
    private Button cancelImageButton;
    private Button saveImageButton;
    private TextView imageStatus;
    private ImageView sourcePreview;
    private ImageView outputPreview;
    private Spinner videoNeuralProfileSpinner;
    private Spinner videoQnnTuningSpinner;
    private Spinner videoTargetSpinner;
    private Button lastVideoButton;
    private Button videoEffectButton;
    private TextView videoStatus;
    private PlayerView playerView;
    private ExoPlayer player;
    private String appliedVideoEffectKey;
    private boolean anime4kRecoveryInProgress;
    private Future<?> activeImageTask;
    private Future<?> activeSaveTask;
    private Bitmap latestSource;
    private Bitmap latestOutput;
    private Bitmap bitmapBeingSaved;
    private volatile long imageTaskGeneration;
    private volatile boolean activityDestroyed;
    private volatile boolean benchmarkIntentActive;
    private boolean benchmarkPlaybackRepeats;
    private boolean qnnEnvironmentReady;
    private String benchmarkRunId;
    private QuickSrSession.Mode benchmarkMode;
    private QuickSrSession.Tuning benchmarkTuning;
    private QuickSrVideoEffect.Profile benchmarkProfile;
    private VideoEvidenceStore.CaptureSpec benchmarkCaptureSpec =
            VideoEvidenceStore.CaptureSpec.none();
    private JSONObject benchmarkQnnStrictEvidence;
    private VideoMode videoMode = BuildConfig.QNN_RUNTIME_EXPECTED
            ? VideoMode.QUICKSR_QNN
            : VideoMode.QUICKSR_CPU;
    private Uri lastVideoUri;
    private String lastVideoName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        restoreLastVideo();
        setContentView(createContent());
        if (BuildConfig.QNN_RUNTIME_EXPECTED) {
            prepareQnnEnvironment();
        } else {
            imageBackendSpinner.setSelection(QuickSrSession.Mode.CPU.ordinal());
            imageStatus.setText("x86_64 模拟器构建：使用 CPU 验证 UI/Media3；不提供 QNN HTP。");
        }
        // Releasing an active 1080p QNN frame and closing the HTP session takes longer than
        // Media3's short default release window on the physical target. Keep the bound finite,
        // but allow the shader's orderly worker cleanup to complete without a false player error.
        player = new ExoPlayer.Builder(this, new DefaultWorkingColorRenderersFactory(this))
                .setReleaseTimeoutMs(PLAYER_RELEASE_TIMEOUT_MS)
                .build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                updateVideoStatus(videoSize.width + "×" + videoSize.height + " 正在播放");
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (recoverFromAnime4kPipelineFailure(
                        "Media3 video pipeline failed: " + safeMessage(error))) {
                    return;
                }
                logBenchmarkError("player", safeMessage(error));
                logBenchmarkTerminal("FAIL", "player");
                updateVideoStatus("视频播放失败：" + safeMessage(error));
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_BUFFERING) {
                    updateVideoStatus("正在读取视频……");
                } else if (playbackState == Player.STATE_ENDED) {
                    flushBenchmarkStats();
                    logBenchmarkTerminal("PLAYBACK_ENDED", "player");
                }
            }
        });
        boolean benchmarkReady = applyBenchmarkIntentOverrides(getIntent());
        applyVideoEffects();
        if (benchmarkReady) {
            handleVideoIntent(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        flushBenchmarkStats();
        boolean benchmarkReady = applyBenchmarkIntentOverrides(intent);
        applyVideoEffects();
        if (benchmarkReady) {
            handleVideoIntent(intent);
        }
    }

    private View createContent() {
        int padding = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);

        TextView title = text("QuickSR 图片 + 视频超分", 24);
        root.addView(title);
        TextView intro = text(
                "图片：QuickSRNet 2×，整图分块并复用一次 ORT/QNN session。\n" +
                        "视频：可切换逐帧 QuickSR QNN/CPU、GPU-resident Anime4K、" +
                        "GPU Lanczos 或原始画面。",
                15);
        intro.setPadding(0, dp(6), 0, dp(18));
        root.addView(intro);

        root.addView(text("图片 2× 超分", 20));
        imageBackendSpinner = new Spinner(this);
        imageBackendSpinner.setAdapter(new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                QuickSrSession.Mode.values()));
        root.addView(imageBackendSpinner);

        imageSizeSpinner = new Spinner(this);
        imageSizeSpinner.setAdapter(new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"输入长边上限 960", "输入长边上限 1440", "输入长边上限 1920"}));
        imageSizeSpinner.setSelection(1);
        root.addView(imageSizeSpinner);

        chooseImageButton = new Button(this);
        chooseImageButton.setText("选择图片并执行整图 2×");
        chooseImageButton.setOnClickListener(ignored -> chooseImage());
        root.addView(chooseImageButton);

        LinearLayout imageActions = new LinearLayout(this);
        imageActions.setOrientation(LinearLayout.HORIZONTAL);
        cancelImageButton = new Button(this);
        cancelImageButton.setText("取消");
        cancelImageButton.setEnabled(false);
        cancelImageButton.setOnClickListener(ignored -> cancelImageTask());
        saveImageButton = new Button(this);
        saveImageButton.setText("保存 2× PNG");
        saveImageButton.setEnabled(false);
        saveImageButton.setOnClickListener(ignored -> chooseSaveLocation());
        imageActions.addView(cancelImageButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        imageActions.addView(saveImageButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(imageActions);

        imageStatus = text("请选择一张本地图片。", 14);
        imageStatus.setTextIsSelectable(true);
        imageStatus.setPadding(0, dp(8), 0, dp(8));
        root.addView(imageStatus);
        root.addView(text("输入预览", 15));
        sourcePreview = preview();
        root.addView(sourcePreview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(220)));
        root.addView(text("QuickSR 2× 输出", 15));
        outputPreview = preview();
        root.addView(outputPreview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(260)));

        TextView videoHeading = text("视频实时增强播放器", 20);
        videoHeading.setPadding(0, dp(24), 0, dp(6));
        root.addView(videoHeading);
        TextView videoBoundary = text(
                "QuickSR 视频模式会真实执行逐帧神经推理。默认 720p 档保持 16:9，" +
                        "把整帧缩到 640×360，再由 2× 模型输出 1280×720；" +
                        "实验性 3×/4× 档分别生成真实 1920×1080 与 2560×1440 神经纹理。" +
                        "4K 显示档先生成 1080p 神经纹理，再由 GPU 放大到 3840×2160，" +
                        "不等同原生 4K 神经推理。Anime4K 模式先在 GPU 上缩放到目标尺寸的一半，" +
                        "再执行官方 Small 的四层卷积与 2× depth-to-space；不经过 CPU readback。" +
                        "所有档位都是整帧缩放，不是分块覆盖原始高分辨率帧。",
                14);
        root.addView(videoBoundary);

        videoNeuralProfileSpinner = new Spinner(this);
        videoNeuralProfileSpinner.setAdapter(new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                QuickSrVideoEffect.Profile.values()));
        videoNeuralProfileSpinner.setSelection(
                QuickSrVideoEffect.Profile.FULL_720P.ordinal());
        videoNeuralProfileSpinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            AdapterView<?> parent,
                            View view,
                            int position,
                            long id) {
                        if (player != null && isNeuralVideoMode()) {
                            applyVideoEffects();
                        }
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                    }
                });
        root.addView(videoNeuralProfileSpinner);

        videoQnnTuningSpinner = new Spinner(this);
        videoQnnTuningSpinner.setAdapter(new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                QuickSrSession.Tuning.values()));
        videoQnnTuningSpinner.setSelection(QuickSrSession.Tuning.SUSTAINED.ordinal());
        videoQnnTuningSpinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            AdapterView<?> parent,
                            View view,
                            int position,
                            long id) {
                        if (player != null && videoMode == VideoMode.QUICKSR_QNN) {
                            applyVideoEffects();
                        }
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                    }
                });
        root.addView(videoQnnTuningSpinner);

        videoTargetSpinner = new Spinner(this);
        videoTargetSpinner.setAdapter(new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"目标 720p", "目标 1080p", "目标 1440p"}));
        videoTargetSpinner.setSelection(1);
        videoTargetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (player != null) {
                    applyVideoEffects();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        root.addView(videoTargetSpinner);

        Button chooseVideoButton = new Button(this);
        chooseVideoButton.setText("选择本地视频并播放");
        chooseVideoButton.setOnClickListener(ignored -> chooseVideo());
        root.addView(chooseVideoButton);
        lastVideoButton = new Button(this);
        lastVideoButton.setOnClickListener(ignored -> playLastVideo());
        root.addView(lastVideoButton);
        updateLastVideoButton();
        videoEffectButton = new Button(this);
        videoEffectButton.setOnClickListener(ignored -> toggleVideoEffect());
        root.addView(videoEffectButton);
        updateVideoEffectButton();

        playerView = new PlayerView(this);
        playerView.setUseController(true);
        root.addView(playerView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(280)));
        videoStatus = text(
                lastVideoUri == null
                        ? "尚未选择视频。"
                        : "已记住上次视频；点击上方按钮即可再次播放。",
                14);
        videoStatus.setPadding(0, dp(8), 0, dp(24));
        root.addView(videoStatus);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private TextView text(String value, int sizeSp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        return view;
    }

    private ImageView preview() {
        ImageView view = new ImageView(this);
        view.setAdjustViewBounds(true);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setBackgroundColor(0xff101010);
        view.setContentDescription("超分图片预览");
        return view;
    }

    private void prepareQnnEnvironment() {
        try {
            JSONObject evidence = new JSONObject();
            QnnPluginRuntime.prepareProcessEnvironment(getApplicationContext(), evidence);
            qnnEnvironmentReady = true;
        } catch (Throwable failure) {
            qnnEnvironmentReady = false;
            imageStatus.setText(
                    "QNN 环境初始化失败；仍可切换 CPU：" + safeMessage(failure));
            imageBackendSpinner.setSelection(QuickSrSession.Mode.CPU.ordinal());
            videoMode = VideoMode.QUICKSR_CPU;
            updateVideoEffectButton();
        }
    }

    private void chooseImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_IMAGE);
    }

    private void chooseVideo() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION |
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_VIDEO);
    }

    private void chooseSaveLocation() {
        synchronized (bitmapOwnershipLock) {
            if (latestOutput == null ||
                    latestOutput.isRecycled() ||
                    bitmapBeingSaved != null) {
                return;
            }
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/png");
        intent.putExtra(
                Intent.EXTRA_TITLE,
                "quicksr-2x-" + Instant.now().toString().replace(':', '-') + ".png");
        startActivityForResult(intent, REQUEST_SAVE_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQUEST_IMAGE) {
            runFullImage(uri);
        } else if (requestCode == REQUEST_VIDEO) {
            clearBenchmarkTelemetry();
            rememberLastVideo(uri, data);
            playVideo(uri);
        } else if (requestCode == REQUEST_SAVE_IMAGE) {
            saveOutput(uri);
        }
    }

    private void runFullImage(Uri uri) {
        cancelActiveImageWork(false);
        long taskGeneration = ++imageTaskGeneration;
        QuickSrSession.Mode mode =
                (QuickSrSession.Mode) imageBackendSpinner.getSelectedItem();
        int maxDimension = IMAGE_LIMITS[imageSizeSpinner.getSelectedItemPosition()];
        setImageRunning(true);
        imageStatus.setText("正在解码图片……");

        activeImageTask = imageExecutor.submit(() -> {
            Bitmap decoded = null;
            try {
                decoded = decodeBitmap(uri, maxDimension);
                final Bitmap input = decoded;
                postImageStatus(
                        taskGeneration,
                        "输入 " + input.getWidth() + "×" + input.getHeight() +
                                "；正在创建 " + (input.getWidth() * 2) + "×" +
                                (input.getHeight() * 2) + " 输出……");
                FullImageUpscaler.Result result = FullImageUpscaler.upscale(
                        getApplicationContext(),
                        input,
                        mode,
                        (completed, total) -> {
                            int step = Math.max(1, total / 100);
                            if (completed == total || completed % step == 0) {
                                postImageStatus(
                                        taskGeneration,
                                        String.format(
                                                Locale.US,
                                                "%s：%d/%d tiles（%.0f%%）",
                                                mode,
                                                completed,
                                                total,
                                                completed * 100.0 / total));
                            }
                        });
                decoded = null;
                postImageResult(taskGeneration, input, result);
            } catch (Throwable failure) {
                recycleBitmap(decoded);
                postImageFailure(taskGeneration, failure);
            }
        });
    }

    private void postImageResult(
            long taskGeneration,
            Bitmap input,
            FullImageUpscaler.Result result) {
        if (activityDestroyed) {
            recycleBitmap(input);
            recycleBitmap(result.bitmap);
            return;
        }
        runOnUiThread(() -> {
            if (!canPublishImageTask(taskGeneration)) {
                recycleBitmap(input);
                recycleBitmap(result.bitmap);
                return;
            }
            showImageResult(input, result);
            finishImageTask(taskGeneration);
        });
    }

    private void postImageFailure(long taskGeneration, Throwable failure) {
        if (activityDestroyed) {
            return;
        }
        runOnUiThread(() -> {
            if (!canPublishImageTask(taskGeneration)) {
                return;
            }
            imageStatus.setText("图片超分失败：" + safeMessage(failure));
            setImageRunning(false);
            finishImageTask(taskGeneration);
        });
    }

    private void postImageStatus(long taskGeneration, String value) {
        if (activityDestroyed) {
            return;
        }
        runOnUiThread(() -> {
            if (canPublishImageTask(taskGeneration)) {
                imageStatus.setText(value);
            }
        });
    }

    private boolean canPublishImageTask(long taskGeneration) {
        return !activityDestroyed &&
                !isFinishing() &&
                !isDestroyed() &&
                imageTaskGeneration == taskGeneration;
    }

    private void finishImageTask(long taskGeneration) {
        if (imageTaskGeneration == taskGeneration) {
            activeImageTask = null;
        }
    }

    private void showImageResult(Bitmap input, FullImageUpscaler.Result result) {
        replaceLatestImages(input, result.bitmap);
        sourcePreview.setImageBitmap(latestSource);
        outputPreview.setImageBitmap(latestOutput);
        imageStatus.setText(String.format(
                Locale.US,
                "完成：%dx%d → %dx%d\n后端：%s\ntiles/runs：%d/%d\n耗时：%.2f 秒",
                latestSource.getWidth(),
                latestSource.getHeight(),
                latestOutput.getWidth(),
                latestOutput.getHeight(),
                result.backend,
                result.tileCount,
                result.runCount,
                result.elapsedMs / 1000.0));
        setImageRunning(false);
        saveImageButton.setEnabled(true);
    }

    private void cancelImageTask() {
        cancelActiveImageWork(true);
    }

    private void cancelActiveImageWork(boolean userVisible) {
        ++imageTaskGeneration;
        Future<?> imageTask = activeImageTask;
        activeImageTask = null;
        if (imageTask != null && !imageTask.isDone()) {
            imageTask.cancel(true);
        }
        if (userVisible && !activityDestroyed) {
            imageStatus.setText("取消请求已发送；正在运行的本地操作会安全收尾。");
            setImageRunning(false);
            synchronized (bitmapOwnershipLock) {
                saveImageButton.setEnabled(
                        latestOutput != null &&
                                !latestOutput.isRecycled() &&
                                bitmapBeingSaved == null);
            }
        }
    }

    private void saveOutput(Uri uri) {
        final Bitmap output;
        synchronized (bitmapOwnershipLock) {
            output = latestOutput;
            if (output == null || output.isRecycled() || bitmapBeingSaved != null) {
                return;
            }
            bitmapBeingSaved = output;
        }
        cancelActiveImageWork(false);
        long taskGeneration = ++imageTaskGeneration;
        setImageRunning(true);
        imageStatus.setText("正在保存 PNG……");
        try {
            activeSaveTask = saveExecutor.submit(() -> {
                Throwable failure = null;
                try (OutputStream stream = getContentResolver().openOutputStream(uri, "w")) {
                    if (stream == null ||
                            !output.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        throw new IOException("PNG encoder did not produce output");
                    }
                    stream.flush();
                } catch (Throwable caught) {
                    failure = caught;
                } finally {
                    releaseSavedBitmap(output);
                }
                postSaveResult(taskGeneration, uri, failure);
            });
        } catch (Throwable failure) {
            releaseSavedBitmap(output);
            imageStatus.setText("保存失败：" + safeMessage(failure));
            setImageRunning(false);
            saveImageButton.setEnabled(true);
        }
    }

    private void postSaveResult(long taskGeneration, Uri uri, Throwable failure) {
        if (activityDestroyed) {
            return;
        }
        runOnUiThread(() -> {
            if (!canPublishImageTask(taskGeneration)) {
                return;
            }
            imageStatus.setText(failure == null
                    ? "已保存 2× PNG：" + uri
                    : "保存失败：" + safeMessage(failure));
            setImageRunning(false);
            synchronized (bitmapOwnershipLock) {
                saveImageButton.setEnabled(
                        latestOutput != null && !latestOutput.isRecycled());
            }
            activeSaveTask = null;
        });
    }

    private void releaseSavedBitmap(Bitmap output) {
        boolean recycle;
        synchronized (bitmapOwnershipLock) {
            if (bitmapBeingSaved == output) {
                bitmapBeingSaved = null;
            }
            recycle = activityDestroyed || latestOutput != output;
        }
        if (recycle) {
            recycleBitmap(output);
        }
    }

    private void replaceLatestImages(Bitmap source, Bitmap output) {
        Bitmap oldSource;
        Bitmap oldOutput;
        boolean recycleOldOutput;
        synchronized (bitmapOwnershipLock) {
            oldSource = latestSource;
            oldOutput = latestOutput;
            latestSource = source;
            latestOutput = output;
            recycleOldOutput = oldOutput != null && oldOutput != bitmapBeingSaved;
        }
        recycleBitmap(oldSource);
        if (recycleOldOutput) {
            recycleBitmap(oldOutput);
        }
    }

    private static void recycleBitmap(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    private void playVideo(Uri uri) {
        try {
            applyVideoEffects();
            applyBenchmarkRepeatMode();
            player.setMediaItem(MediaItem.fromUri(uri));
            player.prepare();
            player.play();
            updateVideoStatus("视频已载入，正在启动解码与 " + videoModeLabel() + "……");
        } catch (Throwable failure) {
            updateVideoStatus("视频启动失败：" + safeMessage(failure));
        }
    }

    private void playLastVideo() {
        Uri uri = lastVideoUri;
        if (uri == null) {
            updateVideoStatus("还没有保存过视频，请先选择一次本地视频。");
            return;
        }
        clearBenchmarkTelemetry();
        playVideo(uri);
    }

    private void restoreLastVideo() {
        SharedPreferences preferences = getSharedPreferences(
                PLAYER_PREFERENCES,
                MODE_PRIVATE);
        String storedUri = preferences.getString(LAST_VIDEO_URI, null);
        if (storedUri == null || storedUri.isBlank()) {
            return;
        }
        lastVideoUri = Uri.parse(storedUri);
        lastVideoName = preferences.getString(LAST_VIDEO_NAME, null);
    }

    private void rememberLastVideo(Uri uri, Intent grantSource) {
        int sourceFlags = grantSource == null ? 0 : grantSource.getFlags();
        int readFlag = sourceFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (readFlag != 0 &&
                (sourceFlags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
            try {
                getContentResolver().takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // Some VIEW providers expose a stable URI without offering a persistable grant.
                // The stored URI remains useful for the current task and for providers that allow
                // later reads; ACTION_OPEN_DOCUMENT selections take the durable path above.
            }
        }
        String displayName = queryDisplayName(uri);
        getSharedPreferences(PLAYER_PREFERENCES, MODE_PRIVATE)
                .edit()
                .putString(LAST_VIDEO_URI, uri.toString())
                .putString(LAST_VIDEO_NAME, displayName)
                .apply();
        lastVideoUri = uri;
        lastVideoName = displayName;
        updateLastVideoButton();
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) {
                    String value = cursor.getString(column);
                    if (value != null && !value.isBlank()) {
                        return value;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // The URI can still be replayed even when its provider does not expose a display name.
        }
        return null;
    }

    private void updateLastVideoButton() {
        if (lastVideoButton == null) {
            return;
        }
        boolean available = lastVideoUri != null;
        lastVideoButton.setEnabled(available);
        lastVideoButton.setText(
                available
                        ? "播放上次视频" +
                                (lastVideoName == null ? "" : "：" + lastVideoName)
                        : "播放上次视频（尚未保存）");
    }

    private void handleVideoIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) {
            return;
        }
        Uri uri = intent.getData();
        String type = intent.getType();
        if (uri != null && (type == null || type.startsWith("video/"))) {
            rememberLastVideo(uri, intent);
            playVideo(uri);
        }
    }

    private boolean applyBenchmarkIntentOverrides(Intent intent) {
        boolean requested = intent != null && (
                intent.hasExtra(VideoBenchmarkTelemetry.EXTRA_RUN_ID)
                        || intent.hasExtra(VideoBenchmarkTelemetry.EXTRA_VIDEO_MODE)
                        || intent.hasExtra(VideoBenchmarkTelemetry.EXTRA_VIDEO_PROFILE)
                        || intent.hasExtra(VideoBenchmarkTelemetry.EXTRA_VIDEO_TUNING)
                        || intent.hasExtra(VideoBenchmarkTelemetry.EXTRA_CAPTURE_FRAME)
                        || intent.hasExtra(VideoBenchmarkTelemetry.EXTRA_CAPTURE_PTS_US));
        if (!requested) {
            clearBenchmarkTelemetry();
            return true;
        }
        setBenchmarkPlaybackRepeats(false);
        String runId = intent.getStringExtra(VideoBenchmarkTelemetry.EXTRA_RUN_ID);
        String requestedMode = intent.getStringExtra(VideoBenchmarkTelemetry.EXTRA_VIDEO_MODE);
        String requestedProfile = intent.getStringExtra(
                VideoBenchmarkTelemetry.EXTRA_VIDEO_PROFILE);
        String requestedTuning = intent.getStringExtra(
                VideoBenchmarkTelemetry.EXTRA_VIDEO_TUNING);
        boolean captureFrameRequested = intent.hasExtra(
                VideoBenchmarkTelemetry.EXTRA_CAPTURE_FRAME);
        boolean capturePtsRequested = intent.hasExtra(
                VideoBenchmarkTelemetry.EXTRA_CAPTURE_PTS_US);
        benchmarkIntentActive = true;
        benchmarkRunId = validBenchmarkRunId(runId) ? runId : "invalid-run-id";
        benchmarkCaptureSpec = VideoEvidenceStore.CaptureSpec.none();
        benchmarkQnnStrictEvidence = null;
        if (!validBenchmarkRunId(runId)
                || requestedMode == null
                || requestedProfile == null
                || requestedTuning == null) {
            return benchmarkConfigurationFailure(
                    "Benchmark extras require a safe run id, mode, profile and tuning");
        }
        try {
            VideoMode selectedMode = VideoMode.valueOf(requestedMode);
            QuickSrVideoEffect.Profile selectedProfile =
                    QuickSrVideoEffect.Profile.valueOf(requestedProfile);
            QuickSrSession.Tuning selectedTuning =
                    QuickSrSession.Tuning.valueOf(requestedTuning);
            if (captureFrameRequested && capturePtsRequested) {
                return benchmarkConfigurationFailure(
                        "Specify at most one video evidence selector: frame or ptsUs");
            }
            VideoEvidenceStore.CaptureSpec captureSpec = VideoEvidenceStore.CaptureSpec.none();
            if (captureFrameRequested) {
                captureSpec = VideoEvidenceStore.CaptureSpec.forFrame(intent.getIntExtra(
                        VideoBenchmarkTelemetry.EXTRA_CAPTURE_FRAME,
                        -1));
            } else if (capturePtsRequested) {
                captureSpec = VideoEvidenceStore.CaptureSpec.forPresentationTimeUs(
                        intent.getLongExtra(
                                VideoBenchmarkTelemetry.EXTRA_CAPTURE_PTS_US,
                                -1L));
            }
            if (selectedMode != VideoMode.QUICKSR_QNN
                    && selectedMode != VideoMode.QUICKSR_CPU) {
                return benchmarkConfigurationFailure(
                        "Benchmark mode must be QUICKSR_QNN or QUICKSR_CPU");
            }
            if (selectedMode == VideoMode.QUICKSR_QNN
                    && (!BuildConfig.QNN_RUNTIME_EXPECTED || !qnnEnvironmentReady)) {
                return benchmarkConfigurationFailure(
                        "QUICKSR_QNN requested but the QNN runtime is unavailable");
            }
            if (captureSpec.isRequested() && selectedMode != VideoMode.QUICKSR_QNN) {
                return benchmarkConfigurationFailure(
                        "Video tensor capture is restricted to QUICKSR_QNN");
            }
            videoMode = selectedMode;
            videoNeuralProfileSpinner.setSelection(selectedProfile.ordinal());
            videoQnnTuningSpinner.setSelection(selectedTuning.ordinal());
            benchmarkMode = selectedMode == VideoMode.QUICKSR_QNN
                    ? QuickSrSession.Mode.QNN_HTP
                    : QuickSrSession.Mode.CPU;
            benchmarkTuning = selectedMode == VideoMode.QUICKSR_QNN
                    ? selectedTuning
                    : QuickSrSession.Tuning.BASELINE;
            benchmarkProfile = selectedProfile;
            benchmarkCaptureSpec = captureSpec;
            setBenchmarkPlaybackRepeats(true);
            synchronized (benchmarkStatsLock) {
                benchmarkStatsBatch.clear();
            }
            updateVideoEffectButton();
            Log.i(
                    VideoBenchmarkTelemetry.TAG,
                    VideoBenchmarkTelemetry.configurationJson(
                            benchmarkRunId,
                            selectedMode.name(),
                            benchmarkTuning,
                            benchmarkProfile,
                            BuildConfig.QNN_RUNTIME_EXPECTED,
                            benchmarkCaptureSpec,
                            BuildConfig.QUICKSR_POSTPROCESS_OVERLAP));
            return true;
        } catch (IllegalArgumentException failure) {
            return benchmarkConfigurationFailure(
                    "Invalid benchmark enum value: " + safeMessage(failure));
        }
    }

    private boolean benchmarkConfigurationFailure(String message) {
        setBenchmarkPlaybackRepeats(false);
        logBenchmarkError("configuration", message);
        updateVideoStatus("设备基准配置失败：" + message);
        return false;
    }

    static boolean validBenchmarkRunId(String value) {
        return value != null
                && value.length() >= 1
                && value.length() <= 80
                && value.matches("[A-Za-z0-9._-]+");
    }

    static int repeatModeForBenchmark(boolean benchmarkPlaybackRepeats) {
        return benchmarkPlaybackRepeats ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF;
    }

    private void setBenchmarkPlaybackRepeats(boolean repeats) {
        benchmarkPlaybackRepeats = repeats;
        applyBenchmarkRepeatMode();
    }

    private void applyBenchmarkRepeatMode() {
        if (player != null) {
            player.setRepeatMode(repeatModeForBenchmark(benchmarkPlaybackRepeats));
        }
    }

    private void recordBenchmarkStats(
            String runId,
            QuickSrVideoEffect.FrameStats stats) {
        if (!isActiveBenchmarkRun(runId)
                || stats.mode != benchmarkMode
                || stats.tuning != benchmarkTuning
                || stats.profile != benchmarkProfile) {
            return;
        }
        List<QuickSrVideoEffect.FrameStats> ready = null;
        synchronized (benchmarkStatsLock) {
            benchmarkStatsBatch.add(stats);
            if (benchmarkStatsBatch.size() >= VideoBenchmarkTelemetry.FRAME_BATCH_SIZE) {
                ready = new ArrayList<>(benchmarkStatsBatch);
                benchmarkStatsBatch.clear();
            }
        }
        if (ready != null) {
            Log.i(
                    VideoBenchmarkTelemetry.TAG,
                    VideoBenchmarkTelemetry.frameBatchJson(runId, ready));
        }
    }

    private void flushBenchmarkStats() {
        List<QuickSrVideoEffect.FrameStats> ready;
        synchronized (benchmarkStatsLock) {
            if (benchmarkStatsBatch.isEmpty()) {
                return;
            }
            ready = new ArrayList<>(benchmarkStatsBatch);
            benchmarkStatsBatch.clear();
        }
        if (benchmarkIntentActive && benchmarkRunId != null) {
            Log.i(
                    VideoBenchmarkTelemetry.TAG,
                    VideoBenchmarkTelemetry.frameBatchJson(benchmarkRunId, ready));
        }
    }

    private void logBenchmarkError(String stage, String message) {
        logBenchmarkError(benchmarkRunId, stage, message);
    }

    private void logBenchmarkError(String runId, String stage, String message) {
        if (isActiveBenchmarkRun(runId)) {
            Log.e(
                    VideoBenchmarkTelemetry.TAG,
                    VideoBenchmarkTelemetry.errorJson(runId, stage, message));
        }
    }

    private void recordBenchmarkQnnStrictEvidence(
            String runId,
            QuickSrVideoEffect.Profile profile,
            JSONObject qnnStrict) {
        if (!isActiveBenchmarkRun(runId)) {
            return;
        }
        benchmarkQnnStrictEvidence = qnnStrict;
        Log.i(
                VideoBenchmarkTelemetry.TAG,
                VideoBenchmarkTelemetry.qnnStrictJson(runId, profile, qnnStrict));
    }

    private void recordBenchmarkEvidenceCapture(String runId, JSONObject evidence) {
        if (!isActiveBenchmarkRun(runId)) {
            return;
        }
        Log.i(
                VideoBenchmarkTelemetry.TAG,
                VideoBenchmarkTelemetry.evidenceCaptureJson(runId, evidence));
    }

    private void recordBenchmarkProcessingError(String runId, String stage, Throwable failure) {
        if (!isActiveBenchmarkRun(runId)) {
            return;
        }
        logBenchmarkError(runId, stage, safeMessage(failure));
        Log.e(
                VideoBenchmarkTelemetry.TAG,
                VideoBenchmarkTelemetry.terminalJson(
                        runId,
                        "FAIL",
                        stage,
                        benchmarkQnnStrictEvidence));
    }

    private void recordBenchmarkPipelineSnapshot(
            String runId,
            VideoPipelineTelemetry.Snapshot snapshot,
            String reason) {
        if (!isActiveBenchmarkRun(runId)) {
            return;
        }
        Log.i(
                VideoBenchmarkTelemetry.TAG,
                VideoBenchmarkTelemetry.pipelineSnapshotJson(runId, snapshot, reason));
    }

    private void logBenchmarkTerminal(String status, String stage) {
        if (!isActiveBenchmarkRun(benchmarkRunId)) {
            return;
        }
        Log.i(
                VideoBenchmarkTelemetry.TAG,
                VideoBenchmarkTelemetry.terminalJson(
                        benchmarkRunId,
                        status,
                        stage,
                        benchmarkQnnStrictEvidence));
    }

    private boolean isActiveBenchmarkRun(String runId) {
        return benchmarkIntentActive && runId != null && runId.equals(benchmarkRunId);
    }

    private void clearBenchmarkTelemetry() {
        benchmarkIntentActive = false;
        setBenchmarkPlaybackRepeats(false);
        benchmarkRunId = null;
        benchmarkMode = null;
        benchmarkTuning = null;
        benchmarkProfile = null;
        benchmarkCaptureSpec = VideoEvidenceStore.CaptureSpec.none();
        benchmarkQnnStrictEvidence = null;
        synchronized (benchmarkStatsLock) {
            benchmarkStatsBatch.clear();
        }
    }

    private void toggleVideoEffect() {
        do {
            videoMode = videoMode.next();
        } while (!BuildConfig.QNN_RUNTIME_EXPECTED && videoMode == VideoMode.QUICKSR_QNN);
        applyVideoEffects();
        updateVideoEffectButton();
    }

    private void applyVideoEffects() {
        if (player == null) {
            return;
        }
        boolean neuralMode = isNeuralVideoMode();
        videoNeuralProfileSpinner.setEnabled(neuralMode);
        videoQnnTuningSpinner.setEnabled(videoMode == VideoMode.QUICKSR_QNN);
        videoTargetSpinner.setEnabled(
                videoMode == VideoMode.GPU_LANCZOS || videoMode == VideoMode.GPU_ANIME4K);
        if (videoMode == VideoMode.ORIGINAL) {
            String effectKey = "ORIGINAL";
            if (!shouldApplyVideoEffect(appliedVideoEffectKey, effectKey)) {
                return;
            }
            player.setVideoEffects(Collections.emptyList());
            appliedVideoEffectKey = effectKey;
            updateVideoStatus("原始视频路径：未应用上采样效果。");
            return;
        }
        if (videoMode == VideoMode.QUICKSR_QNN || videoMode == VideoMode.QUICKSR_CPU) {
            QuickSrSession.Mode backend = videoMode == VideoMode.QUICKSR_QNN
                    ? QuickSrSession.Mode.QNN_HTP
                    : QuickSrSession.Mode.CPU;
            QuickSrVideoEffect.Profile selectedProfile =
                    (QuickSrVideoEffect.Profile) videoNeuralProfileSpinner.getSelectedItem();
            if (selectedProfile == null) {
                selectedProfile = QuickSrVideoEffect.Profile.FULL_720P;
            }
            QuickSrSession.Tuning selectedTuning = videoMode == VideoMode.QUICKSR_QNN
                    ? (QuickSrSession.Tuning) videoQnnTuningSpinner.getSelectedItem()
                    : QuickSrSession.Tuning.BASELINE;
            if (selectedTuning == null) {
                selectedTuning = QuickSrSession.Tuning.SUSTAINED;
            }
            final QuickSrVideoEffect.Profile profile = selectedProfile;
            final QuickSrSession.Tuning tuning = selectedTuning;
            final String effectBenchmarkRunId = benchmarkIntentActive
                    && VideoEvidenceStore.isSafeRunId(benchmarkRunId)
                    ? benchmarkRunId
                    : null;
            final VideoEvidenceStore.CaptureSpec effectCaptureSpec = effectBenchmarkRunId == null
                    ? VideoEvidenceStore.CaptureSpec.none()
                    : benchmarkCaptureSpec;
            String effectKey = neuralVideoEffectKey(
                    backend,
                    profile,
                    tuning,
                    effectBenchmarkRunId,
                    effectCaptureSpec,
                    BuildConfig.QUICKSR_POSTPROCESS_OVERLAP);
            if (!shouldApplyVideoEffect(appliedVideoEffectKey, effectKey)) {
                return;
            }
            Effect effect = new QuickSrVideoEffect(
                    getApplicationContext(),
                    backend,
                    profile,
                    tuning,
                    effectBenchmarkRunId,
                    effectCaptureSpec,
                    BuildConfig.QUICKSR_POSTPROCESS_OVERLAP,
                    new QuickSrVideoEffect.StatsListener() {
                        @Override
                        public void onFrameProcessed(QuickSrVideoEffect.FrameStats stats) {
                            if (effectBenchmarkRunId != null) {
                                recordBenchmarkStats(effectBenchmarkRunId, stats);
                            }
                            if (stats.frameNumber != 1 && stats.frameNumber % 15 != 0) {
                                return;
                            }
                            runOnUiThread(() -> {
                                if (!activityDestroyed && videoModeMatches(stats.mode)
                                        && videoProfileMatches(stats.profile)
                                        && videoTuningMatches(stats.tuning)) {
                                    updateVideoStatus(String.format(
                                            Locale.US,
                                            "%s · %s · %s：已完成 %d 帧\n" +
                                                    "效果画布 %d×%d；神经 %d×%d→%d×%d；" +
                                                    "会话/复制/排队/RGBA转张量/整段推理/输出转RGBA/总计 " +
                                                    "%d/%d/%d/%d/%d/%d/%d ms\n" +
                                                    "推理拆分：输入拷贝/ORT run/输出拷贝/finite扫描 " +
                                                    "%d/%d/%d/%d ms%s；PTS %.3f s",
                                            stats.mode,
                                            videoTuningLabel(stats.mode, stats.tuning),
                                            stats.profile,
                                            stats.frameNumber,
                                            stats.effectInputWidth,
                                            stats.effectInputHeight,
                                            stats.modelInputWidth,
                                            stats.modelInputHeight,
                                            stats.modelOutputWidth,
                                            stats.modelOutputHeight,
                                            stats.sessionSetupMs,
                                            stats.copyMs,
                                            stats.queueMs,
                                            stats.inputConversionMs,
                                            stats.inferenceMs,
                                            stats.outputConversionMs,
                                            stats.totalProcessingMs,
                                            stats.tensorInputCopyMs,
                                            stats.ortRunMs,
                                            stats.tensorOutputCopyMs,
                                            stats.finiteScanMs,
                                            stats.finiteScanExecuted ? "" : "（本帧跳过扫描）",
                                            stats.presentationTimeUs / 1_000_000.0));
                                }
                            });
                        }

                        @Override
                        public void onQnnStrictEvidence(JSONObject qnnStrict) {
                            if (effectBenchmarkRunId != null) {
                                recordBenchmarkQnnStrictEvidence(
                                        effectBenchmarkRunId,
                                        profile,
                                        qnnStrict);
                            }
                        }

                        @Override
                        public void onEvidenceCaptured(JSONObject evidence) {
                            if (effectBenchmarkRunId != null) {
                                recordBenchmarkEvidenceCapture(effectBenchmarkRunId, evidence);
                            }
                        }

                        @Override
                        public void onProcessingError(String stage, Throwable failure) {
                            if (effectBenchmarkRunId != null) {
                                recordBenchmarkProcessingError(
                                        effectBenchmarkRunId,
                                        stage,
                                        failure);
                            }
                        }

                        @Override
                        public void onPipelineSnapshot(
                                VideoPipelineTelemetry.Snapshot snapshot,
                                String reason) {
                            if (effectBenchmarkRunId != null) {
                                recordBenchmarkPipelineSnapshot(
                                        effectBenchmarkRunId,
                                        snapshot,
                                        reason);
                            }
                        }
                    });
            // ByteBufferGlEffect keeps the dimensions of its input texture for its output pool.
            // Establish the display canvas first, then let QuickSR downsample only its readback
            // input and write the selected-scale neural result into the effect pipeline.
            Effect outputCanvas = Presentation.createForWidthAndHeight(
                    profile.canvasWidth(),
                    profile.canvasHeight(),
                    Presentation.LAYOUT_SCALE_TO_FIT);
            player.setVideoEffects(Arrays.asList(outputCanvas, effect));
            appliedVideoEffectKey = effectKey;
            updateVideoStatus(
                    backend + " · " + tuning + " 已开启：每帧 " + profile +
                            "，输出画布 " + profile.canvasWidth() + "×" + profile.canvasHeight() + "。" +
                            (profile.canvasWidth() == profile.outputWidth()
                                    ? ""
                                    : "神经纹理 " + profile.outputWidth() + "×" + profile.outputHeight()
                                            + "，再由 GPU 缩放显示。") +
                            "正在等待首帧 HTP/CPU 实测……");
            return;
        }
        int position = Math.max(0, videoTargetSpinner.getSelectedItemPosition());
        int[] target = VIDEO_TARGETS[position];
        if (videoMode == VideoMode.GPU_ANIME4K) {
            int[] input = Anime4kSmallEffect.inputSizeForTarget(target[0], target[1]);
            String effectKey = anime4kVideoEffectKey(target[0], target[1]);
            if (!shouldApplyVideoEffect(appliedVideoEffectKey, effectKey)) {
                return;
            }
            Effect inputCanvas = Presentation.createForWidthAndHeight(
                    input[0],
                    input[1],
                    Presentation.LAYOUT_SCALE_TO_FIT);
            anime4kRecoveryInProgress = false;
            Effect anime4k = new Anime4kSmallEffect(new Anime4kSmallEffect.StatusListener() {
                @Override
                public void onStatus(boolean modelActive, String detail) {
                    runOnUiThread(() -> {
                        if (!activityDestroyed
                                && videoMode == VideoMode.GPU_ANIME4K
                                && effectKey.equals(appliedVideoEffectKey)) {
                            if (modelActive) {
                                updateVideoStatus(
                                        "Anime4K v4.0.1 x2 Small 首帧 GL 路径已完成："
                                                + input[0] + "×" + input[1] + "→"
                                                + target[0] + "×" + target[1]
                                                + "。这只表示 model active；仍需固定同帧参考、"
                                                + "GPU timing 与 thermal A/B。");
                            } else {
                                updateVideoStatus(
                                        "Anime4K model inactive，已在同一 GL effect 内回退 "
                                                + "GPU 双线性 2×：" + detail);
                            }
                        }
                    });
                }

                @Override
                public void onFatalFallbackFailure(String detail) {
                    runOnUiThread(() -> {
                        if (!activityDestroyed
                                && videoMode == VideoMode.GPU_ANIME4K
                                && effectKey.equals(appliedVideoEffectKey)) {
                            updateVideoStatus(
                                    "Anime4K effect 内 fallback 已失败，等待 Media3 error "
                                            + "触发一次 app-level 恢复：" + detail);
                        }
                    });
                }
            });
            player.setVideoEffects(Arrays.asList(inputCanvas, anime4k));
            appliedVideoEffectKey = effectKey;
            updateVideoStatus(
                    "Anime4K v4.0.1 x2 Small 已请求：GPU "
                            + input[0] + "×" + input[1] + "→"
                            + target[0] + "×" + target[1]
                            + "，等待 shader compile/link 与首帧验证……");
            return;
        }
        String effectKey = "GPU_LANCZOS:" + target[0] + "x" + target[1];
        if (!shouldApplyVideoEffect(appliedVideoEffectKey, effectKey)) {
            return;
        }
        Effect effect = LanczosResample.scaleToFitWithFlexibleOrientation(
                target[0],
                target[1]);
        player.setVideoEffects(Collections.singletonList(effect));
        appliedVideoEffectKey = effectKey;
        updateVideoStatus(
                "GPU Lanczos 已开启，目标边界 " + target[0] + "×" + target[1] + "。");
    }

    static boolean shouldApplyVideoEffect(String appliedKey, String requestedKey) {
        return !requestedKey.equals(appliedKey);
    }

    static String anime4kVideoEffectKey(int targetWidth, int targetHeight) {
        return "GPU_ANIME4K:" + targetWidth + "x" + targetHeight + ':'
                + Anime4kSmallEffect.UPSTREAM_COMMIT;
    }

    static String anime4kAppFallbackKey(int targetWidth, int targetHeight) {
        return "GPU_ANIME4K_APP_FALLBACK_LANCZOS:" + targetWidth + "x" + targetHeight;
    }

    private boolean recoverFromAnime4kPipelineFailure(String detail) {
        if (activityDestroyed
                || player == null
                || videoMode != VideoMode.GPU_ANIME4K) {
            return false;
        }
        if (anime4kRecoveryInProgress) {
            return false;
        }
        anime4kRecoveryInProgress = true;
        int position = Math.max(0, videoTargetSpinner.getSelectedItemPosition());
        int[] target = VIDEO_TARGETS[position];
        long playbackPositionMs = Math.max(0L, player.getCurrentPosition());
        boolean resumePlayback = player.getPlayWhenReady();
        try {
            Effect appFallback = LanczosResample.scaleToFitWithFlexibleOrientation(
                    target[0], target[1]);
            player.setVideoEffects(Collections.singletonList(appFallback));
            appliedVideoEffectKey = anime4kAppFallbackKey(target[0], target[1]);
            player.prepare();
            player.seekTo(playbackPositionMs);
            if (resumePlayback) {
                player.play();
            }
            updateVideoStatus(
                    "Anime4K 与 effect 内双线性均未能继续；已移除 Anime4K，"
                            + "改用 app-level GPU Lanczos 并从原位置重试：" + detail);
            return true;
        } catch (RuntimeException recoveryFailure) {
            anime4kRecoveryInProgress = false;
            return false;
        }
    }

    private static String neuralVideoEffectKey(
            QuickSrSession.Mode backend,
            QuickSrVideoEffect.Profile profile,
            QuickSrSession.Tuning tuning,
            String benchmarkRunId,
            VideoEvidenceStore.CaptureSpec captureSpec,
            boolean postprocessOverlap) {
        return "NEURAL:"
                + backend + ':'
                + profile + ':'
                + tuning + ':'
                + (benchmarkRunId == null ? "interactive" : benchmarkRunId) + ':'
                + captureSpec.telemetryKind() + ':'
                + captureSpec.value() + ':'
                + (postprocessOverlap ? "overlap" : "serial");
    }

    private void updateVideoEffectButton() {
        if (videoEffectButton != null) {
            videoEffectButton.setText("当前：" + videoModeLabel() + "（点击切换）");
        }
    }

    private String videoModeLabel() {
        switch (videoMode) {
            case QUICKSR_QNN:
                return "QuickSR QNN HTP · 真实逐帧/实验";
            case QUICKSR_CPU:
                return "QuickSR CPU · 真实逐帧/很慢";
            case GPU_ANIME4K:
                return "Anime4K x2 Small · GPU-resident/待真机 A/B";
            case GPU_LANCZOS:
                return "GPU Lanczos · 实时传统上采样";
            case ORIGINAL:
                return "原始视频";
            default:
                throw new IllegalStateException("Unknown video mode: " + videoMode);
        }
    }

    private static String videoTuningLabel(
            QuickSrSession.Mode mode,
            QuickSrSession.Tuning tuning) {
        return mode == QuickSrSession.Mode.CPU ? "CPU 默认" : tuning.toString();
    }

    private boolean videoModeMatches(QuickSrSession.Mode mode) {
        return (videoMode == VideoMode.QUICKSR_QNN && mode == QuickSrSession.Mode.QNN_HTP) ||
                (videoMode == VideoMode.QUICKSR_CPU && mode == QuickSrSession.Mode.CPU);
    }

    private boolean isNeuralVideoMode() {
        return videoMode == VideoMode.QUICKSR_QNN || videoMode == VideoMode.QUICKSR_CPU;
    }

    private boolean videoProfileMatches(QuickSrVideoEffect.Profile profile) {
        return videoNeuralProfileSpinner != null
                && videoNeuralProfileSpinner.getSelectedItem() == profile;
    }

    private boolean videoTuningMatches(QuickSrSession.Tuning tuning) {
        if (videoMode != VideoMode.QUICKSR_QNN) {
            return tuning == QuickSrSession.Tuning.BASELINE;
        }
        return videoQnnTuningSpinner != null
                && videoQnnTuningSpinner.getSelectedItem() == tuning;
    }

    private void updateVideoStatus(String value) {
        if (videoStatus != null) {
            videoStatus.setText(value);
        }
    }

    private void setImageRunning(boolean running) {
        chooseImageButton.setEnabled(!running);
        imageBackendSpinner.setEnabled(!running);
        imageSizeSpinner.setEnabled(!running);
        synchronized (bitmapOwnershipLock) {
            // Bitmap.compress is not safely interruptible on all Android builds. During a save,
            // keep Cancel disabled and let the leased bitmap finish on the background executor.
            cancelImageButton.setEnabled(running && bitmapBeingSaved == null);
        }
        if (running) {
            saveImageButton.setEnabled(false);
        }
    }

    private Bitmap decodeBitmap(Uri uri, int maxDimension) throws IOException {
        Bitmap decoded;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
            decoded = ImageDecoder.decodeBitmap(source, (decoder, info, ignored) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
                int largest = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                if (largest > maxDimension) {
                    decoder.setTargetSampleSize(
                            Math.max(1, (int) Math.floor(largest / (double) maxDimension)));
                }
            });
        } else {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream stream = requireStream(uri)) {
                BitmapFactory.decodeStream(stream, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw new IOException("无法读取图片尺寸");
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inSampleSize = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (options.inSampleSize * 2)
                    >= maxDimension) {
                options.inSampleSize *= 2;
            }
            try (InputStream stream = requireStream(uri)) {
                decoded = BitmapFactory.decodeStream(stream, null, options);
            }
            if (decoded == null) {
                throw new IOException("无法解码图片");
            }
        }
        decoded = requireArgb8888(decoded);
        return scaleToFitLimits(decoded, maxDimension);
    }

    private static Bitmap scaleToFitLimits(Bitmap source, int maxDimension) {
        double longEdgeScale = Math.min(
                1.0,
                maxDimension / (double) Math.max(source.getWidth(), source.getHeight()));
        double outputPixelScale = Math.min(
                1.0,
                Math.sqrt(
                        FullImageUpscaler.MAX_OUTPUT_PIXELS /
                                (4.0 * source.getWidth() * source.getHeight())));
        double scale = Math.min(longEdgeScale, outputPixelScale);
        if (scale >= 0.999999) {
            return source;
        }
        int width = Math.max(1, (int) Math.floor(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.floor(source.getHeight() * scale));
        Bitmap resized = Bitmap.createScaledBitmap(source, width, height, true);
        if (resized != source) {
            source.recycle();
        }
        return resized;
    }

    private static Bitmap requireArgb8888(Bitmap source) throws IOException {
        if (source.getConfig() == Bitmap.Config.ARGB_8888) {
            return source;
        }
        Bitmap converted = source.copy(Bitmap.Config.ARGB_8888, false);
        if (converted == null) {
            throw new IOException("无法转换图片像素格式");
        }
        source.recycle();
        return converted;
    }

    private InputStream requireStream(Uri uri) throws IOException {
        InputStream stream = getContentResolver().openInputStream(uri);
        if (stream == null) {
            throw new IOException("无法打开图片");
        }
        return stream;
    }

    private void recycleLatestImages() {
        sourcePreview.setImageDrawable(null);
        outputPreview.setImageDrawable(null);
        Bitmap source;
        Bitmap output;
        boolean recycleOutput;
        synchronized (bitmapOwnershipLock) {
            source = latestSource;
            output = latestOutput;
            latestSource = null;
            latestOutput = null;
            recycleOutput = output != null && output != bitmapBeingSaved;
        }
        recycleBitmap(source);
        if (recycleOutput) {
            recycleBitmap(output);
        }
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() +
                (message == null || message.isBlank() ? "" : "：" + message);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        flushBenchmarkStats();
        logBenchmarkTerminal("ACTIVITY_DESTROYED", "activity");
        activityDestroyed = true;
        ++imageTaskGeneration;
        Future<?> imageTask = activeImageTask;
        activeImageTask = null;
        if (imageTask != null && !imageTask.isDone()) {
            imageTask.cancel(true);
        }
        imageExecutor.shutdownNow();
        // A PNG encoder may keep reading its Bitmap after interruption. Let that short-lived
        // worker finish; recycleLatestImages() leaves its leased output for releaseSavedBitmap().
        activeSaveTask = null;
        saveExecutor.shutdown();
        if (playerView != null) {
            playerView.setPlayer(null);
        }
        if (player != null) {
            player.release();
        }
        recycleLatestImages();
        super.onDestroy();
    }
}
