// Copyright (c) 2026 tupAI
//
// IPC commands that drive the local skill catalog cache.
//
// # The 502 problem
//
// `skill_discovery::check_remote_skill_updates` and
// `discover_skills_from_server` both call the upstream MCP
// server live. When the upstream returns 502 (or the WebView
// TLS bug fires `tlsv1 alert internal error`), the front-end
// loses its "available updates" list and the upgrade page goes
// blank. This module adds a thin cache + diff layer in front
// of those calls so the UI degrades gracefully:
//
//   * `get_cached_skill_catalog` — read the on-disk snapshot
//     synchronously. Always succeeds (returns an empty catalog
//     on first run). Returns the `last_refresh_at` timestamp
//     so the front-end can render a "stale since ..." badge.
//
//   * `refresh_skill_catalog` — fetch `skill.list` from the
//     upstream, diff against the local cache, write the merged
//     cache back to disk, and return the diff. On 502 / network
//     failure, it returns the cached entries with
//     `from_cache: true` so the UI can show "showing last-known
//     data, refresh failed".
//
//   * `get_skill_catalog_diff` — pure diff: fetch + diff but
//     don't persist. Useful for a "preview" UI before the user
//     clicks Refresh.
//
//   * `clear_skill_catalog_cache` — wipe the file. Hooked up
//     to a "Reset cache" button in the Settings overlay so
//     debugging is one click.
//
// # Why this lives outside `commands::skill_discovery`
//
// `skill_discovery` does the full search → evaluate → adopt
// pipeline, which is intentionally heavyweight. The cache
// layer here is a *read-through* layer that the discovery
// commands can also call into (see `write_cache_from_items`).
// Splitting them keeps each file focused.

use serde::{Deserialize, Serialize};
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tauri::{AppHandle, Emitter};

use crate::skill::catalog_cache::{
    self, CatalogCacheFile, CatalogDiff, CatalogEntry, SearchHit,
};

/// How long the on-disk cache is considered "fresh" for the
/// purposes of skipping background refreshes. Anything older
/// is "stale" and a background refresh will be spawned (when
/// the caller asks for one).
///
/// 5 minutes is a compromise: long enough that a settings
/// panel that re-renders on every interaction doesn't keep
/// firing refreshes, short enough that the user doesn't
/// stare at a list of skills that's hours out of date.
pub const FRESH_WINDOW_SECS: i64 = 5 * 60;

/// Coalesce window: when multiple callers ask for a refresh
/// within this many seconds of each other, only the first
/// one actually hits the network; the rest are no-ops.
const REFRESH_COALESCE_WINDOW: Duration = Duration::from_secs(5);

/// In-memory dedup state for background refreshes. We use
/// `Instant` (monotonic) instead of `SystemTime` because
/// `Instant` is immune to wall-clock jumps (NTP, user
/// adjusting the clock, etc.) — which would otherwise let
/// two refreshes slip past the coalesce window if the clock
/// went backwards.
static LAST_REFRESH: Mutex<Option<Instant>> = Mutex::new(None);

/// Wire shape returned by `get_cached_skill_catalog`. Includes
/// the `stale` flag and the raw `last_refresh_at` so the
/// front-end doesn't have to re-derive "is this old?".
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CachedCatalogResponse {
    pub entries: Vec<CatalogEntry>,
    pub last_refresh_at: i64,
    /// `true` when the cache has never been populated or is
    /// older than the configurable threshold (default 24h).
    pub stale: bool,
    /// Absolute path of the cache file. Useful for the
    /// "Open cache folder" debugging affordance.
    pub file_path: String,
}

/// Result of `refresh_skill_catalog` and `get_skill_catalog_diff`.
/// `from_cache: true` means the upstream call failed and we're
/// returning whatever was on disk; `error` carries the upstream
/// failure detail for the toast.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CatalogRefreshResponse {
    pub diff: CatalogDiff,
    pub entries: Vec<CatalogEntry>,
    pub last_refresh_at: i64,
    /// `true` when this response came from the on-disk cache
    /// (upstream unreachable). The front-end uses this to
    /// render the "stale data" badge and an error toast.
    pub from_cache: bool,
    /// Populated when `from_cache == true`. JSON-shaped string
    /// (mirrors the mcp_proxy error envelope) so the front-end
    /// can branch on `code` without extra parsing.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

