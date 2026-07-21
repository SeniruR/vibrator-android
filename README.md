# Haptic Diagnostic Tester

Minimal Jetpack Compose Android app to test vibrator amplitude and duty cycle on a device (Samsung A04 recommended).

The app now has two playback modes:

- Audio mode: load an audio file and let the app analyze it for haptic events.
- Video mode: load a `video.mp4` plus a haptic track (WAV from haptic-groundtruth or JSON from `process_video.py`), then play the video while the track drives vibrations.

Quick start:

1. Open the `HapticDiagnosticTester` folder in Android Studio.
2. Let Android Studio sync Gradle and install required plugins.
3. Connect your Samsung A04 with USB debugging enabled.
4. Run the `app` configuration.

Video workflow:

1. Open the app and go to the **Video + Haptic** tab.
2. Choose a video file (MP4).
3. Choose a haptic track — **WAV** (from haptic-groundtruth Colab) or **JSON** (from `model_json/process_video.py`).
4. Press Play. Video audio plays on speakers; the haptic track drives the motor.

### Build from terminal

**If you see `SDK location not found`:** Android SDK is not installed yet. Run:

```cmd
setup-android.cmd
```

Open Android Studio once after install and finish the setup wizard. Then build:

```cmd
.\build.cmd
install.cmd
```

If `.\build.ps1` is blocked by PowerShell execution policy, use `.\build.cmd` instead.

`build.cmd` auto-creates `local.properties` when it finds the SDK (default: `%LOCALAPPDATA%\Android\Sdk`).

`install.cmd` uses the SDK's `adb` and auto-uninstalls if signatures conflict (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).

Notes:
- This project targets Android API 31+. Adjust `minSdk` in `app/build.gradle.kts` if needed.
- The app calls `vibrator.cancel()` before issuing new commands and enforces a 100ms throttle (max ~10Hz).
- Tests should be done on the physical device; emulator haptics are unreliable.
