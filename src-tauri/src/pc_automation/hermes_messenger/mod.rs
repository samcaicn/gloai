// Copyright (c) 2026 tupAI
//
// tupAI v5 §6.2 — Hermes messenger public surface.
//
// Re-exports the protocol types + the in-process bus. The follow-up
// PR that adds the dispatch task will live entirely inside
// `bus.rs`; the public API is stable.

pub mod bus;
pub mod events;

// Re-export the bus struct at the module root so callers
// (test code, the dispatcher) can `use pc_automation::hermes_messenger::HermesMessenger`
// without reaching into `bus::HermesMessenger` directly. Keeps
// the type / impl in their own file but the public surface flat.
#[allow(unused_imports)] // only consumed by #[cfg(test)] modules
pub use bus::HermesMessenger;

#[cfg(test)]
#[path = "tests.rs"]
mod tests;
