// Copyright (c) 2026 tupAI
//
// EvolutionGate — 统一进化评估门控 (Phase 1)。
//
// 把一条 `EvolutionSignal` 转成一个带评估结果的 `ProposalResult`:
//   1. SessionInsight (MissingSkill)       → LLM 生成新 skill.md → SkillEvaluator 5 维评估
//   2. SessionInsight (FrequentCorrection) → LLM 改写现有 skill.md → SkillEvaluator 评估
//   3. SessionInsight (NegativeRating)     → 同上 (退化诊断 + 修复建议)
//   4. SessionInsight (RepetitiveAction)   → LLM 固化为静态值 (移除 prompt) → SkillEvaluator
//   5. Telemetry / MergeCandidate / MemoryLinked → PassThrough (交给既有 AutoSkillEngine 处理)
//
// 双 gate 规则:
//   * MissingSkill (新建)        → 必须过 SkillEvaluator (Accept 或 NeedsReview) + SandboxRunner dry_run
//   * FrequentCorrection (改已有) → 必须过 SkillEvaluator 且 new_score >= old_score
//   * 任意 Reject → 不进 draft (但调用方应记 consumed=2 拒绝留痕)
//
// LLM 调用走 MCP `llm.stream_request` (hermes_llm_complete_messages), 无需 LLMServiceConfig。

use serde::{Deserialize, Serialize};

use crate::hermes::dedup_index::DedupIndex;
use crate::hermes::evolution_signal::{
    EvolutionSignal, SessionSignalType, SignalSource, SkillKind, CONFIDENCE_THRESHOLD,
};
use crate::hermes::llm_service::hermes_llm_complete_messages;
use crate::hermes::skill_evaluator::proposal::{ProposalSource, SkillProposal};
use crate::hermes::skill_evaluator::{EvalVerdict, SkillEvaluation, SkillEvaluator};
use crate::hermes::types::VLMMessage;

/// 门控处理的最终结果。调用方 (orchestrator) 据此决定是否写 draft。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProposalResult {
    pub signal_id: String,
    /// 生成/改写的 skill_id。MissingSkill 时由 LLM 命名 (kebab-case)。
    pub skill_id: String,
    pub skill_kind: SkillKind,
    pub source_kind: SignalSource,
    /// LLM 产出的完整 skill.md (front matter + body)。
    pub proposed_skill_md: String,
    /// SkillEvaluator 的 5 维评估结果。
    pub evaluation: SkillEvaluation,
    /// 改已有技能时, 旧版本评分 (来自 skill_score_eval)。MissingSkill 时为 None。
    pub old_score: Option<f32>,
    /// 新评分 (取 evaluation.total)。
    pub new_score: f32,
    /// 原始证据片段 (脱敏后), 落 draft 时透传给前端展示。
    pub evidence: Vec<String>,
    /// LLM 给出的进化建议 (人类可读)。
    pub suggested_action: String,
    /// 是否应进入 draft 队列。
    pub should_propose: bool,
    /// 不进 draft 的原因 (should_propose=false 时填)。
    pub skip_reason: Option<String>,
}

/// PassThrough: 信号不由本 gate 处理, 交给既有 AutoSkillEngine。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PassThrough {
    pub signal_id: String,
    pub source_kind: SignalSource,
    pub reason: String,
}

/// gate 对一条信号的处理结果。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum GateOutcome {
    /// 已评估, 调用方据此决定是否写 draft。
    Evaluated(ProposalResult),
    /// 交给既有 AutoSkillEngine 处理 (Telemetry / MergeCandidate / MemoryLinked)。
    PassThrough(PassThrough),
}

#[derive(Debug, thiserror::Error)]
pub enum GateError {
    #[error("llm error: {0}")]
    Llm(String),
    #[error("parse error: {0}")]
    Parse(String),
    #[error("confidence {0} below threshold {1}")]
    LowConfidence(f32, f32),
    #[error("missing current skill_md for {0}")]
    MissingCurrent(String),
}

/// 无状态门控。dedup index 由调用方注入 (跨信号共享, 让重复提案扣分)。
pub struct EvolutionGate {
    dedup: DedupIndex,
}

impl EvolutionGate {
    pub fn new() -> Self {
        Self { dedup: DedupIndex::new() }
    }

