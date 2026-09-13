@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul

:: =========================================================
:: WeAuto 启动器 (v3.25.1) — 已升级支持微信 4.x
:: 微信 3.9 / 4.x 均支持；Python 3.9 ~ 3.13
:: =========================================================

:: ---------------------------
:: 检查微信版本（支持 3.x 与 4.x）
:: ---------------------------
set "wxversion="
for %%K in (
    "HKLM\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\Weixin"
    "HKLM\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\WeChat"
) do (
    for /f "tokens=2,*" %%i in ('reg query %%K /v DisplayVersion 2^>nul ^| find "DisplayVersion"') do (
        set "wxversion=%%j"
        set "RegPath=%%K"
        goto :found_wxversion
    )
)
if not defined wxversion (
    echo ⚠️ 未检测到微信安装或无法读取注册表，跳过版本检查继续运行。
    echo ⚠️ 若启动后无法控制微信，请确认微信已安装（3.9 或 4.x 均支持）。
    timeout /t 3 /nobreak >nul
    goto :check_python
)
:found_wxversion
for /f "tokens=1 delims=." %%a in ("!wxversion!") do set "major=%%a"

if !major! lss 3 (
    echo ❌ 当前微信版本 !wxversion! 版本过低！
    echo ⚠️ 请安装微信 3.9 或 4.x 版本。
    echo ⚠️ 下载地址：https://dldir1v6.qq.com/weixin/Windows/WeChatSetup.exe
    echo.
    echo 🔄 若确信已安装正确版本，按任意键继续；否则关闭窗口退出。
    pause
    goto :check_python
)
if !major! gtr 4 (
    echo ⚠️ 当前微信版本 !wxversion! 未经此版本测试，可能存在兼容性问题。
    echo ⚠️ 已知支持：微信 3.9 / 4.x（WeAuto 已升级支持微信 4.x）。
    echo.
    echo 🔄 若确信无误，按任意键继续；否则关闭窗口退出。
    pause
    goto :check_python
)
echo ✅ 微信版本检查通过：!wxversion!（WeAuto 已支持微信 4.x）

:check_python

:: ---------------------------
:: 检查 Python 是否安装（支持 3.9 ~ 3.13）
:: ---------------------------
echo 🔍 检查Python环境...
python --version >nul 2>&1
if %errorlevel% neq 0 (
    echo ❌ Python 未安装或未添加到系统PATH！
    echo 请前往官网下载并安装 Python 3.9-3.13 版本
    echo 下载地址：https://www.python.org/downloads/
    echo ⚠️ 安装时请勾选 "Add Python to PATH" 选项
    pause
    exit /b 1
)

for /f "tokens=2,*" %%i in ('python --version 2^>^&1') do set "pyversion=%%i"
echo 检测到Python版本：%pyversion%

for /f "tokens=1,2,3 delims=." %%a in ("%pyversion%") do (
    set "py_major=%%a"
    set "py_minor=%%b"
    set "py_patch=%%c"
)

if "%py_major%" neq "3" (
    echo ❌ 不支持的Python主版本：%pyversion%（需 Python 3.x）
    pause
    exit /b 1
)
if %py_minor% lss 9 (
    echo ❌ Python版本过低：%pyversion%
    echo 最低要求：Python 3.9
    pause
    exit /b 1
)
if %py_minor% gtr 13 (
    echo ❌ Python版本过高：%pyversion%
    echo 支持版本：Python 3.9-3.13
    pause
    exit /b 1
)
echo ✅ Python版本检查通过：%pyversion% (满足 3.9-3.13 要求)

:: ---------------------------
:: 检查 pip 是否存在
:: ---------------------------
python -m pip --version >nul 2>&1
if %errorlevel% neq 0 (
    echo ❌ pip 未安装，请先安装 pip。
    pause
    exit /b 1
)

:: ---------------------------
:: 选择最快的 pip 源
:: ---------------------------
echo 🚀 正在检测可用镜像源...
python -m pip install --upgrade pip --only-binary=:all: --index-url https://mirrors.aliyun.com/pypi/simple/ --trusted-host mirrors.aliyun.com >nul 2>&1
if !errorlevel! equ 0 (
    set "SOURCE_URL=https://mirrors.aliyun.com/pypi/simple/"
    set "TRUSTED_HOST=mirrors.aliyun.com"
    echo ✅ 使用阿里源
    goto :INSTALL
)
python -m pip install --upgrade pip --only-binary=:all: --index-url https://pypi.tuna.tsinghua.edu.cn/simple --trusted-host pypi.tuna.tsinghua.edu.cn >nul 2>&1
if !errorlevel! equ 0 (
    set "SOURCE_URL=https://pypi.tuna.tsinghua.edu.cn/simple"
    set "TRUSTED_HOST=pypi.tuna.tsinghua.edu.cn"
    echo ✅ 使用清华源
    goto :INSTALL
)
python -m pip install --upgrade pip --only-binary=:all: --index-url https://pypi.org/simple >nul 2>&1
if !errorlevel! equ 0 (
    set "SOURCE_URL=https://pypi.org/simple"
    set "TRUSTED_HOST="
    echo ✅ 使用官方源
    goto :INSTALL
)
echo ❌ 无可用镜像源，请检查网络
pause
exit /b 1

:INSTALL
echo 🔄 正在安装依赖（优先在线镜像，自动匹配当前 Python 的 wheel）...

if "!TRUSTED_HOST!"=="" (
    set "IDX=!SOURCE_URL!"
) else (
    set "IDX=!SOURCE_URL! --trusted-host !TRUSTED_HOST!"
)

:: 微信 4.x 引擎已内置为 vendor/wechatauto，无需再 pip 安装 wxauto / wxautox-wechatbot。
:: 优先在线安装（能拿到与当前 Python 版本匹配的 wheel，如 cp313）。
python -m pip install -r requirements.txt --only-binary=:all: --index-url !IDX!
if !errorlevel! neq 0 (
    echo ⚠️ 在线安装失败，尝试回退本地 libs 离线安装（适用于 Python 3.9~3.12）...
    if "!TRUSTED_HOST!"=="" (
        python -m pip install -r requirements.txt -f ./libs --index-url !SOURCE_URL!
    ) else (
        python -m pip install -r requirements.txt -f ./libs --index-url !SOURCE_URL! --trusted-host !TRUSTED_HOST!
    )
    if !errorlevel! neq 0 (
        echo ❌ 安装依赖失败，请检查网络或 requirements.txt 是否存在
        pause
        exit /b 1
    )
)
echo ✅ 所有依赖安装成功！

:: 清屏
cls

:: ---------------------------
:: 检查程序更新（指向 GitHub samcaicn/gloai 的 weauto 分支）
:: CI 在该分支构建加密混淆的 WeAuto.exe，产物以 GitHub Actions Artifact
:: 形式保留 90 天，供本机下载与自动更新使用。
:: ---------------------------
echo 🟢 检查程序更新...
python updater.py

:: 清屏
cls

:: ---------------------------
:: 启动程序（Web 配置后台，端口 5001）
:: ---------------------------
echo 🟢 启动主程序...
python config_editor.py
