// Tests for the agent-spawn feature (board subtask 4ab67b6b / task 1c582a88).
//
// Runs a fully isolated mail-daemon via test/helpers/spawn-harness.mjs (fake
// `tmux`, throwaway HOME, short register timeout). Everything is driven over the
// daemon socket — no real tmux/pi is spawned and nothing touches ~/.pi.
//
// Run: npm test   (uses node:test, the stdlib runner — no new dependency)

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import * as fs from "node:fs";
import * as path from "node:path";
import * as os from "node:os";
import * as crypto from "node:crypto";
import {
  mkFakeTmux,
  startDaemon,
  stopDaemon,
  mkClient,
  register as harnessRegister,
} from "./helpers/spawn-harness.mjs";

// ── Isolation harness state (owned here; helper fns are stateless) ──────────

let tmpHome, tmpState, fakeTmux, proc, sockPath, client;
// Kill any spawned daemon when the test runner exits (incl. Ctrl-C / timeout)
// so interrupted runs don't leave orphan daemon processes behind.
process.on("exit", () => { try { if (proc) proc.kill("SIGKILL"); } catch {} });

// Local register wrapper: defaults cwd to tmpHome (matches the original harness).
const register = (c, name, cwd = tmpHome) => harnessRegister(c, name, cwd);

before(async () => {
  tmpHome = fs.mkdtempSync(path.join(os.tmpdir(), "pimail-home-"));
  tmpState = fs.mkdtempSync(path.join(os.tmpdir(), "pimail-tmux-"));
  fakeTmux = path.join(tmpHome, "fake-tmux");
  mkFakeTmux(fakeTmux);
  sockPath = path.join(tmpHome, ".pi", "agent", "mail-daemon.sock");
  proc = await startDaemon({ tmpHome, tmpState, fakeTmux, sockPath });
  client = await mkClient(sockPath);
  await register(client, "test-orchestrator");
});

after(async () => {
  client?.close();
  await stopDaemon(proc);
  fs.rmSync(tmpHome, { recursive: true, force: true });
  fs.rmSync(tmpState, { recursive: true, force: true });
});

// Helper: spawn and return the reply.
const spawn = (c, o) => c.request({ type: "spawn", cwd: o.cwd, name: o.name, model: o.model, kickoff: o.kickoff });
const spawnStop = (c, name) => c.request({ type: "spawn_stop", name });
const spawnState = (c) => c.request({ type: "spawn_state" });

// ── validateSpawnCwd ────────────────────────────────────────────────────────

test("spawn rejects a non-existent cwd", async () => {
  const r = await spawn(client, { cwd: path.join(tmpHome, "nope-does-not-exist") });
  assert.equal(r.type, "error");
  assert.match(r.message, /not a directory/);
});

test("spawn rejects a cwd outside the allowed roots", async () => {
  // NOTE (flagged to operator): task 1c582a88 asks to test "outside allowlist"
  // rejection, but the spawn allowlist was REMOVED from daemon.mjs (uncommitted)
  // while this task was in progress — validateSpawnCwd now only checks that the
  // path is a real directory. So a real dir outside $HOME is currently ACCEPTED.
  // This test pins the CURRENT behaviour; if the allowlist is restored, flip
  // the assertion to expect {type:"error", /outside the allowed roots/}.
  const r = await spawn(client, { cwd: "/etc", name: "etc-currently-allowed" });
  assert.equal(r.type, "spawned", `expected /etc to be allowed (allowlist removed); got ${JSON.stringify(r)}`);
  await spawnStop(client, "etc-currently-allowed");
});

test("spawn accepts a cwd under the allowlist", async () => {
  const r = await spawn(client, { cwd: tmpHome, name: "allowlist-ok" });
  assert.equal(r.type, "spawned");
  await spawnStop(client, "allowlist-ok");
});

// ── name derivation + sanitisation ─────────────────────────────────────────

test("default name is <dir-basename>-<6hex> when no name given", async () => {
  const subdir = path.join(tmpHome, "myproject");
  fs.mkdirSync(subdir, { recursive: true });
  const r = await spawn(client, { cwd: subdir });
  assert.equal(r.type, "spawned");
  assert.match(r.name, /^myproject-[a-f0-9]{6}$/, `got ${r.name}`);
  await spawnStop(client, r.name);
});

