// Copyright (c) 2026 AIMarketing
//
// Hermes 自动记忆升级 V2 — 数据层。
//
// 落地 SELF-EVOLUTION 设计文档中的 writeSuccess /
// writeFailure / dailyReflection 三大进化钩子。本模块只负责数据层
// 逻辑（SQLite 读写 + Jaccard 去重 + 版本族谱），LLM 调用由调用方
// (commands::memory_evolution 或后台任务) 完成后传入文本。
//
// 表结构（在 commands::legacy::ensure_app_schema 中扩展）：
//   memories 表新增列：version, parent_id, parent_version, task_type,
//     tool_used, confidence, session_id, channel_id, outcome
//   memory_lineage 新表：记录 parent → child 的合并族谱
//
// 与 hermes::memory_ops 的关系：memory_ops 是 V1 死代码脚手架（操作
// hermes_memories 空表），本模块操作实际生效的 memories 表，是 V2
// 的正式实现。

use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};

use crate::commands::types::MemoryEntry;
use crate::hermes::dedup_index::{DedupIndex, jaccard};

// === 输入 / 输出类型 ====================================================

/// 一次对话/技能执行的结果，用于编码为记忆。对应 V2 设计的
/// `writeSuccess` / `writeFailure` 入参。
#[derive(Serialize, Deserialize, Clone, Debug, Default)]
#[serde(rename_all = "camelCase")]
pub struct MemoryOutcome {
    pub success: bool,
    pub task_type: String,
    pub summary: String,
    pub content: String,
    /// 使用的工具名（如 "im_bridge.send_message"）
    pub tool_used: Option<String>,
    /// 执行的命令或关键动作
    pub command: Option<String>,
    pub time_taken_ms: Option<u64>,
    /// "positive" | "negative" | "neutral"
    pub user_feedback: Option<String>,
    pub session_id: Option<String>,
    pub channel_id: Option<String>,
    pub workspace_path: Option<String>,
}

