// Copyright (c) 2026 AIMarketing
//
// SkillMemory
//
// Persistent layer for the client-side skill family-tree, run history
// and adoption rate. Backed by the same `tupai.db` that already hosts
// `memories` and `tasks` (see `commands::types::open_app_db`).
//
// All DDL lives in `schema/app.sql` and is
// re-applied on every `SkillDb::open`. We deliberately do **not**
// depend on `commands::open_app_db` — that one returns a fresh
// short-lived `Connection` and is unsuitable for the long-lived
// state this module needs.

use std::path::PathBuf;
use std::sync::Mutex;

use chrono::{DateTime, Utc};
use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Manager};

// === Schema bootstrap ===================================================

/// Inline copy of the schema DDL block. Kept in sync with the tail
/// of `schema/app.sql` — see that file for the authoritative source.
///
/// We embed a substring here so unit tests (which build a temp
/// sqlite file) can spin up the schema without touching the project
/// tree, and so production `init_skill_db` can safely retry
/// `execute_batch` even if the host database is older than the
/// current `app.sql`.
#[allow(dead_code)] // DDL bootstrap; applied by `SkillDb::open_at` below
const SKILL_DDL: &str = r#"
CREATE TABLE IF NOT EXISTS skill_versions (
  skill_id        TEXT NOT NULL,
  version         INTEGER NOT NULL,
  parent_skill_id TEXT,
  parent_version  INTEGER,
  source          TEXT NOT NULL,
  skill_md        TEXT NOT NULL,
  created_at      TEXT NOT NULL,
  state           TEXT NOT NULL,
  PRIMARY KEY (skill_id, version)
);
CREATE INDEX IF NOT EXISTS idx_skill_versions_state ON skill_versions(state, created_at);
CREATE INDEX IF NOT EXISTS idx_skill_versions_parent ON skill_versions(parent_skill_id, parent_version);

