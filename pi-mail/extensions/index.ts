/**
 * pi-mail — federated agent mail extension.
 *
 * Registers each pi process as an agent in a shared mail federation; a singleton
 * daemon (daemon.mjs) is auto-started when needed. Unread mail is injected as
 * context at the start of each turn; the status bar shows the unread count.
 * Clean exit unregisters + clears mailbox; crashes preserve it for reconnect.
 *
 * Commands: /mail-name [name], /mail-status.
 * Tools: mail_list, mail_read, mail_send, mail_broadcast, mail_mark_read,
 *        mail_list_agents, mail_restart_daemon (board + spawn tools in lib/).
 */

import type { ExtensionAPI, ExtensionContext } from "@earendil-works/pi-coding-agent";
import { matchesKey, Key } from "@earendil-works/pi-tui";
import { Type } from "typebox";
import { homedir } from "node:os";
import { join, basename } from "node:path";
import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { MailClient, projectGroupKey } from "./lib/mail-client.js";
import { ensureDaemonAndConnect, isDaemonAlive, sleep } from "./lib/daemon-bootstrap.js";
import { registerCommands } from "./lib/commands.js";
import { registerMailTools } from "./lib/mail-tools.js";
import { registerBoardReadTools } from "./lib/board-read-tools.js";
import { registerBoardTools } from "./lib/board-tools.js";
import { registerSpawnTools } from "./lib/spawn-tools.js";
import { buildBeforeStartGuidance, formatIncomingMailContent, renderMailStatus } from "./lib/mail-injection.js";
import type { MailMessage } from "./lib/mail-client.js";

// jiti provides __dirname for directory-based extensions
declare const __dirname: string;

// ── Config ────────────────────────────────────────────────────────────────────

const SOCKET_PATH = join(homedir(), ".pi", "agent", "mail-daemon.sock");
const PID_PATH = join(homedir(), ".pi", "agent", "mail-daemon.pid");
const DAEMON_SCRIPT = join(__dirname, "daemon.mjs");

// ── Extension ─────────────────────────────────────────────────────────────────

