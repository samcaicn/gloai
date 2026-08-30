// AiMarketing — Tauri 2 desktop client with official DSH WebUI.
//
// Architecture:
//   - Spawns `npx @deepseek-ai/dsh web` as a child process
//   - Waits for the backend to be ready
//   - Navigates the WebView to the backend URL (serves the official WebUI)
//   - Exposes Tauri commands for: skills, autoskill, memory, evolution, compiler, window control

use std::process::Child;
use std::sync::{Arc, Mutex};
#[cfg(windows)]
use std::os::windows::process::CommandExt;
use std::path::PathBuf;
use tauri::{Manager, State};

use dsh_core::autoskill::AutoSkillEngine;
use dsh_core::evolution::{EvolutionReport, EvolutionTracker, RunRecord};
use dsh_core::memory::{MemoryOps, MemoryStats};
use dsh_core::skill::compiler::{compile, decompile, validate};
use dsh_core::skill::embedded::get_embedded_skills;
use dsh_core::skill::eval::{SkillEvaluation, SkillEvalEngine};
use dsh_core::skill::executor::{ExecutionResult, SkillExecutor};
use dsh_core::skill::manifest::SkillManifest;
use dsh_core::skill::registry::{InboxItem, SkillRegistry};
use dsh_core::storage::{MemoryInput, MemoryQuery, Storage};

/// Application state shared across all Tauri commands.
pub struct AppState {
    storage: Arc<Storage>,
    registry: Arc<SkillRegistry>,
    memory: Arc<MemoryOps>,
    evolution: Mutex<EvolutionTracker>,
    autoskill: Arc<AutoSkillEngine>,
    /// Handle to the DSH backend child process
    dsh_backend: Mutex<Option<Child>>,
    /// Port the DSH backend is running on
    dsh_port: Mutex<Option<u16>>,
}

impl AppState {
    pub fn new(storage: Arc<Storage>) -> Self {
        let eval = Arc::new(SkillEvalEngine::new());
        Self {
            storage: storage.clone(),
            registry: Arc::new(SkillRegistry::new()),
            memory: Arc::new(MemoryOps::with_storage(storage.clone())),
            evolution: Mutex::new(EvolutionTracker::new(50)),
            autoskill: Arc::new(AutoSkillEngine::new(storage, eval)),
            dsh_backend: Mutex::new(None),
            dsh_port: Mutex::new(None),
        }
    }

    /// Resolve DSH bin.js path via multiple candidates.
    /// Returns (node_exe, dsh_bin) or None.
    fn find_dsh() -> Option<(PathBuf, PathBuf)> {
        let mut candidates: Vec<PathBuf> = Vec::new();

        // 1) NSIS install directory / portable layout
        if let Ok(exe) = std::env::current_exe() {
            if let Some(dir) = exe.parent() {
                candidates.push(dir.join("_up_/dsh/bin.js"));
                candidates.push(dir.join("dsh/bin.js"));
                candidates.push(dir.join("../.dsh-portable/node_modules/@deepseek-ai/dsh/lib/bin.js"));
            }
        }

        // 2) CatPaw runtime (dev fallback)
        if let Some(home) = std::env::var_os("USERPROFILE").or_else(|| std::env::var_os("HOME")) {
            let base = PathBuf::from(home).join(".meituan-catpaw/runtimes/node/versions");
            if let Ok(versions) = std::fs::read_dir(&base) {
                for entry in versions.flatten() {
                    let p = entry.path().join("node_modules/@deepseek-ai/dsh/lib/bin.js");
                    candidates.push(p);
                    // Also the nested node_modules location
                    candidates.push(entry.path().join("node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js"));
                }
            }
        }

        // 3) Global npm prefix
        for prefix in &[
            PathBuf::from("C:/Users/Administrator/AppData/Roaming/npm"),
            PathBuf::from("C:/Program Files/nodejs"),
            PathBuf::from("C:/Program Files (x86)/nodejs"),
        ] {
            candidates.push(prefix.join("node_modules/@deepseek-ai/dsh/lib/bin.js"));
        }

        for dsh_bin in &candidates {
            if dsh_bin.exists() {
                // node.exe sits in the parent of node_modules
                if let Some(node_modules) = dsh_bin.parent() {
                    if let Some(dsh_scope) = node_modules.parent() {
                        if let Some(dsh_root) = dsh_scope.parent() {
                            let node_exe = if let Some(nd) = dsh_root.parent() {
                                let n = nd.join("node.exe");
                                if n.exists() { Some(n) } else { Self::find_node_in_path() }
                            } else {
                                Self::find_node_in_path()
                            };
                            if let Some(node) = node_exe {
                                return Some((node, dsh_bin.clone()));
                            }
                        }
                    }
                }
            }
        }
        None
    }