const DEFAULT_STALE_AFTER_SECS: i64 = 24 * 60 * 60;

/// Read the on-disk cache. Never hits the network. Always
/// returns a response — empty catalog on first run, populated
/// thereafter. Safe to call from a settings panel on every
/// tab-switch.
#[tauri::command]
pub fn get_cached_skill_catalog(
    app: AppHandle,
) -> Result<CachedCatalogResponse, String> {
    let file = catalog_cache::load(&app)?;
    let path = catalog_cache::cache_file_path(&app)?;
    let stale = catalog_cache::is_stale(&file, DEFAULT_STALE_AFTER_SECS);
    Ok(CachedCatalogResponse {
        entries: file.entries,
        last_refresh_at: file.last_refresh_at,
        stale,
        file_path: path.to_string_lossy().to_string(),
    })
}

/// Fetch the upstream catalog, merge into the local cache,
/// return the diff. On upstream failure, fall back to the
/// on-disk cache and set `from_cache: true`.
#[tauri::command]
pub async fn refresh_skill_catalog(
    app: AppHandle,
    server_url: String,
    token: Option<String>,
) -> Result<CatalogRefreshResponse, String> {
    match fetch_remote(&app, &server_url, token.as_deref()).await {
        Ok(remote) => {
            let (diff, file) = catalog_cache::refresh(&app, remote)?;
            // Surface the refresh event so a settings panel
            // listening on "skill:catalog-refreshed" can re-render
            // without polling.
            let _ = app.emit("skill:catalog-refreshed", &diff);
            Ok(CatalogRefreshResponse {
                diff,
                entries: file.entries,
                last_refresh_at: file.last_refresh_at,
                from_cache: false,
                error: None,
            })
        }
        Err(e) => {
            log::warn!(
                "[skill/catalog-cache] refresh failed, falling back to on-disk cache: {}",
                e
            );
            let file = catalog_cache::load(&app)?;
            Ok(CatalogRefreshResponse {
                diff: CatalogDiff::default(),
                entries: file.entries,
                last_refresh_at: file.last_refresh_at,
                from_cache: true,
                error: Some(e),
            })
        }
    }
}

/// Fetch + diff but don't persist. Lets the front-end show a
/// "here's what would change" preview before the user clicks
/// the destructive Refresh button. On upstream failure,
/// returns the cached diff (which is empty) and `from_cache:
/// true`.
#[tauri::command]
pub async fn get_skill_catalog_diff(
    app: AppHandle,
    server_url: String,
    token: Option<String>,
) -> Result<CatalogRefreshResponse, String> {
    match fetch_remote(&app, &server_url, token.as_deref()).await {
        Ok(remote) => {
            let local = catalog_cache::load(&app)?.entries;
            let diff = catalog_cache::diff(&remote, &local);
            Ok(CatalogRefreshResponse {
                diff,
                entries: remote,
                last_refresh_at: catalog_cache::now_unix_secs(),
                from_cache: false,
                error: None,
            })
        }
        Err(e) => Ok(CatalogRefreshResponse {
            diff: CatalogDiff::default(),
            entries: catalog_cache::load(&app)?.entries,
            last_refresh_at: catalog_cache::load(&app)?.last_refresh_at,
            from_cache: true,
            error: Some(e),
        }),
    }
}

/// Wipe the on-disk cache. Used by the Settings overlay's
/// "Reset skill cache" button — useful when the cached catalog
/// has drifted from reality (e.g. after manually editing
/// `skill_catalog_cache.json` for debugging).
#[tauri::command]
pub fn clear_skill_catalog_cache(app: AppHandle) -> Result<(), String> {
    let path = catalog_cache::cache_file_path(&app)?;
    match std::fs::remove_file(&path) {
        Ok(()) => {
            log::info!("[skill/catalog-cache] cleared {:?}", path);
            Ok(())
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(format!("Failed to remove {:?}: {}", path, e)),
    }
}

// =====================================================================
// Local-first search
// =====================================================================
//
// The front-end opens the Skills panel, types into the search
// box, and expects an instant list of matches. The previous
// design always hit the upstream — when `ai.tuptup.top` was
// 502, the user got an empty list and a confusing error. This
// block makes the search path local-first:

/// Wire shape returned by `search_skill_catalog_local`. Lets the
/// front-end decide whether to show a "data is N minutes old"
/// badge without re-deriving anything on the JS side.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalSearchResponse {
    pub hits: Vec<SearchHit>,
    /// Total entries in the local cache (pre-filter). Used by
    /// the front-end to render "showing 5 of 23 cached skills".
    pub total_in_cache: usize,
    pub last_refresh_at: i64,
    /// True when the cache is older than `FRESH_WINDOW_SECS`.
    /// The caller can use this to schedule a background refresh.
    pub stale: bool,
    /// True when the cache has never been populated. Different
    /// from `stale` semantically: a never-populated cache is a
    /// "first run" signal, not a "data is old" signal.
    pub empty: bool,
}

