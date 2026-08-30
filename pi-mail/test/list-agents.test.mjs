// Regression test for the socket `list_agents` handler.
//
// Background: the HTTP/UI extraction moved `federationState()` into
// lib/http.mjs (local, not exported), but the socket protocol handler in
// lib/protocol.mjs still called `federationState().agents` for `list_agents`
// without importing it. That threw a synchronous ReferenceError inside the
// daemon's socket data handler — an uncaught exception that CRASHED the
// daemon on every `mail_list_agents` call. Agents saw the socket drop
// ("disconnected" / ECONNRESET), reconnected (respawning the daemon), and the
// next `list_agents` crashed it again — a crash-loop. The middle-manager
// (whose pass starts with `mail_list_agents`) was completely blocked by this.
//
// This test pins the fix: `list_agents` returns an `agents` array (including
// the human virtual agent) AND the daemon process stays alive (same PID)
// afterwards. If the handler ever throws again, the daemon would die and the
// PID would change (or the request would hang/timeout).
//
// Run: npm test   (node:test stdlib runner — no new dependency)

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

// ── Isolation harness (throwaway HOME so socket/pid/lock land in a temp dir)
let tmpHome, proc, sockPath, pidPath, client;
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

function readPid() {
  try { return parseInt(fs.readFileSync(pidPath, "utf8").trim(), 10); } catch { return null; }
}

// Minimal newline-delimited JSON socket client (matches the extension).
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

before(async () => {
  tmpHome = fs.mkdtempSync(path.join(os.tmpdir(), "pimail-listagents-"));
  sockPath = path.join(tmpHome, ".pi", "agent", "mail-daemon.sock");
  pidPath = path.join(tmpHome, ".pi", "agent", "mail-daemon.pid");
  await startDaemon();
  client = await mkClient();
  await client.request({
    type: "register",
    agentId: crypto.randomUUID(),
    agentName: "test-orchestrator",
    cwd: tmpHome,
  });
});

after(async () => {
  client?.close();
  await stopDaemon();
  fs.rmSync(tmpHome, { recursive: true, force: true });
});

// ── Regression ──────────────────────────────────────────────────────────────

test("list_agents returns an agents list including the human (does not crash the daemon)", async () => {
  const pidBefore = readPid();

  const r = await client.request({ type: "list_agents" });
  assert.equal(r.type, "agents");
  assert.ok(Array.isArray(r.agents));
  // The human virtual agent must be discoverable so agents can reply to it.
  const human = r.agents.find((a) => a.isHuman);
  assert.ok(human, "human virtual agent is present");
  assert.equal(human.agentName, "human");
  // The caller itself is present.
  assert.ok(r.agents.some((a) => a.agentName === "test-orchestrator"));
});

test("list_agents does not kill the daemon (same pid after the call)", async () => {
  const pidBefore = readPid();
  assert.ok(pidBefore, "daemon had a pid before");
  await client.request({ type: "list_agents" });
  // Give a beat for any delayed crash to land.
  await new Promise((res) => setTimeout(res, 150));
  const pidAfter = readPid();
  assert.equal(pidAfter, pidBefore, "daemon pid changed — list_agents crashed the daemon (regression)");
  assert.ok(proc && !proc.killed, "daemon process is still alive");
});

test("list_agents is repeatable (no crash-loop)", async () => {
  const pidBefore = readPid();
  for (let i = 0; i < 3; i++) {
    const r = await client.request({ type: "list_agents" });
    assert.equal(r.type, "agents");
  }
  await new Promise((res) => setTimeout(res, 150));
  assert.equal(readPid(), pidBefore, "daemon pid changed after repeated list_agents (crash-loop regression)");
});
