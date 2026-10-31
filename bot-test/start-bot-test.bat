@echo off
setlocal
cd /d "%~dp0"

rem Usage:  start-bot-test.bat [bots] [keep]
rem   bots  number of bots, 2 to 10 (default 5)
rem   keep  leave the server and bots running with a status page at http://localhost:3008

set BOTS=%~1
if "%BOTS%"=="" set BOTS=5
set EXTRA=
if /i "%~2"=="keep" set EXTRA=--keep

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

echo Starting bot test with %BOTS% bots. This server is localhost only.
node run-test.js --bots %BOTS% %EXTRA%
set CODE=%ERRORLEVEL%
echo.
echo Finished with exit code %CODE%. The report is in bot-test\reports\latest.json
exit /b %CODE%
