@echo off
setlocal enabledelayedexpansion

call build.bat
if %ERRORLEVEL% neq 0 (
    echo Bob der Baumeister failed to build
    exit /b 1
)

for /L %%i in (1,1,300) do (
    echo Iteration %%i
    java -cp "bin;bin/de/codecoverage/config/*" de.codecoverage.config.TestDriver 3 > output.log 2>&1
    findstr /i "Exception" output.log >nul
    if !errorlevel! equ 0 (
        echo Fehler im Iteration %%i.
        exit /b 1
    )
)

exit /b 0
