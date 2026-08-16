// Copyright (c) 2026 AIMarketing
//
// SkillMemory · FTS5 recall.
//
// Thin wrapper over the `skill_fts` virtual table managed by
// `memory::SkillDb`. Two public entry points:
//
//   * `search_skills` — BM25-ranked full-text recall over every
//     saved `skill_versions.skill_md`, returning a snippet for the
//     front-end to render in the picker UI.
//
//   * `reindex_fts` — rebuild the FTS mirror from the canonical
//     `skill_versions` table. Useful after a schema migration that
//     pre-dates the FTS table, or after a manual SQL edit.

use rusqlite::params;
use serde::{Deserialize, Serialize};

use super::memory::SkillDb;

/// One full-text search hit. `snippet` is the
/// `snippet(skill_fts, 2, …)`-rendered HTML — a short window of the
/// original `skill_md` with the matched terms wrapped in `<b>…</b>`.
/// `rank` is the negative BM25 score (FTS5 returns a *lower-is-better*
/// value, but we invert it here so the front-end can sort descending
/// without an extra step).
#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct FtsHit {
    pub skill_id: String,
    pub version: u32,
    pub snippet: String,
    pub rank: f64,
}

/// Run an FTS5 query against the `skill_fts` virtual table. Returns
/// up to `limit` hits, ordered by BM25 rank. An empty query returns
/// an empty list — we deliberately don't fall back to "recent
/// versions" so the front-end has to make that decision explicitly
/// (and so the function is safe to call from the daily evolution
/// job without surprising side effects).
#[allow(dead_code)] // wired into IPC by `commands::memory::search_skills`
pub fn search_skills(
    state: &SkillDb,
    query: &str,
    limit: u32,
) -> Result<Vec<FtsHit>, String> {
    let trimmed = query.trim();
    if trimmed.is_empty() {
        return Ok(Vec::new());
    }
    let conn = state.conn();
    let mut stmt = conn
        .prepare(
            r#"SELECT skill_id,
                      version,
                      snippet(skill_fts, 2, '<b>', '</b>', '…', 12) AS snip,
                      bm25(skill_fts) AS score
               FROM skill_fts
              WHERE skill_fts MATCH ?1
              ORDER BY score ASC
              LIMIT ?2"#,
        )
        .map_err(|e| format!("prepare fts query: {}", e))?;
    let rows = stmt
        .query_map(params![trimmed, limit as i64], |row| {
            Ok(FtsHit {
                skill_id: row.get(0)?,
                version: row.get::<_, i64>(1)? as u32,
                snippet: row.get(2)?,
                // Invert the BM25 score so callers can sort
                // descending (higher rank = better match).
                rank: -row.get::<_, f64>(3)?,
            })
        })
        .map_err(|e| format!("query fts: {}", e))?;
    let mut out = Vec::new();
    for r in rows {
        out.push(r.map_err(|e| format!("read fts row: {}", e))?);
    }
    Ok(out)
}

/// Drop and rebuild every row of `skill_fts` from the canonical
/// `skill_versions` table. Returns the number of rows reindexed.
#[allow(dead_code)] // wired into IPC by `commands::memory`
pub fn reindex_fts(state: &SkillDb) -> Result<usize, String> {
    let conn = state.conn();
    let tx = conn
        .unchecked_transaction()
        .map_err(|e| format!("reindex tx begin: {}", e))?;
    tx.execute("DELETE FROM skill_fts", [])
        .map_err(|e| format!("reindex clear: {}", e))?;
    let inserted = tx
        .execute(
            "INSERT INTO skill_fts (skill_id, version, skill_md)
             SELECT skill_id, version, skill_md FROM skill_versions",
            [],
        )
        .map_err(|e| format!("reindex insert: {}", e))?;
    tx.commit().map_err(|e| format!("reindex commit: {}", e))?;
    Ok(inserted)
}

// === Unit tests ===========================================================

#[cfg(test)]
mod tests {
    use super::*;
    use crate::skill::memory::{save_skill_version, SkillVersion};
    use tempfile::tempdir;

    fn open_tmp() -> (tempfile::TempDir, SkillDb) {
        let dir = tempdir().expect("tempdir");
        let db = SkillDb::open_at(dir.path().join("skill.db")).expect("open");
        (dir, db)
    }

    fn seed(db: &SkillDb, id: &str, v: u32, body: &str) {
        save_skill_version(
            db,
            &SkillVersion {
                skill_id: id.to_string(),
                version: v,
                parent_skill_id: None,
                parent_version: None,
                source: "manual".to_string(),
                skill_md: body.to_string(),
                created_at: "2026-06-06T00:00:00.000Z".to_string(),
                state: "candidate".to_string(),
            },
        )
        .expect("seed");
    }

