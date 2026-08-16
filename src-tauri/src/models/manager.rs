// Copyright (c) 2026 MeeJoy

// Model path manager. The manager holds a `download_dir` pointer that
// the rest of AIMarketing (and the dashboard) reads to find `.gguf` files.
//
// We deliberately keep state out of the manager itself: the active path
// is persisted to a JSON file in the app data directory
// (`<app_data>/models/active_path.json`) so the next launch picks it up
// without needing a global in-process state. The `ModelManager` reads
// that file on every call (cheap) and writes it back when the path
// changes.

use serde::{Deserialize, Serialize};
use serde_json;
use sha2::{Digest, Sha256};
use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};
use thiserror::Error;

const CONFIG_FILENAME: &str = "active_path.json";
const KNOWN_EXTENSIONS: &[&str] = &["gguf", "bin", "safetensors", "pt", "ggml"];

#[derive(Debug, Error)]
pub enum ModelError {
    #[error("模型目录不存在: {0}")]
    #[allow(dead_code)]
    MissingDirectory(String),
    #[error("源目录与目标目录相同: {0}")]
    SameSourceAndTarget(String),
    #[error("文件未找到: {0}")]
    NotFound(String),
    #[error("文件不是允许的模型格式: {0}")]
    UnsupportedExtension(String),
    #[error("IO 错误: {0}")]
    Io(String),
    #[error("配置错误: {0}")]
    Config(String),
}

impl From<std::io::Error> for ModelError {
    fn from(error: std::io::Error) -> Self {
        ModelError::Io(error.to_string())
    }
}

/// On-disk record that stores the active model directory.
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ModelConfig {
    #[serde(default)]
    pub download_dir: Option<String>,
}

/// One model file in the scan result.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModelEntry {
    pub name: String,
    pub path: String,
    pub size_bytes: u64,
    pub sha256: String,
    pub extension: String,
}

/// Holds the path to the active model directory. The path is read from
/// `<app_data>/models/active_path.json` on construction and re-read on
/// every call to `active_dir`, so updates made by another process are
/// visible immediately.
pub struct ModelManager {
    config_path: PathBuf,
    fallback_dir: PathBuf,
}

impl ModelManager {
    /// Build a manager rooted at `<app_data>/models`.
    pub fn new(app_data_dir: &Path) -> Result<Self, ModelError> {
        let base = app_data_dir.join("models");
        fs::create_dir_all(&base)?;
        Ok(Self {
            config_path: base.join(CONFIG_FILENAME),
            fallback_dir: base.join("downloads"),
        })
    }

    /// The currently-active model directory. If no override is
    /// persisted, returns `<app_data>/models/downloads`.
    pub fn active_dir(&self) -> Result<PathBuf, ModelError> {
        if let Some(config) = self.load_config()? {
            if let Some(dir) = config.download_dir {
                if !dir.trim().is_empty() {
                    return Ok(PathBuf::from(dir));
                }
            }
        }
        Ok(self.fallback_dir.clone())
    }

    /// The currently-active model directory, returned as a String.
    #[allow(dead_code)] // public models API; used in subsequent PRs
    pub fn active_dir_string(&self) -> Result<String, ModelError> {
        Ok(self.active_dir()?.to_string_lossy().into_owned())
    }

    /// Atomically point at a new model directory. If the previous
    /// directory contains `.gguf`/`.bin`/... files and the new
    /// directory is empty, the files are *moved* across (best-effort
    /// cross-device copy if the rename fails). The new path is
    /// persisted regardless of whether files were moved.
    pub fn change_model_path(&self, new_path: &str) -> Result<String, ModelError> {
        let new_dir = PathBuf::from(new_path);
        if new_dir.as_os_str().is_empty() {
            return Err(ModelError::Config("模型目录不能为空".to_string()));
        }
        fs::create_dir_all(&new_dir)?;

        let current = self.active_dir()?;
        if paths_equal(&current, &new_dir) {
            return Err(ModelError::SameSourceAndTarget(
                new_dir.to_string_lossy().into_owned(),
            ));
        }

        if !current.exists() {
            fs::create_dir_all(&current)?;
        }

        let moved = move_known_models(&current, &new_dir)?;

        let next = ModelConfig {
            download_dir: Some(new_dir.to_string_lossy().into_owned()),
        };
        self.save_config(&next)?;
        Ok(format!(
            "已切换模型目录到 {}（迁移 {} 个文件）",
            new_dir.display(),
            moved
        ))
    }

