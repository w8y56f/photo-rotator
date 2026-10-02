# Photo Rotator

[简体中文](README.md)

A lightweight Android app that lets you select one or more photos in the system photo picker, rotate them by 90° or 180°, and either overwrite the originals or save copies to `Pictures/PhotoRotator`.

## Features

- **Rotation direction**: 90° counterclockwise (default), 90° clockwise, or 180°.
- **Save mode**: save a new image (default) to `Pictures/PhotoRotator`, or overwrite the original after Android grants write access.
- **EXIF information**: common EXIF data is retained, including GPS, camera make/model, and capture time. Pixels are re-encoded; proprietary maker data and embedded thumbnails may not be retained. If an overwrite fails, the app attempts to restore the original file.
- **Batch processing**: keep up to 50 photos selected at once, with previews, an expanded view, and individual removal before processing.
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

The system photo picker may return a virtual path or a transformed display name. For local Android photo-picker URIs, the app reads the original MediaStore image ID from the URI and checks the file size and MIME type before accessing the corresponding local original. It does not guess based on a filename alone. Other URI shapes use filename, size, and relative directory matching, and ambiguous matches are rejected.

EXIF handling uses AndroidX `ExifInterface`. Before and after writing, the app compares the common EXIF fields it copies and sets the image orientation to normal. Failed new copies are removed; failed overwrites attempt to restore the original file.

## License

No license has been specified yet.
