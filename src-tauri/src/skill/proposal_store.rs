// Copyright (c) 2026 AIMarketing
//
// SkillProposal persistence.
//
// Stores the unified `SkillProposal` rows in the existing
// `tupai.db` SQLite file (the same one `commands::legacy` uses
// for memories and tasks).  We re-use the same database so
// future `skill_runs` / `skill_evaluations` tables can
// join naturally without a second-file migration.
//
// The schema is added with `CREATE TABLE IF NOT EXISTS`, so the
// `ensure_app_schema` does not need to be modified — this
// keeps us off the main-thread reserved file list.
//
// The companion SQL that is expected to be added in
// `src-tauri/src/schema/app.sql` can mirror the columns here.

use std::path::PathBuf;

use chrono::{DateTime, Utc};
use rusqlite::{params, Connection, OptionalExtension, Row};
use tauri::Manager;

use crate::skill::proposal::{ProposalSource, SkillLineage, SkillProposal, ProposalTelemetry};

const PROPOSALS_DB_FILENAME: &str = "tupai.db";

fn proposals_db_path(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    let dir = app
        .path()
        .app_data_dir()
        .map_err(|e| format!("Failed to resolve app data dir: {}", e))?;
    std::fs::create_dir_all(&dir)
        .map_err(|e| format!("Failed to create app data dir {}: {}", dir.display(), e))?;
    Ok(dir.join(PROPOSALS_DB_FILENAME))
}

