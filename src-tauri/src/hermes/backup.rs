
//
// Backup / restore helpers. The TypeScript module exported functions
// to: snapshot the app database to a tarball, list existing
// backups, and restore a backup. The Rust port exposes the same
// surface and relies on `tar` + `flate2` to do the actual work; this
// is intentionally stubbed out and the main thread is expected to
// wire it up.

use std::path::PathBuf;
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct BackupInfo {
    pub id: String,
    pub created_at: i64,
    pub size_bytes: u64,
    pub path: PathBuf,
    pub label: Option<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct BackupSummary {
    pub total: usize,
    pub total_bytes: u64,
}

pub fn list(dir: &PathBuf) -> Result<Vec<BackupInfo>, String> {
    let mut out = Vec::new();
    let entries = std::fs::read_dir(dir).map_err(|e| e.to_string())?;
    for entry in entries.flatten() {
        let path = entry.path();
        if path.extension().and_then(|s| s.to_str()) == Some("tar.gz") {
            let meta = entry.metadata().ok();
            let size = meta.as_ref().map(|m| m.len()).unwrap_or(0);
            let id = path.file_stem().and_then(|s| s.to_str()).unwrap_or("").to_string();
            out.push(BackupInfo { id, created_at: 0, size_bytes: size, path, label: None });
        }
    }
    Ok(out)
}

pub fn summarize(backups: &[BackupInfo]) -> BackupSummary {
    BackupSummary { total: backups.len(), total_bytes: backups.iter().map(|b| b.size_bytes).sum() }
}
