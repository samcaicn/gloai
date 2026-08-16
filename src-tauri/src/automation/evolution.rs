// Copyright (c) 2026 tupAI
//
// EvolutionLoop · periodic scheduler.
//
// The loop ties together three upstream modules that are being
// shipped in parallel:
//   * `skill::memory`           — `SkillDb` (read-only here)
//   * `automation::healing`     — `HealingEngine`
//   * `hermes::transport`      — `HermesTransport` (optional)
//
// The actual storage / network types are owned by the other
// modules, so we depend on them through *trait* abstractions
// (`SkillDbOps` / `HermesTransportOps`). That keeps `evolution.rs`
// compilable as soon as this commit lands — concrete implementations
// can later be wired in `lib.rs` without forcing
// a rewrite of the loop.
//
// The loop is intentionally simple: a `tokio::time::sleep` driven
// coroutine that:
//   1. On startup, fires one evolution pass immediately (so the
//      `EvolutionPanel` has data to render on first launch).
//   2. Afterwards, wakes at the next local-time 02:00 boundary and
//      re-runs the pass.
//   3. A second `trigger_now` entry point lets the user fire the
//      pass from the UI. If a manual trigger is in flight, the
//      daily 02:00 trigger is *skipped* (no double work).
//
// A simple `Arc<AtomicBool>` is used as the shutdown signal —
// `tokio_util::sync::CancellationToken` would be cleaner but the
// crate isn't in our dep tree. We only need a one-shot "is the
// loop still running" bit.
//
// The `trigger_now` / `get_history` methods are bound to Tauri
// commands in `commands/automation.rs` (only the command names are
// wired in lib.rs by the main thread; this file owns the impls).

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use chrono::{DateTime, Local, NaiveDate, NaiveTime, TimeZone, Utc};
use serde::{Deserialize, Serialize};
use tokio::time::sleep;

use super::healing::{FailureContext, HealingEngine};
use super::heuristics::{
    build_daily_trigger, build_manual_trigger, ADOPTION_RATE_FLOOR, EvolutionAction,
    EvolutionReason, EvolutionTrigger, RunStats, RunSample, SkillLineage,
};

// =============================================================
// Trait abstractions — owned here so the loop compiles before
// concrete types land.
// =============================================================

/// A single skill's recent run history + adoption metadata, as
/// collected from the skill memory module. The loop calls
/// `gather_stats(skill_id)` to get this; the in-memory default impl
/// returns an empty result so the loop still works on a fresh install.
pub trait SkillDbOps: Send + Sync {
    /// Snapshot the recent runs + lineage for a single skill.
    fn gather_stats(&self, skill_id: &str) -> (RunStats, SkillLineage);

    /// Cheap, line-counted view of all known skills. The daily
    /// batch iterates over this; the manual trigger iterates over
    /// the same set.
    fn list_skill_ids(&self) -> Vec<String>;

    /// Persist a run back to the store. Used when the loop re-records
    /// a re-parsed version of a skill so the lineage reflects the
    /// new state.
    fn record_event(&self, event: &EvolutionEvent);
}

/// Push-only transport to the Hermes 8642 server. The
/// loop is *best-effort* with this — if the transport is `None`
/// (no wiring yet), or any send errors out, we log and
/// continue. Per hard rule: "评估不能阻塞产出".
pub trait HermesTransportOps: Send + Sync {
    fn post_evolution_event(&self, event: &EvolutionEvent) -> Result<(), String>;
}

// =============================================================
// In-memory defaults — keep the loop runnable on a fresh install.
// =============================================================

/// Naïve, in-process `SkillDb`. Used:
///   1. As the default constructor in `EvolutionLoop::new` so the
///      loop is usable even before `skill::memory` is
///      wired in.
///   2. In tests / `cargo check` so we don't need a real SQLite
///      handle to verify the periodic scheduler compiles.
#[derive(Default)]
pub struct InMemorySkillDb {
    /// skill_id → recent runs (most recent first). The default
    /// is empty; the engine feeds runs through `record_event` (or
    /// the main thread can swap the field for the real SQLite
    /// store).
    runs: Mutex<std::collections::HashMap<String, Vec<RunSample>>>,
    /// skill_id → adoption rate (manually set, for the bootstrap
    /// period).
    adoption: Mutex<std::collections::HashMap<String, f32>>,
}

