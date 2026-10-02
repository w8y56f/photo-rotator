package dev.stone.photorotator;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import androidx.exifinterface.media.ExifInterface;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

/** Device regression: use an already granted picker URI; never modifies its original. */
public class ExifRotationInstrumentation extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments = args; start(); }

    @Override public void onStart() {
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
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
