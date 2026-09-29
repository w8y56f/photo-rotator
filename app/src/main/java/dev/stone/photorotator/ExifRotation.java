package dev.stone.photorotator;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.List;

/** Makes a new photo whose pixels are untouched and whose EXIF orientation is rotated. */
final class ExifRotation {
    private ExifRotation() { }

    static Uri saveCopy(Context context, Uri source) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        Uri mediaUri = resolveLocalMediaUri(resolver, source);
        String mime = resolver.getType(mediaUri);
        String extension;
        if ("image/jpeg".equalsIgnoreCase(mime) || "image/jpg".equalsIgnoreCase(mime)) {
            mime = "image/jpeg";
            extension = ".jpg";
        } else if ("image/png".equalsIgnoreCase(mime)) {
            extension = ".png";
        } else if ("image/webp".equalsIgnoreCase(mime)) {
            extension = ".webp";
        } else {
            throw new IllegalArgumentException("保留 EXIF 模式只支持 JPEG、PNG、WebP；此照片请使用普通旋转");
        }

        File temporary = File.createTempFile("photo-rotator-", extension, context.getCacheDir());
        Uri destination = null;
        try {
            // Picker URIs are always redacted on some Android versions. Query the
            // original MediaStore item after the user grants media access.
            Uri original = MediaStore.setRequireOriginal(mediaUri);
            try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(original, "r")) {
                if (descriptor == null) throw new IllegalStateException("无法读取照片原始文件");
                try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                 OutputStream output = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[64 * 1024];
                    int length;
                    while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
                }
            } catch (java.io.IOException | UnsupportedOperationException | SecurityException e) {
                throw new IllegalStateException("系统未提供包含定位信息的原始照片，未生成副本", e);
            }

            ExifInterface exif = new ExifInterface(temporary);
            String[] retainedTags = {
                    ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
                    ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
                    ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
                    ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
                    ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
                    ExifInterface.TAG_DATETIME_ORIGINAL
            };
            String[] originalValues = new String[retainedTags.length];
            for (int i = 0; i < retainedTags.length; i++) {
                originalValues[i] = exif.getAttribute(retainedTags[i]);
            }
            exif.rotate(-90);
            exif.saveAttributes();
            ExifInterface verified = new ExifInterface(temporary);
            for (int i = 0; i < retainedTags.length; i++) {
                if (!java.util.Objects.equals(originalValues[i], verified.getAttribute(retainedTags[i]))) {
                    throw new IllegalStateException("照片的地址或拍摄信息无法完整保留，未生成副本");
                }
            }

            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "Rotated_EXIF_" + UUID.randomUUID() + extension);
            values.put(MediaStore.Images.Media.MIME_TYPE, mime);
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoRotator");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            destination = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (destination == null) throw new IllegalStateException("无法创建照片副本");

            try (InputStream input = new FileInputStream(temporary);
                 OutputStream output = resolver.openOutputStream(destination, "wt")) {
                if (output == null) throw new IllegalStateException("无法写入照片副本");
                byte[] buffer = new byte[64 * 1024];
                int length;
                while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
                output.flush();
            }

            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Images.Media.IS_PENDING, 0);
            if (resolver.update(destination, ready, null, null) != 1) {
                throw new IllegalStateException("无法将副本添加到相册");
            }
            return destination;
        } catch (Exception e) {
            if (destination != null) {
                try { resolver.delete(destination, null, null); }
                catch (Exception cleanupFailure) { e.addSuppressed(cleanupFailure); }
            }
            throw e;
        } finally {
            // A failed deletion only leaves a private cache file; Android may clear it later.
            temporary.delete();
        }
    }

    static Uri resolveLocalMediaUri(ContentResolver resolver, Uri pickerUri) {
        List<String> segments = pickerUri.getPathSegments();
        if ("media".equals(pickerUri.getAuthority()) && segments.size() == 5
                && ("picker".equals(segments.get(0)) || "picker_get_content".equals(segments.get(0)))
                && "com.android.providers.media.photopicker".equals(segments.get(2))
                && "media".equals(segments.get(3))) {
            // The system local provider embeds the original MediaStore row ID.
            // Picker DATA/DISPLAY_NAME can instead describe a synthetic FUSE file.
            // Preserve the user component so IDs from different profiles cannot collide.
            long id;
            int user;
            try {
                id = Long.parseLong(segments.get(4));
                user = Integer.parseInt(segments.get(1));
                if (id <= 0 || user < 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                throw new IllegalStateException("照片选择器返回了无效的本机照片标识", e);
            }
            Uri originalUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    .buildUpon().authority(user + "@media").build();
            try (Cursor picked = resolver.query(pickerUri,
                    new String[]{MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE},
                    null, null, null);
                 Cursor original = resolver.query(originalUri,
                    new String[]{MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE},
                    null, null, null)) {
                if (picked == null || original == null || !picked.moveToFirst() || !original.moveToFirst()) {
                    throw new IllegalStateException("无法读取所选原图；请允许访问该照片后重新选择");
                }
                if (picked.getLong(0) != original.getLong(0)
                        || !java.util.Objects.equals(picked.getString(1), original.getString(1))) {
                    throw new IllegalStateException("所选照片与本机原图不一致，未生成副本");
                }
            }
            return originalUri;
        }
        String path;
        String name;
        long size;
        try (Cursor picked = resolver.query(pickerUri,
                new String[]{MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.SIZE}, null, null, null)) {
            if (picked == null || !picked.moveToFirst()) {
                throw new IllegalStateException("无法找到所选照片的本机原始文件；未生成副本");
            }
            path = picked.getString(0);
            name = picked.getString(1);
            size = picked.getLong(2);
        }
        if (path == null || path.isEmpty() || name == null || size <= 0) {
            throw new IllegalStateException("此照片没有可读取的本机原始文件；未生成副本");
        }
        // Picker paths can start with /sdcard, /storage/emulated or /mnt/user,
        // while MediaStore stores another spelling of that same location.
        // Match the relative directory, exact filename and exact byte size.
        Uri match = null;
        try (Cursor original = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID, MediaStore.MediaColumns.RELATIVE_PATH,
                        MediaStore.MediaColumns.DISPLAY_NAME},
                MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.SIZE + "=?",
                new String[]{name, Long.toString(size)}, null)) {
            if (original == null) {
                throw new IllegalStateException("无法查询本机原图；未生成副本");
            }
            while (original.moveToNext()) {
                String relative = original.getString(1);
                String displayName = original.getString(2);
                if (relative == null || displayName == null) continue;
                String suffix = "/" + relative + displayName;
                if (!path.endsWith(suffix)) continue;
                if (match != null) {
                    throw new IllegalStateException("找到多张同名同大小照片，无法安全确认原图；未生成副本");
                }
                match = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        original.getLong(0));
            }
        }
        if (match == null) {
            throw new IllegalStateException("无法定位本机原图；请确认照片已下载到本机且允许访问所有照片。未生成副本");
        }
        return match;
    }
}
