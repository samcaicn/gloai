/**
 * mail_restart_daemon tool + restart helpers for the pi-mail extension.
 *
 * Extracted from mail-tools.ts. The restart logic is debounced/cooldown-guarded
 * at module level so repeated invocations don't tear down multiple daemons, and
 * a query-readiness gate proves a real round-trip succeeds before claiming
 * "reconnected". Registered via registerMailRestartTool(pi, st).
 */
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";
import { readFileSync } from "node:fs";
import { sleep } from "./daemon-bootstrap.js";
import type { MailToolCtx } from "./mail-tools.js";

// Module-level restart state: debounces concurrent / rapid `mail_restart_daemon`
// calls so 4× repeated invocations don't tear down 4 daemons in a row, and a
// cooldown prevents a second restart from killing a daemon that just came up.
let restartInFlight: Promise<boolean> | null = null;
let lastRestartTs = 0;
const RESTART_COOLDOWN_MS = 5_000; // don't kill again within this window

async function triggerRestart(st: MailToolCtx): Promise<boolean> {
  // Cooldown: if a restart just completed, don't kill the (likely freshly
  // spawned) daemon again — just let the caller re-verify connectivity.
  const now = Date.now();
  const withinCooldown = now - lastRestartTs < RESTART_COOLDOWN_MS;
  if (!withinCooldown) {
    lastRestartTs = now;
    const client = st.client;
    if (st.connected && client) {
      // Connected: ask the daemon to restart itself. It replies, then exits
      // gracefully via its SIGTERM handler (flushes everything to disk).
      try { await client.request({ type: "restart_daemon" }); }
      catch { /* socket may close before the reply lands during shutdown */ }
    } else {
      // Not connected: the daemon may be dead or hung. If a process is still
      // alive (hung), signal it to shut down so a fresh one can spawn.
      try {
        const raw = readFileSync(st.pidPath, "utf8").trim();
        const pid = parseInt(raw, 10);
        if (pid > 0) process.kill(pid, "SIGTERM");
      } catch { /* no PID file or process already gone — nothing to signal */ }
    }
    // Wait for our connection to drop (if it hadn't already).
    for (let i = 0; i < 40 && st.connected; i++) await sleep(100);
    // Reconnect (await — resolves even on failure, then we poll). Respawns the
    // daemon if none is alive.
    await st.connectToDaemon().catch(() => {});
  }
  // Wait for the connection to come back (spawn + register takes a moment,
  // and under load with several agents reconnecting it can take a few s).
  for (let i = 0; i < 120 && !st.connected; i++) await sleep(100);
  return st.connected;
}

/** Verify the daemon is query-ready (not just socket-up) by doing a real
 *  round-trip. Retries for a few seconds so we don't report "reconnected"
 *  right before a list/agents/board call would ECONNRESET. */
async function verifyQueryReady(st: MailToolCtx): Promise<boolean> {
  for (let attempt = 0; attempt < 8; attempt++) {
    if (st.connected && st.client) {
      try {
        const r = await st.client.request<{ type: string }>({ type: "list_mail" }, 4_000);
        if (r && r.type === "mail") return true;
      } catch {
        // not ready yet — daemon may still be settling; fall through to retry
      }
    }
    // Not connected yet (triggerRestart's poll may still be racing a reconnect)
    // or the probe failed — nudge the connection and retry.
    if (!st.connected) await st.connectToDaemon().catch(() => {});
    await sleep(250);
  }
  return false;
}

export function registerMailRestartTool(pi: ExtensionAPI, st: MailToolCtx): void {
  pi.registerTool({
    name: "mail_restart_daemon",
    label: "Mail: Restart Daemon",
    description:
      "Restart the shared pi-mail daemon. The daemon shuts down gracefully (flushing mail history, board, and spawn registry to disk) and is automatically respawned; every connected agent briefly disconnects and reconnects. " +
      "Live agent mailboxes are cleared on restart (the persisted message history is not). Returns once this agent has reconnected. " +
      "Use when the daemon is misbehaving or you need a clean process. Affects the whole federation, not just you.",
    promptSnippet: "Restart the mail daemon",
    promptGuidelines: [
      "Use mail_restart_daemon sparingly — it briefly disconnects every agent in the federation.",
    ],
    parameters: Type.Object({}),
    async execute(_id, _params, _signal, _onUpdate, _ctx) {
      // Debounce concurrent calls: if a restart is already in flight, await it
      // instead of triggering a second daemon teardown.
      let attempt: Promise<boolean>;
      if (restartInFlight) {
        attempt = restartInFlight;
      } else {
        attempt = (async () => {
          const connected = await triggerRestart(st);
          if (!connected) return false;
          // Query-readiness gate: prove a real round-trip succeeds before
          // claiming "reconnected", so the next list/agents/board call won't
          // hit ECONNRESET on a half-ready daemon.
          return verifyQueryReady(st);
        })();
        restartInFlight = attempt;
        restartInFlight.finally(() => { restartInFlight = null; });
      }
      const ok = await attempt;
      if (ok) {
        return { content: [{ type: "text" as const, text: "✅ Mail daemon restarted and reconnected (query-ready)" }] };
      }
      return {
        content: [
          { type: "text" as const, text: "🔄 Mail daemon restart triggered; still reconnecting (will keep retrying automatically). If this persists, the daemon may be failing to boot — check ~/.pi/agent/ for a stale socket/pid/lock." },
        ],
      };
    },
  });
}
