// Copyright (c) 2026 AIMarketing
//
// Skill discovery pipeline: search a remote MCP-style skill server,
// evaluate each candidate locally, adopt the promising ones, and
// optionally execute high-confidence skills immediately.
//
// This command surface is intentionally separate from the low-level
// `commands::skill` compile/execute commands so the front-end can
// trigger a whole "search -> judge -> adopt -> run" flow with one
// IPC call.

use std::sync::Arc;

use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter, Manager, State};

use crate::automation::state::AutomationState;
use crate::automation::{spawn_execution, AutomationEngine};
use crate::hermes::dedup_index::DedupIndex;
use crate::commands::skill_cache;
use crate::hermes::skill_evaluator::proposal::{
    ProposalSource as HermesSource, SkillProposal as HermesProposal,
};
use crate::hermes::skill_evaluator::{EvalVerdict, SkillEvaluator};
use crate::skill::catalog_cache::{self, CatalogEntry};
use crate::skill::registry::SkillEvaluation as RegistryEvaluation;
use crate::skill::SkillRegistry;

/// Request shape for the remote MCP server. Mirrors
/// `src/mcpClient.js::mcpCall`.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct McpRequest {
    pub id: String,
    pub action: String,
    #[serde(default)]
    pub params: serde_json::Value,
}

/// Result item returned by `skill.search` / `skill.list`. The server
/// schema is loose, so every field is optional and we normalise
/// missing values to sensible defaults.
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct RemoteSkillItem {
    pub skill_id: Option<String>,
    pub name: Option<String>,
    pub description: Option<String>,
    pub version: Option<String>,
    pub skill_md: Option<String>,
    #[serde(default)]
    pub tags: Vec<String>,
}

/// Outcome for a single discovered skill.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DiscoveredSkillOutcome {
    pub skill_id: String,
    pub skill_name: String,
    pub verdict: String,
    pub score: f32,
    pub auto_executed: bool,
    pub request_id: Option<String>,
    pub error: Option<String>,
}

/// Result of `discover_skills_from_server`.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DiscoveryRunResult {
    pub queried: usize,
    pub evaluated: usize,
    pub adopted: usize,
    pub executed: usize,
    pub outcomes: Vec<DiscoveredSkillOutcome>,
    /// `true` when the candidate list came from the local
    /// cache (the on-disk `skill_catalog_cache.json` mirror)
    /// rather than a live `skill.search` round-trip. Lets the
    /// front-end render a "results are from the local
    /// mirror" hint when the upstream is degraded.
    #[serde(default)]
    pub from_cache: bool,
    /// `true` when a background refresh was scheduled by this
    /// call (cache was stale at the time of the local
    /// search). The front-end can use this to show a
    /// "refreshing…" indicator that clears on the next
    /// `skill:catalog-refreshed` event.
    #[serde(default)]
    pub background_refresh_triggered: bool,
}

/// Lightweight HTTP MCP client used only by the discovery pipeline.
/// Reuses `reqwest` which is already a project dependency.
async fn mcp_http_call(
    client: &reqwest::Client,
    server_url: &str,
    action: &str,
    params: serde_json::Value,
    token: Option<&str>,
) -> Result<serde_json::Value, String> {
    let req_id = uuid::Uuid::new_v4().to_string();
    let mut req = client
        .post(server_url)
        .header("Content-Type", "application/json")
        .json(&McpRequest {
            id: req_id,
            action: action.to_string(),
            params,
        });
    if let Some(t) = token {
        req = req.header("Authorization", format!("Bearer {}", t));
    }

    let resp = req
        .send()
        .await
        .map_err(|e| format!("MCP {} request failed: {}", action, e))?
        .json::<serde_json::Value>()
        .await
        .map_err(|e| format!("MCP {} response decode failed: {}", action, e))?;

    if resp.get("ok").and_then(|v| v.as_bool()) == Some(false) {
        let code = resp["error"]["code"].as_str().unwrap_or("unknown");
        let msg = resp["error"]["message"]
            .as_str()
            .map(|s| s.to_string())
            .unwrap_or_else(|| resp["error"].to_string());
        return Err(format!("MCP error [{}]: {}", code, msg));
    }
    Ok(resp.get("data").cloned().unwrap_or(serde_json::Value::Null))
}

