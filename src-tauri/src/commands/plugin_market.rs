// Copyright (c) 2026 tupAI
//
// Plugin Market commands — the "everything is a plugin" surface.
//
// Three plugin axes are unified here, all managed through the same seam:
//   1. 全网技能 (network skills)        — reused from the existing market
//                                         backends (see skill_multi_market.rs
//                                         / agent.rs::get_market_skills); this
//                                         module does NOT re-implement them.
//   2. DSH 插件 (DSH / Cordis plugins)  — tracked in the active profile's
//                                         `dsh.plugins` and reflected into the
//                                         connected DSH runtime's config so it
//                                         hot-loads via Cordis reversible
//                                         side-effects. safeopcAPP does NOT
//                                         spawn the dsh process.
//   3. 内置能力 (built-in app plugins)  — cdp / mcp / memory / pc_automation /
//                                         skill / system; toggled per profile.
//
// Every mutation broadcasts `plugins.changed` (see hermes::event_bus::topics)
// and re-seeds the runtime-registry, so an install takes effect immediately
// without restarting the app — the core of the "安装后立即刷新执行机制" ask.

use serde::Deserialize;
use std::path::PathBuf;
use tauri::{AppHandle, Emitter, Manager, State};

use crate::hermes::{event_bus, HermesAppState};
use crate::profile::{DshPluginRef, ProfileStore};
use crate::runtime_registry::registry::RuntimeRegistry;

// ── shared load/save helpers (mirrors commands/dsh.rs) ─────────────────────

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

/// Broadcast that the plugin catalog changed and re-seed the runtime-registry
/// (DSH upstream view) so the change is reflected in the execution engine.
///
/// Two fan-out paths:
///   1. Hermes internal bus (`plugins.changed`) — used by the backend stack
///      (e.g. re-seeding runtime-registry, DSH upstream sync).
///   2. A Tauri web-event (`plugins-changed`) — the WebView has no access to
///      the internal bus, so this is what lets the PluginMarketScene refresh
///      automatically when *anything* mutates the catalog (this scene's own
///      actions, OR an external actor such as the connected DSH runtime hot-
///      loading a Cordis plugin and republishing). `kind` is `"dsh"` or
///      `"builtin"` so the UI can refresh only the affected list.
async fn notify_plugins_changed(
    app: &AppHandle,
    registry: &State<'_, RuntimeRegistry>,
    kind: &str,
) {
    let bus = app.state::<HermesAppState>().bus.clone();
    let _ = bus.publish(event_bus::topics::PLUGINS_CHANGED, serde_json::json!({ "kind": kind })).await;
    // WebView-facing event — the "界面自动根据插件刷新" trigger.
    let _ = app.emit("plugins-changed", serde_json::json!({ "kind": kind }));
    // Keep the DSH upstream view consistent (plugins ride on the same DSH
    // runtime; harmless when there are no upstreams configured).
    let store = load_store(app);
    registry.sync_dsh_upstreams(&store.dsh_upstreams()).await;
}

// ── DSH plugin search (network-wide) ───────────────────────────────────────

/// A repo returned by the GitHub `topic:dsh-plugin` search. camelCase wire
/// shape consumed by the frontend PluginMarketScene.
#[derive(Debug, Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DshPluginSearchItem {
    pub id: String,
    pub repo: String,
    pub name: String,
    pub description: Option<String>,
    pub stars: u64,
    pub url: String,
    pub language: Option<String>,
    pub license: Option<String>,
    pub updated_at: Option<String>,
    /// `github:<owner>/<repo>` install reference surfaced in the UI.
    pub install_ref: String,
}

/// Minimal GitHub search-repositories response shape.
#[derive(Debug, Deserialize)]
struct GithubSearchResponse {
    items: Vec<GithubRepo>,
}

#[derive(Debug, Deserialize)]
struct GithubRepo {
    full_name: String,
    #[serde(default)]
    description: Option<String>,
    #[serde(default)]
    stargazers_count: u64,
    html_url: String,
    #[serde(default)]
    language: Option<String>,
    #[serde(default)]
    license: Option<GithubLicense>,
    #[serde(default)]
    updated_at: Option<String>,
}

