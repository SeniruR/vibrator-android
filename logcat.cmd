@echo off
setlocal
set ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe

if not exist "%ADB%" (
  echo adb not found at %ADB%
  exit /b 1
)

if /I "%~1"=="clear" (
  "%ADB%" logcat -c
  echo Logcat buffer cleared.
  exit /b 0
)

echo Watching haptic logs. Stop with Ctrl+C.
echo   logcat.cmd clear   - wipe old logs before a clean test run
echo.
"%ADB%" logcat -s VideoHapticController:D HapticController:D
exit /b 0
