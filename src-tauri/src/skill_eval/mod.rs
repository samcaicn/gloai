// Copyright (c) 2026 MeeJoy
//
// 技能评估引擎 —— 4 维度加权打分。
//
// 维度权重：
//   - 执行成功率 40% (WEIGHT_SUCCESS)
//   - 流程稳定性 25% (WEIGHT_STABILITY)
//   - 执行效率   20% (WEIGHT_EFFICIENCY)
//   - 通用性     15% (WEIGHT_GENERALITY)
// 达标阈值 85 分。
//
// 数据流：
//   worker_task_log (历史执行) → SkillScorer (打分) → skill_score_eval (持久化)
//
// SkillEvalEngine 持有 DuckDBPool 引用，对外暴露 evaluate / get_latest_score。

pub mod dimensions;
pub mod scorer;

pub use dimensions::{DimensionScore, ScoreResult};
pub use scorer::{SkillScorer, TaskRecord};

use std::sync::Arc;

use crate::storage::skill_eval::{insert_eval, query_latest, EvalInsert, EvalRow};
use crate::storage::worker_task_log::{query_by_skill, TaskLogRow};
use crate::storage::DuckDBPool;

/// 技能评估引擎。
pub struct SkillEvalEngine {
    db: Arc<DuckDBPool>,
    scorer: SkillScorer,
}

impl SkillEvalEngine {
    pub fn new(db: Arc<DuckDBPool>) -> Self {
        Self {
            db,
            scorer: SkillScorer::new(),
        }
    }

    /// 评估技能：读取历史执行记录，计算 4 维度得分，写入 skill_score_eval 表。
    ///
    /// 步骤：
    /// 1. 从 worker_task_log 查询该技能版本的历史执行记录（最多 1000 条）
    /// 2. 转换为 TaskRecord 数组
    /// 3. 调用 SkillScorer::score 计算 4 维度加权总分
    /// 4. 写入 skill_score_eval 表（完整 ScoreResult 序列化到 eval_detail 列）
    /// 5. 返回 ScoreResult
    pub async fn evaluate(
        &self,
        scene: &str,
        skill_id: &str,
        skill_version: &str,
    ) -> Result<ScoreResult, EvalError> {
        // 1. 查询历史执行记录
        let rows = query_by_skill(
            &self.db,
            scene,
            skill_id,
            Some(skill_version),
            1000,
        )?;

        if rows.is_empty() {
            return Err(EvalError::NoData);
        }

        // 2. 转换为 TaskRecord
        let records: Vec<TaskRecord> = rows.iter().map(task_record_from_row).collect();

        // 3. 计算分数
        let mut result = self.scorer.score(&records);
        result.scene = scene.to_string();
        result.skill_id = skill_id.to_string();
        result.skill_version = skill_version.to_string();

        // 4. 写入 skill_score_eval 表
        let eval_insert = build_eval_insert(scene, skill_id, skill_version, &result);
        insert_eval(&self.db, &eval_insert)?;

        // 5. 返回结果
        Ok(result)
    }

    /// 获取最新评分：从 skill_score_eval 表查询最新一条记录。
    ///
    /// 优先从 eval_detail 列反序列化完整 ScoreResult（包含 4 维度详情）；
    /// 若 eval_detail 缺失或解析失败则从各列字段降级重建。
    pub async fn get_latest_score(
        &self,
        scene: &str,
        skill_id: &str,
        skill_version: &str,
    ) -> Result<Option<ScoreResult>, EvalError> {
        let row = query_latest(&self.db, scene, skill_id, skill_version)?;
        match row {
            None => Ok(None),
            Some(row) => Ok(Some(rebuild_score_result(&row))),
        }
    }

    /// 达标判定（>=85 分）。
    pub fn is_qualified(score: i32) -> bool {
        score >= dimensions::QUALIFY_THRESHOLD
    }
}

/// 将 TaskLogRow 转换为 Scorer 使用的 TaskRecord。
///
/// - duration_ms 缺失时记 0（失败任务通常无耗时）。
/// - retry_count 负值钳制为 0（DDL 默认 0，理论上不会出现）。
/// - params 解析失败时降级为 null。
fn task_record_from_row(row: &TaskLogRow) -> TaskRecord {
    let params = row
        .params
        .as_deref()
        .and_then(|s| serde_json::from_str(s).ok())
        .unwrap_or(serde_json::Value::Null);

    TaskRecord {
        status: row.status.clone(),
        duration_ms: row.duration_ms.unwrap_or(0),
        retry_count: row.retry_count.max(0) as u32,
        error: row.error.clone(),
        params,
    }
}

