// Copyright (c) 2026 AIMarketing
//
// Generic reversible-effect ledger.
//
// deepseek-harness ("everything is a plugin") taught one transferable
// idea worth borrowing: *reversible effects*. Any mutation the agent
// makes — a skill upgrade, an automation adopt, a memory write, a config
// change — registers a snapshot of the previous state plus a watch window.
// If the mutation later proves bad it can be undone cleanly, and a cooldown
// prevents flap-loops.
//
// This module is the single source of truth for that decision logic. The
// two legacy rollback mechanisms (automation::rollback::RollbackBook and
// autoskill's upgrade rollback) now delegate here, so there is one
// watch/cooldown/trip engine instead of two hand-rolled copies.

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

pub const WATCH_WINDOW_SECS: i64 = 5 * 60;
pub const COOLDOWN_SECS: i64 = 30 * 60;
pub const FAILURE_THRESHOLD: f32 = 0.30;
pub const MIN_SAMPLE: u32 = 3;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EffectKind {
    SkillUpgrade,
    AutomationAdopt,
    MemoryWrite,
    ConfigChange,
}

impl EffectKind {
    pub fn as_str(&self) -> &'static str {
        match self {
            EffectKind::SkillUpgrade => "skill_upgrade",
            EffectKind::AutomationAdopt => "automation_adopt",
            EffectKind::MemoryWrite => "memory_write",
            EffectKind::ConfigChange => "config_change",
        }
    }
}

#[derive(Debug, Clone)]
pub struct Effect {
    pub id: String,
    pub kind: EffectKind,
    /// Serialized pre-mutation state. The caller restores from this on
    /// undo. "none" means there was no prior state (e.g. a brand-new key).
    pub previous_state: String,
    pub applied_at: i64,
    pub expires_at: i64,
    pub total_runs: u32,
    pub failed_runs: u32,
    pub undone: bool,
    pub undo_reason: Option<String>,
}

impl Effect {
    pub fn is_active(&self, now: i64) -> bool {
        !self.undone && now < self.expires_at
    }

    pub fn failure_rate(&self) -> f32 {
        if self.total_runs == 0 {
            0.0
        } else {
            self.failed_runs as f32 / self.total_runs as f32
        }
    }

    /// Record one observation. Returns true if the failure budget tripped
    /// and the caller should now undo.
    pub fn record_run(&mut self, success: bool, now: i64) -> bool {
        if !self.is_active(now) {
            return false;
        }
        self.total_runs += 1;
        if !success {
            self.failed_runs += 1;
        }
        self.total_runs >= MIN_SAMPLE && self.failure_rate() > FAILURE_THRESHOLD
    }
}

#[derive(Debug, Default)]
pub struct EffectLedger {
    effects: HashMap<String, Effect>,
    cooldowns: HashMap<String, i64>,
}

fn now_secs() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

impl EffectLedger {
    pub fn new() -> Self {
        Self::default()
    }

    /// Register a freshly applied effect. Errors if `id` is still inside its
    /// cooldown window. `previous_state` is the serialized pre-mutation state.
    pub fn register(
        &mut self,
        id: &str,
        kind: EffectKind,
        previous_state: &str,
        now: i64,
    ) -> Result<(), String> {
        if let Some(last) = self.cooldowns.get(id) {
            if now - *last < COOLDOWN_SECS {
                return Err(format!(
                    "effect '{}' ({}) is in cooldown ({}s remaining)",
                    id,
                    kind.as_str(),
                    COOLDOWN_SECS - (now - *last)
                ));
            }
        }
        self.effects.insert(
            id.to_string(),
            Effect {
                id: id.to_string(),
                kind,
                previous_state: previous_state.to_string(),
                applied_at: now,
                expires_at: now + WATCH_WINDOW_SECS,
                total_runs: 0,
                failed_runs: 0,
                undone: false,
                undo_reason: None,
            },
        );
        Ok(())
    }

    /// Record an observation against an active effect. Returns
    /// `Some(previous_state)` if the failure budget tripped and the caller
    /// must undo; otherwise `None`.
    pub fn record_run(&mut self, id: &str, success: bool) -> Option<String> {
        let now = now_secs();
        let trip = if let Some(e) = self.effects.get_mut(id) {
            e.record_run(success, now)
        } else {
            false
        };
        if !trip {
            return None;
        }
        let prev = self.effects.get(id).map(|e| e.previous_state.clone());
        if let Some(e) = self.effects.get_mut(id) {
            e.undone = true;
            e.undo_reason = Some(format!(
                "failure rate {:.0}% over {} runs exceeded threshold {:.0}%",
                e.failure_rate() * 100.0,
                e.total_runs,
                FAILURE_THRESHOLD * 100.0
            ));
        }
        if prev.is_some() {
            self.cooldowns.insert(id.to_string(), now);
        }
        prev
    }

