// Copyright (c) 2026 AIMarketing
//
// Hermes 自进化 — Track A: SessionAnalyzer.
//
// 从 sessions.db (messages 表) + tupai.db (memories 表中 task_type='dialog_turn'
// 的 turn_rating 记录) 读取近期会话, 喂给 LLM 提炼 `EvolutionSignal::SessionInsight`
// 信号。LLM 不可用 (cfg 缺失 / 调用失败 / JSON 解析失败) 时降级到关键词启发式,
// 置信度封顶 0.55 (低于 CONFIDENCE_THRESHOLD, 被 EvolutionGate 直接丢弃 —— 安全默认)。
//
// 健壮性:
//   * 所有 SQL 读: 开连接 → prepare → query → DROP 连接, 之后才做任何 `.await`
//     (遵循 memory_evolution.rs 的 `memory_reflect` 模式 + HermesAppState 的
//     "never hold std::sync::MutexGuard across .await" 硬规则)。
//   * DB / LLM 失败一律返回 `String` / `AnalyzeError`, 绝不 `unwrap()`。
//   * 降级路径不抛错, 而是返回 `AnalyzeResult { degraded: true, signals: heuristic }`。

use rusqlite::{params, Connection};
use serde::{Deserialize, Serialize};
use tauri::Manager;

use crate::hermes::evolution_signal::{
    EvolutionSignal, SessionSignalType, SkillKind, CONFIDENCE_THRESHOLD,
    MAX_CHARS_PER_SESSION_BRIEF, MAX_SESSIONS_PER_LLM_CALL,
};
use crate::hermes::llm_service::hermes_llm_complete_messages;
use crate::hermes::types::VLMMessage;

// === 公共类型 ==========================================================

/// 会话分析器。无状态 —— 不持有连接 / 缓存, 每次调用独立打开 DB。
/// 注释里提到的 `DuckDBPool` (worker_task_log 交叉引用) 留待 Track D 接入,
/// Phase 1 仅用 sqlite 路径。
pub struct SessionAnalyzer;

/// 单个会话的摘要, 喂给 LLM 的最小单元。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionBrief {
    pub session_id: String,
    /// 该会话消息拼接后截断到 `MAX_CHARS_PER_SESSION_BRIEF` 字符。
    pub message_summary: String,
    pub turn_rating_stats: TurnRatingStats,
    /// 本会话中提到 / 调用过的技能 id (best-effort; Phase 1 留空,
    /// 由 LLM 在 analyze_window 阶段根据 installed_skills 关联)。
    pub associated_skill_ids: Vec<String>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TurnRatingStats {
    pub positive: u32,
    pub negative: u32,
    /// positive / (positive + negative); 无评分时为 None。
    pub avg_score: Option<f32>,
}

/// 已安装技能的精简视图, 喂给 LLM 做关联。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SkillSummary {
    pub skill_id: String,
    pub name: String,
    pub kind: SkillKind,
    pub description: String,
}

/// 一次 analyze_window 的返回。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AnalyzeResult {
    pub signals: Vec<EvolutionSignal>,
    /// true = LLM 不可用 / 失败, 用了启发式。
    pub degraded: bool,
    pub llm_tokens_used: Option<u32>,
}

/// analyze_window 的错误类型。LLM 调用失败 / 解析失败已被内部捕获为降级,
/// 这里只用于真正无法继续的情况 (当前实际不会从 analyze_window 抛出,
/// 保留是为了未来扩展 + 调用方类型签名)。
#[derive(thiserror::Error, Debug)]
pub enum AnalyzeError {
    #[error("db error: {0}")]
    Db(String),
    #[error("llm error: {0}")]
    Llm(String),
    #[error("parse error: {0}")]
    Parse(String),
}

impl SessionAnalyzer {
    pub fn new() -> Self {
        SessionAnalyzer
    }

