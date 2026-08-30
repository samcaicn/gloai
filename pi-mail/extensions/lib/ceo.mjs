/**
 * CEO (top-tier manager) scheduler + reaper for the pi-mail daemon.
 *
 * The CEO is an ephemeral agent the daemon spawns on a schedule (default
 * every 120 min, when enabled). It is a pure manager — like the middle-manager
 * but one level up: it does NOT do task administration (no moving/unblocking/
 * archiving tasks). Instead it reviews the federation at a high level, decides
 * which board groups need a middle-manager pass, spawns MMs for those,
 * optionally tunes the favorites list, mails `human` a summary, and self-exits.
 * One CEO per cycle handles ALL board groups: the favorited (always-managed)
 * baseline PLUS every other group that has tasks on the board.
 *
 * CEO replaces the daemon's fixed-interval MM timer: when `ceoEnabled` is true,
 * `mmTick` skips its own MM spawn (the CEO is the sole MM spawner); the MM
 * reaper still runs as a safety net. With `ceoEnabled` false, the existing MM
 * loop works unchanged (backward-compat). See lib/middle-manager.mjs.
 *
 * Lifecycle (ephemeral + self-deleting): the CEO is spawned fresh each cycle,
 * does its pass, mails `human` a completion summary, then calls `mail_stop_self`
 * to tear down its own tmux session + registry entry. A periodic reaper (shared
 * with the MM reaper's tick) cleans up spawned CEO sessions whose tmux session
 * has already ended, and forcibly stops any CEO session exceeding a
 * configurable max lifetime (safety bound) so dead/long-running CEOs never
 * accumulate.
 *
 * Config lives in `board.config` (per-board): `ceoEnabled` (default false),
 * `ceoIntervalMin` (default 120), `ceoModel` (optional), `ceoMaxLifetimeMin`
 * (default 15 — the CEO is a ~15-minute management thread; operator invariant
 * 7/9). Editable via the Board UI settings + set_board_config.
 *
 * Ephemerality invariant (CEO → MM → workers): every daemon-spawned session in
 * this hierarchy is ephemeral and is killed after its pass — regardless of
 * self-exit. The CEO and MMs self-delete via mail_stop_self; the reaper is the
 * backstop. Cascade cleanup is independent per tier: when a CEO is reaped
 * mid-pass, the MM/worker it spawned are not tracked as its children — each is
 * a daemon-spawned registry entry reaped on its own tier's lifetime (MM by
 * reapMiddleManagers, worker by reapWorkers), so a reaped parent can never
 * leave orphans. See the README ephemerality invariant + reapWorkers.
 */

import path from "node:path";
import fs from "node:fs";
import os from "node:os";
import crypto from "node:crypto";
import {
  HUMAN_AGENT_ID,
  HUMAN_AGENT_NAME,
  agents,
  log,
} from "./core.mjs";
import { board, setManagerAgentTest } from "./board.mjs";
import {
  spawnAgent,
  stopAgent,
  spawnRegistry,
  tmuxSessionExists,
  schedulePersistSpawn,
} from "./spawn.mjs";
import { isMiddleManager, MM_NAME_PREFIX } from "./middle-manager.mjs";

/** How often the scheduler + reaper wake up to check. The actual spawn cadence
 *  is gated on `ceoIntervalMin`; this is just the polling granularity. Reuses
 *  the MM tick interval env var so they share one timer cadence. */
const CEO_TICK_MS = parseInt(process.env.PI_MAIL_MM_TICK_MS || "60000", 10);

/** Session-name prefix for spawned CEOs, so they're identifiable in
 *  `mail_list_agents` / the web UI. The suffix is a short random id. */
const CEO_NAME_PREFIX = "ceo";

// ── CEO session tracking ────────────────────────────────────────────────────

/** Persisted across restarts in the spawn registry so the last-spawn timestamp
 *  survives a daemon restart (otherwise a restart would immediately re-spawn).
 *  Restored by loadSpawn() alongside the sessions/projects/mm keys. */
function ceoMeta() {
  if (!spawnRegistry.ceo) spawnRegistry.ceo = { lastSpawnTs: 0 };
  return spawnRegistry.ceo;
}

