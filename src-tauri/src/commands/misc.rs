// Copyright (c) 2026 MeeJoy

// Miscellaneous commands
// Handles: cron, file operations, workspace, terminal, model candidates

use std::path::{Path, PathBuf};
use tauri::Manager;

// 文件读写沙盒根目录:只允许访问 app data dir 下的子路径,防止前端
// 通过 read_file_content / write_file_content 读取或写入任意
// 系统文件(C:\Users\xxx\.ssh\id_rsa 等)。
fn sandbox_root(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    app.path()
        .app_data_dir()
        .map_err(|error| format!("无法定位 app data 目录: {}", error))
}

fn ensure_within_sandbox(path: &Path, app: &tauri::AppHandle) -> Result<PathBuf, String> {
    let root = sandbox_root(app)?;
    let canonical_root = std::fs::canonicalize(&root)
        .map_err(|error| format!("沙盒根目录不可访问 {}: {}", root.display(), error))?;
    let canonical = std::fs::canonicalize(path)
        .map_err(|error| format!("路径不可访问 {}: {}", path.display(), error))?;
    if !canonical.starts_with(&canonical_root) {
        return Err(format!(
            "path outside sandbox: {} (root: {})",
            canonical.display(),
            canonical_root.display()
        ));
    }
    Ok(canonical)
}

#[tauri::command]
pub fn read_file_content(
    app: tauri::AppHandle,
    path: String,
) -> Result<String, String> {
    let target = PathBuf::from(&path);
    let safe = ensure_within_sandbox(&target, &app)?;
    // 先用 metadata 检查大小，超过 4MB 直接报错，避免 read_to_string 大文件 OOM
    let metadata = std::fs::metadata(&safe)
        .map_err(|error| format!("Failed to stat file {}: {}", safe.display(), error))?;
    if metadata.len() > 4 * 1024 * 1024 {
        return Err(format!(
            "file too large: {} bytes (max 4MB): {}",
            metadata.len(),
            safe.display()
        ));
    }
    std::fs::read_to_string(&safe)
        .map_err(|error| format!("Failed to read file {}: {}", safe.display(), error))
}

#[tauri::command]
pub fn write_file_content(
    app: tauri::AppHandle,
    path: String,
    content: String,
) -> Result<(), String> {
    let target = PathBuf::from(&path);

    if let Some(parent) = target.parent() {
        let safe_parent = ensure_within_sandbox(parent, &app)?;
        std::fs::create_dir_all(&safe_parent).map_err(|error| {
            format!(
                "Failed to create directory {}: {}",
                safe_parent.display(),
                error
            )
        })?;
    }

    let safe = if target.exists() {
        ensure_within_sandbox(&target, &app)?
    } else {
        let safe_parent = ensure_within_sandbox(target.parent().unwrap_or(Path::new(".")), &app)?;
        let file_name = target.file_name().ok_or_else(|| "invalid file name".to_string())?;
        safe_parent.join(file_name)
    };
    std::fs::write(&safe, content)
        .map_err(|error| format!("Failed to write file {}: {}", safe.display(), error))
}

#[tauri::command]
pub fn create_directory_if_not_exists(
    app: tauri::AppHandle,
    path: String,
) -> Result<(), String> {
    let target = PathBuf::from(&path);
    let safe = ensure_within_sandbox(&target, &app)?;
    if !safe.exists() {
        std::fs::create_dir_all(&safe).map_err(|e| format!("Failed to create directory {}: {}", safe.display(), e))?;
    }
    Ok(())
}

// TODO: Extract full implementations from original commands.rs
// Functions to implement:
// - check_cron_python_dependency, install_cron_python_dependency, restart_hermes_dashboard
// - get_configured_model_candidates
// - list_directory, read_file, get_file_preview, open_file_external, write_file, delete_file, create_directory
// - create_terminal_session, write_terminal_input, resize_terminal_session, close_terminal_session
// - get_workspaces, create_workspace, update_workspace, delete_workspace, set_workspace, get_current_workspace
