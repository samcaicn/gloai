/**
 * MCP project chat for the pi-mail daemon.
 *
 * Lets an MCP client hold a multi-turn chat with a project's spawned agent,
 * with all traffic flowing over pi-mail. Each chat thread spawns a dedicated
 * "chat worker" pi agent (a daemon-spawned tmux session flagged `chat:true`)
 * in the target project cwd; the agent registers with the federation, receives
 * questions as mail from the `human` virtual agent, and replies via mail back
 * to `human`. The MCP surface (chat_post / chat_get) is a request/response
 * shim over that mail traffic.
 *
 * Threading: every message in a thread carries the marker `chat:<threadId>`
 * in its subject, so multi-turn messages group together in the history. Reply
 * correlation is primarily by the thread marker, with a robust fallback to
 * "any mail from the thread's agent to human while a reply is outstanding"
 * (one agent per thread, so unambiguous).
 *
 * Blocking (no polling): chat_get and chat_post(wait=true) block — event-driven
 * via a delivery hook (core.mjs → setDeliveryHook) — until the agent's reply
 * lands, then return. No busy wait, no polling loop.
 *
 * Lifecycle: chat workers are killed after a configurable idle timeout
 * (board.config.chatIdleMin, default 60 min) with no communication. They are
 * excluded from the MM worker reaper so their lifetime is governed by activity,
 * not the fixed worker lifetime. A thread whose agent has exited is reused by
 * re-spawning on the next chat_post to that threadId.
 *
 * This module is pure daemon-side logic; it does not touch the network itself.
 * The HTTP API (extensions/lib/http.mjs → /api/chat/*), the socket protocol
 * (extensions/lib/protocol.mjs → chat_* cases), and the in-process MCP backend
 * (extensions/lib/http-mcp.mjs) all call into chatPost / chatGet here.
 */

import crypto from "node:crypto";
import path from "node:path";
import {
  HUMAN_AGENT_ID,
  HUMAN_AGENT_NAME,
  messageLog,
  sendMail,
  resolveTarget,
  setDeliveryHook,
  log,
} from "./core.mjs";
import { board } from "./board.mjs";
import {
  spawnAgent,
  stopAgent,
  waitForRegistration,
  spawnRegistry,
  tmuxSessionExists,
} from "./spawn.mjs";

// ── Config ───────────────────────────────────────────────────────────────────

/** How often the idle reaper wakes up to check chat threads. */
const CHAT_REAP_TICK_MS = parseInt(process.env.PI_MAIL_CHAT_TICK_MS || "60000", 10);
/** Default idle kill timeout (minutes) when board.config.chatIdleMin is unset. */
const DEFAULT_CHAT_IDLE_MIN = 60;
/** Default per-request wait timeout for chat_get / chat_post(wait=true), in ms. */
const DEFAULT_WAIT_MS = parseInt(process.env.PI_MAIL_CHAT_WAIT_MS || "300000", 10);
/** How long to wait for a freshly-spawned chat worker to register. */
const CHAT_REGISTER_TIMEOUT_MS = parseInt(process.env.PI_MAIL_CHAT_REGISTER_TIMEOUT || "30000", 10);

/** Subject marker prefix for chat-thread messages: `chat:<threadId>`. */
const CHAT_MARKER = (threadId) => `chat:${threadId}`;

// ── Thread registry ──────────────────────────────────────────────────────────

/**
 * @type {Map<string, { threadId: string, agentName: string, agentId: string, cwd: string, createdAt: number, lastActivity: number, pendingSpawn?: boolean }>}
 * In-memory only (chat threads are ephemeral). An agent whose tmux session has
 * died is re-spawned lazily on the next chat_post to its threadId.
 * `pendingSpawn`: true while an async spawn is in flight (wait:false path);
 * the reaper skips these threads so they aren't killed before the agent registers.
 */
const threads = new Map();