    /// Try to find node.exe in PATH or common locations.
    fn find_node_in_path() -> Option<PathBuf> {
        // PATH search
        if let Ok(path_var) = std::env::var("PATH") {
            for dir in path_var.split(';') {
                let p = PathBuf::from(dir).join("node.exe");
                if p.exists() { return Some(p); }
            }
        }
        // Common locations
        for p in &[
            PathBuf::from("C:/Program Files/nodejs/node.exe"),
            PathBuf::from("C:/Program Files (x86)/nodejs/node.exe"),
            PathBuf::from("C:/Users/Administrator/AppData/Roaming/npm/node.exe"),
        ] {
            if p.exists() { return Some(p.clone()); }
        }
        None
    }

    /// Spawn the DSH backend process on the FIXED port 3080.
    /// If port 3080 is already in use, assume a user-started instance is running and reuse it.
    /// Returns the port (always 3080).
    pub fn start_dsh_backend(&self) -> Result<u16, String> {
        let mut backend = self.dsh_backend.lock().map_err(|e| e.to_string())?;
        let fixed_port: u16 = 3080;

        // If we already spawned a backend, return its port
        if backend.is_some() {
            if let Some(port) = self.dsh_port.lock().ok().and_then(|p| *p) {
                return Ok(port);
            }
            return Ok(fixed_port);
        }

        // Try to bind port 3080. If it's already in use, assume user-started instance
        match std::net::TcpListener::bind(format!("127.0.0.1:{}", fixed_port)) {
            Ok(l) => {
                drop(l); // release the port so dsh can bind it
            }
            Err(_) => {
                // Port already in use — assume user manually started dsh web on 3080
                log::info!("[dsh] port {} already in use, reusing existing instance", fixed_port);
                if let Ok(mut p) = self.dsh_port.lock() {
                    *p = Some(fixed_port);
                }
                return Ok(fixed_port);
            }
        }

        // Resolve node + dsh bin.js
        let (node_exe, dsh_bin) = Self::find_dsh().ok_or("Cannot find node + @deepseek-ai/dsh. Please install: npm i -g @deepseek-ai/dsh")?;

        // Ensure aimarketing profile exists
        self.ensure_aimarketing_profile()?;

        // Log file for DSH backend debugging
        let _log_file = std::env::temp_dir().join(format!("dsh-backend-{}.log", fixed_port));

        #[cfg(windows)]
        let child = {
            let port_str = fixed_port.to_string();
            let node_path = node_exe.parent().map(|p| p.to_string_lossy().to_string()).unwrap_or_default();
            std::process::Command::new(&node_exe)
                .arg(&dsh_bin)
                .arg("--profile").arg("aimarketing")
                .arg("--host").arg("127.0.0.1")
                .arg("--port").arg(&port_str)
                .arg("--no-open")
                .env("PATH", &node_path)
                .stdin(std::process::Stdio::null())
                .stdout(std::process::Stdio::null())
                .stderr(std::process::Stdio::null())
                .creation_flags(0x00000008 | 0x00000200) // CREATE_NO_WINDOW | DETACHED_PROCESS
                .spawn()
                .map_err(|e| format!("Failed to start DSH backend: {}", e))?
        };

        #[cfg(not(windows))]
        let child = {
            let port_str = fixed_port.to_string();
            std::process::Command::new(&node_exe)
                .arg(&dsh_bin)
                .arg("--profile").arg("aimarketing")
                .arg("--host").arg("127.0.0.1")
                .arg("--port").arg(&port_str)
                .arg("--no-open")
                .stdin(std::process::Stdio::null())
                .stdout(std::process::Stdio::piped())
                .stderr(std::process::Stdio::piped())
                .spawn()
                .map_err(|e| format!("Failed to start DSH backend: {}", e))?
        };

        log::info!("[dsh] backend starting on 127.0.0.1:{} via {:?}", fixed_port, node_exe);

        *backend = Some(child);
        if let Ok(mut p) = self.dsh_port.lock() {
            *p = Some(fixed_port);
        }
        log::info!("[dsh] backend started on 127.0.0.1:{}", fixed_port);
        Ok(fixed_port)
    }

