// Copyright (c) 2026 MeeJoy
//
// 4 维度打分逻辑。
//
// SkillScorer 是无状态的纯函数集合，输入 &[TaskRecord] 输出 ScoreResult。
// 由 SkillEvalEngine::evaluate() 调用，自身不接触 DB。

use crate::skill_eval::dimensions::*;
use serde_json::{json, Value};

/// 4 维度打分器（无状态）。
pub struct SkillScorer;

impl SkillScorer {
    pub fn new() -> Self {
        Self
    }

    /// 计算完整评分。
    ///
    /// 返回的 ScoreResult 中 scene / skill_id / skill_version 为空字符串，
    /// 由调用方（SkillEvalEngine::evaluate）填充。
    pub fn score(&self, records: &[TaskRecord]) -> ScoreResult {
        let sample_count = records.len();

        let success = self.score_success_rate(records);
        let stability = self.score_stability(records);
        let efficiency = self.score_efficiency(records);
        let generality = self.score_generality(records);

        let dimensions = vec![success, stability, efficiency, generality];
        let total: f64 = dimensions.iter().map(|d| d.weighted).sum();
        let total_score = total.round() as i32;

        let detail = json!({
            "sample_count": sample_count,
            "success_rate": dimensions[0].detail,
            "stability": dimensions[1].detail,
            "efficiency": dimensions[2].detail,
            "generality": dimensions[3].detail,
        });

        ScoreResult {
            scene: String::new(),
            skill_id: String::new(),
            skill_version: String::new(),
            total_score,
            dimensions,
            sample_count,
            qualified: total_score >= QUALIFY_THRESHOLD,
            eval_detail: detail,
        }
    }

    /// 成功率 40%：成功次数/总次数，sigmoid 映射到 0-100。
    ///
    /// sigmoid 映射：rate=0.5 → ~50分, rate=0.9 → ~85分, rate=1.0 → ~95分
    fn score_success_rate(&self, records: &[TaskRecord]) -> DimensionScore {
        let total = records.len() as f64;
        let succeeded = records
            .iter()
            .filter(|r| r.status == "succeeded")
            .count() as f64;
        let rate = if total > 0.0 { succeeded / total } else { 0.0 };

        let score = 100.0 / (1.0 + (-10.0 * (rate - 0.5)).exp());
        let score = score.clamp(0.0, 100.0);

        DimensionScore {
            name: "success_rate".into(),
            score,
            weight: WEIGHT_SUCCESS,
            weighted: score * WEIGHT_SUCCESS,
            detail: json!({
                "total": total as u32,
                "succeeded": succeeded as u32,
                "rate": rate,
            }),
        }
    }

    /// 稳定性 25%：重试率越低分越高(60%) + 错误集中度(40%)。
    ///
    /// - 重试率：avg_retries=0 → 100, avg_retries=3 → 0
    /// - 错误集中度：失败都集中在同一错误说明问题明确（扣分少），
    ///   错误分散说明不稳定（扣分多）。
    fn score_stability(&self, records: &[TaskRecord]) -> DimensionScore {
        let total = records.len() as f64;
        let total_retries: u32 = records.iter().map(|r| r.retry_count).sum();
        let avg_retries = if total > 0.0 {
            total_retries as f64 / total
        } else {
            0.0
        };

        let retry_score = (100.0 - avg_retries * 33.3).clamp(0.0, 100.0);

        let errors: Vec<&str> = records
            .iter()
            .filter(|r| r.status == "failed")
            .filter_map(|r| r.error.as_deref())
            .collect();
        let unique_errors = errors
            .iter()
            .collect::<std::collections::HashSet<_>>()
            .len();
        let error_diversity = if errors.is_empty() {
            0.0
        } else {
            unique_errors as f64 / errors.len() as f64
        };
        let concentration_score = 100.0 - error_diversity * 50.0;

        let score = (retry_score * 0.6 + concentration_score * 0.4)
            .clamp(0.0, 100.0);

        DimensionScore {
            name: "stability".into(),
            score,
            weight: WEIGHT_STABILITY,
            weighted: score * WEIGHT_STABILITY,
            detail: json!({
                "avg_retries": avg_retries,
                "total_retries": total_retries,
                "unique_errors": unique_errors,
                "error_diversity": error_diversity,
            }),
        }
    }

