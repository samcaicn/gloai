# SafeOPC 跨平台桌面客户端打包计划（修订版）

> 目标：把港大 samcaicn/safeopc（Python 后端 + React/Phaser 前端）打包为**原生桌面客户端**，
> 在 Windows / macOS / Linux 上一键安装、双击启动，原生窗口加载现有 Office UI。

---

## 1. 现状与约束

- 当前形态：`opc ui` 启动 aiohttp 服务（默认 `127.0.0.1:8765`），托管已 `vite build` 的前端
  （`opc/plugins/office_ui/frontend_dist`）+ WebSocket，浏览器访问。
- 后端重依赖：`litellm`、`chromadb`（含 onnxruntime/numpy）、`mcp`、`aiohttp`、`playwright`（可选）。
- `get_opc_home()` 解析顺序：`$OPC_HOME` → `{project_root}/.opc`。**冻结后 cwd 不可控**，
  必须显式设置 `OPC_HOME` 指向用户可写目录。
- `playwright` 仅在 `opc/layer4_tools/browser.py` 用到，且已对缺失做 `try/except` 兜底
  （`async_playwright=None`），浏览器工具会优雅降级 —— **首版可直接排除 playwright 以缩小体积、规避 node 驱动冻结失败**。
- 前端已构建产物为 `frontend_dist`（约 32K），通过 `Path(__file__).parent / "frontend_dist"` 定位，
  冻结时必须保持 `opc/plugins/office_ui/frontend_dist` 的相对路径。

---

## 2. 技术选型（已定）

| 层 | 工具 | 说明 |
|---|---|---|
| 后端冻结 | **PyInstaller** | 重依赖 hooks 最稳，跨平台需在各 OS 分别构建 |
| 窗口外壳 | **pywebview** | 用系统 WebView（Win=WebView2 / mac=WKWebView / Linux=WebKitGTK）承载现有前端，最像真 App |
| 分发层 | **Briefcase**（BeeWare）或平台安装器 | PyInstaller 只出裸 exe，Briefcase 补 `.msi/.dmg/.deb/.AppImage` |
| 体积优化 | 排除 playwright / channels-extras / pytest | 浏览器工具降级，其他渠道客户端不需要；后续可上 UPX |

> 备选：Nuitka 编译成 C 在 NumPy/onnxruntime 上翻车率约 20%，**暂不采用**；PyOxidizer 已停更，**出局**；Tauri 需把 Python 后端做 sidecar，复杂度高，**留作后续小体积方案**。

---

## 3. 架构

```
┌─────────────────────────────────────────────┐
│  SafeOPC.exe (PyInstaller one-file/dir)      │
│                                               │
│  desktop_app.py  (入口)                       │
│   ├─ 设置 OPC_HOME → %APPDATA%/SafeOPC        │
│   ├─ 首启：复制 config 模板 → OPC_HOME/config │
│   ├─ 线程A：run_server(127.0.0.1:8765)        │  ← aiohttp (OPCEngine + WS + 静态前端)
│   └─ 主线程：webview.create_window(           │  ← 系统 WebView 原生窗口
│              "http://127.0.0.1:8765")         │
│                                               │
│  内嵌数据：frontend_dist / config / skills    │
└─────────────────────────────────────────────┘
```

- 后端与前端同进程、同机，零网络暴露（绑定 127.0.0.1）。
- 关闭窗口 → `webview.start()` 返回 → `os._exit(0)`，后台线程随进程退出。

---

## 4. 文件清单（本目录 `packaging/`）

| 文件 | 作用 |
|---|---|
| `DESKTOP_PACKAGING.md` | 本计划文档 |
| `desktop_app.py` | 桌面入口（OPC_HOME / 配置引导 / 起服务 / 开窗），含 `SAFEOPC_HEADLESS` 冒烟分支 |
| `safeopc.spec` | PyInstaller 规格（datas / hiddenimports / excludes / console=False） |
| `build.ps1` | Windows 构建脚本（调 pyinstaller + 清理 + 报告产物） |
| `build.sh` | macOS / Linux 构建脚本（待补） |
| `briefcase.toml` | 安装包配置（待补，分发层） |
| `.github/workflows/build-desktop.yml` | CI 矩阵（待补） |

---

## 5. 分阶段实施