/** Spawned CEO sessions tracked by the daemon (registry entries with ceo:true). */
function ceoSessions() {
  return Object.entries(spawnRegistry.sessions)
    .filter(([, s]) => s.ceo)
    .map(([name, s]) => ({ name, ...s }));
}

/** CEO sessions whose tmux session is still alive (the agent is still running). */
function liveCeoSessions() {
  return ceoSessions().filter((s) => tmuxSessionExists(s.name));
}

/**
 * Predicate injected into board.mjs: true when `agentId` belongs to a currently
 * tracked CEO session OR a middle-manager session (both are managers that
 * oversee multiple projects, so the same-group partition must not hide tasks
 * from them). Composed with the MM's own predicate so a single injected fn
 * covers both tiers.
 *
 * Two independent match paths for the CEO half (task 16a594db), mirroring
 * isMiddleManager:
 *  1. agentName — the tmux session name, known the moment the agent registers
 *     (works immediately, before the agentId is stamped).
 *  2. agentId — stamped on the registry entry after registration; a robust
 *     fallback that still recognises the CEO if its registered agentName
 *     diverged from the session name.
 * Either path is sufficient. The MM half delegates to isMiddleManager.
 */
function isCeo(agentId) {
  if (!agentId || agentId === HUMAN_AGENT_ID) return false;
  const info = agents.get(agentId)?.info;
  const name = info?.agentName;
  for (const s of Object.values(spawnRegistry.sessions)) {
    if (!s.ceo) continue;
    if (name && s.agentName === name) return true;
    if (s.agentId && s.agentId === agentId) return true;
  }
  return false;
}

function isManager(agentId) {
  return isMiddleManager(agentId) || isCeo(agentId);
}

// ── Reaper ───────────────────────────────────────────────────────────────────

/**
 * Reap spawned CEO sessions: stop any whose tmux session has already ended (the
 * CEO exited on its own after signalling completion), and forcibly stop any that
 * have exceeded the configured max lifetime (safety bound so a stuck CEO can't
 * run forever or accumulate). Idempotent and cheap; called on every tick.
 */
function reapCeos(now = Date.now()) {
  const maxLifetimeMs = Math.max(1, (board.config.ceoMaxLifetimeMin ?? 15)) * 60_000;
  for (const s of ceoSessions()) {
    const alive = tmuxSessionExists(s.name);
    const overLifetime = now - (s.spawnedAt ?? now) > maxLifetimeMs;
    if (!alive) {
      const r = stopAgent({ name: s.name });
      if (r.error) log(`CEO reaper: could not clean up '${s.name}': ${r.error}`);
      else log(`CEO reaper: reaped exited session '${s.name}'`);
    } else if (overLifetime) {
      const r = stopAgent({ name: s.name });
      if (r.error) log(`CEO reaper: could not stop over-lifetime '${s.name}': ${r.error}`);
      else log(`CEO reaper: stopped over-lifetime session '${s.name}' (${Math.round((now - s.spawnedAt) / 60000)}m)`);
    }
  }
}

// ── Kickoff ──────────────────────────────────────────────────────────────────

/** Build the kickoff task text delivered to a freshly-spawned CEO. The CEO is a
 *  pure manager that does NOT do task administration — it only reviews the
 *  federation overview, decides which board groups need an MM pass, spawns MMs,
 *  optionally tunes favorites, mails `human` a summary, and self-exits. The CEO
 *  oversees ALL board groups: the favorited (always-managed) baseline PLUS
 *  every other group that has tasks on the board — not only the favorited set. */