    /// 读取 since_ms 之后的近期会话 (messages + turn_rating), 返回按
    /// (negative 评分降序, 会话长度降序) 排序后取 top `MAX_SESSIONS_PER_LLM_CALL`。
    ///
    /// 全程同步 (无 `.await`), 但声明为 async 以便调用方在异步上下文统一 await。
    pub async fn collect_window(
        &self,
        app: &tauri::AppHandle,
        since_ms: i64,
    ) -> Result<Vec<SessionBrief>, String> {
        let since_rfc = ms_to_rfc3339(since_ms);

        // 1) sessions.db → messages 按 session_id 分组
        let messages_by_session = read_messages_by_session(app, &since_rfc)?;

        // 2) tupai.db → turn_rating (memories 表 task_type='dialog_turn') 按 session 聚合
        let rating_by_session = read_turn_ratings_by_session(app, &since_rfc)?;

        // 3) 组装 SessionBrief
        let mut briefs: Vec<SessionBrief> = messages_by_session
            .into_iter()
            .map(|(session_id, msgs)| {
                let stats = rating_by_session
                    .get(&session_id)
                    .cloned()
                    .unwrap_or_default();
                let summary = build_message_summary(&msgs);
                SessionBrief {
                    session_id,
                    message_summary: summary,
                    turn_rating_stats: stats,
                    associated_skill_ids: Vec::new(),
                }
            })
            .collect();

        // 4) 排序: negative 降序 → message_summary 长度降序 → 取 top N
        briefs.sort_by(|a, b| {
            b.turn_rating_stats
                .negative
                .cmp(&a.turn_rating_stats.negative)
                .then_with(|| b.message_summary.len().cmp(&a.message_summary.len()))
        });
        briefs.truncate(MAX_SESSIONS_PER_LLM_CALL);
        Ok(briefs)
    }

    /// 单次 LLM 调用 (走 MCP `llm.stream_request` via `hermes_llm_complete_messages`,
    /// 不需要 `LLMServiceConfig`)。MCP 失败 / 解析失败 → 降级到启发式
    /// (`degraded=true`, 置信度封顶 0.55 < 阈值, 会被 gate 丢弃 —— 安全默认)。
    pub async fn analyze_window(
        &self,
        sessions: &[SessionBrief],
        installed_skills: &[SkillSummary],
    ) -> Result<AnalyzeResult, AnalyzeError> {
        if sessions.is_empty() {
            return Ok(AnalyzeResult {
                signals: Vec::new(),
                degraded: false,
                llm_tokens_used: None,
            });
        }

        let messages = build_llm_messages(sessions, installed_skills);

        // MCP 路径不返回 token usage, 所以 llm_tokens_used 始终 None。
        let content = match hermes_llm_complete_messages(messages).await {
            Ok(c) => c,
            Err(e) => {
                log::warn!("[session_analyzer] LLM call failed, degrading: {}", e);
                let signals = self.heuristic_analyze(sessions);
                return Ok(AnalyzeResult {
                    signals,
                    degraded: true,
                    llm_tokens_used: None,
                });
            }
        };

        match parse_llm_signals(&content) {
            Ok(signals) if !signals.is_empty() => Ok(AnalyzeResult {
                signals,
                degraded: false,
                llm_tokens_used: None,
            }),
            Ok(_) => {
                // LLM 返回空信号列表 —— 视为可接受, 不降级
                Ok(AnalyzeResult {
                    signals: Vec::new(),
                    degraded: false,
                    llm_tokens_used: None,
                })
            }
            Err(e) => {
                log::warn!(
                    "[session_analyzer] LLM JSON parse failed, degrading: {} (raw={})",
                    e,
                    content.chars().take(200).collect::<String>()
                );
                let signals = self.heuristic_analyze(sessions);
                Ok(AnalyzeResult {
                    signals,
                    degraded: true,
                    llm_tokens_used: None,
                })
            }
        }
    }

