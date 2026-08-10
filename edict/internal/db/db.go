// Package db 负责 SQLite 连接与表结构迁移。
// 采用与根项目一致的 modernc.org/sqlite（纯 Go、无 cgo）。
package db

import (
	"database/sql"
	"fmt"
	"time"

	_ "modernc.org/sqlite"
)

// Open 打开（或创建）edict 的 SQLite 数据库，并执行迁移。
func Open(path string) (*sql.DB, error) {
	d, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, fmt.Errorf("open sqlite %q: %w", path, err)
	}
	// 单连接，避免 SQLite 写并发锁竞争（edict 为单机伴生服务）。
	d.SetMaxOpenConns(1)
	if _, err := d.Exec(`PRAGMA journal_mode=WAL;`); err != nil {
		return nil, err
	}
	if _, err := d.Exec(`PRAGMA foreign_keys=ON;`); err != nil {
		return nil, err
	}
	if err := Migrate(d); err != nil {
		return nil, err
	}
	return d, nil
}

// Migrate 创建所有表（幂等）。
func Migrate(d *sql.DB) error {
	stmts := []string{
		`CREATE TABLE IF NOT EXISTS tasks (
			id TEXT PRIMARY KEY,
			title TEXT NOT NULL,
			state TEXT NOT NULL DEFAULT 'Taizi',
			org TEXT NOT NULL DEFAULT '太子',
			official TEXT DEFAULT '',
			now TEXT DEFAULT '',
			eta TEXT DEFAULT '-',
			block TEXT DEFAULT '无',
			ac TEXT DEFAULT '',
			output TEXT DEFAULT '',
			archived INTEGER NOT NULL DEFAULT 0,
			archived_at TEXT DEFAULT '',
			review_round INTEGER NOT NULL DEFAULT 0,
			flow_log TEXT NOT NULL DEFAULT '[]',
			todos TEXT NOT NULL DEFAULT '[]',
			source_meta TEXT NOT NULL DEFAULT '{}',
			scheduler TEXT NOT NULL DEFAULT '{}',
			created_at TEXT NOT NULL,
			updated_at TEXT NOT NULL
		);`,
		`CREATE INDEX IF NOT EXISTS ix_tasks_state ON tasks(state);`,
		`CREATE INDEX IF NOT EXISTS ix_tasks_updated ON tasks(updated_at);`,

		`CREATE TABLE IF NOT EXISTS agents (
			id TEXT PRIMARY KEY,
			label TEXT NOT NULL,
			emoji TEXT NOT NULL DEFAULT '',
			role TEXT NOT NULL DEFAULT '',
			model TEXT NOT NULL DEFAULT 'default'
		);`,

		`CREATE TABLE IF NOT EXISTS dispatch_channel (
			id INTEGER PRIMARY KEY CHECK (id = 1),
			channel TEXT NOT NULL DEFAULT 'openclaw'
		);`,

		`CREATE TABLE IF NOT EXISTS model_change_log (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			at TEXT NOT NULL,
			agent_id TEXT NOT NULL,
			old_model TEXT NOT NULL DEFAULT '',
			new_model TEXT NOT NULL DEFAULT '',
			rolled_back INTEGER NOT NULL DEFAULT 0
		);`,

		`CREATE TABLE IF NOT EXISTS activity (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			task_id TEXT NOT NULL,
			kind TEXT NOT NULL,
			at TEXT NOT NULL,
			data TEXT NOT NULL DEFAULT '{}'
		);`,
		`CREATE INDEX IF NOT EXISTS ix_activity_task ON activity(task_id);`,

		`CREATE TABLE IF NOT EXISTS morning_brief (
			id INTEGER PRIMARY KEY CHECK (id = 1),
			date TEXT,
			generated_at TEXT,
			categories TEXT NOT NULL DEFAULT '{}'
		);`,

		`CREATE TABLE IF NOT EXISTS morning_config (
			id INTEGER PRIMARY KEY CHECK (id = 1),
			categories TEXT NOT NULL DEFAULT '[]',
			keywords TEXT NOT NULL DEFAULT '[]',
			custom_feeds TEXT NOT NULL DEFAULT '[]',
			feishu_webhook TEXT NOT NULL DEFAULT ''
		);`,

		`CREATE TABLE IF NOT EXISTS court_discuss (
			session_id TEXT PRIMARY KEY,
			topic TEXT NOT NULL,
			officials TEXT NOT NULL DEFAULT '[]',
			task_id TEXT DEFAULT '',
			round INTEGER NOT NULL DEFAULT 0,
			messages TEXT NOT NULL DEFAULT '[]',
			created_at TEXT NOT NULL,
			updated_at TEXT NOT NULL
		);`,

		`CREATE TABLE IF NOT EXISTS scheduler (
			task_id TEXT PRIMARY KEY,
			retry_count INTEGER NOT NULL DEFAULT 0,
			escalation_level INTEGER NOT NULL DEFAULT 0,
			last_dispatch_status TEXT DEFAULT '',
			last_progress_at TEXT,
			last_dispatch_at TEXT,
			last_dispatch_agent TEXT DEFAULT '',
			enabled INTEGER NOT NULL DEFAULT 1,
			auto_rollback INTEGER NOT NULL DEFAULT 0,
			stall_threshold_sec INTEGER NOT NULL DEFAULT 180
		);`,

		`CREATE TABLE IF NOT EXISTS remote_skills (
			skill_name TEXT NOT NULL,
			agent_id TEXT NOT NULL,
			source_url TEXT NOT NULL DEFAULT '',
			description TEXT NOT NULL DEFAULT '',
			local_path TEXT NOT NULL DEFAULT '',
			added_at TEXT NOT NULL,
			last_updated TEXT NOT NULL,
			status TEXT NOT NULL DEFAULT 'valid',
			PRIMARY KEY (skill_name, agent_id)
		);`,
	}
	for _, s := range stmts {
		if _, err := d.Exec(s); err != nil {
			return fmt.Errorf("migrate: %w", err)
		}
	}
	return nil
}

// NowRFC3339 返回当前 UTC 时间戳（与 Python datetime.now(timezone.utc).isoformat() 对齐）。
func NowRFC3339() string {
	return time.Now().UTC().Format(time.RFC3339)
}
