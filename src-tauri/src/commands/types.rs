// Copyright (c) 2026 MeeJoy

use serde::{Deserialize, Serialize};
use rusqlite::Connection;
use tauri::Manager;

const APP_DB_FILENAME: &str = "tupai.db";
const LEGACY_APP_DB_FILENAME: &str = "hermes-desktop-lite.db";

// === Helper Functions ===
pub fn now_rfc3339() -> String {
    use std::time::{SystemTime, UNIX_EPOCH};
    // 用 saturating_duration_since 避免 NTP 异常 / CMOS 电池没电导致
    // 系统时间早于 1970-01-01 时整个进程 panic。
    // 退化为 1970-01-01 epoch 起点,而不是吞错返回 1970-01-01 之外
    // 的奇怪时间戳(unwrap_or_default 在 duration_since 失败时返回
    // Duration::ZERO,与"时间正常但非常早"无法区分)。
    let dur = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_else(|error| {
            log::warn!(
                "[types::now_rfc3339] SystemTime 早于 UNIX_EPOCH,退化到 epoch: {}",
                error
            );
            std::time::Duration::ZERO
        });
    let secs = dur.as_secs();
    let millis = dur.subsec_millis();
    // 保留真实毫秒精度,避免同秒内多条记录排序不确定,
    // 也避免前端误以为精度到毫秒而实际永远是 .000。
    format!("{}.{:03}Z", secs, millis)
}

pub fn open_app_db(app: &tauri::AppHandle) -> Result<Connection, String> {
    let app_dir = app.path().app_data_dir().map_err(|e: tauri::Error| e.to_string())?;
    std::fs::create_dir_all(&app_dir).map_err(|e| e.to_string())?;
    let db_path = app_dir.join(APP_DB_FILENAME);
    let legacy_db_path = app_dir.join(LEGACY_APP_DB_FILENAME);

    if !db_path.exists() && legacy_db_path.exists() {
        std::fs::copy(&legacy_db_path, &db_path).map_err(|e| e.to_string())?;
    }

    Connection::open(db_path).map_err(|e| e.to_string())
}

#[allow(dead_code)]
// Helper kept for the upcoming workspace-path unification PR; the
// active duplicate lives in `commands::legacy::normalize_workspace_path`.
pub fn normalize_workspace_path(workspace_filter: Option<&str>) -> Option<String> {
    workspace_filter.map(|p| {
        let expanded = shellexpand::tilde(p);
        let cleaned = expanded.to_string().trim_end_matches('/').to_string();
        if cleaned.is_empty() { "/".to_string() } else { cleaned }
    })
}

// === Memory Types ===
//
// V2 扩展字段（version / parent_id / parent_version / task_type /
// tool_used / confidence / session_id / channel_id / outcome）支持
// hermes::memory_evolution 的自动记忆升级（版本族谱 + 去重合并 +
// writeSuccess/writeFailure 编码）。旧数据这些字段为 None/默认值，
// 通过 ensure_app_schema 的 ALTER TABLE ADD COLUMN 兜底。
#[derive(Serialize, Deserialize, Clone, Debug, Default)]
#[serde(default)]
pub struct MemoryEntry {
    pub id: String,
    pub summary: String,
    pub content: String,
    pub source: Option<String>,
    pub created_at: String,
    pub updated_at: String,
    pub importance: String,
    pub access_count: i64,
    pub last_accessed_at: Option<String>,
    pub workspace_path: Option<String>,
    /// 记忆版本号，初始 1，每次高相似度升级 +1
    pub version: i64,
    /// 指向父记忆（升级前的上一版本），根记忆为 None
    pub parent_id: Option<String>,
    pub parent_version: Option<i64>,
    /// 任务类型标签（如 "im_chat" / "skill_run" / "insight"）
    pub task_type: Option<String>,
    /// 使用的工具名（如 "im_bridge.send_message"）
    pub tool_used: Option<String>,
    /// 置信度 [0.0, 1.0]，由 success/user_feedback 推导
    pub confidence: f32,
    pub session_id: Option<String>,
    pub channel_id: Option<String>,
    /// "success" | "failure" | None（旧数据无）
    pub outcome: Option<String>,
}

#[derive(Serialize, Deserialize)]
#[allow(dead_code)]
// IPC contract type; may be invoked by external consumers
pub struct MemoryCreate {
    pub summary: String,
    pub content: String,
    pub source: Option<String>,
    pub importance: Option<String>,
    pub workspace_path: Option<String>,
}

#[derive(Serialize, Deserialize)]
#[allow(dead_code)]
// IPC contract type; may be invoked by external consumers
pub struct MemoryUpdate {
    pub summary: Option<String>,
    pub content: Option<String>,
    pub source: Option<String>,
    pub importance: Option<String>,
}

#[derive(Serialize, Deserialize)]
#[allow(dead_code)]
// IPC contract type; may be invoked by external consumers
pub struct MigrationResult {
    pub migrated: i64,
    pub skipped: i64,
    pub errors: Vec<String>,
}

// === Task Types ===
#[derive(Serialize, Deserialize, Clone, Debug)]
#[allow(dead_code)]
// IPC contract type; may be invoked by external consumers
pub struct Task {
    pub id: String,
    pub title: String,
    pub description: Option<String>,
    pub status: String,
    pub priority: Option<String>,
    pub due_date: Option<String>,
    pub tags: Option<String>,
    pub project: Option<String>,
    pub workspace_path: Option<String>,
    pub created_at: String,
    pub updated_at: String,
    pub completed_at: Option<String>,
}

