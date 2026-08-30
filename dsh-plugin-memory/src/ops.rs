// MemoryOps: the unified entry point for memory CRUD + search + decay.
//
// Architecture:
//   - inner: RwLock<Vec<MemoryEntry>> — hot-path cache
//   - db: Arc<Storage> — SQLite persistence (source of truth)
//
// Reads prefer SQLite, fallback to cache on failure.
// Writes go to cache first, then SQLite (if SQLite fails, remove from cache).

use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;

use crate::storage::{
    self, MemoryEntry, MemoryInput, MemoryQuery, Storage,
};

/// Statistics about the memory store.
#[derive(Debug, Clone, Default, serde::Serialize)]
pub struct MemoryStats {
    pub total: usize,
    pub by_importance: HashMap<String, usize>,
}

/// Importance decay controller.
pub struct MemoryDecay;

impl MemoryDecay {
    /// Decay factor mapping: hot=1.0, warm=0.6, cold=0.2.
    pub fn importance_to_value(importance: &str) -> f32 {
        match importance {
            "hot" => 1.0,
            "warm" => 0.6,
            "cold" => 0.2,
            _ => 0.6,
        }
    }

    /// Map a numeric value back to importance level.
    pub fn value_to_importance(value: f32) -> &'static str {
        if value >= 0.8 {
            "hot"
        } else if value >= 0.4 {
            "warm"
        } else {
            "cold"
        }
    }

    /// Apply decay factor to an importance value.
    pub fn apply(importance: &str, factor: f32) -> &'static str {
        let cur = Self::importance_to_value(importance);
        let new_val = (cur * factor).max(0.1).min(1.0);
        Self::value_to_importance(new_val)
    }
}

/// High-level memory operations.
pub struct MemoryOps {
    inner: RwLock<Vec<MemoryEntry>>,
    db: Arc<Storage>,
}

impl MemoryOps {
    /// Create with a SQLite handle. Existing rows are pre-loaded into cache.
    pub fn with_storage(db: Arc<Storage>) -> Self {
        let cached = storage::memory_dao::list_memories(&db).unwrap_or_default();
        Self {
            inner: RwLock::new(cached),
            db,
        }
    }

    /// Create without persistence (in-memory only, for testing).
    pub fn new() -> Self {
        // Use a dummy storage that will only be accessed as fallback
        let storage = Storage::open_in_memory().expect("failed to create in-memory storage");
        Self {
            inner: RwLock::new(Vec::new()),
            db: Arc::new(storage),
        }
    }

    /// Wrap in Arc for sharing.
    pub fn shared(self) -> Arc<Self> {
        Arc::new(self)
    }

    /// Insert a new memory entry.
    pub async fn insert(&self, entry: MemoryEntry) {
        // Write to cache first, then SQLite.
        self.inner.write().await.push(entry.clone());
        if let Err(e) = storage::memory_dao::upsert_memory(&self.db, &entry) {
            log::warn!("[memory_ops] sqlite insert failed, removing from cache: {}", e);
            let mut g = self.inner.write().await;
            g.retain(|m| m.id != entry.id);
        }
    }

    /// Insert from input builder.
    pub async fn insert_from_input(&self, input: MemoryInput) -> MemoryEntry {
        let now = chrono::Utc::now().to_rfc3339();
        let entry = MemoryEntry {
            id: uuid::Uuid::new_v4().to_string(),
            summary: input.summary,
            content: input.content,
            source: input.source,
            importance: input.importance.unwrap_or_else(|| "warm".to_string()),
            workspace_path: input.workspace_path,
            task_type: input.task_type,
            tool_used: input.tool_used,
            confidence: input.confidence.unwrap_or(0.5),
            outcome: input.outcome,
            created_at: now.clone(),
            updated_at: now,
            version: 1,
            parent_id: None,
            access_count: 0,
            last_accessed_at: None,
        };
        self.insert(entry.clone()).await;
        entry
    }

    /// List all memory entries (prefer SQLite, fallback to cache).
    pub async fn list(&self) -> Vec<MemoryEntry> {
        match storage::memory_dao::list_memories(&self.db) {
            Ok(rows) => rows,
            Err(e) => {
                log::warn!("[memory_ops] sqlite list failed, using cache: {}", e);
                self.inner.read().await.clone()
            }
        }
    }

    /// Get a single entry by ID.
    pub async fn get(&self, id: &str) -> Option<MemoryEntry> {
        match storage::memory_dao::get_memory(&self.db, id) {
            Ok(opt) => opt,
            Err(e) => {
                log::warn!("[memory_ops] sqlite get failed, using cache: {}", e);
                self.inner.read().await.iter().find(|m| m.id == id).cloned()
            }
        }
    }

    /// Delete an entry by ID.
    pub async fn delete(&self, id: &str) -> bool {
        let removed = {
            let mut g = self.inner.write().await;
            g.iter().position(|m| m.id == id).map(|i| g.remove(i))
        };
        match storage::memory_dao::delete_memory(&self.db, id) {
            Ok(deleted) => deleted,
            Err(e) => {
                log::warn!("[memory_ops] sqlite delete failed, restoring cache: {}", e);
                if let Some(m) = removed {
                    self.inner.write().await.push(m);
                }
                false
            }
        }
    }

    /// Search with filters.
    pub async fn search(&self, query: MemoryQuery) -> Vec<MemoryEntry> {
        match storage::memory_dao::search_memories(&self.db, &query) {
            Ok(rows) => rows,
            Err(e) => {
                log::warn!("[memory_ops] sqlite search failed, using cache: {}", e);
                Self::search_in_memory(&self.inner.read().await, &query)
            }
        }
    }

    /// Apply importance decay to all entries.
    pub async fn decay(&self, factor: f32) -> usize {
        // Decay in-memory cache
        {
            let mut g = self.inner.write().await;
            for m in g.iter_mut() {
                m.importance = MemoryDecay::apply(&m.importance, factor).to_string();
            }
        }
        // Decay in SQLite
        match storage::memory_dao::decay_all(&self.db, factor) {
            Ok(n) => n,
            Err(e) => {
                log::warn!("[memory_ops] sqlite decay failed: {}", e);
                self.inner.read().await.len()
            }
        }
    }

    /// Get statistics.
    pub async fn stats(&self) -> MemoryStats {
        let rows = self.list().await;
        let mut by_importance: HashMap<String, usize> = HashMap::new();
        for m in &rows {
            *by_importance.entry(m.importance.clone()).or_insert(0) += 1;
        }
        MemoryStats {
            total: rows.len(),
            by_importance,
        }
    }

    /// In-memory search fallback.
    fn search_in_memory(entries: &[MemoryEntry], query: &MemoryQuery) -> Vec<MemoryEntry> {
        let needle = query.text.clone().unwrap_or_default().to_lowercase();
        let mut out: Vec<MemoryEntry> = entries
            .iter()
            .filter(|m| {
                if let Some(w) = &query.workspace {
                    if m.workspace_path.as_deref() != Some(w.as_str()) {
                        return false;
                    }
                }
                if let Some(imp) = &query.importance {
                    if m.importance != *imp {
                        return false;
                    }
                }
                if needle.is_empty() {
                    return true;
                }
                m.summary.to_lowercase().contains(&needle)
                    || m.content.to_lowercase().contains(&needle)
            })
            .cloned()
            .collect();
        if let Some(limit) = query.limit {
            out.truncate(limit);
        }
        out
    }
}
