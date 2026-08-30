/**
 * Middle-manager (MM) scheduler + reaper for the pi-mail daemon.
 *
 * The middle-manager is an ephemeral agent the daemon spawns on a schedule
 * (default every 30 min, when enabled). It reviews the task board for the
 * favorited (managed) projects, unblocks stuck workers, shepherds finished
 * tasks into Done, and curates the favorites list — so the board
 * keeps moving without an operator babysitting it. One MM per cycle handles
 * all managed projects in a single pass.
 *
 * Lifecycle (ephemeral + auto-close): the MM is spawned fresh each cycle,
 * does its pass, mails `human` a completion summary, and exits pi (ending its
 * tmux session). A periodic reaper cleans up spawned MM sessions whose tmux
 * session has already ended, and forcibly stops any MM session exceeding a
 * configurable max lifetime (safety bound) so dead/long-running MM sessions
 * never accumulate. The MM tick also runs the WORKER reaper (reapWorkers) —
 * the ephemerality backstop for the third tier — so hung/forgotten workers
 * are force-killed at their own max lifetime. See reapWorkers + the README
 * ephemerality invariant.
 *
 * Project selection = the favorites list (`spawnRegistry.projects.favorites`).
 * The MM sees every project group's tasks (it is given all-groups board access
 * via the `mmAgentTest` predicate injected into board.mjs — same visibility as
 * the human operator), then filters client-side to the favorited projects'
 * group (cwd basename). The MM may add/remove favorites itself to curate its
 * roster over time.
 *
 * Config lives in `board.config` (per-board): `mmEnabled` (default false),
 * `mmIntervalMin` (default 30), `mmModel` (optional), `mmMaxLifetimeMin`
 * (default 15). Editable via the Board UI settings + set_board_config.
 *
 * Worker failure modes (detected by reapWorkers + cleanupTasksForAgent):
 *
 * 1. tmux session killed externally — `tmux kill-session -t <name>` or
 *    operator kills the tmux pane. Detected: !alive (tmuxSessionExists)
 *    on next reaper tick (≤ 30s). Tasks auto-unassigned + comment posted.
 *
 * 2. OOM / process crash — the pi agent process dies and tmux exits.
 *    Detected: same as above (!alive). Tasks auto-unassigned.
 *
 * 3. Daemon restart — all tmux sessions survive (they are detached), but
 *    the agents briefly disconnect and reconnect. NOT treated as death
 *    (alive = true). No task impact — the agent resumes its work.
 *
 * 4. Network loss — the agent disconnects from the daemon socket but the
 *    tmux session is still alive. NOT treated as death (alive = true).
 *    The agent reconnects on the next daemon tick.
 *
 * 5. Over-lifetime — worker runs beyond workerMaxLifetimeMin (default 30).
 *    Force-killed by the reaper. Tasks auto-unassigned BEFORE kill.
 *
 * 6. Graceful exit (mail_stop_self) — worker calls mail_stop_self when
 *    done. Session is removed from spawnRegistry BEFORE the tmux kill, so
 *    the reaper never sees it. NOT treated as death — tasks should be
 *    completed/finished by the worker before self-exit.
 *
 * All unexpected deaths are logged with the session name and task count.
 */

import path from "node:path";
import fs from "node:fs";
import crypto from "node:crypto";
import {
  HUMAN_AGENT_ID,
  HUMAN_AGENT_NAME,
  agents,
  log,
  agentDisplayName,
} from "./core.mjs";
import { board, setMmAgentTest, setManagerAgentTest, taskActivity, schedulePersistBoard } from "./board.mjs";
import {
  spawnAgent,
  stopAgent,
  spawnRegistry,
  tmuxSessionExists,
  schedulePersistSpawn,
} from "./spawn.mjs";

/** How often the scheduler + reaper wake up to check. The actual spawn cadence
 *  is gated on `mmIntervalMin`; this is just the polling granularity. */
