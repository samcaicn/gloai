use chrono::Local;
use rusqlite::{Connection, Result};
use serde::{Deserialize, Serialize};
use std::fmt;
use std::path::PathBuf;
use std::sync::RwLock;

// 自定义数据库错误类型
#[derive(Debug)]
pub enum DatabaseError {
    RusqliteError(rusqlite::Error),
    LockError(String),
    SerializationError(serde_json::Error),
    Other(String),
}

impl fmt::Display for DatabaseError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            DatabaseError::RusqliteError(e) => write!(f, "SQLite error: {}", e),
            DatabaseError::LockError(e) => write!(f, "Lock error: {}", e),
            DatabaseError::SerializationError(e) => write!(f, "Serialization error: {}", e),
            DatabaseError::Other(e) => write!(f, "Database error: {}", e),
        }
    }
}

impl From<rusqlite::Error> for DatabaseError {
    fn from(e: rusqlite::Error) -> Self {
        DatabaseError::RusqliteError(e)
    }
}

impl From<serde_json::Error> for DatabaseError {
    fn from(e: serde_json::Error) -> Self {
        DatabaseError::SerializationError(e)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DatabaseConfig {
    pub path: PathBuf,
}

pub struct Database {
    conn: RwLock<Connection>,
}

unsafe impl Send for Database {}
unsafe impl Sync for Database {}

impl Database {
    pub fn new(path: PathBuf) -> Result<Self> {
        let conn = Connection::open(path)?;
        let db = Self {
            conn: RwLock::new(conn),
        };
        db.initialize()?;
        Ok(db)
    }

    pub fn clone(&self) -> Self {
        panic!("Database cannot be cloned directly, use Arc<Mutex<Database>> instead");
    }

    fn initialize(&self) -> Result<()> {
        println!("[Database] Initializing database...");

        let conn = self.conn.write().unwrap();

        // 创建 KV 表
        println!("[Database] Creating kv table...");
        conn.execute(
            "CREATE TABLE IF NOT EXISTS kv (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                key TEXT UNIQUE NOT NULL,
                value TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )",
            [],
        )
        .map_err(|e| {
            println!("[Database] Error creating kv table: {}", e);
            e
        })?;

        // 创建会话表
        println!("[Database] Creating cowork_sessions table...");
        conn.execute(
            "CREATE TABLE IF NOT EXISTS cowork_sessions (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                status TEXT NOT NULL,
                pinned BOOLEAN DEFAULT 0,
                cwd TEXT,
                system_prompt TEXT,
                execution_mode TEXT,
                active_skill_ids TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )",
            [],
        )
        .map_err(|e| {
            println!("[Database] Error creating cowork_sessions table: {}", e);
            e
        })?;

        // 创建消息表
        println!("[Database] Creating cowork_messages table...");
        conn.execute(
            "CREATE TABLE IF NOT EXISTS cowork_messages (
                id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                type TEXT NOT NULL,
                content TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                metadata TEXT,
                sequence INTEGER,
                FOREIGN KEY (session_id) REFERENCES cowork_sessions(id) ON DELETE CASCADE
            )",
            [],
        )
        .map_err(|e| {
            println!("[Database] Error creating cowork_messages table: {}", e);
            e
        })?;

