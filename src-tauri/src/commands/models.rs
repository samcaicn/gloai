// Copyright (c) 2026 MeeJoy
//
// Model-path Tauri commands.
//
// The Settings UI talks to three commands:
//   * `change_model_path` — point the manager at a new directory and
//     migrate any existing `.gguf`/`.bin`/… files
//   * `scan_models`        — list the active directory
//   * `delete_model`       — remove a single file (with safety checks)
//
// All commands go through a `ModelManager` rooted at
// `<app_data>/models`, which is the canonical place the rest of the
// app (and the dashboard) reads from.

use crate::models::manager::{ModelEntry, ModelManager};
use std::path::PathBuf;
use tauri::Manager;

fn manager(app: &tauri::AppHandle) -> Result<ModelManager, String> {
    let app_data_dir = app
        .path()
        .app_data_dir()
        .map_err(|e: tauri::Error| e.to_string())?;
    ModelManager::new(&app_data_dir).map_err(|e| e.to_string())
}

/// Switch the active model directory. Any existing model files in the
/// old directory are moved into the new one (cross-device copy+remove
/// fallback). Returns a human-readable status string.
#[tauri::command]
pub fn change_model_path(
    app: tauri::AppHandle,
    new_path: String,
) -> Result<String, String> {
    let manager = manager(&app)?;
    manager.change_model_path(&new_path).map_err(|e| e.to_string())
}

/// Scan the active model directory and return one entry per recognized
/// file (gguf / bin / safetensors / pt / ggml).
#[tauri::command]
pub fn scan_models(app: tauri::AppHandle) -> Result<Vec<ModelEntry>, String> {
    let manager = manager(&app)?;
    manager.scan_models().map_err(|e| e.to_string())
}

/// Delete a single model file. The path must live inside the active
/// model directory and have a recognized extension.
#[tauri::command]
pub fn delete_model(
    app: tauri::AppHandle,
    path: String,
) -> Result<(), String> {
    let manager = manager(&app)?;
    manager.delete_model(&path).map_err(|e| e.to_string())
}

/// Return the currently-active model directory as a plain string.
/// Used by the UI to render the current location in the Settings tab.
#[allow(dead_code)]
// Reserved for the SettingsModal model-path display; the
// `invoke_handler!` registration in `lib.rs` is the main thread's
// reserved action.
pub fn get_active_model_path(app: &tauri::AppHandle) -> Result<String, String> {
    let manager = manager(app)?;
    manager.active_dir_string().map_err(|e| e.to_string())
}

/// Helper used by the UI to validate a candidate path before
/// committing to the move.
#[allow(dead_code)]
// Reserved for the SettingsModal "browse model path" form; the
// `invoke_handler!` registration in `lib.rs` is the main thread's
// reserved action.
pub fn validate_model_path(path: String) -> Result<bool, String> {
    if path.trim().is_empty() {
        return Ok(false);
    }
    let path = PathBuf::from(&path);
    // We don't require the path to exist — the UI lets users pick a
    // brand-new location. We just check that it looks like a valid
    // absolute or relative path.
    if path.as_os_str().is_empty() {
        return Ok(false);
    }
    Ok(true)
}

/// Verify a model file's SHA-256. Exposed so the UI can run an
/// "integrity check" action without scanning the whole directory.
#[allow(dead_code)]
// Reserved for the SettingsModal integrity-check action; the
// `invoke_handler!` registration in `lib.rs` is the main thread's
// reserved action.
pub fn verify_model_integrity(
    path: String,
    expected_sha256: String,
) -> Result<bool, String> {
    Ok(ModelManager::verify_model_integrity(&path, &expected_sha256))
}