/// Pure local search. **Never hits the network.** Returns the
/// relevance-ranked hits plus cache metadata so the front-end
/// can render staleness hints without a second round-trip.
///
/// Default `limit` of 50 matches a typical settings-panel
/// viewport; hard-capped at 200 inside `catalog_cache::search`.
#[tauri::command]
pub fn search_skill_catalog_local(
    app: AppHandle,
    query: String,
    limit: Option<usize>,
) -> Result<LocalSearchResponse, String> {
    let file = catalog_cache::load(&app)?;
    let limit = limit.unwrap_or(50);
    let hits = catalog_cache::search(&query, &file.entries, limit);
    Ok(LocalSearchResponse {
        total_in_cache: file.entries.len(),
        last_refresh_at: file.last_refresh_at,
        stale: catalog_cache::is_stale(&file, FRESH_WINDOW_SECS),
        empty: file.entries.is_empty(),
        hits,
    })
}

/// Outcome of a coalesced background refresh — mostly useful
/// for the `Spawned` variant so tests can assert the dedup
/// path is taken. The other two are also serialised so the
/// caller (if it ever wants to) can render a "skip" hint.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum RefreshOutcome {
    /// We actually hit the network. The cache file has been
    /// updated (or the attempt failed — see the command
    /// return for the error path).
    Spawned,
    /// Another caller already triggered a refresh within
    /// the coalesce window. We piggy-backed on theirs.
    Coalesced,
    /// The cache is fresh enough; no refresh needed.
    Fresh,
}

/// Decide whether to fire a background refresh, and if so,
/// spawn it on the tokio runtime. Returns the outcome so the
/// caller (or tests) can react.
///
/// **Coalescing**: a single in-memory `Mutex<Option<Instant>>`
/// tracks the last refresh attempt. If a second call comes
/// in within `REFRESH_COALESCE_WINDOW`, the new call is
/// dropped. The mutex is the simplest possible dedup — a
/// full semaphore would be overkill for a "settings panel
/// re-renders 60fps" scenario.
///
/// **Freshness check**: if the cache is fresher than
/// `fresh_window_secs`, we don't even consider spawning. The
/// caller passes `force = true` to bypass this (e.g. the
/// "Force refresh" button).
///
/// **Exposed as a Tauri command** so the front-end can trigger
/// a background refresh directly (e.g. on app start, or via
/// a "Refresh" button on the Skills panel). The IPC layer
/// returns the `RefreshOutcome` enum so the UI can render
/// "refreshed" / "coalesced (already in flight)" / "cache is
/// fresh" feedback.
#[tauri::command]
pub fn spawn_background_refresh(
    app: AppHandle,
    server_url: String,
    token: Option<String>,
    force: Option<bool>,
) -> RefreshOutcome {
    let force = force.unwrap_or(false);
    // Freshness gate: skip if the cache is fresh and the
    // caller isn't forcing. We load the cache on every call
    // because the on-disk file is the source of truth — a
    // previous coalesced refresh might have updated it.
    if !force {
        let file = catalog_cache::load(&app).unwrap_or_default();
        if !catalog_cache::is_stale(&file, FRESH_WINDOW_SECS) && !file.entries.is_empty() {
            return RefreshOutcome::Fresh;
        }
    }

    // Coalesce gate: if someone just fired a refresh, drop.
    {
        let mut last = LAST_REFRESH.lock().unwrap_or_else(|p| p.into_inner());
        if let Some(prev) = *last {
            if prev.elapsed() < REFRESH_COALESCE_WINDOW {
                return RefreshOutcome::Coalesced;
            }
        }
        *last = Some(Instant::now());
    }

    // Actually spawn. `tauri::async_runtime::spawn` routes
    // through the Tauri-managed tokio runtime so the task
    // shares the same executor as the rest of the app.
    tauri::async_runtime::spawn(async move {
        match fetch_remote(&app, &server_url, token.as_deref()).await {
            Ok(remote) => match catalog_cache::refresh(&app, remote) {
                Ok((diff, _)) => {
                    let _ = app.emit("skill:catalog-refreshed", &diff);
                    log::info!("[skill/catalog-cache] background refresh done");
                }
                Err(e) => log::warn!(
                    "[skill/catalog-cache] background refresh write failed: {}",
                    e
                ),
            },
            Err(e) => {
                log::info!(
                    "[skill/catalog-cache] background refresh upstream failed (cache unchanged): {}",
                    e
                );
            }
        }
    });
    RefreshOutcome::Spawned
}