const MM_TICK_MS = parseInt(process.env.PI_MAIL_MM_TICK_MS || "60000", 10);

/** Session-name prefix for spawned middle-managers, so they're identifiable in
 *  `mail_list_agents` / the web UI. The suffix is a short random id. */
const MM_NAME_PREFIX = "middle-manager";

// ── MM session tracking ──────────────────────────────────────────────────────

/** Persisted across restarts in the spawn registry so the last-spawn timestamp
 *  survives a daemon restart (otherwise a restart would immediately re-spawn).
 *  Restored by loadSpawn() alongside the sessions/projects keys. */
function mmMeta() {
  if (!spawnRegistry.mm) spawnRegistry.mm = { lastSpawnTs: 0 };
  return spawnRegistry.mm;
}

/** Spawned MM sessions tracked by the daemon (registry entries with mm:true). */
function mmSessions() {
  return Object.entries(spawnRegistry.sessions)
    .filter(([, s]) => s.mm)
    .map(([name, s]) => ({ name, ...s }));
}

/** MM sessions whose tmux session is still alive (the agent is still running). */
function liveMMSessions() {
  return mmSessions().filter((s) => tmuxSessionExists(s.name));
}

/**
 * Predicate injected into board.mjs: true when `agentId` belongs to a currently
 * tracked middle-manager session. Gives the MM all-groups board visibility
 * (it oversees multiple projects, so the same-group partition must not hide
 * tasks from it).
 *
 * Two independent match paths, either sufficient (task 16a594db):
 *  1. agentName — the tmux session name, known the moment the agent registers,
 *     so all-groups visibility kicks in immediately, before the agentId is
 *     stamped on the registry entry (the async registration wait may not have
 *     completed yet when the MM fires its first board_list_tasks).
 *  2. agentId — the id stamped on the registry entry once the agent has
 *     registered. A robust fallback that still recognises the MM if its
 *     registered agentName diverged from the session name (e.g. a custom name
 *     restored from a prior session, or a mail_set_name). Without this, a
 *     CEO-spawned MM (auto-named "<dir>-<id6>") that for any reason registered
 *     under a different name would lose all-groups visibility and be unable to
 *     administer cross-group tasks — the live 7/16 MM failure mode.
 */
function isMiddleManager(agentId) {
  if (!agentId || agentId === HUMAN_AGENT_ID) return false;
  const info = agents.get(agentId)?.info;
  const name = info?.agentName;
  for (const s of Object.values(spawnRegistry.sessions)) {
    if (!s.mm) continue;
    if (name && s.agentName === name) return true;
    if (s.agentId && s.agentId === agentId) return true;
  }
  return false;
}

/**
 * When a worker dies unexpectedly, unassign all board tasks they owned
 * and post a comment so the next MM pass (or the operator) can re-dispatch.
 * Does NOT move the task column — the worker may have been in progress.
 * Only the assignee is cleared; the task stays in its current column.
 */
function cleanupTasksForAgent(session, reason) {
  const names = new Set();
  if (session.agentName) names.add(session.agentName);
  // Also match by the agent's registered display name (mail_set_name)
  if (session.agentId) {
    const dn = agentDisplayName(session.agentId);
    if (dn && dn !== session.agentId) names.add(dn);
  }
  if (!names.size) return;
  let count = 0;
  for (const t of board.tasks) {
    if (t.assignee && names.has(t.assignee)) {
      const matched = t.assignee;
      t.assignee = null;
      taskActivity(t, "board", `worker '${matched}' disappeared (${reason || "unknown"}) — auto-unassigned`);
      count++;
    }
  }
  if (count) {
    schedulePersistBoard();
    log(`worker reaper: auto-unassigned ${count} task(s) from '${session.agentName || session.name}' — ${reason || "unknown"}`);
  }
}

// ── Reaper ───────────────────────────────────────────────────────────────────

