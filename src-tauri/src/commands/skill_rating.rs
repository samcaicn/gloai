use serde::Deserialize;
use tauri::AppHandle;

use crate::commands::legacy::open_app_db;
use crate::hermes::memory_evolution::{self, MemoryOutcome, WriteResult};

#[derive(Deserialize, Debug)]
pub struct SkillRatingInput {
    pub skill_id: String,
    /// 1–5 star rating
    pub rating: u8,
    pub session_id: Option<String>,
}

/// Record a user's star rating (1-5) for a skill execution.
/// Writes a memory entry with task_type "skill_feedback" so the
/// autoskill / evolution engine can consume it during analysis.
#[tauri::command]
pub async fn submit_skill_rating(
    app: AppHandle,
    input: SkillRatingInput,
) -> Result<(), String> {
    let rating = input.rating.clamp(1, 5);
    log::info!(
        "[skill_rating] submit: skill={}, rating={}/5",
        input.skill_id,
        rating,
    );

    let outcome = MemoryOutcome {
        success: rating >= 3,
        task_type: "skill_feedback".to_string(),
        summary: format!("Skill {} rated {}/5", input.skill_id, rating),
        content: format!(
            "Skill: {}, Rating: {}/5{}",
            input.skill_id,
            rating,
            input
                .session_id
                .as_ref()
                .map(|s| format!(", Session: {}", s))
                .unwrap_or_default(),
        ),
        tool_used: None,
        command: None,
        time_taken_ms: None,
        user_feedback: Some(format!("{}/5", rating)),
        session_id: input.session_id.clone(),
        channel_id: None,
        workspace_path: None,
    };

    let db = match open_app_db(&app) {
        Ok(db) => db,
        Err(e) => {
            log::warn!(
                "[skill_rating] cannot open db, rating stored locally only: {}",
                e
            );
            return Ok(());
        }
    };

    match memory_evolution::write_outcome(&db, &outcome) {
        Ok(WriteResult::Created { id, version }) => {
            log::debug!("[skill_rating] memory created: id={}, v={}", id, version);
        }
        Ok(WriteResult::Upgraded {
            id,
            version,
            parent_id,
            parent_version,
        }) => {
            log::debug!(
                "[skill_rating] memory upgraded: id={}, v={}, parent={}:{}",
                id,
                version,
                parent_id,
                parent_version
            );
        }
        Ok(WriteResult::Merged { id, version }) => {
            log::debug!("[skill_rating] memory merged: id={}, v={}", id, version);
        }
        Err(e) => {
            log::warn!("[skill_rating] write_outcome failed: {}", e);
        }
    }

    Ok(())
}
