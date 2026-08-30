// Tests for the spawn project history + favorites feature (task 0129ffee).
//
// Drives the daemon over its socket: spawn tracks the cwd in history, the
// spawn_projects RPC lists favorites + recent dirs, and spawn_favorite
// stars/unstars a dir. History is deduped (newest-first, count incremented)
// and capped. State survives a daemon restart (persisted in the spawn
// registry file). Uses an isolated HOME + a fake tmux, like spawn.test.mjs.
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

let tmpHome, tmpState, fakeTmux, proc, sockPath, client;
// Kill any spawned daemon when the test runner exits (incl. Ctrl-C / timeout)
// so interrupted runs don't leave orphan daemon processes behind.
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
        PI_MAIL_UI_PORT: "0",
        PI_MAIL_UI_HOST: "127.0.0.1",
        PI_MAIL_SPAWN_TIMEOUT: "1500",
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
  tmpHome = fs.mkdtempSync(path.join(os.tmpdir(), "pimail-proj-"));
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

const spawn = (c, o) => c.request({ type: "spawn", cwd: o.cwd, name: o.name, model: o.model, kickoff: o.kickoff, favorite: o.favorite });
const spawnStop = (c, name) => c.request({ type: "spawn_stop", name });
const projects = (c) => c.request({ type: "spawn_projects" });
const favorite = (c, cwd, fav) => c.request({ type: "spawn_favorite", cwd, favorite: fav });

// ── history tracking ──────────────────────────────────────────────────────────

test("spawn_projects starts empty (no history, no favorites)", async () => {
  const r = await projects(client);
  assert.equal(r.type, "spawn_projects");
  assert.deepEqual(r.favorites, []);
  assert.deepEqual(r.history, []);
});

test("spawn records the cwd in history", async () => {
  const dir = path.join(tmpHome, "projA");
  fs.mkdirSync(dir, { recursive: true });
  const r = await spawn(client, { cwd: dir, name: "a1" });
  assert.equal(r.type, "spawned");
  const p = await projects(client);
  assert.equal(p.history.length, 1);
  assert.equal(p.history[0].cwd, dir);
  assert.equal(p.history[0].count, 1);
  assert.ok(p.history[0].lastSpawnedAt > 0);
  assert.equal(p.history[0].lastName, "a1");
  await spawnStop(client, "a1");
});

test("re-spawning the same cwd dedupes + increments count, newest-first", async () => {
  const dir = path.join(tmpHome, "projB");
  fs.mkdirSync(dir, { recursive: true });
  await spawn(client, { cwd: dir, name: "b1" });
  await spawnStop(client, "b1");
  await spawn(client, { cwd: dir, name: "b2" });
  await spawnStop(client, "b2");
  const p = await projects(client);
  const entry = p.history.find((h) => h.cwd === dir);
  assert.ok(entry, "projB in history");
  assert.equal(entry.count, 2, "count incremented");
  assert.equal(entry.lastName, "b2");
  // No duplicate entries for the same cwd.
  assert.equal(p.history.filter((h) => h.cwd === dir).length, 1);
});

test("history is newest-first", async () => {
  const d1 = path.join(tmpHome, "h1"); fs.mkdirSync(d1, { recursive: true });
  const d2 = path.join(tmpHome, "h2"); fs.mkdirSync(d2, { recursive: true });
  await spawn(client, { cwd: d1, name: "h1a" }); await spawnStop(client, "h1a");
  await spawn(client, { cwd: d2, name: "h2a" }); await spawnStop(client, "h2a");
  const p = await projects(client);
  // Most recent spawn (d2) should be before d1.
  const i1 = p.history.findIndex((h) => h.cwd === d1);
  const i2 = p.history.findIndex((h) => h.cwd === d2);
  assert.ok(i2 < i1, `newest-first expected (h2@${i2} before h1@${i1})`);
});

// ── favorites ────────────────────────────────────────────────────────────────

test("spawn_favorite stars a dir", async () => {
  const dir = path.join(tmpHome, "fav1");
  fs.mkdirSync(dir, { recursive: true });
  const r = await favorite(client, dir, true);
  assert.equal(r.type, "ok");
  assert.equal(r.favorite, true);
  const p = await projects(client);
  assert.ok(p.favorites.some((f) => f.cwd === dir));
  await favorite(client, dir, false); // cleanup
});

test("spawn_favorite false unstars a dir", async () => {
  const dir = path.join(tmpHome, "fav2");
  fs.mkdirSync(dir, { recursive: true });
  await favorite(client, dir, true);
  const r = await favorite(client, dir, false);
  assert.equal(r.favorite, false);
  const p = await projects(client);
  assert.ok(!p.favorites.some((f) => f.cwd === dir));
});

test("mail_spawn_agent favorite param stars the dir at spawn time", async () => {
  const dir = path.join(tmpHome, "fav3");
  fs.mkdirSync(dir, { recursive: true });
  await spawn(client, { cwd: dir, name: "favspawn", favorite: true });
  const p = await projects(client);
  assert.ok(p.favorites.some((f) => f.cwd === dir), "spawn favorite=true added to favorites");
  await spawnStop(client, "favspawn");
  await favorite(client, dir, false);
});

// ── alive flag ────────────────────────────────────────────────────────────────

test("a project with a live session reports alive=true", async () => {
  const dir = path.join(tmpHome, "alive1");
  fs.mkdirSync(dir, { recursive: true });
  await spawn(client, { cwd: dir, name: "alive-agent" });
  const p = await projects(client);
  const entry = p.history.find((h) => h.cwd === dir);
  assert.ok(entry, "alive1 in history");
  assert.equal(entry.alive, true, "should be alive while session is running");
  await spawnStop(client, "alive-agent");
  const p2 = await projects(client);
  const entry2 = p2.history.find((h) => h.cwd === dir);
  assert.equal(entry2.alive, false, "should be not-alive after stop");
});

// ── persistence across restart ───────────────────────────────────────────────

test("history + favorites survive a daemon restart", async () => {
  const dir = path.join(tmpHome, "persist1");
  fs.mkdirSync(dir, { recursive: true });
  await spawn(client, { cwd: dir, name: "persist-agent", favorite: true });
  await spawnStop(client, "persist-agent");
  // Restart with the same HOME so the spawn registry persists.
  client.close();
  await stopDaemon();
  await startDaemon();
  client = await mkClient();
  await register(client, "test-orchestrator-2");
  const p = await projects(client);
  assert.ok(p.history.some((h) => h.cwd === dir), "history survived restart");
  assert.ok(p.favorites.some((f) => f.cwd === dir), "favorite survived restart");
});
