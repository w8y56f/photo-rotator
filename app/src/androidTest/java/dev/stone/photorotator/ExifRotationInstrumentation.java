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
        if ("true".equals(arguments.getString("compressionOnly"))) {
            testCompression();
            return;
        }
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

    private void testCompression() {
        Bundle result = new Bundle();
        ContentResolver resolver = getTargetContext().getContentResolver();
        java.util.ArrayList<Uri> fixtures = new java.util.ArrayList<>();
        int code = Activity.RESULT_CANCELED;
        try {
            for (String mime : new String[]{"image/jpeg", "image/webp"}) {
                Uri fixture = createCompressionFixture(resolver, mime, fixtures);
                byte[] before = read(resolver, fixture);
                ExifRotation.CompressionResult copy = ExifRotation.compress(getTargetContext(), fixture, 85, false, false);
                require(copy.destination != null && copy.skipReason == null, "Compression was unexpectedly skipped: " + mime);
                fixtures.add(copy.destination);
                byte[] after = read(resolver, copy.destination);
                require(after.length < before.length && copy.originalBytes == before.length
                        && copy.compressedBytes == after.length, "Incorrect compression sizes");
                verifyCompressedImage(resolver, copy.destination);
                require(Arrays.equals(before, read(resolver, fixture)), "Save-copy modified the original");
                result.putLong(mime + "_before", before.length);
                result.putLong(mime + "_after", after.length);

                long historicalTime = 1600000000000L;
                try (ParcelFileDescriptor fd = resolver.openFileDescriptor(fixture, "rw")) {
                    require(fd != null && new File("/proc/self/fd/" + fd.getFd()).setLastModified(historicalTime),
                            "Cannot set fixture timestamp");
                }
                ExifRotation.CompressionResult overwritten = ExifRotation.compress(getTargetContext(), fixture, 85, true, false);
                require(fixture.equals(overwritten.destination), "Compression overwrite returned wrong URI");
                verifyCompressedImage(resolver, fixture);
                try (ParcelFileDescriptor fd = resolver.openFileDescriptor(fixture, "r")) {
                    require(fd != null && Os.fstat(fd.getFileDescriptor()).st_mtime == historicalTime / 1000,
                            "Compression changed preserved timestamp");
                }
                require(mediaDates(resolver, fixture)[0] == historicalTime / 1000, "Gallery timestamp not preserved");
                result.putBoolean(mime + "_dimensions_orientation_exif_copy_overwrite", true);

                Uri fresh = createCompressionFixture(resolver, mime, fixtures);
                long start = System.currentTimeMillis() / 1000;
                ExifRotation.CompressionResult updated = ExifRotation.compress(getTargetContext(), fresh, 70, true, true);
                require(fresh.equals(updated.destination) && mediaDates(resolver, fresh)[0] >= start,
                        "Compression did not update timestamp");
                verifyCompressedImage(resolver, fresh);
            }
            Uri tiny = createSolidFixture(resolver, "image/jpeg", Bitmap.CompressFormat.JPEG, fixtures);
            byte[] tinyBefore = read(resolver, tiny);
            scanFixture(resolver, tiny);
            Long[] datesBefore = mediaDates(resolver, tiny);
            ExifRotation.CompressionResult skipped = ExifRotation.compress(getTargetContext(), tiny, 92, true, true);
            require(skipped.destination == null && "压缩后未变小".equals(skipped.skipReason), "Larger result not skipped");
            require(Arrays.equals(tinyBefore, read(resolver, tiny)), "Skipped compression changed original bytes");
            require(Arrays.equals(datesBefore, mediaDates(resolver, tiny)),
                    "Skipped compression changed dates: " + Arrays.toString(datesBefore)
                            + " -> " + Arrays.toString(mediaDates(resolver, tiny)));
            ExifRotation.CompressionResult skippedCopy = ExifRotation.compress(getTargetContext(), tiny, 92, false, false);
            require(skippedCopy.destination == null, "Skipped save-copy created a destination");
            Uri png = createSolidFixture(resolver, "image/png", Bitmap.CompressFormat.PNG, fixtures);
            byte[] pngBefore = read(resolver, png);
            require(ExifRotation.compress(getTargetContext(), png, 85, true, true).skipReason != null,
                    "PNG was not skipped");
            require(Arrays.equals(pngBefore, read(resolver, png)), "Skipped PNG was changed");
            result.putBoolean("skip_preserves_bytes_and_dates", true);
            result.putBoolean("png_skipped_without_changes", true);
            testOperationControls(result);
            code = Activity.RESULT_OK;
        } catch (Throwable failure) {
            result.putString("failure", failure.toString());
            android.util.Log.e("CompressionTest", "Regression failed", failure);
        } finally {
            for (Uri fixture : fixtures) {
                try { require(resolver.delete(fixture, null, null) == 1, "Cannot remove compression fixture"); }
                catch (Throwable failure) { result.putString("cleanup_failure", failure.toString()); code = Activity.RESULT_CANCELED; }
            }
            File[] temporary = getTargetContext().getCacheDir().listFiles((dir, name) -> name.startsWith("photo-rotator-"));
            result.putBoolean("temporary_files_cleaned", temporary != null && temporary.length == 0);
            finish(code, result);
        }
    }

    private void scanFixture(ContentResolver resolver, Uri uri) throws Exception {
        String path;
        try (Cursor cursor = resolver.query(uri, new String[]{MediaStore.MediaColumns.DATA}, null, null, null)) {
            require(cursor != null && cursor.moveToFirst(), "Missing fixture path");
            path = cursor.getString(0);
        }
        CountDownLatch scanned = new CountDownLatch(1);
        MediaScannerConnection.scanFile(getTargetContext(), new String[]{path}, null,
                (scannedPath, scannedUri) -> scanned.countDown());
        require(scanned.await(30, TimeUnit.SECONDS), "Fixture scan timed out");
    }

    private Uri createCompressionFixture(ContentResolver resolver, String mime,
                                         java.util.List<Uri> fixtures) throws Exception {
        Uri uri = insertFixture(resolver, mime, fixtures);
        Bitmap bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
        java.util.Random random = new java.util.Random(1234);
        int[] pixels = new int[640 * 360];
        for (int i = 0; i < pixels.length; i++) pixels[i] = 0xff000000 | random.nextInt(0x1000000);
        bitmap.setPixels(pixels, 0, 640, 0, 0, 640, 360);
        try (OutputStream output = resolver.openOutputStream(uri, "wt")) {
            require(output != null && bitmap.compress("image/jpeg".equals(mime) ? Bitmap.CompressFormat.JPEG
                    : Bitmap.CompressFormat.WEBP_LOSSLESS, 100, output), "Cannot encode fixture");
        } finally { bitmap.recycle(); }
        try (ParcelFileDescriptor fd = resolver.openFileDescriptor(uri, "rw")) {
            require(fd != null, "Cannot write fixture EXIF");
            ExifInterface exif = new ExifInterface(fd.getFileDescriptor());
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, "6");
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2020:09:13 12:26:40");
            exif.setAttribute(ExifInterface.TAG_MAKE, "CompressionTest");
            exif.setAttribute(ExifInterface.TAG_MODEL, "FixtureCamera");
            exif.setLatLong(25.03, 121.56);
            exif.saveAttributes();
        }
        return uri;
    }

    private Uri insertFixture(ContentResolver resolver, String mime, java.util.List<Uri> fixtures) {
        ContentValues values = new ContentValues();
        String extension = "image/jpeg".equals(mime) ? ".jpg" : "image/webp".equals(mime) ? ".webp" : ".png";
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "CompressionTest_" + UUID.randomUUID() + extension);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/PhotoRotator");
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        require(uri != null, "Cannot create compression fixture");
        fixtures.add(uri);
        return uri;
    }

    private Uri createSolidFixture(ContentResolver resolver, String mime, Bitmap.CompressFormat format,
                                   java.util.List<Uri> fixtures) throws Exception {
        Uri uri = insertFixture(resolver, mime, fixtures);
        Bitmap bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xff808080);
        try (OutputStream output = resolver.openOutputStream(uri, "wt")) {
            require(output != null && bitmap.compress(format, 1, output), "Cannot encode solid fixture");
        } finally { bitmap.recycle(); }
        return uri;
    }

    private void verifyCompressedImage(ContentResolver resolver, Uri uri) throws Exception {
        byte[] bytes = read(resolver, uri);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        require(bounds.outWidth == 640 && bounds.outHeight == 360, "Compression rotated or resized pixels");
        ExifInterface exif = new ExifInterface(new ByteArrayInputStream(bytes));
        require(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1) == 6, "Compression changed EXIF orientation");
        require("2020:09:13 12:26:40".equals(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)), "Lost capture time");
        require("FixtureCamera".equals(exif.getAttribute(ExifInterface.TAG_MODEL)), "Lost camera model");
        double[] gps = exif.getLatLong();
        require(gps != null && Math.abs(gps[0] - 25.03) < .00001 && Math.abs(gps[1] - 121.56) < .00001, "Lost GPS");
    }

    private Object field(Object object, String name) throws Exception {
        java.lang.reflect.Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private void testOperationControls(Bundle result) throws Exception {
        android.content.Intent intent = new android.content.Intent(getTargetContext(), MainActivity.class);
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        MainActivity activity = (MainActivity) startActivitySync(intent);
        waitForIdleSync();
        final Throwable[] failure = new Throwable[1];
        runOnMainSync(() -> {
            try {
                android.widget.RadioGroup mode = (android.widget.RadioGroup) field(activity, "operationGroup");
                android.widget.RadioGroup direction = (android.widget.RadioGroup) field(activity, "directionGroup");
                android.widget.RadioGroup quality = (android.widget.RadioGroup) field(activity, "qualityGroup");
                android.widget.RadioGroup save = (android.widget.RadioGroup) field(activity, "saveModeGroup");
                android.widget.RadioGroup timestamp = (android.widget.RadioGroup) field(activity, "overwriteTimestampGroup");
                android.widget.Button action = (android.widget.Button) field(activity, "processButton");
                require(((android.view.View) field(activity, "rotationSection")).getVisibility() == android.view.View.VISIBLE,
                        "Rotation should be the initial mode");
                direction.check(direction.getChildAt(2).getId());
                save.check(save.getChildAt(1).getId());
                timestamp.check(timestamp.getChildAt(1).getId());
                mode.check(mode.getChildAt(1).getId());
                require(((android.view.View) field(activity, "rotationSection")).getVisibility() == android.view.View.GONE
                        && ((android.view.View) field(activity, "compressionSection")).getVisibility() == android.view.View.VISIBLE,
                        "Compression did not replace rotation settings");
                require("开始压缩".contentEquals(action.getText()), "Compression action label is wrong");
                quality.check(quality.getChildAt(2).getId());
                mode.check(mode.getChildAt(0).getId());
                require(direction.getCheckedRadioButtonId() == direction.getChildAt(2).getId()
                        && save.getCheckedRadioButtonId() == save.getChildAt(1).getId()
                        && timestamp.getCheckedRadioButtonId() == timestamp.getChildAt(1).getId(),
                        "Switching modes lost shared or rotation options");
                mode.check(mode.getChildAt(1).getId());
                require(quality.getCheckedRadioButtonId() == quality.getChildAt(2).getId(), "Switching modes lost compression options");
                result.putBoolean("mode_switch_and_shared_settings_passed", true);
            } catch (Throwable e) { failure[0] = e; }
        });
        if (failure[0] != null) throw new AssertionError("UI controls failed", failure[0]);
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
            require(MainActivity.canWritePhoto(getTargetContext(), fixture),
                    "Writable photo should not need a confirmation request");
            result.putBoolean("existing_write_access_passed", true);
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
            Bitmap originalPreview = MainActivity.loadFreshThumbnail(resolver, fixture, 16);
            require(originalPreview.getWidth() == 32 && originalPreview.getHeight() == 16,
                    "Original preview dimensions are wrong");
            originalPreview.recycle();
            Uri updatedSource = ExifRotation.overwrite(getTargetContext(), fixture, 90, false);
            require(fixture.equals(updatedSource), "Overwrite returned the wrong preview source");
            Bitmap updatedPreview = MainActivity.loadFreshThumbnail(resolver, updatedSource, 16);
            require(updatedPreview.getWidth() == 16 && updatedPreview.getHeight() == 32,
                    "Preview did not reflect the overwritten pixels");
            updatedPreview.recycle();
            require(Arrays.equals(before, mediaDates(resolver, fixture)), "Keep mode changed gallery dates");
            try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(fixture, "r")) {
                require(descriptor != null && Os.fstat(descriptor.getFileDescriptor()).st_mtime
                        == historicalTime / 1000, "Keep mode changed file time");
            }
            result.putBoolean("keep_timestamp_passed", true);
            long start = System.currentTimeMillis() / 1000;
            updatedSource = ExifRotation.overwrite(getTargetContext(), updatedSource, 90, true);
            Bitmap repeatedPreview = MainActivity.loadFreshThumbnail(resolver, updatedSource, 16);
            require(repeatedPreview.getWidth() == 32 && repeatedPreview.getHeight() == 16,
                    "Preview did not reflect a repeated overwrite");
            repeatedPreview.recycle();
            result.putBoolean("fresh_overwrite_preview_passed", true);
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