    /// Ensure the aimarketing profile exists.
    /// Creates a minimal profile that uses the official DSH WebUI without extra plugins.
    fn ensure_aimarketing_profile(&self) -> Result<(), String> {
        let home = std::env::var("USERPROFILE").or_else(|_| std::env::var("HOME")).map_err(|_| "no home dir")?;
        let profile_dir = PathBuf::from(&home).join(".dsh/profiles/aimarketing");
        if profile_dir.exists() {
            return Ok(()); // already exists
        }
        std::fs::create_dir_all(&profile_dir).map_err(|e| format!("create profile dir: {}", e))?;
        // Minimal profile: use dsh-base + dsh-web-app (official WebUI) without task-board
        let pkg = r#"{
  "name": "dsh-profile-aimarketing",
  "private": true,
  "dsh": {
    "profile": {
      "bundles": [
        "@deepseek-ai/dsh-base",
        "@deepseek-ai/dsh-web-app"
      ]
    }
  }
}"#;
        std::fs::write(profile_dir.join("package.json"), pkg)
            .map_err(|e| format!("write package.json: {}", e))?;
        log::info!("[dsh] created aimarketing profile at {:?}", profile_dir);
        Ok(())
    }

    /// Wait for the backend to be ready by polling the TCP port.
    pub fn wait_for_backend(&self, port: u16, timeout_secs: u64) -> bool {
        let start = std::time::Instant::now();
        let timeout = std::time::Duration::from_secs(timeout_secs);
        while start.elapsed() < timeout {
            if std::net::TcpStream::connect(format!("127.0.0.1:{}", port)).is_ok() {
                return true;
            }
            std::thread::sleep(std::time::Duration::from_millis(500));
        }
        false
    }

    /// Get the DSH backend port (if running).
    pub fn dsh_port(&self) -> Option<u16> {
        self.dsh_port.lock().ok().and_then(|p| *p)
    }
}

// ============================================================
// DSH backend commands
// ============================================================

#[tauri::command]
fn get_dsh_url(state: State<'_, AppState>) -> Result<String, String> {
    match state.dsh_port() {
        Some(port) => Ok(format!("http://127.0.0.1:{}", port)),
        None => Err("DSH backend not running".to_string()),
    }
}

// ============================================================
// Skill commands
// ============================================================

#[tauri::command]
fn list_embedded_skills() -> Vec<serde_json::Value> {
    get_embedded_skills()
        .into_iter()
        .map(|s| {
            serde_json::json!({
                "id": s.id,
                "name": s.name,
                "version": s.version,
                "description": s.description,
                "category": s.category,
                "tags": s.tags,
            })
        })
        .collect()
}

#[tauri::command]
fn get_embedded_skill_yaml(id: String) -> Result<String, String> {
    get_embedded_skills()
        .into_iter()
        .find(|s| s.id == id)
        .map(|s| s.yaml)
        .ok_or_else(|| format!("skill '{}' not found", id))
}

#[tauri::command]
async fn execute_skill(
    state: State<'_, AppState>,
    skill_id: String,
    scene: Option<String>,
) -> Result<ExecutionResult, String> {
    let skill_yaml = get_embedded_skills()
        .into_iter()
        .find(|s| s.id == skill_id)
        .map(|s| s.yaml)
        .ok_or_else(|| format!("skill '{}' not found", skill_id))?;

    let manifest = SkillManifest::from_yaml(&skill_yaml).map_err(|e| e.to_string())?;
    manifest.validate().map_err(|e| e.to_string())?;

    let executor = SkillExecutor::new(state.storage.clone()).with_http();
    let scene = scene.unwrap_or_else(|| "default".to_string());
    executor.execute(&scene, &manifest).await.map_err(|e| e.to_string())
}

#[tauri::command]
fn eval_skill(content: String, context: Option<String>) -> SkillEvaluation {
    let engine = SkillEvalEngine::new();
    engine.evaluate(&content, context.as_deref().unwrap_or(""))
}

#[tauri::command]
fn list_registered_skills(state: State<'_, AppState>) -> Vec<serde_json::Value> {
    state
        .registry
        .list_skills()
        .into_iter()
        .map(|(id, ver, name)| {
            serde_json::json!({
                "id": id,
                "version": ver,
                "name": name,
            })
        })
        .collect()
}

