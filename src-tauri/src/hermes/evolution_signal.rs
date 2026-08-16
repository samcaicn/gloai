// Copyright (c) 2026 AIMarketing
//
// Hermes 自进化 — 统一进化信号类型 (Phase 1)。
//
// 本模块是全栈契约的"单一事实来源": 后端采集器 / SessionAnalyzer /
// EvolutionGate / ProposalRouter / 前端 AutoskillScene 全部引用这里定义的
// 类型。任何字段变更都会触发跨模块编译错误, 强制同步更新。
//
// 设计要点:
//   * 三类技能 (mcp / automation / builtin) 用 `SkillKind` 区分, 让
//     `UpgradeWriter` 知道往哪个存储落盘。
//   * `EvolutionSignal` 用 `#[serde(tag = "kind")]` 内部标签枚举, 单条
//     信号可整体 JSON 序列化落 `evolution_signals.evidence_json` 列。
//   * Phase 1 仅落地 mcp 路径; automation 留待 Phase 2 (但类型已就绪);
//     builtin 暂不支持 (UpgradeWriter 返回 Skipped)。

use serde::{Deserialize, Serialize};

/// 技能类型。决定 `UpgradeWriter` 的落盘目标。
///
/// - `Mcp`        → `<app_data>/skills_optimized/<id>.md` + `<hermes_home>/skills/<id>/SKILL.md`
/// - `Automation` → 加密 Skill store `pc_automation::skill::storage` + `skill_version_manage` 表
/// - `Builtin`    → Phase 1 不支持升级 (返回 Skipped); Phase 2 引入 override 覆盖层
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "lowercase")]
pub enum SkillKind {
    #[default]
    Mcp,
    Automation,
    Builtin,
}

impl SkillKind {
    /// 落地存储的字符串标识 (用于 draft 表的 skill_kind 列)。
    pub fn as_str(&self) -> &'static str {
        match self {
            SkillKind::Mcp => "mcp",
            SkillKind::Automation => "automation",
            SkillKind::Builtin => "builtin",
        }
    }

    /// 从字符串反解。未知值降级到 `Mcp` (最安全的默认, 落盘到文件即可见)。
    pub fn from_str_lossy(s: &str) -> Self {
        match s {
            "automation" => SkillKind::Automation,
            "builtin" => SkillKind::Builtin,
            _ => SkillKind::Mcp,
        }
    }
}

/// 会话内容分析提炼出的信号类型。决定 EvolutionGate 走哪条评估分支。
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum SessionSignalType {
    /// 用户多次问同一类问题, 但没有技能覆盖 → 新建技能。
    MissingSkill,
    /// 用户对同一技能多次纠正参数/步骤 → 优化参数或步骤。
    FrequentCorrection,
    /// turn_rating 连续低分且关联到某 skill_id → 退化诊断。
    NegativeRating,
    /// 会话中用户手动重复了一串 UI 操作 → 录制补强信号
    /// (含 InteractionPrompt 回传: 用户每次都在同一 prompt 处手动选同一值)。
    RepetitiveAction,
}

impl SessionSignalType {
    pub fn as_str(&self) -> &'static str {
        match self {
            SessionSignalType::MissingSkill => "missing_skill",
            SessionSignalType::FrequentCorrection => "frequent_correction",
            SessionSignalType::NegativeRating => "negative_rating",
            SessionSignalType::RepetitiveAction => "repetitive_action",
        }
    }

    pub fn from_str_lossy(s: &str) -> Option<Self> {
        match s {
            "missing_skill" => Some(Self::MissingSkill),
            "frequent_correction" => Some(Self::FrequentCorrection),
            "negative_rating" => Some(Self::NegativeRating),
            "repetitive_action" => Some(Self::RepetitiveAction),
            _ => None,
        }
    }
}

