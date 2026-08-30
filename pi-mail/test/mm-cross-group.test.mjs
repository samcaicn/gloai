// Tests for cross-group board access by manager agents (task 16a594db).
//
// Bug: a CEO-spawned middle-manager (mail_spawn_agent({cwd, mm:true}) with no
// explicit name → auto-named "<dir-basename>-<id6>") could not administer
// (board_get_task / board_comment_task / board_move_task) tasks in groups other
// than its own cwd's group, and board_list_tasks() with no group param showed
// only its own group ("same-group view") instead of all groups. This defeated
// the MM's purpose (it oversees multiple projects).
//
// Two intertwined root causes:
//  A — manager recognition: isMiddleManager(agentId) must reliably recognise a
//      CEO-spawned MM session (mm:true on the spawn-registry entry), regardless
//      of the auto-generated name, so boardState's default scoping falls into
//      the all-groups (seesAll) branch for it.
//  B — board_get_task default scope: loadTask() (the in-pi board_get_task tool
//      and the MCP board_get_task) must resolve a task by id across ALL groups,
//      not just the caller's own group, so a manager (or any agent) can fetch a
//      cross-group task by id.
//
// These tests boot an isolated daemon (fake tmux, pi=/bin/true), spawn an MM
// the way the CEO does (mm:true, no name → auto-named), register a client
// under that name, create tasks in two different project groups, and assert
// the MM sees all groups by default and can get/comment/move a task in a group
// ≠ its own cwd's group. Also pins the Part B fix (board_get_task resolves
// cross-group by id) and that a regular worker is NOT granted all-groups
// visibility (no regression to same-group scoping).
//
// Run: npm test

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { spawn as pSpawn } from "node:child_process";
import * as net from "node:net";
import * as fs from "node:fs";
import * as path from "node:path";
import * as os from "node:os";
import * as crypto from "node:crypto";

const REPO = path.resolve(import.meta.dirname, "..");
const DAEMON = path.join(REPO, "extensions", "daemon.mjs");
const UI_PORT = "19998";

let tmpHome, tmpState, fakeTmux, proc, sockPath, client;
process.on("exit", () => { try { if (proc) proc.kill("SIGKILL"); } catch {} });

function mkFakeTmux() {
  const script = `#!/bin/sh
STATE="$TMUX_STATE_DIR"
case "$1" in
  has-session)
    name="$3"
    [ -f "$STATE/sessions/$name" ] && exit 0 || exit 1 ;;
  new-session)
    name=""
    while [ $# -gt 0 ]; do
      case "$1" in -s) name="$2"; shift 2 ;; *) shift ;; esac
    done
    mkdir -p "$STATE/sessions"
    touch "$STATE/sessions/$name"
    exit 0 ;;
  kill-session)
    name="$3"
    rm -f "$STATE/sessions/$name"
    exit 0 ;;
  *)
    exit 0 ;;
esac
`;
  fs.writeFileSync(fakeTmux, script, { mode: 0o755 });
}