// ── Reply waiters (blocking chat_get / chat_post wait) ───────────────────────
//
// One waiter per thread that has an outstanding question (no reply yet). The
// delivery hook resolves the waiter the moment a matching reply lands, so
// chat_get / chat_post(wait=true) block WITHOUT polling.

/** @type {Map<string, { resolve: (msgs: ChatMessage[]) => void, timer: NodeJS.Timeout }>} */
const waiters = new Map();

let deliveryHookRemover = null;

/** Install the delivery hook that resolves reply waiters. Called once at boot. */
function initChat() {
  if (deliveryHookRemover) return;
  deliveryHookRemover = setDeliveryHook(onDelivery);
}

/** Delivery hook: when a message lands, check whether it resolves an
 *  outstanding chat-thread waiter. Best-effort; never throws. */
function onDelivery(message, toAgentId) {
  // A reply is a message delivered TO the human (the MCP client side) from a
  // chat worker. Correlate by subject marker first, then by fromId === thread
  // agent (one agent per thread, so unambiguous).
  const threadId = threadIdForMessage(message);
  let thread = threadId ? threads.get(threadId) : null;
  if (!thread) {
    // Fallback: match by sender — is the fromId a known chat-thread agent?
    for (const t of threads.values()) {
      if (t.agentId && message.fromId === t.agentId && toAgentId === HUMAN_AGENT_ID) {
        thread = t;
        break;
      }
    }
  }
  if (!thread) return;
  const waiter = waiters.get(thread.threadId);
  if (!waiter) return;
  // Only resolve when the latest message in the thread IS a reply (from the
  // agent to human). The just-delivered message qualifies.
  const history = threadHistory(thread.threadId);
  const last = history[history.length - 1];
  if (!last || last.direction !== "reply") return;
  waiters.delete(thread.threadId);
  clearTimeout(waiter.timer);
  thread.lastActivity = Date.now();
  waiter.resolve(history);
}

/** Extract a thread id from a message subject (`chat:<threadId> ...`), or null. */
function threadIdForMessage(message) {
  const s = message?.subject || "";
  const m = s.match(/chat:([0-9a-fA-F-]{6,})/);
  return m ? m[1] : null;
}

// ── History ─────────────────────────────────────────────────────────────────

/**
 * @typedef {{ id: string, direction: "question"|"reply", from: string, to: string, subject: string, body: string, timestamp: number }} ChatMessage
 */

/** The thread's message history (oldest-first), normalized to {direction,...}.
 *  A "question" is human→agent; a "reply" is agent→human. Correlation: subject
 *  marker, with a fromId/toId fallback for the thread's known agent. */
function threadHistory(threadId) {
  const thread = threads.get(threadId);
  if (!thread) return [];
  const marker = CHAT_MARKER(threadId);
  const out = [];
  for (const m of messageLog) {
    const isQuestion = m.fromId === HUMAN_AGENT_ID && (m.toId === thread.agentId || m.toName === thread.agentName);
    const isReply = m.toId === HUMAN_AGENT_ID && (m.fromId === thread.agentId || m.fromName === thread.agentName);
    if (!isQuestion && !isReply) {
      // Also accept any message carrying the thread marker subject.
      if (typeof m.subject === "string" && m.subject.includes(marker)) {
        // fall through; classify by direction below
      } else {
        continue;
      }
    }
    out.push({
      id: m.id,
      direction: isReply ? "reply" : "question",
      from: m.fromName,
      to: m.toName,
      subject: m.subject,
      body: m.body,
      timestamp: m.timestamp,
    });
  }
  out.sort((a, b) => a.timestamp - b.timestamp || (a.id < b.id ? -1 : 1));
  return out;
}

/** Is the latest message in the thread a reply (agent answered)? */
function threadAnswered(threadId) {
  const h = threadHistory(threadId);
  return h.length > 0 && h[h.length - 1].direction === "reply";
}

// ── Agent lifecycle ─────────────────────────────────────────────────────────