/**
 * Reap spawned MM sessions: stop any whose tmux session has already ended (the
 * MM exited on its own after signalling completion), and forcibly stop any that
 * have exceeded the configured max lifetime (safety bound so a stuck MM can't
 * run forever or accumulate). Idempotent and cheap; called on every tick.
 */
function reapMiddleManagers(now = Date.now()) {
  const maxLifetimeMs = Math.max(1, (board.config.mmMaxLifetimeMin ?? 15)) * 60_000;
  for (const s of mmSessions()) {
    const alive = tmuxSessionExists(s.name);
    const overLifetime = now - (s.spawnedAt ?? now) > maxLifetimeMs;
    if (!alive) {
      // Agent exited on its own — clean up the registry entry. stopAgent
      // tolerates the tmux session already being gone.
      const r = stopAgent({ name: s.name });
      if (r.error) log(`MM reaper: could not clean up '${s.name}': ${r.error}`);
      else log(`MM reaper: reaped exited session '${s.name}'`);
    } else if (overLifetime) {
      const r = stopAgent({ name: s.name });
      if (r.error) log(`MM reaper: could not stop over-lifetime '${s.name}': ${r.error}`);
      else log(`MM reaper: stopped over-lifetime session '${s.name}' (${Math.round((now - s.spawnedAt) / 60000)}m)`);
    }
  }
}

/** Daemon-spawned WORKER sessions: plain spawns (no mm/ceo/chat flag) — i.e.
 *  agents spawned by an MM, the CEO, the board UI, or `mail_spawn_agent` that
 *  are not themselves management passes and not chat workers. Workers are the
 *  third tier of the CEO → MM → worker hierarchy. Chat workers (chat:true) are
 *  excluded — they have their own idle reaper (lib/chat.mjs). */
function workerSessions() {
  return Object.entries(spawnRegistry.sessions)
    .filter(([, s]) => !s.mm && !s.ceo && !s.chat)
    .map(([name, s]) => ({ name, ...s }));
}

/**
 * Reap spawned WORKER sessions: stop any whose tmux session has already ended
 * (the worker exited on its own), and forcibly stop any that have exceeded the
 * configured worker max lifetime. This is the ephemerality backstop for the
 * third tier — workers are expected to call `mail_stop_self` when their task
 * is done, but a worker that hangs, crashes, or simply forgets is still
 * force-killed at its lifetime boundary so no session ever leaks.
 *
 * Liveness signal: the reaper uses `tmuxSessionExists` (the tmux session / the
 * agent process is alive). It does NOT depend on the agent being responsive —
 * a worker that is "alive but stuck" (in a long turn, not calling
 * `mail_stop_self`) still has a live tmux session, so `alive` is true and the
 * reaper catches it on the over-lifetime branch at its max-lifetime boundary.
 *
 * Cascade cleanup: reaping is independent per tier. When a CEO (or MM) is
 * reaped mid-pass, the workers/MMs it spawned are NOT tracked as its children —
 * they are tracked only as daemon-spawned registry entries. Each is reaped on
 * its own tier's lifetime (worker here, MM by reapMiddleManagers, CEO by
 * reapCeos), so a reaped parent can never leave orphans: every daemon-spawned
 * session is reaped by exactly one tier's reaper regardless of who spawned it.
 * Idempotent and cheap; called on every MM tick (which runs every tick even
 * when the CEO is the sole MM spawner, so workers are reaped either way).
 */
