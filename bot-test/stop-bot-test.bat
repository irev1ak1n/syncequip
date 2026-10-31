@echo off
setlocal
cd /d "%~dp0"
rem Stops the isolated bot server and the test runner if they are still running.

for %%N in (server harness) do (
    if exist ".run\%%N.pid" (
        set /p PID=<".run\%%N.pid"
        call :kill %%N
    )
)
echo Done.
exit /b 0

:kill
setlocal enabledelayedexpansion
set /p PID=<".run\%1.pid"
taskkill /PID !PID! /T /F >nul 2>nul && echo Stopped %1 (PID !PID!) || echo %1 was not running
del ".run\%1.pid" >nul 2>nul
endlocal
exit /b 0
