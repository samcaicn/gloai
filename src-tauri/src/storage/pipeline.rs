use duckdb::params;
use serde::{Deserialize, Serialize};

use super::DuckDBPool;

// ── 状态常量 ────────────────────────────────────────────

pub const STATUS_IDLE: &str = "idle";
pub const STATUS_RUNNING: &str = "running";
pub const STATUS_PAUSED: &str = "paused";
pub const STATUS_COMPLETED: &str = "completed";
pub const STATUS_STOPPED: &str = "stopped";

// ── 插入结构 ────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PipelineInsert {
    pub id: String,
    pub name: String,
    pub scene: String,
    pub steps_json: String,
    pub rounds: i32,
    pub status: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PipelineRow {
    pub id: String,
    pub name: String,
    pub scene: String,
    pub steps_json: String,
    pub rounds: i32,
    pub current_round: i32,
    pub status: String,
    pub created_at: String,
    pub updated_at: String,
}

// ── CRUD ────────────────────────────────────────────────

pub fn insert_pipeline(pool: &DuckDBPool, input: &PipelineInsert) -> Result<(), duckdb::Error> {
    let conn = pool.get_conn();
    conn.execute(
        "INSERT INTO pipeline_def (id, name, scene, steps_json, rounds, status)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
        params![input.id, input.name, input.scene, input.steps_json, input.rounds, input.status],
    )?;
    Ok(())
}

pub fn list_pipelines(pool: &DuckDBPool, scene: &str) -> Result<Vec<PipelineRow>, duckdb::Error> {
    let conn = pool.get_conn();
    let mut stmt = conn.prepare(
        "SELECT id, name, scene, steps_json, rounds, current_round, status,
                created_at::TEXT, updated_at::TEXT
         FROM pipeline_def
         WHERE scene = ?1
         ORDER BY updated_at DESC"
    )?;
    let rows = stmt.query_map(params![scene], |row| {
        Ok(PipelineRow {
            id: row.get(0)?,
            name: row.get(1)?,
            scene: row.get(2)?,
            steps_json: row.get(3)?,
            rounds: row.get(4)?,
            current_round: row.get(5)?,
            status: row.get(6)?,
            created_at: row.get(7)?,
            updated_at: row.get(8)?,
        })
    })?;
    let mut result = Vec::new();
    for r in rows {
        result.push(r?);
    }
    Ok(result)
}

pub fn get_pipeline(pool: &DuckDBPool, id: &str) -> Result<Option<PipelineRow>, duckdb::Error> {
    let conn = pool.get_conn();
    let mut stmt = conn.prepare(
        "SELECT id, name, scene, steps_json, rounds, current_round, status,
                created_at::TEXT, updated_at::TEXT
         FROM pipeline_def WHERE id = ?1"
    )?;
    let mut rows = stmt.query_map(params![id], |row| {
        Ok(PipelineRow {
            id: row.get(0)?,
            name: row.get(1)?,
            scene: row.get(2)?,
            steps_json: row.get(3)?,
            rounds: row.get(4)?,
            current_round: row.get(5)?,
            status: row.get(6)?,
            created_at: row.get(7)?,
            updated_at: row.get(8)?,
        })
    })?;
    match rows.next() {
        Some(r) => Ok(Some(r?)),
        None => Ok(None),
    }
}

pub fn update_pipeline(
    pool: &DuckDBPool,
    id: &str,
    name: &str,
    steps_json: &str,
    rounds: i32,
    status: &str,
) -> Result<(), duckdb::Error> {
    let conn = pool.get_conn();
    conn.execute(
        "UPDATE pipeline_def SET name=?1, steps_json=?2, rounds=?3, status=?4,
                updated_at=now()
         WHERE id=?5",
        params![name, steps_json, rounds, status, id],
    )?;
    Ok(())
}

pub fn update_pipeline_status(pool: &DuckDBPool, id: &str, status: &str) -> Result<(), duckdb::Error> {
    let conn = pool.get_conn();
    conn.execute(
        "UPDATE pipeline_def SET status=?1, updated_at=now() WHERE id=?2",
        params![status, id],
    )?;
    Ok(())
}

pub fn update_pipeline_round(
    pool: &DuckDBPool,
    id: &str,
    current_round: i32,
    status: &str,
) -> Result<(), duckdb::Error> {
    let conn = pool.get_conn();
    conn.execute(
        "UPDATE pipeline_def SET current_round=?1, status=?2, updated_at=now() WHERE id=?3",
        params![current_round, status, id],
    )?;
    Ok(())
}

pub fn delete_pipeline(pool: &DuckDBPool, id: &str) -> Result<bool, duckdb::Error> {
    let conn = pool.get_conn();
    let affected = conn.execute("DELETE FROM pipeline_def WHERE id=?1", params![id])?;
    Ok(affected > 0)
}
