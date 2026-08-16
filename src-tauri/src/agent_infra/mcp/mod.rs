// Copyright (c) 2026 tupAI
//
// Aggregate module for `@agent-infra/mcp/*` (Rust port).
// Only `im_bridge` remains wired into the Tauri invoke layer; the
// generic MCP client / http_server / benchmark / shared helpers were
// dead stubs and have been removed.

pub mod im_bridge;

/// Symbolic names of the bundled MCP servers (mirrors `MCPServerName`).
pub mod mcp_servers {
    pub const FILE_SYSTEM: &str = "filesystem";
    pub const COMMANDS: &str = "commands";
    pub const BROWSER: &str = "browser";
    pub const IM_BRIDGE: &str = "im_bridge";
}
