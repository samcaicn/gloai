// Copyright (c) 2026 AIMarketing
//
// screen_parser/types.rs
//
// Unified screen-element shape that the front-end can render
// in a flat list (e.g. the "Inspector" overlay) regardless of
// whether the underlying data came from UIA (structured) or OCR
// (pixel-soup). Keeping the wire format flat — not nested — is
// what lets the UI-TARS / VLM rescue consumer walk the result
// with a simple `for el in elements` loop.
//
// Three intentional choices here, to keep this layer small:
//   1. `id` is a *content* hash, not a runtime handle. A UIA
//      node whose Name + ControlType + AutomationId match has
//      the same `id` across re-parses of the same screen; an
//      OCR match contributes its `(text, x, y, w, h)` tuple.
//   2. `source` is one of `"uia"`, `"ocr"`, `"uia+ocr"` (the
//      last one means both backends agreed, which is what the
//      router uses to bump `confidence` to `0.95+`).
//   3. `confidence` is *not* the raw UIA / OCR score. It is the
//      merged score the front-end should display, in `[0, 1]`.

use serde::{Deserialize, Serialize};

/// Bounding rectangle in screen coordinates (pixels). The
/// convention matches `OcrRegion` so the two can be merged
/// without any conversion.
#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
#[derive(Default)]
pub struct ScreenRect {
    pub x: i32,
    pub y: i32,
    pub w: i32,
    pub h: i32,
}


/// Where the element came from. Stored as a plain string (not
/// an enum) so a future `"vlm"` source can be added without an
/// IPC schema bump.
#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum ElementSource {
    Uia,
    Ocr,
    /// Both backends saw the same element. Highest trust.
    UiaOcr,
}

impl ElementSource {
    pub fn as_str(self) -> &'static str {
        match self {
            ElementSource::Uia => "uia",
            ElementSource::Ocr => "ocr",
            ElementSource::UiaOcr => "uia+ocr",
        }
    }
}

/// Coarse UI role. Aligns with UIA's `ControlType` values where
/// possible; the OCR path maps its text-only output to a default
/// of `Text`. `Hash` is implemented so the parser can produce
/// stable content-hash ids (see `WindowsScreenParserBackend::content_id`).
#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[serde(rename_all = "lowercase")]
pub enum ElementRole {
    Button,
    Text,
    Edit,
    CheckBox,
    Radio,
    Combo,
    List,
    ListItem,
    Tab,
    Menu,
    MenuItem,
    Window,
    Pane,
    Image,
    Hyperlink,
    Other,
}

impl ElementRole {
    /// Map a UIA control-type string to a coarse `ElementRole`.
    /// The UIA crate returns names like "ButtonControl" /
    /// "EditControl" / "Document"; we strip the trailing
    /// "Control" and lowercase. Unknowns fall through to `Other`.
    pub fn from_uia_control(control: &str) -> Self {
        let raw = control
            .strip_suffix("Control")
            .unwrap_or(control)
            .to_ascii_lowercase();
        match raw.as_str() {
            "button" => ElementRole::Button,
            "text" | "document" | "static" => ElementRole::Text,
            "edit" => ElementRole::Edit,
            "checkbox" | "check" => ElementRole::CheckBox,
            "radiobutton" | "radio" => ElementRole::Radio,
            "combobox" => ElementRole::Combo,
            "list" => ElementRole::List,
            "listitem" | "item" => ElementRole::ListItem,
            "tab" => ElementRole::Tab,
            "menu" | "menubar" => ElementRole::Menu,
            "menuitem" => ElementRole::MenuItem,
            "window" => ElementRole::Window,
            "pane" | "group" | "tree" | "treeitem" | "table" | "datagrid" => {
                ElementRole::Pane
            }
            "image" => ElementRole::Image,
            "hyperlink" | "link" => ElementRole::Hyperlink,
            _ => ElementRole::Other,
        }
    }
}

/// One UI element on the screen, in a UI-TARS-friendly flat
/// shape.
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ScreenElement {
    /// Stable content-hash id, see file header note #1.
    pub id: String,
    pub role: ElementRole,
    pub text: String,
    pub rect: ScreenRect,
    pub source: ElementSource,
    /// Merged trust score in `[0, 1]`. See file header note #3.
    pub confidence: f32,
    /// Original UIA `AutomationId`, if known. Empty string when
    /// the element came from OCR only.
    pub automation_id: String,
}

/// What window / monitor to parse. `None` = the currently focused
/// window; `Some(rect)` = a hand-picked screen rectangle.
#[derive(Serialize, Deserialize, Debug, Clone, Default)]
#[serde(rename_all = "camelCase")]
pub struct ParseRequest {
    /// Limit the parse to a specific screen rectangle. `None` =
    /// the focused window's bounding rect (or the full virtual
    /// screen when there is no focused window).
    pub region: Option<ScreenRect>,
    /// Whether to also run OCR over the parsed region. Default
    /// `true`. The Windows implementation uses this to fill in
    /// the text of UIA nodes that have `Name=""` (which is
    /// common for legacy Win32 controls that only carry
    /// accessible labels via UIA's `Value` property).
    pub include_ocr: bool,
    /// Minimum UIA / OCR confidence to keep an element. Default
    /// `0.5`.
    pub min_confidence: f32,
}
