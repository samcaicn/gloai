//! Local filesystem provider and model-facing file tools.

mod tools;

use std::path::{Path, PathBuf};
use std::sync::Arc;

use async_trait::async_trait;
use dsh_events::Disposer;
use dsh_runtime_ports::{
    FsEditOutcome, FsPort, FsWriteOp, FsWriteOutcome, GrepMatch, PortError, PortErrorKind,
    PortResult,
};
use dsh_system_prompt::SystemPrompt;
use dsh_tool_contracts::ToolRegistry;
use globset::{Glob, GlobBuilder};
use ignore::WalkBuilder;
use regex::Regex;
use tokio::fs;

pub use tools::install_fs_tools;

pub const READ_LIMIT: usize = 2000;

#[derive(Clone)]
pub struct LocalFs {
    root: PathBuf,
}

impl LocalFs {
    pub fn new(root: impl Into<PathBuf>) -> Self {
        Self { root: root.into() }
    }
}

#[async_trait]
impl FsPort for LocalFs {
    fn workspace_root(&self) -> &Path {
        &self.root
    }

    fn resolve(&self, path: &str) -> PortResult<PathBuf> {
        if path.trim().is_empty() {
            return Err(PortError::new(
                PortErrorKind::InvalidRequest,
                "path must be a non-empty string",
            ));
        }
        let candidate = PathBuf::from(path);
        let resolved = if candidate.is_absolute() {
            candidate
        } else {
            self.root.join(candidate)
        };
        let normalized = normalize(&resolved);
        let root = normalize(&self.root);
        if !normalized.starts_with(&root) {
            return Err(PortError::new(
                PortErrorKind::PermissionDenied,
                format!("{} is outside the workspace", resolved.display()),
            ));
        }
        Ok(normalized)
    }

    async fn read_text(&self, path: &Path) -> PortResult<String> {
        fs::read_to_string(path).await.map_err(io_error)
    }

    async fn write_text(&self, path: &Path, content: &str) -> PortResult<FsWriteOutcome> {
        let exists = fs::try_exists(path).await.map_err(io_error)?;
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).await.map_err(io_error)?;
        }
        fs::write(path, content).await.map_err(io_error)?;
        Ok(FsWriteOutcome {
            path: path.to_path_buf(),
            operation: if exists {
                FsWriteOp::Update
            } else {
                FsWriteOp::Create
            },
        })
    }

    async fn edit_text(
        &self,
        path: &Path,
        old: &str,
        new: &str,
        replace_all: bool,
    ) -> PortResult<FsEditOutcome> {
        if old.is_empty() {
            return Err(PortError::new(
                PortErrorKind::InvalidRequest,
                "old_string must be non-empty",
            ));
        }
        let original = fs::read_to_string(path).await.map_err(io_error)?;
        let matches = original.matches(old).count();
        if matches == 0 {
            return Err(PortError::new(
                PortErrorKind::InvalidRequest,
                "old_string was not found",
            ));
        }
        if matches > 1 && !replace_all {
            return Err(PortError::new(
                PortErrorKind::InvalidRequest,
                format!("old_string matched {matches} times; pass replace_all to replace every match"),
            ));
        }
        let updated = if replace_all {
            original.replace(old, new)
        } else {
            original.replacen(old, new, 1)
        };
        fs::write(path, updated).await.map_err(io_error)?;
        Ok(FsEditOutcome {
            path: path.to_path_buf(),
            replacements: if replace_all { matches } else { 1 },
        })
    }

    async fn glob(&self, pattern: &str, search_path: Option<&Path>) -> PortResult<Vec<PathBuf>> {
        let root = search_path.unwrap_or(&self.root).to_path_buf();
        let glob = glob_for(pattern)?;
        let matcher = glob.compile_matcher();
        let mut paths = Vec::new();
        let walker = WalkBuilder::new(&root)
            .hidden(false)
            .git_ignore(false)
            .build();
        for entry in walker {
            let entry = entry.map_err(|error| {
                PortError::new(PortErrorKind::Backend, error.to_string())
            })?;
            if !entry.file_type().map(|t| t.is_file()).unwrap_or(false) {
                continue;
            }
            let path = entry.path();
            let relative = path.strip_prefix(&root).unwrap_or(path);
            let basename = path.file_name().and_then(|n| n.to_str()).unwrap_or_default();
            let rel_str = relative.to_string_lossy();
            let matched = if pattern.contains('/') {
                matcher.is_match(rel_str.as_ref())
            } else {
                matcher.is_match(basename) || matcher.is_match(rel_str.as_ref())
            };
            if matched {
                paths.push(path.to_path_buf());
            }
        }
        paths.sort_by_key(|path| {
            std::fs::metadata(path)
                .and_then(|m| m.modified())
                .ok()
        });
        paths.reverse();
        Ok(paths)
    }

    async fn grep(
        &self,
        pattern: &str,
        search_path: Option<&Path>,
        include: Option<&str>,
    ) -> PortResult<Vec<GrepMatch>> {
        let regex = Regex::new(pattern).map_err(|error| {
            PortError::new(PortErrorKind::InvalidRequest, error.to_string())
        })?;
        let include_glob = include.map(glob_for).transpose()?;
        let root = search_path.unwrap_or(&self.root).to_path_buf();
        if root.is_file() {
            return grep_file(&regex, &root).await;
        }
        let mut matches = Vec::new();
        for entry in WalkBuilder::new(&root).hidden(false).git_ignore(false).build() {
            let entry = entry.map_err(|error| {
                PortError::new(PortErrorKind::Backend, error.to_string())
            })?;
            if !entry.file_type().map(|t| t.is_file()).unwrap_or(false) {
                continue;
            }
            let path = entry.path();
            if let Some(glob) = &include_glob {
                let name = path.file_name().and_then(|n| n.to_str()).unwrap_or_default();
                if !glob.compile_matcher().is_match(name) {
                    continue;
                }
            }
            matches.extend(grep_file(&regex, path).await?);
        }
        Ok(matches)
    }
}

