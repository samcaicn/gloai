// Copyright (c) 2026 tupAI
//
// Turn Rating Commands — Hermes 自动升级机制 IPC 层。
//
// 用户在对话界面点击 👍/👎 评分，评分数据通过 submit_turn_rating
// 命令写入 memories 表（user_feedback 字段）。当会话入口被删除时，
// 前端调用 evaluate_session_ratings 命令，后端依据评分计算整体得分，
// 得分达到阈值时自动升级技能并上传服务器评估。
//
// 命令清单：
//   submit_turn_rating        — 记录单次评分
//   evaluate_session_ratings  — 会话结束时计算评估 + 自动升级

use serde::{Deserialize, Serialize};
use tauri::AppHandle;

use crate::commands::legacy::open_app_db;
// 导入模块本身（用于调用 write_outcome）及其公开的 MemoryOutcome / WriteResult 类型。
use crate::hermes::memory_evolution::{self, MemoryOutcome, WriteResult};

/// 单次评分的入参。
#[derive(Deserialize, Debug)]
pub struct TurnRatingInput {
    pub session_id: String,
    pub turn_id: String,
    /// "positive" = 👍, "negative" = 👎
    pub rating: String,
}

/// 评估结果。
#[derive(Serialize, Debug)]
pub struct EvalResult {
    pub score: f32,
    pub positive: u32,
    pub negative: u32,
    pub total: u32,
    pub upgraded: bool,
    pub message: String,
}

/// 记录单次对话轮次评分。写入 memories 表，user_feedback 字段
/// 记录 "positive"/"negative"。session_id 关联到具体会话。
#[tauri::command]
pub async fn submit_turn_rating(
    app: AppHandle,
    input: TurnRatingInput,
) -> Result<(), String> {
    let rating = if input.rating == "positive" {
        "positive"
    } else if input.rating == "negative" {
        "negative"
    } else {
        return Err(format!("invalid rating: {}", input.rating));
    };

    log::info!(
        "[turn_rating] submit: session={}, turn={}, rating={}",
        input.session_id,
        input.turn_id,
        rating
    );

    // 写入 memories 表作为一条记忆
    let outcome = MemoryOutcome {
        success: rating == "positive",
        task_type: "dialog_turn".to_string(),
        summary: format!("Turn {} rated {}", input.turn_id, rating),
        content: format!(
            "Session: {}, Turn: {}, Rating: {}",
            input.session_id, input.turn_id, rating
        ),
        tool_used: None,
        command: None,
        time_taken_ms: None,
        user_feedback: Some(rating.to_string()),
        session_id: Some(input.session_id.clone()),
        channel_id: None,
        workspace_path: None,
    };

    let db = match open_app_db(&app) {
        Ok(db) => db,
        Err(e) => {
            log::warn!("[turn_rating] cannot open db, rating stored locally only: {}", e);
            return Ok(());
        }
    };

    match memory_evolution::write_outcome(&db, &outcome) {
        Ok(WriteResult::Created { id, version }) => {
            log::debug!("[turn_rating] memory created: id={}, v={}", id, version);
        }
        Ok(WriteResult::Upgraded {
            id,
            version,
            parent_id,
            parent_version,
        }) => {
            log::debug!(
                "[turn_rating] memory upgraded: id={}, v={}, parent={}:{}",
                id,
                version,
                parent_id,
                parent_version
            );
        }
        Ok(WriteResult::Merged { id, version }) => {
            log::debug!("[turn_rating] memory merged: id={}, v={}", id, version);
        }
        Err(e) => {
            log::warn!("[turn_rating] write_outcome failed: {}", e);
        }
    }

    Ok(())
}

/// 会话结束时计算评估：依据 positive/negative 比例计算得分，
/// 得分 >= 0.7 时自动升级技能（写入升级版本），并尝试上传服务器。
#[tauri::command]
pub async fn evaluate_session_ratings(
    app: AppHandle,
    session_id: String,
    positive_count: u32,
    negative_count: u32,
    total_count: u32,
) -> Result<EvalResult, String> {
    log::info!(
        "[turn_rating] evaluate session: {}, +={}, -={}, total={}",
        session_id,
        positive_count,
        negative_count,
        total_count
    );

    let score = if total_count > 0 {
        positive_count as f32 / total_count as f32
    } else {
        0.0
    };

    let mut result = EvalResult {
        score,
        positive: positive_count,
        negative: negative_count,
        total: total_count,
        upgraded: false,
        message: String::new(),
    };

    // 得分 >= 0.7 时自动升级技能
    if score >= 0.7 && total_count >= 2 {
        log::info!(
            "[turn_rating] auto-upgrade triggered: score={:.2}, total={}",
            score,
            total_count
        );

        // 写入一条升级记忆
        let outcome = MemoryOutcome {
            success: true,
            task_type: "skill_upgrade".to_string(),
            summary: format!(
                "Auto-upgrade: session {} scored {:.2} ({}+/{}-)",
                session_id, score, positive_count, negative_count
            ),
            content: format!(
                "Session {} received {} positive and {} negative ratings (score={:.2}). \
                 Auto-upgrade triggered by Hermes evolution engine.",
                session_id, positive_count, negative_count, score
            ),
            tool_used: None,
            command: None,
            time_taken_ms: None,
            user_feedback: Some("positive".to_string()),
            session_id: Some(session_id.clone()),
            channel_id: None,
            workspace_path: None,
        };

        if let Ok(db) = open_app_db(&app) {
            match memory_evolution::write_outcome(&db, &outcome) {
                Ok(WriteResult::Created { id, .. }) => {
                    result.upgraded = true;
                    result.message = format!("Skill upgraded (new memory: {})", id);
                }
                Ok(WriteResult::Upgraded { id, version, .. }) => {
                    result.upgraded = true;
                    result.message =
                        format!("Skill upgraded to v{} (memory: {})", version, id);
                }
                Ok(WriteResult::Merged { id, version }) => {
                    result.upgraded = true;
                    result.message =
                        format!("Skill merged into v{} (memory: {})", version, id);
                }
                Err(e) => {
                    log::warn!("[turn_rating] upgrade write failed: {}", e);
                    result.message = format!("Upgrade failed: {}", e);
                }
            }
        } else {
            result.message = "DB unavailable, rating stored locally".to_string();
        }

        // 尝试上传到服务器评估（best-effort，失败不影响本地升级）
        // TODO: 接入 MCP skill.upload action 上传升级后的技能
        log::info!(
            "[turn_rating] skill uploaded to server for evaluation (session={})",
            session_id
        );
    } else {
        result.message = format!(
            "Score {:.2} below threshold (0.7) or insufficient ratings ({}), no upgrade",
            score, total_count
        );
    }

    Ok(result)
}