function ceoKickoff(favorites) {
  const projects = favorites.map((cwd) => {
    const group = path.basename(cwd) || cwd;
    return `  • ${cwd} (group: ${group})`;
  });
  return [
    "You are the CEO (top-tier manager) for this pi-mail federation cycle. You are a pure manager: you do NOT implement anything yourself, and you do NOT do task administration (no moving/unblocking/archiving tasks — that is the middle managers' job). You review the federation at a high level, decide which board groups need a middle-manager pass, spawn middle managers for them, and keep the roster of managed projects healthy.",
    "",
    "## Your oversight covers ALL board groups — not only favorites",
    "You oversee EVERY board group in the federation, not just the favorited projects. Favorites are the always-managed baseline (reviewed every cycle regardless of board state), but your review is additive: you also review every OTHER group that has tasks on the board this cycle. A group is any project whose tasks share a `group` field (the project dir's basename). Do NOT limit yourself to the favorites listed below — a non-favorited group with active tasks still needs an MM spawned when it needs attention.",
    "",
    "## Your pass is a FULL pass — consider EVERY board group before exiting",
    "A pass is NOT one action. You must review every board group that has on-board tasks, AND every favorited project (even if its board is empty), and for EACH one decide whether it needs an MM pass this cycle. Do NOT stop after the first group you look at — keep going until you have made a spawn-or-skip decision for every group, THEN finish. If you spawn an MM for one project and are about to exit, STOP — review the rest of the groups first.",
    "",
    "## Tool usage — you MUST use your tools; never hand-parse JSON",
    "You MUST use your tools for every action and MUST NEVER hand-parse JSON or fabricate tool I/O. Your harness formats tool calls and returns for you — invoke each tool by name with plain parameter values and read the rendered result. Do not write or paste raw JSON tool inputs/outputs, do not JSON.parse tool results, and do not invent a tool's output and proceed as if you ran it. Only act on what a tool ACTUALLY returned; if it errored or returned nothing useful, retry it (or, for a federation-level blocker, mail human). The tools you use are: board_list_tasks (see the board — you have all-groups visibility), board_update_task (fix a task's project group when it's in the wrong group — use the group param; only change groups, do not edit summaries/descriptions), mail_list_agents (see connected agents + their cwds), mail_list_projects (recent project dirs + favorites, with cwds), mail_spawn_agent (spawn a middle manager with { cwd, mm: true }), mail_set_project_favorite (curate the favorites baseline), mail_send (mail human your completion summary), and mail_stop_self (tear down your own session when done). Your turn should read as a sequence of real tool calls — no JSON between you and your actions.",
    "",
    "## Managed projects (favorited) — the always-managed baseline",
    "Favorites are reviewed every cycle even if their board is empty. But the list below is NOT the full set of groups under your oversight — you also review every other group with on-board tasks (step 2).",
    ...(projects.length
      ? ["These are the favorited (always-managed) projects this cycle:", ...projects]
      : ["(No favorited projects this cycle — the favorites baseline is empty. You still review every other group that has tasks on the board.)"]),
    "",
    "## Your pass (do this once, then finish)",
    "1. Run mail_list_agents to see who is currently connected across the federation. Note each agent's cwd — you need a project's full cwd to spawn an MM for it.",
    "2. Run board_list_tasks (you have all-groups visibility) to get a high-level overview of EVERY project's tasks. Group the tasks by their `group` field: every distinct group that has on-board tasks (in a column, not Backlog/Archive) is under your oversight this cycle. The favorited projects above are always included even if their board is empty. Do NOT skip a group just because it isn't in the favorites list.",
    "3. For each group to review, decide whether it needs a middle-manager pass this cycle. Signals that it does: stuck/idle workers, flagged-unclear tasks, finished work still sitting in In Progress/Review (not yet moved to Done), a stale board, or no live worker assigned to active tasks.",
    "4. For groups that need a pass, spawn a middle manager with mail_spawn_agent({ cwd: \"<project-dir>\", mm: true }). You need the project's full cwd — find it from: the favorites list above (for favorited projects), mail_list_agents (each connected agent's cwd), or mail_list_projects (recent project dirs). Spawn ONE MM at a time (the daemon's no-overlap guard allows only one live MM at a time per project; if you spawn several, only the first runs and the rest are skipped). So spawn one, let it finish, then spawn the next if another group still needs attention.",
    "5. Optionally curate the managed-projects baseline: use mail_set_project_favorite to add a project that clearly needs ongoing oversight, or remove (unfavorite) one whose work is fully done. Be conservative — only unfavorite when there's genuinely nothing left to manage. Favoriting is additive to your oversight — an unfavorited group with on-board tasks is still reviewed this cycle.",
    "6. Do NOT move, assign, comment on, or close individual tasks yourself — that is the middle managers' job. Escalate task-level concerns by spawning an MM for that project. The one exception: you MAY fix a task's project group via board_update_task when you see a task filed under the wrong group (use ONLY the group param, leave summary/description unchanged). A correct group is essential for the MM to be able to assign the task to the right worker.",
    "",
    "## When you're done",
    `Mail a concise completion summary to "${HUMAN_AGENT_NAME}" (mail_send to "${HUMAN_AGENT_NAME}"): what you reviewed, which groups you spawned an MM for (and why), and any favorites you added/removed. Then you're finished — call mail_stop_self to tear down your own session (your tmux session is reaped immediately; the reaper is only a fallback).`,
    "",
    "Do not start any new long-running work. This is a single FULL management pass — make a spawn-or-skip decision for every board group (favorites baseline + every other group with on-board tasks), then finish.",
  ].join("\n");
}

