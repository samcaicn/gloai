# ADR-001: plugin_bus（"一切皆插件"）的范围边界

- 状态: 已裁决（Accepted）
- 日期: 2026-08-21
- 决策人: tupAI 核心开发
- 关联设计: `src-tauri/src/plugin_bus.rs`、`src-tauri/src/plugins/`、`src-tauri/src/runtime_registry/`

---

## 背景

项目存在两套平行的"可插拔能力"机制，命名与职责容易混淆：

1. **plugin_bus（Cordis / DeepSeek Harness 式"一切皆插件"）**
   - 入口：`src-tauri/src/plugin_bus.rs`（`Plugin` trait + `PluginManager` + `PluginContext`）
   - 接线：`src-tauri/src/lib.rs:1046-1054`，setup 钩子在全部内置工具注册完成后调用 `plugin_mgr.load_all(&plugin_ctx)`
   - 装配：`src-tauri/src/plugins/mod.rs` 的 `register_builtin_plugins`，当前 6 个内置插件：`cdp` / `mcp` / `memory` / `pc_automation` / `skill` / `system`
   - 能力边界：**只能扩展内部工具（`ToolRegistry2`）与事件订阅（`EventBus`）**

2. **runtime_registry（运行时注册表，含外部运行时接缝）**
   - 入口：`src-tauri/src/runtime_registry/`
   - 适配器：`adapters/{custom_api,cli_run,upstream,acp}.rs`
   - DSH 通过 `commands/dsh.rs` 把 `DshUpstreamConfig` 同步为 `RuntimeInstance{kind: Upstream}`，由 `adapters/upstream.rs` 的 `UpstreamAdapter` 驱动（http `/chat/completions` 或本地二进制 subprocess）
   - 能力边界：**外部 Agent 后端的调用契约**（`detect` / `invoke` / `health`）

## 调研发现（基于真实代码，非设计文档）

1. `plugin_bus` 已落地并接线，6 个内置插件全部为**逻辑逐字保留的迁移壳**：
   - `plugins/cdp.rs:4`、`plugins/mcp.rs:4`、`plugins/skill.rs`、`plugins/memory.rs`、`plugins/pc_automation.rs` 注释均写明"逻辑逐字保留，仅把闭包捕获改为从 `PluginContext` 克隆，行为不变"
   - 已确认 lib.rs setup 中原有的 `ensure_cdp_browser` / `cdp_action` / `mcp_call` 内联注册被移除，仅保留在 `plugins/` 与注释里——属**迁移，非双注册**
2. **硬天花板**：`plugin_bus.rs:7-9` 注释自承——`tauri::generate_handler!` 必须在编译期固定 IPC 命令列表，插件**不能**动态新增 `#[tauri::command]`。因此"一切皆插件"只能覆盖内部 agent 工具层，无法触及 45+ 个前端直接 invoke 的 IPC 命令。
3. DSH 与 plugin_bus **职责正交**：DSH 是 `AgentProviderAdapter`（有 `invoke/detect/health`），plugin_bus 的 `Plugin` trait 无此契约。DSH 走 runtime_registry 的 Upstream 接缝是正确设计，不该并入 plugin_bus。
4. runtime_registry 适配器中**无 MCP adapter**（MCP 仅存在于 `plugins/mcp.rs` 插件 + `commands/mcp_proxy.rs` 命令），不存在 MCP 双实现问题。

## 决策

1. **保留并维持** plugin_bus 当前范围：**内部 agent 工具 + 事件订阅的去中心化注册**。新增内部工具走 `plugins/` 模块，不再回写到 lib.rs setup 大块。
2. **停止**将剩余 `#[tauri::command]` IPC 命令机械地套 `Plugin` trait 迁移——这是低 ROI 的组织重构，不带来能力增量。
3. **DSH 不并入 plugin_bus**：维持其作为 runtime_registry `Upstream` 外部后端的接线方式。
4. **不为"一切皆插件"口号继续深化**。若未来需要真正的"运行时动态加载第三方插件"（市场下载即插即用），当前 `plugin_bus`（编译期 `Arc::new` 静态注册）**架构上无法支撑**，须另起炉灶（WASM 插件沙箱或动态链接库方案）——那是独立量级工程，不在本 ADR 范围内，需单独立项评估。

## 后果

- 架构清晰度：明确两套机制的职责边界，避免后续开发者把 DSH / IPC 命令误塞进 plugin_bus。
- 不做的事：不承诺"一切皆插件"的完整性；不投入动态插件加载基础设施（除非单独立项）。
- 已知缺口：内部工具层已插件化，IPC 命令层与第三方插件动态加载仍为硬编码/缺失，属预期内的范围边界。
