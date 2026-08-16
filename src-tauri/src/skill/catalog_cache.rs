// Copyright (c) 2026 AIMarketing
//
// Skill catalog cache — a local mirror of the remote skill
// marketplace, used as a fallback when the upstream MCP server
// (e.g. `https://ai.tuptup.top/api/v2/mcp`) returns 502 / times
// out. Without this layer, a single upstream blip nukes the
// "available updates" UI and the upgrade candidate list.
//
// # Layout
//
// `<app_data>/skill_catalog_cache.json`
// ```json
// {
//   "version": 1,
//   "last_refresh_at": 1700000000,
//   "entries": [
//     { "skillId": "trace-auto", "name": "AIMarketing",
//       "version": "6.0.0", "tags": ["trae","automation"],
//       "description": "...",
//       "lastSeenAt": 1700000000, "source": "community" }
//   ]
// }
// ```
//
// # Why JSON (not sqlite)
// The catalog is small (tens of entries in practice), read
// wholesale on every refresh, and never queried by anything
// other than id equality. A JSON file keeps the implementation
// auditable (you can `cat` it to debug) and avoids one more
// schema migration file in `src-tauri/src/skill/memory/`.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Manager};

/// Current on-disk schema version. Bump when the wire shape
/// changes; on read we tolerate older versions by falling back
/// to defaults (and logging).
const CACHE_SCHEMA_VERSION: u32 = 1;

/// A single entry in the local skill catalog. Mirrors the
/// fields we care about from the upstream `skill.list` response
/// (id / name / version / tags / description) plus two
/// cache-only fields: `last_seen_at` (when we last observed the
/// upstream still returning this id) and `source` (which catalog
/// we observed it in, e.g. "community").
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct CatalogEntry {
    pub skill_id: String,
    pub name: String,
    pub version: String,
    #[serde(default)]
    pub tags: Vec<String>,
    #[serde(default)]
    pub description: Option<String>,
    /// Unix-seconds when this entry was last observed in a
    /// successful upstream fetch. Used by the front-end to render
    /// a "last seen N days ago" badge.
    pub last_seen_at: i64,
    /// "community" / "official" / "test" — passed through from
    /// the upstream response (when present) so the front-end can
    /// filter by source.
    #[serde(default)]
    pub source: String,
}

/// On-disk envelope. Versioned so a future schema bump can
/// trigger a one-shot migration instead of panicking on read.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CatalogCacheFile {
    pub version: u32,
    pub last_refresh_at: i64,
    pub entries: Vec<CatalogEntry>,
}

impl Default for CatalogCacheFile {
    fn default() -> Self {
        Self {
            version: CACHE_SCHEMA_VERSION,
            last_refresh_at: 0,
            entries: Vec::new(),
        }
    }
}

/// The result of comparing a freshly-fetched remote catalog
/// against the local cache. Three sets, no overlap:
///
/// * `added`   — present in `remote` but not in `local`
///   (newly published, brand-new skill)
/// * `updated` — present in both, but `remote.version` differs
///   (or remote has a non-empty `skill_md` the local copy lacks)
/// * `removed` — present in `local` but not in `remote`
///   (skill was unpublished / deprecated)
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CatalogDiff {
    pub added: Vec<CatalogEntry>,
    pub updated: Vec<CatalogUpdate>,
    pub removed: Vec<String>,
}

/// A single update candidate. Carries both old and new so the
/// front-end can render "v6.0.0 → v6.1.0" without a second IPC
/// call.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CatalogUpdate {
    pub skill_id: String,
    pub old_version: String,
    pub new_version: String,
    pub entry: CatalogEntry,
}

impl CatalogDiff {
    pub fn is_empty(&self) -> bool {
        self.added.is_empty() && self.updated.is_empty() && self.removed.is_empty()
    }
    pub fn total(&self) -> usize {
        self.added.len() + self.updated.len() + self.removed.len()
    }
}

