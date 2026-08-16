// Copyright (c) 2026 tupAI
//
// IPC surface for the Profile patch layer. Frontend uses these to read the
// active profile (display brand, skill allow/deny lists, config overrides)
// and to switch the active profile at runtime without recompiling.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::PathBuf;
use tauri::AppHandle;

use crate::profile::ProfileStore;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProfileView {
    pub active: String,
    pub display_brand: String,
    pub enabled_skills: Option<Vec<String>>,
    pub disabled_skills: Vec<String>,
    pub config_overrides: HashMap<String, serde_json::Value>,
}

fn app_data_dir(app: &AppHandle) -> Result<PathBuf, String> {
    app.path()
        .app_data_dir()
        .map_err(|e| format!("app_data_dir: {}", e))
}

fn load_store(app: &AppHandle) -> ProfileStore {
    match app_data_dir(app) {
        Ok(dir) => ProfileStore::load(&dir).unwrap_or_else(|_| ProfileStore::builtin_default()),
        Err(_) => ProfileStore::builtin_default(),
    }
}

fn view_of(store: &ProfileStore) -> ProfileView {
    let p = store.active_profile();
    ProfileView {
        active: store.active.clone(),
        display_brand: p.display_brand.clone(),
        enabled_skills: p.enabled_skills.clone(),
        disabled_skills: p.disabled_skills.clone(),
        config_overrides: p.config_overrides.clone(),
    }
}

/// Returns the active profile as seen by the frontend.
#[tauri::command]
pub fn get_profile(app: AppHandle) -> ProfileView {
    view_of(&load_store(&app))
}

/// Switches the active profile and persists it to `profile.json`.
#[tauri::command]
pub fn set_active_profile(app: AppHandle, id: String) -> Result<ProfileView, String> {
    let dir = app_data_dir(&app)?;
    let mut store = load_store(&app);
    if !store.profiles.contains_key(&id) {
        return Err(format!("profile '{}' not found", id));
    }
    store.active = id;
    store.save(&dir)?;
    Ok(view_of(&store))
}

/// Lists all known profile ids (built-in + custom).
#[tauri::command]
pub fn list_profiles(app: AppHandle) -> Vec<String> {
    load_store(&app).profiles.keys().cloned().collect()
}

/// Helper used by other commands to consult the active profile's skill
/// enablement. Exposed for symmetry with the Rust API.
#[allow(dead_code)]
pub fn profile_is_skill_enabled(app: &AppHandle, skill_id: &str, builtin_enabled: bool) -> bool {
    load_store(app).is_skill_enabled(skill_id, builtin_enabled)
}
