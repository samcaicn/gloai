/**
 * In-process MCP server (Streamable HTTP) hosted by the pi-mail daemon.
 * Extracted from http.mjs. The daemon serves POST /mcp via the MCP
 * Streamable HTTP transport, backed by an IN-PROCESS board backend that calls
 * the daemon's own board functions directly (no HTTP loopback, no second
 * process). It reuses the same createBoardMcpServer() the standalone stdio
 * bridge (mcp/index.js) builds, so the tool surface is identical. The SDK +
 * compiled board-mcp.js are imported lazily so the daemon keeps working if the
 * MCP build or its npm deps are absent (graceful 503 on /mcp in that case).
 *
 * Stateless mode: a fresh McpServer + transport per POST/DELETE (the stateless
 * Streamable HTTP transport is single-use — see SDK docs). Method dispatch:
 * POST = JSON-RPC and DELETE = session close go through the SDK transport;
 * GET = standalone SSE stream is served directly by this module as a
 * keep-alive (the SDK's stateless GET handler emits nothing and the board
 * server pushes no notifications, so we emit SSE comment keep-alives
 * ourselves to satisfy clients that wait for the first byte). Anything else
 * falls through to the SDK's 405 with Allow: GET, POST, DELETE. Board
 * operations run as the human agent, same as the web UI and the socket
 * protocol's board_* cases.
 */
import path from "node:path";
import { fileURLToPath } from "node:url";
import { boardState, board, jiraCfg } from "./board.mjs";
import {
  boardMove,
  boardAssign,
  boardComment,
  boardProgress,
  boardCreate,
  boardUpdate,
  boardFlag,
  boardSetConfig,
} from "./board-ops.mjs";
import { syncBoard } from "./jira.mjs";
import { messagePage } from "./core.mjs";
import { chatPost, chatGet } from "./chat.mjs";
import { projectsState } from "./spawn.mjs";

const HUMAN_AGENT_ID = "00000000-0000-0000-0000-000000000000";

/** Interval between SSE keep-alive comments on the GET /mcp stream. The MCP
 *  Streamable HTTP spec explicitly permits SSE comment lines (": ...") as
 *  keep-alives. The board server pushes no real notifications over the GET
 *  stream (stateless, no subscriptions), so the stream is a keep-alive: we
 *  emit one comment immediately on open (so clients waiting for the first
 *  byte don't hit their connect timeout) and then every SSE_KEEPALIVE_MS. */
const SSE_KEEPALIVE_MS = 15_000;

const MCP_BUILD_PATH = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  "..", "..", "mcp", "build", "board-mcp.js",
);

/**
 * In-process board backend for the hosted MCP server. Each method calls the
 * daemon's board functions directly and returns the SAME response shape the
 * daemon's /api/board* HTTP endpoints return, so the MCP tool formatters
 * (which expect those shapes) work unchanged.
 */
