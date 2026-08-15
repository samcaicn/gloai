# SafeOPC / OpenOPC 项目长期记忆

## Playwright 浏览器自动化（已集成）
- Playwright 在项目中**已经完整集成**，不是从零开始：
  - `pyproject.toml` 已将 `playwright>=1.40` 列为 base 依赖。
  - 实现：`opc/layer4_tools/browser.py`（标题 "Native Playwright-backed browser tools"），基于 `playwright.async_api.async_playwright`，实现 10 个工具：browser_navigate / snapshot / click / type / take_screenshot / wait_for / scroll / select_option / navigate_back / close，并有 `BrowserRuntime` 单例 + `BrowserLaunchConfig`（embedded/chrome/auto 三模式，配置读 `system.browser`）。
  - 注册：`opc/engine.py` 中 `create_browser_tools()` 已注册进 agent；CLI role 配置（ceo/cto/engineering/review 等）已把 browser_tools 列入可用工具。
- **唯一缺口**：浏览器二进制未下载。需 `python -m playwright install chromium`。
- 本环境浏览器二进制安装路径约定为 `C:\Users\User\AppData\Local\hermes\runtime\ms-playwright`（非标准 `AppData\Local\ms-playwright`），已自动识别，无需手工设 `PLAYWRIGHT_BROWSERS_PATH`。
- 注意：Playwright Chromium 与桌面 GUI 的 WebView2 互不冲突；browser 工具用于 agent 操控**外部网页**，不是驱动 SafeOPC 自身的 WebView2 窗口（WebView2 默认不暴露 CDP 调试端口）。
- **关键能力**：Playwright 底层走 **CDP（Chrome DevTools Protocol）**，自带 `connect_over_cdp("http://127.0.0.1:9222")` 可连接**已开启远程调试端口**的浏览器实例，不一定要自己 launch 全新 Chromium。SafeOPC 桌面 GUI 是 WebView2（Chromium 内核），启动时可经环境变量 `WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=--remote-debugging-port=9222` 开端口，于是 Playwright 能 `connect_over_cdp` 连上 SafeOPC 自身的窗口，复用现有 10 个 browser 工具做**自驱动 / 桌面 UI 自动化测试**。仅限开发/测试模式开启（开 CDP=本机任意程序可控制该窗口，有安全风险）。

## 桌面应用打包（SafeOPC.exe）
- 栈：PyInstaller onedir + pywebview(WebView2) + NSIS。构建 venv：`C:/Users/User/.workbuddy/binaries/python/envs/openopc`。
- 构建命令：`cd C:\code\openopc && taskkill /F /IM SafeOPC.exe; CODEBUDDY_SESSION_ID= CLAUDE_SESSION_ID= <venv>/Scripts/python.exe -u -m PyInstaller packaging/openopc.spec --noconfirm --clean`，NSIS：`"/c/Program Files (x86)/NSIS/makensis.exe" packaging/installer.nsi`。
- 启动动画：原生 Win32 ctypes splash（`_NativeSplash`，无 WebView2/Tk 依赖）。曾因 `wintypes` 无 `HCURSOR`、64 位句柄截断、`FillRect` 非 gdi32 直接导出等问题导致 splash 线程静默崩溃，已全部修复。
- 未签名 exe 首次会被 SmartScreen/Defender 拦截或慢扫，彻底解决需走 SignPath 签名（申请仓库侧已完成，待用户提交表单）。