/// write_outcome 的返回：新建还是升级版本。
#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase", tag = "kind")]
pub enum WriteResult {
    /// 全新记忆，无相似前置
    Created { id: String, version: i64 },
    /// 命中高相似度前置，升级为新版本（parent 指向旧版本）
    Upgraded {
        id: String,
        version: i64,
        parent_id: String,
        parent_version: i64,
    },
    /// 命中中相似度，内容被合并进已有记忆（不新建版本）
    Merged { id: String, version: i64 },
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
#[serde(rename_all = "camelCase")]
pub struct DedupeResult {
    pub scanned: usize,
    pub merged: usize,
    pub upgraded: usize,
    pub skipped: usize,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct LineageNode {
    pub memory: MemoryEntry,
    pub children: Vec<LineageNode>,
}

/// 搜索/列表 API 的精简返回类型。屏蔽 MemoryEntry 的内部字段
/// (access_count / last_accessed_at / parent_* / session_id 等)，
/// 只暴露前端反思面板和对话注入真正需要的字段。
#[derive(Serialize, Deserialize, Clone, Debug, Default)]
#[serde(rename_all = "camelCase")]
pub struct MemoryEntrySearch {
    pub id: String,
    pub summary: String,
    pub content: String,
    pub importance: String,
    pub confidence: f32,
    pub version: i64,
    pub task_type: Option<String>,
}

/// `memory_reflect` (dailyReflection) 的返回结果。汇总近期记忆数、
/// LLM 提炼出的 insight id（未调用 LLM 或 LLM 返回空时为 None），
/// 以及批量去重的统计。
#[derive(Serialize, Deserialize, Clone, Debug, Default)]
#[serde(rename_all = "camelCase")]
pub struct ReflectionResult {
    pub recent_count: usize,
    pub insight_id: Option<String>,
    pub dedupe: DedupeResult,
}

/// 相似度阈值。高于 high 触发版本升级；介于 mid~high 触发内容合并；
/// 低于 mid 视为全新记忆。
pub const SIM_THRESHOLD_HIGH: f32 = 0.80;
pub const SIM_THRESHOLD_MID: f32 = 0.50;

// === 核心 API ==========================================================

/// 把一次执行结果编码为记忆。去重流程：
/// 1. 按 summary 的 Jaccard 相似度在当前 workspace 下找最相似的已有记忆
/// 2. similarity >= HIGH → 升级版本（version+1，parent 指向旧版本，
///    content 用 LLM 提炼后的 merged_content；若调用方未提炼则追加）
/// 3. MID <= similarity < HIGH → 合并内容到已有记忆（不新增版本，
///    content 追加新经验）
/// 4. similarity < MID → 新建记忆
pub fn write_outcome(conn: &Connection, outcome: &MemoryOutcome) -> Result<WriteResult, String> {
    let now = now_rfc3339();
    let normalized_ws = normalize_workspace(outcome.workspace_path.as_deref());

    // 1) 找最相似的已有记忆（同 workspace + 同 task_type 优先）
    let candidates = list_outcome_candidates(conn, &normalized_ws, &outcome.task_type)?;
    let incoming_tokens = DedupIndex::tokenize(&outcome.summary);

    let mut best_id: Option<String> = None;
    let mut best_version: i64 = 1;
    let mut best_sim: f32 = 0.0;
    let mut best_content: String = String::new();

    for cand in &candidates {
        let cand_tokens = DedupIndex::tokenize(&cand.summary);
        let sim = jaccard(&incoming_tokens, &cand_tokens);
        if sim > best_sim {
            best_sim = sim;
            best_id = Some(cand.id.clone());
            best_version = cand.version;
            best_content = cand.content.clone();
        }
    }

    // 2) 根据相似度分支
    if best_sim >= SIM_THRESHOLD_HIGH {
        let parent_id = best_id.ok_or("invariant: best_id must be Some when sim >= HIGH")?;
        let parent_version = best_version;
        let new_version = parent_version + 1;
        let new_id = format!("mem_{}", uuid::Uuid::new_v4());

        // 合并 content：调用方应先 LLM 提炼，这里做保守追加
        let merged_content = if outcome.content.is_empty() {
            best_content.clone()
        } else {
            format!("{}\n\n--- 升级 v{} ---\n{}", best_content, new_version, outcome.content)
        };

        insert_versioned_memory(
            conn,
            &new_id,
            new_version,
            Some(&parent_id),
            Some(parent_version),
            &outcome.summary,
            &merged_content,
            &outcome.task_type,
            outcome.tool_used.as_deref(),
            outcome.success,
            outcome.confidence(),
            outcome.session_id.as_deref(),
            outcome.channel_id.as_deref(),
            normalized_ws.as_deref(),
            &now,
        )?;
        record_lineage(conn, &parent_id, parent_version, &new_id, new_version, &now)?;

        Ok(WriteResult::Upgraded {
            id: new_id,
            version: new_version,
            parent_id,
            parent_version,
        })
    } else if best_sim >= SIM_THRESHOLD_MID {
        // 合并到已有记忆：追加 content，更新 updated_at，access_count+1
        let target_id = best_id.ok_or("invariant: best_id must be Some when sim >= MID")?;
        let appended = if outcome.content.is_empty() {
            best_content.clone()
        } else {
            format!("{}\n\n--- 合并 ---\n{}", best_content, outcome.content)
        };
        merge_into_existing(conn, &target_id, &appended, outcome.success, &now)?;
        Ok(WriteResult::Merged {
            id: target_id,
            version: best_version,
        })
    } else {
        // 全新记忆
        let new_id = format!("mem_{}", uuid::Uuid::new_v4());
        insert_versioned_memory(
            conn,
            &new_id,
            1,
            None,
            None,
            &outcome.summary,
            &outcome.content,
            &outcome.task_type,
            outcome.tool_used.as_deref(),
            outcome.success,
            outcome.confidence(),
            outcome.session_id.as_deref(),
            outcome.channel_id.as_deref(),
            normalized_ws.as_deref(),
            &now,
        )?;
        Ok(WriteResult::Created {
            id: new_id,
            version: 1,
        })
    }
}

/// 取最近 N 毫秒内的记忆，供 dailyReflection 分析。按 created_at 降序。
pub fn get_recent(conn: &Connection, since_ms: i64) -> Result<Vec<MemoryEntry>, String> {
    let since_rfc = ms_to_rfc3339(since_ms);
    let mut stmt = conn
        .prepare(
            r#"SELECT id, summary, content, source, created_at, updated_at,
                      importance, access_count, last_accessed_at, workspace_path,
                      COALESCE(version, 1), parent_id, parent_version,
                      task_type, tool_used, COALESCE(confidence, 0),
                      session_id, channel_id, outcome
               FROM memories
               WHERE created_at >= ?1
               ORDER BY created_at DESC"#,
        )
        .map_err(|e| format!("prepare get_recent: {}", e))?;
    let rows = stmt
        .query_map(params![since_rfc], row_to_memory_entry)
        .map_err(|e| format!("query get_recent: {}", e))?;
    let mut out = Vec::new();
    for r in rows {
        out.push(r.map_err(|e| format!("read get_recent: {}", e))?);
    }
    Ok(out)
}

/// 批量去重：扫描所有记忆，对每条找相似度 >= MID 的，合并或升级。
/// 用于 dailyReflection 的记忆整理阶段。
///
/// 健壮性设计：
///   * 用 `deleted_ids: HashSet` 跟踪本轮已删除的记忆 id，遍历时跳过。
///     旧实现在循环中 `delete_memory_by_id` 后继续用 `all` 数组遍历，
///     `all[j]` (j < i) 可能已被删除，`merge_into_existing` 的 UPDATE
///     不匹配任何行，数据静默丢失。
///   * 升级/合并后 cur 被标记删除，但 parent 也可能被后续迭代引用，
///     所以 parent 删除后也要加入 deleted_ids。
pub fn dedupe_memories(conn: &Connection) -> Result<DedupeResult, String> {
    let all = list_all_outcome_memories(conn)?;
    let mut result = DedupeResult { scanned: all.len(), ..Default::default() };
    let now = now_rfc3339();
    let mut deleted_ids: std::collections::HashSet<String> = std::collections::HashSet::new();

    let mut i = 0;
    while i < all.len() {
        // 跳过本轮已删除的记忆（被之前的合并/升级吸收了）
        if deleted_ids.contains(&all[i].id) {
            i += 1;
            continue;
        }
        let cur = &all[i];
        let cur_tokens = DedupIndex::tokenize(&cur.summary);
        let mut best_j = Option::<(usize, f32)>::None;
        for (j, other) in all.iter().enumerate() {
            if j == i {
                continue;
            }
            // 跳过已删除的候选，避免合并进幽灵记忆
            if deleted_ids.contains(&other.id) {
                continue;
            }
            if other.workspace_path != cur.workspace_path {
                continue;
            }
            if other.task_type != cur.task_type {
                continue;
            }
            let sim = jaccard(&cur_tokens, &DedupIndex::tokenize(&other.summary));
            if sim >= SIM_THRESHOLD_MID {
                match &best_j {
                    Some((_, bs)) if *bs >= sim => {}
                    _ => best_j = Some((j, sim)),
                }
            }
        }

        match best_j {
            Some((j, sim)) if sim >= SIM_THRESHOLD_HIGH => {
                // 升级 cur 为新版本，parent = other (all[j])
                let parent = &all[j];
                let new_version = parent.version + 1;
                let new_id = format!("mem_{}", uuid::Uuid::new_v4());
                let merged_content = format!("{}\n\n--- 升级 v{} ---\n{}", parent.content, new_version, cur.content);
                insert_versioned_memory(
                    conn,
                    &new_id,
                    new_version,
                    Some(&parent.id),
                    Some(parent.version),
                    &cur.summary,
                    &merged_content,
                    cur.task_type.as_deref().unwrap_or(""),
                    cur.tool_used.as_deref(),
                    cur.success(),
                    cur.confidence,
                    cur.session_id.as_deref(),
                    cur.channel_id.as_deref(),
                    cur.workspace_path.as_deref(),
                    &now,
                )?;
                record_lineage(conn, &parent.id, parent.version, &new_id, new_version, &now)?;
                // 删除旧的 cur（被升级吸收）
                delete_memory_by_id(conn, &cur.id)?;
                deleted_ids.insert(cur.id.clone());
                result.upgraded += 1;
            }
            Some((j, _sim)) => {
                // 合并 cur 进 all[j]
                let parent = &all[j];
                let appended = format!("{}\n\n--- 合并 ---\n{}", parent.content, cur.content);
                merge_into_existing(conn, &parent.id, &appended, parent.success() || cur.success(), &now)?;
                delete_memory_by_id(conn, &cur.id)?;
                deleted_ids.insert(cur.id.clone());
                result.merged += 1;
            }
            None => {
                result.skipped += 1;
            }
        }
        i += 1;
    }

    Ok(result)
}

/// 查询记忆族谱树（parent → children 递归）。
///
/// 健壮性：加 `depth` 限制（默认 10 层），防止 memory_lineage 表出现
/// 环（理论上不应该，但 bug / 手动篡改可能导致）时栈溢出。同时用
/// `visited` 集合防环 —— 同一 id 在一条路径上只访问一次。
pub fn get_lineage(conn: &Connection, id: &str) -> Result<LineageNode, String> {
    let mut visited: std::collections::HashSet<String> = std::collections::HashSet::new();
    get_lineage_inner(conn, id, 0, &mut visited)
}

const LINEAGE_MAX_DEPTH: usize = 10;

fn get_lineage_inner(
    conn: &Connection,
    id: &str,
    depth: usize,
    visited: &mut std::collections::HashSet<String>,
) -> Result<LineageNode, String> {
    if depth >= LINEAGE_MAX_DEPTH {
        return Err(format!("lineage depth exceeded {} (possible cycle)", LINEAGE_MAX_DEPTH));
    }
    if !visited.insert(id.to_string()) {
        return Err(format!("lineage cycle detected at id={}", id));
    }
    let memory = get_memory_full(conn, id)?
        .ok_or_else(|| format!("memory not found: {}", id))?;
    let children_ids = list_children(conn, id)?;
    let mut children = Vec::new();
    for cid in children_ids {
        match get_lineage_inner(conn, &cid, depth + 1, visited) {
            Ok(node) => children.push(node),
            Err(e) => log::warn!("[memory_evolution] get_lineage child {} failed: {}", cid, e),
        }
    }
    visited.remove(id);
    Ok(LineageNode { memory, children })
}

/// 把 LLM 提炼后的 insight 写为新记忆（type=insight，importance=hot）。
/// dailyReflection 调用此函数持久化反思结论。
pub fn save_insight(
    conn: &Connection,
    summary: &str,
    content: &str,
    workspace_path: Option<&str>,
) -> Result<String, String> {
    let now = now_rfc3339();
    let id = format!("mem_{}", uuid::Uuid::new_v4());
    insert_versioned_memory(
        conn,
        &id,
        1,
        None,
        None,
        summary,
        content,
        "insight",
        None,
        true,
        0.8, // insight 默认高置信度
        None,
        None,
        normalize_workspace(workspace_path).as_deref(),
        &now,
    )?;
    // insight 直接标记为 hot
    conn.execute(
        "UPDATE memories SET importance = 'hot' WHERE id = ?1",
        params![id],
    )
    .map_err(|e| format!("set insight hot: {}", e))?;
    Ok(id)
}

/// 按关键词搜索记忆（summary/content LIKE）。供对话注入用。
pub fn search_memories(
    conn: &Connection,
    query: &str,
    workspace: Option<&str>,
    limit: usize,
) -> Result<Vec<MemoryEntry>, String> {
    let ws = normalize_workspace(workspace);
    let needle = query.to_lowercase();
    let mut stmt = conn
        .prepare(
            r#"SELECT id, summary, content, source, created_at, updated_at,
                      importance, access_count, last_accessed_at, workspace_path,
                      COALESCE(version, 1), parent_id, parent_version,
                      task_type, tool_used, COALESCE(confidence, 0),
                      session_id, channel_id, outcome
               FROM memories
               WHERE (?1 IS NULL OR workspace_path IS NULL OR workspace_path = ?1)
                 AND (?2 = '' OR LOWER(summary) LIKE '%' || ?2 || '%'
                            OR LOWER(content) LIKE '%' || ?2 || '%')
               ORDER BY
                 CASE importance WHEN 'hot' THEN 1 WHEN 'warm' THEN 2 ELSE 3 END,
                 access_count DESC,
                 created_at DESC
               LIMIT ?3"#,
        )
        .map_err(|e| format!("prepare search_memories: {}", e))?;
    let rows = stmt
        .query_map(params![ws.as_deref(), needle, limit as i64], row_to_memory_entry)
        .map_err(|e| format!("query search_memories: {}", e))?;
    let mut out = Vec::new();
    for r in rows {
        out.push(r.map_err(|e| format!("read search_memories: {}", e))?);
    }
    Ok(out)
}

// === 内部辅助 ==========================================================

impl MemoryOutcome {
    /// 根据成功/失败和用户反馈计算初始置信度。
    fn confidence(&self) -> f32 {
        let mut c = if self.success { 0.7 } else { 0.4 };
        match self.user_feedback.as_deref() {
            Some("positive") => c = (c + 0.2f32).min(1.0f32),
            Some("negative") => c = (c - 0.3f32).max(0.0f32),
            _ => {}
        }
        c
    }
}

fn row_to_memory_entry(row: &rusqlite::Row<'_>) -> rusqlite::Result<MemoryEntry> {
    Ok(MemoryEntry {
        id: row.get(0)?,
        summary: row.get(1)?,
        content: row.get(2)?,
        source: row.get(3)?,
        created_at: row.get(4)?,
        updated_at: row.get(5)?,
        importance: row.get(6)?,
        access_count: row.get(7)?,
        last_accessed_at: row.get(8)?,
        workspace_path: row.get(9)?,
        version: row.get::<_, Option<i64>>(10)?.unwrap_or(1),
        parent_id: row.get(11)?,
        parent_version: row.get::<_, Option<i64>>(12)?,
        task_type: row.get(13)?,
        tool_used: row.get(14)?,
        confidence: row.get::<_, Option<f32>>(15)?.unwrap_or(0.0),
        session_id: row.get(16)?,
        channel_id: row.get(17)?,
        outcome: row.get(18)?,
    })
}

fn list_outcome_candidates(
    conn: &Connection,
    workspace: &Option<String>,
    task_type: &str,
) -> Result<Vec<MemoryEntry>, String> {
    let mut stmt = conn
        .prepare(
            r#"SELECT id, summary, content, source, created_at, updated_at,
                      importance, access_count, last_accessed_at, workspace_path,
                      COALESCE(version, 1), parent_id, parent_version,
                      task_type, tool_used, COALESCE(confidence, 0),
                      session_id, channel_id, outcome
               FROM memories
               WHERE (?1 IS NULL OR workspace_path IS NULL OR workspace_path = ?1)
                 AND (?2 = '' OR task_type = ?2)
               ORDER BY created_at DESC
               LIMIT 200"#,
        )
        .map_err(|e| format!("prepare list_outcome_candidates: {}", e))?;
    let rows = stmt
        .query_map(params![workspace.as_deref(), task_type], row_to_memory_entry)
        .map_err(|e| format!("query list_outcome_candidates: {}", e))?;
    let mut out = Vec::new();
    for r in rows {
        out.push(r.map_err(|e| format!("read list_outcome_candidates: {}", e))?);
    }
    Ok(out)
}

fn list_all_outcome_memories(conn: &Connection) -> Result<Vec<MemoryEntry>, String> {
    let mut stmt = conn
        .prepare(
            r#"SELECT id, summary, content, source, created_at, updated_at,
                      importance, access_count, last_accessed_at, workspace_path,
                      COALESCE(version, 1), parent_id, parent_version,
                      task_type, tool_used, COALESCE(confidence, 0),
                      session_id, channel_id, outcome
               FROM memories
               ORDER BY created_at DESC"#,
        )
        .map_err(|e| format!("prepare list_all_outcome_memories: {}", e))?;
    let rows = stmt
        .query_map([], row_to_memory_entry)
        .map_err(|e| format!("query list_all_outcome_memories: {}", e))?;
    let mut out = Vec::new();
    for r in rows {
        out.push(r.map_err(|e| format!("read list_all_outcome_memories: {}", e))?);
    }
    Ok(out)
}

fn get_memory_full(conn: &Connection, id: &str) -> Result<Option<MemoryEntry>, String> {
    conn.query_row(
        r#"SELECT id, summary, content, source, created_at, updated_at,
                  importance, access_count, last_accessed_at, workspace_path,
                  COALESCE(version, 1), parent_id, parent_version,
                  task_type, tool_used, COALESCE(confidence, 0),
                  session_id, channel_id, outcome
           FROM memories WHERE id = ?1"#,
        params![id],
        row_to_memory_entry,
    )
    .optional()
    .map_err(|e| format!("get_memory_full: {}", e))
}

fn insert_versioned_memory(
    conn: &Connection,
    id: &str,
    version: i64,
    parent_id: Option<&str>,
    parent_version: Option<i64>,
    summary: &str,
    content: &str,
    task_type: &str,
    tool_used: Option<&str>,
    success: bool,
    confidence: f32,
    session_id: Option<&str>,
    channel_id: Option<&str>,
    workspace_path: Option<&str>,
    now: &str,
) -> Result<(), String> {
    let outcome = if success { "success" } else { "failure" };
    // importance 初值：success → warm，failure → cold（低调保留）
    let importance = if success { "warm" } else { "cold" };
    // source 字段在 legacy.rs 中约定为来源标记（"对话"/"手动"等），
    // 不能复用 task_type（否则前端 source 列会显示 "im_chat" 等值）。
    // V2 自动记忆统一标记为 "evolution"。
    let source = "evolution";
    conn.execute(
        r#"INSERT INTO memories
            (id, summary, content, source, workspace_path, created_at, updated_at,
             importance, access_count, version, parent_id, parent_version,
             task_type, tool_used, confidence, session_id, channel_id, outcome)
           VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?6, ?7, 0, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16)"#,
        params![
            id,
            summary,
            content,
            source,
            workspace_path,
            now,
            importance,
            version,
            parent_id,
            parent_version,
            task_type,
            tool_used,
            confidence,
            session_id,
            channel_id,
            outcome,
        ],
    )
    .map_err(|e| format!("insert_versioned_memory: {}", e))?;
    Ok(())
}

fn record_lineage(
    conn: &Connection,
    parent_id: &str,
    parent_version: i64,
    child_id: &str,
    child_version: i64,
    merged_at: &str,
) -> Result<(), String> {
    conn.execute(
        r#"INSERT OR IGNORE INTO memory_lineage
            (parent_id, parent_version, child_id, child_version, merged_at)
           VALUES (?1, ?2, ?3, ?4, ?5)"#,
        params![parent_id, parent_version, child_id, child_version, merged_at],
    )
    .map_err(|e| format!("record_lineage: {}", e))?;
    Ok(())
}

fn list_children(conn: &Connection, parent_id: &str) -> Result<Vec<String>, String> {
    let mut stmt = conn
        .prepare("SELECT child_id FROM memory_lineage WHERE parent_id = ?1")
        .map_err(|e| format!("prepare list_children: {}", e))?;
    let rows = stmt
        .query_map(params![parent_id], |r| r.get::<_, String>(0))
        .map_err(|e| format!("query list_children: {}", e))?;
    let mut out = Vec::new();
    for r in rows {
        out.push(r.map_err(|e| format!("read list_children: {}", e))?);
    }
    Ok(out)
}

fn merge_into_existing(
    conn: &Connection,
    id: &str,
    new_content: &str,
    success: bool,
    now: &str,
) -> Result<(), String> {
    // 合并后 access_count + 1，importance 按 access_count 重算（复用现有 CASE 语义）
    conn.execute(
        r#"UPDATE memories
           SET content = ?1,
               updated_at = ?2,
               access_count = access_count + 1,
               last_accessed_at = ?2,
               importance = CASE
                   WHEN access_count + 1 >= 3 THEN 'hot'
                   WHEN access_count + 1 >= 1 THEN 'warm'
                   ELSE 'cold'
               END,
               outcome = CASE
                   WHEN ?3 = 1 THEN 'success'
                   ELSE outcome
               END
           WHERE id = ?4"#,
        params![new_content, now, success as i32, id],
    )
    .map_err(|e| format!("merge_into_existing: {}", e))?;
    Ok(())
}

fn delete_memory_by_id(conn: &Connection, id: &str) -> Result<(), String> {
    conn.execute("DELETE FROM memories WHERE id = ?1", params![id])
        .map_err(|e| format!("delete_memory_by_id: {}", e))?;
    Ok(())
}

fn normalize_workspace(path: Option<&str>) -> Option<String> {
    path.map(|p| {
        let expanded = shellexpand::tilde(p);
        let cleaned = expanded.to_string().trim_end_matches('/').to_string();
        if cleaned.is_empty() {
            "/".to_string()
        } else {
            cleaned
        }
    })
}

fn now_rfc3339() -> String {
    // 必须与 commands::legacy::now_rfc3339() 保持完全一致，否则
    // memory_evolution 写入的 created_at 与 legacy 写入的格式不同，
    // get_recent 的 `WHERE created_at >= ?1` 字符串比较会错乱。
    // legacy 用 chrono::Utc::now().to_rfc3339()，这里也必须用同一个。
    chrono::Utc::now().to_rfc3339()
}

fn ms_to_rfc3339(ms: i64) -> String {
    // 把毫秒时间戳转成 RFC3339 字符串，与 now_rfc3339 同格式。
    // chrono::DateTime::from_timestamp_millis 返回 UTC 时间，
    // to_rfc3339() 输出如 "2026-07-06T12:00:00.000+00:00"。
    match chrono::DateTime::from_timestamp_millis(ms) {
        Some(dt) => dt.to_rfc3339(),
        None => {
            log::warn!("[memory_evolution] invalid timestamp_millis: {}", ms);
            chrono::Utc::now().to_rfc3339()
        }
    }
}

// 为 MemoryEntry 扩展的辅助方法（不污染 commands::types 的定义）
impl MemoryEntry {
    fn success(&self) -> bool {
        self.outcome.as_deref() == Some("success")
    }
}

// === 单元测试 ==========================================================

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    fn open_tmp() -> (tempfile::TempDir, Connection) {
        let dir = tempdir().expect("tempdir");
        let conn = Connection::open(dir.path().join("test.db")).expect("open");
        // 创建带新列的 memories 表 + lineage 表
        conn.execute_batch(
            r#"
            CREATE TABLE memories (
                id TEXT PRIMARY KEY,
                summary TEXT NOT NULL,
                content TEXT NOT NULL,
                source TEXT NOT NULL DEFAULT '对话',
                workspace_path TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                importance TEXT NOT NULL DEFAULT 'warm',
                access_count INTEGER DEFAULT 0,
                last_accessed_at TEXT,
                version INTEGER DEFAULT 1,
                parent_id TEXT,
                parent_version INTEGER,
                task_type TEXT,
                tool_used TEXT,
                confidence REAL DEFAULT 0,
                session_id TEXT,
                channel_id TEXT,
                outcome TEXT
            );
            CREATE TABLE memory_lineage (
                parent_id TEXT NOT NULL,
                parent_version INTEGER NOT NULL,
                child_id TEXT NOT NULL,
                child_version INTEGER NOT NULL,
                merged_at TEXT NOT NULL,
                PRIMARY KEY (parent_id, parent_version, child_id, child_version)
            );
            "#,
        )
        .expect("create schema");
        (dir, conn)
    }

