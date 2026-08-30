// Memory entry CRUD operations — adapted from safeopcapp persistence layer.
//
// All operations go through the Storage handle, which provides
// a mutex-guarded rusqlite connection.

use rusqlite::{params, OptionalExtension};

use super::{MemoryEntry, MemoryQuery, Storage, StorageError};

// Re-export DDL so callers don't need to import schema separately
pub use crate::storage::schema::DDL as MEMORY_DDL;

/// Insert or update a memory entry.
pub fn upsert_memory(storage: &Storage, entry: &MemoryEntry) -> Result<(), StorageError> {
    let conn = storage.conn();
    conn.execute(
        "INSERT INTO hermes_memories
            (id, summary, content, source, created_at, updated_at,
             importance, access_count, last_accessed_at, workspace_path,
             version, parent_id, task_type, tool_used, confidence, outcome)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16)
         ON CONFLICT(id) DO UPDATE SET
            summary = excluded.summary,
            content = excluded.content,
            source = excluded.source,
            updated_at = excluded.updated_at,
            importance = excluded.importance,
            access_count = excluded.access_count,
            last_accessed_at = excluded.last_accessed_at,
            workspace_path = excluded.workspace_path,
            version = excluded.version,
            parent_id = excluded.parent_id,
            task_type = excluded.task_type,
            tool_used = excluded.tool_used,
            confidence = excluded.confidence,
            outcome = excluded.outcome",
        params![
            &entry.id,
            &entry.summary,
            &entry.content,
            &entry.source,
            &entry.created_at,
            &entry.updated_at,
            &entry.importance,
            entry.access_count,
            &entry.last_accessed_at,
            &entry.workspace_path,
            entry.version,
            &entry.parent_id,
            &entry.task_type,
            &entry.tool_used,
            entry.confidence,
            &entry.outcome,
        ],
    )?;
    Ok(())
}

/// Get a memory entry by ID.
pub fn get_memory(storage: &Storage, id: &str) -> Result<Option<MemoryEntry>, StorageError> {
    let conn = storage.conn();
    let mut stmt = conn.prepare(
        "SELECT id, summary, content, source, created_at, updated_at,
                importance, access_count, last_accessed_at, workspace_path,
                version, parent_id, task_type, tool_used, confidence, outcome
         FROM hermes_memories WHERE id = ?1",
    )?;
    let result = stmt
        .query_row(params![id], |row| {
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
                version: row.get(10)?,
                parent_id: row.get(11)?,
                task_type: row.get(12)?,
                tool_used: row.get(13)?,
                confidence: row.get(14)?,
                outcome: row.get(15)?,
            })
        })
        .optional()?;
    Ok(result)
}

/// List all memory entries (ordered by created_at DESC).
pub fn list_memories(storage: &Storage) -> Result<Vec<MemoryEntry>, StorageError> {
    let conn = storage.conn();
    let mut stmt = conn.prepare(
        "SELECT id, summary, content, source, created_at, updated_at,
                importance, access_count, last_accessed_at, workspace_path,
                version, parent_id, task_type, tool_used, confidence, outcome
         FROM hermes_memories ORDER BY created_at DESC",
    )?;
    let rows = stmt.query_map([], |row| {
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
            version: row.get(10)?,
            parent_id: row.get(11)?,
            task_type: row.get(12)?,
            tool_used: row.get(13)?,
            confidence: row.get(14)?,
            outcome: row.get(15)?,
        })
    })?;
    let mut entries = Vec::new();
    for row in rows {
        entries.push(row?);
    }
    Ok(entries)
}

/// Delete a memory entry by ID. Returns true if a row was deleted.
pub fn delete_memory(storage: &Storage, id: &str) -> Result<bool, StorageError> {
    let conn = storage.conn();
    let rows = conn.execute("DELETE FROM hermes_memories WHERE id = ?1", params![id])?;
    Ok(rows > 0)
}

/// Search memory entries with filters.
pub fn search_memories(
    storage: &Storage,
    query: &MemoryQuery,
) -> Result<Vec<MemoryEntry>, StorageError> {
    let conn = storage.conn();
    let needle = query.text.clone().unwrap_or_default();
    let limit = query.limit.unwrap_or(usize::MAX);
    let mut stmt = conn.prepare(
        r#"SELECT id, summary, content, source, created_at, updated_at,
                  importance, access_count, last_accessed_at, workspace_path,
                  version, parent_id, task_type, tool_used, confidence, outcome
           FROM hermes_memories
           WHERE (?1 IS NULL OR workspace_path = ?1)
             AND (?2 IS NULL OR importance = ?2)
             AND (?3 = '' OR LOWER(summary) LIKE '%' || ?3 || '%'
                        OR LOWER(content) LIKE '%' || ?3 || '%')
           ORDER BY created_at DESC
           LIMIT ?4"#,
    )?;
    let rows = stmt.query_map(
        params![
            query.workspace.as_deref(),
            query.importance.as_deref(),
            needle.to_lowercase(),
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
                version: row.get(10)?,
                parent_id: row.get(11)?,
                task_type: row.get(12)?,
                tool_used: row.get(13)?,
                confidence: row.get(14)?,
                outcome: row.get(15)?,
            })
        },
    )?;
    let mut entries = Vec::new();
    for row in rows {
        entries.push(row?);
    }
    Ok(entries)
}

/// Batch apply importance decay to all entries.
pub fn decay_all(storage: &Storage, factor: f32) -> Result<usize, StorageError> {
    let imp_to_val = |s: &str| -> f32 {
        match s {
            "hot" => 1.0,
            "warm" => 0.6,
            "cold" => 0.2,
            _ => 0.6,
        }
    };
    let val_to_imp = |v: f32| -> &'static str {
        if v >= 0.8 {
            "hot"
        } else if v >= 0.4 {
            "warm"
        } else {
            "cold"
        }
    };

    let entries = list_memories(storage)?;
    let conn = storage.conn();
    let tx = conn.unchecked_transaction()?;
    for entry in &entries {
        let cur = imp_to_val(&entry.importance);
        let new_val = (cur * factor).max(0.1).min(1.0);
        let new_importance = val_to_imp(new_val);
        tx.execute(
            "UPDATE hermes_memories SET importance = ?1 WHERE id = ?2",
            params![new_importance, &entry.id],
        )?;
    }
    tx.commit()?;
    Ok(entries.len())
}