fn glob_for(pattern: &str) -> PortResult<Glob> {
    GlobBuilder::new(pattern)
        .literal_separator(true)
        .build()
        .map_err(|error| PortError::new(PortErrorKind::InvalidRequest, error.to_string()))
}

async fn grep_file(regex: &Regex, path: &Path) -> PortResult<Vec<GrepMatch>> {
    let text = match fs::read_to_string(path).await {
        Ok(text) => text,
        Err(_) => return Ok(Vec::new()),
    };
    Ok(text
        .lines()
        .enumerate()
        .filter(|(_, line)| regex.is_match(line))
        .map(|(index, line)| GrepMatch {
            path: path.to_path_buf(),
            line_number: (index + 1) as u32,
            line: line.to_string(),
        })
        .collect())
}

fn io_error(error: std::io::Error) -> PortError {
    let kind = match error.kind() {
        std::io::ErrorKind::NotFound => PortErrorKind::NotFound,
        std::io::ErrorKind::PermissionDenied => PortErrorKind::PermissionDenied,
        _ => PortErrorKind::Backend,
    };
    PortError::new(kind, error.to_string())
}

fn normalize(path: &Path) -> PathBuf {
    let mut out = PathBuf::new();
    for component in path.components() {
        match component {
            std::path::Component::ParentDir => {
                let _ = out.pop();
            }
            std::path::Component::CurDir => {}
            other => out.push(other.as_os_str()),
        }
    }
    out
}

pub fn install(registry: &ToolRegistry, prompt: &SystemPrompt, fs: Arc<dyn FsPort>) -> Vec<Disposer> {
    install_fs_tools(registry, prompt, fs)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn write_read_edit_roundtrip() {
        let dir = tempfile::tempdir().unwrap();
        let fs = LocalFs::new(dir.path());
        let path = fs.resolve("note.txt").unwrap();
        fs.write_text(&path, "hello world").await.unwrap();
        assert_eq!(fs.read_text(&path).await.unwrap(), "hello world");
        fs.edit_text(&path, "world", "dsh", false).await.unwrap();
        assert_eq!(fs.read_text(&path).await.unwrap(), "hello dsh");
    }

    #[tokio::test]
    async fn rejects_path_escape() {
        let dir = tempfile::tempdir().unwrap();
        let fs = LocalFs::new(dir.path());
        let err = fs.resolve("../secret").unwrap_err();
        assert_eq!(err.kind, PortErrorKind::PermissionDenied);
    }
}
