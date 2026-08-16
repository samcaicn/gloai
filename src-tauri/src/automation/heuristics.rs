// Copyright (c) 2026 tupAI
//
// EvolutionLoop · decision thresholds.
//
// `should_trigger` decides — for a given skill — whether the
// evolution loop should escalate (DeepReanalyze / RewritePrompt),
// accept a light-touch heal, or stay quiet (NoOp). The rules are
// intentionally conservative: we only trigger *expensive* paths
// (deep re-parse, prompt rewrite) when the cheaper signal is
// statistically meaningful.
//
// All thresholds are pure functions of the supplied `RunStats` +
// `SkillLineage` slices — no clock, no I/O, no global state — so
// the same input always produces the same output. This makes the
// function trivially testable and lets us replay historical data
// to tune the thresholds without touching the real engine.

use serde::{Deserialize, Serialize};

/// Maximum number of *consecutive* failures (most recent first) we
/// look at to decide whether a deep re-parse is warranted. Matches
/// the 3-strike rule.
#[allow(dead_code)] // public API for evolution/heuristics; invoked from JS in next PR
pub const CONSECUTIVE_FAILURE_THRESHOLD: u32 = 3;

/// 24-hour adoption floor below which a skill is considered
/// "abandoned" and we trigger a prompt rewrite. The number is the
/// ratio of adopted runs to total runs in the window.
#[allow(dead_code)] // public API for evolution/heuristics; invoked from JS in next PR
pub const ADOPTION_RATE_FLOOR: f32 = 0.30;

/// The 24-hour window (in hours) for the adoption-rate check.
#[allow(dead_code)] // public API for evolution/heuristics; invoked from JS in next PR
pub const ADOPTION_WINDOW_HOURS: u32 = 24;

/// `HealRecord`-style failure category. Mirrors the
/// `error_kind` values written into `skill_runs`.
/// Only `dom_not_found` and `timeout` are
/// treated as "deep re-parse worthy" — everything else is a hard
/// fault that the engine should surface to the user instead of
/// silently retrying.
#[allow(dead_code)] // event-protocol names, wire contract with memory module
pub mod error_kind {
    pub const DOM_NOT_FOUND: &str = "dom_not_found";
    pub const TIMEOUT: &str = "timeout";
    pub const PERMISSION: &str = "permission";
    pub const OTHER: &str = "other";
}

/// Why the evolution loop is considering an action. The same
/// `EvolutionReason` flows into the `EvolutionEvent` written to
/// history so the UI can group by cause.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum EvolutionReason {
    /// 3 (or more) consecutive failures of a skill, with the last
    /// error in `{dom_not_found, timeout}`.
    ConsecutiveFailures { count: u32, last_error: String },
    /// 24h adoption rate dropped below the floor.
    LowAdoptionRate { rate: f32, window_hours: u32 },
    /// Daily 02:00 batch trigger.
    DailyBatchTrigger,
    /// User pressed "立即跑进化" in the UI.
    ManualTrigger,
    /// No actionable signal — the loop just looked and found nothing.
    NoSignal,
}

/// What the loop decided to do for a single skill. The mapping is:
///
///   `LightHeal`       → 90 % path: hand back to `HealingEngine` for
///                       UIA-tree-diff / CDP-DOM-diff micro-corrections
///                       on the step's selector.
///   `DeepReanalyze`   → 10 % path: queue a PaddleOCR-VL-1.6
///                       re-parse of the ocr_anchor segment.
///   `RewritePrompt`   → call the LLM evaluator with a different
///                       system prompt so future proposals for the
///                       same skill are scored more accurately.
///   `NoOp`            → nothing to do.
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum EvolutionAction {
    LightHeal,
    DeepReanalyze,
    RewritePrompt,
    NoOp,
}

/// One row of a skill's recent run history. We don't care about
/// success metrics, only:
///   * was it a success?
///   * what error kind did the failure carry (if any)?
///
/// The caller (EvolutionLoop) is responsible for keeping the slice
/// ordered most-recent-first.
#[allow(dead_code)] // public API for evolution/heuristics; invoked from JS in next PR
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RunStats {
    pub skill_id: String,
    /// Recent runs, most recent first. Empty slice ⇒ "no signal".
    pub recent: Vec<RunSample>,
}

#[allow(dead_code)] // public API for evolution/heuristics; invoked from JS in next PR
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RunSample {
    pub success: bool,
    pub error_kind: Option<String>,
    /// ISO-8601 timestamp. Used by the adoption-rate window filter
    /// (24h). The function does not consult the wall clock itself.
    pub ran_at: chrono::DateTime<chrono::Utc>,
}