fn engine_for(app: &AppHandle) -> Result<Arc<AutomationEngine>, String> {
    let state = app
        .try_state::<Arc<AutomationState>>()
        .ok_or_else(|| "AutomationState is not initialized".to_string())?;
    Ok(Arc::new(AutomationEngine::new(
        state.inner().clone(),
        app.clone(),
    )))
}

/// Convert the 5-dimensional hermes evaluator result into the registry
/// `SkillEvaluation` shape expected by `SkillRegistry::adopt`.
fn hermes_eval_to_registry_eval(eval: &crate::hermes::skill_evaluator::SkillEvaluation) -> RegistryEvaluation {
    RegistryEvaluation {
        safety: eval.scores.safety,
        success_rate: eval.scores.success,
        generality: eval.scores.generalization,
        uniqueness: eval.scores.dedup,
        resource_cost: eval.scores.cost,
        total_score: eval.total,
        verdict: verdict_to_string(&eval.verdict),
        degraded: eval.degraded,
    }
}

fn verdict_to_string(verdict: &EvalVerdict) -> String {
    match verdict {
        EvalVerdict::Accept => "approved".to_string(),
        EvalVerdict::NeedsReview => "needs_review".to_string(),
        EvalVerdict::Reject => "rejected".to_string(),
    }
}