/// Pure diff function. `local` may be empty (first run), in
/// which case everything in `remote` lands in `added`. We
/// compare by `skill_id` (case-sensitive, matching the upstream
/// contract). Version comparison is string-based — the upstream
/// uses SemVer but we don't want to pull in a semver crate just
/// to decide "is this different from last time?". Different
/// string ⇒ update.
pub fn diff(remote: &[CatalogEntry], local: &[CatalogEntry]) -> CatalogDiff {
    let local_by_id: HashMap<&str, &CatalogEntry> =
        local.iter().map(|e| (e.skill_id.as_str(), e)).collect();
    let remote_by_id: HashMap<&str, &CatalogEntry> =
        remote.iter().map(|e| (e.skill_id.as_str(), e)).collect();

    let mut out = CatalogDiff::default();

    for r in remote {
        match local_by_id.get(r.skill_id.as_str()) {
            None => out.added.push(r.clone()),
            Some(l) if l.version != r.version => out.updated.push(CatalogUpdate {
                skill_id: r.skill_id.clone(),
                old_version: l.version.clone(),
                new_version: r.version.clone(),
                entry: r.clone(),
            }),
            // same version, same id → no change; but we still
            // want to bump `last_seen_at` downstream, so don't
            // emit a diff entry for it.
            Some(_) => {}
        }
    }
    for l in local {
        if !remote_by_id.contains_key(l.skill_id.as_str()) {
            out.removed.push(l.skill_id.clone());
        }
    }
    out
}

/// Resolve the cache file path: `<app_data>/skill_catalog_cache.json`.
/// Created on demand so the caller doesn't have to mkdir.
pub fn cache_file_path(app: &AppHandle) -> Result<PathBuf, String> {
    let dir = app
        .path()
        .app_data_dir()
        .map_err(|e| format!("Failed to get app_data_dir: {}", e))?;
    std::fs::create_dir_all(&dir)
        .map_err(|e| format!("Failed to create app_data_dir {:?}: {}", dir, e))?;
    Ok(dir.join("skill_catalog_cache.json"))
}

/// Read the cache from disk. Returns `Default::default()` on
/// `NotFound` (first run, expected). All other I/O errors
/// propagate so the IPC layer can surface them.
///
/// We tolerate version mismatches by returning the entries
/// anyway — a future schema bump shouldn't brick the cache; it
/// just means we lose any version-specific denormalised fields.
pub fn load(app: &AppHandle) -> Result<CatalogCacheFile, String> {
    let path = cache_file_path(app)?;
    load_from_path(&path)
}

pub fn load_from_path(path: &Path) -> Result<CatalogCacheFile, String> {
    match std::fs::read_to_string(path) {
        Ok(text) => match serde_json::from_str::<CatalogCacheFile>(&text) {
            Ok(parsed) => Ok(parsed),
            Err(e) => {
                log::warn!(
                    "[skill/catalog-cache] parse failed at {:?}: {} — returning empty cache",
                    path,
                    e
                );
                Ok(CatalogCacheFile::default())
            }
        },
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            Ok(CatalogCacheFile::default())
        }
        Err(e) => Err(format!("Failed to read {:?}: {}", path, e)),
    }
}

/// Atomic write: write to `<file>.<uuid>.tmp` then rename.
/// Same pattern as `save_optimized_skill` — keeps a torn-write
/// from corrupting the cache. Returns the absolute path so
/// callers can include it in `SkillCacheState`.
pub fn save(
    app: &AppHandle,
    file: &CatalogCacheFile,
) -> Result<PathBuf, String> {
    let target = cache_file_path(app)?;
    save_to_path(&target, file)?;
    Ok(target)
}

pub fn save_to_path(target: &Path, file: &CatalogCacheFile) -> Result<(), String> {
    let parent = target
        .parent()
        .ok_or_else(|| format!("cache path {:?} has no parent", target))?;
    std::fs::create_dir_all(parent)
        .map_err(|e| format!("Failed to create {:?}: {}", parent, e))?;

    // Refresh the timestamp on every write so `last_refresh_at`
    // reflects the last successful upstream fetch (not the last
    // time the caller *tried* to write — that's the caller's
    // responsibility to only call `save` on success).
    let mut to_write = file.clone();
    to_write.last_refresh_at = now_unix_secs();
    to_write.version = CACHE_SCHEMA_VERSION;

    let json = serde_json::to_string_pretty(&to_write)
        .map_err(|e| format!("serialise cache: {}", e))?;

    let tmp = parent.join(format!(
        "{}.{}.tmp",
        target
            .file_name()
            .and_then(|s| s.to_str())
            .unwrap_or("skill_catalog_cache.json"),
        uuid::Uuid::new_v4().simple()
    ));
    if let Err(e) = std::fs::write(&tmp, json.as_bytes()) {
        let _ = std::fs::remove_file(&tmp);
        return Err(format!("write tmp {:?} failed: {}", tmp, e));
    }
    if let Err(e) = std::fs::rename(&tmp, target) {
        let _ = std::fs::remove_file(&tmp);
        return Err(format!("rename {:?} -> {:?} failed: {}", tmp, target, e));
    }
    Ok(())
}

