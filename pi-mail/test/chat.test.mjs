// Tests for the MCP project chat feature (task 942b9ad7).
//
// chat_post / chat_get let an MCP client hold a multi-turn chat with a
// project's spawned agent over pi-mail. These tests drive the daemon over its
// socket protocol (chat_post / chat_get / chat_state cases) using the same
// isolated harness as the spawn tests (fake tmux, throwaway HOME). The
// "spawned agent" is a second socket client that registers under the chat
// session name, receives the question mail, and mails a reply to "human".
//
// Covers: thread creation + thread_id return, multi-turn round-trip,
// chat_get blocking-then-resolve (no polling), chat_post(wait:false) returning
// the thread_id immediately, and the idle/dead reaper dropping a thread whose
// agent has exited.
//
// Run: npm test   (node:test runner)

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

// ── Isolation harness state ─────────────────────────────────────────────────

let tmpHome, tmpState, fakeTmux, proc, sockPath, client;
process.on("exit", () => { try { if (proc) proc.kill("SIGKILL"); } catch {} });

const register = (c, name, cwd = tmpHome) => harnessRegister(c, name, cwd);

before(async () => {
  tmpHome = fs.mkdtempSync(path.join(os.tmpdir(), "pimail-chat-home-"));
  tmpState = fs.mkdtempSync(path.join(os.tmpdir(), "pimail-chat-tmux-"));
  fakeTmux = path.join(tmpHome, "fake-tmux");
  mkFakeTmux(fakeTmux);
  sockPath = path.join(tmpHome, ".pi", "agent", "mail-daemon.sock");
  proc = await startDaemon({
    tmpHome, tmpState, fakeTmux, sockPath,
    envExtra: {
      PI_MAIL_CHAT_TICK_MS: "100",
      PI_MAIL_CHAT_REGISTER_TIMEOUT: "8000",
      PI_MAIL_CHAT_WAIT_MS: "10000",
    },
  });
  client = await mkClient(sockPath);
  await register(client, "chat-test-orchestrator");
});

after(async () => {
  client?.close();
  await stopDaemon(proc);
  fs.rmSync(tmpHome, { recursive: true, force: true });
  fs.rmSync(tmpState, { recursive: true, force: true });
});

// ── Helpers ────────────────────────────────────────────────────────────────

/** Poll chat_state until a thread with the given id appears, returning it. */
async function waitForThread(threadId, timeoutMs = 5000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    const st = await chatState(client);
    const t = st.threads.find((x) => x.threadId === threadId);
    if (t) return t;
    await new Promise((r) => setTimeout(r, 30));
  }
  assert.fail(`thread ${threadId} never appeared in chat_state`);
}

/** Register a fake worker under `name` and resolve chat mails it receives.
 *  Returns { client, waitForMail, reply }. waitForMail returns the NEXT unseen
 *  chat mail (subject starts with "chat:"), so multi-turn calls each get the
 *  latest message rather than re-resolving the first one. */
async function fakeWorker(name) {
  const w = await mkClient(sockPath);
  const inbox = [];
  let cursor = 0;
  w.onNewMail((m) => { inbox.push(m); });
  await register(w, name);
  return {
    client: w,
    /** Wait for the next unseen chat mail (subject starts with "chat:"). */
    async waitForMail(timeoutMs = 6000) {
      const start = Date.now();
      while (Date.now() - start < timeoutMs) {
        while (cursor < inbox.length) {
          const m = inbox[cursor++];
          if (m.subject && m.subject.startsWith("chat:")) return m;
        }
        await new Promise((r) => setTimeout(r, 30));
      }
      assert.fail(`worker '${name}' never received a chat mail`);
    },
    /** Reply to the human, echoing the thread marker in the subject. */
    async reply(question, body) {
      const threadId = (question.subject.match(/chat:([0-9a-fA-F-]{6,})/) || [])[1];
      const r = await w.request({ type: "send", to: "human", subject: `chat:${threadId} reply`, body });
      assert.ok(r.messageId || r.type === "sent", `worker reply send failed: ${JSON.stringify(r)}`);
    },
    close() { w.close(); },
  };
}

const chatPost = (c, o) => c.request({ type: "chat_post", cwd: o.cwd, message: o.message, threadId: o.threadId, wait: o.wait, timeoutMs: o.timeoutMs }, 25_000);
const chatGet = (c, o) => c.request({ type: "chat_get", threadId: o.threadId, timeoutMs: o.timeoutMs }, 25_000);
const chatState = (c) => c.request({ type: "chat_state" }, 10_000);
const spawnStop = (c, name) => c.request({ type: "spawn_stop", name }, 10_000);

/** Start a thread (wait:false), bring up the fake worker, return the thread +
 *  worker. The worker registers under the thread's auto-generated session name. */
async function startThreadWithWorker(message) {
  const post = await chatPost(client, { cwd: tmpHome, message, wait: false });
  assert.ok(post.threadId, "must return a thread_id");
  const thread = await waitForThread(post.threadId);
  const worker = await fakeWorker(thread.agentName);
  return { threadId: post.threadId, thread, worker };
}

// ── Tests ──────────────────────────────────────────────────────────────────

test("chat_post with no thread_id spawns a worker and returns a thread_id", async () => {
  const r = await chatPost(client, { cwd: tmpHome, message: "what is 2+2?", wait: false });
  assert.equal(r.type, "ok", `expected ok: ${JSON.stringify(r)}`);
  assert.ok(r.threadId, "must return a thread_id");
  assert.match(r.threadId, /^[0-9a-f-]{30,}$/);
  const thread = await waitForThread(r.threadId);
  assert.ok(thread.agentName, "thread tracks its agent name");
  await spawnStop(client, thread.agentName).catch(() => {});
});

