// Tests for the middle-manager (MM) scheduler + reaper (task 7f73d6b9).
//
// Drives the daemon over its socket with the same isolated harness as the
// spawn/projects tests: a throwaway HOME, a fake tmux (state files in a temp
// dir), pi=/bin/true, and a short spawn-register timeout. The MM scheduler is
// exercised via the diagnostic mm_tick / mm_state RPCs (mm_tick accepts a
// fake `now` so time-based gates — interval, max lifetime — are controllable
// without waiting). MM config is driven over the HTTP board-config endpoint
// (fixed UI port). No real tmux/pi is spawned; nothing touches the operator's
// ~/.pi.
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
// Fixed UI port so tests can hit the HTTP board-config endpoint.
const UI_PORT = "19994";

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
  tmpHome = fs.mkdtempSync(path.join(os.tmpdir(), "pimail-mm-"));
  tmpState = fs.mkdtempSync(path.join(os.tmpdir(), "pimail-tmux-"));
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
const favorite = (cwd, fav) => client.request({ type: "spawn_favorite", cwd, favorite: fav });
const mmState = () => client.request({ type: "mm_state" });
const mmTick = (now, force) => client.request({ type: "mm_tick", ...(now != null ? { now } : {}), ...(force ? { force: true } : {}) });
const spawnState = () => client.request({ type: "spawn_state" });
const spawnStop = (name) => client.request({ type: "spawn_stop", name });

function mkDir(name) {
  const d = path.join(tmpHome, name);
  fs.mkdirSync(d, { recursive: true });
  return d;
}
// Wait a beat for the async kickoff-delivery (spawn-register timeout) to settle.
const settle = (ms = 100) => new Promise((r) => setTimeout(r, ms));
const mmSessions = async () => (await spawnState()).sessions.filter((s) => s.mm);

// ── tests ────────────────────────────────────────────────────────────────────

test("MM is disabled by default", async () => {
  const st = await mmState();
  assert.equal(st.enabled, false);
});

test("MM config is editable + restores booleans (incl. false)", async () => {
  await postCfg({ config: { mmEnabled: true, mmIntervalMin: 5, mmModel: "anthropic/claude-sonnet-4", mmMaxLifetimeMin: 10 } });
  const st = await mmState();
  assert.equal(st.enabled, true);
  assert.equal(st.intervalMin, 5);
  assert.equal(st.model, "anthropic/claude-sonnet-4");
  assert.equal(st.maxLifetimeMin, 10);
  // Turning it back off sticks (boolean restored, not truthy-only).
  await postCfg({ config: { mmEnabled: false } });
  assert.equal((await mmState()).enabled, false);
});

test("no spawn when disabled, even with favorites", async () => {
  const dir = mkDir("proj-disabled");
  await favorite(dir, true);
  const before = (await spawnState()).sessions.length;
  await mmTick();
  await settle();
  assert.equal((await spawnState()).sessions.length, before, "no MM spawned while disabled");
  await favorite(dir, false);
});

test("no spawn when favorites empty, even when enabled", async () => {
  await postCfg({ config: { mmEnabled: true, mmIntervalMin: 1 } });
  for (const cwd of (await mmState()).managedProjects) await favorite(cwd, false);
  const before = (await spawnState()).sessions.length;
  await mmTick();
  await settle();
  assert.equal((await spawnState()).sessions.length, before, "no MM spawned with empty favorites");
  await postCfg({ config: { mmEnabled: false } });
});

test("enabled + favorites → spawns one MM whose kickoff names the projects", async () => {
  const dir = mkDir("proj-spawn");
  await favorite(dir, true);
  await postCfg({ config: { mmEnabled: true, mmIntervalMin: 1 } });
  // lastSpawnTs may be set by a prior test → force a cycle now.
  assert.equal((await mmTick(undefined, true)).spawned, true);
  await settle();
  const mm = (await mmSessions())[0];
  assert.ok(mm, "an MM session was spawned");
  assert.ok(mm.name.startsWith("middle-manager-"), `MM name has prefix: ${mm.name}`);
  assert.ok(mm.kickoff.includes(dir), "kickoff names the favorited project");
  assert.ok(mm.kickoff.includes("group: proj-spawn"), "kickoff names the project group");
  assert.ok(/unblock|archive|Done/i.test(mm.kickoff), "kickoff describes the review workflow");
  assert.ok(mm.kickoff.includes("human"), "kickoff instructs mailing human on completion");
  await spawnStop(mm.name);
  await favorite(dir, false);
  await postCfg({ config: { mmEnabled: false } });
});

test("no overlap: a second tick while the MM is alive does not spawn another", async () => {
  const dir = mkDir("proj-overlap");
  await favorite(dir, true);
  await postCfg({ config: { mmEnabled: true, mmIntervalMin: 1 } });
  assert.equal((await mmTick(undefined, true)).spawned, true);
  await settle();
  assert.equal((await mmSessions()).length, 1);
  // Immediately tick again — the MM is still alive, so skip.
  const r2 = await mmTick();
  assert.equal(r2.spawned, false);
  assert.match(r2.reason, /live/i);
  assert.equal((await mmSessions()).length, 1, "still exactly one MM session");
  await spawnStop((await mmSessions())[0].name);
  await favorite(dir, false);
  await postCfg({ config: { mmEnabled: false } });
});