function reapWorkers(now = Date.now()) {
  const maxLifetimeMs = Math.max(1, (board.config.workerMaxLifetimeMin ?? 30)) * 60_000;
  for (const s of workerSessions()) {
    const alive = tmuxSessionExists(s.name);
    const overLifetime = now - (s.spawnedAt ?? now) > maxLifetimeMs;
    if (!alive) {
      // Worker died unexpectedly (not via mail_stop_self — that removes the
      // session from the registry before the tmux kill, so the reaper never
      // sees it here). Auto-unassign their board tasks so work isn't stranded.
      cleanupTasksForAgent(s, "tmux session ended (crash/kill/OOM)");
      const r = stopAgent({ name: s.name });
      if (r.error) log(`worker reaper: could not clean up '${s.name}': ${r.error}`);
      else log(`worker reaper: reaped exited session '${s.name}'`);
    } else if (overLifetime) {
      cleanupTasksForAgent(s, "exceeded max lifetime");
      const r = stopAgent({ name: s.name });
      if (r.error) log(`worker reaper: could not stop over-lifetime '${s.name}': ${r.error}`);
      else log(`worker reaper: stopped over-lifetime session '${s.name}' (${Math.round((now - s.spawnedAt) / 60000)}m)`);
    }
  }
}

// ── Kickoff ──────────────────────────────────────────────────────────────────

/** Build the kickoff task text delivered to a freshly-spawned middle-manager.
 *  Names the managed (favorited) projects and the review/unblock/archive/curate
 *  workflow, and instructs the MM to mail `human` a completion summary then
 *  exit. Resolves each favorite cwd to its project group (basename) so the MM
 *  can filter board_list_tasks to the right tasks. */
function mmKickoff(favorites) {
  const projects = favorites.map((cwd) => {
    const group = path.basename(cwd) || cwd;
    return `  • ${cwd} (group: ${group})`;
  });
  return [
    "You are the middle-manager (MM) for this pi-mail federation cycle. You are a pure manager: you do NOT implement anything yourself — you review the board, unblock workers, and keep tasks moving toward Done.",
    "",
    "## Your pass is a FULL pass — iterate EVERY task in EVERY column before exiting",
    "A pass is NOT one action. You must walk every column in order (Refine, To Do, In Progress, Review, Done) and, for each task the managed projects have on the board, make an explicit decision (refine / dispatch / unblock / move / leave alone). Do NOT stop after the first move, comment, or refine — keep going until you have considered every on-board task for the managed projects, THEN finish. If you find yourself stopping after a single action, you are not done — go back and finish the rest of the board.",
    "",
    "Workers escalate to you by updating the board — a comment, a progress post, or flagging the task unclear. You re-read each task's activity on every cycle, so that is how blockers reach you; workers do not need to mail the human or you directly. Resolve what you can; only mail the human if you genuinely can't resolve a blocker.",
    "",
    "## Managed projects (favorited)",
    "Oversee these projects this cycle:",
    ...projects,
    "",
    "## Your pass (do this once, then finish)",
    "1. Run mail_list_agents to see who is currently connected.",
    `2. Run board_list_tasks (you have all-groups visibility) to see every project's tasks. Focus only on tasks whose group matches one of the managed projects listed above. Note every column these projects have tasks in — you will iterate all of them.`,
    "3. Iterate EVERY column in order, and for EACH task make a decision:",
    "   - Refine: if vague/unclear, refine it (board_update_task to clarify goal/scope/acceptance) and move to To Do; if clear, move to To Do. Also fix the task's group if it's in the wrong project (board_update_task group param) — a mismatched group blocks assignment. Don't leave it parked unless only the human can clarify.",
    "   - To Do: it's actionable — assign it to a live same-group worker (board_assign_task); if no live worker exists, spawn one (mail_spawn_agent) then assign. Don't leave actionable work unassigned.",
    "   - In Progress: board_get_task + check assignee liveness (from mail_list_agents):",
    "     - Stuck / idle worker: mail them a nudge (mail_send) or post a board comment.",
    "     - Dead assignee (not connected, task stalled): reassign to a live same-group worker, or move the task back to To Do / Backlog with a board comment. Do not leave it stuck on a dead worker.",
    "     - Flagged unclear: read the worker's questions in the activity log. If you can resolve it (refine the spec, reassign, find the answer), do so and clear the flag (board_flag_task clear:true). If you genuinely can't, leave it flagged and mention it in your summary to human.",
    "   - Review: the work is done — review it (correctness, tests, scope). If clean, move to Done (board_move_task). If not clean, move back to In Progress with a board comment on what must change.",
    "   - Done: leave it on Done — only the human operator archives tasks.",
    "4. Only AFTER you have considered every task in every column: curate the managed-projects list with mail_set_project_favorite (add a project needing oversight, or unfavorite one fully done — be conservative).",
    "5. Post a short board_comment ONLY on tasks you actually changed (moved, refined, reassigned, cleared a flag, etc.) explaining what you did, so the next agent/operator sees it. If nothing changed on a task, do NOT comment on it.",
    "",
    "## Before you finish",
    "Confirm you have made a decision for EVERY task in EVERY column (Refine, To Do, In Progress, Review, Done) for the managed projects. If you performed one action and were about to exit, STOP — go back and finish the rest of the board first.",
    "",
    "## When you're done",
    `Mail a concise completion summary to "${HUMAN_AGENT_NAME}" (mail_send to "${HUMAN_AGENT_NAME}"): what you reviewed, what you unblocked/moved, and any favorites you added/removed. Then you're finished — call mail_stop_self to tear down your own session (your tmux session is reaped immediately; the reaper is only a fallback).`,
    "",
    "Do not start any new long-running work. This is a single FULL management pass — iterate every task in every column, then finish.",
  ].join("\n");
}