/// 进化信号来源。对应不同采集器, 也决定 DraftRow 的 `source_kind` 列。
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "lowercase")]
pub enum SignalSource {
    /// 执行遥测: worker_task_log / trajectory_store
    #[default]
    Telemetry,
    /// 会话洞察: SessionAnalyzer 从 messages + turn_rating 提炼
    SessionInsight,
    /// 记忆升级联动: memory_evolution 触发 Upgraded 时跟进
    MemoryLinked,
    /// 合并候选: action 序列相似 (现有 AutoSkillEngine 逻辑)
    MergeCandidate,
}

impl SignalSource {
    pub fn as_str(&self) -> &'static str {
        match self {
            SignalSource::Telemetry => "telemetry",
            SignalSource::SessionInsight => "session_insight",
            SignalSource::MemoryLinked => "memory_linked",
            SignalSource::MergeCandidate => "merge",
        }
    }
}

/// 统一进化信号。一个信号 = 一次"应该改进某个技能"的证据。
///
/// 落盘到 `evolution_signals` 表时, 整体序列化为 `evidence_json`。
/// `signal_id` 用 `sig_{ulid}` 风格, 由调用方生成 (避免 DB 自增跨库不一致)。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", tag = "kind")]
pub enum EvolutionSignal {
    /// 执行遥测信号。
    Telemetry {
        signal_id: String,
        skill_id: String,
        scene: String,
        skill_kind: SkillKind,
        failure_rate: f32,
        run_count: u32,
        last_error: Option<String>,
    },
    /// 会话洞察信号 (Phase 1 主要信号源)。
    SessionInsight {
        signal_id: String,
        session_id: String,
        /// None = 该会话暴露了"缺一个技能" (MissingSkill)
        skill_id: Option<String>,
        skill_kind: SkillKind,
        signal_type: SessionSignalType,
        /// 原始消息片段 (脱敏后), 让用户在 confirm UI 中可追溯。
        evidence: Vec<String>,
        /// LLM 给出的进化建议 (人类可读)。
        suggested_action: String,
        /// 0.0..=1.0。低于 `CONFIDENCE_THRESHOLD` 的信号被 EvolutionGate 直接丢弃。
        confidence: f32,
    },
    /// 记忆升级联动信号。
    MemoryLinked {
        signal_id: String,
        memory_id: String,
        parent_skill_id: Option<String>,
        task_type: String,
        skill_kind: SkillKind,
    },
    /// 合并候选信号 (保留现有 AutoSkillEngine 逻辑的产出形状)。
    MergeCandidate {
        signal_id: String,
        skill_ids: Vec<String>,
        similarity: f32,
        action_signature: String,
        skill_kind: SkillKind,
    },
}

impl EvolutionSignal {
    /// 信号的统一 id (无论变体)。
    pub fn signal_id(&self) -> &str {
        match self {
            EvolutionSignal::Telemetry { signal_id, .. }
            | EvolutionSignal::SessionInsight { signal_id, .. }
            | EvolutionSignal::MemoryLinked { signal_id, .. }
            | EvolutionSignal::MergeCandidate { signal_id, .. } => signal_id,
        }
    }

    /// 信号源分类。
    pub fn source(&self) -> SignalSource {
        match self {
            EvolutionSignal::Telemetry { .. } => SignalSource::Telemetry,
            EvolutionSignal::SessionInsight { .. } => SignalSource::SessionInsight,
            EvolutionSignal::MemoryLinked { .. } => SignalSource::MemoryLinked,
            EvolutionSignal::MergeCandidate { .. } => SignalSource::MergeCandidate,
        }
    }

    /// 信号关联的技能 id (MissingSkill / MergeCandidate 可能无单一关联)。
    pub fn skill_id(&self) -> Option<&str> {
        match self {
            EvolutionSignal::Telemetry { skill_id, .. } => Some(skill_id),
            EvolutionSignal::SessionInsight { skill_id, .. } => skill_id.as_deref(),
            EvolutionSignal::MemoryLinked { parent_skill_id, .. } => parent_skill_id.as_deref(),
            EvolutionSignal::MergeCandidate { .. } => None,
        }
    }

