// Copyright (c) 2026 MeeJoy

// commands module — `legacy` is the single source of truth for the IPC
// surface.

pub mod types;
pub mod legacy;
pub mod memory;
pub mod task;
pub mod session;
pub mod config;
pub mod gateway;
pub mod agent;
pub mod misc;
pub mod notebook;
// Webview → Rust log forwarder. The bundled `main.jsx`
// `diagLog()` function calls `invoke('tupai_emit_log', ...)` so
// JS-side errors end up in the same `tupai.log` next to the
// binary that the Rust side writes to via `crate::logging`.
pub mod diag_log;
// PCUI 路线 (UIA + CDP + OCR 路由器)
// The 16 commands in this module
// surface the three-strategy router to the front-end; backends are
// pending the real implementations of `uiautomation`,
// `chromiumoxide`, and `paddleocr`.
pub mod pc_automation;

// P0 infrastructure: hardware / crypto / models commands.
// Enabled (Step 1 un-gating) — modules live under `commands::`.
pub mod hardware;
pub mod crypto;
pub mod models;
pub mod model_sources;
pub mod system; // silent-upgrade + monitoring commands
pub mod diagnostics; // Dev-mode self-diagnostics + log collection

// P2 §1 / §2: manual teaching + self-healing framework.
// Enabled (Step 3 un-gating) — `mod automation` is now active.
pub mod teaching;

// P0 §1/§5 + P1 §2: skill execution + automation engine.
// Enabled (Step 1 un-gating) — `mod skill` and `mod automation` are now
// declared in `lib.rs`.
pub mod skill;
pub mod skill_discovery; // remote MCP skill search -> evaluate -> adopt -> run
pub mod skill_cache; // local cache + diff for the remote skill catalog (survives 502s)
pub mod automation; // (commands/automation.rs)
// Track F — interactive prompt answer/cancel commands. Declared
// here so the module compiles; the two commands are NOT yet
// registered in `lib.rs`'s `invoke_handler!` (integration step).
pub mod automation_prompt;

// Runtime brand info — replaces hard-coded VITE_APP_* env vars.
pub mod brand_info;

// Profile patch layer (deepseek-harness style: skill set + config + display brand).
pub mod profile;

// DSH upstream management (profile-backed runtime-registry Upstream).
pub mod dsh;

// Plugin Market — "everything is a plugin" surface: network-wide DSH plugin
// search + DSH plugin CRUD + built-in plugin toggles (profile-backed).
pub mod plugin_market;

// Preset packages — the dsh-equivalent portable agent mechanism: import/export
// shareable `.dshpreset` files with safe preview + atomic (never-overwrite-ID)
// install. Mirrors dsh-desktop's Agent Preset UX in our Tauri+Rust stack.
pub mod preset_pack;

// P2 §3 — multi-GPU status + skill/MCP task queue.
// First cut — commands are wired into the invoke layer; the real
// parallel worker + SQLite-backed queue are not yet implemented.
pub mod gpu;
pub mod task_queue;

// 设备注册用硬件 ID 检索
// 跨平台命令:ioreg / Get-CimInstance / /etc/machine-id / uuid v4 fallback
pub mod hardware_id;

// 设备注册命令 - 使用 Rust reqwest，不依赖 WebView2 API
pub mod device_register;

// Compile-time build metadata (git SHA, build time,
// target triple) exposed to the front-end About / crash-report
// support bundle. Stamped by `build.rs` into `crate::build_info::*`.
pub mod build_info;

// UIRPA: 技能驱动 + 自适应执行 + 加密落盘 +
// VLM 救援. 13 commands surface the registry / executor /
// encrypted store / per-execution state machine to the front-end.
// Lower layers (`pc_automation::skill::*` / `pc_automation::executor::*`)
// The public wire shape (serde
// `rename_all = "camelCase"`) is defined inline in this module
// until the lower layers stabilise.
pub mod uirpa;

