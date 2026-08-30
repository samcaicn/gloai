#!/usr/bin/env node
/**
 * pi-mail daemon — singleton federation server
 *
 * Manages agent registration, mailboxes, and routing.
 * Communication: newline-delimited JSON over a Unix domain socket.
 *
 * Also hosts an optional HTTP web UI (default port 1994) so a human operator
 * can browse per-agent mail history, see the live federation, and send or
 * broadcast mail as a first-class "human" agent.
 *
 * Lifecycle:
 *   - Spawned by the pi-mail extension when not already running
 *   - Stays alive as long as at least one agent is connected (or forever)
 *   - Gracefully shuts down on SIGTERM / SIGINT, removing the socket file
 *
 * Ping-pong (server-initiated):
 *   - Daemon sends { type: "ping" } every PING_INTERVAL_MS
 *   - Client must respond with { type: "pong" }
 *   - If no pong within the next ping cycle, the connection is terminated
 *
 * Mailbox durability:
 *   - Live agent mailboxes persist through disconnects (reclaim on reconnect)
 *   - A clean unregister clears that agent's live mailbox
 *   - The full message history (for the UI) is persisted to disk and survives
 *     daemon restarts; the human's inbox is derived from that history.
 */

import net from "node:net";
import http from "node:http";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import crypto from "node:crypto";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import {
  AGENT_DIR,
  HUMAN_AGENT_ID,
  HUMAN_AGENT_NAME,
  agents,
  mailboxes,
  messageLog,
  send,
  log,
  agentDisplayName,
  logDelivery,
  deliverMail,
  makeMail,
  resolveTarget,
  sendMail,
  broadcastMail,
  archiveHumanMessage,
  schedulePersist,
  loadHistory,
  flushHistory,
  shellQuote,
} from "./lib/core.mjs";
import {
  BOARD_FILE,
  JIRA_SYNC_INTERVAL_MS,
  DEFAULT_JQL,
  DEFAULT_COLUMNS,
  board,
  boardPersistTimer,
  schedulePersistBoard,
  flushBoard,
  loadBoard,
  jiraCfg,
  findBoardTask,
  findBoardColumn,
  levelFromIssueType,
  taskActivity,
  progressEntriesSince,
  agentGroup,
  groupForName,
  taskGroup,
  canAccessGroup,
  boardState,
  taskLocationLabel,
  taskMailBody,
  notifyAssignee,
  nudgeIdleTasks,
} from "./lib/board.mjs";
import {
  loadSpawn,
  flushSpawn,
  spawnAgent,
  stopAgent,
  spawnState,
  listSpawnDir,
  spawnRegistry,
} from "./lib/spawn.mjs";
import {
  jiraFetch,
  adfToText,
  textToAdf,
  JIRA_FIELDS,
  jiraSearch,
  jiraTransitionTo,
  jiraAddComment,
  jiraCreateIssue,
  jiraUpdateIssue,
  importJiraComments,
  syncBoard,
  boardSyncing,
} from "./lib/jira.mjs";
import {
  boardMove,
  boardAssign,
  boardComment,
  boardProgress,
  boardCreate,
  boardUpdate,
  boardFlag,
  boardSetConfig,
} from "./lib/board-ops.mjs";
import { handleMessage } from "./lib/protocol.mjs";
import { createHttpServer } from "./lib/http.mjs";
import { startMiddleManagerLoop } from "./lib/middle-manager.mjs";
import { startCeoLoop } from "./lib/ceo.mjs";
import { startChatLoop } from "./lib/chat.mjs";

// ── Config (server/UI-only) ────────────────────────────────────────────────

const IS_WINDOWS = process.platform === "win32";
const SOCKET_PATH = IS_WINDOWS ? null : path.join(AGENT_DIR, "mail-daemon.sock");
const PID_FILE = path.join(AGENT_DIR, "mail-daemon.pid");
const LOCK_FILE = path.join(AGENT_DIR, "mail-daemon.lock");
// On Windows, use TCP for local daemon communication (Unix sockets unsupported)
const TCP_HOST = process.env.PI_MAIL_TCP_HOST || "127.0.0.1";
const TCP_PORT = parseInt(process.env.PI_MAIL_TCP_PORT || "1995", 10);

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const UI_HTML_PATH = path.join(__dirname, "ui.html");
const UI_DIR = __dirname;

// Build the HTTP server (REST routes + static UI + WebSocket terminal).
// UI assets are re-read from disk on each request (no boot-time cache) so
// editing HTML/CSS/JS takes effect after a browser refresh — no daemon
// restart needed. See lib/http.mjs (createHttpServer).
const httpServer = createHttpServer({ uiHtmlPath: UI_HTML_PATH, uiDir: UI_DIR });

