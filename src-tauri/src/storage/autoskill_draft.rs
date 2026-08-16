// Copyright (c) 2026 MeeJoy
//
// skill_auto_iter_draft —— AutoSkill 草稿 CRUD
//
// 管理 AutoSkill 自动迭代生成的技能草稿，覆盖从 drafting → scoring →
// pending_confirm → upgrading → watching → running 的完整生命周期，
// 以及 rejected / rollback 两种终态。
//
// 草稿经用户确认后，通过 skill_version::upsert_version 升级为正式版本。

use duckdb::params;
use serde::{Deserialize, Serialize};

use super::DuckDBPool;

#[cfg(test)]
use std::sync::Arc;

// === 来源 & 状态常量 =====================================================

pub const SOURCE_TEACHING: &str = "teaching";
pub const SOURCE_LOG_MINING: &str = "log_mining";
pub const SOURCE_MANUAL: &str = "manual";

pub const STATUS_DRAFTING: &str = "drafting";
pub const STATUS_SCORING: &str = "scoring";
pub const STATUS_PENDING_CONFIRM: &str = "pending_confirm";
pub const STATUS_UPGRADING: &str = "upgrading";
pub const STATUS_WATCHING: &str = "watching";
pub const STATUS_RUNNING: &str = "running";
pub const STATUS_REJECTED: &str = "rejected";
pub const STATUS_ROLLBACK: &str = "rollback";

// === Phase 1 迁移 ========================================================

/// 幂等迁移: 为 `skill_auto_iter_draft` 表追加 Phase 1 自进化信号元数据列。
///
/// 老的 DuckDB 文件 (在 Track C 上线前由 `SCHEMA_DDL` 建表) 没有这 4 列,
/// 这里用 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 安全补齐:
///   * `skill_kind`     TEXT DEFAULT 'mcp'      — UpgradeWriter 路由用
///   * `source_kind`    TEXT DEFAULT 'telemetry' — 信号来源 (telemetry/session_insight/...)
///   * `evidence_json`  TEXT                     — 原始 EvolutionSignal 序列化证据
///   * `signal_ref`     TEXT                     — 关联 evolution_signals.signal_id
///
/// 在 `autoskill_list_pending_drafts` 顶部调用一次即可 (幂等, 安全重复执行)。
/// 新建 DB 也会走这里 —— `SCHEMA_DDL` 不包含这 4 列, 统一由本函数补齐,
/// 避免 `storage/mod.rs::SCHEMA_DDL` 与 Phase 1 解耦。
pub fn migrate_phase1(pool: &DuckDBPool) -> Result<(), duckdb::Error> {
    let conn = pool.get_conn();
    conn.execute_batch(
        r#"
        ALTER TABLE skill_auto_iter_draft ADD COLUMN IF NOT EXISTS skill_kind TEXT DEFAULT 'mcp';
        ALTER TABLE skill_auto_iter_draft ADD COLUMN IF NOT EXISTS source_kind TEXT DEFAULT 'telemetry';
        ALTER TABLE skill_auto_iter_draft ADD COLUMN IF NOT EXISTS evidence_json TEXT;
        ALTER TABLE skill_auto_iter_draft ADD COLUMN IF NOT EXISTS signal_ref TEXT;
        "#,
    )?;
    Ok(())
}

// === 数据结构 ============================================================

/// 插入草稿的输入参数。
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct DraftInsert {
    pub scene: String,
    pub skill_id: String,
    pub draft_version: String,
    pub source: String, // teaching / log_mining / manual
    pub status: String,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub content: Option<String>, // 生成的 SKILL.md 内容
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub old_score: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub new_score: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub optimization_points: Option<serde_json::Value>,
    // === Phase 1: Hermes 自进化信号元数据 ============================
    // 由 ProposalRouter 在把 EvolutionSignal 转成 draft 时填入,
    // 让 UpgradeWriter 知道按哪种 skill_kind 落盘, 以及保留证据追溯链。
    // None 时由 DB DEFAULT 兜底 (skill_kind='mcp', source_kind='telemetry')。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub skill_kind: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub source_kind: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub evidence_json: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub signal_ref: Option<String>,
}