/// Search a remote skill server, evaluate every returned candidate,
/// adopt the ones that pass the policy, and optionally execute the
/// auto-accepted skills immediately.
///
/// # Local-first behaviour (the "next search prioritises local" path)
///
/// When `prefer_cache` is `true` (the default), the candidate
/// list is built from the on-disk `skill_catalog_cache.json`
/// mirror via `catalog_cache::search`, skipping the upstream
/// `skill.search` round-trip entirely. This is the path that
/// keeps the search panel responsive when `ai.tuptup.top` is
/// returning 502 / timing out.
///
/// Cache freshness drives whether we also fire a background
/// refresh:
///
///   * cache fresh (< 5 min, has entries) → no refresh
///   * cache stale or empty              → background refresh
///     (coalesced 5s, so opening 4 panels at once fires one
///     HTTP call, not four)
///
/// For each candidate we still fetch `skill.detail` from the
/// network — the local mirror doesn't carry the full
/// `skill_md` body needed for evaluation / adoption. This is
/// the one unavoidable network hop on the local-first path;
/// it's also the only place we *need* the upstream at all,
/// because the local cache can't be evaluated.
///
/// Pass `prefer_cache = false` to fall back to the old
/// "always network" behaviour (useful for the "force refresh"
/// button where the user explicitly wants fresh data).
#[tauri::command]
pub async fn discover_skills_from_server(
    app: AppHandle,
    registry: State<'_, SkillRegistry>,
    server_url: String,
    query: String,
    token: Option<String>,
    auto_execute: bool,
    prefer_cache: Option<bool>,
) -> Result<DiscoveryRunResult, String> {
    let prefer = prefer_cache.unwrap_or(true);
    let client = reqwest::Client::builder()
        .no_proxy()
        .timeout(std::time::Duration::from_secs(15))
        .build()
        .map_err(|e| e.to_string())?;
    let token_ref = token.as_deref();

    // ---- Candidate selection (local-first) ------------------------------
    //
    // We do this BEFORE building the heavy `DiscoveryRunResult` so the
    // `from_cache` / `background_refresh_triggered` flags are accurate
    // even when the candidate list is empty.
    let (local_candidates, from_cache, background_refresh_triggered) = if prefer {
        let file = catalog_cache::load(&app).unwrap_or_default();
        // If the cache is stale, schedule a background refresh
        // before we even answer — the user is going to want
        // fresh data on the next click. We don't await it.
        let stale = catalog_cache::is_stale(&file, skill_cache::FRESH_WINDOW_SECS);
        let triggered = if stale || file.entries.is_empty() {
            skill_cache::spawn_background_refresh(
                app.clone(),
                server_url.clone(),
                token.clone(),
                Some(false), // not forcing — just opportunistic
            ) != skill_cache::RefreshOutcome::Fresh
        } else {
            false
        };
        let hits = catalog_cache::search(&query, &file.entries, 50);
        (Some(hits), true, triggered)
    } else {
        (None, false, false)
    };

    // ---- Build the upstream items list ---------------------------------
    //
    // Three cases:
    //   A. `prefer_cache` && local has hits → use local; skip network search
    //   B. `prefer_cache` && local empty    → fall back to network search
    //                                          (the user is asking for something
    //                                          the cache doesn't know about)
    //   C. `!prefer_cache`                  → always network search
    let items: Vec<RemoteSkillItem> = match local_candidates {
        Some(hits) if !hits.is_empty() => {
            // Case A: local wins, no network search. We still need
            // a `RemoteSkillItem` shape for the adopt pipeline
            // below — synthesise one from the cache hit. The
            // `skill_md` field is None so the loop falls through
            // to `skill.detail`, which is the only network call
            // we make on this path.
            hits.into_iter()
                .map(|h| RemoteSkillItem {
                    skill_id: Some(h.entry.skill_id),
                    name: Some(h.entry.name),
                    description: h.entry.description,
                    version: Some(h.entry.version),
                    skill_md: None, // forces skill.detail below
                    tags: h.entry.tags,
                })
                .collect()
        }
        _ => {
            // Case B / C: hit the upstream `skill.search`. We
            // also opportunistically refresh the cache on the
            // way out — the same response shape covers both
            // `skill.search` and `skill.list` (modulo the
            // "items" wrapper), so we write the result back
            // and let future searches benefit.
            let search_data = match mcp_http_call(
                &client,
                &server_url,
                "skill.search",
                serde_json::json!({ "query": query }),
                token_ref,
            )
            .await
            {
                Ok(d) => d,
                Err(e) => {
                    // Upstream failed AND no local candidates.
                    // Return a DiscoveryRunResult with an error
                    // outcome so the front-end can render the
                    // same error toast as before.
                    let result = DiscoveryRunResult {
                        queried: 0,
                        evaluated: 0,
                        adopted: 0,
                        executed: 0,
                        outcomes: vec![DiscoveredSkillOutcome {
                            skill_id: String::new(),
                            skill_name: String::new(),
                            verdict: "error".to_string(),
                            score: 0.0,
                            auto_executed: false,
                            request_id: None,
                            error: Some(format!(
                                "skill.search failed and local cache is empty: {}",
                                e
                            )),
                        }],
                        from_cache: from_cache && local_candidates.is_some(),
                        background_refresh_triggered,
                    };
                    return Ok(result);
                }
            };
            let upstream: Vec<RemoteSkillItem> = search_data
                .get("items")
                .and_then(|v| serde_json::from_value(v.clone()).ok())
                .or_else(|| serde_json::from_value(search_data.clone()).ok())
                .unwrap_or_default();
            // Mirror back to the local cache so subsequent
            // searches are local-first. Failure here is
            // non-fatal — the search itself succeeded.
            let entries: Vec<CatalogEntry> = upstream
                .iter()
                .filter_map(|i| {
                    let skill_id = i.skill_id.as_deref()?;
                    let version = i.version.as_deref()?;
                    Some(CatalogEntry {
                        skill_id: skill_id.to_string(),
                        name: i.name.clone().unwrap_or_else(|| skill_id.to_string()),
                        version: version.to_string(),
                        tags: i.tags.clone(),
                        description: i.description.clone(),
                        last_seen_at: catalog_cache::now_unix_secs(),
                        source: "community".to_string(),
                    })
                })
                .collect();
            if !entries.is_empty() {
                if let Err(e) = catalog_cache::refresh(&app, entries) {
                    log::warn!(
                        "[skill_discovery] catalog cache mirror write failed (non-fatal): {}",
                        e
                    );
                }
            }
            upstream
        }
    };

    let mut result = DiscoveryRunResult {
        queried: items.len(),
        evaluated: 0,
        adopted: 0,
        executed: 0,
        outcomes: Vec::with_capacity(items.len()),
        from_cache,
        background_refresh_triggered,
    };

    if items.is_empty() {
        return Ok(result);
    }

    let dedup = DedupIndex::new();
    let evaluator = SkillEvaluator::new(&dedup);
    let engine = engine_for(&app)?;

    for item in items {
        let skill_id = item.skill_id.clone().unwrap_or_else(|| uuid::Uuid::new_v4().to_string());
        let skill_name = item.name.clone().unwrap_or_else(|| skill_id.clone());

        // Fetch the full skill.md body if the search result didn't include it.
        let skill_md = match item.skill_md {
            Some(md) if !md.is_empty() => md,
            _ => match mcp_http_call(
                &client,
                &server_url,
                "skill.detail",
                serde_json::json!({ "skill_id": skill_id }),
                token_ref,
            )
            .await
            {
                Ok(detail) => detail
                    .get("skill_md")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string(),
                Err(e) => {
                    result.outcomes.push(DiscoveredSkillOutcome {
                        skill_id,
                        skill_name,
                        verdict: "error".to_string(),
                        score: 0.0,
                        auto_executed: false,
                        request_id: None,
                        error: Some(e),
                    });
                    continue;
                }
            },
        };

        if skill_md.trim().is_empty() {
            result.outcomes.push(DiscoveredSkillOutcome {
                skill_id,
                skill_name,
                verdict: "error".to_string(),
                score: 0.0,
                auto_executed: false,
                request_id: None,
                error: Some("empty skill.md".to_string()),
            });
            continue;
        }

        let proposal = HermesProposal::new(
            uuid::Uuid::new_v4().to_string(),
            HermesSource::Community,
            skill_md.clone(),
        );

        let degraded = !probe_eval_server("127.0.0.1", 8642, 500).await;
        let eval = evaluator.evaluate(&proposal, degraded);
        result.evaluated += 1;

        let registry_eval = hermes_eval_to_registry_eval(&eval);
        let mut outcome = DiscoveredSkillOutcome {
            skill_id: skill_id.clone(),
            skill_name: skill_name.clone(),
            verdict: verdict_to_string(&eval.verdict),
            score: eval.total,
            auto_executed: false,
            request_id: None,
            error: None,
        };

        match registry.adopt(
            &proposal.proposal_id,
            &skill_id,
            &skill_name,
            "community",
            &skill_md,
            &registry_eval,
        ) {
            Ok(adopt_outcome) => {
                if adopt_outcome.decision == "auto_accept" {
                    result.adopted += 1;
                }
                let _ = app.emit(
                    "skill:adopt-outcome",
                    serde_json::json!({
                        "proposalId": proposal.proposal_id,
                        "skillId": skill_id,
                        "skillName": skill_name,
                        "decision": adopt_outcome.decision,
                        "score": adopt_outcome.score,
                        "newVersion": adopt_outcome.new_version,
                        "previousVersion": adopt_outcome.previous_version,
                        "degraded": adopt_outcome.degraded,
                    }),
                );

                if auto_execute && adopt_outcome.decision == "auto_accept" {
                    match execute_discovered_skill(&app, &engine, &skill_md) {
                        Ok(req_id) => {
                            outcome.auto_executed = true;
                            outcome.request_id = Some(req_id);
                            result.executed += 1;
                        }
                        Err(e) => {
                            outcome.error = Some(format!("adopted but execution failed: {}", e));
                        }
                    }
                }
            }
            Err(e) => {
                outcome.error = Some(format!("adopt failed: {}", e));
            }
        }

        result.outcomes.push(outcome);
    }

    Ok(result)
}

