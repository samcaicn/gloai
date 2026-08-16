// Copyright (c) 2026 AIMarketing
//
// screen_parser/stub.rs
//
// Non-Windows stub. The trait is still implemented (so the
// v5 router can be wired on macOS without `cfg`-gating the
// `PcAutomationState::new` constructor), but the implementation
// is a no-op that reports "no parser available" through the
// health envelope. The macOS / Linux backends land in follow-up
// PRs alongside their UIA / OCR shims.

use crate::pc_automation::screen_parser::backend::{ScreenParserBackend, ScreenParserHealth};
use crate::pc_automation::screen_parser::types::ParseRequest;
use crate::pc_automation::screen_parser::types::ScreenElement;

pub struct StubScreenParserBackend;

impl ScreenParserBackend for StubScreenParserBackend {
    fn parse(&self, _req: ParseRequest) -> Result<Vec<ScreenElement>, String> {
        Err("screen_parser: no backend on this platform".to_string())
    }

    fn health(&self) -> Result<ScreenParserHealth, String> {
        Ok(ScreenParserHealth {
            uia_backend_available: false,
            ocr_backend_available: false,
            parse_capable: false,
        })
    }
}
