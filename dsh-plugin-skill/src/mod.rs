// Skill system — adapted from safeopcapp.
//
// Sub-modules:
//   - manifest   : skill metadata (name, version, steps, execution type)
//   - eval       : 5-dimension evaluation vector
//   - registry   : atomic version switching + inbox + rollback
//   - compiler   : SKILL.md → binary representation
//   - executor   : step-by-step execution engine (shell, file, http, wait)
//   - embedded   : compile-time embedded built-in skills (include_str!)
//   - loader     : filesystem skill loader (~/.dsh/skills/ hot-load)

pub mod compiler;
pub mod embedded;
pub mod eval;
pub mod executor;
pub mod loader;
pub mod manifest;
pub mod registry;
pub mod sandbox;

pub use eval::{SkillEvaluation, SkillEvalEngine};
pub use executor::{ExecutionResult, SkillExecutor, StepResult};
pub use manifest::{Capability, ExecAction, ExecutionType, InputAction, Permission, Runtime, SkillCategory, SkillManifest, Step};
pub use sandbox::{Sandbox, SandboxError};