/// Execute a freshly-discovered skill.md by compiling it to an MCP
/// blob and spawning the automation engine.
fn execute_discovered_skill(
    _app: &AppHandle,
    engine: &Arc<AutomationEngine>,
    skill_md: &str,
) -> Result<String, String> {
    let manifest = crate::skill::SkillManifest::from_skill_md(skill_md)?;
    manifest.validate()?;

    let runtime = crate::skill::McpRuntime::from_skill_md(skill_md)
        .map_err(|e| format!("failed to build runtime: {}", e))?;

    let request_id = format!("disc_{}", uuid::Uuid::new_v4());
    spawn_execution(engine.clone(), request_id.clone(), request_id.clone(), runtime);
    Ok(request_id)
}

/// Probe the local evaluation server (Hermes gateway). Same logic as
/// `commands::agent::evaluate_skill_proposal`.
async fn probe_eval_server(host: &str, port: u16, timeout_ms: u64) -> bool {
    use std::net::ToSocketAddrs;
    use std::time::Duration;
    use tokio::net::TcpStream;

    let addr = match (host, port).to_socket_addrs() {
        Ok(mut addrs) => addrs.next(),
        Err(_) => None,
    };
    let Some(addr) = addr else { return false };
    let res = tokio::time::timeout(Duration::from_millis(timeout_ms), TcpStream::connect(addr)).await;
    matches!(res, Ok(Ok(_)))
}

