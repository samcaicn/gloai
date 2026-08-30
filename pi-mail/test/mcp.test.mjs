// Tests for the in-daemon MCP server (task c8f3cb77).
//
// The MCP server is hosted inside the pi-mail daemon (POST /mcp on its HTTP
// UI port), backed by an in-process board backend — no separate process, no
// HTTP loopback. These tests boot an isolated daemon, then drive /mcp over
// real HTTP with JSON-RPC 2.0 (initialize → tools/list → tools/call) and
// assert the board tools work end-to-end against the daemon's live board
// state.
//
// Run: npm test   (node:test runner)

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { spawn as pSpawn } from "node:child_process";
import * as net from "node:net";
import * as crypto from "node:crypto";
import * as fs from "node:fs";
import * as path from "node:path";
import * as os from "node:os";

const REPO = path.resolve(import.meta.dirname, "..");
const DAEMON = path.join(REPO, "extensions", "daemon.mjs");

// ── Isolation harness ──────────────────────────────────────────────────────

let tmpHome, proc, sockPath, port;
// Kill any spawned daemon when the test runner exits (incl. Ctrl-C / timeout)
// so interrupted runs don't leave orphan daemon processes behind.
process.on("exit", () => { try { if (proc) proc.kill("SIGKILL"); } catch {} });

function freePort() {
  return new Promise((resolve) => {
    const s = net.createServer();
    s.listen(0, "127.0.0.1", () => {
      const p = s.address().port;
      s.close(() => resolve(p));
    });
  });
}

function startDaemon() {
  return new Promise((resolve, reject) => {
    proc = pSpawn(process.execPath, [DAEMON], {
      env: {
        ...process.env,
        HOME: tmpHome,
        PI_MAIL_UI_HOST: "127.0.0.1",
        PI_MAIL_UI_PORT: String(port),
        // No real tmux/pi needed for the board MCP tests.
        PATH: process.env.PATH,
      },
      stdio: ["ignore", "pipe", "pipe"],
    });
    let stderr = "";
    proc.stderr.on("data", (c) => { stderr += c.toString(); });
    proc.on("exit", (code, sig) => {
      if (!proc.__stopped) console.error("daemon exited unexpectedly", code, sig, stderr.slice(-500));
    });
    // Wait for the socket to appear (daemon is up), then wait for HTTP.
    const tryConnect = (retries = 0) => {
      const s = net.createConnection(sockPath);
      s.once("connect", () => { s.destroy(); resolve(); });
      s.once("error", () => {
        if (retries > 200) return reject(new Error("daemon socket never appeared\n" + stderr));
        setTimeout(() => tryConnect(retries + 1), 30);
      });
    };
    tryConnect();
  });
}

function stopDaemon() {
  if (!proc) return Promise.resolve();
  proc.__stopped = true;
  return new Promise((r) => {
    proc.once("exit", () => { proc = null; r(); });
    proc.kill("SIGTERM");
    setTimeout(() => { if (proc) { proc.kill("SIGKILL"); proc = null; } r(); }, 3000);
  });
}

/** POST JSON-RPC to /mcp and return the parsed response.
 *  Handles both application/json and text/event-stream (SSE) responses — the
 *  SDK may answer either; for a single request the result is the one
 *  `data:` line carrying the matching JSON-RPC id. */
async function mcp(rpc) {
  const res = await fetch(`http://127.0.0.1:${port}/mcp`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      // The MCP Streamable HTTP transport requires the client to accept both.
      "Accept": "application/json, text/event-stream",
    },
    body: JSON.stringify(rpc),
  });
  const ct = res.headers.get("content-type") || "";
  const text = await res.text();
  let body = null;
  if (ct.includes("text/event-stream")) {
    // Collect `data:` payloads from each SSE event block.
    for (const block of text.split(/\n\n+/)) {
      const dataLines = block.split("\n").filter((l) => l.startsWith("data:")).map((l) => l.slice(5).trim());
      if (!dataLines.length) continue;
      const parsed = dataLines.join("\n");
      try {
        const obj = JSON.parse(parsed);
        // Match the request id when present (notifications have no id).
        if (obj.id === rpc.id || obj.id === undefined) { body = obj; break; }
      } catch {}
    }
  } else {
    try { body = JSON.parse(text); } catch {}
  }
  return { status: res.status, body, text };
}

let rpcId = 0;
const call = (method, params) => mcp({ jsonrpc: "2.0", id: ++rpcId, method, params });
const notify = (method, params) => mcp({ jsonrpc: "2.0", method, params });

before(async () => {
  tmpHome = fs.mkdtempSync(path.join(os.tmpdir(), "pimail-mcp-"));
  sockPath = path.join(tmpHome, ".pi", "agent", "mail-daemon.sock");
  port = await freePort();
  await startDaemon();
});