fn ensure_proposals_schema(conn: &Connection) -> Result<(), String> {
    conn.execute_batch(
        r#"
        CREATE TABLE IF NOT EXISTS skill_proposals (
            proposal_id TEXT PRIMARY KEY,
            source TEXT NOT NULL,
            skill_md TEXT NOT NULL,
            parent_skill_id TEXT,
            parent_version INTEGER,
            derivation_note TEXT,
            source_success_rate REAL NOT NULL DEFAULT 0.0,
            avg_latency_ms INTEGER NOT NULL DEFAULT 0,
            sample_size INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_proposals_source ON skill_proposals(source);
        CREATE INDEX IF NOT EXISTS idx_proposals_created_at ON skill_proposals(created_at DESC);
        "#,
    )
    .map_err(|e| format!("Failed to initialise skill_proposals schema: {}", e))?;
    Ok(())
}

/// Open the app DB and ensure the proposals schema exists.
/// Mirrors `commands::legacy::open_app_db` so we can share the
/// same `tupai.db` file without editing the legacy module.
pub fn open_proposals_db(app: &tauri::AppHandle) -> Result<Connection, String> {
    let path = proposals_db_path(app)?;
    let conn = Connection::open(&path)
        .map_err(|e| format!("Failed to open app db {}: {}", path.display(), e))?;
    ensure_proposals_schema(&conn)?;
    Ok(conn)
}

#[allow(dead_code)] // row mapper shared by `list` and `get` below
fn row_to_proposal(row: &Row<'_>) -> Result<SkillProposal, rusqlite::Error> {
    let source_str: String = row.get("source")?;
    let source = match source_str.as_str() {
        "teaching" => ProposalSource::Teaching,
        "healing" => ProposalSource::Healing,
        "recorder" => ProposalSource::Recorder,
        "monitoring" => ProposalSource::Monitoring,
        "community" => ProposalSource::Community,
        "manual" => ProposalSource::Manual,
        // Unknown / future variants land in Manual so the column
        // is never lost during a forward-compat upgrade.
        _ => ProposalSource::Manual,
    };
    let created_at_str: String = row.get("created_at")?;
    let created_at = DateTime::parse_from_rfc3339(&created_at_str)
        .map(|dt| dt.with_timezone(&Utc))
        .unwrap_or_else(|_| Utc::now());
    Ok(SkillProposal {
        proposal_id: row.get("proposal_id")?,
        source,
        skill_md: row.get("skill_md")?,
        lineage: SkillLineage {
            parent_skill_id: row.get("parent_skill_id")?,
            parent_version: row.get("parent_version")?,
            derivation_note: row.get("derivation_note")?,
        },
        telemetry: ProposalTelemetry {
            source_success_rate: row.get("source_success_rate")?,
            avg_latency_ms: row.get("avg_latency_ms")?,
            sample_size: row.get("sample_size")?,
        },
        created_at,
    })
}

/// Insert or replace a `SkillProposal`.  Uses `INSERT OR REPLACE`
/// keyed on `proposal_id` so re-submitting the same id (e.g. when
/// the front-end retries after a transient IPC error) is
/// idempotent.
pub fn save(conn: &Connection, proposal: &SkillProposal) -> Result<(), String> {
    conn.execute(
        r#"
        INSERT OR REPLACE INTO skill_proposals (
            proposal_id, source, skill_md,
            parent_skill_id, parent_version, derivation_note,
            source_success_rate, avg_latency_ms, sample_size,
            created_at
        ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)
        "#,
        params![
            proposal.proposal_id,
            proposal.source.as_str(),
            proposal.skill_md,
            proposal.lineage.parent_skill_id,
            proposal.lineage.parent_version,
            proposal.lineage.derivation_note,
            proposal.telemetry.source_success_rate,
            proposal.telemetry.avg_latency_ms,
            proposal.telemetry.sample_size,
            proposal.created_at.to_rfc3339(),
        ],
    )
    .map_err(|e| format!("Failed to save proposal {}: {}", proposal.proposal_id, e))?;
    Ok(())
}

/// List proposals, optionally filtered by `source` and limited
/// to the most recent `limit` rows.  `None` for either filter
/// means "no filter".  When `limit` is `None` the default cap is
/// 100 rows.
#[allow(dead_code)] // wired into IPC by `commands::teaching::list_proposals`
pub fn list(
    conn: &Connection,
    source: Option<ProposalSource>,
    limit: Option<u32>,
) -> Result<Vec<SkillProposal>, String> {
    let take: i64 = limit.unwrap_or(100) as i64;
    let mut out: Vec<SkillProposal> = Vec::new();

    if let Some(src) = source {
        let mut stmt = conn
            .prepare(
                "SELECT proposal_id, source, skill_md, parent_skill_id, parent_version, \
                 derivation_note, source_success_rate, avg_latency_ms, sample_size, created_at \
                 FROM skill_proposals WHERE source = ?1 \
                 ORDER BY created_at DESC LIMIT ?2",
            )
            .map_err(|e| format!("Failed to prepare list (filtered) query: {}", e))?;
        let rows = stmt
            .query_map(params![src.as_str(), take], row_to_proposal)
            .map_err(|e| format!("Failed to list proposals: {}", e))?;
        for row in rows {
            out.push(row.map_err(|e| format!("Failed to read proposal row: {}", e))?);
        }
    } else {
        let mut stmt = conn
            .prepare(
                "SELECT proposal_id, source, skill_md, parent_skill_id, parent_version, \
                 derivation_note, source_success_rate, avg_latency_ms, sample_size, created_at \
                 FROM skill_proposals ORDER BY created_at DESC LIMIT ?1",
            )
            .map_err(|e| format!("Failed to prepare list query: {}", e))?;
        let rows = stmt
            .query_map(params![take], row_to_proposal)
            .map_err(|e| format!("Failed to list proposals: {}", e))?;
        for row in rows {
            out.push(row.map_err(|e| format!("Failed to read proposal row: {}", e))?);
        }
    }

    Ok(out)
}

/// Fetch a single proposal by id.  Returns `Ok(None)` when the
/// id is not present in the table.
#[allow(dead_code)] // wired into IPC by `commands::teaching`; inbox detail view
pub fn get(conn: &Connection, id: &str) -> Result<Option<SkillProposal>, String> {
    let mut stmt = conn
        .prepare(
            "SELECT proposal_id, source, skill_md, parent_skill_id, parent_version, \
             derivation_note, source_success_rate, avg_latency_ms, sample_size, created_at \
             FROM skill_proposals WHERE proposal_id = ?1",
        )
        .map_err(|e| format!("Failed to prepare get query: {}", e))?;
    let result = stmt
        .query_row(params![id], row_to_proposal)
        .optional()
        .map_err(|e| format!("Failed to fetch proposal {}: {}", id, e))?;
    Ok(result)
}

/// Delete a proposal by id.  Returns `true` when a row was
/// removed, `false` when the id was not present.
#[allow(dead_code)] // wired into IPC by `commands::teaching::delete_proposal`
pub fn delete(conn: &Connection, id: &str) -> Result<bool, String> {
    let removed = conn
        .execute(
            "DELETE FROM skill_proposals WHERE proposal_id = ?1",
            params![id],
        )
        .map_err(|e| format!("Failed to delete proposal {}: {}", id, e))?;
    Ok(removed > 0)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn in_memory_db() -> Connection {
        let conn = Connection::open_in_memory().expect("in-memory db");
        ensure_proposals_schema(&conn).expect("schema");
        conn
    }

    fn make_proposal(source: ProposalSource, body: &str) -> SkillProposal {
        SkillProposal::new(
            source,
            body.to_string(),
            SkillLineage {
                parent_skill_id: Some("parent".into()),
                parent_version: Some(1),
                derivation_note: Some("note".into()),
            },
            ProposalTelemetry {
                source_success_rate: 0.8,
                avg_latency_ms: 42,
                sample_size: 7,
            },
        )
    }

    #[test]
    fn save_and_get_round_trip() {
        let conn = in_memory_db();
        let p = make_proposal(ProposalSource::Teaching, "name: a");
        save(&conn, &p).unwrap();
        let loaded = get(&conn, &p.proposal_id).unwrap().unwrap();
        assert_eq!(loaded.proposal_id, p.proposal_id);
        assert_eq!(loaded.source, p.source);
        assert_eq!(loaded.skill_md, p.skill_md);
        assert_eq!(loaded.lineage.parent_skill_id.as_deref(), Some("parent"));
        assert_eq!(loaded.telemetry.sample_size, 7);
    }

    #[test]
    fn list_filters_by_source() {
        let conn = in_memory_db();
        save(&conn, &make_proposal(ProposalSource::Teaching, "t1")).unwrap();
        save(&conn, &make_proposal(ProposalSource::Healing, "h1")).unwrap();
        save(&conn, &make_proposal(ProposalSource::Healing, "h2")).unwrap();

        let teaching = list(&conn, Some(ProposalSource::Teaching), None).unwrap();
        assert_eq!(teaching.len(), 1);
        assert_eq!(teaching[0].source, ProposalSource::Teaching);

        let healing = list(&conn, Some(ProposalSource::Healing), None).unwrap();
        assert_eq!(healing.len(), 2);

        let all = list(&conn, None, None).unwrap();
        assert_eq!(all.len(), 3);
    }

    #[test]
    fn delete_removes_row() {
        let conn = in_memory_db();
        let p = make_proposal(ProposalSource::Manual, "m1");
        save(&conn, &p).unwrap();
        assert!(delete(&conn, &p.proposal_id).unwrap());
        assert!(get(&conn, &p.proposal_id).unwrap().is_none());
        // Second delete is a no-op (returns false).
        assert!(!delete(&conn, &p.proposal_id).unwrap());
    }

    #[test]
    fn unknown_source_falls_back_to_manual() {
        let conn = in_memory_db();
        // Direct insert to bypass the typed save() so we can
        // simulate a row written by a future schema.
        conn.execute(
            "INSERT INTO skill_proposals (proposal_id, source, skill_md, created_at) \
             VALUES ('x', 'future-thing', 'name: x', '2026-06-06T00:00:00+00:00')",
            [],
        )
        .unwrap();
        let p = get(&conn, "x").unwrap().unwrap();
        assert_eq!(p.source, ProposalSource::Manual);
    }
}
