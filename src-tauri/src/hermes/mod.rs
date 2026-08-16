// Copyright (c) 2026 MeeJoy
//

use tauri::Manager;

pub mod types;
pub mod event_bus;
pub mod lru_map;
pub mod test_harness;
pub mod utils;
pub mod yaml_path;
pub mod working_directory;
pub mod shared_types;

pub mod agent;
pub mod multi_agent;
pub mod agent_events;
pub mod agent_registry;
pub mod agent_tools;
pub mod agent_delegation;

pub mod cron;
pub mod cron_local;
pub mod kanban;
pub mod llm_service;
pub mod memory_ops;
pub mod persistence;     // HermesDb: unified sqlite layer for hermes modules
pub mod persona;
pub mod profile;
pub mod reflection;
pub mod evolution;
pub mod evolution_stats;
pub mod evolution_signal; // Phase 1: 统一进化信号契约 (SkillKind / EvolutionSignal)
pub mod evolution_gate; // Phase 1: 统一评估门控 (SkillEvaluator + SandboxRunner + LLM 改写)
pub mod evolution_orchestrator; // Phase 1: 采集→分析→门控→draft 编排器
pub mod session_analyzer; // Phase 1: 会话内容 LLM 分析 (hermes_llm_service) + 降级启发式
pub mod memory_evolution; // V2 自动记忆升级：write_outcome + dedupe + lineage
#[cfg(feature = "mesh")]
pub mod mesh; // Phase 3: 安全设计 P2P 组网 + SkillSync 技能升级同步
pub mod dilemma_detector;
pub mod lifecycle;
pub mod safe_stream;
pub mod sse_client;
pub mod ssh_tunnel;
pub mod skill_discovery;
pub mod skill_parser;
pub mod skill_manifest;
pub mod skill_evaluator;   // ServerEval: 5-dim evaluator
pub mod sandbox_runner;    // ServerEval: static dry-run sandbox
pub mod dedup_index;       // ServerEval: Jaccard dedup index
pub mod tool_registry;
pub mod tool_schemas;
pub mod agent_loop;
pub mod intent_router;
pub mod hook;
pub mod parallel_safety;
pub mod permission_checker;
pub mod bash_validator;
pub mod context_estimator;
pub mod context_pruner;
pub mod session_search;
pub mod trajectory_store;
pub mod tuptup_client;
pub mod theme_catalog;
pub mod model_catalog;
pub mod installer;
pub mod i18n;
pub mod backup;
pub mod logger;
pub mod report_sender;
pub mod cli_resolver;

pub mod im;

// TransportLayer
// transport / ws_client / auth live alongside the other hermes
// submodules; main thread does NOT touch this list. Adding the three
// lines is the only way the new submodules become visible to the
// crate root.
pub mod transport;
pub mod ws_client;
pub mod auth;
pub mod embedded_server;   // v5: in-process axum-based gateway + dashboard

// Re-exports for convenience.
#[allow(unused_imports)] // types module is also accessed directly via crate::hermes::types
pub use types::*;
#[allow(unused_imports)] // EventHandler/EventPayload/EventBus are part of public event_bus API; consumed by external integrations
pub use event_bus::{EventBus, EventHandler, EventPayload};
pub use agent::HermesAgent;
pub use multi_agent::MultiAgent;
pub use cron::CronScheduler;
pub use kanban::KanbanBoard;
pub use memory_ops::MemoryOps;
pub use persona::PersonaRegistry;
pub use profile::ProfileStore;

