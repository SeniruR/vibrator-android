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

if /I "%~1"=="json" goto :json_help

echo Watching haptic logs. Stop with Ctrl+C.
echo   logcat.cmd clear   - wipe buffer before a clean test run
echo   logcat.cmd json    - show JSON load/play workflow
echo.
"%ADB%" logcat -s VideoHapticController:D HapticController:D
exit /b 0

:json_help
echo.
echo === JSON haptic map test workflow ===
echo.
echo 1. Run:  logcat.cmd clear
echo 2. Run:  logcat.cmd          ^(leave this window open^)
echo 3. On phone: Video tab - tap JSON - pick videoplayback_output_haptic_map*.json
echo    ^(NOT "Load pipeline events.json" — that is events.json / peak_ref only^)
echo 4. Load your MP4, press Play
echo.
echo Look for LOAD line:
echo   Loaded haptic track label=... format=JSON windows=... windowSizeMs=40 ...
echo.
echo During play, DBG lines use winStart/winCenter every ~300ms ^(Event-Trigger ON^).
echo JSON uses 40ms windows; sustained segments should show many consecutive srcInt^>0.
echo.
pause
exit /b 0
