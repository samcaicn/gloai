// Tests for the CEO scheduler + reaper (task 1bee57ee) + mail_stop_self.
//
// Same isolated harness as middle-manager.test.mjs: a throwaway HOME, a fake
// tmux (state files in a temp dir), pi=/bin/true, and a short spawn-register
// timeout. The CEO scheduler is exercised via the diagnostic ceo_tick /
// ceo_state RPCs (ceo_tick accepts a fake `now` so time-based gates are
// controllable without waiting). mail_stop_self is driven over the socket.
// CEO config is driven over the HTTP board-config endpoint (fixed UI port).
// No real tmux/pi is spawned; nothing touches the operator's ~/.pi.
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
const UI_PORT = "19997";

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
  tmpHome = fs.mkdtempSync(path.join(os.tmpdir(), "pimail-ceo-"));
  tmpState = fs.mkdtempSync(path.join(os.tmpdir(), "pimail-tmux-ceo-"));
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
const ceoState = () => client.request({ type: "ceo_state" });
const ceoTick = (now, force) => client.request({ type: "ceo_tick", ...(now != null ? { now } : {}), ...(force ? { force: true } : {}) });
const mmState = () => client.request({ type: "mm_state" });
const mmTick = (now, force) => client.request({ type: "mm_tick", ...(now != null ? { now } : {}), ...(force ? { force: true } : {}) });
const spawnState = () => client.request({ type: "spawn_state" });
const spawnStop = (name) => client.request({ type: "spawn_stop", name });
const stopSelf = () => client.request({ type: "stop_self" });

function mkDir(name) {
  const d = path.join(tmpHome, name);
  fs.mkdirSync(d, { recursive: true });
  return d;
}
const settle = (ms = 100) => new Promise((r) => setTimeout(r, ms));
const ceoSessions = async () => (await spawnState()).sessions.filter((s) => s.ceo);

// ── tests ────────────────────────────────────────────────────────────────────

test("CEO is disabled by default", async () => {
  const st = await ceoState();
  assert.equal(st.enabled, false);
});

test("CEO config is editable + restores booleans (incl. false)", async () => {
  await postCfg({ config: { ceoEnabled: true, ceoIntervalMin: 60, ceoModel: "anthropic/claude-opus-4", ceoMaxLifetimeMin: 25 } });
  const st = await ceoState();
  assert.equal(st.enabled, true);
  assert.equal(st.intervalMin, 60);
  assert.equal(st.model, "anthropic/claude-opus-4");
  assert.equal(st.maxLifetimeMin, 25);
  await postCfg({ config: { ceoEnabled: false } });
  assert.equal((await ceoState()).enabled, false);
});

test("no spawn when disabled, even with favorites", async () => {
  const dir = mkDir("proj-ceo-disabled");
  await favorite(dir, true);
  const before = (await spawnState()).sessions.length;
  await ceoTick();
  await settle();
  assert.equal((await spawnState()).sessions.length, before, "no CEO spawned while disabled");
  await favorite(dir, false);
});

test("no spawn when favorites empty, even when enabled", async () => {
  await postCfg({ config: { ceoEnabled: true, ceoIntervalMin: 1 } });
  for (const cwd of (await ceoState()).managedProjects) await favorite(cwd, false);
  const before = (await spawnState()).sessions.length;
  await ceoTick();
  await settle();
  assert.equal((await spawnState()).sessions.length, before, "no CEO spawned with empty favorites");
  await postCfg({ config: { ceoEnabled: false } });
});

test("enabled + favorites → spawns one CEO whose kickoff names the projects", async () => {
  const dir = mkDir("proj-ceo-spawn");
  await favorite(dir, true);
  await postCfg({ config: { ceoEnabled: true, ceoIntervalMin: 1 } });
  assert.equal((await ceoTick(undefined, true)).spawned, true);
  await settle();
  const ceo = (await ceoSessions())[0];
  assert.ok(ceo, "a CEO session was spawned");
  assert.ok(ceo.name.startsWith("ceo-"), `CEO name has prefix: ${ceo.name}`);
  assert.ok(ceo.kickoff.includes(dir), "kickoff names the favorited project");
  assert.ok(ceo.kickoff.includes("group: proj-ceo-spawn"), "kickoff names the project group");
  assert.ok(/spawn.*middle manager|mm: true/i.test(ceo.kickoff), "kickoff describes spawning MMs");
  assert.ok(/no.*task administration|do not.*move|do not.*archive/i.test(ceo.kickoff), "kickoff forbids task administration");
  assert.ok(ceo.kickoff.includes("human"), "kickoff instructs mailing human on completion");
  assert.ok(ceo.kickoff.includes("mail_stop_self"), "kickoff instructs calling mail_stop_self");
  await spawnStop(ceo.name);
  await favorite(dir, false);
  await postCfg({ config: { ceoEnabled: false } });
});

