// Copyright (c) 2026 tupAI
//
// screen_parser/backend.rs
//
// Trait + health envelope for the screen-parser layer. The trait
// is intentionally narrow: parse a rectangle, get back a flat
// list of `ScreenElement`. No selection / no click — those live
// in the router and on the UIA / CDP backends, where they belong.

use crate::pc_automation::ocr::types::OcrRegion;
use crate::pc_automation::screen_parser::types::{ParseRequest, ScreenElement};
use serde::{Deserialize, Serialize};

/// Engine-availability report. Mirrors `OcrHealth` shape so the
/// front-end can render the two side-by-side without a custom
/// card.
#[derive(Serialize, Deserialize, Debug, Clone, Default)]
#[serde(rename_all = "camelCase")]
pub struct ScreenParserHealth {
    pub uia_backend_available: bool,
    pub ocr_backend_available: bool,
    /// True when the parse path on this host can run end-to-end
    /// (UIA *or* OCR present). The front-end dims the "Inspector"
    /// button when this is false.
    pub parse_capable: bool,
}

/// Convert a `ScreenRect` (the screen-parser DTO) into an
/// `OcrRegion` (the OCR backend DTO). They have the same field
/// layout but live in different modules so the call sites are
/// explicit.
pub fn to_ocr_region(r: crate::pc_automation::screen_parser::types::ScreenRect) -> OcrRegion {
    OcrRegion { x: r.x, y: r.y, w: r.w, h: r.h }
}

pub trait ScreenParserBackend: Send + Sync {
    /// Parse the requested region into a flat list of
    /// `ScreenElement`. Implementations are expected to:
    ///   * walk the UIA tree of the focused window
    ///   * (optionally) overlay OCR for elements with no `Name`
    ///   * dedupe + merge by content hash
    /// The result is *unsorted* — the front-end / VLM consumer
    /// is free to sort by `rect.y` for a top-to-bottom reading
    /// order.
    fn parse(&self, req: ParseRequest) -> Result<Vec<ScreenElement>, String>;

    /// Cheap health probe; mirrors the OCR / UIA convention.
    fn health(&self) -> Result<ScreenParserHealth, String>;
}
