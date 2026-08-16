// Copyright (c) 2026 MeeJoy

use crate::commands::types::{
    now_rfc3339, open_app_db, NotebookFolder, NotebookNote, NotebookNoteMeta, NotebookTree,
};
use rusqlite::{params, Connection};

fn ensure_notebook_schema(conn: &Connection) -> Result<(), String> {
    conn.execute_batch(
        r#"
        PRAGMA foreign_keys = ON;
        CREATE TABLE IF NOT EXISTS notebook_folders (
            id TEXT PRIMARY KEY,
            parent_id TEXT,
            name TEXT NOT NULL,
            sort_order INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            FOREIGN KEY (parent_id) REFERENCES notebook_folders(id) ON DELETE CASCADE
        );
        CREATE TABLE IF NOT EXISTS notebook_notes (
            id TEXT PRIMARY KEY,
            folder_id TEXT,
            title TEXT NOT NULL,
            content TEXT NOT NULL DEFAULT '',
            sort_order INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            FOREIGN KEY (folder_id) REFERENCES notebook_folders(id) ON DELETE SET NULL
        );
        CREATE INDEX IF NOT EXISTS idx_notebook_folders_parent ON notebook_folders(parent_id);
        CREATE INDEX IF NOT EXISTS idx_notebook_notes_folder ON notebook_notes(folder_id);
        CREATE INDEX IF NOT EXISTS idx_notebook_notes_updated ON notebook_notes(updated_at DESC);
        "#,
    )
    .map_err(|e| e.to_string())?;

    Ok(())
}

fn open_notebook_db(app: &tauri::AppHandle) -> Result<Connection, String> {
    let conn = open_app_db(app)?;
    ensure_notebook_schema(&conn)?;
    Ok(conn)
}

fn list_folder_ids(conn: &Connection, parent_id: Option<&str>) -> Result<Vec<String>, String> {
    let mut stmt = conn
        .prepare(
            r#"
            SELECT id
            FROM notebook_folders
            WHERE parent_id IS ?1
            ORDER BY sort_order ASC, updated_at DESC
            "#,
        )
        .map_err(|e| e.to_string())?;

    let rows = stmt
        .query_map(params![parent_id], |row| row.get(0))
        .map_err(|e| e.to_string())?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string())?;

    Ok(rows)
}

fn list_note_ids(conn: &Connection, folder_id: Option<&str>) -> Result<Vec<String>, String> {
    let mut stmt = conn
        .prepare(
            r#"
            SELECT id
            FROM notebook_notes
            WHERE folder_id IS ?1
            ORDER BY sort_order ASC, updated_at DESC
            "#,
        )
        .map_err(|e| e.to_string())?;

    let rows = stmt
        .query_map(params![folder_id], |row| row.get(0))
        .map_err(|e| e.to_string())?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string())?;

    Ok(rows)
}

fn reorder_ids(ids: &mut Vec<String>, moving_id: &str, target_id: Option<&str>, position: Option<&str>) {
    ids.retain(|id| id != moving_id);

    let insert_index = if let (Some(target_id), Some(position)) = (target_id, position) {
        if let Some(target_index) = ids.iter().position(|id| id == target_id) {
            if position == "after" {
                target_index + 1
            } else {
                target_index
            }
        } else {
            ids.len()
        }
    } else {
        ids.len()
    };

    ids.insert(insert_index.min(ids.len()), moving_id.to_string());
}

fn write_folder_order(
    conn: &Connection,
    parent_id: Option<&str>,
    ids: &[String],
) -> Result<(), String> {
    for (index, id) in ids.iter().enumerate() {
        conn.execute(
            "UPDATE notebook_folders SET parent_id = ?1, sort_order = ?2 WHERE id = ?3",
            params![parent_id, index as i64, id],
        )
        .map_err(|e| e.to_string())?;
    }
    Ok(())
}

