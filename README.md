# Haptic Diagnostic Tester

Minimal Jetpack Compose Android app to test vibrator amplitude and duty cycle on a device (Samsung A04 recommended).

The app now has two playback modes:

- Audio mode: load an audio file and let the app analyze it for haptic events.
- Video mode: load a `video.mp4` plus a generated haptic JSON map such as `output_haptic_map.json`, then play the video while the map drives vibrations.

Quick start:

1. Open the `HapticDiagnosticTester` folder in Android Studio.
2. Let Android Studio sync Gradle and install required plugins.
3. Connect your Samsung A04 with USB debugging enabled.
4. Run the `app` configuration.

Video workflow:

1. Open the app.
2. In the Video Playback Mode card, choose a video file.
3. Choose the matching haptic JSON file.
4. Press Play, Pause, or Stop.

in vscode, use below ps commands.
1. .\build.ps1
2. connect android device in debugging state
3. adb install -r .\app\build\outputs\apk\debug\app-debug.apk


Notes:
- This project targets Android API 31+. Adjust `minSdk` in `app/build.gradle.kts` if needed.
- The app calls `vibrator.cancel()` before issuing new commands and enforces a 100ms throttle (max ~10Hz).
- Tests should be done on the physical device; emulator haptics are unreliable.
