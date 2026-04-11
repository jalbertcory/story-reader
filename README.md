# Story Reader

A full-featured Android e-reader for EPUB books with text-to-speech, reading statistics, Android Auto support, and Nextcloud sync.

## Features

### Reading
- EPUB parsing and rendering via [Readium Kotlin Toolkit](https://readium.org)
- Customizable reading preferences — font size, font family, and themes (Default, Dark, Sepia, Night)
- Full-screen reading with tap-to-toggle chrome and automatic fullscreen on page turn
- Table of contents navigation with chapter tracking in the status bar
- Persistent reading position — picks up exactly where you left off

### Text-to-Speech
- TTS playback with real-time word highlighting synced to the current page
- Page automatically turns when the highlight crosses a page boundary
- Configurable speed (default 1.5x), pitch, engine, and voice selection
- Media notification controls (play, pause, skip) — works with Bluetooth headphones and watches
- Android Auto integration — browse your library and start TTS playback from your car

### Library Management
- Import EPUBs from Android's file picker, including providers like Google Drive, or browse remote catalogs such as Nextcloud
- Cover art extraction and display
- Long-press any book for detailed stats, session history, metadata, and the option to remove it from the library (reading stats are preserved)

### Reading Statistics
- Automatic session tracking with idle detection (discards inactive time)
- Separate tracking for manual reading and TTS listening
- Per-book stats: time read (manual + TTS), words read, manual WPM, session count, progress
- Global stats: all-time hours, words, and manual WPM
- Yearly goals with progress bars (hours and words)
- Monthly bar chart breakdown
- Year-over-year comparison

### Sync
- Cross-device reading progress sync via Nextcloud WebDAV
- Optional Google Drive app-data backup for reading progress sync
- Automatic background sync on Wi-Fi (hourly via WorkManager)
- Credentials encrypted with AES-256-GCM via Android Keystore

## Requirements

- JDK 21+
- Android SDK 36 (Android 15)
- Android Studio Ladybug or later (recommended)
- Device or emulator running Android 9 (API 28) or higher

## Running the app in Android Studio

### 1. Open the project

1. Launch Android Studio.
2. On the Welcome screen choose **Open**, or go to **File > Open**.
3. Select the `story-reader` directory and click **OK**.
4. Wait for Gradle sync to finish (progress bar in the bottom status bar).

### 2. Create an emulator

1. Go to **Tools > Device Manager** (or click the phone icon in the right toolbar).
2. Click **Create Virtual Device**.
3. Choose a phone profile — **Pixel 8** is a good default — then click **Next**.
4. Select a system image with **API 28 or higher**. API 35 (Android 15) is recommended to match the target SDK. Click **Download** next to the image if it isn't already installed, then click **Next**.
5. Leave the AVD settings at their defaults and click **Finish**.

### 3. Run the app

1. In the toolbar, select your new emulator from the device dropdown (it will show the AVD name, e.g. `Pixel 8 API 35`).
2. Click the green **Run** button, or press **Ctrl+R** (macOS: **Cmd+R**).
3. Android Studio will build the app, start the emulator, and install the APK automatically.

### Command line (alternative)

```bash
# Build and install a debug APK on a running emulator or connected device
./gradlew installDebug
```

### Build only

```bash
./gradlew assembleDebug       # debug APK
./gradlew assembleRelease     # release APK (requires signing config)
./gradlew bundleRelease       # release App Bundle (requires signing config)
```

## Testing

Unit tests run on the JVM using [Robolectric](http://robolectric.org) — no emulator required.

```bash
./gradlew test
```

HTML reports are written to `app/build/reports/tests/`.

Instrumentation tests run on a connected emulator or device and cover Compose reader flows that are prone to UI regressions.

```bash
./gradlew connectedDebugAndroidTest
```

## Linting

```bash
./gradlew lint
```

Reports are written to `app/build/reports/lint-results-*.html`.

## CI

GitHub Actions runs lint, unit tests, and a debug build on every push and pull request to `main` or `develop`. See [`.github/workflows/ci.yml`](.github/workflows/ci.yml).

## Signed Release Bundles In GitHub Actions

The repository also includes a manual GitHub Actions workflow for building a signed release App Bundle (`.aab`) without storing signing secrets in the repo workspace.

Create these repository secrets before running [`.github/workflows/release-bundle.yml`](.github/workflows/release-bundle.yml):

- `ANDROID_UPLOAD_KEYSTORE_B64` — base64-encoded contents of your upload keystore
- `ANDROID_KEYSTORE_PASSWORD` — keystore password
- `ANDROID_KEY_ALIAS` — key alias, for example `storyreader`
- `ANDROID_KEY_PASSWORD` — key password

To create the base64 secret locally:

```bash
base64 < "$HOME/keys/story-reader-upload.jks" | tr -d '\n'
```

After adding the secrets, run the **Release Bundle** workflow from the GitHub Actions tab. The signed `.aab` artifact will be uploaded to the workflow run and can be used for Play internal testing.

## Architecture

- **UI:** Jetpack Compose with Material 3
- **Navigation:** Jetpack Navigation Compose
- **State:** MVVM with `StateFlow`-backed ViewModels
- **Database:** Room (SQLite) with versioned migrations
- **EPUB rendering:** Readium 3 (`EpubNavigatorFragment` embedded in Compose)
- **TTS:** Readium TTS Navigator with Media3 `MediaLibraryService`
- **Sync:** WorkManager + dav4jvm (WebDAV)
- **Security:** Android Keystore for credential encryption
- **Image loading:** Coil

## Nextcloud sync setup

1. Open the app and navigate to **Settings** from the library screen.
2. Enter your Nextcloud server URL, username, and an [app password](https://docs.nextcloud.com/server/latest/user_manual/en/session_management.html#managing-devices).
3. Reading progress is synced automatically in the background when on Wi-Fi.

Credentials are encrypted with AES-256-GCM and stored in Android Keystore — they never leave the device in plaintext.

## Google Drive sync setup

1. Open the app and navigate to **Settings** from the library screen.
2. Connect Google Drive under **Sync Providers**.
3. The app stores sync backups in your app-specific Google Drive data area.

Story Reader does not add its own end-to-end encryption layer to the backup JSON stored there.

To import an EPUB from Google Drive, use the library import button and pick the file through Android's system document picker.
