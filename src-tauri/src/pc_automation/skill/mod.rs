// Copyright (c) 2026 tupAI
//
// UIRPA public API for the `pc_automation::skill`
// tree. Mirrors the `pc_automation/mod.rs` barrel pattern: the
// front-end / executor / commands import the names they need
// from `crate::pc_automation::skill::*`, and this file decides
// which symbols are part of the public surface.
//
// The sibling `tests.rs` file is `#[path]`-included from this
// module so `cargo test --lib pc_automation::skill` picks it up
// without polluting the public API.

pub mod convert;
pub mod decryptor;
pub mod export;
pub mod registry;
pub mod storage;
pub mod template;
pub mod types;

// Re-export the public surface at the module root so downstream
// code (executor, integration tests) can `use pc_automation::skill::LocalSkillStorage`
// without reaching into `skill::storage::LocalSkillStorage`
// directly. The trait + impl still live in their own files.
// `#[allow(unused_imports)]` because the re-exports are only
// consumed by `#[cfg(test)]` modules and by the `commands::skill`
// Tauri surface (which goes through `skill::compiler` instead).
#[allow(unused_imports)]
pub use convert::{from_pc_step, to_pc_step, to_pc_steps};
#[allow(unused_imports)]
pub use decryptor::SkillDecryptor;
#[allow(unused_imports)]
pub use registry::SkillRegistry;
#[allow(unused_imports)]
pub use storage::LocalSkillStorage;
#[allow(unused_imports)]
pub use template::render_template;

#[cfg(test)]
#[path = "tests.rs"]
mod tests;