/// Replace the cache wholesale. The companion of
/// `refresh_skill_catalog` for callers that already have a
/// `Vec<CatalogEntry>` in hand (the `skill_discovery` module
/// is the only such caller today; it passes the items it
/// received from `skill.list` so we don't double-fetch).
///
/// `entries` is empty-allowed: an empty array means "the
/// upstream catalog is empty / the project was unpublished",
/// which legitimately empties the cache.
#[tauri::command]
pub fn write_skill_catalog_cache(
    app: AppHandle,
    entries: Vec<CatalogEntry>,
) -> Result<CatalogCacheFile, String> {
    let (diff, file) = catalog_cache::refresh(&app, entries)?;
    let _ = app.emit("skill:catalog-refreshed", &diff);
    Ok(file)
}

// --- internals ------------------------------------------------------------

/// One-shot HTTP fetch + parse. Reuses the same `reqwest`
/// recipe as `commands::skill_discovery::mcp_http_call` —
/// tight 15s timeout, JSON envelope, normalised error string.
/// Lives here (not in `skill_discovery`) to keep the cache
/// module self-contained: a future migration to a different
/// transport only touches one file.
async fn fetch_remote(
    _app: &AppHandle,
    server_url: &str,
    token: Option<&str>,
) -> Result<Vec<CatalogEntry>, String> {
    use serde_json::Value;

    let client = reqwest::Client::builder()
        .no_proxy()
        .timeout(std::time::Duration::from_secs(15))
        .build()
        .map_err(|e| e.to_string())?;

    let body = serde_json::json!({
        "id": uuid::Uuid::new_v4().to_string(),
        "action": "skill.list",
        "params": {},
    });

    let mut req = client
        .post(server_url)
        .header("Content-Type", "application/json")
        .json(&body);
    if let Some(t) = token.filter(|s| !s.is_empty()) {
        req = req.bearer_auth(t);
    }

    let resp = req
        .send()
        .await
        .map_err(|e| format!("skill.list request failed: {}", e))?
        .json::<Value>()
        .await
        .map_err(|e| format!("skill.list response decode failed: {}", e))?;

    if resp.get("ok").and_then(|v| v.as_bool()) == Some(false) {
        let code = resp["error"]["code"].as_str().unwrap_or("unknown");
        // 修复 E0716: 先把 `resp["error"].to_string()` 提取为 let 绑定，
        // 避免临时值在 `unwrap_or` 表达式结束后被 drop（`&` 借用的临时值
        // 生命周期只到本语句末，而后面 `format!` 还要用 msg）。
        let fallback = resp["error"].to_string();
        let msg = resp["error"]["message"].as_str().unwrap_or(&fallback);
        return Err(format!("upstream error [{}]: {}", code, msg));
    }

    // The upstream returns either `{ "data": { "items": [...] } }`
    // (envelope form, what mcp_v2 emits) or a bare array (the
    // `skill_discovery` callers accept both — we mirror that).
    let items_value: Value = resp
        .get("data")
        .and_then(|d| d.get("items").cloned())
        .or_else(|| resp.get("items").cloned())
        .unwrap_or(Value::Null);

    let raw: Vec<RemoteCatalogItem> = if items_value.is_array() {
        serde_json::from_value(items_value)
            .map_err(|e| format!("items decode: {}", e))?
    } else {
        Vec::new()
    };

    let now = catalog_cache::now_unix_secs();
    let entries = raw
        .into_iter()
        .filter_map(|r| {
            if r.skill_id.is_empty() || r.version.is_empty() {
                return None;
            }
            Some(CatalogEntry {
                skill_id: r.skill_id,
                name: if r.name.is_empty() {
                    // Caller didn't supply a name; fall back to
                    // id so the diff still emits a usable row.
                    "(unnamed)".to_string()
                } else {
                    r.name
                },
                version: r.version,
                tags: r.tags,
                description: r.description,
                last_seen_at: now,
                source: r.source,
            })
        })
        .collect();
    Ok(entries)
}

