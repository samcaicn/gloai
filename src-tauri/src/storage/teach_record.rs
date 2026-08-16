// Copyright (c) 2026 MeeJoy
//
// teach_record_log —— 示教录制日志 CRUD
//
// 记录用户通过 CDP / UIA / computer_use 协议录制的操作步骤，
// 作为 AutoSkill 技能生成（teaching 来源）和去重的基础数据。

use duckdb::params;
use serde::{Deserialize, Serialize};

use super::DuckDBPool;

#[cfg(test)]
use std::sync::Arc;

// === 协议常量 ============================================================

pub const PROTOCOL_CDP: &str = "cdp";
pub const PROTOCOL_UIA: &str = "uia";
pub const PROTOCOL_COMPUTER_USE: &str = "computer_use";

// === 数据结构 ============================================================

/// 插入示教日志的输入参数。
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct TeachInsert {
    pub scene: String,
    pub app_name: String,
    pub protocol: String,
    /// 录制步骤数组（JSON），调用方传入 serde_json::Value。
    pub steps: serde_json::Value,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub step_count: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub dedup_hash: Option<String>,
}

/// 示教日志查询结果行。
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct TeachRecordRow {
    pub id: String,
    pub scene: String,
    pub app_name: String,
    pub protocol: String,
    pub steps: String, // JSON 字符串
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub step_count: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub dedup_hash: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub created_at: Option<String>,
}

// === CRUD 函数 ===========================================================

/// 插入一条示教录制日志，返回生成的记录 ID。
///
/// `steps` 必须是 JSON 数组（serde_json::Value::Array），内部序列化为
/// JSON 字符串写入 DuckDB 的 JSON 列。
pub fn insert_teach(pool: &DuckDBPool, record: &TeachInsert) -> Result<String, duckdb::Error> {
    let id = uuid::Uuid::new_v4().to_string();
    let steps_json = serde_json::to_string(&record.steps).unwrap_or_else(|_| "[]".into());

    let conn = pool.get_conn();
    conn.execute(
        "INSERT INTO teach_record_log
            (id, scene, app_name, protocol, steps, step_count, dedup_hash)
         VALUES (?, ?, ?, ?, ?, ?, ?)",
        params![
            id,
            record.scene,
            record.app_name,
            record.protocol,
            steps_json,
            record.step_count,
            record.dedup_hash,
        ],
    )?;
    Ok(id)
}

/// 按场景查询示教日志，按创建时间倒序。
pub fn query_by_scene(
    pool: &DuckDBPool,
    scene: &str,
    limit: i64,
) -> Result<Vec<TeachRecordRow>, duckdb::Error> {
    let conn = pool.get_conn();
    let mut stmt = conn.prepare(
        "SELECT
            CAST(id AS TEXT), scene, app_name, protocol, CAST(steps AS TEXT),
            step_count, dedup_hash, CAST(created_at AS TEXT)
         FROM teach_record_log
         WHERE scene = ?
         ORDER BY created_at DESC
         LIMIT ?",
    )?;
    let rows = stmt.query_map(params![scene, limit], |row| {
        Ok(TeachRecordRow {
            id: row.get(0)?,
            scene: row.get(1)?,
            app_name: row.get(2)?,
            protocol: row.get(3)?,
            steps: row.get(4)?,
            step_count: row.get(5)?,
            dedup_hash: row.get(6)?,
            created_at: row.get(7)?,
        })
    })?;
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
        let record = TeachInsert {
            scene: "work".into(),
            app_name: "WeChat".into(),
            protocol: PROTOCOL_UIA.into(),
            steps: serde_json::json!([
                {"action": "click", "target": "发送按钮"},
                {"action": "type", "text": "你好"}
            ]),
            step_count: Some(2),
            dedup_hash: Some("abc123".into()),
        };
        let id = insert_teach(&pool, &record).unwrap();
        assert!(!id.is_empty());

        let rows = query_by_scene(&pool, "work", 10).unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].app_name, "WeChat");
        assert_eq!(rows[0].protocol, PROTOCOL_UIA);
        assert!(rows[0].steps.contains("发送按钮"));
        assert_eq!(rows[0].step_count, Some(2));
    }
}
