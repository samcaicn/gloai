/**
 * Agent-spawn + project-history tool registrations for the pi-mail extension.
 * Extracted from board-tools.ts. Registered via registerSpawnTools(pi, ctx).
 *
 * These let an orchestrator bring up a brand-new, long-running pi agent in a
 * chosen working directory (a fresh worker for a project), then drive it with
 * board_assign_task / mail_send newSession:true. The daemon spawns the agent
 * in a detached tmux session (PTY, attachable, survives daemon restarts). Only
 * daemon-spawned sessions can be stopped.
 */
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";
import type { BoardToolCtx } from "./board-tools.js";

function errText(err: unknown) { return { content: [{ type: "text" as const, text: `Error: ${err instanceof Error ? err.message : String(err)}` }] }; }

export function registerSpawnTools(pi: ExtensionAPI, ctx: BoardToolCtx): void {
  pi.registerTool({
    name: "mail_spawn_agent",
    label: "Mail: Spawn Agent",
    description:
      "Spawn a fresh, long-running pi agent in a chosen working directory (a new worker for that project). The agent runs in a detached tmux session and registers with the federation within a few seconds; you can then assign it board tasks or mail it (newSession:true) to give it work. Returns the new agent's name. The cwd may be any directory on the filesystem (no allowlist). Use this to scale out orchestration to a new project directory instead of messaging an already-running agent.",
    promptSnippet: "Spawn a fresh pi agent in a directory",
    promptGuidelines: [
      "Use mail_spawn_agent to bring up a new worker in a project dir, then board_assign_task / mail_send(newSession:true) to give it work.",
      "The agent name defaults to <dir-basename>-<id6>; pass a name only if you need a specific one (tmux session name, no '.' or ':').",
    ],
    parameters: Type.Object({
      cwd: Type.String({ description: "Absolute working directory for the new agent (any directory on the filesystem)" }),
      name: Type.Optional(Type.String({ description: "Optional agent/session name (defaults to <dir-basename>-<id6>)" })),
      model: Type.Optional(Type.String({ description: "Optional model, e.g. 'anthropic/claude-sonnet-4' (defaults to pi's default)" })),
      kickoff: Type.Optional(Type.String({ description: "Optional kickoff prompt; delivered to the new agent as a new-session task once it registers" })),
      favorite: Type.Optional(Type.Boolean({ description: "If true, mark this project dir as a favorite (shown at the top of mail_list_projects and the UI picker). Use for projects you spawn into often." })),
      mm: Type.Optional(Type.Boolean({ description: "If true, spawn a middle-manager session (ephemeral, runs the MM pass + self-deletes). Used by the CEO to spawn MMs; rarely needed by other agents." })),
      ceo: Type.Optional(Type.Boolean({ description: "If true, spawn a CEO session (top-tier manager; ephemeral). Reserved for the daemon scheduler / operator; rarely used directly." })),
    }),
    async execute(_id, params, _signal, _onUpdate, _ctx) {
      if (!ctx.connected || !ctx.client) return ctx.notConnected;
      try {
        const resp = await ctx.client.request<{ type: string; name?: string; message?: string }>(
          { type: "spawn", cwd: params.cwd, name: params.name, model: params.model, kickoff: params.kickoff, favorite: params.favorite, mm: params.mm, ceo: params.ceo },
          45_000
        );
        if (resp.type === "error") return { content: [{ type: "text" as const, text: `❌ ${resp.message}` }] };
        const name = resp.name ?? "";
        const fav = params.favorite ? " · ⭐ favorited" : "";
        const kick = params.kickoff ? ` (kickoff delivered as new-session task)` : "";
        return {
          content: [{ type: "text" as const, text: `✅ Spawned agent '${name}' in ${params.cwd}${kick}${fav}. It will appear in mail_list_agents shortly; assign it work with board_assign_task or mail_send(newSession:true).` }],
          details: { name, cwd: params.cwd },
        };
      } catch (err: unknown) {
        return errText(err);
      }
    },
  });

  pi.registerTool({
    name: "mail_stop_agent",
    label: "Mail: Stop Agent",
    description:
      "Stop a daemon-spawned agent (kills its tmux session). Only stops agents the daemon itself spawned via mail_spawn_agent — never an operator-launched agent. Use to tear down a worker when its work is done.",
    promptSnippet: "Stop a spawned agent",
    promptGuidelines: [
      "Use mail_stop_agent only for agents you spawned with mail_spawn_agent; it will refuse operator-launched agents.",
    ],
    parameters: Type.Object({
      name: Type.String({ description: "Name of the daemon-spawned agent to stop" }),
    }),
    async execute(_id, params, _signal, _onUpdate, _ctx) {
      if (!ctx.connected || !ctx.client) return ctx.notConnected;
      try {
        const resp = await ctx.client.request<{ type: string; message?: string }>(
          { type: "spawn_stop", name: params.name },
          15_000
        );
        if (resp.type === "error") return { content: [{ type: "text" as const, text: `❌ ${resp.message}` }] };
        return { content: [{ type: "text" as const, text: `✅ Stopped agent '${params.name}'` }] };
      } catch (err: unknown) {
        return errText(err);
      }
    },
  });

  // A daemon-spawned agent tears down its OWN session + registry entry when its
  // work is done (instead of waiting for the reaper / operator). Workers,
  // middle-managers, CEOs, and any other daemon-spawned agent may call this.
  // Refuses operator-launched interactive agents (they stay alive unless
  // explicitly stopped via the UI). The daemon removes the registry entry
  // immediately and kills the tmux session after a short grace so the tool
  // response + any final mail flush before the process dies.
  pi.registerTool({
    name: "mail_stop_self",
    label: "Mail: Stop Self",
    description:
      "Tear down your own daemon-spawned agent session (kills your tmux session + removes the spawn-registry entry). Call this when your work is fully done and no further work is assigned/expected — e.g. a board-dispatched worker after it finishes its task and reports completion, or a middle-manager/CEO after its pass + completion summary. Only daemon-spawned agents may call this (operator-launched interactive agents are refused and stay alive). The daemon kills the session after a short grace so your final mail/tool response flushes first.",
    promptSnippet: "Stop your own spawned session when done",
    promptGuidelines: [
      "Call mail_stop_self when your assigned work is fully done and you have no further work expected — after reporting completion / mailing the summary.",
      "Do NOT call it if you are a persistent orchestrator-managed worker expecting more tasks, or an operator-launched interactive agent (it will be refused).",
    ],
    parameters: Type.Object({}),
    async execute(_id, _params, _signal, _onUpdate, _ctx) {
      if (!ctx.connected || !ctx.client) return ctx.notConnected;
      try {
        const resp = await ctx.client.request<{ type: string; message?: string; name?: string; graceMs?: number }>(
          { type: "stop_self" },
          8_000
        );
        if (resp.type === "error") return { content: [{ type: "text" as const, text: `❌ ${resp.message}` }] };
        const grace = resp.graceMs ?? 3000;
        return { content: [{ type: "text" as const, text: `✅ Self-exit scheduled: your session '${resp.name ?? ""}' will be torn down in ${Math.round(grace / 1000)}s (final mail flushes first).` }] };
      } catch (err: unknown) {
        return errText(err);
      }
    },
  });

  // ── Project history + favorites (spawn-agent “recent projects”) ──────────────
  //
  // The daemon tracks every dir you spawn an agent into (recent history) plus
  // a starred set (favorites), shared across the federation and persisted to
  // disk. mail_list_projects surfaces them so an orchestrator can pick a cwd
  // to spawn into without browsing the filesystem each time. mail_set_project_favorite
  // stars/unstars a dir (also doable in one shot via mail_spawn_agent's `favorite` param).

  pi.registerTool({
    name: "mail_list_projects",
    label: "Mail: Projects",
    description:
      "List recently-spawned project directories (history) and favorited project directories, tracked by the daemon across the federation. Each entry shows the cwd, whether a spawned agent is currently running in it, and (for history) the last spawn time + count. Use to pick a working directory for mail_spawn_agent instead of browsing the filesystem each time.",
    promptSnippet: "List recent + favorite spawn project dirs",
    promptGuidelines: [
      "Use mail_list_projects before mail_spawn_agent to find a known project dir quickly.",
      "Favorites persist and are shared federation-wide; star dirs you spawn into often with mail_set_project_favorite or the `favorite` param on mail_spawn_agent.",
    ],
    parameters: Type.Object({}),
    async execute(_id, _params, _signal, _onUpdate, _ctx) {
      if (!ctx.connected || !ctx.client) return ctx.notConnected;
      try {
        const resp = await ctx.client.request<{ type: string; favorites?: Array<{ cwd: string; alive: boolean }>; history?: Array<{ cwd: string; alive: boolean; lastSpawnedAt: number; count: number; lastName?: string }> }>(
          { type: "spawn_projects" },
          10_000
        );
        if (resp.type === "error") return { content: [{ type: "text" as const, text: `❌ ${(resp as { message?: string }).message ?? "unknown"}` }] };
        const favs = resp.favorites ?? [];
        const hist = resp.history ?? [];
        const lines: string[] = [];
        if (favs.length) {
          lines.push(`⭐ Favorites (${favs.length})`);
          for (const f of favs) lines.push(`  • ${f.cwd}${f.alive ? "  · live agent running" : ""}`);
          lines.push("");
        }
        if (hist.length) {
          lines.push(`🕒 Recent projects (${hist.length})`);
          for (const h of hist) {
            const when = new Date(h.lastSpawnedAt).toLocaleString();
            lines.push(`  • ${h.cwd}${h.alive ? "  · live" : ""}  · ${when} (×${h.count}${h.lastName ? `, last “${h.lastName}”` : ""})`);
          }
        }
        if (!lines.length) lines.push("(no projects yet — spawn an agent to start tracking recent dirs)");
        const body = `📂 Projects — ${favs.length} favorite${favs.length === 1 ? "" : "s"}, ${hist.length} recent\n\n${lines.join("\n")}`;
        return {
          content: [{ type: "text" as const, text: body }],
          details: { favorites: favs, history: hist },
        };
      } catch (err: unknown) {
        return errText(err);
      }
    },
  });

  pi.registerTool({
    name: "mail_set_project_favorite",
    label: "Mail: Favorite Project",
    description:
      "Star or unstar a project directory as a favorite (tracked by the daemon, shared federation-wide). Favorited dirs appear at the top of mail_list_projects and the web UI spawn picker. Use to mark project dirs you spawn agents into often. Returns the updated projects list.",
    promptSnippet: "Star/unstar a spawn project dir",
    promptGuidelines: [
      "Favorite a project dir with mail_set_project_favorite when you expect to spawn agents into it repeatedly.",
      "You can also favorite at spawn time via the `favorite` param on mail_spawn_agent.",
    ],
    parameters: Type.Object({
      cwd: Type.String({ description: "Absolute project directory to favorite/unfavorite" }),
      favorite: Type.Boolean({ description: "true to add to favorites, false to remove" }),
    }),
    async execute(_id, params, _signal, _onUpdate, _ctx) {
      if (!ctx.connected || !ctx.client) return ctx.notConnected;
      try {
        const resp = await ctx.client.request<{ type: string; favorite?: boolean; message?: string }>(
          { type: "spawn_favorite", cwd: params.cwd, favorite: params.favorite },
          10_000
        );
        if (resp.type === "error") return { content: [{ type: "text" as const, text: `❌ ${resp.message}` }] };
        const state = resp.favorite ? "⭐ favorited" : "unfavorited";
        return { content: [{ type: "text" as const, text: `✅ ${params.cwd} — ${state}` }] };
      } catch (err: unknown) {
        return errText(err);
      }
    },
  });
}
