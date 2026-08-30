/**
 * Core shared state + helpers for the pi-mail daemon.
 *
 * Holds the federation's mutable state (live agents, durable mailboxes, the
 * append-only message history) and the mail-routing helpers built on top of it
 * (send, deliverMail, sendMail, broadcastMail, …). Extracted into its own
 * module so the board, Jira, spawn, protocol, and HTTP modules can depend on a
 * single source of truth without circular imports — this module depends on
 * nothing else in the daemon.
 *
 * ESM live bindings: `messageLog` is a `let` rebound by `loadHistory()`; other
 * modules import the binding and always see the current array.
 */

import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import crypto from "node:crypto";
import { notifySSE } from "./sse-events.mjs";

// ── Config (shared paths) ────────────────────────────────────────────────────

export const AGENT_DIR = path.join(os.homedir(), ".pi", "agent");
export const HISTORY_FILE = path.join(AGENT_DIR, "mail-daemon.history.json");

// ── Human agent ──────────────────────────────────────────────────────────────
//
// A fixed, well-known virtual agent so a human operator can send and receive
// mail through the web UI. It has no live socket — its "inbox" is just the
// slice of the message history addressed to this ID.

export const HUMAN_AGENT_ID = "00000000-0000-0000-0000-000000000000";
export const HUMAN_AGENT_NAME = "human";

// ── State ─────────────────────────────────────────────────────────────────────

/**
 * Live agent connections.
 * @type {Map<string, { conn: import("node:net").Socket, info: AgentInfo, pingTimer: NodeJS.Timeout | null, pongPending: boolean, lastSeen: number }>}
 *
 * @typedef {{ agentId: string, agentName: string, registeredAt: number, status: string, contextPct: number | null, model: string, cwd: string, isHuman?: boolean }} AgentInfo
 */
export const agents = new Map();

/**
 * Durable mailboxes (survives disconnects until unregister).
 * @type {Map<string, MailMessage[]>}
 *
 * @typedef {{ id: string, fromId: string, fromName: string, subject: string, body: string, timestamp: number, read: boolean, broadcast?: boolean, newSession?: boolean }} MailMessage
 */
export const mailboxes = new Map();

/**
 * Append-only message history — the single source of truth for the web UI.
 * Each entry is a delivered message enriched with recipient info.
 *
 * @type {Array<MailMessage & { toId: string, toName: string, archived: boolean, broadcastId: string | null }>}
 */
export let messageLog = [];

// ── Persistence ──────────────────────────────────────────────────────────────
//
// The history is small (federation mail is low-volume) so we rewrite the whole
// file, debounced, on each change. This keeps the UI's history across daemon
// restarts (/restart-mail-daemon, crashes, reboots).

let persistTimer = null;
function schedulePersist() {
  if (persistTimer) return;
  persistTimer = setTimeout(() => {
    persistTimer = null;
    try {
      fs.writeFileSync(HISTORY_FILE, JSON.stringify(messageLog));
    } catch (e) {
      log(`persist failed: ${e.message}`);
    }
  }, 300);
}

function loadHistory() {
  try {
    const raw = fs.readFileSync(HISTORY_FILE, "utf8");
    const parsed = JSON.parse(raw);
    messageLog = Array.isArray(parsed) ? parsed : [];
  } catch {
    messageLog = [];
  }
}

/** Flush any pending history write immediately (used on shutdown). */
function flushHistory() {
  if (persistTimer) {
    clearTimeout(persistTimer);
    persistTimer = null;
  }
  try {
    fs.writeFileSync(HISTORY_FILE, JSON.stringify(messageLog));
  } catch {}
}

// ── Helpers ───────────────────────────────────────────────────────────────────

export function send(socket, msg) {
  if (!socket || socket.destroyed) return;
  try {
    socket.write(JSON.stringify(msg) + "\n");
  } catch {}
}

export function log(msg) {
  process.stderr.write(`[pi-mail daemon] ${msg}\n`);
}