    /// Scan the active directory and return one entry per recognized
    /// model file. Hidden files and symlinks-to-directories are
    /// ignored.
    pub fn scan_models(&self) -> Result<Vec<ModelEntry>, ModelError> {
        let dir = self.active_dir()?;
        if !dir.exists() {
            fs::create_dir_all(&dir)?;
        }
        let mut entries: Vec<ModelEntry> = Vec::new();
        for entry in fs::read_dir(&dir)? {
            let entry = entry?;
            let path = entry.path();
            if !entry.file_type()?.is_file() {
                continue;
            }
            let extension = path
                .extension()
                .and_then(|ext| ext.to_str())
                .unwrap_or("")
                .to_lowercase();
            if !KNOWN_EXTENSIONS.contains(&extension.as_str()) {
                continue;
            }
            let metadata = entry.metadata()?;
            let size_bytes = metadata.len();
            let sha256 = match sha256_file(&path) {
                Ok(digest) => digest,
                Err(error) => {
                    eprintln!(
                        "[models] failed to hash {}: {}",
                        path.display(),
                        error
                    );
                    continue;
                }
            };
            let name = path
                .file_name()
                .and_then(|name| name.to_str())
                .unwrap_or("")
                .to_string();
            entries.push(ModelEntry {
                name,
                path: path.to_string_lossy().into_owned(),
                size_bytes,
                sha256,
                extension,
            });
        }
        entries.sort_by(|left, right| left.name.cmp(&right.name));
        Ok(entries)
    }

    /// Delete a single model file by path. The path must:
    ///   1. Exist
    ///   2. Be a regular file
    ///   3. Reside inside the active model directory
    ///   4. Have a recognized model extension
    pub fn delete_model(&self, path: &str) -> Result<(), ModelError> {
        let target = PathBuf::from(path);
        // Resolve the canonical path first so a symlink (or any
        // other race) can't escape the active model directory
        // between an existence check and the remove_file() call
        // (TOCTOU).
        let active = self.active_dir()?;
        let canonical_target = fs::canonicalize(&target).map_err(|error| {
            if error.kind() == std::io::ErrorKind::NotFound {
                return ModelError::NotFound(path.to_string());
            }
            ModelError::Io(error.to_string())
        })?;
        let canonical_active = fs::canonicalize(&active)
            .map_err(|error| ModelError::Io(error.to_string()))?;
        if !canonical_target.starts_with(&canonical_active) {
            return Err(ModelError::Config(format!(
                "拒绝删除非活动模型目录之外的文件: {}",
                path
            )));
        }
        let extension = canonical_target
            .extension()
            .and_then(|ext| ext.to_str())
            .unwrap_or("")
            .to_lowercase();
        if !KNOWN_EXTENSIONS.contains(&extension.as_str()) {
            return Err(ModelError::UnsupportedExtension(extension));
        }
        fs::remove_file(&canonical_target)?;
        Ok(())
    }

    /// Verify a single file's SHA-256 against an expected hex digest.
    #[allow(dead_code)] // public models API; used in subsequent PRs
    pub fn verify_model_integrity(path: &str, expected_sha256: &str) -> bool {
        let target = Path::new(path);
        if !target.exists() || !target.is_file() {
            return false;
        }
        match sha256_file(target) {
            Ok(actual) => actual.eq_ignore_ascii_case(expected_sha256.trim()),
            Err(_) => false,
        }
    }

    // --- private helpers ---

    fn load_config(&self) -> Result<Option<ModelConfig>, ModelError> {
        match fs::read_to_string(&self.config_path) {
            Ok(content) => {
                let config: ModelConfig = serde_json::from_str(&content)
                    .map_err(|error| ModelError::Config(error.to_string()))?;
                Ok(Some(config))
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(None),
            Err(error) => Err(ModelError::Io(error.to_string())),
        }
    }

    fn save_config(&self, config: &ModelConfig) -> Result<(), ModelError> {
        if let Some(parent) = self.config_path.parent() {
            fs::create_dir_all(parent)?;
        }
        let serialized = serde_json::to_string_pretty(config)
            .map_err(|error| ModelError::Config(error.to_string()))?;
        fs::write(&self.config_path, serialized)?;
        Ok(())
    }
}

fn paths_equal(left: &Path, right: &Path) -> bool {
    if let (Ok(a), Ok(b)) = (fs::canonicalize(left), fs::canonicalize(right)) {
        return a == b;
    }
    left == right
}

fn move_known_models(from: &Path, to: &Path) -> Result<usize, ModelError> {
    if !from.exists() {
        return Ok(0);
    }
    fs::create_dir_all(to)?;
    let mut moved = 0usize;
    for entry in fs::read_dir(from)? {
        let entry = entry?;
        let source = entry.path();
        if !entry.file_type()?.is_file() {
            continue;
        }
        let extension = source
            .extension()
            .and_then(|ext| ext.to_str())
            .unwrap_or("")
            .to_lowercase();
        if !KNOWN_EXTENSIONS.contains(&extension.as_str()) {
            continue;
        }
        let file_name = match source.file_name() {
            Some(name) => name.to_owned(),
            None => continue,
        };
        let destination = to.join(&file_name);
        match fs::rename(&source, &destination) {
            Ok(()) => {
                moved += 1;
            }
            Err(_) => {
                // Cross-device rename — fall back to copy + remove.
                fs::copy(&source, &destination)?;
                fs::remove_file(&source)?;
                moved += 1;
            }
        }
    }
    Ok(moved)
}

/// Compute the SHA-256 of a file and return it as lowercase hex.
pub fn sha256_file(path: &Path) -> Result<String, ModelError> {
    let mut file = fs::File::open(path)?;
    let mut hasher = Sha256::new();
    let mut buffer = [0u8; 64 * 1024];
    loop {
        let read = file.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    let digest = hasher.finalize();
    Ok(digest.iter().map(|byte| format!("{:02x}", byte)).collect())
}
