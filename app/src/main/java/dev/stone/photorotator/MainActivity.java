package dev.stone.photorotator;

import android.Manifest;
import android.content.Intent;
import android.content.ContentValues;
import android.content.ActivityNotFoundException;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.MediaStore;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.exifinterface.media.ExifInterface;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int PICK_NATIVE_PHOTOS = 12;
    private static final int REQUEST_ORIGINAL_PHOTOS = 13;
    private Button chooseButton;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<Uri> selected = new ArrayList<>();
    private TextView selectionText;
    private TextView statusText;
    private Button rotateButton;
    private Button exifRotateButton;
    private ProgressBar progress;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildScreen();
    }

    private void buildScreen() {
        int pad = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(32), pad, pad);
        root.setGravity(Gravity.TOP);
        root.setBackgroundColor(0xFFF7F5FA);

        TextView title = new TextView(this);
        title.setText("照片逆时针旋转");
        title.setTextColor(0xFF1D1B20);
        title.setTextSize(26);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, matchWrap());

        TextView hint = new TextView(this);
        hint.setText("从系统相册选择照片，可一次选择多张。旋转后的副本保存到 Pictures/PhotoRotator，原图保留。保留 EXIF 模式需要照片读取和位置信息权限；只修改方向标记，不重新压缩画质。");
        hint.setTextColor(0xFF625F67);
        hint.setTextSize(15);
        LinearLayout.LayoutParams hintParams = matchWrap();
        hintParams.topMargin = dp(14);
        root.addView(hint, hintParams);

        chooseButton = new Button(this);
        chooseButton.setText("选择照片");
        LinearLayout.LayoutParams buttonParams = matchWrap();
        buttonParams.topMargin = dp(24);
        root.addView(chooseButton, buttonParams);
        chooseButton.setOnClickListener(v -> openNativePhotoPicker());

        selectionText = new TextView(this);
        selectionText.setText("尚未选择照片");
        selectionText.setTextColor(0xFF1D1B20);
        selectionText.setTextSize(16);
        LinearLayout.LayoutParams selectionParams = matchWrap();
        selectionParams.topMargin = dp(12);
        root.addView(selectionText, selectionParams);

        rotateButton = new Button(this);
        rotateButton.setText("逆时针旋转并保存副本");
        rotateButton.setEnabled(false);
        LinearLayout.LayoutParams rotateParams = matchWrap();
        rotateParams.topMargin = dp(12);
        root.addView(rotateButton, rotateParams);
        rotateButton.setOnClickListener(v -> rotateSelected(false));

        exifRotateButton = new Button(this);
        exifRotateButton.setText("逆时针旋转（保留 EXIF）");
        exifRotateButton.setEnabled(false);
        LinearLayout.LayoutParams exifParams = matchWrap();
        exifParams.topMargin = dp(8);
        root.addView(exifRotateButton, exifParams);
        exifRotateButton.setOnClickListener(v -> rotatePreservingExif());

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setVisibility(ProgressBar.GONE);
        LinearLayout.LayoutParams progressParams = matchWrap();
        progressParams.topMargin = dp(20);
        root.addView(progress, progressParams);

        statusText = new TextView(this);
        statusText.setTextColor(0xFF625F67);
        statusText.setTextSize(14);
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(12);
        root.addView(statusText, statusParams);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void openNativePhotoPicker() {
        // On the target vivo device this resolves to the system photo picker.
        // Its URI can provide original EXIF after ACCESS_MEDIA_LOCATION is granted.
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, PICK_NATIVE_PHOTOS);
        } catch (ActivityNotFoundException e) {
            statusText.setText("无法打开系统相册。");
        }
    }

    private void updateSelection() {
        selectionText.setText(selected.isEmpty() ? "尚未选择照片" : "已选择 " + selected.size() + " 张照片");
        rotateButton.setEnabled(!selected.isEmpty());
        exifRotateButton.setEnabled(!selected.isEmpty());
        statusText.setText("");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_NATIVE_PHOTOS || resultCode != RESULT_OK || data == null) return;
        selected.clear();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                Uri uri = data.getClipData().getItemAt(i).getUri();
                if (!selected.contains(uri)) selected.add(uri);
            }
        } else if (data.getData() != null) {
            selected.add(data.getData());
        }
        updateSelection();
    }

    private void rotatePreservingExif() {
        if (selected.isEmpty()) return;
        String readPermission = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(readPermission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{readPermission, Manifest.permission.ACCESS_MEDIA_LOCATION},
                    REQUEST_ORIGINAL_PHOTOS);
            return;
        }
        rotateSelected(true);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_ORIGINAL_PHOTOS) return;
        if (grantResults.length == 2 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
            rotateSelected(true);
        } else {
            statusText.setText("保留地址和拍照设备信息需要允许读取照片及其位置信息；未生成副本。");
        }
    }

    private void rotateSelected(boolean preserveExif) {
        if (selected.isEmpty()) return;
        List<Uri> items = new ArrayList<>(selected);
        rotateButton.setEnabled(false);
        exifRotateButton.setEnabled(false);
        chooseButton.setEnabled(false);
        progress.setVisibility(ProgressBar.VISIBLE);
        progress.setMax(items.size());
        progress.setProgress(0);
        statusText.setText("准备处理 " + items.size() + " 张照片…");
        worker.execute(() -> {
            int success = 0;
            List<String> failures = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                Uri uri = items.get(i);
                try {
                    if (preserveExif) ExifRotation.saveCopy(this, uri);
                    else rotateAndSave(uri);
                    success++;
                } catch (Exception e) {
                    failures.add((i + 1) + "：" + (e.getMessage() == null ? "无法保存副本" : e.getMessage()));
                }
                final int done = i + 1;
                final int completed = success;
                runOnUiThread(() -> {
                    progress.setProgress(done);
                    statusText.setText("处理中 " + done + "/" + items.size() + "（成功 " + completed + "）");
                });
            }
            final int completed = success;
            final List<String> errors = failures;
            runOnUiThread(() -> {
                progress.setVisibility(ProgressBar.GONE);
                rotateButton.setEnabled(!selected.isEmpty());
                exifRotateButton.setEnabled(!selected.isEmpty());
                chooseButton.setEnabled(true);
                String result = "完成：成功 " + completed + " 张，失败 " + errors.size() + " 张。";
                if (completed > 0) result += "\n副本已保存到 Pictures/PhotoRotator，原图未修改。";
                if (preserveExif && completed > 0) result += "\n照片像素未重新编码；部分应用可能不识别方向标记。";
                if (!errors.isEmpty()) result += "\n" + String.join("\n", errors);
                statusText.setText(result);
            });
        });
    }

    private void rotateAndSave(Uri uri) throws Exception {
        Bitmap source;
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalStateException("无法读取照片");
            try {
                orientation = new ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            } catch (Exception ignored) { }
        }
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalStateException("无法读取照片");
            source = BitmapFactory.decodeStream(input);
        }
        if (source == null) throw new IllegalArgumentException("不支持的图片格式");
        Bitmap oriented = applyExifOrientation(source, orientation);
        if (oriented != source) source.recycle();
        Matrix rotation = new Matrix();
        rotation.postRotate(-90f);
        Bitmap rotated = Bitmap.createBitmap(oriented, 0, 0, oriented.getWidth(), oriented.getHeight(), rotation, true);
        if (rotated != oriented) oriented.recycle();

        String type = getContentResolver().getType(uri);
        Bitmap.CompressFormat format = Bitmap.CompressFormat.JPEG;
        if (type != null && type.equalsIgnoreCase("image/png")) format = Bitmap.CompressFormat.PNG;
        else if (type != null && type.equalsIgnoreCase("image/webp")) format = Bitmap.CompressFormat.WEBP;
        Uri destination = null;
        try {
            boolean png = format == Bitmap.CompressFormat.PNG;
            boolean webp = format == Bitmap.CompressFormat.WEBP;
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "Rotated_" + java.util.UUID.randomUUID()
                    + (png ? ".png" : webp ? ".webp" : ".jpg"));
            values.put(MediaStore.Images.Media.MIME_TYPE, png ? "image/png" : webp ? "image/webp" : "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoRotator");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            destination = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (destination == null) throw new IllegalStateException("无法创建照片副本");
            try (OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                if (output == null) throw new IllegalStateException("无法写入照片");
                if (!rotated.compress(format, 95, output)) throw new IllegalStateException("保存照片失败");
                output.flush();
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Images.Media.IS_PENDING, 0);
            if (getContentResolver().update(destination, ready, null, null) != 1)
                throw new IllegalStateException("无法将副本添加到相册");
        } catch (Exception e) {
            if (destination != null) {
                try { getContentResolver().delete(destination, null, null); }
                catch (Exception cleanupFailure) { e.addSuppressed(cleanupFailure); }
            }
            throw e;
        } finally {
            rotated.recycle();
        }
    }

    private Bitmap applyExifOrientation(Bitmap source, int orientation) {
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

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }

    @Override protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }
}