test("no overlap: a second tick while the CEO is alive does not spawn another", async () => {
  const dir = mkDir("proj-ceo-overlap");
  await favorite(dir, true);
  await postCfg({ config: { ceoEnabled: true, ceoIntervalMin: 1 } });
  assert.equal((await ceoTick(undefined, true)).spawned, true);
  await settle();
  assert.equal((await ceoSessions()).length, 1);
  const r2 = await ceoTick();
  assert.equal(r2.spawned, false);
  assert.match(r2.reason, /live/i);
  assert.equal((await ceoSessions()).length, 1, "still exactly one CEO session");
  await spawnStop((await ceoSessions())[0].name);
  await favorite(dir, false);
  await postCfg({ config: { ceoEnabled: false } });
});

test("interval gate: no spawn within ceoIntervalMin of the last spawn", async () => {
  const dir = mkDir("proj-ceo-interval");
  await favorite(dir, true);
  await postCfg({ config: { ceoEnabled: true, ceoIntervalMin: 120 } });
  const t0 = 1_000_000_000_000;
  assert.equal((await ceoTick(t0, true)).spawned, true);
  await settle();
  await spawnStop((await ceoSessions())[0].name);
  // 10 min later — within the 120-min interval → no spawn.
  const r2 = await ceoTick(t0 + 10 * 60_000);
  assert.equal(r2.spawned, false);
  assert.match(r2.reason, /interval/i);
  // 121 min later — past the interval → spawns again.
  assert.equal((await ceoTick(t0 + 121 * 60_000)).spawned, true);
  await settle();
  await spawnStop((await ceoSessions())[0].name);
  await favorite(dir, false);
  await postCfg({ config: { ceoEnabled: false } });
});

test("reaper cleans up a CEO session whose tmux session has ended", async () => {
  const dir = mkDir("proj-ceo-reap-dead");
  await favorite(dir, true);
  await postCfg({ config: { ceoEnabled: true, ceoIntervalMin: 1 } });
  await ceoTick(undefined, true);
  await settle();
  const ceo = (await ceoSessions())[0];
  assert.ok(ceo);
  fs.rmSync(path.join(tmpState, "sessions", ceo.name));
  assert.equal((await spawnState()).sessions.find((s) => s.name === ceo.name).alive, false);
  await ceoTick();
  assert.equal((await spawnState()).sessions.find((s) => s.name === ceo.name), undefined, "dead CEO reaped");
  await favorite(dir, false);
  await postCfg({ config: { ceoEnabled: false } });
});

test("reaper forcibly stops a CEO session exceeding max lifetime", async () => {
  const dir = mkDir("proj-ceo-reap-old");
  await favorite(dir, true);
  await postCfg({ config: { ceoEnabled: true, ceoIntervalMin: 1, ceoMaxLifetimeMin: 1 } });
  await ceoTick(undefined, true);
  await settle();
  const ceo = (await ceoSessions())[0];
  assert.ok(ceo);
  await favorite(dir, false);
  const st = await ceoState();
  await ceoTick(st.lastSpawnTs + 2 * 60_000);
  assert.equal((await spawnState()).sessions.find((s) => s.name === ceo.name), undefined, "over-lifetime CEO stopped");
  await postCfg({ config: { ceoEnabled: false, ceoMaxLifetimeMin: 20 } });
});

