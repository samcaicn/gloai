// Copyright (c) 2026 MeeJoy
//
// skill_version_manage —— 技能版本管理 CRUD
//
// 管理技能的版本族谱：draft → active → watching → rollback / archived。
// 每条记录对应一个 (scene, skill_id, version) 唯一组合，存储 SKILL.md
// 内容、评分、changelog。AutoSkill 迭代时通过 upsert 写入新版本，
// 通过 get_active 查询当前生效版本。

use duckdb::params;
use serde::{Deserialize, Serialize};

use super::DuckDBPool;

#[cfg(test)]
use std::sync::Arc;

// === 状态常量 ============================================================

pub const STATUS_DRAFT: &str = "draft";
pub const STATUS_ACTIVE: &str = "active";
pub const STATUS_WATCHING: &str = "watching";
pub const STATUS_ROLLBACK: &str = "rollback";
pub const STATUS_ARCHIVED: &str = "archived";

// === 数据结构 ============================================================

/// 技能版本 upsert 输入参数。
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct SkillVersionUpsert {
    pub scene: String,
    pub skill_id: String,
    pub version: String, // semver: 1.0.0
    pub status: String,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub score: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub score_detail: Option<serde_json::Value>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub content: Option<String>, // SKILL.md 内容
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub changelog: Option<String>,
}

/// 技能版本查询结果行。
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct SkillVersionRow {
    pub id: String,
    pub scene: String,
    pub skill_id: String,
    pub version: String,
    pub status: String,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub score: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub score_detail: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub content: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub changelog: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub created_at: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub activated_at: Option<String>,
}

// === CRUD 函数 ===========================================================

/// 插入或更新技能版本记录（UPSERT）。
///
/// 依据 UNIQUE(scene, skill_id, version) 约束，冲突时更新
/// status / score / score_detail / content / changelog。
/// 当 status 变为 'active' 且 activated_at 为空时，自动填入 now()。
pub fn upsert_version(
    pool: &DuckDBPool,
    input: &SkillVersionUpsert,
) -> Result<(), duckdb::Error> {
    let score_detail_json = input
        .score_detail
        .as_ref()
        .map(|v| serde_json::to_string(v).unwrap_or_else(|_| "{}".into()));

    let conn = pool.get_conn();
    conn.execute(
        "INSERT INTO skill_version_manage
            (scene, skill_id, version, status, score, score_detail, content, changelog, activated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?,
            CASE WHEN ? = 'active' THEN now() ELSE NULL END)
         ON CONFLICT(scene, skill_id, version) DO UPDATE SET
            status = excluded.status,
            score = excluded.score,
            score_detail = excluded.score_detail,
            content = excluded.content,
            changelog = excluded.changelog,
            activated_at = CASE
                WHEN excluded.status = 'active' AND skill_version_manage.activated_at IS NULL
                THEN now()
                ELSE skill_version_manage.activated_at
            END",
        params![
            input.scene,
            input.skill_id,
            input.version,
            input.status,
            input.score,
            score_detail_json,
            input.content,
            input.changelog,
            input.status,
        ],
    )?;
    Ok(())
}

/// 查询指定技能当前 active 的版本（最多 1 条）。
///
/// 返回 None 表示该技能尚无 active 版本。
pub fn get_active(
    pool: &DuckDBPool,
    scene: &str,
    skill_id: &str,
) -> Result<Option<SkillVersionRow>, duckdb::Error> {
    let conn = pool.get_conn();
    let mut stmt = conn.prepare(
        "SELECT
            CAST(id AS TEXT), scene, skill_id, version, status,
            score, CAST(score_detail AS TEXT), content, changelog,
            CAST(created_at AS TEXT), CAST(activated_at AS TEXT)
         FROM skill_version_manage
         WHERE scene = ? AND skill_id = ? AND status = 'active'
         ORDER BY activated_at DESC NULLS LAST
         LIMIT 1",
    )?;
    let mut rows = stmt.query_map(params![scene, skill_id], |row| {
        Ok(SkillVersionRow {
            id: row.get(0)?,
            scene: row.get(1)?,
            skill_id: row.get(2)?,
            version: row.get(3)?,
            status: row.get(4)?,
            score: row.get(5)?,
            score_detail: row.get(6)?,
            content: row.get(7)?,
            changelog: row.get(8)?,
            created_at: row.get(9)?,
            activated_at: row.get(10)?,
        })
    })?;
    match rows.next() {
        Some(Ok(row)) => Ok(Some(row)),
        Some(Err(e)) => Err(e),
        None => Ok(None),
    }
}