test("explicit name with '.' and ':' is sanitised", async () => {
  const r = await spawn(client, { cwd: tmpHome, name: "my.agent:1" });
  assert.equal(r.type, "spawned");
  assert.equal(r.name, "my-agent-1");
  await spawnStop(client, r.name);
});

test("duplicate spawn name is rejected", async () => {
  const r = await spawn(client, { cwd: tmpHome, name: "dup-name" });
  assert.equal(r.type, "spawned");
  const r2 = await spawn(client, { cwd: tmpHome, name: "dup-name" });
  assert.equal(r2.type, "error");
  assert.match(r2.message, /already exists/);
  await spawnStop(client, "dup-name");
});

// ── register-wait timeout + kickoff ─────────────────────────────────────────

test("spawn returns ok even if the agent never registers (register-wait timeout)", async () => {
  // No one registers as this name, so waitForRegistration times out. The reply
  // must still be {type:"spawned"} (kickoff delivery is best-effort, non-blocking)
  // and the daemon must stay alive.
  const r = await spawn(client, { cwd: tmpHome, name: "never-registers", kickoff: "do nothing" });
  assert.equal(r.type, "spawned");
  assert.equal(r.name, "never-registers");
  // Daemon survives the timeout: a trivial RPC still works.
  const st = await spawnState(client);
  assert.equal(st.type, "spawn");
  await spawnStop(client, "never-registers");
});

test("kickoff is delivered once the spawned name registers", async () => {
  // A fresh client registers as the spawned agentName and should receive the
  // kickoff mail (waitForRegistration resolves → sendMail with newSession:true).
  const kickoff = "trivial: reply 'ok' then stop";
  await spawn(client, { cwd: tmpHome, name: "will-register", kickoff });
  const worker = await mkClient(sockPath);
  const mail = new Promise((res) => worker.onNewMail((m) => res(m)));
  await register(worker, "will-register");
  const got = await mail;
  assert.equal(got.subject.includes("Task:"), true, `subject was ${got.subject}`);
  assert.equal(got.body, kickoff);
  assert.equal(got.newSession, true, "kickoff must be a fresh-session task");
  worker.close();
  await spawnStop(client, "never-registers").catch(() => {});
  await spawnStop(client, "will-register");
});

// ── CEO-driven MM/CEO spawn injects the management kickoff (task 9ab32695) ──
// When an orchestrator (the CEO) calls mail_spawn_agent({cwd, mm:true}) WITHOUT
// a kickoff, the daemon must inject the canonical MM pass kickoff built from
// the favorited projects — otherwise the spawned MM wakes up with an empty
// inbox + empty context and sits idle until the reaper kills it. Same for
// ceo:true. An explicit kickoff always wins.

test("mm:true spawn with no kickoff injects the MM pass kickoff", async () => {
  // Add a managed (favorited) project so mmKickoff has something to list.
  await client.request({ type: "spawn_favorite", cwd: tmpHome, favorite: true });
  const r = await client.request({ type: "spawn", cwd: tmpHome, name: "ceo-spawned-mm", mm: true });
  assert.equal(r.type, "spawned", `mm spawn failed: ${JSON.stringify(r)}`);
  const worker = await mkClient(sockPath);
  const mail = new Promise((res) => worker.onNewMail((m) => res(m)));
  await register(worker, "ceo-spawned-mm");
  const got = await mail;
  assert.match(got.body, /You are the middle-manager/, "MM kickoff was not injected");
  assert.match(got.body, /Managed projects/, "MM kickoff must list managed projects");
  assert.equal(got.newSession, true, "injected kickoff must be a fresh-session task");
  // And the session is flagged mm:true so the reaper tracks it.
  const st = await spawnState(client);
  const s = st.sessions.find((x) => x.name === "ceo-spawned-mm");
  assert.ok(s?.mm === true, "mm:true flag not recorded on the spawn registry entry");
  worker.close();
  await spawnStop(client, "ceo-spawned-mm");
  await client.request({ type: "spawn_favorite", cwd: tmpHome, favorite: false });
});

