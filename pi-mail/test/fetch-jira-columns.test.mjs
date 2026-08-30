// Tests for the "fetch from Jira" column-mapping refresh (task f056632d).
//
// Adds an on-demand fetch that pulls the remote Jira project's board columns
// and reconciles the board's column↔jiraStatus mapping (non-destructive).
//   - mergeJiraColumns(columns, remoteStatuses): pure reconciliation — adds a
//     new Jira-mapped column for an unmapped status, promotes a same-named
//     board-only column, never removes user columns/instructions. Mutates the
//     array in place; returns { added, promoted }.
//   - fetchJiraColumns(): the network half (agile board config → project
//     statuses fallback). No-op (no network) when Jira is disabled or
//     unconfigured, or when no project key is set.
//
// mergeJiraColumns is the substantive logic and is fully unit-tested here
// (no daemon/socket/network). fetchJiraColumns is tested for its gating
// (no-op + no network when off), the acceptance criterion that the feature
// makes no Jira calls in board-only mode.
//
// Run: npm test

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { mergeJiraColumns, fetchJiraColumns } from "../extensions/lib/jira.mjs";
import { board, jiraCfg, DEFAULT_COLUMNS } from "../extensions/lib/board.mjs";

let savedCfg, savedColumns, savedTasks;

before(() => {
  savedCfg = { ...board.config };
  savedColumns = board.columns;
  savedTasks = board.tasks;
});

after(() => {
  board.config = savedCfg;
  board.columns = savedColumns;
  board.tasks = savedTasks;
});

// ── mergeJiraColumns (pure reconciliation) ───────────────────────────────────

function cols(...defs) {
  // defs: [name, jiraStatus|null, instructions?]
  return defs.map(([name, jiraStatus, instructions], i) => ({
    id: name.toLowerCase().replace(/[^a-z0-9]+/g, "-") || `c${i}`,
    name,
    jiraStatus: jiraStatus ?? null,
    instructions: instructions ?? "",
  }));
}

test("mergeJiraColumns: adds a new Jira-mapped column for an unmapped status", () => {
  const columns = cols(["To Do", "To Do"], ["In Progress", "In Progress"], ["Done", "Done"]);
  const { added, promoted } = mergeJiraColumns(columns, ["Blocked"]);
  assert.deepEqual(added, ["Blocked"]);
  assert.deepEqual(promoted, []);
  const blocked = columns.find((c) => c.name === "Blocked");
  assert.equal(blocked.jiraStatus, "Blocked");
  assert.equal(blocked.instructions, "");
  assert.ok(blocked.id, "new column gets a slug id");
});

test("mergeJiraColumns: inserts a new mapped column after the last Jira-mapped column", () => {
  // Refine(board), To Do(mapped), In Progress(mapped), Review(board), Done(mapped)
  const columns = cols(
    ["Refine", null, "Refine it"],
    ["To Do", "To Do"],
    ["In Progress", "In Progress"],
    ["Review", null, "Review it"],
    ["Done", "Done"],
  );
  const before = columns.map((c) => c.name);
  mergeJiraColumns(columns, ["Blocked"]);
  // Last mapped column is Done (index 4) → new column inserted at index 5,
  // ahead of nothing trailing here (Done is last) → appended at the end.
  const after = columns.map((c) => c.name);
  assert.deepEqual(before, ["Refine", "To Do", "In Progress", "Review", "Done"]);
  assert.deepEqual(after, ["Refine", "To Do", "In Progress", "Review", "Done", "Blocked"]);
});

test("mergeJiraColumns: clusters new mapped columns before a trailing board-only column", () => {
  // To Do(mapped), In Progress(mapped), Review(board, trailing) → a new status
  // should insert after In Progress (the last mapped), i.e. BEFORE Review.
  const columns = cols(
    ["To Do", "To Do"],
    ["In Progress", "In Progress"],
    ["Review", null, "Review it"],
  );
  mergeJiraColumns(columns, ["Blocked"]);
  assert.deepEqual(
    columns.map((c) => c.name),
    ["To Do", "In Progress", "Blocked", "Review"],
    "new mapped column clusters with mapped columns, ahead of trailing board-only Review",
  );
  // The board-only column's instructions are untouched.
  assert.equal(columns.find((c) => c.name === "Review").instructions, "Review it");
});

test("mergeJiraColumns: promotes a same-named board-only column to Jira-mapped", () => {
  // A board-only "In Review" column that matches a remote "In Review" status.
  const columns = cols(
    ["To Do", "To Do"],
    ["In Review", null, "custom review instructions"],
  );
  const { added, promoted } = mergeJiraColumns(columns, ["In Review"]);
  assert.deepEqual(promoted, ["In Review"]);
  assert.deepEqual(added, []);
  const promotedCol = columns.find((c) => c.name === "In Review");
  assert.equal(promotedCol.jiraStatus, "In Review", "now maps to the Jira status");
  assert.equal(promotedCol.instructions, "custom review instructions", "instructions kept");
  assert.equal(columns.length, 2, "no new column added — existing one promoted");
});