/// 列出指定技能的所有版本，按创建时间倒序。
pub fn list_by_skill(
    pool: &DuckDBPool,
    scene: &str,
    skill_id: &str,
) -> Result<Vec<SkillVersionRow>, duckdb::Error> {
    let conn = pool.get_conn();
    let mut stmt = conn.prepare(
        "SELECT
            CAST(id AS TEXT), scene, skill_id, version, status,
            score, CAST(score_detail AS TEXT), content, changelog,
            CAST(created_at AS TEXT), CAST(activated_at AS TEXT)
         FROM skill_version_manage
         WHERE scene = ? AND skill_id = ?
         ORDER BY created_at DESC",
    )?;
    let rows = stmt.query_map(params![scene, skill_id], |row| {
        Ok(SkillVersionRow {
            id: row.get(0)?,
            scene: row.get(1)?,
            skill_id: row.get(2)?,
            version: row.get(3)?,
            status: row.get(4)?,
            score: row.get(5)?,
            score_detail: row.get(6)?,
            content: row.get(7)?,
            changelog: row.get(8)?,
            created_at: row.get(9)?,
            activated_at: row.get(10)?,
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
    fn test_upsert_and_get_active() {
        let pool = setup_pool();

        // 插入 draft 版本
        upsert_version(
            &pool,
            &SkillVersionUpsert {
                scene: "work".into(),
                skill_id: "skill-A".into(),
                version: "1.0.0".into(),
                status: STATUS_DRAFT.into(),
                score: Some(60),
                score_detail: Some(serde_json::json!({"stability": 60})),
                content: Some("# Skill A v1".into()),
                changelog: Some("初始版本".into()),
            },
        )
        .unwrap();

        // draft 时无 active 版本
        let active = get_active(&pool, "work", "skill-A").unwrap();
        assert!(active.is_none());

        // 升级为 active
        upsert_version(
            &pool,
            &SkillVersionUpsert {
                scene: "work".into(),
                skill_id: "skill-A".into(),
                version: "1.0.0".into(),
                status: STATUS_ACTIVE.into(),
                score: Some(75),
                score_detail: None,
                content: Some("# Skill A v1 improved".into()),
                changelog: Some("激活".into()),
            },
        )
        .unwrap();

        let active = get_active(&pool, "work", "skill-A").unwrap();
        assert!(active.is_some());
        let active = active.unwrap();
        assert_eq!(active.status, STATUS_ACTIVE);
        assert_eq!(active.score, Some(75));
        assert!(active.activated_at.is_some());
    }

    #[test]
    fn test_list_by_skill() {
        let pool = setup_pool();
        for ver in &["1.0.0", "1.1.0", "2.0.0"] {
            upsert_version(
                &pool,
                &SkillVersionUpsert {
                    scene: "hobby".into(),
                    skill_id: "skill-B".into(),
                    version: (*ver).into(),
                    status: STATUS_DRAFT.into(),
                    score: None,
                    score_detail: None,
                    content: None,
                    changelog: None,
                },
            )
            .unwrap();
        }
        let list = list_by_skill(&pool, "hobby", "skill-B").unwrap();
        assert_eq!(list.len(), 3);
    }
}