#[tauri::command]
fn register_skill(
    state: State<'_, AppState>,
    skill_id: String,
    name: String,
    yaml_content: String,
) -> Result<u64, String> {
    let manifest = SkillManifest::from_yaml(&yaml_content).map_err(|e| e.to_string())?;
    let mut manifest = manifest;
    if manifest.name.is_empty() {
        manifest.name = name;
    }
    state.registry.register_version(&skill_id, manifest, yaml_content);
    Ok(state.registry.get_running(&skill_id).map(|(v, _, _)| v).unwrap_or(1))
}

#[tauri::command]
fn list_skill_inbox(state: State<'_, AppState>) -> Vec<InboxItem> {
    state.registry.list_inbox()
}

#[tauri::command]
fn adopt_skill_proposal(
    state: State<'_, AppState>,
    proposal_id: String,
    skill_id: String,
    evaluation: SkillEvaluation,
    manifest: SkillManifest,
    content: String,
) -> serde_json::Value {
    let outcome = state
        .registry
        .adopt_proposal(&proposal_id, &skill_id, evaluation, manifest, content);
    serde_json::json!({
        "proposalId": outcome.proposal_id,
        "skillId": outcome.skill_id,
        "decision": outcome.decision,
        "score": outcome.score,
    })
}

#[tauri::command]
fn rollback_skill(state: State<'_, AppState>, skill_id: String) -> Result<u64, String> {
    state
        .registry
        .rollback(&skill_id)
        .ok_or_else(|| format!("no fallback version for skill '{}'", skill_id))
}

#[tauri::command]
fn skill_version_history(
    state: State<'_, AppState>,
    skill_id: String,
) -> Vec<serde_json::Value> {
    state
        .registry
        .get_history(&skill_id)
        .into_iter()
        .map(|(id, from, to, ts)| {
            serde_json::json!({
                "skillId": id,
                "fromVersion": from,
                "toVersion": to,
                "timestamp": ts,
            })
        })
        .collect()
}

// ============================================================
// Skill compiler commands
// ============================================================

#[tauri::command]
fn compile_skill_md(skill_md: String) -> Result<serde_json::Value, String> {
    let manifest = compile(&skill_md).map_err(|e| e.to_string())?;
    Ok(serde_json::json!({
        "id": manifest.id,
        "name": manifest.name,
        "version": manifest.version,
        "description": manifest.description,
        "category": manifest.category,
        "tags": manifest.tags,
        "steps": manifest.steps.len(),
    }))
}

#[tauri::command]
fn validate_skill_md(skill_md: String) -> Result<(), String> {
    validate(&skill_md).map_err(|e| e.to_string())
}

#[tauri::command]
fn decompile_skill(manifest: SkillManifest) -> Result<String, String> {
    decompile(&manifest).map_err(|e| e.to_string())
}

// ============================================================
// AutoSkill commands
// ============================================================

