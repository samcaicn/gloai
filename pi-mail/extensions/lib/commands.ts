/**
 * Slash-command registrations for the pi-mail extension.
 * Extracted from index.ts. Registered via registerCommands(pi, st) where st is
 * the live (getter-backed) state object from the extension closure.
 */
import type { ExtensionAPI, ExtensionContext } from "@earendil-works/pi-coding-agent";
import { matchesKey, Key } from "@earendil-works/pi-tui";
import { readFileSync } from "node:fs";
import type { MailClient, MailMessage, AgentInfo } from "./mail-client.js";
import { projectGroupKey } from "./mail-client.js";
import { isDaemonAlive, sleep } from "./daemon-bootstrap.js";
export interface CommandCtx { client: MailClient | null; connected: boolean; agentId: string; agentName: string; mailbox: MailMessage[]; notConnected: { content: { type: "text"; text: string }[] }; updateStatus: (ctx?: ExtensionContext | null) => void; connectToDaemon: () => Promise<void>; clearReconnect: () => void; agentCwd: string; agentModel: string; nameCustomized: boolean; suppressReconnect: boolean; latestCtx: ExtensionContext | null; pidPath: string; }
export function registerCommands(pi: ExtensionAPI, st: CommandCtx): void {
  // ── Commands ────────────────────────────────────────────────────────────────

  pi.registerCommand("mail-name", {
    description: "View or set your agent display name in the mail federation",
    handler: async (args, ctx) => {
      st.latestCtx = ctx;
      const name = args.trim();
      if (!name) {
        ctx.ui.notify(`Mail name: ${st.agentName} | ID: ${st.agentId.slice(0, 8)}`, "info");
        return;
      }
      st.agentName = name;
      st.nameCustomized = true;
      pi.appendEntry("pi-mail-name", { name });

      // Re-register with new name (daemon updates its registry)
      if (st.client && st.connected) {
        try {
          await st.client.request({ type: "register", agentId: st.agentId, agentName: st.agentName, cwd: st.agentCwd });
        } catch {}
      }

      st.updateStatus(ctx);
      ctx.ui.notify(`Mail name set to: ${st.agentName}`, "info");
    },
  });

  pi.registerCommand("restart-mail-daemon", {
    description: "Stop the mail daemon and reconnect (spawns a fresh daemon)",
    handler: async (_args, ctx) => {
      st.latestCtx = ctx;

      // Disconnect our own st.client first (without clearing our st.mailbox).
      // Suppress the auto-reconnect that onDisconnect would otherwise schedule,
      // since we explicitly reconnect below.
      st.suppressReconnect = true;
      st.clearReconnect();
      st.client?.disconnect();
      st.client = null;
      st.connected = false;
      st.updateStatus(ctx);

      // Kill the running daemon via its PID file
      let killed = false;
      try {
        const pid = parseInt(readFileSync(st.pidPath, "utf8").trim(), 10);
        if (pid > 0) {
          process.kill(pid, "SIGTERM");
          killed = true;
        }
      } catch {
        // No PID file / process already gone
      }

      // Give the old daemon a moment to release the socket
      await sleep(killed ? 500 : 100);

      // Reconnect — ensureDaemonAndConnect spawns a new daemon if needed
      st.suppressReconnect = false;
      await st.connectToDaemon().catch(() => {});
      st.updateStatus(ctx);

      if (st.connected) {
        ctx.ui.notify(
          killed ? "♻️ Mail daemon restarted and reconnected" : "✅ Mail daemon (re)started and connected",
          "info"
        );
      } else {
        ctx.ui.notify("❌ Failed to reconnect to mail daemon", "error");
      }
    },
  });

  // ── /agents — live TUI view of connected agents ──────────────────────────

  pi.registerCommand("agents", {
    description: "Show a live view of all connected agents in the mail federation",
    handler: async (_args, ctx) => {
      st.latestCtx = ctx;
      if (ctx.mode !== "tui") {
        // Fallback for non-TUI mode
        if (!st.connected || !st.client) {
          ctx.ui.notify("❌ Not connected to mail daemon", "error");
          return;
        }
        const resp = await st.client.request<{ type: string; agents?: Array<AgentInfo & { contextPct?: number | null }> }>({
          type: "list_agents",
        });
        if (resp.type === "agents" && resp.agents) {
          const sorted = [...resp.agents].sort(
            (x, y) =>
              projectGroupKey(x.cwd).localeCompare(projectGroupKey(y.cwd)) ||
              x.agentName.localeCompare(y.agentName)
          );
          const lines: string[] = [];
          let prev = "";
          for (const a of sorted) {
            const grp = projectGroupKey(a.cwd);
            if (grp !== prev) {
              prev = grp;
              lines.push(`📁 ${grp}${a.cwd && a.cwd !== grp ? `  (${a.cwd})` : ""}`);
            }
            const self = a.agentId === st.agentId ? " (you)" : "";
            const upSec = Math.round((Date.now() - a.registeredAt) / 1000);
            const up = upSec < 60 ? `${upSec}s` : upSec < 3600 ? `${Math.round(upSec / 60)}m` : `${Math.round(upSec / 3600)}h`;
            const ctx2 = a.contextPct != null ? ` ctx=${a.contextPct}%` : "";
            const modelStr = a.model ? ` model=${a.model}` : "";
            const statusStr = a.status ? ` — ${a.status}` : "";
            lines.push(`  • ${a.agentName}${self} [${up}]${ctx2}${modelStr}${statusStr}`);
          }
          ctx.ui.notify(`${resp.agents.length} agents:\n${lines.join("\n")}`, "info");
        }
        return;
      }

      type AgentRow = AgentInfo & { contextPct?: number | null };
      let agentRows: AgentRow[] = [];
      let lastRefresh = 0;
      let refreshError = "";

      const fetchAgents = async (): Promise<void> => {
        if (!st.connected || !st.client) { refreshError = "Not connected"; return; }
        try {
          const resp = await st.client.request<{ type: string; agents?: AgentRow[] }>({
            type: "list_agents",
          });
          if (resp.type === "agents" && resp.agents) {
            agentRows = [...resp.agents].sort(
              (x, y) =>
                projectGroupKey(x.cwd).localeCompare(projectGroupKey(y.cwd)) ||
                x.agentName.localeCompare(y.agentName)
            );
            lastRefresh = Date.now();
            refreshError = "";
          }
        } catch (e) {
          refreshError = e instanceof Error ? e.message : String(e);
        }
      };

      await fetchAgents();

      await ctx.ui.custom<void>((tui, theme, _kb, done) => {
        let selectedIdx = 0;
        let cachedWidth: number | undefined;
        let cachedLines: string[] | undefined;

        const fmtUptime = (registeredAt: number): string => {
          const s = Math.round((Date.now() - registeredAt) / 1000);
          return s < 60 ? `${s}s` : s < 3600 ? `${Math.round(s / 60)}m` : `${Math.round(s / 3600)}h`;
        };

        const fmtCtx = (pct: number | null | undefined, theme2: typeof theme): string => {
          if (pct == null) return theme2.fg("dim", "  —  ");
          const s = `${pct}%`.padStart(4);
          const color = pct >= 80 ? "error" : pct >= 50 ? "warning" : "success";
          return theme2.fg(color, s);
        };

        const invalidate = (): void => {
          cachedWidth = undefined;
          cachedLines = undefined;
        };

        const render = (width: number): string[] => {
          if (cachedLines && cachedWidth === width) return cachedLines;

          const lines: string[] = [];
          const pad = (s: string, n: number) => s.slice(0, n).padEnd(n);
          const hr = theme.fg("border", "─".repeat(width));

          // Header
          const ago = lastRefresh ? `${Math.round((Date.now() - lastRefresh) / 1000)}s ago` : "…";
          const title = refreshError
            ? theme.fg("error", `Federation — error: ${refreshError}`)
            : theme.fg("accent", `Federation — ${agentRows.length} agent${agentRows.length === 1 ? "" : "s"} `) +
              theme.fg("dim", `(refreshed ${ago})`);
          lines.push(" " + title);
          lines.push(hr);

          // Column header
          const colHdr =
            theme.fg("dim", pad("name", 26)) +
            theme.fg("dim", pad("up", 5)) +
            theme.fg("dim", " ctx  ") +
            theme.fg("dim", pad("model", 28)) +
            theme.fg("dim", "status");
          lines.push(" " + colHdr);
          lines.push(hr);

          if (agentRows.length === 0) {
            lines.push(theme.fg("muted", "  (no agents)"));
          } else {
            let prevGroup = "";
            agentRows.forEach((a, i) => {
              const grp = projectGroupKey(a.cwd);
              if (grp !== prevGroup) {
                prevGroup = grp;
                const full = a.cwd ?? "";
                const header =
                  theme.fg("accent", `📁 ${grp}`) +
                  (full && full !== grp ? theme.fg("dim", `  ${full}`) : "");
                lines.push(" " + header);
              }
              const self = a.agentId === st.agentId;
              const selfMark = self ? theme.fg("accent", " ←") : "   ";
              const name = self
                ? theme.fg("accent", pad(a.agentName, 24)) + selfMark
                : theme.fg("text", pad(a.agentName, 24)) + selfMark;
              const up = theme.fg("dim", pad(fmtUptime(a.registeredAt), 5));
              const ctxStr = " " + fmtCtx(a.contextPct, theme) + " ";
              const modelLabel = a.model
                ? theme.fg("dim", pad(a.model, 28))
                : theme.fg("dim", pad("—", 28));
              const status = a.status
                ? theme.fg(i === selectedIdx ? "text" : "muted", a.status)
                : theme.fg("dim", "—");

              const row = "  " + name + " " + up + ctxStr + modelLabel + status;
              if (i === selectedIdx) {
                lines.push(theme.bg("selectedBg", " " + row));
              } else {
                lines.push(" " + row);
              }
            });
          }

          lines.push(hr);
          lines.push(
            theme.fg("dim", "  ↑↓ navigate  ") +
            theme.fg("dim", "r refresh  ") +
            theme.fg("dim", "esc close")
          );

          cachedLines = lines;
          cachedWidth = width;
          return lines;
        };

        // Auto-refresh every 5s
        const refreshTimer = setInterval(async () => {
          await fetchAgents();
          invalidate();
          tui.requestRender();
        }, 5000);

        const handleInput = (data: string): void => {
          if (matchesKey(data, Key.up)) {
            if (selectedIdx > 0) { selectedIdx--; invalidate(); tui.requestRender(); }
          } else if (matchesKey(data, Key.down)) {
            if (selectedIdx < agentRows.length - 1) { selectedIdx++; invalidate(); tui.requestRender(); }
          } else if (data === "r" || data === "R") {
            fetchAgents().then(() => { invalidate(); tui.requestRender(); });
          } else if (matchesKey(data, Key.escape) || data === "q" || data === "Q") {
            clearInterval(refreshTimer);
            done();
          }
        };

        return { render, invalidate, handleInput };
      });
    },
  });

  pi.registerCommand("new-task", {
    description: "Start a fresh session, clearing all context. Optional arg = kickoff prompt for the new session.",
    handler: async (args, ctx) => {
      st.latestCtx = ctx;
      await ctx.waitForIdle();
      const kickoff = args.trim();
      await ctx.newSession({
        withSession: async (newCtx) => {
          if (kickoff) {
            await newCtx.sendUserMessage(kickoff);
          } else {
            newCtx.ui.notify("✅ New session started (context cleared)", "info");
          }
        },
      });
    },
  });

  pi.registerCommand("prune-agents", {
    description: "Probe all agents, then remove ones that don't respond within 15s",
    handler: async (args, ctx) => {
      st.latestCtx = ctx;
      if (!st.connected || !st.client) {
        ctx.ui.notify("❌ Not connected to pi-mail daemon", "error");
        return;
      }

      const waitSec = parseInt(args.trim(), 10) || 15;

      // 1. Broadcast a probe so live agents get a chance to reply (their pong
      //    updates lastSeen on the daemon side).
      try {
        await st.client.request({
          type: "broadcast",
          subject: "__probe__",
          body: `Liveness probe — please reply so you are not pruned. You have ${waitSec}s.`,
        });
      } catch {}

      ctx.ui.notify(`🔍 Probe sent — waiting ${waitSec}s for replies…`, "info");

      // 2. Wait for agents to reply.
      await new Promise<void>((r) => setTimeout(r, waitSec * 1000));

      // 3. Prune agents that haven't been seen since before the probe.
      try {
        const resp = await st.client.request<{ type: string; pruned?: Array<{ agentId: string; agentName: string }> }>({
          type: "prune_silent",
          olderThanMs: (waitSec + 5) * 1000,
        });
        if (resp.type === "pruned") {
          const n = resp.pruned?.length ?? 0;
          const names = resp.pruned?.map((a) => a.agentName).join(", ") ?? "";
          ctx.ui.notify(
            n === 0
              ? "✅ All agents responded — nothing pruned"
              : `🗑️ Pruned ${n} silent agent${n === 1 ? "" : "s"}: ${names}`,
            n === 0 ? "info" : "warn"
          );
        }
      } catch (err: unknown) {
        ctx.ui.notify(`Error pruning: ${err instanceof Error ? err.message : String(err)}`, "error");
      }
    },
  });

  pi.registerCommand("mail-status", {
    description: "Show mail federation connection status and unread count",
    handler: async (_args, ctx) => {
      st.latestCtx = ctx;
      if (!st.connected) {
        ctx.ui.notify("❌ Not connected to pi-mail daemon", "error");
        return;
      }
      const unread = st.mailbox.filter((m) => !m.read).length;
      ctx.ui.notify(
        `✅ Connected as "${st.agentName}" (${st.agentId.slice(0, 8)}) | ${unread} unread`,
        "info"
      );
    },
  });

}
