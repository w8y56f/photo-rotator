# Photo Rotator

[简体中文](README.md)

A lightweight Android app that lets you pick photos or share them from a gallery, rotate them by 90° or 180°, and either overwrite the originals or save copies to `Pictures/PhotoRotator`.

## Features

- **Rotation direction**: 90° counterclockwise (default), 90° clockwise, or 180°.
- **Save mode**: overwrite the original (default) after Android grants write access, or save a new image to `Pictures/PhotoRotator`.
- **EXIF information**: common EXIF data is retained, including GPS, camera make/model, and capture time. Pixels are re-encoded; proprietary maker data and embedded thumbnails may not be retained. If an overwrite fails, the app attempts to restore the original file.
- **Batch processing**: keep up to 50 photos selected at once, with previews, an expanded view, and individual removal before processing.
- **Share from a gallery**: shared photos appear selected, ready for review before rotation. Sharing more than 50 photos rejects the entire batch and keeps any prior selection. Photos shared by other apps may lack some EXIF; overwrite is unavailable unless the local original can be identified.
- **Picking again**: supported systems preselect the current photos in the picker; older systems append new picks without duplicates.
- **System photo picker**: no folder selection or whole-directory access is required. Cloud photos that are not downloaded locally cannot be rotated.

Processing keeps temporary source and rotated files in the app cache and encodes JPEG/WebP at 95% quality, so allow sufficient free cache space. Overwriting requires approval in an Android confirmation prompt.

## Build

- Application ID: `dev.stone.photorotator`
- Version: `0.9.0` (versionCode `2`)
- Minimum Android version: Android 11 (API 30)
- Compile SDK: API 35
- Java: 11

Open the project in Android Studio or run the following command with the Android SDK installed:

```sh
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Implementation notes

The system photo picker may return a virtual path or a transformed display name. For local Android photo-picker URIs, the app reads the original MediaStore image ID from the URI and confirms that the local image exists before accessing it. Android document URIs are mapped to equivalent MediaStore URIs where possible. Other shared URIs are matched to local photos by name and verified by original path or file contents. If a gallery strips camera EXIF and changes the shared file size, identical JPEG image data can still identify the original. Overwrite is allowed only for a unique match. If no local original can be identified, saving a copy uses the bytes and EXIF supplied by the sharing app.

EXIF handling uses AndroidX `ExifInterface`. Before and after writing, the app compares the common EXIF fields it copies and sets the image orientation to normal. Failed new copies are removed; failed overwrites attempt to restore the original file.

## License

No license has been specified yet.