    fn outcome(summary: &str, success: bool) -> MemoryOutcome {
        MemoryOutcome {
            success,
            task_type: "test".into(),
            summary: summary.into(),
            content: format!("content for {}", summary),
            ..Default::default()
        }
    }

    #[test]
    fn write_outcome_creates_new_for_disjoint_summary() {
        let (_dir, conn) = open_tmp();
        let r = write_outcome(&conn, &outcome("完全独立的任务 summary xyz", true)).unwrap();
        assert!(matches!(r, WriteResult::Created { version: 1, .. }));
    }

    #[test]
    fn write_outcome_merges_for_medium_similarity() {
        let (_dir, conn) = open_tmp();
        write_outcome(&conn, &outcome("向飞书群发送构建通知", true)).unwrap();
        // 第二次：summary 部分重叠
        let r = write_outcome(&conn, &outcome("向飞书群发送构建完成通知", true)).unwrap();
        // 应该是 Merged 或 Upgraded（取决于 Jaccard 实际值）
        match r {
            WriteResult::Merged { .. } | WriteResult::Upgraded { .. } => {}
            WriteResult::Created { .. } => panic!("expected merge/upgrade, got Created"),
        }
    }

    #[test]
    fn write_outcome_upgrades_for_high_similarity() {
        let (_dir, conn) = open_tmp();
        let r1 = write_outcome(&conn, &outcome("向飞书群发送构建通知", true)).unwrap();
        // 完全相同的 summary → Jaccard = 1.0
        let r2 = write_outcome(&conn, &outcome("向飞书群发送构建通知", true)).unwrap();
        match r2 {
            WriteResult::Upgraded { parent_id, version, .. } => {
                let created_id = match r1 {
                    WriteResult::Created { id, .. } => id,
                    _ => panic!("first should be Created"),
                };
                assert_eq!(parent_id, created_id);
                assert_eq!(version, 2);
            }
            _ => panic!("expected Upgraded, got {:?}", r2),
        }
    }

