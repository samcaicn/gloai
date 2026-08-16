// Copyright (c) 2026 tupAI
//
// Automatic rollback after a high-confidence adopt.
//
// When `SkillRegistry::adopt` decides to swap a running version, we keep a
// *snapshot* of the previous version (stored by the registry) and watch the
// new version for a failure burst. If the failure rate inside the watch
// window exceeds the threshold, the adopt is rolled back atomically.
//
// The watch/cooldown/trip *decision* logic now lives in
// `crate::effects::EffectLedger` — a single process-wide reversible-effect
// ledger shared with autoskill upgrades. This module is a thin, skill-aware
// facade over that ledger (it only translates version numbers to/from the
// ledger's `previous_state` string). One engine, no duplicated bookkeeping.

use crate::effects::{register_effect, record_effect_run, force_undo_effect, gc_effects, effect_in_cooldown, EffectKind};

/// 5 minutes.
pub const WATCH_WINDOW_SECS: i64 = 5 * 60;

/// 30 minutes — cooldown after a rollback.
/// Reset on success.
pub const COOLDOWN_SECS: i64 = 30 * 60;

/// 30% — failure rate threshold inside the watch window that
/// triggers an automatic rollback.
pub const FAILURE_THRESHOLD: f32 = 0.30;

/// Per-skill watch record. Holds the previous version so callers can show
/// "v3 in trouble" labels and restore on rollback.
#[allow(dead_code)] // used by unit tests; production routes through the ledger
#[derive(Debug, Clone)]
pub struct RollbackGuard {
    pub skill_id: String,
    pub previous_version: u32,
    pub new_version: u32,
    pub adopted_at: i64,
    pub expires_at: i64,
    pub total_runs: u32,
    pub failed_runs: u32,
    pub rollback_reason: Option<String>,
}

impl RollbackGuard {
    pub fn is_active(&self, now: i64) -> bool {
        self.rollback_reason.is_none() && now < self.expires_at
    }
    pub fn has_rolled_back(&self) -> bool {
        self.rollback_reason.is_some()
    }
    pub fn failure_rate(&self) -> f32 {
        if self.total_runs == 0 {
            0.0
        } else {
            self.failed_runs as f32 / self.total_runs as f32
        }
    }
    pub fn record_run(&mut self, success: bool, now: i64) -> bool {
        if !self.is_active(now) {
            return false;
        }
        self.total_runs += 1;
        if !success {
            self.failed_runs += 1;
        }
        self.total_runs >= 3 && self.failure_rate() > FAILURE_THRESHOLD
    }
}

/// Facade over the global `EffectLedger`. Holds no state of its own; all
/// bookkeeping lives in `crate::effects`.
#[derive(Debug, Default)]
pub struct RollbackBook;

impl RollbackBook {
    /// Begin watching a freshly adopted skill. Returns `Err(reason)` if the
    /// skill is still in its cooldown window after a previous rollback.
    pub fn start_watch(
        &mut self,
        skill_id: &str,
        previous_version: u32,
        _new_version: u32,
        now: i64,
    ) -> Result<(), String> {
        register_effect(
            skill_id,
            EffectKind::AutomationAdopt,
            &previous_version.to_string(),
            now,
        )
    }

    /// Record a run against a guarded skill. Returns `Some(previous_version)`
    /// if the run tripped the failure budget and the caller should roll back.
    pub fn record_run(&mut self, skill_id: &str, success: bool) -> Option<u32> {
        record_effect_run(skill_id, success).map(|s| s.parse().unwrap_or(0))
    }

    /// Manually trigger a rollback (e.g. the user clicked "Roll back").
    /// Returns the previous version that should be restored.
    pub fn force_rollback(&mut self, skill_id: &str, reason: &str) -> Option<u32> {
        force_undo_effect(skill_id, reason).map(|s| s.parse().unwrap_or(0))
    }

    /// Drop guards whose watch window has expired. Call periodically.
    pub fn gc_expired(&mut self, now: i64) {
        gc_effects(now);
    }

    /// `true` if a fresh promotion would be blocked by the cooldown timer.
    pub fn in_cooldown(&self, skill_id: &str, now: i64) -> bool {
        effect_in_cooldown(skill_id, now)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn guard(prev: u32, new: u32, now: i64) -> RollbackGuard {
        RollbackGuard {
            skill_id: "s".to_string(),
            previous_version: prev,
            new_version: new,
            adopted_at: now,
            expires_at: now + WATCH_WINDOW_SECS,
            total_runs: 0,
            failed_runs: 0,
            rollback_reason: None,
        }
    }

    #[test]
    fn trips_after_threshold() {
        let mut g = guard(1, 2, 0);
        assert!(!g.record_run(false, 10));
        assert!(!g.record_run(false, 11));
        assert!(g.record_run(true, 12));
    }

    #[test]
    fn does_not_trip_on_small_sample() {
        let mut g = guard(1, 2, 0);
        assert!(!g.record_run(false, 10));
    }

    #[test]
    fn cooldown_blocks_replay() {
        let mut book = RollbackBook;
        let now = 1_000_000;
        book.start_watch("s", 1, 2, now).unwrap();
        let prev = book.force_rollback("s", "user clicked").unwrap();
        assert_eq!(prev, 1u32);
        let err = book
            .start_watch("s", 2, 3, now + 60)
            .expect_err("should be in cooldown");
        assert!(err.contains("cooldown"));
    }
}