/// Aggregator struct that the main thread can hand to `app.manage(...)`.
/// Every Tauri command that needs shared state should take a
/// `tauri::State<'_, HermesAppState>` argument.
///
/// # Concurrency note (v5)
///
/// The fields below that hold `std::sync::Mutex<T>` (cron, permission,
/// tools, hooks, parallel_safety, ssh_tunnels, evolution) are
/// **deliberately not `tokio::sync::Mutex`**. This is an intentional
/// v5 design choice to keep the type `Clone` (so it can move into
/// `app.manage(...)` cheaply) and to avoid paying the
/// `Send`-borrowing cost of `tokio::sync::Mutex` on every command
/// entry point. **Callers MUST therefore follow this rule:**
///
/// > Acquire the lock, do the synchronous work, drop the guard —
/// > never hold a `std::sync::MutexGuard` across `.await`.
///
/// Holding a guard across `.await` would let another task deadlock
/// when the same task's executor thread is blocked on the awaited
/// future, or — on multi-threaded runtimes — cause
/// `Send`-related compile errors. The five tauri commands that
/// touch these mutexes today (hermes_cron_list / add / remove,
/// permission checks, tool registry) all follow the rule. Any new
/// command that needs async I/O while holding one of these locks
/// should refactor the lock to `tokio::sync::Mutex` first.
#[derive(Clone)]
pub struct HermesAppState {
    pub bus: std::sync::Arc<EventBus>,
    pub agent: std::sync::Arc<HermesAgent>,
    pub multi_agent: std::sync::Arc<MultiAgent>,
    pub agent_registry: std::sync::Arc<agent_registry::AgentRegistry>,
    pub cron: std::sync::Arc<std::sync::Mutex<CronScheduler>>,
    pub kanban: std::sync::Arc<tokio::sync::RwLock<KanbanBoard>>,
    pub memory_ops: std::sync::Arc<MemoryOps>,
    pub persona: std::sync::Arc<PersonaRegistry>,
    pub profile: std::sync::Arc<ProfileStore>,
    pub permission: std::sync::Arc<std::sync::Mutex<permission_checker::PermissionChecker>>,
    pub trajectory: std::sync::Arc<trajectory_store::TrajectoryStore>,
    pub tools: std::sync::Arc<std::sync::Mutex<tool_registry::ToolRegistry2>>,
    pub hooks: std::sync::Arc<hook::HookRegistry>,
    pub parallel_safety: std::sync::Arc<std::sync::Mutex<parallel_safety::ParallelSafetyGraph>>,
    pub ssh_tunnels: std::sync::Arc<std::sync::Mutex<ssh_tunnel::SshTunnelManager>>,
    pub evolution: std::sync::Arc<std::sync::Mutex<evolution::EvolutionTracker>>,
    /// Unified sqlite handle. `None` when persistence is disabled
    /// (unit tests, headless library use). When `Some`, every
    /// sub-module that opted in (`MemoryOps`, `TrajectoryStore`,
    /// `evolution_stats`) reads/writes through this handle.
    pub db: Option<std::sync::Arc<persistence::HermesDb>>,
    /// Device auth token (set from frontend via `hermes_set_device_token`).
    /// Used by Hermes tool handlers (e.g. `mcp_call`) for Bearer auth.
    pub device_token: std::sync::Arc<std::sync::RwLock<Option<String>>>,
}

impl HermesAppState {
    pub fn new() -> Self {
        let agent = std::sync::Arc::new(HermesAgent::new(types::HermesConfig::default()));
        init_report_sender_from_env();
        Self {
            bus: std::sync::Arc::new(EventBus::new()),
            multi_agent: std::sync::Arc::new(MultiAgent::new()),
            agent_registry: std::sync::Arc::new(agent_registry::AgentRegistry::new()),
            cron: std::sync::Arc::new(std::sync::Mutex::new(CronScheduler::new())),
            kanban: std::sync::Arc::new(tokio::sync::RwLock::new(KanbanBoard::default())),
            memory_ops: MemoryOps::shared(),
            persona: std::sync::Arc::new(PersonaRegistry::new()),
            profile: ProfileStore::shared(),
            permission: std::sync::Arc::new(std::sync::Mutex::new(permission_checker::PermissionChecker::new())),
            trajectory: trajectory_store::TrajectoryStore::shared(),
            tools: std::sync::Arc::new(std::sync::Mutex::new(tool_registry::ToolRegistry2::new())),
            hooks: std::sync::Arc::new(hook::HookRegistry::new()),
            parallel_safety: std::sync::Arc::new(std::sync::Mutex::new(parallel_safety::ParallelSafetyGraph::new())),
            ssh_tunnels: std::sync::Arc::new(std::sync::Mutex::new(ssh_tunnel::SshTunnelManager::new())),
            evolution: std::sync::Arc::new(std::sync::Mutex::new(evolution::EvolutionTracker::new(64))),
            agent,
            db: None,
            device_token: std::sync::Arc::new(std::sync::RwLock::new(None)),
        }
    }