    /// 降级启发式。检测简单模式, 置信度封顶 0.55 (低于 CONFIDENCE_THRESHOLD,
    /// EvolutionGate 会丢弃 —— 安全默认, 不向用户轰炸低质量建议)。
    pub fn heuristic_analyze(&self, sessions: &[SessionBrief]) -> Vec<EvolutionSignal> {
        const HEURISTIC_CONF: f32 = 0.55;
        const MISSING_CONF: f32 = 0.5;
        let correction_keywords = ["又失败了", "不对", "重新", "再试", "失败了", "不对劲"];
        let question_keywords = ["怎么", "如何", "能不能", "可以吗", "有没有办法"];

        let mut out: Vec<EvolutionSignal> = Vec::new();

        // (a) FrequentCorrection / NegativeRating: 逐会话扫描
        for s in sessions {
            let has_correction_kw = correction_keywords
                .iter()
                .any(|k| s.message_summary.contains(k));
            if has_correction_kw && s.turn_rating_stats.negative > 0 {
                out.push(EvolutionSignal::SessionInsight {
                    signal_id: new_signal_id(),
                    session_id: s.session_id.clone(),
                    skill_id: None,
                    skill_kind: SkillKind::Mcp,
                    signal_type: SessionSignalType::FrequentCorrection,
                    evidence: vec![truncate_for_evidence(&s.message_summary)],
                    suggested_action: "用户频繁纠正, 建议检查技能参数/步骤".to_string(),
                    confidence: HEURISTIC_CONF,
                });
            }
            if s.turn_rating_stats.negative >= 2 {
                let avg = s.turn_rating_stats.avg_score.unwrap_or(1.0);
                if avg < 0.5 {
                    out.push(EvolutionSignal::SessionInsight {
                        signal_id: new_signal_id(),
                        session_id: s.session_id.clone(),
                        skill_id: None,
                        skill_kind: SkillKind::Mcp,
                        signal_type: SessionSignalType::NegativeRating,
                        evidence: vec![format!(
                            "negative={}, avg_score={:.2}",
                            s.turn_rating_stats.negative, avg
                        )],
                        suggested_action: "连续低分, 建议诊断技能退化".to_string(),
                        confidence: HEURISTIC_CONF,
                    });
                }
            }
        }

        // (b) MissingSkill: 多会话重复同类提问 (question keyword 命中 >= 2 个会话)
        let mut keyword_hits: usize = 0;
        let mut anchor_session: Option<&SessionBrief> = None;
        for s in sessions {
            if question_keywords.iter().any(|kw| s.message_summary.contains(kw)) {
                keyword_hits += 1;
                if anchor_session.is_none() {
                    anchor_session = Some(s);
                }
            }
        }
        if keyword_hits >= 2 {
            if let Some(s) = anchor_session {
                out.push(EvolutionSignal::SessionInsight {
                    signal_id: new_signal_id(),
                    session_id: s.session_id.clone(),
                    skill_id: None,
                    skill_kind: SkillKind::Mcp,
                    signal_type: SessionSignalType::MissingSkill,
                    evidence: vec![truncate_for_evidence(&s.message_summary)],
                    suggested_action: "多会话重复同类提问, 可能缺少覆盖技能".to_string(),
                    confidence: MISSING_CONF,
                });
            }
        }

        out
    }
}

impl Default for SessionAnalyzer {
    fn default() -> Self {
        Self::new()
    }
}

// === 内部: DB 读取 =====================================================

/// 单条消息的扁平视图 (仅 collect_window 用)。
struct MessageRow {
    session_id: String,
    role: String,
    content: String,
    #[allow(dead_code)]
    created_at: String,
}

/// 打开 sessions.db 用于读取。`commands::legacy::open_sessions_db` 是私有的,
/// 这里复制其模式 (开 `<app_data>/sessions.db`)。idempotent 地 ensure messages 表,
/// 避免全新 DB 上 SELECT 报错。连接在返回前已就绪; 调用方负责在 `.await` 前 drop。
fn open_sessions_db_for_read(app: &tauri::AppHandle) -> Result<Connection, String> {
    let dir = app
        .path()
        .app_data_dir()
        .map_err(|e| format!("resolve app_data_dir: {}", e))?;
    std::fs::create_dir_all(&dir)
        .map_err(|e| format!("create app_data_dir: {}", e))?;
    let path = dir.join("sessions.db");
    let conn = Connection::open(&path)
        .map_err(|e| format!("open sessions.db {}: {}", path.display(), e))?;
    // 与 legacy.rs `ensure_sessions_schema` 的 messages 子集保持一致 (idempotent)。
    conn.execute_batch(
        r#"CREATE TABLE IF NOT EXISTS messages (
            id TEXT PRIMARY KEY,
            session_id TEXT NOT NULL,
            role TEXT NOT NULL,
            content TEXT NOT NULL,
            created_at TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id);"#,
    )
    .map_err(|e| format!("ensure sessions messages schema: {}", e))?;
    Ok(conn)
}