// 跨窗口悬浮窗(floating window)状态机。state 存 Rust,
// 主窗口和 `floating-window` 独立 webview 都通过 IPC 同步,主窗口
// 关掉时只 hide() 不 destroy(),浮窗因此能"主窗口关闭后单独存在"。
// 12 个 `fw_*` 命令 + 1 个 `fw_install_main_close_intercept` 命令
// 给 lib.rs 注册。
pub mod floating_window;

// IM 渠道配置管理(企业微信/飞书/钉钉/Webhook/Websocket)。
// 配置持久化到 im_config.json,支持 im_config_get/set/remove、
// im_send、im_sync_send、im_channels 共 6 个命令。
// 注:该模块在 v2 分支迁移时丢失,从 tupauto 分支恢复。
pub mod im_config;

// IM 扫码 OAuth 命令（飞书 / Lark 优先；URL 全来自 im_endpoints）。
// 扫码成功后调 im_config_set 完成直连长连接建立。
pub mod im_oauth;

// IM 通用扫码登录命令（微信 iLink / QQ Bot / 企微）。
// im_qr_begin → im_qr_poll → im_qr_cancel 三联，状态由 QrLoginState 注入。
pub mod im_qr_login;

// IM 对象选择命令（好友/群组/文档列表）。
// im_list_targets(channel_id, target_type, query?) 列出可发送目标。
pub mod im_targets;

// Hermes 自动记忆升级 V2 — IPC 命令层。
// 7 个 memory_* 命令暴露 hermes::memory_evolution 数据层给前端:
//   memory_write_outcome / memory_search / memory_get_lineage /
//   memory_dedupe / memory_get_recent / memory_save_insight / memory_reflect
// 操作的是 tupai.db 中的 memories 表（不是 hermes_memories 空表）。
pub mod memory_evolution;

// MCP v2 / API 代理命令。把前端 `fetch('https://api.tuptup.top/...')`
// 改走 Tauri 命令(走 rustls 而不是 WebView2 fetch),并提供
// 重试和结构化错误。前端所有 MCP 调用（含 LLM）统一走此模块：
//   - skill.scene_tags / skill.top_by_tags 等技能查询
//   - task.poll_pending / task.complete 等任务管理
//   - llm.stream_request（LLM 流式对话，替代旧的 /v1/llm/stream 直连）
//   - client.check_update 等设备检查
// 不要在前端直接 fetch('https://api.tuptup.top/api/v2/mcp')，
// 走 invoke('mcp_call_v2') 以绕开 WebView2 TLS 黑洞。
pub mod mcp_proxy;
pub mod network_guard;

// 扩展流式 / 整体执行 / IM 连接状态命令（mcp_stream / automation_execute /
// im_connect / im_status）。命令实现完整可编译，暂未在 lib.rs invoke_handler! 注册。
pub mod ext_streams;

// CLI tool resolution commands
pub mod cli_resolve;

// 后台录制数据查询命令 (list_recorded_apps / get_recorded_flowchart / get_app_stats)
pub mod recording_cmds;

// 录制后分析命令 — 借鉴 understudy teach 模式的录制后处理流程。
// 基于已有的 CDP/UIA 录制产物（events + flowchart），进行 AI 意图提取 +
// 路由优化 + 澄清对话 + 三层抽象技能发布。不含视频录制。
// Phase 1: 命令注册 + stub 分析。Phase 2: LLM 集成。
pub mod recording_analysis;

// 长记忆清空命令 — memory_clear（复用 legacy::open_app_db 操作 memories 表）。
// 与 memory.rs（skill memory）/ memory_evolution.rs（V2 记忆演化）区分：
// 本模块只补前端 memoryClear() 桥接所需的清空命令。
pub mod memory_ext;

// AutoSkill 自进化 IPC 命令层 — 7 个 autoskill_* 命令暴露
// autoskill::AutoSkillEngine 给前端：候选扫描 / 草稿确认/拒绝 / 手动触发等。
pub mod autoskill;
// Phase 1: Hermes 自进化 IPC — 会话分析触发 / 信号列表 / 标记消费。
pub mod evolution;

