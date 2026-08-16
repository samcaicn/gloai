// Copyright (c) 2026 MeeJoy
//
// Activity log + entry type used by the front-end's
// `get_recent_activity_log` command and the rotation policy
// described in plan.md §3.5.

use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

use chrono::{DateTime, Local};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ActivityEntry {
    pub timestamp: String,
    pub kind: String,
    pub detail: String,
}

/// File-backed append-only log. The directory is created lazily on
/// the first write. Each entry is a single line of JSON so we can
/// parse the file back into `ActivityEntry` records without a
/// streaming parser.
pub struct ActivityLog;

#[allow(dead_code)] // public API for monitoring; ActivityLog wired to Tauri commands in next PR
impl ActivityLog {
    /// Returns the directory that holds the rotated log files.
    pub fn dir() -> PathBuf {
        dirs::data_local_dir()
            .unwrap_or_else(|| PathBuf::from("."))
            .join("tupai")
            .join("monitor")
    }

    /// Returns the file path for the log file belonging to the
    /// supplied local date.
    pub fn path_for(date: &DateTime<Local>) -> PathBuf {
        Self::dir().join(format!("activity-{}.log", date.format("%Y-%m-%d")))
    }

    /// Appends an entry to the today's log file. Errors are
    /// swallowed: the monitor must never panic the host process.
    pub fn append(entry: &ActivityEntry) {
        let dir = Self::dir();
        if let Err(error) = fs::create_dir_all(&dir) {
            eprintln!("[monitor] failed to create log dir: {}", error);
            return;
        }
        let path = Self::path_for(&Local::now());
        let line = match serde_json::to_string(entry) {
            Ok(s) => s,
            Err(error) => {
                eprintln!("[monitor] failed to serialise entry: {}", error);
                return;
            }
        };
        let mut options = fs::OpenOptions::new();
        options.create(true).append(true);
        if let Ok(mut file) = options.open(&path) {
            if let Err(error) = writeln!(file, "{}", line) {
                eprintln!("[monitor] failed to write log line: {}", error);
            }
        }
        // 写入后顺手裁剪旧文件,避免长期运行后磁盘堆积数千个
        // activity-YYYY-MM-DD.log。保留最近 MAX_RETENTION_DAYS 天。
        Self::prune_old_logs();
    }

    /// 保留策略:删除早于 MAX_RETENTION_DAYS 天的日志文件。
    /// 文件名格式 `activity-YYYY-MM-DD.log`,日期早于 cutoff 的删除。
    /// 任何 IO 错误都吞掉(本模块绝不抛 panic)。
    const MAX_RETENTION_DAYS: i64 = 30;
    fn prune_old_logs() {
        let cutoff = Local::now() - chrono::Duration::days(Self::MAX_RETENTION_DAYS);
        let cutoff_str = cutoff.format("%Y-%m-%d").to_string();
        let dir = Self::dir();
        let Ok(read_dir) = fs::read_dir(&dir) else { return };
        for entry in read_dir.flatten() {
            let path = entry.path();
            // 解析文件名 activity-YYYY-MM-DD.log 中的日期部分。
            let Some(name) = path.file_name().and_then(|s| s.to_str()) else { continue };
            let Some(date_str) = name
                .strip_prefix("activity-")
                .and_then(|s| s.strip_suffix(".log"))
            else { continue };
            // 字符串字典序 == ISO 日期顺序,可直接比较。
            if date_str < cutoff_str.as_str() {
                let _ = fs::remove_file(&path);
            }
        }
    }

    /// Reads up to `limit` most recent entries (across all rotated
    /// files). Newest entries come first.
    pub fn read_recent(limit: usize) -> Vec<ActivityEntry> {
        let dir = Self::dir();
        let Ok(read_dir) = fs::read_dir(&dir) else {
            return Vec::new();
        };
        let mut files: Vec<PathBuf> = read_dir
            .filter_map(|entry| entry.ok().map(|e| e.path()))
            .filter(|path| {
                path.extension().and_then(|ext| ext.to_str()) == Some("log")
            })
            .collect();
        // Sort newest first by file name (`activity-YYYY-MM-DD.log`).
        files.sort_by(|a, b| b.file_name().cmp(&a.file_name()));

        let mut entries: Vec<ActivityEntry> = Vec::new();
        for path in files {
            if let Ok(content) = fs::read_to_string(&path) {
                for line in content.lines().rev() {
                    if line.is_empty() {
                        continue;
                    }
                    if let Ok(entry) = serde_json::from_str::<ActivityEntry>(line) {
                        entries.push(entry);
                        if entries.len() >= limit {
                            return entries;
                        }
                    }
                }
            }
        }
        entries
    }

    /// Wipes the activity log directory. Used by the test suite.
    pub fn reset() {
        if let Err(error) = fs::remove_dir_all(Self::dir()) {
            // ENOTEMPTY / ENOENT are both fine here.
            let _ = error;
        }
        if let Err(error) = fs::create_dir_all(Self::dir()) {
            eprintln!("[monitor] failed to recreate log dir: {}", error);
        }
    }
}

/// Writes a synthetic entry to today's log. Convenience helper for
/// the front-end's "synthesise an event" debug action and for the
/// test suite.
#[allow(dead_code)] // public API for monitoring; invoked from JS in next PR
pub fn log_event(kind: impl Into<String>, detail: impl Into<String>) -> ActivityEntry {
    let entry = ActivityEntry {
        timestamp: Local::now().to_rfc3339(),
        kind: kind.into(),
        detail: detail.into(),
    };
    ActivityLog::append(&entry);
    entry
}

#[allow(dead_code)] // public API for monitoring; invoked from JS in next PR
pub fn dir_exists() -> bool {
    Path::new(&ActivityLog::dir()).exists()
}
