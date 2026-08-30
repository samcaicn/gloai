// AutoSkill self-evolution engine — adapted from safeopcapp.
//
// 5-step pipeline: LogMiner → PatternFinder → FlowReconstructor →
//                  ParamGeneralizer → ScoreCheck
//
// State machine: Monitoring → Drafting → Scoring → PendingConfirm →
//                Upgrading → Watching → Running / Rollback
//
// Pure local implementation, no LLM calls. ParamGeneralizer uses regex rules.

pub mod param_generalizer;
pub mod pattern_miner;
pub mod pipeline;
pub mod state_machine;

pub use pattern_miner::jaccard_similarity;
pub use pipeline::AutoSkillPipeline;
pub use state_machine::{UpgradeState, UpgradeStateMachine};

use std::sync::Arc;

use rusqlite::OptionalExtension;

use crate::skill::eval::SkillEvalEngine;
use crate::storage::{DraftRecord, SkillVersion, Storage};

/// Errors from the AutoSkill engine.
#[derive(Debug, thiserror::Error)]
pub enum AutoSkillError {
    #[error("Storage error: {0}")]
    Storage(#[from] crate::storage::StorageError),

    #[error("SQLite error: {0}")]
    Sqlite(#[from] rusqlite::Error),

    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),

    #[error("Insufficient data to generate draft")]
    InsufficientData,

    #[error("Draft score too low: {0}")]
    ScoreTooLow(i32),

    #[error("Draft not found: {0}")]
    NotFound(String),

    #[error("Invalid state transition: {0} -> {1}")]
    InvalidTransition(String, String),

    #[error("Serialization error: {0}")]
    Serde(#[from] serde_json::Error),
}

pub type Result<T> = std::result::Result<T, AutoSkillError>;

/// Candidate skills identified for optimization.
#[derive(Debug, Clone, serde::Serialize)]
pub struct OptimizationCandidate {
    pub scene: String,
    pub skill_id: String,
    pub current_version: String,
    pub current_score: i32,
    pub run_count: usize,
    pub failure_rate: f64,
    pub reason: String,
}

/// Result of a draft generation run.
#[derive(Debug, Clone, serde::Serialize)]
pub struct DraftResult {
    pub draft_id: String,
    pub scene: String,
    pub skill_id: String,
    pub draft_version: String,
    pub content: String, // Generated SKILL.md
    pub new_score: i32,
    pub old_score: i32,
    pub optimization_points: Vec<String>,
    pub qualified: bool,
}

/// Merge candidate: a group of similar skills that can be merged.
#[derive(Debug, Clone, serde::Serialize)]
pub struct MergeCandidate {
    pub scene: String,
    pub skill_ids: Vec<String>,
    pub similarity: f64,
    pub action_signature: String,
    pub total_runs: usize,
}

/// The main AutoSkill engine.
pub struct AutoSkillEngine {
    storage: Arc<Storage>,
    eval: Arc<SkillEvalEngine>,
    pipeline: AutoSkillPipeline,
}

impl AutoSkillEngine {
    pub fn new(storage: Arc<Storage>, eval: Arc<SkillEvalEngine>) -> Self {
        let pipeline = AutoSkillPipeline::new(storage.clone());
        Self {
            storage,
            eval,
            pipeline,
        }
    }

    /// Scan for optimization candidates.
    pub async fn scan_for_optimization(
        &self,
        scene: &str,
    ) -> Result<Vec<OptimizationCandidate>> {
        self.pipeline.scan_candidates(scene).await
    }

    /// Generate a draft for a specific skill.
    pub async fn generate_draft(
        &self,
        scene: &str,
        skill_id: &str,
    ) -> Result<DraftResult> {
        self.pipeline
            .generate(scene, skill_id, &self.eval)
            .await
    }

    /// Scan for merge candidates.
    pub async fn scan_merge_candidates(
        &self,
        scene: &str,
    ) -> Result<Vec<MergeCandidate>> {
        self.pipeline.scan_merge_candidates(scene).await
    }

    /// Generate a merged draft.
    pub async fn generate_merge_draft(
        &self,
        scene: &str,
        skill_ids: &[String],
    ) -> Result<DraftResult> {
        self.pipeline
            .generate_merge_draft(scene, skill_ids, &self.eval)
            .await
    }

    /// Confirm an upgrade: backup old version, activate new version, set watching.
    pub async fn confirm_upgrade(&self, draft_id: &str) -> Result<()> {
        // Read draft
        let draft = self.get_draft(draft_id)?;
        let content = draft.content.ok_or_else(|| {
            AutoSkillError::NotFound(format!("draft {} content is empty", draft_id))
        })?;

        // Backup old version
        if let Some(old) = self.get_active_version(&draft.scene, &draft.skill_id)? {
            self.upsert_version(SkillVersion {
                scene: draft.scene.clone(),
                skill_id: draft.skill_id.clone(),
                version: old.version,
                status: "rollback".to_string(),
                score: old.score,
                content: old.content,
                changelog: Some(format!("Backup before upgrading to {}", draft.draft_version)),
                activated_at: Some(chrono::Utc::now().to_rfc3339()),
            })?;
        }

        // Activate new version (watching status)
        self.upsert_version(SkillVersion {
            scene: draft.scene,
            skill_id: draft.skill_id,
            version: draft.draft_version,
            status: "watching".to_string(),
            score: draft.new_score,
            content: Some(content),
            changelog: Some(format!("AutoSkill draft {} confirmed", draft_id)),
            activated_at: Some(chrono::Utc::now().to_rfc3339()),
        })?;

        // Update draft status
        self.update_draft_status(draft_id, "watching")?;

        Ok(())
    }

    /// Rollback if the new version has degraded beyond threshold.
    pub async fn rollback_if_degraded(
        &self,
        draft_id: &str,
        threshold: i32,
    ) -> Result<bool> {
        let draft = self.get_draft(draft_id)?;
        if draft.status != "watching" {
            return Ok(false);
        }

        let new_score = draft.new_score.unwrap_or(0);
        let old_score = draft.old_score.unwrap_or(0);

        // Score hasn't dropped enough to trigger rollback
        if old_score - new_score <= threshold {
            return Ok(false);
        }

        // Find and restore the backup (rollback version)
        if let Some(backup) = self.get_rollback_version(&draft.scene, &draft.skill_id)? {
            self.upsert_version(SkillVersion {
                scene: backup.scene,
                skill_id: backup.skill_id,
                version: backup.version,
                status: "active".to_string(),
                score: backup.score,
                content: backup.content,
                changelog: backup.changelog,
                activated_at: Some(chrono::Utc::now().to_rfc3339()),
            })?;
        }

        // Mark current as rollback
        self.update_draft_status(draft_id, "rollback")?;

        // Update the version record
        self.upsert_version(SkillVersion {
            scene: draft.scene,
            skill_id: draft.skill_id,
            version: draft.draft_version,
            status: "rollback".to_string(),
            score: Some(new_score),
            content: None,
            changelog: Some(format!(
                "Rolled back: score dropped from {} to {} (threshold {})",
                old_score, new_score, threshold
            )),
            activated_at: Some(chrono::Utc::now().to_rfc3339()),
        })?;

        Ok(true)
    }

    /// Batch rollback check across all watching drafts.
    pub async fn rollback_all_degraded(&self, threshold: i32) -> Result<usize> {
        let watching = self.list_drafts_by_status("watching")?;
        let mut rolled_back = 0;
        for draft in &watching {
            match self.rollback_if_degraded(&draft.id, threshold).await {
                Ok(true) => {
                    rolled_back += 1;
                    log::info!("[autoskill] draft {} triggered rollback", draft.id);
                }
                Ok(false) => {}
                Err(e) => {
                    log::warn!("[autoskill] draft {} rollback check failed: {}", draft.id, e);
                }
            }
        }
        Ok(rolled_back)
    }

    // ---- Storage helpers ----

    fn get_draft(&self, id: &str) -> Result<DraftRecord> {
        let conn = self.storage.conn();
        let mut stmt = conn.prepare(
            "SELECT id, scene, skill_id, draft_version, source, status,
                    content, old_score, new_score, optimization_points, created_at
             FROM skill_auto_iter_draft WHERE id = ?1",
        )?;
        let result = stmt
            .query_row(rusqlite::params![id], |row| {
                Ok(DraftRecord {
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
                    created_at: row.get(10)?,
                })
            })
            .optional()
            .map_err(|e| AutoSkillError::Storage(e.into()))?;
        match result {
            Some(d) => Ok(d),
            None => Err(AutoSkillError::NotFound(id.to_string())),
        }
    }

    fn get_active_version(&self, scene: &str, skill_id: &str) -> Result<Option<SkillVersion>> {
        let conn = self.storage.conn();
        let mut stmt = conn.prepare(
            "SELECT scene, skill_id, version, status, score, content, changelog, activated_at
             FROM skill_versions WHERE scene = ?1 AND skill_id = ?2 AND status = 'active'",
        )?;
        let result = stmt
            .query_row(rusqlite::params![scene, skill_id], |row| {
                Ok(SkillVersion {
                    scene: row.get(0)?,
                    skill_id: row.get(1)?,
                    version: row.get(2)?,
                    status: row.get(3)?,
                    score: row.get(4)?,
                    content: row.get(5)?,
                    changelog: row.get(6)?,
                    activated_at: row.get(7)?,
                })
            })
            .optional()
            .map_err(|e| AutoSkillError::Storage(e.into()))?;
        Ok(result)
    }

    fn get_rollback_version(&self, scene: &str, skill_id: &str) -> Result<Option<SkillVersion>> {
        let conn = self.storage.conn();
        let mut stmt = conn.prepare(
            "SELECT scene, skill_id, version, status, score, content, changelog, activated_at
             FROM skill_versions
             WHERE scene = ?1 AND skill_id = ?2 AND status = 'rollback'
             ORDER BY activated_at DESC LIMIT 1",
        )?;
        let result = stmt
            .query_row(rusqlite::params![scene, skill_id], |row| {
                Ok(SkillVersion {
                    scene: row.get(0)?,
                    skill_id: row.get(1)?,
                    version: row.get(2)?,
                    status: row.get(3)?,
                    score: row.get(4)?,
                    content: row.get(5)?,
                    changelog: row.get(6)?,
                    activated_at: row.get(7)?,
                })
            })
            .optional()
            .map_err(|e| AutoSkillError::Storage(e.into()))?;
        Ok(result)
    }

    fn upsert_version(&self, version: SkillVersion) -> Result<()> {
        let conn = self.storage.conn();
        conn.execute(
            "INSERT INTO skill_versions (scene, skill_id, version, status, score, content, changelog, activated_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
             ON CONFLICT(scene, skill_id, version) DO UPDATE SET
                status = excluded.status,
                score = excluded.score,
                content = excluded.content,
                changelog = excluded.changelog,
                activated_at = excluded.activated_at",
            rusqlite::params![
                &version.scene,
                &version.skill_id,
                &version.version,
                &version.status,
                version.score,
                &version.content,
                &version.changelog,
                &version.activated_at,
            ],
        )
        .map_err(|e| AutoSkillError::Storage(e.into()))?;
        Ok(())
    }

    fn update_draft_status(&self, id: &str, status: &str) -> Result<()> {
        let conn = self.storage.conn();
        conn.execute(
            "UPDATE skill_auto_iter_draft SET status = ?1 WHERE id = ?2",
            rusqlite::params![status, id],
        )
        .map_err(|e| AutoSkillError::Storage(e.into()))?;
        Ok(())
    }

    fn list_drafts_by_status(&self, status: &str) -> Result<Vec<DraftRecord>> {
        let conn = self.storage.conn();
        let mut stmt = conn.prepare(
            "SELECT id, scene, skill_id, draft_version, source, status,
                    content, old_score, new_score, optimization_points, created_at
             FROM skill_auto_iter_draft WHERE status = ?1",
        )?;
        let rows = stmt
            .query_map(rusqlite::params![status], |row| {
                Ok(DraftRecord {
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
                    created_at: row.get(10)?,
                })
            })
            .map_err(|e| AutoSkillError::Storage(e.into()))?;
        let mut drafts = Vec::new();
        for row in rows {
            drafts.push(row.map_err(|e| AutoSkillError::Storage(e.into()))?);
        }
        Ok(drafts)
    }
}