/** Build a chat-worker session name for a thread + cwd. */
function chatSessionName(threadId, cwd) {
  const base = path.basename(cwd) || "chat";
  return `chat-${base}-${threadId.slice(0, 8)}`;
}

/** Is the thread's agent still alive (tmux session present + tracked)? */
function threadAgentAlive(thread) {
  if (!thread?.agentName) return false;
  return !!spawnRegistry.sessions[thread.agentName] && tmuxSessionExists(thread.agentName);
}

/** Spawn (or re-spawn) the chat worker for a thread, waiting for it to
 *  register. Stamps agentId on the thread. Returns { ok } or { error }. */
async function ensureThreadAgent(thread) {
  if (threadAgentAlive(thread) && thread.agentId) {
    // Already live — update lastActivity and return.
    thread.lastActivity = Date.now();
    return { ok: true };
  }
  // Clean up any stale registry entry for the old name, then spawn fresh.
  if (thread.agentName && spawnRegistry.sessions[thread.agentName]) {
    stopAgent({ name: thread.agentName });
  }
  const name = chatSessionName(thread.threadId, thread.cwd);
  thread.agentName = name;
  const r = spawnAgent({ cwd: thread.cwd, name, chat: true });
  if (r.error) return { error: r.error };
  const agentId = await waitForRegistration(name, CHAT_REGISTER_TIMEOUT_MS);
  if (!agentId) return { error: `chat worker '${name}' did not register within ${CHAT_REGISTER_TIMEOUT_MS}ms` };
  thread.agentId = agentId;
  thread.lastActivity = Date.now();
  // Persist the agentId stamp on the spawn registry entry too.
  if (spawnRegistry.sessions[name]) {
    spawnRegistry.sessions[name].agentId = agentId;
  }
  return { ok: true };
}

// ── Public API: chatPost ────────────────────────────────────────────────────

/**
 * Post a question to a project's chat agent.
 *
 * - No `threadId`: starts a new thread — spawns a chat worker for `cwd`,
 *   returns a new `threadId`. The first question is delivered as a fresh
 *   newSession task so the agent starts on it.
 * - Existing `threadId`: reuses the thread's agent (re-spawning if it died),
 *   delivers the question as a continuation (non-newSession).
 *
 * When `wait` is true (default), blocks until the agent replies and returns
 * the answer + threadId. When false, returns the threadId immediately.
 *
 * @param {{ cwd: string, message: string, threadId?: string, wait?: boolean, timeoutMs?: number }} opts
 * @returns {Promise<{ threadId: string, answer?: string, history?: ChatMessage[], error?: string }>}
 */
