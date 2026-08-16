// Copyright (c) 2026 tupAI
//
// Persistent counter + flag backing the "自进化" UI panel.
//
// v5.7 (initial): cumulative counters + auto-evolve flag.
// v5.8: per-skill stats (the v5.7 cumulative "成功率" was a
//   product metric that hid which skill was broken — a single
//   failing skill could pull the global rate down without the
//   user knowing *which* one). v5.8 keeps the global counters
//   for the existing Stats page but adds a `SkillStat` per
//   skill so the panel can render a real per-skill table.
//
// v5.8 also adds two protective mechanisms the v5.7 scheduler
// was missing:
//   * **Time-based dedup** — a skill that ran successfully in
//     the last `min_interval_ms` is skipped. Without this,
//     toggling auto-evolve on and then immediately clicking
//     "立即运行" would re-execute the same skills back-to-back
//     and hammer the LLM.
//   * **Circuit breaker** — after `CIRCUIT_BREAKER_THRESHOLD`
//     consecutive failures, the skill is marked
//     `CircuitBroken` and skipped for `CIRCUIT_BREAKER_COOLDOWN_MS`.
//     A single success resets the consecutive-failure count,
//     so a flaky skill self-heals once it starts working again.
//
// Scope: process-local. Restart resets the counters. We
// deliberately do NOT persist to disk yet — the front-end
// already keeps the per-execution log in localStorage, so
// the lifetime we care about is "one session of the desktop
// app", which is exactly what `OnceLock<Mutex<>>` gives us.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

use crate::hermes::persistence::{self, HermesDb};

// =============================================================
// Tunable constants. Bumped to module scope (vs hard-coded
// literals) so the front-end can document them in a tooltip
// without a code dive.
// =============================================================

/// A successful run "ages out" of the dedup window after this
/// long. Default 2 min: short enough that auto-evolve's 5-min
/// tick can re-run a successful skill, long enough that an
/// auto fire + an immediate "立即运行" click won't re-execute
/// the same skill in <2 min.
pub const DEFAULT_MIN_INTERVAL_MS: i64 = 2 * 60 * 1000;

/// Auto-open the circuit breaker after this many consecutive
/// failures. 3 is the industry-standard default for "is this
/// thing actually broken" — one failure is a flake, two is
/// suspicious, three is a pattern.
pub const CIRCUIT_BREAKER_THRESHOLD: u32 = 3;

/// Once open, the circuit stays open for this long. 30 min
/// matches the spec ("broken skills back off for half an hour")
/// and is short enough that a transient outage (e.g. the LLM
/// provider is down for 10 min) self-heals within the same
/// user session.
pub const CIRCUIT_BREAKER_COOLDOWN_MS: i64 = 30 * 60 * 1000;

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

// =============================================================
// Public types — the wire contract.
// =============================================================

/// One skill's lifecycle status. Mirrored in
/// `src/pages/Evolution.jsx` so the panel can render a status
/// badge.
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum SkillStatus {
    /// Never run yet, or ran successfully long enough ago that
    /// the circuit cooldown has elapsed.
    Idle,
    /// Ran successfully within `DEFAULT_MIN_INTERVAL_MS` —
    /// the dedup window is open.
    Active,
    /// Open circuit breaker. Skipped by `should_skip_skill` for
    /// the next `CIRCUIT_BREAKER_COOLDOWN_MS`.
    CircuitBroken,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SkillStat {
    pub skill_id: String,
    pub name: String,
    pub runs: u64,
    pub sent: u64,
    pub failed: u64,
    /// 0..=1, computed on read. `None` if `runs == 0`.
    pub success_rate: Option<f32>,
    pub consecutive_failures: u32,
    pub last_run_ms: i64,
    pub last_success_ms: i64,
    pub last_failure_ms: i64,
    pub status: SkillStatus,
    /// UNIX ms at which `CircuitBroken` clears and the skill
    /// becomes eligible for retry. 0 when status != CircuitBroken.
    pub circuit_open_until_ms: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
#[derive(Default)]
pub struct EvolutionState {
    pub total_scans: u64,
    pub total_sent: u64,
    pub total_failed: u64,
    pub auto_evolve: bool,
    pub last_updated_ms: i64,
    /// Per-skill stats. Empty if no skills have ever run.
    /// Front-end renders this as the main per-skill table.
    pub skills: Vec<SkillStat>,
}


// =============================================================
// Storage.
// =============================================================

struct Inner {
    cumulative: EvolutionState,
    /// `skill_id` → index into `cumulative.skills`. Lets us
    /// upsert in O(1) without scanning the Vec.
    skill_index: HashMap<String, usize>,
}

static STATE: OnceLock<Mutex<Inner>> = OnceLock::new();

fn state() -> &'static Mutex<Inner> {
    STATE.get_or_init(|| {
        Mutex::new(Inner {
            cumulative: EvolutionState::default(),
            skill_index: HashMap::new(),
        })
    })
}

fn lock() -> std::sync::MutexGuard<'static, Inner> {
    // The mutex is `OnceLock`-owned, so poisoning is a sign of
    // a panic mid-update — recoverable by taking the inner
    // value and continuing. The whole point of the front-end
    // calling `report_skill_execution_result` is that a single
    // broken skill must not poison the whole stats store.
    match state().lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    }
}

