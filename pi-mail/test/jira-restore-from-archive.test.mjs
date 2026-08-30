// Tests for task 6db653a3: syncBoard() must treat Jira as the source of
// truth for Jira-origin tasks — a task parked in backlog/archive is only
// "sticky" while the remote Jira status is unchanged. The moment the remote
// status changes, the task is pulled back onto the board into the mapped
// column (even though it was archived/backlogged locally).
//
// These tests mock global.fetch (jiraFetch's transport) so no real network
// calls are made. They mirror the JQL search → subtask search → pinned-task
// flow inside syncBoard().
//
// Run: npm test

import { test, before, after, beforeEach } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import { syncBoard } from "../extensions/lib/jira.mjs";
import { board, DEFAULT_COLUMNS } from "../extensions/lib/board.mjs";

let savedCfg, savedColumns, savedTasks, savedFetch, savedWriteFileSync;

before(() => {
  savedCfg = { ...board.config };
  savedColumns = board.columns;
  savedTasks = board.tasks;
  savedFetch = globalThis.fetch;
  // syncBoard() unconditionally calls schedulePersistBoard() → a 300ms-
  // debounced fs.writeFileSync(BOARD_FILE, ...) in lib/board.mjs. BOARD_FILE
  // is NOT test-isolated (it's the real ~/.pi/agent/mail-board.json shared
  // with any live daemon on this host), so calling the real syncBoard() from
  // a unit test must never let that write hit disk. Stub fs.writeFileSync for
  // the duration of this file's tests (restored in `after`, well before the
  // 300ms debounce could fire past the test boundary in practice, and always
  // harmless even if it does since board state is restored first).
  savedWriteFileSync = fs.writeFileSync;
  fs.writeFileSync = () => {};
});

after(() => {
  board.config = savedCfg;
  board.columns = savedColumns;
  board.tasks = savedTasks;
  globalThis.fetch = savedFetch;
  fs.writeFileSync = savedWriteFileSync;
});

function jsonResponse(body) {
  return {
    ok: true,
    status: 200,
    text: async () => JSON.stringify(body),
  };
}

/** Installs a fetch mock: the initial JQL search returns `issue` (once),
 *  every subsequent search (subtask lookups) returns no issues. */
function mockFetchFor(issue) {
  globalThis.fetch = async (url) => {
    const u = String(url);
    if (u.includes("/rest/api/3/search/jql")) {
      if (u.includes("parent%20in") || u.includes("parent in")) {
        return jsonResponse({ issues: [] });
      }
      return jsonResponse({ issues: [issue] });
    }
    throw new Error(`unexpected fetch: ${u}`);
  };
}

function jiraTask(overrides) {
  return {
    id: "t-jira-restore-1",
    key: "PROJ-500",
    origin: "jira",
    summary: "some task",
    description: "",
    url: "https://acme.atlassian.net/browse/PROJ-500",
    jiraStatus: "Done",
    columnId: null,
    assignee: null,
    priority: null,
    issueType: "Task",
    parentId: null,
    parentKey: null,
    flagged: null,
    knownCommentIds: [],
    updatedAt: 0,
    location: "archive",
    level: "task",
    epicId: null,
    activity: [],
    ...overrides,
  };
}

function issueFor(key, statusName) {
  return {
    key,
    fields: {
      summary: "some task",
      description: null,
      status: { name: statusName },
      priority: null,
      issuetype: { name: "Task" },
      parent: null,
      comment: { comments: [] },
    },
  };
}

beforeEach(() => {
  board.config.jiraEnabled = true;
  board.config.baseUrl = "https://acme.atlassian.net";
  board.config.email = "bot@acme.com";
  board.config.apiToken = "secret-token";
  board.config.projectKey = "";
  board.config.jql = "assignee = currentUser()";
  board.columns = DEFAULT_COLUMNS.map((c) => ({ ...c }));
});

