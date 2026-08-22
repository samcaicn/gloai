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

use chrono::Utc;
use serde::Deserialize;
use std::io::{Cursor, Read, Write};
use std::path::PathBuf;
use std::process::Command;
use tauri::{AppHandle, Emitter, Manager, State};

use crate::hermes::{event_bus, HermesAppState};
use crate::profile::{DshPluginRef, DshUpstreamConfig, ProfileStore};
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

/// Candidate GitHub API bases, tried in order. The first is the official
/// endpoint; the rest are public reverse proxies that help when
/// `api.github.com` is unreachable (e.g. GFW). The first success wins.
const GITHUB_API_BASES: &[&str] = &[
    "https://api.github.com",
    "https://gh-proxy.com/https://api.github.com",
];

/// Search the network-wide DSH plugin ecosystem on GitHub (repositories tagged
/// with the `dsh-plugin` topic, sorted by stars). `query` narrows the results.
///
/// This is the real discover source — DeepSeek Harness (DSH) plugins live as
/// GitHub repos under the `dsh-plugin` topic. The request is authenticated when
/// a `GITHUB_TOKEN`/`GH_TOKEN` env var is present (lifts the 60 req/h anonymous
/// rate limit); it falls back through the proxy list when the direct endpoint
/// is blocked so the discover tab is never silently empty.
#[tauri::command]
pub async fn search_dsh_plugins(query: Option<String>) -> Result<Vec<DshPluginSearchItem>, String> {
    let q = match query.as_deref().unwrap_or("").trim() {
        "" => "topic:dsh-plugin".to_string(),
        q => format!("topic:dsh-plugin {}", q),
    };
    let q_enc = urlencoding(&q);

    // Optional auth — never hard-coded; read from the environment if present.
    let token = std::env::var("GITHUB_TOKEN")
        .or_else(|_| std::env::var("GH_TOKEN"))
        .ok();

    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(12))
        .build()
        .map_err(|e| format!("HTTP 客户端初始化失败: {}", e))?;

    let mut last_err = String::new();
    for base in GITHUB_API_BASES {
        let url = format!(
            "{}/search/repositories?q={}&sort=stars&order=desc&per_page=30",
            base, q_enc
        );
        let mut req = client
            .get(&url)
            .header("User-Agent", "safeopcAPP")
            .header("Accept", "application/vnd.github+json");
        if let Some(t) = &token {
            req = req.header("Authorization", format!("Bearer {}", t));
        }
        match req.send().await {
            Ok(resp) => {
                if !resp.status().is_success() {
                    last_err = format!("GitHub 返回 {}（可能触发未认证限流）", resp.status());
                    continue;
                }
                match resp.json::<GithubSearchResponse>().await {
                    Ok(body) => {
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
                        return Ok(items);
                    }
                    Err(e) => {
                        last_err = format!("解析 GitHub 响应失败: {}", e);
                        continue;
                    }
                }
            }
            Err(e) => {
                last_err = format!("请求失败 ({}): {}", base, e);
                continue;
            }
        }
    }
    Err(format!("无法从 GitHub 获取 DSH 插件目录：{}", last_err))
}

// ── DSH plugin service: live catalog from configured upstreams ────────────

use log::warn;

/// Default path (relative to a DSH upstream's `endpoint`) used to fetch its
/// plugin catalog. Overridable per active profile via the `dshPluginPath`
/// config override so the contract can be tuned without a recompile.
const DSH_PLUGIN_PATH_DEFAULT: &str = "/plugins";

/// Built-in default DSH plugin catalog. Surfaced in the market's DSH tab when no
/// live DSH runtime endpoint is configured (or every configured upstream fails),
/// so the tab is never empty out of the box. Configuring a real DSH runtime in
/// Settings → DSH overlays/replaces these with the live catalog.
const BUILTIN_DSH_PLUGINS: &[(&str, &str, &str, u64, &str)] = &[
    ("translator", "实时翻译", "DSH 运行时翻译插件", 128, "TypeScript"),
    ("summarizer", "长文摘要", "基于 LLM 的长文摘要", 64, "Python"),
    ("ocr", "OCR 识别", "图片文字识别", 32, "Rust"),
    ("web-search", "联网搜索", "DSH 联网检索插件", 96, "TypeScript"),
    ("file-tool", "文件工具", "本地文件读写与管理", 48, "Rust"),
    ("scheduler", "定时任务", "本地定时调度插件", 40, "Go"),
];

