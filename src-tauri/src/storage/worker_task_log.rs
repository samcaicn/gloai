// Copyright (c) 2026 MeeJoy
//
// worker_task_log —— Worker 任务执行日志 CRUD
//
// 记录每次技能 / 任务的执行轨迹：场景、技能版本、状态流转、耗时、错误等。
// 用于 skill_score_eval 的成功率 / 稳定性统计，以及 AutoSkill 迭代的数据源。

use duckdb::params;
use serde::{Deserialize, Serialize};

use super::DuckDBPool;

#[cfg(test)]
use std::sync::Arc;

// === 状态常量（与 DDL CHECK 约束对齐）=====================================

pub const STATUS_QUEUED: &str = "queued";
pub const STATUS_RUNNING: &str = "running";
pub const STATUS_RETRYING: &str = "retrying";
pub const STATUS_SUCCEEDED: &str = "succeeded";
pub const STATUS_FAILED: &str = "failed";
pub const STATUS_CANCELLED: &str = "cancelled";

/// 终态状态集合（不再变更的状态）。
pub const TERMINAL_STATUSES: &[&str] = &[STATUS_SUCCEEDED, STATUS_FAILED, STATUS_CANCELLED];

// === 数据结构 ============================================================

/// 插入任务日志的输入参数。
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct TaskLogInsert {
    pub scene: String,
    pub task_type: String, // lightweight / heavyweight
    pub skill_id: Option<String>,
    pub skill_version: Option<String>,
    pub status: String,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub priority: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub params: Option<serde_json::Value>,
}

/// 任务日志查询结果行。JSON / 时间戳字段统一以 TEXT 形式返回，
/// 避免依赖 duckdb crate 的 chrono / serde_json feature。
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct TaskLogRow {
    pub id: String,
    pub scene: String,
    pub task_type: String,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub skill_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub skill_version: Option<String>,
    pub status: String,
    pub priority: i32,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub params: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub result: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub error: Option<String>,
    pub retry_count: i32,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub duration_ms: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub created_at: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub started_at: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub finished_at: Option<String>,
}

// === CRUD 函数 ===========================================================

/// 插入一条任务执行日志，返回生成的任务 ID（UUID v4 字符串）。
///
/// UUID 在客户端生成（uuid crate），避免依赖 DuckDB 的 gen_random_uuid()。
/// params 字段接受 serde_json::Value，内部序列化为 JSON 字符串写入。
pub fn insert_task(pool: &DuckDBPool, task: &TaskLogInsert) -> Result<String, duckdb::Error> {
    let id = uuid::Uuid::new_v4().to_string();
    // serde_json::Value 序列化失败极少见（仅非字符串 map key），降级为空 JSON。
    let params_json = task
        .params
        .as_ref()
        .map(|v| serde_json::to_string(v).unwrap_or_else(|_| "{}".into()));

    let conn = pool.get_conn();
    conn.execute(
        "INSERT INTO worker_task_log
            (id, scene, task_type, skill_id, skill_version, status, priority, params)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        params![
            id,
            task.scene,
            task.task_type,
            task.skill_id,
            task.skill_version,
            task.status,
            task.priority.unwrap_or(0),
            params_json,
        ],
    )?;
    Ok(id)
}

/// 更新任务状态。
///
/// - `status`：新状态。
/// - `error`：失败时的错误信息（终态使用）。
/// - `result`：成功时的结果 JSON 字符串。
/// - `duration_ms`：执行耗时（毫秒）。
///
/// 自动维护时间戳：
/// - 进入 running / retrying 时若 started_at 为空则填入 now()。
/// - 进入终态（succeeded / failed / cancelled）时填入 finished_at。
pub fn update_status(
    pool: &DuckDBPool,
    id: &str,
    status: &str,
    error: Option<&str>,
    result: Option<&str>,
    duration_ms: Option<i64>,
) -> Result<usize, duckdb::Error> {
    let conn = pool.get_conn();
    let affected = conn.execute(
        "UPDATE worker_task_log SET
            status = ?,
            error = ?,
            result = ?,
            duration_ms = ?,
            started_at = CASE
                WHEN ? IN ('running','retrying') AND started_at IS NULL THEN now()
                ELSE started_at
            END,
            finished_at = CASE
                WHEN ? IN ('succeeded','failed','cancelled') THEN now()
                ELSE finished_at
            END
         WHERE id = ?",
        params![status, error, result, duration_ms, status, status, id],
    )?;
    Ok(affected)
}

