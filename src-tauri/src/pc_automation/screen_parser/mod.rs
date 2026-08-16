// Copyright (c) 2026 AIMarketing
//
// screen_parser/mod.rs
//
// Flat screen-content parser: walks the UIA tree of the
// focused window and (optionally) overlays OCR text for
// name-less controls, returning a single normalised list
// of `ScreenElement`.
//
// See the v5 doc for the rationale — the v3 YOLO /
// TinyClick / ScreenParser stack was dropped in favour of
// this thin UIA + OCR composer. The point of the layer is
// not to "understand" the screen (that's VLM rescue's job)
// but to hand the front-end / recipe a single shape to walk.
//
// Public surface (consumed by `commands/pc_automation.rs`):
//   * `WindowsScreenParserBackend` — wired on Windows
//   * `StubScreenParserBackend`    — used on macOS / Linux
//   * `parse_*` selector grammar helpers (in `types.rs`)

pub mod backend;
pub mod stub;
pub mod types;
pub mod windows;

/// Platform-correct constructor. Returns the Windows
/// implementation on Windows, the stub otherwise. Keeps the
/// `PcAutomationState::new` site in
/// `commands/pc_automation.rs` free of `#[cfg]` noise.
pub fn default_backend(
    uia: std::sync::Arc<dyn crate::pc_automation::uia::backend::UiaBackend>,
    ocr: std::sync::Arc<dyn crate::pc_automation::ocr::backend::OcrBackend>,
) -> std::sync::Arc<dyn crate::pc_automation::screen_parser::backend::ScreenParserBackend> {
    #[cfg(target_os = "windows")]
    {
        std::sync::Arc::new(windows::WindowsScreenParserBackend::new(uia, ocr))
    }
    #[cfg(not(target_os = "windows"))]
    {
        let _ = (uia, ocr); // silence unused-arg warnings on macOS / Linux
        std::sync::Arc::new(stub::StubScreenParserBackend)
    }
}