test("ceoEnabled suppresses the daemon MM loop's own spawn (CEO is sole MM spawner)", async () => {
  const dir = mkDir("proj-mm-suppressed");
  await favorite(dir, true);
  // Enable BOTH mm and ceo. With ceoEnabled, mmTick must skip its own spawn.
  await postCfg({ config: { mmEnabled: true, mmIntervalMin: 1, ceoEnabled: true, ceoIntervalMin: 1 } });
  const r = await mmTick(undefined, true);
  assert.equal(r.spawned, false, "mmTick did not spawn an MM while ceoEnabled");
  assert.match(r.reason, /ceo/i, "skip reason mentions ceo manages MM spawning");
  await postCfg({ config: { mmEnabled: false, ceoEnabled: false } });
  await favorite(dir, false);
});

test("CEO config + favorites + lastSpawnTs survive a daemon restart", async () => {
  const dir = mkDir("proj-ceo-persist");
  await favorite(dir, true);
  await postCfg({ config: { ceoEnabled: true, ceoIntervalMin: 90, ceoMaxLifetimeMin: 25 } });
  await ceoTick(1_700_000_000_000, true);
  await settle();
  const ceo = (await ceoSessions())[0];
  if (ceo) await spawnStop(ceo.name);
  const before = await ceoState();
  assert.ok(before.lastSpawnTs > 0, "lastSpawnTs set before restart");
  client.close();
  await stopDaemon();
  await startDaemon();
  client = await mkClient();
  await register(client, "test-orchestrator-2");
  const st = await ceoState();
  assert.equal(st.enabled, true, "ceoEnabled survived restart");
  assert.equal(st.intervalMin, 90, "ceoIntervalMin survived restart");
  assert.equal(st.maxLifetimeMin, 25, "ceoMaxLifetimeMin survived restart");
  assert.ok(st.managedProjects.includes(dir), "favorites survived restart");
  assert.equal(st.lastSpawnTs, before.lastSpawnTs, "lastSpawnTs survived restart (no immediate re-spawn)");
  await favorite(dir, false);
  await postCfg({ config: { ceoEnabled: false } });
});

// ── mail_stop_self ───────────────────────────────────────────────────────────

test("mail_stop_self refuses an unregistered (operator-launched) agent", async () => {
  // The test-orchestrator client was launched by the test harness, not by the
  // daemon's spawnAgent — so stop_self must refuse it.
  const r = await stopSelf();
  assert.equal(r.type, "error");
  assert.match(r.message, /not a daemon-spawned agent|operator/i, "refusal message explains it's for daemon-spawned agents only");
});

test("mail_stop_self tears down a daemon-spawned agent's session", async () => {
  const dir = mkDir("proj-stopself");
  // Spawn a daemon-spawned agent (fake pi=/bin/true so it "registers" as a
  // tracked session without a real pi process). Then register a client under
  // the SAME name + agentId the daemon would stamp, and call stop_self from it.
  const name = `stopself-test-${crypto.randomUUID().slice(0, 6)}`;
  // Spawn via the daemon so the session is in the spawn registry.
  const sp = await client.request({ type: "spawn", cwd: dir, name });
  assert.equal(sp.type, "spawned");
  // Make the fake tmux session "alive" (mkFakeTmux already touched it on spawn).
  // Register a client connection under that session name so the daemon links
  // the agentId to the spawn-registry entry (by agentName).
  const workerClient = await mkClient();
  const workerAgentId = crypto.randomUUID();
  await workerClient.request({ type: "register", agentId: workerAgentId, agentName: name, cwd: dir });
  // The registry should now have agentId stamped (or at least agentName match).
  const before = (await spawnState()).sessions.find((s) => s.name === name);
  assert.ok(before, "spawned session is tracked before stop_self");
  // Call stop_self as that worker.
  const r = await workerClient.request({ type: "stop_self" });
  assert.notEqual(r.type, "error", `stop_self should succeed for a daemon-spawned agent: ${JSON.stringify(r)}`);
  assert.equal(r.name, name, "stop_self returns the session name");
  // The registry entry is removed immediately (grace only delays the tmux kill).
  await settle(50);
  const after = (await spawnState()).sessions.find((s) => s.name === name);
  assert.equal(after, undefined, "registry entry removed immediately on stop_self");
  workerClient.close();
});

// ── all-groups oversight (CEO covers EVERY board group, not only favorites) ───

