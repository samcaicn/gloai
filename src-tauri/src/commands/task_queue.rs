// Copyright (c) 2026 AIMarketing
//
// Skill / MCP execution task queue.
//
// 真实的入队 / 查询 / 取消队列：内存状态 + 持久化到
// `app_data_dir/skill_queue.json`（pretty JSON，整文件原子写）。
// 执行 worker 是后续交付，但入队后的任务会真实落盘、查询可见、
// 取消可标记 `cancelled` 并同步持久化。

use serde::{Deserialize, Serialize};
use std::sync::{Mutex, OnceLock};
use tauri::Manager;

/// 一条队列任务（字段与前端约定一致）。
#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct QueuedTask {
    pub queue_id: String,
    /// 原始 `SKILL.md` 正文，或一个 MCP 工具描述符（如 `mcp://filesystem/read`）。
    pub skill_md_or_mcp: String,
    pub priority: u32,
    pub enqueued_at: i64, // unix 秒
    /// `queued` | `running` | `done` | `cancelled` | `failed`
    pub status: String,
}

static QUEUE: OnceLock<Mutex<Vec<QueuedTask>>> = OnceLock::new();
static LOADED: OnceLock<()> = OnceLock::new();

fn queue() -> &'static Mutex<Vec<QueuedTask>> {
    QUEUE.get_or_init(|| Mutex::new(Vec::new()))
}

fn queue_path(app: &tauri::AppHandle) -> Option<std::path::PathBuf> {
    app.path()
        .app_data_dir()
        .ok()
        .map(|d| d.join("skill_queue.json"))
}

/// 首次访问时从磁盘加载；后续调用是 no-op。
fn ensure_loaded(app: &tauri::AppHandle) {
    if LOADED.set(()).is_ok() {
        if let Some(path) = queue_path(app) {
            if let Ok(bytes) = std::fs::read(&path) {
                if let Ok(tasks) = serde_json::from_slice::<Vec<QueuedTask>>(&bytes) {
                    if let Ok(mut q) = queue().lock() {
                        *q = tasks;
                    }
                }
            }
        }
    }
}

/// 把内存队列整文件写回磁盘（覆盖式原子写：先写临时文件再 rename）。
fn persist(app: &tauri::AppHandle) {
    if let Some(path) = queue_path(app) {
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        if let Ok(q) = queue().lock() {
            if let Ok(bytes) = serde_json::to_vec_pretty(&*q) {
                let tmp = path.with_extension("json.tmp");
                if std::fs::write(&tmp, &bytes).is_ok() {
                    let _ = std::fs::rename(&tmp, &path);
                }
            }
        }
    }
}

/// 入队一个 skill / MCP 执行任务。立即返回 `queue_id` 并落盘。
#[tauri::command]
pub fn enqueue_skill_task(
    app: tauri::AppHandle,
    skill_md_or_mcp: String,
    priority: u32,
) -> Result<String, String> {
    ensure_loaded(&app);
    let now = chrono::Utc::now();
    let queue_id = format!("q_{}", now.timestamp_millis());
    let task = QueuedTask {
        queue_id: queue_id.clone(),
        skill_md_or_mcp,
        priority,
        enqueued_at: now.timestamp(),
        status: "queued".to_string(),
    };
    {
        let mut q = queue().lock().map_err(|e| e.to_string())?;
        q.push(task);
    }
    persist(&app);
    Ok(queue_id)
}

/// 返回当前队列（按入队时间倒序，最新的在前）。
#[tauri::command]
pub fn list_queued_tasks(app: tauri::AppHandle) -> Vec<QueuedTask> {
    ensure_loaded(&app);
    if let Ok(q) = queue().lock() {
        let mut tasks: Vec<QueuedTask> = q.iter().cloned().collect();
        tasks.sort_by_key(|b| std::cmp::Reverse(b.enqueued_at));
        tasks
    } else {
        Vec::new()
    }
}

/// 取消一个排队中的任务：标记为 `cancelled` 并落盘。
/// 找不到 `queue_id` 时返回错误（让前端知道 id 失效）。
#[tauri::command]
pub fn cancel_queued_task(app: tauri::AppHandle, queue_id: String) -> Result<(), String> {
    ensure_loaded(&app);
    let mut q = queue().lock().map_err(|e| e.to_string())?;
    let mut found = false;
    for task in q.iter_mut() {
        if task.queue_id == queue_id {
            task.status = "cancelled".to_string();
            found = true;
            break;
        }
    }
    drop(q);
    if found {
        persist(&app);
        Ok(())
    } else {
        Err(format!("queue_id not found: {}", queue_id))
    }
}
