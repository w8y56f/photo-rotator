package dev.stone.photorotator;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.system.Os;
import android.system.StructStat;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
        PreparedPhoto prepared = prepare(context, source, rotationDegrees, false);
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

    static Uri overwrite(Context context, Uri source, int rotationDegrees,
                          boolean updateTimestamp) throws Exception {
        PreparedPhoto prepared = prepare(context, source, rotationDegrees, true);
        ContentResolver resolver = context.getContentResolver();
        Uri writeTarget = prepared.mediaUri.buildUpon().authority(MediaStore.AUTHORITY).build();
        try {
            long originalTimestamp = readModifiedTime(resolver, writeTarget);
            String path = readMediaPath(resolver, writeTarget);
            try {
                long timestamp = updateTimestamp ? System.currentTimeMillis() : originalTimestamp;
                writeFile(resolver, writeTarget, prepared.rotatedFile, timestamp);
                scanAndVerifyTimestamp(context, writeTarget, path, timestamp);
            } catch (Exception writeFailure) {
                try {
                    writeFile(resolver, writeTarget, prepared.sourceFile, originalTimestamp);
                    scanAndVerifyTimestamp(context, writeTarget, path, originalTimestamp);
                } catch (Exception restoreFailure) {
                    writeFailure.addSuppressed(restoreFailure);
                }
                throw writeFailure;
            }
            return writeTarget;
        } finally {
            prepared.cleanup();
        }
    }

    static Uri resolveForOverwrite(Context context, Uri pickerUri) {
        try {
            Uri mediaUri = resolveLocalMediaUri(context, pickerUri);
            // createWriteRequest requires a MediaStore item URI under the canonical authority.
            return mediaUri.buildUpon().authority(MediaStore.AUTHORITY).build();
        } catch (RuntimeException e) {
            throw new IllegalStateException("无法确认分享照片的本机原图（" + e.getMessage()
                    + "），不能覆盖；请改选另存新图", e);
        }
    }

    private static PreparedPhoto prepare(Context context, Uri source, int rotationDegrees,
                                         boolean overwrite) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        Uri mediaUri;
        try {
            mediaUri = resolveLocalMediaUri(context, source);
        } catch (RuntimeException e) {
            if (overwrite) {
                throw new IllegalStateException("无法确认分享照片的本机原图（" + e.getMessage()
                        + "），不能覆盖；请改选另存新图", e);
            }
            mediaUri = null;
        }
        String mime = resolver.getType(mediaUri != null ? mediaUri : source);
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
            if (mediaUri != null) {
                Uri requireOriginal = MediaStore.setRequireOriginal(mediaUri);
                try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(requireOriginal, "r")) {
                    if (descriptor == null) throw new IllegalStateException("无法读取照片原始文件");
                    try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                         OutputStream output = new FileOutputStream(original)) {
                        copy(input, output);
                    }
                } catch (java.io.IOException | UnsupportedOperationException | SecurityException e) {
                    throw new IllegalStateException("系统未提供照片原文件；请确认照片已下载到本机", e);
                }
            } else {
                // Non-MediaStore shares can only provide the bytes and EXIF exposed by
                // the sending app. They cannot be safely used for overwrite.
                try (InputStream input = resolver.openInputStream(source);
                     OutputStream output = new FileOutputStream(original)) {
                    if (input == null) throw new IllegalStateException("无法读取分享的照片");
                    copy(input, output);
                }
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

    private static void copy(InputStream input, OutputStream output) throws java.io.IOException {
        byte[] buffer = new byte[64 * 1024];
        int length;
        while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
    }

    private static long readModifiedTime(ContentResolver resolver, Uri source) throws Exception {
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(source, "r")) {
            if (descriptor == null) throw new IllegalStateException("无法读取原照片时间");
            StructStat stat = Os.fstat(descriptor.getFileDescriptor());
            return stat.st_mtim.tv_sec * 1000 + stat.st_mtim.tv_nsec / 1000000;
        }
    }

    private static String readMediaPath(ContentResolver resolver, Uri source) {
        try (Cursor cursor = resolver.query(source, new String[]{MediaStore.MediaColumns.DATA},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                String path = cursor.getString(0);
                if (path != null && !path.isEmpty()) return path;
            }
        }
        throw new IllegalStateException("无法读取原照片路径以同步相册时间");
    }

    private static void scanAndVerifyTimestamp(Context context, Uri source, String path,
                                               long timestamp) throws Exception {
        CountDownLatch scanned = new CountDownLatch(1);
        MediaScannerConnection.scanFile(context, new String[]{path}, null,
                (scannedPath, scannedUri) -> scanned.countDown());
        if (!scanned.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("同步相册时间超时");
        }
        try (Cursor cursor = context.getContentResolver().query(source,
                new String[]{MediaStore.MediaColumns.DATE_MODIFIED}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst() || cursor.isNull(0)
                    || cursor.getLong(0) != timestamp / 1000) {
                throw new IllegalStateException("相册未能同步照片时间");
            }
        }
    }

    private static void writeFile(ContentResolver resolver, Uri destination, File source,
                                  long timestamp) throws Exception {
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(destination, "rwt")) {
            if (descriptor == null) throw new IllegalStateException("无法打开原照片以写入");
            try (FileOutputStream output = new FileOutputStream(descriptor.getFileDescriptor());
                 InputStream input = new FileInputStream(source)) {
                byte[] buffer = new byte[64 * 1024];
                int length;
                while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
                output.flush();
                output.getFD().sync();
                File descriptorPath = new File("/proc/self/fd/" + descriptor.getFd());
                if (!descriptorPath.setLastModified(timestamp)
                        || Os.fstat(descriptor.getFileDescriptor()).st_mtime != timestamp / 1000) {
                    throw new IllegalStateException("无法设置照片修改时间");
                }
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

    private static Uri resolveLocalMediaUri(Context context, Uri source) {
        String authority = source.getAuthority();
        if ("com.android.providers.media.documents".equals(authority)
                || "com.android.externalstorage.documents".equals(authority)) {
            Uri equivalent = MediaStore.getMediaUri(context, source);
            if (equivalent != null) {
                return resolveLocalMediaUri(context.getContentResolver(), equivalent);
            }
        }
        return resolveLocalMediaUri(context.getContentResolver(), source);
    }

    static Uri resolveLocalMediaUri(ContentResolver resolver, Uri pickerUri) {
        List<String> segments = pickerUri.getPathSegments();
        if ("media".equals(pickerUri.getAuthority()) && segments.size() == 4
                && "images".equals(segments.get(1)) && "media".equals(segments.get(2))) {
            try {
                if (Long.parseLong(segments.get(3)) <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                throw new IllegalStateException("分享的本机照片标识无效", e);
            }
            try (Cursor original = resolver.query(pickerUri,
                    new String[]{MediaStore.MediaColumns.MIME_TYPE}, null, null, null)) {
                if (original == null || !original.moveToFirst()
                        || original.getString(0) == null
                        || !original.getString(0).startsWith("image/")) {
                    throw new IllegalStateException("无法读取分享的本机原图");
                }
            }
            return pickerUri;
        }
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
        String name;
        long size;
        // Shared content providers are only required to expose these two columns;
        // asking them for DATA in the same query can make the whole query fail.
        try (Cursor picked = resolver.query(pickerUri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null)) {
            if (picked == null || !picked.moveToFirst()) {
                throw new IllegalStateException("无法读取分享照片的名称与大小");
            }
            name = picked.getString(0);
            size = picked.isNull(1) ? -1 : picked.getLong(1);
        }
        if (name == null || name.isEmpty()) {
            throw new IllegalStateException("分享照片没有可用于确认原图的名称");
        }
        String path = null;
        try (Cursor picked = resolver.query(pickerUri,
                new String[]{MediaStore.MediaColumns.DATA}, null, null, null)) {
            if (picked != null && picked.moveToFirst()) path = picked.getString(0);
        } catch (RuntimeException ignored) {
            // Most sharing providers do not expose a filesystem path.
        }
        // Galleries can strip camera EXIF when sharing. The exported file then has
        // a different byte size even though it refers to the same local photo.
        // Check the path first, then compare the actual image data if needed.
        Uri match = null;
        try (Cursor original = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID, MediaStore.MediaColumns.RELATIVE_PATH,
                        MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.MIME_TYPE},
                MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                new String[]{name}, null)) {
            if (original == null) {
                throw new IllegalStateException("无法查询本机原图；未生成副本");
            }
            while (original.moveToNext()) {
                String relative = original.getString(1);
                String displayName = original.getString(2);
                if (displayName == null) continue;
                Uri candidate = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        original.getLong(0));
                boolean samePath = path != null && relative != null
                        && path.endsWith("/" + relative + displayName);
                boolean sameBytes = !samePath && !original.isNull(3) && size == original.getLong(3)
                        && sameOriginalBytes(resolver, pickerUri, candidate);
                boolean sameJpegData = !samePath && !sameBytes
                        && "image/jpeg".equalsIgnoreCase(original.getString(4))
                        && sameJpegImageData(resolver, pickerUri, candidate);
                if (!samePath && !sameBytes && !sameJpegData) continue;
                if (match != null) {
                    throw new IllegalStateException("找到多张匹配的本机照片，无法安全确认原图；未生成副本");
                }
                match = candidate;
            }
        }
        if (match == null) {
            throw new IllegalStateException("无法定位本机原图；请确认照片已下载到本机且允许访问所有照片。未生成副本");
        }
        return match;
    }

    private static boolean sameOriginalBytes(ContentResolver resolver, Uri shared, Uri original) {
        try (InputStream sharedInput = resolver.openInputStream(shared);
             ParcelFileDescriptor descriptor = resolver.openFileDescriptor(
                     MediaStore.setRequireOriginal(original), "r")) {
            if (sharedInput == null || descriptor == null) return false;
            try (InputStream originalInput = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                byte[] sharedBuffer = new byte[64 * 1024];
                byte[] originalBuffer = new byte[64 * 1024];
                int count;
                while ((count = sharedInput.read(sharedBuffer)) != -1) {
                    int offset = 0;
                    while (offset < count) {
                        int read = originalInput.read(originalBuffer, offset, count - offset);
                        if (read == -1) return false;
                        offset += read;
                    }
                    for (int i = 0; i < count; i++) {
                        if (sharedBuffer[i] != originalBuffer[i]) return false;
                    }
                }
                return originalInput.read() == -1;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean sameJpegImageData(ContentResolver resolver, Uri shared, Uri original) {
        try (InputStream sharedInput = resolver.openInputStream(shared);
             ParcelFileDescriptor descriptor = resolver.openFileDescriptor(
                     MediaStore.setRequireOriginal(original), "r")) {
            if (sharedInput == null || descriptor == null) return false;
            try (InputStream originalInput = new BufferedInputStream(
                    new ParcelFileDescriptor.AutoCloseInputStream(descriptor))) {
                return sameJpegImageData(sharedInput, originalInput);
            }
        } catch (Exception e) {
            return false;
        }
    }

    static boolean sameJpegImageData(InputStream sharedInput, InputStream originalInput)
            throws java.io.IOException {
        InputStream shared = new BufferedInputStream(sharedInput);
        InputStream original = new BufferedInputStream(originalInput);
        if (!skipJpegMetadata(shared) || !skipJpegMetadata(original)) return false;
        byte[] sharedBuffer = new byte[64 * 1024];
        byte[] originalBuffer = new byte[64 * 1024];
        int count;
        while ((count = shared.read(sharedBuffer)) != -1) {
            int offset = 0;
            while (offset < count) {
                int read = original.read(originalBuffer, offset, count - offset);
                if (read == -1) return false;
                offset += read;
            }
            for (int i = 0; i < count; i++) {
                if (sharedBuffer[i] != originalBuffer[i]) return false;
            }
        }
        return original.read() == -1;
    }

    // Compare the JPEG scan and everything after it. APP/EXIF segments before
    // the scan may differ after a gallery redacts location and camera metadata.
    private static boolean skipJpegMetadata(InputStream input) throws java.io.IOException {
        if (input.read() != 0xff || input.read() != 0xd8) return false;
        while (true) {
            if (input.read() != 0xff) return false;
            int marker;
            do { marker = input.read(); } while (marker == 0xff);
            if (marker < 0 || marker == 0xd9 || marker == 0x00) return false;
            if (marker == 0xd8 || marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) continue;
            int high = input.read();
            int low = input.read();
            if (high < 0 || low < 0) return false;
            int length = (high << 8) | low;
            if (length < 2) return false;
            int remaining = length - 2;
            while (remaining > 0) {
                long skipped = input.skip(remaining);
                if (skipped == 0) {
                    if (input.read() == -1) return false;
                    skipped = 1;
                }
                remaining -= (int) skipped;
            }
            if (marker == 0xda) return true;
        }
    }
}