fn write_note_order(
    conn: &Connection,
    folder_id: Option<&str>,
    ids: &[String],
) -> Result<(), String> {
    for (index, id) in ids.iter().enumerate() {
        conn.execute(
            "UPDATE notebook_notes SET folder_id = ?1, sort_order = ?2 WHERE id = ?3",
            params![folder_id, index as i64, id],
        )
        .map_err(|e| e.to_string())?;
    }
    Ok(())
}

#[tauri::command]
pub fn list_notebook_tree(app: tauri::AppHandle) -> Result<NotebookTree, String> {
    let conn = open_notebook_db(&app)?;

    let mut folder_stmt = conn
        .prepare(
            r#"
            SELECT id, parent_id, name, sort_order, created_at, updated_at
            FROM notebook_folders
            ORDER BY sort_order ASC, updated_at DESC
            "#,
        )
        .map_err(|e| e.to_string())?;

    let folders = folder_stmt
        .query_map([], |row| {
            Ok(NotebookFolder {
                id: row.get(0)?,
                parent_id: row.get(1)?,
                name: row.get(2)?,
                sort_order: row.get(3)?,
                created_at: row.get(4)?,
                updated_at: row.get(5)?,
            })
        })
        .map_err(|e| e.to_string())?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string())?;

    let mut note_stmt = conn
        .prepare(
            r#"
            SELECT id, folder_id, title, sort_order, created_at, updated_at
            FROM notebook_notes
            ORDER BY sort_order ASC, updated_at DESC
            "#,
        )
        .map_err(|e| e.to_string())?;

    let notes = note_stmt
        .query_map([], |row| {
            Ok(NotebookNoteMeta {
                id: row.get(0)?,
                folder_id: row.get(1)?,
                title: row.get(2)?,
                sort_order: row.get(3)?,
                created_at: row.get(4)?,
                updated_at: row.get(5)?,
            })
        })
        .map_err(|e| e.to_string())?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string())?;

    Ok(NotebookTree { folders, notes })
}

#[tauri::command]
pub fn create_notebook_folder(
    app: tauri::AppHandle,
    parent_id: Option<String>,
    name: String,
) -> Result<NotebookFolder, String> {
    let conn = open_notebook_db(&app)?;
    let now = now_rfc3339();
    let id = format!("nbf_{}", uuid::Uuid::new_v4());

    let sort_order: i64 = conn
        .query_row(
            "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM notebook_folders WHERE parent_id IS ?1",
            params![parent_id],
            |row| row.get(0),
        )
        .map_err(|e| e.to_string())?;

    conn.execute(
        r#"
        INSERT INTO notebook_folders (id, parent_id, name, sort_order, created_at, updated_at)
        VALUES (?1, ?2, ?3, ?4, ?5, ?5)
        "#,
        params![id, parent_id, name, sort_order, now],
    )
    .map_err(|e| e.to_string())?;

    Ok(NotebookFolder {
        id,
        parent_id,
        name,
        sort_order,
        created_at: now.clone(),
        updated_at: now,
    })
}

