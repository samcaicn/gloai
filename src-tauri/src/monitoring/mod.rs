// Copyright (c) 2026 MeeJoy
//
// tupAI P1 §5 — 后台静默监测与记忆沉淀
//
// The full CDP-based monitor is a future task; for the staged
// rollout we expose a tiny stub that writes the events the
// front-end asks about to a daily-rotated log file under
// `<app_data_dir>/tupai/monitor/activity-<YYYY-MM-DD>.log`. This is
// enough to wire the UI, the tray, and the test suite.

pub mod observer;

use std::sync::atomic::{AtomicBool, Ordering};

use observer::ActivityLog;

/// Global "monitoring on/off" switch. The tray menu flips it and
/// the front-end reflects it via the `set_monitoring_enabled`
/// command.
static MONITORING_ENABLED: AtomicBool = AtomicBool::new(true);

pub fn is_monitoring_enabled() -> bool {
    MONITORING_ENABLED.load(Ordering::SeqCst)
}

pub fn set_monitoring_enabled(value: bool) {
    MONITORING_ENABLED.store(value, Ordering::SeqCst);
}

/// Flips the current state and returns the new value. Used by the
/// tray menu callback to keep the label in sync.
pub fn toggle_monitoring() -> bool {
    let next = !is_monitoring_enabled();
    set_monitoring_enabled(next);
    next
}

/// Returns the most recent activity entries, newest first.
pub fn recent_activity(limit: u32) -> Vec<observer::ActivityEntry> {
    ActivityLog::read_recent(limit as usize)
}

#[cfg(test)]
#[path = "observer_test.rs"]
mod observer_test;
