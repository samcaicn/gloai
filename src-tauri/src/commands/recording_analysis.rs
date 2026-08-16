// Copyright (c) 2026 MeeJoy
//
// Recording post-analysis — inspired by Understudy's teach-mode post-processing.
//
// Key design difference from the deleted enhanced_recording module:
//   - NO video recording, NO dual-track, NO evidence pack from video.
//   - Works ON TOP of the existing recording pipeline (CDP + UIA + rdev).
//   - After `stop_recording` writes flowchart + events to the recording store,
//     the user can optionally trigger AI analysis to extract:
//     1. Intent (task title + objective + parameter slots)
//     2. Route options (skill → browser → shell → gui, preferred/fallback)
//     3. GUI replay hints (last resort, re-grounded from current UI state)
//   - Supports multi-turn clarification dialogue to refine the analysis.
//   - Supports publishing as a three-layer SKILL.md.
//
// Architecture:
//   analyze_recording(app_name)  → reads stored flowchart → builds semantic context → LLM (stub)
//   get_analysis_status(app_name) → poll for progress / result
//   refine_analysis(app_name, msg) → clarification round
//   publish_analyzed_skill(app_name, name?) → compile + persist three-layer SKILL.md

use std::collections::HashMap;
use std::sync::Mutex;

use base64::Engine;
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter, State};

use crate::automation::flowchart::Flowchart;

// ── Analysis state ───────────────────────────────────────────────

/// Global analysis state, keyed by `app_name`.
/// One analysis at a time per app — calling `analyze_recording` again overwrites.
pub struct RecordingAnalysisState {
    sessions: Mutex<HashMap<String, AnalysisSession>>,
}

impl RecordingAnalysisState {
    pub fn new() -> Self {
        Self {
            sessions: Mutex::new(HashMap::new()),
        }
    }
}

