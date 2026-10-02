package dev.stone.photorotator;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;

/** Rotates the image pixels and carries the source EXIF into the new photo. */
final class ExifRotation {
    private ExifRotation() { }

    private static final String[] COPIED_TAGS = {
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_USER_COMMENT,
            ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_SHUTTER_SPEED_VALUE, ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_BRIGHTNESS_VALUE, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_LIGHT_SOURCE,
            ExifInterface.TAG_FLASH, ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_EXPOSURE_MODE,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_BODY_SERIAL_NUMBER, ExifInterface.TAG_LENS_SERIAL_NUMBER,
            ExifInterface.TAG_GPS_VERSION_ID, ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF,
            ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF
    };

    static Uri saveCopy(Context context, Uri source, int rotationDegrees) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        PreparedPhoto prepared = prepare(context, source, rotationDegrees);
        Uri destination = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "Rotated_" + UUID.randomUUID() + prepared.extension);
            values.put(MediaStore.Images.Media.MIME_TYPE, prepared.mime);
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoRotator");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            destination = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (destination == null) throw new IllegalStateException("无法创建照片副本");

            try (InputStream input = new FileInputStream(prepared.rotatedFile);
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
            prepared.cleanup();
        }
    }

    static void overwrite(Context context, Uri source, int rotationDegrees) throws Exception {
        PreparedPhoto prepared = prepare(context, source, rotationDegrees);
        ContentResolver resolver = context.getContentResolver();
        Uri writeTarget = prepared.mediaUri.buildUpon().authority(MediaStore.AUTHORITY).build();
        try {
            writeFile(resolver, writeTarget, prepared.rotatedFile);
        } catch (Exception writeFailure) {
            try {
                writeFile(resolver, writeTarget, prepared.sourceFile);
            } catch (Exception restoreFailure) {
                writeFailure.addSuppressed(restoreFailure);
            }
            throw writeFailure;
        } finally {
            prepared.cleanup();
        }
    }

    static Uri resolveForOverwrite(ContentResolver resolver, Uri pickerUri) {
        Uri mediaUri = resolveLocalMediaUri(resolver, pickerUri);
        // createWriteRequest requires a MediaStore item URI under the canonical authority.
        return mediaUri.buildUpon().authority(MediaStore.AUTHORITY).build();
    }

    private static PreparedPhoto prepare(Context context, Uri source, int rotationDegrees) throws Exception {
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
            throw new IllegalArgumentException("仅支持 JPEG、PNG、WebP 照片");
        }
        File original = File.createTempFile("photo-rotator-source-", extension, context.getCacheDir());
        File rotated = null;
        try {
            Uri requireOriginal = MediaStore.setRequireOriginal(mediaUri);
            try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(requireOriginal, "r")) {
                if (descriptor == null) throw new IllegalStateException("无法读取照片原始文件");
                try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                     OutputStream output = new FileOutputStream(original)) {
                    byte[] buffer = new byte[64 * 1024];
                    int length;
                    while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
                }
            } catch (java.io.IOException | UnsupportedOperationException | SecurityException e) {
                throw new IllegalStateException("系统未提供照片原文件；请确认照片已下载到本机", e);
            }

            ExifInterface sourceExif = new ExifInterface(original);
            String[] originalValues = new String[COPIED_TAGS.length];
            for (int i = 0; i < COPIED_TAGS.length; i++) {
                originalValues[i] = sourceExif.getAttribute(COPIED_TAGS[i]);
            }
            rotated = File.createTempFile("photo-rotator-rotated-", extension, context.getCacheDir());
            rotatePixels(original, rotated, mime, sourceExif, rotationDegrees);

            ExifInterface outputExif = new ExifInterface(rotated);
            for (int i = 0; i < COPIED_TAGS.length; i++) {
                if (originalValues[i] != null) outputExif.setAttribute(COPIED_TAGS[i], originalValues[i]);
            }
            outputExif.setAttribute(ExifInterface.TAG_ORIENTATION,
                    Integer.toString(ExifInterface.ORIENTATION_NORMAL));
            outputExif.saveAttributes();
            ExifInterface verified = new ExifInterface(rotated);
            if (verified.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1) != ExifInterface.ORIENTATION_NORMAL) {
                throw new IllegalStateException("无法更新照片方向信息");
            }
            for (int i = 0; i < COPIED_TAGS.length; i++) {
                if (!sameExifValue(COPIED_TAGS[i], originalValues[i], verified.getAttribute(COPIED_TAGS[i]))) {
                    throw new IllegalStateException("EXIF 字段 " + COPIED_TAGS[i] + " 无法完整保留");
                }
            }
            return new PreparedPhoto(mediaUri, mime, extension, original, rotated);
        } catch (Exception e) {
            original.delete();
            if (rotated != null) rotated.delete();
            throw e;
        }
    }

    private static void writeFile(ContentResolver resolver, Uri destination, File source) throws Exception {
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(destination, "rwt")) {
            if (descriptor == null) throw new IllegalStateException("无法打开原照片以写入");
            try (FileOutputStream output = new FileOutputStream(descriptor.getFileDescriptor());
                 InputStream input = new FileInputStream(source)) {
                byte[] buffer = new byte[64 * 1024];
                int length;
                while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
                output.flush();
                output.getFD().sync();
            }
        }
    }

    private static final class PreparedPhoto {
        final Uri mediaUri;
        final String mime;
        final String extension;
        final File sourceFile;
        final File rotatedFile;

        PreparedPhoto(Uri mediaUri, String mime, String extension, File sourceFile, File rotatedFile) {
            this.mediaUri = mediaUri;
            this.mime = mime;
            this.extension = extension;
            this.sourceFile = sourceFile;
            this.rotatedFile = rotatedFile;
        }

        void cleanup() {
            sourceFile.delete();
            rotatedFile.delete();
        }
    }

    private static boolean sameExifValue(String tag, String original, String copied) {
        if (java.util.Objects.equals(original, copied)) return true;
        if (original == null || copied == null) return false;
        if (ExifInterface.TAG_EXPOSURE_TIME.equals(tag) || ExifInterface.TAG_F_NUMBER.equals(tag)) {
            try {
                // ExifInterface writes compatibility rational tags at 1/10000 precision.
                return Math.abs(Double.parseDouble(original) - Double.parseDouble(copied)) < 0.0001;
            } catch (NumberFormatException ignored) { }
        }
        return false;
    }

    private static void rotatePixels(File source, File destination, String mime,
                                     ExifInterface sourceExif, int rotationDegrees) throws Exception {
        Bitmap bitmap = BitmapFactory.decodeFile(source.getAbsolutePath());
        if (bitmap == null) throw new IllegalArgumentException("无法解码所选照片");
        Bitmap oriented = bitmap;
        Bitmap rotated = null;
        try {
            oriented = MainActivity.applyExifOrientation(bitmap,
                    sourceExif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL));
            Matrix matrix = new Matrix();
            matrix.postRotate(rotationDegrees);
            rotated = Bitmap.createBitmap(oriented, 0, 0, oriented.getWidth(),
                    oriented.getHeight(), matrix, true);
            Bitmap.CompressFormat format = "image/png".equals(mime)
                    ? Bitmap.CompressFormat.PNG : "image/webp".equals(mime)
                    ? Bitmap.CompressFormat.WEBP : Bitmap.CompressFormat.JPEG;
            try (OutputStream output = new FileOutputStream(destination)) {
                if (!rotated.compress(format, 95, output)) {
                    throw new IllegalStateException("无法编码旋转后的照片");
                }
            }
        } finally {
            if (rotated != null && rotated != oriented) rotated.recycle();
            if (oriented != bitmap) oriented.recycle();
            bitmap.recycle();
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
            // A picker URI can expose a redacted representation of the selected image.
            // Its byte size and MIME type need not equal those of the MediaStore original.
            try (Cursor original = resolver.query(originalUri,
                    new String[]{MediaStore.MediaColumns.MIME_TYPE}, null, null, null)) {
                if (original == null || !original.moveToFirst()) {
                    throw new IllegalStateException("无法读取所选原图；请允许访问该照片后重新选择");
                }
                String originalMime = original.getString(0);
                if (originalMime == null || !originalMime.startsWith("image/")) {
                    throw new IllegalStateException("所选项目不是本机照片，未生成副本");
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