// 租户信息命令 — tenant_get / tenant_register。
// 租户信息持久化到 app_data_dir/tenant.json（参考 im_config.json 模式）。
// 前端 src/web-ui/.../infrastructure/api/tupai/tenant.ts 桥接所需。
pub mod tenant;

// Turn Rating — Hermes 自动升级机制 IPC 层。
// 用户在对话界面 👍/👎 评分，会话结束时计算评估并自动升级技能。
// 前端 flow_chat/store/turnRatingStore.ts 桥接所需。
pub mod turn_rating;

// Skill Rating — 技能执行后用户星级评分 (1-5)。
// 评分写入 memories 表 (task_type="skill_feedback")，供 autoskill 演化引擎消费。
pub mod skill_rating;

// 多源技能市场搜索与下载 — 聚合 LinkFox/Skills.sh/ClawHub/SkillStore/Noique/
// SkillBank.app 的 curl/CLI/API 搜索+下载能力 + FindSkill.com 目录索引。
// 4 个命令: search_multi_market / download_market_skill /
// list_downloaded_market_skills / delete_downloaded_market_skill
pub mod skill_multi_market;

// 流水线管理 — 用户编排多步技能调用 + 轮次执行，执行日志写入 worker_task_log
// 供 AutoSkill 后台挖掘优化。9 个命令: pipeline_create / list / get / update / delete /
// start / pause / stop / complete_round / record_step
pub mod pipelines;

// 公告 / 通知系统后端 — 6 个命令补齐前端 announcement-system 契约：
//   get_pending_announcements / mark_announcement_seen /
//   dismiss_announcement / never_show_announcement /
//   trigger_announcement / get_announcement_tips
// 含内置卡片注册表 + 版本化状态持久化(announcements_state.json) +
// 通过 MCP client.check_update 生成客户端更新提示卡片。
pub mod announcements;

// 系统通知命令 — send_system_notification（任务完成展示，OS 桌面通知）。
// 用 tauri-plugin-notification；前端 useDialogCompletionNotify 调用。
pub mod notification;

// 嵌入式浏览器面板（BrowserPanel）后端桥接 — browser_webview_eval / browser_get_url。
// 按 webview label 在原生 webview 上执行 JS / 读取当前 URL。
// 前端 src/web-ui/.../app/scenes/browser/BrowserPanel.tsx 调用。
pub mod browser;
pub mod computer_use;

// UIRPA IPC signature tests
// Verifies the 13 commands' signatures and the
// camelCase wire format without spinning up a Tauri runtime.
#[cfg(test)]
#[path = "uirpa_integration_test.rs"]
mod uirpa_integration_test;

// Re-export the surface the rest of the crate uses.
pub use legacy::*;
// `agent::*` 提供 get_agents / get_skills / get_skill_detail /
// toggle_skill / get_toolsets 5 个命令(从 legacy.rs 迁移而来)。
pub use agent::*;
pub use misc::*;
pub use notebook::*;
pub use diag_log::*;
// pub use build_info::*; // get_build_info command
// `system::*`, `skill::*`, `automation::*`, `pc_automation::*`
// re-exports are gated off — see the `pub mod` lines above.
pub use diagnostics::*;
// pub use system::*;
// pub use hardware::*;
// pub use crypto::*;
// pub use models::*;
// pub use automation::*;
// PCUI 路线 (UIA + CDP + OCR 路由器)
// pub use pc_automation::*;

// P2 §3 — multi-GPU status + skill/MCP task queue.
// Re-export the three commands so `lib.rs` can call them via
// `commands::gpu::get_gpu_status` / `commands::task_queue::…`.
// pub use gpu::*;
// pub use task_queue::*;

// P2 §1 / §2: re-export teaching + healing commands.
// GATED OFF — see comment above `pub mod teaching`.
// pub use teaching::*;
