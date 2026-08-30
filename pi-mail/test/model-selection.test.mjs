// Tests for the per-task model selection feature (task 46c60a81).
//
// Covers:
//   1. boardCreate with model field round-trip
//   2. boardUpdate changes/clears model
//   3. availableModels() returns the provider's models
//
// Run: npm test  (node:test runner)

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { board } from "../extensions/lib/board.mjs";
import { agents, HUMAN_AGENT_ID } from "../extensions/lib/core.mjs";
import { boardCreate, boardUpdate } from "../extensions/lib/board-create.mjs";
import { boardAssign } from "../extensions/lib/board-ops.mjs";
import { availableModels } from "../extensions/lib/models.mjs";

// ── fixtures ──────────────────────────────────────────────────────────────────

const TEST_AGENT = "aaaaaaaa-0000-0000-0000-000000000001";
const TEST_MODEL = "openrouter/openai/gpt-4o";
const UPDATED_MODEL = "openrouter/anthropic/claude-sonnet-4";

let savedTasks, savedCols;

before(() => {
  savedTasks = board.tasks;
  savedCols = board.columns;
  board.tasks = [];
  board.columns = [{ id: "todo", name: "To Do", jiraStatus: null, instructions: "" }];
  agents.set(TEST_AGENT, { info: { cwd: "/tmp/test", agentName: "test-agent" } });
});

after(() => {
  board.tasks = savedTasks;
  board.columns = savedCols;
  agents.delete(TEST_AGENT);
  // NOTE: any pending board-persist debounce fires after this restore and
  // writes the original board state back to disk — harmless.
});

// ── Tests ─────────────────────────────────────────────────────────────────────

test("boardCreate sets model on the task", async () => {
  const r = await boardCreate(TEST_AGENT, {
    summary: "Test model task",
    model: TEST_MODEL,
  });
  assert.ok(r.ok, "create succeeded");
  assert.ok(r.task, "task returned");
  assert.equal(r.task.model, TEST_MODEL, "model stored on task");
  assert.equal(r.task.summary, "Test model task", "summary preserved");
  // Clean up
  board.tasks = [];
});

test("boardCreate defaults model to null when omitted", async () => {
  const r = await boardCreate(TEST_AGENT, {
    summary: "No model task",
  });
  assert.ok(r.ok);
  assert.equal(r.task.model, null, "model is null when omitted");
  board.tasks = [];
});

test("boardUpdate changes model", async () => {
  const r1 = await boardCreate(TEST_AGENT, {
    summary: "Update model task",
    model: TEST_MODEL,
  });
  assert.ok(r1.ok);
  const taskId = r1.task.id;
  assert.equal(r1.task.model, TEST_MODEL);

  const r2 = await boardUpdate(TEST_AGENT, taskId, { model: UPDATED_MODEL });
  assert.ok(r2.ok);
  assert.equal(r2.task.model, UPDATED_MODEL, "model updated");

  board.tasks = [];
});

test("boardUpdate clears model with empty string", async () => {
  const r1 = await boardCreate(TEST_AGENT, {
    summary: "Clear model task",
    model: TEST_MODEL,
  });
  assert.ok(r1.ok);
  const taskId = r1.task.id;

  const r2 = await boardUpdate(TEST_AGENT, taskId, { model: "" });
  assert.ok(r2.ok);
  assert.equal(r2.task.model, null, "model cleared");

  board.tasks = [];
});

test("availableModels returns an array of model objects", async () => {
  const models = availableModels();
  assert.ok(Array.isArray(models), "availableModels returns an array");
  // It may be empty if no settings.json / models-store.json exist in the test
  // environment (the daemon has its own home). But if it's non-empty, each
  // entry should have the expected shape.
  for (const m of models) {
    assert.ok(typeof m.id === "string" && m.id.length > 0, `id is a non-empty string: ${m.id}`);
    assert.ok(typeof m.name === "string" && m.name.length > 0, `name is a non-empty string: ${m.name}`);
    assert.ok(typeof m.provider === "string" && m.provider.length > 0, `provider is a non-empty string: ${m.provider}`);
  }
});

test("boardAssign pushes set_model to a live worker for a task with a model", async () => {
  // Register a live worker whose socket captures outgoing messages.
  const WORKER = "cccccccc-0000-0000-0000-000000000003";
  const writes = [];
  const fakeConn = { destroyed: false, write: (data) => writes.push(data.toString()) };
  agents.set(WORKER, { conn: fakeConn, info: { cwd: "/tmp/test", agentName: "model-worker" } });
  try {
    const r = await boardCreate(HUMAN_AGENT_ID, { summary: "Dispatch model task", model: TEST_MODEL });
    assert.ok(r.ok);
    await boardAssign(HUMAN_AGENT_ID, r.task.id, "model-worker", false);
    // The push is sent via setImmediate; give it a tick to land.
    await new Promise((res) => setTimeout(res, 10));
    const setModelWrites = writes.filter((w) => w.includes('"set_model"'));
    assert.ok(setModelWrites.length >= 1, `set_model push sent: ${JSON.stringify(writes)}`);
    assert.ok(setModelWrites.some((w) => w.includes(TEST_MODEL)), "push carries the task's model");
  } finally {
    agents.delete(WORKER);
    board.tasks = [];
  }
});

test("boardAssign does not push set_model when the task has no model", async () => {
  const WORKER = "dddddddd-0000-0000-0000-000000000004";
  const writes = [];
  const fakeConn = { destroyed: false, write: (data) => writes.push(data.toString()) };
  agents.set(WORKER, { conn: fakeConn, info: { cwd: "/tmp/test", agentName: "default-worker" } });
  try {
    const r = await boardCreate(HUMAN_AGENT_ID, { summary: "No-model dispatch task" });
    assert.ok(r.ok);
    await boardAssign(HUMAN_AGENT_ID, r.task.id, "default-worker", false);
    await new Promise((res) => setTimeout(res, 10));
    assert.equal(writes.filter((w) => w.includes('"set_model"')).length, 0, "no set_model push when unset");
  } finally {
    agents.delete(WORKER);
    board.tasks = [];
  }
});