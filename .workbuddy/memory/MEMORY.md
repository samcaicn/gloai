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
- 构建命令（⚠️ **本地构建已废弃，2026-08-16 起统一走 GitHub CI，勿再本地出包**）：`cd C:\code\openopc && taskkill /F /IM SafeOPC.exe; CODEBUDDY_SESSION_ID= CLAUDE_SESSION_ID= <venv>/Scripts/python.exe -u -m PyInstaller packaging/openopc.spec --noconfirm --clean`，NSIS：`"/c/Program Files (x86)/NSIS/makensis.exe" packaging/installer.nsi`。
- 启动动画：原生 Win32 ctypes splash（`_NativeSplash`，无 WebView2/Tk 依赖）。曾因 `wintypes` 无 `HCURSOR`、64 位句柄截断、`FillRect` 非 gdi32 直接导出等问题导致 splash 线程静默崩溃，已全部修复。
- 未签名 exe 首次会被 SmartScreen/Defender 拦截或慢扫，彻底解决需走 SignPath 签名（申请仓库侧已完成，待用户提交表单）。

## CI / 流水线地址（SafeOPC 构建与签名）
- **构建 CI（Build，唯一出包途径）**：`samcaicn/safeopc` 仓库的 `opc` 分支（`opc-build.yml`，自清理：push→PyInstaller+NSIS 构建 `SafeOPC-Setup.exe`→artifact `safeopc-windows-setup`(保留90天)+短暂 Release→成功后删分支与 Release）。**2026-08-16 用户明确：本地不再构建，统一走此 CI，且坚持「构建后立即删分支+release」**。本地 `dist/`、`build/` 已于当日清理。
- 注：当日曾短暂试过「保留 Release 供签名 CI 抓取」（run 31921372860），随后已**回退为仍删 Release**（run 31932937532 实测 cleanup 步确实删了本次 release+tag+opc 分支）。该次实验遗留的 release `safeopc-win-20260816021838`（未签名 exe）仍在仓库，待清理（见下「待办」）。
- **签名 CI（Sign，当前正确/可用版）**：`samcaicn/safeopc` 自己的 `safeopc-signpath` 分支（`sign-windows.yml`：文件名 `SafeOPC-Setup.exe`、project-slug `safeopc`、artifact-config `safeopc-installer`、github-artifact-name `safeopc-setup`，监听 safeopc 本仓库 `release:published`）。SignPath 云端 EV 签名，需仓库 secret `SIGNPATH_API_TOKEN`/`SIGNPATH_ORG_ID`（OSS 审批通过后配置）。
- **gloai 的 `openopc-signpath` 是陈旧副本（已失效，勿当作可用 CI）**：同样有 `sign-windows.yml`，但仍是 **OpenOPC 时代**配置——文件名 `OpenOPC-Setup.exe`、project-slug `openopc`、artifact-config `openopc-installer`，且监听 **gloai 自己的** `release:published`（SafeOPC 构建 release 发在 safeopc，故该工作流永远不会被正确触发）。用户口头称「gloai 的 openopc/open 分支是本项目 CI 地址」实指这份，但它已不是可用签名流水线，需同步到 SafeOPC 配置或退役。
- **签名接线的致命冲突（已确认）**：`opc-build.yml` 按用户 2026-08-16 决策**构建后立即删 Release+分支**（自清理），而当前签名 CI（`safeopc/main` 的 `sign-windows.yml`）监听 `release:published`→release 被秒删，签名几乎必然拿不到安装包。**结论：在「删 Release」策略下，基于 `release:published` 事件的签名路径已死。** 若将来要签名，必须把签名改成 `opc-build.yml` 的**依赖 job**（在删 release 前对 artifact 签名）或监听 workflow artifact，而不是 `release:published`。
- **重要纠正**：`samcaicn/gloai` 默认的 `opc-build.yml`（含 `wt-build.yml`）构建的是 **Tupai**（gloai 自身 Electron 应用），与 SafeOPC 无关；gloai 当前无 `open`/`opc` 常驻分支（有 `cdp`/`colearn`/`main`/`openopc-signpath`/`tmp-empty`，`opc` 分支被自清理删掉）。勿把 SafeOPC 代码推到 gloai 去「构建」——技术栈不同（SafeOPC=Python+PyInstaller+NSIS；gloai=TS+Electron+electron-builder），gloai 只能签名、不能构建 SafeOPC。
- SignPath 申请仍待用户批准（批准前勿触发签名/勿发 Release）。

## 同赛道开源竞品：Wanta（oomol-lab/wanta）
- 真实存在，是**开源桌面 AI Agent 应用基础框架**（非之前的 wanna 待办应用，拼写差一个字母）。
- 仓库：`https://github.com/oomol-lab/wanta`，组织 OOMOL Lab，Apache-2.0，2026-08 仍活跃（~54 stars）。
- 技术栈：**Electron 42 + Vite 8 + React 19 + Tailwind CSS 4 + OpenCode(sidecar 运行时) + TypeScript**；包管理 pnpm/Corepack，要求 Node 22.22.2+；跨平台（mac/win/linux）。
- 能力：Agent 运行时、本地工具（文件/shell/脚本/搜索/Web）、权限控制（高危操作走显式 approval UI）、artifacts（生成物挂任务）、OpenConnector 接入 Gmail/Slack/Notion 等 SaaS、Build/Plan 双模式、BYOK（OpenAI 兼容自托管模型）。
- 与 SafeOPC 对比：Wanta 用 Electron+React+OpenCode、单 agent 桌面基础；SafeOPC 用 pywebview+WebView2+Python、多 AI 角色「虚拟公司」编排（自构建/自运行/自成长）。**两者同赛道但技术栈与产品范式不同**：Wanta 是「fork 改造成你自己的 agent」基础件，SafeOPC 是「AI 原生公司」编排平台。前端 UI 都是 React（可借鉴交互），桌面壳与 Agent 运行时不可直接复用。
- 注意区分：用户口中的 wanna（mkermani144/wanna，2018 停更的待办事项）= 无关；wanta（oomol-lab/wanta，agent app）= 真竞品。两名字易混。

## hermes 应用身份（重要，易与 SafeOPC 混淆）
- **hermes ≠ SafeOPC**。本机 `C:\Users\User\AppData\Local\hermes`（运行时数据：memories/sessions/skills/SOUL.md/cron/hooks）与 `~\.hermes`（config.yaml/skills/.env）是 **gloai 仓库中 Tupai 桌面 AI 应用的发布/品牌名**（应用名 `HermesDesktop`、自动更新器 `HermesUpdater`）。
- 关键证据：`~/.hermes/config.yaml` 注释 "Generated by tupai embedded server on first boot"；`C:\code\gloai\package.json` 的 `name: "tupai"`、`main: "dist-electron/main.js"`。
- **语言栈：TypeScript / Node 为主（Electron），不是 Python**。`C:\code\gloai` = Electron 42 + React 19 + Vite 8 + Tailwind 4 + @opencode-ai/sdk(1.18.10) + @oomol/connection-electron-adapter + electron-updater + pnpm；仓库内 depth-3 无 `*.py`/`requirements.txt`/`pyproject.toml`，即无 Python 后端。
- hermes 与 **wanta 同栈同生态**（同为 OOMOL 系：Electron+React+Vite+OpenCode+oxlint/oxfmt+@oomol/connection）；与 **SafeOPC 异栈**（SafeOPC=Python+pywebview/WebView2+PyInstaller）。
- 结论：用户问 "hermes 是 python 还是 ts node" → **TS/Node (Electron)**。勿把 SafeOPC 的 Python 经验套到 hermes 上。