        // 创建配置表
        println!("[Database] Creating cowork_config table...");
        conn.execute(
            "CREATE TABLE IF NOT EXISTS cowork_config (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                key TEXT UNIQUE NOT NULL,
                value TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )",
            [],
        )
        .map_err(|e| {
            println!("[Database] Error creating cowork_config table: {}", e);
            e
        })?;

        // 创建用户记忆表
        println!("[Database] Creating user_memories table...");
        conn.execute(
            "CREATE TABLE IF NOT EXISTS user_memories (
                id TEXT PRIMARY KEY,
                text TEXT NOT NULL,
                confidence REAL NOT NULL,
                is_explicit BOOLEAN DEFAULT 0,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                last_used_at INTEGER
            )",
            [],
        )
        .map_err(|e| {
            println!("[Database] Error creating user_memories table: {}", e);
            e
        })?;

        // 创建记忆来源表
        println!("[Database] Creating user_memory_sources table...");
        conn.execute(
            "CREATE TABLE IF NOT EXISTS user_memory_sources (
                id TEXT PRIMARY KEY,
                memory_id TEXT NOT NULL,
                session_id TEXT,
                message_id TEXT,
                role TEXT NOT NULL,
                is_active BOOLEAN DEFAULT 1,
                created_at INTEGER NOT NULL,
                FOREIGN KEY (memory_id) REFERENCES user_memories(id) ON DELETE CASCADE
            )",
            [],
        )
        .map_err(|e| {
            println!("[Database] Error creating user_memory_sources table: {}", e);
            e
        })?;

        // 创建定时任务表
        println!("[Database] Creating scheduled_tasks table...");
        conn.execute(
            "CREATE TABLE IF NOT EXISTS scheduled_tasks (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT,
                cron_expression TEXT NOT NULL,
                enabled BOOLEAN DEFAULT 1,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )",
            [],
        )
        .map_err(|e| {
            println!("[Database] Error creating scheduled_tasks table: {}", e);
            e
        })?;

        // 创建任务运行历史表
        println!("[Database] Creating task_runs table...");
        conn.execute(
            "CREATE TABLE IF NOT EXISTS task_runs (
                id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL,
                status TEXT NOT NULL,
                start_time INTEGER NOT NULL,
                end_time INTEGER,
                output TEXT,
                error TEXT,
                FOREIGN KEY (task_id) REFERENCES scheduled_tasks(id) ON DELETE CASCADE
            )",
            [],
        )
        .map_err(|e| {
            println!("[Database] Error creating task_runs table: {}", e);
            e
        })?;

        // 创建IM配置表
        println!("[Database] Creating im_config table...");
        conn.execute(
            "CREATE TABLE IF NOT EXISTS im_config (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                platform TEXT NOT NULL,
                config TEXT NOT NULL,
                enabled BOOLEAN DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )",
            [],
        )
        .map_err(|e| {
            println!("[Database] Error creating im_config table: {}", e);
            e
        })?;

        // 创建IM消息表
        println!("[Database] Creating im_messages table...");
        conn.execute(
            "CREATE TABLE IF NOT EXISTS im_messages (
                id TEXT PRIMARY KEY,
                platform TEXT NOT NULL,
                channel_id TEXT NOT NULL,
                user_id TEXT NOT NULL,
                user_name TEXT NOT NULL,
                content TEXT NOT NULL,
                is_mention BOOLEAN DEFAULT 0,
                direction TEXT NOT NULL, -- inbound or outbound
                status TEXT NOT NULL, -- pending, sent, failed, received
                timestamp INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )",
            [],
        )
        .map_err(|e| {
            println!("[Database] Error creating im_messages table: {}", e);
            e
        })?;

        println!("[Database] Database initialization completed successfully");
        Ok(())
    }

    // KV 操作
    pub fn kv_get(&self, key: &str) -> Result<Option<String>> {
        println!("[Database] KV get: {}", key);
        let conn = self.conn.read().unwrap();
        let mut stmt = conn
            .prepare("SELECT value FROM kv WHERE key = ?")
            .map_err(|e| {
                println!("[Database] Error preparing KV get statement: {}", e);
                e
            })?;
        let value = stmt.query_row([key], |row| row.get(0)).ok();
        println!("[Database] KV get result: {:?}", value);
        Ok(value)
    }

    pub fn kv_set(&self, key: &str, value: &str) -> Result<()> {
        println!("[Database] KV set: {}, value length: {}", key, value.len());
        let conn = self.conn.write().unwrap();
        let now = Local::now().timestamp_millis();
        let count = conn
            .execute(
                "UPDATE kv SET value = ?, updated_at = ? WHERE key = ?",
                [value, &now.to_string(), key],
            )
            .map_err(|e| {
                println!("[Database] Error updating KV: {}", e);
                e
            })?;

        if count == 0 {
            conn.execute(
                "INSERT INTO kv (key, value, created_at, updated_at) VALUES (?, ?, ?, ?)",
                [key, value, &now.to_string(), &now.to_string()],
            )
            .map_err(|e| {
                println!("[Database] Error inserting KV: {}", e);
                e
            })?;
            println!("[Database] KV inserted: {}", key);
        } else {
            println!("[Database] KV updated: {}", key);
        }
        Ok(())
    }

    pub fn kv_remove(&self, key: &str) -> Result<()> {
        println!("[Database] KV remove: {}", key);
        let conn = self.conn.write().unwrap();
        let count = conn
            .execute("DELETE FROM kv WHERE key = ?", [key])
            .map_err(|e| {
                println!("[Database] Error removing KV: {}", e);
                e
            })?;
        println!("[Database] KV removed: {}, rows affected: {}", key, count);
        Ok(())
    }

    // 会话操作
    pub fn cowork_list_sessions(&self) -> Result<Vec<serde_json::Value>> {
        println!("[Database] Listing cowork sessions...");
        let conn = self.conn.read().unwrap();
        let mut stmt = conn.prepare(
            "SELECT id, title, status, pinned, cwd, system_prompt, execution_mode, active_skill_ids, created_at, updated_at 
             FROM cowork_sessions 
             ORDER BY pinned DESC, updated_at DESC"
        ).map_err(|e| {
            println!("[Database] Error preparing cowork_list_sessions statement: {}", e);
            e
        })?;
        let rows = stmt
            .query_map([], |row| {
                Ok(serde_json::json! ({
                    "id": row.get::<_, String>(0)?,
                    "title": row.get::<_, String>(1)?,
                    "status": row.get::<_, String>(2)?,
                    "pinned": row.get::<_, bool>(3)?,
                    "cwd": row.get::<_, Option<String>>(4)?,
                    "system_prompt": row.get::<_, Option<String>>(5)?,
                    "execution_mode": row.get::<_, Option<String>>(6)?,
                    "active_skill_ids": row.get::<_, Option<String>>(7)?,
                    "created_at": row.get::<_, i64>(8)?,
                    "updated_at": row.get::<_, i64>(9)?,
                }))
            })
            .map_err(|e| {
                println!("[Database] Error querying cowork sessions: {}", e);
                e
            })?;

        let mut sessions = Vec::new();
        for row in rows {
            sessions.push(row?);
        }
        println!("[Database] Found {} cowork sessions", sessions.len());
        Ok(sessions)
    }

    pub fn cowork_create_session(
        &self,
        id: &str,
        title: &str,
        cwd: Option<&str>,
        system_prompt: Option<&str>,
        execution_mode: Option<&str>,
    ) -> Result<()> {
        println!(
            "[Database] Creating cowork session: {}, title: {}",
            id, title
        );
        let conn = self.conn.write().unwrap();
        let now = Local::now().timestamp_millis();
        conn.execute(
            "INSERT INTO cowork_sessions (id, title, status, cwd, system_prompt, execution_mode, created_at, updated_at) 
             VALUES (?, ?, 'idle', ?, ?, ?, ?, ?)",
            [
                id, 
                title, 
                cwd.unwrap_or(""), 
                system_prompt.unwrap_or(""), 
                execution_mode.unwrap_or("local"),
                &now.to_string(), 
                &now.to_string()
            ],
        ).map_err(|e| {
            println!("[Database] Error creating cowork session: {}", e);
            e
        })?;
        println!("[Database] Cowork session created successfully: {}", id);
        Ok(())
    }

    pub fn cowork_delete_session(&self, id: &str) -> Result<()> {
        println!("[Database] Deleting cowork session: {}", id);
        let conn = self.conn.write().unwrap();
        let count = conn
            .execute("DELETE FROM cowork_sessions WHERE id = ?", [id])
            .map_err(|e| {
                println!("[Database] Error deleting cowork session: {}", e);
                e
            })?;
        println!(
            "[Database] Cowork session deleted: {}, rows affected: {}",
            id, count
        );
        Ok(())
    }

    pub fn cowork_update_session(
        &self,
        id: &str,
        title: Option<&str>,
        pinned: Option<bool>,
        status: Option<&str>,
        cwd: Option<&str>,
        system_prompt: Option<&str>,
        execution_mode: Option<&str>,
    ) -> Result<()> {
        println!("[Database] Updating cowork session: {}", id);
        let conn = self.conn.write().unwrap();
        let now = Local::now().timestamp_millis();
        let mut set_clauses = Vec::new();
        let mut params = Vec::new();

        if let Some(title) = title {
            set_clauses.push("title = ?");
            params.push(title.to_string());
        }
        if let Some(pinned) = pinned {
            set_clauses.push("pinned = ?");
            params.push(if pinned { "1" } else { "0" }.to_string());
        }
        if let Some(status) = status {
            set_clauses.push("status = ?");
            params.push(status.to_string());
        }
        if let Some(cwd) = cwd {
            set_clauses.push("cwd = ?");
            params.push(cwd.to_string());
        }
        if let Some(system_prompt) = system_prompt {
            set_clauses.push("system_prompt = ?");
            params.push(system_prompt.to_string());
        }
        if let Some(execution_mode) = execution_mode {
            set_clauses.push("execution_mode = ?");
            params.push(execution_mode.to_string());
        }

        if set_clauses.is_empty() {
            println!("[Database] No updates needed for session: {}", id);
            return Ok(());
        }

        set_clauses.push("updated_at = ?");
        params.push(now.to_string());
        params.push(id.to_string());

        let sql = format!(
            "UPDATE cowork_sessions SET {} WHERE id = ?",
            set_clauses.join(", ")
        );
        conn.execute(&sql, rusqlite::params_from_iter(params))
            .map_err(|e| {
                println!("[Database] Error updating cowork session: {}", e);
                e
            })?;

        println!("[Database] Cowork session updated successfully: {}", id);
        Ok(())
    }

    // 消息操作
    pub fn cowork_list_messages(&self, session_id: &str) -> Result<Vec<serde_json::Value>> {
        println!("[Database] Listing messages for session: {}", session_id);
        let conn = self.conn.read().unwrap();
        let mut stmt = conn
            .prepare(
                "SELECT id, type, content, timestamp, metadata 
             FROM cowork_messages 
             WHERE session_id = ? 
             ORDER BY sequence ASC, timestamp ASC",
            )
            .map_err(|e| {
                println!(
                    "[Database] Error preparing cowork_list_messages statement: {}",
                    e
                );
                e
            })?;
        let rows = stmt
            .query_map([session_id], |row| {
                Ok(serde_json::json! ({
                    "id": row.get::<_, String>(0)?,
                    "type": row.get::<_, String>(1)?,
                    "content": row.get::<_, String>(2)?,
                    "timestamp": row.get::<_, i64>(3)?,
                    "metadata": row.get::<_, Option<String>>(4)?,
                }))
            })
            .map_err(|e| {
                println!("[Database] Error querying cowork messages: {}", e);
                e
            })?;

        let mut messages = Vec::new();
        for row in rows {
            messages.push(row?);
        }
        println!(
            "[Database] Found {} messages for session: {}",
            messages.len(),
            session_id
        );
        Ok(messages)
    }

    pub fn cowork_add_message(
        &self,
        id: &str,
        session_id: &str,
        msg_type: &str,
        content: &str,
    ) -> Result<()> {
        println!(
            "[Database] Adding message to session: {}, type: {}, content length: {}",
            session_id,
            msg_type,
            content.len()
        );
        let conn = self.conn.write().unwrap();
        let now = Local::now().timestamp_millis();
        let sequence = conn
            .query_row(
                "SELECT COALESCE(MAX(sequence), 0) + 1 FROM cowork_messages WHERE session_id = ?",
                [session_id],
                |row| row.get::<_, i32>(0),
            )
            .map_err(|e| {
                println!("[Database] Error getting next message sequence: {}", e);
                e
            })?;

        println!(
            "[Database] Message sequence for session {}: {}",
            session_id, sequence
        );

        conn.execute(
            "INSERT INTO cowork_messages (id, session_id, type, content, timestamp, sequence) 
             VALUES (?, ?, ?, ?, ?, ?)",
            [
                id,
                session_id,
                msg_type,
                content,
                &now.to_string(),
                &sequence.to_string(),
            ],
        )
        .map_err(|e| {
            println!("[Database] Error adding cowork message: {}", e);
            e
        })?;

        // 更新会话的更新时间
        conn.execute(
            "UPDATE cowork_sessions SET updated_at = ? WHERE id = ?",
            [&now.to_string(), session_id],
        )
        .map_err(|e| {
            println!("[Database] Error updating session updated_at: {}", e);
            e
        })?;

        println!(
            "[Database] Message added successfully: {}, session: {}",
            id, session_id
        );
        Ok(())
    }

    pub fn cowork_update_message(
        &self,
        id: &str,
        session_id: &str,
        content: Option<&str>,
        metadata: Option<&str>,
    ) -> Result<()> {
        println!(
            "[Database] Updating message: {}, session: {}",
            id, session_id
        );
        let conn = self.conn.write().unwrap();
        let now = Local::now().timestamp_millis();
        let mut set_clauses = Vec::new();
        let mut params = Vec::new();

        if let Some(content) = content {
            set_clauses.push("content = ?");
            params.push(content.to_string());
        }
        if let Some(metadata) = metadata {
            set_clauses.push("metadata = ?");
            params.push(metadata.to_string());
        }

        if set_clauses.is_empty() {
            println!("[Database] No updates needed for message: {}", id);
            return Ok(());
        }

        params.push(id.to_string());
        params.push(session_id.to_string());

        let sql = format!(
            "UPDATE cowork_messages SET {} WHERE id = ? AND session_id = ?",
            set_clauses.join(", ")
        );
        conn.execute(&sql, rusqlite::params_from_iter(params))
            .map_err(|e| {
                println!("[Database] Error updating message: {}", e);
                e
            })?;

        // 更新会话的更新时间
        conn.execute(
            "UPDATE cowork_sessions SET updated_at = ? WHERE id = ?",
            [&now.to_string(), session_id],
        )
        .map_err(|e| {
            println!("[Database] Error updating session updated_at: {}", e);
            e
        })?;

        println!("[Database] Message updated successfully: {}", id);
        Ok(())
    }

    // Cowork Config 操作
    pub fn cowork_config_get(&self, key: &str) -> Result<Option<String>> {
        println!("[Database] Getting cowork config: {}", key);
        let conn = self.conn.read().unwrap();
        let mut stmt = conn.prepare("SELECT value FROM cowork_config WHERE key = ?")?;
        let value = stmt.query_row([key], |row| row.get(0)).ok();
        println!("[Database] Cowork config get result: {:?}", value);
        Ok(value)
    }

    pub fn cowork_config_set(&self, key: &str, value: &str) -> Result<()> {
        println!(
            "[Database] Setting cowork config: {}, value length: {}",
            key,
            value.len()
        );
        let conn = self.conn.write().unwrap();
        let now = Local::now().timestamp_millis();

        // 尝试更新现有配置
        let count = conn.execute(
            "UPDATE cowork_config SET value = ?, updated_at = ? WHERE key = ?",
            [value, &now.to_string(), key],
        )?;

        if count == 0 {
            // 插入新配置
            conn.execute(
                "INSERT INTO cowork_config (key, value, updated_at) VALUES (?, ?, ?)",
                [key, value, &now.to_string()],
            )?;
            println!("[Database] Cowork config inserted: {}", key);
        } else {
            println!("[Database] Cowork config updated: {}", key);
        }
        Ok(())
    }

    pub fn cowork_config_get_all(&self) -> Result<Vec<serde_json::Value>> {
        println!("[Database] Getting all cowork configs...");
        let conn = self.conn.read().unwrap();
        let mut stmt = conn.prepare("SELECT key, value FROM cowork_config")?;
        let rows = stmt.query_map([], |row| {
            Ok(serde_json::json!({
                "key": row.get::<_, String>(0)?,
                "value": row.get::<_, String>(1)?,
            }))
        })?;

        let mut configs = Vec::new();
        for row in rows {
            configs.push(row?);
        }
        println!("[Database] Found {} cowork configs", configs.len());
        Ok(configs)
    }

    // 用户记忆操作
    pub fn user_memories_list(&self) -> Result<Vec<serde_json::Value>> {
        println!("[Database] Listing user memories...");
        let conn = self.conn.read().unwrap();
        let mut stmt = conn.prepare(
            "SELECT id, text, confidence, is_explicit, status, created_at, updated_at, last_used_at 
             FROM user_memories 
             ORDER BY updated_at DESC"
        ).map_err(|e| {
            println!("[Database] Error preparing user_memories_list statement: {}", e);
            e
        })?;
        let rows = stmt
            .query_map([], |row| {
                Ok(serde_json::json! ({
                    "id": row.get::<_, String>(0)?,
                    "text": row.get::<_, String>(1)?,
                    "confidence": row.get::<_, f64>(2)?,
                    "is_explicit": row.get::<_, bool>(3)?,
                    "status": row.get::<_, String>(4)?,
                    "created_at": row.get::<_, i64>(5)?,
                    "updated_at": row.get::<_, i64>(6)?,
                    "last_used_at": row.get::<_, Option<i64>>(7)?,
                }))
            })
            .map_err(|e| {
                println!("[Database] Error querying user memories: {}", e);
                e
            })?;

        let mut memories = Vec::new();
        for row in rows {
            memories.push(row?);
        }
        println!("[Database] Found {} user memories", memories.len());
        Ok(memories)
    }

    pub fn user_memory_create(
        &self,
        id: &str,
        text: &str,
        confidence: f64,
        is_explicit: bool,
    ) -> Result<()> {
        println!(
            "[Database] Creating user memory: {}, confidence: {}, is_explicit: {}",
            id, confidence, is_explicit
        );
        let conn = self.conn.write().unwrap();
        let now = Local::now().timestamp_millis();
        let is_explicit_int = if is_explicit { 1 } else { 0 };
        conn.execute(
            "INSERT INTO user_memories (id, text, confidence, is_explicit, status, created_at, updated_at) 
             VALUES (?, ?, ?, ?, 'created', ?, ?)",
            [id, text, &confidence.to_string(), &is_explicit_int.to_string(), &now.to_string(), &now.to_string()],
        ).map_err(|e| {
            println!("[Database] Error creating user memory: {}", e);
            e
        })?;
        println!("[Database] User memory created successfully: {}", id);
        Ok(())
    }

    pub fn user_memory_update(
        &self,
        id: &str,
        text: Option<&str>,
        confidence: Option<f64>,
        status: Option<&str>,
        is_explicit: Option<bool>,
    ) -> Result<()> {
        println!("[Database] Updating user memory: {}", id);
        let conn = self.conn.write().unwrap();
        let now = Local::now().timestamp_millis();
        let mut set_clauses = Vec::new();
        let mut params = Vec::new();

        if let Some(text) = text {
            set_clauses.push("text = ?");
            params.push(text.to_string());
        }
        if let Some(confidence) = confidence {
            set_clauses.push("confidence = ?");
            params.push(confidence.to_string());
        }
        if let Some(status) = status {
            set_clauses.push("status = ?");
            params.push(status.to_string());
        }
        if let Some(is_explicit) = is_explicit {
            set_clauses.push("is_explicit = ?");
            params.push(if is_explicit { "1" } else { "0" }.to_string());
        }

        if set_clauses.is_empty() {
            println!("[Database] No updates needed for user memory: {}", id);
            return Ok(());
        }

        set_clauses.push("updated_at = ?");
        params.push(now.to_string());
        params.push(id.to_string());

        let sql = format!(
            "UPDATE user_memories SET {} WHERE id = ?",
            set_clauses.join(", ")
        );
        conn.execute(&sql, rusqlite::params_from_iter(params))
            .map_err(|e| {
                println!("[Database] Error updating user memory: {}", e);
                e
            })?;

        println!("[Database] User memory updated successfully: {}", id);
        Ok(())
    }

    pub fn user_memory_delete(&self, id: &str) -> Result<bool> {
        println!("[Database] Deleting user memory: {}", id);
        let conn = self.conn.write().unwrap();
        let now = Local::now().timestamp_millis();

        // 首先将状态标记为deleted而不是直接删除
        let count = conn.execute(
            "UPDATE user_memories SET status = 'deleted', updated_at = ? WHERE id = ?",
            [&now.to_string(), id],
        )?;

        println!(
            "[Database] User memory marked as deleted: {}, rows affected: {}",
            id, count
        );
        Ok(count > 0)
    }

    pub fn user_memory_get_stats(&self) -> Result<serde_json::Value> {
        println!("[Database] Getting user memory stats...");
        let conn = self.conn.read().unwrap();

        let mut stmt = conn.prepare(
            "SELECT status, is_explicit, COUNT(*) as count 
             FROM user_memories 
             GROUP BY status, is_explicit",
        )?;

        let mut total = 0;
        let mut created = 0;
        let mut stale = 0;
        let mut deleted = 0;
        let mut explicit = 0;
        let mut implicit = 0;

        let rows = stmt.query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, bool>(1)?,
                row.get::<_, i64>(2)?,
            ))
        })?;

        for row in rows {
            let (status, is_explicit, count) = row?;
            total += count;
            if is_explicit {
                explicit += count;
            } else {
                implicit += count;
            }
            match status.as_str() {
                "created" => created += count,
                "stale" => stale += count,
                "deleted" => deleted += count,
                _ => {}
            }
        }

        println!("[Database] User memory stats: total={}, created={}, stale={}, deleted={}, explicit={}, implicit={}", 
            total, created, stale, deleted, explicit, implicit);

        Ok(serde_json::json!({
            "total": total,
            "created": created,
            "stale": stale,
            "deleted": deleted,
            "explicit": explicit,
            "implicit": implicit,
        }))
    }

    // 定时任务操作
    pub fn scheduled_tasks_list(&self) -> Result<Vec<serde_json::Value>> {
        println!("[Database] Listing scheduled tasks...");
        let conn = self.conn.read().unwrap();
        let mut stmt = conn
            .prepare(
                "SELECT id, name, description, cron_expression, enabled, created_at, updated_at 
             FROM scheduled_tasks 
             ORDER BY created_at DESC",
            )
            .map_err(|e| {
                println!(
                    "[Database] Error preparing scheduled_tasks_list statement: {}",
                    e
                );
                e
            })?;
        let rows = stmt
            .query_map([], |row| {
                Ok(serde_json::json! ({
                    "id": row.get::<_, String>(0)?,
                    "name": row.get::<_, String>(1)?,
                    "description": row.get::<_, Option<String>>(2)?,
                    "cron_expression": row.get::<_, String>(3)?,
                    "enabled": row.get::<_, bool>(4)?,
                    "created_at": row.get::<_, i64>(5)?,
                    "updated_at": row.get::<_, i64>(6)?,
                }))
            })
            .map_err(|e| {
                println!("[Database] Error querying scheduled tasks: {}", e);
                e
            })?;

        let mut tasks = Vec::new();
        for row in rows {
            tasks.push(row?);
        }
        println!("[Database] Found {} scheduled tasks", tasks.len());
        Ok(tasks)
    }

    pub fn scheduled_task_create(&self, id: &str, name: &str, cron_expression: &str) -> Result<()> {
        println!(
            "[Database] Creating scheduled task: {}, name: {}, cron: {}",
            id, name, cron_expression
        );
        let conn = self.conn.write().unwrap();
        let now = Local::now().timestamp_millis();
        conn.execute(
            "INSERT INTO scheduled_tasks (id, name, cron_expression, enabled, created_at, updated_at) 
             VALUES (?, ?, ?, 1, ?, ?)",
            [id, name, cron_expression, &now.to_string(), &now.to_string()],
        ).map_err(|e| {
            println!("[Database] Error creating scheduled task: {}", e);
            e
        })?;
        println!("[Database] Scheduled task created successfully: {}", id);
        Ok(())
    }

    pub fn scheduled_task_delete(&self, id: &str) -> Result<()> {
        println!("[Database] Deleting scheduled task: {}", id);
        let conn = self.conn.write().unwrap();
        let count = conn
            .execute("DELETE FROM scheduled_tasks WHERE id = ?", [id])
            .map_err(|e| {
                println!("[Database] Error deleting scheduled task: {}", e);
                e
            })?;
        println!(
            "[Database] Scheduled task deleted: {}, rows affected: {}",
            id, count
        );
        Ok(())
    }

    pub fn scheduled_task_update_enabled(&self, id: &str, enabled: bool) -> Result<()> {
        println!(
            "[Database] Updating scheduled task enabled: {}, enabled: {}",
            id, enabled
        );
        let conn = self.conn.write().unwrap();
        let now = Local::now().timestamp_millis();
        let enabled_int = if enabled { 1 } else { 0 };
        conn.execute(
            "UPDATE scheduled_tasks SET enabled = ?, updated_at = ? WHERE id = ?",
            [&enabled_int.to_string(), &now.to_string(), id],
        )
        .map_err(|e| {
            println!("[Database] Error updating scheduled task enabled: {}", e);
            e
        })?;
        println!(
            "[Database] Scheduled task enabled updated successfully: {}",
            id
        );
        Ok(())
    }

    // 任务运行历史操作
    pub fn task_runs_list(&self, task_id: Option<&str>) -> Result<Vec<serde_json::Value>> {
        println!("[Database] Listing task runs for task: {:?}", task_id);
        let conn = self.conn.read().unwrap();

        let mut runs = Vec::new();

        if let Some(task_id) = task_id {
            let mut stmt = conn
                .prepare(
                    "SELECT id, task_id, status, start_time, end_time, output, error 
                 FROM task_runs 
                 WHERE task_id = ? 
                 ORDER BY start_time DESC",
                )
                .map_err(|e| {
                    println!("[Database] Error preparing task_runs_list statement: {}", e);
                    e
                })?;
            let rows = stmt
                .query_map([task_id], |row| {
                    Ok(serde_json::json! ({
                        "id": row.get::<_, String>(0)?,
                        "task_id": row.get::<_, String>(1)?,
                        "status": row.get::<_, String>(2)?,
                        "start_time": row.get::<_, i64>(3)?,
                        "end_time": row.get::<_, Option<i64>>(4)?,
                        "output": row.get::<_, Option<String>>(5)?,
                        "error": row.get::<_, Option<String>>(6)?,
                    }))
                })
                .map_err(|e| {
                    println!("[Database] Error querying task runs: {}", e);
                    e
                })?;
            for row in rows {
                runs.push(row?);
            }
        } else {
            let mut stmt = conn
                .prepare(
                    "SELECT id, task_id, status, start_time, end_time, output, error 
                 FROM task_runs 
                 ORDER BY start_time DESC",
                )
                .map_err(|e| {
                    println!("[Database] Error preparing task_runs_list statement: {}", e);
                    e
                })?;
            let rows = stmt
                .query_map([], |row| {
                    Ok(serde_json::json! ({
                        "id": row.get::<_, String>(0)?,
                        "task_id": row.get::<_, String>(1)?,
                        "status": row.get::<_, String>(2)?,
                        "start_time": row.get::<_, i64>(3)?,
                        "end_time": row.get::<_, Option<i64>>(4)?,
                        "output": row.get::<_, Option<String>>(5)?,
                        "error": row.get::<_, Option<String>>(6)?,
                    }))
                })
                .map_err(|e| {
                    println!("[Database] Error querying task runs: {}", e);
                    e
                })?;
            for row in rows {
                runs.push(row?);
            }
        }

        println!("[Database] Found {} task runs", runs.len());
        Ok(runs)
    }

    pub fn task_run_create(&self, id: &str, task_id: &str) -> Result<()> {
        println!("[Database] Creating task run: {}, task_id: {}", id, task_id);
        let conn = self.conn.write().unwrap();
        let now = Local::now().timestamp_millis();
        conn.execute(
            "INSERT INTO task_runs (id, task_id, status, start_time) 
             VALUES (?, ?, 'running', ?)",
            [id, task_id, &now.to_string()],
        )
        .map_err(|e| {
            println!("[Database] Error creating task run: {}", e);
            e
        })?;
        println!("[Database] Task run created successfully: {}", id);
        Ok(())
    }

    pub fn task_run_complete(
        &self,
        id: &str,
        status: &str,
        output: Option<&str>,
        error: Option<&str>,
    ) -> Result<()> {
        println!("[Database] Completing task run: {}, status: {}", id, status);
        let conn = self.conn.write().unwrap();
        let now = Local::now().timestamp_millis();
        conn.execute(
            "UPDATE task_runs SET status = ?, end_time = ?, output = ?, error = ? WHERE id = ?",
            [
                status,
                &now.to_string(),
                output.unwrap_or(""),
                error.unwrap_or(""),
                id,
            ],
        )
        .map_err(|e| {
            println!("[Database] Error completing task run: {}", e);
            e
        })?;
        println!("[Database] Task run completed successfully: {}", id);
        Ok(())
    }

    // IM 配置操作
    pub fn im_config_save(&self, platform: &str, config: &str, enabled: bool) -> Result<()> {
        println!("[Database] Saving IM config for platform: {}", platform);
        let conn = self.conn.write().unwrap();
        let now = Local::now().timestamp_millis();
        let enabled_int = if enabled { 1 } else { 0 };

        // 尝试更新现有配置
        let count = conn.execute(
            "UPDATE im_config SET config = ?, enabled = ?, updated_at = ? WHERE platform = ?",
            [config, &enabled_int.to_string(), &now.to_string(), platform],
        )?;

        if count == 0 {
            // 插入新配置
            conn.execute(
                "INSERT INTO im_config (platform, config, enabled, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                [platform, config, &enabled_int.to_string(), &now.to_string(), &now.to_string()],
            )?;
            println!("[Database] IM config inserted for platform: {}", platform);
        } else {
            println!("[Database] IM config updated for platform: {}", platform);
        }
        Ok(())
    }

    pub fn im_config_load(&self, platform: &str) -> Result<Option<String>> {
        println!("[Database] Loading IM config for platform: {}", platform);
        let conn = self.conn.read().unwrap();
        let mut stmt = conn.prepare("SELECT config FROM im_config WHERE platform = ?")?;
        let config = stmt.query_row([platform], |row| row.get(0)).ok();
        println!(
            "[Database] IM config loaded for platform: {}, found: {:?}",
            platform,
            config.is_some()
        );
        Ok(config)
    }

    pub fn im_config_list(&self) -> Result<Vec<serde_json::Value>> {
        println!("[Database] Listing IM configs...");
        let conn = self.conn.read().unwrap();
        let mut stmt = conn.prepare(
            "SELECT platform, config, enabled, created_at, updated_at FROM im_config ORDER BY platform"
        )?;
        let rows = stmt.query_map([], |row| {
            Ok(serde_json::json! ({
                "platform": row.get::<_, String>(0)?,
                "config": row.get::<_, String>(1)?,
                "enabled": row.get::<_, bool>(2)?,
                "created_at": row.get::<_, i64>(3)?,
                "updated_at": row.get::<_, i64>(4)?,
            }))
        })?;

        let mut configs = Vec::new();
        for row in rows {
            configs.push(row?);
        }
        println!("[Database] Found {} IM configs", configs.len());
        Ok(configs)
    }

    pub fn im_config_delete(&self, platform: &str) -> Result<()> {
        println!("[Database] Deleting IM config for platform: {}", platform);
        let conn = self.conn.write().unwrap();
        let count = conn.execute("DELETE FROM im_config WHERE platform = ?", [platform])?;
        println!(
            "[Database] IM config deleted for platform: {}, rows affected: {}",
            platform, count
        );
        Ok(())
    }

    // IM 消息操作
    pub fn im_message_add(
        &self,
        id: &str,
        platform: &str,
        channel_id: &str,
        user_id: &str,
        user_name: &str,
        content: &str,
        is_mention: bool,
        direction: &str,
        status: &str,
    ) -> Result<()> {
        println!(
            "[Database] Adding IM message: {}, platform: {}, direction: {}",
            id, platform, direction
        );
        let conn = self.conn.write().unwrap();
        let now = Local::now().timestamp_millis();
        let is_mention_int = if is_mention { 1 } else { 0 };

        conn.execute(
            "INSERT INTO im_messages (id, platform, channel_id, user_id, user_name, content, is_mention, direction, status, timestamp, created_at, updated_at) 
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            [
                id, platform, channel_id, user_id, user_name, content, 
                &is_mention_int.to_string(), direction, status, 
                &now.to_string(), &now.to_string(), &now.to_string()
            ],
        )?;

        println!(
            "[Database] IM message added successfully: {}, platform: {}",
            id, platform
        );
        Ok(())
    }

    pub fn im_message_list(
        &self,
        platform: Option<&str>,
        channel_id: Option<&str>,
        limit: Option<u32>,
    ) -> Result<Vec<serde_json::Value>> {
        println!(
            "[Database] Listing IM messages, platform: {:?}, channel: {:?}, limit: {:?}",
            platform, channel_id, limit
        );
        let conn = self.conn.read().unwrap();

        // 使用更简单的方法构建查询，避免参数绑定问题
        let mut query = "SELECT id, platform, channel_id, user_id, user_name, content, is_mention, direction, status, timestamp, created_at, updated_at FROM im_messages".to_string();

        if platform.is_some() || channel_id.is_some() {
            query.push_str(" WHERE");
            let mut conditions = Vec::new();

            if let Some(p) = platform {
                conditions.push(format!("platform = '{}'", p.replace("'", "''")));
            }

            if let Some(c) = channel_id {
                if !conditions.is_empty() {
                    conditions.push("AND".to_string());
                }
                conditions.push(format!("channel_id = '{}'", c.replace("'", "''")));
            }

            query.push_str(" ");
            query.push_str(&conditions.join(" "));
        }

        query.push_str(" ORDER BY timestamp DESC");

        if let Some(lim) = limit {
            query.push_str(&format!(" LIMIT {}", lim));
        }

        let mut stmt = conn.prepare(&query)?;
        let rows = stmt.query_map([], |row| {
            Ok(serde_json::json! ({
                "id": row.get::<_, String>(0)?,
                "platform": row.get::<_, String>(1)?,
                "channel_id": row.get::<_, String>(2)?,
                "user_id": row.get::<_, String>(3)?,
                "user_name": row.get::<_, String>(4)?,
                "content": row.get::<_, String>(5)?,
                "is_mention": row.get::<_, bool>(6)?,
                "direction": row.get::<_, String>(7)?,
                "status": row.get::<_, String>(8)?,
                "timestamp": row.get::<_, i64>(9)?,
                "created_at": row.get::<_, i64>(10)?,
                "updated_at": row.get::<_, i64>(11)?,
            }))
        })?;

        let mut messages = Vec::new();
        for row in rows {
            messages.push(row?);
        }
        println!("[Database] Found {} IM messages", messages.len());
        Ok(messages)
    }

    pub fn im_message_update_status(&self, id: &str, status: &str) -> Result<()> {
        println!(
            "[Database] Updating IM message status: {}, status: {}",
            id, status
        );
        let conn = self.conn.write().unwrap();
        let now = Local::now().timestamp_millis();
        conn.execute(
            "UPDATE im_messages SET status = ?, updated_at = ? WHERE id = ?",
            [status, &now.to_string(), id],
        )?;
        println!(
            "[Database] IM message status updated successfully: {}, status: {}",
            id, status
        );
        Ok(())
    }

    pub fn im_message_delete(&self, id: &str) -> Result<()> {
        println!("[Database] Deleting IM message: {}", id);
        let conn = self.conn.write().unwrap();
        let count = conn.execute("DELETE FROM im_messages WHERE id = ?", [id])?;
        println!(
            "[Database] IM message deleted: {}, rows affected: {}",
            id, count
        );
        Ok(())
    }

    pub fn im_message_count(&self, platform: Option<&str>, direction: Option<&str>) -> Result<i64> {
        println!(
            "[Database] Counting IM messages, platform: {:?}, direction: {:?}",
            platform, direction
        );
        let conn = self.conn.read().unwrap();

        // 使用更简单的方法构建查询，避免参数绑定问题
        let mut query = "SELECT COUNT(*) FROM im_messages".to_string();

        if platform.is_some() || direction.is_some() {
            query.push_str(" WHERE");
            let mut conditions = Vec::new();

            if let Some(p) = platform {
                conditions.push(format!("platform = '{}'", p.replace("'", "''")));
            }

            if let Some(d) = direction {
                if !conditions.is_empty() {
                    conditions.push("AND".to_string());
                }
                conditions.push(format!("direction = '{}'", d.replace("'", "''")));
            }

            query.push_str(" ");
            query.push_str(&conditions.join(" "));
        }

        let count: i64 = conn.query_row(&query, [], |row| row.get(0))?;
        println!("[Database] IM message count: {}", count);
        Ok(count)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;
    use tempfile::tempdir;

    #[tokio::test]
    async fn test_database_initialization() {
        // 创建临时目录
        let temp_dir = tempdir().unwrap();
        let db_path = temp_dir.path().join("test.db");

        // 初始化数据库
        let db = Database::new(db_path).unwrap();
        assert!(true, "Database initialization should succeed");
    }

    #[tokio::test]
    async fn test_kv_operations() {
        // 创建临时目录
        let temp_dir = tempdir().unwrap();
        let db_path = temp_dir.path().join("test.db");

        // 初始化数据库
        let db = Database::new(db_path).unwrap();

        // 测试设置值
        db.kv_set("test_key", "test_value").unwrap();

        // 测试获取值
        let value = db.kv_get("test_key").unwrap();
        assert_eq!(value, Some("test_value".to_string()));

        // 测试更新值
        db.kv_set("test_key", "updated_value").unwrap();
        let value = db.kv_get("test_key").unwrap();
        assert_eq!(value, Some("updated_value".to_string()));

        // 测试删除值
        db.kv_remove("test_key").unwrap();
        let value = db.kv_get("test_key").unwrap();
        assert_eq!(value, None);
    }

    #[tokio::test]
    async fn test_cowork_sessions() {
        // 创建临时目录
        let temp_dir = tempdir().unwrap();
        let db_path = temp_dir.path().join("test.db");

        // 初始化数据库
        let db = Database::new(db_path).unwrap();

        // 测试创建会话
        let session_id = "test_session_1";
        db.cowork_create_session(session_id, "Test Session", None, None, None)
            .unwrap();

        // 测试列出会话
        let sessions = db.cowork_list_sessions().unwrap();
        assert!(!sessions.is_empty(), "Should have at least one session");

        // 测试删除会话
        db.cowork_delete_session(session_id).unwrap();
        let sessions = db.cowork_list_sessions().unwrap();
        assert!(
            sessions.is_empty(),
            "Should have no sessions after deletion"
        );
    }

    #[tokio::test]
    async fn test_cowork_messages() {
        // 创建临时目录
        let temp_dir = tempdir().unwrap();
        let db_path = temp_dir.path().join("test.db");

        // 初始化数据库
        let db = Database::new(db_path).unwrap();

        // 创建会话
        let session_id = "test_session_1";
        db.cowork_create_session(session_id, "Test Session", None, None, None)
            .unwrap();

        // 测试添加消息
        let message_id = "test_message_1";
        db.cowork_add_message(message_id, session_id, "user", "Hello world")
            .unwrap();

        // 测试列出消息
        let messages = db.cowork_list_messages(session_id).unwrap();
        assert!(!messages.is_empty(), "Should have at least one message");
    }

    #[tokio::test]
    async fn test_user_memories() {
        // 创建临时目录
        let temp_dir = tempdir().unwrap();
        let db_path = temp_dir.path().join("test.db");

        // 初始化数据库
        let db = Database::new(db_path).unwrap();

        // 测试创建记忆
        let memory_id = "test_memory_1";
        db.user_memory_create(memory_id, "Test memory content", 0.9, false)
            .unwrap();

        // 测试列出记忆
        let memories = db.user_memories_list().unwrap();
        assert!(!memories.is_empty(), "Should have at least one memory");
    }

    #[tokio::test]
    async fn test_scheduled_tasks() {
        // 创建临时目录
        let temp_dir = tempdir().unwrap();
        let db_path = temp_dir.path().join("test.db");

        // 初始化数据库
        let db = Database::new(db_path).unwrap();

        // 测试创建任务
        let task_id = "test_task_1";
        db.scheduled_task_create(task_id, "Test Task", "* * * * *")
            .unwrap();

        // 测试列出任务
        let tasks = db.scheduled_tasks_list().unwrap();
        assert!(!tasks.is_empty(), "Should have at least one task");
    }

    #[tokio::test]
    async fn test_task_runs() {
        // 创建临时目录
        let temp_dir = tempdir().unwrap();
        let db_path = temp_dir.path().join("test.db");

        // 初始化数据库
        let db = Database::new(db_path).unwrap();

        // 创建任务
        let task_id = "test_task_1";
        db.scheduled_task_create(task_id, "Test Task", "* * * * *")
            .unwrap();

        // 测试创建任务运行
        let run_id = "test_run_1";
        db.task_run_create(run_id, task_id).unwrap();

        // 测试列出任务运行
        let runs = db.task_runs_list(Some(task_id)).unwrap();
        assert!(!runs.is_empty(), "Should have at least one task run");

        // 测试完成任务运行
        db.task_run_complete(run_id, "completed", Some("Task output"), None)
            .unwrap();
        let runs = db.task_runs_list(Some(task_id)).unwrap();
        assert!(
            !runs.is_empty(),
            "Should have at least one completed task run"
        );
    }
}
