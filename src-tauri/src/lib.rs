// Copyright (c) 2026 MeeJoy

use tauri::{Emitter, Manager};

mod commands;
mod agent_infra;
mod sse;
mod markdown;
mod hermes;
mod build_info;
// Generic reversible-effect ledger (deepseek-harness "reversible effects").
mod effects;
// Profile patch layer (deepseek-harness "bundle + user patch").
mod profile;
mod logging;
// PCUI 路线 (UIA + CDP + OCR) is the primary desktop automation stack.
// `pc_automation` owns the three-strategy router; the front-end drives it
// through `commands::pc_automation::*`.
mod pc_automation;
mod automation;
// Wire into binary (Step 1 un-gating).
mod hardware;
mod crypto;
mod models;
mod tray;
mod upgrade;

// tupAI 本地定时任务调度器 shutdown 信号. setup hook 注册 watch sender,
// 未来退出主窗口时调用 `tx.send(true)` 让后台 tick 循环优雅退出.
pub struct CronLocalShutdownTx(pub tokio::sync::watch::Sender<bool>);
mod monitoring;
mod recording;
mod skill;
mod skills_embedded;
// DuckDB 数据中台 —— 7 张核心表的持久化层（worker_task_log /
// teach_record_log / scene_asset_index / skill_version_manage /
// mcp_connect_log / skill_score_eval / skill_auto_iter_draft）。
mod storage;
// 技能评估引擎 —— 基于 worker_task_log 历史执行记录对技能版本进行
// 4 维度加权打分（成功率 40% / 稳定性 25% / 效率 20% / 通用性 15%），
// 结果写入 skill_score_eval 表，达标阈值 85 分。
mod skill_eval;
// AutoSkill 自进化模块 —— 基于 worker_task_log 日志挖掘成功模式，
// 聚类精简 + 参数泛化生成新版本草稿，用户确认后进入 24h 观察期，
// 分数下降 >15 分自动回滚。纯本地实现，不调用 LLM。
mod autoskill;
// Pipeline 运行时占位符解析器 —— `$steps[i].field` → 步骤输出值替换。
// 不改技能接口，不改 DuckDB schema，纯运行时字符串替换。
mod pipeline;
// Worker 异步任务引擎 —— 双通道（轻量 / 重型）优先级队列调度，
// 指数退避重试 + oneshot 取消 + broadcast 事件广播。
// 与 commands/task_queue.rs（旧 stub）解耦，独立模块。
mod worker;
// ACP (Agent Client Protocol) — 简化 CLI 接入层。
// 通过 stdio JSON-RPC 与 ACP 兼容的 CLI 工具（claude-code / codex /
// opencode / omp）通信。从 BitFun 上游精简而来，去掉了 remote SSH /
// session persistence / tool registry 等重依赖。
mod acp;
mod runtime_registry;
// Path A 改造：Cordis 式可插拔能力层（插件总线 + 内置插件）。
// 不引入 dsh 运行时；仅在已有 ToolRegistry2 / EventBus 之上提供 PluginContext。
mod plugin_bus;
mod plugins;