// HTTP UI bind settings. Override with env vars if needed.
const UI_HOST = process.env.PI_MAIL_UI_HOST || "0.0.0.0";
const UI_PORT = parseInt(process.env.PI_MAIL_UI_PORT || "1994", 10);


// ── Server ────────────────────────────────────────────────────────────────────

// Ensure dirs exist
fs.mkdirSync(AGENT_DIR, { recursive: true });

// Restore history before serving (so the UI shows prior mail immediately)
loadHistory();
loadBoard();
loadSpawn();

// Single-instance guard: if a live daemon already owns the socket, exit
// quietly instead of stealing it. Without this, concurrent spawn attempts
// (e.g. several agents reconnecting at once after a daemon crash) each
// unlink the socket and re-listen, leaving multiple daemons fighting over
// the path — the root cause of the reconnect loop.
//
// The takeover (probe → unlink stale socket → listen) is wrapped in an
// OS-atomic exclusive lock file held for the process lifetime, so two
// concurrent spawns can't both pass the probe and end up running side by
// side. The socket probe remains as a defence-in-depth liveness check.
let lockFd = null;
function acquireInstanceLock() {
  for (let attempt = 0; attempt < 5; attempt++) {
    try {
      // 'wx' = O_CREAT | O_EXCL: atomic create-only; fails if the file exists.
      lockFd = fs.openSync(LOCK_FILE, "wx", 0o600);
      fs.writeFileSync(lockFd, String(process.pid) + "\n");
      return true;
    } catch (e) {
      if (e.code !== "EEXIST") throw e;
      // Lock exists — check whether its owner is still alive.
      let stale = false;
      try {
        const pid = parseInt(fs.readFileSync(LOCK_FILE, "utf8").trim(), 10);
        if (!pid || !pidAlive(pid)) stale = true;
      } catch {
        stale = true;
      }
      if (!stale) return false; // a live daemon holds the lock
      // reap stale lock and retry
      try { fs.unlinkSync(LOCK_FILE); } catch {}
      // Small delay before retry to avoid tight loop
      if (attempt < 4) {
        const start = Date.now();
        while (Date.now() - start < 100) { /* busy wait for ~100ms */ }
      }
    }
  }
  return false;
}

function pidAlive(pid) {
  try {
    process.kill(pid, 0); // throws if no such process
    return true;
  } catch {
    return false;
  }
}

if (!acquireInstanceLock()) {
  log("Another daemon is already running; exiting");
  process.exit(0);
}

// Secondary check: even with the lock, confirm no live listener on the socket.
if (IS_WINDOWS) {
  // TCP probe on Windows
  try {
    await new Promise((resolve, reject) => {
      const probe = net.createConnection(TCP_PORT, TCP_HOST);
      probe.once("connect", () => { probe.destroy(); resolve(); });
      probe.once("error", reject);
    });
    log("Another daemon is already running; exiting");
    process.exit(0);
  } catch {
    // No live daemon — fall through.
  }
} else {
  try {
    await new Promise((resolve, reject) => {
      const probe = net.createConnection(SOCKET_PATH);
      probe.once("connect", () => { probe.destroy(); resolve(); });
      probe.once("error", reject);
    });
    log("Another daemon is already running; exiting");
    process.exit(0);
  } catch {
    // No live daemon — fall through and take over the socket below.
  }
}

// Remove stale socket from previous run (Unix only)
if (!IS_WINDOWS) {
  try {
    fs.unlinkSync(SOCKET_PATH);
  } catch {}
}

const server = net.createServer((socket) => {
  let agentId = null;
  let buf = "";

  socket.setEncoding("utf8");

  socket.on("data", (chunk) => {
    buf += chunk;
    const lines = buf.split("\n");
    buf = lines.pop() ?? "";

    for (const line of lines) {
      if (!line.trim()) continue;
      let msg;
      try {
        msg = JSON.parse(line);
      } catch {
        send(socket, { type: "error", message: "Invalid JSON" });
        continue;
      }

      // register sets the agentId for this connection
      if (msg.type === "register") {
        agentId = msg.agentId;
        handleMessage(agentId, msg, socket);
        continue;
      }

      // pong is a heartbeat response — handle inline, not via handleMessage
      if (msg.type === "pong") {
        if (agentId) {
          const a = agents.get(agentId);
          // Only accept pong from the currently registered socket for this agentId
          if (a && a.conn === socket) {
            a.pongPending = false;
            a.lastSeen = Date.now();
          }
        }
        continue;
      }

      if (!agentId) {
        send(socket, { type: "error", message: "Must register first" });
        continue;
      }

      handleMessage(agentId, msg, socket);
    }
  });

  socket.on("close", () => {
    if (!agentId) return;
    const a = agents.get(agentId);
    if (a && a.conn === socket) {
      clearInterval(a.pingTimer);
      agents.delete(agentId);
      // Mailbox is intentionally preserved for reconnect
      log(`Disconnected: ${a.info.agentName} — mailbox preserved`);
    }
  });

  socket.on("error", (err) => {
    if (err.code !== "ECONNRESET") {
      log(`Socket error: ${err.message}`);
    }
  });
});

