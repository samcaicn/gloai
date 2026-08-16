# cua 驱动本地开发与调试指南

## 背景

本系统（tupai）的计算机使用自动化（CUA）基于开源项目 [trycua/cua](https://github.com/trycua/cua)。
其核心 `cua-driver` 在本仓库 `up/cua/` 中以 vendor 形式内置，作为 sidecar 二进制接入
`src-tauri/src/pc_automation/cua_driver/`。

`pc_automation` 的分层路由为 `CDP > UIA > OCR > VLM`，cua-driver 是底层输入/截图后端之一。
当 cua-driver 二进制缺失或启动失败时，会自动降级到 `enigo`（纯输入模拟，无视觉/控件感知）。

## 调试脚本速查

> 所有脚本在仓库根目录的 `package.json` 中定义，使用 pnpm 调用。

| 命令 | 作用 |
|------|------|
| `pnpm cua:build` | 构建 `cua-driver`（已存在则自动跳过；启用 sccache 缓存，见下文） |
| `pnpm cua:build:force` | 强制重建 `cua-driver`（`--force`） |
| `pnpm cua:check` | 仅检查二进制是否已构建（缺失则退出 1，不触发编译） |
| `pnpm dev:cua` | 确保 `cua-driver` 已构建，然后起 `tauri dev` |
| `pnpm dev:tauri` | 原生 `tauri dev`，默认 `RUST_LOG=debug`（已降噪，便于定位错误） |
| `pnpm dev:tauri:trace` | 起 `tauri dev` 并默认开启逐条 JSON-RPC 追踪（`RUST_LOG=trace` + `CUA_DRIVER_TRACE=1`） |
| `pnpm dev:cua:trace` | 构建 cua + 起 dev 并开启追踪 |
| `pnpm dev:tauri:skip-cua` | 跳过 cua 二进制校验直接起 dev（纯前端 / App 迭代，不需要 computer-use 时最快） |
| `pnpm dev:tauri:fast` | 用 `--profile release-fast` 起 dev（优化级编译，单 crate 热改略慢但运行更快） |
| `pnpm dev:cua:fast` | 构建 cua 后起 `dev:tauri:fast` |

跨平台构建逻辑见 `scripts/build-cua-driver.mjs`（Node 脚本，规避 Windows cmd/bash 差异）。
支持参数：`--force`、`--release`、`--check`。

> **日志级别约定**：日常开发用 `dev:tauri`（`debug`，安静可读）；需要逐条 RPC / 底层
> 追踪时再用 `dev:tauri:trace` 或 `dev:cua:trace`（`trace` + `CUA_DRIVER_TRACE=1`）。
> 之前默认写死 `trace` 会刷屏淹没真实错误，已改为默认 `debug`。

## 环境校验机制（前置硬拦截）

`src-tauri/tauri.conf.json` 中 `beforeDevCommand` 与 `beforeBuildCommand` 都前置了：

```
node scripts/build-cua-driver.mjs --check && <前端 dev / build>
```

- cua-driver 二进制缺失时，`tauri dev` / `tauri build` 会**直接失败报错**，不再是静默降级到 enigo。
- 逃生口：纯前端调试时设 `CUA_SKIP_CHECK=1` 可跳过该校验（脚本 `--check` 分支处理）。

二进制查找顺序与 `resolve_binary_path` 一致：`up/cua/target/{debug,release}/cua-driver`。

## 日志与可观测性

`src-tauri/src/pc_automation/cua_driver/client.rs` 做了两处日志增强：

1. **stderr 分级**：cua-driver 进程的 stderr 不再无脑 `info` 刷屏，
   而是按内容分级 —— 含 `error` → error 级、含 `warn` → warn 级、其余 → debug 级。
   生产环境（默认 info/warn）不会被淹没，关键错误不丢失。
2. **JSON-RPC 逐条追踪**（开关 `CUA_DRIVER_TRACE`）：
   设 `CUA_DRIVER_TRACE=1`（或直接使用 `pnpm dev:tauri:trace`）后，
   会记录每个 `tools/call` 的**请求方法 + 参数摘要**与**响应摘要**，
   以及握手阶段的 `initialize` 响应，便于排查调用失败。

`src-tauri/src/pc_automation/logger.rs` 提供 `info / warn / error / debug / trace`，
cua 客户端统一以 `target = "pc_automation"` 输出。

## 手动挂载 cua-driver

调试单个 cua-driver 实例时，可用环境变量 `CUA_DRIVER_PATH` 覆盖二进制路径，
把本地手动启动的 cua-driver 挂接到 tupai 进程上（绕过自动查找与降级）。

## 故障排查

- **cua 不起作用 / 自动化走 enigo**：先 `pnpm cua:check` 确认二进制已构建；
  缺失则 `pnpm cua:build`（首次编译 `up/cua` 大型 Rust workspace 较慢，增量编译很快）。
- **编译 cua-driver 慢**：二进制一旦存在，构建脚本会自动跳过；
  仅在 `--force` 或清理 target 后才会全量重建。
- **RPC 调用失败 / 无响应**：开 `CUA_DRIVER_TRACE=1` 看请求/响应细节；
  检查 `src-tauri/resources/cua-driver-policy.yaml` 权限策略
  （关键项 `CUA_DRIVER_PERMISSION_MODE=unrestricted`）。
- **dev 下仍降级 enigo**：检查 `client.rs` 的 `ensure_connected` 在 `debug_assertions`
  下打印的提示，确认 `CUA_SKIP_CHECK` 未误设、且 `--check` 未因路径不匹配而误报缺失。
- **Windows 构建报 “MSVC Spectre-mitigated libs” 缺失**：根因是 `regorus`（cua-driver
  策略引擎的传递依赖）可选依赖 `msvc_spectre_libs` 并开了 `error` feature，缺该 VS 工作负载时
  构建直接 panic。本仓库已内置**非管理员修复**：`libs/cua-driver/rust/Cargo.toml` 通过
  `[patch.crates-io]` 把 `msvc_spectre_libs` 替换为本仓 `crates/msvc-spectre-libs-stub`
  ——stub 的 build.rs 不 panic，仅在缺 Spectre libs 时 warning 并链接普通 CRT（本地开发完全可用）；
  若机器装了该工作负载，stub 行为与上游一致。安装工作负载的步骤：Visual Studio Installer →
  修改 → 单个组件 → 勾选 “MSVC v143 - VS 2022 C++ x64/x86 Spectre-mitigated libs”（版本号按
  VS 年份选 v142/v143）；装好后删掉该 patch 与 stub 目录即可回到上游行为。`build-cua-driver.mjs`
  的构建前自检会检测到 stub 存在而自动跳过 Spectre 检查，避免误杀 dev 链路；也可用
  `CUA_SKIP_ENV_CHECK=1` 强制跳过自检。

## 构建加速（sccache）

`up/cua` 是个巨型 Rust workspace，且 `src-tauri` 与 `up/cua` 会各自重编 tokio / serde
等公共依赖。`scripts/build-cua-driver.mjs` 现在会自动检测本机是否安装了 `sccache`：

- **已安装**：注入 `CARGO_BUILD_RUSTC_WRAPPER=sccache` 与 `CARGO_BUILD_INCREMENTAL=false`，
  让 cua 的编译产物按源码哈希缓存。好处：
  - 两次 workspace 之间去重公共依赖（首次编 tokio 后，另一处直接命中缓存）；
  - `cargo clean` / 切分支后快速回填，而不是全量重编。
- **未安装**：打印提示并跳过加速，构建照常进行（不会因此失败）。

> 验证：`sccache --show-stats` 可看到 cua 构建命中 / 缓存计数。
> 注意 sccache 在 `incremental` 开启时**不会**缓存，因此 cua 构建显式关闭了增量。
> 主程序 `tauri dev` 与 `tauri build` 都已透明接入 sccache：所有 `dev:tauri*` / `dev:cua*`
> 脚本以及 `build:tauri` 均经 `scripts/tauri-dev.mjs` 包装，检测到 sccache 时自动注入
> `CARGO_BUILD_RUSTC_WRAPPER` + `CARGO_BUILD_INCREMENTAL=false`；未安装则完全不干预
> （行为与原生 `tauri` 一致）。因此日常 `pnpm dev:cua` 与本地 `pnpm build:tauri` 都自动
> 享受缓存，无需手动设环境变量。

## Rust 后端断点调试（VS Code）

仓库已附带 `.vscode/launch.json` / `tasks.json` / `extensions.json`，首次打开时
VS Code 会提示安装 `rust-analyzer` 与 `vadimcn.vscode-lldb`（codelldb）。

两种调试方式：

1. **Attach（最常用，不打断 dev 热循环）**
   - 终端里照常 `pnpm dev:cua`（或 `pnpm dev:tauri`）起开发环境；
   - VS Code 按 `F5` → 选 `Tauri: Attach to running app` → 在进程列表里选 `tupai.exe`；
   - 在 `src-tauri/src/**/*.rs` 里直接打断点即可（Rust 后端是 `tupai` 这个 bin）。
2. **Launch（由 VS Code 一把拉起）**
   - `F5` → 选 `Tauri: Launch dev (Rust + Vite)`；
   - 它会先 `cargo build` 再启动 `tupai.exe`，并通过 `preLaunchTask: vite:dev`
   - 在后台拉起前端 dev server（默认 `http://localhost:5173`）。
   - 该模式下设了 `RUST_BACKTRACE=1` 与 `RUST_LOG=debug`，崩溃可直接看堆栈。

> 提示：前端（TS/React）调试用 WebView2 自带的 DevTools（右键页面 → Inspect，
> 或 `Ctrl+Shift+I`），与 Rust 侧 codelldb 互不冲突。

## 典型工作流

```bash
# 1. 首次克隆后，构建 cua 并起开发环境（含追踪）
pnpm dev:cua:trace

# 2. 日常开发（已构建，增量很快；默认 RUST_LOG=debug，安静可读）
pnpm dev:cua

# 3. 纯前端 / App 迭代，不需要 computer-use：跳过 cua 校验，最快
pnpm dev:tauri:skip-cua

# 4. 只想验证二进制是否就绪
pnpm cua:check

# 5. 用 VS Code 断点调试 Rust：先起环境，再 F5 → Attach to running app
pnpm dev:cua
```

## 构建 NSIS 安装包（本机环境要点）

`pnpm build:nsis`（= `build.ps1 -Nsis`）在本机直接产出
`target/release-nsis/bundle/nsis/*-setup.exe`。本机有两个环境坑，提前处理即可：

1. **父级 pnpm workspace 会吞掉安装**：`/c/code/pnpm-workspace.yaml` 的 `packages`
   含 `./*/**`，会把 `/c/code` 下所有项目（含 aiagent、digitalmanapp 等）都纳为一个
   巨型 workspace（约 126 个子包），且其中某包引用了 `catalog:` 却未在根 workspace
   定义 catalog → 任意 `pnpm install` / `pnpm tauri` 从 `safeopcAPP` 触发都会报
   `No catalog entry ... for catalog 'default'`，并因并行拉取全 workspace 依赖而
   触发 `UND_ERR_DESTROYED`（registry 连接被重置）。
   - 避坑：`safeopcAPP` 自身只需 `@tauri-apps/cli`（根 `package.json` devDependencies）。
     构建前把父 workspace **临时改名隐藏**，让 `safeopcAPP` 以**独立单包**安装，只装这一个
     依赖（约 23s）即可：
     ```bash
     mv /c/code/pnpm-workspace.yaml /c/code/pnpm-workspace.yaml.bak
     cd /c/code/safeopcAPP && pnpm install   # 仅装 @tauri-apps/cli
     # ... 跑完 build.ps1 -Nsis 后 ...
     mv /c/code/pnpm-workspace.yaml.bak /c/code/pnpm-workspace.yaml
     ```
   - 前端 `src/web-ui` 是独立 workspace（`src/web-ui/pnpm-workspace.yaml`，仅含自身），
     已提前 `pnpm install` 过，隐藏父 workspace 不影响它（`pnpm --dir src/web-ui/src/web-ui build`）。

2. **makensis 不在 PATH**：Tauri 的 NSIS bundler 会调用 `makensis.exe`，本机装在
   `C:\Program Files (x86)\NSIS\`（默认未加入 PATH）。跑构建前把它加入 PATH：
   ```powershell
   $env:PATH = "C:\Program Files (x86)\NSIS" + ";" + $env:PATH
   ```

3. **`build.ps1` 必须是 UTF-8 BOM（否则整脚本不执行）**：该文件是 UTF-8 **无 BOM**。
   Windows PowerShell 5.1 对无 BOM 的 `.ps1` 用系统 ANSI 代码页（GBK/936）解码，中文被
   乱码、且部分 UTF-8 多字节序列被当成 `$` → 凡是"中文 + `$`"的 `Write-Host` 行都会
   `ParserError: MissingExpressionAfterToken`，**脚本根本不执行**（现象是 `tauri build`
   完全没跑、无任何 `*-setup.exe` 产物，日志里一堆 `CategoryInfo : ParserError`）。
   修复：在文件头 prepend UTF-8 BOM（`EF BB BF`，不重编码、内容不变）。用 Python 一行即可：
   ```python
   b = open("build.ps1", "rb").read()
   if not b.startswith(b"\xef\xbb\xbf"):
       open("build.ps1", "wb").write(b"\xef\xbb\xbf" + b)
   ```
   验证：`[void][System.Management.Automation.Language.Parser]::ParseFile("build.ps1", [ref]$null, [ref]$e); $e.Count` 应为 `0`。
   （注：这是项目文件本身的编码问题，与本次改动无关；任何在中文 Windows 上跑
   `build.ps1` 的机器都会踩，务必保证文件带 BOM。）

其它要点：
- `beforeBuildCommand` 的 `build-cua-driver.mjs --check` 只**校验**
  `up/cua/target/{debug,release}/cua-driver.exe` 是否存在（本机已构建在 `debug`，约 45MB），
  **不重新编译**；cua-driver 在运行时由 `resolve_binary_path`（Rust）从 `up/cua/target/...`
  定位，**不走 tauri `externalBin`**，因此打包阶段不嵌入该二进制。
- `--profile release-nsis` 已在 `src-tauri/Cargo.toml` 定义
  （`[profile.release-nsis]`，lto=off + codegen-units=16 + strip=symbols）。
- 构建产物定位：脚本会按 `target/release-nsis/bundle/nsis` → `target/release-fast/bundle/nsis`
  → `target/release/bundle/nsis` 顺序查找 `*-setup.exe`。
