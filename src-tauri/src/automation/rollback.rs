// Copyright (c) 2026 tupAI
//
// Automatic rollback after a high-confidence adopt.
//
// When `SkillRegistry::adopt` decides to swap a running version, we
// keep a *snapshot* of the previous version on disk (in memory
// here, in a real deploy that would be `skill_versions.state =
// 'retired'`). For the next 5 minutes we treat any
// post-swap run with > 30% failure rate as evidence the new
// version is broken and roll back atomically.
//
// A 30-minute cooldown then prevents a flap-loop: if a skill is
// rolled back, it can't be promoted again (or re-rolled-back)
// within the cooldown window.

use std::collections::HashMap;
use std::time::{SystemTime, UNIX_EPOCH};

/// 5 minutes.
pub const WATCH_WINDOW_SECS: i64 = 5 * 60;

/// 30 minutes — cooldown after a rollback.
/// Reset on success.
pub const COOLDOWN_SECS: i64 = 30 * 60;

/// 30% — failure rate threshold inside the watch window that
/// triggers an automatic rollback.
pub const FAILURE_THRESHOLD: f32 = 0.30;

/// One in-flight `RollbackGuard` per skill id. We keep the
/// previous version number so the guard can revert atomically
/// when the failure budget is blown.
#[derive(Debug, Clone)]
pub struct RollbackGuard {
    /// Skill id this guard is watching.
    pub skill_id: String,
    /// The version we were running *before* the adopt. This is
    /// the version we restore to on rollback.
    pub previous_version: u32,
    /// The version we just promoted to. We track it so callers
    /// can show "v3 in trouble" labels.
    pub new_version: u32,
    /// Unix-seconds when the watch window started.
    pub adopted_at: i64,
    /// Unix-seconds when the guard automatically expires.
    pub expires_at: i64,
    /// Total runs observed inside the watch window.
    pub total_runs: u32,
    /// Failed runs observed inside the watch window.
    pub failed_runs: u32,
    /// Why we rolled back, if we did. `None` while the guard is
    /// still in the watch window.
    pub rollback_reason: Option<String>,
}

impl RollbackGuard {
    /// `true` if the watch window is still open (no rollback yet
    /// and the deadline hasn't passed). Callers pass the
    /// current timestamp explicitly so unit tests can pin
    /// time without monkey-patching `SystemTime`.
    pub fn is_active(&self, now: i64) -> bool {
        self.rollback_reason.is_none() && now < self.expires_at
    }

    /// `true` if the guard rolled back and is now in cooldown.
    pub fn has_rolled_back(&self) -> bool {
        self.rollback_reason.is_some()
    }

    /// Current failure rate inside the watch window. Returns
    /// `0.0` until the first run lands so we never trip on
    /// an empty sample.
    pub fn failure_rate(&self) -> f32 {
        if self.total_runs == 0 {
            0.0
        } else {
            self.failed_runs as f32 / self.total_runs as f32
        }
    }

    /// Record a single run inside the watch window. Returns
    /// `true` if the recorded run pushed the failure rate over
    /// the threshold and a rollback should fire.
    ///
    /// `now` is passed in by the caller so `RollbackBook` can
    /// re-use the same timestamp it just sampled for the
    /// `expires_at` math (and so tests don't have to wait for
    /// wall-clock time to elapse).
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

/// Snapshot of the registry's rollback state. Held under
/// `SkillRegistry::rollback_guards` and protected by the same
/// mutex. We keep this in its own struct so `SkillRegistry` can
/// own the rest of the state (running versions, inbox) without
/// mixing concerns.
#[derive(Debug, Default)]
pub struct RollbackBook {
    /// Active guards keyed by skill id. There is at most one
    /// guard per skill id at a time.
    pub guards: HashMap<String, RollbackGuard>,
    /// Last rollback (or reject) timestamp per skill id. Used
    /// to enforce `COOLDOWN_SECS` between promotions on the
    /// same skill id.
    pub cooldowns: HashMap<String, i64>,
}

impl RollbackBook {
    /// Begin watching a freshly adopted skill. Returns
    /// `Err(reason)` if the skill is still in its cooldown
    /// window after a previous rollback.
    pub fn start_watch(
        &mut self,
        skill_id: &str,
        previous_version: u32,
        new_version: u32,
        now: i64,
    ) -> Result<(), String> {
        if let Some(last) = self.cooldowns.get(skill_id) {
            if now - *last < COOLDOWN_SECS {
                return Err(format!(
                    "skill '{}' is in rollback cooldown ({}s remaining)",
                    skill_id,
                    COOLDOWN_SECS - (now - *last)
                ));
            }
        }
        self.guards.insert(
            skill_id.to_string(),
            RollbackGuard {
                skill_id: skill_id.to_string(),
                previous_version,
                new_version,
                adopted_at: now,
                expires_at: now + WATCH_WINDOW_SECS,
                total_runs: 0,
                failed_runs: 0,
                rollback_reason: None,
            },
        );
        Ok(())
    }