export function agentDisplayName(agentId) {
  if (agentId === HUMAN_AGENT_ID) return HUMAN_AGENT_NAME;
  return agents.get(agentId)?.info.agentName ?? agentId;
}

/** Snapshot of every currently-connected agent plus the human virtual agent
 *  (so the human is discoverable via list_agents and the UI). Shared by the
 *  socket protocol's `list_agents` handler and the HTTP federation snapshot
 *  so there is one source of truth. Lives here — core owns `agents` and the
 *  human constants — so neither caller needs to pull in another daemon
 *  module (core must stay dependency-free to avoid circular imports). */
export function federationAgents() {
  const list = Array.from(agents.values()).map((a) => a.info);
  // Always expose the human as a virtual, discoverable agent.
  list.push({
    agentId: HUMAN_AGENT_ID,
    agentName: HUMAN_AGENT_NAME,
    registeredAt: 0,
    status: "human operator",
    contextPct: null,
    cwd: "",
    model: "",
    isHuman: true,
  });
  return list;
}

/** Append a delivered message to the history log (UI source of truth). */
export function logDelivery(message, toAgentId, opts = {}) {
  const entry = {
    ...message,
    toId: toAgentId,
    toName: agentDisplayName(toAgentId),
    archived: false,
    broadcastId: opts.broadcastId ?? null,
  };
  messageLog.push(entry);
  schedulePersist();
}

export function deliverMail(toAgentId, message, opts = {}) {
  // Record in history regardless of recipient (including the human).
  logDelivery(message, toAgentId, opts);

  // Notify delivery hooks (e.g. the MCP chat module's blocking chat_get) so a
  // thread waiter can resolve the moment a matching reply lands. Best-effort:
  // a throwing hook never blocks delivery.
  if (deliveryHooks.length) {
    for (const hook of deliveryHooks) {
      try { hook(message, toAgentId); } catch (e) { log(`delivery hook error: ${e?.message ?? String(e)}`); }
    }
  }

  // The human has no live mailbox or socket — its inbox is the history slice
  // where toId === HUMAN_AGENT_ID && !archived.
  if (toAgentId === HUMAN_AGENT_ID) return;

  let box = mailboxes.get(toAgentId);
  if (!box) {
    box = [];
    mailboxes.set(toAgentId, box);
  }
  box.push(message);

  // Push to live agent — async so the sender's request handler is not blocked
  const agent = agents.get(toAgentId);
  if (agent) {
    setImmediate(() => send(agent.conn, { type: "new_mail", message }));
  }
}

/** Delivery hooks — notified (best-effort) whenever a message is delivered, so a
 *  module (the MCP chat module's blocking chat_get) can react to a reply the
 *  moment it lands without polling. A hook receives (message, toAgentId). */
const deliveryHooks = [];
export function setDeliveryHook(fn) {
  if (typeof fn !== "function") return;
  deliveryHooks.push(fn);
  return () => { const i = deliveryHooks.indexOf(fn); if (i >= 0) deliveryHooks.splice(i, 1); };
}

export function makeMail(fromAgentId, subject, body, extra = {}) {
  const fromName =
    fromAgentId === HUMAN_AGENT_ID
      ? HUMAN_AGENT_NAME
      : agents.get(fromAgentId)?.info.agentName ?? fromAgentId;
  return {
    id: crypto.randomUUID(),
    fromId: fromAgentId,
    fromName,
    subject: subject ?? "(no subject)",
    body: body ?? "",
    timestamp: Date.now(),
    read: false,
    ...extra,
  };
}

/** Resolve a recipient spec (name, full id, or id prefix) to an agentId. */
export function resolveTarget(to) {
  if (!to) return null;
  // Human is always resolvable by name or id.
  if (to === HUMAN_AGENT_ID || to === HUMAN_AGENT_NAME) return HUMAN_AGENT_ID;
  for (const [id, a] of agents) {
    if (id === to || id.startsWith(to) || a.info.agentName === to) {
      return id;
    }
  }
  // Offline agents we still hold a mailbox for.
  for (const [id] of mailboxes) {
    if (id === to || id.startsWith(to)) return id;
  }
  return null;
}