// UIRPA sub-modules are declared inside `pc_automation/mod.rs`. We do
// not redeclare them at the crate root here because that would shadow
// the existing `mod skill;` (line above) which holds the v3 skill set.
// The IPC layer (`commands::uirpa::*`) does not import any of these
// sub-modules — it uses inline types so the surface compiles
// independently.

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    // Install the file logger + panic hook *first*, before any
    // Tauri / axum / setup-hook code runs. `main.rs` sets
    // `windows_subsystem = "windows"` so stdout is detached —
    // this is the only place we get a chance to capture startup
    // panics to disk. Failure to open the log file is non-fatal;
    // the rest of the app still starts.
    let _ = crate::logging::FileLogger::init();
    crate::logging::write_startup_marker("00-after-FileLogger-init");
    crate::logging::install_panic_hook();
    crate::logging::write_startup_marker("01-after-panic-hook");
    crate::logging::FileLogger::write_external(
        "INFO",
        "boot",
        &format!(
            "tupai {} starting; log path = {}",
            env!("CARGO_PKG_VERSION"),
            crate::logging::FileLogger::path().display()
        ),
    );
    crate::logging::write_startup_marker("02-after-boot-info-line");

    // Unbuffered per-stage marker used by `startup_marker!`. We
    // also keep a `startup_stage!` macro that mirrors the same
    // tag to the regular `tupai.log` (good for in-app diagnostics)
    // AND to the unbuffered `tupai-startup.log` (the only line
    // that survives a native crash in Tauri / WebView2 init).
    macro_rules! startup_stage {
        ($tag:expr) => {
            crate::logging::write_startup_marker($tag);
        };
    }
    startup_stage!("03-init-state-constructors");

    // IM ChannelRegistry — im_config_* / im_channels / im_bridge 命令共享。
    let channel_registry = crate::hermes::im::channel_registry::SharedChannelRegistry::new(
        crate::hermes::im::channel_registry::ChannelRegistry::new(),
    );
    // AdapterPool — 按 channel_id 缓存已连接的 IMAdapter, im_bridge / init_im_channels 共享。
    let adapter_pool: crate::hermes::im::channel_registry::SharedAdapterPool =
        std::sync::Arc::new(crate::hermes::im::channel_registry::AdapterPool::new());
    // im_bridge MCP server 实例（进程内，白名单默认为空，可后续通过配置扩展）。
    let im_bridge = crate::agent_infra::mcp::im_bridge::ImBridge::new(
        Default::default(),
        channel_registry.clone(),
        adapter_pool.clone(),
    );
    // 保存克隆用于 setup 阶段的 IM 渠道初始化
    let channel_registry_for_setup = channel_registry.clone();
    let im_bridge_for_setup = im_bridge.clone();
    let adapter_pool_for_setup = adapter_pool.clone();
    // im_config.json 读-改-写锁，保护 im_config_set / im_config_remove 的原子性。
    let im_config_lock: crate::commands::im_config::ImConfigLock =
        std::sync::Arc::new(tokio::sync::Mutex::new(()));
    // 前端桥接渠道集合 — im_set_bridged 写入，inbound auto_reply 循环读取。
    let bridged_channels: crate::commands::im_config::SharedBridgedChannels =
        crate::commands::im_config::new_shared_bridged_channels();

    // IM 扫码 OAuth 状态 (in-memory flow 表)。重启后 flow 失效需重新开始。
    let feishu_oauth_state: crate::commands::im_oauth::FeishuOAuthState =
        crate::commands::im_oauth::FeishuOAuthState::default();

    // IM 通用扫码登录状态 (微信 iLink / QQ Bot / 企微)。
    let qr_login_state: crate::commands::im_qr_login::QrLoginState =
        crate::commands::im_qr_login::QrLoginState::default();

    startup_stage!("10-pre-tauri-builder");
    let builder = tauri::Builder::default();
    // Single-instance MUST be the first plugin — secondary launches need to
    // be intercepted before any other plugin/state init runs. Otherwise the
    // second process would re-open SQLite/DuckDB files (lock races),
    // re-bind gateway port 8642 / dashboard port 9119 (bind failure),
    // re-register the tray icon (duplicate icons), and re-register global
    // shortcuts (only one process can own a hotkey → second crashes).
    // Desktop-only: tauri-plugin-single-instance is unsupported on iOS/Android.
    #[cfg(any(target_os = "windows", target_os = "macos", target_os = "linux"))]
    let builder = builder.plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
        // Focus the existing main window and emit an event the frontend
        // can toast on ("app already running"). `app.get_webview_window`
        // is cheap (in-process map lookup) so we can call it on every
        // secondary launch without throttling.
        if let Some(win) = app.get_webview_window("main") {
            let _ = win.show();
            let _ = win.set_focus();
        }
        let _ = app.emit("app://second-instance", ());
    }));
    startup_stage!("10a-after-single-instance-plugin");
    let builder = builder
        .plugin(tauri_plugin_http::init());
    startup_stage!("11-after-http-plugin");
    let builder = builder
        .plugin(tauri_plugin_dialog::init());
    startup_stage!("12-after-dialog-plugin");
    let builder = builder
        .plugin(tauri_plugin_fs::init());
    startup_stage!("13-after-fs-plugin");
    // tauri-plugin-updater removed — update check now goes through MCP
    // (POST /api/v2/mcp action=update.check) instead of the built-in
    // updater endpoint which required Bearer token injection it cannot do.
    startup_stage!("14-after-updater-plugin-removed");
    // 任务完成展示 / 公告更新提示需要发 OS 级桌面通知，初始化
    // notification 插件（send_system_notification 命令依赖它）。
    let builder = builder
        .plugin(tauri_plugin_notification::init());
    startup_stage!("15-after-notification-plugin");
    // ── Register all plugins declared in Cargo.toml + capabilities ──
    // Previously only http/dialog/fs/updater/notification were registered; updater removed 2026-07;
    // store / global-shortcut / os / process / deep-link / clipboard-manager
    // were declared as dependencies and had capability entries but were never
    // .plugin()‑ed, leaving dead ACL permissions.  shell was added later but
    // is DEPRECATED (use tauri-plugin-opener).  autostart is required by the
    // frontend (@tauri-apps/plugin-autostart) but was missing entirely.
    let builder = builder
        .plugin(tauri_plugin_store::Builder::default().build());
    startup_stage!("15a-after-store-plugin");
    let builder = builder
        .plugin(tauri_plugin_global_shortcut::Builder::default().build());
    startup_stage!("15b-after-global-shortcut-plugin");
    let builder = builder
        .plugin(tauri_plugin_os::init());
    startup_stage!("15c-after-os-plugin");
    let builder = builder
        .plugin(tauri_plugin_process::init());
    startup_stage!("15d-after-process-plugin");
    let builder = builder
        .plugin(tauri_plugin_deep_link::init());
    startup_stage!("15e-after-deep-link-plugin");
    let builder = builder
        .plugin(tauri_plugin_clipboard_manager::init());
    startup_stage!("15f-after-clipboard-manager-plugin");
    let builder = builder
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            None,
        ));
    startup_stage!("15g-after-autostart-plugin");
    // tauri-plugin-shell is intentionally NOT registered: its `open` command
    // is deprecated (since 2.1.0) in favour of tauri-plugin-opener, and both
    // plugins inject conflicting <a target=_blank> click interceptors.
    let builder = builder
        .plugin(tauri_plugin_opener::init());
    startup_stage!("15h-after-opener-plugin");
    let builder = builder
        .invoke_handler(tauri::generate_handler![
            commands::chat,
            commands::chat_stream,
            commands::cancel_chat_stream,
            commands::get_memories,
            commands::add_memory,
            commands::update_memory,
            commands::delete_memory,
            commands::compact_memories,
            commands::get_tasks,
            commands::create_task,
            commands::update_task,
            commands::delete_task,
            commands::get_config,
            commands::set_config,
            // 会话相关
            commands::session::chat_session_save,
            commands::session::chat_session_load,
            commands::session::chat_session_delete,
            commands::get_sessions,
            commands::create_session,
            commands::delete_session,
            commands::toggle_pin_session,
            commands::update_session_model,
            commands::update_session_workspace,
            commands::update_session_title,
            commands::get_session_response_id,
            commands::set_session_response_id,
            // 消息相关
            commands::get_messages,
            commands::add_message,
            commands::save_pasted_attachment,
            commands::import_attachment_from_path,
            commands::upload_file_attachments,
            // 工作区相关
            commands::get_workspaces,
            commands::create_workspace,
            commands::update_workspace,
            commands::delete_workspace,
            commands::set_workspace,
            commands::get_current_workspace,
            commands::create_terminal_session,
            commands::write_terminal_input,
            commands::resize_terminal_session,
            commands::close_terminal_session,
            // 智能体相关
            commands::get_agents,
            commands::get_skills,
            commands::get_skill_detail,
            commands::toggle_skill,
            commands::get_toolsets,
            commands::get_market_skills,
            commands::install_skill,
            commands::uninstall_skill,
            commands::check_skill_updates,
            commands::update_skill,
            commands::inspect_market_skill,
            // 编译期内置技能（代码嵌入 Rust 二进制）
            skills_embedded::get_builtin_skills_command,
            skills_embedded::record_builtin_skill_run_command,
            skills_embedded::get_builtin_skill_coverage_command,
            commands::get_cron_jobs,
            commands::create_cron_job,
            commands::restart_hermes_dashboard,
            commands::check_dashboard_running,
            commands::restart_hermes_gateway,
            commands::check_gateway_running,
            commands::stop_hermes_gateway,
            commands::stop_hermes_dashboard,
            commands::ensure_hermes_gateway_running,
            commands::ensure_hermes_dashboard_running,
            // Self-diagnostics + log collection. Always
            // registered so the user can pull error context from the
            // running app without leaving the UI.
            commands::run_self_diagnostics,
            commands::collect_recent_logs,
            commands::auto_fix_hermes_connection,
            commands::reveal_log_file,
            commands::analyze_log_for_errors,
            commands::try_auto_fix_error,
            commands::start_dev_log_watcher,
            commands::stop_dev_log_watcher,
            // Webview-side console forwarder. The webview's
            // `main.jsx` `diagLog()` calls this so JS errors /
            // unhandledrejection events land in `tupai.log` next
            // to the binary (matching the path the Rust side
            // uses), even when the in-window diag overlay is
            // closed in release builds.
            commands::tupai_emit_log,
            commands::is_dev_log_watcher_active,
            // 启动诊断通道: 前端首屏加载时调一次, 拉取启动期错误/警告列表
            // 渲染 toast / 诊断面板。配合后端 record_diagnostic() 使用。
            commands::get_startup_diagnostics,
            commands::read_file_content,
            commands::write_file_content,
            commands::create_directory_if_not_exists,
            commands::pause_cron_job,
            commands::resume_cron_job,
            commands::trigger_cron_job,
            commands::delete_cron_job,
            commands::get_dashboard_logs,
            commands::get_dashboard_primary_model_config,
            commands::get_model_options,
            commands::get_configured_model_candidates,
            commands::save_dashboard_primary_model_config,
            commands::set_default_model,
            commands::get_dashboard_env_vars,
            commands::set_dashboard_env_var,
            commands::delete_dashboard_env_var,
            commands::reveal_dashboard_env_var,
            commands::test_gateway_connection,
            commands::get_gateway_info,
            commands::get_hermes_version_info,
            commands::update_hermes_agent,
            // 文件操作相关
            commands::list_directory,
            commands::read_file,
            commands::get_file_preview,
            commands::open_file_external,
            commands::write_file,
            commands::delete_file,
            commands::create_directory,
            // tupAI P1 §5 — 在指定目录派生独立进程副本
            commands::launch_new_instance,
            // notebook
            commands::list_notebook_tree,
            commands::create_notebook_folder,
            commands::rename_notebook_folder,
            commands::delete_notebook_folder,
            commands::create_notebook_note,
            commands::rename_notebook_note,
            commands::delete_notebook_note,
            commands::get_notebook_note,
            commands::update_notebook_note,
            commands::search_notebook_notes,
            commands::move_notebook_folder,
            commands::move_notebook_note,
            // 数据迁移相关
            commands::migrate_memories_to_db,
            commands::migrate_tasks_to_db,
            commands::needs_migration,
            // Hermes 集成（hermes 模块）
            hermes::agent::hermes_create_task,
            hermes::agent::hermes_list_tasks,
            hermes::agent::hermes_task_stats,
            hermes::cron::hermes_cron_list,
            hermes::cron::hermes_cron_add,
            hermes::cron::hermes_cron_remove,
            // 本地定时任务: 应用内自管 store + 调度器 + 执行历史
            hermes::cron_local::cron_local_list,
            hermes::cron_local::cron_local_create,
            hermes::cron_local::cron_local_pause,
            hermes::cron_local::cron_local_resume,
            hermes::cron_local::cron_local_trigger,
            hermes::cron_local::cron_local_delete,
            hermes::cron_local::cron_local_get_runs,
            hermes::cron_local::cron_local_clear_runs,
            hermes::cron_local::cron_local_set_token,
            hermes::hermes_set_device_token,
            hermes::bash_validator::hermes_bash_validate,
            hermes::llm_service::hermes_llm_complete,
            // 路径助手：renderer 拿不到 process.env，~ 展开交给 Rust
            commands::get_home_dir,
            commands::expand_home_path_command,
            // Teaching + self-healing commands (commands/teaching.rs).
            commands::teaching::start_recording,
            commands::teaching::stop_recording,
            commands::teaching::get_recording_status,
            commands::teaching::pause_recording,
            commands::teaching::resume_recording,
            commands::teaching::discard_recording,
            // 录制 → 流程图 实时转换（前端 FlowchartView 数据源）
            commands::teaching::recording_to_flowchart,
            // 持久化编辑后的流程图
            commands::teaching::save_flowchart,
            commands::teaching::attempt_heal,
            commands::teaching::set_healing_mode,
            commands::teaching::get_healing_history,
            // teaching proposal commands — 之前标 #[allow(dead_code)] 但未注册,
            // 前端 invoke 会 404。功能已实现完整(SkillProposal CRUD),补注册。
            commands::teaching::push_proposal,
            commands::teaching::list_proposals,
            commands::teaching::delete_proposal,
            // 录制后分析命令 — 基于已有 CDP/UIA 录制产物进行 AI 分析。
            // analyze_recording / get_analysis_status / refine_analysis / publish_analyzed_skill
            commands::recording_analysis::analyze_recording,
            commands::recording_analysis::get_analysis_status,
            commands::recording_analysis::refine_analysis,
            commands::recording_analysis::publish_analyzed_skill,
            // 后台录制数据查询命令 (用户点击软件时加载已存储的流程图)
            commands::recording_cmds::list_recorded_apps_cmd,
            commands::recording_cmds::get_recorded_flowchart_cmd,
            commands::recording_cmds::get_app_stats_cmd,
            // system_software + browser automation commands.
            commands::automation::detect_installed_software,
            commands::automation::scan_installed_software,
            commands::automation::launch_software_cmd,
            commands::automation::detect_installed_browsers_cmd,
            commands::automation::start_browser_session_cmd,
            commands::automation::execute_browser_action_cmd,
            commands::automation::close_browser_session_cmd,
            // 枚举 CDP 浏览器会话的所有页面目标（替代旧的 GetTargets stub）
            commands::automation::list_browser_targets_cmd,
            // v1.9.6 重打：确保/诊断浏览器会话（空 browserType 自动探测最佳浏览器）
            commands::automation::ensure_browser_session_cmd,
            commands::automation::get_browser_session_status_cmd,
            // 内嵌 webview 打开外部 URL(网页类技能)
            commands::automation::open_url_in_webview,
            commands::automation::is_webview_window_open,
            commands::automation::close_webview_window,
            // 嵌入式浏览器面板（BrowserPanel）原生 webview 桥接
            commands::browser::browser_webview_eval,
            commands::browser::browser_get_url,
            // silent upgrade + monitoring commands.
            commands::system::check_silent_upgrade,
            commands::system::trigger_silent_upgrade_now,
            commands::system::set_auto_upgrade_enabled,
            commands::system::install_pending_upgrade_now,
            commands::system::set_monitoring_enabled,
            commands::system::get_recent_activity_log,
            commands::system::get_monitoring_enabled,
            commands::system::get_silent_upgrade_plan,
            // 自建升级流水线命令 (绕过 Tauri updater API)。
            commands::system::check_for_updates,
            commands::system::install_update,
            commands::system::restart_app,
            commands::system::silent_download_upgrade,
            commands::system::open_external,
            // OS-compatibility helpers — frontend calls on first launch to
            // detect macOS Accessibility / Windows OCR language pack gaps
            // and surface a one-click fix banner. P1-2 / P1-6.
            commands::system::check_os_compatibility,
            commands::system::open_os_permission_panel,
            // PCUI 路线 (UIA + CDP + OCR 路由器).
            //
            // 18 个 pc_automation 命令已注册到 invoke_handler —— 前端
            // 调用 `router_health` / `check_uia` / `execute_step` 等命令
            // 能正常到达 Rust 端。UIA / CDP / OCR / ScreenParser
            // 后端以真实(Windows)/ stub(非 Windows)形式存在,叶
            // 节点 probe 返回 `false` / `all_strategy_fail`,但 IPC
            // 表面是真实的。
            commands::pc_automation::router_health,
            commands::pc_automation::check_uia,
            commands::pc_automation::check_cdp,
            commands::pc_automation::check_ocr,
            commands::pc_automation::check_broker,
            commands::pc_automation::list_brokers,
            commands::pc_automation::configure_broker,
            commands::pc_automation::set_app_profile,
            commands::pc_automation::list_app_profiles,
            commands::pc_automation::get_app_profile,
            commands::pc_automation::parse_selector,
            commands::pc_automation::parse_step,
            commands::pc_automation::select_strategy,
            commands::pc_automation::execute_step,
            commands::pc_automation::no_broker_available,
            commands::pc_automation::broker_only,
            // Flat screen-content composer (UIA + OCR).
            commands::pc_automation::parse_screen,
            commands::pc_automation::check_screen_parser,
            // Auto-launch CDP browser for skills that need it.
            commands::pc_automation::launch_cdp_browser,
            // UIA direct surface —— 让 skillBridge.cap.uia 不再是空实现。
            // AutomationPage 点「执行」→ cap.uia.find/click/type → 这些命令
            // → WindowsUiaBackend → 真正控制外部软件（微信/WPS/钉钉等）。
            commands::pc_automation::uia_get_focused_window,
            commands::pc_automation::uia_find,
            commands::pc_automation::uia_click,
            commands::pc_automation::uia_type,
// Cua Driver sidecar —— 通过 MCP JSON-RPC over stdio 与 cua-driver
// 子进程通信，替代 enigo 作为主要输入路径。
// 前端可调用 check_cua_driver 获取健康状态，或直接通过
// cua_driver_click / cua_driver_type_text / cua_driver_invoke
// 执行输入操作。
commands::pc_automation::check_cua_driver,
commands::pc_automation::cua_driver_click,
commands::pc_automation::cua_driver_type_text,
commands::pc_automation::cua_driver_invoke,
// BrowserSkill (腾讯开源) —— 独立浏览器 Agent 驱动后端，
// 通过 bsk CLI 操作用户已登录的真实浏览器，与 CDP 感知层并行互补。
commands::pc_automation::browser_skill_health,
commands::pc_automation::browser_skill_exec,
commands::pc_automation::browser_skill_status,
commands::pc_automation::browser_skill_setup,
// Computer Use 设置页命令 —— 前端 SessionConfig.tsx 调用（此前缺失）。
commands::computer_use::computer_use_get_status,
commands::computer_use::computer_use_open_system_settings,
            // hardware / crypto / models commands.
            commands::hardware::detect_hardware,
            commands::hardware::match_hardware_version,
            commands::hardware::get_recommended_version,
            commands::hardware::set_hardware_version,
            commands::hardware::get_system_info,
            // 设备注册用硬件 ID(升级方案 §4)
            // 跨平台命令:ioreg / Get-CimInstance / /etc/machine-id / uuid v4 fallback
            commands::hardware_id::get_hardware_id,
            // 设备注册 - 使用 Rust reqwest，不依赖 WebView2
            commands::device_register::register_device,
            commands::device_register::renew_device_token,
            commands::device_register::check_bind_status,
            // Compile-time build metadata (git SHA, build time,
            // target triple) for the About panel / crash-report support bundle.
            // Cheap (no IO, just const reads); always-on.
            commands::build_info::get_build_info,
            // Runtime brand info — replaces hard-coded VITE_APP_* env vars.
            commands::brand_info::get_brand_info,
            commands::brand_info::is_oem_build,
            // Profile patch layer (skill set + config + display brand).
            commands::profile::get_profile,
            commands::profile::set_active_profile,
            commands::profile::list_profiles,
            commands::crypto::wipe_all_local_data,
            commands::crypto::encrypt_data,
            commands::crypto::decrypt_data,
            commands::models::change_model_path,
            commands::models::scan_models,
            commands::models::delete_model,
            // Model source catalog (tupAI cloud) surfaced to the Settings model page.
            commands::model_sources::list_tupai_cloud_models,
            // skill execution + automation commands.
            commands::skill::compile_skill,
            commands::skill::decompile_skill,
            commands::skill::execute_skill,
            commands::skill::cancel_execution,
            commands::skill::adopt_proposal,
            commands::skill::list_inbox,
            commands::skill::dismiss_proposal,
            commands::skill::user_accept_proposal,
            // SkillMemory —— 之前漏注册，配合上面的 init_skill_db 一并启用。
            // 三个命令都通过 `try_state::<SkillDb>` 读 sqlite (init 失败时返回
            // 友好错误而非 panic)，不需要再开 connection。
            commands::memory::search_skills,
            commands::memory::get_lineage,
            commands::memory::get_run_stats,
            // Hermes 修改 / 优化 / 保存技能 —— 把外部下载或本地编辑后的
            // skill.md 明文落盘到 <app_data>/skills_optimized/<skill_id>.md。
            // 不加密：这部分技能是用户主动认可的本地资产，加密反而会让
            // 用户无法用文本编辑器查看 / 调试。下载源仍保持内存态不落盘。
            commands::skill::save_optimized_skill,
            commands::skill::list_optimized_skills,
            commands::skill::delete_optimized_skill,
            commands::skill_discovery::discover_skills_from_server,
            commands::skill_discovery::check_remote_skill_updates,
            commands::skill_discovery::adopt_skill_upgrade,
            // Skill catalog cache: local mirror of the remote
            // `skill.list` payload so the "available updates" UI
            // keeps working when `ai.tuptup.top` returns 502.
            commands::skill_cache::get_cached_skill_catalog,
            commands::skill_cache::refresh_skill_catalog,
            commands::skill_cache::get_skill_catalog_diff,
            commands::skill_cache::clear_skill_catalog_cache,
            commands::skill_cache::write_skill_catalog_cache,
            // Pure-local skill search — the front-end's first
            // call when the user types into the Skills panel.
            // Returns in milliseconds because it never hits
            // the network.
            commands::skill_cache::search_skill_catalog_local,
            // Coalesced background refresh. The front-end calls
            // this on tab open or via a "Refresh" button. The
            // function is sync (returns immediately after
            // spawning a tokio task) and returns
            // `RefreshOutcome` so the UI can show "refreshing
            // now" / "already in flight" / "cache is fresh"
            // feedback.
            commands::skill_cache::spawn_background_refresh,
            // 多源技能市场搜索与下载（8 个市场聚合）
            commands::skill_multi_market::search_multi_market,
            commands::skill_multi_market::download_market_skill,
            commands::skill_multi_market::list_downloaded_market_skills,
            commands::skill_multi_market::delete_downloaded_market_skill,
            commands::automation::pause_execution,
            commands::automation::resume_execution,
            commands::automation::get_execution_status,
            commands::automation::get_execution_history,
            // Single-step debugging commands.
            commands::automation::set_execution_breakpoint,
            commands::automation::clear_execution_breakpoint,
            commands::automation::clear_execution_breakpoints,
            commands::automation::enable_step_mode,
            commands::automation::disable_step_mode,
            commands::automation::step_over,
            // v5.7 — Evolution panel commands: wire the
            // "自进化" page's stats counters + auto-evolve
            // toggle to real Rust-side state instead of the
            // localStorage / component-state stubs.
            commands::automation::report_skill_execution_result,
            commands::automation::set_auto_evolve,
            commands::automation::get_auto_evolve,
            commands::automation::get_evolution_state,
            // v5.8 — per-skill dedup + circuit breaker
            // decision API, and the "重置统计" reset.
            commands::automation::should_skip_skill,
            commands::automation::clear_evolution_stats,
            // automation evolution/debug commands — 之前标 #[allow(dead_code)] 但未注册,
            // 前端 invoke 会 404。功能已实现完整,补注册。
            commands::automation::trigger_evolution_now,
            commands::automation::get_evolution_history,
            commands::automation::disable_automation,
            // multi-GPU status + skill/MCP task queue commands.
            //
            // 4 个 gpu / task_queue 命令已注册到 invoke_handler —— 前端
            // 调用能正常到达 Rust 端。第一版是 stub(queue 返回 id,
            // list 返回 [], cancel 是 log 一行;gpu 调用底层
            // HardwareDetector),worker + 持久化队列补齐后即可生效。
            commands::gpu::get_gpu_status,
            commands::task_queue::enqueue_skill_task,
            commands::task_queue::list_queued_tasks,
            commands::task_queue::cancel_queued_task,
            // UIRPA.
            //
            // 13 个 uirpa 命令已注册到 invoke_handler —— 前端
            // `uirpaListSkills` / `uirpaExecuteSkill` 等调用能正常
            // 到达 Rust 端。命令体多数 stub 返回 "not yet wired"
            // 错误,但 UI 自动化层 + pause/resume/get_status 三个
            // 状态命令是真实现(在 UirpaState::executions map 上
            // 读写)。后续把底层 skill/executor backend 补齐即可。
            commands::uirpa::uirpa_list_skills,
            commands::uirpa::uirpa_import_skill,
            commands::uirpa::uirpa_export_skill,
            commands::uirpa::uirpa_delete_skill,
            commands::uirpa::uirpa_encrypt_skill,
            commands::uirpa::uirpa_decrypt_skill,
            commands::uirpa::uirpa_execute_skill,
            commands::uirpa::uirpa_pause_execution,
            commands::uirpa::uirpa_resume_execution,
            commands::uirpa::uirpa_get_execution_status,
            commands::uirpa::uirpa_list_executions,
            commands::uirpa::uirpa_validate_selector,
            commands::uirpa::uirpa_subscribe_events,
            // uirpa export commands — 功能已实现完整(episodic 查询 + UI-TARS JSONL 导出),
            // 但之前未注册到 generate_handler!,前端 invoke 返回 404。补注册。
            commands::uirpa::uirpa_export_episodic,
            commands::uirpa::uirpa_export_trajectory,
            // 跨窗口悬浮窗(floating window) 12 个 fw_*
            // 命令 + 1 个 main-close 拦截器。挂在 invoke_handler
            // 末尾,跟其他命令同等待遇。前端 api/
            // `renderer-floating-window.js` 调它们。
            commands::floating_window::fw_get_state,
            commands::floating_window::fw_open,
            commands::floating_window::fw_close,
            commands::floating_window::fw_focus,
            commands::floating_window::fw_hide_main_window,
            commands::floating_window::fw_show_main_window,
            commands::floating_window::fw_finish_session,
commands::floating_window::fw_chat_to_main,
            commands::floating_window::fw_chat_transfer_to_main,
            commands::floating_window::fw_dock,
            commands::floating_window::fw_undock,
            commands::floating_window::fw_move,
            commands::floating_window::fw_resize,
            commands::floating_window::fw_set_payload,
            commands::floating_window::fw_set_dock_offset,
            commands::floating_window::fw_set_dock_edge,
            commands::floating_window::fw_set_last_session_id,
            commands::floating_window::fw_minimize,
            commands::floating_window::fw_restore,
            commands::floating_window::fw_install_main_close_intercept,
            // IM 渠道配置命令(从 tupauto 分支恢复)
            commands::im_config::im_config_get,
            commands::im_config::im_config_set,
            commands::im_config::im_config_remove,
            commands::im_config::im_send,
            commands::im_config::im_send_skill_params,
            commands::im_config::im_sync_send,
            commands::im_config::im_channels,
            commands::im_config::im_set_bridged,
            // IM 扫码 OAuth (飞书 / Lark) — 前端 ImSettingsTab 调用，
            // begin/poll/cancel 三联。状态由 State<'_, FeishuOAuthState> 注入。
            commands::im_oauth::im_oauth_begin_feishu,
            commands::im_oauth::im_oauth_poll_feishu,
            commands::im_oauth::im_oauth_cancel_feishu,
            // IM 通用扫码登录 (微信 iLink / QQ Bot / 企微) —
            // begin/poll/cancel 三联。状态由 State<'_, QrLoginState> 注入。
            commands::im_qr_login::im_qr_begin,
            commands::im_qr_login::im_qr_poll,
            commands::im_qr_login::im_qr_cancel,
            // MCP v2 + API 代理:webview 的 mcpClient.js 改走
            // 这三个命令而不是直接 fetch('https://api.tuptup.top/...'),
            // 绕开 WebView2 在混合内容 / 部分 TLS 场景下的
            // "Failed to fetch" 黑洞。LLM 流式通过 MCP llm.stream_request 调用。
            commands::mcp_proxy::mcp_call_v2,
            commands::mcp_proxy::mcp_api_get,
            commands::mcp_proxy::mcp_api_post,
            // 公告 / 通知系统 — 前端 announcement-system 契约的 6 个命令。
            // get_pending_announcements 内部通过 MCP client.check_update
            // 拉客户端更新并生成更新提示卡片。
            commands::announcements::get_pending_announcements,
            commands::announcements::mark_announcement_seen,
            commands::announcements::dismiss_announcement,
            commands::announcements::never_show_announcement,
            commands::announcements::trigger_announcement,
            commands::announcements::get_announcement_tips,
            // 任务完成展示 — OS 级桌面通知（useDialogCompletionNotify 调用）。
            commands::notification::send_system_notification,
            // CLI tool resolution
            commands::cli_resolve::resolve_cli_tool,
            commands::cli_resolve::resolve_cli_batch,
            commands::cli_resolve::check_skill_cli_deps,
            // im_bridge MCP server 命令（进程内，供前端 / LLM 调用）
            crate::agent_infra::mcp::im_bridge::im_bridge_dispatch,
            crate::agent_infra::mcp::im_bridge::im_bridge_list_tools,
            crate::agent_infra::mcp::im_bridge::im_bridge_list_pending,
            crate::agent_infra::mcp::im_bridge::im_bridge_confirm,
            crate::agent_infra::mcp::im_bridge::im_bridge_revoke,
            crate::agent_infra::mcp::im_bridge::im_bridge_audit,
            crate::agent_infra::mcp::im_bridge::im_bridge_add_whitelist,
            crate::agent_infra::mcp::im_bridge::im_bridge_remove_whitelist,
            // Hermes 自动记忆升级 V2 — 7 个 memory_* 命令暴露
            // hermes::memory_evolution 数据层给前端。操作 tupai.db 的
            // memories 表（带 version / parent_id / lineage / outcome 等
            // V2 新列），与 legacy 的 get/add/update/delete_memories 共存。
            //   memory_write_outcome  — writeSuccess/writeFailure 入口
            //   memory_search         — 语义搜索（供对话注入）
            //   memory_get_lineage    — 版本族谱树
            //   memory_dedupe         — 批量去重合并
            //   memory_get_recent     — 取近 N 毫秒记忆
            //   memory_save_insight   — 持久化反思结论
            //   memory_reflect        — 完整 dailyReflection
            commands::memory_evolution::memory_write_outcome,
            commands::memory_evolution::memory_search,
            commands::memory_evolution::memory_get_lineage,
            commands::memory_evolution::memory_dedupe,
            commands::memory_evolution::memory_get_recent,
            commands::memory_evolution::memory_save_insight,
            commands::memory_evolution::memory_reflect,
            // 增量 memory_access 之前漏注册（隐藏 bug），前端调用
            // 会得到 "command not found"。现在补上，让记忆访问计数 /
            // importance 自动升档（cold→warm→hot）真正生效。
            commands::increment_memory_access,
            // 新命令：memory_clear / tenant_get / tenant_register
            commands::memory_ext::memory_clear,
            commands::tenant::tenant_get,
            commands::tenant::tenant_register,
            commands::tenant::tenant_info,
            // Turn Rating — Hermes 自动升级评分
            commands::turn_rating::submit_turn_rating,
            commands::turn_rating::evaluate_session_ratings,
            // Skill Rating — 技能执行星级评分 (1-5)
            commands::skill_rating::submit_skill_rating,
            // 扩展命令：mcp_stream / automation_execute / im_connect / im_status
            commands::ext_streams::mcp_stream,
            commands::ext_streams::automation_execute,
            commands::ext_streams::execute_flowchart_step,
            commands::ext_streams::im_connect,
            commands::ext_streams::im_status,
            // IM 对象选择：列出好友/群组/文档可发送目标
            commands::im_targets::im_list_targets,
            // 流水线管理 — 9 个 pipeline_* 命令（CRUD + 执行控制 + 步骤日志）
            commands::pipelines::pipeline_create,
            commands::pipelines::pipeline_list,
            commands::pipelines::pipeline_get,
            commands::pipelines::pipeline_update,
            commands::pipelines::pipeline_delete,
            commands::pipelines::pipeline_start,
            commands::pipelines::pipeline_pause,
            commands::pipelines::pipeline_stop,
            commands::pipelines::pipeline_complete_round,
            commands::pipelines::pipeline_record_step,
            commands::pipelines::pipeline_get_templates,
            commands::pipelines::pipeline_resolve_params,
            // AutoSkill 自进化 — 7 个 autoskill_* 命令暴露
            // autoskill::AutoSkillEngine 给前端：候选扫描 / 合并候选 /
            // 待确认草稿列表 / 草稿确认（升级+落盘）/ 拒绝 / 手动触发扫描+合并。
            commands::autoskill::autoskill_list_candidates,
            commands::autoskill::autoskill_list_merge_candidates,
            commands::autoskill::autoskill_list_pending_drafts,
            commands::autoskill::autoskill_confirm_draft,
            commands::autoskill::autoskill_reject_draft,
            commands::autoskill::autoskill_trigger_scan,
            commands::autoskill::autoskill_trigger_merge,
            // Phase 1: Hermes 自进化 — 4 个 evolution_* 命令暴露
            // EvolutionOrchestrator 给前端 AutoskillScene 的"会话洞察"tab。
            commands::evolution::evolution_trigger_session_analysis,
            commands::evolution::evolution_list_signals,
            commands::evolution::evolution_list_session_insights,
            commands::evolution::evolution_mark_signal_consumed,
            // Phase 1: 互动输入 — executor 执行中向用户提问的回答/取消命令。
            commands::automation_prompt::automation_answer_prompt,
            commands::automation_prompt::automation_cancel_prompt,
            // BitFun clone-layer compatibility stubs — return sensible
            // defaults so BitFun initialization doesn't error out.
            commands::i18n_set_language,
            commands::i18n_get_current_language,
            commands::i18n_get_supported_languages,
            commands::i18n_get_config,
            commands::i18n_set_config,
            commands::list_persisted_sessions,
            commands::list_persisted_sessions_page,
            commands::terminal_get_shells,
            commands::get_runtime_logging_info,
            commands::git_is_repository,
            commands::get_configs,
            commands::lsp_get_supported_extensions,
            // ACP (Agent Client Protocol) — 简化 CLI 接入层。
            // 15 个命令对应前端 ACPClientAPI.ts 的全部调用；
            // 通过 State<'_, Arc<AcpClientService>> 注入全局服务实例。
            acp::commands::load_acp_json_config,
            acp::commands::save_acp_json_config,
            acp::commands::initialize_acp_clients,
            acp::commands::get_acp_clients,
            acp::commands::stop_acp_client,
            acp::commands::probe_acp_client_requirements,
            acp::commands::predownload_acp_client_adapter,
            acp::commands::install_acp_client_cli,
            acp::commands::create_acp_flow_session,
            acp::commands::start_acp_dialog_turn,
            acp::commands::cancel_acp_dialog_turn,
            acp::commands::get_acp_session_options,
            acp::commands::get_acp_session_commands,
            acp::commands::set_acp_session_model,
            acp::commands::submit_acp_permission_response,
        ]);
    startup_stage!("15i-after-acp-handler");
    let builder = builder
        .invoke_handler(tauri::generate_handler![
            runtime_registry::commands::rr_scan_runtimes,
            runtime_registry::commands::rr_list_runtimes,
            runtime_registry::commands::rr_list_subagents,
            runtime_registry::commands::rr_spawn_instance,
            runtime_registry::commands::rr_add_custom_agent,
            runtime_registry::commands::rr_remove_agent,
            runtime_registry::commands::rr_invoke_subagent,
            runtime_registry::commands::rr_register_upstream,
            runtime_registry::commands::rr_discover_models,
            runtime_registry::commands::rr_set_runtime_enabled,
            // DSH upstream management (profile-backed runtime-registry Upstream).
            commands::dsh::dsh_list_upstreams,
            commands::dsh::dsh_upsert_upstream,
            commands::dsh::dsh_remove_upstream,
            commands::dsh::dsh_set_upstream_enabled,
            // Plugin Market — "everything is a plugin": network-wide DSH plugin
            // search + DSH plugin CRUD + built-in plugin toggles.
            commands::plugin_market::search_dsh_plugins,
            commands::plugin_market::list_dsh_plugins,
            commands::plugin_market::install_dsh_plugin,
            commands::plugin_market::remove_dsh_plugin,
            commands::plugin_market::set_dsh_plugin_enabled,
            commands::plugin_market::list_builtin_plugins,
            commands::plugin_market::set_builtin_plugin_enabled,
            // Preset packages — dsh-equivalent portable agent mechanism
            // (.dshpreset import/export + safe atomic install).
            commands::preset_pack::preset_list,
            commands::preset_pack::preset_preview,
            commands::preset_pack::preset_import,
            commands::preset_pack::preset_export,
            commands::preset_pack::preset_delete,
        ]);
    startup_stage!("15i2-after-runtime-registry-handler");
    #[cfg(feature = "mesh")]
    let builder = builder
        .invoke_handler(tauri::generate_handler![
            // mesh P2P 组网 (feature-gated)
            #[cfg(feature = "mesh")]
            hermes::mesh::commands::mesh_create,
            #[cfg(feature = "mesh")]
            hermes::mesh::commands::mesh_join,
            #[cfg(feature = "mesh")]
            hermes::mesh::commands::mesh_leave,
            #[cfg(feature = "mesh")]
            hermes::mesh::commands::mesh_status,
            #[cfg(feature = "mesh")]
            hermes::mesh::commands::mesh_submit_requirement,
            #[cfg(feature = "mesh")]
            hermes::mesh::commands::mesh_list_peers,
            #[cfg(feature = "mesh")]
            hermes::mesh::commands::mesh_send_file,
        ]);
    startup_stage!("16-after-invoke-handler");
    let builder = builder
        // IM ChannelRegistry — im_config_* / im_channels / im_bridge 命令通过
        // State<'_, SharedChannelRegistry> 注入。
        .manage(channel_registry)
        // im_bridge MCP server 实例 — im_bridge_* 命令通过 State<'_, Arc<ImBridge>> 注入。
        .manage(im_bridge)
        // AdapterPool — im_config_set/remove/send/sync_send 通过 State<'_, SharedAdapterPool> 注入。
        .manage(adapter_pool)
        // im_config 读-改-写锁 — im_config_set/remove 通过 State<'_, ImConfigLock> 注入。
        .manage(im_config_lock)
        // 前端桥接渠道集合 — im_set_bridged 命令通过 State<'_, SharedBridgedChannels> 注入。
        .manage(bridged_channels)
        .manage(feishu_oauth_state)
        .manage(qr_login_state);
    startup_stage!("17-after-manage-states");
    let _builder = builder
        .setup(|app| {
            crate::logging::write_startup_marker("20-setup-hook-entered");
            log::info!("[startup-stage] setup hook entered");

            // 周期性清理过期的 IM OAuth / QR 登录 flow，防止崩溃或断网导致 flow 永驻内存。
            // cleanup_expired 是同步方法（std::sync::Mutex），百微秒级，不阻塞 async runtime。
            {
                let handle = app.handle().clone();
                tauri::async_runtime::spawn(async move {
                    let mut interval = tokio::time::interval(std::time::Duration::from_secs(60));
                    loop {
                        interval.tick().await;
                        if let Some(state) = handle.try_state::<crate::commands::im_oauth::FeishuOAuthState>() {
                            state.cleanup_expired();
                        }
                        if let Some(state) = handle.try_state::<crate::commands::im_qr_login::QrLoginState>() {
                            state.cleanup_expired();
                        }
                    }
                });
            }
            // Cua Driver sidecar 启动预热：软件启动后自动在后台拉起并完成 MCP
            // 握手，避免首次自动化操作时「spawn+握手」卡顿，保证运行流畅。
            // 二进制未找到时 ensure_started 优雅失败（仅记录日志，不阻塞启动）。
            {
                tauri::async_runtime::spawn(async move {
                    let client = crate::pc_automation::cua_driver::CuaDriverClient::shared();
                    if let Err(e) = client.ensure_started().await {
                        log::warn!("[startup] cua-driver warmup failed (will retry on first use): {}", e);
                    } else {
                        log::info!("[startup] cua-driver warmed up successfully");
                    }
                });
            }
            // Window is auto-created from tauri.conf.json (create: true)
            //
            // File logging is installed in `run()` above via
            // `crate::logging::FileLogger::init()` — that writes to
            // `tupai.log` next to the binary and stamps a session
            // header on every startup. Do NOT register
            // `tauri-plugin-log` here as well: it tries to call
            // `log::set_logger` for a second time and the global
            // `log` facade refuses with
            //   "attempted to set a logger after the logging system
            //    was already initialized"
            // which we previously surfaced as a noisy stderr line.
            // The plugin's own file target (under `app_log_dir()`)
            // was never reachable in practice for the same reason.
            let log_dir = app
                .path()
                .app_log_dir()
                .or_else(|_| app.path().app_data_dir())
                .ok();
            if let Some(dir) = log_dir.as_ref() {
                // Best-effort directory creation so the auto-reveal
                // buttons in the diagnostic overlay can still open
                // a folder even when no file has been written there
                // by `tauri-plugin-log` (we removed the plugin).
                if let Err(error) = std::fs::create_dir_all(dir) {
                    eprintln!(
                        "[startup] Failed to create log dir {:?}: {}",
                        dir, error
                    );
                } else {
                    log::info!(
                        "[startup] log file directory: {}",
                        dir.display()
                    );
                }
            }

            // 启动诊断通道: 必须在所有可能失败的 init 步骤之前 manage。
            // 后续 open_app_db / init_skill_db / load_optimized_skills_into_registry /
            // init_im_channels 失败时会调 record_diagnostic() 写入, 前端通过
            // get_startup_diagnostics IPC 拉取并 toast / 渲染诊断面板。
            app.manage(commands::diagnostics::StartupDiagnostics::new());
            crate::logging::write_startup_marker("21-after-startup-diag");

            // 初始化应用数据库（ memories + tasks 表）
            if let Err(e) = commands::open_app_db(app.handle()) {
                log::error!("[startup] Failed to initialize app db: {}", e);
                commands::diagnostics::record_diagnostic(
                    app.handle(),
                    "error",
                    "app_db",
                    format!("初始化应用数据库失败: {}", e),
                );
            }
            crate::logging::write_startup_marker("22-after-app-db");

            // 初始化技能持久化数据库（ tupai.db 中的 skill_versions / skill_runs /
            // skill_evaluations / skill_lineage / skill_fts 五张表 + FTS5 全文索引）。
            // `init_skill_db` 内部用 `app.manage(SkillDb)` 注册 Tauri 全局状态，
            // 后续 `commands::memory::{search_skills,get_lineage,get_run_stats}` 通过
            // `try_state::<SkillDb>` 读取它 (失败时返回友好错误而非 panic)。
            //
            // 失败时降级而非阻断启动:
            //   * 技能记忆 (FTS5 检索 / lineage / run_stats) 不是核心功能, 磁盘满
            //     / 权限问题导致 sqlite 打不开时, 聊天 / 自动化 / IM 等主流程仍应
            //     可用;
            //   * init_skill_db 失败时不注册 SkillDb, 后续 commands::memory::* 命令
            //     通过 try_state 检测到 None 并返回友好错误, commands::skill::*
            //     的 best-effort 写入路径已用 try_state 处理 None (log warn 跳过);
            //   * 错误通过 record_diagnostic 写入启动诊断通道, 前端首屏拉取并 toast。
            if let Err(e) = crate::skill::memory::init_skill_db(app.handle()) {
                log::error!("[startup] Failed to init skill db (degraded): {}", e);
                commands::diagnostics::record_diagnostic(
                    app.handle(),
                    "error",
                    "skill_db",
                    format!("初始化技能数据库失败（已降级，技能记忆功能不可用）: {}", e),
                );
            }
            crate::logging::write_startup_marker("23-after-skill-db");

            // 挂载 Hermes 全局状态（cron / kanban / agent_registry / 多 agent 调度等）
            // 使用 with_persistence 构造函数,自动打开 tupai.db + 派生加密存储,
            // 为 memory_ops / profile / persona / trajectory_store / evolution_stats
            // 提供本地持久化。失败时降级为无持久化的 new() 路径。
            app.manage(hermes::HermesAppState::with_persistence(app.handle()));
            crate::logging::write_startup_marker("24-after-hermes-state");

            // ── Hermes AgentLoop 初始化 ──────────────────────────────────
            // 从 HermesAppState 拿到 tools registry，创建 AgentLoop 并注册为 Tauri 全局状态。
            // Feature flag `agent_loop` 控制是否启用（默认启用）。
            // 工具 handler 在此注册到 ToolRegistry2，AgentLoop.run() 在每次
            // LLM 调用前用这些 schema 注入 tools 字段。
            //
            // 所有 handler 均连接到真实后端实现：
            //   • execute_skill  → commands::skill::execute_skill (AppHandle)
            //   • mcp_call       → mcp_proxy::mcp_call_v2_inner (reqwest + device_token)
            //   • cdp_action     → pc_automation::shared_state().router.cdp
            //   • uia_action     → pc_automation::shared_state().router.uia
            //   • vlm_query      → UIA tree + LLM via mcp_call_v2_inner
            //   • memory_search  → commands::memory_evolution::memory_search (AppHandle)
            {
                let hermes_state = app.state::<hermes::HermesAppState>();
                let tools = hermes_state.tools.clone();



                // ── 插件总线加载（Path A: Cordis 式可插拔能力）──
                // 在全部内置工具注册完成后、AgentLoop 启动前，统一加载插件。
                {
                    let plugin_ctx = crate::plugin_bus::PluginContext {
                        app: app.handle().clone(),
                        tools: hermes_state.tools.clone(),
                        bus: hermes_state.bus.clone(),
                        device_token: hermes_state.device_token.clone(),
                    };
                    let mut plugin_mgr = crate::plugin_bus::PluginManager::new();
                    crate::plugins::register_builtin_plugins(&mut plugin_mgr);
                    plugin_mgr.load_all(&plugin_ctx);
                    log::info!(
                        "[setup] 插件总线已加载 {} 个插件: {:?}",
                        plugin_mgr.names().len(),
                        plugin_mgr.names()
                    );
                }

                let agent_loop = std::sync::Arc::new(hermes::agent_loop::AgentLoop::new(tools.clone()));
                app.manage(agent_loop);
                log::info!(
                    "[setup] Hermes AgentLoop 初始化完成，已注册 {} 个工具",
                    tools.lock().unwrap().list().len()
                );
            }

            // 挂载本地 cron store + 启动后台调度器。
            // 真实可用的应用内定时任务: jobs.json 持久化 + runs/<id>.jsonl
            // 执行历史 + 30s tick 扫描 + LLMService 调用。应用关闭则任务
            // 不跑, 所以用户需要保持应用运行 (系统托盘 / 开机启动)。
            {
                // 多级 fallback 防止 cron 状态被写到 temp_dir (OS 可能清理,
                // 导致定时任务配置丢失)。优先级: app_data_dir → dirs::data_dir
                // → std::env::temp_dir (最后兜底, 会 log::error 提示)。
                let app_data_dir = match app.path().app_data_dir() {
                    Ok(d) => d,
                    Err(e1) => match dirs::data_dir() {
                        Some(d) => {
                            log::warn!(
                                "[startup] app_data_dir failed ({}), falling back to dirs::data_dir: {}",
                                e1,
                                d.display()
                            );
                            d
                        }
                        None => {
                            log::error!(
                                "[startup] app_data_dir ({}) and dirs::data_dir both unavailable, \
                                 cron state will not persist across restarts",
                                e1
                            );
                            commands::diagnostics::record_diagnostic(
                                app.handle(),
                                "error",
                                "app_data_dir",
                                format!("应用数据目录不可用: {}", e1),
                            );
                            std::env::temp_dir()
                        }
                    },
                };
                let cron_dir = app_data_dir.join("tupai").join("cron");
                let cron_state = std::sync::Arc::new(
                    hermes::cron_local::CronLocalState::new(app.handle().clone(), cron_dir),
                );
                app.manage(cron_state.clone());
                let (tx, rx) = tokio::sync::watch::channel(false);
                app.manage(crate::CronLocalShutdownTx(tx));
                cron_state.spawn_scheduler(rx);
                log::info!("[cron_local] initialized and scheduler started");
            }

            // 挂载 teaching + 自愈引擎全局状态。
            // commands/teaching commands are wired in.
            app.manage(commands::teaching::TeachingState::new());

            // 挂载录制后分析全局状态 — 按 app_name 维护分析会话。
            // recording_analysis commands read this state.
            app.manage(commands::recording_analysis::RecordingAnalysisState::new());

            // 挂载 UIRPA v1 全局状态(技能注册表 + 自适应执行器)。
            // 持有 SkillRegistry + AdaptiveExecutor + 内存中的
            // ExecutionStatus 映射。 13 个 uirpa_* 命令读这个 state。
            app.manage(commands::uirpa::UirpaState::new());

            // 挂载静默升级管理器 + AutomationState。
            app.manage(std::sync::Arc::new(
                crate::automation::state::AutomationState::new(),
            ));
            app.manage(std::sync::Arc::new(upgrade::UpgradeManager::new()));
            // 启动时检查是否有已下载完成的 pending 升级,若有则静默安装 + 重启。
            // 失败只记日志,不阻塞启动。
            let app_for_upgrade = app.handle().clone();
            tauri::async_runtime::spawn(async move {
                crate::upgrade::manager::install_pending_on_startup(app_for_upgrade).await;
            });
            app.manage(crate::automation::browser::new_session_map());

            // mesh P2P 组网全局状态（按需 create/join，未激活时为 None）。
            // mesh_* 命令通过 State<'_, MeshHandle> 注入。
            // mesh feature 未启用时不注册。
            #[cfg(feature = "mesh")]
            app.manage(crate::hermes::mesh::MeshHandle::default());

            // 挂载 ClientAdopt 技能注册表（inbox / 自动采纳 / 回滚）。
            app.manage(crate::skill::SkillRegistry::new());

            // 把本地保存的优化技能 (Hermes 修改/优化过的 skill.md) 装回
            // SkillRegistry 内存态。必须在 `app.manage(SkillRegistry::new())`
            // 之后调用 —— `load_optimized_skills_into_registry` 内部用
            // `try_state::<SkillRegistry>` 拿到刚注册的 registry。
            // 下载源保持内存态不落盘 (见 commands::skill::discover_skills_from_server),
            // 只有用户主动保存的优化技能才会走这条路。
            match commands::skill::load_optimized_skills_into_registry(app.handle()) {
                Ok(n) => {
                    log::info!("[startup] loaded {} optimized skills from disk", n);
                    if n > 0 {
                        commands::diagnostics::record_diagnostic(
                            app.handle(),
                            "info",
                            "optimized_skills",
                            format!("从本地加载了 {} 个优化技能", n),
                        );
                    }
                }
                Err(e) => {
                    log::warn!("[startup] load optimized skills failed: {}", e);
                    commands::diagnostics::record_diagnostic(
                        app.handle(),
                        "warn",
                        "optimized_skills",
                        format!("加载本地优化技能失败: {}", e),
                    );
                }
            }

            // AutoSkill 自进化模块 —— 初始化 DuckDB 数据中台 + 技能评估引擎 +
            // AutoSkill 引擎，注册为 Tauri 全局状态。
            //
            // DuckDB 文件放在 <app_data>/duckdb.db，7 张核心表的 DDL 由
            // DuckDBPool::init 执行（IF NOT EXISTS，可安全重复执行）。
            // 初始化失败时降级（不注册 AutoSkillEngine），后续 autoskill_*
            // 命令通过 try_state 检测到 None 并返回友好错误。
            {
                let autoskill_engine = {
                    let db_path = app
                        .path()
                        .app_data_dir()
                        .ok()
                        .map(|d| d.join("duckdb.db"));
                    match db_path {
                        Some(path) => {
                            match crate::storage::DuckDBPool::init(&path) {
                                Ok(pool) => {
                                    let pool_arc = std::sync::Arc::new(pool);
                                    let eval =
                                        std::sync::Arc::new(
                                            crate::skill_eval::SkillEvalEngine::new(
                                                pool_arc.clone(),
                                            ),
                                        );
                                    let engine = std::sync::Arc::new(
                                        crate::autoskill::AutoSkillEngine::new(
                                            pool_arc.clone(),
                                            eval.clone(),
                                        ),
                                    );
                                    // 注册三个 state 供 commands 层使用
                                    app.manage(pool_arc);
                                    app.manage(eval);
                                    app.manage(engine.clone());
                                    log::info!(
                                        "[startup] AutoSkill engine initialized (duckdb: {})",
                                        path.display()
                                    );
                                    Some(engine)
                                }
                                Err(e) => {
                                    log::error!(
                                        "[startup] DuckDB init failed (autoskill degraded): {}",
                                        e
                                    );
                                    commands::diagnostics::record_diagnostic(
                                        app.handle(),
                                        "error",
                                        "autoskill_db",
                                        format!("DuckDB 初始化失败: {}", e),
                                    );
                                    None
                                }
                            }
                        }
                        None => {
                            log::warn!(
                                "[startup] app_data_dir unavailable, AutoSkill not initialized"
                            );
                            None
                        }
                    }
                };

                // Phase 1: Hermes 自进化编排器 (EvolutionOrchestrator)。
                // 依赖 HermesAppState.db (SQLite/tupai.db) + DuckDBPool (autoskill_drafts)。
                // 任一缺失则降级 (不注册), 前端 evolution_* 命令返回友好错误。
                // LLM 走 MCP `llm.stream_request` (hermes_llm_complete_messages),
                // 始终可用, 无需 LLMServiceConfig。MCP 失败时 SessionAnalyzer 内部降级。
                {
                    let hermes_db = app
                        .try_state::<hermes::HermesAppState>()
                        .and_then(|s| s.db.clone());
                    let duckdb_pool = app
                        .try_state::<std::sync::Arc<crate::storage::DuckDBPool>>()
                        .map(|s| s.inner().clone());
                    match (hermes_db, duckdb_pool) {
                        (Some(db), Some(pool)) => {
                            let orch = std::sync::Arc::new(
                                hermes::evolution_orchestrator::EvolutionOrchestrator::new(db, pool),
                            );
                            app.manage(orch);
                            log::info!(
                                "[startup] EvolutionOrchestrator initialized (LLM via MCP llm.stream_request)"
                            );
                        }
                        _ => {
                            log::warn!(
                                "[startup] EvolutionOrchestrator NOT initialized (hermes_db or duckdb pool missing)"
                            );
                            commands::diagnostics::record_diagnostic(
                                app.handle(),
                                "warn",
                                "evolution_orchestrator",
                                "自进化编排器未初始化 (hermes_db/duckdb 缺失)".to_string(),
                            );
                        }
                    }
                }

                // Phase 1: Hermes 自进化周期触发 (每 5 分钟)。
                // session_end 触发的补充 (safety net): 即使会话结束事件漏触发
                // (崩溃 / 非主路径会话), 周期扫描也能补上。dedup (signal_id 唯一
                // 约束) 保证重复分析不产生重复信号。`try_trigger_analysis` 用
                // AtomicBool 保证同一时刻只有一个分析在跑 (与 session_end 钩子
                // 共用); orchestrator 未注册时静默跳过。
                {
                    let app_handle_for_bg = app.handle().clone();
                    tauri::async_runtime::spawn(async move {
                        // 首次延迟 120s, 让 startup 完成所有 db init + HermesAppState 就绪
                        tokio::time::sleep(std::time::Duration::from_secs(120)).await;
                        loop {
                            let _ = hermes::evolution_orchestrator::try_trigger_analysis(
                                &app_handle_for_bg,
                                "periodic",
                            );
                            // 5 分钟间隔 (dedup 保证重复分析不产生重复信号)
                            tokio::time::sleep(std::time::Duration::from_secs(5 * 60)).await;
                        }
                    });
                    log::info!("[startup] EvolutionOrchestrator periodic trigger (5min) spawned");
                }

                // 后台定时扫描任务 —— 每 30 分钟扫描所有 scene，
                // 对单技能候选和合并候选生成草稿。失败不崩溃，只记日志。
                // 同时检查 watching 状态的草稿是否需要回滚。
                // 扫描完成后 emit event 通知前端刷新徽章。
                if let Some(engine) = autoskill_engine {
                    let app_handle_for_bg = app.handle().clone();
                    tauri::async_runtime::spawn(async move {
                        // 首次延迟 120s，让 startup 完成所有 db init
                        tokio::time::sleep(std::time::Duration::from_secs(120)).await;
                        loop {
                            // 动态查询 worker_task_log 里有数据的 scenes
                            let scenes: Vec<String> = {
                                let pool = engine.db();
                                let conn = pool.get_conn();
                                match conn.prepare(
                                    "SELECT DISTINCT scene FROM worker_task_log WHERE skill_id IS NOT NULL",
                                ) {
                                    Ok(mut stmt) => stmt
                                        .query_map([], |row| row.get::<_, String>(0))
                                        .ok()
                                        .map(|rows| {
                                            rows.filter_map(Result::ok).collect()
                                        })
                                        .unwrap_or_else(|| vec!["default".to_string()]),
                                    Err(_) => vec!["default".to_string()],
                                }
                            };
                            let scenes = if scenes.is_empty() {
                                vec!["default".to_string()]
                            } else {
                                scenes
                            };
                            for scene in &scenes {
                                log::info!("[autoskill] 后台扫描 scene={}", scene);
                                // 单技能扫描 + 生成草稿
                                match engine.scan_for_optimization(scene).await {
                                    Ok(candidates) => {
                                        for c in &candidates {
                                            if let Err(e) = engine
                                                .generate_draft(scene, &c.skill_id)
                                                .await
                                            {
                                                log::warn!(
                                                    "[autoskill] generate_draft 失败: scene={}, skill={}, err={}",
                                                    scene, c.skill_id, e
                                                );
                                            }
                                        }
                                        log::info!(
                                            "[autoskill] scene={} 单技能候选 {} 个",
                                            scene,
                                            candidates.len()
                                        );
                                    }
                                    Err(e) => log::warn!(
                                        "[autoskill] scan_for_optimization 失败: scene={}, err={}",
                                        scene, e
                                    ),
                                }
                                // 合并扫描 + 生成合并草稿
                                match engine.scan_merge_candidates(scene).await {
                                    Ok(groups) => {
                                        for g in &groups {
                                            if let Err(e) = engine
                                                .generate_merge_draft(scene, &g.skill_ids)
                                                .await
                                            {
                                                log::warn!(
                                                    "[autoskill] generate_merge_draft 失败: scene={}, skills={:?}, err={}",
                                                    scene, g.skill_ids, e
                                                );
                                            }
                                        }
                                        log::info!(
                                            "[autoskill] scene={} 合并候选 {} 组",
                                            scene,
                                            groups.len()
                                        );
                                    }
                                    Err(e) => log::warn!(
                                        "[autoskill] scan_merge_candidates 失败: scene={}, err={}",
                                        scene, e
                                    ),
                                }
                            }
                            // 回滚检查：检查 watching 状态的草稿是否需要回滚
                            if let Err(e) = engine.rollback_all_degraded(15).await {
                                log::warn!("[autoskill] 回滚检查失败: err={}", e);
                            }
                            // 通知前端刷新草稿徽章
                            let _ = app_handle_for_bg.emit("autoskill://drafts-updated", ());
                            // 30 分钟间隔
                            tokio::time::sleep(std::time::Duration::from_secs(
                                30 * 60,
                            ))
                            .await;
                        }
                    });
                }
            }

            // 挂载跨窗口悬浮窗的全局 state。 主窗口
            // 和 `floating-window` 独立 webview 都从这一份 state 读
            // / 写 —— 是这个架构能成立的关键。
            app.manage(commands::floating_window::FloatingWindowState::new());

            // 挂载 ACP 客户端服务全局 state。
            // acp::commands::* 通过 State<'_, Arc<AcpClientService>> 读取它。
            // 失败时记录诊断日志但不阻断启动 —— ACP 是可选功能。
            let acp_service: Option<std::sync::Arc<acp::AcpClientService>> =
                match acp::AcpClientService::new(app.handle().clone()) {
                    Ok(service) => {
                        let arc = std::sync::Arc::new(service);
                        app.manage(std::sync::Arc::clone(&arc));
                        log::info!("[startup] ACP client service initialized");
                        Some(arc)
                    }
                    Err(error) => {
                        log::warn!("[startup] ACP client service init failed: {}", error);
                        None
                    }
                };

            // 挂载 runtime-registry 全局 state（探测 opencode/claude/codex/kimi/trae
            // 并自动注册为子 agent；用户也可添加自定义 agent API）。
            // ACP 是可选功能 —— 即使 ACP 初始化失败，CliRun/CustomApi 适配器仍可用。
            let runtime_registry = std::sync::Arc::new(
                crate::runtime_registry::RuntimeRegistry::new(acp_service),
            );
            app.manage(std::sync::Arc::clone(&runtime_registry));

            // 启动流程：设置持久化目录 → 加载用户自定义 agent → 自动探测本机 CLI，
            // 让内置 runtime 立即作为可用子 agent 出现（复刻 Multica 模型）。
            {
                let data_dir = app.path().app_data_dir().ok();
                tauri::async_runtime::spawn(async move {
                    if let Some(dir) = data_dir {
                        runtime_registry.set_data_dir(dir.clone()).await;
                        runtime_registry.load_custom_agents().await;
                        runtime_registry.load_upstream_runtimes().await;
                        // Seed DSH upstreams from the active profile (profile-backed
                        // single source of truth for DSH config).
                        if let Ok(store) = crate::profile::ProfileStore::load(&dir) {
                            runtime_registry.sync_dsh_upstreams(&store.dsh_upstreams()).await;
                        }
                    }
                    runtime_registry.scan().await;
                    log::info!("[startup] runtime-registry scan complete");
                });
            }

            // 初始化 IM 渠道：从配置文件加载已保存的渠道，注册到 ChannelRegistry 并加入白名单
            let app_handle = app.handle().clone();
            tauri::async_runtime::spawn(async move {
                commands::im_config::init_im_channels(&app_handle, channel_registry_for_setup, im_bridge_for_setup, adapter_pool_for_setup).await;
            });

            // 注册托盘菜单。失败不阻断启动（主窗口仍可用），但 emit 事件
            // 让前端显示 banner —— macOS 14+ Sequoia 收紧了菜单栏权限，
            // 托盘初始化失败比以前更常见，用户需要明确感知。
            if let Err(error) = tray::setup_tray(app.handle()) {
                log::warn!("[startup] tray setup failed: {}", error);
                let _ = app.emit("tray://init-failed", serde_json::json!({ "error": error }));
                commands::diagnostics::record_diagnostic(
                    app.handle(),
                    "warn",
                    "tray",
                    format!("系统托盘初始化失败: {}", error),
                );
            }

            // 初始化后台录制模块
            recording::init_recording(app.handle());

            // 把主窗口的"用户点 X"从 destroy 改成 hide。
            // 这样主窗口关掉后,浮窗的 Tauri webview 不会被一起
            // 带走,实现了"主窗口关闭后悬浮窗单独存在"。
            if let Err(error) =
                commands::floating_window::fw_install_main_close_intercept(app.handle().clone())
            {
                log::warn!(
                    "[startup] Failed to install main close intercept: {}",
                    error
                );
            }

            log::info!(
                "[startup] tupAI {} on {} {}",
                env!("CARGO_PKG_VERSION"),
                std::env::consts::OS,
                std::env::consts::ARCH
            );

            // 自动拉起 Hermes gateway（跨平台）。失败时只记录 log::error，
            // 不阻塞 UI 启动 —— 前端的连接检测会再次重试，用户可点
            // "重拉起" 按钮手动恢复。
            //
            // `ensure_hermes_gateway_running` 是同步函数（内部用
            // `std::thread::sleep` 轮询端口），所以走 `spawn_blocking`
            // 而非 `spawn`。ensure_embedded_server_running 内部已改用
            // tauri::async_runtime::spawn（全局 runtime），可在
            // spawn_blocking 线程中正常启动 embedded server。
            tauri::async_runtime::spawn_blocking(|| {
                match commands::ensure_hermes_gateway_running() {
                    Ok(true) => log::info!(
                        "[startup] Hermes gateway 已就绪 (127.0.0.1:8642)"
                    ),
                    Ok(false) => log::warn!(
                        "[startup] Hermes gateway 拉起未成功，前端会继续重试"
                    ),
                    Err(error) => log::error!(
                        "[startup] Hermes gateway 拉起失败: {}",
                        error
                    ),
                }
            });

            // Dev-mode 自动错误监控。后台扫描 log 文件并通过
            // `tupai-dev-error-detected` 事件推送到前端。仅在
            // debug 构建里启动 —— release 构建里用户用的就是稳定
            // 路径了，再开一个后台线程意义不大。
            //
            // 启动前先做一次 cold-start 分析，让前端挂载后立即有
            // 错误上下文可以显示（不用等下一次 5s 轮询）。
            if cfg!(debug_assertions) {
                let app_handle = app.handle().clone();
                if let Err(error) =
                    commands::start_dev_log_watcher(app_handle.clone())
                {
                    log::warn!(
                        "[startup] Failed to start dev log watcher: {}",
                        error
                    );
                }
                // 初次扫描（非阻塞）—— 在 watch 线程外做一次，watcher
                // 的下一次 sleep 之后才会做正式扫描。
                tauri::async_runtime::spawn_blocking(move || {
                    match commands::analyze_log_for_errors(
                        app_handle.clone(),
                        Some(256 * 1024),
                    ) {
                        Ok(initial) if !initial.is_empty() => {
                            log::warn!(
                                "[startup] dev log cold-scan found {} error signature(s)",
                                initial.len()
                            );
                            let payload = serde_json::json!({
                                "errors": initial,
                                "all_errors": initial,
                            });
                            let _ = app_handle.emit(
                                "tupai-dev-error-detected",
                                payload,
                            );
                        }
                        Ok(_) => {
                            log::info!(
                                "[startup] dev log cold-scan: no errors detected"
                            );
                        }
                        Err(error) => {
                            log::warn!(
                                "[startup] dev log cold-scan failed: {}",
                                error
                            );
                        }
                    }
                });
            }

            // 凌晨 2 点自动归集当日 heal 记录，
            // 触发 `pc_automation` 路由器重写 skill.md，复用现有的
            // `commands::create_cron_job` 路径写进 Hermes dashboard
            // 的 cron 表（避免拉入新的 cron crate）。如果 dashboard
            // 还没起、或同名 cron 已存在，调用会返回 Err，我们
            // 只 log warn，不阻塞 UI 启动。
            //
            // `0 2 * * *` = 每天凌晨 2:00.
            tauri::async_runtime::spawn(async move {
                use commands::legacy::CreateCronJobInput;
                let input = CreateCronJobInput {
                    prompt: "tupai internal: 归集当日 heal 记录，更新 skill.md"
                        .to_string(),
                    schedule: "0 2 * * *".to_string(),
                    name: Some("tupai_daily_skill_evolution".to_string()),
                    deliver: Some("local".to_string()),
                };
                match commands::create_cron_job(input).await {
                    Ok(job) => log::info!(
                        "[startup] 注册凌晨 2 点技能归集 cron 成功 (id={})",
                        job.id
                    ),
                    Err(e) => log::warn!(
                        "[startup] 凌晨 2 点 cron 注册失败（可重入）: {}",
                        e
                    ),
                }
            });

            // Hermes 自动记忆升级 V2 — dailyReflection 后台定时任务。
            //
            // 落地 SELF-EVOLUTION 设计文档中的
            // dailyReflection 钩子：每 24h 扫描一次 memories 表，对相似
            // 记忆做 Jaccard 去重（合并 / 版本升级），保持记忆库精简。
            //
            // 设计选择：
            //   * 不调 LLM —— 后台任务无法可靠拿到 LLMServiceConfig
            //     (dashboard 可能没起 / config 还没填)，LLM 反思由前端
            //     反思面板通过 memory_reflect 命令手动触发；
            //   * 首次延迟 60s —— 让 startup 期的 db init / migration
            //     先完成，避免与 open_app_db 抢锁；
            //   * 24h 间隔 —— 桌面应用不一定 24/7 运行，每次启动后 60s
            //     跑一次相当于"每日反思"，24h 间隔防止长开用户重复跑；
            //   * 失败不退出 —— 单次 dedupe 失败只 log warn，下一轮
            //     继续尝试，避免临时 db 锁导致任务永久停止。
            {
                let app_handle = app.handle().clone();
                tauri::async_runtime::spawn(async move {
                    // 首次延迟 60s，让 startup 完成所有 db init
                    tokio::time::sleep(std::time::Duration::from_secs(60)).await;
                    loop {
                        // 在 spawn_blocking 里跑同步 sqlite 操作，避免
                        // 阻塞 tauri async runtime（dedupe_memories 会
                        // 全表扫描 + N×N Jaccard，记忆多时可能几百 ms）。
                        let handle = app_handle.clone();
                        let result = tauri::async_runtime::spawn_blocking(move || {
                            let conn = match commands::legacy::open_app_db(&handle) {
                                Ok(c) => c,
                                Err(e) => {
                                    log::warn!(
                                        "[memory_evolution] dailyReflection: open_app_db failed: {}",
                                        e
                                    );
                                    return;
                                }
                            };
                            match hermes::memory_evolution::dedupe_memories(&conn) {
                                Ok(r) => log::info!(
                                    "[memory_evolution] dailyReflection: scanned={}, merged={}, upgraded={}, skipped={}",
                                    r.scanned, r.merged, r.upgraded, r.skipped
                                ),
                                Err(e) => log::warn!(
                                    "[memory_evolution] dailyReflection: dedupe_memories failed: {}",
                                    e
                                ),
                            }
                        })
                        .await;
                        if result.is_err() {
                            log::warn!("[memory_evolution] dailyReflection task panicked");
                        }
                        // 24h 间隔
                        tokio::time::sleep(std::time::Duration::from_secs(
                            24 * 60 * 60,
                        ))
                        .await;
                    }
                });
            }

            // 启动降级指示器: 检查 StartupDiagnostics 是否有 error 级条目。
            // 若有, emit `startup://degraded` 让前端首屏显示非阻塞 banner
            // (用户点击后跳转诊断面板查看详情)。warn 级条目不触发 banner
            // (太多正常的非阻断警告会扰民, 仍可通过诊断面板查)。
            {
                let degraded_entries: Vec<_> = app
                    .try_state::<commands::diagnostics::StartupDiagnostics>()
                    .map(|s| {
                        s.list()
                            .into_iter()
                            .filter(|e| e.level == "error")
                            .collect()
                    })
                    .unwrap_or_default();
                if !degraded_entries.is_empty() {
                    log::warn!(
                        "[startup] {} error-level diagnostic(s) recorded — emitting startup://degraded",
                        degraded_entries.len()
                    );
                    let _ = app.emit(
                        "startup://degraded",
                        serde_json::json!({
                            "count": degraded_entries.len(),
                            "modules": degraded_entries.iter().map(|e| e.module.clone()).collect::<Vec<_>>(),
                        }),
                    );
                } else {
                    let _ = app.emit("startup://ready", ());
                }
            }

            crate::logging::write_startup_marker("29-setup-returns-ok");
            Ok(())
        });
    startup_stage!("30-after-setup-closure");
    let app = _builder
        .build(tauri::generate_context!());
    let app = match app {
        Ok(app) => {
            crate::logging::write_startup_marker("41-build-ok");
            app
        }
        Err(e) => {
            let msg = format!(
                "[startup-stage] tauri::Builder::build failed: {e:?}"
            );
            log::error!("{msg}");
            crate::logging::write_startup_marker(&format!("40-build-err: {e:?}"));
            // Show a native message box so the user sees the error instead
            // of a silent crash (windows_subsystem="windows" hides stderr).
            #[cfg(target_os = "windows")]
            {
                // 使用 native MessageBox 让用户看到错误
                // （windows_subsystem="windows" 隐藏了 stderr/console）
                use windows::Win32::UI::WindowsAndMessaging::{
                    MessageBoxW, MB_OK, MB_ICONERROR,
                };
                let title = windows::core::HSTRING::from("tupAI 启动失败");
                let body = windows::core::HSTRING::from(format!(
                    "tupAI 启动时遇到错误，无法创建窗口。\n\
                     详细信息已记录到日志文件。\n\n\
                     错误: {}",
                    e
                ));
                let _ = unsafe {
                    MessageBoxW(
                        None,
                        &body,
                        &title,
                        MB_OK | MB_ICONERROR,
                    )
                };
            }
            #[cfg(not(target_os = "windows"))]
            {
                // macOS: `eprintln!` is invisible to GUI users (no Terminal
                // attached when launched from Finder/Dock). Use osascript to
                // pop a native NSAlert via AppleScript so the user actually
                // sees the failure instead of a silent exit. Best-effort —
                // if osascript itself fails (rare), we still exit with code 1.
                //
                // Linux: stderr is usually visible when launched from a
                // terminal; for GUI launches there's no portable dialog
                // tool (zenity / kdialog are optional), so eprintln is the
                // best we can do without pulling in another dep.
                #[cfg(target_os = "macos")]
                {
                    // Escape double quotes for AppleScript string literal.
                    let escaped = format!("{}", e).replace('"', "\\\"");
                    let script = format!(
                        "display dialog \"tupAI 启动失败\\n\\n遇到错误，无法创建窗口。\\n详细信息已记录到日志文件。\\n\\n错误: {}\" buttons {{\"OK\"}} with icon stop",
                        escaped
                    );
                    let _ = std::process::Command::new("osascript")
                        .args(["-e", &script])
                        .status();
                }
                eprintln!("FATAL: {}", msg);
            }
            std::process::exit(1);
        }
    };
    crate::logging::write_startup_marker("50-pre-run");
    app.run(move |_app_handle, event| {
        // 应用退出时确保录制缓冲数据落盘并关闭 runtime，
        // 否则 spawn 的 flush 任务被 abort，缓冲数据丢失。
        if matches!(
            event,
            tauri::RunEvent::ExitRequested { .. } | tauri::RunEvent::Exit
        ) {
            crate::logging::write_startup_marker("60-run-event-exit");
            recording::shutdown_recording();
        }
    });
    crate::logging::write_startup_marker("99-run-returned");
}