    /// Manually undo (e.g. user clicked "roll back"). Returns the previous
    /// state to restore, or None if no active effect exists for `id`.
    pub fn force_undo(&mut self, id: &str, reason: &str) -> Option<String> {
        let now = now_secs();
        let prev = self.effects.get(id).map(|e| e.previous_state.clone());
        if let Some(e) = self.effects.get_mut(id) {
            e.undone = true;
            e.undo_reason = Some(reason.to_string());
        }
        if prev.is_some() {
            self.cooldowns.insert(id.to_string(), now);
        }
        prev
    }

    pub fn is_active(&self, id: &str, now: i64) -> bool {
        self.effects.get(id).map(|e| e.is_active(now)).unwrap_or(false)
    }

    pub fn get(&self, id: &str) -> Option<&Effect> {
        self.effects.get(id)
    }

    pub fn in_cooldown(&self, id: &str, now: i64) -> bool {
        self.cooldowns
            .get(id)
            .map(|last| now - *last < COOLDOWN_SECS)
            .unwrap_or(false)
    }

    /// Drop expired effects and cooldown entries so the maps stay bounded.
    pub fn gc_expired(&mut self, now: i64) {
        self.effects
            .retain(|_, e| e.expires_at > now || e.undone);
        self.cooldowns.retain(|_, last| now - *last < COOLDOWN_SECS);
    }
}

// --- Global ledger -------------------------------------------------------
// One process-wide ledger shared by every subsystem (automation adopts,
// autoskill upgrades, memory writes, config changes). Using a global keeps
// the refactor additive: call sites just call these free functions instead
// of holding their own bookkeeping struct.

static LEDGER: OnceLock<Mutex<EffectLedger>> = OnceLock::new();

fn ledger() -> &'static Mutex<EffectLedger> {
    LEDGER.get_or_init(|| Mutex::new(EffectLedger::new()))
}

pub fn register_effect(
    id: &str,
    kind: EffectKind,
    previous_state: &str,
    now: i64,
) -> Result<(), String> {
    ledger().lock().unwrap().register(id, kind, previous_state, now)
}

pub fn record_effect_run(id: &str, success: bool) -> Option<String> {
    ledger().lock().unwrap().record_run(id, success)
}

pub fn force_undo_effect(id: &str, reason: &str) -> Option<String> {
    ledger().lock().unwrap().force_undo(id, reason)
}

pub fn gc_effects(now: i64) {
    ledger().lock().unwrap().gc_expired(now)
}

pub fn effect_in_cooldown(id: &str, now: i64) -> bool {
    ledger().lock().unwrap().in_cooldown(id, now)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn eff(id: &str, prev: &str, now: i64) -> Effect {
        Effect {
            id: id.to_string(),
            kind: EffectKind::AutomationAdopt,
            previous_state: prev.to_string(),
            applied_at: now,
            expires_at: now + WATCH_WINDOW_SECS,
            total_runs: 0,
            failed_runs: 0,
            undone: false,
            undo_reason: None,
        }
    }

    #[test]
    fn trips_after_threshold() {
        let mut e = eff("s", "1", 0);
        // 2 fails + 1 success = 66% failure rate, above 30% and >= 3 runs.
        assert!(!e.record_run(false, 10));
        assert!(!e.record_run(false, 11));
        assert!(e.record_run(true, 12));
    }

    #[test]
    fn does_not_trip_on_small_sample() {
        let mut e = eff("s", "1", 0);
        assert!(!e.record_run(false, 10));
    }

    #[test]
    fn cooldown_blocks_reregister() {
        let mut l = EffectLedger::new();
        let now = 1_000_000;
        l.register("s", EffectKind::AutomationAdopt, "1", now).unwrap();
        let prev = l.force_undo("s", "user clicked").unwrap();
        assert_eq!(prev, "1");
        // Re-registering inside the 30-min cooldown must fail.
        let err = l
            .register("s", EffectKind::AutomationAdopt, "2", now + 60)
            .expect_err("should be in cooldown");
        assert!(err.contains("cooldown"));
    }

    #[test]
    fn global_ledger_trips_and_returns_previous() {
        register_effect("g", EffectKind::SkillUpgrade, "v2", 0).unwrap();
        assert!(record_effect_run("g", false).is_none());
        assert!(record_effect_run("g", false).is_none());
        let prev = record_effect_run("g", true);
        assert_eq!(prev, Some("v2".to_string()));
        assert!(effect_in_cooldown("g", 100));
    }
}
