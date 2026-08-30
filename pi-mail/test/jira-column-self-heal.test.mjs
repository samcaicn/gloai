// Tests for task 4b60ea0b: syncBoard() must self-heal a task's columnId when
// the remote status is UNCHANGED but no column was mapped to that status at
// the time the task was first imported (or the mapping was added/fixed
// later). Root cause of the reported bug: a Jira project using non-English
// status names (e.g. "Gesloten", "On Hold", "Actief") with only English
// columns mapped ("To Do"/"In Progress"/"Done") — every issue fell back to
// the fallback column at import and, because task.jiraStatus never changed
// afterwards, was never corrected even after the right column was added.
//
// This does NOT touch Jira (no transition, no push) — it's a pure local
// re-home once the mapping catches up to reality.
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
  // See jira-restore-from-archive.test.mjs: syncBoard() persists via the real
  // (non-test-isolated) BOARD_FILE. Stub the write for this file's duration.
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
  return { ok: true, status: 200, text: async () => JSON.stringify(body) };
}

function mockFetchFor(issue) {
  globalThis.fetch = async (url) => {
    const u = String(url);
    if (u.includes("/rest/api/3/search/jql")) {
      if (u.includes("parent%20in") || u.includes("parent in")) return jsonResponse({ issues: [] });
      return jsonResponse({ issues: [issue] });
    }
    throw new Error(`unexpected fetch: ${u}`);
  };
}

function jiraTask(overrides) {
  return {
    id: "t-heal-1",
    key: "PROJ-700",
    origin: "jira",
    summary: "some task",
    description: "",
    url: "https://acme.atlassian.net/browse/PROJ-700",
    jiraStatus: "Gesloten",
    columnId: "todo", // stuck at the fallback column from import time
    assignee: null,
    priority: null,
    issueType: "Task",
    parentId: null,
    parentKey: null,
    flagged: null,
    knownCommentIds: [],
    updatedAt: 0,
    location: "board",
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
  // DEFAULT_COLUMNS + a newly-added "Gesloten" column (simulating a
  // "Fetch from Jira" run that added a mapping for the previously-unmapped
  // Dutch status), mirroring the production incident.
  board.columns = [
    ...DEFAULT_COLUMNS.map((c) => ({ ...c })),
    { id: "gesloten", name: "Gesloten", jiraStatus: "Gesloten", instructions: "" },
  ];
});

test("syncBoard: self-heals columnId when status is unchanged but a matching column now exists", async () => {
  const task = jiraTask({ columnId: "todo", jiraStatus: "Gesloten" });
  board.tasks = [task];
  mockFetchFor(issueFor("PROJ-700", "Gesloten")); // unchanged remote status

  await syncBoard("manual");

  const t = board.tasks.find((x) => x.key === "PROJ-700");
  assert.equal(t.jiraStatus, "Gesloten", "status itself is unchanged");
  assert.equal(t.columnId, "gesloten", "columnId corrected to the now-mapped column");
  assert.equal(t.location, "board");
  const entry = t.activity.find((a) => /column corrected/.test(a.text));
  assert.ok(entry, "activity records the self-heal");
  assert.match(entry.text, /To Do → Gesloten/);
});

test("syncBoard: no-op when columnId already matches the mapped column", async () => {
  const task = jiraTask({ columnId: "gesloten", jiraStatus: "Gesloten" });
  board.tasks = [task];
  mockFetchFor(issueFor("PROJ-700", "Gesloten"));

  await syncBoard("manual");

  const t = board.tasks.find((x) => x.key === "PROJ-700");
  assert.equal(t.columnId, "gesloten");
  assert.equal(t.activity.length, 0, "no churn when already correctly placed");
});

test("syncBoard: self-heal does not fire for backlog/archive tasks (those go through the restore path instead)", async () => {
  const task = jiraTask({ columnId: null, location: "archive", jiraStatus: "Gesloten" });
  board.tasks = [task];
  mockFetchFor(issueFor("PROJ-700", "Gesloten")); // unchanged status

  await syncBoard("manual");

  const t = board.tasks.find((x) => x.key === "PROJ-700");
  assert.equal(t.location, "archive", "archived + unchanged status stays put (no self-heal, no restore)");
  assert.equal(t.columnId, null);
  assert.equal(t.activity.length, 0);
});

test("syncBoard: self-heal does not fire when no column maps the status yet", async () => {
  const task = jiraTask({ columnId: "todo", jiraStatus: "Onbekende Status" });
  board.tasks = [task];
  mockFetchFor(issueFor("PROJ-700", "Onbekende Status"));

  await syncBoard("manual");

  const t = board.tasks.find((x) => x.key === "PROJ-700");
  assert.equal(t.columnId, "todo", "left alone \u2014 nothing to heal to");
  assert.equal(t.activity.length, 0);
});