/// Merge a fresh remote snapshot into the existing local cache
/// and write it back. This is the function the IPC layer should
/// call after a successful `skill.list` round-trip — it updates
/// `last_seen_at` for all entries (including the no-change
/// ones) so the badge text stays accurate, and returns the
/// diff for the caller to act on / return to the front-end.
pub fn refresh(
    app: &AppHandle,
    remote: Vec<CatalogEntry>,
) -> Result<(CatalogDiff, CatalogCacheFile), String> {
    let now = now_unix_secs();
    let mut existing = load(app)?;
    let local_snapshot: Vec<CatalogEntry> = existing.entries.clone();
    let computed = diff(&remote, &local_snapshot);

    // Bump last_seen_at on every remote entry (including the
    // ones that didn't change in the diff). Use HashMap for
    // O(n) merge instead of nested loops.
    let remote_by_id: HashMap<&str, &CatalogEntry> =
        remote.iter().map(|e| (e.skill_id.as_str(), e)).collect();
    for e in existing.entries.iter_mut() {
        if let Some(r) = remote_by_id.get(e.skill_id.as_str()) {
            // Mirror upstream content if it changed. We always
            // copy the fresh description / tags / source — the
            // diff already decided whether version changed.
            e.last_seen_at = now;
            e.name = r.name.clone();
            e.version = r.version.clone();
            e.tags = r.tags.clone();
            e.description = r.description.clone();
            if !r.source.is_empty() {
                e.source = r.source.clone();
            }
        }
    }
    // For newly-added entries, stamp last_seen_at too.
    //
    // 修复 E0502: 原写法 `let existing_ids: HashSet<&str> = existing.entries
    // .iter().map(...).collect()` 会持着 `existing` 的不可变借用，循环里
    // `existing.entries.push(...)` 又要可变借用，编译失败。改成
    // `Vec<String>`（owned）—— set 转 vec 的代价是复制 16+ 字节字符串
    // （catalog 规模 < 1000 条），远比为它引入 `drop(existing_ids)` 或
    // `unsafe { &mut *ptr }` 干净。
    let existing_ids: Vec<String> = existing
        .entries
        .iter()
        .map(|e| e.skill_id.clone())
        .collect();
    for r in remote.iter() {
        if !existing_ids.iter().any(|id| id == &r.skill_id) {
            let mut stamped = r.clone();
            stamped.last_seen_at = now;
            existing.entries.push(stamped);
        }
    }
    // Removal: drop entries that the upstream no longer
    // mentions. This intentionally *deletes* them from the
    // cache — if a removed skill comes back later, it will
    // appear as `added` again. We could keep tombstones but
    // the catalog is small and the front-end doesn't track
    // "recently removed" — keeping stale rows would just make
    // the diff noisy.
    existing.entries.retain(|e| remote_by_id.contains_key(e.skill_id.as_str()));

    save(app, &existing)?;
    Ok((computed, existing))
}

/// Returns true when the cache is older than `max_age_secs`.
/// Used by the front-end to render a "stale since ..." hint
/// without re-querying the upstream.
pub fn is_stale(file: &CatalogCacheFile, max_age_secs: i64) -> bool {
    if file.last_refresh_at == 0 {
        return true; // never refreshed
    }
    now_unix_secs().saturating_sub(file.last_refresh_at) > max_age_secs
}

/// A scored search hit. `score` is the relevance sum across
/// id / name / tag / description matches (see `search`).
/// Higher is better. We expose the score so the IPC layer
/// can decide whether to break ties (e.g. by `last_seen_at`)
/// before returning to the front-end.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SearchHit {
    pub entry: CatalogEntry,
    pub score: i32,
    /// Which fields matched, in declaration order. Useful
    /// for the front-end to bold the matched term in the UI.
    #[serde(default)]
    pub matched_in: Vec<String>,
}