#[tauri::command]
pub fn rename_notebook_folder(
    app: tauri::AppHandle,
    folder_id: String,
    name: String,
) -> Result<(), String> {
    let conn = open_notebook_db(&app)?;
    conn.execute(
        "UPDATE notebook_folders SET name = ?1, updated_at = ?2 WHERE id = ?3",
        params![name, now_rfc3339(), folder_id],
    )
    .map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
pub fn delete_notebook_folder(app: tauri::AppHandle, folder_id: String) -> Result<(), String> {
    let conn = open_notebook_db(&app)?;

    let child_folder_count: i64 = conn
        .query_row(
            "SELECT COUNT(1) FROM notebook_folders WHERE parent_id = ?1",
            params![folder_id],
            |row| row.get(0),
        )
        .map_err(|e| e.to_string())?;

    let child_note_count: i64 = conn
        .query_row(
            "SELECT COUNT(1) FROM notebook_notes WHERE folder_id = ?1",
            params![folder_id],
            |row| row.get(0),
        )
        .map_err(|e| e.to_string())?;

    if child_folder_count > 0 || child_note_count > 0 {
        return Err("目录下存在目录或笔记，请清除".to_string());
    }

    conn.execute("DELETE FROM notebook_folders WHERE id = ?1", params![folder_id])
        .map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
pub fn create_notebook_note(
    app: tauri::AppHandle,
    folder_id: Option<String>,
    title: String,
) -> Result<NotebookNote, String> {
    let conn = open_notebook_db(&app)?;
    let now = now_rfc3339();
    let id = format!("nbn_{}", uuid::Uuid::new_v4());

    let sort_order: i64 = conn
        .query_row(
            "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM notebook_notes WHERE folder_id IS ?1",
            params![folder_id],
            |row| row.get(0),
        )
        .map_err(|e| e.to_string())?;

    conn.execute(
        r#"
        INSERT INTO notebook_notes (id, folder_id, title, content, sort_order, created_at, updated_at)
        VALUES (?1, ?2, ?3, '', ?4, ?5, ?5)
        "#,
        params![id, folder_id, title, sort_order, now],
    )
    .map_err(|e| e.to_string())?;

    Ok(NotebookNote {
        id,
        folder_id,
        title,
        content: String::new(),
        sort_order,
        created_at: now.clone(),
        updated_at: now,
    })
}

#[tauri::command]
pub fn rename_notebook_note(
    app: tauri::AppHandle,
    note_id: String,
    title: String,
) -> Result<(), String> {
    let conn = open_notebook_db(&app)?;
    conn.execute(
        "UPDATE notebook_notes SET title = ?1, updated_at = ?2 WHERE id = ?3",
        params![title, now_rfc3339(), note_id],
    )
    .map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
pub fn delete_notebook_note(app: tauri::AppHandle, note_id: String) -> Result<(), String> {
    let conn = open_notebook_db(&app)?;
    conn.execute("DELETE FROM notebook_notes WHERE id = ?1", params![note_id])
        .map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
pub fn get_notebook_note(app: tauri::AppHandle, note_id: String) -> Result<NotebookNote, String> {
    let conn = open_notebook_db(&app)?;
    conn.query_row(
        r#"
        SELECT id, folder_id, title, content, sort_order, created_at, updated_at
        FROM notebook_notes
        WHERE id = ?1
        "#,
        params![note_id],
        |row| {
            Ok(NotebookNote {
                id: row.get(0)?,
                folder_id: row.get(1)?,
                title: row.get(2)?,
                content: row.get(3)?,
                sort_order: row.get(4)?,
                created_at: row.get(5)?,
                updated_at: row.get(6)?,
            })
        },
    )
    .map_err(|e| e.to_string())
}

#[tauri::command]
pub fn update_notebook_note(
    app: tauri::AppHandle,
    note_id: String,
    title: String,
    content: String,
) -> Result<(), String> {
    let conn = open_notebook_db(&app)?;
    conn.execute(
        "UPDATE notebook_notes SET title = ?1, content = ?2, updated_at = ?3 WHERE id = ?4",
        params![title, content, now_rfc3339(), note_id],
    )
    .map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
pub fn search_notebook_notes(
    app: tauri::AppHandle,
    query: String,
) -> Result<Vec<NotebookNoteMeta>, String> {
    let conn = open_notebook_db(&app)?;
    let pattern = format!("%{}%", query.trim());
    let mut stmt = conn
        .prepare(
            r#"
            SELECT id, folder_id, title, sort_order, created_at, updated_at
            FROM notebook_notes
            WHERE title LIKE ?1 OR content LIKE ?1
            ORDER BY updated_at DESC
            "#,
        )
        .map_err(|e| e.to_string())?;

    let notes = stmt
        .query_map(params![pattern], |row| {
            Ok(NotebookNoteMeta {
                id: row.get(0)?,
                folder_id: row.get(1)?,
                title: row.get(2)?,
                sort_order: row.get(3)?,
                created_at: row.get(4)?,
                updated_at: row.get(5)?,
            })
        })
        .map_err(|e| e.to_string())?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string())?;

    Ok(notes)
}

#[tauri::command]
pub fn move_notebook_folder(
    app: tauri::AppHandle,
    folder_id: String,
    parent_id: Option<String>,
    target_folder_id: Option<String>,
    position: Option<String>,
) -> Result<(), String> {
    let conn = open_notebook_db(&app)?;

    if parent_id.as_deref() == Some(folder_id.as_str()) {
        return Err("目录不能移动到自己下面".to_string());
    }

    let mut current_parent = parent_id.clone();
    while let Some(current_id) = current_parent {
        if current_id == folder_id {
            return Err("目录不能移动到自己的下级目录".to_string());
        }

        current_parent = conn
            .query_row(
                "SELECT parent_id FROM notebook_folders WHERE id = ?1",
                params![current_id],
                |row| row.get(0),
            )
            .map_err(|e| e.to_string())?;
    }

    let source_parent: Option<String> = conn
        .query_row(
            "SELECT parent_id FROM notebook_folders WHERE id = ?1",
            params![folder_id],
            |row| row.get(0),
        )
        .map_err(|e| e.to_string())?;

    let source_parent_ref = source_parent.as_deref();
    let dest_parent_ref = parent_id.as_deref();

    let tx = conn.unchecked_transaction().map_err(|e| e.to_string())?;

    let mut source_ids = list_folder_ids(&tx, source_parent_ref)?;
    let mut dest_ids = if source_parent_ref == dest_parent_ref {
        source_ids.clone()
    } else {
        list_folder_ids(&tx, dest_parent_ref)?
    };

    reorder_ids(
        &mut dest_ids,
        &folder_id,
        target_folder_id.as_deref(),
        position.as_deref(),
    );

    tx.execute(
        "UPDATE notebook_folders SET parent_id = ?1, updated_at = ?2 WHERE id = ?3",
        params![parent_id, now_rfc3339(), folder_id],
    )
    .map_err(|e| e.to_string())?;

    if source_parent_ref != dest_parent_ref {
        source_ids.retain(|id| id != &folder_id);
        write_folder_order(&tx, source_parent_ref, &source_ids)?;
    }
    write_folder_order(&tx, dest_parent_ref, &dest_ids)?;
    tx.commit().map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
pub fn move_notebook_note(
    app: tauri::AppHandle,
    note_id: String,
    folder_id: Option<String>,
    target_note_id: Option<String>,
    position: Option<String>,
) -> Result<(), String> {
    let conn = open_notebook_db(&app)?;

    let source_folder: Option<String> = conn
        .query_row(
            "SELECT folder_id FROM notebook_notes WHERE id = ?1",
            params![note_id],
            |row| row.get(0),
        )
        .map_err(|e| e.to_string())?;

    let source_folder_ref = source_folder.as_deref();
    let dest_folder_ref = folder_id.as_deref();

    let tx = conn.unchecked_transaction().map_err(|e| e.to_string())?;

    let mut source_ids = list_note_ids(&tx, source_folder_ref)?;
    let mut dest_ids = if source_folder_ref == dest_folder_ref {
        source_ids.clone()
    } else {
        list_note_ids(&tx, dest_folder_ref)?
    };

    reorder_ids(
        &mut dest_ids,
        &note_id,
        target_note_id.as_deref(),
        position.as_deref(),
    );

    tx.execute(
        "UPDATE notebook_notes SET folder_id = ?1, updated_at = ?2 WHERE id = ?3",
        params![folder_id, now_rfc3339(), note_id],
    )
    .map_err(|e| e.to_string())?;

    if source_folder_ref != dest_folder_ref {
        source_ids.retain(|id| id != &note_id);
        write_note_order(&tx, source_folder_ref, &source_ids)?;
    }
    write_note_order(&tx, dest_folder_ref, &dest_ids)?;
    tx.commit().map_err(|e| e.to_string())?;
    Ok(())
}