#[derive(Serialize, Deserialize)]
#[allow(dead_code)]
// IPC contract type; may be invoked by external consumers
pub struct TaskCreate {
    pub title: String,
    pub description: Option<String>,
    pub priority: Option<String>,
    pub due_date: Option<String>,
    pub tags: Option<String>,
    pub project: Option<String>,
    pub workspace_path: Option<String>,
}

#[derive(Serialize, Deserialize)]
#[allow(dead_code)]
// IPC contract type; may be invoked by external consumers
pub struct TaskUpdate {
    pub title: Option<String>,
    pub description: Option<String>,
    pub status: Option<String>,
    pub priority: Option<String>,
    pub due_date: Option<String>,
    pub tags: Option<String>,
    pub project: Option<String>,
}

// === Session Types ===
#[derive(Serialize, Deserialize, Clone, Debug)]
#[allow(dead_code)]
// IPC contract type; may be invoked by external consumers
pub struct Session {
    pub id: String,
    pub title: String,
    pub model: Option<String>,
    pub pinned: bool,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Serialize, Deserialize)]
#[allow(dead_code)]
// IPC contract type; may be invoked by external consumers
pub struct SessionCreate {
    pub title: Option<String>,
    pub model: Option<String>,
}

// === Message Types ===
#[derive(Serialize, Deserialize, Clone, Debug)]
#[allow(dead_code)]
// IPC contract type; may be invoked by external consumers
pub struct Message {
    pub id: String,
    pub session_id: String,
    pub role: String,
    pub content: String,
    pub created_at: String,
    pub attachments: Option<serde_json::Value>,
}

#[derive(Serialize, Deserialize)]
#[allow(dead_code)]
// IPC contract type; may be invoked by external consumers
pub struct MessageCreate {
    pub content: String,
    pub attachments: Option<serde_json::Value>,
}

// === Workspace Types ===
#[derive(Serialize, Deserialize, Clone, Debug)]
#[allow(dead_code)]
// IPC contract type; may be invoked by external consumers
pub struct Workspace {
    pub id: String,
    pub name: String,
    pub path: String,
    pub icon: Option<String>,
    pub created_at: Option<String>,
}

#[derive(Serialize, Deserialize)]
#[allow(dead_code)]
// IPC contract type; may be invoked by external consumers
pub struct WorkspaceCreate {
    pub name: String,
    pub path: String,
    pub icon: Option<String>,
}

#[derive(Serialize, Deserialize)]
#[allow(dead_code)]
// IPC contract type; may be invoked by external consumers
pub struct WorkspaceUpdate {
    pub name: Option<String>,
    pub path: Option<String>,
    pub icon: Option<String>,
}

// === Notebook Types ===
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct NotebookFolder {
    pub id: String,
    pub parent_id: Option<String>,
    pub name: String,
    pub sort_order: i64,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct NotebookNote {
    pub id: String,
    pub folder_id: Option<String>,
    pub title: String,
    pub content: String,
    pub sort_order: i64,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct NotebookTree {
    pub folders: Vec<NotebookFolder>,
    pub notes: Vec<NotebookNoteMeta>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct NotebookNoteMeta {
    pub id: String,
    pub folder_id: Option<String>,
    pub title: String,
    pub sort_order: i64,
    pub created_at: String,
    pub updated_at: String,
}

// === Config Types ===
#[derive(Serialize, Deserialize, Clone, Debug)]
#[allow(dead_code)]
// IPC contract type; may be invoked by external consumers
pub struct AppConfig {
    pub gateway_host: Option<String>,
    pub gateway_port: Option<i64>,
    pub hermes_agent_path: Option<String>,
    pub hermes_workspace: Option<String>,
    #[serde(default)]
    pub user_nickname: Option<String>,
}

// === Gateway Types ===
#[derive(Serialize, Deserialize, Clone, Debug)]
#[allow(dead_code)]
// IPC contract type; may be invoked by external consumers
pub struct GatewayInfo {
    pub target: String,
    pub installed_version: Option<String>,
    pub latest_version: Option<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[allow(dead_code)]
// IPC contract type; may be invoked by external consumers
pub struct HermesVersionInfo {
    pub installed_display: Option<String>,
    pub installed_version: Option<String>,
    pub latest_tag: Option<String>,
    pub latest_name: Option<String>,
    pub latest_display: Option<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[allow(dead_code)]
// IPC contract type; may be invoked by external consumers
pub struct HermesUpdateResult {
    pub success: bool,
    pub stdout: String,
    pub stderr: String,
}

// === Agent/Skill Types ===
#[derive(Serialize, Deserialize, Clone, Debug)]
#[allow(dead_code)]
// IPC contract type; may be invoked by external consumers
pub struct Agent {
    pub id: String,
    pub name: String,
    pub description: Option<String>,
    pub version: Option<String>,
    pub enabled: bool,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[allow(dead_code)]
// IPC contract type; may be invoked by external consumers
pub struct SkillInfo {
    pub name: String,
    pub description: Option<String>,
    pub enabled: bool,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[allow(dead_code)]
// IPC contract type; may be invoked by external consumers
pub struct ToolsetInfo {
    pub name: String,
    pub tools: Vec<serde_json::Value>,
}

// === Misc Types ===
#[derive(Serialize, Deserialize, Clone, Debug)]
#[allow(dead_code)]
// IPC contract type; may be invoked by external consumers
pub struct HermesDashboardRestartResult {
    pub success: bool,
    pub stdout: String,
    pub stderr: String,
}
