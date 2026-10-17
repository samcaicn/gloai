@echo off
chcp 65001 >nul
rem WeAuto 本地视觉语义服务一键启动（llama.cpp Vulkan GPU）
rem 用途：给「本地视觉语义识别」引擎提供 OpenAI 兼容接口（默认 127.0.0.1:8080）
rem 也可在设置页勾选「识别时自动拉起本地视觉服务」，由软件代拉。

set "SERVER_EXE=%~dp0llama-server.exe"
set "MODEL_DIR=%~dp0models"
set "HOST=127.0.0.1"
set "PORT=8080"

rem 本机未内置 server 时，尝试用户已编译的 Vulkan 版
if not exist "%SERVER_EXE%" set "SERVER_EXE=C:\Users\vangq\buildtools\llama-vulkan\llama-server.exe"
if not exist "%SERVER_EXE%" (
    echo [错误] 找不到 llama-server.exe：请将其放到本目录，或编辑本脚本指定路径。
    pause & exit /b 1
)

set "GGUF=%MODEL_DIR%\HunyuanOCR.Q4_K_M.gguf"
set "MMPROJ=%MODEL_DIR%\HunyuanOCR.mmproj-q8_0.gguf"
rem 未按默认名放置时，取目录里第一个主模型 / 第一个 mmproj（mmproj 可能在文件名中间）
if not exist "%GGUF%" for /f "delims=" %%f in ('dir /b /a-d /o:n "%MODEL_DIR%\*.gguf" 2^>nul ^| findstr /v /i "mmproj" ^| findstr /v /i "^\."') do set "GGUF=%MODEL_DIR%\%%f" & goto gguf_ok
:gguf_ok
if not exist "%MMPROJ%" for /f "delims=" %%f in ('dir /b /a-d /o:n "%MODEL_DIR%\*.gguf" 2^>nul ^| findstr /i "mmproj"') do set "MMPROJ=%MODEL_DIR%\%%f" & goto mmproj_ok
:mmproj_ok

if not exist "%GGUF%" (
    echo [错误] 模型目录无 GGUF：%MODEL_DIR%
    echo        下载示例：curl -L -C - -o Qwen3-VL-2B-Instruct-Q4_K_M.gguf ^
      "https://hf-mirror.com/unsloth/Qwen3-VL-2B-Instruct-GGUF/resolve/main/Qwen3-VL-2B-Instruct-Q4_K_M.gguf"
    pause & exit /b 1
)

echo 启动本地视觉服务: http://%HOST%:%PORT%
echo   模型: %GGUF%
echo   视觉投影: %MMPROJ%
echo 按 Ctrl+C 停止
"%SERVER_EXE%" -m "%GGUF%" --mmproj "%MMPROJ%" --host %HOST% --port %PORT% -ngl 99 -c 4096
pause
