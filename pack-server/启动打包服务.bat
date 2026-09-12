@echo off
chcp 65001 >nul
setlocal

rem ============================================================
rem  Gridea Pro mobile - desktop pack service launcher
rem  Keep this window open while syncing from the phone.
rem
rem  Why admin: Windows only lets an elevated process listen on
rem  an address reachable from the LAN (http://+:port). A normal
rem  process can only bind loopback, and the phone could never
rem  connect. The firewall rule we add also needs admin.
rem ============================================================

rem --- elevate once, then continue below ---
net session >nul 2>&1
if errorlevel 1 (
    echo Requesting administrator privileges...
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    if errorlevel 1 (
        echo.
        echo [ERROR] Elevation was declined or failed.
        echo         Right-click this file and pick "Run as administrator".
        pause
    )
    exit /b
)

set "SCRIPT=%~dp0gridea-pack-server.ps1"

if not exist "%SCRIPT%" (
    echo [ERROR] gridea-pack-server.ps1 is missing next to this file.
    echo         Both files must stay in the same folder.
    pause
    exit /b 1
)

rem Prefer the absolute path so a broken PATH cannot stop us.
set "PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if not exist "%PS%" set "PS=powershell"

"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%"

echo.
echo Service stopped.
pause
endlocal