    /// 处理一条信号。`current_skill_md` 仅 FrequentCorrection / NegativeRating /
    /// RepetitiveAction 需要; MissingSkill 传 None。
    /// LLM 经 MCP `llm.stream_request` 始终可用; MCP 失败时 `generate_skill_md`
    /// 返回 `GateError::Llm`, 调用方据此标 `consumed=2` 拒绝留痕。
    pub async fn handle(
        &self,
        signal: &EvolutionSignal,
        current_skill_md: Option<&str>,
    ) -> Result<GateOutcome, GateError> {
        match signal {
            EvolutionSignal::SessionInsight {
                signal_id,
                session_id: _,
                skill_id,
                skill_kind,
                signal_type,
                evidence,
                suggested_action,
                confidence,
            } => {
                // 置信度门控
                if *confidence < CONFIDENCE_THRESHOLD {
                    return Ok(GateOutcome::Evaluated(ProposalResult {
                        signal_id: signal_id.clone(),
                        skill_id: skill_id.clone().unwrap_or_else(|| "unknown".to_string()),
                        skill_kind: *skill_kind,
                        source_kind: SignalSource::SessionInsight,
                        proposed_skill_md: String::new(),
                        evaluation: SkillEvaluation {
                            proposal_id: signal_id.clone(),
                            scores: Default::default(),
                            total: 0.0,
                            verdict: EvalVerdict::Reject,
                            issues: vec![],
                            evaluated_at: chrono::Utc::now(),
                            degraded: false,
                        },
                        old_score: None,
                        new_score: 0.0,
                        evidence: evidence.clone(),
                        suggested_action: suggested_action.clone(),
                        should_propose: false,
                        skip_reason: Some(format!(
                            "confidence {} < threshold {}",
                            confidence, CONFIDENCE_THRESHOLD
                        )),
                    }));
                }

                // LLM 始终经 MCP 可用 (hermes_llm_complete_messages); MCP 失败时
                // generate_skill_md 返回 GateError::Llm, 调用方据此标 consumed=2。
                match signal_type {
                    SessionSignalType::MissingSkill => {
                        let result = self
                            .handle_missing_skill(signal_id, skill_id, *skill_kind, evidence, suggested_action)
                            .await?;
                        Ok(GateOutcome::Evaluated(result))
                    }
                    SessionSignalType::FrequentCorrection
                    | SessionSignalType::NegativeRating
                    | SessionSignalType::RepetitiveAction => {
                        let current = current_skill_md.ok_or_else(|| {
                            GateError::MissingCurrent(skill_id.clone().unwrap_or_default())
                        })?;
                        let result = self
                            .handle_existing_skill_fix(
                                signal_id,
                                skill_id.as_deref().unwrap_or("unknown"),
                                *skill_kind,
                                *signal_type,
                                evidence,
                                suggested_action,
                                current,
                            )
                            .await?;
                        Ok(GateOutcome::Evaluated(result))
                    }
                }
            }
            // PassThrough: 既有 AutoSkillEngine 的领地
            EvolutionSignal::Telemetry { signal_id, .. } => Ok(GateOutcome::PassThrough(PassThrough {
                signal_id: signal_id.clone(),
                source_kind: SignalSource::Telemetry,
                reason: "telemetry signal handled by AutoSkillEngine::scan_for_optimization".to_string(),
            })),
            EvolutionSignal::MergeCandidate { signal_id, .. } => Ok(GateOutcome::PassThrough(PassThrough {
                signal_id: signal_id.clone(),
                source_kind: SignalSource::MergeCandidate,
                reason: "merge signal handled by AutoSkillEngine::scan_merge_candidates".to_string(),
            })),
            EvolutionSignal::MemoryLinked { signal_id, .. } => Ok(GateOutcome::PassThrough(PassThrough {
                signal_id: signal_id.clone(),
                source_kind: SignalSource::MemoryLinked,
                reason: "memory-linked signal deferred to Phase 2".to_string(),
            })),
        }
    }

    /// MissingSkill: LLM 生成全新 skill.md, SkillEvaluator 评估 (source=Teaching)。
    async fn handle_missing_skill(
        &self,
        signal_id: &str,
        skill_id: &Option<String>,
        skill_kind: SkillKind,
        evidence: &[String],
        suggested_action: &str,
    ) -> Result<ProposalResult, GateError> {
        let skill_md = generate_skill_md(None, evidence, suggested_action, skill_kind).await?;

        let proposal = SkillProposal::new(signal_id, ProposalSource::Teaching, &skill_md);
        let evaluation = SkillEvaluator::new(&self.dedup).evaluate(&proposal, false);

        let new_score = evaluation.total;
        let should_propose = matches!(evaluation.verdict, EvalVerdict::Accept | EvalVerdict::NeedsReview);

        Ok(ProposalResult {
            signal_id: signal_id.to_string(),
            skill_id: skill_id
                .clone()
                .unwrap_or_else(|| extract_skill_name(&skill_md).unwrap_or_else(|| "new-skill".to_string())),
            skill_kind,
            source_kind: SignalSource::SessionInsight,
            proposed_skill_md: skill_md,
            evaluation,
            old_score: None,
            new_score,
            evidence: evidence.to_vec(),
            suggested_action: suggested_action.to_string(),
            should_propose,
            skip_reason: if should_propose {
                None
            } else {
                Some(format!("verdict={:?} (total={:.2})", EvalVerdict::Reject, new_score))
            },
        })
    }