#[tauri::command]
async fn autoskill_scan_optimization(
    state: State<'_, AppState>,
    scene: String,
) -> Result<Vec<dsh_core::autoskill::OptimizationCandidate>, String> {
    state
        .autoskill
        .scan_for_optimization(&scene)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn autoskill_generate_draft(
    state: State<'_, AppState>,
    scene: String,
    skill_id: String,
) -> Result<dsh_core::autoskill::DraftResult, String> {
    state
        .autoskill
        .generate_draft(&scene, &skill_id)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn autoskill_scan_merge(
    state: State<'_, AppState>,
    scene: String,
) -> Result<Vec<dsh_core::autoskill::MergeCandidate>, String> {
    state
        .autoskill
        .scan_merge_candidates(&scene)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn autoskill_generate_merge_draft(
    state: State<'_, AppState>,
    scene: String,
    skill_ids: Vec<String>,
) -> Result<dsh_core::autoskill::DraftResult, String> {
    state
        .autoskill
        .generate_merge_draft(&scene, &skill_ids)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn autoskill_confirm_upgrade(
    state: State<'_, AppState>,
    draft_id: String,
) -> Result<(), String> {
    state
        .autoskill
        .confirm_upgrade(&draft_id)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn autoskill_rollback_if_degraded(
    state: State<'_, AppState>,
    draft_id: String,
    threshold: i32,
) -> Result<bool, String> {
    state
        .autoskill
        .rollback_if_degraded(&draft_id, threshold)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn autoskill_rollback_all(
    state: State<'_, AppState>,
    threshold: i32,
) -> Result<usize, String> {
    state
        .autoskill
        .rollback_all_degraded(threshold)
        .await
        .map_err(|e| e.to_string())
}

// ============================================================
// Memory commands
// ============================================================

#[tauri::command]
async fn list_memories(state: State<'_, AppState>) -> Result<Vec<dsh_core::storage::MemoryEntry>, String> {
    Ok(state.memory.list().await)
}

#[tauri::command]
async fn get_memory(state: State<'_, AppState>, id: String) -> Result<Option<dsh_core::storage::MemoryEntry>, String> {
    Ok(state.memory.get(&id).await)
}

#[tauri::command]
async fn search_memories(
    state: State<'_, AppState>,
    query: MemoryQuery,
) -> Result<Vec<dsh_core::storage::MemoryEntry>, String> {
    Ok(state.memory.search(query).await)
}

#[tauri::command]
async fn insert_memory(
    state: State<'_, AppState>,
    input: MemoryInput,
) -> Result<dsh_core::storage::MemoryEntry, String> {
    Ok(state.memory.insert_from_input(input).await)
}

#[tauri::command]
async fn delete_memory(state: State<'_, AppState>, id: String) -> Result<bool, String> {
    Ok(state.memory.delete(&id).await)
}

#[tauri::command]
async fn memory_stats(state: State<'_, AppState>) -> Result<MemoryStats, String> {
    Ok(state.memory.stats().await)
}

#[tauri::command]
async fn decay_memory(state: State<'_, AppState>, factor: f32) -> Result<usize, String> {
    Ok(state.memory.decay(factor).await)
}

// ============================================================
// Evolution commands
// ============================================================

#[tauri::command]
fn evolution_push(
    state: State<'_, AppState>,
    run_id: String,
    agent_id: String,
    success: bool,
    user_rating: Option<u8>,
) -> Result<(), String> {
    let mut tracker = state.evolution.lock().map_err(|e| e.to_string())?;
    tracker.push(RunRecord {
        run_id,
        agent_id,
        success,
        user_rating,
        ts: chrono::Utc::now().timestamp(),
    });
    Ok(())
}

#[tauri::command]
fn evolution_report(state: State<'_, AppState>) -> Result<EvolutionReport, String> {
    let tracker = state.evolution.lock().map_err(|e| e.to_string())?;
    Ok(tracker.report())
}

#[tauri::command]
fn evolution_history(state: State<'_, AppState>) -> Result<Vec<serde_json::Value>, String> {
    let tracker = state.evolution.lock().map_err(|e| e.to_string())?;
    let report = tracker.report();
    Ok(vec![serde_json::json!({
        "window": report.window,
        "successRate": report.success_rate,
        "averageRating": report.average_rating,
        "trend": report.trend,
    })])
}

#[tauri::command]
fn evolution_clear(state: State<'_, AppState>) -> Result<(), String> {
    let mut tracker = state.evolution.lock().map_err(|e| e.to_string())?;
    tracker.clear();
    Ok(())
}

// ============================================================
// Window control commands
// ============================================================

#[tauri::command]
fn minimize_window(win: tauri::WebviewWindow) -> Result<(), String> {
    win.minimize().map_err(|e| e.to_string())
}

#[tauri::command]
fn toggle_maximize(win: tauri::WebviewWindow) -> Result<(), String> {
    let is_max = win.is_maximized().map_err(|e| e.to_string())?;
    if is_max {
        win.unmaximize()
    } else {
        win.maximize()
    }
    .map_err(|e| e.to_string())
}

#[tauri::command]
fn close_window(win: tauri::WebviewWindow) -> Result<(), String> {
    win.close().map_err(|e| e.to_string())
}

// ============================================================
// Task-board plugin auto-install (NSIS integration)
// ============================================================

/// Ensure the task-board plugin is installed in the web profile.
/// Called on first launch so NSIS installs get the plugin automatically.
fn ensure_task_board_installed() -> Result<(), String> {
    let profile_dir = std::env::home_dir()
        .ok_or("Cannot find home directory")?
        .join(".dsh/profiles/web");
    let pkg_json = profile_dir.join("package.json");

    // Check if task-board is already in package.json
    if pkg_json.exists() {
        if let Ok(content) = std::fs::read_to_string(&pkg_json) {
            if content.contains("@linxin666/dsh-client-ui-task-board") {
                log::info!("[dsh] task-board plugin already in web profile");
                return Ok(());
            }
        }
    }

    // Install task-board via dsh plugin command
    log::info!("[dsh] installing task-board plugin to web profile...");
    let output = std::process::Command::new("npx")
        .args([
            "@deepseek-ai/dsh",
            "plugin",
            "--profile",
            "web",
            "add",
            "@linxin666/dsh-client-ui-task-board@latest",
        ])
        .current_dir(&profile_dir)
        .output()
        .map_err(|e| format!("Failed to run dsh plugin: {}", e))?;

    if output.status.success() {
        log::info!("[dsh] task-board plugin installed successfully");
        Ok(())
    } else {
        let stderr = String::from_utf8_lossy(&output.stderr);
        log::warn!("[dsh] task-board install returned: {}", stderr);
        // Non-fatal: user can install manually later
        Ok(())
    }
}

// ============================================================
// Tauri entry point
// ============================================================

pub fn run() {
    tauri::Builder::default()
        .setup(|app| {
            // Storage setup
            let exe_dir = std::env::current_exe()
                .ok()
                .and_then(|p| p.parent().map(|p| p.to_path_buf()))
                .unwrap_or_else(|| std::path::PathBuf::from("."));
            let db_path = exe_dir.join("aimarketing.db");
            let storage = match Storage::open(&db_path) {
                Ok(s) => s,
                Err(e) => {
                    eprintln!("[WARN] Failed to open storage at {:?}: {}", db_path, e);
                    let temp_dir = std::env::temp_dir().join("aimarketing");
                    std::fs::create_dir_all(&temp_dir).ok();
                    let fallback_path = temp_dir.join("aimarketing.db");
                    Storage::open(&fallback_path).expect("failed to open fallback storage")
                }
            };
            let state = AppState::new(Arc::new(storage));

            // Register embedded skills at startup
            for skill in get_embedded_skills() {
                if let Ok(manifest) = SkillManifest::from_yaml(&skill.yaml) {
                    state.registry.register_version(&skill.id, manifest, skill.yaml);
                }
            }
            log::info!("[dsh] registered {} embedded skills", get_embedded_skills().len());

            // Ensure task-board plugin is installed (NSIS integration)
            if let Err(e) = ensure_task_board_installed() {
                log::warn!("[dsh] task-board auto-install skipped: {}", e);
            }

            // Start DSH backend SYNCHRONOUSLY
            match state.start_dsh_backend() {
                Ok(port) => {
                    if state.wait_for_backend(port, 60) {
                        log::info!("[dsh] backend ready on http://127.0.0.1:{}", port);
                    } else {
                        log::error!("[dsh] backend did not become ready on port {}", port);
                    }
                }
                Err(e) => {
                    log::error!("[dsh] failed to start backend: {}", e);
                }
            }

            app.manage(state);
            log::info!("[dsh] setup complete, launching app");
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            // DSH backend
            get_dsh_url,
            // Skills
            list_embedded_skills,
            get_embedded_skill_yaml,
            execute_skill,
            eval_skill,
            list_registered_skills,
            register_skill,
            list_skill_inbox,
            adopt_skill_proposal,
            rollback_skill,
            skill_version_history,
            // Skill compiler
            compile_skill_md,
            validate_skill_md,
            decompile_skill,
            // AutoSkill
            autoskill_scan_optimization,
            autoskill_generate_draft,
            autoskill_scan_merge,
            autoskill_generate_merge_draft,
            autoskill_confirm_upgrade,
            autoskill_rollback_if_degraded,
            autoskill_rollback_all,
            // Memory
            list_memories,
            get_memory,
            search_memories,
            insert_memory,
            delete_memory,
            memory_stats,
            decay_memory,
            // Evolution
            evolution_push,
            evolution_report,
            evolution_history,
            evolution_clear,
            // Window
            minimize_window,
            toggle_maximize,
            close_window,
        ])
        .on_window_event(|_window, event| {
            if let tauri::WindowEvent::CloseRequested { .. } = event {
                // Backend process will be cleaned up by OS
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

// ============================================================
// Watermark removal is handled by dsh-plugin-watermark plugin.
// Do NOT compile plugin functionality into the core binary.
// ============================================================