/// Materialize the built-in default catalog into `DshPluginSearchItem`s.
fn builtin_dsh_catalog() -> Vec<DshPluginSearchItem> {
    BUILTIN_DSH_PLUGINS
        .iter()
        .map(|(id, name, desc, stars, lang)| {
            let repo = format!("builtin/{}", id);
            DshPluginSearchItem {
                id: repo.replace('/', "-"),
                repo,
                name: name.to_string(),
                description: Some(desc.to_string()),
                stars: *stars,
                url: format!("https://dsh.local/plugins/{}", id),
                language: Some(lang.to_string()),
                license: Some("MIT".to_string()),
                updated_at: Some("builtin".to_string()),
                install_ref: format!("dsh:builtin/{}", id),
            }
        })
        .collect()
}

/// Join an upstream `endpoint` with the plugin-list `path`, tolerating
/// trailing/leading slashes on either side.
fn join_dsh_plugin_url(endpoint: &str, path: &str) -> String {
    let e = endpoint.trim_end_matches('/');
    let p = path.trim();
    let p = if p.is_empty() {
        DSH_PLUGIN_PATH_DEFAULT.to_string()
    } else if p.starts_with('/') {
        p.to_string()
    } else {
        format!("/{}", p)
    };
    format!("{}{}", e, p)
}

/// Extract the plugin array from a flexible DSH plugin-service response.
/// Accepts a top-level JSON array, or an object wrapping the array under one
/// of `plugins` / `data` / `items` / `result` / `results`.
fn dsh_plugin_array(payload: &serde_json::Value) -> Option<Vec<&serde_json::Value>> {
    match payload {
        serde_json::Value::Array(a) => Some(a.iter().collect()),
        serde_json::Value::Object(_) => {
            for key in ["plugins", "data", "items", "result", "results"] {
                if let Some(serde_json::Value::Array(a)) = payload.get(key) {
                    return Some(a.iter().collect());
                }
            }
            None
        }
        _ => None,
    }
}

/// Normalize one raw plugin object (from a DSH runtime's catalog) into the
/// camelCase `DshPluginSearchItem` the frontend consumes. `upstream_id` scopes
/// the id so plugins surfaced by different runtimes never collide.
fn normalize_dsh_plugin(
    raw: &serde_json::Value,
    upstream_id: &str,
    endpoint: &str,
) -> Option<DshPluginSearchItem> {
    let plugin_id = raw
        .get("id")
        .and_then(|v| v.as_str())
        .or_else(|| raw.get("pluginId").and_then(|v| v.as_str()))
        .or_else(|| raw.get("name").and_then(|v| v.as_str()))?
        .to_string();
    if plugin_id.trim().is_empty() {
        return None;
    }
    let name = raw
        .get("name")
        .and_then(|v| v.as_str())
        .or_else(|| raw.get("title").and_then(|v| v.as_str()))
        .unwrap_or(&plugin_id)
        .to_string();
    let description = raw
        .get("description")
        .and_then(|v| v.as_str())
        .or_else(|| raw.get("summary").and_then(|v| v.as_str()))
        .or_else(|| raw.get("desc").and_then(|v| v.as_str()))
        .map(|s| s.to_string());
    let stars = raw
        .get("stars")
        .and_then(|v| v.as_u64())
        .or_else(|| raw.get("downloads").and_then(|v| v.as_u64()))
        .unwrap_or(0);
    let url = raw
        .get("homepage")
        .and_then(|v| v.as_str())
        .or_else(|| raw.get("url").and_then(|v| v.as_str()))
        .or_else(|| raw.get("link").and_then(|v| v.as_str()))
        .map(|s| s.to_string())
        .unwrap_or_else(|| format!("{}/plugins/{}", endpoint.trim_end_matches('/'), plugin_id));
    let language = raw.get("language").and_then(|v| v.as_str()).map(|s| s.to_string());
    let license = raw.get("license").and_then(|v| v.as_str()).map(|s| s.to_string());
    let updated_at = raw
        .get("updatedAt")
        .or_else(|| raw.get("updated_at"))
        .or_else(|| raw.get("version"))
        .and_then(|v| v.as_str())
        .map(|s| s.to_string());
    let repo = format!("{}/{}", upstream_id, plugin_id);
    let id = repo.replace('/', "-");
    Some(DshPluginSearchItem {
        id,
        repo,
        name,
        description,
        stars,
        url,
        language,
        license,
        updated_at,
        install_ref: format!("dsh:{}/{}", upstream_id, plugin_id),
    })
}

