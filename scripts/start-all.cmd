@echo off
rem telbank-rbac one-click start (Windows wrapper, PowerShell 5.1+)
rem Usage: scripts\start-all.cmd [--temp-db] [--init-db] [-ApiPort 8080] [-FrontPort 5173]
rem Actual logic lives in start-all-windows.ps1
setlocal enabledelayedexpansion
set "ARGS="
:loop
if "%~1"=="" goto run
set "A=%~1"
if /I "!A!"=="--temp-db" (set "ARGS=!ARGS! -TempDb"
) else if /I "!A!"=="--init-db" (set "ARGS=!ARGS! -InitDb"
) else if /I "!A!"=="--help" (set "ARGS=!ARGS! -Help"
) else if /I "!A!"=="-h" (set "ARGS=!ARGS! -Help"
) else (set "ARGS=!ARGS! !A!")
shift
goto loop
:run
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-all-windows.ps1" !ARGS!
exit /b %ERRORLEVEL%