const inProcessBoardBackend = {
  async getBoard(opts) {
    return boardState(HUMAN_AGENT_ID, opts);
  },
  async getBoardConfig() {
    return {
      config: {
        baseUrl: board.config.baseUrl,
        email: board.config.email,
        jql: board.config.jql,
        projectKey: board.config.projectKey,
        issueType: board.config.issueType,
        subtaskIssueType: board.config.subtaskIssueType,
        apiTokenSet: !!board.config.apiToken,
        jiraEnabled: board.config.jiraEnabled !== false,
        nudgeEnabled: board.config.nudgeEnabled !== false,
        nudgeIntervalMin: board.config.nudgeIntervalMin ?? 60,
        mmEnabled: board.config.mmEnabled === true,
        mmIntervalMin: board.config.mmIntervalMin ?? 30,
        mmModel: board.config.mmModel ?? "",
        mmMaxLifetimeMin: board.config.mmMaxLifetimeMin ?? 15,
        workerMaxLifetimeMin: board.config.workerMaxLifetimeMin ?? 30,
        ceoEnabled: board.config.ceoEnabled === true,
        ceoIntervalMin: board.config.ceoIntervalMin ?? 120,
        ceoModel: board.config.ceoModel ?? "",
        ceoMaxLifetimeMin: board.config.ceoMaxLifetimeMin ?? 15,
        ceoAllowedHosts: board.config.ceoAllowedHosts ?? [],
        chatIdleMin: board.config.chatIdleMin ?? 60,
      },
      columns: board.columns,
    };
  },
  async setBoardConfig(config) {
    // The MCP tool passes a config record; boardSetConfig expects {config, columns}.
    return boardSetConfig({ config });
  },
  async syncBoard() {
    if (!jiraCfg()) {
      const reason = board.config.jiraEnabled === false ? "Jira is disabled" : "Jira is not configured";
      return { ok: false, error: reason };
    }
    const r = await syncBoard("manual");
    return { ok: !board.syncError, error: board.syncError ?? undefined, columns: r?.columns ?? null };
  },
  async moveTask(taskId, column, note) {
    const r = await boardMove(HUMAN_AGENT_ID, taskId, column, note);
    return r.error ? { ok: false, error: r.error } : { ok: true, warning: r.warning };
  },
  async commentTask(taskId, text) {
    const r = await boardComment(HUMAN_AGENT_ID, taskId, text);
    return r.error ? { ok: false, error: r.error } : { ok: true, warning: r.warning };
  },
  async progressTask(taskId, text) {
    const r = await boardProgress(HUMAN_AGENT_ID, taskId, text);
    return r.error ? { ok: false, error: r.error } : { ok: true };
  },
  async assignTask(taskId, assignee, newSession) {
    const r = await boardAssign(HUMAN_AGENT_ID, taskId, assignee, !!newSession);
    return r.error ? { ok: false, error: r.error } : { ok: true, warning: r.warning };
  },
  async createTask(body) {
    const r = await boardCreate(HUMAN_AGENT_ID, body);
    return r.error
      ? { ok: false, error: r.error }
      : { ok: true, taskId: r.task.id, key: r.task.key ?? undefined };
  },
  async updateTask(taskId, body) {
    const r = await boardUpdate(HUMAN_AGENT_ID, taskId, body);
    return r.error ? { ok: false, error: r.error } : { ok: true, warning: r.warning };
  },
  async flagTask(taskId, reason, clear) {
    const r = boardFlag(HUMAN_AGENT_ID, taskId, reason, !!clear);
    return r.error ? { ok: false, error: r.error } : { ok: true, warning: r.warning };
  },
  // ── MCP project chat (in-process: calls lib/chat.mjs directly) ───────────
  async chatPost(body) {
    const r = await chatPost({ cwd: body.cwd, message: body.message, threadId: body.threadId, wait: body.wait !== false, timeoutMs: body.timeoutMs });
    return r.error ? { ok: false, error: r.error } : { ok: true, ...r };
  },
  async chatGet(body) {
    const r = await chatGet({ threadId: body.threadId, timeoutMs: body.timeoutMs });
    return r.error ? { ok: false, error: r.error } : { ok: true, ...r };
  },
  listMessages(opts) {
    return messagePage(opts);
  },
  listProjects() {
    return projectsState();
  },
};

let mcpDepsPromise = null;
/** Lazily import the MCP SDK + compiled board-mcp.js (cached). Throws if the
 *  build or deps are unavailable. */
async function ensureMcp() {
  if (mcpDepsPromise) return mcpDepsPromise;
  mcpDepsPromise = (async () => {
    const [{ McpServer }, { StreamableHTTPServerTransport }, mod] = await Promise.all([
      import("@modelcontextprotocol/sdk/server/mcp.js"),
      import("@modelcontextprotocol/sdk/server/streamableHttp.js"),
      import(MCP_BUILD_PATH),
    ]);
    return { McpServer, StreamableHTTPServerTransport, createBoardMcpServer: mod.createBoardMcpServer };
  })().catch((e) => {
    mcpDepsPromise = null; // allow retry on transient failure
    throw e;
  });
  return mcpDepsPromise;
}

