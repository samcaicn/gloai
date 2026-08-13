//! Test helpers that boot a `ProductRuntime` on the mock LLM.

use std::path::PathBuf;

use dsh_core::{DeliveryProfile, LlmBackend, ProductRuntime, RuntimeRequest};
use dsh_llm_mock::MockTurn;

/// Build a test-profile request against `workspace` / `home` with scripted turns.
pub fn mock_request(workspace: PathBuf, home: PathBuf, turns: Vec<MockTurn>) -> RuntimeRequest {
    RuntimeRequest {
        profile: Some(DeliveryProfile::Test),
        llm: Some(LlmBackend::Mock),
        workspace: Some(workspace),
        home: Some(home),
        mock_turns: turns,
        ..RuntimeRequest::default()
    }
}

/// Resolve and boot a mock runtime. Panics on misconfiguration so tests stay short.
pub fn boot_mock(workspace: PathBuf, home: PathBuf, turns: Vec<MockTurn>) -> ProductRuntime {
    ProductRuntime::resolve(mock_request(workspace, home, turns))
        .expect("mock runtime request must resolve")
        .boot()
        .expect("mock runtime must boot")
}