impl Default for RecordingAnalysisState {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Clone, Debug)]
struct AnalysisSession {
    app_name: String,
    flowchart: Flowchart,
    state: AnalysisState,
    result: Option<AnalysisResult>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase", tag = "state")]
pub enum AnalysisState {
    Pending,
    Analyzing { message: String },
    Completed,
    Failed { message: String },
}

// ── Analysis result types ────────────────────────────────────────
//
// These types are adapted from Understudy's VideoTeachAnalysis,
// but stripped of video-specific fields (keyframes, episodes, etc.).
// The "evidence" is the event track (CDP + UIA events), not video.

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AnalysisResult {
    pub title: String,
    pub objective: String,
    pub task_kind: String,
    pub parameter_slots: Vec<ParameterSlot>,
    pub success_criteria: Vec<String>,
    pub open_questions: Vec<String>,
    pub steps: Vec<AnalyzedStep>,
    pub route_options: Vec<RouteOption>,
    pub preferred_routes: Vec<String>,
    pub provider: String,
    pub model: String,
    pub event_count: u32,
    pub summary: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ParameterSlot {
    pub name: String,
    pub label: String,
    pub sample_value: Option<String>,
    pub required: bool,
    pub notes: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AnalyzedStep {
    pub route: String,
    pub tool_name: String,
    pub instruction: String,
    pub summary: Option<String>,
    pub target: Option<String>,
    pub app: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RouteOption {
    pub id: String,
    pub step_index: u32,
    pub route: String,
    pub preference: String,
    pub instruction: String,
}

// ── Commands ─────────────────────────────────────────────────────

/// Trigger AI analysis on the last completed recording for `app_name`.
///
/// Reads the stored flowchart (written by `stop_recording`), builds a semantic
/// context from the event nodes, and runs analysis (stub for now — Phase 2
/// will wire in LLM).
///
/// Returns immediately with a "pending" status; the frontend polls
/// `get_analysis_status` for progress.
#[tauri::command]
pub fn analyze_recording(
    app_name: String,
    state: State<'_, RecordingAnalysisState>,
) -> Result<serde_json::Value, String> {
    // Read the stored flowchart for this app.
    let flowchart = {
        let fc_val = crate::recording::store::read_app_flowchart(&app_name)
            .ok_or_else(|| format!("No recording found for '{}'. Record first, then analyze.", app_name))?;
        serde_json::from_value::<Flowchart>(fc_val)
            .map_err(|e| format!("Failed to parse stored flowchart: {}", e))?
    };

    let action_count = flowchart.nodes.iter()
        .filter(|n| matches!(n.action.as_deref(), Some("click") | Some("type") | Some("hotkey")))
        .count() as u32;

    if action_count == 0 {
        return Err(format!("Recording for '{}' has no action nodes to analyze.", app_name));
    }

    // Build a stub analysis from the flowchart structure.
    // Phase 2: replace with LLM call that takes events + flowchart as context.
    let result = build_stub_analysis(&app_name, &flowchart, action_count);

    let session = AnalysisSession {
        app_name: app_name.clone(),
        flowchart,
        state: AnalysisState::Completed,
        result: Some(result),
    };

    {
        let mut sessions = state.sessions.lock().map_err(|e| format!("lock error: {}", e))?;
        sessions.insert(app_name.clone(), session);
    }

    log::info!(
        "[recording-analysis] analysis completed for '{}' ({} action nodes)",
        app_name,
        action_count
    );

    Ok(serde_json::json!({
        "appName": app_name,
        "state": "completed",
    }))
}

/// Get the current analysis status and result for `app_name`.
#[tauri::command]
pub fn get_analysis_status(
    app_name: String,
    state: State<'_, RecordingAnalysisState>,
) -> Result<serde_json::Value, String> {
    let sessions = state.sessions.lock().map_err(|e| format!("lock error: {}", e))?;
    let session = sessions.get(&app_name)
        .ok_or_else(|| format!("No analysis session for '{}'. Call analyze_recording first.", app_name))?;

    let state_val = match &session.state {
        AnalysisState::Pending => serde_json::json!({ "state": "pending", "message": "Waiting..." }),
        AnalysisState::Analyzing { message } => serde_json::json!({ "state": "analyzing", "message": message }),
        AnalysisState::Completed => serde_json::json!({ "state": "completed" }),
        AnalysisState::Failed { message } => serde_json::json!({ "state": "failed", "message": message }),
    };

    let analysis_val = session.result.as_ref()
        .map(|r| serde_json::to_value(r).unwrap_or_default());

    Ok(serde_json::json!({
        "status": state_val,
        "analysis": analysis_val,
    }))
}

/// Refine analysis through clarification dialogue.
///
/// Phase 2: will call LLM with current analysis + user message.
/// For now, returns the current analysis unchanged with a stub reply.
#[tauri::command]
pub fn refine_analysis(
    app_name: String,
    message: String,
    state: State<'_, RecordingAnalysisState>,
) -> Result<serde_json::Value, String> {
    let mut sessions = state.sessions.lock().map_err(|e| format!("lock error: {}", e))?;
    let session = sessions.get_mut(&app_name)
        .ok_or_else(|| format!("No analysis session for '{}'.", app_name))?;

    let analysis = session.result.clone().ok_or("No analysis result to refine")?;

    // Phase 2: call LLM with analysis context + user message.
    let reply = format!(
        "Received your feedback: '{}'. AI-powered refinement will be available in the next phase.",
        message
    );

    Ok(serde_json::json!({
        "analysis": serde_json::to_value(&analysis).unwrap_or_default(),
        "reply": reply,
        "hasOpenQuestions": !analysis.open_questions.is_empty(),
    }))
}

/// Publish the analyzed recording as a three-layer SKILL.md.
///
/// Three layers (adapted from Understudy, not copied):
///   1. Intent procedure — natural language steps from analysis
///   2. Route options — preferred/fallback per step
///   3. GUI replay hints — coordinates + element info from original events
#[tauri::command]
pub fn publish_analyzed_skill(
    app: AppHandle,
    app_name: String,
    skill_name: Option<String>,
    state: State<'_, RecordingAnalysisState>,
) -> Result<serde_json::Value, String> {
    let sessions = state.sessions.lock().map_err(|e| format!("lock error: {}", e))?;
    let session = sessions.get(&app_name)
        .ok_or_else(|| format!("No analysis session for '{}'.", app_name))?;

    let analysis = session.result.as_ref().ok_or("No analysis result to publish")?;
    let flowchart = &session.flowchart;

    // Generate three-layer SKILL.md from analysis + flowchart.
    let skill_md = build_three_layer_skill_md(&app_name, skill_name.as_deref(), analysis, flowchart);

    // Compile to MCP binary.
    let compiled = crate::skill::compiler::compile_skill_md(&skill_md)
        .map_err(|e| format!("compile MCP failed: {}", e))?;
    let mcp_blob_base64 = base64::engine::general_purpose::STANDARD.encode(&compiled.mcp_binary);

    // Publish as a SkillProposal (source = Manual, since it's user-curated).
    let proposal = crate::skill::proposal::SkillProposal::new(
        crate::skill::proposal::ProposalSource::Manual,
        skill_md.clone(),
        crate::skill::proposal::SkillLineage {
            parent_skill_id: None,
            parent_version: None,
            derivation_note: Some(format!(
                "AI-analyzed recording for '{}' ({} steps, {} routes)",
                app_name,
                analysis.steps.len(),
                analysis.route_options.len()
            )),
        },
        crate::skill::proposal::ProposalTelemetry {
            source_success_rate: 1.0,
            avg_latency_ms: 0,
            sample_size: analysis.steps.len() as u32,
        },
    );

    let conn = crate::skill::proposal_store::open_proposals_db(&app)?;
    crate::skill::proposal_store::save(&conn, &proposal)?;
    let _ = app.emit("proposal-created", &proposal);

    log::info!(
        "[recording-analysis] published skill '{}' for '{}' ({} steps)",
        proposal.proposal_id,
        app_name,
        analysis.steps.len()
    );

    Ok(serde_json::json!({
        "skillId": proposal.proposal_id,
        "skillMd": skill_md,
        "mcpBlobBase64": mcp_blob_base64,
        "published": true,
    }))
}

// ── Helpers ──────────────────────────────────────────────────────

/// Build a stub analysis from the flowchart structure.
///
/// Phase 2 will replace this with an LLM call that takes the events +
/// flowchart as context and produces a richer analysis.
fn build_stub_analysis(app_name: &str, flowchart: &Flowchart, action_count: u32) -> AnalysisResult {
    let steps: Vec<AnalyzedStep> = flowchart.nodes.iter()
        .filter(|n| matches!(n.action.as_deref(), Some("click") | Some("type") | Some("hotkey")))
        .enumerate()
        .map(|(i, n)| AnalyzedStep {
            route: "gui".to_string(),
            tool_name: match n.action.as_deref() {
                Some("click") => "mouse_click".to_string(),
                Some("type") => "keyboard_type".to_string(),
                Some("hotkey") => "keyboard_hotkey".to_string(),
                _ => "unknown".to_string(),
            },
            instruction: n.label.clone(),
            summary: Some(format!("Step {}: {}", i + 1, n.label)),
            target: n.meta.as_ref()
                .and_then(|m| m.get("element"))
                .and_then(|e| e.as_str())
                .map(|s| s.to_string()),
            app: Some(app_name.to_string()),
        })
        .collect();

    let route_options: Vec<RouteOption> = steps.iter().enumerate()
        .map(|(i, s)| RouteOption {
            id: format!("route-{}", i),
            step_index: i as u32,
            route: s.route.clone(),
            preference: "observed".to_string(),
            instruction: s.instruction.clone(),
        })
        .collect();

    AnalysisResult {
        title: format!("Task: {}", flowchart.title),
        objective: format!(
            "Automate {} ({} steps recorded via CDP/UIA event capture).",
            app_name,
            action_count
        ),
        task_kind: "parameterized_workflow".to_string(),
        parameter_slots: vec![],
        success_criteria: vec![format!("All {} steps execute without error", action_count)],
        open_questions: vec![
            "What is the primary goal of this task?".to_string(),
            "Are there any variable parameters (file paths, usernames, etc.)?".to_string(),
        ],
        steps,
        route_options,
        preferred_routes: vec!["skill".to_string(), "browser".to_string(), "shell".to_string(), "gui".to_string()],
        provider: "stub".to_string(),
        model: "stub".to_string(),
        event_count: action_count,
        summary: format!(
            "Recorded {} action(s) for '{}'. AI analysis will be enhanced in Phase 2 with LLM integration.",
            action_count,
            app_name
        ),
    }
}

/// Build a three-layer SKILL.md from analysis + flowchart.
///
/// Layer 1: Intent procedure (natural language steps)
/// Layer 2: Route options (preferred/fallback per step)
/// Layer 3: GUI replay hints (coordinates + element info)
fn build_three_layer_skill_md(
    _app_name: &str,
    skill_name: Option<&str>,
    analysis: &AnalysisResult,
    flowchart: &Flowchart,
) -> String {
    let name = skill_name.unwrap_or(&analysis.title)
        .replace(' ', "_")
        .replace(':', "")
        .to_lowercase();

    let mut md = format!(
        "---\nname: {name}\ndescription: |\n  {objective}\nsteps:\n",
        name = name,
        objective = analysis.objective
    );

    // Layer 1: Intent procedure (natural language steps).
    md.push_str("\n# Intent Procedure\n\n");
    for (i, step) in analysis.steps.iter().enumerate() {
        md.push_str(&format!("{}. {}\n", i + 1, step.instruction));
    }

    // Layer 2: Route options.
    md.push_str("\n# Route Options\n\n");
    md.push_str("| Step | Route | Preference | Instruction |\n");
    md.push_str("|------|-------|------------|-------------|\n");
    for opt in &analysis.route_options {
        md.push_str(&format!(
            "| {} | {} | {} | {} |\n",
            opt.step_index + 1,
            opt.route,
            opt.preference,
            opt.instruction
        ));
    }

    // Layer 3: GUI replay hints (from original flowchart events).
    md.push_str("\n# GUI Replay Hints\n\n");
    md.push_str("| # | Action | Label | Meta |\n");
    md.push_str("|---|--------|-------|------|\n");
    for (i, node) in flowchart.nodes.iter()
        .filter(|n| matches!(n.action.as_deref(), Some("click") | Some("type") | Some("hotkey")))
        .enumerate()
    {
        let meta_str = node.meta.as_ref()
            .map(|m| serde_json::to_string(m).unwrap_or_default())
            .unwrap_or_default();
        md.push_str(&format!(
            "| {} | {} | {} | {} |\n",
            i + 1,
            node.action.as_deref().unwrap_or("?"),
            node.label,
            meta_str
        ));
    }

    // Open questions (for future clarification).
    if !analysis.open_questions.is_empty() {
        md.push_str("\n# Open Questions\n\n");
        for q in &analysis.open_questions {
            md.push_str(&format!("- {}\n", q));
        }
    }

    md
}
