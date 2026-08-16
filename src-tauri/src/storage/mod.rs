// Copyright (c) 2026 MeeJoy
//
// DuckDB 数据中台
//
// 提供 8 张核心表（worker_task_log / teach_record_log / scene_asset_index /
// skill_version_manage / mcp_connect_log / skill_score_eval /
// skill_auto_iter_draft / pipeline_def）的 DDL 建表 + 简单 CRUD 辅助函数。
//
// 连接管理采用 Arc<Mutex<Connection>> 单连接实现（简单优先）。
// DuckDB 本身支持 MVCC，单连接通过 Mutex 串行化已足够满足桌面应用的
// 日志 / 技能管理写入负载。未来若需并发读可改用 try_clone() 扩展池。

use std::path::Path;
use std::sync::{Arc, Mutex, MutexGuard};

use duckdb::Connection;

pub mod autoskill_draft;
pub mod pipeline;
pub mod skill_eval;
pub mod skill_version;
pub mod teach_record;
pub mod worker_task_log;

// === 连接池 ===============================================================

/// DuckDB 连接池（单连接 + Mutex 实现）。
///
/// Clone 时仅增加 Arc 引用计数，底层 Connection 共享。
/// 适合作为 Tauri State 注入，或多线程共享访问。
#[derive(Clone)]
pub struct DuckDBPool {
    conn: Arc<Mutex<Connection>>,
}

/// 借出的连接（Mutex 守卫），生命周期绑定到 &DuckDBPool。
/// 用法：`let conn = pool.get_conn(); conn.execute(...)?;`
pub type PooledConn<'a> = MutexGuard<'a, Connection>;

// === DDL ==================================================================

/// 7 张核心表 + 索引 + schema 版本表的 DDL（DuckDB 语法）。
/// 全部使用 IF NOT EXISTS，可安全重复执行。
const SCHEMA_DDL: &str = r#"
-- 1. Worker 任务执行日志
CREATE TABLE IF NOT EXISTS worker_task_log (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    scene TEXT NOT NULL CHECK (scene IN ('work','personal','hobby')),
    task_type TEXT NOT NULL,
    skill_id TEXT,
    skill_version TEXT,
    status TEXT NOT NULL CHECK (status IN ('queued','running','retrying','succeeded','failed','cancelled')),
    priority INTEGER DEFAULT 0,
    params JSON,
    result JSON,
    error TEXT,
    retry_count INTEGER DEFAULT 0,
    duration_ms BIGINT,
    created_at TIMESTAMP DEFAULT now(),
    started_at TIMESTAMP,
    finished_at TIMESTAMP
);

-- 2. 示教录制日志
CREATE TABLE IF NOT EXISTS teach_record_log (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    scene TEXT NOT NULL CHECK (scene IN ('work','personal','hobby')),
    app_name TEXT NOT NULL,
    protocol TEXT NOT NULL CHECK (protocol IN ('cdp','uia','computer_use')),
    steps JSON NOT NULL,
    step_count INTEGER,
    dedup_hash TEXT,
    created_at TIMESTAMP DEFAULT now()
);

-- 3. 场景资产索引
CREATE TABLE IF NOT EXISTS scene_asset_index (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    scene TEXT NOT NULL CHECK (scene IN ('work','personal','hobby')),
    file_path TEXT NOT NULL,
    file_type TEXT NOT NULL,
    file_size BIGINT,
    content_hash TEXT,
    embedding FLOAT[384],
    meta JSON,
    indexed_at TIMESTAMP DEFAULT now()
);

-- 4. 技能版本管理
CREATE TABLE IF NOT EXISTS skill_version_manage (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    scene TEXT NOT NULL CHECK (scene IN ('work','personal','hobby')),
    skill_id TEXT NOT NULL,
    version TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('draft','active','watching','rollback','archived')),
    score INTEGER,
    score_detail JSON,
    content TEXT,
    changelog TEXT,
    created_at TIMESTAMP DEFAULT now(),
    activated_at TIMESTAMP,
    UNIQUE(scene, skill_id, version)
);

-- 5. MCP 连接日志
CREATE TABLE IF NOT EXISTS mcp_connect_log (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    scene TEXT NOT NULL CHECK (scene IN ('work','personal','hobby')),
    server_name TEXT NOT NULL,
    server_url TEXT,
    status TEXT NOT NULL CHECK (status IN ('connected','disconnected','error')),
    error TEXT,
    meta JSON,
    created_at TIMESTAMP DEFAULT now()
);

