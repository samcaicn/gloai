// Copyright (c) 2026 MeeJoy
//
// 技能评估维度定义与结果类型。
//
// 4 维度加权打分模型：
//   - 执行成功率 40% (WEIGHT_SUCCESS)
//   - 流程稳定性 25% (WEIGHT_STABILITY)
//   - 执行效率   20% (WEIGHT_EFFICIENCY)
//   - 通用性     15% (WEIGHT_GENERALITY)
// 达标阈值 85 分（QUALIFY_THRESHOLD）。

use serde::{Deserialize, Serialize};

/// 4 维度权重
pub const WEIGHT_SUCCESS: f64 = 0.40; // 执行成功率 40%
pub const WEIGHT_STABILITY: f64 = 0.25; // 流程稳定性 25%
pub const WEIGHT_EFFICIENCY: f64 = 0.20; // 执行效率 20%
pub const WEIGHT_GENERALITY: f64 = 0.15; // 通用性 15%

/// 达标阈值
pub const QUALIFY_THRESHOLD: i32 = 85;

/// 单维度得分
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DimensionScore {
    pub name: String,
    pub score: f64, // 0-100
    pub weight: f64,
    pub weighted: f64, // score * weight
    pub detail: serde_json::Value, // 详细计算数据
}

/// 评估结果
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScoreResult {
    pub scene: String,
    pub skill_id: String,
    pub skill_version: String,
    pub total_score: i32, // 0-100 加权总分
    pub dimensions: Vec<DimensionScore>,
    pub sample_count: usize, // 采样执行次数
    pub qualified: bool, // 是否达标 (>=85)
    pub eval_detail: serde_json::Value,
}