/** JSON-RPC error response (used by the MCP /mcp route). */
function jsonRpcError(res, httpStatus, code, message) {
  if (res.headersSent) return;
  res.writeHead(httpStatus, { "Content-Type": "application/json; charset=utf-8" });
  res.end(JSON.stringify({ jsonrpc: "2.0", error: { code, message }, id: null }));
}

/** Serve the GET /mcp standalone SSE stream directly.
 *
 *  Why we don't delegate GET to the SDK transport: the SDK's stateless
 *  `handleGetRequest` creates a ReadableStream whose `start()` only stores
 *  the controller — it enqueues NOTHING. It only writes a priming event on
 *  POST (and only when an eventStore is configured, which we don't). So a
 *  stateless GET stream is silent until the server pushes a notification,
 *  which the board server never does. A client that blocks waiting for the
 *  first SSE byte (e.g. bundle-mcp, with a 30s connect timeout) therefore
 *  hangs. Serving the keep-alive stream ourselves — an immediate comment +
 *  periodic comments — is spec-compliant and fixes that. POST/DELETE (which
 *  need JSON-RPC dispatch + session handling) still go through the SDK. */
function handleGetSseStream(req, res) {
  // The client MUST Accept text/event-stream (spec). Missing → 406, matching
  // the SDK transport's own behaviour so the route stays spec-compliant.
  const accept = req.headers.accept || "";
  if (!accept.includes("text/event-stream")) {
    jsonRpcError(res, 406, -32000, "Not Acceptable: Client must accept text/event-stream");
    return;
  }
  res.writeHead(200, {
    "Content-Type": "text/event-stream",
    "Cache-Control": "no-cache, no-transform",
    Connection: "keep-alive",
  });
  // Immediate keep-alive: gives waiting clients a first byte right away so
  // their connect timeout never fires. Comment lines are ignored by SSE
  // parsers and explicitly endorsed as keep-alives by the Streamable HTTP spec.
  res.write(": keepalive\n\n");
  const timer = setInterval(() => {
    if (res.writableEnded || res.destroyed) { clearInterval(timer); return; }
    res.write(": keepalive\n\n", () => {});
  }, SSE_KEEPALIVE_MS);
  const stop = () => clearInterval(timer);
  req.on("close", stop);
  res.on("close", stop);
  res.on("error", stop);
}

/** Handle a /mcp request (any method — POST/GET/DELETE/…) using the in-process
 *  backend. Stateless: a fresh McpServer + transport per POST/DELETE. GET is
 *  served directly as a keep-alive SSE stream (see handleGetSseStream).
 *  `parsedBody` is only meaningful for POST (pre-parsed by the caller to
 *  enforce the size guard); pass undefined for non-POST. */
export async function handleMcpRequest(req, res, parsedBody) {
  // GET opens a standalone SSE keep-alive stream. Served directly (not via
  // the SDK transport) because the SDK's stateless GET handler emits nothing
  // and the board server pushes no notifications — see handleGetSseStream.
  if (req.method === "GET") {
    handleGetSseStream(req, res);
    return;
  }
  let deps;
  try {
    deps = await ensureMcp();
  } catch (e) {
    jsonRpcError(res, 503, -32603, `MCP server unavailable: ${e?.message ?? String(e)}`);
    return;
  }
  const server = deps.createBoardMcpServer(inProcessBoardBackend);
  const transport = new deps.StreamableHTTPServerTransport({ sessionIdGenerator: undefined });
  res.on("close", () => {
    transport.close().catch(() => {});
    server.close().catch(() => {});
  });
  try {
    await server.connect(transport);
    await transport.handleRequest(req, res, parsedBody);
  } catch (e) {
    if (!res.headersSent) jsonRpcError(res, 500, -32603, `Internal error: ${e?.message ?? String(e)}`);
  }
}

export { jsonRpcError };
