# Photo Rotator

A lightweight Android app that lets you select one or more photos in the system photo picker, rotate them 90° counterclockwise, and save copies to `Pictures/PhotoRotator`. Originals are kept unchanged.

## Features

- **Rotate and save a copy**: rotates image pixels and re-encodes the image. JPEG output uses 95% quality; this mode does not guarantee preservation of all EXIF metadata.
- **Rotate while preserving EXIF**: supports JPEG, PNG, and WebP. The app reads the local original and updates only the EXIF orientation in the copy, without re-encoding image pixels. It checks GPS, camera make/model, and original capture time. Photo and photo-location access are required; the app requests these permissions on first use. If the original cannot be accessed or metadata verification fails, no copy is created.
- **Batch processing**: select multiple photos and see the result for each item.
- **System photo picker**: no folder selection or whole-directory access is required. Cloud photos that are not downloaded locally may not work in EXIF-preserving mode.

EXIF-preserving mode temporarily copies the photo into the app cache, so allow roughly as much free cache space as the source file size. Some image viewers ignore EXIF orientation and may not display the rotated direction.

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

EXIF-preserving rotation uses AndroidX `ExifInterface`. Before and after writing, the app compares GPS coordinates and time, camera make/model, and original capture time. Failed or incomplete copies are removed. The app never overwrites the original.

## License

No license has been specified yet.