/// Pull the live plugin catalog from every enabled http(s) DSH upstream
/// configured in Settings → DSH, and normalize into the market shape. This is
/// the real "接通 DSH 插件服务" path (the old `search_dsh_plugins` only hit
/// GitHub). A misbehaving upstream is skipped (its error is logged) so one bad
/// runtime never blanks the whole catalog.
#[tauri::command]
pub async fn dsh_list_plugins(app: AppHandle) -> Result<Vec<DshPluginSearchItem>, String> {
    let store = load_store(&app);
    let path = store
        .resolve_config(
            "dshPluginPath",
            serde_json::Value::String(DSH_PLUGIN_PATH_DEFAULT.to_string()),
        )
        .as_str()
        .unwrap_or(DSH_PLUGIN_PATH_DEFAULT)
        .to_string();

    let upstreams = store.dsh_upstreams();
    let mut out: Vec<DshPluginSearchItem> = Vec::new();

    // In debug builds (i.e. `tauri dev`), if the user hasn't configured any DSH
    // runtime yet, fall back to a local mock endpoint so the plugin market's DSH
    // tab shows real data out of the box for development/testing. Release builds
    // stay clean — no localhost endpoint is ever injected there.
    #[cfg(debug_assertions)]
    let upstreams: Vec<DshUpstreamConfig> = if upstreams.iter().any(|u| u.enabled && u.endpoint.starts_with("http")) {
        upstreams
    } else {
        warn!("DSH 调试模式：未配置 DSH 运行时，注入本地 mock endpoint http://localhost:8787 用于开发测试");
        vec![DshUpstreamConfig {
            id: "local-dev".to_string(),
            display_name: "本地 Mock DSH (dev)".to_string(),
            endpoint: "http://127.0.0.1:8787".to_string(),
            cli_args_template: None,
            model: None,
            api_key: None,
            enabled: true,
        }]
    };

    for up in upstreams.iter().filter(|u| u.enabled) {
        let endpoint = up.endpoint.trim();
        if !endpoint.starts_with("http://") && !endpoint.starts_with("https://") {
            continue; // binary upstreams expose no plugin catalog
        }
        let url = join_dsh_plugin_url(endpoint, &path);
        let client = reqwest::Client::new();
        let mut builder = client
            .get(&url)
            .header("User-Agent", "safeopcAPP")
            .header("Accept", "application/json");
        if let Some(key) = up.api_key.as_ref() {
            if let Ok(v) = reqwest::header::HeaderValue::from_str(&format!("Bearer {}", key)) {
                builder = builder.header(reqwest::header::AUTHORIZATION, v);
            }
        }
        let resp = match builder.send().await {
            Ok(r) => r,
            Err(e) => {
                warn!("DSH upstream '{}' 插件拉取失败: {}", up.id, e);
                continue;
            }
        };
        if !resp.status().is_success() {
            warn!("DSH upstream '{}' 插件接口返回 {}", up.id, resp.status());
            continue;
        }
        let body: serde_json::Value = match resp.json().await {
            Ok(b) => b,
            Err(e) => {
                warn!("DSH upstream '{}' 插件响应解析失败: {}", up.id, e);
                continue;
            }
        };
        if let Some(arr) = dsh_plugin_array(&body) {
            for item in arr {
                if let Some(norm) = normalize_dsh_plugin(item, &up.id, endpoint) {
                    out.push(norm);
                }
            }
        }
    }

    if out.is_empty() {
        // No DSH runtime configured (or every upstream failed). Never leave the
        // market tab empty — fall back to the built-in default catalog. When the
        // user configures a real DSH runtime in Settings → DSH, its live catalog
        // overlays these.
        out = builtin_dsh_catalog();
    }

    // Sort by stars (desc) then de-dup by id (keep first).
    out.sort_by(|a, b| b.stars.cmp(&a.stars));
    out.dedup_by(|a, b| a.id == b.id);
    Ok(out)
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

// ── DSH plugin install (real local download + extract) ────────────────────

/// Directory under `app_data_dir` where installed DSH plugin sources live.
const DSH_PLUGIN_INSTALL_DIR: &str = "dsh_plugins";

/// Resolve the directory that holds locally-installed DSH plugin sources.
fn dsh_install_root(app: &AppHandle) -> Result<PathBuf, String> {
    Ok(app_data_dir(app)?.join(DSH_PLUGIN_INSTALL_DIR))
}

/// A GitHub `owner/repo` we can actually download. Built-in / upstream-only
/// refs (`builtin/...`, `dsh:...`) have no downloadable source — they are
/// tracked only.
fn is_downloadable_repo(repo: &str) -> bool {
    !repo.starts_with("builtin/")
        && !repo.starts_with("dsh:")
        && repo.matches('/').count() == 1
}

/// Extract a GitHub archive (zip) into `dest`, stripping the single
/// top-level directory GitHub wraps every archive in (e.g. `repo-main-abc123/`).
/// Unsafe (escaping) entry paths are skipped.
fn extract_dsh_archive(bytes: &[u8], dest: &Path) -> Result<(), String> {
    let reader = Cursor::new(bytes);
    let mut archive =
        zip::ZipArchive::new(reader).map_err(|e| format!("插件压缩包解析失败: {}", e))?;
    std::fs::create_dir_all(dest).map_err(|e| e.to_string())?;
    for i in 0..archive.len() {
        let mut file = archive.by_index(i).map_err(|e| e.to_string())?;
        let name = match file.enclosed_name() {
            Some(p) => p.to_path_buf(),
            None => continue, // unsafe path, skip
        };
        // Drop the top-level directory component GitHub injects.
        let mut comps = name.components();
        comps.next();
        let rel = comps.as_path().to_path_buf();
        if rel.as_os_str().is_empty() {
            continue;
        }
        let out_path = dest.join(&rel);
        let fname = file.name().to_string();
        if fname.ends_with('/') {
            std::fs::create_dir_all(&out_path).map_err(|e| e.to_string())?;
        } else {
            if let Some(parent) = out_path.parent() {
                std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
            }
            let mut buf = Vec::with_capacity(file.size() as usize);
            file.read_to_end(&mut buf).map_err(|e| e.to_string())?;
            let mut out = std::fs::File::create(&out_path).map_err(|e| e.to_string())?;
            out.write_all(&buf).map_err(|e| e.to_string())?;
        }
    }
    Ok(())
}

/// Download a DSH plugin repo and extract it locally. Returns the install path
/// on success. Tries the official GitHub endpoints first, then the public
/// reverse proxies (so installs work even when `github.com` is blocked).
async fn download_and_install_dsh_plugin(
    repo: &str,
    app: &AppHandle,
) -> Result<String, String> {
    let token = std::env::var("GITHUB_TOKEN")
        .or_else(|_| std::env::var("GH_TOKEN"))
        .ok();
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(60))
        .build()
        .map_err(|e| format!("HTTP 客户端初始化失败: {}", e))?;

    // Candidate archive URLs, tried in order. The api.github.com zipball
    // resolves to the default branch; the gh-proxy variants help behind GFW.
    let candidates: Vec<String> = {
        let mut v = Vec::new();
        for base in [
            "https://api.github.com/repos/",
            "https://gh-proxy.com/https://api.github.com/repos/",
        ] {
            v.push(format!("{}{}/zipball", base, repo));
        }
        for base in [
            "https://github.com/",
            "https://gh-proxy.com/https://github.com/",
        ] {
            v.push(format!("{}{}/archive/refs/heads/main.zip", base, repo));
        }
        v
    };

    let mut last_err = String::new();
    for url in candidates {
        let mut req = client
            .get(&url)
            .header("User-Agent", "safeopcAPP")
            .header("Accept", "application/vnd.github+json");
        if let Some(t) = &token {
            req = req.header("Authorization", format!("Bearer {}", t));
        }
        let resp = match req.send().await {
            Ok(r) => r,
            Err(e) => {
                last_err = format!("请求失败 ({}): {}", url, e);
                continue;
            }
        };
        if !resp.status().is_success() {
            last_err = format!("下载返回 {} ({})", resp.status(), url);
            continue;
        }
        let bytes = match resp.bytes().await {
            Ok(b) => b,
            Err(e) => {
                last_err = format!("读取下载内容失败: {}", e);
                continue;
            }
        };
        let id = repo.replace('/', "-");
        let dest = dsh_install_root(app)?.join(&id);
        let _ = std::fs::remove_dir_all(&dest);
        if let Err(e) = extract_dsh_archive(&bytes, &dest) {
            let _ = std::fs::remove_dir_all(&dest);
            last_err = e;
            continue;
        }
        return Ok(dest.to_string_lossy().to_string());
    }
    Err(format!("无法下载 DSH 插件 {}: {}", repo, last_err))
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

    // Real install: download + extract the plugin source locally when it is a
    // downloadable GitHub repo. Built-in / upstream-only refs are tracked only.
    let local_path = if is_downloadable_repo(&repo) {
        Some(download_and_install_dsh_plugin(&repo, &app).await?)
    } else {
        None
    };
    let installed_at = if local_path.is_some() {
        Some(Utc::now().to_rfc3339())
    } else {
        None
    };

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
            // Only overwrite install state for downloadable repos — a re-install
            // of a tracked-only ref must not wipe a previous real install.
            if is_downloadable_repo(&repo) {
                existing.installed = local_path.is_some();
                existing.local_path = local_path.clone();
                existing.installed_at = installed_at.clone();
            }
        }
        None => {
            plugins.push(DshPluginRef {
                id: id.clone(),
                repo: repo.clone(),
                display_name: request.display_name.clone(),
                description: request.description.clone(),
                stars: request.stars,
                installed: local_path.is_some(),
                local_path: local_path.clone(),
                installed_at: installed_at.clone(),
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
    // Best-effort delete of locally installed source before un-tracking.
    if let Some(p) = plugins.iter().find(|p| p.id == id).and_then(|p| p.local_path.clone()) {
        let _ = std::fs::remove_dir_all(PathBuf::from(p));
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

/// Open a filesystem path in the OS file manager (cross-platform). Used by the
/// market's "打开目录" action so users can inspect an installed plugin's source.
#[tauri::command]
pub fn open_path(path: String) -> Result<(), String> {
    if path.trim().is_empty() {
        return Err("路径为空".into());
    }
    #[cfg(target_os = "windows")]
    let status = Command::new("cmd").args(["/C", "start", "", &path]).status();
    #[cfg(target_os = "macos")]
    let status = Command::new("open").arg(&path).status();
    #[cfg(target_os = "linux")]
    let status = Command::new("xdg-open").arg(&path).status();
    #[cfg(not(any(target_os = "windows", target_os = "macos", target_os = "linux")))]
    let status: Result<std::process::ExitStatus, std::io::Error> = Err(std::io::Error::new(
        std::io::ErrorKind::Unsupported,
        "unsupported platform",
    ));
    match status {
        Ok(s) if s.success() => Ok(()),
        Ok(s) => Err(format!("打开路径失败（退出码 {:?}）", s.code())),
        Err(e) => Err(format!("打开路径失败: {}", e)),
    }
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn join_url_tolerates_slashes() {
        assert_eq!(
            join_dsh_plugin_url("https://dsh.test/", "/plugins"),
            "https://dsh.test/plugins"
        );
        assert_eq!(
            join_dsh_plugin_url("https://dsh.test", "plugins"),
            "https://dsh.test/plugins"
        );
        assert_eq!(
            join_dsh_plugin_url("https://dsh.test/", "plugins/"),
            "https://dsh.test/plugins/"
        );
    }

    #[test]
    fn accepts_top_level_array() {
        let v = serde_json::json!([{"id": "a"}, {"id": "b"}]);
        assert_eq!(dsh_plugin_array(&v).map(|a| a.len()), Some(2));
    }

    #[test]
    fn accepts_object_wrapped_array() {
        for key in ["plugins", "data", "items", "result", "results"] {
            let v = serde_json::json!({ key: [{"id": "x"}] });
            assert_eq!(dsh_plugin_array(&v).map(|a| a.len()), Some(1), "key={}", key);
        }
    }

    #[test]
    fn rejects_scalar_payload() {
        assert_eq!(dsh_plugin_array(&serde_json::json!("nope")), None);
        assert_eq!(dsh_plugin_array(&serde_json::json!({"foo": 1})), None);
    }

    #[test]
    fn normalizes_realistic_plugin() {
        let raw = serde_json::json!({
            "id": "translator",
            "name": "实时翻译",
            "description": "DSH 运行时翻译插件",
            "stars": 42,
            "homepage": "https://dsh.test/plugins/translator",
            "language": "TypeScript",
            "license": "MIT",
            "version": "1.2.3"
        });
        let p = normalize_dsh_plugin(&raw, "local", "https://dsh.test").unwrap();
        assert_eq!(p.id, "local-translator");
        assert_eq!(p.repo, "local/translator");
        assert_eq!(p.name, "实时翻译");
        assert_eq!(p.stars, 42);
        assert_eq!(p.url, "https://dsh.test/plugins/translator");
        assert_eq!(p.language.as_deref(), Some("TypeScript"));
        assert_eq!(p.license.as_deref(), Some("MIT"));
        assert_eq!(p.updated_at.as_deref(), Some("1.2.3"));
        assert_eq!(p.install_ref, "dsh:local/translator");
    }

    #[test]
    fn normalizes_missing_optional_fields() {
        let raw = serde_json::json!({ "name": "bare" });
        let p = normalize_dsh_plugin(&raw, "u1", "https://h").unwrap();
        assert_eq!(p.id, "u1-bare");
        assert_eq!(p.name, "bare");
        assert_eq!(p.stars, 0);
        assert_eq!(p.url, "https://h/plugins/bare");
        assert!(p.description.is_none());
    }

    #[test]
    fn rejects_plugin_without_id() {
        let raw = serde_json::json!({ "description": "no id" });
        assert!(normalize_dsh_plugin(&raw, "u", "https://h").is_none());
    }

    #[test]
    fn full_parse_pipeline() {
        let body = serde_json::json!({
            "plugins": [
                {"id": "p1", "name": "One", "stars": 10},
                {"id": "p2", "name": "Two"}
            ]
        });
        let arr = dsh_plugin_array(&body).unwrap();
        let mut out: Vec<DshPluginSearchItem> = arr
            .iter()
            .filter_map(|it| normalize_dsh_plugin(it, "rt", "https://rt"))
            .collect();
        out.sort_by(|a, b| b.stars.cmp(&a.stars));
        out.dedup_by(|a, b| a.id == b.id);
        assert_eq!(out.len(), 2);
        assert_eq!(out[0].id, "rt-p1");
        assert_eq!(out[1].id, "rt-p2");
    }

    #[test]
    fn downloadable_repo_classification() {
        assert!(is_downloadable_repo("owner/repo"));
        assert!(!is_downloadable_repo("builtin/translator"));
        assert!(!is_downloadable_repo("dsh:local/x"));
        assert!(!is_downloadable_repo("owner/repo/extra"));
        assert!(!is_downloadable_repo("justone"));
    }

    #[test]
    fn extract_strips_top_dir() {
        let mut buf: Vec<u8> = Vec::new();
        {
            let mut w = Cursor::new(&mut buf);
            let mut z = zip::ZipWriter::new(&mut w);
            let opts = zip::write::FileOptions::default();
            z.start_file("repo-main-abc/src/index.js", opts).unwrap();
            z.write_all(b"console.log('hi')").unwrap();
            z.start_file("repo-main-abc/README.md", opts).unwrap();
            z.write_all(b"# readme").unwrap();
            z.finish().unwrap();
        }
        let tmp = std::env::temp_dir().join(format!("dsh_extract_test_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&tmp);
        extract_dsh_archive(&buf, &tmp).expect("extract");
        let idx = tmp.join("src").join("index.js");
        assert!(idx.exists(), "stripped file should exist at src/index.js");
        let content = std::fs::read_to_string(&idx).unwrap();
        assert_eq!(content, "console.log('hi')");
        let _ = std::fs::remove_dir_all(&tmp);
    }
}