impl InMemorySkillDb {
    pub fn new() -> Self {
        Self::default()
    }
}

impl SkillDbOps for InMemorySkillDb {
    fn gather_stats(&self, skill_id: &str) -> (RunStats, SkillLineage) {
        let recent = self
            .runs
            .lock()
            .expect("InMemorySkillDb.runs poisoned")
            .get(skill_id)
            .cloned()
            .unwrap_or_default();
        let adoption = self
            .adoption
            .lock()
            .expect("InMemorySkillDb.adoption poisoned")
            .get(skill_id)
            .copied();
        (
            RunStats {
                skill_id: skill_id.to_string(),
                recent,
            },
            SkillLineage {
                skill_id: skill_id.to_string(),
                state: Some("running".to_string()),
                adoption_rate_24h: adoption,
            },
        )
    }

    fn list_skill_ids(&self) -> Vec<String> {
        // Mutex 中毒时用 into_inner 恢复内部值, 避免 .expect() 触发二次 panic
        // (release 模式 panic=abort 会直接闪退)。中毒仅在 dev unwinding 路径
        // 出现, 恢复后返回可能陈旧的快照但不阻断调用方。
        let runs = self.runs.lock().unwrap_or_else(|e| e.into_inner());
        let adoption = self.adoption.lock().unwrap_or_else(|e| e.into_inner());
        let mut ids: Vec<String> = runs.keys().cloned().collect();
        for id in adoption.keys() {
            if !ids.iter().any(|x| x == id) {
                ids.push(id.clone());
            }
        }
        ids.sort();
        ids
    }

    fn record_event(&self, _event: &EvolutionEvent) {
        // No-op: the in-memory store doesn't persist `EvolutionEvent`
        // rows (the loop's own history buffer handles that). This
        // method exists only to satisfy the trait.
    }
}

/// No-op transport. Used when the transport hasn't been wired yet. The
/// real HTTP / WS implementation will be plugged in by the main
/// thread without changing the loop's interface.
#[derive(Default)]
pub struct NoopTransport;
impl HermesTransportOps for NoopTransport {
    fn post_evolution_event(&self, _event: &EvolutionEvent) -> Result<(), String> {
        Ok(())
    }
}

// =============================================================
// Public types — the wire contract for `commands/automation.rs`.
// =============================================================

/// One row of the evolution history. Rendered by `EvolutionPanel`.
/// The struct is also what we post to Hermes (when the server
/// is online) so the server can replay the same shape the UI shows.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EvolutionEvent {
    pub event_id: String,
    pub skill_id: String,
    pub version: u32,
    pub reason: EvolutionReason,
    pub action: EvolutionAction,
    /// 0..=1 score before the action was applied (None when this
    /// row is a *measurement*, not an action).
    pub before_score: Option<f32>,
    /// 0..=1 score after the action (None if we never measured).
    pub after_score: Option<f32>,
    pub ran_at: DateTime<Utc>,
    pub success: bool,
    /// Free-form human note: "deep re-parse queued", "prompt
    /// rewrite applied", etc. Useful for the history drawer.
    pub note: Option<String>,
}

// =============================================================
// The loop
// =============================================================

/// Maximum entries kept in the in-memory history ring. Same shape
/// as `AutomationState::history_limit` — 64 is enough for the
/// EvolutionPanel to show ~2 months of daily 02:00 runs plus
/// occasional manual triggers.
pub const HISTORY_LIMIT: usize = 64;

/// Skill version we report when the lineage hasn't been wired
/// yet. The real version is read from `skill_versions` once
/// memory lands.
pub const DEFAULT_VERSION: u32 = 1;

pub struct EvolutionLoop {
    db: Arc<dyn SkillDbOps>,
    healing: Arc<HealingEngine>,
    transport: Arc<dyn HermesTransportOps>,
    history: Mutex<Vec<EvolutionEvent>>,
    /// Set to `true` while a manual trigger is in flight. The
    /// daily 02:00 trigger inspects this to avoid double work.
    manual_in_flight: AtomicBool,
    /// When `true`, the daily batch is paused. Bound to the
    /// `disable_automation` Tauri command. Note: manual triggers
    /// still run even when this is set — the user explicitly asked.
    automation_disabled: AtomicBool,
    /// Set to `true` to make `run_daily_loop` exit at its next
    /// sleep boundary. Held as `Arc` so the future returned by
    /// `run_daily_loop` can drop the bit from a different thread.
    shutdown: Arc<AtomicBool>,
}