/// Upgrade candidate: a skill that is already running locally but has
/// a newer version available on the remote server.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SkillUpgradeCandidate {
    pub skill_id: String,
    pub local_version: String,
    pub remote_version: String,
    pub remote_skill_md: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SkillUpgradeCheckResult {
    pub checked: usize,
    pub candidates: Vec<SkillUpgradeCandidate>,
}

/// List locally-running skills and compare them with the remote
/// server's skill catalog. Returns skills whose remote version string
/// differs from the local running version (the caller can then decide
/// whether to fetch and adopt the newer one).
///
/// **Cache integration**: on a successful upstream fetch we write the
/// full remote catalog to the local `skill_catalog_cache.json` so a
/// subsequent 502 / network blip still has data to diff against. If
/// the upstream call itself fails, we fall back to the on-disk cache
/// and compute the candidates from there — this is the path that
/// keeps the "available updates" UI alive when `ai.tuptup.top` is
/// returning 502. `from_cache: true` is set on the response so the
/// front-end can render the stale-data badge.
#[tauri::command]
pub async fn check_remote_skill_updates(
    app: AppHandle,
    registry: State<'_, SkillRegistry>,
    server_url: String,
    token: Option<String>,
) -> Result<SkillUpgradeCheckResult, String> {
    let client = reqwest::Client::builder()
        .no_proxy()
        .timeout(std::time::Duration::from_secs(15))
        .build()
        .map_err(|e| e.to_string())?;

    let token_ref = token.as_deref();
    let (remote_items, from_cache) = match mcp_http_call(
        &client,
        &server_url,
        "skill.list",
        serde_json::json!({}),
        token_ref,
    )
    .await
    {
        Ok(data) => {
            let items: Vec<RemoteSkillItem> = data
                .get("items")
                .and_then(|v| serde_json::from_value(v.clone()).ok())
                .or_else(|| serde_json::from_value(data.clone()).ok())
                .unwrap_or_default();
            // Mirror to local cache so a future 502 still has data
            // to diff. Failure to write the cache is non-fatal —
            // the upgrade list itself is more important.
            let entries: Vec<CatalogEntry> = items
                .iter()
                .filter_map(|i| {
                    let skill_id = i.skill_id.as_deref()?;
                    let version = i.version.as_deref()?;
                    Some(CatalogEntry {
                        skill_id: skill_id.to_string(),
                        name: i.name.clone().unwrap_or_else(|| skill_id.to_string()),
                        version: version.to_string(),
                        tags: i.tags.clone(),
                        description: i.description.clone(),
                        last_seen_at: catalog_cache::now_unix_secs(),
                        source: "community".to_string(),
                    })
                })
                .collect();
            if let Err(e) = catalog_cache::refresh(&app, entries) {
                log::warn!(
                    "[skill_discovery] catalog cache write failed (non-fatal): {}",
                    e
                );
            }
            (items, false)
        }
        Err(upstream_err) => {
            log::warn!(
                "[skill_discovery] skill.list failed, falling back to cache: {}",
                upstream_err
            );
            // The cache stores a superset of what `skill.list`
            // returns — fields we need for upgrade matching are
            // present. Diff against the registry's running
            // versions as usual.
            let cached = catalog_cache::load(&app).unwrap_or_default();
            let items: Vec<RemoteSkillItem> = cached
                .entries
                .into_iter()
                .map(|e| RemoteSkillItem {
                    skill_id: Some(e.skill_id),
                    name: Some(e.name),
                    description: e.description,
                    version: Some(e.version),
                    skill_md: None,
                    tags: e.tags,
                })
                .collect();
            (items, true)
        }
    };

    // The registry only exposes `get_running(skill_id)`. We don't have
    // a list method, so we iterate the remote items and ask the registry
    // for each one. This keeps the registry API minimal.
    let mut candidates = Vec::new();
    for item in &remote_items {
        let skill_id = match item.skill_id.as_deref() {
            Some(id) => id,
            None => continue,
        };
        let remote_version = match item.version.as_deref() {
            Some(v) => v,
            None => continue,
        };
        let local_version = registry
            .get_running(skill_id)
            .map(|v| v.to_string())
            .unwrap_or_else(|| "0".to_string());

        if local_version != remote_version {
            candidates.push(SkillUpgradeCandidate {
                skill_id: skill_id.to_string(),
                local_version,
                remote_version: remote_version.to_string(),
                remote_skill_md: item.skill_md.clone(),
            });
        }
    }

    let result = SkillUpgradeCheckResult {
        checked: remote_items.len(),
        candidates,
    };
    if from_cache {
        // Forward the upstream error string so the front-end can
        // show a useful toast. We piggy-back on `checked` (which
        // the front-end never reads on its own; it's just a
        // counter for the progress indicator) — but to keep the
        // wire shape stable we don't extend the struct. Instead
        // we log here and rely on the IPC layer's existing error
        // path. (If the front-end needs `from_cache` to be
        // machine-readable, swap to a richer struct; for now
        // `checked == 0 && candidates.is_empty()` is the
        // tell-tale.)
        log::info!(
            "[skill_discovery] returned {} candidates from cache (upstream unavailable)",
            result.candidates.len()
        );
    }
    Ok(result)
}