/// 草稿查询结果行。
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct DraftRow {
    pub id: String,
    pub scene: String,
    pub skill_id: String,
    pub draft_version: String,
    pub source: String,
    pub status: String,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub content: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub old_score: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub new_score: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub optimization_points: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub watch_started_at: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub watch_score_drop: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub created_at: Option<String>,
    // === Phase 1: Hermes 自进化信号元数据 ============================
    // 老行 (migrate_phase1 之前) 或没有信号来源的 draft 这些列为 NULL,
    // 调用方用 SkillKind::from_str_lossy 兜底到 Mcp。
    #[serde(default)]
    pub skill_kind: Option<String>,
    #[serde(default)]
    pub source_kind: Option<String>,
    #[serde(default)]
    pub evidence_json: Option<String>,
    #[serde(default)]
    pub signal_ref: Option<String>,
}

// === CRUD 函数 ===========================================================

/// 插入一条 AutoSkill 草稿，返回生成的草稿 ID。
///
/// Phase 1 新增 4 个自进化信号元数据列 (skill_kind / source_kind /
/// evidence_json / signal_ref)。调用方 (Track B ProposalRouter) 应填入,
/// 未填时 None 会让 DB 列落到 NULL (老调用方完全无感)。
pub fn insert_draft(pool: &DuckDBPool, draft: &DraftInsert) -> Result<String, duckdb::Error> {
    let id = uuid::Uuid::new_v4().to_string();
    let opt_points_json = draft
        .optimization_points
        .as_ref()
        .map(|v| serde_json::to_string(v).unwrap_or_else(|_| "[]".into()));

    let conn = pool.get_conn();
    conn.execute(
        "INSERT INTO skill_auto_iter_draft
            (id, scene, skill_id, draft_version, source, status,
             content, old_score, new_score, optimization_points,
             skill_kind, source_kind, evidence_json, signal_ref)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        params![
            id,
            draft.scene,
            draft.skill_id,
            draft.draft_version,
            draft.source,
            draft.status,
            draft.content,
            draft.old_score,
            draft.new_score,
            opt_points_json,
            draft.skill_kind,
            draft.source_kind,
            draft.evidence_json,
            draft.signal_ref,
        ],
    )?;
    Ok(id)
}

/// 更新草稿状态。
///
/// 可选附带 old_score / new_score（评分完成后填入）。
/// 当状态变为 'watching' 时，自动记录 watch_started_at。
pub fn update_status(
    pool: &DuckDBPool,
    id: &str,
    status: &str,
    old_score: Option<i32>,
    new_score: Option<i32>,
) -> Result<usize, duckdb::Error> {
    let conn = pool.get_conn();
    let affected = conn.execute(
        "UPDATE skill_auto_iter_draft SET
            status = ?,
            old_score = COALESCE(?, old_score),
            new_score = COALESCE(?, new_score),
            watch_started_at = CASE
                WHEN ? = 'watching' AND watch_started_at IS NULL THEN now()
                ELSE watch_started_at
            END
         WHERE id = ?",
        params![status, old_score, new_score, status, id],
    )?;
    Ok(affected)
}

