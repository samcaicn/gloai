// Copyright (c) 2026 AIMarketing
//
// `agent_infra` aggregates the Rust port of `gloai` agent-infra
// packages. The browser automation, search providers, generic MCP
// client/server and shared helpers were dead stubs (only `mcp::im_bridge`
// is wired into the Tauri invoke layer) and have been removed.
// Browser automation now lives in `automation::browser` (chromiumoxide-backed).

pub mod mcp;