test("interval gate: no spawn within mmIntervalMin of the last spawn", async () => {
  const dir = mkDir("proj-interval");
  await favorite(dir, true);
  await postCfg({ config: { mmEnabled: true, mmIntervalMin: 30 } });
  // First spawn at t0 (force past any leftover lastSpawnTs).
  const t0 = 1_000_000_000_000;
  assert.equal((await mmTick(t0, true)).spawned, true);
  await settle();
  // Stop the live MM so the overlap gate doesn't mask the interval gate.
  await spawnStop((await mmSessions())[0].name);
  // 5 min later — within the 30-min interval → no spawn.
  const r2 = await mmTick(t0 + 5 * 60_000);
  assert.equal(r2.spawned, false);
  assert.match(r2.reason, /interval/i);
  // 31 min later — past the interval → spawns again.
  assert.equal((await mmTick(t0 + 31 * 60_000)).spawned, true);
  await settle();
  await spawnStop((await mmSessions())[0].name);
  await favorite(dir, false);
  await postCfg({ config: { mmEnabled: false } });
});

test("reaper cleans up an MM session whose tmux session has ended", async () => {
  const dir = mkDir("proj-reap-dead");
  await favorite(dir, true);
  await postCfg({ config: { mmEnabled: true, mmIntervalMin: 1 } });
  await mmTick(undefined, true);
  await settle();
  const mm = (await mmSessions())[0];
  assert.ok(mm);
  // Kill the tmux session out of band (simulate the MM exiting on its own).
  fs.rmSync(path.join(tmpState, "sessions", mm.name));
  assert.equal((await spawnState()).sessions.find((s) => s.name === mm.name).alive, false);
  // A tick should reap it (stopAgent cleans the registry entry).
  await mmTick();
  assert.equal((await spawnState()).sessions.find((s) => s.name === mm.name), undefined, "dead MM reaped");
  await favorite(dir, false);
  await postCfg({ config: { mmEnabled: false } });
});

test("reaper forcibly stops an MM session exceeding max lifetime", async () => {
  const dir = mkDir("proj-reap-old");
  await favorite(dir, true);
  await postCfg({ config: { mmEnabled: true, mmIntervalMin: 1, mmMaxLifetimeMin: 1 } });
  await mmTick(undefined, true);
  await settle();
  const mm = (await mmSessions())[0];
  assert.ok(mm);
  // Unfavorite so the spawn path won't immediately re-spawn after the reaper
  // stops the over-lifetime session — isolate the reaper.
  await favorite(dir, false);
  // 2 min after spawn — past the 1-min max lifetime → reaped.
  const st = await mmState();
  await mmTick(st.lastSpawnTs + 2 * 60_000);
  assert.equal((await spawnState()).sessions.find((s) => s.name === mm.name), undefined, "over-lifetime MM stopped");
  await postCfg({ config: { mmEnabled: false, mmMaxLifetimeMin: 15 } });
});

test("a deleted first favorite falls back to another managed dir", async () => {
  const dirA = mkDir("proj-fallback-a");
  const dirB = mkDir("proj-fallback-b");
  await favorite(dirA, true);
  await favorite(dirB, true);
  await postCfg({ config: { mmEnabled: true, mmIntervalMin: 1 } });
  // Delete the first favorite's dir out of band — the MM should fall back to dirB.
  fs.rmSync(dirA, { recursive: true, force: true });
  assert.equal((await mmTick(undefined, true)).spawned, true);
  await settle();
  const mm = (await mmSessions())[0];
  assert.ok(mm, "MM spawned from the surviving managed dir");
  assert.equal(mm.cwd, dirB, "MM cwd fell back to the second favorite");
  await spawnStop(mm.name);
  await favorite(dirA, false);
  await favorite(dirB, false);
  await postCfg({ config: { mmEnabled: false } });
});

test("MM config + favorites + lastSpawnTs survive a daemon restart", async () => {
  const dir = mkDir("proj-persist");
  await favorite(dir, true);
  await postCfg({ config: { mmEnabled: true, mmIntervalMin: 7, mmMaxLifetimeMin: 12 } });
  // Force a spawn so lastSpawnTs is set to a known, non-zero value.
  await mmTick(1_700_000_000_000, true);
  await settle();
  // Stop the spawned MM so it doesn't linger across the restart.
  const mm = (await mmSessions())[0];
  if (mm) await spawnStop(mm.name);
  const before = await mmState();
  assert.ok(before.lastSpawnTs > 0, "lastSpawnTs set before restart");
  client.close();
  await stopDaemon();
  await startDaemon();
  client = await mkClient();
  await register(client, "test-orchestrator-2");
  const st = await mmState();
  assert.equal(st.enabled, true, "mmEnabled survived restart");
  assert.equal(st.intervalMin, 7, "mmIntervalMin survived restart");
  assert.equal(st.maxLifetimeMin, 12, "mmMaxLifetimeMin survived restart");
  assert.ok(st.managedProjects.includes(dir), "favorites survived restart");
  assert.equal(st.lastSpawnTs, before.lastSpawnTs, "lastSpawnTs survived restart (no immediate re-spawn)");
  await favorite(dir, false);
  await postCfg({ config: { mmEnabled: false } });
});