// =============================================================
// Persistence handle.
// =============================================================

/// Optional sqlite handle. Set once by `init_persistence` from
/// `HermesAppState::with_persistence`. When `Some`, every mutator
/// (`record_run` / `set_auto_evolve` / `clear_stats`) syncs to the
/// `hermes_evolution_stats` / `hermes_evolution_meta` tables.
static DB: OnceLock<Option<Arc<HermesDb>>> = OnceLock::new();

/// Install the sqlite handle and hydrate the in-memory state from
/// the persisted rows. Called once during app startup, before any
/// `record_run` / `should_skip` / `snapshot` call.
pub fn init_persistence(db: Arc<HermesDb>) {
    // Load cumulative counters + auto_evolve flag.
    let total_scans = persistence::get_meta(&db, "total_scans")
        .ok().flatten()
        .and_then(|v| v.parse::<u64>().ok())
        .unwrap_or(0);
    let total_sent = persistence::get_meta(&db, "total_sent")
        .ok().flatten()
        .and_then(|v| v.parse::<u64>().ok())
        .unwrap_or(0);
    let total_failed = persistence::get_meta(&db, "total_failed")
        .ok().flatten()
        .and_then(|v| v.parse::<u64>().ok())
        .unwrap_or(0);
    let auto_evolve = persistence::get_meta(&db, "auto_evolve")
        .ok().flatten()
        .map(|v| v == "1")
        .unwrap_or(false);
    let last_updated_ms = persistence::get_meta(&db, "last_updated_ms")
        .ok().flatten()
        .and_then(|v| v.parse::<i64>().ok())
        .unwrap_or(0);

    // Load per-skill stats and rebuild the index.
    let skills = persistence::list_skill_stats(&db).unwrap_or_default();
    let mut skill_index: HashMap<String, usize> = HashMap::new();
    for (i, s) in skills.iter().enumerate() {
        skill_index.insert(s.skill_id.clone(), i);
    }

    // Recompute success_rate (the DB row doesn't store it).
    let mut skills = skills;
    for s in &mut skills {
        s.success_rate = if s.runs == 0 {
            None
        } else {
            Some(s.sent as f32 / s.runs as f32)
        };
    }

    {
        let mut g = lock();
        g.cumulative = EvolutionState {
            total_scans,
            total_sent,
            total_failed,
            auto_evolve,
            last_updated_ms,
            skills,
        };
        g.skill_index = skill_index;
    }

    // Install the handle last so mutators only fire after hydration.
    let _ = DB.set(Some(db));
    log::info!("[evolution_stats] persistence initialised, loaded {} skill stats", {
        let g = lock();
        g.cumulative.skills.len()
    });
}

/// Borrow the installed db handle, if any. Returns `None` when
/// persistence was never initialised (unit tests, headless library use).
fn db() -> Option<&'static Arc<HermesDb>> {
    DB.get().and_then(|opt| opt.as_ref())
}

/// Best-effort persist of a single `SkillStat` row. Failures are
/// logged but never surfaced — a missed sqlite write doesn't roll
/// back the in-memory update, and the next mutation will retry.
fn persist_skill(stat: &SkillStat) {
    if let Some(db) = db() {
        if let Err(e) = persistence::upsert_skill_stat(db, stat) {
            log::warn!("[evolution_stats] sqlite upsert_skill_stat failed: {}", e);
        }
    }
}

/// Best-effort persist of the cumulative counters + auto_evolve flag.
fn persist_meta(state: &EvolutionState) {
    if let Some(db) = db() {
        if let Err(e) = persistence::save_evolution_state(db, state) {
            log::warn!("[evolution_stats] sqlite save_evolution_state failed: {}", e);
        }
    }
}

// =============================================================
// Mutators.
// =============================================================