impl EvolutionLoop {
    /// Default constructor: wires the in-memory DB and the no-op
    /// transport. The main thread can override either field with a
    /// richer implementation.
    pub fn new(healing: Arc<HealingEngine>) -> Self {
        Self {
            db: Arc::new(InMemorySkillDb::new()),
            healing,
            transport: Arc::new(NoopTransport),
            history: Mutex::new(Vec::new()),
            manual_in_flight: AtomicBool::new(false),
            automation_disabled: AtomicBool::new(false),
            shutdown: Arc::new(AtomicBool::new(false)),
        }
    }

    /// Replace the skill DB at runtime (e.g. when the
    /// SQLite-backed store is wired in).
    pub fn set_db(&mut self, db: Arc<dyn SkillDbOps>) {
        self.db = db;
    }

    /// Replace the transport at runtime.
    pub fn set_transport(&mut self, transport: Arc<dyn HermesTransportOps>) {
        self.transport = transport;
    }

    /// Returns the current auto-automation flag. Bound to
    /// `disable_automation` and to the UI switch in
    /// `EvolutionPanel`.
    pub fn is_automation_disabled(&self) -> bool {
        self.automation_disabled.load(Ordering::SeqCst)
    }

    /// Flips the auto-automation flag. Called by
    /// `disable_automation(disabled)` in `commands/automation.rs`.
    pub fn set_automation_disabled(&self, disabled: bool) {
        self.automation_disabled.store(disabled, Ordering::SeqCst);
        log::info!(
            "[evolution] automation_disabled={} (daily batch is {})",
            disabled,
            if disabled { "paused" } else { "active" }
        );
    }

    /// Returns the shutdown flag (a clone of the `Arc<AtomicBool>`).
    /// The caller (lib.rs) flips the bit to break `run_daily_loop`
    /// out of its sleep.
    pub fn shutdown_handle(&self) -> Arc<AtomicBool> {
        self.shutdown.clone()
    }

    /// Long-running periodic task. Designed to be spawned once
    /// from `lib.rs` `setup`.
    ///
    /// The implementation intentionally does **not** use
    /// `tokio::time::interval` because that would fire every
    /// `Duration::from_secs(86_400)` regardless of where we are
    /// in the day. Instead we use `duration_until_next_2am()` to
    /// compute the wall-clock-aligned wait each iteration.
    pub async fn run_daily_loop(self: Arc<Self>) {
        log::info!("[evolution] daily loop starting");

        // Phase 1: fire once on startup so the panel has data on
        // first launch. Skip if automation is disabled.
        if !self.is_automation_disabled() {
            if let Err(err) = self.run_pass(EvolutionReason::DailyBatchTrigger).await {
                log::warn!("[evolution] startup pass failed: {}", err);
            }
        } else {
            log::info!("[evolution] startup pass skipped (automation disabled)");
        }

        // Phase 2: sleep until the next 02:00, then loop. The
        // shutdown bit is checked at every wake boundary.
        loop {
            if self.shutdown.load(Ordering::SeqCst) {
                log::info!("[evolution] daily loop exiting (shutdown requested)");
                return;
            }
            let wait = duration_until_next_2am();
            log::info!(
                "[evolution] next daily pass in {}s",
                wait.as_secs()
            );
            tokio::select! {
                _ = sleep(wait) => {}
                _ = wait_for_shutdown(self.shutdown.clone()) => {
                    log::info!("[evolution] daily loop exiting (shutdown requested)");
                    return;
                }
            }

            if self.shutdown.load(Ordering::SeqCst) {
                return;
            }
            if self.is_automation_disabled() {
                log::info!("[evolution] daily pass skipped (automation disabled)");
                continue;
            }
            if self.manual_in_flight.load(Ordering::SeqCst) {
                log::info!("[evolution] daily pass skipped (manual trigger in flight)");
                continue;
            }
            if let Err(err) = self.run_pass(EvolutionReason::DailyBatchTrigger).await {
                log::warn!("[evolution] daily pass failed: {}", err);
            }
        }
    }

