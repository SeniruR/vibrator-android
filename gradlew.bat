@echo off
setlocal
set SCRIPT_DIR=%~dp0
set GRADLE_VERSION=8.1.1
set GRADLE_DIR=%SCRIPT_DIR%\.gradle-wrapper\gradle-%GRADLE_VERSION%

if not exist "%GRADLE_DIR%\bin\gradle.bat" (
  echo Gradle %GRADLE_VERSION% not found — downloading...
  powershell -NoProfile -Command "
    $u='https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip';
    $u=$u -replace '%GRADLE_VERSION%', '%GRADLE_VERSION%';
    $dst=Join-Path '%SCRIPT_DIR%' '.gradle-wrapper\gradle-%GRADLE_VERSION%.zip';
    New-Item -ItemType Directory -Path (Split-Path $dst) -Force | Out-Null;
    Invoke-WebRequest -Uri $u -OutFile $dst -UseBasicParsing;
    Add-Type -AssemblyName System.IO.Compression.FileSystem;
    [System.IO.Compression.ZipFile]::ExtractToDirectory($dst, Join-Path '%SCRIPT_DIR%' '.gradle-wrapper');
    Remove-Item $dst -Force
  "
)

"%GRADLE_DIR%\bin\gradle.bat" %*