export default function (pi: ExtensionAPI) {
  let client: MailClient | null = null;
  let agentId = randomUUID();  // may be overwritten from session entries in session_start
  let agentName = `${basename(process.cwd()) || "pi-agent"}-${agentId.slice(0, 6)}`;
  // Fixed per process — the directory pi was launched in (the "project").
  const agentCwd = process.cwd();
  let agentStatus = "";
  /** Active model string, e.g. "anthropic/claude-sonnet-4". Updated via model_select event. */
  let agentModel = "";
  // True once the agent/user has chosen an explicit name (vs. the auto slug)
  let nameCustomized = false;
  // Track whether agentId was restored from a previous session (prevents double-counting on reload)
  let agentIdRestored = false;
  /**
   * Who dispatched the task the agent is currently working on, when it arrived
   * via mail (from the human operator or another agent). `null` means the
   * operator is driving directly over the TUI. Drives the "channel" guidance
   * injected in before_agent_start, so the agent knows whether to reply via
   * mail or respond in place. Set when a mail triggers a turn; cleared when the
   * operator types directly in the TUI (see the `input` handler).
   */
  let mailTaskSender: { name: string; id: string } | null = null;
  let mailbox: MailMessage[] = [];
  let connected = false;
  // Reconnect driver: exponential backoff that keeps retrying instead of
  // giving up after a single failed attempt.
  let suppressReconnect = false;
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  let reconnectAttempts = 0;
  const RECONNECT_BASE_MS = 1_000;
  const RECONNECT_MAX_MS = 30_000;
  // Ensures only one in-flight connectToDaemon() at a time per process instance
  let connectingPromise: Promise<void> | null = null;

  // Stored so we can update the status bar from async callbacks
  let latestCtx: ExtensionContext | null = null;

  // Live (getter/setter-backed) state alias passed to the extracted command +
  // tool modules (lib/commands, lib/mail-tools, lib/board-tools). Getters+setters
  // over the `let` vars above so inline code (bare `agentName`) and the
  // extracted modules (st.agentName) always read/write the SAME bindings.
  const st = {
    get client() { return client; }, set client(v) { client = v; },
    get connected() { return connected; }, set connected(v) { connected = v; },
    get agentId() { return agentId; },
    get agentName() { return agentName; }, set agentName(v) { agentName = v; },
    get agentStatus() { return agentStatus; }, set agentStatus(v) { agentStatus = v; },
    get agentModel() { return agentModel; },
    get agentCwd() { return agentCwd; },
    get nameCustomized() { return nameCustomized; }, set nameCustomized(v) { nameCustomized = v; },
    get mailbox() { return mailbox; }, set mailbox(v) { mailbox = v; },
    get suppressReconnect() { return suppressReconnect; }, set suppressReconnect(v) { suppressReconnect = v; },
    get latestCtx() { return latestCtx; }, set latestCtx(v) { latestCtx = v; },
    get updateStatus() { return updateStatus; },
    get connectToDaemon() { return connectToDaemon; },
    get clearReconnect() { return clearReconnect; },
    notConnected: { content: [{ type: "text" as const, text: "❌ Not connected to mail daemon" }] },
    pidPath: PID_PATH,
  };

  // ── Status bar helper ───────────────────────────────────────────────────────

  function updateStatus(ctx?: ExtensionContext | null): void {
    const c = ctx ?? latestCtx;
    if (!c) return;
    renderMailStatus(c, { connected, mailbox, agentName });
  }

  // ── Connection management ───────────────────────────────────────────────────

  async function connectToDaemon(): Promise<void> {
    // Singleton guard: already live → nothing to do
    if (client?.connected) return;
    // In-flight guard: coalesce concurrent callers onto the same attempt
    if (connectingPromise) return connectingPromise;

    connectingPromise = _connectToDaemon().finally(() => {
      connectingPromise = null;
    });
    return connectingPromise;
  }

  function clearReconnect(): void {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
  }

  /**
   * Auto-reconnect with exponential backoff. Keeps retrying (rather than
   * giving up after one attempt) so a daemon that takes a while to come back
   * is recovered automatically. No-op while an intentional disconnect is
   * in progress or a retry is already pending.
   */
  function scheduleReconnect(): void {
    if (suppressReconnect) return;
    if (reconnectTimer) return; // already pending
    const backoff = Math.min(
      RECONNECT_MAX_MS,
      RECONNECT_BASE_MS * 2 ** Math.min(reconnectAttempts, 5)
    );
    reconnectAttempts++;
    reconnectTimer = setTimeout(async () => {
      reconnectTimer = null;
      await connectToDaemon().catch(() => {});
      // If the attempt failed (or the new connection dropped immediately),
      // keep trying with further backoff.
      if (!connected && !suppressReconnect) scheduleReconnect();
    }, backoff);
  }

  async function _connectToDaemon(): Promise<void> {
    try {
      client = await ensureDaemonAndConnect(SOCKET_PATH, DAEMON_SCRIPT, PID_PATH);

      client.onNewMail = (msg) => {
        if (mailbox.some((m) => m.id === msg.id)) return; // already known
        mailbox.push(msg);
        updateStatus();
        // Remember who dispatched this task so the agent knows to reply via
        // mail (not via ask_user_question / the TUI). Cleared when the operator
        // types directly in the TUI again (see the `input` handler).
        mailTaskSender = { name: msg.fromName, id: msg.fromId };
        try {
          // newSession flag: orchestrator wants a fresh session before this task
          if (msg.newSession) {
            // Archive immediately so it doesn't linger in the inbox
            client?.request({ type: "mark_read", messageId: msg.id }).catch(() => {});
            mailbox = mailbox.filter((m) => m.id !== msg.id);
            updateStatus();
            // Queue /new-task as a follow-up (waits for agent to become idle first)
            const kickoff = msg.body?.trim() || "";
            pi.sendUserMessage(`/new-task ${kickoff}`.trimEnd(), { deliverAs: "followUp" });
            return;
          }

          const content = formatIncomingMailContent(msg);
          pi.sendMessage(
            { customType: "pi-mail", content, display: true },
            { deliverAs: "steer", triggerTurn: true }
          );
        } catch {
          // Agent may not be running; ignore
        }
      };

      client.onDisconnect = () => {
        connected = false;
        client = null;
        updateStatus();
        scheduleReconnect();
      };

      // Daemon push: an assigned task requires a specific model. Resolve the
      // "provider/slug" string to a Model and switch to it (best-effort — an
      // unknown model or missing API key leaves the current model untouched).
      client.onSetModel = (modelStr: string) => {
        try {
          const slash = (modelStr || "").indexOf("/");
          if (slash < 0) return;
          const provider = modelStr.slice(0, slash);
          const modelId = modelStr.slice(slash + 1);
          const ctx = latestCtx;
          const model = ctx?.modelRegistry?.find(provider, modelId);
          if (!model) return;
          // Fire-and-forget: the model applies to the next agent turn.
          pi.setModel(model).catch(() => {});
        } catch {
          // best-effort model switch; ignore failures
        }
      };

      // Register with the daemon
      const resp = await client.request<{ type: string; agentId?: string }>({
        type: "register",
        agentId,
        agentName,
        cwd: agentCwd,
        model: agentModel,
      });

      if (resp.type === "registered") {
        connected = true;
        // Stable connection — reset the backoff state.
        reconnectAttempts = 0;
        clearReconnect();
        // Flush any messages that were buffered while we were disconnected
        client.flushWriteQueue();
        // Restore status and model on the daemon side after (re)connecting
        if (agentStatus) {
          try {
            await client.request({ type: "set_status", status: agentStatus });
          } catch {}
        }
        if (agentModel) {
          try {
            await client.request({ type: "set_model", model: agentModel });
          } catch {}
        }
        // Load any pending mail (e.g. from a previous session or offline delivery)
        const mailResp = await client.request<{ type: string; messages?: MailMessage[] }>({
          type: "list_mail",
        });
        if (mailResp.type === "mail" && mailResp.messages) {
          mailbox = mailResp.messages;

          // Process any newSession messages that arrived while we were offline.
          // These were never seen by onNewMail (push path), so handle them now.
          // Use the last one if there are multiple (most recent task wins).
          const newSessionMsgs = mailResp.messages.filter((m) => m.newSession);
          if (newSessionMsgs.length > 0) {
            const msg = newSessionMsgs[newSessionMsgs.length - 1];
            // Archive all newSession messages so they don't re-trigger
            for (const m of newSessionMsgs) {
              client?.request({ type: "mark_read", messageId: m.id }).catch(() => {});
            }
            mailbox = mailbox.filter((m) => !m.newSession);
            mailTaskSender = { name: msg.fromName, id: msg.fromId };
            const kickoff = msg.body?.trim() || "";
            pi.sendUserMessage(`/new-task ${kickoff}`.trimEnd(), { deliverAs: "followUp" });
          }
        }
        updateStatus();
      }
    } catch {
      connected = false;
      client = null;
      // Start (or continue) the backoff retry loop so we recover once the
      // daemon is back, instead of staying offline forever after one failure.
      scheduleReconnect();
    }
  }

  async function disconnectFromDaemon(cleanExit: boolean): Promise<void> {
    // Intentional disconnect — don't let onDisconnect trigger auto-reconnect.
    suppressReconnect = true;
    clearReconnect();
    if (client && connected && cleanExit) {
      try {
        await client.request({ type: "unregister", agentId });
      } catch {}
    }
    client?.disconnect();
    client = null;
    connected = false;
    if (cleanExit) mailbox = [];
  }

  // ── Session lifecycle ───────────────────────────────────────────────────────

  pi.on("session_start", async (_event, ctx) => {
    latestCtx = ctx;

    // Restore agent id, name and status from session entries
    for (const entry of ctx.sessionManager.getEntries()) {
      if (entry.type === "custom" && entry.customType === "pi-mail-id") {
        const data = entry.data as { agentId?: string } | undefined;
        if (data?.agentId) {
          agentId = data.agentId;
          agentIdRestored = true;
        }
      }
      if (entry.type === "custom" && entry.customType === "pi-mail-name") {
        const data = entry.data as { name?: string } | undefined;
        if (data?.name) {
          agentName = data.name;
          nameCustomized = true;
        }
        // Take the last stored name
      }
      if (entry.type === "custom" && entry.customType === "pi-mail-status") {
        const data = entry.data as { status?: string } | undefined;
        if (typeof data?.status === "string") agentStatus = data.status;
        // Take the last stored status
      }
    }

    // Persist agentId once (first session only — not on reload)
    if (!agentIdRestored) {
      pi.appendEntry("pi-mail-id", { agentId });
    }

    // If pi was launched with a session name (`pi -n <name>`, e.g. by the
    // mail_spawn_agent / spawn flow), adopt it as the agent display name so the
    // registered name matches the tmux session name. Without this the extension
    // would register under its own auto-slug (`<dir>-<ownUUID6>`, a DIFFERENT
    // uuid than the daemon's), so the daemon could never link the registered
    // agent back to the tmux session — breaking kickoff delivery and the UI's
    // Terminal/Stop linkage. Only when the name hasn't been customized via
    // mail_set_name (so an explicit name still wins on reload).
    if (!nameCustomized) {
      const sessionName = pi.getSessionName();
      if (sessionName) agentName = sessionName;
    }

    await connectToDaemon();
    updateStatus(ctx);
  });

  pi.on("session_shutdown", async (_event, _ctx) => {
    // Clear latestCtx BEFORE disconnecting. The socket 'close' event fires
    // asynchronously after socket.destroy(), which would call onDisconnect ->
    // updateStatus() with the now-stale ctx, causing an uncaughtException.
    latestCtx = null;
    await disconnectFromDaemon(true);
  });

  // ── Mail injection ──────────────────────────────────────────────────────────

  // When the operator types directly in the TUI, the current task is no
  // longer mail-driven — clear the channel marker so before_agent_start tells
  // the agent to reply in place (and that ask_user_question is fine again).
  // Extension-injected messages (mail kickoffs, /new-task) keep the marker.
  pi.on("input", async (event, _ctx) => {
    if (event.source === "interactive") {
      mailTaskSender = null;
    }
    return { action: "continue" };
  });

  pi.on("before_agent_start", async (event, ctx) => {
    latestCtx = ctx;
    updateStatus(ctx);

    if (!connected) return;

    const unread = mailbox.filter((m) => !m.read);
    return buildBeforeStartGuidance(event.systemPrompt, {
      agentName, nameCustomized, agentStatus, mailTaskSender, unread,
    });
  });

  // Keep agentModel in sync whenever the model changes
  pi.on("model_select", async (event, _ctx) => {
    agentModel = `${event.model.provider}/${event.model.id}`;
    if (client && connected) {
      client.fire({ type: "set_model", model: agentModel });
    }
  });

  // Track ctx for status updates during turns
  pi.on("turn_start", async (_event, ctx) => {
    latestCtx = ctx;
  });

  // Push context saturation to daemon after each LLM turn
  pi.on("turn_end", async (_event, ctx) => {
    latestCtx = ctx;
    if (!connected || !client) return;
    const usage = ctx.getContextUsage();
    const pct = usage?.percent != null ? Math.round(usage.percent) : null;
    client.fire({ type: "set_context", pct });
  });

  // Slash commands, mail tools, and board/spawn tools are extracted into
  // lib/commands.ts, lib/mail-tools.ts, lib/board-tools.ts. All share this
  // live client/connection state (st is getter-backed over the closure vars).
  registerCommands(pi, st);
  registerMailTools(pi, st);
  registerBoardReadTools(pi, st);
  registerBoardTools(pi, st);
  registerSpawnTools(pi, st);
}
