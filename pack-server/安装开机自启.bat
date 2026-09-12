@echo off
rem ============================================================
rem  Install the Gridea pack service as a startup task.
rem
rem  Self-elevates once, then hands over to install-autostart.ps1
rem  (all Chinese messages live there, since PowerShell handles
rem  UTF-8 reliably and cmd.exe does not).
rem
rem  Why admin: registering a task that runs as SYSTEM requires it.
rem ============================================================

net session >nul 2>&1
if errorlevel 1 (
    echo Requesting administrator privileges...
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    if errorlevel 1 (
        echo.
        echo [ERROR] Elevation was declined.
        echo         Right-click this file and pick "Run as administrator".
        pause
    )
    exit /b
)

set "PS1=%~dp0install-autostart.ps1"

if not exist "%PS1%" (
    echo [ERROR] install-autostart.ps1 is missing next to this file.
    pause
    exit /b 1
)

set "PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if not exist "%PS%" set "PS=powershell"

"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%PS1%"