    /// 效率 20%：成功执行耗时的变异系数(CV)(50%) + 平均耗时基准(50%)。
    ///
    /// - CV 越低越稳定高效：CV=0 → 100, CV=1 → 50, CV=2 → 0
    /// - 平均耗时基准：<5s=100, <30s=80, <60s=60, >60s=40
    fn score_efficiency(&self, records: &[TaskRecord]) -> DimensionScore {
        let durations: Vec<f64> = records
            .iter()
            .filter(|r| r.status == "succeeded")
            .map(|r| r.duration_ms as f64)
            .collect();

        if durations.is_empty() {
            return DimensionScore {
                name: "efficiency".into(),
                score: 0.0,
                weight: WEIGHT_EFFICIENCY,
                weighted: 0.0,
                detail: json!({"reason": "no_successful_runs"}),
            };
        }

        let mean = durations.iter().sum::<f64>() / durations.len() as f64;
        let variance = durations.iter().map(|d| (d - mean).powi(2)).sum::<f64>()
            / durations.len() as f64;
        let std_dev = variance.sqrt();
        let cv = if mean > 0.0 { std_dev / mean } else { 0.0 };

        let cv_score = (100.0 - cv * 50.0).clamp(0.0, 100.0);

        let mean_score = if mean < 5000.0 {
            100.0
        } else if mean < 30000.0 {
            80.0
        } else if mean < 60000.0 {
            60.0
        } else {
            40.0
        };

        let score = (cv_score * 0.5 + mean_score * 0.5).clamp(0.0, 100.0);

        DimensionScore {
            name: "efficiency".into(),
            score,
            weight: WEIGHT_EFFICIENCY,
            weighted: score * WEIGHT_EFFICIENCY,
            detail: json!({
                "mean_ms": mean,
                "std_dev": std_dev,
                "cv": cv,
                "min_ms": durations.iter().cloned().fold(f64::INFINITY, f64::min),
                "max_ms": durations.iter().cloned().fold(0.0, f64::max),
            }),
        }
    }

    /// 通用性 15%：参数多样性。
    ///
    /// diversity 越高说明技能适配多种输入 → 通用性好。
    fn score_generality(&self, records: &[TaskRecord]) -> DimensionScore {
        let param_sets: Vec<&Value> = records.iter().map(|r| &r.params).collect();

        let unique_params: std::collections::HashSet<String> = param_sets
            .iter()
            .map(|p| p.to_string())
            .collect();
        let diversity = if records.is_empty() {
            0.0
        } else {
            unique_params.len() as f64 / records.len() as f64
        };

        let score = (diversity * 100.0).clamp(0.0, 100.0);

        DimensionScore {
            name: "generality".into(),
            score,
            weight: WEIGHT_GENERALITY,
            weighted: score * WEIGHT_GENERALITY,
            detail: json!({
                "unique_param_sets": unique_params.len(),
                "total_runs": records.len(),
                "diversity_ratio": diversity,
            }),
        }
    }
}

/// 从 DuckDB 读取的执行记录（简化版，由 TaskLogRow 转换而来）。
#[derive(Debug, Clone)]
pub struct TaskRecord {
    pub status: String, // succeeded / failed / cancelled
    pub duration_ms: i64,
    pub retry_count: u32,
    pub error: Option<String>,
    pub params: serde_json::Value,
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_record(
        status: &str,
        duration_ms: i64,
        retry_count: u32,
        params: serde_json::Value,
    ) -> TaskRecord {
        TaskRecord {
            status: status.into(),
            duration_ms,
            retry_count,
            error: None,
            params,
        }
    }

    #[test]
    fn test_score_all_success() {
        let scorer = SkillScorer::new();
        let records = vec![
            make_record("succeeded", 3000, 0, json!({"url": "a"})),
            make_record("succeeded", 4000, 0, json!({"url": "b"})),
            make_record("succeeded", 3500, 0, json!({"url": "c"})),
        ];
        let result = scorer.score(&records);
        assert_eq!(result.sample_count, 3);
        assert_eq!(result.dimensions.len(), 4);
        // 全成功 + 低耗时 → 高分
        assert!(result.total_score > 50);
        assert!(result.qualified);
    }

    #[test]
    fn test_score_all_failed() {
        let scorer = SkillScorer::new();
        let records = vec![
            make_record("failed", 0, 2, json!({"url": "a"})),
            make_record("failed", 0, 3, json!({"url": "b"})),
        ];
        let result = scorer.score(&records);
        // 全失败 → 成功率维度极低
        let success_dim = result.dimensions.iter().find(|d| d.name == "success_rate").unwrap();
        assert!(success_dim.score < 50.0);
        assert!(!result.qualified);
    }

    #[test]
    fn test_score_empty() {
        let scorer = SkillScorer::new();
        let result = scorer.score(&[]);
        assert_eq!(result.sample_count, 0);
        assert!(!result.qualified);
    }
}
