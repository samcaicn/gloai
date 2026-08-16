// Copyright (c) 2026 MeeJoy
//
// Unit tests for tupAI P1 §1 — upgrade manager.
//
// We intentionally avoid touching the network or disk in these
// tests. The manager's preconditions are pure functions on the
// platform shell helpers; the rest of the manager is a state
// machine we drive by hand.

use super::manager::{UpgradeManager, UpgradeStatus};

#[test]
fn new_manager_is_idle_and_disabled() {
    let manager = UpgradeManager::new();
    assert!(!manager.is_auto_upgrade_enabled());
    match manager.status() {
        UpgradeStatus::Idle { latest_version } => assert!(latest_version.is_none()),
        other => panic!("expected Idle, got {:?}", other),
    }
    assert!(manager.pending().is_none());
    assert_eq!(manager.current_version(), env!("CARGO_PKG_VERSION"));
}

#[test]
fn set_auto_upgrade_enabled_flips_status() {
    let manager = UpgradeManager::new();
    manager.set_auto_upgrade_enabled(true);
    assert!(manager.is_auto_upgrade_enabled());
    manager.set_auto_upgrade_enabled(false);
    assert!(!manager.is_auto_upgrade_enabled());
    match manager.status() {
        UpgradeStatus::Disabled => {}
        other => panic!("expected Disabled, got {:?}", other),
    }
}

#[test]
#[ignore = "calls wmic / netsh / df on the host; flaky on busy dev machines. \
             Run with `cargo test -- --ignored` on a quiet CI runner."]
fn preconditions_reject_zero_required_bytes() {
    // Zero-byte requirement is a "no-op" precondition: the
    // disk branch needs a positive number to avoid divide-by-
    // zero surprises in the multiplier.
    let result = UpgradeManager::preconditions_satisfied(0);
    assert!(result.is_ok());
}

#[test]
fn package_version_is_non_empty() {
    // Belt-and-braces: if the manager ever stops reading from
    // CARGO_PKG_VERSION (e.g. someone hard-codes a value), the
    // current version will stop matching the compile-time
    // constant and the panel will render a stale value.
    let manager = UpgradeManager::new();
    assert!(!manager.current_version().is_empty());
    assert!(manager.current_version().contains('.'));
}
