@echo off
setlocal
set ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
set APK=%~dp0app\build\outputs\apk\debug\app-debug.apk

if not exist "%ADB%" (
  echo adb not found at %ADB%
  echo Install Android SDK Platform-Tools in Android Studio, then retry.
  exit /b 1
)

if not exist "%APK%" (
  echo APK not found. Run build.cmd first.
  exit /b 1
)

echo Checking device...
"%ADB%" devices
"%ADB%" get-state 1>nul 2>nul
if errorlevel 1 (
  echo.
  echo No usable phone found.
  echo  - Nothing listed above: check the cable, set USB mode to "File transfer", and turn on USB debugging.
  echo  - Listed as "unauthorized": unlock the phone, tick "Always allow from this computer", tap Allow.
  echo Then run install.cmd again.
  exit /b 1
)

echo Installing %APK%
"%ADB%" install -r "%APK%"
if %ERRORLEVEL% equ 0 goto :done

echo.
echo Install failed - often caused by a previous build signed with a different key.
echo Uninstalling old app and retrying...
"%ADB%" uninstall com.example.haptictester
"%ADB%" install -r "%APK%"
if errorlevel 1 exit /b 1

:done
echo.
echo Installed. Open "Haptic Diagnostic Tester" on the phone.
endlocal
