// Copyright (c) 2026 tupAI
//
// terminator_bridge — adapter module that bridges terminator's
// `Desktop` / `AccessibilityEngine` to tupai's existing `UiaBackend`
// and `OcrBackend` traits.
//
// Architecture:
//
//   ┌──────────────────────────────────────────────────┐
//   │  tupai router / executor (UNCHANGED)             │
//   │  PcRouter { uia, cdp, ocr }                      │
//   ├──────────────┬──────────────┬────────────────────┤
//   │ UiaBackend   │ CdpBackend   │ OcrBackend         │
//   │  (trait)     │  (trait)     │  (trait)           │
//   ├──────────────┼──────────────┼────────────────────┤
//   │ THIS MODULE  │ cdp/ (kept)  │ THIS MODULE        │
//   │ Terminator   │ WebSocket    │ Terminator         │
//   │ UiaBackend   │ CdpBackend   │ OcrBackend         │
//   │  ↓           │              │  ↓                 │
//   │ terminator   │              │ terminator         │
//   │ ::Desktop    │              │ ::Desktop::ocr_*   │
//   └──────────────┴──────────────┴────────────────────┘
//
// The adapter is a thin layer: it translates tupai's `UiaSelector`
// to terminator's `Selector`, calls `Desktop::locator().first()`,
// and wraps the result back into tupai's `UiaNode`.
//
// Benefits over the old hand-rolled `WindowsUiaBackend`:
//   1. Cross-platform (macOS/Linux via accessibility/AT-SPI)
//   2. Richer selectors (Near, Above, Below, LeftOf, RightOf, Has, Nth)
//   3. Better error messages from terminator
//   4. Active upstream maintenance (mediar-ai/terminator)
//   5. OCR via `uni-ocr` (replaces WinRT-only OCR)

pub mod uia_backend;
pub mod ocr_backend;
#[cfg(test)]
#[path = "tests.rs"]
mod tests;

pub use uia_backend::TerminatorUiaBackend;
#[allow(unused_imports)]
pub use ocr_backend::TerminatorOcrBackend;

/// Lazily-initialized terminator `Desktop` instance shared across
/// all backend calls. Terminator's `Desktop::new` creates the
/// platform engine (UIAutomation COM on Windows, AXUIElement on
/// macOS, AT-SPI on Linux) which is expensive to create, so we
/// cache it in a `OnceCell`.
///
/// **Thread-safety note**: the wrapped `Desktop` value is held inside
/// a `std::sync::Mutex` so that every public access path
/// (`shared_desktop()`) hands out a `MutexGuard` that holds the lock
/// for its entire lifetime. This serializes all access to the
/// underlying COM UIAutomation object on Windows, which is **not**
/// thread-safe: concurrent calls from multiple threads can corrupt
/// the call stack and trigger STATUS_ACCESS_VIOLATION (0xc0000005)
/// or STATUS_STACK_BUFFER_OVERRUN (0xc0000409). The previous
/// `OnceCell<Desktop>` design returned `&'static Desktop` without
/// any locking, and made the test suite (`cargo test -- pc_automation`)
/// flaky 3/5 — the bug surfaced as soon as tests ran in parallel
/// because each `#[test]` thread could dereference the same COM
/// pointer at the same time.
use once_cell::sync::OnceCell;
use std::sync::{Mutex, MutexGuard};
use terminator::Desktop;

static DESKTOP: OnceCell<Mutex<Desktop>> = OnceCell::new();

/// Get the shared `Desktop` instance, initializing it on first call.
/// Returns a `MutexGuard<'static, Desktop>` — the lock is held for
/// the lifetime of the guard, serializing all COM access until the
/// caller drops it. Callers that need to perform multiple COM calls
/// must keep the guard alive (don't bind it to a temporary that
/// drops at the end of a statement). Because `MutexGuard` derefs to
/// `&Desktop`, the call sites `let desktop = shared_desktop()?;
/// desktop.focused_element()?;` continue to work without changes.
pub fn shared_desktop() -> Result<MutexGuard<'static, Desktop>, String> {
    let mutex = DESKTOP.get_or_try_init(|| {
        Desktop::new_default()
            .map(Mutex::new)
            .map_err(|e| format!("terminator Desktop init failed: {}", e))
    })?;
    mutex.lock().map_err(|e| format!("desktop mutex poisoned: {}", e))
}

/// Run an async future to completion in a blocking-safe manner.
///
/// **Problem**: terminator's OCR/screenshot methods are `async`, but
/// tupai's `UiaBackend` / `OcrBackend` traits are synchronous. The
/// UIA backend avoids this by using only synchronous `UIElement` methods,
/// but the OCR backend needs to call async `Desktop` methods.
///
/// **Solution**: 
/// - If we're inside a multi-threaded tokio runtime (the normal case
///   for Tauri commands), use `tokio::task::block_in_place` + `Handle::block_on`.
/// - If we're in a current-thread runtime or no runtime, spawn a
///   dedicated OS thread with its own current-thread runtime.
///
/// This avoids the "Cannot block the current thread from within
/// a runtime" panic that a naive `Runtime::new().block_on()` would
/// trigger inside a tokio context.
pub(crate) fn block_on_async<F, T>(future: F) -> Result<T, String>
where
    F: std::future::Future<Output = Result<T, String>> + Send + 'static,
    T: Send + 'static,
{
    match tokio::runtime::Handle::try_current() {
        Ok(handle) => match handle.runtime_flavor() {
            tokio::runtime::RuntimeFlavor::MultiThread => {
                // Multi-threaded runtime — safe to use block_in_place
                Ok(tokio::task::block_in_place(|| handle.block_on(future))?)
            }
            _ => {
                // Current-thread runtime — can't block_in_place, use a
                // dedicated thread with its own runtime.
                let (tx, rx) = std::sync::mpsc::channel::<Result<T, String>>();
                std::thread::spawn(move || {
                    let rt = match tokio::runtime::Builder::new_current_thread()
                        .enable_all()
                        .build()
                    {
                        Ok(rt) => rt,
                        Err(e) => {
                            let _ = tx.send(Err(format!("runtime init: {}", e)));
                            return;
                        }
                    };
                    let result = rt.block_on(future);
                    let _ = tx.send(result);
                });
                rx.recv().map_err(|e| format!("thread recv: {}", e))?
            }
        },
        Err(_) => {
            // No runtime — create a temporary one
            let rt = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .map_err(|e| format!("runtime init: {}", e))?;
            Ok(rt.block_on(future)?)
        }
    }
}
