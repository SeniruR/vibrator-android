@echo off
REM Wrapper for build.ps1 — works when PowerShell script execution is disabled.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
