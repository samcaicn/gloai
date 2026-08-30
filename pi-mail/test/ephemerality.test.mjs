// Ephemerality tests (task 84e497c2): the reaper force-kills over-lifetime
// sessions of ALL three tiers (CEO, MM, worker), leaves no leaked sessions
// after a full CEO→MM→worker cycle where nobody self-exits, and cleans up the
// cascade when a CEO is reaped mid-pass.
//
// Same isolated harness as ceo/middle-manager tests: throwaway HOME, fake tmux
// (state files in a temp dir), pi=/bin/true, short spawn-register timeout. The
// three tiers are spawned directly via the `spawn` RPC with ceo:true / mm:true
// / (plain) flags so we get registry entries + fake tmux sessions WITHOUT real
// agents (pi=/bin/true never registers, so nobody calls mail_stop_self — the
// "none self-exit" scenario). Time is driven with the fake `now` param to
// mm_tick (reaps workers + MMs) and ceo_tick (reaps CEOs). Lifetimes are set
// small via the HTTP board-config endpoint.
//
// Liveness signal note (documented in lib/middle-manager.mjs): the reaper uses
// `tmuxSessionExists` (the tmux session / process is alive), NOT agent
// responsiveness — so an "alive but stuck" worker (long turn, not calling
// mail_stop_self) is caught on the over-lifetime branch at its max-lifetime
// boundary. The "hung worker" test keeps the fake tmux session file present
// (= alive) right up to the reap and asserts it's still reaped.
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

async function register(c, name, cwd = tmpHome) {
  return c.request({ type: "register", agentId: crypto.randomUUID(), agentName: name, cwd });
}