export async function chatPost({ cwd, message, threadId, wait = true, timeoutMs }) {
  if (!cwd) return { error: "cwd (project directory) is required — use list_projects to discover available project paths" };
  if (!message || !String(message).trim()) return { error: "message is required" };

  let thread;
  let isNew = false;
  if (threadId) {
    thread = threads.get(threadId);
    if (!thread) return { error: `unknown thread: ${threadId}` };
  } else {
    threadId = crypto.randomUUID();
    const name = chatSessionName(threadId, cwd);
    thread = { threadId, agentName: name, agentId: "", cwd, createdAt: Date.now(), lastActivity: Date.now() };
    threads.set(threadId, thread);
    isNew = true;
  }

  // Ensure the agent is live before we send (spawns / re-spawns as needed).
  // For wait=false we don't block on registration — kick off the spawn +
  // best-effort delivery asynchronously and return the threadId immediately so
  // the MCP client can fetch the answer later with chat_get.
  if (!wait) {
    // The thread + agentName are set synchronously, so chat_get can find it.
    // pendingSpawn flag prevents the idle reaper from killing the thread
    // before the agent registers (async spawn in flight).
    thread.pendingSpawn = true;
    ensureThreadAgent(thread)
      .then((up) => {
        thread.pendingSpawn = false;
        if (up.error) return;
        const subject = `${CHAT_MARKER(threadId)} question`;
        const body = isNew ? firstTurnBody(threadId, cwd, message) : continuationBody(threadId, message);
        sendMail(HUMAN_AGENT_ID, thread.agentName, subject, body, { newSession: isNew });
        thread.lastActivity = Date.now();
      })
      .catch((e) => {
        thread.pendingSpawn = false;
        log(`chat_post async delivery error: ${e?.message ?? String(e)}`);
      });
    return { threadId };
  }

  const up = await ensureThreadAgent(thread);
  if (up.error) return { error: up.error };

  // Deliver the question as mail from human → agent, subject carrying the
  // thread marker. New thread → newSession task (agent starts on it); existing
  // thread → a steering continuation message.
  const subject = `${CHAT_MARKER(threadId)} question`;
  const body = isNew ? firstTurnBody(threadId, cwd, message) : continuationBody(threadId, message);
  const r = sendMail(HUMAN_AGENT_ID, thread.agentName, subject, body, { newSession: isNew });
  if (r.error) return { error: r.error };
  thread.lastActivity = Date.now();

  // Block until the agent replies (or timeout).
  const history = await waitForReply(threadId, timeoutMs);
  if (!history) return { threadId, error: "timed out waiting for the agent's reply" };
  const lastReply = [...history].reverse().find((m) => m.direction === "reply");
  return { threadId, answer: lastReply?.body ?? "", history };
}

// ── Public API: chatGet ────────────────────────────────────────────────────

/**
 * Get the mail history for a chat thread. Blocks (non-busy, event-driven via
 * the delivery hook) until the LAST message in the thread is a reply from the
 * agent — so no polling: the caller waits only when an answer is pending, and
 * resolves the moment it lands. If the thread is already answered, returns
 * immediately.
 *
 * @param {{ threadId: string, timeoutMs?: number }} opts
 * @returns {Promise<{ threadId: string, history: ChatMessage[], answered: boolean, error?: string }>}
 */
export async function chatGet({ threadId, timeoutMs }) {
  const thread = threads.get(threadId);
  if (!thread) return { error: `unknown thread: ${threadId}` };
  if (!threadAnswered(threadId)) {
    const history = await waitForReply(threadId, timeoutMs);
    if (!history) {
      return { threadId, history: threadHistory(threadId), answered: false, error: "timed out waiting for the agent's reply" };
    }
    return { threadId, history, answered: true };
  }
  return { threadId, history: threadHistory(threadId), answered: true };
}

// ── Blocking wait primitive ────────────────────────────────────────────────

/** Block until a reply lands for the thread (delivery hook resolves), or the
 *  timeout elapses. Returns the full thread history (with the new reply) on
 *  resolve, or null on timeout. If already answered, resolves immediately. */
function waitForReply(threadId, timeoutMs = DEFAULT_WAIT_MS) {
  return new Promise((resolve) => {
    if (threadAnswered(threadId)) {
      resolve(threadHistory(threadId));
      return;
    }
    if (waiters.has(threadId)) {
      // A waiter already exists (e.g. a concurrent chat_get). Resolve both by
      // chaining: replace the waiter with one that resolves the new caller too.
      const prev = waiters.get(threadId);
      waiters.set(threadId, {
        resolve: (msgs) => { prev.resolve(msgs); resolve(msgs); },
        timer: prev.timer,
      });
      return;
    }
    const timer = setTimeout(() => {
      if (waiters.has(threadId)) {
        waiters.delete(threadId);
        resolve(null);
      }
    }, timeoutMs);
    waiters.set(threadId, { resolve, timer });
  });
}

// ── Message body builders ───────────────────────────────────────────────────

