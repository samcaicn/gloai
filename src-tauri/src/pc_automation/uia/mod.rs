// Copyright (c) 2026 tupAI
//
// UIA (Windows UI Automation) sub-module. The structured automation
// tier in the v5 stack — fastest (<1ms) and 100% accurate when the
// target process exposes AutomationId / Name. See
// `tupAI 完整开发文档.md` §0 / §1 for the tier ordering.

pub mod backend;
pub mod stub;
pub mod types;
// Real Windows backend — uses the `uiautomation` crate (Win32
// IUIAutomation COM APIs). Gated on `target_os = "windows"` to
// keep macOS / Linux compilation green without bringing in the
// Win32 deps.
#[cfg(target_os = "windows")]
pub mod windows;

// Re-export the trait + stub backend so downstream code
// (router, integration tests) can `use pc_automation::uia::{UiaBackend, StubUiaBackend}`
// without reaching into sub-modules. Keeping the type / impl in
// their own files but the public surface flat.
#[allow(unused_imports)]
pub use backend::UiaBackend;
#[allow(unused_imports)]
pub use stub::StubUiaBackend;
// Windows-only re-export. The router picks between this and
// `StubUiaBackend` based on the target OS at compile time.
#[cfg(target_os = "windows")]
#[allow(unused_imports)]
pub use windows::WindowsUiaBackend;
