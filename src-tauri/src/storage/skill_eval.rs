// Copyright (c) 2026 MeeJoy
//
// skill_score_eval —— 技能评估打分记录 CRUD
//
// 每次对技能版本进行评估后写入一条记录，包含成功率 / 稳定性 / 效率 /
// 通用性四维分数 + 加权总分 + 采样次数 + 评估明细。
// skill_version_manage.score 定期从 skill_score_eval 的最新记录同步。

use duckdb::params;
use serde::{Deserialize, Serialize};

use super::DuckDBPool;

#[cfg(test)]
use std::sync::Arc;

// === 数据结构 ============================================================

/// 插入评估记录的输入参数。
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct EvalInsert {
    pub scene: String,
    pub skill_id: String,
    pub skill_version: String,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub success_rate: Option<f64>, // 0.0-1.0
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub stability_score: Option<f64>, // 0-100
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub efficiency_score: Option<f64>, // 0-100
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub generality_score: Option<f64>, // 0-100
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub total_score: Option<i32>, // 0-100 加权总分
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub sample_count: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub eval_detail: Option<serde_json::Value>,
}

/// 评估记录查询结果行。
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct EvalRow {
    pub id: String,
    pub scene: String,
    pub skill_id: String,
    pub skill_version: String,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub success_rate: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub stability_score: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub efficiency_score: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub generality_score: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub total_score: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub sample_count: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub eval_detail: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub created_at: Option<String>,
}

// === CRUD 函数 ===========================================================

/// 插入一条评估打分记录，返回生成的记录 ID。
pub fn insert_eval(pool: &DuckDBPool, eval: &EvalInsert) -> Result<String, duckdb::Error> {
    let id = uuid::Uuid::new_v4().to_string();
    let eval_detail_json = eval
        .eval_detail
        .as_ref()
        .map(|v| serde_json::to_string(v).unwrap_or_else(|_| "{}".into()));

    let conn = pool.get_conn();
    conn.execute(
        "INSERT INTO skill_score_eval
            (id, scene, skill_id, skill_version,
             success_rate, stability_score, efficiency_score, generality_score,
             total_score, sample_count, eval_detail)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        params![
            id,
            eval.scene,
            eval.skill_id,
            eval.skill_version,
            eval.success_rate,
            eval.stability_score,
            eval.efficiency_score,
            eval.generality_score,
            eval.total_score,
            eval.sample_count,
            eval_detail_json,
        ],
    )?;
    Ok(id)
}

/// 查询指定技能版本的最新评估记录。
///
/// 返回 None 表示该版本尚无评估记录。
pub fn query_latest(
    pool: &DuckDBPool,
    scene: &str,
    skill_id: &str,
    skill_version: &str,
) -> Result<Option<EvalRow>, duckdb::Error> {
    let conn = pool.get_conn();
    let mut stmt = conn.prepare(
        "SELECT
            CAST(id AS TEXT), scene, skill_id, skill_version,
            success_rate, stability_score, efficiency_score, generality_score,
            total_score, sample_count, CAST(eval_detail AS TEXT),
            CAST(created_at AS TEXT)
         FROM skill_score_eval
         WHERE scene = ? AND skill_id = ? AND skill_version = ?
         ORDER BY created_at DESC
         LIMIT 1",
    )?;
    let mut rows = stmt.query_map(params![scene, skill_id, skill_version], |row| {
        Ok(EvalRow {
            id: row.get(0)?,
            scene: row.get(1)?,
            skill_id: row.get(2)?,
            skill_version: row.get(3)?,
            success_rate: row.get(4)?,
            stability_score: row.get(5)?,
            efficiency_score: row.get(6)?,
            generality_score: row.get(7)?,
            total_score: row.get(8)?,
            sample_count: row.get(9)?,
            eval_detail: row.get(10)?,
            created_at: row.get(11)?,
        })
    })?;
    match rows.next() {
        Some(Ok(row)) => Ok(Some(row)),
        Some(Err(e)) => Err(e),
        None => Ok(None),
    }
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
    fn test_insert_and_query_latest() {
        let pool = setup_pool();

        // 插入第一条评估
        insert_eval(
            &pool,
            &EvalInsert {
                scene: "work".into(),
                skill_id: "skill-X".into(),
                skill_version: "1.0.0".into(),
                success_rate: Some(0.8),
                stability_score: Some(70.0),
                efficiency_score: Some(65.0),
                generality_score: Some(60.0),
                total_score: Some(68),
                sample_count: Some(10),
                eval_detail: Some(serde_json::json!({"note": "first"})),
            },
        )
        .unwrap();

        // 插入第二条（更新的评估）
        insert_eval(
            &pool,
            &EvalInsert {
                scene: "work".into(),
                skill_id: "skill-X".into(),
                skill_version: "1.0.0".into(),
                success_rate: Some(0.9),
                stability_score: Some(85.0),
                efficiency_score: Some(80.0),
                generality_score: Some(75.0),
                total_score: Some(82),
                sample_count: Some(20),
                eval_detail: None,
            },
        )
        .unwrap();

        // 查询最新 —— 应返回第二条
        let latest = query_latest(&pool, "work", "skill-X", "1.0.0").unwrap();
        assert!(latest.is_some());
        let latest = latest.unwrap();
        assert_eq!(latest.total_score, Some(82));
        // FLOAT 列存在浮点精度损失，用 approx 比较
        let rate = latest.success_rate.unwrap_or(0.0);
        assert!((rate - 0.9).abs() < 1e-5, "success_rate 精度损失: got {}", rate);
        assert_eq!(latest.sample_count, Some(20));

        // 查询不存在的版本
        let none = query_latest(&pool, "work", "skill-X", "9.9.9").unwrap();
        assert!(none.is_none());
    }
}