    /// Manual trigger from the UI. Returns the new events so the
    /// panel can show a "已触发 N 条进化" toast.
    ///
    /// Unlike the daily batch, this path does **not** honour the
    /// `automation_disabled` flag — the user explicitly asked.
    /// Instead it just sets `manual_in_flight` to suppress the
    /// daily 02:00 trigger from double-firing on the same day.
    pub async fn trigger_now(&self) -> Result<Vec<EvolutionEvent>, String> {
        // Mark in-flight *before* we await anything so the daily
        // loop's `manual_in_flight` check races correctly.
        self.manual_in_flight.store(true, Ordering::SeqCst);
        let result = self.run_pass(EvolutionReason::ManualTrigger).await;
        self.manual_in_flight.store(false, Ordering::SeqCst);
        result
    }

    /// Returns the last `limit` events newer than `since`. Both
    /// arguments are optional on the Tauri side; the defaults
    /// here are "all history, up to HISTORY_LIMIT entries".
    pub fn get_history(
        &self,
        since: Option<DateTime<Utc>>,
        limit: Option<u32>,
    ) -> Result<Vec<EvolutionEvent>, String> {
        let history = self.history.lock().map_err(|e| e.to_string())?;
        let take = (limit.unwrap_or(HISTORY_LIMIT as u32) as usize).min(history.len());
        let since_ts = since.unwrap_or_else(|| {
            DateTime::<Utc>::from_timestamp(0, 0).expect("epoch is valid")
        });
        let mut out: Vec<EvolutionEvent> = history
            .iter()
            .rev()
            .filter(|e| e.ran_at >= since_ts)
            .take(take)
            .cloned()
            .collect();
        out.reverse();
        Ok(out)
    }

    /// The single pass: walk every known skill, ask
    /// `should_trigger` what to do, then dispatch to the
    /// appropriate subsystem. Returns the events that were
    /// appended to history so the caller can surface them
    /// (manual trigger toasts, Tauri event for the floating
    /// panel, etc.).
    pub async fn run_pass(&self, reason: EvolutionReason) -> Result<Vec<EvolutionEvent>, String> {
        let skill_ids = self.db.list_skill_ids();
        log::info!(
            "[evolution] pass starting (reason={:?}, skills={})",
            reason, skill_ids.len()
        );
        let mut new_events: Vec<EvolutionEvent> = Vec::new();
        for skill_id in skill_ids {
            let (stats, lineage) = self.db.gather_stats(&skill_id);
            let version = DEFAULT_VERSION;
            let trigger = match &reason {
                EvolutionReason::ManualTrigger => {
                    build_manual_trigger(&stats, &lineage, version)
                }
                _ => build_daily_trigger(&stats, &lineage, version),
            };
            // Skip skills whose lineage says "retired" / "rejected"
            // — no point in re-analyzing dead code. The daily batch
            // honours this; the manual trigger does NOT (the user
            // can ask for a re-parse of any skill, even a retired
            // one).
            if !matches!(reason, EvolutionReason::ManualTrigger) {
                match lineage.state.as_deref() {
                    Some("retired") | Some("rejected") => continue,
                    _ => {}
                }
            }
            if trigger.suggested_action == EvolutionAction::NoOp {
                // Always record the "we looked" row for the daily
                // batch so the panel can show that the loop is
                // alive. For the manual trigger we also emit one
                // row per skill so the user can see what happened.
                let event = self.materialize_event(&trigger, true, Some("no signal".into()));
                self.append_event(event.clone());
                new_events.push(event);
                continue;
            }
            let event = match trigger.suggested_action {
                EvolutionAction::LightHeal => self.execute_light_heal(&trigger),
                EvolutionAction::DeepReanalyze => self.execute_deep_reanalyze(&trigger),
                EvolutionAction::RewritePrompt => self.execute_rewrite_prompt(&trigger),
                EvolutionAction::NoOp => unreachable!(),
            };
            self.append_event(event.clone());
            new_events.push(event);
        }
        Ok(new_events)
    }