/// Record one skill execution. `success=true` increments
/// `sent`; `false` increments `failed`. Either way, the
/// per-skill `runs` / `last_run_ms` advance and the global
/// `total_scans` / `last_updated_ms` follow.
///
/// The per-skill consecutive-failure counter resets on a
/// success, which means a previously-broken skill that starts
/// working again self-heals the moment it succeeds once.
pub fn record_run(skill_id: &str, skill_name: &str, success: bool) {
    let now = now_ms();
    let mut g = lock();

    // Global counters.
    g.cumulative.total_scans = g.cumulative.total_scans.saturating_add(1);
    if success {
        g.cumulative.total_sent = g.cumulative.total_sent.saturating_add(1);
    } else {
        g.cumulative.total_failed = g.cumulative.total_failed.saturating_add(1);
    }
    g.cumulative.last_updated_ms = now;

    // Per-skill upsert.
    let entry = g
        .skill_index
        .get(skill_id)
        .copied()
        .and_then(|i| g.cumulative.skills.get_mut(i));
    let stat = match entry {
        Some(s) => s,
        None => {
            // First time we see this skill — append + index.
            let stat = SkillStat {
                skill_id: skill_id.to_string(),
                name: skill_name.to_string(),
                runs: 0,
                sent: 0,
                failed: 0,
                success_rate: None,
                consecutive_failures: 0,
                last_run_ms: 0,
                last_success_ms: 0,
                last_failure_ms: 0,
                status: SkillStatus::Idle,
                circuit_open_until_ms: 0,
            };
            let idx = g.cumulative.skills.len();
            g.cumulative.skills.push(stat);
            g.skill_index.insert(skill_id.to_string(), idx);
            g.cumulative.skills.last_mut().expect("just pushed")
        }
    };
    stat.runs = stat.runs.saturating_add(1);
    stat.last_run_ms = now;
    if success {
        stat.sent = stat.sent.saturating_add(1);
        stat.consecutive_failures = 0;
        stat.last_success_ms = now;
        stat.status = SkillStatus::Active;
        stat.circuit_open_until_ms = 0;
    } else {
        stat.failed = stat.failed.saturating_add(1);
        stat.consecutive_failures = stat.consecutive_failures.saturating_add(1);
        stat.last_failure_ms = now;
        // Trip the breaker on threshold.
        if stat.consecutive_failures >= CIRCUIT_BREAKER_THRESHOLD {
            stat.status = SkillStatus::CircuitBroken;
            stat.circuit_open_until_ms = now + CIRCUIT_BREAKER_COOLDOWN_MS;
        }
    }
    stat.success_rate = if stat.runs == 0 {
        None
    } else {
        Some(stat.sent as f32 / stat.runs as f32)
    };

    // Sync to sqlite (best-effort). Done after the in-memory update
    // so a failed write never rolls back the hot path.
    persist_skill(stat);
    persist_meta(&g.cumulative);
}

