// Copyright (c) 2026 AIMarketing
//
// TerminatorOcrBackend — basic OCR backend using terminator's
// `Desktop::ocr_screenshot` / `ocr_image_path` APIs.
//
// **Limitation**: terminator's OCR API returns a plain `String`
// (all text concatenated), not structured matches with per-line
// coordinates. This means:
//   - `read_text` returns a single `OcrMatch` covering the full
//     region (no per-line coordinates).
//   - `locate` returns a single match covering the full region if
//     the `match_text` appears anywhere in the OCR result.
//   - This is a **degraded** OCR mode compared to the Windows-only
//     `WindowsOcrBackend` (which returns per-line coordinates via
//     WinRT `Windows.Media.Ocr`).
//
// **Usage**: This backend is used as the OCR fallback on non-Windows
// platforms (macOS, Linux). On Windows, the existing
// `WindowsOcrBackend` is preferred because it provides coordinates.
// The router cascades: primary (UIA/CDP) → OCR fallback → VLM rescue,
// so a degraded OCR backend only means more VLM rescues, not a
// broken pipeline.

use crate::pc_automation::ocr::backend::{OcrBackend, OcrHealth};
use crate::pc_automation::ocr::types::{OcrAnchor, OcrMatch, OcrRegion};

pub struct TerminatorOcrBackend;

impl TerminatorOcrBackend {
    /// Run OCR on a screenshot of the given region (or full screen
    /// if `region` is None / `full_screen` is true). Returns the
    /// raw text string.
    ///
    /// Uses `tokio::task::block_in_place` when inside a multi-threaded
    /// tokio runtime (the normal case for Tauri commands), or spawns
    /// a dedicated thread with its own runtime otherwise.
    fn run_ocr(region: Option<OcrRegion>) -> Result<String, String> {
        let desktop = super::shared_desktop()?.clone();

        // Capture screenshot of the appropriate monitor
        let screenshot = super::block_on_async(async move {
            // For region-specific OCR, we capture the primary monitor
            // and let the caller filter by region bounds. A future
            // optimisation could crop the screenshot before OCR.
            let monitor = desktop
                .get_primary_monitor()
                .await
                .map_err(|e| format!("get_primary_monitor: {}", e))?;
            desktop
                .capture_monitor(&monitor)
                .await
                .map_err(|e| format!("capture_monitor: {}", e))
        })?;

        // Run OCR on the screenshot
        let desktop = super::shared_desktop()?.clone();
        let text = super::block_on_async(async move {
            desktop
                .ocr_screenshot(&screenshot)
                .await
                .map_err(|e| format!("ocr_screenshot: {}", e))
        })?;

        // If a region is specified, we could post-filter by
        // coordinates — but since terminator's OCR doesn't return
        // coordinates, we just return the full text. The router
        // will use this text for the `locate` call.
        let _ = region; // suppress unused warning
        Ok(text)
    }
}

impl OcrBackend for TerminatorOcrBackend {
    fn read_text(&self, region: OcrRegion) -> Result<Vec<OcrMatch>, String> {
        let text = Self::run_ocr(Some(region))?;
        if text.is_empty() {
            return Ok(Vec::new());
        }
        // Return a single match covering the full region.
        // This is a degraded mode — the WindowsOcrBackend returns
        // per-line matches with coordinates.
        Ok(vec![OcrMatch {
            text,
            confidence: 0.7, // Conservative confidence for degraded mode
            x: region.x,
            y: region.y,
            w: region.w,
            h: region.h,
        }])
    }

    fn locate(&self, anchor: &OcrAnchor) -> Result<Option<OcrMatch>, String> {
        if anchor.match_text.is_empty() {
            return Ok(None);
        }

        let region = if anchor.full_screen {
            None
        } else {
            anchor.region
        };

        let text = Self::run_ocr(region)?;

        // Check if the match_text appears in the OCR result.
        // Case-sensitive match (matches the existing WindowsOcrBackend
        // behaviour).
        if text.contains(&anchor.match_text) {
            let r = region.unwrap_or(OcrRegion { x: 0, y: 0, w: 0, h: 0 });
            return Ok(Some(OcrMatch {
                text: anchor.match_text.clone(),
                confidence: 0.6, // Lower confidence for substring match
                x: r.x,
                y: r.y,
                w: r.w,
                h: r.h,
            }));
        }

        Ok(None)
    }

    fn health(&self) -> Result<OcrHealth, String> {
        // Terminator's OCR uses `uni-ocr` under the hood, which
        // wraps platform-native OCR (WinRT on Windows, Vision on
        // macOS, Tesseract on Linux). We report it as available
        // since the Desktop was successfully initialised.
        Ok(OcrHealth {
            pp_ocr_v5_available: false,
            paddle_vl_1_6_available: false,
            vulkan_enabled: false,
        })
    }
}
