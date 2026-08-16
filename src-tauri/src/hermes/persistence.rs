// Copyright (c) 2026 AIMarketing
//
// HermesDb — unified sqlite persistence layer for the hermes core
// modules (memory_ops / evolution_stats / trajectory_store /
// profile / persona).
//
// Design mirrors `crate::skill::memory::SkillDb`: a long-lived
// `Mutex<Connection>` opened against `<app_data_dir>/tupai.db`.
// We deliberately reuse the *same* database file that
// `commands::types::open_app_db` and `skill::memory::SkillDb`
// already touch, so a single WAL handle serves every sqlite-backed
// feature in the app.
//
// All hermes-owned tables are prefixed with `hermes_` to keep them
// visually separate from the `memories` / `tasks` / `skill_*` tables
// that pre-date this module.

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};

use crate::commands::types::MemoryEntry;
use crate::hermes::evolution_signal::EvolutionSignal;
use crate::hermes::evolution_stats::{EvolutionState, SkillStat, SkillStatus};
use crate::hermes::trajectory_store::TrajectoryStep;

// === Schema bootstrap ===================================================

/// Inline DDL for every hermes-owned table. Re-applied on every
/// `HermesDb::open_at` — `CREATE TABLE IF NOT EXISTS` makes this
/// idempotent.
#[allow(dead_code)] // DDL bootstrap; applied by `HermesDb::open_at` below
const HERMES_DDL: &str = r#"
CREATE TABLE IF NOT EXISTS hermes_memories (
    id               TEXT PRIMARY KEY,
    summary          TEXT NOT NULL,
    content          TEXT NOT NULL,
    source           TEXT,
    created_at       TEXT NOT NULL,
    updated_at       TEXT NOT NULL,
    importance       TEXT NOT NULL DEFAULT 'warm',
    access_count     INTEGER NOT NULL DEFAULT 0,
    last_accessed_at TEXT,
    workspace_path   TEXT
);
CREATE INDEX IF NOT EXISTS idx_hermes_memories_workspace  ON hermes_memories(workspace_path);
CREATE INDEX IF NOT EXISTS idx_hermes_memories_importance ON hermes_memories(importance);
CREATE INDEX IF NOT EXISTS idx_hermes_memories_created_at ON hermes_memories(created_at DESC);

