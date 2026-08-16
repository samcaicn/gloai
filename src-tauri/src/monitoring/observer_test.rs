// Copyright (c) 2026 MeeJoy
//
// Smoke tests for the activity log writer. These tests deliberately
// hit the local filesystem (the on-disk log file is the only
// observable surface of the monitor in the staged rollout) but
// always clean up after themselves.

use std::sync::Mutex;

// 测试串行化:monitoring 的所有测试共享同一个全局日志目录,
// 必须串行执行避免互相把对方刚写入的条目 reset / overwrite。
// 用 once_cell 风格的 static 即可,无需引入额外 crate。
static LOG_GUARD: Mutex<()> = Mutex::new(());

use super::observer::{log_event, ActivityLog, ActivityEntry};
use crate::monitoring::observer;

#[test]
fn log_event_round_trips_through_disk() {
    let _g = LOG_GUARD.lock().unwrap_or_else(|p| p.into_inner());
    // Clean up any leftovers from a previous test run.
    ActivityLog::reset();

    let entry = log_event("skill.run", "compiled skill foo into mcp://abc");
    assert_eq!(entry.kind, "skill.run");
    assert!(entry.timestamp.contains('T'));

    let recent = observer::ActivityLog::read_recent(10);
    assert!(
        recent.iter().any(|e| e.kind == "skill.run"),
        "expected the new entry in the recent log, got {:?}",
        recent
    );

    // Clean up.
    ActivityLog::reset();
}

#[test]
fn recent_entries_are_newest_first() {
    let _g = LOG_GUARD.lock().unwrap_or_else(|p| p.into_inner());
    ActivityLog::reset();

    log_event("first", "earliest");
    // The timestamp resolution on some platforms is per-second, so
    // we sleep for a whole second to guarantee the second entry
    // sorts strictly newer.
    std::thread::sleep(std::time::Duration::from_millis(1_100));
    log_event("second", "middle");
    std::thread::sleep(std::time::Duration::from_millis(1_100));
    log_event("third", "latest");

    let recent = observer::ActivityLog::read_recent(10);
    assert!(recent.len() >= 3, "expected at least 3 entries, got {:?}", recent);
    let kinds: Vec<&str> = recent.iter().map(|e| e.kind.as_str()).collect();
    let first_idx = kinds.iter().position(|k| *k == "first");
    let second_idx = kinds.iter().position(|k| *k == "second");
    let third_idx = kinds.iter().position(|k| *k == "third");
    assert!(third_idx < second_idx, "third should be before second");
    assert!(second_idx < first_idx, "second should be before first");

    ActivityLog::reset();
}

#[test]
fn limit_caps_returned_entries() {
    let _g = LOG_GUARD.lock().unwrap_or_else(|p| p.into_inner());
    ActivityLog::reset();
    for i in 0..5 {
        log_event("burst", &format!("entry-{}", i));
    }
    let recent = observer::ActivityLog::read_recent(2);
    assert!(recent.len() <= 2, "limit=2 should cap the result, got {}", recent.len());
    ActivityLog::reset();
}

#[allow(dead_code)]
fn _entry_type_in_use(_: ActivityEntry) {}
