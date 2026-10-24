package dev.stone.photorotator;

import android.Manifest;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.res.ColorStateList;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.ext.SdkExtensions;
import android.provider.MediaStore;
import android.provider.Settings;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.drawable.GradientDrawable;
import android.util.Size;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.exifinterface.media.ExifInterface;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int COLOR_SURFACE = 0xFFF2F5FA;
    private static final int COLOR_TEXT = 0xFF263449;
    private static final int COLOR_MUTED = 0xFF637187;
    private int brandColor;
    private static final int PICK_NATIVE_PHOTOS = 12;
    private static final int REQUEST_ORIGINAL_PHOTOS = 13;
    private static final int REQUEST_OVERWRITE_PERMISSION = 14;
    private static final int REQUEST_MEDIA_MANAGEMENT = 15;
    private static final int MAX_SELECTED_PHOTOS = 50;
    private static final String STATE_SELECTED = "selected_photos";
    private static final String STATE_PICKER_SELECTION = "picker_selection";
    private static final String STATE_PREVIEW_SOURCES = "preview_sources";
    private static final String PREFS_NAME = "photo_rotator_preferences";
    private static final String PREF_DEFAULT_ROTATION = "default_rotation";
    private static final String PREF_DEFAULT_QUALITY = "default_compression_quality";
    private static final String PREF_DEFAULT_SAVE_MODE = "default_save_mode";
    private static final String PREF_DEFAULT_TIMESTAMP = "default_timestamp";
    private static final String PREF_MEDIA_MANAGEMENT_OFFERED = "media_management_offered";
    private static final int PAGE_MAIN = 0;
    private static final int PAGE_SETTINGS = 1;
    private static final int PAGE_ABOUT = 2;
    // This extra was introduced in API 36 / R extension 15; compileSdk is currently 35.
    private static final String EXTRA_PICKER_PRE_SELECTION_URIS =
            "android.provider.extra.PICKER_PRE_SELECTION_URIS";
    private Button chooseButton;
    private Button processButton;
    private RadioGroup operationGroup;
    private RadioGroup qualityGroup;
    private View rotationSection;
    private View compressionSection;
    private RadioGroup directionGroup;
    private RadioGroup saveModeGroup;
    private RadioGroup overwriteTimestampGroup;
    private View mainScreen;
    private View settingsScreen;
    private int currentPage = PAGE_MAIN;
    private ProgressBar progress;
    private TextView selectionText;
    private TextView untickAllButton;
    private TextView collapsePreviewButton;
    private TextView statusText;
    private LinearLayout selectionRow;
    private GridLayout previewGrid;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService previewWorker = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> thumbnailCache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getAllocationByteCount();
        }
    };
    private volatile int previewGeneration;
    private final ArrayList<Uri> selected = new ArrayList<>();
    private final Map<Uri, Uri> updatedPhotoSources = new HashMap<>();
    private boolean previewExpanded;
    private boolean pickerResultReplacesSelection;
    private boolean selectedUrisFromPhotoPicker = true;
    private boolean launchedPhotoPicker;
    private boolean processing;
    private ArrayList<Uri> pendingItems;
    private int pendingDegrees;
    private boolean pendingCompression;
    private int pendingQuality;
    private boolean pendingUpdateTimestamp;
    private boolean pendingOverwrite;
    private boolean resumeProcessingAfterMediaManagement;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        brandColor = getColor(R.color.launcher_background);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarContrastEnforced(false);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(false);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightNavigationBars(false);
        buildScreen();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (processing) return;
                if (currentPage == PAGE_ABOUT) showSettingsScreen();
                else if (currentPage == PAGE_SETTINGS) showMainScreen();
                // Keep the current selection and controls, just like pressing Home.
                else moveTaskToBack(true);
            }
        });
        if (savedInstanceState != null) {
            ArrayList<Uri> restored = savedInstanceState.getParcelableArrayList(STATE_SELECTED);
            if (restored != null) selected.addAll(restored);
            ArrayList<Uri> restoredSources = savedInstanceState.getParcelableArrayList(STATE_PREVIEW_SOURCES);
            if (restoredSources != null && restoredSources.size() == selected.size()) {
                for (int index = 0; index < selected.size(); index++) {
                    if (restoredSources.get(index) != null) {
                        updatedPhotoSources.put(selected.get(index), restoredSources.get(index));
                    }
                }
            }
            selectedUrisFromPhotoPicker = savedInstanceState.getBoolean(STATE_PICKER_SELECTION, true);
            operationGroup.check(operationGroup.getChildAt(savedInstanceState.getBoolean("compress_mode", false) ? 1 : 0).getId());
            checkInt(directionGroup, savedInstanceState.getInt("rotation", selectedInt(directionGroup)));
            checkInt(qualityGroup, savedInstanceState.getInt("quality", selectedInt(qualityGroup)));
            checkInt(saveModeGroup, savedInstanceState.getInt("save_mode", selectedInt(saveModeGroup)));
            checkInt(overwriteTimestampGroup, savedInstanceState.getInt("timestamp", selectedInt(overwriteTimestampGroup)));
            pendingCompression = savedInstanceState.getBoolean("pending_compression");
            pendingQuality = savedInstanceState.getInt("pending_quality", 85);
            pendingDegrees = savedInstanceState.getInt("pending_degrees", -90);
            pendingOverwrite = savedInstanceState.getBoolean("pending_overwrite");
            pendingUpdateTimestamp = savedInstanceState.getBoolean("pending_timestamp");
            pendingItems = savedInstanceState.getParcelableArrayList("pending_items");
            resumeProcessingAfterMediaManagement = savedInstanceState.getBoolean("resume_media_management");
            updateSelection();
        } else {
            receiveSharedImages(getIntent());
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        receiveSharedImages(intent);
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putParcelableArrayList(STATE_SELECTED, new ArrayList<>(selected));
        ArrayList<Uri> previewSources = new ArrayList<>();
        for (Uri uri : selected) previewSources.add(updatedPhotoSources.get(uri));
        outState.putParcelableArrayList(STATE_PREVIEW_SOURCES, previewSources);
        outState.putBoolean(STATE_PICKER_SELECTION, selectedUrisFromPhotoPicker);
        outState.putBoolean("compress_mode", isCompressionMode());
        outState.putInt("rotation", selectedInt(directionGroup));
        outState.putInt("quality", selectedInt(qualityGroup));
        outState.putInt("save_mode", selectedInt(saveModeGroup));
        outState.putInt("timestamp", selectedInt(overwriteTimestampGroup));
        outState.putBoolean("pending_compression", pendingCompression);
        outState.putInt("pending_quality", pendingQuality);
        outState.putInt("pending_degrees", pendingDegrees);
        outState.putBoolean("pending_overwrite", pendingOverwrite);
        outState.putBoolean("pending_timestamp", pendingUpdateTimestamp);
        outState.putParcelableArrayList("pending_items", pendingItems);
        outState.putBoolean("resume_media_management", resumeProcessingAfterMediaManagement);
        super.onSaveInstanceState(outState);
    }

    private void receiveSharedImages(Intent intent) {
        if (intent == null || !(Intent.ACTION_SEND.equals(intent.getAction())
                || Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction()))) return;
        if (processing) {
            statusText.setText("正在处理照片，请完成后重新分享。");
            return;
        }
        ArrayList<Uri> shared = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<?> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (streams != null) {
                for (Object item : streams) {
                    if (!(item instanceof Uri)) {
                        statusText.setText("分享内容包含无效的照片地址，请重新分享。");
                        return;
                    }
                    shared.add((Uri) item);
                }
            }
        } else {
            Object stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream instanceof Uri) shared.add((Uri) stream);
        }
        if (shared.isEmpty()) {
            ClipData clip = intent.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri uri = clip.getItemAt(i).getUri();
                    if (uri != null) shared.add(uri);
                }
            }
        }
        if (shared.size() > MAX_SELECTED_PHOTOS) {
            new AlertDialog.Builder(this)
                    .setTitle("分享的照片过多")
                    .setMessage("本次分享了 " + shared.size() + " 张照片，最多可一次处理 50 张。请回到相册，重新选择不超过 50 张。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        if (shared.isEmpty()) {
            statusText.setText("没有收到照片，请从相册重新分享。");
            return;
        }
        for (Uri uri : shared) {
            if (!"content".equals(uri.getScheme())) {
                statusText.setText("分享内容包含无法读取的照片地址，请从相册重新分享。");
                return;
            }
            try {
                String mime = getContentResolver().getType(uri);
                if (mime != null && !mime.startsWith("image/")) {
                    statusText.setText("分享内容包含非图片文件，请只分享照片。");
                    return;
                }
                if (mime != null && !"image/jpeg".equalsIgnoreCase(mime)
                        && !"image/jpg".equalsIgnoreCase(mime)
                        && !"image/png".equalsIgnoreCase(mime)
                        && !"image/webp".equalsIgnoreCase(mime)) {
                    statusText.setText("分享内容包含暂不支持的图片格式；目前只支持 JPEG、PNG、WebP。");
                    return;
                }
            } catch (RuntimeException e) {
                Log.w("PhotoRotator", "Could not inspect shared photo", e);
                statusText.setText("无法读取分享的照片，请从相册重新分享。");
                return;
            }
        }
        selected.clear();
        selected.addAll(new LinkedHashSet<>(shared));
        selectedUrisFromPhotoPicker = false;
        previewExpanded = false;
        updateSelection();
        statusText.setText("已载入 " + selected.size() + " 张分享照片。请确认处理功能与保存设置；其他应用分享的照片可能不包含完整 EXIF，且可能无法覆盖原图。");
    }

    private void buildScreen() {
        LinearLayout root = createPageRoot();

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        stylePageHeader(titleRow);

        LinearLayout titleColumn = new LinearLayout(this);
        titleColumn.setOrientation(LinearLayout.VERTICAL);
        titleRow.addView(titleColumn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText("Photo Rotator");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(28);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        titleColumn.addView(title, wrapWrap());

        TextView version = new TextView(this);
        version.setText(appVersionLabel());
        version.setTextColor(0xFFDCE6FF);
        version.setTextSize(14);
        LinearLayout.LayoutParams versionParams = wrapWrap();
        versionParams.topMargin = dp(2);
        titleColumn.addView(version, versionParams);

        TextView settingsButton = new TextView(this);
        settingsButton.setText("⚙ 设置");
        settingsButton.setTextColor(0xFFFFFFFF);
        settingsButton.setTextSize(16);
        settingsButton.setGravity(Gravity.CENTER);
        settingsButton.setMinHeight(dp(48));
        settingsButton.setPadding(dp(8), 0, 0, 0);
        settingsButton.setContentDescription("设置");
        settingsButton.setOnClickListener(v -> showSettingsScreen());
        titleRow.addView(settingsButton, wrapWrap());

        operationGroup = new RadioGroup(this);
        operationGroup.setOrientation(RadioGroup.HORIZONTAL);
        operationGroup.setContentDescription("处理功能");
        addRadio(operationGroup, "旋转", 0, true);
        addRadio(operationGroup, "压缩", 1, false);
        for (int i = 0; i < operationGroup.getChildCount(); i++) {
            RadioButton tab = (RadioButton) operationGroup.getChildAt(i);
            RadioGroup.LayoutParams tabParams = new RadioGroup.LayoutParams(0, dp(48), 1f);
            if (i == 0) tabParams.rightMargin = dp(6);
            else tabParams.leftMargin = dp(6);
            tab.setLayoutParams(tabParams);
            tab.setButtonDrawable(null);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(8), 0, dp(8), 0);
            tab.setTextColor(new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{0xFFFFFFFF, brandColor}));
            android.graphics.drawable.StateListDrawable backgrounds = new android.graphics.drawable.StateListDrawable();
            GradientDrawable active = new GradientDrawable();
            active.setColor(brandColor);
            active.setCornerRadius(dp(14));
            GradientDrawable inactive = new GradientDrawable();
            inactive.setColor(0xFFE0E9FA);
            inactive.setCornerRadius(dp(14));
            backgrounds.addState(new int[]{android.R.attr.state_checked}, active);
            backgrounds.addState(new int[]{}, inactive);
            tab.setBackground(new android.graphics.drawable.RippleDrawable(
                    ColorStateList.valueOf(0x302454A8), backgrounds, null));
        }
        LinearLayout.LayoutParams operationParams = matchWrap();
        operationParams.topMargin = dp(16);
        root.addView(operationGroup, operationParams);

        LinearLayout rotationOptions = new LinearLayout(this);
        rotationOptions.setOrientation(LinearLayout.VERTICAL);
        rotationSection = rotationOptions;
        root.addView(rotationOptions, matchWrap());
        addSectionTitle(rotationOptions, "旋转方向", dp(12));
        LinearLayout directionCard = optionCard();
        directionGroup = new RadioGroup(this);
        directionGroup.setOrientation(RadioGroup.VERTICAL);
        int defaultRotation = defaultPreferences().getInt(PREF_DEFAULT_ROTATION, -90);
        addRadio(directionGroup, "逆时针90度", -90, defaultRotation == -90);
        addRadio(directionGroup, "顺时针90度", 90, defaultRotation == 90);
        addRadio(directionGroup, "180度", 180, defaultRotation == 180);
        directionCard.addView(directionGroup, matchWrap());
        rotationOptions.addView(directionCard, matchWrap());

        LinearLayout compressionOptions = new LinearLayout(this);
        compressionOptions.setOrientation(LinearLayout.VERTICAL);
        compressionSection = compressionOptions;
        root.addView(compressionOptions, matchWrap());
        addSectionTitle(compressionOptions, "压缩强度", dp(12));
        qualityGroup = new RadioGroup(this);
        qualityGroup.setOrientation(RadioGroup.VERTICAL);
        int defaultQuality = defaultPreferences().getInt(PREF_DEFAULT_QUALITY, 85);
        addCompressionChoices(qualityGroup, defaultQuality);
        LinearLayout qualityCard = optionCard();
        qualityCard.addView(qualityGroup, matchWrap());
        compressionOptions.addView(qualityCard, matchWrap());
        addHint(compressionOptions, "保持分辨率，不旋转照片；画质可能变化。\n支持 JPEG、WebP；PNG 会跳过。压缩后未变小也会跳过。");

        addSectionTitle(root, "保存设置", dp(18));
        LinearLayout modeCard = optionCard();
        saveModeGroup = new RadioGroup(this);
        saveModeGroup.setOrientation(RadioGroup.HORIZONTAL);
        int defaultSaveMode = defaultPreferences().getInt(PREF_DEFAULT_SAVE_MODE, 1);
        addRadio(saveModeGroup, "覆盖原图", 1, defaultSaveMode == 1);
        addRadio(saveModeGroup, "另存新图", 0, defaultSaveMode == 0);
        modeCard.addView(saveModeGroup, matchWrap());
        root.addView(modeCard, matchWrap());

        LinearLayout timestampCard = optionCard();
        overwriteTimestampGroup = new RadioGroup(this);
        overwriteTimestampGroup.setOrientation(RadioGroup.VERTICAL);
        int defaultTimestamp = defaultPreferences().getInt(PREF_DEFAULT_TIMESTAMP, 0);
        addHint(timestampCard, "覆盖后的修改时间");
        addRadio(overwriteTimestampGroup, "保持原时间", 0, defaultTimestamp == 0);
        addRadio(overwriteTimestampGroup, "更新为当前时间", 1, defaultTimestamp == 1);
        timestampCard.addView(overwriteTimestampGroup, matchWrap());
        addHint(timestampCard, "不修改 EXIF 拍摄时间");
        timestampCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams timestampParams = matchWrap();
        timestampParams.topMargin = dp(8);
        root.addView(timestampCard, timestampParams);
        saveModeGroup.setOnCheckedChangeListener((group, checkedId) ->
                timestampCard.setVisibility(selectedInt(saveModeGroup) == 1 ? View.VISIBLE : View.GONE));
        timestampCard.setVisibility(selectedInt(saveModeGroup) == 1 ? View.VISIBLE : View.GONE);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionParams = matchWrap();
        actionParams.topMargin = 0;

        chooseButton = new Button(this);
        chooseButton.setText("选择照片");
        styleActionButton(chooseButton, false);
        LinearLayout.LayoutParams chooseParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        chooseParams.rightMargin = dp(6);
        actionRow.addView(chooseButton, chooseParams);
        chooseButton.setOnClickListener(v -> openNativePhotoPicker());

        processButton = new Button(this);
        processButton.setText("开始旋转");
        styleActionButton(processButton, true);
        processButton.setEnabled(false);
        LinearLayout.LayoutParams rotateParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rotateParams.leftMargin = dp(6);
        actionRow.addView(processButton, rotateParams);
        processButton.setOnClickListener(v -> beginProcessing());

        TextView metadataHint = new TextView(this);
        metadataHint.setText("尽量保留拍摄时间、位置等常用 EXIF 信息");
        metadataHint.setTextColor(COLOR_MUTED);
        metadataHint.setTextSize(12);
        metadataHint.setGravity(Gravity.END);
        LinearLayout.LayoutParams metadataParams = matchWrap();
        metadataParams.topMargin = dp(2);

        selectionRow = new LinearLayout(this);
        selectionRow.setOrientation(LinearLayout.HORIZONTAL);
        selectionRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams selectionParams = matchWrap();
        selectionParams.topMargin = dp(16);
        root.addView(selectionRow, selectionParams);

        selectionText = new TextView(this);
        selectionText.setText("尚未选择照片");
        selectionText.setTextColor(COLOR_TEXT);
        selectionText.setTextSize(16);
        selectionRow.addView(selectionText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        untickAllButton = new TextView(this);
        untickAllButton.setText("Untick All");
        untickAllButton.setTextColor(brandColor);
        untickAllButton.setTextSize(14);
        untickAllButton.setGravity(Gravity.CENTER);
        untickAllButton.setPadding(dp(12), dp(8), dp(4), dp(8));
        untickAllButton.setVisibility(View.GONE);
        untickAllButton.setOnClickListener(v -> {
            if (processing) return;
            selected.clear();
            selectedUrisFromPhotoPicker = true;
            previewExpanded = false;
            updateSelection();
        });
        selectionRow.addView(untickAllButton);

        collapsePreviewButton = new TextView(this);
        collapsePreviewButton.setText("收起");
        collapsePreviewButton.setTextColor(brandColor);
        collapsePreviewButton.setTextSize(14);
        collapsePreviewButton.setGravity(Gravity.CENTER);
        collapsePreviewButton.setPadding(dp(12), dp(8), dp(4), dp(8));
        collapsePreviewButton.setVisibility(View.GONE);
        collapsePreviewButton.setOnClickListener(v -> {
            previewExpanded = false;
            showSelectionPreview();
        });
        selectionRow.addView(collapsePreviewButton);

        previewGrid = new GridLayout(this);
        previewGrid.setColumnCount(4);
        previewGrid.setVisibility(View.GONE);
        LinearLayout.LayoutParams previewParams = matchWrap();
        previewParams.topMargin = dp(10);
        root.addView(previewGrid, previewParams);
        LinearLayout actionFooter = new LinearLayout(this);
        actionFooter.setOrientation(LinearLayout.VERTICAL);
        actionFooter.setPadding(dp(20), dp(8), dp(20), dp(8));
        actionFooter.setBackgroundColor(COLOR_SURFACE);
        actionFooter.addView(actionRow, actionParams);
        actionFooter.addView(metadataHint, metadataParams);
        operationGroup.setOnCheckedChangeListener((group, checkedId) -> updateOperation());
        updateOperation();

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = matchWrap();
        progressParams.topMargin = dp(18);
        root.addView(progress, progressParams);

        statusText = new TextView(this);
        statusText.setTextColor(COLOR_MUTED);
        statusText.setTextSize(14);
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(10);
        root.addView(statusText, statusParams);

        mainScreen = createScreen(titleRow, root, actionFooter);
        setContentView(mainScreen);
    }

    private boolean isCompressionMode() {
        return selectedInt(operationGroup) == 1;
    }

    private void updateOperation() {
        boolean compress = isCompressionMode();
        rotationSection.setVisibility(compress ? View.GONE : View.VISIBLE);
        compressionSection.setVisibility(compress ? View.VISIBLE : View.GONE);
        processButton.setText(compress ? "开始压缩" : "开始旋转");
    }

    private void addCompressionChoices(RadioGroup group, int quality) {
        addRadio(group, "轻度 · 优先画质", 92, quality == 92);
        addRadio(group, "均衡 · 推荐", 85, quality == 85);
        addRadio(group, "强力 · 优先体积", 70, quality == 70);
    }

    private void checkInt(RadioGroup group, int value) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (Integer.valueOf(value).equals(child.getTag())) group.check(child.getId());
        }
    }

    private void addHint(LinearLayout parent, String text) {
        TextView hint = new TextView(this);
        hint.setText(text);
        hint.setTextSize(12);
        hint.setTextColor(COLOR_MUTED);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(6);
        parent.addView(hint, params);
    }

    private void setEditingEnabled(boolean enabled) {
        processButton.setEnabled(enabled && !selected.isEmpty());
        chooseButton.setEnabled(enabled);
        setGroupEnabled(operationGroup, enabled);
        setGroupEnabled(qualityGroup, enabled);
        setGroupEnabled(directionGroup, enabled);
        setGroupEnabled(saveModeGroup, enabled);
        setGroupEnabled(overwriteTimestampGroup, enabled);
    }

    private void stylePageHeader(LinearLayout header) {
        header.setBackgroundColor(brandColor);
        header.setPadding(dp(24), dp(22), dp(24), dp(24));
        header.setMinimumHeight(dp(112));
    }

    // Own both system-bar insets so Android 15's edge-to-edge layout keeps the
    // blue header and footer behind system icons without covering any controls.
    private View createScreen(LinearLayout header, LinearLayout content) {
        return createScreen(header, content, null);
    }

    private View createScreen(LinearLayout header, LinearLayout content, @Nullable View footer) {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(brandColor);
        screen.addView(header, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_SURFACE);
        scroll.setClipToPadding(false);
        scroll.addView(content);
        screen.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        if (footer != null) screen.addView(footer, matchWrap());
        ViewCompat.setOnApplyWindowInsetsListener(screen, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, dp(12)));
            return WindowInsetsCompat.CONSUMED;
        });
        return screen;
    }

    private void styleActionButton(Button button, boolean primary) {
        int enabledBackground = primary ? brandColor : 0xFFE0E9FA;
        button.setBackgroundTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_enabled}, new int[]{}},
                new int[]{enabledBackground, 0xFFE2E7EF}));
        button.setTextColor(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_enabled}, new int[]{}},
                new int[]{primary ? 0xFFFFFFFF : brandColor, 0xFF8995A7}));
        button.setMinHeight(dp(52));
        button.setAllCaps(false);
    }

    private SharedPreferences defaultPreferences() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    private void showSettingsScreen() {
        if (processing) return;
        currentPage = PAGE_SETTINGS;
        settingsScreen = createSettingsScreen();
        setContentView(settingsScreen);
    }

    private void showMainScreen() {
        currentPage = PAGE_MAIN;
        setContentView(mainScreen);
    }

    private LinearLayout createPageRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(4), dp(20), dp(24));
        root.setGravity(Gravity.TOP);
        root.setBackgroundColor(COLOR_SURFACE);
        return root;
    }

    private LinearLayout createPageHeader(String title, Runnable onBack) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        stylePageHeader(header);

        TextView backButton = new TextView(this);
        backButton.setText("‹");
        backButton.setTextSize(32);
        backButton.setTextColor(0xFFFFFFFF);
        backButton.setGravity(Gravity.CENTER);
        backButton.setMinWidth(dp(48));
        backButton.setMinHeight(dp(48));
        backButton.setContentDescription("返回");
        backButton.setOnClickListener(v -> onBack.run());
        header.addView(backButton, wrapWrap());

        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextColor(0xFFFFFFFF);
        heading.setTextSize(24);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams headingParams = wrapWrap();
        headingParams.leftMargin = dp(8);
        header.addView(heading, headingParams);
        return header;
    }

    private View createSettingsScreen() {
        LinearLayout root = createPageRoot();
        LinearLayout header = createPageHeader("设置", this::showMainScreen);

        addSectionTitle(root, "默认设置", dp(22));
        TextView hint = new TextView(this);
        hint.setText("这些选项会作为主界面的默认值；每次处理前仍可单独调整。旋转和压缩共用保存设置。");
        hint.setTextColor(COLOR_MUTED);
        hint.setTextSize(14);
        LinearLayout.LayoutParams hintParams = matchWrap();
        hintParams.bottomMargin = dp(10);
        root.addView(hint, hintParams);

        SharedPreferences preferences = defaultPreferences();
        RadioGroup rotationDefaults = new RadioGroup(this);
        rotationDefaults.setOrientation(RadioGroup.VERTICAL);
        int rotation = preferences.getInt(PREF_DEFAULT_ROTATION, -90);
        addRadio(rotationDefaults, "逆时针90度", -90, rotation == -90);
        addRadio(rotationDefaults, "顺时针90度", 90, rotation == 90);
        addRadio(rotationDefaults, "180度", 180, rotation == 180);
        addSettingsGroup(root, "旋转设置", rotationDefaults);
        rotationDefaults.setOnCheckedChangeListener((group, checkedId) ->
                preferences.edit().putInt(PREF_DEFAULT_ROTATION, selectedInt(rotationDefaults)).apply());

        RadioGroup qualityDefaults = new RadioGroup(this);
        qualityDefaults.setOrientation(RadioGroup.VERTICAL);
        addCompressionChoices(qualityDefaults, preferences.getInt(PREF_DEFAULT_QUALITY, 85));
        addSettingsGroup(root, "压缩强度", qualityDefaults);
        qualityDefaults.setOnCheckedChangeListener((group, checkedId) ->
                preferences.edit().putInt(PREF_DEFAULT_QUALITY, selectedInt(qualityDefaults)).apply());

        RadioGroup saveModeDefaults = new RadioGroup(this);
        saveModeDefaults.setOrientation(RadioGroup.VERTICAL);
        int saveMode = preferences.getInt(PREF_DEFAULT_SAVE_MODE, 1);
        addRadio(saveModeDefaults, "覆盖", 1, saveMode == 1);
        addRadio(saveModeDefaults, "另存新图", 0, saveMode == 0);
        addSettingsGroup(root, "保存方式（旋转、压缩共用）", saveModeDefaults);
        saveModeDefaults.setOnCheckedChangeListener((group, checkedId) ->
                preferences.edit().putInt(PREF_DEFAULT_SAVE_MODE, selectedInt(saveModeDefaults)).apply());

        RadioGroup timestampDefaults = new RadioGroup(this);
        timestampDefaults.setOrientation(RadioGroup.VERTICAL);
        int timestamp = preferences.getInt(PREF_DEFAULT_TIMESTAMP, 0);
        addRadio(timestampDefaults, "保持原修改时间", 0, timestamp == 0);
        addRadio(timestampDefaults, "更新为当前时间", 1, timestamp == 1);
        addSettingsGroup(root, "覆盖后的修改时间（不改变拍摄时间）", timestampDefaults);
        timestampDefaults.setOnCheckedChangeListener((group, checkedId) ->
                preferences.edit().putInt(PREF_DEFAULT_TIMESTAMP, selectedInt(timestampDefaults)).apply());

        if (Build.VERSION.SDK_INT >= 31) {
            addSectionTitle(root, "照片修改权限", dp(24));
            LinearLayout permissionCard = optionCard();
            TextView permissionRow = new TextView(this);
            permissionRow.setText(MediaStore.canManageMedia(this)
                    ? "免确认修改照片：已开启\n点击管理系统授权"
                    : "免确认修改照片：未开启\n开启系统的媒体管理授权后，覆盖照片无需逐次确认");
            permissionRow.setTextColor(COLOR_TEXT);
            permissionRow.setTextSize(15);
            permissionRow.setPadding(dp(4), dp(12), dp(4), dp(12));
            permissionRow.setOnClickListener(v -> openMediaManagementSettings(false));
            permissionCard.addView(permissionRow, matchWrap());
            root.addView(permissionCard, matchWrap());
        }

        addSectionTitle(root, "关于", dp(24));
        LinearLayout aboutCard = optionCard();
        TextView aboutRow = new TextView(this);
        aboutRow.setText("关于 Photo Rotator     ›");
        aboutRow.setTextColor(COLOR_TEXT);
        aboutRow.setTextSize(16);
        aboutRow.setGravity(Gravity.CENTER_VERTICAL);
        aboutRow.setMinHeight(dp(52));
        aboutRow.setOnClickListener(v -> showAboutScreen());
        aboutCard.addView(aboutRow, matchWrap());
        root.addView(aboutCard, matchWrap());

        return createScreen(header, root);
    }

    private void addSettingsGroup(LinearLayout root, String title, RadioGroup options) {
        addSectionTitle(root, title, dp(12));
        LinearLayout card = optionCard();
        card.addView(options, matchWrap());
        root.addView(card, matchWrap());
    }

    private void showAboutScreen() {
        currentPage = PAGE_ABOUT;
        LinearLayout root = createPageRoot();
        LinearLayout header = createPageHeader("关于", this::showSettingsScreen);

        LinearLayout card = optionCard();
        TextView appName = new TextView(this);
        appName.setText("Photo Rotator");
        appName.setTextColor(COLOR_TEXT);
        appName.setTextSize(20);
        appName.setTypeface(null, android.graphics.Typeface.BOLD);
        appName.setPadding(dp(8), dp(10), dp(8), dp(8));
        card.addView(appName, matchWrap());

        TextView version = new TextView(this);
        version.setText("版本 " + appVersionLabel());
        version.setTextColor(COLOR_MUTED);
        version.setTextSize(15);
        version.setPadding(dp(8), dp(6), dp(8), dp(8));
        card.addView(version, matchWrap());

        TextView attribution = new TextView(this);
        attribution.setText("Powered by Stone Wang");
        attribution.setTextColor(COLOR_MUTED);
        attribution.setTextSize(14);
        attribution.setPadding(dp(8), dp(6), dp(8), dp(10));
        card.addView(attribution, matchWrap());
        LinearLayout.LayoutParams cardParams = matchWrap();
        cardParams.topMargin = dp(24);
        root.addView(card, cardParams);
        setContentView(createScreen(header, root));
    }

    private void addRadio(RadioGroup group, String label, int value, boolean checked) {
        RadioButton radio = new RadioButton(this);
        radio.setId(View.generateViewId());
        radio.setText(label);
        radio.setTextColor(COLOR_TEXT);
        radio.setButtonTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{brandColor, COLOR_MUTED}));
        radio.setTag(value);
        radio.setMinHeight(dp(48));
        RadioGroup.LayoutParams params = group.getOrientation() == RadioGroup.HORIZONTAL
                ? new RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                : new RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        group.addView(radio, params);
        if (checked) group.check(radio.getId());
    }

    private String appVersionLabel() {
        String suffix = BuildConfig.DEBUG && BuildConfig.GIT_DIRTY ? "-dirty" : "";
        return appVersionName() + " (" + BuildConfig.GIT_COMMIT + suffix + ")";
    }

    private String appVersionName() {
        try {
            String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return versionName == null ? "" : versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private void addSectionTitle(LinearLayout root, String label, int topMargin) {
        TextView header = new TextView(this);
        header.setText(label);
        header.setTextColor(brandColor);
        header.setTextSize(16);
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = topMargin;
        params.bottomMargin = dp(8);
        root.addView(header, params);
    }

    private LinearLayout optionCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFFFFFFFF);
        background.setCornerRadius(dp(20));
        card.setBackground(background);
        return card;
    }

    private void openNativePhotoPicker() {
        boolean systemPickerAvailable = supportsSystemPhotoPicker();
        int systemLimit = systemPickerAvailable ? MediaStore.getPickImagesMaxLimit() : MAX_SELECTED_PHOTOS;
        boolean canPreselect = systemLimit >= 2 && supportsPickerPreselection() && selectedUrisFromPhotoPicker
                && selected.size() <= systemLimit;
        if (selected.size() == MAX_SELECTED_PHOTOS && !canPreselect) {
            statusText.setText("最多选择 50 张照片；请先删除已选照片，再添加新照片。");
            return;
        }
        if (systemPickerAvailable) {
            int pickerLimit = Math.min(MAX_SELECTED_PHOTOS, systemLimit);
            if (!canPreselect) pickerLimit = Math.min(pickerLimit, MAX_SELECTED_PHOTOS - selected.size());
            Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
            intent.setType("image/*");
            if (pickerLimit >= 2) intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, pickerLimit);
            if (canPreselect && !selected.isEmpty()) {
                intent.putParcelableArrayListExtra(EXTRA_PICKER_PRE_SELECTION_URIS,
                        new ArrayList<>(selected));
            }
            try {
                launchedPhotoPicker = true;
                pickerResultReplacesSelection = canPreselect;
                startActivityForResult(intent, PICK_NATIVE_PHOTOS);
                return;
            } catch (ActivityNotFoundException | IllegalArgumentException e) {
                Log.w("PhotoRotator", "System photo picker unavailable", e);
            }
        }
        Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
        fallback.setType("image/*");
        fallback.addCategory(Intent.CATEGORY_OPENABLE);
        fallback.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            launchedPhotoPicker = false;
            pickerResultReplacesSelection = false;
            startActivityForResult(fallback, PICK_NATIVE_PHOTOS);
        } catch (ActivityNotFoundException e) {
            statusText.setText("无法打开系统相册。");
        }
    }

    private boolean supportsSystemPhotoPicker() {
        return Build.VERSION.SDK_INT >= 33
                || SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2;
    }

    private boolean supportsPickerPreselection() {
        return Build.VERSION.SDK_INT >= 36
                || SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 15;
    }

    private void updateSelection() {
        updatedPhotoSources.keySet().retainAll(selected);
        statusText.setTextColor(COLOR_MUTED);
        selectionText.setText(selected.isEmpty() ? "尚未选择照片" : "已选择 " + selected.size() + " 张照片");
        processButton.setEnabled(!selected.isEmpty());
        if (selected.size() <= 8) previewExpanded = false;
        showSelectionPreview();
        statusText.setText("");
    }

    private void showSelectionPreview() {
        int generation = ++previewGeneration;
        previewGrid.removeAllViews();
        untickAllButton.setVisibility(!processing && !selected.isEmpty()
                ? View.VISIBLE : View.GONE);
        collapsePreviewButton.setVisibility(previewExpanded && selected.size() > 8
                ? View.VISIBLE : View.GONE);
        if (selected.isEmpty()) {
            previewGrid.setVisibility(View.GONE);
            return;
        }
        previewGrid.setVisibility(View.VISIBLE);
        int gap = dp(8);
        int availableWidth = selectionRow.getWidth();
        if (availableWidth == 0) availableWidth = getResources().getDisplayMetrics().widthPixels - dp(48);
        int tileSize = Math.max(1, Math.min(dp(88), (availableWidth - 3 * gap) / 4));
        int visiblePhotos = previewExpanded ? selected.size()
                : Math.min(selected.size(), selected.size() > 8 ? 7 : 8);
        for (int i = 0; i < visiblePhotos; i++) {
            addPhotoPreview(selected.get(i), i, tileSize, gap, generation);
        }
        if (!previewExpanded && selected.size() > 8) {
            FrameLayout moreTile = previewTile(tileSize, gap, 7);
            GradientDrawable moreBackground = new GradientDrawable();
            moreBackground.setColor(brandColor);
            moreBackground.setCornerRadius(dp(10));
            moreTile.setBackground(moreBackground);
            TextView more = new TextView(this);
            more.setText("+" + (selected.size() - 7));
            more.setTextColor(0xFFFFFFFF);
            more.setTextSize(22);
            more.setTypeface(null, android.graphics.Typeface.BOLD);
            more.setGravity(Gravity.CENTER);
            moreTile.addView(more, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            moreTile.setContentDescription("另有 " + (selected.size() - 7) + " 张已选择照片");
            moreTile.setClickable(true);
            moreTile.setFocusable(true);
            moreTile.setOnClickListener(v -> {
                previewExpanded = true;
                showSelectionPreview();
            });
            previewGrid.addView(moreTile);
        }
    }

    private void addPhotoPreview(Uri uri, int index, int tileSize, int gap, int generation) {
        FrameLayout tile = previewTile(tileSize, gap, index);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        tile.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tile.setContentDescription("第 " + (index + 1) + " 张所选照片预览");

        FrameLayout removeTouchTarget = new FrameLayout(this);
        removeTouchTarget.setContentDescription("移除第 " + (index + 1) + " 张所选照片");
        TextView remove = new TextView(this);
        remove.setText("×");
        remove.setTextSize(11);
        remove.setTextColor(COLOR_TEXT);
        remove.setGravity(Gravity.CENTER);
        GradientDrawable removeBackground = new GradientDrawable();
        removeBackground.setColor(0xEFFFFFFF);
        removeBackground.setCornerRadius(dp(9));
        remove.setBackground(removeBackground);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                dp(18), dp(18), Gravity.TOP | Gravity.END);
        iconParams.topMargin = dp(2);
        iconParams.rightMargin = dp(2);
        removeTouchTarget.addView(remove, iconParams);
        FrameLayout.LayoutParams removeParams = new FrameLayout.LayoutParams(
                dp(36), dp(36), Gravity.TOP | Gravity.END);
        tile.addView(removeTouchTarget, removeParams);
        removeTouchTarget.setVisibility(processing ? View.GONE : View.VISIBLE);
        removeTouchTarget.setOnClickListener(v -> {
            if (processing) return;
            selected.remove(uri);
            updateSelection();
        });
        previewGrid.addView(tile);

        String key = uri.toString();
        Uri updatedSource = updatedPhotoSources.get(uri);
        Bitmap cached = thumbnailCache.get(key);
        if (cached != null) {
            image.setImageBitmap(cached);
            return;
        }
        previewWorker.execute(() -> {
            if (generation != previewGeneration) return;
            try {
                Bitmap thumbnail = updatedSource != null
                        ? loadFreshThumbnail(getContentResolver(), updatedSource, tileSize)
                        : getContentResolver().loadThumbnail(uri, new Size(tileSize, tileSize), null);
                if (thumbnail == null) return;
                runOnUiThread(() -> {
                    if (generation == previewGeneration) {
                        thumbnailCache.put(key, thumbnail);
                        image.setImageBitmap(thumbnail);
                    } else {
                        thumbnail.recycle();
                    }
                });
            } catch (Exception e) {
                Log.w("PhotoRotator", "Could not load selected photo thumbnail", e);
            }
        });
    }

    static Bitmap loadFreshThumbnail(ContentResolver resolver, Uri source, int size) throws Exception {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream input = resolver.openInputStream(source)) {
            if (input == null) throw new IllegalStateException("无法读取最新照片预览");
            BitmapFactory.decodeStream(input, null, options);
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw new IllegalStateException("无法解码最新照片预览");
        }
        options.inSampleSize = 1;
        while (Math.min(options.outWidth, options.outHeight) / (options.inSampleSize * 2) >= size) {
            options.inSampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        Bitmap decoded;
        try (InputStream input = resolver.openInputStream(source)) {
            if (input == null) throw new IllegalStateException("无法读取最新照片预览");
            decoded = BitmapFactory.decodeStream(input, null, options);
        }
        if (decoded == null) throw new IllegalStateException("无法解码最新照片预览");
        float scale = Math.min(1f, (float) size / Math.min(decoded.getWidth(), decoded.getHeight()));
        Bitmap thumbnail = Bitmap.createScaledBitmap(decoded,
                Math.max(1, Math.round(decoded.getWidth() * scale)),
                Math.max(1, Math.round(decoded.getHeight() * scale)), true);
        if (thumbnail != decoded) decoded.recycle();
        return thumbnail;
    }

    private FrameLayout previewTile(int size, int gap, int index) {
        FrameLayout tile = new FrameLayout(this);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFFE2EAF6);
        background.setCornerRadius(dp(10));
        tile.setBackground(background);
        tile.setClipToOutline(true);
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = size;
        params.height = size;
        params.rightMargin = index % 4 == 3 ? 0 : gap;
        params.bottomMargin = gap;
        tile.setLayoutParams(params);
        return tile;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MEDIA_MANAGEMENT) {
            boolean resumeProcessing = resumeProcessingAfterMediaManagement;
            resumeProcessingAfterMediaManagement = false;
            if (resumeProcessing && pendingItems != null) {
                requestOverwritePermission(pendingItems, false);
            } else if (currentPage == PAGE_SETTINGS) {
                showSettingsScreen();
            }
            return;
        }
        if (requestCode == PICK_NATIVE_PHOTOS) {
            if (resultCode != RESULT_OK || data == null) return;
            boolean wasEmpty = selected.isEmpty();
            LinkedHashSet<Uri> updated = pickerResultReplacesSelection
                    ? new LinkedHashSet<>() : new LinkedHashSet<>(selected);
            int ignored = 0;
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    Uri uri = data.getClipData().getItemAt(i).getUri();
                    if (!updated.contains(uri)) {
                        if (updated.size() < MAX_SELECTED_PHOTOS) updated.add(uri);
                        else ignored++;
                    }
                }
            } else if (data.getData() != null) {
                Uri uri = data.getData();
                if (!updated.contains(uri)) {
                    if (updated.size() < MAX_SELECTED_PHOTOS) updated.add(uri);
                    else ignored++;
                }
            }
            selected.clear();
            selected.addAll(updated);
            selectedUrisFromPhotoPicker = selected.isEmpty() ||
                    (launchedPhotoPicker && (pickerResultReplacesSelection
                            || wasEmpty || selectedUrisFromPhotoPicker));
            updateSelection();
            if (ignored > 0) statusText.setText("最多选择 50 张照片，另有 " + ignored + " 张未加入。");
            return;
        }
        if (requestCode == REQUEST_ORIGINAL_PHOTOS) {
            if (resultCode == RESULT_OK) continueAfterReadPermission();
            else {
                restoreControls();
                statusText.setText("需要照片读取和位置信息权限才能保留 EXIF。");
            }
            return;
        }
        if (requestCode == REQUEST_OVERWRITE_PERMISSION) {
            if (resultCode == RESULT_OK && pendingItems != null) {
                processSelection(pendingItems, pendingDegrees, true,
                        pendingUpdateTimestamp);
            } else {
                restoreControls();
                statusText.setText("未获准覆盖照片，原图没有修改。");
            }
        }
    }

    private void beginProcessing() {
        if (processing || selected.isEmpty()) return;
        pendingItems = new ArrayList<>(selected);
        pendingCompression = isCompressionMode();
        pendingQuality = selectedInt(qualityGroup);
        pendingUpdateTimestamp = selectedInt(overwriteTimestampGroup) == 1;
        processing = true;
        setEditingEnabled(false);
        showSelectionPreview();
        pendingDegrees = selectedInt(directionGroup);
        pendingOverwrite = selectedInt(saveModeGroup) == 1;
        boolean needsOriginalMediaAccess = pendingOverwrite;
        for (Uri uri : selected) {
            String authority = uri.getAuthority();
            if ("media".equals(authority)
                    || "com.android.providers.media.documents".equals(authority)
                    || "com.android.externalstorage.documents".equals(authority)
                    || (authority != null && authority.endsWith("@media"))) {
                needsOriginalMediaAccess = true;
                break;
            }
        }
        String readPermission = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (needsOriginalMediaAccess
                && (checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(readPermission) != PackageManager.PERMISSION_GRANTED)) {
            requestPermissions(new String[]{readPermission, Manifest.permission.ACCESS_MEDIA_LOCATION},
                    REQUEST_ORIGINAL_PHOTOS);
            return;
        }
        continueAfterReadPermission();
    }

    private int selectedInt(RadioGroup group) {
        RadioButton selectedRadio = group.findViewById(group.getCheckedRadioButtonId());
        return (Integer) selectedRadio.getTag();
    }

    private void continueAfterReadPermission() {
        ArrayList<Uri> items = pendingItems == null ? new ArrayList<>(selected) : new ArrayList<>(pendingItems);
        if (pendingOverwrite) requestOverwritePermission(items);
        else processSelection(items, pendingDegrees, false, false);
    }

    private void requestOverwritePermission(ArrayList<Uri> pickerItems) {
        requestOverwritePermission(pickerItems, true);
    }

    private void openMediaManagementSettings(boolean resumeProcessing) {
        if (Build.VERSION.SDK_INT < 31) return;
        resumeProcessingAfterMediaManagement = resumeProcessing;
        defaultPreferences().edit().putBoolean(PREF_MEDIA_MANAGEMENT_OFFERED, true).apply();
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_MEDIA_MANAGEMENT);
        } catch (ActivityNotFoundException | SecurityException e) {
            resumeProcessingAfterMediaManagement = false;
            if (resumeProcessing && pendingItems != null) requestOverwritePermission(pendingItems, false);
            else Toast.makeText(this, "系统未提供媒体管理授权入口", Toast.LENGTH_SHORT).show();
        }
    }

    static boolean canWritePhoto(Context context, Uri uri) {
        if (context.checkUriPermission(uri, Process.myPid(), Process.myUid(),
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED) return true;
        try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "rw")) {
            return descriptor != null;
        } catch (java.io.IOException | SecurityException e) {
            return false;
        }
    }

    private void requestOverwritePermission(ArrayList<Uri> pickerItems, boolean requestMediaManagement) {
        ArrayList<Uri> sources = new ArrayList<>();
        for (Uri uri : pickerItems) sources.add(updatedPhotoSources.getOrDefault(uri, uri));
        processing = true;
        setEditingEnabled(false);
        showSelectionPreview();
        progress.setVisibility(View.VISIBLE);
        progress.setMax(pickerItems.size());
        progress.setProgress(0);
        statusText.setTextColor(COLOR_MUTED);
        statusText.setText("正在确认分享照片与本机原图…");
        worker.execute(() -> {
            try {
                ArrayList<Uri> mediaItems = new ArrayList<>();
                for (int i = 0; i < pickerItems.size(); i++) {
                    Uri mediaUri = ExifRotation.resolveForOverwrite(this, sources.get(i));
                    if (!mediaItems.contains(mediaUri) && !canWritePhoto(this, mediaUri)) mediaItems.add(mediaUri);
                    int checked = i + 1;
                    runOnUiThread(() -> progress.setProgress(checked));
                }
                runOnUiThread(() -> {
                    try {
                        pendingItems = pickerItems;
                        if (mediaItems.isEmpty()) {
                            processSelection(pickerItems, pendingDegrees, true,
                                    pendingUpdateTimestamp);
                            return;
                        }
                        if (requestMediaManagement && Build.VERSION.SDK_INT >= 31
                                && !defaultPreferences().getBoolean(PREF_MEDIA_MANAGEMENT_OFFERED, false)
                                && !MediaStore.canManageMedia(this)) {
                            progress.setVisibility(View.GONE);
                            statusText.setText("开启系统的媒体管理授权后，覆盖照片无需再次确认。返回后继续处理。");
                            Toast.makeText(this, "开启媒体管理授权，之后覆盖无需再次确认", Toast.LENGTH_LONG).show();
                            openMediaManagementSettings(true);
                            return;
                        }
                        PendingIntent request = MediaStore.createWriteRequest(getContentResolver(), mediaItems);
                        progress.setVisibility(View.GONE);
                        statusText.setText(Build.VERSION.SDK_INT >= 31 && MediaStore.canManageMedia(this)
                                ? "正在准备处理…" : "等待系统授权修改照片…");
                        startIntentSenderForResult(request.getIntentSender(), REQUEST_OVERWRITE_PERMISSION,
                                null, 0, 0, 0);
                    } catch (IntentSender.SendIntentException | RuntimeException e) {
                        showOverwritePreparationError(e);
                    }
                });
            } catch (RuntimeException e) {
                runOnUiThread(() -> showOverwritePreparationError(e));
            }
        });
    }

    private void showOverwritePreparationError(Exception e) {
        Log.e("PhotoRotator", "Could not request overwrite access", e);
        pendingItems = null;
        restoreControls();
        statusText.setText("无法请求覆盖权限：" + (e.getMessage() == null ? "请重试" : e.getMessage()));
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_ORIGINAL_PHOTOS) return;
        if (grantResults.length == 2 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
            continueAfterReadPermission();
        } else {
            restoreControls();
            statusText.setText("需要照片读取和位置信息权限才能保留 EXIF。");
        }
    }

    private void processSelection(List<Uri> items, int degrees, boolean overwrite,
                                  boolean updateTimestamp) {
        ArrayList<Uri> sources = new ArrayList<>();
        for (Uri uri : items) sources.add(updatedPhotoSources.getOrDefault(uri, uri));
        processing = true;
        previewExpanded = false;
        showSelectionPreview();
        setEditingEnabled(false);
        progress.setVisibility(View.VISIBLE);
        progress.setMax(items.size());
        progress.setProgress(0);
        statusText.setText("准备处理 " + items.size() + " 张照片…");
        statusText.setTextColor(COLOR_MUTED);
        final boolean compress = pendingCompression;
        final int quality = pendingQuality;
        worker.execute(() -> {
            int success = 0;
            int skipped = 0;
            long beforeBytes = 0;
            long afterBytes = 0;
            List<String> failures = new ArrayList<>();
            List<String> skipDetails = new ArrayList<>();
            Map<Uri, Uri> overwrittenSources = new HashMap<>();
            for (int i = 0; i < items.size(); i++) {
                try {
                    if (compress) {
                        ExifRotation.CompressionResult result = ExifRotation.compress(this, sources.get(i),
                                quality, overwrite, updateTimestamp);
                        if (result.skipReason != null) {
                            skipped++;
                            skipDetails.add((i + 1) + "：" + result.skipReason);
                        } else {
                            if (overwrite) overwrittenSources.put(items.get(i), result.destination);
                            success++;
                        }
                        beforeBytes += result.originalBytes;
                        afterBytes += result.compressedBytes;
                    } else {
                        if (overwrite) {
                            Uri updatedSource = ExifRotation.overwrite(this, sources.get(i), degrees, updateTimestamp);
                            overwrittenSources.put(items.get(i), updatedSource);
                        } else ExifRotation.saveCopy(this, sources.get(i), degrees);
                        success++;
                    }
                } catch (Exception | OutOfMemoryError e) {
                    Log.e("PhotoRotator", "Failed to process photo " + (i + 1), e);
                    String message = e instanceof OutOfMemoryError ? "内存不足，无法保持原尺寸处理"
                            : e.getMessage() == null ? "处理失败" : e.getMessage();
                    failures.add((i + 1) + "：" + message);
                }
                int done = i + 1;
                int completed = success;
                int ignored = skipped;
                runOnUiThread(() -> {
                    progress.setProgress(done);
                    statusText.setText("处理中 " + done + "/" + items.size() + "（成功 " + completed
                            + (compress ? "，跳过 " + ignored : "") + "）");
                });
            }
            int completed = success;
            int ignored = skipped;
            long originalSize = beforeBytes;
            long compressedSize = afterBytes;
            runOnUiThread(() -> {
                updatedPhotoSources.putAll(overwrittenSources);
                for (Uri uri : overwrittenSources.keySet()) thumbnailCache.remove(uri.toString());
                restoreControls();
                String summary = compress
                        ? "压缩完成：成功 " + completed + " 张，跳过 " + ignored + " 张，失败 " + failures.size() + " 张"
                        : failures.isEmpty()
                        ? "旋转成功，已" + (overwrite ? "覆盖 " : "另存 ") + completed + " 张照片"
                        : "处理完成：成功 " + completed + " 张，失败 " + failures.size() + " 张";
                String result = summary;
                if (compress && originalSize > 0) {
                    result += "\n体积合计：" + formatBytes(originalSize) + " → " + formatBytes(compressedSize)
                            + "，节省 " + formatBytes(originalSize - compressedSize)
                            + "（" + Math.round(100.0 * (originalSize - compressedSize) / originalSize) + "%）";
                }
                if (completed > 0) result += overwrite
                        ? "\n原照片已覆盖，常用 EXIF 信息已保留。"
                        : "\n处理后的照片已另存到 Pictures/PhotoRotator。";
                if (!skipDetails.isEmpty()) result += "\n跳过（原图未修改）：\n" + String.join("\n", skipDetails);
                if (!failures.isEmpty()) result += "\n失败：\n" + String.join("\n", failures);
                statusText.setText(result);
                statusText.setTextColor(failures.isEmpty() ? 0xFF2E7D32
                        : completed > 0 || ignored > 0 ? 0xFF9C6500 : 0xFFBA1A1A);
                Toast.makeText(this, summary, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private String formatBytes(long bytes) {
        return android.text.format.Formatter.formatShortFileSize(this, bytes);
    }

    private void restoreControls() {
        processing = false;
        progress.setVisibility(View.GONE);
        pendingItems = null;
        setEditingEnabled(true);
        showSelectionPreview();
    }

    private void setGroupEnabled(ViewGroup group, boolean enabled) {
        group.setEnabled(enabled);
        for (int i = 0; i < group.getChildCount(); i++) group.getChildAt(i).setEnabled(enabled);
    }

    static Bitmap applyExifOrientation(Bitmap source, int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: matrix.setRotate(90); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: matrix.setRotate(-90); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(-90); break;
            default: return source;
        }
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    @Override protected void onDestroy() {
        previewGeneration++;
        previewWorker.shutdownNow();
        thumbnailCache.evictAll();
        worker.shutdown();
        super.onDestroy();
    }
}
