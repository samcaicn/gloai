// Tests for the Jira-disable master switch (task 6e6e2ab2).
//
// Adds a `jiraEnabled` config flag (default true → no behaviour change for
// existing users). When false the board runs in board-only mode:
//   - jiraCfg() returns null → no network calls (sync, transitions, comments,
//     issue creation all short-circuit), jiraConfigured reports false.
//   - boardState scrubs every Jira ticket reference (key, jiraStatus, url,
//     parentKey, origin→"local") from its returned VIEW so board_list_tasks
//     and all board requests surface zero Jira info. Stored state is
//     untouched, so re-enabling restores the keys.
//
// These are pure unit tests of board.mjs (jiraCfg + boardState): they seed
// board.config / board.columns / board.tasks directly — no daemon/socket/
// tmux/network. They mirror the style of board-group-filter.test.mjs.
//
// Run: npm test

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import {
  board,
  jiraCfg,
  boardState,
  DEFAULT_COLUMNS,
} from "../extensions/lib/board.mjs";
import { HUMAN_AGENT_ID } from "../extensions/lib/core.mjs";

// A board with one Jira-origin task (key/status/url/parentKey set) and one
// local task, plus a Jira-mapped column, so we can assert scrubbing.
const JIRA_TASK = {
  id: "t-jira-1",
  key: "PROJ-123",
  origin: "jira",
  summary: "imported from jira",
  description: "",
  url: "https://acme.atlassian.net/browse/PROJ-123",
  jiraStatus: "In Progress",
  columnId: "inprogress",
  assignee: null,
  priority: "High",
  issueType: "Task",
  parentId: null,
  parentKey: "PROJ-100",
  flagged: null,
  updatedAt: 0,
  location: "board",
  level: "task",
  activity: [],
};
const LOCAL_TASK = {
  id: "t-local-1",
  key: null,
  origin: "local",
  summary: "a local task",
  description: "",
  url: null,
  jiraStatus: null,
  columnId: "todo",
  assignee: null,
  priority: null,
  issueType: null,
  parentId: null,
  parentKey: null,
  flagged: null,
  updatedAt: 0,
  location: "board",
  level: "task",
  activity: [],
};

let savedCfg, savedColumns, savedTasks;

before(() => {
  savedCfg = { ...board.config };
  savedColumns = board.columns;
  savedTasks = board.tasks;
  board.columns = DEFAULT_COLUMNS;
  board.tasks = [JIRA_TASK, LOCAL_TASK];
});

after(() => {
  board.config = savedCfg;
  board.columns = savedColumns;
  board.tasks = savedTasks;
});

function withCreds() {
  // Credentials present so jiraCfg() would be non-null when enabled.
  board.config.baseUrl = "https://acme.atlassian.net";
  board.config.email = "bot@acme.com";
  board.config.apiToken = "secret-token";
}

test("jiraEnabled defaults to true (no behaviour change for existing users)", () => {
  withCreds();
  delete board.config.jiraEnabled; // force default
  assert.equal(jiraCfg(), board.config, "with creds + default flag → Jira is active");
  const st = boardState(HUMAN_AGENT_ID, { includeArchived: false });
  assert.equal(st.jiraEnabled, true);
  assert.equal(st.jiraConfigured, true);
});

test("jiraEnabled:false makes jiraCfg() return null even with credentials set", () => {
  withCreds();
  board.config.jiraEnabled = false;
  assert.equal(jiraCfg(), null, "disabled short-circuits jiraCfg regardless of creds");
  const st = boardState(HUMAN_AGENT_ID, { includeArchived: false });
  assert.equal(st.jiraEnabled, false);
  assert.equal(st.jiraConfigured, false, "jiraConfigured reports false when disabled");
});

test("jiraEnabled:false scrubs all Jira ticket references from the board view", () => {
  withCreds();
  board.config.jiraEnabled = false;
  const st = boardState(HUMAN_AGENT_ID, { includeArchived: false });
  const jiraView = st.tasks.find((t) => t.id === "t-jira-1");
  assert.equal(jiraView.key, null, "key scrubbed");
  assert.equal(jiraView.jiraStatus, null, "jiraStatus scrubbed");
  assert.equal(jiraView.url, null, "url scrubbed");
  assert.equal(jiraView.parentKey, null, "parentKey scrubbed (was a Jira key)");
  assert.equal(jiraView.origin, "local", "origin relabelled to local");
  // Non-Jira metadata is preserved (these aren't ticket references).
  assert.equal(jiraView.summary, "imported from jira");
  assert.equal(jiraView.priority, "High");
  assert.equal(jiraView.id, "t-jira-1");
});

test("jiraEnabled:false scrubs jiraStatus off Jira-mapped columns", () => {
  withCreds();
  board.config.jiraEnabled = false;
  const st = boardState(HUMAN_AGENT_ID, { includeArchived: false });
  const inProgress = st.columns.find((c) => c.id === "inprogress");
  assert.equal(inProgress.jiraStatus, null, "column jiraStatus scrubbed → renders as board-only");
  // The stored column is untouched (only the view copy is scrubbed).
  assert.equal(board.columns.find((c) => c.id === "inprogress").jiraStatus, "In Progress");
});