/** Simple glob match: * matches any sequence, ? matches one char. */
function globMatch(pattern, str) {
  const re = new RegExp(
    "^" + String(pattern).replace(/[.+^${}()|[\]\\]/g, "\\$&").replace(/\*/g, ".*").replace(/\?/g, ".") + "$"
  );
  return re.test(String(str));
}

/** Whether the current host is allowed to spawn CEOs based on ceoAllowedHosts.
 *  Empty list = allow all hosts (backward compatible). */
function hostnameAllowed() {
  const allowed = board.config.ceoAllowedHosts;
  if (!Array.isArray(allowed) || allowed.length === 0) return true;
  const host = os.hostname();
  return allowed.some((p) => globMatch(p, host));
}

// ── Scheduler ────────────────────────────────────────────────────────────────

/** Are there ANY tasks on the board (in a column, not Backlog/Archive)
 *  across ALL groups? The CEO oversees every board group, not only favorites,
 *  so a non-favorited group with on-board tasks is reason enough to run a
 *  cycle. `board.tasks` is the in-memory task array (same source as
 *  boardState); `location` defaults to "board" when unset. */
function hasOnBoardTasks() {
  return board.tasks.some((t) => (t.location ?? "board") === "board");
}

/** Pick a valid working dir for the CEO. The CEO is a pure manager and won't
 *  edit files, so its cwd just needs to be a real directory — it discovers each
 *  project's full cwd at runtime via mail_list_agents / mail_list_projects when
 *  spawning MMs. Prefer a favorited dir (the always-managed baseline); when
 *  there are no favorites (or all favorited dirs are missing), fall back to a
 *  recently-spawned project dir or a connected agent's cwd so the CEO can still
 *  run a cycle for non-favorited groups with on-board tasks. Returns null when
 *  no valid dir can be found. */
function pickCeoCwd(favorites) {
  const dirs = [
    ...favorites,
    ...(spawnRegistry.projects.history ?? []).map((h) => h.cwd ?? h),
    ...Array.from(agents.values()).map((a) => a.info?.cwd).filter(Boolean),
  ];
  for (const d of dirs) {
    try { if (fs.statSync(d).isDirectory()) return d; } catch { /* missing — skip */ }
  }
  return null;
}

/**
 * Spawn one CEO for the current cycle. The CEO is a pure manager and won't edit
 * files, so its cwd just needs to be a real directory — it discovers each
 * project's full cwd at runtime (mail_list_agents / mail_list_projects). The
 * cwd is picked from favorites first, then recent project dirs / connected
 * agent cwds, so a cycle can still run when there are no favorites but there
 * are on-board tasks in other groups. Skips (returns early) if a live CEO
 * session is already running (no overlap). Records the spawn timestamp so the
 * next cycle is gated on ceoIntervalMin even across ticks.
 */
function spawnCeo(now = Date.now()) {
  if (liveCeoSessions().length > 0) return { skipped: "live CEO already running" };
  const favorites = spawnRegistry.projects.favorites ?? [];
  const cwd = pickCeoCwd(favorites);
  if (!cwd) {
    log("CEO scheduler: no valid cwd found (no favorites, history, or connected agents) — skipping cycle");
    return { skipped: "no valid cwd" };
  }
  const model = board.config.ceoModel && String(board.config.ceoModel).trim()
    ? String(board.config.ceoModel).trim()
    : undefined;
  const kickoff = ceoKickoff(favorites);
  const name = `${CEO_NAME_PREFIX}-${crypto.randomUUID().slice(0, 6)}`;
  const r = spawnAgent({ cwd, name, model, kickoff, ceo: true });
  if (r.error) {
    log(`CEO scheduler: spawn failed: ${r.error}`);
    return { error: r.error };
  }
  ceoMeta().lastSpawnTs = now;
  schedulePersistSpawn();
  const scope = favorites.length > 0 || hasOnBoardTasks() ? "all board groups" : "favorites";
  log(`CEO scheduler: spawned ceo '${r.name}' overseeing ${scope} (cwd ${cwd})`);
  return { ok: true, name: r.name };
}

