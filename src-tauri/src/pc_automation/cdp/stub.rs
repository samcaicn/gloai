// Copyright (c) 2026 AIMarketing
//
// Stub CDP backend. The real implementation will be wired in a
// follow-up PR that adds the `chromiumoxide` glue to
// `Cargo.toml` (the workspace already depends on it for
// `agent_browser`, but the pc_automation stack intentionally does
// not yet share the Browser abstraction — see the v5 doc §1.4).

use crate::pc_automation::cdp::backend::{CdpBackend, CdpResult};
use crate::pc_automation::cdp::types::CdpAction;

pub struct StubCdpBackend;

impl CdpBackend for StubCdpBackend {
    fn attach_or_launch(&self, _url: Option<&str>) -> Result<String, String> {
        Err("CDP backend not yet wired — chromiumoxide integration in follow-up PR".to_string())
    }

    fn send(&self, _action: CdpAction) -> Result<CdpResult, String> {
        Err("CDP backend not yet wired — chromiumoxide integration in follow-up PR".to_string())
    }

    fn detach(&self) -> Result<(), String> {
        Err("CDP backend not yet wired — chromiumoxide integration in follow-up PR".to_string())
    }
}