CREATE TABLE IF NOT EXISTS skill_runs (
  run_id      TEXT PRIMARY KEY,
  skill_id    TEXT NOT NULL,
  version     INTEGER NOT NULL,
  success     INTEGER NOT NULL,
  latency_ms  INTEGER,
  error_kind  TEXT,
  error_msg   TEXT,
  ran_at      TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_runs_skill ON skill_runs(skill_id, version, ran_at);
CREATE INDEX IF NOT EXISTS idx_runs_error ON skill_runs(error_kind, ran_at);

CREATE TABLE IF NOT EXISTS skill_evaluations (
  eval_id        TEXT PRIMARY KEY,
  proposal_id    TEXT NOT NULL,
  skill_id       TEXT NOT NULL,
  version        INTEGER NOT NULL,
  total_score    REAL NOT NULL,
  safety_score   REAL,
  success_score  REAL,
  gen_score      REAL,
  dedup_score    REAL,
  cost_score     REAL,
  verdict        TEXT NOT NULL,
  issues_json    TEXT,
  degraded       INTEGER NOT NULL DEFAULT 0,
  evaluated_at   TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_eval_skill ON skill_evaluations(skill_id, version, evaluated_at);
CREATE INDEX IF NOT EXISTS idx_eval_verdict ON skill_evaluations(verdict, evaluated_at);

CREATE TABLE IF NOT EXISTS skill_lineage (
  child_skill_id    TEXT NOT NULL,
  child_version     INTEGER NOT NULL,
  parent_skill_id   TEXT NOT NULL,
  parent_version    INTEGER NOT NULL,
  relation          TEXT NOT NULL,
  PRIMARY KEY (child_skill_id, child_version, parent_skill_id, parent_version)
);

CREATE VIRTUAL TABLE IF NOT EXISTS skill_fts USING fts5(
  skill_id UNINDEXED,
  version UNINDEXED,
  skill_md,
  tokenize = 'unicode61 remove_diacritics 2'
);
"#;

// === Domain types =======================================================

/// A persisted skill version row. The pair `(skill_id, version)` is
/// the natural primary key; the `parent_*` columns encode the
/// family-tree edge that v4 §2.4 needs to render in the lineage UI.
#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
#[allow(dead_code)] // populated by `save_skill_version` below
pub struct SkillVersion {
    pub skill_id: String,
    pub version: u32,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub parent_skill_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub parent_version: Option<u32>,
    pub source: String,
    pub skill_md: String,
    pub created_at: String,
    pub state: String,
}

/// One row of `skill_runs` — recorded after every skill execution so
/// the daily evolution job and the evaluation job
/// can compute rolling success-rate, latency and error histograms.
#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
#[allow(dead_code)] // populated by `save_run` below
pub struct SkillRun {
    pub run_id: String,
    pub skill_id: String,
    pub version: u32,
    pub success: bool,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub latency_ms: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub error_kind: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub error_msg: Option<String>,
    pub ran_at: String,
}

/// A single server-side evaluation outcome. `issues_json` carries the
/// serialised `Vec<EvalIssue>` returned by the 8642 evaluator.
#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
#[allow(dead_code)] // skill memory evaluation record; populated by `save_evaluation` below
pub struct SkillEvaluationRecord {
    pub eval_id: String,
    pub proposal_id: String,
    pub skill_id: String,
    pub version: u32,
    pub total_score: f32,
    pub safety_score: Option<f32>,
    pub success_score: Option<f32>,
    pub gen_score: Option<f32>,
    pub dedup_score: Option<f32>,
    pub cost_score: Option<f32>,
    pub verdict: String,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub issues_json: Option<String>,
    pub degraded: bool,
    pub evaluated_at: String,
}

/// One edge in the skill family-tree. Returned by `get_lineage`.
#[derive(Serialize, Deserialize, Clone, Debug, Default)]
#[serde(rename_all = "camelCase")]
pub struct LineageEdge {
    pub parent_skill_id: String,
    pub parent_version: u32,
    pub child_skill_id: String,
    pub child_version: u32,
    pub relation: String,
}

/// Aggregated run statistics for a single (skill_id, version) pair
/// over a caller-defined time window. Used by the inbox / metrics UI.
#[derive(Serialize, Deserialize, Clone, Debug, Default)]
#[serde(rename_all = "camelCase")]
pub struct RunStats {
    pub total: u32,
    pub success: u32,
    pub avg_latency_ms: u32,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub top_error_kind: Option<String>,
}

// === State ==============================================================

/// Long-lived Tauri-managed state. The inner `Mutex<Connection>` is
/// shared by every command in `commands::memory` so we avoid opening
/// a brand-new sqlite handle on every IPC call.
#[allow(dead_code)] // registered by `init_skill_db` in `lib.rs::setup`
pub struct SkillDb {
    conn: Mutex<Connection>,
}

#[allow(dead_code)] // Tauri-managed `SkillDb` constructors; reached via `init_skill_db` and tests
impl SkillDb {
    /// Open (or create) the skill-memory database at
    /// `<app_data_dir>/tupai.db` and apply the schema DDL block.
    /// Failures are surfaced as plain `String` so the Tauri command
    /// layer can return them verbatim to the renderer.
    pub fn open(app: &AppHandle) -> Result<Self, String> {
        let path = skill_db_path(app)?;
        Self::open_at(path)
    }

    /// Open at a specific path. Public for unit tests so they can
    /// point at a `tempfile::tempdir()` instead of the real app
    /// data dir.
    pub fn open_at(path: PathBuf) -> Result<Self, String> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)
                .map_err(|e| format!("Failed to create db parent {:?}: {}", parent, e))?;
        }
        let conn = Connection::open(&path)
            .map_err(|e| format!("Failed to open skill db {:?}: {}", path, e))?;
        conn.execute_batch(
            "PRAGMA journal_mode = WAL;\nPRAGMA foreign_keys = ON;\nPRAGMA busy_timeout = 5000;\n",
        )
        .map_err(|e| format!("Failed to apply pragmas: {}", e))?;
        conn.execute_batch(SKILL_DDL)
            .map_err(|e| format!("Failed to apply skill DDL: {}", e))?;
        Ok(Self { conn: Mutex::new(conn) })
    }

    /// Acquire the inner connection. Recovers from a poisoned mutex
    /// by discarding the poisoned data (logging an error) so the
    /// process can continue.
    pub fn conn(&self) -> std::sync::MutexGuard<'_, Connection> {
        self.conn.lock().unwrap_or_else(|e| {
            log::error!("SkillDb mutex poisoned, recovering");
            e.into_inner()
        })
    }
}