/// 按 skill_id (+ 可选 skill_version) 查询任务日志，按创建时间倒序。
///
/// `skill_version` 为 None 时查询该技能所有版本。
pub fn query_by_skill(
    pool: &DuckDBPool,
    scene: &str,
    skill_id: &str,
    skill_version: Option<&str>,
    limit: i64,
) -> Result<Vec<TaskLogRow>, duckdb::Error> {
    let conn = pool.get_conn();
    let mut stmt = conn.prepare(
        "SELECT
            CAST(id AS TEXT), scene, task_type, skill_id, skill_version,
            status, priority, CAST(params AS TEXT), CAST(result AS TEXT),
            error, retry_count, duration_ms,
            CAST(created_at AS TEXT), CAST(started_at AS TEXT), CAST(finished_at AS TEXT)
         FROM worker_task_log
         WHERE scene = ? AND skill_id = ?
           AND (? IS NULL OR skill_version = ?)
         ORDER BY created_at DESC
         LIMIT ?",
    )?;
    let rows = stmt.query_map(
        params![scene, skill_id, skill_version, skill_version, limit],
        |row| {
            Ok(TaskLogRow {
                id: row.get(0)?,
                scene: row.get(1)?,
                task_type: row.get(2)?,
                skill_id: row.get(3)?,
                skill_version: row.get(4)?,
                status: row.get(5)?,
                priority: row.get(6)?,
                params: row.get(7)?,
                result: row.get(8)?,
                error: row.get(9)?,
                retry_count: row.get(10)?,
                duration_ms: row.get(11)?,
                created_at: row.get(12)?,
                started_at: row.get(13)?,
                finished_at: row.get(14)?,
            })
        },
    )?;
    let mut result = Vec::new();
    for row in rows {
        result.push(row?);
    }
    Ok(result)
}

// === 测试 ================================================================

#[cfg(test)]
mod tests {
    use super::*;

    fn setup_pool() -> DuckDBPool {
        let conn = duckdb::Connection::open_in_memory().unwrap();
        conn.execute_batch(super::super::SCHEMA_DDL).unwrap();
        DuckDBPool {
            conn: Arc::new(std::sync::Mutex::new(conn)),
        }
    }

    #[test]
    fn test_insert_and_query() {
        let pool = setup_pool();
        let task = TaskLogInsert {
            scene: "work".into(),
            task_type: "lightweight".into(),
            skill_id: Some("skill-1".into()),
            skill_version: Some("1.0.0".into()),
            status: STATUS_QUEUED.into(),
            priority: Some(5),
            params: Some(serde_json::json!({"url": "https://example.com"})),
        };
        let id = insert_task(&pool, &task).unwrap();
        assert!(!id.is_empty());

        // 更新为 running
        update_status(&pool, &id, STATUS_RUNNING, None, None, None).unwrap();

        // 更新为 succeeded
        update_status(&pool, &id, STATUS_SUCCEEDED, None, Some(r#"{"ok":true}"#), Some(1234))
            .unwrap();

        // 查询验证
        let rows = query_by_skill(&pool, "work", "skill-1", None, 10).unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].status, STATUS_SUCCEEDED);
        assert_eq!(rows[0].duration_ms, Some(1234));
        assert!(rows[0].finished_at.is_some());
        assert!(rows[0].started_at.is_some());
    }

    #[test]
    fn test_query_by_version() {
        let pool = setup_pool();
        for ver in &["1.0.0", "1.1.0", "2.0.0"] {
            insert_task(
                &pool,
                &TaskLogInsert {
                    scene: "personal".into(),
                    task_type: "heavyweight".into(),
                    skill_id: Some("skill-2".into()),
                    skill_version: Some((*ver).into()),
                    status: STATUS_FAILED.into(),
                    priority: None,
                    params: None,
                },
            )
            .unwrap();
        }
        // 查所有版本
        let all = query_by_skill(&pool, "personal", "skill-2", None, 100).unwrap();
        assert_eq!(all.len(), 3);

        // 只查 1.1.0
        let filtered = query_by_skill(&pool, "personal", "skill-2", Some("1.1.0"), 100).unwrap();
        assert_eq!(filtered.len(), 1);
    }
}