/**
 * Send mail from one agent to another. Shared by the socket protocol handler
 * and the HTTP/UI send path (which sends as the human).
 * @returns {{ messageId?: string, error?: string }}
 */
export function sendMail(fromId, toSpec, subject, body, opts = {}) {
  const targetId = resolveTarget(toSpec);
  if (!targetId) return { error: `Agent '${toSpec}' not found` };
  const mail = makeMail(fromId, subject, body, opts.newSession ? { newSession: true } : {});
  deliverMail(targetId, mail);
  notifySSE("mail-received");
  return { messageId: mail.id };
}

/**
 * Broadcast mail from one agent to all others. The human is included as a
 * recipient whenever the sender is not the human, so the operator sees every
 * broadcast in their inbox.
 * @returns {{ recipients: number, broadcastId: string }}
 */
export function broadcastMail(fromId, subject, body) {
  const broadcastId = crypto.randomUUID();
  let count = 0;
  for (const [id] of agents) {
    if (id === fromId) continue; // don't self-send
    const mail = { ...makeMail(fromId, subject, body), broadcast: true };
    deliverMail(id, mail, { broadcastId });
    count++;
  }
  // Deliver a copy to the human unless the human is the sender.
  if (fromId !== HUMAN_AGENT_ID) {
    const mail = { ...makeMail(fromId, subject, body), broadcast: true };
    deliverMail(HUMAN_AGENT_ID, mail, { broadcastId });
  }
  return { recipients: count, broadcastId };
}

// ── Paginated / filtered message history ───────────────────────────────────
//
// The web UI used to fetch the ENTIRE messageLog every poll (via /api/state).
// messagePage returns a single page of the history, newest-first, with optional
// filtering by archived state and by sender/recipient. Cursor pagination keeps
// the page stable across polls: the cursor encodes the last item's
// `${timestamp}:${id}`; the next page is everything strictly older than that
// point (timestamp desc, id desc for a stable total order on equal timestamps).

const DEFAULT_PAGE_SIZE = 50;
const MAX_PAGE_SIZE = 200;

/** Encode a cursor (opaque to clients) from a history entry. */
function encodeCursor(entry) {
  return Buffer.from(`${entry.timestamp}:${entry.id}`, "utf8").toString("base64url");
}

/** Decode a cursor back to { ts, id }, or null if malformed/empty. */
function decodeCursor(cursor) {
  if (!cursor) return null;
  try {
    const raw = Buffer.from(cursor, "base64url").toString("utf8");
    const sep = raw.lastIndexOf(":");
    if (sep < 0) return null;
    const ts = Number(raw.slice(0, sep));
    const id = raw.slice(sep + 1);
    if (!Number.isFinite(ts) || !id) return null;
    return { ts, id };
  } catch {
    return null;
  }
}

/** Resolve an agent spec (name, full id, or prefix) to an agentId, including
 *  the human. Returns null when unresolvable (so a filter simply matches
 *  nothing instead of erroring). */
function resolveAgentId(spec) {
  if (!spec) return null;
  return resolveTarget(spec);
}

/** Count the human's non-archived inbox (messages addressed to the human that
 *  haven't been archived) — used for the inbox badge in the lean state snapshot. */
export function humanInboxCount() {
  let n = 0;
  for (const m of messageLog) {
    if (m.toId === HUMAN_AGENT_ID && !m.archived) n++;
  }
  return n;
}

/** A single page of the message history, newest-first.
 * @param {{ limit?: number, cursor?: string, archived?: "include"|"exclude"|"only",
 *           to?: string, from?: string, involves?: string }} opts
 * @returns {{ messages: object[], nextCursor: string|null, hasMore: boolean, total: number }} */
