// Copyright (c) 2026 tupAI
//
// DSH upstream management commands. DSH is an external runtime wired into the
// runtime-registry via the `Upstream` seam (`adapters/upstream.rs`). Its
// config is profile-backed: this module is the single writer of the active
// profile's `dsh.upstreams`, and every mutation re-seeds the runtime-registry
// so the change takes effect immediately (and survives a profile switch).

use serde::Deserialize;
use std::path::PathBuf;
use tauri::{AppHandle, Manager, State};

use crate::profile::{DshUpstreamConfig, ProfileStore};
use crate::runtime_registry::registry::RuntimeRegistry;

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

fn save_store(app: &AppHandle, store: &ProfileStore) -> Result<(), String> {
    let dir = app_data_dir(app)?;
    store.save(&dir)
}

/// Request shape for creating/updating a DSH upstream. Mirrors the fields of
/// `DshUpstreamConfig`; `enabled` defaults to true when omitted.
#[derive(Debug, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct DshUpsertRequest {
    pub id: String,
    pub display_name: String,
    pub endpoint: String,
    #[serde(default)]
    pub cli_args_template: Option<Vec<String>>,
    #[serde(default)]
    pub model: Option<String>,
    #[serde(default)]
    pub api_key: Option<String>,
    #[serde(default)]
    pub enabled: Option<bool>,
}

/// Validate a DSH endpoint the same way the upstream adapter does:
/// http(s) URL or an existing binary path; binary endpoints require a
/// non-empty `cli_args_template`.
fn validate_dsh_endpoint(
    endpoint: &str,
    cli_args_template: &Option<Vec<String>>,
) -> Result<(), String> {
    let e = endpoint.trim();
    let is_http = e.starts_with("http://") || e.starts_with("https://");
    let is_bin = !is_http && !e.is_empty() && std::path::Path::new(e).is_file();
    if !is_http && !is_bin {
        return Err("endpoint 必须是 http(s) URL 或已存在的二进制文件路径".into());
    }
    if !is_http && cli_args_template.as_ref().map_or(true, |t| t.is_empty()) {
        return Err("二进制 endpoint 必须提供非空的 cli_args_template".into());
    }
    Ok(())
}

/// Returns the DSH upstreams of the active profile.
#[tauri::command]
pub async fn dsh_list_upstreams(app: AppHandle) -> Vec<DshUpstreamConfig> {
    load_store(&app).dsh_upstreams()
}

/// Create or update a DSH upstream, persist it to the active profile, and
/// re-seed the runtime-registry. Returns the updated upstream list.
#[tauri::command]
pub async fn dsh_upsert_upstream(
    app: AppHandle,
    registry: State<'_, RuntimeRegistry>,
    request: DshUpsertRequest,
) -> Result<Vec<DshUpstreamConfig>, String> {
    let id = request.id.trim().to_string();
    if id.is_empty() {
        return Err("id 不能为空".into());
    }
    validate_dsh_endpoint(&request.endpoint, &request.cli_args_template)?;

    let mut store = load_store(&app);
    let mut upstreams = store.dsh_upstreams();
    // Preserve the existing API key when the request omits one (edit without
    // touching the secret field); only an explicit Some replaces it.
    let prev_api_key = upstreams
        .iter()
        .find(|u| u.id == id)
        .and_then(|u| u.api_key.clone());
    let api_key = request.api_key.clone().or(prev_api_key);
    let item = DshUpstreamConfig {
        id: id.clone(),
        display_name: request.display_name.trim().to_string(),
        endpoint: request.endpoint.trim().to_string(),
        cli_args_template: request.cli_args_template.clone(),
        model: request.model.clone(),
        api_key,
        enabled: request.enabled.unwrap_or(true),
    };
    if let Some(existing) = upstreams.iter_mut().find(|u| u.id == id) {
        *existing = item;
    } else {
        upstreams.push(item);
    }
    store.set_dsh_upstreams(upstreams.clone());
    save_store(&app, &store)?;
    registry.sync_dsh_upstreams(&upstreams).await;
    Ok(upstreams)
}

/// Remove a DSH upstream by id, persist, and re-seed. Returns the updated list.
#[tauri::command]
pub async fn dsh_remove_upstream(
    app: AppHandle,
    registry: State<'_, RuntimeRegistry>,
    id: String,
) -> Result<Vec<DshUpstreamConfig>, String> {
    let mut store = load_store(&app);
    let mut upstreams = store.dsh_upstreams();
    upstreams.retain(|u| u.id != id);
    store.set_dsh_upstreams(upstreams.clone());
    save_store(&app, &store)?;
    registry.sync_dsh_upstreams(&upstreams).await;
    Ok(upstreams)
}

/// Toggle a DSH upstream's `enabled` flag, persist, and re-seed.
#[tauri::command]
pub async fn dsh_set_upstream_enabled(
    app: AppHandle,
    registry: State<'_, RuntimeRegistry>,
    id: String,
    enabled: bool,
) -> Result<Vec<DshUpstreamConfig>, String> {
    let mut store = load_store(&app);
    let mut upstreams = store.dsh_upstreams();
    match upstreams.iter_mut().find(|u| u.id == id) {
        Some(u) => u.enabled = enabled,
        None => return Err(format!("dsh upstream '{}' 不存在", id)),
    }
    store.set_dsh_upstreams(upstreams.clone());
    save_store(&app, &store)?;
    registry.sync_dsh_upstreams(&upstreams).await;
    Ok(upstreams)
}
