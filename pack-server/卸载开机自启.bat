@echo off
rem ============================================================
rem  Remove the Gridea pack service startup task.
rem  Self-elevates once, then runs uninstall-autostart.ps1
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

set "PS1=%~dp0uninstall-autostart.ps1"

if not exist "%PS1%" (
    echo [ERROR] uninstall-autostart.ps1 is missing next to this file.
    pause
    exit /b 1
)

set "PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if not exist "%PS%" set "PS=powershell"

"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%PS1%"
