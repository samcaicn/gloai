// Copyright (c) 2026 tupAI
//
// CDP (Chrome DevTools Protocol) sub-module. The browser automation
// tier — covers every renderer-based target (Electron apps, in-app
// Chromium widgets, web-based trading consoles such as 雪球 /
// 同花顺 iFinD / Choice). On a 5800H class machine, 80% of user
// time is in a browser, so this tier is the v5 workhorse.

pub mod backend;
pub mod stub;
pub mod types;
pub mod websockets;

// Re-export the trait + stub backend so downstream code
// (router, integration tests) can `use pc_automation::cdp::{CdpBackend, StubCdpBackend}`
// without reaching into `cdp::backend` / `cdp::stub`. Keeping the
// type / impl in their own files but the public surface flat.
// `#[allow(unused_imports)]` because the re-exports are only
// consumed by `#[cfg(test)]` modules — the rest of the crate
// reaches for the trait via `cdp::backend::CdpBackend` directly.
#[allow(unused_imports)]
pub use backend::CdpBackend;
#[allow(unused_imports)]
pub use stub::StubCdpBackend;
// Real backend wired into the router in `commands/pc_automation.rs`.
// Re-exported for the integration tests + the
// `WebSocketCdpBackend::default()` opt-in from the Tauri setup hook.
#[allow(unused_imports)]
pub use websockets::WebSocketCdpBackend;
