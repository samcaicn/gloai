// Copyright (c) 2026 AIMarketing
//
// UIA backend trait. Implemented per platform (Windows today; macOS
// `accessibility` and Linux AT-SPI shims land in follow-up PRs).
// Methods take `&self` and return `Result<_, String>` so the trait
// stays object-safe and so the error string can be surfaced directly
// into `RouterError::PrimaryMiss(...)` without allocation gymnastics.

use crate::pc_automation::uia::types::{UiaNode, UiaSelector};

pub trait UiaBackend: Send + Sync {
    /// Returns the focused window's UIA root, or `None` if no window
    /// is focused / the desktop has focus.
    fn get_focused_window(&self) -> Result<Option<UiaNode>, String>;

    /// Depth-first search rooted at the focused window, returning
    /// the first node that matches every populated field of `sel`.
    fn find_by(&self, sel: &UiaSelector) -> Result<Option<UiaNode>, String>;

    /// Synthesise a click at the centre of `node`'s bounding rect.
    fn click(&self, node: &UiaNode) -> Result<(), String>;

    /// Focus `node` and then type `text` via the platform's
    /// `SendInput` / `CGEventCreateKeyboardEvent` / `XTestFakeKeyEvent`
    /// equivalent.
    fn type_text(&self, node: &UiaNode, text: &str) -> Result<(), String>;

    /// Returns the entire UIA tree rooted at the focused window.
    /// Used by the healing subsystem to diff against a recorded
    /// snapshot.
    fn get_root(&self) -> Result<UiaNode, String>;
}
