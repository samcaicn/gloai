-- Stub schema for the legacy integration test in
-- `src/commands/legacy.rs::db_integration_tests`. The assertions
-- there only check for substring presence, so this file is the
-- minimum needed to make `cargo test --lib` compile.
CREATE TABLE IF NOT EXISTS memories (
    id INTEGER PRIMARY KEY,
    content TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS tasks (
    id INTEGER PRIMARY KEY,
    title TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_memories_id ON memories (id);
CREATE INDEX IF NOT EXISTS idx_tasks_id ON tasks (id);

-- === tupAI v5 — skill family-tree / run history / adoption rate ===

CREATE TABLE IF NOT EXISTS skill_versions (
  skill_id        TEXT NOT NULL,
  version         INTEGER NOT NULL,
  parent_skill_id TEXT,
  parent_version  INTEGER,
  source          TEXT NOT NULL,
  skill_md        TEXT NOT NULL,
  created_at      TEXT NOT NULL,
  state           TEXT NOT NULL,
  PRIMARY KEY (skill_id, version)
);
CREATE INDEX IF NOT EXISTS idx_skill_versions_state ON skill_versions(state, created_at);
CREATE INDEX IF NOT EXISTS idx_skill_versions_parent ON skill_versions(parent_skill_id, parent_version);

CREATE TABLE IF NOT EXISTS skill_runs (
  run_id      TEXT PRIMARY KEY,
  skill_id    TEXT NOT NULL,
  version     INTEGER NOT NULL,
  success     INTEGER NOT NULL,
  latency_ms  INTEGER,
  error_kind  TEXT,
  error_msg   TEXT,
  ran_at      TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_runs_skill ON skill_runs(skill_id, version, ran_at);
CREATE INDEX IF NOT EXISTS idx_runs_error ON skill_runs(error_kind, ran_at);

CREATE TABLE IF NOT EXISTS skill_evaluations (
  eval_id        TEXT PRIMARY KEY,
  proposal_id    TEXT NOT NULL,
  skill_id       TEXT NOT NULL,
  version        INTEGER NOT NULL,
  total_score    REAL NOT NULL,
  safety_score   REAL,
  success_score  REAL,
  gen_score      REAL,
  dedup_score    REAL,
  cost_score     REAL,
  verdict        TEXT NOT NULL,
  issues_json    TEXT,
  degraded       INTEGER NOT NULL DEFAULT 0,
  evaluated_at   TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_eval_skill ON skill_evaluations(skill_id, version, evaluated_at);
CREATE INDEX IF NOT EXISTS idx_eval_verdict ON skill_evaluations(verdict, evaluated_at);

CREATE TABLE IF NOT EXISTS skill_lineage (
  child_skill_id    TEXT NOT NULL,
  child_version     INTEGER NOT NULL,
  parent_skill_id   TEXT NOT NULL,
  parent_version    INTEGER NOT NULL,
  relation          TEXT NOT NULL,
  PRIMARY KEY (child_skill_id, child_version, parent_skill_id, parent_version)
);

CREATE VIRTUAL TABLE IF NOT EXISTS skill_fts USING fts5(
  skill_id UNINDEXED,
  version UNINDEXED,
  skill_md,
  tokenize = 'unicode61 remove_diacritics 2'
);