after(async () => {
  await stopDaemon();
  fs.rmSync(tmpHome, { recursive: true, force: true });
});

// ── socket client helper (to register workers that stamp task groups) ────────

function mkSocketClient() {
  return new Promise((resolve, reject) => {
    const s = net.createConnection(sockPath);
    s.setEncoding("utf8");
    let buf = "";
    let nextId = 1;
    const pending = new Map();
    s.on("data", (chunk) => {
      buf += chunk;
      const lines = buf.split("\n");
      buf = lines.pop();
      for (const line of lines) {
        if (!line.trim()) continue;
        let m; try { m = JSON.parse(line); } catch { continue; }
        if (m.type === "ping") { s.write(JSON.stringify({ type: "pong" }) + "\n"); continue; }
        if (m._reqId != null && pending.has(m._reqId)) {
          const e = pending.get(m._reqId); clearTimeout(e.t); pending.delete(m._reqId); e.res(m);
        }
      }
    });
    s.once("connect", () => resolve({
      request(msg, timeoutMs = 5000) {
        const id = nextId++;
        return new Promise((res, rej) => {
          const t = setTimeout(() => { pending.delete(id); rej(new Error("timeout: " + msg.type)); }, timeoutMs);
          pending.set(id, { res, rej, t });
          s.write(JSON.stringify({ ...msg, _reqId: id }) + "\n");
        });
      },
      close() { s.destroy(); },
    }));
    s.once("error", reject);
  });
}

// ── initialize / tools/list ─────────────────────────────────────────────────

test("/mcp initialize handshake succeeds", async () => {
  const r = await call("initialize", {
    protocolVersion: "2024-11-05",
    capabilities: {},
    clientInfo: { name: "mcp-test", version: "1.0" },
  });
  assert.equal(r.status, 200);
  assert.ok(r.body, "response is JSON");
  assert.equal(r.body.jsonrpc, "2.0");
  assert.equal(r.body.id, 1);
  assert.ok(r.body.result, "has result");
  assert.equal(r.body.result.serverInfo.name, "pi-mail-board");
  // The daemon-hosted server must advertise the streamable HTTP transport.
  // (Presence of capabilities is enough; exact shape is SDK-version dependent.)
  assert.ok(r.body.result.capabilities, "advertises capabilities");
});

test("tools/list exposes the board tools", async () => {
  // initialize first (most clients require it, though stateless server is permissive)
  await call("initialize", { protocolVersion: "2024-11-05", capabilities: {}, clientInfo: { name: "t", version: "1" } });
  const r = await call("tools/list");
  assert.equal(r.status, 200);
  const names = (r.body.result.tools ?? []).map((t) => t.name);
  for (const expected of [
    "board_list_tasks", "board_get_task", "board_move_task", "board_comment_task",
    "board_progress_task", "board_assign_task", "board_create_task", "board_split_task",
    "board_update_task", "board_flag_task", "get_board_config", "set_board_config", "sync_board",
    "list_projects", "chat_post", "chat_get",
  ]) {
    assert.ok(names.includes(expected), `missing tool ${expected}`);
  }
});

test("GET /mcp without Accept: text/event-stream is 406 (not 405)", async () => {
  // The SDK transport (not the daemon) now handles method dispatch. A GET that
  // doesn't advertise text/event-stream gets a proper 406 — proving the request
  // reached the SDK instead of being 405'd at the method gate.
  const res = await fetch(`http://127.0.0.1:${port}/mcp`);
  assert.equal(res.status, 406);
});

test("GET /mcp with Accept: text/event-stream opens an SSE stream", async () => {
  // The Streamable HTTP GET handshake: the server MUST NOT 405 this. It opens
  // a long-lived text/event-stream the client can hold for server→client
  // notifications (the board server pushes none — it's a keep-alive).
  const res = await fetch(`http://127.0.0.1:${port}/mcp`, {
    headers: { Accept: "text/event-stream" },
  });
  assert.equal(res.status, 200);
  assert.match(res.headers.get("content-type") || "", /text\/event-stream/);
  // Don't hold the stream open — cancel to let the test proceed.
  await res.body?.cancel();
});