CREATE TABLE IF NOT EXISTS hermes_evolution_stats (
    skill_id              TEXT PRIMARY KEY,
    name                  TEXT NOT NULL,
    runs                  INTEGER NOT NULL DEFAULT 0,
    sent                  INTEGER NOT NULL DEFAULT 0,
    failed                INTEGER NOT NULL DEFAULT 0,
    consecutive_failures  INTEGER NOT NULL DEFAULT 0,
    last_run_ms           INTEGER NOT NULL DEFAULT 0,
    last_success_ms       INTEGER NOT NULL DEFAULT 0,
    last_failure_ms       INTEGER NOT NULL DEFAULT 0,
    status                TEXT NOT NULL DEFAULT 'idle',
    circuit_open_until_ms INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS hermes_evolution_meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS hermes_trajectory_steps (
    id           TEXT NOT NULL,
    session_id   TEXT NOT NULL,
    step         INTEGER NOT NULL,
    kind         TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    ts           TEXT NOT NULL,
    PRIMARY KEY (session_id, step)
);
CREATE INDEX IF NOT EXISTS idx_hermes_trajectory_session ON hermes_trajectory_steps(session_id);

CREATE TABLE IF NOT EXISTS hermes_profiles (
    id           TEXT PRIMARY KEY,
    profile_json TEXT NOT NULL,
    updated_at   TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS hermes_personas (
    id           TEXT PRIMARY KEY,
    persona_json TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS hermes_active_persona (
    id         INTEGER PRIMARY KEY CHECK (id = 0),
    persona_id TEXT
);

CREATE TABLE IF NOT EXISTS hermes_evolution_signals (
    signal_id        TEXT PRIMARY KEY,
    signal_kind      TEXT NOT NULL,
    source_kind      TEXT NOT NULL,
    session_id       TEXT,
    skill_id         TEXT,
    skill_kind        TEXT NOT NULL DEFAULT 'mcp',
    signal_type      TEXT,
    evidence_json    TEXT,
    suggested_action TEXT,
    confidence       REAL NOT NULL DEFAULT 0,
    consumed         INTEGER NOT NULL DEFAULT 0,
    created_at       TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_hermes_evo_signals_session   ON hermes_evolution_signals(session_id);
CREATE INDEX IF NOT EXISTS idx_hermes_evo_signals_skill     ON hermes_evolution_signals(skill_id);
CREATE INDEX IF NOT EXISTS idx_hermes_evo_signals_consumed  ON hermes_evolution_signals(consumed);

CREATE TABLE IF NOT EXISTS hermes_session_analysis_runs (
    run_id            TEXT PRIMARY KEY,
    started_at        TEXT NOT NULL,
    finished_at       TEXT NOT NULL,
    sessions_scanned  INTEGER NOT NULL,
    signals_emitted   INTEGER NOT NULL,
    llm_tokens_used   INTEGER,
    degraded          INTEGER NOT NULL DEFAULT 0,
    summary           TEXT
);
CREATE INDEX IF NOT EXISTS idx_hermes_sar_started ON hermes_session_analysis_runs(started_at DESC);
"#;

// === State ==============================================================

/// Long-lived sqlite handle shared by every hermes sub-module that
/// needs persistence. Modeled after `crate::skill::memory::SkillDb`.
#[allow(dead_code)] // registered by `HermesAppState::with_persistence`
pub struct HermesDb {
    conn: Mutex<Connection>,
}

#[allow(dead_code)] // Tauri-managed `HermesDb` constructors
impl HermesDb {
    /// Open (or create) the hermes database at
    /// `<app_data_dir>/tupai.db` and apply the schema DDL block.
    pub fn open(app_data_dir: &Path) -> Result<Self, String> {
        Self::open_at(app_data_dir.join("tupai.db"))
    }

    /// Open at a specific path. Public for unit tests so they can
    /// point at a `tempfile::tempdir()`.
    pub fn open_at(path: PathBuf) -> Result<Self, String> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)
                .map_err(|e| format!("Failed to create db parent {:?}: {}", parent, e))?;
        }
        let conn = Connection::open(&path)
            .map_err(|e| format!("Failed to open hermes db {:?}: {}", path, e))?;
        conn.execute_batch(
            "PRAGMA journal_mode = WAL;\nPRAGMA foreign_keys = ON;\nPRAGMA busy_timeout = 5000;\n",
        )
        .map_err(|e| format!("Failed to apply pragmas: {}", e))?;
        conn.execute_batch(HERMES_DDL)
            .map_err(|e| format!("Failed to apply hermes DDL: {}", e))?;
        Ok(Self { conn: Mutex::new(conn) })
    }

    /// Acquire the inner connection. Recovers from a poisoned mutex
    /// by discarding the poisoned data (logging an error) so the
    /// process can continue — same recovery policy as `SkillDb`.
    pub fn conn(&self) -> std::sync::MutexGuard<'_, Connection> {
        self.conn.lock().unwrap_or_else(|e| {
            log::error!("[hermes/persistence] HermesDb mutex poisoned, recovering");
            e.into_inner()
        })
    }

    /// Shared convenience: wrap in `Arc` for threading through
    /// `HermesAppState`.
    pub fn shared(self) -> Arc<Self> {
        Arc::new(self)
    }
}

// === MemoryEntry CRUD ===================================================

/// Upsert a single `hermes_memories` row. `MemoryEntry` is the
/// canonical in-memory type already used by `MemoryOps`, so we
/// map field-for-field.
#[allow(dead_code)] // called by `MemoryOps::insert`
pub fn upsert_memory(db: &HermesDb, m: &MemoryEntry) -> Result<(), String> {
    let conn = db.conn();
    conn.execute(
        r#"INSERT INTO hermes_memories
            (id, summary, content, source, created_at, updated_at,
             importance, access_count, last_accessed_at, workspace_path)
           VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)
           ON CONFLICT(id) DO UPDATE SET
             summary          = excluded.summary,
             content          = excluded.content,
             source           = excluded.source,
             updated_at       = excluded.updated_at,
             importance       = excluded.importance,
             access_count     = excluded.access_count,
             last_accessed_at = excluded.last_accessed_at,
             workspace_path   = excluded.workspace_path"#,
        params![
            m.id,
            m.summary,
            m.content,
            m.source.as_deref().unwrap_or("对话"),
            m.created_at,
            m.updated_at,
            m.importance,
            m.access_count,
            m.last_accessed_at,
            m.workspace_path,
        ],
    )
    .map_err(|e| format!("upsert hermes_memories: {}", e))?;
    Ok(())
}

/// Delete a single `hermes_memories` row by id.
#[allow(dead_code)] // called by `MemoryOps::delete`
pub fn delete_memory(db: &HermesDb, id: &str) -> Result<bool, String> {
    let conn = db.conn();
    let n = conn
        .execute("DELETE FROM hermes_memories WHERE id = ?1", params![id])
        .map_err(|e| format!("delete hermes_memories: {}", e))?;
    Ok(n > 0)
}

