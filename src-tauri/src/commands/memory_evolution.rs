// Copyright (c) 2026 AIMarketing
//
// Hermes 自动记忆升级 V2 — IPC 命令层。
//
// 薄包装：把 Tauri IPC 参数转成 hermes::memory_evolution 的数据层调用。
// 所有命令复用 commands::legacy::open_app_db 打开 tupai.db，操作的是
// 实际生效的 memories 表（不是 hermes_memories 空表）。
//
// 命令清单：
//   memory_write_outcome   — writeSuccess/writeFailure 入口
//   memory_search          — 语义搜索（供对话注入）
//   memory_get_lineage     — 版本族谱树
//   memory_dedupe          — 批量去重合并
//   memory_get_recent      — 取近 N 毫秒记忆（供反思）
//   memory_save_insight    — 持久化反思结论
//   memory_reflect         — 完整 dailyReflection（LLM 提炼 + 去重 + 洞察）

use tauri::AppHandle;

use crate::commands::legacy::open_app_db;
use crate::hermes::llm_service::{hermes_llm_complete, LLMServiceConfig};
use crate::hermes::memory_evolution::{
    self, DedupeResult, LineageNode, MemoryOutcome, WriteResult,
};
use crate::hermes::types::VLMMessage;

// === IPC 命令 ==========================================================

/// 把一次对话/技能执行结果编码为记忆。自动去重 + 版本升级。
#[tauri::command]
pub fn memory_write_outcome(
    app: AppHandle,
    outcome: MemoryOutcome,
) -> Result<WriteResult, String> {
    let conn = open_app_db(&app)?;
    memory_evolution::write_outcome(&conn, &outcome)
}

/// 语义搜索记忆（summary/content LIKE）。供对话注入用。
#[tauri::command]
pub fn memory_search(
    app: AppHandle,
    query: String,
    workspace: Option<String>,
    limit: Option<usize>,
) -> Result<Vec<memory_evolution::MemoryEntrySearch>, String> {
    let conn = open_app_db(&app)?;
    let limit = limit.unwrap_or(10).min(100);
    let rows = memory_evolution::search_memories(&conn, &query, workspace.as_deref(), limit)?;
    // 转成精简的搜索结果类型，避免泄露内部字段
    Ok(rows.into_iter().map(|m| memory_evolution::MemoryEntrySearch {
        id: m.id,
        summary: m.summary,
        content: m.content,
        importance: m.importance,
        confidence: m.confidence,
        version: m.version,
        task_type: m.task_type,
    }).collect())
}

/// 查询记忆族谱树（parent → children 递归）。
#[tauri::command]
pub fn memory_get_lineage(app: AppHandle, id: String) -> Result<LineageNode, String> {
    let conn = open_app_db(&app)?;
    memory_evolution::get_lineage(&conn, &id)
}

/// 批量去重：扫描所有记忆，合并/升级相似条目。供 dailyReflection 调用。
#[tauri::command]
pub fn memory_dedupe(app: AppHandle) -> Result<DedupeResult, String> {
    let conn = open_app_db(&app)?;
    memory_evolution::dedupe_memories(&conn)
}

/// 取最近 N 毫秒内的记忆（供前端反思面板展示）。
#[tauri::command]
pub fn memory_get_recent(app: AppHandle, since_ms: i64) -> Result<Vec<memory_evolution::MemoryEntrySearch>, String> {
    let conn = open_app_db(&app)?;
    let rows = memory_evolution::get_recent(&conn, since_ms)?;
    Ok(rows.into_iter().map(|m| memory_evolution::MemoryEntrySearch {
        id: m.id,
        summary: m.summary,
        content: m.content,
        importance: m.importance,
        confidence: m.confidence,
        version: m.version,
        task_type: m.task_type,
    }).collect())
}

/// 持久化一条反思 insight 为 hot 记忆。
#[tauri::command]
pub fn memory_save_insight(
    app: AppHandle,
    summary: String,
    content: String,
    workspace_path: Option<String>,
) -> Result<String, String> {
    let conn = open_app_db(&app)?;
    memory_evolution::save_insight(&conn, &summary, &content, workspace_path.as_deref())
}