    /// Dispatch a light-heal trigger to the existing
    /// `HealingEngine`. We always build a synthetic
    /// `FailureContext` (we don't have the original executor
    /// state) but the engine accepts a default context and will
    /// route to the configured mode.
    fn execute_light_heal(&self, trigger: &EvolutionTrigger) -> EvolutionEvent {
        log::info!(
            "[evolution] LightHeal for skill_id={} version={}",
            trigger.skill_id, trigger.version
        );
        let ctx = FailureContext {
            step_index: 0,
            description: format!(
                "evolution loop light heal (reason={:?})",
                trigger.reason
            ),
            expected_x: None,
            expected_y: None,
            expected_text: None,
        };
        // `HealingEngine::attempt_heal` is sync; calling it from
        // an async fn is fine. We don't await because there's
        // nothing to await on.
        let result = self.healing.attempt_heal(&trigger.skill_id, &ctx);
        let (success, note) = match result {
            Ok(crate::automation::healing::HealResult::Healed { reason, .. }) => {
                (true, Some(reason))
            }
            Ok(crate::automation::healing::HealResult::NeedsReparse { reason }) => {
                (false, Some(reason))
            }
            Ok(crate::automation::healing::HealResult::DeepPending { reason, .. }) => {
                (true, Some(reason))
            }
            Ok(crate::automation::healing::HealResult::Failed { reason }) => {
                (false, Some(reason))
            }
            Err(err) => (false, Some(format!("heal engine error: {}", err))),
        };
        self.materialize_event(trigger, success, note)
    }

    /// Queue a deep re-parse. The current implementation just
    /// emits a `HealingEngine::attempt_deep_heal` (which is the
    /// existing v5 stub) and records a "deep re-parse queued"
    /// event. When PaddleOCR-VL-1.6 lands, the dispatch happens
    /// here.
    fn execute_deep_reanalyze(&self, trigger: &EvolutionTrigger) -> EvolutionEvent {
        log::info!(
            "[evolution] DeepReanalyze for skill_id={} version={} (reason={:?})",
            trigger.skill_id, trigger.version, trigger.reason
        );
        let ctx = FailureContext {
            step_index: 0,
            description: format!(
                "evolution loop deep re-analyze (reason={:?})",
                trigger.reason
            ),
            expected_x: None,
            expected_y: None,
            expected_text: None,
        };
        let mode = self.healing.current_mode();
        // Force deep mode for the duration of the call so the
        // engine actually takes the deep path.
        // 修复:之前 `let _ = set_mode(...)` 静默丢弃错误。
        // - 设置 deep 失败时,深度重分析会以当前(可能是 light/off)模式跑,
        //   结果与触发器预期不符,但没有任何日志暴露给用户。
        // - 恢复 mode 失败更严重:healing 引擎会永久卡在 deep 模式,
        //   影响之后所有自动化操作。两处失败都改为 log::error! 记录。
        if let Err(e) = self.healing.set_mode("deep") {
            log::error!(
                "[evolution] failed to set healing mode to 'deep' for skill_id={} (will run with current mode): {}",
                trigger.skill_id, e
            );
        }
        let result = self.healing.attempt_deep_heal(&trigger.skill_id, &ctx);
        if let Err(e) = self.healing.set_mode(&mode) {
            log::error!(
                "[evolution] CRITICAL: failed to restore healing mode to {:?} after deep re-analyze (engine stuck in 'deep' mode until next set_mode call): {}",
                mode, e
            );
        }
        let note = match result {
            crate::automation::healing::HealResult::DeepPending { reason, .. } => Some(reason),
            other => Some(format!("deep reanalyze outcome: {:?}", other)),
        };
        self.materialize_event(trigger, true, note)
    }

    /// Stub for the LLM-side prompt rewrite. The real
    /// implementation will reach out to the LLM service (already
    /// exposed via `hermes::llm_service`) with a meta-prompt that
    /// rephrases the evaluator's system instructions for the
    /// target skill. For now we record a "queued" event so the
    /// UI can show that the loop fired.
    fn execute_rewrite_prompt(&self, trigger: &EvolutionTrigger) -> EvolutionEvent {
        log::info!(
            "[evolution] RewritePrompt for skill_id={} version={} (reason={:?})",
            trigger.skill_id, trigger.version, trigger.reason
        );
        let note = match &trigger.reason {
            EvolutionReason::LowAdoptionRate { rate, window_hours } => Some(format!(
                "已记录评估 prompt 改写请求: 24h 采纳率 {} < {} (window {}h), 待 LLM 重新生成",
                rate, ADOPTION_RATE_FLOOR, window_hours
            )),
            other => Some(format!("评估 prompt 改写: {:?}", other)),
        };
        self.materialize_event(trigger, true, note)
    }

