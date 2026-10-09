@echo off
setlocal
REM Runs the local Gradle that build.cmd uses (8.7; the Android plugin needs 8.7+).
set SCRIPT_DIR=%~dp0
set GRADLE_VERSION=8.7
set GRADLE_DIR=%SCRIPT_DIR%.gradle-wrapper\gradle-%GRADLE_VERSION%

if not exist "%GRADLE_DIR%\bin\gradle.bat" (
  echo Gradle %GRADLE_VERSION% not found - downloading...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$d='%SCRIPT_DIR%.gradle-wrapper'; New-Item -ItemType Directory -Path $d -Force | Out-Null; $z=Join-Path $d 'gradle-%GRADLE_VERSION%.zip'; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile $z -UseBasicParsing; Expand-Archive -Path $z -DestinationPath $d -Force; Remove-Item $z -Force"
  if errorlevel 1 exit /b 1
)

pushd "%SCRIPT_DIR%"
call "%GRADLE_DIR%\bin\gradle.bat" %*
set RESULT=%ERRORLEVEL%
popd
exit /b %RESULT%
