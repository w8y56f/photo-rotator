---
name: photo-rotator-release
description: Build and package a versioned APK for this Android project, then create and push a Git tag whose name exactly matches versionName (for example, 0.9.0). Use when asked to release, package, or tag Photo Rotator.
---

# Photo Rotator release

Use this skill only in the `photo-rotator` project. The project version is defined by `versionName` and `versionCode` in `app/build.gradle.kts`.

## Release procedure

1. Read `versionName` and `versionCode` from `app/build.gradle.kts`. Keep the tag and APK filename versioned from this source; do not hard-code a version in commands. Confirm `README.md` and `README.en.md` show the same version. If the user asked to change the version, update both Gradle and README files and commit that change before packaging.
2. Inspect `git status --short --branch`, the current branch, and existing tags. Do not create a release from uncommitted changes. If the exact version tag already exists, stop and report it instead of moving or replacing it. Confirm the commit to be tagged is on the intended branch and has been pushed to `origin` before pushing the tag.
3. Build the APK:

   ```sh
   ./gradlew :app:assembleDebug --console=plain
   ```

   This project currently has no release signing configuration. The debug APK is signed with Android's debug key; do not describe it as a production-signed APK. If a production release is requested, explain that a private release keystore and credentials must first be configured securely, and never create or commit a keystore or credentials.

4. Copy the built APK into the same output directory using the version name:

   ```text
   app/build/outputs/apk/debug/photo-rotator-<versionName>.apk
   ```

   For this release, the expected filename is `photo-rotator-0.9.0.apk`. Keep the original `app-debug.apk`. The output directory is ignored by Git.
5. Check that the versioned APK exists and is non-empty. Report its absolute path, file size, and SHA-256 digest. Do not commit the APK unless the user explicitly asks.
6. Create an annotated tag named exactly `<versionName>` on the release commit, then push only that tag:

   ```sh
   git tag -a <versionName> -m "Photo Rotator <versionName>"
   git push origin <versionName>
   ```

   Never add a `v` prefix. Do not push commits or publish a GitHub Release as part of this step unless the user also asks for those actions. If tag creation or push fails, report the actual repository state and do not force-push or move tags.
7. Verify the tag points to the intended commit and the versioned APK remains available. Summarize the tag, commit, APK path, signature type, and verification result.

## Version format

Use the Gradle `versionName` exactly, such as `0.9.0`, for both the APK suffix and Git tag. Keep `versionCode` as the monotonically increasing Android build number; do not derive it from the tag.
