/**
 * Mail + agent-federation tool registrations for the pi-mail extension.
 * Extracted from index.ts. Registered via registerMailTools(pi, st) where st
 * is the live (getter-backed) state object from the extension closure.
 */
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";
import type { MailClient, MailMessage, AgentInfo } from "./mail-client.js";
import { projectGroupKey } from "./mail-client.js";
import { registerMailRestartTool } from "./mail-restart.js";
export interface MailToolCtx { client: MailClient | null; connected: boolean; agentId: string; agentName: string; nameCustomized: boolean; mailbox: MailMessage[]; notConnected: { content: { type: "text"; text: string }[] }; agentStatus: string; updateStatus: (ctx?: unknown) => void; connectToDaemon: () => Promise<void>; pidPath: string; }
function errText(err: unknown) { return { content: [{ type: "text" as const, text: `Error: ${err instanceof Error ? err.message : String(err)}` }] }; };

// Module-level restart state + the mail_restart_daemon tool live in
// mail-restart.ts (extracted to keep this module focused on the read/send/name
// tools). Registered below.

export function registerMailTools(pi: ExtensionAPI, st: MailToolCtx): void {
  // ── Tools ───────────────────────────────────────────────────────────────────

  pi.registerTool({
    name: "mail_list",
    label: "Mail: Inbox",
    description: "List all messages in your mail inbox (read and unread)",
    promptSnippet: "List your mail inbox",
    promptGuidelines: [
      "Use mail_list when the user asks about mail, messages, or other agents' communications.",
    ],
    parameters: Type.Object({}),
    async execute(_id, _params, _signal, _onUpdate, _ctx) {
      if (!st.connected || !st.client) {
        return { content: [{ type: "text", text: "❌ Not connected to mail daemon" }] };
      }
      try {
        const resp = await st.client.request<{ type: string; messages?: MailMessage[] }>({
          type: "list_mail",
        });
        if (resp.type !== "mail" || !resp.messages) {
          return {
            content: [{ type: "text", text: `Error: ${(resp as { message?: string }).message ?? "unknown"}` }],
          };
        }
        st.mailbox = resp.messages;

        if (resp.messages.length === 0) {
          return { content: [{ type: "text", text: "📭 Inbox is empty" }] };
        }

        const lines = resp.messages.map((m) => {
          const status = m.read ? "✓" : "●";
          const time = new Date(m.timestamp).toLocaleString();
          const id = m.id.slice(0, 8);
          return `${status} [${id}] From: ${m.fromName} | Subject: ${m.subject} | ${time}`;
        });

        const unread = resp.messages.filter((m) => !m.read).length;
        const header = `📬 Inbox — ${resp.messages.length} message(s), ${unread} unread\n`;
        return {
          content: [{ type: "text", text: header + "\n" + lines.join("\n") }],
          details: { messages: resp.messages },
        };
      } catch (err: unknown) {
        return {
          content: [{ type: "text", text: `Error: ${err instanceof Error ? err.message : String(err)}` }],
        };
      }
    },
  });

  pi.registerTool({
    name: "mail_read",
    label: "Mail: Read",
    description: "Read a mail message in full by its ID (first 8 chars are enough)",
    promptSnippet: "Read a specific mail message",
    parameters: Type.Object({
      messageId: Type.String({
        description: "Message ID or prefix (from mail_list output, e.g. 'a1b2c3d4')",
      }),
    }),
    async execute(_id, params, _signal, _onUpdate, _ctx) {
      const msg = st.mailbox.find(
        (m) => m.id === params.messageId || m.id.startsWith(params.messageId)
      );
      if (!msg) {
        return {
          content: [{ type: "text", text: `Message not found: ${params.messageId}. Run mail_list first.` }],
        };
      }
      const time = new Date(msg.timestamp).toLocaleString();
      const text = [
        `From:    ${msg.fromName} (${msg.fromId.slice(0, 8)})`,
        `Subject: ${msg.subject}`,
        `Date:    ${time}`,
        `ID:      ${msg.id}`,
        `${"─".repeat(40)}`,
        msg.body,
      ].join("\n");
      return {
        content: [{ type: "text", text }],
        details: { message: msg },
      };
    },
  });

  pi.registerTool({
    name: "mail_send",
    label: "Mail: Send",
    description: "Send a mail message to a specific agent by name or ID",
    promptSnippet: "Send mail to a specific agent",
    parameters: Type.Object({
      to: Type.String({
        description: "Recipient agent name or ID (use mail_list_agents to see available agents)",
      }),
      subject: Type.String({ description: "Message subject line" }),
      body: Type.String({ description: "Message body text" }),
      newSession: Type.Optional(Type.Boolean({
        description: "If true, the receiving agent will start a fresh session (cleared context) before acting on this message. Use when sending an unrelated new task.",
      })),
    }),
    async execute(_id, params, _signal, _onUpdate, _ctx) {
      if (!st.connected || !st.client) {
        return { content: [{ type: "text", text: "❌ Not connected to mail daemon" }] };
      }
      try {
        const resp = await st.client.request<{
          type: string;
          messageId?: string;
          message?: string;
        }>({ type: "send", to: params.to, subject: params.subject, body: params.body, newSession: params.newSession });

        if (resp.type === "error") {
          return { content: [{ type: "text", text: `❌ ${resp.message}` }] };
        }
        return {
          content: [
            {
              type: "text",
              text: `✅ Sent to ${params.to} | Subject: "${params.subject}" | ID: ${resp.messageId?.slice(0, 8)}`,
            },
          ],
        };
      } catch (err: unknown) {
        return {
          content: [{ type: "text", text: `Error: ${err instanceof Error ? err.message : String(err)}` }],
        };
      }
    },
  });

  pi.registerTool({
    name: "mail_broadcast",
    label: "Mail: Broadcast",
    description: "Send a mail message to all currently connected agents (excluding yourself)",
    promptSnippet: "Broadcast a message to all connected agents",
    parameters: Type.Object({
      subject: Type.String({ description: "Message subject" }),
      body: Type.String({ description: "Message body" }),
    }),
    async execute(_id, params, _signal, _onUpdate, _ctx) {
      if (!st.connected || !st.client) {
        return { content: [{ type: "text", text: "❌ Not connected to mail daemon" }] };
      }
      try {
        const resp = await st.client.request<{
          type: string;
          recipients?: number;
          message?: string;
        }>({ type: "broadcast", subject: params.subject, body: params.body });

        if (resp.type === "error") {
          return { content: [{ type: "text", text: `❌ ${resp.message}` }] };
        }
        const n = resp.recipients ?? 0;
        return {
          content: [
            {
              type: "text",
              text: `📡 Broadcast sent to ${n} agent${n === 1 ? "" : "s"} | Subject: "${params.subject}"`,
            },
          ],
        };
      } catch (err: unknown) {
        return {
          content: [{ type: "text", text: `Error: ${err instanceof Error ? err.message : String(err)}` }],
        };
      }
    },
  });

  pi.registerTool({
    name: "mail_mark_read",
    label: "Mail: Archive",
    description: "Mark a message as read and remove it from your inbox",
    promptSnippet: "Archive a mail message after reading",
    parameters: Type.Object({
      messageId: Type.String({ description: "Message ID or prefix to archive" }),
    }),
    async execute(_id, params, _signal, _onUpdate, _ctx) {
      const msg = st.mailbox.find(
        (m) => m.id === params.messageId || m.id.startsWith(params.messageId)
      );
      if (!msg) {
        return {
          content: [{ type: "text", text: `Message not found: ${params.messageId}` }],
        };
      }

      if (!st.connected || !st.client) {
        return { content: [{ type: "text", text: "❌ Not connected to mail daemon" }] };
      }

      try {
        const resp = await st.client.request<{ type: string }>({
          type: "mark_read",
          messageId: msg.id,
        });
        if (resp.type === "ok") {
          st.mailbox = st.mailbox.filter((m) => m.id !== msg.id);
          st.updateStatus();
          return {
            content: [{ type: "text", text: `✅ Archived: "${msg.subject}" from ${msg.fromName}` }],
          };
        }
        return { content: [{ type: "text", text: "Failed to archive message" }] };
      } catch (err: unknown) {
        return {
          content: [{ type: "text", text: `Error: ${err instanceof Error ? err.message : String(err)}` }],
        };
      }
    },
  });

  pi.registerTool({
    name: "mail_list_agents",
    label: "Mail: Agents",
    description: "List all agents currently connected to the mail federation",
    promptSnippet: "List connected federation agents",
    parameters: Type.Object({}),
    async execute(_id, _params, _signal, _onUpdate, _ctx) {
      if (!st.connected || !st.client) {
        return { content: [{ type: "text", text: "❌ Not connected to mail daemon" }] };
      }
      try {
        const resp = await st.client.request<{ type: string; agents?: Array<AgentInfo & { contextPct?: number | null }> }>({
          type: "list_agents",
        });
        if (resp.type !== "agents" || !resp.agents) {
          return {
            content: [{ type: "text", text: `Error: ${(resp as { message?: string }).message ?? "unknown"}` }],
          };
        }
        if (resp.agents.length === 0) {
          return { content: [{ type: "text", text: "🤝 No agents currently connected" }] };
        }
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
          const self = a.agentId === st.agentId ? " ← you" : "";
          const upSec = Math.round((Date.now() - a.registeredAt) / 1000);
          const upTime =
            upSec < 60
              ? `${upSec}s`
              : upSec < 3600
              ? `${Math.round(upSec / 60)}m`
              : `${Math.round(upSec / 3600)}h`;
          const ctxStr = a.contextPct != null ? ` ctx=${a.contextPct}%` : "";
          const modelStr = a.model ? `\n    ↳ model: ${a.model}` : "";
          const status = a.status ? `\n    ↳ status: ${a.status}` : "";
          lines.push(`  • ${a.agentName}${self}  [online ${upTime}] id=${a.agentId.slice(0, 8)}${ctxStr}${modelStr}${status}`);
        }
        return {
          content: [
            {
              type: "text",
              text: `🤝 Federation — ${resp.agents.length} agent${resp.agents.length === 1 ? "" : "s"} connected\n\n${lines.join("\n")}`,
            },
          ],
          details: { agents: resp.agents },
        };
      } catch (err: unknown) {
        return {
          content: [{ type: "text", text: `Error: ${err instanceof Error ? err.message : String(err)}` }],
        };
      }
    },
  });

  pi.registerTool({
    name: "mail_set_name",
    label: "Mail: Set Name",
    description:
      "Set your own display name in the mail federation (replaces the auto-generated id-based name). " +
      "Other agents see this name in mail_list_agents and as the sender of your messages.",
    promptSnippet: "Set your mail federation display name",
    parameters: Type.Object({
      name: Type.String({ description: "Your new display name" }),
    }),
    async execute(_id, params, _signal, _onUpdate, ctx) {
      const name = params.name.trim();
      if (!name) {
        return { content: [{ type: "text", text: "❌ Name cannot be empty" }] };
      }
      st.agentName = name;
      st.nameCustomized = true;
      pi.appendEntry("pi-mail-name", { name });
      if (!st.connected || !st.client) {
        return { content: [{ type: "text", text: `⚠️ Name set locally to "${name}" but not connected to daemon` }] };
      }
      try {
        await st.client.request({ type: "set_name", agentName: name });
        st.updateStatus(ctx);
        return { content: [{ type: "text", text: `✅ Display name set to "${name}"` }] };
      } catch (err: unknown) {
        return {
          content: [{ type: "text", text: `Error: ${err instanceof Error ? err.message : String(err)}` }],
        };
      }
    },
  });

  pi.registerTool({
    name: "mail_set_status",
    label: "Mail: Set Status",
    description:
      "Set your own status line in the mail federation so other agents (e.g. an orchestrator) can see " +
      "what you are working on. Visible to others via mail_list_agents. Pass an empty string to clear it. " +
      "This is not injected into anyone's context automatically — it is only shown on request.",
    promptSnippet: "Set your mail federation status",
    promptGuidelines: [
      "Update mail_set_status when you start or finish a significant task so an orchestrator can track progress.",
      "Keep the status short, e.g. 'implementing auth refactor' or 'idle'.",
    ],
    parameters: Type.Object({
      status: Type.String({ description: "Short status text (empty string clears your status)" }),
    }),
    async execute(_id, params, _signal, _onUpdate, _ctx) {
      const status = params.status.trim();
      st.agentStatus = status;
      pi.appendEntry("pi-mail-status", { status });
      if (!st.connected || !st.client) {
        return { content: [{ type: "text", text: `⚠️ Status set locally but not connected to daemon` }] };
      }
      try {
        await st.client.request({ type: "set_status", status });
        return {
          content: [
            {
              type: "text",
              text: status ? `✅ Status set to: "${status}"` : `✅ Status cleared`,
            },
          ],
        };
      } catch (err: unknown) {
        return {
          content: [{ type: "text", text: `Error: ${err instanceof Error ? err.message : String(err)}` }],
        };
      }
    },
  });

  // mail_restart_daemon is registered from the extracted mail-restart module
  // (debounce + cooldown + query-readiness gate live there).
  registerMailRestartTool(pi, st);

}
