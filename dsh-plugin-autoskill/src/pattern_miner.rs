// Log mining + pattern discovery — adapted from safeopcapp.
//
// LogMiner: mine successful execution traces from worker_task_log.
// PatternFinder: cluster similar action sequences.
// FlowReconstructor: remove consecutive duplicate steps.

use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::sync::Arc;

use crate::storage::Storage;

/// A successful execution trace mined from logs.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExecutionTrace {
    pub task_id: String,
    pub skill_id: String,
    pub params: serde_json::Value,
    pub duration_ms: i64,
    pub steps: Vec<TraceStep>,
}

/// A single step in an execution trace.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct TraceStep {
    pub action: String,
    pub target: String,
    pub value: Option<String>,
}

/// A discovered pattern (cluster of similar traces).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Pattern {
    pub steps: Vec<TraceStep>,
    pub frequency: usize,
    pub variants: Vec<Vec<TraceStep>>,
}

pub struct PatternMiner {
    storage: Arc<Storage>,
}

impl PatternMiner {
    pub fn new(storage: Arc<Storage>) -> Self {
        Self { storage }
    }

    /// LogMine: mine successful execution traces for a given skill.
    pub fn mine_traces(
        &self,
        scene: &str,
        skill_id: &str,
        limit: usize,
    ) -> Result<Vec<ExecutionTrace>, crate::storage::StorageError> {
        let conn = self.storage.conn();
        let mut stmt = conn.prepare(
            "SELECT id, skill_id, params, duration_ms, result
             FROM worker_task_log
             WHERE scene = ?1 AND skill_id = ?2 AND status = 'succeeded'
             ORDER BY created_at DESC LIMIT ?3",
        )?;

        let rows = stmt.query_map(
            rusqlite::params![scene, skill_id, limit as i64],
            |row| {
                let task_id: String = row.get(0)?;
                let skill_id: String = row.get(1)?;
                let params_str: String = row.get::<_, Option<String>>(2)?.unwrap_or_default();
                let duration_ms: i64 = row.get::<_, Option<i64>>(3)?.unwrap_or(0);
                let result_str: String = row.get::<_, Option<String>>(4)?.unwrap_or_default();

                let params: serde_json::Value =
                    serde_json::from_str(&params_str).unwrap_or_default();
                let result: serde_json::Value =
                    serde_json::from_str(&result_str).unwrap_or_default();

                // Extract steps from result JSON
                let steps: Vec<TraceStep> = result
                    .get("steps")
                    .and_then(|s| serde_json::from_value(s.clone()).ok())
                    .unwrap_or_default();

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
        for row in rows {
            if let Ok(t) = row {
                traces.push(t);
            }
        }
        Ok(traces)
    }

    /// PatternFinder: cluster similar action sequences.
    /// Uses concatenated action names as the clustering key.
    pub fn find_patterns(&self, traces: &[ExecutionTrace]) -> Vec<Pattern> {
        if traces.is_empty() {
            return vec![];
        }

        let mut clusters: std::collections::HashMap<String, Vec<&ExecutionTrace>> =
            std::collections::HashMap::new();

        for trace in traces {
            let key: String = trace
                .steps
                .iter()
                .map(|s| s.action.clone())
                .collect::<Vec<_>>()
                .join("|");
            clusters.entry(key).or_default().push(trace);
        }

        clusters
            .into_iter()
            .map(|(_, group)| {
                let base = &group[0].steps;
                let frequency = group.len();
                let variants: Vec<Vec<TraceStep>> =
                    group.iter().map(|t| t.steps.clone()).collect();
                Pattern {
                    steps: base.clone(),
                    frequency,
                    variants,
                }
            })
            .collect()
    }

    /// FlowReconstructor: remove consecutive duplicate steps.
    pub fn reconstruct_flow(&self, pattern: &Pattern) -> Vec<TraceStep> {
        let mut result: Vec<TraceStep> = Vec::new();
        for step in &pattern.steps {
            if let Some(last) = result.last() {
                if last.action == step.action && last.target == step.target {
                    continue;
                }
            }
            result.push(step.clone());
        }
        result
    }
}

/// Calculate Jaccard similarity between two sets.
pub fn jaccard_similarity(a: &HashSet<String>, b: &HashSet<String>) -> f64 {
    if a.is_empty() && b.is_empty() {
        return 1.0;
    }
    let intersection = a.intersection(b).count();
    let union = a.len() + b.len() - intersection;
    if union == 0 {
        return 0.0;
    }
    intersection as f64 / union as f64
}
