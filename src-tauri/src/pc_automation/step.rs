// Copyright (c) 2026 AIMarketing
//
// Step / outcome / error vocabulary used by the domain-aware
// router. Pure data — no I/O — so it can be freely shared
// between the router, the executor, the recorder and the IPC
// surface.
//
// IMPORTANT (uirap改造技术方案.md §4):
//   * `Uia` and `Cdp` are **domain primaries**, not a 3-tier
//     cascade. The router picks exactly one of them per step
//     based on the step's `app_profile`:
//       - MFC / SelfDraw profile → `Uia` primary
//       - Electron / Web profile → `Cdp` primary
//   * `Ocr` is the **shared fallback** for both domains
//     (self-drawn buttons on desktop, canvas / image-only
//     elements on web).
//   * `Vlm` is the **pre-error escalation** — it is NOT a
//     tier in the cascade. The router never picks it; only
//     the executor escalates to it after `StructuredMiss`.

use serde::{Deserialize, Serialize};
use std::fmt;

use crate::pc_automation::parse_error::ParseError;

/// Which tier a step *can* be dispatched to. The router picks
/// exactly one primary per step based on the step's domain; on
/// miss the router cascades to `Ocr`. `Vlm` is reserved for
/// the executor's pre-error escalation path.
#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum StepStrategy {
    /// Desktop primary — UI Automation tree query.
    Uia,
    /// Web primary — Chrome DevTools Protocol DOM query.
    Cdp,
    /// Cross-domain fallback — OCR text-anchor match.
    Ocr,
    /// Pre-error escalation — visual LLM rescue. Set by the
    /// executor when the structured tiers have all missed; the
    /// router never assigns this directly.
    Vlm,
}

/// One executable unit in a skill / recipe. The router takes
/// ownership of a `&PcStep` and decides which backend to dispatch
/// to, falling through primary → OCR on miss. `app_profile`
/// drives the *domain* (Desktop vs Web) which in turn drives
/// the primary tier choice.
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct PcStep {
    pub id: String,
    pub description: String,
    pub app_profile: Option<String>,
    pub strategy: StepStrategy,
    pub primary_selector: String,
    pub fallback_selectors: Vec<String>,
    /// 录制时捕获的鼠标坐标（屏幕绝对坐标）。
    /// 当 UiaSelector find_by 失败时，用此坐标做 enigo 坐标点击 fallback，
    /// 避免直接进入 OCR/VLM 链（开销大）。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub recorded_coords: Option<(i32, i32)>,
}

/// Result of a successful (non-failed) router attempt.
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct StepOutcome {
    pub strategy_used: StepStrategy,
    pub latency_ms: u64,
    pub action_taken: String,
}

/// Reasons the router can give up on a step. Each variant is
/// intentionally rich so the caller can decide whether to fall
/// back to VLM rescue, log a metric, or mark the step for
/// relearning.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RouterError {
    /// The primary tier (UIA or CDP, chosen by domain) missed.
    /// `reason` is the backend's error message verbatim.
    PrimaryMiss(String),
    /// The primary tier missed AND the OCR fallback missed too.
    /// Carries both error messages so the executor can log them
    /// and the VLM rescue can include them in the dynamic prompt.
    /// This is the signal to escalate to VLM rescue.
    StructuredMiss { primary: String, fallback: String },
}

impl fmt::Display for RouterError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            RouterError::PrimaryMiss(reason) => write!(f, "primary miss: {}", reason),
            RouterError::StructuredMiss { primary, fallback } => write!(
                f,
                "structured miss (primary: {}; fallback: {}) — escalate to VLM",
                primary, fallback
            ),
        }
    }
}

impl std::error::Error for RouterError {}

impl From<ParseError> for RouterError {
    fn from(e: ParseError) -> Self {
        RouterError::PrimaryMiss(format!("parse error: {}", e))
    }
}
