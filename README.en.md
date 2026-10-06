<div align="center">

<img src="assets/icon.svg" alt="ZViewer icon" width="96" height="96">

# ZViewer

[简体中文](README.md) | [**English**](README.en.md)

[![Release](https://img.shields.io/github/v/release/Zo-Live/ZViewer?label=release&style=flat-square)](https://github.com/Zo-Live/ZViewer/releases/latest) [![License](https://img.shields.io/github/license/Zo-Live/ZViewer?style=flat-square)](LICENSE) [![Platform](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white&style=flat-square)](#install-and-start-reading) [![Kotlin](https://img.shields.io/badge/Kotlin-2.1.21-7F52FF?logo=kotlin&logoColor=white&style=flat-square)](https://kotlinlang.org) [![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white&style=flat-square)](https://developer.android.com/compose)

**A native Android local comic reader with support for common archive formats, PDF, image folders, and video folders.**

</div>

## Install and Start Reading

Supports Android 10 and later; the universal APK works on mainstream phones and emulators.

1. On first launch, tap "Select library folder", navigate to your comic directory in the system file picker, tap "Use this folder", and grant access.
2. The app scans the directory and shows the cover and title of each comic; later launches open directly to this library.
3. Tap a comic to start reading. Tap the screen to show the floating progress bar and tap again to hide it; drag the progress bar or tap the page number to jump pages.
4. Pinch, double-tap, or use the zoom buttons for both images and videos. Videos autoplay by default. Tap the picture to show the toolbar and video seek bar above it, with a play/pause button on the left and current time / duration above the bar. Drag the seek bar to change position. Tap the picture again to hide both.
5. In reading settings, switch between vertical continuous, horizontal paging, and right-to-left paging. Use the system edge back gesture or the back button in the top-left corner to return to the library.
6. If something goes wrong, enable "Diagnostic log" in Settings and pick a log folder, then share the log file with the developer after reproducing the issue.

Android usually does not allow granting access to the internal storage root, the Download root, or Android/data. Create a specific comic subfolder, such as `Download/comics`, and select that. The app does not need the "All files access" permission.

## Features

| Category        | Support                                                                                                                                                                    |
| --------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Comic sources   | ZIP / CBZ, RAR / CBR, 7Z, PDF, image folders, video folders                                                                                                                |
| Static images   | JPG, JPEG, PNG, WebP, BMP, HEIF, HEIC, AVIF                                                                                                                                |
| Animated images | APNG, Animated WebP, GIF, Animated AVIF                                                                                                                                    |
| Video           | MP4, M4V, MKV, WebM, AVI, MOV, 3GP, MPG, MPEG, TS, MTS, M2TS, FLV, WMV, OGV, and more                                                                                      |
| Library         | Recursive scan, natural sorting, HD covers, search, reading-status filter, recently read, favorites, 2–5 column grid                                                      |
| Reading         | Continuous vertical or paged horizontal images, smooth vertical/horizontal video switching, mixed content, zoom, page jumping, per-video positions, seamless image loops, keep screen on |
| Appearance      | Follow system / light / dark theme, preset accent colors, custom accent color, separate light/dark reading backgrounds                                                     |
| System          | Immersive reading, portrait and landscape, remembered library permission                                                                                                   |
| Diagnostics     | Optional diagnostic log written to the app-specific directory by default, or any writable folder you pick                                                                  |
| Storage         | Cache clearing and automatic reclamation                                                                                                                                   |

While reading, the zoom range goes from fit-to-screen up to 5x, and you can return to fit-to-screen at any time.

## Format Support and Features

- Files inside archives are sorted naturally by file name, so `2.jpg` comes before `10.jpg`.
- Images directly contained in an image folder form one book; each subdirectory is scanned separately.
- A folder or archive containing only videos forms one book; mixed image/video content opens in the same pager and follows the vertical/horizontal setting, with the video seek bar shown only on video pages. Archive member names are decoded as UTF-8, so Chinese filenames work; a finished video replays from the start when you return to it after auto-advancing. Video archives are extracted to the app's temporary cache before playback.
- Video codec support depends on the device's Media3 / system decoders.
- PDFs are supported only at the library root or in uncompressed subdirectories; any readable archive containing a PDF asks you to extract it before reading.
- Videos autoplay in order and remember an individual position. Settings can loop one video or open videos in preview mode until the play button starts playback. Video folders and archives use a frame from the naturally sorted first video as their cover.
- With Loop mode enabled, the first and last image pages are adjacent in both directions and scroll naturally across the boundary; videos cross the boundary with the same transition. Double-tap Clear reading history in Settings to remove all progress without affecting favorites.
- Settings can enable a diagnostic log and pick its folder (the app-specific directory by default, or any writable folder such as Download via the system picker). The log records app startup, library scans/opens, video switching, playback errors, and uncaught exceptions to help diagnose issues.
- 7Z solid archives need their whole content prepared on first open, so larger books take longer to open.
- Encrypted archives, multi-volume files, password-protected PDFs, and DRM content are not supported yet; decrypt or extract them to a folder first.
- Whether HEIF / HEIC can be displayed depends on the device, and animated HEIF is not supported; APNG, GIF, animated WebP, and animated AVIF all play normally.
- If files are too large, have too many pages, or are corrupted, the app reports a clear error instead of freezing or filling up storage.
- Reading progress is saved per page (or per video index); after the app is killed by the system, reopening it continues from the saved page and video position.

## Privacy and Data

ZViewer does not request network permission and contains no accounts or ads. Reading history, covers, and caches are stored locally on the device and can be cleared in the app; when the cache reaches about 1.5 GiB it is reclaimed automatically.

## Local Build

Requires JDK 17, Android SDK 36, and network access (only for downloading dependencies at build time). The project ships with a Gradle 8.11.1 wrapper.

```sh
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME="$HOME/Android"
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

If your SDK path differs, adjust `ANDROID_HOME`, or set `sdk.dir` in an untracked `local.properties`.

## Release Build and Signing

The key is located at `.signing/zviewer-release.jks` with alias `zviewer`; passwords are passed via `ZVIEWER_STORE_PASSWORD` and `ZVIEWER_KEY_PASSWORD`.

```sh
./gradlew :app:assembleRelease
```

The artifact is `app/build/outputs/apk/release/app-release.apk`.

The current release is `1.3.1` (`versionCode=9`). The distribution APK is `dist/ZViewer-1.3.1-release.apk`, with its SHA-256 checksum in `dist/ZViewer-1.3.1-release.apk.sha256`.

The private key and passwords must not be committed to Git, and `.signing/` must be backed up separately and securely. Subsequent releases must use the same key and an incremented `versionCode` so that installed apps can be upgraded in place with their data preserved; a new key generated on a new machine means fresh installs only.

## Testing and Implementation Docs

- [Development plan](docs/开发计划.md) · [Architecture and compatibility](docs/架构与兼容性.md) · [Test log](docs/测试记录.md)
- Core logic tests are in `app/src/test/`, device format integration tests are in `app/src/androidTest/`, and fixtures are generated by `tools/generate_fixtures.py`.

## Open Source Components

Built on open source components including [AndroidX / Compose](https://github.com/androidx/androidx), [Media3](https://github.com/androidx/media), the [Android Open Source Project](https://android.googlesource.com/platform/frameworks/base/), [Kotlin](https://github.com/JetBrains/kotlin), [libarchive](https://github.com/libarchive/libarchive), [APNG4Android](https://github.com/penfeizhou/APNG4Android), and [libavif](https://github.com/AOMediaCodec/libavif).

## License

MIT