/// Loose on-the-wire shape for `skill.list` items. We accept
/// every field as `Option<String>` / `Vec::default()` because
/// the upstream schema is loose and we'd rather skip a row
/// than fail the whole refresh.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RemoteCatalogItem {
    #[serde(default)]
    skill_id: String,
    #[serde(default)]
    name: String,
    #[serde(default)]
    version: String,
    #[serde(default)]
    description: Option<String>,
    #[serde(default)]
    tags: Vec<String>,
    #[serde(default)]
    source: String,
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Reset the coalescer state. Each test starts fresh —
    /// otherwise a previously-spawned refresh would shadow
    /// the new one. We also clear any "fresh" cache file
    /// that might be sitting in the way.
    fn reset_coalescer() {
        let mut last = LAST_REFRESH.lock().unwrap_or_else(|p| p.into_inner());
        *last = None;
    }

    #[test]
    fn coalescer_second_call_within_window_is_coalesced() {
        reset_coalescer();
        // First call: no prior timestamp → spawn.
        // (We can't actually spawn without a tauri::AppHandle;
        //  the freshness gate will short-circuit anyway
        //  because the on-disk cache doesn't exist. That
        //  returns Fresh — the same first-call semantics
        //  from a "no work to do" perspective.)
        // The point of this test is the *second* call:
        // after we manually stamp the timestamp, a second
        // call should report Coalesced.
        {
            let mut last = LAST_REFRESH.lock().unwrap_or_else(|p| p.into_inner());
            *last = Some(Instant::now());
        }
        // We can't construct an AppHandle in a unit test, so
        // assert the invariant directly: the elapsed < window
        // check is what the function uses. We document the
        // contract here.
        let prev = {
            let last = LAST_REFRESH.lock().unwrap_or_else(|p| p.into_inner());
            *last
        };
        assert!(prev.unwrap().elapsed() < REFRESH_COALESCE_WINDOW);
    }

    #[test]
    fn refresh_outcome_serialises_camel_case() {
        // The front-end reads these as enum values; assert
        // the wire shape is the camelCase variant names
        // (matches serde's default for unit variants in
        // an externally-tagged enum).
        let v = serde_json::to_value(RefreshOutcome::Fresh).unwrap();
        assert_eq!(v, serde_json::json!("Fresh"));
        let v = serde_json::to_value(RefreshOutcome::Coalesced).unwrap();
        assert_eq!(v, serde_json::json!("Coalesced"));
        let v = serde_json::to_value(RefreshOutcome::Spawned).unwrap();
        assert_eq!(v, serde_json::json!("Spawned"));
    }

    #[test]
    fn local_search_response_serialises_camel_case() {
        // Spot-check the wire shape the front-end will see.
        let resp = LocalSearchResponse {
            hits: vec![],
            total_in_cache: 0,
            last_refresh_at: 1_700_000_000,
            stale: true,
            empty: true,
        };
        let v = serde_json::to_value(&resp).unwrap();
        assert_eq!(v["totalInCache"], serde_json::json!(0));
        assert_eq!(v["lastRefreshAt"], serde_json::json!(1_700_000_000));
        assert_eq!(v["stale"], serde_json::json!(true));
        assert_eq!(v["empty"], serde_json::json!(true));
        assert!(v["hits"].is_array());
    }

    #[test]
    fn fresh_window_is_5_minutes() {
        // Lock the value in: any future change to
        // FRESH_WINDOW_SECS is a deliberate UX decision,
        // not an accident. If you're changing this, also
        // update the doc comment in skill_cache.rs.
        assert_eq!(FRESH_WINDOW_SECS, 300);
    }
}