/// Return every `hermes_memories` row, ordered newest-first.
#[allow(dead_code)] // called by `MemoryOps::list`
pub fn list_memories(db: &HermesDb) -> Result<Vec<MemoryEntry>, String> {
    let conn = db.conn();
    let mut stmt = conn
        .prepare(
            r#"SELECT id, summary, content, source, created_at, updated_at,
                      importance, access_count, last_accessed_at, workspace_path
               FROM hermes_memories
               ORDER BY created_at DESC"#,
        )
        .map_err(|e| format!("prepare list hermes_memories: {}", e))?;
    let rows = stmt
        .query_map([], |row| {
            Ok(MemoryEntry {
                id: row.get(0)?,
                summary: row.get(1)?,
                content: row.get(2)?,
                source: row.get(3)?,
                created_at: row.get(4)?,
                updated_at: row.get(5)?,
                importance: row.get(6)?,
                access_count: row.get(7)?,
                last_accessed_at: row.get(8)?,
                workspace_path: row.get(9)?,
                version: 1,
                parent_id: None,
                parent_version: None,
                task_type: None,
                tool_used: None,
                confidence: 0.0,
                session_id: None,
                channel_id: None,
                outcome: None,
            })
        })
        .map_err(|e| format!("query list hermes_memories: {}", e))?;
    let mut out = Vec::new();
    for r in rows {
        out.push(r.map_err(|e| format!("read hermes_memories: {}", e))?);
    }
    Ok(out)
}

/// Look up a single `hermes_memories` row by id.
#[allow(dead_code)] // called by `MemoryOps::get`
pub fn get_memory(db: &HermesDb, id: &str) -> Result<Option<MemoryEntry>, String> {
    let conn = db.conn();
    conn.query_row(
        r#"SELECT id, summary, content, source, created_at, updated_at,
                  importance, access_count, last_accessed_at, workspace_path
           FROM hermes_memories WHERE id = ?1"#,
        params![id],
        |row| {
            Ok(MemoryEntry {
                id: row.get(0)?,
                summary: row.get(1)?,
                content: row.get(2)?,
                source: row.get(3)?,
                created_at: row.get(4)?,
                updated_at: row.get(5)?,
                importance: row.get(6)?,
                access_count: row.get(7)?,
                last_accessed_at: row.get(8)?,
                workspace_path: row.get(9)?,
                version: 1,
                parent_id: None,
                parent_version: None,
                task_type: None,
                tool_used: None,
                confidence: 0.0,
                session_id: None,
                channel_id: None,
                outcome: None,
            })
        },
    )
    .optional()
    .map_err(|e| format!("get hermes_memories: {}", e))
}

// === Evolution stats persistence ========================================

/// Persist a single `SkillStat` row (upsert by `skill_id`).
#[allow(dead_code)] // called by `evolution_stats::record_run` / `clear_stats`
pub fn upsert_skill_stat(db: &HermesDb, s: &SkillStat) -> Result<(), String> {
    let conn = db.conn();
    let status_str = match s.status {
        SkillStatus::Idle => "idle",
        SkillStatus::Active => "active",
        SkillStatus::CircuitBroken => "circuit_broken",
    };
    conn.execute(
        r#"INSERT INTO hermes_evolution_stats
            (skill_id, name, runs, sent, failed, consecutive_failures,
             last_run_ms, last_success_ms, last_failure_ms, status,
             circuit_open_until_ms)
           VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)
           ON CONFLICT(skill_id) DO UPDATE SET
             name                  = excluded.name,
             runs                  = excluded.runs,
             sent                  = excluded.sent,
             failed                = excluded.failed,
             consecutive_failures  = excluded.consecutive_failures,
             last_run_ms           = excluded.last_run_ms,
             last_success_ms       = excluded.last_success_ms,
             last_failure_ms       = excluded.last_failure_ms,
             status                = excluded.status,
             circuit_open_until_ms = excluded.circuit_open_until_ms"#,
        params![
            s.skill_id,
            s.name,
            s.runs,
            s.sent,
            s.failed,
            s.consecutive_failures,
            s.last_run_ms,
            s.last_success_ms,
            s.last_failure_ms,
            status_str,
            s.circuit_open_until_ms,
        ],
    )
    .map_err(|e| format!("upsert hermes_evolution_stats: {}", e))?;
    Ok(())
}

/// Delete every `hermes_evolution_stats` row. Used by `clear_stats`.
#[allow(dead_code)] // called by `evolution_stats::clear_stats`
pub fn clear_skill_stats(db: &HermesDb) -> Result<(), String> {
    let conn = db.conn();
    conn.execute("DELETE FROM hermes_evolution_stats", [])
        .map_err(|e| format!("clear hermes_evolution_stats: {}", e))?;
    Ok(())
}

