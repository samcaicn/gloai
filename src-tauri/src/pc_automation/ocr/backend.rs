// Copyright (c) 2026 AIMarketing
//
// OCR backend trait + health envelope. The health struct is what
// the IPC layer surfaces to the front-end so the Settings screen
// can show "PaddleOCR-VL-1.6: not installed" honestly.

use crate::pc_automation::ocr::types::{OcrAnchor, OcrMatch, OcrRegion};
use serde::{Deserialize, Serialize};

/// Engine-availability report. The router is allowed to keep
/// running with any subset of these as `false` — the OCR tier
/// simply degrades to whatever the host actually has.
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct OcrHealth {
    pub pp_ocr_v5_available: bool,
    pub paddle_vl_1_6_available: bool,
    pub vulkan_enabled: bool,
}

pub trait OcrBackend: Send + Sync {
    /// OCR the given region. The backend decides which engine to
    /// run (typically PP-OCRv5 unless the region is marked
    /// `PaddleVl16` via the anchor).
    fn read_text(&self, region: OcrRegion) -> Result<Vec<OcrMatch>, String>;

    /// Resolve an `OcrAnchor` to a single match. `None` means
    /// "scanned, but no `match_text` was found at/above the
    /// confidence threshold".
    fn locate(&self, anchor: &OcrAnchor) -> Result<Option<OcrMatch>, String>;

    /// Cheap health probe — does NOT load the heavy models. The
    /// first call to `read_text` / `locate` is the one that pays
    /// the model-load cost.
    fn health(&self) -> Result<OcrHealth, String>;
}
