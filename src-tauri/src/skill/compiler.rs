// Copyright (c) 2026 AIMarketing
//
// AIMarketing P0 §1 — Skill compiler: skill.md (YAML) <-> MCP (binary)
//
// The MCP (Meta-Compilation Package) is the binary distribution
// format for a compiled skill. Layout (v1):
//
//   bytes 0..4   = magic "MCP1"  ([0x4D, 0x43, 0x50, 0x31])
//   bytes 4..N   = borsh-serialized SkillManifest
//
// We keep the format deliberately small so that future versions
// (MCP2, MCP3) can extend the magic byte and add a versioned header
// without invalidating existing on-disk packages.

use borsh::BorshDeserialize;
use chrono::Utc;
use serde::{Deserialize, Serialize};

use super::manifest::SkillManifest;

const MCP_MAGIC: [u8; 4] = [0x4D, 0x43, 0x50, 0x31];

/// The result of `compile_skill_md`. `mcp_binary` is what the
/// `McpRuntime` will keep in memory; `timestamp` lets the UI show
/// "compiled at …" without having to re-parse the bytes.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CompiledMcp {
    pub manifest: SkillManifest,
    pub mcp_binary: Vec<u8>,
    pub timestamp: i64,
}

/// Compile a `skill.md` (YAML) source string into an MCP binary
/// blob. Returns the parsed manifest (so callers don't have to
/// re-decompile) plus the raw bytes.
pub fn compile_skill_md(source: &str) -> Result<CompiledMcp, String> {
    let manifest = SkillManifest::from_skill_md(source)?;
    manifest.validate()?;
    let body = borsh::to_vec(&manifest).map_err(|e| format!("borsh encode failed: {}", e))?;
    let mut mcp_binary = Vec::with_capacity(MCP_MAGIC.len() + body.len());
    mcp_binary.extend_from_slice(&MCP_MAGIC);
    mcp_binary.extend_from_slice(&body);
    Ok(CompiledMcp {
        manifest,
        mcp_binary,
        timestamp: Utc::now().timestamp(),
    })
}

/// Reverse of `compile_skill_md`. Returns the parsed manifest and
/// the timestamp the MCP was originally produced at.
pub fn decompile_mcp(bytes: &[u8]) -> Result<DecompiledMcp, String> {
    if bytes.len() <= MCP_MAGIC.len() {
        return Err(format!(
            "MCP blob is too short: {} bytes (expected at least {})",
            bytes.len(),
            MCP_MAGIC.len()
        ));
    }
    if bytes[..MCP_MAGIC.len()] != MCP_MAGIC {
        return Err("MCP magic mismatch — not a v1 MCP blob".to_string());
    }
    let body = &bytes[MCP_MAGIC.len()..];
    let manifest = SkillManifest::try_from_slice(body)
        .map_err(|e| format!("borsh decode failed: {}", e))?;
    manifest.validate()?;
    Ok(DecompiledMcp {
        manifest,
        timestamp: Utc::now().timestamp(),
        original_size: bytes.len(),
    })
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DecompiledMcp {
    pub manifest: SkillManifest,
    pub timestamp: i64,
    pub original_size: usize,
}

/// Convenience for `commands::skill` — returns the YAML source back
/// so the front-end can display / diff the original skill.md.
pub fn decompile_to_skill_md(bytes: &[u8]) -> Result<String, String> {
    let decompiled = decompile_mcp(bytes)?;
    decompiled.manifest.to_skill_md()
}
