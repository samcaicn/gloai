// Copyright (c) 2026 MeeJoy
//
// 日志挖掘 + 模式发现。
//
// LogMiner: 从 worker_task_log 挖掘 status=succeeded 的执行轨迹。
// PatternFinder: 按 action 序列聚类相似流程。
// FlowReconstructor: 移除连续重复步骤精简流程。

use serde::{Deserialize, Serialize};
use std::sync::Arc;

use crate::storage::DuckDBPool;

/// 从执行日志挖掘的成功执行链。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExecutionTrace {
    pub task_id: String,
    pub skill_id: String,
    pub params: serde_json::Value,
    pub duration_ms: i64,
    pub steps: Vec<TraceStep>,
}

/// 单步执行轨迹（点击 / 输入 / 滚动 / 等待等）。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TraceStep {
    pub action: String, // click / type / scroll / wait / ...
    pub target: String, // 元素选择器
    pub value: Option<String>,
}

/// 模式聚类结果。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Pattern {
    pub steps: Vec<TraceStep>, // 通用化后的步骤
    pub frequency: usize,      // 出现次数
    pub variants: Vec<Vec<TraceStep>>, // 变体
}

pub struct PatternMiner {
    db: Arc<DuckDBPool>,
}

impl PatternMiner {
    pub fn new(db: Arc<DuckDBPool>) -> Self {
        Self { db }
    }

    /// LogMiner：从 worker_task_log 挖掘成功执行链。
    ///
    /// 查询 status=succeeded 的记录，解析 result 中的 steps 数组。
    /// 按 created_at 倒序取最近 limit 条。
    pub async fn mine_traces(
        &self,
        scene: &str,
        skill_id: &str,
        limit: usize,
    ) -> Result<Vec<ExecutionTrace>, duckdb::Error> {
        let conn = self.db.get_conn();
        let mut stmt = conn.prepare(
            "SELECT CAST(id AS TEXT), skill_id, CAST(params AS TEXT), duration_ms, CAST(result AS TEXT)
             FROM worker_task_log
             WHERE scene = ?1 AND skill_id = ?2 AND status = 'succeeded'
             ORDER BY created_at DESC LIMIT ?3",
        )?;

        let rows = stmt.query_map(
            duckdb::params![scene, skill_id, limit as i64],
            |row| {
                let task_id: String = row.get(0)?;
                let skill_id: String = row.get(1)?;
                let params_str: String = row.get(2)?;
                let duration_ms: i64 = row.get::<_, Option<i64>>(3)?.unwrap_or(0);
                let result_str: String = row.get(4)?;

                let params: serde_json::Value =
                    serde_json::from_str(&params_str).unwrap_or_default();
                let result: serde_json::Value =
                    serde_json::from_str(&result_str).unwrap_or_default();

                // 从 result 中提取 steps（假设 result.steps 是数组）
                // 如果 result 不含 steps 字段（如 pipeline 普通技能记录），用合成 trace 占位
                let steps: Vec<TraceStep> = result
                    .get("steps")
                    .and_then(|s| serde_json::from_value(s.clone()).ok())
                    .unwrap_or_else(|| {
                        vec![TraceStep {
                            action: "execute".to_string(),
                            target: skill_id.clone(),
                            value: None,
                        }]
                    });

                Ok(ExecutionTrace {
                    task_id,
                    skill_id,
                    params,
                    duration_ms,
                    steps,
                })
            },
        )?;

        let mut traces = Vec::new();
        for t in rows.flatten() {
            traces.push(t);
        }
        Ok(traces)
    }

    /// PatternFinder：聚类相似流程。
    ///
    /// 简单实现：按 action 序列的拼接字符串作为聚类 key，
    /// 相同 key 的 trace 归为同一模式。
    pub fn find_patterns(&self, traces: &[ExecutionTrace]) -> Vec<Pattern> {
        if traces.is_empty() {
            return vec![];
        }

        let mut clusters: std::collections::HashMap<String, Vec<&ExecutionTrace>> =
            std::collections::HashMap::new();

        for trace in traces {
            // 用 action 序列作为聚类 key
            let key: String = trace
                .steps
                .iter()
                .map(|s| s.action.clone())
                .collect::<Vec<_>>()
                .join("|");
            clusters.entry(key).or_default().push(trace);
        }

        // 每个聚类生成一个 Pattern
        clusters.into_values().map(|group| {
                // 取第一个变体作为基准（频率最高的聚类中第一个 trace）
                let base = &group[0].steps;
                let frequency = group.len();
                let variants: Vec<Vec<TraceStep>> = group.iter().map(|t| t.steps.clone()).collect();
                Pattern {
                    steps: base.clone(),
                    frequency,
                    variants,
                }
            })
            .collect()
    }

    /// FlowReconstructor：精简冗余步骤。
    ///
    /// 简单实现：移除连续重复的相同 action+target 步骤。
    pub fn reconstruct_flow(&self, pattern: &Pattern) -> Vec<TraceStep> {
        let mut result: Vec<TraceStep> = Vec::new();
        for step in &pattern.steps {
            if let Some(last) = result.last() {
                if last.action == step.action && last.target == step.target {
                    continue; // 跳过连续重复
                }
            }
            result.push(step.clone());
        }
        result
    }
}