/// Resolve the on-disk path. Centralised so tests and prod agree.
#[allow(dead_code)] // helper for `SkillDb::open` above
fn skill_db_path(app: &AppHandle) -> Result<PathBuf, String> {
    let dir = app
        .path()
        .app_data_dir()
        .map_err(|e| format!("Failed to get app_data_dir: {}", e))?;
    std::fs::create_dir_all(&dir)
        .map_err(|e| format!("Failed to create app data dir {:?}: {}", dir, e))?;
    Ok(dir.join("tupai.db"))
}

/// Register a freshly opened `SkillDb` against the running Tauri
/// app. The main thread is expected to call this from `lib.rs`
/// `setup()` after `commands::open_app_db`; calling it twice for the
/// same handle is harmless (Tauri's `manage` is keyed on type, but
/// a double-register is a programmer error and we log a warning).
#[allow(dead_code)] // invoked from `lib.rs::setup`; skill memory boot path
pub fn init_skill_db(app: &AppHandle) -> Result<(), String> {
    let db = SkillDb::open(app)?;
    if app.try_state::<SkillDb>().is_some() {
        log::warn!("[skill/memory] SkillDb is already registered; replacing");
    }
    app.manage(db);
    Ok(())
}

// === CRUD =================================================================

/// Upsert a `skill_versions` row and keep the FTS5 mirror in sync.
/// We do the FTS write inside the same critical section as the table
/// upsert so a reader never sees a row that isn't searchable.
#[allow(dead_code)] // skill persistence; called from evolution job
pub fn save_skill_version(state: &SkillDb, v: &SkillVersion) -> Result<(), String> {
    let conn = state.conn();
    let tx = conn
        .unchecked_transaction()
        .map_err(|e| format!("tx begin: {}", e))?;
    tx.execute(
        r#"INSERT INTO skill_versions
            (skill_id, version, parent_skill_id, parent_version,
             source, skill_md, created_at, state)
           VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
           ON CONFLICT(skill_id, version) DO UPDATE SET
             parent_skill_id = excluded.parent_skill_id,
             parent_version  = excluded.parent_version,
             source          = excluded.source,
             skill_md        = excluded.skill_md,
             created_at      = excluded.created_at,
             state           = excluded.state"#,
        params![
            v.skill_id,
            v.version,
            v.parent_skill_id,
            v.parent_version,
            v.source,
            v.skill_md,
            v.created_at,
            v.state,
        ],
    )
    .map_err(|e| format!("upsert skill_versions: {}", e))?;

    // Mirror the new content into the FTS5 table. Use a delete-then-
    // insert so a re-saved version (the ON CONFLICT path) doesn't
    // leave stale tokens.
    tx.execute(
        "DELETE FROM skill_fts WHERE skill_id = ?1 AND version = ?2",
        params![v.skill_id, v.version],
    )
    .map_err(|e| format!("fts delete: {}", e))?;
    tx.execute(
        "INSERT INTO skill_fts (skill_id, version, skill_md) VALUES (?1, ?2, ?3)",
        params![v.skill_id, v.version, v.skill_md],
    )
    .map_err(|e| format!("fts insert: {}", e))?;

    tx.commit().map_err(|e| format!("tx commit: {}", e))?;
    Ok(())
}

/// Delete every `skill_versions` row for a given `skill_id` together
/// with its FTS5 mirror rows. Used by `delete_optimized_skill` so the
/// deleted skill stops showing up in `search_skills` (FTS) and
/// `get_lineage` / `get_run_stats` (versions table).
///
/// FTS rows are deleted first; if that step fails we log a warning but
/// still attempt the versions-table delete so the caller's intent
/// (remove the skill) is best-effort honoured. Both deletes run inside
/// one transaction so a partial failure rolls back.
#[allow(dead_code)] // called from commands::skill::delete_optimized_skill
pub fn delete_skill_versions(state: &SkillDb, skill_id: &str) -> Result<(), String> {
    let conn = state.conn();
    let tx = conn
        .unchecked_transaction()
        .map_err(|e| format!("tx begin: {}", e))?;
    // FTS5 虚拟表没有主键约束, 按 skill_id 删全部镜像行。
    if let Err(e) = tx.execute(
        "DELETE FROM skill_fts WHERE skill_id = ?1",
        params![skill_id],
    ) {
        // FTS 删除失败不阻断主表删除 —— 主表删了 FTS 残留 token 顶多
        // 让 search_skills 多返回一个空 body 命中, 不会崩。
        log::warn!(
            "[skill/memory] FTS delete for {} failed (continuing): {}",
            skill_id,
            e
        );
    }
    tx.execute(
        "DELETE FROM skill_versions WHERE skill_id = ?1",
        params![skill_id],
    )
    .map_err(|e| format!("delete skill_versions: {}", e))?;
    tx.commit().map_err(|e| format!("tx commit: {}", e))?;
    Ok(())
}

