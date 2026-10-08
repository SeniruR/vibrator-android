# Haptic Diagnostic Tester

Minimal Jetpack Compose Android app that plays a video while one of the A–E haptic WAVs from haptic-groundtruth drives the phone's vibration motor (Samsung A04 recommended).

Video workflow:

1. Run the haptic-groundtruth Colab and download the zip (it is named after the video).
2. Copy the zip to the phone and extract it.
3. Open the app, tap **Load folder**, and pick the extracted folder. The video and the A–E WAVs load automatically; anything inside `components/` is ignored.
4. Press Play and switch between A–E (also available in Fullscreen) to compare algorithms.

The app plays each WAV as written, including the quiet continuous layer under the accents. On motors without amplitude control, strength is rendered as an on/off duty cycle.

### Build and install from terminal

Run these from this folder (`vibrator-android`), with the phone connected and USB debugging allowed:

```cmd
.\build.cmd
.\install.cmd
```

`build.cmd` auto-creates `local.properties` when it finds the SDK (default: `%LOCALAPPDATA%\Android\Sdk`) and runs a clean debug build. For a faster incremental build, run `.\.gradle-wrapper\gradle-8.7\bin\gradle.bat assembleDebug` instead.

`install.cmd` uses the SDK's `adb` and auto-uninstalls if signatures conflict (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). If `adb devices` lists the phone as `unauthorized`, unlock it and accept the "Allow USB debugging?" prompt.

**If you see `SDK location not found`:** Android SDK is not installed yet. Run `setup-android.cmd`, open Android Studio once to finish the setup wizard, then build again.

Notes:
- This project targets Android API 31+. Adjust `minSdk` in `app/build.gradle.kts` if needed.
- Tests should be done on the physical device; emulator haptics are unreliable.
