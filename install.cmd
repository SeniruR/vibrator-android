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
echo.

echo Installing %APK%
"%ADB%" install -r "%APK%"
if %ERRORLEVEL% equ 0 goto :done

echo.
echo Install failed — often caused by a previous build signed with a different key.
echo Uninstalling old app and retrying...
"%ADB%" uninstall com.example.haptictester
"%ADB%" install -r "%APK%"

:done
endlocal