test("chat_get blocks (no polling) then resolves when the reply lands", async () => {
  const { threadId, worker } = await startThreadWithWorker("say hi");
  const q = await worker.waitForMail();
  assert.match(q.body, /say hi/);

  // Start chat_get BEFORE the reply is sent — it must block (pending).
  const getP = chatGet(client, { threadId, timeoutMs: 10_000 });
  // Give the daemon a moment to register the waiter, then reply.
  await new Promise((r) => setTimeout(r, 150));
  await worker.reply(q, "hello from the project agent");
  const get = await getP;
  assert.equal(get.type, "ok", `get failed: ${JSON.stringify(get)}`);
  assert.equal(get.answered, true);
  assert.ok(get.history.length >= 2, "history has the question + reply");
  const last = get.history[get.history.length - 1];
  assert.equal(last.direction, "reply");
  assert.match(last.body, /hello from the project agent/);
  worker.close();
  await spawnStop(client, (await waitForThread(threadId)).agentName).catch(() => {});
});

test("chat_post(wait=true) blocks and returns the agent's answer directly", async () => {
  // Fire chat_post(wait=true) without awaiting; it blocks on registration. The
  // fake worker must register concurrently so the daemon's waitForRegistration
  // resolves. We discover the session name via chat_state, then register.
  const postP = chatPost(client, { cwd: tmpHome, message: "what time is it?", wait: true, timeoutMs: 15_000 });
  // chat_post(wait:true) creates the thread synchronously before awaiting
  // registration, so chat_state reveals the agent name right away. Poll for it.
  let threadId = null;
  let agentName = null;
  const start = Date.now();
  while (Date.now() - start < 5000) {
    const st = await chatState(client);
    // The newest thread with no agentId yet is ours.
    const t = st.threads.find((x) => !x.agentId && x.cwd === tmpHome);
    if (t) { threadId = t.threadId; agentName = t.agentName; break; }
    await new Promise((r) => setTimeout(r, 30));
  }
  assert.ok(threadId, "chat_post(wait:true) must create the thread synchronously");
  assert.ok(agentName, "thread must have an agent name to register under");
  const worker = await fakeWorker(agentName);
  const q = await worker.waitForMail();
  assert.match(q.body, /what time is it/);
  await worker.reply(q, "it is noon");
  const post = await postP;
  assert.equal(post.type, "ok", `post failed: ${JSON.stringify(post)}`);
  assert.equal(post.threadId, threadId);
  assert.ok(post.answer, "wait=true must return the answer");
  assert.match(post.answer, /it is noon/);
  worker.close();
  await spawnStop(client, agentName).catch(() => {});
});

test("multi-turn: a second chat_post on the same thread_id continues the conversation", async () => {
  const { threadId, worker } = await startThreadWithWorker("turn one");
  const q1 = await worker.waitForMail();
  assert.match(q1.body, /turn one/);
  // Reply so the thread is "answered".
  await worker.reply(q1, "answer one");
  const get1 = await chatGet(client, { threadId, timeoutMs: 10_000 });
  assert.equal(get1.answered, true);

  // Turn 2 — reuse the thread_id. The SAME agent should receive the follow-up.
  const post2 = await chatPost(client, { cwd: tmpHome, message: "turn two", threadId, wait: false });
  assert.equal(post2.threadId, threadId, "thread id must be preserved across turns");
  // The follow-up is delivered async after registration (the agent is already
  // live, so registration resolves immediately). The worker's cursor is past q1,
  // so waitForMail resolves the new question.
  const q2 = await worker.waitForMail(8000);
  assert.match(q2.body, /turn two/, "worker must receive the follow-up question");
  await worker.reply(q2, "answer two");
  const get2 = await chatGet(client, { threadId, timeoutMs: 10_000 });
  assert.equal(get2.answered, true);
  assert.ok(get2.history.length >= 4, "history accumulates across turns");
  worker.close();
  await spawnStop(client, (await waitForThread(threadId)).agentName).catch(() => {});
});

test("chat_post with an unknown thread_id errors", async () => {
  const r = await chatPost(client, { cwd: tmpHome, message: "nope", threadId: crypto.randomUUID(), wait: false });
  assert.equal(r.type, "error");
  assert.match(r.message, /unknown thread/);
});

test("chat_post rejects a missing message", async () => {
  const r = await chatPost(client, { cwd: tmpHome, message: "   ", wait: false });
  assert.equal(r.type, "error");
  assert.match(r.message, /message is required/);
});

test("chat_get on an unknown thread errors", async () => {
  const r = await chatGet(client, { threadId: crypto.randomUUID(), timeoutMs: 500 });
  assert.equal(r.type, "error");
  assert.match(r.message, /unknown thread/);
});

test("chat reaper drops a thread once its agent has exited (idle kill backstop)", async () => {
  // The 1h idle kill is time-based (chatIdleMin). For a deterministic test we
  // exercise the reaper's other branch: a thread whose agent is no longer alive
  // (tmux session gone) is dropped on the next reaper tick. This is the same
  // mechanism that cleans up after an idle kill stops the agent.
  const { threadId } = await startThreadWithWorker("will exit");
  const thread = await waitForThread(threadId);
  // Kill the agent out-of-band (simulates the idle reaper stopping it, or a crash).
  await spawnStop(client, thread.agentName);
  // Wait for a couple of reaper ticks (PI_MAIL_CHAT_TICK_MS=100).
  const start = Date.now();
  let gone = false;
  while (Date.now() - start < 3000) {
    const st = await chatState(client);
    if (!st.threads.some((t) => t.threadId === threadId)) { gone = true; break; }
    await new Promise((r) => setTimeout(r, 120));
  }
  assert.ok(gone, "thread must be dropped once its agent is no longer alive");
});