// These run last and archive their task in cleanup so the shared board stays
// clean for the suite (the spawn-gate now also fires on on-board tasks, so a
// leftover task would flip "no spawn when favorites empty" for a later run).

test("ceoState reports all-groups scope + on-board-task signal", async () => {
  await postCfg({ config: { ceoEnabled: true, ceoIntervalMin: 1 } });
  const dir = mkDir("proj-ceo-state");
  await favorite(dir, true);
  const st = await ceoState();
  assert.equal(st.allGroups, true, "ceoState reports the all-groups scope");
  assert.equal(st.onBoardTasks, false, "no on-board tasks yet → signal false");
  await favorite(dir, false);
  await postCfg({ config: { ceoEnabled: false } });
});

test("CEO spawns when favorites empty but on-board tasks exist (all-groups)", async () => {
  // A non-favorited group with an on-board task must still trigger a CEO
  // cycle — the CEO oversees ALL board groups, not only the favorited baseline.
  const dir = mkDir("proj-ceo-allgroups");
  // Register an agent in that project so created tasks stamp its group.
  const worker = await mkClient();
  const agentId = crypto.randomUUID();
  await worker.request({ type: "register", agentId, agentName: "allgroups-worker", cwd: dir });
  // Ensure NO favorites — the only thing to manage is this non-favorited task.
  for (const cwd of (await ceoState()).managedProjects) await favorite(cwd, false);
  assert.equal((await ceoState()).managedProjects.length, 0, "favorites is empty");
  // Create an on-board task in the non-favorited group.
  const cr = await worker.request({ type: "board_create", summary: "allgroups probe", description: "non-favorited group task" });
  assert.notEqual(cr.type, "error", `board_create succeeded: ${JSON.stringify(cr)}`);
  const taskId = cr.task.id;
  assert.equal((await ceoState()).onBoardTasks, true, "on-board task detected");
  await postCfg({ config: { ceoEnabled: true, ceoIntervalMin: 1 } });
  // Favorites empty + on-board task present → a CEO spawns anyway.
  assert.equal((await ceoTick(undefined, true)).spawned, true, "CEO spawned despite empty favorites (on-board task exists)");
  await settle();
  const ceo = (await ceoSessions())[0];
  assert.ok(ceo, "a CEO session was spawned for the non-favorited group");
  // The kickoff must NOT list the project as a favorited baseline (it isn't),
  // but MUST instruct all-groups oversight so the CEO reviews it anyway.
  assert.ok(!ceo.kickoff.includes(dir), "kickoff does not list the non-favorited project as a favorite");
  assert.match(ceo.kickoff, /No favorited projects this cycle/i, "kickoff notes the empty favorites baseline");
  assert.match(ceo.kickoff, /ALL board groups|every board group/i, "kickoff instructs all-groups oversight");
  assert.match(ceo.kickoff, /non-favorited group with active tasks|unfavorited group with on-board tasks/i, "kickoff covers non-favorited groups with tasks");
  await spawnStop(ceo.name);
  // Cleanup: archive the task so it doesn't keep triggering cycles / pollute
  // the shared board for the rest of the suite.
  await worker.request({ type: "board_move", taskId, column: "archive" });
  worker.close();
  await postCfg({ config: { ceoEnabled: false } });
});

test("no spawn when favorites empty AND no on-board tasks (all-groups gate)", async () => {
  // Complement of the above: with nothing to manage at all, the CEO must not
  // spawn. (Same condition the legacy "no spawn when favorites empty" test
  // relied on — now made explicit for the all-groups gate.)
  await postCfg({ config: { ceoEnabled: true, ceoIntervalMin: 1 } });
  for (const cwd of (await ceoState()).managedProjects) await favorite(cwd, false);
  assert.equal((await ceoState()).managedProjects.length, 0, "favorites is empty");
  assert.equal((await ceoState()).onBoardTasks, false, "no on-board tasks");
  const before = (await spawnState()).sessions.length;
  await ceoTick(undefined, true);
  await settle();
  assert.equal((await spawnState()).sessions.length, before, "no CEO spawned with nothing to manage");
  await postCfg({ config: { ceoEnabled: false } });
});
