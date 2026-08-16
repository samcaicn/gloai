// Copyright (c) 2026 MeeJoy
//
// 升级状态机 —— 草稿生命周期状态转换规则。
//
// 状态流转：
//   Monitoring → Drafting → Scoring → PendingConfirm → Upgrading →
//   Watching → Running（观察期通过）/ Rollback（分数下降）
//
// 异常分支：
//   Scoring → Rejected（评分不达标）
//   PendingConfirm → Rejected（用户拒绝）
//   Rejected / Rollback → Monitoring（重新监测）
//
// 观察期时长 24 小时，回滚分数阈值 15 分。

use serde::{Deserialize, Serialize};

/// 升级状态。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum UpgradeState {
    Monitoring,     // 监测中
    Drafting,       // 生成草稿中
    Scoring,        // 评分中
    PendingConfirm, // 待用户确认
    Upgrading,      // 升级中
    Watching,       // 观察期（24h）
    Running,        // 正常运行
    Rejected,       // 用户拒绝
    Rollback,       // 回滚
}

impl UpgradeState {
    pub fn as_str(&self) -> &'static str {
        match self {
            UpgradeState::Monitoring => "monitoring",
            UpgradeState::Drafting => "drafting",
            UpgradeState::Scoring => "scoring",
            UpgradeState::PendingConfirm => "pending_confirm",
            UpgradeState::Upgrading => "upgrading",
            UpgradeState::Watching => "watching",
            UpgradeState::Running => "running",
            UpgradeState::Rejected => "rejected",
            UpgradeState::Rollback => "rollback",
        }
    }

    pub fn from_str(s: &str) -> Option<Self> {
        match s {
            "monitoring" => Some(Self::Monitoring),
            "drafting" => Some(Self::Drafting),
            "scoring" => Some(Self::Scoring),
            "pending_confirm" => Some(Self::PendingConfirm),
            "upgrading" => Some(Self::Upgrading),
            "watching" => Some(Self::Watching),
            "running" => Some(Self::Running),
            "rejected" => Some(Self::Rejected),
            "rollback" => Some(Self::Rollback),
            _ => None,
        }
    }
}

/// 状态转换规则。
pub struct UpgradeStateMachine;

impl UpgradeStateMachine {
    /// 检查状态转换是否合法。
    pub fn can_transition(from: UpgradeState, to: UpgradeState) -> bool {
        use UpgradeState::*;
        matches!(
            (from, to),
            (Monitoring, Drafting)
                | (Drafting, Scoring)
                | (Scoring, PendingConfirm)
                | (Scoring, Rejected) // 评分不达标
                | (PendingConfirm, Upgrading)
                | (PendingConfirm, Rejected)
                | (Upgrading, Watching)
                | (Watching, Running) // 观察期通过
                | (Watching, Rollback) // 观察期分数下降
                | (Rollback, Monitoring) // 回滚后重新监测
                | (Rejected, Monitoring) // 拒绝后重新监测
        )
    }

    /// 观察期时长（24小时）。
    pub const WATCH_DURATION_HOURS: i64 = 24;

    /// 回滚分数阈值（下降超过15分）。
    pub const ROLLBACK_SCORE_DROP: i32 = 15;
}