// ── Scheduler ────────────────────────────────────────────────────────────────

/**
 * Spawn one middle-manager for the current cycle. Picks the first favorited
 * project dir as the MM's cwd (the MM is a pure manager and won't edit files;
 * any valid managed dir works). Skips (returns early) if a live MM session is
 * already running (no overlap). Records the spawn timestamp so the next cycle
 * is gated on mmIntervalMin even across ticks.
 */
function spawnMiddleManager(now = Date.now()) {
  if (liveMMSessions().length > 0) return { skipped: "live MM already running" };
  const favorites = spawnRegistry.projects.favorites ?? [];
  if (favorites.length === 0) return { skipped: "no managed projects (favorites empty)" };
  // The MM is a pure manager and won't edit files, so its cwd just needs to be
  // a valid managed dir (used only to launch pi). Pick the first favorited dir
  // that still exists; a deleted managed dir shouldn't skip the whole cycle.
  const cwd = favorites.find((d) => { try { return fs.statSync(d).isDirectory(); } catch { return false; } });
  if (!cwd) {
    log("MM scheduler: all managed (favorited) dirs are missing — skipping cycle");
    return { skipped: "all managed dirs missing" };
  }
  const model = board.config.mmModel && String(board.config.mmModel).trim()
    ? String(board.config.mmModel).trim()
    : undefined;
  const kickoff = mmKickoff(favorites);
  // Identifiable session name so the MM shows up clearly in mail_list_agents
  // and the web UI (and is greppable in the spawn registry).
  const name = `${MM_NAME_PREFIX}-${crypto.randomUUID().slice(0, 6)}`;
  const r = spawnAgent({ cwd, name, model, kickoff, mm: true });
  if (r.error) {
    log(`MM scheduler: spawn failed: ${r.error}`);
    return { error: r.error };
  }
  mmMeta().lastSpawnTs = now;
  schedulePersistSpawn();
  log(`MM scheduler: spawned middle-manager '${r.name}' for ${favorites.length} project(s)`);
  return { ok: true, name: r.name };
}

/**
 * One scheduler tick. Spawns an MM when enabled + favorites non-empty + no live
 * MM + the interval has elapsed; reaps dead/over-lifetime sessions either way.
 * `force` bypasses the interval-elapsed check (an operator "run a cycle now").
 * `now` (default real time) is used for both gating and the recorded spawn ts,
 * so callers can drive time-based gates deterministically (tests). Exported
 * for testing (with a controllable "now").
 */