    /// Convert a `EvolutionTrigger` + outcome pair into a
    /// `EvolutionEvent`, then append to the in-memory history
    /// *and* best-effort post to Hermes.
    fn materialize_event(
        &self,
        trigger: &EvolutionTrigger,
        success: bool,
        note: Option<String>,
    ) -> EvolutionEvent {
        let event = EvolutionEvent {
            event_id: next_event_id(),
            skill_id: trigger.skill_id.clone(),
            version: trigger.version,
            reason: trigger.reason.clone(),
            action: trigger.suggested_action,
            before_score: None,
            after_score: None,
            ran_at: Utc::now(),
            success,
            note,
        };
        // Best-effort post to Hermes. We do not bubble the error
        // up: the v4 plan says "评估不能阻塞产出". The transport
        // impl is expected to log its own failure mode.
        if let Err(err) = self.transport.post_evolution_event(&event) {
            log::warn!("[evolution] transport post failed: {}", err);
        }
        event
    }

    fn append_event(&self, event: EvolutionEvent) {
        if let Ok(mut history) = self.history.lock() {
            history.push(event.clone());
            if history.len() > HISTORY_LIMIT {
                let drop = history.len() - HISTORY_LIMIT;
                history.drain(0..drop);
            }
        }
        // Mirror the event into the DB so the `skill_runs`
        // table can correlate (when it lands). The in-memory
        // default impl is a no-op.
        self.db.record_event(&event);
    }
}

// =============================================================
// Helpers — wall-clock alignment for the 02:00 batch.
// =============================================================

/// Returns the duration from `now` to the next 02:00 local time.
/// If we're already within 60 seconds of 02:00, returns a tiny
/// 1-second wait so we don't busy-loop on the boundary.
pub fn duration_until_next_2am() -> Duration {
    let now_local = Local::now();
    let today: NaiveDate = now_local.date_naive();
    let target_time = NaiveTime::from_hms_opt(2, 0, 0).expect("02:00 is a valid time");
    let mut target_dt = Local
        .from_local_datetime(&today.and_time(target_time))
        .single()
        .unwrap_or_else(|| {
            // DST spring-forward fallback: use 03:00 if 02:00
            // doesn't exist locally.
            Local
                .from_local_datetime(
                    &today.and_time(NaiveTime::from_hms_opt(3, 0, 0).unwrap()),
                )
                .single()
                .expect("03:00 must exist")
        });
    // If 02:00 today is already in the past, jump to tomorrow.
    if target_dt <= now_local {
        let tomorrow = today + chrono::Duration::days(1);
        target_dt = Local
            .from_local_datetime(&tomorrow.and_time(target_time))
            .single()
            .unwrap_or_else(|| {
                Local
                    .from_local_datetime(
                        &tomorrow.and_time(NaiveTime::from_hms_opt(3, 0, 0).unwrap()),
                    )
                    .single()
                    .expect("03:00 must exist")
            });
    }
    let diff = target_dt - now_local;
    let secs = diff.num_seconds().max(1) as u64;
    Duration::from_secs(secs)
}

/// Sleeps until the shutdown bit flips, or forever if it never
/// does. Used to make `run_daily_loop`'s `tokio::select!` aware
/// of the shutdown bit without having to do a busy poll.
async fn wait_for_shutdown(shutdown: Arc<AtomicBool>) {
    loop {
        if shutdown.load(Ordering::SeqCst) {
            return;
        }
        sleep(Duration::from_millis(500)).await;
    }
}

/// Cheap, monotonic-ish event id. We deliberately avoid the
/// `uuid` crate (it's already in the dep tree but we don't want
/// a hard dep from this leaf module) and use a `<unix-nanos>-
/// <counter>` string instead. Collisions on a single process
/// are vanishingly unlikely.
fn next_event_id() -> String {
    use std::sync::atomic::{AtomicU64, Ordering as O};
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let n = COUNTER.fetch_add(1, O::SeqCst);
    let nanos = Utc::now().timestamp_nanos_opt().unwrap_or_else(|| {
        // `timestamp_nanos_opt` returns `None` for years that
        // don't fit in an i64; fall back to seconds.
        Utc::now().timestamp() * 1_000_000_000
    });
    format!("evo-{:x}-{:x}", nanos, n)
}
