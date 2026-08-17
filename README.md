# DeepSeek Harness — unified codebase

BitFun 团队开源的 DeepSeek Harness 配套三仓，整合进 **单一代码库**（单一分支 `dsh`，统一 CI）。

| 子目录 | 源仓库 | 内容 | 产物 |
|---|---|---|---|
| `rust/` | bobleer/deepseek-harness-rust | Rust 重实现本体：分层 crate + CLI（`dsh`）+ ACP stdio | `dsh.exe`（静态链接 CRT） |
| `gui/`  | bobleer/deepseek-harness-gui   | Tauri 桌面壳（React + Rust 后端），拉起 harness web | Windows 安装包（已捆绑 WebView2） |
| `mcp/`  | bobleer/deepseek-harness-plugin-mcp | MCP server：让任意 agent 发现/安装/运行 `dsh-plugin` | `dist/`（Node CLI） |

## 已知架构事实
- `rust/` 的 `dsh` 是 **headless CLI / ACP server**，没有 `web` 子命令，也不提供 HTTP 服务。
- `gui/` 的 Rust 后端默认去拉起官方的 Node 版 `@deepseek-ai/dsh web`（走 npx），并非直接用 `rust/` 本体。三者目前是「外壳 + 独立后端」关系，尚未深度耦合。
- `gui/` 之前在干净 Windows 上「启动即闪退」的根因是 **Tauri 默认不捆绑 WebView2**。现已在 `gui/src-tauri/tauri.conf.json` 设 `webviewInstallMode: embedBootstrapper`：安装包自带 WebView2 引导，安装/首次启动时会自动装好，修干净机器闪退。

## 构建（本地）
- Rust CLI：`cd rust && cargo build --release`（建议加 `RUSTFLAGS="-C target-feature=+crt-static"` 以免目标机缺 VC++ 运行库）。
- GUI：`cd gui && pnpm install && pnpm tauri build`（需先装 WebView2 或固定运行时）。
- MCP：`cd mcp && npm ci && npm test`。

## CI
根目录 `.github/workflows/ci.yml` 一个 workflow 三个 job，推到 `dsh` 分支即触发：
`rust`（windows，1.88.0，静态 CRT）→ `gui`（windows，Node22+pnpm+Tauri+WebView2）→ `mcp`（ubuntu，Node22，build+test）。各 job 产物作为 artifact 上传。