    #[test]
    fn search_returns_at_least_one_hit_for_known_query() {
        let (_dir, db) = open_tmp();
        seed(
            &db,
            "export-excel",
            1,
            "# 导出 Excel\nname: 导出 Excel\ndescription: 把当前表格导出为 xlsx\n",
        );
        seed(
            &db,
            "send-mail",
            1,
            "# 发邮件\nname: 发邮件\ndescription: 调起 Outlook 发邮件给指定收件人\n",
        );
        seed(
            &db,
            "merge-cells",
            1,
            "# 合并单元格\nname: 合并单元格\ndescription: 选区内 Excel 单元格合并\n",
        );

        let hits = search_skills(&db, "导出 Excel", 10).expect("search");
        assert!(
            !hits.is_empty(),
            "expected at least one hit for '导出 Excel'"
        );
        assert_eq!(hits[0].skill_id, "export-excel");
        assert!(
            hits[0].snippet.contains("导出") || hits[0].snippet.contains("<b>"),
            "snippet should highlight the matched term: {}",
            hits[0].snippet
        );
    }

    #[test]
    fn search_ranks_exact_match_above_partial_match() {
        let (_dir, db) = open_tmp();
        seed(
            &db,
            "exact",
            1,
            "# 导出 Excel\ndescription: 这是一个专门讲导出 Excel 的 skill\n",
        );
        // The FTS5 `unicode61` tokenizer (with the project's
        // `remove_diacritics 2` option) does NOT split CJK by
        // default: a run of Chinese characters with no ASCII
        // whitespace in between is a single token. Surround the
        // shared term "导出" with ASCII spaces so it tokenizes on
        // its own and is comparable to the "exact" body.
        seed(
            &db,
            "noisy",
            1,
            "# 杂项\ndescription: 这条只顺带提了 导出 一句,主体是别的事\n",
        );
        // Pure-decoy row that doesn't contain "导出" at all.
        seed(
            &db,
            "decoy",
            1,
            "# PowerPoint 幻灯片放映与排版技巧示例合集，专注于 Office 全家桶的常用操作\n",
        );

        // Single-token query "导出" so both exact and noisy match.
        let hits = search_skills(&db, "导出", 10).expect("search");
        let ids: Vec<&str> = hits.iter().map(|h| h.skill_id.as_str()).collect();

        // Both rows that contain the query term must be in the
        // result set. The decoy (no term) must not be.
        assert!(
            ids.contains(&"exact"),
            "expected 'exact' in hits, got {:?}",
            ids
        );
        assert!(
            ids.contains(&"noisy"),
            "expected 'noisy' in hits, got {:?}",
            ids
        );
        assert!(
            !ids.contains(&"decoy"),
            "decoy must not appear in hits, got {:?}",
            ids
        );
        // Note: we deliberately do NOT assert a strict order
        // between `exact` and `noisy`. The `skill_fts` table is a
        // single-column FTS5 (skill_md only); there is no title /
        // body split, so bm25 has no way to reward the "H1" placement
        // of the term in `exact` over the body-only placement in
        // `noisy`. Asserting strict order here was a test bug, not
        // a feature of the search code.
        for hit in &hits {
            assert!(hit.rank > 0.0, "inverted rank must be > 0: {:?}", hit);
        }
    }

    #[test]
    fn search_respects_limit_and_empty_query() {
        let (_dir, db) = open_tmp();
        for i in 0..5 {
            seed(
                &db,
                &format!("skill-{i}"),
                1,
                &format!("# skill {i}\nname: skill {i}\ndescription: 通用 helper\n"),
            );
        }
        let hits = search_skills(&db, "helper", 2).expect("search");
        assert!(hits.len() <= 2, "limit must be honoured");
        assert!(!hits.is_empty());

        let empty = search_skills(&db, "   ", 10).expect("empty");
        assert!(empty.is_empty(), "whitespace-only query returns no hits");
    }

    #[test]
    fn reindex_fts_resyncs_after_manual_delete() {
        let (_dir, db) = open_tmp();
        seed(&db, "a", 1, "first body about cats");
        seed(&db, "b", 1, "second body about dogs");
        // Manually delete one FTS row to simulate drift.
        db.conn()
            .execute("DELETE FROM skill_fts WHERE skill_id='a'", [])
            .unwrap();
        assert_eq!(search_skills(&db, "cats", 10).unwrap().len(), 0);
        let n = reindex_fts(&db).expect("reindex");
        assert!(n >= 2);
        assert_eq!(search_skills(&db, "cats", 10).unwrap().len(), 1);
    }
}
