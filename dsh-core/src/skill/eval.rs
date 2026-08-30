// Skill evaluation — adapted from safeopcapp.
//
// 5-dimension evaluation vector:
//   - safety       : is this skill going to do something dangerous?
//   - success_rate : success rate in dry-run
//   - generality   : does it cover the cases the proposer claims?
//   - uniqueness   : Jaccard distance to existing skills (1.0 = no duplicate)
//   - resource_cost: lower cost scores higher

use serde::{Deserialize, Serialize};

/// Five-dimension evaluation vector.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SkillEvaluation {
    pub safety: f32,
    pub success_rate: f32,
    pub generality: f32,
    pub uniqueness: f32,
    pub resource_cost: f32,
    pub total_score: f32,
    #[serde(default)]
    pub verdict: String,
    #[serde(default)]
    pub degraded: bool,
}

impl SkillEvaluation {
    /// Compute total score as a weighted average.
    pub fn compute_total_score(&mut self) {
        // Weights: safety=0.25, success_rate=0.30, generality=0.20,
        //          uniqueness=0.15, resource_cost=0.10
        self.total_score = self.safety * 0.25
            + self.success_rate * 0.30
            + self.generality * 0.20
            + self.uniqueness * 0.15
            + self.resource_cost * 0.10;
    }

    /// Get total score (computed if not already set).
    pub fn total_score(&self) -> f32 {
        if self.total_score > 0.0 {
            self.total_score
        } else {
            self.safety * 0.25
                + self.success_rate * 0.30
                + self.generality * 0.20
                + self.uniqueness * 0.15
                + self.resource_cost * 0.10
        }
    }
}

/// Decision band based on total score.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Decision {
    AutoAccept,
    NeedsReview,
    Reject,
}

/// Classify an evaluation into a decision band.
pub fn classify(score: f32) -> Decision {
    if score >= 0.85 {
        Decision::AutoAccept
    } else if score >= 0.60 {
        Decision::NeedsReview
    } else {
        Decision::Reject
    }
}

/// The evaluation engine that scores skill drafts.
pub struct SkillEvalEngine;

impl SkillEvalEngine {
    pub fn new() -> Self {
        Self
    }

    /// Score a skill based on its content and metadata.
    /// Simplified implementation — in production this would run dry-runs
    /// against a test suite.
    pub fn evaluate(&self, content: &str, _context: &str) -> SkillEvaluation {
        // Simple heuristic scoring based on content analysis
        let has_params = content.contains("{{") && content.contains("}}");
        let has_steps = content.contains("## Execution Steps") || content.contains("## 执行步骤");
        let _has_params_section = content.contains("## Parameters") || content.contains("## 参数");
        let step_count = content.lines().filter(|l| l.trim().starts_with(|c: char| c.is_ascii_digit())).count();

        let safety = 0.9f32; // Assume safe by default
        let success_rate = if has_steps { 0.85 } else { 0.5 };
        let generality = if has_params { 0.8 } else { 0.5 };
        let uniqueness = 0.7f32; // Would compare against existing skills
        let resource_cost = if step_count > 10 { 0.5 } else { 0.8 };

        let mut eval = SkillEvaluation {
            safety,
            success_rate,
            generality,
            uniqueness,
            resource_cost,
            total_score: 0.0,
            verdict: String::new(),
            degraded: false,
        };
        eval.compute_total_score();
        eval
    }

    /// Get the latest recorded score for a skill.
    /// Returns None if no score has been recorded.
    pub fn get_latest_score(
        &self,
        _scene: &str,
        _skill_id: &str,
    ) -> Result<Option<SkillEvalRecord>, crate::storage::StorageError> {
        // Query from skill_versions table
        Ok(None)
    }
}

/// Stored evaluation record for a skill.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SkillEvalRecord {
    pub skill_id: String,
    pub version: String,
    pub total_score: i32,
    pub safety: f32,
    pub success_rate: f32,
    pub generality: f32,
    pub uniqueness: f32,
    pub resource_cost: f32,
    pub evaluated_at: String,
}