/// 完整 dailyReflection：
/// 1. 取近 24h 记忆
/// 2. 调 LLM 提炼 insight（cfg 为 None 时跳过 LLM，只做去重）
/// 3. 持久化 insight
/// 4. 批量去重
#[tauri::command]
pub async fn memory_reflect(
    app: AppHandle,
    llm_cfg: Option<LLMServiceConfig>,
    since_ms: Option<i64>,
) -> Result<memory_evolution::ReflectionResult, String> {
    let window_ms = since_ms.unwrap_or(24 * 60 * 60 * 1000); // 默认 24h
    let now_ms = chrono::Utc::now().timestamp_millis();
    let since = now_ms - window_ms;

    // 1) 取近期记忆
    let conn = open_app_db(&app)?;
    let recent = memory_evolution::get_recent(&conn, since)?;
    drop(conn); // 释放连接，后续 LLM 调用不持锁

    // 2) LLM 提炼（可选）
    let insight = if let Some(cfg) = llm_cfg {
        match reflect_with_llm(&cfg, &recent).await {
            Ok(text) if !text.trim().is_empty() => Some(text),
            Ok(_) => {
                log::warn!("[memory_evolution] LLM reflection returned empty");
                None
            }
            Err(e) => {
                log::warn!("[memory_evolution] LLM reflection failed: {}", e);
                None
            }
        }
    } else {
        None
    };

    // 3) 持久化 insight
    let mut insight_id = None;
    if let Some(text) = &insight {
        let conn = open_app_db(&app)?;
        // 把 LLM 输出拆成 summary（首行）+ content（剩余）
        let (summary, content) = split_insight(text);
        let id = memory_evolution::save_insight(&conn, &summary, &content, None)?;
        insight_id = Some(id);
    }

    // 4) 批量去重
    let conn = open_app_db(&app)?;
    let dedupe = memory_evolution::dedupe_memories(&conn)?;

    Ok(memory_evolution::ReflectionResult {
        recent_count: recent.len(),
        insight_id,
        dedupe,
    })
}

// === 内部辅助 ==========================================================

async fn reflect_with_llm(
    cfg: &LLMServiceConfig,
    memories: &[crate::commands::types::MemoryEntry],
) -> Result<String, String> {
    if memories.is_empty() {
        return Ok(String::new());
    }
    // 构造反思 prompt：把近期记忆摘要喂给 LLM，让它提炼模式 + 教训
    let memory_brief: String = memories
        .iter()
        .take(50) // 限制 token 量
        .map(|m| format!("- [{}] {} (置信度: {:.2})", m.outcome.as_deref().unwrap_or("unknown"), m.summary, m.confidence))
        .collect::<Vec<_>>()
        .join("\n");

    let system = "你是一个记忆反思助手。根据给定的近期记忆列表，提炼出 1-3 条可复用的模式或教训。\
                  每条用一行输出，格式：`[类型] 内容`，类型为 success/failure/insight。\
                  只输出提炼结果，不要解释。";
    let user = format!("近期记忆：\n{}", memory_brief);

    let messages = vec![
        VLMMessage {
            role: "system".into(),
            content: system.into(),
            ..Default::default()
        },
        VLMMessage {
            role: "user".into(),
            content: user,
            ..Default::default()
        },
    ];

    let resp = hermes_llm_complete(cfg.clone(), messages, None).await?;
    // VLMResponse.content 直接是 LLM 返回的文本（OpenAI 流式由
    // openai_complete_collect 内部累积成单一 content 字段），不需要
    // 像 OpenAI chat-completions 那样取 choices[0].message.content。
    Ok(resp.content.unwrap_or_default())
}

fn split_insight(text: &str) -> (String, String) {
    let trimmed = text.trim();
    if let Some(idx) = trimmed.find('\n') {
        let summary = trimmed[..idx].trim().to_string();
        let content = trimmed[idx..].trim().to_string();
        (summary, content)
    } else {
        // 单行：summary = 全文，content = 空
        (trimmed.to_string(), String::new())
    }
}