/// 查询待确认的草稿（status = 'pending_confirm'），按创建时间倒序。
///
/// 这是 AutoSkill 面板"待确认"列表的数据源。
///
/// 调用方需先调用 `migrate_phase1` 确保 4 个 Phase 1 列已存在,
/// 否则 SELECT 会因列不存在而报错。`autoskill_list_pending_drafts`
/// 已在顶部自动调用 migrate_phase1, 直接调用 query_pending 的代码需自行保证。
pub fn query_pending(
    pool: &DuckDBPool,
    scene: &str,
) -> Result<Vec<DraftRow>, duckdb::Error> {
    let conn = pool.get_conn();
    let mut stmt = conn.prepare(
        "SELECT
            CAST(id AS TEXT), scene, skill_id, draft_version, source, status,
            content, old_score, new_score, CAST(optimization_points AS TEXT),
            CAST(watch_started_at AS TEXT), watch_score_drop,
            CAST(created_at AS TEXT),
            skill_kind, source_kind, evidence_json, signal_ref
         FROM skill_auto_iter_draft
         WHERE scene = ? AND status = 'pending_confirm'
         ORDER BY created_at DESC",
    )?;
    let rows = stmt.query_map(params![scene], |row| {
        Ok(DraftRow {
            id: row.get(0)?,
            scene: row.get(1)?,
            skill_id: row.get(2)?,
            draft_version: row.get(3)?,
            source: row.get(4)?,
            status: row.get(5)?,
            content: row.get(6)?,
            old_score: row.get(7)?,
            new_score: row.get(8)?,
            optimization_points: row.get(9)?,
            watch_started_at: row.get(10)?,
            watch_score_drop: row.get(11)?,
            created_at: row.get(12)?,
            skill_kind: row.get(13)?,
            source_kind: row.get(14)?,
            evidence_json: row.get(15)?,
            signal_ref: row.get(16)?,
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
        let pool = DuckDBPool {
            conn: Arc::new(std::sync::Mutex::new(conn)),
        };
        // SCHEMA_DDL 不含 Phase 1 的 4 个新列, 这里补齐以匹配生产路径
        // (autoskill_list_pending_drafts 顶部会调用 migrate_phase1)。
        super::migrate_phase1(&pool).unwrap();
        pool
    }

    #[test]
    fn test_insert_update_and_query_pending() {
        let pool = setup_pool();

        // 插入两条草稿。id1 带 Phase 1 信号元数据 (会被推进到 pending_confirm,
        // 用来验证新列 SELECT 回填); id2 全部 None (验证老调用方无感)。
        let id1 = insert_draft(
            &pool,
            &DraftInsert {
                scene: "work".into(),
                skill_id: "skill-D".into(),
                draft_version: "1.1.0-draft".into(),
                source: SOURCE_TEACHING.into(),
                status: STATUS_DRAFTING.into(),
                content: Some("# Improved Skill".into()),
                old_score: Some(60),
                new_score: None,
                optimization_points: Some(serde_json::json!([{"point": "增加重试"}])),
                skill_kind: Some("mcp".into()),
                source_kind: Some("session_insight".into()),
                evidence_json: Some(r#"{"kind":"sessionInsight"}"#.into()),
                signal_ref: Some("sig_01".into()),
            },
        )
        .unwrap();

        let _id2 = insert_draft(
            &pool,
            &DraftInsert {
                scene: "work".into(),
                skill_id: "skill-E".into(),
                draft_version: "2.0.0-draft".into(),
                source: SOURCE_LOG_MINING.into(),
                status: STATUS_DRAFTING.into(),
                content: None,
                old_score: None,
                new_score: None,
                optimization_points: None,
                skill_kind: None,
                source_kind: None,
                evidence_json: None,
                signal_ref: None,
            },
        )
        .unwrap();

        // 初始无 pending_confirm
        let pending = query_pending(&pool, "work").unwrap();
        assert_eq!(pending.len(), 0);

        // 将 id1 状态推进到 scoring → pending_confirm
        update_status(&pool, &id1, STATUS_SCORING, None, Some(78)).unwrap();
        update_status(&pool, &id1, STATUS_PENDING_CONFIRM, None, None).unwrap();

        // 现在有 1 条 pending
        let pending = query_pending(&pool, "work").unwrap();
        assert_eq!(pending.len(), 1);
        assert_eq!(pending[0].skill_id, "skill-D");
        assert_eq!(pending[0].new_score, Some(78));
        assert!(pending[0].optimization_points.as_deref().unwrap().contains("增加重试"));

        // Phase 1 信号元数据列正确回填
        assert_eq!(pending[0].skill_kind.as_deref(), Some("mcp"));
        assert_eq!(pending[0].source_kind.as_deref(), Some("session_insight"));
        assert_eq!(pending[0].signal_ref.as_deref(), Some("sig_01"));
        assert!(pending[0].evidence_json.as_deref().unwrap().contains("sessionInsight"));

        // 切到 watching 验证 watch_started_at
        update_status(&pool, &id1, STATUS_WATCHING, None, None).unwrap();
        let pending = query_pending(&pool, "work").unwrap();
        assert_eq!(pending.len(), 0); // watching 不在 pending 列表
    }
}