test("mergeJiraColumns: no-op for a status already mapped (case-insensitive)", () => {
  const columns = cols(["To Do", "to do"]); // stored mapping lower-case
  const { added, promoted } = mergeJiraColumns(columns, ["TO DO"]);
  assert.deepEqual(added, []);
  assert.deepEqual(promoted, []);
  assert.equal(columns.length, 1, "nothing added or promoted for an existing mapping");
});

test("mergeJiraColumns: never removes board-only columns or instructions", () => {
  const columns = cols(
    ["Refine", null, "Refine the spec"],
    ["To Do", "To Do"],
    ["Review", null, "Review the impl"],
  );
  const snapshot = columns.map((c) => ({ ...c }));
  mergeJiraColumns(columns, ["To Do", "In Progress"]);
  // Refine + Review + their instructions survive; only In Progress was added.
  const refine = columns.find((c) => c.name === "Refine");
  const review = columns.find((c) => c.name === "Review");
  assert.equal(refine.instructions, "Refine the spec");
  assert.equal(refine.jiraStatus, null, "Refine stays board-only");
  assert.equal(review.instructions, "Review the impl");
  assert.equal(review.jiraStatus, null, "Review stays board-only");
  // snapshot Refine/Review unchanged except ordering around the insertion.
  assert.equal(columns.filter((c) => c.jiraStatus === null).length, 2, "both board-only columns kept");
});

test("mergeJiraColumns: handles multiple statuses in one pass (mix of add + promote + no-op)", () => {
  const columns = cols(
    ["To Do", "To Do"],
    ["In Review", null, "instr"],
    ["Done", "Done"],
  );
  const { added, promoted } = mergeJiraColumns(columns, ["To Do", "In Review", "Blocked", "Done"]);
  assert.deepEqual(added, ["Blocked"]);
  assert.deepEqual(promoted, ["In Review"]);
  assert.equal(columns.find((c) => c.name === "In Review").jiraStatus, "In Review");
  assert.ok(columns.find((c) => c.name === "Blocked"));
});

test("mergeJiraColumns: ignores blank/null statuses", () => {
  const columns = cols(["To Do", "To Do"]);
  const { added, promoted } = mergeJiraColumns(columns, ["", null, "To Do"]);
  assert.deepEqual(added, []);
  assert.deepEqual(promoted, []);
  assert.equal(columns.length, 1);
});

// ── fetchJiraColumns gating (no network when Jira is off) ────────────────────

function setCreds() {
  board.config.baseUrl = "https://acme.atlassian.net";
  board.config.email = "bot@acme.com";
  board.config.apiToken = "secret-token";
  board.config.projectKey = "PROJ";
}

test("fetchJiraColumns: no-op (no network) when Jira is disabled", async () => {
  setCreds();
  board.config.jiraEnabled = false;
  board.columns = DEFAULT_COLUMNS;
  const before = board.columns.map((c) => ({ ...c }));
  const r = await fetchJiraColumns();
  assert.equal(r.ok, false);
  assert.equal(r.reason, "not-configured");
  assert.deepEqual(board.columns.map((c) => ({ ...c })), before, "columns untouched — no merge ran");
  assert.equal(jiraCfg(), null, "disabled ⇒ jiraCfg null ⇒ fetchJiraColumns returned before any fetch");
});

test("fetchJiraColumns: no-op (no network) when enabled but unconfigured (no creds)", async () => {
  board.config.jiraEnabled = true;
  board.config.baseUrl = "";
  board.config.email = "";
  board.config.apiToken = "";
  board.columns = DEFAULT_COLUMNS;
  const before = board.columns.map((c) => ({ ...c }));
  const r = await fetchJiraColumns();
  assert.equal(r.ok, false);
  assert.equal(r.reason, "not-configured");
  assert.deepEqual(board.columns.map((c) => ({ ...c })), before, "columns untouched");
});

test("fetchJiraColumns: no-op (no network) when configured but no project key set", async () => {
  setCreds();
  board.config.projectKey = "";
  board.columns = DEFAULT_COLUMNS;
  const before = board.columns.map((c) => ({ ...c }));
  const r = await fetchJiraColumns();
  assert.equal(r.ok, false);
  assert.equal(r.reason, "no-project-key");
  assert.deepEqual(board.columns.map((c) => ({ ...c })), before, "columns untouched — returned before any fetch");
});