function firstTurnBody(threadId, cwd, message) {
  return [
    "You are a chat worker for the project at " + cwd + ".",
    "An MCP client is asking you questions about this project; your replies go back to the client over pi-mail.",
    "",
    "## For broad/exploratory questions (e.g. architectural overview):",
    "- Start with a focused read of key files (package.json, README, main entry point, top-level dir listing).",
    "- Build your answer from what you find in those key files — do NOT scan the entire repo.",
    "- If the answer would require reading many files, reply with what you've found so far rather than trying to read everything.",
    "- The MCP client can ask follow-up questions for deeper detail.",
    "",
    "## For narrow/specific questions:",
    "- Read only the relevant files and answer directly.",
    "",
    "When you have an answer, reply via `mail_send` to \"human\" with subject \"chat:" + threadId + "\" and your answer in the body. Keep replies focused and concise. Do NOT use ask_user_question — the client is not at a TUI; it only sees your mailed reply.",
    "",
    "────────────── question ──────────────",
    message,
  ].join("\n");
}

function continuationBody(threadId, message) {
  return [
    "chat:" + threadId,
    "",
    message,
  ].join("\n");
}

// ── Idle reaper ─────────────────────────────────────────────────────────────

let reapTimer = null;

/** Reap chat threads whose agent has been idle longer than the configured idle
 *  timeout (board.config.chatIdleMin, default 60 min). Stops the agent and
 *  removes the thread. Also cleans up threads whose agent has exited. */
function reapIdleChat(now = Date.now()) {
  const idleMs = Math.max(1, (board.config.chatIdleMin ?? DEFAULT_CHAT_IDLE_MIN)) * 60_000;
  for (const [threadId, t] of [...threads]) {
    // Skip threads waiting for async agent spawn (wait:false path).
    if (t.pendingSpawn) continue;
    const alive = threadAgentAlive(t);
    const idleFor = now - (t.lastActivity ?? now);
    if (!alive) {
      // Agent exited (reaped by operator, crashed, or never came up). Drop the
      // thread so a new chat_post with a fresh threadId spawns fresh.
      threads.delete(threadId);
      waiters.delete(threadId);
      log(`chat reaper: dropped dead thread ${threadId.slice(0, 8)} (agent '${t.agentName}')`);
      continue;
    }
    if (idleFor > idleMs) {
      const r = stopAgent({ name: t.agentName });
      if (r.error) log(`chat reaper: could not stop '${t.agentName}': ${r.error}`);
      else log(`chat reaper: stopped idle chat worker '${t.agentName}' (${Math.round(idleFor / 60000)}m idle)`);
      threads.delete(threadId);
      waiters.delete(threadId);
    }
  }
}

/** Start the idle reaper loop. Called once from daemon.mjs at boot. Also
 *  installs the delivery hook (initChat). */
function startChatLoop() {
  initChat();
  if (reapTimer) clearInterval(reapTimer);
  reapTimer = setInterval(() => {
    try { reapIdleChat(); } catch (e) { log(`chat reaper error: ${e?.message ?? String(e)}`); }
  }, CHAT_REAP_TICK_MS);
}

// ── Snapshot (for diagnostics / UI / tests) ─────────────────────────────────

function chatState() {
  const now = Date.now();
  const idleMs = Math.max(1, (board.config.chatIdleMin ?? DEFAULT_CHAT_IDLE_MIN)) * 60_000;
  return {
    chatIdleMin: board.config.chatIdleMin ?? DEFAULT_CHAT_IDLE_MIN,
    threads: [...threads.values()].map((t) => ({
      threadId: t.threadId,
      agentName: t.agentName,
      agentId: t.agentId,
      cwd: t.cwd,
      createdAt: t.createdAt,
      lastActivity: t.lastActivity,
      idleForMin: Math.round((now - (t.lastActivity ?? now)) / 60000),
      alive: threadAgentAlive(t),
      answered: threadAnswered(t.threadId),
      idleInMin: Math.max(0, Math.round((idleMs - (now - (t.lastActivity ?? now))) / 60000)),
    })),
  };
}

export {
  initChat,
  startChatLoop,
  reapIdleChat,
  chatState,
  threadHistory,
  CHAT_MARKER,
};