/// Read every `hermes_evolution_stats` row, used by
/// `evolution_stats::init_persistence` to hydrate the in-memory
/// state on startup.
#[allow(dead_code)] // called by `evolution_stats::init_persistence`
pub fn list_skill_stats(db: &HermesDb) -> Result<Vec<SkillStat>, String> {
    let conn = db.conn();
    let mut stmt = conn
        .prepare(
            r#"SELECT skill_id, name, runs, sent, failed, consecutive_failures,
                      last_run_ms, last_success_ms, last_failure_ms, status,
                      circuit_open_until_ms
               FROM hermes_evolution_stats"#,
        )
        .map_err(|e| format!("prepare list hermes_evolution_stats: {}", e))?;
    let rows = stmt
        .query_map([], |row| {
            let status_str: String = row.get(9)?;
            let status = match status_str.as_str() {
                "active" => SkillStatus::Active,
                "circuit_broken" => SkillStatus::CircuitBroken,
                _ => SkillStatus::Idle,
            };
            Ok(SkillStat {
                skill_id: row.get(0)?,
                name: row.get(1)?,
                runs: row.get(2)?,
                sent: row.get(3)?,
                failed: row.get(4)?,
                success_rate: None, // recomputed by caller
                consecutive_failures: row.get(5)?,
                last_run_ms: row.get(6)?,
                last_success_ms: row.get(7)?,
                last_failure_ms: row.get(8)?,
                status,
                circuit_open_until_ms: row.get(10)?,
            })
        })
        .map_err(|e| format!("query list hermes_evolution_stats: {}", e))?;
    let mut out = Vec::new();
    for r in rows {
        out.push(r.map_err(|e| format!("read hermes_evolution_stats: {}", e))?);
    }
    Ok(out)
}

/// Set / get the global `auto_evolve` flag and the cumulative
/// counters (`total_scans`, `total_sent`, `total_failed`,
/// `last_updated_ms`). Stored as key/value rows in
/// `hermes_evolution_meta`.
#[allow(dead_code)] // called by `evolution_stats::set_auto_evolve` / `init_persistence`
pub fn set_meta(db: &HermesDb, key: &str, value: &str) -> Result<(), String> {
    let conn = db.conn();
    conn.execute(
        "INSERT INTO hermes_evolution_meta (key, value) VALUES (?1, ?2)
         ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        params![key, value],
    )
    .map_err(|e| format!("upsert hermes_evolution_meta: {}", e))?;
    Ok(())
}

#[allow(dead_code)] // called by `evolution_stats::init_persistence`
pub fn get_meta(db: &HermesDb, key: &str) -> Result<Option<String>, String> {
    let conn = db.conn();
    conn.query_row(
        "SELECT value FROM hermes_evolution_meta WHERE key = ?1",
        params![key],
        |row| row.get::<_, String>(0),
    )
    .optional()
    .map_err(|e| format!("get hermes_evolution_meta: {}", e))
}

/// Persist the cumulative `EvolutionState` counters + flag. The
/// per-skill `skills` vec is saved separately via `upsert_skill_stat`.
#[allow(dead_code)] // called by `evolution_stats` mutators
pub fn save_evolution_state(db: &HermesDb, state: &EvolutionState) -> Result<(), String> {
    set_meta(db, "total_scans", &state.total_scans.to_string())?;
    set_meta(db, "total_sent", &state.total_sent.to_string())?;
    set_meta(db, "total_failed", &state.total_failed.to_string())?;
    set_meta(db, "auto_evolve", if state.auto_evolve { "1" } else { "0" })?;
    set_meta(db, "last_updated_ms", &state.last_updated_ms.to_string())?;
    Ok(())
}

// === Trajectory step persistence ========================================

/// Append a single `hermes_trajectory_steps` row.
#[allow(dead_code)] // called by `TrajectoryStore::append`
pub fn insert_trajectory_step(db: &HermesDb, step: &TrajectoryStep) -> Result<(), String> {
    let conn = db.conn();
    let payload_json = serde_json::to_string(&step.payload)
        .map_err(|e| format!("serialize trajectory payload: {}", e))?;
    let ts = step.ts.to_rfc3339();
    conn.execute(
        r#"INSERT OR REPLACE INTO hermes_trajectory_steps
            (id, session_id, step, kind, payload_json, ts)
           VALUES (?1, ?2, ?3, ?4, ?5, ?6)"#,
        params![step.id, step.session_id, step.step, step.kind, payload_json, ts],
    )
    .map_err(|e| format!("insert hermes_trajectory_steps: {}", e))?;
    Ok(())
}