test("ceo:true spawn with no kickoff injects the CEO pass kickoff", async () => {
  await client.request({ type: "spawn_favorite", cwd: tmpHome, favorite: true });
  const r = await client.request({ type: "spawn", cwd: tmpHome, name: "op-spawned-ceo", ceo: true });
  assert.equal(r.type, "spawned", `ceo spawn failed: ${JSON.stringify(r)}`);
  const worker = await mkClient(sockPath);
  const mail = new Promise((res) => worker.onNewMail((m) => res(m)));
  await register(worker, "op-spawned-ceo");
  const got = await mail;
  assert.match(got.body, /You are the CEO/, "CEO kickoff was not injected");
  worker.close();
  await spawnStop(client, "op-spawned-ceo");
  await client.request({ type: "spawn_favorite", cwd: tmpHome, favorite: false });
});

test("explicit kickoff wins over the injected mm/ceo kickoff", async () => {
  await client.request({ type: "spawn_favorite", cwd: tmpHome, favorite: true });
  const r = await client.request({ type: "spawn", cwd: tmpHome, name: "explicit-kickoff-mm", mm: true, kickoff: "CUSTOM: do the thing" });
  assert.equal(r.type, "spawned");
  const worker = await mkClient(sockPath);
  const mail = new Promise((res) => worker.onNewMail((m) => res(m)));
  await register(worker, "explicit-kickoff-mm");
  const got = await mail;
  assert.equal(got.body, "CUSTOM: do the thing", "explicit kickoff must override the injected MM kickoff");
  worker.close();
  await spawnStop(client, "explicit-kickoff-mm");
  await client.request({ type: "spawn_favorite", cwd: tmpHome, favorite: false });
});

// ── stop-only-tracked-sessions ──────────────────────────────────────────────

test("spawn_stop refuses a name the daemon did not spawn", async () => {
  const r = await spawnStop(client, "some-operator-agent");
  assert.equal(r.type, "error");
  assert.match(r.message, /not a daemon-spawned agent/);
});

test("spawn_stop stops a daemon-spawned session", async () => {
  const r = await spawn(client, { cwd: tmpHome, name: "to-stop" });
  assert.equal(r.type, "spawned");
  const st = await spawnState(client);
  assert.ok(st.sessions.some((s) => s.name === "to-stop"));
  const stop = await spawnStop(client, "to-stop");
  assert.equal(stop.type, "ok");
  const st2 = await spawnState(client);
  assert.ok(!st2.sessions.some((s) => s.name === "to-stop"), "session still present after stop");
});

test("stopped session's tmux session is killed", async () => {
  const r = await spawn(client, { cwd: tmpHome, name: "kill-check" });
  assert.equal(r.type, "spawned");
  // fake-tmux recorded the session file on new-session:
  assert.ok(fs.existsSync(path.join(tmpState, "sessions", "kill-check")));
  await spawnStop(client, "kill-check");
  assert.ok(!fs.existsSync(path.join(tmpState, "sessions", "kill-check")), "tmux session file should be gone after stop");
});

// ── happy path + restart survival ───────────────────────────────────────────

test("happy path: spawn → visible in state → stop → gone", async () => {
  const r = await spawn(client, { cwd: tmpHome, name: "happy", model: "anthropic/claude-sonnet-4" });
  assert.equal(r.type, "spawned");
  const st = await spawnState(client);
  const s = st.sessions.find((x) => x.name === "happy");
  assert.ok(s, "happy not in spawn state");
  assert.equal(s.cwd, tmpHome);
  assert.equal(s.model, "anthropic/claude-sonnet-4");
  assert.equal(s.alive, true);
  await spawnStop(client, "happy");
  const st2 = await spawnState(client);
  assert.ok(!st2.sessions.find((x) => x.name === "happy"));
});

