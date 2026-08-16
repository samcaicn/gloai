// Copyright (c) 2026 tupAI
//
// Stub BrowserSkill backend for `#[cfg(test)]` and environments where
// the `bsk` CLI is intentionally absent. Returns deterministic results
// so the executor / IPC layer can be unit-tested without shelling out.

use crate::pc_automation::browser_skill::backend::{BrowserSkillBackend, BSK_EXTENSION_STORE_URL};
use crate::pc_automation::browser_skill::types::{
    BrowserSkillAction, BrowserSkillResult, BrowserSkillStatus,
};

pub struct StubBrowserSkillBackend;

impl BrowserSkillBackend for StubBrowserSkillBackend {
    fn health(&self) -> Result<String, String> {
        Ok("stub-0.0.0".to_string())
    }

    fn exec(&self, _action: BrowserSkillAction) -> Result<BrowserSkillResult, String> {
        Ok(BrowserSkillResult {
            success: true,
            stdout: "stub".to_string(),
            stderr: String::new(),
            exit_code: 0,
            latency_ms: 0,
        })
    }

    fn ensure_installed(&self) -> Result<(), String> {
        Ok(())
    }

    fn status(&self) -> BrowserSkillStatus {
        BrowserSkillStatus {
            cli_installed: true,
            cli_version: Some("stub-0.0.0".to_string()),
            daemon_running: true,
            extension_connected: true,
            needs_setup: false,
            needs_extension: false,
            extension_store_url: BSK_EXTENSION_STORE_URL.to_string(),
            error: None,
        }
    }
}