/// Return every `hermes_trajectory_steps` row for a session, ordered
/// by `step` ascending.
#[allow(dead_code)] // called by `TrajectoryStore::list`
pub fn list_trajectory_steps(db: &HermesDb, session_id: &str) -> Result<Vec<TrajectoryStep>, String> {
    let conn = db.conn();
    let mut stmt = conn
        .prepare(
            r#"SELECT id, session_id, step, kind, payload_json, ts
               FROM hermes_trajectory_steps
               WHERE session_id = ?1
               ORDER BY step ASC"#,
        )
        .map_err(|e| format!("prepare list hermes_trajectory_steps: {}", e))?;
    let rows = stmt
        .query_map(params![session_id], |row| {
            let payload_json: String = row.get(4)?;
            let ts_str: String = row.get(5)?;
            let payload: serde_json::Value = serde_json::from_str(&payload_json)
                .unwrap_or(serde_json::Value::Null);
            let ts = chrono::DateTime::parse_from_rfc3339(&ts_str)
                .map(|d| d.with_timezone(&chrono::Utc))
                .unwrap_or_else(|_| chrono::Utc::now());
            Ok(TrajectoryStep {
                id: row.get(0)?,
                session_id: row.get(1)?,
                step: row.get(2)?,
                kind: row.get(3)?,
                payload,
                ts,
            })
        })
        .map_err(|e| format!("query list hermes_trajectory_steps: {}", e))?;
    let mut out = Vec::new();
    for r in rows {
        out.push(r.map_err(|e| format!("read hermes_trajectory_steps: {}", e))?);
    }
    Ok(out)
}

/// Delete every `hermes_trajectory_steps` row for a session.
#[allow(dead_code)] // called by `TrajectoryStore::clear`
pub fn clear_trajectory_steps(db: &HermesDb, session_id: &str) -> Result<(), String> {
    let conn = db.conn();
    conn.execute(
        "DELETE FROM hermes_trajectory_steps WHERE session_id = ?1",
        params![session_id],
    )
    .map_err(|e| format!("clear hermes_trajectory_steps: {}", e))?;
    Ok(())
}

/// Total row count across every session.
#[allow(dead_code)] // called by `TrajectoryStore::total`
pub fn count_trajectory_steps(db: &HermesDb) -> Result<usize, String> {
    let conn = db.conn();
    let n: i64 = conn
        .query_row("SELECT COUNT(*) FROM hermes_trajectory_steps", [], |r| r.get(0))
        .map_err(|e| format!("count hermes_trajectory_steps: {}", e))?;
    Ok(n.max(0) as usize)
}

// === Profile / persona persistence ======================================

/// Persist a profile JSON blob. The encrypted-file path is owned by
/// `ProfileStore`; this table is the sqlite mirror (used by the
/// DDL block above so the schema exists even if the encrypted file
/// is the primary store).
#[allow(dead_code)] // reserved for future sqlite-backed profile mirror
pub fn upsert_profile_row(db: &HermesDb, id: &str, json: &str, updated_at: &str) -> Result<(), String> {
    let conn = db.conn();
    conn.execute(
        r#"INSERT INTO hermes_profiles (id, profile_json, updated_at)
           VALUES (?1, ?2, ?3)
           ON CONFLICT(id) DO UPDATE SET
             profile_json = excluded.profile_json,
             updated_at   = excluded.updated_at"#,
        params![id, json, updated_at],
    )
    .map_err(|e| format!("upsert hermes_profiles: {}", e))?;
    Ok(())
}

/// Persist a persona JSON blob.
#[allow(dead_code)] // reserved for future sqlite-backed persona mirror
pub fn upsert_persona_row(db: &HermesDb, id: &str, json: &str) -> Result<(), String> {
    let conn = db.conn();
    conn.execute(
        r#"INSERT INTO hermes_personas (id, persona_json)
           VALUES (?1, ?2)
           ON CONFLICT(id) DO UPDATE SET persona_json = excluded.persona_json"#,
        params![id, json],
    )
    .map_err(|e| format!("upsert hermes_personas: {}", e))?;
    Ok(())
}

/// Delete a persona row.
#[allow(dead_code)] // reserved for future sqlite-backed persona mirror
pub fn delete_persona_row(db: &HermesDb, id: &str) -> Result<bool, String> {
    let conn = db.conn();
    let n = conn
        .execute("DELETE FROM hermes_personas WHERE id = ?1", params![id])
        .map_err(|e| format!("delete hermes_personas: {}", e))?;
    Ok(n > 0)
}

/// Set the active persona id (single-row table, id=0).
#[allow(dead_code)] // reserved for future sqlite-backed persona mirror
pub fn set_active_persona(db: &HermesDb, persona_id: Option<&str>) -> Result<(), String> {
    let conn = db.conn();
    conn.execute(
        "INSERT INTO hermes_active_persona (id, persona_id) VALUES (0, ?1)
         ON CONFLICT(id) DO UPDATE SET persona_id = excluded.persona_id",
        params![persona_id],
    )
    .map_err(|e| format!("set hermes_active_persona: {}", e))?;
    Ok(())
}

// === Evolution signal persistence (Track A) ============================

