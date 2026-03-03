@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul 2>nul

REM ============================================================
REM  Complete pipeline: Build + Upload + Show update instructions
REM  Usage: scripts\rebuild-sandbox.bat [amd64|arm64]
REM ============================================================

set ROOT_DIR=%~dp0..
set ARCH=%~1
if "%ARCH%"=="" set ARCH=amd64

echo.
echo ================================================================
echo   Sandbox VM Image Rebuild Pipeline
echo   Architecture: %ARCH%
echo ================================================================
echo.

REM Step 1: Build
echo === STEP 1: Build image ===
echo.
call "%ROOT_DIR%\scripts\build-sandbox-in-wsl.bat" %ARCH%
if %ERRORLEVEL% neq 0 (
    echo.
    echo Build failed. Aborting.
    exit /b 1
)

REM Step 2: Check Python
echo.
echo === STEP 2: Upload to CDN ===
echo.

where python >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo ERROR: Python not found. Please install Python first.
    echo You can manually upload later: python scripts\upload-sandbox-image.py --arch %ARCH%
    exit /b 1
)

REM Check requests module
python -c "import requests" >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo Installing requests module...
    pip install requests -q
)

python "%ROOT_DIR%\scripts\upload-sandbox-image.py" --arch %ARCH%

echo.
echo ================================================================
echo   Pipeline complete!
echo
echo   Don't forget to update the CDN URL in:
echo     electron\libs\coworkSandboxRuntime.ts
echo
echo   Look for DEFAULT_SANDBOX_IMAGE_URL_%ARCH% and replace the URL
echo   with the one printed above.
echo ================================================================
echo.

endlocal
