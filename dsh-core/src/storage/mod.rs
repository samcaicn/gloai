// SQLite persistence layer — adapted from safeopcapp HermesDb.
//
// Single source of truth for all structured data: memory entries,
// skill versions, evolution stats, execution logs, and autoskill drafts.

pub mod memory_dao;
pub mod schema;

use std::path::Path;
use std::sync::{Arc, Mutex};

use rusqlite::Connection;
use serde::{Deserialize, Serialize};

use crate::storage::schema::DDL;

/// Long-lived SQLite handle shared across all core sub-modules.
#[derive(Clone)]
pub struct Storage {
    conn: Arc<Mutex<Connection>>,
}

/// Errors that can occur during storage operations.
#[derive(Debug, thiserror::Error)]
pub enum StorageError {
    #[error("SQLite error: {0}")]
    Sqlite(#[from] rusqlite::Error),

    #[error("Mutex poisoned: {0}")]
    Poisoned(String),

    #[error("Serialization error: {0}")]
    Serde(#[from] serde_json::Error),

    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),

    #[error("Not found: {0}")]
    NotFound(String),
}

pub type Result<T> = std::result::Result<T, StorageError>;

impl Storage {
    /// Open (or create) the database at the given path and apply schema DDL.
    pub fn open<P: AsRef<Path>>(path: P) -> Result<Self> {
        let conn = Connection::open(path)?;
        conn.execute_batch(
            "PRAGMA journal_mode = WAL;
             PRAGMA foreign_keys = ON;
             PRAGMA busy_timeout = 5000;",
        )?;
        conn.execute_batch(DDL)?;
        Ok(Self {
            conn: Arc::new(Mutex::new(conn)),
        })
    }

    /// Open an in-memory database (useful for testing).
    pub fn open_in_memory() -> Result<Self> {
        let conn = Connection::open_in_memory()?;
        conn.execute_batch(DDL)?;
        Ok(Self {
            conn: Arc::new(Mutex::new(conn)),
        })
    }

    /// Acquire the inner connection.
    pub fn conn(&self) -> std::sync::MutexGuard<'_, Connection> {
        self.conn.lock().unwrap_or_else(|e| {
            log::error!("[storage] mutex poisoned, recovering");
            e.into_inner()
        })
    }

    /// Wrap in Arc for sharing across tasks.
    pub fn shared(self) -> Arc<Self> {
        Arc::new(self)
    }
}

/// A memory entry stored in the database.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct MemoryEntry {
    pub id: String,
    pub summary: String,
    pub content: String,
    pub source: Option<String>,
    pub importance: String, // "hot" | "warm" | "cold"
    pub workspace_path: Option<String>,
    pub access_count: i64,
    pub last_accessed_at: Option<String>,
    pub created_at: String,
    pub updated_at: String,
    pub version: i64,
    pub parent_id: Option<String>,
    pub task_type: Option<String>,
    pub tool_used: Option<String>,
    pub confidence: f32,
    pub outcome: Option<String>,
}

impl Default for MemoryEntry {
    fn default() -> Self {
        Self {
            id: String::new(),
            summary: String::new(),
            content: String::new(),
            source: None,
            importance: "warm".to_string(),
            workspace_path: None,
            access_count: 0,
            last_accessed_at: None,
            created_at: String::new(),
            updated_at: String::new(),
            version: 1,
            parent_id: None,
            task_type: None,
            tool_used: None,
            confidence: 0.5,
            outcome: None,
        }
    }
}

/// Input for creating/updating a memory entry.
#[derive(Debug, Clone, Default, Deserialize)]
pub struct MemoryInput {
    pub summary: String,
    pub content: String,
    pub source: Option<String>,
    pub importance: Option<String>,
    pub workspace_path: Option<String>,
    pub task_type: Option<String>,
    pub tool_used: Option<String>,
    pub confidence: Option<f32>,
    pub outcome: Option<String>,
}

/// Query parameters for searching memory entries.
#[derive(Debug, Clone, Default, Deserialize)]
pub struct MemoryQuery {
    pub text: Option<String>,
    pub workspace: Option<String>,
    pub importance: Option<String>,
    pub limit: Option<usize>,
}

/// Skill version record for the version management table.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SkillVersion {
    pub scene: String,
    pub skill_id: String,
    pub version: String,
    pub status: String, // "active" | "watching" | "rollback"
    pub score: Option<i32>,
    pub content: Option<String>,
    pub changelog: Option<String>,
    pub activated_at: Option<String>,
}

/// AutoSkill draft record.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DraftRecord {
    pub id: String,
    pub scene: String,
    pub skill_id: String,
    pub draft_version: String,
    pub source: String,
    pub status: String,
    pub content: Option<String>,
    pub old_score: Option<i32>,
    pub new_score: Option<i32>,
    pub optimization_points: Option<String>,
    pub created_at: String,
}

/// Execution log entry mined by AutoSkill.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExecutionLog {
    pub id: String,
    pub scene: String,
    pub skill_id: String,
    pub status: String, // "succeeded" | "failed"
    pub params: Option<String>,
    pub duration_ms: i64,
    pub result: Option<String>,
    pub user_rating: Option<i32>,
    pub created_at: String,
}
