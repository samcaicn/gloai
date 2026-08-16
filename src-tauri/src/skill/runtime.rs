// Copyright (c) 2026 tupAI
//
// tupAI P0 §1 — In-memory MCP runtime.
//
// The `McpRuntime` owns a decrypted `SkillManifest` in process memory
// and is the only place that does so. Once dropped, the manifest
// bytes are overwritten via the `zeroize` crate. (We use the
// `Zeroizing<String>` wrapper from `zeroize` for the name and
// description fields; the manifest itself is `Clone`, so callers
// must take care to drop their copies once the runtime is consumed.)
//
// Future work (A1, A6):
//   * `load(source)` will pull the encrypted MCP blob from
//     `EncryptedStorage` (built by A1) and decrypt it via
//     AES-256-GCM with a hardware-bound key.
//   * `McpRuntime` will integrate with `Recorder::generate_skill_md`
//     (built by A6) so a freshly recorded skill can be compiled and
//     loaded in one call.

use zeroize::Zeroizing;

use super::compiler::compile_skill_md;
use super::manifest::SkillManifest;

pub struct McpRuntime {
    /// Decrypted manifest. Wrapped in `Zeroizing` so the buffer is
    /// wiped on drop. We keep the whole manifest zeroized — the
    /// engine is free to clone it locally, but the canonical
    /// `McpRuntime` should be the only long-lived owner.
    decrypted: Zeroizing<SkillManifest>,
}

impl McpRuntime {
    /// Load a runtime directly from a `skill.md` source. This is
    /// what the floating panel uses when a user pastes raw YAML —
    /// no compilation step is needed in the caller's hands.
    pub fn from_skill_md(source: &str) -> Result<Self, String> {
        let compiled = compile_skill_md(source)?;
        Ok(Self {
            decrypted: Zeroizing::new(compiled.manifest),
        })
    }

    /// Borrow the manifest. The returned reference is a `&SkillManifest`
    /// to the *zeroized* buffer, so it will be wiped on drop.
    pub fn manifest(&self) -> &SkillManifest {
        &self.decrypted
    }

    /// Explicit destroy hook. Equivalent to dropping the value, but
    /// spelt out for callers that hold the runtime inside a struct
    /// and want to make the intent obvious.
    pub fn destroy(self) {
        // The destructor on `Zeroizing<SkillManifest>` will scrub the
        // buffer; this method just makes the call site self-documenting.
        drop(self);
    }
}