/// Return the next version number for `skill_id`: `MAX(version) + 1`
/// over `skill_versions`, or `1` if the skill has no rows yet. Used
/// by `save_optimized_skill` so repeated saves produce a monotonically
/// increasing version that stays in sync with the `adopt` path (which
/// bumps `version_counter` in the registry).
#[allow(dead_code)] // called from commands::skill::save_optimized_skill
pub fn next_skill_version(state: &SkillDb, skill_id: &str) -> Result<u32, String> {
    let conn = state.conn();
    let max: i64 = conn
        .query_row(
            "SELECT COALESCE(MAX(version), 0) FROM skill_versions WHERE skill_id = ?1",
            params![skill_id],
            |row| row.get(0),
        )
        .map_err(|e| format!("query max version: {}", e))?;
    Ok(max.max(0) as u32 + 1)
}

/// Append a single `skill_runs` row. The `run_id` should be a ULID
/// minted by the caller (we don't generate one here so the
/// automation engine can keep a parallel in-memory record without a
/// second source of truth).
#[allow(dead_code)] // called by automation engine after every run
pub fn save_run(state: &SkillDb, run: &SkillRun) -> Result<(), String> {
    let conn = state.conn();
    conn.execute(
        r#"INSERT OR REPLACE INTO skill_runs
            (run_id, skill_id, version, success, latency_ms,
             error_kind, error_msg, ran_at)
           VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)"#,
        params![
            run.run_id,
            run.skill_id,
            run.version,
            run.success as i32,
            run.latency_ms,
            run.error_kind,
            run.error_msg,
            run.ran_at,
        ],
    )
    .map_err(|e| format!("insert skill_runs: {}", e))?;
    Ok(())
}

/// Persist the server's evaluation verdict.
#[allow(dead_code)] // called after every server evaluation
pub fn save_evaluation(
    state: &SkillDb,
    ev: &SkillEvaluationRecord,
) -> Result<(), String> {
    let conn = state.conn();
    conn.execute(
        r#"INSERT OR REPLACE INTO skill_evaluations
            (eval_id, proposal_id, skill_id, version, total_score,
             safety_score, success_score, gen_score, dedup_score,
             cost_score, verdict, issues_json, degraded, evaluated_at)
           VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10,
                   ?11, ?12, ?13, ?14)"#,
        params![
            ev.eval_id,
            ev.proposal_id,
            ev.skill_id,
            ev.version,
            ev.total_score,
            ev.safety_score,
            ev.success_score,
            ev.gen_score,
            ev.dedup_score,
            ev.cost_score,
            ev.verdict,
            ev.issues_json,
            ev.degraded as i32,
            ev.evaluated_at,
        ],
    )
    .map_err(|e| format!("insert skill_evaluations: {}", e))?;
    Ok(())
}

/// Insert a `skill_lineage` edge. Duplicate edges are silently
/// ignored (PRIMARY KEY) so a re-evaluation round doesn't error.
#[allow(dead_code)] // called when a new version is adopted
pub fn link_lineage(
    state: &SkillDb,
    child: &str,
    child_v: u32,
    parent: &str,
    parent_v: u32,
    rel: &str,
) -> Result<(), String> {
    let conn = state.conn();
    conn.execute(
        r#"INSERT OR IGNORE INTO skill_lineage
            (child_skill_id, child_version, parent_skill_id,
             parent_version, relation)
           VALUES (?1, ?2, ?3, ?4, ?5)"#,
        params![child, child_v, parent, parent_v, rel],
    )
    .map_err(|e| format!("insert skill_lineage: {}", e))?;
    Ok(())
}

// === Queries =============================================================

