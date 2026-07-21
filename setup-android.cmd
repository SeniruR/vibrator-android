@echo off
REM Install Android Studio (includes the Android SDK). Run once, then run build.cmd again.
echo This will install Android Studio via winget (~1 GB download).
echo After install, open Android Studio once and finish the setup wizard.
echo.
pause
winget install Google.AndroidStudio
echo.
echo When Android Studio setup is complete, run:  build.cmd
pause
