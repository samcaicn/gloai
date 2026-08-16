// Copyright (c) 2026 MeeJoy

// Encryption-related Tauri commands.
//
// The "data safety" UX in SettingsModal calls into this module:
//   * `wipe_all_local_data`  — nuke the `skill/` directory and the
//     model-path config (irreversible, double-confirm recommended)
//   * `encrypt_data` / `decrypt_data` — symmetric round-trip using a
//     short-lived password supplied by the UI. The key never leaves
//     the function call.
//
// We do *not* persist the password. The key is derived on every call
// from `(password, hardware_fingerprint)`, and the fingerprint is read
// from the live hardware probe so the operation works even on a fresh
// install (where no config file exists yet).

use crate::commands::hardware::compute_hardware_fingerprint;
use crate::crypto::storage::EncryptedStorage;
use std::fs;
use std::path::Path;
use tauri::Manager;

/// Best-effort "nuke it" command. Removes every file under
/// `<app_data>/skill/`, the model path config file, and the persisted
/// hardware version file. Returns the number of files that were
/// removed. The encrypted payloads cannot be recovered — this is the
/// data-safety reset.
#[tauri::command]
pub fn wipe_all_local_data(app: tauri::AppHandle) -> Result<usize, String> {
    let app_data_dir = app
        .path()
        .app_data_dir()
        .map_err(|e: tauri::Error| e.to_string())?;
    let mut removed = 0usize;

    let skill_dir = app_data_dir.join("skill");
    removed += EncryptedStorage::wipe_directory(&skill_dir).map_err(|e| e.to_string())?;
    let _ = fs::remove_dir(&skill_dir);

    // The models config is a single JSON file; just delete it.
    let models_dir = app_data_dir.join("models");
    let active_path_file = models_dir.join("active_path.json");
    if active_path_file.exists()
        && fs::remove_file(&active_path_file).is_ok() {
            removed += 1;
        }

    // Hardware version selection is regenerated on the next detect
    // call, so remove it too — that way the user gets a clean
    // "fresh-install" experience.
    let hardware_version_file = app_data_dir.join("hardware_version.json");
    if hardware_version_file.exists()
        && fs::remove_file(&hardware_version_file).is_ok() {
            removed += 1;
        }

    Ok(removed)
}

/// Encrypt `plaintext` (UTF-8) and return a base64-framed ciphertext.
/// The key is derived from `password` + the live hardware fingerprint.
#[tauri::command]
pub fn encrypt_data(plaintext: String, password: String) -> Result<String, String> {
    let storage = build_storage(&password)?;
    storage
        .encrypt_base64(plaintext.as_bytes())
        .map_err(|e| e.to_string())
}

/// Decrypt a base64-framed ciphertext and return the UTF-8 plaintext.
/// Returns an error if the password is wrong, the fingerprint changed,
/// or the ciphertext is malformed.
#[tauri::command]
pub fn decrypt_data(ciphertext: String, password: String) -> Result<String, String> {
    let storage = build_storage(&password)?;
    let plaintext = storage
        .decrypt_base64_string(&ciphertext)
        .map_err(|e| e.to_string())?;
    Ok(plaintext.to_string())
}

fn build_storage(password: &str) -> Result<EncryptedStorage, String> {
    if password.is_empty() {
        return Err("密码不能为空".to_string());
    }
    let fingerprint = compute_hardware_fingerprint();
    EncryptedStorage::derive(password, &fingerprint).map_err(|e| e.to_string())
}

/// Helper: list the directory the wipe command targets. Exposed so the
/// UI can show a preview ("你将删除 17 个文件…").
#[allow(dead_code)]
// Reserved for the SettingsModal "preview wipe" affordance; the
// Tauri command registration is the main thread's reserved action.
pub fn preview_wipe(app_data_dir: &Path) -> usize {
    let mut total = 0usize;
    let skill_dir = app_data_dir.join("skill");
    if let Ok(entries) = fs::read_dir(&skill_dir) {
        for entry in entries.flatten() {
            if let Ok(file_type) = entry.file_type() {
                if file_type.is_file() {
                    total += 1;
                } else if file_type.is_dir() {
                    total += count_recursive(&entry.path());
                }
            }
        }
    }
    total
}

#[allow(dead_code)]
// Recursion helper for `preview_wipe`; the lint cascades from the
// staged `preview_wipe` so we silence it here.
fn count_recursive(dir: &std::path::Path) -> usize {
    let mut count = 0;
    if let Ok(entries) = fs::read_dir(dir) {
        for entry in entries.flatten() {
            if let Ok(file_type) = entry.file_type() {
                if file_type.is_file() {
                    count += 1;
                } else if file_type.is_dir() {
                    count += count_recursive(&entry.path());
                }
            }
        }
    }
    count
}