/// Return every lineage edge that has the given `(skill_id, version)`
/// on either end. We union both directions so the UI can render a
/// full ancestor/descendant fan-out in one round trip.
#[allow(dead_code)] // wired into IPC by `commands::memory::get_lineage`
pub fn get_lineage(
    state: &SkillDb,
    skill_id: &str,
    version: u32,
) -> Result<Vec<LineageEdge>, String> {
    let conn = state.conn();
    let mut stmt = conn
        .prepare(
            r#"SELECT child_skill_id, child_version,
                      parent_skill_id, parent_version, relation
               FROM skill_lineage
              WHERE (child_skill_id  = ?1 AND child_version  = ?2)
                 OR (parent_skill_id = ?1 AND parent_version = ?2)"#,
        )
        .map_err(|e| format!("prepare lineage: {}", e))?;
    let rows = stmt
        .query_map(params![skill_id, version], |row| {
            Ok(LineageEdge {
                child_skill_id: row.get(0)?,
                child_version: row.get(1)?,
                parent_skill_id: row.get(2)?,
                parent_version: row.get(3)?,
                relation: row.get(4)?,
            })
        })
        .map_err(|e| format!("query lineage: {}", e))?;
    let mut out = Vec::new();
    for r in rows {
        out.push(r.map_err(|e| format!("read lineage: {}", e))?);
    }
    Ok(out)
}

/// Aggregate per-version run statistics since the given cutoff. When
/// `since` is `None` we use the unix epoch so the call returns the
/// full history.
#[allow(dead_code)] // wired into IPC by `commands::memory::get_run_stats`
pub fn get_run_stats(
    state: &SkillDb,
    skill_id: &str,
    version: u32,
    since: DateTime<Utc>,
) -> Result<RunStats, String> {
    let conn = state.conn();
    // We compare against the unix-epoch seconds stamp embedded in the
    // ISO-8601 `ran_at` string ("…Z" suffix). A pure-lexicographic
    // comparison would also work *as long as* every row has the same
    // number of fractional digits, which `commands::types::now_rfc3339`
    // guarantees. Numeric compare is more defensive against future
    // changes to the timestamp format.
    let since_ts = since.timestamp().to_string();

    let totals: (i64, i64, Option<f64>) = conn
        .query_row(
            r#"SELECT COUNT(*),
                      COALESCE(SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END), 0),
                      AVG(CASE WHEN success = 1 THEN latency_ms ELSE NULL END)
               FROM skill_runs
              WHERE skill_id = ?1 AND version = ?2
                AND CAST(strftime('%s', substr(ran_at, 1, 19)) AS INTEGER) >= ?3"#,
            params![skill_id, version, since_ts],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )
        .map_err(|e| format!("run stats totals: {}", e))?;

    let top_error: Option<String> = conn
        .query_row(
            r#"SELECT error_kind
               FROM skill_runs
              WHERE skill_id = ?1 AND version = ?2
                AND success = 0
                AND error_kind IS NOT NULL
                AND CAST(strftime('%s', substr(ran_at, 1, 19)) AS INTEGER) >= ?3
              GROUP BY error_kind
              ORDER BY COUNT(*) DESC, error_kind ASC
              LIMIT 1"#,
            params![skill_id, version, since_ts],
            |row| row.get::<_, String>(0),
        )
        .optional()
        .map_err(|e| format!("run stats top error: {}", e))?;

    let avg_latency = totals.2.unwrap_or(0.0).round().max(0.0) as u32;
    Ok(RunStats {
        total: totals.0.max(0) as u32,
        success: totals.1.max(0) as u32,
        avg_latency_ms: avg_latency,
        top_error_kind: top_error,
    })
}

