// Copyright (c) 2026 MeeJoy
//
// AIMarketing P1 §1 — 增量式静默自动升级
//
// This module is a self-contained façade over `tauri-plugin-updater`.
// The plugin's `Updater` trait is heavy and lives behind dynamic
// dispatch; rather than pulling it into the main state, we expose a
// tiny `UpgradeManager` that drives the policy decisions (when to
// download, when to apply) and emits Tauri events the frontend can
// subscribe to.

pub mod manager;
pub mod preconditions;
pub mod updater_client;

pub use manager::{build_silent_upgrade_plan, SilentUpgradePlan, UpgradeManager, UpgradeStatus};

#[cfg(test)]
#[path = "manager_test.rs"]
mod manager_test;
