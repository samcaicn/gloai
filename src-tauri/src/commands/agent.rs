// Copyright (c) 2026 MeeJoy

// Agent, skills, toolsets queries
// Handles: agent list, skill management, toolset discovery

// Agent, skills, toolsets query commands migrated from `legacy.rs`.
// The shared skill/toolset helpers (`collect_installed_skills`,
// `load_installed_skill_detail`, `run_hermes_command`, …) and the
// `SkillInfo` / `SkillDetail` / `ToolsetInfo` structs still live in
// `legacy.rs` because they are reused by `get_market_skills` etc.; we
// pull them in via `use super::legacy::…`.

use serde::{Deserialize, Serialize};
use std::sync::Mutex;

use super::legacy::{
    collect_installed_skills, extract_disabled_skills, load_hermes_config_yaml,
    load_installed_skill_detail, parse_toolsets_list, run_hermes_command, save_disabled_skills,
    SkillDetail, SkillInfo, ToolsetInfo,
};

// ---------------------------------------------------------------------------
// Agent registry
// ---------------------------------------------------------------------------

#[derive(Serialize, Deserialize, Clone)]
pub struct Agent {
    pub id: String,
    pub name: String,
    pub description: String,
}

static AGENTS: std::sync::LazyLock<Mutex<Vec<Agent>>> = std::sync::LazyLock::new(|| {
    Mutex::new(vec![Agent {
        id: "hermes-agent".to_string(),
        name: "Hermes Agent".to_string(),
        description: "默认助手".to_string(),
    }])
});

#[tauri::command]
pub fn get_agents() -> Result<Vec<Agent>, String> {
    let agents = AGENTS.lock().map_err(|e| e.to_string())?;
    Ok(agents.clone())
}

// ---------------------------------------------------------------------------
// Skill & toolset queries
// ---------------------------------------------------------------------------

#[tauri::command]
pub fn get_skills() -> Result<Vec<SkillInfo>, String> {
    collect_installed_skills()
}

#[tauri::command]
pub fn get_skill_detail(name: String) -> Result<SkillDetail, String> {
    load_installed_skill_detail(&name)
}

#[tauri::command]
pub fn toggle_skill(name: String, enabled: bool) -> Result<SkillInfo, String> {
    let mut disabled_skills = extract_disabled_skills(&load_hermes_config_yaml()?);

    if enabled {
        disabled_skills.remove(&name);
    } else {
        disabled_skills.insert(name.clone());
    }

    save_disabled_skills(&disabled_skills)?;
    load_installed_skill_detail(&name).map(|detail| detail.skill)
}

#[tauri::command]
pub fn get_toolsets() -> Result<Vec<ToolsetInfo>, String> {
    let result = run_hermes_command(&["tools", "list"])?;
    if !result.success {
        return Err(if result.stderr.is_empty() {
            result.stdout
        } else {
            result.stderr
        });
    }

    Ok(parse_toolsets_list(&result.stdout))
}

// ---------------------------------------------------------------------------
// Tauri command surface for the 5-dimensional server-side skill
// scoring engine.
//
// The actual scoring engine lives in
// `crate::hermes::skill_evaluator::SkillEvaluator`. This file only
// exposes the Tauri command and threads a shared `HermesAppState`
// through it.
//
// The proposal type is intentionally aliased to the re-export that
// lives inside the evaluator module; the canonical
// `crate::skill::proposal` will be the long-term home, at which
// point the alias should be flipped to point there.
// ---------------------------------------------------------------------------

/// Canonical path to the proposal type. Defined here as an alias so
/// the command signature stays stable when the canonical proposal
/// module lands.
#[allow(dead_code)]
// Reserved for the v4 §2.1 server-eval command; the live Tauri
// surface re-exports this alias when the canonical module lands.
pub type Proposal = crate::hermes::skill_evaluator::proposal::SkillProposal;

/// Tauri command: 5-dimensional server-side evaluation of a single
/// `SkillProposal`. Returns the full `SkillEvaluation` (scores, total,
/// verdict, issues, timestamp, `degraded` flag).
///
/// Front-end wrapper: `src/api/server-eval.js#evaluateProposal`.
#[allow(dead_code)]
#[tauri::command]
pub async fn evaluate_skill_proposal(
    _state: tauri::State<'_, crate::hermes::HermesAppState>,
    skill_db: tauri::State<'_, crate::skill::memory::SkillDb>,
    proposal: Proposal,
) -> Result<crate::hermes::skill_evaluator::SkillEvaluation, String> {
    // Build a per-call dedup index. A real implementation will
    // hydrate this from the SQLite-backed skill registry owned by
    // the SkillMemory module; for now we start empty so the first
    // ever proposal is always "novel".
    let dedup = crate::hermes::dedup_index::DedupIndex::new();

    // Probe the upstream evaluation server (127.0.0.1:8642). If it
    // doesn't accept a TCP connection within 500ms we run the local
    // heuristic pass and mark the result `degraded = true`. This is
    // the only place the command performs I/O; everything else is
    // pure.
    let degraded = !probe_eval_server("127.0.0.1", 8642, 500).await;

    let evaluator = crate::hermes::skill_evaluator::SkillEvaluator::new(&dedup)
        .with_skill_db(&skill_db, None, None);
    Ok(evaluator.evaluate(&proposal, degraded))
}

/// Lightweight reachability probe. We do a `TcpStream::connect` with
/// a hard deadline; any error or timeout is treated as "server down".
/// The transport layer will replace this with a proper
/// HTTP HEAD / WS handshake later, but TCP-connect is enough to keep
/// the fallback path correct during early development.
#[allow(dead_code)]
async fn probe_eval_server(host: &str, port: u16, timeout_ms: u64) -> bool {
    use std::net::ToSocketAddrs;
    use std::time::Duration;
    use tokio::net::TcpStream;

    let addr = match (host, port).to_socket_addrs() {
        Ok(mut addrs) => addrs.next(),
        Err(_) => None,
    };
    let Some(addr) = addr else { return false; };
    let res = tokio::time::timeout(
        Duration::from_millis(timeout_ms),
        TcpStream::connect(addr),
    )
    .await;
    matches!(res, Ok(Ok(_)))
}