function mmTick(now = Date.now(), force = false) {
  // Always reap — even when disabled, so previously-spawned workers, MMs, and
  // (via the CEO tick) CEOs that are still tracked get cleaned up if they exit
  // or overstay their lifetimes. The worker reaper is the ephemerality backstop
  // for the third tier (CEO → MM → worker); see reapWorkers. Reaping runs every
  // tick regardless of whether the CEO is the sole MM spawner.
  reapWorkers(now);
  reapMiddleManagers(now);
  if (board.config.mmEnabled !== true) return { reaped: true, spawned: false };
  // When the CEO is enabled, the CEO is the sole spawner of middle managers —
  // the daemon's fixed-interval MM timer must not also spawn MMs (they'd
  // race / double up). Reaping still runs as a safety net. See lib/ceo.mjs.
  if (board.config.ceoEnabled === true) return { reaped: true, spawned: false, reason: "ceo manages MM spawning" };
  const favorites = spawnRegistry.projects.favorites ?? [];
  if (favorites.length === 0) return { reaped: true, spawned: false, reason: "no favorites" };
  if (liveMMSessions().length > 0) return { reaped: true, spawned: false, reason: "live MM running" };
  const intervalMs = Math.max(1, (board.config.mmIntervalMin ?? 30)) * 60_000;
  if (!force && now - (mmMeta().lastSpawnTs ?? 0) < intervalMs) {
    return { reaped: true, spawned: false, reason: "interval not elapsed" };
  }
  const r = spawnMiddleManager(now);
  return { reaped: true, spawned: !!r.ok, ...r };
}

/** Snapshot of MM state, for inspection / the UI / tests. */
function mmState() {
  const sessions = mmSessions().map((s) => ({
    name: s.name,
    cwd: s.cwd,
    spawnedAt: s.spawnedAt,
    alive: tmuxSessionExists(s.name),
  }));
  const workers = workerSessions().map((s) => ({
    name: s.name,
    cwd: s.cwd,
    spawnedAt: s.spawnedAt,
    alive: tmuxSessionExists(s.name),
  }));
  return {
    enabled: board.config.mmEnabled === true,
    intervalMin: board.config.mmIntervalMin ?? 30,
    model: board.config.mmModel ?? "",
    maxLifetimeMin: board.config.mmMaxLifetimeMin ?? 15,
    workerMaxLifetimeMin: board.config.workerMaxLifetimeMin ?? 30,
    lastSpawnTs: mmMeta().lastSpawnTs ?? 0,
    managedProjects: (spawnRegistry.projects.favorites ?? []).slice(),
    sessions,
    workers,
  };
}

/** Start the periodic scheduler + reaper loop. Called once from daemon.mjs at
 *  boot. Also injects the all-groups predicate into board.mjs so spawned MMs
 *  can see every project's tasks. */
let mmTimer = null;
function startMiddleManagerLoop() {
  // The all-groups visibility predicate is shared with the CEO (both are
  // managers that oversee multiple projects). ceo.mjs composes the combined
  // predicate (isMiddleManager || isCeo) and calls setManagerAgentTest; here
  // we just keep the legacy alias wired for backward-compat / standalone use.
  setMmAgentTest(isMiddleManager);
  setManagerAgentTest(isMiddleManager);
  if (mmTimer) clearInterval(mmTimer);
  mmTimer = setInterval(() => {
    try {
      mmTick();
    } catch (e) {
      log(`MM scheduler error: ${e?.message ?? String(e)}`);
    }
  }, MM_TICK_MS);
  // Reap any leftover MM + worker sessions from a previous run immediately on
  // boot (workers too — a daemon restart must not leave hung workers leaking).
  reapWorkers();
  reapMiddleManagers();
}

export {
  mmTick,
  spawnMiddleManager,
  reapMiddleManagers,
  reapWorkers,
  workerSessions,
  isMiddleManager,
  mmKickoff,
  mmState,
  startMiddleManagerLoop,
  MM_NAME_PREFIX,
};
