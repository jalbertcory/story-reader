# Story Reader

An Android e-reader for EPUB books with text-to-speech and Nextcloud sync.

## Features

- EPUB parsing and rendering via [Readium Kotlin Toolkit](https://readium.org)
- Local library management with reading progress tracking
- Text-to-speech with synchronized word highlighting
- Cross-device reading progress sync via Nextcloud WebDAV
- Customizable reading preferences (font size, theme, font family)
- Reading statistics and session tracking
- Secure credential storage using Android Keystore (AES-256-GCM)

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
2. Click the green **Run** button (▶), or press **Ctrl+R** (macOS: **⌃R**).
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
```

## Testing

Unit tests run on the JVM using [Robolectric](http://robolectric.org) — no emulator required.

```bash
./gradlew test
```

HTML reports are written to `app/build/reports/tests/`.

## Linting

```bash
./gradlew lint
```

Reports are written to `app/build/reports/lint-results-*.html`.

## CI

GitHub Actions runs lint, unit tests, and a debug build on every push and pull request to `main` or `develop`. See [`.github/workflows/ci.yml`](.github/workflows/ci.yml).

## Architecture

- **UI:** Jetpack Compose with Material 3
- **Navigation:** Jetpack Navigation Compose
- **State:** MVVM with `StateFlow`-backed ViewModels
- **Database:** Room (SQLite)
- **EPUB rendering:** Readium 3 (`EpubNavigatorFragment` embedded in Compose via `AndroidViewBinding`)
- **Sync:** WorkManager + dav4jvm (WebDAV)
- **Security:** Android Keystore for credential encryption

## Nextcloud sync setup

1. Open the app and navigate to **Sync Settings** from the library screen.
2. Enter your Nextcloud server URL, username, and an [app password](https://docs.nextcloud.com/server/latest/user_manual/en/session_management.html#managing-devices).
3. Reading progress is synced automatically in the background when on Wi-Fi.

Credentials are encrypted with AES-256-GCM and stored in Android Keystore — they never leave the device in plaintext.
