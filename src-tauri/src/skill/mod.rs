// Copyright (c) 2026 AIMarketing
//
// AIMarketing P0 §1 — Skill execution engine (skill.md ↔ MCP)
//
// Re-exports the four sub-modules so the rest of the codebase can
// reach them via `crate::skill::*` rather than the long form.

pub mod compiler;
pub mod manifest;
pub mod runtime;

// ClientAdopt.  `registry` owns the
// running-version map, the inbox of `NeedsReview` proposals, and
// the rollback book. The Tauri commands in `commands::skill` are
// the only public surface.
pub mod registry;

// Local cache of the remote skill catalog. Survives upstream
// 502s by mirroring the last-known-good `skill.list` payload
// to `<app_data>/skill_catalog_cache.json`. Diffed on every
// refresh so the front-end can render "new / updated / removed"
// without re-querying the upstream.
pub mod catalog_cache;

// SkillMemory (family-tree + run history +
// adoption rate).  The `memory` module owns the `SkillDb` state and
// CRUD helpers; `fts` is the FTS5 recall wrapper.
pub mod fts;
pub mod memory;

// SkillSource.  `proposal` is the
// unified candidate schema (Teaching / Healing / Recorder /
// Monitoring / Community / Manual); `proposal_store` is the
// SQLite persistence layer (table `skill_proposals`).
pub mod proposal;
pub mod proposal_store;

#[cfg(test)]
mod compiler_test;

// `#[allow(unused_imports)]` because `compile_skill_md` and
// `decompile_to_skill_md` are only consumed by `#[cfg(test)]`
// modules (notably `commands::teaching`); the rest of the
// crate reaches them through `skill::compiler::*` directly.
#[allow(unused_imports)]
pub use compiler::{compile_skill_md, decompile_to_skill_md, CompiledMcp};
pub use manifest::SkillManifest;
pub use registry::{AdoptOutcome, InboxItem, SkillEvaluation, SkillRegistry};
pub use runtime::McpRuntime;