/// Adoption / lineage metadata for a single skill. This
/// is produced by the `skill::memory` module. The
/// fields below are what `should_trigger` actually reads; everything
/// else lives in the persistent store and is fetched by
/// `EvolutionLoop::gather_stats` before calling this function.
#[allow(dead_code)] // public API for evolution/heuristics; invoked from JS in next PR
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct SkillLineage {
    pub skill_id: String,
    /// `state` field in `skill_versions` — `candidate` | `running`
    /// | `retired` | `rejected`. We skip retired / rejected skills
    /// in the daily batch (no point re-analyzing dead code).
    pub state: Option<String>,
    /// 0..=1 ratio of adopted runs in the last `ADOPTION_WINDOW_HOURS`.
    /// `None` means "no signal" (no evaluations yet) — caller treats
    /// this as "do not fire `RewritePrompt`".
    pub adoption_rate_24h: Option<f32>,
}

/// The trigger envelope returned by `should_trigger` for a single
/// skill. `EvolutionLoop` collects a `Vec<EvolutionTrigger>` and
/// fans out work to the appropriate subsystem.
#[allow(dead_code)] // public API for evolution/heuristics; invoked from JS in next PR
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EvolutionTrigger {
    pub skill_id: String,
    pub version: u32,
    pub reason: EvolutionReason,
    pub suggested_action: EvolutionAction,
}

/// Core decision function. Pure — no I/O, no global state.
///
/// Rules (v4 §2.6):
///   1. Same `skill_id` with ≥ `CONSECUTIVE_FAILURE_THRESHOLD`
///      consecutive `success=false` AND the latest `error_kind`
///      ∈ `{dom_not_found, timeout}` → `DeepReanalyze`.
///   2. `lineage.adoption_rate_24h.is_some() && < ADOPTION_RATE_FLOOR`
///      → `RewritePrompt`.
///   3. Otherwise → `NoOp`.
///
/// `version` is propagated from the caller's lineage; the function
/// does not invent it (it might be 0 for a never-yet-seen skill).
#[allow(dead_code)] // public API for evolution/heuristics; invoked from JS in next PR
pub fn should_trigger(stats: &RunStats, lineage: &SkillLineage) -> EvolutionAction {
    // Rule 1: 3-strike deep re-parse. We look at the most recent
    // `CONSECUTIVE_FAILURE_THRESHOLD` entries; if they're *all*
    // failures and the last failure is in the
    // {dom_not_found, timeout} bucket, escalate.
    let consecutive_failures = count_trailing_failures(&stats.recent);
    if consecutive_failures >= CONSECUTIVE_FAILURE_THRESHOLD {
        if let Some(last) = stats.recent.first() {
            let kind = last.error_kind.as_deref().unwrap_or(error_kind::OTHER);
            if kind == error_kind::DOM_NOT_FOUND || kind == error_kind::TIMEOUT {
                return EvolutionAction::DeepReanalyze;
            }
        }
    }

    // Rule 2: 24h adoption rate. The lineage owns this number; the
    // function trusts it. `None` ⇒ no signal.
    if let Some(rate) = lineage.adoption_rate_24h {
        if rate < ADOPTION_RATE_FLOOR {
            return EvolutionAction::RewritePrompt;
        }
    }

    EvolutionAction::NoOp
}

/// Build a fully-populated `EvolutionTrigger` for the daily-batch
/// path. The daily batch always sets the reason to
/// `DailyBatchTrigger` even when the action is `NoOp` — that way
/// the UI can distinguish "we looked, found nothing" from "we
/// haven't run yet today".
#[allow(dead_code)] // public API for evolution/heuristics; invoked from JS in next PR
pub fn build_daily_trigger(
    stats: &RunStats,
    lineage: &SkillLineage,
    version: u32,
) -> EvolutionTrigger {
    let action = should_trigger(stats, lineage);
    let reason = match &action {
        EvolutionAction::DeepReanalyze => {
            let count = count_trailing_failures(&stats.recent);
            let last_error = stats
                .recent
                .first()
                .and_then(|s| s.error_kind.clone())
                .unwrap_or_else(|| error_kind::OTHER.to_string());
            EvolutionReason::ConsecutiveFailures { count, last_error }
        }
        EvolutionAction::RewritePrompt => EvolutionReason::LowAdoptionRate {
            rate: lineage.adoption_rate_24h.unwrap_or(0.0),
            window_hours: ADOPTION_WINDOW_HOURS,
        },
        _ => EvolutionReason::NoSignal,
    };
    EvolutionTrigger {
        skill_id: stats.skill_id.clone(),
        version,
        reason,
        suggested_action: action,
    }
}

