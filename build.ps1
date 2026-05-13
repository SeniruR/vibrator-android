$ErrorActionPreference = 'Stop'
$version = '8.4'
$gradleDir = ".\.gradle-wrapper\gradle-$version"

if (!(Test-Path "$gradleDir\bin\gradle.bat")) {
    Write-Host "Downloading Gradle $version..."
    New-Item -ItemType Directory -Path ".\.gradle-wrapper" -Force | Out-Null
    $zip = ".\.gradle-wrapper\gradle-$version.zip"
    Invoke-WebRequest -Uri "https://services.gradle.org/distributions/gradle-$version-bin.zip" -OutFile $zip -UseBasicParsing
    Write-Host "Extracting..."
    Expand-Archive -Path $zip -DestinationPath ".\.gradle-wrapper" -Force
    Remove-Item -Path $zip -Force
}

& "$gradleDir\bin\gradle.bat" clean assembleDebug