#[derive(Debug, Deserialize)]
struct GithubLicense {
    #[serde(default)]
    spdx_id: Option<String>,
}

/// Search the network-wide DSH plugin ecosystem on GitHub (repositories tagged
/// with the `dsh-plugin` topic, sorted by stars). `query` narrows the results.
#[tauri::command]
pub async fn search_dsh_plugins(query: Option<String>) -> Result<Vec<DshPluginSearchItem>, String> {
    let q = match query.as_deref().unwrap_or("").trim() {
        "" => "topic:dsh-plugin".to_string(),
        q => format!("topic:dsh-plugin {}", q),
    };
    let url = format!(
        "https://api.github.com/search/repositories?q={}&sort=stars&order=desc&per_page=30",
        urlencoding(&q)
    );

    let client = reqwest::Client::new();
    let resp = client
        .get(&url)
        .header("User-Agent", "safeopcAPP")
        .header("Accept", "application/vnd.github+json")
        .send()
        .await
        .map_err(|e| format!("GitHub 搜索请求失败: {}", e))?;

    if !resp.status().is_success() {
        return Err(format!("GitHub 搜索返回 {}（可能触发未认证限流）", resp.status()));
    }

    let body: GithubSearchResponse = resp
        .json()
        .await
        .map_err(|e| format!("解析 GitHub 响应失败: {}", e))?;

    let items = body
        .items
        .into_iter()
        .map(|r| {
            let id = r.full_name.replace('/', "-");
            DshPluginSearchItem {
                id: id.clone(),
                repo: r.full_name.clone(),
                name: r.full_name.clone(),
                description: r.description,
                stars: r.stargazers_count,
                url: r.html_url,
                language: r.language,
                license: r.license.and_then(|l| l.spdx_id),
                updated_at: r.updated_at,
                install_ref: format!("github:{}", r.full_name),
            }
        })
        .collect();
    Ok(items)
}

/// Minimal URL-query-component encoder (no external crate dependency).
fn urlencoding(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    for b in input.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(b as char)
            }
            b' ' => out.push('+'),
            _ => out.push_str(&format!("%{:02X}", b)),
        }
    }
    out
}

// ── DSH plugin CRUD (profile-backed) ───────────────────────────────────────

/// Request to install a DSH plugin. `repo` is the GitHub `owner/repo`.
#[derive(Debug, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct DshPluginInstallRequest {
    pub repo: String,
    #[serde(default)]
    pub display_name: Option<String>,
    #[serde(default)]
    pub description: Option<String>,
    #[serde(default)]
    pub stars: Option<u64>,
}

/// List the DSH plugins tracked for the active profile.
#[tauri::command]
pub async fn list_dsh_plugins(app: AppHandle) -> Vec<DshPluginRef> {
    load_store(&app).dsh_plugins()
}

/// Install (track) a DSH plugin for the active profile. Idempotent: re-install
/// just refreshes metadata. Persists, re-seeds the runtime-registry, and
/// broadcasts `plugins.changed`.
#[tauri::command]
pub async fn install_dsh_plugin(
    app: AppHandle,
    registry: State<'_, RuntimeRegistry>,
    request: DshPluginInstallRequest,
) -> Result<Vec<DshPluginRef>, String> {
    let repo = request.repo.trim().to_string();
    if repo.is_empty() || !repo.contains('/') {
        return Err("repo 必须是 GitHub owner/repo 形式".into());
    }
    let id = repo.replace('/', "-");

    let mut store = load_store(&app);
    let mut plugins = store.dsh_plugins();
    match plugins.iter_mut().find(|p| p.id == id) {
        Some(existing) => {
            if request.display_name.is_some() {
                existing.display_name = request.display_name.clone();
            }
            if request.description.is_some() {
                existing.description = request.description.clone();
            }
            if request.stars.is_some() {
                existing.stars = request.stars;
            }
            existing.enabled = true;
        }
        None => {
            plugins.push(DshPluginRef {
                id: id.clone(),
                repo: repo.clone(),
                display_name: request.display_name.clone(),
                description: request.description.clone(),
                stars: request.stars,
                enabled: true,
            });
        }
    }
    store.set_dsh_plugins(plugins.clone());
    save_store(&app, &store)?;
    notify_plugins_changed(&app, &registry, "dsh").await;
    Ok(plugins)
}