/// `hermes_evolution_signals` 行的平面视图 (列展开)。`evidence_json` 保存
/// 整条 `EvolutionSignal` 的 JSON 序列化结果, 调用方可按需反解。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StoredSignal {
    pub signal_id: String,
    /// 序列化 tag (`sessionInsight` / `telemetry` / `memoryLinked` / `mergeCandidate`)。
    pub signal_kind: String,
    pub source_kind: String,
    pub session_id: Option<String>,
    pub skill_id: Option<String>,
    pub skill_kind: String,
    /// `SessionSignalType::as_str()` 值; 非 SessionInsight 信号为 None。
    pub signal_type: Option<String>,
    pub evidence_json: String,
    pub suggested_action: Option<String>,
    pub confidence: f32,
    pub consumed: i32,
    pub created_at: String,
}

/// `hermes_session_analysis_runs` 行。一次 `SessionAnalyzer::analyze_window`
/// 执行的审计记录。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AnalysisRunRow {
    pub run_id: String,
    pub started_at: String,
    pub finished_at: String,
    pub sessions_scanned: i64,
    pub signals_emitted: i64,
    pub llm_tokens_used: Option<i64>,
    pub degraded: bool,
    pub summary: Option<String>,
}

#[allow(dead_code)] // called by Track B orchestrator (passes &Arc<HermesDb>, derefs to &HermesDb)
impl HermesDb {
    /// 插入一条进化信号。INSERT OR IGNORE 按 signal_id 去重 (同一信号重复写入安全)。
    /// 整条信号序列化到 `evidence_json` 列。
    pub fn insert_evolution_signal(&self, sig: &EvolutionSignal) -> Result<(), String> {
        let signal_id = sig.signal_id().to_string();
        let source_kind = sig.source().as_str().to_string();
        let skill_id = sig.skill_id().map(|s| s.to_string());
        let skill_kind = sig.skill_kind().as_str().to_string();
        let evidence_json = serde_json::to_string(sig)
            .map_err(|e| format!("serialize evolution signal: {}", e))?;

        // 变体特有列: SessionInsight 才有 session_id / signal_type / suggested_action / confidence
        let (signal_kind, session_id, signal_type, suggested_action, confidence) = match sig {
            EvolutionSignal::SessionInsight {
                session_id,
                signal_type,
                suggested_action,
                confidence,
                ..
            } => (
                "sessionInsight",
                Some(session_id.clone()),
                Some(signal_type.as_str().to_string()),
                Some(suggested_action.clone()),
                *confidence,
            ),
            EvolutionSignal::Telemetry { .. } => ("telemetry", None, None, None, 0.0),
            EvolutionSignal::MemoryLinked { .. } => ("memoryLinked", None, None, None, 0.0),
            EvolutionSignal::MergeCandidate { .. } => ("mergeCandidate", None, None, None, 0.0),
        };

        let created_at = chrono::Utc::now().to_rfc3339();
        let conn = self.conn();
        conn.execute(
            r#"INSERT OR IGNORE INTO hermes_evolution_signals
                (signal_id, signal_kind, source_kind, session_id, skill_id, skill_kind,
                 signal_type, evidence_json, suggested_action, confidence, consumed, created_at)
               VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, 0, ?11)"#,
            params![
                signal_id,
                signal_kind,
                source_kind,
                session_id,
                skill_id,
                skill_kind,
                signal_type,
                evidence_json,
                suggested_action,
                confidence,
                created_at,
            ],
        )
        .map_err(|e| format!("insert hermes_evolution_signals: {}", e))?;
        Ok(())
    }

    /// 列出未消费 (`consumed=0`) 的信号, 按 created_at 降序, 限制 limit 条。
    pub fn list_pending_signals(&self, limit: u32) -> Result<Vec<StoredSignal>, String> {
        let conn = self.conn();
        let mut stmt = conn
            .prepare(
                r#"SELECT signal_id, signal_kind, source_kind, session_id, skill_id, skill_kind,
                          signal_type, evidence_json, suggested_action, confidence, consumed, created_at
                   FROM hermes_evolution_signals
                   WHERE consumed = 0
                   ORDER BY created_at DESC
                   LIMIT ?1"#,
            )
            .map_err(|e| format!("prepare list_pending_signals: {}", e))?;
        let rows = stmt
            .query_map(params![limit as i64], |row| {
                Ok(StoredSignal {
                    signal_id: row.get(0)?,
                    signal_kind: row.get(1)?,
                    source_kind: row.get(2)?,
                    session_id: row.get(3)?,
                    skill_id: row.get(4)?,
                    skill_kind: row.get(5)?,
                    signal_type: row.get(6)?,
                    evidence_json: row.get(7)?,
                    suggested_action: row.get(8)?,
                    confidence: row.get(9)?,
                    consumed: row.get(10)?,
                    created_at: row.get(11)?,
                })
            })
            .map_err(|e| format!("query list_pending_signals: {}", e))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| format!("read pending signal: {}", e))?);
        }
        Ok(out)
    }

    /// 标记信号消费状态 (`consumed=1` 表示已被 ProposalRouter 处理; 0 还原)。
    pub fn mark_signal_consumed(&self, signal_id: &str, consumed: i32) -> Result<(), String> {
        let conn = self.conn();
        conn.execute(
            "UPDATE hermes_evolution_signals SET consumed = ?1 WHERE signal_id = ?2",
            params![consumed, signal_id],
        )
        .map_err(|e| format!("mark_signal_consumed: {}", e))?;
        Ok(())
    }

    /// 记录一次 analyze_window 执行 (审计)。INSERT OR REPLACE 按 run_id。
    pub fn insert_analysis_run(&self, run: &AnalysisRunRow) -> Result<(), String> {
        let conn = self.conn();
        conn.execute(
            r#"INSERT OR REPLACE INTO hermes_session_analysis_runs
                (run_id, started_at, finished_at, sessions_scanned, signals_emitted,
                 llm_tokens_used, degraded, summary)
               VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)"#,
            params![
                run.run_id,
                run.started_at,
                run.finished_at,
                run.sessions_scanned,
                run.signals_emitted,
                run.llm_tokens_used,
                run.degraded as i32,
                run.summary,
            ],
        )
        .map_err(|e| format!("insert hermes_session_analysis_runs: {}", e))?;
        Ok(())
    }

    /// 列出最近的 analyze_window 执行记录, 按 started_at 降序, 限制 limit 条。
    pub fn list_recent_analysis_runs(&self, limit: u32) -> Result<Vec<AnalysisRunRow>, String> {
        let conn = self.conn();
        let mut stmt = conn
            .prepare(
                r#"SELECT run_id, started_at, finished_at, sessions_scanned, signals_emitted,
                          llm_tokens_used, degraded, summary
                   FROM hermes_session_analysis_runs
                   ORDER BY started_at DESC
                   LIMIT ?1"#,
            )
            .map_err(|e| format!("prepare list_recent_analysis_runs: {}", e))?;
        let rows = stmt
            .query_map(params![limit as i64], |row| {
                let degraded: i64 = row.get(6)?;
                Ok(AnalysisRunRow {
                    run_id: row.get(0)?,
                    started_at: row.get(1)?,
                    finished_at: row.get(2)?,
                    sessions_scanned: row.get(3)?,
                    signals_emitted: row.get(4)?,
                    llm_tokens_used: row.get(5)?,
                    degraded: degraded != 0,
                    summary: row.get(7)?,
                })
            })
            .map_err(|e| format!("query list_recent_analysis_runs: {}", e))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| format!("read analysis run: {}", e))?);
        }
        Ok(out)
    }
}

