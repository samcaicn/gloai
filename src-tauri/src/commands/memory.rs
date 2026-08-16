// Copyright (c) 2026 MeeJoy

// Memory management commands
//
// Surface is reserved for the main thread; allow dead_code until wired up.
#![allow(dead_code)]

// TODO: Extract full implementations from original commands.rs
// Functions to implement:
// - get_memories
// - add_memory
// - update_memory
// - delete_memory
// - increment_memory_access
// - compact_memories
// - migrate_memories_to_db

// === SkillMemory commands =================================================
//
// Thin Tauri-command wrappers over `crate::skill::memory` and
// `crate::skill::fts`. The heavy lifting lives in those modules;
// here we only translate the IPC types (the `since` cutoff is a
// string, not a `DateTime<Utc>`) and re-export the results.

use tauri::Manager;
use crate::skill::{fts::FtsHit, memory::RunStats};
use chrono::{DateTime, Utc};

/// Parse the `since` argument. The front-end is allowed to send
/// `None` (no cutoff) or an RFC-3339 string. Empty / whitespace
/// inputs are treated as "no cutoff" so the caller's
/// `JSON.stringify(undefined)` accidentally turning into `""` is
/// not a footgun.
fn parse_since(since: Option<String>) -> Result<DateTime<Utc>, String> {
    match since {
        None => Ok(DateTime::<Utc>::from_timestamp(0, 0).unwrap_or_else(Utc::now)),
        Some(raw) => {
            let trimmed = raw.trim();
            if trimmed.is_empty() {
                Ok(DateTime::<Utc>::from_timestamp(0, 0)
                    .unwrap_or_else(Utc::now))
            } else {
                DateTime::parse_from_rfc3339(trimmed)
                    .map(|d| d.with_timezone(&Utc))
                    .map_err(|e| format!("invalid since rfc3339: {}", e))
            }
        }
    }
}

/// Full-text search across every saved `skill_versions.skill_md`.
///
/// Wired name: `search_skills`. The front-end calls this from the
/// "我以前做过类似的吗" picker; the contract says it should be cheap
/// enough to run on every keystroke.
#[tauri::command]
pub async fn search_skills(
    app: tauri::AppHandle,
    query: String,
    limit: Option<u32>,
) -> Result<Vec<FtsHit>, String> {
    let db = app
        .try_state::<crate::skill::memory::SkillDb>()
        .ok_or_else(|| "技能数据库不可用（初始化失败，已降级）".to_string())?;
    let cap = limit.unwrap_or(20).clamp(1, 200);
    crate::skill::fts::search_skills(&db, &query, cap)
}

/// Return every lineage edge that has the given `(skill_id, version)`
/// on either end.
///
/// Wired name: `get_lineage`.
#[tauri::command]
pub async fn get_lineage(
    app: tauri::AppHandle,
    skill_id: String,
    version: u32,
) -> Result<Vec<crate::skill::memory::LineageEdge>, String> {
    let db = app
        .try_state::<crate::skill::memory::SkillDb>()
        .ok_or_else(|| "技能数据库不可用（初始化失败，已降级）".to_string())?;
    crate::skill::memory::get_lineage(&db, &skill_id, version)
}

/// Aggregate per-version run statistics since the given cutoff.
///
/// Wired name: `get_run_stats`. `since` is an RFC-3339 string; pass
/// `None` (or an empty string) for "all of history".
#[tauri::command]
pub async fn get_run_stats(
    app: tauri::AppHandle,
    skill_id: String,
    version: u32,
    since: Option<String>,
) -> Result<RunStats, String> {
    let cutoff = parse_since(since)?;
    let db = app
        .try_state::<crate::skill::memory::SkillDb>()
        .ok_or_else(|| "技能数据库不可用（初始化失败，已降级）".to_string())?;
    crate::skill::memory::get_run_stats(&db, &skill_id, version, cutoff)
}