/// 读取 since_rfc 之后的所有消息, 按 session_id 分组 (组内按 created_at 升序)。
fn read_messages_by_session(
    app: &tauri::AppHandle,
    since_rfc: &str,
) -> Result<std::collections::HashMap<String, Vec<MessageRow>>, String> {
    let conn = open_sessions_db_for_read(app)?;
    let mut stmt = conn
        .prepare(
            r#"SELECT session_id, role, content, created_at
               FROM messages
               WHERE created_at >= ?1
               ORDER BY session_id ASC, created_at ASC"#,
        )
        .map_err(|e| format!("prepare messages: {}", e))?;
    let rows = stmt
        .query_map(params![since_rfc], |row| {
            Ok(MessageRow {
                session_id: row.get(0)?,
                role: row.get(1)?,
                content: row.get(2)?,
                created_at: row.get(3)?,
            })
        })
        .map_err(|e| format!("query messages: {}", e))?;
    let mut out: std::collections::HashMap<String, Vec<MessageRow>> =
        std::collections::HashMap::new();
    for r in rows {
        let row = r.map_err(|e| format!("read messages row: {}", e))?;
        out.entry(row.session_id.clone()).or_default().push(row);
    }
    drop(stmt);
    drop(conn);
    Ok(out)
}

/// 读取 turn_rating (memories 表 task_type='dialog_turn'), 按 session_id 聚合
/// positive (outcome='success') / negative (outcome='failure') 计数 + avg_score。
///
/// 注: 项目里没有独立的 `turn_rating` 表 —— `commands::turn_rating::submit_turn_rating`
/// 把评分写成 memories 行 (task_type='dialog_turn', outcome='success'/'failure')。
/// 这里据此聚合。如未来 schema 变更需同步调整。
fn read_turn_ratings_by_session(
    app: &tauri::AppHandle,
    since_rfc: &str,
) -> Result<std::collections::HashMap<String, TurnRatingStats>, String> {
    let conn = crate::commands::legacy::open_app_db(app)?;
    let mut stmt = conn
        .prepare(
            r#"SELECT session_id, outcome, COUNT(*)
               FROM memories
               WHERE task_type = 'dialog_turn'
                 AND session_id IS NOT NULL
                 AND created_at >= ?1
               GROUP BY session_id, outcome"#,
        )
        .map_err(|e| format!("prepare turn_rating memories: {}", e))?;
    let rows = stmt
        .query_map(params![since_rfc], |row| {
            let session_id: String = row.get(0)?;
            let outcome: String = row.get(1)?;
            let count: i64 = row.get(2)?;
            Ok((session_id, outcome, count))
        })
        .map_err(|e| format!("query turn_rating memories: {}", e))?;
    let mut stats_map: std::collections::HashMap<String, TurnRatingStats> =
        std::collections::HashMap::new();
    for r in rows {
        let (session_id, outcome, count) =
            r.map_err(|e| format!("read turn_rating row: {}", e))?;
        let entry = stats_map.entry(session_id).or_default();
        let n = count.max(0) as u32;
        if outcome == "success" {
            entry.positive += n;
        } else if outcome == "failure" {
            entry.negative += n;
        }
        // 其它 outcome 值忽略 (健壮性: 不破坏聚合)
    }
    drop(stmt);
    drop(conn);
    // 计算 avg_score
    for stats in stats_map.values_mut() {
        let total = stats.positive + stats.negative;
        if total > 0 {
            stats.avg_score = Some(stats.positive as f32 / total as f32);
        }
    }
    Ok(stats_map)
}

/// 拼接单会话消息为摘要, 截断到 `MAX_CHARS_PER_SESSION_BRIEF` 字符。
/// 格式: `[role] content` 每条一行, 超长截断并加省略号标记。
fn build_message_summary(msgs: &[MessageRow]) -> String {
    let mut buf = String::new();
    // 用字节长度近似判断是否已超过预算, 避免每条消息都调 `buf.chars().count()`
    // (O(n) 扫描) 在循环里反复执行导致 O(n²)。MAX_CHARS_PER_SESSION_BRIEF 是字符数,
    // 这里用字节长度比较: 对 ASCII 等价; 对多字节字符 (如中文, UTF-8 3 字节/字符)
    // 会略早 break, 但最终精确截断仍由下方 `truncate_chars` 按字符完成, 不影响正确性。
    const MAX_BRIEF_BYTES: usize = MAX_CHARS_PER_SESSION_BRIEF;
    for m in msgs {
        if !buf.is_empty() {
            buf.push('\n');
        }
        buf.push('[');
        buf.push_str(&m.role);
        buf.push_str("] ");
        buf.push_str(&m.content);
        if buf.len() > MAX_BRIEF_BYTES {
            break;
        }
    }
    truncate_chars(&buf, MAX_CHARS_PER_SESSION_BRIEF)
}

// === 内部: LLM 调用 =====================================================

