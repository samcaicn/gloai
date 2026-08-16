// Copyright (c) 2026 tupAI
//
// OCR sub-module. The third / fallback tier in the v5 stack:
// PP-OCRv5 (fast path, ~30ms on CPU) plus PaddleOCR-VL-1.6 (deep
// path, ~400ms on iGPU). The router only falls through to OCR
// when UIA and CDP both miss — typically for self-drawn Chinese
// trading UIs (华泰 / 大智慧 / 平安) where the renderer offers
// zero structure.

pub mod backend;
pub mod stub;
pub mod types;
// Real Windows backend — uses `Windows.Media.Ocr` (WinRT) so the
// OS's built-in OCR runtime is the on-the-wire engine. The
// module is gated on `target_os = "windows"` to keep macOS /
// Linux compilation green without bringing in the WinRT deps.
#[cfg(target_os = "windows")]
pub mod windows;

// Re-export the trait + stub backend so downstream code
// (router, integration tests) can `use pc_automation::ocr::{OcrBackend, StubOcrBackend}`
// without reaching into `ocr::backend` / `ocr::stub`. Keeping the
// type / impl in their own files but the public surface flat.
// `#[allow(unused_imports)]` because the re-exports are only
// consumed by `#[cfg(test)]` modules — the rest of the crate
// reaches for the trait via `ocr::backend::OcrBackend` directly.
#[allow(unused_imports)]
pub use backend::OcrBackend;
#[allow(unused_imports)]
pub use stub::StubOcrBackend;
// Windows-only re-export. The router picks between this and
// `StubOcrBackend` based on the target OS at compile time.
#[cfg(target_os = "windows")]
#[allow(unused_imports)]
pub use windows::WindowsOcrBackend;