-- 6. 技能评估打分记录
CREATE TABLE IF NOT EXISTS skill_score_eval (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    scene TEXT NOT NULL CHECK (scene IN ('work','personal','hobby')),
    skill_id TEXT NOT NULL,
    skill_version TEXT NOT NULL,
    success_rate FLOAT,
    stability_score FLOAT,
    efficiency_score FLOAT,
    generality_score FLOAT,
    total_score INTEGER,
    sample_count INTEGER,
    eval_detail JSON,
    created_at TIMESTAMP DEFAULT now()
);

-- 7. AutoSkill 草稿
CREATE TABLE IF NOT EXISTS skill_auto_iter_draft (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    scene TEXT NOT NULL CHECK (scene IN ('work','personal','hobby')),
    skill_id TEXT NOT NULL,
    draft_version TEXT NOT NULL,
    source TEXT NOT NULL CHECK (source IN ('teaching','log_mining','manual')),
    status TEXT NOT NULL CHECK (status IN ('drafting','scoring','pending_confirm','upgrading','watching','running','rejected','rollback')),
    content TEXT,
    old_score INTEGER,
    new_score INTEGER,
    optimization_points JSON,
    watch_started_at TIMESTAMP,
    watch_score_drop INTEGER,
    created_at TIMESTAMP DEFAULT now()
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_wtl_scene_status ON worker_task_log(scene, status);
CREATE INDEX IF NOT EXISTS idx_wtl_skill ON worker_task_log(skill_id, skill_version);
CREATE INDEX IF NOT EXISTS idx_trl_scene ON teach_record_log(scene, created_at);
CREATE INDEX IF NOT EXISTS idx_sai_scene ON scene_asset_index(scene);
CREATE INDEX IF NOT EXISTS idx_svm_skill ON skill_version_manage(scene, skill_id, status);
CREATE INDEX IF NOT EXISTS idx_sse_skill ON skill_score_eval(scene, skill_id, skill_version);
CREATE INDEX IF NOT EXISTS idx_said_skill ON skill_auto_iter_draft(scene, skill_id, status);

-- schema 版本表
CREATE TABLE IF NOT EXISTS schema_meta (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
-- 8. 流水线定义
CREATE TABLE IF NOT EXISTS pipeline_def (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    scene TEXT NOT NULL CHECK (scene IN ('work','personal','hobby')),
    steps_json TEXT NOT NULL DEFAULT '[]',
    rounds INTEGER DEFAULT 1,
    current_round INTEGER DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'idle' CHECK (status IN ('idle','running','paused','completed','stopped')),
    created_at TIMESTAMP DEFAULT now(),
    updated_at TIMESTAMP DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_pd_scene ON pipeline_def(scene, status);
INSERT OR IGNORE INTO schema_meta VALUES ('version', '1');
"#;

// === 初始化 ==============================================================

impl DuckDBPool {
    /// 初始化数据库：打开 / 创建 duckdb.db，执行 DDL 建表 + 索引。
    ///
    /// `db_path` 通常为 `app_data_dir/tupai/duckdb.db`。
    /// 父目录不存在时自动创建。
    ///
    /// 当 WAL 文件损坏导致打开失败时，自动删除 `.wal` 文件并重试。
    /// 若 WAL 删除后仍失败，再尝试删除整个 `.db` 文件全新创建
    ///（autoskill 数据是可重建的衍生数据，不值得为它阻断启动）。
    pub fn init(db_path: &Path) -> Result<Self, duckdb::Error> {
        // 确保父目录存在（best-effort）
        if let Some(parent) = db_path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }

        // 带重试的 open：处理 Windows 文件锁定（前一个进程刚被杀，
        // OS 未立即释放句柄）。正常情况第一次就成功；仅在文件被锁时重试。
        fn open_with_retry(path: &Path, max_retries: u32) -> Result<Connection, duckdb::Error> {
            for attempt in 0..max_retries {
                match Connection::open(path) {
                    Ok(conn) => return Ok(conn),
                    Err(e) => {
                        let msg = format!("{}", e);
                        let is_locked = msg.contains("另一个程序")
                            || msg.contains("another process")
                            || msg.contains("being used by another");
                        if is_locked && attempt < max_retries - 1 {
                            log::warn!(
                                "[storage] DuckDB file locked, retry {}/{} after 500ms",
                                attempt + 1, max_retries - 1
                            );
                            std::thread::sleep(std::time::Duration::from_millis(500));
                            continue;
                        }
                        return Err(e);
                    }
                }
            }
            // Only reached if max_retries == 0 — do a direct open
            Connection::open(path)
        }

        // 预清理：如果存在 stale WAL 文件，说明前一个进程未正常关闭（被杀/崩溃），
        // DuckDB checkpoint 未完成。直接删除 WAL 避免 "Failure while replaying WAL" 错误。
        // autoskill 数据是衍生数据，丢失未 checkpoint 的 WAL 是可接受的。
        let wal_path = db_path.with_extension("db.wal");
        if wal_path.exists() {
            log::info!(
                "[storage] Pre-open: deleting stale WAL file: {}",
                wal_path.display()
            );
            let _ = std::fs::remove_file(&wal_path);
        }

        // 第一次尝试：带重试的正常打开
        match open_with_retry(db_path, 5) {
            Ok(conn) => {
                conn.execute_batch(SCHEMA_DDL)?;
                Ok(Self {
                    conn: Arc::new(Mutex::new(conn)),
                })
            }
            Err(e) => {
                log::warn!(
                    "[storage] DuckDB open failed (will try recovery): {} — path={}",
                    e,
                    db_path.display()
                );

                // 恢复策略 1：删除 WAL 文件后重试（WAL 可能被第一次失败的 open 重新创建）
                if wal_path.exists() {
                    log::info!(
                        "[storage] Deleting corrupt WAL file: {}",
                        wal_path.display()
                    );
                    let _ = std::fs::remove_file(&wal_path);
                }

                match open_with_retry(db_path, 3) {
                    Ok(conn) => {
                        log::info!(
                            "[storage] DuckDB recovered after WAL deletion"
                        );
                        conn.execute_batch(SCHEMA_DDL)?;
                        Ok(Self {
                            conn: Arc::new(Mutex::new(conn)),
                        })
                    }
                    Err(e2) => {
                        log::warn!(
                            "[storage] DuckDB still failed after WAL deletion: {} — will recreate db file",
                            e2
                        );

                        // 恢复策略 2：删除整个 db 文件全新创建
                        // autoskill 数据是衍生数据（可从 worker_task_log 重建），
                        // 不值得为损坏的 db 文件阻断启动。
                        // 等待 500ms 给 OS 更多时间释放句柄
                        std::thread::sleep(std::time::Duration::from_millis(500));
                        let _ = std::fs::remove_file(db_path);
                        // wal 残留
                        let _ = std::fs::remove_file(&wal_path);

                        match open_with_retry(db_path, 3) {
                            Ok(conn) => {
                                log::info!(
                                    "[storage] DuckDB recreated fresh db file"
                                );
                                conn.execute_batch(SCHEMA_DDL)?;
                                Ok(Self {
                                    conn: Arc::new(Mutex::new(conn)),
                                })
                            }
                            Err(e3) => {
                                log::error!(
                                    "[storage] DuckDB init failed even after full recreate: {}",
                                    e3
                                );
                                Err(e3)
                            }
                        }
                    }
                }
            }
        }
    }

    /// 获取连接（Mutex 守卫）。
    ///
    /// 若 Mutex 中毒（持有期间线程 panic），仍恢复连接以保证健壮性。
    /// 返回的守卫生命周期绑定到 &self，用完自动释放。
    pub fn get_conn(&self) -> PooledConn<'_> {
        self.conn.lock().unwrap_or_else(|e| e.into_inner())
    }
}

// === 测试 ================================================================

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_init_and_ddl() {
        // 使用内存数据库验证 DDL 全部可执行
        let conn = Connection::open_in_memory().unwrap();
        conn.execute_batch(SCHEMA_DDL).unwrap();

        // 验证 7 张表都已创建
        for table in [
            "worker_task_log",
            "teach_record_log",
            "scene_asset_index",
            "skill_version_manage",
            "mcp_connect_log",
            "skill_score_eval",
            "skill_auto_iter_draft",
            "schema_meta",
        ] {
            let count: i64 = conn
                .query_row(
                    &format!(
                        "SELECT count(*) FROM information_schema.tables WHERE table_name = '{}'",
                        table
                    ),
                    [],
                    |row| row.get(0),
                )
                .unwrap();
            assert_eq!(count, 1, "表 {} 未创建", table);
        }

        // 验证 schema_meta 版本写入
        let version: String = conn
            .query_row(
                "SELECT value FROM schema_meta WHERE key = 'version'",
                [],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(version, "1");
    }

    #[test]
    fn test_pool_clone_and_get_conn() {
        let conn = Connection::open_in_memory().unwrap();
        conn.execute_batch(SCHEMA_DDL).unwrap();
        let pool = DuckDBPool {
            conn: Arc::new(Mutex::new(conn)),
        };
        let pool2 = pool.clone();

        // 两个 clone 共享同一底层连接
        {
            let c1 = pool.get_conn();
            c1.execute(
                "INSERT INTO schema_meta (key, value) VALUES ('test_key', 'test_val')",
                [],
            )
            .unwrap();
        }
        {
            let c2 = pool2.get_conn();
            let val: String = c2
                .query_row(
                    "SELECT value FROM schema_meta WHERE key = 'test_key'",
                    [],
                    |row| row.get(0),
                )
                .unwrap();
            assert_eq!(val, "test_val");
        }
    }
}
