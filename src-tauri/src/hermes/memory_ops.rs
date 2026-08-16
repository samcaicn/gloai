
//
// High-level memory operations: create, update, search, embed, decay.
// The Rust port keeps an in-memory `Vec` as the hot path and, when a
// `HermesDb` is wired in, mirrors every write to the `hermes_memories`
// sqlite table. Reads prefer sqlite (the source of truth) and fall
// back to the in-memory cache when persistence is disabled.

use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;
use serde::{Deserialize, Serialize};

use crate::commands::types::MemoryEntry;
use crate::hermes::persistence::{self, HermesDb};

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct MemorySearchQuery {
    pub text: Option<String>,
    pub workspace: Option<String>,
    pub importance: Option<String>,
    pub limit: Option<usize>,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct MemoryOpsStats {
    pub total: usize,
    pub by_importance: HashMap<String, usize>,
}

pub struct MemoryOps {
    /// Hot-path cache. Kept in sync with sqlite on every write so
    /// callers that don't have a db handle still see consistent data.
    inner: RwLock<Vec<MemoryEntry>>,
    /// Optional sqlite persistence. When `Some`, every insert/delete
    /// is mirrored to `hermes_memories` and reads prefer sqlite.
    db: Option<Arc<HermesDb>>,
}

impl Default for MemoryOps {
    fn default() -> Self {
        Self { inner: RwLock::new(Vec::new()), db: None }
    }
}

impl MemoryOps {
    pub fn new() -> Self { Self::default() }
    pub fn shared() -> Arc<Self> { Arc::new(Self::default()) }

    /// Construct with a sqlite handle. Existing `hermes_memories`
    /// rows are pre-loaded into the in-memory cache so the hot path
    /// is warm from the first read.
    pub fn with_db(db: Arc<HermesDb>) -> Arc<Self> {
        let mut cached: Vec<MemoryEntry> = Vec::new();
        match persistence::list_memories(&db) {
            Ok(rows) => {
                cached = rows;
            }
            Err(e) => {
                log::warn!("[memory_ops] failed to pre-load hermes_memories: {}", e);
            }
        }
        Arc::new(Self {
            inner: RwLock::new(cached),
            db: Some(db),
        })
    }

    pub async fn insert(&self, entry: MemoryEntry) {
        // 先写缓存，再写 sqlite；sqlite 失败则从缓存移除（Bug 5）。
        // list/get/search 优先读 sqlite，若先写 sqlite 失败再写缓存，
        // 会导致缓存中的数据对读取不可见。
        if let Some(db) = &self.db {
            let entry_id = entry.id.clone();
            self.inner.write().await.push(entry.clone());
            if let Err(e) = persistence::upsert_memory(db, &entry) {
                log::warn!("[memory_ops] sqlite insert failed (removing from cache): {}", e);
                let mut g = self.inner.write().await;
                g.retain(|m| m.id != entry_id);
            }
        } else {
            self.inner.write().await.push(entry);
        }
    }

    pub async fn list(&self) -> Vec<MemoryEntry> {
        if let Some(db) = &self.db {
            match persistence::list_memories(db) {
                Ok(rows) => return rows,
                Err(e) => log::warn!("[memory_ops] sqlite list failed (using cache): {}", e),
            }
        }
        self.inner.read().await.clone()
    }

    pub async fn get(&self, id: &str) -> Option<MemoryEntry> {
        if let Some(db) = &self.db {
            match persistence::get_memory(db, id) {
                Ok(opt) => return opt,
                Err(e) => log::warn!("[memory_ops] sqlite get failed (using cache): {}", e),
            }
        }
        self.inner.read().await.iter().find(|m| m.id == id).cloned()
    }

    pub async fn delete(&self, id: &str) -> bool {
        // 先从缓存移除，再写 sqlite；sqlite 失败则恢复到缓存（Bug 5）。
        let removed_entry = {
            let mut g = self.inner.write().await;
            g.iter().position(|m| m.id == id).map(|i| g.remove(i))
        };
        if let Some(db) = &self.db {
            match persistence::delete_memory(db, id) {
                Ok(removed) => removed,
                Err(e) => {
                    log::warn!("[memory_ops] sqlite delete failed (restoring cache): {}", e);
                    if let Some(m) = removed_entry {
                        self.inner.write().await.push(m);
                    }
                    false
                }
            }
        } else {
            removed_entry.is_some()
        }
    }

    pub async fn search(&self, q: MemorySearchQuery) -> Vec<MemoryEntry> {
        if let Some(db) = &self.db {
            match Self::search_sqlite(db, &q) {
                Ok(rows) => return rows,
                Err(e) => log::warn!("[memory_ops] sqlite search failed (using cache): {}", e),
            }
        }
        Self::search_memory(&self.inner.read().await, &q)
    }

    pub async fn decay(&self, factor: f32) -> usize {
        // importance 是 "hot"/"warm"/"cold" 字符串分类，不是数值。
        // 旧实现直接 parse::<f32>() 必然失败，fallback 到 1.0 后会把
        // "warm" 污染成 "0.60" 之类的数字字符串，破坏后续 CASE 逻辑。
        // 修复：按字符串映射到数值，decay 后映射回最接近的档位。
        let imp_to_val = |s: &str| -> f32 {
            match s {
                "hot" => 1.0,
                "warm" => 0.6,
                "cold" => 0.2,
                _ => 0.6, // 未知值降级为 warm 中值
            }
        };
        let val_to_imp = |v: f32| -> &'static str {
            if v >= 0.8 { "hot" } else if v >= 0.4 { "warm" } else { "cold" }
        };
        let mut g = self.inner.write().await;
        for m in g.iter_mut() {
            let cur = imp_to_val(&m.importance);
            let new_val = (cur * factor).clamp(0.1, 1.0);
            m.importance = val_to_imp(new_val).to_string();
        }
        if let Some(db) = &self.db {
            for m in g.iter() {
                if let Err(e) = persistence::upsert_memory(db, m) {
                    log::warn!("[memory_ops] sqlite decay upsert failed: {}", e);
                }
            }
        }
        g.len()
    }

    pub async fn stats(&self) -> MemoryOpsStats {
        let rows = if let Some(db) = &self.db {
            match persistence::list_memories(db) {
                Ok(rows) => rows,
                Err(e) => {
                    log::warn!("[memory_ops] sqlite stats failed (using cache): {}", e);
                    self.inner.read().await.clone()
                }
            }
        } else {
            self.inner.read().await.clone()
        };
        let mut by_imp: HashMap<String, usize> = HashMap::new();
        for m in &rows { *by_imp.entry(m.importance.clone()).or_insert(0) += 1; }
        MemoryOpsStats { total: rows.len(), by_importance: by_imp }
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    /// In-memory filter used when no db is attached (or as a fallback).
    fn search_memory(g: &[MemoryEntry], q: &MemorySearchQuery) -> Vec<MemoryEntry> {
        let needle = q.text.clone().unwrap_or_default().to_lowercase();
        let mut out: Vec<MemoryEntry> = g.iter()
            .filter(|m| {
                if let Some(w) = &q.workspace { if m.workspace_path.as_deref() != Some(w.as_str()) { return false; } }
                if let Some(imp) = &q.importance { if m.importance != *imp { return false; } }
                if needle.is_empty() { return true; }
                m.summary.to_lowercase().contains(&needle) || m.content.to_lowercase().contains(&needle)
            })
            .cloned()
            .collect();
        if let Some(limit) = q.limit { out.truncate(limit); }
        out
    }

    /// Sqlite-backed search. Translates `MemorySearchQuery` into a
    /// parameterised `LIKE` query so we get case-insensitive substring
    /// matching equivalent to the in-memory path.
    fn search_sqlite(db: &HermesDb, q: &MemorySearchQuery) -> Result<Vec<MemoryEntry>, String> {
        let conn = db.conn();
        let needle = q.text.clone().unwrap_or_default().to_lowercase();
        let limit = q.limit.unwrap_or(usize::MAX);
        let mut stmt = conn
            .prepare(
                r#"SELECT id, summary, content, source, created_at, updated_at,
                          importance, access_count, last_accessed_at, workspace_path
                   FROM hermes_memories
                   WHERE (?1 IS NULL OR workspace_path = ?1)
                     AND (?2 IS NULL OR importance = ?2)
                     AND (?3 = '' OR LOWER(summary) LIKE '%' || ?3 || '%'
                                OR LOWER(content) LIKE '%' || ?3 || '%')
                   ORDER BY created_at DESC
                   LIMIT ?4"#,
            )
            .map_err(|e| format!("prepare search hermes_memories: {}", e))?;
        let rows = stmt
            .query_map(
                rusqlite::params![
                    q.workspace.as_deref(),
                    q.importance.as_deref(),
                    needle,
                    limit as i64,
                ],
                |row| {
                    Ok(MemoryEntry {
                        id: row.get(0)?,
                        summary: row.get(1)?,
                        content: row.get(2)?,
                        source: row.get(3)?,
                        created_at: row.get(4)?,
                        updated_at: row.get(5)?,
                        importance: row.get(6)?,
                        access_count: row.get(7)?,
                        last_accessed_at: row.get(8)?,
                        workspace_path: row.get(9)?,
                        version: 1,
                        parent_id: None,
                        parent_version: None,
                        task_type: None,
                        tool_used: None,
                        confidence: 0.0,
                        session_id: None,
                        channel_id: None,
                        outcome: None,
                    })
                },
            )
            .map_err(|e| format!("query search hermes_memories: {}", e))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| format!("read search hermes_memories: {}", e))?);
        }
        Ok(out)
    }
}