    /// Production constructor: opens `tupai.db` from `app_data_dir`,
    /// derives a machine-bound `EncryptedStorage` for profile/persona,
    /// and threads the handles through every sub-module that opted
    /// into persistence. Falls back to the no-persistence `new()`
    /// path if either the db or the encrypted storage fails to
    /// initialise — the app still boots, just without persistence.
    pub fn with_persistence(app: &tauri::AppHandle) -> Self {
        let app_data_dir = match app.path().app_data_dir() {
            Ok(dir) => dir,
            Err(e) => {
                log::error!("[hermes] Failed to resolve app_data_dir, persistence disabled: {}", e);
                return Self::new();
            }
        };

        // Open the unified sqlite handle.
        let db = match persistence::HermesDb::open(&app_data_dir) {
            Ok(db) => std::sync::Arc::new(db),
            Err(e) => {
                log::error!("[hermes] HermesDb::open failed, persistence disabled: {}", e);
                // 显式标注 evolution_stats 降级: with_persistence 成功路径会调
                // evolution_stats::init_persistence(db) 从 sqlite hydrate 累计
                // 计数器, 降级到 new() 后 evolution_stats 的 static DB 保持
                // None, record_run 只更新内存态 (重启丢失)。前端 EvolutionPanel
                // 看到的累计扫描/发送/失败计数会归零, 需要可见的日志/诊断提示。
                log::warn!(
                    "[hermes] evolution_stats persistence disabled due to db open failure: {}",
                    e
                );
                // record_diagnostic 用 try_state, StartupDiagnostics 未注册时
                // 静默跳过, 不会 panic。
                crate::commands::diagnostics::record_diagnostic(
                    app,
                    "warn",
                    "hermes",
                    format!(
                        "HermesDb 打开失败, 持久化降级 (evolution_stats / memory_ops / trajectory 仅为内存态): {}",
                        e
                    ),
                );
                return Self::new();
            }
        };

        // Derive a machine-bound EncryptedStorage for profile/persona.
        // We reuse the same (constant-service-name + hardware-fingerprint)
        // pattern as `hermes::auth::TransportToken` — no user password
        // is required because profile/persona are non-secret user
        // preferences, not credentials.
        let enc_storage = {
            let fingerprint = crate::commands::hardware::compute_hardware_fingerprint();
            match crate::crypto::storage::EncryptedStorage::derive(
                HERMES_PROFILE_SERVICE_SALT,
                &fingerprint,
            ) {
                Ok(s) => Some(std::sync::Arc::new(s)),
                Err(e) => {
                    log::warn!("[hermes] EncryptedStorage derive failed, profile/persona persistence disabled: {}", e);
                    None
                }
            }
        };

        let profile_path = app_data_dir.join("hermes_profile.enc");
        let persona_path = app_data_dir.join("hermes_personas.enc");

        let agent = std::sync::Arc::new(HermesAgent::new(types::HermesConfig::default()));

        // Hydrate evolution_stats from sqlite before any record_run call.
        evolution_stats::init_persistence(db.clone());

        init_report_sender_from_env();
        Self {
            bus: std::sync::Arc::new(EventBus::new()),
            multi_agent: std::sync::Arc::new(MultiAgent::new()),
            agent_registry: std::sync::Arc::new(agent_registry::AgentRegistry::new()),
            cron: std::sync::Arc::new(std::sync::Mutex::new(CronScheduler::new())),
            kanban: std::sync::Arc::new(tokio::sync::RwLock::new(KanbanBoard::default())),
            memory_ops: MemoryOps::with_db(db.clone()),
            persona: PersonaRegistry::with_encrypted_storage(
                enc_storage.clone(),
                persona_path,
            ),
            profile: ProfileStore::with_encrypted_storage(enc_storage.clone(), profile_path),
            permission: std::sync::Arc::new(std::sync::Mutex::new(permission_checker::PermissionChecker::new())),
            trajectory: trajectory_store::TrajectoryStore::with_db(db.clone()),
            tools: std::sync::Arc::new(std::sync::Mutex::new(tool_registry::ToolRegistry2::new())),
            hooks: std::sync::Arc::new(hook::HookRegistry::new()),
            parallel_safety: std::sync::Arc::new(std::sync::Mutex::new(parallel_safety::ParallelSafetyGraph::new())),
            ssh_tunnels: std::sync::Arc::new(std::sync::Mutex::new(ssh_tunnel::SshTunnelManager::new())),
            evolution: std::sync::Arc::new(std::sync::Mutex::new(evolution::EvolutionTracker::new(64))),
            agent,
            db: Some(db),
            device_token: std::sync::Arc::new(std::sync::RwLock::new(None)),
        }
    }
}

/// Set the device auth token for Hermes tool handlers (e.g. `mcp_call`).
/// Called from the frontend after device registration / token renewal.
#[tauri::command]
pub fn hermes_set_device_token(
    app: tauri::AppHandle,
    token: Option<String>,
) -> Result<(), String> {
    use tauri::Manager;
    let state = app.state::<HermesAppState>();
    let cleaned = token.filter(|s| !s.trim().is_empty());
    *state.device_token.write().map_err(|e| e.to_string())? = cleaned;
    log::info!("[hermes] device_token updated: is_set={}", state.device_token.read().map(|t| t.is_some()).unwrap_or(false));
    Ok(())
}

/// Service-name used as the Argon2id "password" when deriving the
/// machine-bound AES key for `hermes_profile.enc` /
/// `hermes_personas.enc`. Bumping this string invalidates every
/// existing encrypted file (machine-wide rollout).
const HERMES_PROFILE_SERVICE_SALT: &str = "tupai-hermes-profile-v1";

/// 初始化全局 `report_sender::REPORT_SENDER`。从
/// `TUPAI_CLOUD_BASE_URL`（缺省 `https://ai.tuptup.top`）派生
/// 上报端点，与 `embedded_server::tupai_cloud_base_url()` 同源。
///
/// TODO: 上报端点 `/api/v1/report` 是按云端契约约定写的占位，
/// 真正的端点确定后请替换。`init_report_sender` 幂等，
/// `new()` / `with_persistence()` 都调用一次也只会 set 一次。
fn init_report_sender_from_env() {
    let base = std::env::var("TUPAI_CLOUD_BASE_URL")
        .ok()
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "https://ai.tuptup.top".to_string());
    let cfg = report_sender::SenderConfig {
        endpoint: format!("{}/api/v1/report", base.trim_end_matches('/')),
        api_key: None,
    };
    // 忽略返回值：已初始化时静默跳过（构造函数可能被多次调用）。
    let _ = report_sender::init_report_sender(cfg);
}

impl Default for HermesAppState {
    fn default() -> Self { Self::new() }
}
