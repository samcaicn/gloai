// Skill registry — adapted from safeopcapp.
//
// Atomically swap a running skill to a newer version, with inbox
// buffering for "needs review" proposals and rollback book for failed
// adopts.
//
// Scope:
//   - In-memory SkillRegistry shared across Tauri command layer.
//   - Inbox = list of SkillEvaluations scored 0.60-0.85, surfaced to UI.
//   - Atomic swap = both running and fallback versions kept in memory.

use std::collections::HashMap;
use std::sync::Mutex;

use serde::{Deserialize, Serialize};

use super::eval::{classify, Decision, SkillEvaluation};
use super::manifest::SkillManifest;

/// An inbox item surfaced to the UI.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InboxItem {
    pub proposal_id: String,
    pub skill_id: String,
    pub skill_name: String,
    pub skill_md: String,
    pub source: String,
    pub evaluation: SkillEvaluation,
    pub received_at: i64,
    pub decision: String,
}

/// Result of an adopt_proposal call.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AdoptOutcome {
    pub proposal_id: String,
    pub skill_id: String,
    pub decision: String,
    pub score: f32,
}

/// A versioned skill entry in the running set.
#[derive(Debug, Clone)]
struct VersionedSkill {
    pub version: u64,
    pub manifest: SkillManifest,
    pub content: String,
}

/// The skill registry: holds running versions and inbox proposals.
pub struct SkillRegistry {
    inner: Mutex<RegistryInner>,
}

#[derive(Default)]
struct RegistryInner {
    /// running skills: skill_id → (running version, fallback version)
    running: HashMap<String, (VersionedSkill, Option<VersionedSkill>)>,
    /// inbox of proposals needing review
    inbox: Vec<InboxItem>,
    /// bounded history of version changes (for "evolution timeline")
    history: Vec<(String, u64, u64, String)>, // (skill_id, from, to, timestamp)
}

/// Maximum history entries to keep.
const MAX_HISTORY: usize = 256;

impl SkillRegistry {
    pub fn new() -> Self {
        Self {
            inner: Mutex::new(RegistryInner::default()),
        }
    }

    /// Register a skill version. If the skill already exists, the old
    /// version becomes the fallback. Returns the new version number.
    pub fn register_version(
        &self,
        skill_id: &str,
        manifest: SkillManifest,
        content: String,
    ) -> u64 {
        let mut inner = self.inner.lock().unwrap();
        let new_version = inner
            .running
            .get(skill_id)
            .map(|(r, _)| r.version + 1)
            .unwrap_or(1);
        let new_entry = VersionedSkill {
            version: new_version,
            manifest,
            content: content.clone(),
        };
        if let Some((running, _)) = inner.running.remove(skill_id) {
            // Old running becomes fallback
            inner.running.insert(
                skill_id.to_string(),
                (new_entry, Some(running.clone())),
            );
            // Record history
            inner.history.push((
                skill_id.to_string(),
                running.version,
                new_version,
                chrono::Utc::now().to_rfc3339(),
            ));
            if inner.history.len() > MAX_HISTORY {
                inner.history.remove(0);
            }
        } else {
            inner
                .running
                .insert(skill_id.to_string(), (new_entry, None));
        }
        new_version
    }

    /// Get the running version of a skill.
    pub fn get_running(&self, skill_id: &str) -> Option<(u64, SkillManifest, String)> {
        let inner = self.inner.lock().unwrap();
        inner.running.get(skill_id).map(|(r, _)| {
            (r.version, r.manifest.clone(), r.content.clone())
        })
    }

    /// Atomically swap to a newer, server-evaluated version.
    pub fn adopt_proposal(
        &self,
        proposal_id: &str,
        skill_id: &str,
        evaluation: SkillEvaluation,
        manifest: SkillManifest,
        content: String,
    ) -> AdoptOutcome {
        let decision = classify(evaluation.total_score);
        let mut inner = self.inner.lock().unwrap();

        match decision {
            Decision::AutoAccept => {
                // Atomically swap
                let new_version = inner
                    .running
                    .get(skill_id)
                    .map(|(r, _)| r.version + 1)
                    .unwrap_or(1);
                let new_entry = VersionedSkill {
                    version: new_version,
                    manifest: manifest.clone(),
                    content: content.clone(),
                };
                if let Some((running, _)) = inner.running.remove(skill_id) {
                    inner.running.insert(
                        skill_id.to_string(),
                        (new_entry, Some(running.clone())),
                    );
                    inner.history.push((
                        skill_id.to_string(),
                        running.version,
                        new_version,
                        chrono::Utc::now().to_rfc3339(),
                    ));
                    if inner.history.len() > MAX_HISTORY {
                        inner.history.remove(0);
                    }
                } else {
                    inner
                        .running
                        .insert(skill_id.to_string(), (new_entry, None));
                }
                AdoptOutcome {
                    proposal_id: proposal_id.to_string(),
                    skill_id: skill_id.to_string(),
                    decision: "auto_accepted".to_string(),
                    score: evaluation.total_score,
                }
            }
            Decision::NeedsReview => {
                // Add to inbox
                let item = InboxItem {
                    proposal_id: proposal_id.to_string(),
                    skill_id: skill_id.to_string(),
                    skill_name: manifest.name.clone(),
                    skill_md: content,
                    source: "autoskill".to_string(),
                    evaluation: evaluation.clone(),
                    received_at: chrono::Utc::now().timestamp(),
                    decision: "needs_review".to_string(),
                };
                inner.inbox.push(item);
                AdoptOutcome {
                    proposal_id: proposal_id.to_string(),
                    skill_id: skill_id.to_string(),
                    decision: "needs_review".to_string(),
                    score: evaluation.total_score,
                }
            }
            Decision::Reject => AdoptOutcome {
                proposal_id: proposal_id.to_string(),
                skill_id: skill_id.to_string(),
                decision: "rejected".to_string(),
                score: evaluation.total_score,
            },
        }
    }

    /// Rollback to the previous version of a skill.
    pub fn rollback(&self, skill_id: &str) -> Option<u64> {
        let mut inner = self.inner.lock().unwrap();
        if let Some((running, Some(fallback))) = inner.running.remove(skill_id) {
            let fallback_version = fallback.version;
            // Promote fallback to running, old running discarded
            inner.running.insert(
                skill_id.to_string(),
                (fallback, None),
            );
            inner.history.push((
                skill_id.to_string(),
                running.version,
                fallback_version,
                chrono::Utc::now().to_rfc3339(),
            ));
            if inner.history.len() > MAX_HISTORY {
                inner.history.remove(0);
            }
            Some(fallback_version)
        } else {
            None
        }
    }

    /// List inbox items.
    pub fn list_inbox(&self) -> Vec<InboxItem> {
        let inner = self.inner.lock().unwrap();
        inner.inbox.clone()
    }

    /// Get version history for a skill.
    pub fn get_history(&self, skill_id: &str) -> Vec<(String, u64, u64, String)> {
        let inner = self.inner.lock().unwrap();
        inner
            .history
            .iter()
            .filter(|(id, _, _, _)| id == skill_id)
            .cloned()
            .collect()
    }

    /// List all registered skills.
    pub fn list_skills(&self) -> Vec<(String, u64, String)> {
        let inner = self.inner.lock().unwrap();
        inner
            .running
            .iter()
            .map(|(id, (r, _))| (id.clone(), r.version, r.manifest.name.clone()))
            .collect()
    }
}