    /// FrequentCorrection / NegativeRating / RepetitiveAction: LLM 改写现有 skill.md。
    /// source=Healing, lineage.parent_skill_id = skill_id。
    async fn handle_existing_skill_fix(
        &self,
        signal_id: &str,
        skill_id: &str,
        skill_kind: SkillKind,
        signal_type: SessionSignalType,
        evidence: &[String],
        suggested_action: &str,
        current_skill_md: &str,
    ) -> Result<ProposalResult, GateError> {
        let skill_md = generate_skill_md(Some(current_skill_md), evidence, suggested_action, skill_kind).await?;

        let mut proposal = SkillProposal::new(signal_id, ProposalSource::Healing, &skill_md);
        proposal.lineage.parent_skill_id = skill_id.to_string();

        let evaluation = SkillEvaluator::new(&self.dedup).evaluate(&proposal, false);

        let new_score = evaluation.total;
        // 改已有: 规范要求 new_score >= old_score (old_score 来自 skill_score_eval,
        // 由调用方 orchestrator 回填)。当前 old_score 恒为 None (Major): orchestrator
        // 尚未查询 skill_score_eval 并经 handle/handle_existing_skill_fix 传入,
        // 这里退回用 CONFIDENCE_THRESHOLD 兜底, 避免低质量提案进 draft。
        // TODO(hermes/evolution): orchestrator 应在调用 gate 前从 skill_score_eval
        // 取 old_score, 扩展 handle/handle_existing_skill_fix 签名接收 old_score,
        // 把此处改为 `new_score >= old_score` (old_score 为 None 时再回退到常量)。
        let should_propose = matches!(evaluation.verdict, EvalVerdict::Accept | EvalVerdict::NeedsReview)
            && new_score >= CONFIDENCE_THRESHOLD;

        let _ = signal_type; // 仅用于 prompt 上下文, 已在 generate_skill_md 内通过 suggested_action 传递
        Ok(ProposalResult {
            signal_id: signal_id.to_string(),
            skill_id: skill_id.to_string(),
            skill_kind,
            source_kind: SignalSource::SessionInsight,
            proposed_skill_md: skill_md,
            evaluation,
            old_score: None, // TODO: orchestrator 回填 (见上方 should_propose 注释)
            new_score,
            evidence: evidence.to_vec(),
            suggested_action: suggested_action.to_string(),
            should_propose,
            skip_reason: if should_propose {
                None
            } else {
                Some(format!(
                    "verdict={:?} or new_score {:.2} < CONFIDENCE_THRESHOLD",
                    EvalVerdict::Reject, new_score
                ))
            },
        })
    }

}

impl Default for EvolutionGate {
    fn default() -> Self { Self::new() }
}

// === LLM skill.md 生成 =====================================================

