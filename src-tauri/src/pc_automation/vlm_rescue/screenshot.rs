// Copyright (c) 2026 AIMarketing
//
// AIMarketing v5 §5.2 — screenshot capture for the VLM rescue pipeline.
//
// The production path is platform-native:
//   * Windows — `windows` crate (GDI: GetDC / CreateCompatibleDC / BitBlt
//               → encode the bitmap as PNG via the `image` crate).
//   * macOS   — `CGDisplayCreateImage` via the `core-graphics` crate
//               (deferred; bring in a follow-up PR).
//   * Linux   — `xcap` (X11) or wlr-screencopy (Wayland); X11 path is
//               trivial via `xcap` (deferred; bring in a follow-up PR).
//
// The first cut is a *stub*: each platform implementation is a single
// `Err("...")` returning a "follow-up PR" message so the upstream caller
// (`VlmRescue::try_rescue`) can short-circuit and return a deterministic
// error string to the front-end. The Windows path is wired but currently
// returns the same stub message because the GDI feature set
// (`Win32_Graphics_Gdi`, `Win32_Storage_Xps`) is not enabled in
// `Cargo.toml` — that's a deferred Cargo.toml edit owned by the main
// — see design doc.
//
// Returning `Vec<u8>` (PNG bytes) instead of `String` keeps the option
// open for callers that want to bypass base64 encoding on disk.

use base64::Engine as _;
use base64::engine::general_purpose::STANDARD as BASE64_STANDARD;

/// Capture the region around the currently focused window/control.
///
/// Returns the PNG bytes ready for embedding in the VLM prompt (the
/// caller is responsible for base64-encoding the buffer).
#[allow(dead_code)]
pub async fn capture_focused_region() -> Result<Vec<u8>, String> {
    capture_platform_stub("focused region")
}

/// Capture the entire primary display.
///
/// Same return type as `capture_focused_region` — PNG bytes, no
/// encoding applied.
#[allow(dead_code)] // 5.2
pub async fn capture_full_screen() -> Result<Vec<u8>, String> {
    capture_platform_stub("full screen")
}

/// Convenience wrapper that base64-encodes a screenshot for embedding
/// in a JSON prompt (the VLM request format used by
/// `hermes::llm_service::hermes_llm_complete`).
///
/// Errors from the underlying capture propagate unchanged.
#[allow(dead_code)] // 5.2
pub async fn capture_focused_region_b64() -> Result<String, String> {
    let bytes = capture_focused_region().await?;
    Ok(BASE64_STANDARD.encode(bytes))
}

#[cfg(target_os = "windows")]
#[allow(dead_code)]
fn capture_platform_stub(kind: &str) -> Result<Vec<u8>, String> {
    // The Windows GDI path is the v5 design target. The
    // `Win32_Graphics_Gdi` + `Win32_Storage_Xps` + `Win32_UI_WindowsAndMessaging`
    // feature set is not currently enabled on the `windows` crate in
    // `Cargo.toml` (see task invariants),
    // so we short-circuit here.
    //
    // Follow-up PR checklist:
    //   1. Cargo.toml: enable `Win32_Graphics_Gdi`,
    //      `Win32_Storage_Xps`, and `Win32_UI_WindowsAndMessaging` on
    //      the `windows` crate.
    //   2. Replace this stub with the real GDI BitBlt + PNG encode.
    let _ = kind;
    Err(format!(
        "screenshot ({kind}) not yet wired on Windows — enable Win32_Graphics_Gdi + Win32_UI_WindowsAndMessaging on `windows` crate and implement BitBlt + PNG encode (see vlm_rescue/screenshot.rs)"
    ))
}

#[cfg(target_os = "macos")]
#[allow(dead_code)] // 5.2
fn capture_platform_stub(kind: &str) -> Result<Vec<u8>, String> {
    let _ = kind;
    Err(format!(
        "screenshot ({kind}) not implemented on macOS — bring in `core-graphics` crate and use CGDisplayCreateImage (follow-up PR)"
    ))
}

#[cfg(target_os = "linux")]
#[allow(dead_code)] // 5.2
fn capture_platform_stub(kind: &str) -> Result<Vec<u8>, String> {
    let _ = kind;
    Err(format!(
        "screenshot ({kind}) not implemented on Linux — bring in `xcap` crate (X11) or wlr-screencopy (Wayland) (follow-up PR)"
    ))
}

#[cfg(not(any(target_os = "windows", target_os = "macos", target_os = "linux")))]
#[allow(dead_code)]
fn capture_platform_stub(kind: &str) -> Result<Vec<u8>, String> {
    let _ = kind;
    Err("screenshot not implemented on this platform".to_string())
}
