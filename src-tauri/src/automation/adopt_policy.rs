// Copyright (c) 2026 tupAI
//
// Adoption policy thresholds.
//
// Pure-function layer shared by `skill::registry` and the
// `commands::skill` Tauri surface. Keeping the policy as a
// dependency-free function (no I/O, no shared state) means the
// front-end can mirror the same bands in `SkillInbox.jsx` without
// drift, and unit tests can hammer the boundaries cheaply.

use serde::{Deserialize, Serialize};

/// Score at or above which we auto-accept the proposal and start
/// the atomic swap (灰度 5% → 100%). Below this we always require a
/// human in the loop.
pub const HIGH_CONFIDENCE: f32 = 0.85;

/// Lower bound for the "needs review" band. Below this we reject outright.
pub const REVIEW_THRESHOLD: f32 = 0.60;

/// The three decisions a `SkillEvaluation` can map to.
///
/// We serialize as a snake_case string so the front-end can branch
/// on the `decision` field without needing the enum's full Rust
/// path (`AutoAccept` etc. stay camelCase for our internal use).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Decision {
    /// Score is high enough — registry should swap versions in
    /// place (running_new -> fallback_old -> retire_old).
    AutoAccept,
    /// Score is middling — surface in the UI inbox and let the
    /// user pick.
    NeedsReview,
    /// Score is too low — record the rejection reason to
    /// `skill_proposals.reason` and move on.
    Reject,
}

impl Decision {
    /// Short string the front-end can render inside badges
    /// without needing a second lookup. Mirrors
    /// `t("skillInbox.decision.*")` in the locales files.
    pub fn as_str(&self) -> &'static str {
        match self {
            Decision::AutoAccept => "auto_accept",
            Decision::NeedsReview => "needs_review",
            Decision::Reject => "reject",
        }
    }
}

/// Classify a `total_score` into one of the three bands. The
/// boundaries are inclusive on the low side so a perfect 0.85
/// still auto-accepts (matches the user-visible "≥ 0.85" wording
/// in the v4 plan).
///
/// Anything that is not a finite number (NaN, infinity) is
/// rejected — we never want to auto-accept a malformed score.
pub fn classify(total_score: f32) -> Decision {
    if !total_score.is_finite() {
        return Decision::Reject;
    }
    if total_score >= HIGH_CONFIDENCE {
        Decision::AutoAccept
    } else if total_score >= REVIEW_THRESHOLD {
        Decision::NeedsReview
    } else {
        Decision::Reject
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classifies_three_bands() {
        assert_eq!(classify(0.95), Decision::AutoAccept);
        assert_eq!(classify(0.85), Decision::AutoAccept);
        assert_eq!(classify(0.80), Decision::NeedsReview);
        assert_eq!(classify(0.60), Decision::NeedsReview);
        assert_eq!(classify(0.59), Decision::Reject);
        assert_eq!(classify(0.00), Decision::Reject);
    }

    #[test]
    fn rejects_non_finite_scores() {
        assert_eq!(classify(f32::NAN), Decision::Reject);
        assert_eq!(classify(f32::INFINITY), Decision::Reject);
        assert_eq!(classify(f32::NEG_INFINITY), Decision::Reject);
    }

    #[test]
    fn decision_strings_match_frontend_keys() {
        // Locks the wire contract: the strings here are exactly
        // what `SkillInbox.jsx` and the i18n `decision.*` keys use.
        assert_eq!(Decision::AutoAccept.as_str(), "auto_accept");
        assert_eq!(Decision::NeedsReview.as_str(), "needs_review");
        assert_eq!(Decision::Reject.as_str(), "reject");
    }
}
