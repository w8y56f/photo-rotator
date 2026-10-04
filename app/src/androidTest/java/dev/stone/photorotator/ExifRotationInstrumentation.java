package dev.stone.photorotator;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.media.MediaScannerConnection;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.system.Os;
import androidx.exifinterface.media.ExifInterface;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.File;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Device regressions use a granted picker URI or an app-owned temporary photo. */
public class ExifRotationInstrumentation extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments = args; start(); }

    @Override public void onStart() {
        if ("true".equals(arguments.getString("overwriteTimestampsOnly"))) {
            testOverwriteTimestamps();
            return;
        }
        if ("true".equals(arguments.getString("jpegMatcherOnly"))) {
            testJpegMatcher();
            return;
        }
        Bundle result = new Bundle();
        Uri output = null;
        ContentResolver resolver = getTargetContext().getContentResolver();
        int code = Activity.RESULT_CANCELED;
        try {
            String uri = arguments.getString("pickerUri");
            require(uri != null, "Pass an already granted pickerUri");
            Uri picker = Uri.parse(uri);
            Uri original = ExifRotation.resolveLocalMediaUri(resolver, picker);
            try (Cursor p = resolver.query(picker, new String[]{"_data", "_display_name"}, null, null, null);
                 Cursor o = resolver.query(original, new String[]{"_data", "_display_name"}, null, null, null)) {
                require(p != null && o != null && p.moveToFirst() && o.moveToFirst(), "Missing metadata");
                result.putBoolean("picker_path_differs", !Objects.equals(p.getString(0), o.getString(0)));
                result.putBoolean("picker_name_differs", !Objects.equals(p.getString(1), o.getString(1)));
                result.putBoolean("picker_path_is_synthetic", p.getString(0).contains(".transforms"));
            }
            byte[] before = read(resolver, MediaStore.setRequireOriginal(original));
            ExifInterface source = new ExifInterface(new ByteArrayInputStream(before));
            result.putBoolean("source_has_gps", source.getLatLong() != null);
            result.putBoolean("source_has_camera", source.hasAttribute(ExifInterface.TAG_MODEL));
            output = ExifRotation.saveCopy(getTargetContext(), picker, -90);
            byte[] copied = read(resolver, output);
            ExifInterface rotated = new ExifInterface(new ByteArrayInputStream(copied));
            String[] tags = {"GPSLatitude", "GPSLatitudeRef", "GPSLongitude", "GPSLongitudeRef",
                    "GPSAltitude", "GPSAltitudeRef", "GPSTimeStamp", "GPSDateStamp",
                    "Make", "Model", "DateTimeOriginal", "DateTimeDigitized", "FNumber",
                    "ExposureTime", "PhotographicSensitivity", "FocalLength", "LensModel"};
            int retained = 0;
            for (String tag : tags) {
                require(Objects.equals(source.getAttribute(tag), rotated.getAttribute(tag)), "Changed tag: " + tag);
                if (source.hasAttribute(tag)) retained++;
            }
            Bitmap sourcePixels = BitmapFactory.decodeByteArray(before, 0, before.length);
            Bitmap resultPixels = BitmapFactory.decodeByteArray(copied, 0, copied.length);
            require(sourcePixels != null && resultPixels != null, "Cannot decode images");
            int sourceOrientation = source.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
            boolean sourceQuarterTurn = sourceOrientation >= ExifInterface.ORIENTATION_TRANSPOSE
                    && sourceOrientation <= ExifInterface.ORIENTATION_ROTATE_270;
            require(resultPixels.getWidth() == (sourceQuarterTurn ? sourcePixels.getWidth() : sourcePixels.getHeight())
                    && resultPixels.getHeight() == (sourceQuarterTurn ? sourcePixels.getHeight() : sourcePixels.getWidth()),
                    "Rotated pixel dimensions are wrong");
            require(rotated.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1)
                    == ExifInterface.ORIENTATION_NORMAL, "Output orientation is not normal");
            require(!Arrays.equals(jpegPayload(before), jpegPayload(copied)), "JPEG pixel payload did not change");
            sourcePixels.recycle();
            resultPixels.recycle();
            require(Arrays.equals(sha(before), sha(read(resolver, MediaStore.setRequireOriginal(original)))),
                    "Original file changed");
            result.putInt("present_metadata_tags_preserved", retained);
            result.putBoolean("gps_preserved", true);
            result.putBoolean("camera_preserved", true);
            result.putBoolean("orientation_correct", true);
            result.putBoolean("jpeg_payload_rotated", true);
            result.putBoolean("original_file_unchanged", true);
            code = Activity.RESULT_OK;
        } catch (Throwable e) {
            result.putString("failure", e.toString());
        } finally {
            if (output != null) {
                try {
                    int deleted = resolver.delete(output, null, null);
                    result.putBoolean("test_copy_removed", deleted == 1);
                    if (deleted != 1) code = Activity.RESULT_CANCELED;
                } catch (Exception e) { result.putString("cleanup_failure", e.toString()); code = Activity.RESULT_CANCELED; }
            }
            finish(code, result);
        }
    }

    private void testOverwriteTimestamps() {
        Bundle result = new Bundle();
        ContentResolver resolver = getTargetContext().getContentResolver();
        Uri fixture = null;
        int code = Activity.RESULT_CANCELED;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, "TimestampTest_" + UUID.randomUUID() + ".jpg");
            values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/PhotoRotator");
            fixture = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            require(fixture != null, "Cannot create fixture");
            Bitmap bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(0xff4285f4);
            try (OutputStream output = resolver.openOutputStream(fixture, "wt")) {
                require(output != null && bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output),
                        "Cannot write fixture");
            } finally {
                bitmap.recycle();
            }
            long historicalTime = 1600000000000L;
            try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(fixture, "rw")) {
                require(descriptor != null && new File("/proc/self/fd/" + descriptor.getFd())
                        .setLastModified(historicalTime), "Cannot set fixture historical time");
            }
            String path;
            try (Cursor cursor = resolver.query(fixture, new String[]{MediaStore.MediaColumns.DATA},
                    null, null, null)) {
                require(cursor != null && cursor.moveToFirst(), "Missing fixture path");
                path = cursor.getString(0);
            }
            CountDownLatch scanned = new CountDownLatch(1);
            MediaScannerConnection.scanFile(getTargetContext(), new String[]{path}, null,
                    (scannedPath, scannedUri) -> scanned.countDown());
            require(scanned.await(30, TimeUnit.SECONDS), "Fixture scan timed out");
            Long[] before = mediaDates(resolver, fixture);
            require(Objects.equals(before[0], historicalTime / 1000), "Fixture date not indexed");
            ExifRotation.overwrite(getTargetContext(), fixture, 90, false);
            require(Arrays.equals(before, mediaDates(resolver, fixture)), "Keep mode changed gallery dates");
            try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(fixture, "r")) {
                require(descriptor != null && Os.fstat(descriptor.getFileDescriptor()).st_mtime
                        == historicalTime / 1000, "Keep mode changed file time");
            }
            result.putBoolean("keep_timestamp_passed", true);
            long start = System.currentTimeMillis() / 1000;
            ExifRotation.overwrite(getTargetContext(), fixture, 90, true);
            Long[] after = mediaDates(resolver, fixture);
            require(after[0] != null && after[0] >= start
                    && after[0] <= System.currentTimeMillis() / 1000, "Update mode did not update time");
            require(Objects.equals(before[1], after[1]) && Objects.equals(before[2], after[2]),
                    "Update mode changed added/capture dates");
            try (InputStream input = resolver.openInputStream(fixture)) {
                Bitmap rotated = BitmapFactory.decodeStream(input);
                require(rotated != null && rotated.getWidth() == 40 && rotated.getHeight() == 20,
                        "Overwrite did not rotate pixels");
                rotated.recycle();
            }
            result.putBoolean("update_timestamp_passed", true);
            code = Activity.RESULT_OK;
        } catch (Throwable failure) {
            result.putString("failure", failure.toString());
        } finally {
            if (fixture != null) {
                try {
                    require(resolver.delete(fixture, null, null) == 1, "Cannot remove fixture");
                } catch (Throwable failure) {
                    result.putString("cleanup_failure", failure.toString());
                    code = Activity.RESULT_CANCELED;
                }
            }
            finish(code, result);
        }
    }

    private Long[] mediaDates(ContentResolver resolver, Uri source) {
        try (Cursor cursor = resolver.query(source, new String[]{MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.DATE_ADDED, MediaStore.Images.Media.DATE_TAKEN}, null, null, null)) {
            require(cursor != null && cursor.moveToFirst(), "Missing fixture dates");
            Long[] dates = new Long[3];
            for (int index = 0; index < dates.length; index++) {
                dates[index] = cursor.isNull(index) ? null : cursor.getLong(index);
            }
            return dates;
        }
    }

    private void testJpegMatcher() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_CANCELED;
        try {
            byte[] original = jpeg((byte) 1, (byte) 9);
            byte[] redacted = jpeg((byte) 2, (byte) 9);
            byte[] different = jpeg((byte) 2, (byte) 8);
            require(ExifRotation.sameJpegImageData(
                    new ByteArrayInputStream(original), new ByteArrayInputStream(redacted)),
                    "EXIF-only change was not matched");
            require(!ExifRotation.sameJpegImageData(
                    new ByteArrayInputStream(original), new ByteArrayInputStream(different)),
                    "Different image data was matched");
            require(!ExifRotation.sameJpegImageData(
                    new ByteArrayInputStream(new byte[]{1, 2, 3}), new ByteArrayInputStream(original)),
                    "Invalid JPEG was matched");
            result.putBoolean("jpeg_matcher_passed", true);
            code = Activity.RESULT_OK;
        } catch (Throwable e) {
            result.putString("failure", e.toString());
        }
        finish(code, result);
    }

    private byte[] read(ContentResolver resolver, Uri uri) throws Exception {
        try (InputStream input = resolver.openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            require(input != null, "Missing stream");
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }
    private byte[] sha(byte[] bytes) throws Exception { return MessageDigest.getInstance("SHA-256").digest(bytes); }
    private byte[] jpegPayload(byte[] bytes) {
        require(bytes.length > 4 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216, "Fixture must be JPEG");
        int offset = 2;
        while (offset + 3 < bytes.length) {
            require((bytes[offset++] & 255) == 255, "Bad JPEG marker");
            while ((bytes[offset] & 255) == 255) offset++;
            int marker = bytes[offset++] & 255;
            if (marker == 218) return Arrays.copyOfRange(bytes, offset - 2, bytes.length);
            int length = ((bytes[offset] & 255) << 8) | (bytes[offset + 1] & 255);
            require(length >= 2 && offset + length <= bytes.length, "Invalid segment length");
            offset += length;
        }
        throw new AssertionError("Missing JPEG scan");
    }
    private byte[] jpeg(byte metadata, byte pixel) {
        return new byte[]{(byte) 0xff, (byte) 0xd8,
                (byte) 0xff, (byte) 0xe1, 0, 3, metadata,
                (byte) 0xff, (byte) 0xda, 0, 2,
                pixel, (byte) 0xff, (byte) 0xd9};
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