test("jiraEnabled:false leaves local tasks untouched in the view", () => {
  withCreds();
  board.config.jiraEnabled = false;
  const st = boardState(HUMAN_AGENT_ID, { includeArchived: false });
  const localView = st.tasks.find((t) => t.id === "t-local-1");
  // No shallow-copy needed → same reference, unchanged.
  assert.equal(localView, LOCAL_TASK, "local task with no Jira fields is returned as-is");
});

test("stored state is NOT mutated by the scrub (re-enabling restores keys)", () => {
  withCreds();
  board.config.jiraEnabled = false;
  boardState(HUMAN_AGENT_ID, { includeArchived: false });
  const stored = board.tasks.find((t) => t.id === "t-jira-1");
  assert.equal(stored.key, "PROJ-123", "stored key intact");
  assert.equal(stored.jiraStatus, "In Progress", "stored status intact");
  assert.equal(stored.url, "https://acme.atlassian.net/browse/PROJ-123", "stored url intact");
  assert.equal(stored.origin, "jira", "stored origin intact");
  // Re-enable → keys reappear in the view.
  board.config.jiraEnabled = true;
  const st = boardState(HUMAN_AGENT_ID, { includeArchived: false });
  const jiraView = st.tasks.find((t) => t.id === "t-jira-1");
  assert.equal(jiraView.key, "PROJ-123", "key visible again after re-enabling");
  assert.equal(jiraView.origin, "jira");
  assert.equal(st.jiraConfigured, true);
});

test("jiraEnabled:true with no credentials scrubs the view (board-only mode)", () => {
  // Regression (human report 7/16): the board was "not configured" (no creds)
  // yet Jira ticket refs still surfaced because the scrub only fired on
  // jiraEnabled:false. Board-only mode = Jira effectively off for ANY reason
  // (disabled flag OR no creds) → the view must contain zero Jira references.
  board.config.jiraEnabled = true;
  board.config.baseUrl = "";
  board.config.email = "";
  board.config.apiToken = "";
  assert.equal(jiraCfg(), null, "enabled but unconfigured → null (board-only)");
  const st = boardState(HUMAN_AGENT_ID, { includeArchived: false });
  assert.equal(st.jiraEnabled, true, "master switch still reflects user intent");
  assert.equal(st.jiraConfigured, false);
  // Board-only → view scrubbed even though the flag is on.
  const jiraView = st.tasks.find((t) => t.id === "t-jira-1");
  assert.equal(jiraView.key, null, "key scrubbed when not configured");
  assert.equal(jiraView.jiraStatus, null, "jiraStatus scrubbed when not configured");
  assert.equal(jiraView.url, null, "url scrubbed when not configured");
  assert.equal(jiraView.origin, "local", "origin relabelled when not configured");
  const inProgress = st.columns.find((c) => c.id === "inprogress");
  assert.equal(inProgress.jiraStatus, null, "column jiraStatus scrubbed → no (jira: …) annotation");
  // Stored state intact → adding creds later restores keys in the view.
  const stored = board.tasks.find((t) => t.id === "t-jira-1");
  assert.equal(stored.key, "PROJ-123", "stored key intact while unconfigured");
});

test("board-only view contains zero Jira references in serialized output", () => {
  // End-to-end check of the acceptance criterion: with Jira off, the VIEW
  // (tasks + columns) must serialise to zero Jira ticket references — no key,
  // jiraStatus, url, parentKey, origin:jira, or column jiraStatus anywhere.
  withCreds();
  board.config.jiraEnabled = false;
  const st = boardState(HUMAN_AGENT_ID, { includeArchived: false });
  const blob = JSON.stringify(st);
  assert.match(blob, /"origin":"local"/);
  assert.doesNotMatch(blob, /PROJ-123/);
  assert.doesNotMatch(blob, /PROJ-100/);
  assert.doesNotMatch(blob, /atlassian\.net/);
  assert.doesNotMatch(blob, /"jiraStatus":"[^"]+"/);
  assert.doesNotMatch(blob, /"key":"[A-Z]+-\d+"/);
});

test("board-only: config source keeps jiraStatus while the view scrubs it (settings bug e896f531)", () => {
  // Regression (human report 7/16, Settings → columns → Jira status empty):
  // the Settings columns editor must show + edit the *stored* column↔jiraStatus
  // mapping even in board-only mode, so re-enabling Jira restores it. The view
  // scrub (boardState) hides jiraStatus from /api/board; the config endpoint
  // (GET /api/board/config returns `board.columns` directly, unscrubbed) must
  // keep it. This test pins the two-source contract the UI relies on:
  //   - boardState().columns  → jiraStatus scrubbed to null (board view)
  //   - board.columns         → jiraStatus intact (config/settings source)
  withCreds();
  board.config.jiraEnabled = false;
  const view = boardState(HUMAN_AGENT_ID, { includeArchived: false });
  const inProgressView = view.columns.find((c) => c.id === "inprogress");
  const inProgressCfg = board.columns.find((c) => c.id === "inprogress");
  assert.equal(inProgressView.jiraStatus, null, "view scrubbed — board_list_tasks hides (jira: …)");
  assert.equal(inProgressCfg.jiraStatus, "In Progress", "config source intact — Settings can show/edit/persist the mapping");
  // Re-enabling restores the mapping in the view.
  board.config.jiraEnabled = true;
  const viewOn = boardState(HUMAN_AGENT_ID, { includeArchived: false });
  assert.equal(viewOn.columns.find((c) => c.id === "inprogress").jiraStatus, "In Progress", "view restored after re-enabling");
});