fn build_llm_messages(
    sessions: &[SessionBrief],
    installed_skills: &[SkillSummary],
) -> Vec<VLMMessage> {
    let system = "You are a skill-evolution analyst. Given session briefs and installed skills, \
output a single JSON object: {\"signals\": [{\"session_id\": string, \"signal_type\": string, \
\"skill_id\": string|null, \"skill_kind\": string, \"evidence\": [string], \"suggested_action\": string, \
\"confidence\": number}]}. \
evidence MUST be verbatim quotes from session summaries. confidence in [0,1]. \
Only emit signals with confidence >= 0.6. \
signal_type ∈ {missing_skill, frequent_correction, negative_rating, repetitive_action}. \
skill_kind ∈ {mcp, automation, builtin}. \
If signal_type=missing_skill, skill_id must be null. \
session_id MUST be the id of one of the provided session briefs the evidence came from. \
Output ONLY the JSON object, no prose, no code fences.";

    let user_payload = serde_json::json!({
        "sessions": sessions,
        "installed_skills": installed_skills,
    });
    let user = serde_json::to_string(&user_payload)
        .unwrap_or_else(|_| "{}".to_string());

    vec![
        VLMMessage {
            role: "system".to_string(),
            content: system.to_string(),
            ..Default::default()
        },
        VLMMessage {
            role: "user".to_string(),
            content: user,
            ..Default::default()
        },
    ]
}

/// LLM 输出顶层结构。
#[derive(Deserialize)]
struct LlmOutput {
    #[serde(default)]
    signals: Vec<LlmSignal>,
}

#[derive(Deserialize)]
struct LlmSignal {
    session_id: String,
    signal_type: String,
    #[serde(default)]
    skill_id: Option<String>,
    skill_kind: String,
    #[serde(default)]
    evidence: Vec<String>,
    suggested_action: String,
    confidence: f32,
}

/// 解析 LLM 返回的 JSON (容错: 剥离 ```json ... ``` 围栏)。
fn parse_llm_signals(raw: &str) -> Result<Vec<EvolutionSignal>, String> {
    let trimmed = strip_code_fence(raw).trim();
    if trimmed.is_empty() {
        return Err("empty LLM response".to_string());
    }
    let parsed: LlmOutput = serde_json::from_str(trimmed)
        .map_err(|e| format!("json parse: {}", e))?;

    let signals = parsed
        .signals
        .into_iter()
        .filter_map(|s| {
            let signal_type = SessionSignalType::from_str_lossy(&s.signal_type)?;
            let skill_kind = SkillKind::from_str_lossy(&s.skill_kind);
            // missing_skill → skill_id 必须为 None (与 system prompt 约定一致)
            let skill_id = if signal_type == SessionSignalType::MissingSkill {
                None
            } else {
                s.skill_id.filter(|id| !id.is_empty())
            };
            let confidence = s.confidence.clamp(0.0, 1.0);
            // 低于阈值的信号 LLM 不应输出, 但防御性再过滤一次
            if confidence < CONFIDENCE_THRESHOLD {
                return None;
            }
            Some(EvolutionSignal::SessionInsight {
                signal_id: new_signal_id(),
                session_id: s.session_id,
                skill_id,
                skill_kind,
                signal_type,
                evidence: s.evidence,
                suggested_action: s.suggested_action,
                confidence,
            })
        })
        .collect();
    Ok(signals)
}

/// 剥离可能的 ```json ... ``` 或 ``` ... ``` 围栏。
fn strip_code_fence(raw: &str) -> &str {
    let t = raw.trim();
    if let Some(rest) = t.strip_prefix("```json") {
        return rest.trim_start().trim_end_matches("```").trim();
    }
    if let Some(rest) = t.strip_prefix("```") {
        return rest.trim_start().trim_end_matches("```").trim();
    }
    t
}

// === 内部: 辅助 ========================================================

fn new_signal_id() -> String {
    format!("sig_{}", uuid::Uuid::new_v4())
}

/// 按字符 (UTF-8 安全) 截断到 max_chars, 超长则追加 "…"。
fn truncate_chars(s: &str, max_chars: usize) -> String {
    if s.chars().count() <= max_chars {
        return s.to_string();
    }
    let mut out: String = s.chars().take(max_chars.saturating_sub(1)).collect();
    out.push('…');
    out
}

/// 证据片段专用截断 (略短, 避免单条 evidence 占满摘要)。
fn truncate_for_evidence(s: &str) -> String {
    const MAX_EVIDENCE_CHARS: usize = 200;
    truncate_chars(s, MAX_EVIDENCE_CHARS)
}