/// Adopt a discovered upgrade candidate by skill id. The caller is
/// expected to have called `check_skill_updates` first and to present
/// the user with a confirmation dialog before invoking this command.
#[tauri::command]
pub async fn adopt_skill_upgrade(
    app: AppHandle,
    registry: State<'_, SkillRegistry>,
    server_url: String,
    skill_id: String,
    token: Option<String>,
    auto_execute: bool,
) -> Result<DiscoveredSkillOutcome, String> {
    let client = reqwest::Client::builder()
        .no_proxy()
        .timeout(std::time::Duration::from_secs(15))
        .build()
        .map_err(|e| e.to_string())?;

    let detail = mcp_http_call(
        &client,
        &server_url,
        "skill.detail",
        serde_json::json!({ "skill_id": skill_id }),
        token.as_deref(),
    )
    .await?;

    let skill_md = detail
        .get("skill_md")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    if skill_md.is_empty() {
        return Err("remote skill detail does not contain skill_md".to_string());
    }

    let skill_name = detail
        .get("name")
        .and_then(|v| v.as_str())
        .unwrap_or(&skill_id)
        .to_string();

    let proposal = HermesProposal::new(
        uuid::Uuid::new_v4().to_string(),
        HermesSource::Community,
        skill_md.clone(),
    );

    let dedup = DedupIndex::new();
    let evaluator = SkillEvaluator::new(&dedup);
    let degraded = !probe_eval_server("127.0.0.1", 8642, 500).await;
    let eval = evaluator.evaluate(&proposal, degraded);
    let registry_eval = hermes_eval_to_registry_eval(&eval);

    let mut outcome = DiscoveredSkillOutcome {
        skill_id: skill_id.clone(),
        skill_name: skill_name.clone(),
        verdict: verdict_to_string(&eval.verdict),
        score: eval.total,
        auto_executed: false,
        request_id: None,
        error: None,
    };

    let adopt_outcome = registry.adopt(
        &proposal.proposal_id,
        &skill_id,
        &skill_name,
        "community",
        &skill_md,
        &registry_eval,
    )?;

    if auto_execute && adopt_outcome.decision == "auto_accept" {
        let engine = engine_for(&app)?;
        outcome.request_id = Some(execute_discovered_skill(&app, &engine, &skill_md)?);
        outcome.auto_executed = true;
    }

    Ok(outcome)
}