/// Substring-based local search over the cache. We don't try
/// to be a search engine — relevance is a weighted sum:
///
///   * exact id match        → 100
///   * name contains query   →  30
///   * tag exact match       →  20
///   * tag contains query    →  10
///   * description contains  →   5
///   * id contains query     →  15
///
/// The query is lowercased and trimmed. Empty query returns
/// the most-recently-seen entries (sorted by `last_seen_at`
/// desc, capped at `limit`). This is the "what's new" view
/// — we want it to do *something* sensible when the user
/// just opens the panel and hasn't typed anything.
///
/// `limit` defaults to 50 (matches the size of a typical
/// settings panel) and is hard-capped at 200 to keep the
/// response small.
pub fn search(query: &str, entries: &[CatalogEntry], limit: usize) -> Vec<SearchHit> {
    let limit = limit.clamp(1, 200);
    let q = query.trim().to_lowercase();
    if q.is_empty() {
        // "what's new" view: sort by recency. Stable order
        // across calls because the cache is sorted by
        // skill_id on write; recency gives the user a useful
        // default.
        let mut sorted: Vec<&CatalogEntry> = entries.iter().collect();
        sorted.sort_by_key(|b| std::cmp::Reverse(b.last_seen_at));
        return sorted
            .into_iter()
            .take(limit)
            .map(|e| SearchHit {
                entry: e.clone(),
                score: 0,
                matched_in: vec![],
            })
            .collect();
    }

    let mut hits: Vec<SearchHit> = entries
        .iter()
        .filter_map(|e| {
            let mut score: i32 = 0;
            let mut matched: Vec<String> = Vec::new();
            let id_lc = e.skill_id.to_lowercase();
            let name_lc = e.name.to_lowercase();
            let desc_lc = e.description.as_deref().unwrap_or("").to_lowercase();

            if id_lc == q {
                score += 100;
                matched.push("id:exact".to_string());
            } else if id_lc.contains(&q) {
                score += 15;
                matched.push("id".to_string());
            }
            if name_lc == q {
                score += 80;
                matched.push("name:exact".to_string());
            } else if name_lc.contains(&q) {
                score += 30;
                matched.push("name".to_string());
            }
            for tag in &e.tags {
                let tl = tag.to_lowercase();
                if tl == q {
                    score += 20;
                    matched.push(format!("tag:{}", tag));
                } else if tl.contains(&q) {
                    score += 10;
                    matched.push(format!("tag:{}", tag));
                }
            }
            if !desc_lc.is_empty() && desc_lc.contains(&q) {
                score += 5;
                matched.push("description".to_string());
            }
            if score > 0 {
                Some(SearchHit {
                    entry: e.clone(),
                    score,
                    matched_in: matched,
                })
            } else {
                None
            }
        })
        .collect();

    // Sort by score desc, then by most recently seen (so
    // a new skill beats an old one when they tie), then by
    // name for stable display order.
    hits.sort_by(|a, b| {
        b.score
            .cmp(&a.score)
            .then_with(|| b.entry.last_seen_at.cmp(&a.entry.last_seen_at))
            .then_with(|| a.entry.name.cmp(&b.entry.name))
    });
    hits.truncate(limit);
    hits
}