    /// 信号期望升级的技能类型。
    pub fn skill_kind(&self) -> SkillKind {
        match self {
            EvolutionSignal::Telemetry { skill_kind, .. }
            | EvolutionSignal::SessionInsight { skill_kind, .. }
            | EvolutionSignal::MemoryLinked { skill_kind, .. }
            | EvolutionSignal::MergeCandidate { skill_kind, .. } => *skill_kind,
        }
    }
}

/// 置信度门槛。低于此值的 SessionInsight 信号被 EvolutionGate 直接丢弃,
/// 不进入 draft 队列, 避免低质量建议轰炸用户。
pub const CONFIDENCE_THRESHOLD: f32 = 0.6;

/// 单次 SessionAnalyzer 扫描窗口默认覆盖时长 (24h)。
pub const DEFAULT_ANALYSIS_WINDOW_MS: i64 = 24 * 60 * 60 * 1000;

/// 单次 analyze_window LLM 调用最多喂给 LLM 的会话数 (按 低 turn_rating +
/// 长会话 排序后取 top)。控制 token 成本。
pub const MAX_SESSIONS_PER_LLM_CALL: usize = 20;

/// 单会话喂给 LLM 的摘要最大 token 估算 (字符数近似)。500 字符 ≈ 250 token。
pub const MAX_CHARS_PER_SESSION_BRIEF: usize = 500;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn skill_kind_round_trip() {
        for k in [SkillKind::Mcp, SkillKind::Automation, SkillKind::Builtin] {
            assert_eq!(SkillKind::from_str_lossy(k.as_str()), k);
        }
        // 未知值降级到 Mcp
        assert_eq!(SkillKind::from_str_lossy("unknown"), SkillKind::Mcp);
    }

    #[test]
    fn signal_type_round_trip() {
        for s in [
            SessionSignalType::MissingSkill,
            SessionSignalType::FrequentCorrection,
            SessionSignalType::NegativeRating,
            SessionSignalType::RepetitiveAction,
        ] {
            assert_eq!(SessionSignalType::from_str_lossy(s.as_str()), Some(s));
        }
        assert_eq!(SessionSignalType::from_str_lossy("bogus"), None);
    }

    #[test]
    fn session_insight_accessors() {
        let sig = EvolutionSignal::SessionInsight {
            signal_id: "sig_1".into(),
            session_id: "sess_1".into(),
            skill_id: Some("open-notepad".into()),
            skill_kind: SkillKind::Mcp,
            signal_type: SessionSignalType::FrequentCorrection,
            evidence: vec!["用户说: 再加个延迟".into()],
            suggested_action: "在 step 2 后增加 500ms Wait".into(),
            confidence: 0.8,
        };
        assert_eq!(sig.signal_id(), "sig_1");
        assert_eq!(sig.source(), SignalSource::SessionInsight);
        assert_eq!(sig.skill_id(), Some("open-notepad"));
        assert_eq!(sig.skill_kind(), SkillKind::Mcp);
    }

    #[test]
    fn serde_tag_kind_round_trip() {
        let sig = EvolutionSignal::SessionInsight {
            signal_id: "sig_2".into(),
            session_id: "s".into(),
            skill_id: None,
            skill_kind: SkillKind::Mcp,
            signal_type: SessionSignalType::MissingSkill,
            evidence: vec![],
            suggested_action: "新建技能".into(),
            confidence: 0.7,
        };
        let json = serde_json::to_string(&sig).unwrap();
        assert!(json.contains("\"kind\":\"sessionInsight\""), "json={}", json);
        let back: EvolutionSignal = serde_json::from_str(&json).unwrap();
        assert_eq!(back.signal_id(), "sig_2");
    }
}
