# DSH Desktop 基本状态基准线（BASELINE）★ 不可破坏 ★

> **本文件是项目铁律（2026-08-28 用户郑重声明）：**
> 当前已验证可用的状态 = 最好的基本状态。
> **任何升级（版本 / 依赖 / 功能 / UI）都绝不能把它搞乱。**
> 升级成功的唯一标准：跑完下方自测，全 PASS 且基本状态保持不变。

---

## 1. 基本状态定义（当前唯一允许形态）

| 项 | 允许形态 | 禁止 |
|----|---------|------|
| UI | 官方 DSH WebUI，iframe 嵌入 `http://127.0.0.1:3080`（标准三栏） | 换成其他 UI / 加自定义侧边栏 / 外部浏览器打开 |
| 窗口 | `decorations:false` 自定义标题栏：深色 `#16213e` 顶部 32px，右上角 最小化/最大化/关闭 三按钮，下方接 iframe | Windows 原生标题栏 / 无标题栏 / 改变布局 |
| 标题栏拖动 | Tauri 原生 `data-tauri-drag-region`（标题栏=`true`，按钮=`false`） | `-webkit-app-region: drag`（吞点击） |
| 窗口控制按钮 | 最小化 / 最大化 / 恢复 / 关闭 全部可用（真实输入实测通过） | 按钮无响应 / 点击穿透 |
| 后端 | 固定端口 `3080`；Rust 侧自动拉起 cdsh；端口被占用则复用用户手动启动的实例 | 端口漂移 / navigate 到其他地址 |
| 生产配置 | 无 CDP 调试端口 | `additionalBrowserArgs: --remote-debugging-port=...`（只允许临时调试时加，交付前必须移除） |

## 2. 产物

- 安装包：`D:\code\dsh\target\release\bundle\nsis\DSH Desktop_0.1.0_x64-setup.exe`
- 可执行文件：`D:\code\dsh\target\release\dsh-desktop.exe`

## 3. 升级铁律（防止重蹈覆辙）

1. **按钮事件必须用 `addEventListener` 绑定，禁止 inline onclick**。
   CSP 必须含 `script-src-attr 'unsafe-inline'`，否则严格 CSP 会静默拦截按钮点击（点击到达 DOM 但 handler 不执行）。
2. **禁止在 Rust `setup()` 里调用 `win.navigate(url)`**：
   那会绕过 index.html 直接导航到 DSH 页面，标题栏与按钮完全消失。必须保持 index.html（标题栏 + iframe）作为唯一入口。
3. **禁止使用 `-webkit-app-region: drag`**：它会吞掉按钮点击。改用 Tauri 原生 `data-tauri-drag-region` 属性。
4. **生产配置禁止残留 CDP 调试端口**（`--remote-debugging-port=9333` 等）：仅在临时调试时添加，交付前必须移除。
5. **任何升级后必须自测，全 PASS 才算成功**：
   - 窗口控制按钮（最小化 / 最大化 / 恢复 / 关闭）真实输入点击验证
   - 标题栏拖动（真实按下 + 移动）验证窗口跟随移动

## 4. 可复用自测脚本

位于 `D:\code\dsh\dsh-desktop\tests\`：

| 脚本 | 用途 | 依赖 |
|------|------|------|
| `release-smoke.ps1` | **验收脚本**：真实输入测 minimize / maximize / restore / drag / close，全 PASS 为合格 | 无 CDP，release 或 debug exe 均可（内部配置 exe 路径） |
| `test-sendinput.ps1` | CDP 取真实按钮坐标 + SendInput 真实点击 | 需 debug 构建含 CDP 端口 |
| `test-cdp-full.ps1` | CDP trusted 点击 3 按钮 | 需 debug 构建含 CDP 端口 |
| `test-drag-real.ps1` | 真实输入标题栏拖动 | 需 debug 构建含 CDP 端口 |

### 按钮坐标经验值（窗口宽 W 时，`y = top + 16`）
- 最小化：`W - 118`
- 最大化：`W - 82`
- 关闭：`W - 46`

## 5. 曾踩过的坑（背景，供排查参考）

- **按钮"点击没反应"根因①**：严格 CSP 阻止 inline onclick（浏览器拒绝执行内联事件处理器）。→ 改用 addEventListener + CSP `script-src-attr 'unsafe-inline'`。
- **按钮"点击没反应"根因②**：Rust setup 曾调用 `win.navigate(url)`，整个 WebView 被导向 DSH 页面，标题栏按钮根本不存在。→ 移除 navigate，固定端口 3080，index.html iframe 指向 `http://127.0.0.1:3080`。
- **iframe 黑屏**：loading overlay 仅降低 opacity 到 0 仍占空间显示黑块。→ `.hidden` 同时设置 `display:none`。
- **测试误区**：曾误判"SendInput 不产生 DOM 点击"。实际是测试脚本坐标硬编码错误 + CSP bug 掩盖。SendInput 真实输入完全能驱动 WebView2 DOM（已用 DOM 探针证实 mousedown 到达 titlebar）。
- **CDP 拖动局限**：CDP 合成 mousePressed 不会在 OS 线程消息队列产生真实 WM_LBUTTONDOWN，系统模态拖拽循环依赖 `GetKeyState`（线程队列状态）→ CDP 无法验证拖动，必须用真实输入验证。

---

*任何改动触碰到以上任一红线，即为破坏基本状态，必须先回退再谈新功能。*