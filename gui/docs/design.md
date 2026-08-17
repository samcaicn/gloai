# DeepSeek Harness GUI 设计

独立桌面客户端：用 Tauri 2 承载 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 的 `dsh web` 运行时。前端布局、适配器分层、窗口铬与欢迎页对齐 [BitFun](https://github.com/GCWing/BitFun) 的 desktop + web-ui 设计，而不是复刻 BitFun 的编辑器、Git、MiniApp 或远端设备能力。

## 目标

- 提供可安装的桌面壳：自定义标题栏、工作区选择、设置、托盘、进程生命周期。
- 会话主界面复用官方 `dsh web` GUI（插件、工具卡片、会话与权限流保持与 harness 一致）。
- UI 组件不直接调用 Tauri；所有原生能力经过 adapter。
- 错配在加载或启动时失败并给出可操作说明，不静默跳过。

## 非目标（本仓库第一版）

- 不把 GUI 做进 `deepseek-harness` monorepo（独立 GitHub 仓库，消费已发布的 `@deepseek-ai/dsh`）。
- 不重写 client 插件树、slot 系统或 Conversation Node。
- 不实现 BitFun 的 Monaco、Git 图、Peer Device、Relay、皮肤市场。

## 架构

```
┌─────────────────────────────────────────────────────────────┐
│  React web-ui（BitFun 布局：NavBar / NavPanel / SceneArea）   │
│  infrastructure/adapters → 永不从场景组件 invoke Tauri        │
└──────────────────────────────┬──────────────────────────────┘
                               │ invoke / event
┌──────────────────────────────▼──────────────────────────────┐
│  Tauri 2 host                                                │
│  window / tray / dialog / keyring / settings.json            │
│  spawn dsh web（或 npx @deepseek-ai/dsh web）                 │
└──────────────────────────────┬──────────────────────────────┘
                               │ http://127.0.0.1:<port>
                               ▼
                     官方 dsh web GUI（iframe 场景）
```

### 前端分层（对齐 BitFun）

| 层 | 职责 |
|---|---|
| `infrastructure/runtime` | 探测 Tauri、macOS overlay 标题栏、是否可拖窗 |
| `infrastructure/adapters` | `HostAdapter`：选目录、设置、密钥、harness 启停、窗口铬 |
| `infrastructure/appearance` | `--dshg-*` CSS 变量，深/浅/系统主题 |
| `infrastructure/i18n` | 中英字典；产品文案默认中文 |
| `app/layout` | WorkspaceBody：左栏 NavBar+NavPanel，右栏 SceneBar+SceneViewport |
| `app/scenes` | welcome / session / settings |
| `component-library` | Button、Tooltip、WindowControls、FishLogo |

场景组件只消费 adapter 与 store。Tauri `invoke` / `getCurrentWindow` 只出现在 `adapters/tauri.ts`。

### 宿主职责

- **工作区**：系统目录选择器；最近工作区列表写入配置目录 `settings.json`。
- **密钥**：优先 OS 钥匙串（macOS Keychain）；钥匙串不可用时回退到 settings 文件，并在设置页标明。
- **运行时**：在工作区 cwd 启动 `dsh web --host 127.0.0.1 --port 0`，解析 stdout 行 `dsh web: http://127.0.0.1:<port>`，只接受回环 URL。
- **PATH**：macOS 打包后不继承登录 shell PATH，启动前补齐 Homebrew / pnpm / 用户 local bin。
- **生命周期**：退出或切换工作区时对进程组 SIGTERM → SIGKILL（Windows 用 `taskkill /T`）。窗口关闭默认退出；可选最小化到托盘。

### 场景

1. **Welcome**：无工作区时的启动页——问候、打开文件夹、最近工作区。有工作区时仍可从左栏回到此处切换项目。
2. **Session**：iframe 加载 `dsh web`。加载中与失败态由外壳处理（重试、打开浏览器、查看日志摘要）。
3. **Settings**：API Key、harness 命令覆盖、主题、语言、关闭到托盘、`doctor` 探测（node / dsh / npx / 密钥）。

### 默认启动命令

1. 若设置了 `harnessCommand`，使用该可执行文件 + `harnessArgs`。
2. 否则在增强 PATH 上查找 `dsh`。
3. 否则 `npx --yes @deepseek-ai/dsh@^0.1.0-rc.6` + 默认 args：`web --host 127.0.0.1 --port 0`。

缺少 API Key 时拒绝启动并跳到设置页。

## 窗口铬

- macOS：系统 decorations + Overlay 标题栏 + 隐藏文字标题，交通灯位置对齐 BitFun（约 12, 15）；NavBar 左侧留 78px。
- Windows / Linux：`decorations: false`，NavBar 右侧绘制 WindowControls。
- `acceptFirstMouse` 与窗口居中；NavBar / SceneBar 空白区 `startDragging`，弹性条带使用 `data-tauri-drag-region`；双击空白区最大化（macOS 原生拖拽条带走系统 zoom，避免连点两次）。
- `tauri-plugin-window-state` 记住主窗口尺寸、位置、最大化与全屏；不含 VISIBLE，避免关到托盘后下次启动窗口仍隐藏。该插件最后注册。
- `tauri-plugin-single-instance` 把第二次启动聚焦到已有窗口（unminimize + show + focus）。
- 启动至少展示 650ms 的 BitFun 风格 Splash（FishLogo 呼吸动画）；bootstrap 超过约 1.8s 时显示“正在加载…”。
- 托盘 tooltip 为 `DeepSeek Harness`。打包图标由 `src-tauri/icons/app-icon.svg` 经 `pnpm icons` 生成。

## 安全

- `dsh web` 只绑 `127.0.0.1`。解析到的 URL 必须是 loopback，否则丢弃。
- iframe 指向该 loopback；外壳页面不把密钥送进 iframe（harness 子进程从环境变量读取）。
- 本仓库不提交 `.env` 或密钥。

## 测试

- Rust：URL 解析、PATH 查找（含临时 `dsh` 文件）、启动参数解析的单元测试。
- 前端：adapter 选择、i18n 回退、store 场景切换、`dsh web:` 行解析、Splash 退出时序。
- 编译：`tsc --noEmit`、`vite build`、`cargo test`、`cargo clippy`。
- 本地冒烟：`pnpm test:smoke` 真实拉起 `dsh web --host 127.0.0.1 --port 0`，解析回环 URL 并 GET；PATH 上没有 `dsh`/`npx` 时跳过。使用占位 `DEEPSEEK_API_KEY` 即可绑定端口（与 harness keyless web smoke 相同）。
- 手动：`pnpm tauri dev` 打开工作区并确认 iframe 出现官方 GUI。

## 仓库关系

GUI 是独立仓库，不修改 `deepseek-harness` 主树，避免与其他 agent 的 worktree 冲突。harness 源码仅作协议与 `dsh web:` 日志格式的参考。