/// 调 LLM 生成/改写 skill.md。
/// - `current_skill_md = None` → MissingSkill: 全新生成
/// - `Some(md)` → 改写: 在现有基础上按 suggested_action 修复
async fn generate_skill_md(
    current_skill_md: Option<&str>,
    evidence: &[String],
    suggested_action: &str,
    skill_kind: SkillKind,
) -> Result<String, GateError> {
    let system = "You are a skill author. Output a valid skill.md with YAML front matter (between --- lines) \
                  followed by a markdown body. Required front matter fields: name (kebab-case), description, \
                  version, entrypoints (list). For automation skills also include preferred_execution_type, \
                  software_name or browser_url, and steps. Output ONLY the skill.md, no commentary.";

    let user = if let Some(cur) = current_skill_md {
        format!(
            "Improve this existing skill based on the session evidence.\n\n\
             Current skill.md:\n```\n{}\n```\n\n\
             Evidence (verbatim user feedback):\n{}\n\n\
             Suggested improvement: {}\n\n\
             Skill kind: {}\n\
             Output the improved skill.md:",
            cur,
            evidence.iter().map(|e| format!("- {}", e)).collect::<Vec<_>>().join("\n"),
            suggested_action,
            skill_kind.as_str(),
        )
    } else {
        format!(
            "Create a new skill that would have helped in this session.\n\n\
             Evidence (verbatim user feedback):\n{}\n\n\
             Suggested action: {}\n\n\
             Skill kind: {}\n\
             Output the new skill.md:",
            evidence.iter().map(|e| format!("- {}", e)).collect::<Vec<_>>().join("\n"),
            suggested_action,
            skill_kind.as_str(),
        )
    };

    let messages = vec![
        VLMMessage { role: "system".to_string(), content: system.to_string(), ..Default::default() },
        VLMMessage { role: "user".to_string(), content: user, ..Default::default() },
    ];

    let content = hermes_llm_complete_messages(messages)
        .await
        .map_err(GateError::Llm)?;
    let trimmed = content.trim();
    if trimmed.is_empty() {
        return Err(GateError::Llm("LLM returned empty skill.md".to_string()));
    }
    // 提取 --- 之间的部分 (LLM 偶尔会包 ```markdown fence)
    Ok(extract_skill_md(trimmed))
}

/// 从 LLM 输出中提取 skill.md 主体 (去 code fence, 保留 --- front matter)。
fn extract_skill_md(raw: &str) -> String {
    let mut s = raw.trim().to_string();
    // 去 ```markdown 或 ``` fence
    if s.starts_with("```") {
        let after_first_fence = s.split_once('\n').map(|x| x.1).unwrap_or(&s);
        if let Some(end) = after_first_fence.rfind("```") {
            s = after_first_fence[..end].trim().to_string();
        } else {
            s = after_first_fence.trim().to_string();
        }
    }
    s
}

/// 从 skill.md front matter 提取 name 字段 (轻量正则, 避免完整 YAML 解析)。
fn extract_skill_name(skill_md: &str) -> Option<String> {
    let front = split_front_matter(skill_md)?.0;
    for line in front.lines() {
        let trimmed = line.trim();
        if let Some(rest) = trimmed.strip_prefix("name:") {
            let name = rest.trim().trim_matches('"').trim_matches('\'');
            if !name.is_empty() {
                return Some(name.to_string());
            }
        }
    }
    None
}

/// 简单 split --- front matter。返回 (front, body)。
fn split_front_matter(md: &str) -> Option<(String, String)> {
    let trimmed = md.trim_start();
    if !trimmed.starts_with("---") { return None; }
    let after_open = &trimmed[3..];
    let after_open = after_open.trim_start_matches('\n');
    let close = after_open.find("\n---")?;
    let front = after_open[..close].to_string();
    let body = after_open[close + 4..].trim_start_matches('\n').to_string();
    Some((front, body))
}

// Re-export removed: SkillProposal / ProposalSource imported directly above.
// `crate::hermes::skill_evaluator::proposal` is already `pub mod`, no re-export needed.

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extract_skill_md_strips_code_fence() {
        let raw = "```markdown\n---\nname: foo\n---\nbody\n```";
        let out = extract_skill_md(raw);
        assert!(out.starts_with("---"));
        assert!(out.contains("name: foo"));
        assert!(!out.contains("```"));
    }

    #[test]
    fn extract_skill_name_parses_front_matter() {
        let md = "---\nname: open-notepad\ndescription: x\n---\nbody";
        assert_eq!(extract_skill_name(md).as_deref(), Some("open-notepad"));
    }

    #[test]
    fn split_front_matter_handles_no_frontmatter() {
        assert!(split_front_matter("just body").is_none());
    }

    #[test]
    fn passthrough_telemetry_signal() {
        // 不实际调 LLM, 仅验证 PassThrough 分支
        let gate = EvolutionGate::new();
        // 用 tokio runtime 跑 async
        let rt = tokio::runtime::Runtime::new().unwrap();
        let sig = EvolutionSignal::Telemetry {
            signal_id: "s1".to_string(),
            skill_id: "k".to_string(),
            scene: "default".to_string(),
            skill_kind: SkillKind::Mcp,
            failure_rate: 0.5,
            run_count: 10,
            last_error: None,
        };
        let outcome = rt.block_on(gate.handle(&sig, None)).unwrap();
        match outcome {
            GateOutcome::PassThrough(pt) => {
                assert_eq!(pt.source_kind, SignalSource::Telemetry);
            }
            _ => panic!("expected PassThrough"),
        }
    }
}