    #[test]
    fn lineage_tree_builds_recursively() {
        let (_dir, conn) = open_tmp();
        let r1 = write_outcome(&conn, &outcome("向飞书群发送构建通知", true)).unwrap();
        let parent_id = match r1 {
            WriteResult::Created { id, .. } => id,
            _ => panic!("first should be Created"),
        };
        write_outcome(&conn, &outcome("向飞书群发送构建通知", true)).unwrap(); // v2
        write_outcome(&conn, &outcome("向飞书群发送构建通知", true)).unwrap(); // v3

        let tree = get_lineage(&conn, &parent_id).unwrap();
        assert_eq!(tree.memory.id, parent_id);
        assert!(!tree.children.is_empty(), "should have at least one child");
    }

    #[test]
    fn save_insight_writes_hot_memory() {
        let (_dir, conn) = open_tmp();
        let id = save_insight(&conn, "每日反思结论", "用户偏好夜间执行长任务", None).unwrap();
        let m = get_memory_full(&conn, &id).unwrap().unwrap();
        assert_eq!(m.importance, "hot");
        assert_eq!(m.task_type.as_deref(), Some("insight"));
    }

    #[test]
    fn dedupe_merges_similar_memories() {
        let (_dir, conn) = open_tmp();
        write_outcome(&conn, &outcome("向飞书群发送构建通知", true)).unwrap();
        write_outcome(&conn, &outcome("向飞书群发送构建完成通知", true)).unwrap();
        let result = dedupe_memories(&conn).unwrap();
        assert!(result.scanned >= 2);
        assert!(result.merged + result.upgraded >= 1, "should merge or upgrade at least one pair");
    }
}
