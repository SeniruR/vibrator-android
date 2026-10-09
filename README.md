# Haptic Diagnostic Tester

A small Android app that plays a video while one of the A–E haptic WAV files from the haptic-groundtruth pipeline drives the phone's vibration motor. Use it to feel and compare the algorithms on a real phone (tested on a Samsung A04).

This guide assumes a Windows PC and no Android experience. Follow the parts in order the first time; after that you only need part 4 (or part 5 to test a new clip).

---

## 1. What you need (one time)

| Thing | Why | How to check |
|---|---|---|
| Windows PC | The build scripts are Windows scripts | – |
| Java 17 (JDK) | Gradle, the build tool, runs on Java | `java -version` in a terminal prints `17.x` |
| Android Studio | Installs the Android SDK and `adb` (the tool that talks to the phone) | Folder `%LOCALAPPDATA%\Android\Sdk` exists |
| Android phone, Android 12 or newer | The app needs API 31+ | Settings → About phone → Software information |
| USB cable that carries data | Some cables only charge | The phone shows up in File Explorer when plugged in |

**Install Android Studio:** double-click `setup-android.cmd` in this folder, or run `winget install Google.AndroidStudio`. Then **open Android Studio once** and click through the setup wizard (Standard install). The wizard is what actually downloads the SDK; until it finishes, builds fail with `SDK location not found`.

**Java:** if `java -version` does not print 17, install a JDK 17 (for example [Eclipse Temurin 17](https://adoptium.net/)) and open a new terminal.

You do **not** need to open this project in Android Studio. Everything below runs from a terminal.

## 2. Prepare the phone (one time)

1. **Turn on Developer options:** Settings → About phone → Software information → tap **Build number** 7 times. Enter your PIN if asked. You'll see "Developer mode has been turned on".
2. **Turn on USB debugging:** Settings → Developer options → switch on **USB debugging**.
3. **Plug the phone into the PC.** Pull down the notification shade, tap the USB notification, and choose **File transfer**.
4. **Allow the PC:** a pop-up "Allow USB debugging?" appears on the phone. Tick **Always allow from this computer** and tap **Allow**. If you miss it, it comes back the next time `install.cmd` talks to the phone.

## 3. Open a terminal in this folder

All commands must run **inside the `vibrator-android` folder**, not the repo root. Running them from the wrong folder is the most common mistake.

- In File Explorer, open `haptic-groundtruth\vibrator-android`, click the address bar, type `powershell`, and press Enter.
- Or in an existing terminal: `cd C:\Users\<you>\Projects\haptic-groundtruth\vibrator-android`

## 4. Build and install

```powershell
.\build.cmd
.\install.cmd
```

(In Command Prompt, drop the `.\`: `build.cmd`, then `install.cmd`.)

**`build.cmd`** turns the code into an app file (APK).
- It finds the Android SDK and writes `local.properties` for you.
- The first run downloads Gradle and the libraries the app uses. Expect **5–15 minutes**, with long quiet stretches. That is normal; let it finish.
- When it ends you'll see `BUILD SUCCESSFUL` and `Build finished: app\build\outputs\apk\debug\app-debug.apk`.
- After you change code, `.\build.cmd fast` only rebuilds what changed and is much quicker. Use plain `.\build.cmd` (a clean build) if something looks stale.

**`install.cmd`** copies that APK onto the phone.
- It first checks a phone is connected and allowed, and tells you what to do if not.
- If an older copy signed with a different key is on the phone, it uninstalls it and installs again.
- When it ends you'll see `Success` and `Installed.` The app is called **Haptic Diagnostic Tester** in the phone's app list.

Run only one build at a time. Starting a second build while one is running makes Gradle wait for the first ("busy Daemon").

## 5. Test a clip

1. Run the haptic-groundtruth Colab notebook and download the zip. It is named after the video.
2. Copy the zip to the phone (File Explorer, or any file-sharing app) and extract it with the phone's file manager. You get one folder with the video and the `algorithm_a…e` WAV files at the top, plus a `components` folder.
3. Open the app, tap **Load folder**, and pick the extracted folder. The video and the WAVs load automatically. The app never opens `components`.
4. Press **Play**. Switch between **A–E** to compare algorithms; the switch is also available in **Fullscreen**.

The app plays each WAV exactly as written: vibration where the WAV has sound, none where it is silent. This phone's motor has no strength control, so the app makes weaker moments by switching the motor on and off quickly, and stronger moments by keeping it on longer.

## 6. Watching the app's logs (optional)

With the phone connected:

```powershell
.\logcat.cmd clear   # wipe old logs
.\logcat.cmd         # watch live; stop with Ctrl+C
```

Each line shows when the app changed the motor and at which video position. Useful when a vibration feels early, late, or missing.

## 7. When something goes wrong

| What you see | What it means | What to do |
|---|---|---|
| `SDK location not found` or `Android SDK not found` | Android Studio's setup wizard never finished | Open Android Studio, finish the wizard, run `.\build.cmd` again |
| `Unsupported class file major version`, or a Java version error | Wrong Java version | Install JDK 17, open a new terminal, check `java -version` |
| `is not recognized as an internal or external command` | Terminal is in the wrong folder | `cd` into `vibrator-android` (part 3) |
| Build sits for minutes with no output | First build downloading, or another build is running | Wait. If a second build is stuck on "busy Daemon", close it and run `.\.gradle-wrapper\gradle-8.7\bin\gradle.bat --stop`, then build once |
| `No usable phone found`, or `adb devices` lists nothing | Phone not connected for debugging | Use a data cable, choose **File transfer**, check USB debugging is on (part 2) |
| Phone listed as `unauthorized` | The PC was not allowed yet | Unlock the phone and accept "Allow USB debugging?". No pop-up? Developer options → **Revoke USB debugging authorizations**, unplug, plug back in |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Old copy signed with a different key | `install.cmd` handles it automatically; if it still fails, uninstall the app on the phone and run it again |
| Video plays but the timeline is missing, or seeking jumps to the start | The video file has no length in its header | Re-download the zip from the current notebook; it rewrites such videos. The app shows a message when a video cannot seek |
| Some A–E buttons do nothing | That WAV is not in the folder | Check you picked the extracted folder, not the zip or `components` |

Tip: don't pipe the build into other commands (for example `build.cmd | Select-Object -Last 60`). The output is held back until the build ends, so it looks frozen.

## For developers

- `build.cmd` runs `build.ps1` with PowerShell's script policy bypassed, so it works on locked-down machines. Gradle 8.7 lives in `.gradle-wrapper\` (downloaded on first build); `gradlew.bat` runs the same Gradle, e.g. `.\gradlew.bat assembleDebug`.
- The APK is a debug build (`app\build\outputs\apk\debug\app-debug.apk`, package `com.example.haptictester`). `minSdk` is 31 in `app/build.gradle.kts`.
- Test haptics on a physical phone; emulator vibration is not representative.