    /// Record a run against a guarded skill. Returns
    /// `Some(previous_version)` if the run tripped the failure
    /// budget and the registry should now roll back.
    pub fn record_run(&mut self, skill_id: &str, success: bool) -> Option<u32> {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0);
        let trip = if let Some(guard) = self.guards.get_mut(skill_id) {
            guard.record_run(success, now)
        } else {
            false
        };
        if !trip {
            return None;
        }
        // Pull the previous version out, then mark the guard as
        // rolled back and stamp the cooldown so the next adopt
        // attempt has to wait.
        let previous = self.guards.get(skill_id).map(|g| g.previous_version);
        if let Some(guard) = self.guards.get_mut(skill_id) {
            guard.rollback_reason = Some(format!(
                "failure rate {:.0}% over {} runs exceeded threshold {:.0}%",
                guard.failure_rate() * 100.0,
                guard.total_runs,
                FAILURE_THRESHOLD * 100.0,
            ));
        }
        self.cooldowns.insert(skill_id.to_string(), now);
        previous
    }

    /// Manually trigger a rollback (e.g. the user clicked
    /// "Roll back" in the inbox UI). Returns the previous
    /// version that should now be restored, or `None` if no
    /// active guard exists.
    pub fn force_rollback(&mut self, skill_id: &str, reason: &str) -> Option<u32> {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0);
        let previous = self.guards.get(skill_id).map(|g| g.previous_version);
        if let Some(guard) = self.guards.get_mut(skill_id) {
            guard.rollback_reason = Some(reason.to_string());
        }
        if previous.is_some() {
            self.cooldowns.insert(skill_id.to_string(), now);
        }
        previous
    }

    /// Drop guards whose watch window has expired. The caller
    /// (registry) should call this periodically; we expose it
    /// publicly so unit tests can drive time without
    /// monkey-patching `SystemTime`.
    ///
    /// 同时清理过期的 cooldowns 条目: 30 分钟冷却过期后, 条目留在 map
    /// 里只是死数据 (in_cooldown 已经返回 false), 但会无界增长。每次
    /// gc 时顺手清掉, 保持 cooldowns map 和 guards 一样有界。
    pub fn gc_expired(&mut self, now: i64) {
        self.guards
            .retain(|_, guard| guard.expires_at > now || guard.has_rolled_back());
        // 清掉已过期的 cooldown 条目 (now - last >= COOLDOWN_SECS)
        self.cooldowns.retain(|_, last| now - *last < COOLDOWN_SECS);
    }

    /// `true` if a fresh promotion would be blocked by the
    /// cooldown timer.
    pub fn in_cooldown(&self, skill_id: &str, now: i64) -> bool {
        self.cooldowns
            .get(skill_id)
            .map(|last| now - *last < COOLDOWN_SECS)
            .unwrap_or(false)
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
        // 2 fails, 1 success -> 66% failure rate, above 30% and we
        // already have the minimum 3 runs. We pass `now=10` so
        // the guard is still inside the 5-minute watch window.
        assert!(!g.record_run(false, 10));
        assert!(!g.record_run(false, 11));
        assert!(g.record_run(true, 12));
    }

    #[test]
    fn does_not_trip_on_small_sample() {
        let mut g = guard(1, 2, 0);
        // 1 fail only is below the minimum 3-run sample size.
        assert!(!g.record_run(false, 10));
    }

    #[test]
    fn cooldown_blocks_replay() {
        let mut book = RollbackBook::default();
        let now = 1_000_000;
        book.start_watch("s", 1, 2, now).unwrap();
        let prev = book.force_rollback("s", "user clicked").unwrap();
        // `force_rollback` returns the previous `state` integer; the
        // implementation encodes "watching" as 1.
        assert_eq!(prev, 1u32);
        // The next promotion attempt should fail because we are
        // still inside the 30-minute cooldown.
        let err = book
            .start_watch("s", 2, 3, now + 60)
            .expect_err("should be in cooldown");
        assert!(err.contains("cooldown"));
    }
}