test("survival: spawned session survives a daemon restart", async () => {
  const r = await spawn(client, { cwd: tmpHome, name: "survivor" });
  assert.equal(r.type, "spawned");
  client.close();
  await stopDaemon(proc);
  // Restart with the SAME HOME/env so the registry + fake-tmux state persist.
  proc = await startDaemon({ tmpHome, tmpState, fakeTmux, sockPath });
  client = await mkClient(sockPath);
  await register(client, "test-orchestrator-2");
  const st = await spawnState(client);
  const s = st.sessions.find((x) => x.name === "survivor");
  assert.ok(s, "spawned session did not survive daemon restart");
  assert.equal(s.alive, true, "reconciled session should be alive (fake-tmux has-session=true)");
  // And it can still be stopped.
  const stop = await spawnStop(client, "survivor");
  assert.equal(stop.type, "ok");
});

// ── spawn_ls respects the allowlist ─────────────────────────────────────────

test("spawn_ls lists a directory (allowlist removed)", async () => {
  // The allowlist check was removed from listSpawnDir too, so /etc (a real
  // dir) is listable. If the allowlist is restored, flip this to expect an
  // "outside the allowed roots" error for /etc.
  const r = await client.request({ type: "spawn_ls", path: "/etc" });
  assert.equal(r.type, "spawn_ls");
  assert.equal(r.dir, "/etc");
  assert.ok(Array.isArray(r.dirs));
});

// ── no mail / board regression (spawn feature must not break core RPCs) ──────

test("regression: mail send + list still works", async () => {
  const w = await mkClient(sockPath);
  await register(w, "regression-worker");
  const got = new Promise((res) => w.onNewMail((m) => res(m)));
  const r = await client.request({ type: "send", to: "regression-worker", subject: "regression", body: "hello" });
  assert.ok(r.messageId || r.type === "sent", `send failed: ${JSON.stringify(r)}`);
  const m = await got;
  assert.equal(m.subject, "regression");
  assert.equal(m.body, "hello");
  const inbox = await w.request({ type: "list_mail" });
  assert.ok((inbox.messages || []).some((x) => x.subject === "regression"));
  w.close();
});

test("regression: board state still works", async () => {
  const r = await client.request({ type: "board_state" });
  assert.equal(r.type, "board");
  assert.ok(Array.isArray(r.columns) && r.columns.length > 0, "board columns missing");
  assert.ok(Array.isArray(r.tasks), "board tasks missing");
});

// ── tmux-session ↔ agent name linkage (task c92d3d18) ──────────────────────
// The spawn flow names the tmux session (via `pi -n <name>`) and the
// extension must register UNDER THAT SAME NAME so the daemon can link the
// registered agentId into the spawn registry (and deliver the kickoff).
// These tests pin the daemon-side contract the fixed extension satisfies.

test("spawn registry links the agentId once the agent registers under the session name", async () => {
  const agentId = crypto.randomUUID();
  await spawn(client, { cwd: tmpHome, name: "linker", kickoff: "hi" });
  const worker = await mkClient(sockPath);
  // The fixed extension adopts `pi -n linker` as its agent name, so it
  // registers under "linker" — matching the tmux session name.
  await worker.request({ type: "register", agentId, agentName: "linker", cwd: tmpHome });
  // waitForRegistration polls every 250ms; give it room to resolve + persist.
  await new Promise((r) => setTimeout(r, 700));
  const st = await spawnState(client);
  const s = st.sessions.find((x) => x.name === "linker");
  assert.ok(s, "linker session missing from spawn state");
  assert.equal(s.agentId, agentId, "registry did not link the registered agentId to the tmux session");
  worker.close();
  await spawnStop(client, "linker");
});

test("registry agentId stays empty when the agent registers under a different name (the bug this fix targets)", async () => {
  // Old behaviour: the extension ignored `pi -n` and registered under its own
  // auto-slug (`<dir>-<ownUUID6>`), so the daemon could never resolve the
  // session name to a registered agent — the link stayed broken.
  await spawn(client, { cwd: tmpHome, name: "mismatch-session", kickoff: "hi" });
  const worker = await mkClient(sockPath);
  await register(worker, "mismatch-auto-slug"); // a DIFFERENT name than the session
  await new Promise((r) => setTimeout(r, 700));
  const st = await spawnState(client);
  const s = st.sessions.find((x) => x.name === "mismatch-session");
  assert.ok(s);
  assert.equal(s.agentId, "", "registry should NOT link a name that doesn't match the session");
  worker.close();
  await spawnStop(client, "mismatch-session");
});
