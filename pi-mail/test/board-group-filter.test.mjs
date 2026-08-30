// Tests for the board_list_tasks group filter (task b59e930a).
//
// board_list_tasks was same-group only by default; the CEO (documented as having
// all-groups visibility) could not scope to other groups/specific projects
// explicitly. This adds an optional `group` param to boardState (and wires it
// through the board tool / protocol / HTTP / MCP layers):
//   - default (no group): caller's own group (workers) / all groups (human +
//     manager agents) — unchanged.
//   - group: "all"   → every task, every group (cross-group).
//   - group: "<name>" → only that group's tasks.
//
// These are pure unit tests of boardState (board.mjs): they seed `board.tasks`
// + the `agents` registry directly, no daemon/socket/tmux/network. They cover
// the three cases the spec requires — same-group default, group:"all", and a
// specific group filter — plus the returned `group` scope field and the
// human/operator all-groups default.
//
// Run: npm test

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { board, boardState, setManagerAgentTest } from "../extensions/lib/board.mjs";
import { agents, HUMAN_AGENT_ID } from "../extensions/lib/core.mjs";

// ── fixtures ──────────────────────────────────────────────────────────────────
//
// Three project groups (alpha, beta, gamma) + one ungrouped task, plus a worker
// agent in the "alpha" group and a manager agent. The board's columns are left
// as the real defaults (boardState only groups/filters, it doesn't mutate).

const WORKER_ALPHA = "aaaaaaaa-0000-0000-0000-000000000001";
const MANAGER = "bbbbbbbb-0000-0000-0000-000000000002";

const FIXTURE_TASKS = [
  { id: "t-alpha-1", summary: "alpha task 1", columnId: "todo", assignee: null, group: "alpha", location: "board" },
  { id: "t-alpha-2", summary: "alpha task 2", columnId: "todo", assignee: null, group: "alpha", location: "board" },
  { id: "t-beta-1", summary: "beta task 1", columnId: "todo", assignee: null, group: "beta", location: "board" },
  { id: "t-beta-2", summary: "beta task 2", columnId: "todo", assignee: null, group: "beta", location: "backlog" },
  { id: "t-gamma-1", summary: "gamma task 1", columnId: "todo", assignee: null, group: "gamma", location: "board" },
  { id: "t-none-1", summary: "ungrouped task", columnId: "todo", assignee: null, group: null, location: "board" },
];

let savedTasks, savedManagerTest;

before(() => {
  savedTasks = board.tasks;
  savedManagerTest = null; // managerAgentTest defaults to null in a fresh import
  board.tasks = FIXTURE_TASKS.map((t) => ({ ...t }));
  // Worker agent "alpha" lives in /tmp/alpha (group = "alpha" via cwd basename).
  agents.set(WORKER_ALPHA, { info: { cwd: "/tmp/alpha", agentName: "alpha-worker" } });
  // A manager agent (no cwd group needed — gated by the predicate, not cwd).
  agents.set(MANAGER, { info: { cwd: "/tmp/manager", agentName: "the-mm" } });
});

after(() => {
  board.tasks = savedTasks;
  agents.delete(WORKER_ALPHA);
  agents.delete(MANAGER);
  setManagerAgentTest(savedManagerTest);
});

const ids = (st) => st.tasks.map((t) => t.id).sort();

test("default (no group): a worker sees only its own group (+ ungrouped) (b59e930a)", () => {
  const st = boardState(WORKER_ALPHA, { includeArchived: false });
  // alpha tasks + the ungrouped task; no beta/gamma.
  assert.deepEqual(ids(st), ["t-alpha-1", "t-alpha-2", "t-none-1"]);
  assert.equal(st.myGroup, "alpha");
  assert.equal(st.group, null, "no explicit group filter → group field is null");
});

test("default (no group): the human operator sees all groups (b59e930a)", () => {
  const st = boardState(HUMAN_AGENT_ID, { includeArchived: false });
  assert.equal(st.tasks.length, FIXTURE_TASKS.length, "human sees every task");
  assert.equal(st.myGroup, null);
  assert.equal(st.group, null);
});

test("default (no group): a manager agent sees all groups (b59e930a)", () => {
  setManagerAgentTest((id) => id === MANAGER);
  try {
    const st = boardState(MANAGER, { includeArchived: false });
    assert.equal(st.tasks.length, FIXTURE_TASKS.length, "manager sees every task (all-groups visibility)");
    assert.equal(st.group, null, "default scope → group field is null");
  } finally {
    setManagerAgentTest(null);
  }
});

test("group: 'all' returns every task cross-group, regardless of caller (b59e930a)", () => {
  // Even a worker (normally same-group only) gets the cross-group view.
  const st = boardState(WORKER_ALPHA, { group: "all", includeArchived: false });
  assert.equal(st.tasks.length, FIXTURE_TASKS.length, "group:all returns every task");
  assert.equal(st.group, "all", "group field reflects the explicit 'all' filter");
});

test("group: '<name>' returns only that group's tasks (b59e930a)", () => {
  // A worker scoping to a different group sees that group's tasks.
  const st = boardState(WORKER_ALPHA, { group: "beta", includeArchived: false });
  assert.deepEqual(ids(st), ["t-beta-1", "t-beta-2"], "specific-group filter returns only that group");
  assert.equal(st.group, "beta", "group field reflects the specific group");
  // Ungrouped tasks are NOT included under a specific group filter.
  assert.ok(!ids(st).includes("t-none-1"), "ungrouped task excluded from a named-group filter");
});

test("group: 'all' is independent of location/archive filtering (b59e930a)", () => {
  // group:"all" + location:"backlog" → every group's backlog tasks only.
  const st = boardState(WORKER_ALPHA, { group: "all", location: "backlog", includeArchived: false });
  assert.deepEqual(ids(st), ["t-beta-2"], "group:all + location:backlog intersects correctly");
  assert.equal(st.group, "all");
});

test("group filter on an empty group returns no tasks (b59e930a)", () => {
  const st = boardState(WORKER_ALPHA, { group: "does-not-exist", includeArchived: false });
  assert.equal(st.tasks.length, 0);
  assert.equal(st.group, "does-not-exist");
});
