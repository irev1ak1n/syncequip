@echo off
setlocal
cd /d "%~dp0"

rem Usage:  start-tour.bat YourMinecraftName [bots]
rem Starts the isolated localhost-only bot server with test players, then you join it with your own client.

set PLAYER=%~1
if "%PLAYER%"=="" (
    echo Usage: start-tour.bat YourMinecraftName [bots]
    exit /b 1
)
set BOTS=%~2
if "%BOTS%"=="" set BOTS=5

where node >nul 2>nul
if errorlevel 1 (
    echo Node.js 22 or newer is required. Install it from https://nodejs.org/
    exit /b 1
)
if not exist node_modules (
    echo Installing bot dependencies without running install scripts...
    call npm ci --ignore-scripts
    if errorlevel 1 exit /b 1
)
if not exist "%~dp0..\..\syncequip-bot-server\server.properties" (
    echo The isolated bot server does not exist yet. Run setup-bot-server.ps1 first.
    exit /b 1
)

node tour.js --player %PLAYER% --bots %BOTS%
exit /b %ERRORLEVEL%