- [x] **阶段 0**：源码克隆 + 前端构建 + `opc ui` 预览（已完成，8765 正常）
- [x] **阶段 1**：Windows 骨架 —— `desktop_app.py` + `safeopc.spec` + `build.ps1`，产出 `dist/SafeOPC.exe` 并通过 HEADLESS 冒烟（SPA + WS 链路验证通过）。已加**端口顺延**（`find_free_port`）：8765 被占用时自动跳到下一个空闲端口，避免二次启动/旧 dev server 撞端口直接崩溃。
- [ ] **阶段 2**：macOS / Linux 构建配置（`build.sh` + 对应 spec/hiddenimports），分别验证。
- [x] **阶段 3**：Windows 安装包 —— `packaging/installer.nsi`（NSIS），`makensis` 编译产出 `dist/SafeOPC-Setup.exe`（LZMA 压缩；从 onedir 压到 ~127MB）。装到 `$PROGRAMFILES\SafeOPC`，建桌面/开始菜单快捷方式，带卸载。已用 user 权限测试版静默安装验证：内容完整、装后 exe HEADLESS 启动 HTTP 200。⚠️ **未签名**：双击安装包本身仍会被 SmartScreen/Defender 拦（与裸 exe 同因），根治需代码签名（见阶段 6）。
- [ ] **阶段 4**：CI 矩阵（GitHub Actions win/mac/linux runner 自动出包）。
- [ ] **阶段 5**：体积优化（UPX、进一步裁剪 litellm 可选依赖、可选回带 playwright）。
- [ ] **阶段 6（待做·根治拦截）**：代码签名。方案：(a) 自有 pfx → NSIS 脚本后接 `signtool sign`；(b) 开源免费 EV：SignPath.io / Certum Open Source；自签名无效（SmartScreen 不认）。签名后才能双击无忧。

### 5.1 本次重建计划（2026-08-14）

前端 dist 有未提交修改（`index-C9r7yora.js`、`index-aQzimkVG.css`、`phaser-DFK5Ua9d.js`、`index.html`），需重新构建以确保安装包包含最新前端。

**执行步骤：**
1. 确认前端 dist 为最新（必要时重新 `npm run build`）
2. `pyinstaller packaging/safeopc.spec --noconfirm --clean` 重新构建 `dist/SafeOPC/`
3. HEADLESS 冒烟测试（`SAFEOPC_HEADLESS=1` + `curl http://127.0.0.1:8765/`）
4. 安装 NSIS → `makensis packaging/installer.nsi` 生成 `dist/SafeOPC-Setup.exe`
5. 静默安装验证 + 装后 HEADLESS 启动验证

---

## 6. 构建命令

### Windows（阶段 1）
```powershell
# 在 safeopc venv 激活后，于仓库根 C:\code\openopc 执行
pyinstaller packaging/safeopc.spec --noconfirm
# 或
powershell -ExecutionPolicy Bypass -File packaging/build.ps1
```
产物：`dist/SafeOPC/SafeOPC.exe`（onedir 模式，启动快、便于排查）。

### 冒烟测试（无需显示器）
```powershell
$env:SAFEOPC_HEADLESS=1
dist/SafeOPC/SafeOPC.exe        # 仅起 aiohttp 服务
# 另开终端
curl http://127.0.0.1:8765/     # 应返回 index.html（HTTP 200）
```

### 真实 GUI（需 Windows 桌面 + WebView2）
双击 `SafeOPC.exe` → 原生窗口加载 Office UI。

---

## 7. 风险与缓解

| 风险 | 缓解 |
|---|---|
| PyInstaller 冻结 chromadb/litellm 的 hidden import 缺失 | 用 `--collect-all chromadb litellm mcp aiohttp opc` + 显式 hiddenimports |
| 前端 `frontend_dist` 未随包收集 | spec `datas` 保持 `opc/plugins/office_ui/frontend_dist` 相对路径 |
| config/skills 在 editable 安装下不在 `opc/` 内 | 把仓库根 `config/`、`skills/core` 作为 datas 打进包，首启复制到 OPC_HOME |
| 冻结后 `get_opc_home()` 落到只读 `dist/` | 入口强制 `OPC_HOME` 指向 APPDATA/Library/.config |
| Windows 缺 WebView2 运行时 | 文档提示安装 Evergreen Bootstrapper；默认 edgechromium 后端无需额外二进制 |
| 体积过大（chromadb+litellm） | 排除 playwright/pytest/channels-extras；后续 UPX；可选 Nuitka |
| 跨平台需分别构建 | 阶段 2/4 用对应 OS 构建机或 CI runner，无法单机构建三端 |

---

## 8. 已知限制（首版）

- 浏览器自动化工具（playwright）在桌面包中**不可用**（已降级，不影响核心 agent/文档/检索能力）。
- 真正跑 agent 仍需在 `OPC_HOME/config/llm_config.yaml` 填入 LLM API key（UI 可浏览、聊天/agent 需 key）。
- 单实例锁在 Windows 上因无 `fcntl` 自动跳过（与 `opc ui` 一致）。
- **GUI 窗口（pywebview 原生窗口）尚未在无显示器环境实测**：HEADLESS 冒烟已验证「aiohttp 服务 + 前端 SPA + WebSocket 后端」全链路，pywebview 包已正确收集（`_internal/webview/`），但窗口渲染需真实桌面 + WebView2 才能目视确认。需在 Windows 桌面双击 `SafeOPC.exe` 验证一次。