/// 毫秒时间戳 → RFC3339 字符串 (与 sessions.db / tupai.db 的 created_at 同格式)。
fn ms_to_rfc3339(ms: i64) -> String {
    match chrono::DateTime::from_timestamp_millis(ms) {
        Some(dt) => dt.to_rfc3339(),
        None => {
            log::warn!("[session_analyzer] invalid timestamp_millis: {}", ms);
            chrono::Utc::now().to_rfc3339()
        }
    }
}

// === 单元测试 ==========================================================

#[cfg(test)]
mod tests {
    use super::*;

    fn brief(id: &str, summary: &str, pos: u32, neg: u32) -> SessionBrief {
        let total = pos + neg;
        SessionBrief {
            session_id: id.to_string(),
            message_summary: summary.to_string(),
            turn_rating_stats: TurnRatingStats {
                positive: pos,
                negative: neg,
                avg_score: if total > 0 {
                    Some(pos as f32 / total as f32)
                } else {
                    None
                },
            },
            associated_skill_ids: Vec::new(),
        }
    }

    #[test]
    fn heuristic_caps_below_threshold() {
        let analyzer = SessionAnalyzer::new();
        let sessions = vec![
            brief("s1", "用户说: 又失败了, 不对, 重新弄", 0, 1),
            brief("s2", "怎么打开notepad 如何导出", 0, 0),
            brief("s3", "怎么打开notepad", 0, 0),
        ];
        let sigs = analyzer.heuristic_analyze(&sessions);
        assert!(!sigs.is_empty(), "should detect patterns");
        // 所有信号置信度必须 < CONFIDENCE_THRESHOLD (0.6)
        for s in &sigs {
            if let EvolutionSignal::SessionInsight { confidence, .. } = s {
                assert!(*confidence < CONFIDENCE_THRESHOLD, "confidence {} not capped", confidence);
            }
        }
    }

    #[test]
    fn heuristic_empty_when_no_signal() {
        let analyzer = SessionAnalyzer::new();
        let sessions = vec![brief("s1", "正常对话", 2, 0)];
        let sigs = analyzer.heuristic_analyze(&sessions);
        assert!(sigs.is_empty());
    }

    #[test]
    fn parse_llm_signals_strips_code_fence() {
        let raw = "```json\n{\"signals\":[{\"session_id\":\"s1\",\"signal_type\":\"missing_skill\",\"skill_id\":null,\"skill_kind\":\"mcp\",\"evidence\":[\"用户问怎么导出\"],\"suggested_action\":\"新建导出技能\",\"confidence\":0.8}]}\n```";
        let sigs = parse_llm_signals(raw).expect("parse");
        assert_eq!(sigs.len(), 1);
        match &sigs[0] {
            EvolutionSignal::SessionInsight {
                signal_type, skill_id, confidence, ..
            } => {
                assert_eq!(*signal_type, SessionSignalType::MissingSkill);
                assert!(skill_id.is_none());
                assert!((*confidence - 0.8).abs() < 1e-6);
            }
            _ => panic!("wrong variant"),
        }
    }

    #[test]
    fn parse_llm_signals_drops_below_threshold() {
        let raw = "{\"signals\":[{\"session_id\":\"s1\",\"signal_type\":\"frequent_correction\",\"skill_id\":\"sk1\",\"skill_kind\":\"mcp\",\"evidence\":[\"x\"],\"suggested_action\":\"y\",\"confidence\":0.4}]}";
        let sigs = parse_llm_signals(raw).expect("parse");
        assert!(sigs.is_empty(), "below-threshold signal must be dropped");
    }

    #[test]
    fn parse_llm_signals_rejects_garbage() {
        assert!(parse_llm_signals("not json").is_err());
        assert!(parse_llm_signals("").is_err());
    }

    #[test]
    fn truncate_chars_is_utf8_safe() {
        let s = "你好世界abc";
        let t = truncate_chars(s, 4);
        assert_eq!(t.chars().count(), 4);
        assert!(t.ends_with('…'));
    }

    #[test]
    fn strip_code_fence_variants() {
        assert_eq!(strip_code_fence("```json\n{}\n```"), "{}");
        assert_eq!(strip_code_fence("```\n{}\n```"), "{}");
        assert_eq!(strip_code_fence("  {}  "), "{}");
    }
}