before(async () => {
  tmpHome = fs.mkdtempSync(path.join(os.tmpdir(), "pimail-eph-"));
  tmpState = fs.mkdtempSync(path.join(os.tmpdir(), "pimail-tmux-eph-"));
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

async function postCfg(body) {
  const r = await fetch(`http://127.0.0.1:${UI_PORT}/api/board/config`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  return r.json();
}
const spawnState = () => client.request({ type: "spawn_state" });
const spawnStop = (name) => client.request({ type: "spawn_stop", name });
const mmTick = (now, force) => client.request({ type: "mm_tick", ...(now != null ? { now } : {}), ...(force ? { force: true } : {}) });
const ceoTick = (now, force) => client.request({ type: "ceo_tick", ...(now != null ? { now } : {}), ...(force ? { force: true } : {}) });
const mmState = () => client.request({ type: "mm_state" });

/** Spawn a daemon-spawned session of a given tier. `tier` = "ceo" | "mm" | "worker". */
async function spawnTier(tier, dir, name) {
  const flags = tier === "ceo" ? { ceo: true } : tier === "mm" ? { mm: true } : {};
  const r = await client.request({ type: "spawn", cwd: dir, name, ...flags });
  assert.equal(r.type, "spawned", `spawn ${tier} '${name}' ok: ${JSON.stringify(r)}`);
  return r.name;
}

function mkDir(name) {
  const d = path.join(tmpHome, name);
  fs.mkdirSync(d, { recursive: true });
  return d;
}
const settle = (ms = 100) => new Promise((r) => setTimeout(r, ms));
const tmuxAlive = (name) => fs.existsSync(path.join(tmpState, "sessions", name));

/** Reset all scheduler config to disabled + default lifetimes. */
async function resetCfg() {
  await postCfg({ config: { mmEnabled: false, ceoEnabled: false, mmMaxLifetimeMin: 15, workerMaxLifetimeMin: 30, ceoMaxLifetimeMin: 15 } });
}

// ── tests ────────────────────────────────────────────────────────────────────

test("reaper forcibly stops an over-lifetime WORKER (third tier)", async () => {
  const dir = mkDir("proj-worker-life");
  await postCfg({ config: { workerMaxLifetimeMin: 1 } });
  const t0 = Date.now();
  const name = await spawnTier("worker", dir, `w-life-${crypto.randomUUID().slice(0, 6)}`);
  await settle();
  assert.ok((await spawnState()).sessions.find((s) => s.name === name), "worker tracked");
  // 2 min after spawn — past the 1-min worker max lifetime → reaped by mm_tick.
  await mmTick(t0 + 2 * 60_000);
  assert.equal((await spawnState()).sessions.find((s) => s.name === name), undefined, "over-lifetime worker reaped");
  assert.equal(tmuxAlive(name), false, "worker's tmux session was killed");
  await resetCfg();
});

test("reaper forcibly stops an over-lifetime MM (second tier)", async () => {
  const dir = mkDir("proj-mm-life");
  await postCfg({ config: { mmEnabled: false, mmMaxLifetimeMin: 1 } });
  const t0 = Date.now();
  const name = await spawnTier("mm", dir, `mm-life-${crypto.randomUUID().slice(0, 6)}`);
  await settle();
  assert.ok((await spawnState()).sessions.find((s) => s.name === name), "MM tracked");
  await mmTick(t0 + 2 * 60_000);
  assert.equal((await spawnState()).sessions.find((s) => s.name === name), undefined, "over-lifetime MM reaped");
  assert.equal(tmuxAlive(name), false, "MM's tmux session was killed");
  await resetCfg();
});

test("reaper forcibly stops an over-lifetime CEO (first tier)", async () => {
  const dir = mkDir("proj-ceo-life");
  await postCfg({ config: { ceoEnabled: false, ceoMaxLifetimeMin: 1 } });
  const t0 = Date.now();
  const name = await spawnTier("ceo", dir, `ceo-life-${crypto.randomUUID().slice(0, 6)}`);
  await settle();
  assert.ok((await spawnState()).sessions.find((s) => s.name === name), "CEO tracked");
  await ceoTick(t0 + 2 * 60_000);
  assert.equal((await spawnState()).sessions.find((s) => s.name === name), undefined, "over-lifetime CEO reaped");
  assert.equal(tmuxAlive(name), false, "CEO's tmux session was killed");
  await resetCfg();
});

test("hung WORKER (alive but stuck, never calls mail_stop_self) is reaped at its lifetime boundary", async () => {
  const dir = mkDir("proj-worker-hung");
  await postCfg({ config: { workerMaxLifetimeMin: 1 } });
  const t0 = Date.now();
  const name = await spawnTier("worker", dir, `w-hung-${crypto.randomUUID().slice(0, 6)}`);
  await settle();
  // The worker is "alive but stuck": its tmux session (process) is alive, but
  // it never calls mail_stop_self. The reaper's liveness signal is the tmux
  // session being alive, so this hits the over-lifetime branch, not the
  // dead-session branch.
  assert.equal(tmuxAlive(name), true, "worker tmux session is alive (= process alive) right before reap");
  assert.equal((await spawnState()).sessions.find((s) => s.name === name).alive, true, "daemon sees it as alive");
  await mmTick(t0 + 2 * 60_000);
  assert.equal((await spawnState()).sessions.find((s) => s.name === name), undefined, "alive-but-stuck worker force-killed at its lifetime boundary");
  assert.equal(tmuxAlive(name), false, "hung worker's tmux session killed");
  await resetCfg();
});

test("no leaked sessions: a full CEO→MM→worker cycle where NONE self-exit leaves the registry + tmux clean", async () => {
  const dir = mkDir("proj-cycle");
  // Short, distinct lifetimes so a single late tick reaps all three.
  await postCfg({ config: { workerMaxLifetimeMin: 1, mmMaxLifetimeMin: 1, ceoMaxLifetimeMin: 1 } });
  const t0 = Date.now();
  const ceoName = await spawnTier("ceo", dir, `c-cycle-${crypto.randomUUID().slice(0, 6)}`);
  const mmName = await spawnTier("mm", dir, `m-cycle-${crypto.randomUUID().slice(0, 6)}`);
  const wName = await spawnTier("worker", dir, `w-cycle-${crypto.randomUUID().slice(0, 6)}`);
  await settle();
  assert.equal((await spawnState()).sessions.length, 3, "all three tiers tracked before reaping");
  // Advance past every tier's lifetime and run both reapers (mm_tick reaps
  // workers + MMs; ceo_tick reaps CEOs). Nobody self-exits.
  await mmTick(t0 + 2 * 60_000);
  await ceoTick(t0 + 2 * 60_000);
  const remaining = (await spawnState()).sessions;
  assert.equal(remaining.length, 0, "no leaked sessions — all three tiers reaped, registry empty");
  // And no orphan fake-tmux sessions either.
  for (const n of [ceoName, mmName, wName]) {
    assert.equal(tmuxAlive(n), false, `no orphan tmux session for ${n}`);
  }
  await resetCfg();
});

test("cascade cleanup: reaping a CEO mid-pass leaves no orphan MM/worker (independent per-tier reap)", async () => {
  const dir = mkDir("proj-cascade");
  // Distinct lifetimes so the tiers are reaped at different times, exercising
  // the independent-per-tier cascade design: the CEO (longest-lived) is reaped
  // last / mid-pass, by which point the MM and worker it spawned were already
  // reaped on their OWN lifetimes — no parent/child tracking needed, no orphans.
  await postCfg({ config: { workerMaxLifetimeMin: 2, mmMaxLifetimeMin: 3, ceoMaxLifetimeMin: 4 } });
  const t0 = Date.now();
  const ceoName = await spawnTier("ceo", dir, `c-casc-${crypto.randomUUID().slice(0, 6)}`);
  const mmName = await spawnTier("mm", dir, `m-casc-${crypto.randomUUID().slice(0, 6)}`);
  const wName = await spawnTier("worker", dir, `w-casc-${crypto.randomUUID().slice(0, 6)}`);
  await settle();
  const live = async () => (await spawnState()).sessions.map((s) => s.name);

  // 2.5 min: worker over its 2-min lifetime → reaped. MM (2.5<3) + CEO (2.5<4) alive.
  await mmTick(t0 + 2.5 * 60_000);
  await ceoTick(t0 + 2.5 * 60_000);
  {
    const s = await live();
    assert.ok(!s.includes(wName), "worker reaped on its own lifetime (2 min)");
    assert.ok(s.includes(mmName), "MM still alive at 2.5 min");
    assert.ok(s.includes(ceoName), "CEO still alive at 2.5 min");
  }

  // 3.5 min: MM over its 3-min lifetime → reaped. CEO (3.5<4) still alive.
  await mmTick(t0 + 3.5 * 60_000);
  await ceoTick(t0 + 3.5 * 60_000);
  {
    const s = await live();
    assert.ok(!s.includes(mmName), "MM reaped on its own lifetime (3 min) — not orphaned when CEO still alive");
    assert.ok(s.includes(ceoName), "CEO still alive at 3.5 min");
  }

  // 4.5 min: CEO over its 4-min lifetime → reaped mid-pass. All tiers now gone;
  // the MM and worker were already reaped on their own lifetimes — no orphans.
  await mmTick(t0 + 4.5 * 60_000);
  await ceoTick(t0 + 4.5 * 60_000);
  {
    const s = await live();
    assert.ok(!s.includes(ceoName), "CEO reaped mid-pass at its 4-min lifetime");
    assert.equal(s.length, 0, "registry clean — no orphan MM/worker left behind");
  }
  for (const n of [ceoName, mmName, wName]) {
    assert.equal(tmuxAlive(n), false, `no orphan tmux session for ${n}`);
  }
  await resetCfg();
});

test("stop_self and a concurrent reaper leave no partial state (registry entry + tmux both gone)", async () => {
  const dir = mkDir("proj-stopself-reaper");
  await postCfg({ config: { workerMaxLifetimeMin: 30 } });
  // Spawn a plain worker and register a client under its name so stop_self
  // resolves the session by agentName.
  const name = `w-ss-${crypto.randomUUID().slice(0, 6)}`;
  const sp = await client.request({ type: "spawn", cwd: dir, name });
  assert.equal(sp.type, "spawned");
  const workerClient = await mkClient();
  const workerAgentId = crypto.randomUUID();
  await workerClient.request({ type: "register", agentId: workerAgentId, agentName: name, cwd: dir });
  await settle();
  assert.ok((await spawnState()).sessions.find((s) => s.name === name), "worker tracked");
  // Call stop_self (deletes registry entry immediately; tmux kill after grace).
  const r = await workerClient.request({ type: "stop_self" });
  assert.notEqual(r.type, "error", `stop_self ok: ${JSON.stringify(r)}`);
  // Before the grace kill fires, a reaper tick runs. It must NOT find the
  // registry entry (already deleted by stop_self) → no error, no double work.
  await mmTick();
  // After the grace, the tmux session is gone too.
  await settle(3500);
  assert.equal((await spawnState()).sessions.find((s) => s.name === name), undefined, "registry entry gone (no partial state)");
  assert.equal(tmuxAlive(name), false, "tmux session gone (no partial state)");
  workerClient.close();
  await resetCfg();
});

test("workerMaxLifetimeMin is configurable + reported in mm_state", async () => {
  await postCfg({ config: { workerMaxLifetimeMin: 7 } });
  const st = await mmState();
  assert.equal(st.workerMaxLifetimeMin, 7, "worker lifetime configurable + surfaced in mm_state");
  await resetCfg();
  assert.equal((await mmState()).workerMaxLifetimeMin, 30, "worker lifetime restored to default");
});
