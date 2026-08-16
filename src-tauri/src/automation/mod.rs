// Copyright (c) 2026 tupAI
//
// tupAI — automation module (skill execution + system_software + browser).
//
// The split between `state.rs` and `engine.rs` is intentional:
//   * `state` is data-only and can be reasoned about without the
//     Tauri runtime.
//   * `engine` is the executor; it owns an `AppHandle` and emits
//     Tauri events to the front-end.
//
// Other agents (A3, A4, A6) contribute sibling modules:
//   * `system_software` / `browser` / `browser_steps` / `dispatcher` (A3)
//   * `recorder` / `healing` (A6)

pub mod engine;
pub mod state;
pub mod system_software;
pub mod browser;
pub mod browser_steps;
pub mod dispatcher;
pub mod recorder;
pub mod healing;
pub mod adopt_policy;
pub mod rollback;
pub mod flowchart;
// EvolutionLoop. The loop is a sibling
// of the other automation primitives; it is registered as a
// Tauri state in `lib.rs` setup.
pub mod evolution;
pub mod heuristics;

#[cfg(test)]
mod engine_test;

#[cfg(test)]
mod system_software_test;

#[cfg(test)]
mod browser_test;

#[cfg(test)]
mod recorder_test;

#[cfg(test)]
mod healing_test;

pub use engine::{spawn_execution, AutomationEngine};
pub use evolution::{EvolutionEvent, EvolutionLoop};
pub use healing::{FailureContext, HealRecord, HealResult, HealingEngine};
pub use recorder::{Recorder, RecordingStatus};