// === Unit tests ===========================================================

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    fn open_tmp() -> (tempfile::TempDir, SkillDb) {
        let dir = tempdir().expect("tempdir");
        let db = SkillDb::open_at(dir.path().join("skill.db")).expect("open");
        (dir, db)
    }

    fn sample_version(id: &str, v: u32, parent: Option<(&str, u32)>) -> SkillVersion {
        SkillVersion {
            skill_id: id.to_string(),
            version: v,
            parent_skill_id: parent.map(|(p, _)| p.to_string()),
            parent_version: parent.map(|(_, pv)| pv),
            source: "manual".to_string(),
            skill_md: format!(
                "# {id}\nname: {id}\ndescription: 演示 skill v{v}\n"
            ),
            created_at: "2026-06-06T00:00:00.000Z".to_string(),
            state: "candidate".to_string(),
        }
    }

    #[test]
    fn save_and_read_skill_version_round_trips() {
        let (_dir, db) = open_tmp();
        save_skill_version(&db, &sample_version("导出 Excel", 1, None))
            .expect("save");
        // The FTS mirror must also be in place.
        let count: i64 = db
            .conn()
            .query_row("SELECT COUNT(*) FROM skill_fts", [], |r| r.get(0))
            .unwrap();
        assert_eq!(count, 1, "FTS mirror should hold one row");
    }

    #[test]
    fn upsert_replaces_existing_version_content() {
        let (_dir, db) = open_tmp();
        let mut v = sample_version("导出 Excel", 1, None);
        save_skill_version(&db, &v).unwrap();
        v.skill_md = "# 导出 Excel\nname: 导出 Excel\nNEW CONTENT\n".to_string();
        v.state = "running".to_string();
        save_skill_version(&db, &v).unwrap();
        let md: String = db
            .conn()
            .query_row(
                "SELECT skill_md FROM skill_versions WHERE skill_id='导出 Excel' AND version=1",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert!(md.contains("NEW CONTENT"));
        // FTS must have been refreshed — a stale token from the first
        // write should no longer be the only hit. The mirror should
        // still have exactly one row.
        let count: i64 = db
            .conn()
            .query_row("SELECT COUNT(*) FROM skill_fts", [], |r| r.get(0))
            .unwrap();
        assert_eq!(count, 1);
    }

    #[test]
    fn lineage_and_run_stats_aggregate_correctly() {
        let (_dir, db) = open_tmp();
        save_skill_version(
            &db,
            &sample_version("导出 Excel", 1, Some(("原始版本", 0))),
        )
        .unwrap();
        save_skill_version(
            &db,
            &sample_version("导出 Excel", 2, Some(("导出 Excel", 1))),
        )
        .unwrap();
        link_lineage(&db, "导出 Excel", 2, "导出 Excel", 1, "derived").unwrap();
        link_lineage(&db, "导出 Excel", 1, "原始版本", 0, "refactor").unwrap();

        // get_lineage unions both directions (parent-side and
        // child-side). v=1 is the mid-chain node: it's the child of
        // ("原始版本", 0) AND the parent of (v=2, self) → 2 edges.
        // Querying v=2 only returns the single outgoing edge.
        let edges = get_lineage(&db, "导出 Excel", 1).unwrap();
        assert_eq!(edges.len(), 2, "should see both incoming and outgoing edges");

        for i in 0..3 {
            save_run(
                &db,
                &SkillRun {
                    run_id: format!("r-{i}"),
                    skill_id: "导出 Excel".to_string(),
                    version: 2,
                    success: i != 1,
                    latency_ms: Some(100 + i as u32 * 10),
                    error_kind: if i == 1 { Some("timeout".to_string()) } else { None },
                    error_msg: None,
                    ran_at: "2026-06-06T01:00:00.000Z".to_string(),
                },
            )
            .unwrap();
        }
        let stats = get_run_stats(
            &db,
            "导出 Excel",
            2,
            DateTime::parse_from_rfc3339("2026-01-01T00:00:00Z")
                .unwrap()
                .with_timezone(&Utc),
        )
        .unwrap();
        assert_eq!(stats.total, 3);
        assert_eq!(stats.success, 2);
        assert_eq!(stats.top_error_kind.as_deref(), Some("timeout"));
        assert!(stats.avg_latency_ms >= 100);
    }

    #[test]
    fn evaluation_record_persists() {
        let (_dir, db) = open_tmp();
        let ev = SkillEvaluationRecord {
            eval_id: "e1".to_string(),
            proposal_id: "p1".to_string(),
            skill_id: "导出 Excel".to_string(),
            version: 1,
            total_score: 0.91,
            safety_score: Some(0.95),
            success_score: Some(0.90),
            gen_score: Some(0.88),
            dedup_score: Some(0.92),
            cost_score: Some(0.80),
            verdict: "accept".to_string(),
            issues_json: Some("[]".to_string()),
            degraded: false,
            evaluated_at: "2026-06-06T02:00:00.000Z".to_string(),
        };
        save_evaluation(&db, &ev).unwrap();
        let verdict: String = db
            .conn()
            .query_row(
                "SELECT verdict FROM skill_evaluations WHERE eval_id='e1'",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(verdict, "accept");
    }
}
