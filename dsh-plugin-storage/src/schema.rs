// Database schema DDL — adapted from safeopcapp.
//
// Covers: memory entries, skill versions, evolution stats,
// execution logs, autoskill drafts, and trajectory steps.

pub const DDL: &str = r#"
-- ============================================================
-- Memory entries (long-term memory with importance decay)
-- ============================================================
CREATE TABLE IF NOT EXISTS hermes_memories (
    id               TEXT PRIMARY KEY,
    summary          TEXT NOT NULL,
    content          TEXT NOT NULL,
    source           TEXT,
    created_at       TEXT NOT NULL,
    updated_at       TEXT NOT NULL,
    importance       TEXT NOT NULL DEFAULT 'warm',
    access_count     INTEGER NOT NULL DEFAULT 0,
    last_accessed_at TEXT,
    workspace_path   TEXT,
    version          INTEGER NOT NULL DEFAULT 1,
    parent_id        TEXT,
    task_type        TEXT,
    tool_used        TEXT,
    confidence       REAL NOT NULL DEFAULT 0.5,
    outcome          TEXT
);
CREATE INDEX IF NOT EXISTS idx_memories_workspace  ON hermes_memories(workspace_path);
CREATE INDEX IF NOT EXISTS idx_memories_importance ON hermes_memories(importance);
CREATE INDEX IF NOT EXISTS idx_memories_created_at ON hermes_memories(created_at DESC);

-- ============================================================
-- Skill version management (for autoskill upgrade/rollback)
-- ============================================================
CREATE TABLE IF NOT EXISTS skill_versions (
    scene        TEXT NOT NULL,
    skill_id     TEXT NOT NULL,
    version      TEXT NOT NULL,
    status       TEXT NOT NULL DEFAULT 'active',
    score        INTEGER,
    content      TEXT,
    changelog    TEXT,
    activated_at TEXT,
    PRIMARY KEY (scene, skill_id, version)
);
CREATE INDEX IF NOT EXISTS idx_skill_versions_status ON skill_versions(status);

-- ============================================================
-- AutoSkill drafts (generated upgrade candidates)
-- ============================================================
CREATE TABLE IF NOT EXISTS skill_auto_iter_draft (
    id                  TEXT PRIMARY KEY,
    scene               TEXT NOT NULL,
    skill_id            TEXT NOT NULL,
    draft_version       TEXT NOT NULL,
    source              TEXT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'drafting',
    content             TEXT,
    old_score           INTEGER,
    new_score           INTEGER,
    optimization_points TEXT,
    created_at          TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_draft_status ON skill_auto_iter_draft(status);
CREATE INDEX IF NOT EXISTS idx_draft_skill ON skill_auto_iter_draft(skill_id);

-- ============================================================
-- Execution logs (mined by AutoSkill LogMiner)
-- ============================================================
CREATE TABLE IF NOT EXISTS worker_task_log (
    id           TEXT PRIMARY KEY,
    scene        TEXT NOT NULL,
    skill_id     TEXT,
    status       TEXT NOT NULL,
    params       TEXT,
    duration_ms  INTEGER NOT NULL DEFAULT 0,
    result       TEXT,
    user_rating  INTEGER,
    created_at   TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_task_log_scene_skill ON worker_task_log(scene, skill_id);
CREATE INDEX IF NOT EXISTS idx_task_log_status ON worker_task_log(status);

-- ============================================================
-- Evolution stats (per-skill sliding window stats)
-- ============================================================
CREATE TABLE IF NOT EXISTS hermes_evolution_stats (
    skill_id              TEXT PRIMARY KEY,
    name                  TEXT NOT NULL,
    runs                  INTEGER NOT NULL DEFAULT 0,
    succeeded             INTEGER NOT NULL DEFAULT 0,
    failed                INTEGER NOT NULL DEFAULT 0,
    consecutive_failures  INTEGER NOT NULL DEFAULT 0,
    last_run_ms           INTEGER NOT NULL DEFAULT 0,
    last_success_ms       INTEGER NOT NULL DEFAULT 0,
    last_failure_ms       INTEGER NOT NULL DEFAULT 0,
    status                TEXT NOT NULL DEFAULT 'idle',
    circuit_open_until_ms INTEGER NOT NULL DEFAULT 0
);
"#;

/// Status constants for skill_auto_iter_draft.
pub mod draft_status {
    pub const DRAFTING: &str = "drafting";
    pub const PENDING_CONFIRM: &str = "pending_confirm";
    pub const WATCHING: &str = "watching";
    pub const RUNNING: &str = "running";
    pub const REJECTED: &str = "rejected";
    pub const ROLLBACK: &str = "rollback";
}

/// Status constants for skill_versions.
pub mod version_status {
    pub const ACTIVE: &str = "active";
    pub const WATCHING: &str = "watching";
    pub const ROLLBACK: &str = "rollback";
}