/// 从 ScoreResult 构造 EvalInsert。
///
/// success_rate 列存原始比率（0.0-1.0），其余三维存 0-100 分数。
fn build_eval_insert(
    scene: &str,
    skill_id: &str,
    skill_version: &str,
    result: &ScoreResult,
) -> EvalInsert {
    // 从 success_rate 维度 detail 中提取原始比率
    let success_rate = result
        .dimensions
        .iter()
        .find(|d| d.name == "success_rate")
        .and_then(|d| d.detail.get("rate"))
        .and_then(|v| v.as_f64());

    EvalInsert {
        scene: scene.to_string(),
        skill_id: skill_id.to_string(),
        skill_version: skill_version.to_string(),
        success_rate,
        stability_score: dim_score(result, "stability"),
        efficiency_score: dim_score(result, "efficiency"),
        generality_score: dim_score(result, "generality"),
        total_score: Some(result.total_score),
        sample_count: Some(result.sample_count as i32),
        eval_detail: Some(serde_json::to_value(result).unwrap_or(serde_json::Value::Null)),
    }
}

/// 从 ScoreResult 中按维度名取出 score（0-100）。
fn dim_score(result: &ScoreResult, name: &str) -> Option<f64> {
    result
        .dimensions
        .iter()
        .find(|d| d.name == name)
        .map(|d| d.score)
}

/// 从 EvalRow 重建 ScoreResult。
///
/// 优先从 eval_detail 反序列化完整结构（含 4 维度详情）；
/// 失败时从各列字段降级重建（维度详情为空对象）。
fn rebuild_score_result(row: &EvalRow) -> ScoreResult {
    // 优先走完整反序列化路径
    if let Some(detail_str) = &row.eval_detail {
        if let Ok(result) = serde_json::from_str::<ScoreResult>(detail_str) {
            return result;
        }
    }

    // 降级重建：从各列字段拼装
    let total_score = row.total_score.unwrap_or(0);
    let mut dimensions = Vec::with_capacity(4);

    // success_rate 列存 0.0-1.0，需 sigmoid 还原回 0-100 score
    if let Some(rate) = row.success_rate {
        let score = 100.0 / (1.0 + (-10.0 * (rate - 0.5)).exp());
        dimensions.push(DimensionScore {
            name: "success_rate".into(),
            score,
            weight: dimensions::WEIGHT_SUCCESS,
            weighted: score * dimensions::WEIGHT_SUCCESS,
            detail: serde_json::json!({"rate": rate}),
        });
    }
    for (name, weight, val) in [
        ("stability", dimensions::WEIGHT_STABILITY, row.stability_score),
        ("efficiency", dimensions::WEIGHT_EFFICIENCY, row.efficiency_score),
        ("generality", dimensions::WEIGHT_GENERALITY, row.generality_score),
    ] {
        if let Some(score) = val {
            dimensions.push(DimensionScore {
                name: name.into(),
                score,
                weight,
                weighted: score * weight,
                detail: serde_json::json!({}),
            });
        }
    }

    ScoreResult {
        scene: row.scene.clone(),
        skill_id: row.skill_id.clone(),
        skill_version: row.skill_version.clone(),
        total_score,
        dimensions,
        sample_count: row.sample_count.unwrap_or(0) as usize,
        qualified: total_score >= dimensions::QUALIFY_THRESHOLD,
        eval_detail: serde_json::json!({}),
    }
}

#[derive(Debug, thiserror::Error)]
pub enum EvalError {
    #[error("数据库错误: {0}")]
    Db(#[from] duckdb::Error),
    #[error("无执行记录")]
    NoData,
    #[error("序列化错误: {0}")]
    Serde(#[from] serde_json::Error),
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_is_qualified_threshold() {
        assert!(SkillEvalEngine::is_qualified(85));
        assert!(SkillEvalEngine::is_qualified(100));
        assert!(!SkillEvalEngine::is_qualified(84));
        assert!(!SkillEvalEngine::is_qualified(0));
    }

    #[test]
    fn test_task_record_from_row_defaults() {
        let row = TaskLogRow {
            id: "test".into(),
            scene: "work".into(),
            task_type: "lightweight".into(),
            skill_id: Some("skill-1".into()),
            skill_version: Some("1.0.0".into()),
            status: "failed".into(),
            priority: 0,
            params: None, // 缺失 → null
            result: None,
            error: None,
            retry_count: -1, // 负值 → 钳制为 0
            duration_ms: None, // 缺失 → 0
            created_at: None,
            started_at: None,
            finished_at: None,
        };
        let rec = task_record_from_row(&row);
        assert_eq!(rec.status, "failed");
        assert_eq!(rec.duration_ms, 0);
        assert_eq!(rec.retry_count, 0);
        assert!(rec.params.is_null());
    }

    #[test]
    fn test_build_eval_insert_extracts_success_rate() {
        let scorer = SkillScorer::new();
        let records = vec![TaskRecord {
            status: "succeeded".into(),
            duration_ms: 3000,
            retry_count: 0,
            error: None,
            params: serde_json::json!({"url": "a"}),
        }];
        let mut result = scorer.score(&records);
        result.scene = "work".into();
        result.skill_id = "skill-1".into();
        result.skill_version = "1.0.0".into();

        let insert = build_eval_insert("work", "skill-1", "1.0.0", &result);
        // success_rate 列应存 0.0-1.0 的比率
        assert_eq!(insert.success_rate, Some(1.0));
        assert!(insert.total_score.is_some());
        assert!(insert.eval_detail.is_some());
    }
}