test("GET /mcp SSE stream emits an initial keep-alive byte (965da3ee)", async () => {
  // The SDK's stateless GET handler enqueues nothing, so a client that waits
  // for the first SSE byte (e.g. bundle-mcp, 30s connect timeout) hangs. The
  // daemon now serves GET itself and emits an immediate `: keepalive` SSE
  // comment so the first read resolves right away. Comment lines are ignored
  // by SSE parsers and are spec-endorsed as keep-alives.
  const res = await fetch(`http://127.0.0.1:${port}/mcp`, {
    headers: { Accept: "text/event-stream" },
  });
  assert.equal(res.status, 200);
  const reader = res.body.getReader();
  const first = await Promise.race([
    reader.read(),
    new Promise((_, reject) => setTimeout(() => reject(new Error("no initial byte within 3s")), 3000)),
  ]);
  assert.ok(first.value && first.value.length > 0, "stream sent an initial byte");
  const text = new TextDecoder().decode(first.value);
  assert.match(text, /^: keepalive/, `first chunk is a keep-alive comment: ${JSON.stringify(text)}`);
  await reader.cancel();
});

// ── board tool calls (in-process backend → live board state) ───────────────

test("board_list_tasks on an empty board", async () => {
  const r = await call("tools/call", { name: "board_list_tasks", arguments: {} });
  assert.equal(r.status, 200);
  assert.ok(!r.body.result.isError, "tool returned an error: " + (r.body.result.content?.[0]?.text ?? ""));
  const text = r.body.result.content[0].text;
  assert.match(text, /Board is empty/);
});

test("board_create_task then board_get_task round-trip", async () => {
  const create = await call("tools/call", {
    name: "board_create_task",
    arguments: { summary: "MCP test task", description: "created via the in-daemon MCP server" },
  });
  assert.equal(create.status, 200);
  assert.ok(!create.body.result.isError, "tool returned an error: " + (create.body.result.content?.[0]?.text ?? ""));
  const createText = create.body.result.content[0].text;
  const idMatch = createText.match(/\[([0-9a-f]{8})\]/);
  assert.ok(idMatch, `create result mentions an id: ${createText}`);
  const id = idMatch[1];

  const get = await call("tools/call", { name: "board_get_task", arguments: { taskId: id } });
  assert.equal(get.status, 200);
  const getText = get.body.result.content[0].text;
  assert.match(getText, /MCP test task/);
  assert.match(getText, /created via the in-daemon MCP server/);
});

test("board_move_task moves a task to 'To Do'", async () => {
  await call("tools/call", { name: "board_create_task", arguments: { summary: "move me" } });
  const list = await call("tools/call", { name: "board_list_tasks", arguments: {} });
  const id = list.body.result.content[0].text.match(/\[([0-9a-f]{8})\]/)[1];
  const r = await call("tools/call", { name: "board_move_task", arguments: { taskId: id, column: "To Do" } });
  assert.ok(!r.body.result.isError, "tool returned an error: " + (r.body.result.content?.[0]?.text ?? ""));
  assert.match(r.body.result.content[0].text, /Moved/);
});

test("board_flag_task sets the unclear flag", async () => {
  await call("tools/call", { name: "board_create_task", arguments: { summary: "flag me" } });
  const list = await call("tools/call", { name: "board_list_tasks", arguments: {} });
  const id = list.body.result.content[0].text.match(/\[([0-9a-f]{8})\]/)[1];
  const r = await call("tools/call", { name: "board_flag_task", arguments: { taskId: id, reason: "ambiguous scope" } });
  assert.ok(!r.body.result.isError, "tool returned an error: " + (r.body.result.content?.[0]?.text ?? ""));
  assert.match(r.body.result.content[0].text, /flagged unclear/);
});

// ── board_list_tasks location/archive filtering (task 6586b9ca) ────────────
// Archived tasks are hidden by default; includeArchived / location fetch them
// separately. Backlog is shown by default and via location:'backlog'.
// boardState (board.mjs) is the single source of truth for the filter; the MCP
// tool delegates via getBoard(opts). Markers persist across these tests (the
// board is shared); node:test runs them in definition order.
async function mkTask(args) {
  const r = await call("tools/call", { name: "board_create_task", arguments: args });
  assert.ok(!r.body.result.isError, "create failed: " + (r.body.result.content?.[0]?.text ?? ""));
  return r.body.result.content[0].text.match(/\[([0-9a-f]{8})\]/)[1];
}
const boardText = (r) => r.body.result.content[0].text;

test("board_list_tasks hides archived tasks by default (6586b9ca)", async () => {
  await mkTask({ summary: "onboard-6586-marker" });
  await mkTask({ summary: "backlog-6586-marker", backlog: true });
  const arId = await mkTask({ summary: "archiveme-6586-marker" });
  const mv = await call("tools/call", { name: "board_move_task", arguments: { taskId: arId, column: "archive" } });
  assert.match(boardText(mv), /Moved/);

  // Default: board + backlog shown, archive hidden.
  const def = boardText(await call("tools/call", { name: "board_list_tasks", arguments: {} }));
  assert.match(def, /onboard-6586-marker/);
  assert.match(def, /backlog-6586-marker/);
  assert.ok(!/archiveme-6586-marker/.test(def), "archive task must be hidden by default");
  assert.ok(!/▌ Archive/.test(def), "Archive section must not render by default");

  // includeArchived:true reveals the archive (board + backlog still shown).
  const inc = boardText(await call("tools/call", { name: "board_list_tasks", arguments: { includeArchived: true } }));
  assert.match(inc, /archiveme-6586-marker/);
  assert.match(inc, /▌ Archive/);
  assert.match(inc, /onboard-6586-marker/);
});