test("syncBoard: restores an archived Jira-origin task when its remote status changes", async () => {
  const task = jiraTask({ location: "archive", jiraStatus: "Done", columnId: null });
  board.tasks = [task];
  mockFetchFor(issueFor("PROJ-500", "In Progress"));

  await syncBoard("manual");

  const t = board.tasks.find((x) => x.key === "PROJ-500");
  assert.equal(t.location, "board", "restored out of archive onto the board");
  assert.equal(t.jiraStatus, "In Progress", "jiraStatus updated to the new remote status");
  const inProgress = board.columns.find((c) => c.jiraStatus === "In Progress");
  assert.equal(t.columnId, inProgress.id, "columnId remapped to the column mapped from the new status");
  const restoreEntry = t.activity.find((a) => /restored from archive/.test(a.text));
  assert.ok(restoreEntry, "activity records the restore + status change");
  assert.match(restoreEntry.text, /Jira status changed → In Progress/);
});

test("syncBoard: restores a backlogged Jira-origin task when its remote status changes", async () => {
  const task = jiraTask({ location: "backlog", jiraStatus: "To Do", columnId: null });
  board.tasks = [task];
  mockFetchFor(issueFor("PROJ-500", "In Progress"));

  await syncBoard("manual");

  const t = board.tasks.find((x) => x.key === "PROJ-500");
  assert.equal(t.location, "board", "restored out of backlog onto the board");
  assert.equal(t.jiraStatus, "In Progress");
  const inProgress = board.columns.find((c) => c.jiraStatus === "In Progress");
  assert.equal(t.columnId, inProgress.id);
  const restoreEntry = t.activity.find((a) => /restored from backlog/.test(a.text));
  assert.ok(restoreEntry, "activity records the restore + status change");
});

test("syncBoard: leaves an archived task untouched when its remote status is unchanged (no-op)", async () => {
  const task = jiraTask({ location: "archive", jiraStatus: "Done", columnId: null });
  board.tasks = [task];
  mockFetchFor(issueFor("PROJ-500", "Done"));

  await syncBoard("manual");

  const t = board.tasks.find((x) => x.key === "PROJ-500");
  assert.equal(t.location, "archive", "stays archived — no churn on unchanged status");
  assert.equal(t.jiraStatus, "Done");
  assert.equal(t.columnId, null, "columnId untouched");
  assert.equal(t.activity.length, 0, "no activity entry added for a no-op sync");
});

test("syncBoard: leaves a backlogged task untouched when its remote status is unchanged (no-op)", async () => {
  const task = jiraTask({ location: "backlog", jiraStatus: "To Do", columnId: null });
  board.tasks = [task];
  mockFetchFor(issueFor("PROJ-500", "To Do"));

  await syncBoard("manual");

  const t = board.tasks.find((x) => x.key === "PROJ-500");
  assert.equal(t.location, "backlog");
  assert.equal(t.jiraStatus, "To Do");
  assert.equal(t.columnId, null);
  assert.equal(t.activity.length, 0);
});

test("syncBoard: board-only (local) tasks are never touched by the restore logic", async () => {
  const localTask = {
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
    location: "backlog",
    level: "task",
    activity: [],
  };
  board.tasks = [localTask];
  mockFetchFor(issueFor("PROJ-999", "In Progress")); // unrelated Jira issue, no local match

  await syncBoard("manual");

  const t = board.tasks.find((x) => x.id === "t-local-1");
  assert.ok(t, "local task is not removed by the not-seen-in-Jira filter");
  assert.equal(t.location, "backlog", "board-only task location untouched");
  assert.equal(t.columnId, "todo");
  assert.equal(t.activity.length, 0);
});

test("syncBoard: a board-located Jira task still uses the existing (non-restore) status-change path", async () => {
  const task = jiraTask({ location: "board", jiraStatus: "To Do", columnId: "todo" });
  board.tasks = [task];
  mockFetchFor(issueFor("PROJ-500", "In Progress"));

  await syncBoard("manual");

  const t = board.tasks.find((x) => x.key === "PROJ-500");
  assert.equal(t.location, "board");
  const inProgress = board.columns.find((c) => c.jiraStatus === "In Progress");
  assert.equal(t.columnId, inProgress.id);
  const entry = t.activity.find((a) => /Jira status changed/.test(a.text));
  assert.ok(entry);
  assert.doesNotMatch(entry.text, /restored from/, "board-located tasks use the plain status-change message");
});
