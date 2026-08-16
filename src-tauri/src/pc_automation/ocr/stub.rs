// Copyright (c) 2026 AIMarketing
//
// Stub OCR backend. The real implementation will be wired in a
// follow-up PR that adds `paddleocr` / `paddlepaddle` (Rust
// bindings) to `Cargo.toml`. We deliberately do NOT pull in
// `paddle-ocr` or `tch` here — the v5 doc §0 marks those as
// deferred because on a 5800H the structured tier (UIA + CDP)
// handles >95% of the workload.

use crate::pc_automation::ocr::backend::{OcrBackend, OcrHealth};
use crate::pc_automation::ocr::types::{OcrAnchor, OcrMatch, OcrRegion};

pub struct StubOcrBackend;

impl OcrBackend for StubOcrBackend {
    fn read_text(&self, _region: OcrRegion) -> Result<Vec<OcrMatch>, String> {
        Err("OCR backend not yet wired — install paddleocr / paddlepaddle in follow-up PR".to_string())
    }

    fn locate(&self, _anchor: &OcrAnchor) -> Result<Option<OcrMatch>, String> {
        Err("OCR backend not yet wired — install paddleocr / paddlepaddle in follow-up PR".to_string())
    }

    fn health(&self) -> Result<OcrHealth, String> {
        Ok(OcrHealth {
            pp_ocr_v5_available: false,
            paddle_vl_1_6_available: false,
            vulkan_enabled: false,
        })
    }
}
