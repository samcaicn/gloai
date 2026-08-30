/**
 * Text formatters for board tool output.
 *
 * These mirror the rendering in extensions/index.ts (taskLine,
 * board_get_task, boardOpResult) so MCP clients see the same familiar
 * output the in-pi board_* tools produce: task id prefix, Jira key,
 * summary, assignee, status, flags, and the column instructions /
 * activity log for a single task.
 */

import type { BoardTask, BoardState, BoardOpResponse } from "./types.js";

/** One compact task line, e.g. `  • [5ccd4c51] PROJ-123 Summary → assignee [jira: In Progress] ⚠unclear`. */
export function taskLine(t: BoardTask): string {
  const key = t.key ? `${t.key} ` : "";
  const who = t.assignee ? ` → ${t.assignee}` : "";
  const status = t.jiraStatus ? ` [jira: ${t.jiraStatus}]` : "";
  const sub = t.parentKey || t.parentId ? ` ↳sub of ${t.parentKey ?? t.parentId?.slice(0, 8)}` : "";
  const lvl = t.level && t.level !== "task" ? ` ${t.level}` : "";
  const loc = t.location === "backlog" ? ` [backlog]` : t.location === "archive" ? ` [archive]` : "";
  const grp = t.group ? ` ⟨${t.group}⟩` : "";
  const flag = t.flagged ? ` ⚠unclear` : "";
  const mdl = t.model ? ` 🤖${t.model}` : "";
  return `  • [${t.id.slice(0, 8)}] ${key}${t.summary}${who}${status}${sub}${lvl}${loc}${grp}${mdl}${flag}`;
}

/** Filters for board_list_tasks, mirroring the agent tool's parameters. */
export interface BoardListFilters {
  mineAssignee?: string | null;
  /** Filter to a location: 'board' | 'backlog' | 'archive'. */
  location?: string;
  /** Filter to a level: 'epic' | 'story' | 'task' | 'subtask'. */
  level?: string;
  /** Include archived tasks (location='archive') in the listing. */
  includeArchived?: boolean;
  /** Scope by project group: 'all' = every project's tasks (cross-group), or a
   *  specific group name. Omit for the default scoping. */
  group?: string;
}

/** Render the whole board grouped by location/column (board_list_tasks). */
export function renderBoard(b: BoardState, filters: BoardListFilters = {}): string {
  const { mineAssignee, location: wantLoc, level, includeArchived, group } = filters;
  let tasks = b.tasks ?? [];
  if (mineAssignee) tasks = tasks.filter((t) => t.assignee === mineAssignee);
  if (level) tasks = tasks.filter((t) => (t.level ?? "task") === level);
  const showArchive = !!includeArchived || wantLoc === "archive";
  // NOTE: location/archive/group FILTERING is done server-side by boardState (task
  // 6586b9ca / b59e930a, via getBoard(opts)); `b.tasks` arrives already filtered.
  // The `wantLoc`/`showArchive` flags here only control which SECTIONS to render.
  // The group scope label is derived from the explicit `group` filter, else the
  // server's own group / myGroup (task b59e930a fixes the misleading "same-group
  // view" label managers used to see).
  const scopeLabel = group === "all"
    ? "all groups"
    : group
      ? `group: ${group}`
      : b.group
        ? (b.group === "all" ? "all groups" : `group: ${b.group}`)
        : b.myGroup
          ? `group: ${b.myGroup} (same-group view)`
          : "all groups (operator view)";
  if (tasks.length === 0) {
    if (mineAssignee) return `No tasks assigned to ${mineAssignee}.`;
    return `Board is empty. · ${scopeLabel}`;
  }
  const cols = b.columns ?? [];
  const lines: string[] = [`📋 Task board — ${tasks.length} task(s) · ${scopeLabel}`, ""];
  // Backlog pool (sits above the board) — show first in default/board view.
  if (!wantLoc || wantLoc === "backlog") {
    const inBacklog = tasks.filter((t) => (t.location ?? "board") === "backlog");
    if (mineAssignee ? inBacklog.length : true) {
      lines.push(`▌ Backlog — ${inBacklog.length} item${inBacklog.length === 1 ? "" : "s"}`);
      if (!inBacklog.length) lines.push("  (empty)");
      for (const t of inBacklog) lines.push(taskLine(t));
      lines.push("");
    }
  }
  for (const col of cols) {
    const inCol = tasks.filter((t) => (t.location ?? "board") === "board" && t.columnId === col.id);
    if (mineAssignee && inCol.length === 0) continue;
    const jira = col.jiraStatus ? ` (jira: ${col.jiraStatus})` : " (board-only)";
    lines.push(`▌ ${col.name}${jira} — ${inCol.length} task${inCol.length === 1 ? "" : "s"}`);
    for (const t of inCol) lines.push(taskLine(t));
    lines.push("");
  }
  if (showArchive && (!wantLoc || wantLoc === "archive")) {
    const inArch = tasks.filter((t) => t.location === "archive");
    lines.push(`▌ Archive (done board) — ${inArch.length} item${inArch.length === 1 ? "" : "s"}`);
    if (!inArch.length) lines.push("  (empty)");
    for (const t of inArch) lines.push(taskLine(t));
  }
  return lines.join("\n").trimEnd();
}

/** Render a single task in full (board_get_task). */
export function renderTask(t: BoardTask, b: BoardState): string {
  const col = (b.columns ?? []).find((c) => c.id === t.columnId);
  const loc = t.location ?? "board";
  const locLabel = loc === "backlog" ? "Backlog" : loc === "archive" ? "Archive" : (col?.name ?? t.columnId ?? "?");
  const locNote = loc === "board" ? (col?.jiraStatus ? ` (jira: ${col.jiraStatus})` : " (board-only)") : " (off-board)";
  const lines: string[] = [
    `Task:     ${t.key ? `[${t.key}] ` : ""}${t.summary}`,
    `Id:       ${t.id}`,
    `Location: ${locLabel}${locNote}${t.level ? ` | Level: ${t.level}` : ""}${t.epicId ? ` | Epic: ${t.epicId.slice(0, 8)}` : ""}`,
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
  return lines.join("\n");
}

/** Find a task by exact id, id-prefix, or (case-insensitive) Jira key. */
export function findTask(b: BoardState, taskId: string): BoardTask | undefined {
  const s = taskId.toLowerCase();
  return (b.tasks ?? []).find(
    (x) => x.id === taskId || x.id.startsWith(taskId) || (x.key && x.key.toLowerCase() === s),
  );
}

/** Format a mutation result as a ✅/❌ line, mirroring boardOpResult. */
export function renderOpResult(resp: BoardOpResponse, okText: string): string {
  if (resp.error) return `❌ ${resp.error}`;
  const warn = resp.warning ? `\n⚠️ ${resp.warning}` : "";
  return `✅ ${okText}${warn}`;
}
