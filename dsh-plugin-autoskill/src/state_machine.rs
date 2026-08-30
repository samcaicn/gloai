// Upgrade state machine — adapted from safeopcapp.
//
// State flow:
//   Monitoring → Drafting → Scoring → PendingConfirm →
//   Upgrading → Watching → Running / Rollback
//
// Abnormal branches:
//   Scoring → Rejected (score too low)
//   PendingConfirm → Rejected (user rejected)
//   Rejected / Rollback → Monitoring (re-monitor)
//
// Watch duration: 24 hours. Rollback threshold: 15 points.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Hash)]
#[serde(rename_all = "snake_case")]
pub enum UpgradeState {
    Monitoring,
    Drafting,
    Scoring,
    PendingConfirm,
    Upgrading,
    Watching,
    Running,
    Rejected,
    Rollback,
}

impl UpgradeState {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Monitoring => "monitoring",
            Self::Drafting => "drafting",
            Self::Scoring => "scoring",
            Self::PendingConfirm => "pending_confirm",
            Self::Upgrading => "upgrading",
            Self::Watching => "watching",
            Self::Running => "running",
            Self::Rejected => "rejected",
            Self::Rollback => "rollback",
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

/// Duration of the watching period in hours.
pub const WATCH_DURATION_HOURS: i64 = 24;
/// Score drop threshold that triggers automatic rollback.
pub const ROLLBACK_SCORE_DROP: i32 = 15;

pub struct UpgradeStateMachine;

impl UpgradeStateMachine {
    /// Check if a state transition is valid.
    pub fn can_transition(from: UpgradeState, to: UpgradeState) -> bool {
        use UpgradeState::*;
        matches!(
            (from, to),
            (Monitoring, Drafting)
                | (Drafting, Scoring)
                | (Scoring, PendingConfirm)
                | (Scoring, Rejected)
                | (PendingConfirm, Upgrading)
                | (PendingConfirm, Rejected)
                | (Upgrading, Watching)
                | (Watching, Running)
                | (Watching, Rollback)
                | (Rollback, Monitoring)
                | (Rejected, Monitoring)
        )
    }
}