/// Decide whether a skill should be skipped this pass. The
/// two reasons for skipping are encoded separately so the
/// front-end can surface them in the log:
///   * dedup  — a successful run was registered < min ago
///   * circuit — the breaker is open and the cooldown hasn't
///     elapsed yet
pub fn should_skip(
    skill_id: &str,
    min_interval_ms: i64,
) -> SkipReason {
    let now = now_ms();
    let mut g = lock();
    let idx = match g.skill_index.get(skill_id).copied() {
        Some(i) => i,
        None => return SkipReason::Run, // unknown skill — go ahead
    };
    let stat = match g.cumulative.skills.get_mut(idx) {
        Some(s) => s,
        None => return SkipReason::Run,
    };

    // Recompute status from the clock. We don't pre-emptively
    // flip `CircuitBroken` → `Idle` on every read (that would
    // cost a write per panel mount); we just check whether the
    // cooldown has expired when a skip is being considered.
    if stat.status == SkillStatus::CircuitBroken {
        if now >= stat.circuit_open_until_ms {
            stat.status = SkillStatus::Idle;
            stat.circuit_open_until_ms = 0;
        } else {
            return SkipReason::CircuitOpen {
                until_ms: stat.circuit_open_until_ms,
            };
        }
    }
    if stat.status == SkillStatus::Active
        && stat.last_success_ms > 0
        && now - stat.last_success_ms < min_interval_ms
    {
        return SkipReason::Dedup {
            last_success_ms: stat.last_success_ms,
        };
    }
    SkipReason::Run
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum SkipReason {
    /// The skill is eligible. Front-end should run it.
    Run,
    /// A successful run was registered < min_interval_ms ago.
    Dedup { last_success_ms: i64 },
    /// Circuit breaker is open. `until_ms` is the wall clock
    /// at which the skill becomes eligible again.
    CircuitOpen { until_ms: i64 },
}

// =============================================================
// Auto-evolve flag.
// =============================================================

/// Flip the `auto_evolve` flag. Idempotent — see v5.7.
pub fn set_auto_evolve(enabled: bool) {
    let mut g = lock();
    g.cumulative.auto_evolve = enabled;
    g.cumulative.last_updated_ms = now_ms();
    log::info!(
        "[evolution_stats] auto_evolve={} (scheduler is {})",
        enabled,
        if enabled { "active" } else { "paused" }
    );
    // Sync cumulative counters + flag to sqlite.
    persist_meta(&g.cumulative);
}

pub fn is_auto_evolve() -> bool {
    match state().lock() {
        Ok(g) => g.cumulative.auto_evolve,
        Err(poisoned) => poisoned.into_inner().cumulative.auto_evolve,
    }
}

// =============================================================
// Read-only accessors.
// =============================================================

/// Cheap clone of the full state. The per-skill Vec is
/// re-computed (status re-derived from `now`) on every call
/// so the front-end always sees an up-to-date `Active` /
/// `CircuitBroken` reading without a write on every panel
/// mount.
pub fn snapshot() -> EvolutionState {
    let g = lock();
    let now = now_ms();
    let mut out = g.cumulative.clone();
    for s in &mut out.skills {
        if s.status == SkillStatus::CircuitBroken && now >= s.circuit_open_until_ms
        {
            s.status = SkillStatus::Idle;
            s.circuit_open_until_ms = 0;
        }
    }
    out
}

/// Reset everything — both cumulative counters and per-skill
/// stats. The auto-evolve flag is *not* reset (the user
/// explicitly asked to clear stats, not stop the scheduler).
/// Exposed for the "重置统计" button.
pub fn clear_stats() {
    let mut g = lock();
    let keep_auto_evolve = g.cumulative.auto_evolve;
    let now = now_ms();
    g.cumulative = EvolutionState {
        auto_evolve: keep_auto_evolve,
        last_updated_ms: now,
        ..EvolutionState::default()
    };
    g.skill_index.clear();
    log::info!("[evolution_stats] stats cleared (auto_evolve preserved)");
    // Sync the cleared state to sqlite.
    if let Some(db) = db() {
        if let Err(e) = persistence::clear_skill_stats(db) {
            log::warn!("[evolution_stats] sqlite clear_skill_stats failed: {}", e);
        }
    }
    persist_meta(&g.cumulative);
}

#[cfg(test)]
mod tests {
    use super::*;

    fn skill_id(seed: &str) -> String {
        // Prefix the seed with a per-test unique token so tests
        // don't share state with each other (the OnceLock is
        // process-wide; we can't reset it).
        format!("test-{}-{}", seed, now_ms())
    }

    #[test]
    fn record_run_tracks_cumulative_and_per_skill() {
        let id = skill_id("basic");
        record_run(&id, "basic-skill", true);
        record_run(&id, "basic-skill", true);
        record_run(&id, "basic-skill", false);
        let s = snapshot();
        let mine = s
            .skills
            .iter()
            .find(|s| s.skill_id == id)
            .expect("recorded");
        assert_eq!(mine.runs, 3);
        assert_eq!(mine.sent, 2);
        assert_eq!(mine.failed, 1);
        assert_eq!(mine.consecutive_failures, 1);
        assert_eq!(mine.status, SkillStatus::Active);
    }

    #[test]
    fn circuit_breaker_opens_on_three_consecutive_failures() {
        let id = skill_id("breaker");
        record_run(&id, "breaker-skill", false);
        record_run(&id, "breaker-skill", false);
        assert!(matches!(should_skip(&id, 0), SkipReason::Run));
        record_run(&id, "breaker-skill", false);
        match should_skip(&id, 0) {
            SkipReason::CircuitOpen { until_ms } => assert!(until_ms > now_ms()),
            other => panic!("expected circuit open, got {:?}", other),
        }
    }

    #[test]
    fn circuit_resets_on_success() {
        let id = skill_id("recover");
        record_run(&id, "r", false);
        record_run(&id, "r", false);
        record_run(&id, "r", false);
        // We're in circuit-open now. A success after the
        // cooldown clears it.
        record_run(&id, "r", true);
        let s = snapshot();
        let mine = s.skills.iter().find(|s| s.skill_id == id).unwrap();
        assert_eq!(mine.consecutive_failures, 0);
        assert_eq!(mine.status, SkillStatus::Active);
    }

    #[test]
    fn dedup_skips_a_recent_successful_run() {
        let id = skill_id("dedup");
        record_run(&id, "d", true);
        // With min_interval_ms=999_999_999, dedup is *always*
        // triggered for a skill that just succeeded.
        match should_skip(&id, 999_999_999) {
            SkipReason::Dedup { .. } => {}
            other => panic!("expected dedup, got {:?}", other),
        }
    }

    #[test]
    fn unknown_skill_is_always_run() {
        match should_skip("definitely-not-in-the-store", 0) {
            SkipReason::Run => {}
            other => panic!("expected Run, got {:?}", other),
        }
    }

    #[test]
    fn clear_stats_preserves_auto_evolve() {
        set_auto_evolve(true);
        clear_stats();
        assert!(is_auto_evolve());
        set_auto_evolve(false);
    }
}