/// Build a trigger envelope for the manual "立即跑进化" path.
/// Identical decision logic to the daily batch, but the reason is
/// always `ManualTrigger` so the history row makes it clear the
/// human kicked it off.
#[allow(dead_code)] // public API for evolution/heuristics; invoked from JS in next PR
pub fn build_manual_trigger(
    stats: &RunStats,
    lineage: &SkillLineage,
    version: u32,
) -> EvolutionTrigger {
    let action = should_trigger(stats, lineage);
    EvolutionTrigger {
        skill_id: stats.skill_id.clone(),
        version,
        reason: EvolutionReason::ManualTrigger,
        suggested_action: action,
    }
}

/// Count how many most-recent runs were failures, stopping at the
/// first success. Used to detect the "3 in a row" pattern.
#[allow(dead_code)] // helper for should_trigger/build_daily_trigger; callers are the JS-facing API
fn count_trailing_failures(samples: &[RunSample]) -> u32 {
    let mut count: u32 = 0;
    for sample in samples {
        if sample.success {
            break;
        }
        count += 1;
    }
    count
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeZone;

    fn sample(success: bool, kind: Option<&str>) -> RunSample {
        RunSample {
            success,
            error_kind: kind.map(str::to_string),
            ran_at: chrono::Utc.with_ymd_and_hms(2026, 6, 6, 2, 0, 0).unwrap(),
        }
    }

    fn stats(rows: Vec<RunSample>) -> RunStats {
        RunStats {
            skill_id: "s1".into(),
            recent: rows,
        }
    }

    fn lineage(rate: Option<f32>) -> SkillLineage {
        SkillLineage {
            skill_id: "s1".into(),
            state: Some("running".into()),
            adoption_rate_24h: rate,
        }
    }

    #[test]
    fn three_consecutive_dom_failures_triggers_deep() {
        let s = stats(vec![
            sample(false, Some(error_kind::DOM_NOT_FOUND)),
            sample(false, Some(error_kind::DOM_NOT_FOUND)),
            sample(false, Some(error_kind::DOM_NOT_FOUND)),
        ]);
        assert_eq!(should_trigger(&s, &lineage(None)), EvolutionAction::DeepReanalyze);
    }

    #[test]
    fn three_consecutive_timeouts_triggers_deep() {
        let s = stats(vec![
            sample(false, Some(error_kind::TIMEOUT)),
            sample(false, Some(error_kind::TIMEOUT)),
            sample(false, Some(error_kind::TIMEOUT)),
        ]);
        assert_eq!(should_trigger(&s, &lineage(None)), EvolutionAction::DeepReanalyze);
    }

    #[test]
    fn three_consecutive_permission_failures_stays_quiet() {
        let s = stats(vec![
            sample(false, Some(error_kind::PERMISSION)),
            sample(false, Some(error_kind::PERMISSION)),
            sample(false, Some(error_kind::PERMISSION)),
        ]);
        // Permission is not in {dom_not_found, timeout} → don't auto-escalate.
        assert_eq!(should_trigger(&s, &lineage(None)), EvolutionAction::NoOp);
    }

    #[test]
    fn failure_then_success_breaks_streak() {
        let s = stats(vec![
            sample(true, None),
            sample(false, Some(error_kind::DOM_NOT_FOUND)),
            sample(false, Some(error_kind::DOM_NOT_FOUND)),
        ]);
        assert_eq!(should_trigger(&s, &lineage(None)), EvolutionAction::NoOp);
    }

    #[test]
    fn low_adoption_triggers_rewrite() {
        let s = stats(vec![sample(true, None)]);
        assert_eq!(
            should_trigger(&s, &lineage(Some(0.10))),
            EvolutionAction::RewritePrompt
        );
    }

    #[test]
    fn high_adoption_is_noop() {
        let s = stats(vec![sample(true, None)]);
        assert_eq!(
            should_trigger(&s, &lineage(Some(0.95))),
            EvolutionAction::NoOp
        );
    }

    #[test]
    fn deep_wins_over_rewrite_when_both_apply() {
        let s = stats(vec![
            sample(false, Some(error_kind::DOM_NOT_FOUND)),
            sample(false, Some(error_kind::DOM_NOT_FOUND)),
            sample(false, Some(error_kind::DOM_NOT_FOUND)),
        ]);
        // Even with low adoption, deep-reparse is the more specific
        // signal — we escalate on the immediate pain first.
        assert_eq!(
            should_trigger(&s, &lineage(Some(0.10))),
            EvolutionAction::DeepReanalyze
        );
    }
}
