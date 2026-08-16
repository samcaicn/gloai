// Copyright (c) 2026 AIMarketing
//
// CDP backend trait + result envelope. The trait is intentionally
// minimal (3 methods) so a hand-rolled WS client can satisfy it
// while we wait for the `chromiumoxide` integration.

use crate::pc_automation::cdp::types::CdpAction;
use serde::{Deserialize, Serialize};

/// Result envelope returned by every `send`. Carries enough
/// metadata to surface a useful error to the recipe runner without
/// forcing the caller to `await` a second message.
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct CdpResult {
    pub success: bool,
    pub return_value: Option<String>,
    pub error: Option<String>,
    pub latency_ms: u64,
}

pub trait CdpBackend: Send + Sync {
    /// Attach to a running Chromium target (if `url` is `None`,
    /// attach to whatever the most recently launched browser is
    /// exposing) or, failing that, launch a new one and attach.
    /// Returns the target id.
    fn attach_or_launch(&self, url: Option<&str>) -> Result<String, String>;

    /// Dispatch a single CDP action and wait synchronously for the
    /// reply. Backends that prefer async/stream APIs can adapt by
    /// blocking internally — the router only cares about the
    /// final `CdpResult`.
    fn send(&self, action: CdpAction) -> Result<CdpResult, String>;

    /// Detach from the target. Idempotent.
    fn detach(&self) -> Result<(), String>;
}