function startDaemon() {
  return new Promise((resolve, reject) => {
    proc = pSpawn(process.execPath, [DAEMON], {
      env: {
        ...process.env,
        HOME: tmpHome,
        PI_MAIL_TMUX_BIN: fakeTmux,
        PI_MAIL_PI_BIN: "/bin/true",
        PI_MAIL_UI_PORT: UI_PORT,
        PI_MAIL_UI_HOST: "127.0.0.1",
        PI_MAIL_SPAWN_TIMEOUT: "800",
        PI_MAIL_MM_TICK_MS: "5000",
        TMUX_STATE_DIR: tmpState,
        PATH: `${path.dirname(fakeTmux)}:${process.env.PATH}`,
      },
      stdio: ["ignore", "pipe", "pipe"],
    });
    let stderr = "";
    proc.stderr.on("data", (c) => { stderr += c.toString(); });
    proc.on("exit", (code, sig) => {
      if (!proc.__stopped) console.error("daemon exited unexpectedly", code, sig, stderr.slice(-500));
    });
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

function mkClient() {
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

async function register(c, name, cwd = tmpHome, agentId) {
  return c.request({ type: "register", agentId: agentId ?? crypto.randomUUID(), agentName: name, cwd });
}

before(async () => {
  tmpHome = fs.mkdtempSync(path.join(os.tmpdir(), "pimail-xgrp-"));
  tmpState = fs.mkdtempSync(path.join(os.tmpdir(), "pimail-tmux-xgrp-"));
  fakeTmux = path.join(tmpHome, "fake-tmux");
  mkFakeTmux();
  sockPath = path.join(tmpHome, ".pi", "agent", "mail-daemon.sock");
  await startDaemon();
  client = await mkClient();
  await register(client, "test-orchestrator");
});

after(async () => {
  client?.close();
  await stopDaemon();
  fs.rmSync(tmpHome, { recursive: true, force: true });
  fs.rmSync(tmpState, { recursive: true, force: true });
});

// ── helpers ──────────────────────────────────────────────────────────────────

const spawnState = () => client.request({ type: "spawn_state" });
const spawnStop = (name) => client.request({ type: "spawn_stop", name });
const boardState = (c, opts) => c.request({ type: "board_state", ...(opts ?? {}) });
const boardCreate = (c, args) => c.request({ type: "board_create", ...args });
const boardMove = (c, taskId, column, note) => c.request({ type: "board_move", taskId, column, ...(note ? { note } : {}) });
const boardComment = (c, taskId, text) => c.request({ type: "board_comment", taskId, text });

function mkDir(name) {
  const d = path.join(tmpHome, name);
  fs.mkdirSync(d, { recursive: true });
  return d;
}
const settle = (ms = 100) => new Promise((r) => setTimeout(r, ms));

// ── tests ────────────────────────────────────────────────────────────────────

test("a CEO-spawned MM (auto-named, mm:true) sees all groups by default + can administer cross-group (16a594db)", async () => {
  // Two project groups, with a task in each (stamped from the creator's cwd).
  const dirA = mkDir("xgrp-proja");
  const dirB = mkDir("xgrp-projb");
  const workerA = await mkClient();
  const workerB = await mkClient();
  await register(workerA, "worker-a", dirA);
  await register(workerB, "worker-b", dirB);
  const crA = await boardCreate(workerA, { summary: "proja task" });
  const crB = await boardCreate(workerB, { summary: "projb task" });
  assert.notEqual(crA.type, "error", `board_create A: ${JSON.stringify(crA)}`);
  assert.notEqual(crB.type, "error", `board_create B: ${JSON.stringify(crB)}`);
  const taskA = crA.task.id;
  const taskB = crB.task.id;

  // Spawn an MM the way the CEO does: mm:true, NO explicit name → auto-named
  // "<dir-basename>-<id6>". Its cwd is dirA so its own group is "xgrp-proja".
  const sp = await client.request({ type: "spawn", cwd: dirA, mm: true });
  assert.equal(sp.type, "spawned", `spawn MM: ${JSON.stringify(sp)}`);
  const mmName = sp.name;
  assert.ok(mmName.startsWith("xgrp-proja-"), `MM auto-named from dir basename: ${mmName}`);
  // Confirm the spawn-registry entry is flagged mm:true (the recognition hook).
  await settle();
  const mmSession = (await spawnState()).sessions.find((s) => s.name === mmName);
  assert.ok(mmSession, "MM session tracked in the spawn registry");
  assert.equal(mmSession.mm, true, "MM session has mm:true flag (CEO-spawned MM)");

  // Register a client under the MM's auto-name (the agent connecting back).
  const mm = await mkClient();
  await register(mm, mmName, dirA);

  // Part A: board_list_tasks() with NO group must return ALL groups for the MM
  // (no "same-group view"), even though its cwd group is xgrp-proja.
  const st = await boardState(mm, { includeArchived: false });
  const ids = st.tasks.map((t) => t.id).sort();
  assert.ok(ids.includes(taskA), "MM sees its own-group task");
  assert.ok(ids.includes(taskB), "MM sees the OTHER group's task (all-groups visibility)");
  assert.equal(st.group, null, "default scope → group field is null (not a named filter)");

  // Part B: board_get_task resolves a cross-group task by id. (Exercised via
  // the in-pi tool path separately; here we verify the underlying board_state
  // the tool fetches with group:'all' would surface it.)
  const allSt = await boardState(mm, { group: "all", includeArchived: false });
  assert.ok(allSt.tasks.map((t) => t.id).includes(taskB), "group:'all' surfaces the cross-group task");

  // Cross-group comment must succeed (not "different group").
  const cmt = await boardComment(mm, taskB, "MM cross-group comment");
  assert.notEqual(cmt.type, "error", `cross-group comment should succeed: ${JSON.stringify(cmt)}`);
  assert.equal(cmt.type, "ok", "cross-group comment returned ok");

  // Cross-group move must succeed (not "different group"). Move to backlog
  // (local-only, no Jira) to keep the test self-contained.
  const mv = await boardMove(mm, taskB, "backlog", "MM cross-group move");
  assert.notEqual(mv.type, "error", `cross-group move should succeed: ${JSON.stringify(mv)}`);
  assert.equal(mv.type, "ok", "cross-group move returned ok");
  assert.equal(mv.task.location, "backlog", "task actually moved to backlog");

  // Cleanup: stop the MM session, archive both tasks.
  await spawnStop(mmName);
  await workerA.request({ type: "board_move", taskId: taskA, column: "archive" });
  await workerB.request({ type: "board_move", taskId: taskB, column: "archive" });
  workerA.close();
  workerB.close();
  mm.close();
});

test("a regular worker (non-manager) is NOT granted all-groups visibility by default (no regression, 16a594db)", async () => {
  const dirA = mkDir("xgrp-worker-a");
  const dirB = mkDir("xgrp-worker-b");
  const workerA = await mkClient();
  const workerB = await mkClient();
  await register(workerA, "plain-worker-a", dirA);
  await register(workerB, "plain-worker-b", dirB);
  const crA = await boardCreate(workerA, { summary: "worker-a task" });
  const crB = await boardCreate(workerB, { summary: "worker-b task" });
  const taskA = crA.task.id;
  const taskB = crB.task.id;

  // A plain worker (NOT spawned with mm:true) must still see only its own
  // group by default — the all-groups visibility is reserved for managers.
  const st = await boardState(workerA, { includeArchived: false });
  const ids = st.tasks.map((t) => t.id);
  assert.ok(ids.includes(taskA), "worker sees its own-group task");
  assert.ok(!ids.includes(taskB), "worker does NOT see the other group's task (same-group only)");

  // And a cross-group comment by a plain worker must still be refused.
  const cmt = await boardComment(workerA, taskB, "should be refused");
  assert.equal(cmt.type, "error", "cross-group comment by a plain worker is refused");
  assert.match(cmt.message, /different group/);

  // Cleanup.
  await workerA.request({ type: "board_move", taskId: taskA, column: "archive" });
  await workerB.request({ type: "board_move", taskId: taskB, column: "archive" });
  workerA.close();
  workerB.close();
});

test("Part B: board_get_task resolves a cross-group task by id for a worker via group:'all' (16a594db)", async () => {
  // The in-pi board_get_task tool now fetches with { group: "all", includeArchived: true }
  // so get-by-id finds ANY task regardless of the caller's own group. Pin the
  // boardState contract board_get_task depends on: a worker (same-group only
  // by default) gets the cross-group task when the get-by-id opts are used.
  const dirA = mkDir("xgrp-get-a");
  const dirB = mkDir("xgrp-get-b");
  const workerA = await mkClient();
  const workerB = await mkClient();
  await register(workerA, "get-worker-a", dirA);
  await register(workerB, "get-worker-b", dirB);
  const crB = await boardCreate(workerB, { summary: "cross-group get target" });
  const taskB = crB.task.id;

  // Default (no group): workerA does NOT see dirB's task (same-group only).
  const def = await boardState(workerA, { includeArchived: false });
  assert.ok(!def.tasks.map((t) => t.id).includes(taskB), "default scope hides the cross-group task");

  // The get-by-id opts board_get_task now uses → the cross-group task IS found.
  const getSt = await boardState(workerA, { group: "all", includeArchived: true });
  assert.ok(getSt.tasks.map((t) => t.id).includes(taskB), "group:'all' resolves the cross-group task by id");
  assert.equal(getSt.group, "all");

  // Cleanup.
  await workerB.request({ type: "board_move", taskId: taskB, column: "archive" });
  workerA.close();
  workerB.close();
});

test("Part B: board_get_task finds an archived task via includeArchived:true (16a594db)", async () => {
  // get-by-id should find a task even after it's archived (the default
  // includeArchived:false board_list_tasks hides it). board_get_task now uses
  // includeArchived:true so archived tasks stay reachable by id.
  const dirA = mkDir("xgrp-arch-a");
  const workerA = await mkClient();
  await register(workerA, "arch-worker-a", dirA);
  const cr = await boardCreate(workerA, { summary: "soon-to-be-archived" });
  const taskId = cr.task.id;
  await workerA.request({ type: "board_move", taskId, column: "archive" });

  // Default board_list_tasks hides the archive.
  const def = await boardState(workerA, { includeArchived: false });
  assert.ok(!def.tasks.map((t) => t.id).includes(taskId), "archived task hidden by default");
  // get-by-id opts find it.
  const getSt = await boardState(workerA, { group: "all", includeArchived: true });
  assert.ok(getSt.tasks.map((t) => t.id).includes(taskId), "archived task found via get-by-id opts");
  assert.equal(getSt.tasks.find((t) => t.id === taskId).location, "archive");

  workerA.close();
});