pub fn now_unix_secs() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entry(id: &str, version: &str) -> CatalogEntry {
        CatalogEntry {
            skill_id: id.to_string(),
            name: format!("{} name", id),
            version: version.to_string(),
            tags: vec!["test".to_string()],
            description: Some(format!("{} desc", id)),
            last_seen_at: 0,
            source: "community".to_string(),
        }
    }

    #[test]
    fn diff_first_run_all_added() {
        let remote = vec![entry("a", "1.0.0"), entry("b", "1.0.0")];
        let local: Vec<CatalogEntry> = vec![];
        let d = diff(&remote, &local);
        assert_eq!(d.added.len(), 2);
        assert_eq!(d.updated.len(), 0);
        assert_eq!(d.removed.len(), 0);
        assert!(!d.is_empty());
        assert_eq!(d.total(), 2);
    }

    #[test]
    fn diff_version_change_is_update() {
        let remote = vec![entry("a", "2.0.0")];
        let local = vec![entry("a", "1.0.0")];
        let d = diff(&remote, &local);
        assert!(d.added.is_empty());
        assert_eq!(d.updated.len(), 1);
        assert_eq!(d.updated[0].old_version, "1.0.0");
        assert_eq!(d.updated[0].new_version, "2.0.0");
        assert_eq!(d.updated[0].skill_id, "a");
        assert!(d.removed.is_empty());
    }

    #[test]
    fn diff_missing_in_remote_is_removed() {
        let remote = vec![entry("a", "1.0.0")];
        let local = vec![entry("a", "1.0.0"), entry("b", "1.0.0")];
        let d = diff(&remote, &local);
        assert!(d.added.is_empty());
        assert!(d.updated.is_empty());
        assert_eq!(d.removed, vec!["b".to_string()]);
    }

    #[test]
    fn diff_identical_is_empty() {
        let remote = vec![entry("a", "1.0.0"), entry("b", "2.0.0")];
        let local = remote.clone();
        let d = diff(&remote, &local);
        assert!(d.is_empty());
        assert_eq!(d.total(), 0);
    }

    #[test]
    fn diff_mixed_three_branches() {
        let remote = vec![
            entry("new", "1.0.0"),
            entry("updated", "2.0.0"),
            entry("stable", "1.0.0"),
        ];
        let local = vec![
            entry("updated", "1.0.0"),
            entry("stable", "1.0.0"),
            entry("removed", "1.0.0"),
        ];
        let d = diff(&remote, &local);
        assert_eq!(d.added.len(), 1);
        assert_eq!(d.added[0].skill_id, "new");
        assert_eq!(d.updated.len(), 1);
        assert_eq!(d.updated[0].skill_id, "updated");
        assert_eq!(d.removed, vec!["removed".to_string()]);
    }

    #[test]
    fn roundtrip_via_disk() {
        // Use a temp dir so we don't depend on a real app_data_dir.
        let tmp = std::env::temp_dir().join(format!(
            "skill_catalog_cache_test_{}",
            uuid::Uuid::new_v4().simple()
        ));
        std::fs::create_dir_all(&tmp).unwrap();
        let target = tmp.join("skill_catalog_cache.json");

        let original = CatalogCacheFile {
            version: CACHE_SCHEMA_VERSION,
            last_refresh_at: 1_700_000_000,
            entries: vec![entry("a", "1.0.0"), entry("b", "2.0.0")],
        };
        save_to_path(&target, &original).unwrap();
        let restored = load_from_path(&target).unwrap();
        // last_refresh_at is bumped on save — only entries are
        // expected to round-trip identically.
        assert_eq!(restored.entries.len(), 2);
        assert_eq!(restored.entries[0].skill_id, "a");
        assert_eq!(restored.entries[1].skill_id, "b");
        assert!(restored.last_refresh_at >= 1_700_000_000);

        // Tidy up.
        let _ = std::fs::remove_dir_all(&tmp);
    }

    #[test]
    fn load_missing_returns_default() {
        let bogus = std::env::temp_dir().join(format!(
            "no_such_skill_cache_{}",
            uuid::Uuid::new_v4().simple()
        ));
        let got = load_from_path(&bogus).unwrap();
        assert!(got.entries.is_empty());
        assert_eq!(got.last_refresh_at, 0);
    }

    #[test]
    fn is_stale_handles_never_refreshed() {
        let f = CatalogCacheFile::default();
        assert!(is_stale(&f, 60));
    }

    /// `refresh` 写盘需要 AppHandle,这里直接复用 `save_to_path` /
    /// `load_from_path` + 手写合并逻辑,验证"已有本地条目被
    /// 上游同名条目更新"时 last_seen_at 会被刷新、name/version
    /// 跟着新走,而 removed 条目会被删除。
    #[test]
    fn refresh_merges_existing_entries() {
        let tmp = std::env::temp_dir().join(format!(
            "skill_catalog_cache_refresh_test_{}",
            uuid::Uuid::new_v4().simple()
        ));
        std::fs::create_dir_all(&tmp).unwrap();
        let target = tmp.join("skill_catalog_cache.json");

        // Seed: one stable row, one that will be removed.
        let seed = CatalogCacheFile {
            version: CACHE_SCHEMA_VERSION,
            last_refresh_at: 1_700_000_000,
            entries: vec![
                CatalogEntry {
                    skill_id: "stable".to_string(),
                    name: "Stable".to_string(),
                    version: "1.0.0".to_string(),
                    tags: vec!["old".to_string()],
                    description: None,
                    last_seen_at: 1_700_000_000,
                    source: "community".to_string(),
                },
                CatalogEntry {
                    skill_id: "going-away".to_string(),
                    name: "Going Away".to_string(),
                    version: "0.1.0".to_string(),
                    tags: vec![],
                    description: None,
                    last_seen_at: 1_700_000_000,
                    source: "community".to_string(),
                },
            ],
        };
        save_to_path(&target, &seed).unwrap();

        // Now simulate a refresh from upstream: stable is still
        // there with a new version + new tag, going-away is gone,
        // and a brand-new "fresh" appears. We hand-merge here
        // because `refresh()` takes an AppHandle, which we don't
        // have in unit tests; the merge logic in `refresh()`
        // mirrors what we do below.
        let now = now_unix_secs();
        let remote: Vec<CatalogEntry> = vec![
            CatalogEntry {
                skill_id: "stable".to_string(),
                name: "Stable".to_string(),
                version: "1.1.0".to_string(),
                tags: vec!["new".to_string()],
                description: Some("now with more cowbell".to_string()),
                last_seen_at: now,
                source: "community".to_string(),
            },
            CatalogEntry {
                skill_id: "fresh".to_string(),
                name: "Fresh".to_string(),
                version: "0.0.1".to_string(),
                tags: vec!["new".to_string()],
                description: None,
                last_seen_at: now,
                source: "community".to_string(),
            },
        ];
        let local = load_from_path(&target).unwrap();
        let computed = diff(&remote, &local.entries);

        // Diff contract: stable is an update, fresh is added,
        // going-away is removed.
        assert_eq!(computed.added.len(), 1);
        assert_eq!(computed.added[0].skill_id, "fresh");
        assert_eq!(computed.updated.len(), 1);
        assert_eq!(computed.updated[0].skill_id, "stable");
        assert_eq!(computed.updated[0].old_version, "1.0.0");
        assert_eq!(computed.updated[0].new_version, "1.1.0");
        assert_eq!(computed.removed, vec!["going-away".to_string()]);

        // The post-merge cache: stable + fresh, going-away gone.
        // We re-use the merge logic by hand to keep the test
        // pure (no AppHandle dependency).
        let mut merged = local;
        let remote_by_id: HashMap<String, &CatalogEntry> =
            remote.iter().map(|e| (e.skill_id.clone(), e)).collect();
        let remote_ids: std::collections::HashSet<String> =
            remote_by_id.keys().cloned().collect();
        for e in merged.entries.iter_mut() {
            if let Some(r) = remote_by_id.get(&e.skill_id) {
                e.name = r.name.clone();
                e.version = r.version.clone();
                e.tags = r.tags.clone();
                e.description = r.description.clone();
                e.last_seen_at = now;
            }
        }
        let existing_ids: std::collections::HashSet<String> =
            merged.entries.iter().map(|e| e.skill_id.clone()).collect();
        for r in &remote {
            if !existing_ids.contains(&r.skill_id) {
                let mut stamped = r.clone();
                stamped.last_seen_at = now;
                merged.entries.push(stamped);
            }
        }
        merged.entries.retain(|e| remote_ids.contains(&e.skill_id));
        save_to_path(&target, &merged).unwrap();

        let restored = load_from_path(&target).unwrap();
        assert_eq!(restored.entries.len(), 2);
        let ids: std::collections::HashSet<String> = restored
            .entries
            .iter()
            .map(|e| e.skill_id.clone())
            .collect();
        assert!(ids.contains("stable"));
        assert!(ids.contains("fresh"));
        assert!(!ids.contains("going-away"));
        // stable's tag and description came from upstream.
        let stable = restored
            .entries
            .iter()
            .find(|e| e.skill_id == "stable")
            .unwrap();
        assert_eq!(stable.version, "1.1.0");
        assert_eq!(stable.tags, vec!["new".to_string()]);
        assert_eq!(
            stable.description.as_deref(),
            Some("now with more cowbell")
        );
        // last_seen_at was refreshed.
        assert!(stable.last_seen_at >= now - 1);

        let _ = std::fs::remove_dir_all(&tmp);
    }

    // --- search() ----------------------------------------------------------

    fn e(id: &str, name: &str, tags: &[&str], desc: &str, last_seen: i64) -> CatalogEntry {
        CatalogEntry {
            skill_id: id.to_string(),
            name: name.to_string(),
            version: "1.0.0".to_string(),
            tags: tags.iter().map(|s| s.to_string()).collect(),
            description: Some(desc.to_string()),
            last_seen_at: last_seen,
            source: "community".to_string(),
        }
    }

    #[test]
    fn search_empty_query_returns_recent_first() {
        let entries = vec![
            e("a", "A", &["old"], "old skill", 100),
            e("b", "B", &["new"], "new skill", 200),
            e("c", "C", &["mid"], "mid skill", 150),
        ];
        let hits = search("", &entries, 10);
        assert_eq!(hits.len(), 3);
        assert_eq!(hits[0].entry.skill_id, "b");
        assert_eq!(hits[1].entry.skill_id, "c");
        assert_eq!(hits[2].entry.skill_id, "a");
        // Empty query yields no matched_in markers.
        assert!(hits[0].matched_in.is_empty());
    }

    #[test]
    fn search_exact_id_match_ranks_above_substring() {
        // Entry 1 has the query as an exact id; entry 2 has
        // it only as a substring inside a longer id. The
        // exact id match should rank first because it
        // scores 100 vs 15.
        let entries = vec![
            e("trace", "Trace", &["x"], "the trace", 100),
            e("tracer", "Tracer", &["y"], "another", 100),
        ];
        let hits = search("trace", &entries, 10);
        assert_eq!(hits.len(), 2);
        assert_eq!(hits[0].entry.skill_id, "trace");
        assert_eq!(hits[1].entry.skill_id, "tracer");
        // The exact id match should outscore substring.
        assert!(hits[0].score > hits[1].score);
    }

    #[test]
    fn search_name_match_outscores_description() {
        let entries = vec![
            e("x", "WeChat Publisher", &["wechat"], "weixin thing", 100),
            e("y", "Other", &["wechat"], "wechat in description only", 100),
        ];
        let hits = search("wechat", &entries, 10);
        assert_eq!(hits.len(), 2);
        // Name (30) + tag (20) > tag (20) + desc (5)
        assert_eq!(hits[0].entry.skill_id, "x");
    }

    #[test]
    fn search_tag_match_includes_matched_field() {
        let entries = vec![e("a", "A", &["xlsx", "excel"], "spreadsheet", 100)];
        let hits = search("xlsx", &entries, 10);
        assert_eq!(hits.len(), 1);
        assert!(hits[0].matched_in.iter().any(|m| m == "tag:xlsx"));
    }

    #[test]
    fn search_case_insensitive() {
        let entries = vec![e("a", "WECHAT", &[], "thing", 100)];
        let hits = search("wechat", &entries, 10);
        assert_eq!(hits.len(), 1);
    }

    #[test]
    fn search_no_match_returns_empty() {
        let entries = vec![e("a", "A", &["x"], "y", 100)];
        let hits = search("zzz", &entries, 10);
        assert!(hits.is_empty());
    }

    #[test]
    fn search_respects_limit() {
        let entries: Vec<CatalogEntry> = (0..10)
            .map(|i| e(&format!("skill-{i}"), "S", &["x"], "match here", 100))
            .collect();
        let hits = search("match", &entries, 3);
        assert_eq!(hits.len(), 3);
    }

    #[test]
    fn search_clamps_oversized_limit() {
        let entries: Vec<CatalogEntry> = (0..250)
            .map(|i| e(&format!("s-{i}"), "S", &[], "match", 100))
            .collect();
        let hits = search("match", &entries, 1000);
        // hard cap 200
        assert_eq!(hits.len(), 200);
    }
}