export function messagePage(opts = {}) {
  const limit = Math.max(1, Math.min(MAX_PAGE_SIZE, Math.trunc(opts.limit) || DEFAULT_PAGE_SIZE));
  const archived = opts.archived || "include";
  const toId = opts.to ? resolveAgentId(opts.to) : null;
  const fromId = opts.from ? resolveAgentId(opts.from) : null;
  const invId = opts.involves ? resolveAgentId(opts.involves) : null;
  const hasTo = opts.to != null;
  const hasFrom = opts.from != null;
  const hasInv = opts.involves != null;

  // Filter (total reflects the filtered set, not just this page).
  const filtered = messageLog.filter((m) => {
    if (archived === "exclude" && m.archived) return false;
    if (archived === "only" && !m.archived) return false;
    if (hasTo && m.toId !== toId) return false;
    if (hasFrom && m.fromId !== fromId) return false;
    if (hasInv && m.fromId !== invId && m.toId !== invId) return false;
    return true;
  });

  // Stable total order: newest first, id desc to break timestamp ties.
  filtered.sort((a, b) => (b.timestamp - a.timestamp) || (a.id < b.id ? 1 : a.id > b.id ? -1 : 0));

  let start = 0;
  const cursor = decodeCursor(opts.cursor);
  if (cursor) {
    // First item strictly older than the cursor (timestamp desc, id desc).
    start = filtered.findIndex((m) =>
      m.timestamp < cursor.ts || (m.timestamp === cursor.ts && m.id < cursor.id)
    );
    if (start < 0) start = filtered.length;
  }

  const page = filtered.slice(start, start + limit);
  const hasMore = start + limit < filtered.length;
  const nextCursor = hasMore && page.length ? encodeCursor(page[page.length - 1]) : null;

  return { messages: page, nextCursor, hasMore, total: filtered.length };
}

// ── Human inbox operations ───────────────────────────────────────────────────

/** Clear the entire message history (for the "Clear All Mail" feature).
 *  Persists the empty state immediately. Does not touch the board, agent
 *  registry, or spawn history. */
export function clearMailHistory() {
  messageLog = [];
  flushHistory();
}

/** Archive a message addressed to the human (hide from inbox). */
export function archiveHumanMessage(id) {
  if (!id) return false;
  let found = false;
  for (const m of messageLog) {
    if (m.id === id && m.toId === HUMAN_AGENT_ID && !m.archived) {
      m.archived = true;
      found = true;
    }
  }
  if (found) schedulePersist();
  return found;
}

/** Quote a string for safe inclusion in a shell command line. */
export function shellQuote(s) {
  if (s === "") return "''";
  if (/^[A-Za-z0-9_@%+=:,./-]+$/.test(s)) return s;
  return "'" + String(s).replace(/'/g, "'\\''") + "'";
}

export { schedulePersist, loadHistory, flushHistory, startHeartbeat, PING_INTERVAL_MS };

// ── Heartbeat ─────────────────────────────────────────────────────────────────

const PING_INTERVAL_MS = 5_000;

/** Server-initiated ping-pong: send {type:"ping"} every PING_INTERVAL_MS;
 *  if no pong by the next tick, terminate the connection (keeps the agents map
 *  honest). The mailbox is preserved so a reconnecting agent reclaims mail. */
function startHeartbeat(agentId) {
  const agent = agents.get(agentId);
  if (!agent) return;

  const tick = () => {
    const a = agents.get(agentId);
    if (!a) return; // already removed

    if (a.pongPending) {
      log(`${a.info.agentName} (${agentId.slice(0, 8)}) timed out — removing`);
      clearInterval(a.pingTimer);
      a.conn.destroy();
      agents.delete(agentId);
      // Keep mailbox so the agent can reclaim mail on reconnect
      return;
    }

    a.pongPending = true;
    send(a.conn, { type: "ping" });
  };

  agent.pingTimer = setInterval(tick, PING_INTERVAL_MS);
}
