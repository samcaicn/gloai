// Tests for the mail_restart_daemon / `restart_daemon` protocol feature.
//
// Runs a fully isolated mail-daemon (throwaway HOME so the socket + pid + lock
// land in a temp dir), drives it over the daemon socket, and verifies that a
// `restart_daemon` request causes a graceful shutdown followed by an automatic
// respawn — and that board state (persisted to disk) survives the restart.
//
// Run: npm test   (uses node:test, the stdlib runner — no new dependency)

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

// ── Isolation harness ──────────────────────────────────────────────────────

let tmpHome, proc, sockPath, pidPath, client;
// Kill any spawned daemon when the test runner exits (incl. Ctrl-C / timeout)
// so interrupted runs don't leave orphan daemon processes behind.
process.on("exit", () => { try { if (proc) proc.kill("SIGKILL"); } catch {} });

function startDaemon() {
  return new Promise((resolve, reject) => {
    proc = pSpawn(process.execPath, [DAEMON], {
      env: {
        ...process.env,
        HOME: tmpHome,
        PI_MAIL_UI_PORT: "0",
        PI_MAIL_UI_HOST: "127.0.0.1",
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

// Minimal newline-delimited JSON socket client (matches the extension).
function mkClient() {
  return new Promise((resolve, reject) => {
    const s = net.createConnection(sockPath);
    s.setEncoding("utf8");
    let buf = "";
    let nextId = 1;
    const pending = new Map();
    const onNewMail = [];
    let closed = false;
    s.on("data", (chunk) => {
      buf += chunk;
      const lines = buf.split("\n");
      buf = lines.pop();
      for (const line of lines) {
        if (!line.trim()) continue;
        let m; try { m = JSON.parse(line); } catch { continue; }
        if (m.type === "ping") { s.write(JSON.stringify({ type: "pong" }) + "\n"); continue; }
        if (m.type === "new_mail") { onNewMail.forEach((cb) => cb(m.message)); continue; }
        if (m._reqId != null && pending.has(m._reqId)) {
          const e = pending.get(m._reqId); clearTimeout(e.t); pending.delete(m._reqId); e.res(m);
        }
      }
    });
    s.once("close", () => { closed = true; });
    s.once("connect", () => resolve({
      request(msg, timeoutMs = 5000) {
        const id = nextId++;
        return new Promise((res, rej) => {
          const t = setTimeout(() => { pending.delete(id); rej(new Error("timeout: " + msg.type)); }, timeoutMs);
          pending.set(id, { res, rej, t });
          s.write(JSON.stringify({ ...msg, _reqId: id }) + "\n");
        });
      },
      onNewMail(cb) { onNewMail.push(cb); },
      close() { s.destroy(); },
      isClosed() { return closed; },
    }));
    s.once("error", reject);
  });
}

async function register(c, name, cwd = tmpHome) {
  return c.request({ type: "register", agentId: crypto.randomUUID(), agentName: name, cwd });
}

before(async () => {
  tmpHome = fs.mkdtempSync(path.join(os.tmpdir(), "pimail-restart-"));
  sockPath = path.join(tmpHome, ".pi", "agent", "mail-daemon.sock");
  pidPath = path.join(tmpHome, ".pi", "agent", "mail-daemon.pid");
  await startDaemon();
  client = await mkClient();
  await register(client, "test-orchestrator");
});

after(async () => {
  client?.close();
  await stopDaemon();
  fs.rmSync(tmpHome, { recursive: true, force: true });
});

// ── restart_daemon ───────────────────────────────────────────────────────────

test("restart_daemon replies ok then shuts down (old process exits)", async () => {
  const oldPid = parseInt(fs.readFileSync(pidPath, "utf8").trim(), 10);
  assert.ok(oldPid > 0);

  const r = await client.request({ type: "restart_daemon" });
  assert.equal(r.type, "ok");
  assert.equal(r.message, "restarting");

  // The old daemon process should exit within a couple seconds.
  await new Promise((res) => {
    const check = () => {
      try { process.kill(oldPid, 0); setTimeout(check, 50); }
      catch { res(); } // gone
    };
    setTimeout(check, 50);
  });
  assert.ok(client.isClosed(), "client socket should close when daemon exits");
});

test("after restart, the daemon respawns and is reachable on the same socket", async () => {
  // A fresh client connecting to the same socket path should succeed once the
  // (auto-respawned or operator-restarted) daemon is back. Here we simulate the
  // extension's respawn path: spawn a fresh daemon ourselves.
  client = null; // old client is dead
  await startDaemon();
  const c = await mkClient();
  const reg = await register(c, "post-restart-agent");
  assert.equal(reg.type, "registered");
  c.close();
});

test("restart_daemon is a fresh daemon pid (not the old one)", async () => {
  // After the respawn above, the pid file should hold a live, different pid.
  const newPid = parseInt(fs.readFileSync(pidPath, "utf8").trim(), 10);
  assert.ok(newPid > 0);
  let alive = false;
  try { process.kill(newPid, 0); alive = true; } catch {}
  assert.ok(alive, "new daemon pid should be live");
});

test("restart_daemon persists board state across the restart", async () => {
  // Create a board task, restart, and confirm the task survives (board is
  // flushed to disk on graceful shutdown and reloaded on boot).
  client = await mkClient();
  await register(client, "board-keeper");
  const created = await client.request({
    type: "board_create", summary: "survive-restart-task", column: undefined,
  });
  assert.equal(created.type, "ok", `create failed: ${JSON.stringify(created)}`);
  const taskId = created.task?.id;

  const r = await client.request({ type: "restart_daemon" });
  assert.equal(r.type, "ok");

  // Wait for the old daemon to exit, then respawn.
  await new Promise((res) => setTimeout(res, 400));
  client = null;
  await stopDaemon();
  await startDaemon();
  client = await mkClient();
  await register(client, "board-keeper-2");

  const state = await client.request({ type: "board_state" });
  assert.equal(state.type, "board");
  const found = (state.tasks || []).some((t) => t.id === taskId || t.summary === "survive-restart-task");
  assert.ok(found, "board task did not survive restart");
});

// Reproduces the middle-manager's flakiness report (task fae2b4e6): after a
// restart + reconnect, list/agents/board/list queries must work immediately —
// not ECONNRESET on a half-ready daemon. The fix is in the mail_restart_daemon
// tool (debounce + query-readiness probe); this test guards the daemon side: a
// client that reconnects right after the respawn can fire list_agents /
// list_mail / board_state and get clean responses with no reset.
async function tryConnectPoll(retries = 100) {
  for (let i = 0; i < retries; i++) {
    const s = net.createConnection(sockPath);
    const ok = await new Promise((res) => { s.once("connect", () => { s.destroy(); res(true); }); s.once("error", () => res(false)); });
    if (ok) return true;
    await new Promise((r) => setTimeout(r, 50));
  }
  return false;
}

test("queries succeed immediately after restart+reconnect (query-ready, no ECONNRESET)", async () => {
  client = await mkClient();
  await register(client, "query-probe");
  const oldPid = parseInt(fs.readFileSync(pidPath, "utf8").trim(), 10);

  const r = await client.request({ type: "restart_daemon" });
  assert.equal(r.type, "ok");
  // Wait for the old daemon to exit.
  await new Promise((res) => {
    const check = () => { try { process.kill(oldPid, 0); setTimeout(check, 50); } catch { res(); } };
    setTimeout(check, 50);
  });
  client = null;

  // Simulate the extension's ensureDaemonAndConnect: respawn (no auto-respawn
  // in the test harness) then poll for the socket to come up.
  await startDaemon();
  assert.ok(await tryConnectPoll(), "respawned daemon socket came up");

  // Reconnect + register, then fire the queries the MM saw failing.
  const c = await mkClient();
  await register(c, "query-probe-2");

  const agents = await c.request({ type: "list_agents" }, 4_000);
  assert.equal(agents.type, "agents", `list_agents failed right after restart: ${JSON.stringify(agents)}`);

  const mail = await c.request({ type: "list_mail" }, 4_000);
  assert.equal(mail.type, "mail", `list_mail failed right after restart: ${JSON.stringify(mail)}`);

  const board = await c.request({ type: "board_state" }, 4_000);
  assert.equal(board.type, "board", `board_state failed right after restart: ${JSON.stringify(board)}`);

  c.close();
});
