/**
 * Read-only task-board tool registrations for the pi-mail extension.
 * Extracted from board-tools.ts: board_list_tasks (list the board) and
 * board_get_task (full task detail). The mutation tools remain in
 * board-tools.ts. Registered via registerBoardReadTools(pi, ctx).
 */
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";
import {
  errText,
  taskLine,
  fetchBoard,
  type BoardToolCtx,
  type BoardTask,
  type BoardColumn,
} from "./board-tools.js";

export function registerBoardReadTools(pi: ExtensionAPI, ctx: BoardToolCtx): void {
  pi.registerTool({
    name: "board_list_tasks",
    label: "Board: Tasks",
    description:
      "List all tasks on the shared kanban task board, grouped by column, plus the Backlog and Archive pools. " +
      "Shows task id, Jira key, summary, assignee and Jira status. Use 'mine: true' to only see tasks assigned to you. " +
      "By default archived tasks are hidden; pass includeArchived: true to see them. Pass location to filter to 'board'|'backlog'|'archive'. " +
      "Pass group to scope the listing: group:'all' shows every project's tasks (cross-group), group:'<name>' shows one project's tasks; omit for your own group (the default for workers). " +
      "Pass search to filter by case-insensitive substring match against summary, description, and task ID prefix (use with location:'archive' to search archived tasks).",
    promptSnippet: "List tasks on the shared task board",
    promptGuidelines: [
      "Use board_list_tasks to see sprint/board work, e.g. when asked what to work on or to check task state.",
    ],
    parameters: Type.Object({
      mine: Type.Optional(Type.Boolean({ description: "Only show tasks assigned to you" })),
      location: Type.Optional(Type.String({
        description: "Filter by location: 'board' (on a column), 'backlog', or 'archive'. Omit to see board + backlog (archive hidden unless includeArchived).",
      })),
      level: Type.Optional(Type.String({ description: "Filter to a level: 'epic' | 'story' | 'task' | 'subtask'" })),
      includeArchived: Type.Optional(Type.Boolean({ description: "Include archived tasks (location='archive') in the listing" })),
      group: Type.Optional(Type.String({ description: "Scope by project group: 'all' = every project's tasks (cross-group), or a specific group name. Omit for your own group (default for workers)." })),
      search: Type.Optional(Type.String({ description: "Search query — case-insensitive match against summary, description, and task ID prefix. Use with location:'archive' to search archived tasks." })),
    }),
    async execute(_id, params, _signal, _onUpdate, _ctx) {
      try {
        // Delegate location/archive + group filtering to the daemon's boardState
        // (task 6586b9ca / b59e930a) — single source of truth. Default (no params)
        // hides the archive (includeArchived defaults to false); backlog + board
        // columns are shown. `mine`/`level` stay here (presentation/agent-specific).
        const b = await fetchBoard(ctx, { location: params.location, includeArchived: params.includeArchived ?? false, group: params.group, search: params.search });
        let tasks = b.tasks ?? [];
        if (params.mine) tasks = tasks.filter((t) => t.assignee === ctx.agentName);
        if (params.level) tasks = tasks.filter((t) => (t.level ?? "task") === params.level);
        if (params.priority) tasks = tasks.filter((t) => t.priority === params.priority);
        // Client-side priority sort
        if (params.sort === "priority") {
          const rank = (p: string | null) => ({ high: 0, medium: 1, low: 2 } as Record<string, number>)[p ?? ""] ?? 3;
          tasks = [...tasks].sort((a, b) => rank(a.priority) - rank(b.priority));
        }
        const wantLoc = params.location;
        const showArchive = !!params.includeArchived || wantLoc === "archive";
        const cols = b.columns ?? [];
        const lines: string[] = [];
        // Backlog pool (sits above the board) — show first when in default/board view.
        if (!wantLoc || wantLoc === "backlog") {
          const inBacklog = tasks.filter((t) => (t.location ?? "board") === "backlog");
          if (params.mine ? inBacklog.length : true) {
            lines.push(`▌ Backlog — ${inBacklog.length} item${inBacklog.length === 1 ? "" : "s"}`);
            if (!inBacklog.length) lines.push("  (empty)");
            for (const t of inBacklog) lines.push(taskLine(t));
          }
        }
        for (const col of cols) {
          const inCol = tasks.filter((t) => (t.location ?? "board") === "board" && t.columnId === col.id);
          if (params.mine && inCol.length === 0) continue;
          const jira = col.jiraStatus ? ` (jira: ${col.jiraStatus})` : " (board-only)";
          lines.push(`▌ ${col.name}${jira} — ${inCol.length} task${inCol.length === 1 ? "" : "s"}`);
          if (col.instructions) lines.push(`  ↳ instructions: ${col.instructions.split("\n")[0].slice(0, 100)}…`);
          for (const t of inCol) lines.push(taskLine(t));
        }
        if (showArchive && (!wantLoc || wantLoc === "archive")) {
          const inArch = tasks.filter((t) => t.location === "archive");
          lines.push(`▌ Archive (done board) — ${inArch.length} item${inArch.length === 1 ? "" : "s"}`);
          if (!inArch.length) lines.push("  (empty)");
          for (const t of inArch) lines.push(taskLine(t));
        }
        const sync = b.jiraEnabled === false
          ? "Jira: disabled (board-only mode)"
          : b.jiraConfigured
            ? b.syncError
              ? `⚠️ Jira sync error: ${b.syncError}`
              : `Jira sync: last ${b.lastSync ? new Date(b.lastSync).toLocaleString() : "never"}`
            : "Jira: not configured (board-only mode)";
        const scope = params.group === "all" ? " · all groups" : params.group ? ` · group: ${params.group}` : b.myGroup ? ` · group: ${b.myGroup} (same-group view)` : " · all groups (operator view)";
        return {
          content: [{ type: "text", text: `📋 Task board — ${tasks.length} task(s)\n${sync}${scope}\n\n${lines.join("\n")}` }],
          details: { columns: b.columns, tasks },
        };
      } catch (err: unknown) {
        return errText(err);
      }
    },
  });

  pi.registerTool({
    name: "board_get_task",
    label: "Board: Task",
    description: "Get full details of one board task by id (8-char prefix ok) or Jira key: description, column, assignee, activity log.",
    promptSnippet: "Read a board task in full",
    parameters: Type.Object({
      taskId: Type.String({ description: "Task id prefix (from board_list_tasks) or Jira key (e.g. PROJ-123)" }),
    }),
    async execute(_id, params, _signal, _onUpdate, _ctx) {
      try {
        // Fetch with group:'all' (task 16a594db) so a task is resolved by id
        // across EVERY project group regardless of the caller's default
        // same-group scoping. get-by-id must not be gated by the caller's own
        // group — board_list_tasks can already list cross-group with
        // group:'all', and get-by-id should be at least as permissive. (This
        // also makes board_get_task find archived tasks, which the default
        // includeArchived:false scoping would hide.)
        const b = await fetchBoard(ctx, { group: "all", includeArchived: true });
        const s = params.taskId.toLowerCase();
        const t = (b.tasks ?? []).find(
          (x) => x.id === params.taskId || x.id.startsWith(params.taskId) || (x.key && x.key.toLowerCase() === s)
        );
        if (!t) return { content: [{ type: "text", text: `Task not found: ${params.taskId}. Run board_list_tasks first.` }] };
        const col = (b.columns ?? []).find((c) => c.id === t.columnId);
        const loc = t.location ?? "board";
        const locLabel = loc === "backlog" ? "Backlog" : loc === "archive" ? "Archive" : (col?.name ?? t.columnId ?? "?");
        const epic = t.epicId ? (b.tasks ?? []).find((e) => e.id === t.epicId) : null;
        const lines = [
          `Task:     ${t.key ? `[${t.key}] ` : ""}${t.summary}`,
          `Id:       ${t.id}`,
          `Location: ${locLabel}${loc === "board" ? (col?.jiraStatus ? ` (jira: ${col.jiraStatus})` : " (board-only)") : " (off-board)"}`,
          `Level:    ${t.level ?? "task"}${epic ? ` · epic: ${epic.key ?? epic.id.slice(0, 8)} — ${epic.summary}` : ""}`,
          `Assignee: ${t.assignee ?? "—"}`,
          `Group:    ${t.group ?? "—"}${t.assignee ? "" : " (none — visible to all groups)"}`,
          `Origin:   ${t.origin}${t.jiraStatus ? ` | Jira status: ${t.jiraStatus}` : ""}${t.priority ? ` | Priority: ${t.priority}` : ""}${t.issueType ? ` | Type: ${t.issueType}` : ""}`,
          ...(t.model ? [`Model:    ${t.model}`] : []),
          ...(t.parentKey || t.parentId ? [`Parent:   ${t.parentKey ?? t.parentId?.slice(0, 8)}`] : []),
          ...(t.flagged ? [`⚠ FLAGGED UNCLEAR by ${t.flagged.by}: ${t.flagged.reason}`] : []),
          ...(t.url ? [`Jira:     ${t.url}`] : []),
          "─".repeat(40),
          t.description || "(no description)",
        ];
        const children = (b.tasks ?? []).filter((x) => x.parentId === t.id || (t.key && x.parentKey === t.key));
        if (children.length) {
          lines.push("", "## Subtasks");
          for (const c of children) lines.push(taskLine(c));
        }
        if (col?.instructions) lines.push("", `## Column instructions ("${col.name}")`, col.instructions);
        if (t.activity?.length) {
          lines.push("", "## Activity");
          for (const a of t.activity.slice(-15)) {
            const mark = a.kind === "progress" ? " 📈" : "";
            lines.push(`- ${new Date(a.ts).toLocaleString()} — ${a.who}:${mark} ${a.text}`);
          }
        }
        return { content: [{ type: "text", text: lines.join("\n") }], details: { task: t } };
      } catch (err: unknown) {
        return errText(err);
      }
    },
  });
}
