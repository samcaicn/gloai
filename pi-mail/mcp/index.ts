#!/usr/bin/env node
/**
 * pi-mail board MCP server — stdio bridge entrypoint.
 *
 * The MCP server is now hosted **inside the pi-mail daemon** itself: the
 * daemon serves `POST /mcp` on its existing HTTP UI port (default 1994) via
 * an in-process backend (no separate process, no HTTP loopback). See
 * extensions/daemon.mjs → `handleMcpRequest`.
 *
 * This file remains as a **stdio bridge** for MCP clients that prefer to
 * spawn the server as a subprocess (e.g. Claude Desktop's stdio transport).
 * It is a thin shim: it builds the same `McpServer` the daemon hosts, but the
 * default HTTP-fetch backend talks to the daemon's `/api/board*` endpoints.
 * All board logic / Jira sync stays in the daemon either way.
 *
 * Board operations run as the `human` agent (the daemon's HTTP API
 * attributes to HUMAN_AGENT_ID, same as the web UI).
 *
 * ## Run
 *
 *   node ./mcp/build/index.js --stdio
 *
 * Daemon address: PI_MAIL_BASE_URL (default http://127.0.0.1:1994).
 *
 * ## Claude Desktop / local subprocess config
 *
 *   { "mcpServers": { "pi-mail-board": {
 *       "command": "node",
 *       "args": ["/abs/path/to/pi-mail/mcp/build/index.js", "--stdio"],
 *       "env": { "PI_MAIL_BASE_URL": "http://127.0.0.1:1994" }
 *   } } }
 *
 * For the in-daemon HTTP endpoint (no subprocess), point a remote MCP client
 * at the daemon's UI port instead:
 *
 *   { "mcpServers": { "pi-mail-board": {
 *       "url": "http://127.0.0.1:1994/mcp"
 *   } } }
 */

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { createBoardMcpServer } from "./board-mcp.js";

async function runStdio(): Promise<void> {
  const server: McpServer = createBoardMcpServer();
  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error("pi-mail board MCP server running on stdio (bridge → daemon HTTP API)");
}

async function main(): Promise<void> {
  // The HTTP MCP server now lives in the daemon. This entrypoint is stdio-only.
  if (process.argv[2] === "--stdio" || process.argv.length <= 2) {
    await runStdio();
    return;
  }
  console.error("pi-mail board MCP: unknown arg. Use --stdio (or no args).");
  console.error("The HTTP MCP server is hosted by the daemon at POST /mcp.");
  process.exit(2);
}

main().catch((err) => {
  console.error("pi-mail board MCP server error:", err);
  process.exit(1);
});

// Exported for potential reuse / testing.
export { McpServer };