/// Remove a tracked DSH plugin by id.
#[tauri::command]
pub async fn remove_dsh_plugin(
    app: AppHandle,
    registry: State<'_, RuntimeRegistry>,
    id: String,
) -> Result<Vec<DshPluginRef>, String> {
    let mut store = load_store(&app);
    let mut plugins = store.dsh_plugins();
    if !plugins.iter().any(|p| p.id == id) {
        return Err(format!("DSH 插件 '{}' 不存在", id));
    }
    plugins.retain(|p| p.id != id);
    store.set_dsh_plugins(plugins.clone());
    save_store(&app, &store)?;
    notify_plugins_changed(&app, &registry, "dsh").await;
    Ok(plugins)
}

/// Toggle a DSH plugin's `enabled` flag.
#[tauri::command]
pub async fn set_dsh_plugin_enabled(
    app: AppHandle,
    registry: State<'_, RuntimeRegistry>,
    id: String,
    enabled: bool,
) -> Result<Vec<DshPluginRef>, String> {
    let mut store = load_store(&app);
    let mut plugins = store.dsh_plugins();
    match plugins.iter_mut().find(|p| p.id == id) {
        Some(p) => p.enabled = enabled,
        None => return Err(format!("DSH 插件 '{}' 不存在", id)),
    }
    store.set_dsh_plugins(plugins.clone());
    save_store(&app, &store)?;
    notify_plugins_changed(&app, &registry, "dsh").await;
    Ok(plugins)
}

// ── Built-in app plugins (everything is a plugin) ──────────────────────────

/// Static registry of built-in app plugins (the Cordis-style plugin set that
/// ships compiled into the binary). `id` matches the module under
/// `src/tauri/src/plugins/`.
const BUILTIN_PLUGINS: &[(&str, &str, &str)] = &[
    ("cdp", "CDP 浏览器自动化", "automation"),
    ("mcp", "MCP 工具协议", "integration"),
    ("memory", "记忆系统", "memory"),
    ("pc_automation", "PC 自动化", "automation"),
    ("skill", "技能引擎", "skill"),
    ("system", "系统命令", "system"),
];

/// A built-in app plugin with its per-profile enable state.
#[derive(Debug, Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BuiltinPluginInfo {
    pub name: String,
    pub description: String,
    pub category: String,
    pub enabled: bool,
}

/// List built-in app plugins and their enable state under the active profile.
#[tauri::command]
pub async fn list_builtin_plugins(app: AppHandle) -> Vec<BuiltinPluginInfo> {
    let store = load_store(&app);
    BUILTIN_PLUGINS
        .iter()
        .map(|(name, desc, cat)| BuiltinPluginInfo {
            name: name.to_string(),
            description: desc.to_string(),
            category: cat.to_string(),
            enabled: store.is_builtin_plugin_enabled(name),
        })
        .collect()
}

/// Enable/disable a built-in app plugin for the active profile. Persists and
/// broadcasts `plugins.changed`. (Actual tool unregistration is a follow-up;
/// the UI reflects state immediately via the broadcast + re-fetch.)
#[tauri::command]
pub async fn set_builtin_plugin_enabled(
    app: AppHandle,
    registry: State<'_, RuntimeRegistry>,
    name: String,
    enabled: bool,
) -> Result<Vec<BuiltinPluginInfo>, String> {
    if !BUILTIN_PLUGINS.iter().any(|(n, _, _)| *n == name) {
        return Err(format!("未知内置插件 '{}'", name));
    }
    let mut store = load_store(&app);
    store.set_builtin_plugin_enabled(&name, enabled);
    save_store(&app, &store)?;
    notify_plugins_changed(&app, &registry, "builtin").await;
    // Reflect the new enable state.
    let store = load_store(&app);
    Ok(BUILTIN_PLUGINS
        .iter()
        .map(|(n, desc, cat)| BuiltinPluginInfo {
            name: n.to_string(),
            description: desc.to_string(),
            category: cat.to_string(),
            enabled: store.is_builtin_plugin_enabled(n),
        })
        .collect())
}