test("board_list_tasks location filter fetches each pool separately (6586b9ca)", async () => {
  const boardOnly = boardText(await call("tools/call", { name: "board_list_tasks", arguments: { location: "board" } }));
  assert.match(boardOnly, /onboard-6586-marker/);
  assert.ok(!/backlog-6586-marker/.test(boardOnly), "location:'board' must exclude backlog");
  assert.ok(!/▌ Backlog/.test(boardOnly), "location:'board' must not render the Backlog section");
  assert.ok(!/archiveme-6586-marker/.test(boardOnly), "location:'board' must exclude archive");

  const bkOnly = boardText(await call("tools/call", { name: "board_list_tasks", arguments: { location: "backlog" } }));
  assert.match(bkOnly, /backlog-6586-marker/);
  assert.match(bkOnly, /▌ Backlog/);
  assert.ok(!/onboard-6586-marker/.test(bkOnly), "location:'backlog' must exclude on-board tasks");
  assert.ok(!/archiveme-6586-marker/.test(bkOnly), "location:'backlog' must exclude archive");

  const arOnly = boardText(await call("tools/call", { name: "board_list_tasks", arguments: { location: "archive" } }));
  assert.match(arOnly, /archiveme-6586-marker/);
  assert.match(arOnly, /▌ Archive/);
  assert.ok(!/onboard-6586-marker/.test(arOnly), "location:'archive' must exclude on-board tasks");
  assert.ok(!/backlog-6586-marker/.test(arOnly), "location:'archive' must exclude backlog");
});

test("get_board_config returns column list", async () => {
  const r = await call("tools/call", { name: "get_board_config", arguments: {} });
  assert.ok(!r.body.result.isError, "tool returned an error: " + (r.body.result.content?.[0]?.text ?? ""));
  const cfg = JSON.parse(r.body.result.content[0].text);
  assert.ok(Array.isArray(cfg.columns) && cfg.columns.length > 0);
  assert.equal(cfg.config.apiTokenSet, false);
});

test("list_projects returns an empty project list in a fresh daemon", async () => {
  const r = await call("tools/call", { name: "list_projects", arguments: {} });
  assert.ok(!r.body.result.isError, "tool returned an error: " + (r.body.result.content?.[0]?.text ?? ""));
  const text = r.body.result.content[0].text;
  assert.match(text, /No project directories recorded yet/);
});

// ── board_get_task cross-group resolution (task 16a594db, Part B) ────────────
//
// loadTask() now fetches with group:"all" so a task is resolved by id across
// every project group regardless of the caller's default same-group scoping.
// The in-daemon MCP server runs as the human operator (who already sees all
// groups), so this test pins the cross-group get-by-id path end-to-end
// through the MCP server: a task stamped to a worker's project group is
// found by board_get_task via its id.

test("board_get_task resolves a cross-group task by id (loadTask group:'all', 16a594db)", async () => {
  // Register a socket worker in a project dir so board_create stamps that
  // group onto the task (a human/MCP-created task would have group null).
  const dir = fs.mkdtempSync(path.join(tmpHome, "mcp-xgrp-"));
  const worker = await mkSocketClient();
  await worker.request({ type: "register", agentId: crypto.randomUUID(), agentName: "mcp-xgrp-worker", cwd: dir });
  const cr = await worker.request({ type: "board_create", summary: "mcp cross-group probe", description: "stamped to the worker's group" });
  assert.notEqual(cr.type, "error", `board_create: ${JSON.stringify(cr)}`);
  const taskId = cr.task.id;
  assert.equal(cr.task.group, path.basename(dir), "task stamped with the worker's project group");

  // MCP board_get_task must find the group-stamped task by id (loadTask uses
  // group:"all" so the worker's own-group scoping is not applied to get-by-id).
  const r = await call("tools/call", { name: "board_get_task", arguments: { taskId } });
  assert.equal(r.status, 200);
  assert.ok(!r.body.result.isError, "tool returned an error: " + (r.body.result.content?.[0]?.text ?? ""));
  const text = r.body.result.content[0].text;
  assert.match(text, /mcp cross-group probe/);
  assert.match(text, /stamped to the worker's group/);

  // Cleanup: archive the task so it doesn't pollute the shared board.
  await worker.request({ type: "board_move", taskId, column: "archive" });
  worker.close();
});