if (IS_WINDOWS) {
  server.listen(TCP_PORT, TCP_HOST, () => {
    log(`Listening on TCP ${TCP_HOST}:${TCP_PORT} (PID ${process.pid})`);
    fs.writeFileSync(PID_FILE, String(process.pid), "utf8");
  });
} else {
  server.listen(SOCKET_PATH, () => {
    log(`Listening on ${SOCKET_PATH} (PID ${process.pid})`);
    fs.writeFileSync(PID_FILE, String(process.pid), "utf8");
    try {
      fs.chmodSync(SOCKET_PATH, 0o600); // owner-only
    } catch {}
  });
}

server.on("error", (err) => {
  log(`Fatal: ${err.message}`);
  process.exit(1);
});

// Start the web UI. Non-fatal if it fails (the mail daemon still works).
httpServer.listen(UI_PORT, UI_HOST, () => {
  log(`Mail UI: http://${UI_HOST}:${UI_PORT}`);
});

// Jira pull loop — no-op until Jira is configured.
if (jiraCfg()) syncBoard("startup");
setInterval(() => syncBoard("interval"), JIRA_SYNC_INTERVAL_MS);

// Progress-nudge loop — mails in-progress assignees who haven't posted
// progress in a while. Runs every minute; each task gates itself on its own
// interval.
setInterval(nudgeIdleTasks, 60_000);

// Middle-manager loop — spawns an ephemeral management agent on a schedule
// (default every 30 min, when enabled + favorites non-empty) that reviews the
// board, unblocks workers, and shepherds tasks to Done/Archive. Also reaps
// dead/over-lifetime MM sessions. Disabled by default. See
// lib/middle-manager.mjs.
startMiddleManagerLoop();

// CEO loop — spawns an ephemeral top-tier manager on a schedule (default every
// 120 min, when enabled + favorites non-empty) that reviews the federation and
// spawns middle managers on demand. When `ceoEnabled` is true, the CEO is the
// sole MM spawner (the MM loop above skips its own spawn); the MM reaper still
// runs. Also reaps dead/over-lifetime CEO sessions. Disabled by default. See
// lib/ceo.mjs.
startCeoLoop();

// MCP project chat — installs the mail delivery hook (so blocking chat_get /
// chat_post(wait) resolve the moment a reply lands) and starts the idle reaper
// that kills chat workers after board.config.chatIdleMin (default 60 min) of no
// communication. Chat workers are spawned by the chat_post MCP tool and are
// excluded from the MM worker reaper. See lib/chat.mjs.
startChatLoop();

// ── Graceful shutdown ─────────────────────────────────────────────────────────

function cleanup() {
  log("Shutting down");
  if (!IS_WINDOWS) {
    try {
      fs.unlinkSync(SOCKET_PATH);
    } catch {}
  }
  try {
    fs.unlinkSync(PID_FILE);
  } catch {}
  try {
    if (lockFd != null) { fs.closeSync(lockFd); fs.unlinkSync(LOCK_FILE); }
  } catch {}
  // Flush any pending board write before exiting. (Don't reassign the
  // imported `boardPersistTimer` binding — ESM named imports are read-only
  // in this module; clearTimeout is enough since we exit immediately after.)
  if (boardPersistTimer) {
    clearTimeout(boardPersistTimer);
    flushBoard();
  }
  // Flush any pending spawn registry write before exiting.
  flushSpawn();
  // Flush any pending history write before exiting.
  flushHistory();
  process.exit(0);
}

process.on("SIGTERM", cleanup);
process.on("SIGINT", cleanup);
// Handle uncaught exceptions to avoid zombie lock files
process.on("uncaughtException", (err) => {
  log(`Uncaught exception: ${err.message}`);
  cleanup();
});

// Keep the process alive (it's a daemon)
process.stdin.resume();