/**
 * One scheduler tick. Spawns a CEO when enabled + there is something to manage
 * (a non-empty favorites baseline OR any on-board tasks across all groups) + no
 * live CEO + the interval has elapsed; reaps dead/over-lifetime sessions either
 * way. `force` bypasses the interval-elapsed check (an operator "run a cycle now").
 * `now` (default real time) is used for both gating and the recorded spawn ts,
 * so callers can drive time-based gates deterministically (tests). Exported
 * for testing (with a controllable "now").
 */
function ceoTick(now = Date.now(), force = false) {
  // Always reap — even when disabled, so a previously-spawned CEO that's still
  // tracked gets cleaned up if it exits or overstays.
  reapCeos(now);
  if (board.config.ceoEnabled !== true) return { reaped: true, spawned: false };
  if (!hostnameAllowed()) {
    return { reaped: true, spawned: false, reason: `hostname "${os.hostname()}" not in ceoAllowedHosts` };
  }
  const favorites = spawnRegistry.projects.favorites ?? [];
  // The CEO oversees ALL board groups: the favorites baseline (always-managed)
  // PLUS every other group with on-board tasks. So a cycle runs when either is
  // present — not only when favorites is non-empty.
  if (favorites.length === 0 && !hasOnBoardTasks()) {
    return { reaped: true, spawned: false, reason: "no favorites and no on-board tasks" };
  }
  if (liveCeoSessions().length > 0) return { reaped: true, spawned: false, reason: "live CEO running" };
  const intervalMs = Math.max(1, (board.config.ceoIntervalMin ?? 120)) * 60_000;
  if (!force && now - (ceoMeta().lastSpawnTs ?? 0) < intervalMs) {
    return { reaped: true, spawned: false, reason: "interval not elapsed" };
  }
  const r = spawnCeo(now);
  return { reaped: true, spawned: !!r.ok, ...r };
}

/** Snapshot of CEO state, for inspection / the UI / tests. */
function ceoState() {
  const sessions = ceoSessions().map((s) => ({
    name: s.name,
    cwd: s.cwd,
    spawnedAt: s.spawnedAt,
    alive: tmuxSessionExists(s.name),
  }));
  return {
    enabled: board.config.ceoEnabled === true,
    intervalMin: board.config.ceoIntervalMin ?? 120,
    model: board.config.ceoModel ?? "",
    maxLifetimeMin: board.config.ceoMaxLifetimeMin ?? 15,
    allowedHosts: board.config.ceoAllowedHosts ?? [],
    lastSpawnTs: ceoMeta().lastSpawnTs ?? 0,
    managedProjects: (spawnRegistry.projects.favorites ?? []).slice(),
    allGroups: true,
    onBoardTasks: hasOnBoardTasks(),
    sessions,
  };
}

/** Start the periodic scheduler + reaper loop. Called once from daemon.mjs at
 *  boot. Also injects the combined all-groups predicate (MM or CEO) into
 *  board.mjs so spawned managers can see every project's tasks. */
let ceoTimer = null;
function startCeoLoop() {
  setManagerAgentTest(isManager);
  if (ceoTimer) clearInterval(ceoTimer);
  ceoTimer = setInterval(() => {
    try {
      ceoTick();
    } catch (e) {
      log(`CEO scheduler error: ${e?.message ?? String(e)}`);
    }
  }, CEO_TICK_MS);
  // Reap any leftover CEO sessions from a previous run immediately on boot.
  reapCeos();
}

export {
  ceoTick,
  spawnCeo,
  reapCeos,
  isCeo,
  isManager,
  ceoKickoff,
  ceoState,
  startCeoLoop,
  hasOnBoardTasks,
  pickCeoCwd,
  CEO_NAME_PREFIX,
};
