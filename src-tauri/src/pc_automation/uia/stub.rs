// Copyright (c) 2026 AIMarketing
//
// Stub UIA backend for non-Windows platforms (macOS, Linux).
// The real implementation lives in `uia/windows.rs` and is gated
// on `target_os = "windows"`. On macOS / Linux the UIA tier is
// unavailable — the router cascades to OCR fallback and then VLM
// rescue, which is the intended degradation path.

use crate::pc_automation::uia::backend::UiaBackend;
use crate::pc_automation::uia::types::{UiaNode, UiaSelector};

pub struct StubUiaBackend;

impl UiaBackend for StubUiaBackend {
    fn get_focused_window(&self) -> Result<Option<UiaNode>, String> {
        Err("UIA backend not available on this platform".to_string())
    }

    fn find_by(&self, _sel: &UiaSelector) -> Result<Option<UiaNode>, String> {
        Err("UIA backend not available on this platform".to_string())
    }

    fn click(&self, _node: &UiaNode) -> Result<(), String> {
        Err("UIA backend not available on this platform".to_string())
    }

    fn type_text(&self, _node: &UiaNode, _text: &str) -> Result<(), String> {
        Err("UIA backend not available on this platform".to_string())
    }

    fn get_root(&self) -> Result<UiaNode, String> {
        Err("UIA backend not available on this platform".to_string())
    }
}