// === Unit tests =========================================================

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    fn open_tmp() -> (tempfile::TempDir, HermesDb) {
        let dir = tempdir().expect("tempdir");
        let db = HermesDb::open_at(dir.path().join("hermes.db")).expect("open");
        (dir, db)
    }

    fn sample_memory(id: &str) -> MemoryEntry {
        MemoryEntry {
            id: id.to_string(),
            summary: format!("summary-{}", id),
            content: format!("content-{}", id),
            source: Some("test".to_string()),
            created_at: "2026-07-02T00:00:00.000Z".to_string(),
            updated_at: "2026-07-02T00:00:00.000Z".to_string(),
            importance: "warm".to_string(),
            access_count: 0,
            last_accessed_at: None,
            workspace_path: Some("/tmp".to_string()),
            ..Default::default()
        }
    }

    #[test]
    fn memory_round_trips() {
        let (_dir, db) = open_tmp();
        upsert_memory(&db, &sample_memory("m1")).expect("insert");
        let got = get_memory(&db, "m1").expect("get").expect("present");
        assert_eq!(got.summary, "summary-m1");
        let list = list_memories(&db).expect("list");
        assert_eq!(list.len(), 1);
        assert!(delete_memory(&db, "m1").expect("delete"));
        assert!(get_memory(&db, "m1").expect("get").is_none());
    }

    #[test]
    fn evolution_meta_round_trips() {
        let (_dir, db) = open_tmp();
        set_meta(&db, "auto_evolve", "1").expect("set");
        assert_eq!(get_meta(&db, "auto_evolve").expect("get").as_deref(), Some("1"));
        set_meta(&db, "auto_evolve", "0").expect("set");
        assert_eq!(get_meta(&db, "auto_evolve").expect("get").as_deref(), Some("0"));
    }

    #[test]
    fn skill_stat_round_trips() {
        let (_dir, db) = open_tmp();
        let s = SkillStat {
            skill_id: "sk1".to_string(),
            name: "Demo".to_string(),
            runs: 5,
            sent: 3,
            failed: 2,
            success_rate: Some(0.6),
            consecutive_failures: 1,
            last_run_ms: 1000,
            last_success_ms: 900,
            last_failure_ms: 1100,
            status: SkillStatus::Active,
            circuit_open_until_ms: 0,
        };
        upsert_skill_stat(&db, &s).expect("upsert");
        let list = list_skill_stats(&db).expect("list");
        assert_eq!(list.len(), 1);
        assert_eq!(list[0].skill_id, "sk1");
        assert_eq!(list[0].runs, 5);
        clear_skill_stats(&db).expect("clear");
        assert!(list_skill_stats(&db).expect("list").is_empty());
    }

    #[test]
    fn trajectory_steps_round_trip() {
        let (_dir, db) = open_tmp();
        let step = TrajectoryStep {
            id: "t1".to_string(),
            session_id: "s1".to_string(),
            step: 1,
            kind: "tool_call".to_string(),
            payload: serde_json::json!({"tool": "bash"}),
            ts: chrono::Utc::now(),
        };
        insert_trajectory_step(&db, &step).expect("insert");
        let list = list_trajectory_steps(&db, "s1").expect("list");
        assert_eq!(list.len(), 1);
        assert_eq!(list[0].kind, "tool_call");
        assert_eq!(count_trajectory_steps(&db).expect("count"), 1);
        clear_trajectory_steps(&db, "s1").expect("clear");
        assert!(list_trajectory_steps(&db, "s1").expect("list").is_empty());
    }

    #[test]
    fn evolution_signal_round_trips() {
        use crate::hermes::evolution_signal::{
            EvolutionSignal, SessionSignalType, SkillKind,
        };
        let (_dir, db) = open_tmp();
        let sig = EvolutionSignal::SessionInsight {
            signal_id: "sig_test_1".to_string(),
            session_id: "sess_1".to_string(),
            skill_id: Some("open-notepad".to_string()),
            skill_kind: SkillKind::Mcp,
            signal_type: SessionSignalType::FrequentCorrection,
            evidence: vec!["用户说: 再加个延迟".to_string()],
            suggested_action: "在 step 2 后增加 500ms Wait".to_string(),
            confidence: 0.8,
        };
        db.insert_evolution_signal(&sig).expect("insert");
        // 重复插入 (同 signal_id) 应被 INSERT OR IGNORE 静默吞掉
        db.insert_evolution_signal(&sig).expect("re-insert ignored");

        let pending = db.list_pending_signals(10).expect("list");
        assert_eq!(pending.len(), 1);
        let row = &pending[0];
        assert_eq!(row.signal_id, "sig_test_1");
        assert_eq!(row.signal_kind, "sessionInsight");
        assert_eq!(row.session_id.as_deref(), Some("sess_1"));
        assert_eq!(row.skill_id.as_deref(), Some("open-notepad"));
        assert_eq!(row.skill_kind, "mcp");
        assert_eq!(row.signal_type.as_deref(), Some("frequent_correction"));
        assert!((row.confidence - 0.8).abs() < 1e-6);
        assert_eq!(row.consumed, 0);
        assert!(row.evidence_json.contains("\"kind\":\"sessionInsight\""));

        // mark consumed → 不再出现在 pending 列表
        db.mark_signal_consumed("sig_test_1", 1).expect("mark");
        let pending2 = db.list_pending_signals(10).expect("list");
        assert!(pending2.is_empty(), "consumed signal must not be pending");
    }

    #[test]
    fn analysis_run_round_trips() {
        let (_dir, db) = open_tmp();
        let run = AnalysisRunRow {
            run_id: "run_1".to_string(),
            started_at: "2026-07-23T00:00:00+00:00".to_string(),
            finished_at: "2026-07-23T00:00:05+00:00".to_string(),
            sessions_scanned: 12,
            signals_emitted: 3,
            llm_tokens_used: Some(2048),
            degraded: false,
            summary: Some("normal run".to_string()),
        };
        db.insert_analysis_run(&run).expect("insert");
        let runs = db.list_recent_analysis_runs(10).expect("list");
        assert_eq!(runs.len(), 1);
        assert_eq!(runs[0].run_id, "run_1");
        assert_eq!(runs[0].sessions_scanned, 12);
        assert_eq!(runs[0].signals_emitted, 3);
        assert_eq!(runs[0].llm_tokens_used, Some(2048));
        assert!(!runs[0].degraded);

        // 降级路径: degraded=true
        let run2 = AnalysisRunRow {
            run_id: "run_2".to_string(),
            started_at: "2026-07-23T01:00:00+00:00".to_string(),
            finished_at: "2026-07-23T01:00:02+00:00".to_string(),
            sessions_scanned: 5,
            signals_emitted: 1,
            llm_tokens_used: None,
            degraded: true,
            summary: None,
        };
        db.insert_analysis_run(&run2).expect("insert2");
        let runs2 = db.list_recent_analysis_runs(10).expect("list2");
        assert_eq!(runs2.len(), 2);
        assert!(runs2[0].degraded, "run_2 should be degraded");
    }
}
