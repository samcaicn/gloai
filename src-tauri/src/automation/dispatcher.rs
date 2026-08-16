// Copyright (c) 2026 tupAI
//
// tupAI P1 §3 — Router for `preferred_execution_type`.
//
// Given a `SkillManifest` (from A2's `skill::manifest`), pick the right
// executor: either launch a system binary or drive a CDP browser.
//
// The dispatcher is intentionally agnostic about how a manifest is
// produced — A2 is the owner of the manifest schema. We just need the
// two fields we care about (preferred_execution_type, software_name,
// browser_url, steps).
//
// On a miss the dispatcher returns a typed `DispatchOutcome` that the
// caller can surface to the UI as an install prompt.

use serde::{Deserialize, Serialize};
use serde_yaml;

use super::browser::{detect_installed_browsers, start_session, SessionMap};
use super::browser_steps::{run_action, ActionResult, BrowserAction};
use super::system_software::{check_software_installed, launch_software};

/// Minimal mirror of A2's `SkillManifest`; we re-declare the fields we
/// consume so this module does not pull in a `skill` dependency that
/// might not exist at integration time. If A2 ships a public struct we
/// can switch to it without changing the dispatcher logic.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DispatchManifest {
    pub name: String,
    #[serde(default)]
    pub preferred_execution_type: ExecutionType,
    #[serde(default)]
    pub software_name: Option<String>,
    #[serde(default)]
    pub browser_url: Option<String>,
    #[serde(default)]
    pub steps: Vec<DispatchStep>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
#[derive(Default)]
pub enum ExecutionType {
    #[default]
    SystemSoftware,
    Browser,
}


#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct DispatchStep {
    #[serde(default)]
    pub id: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub dom_selector: Option<String>,
    #[serde(default)]
    pub visual_target: Option<String>,
    #[serde(default)]
    pub action: Option<BrowserAction>,
}

/// Outcome of a dispatch — the UI layer can switch on this to decide
/// whether to render a "skill ran" toast or an "install missing" prompt.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "status", rename_all = "snake_case")]
pub enum DispatchOutcome {
    Executed {
        execution_type: ExecutionType,
        steps_run: usize,
        action_results: Vec<ActionResult>,
    },
    SoftwareInstallRequired {
        software_name: String,
    },
    BrowserInstallRequired,
    NoSteps,
}

impl DispatchOutcome {
    pub fn is_success(&self) -> bool {
        matches!(self, DispatchOutcome::Executed { .. })
    }
}

/// Parse a `skill.md` source (YAML front matter) into a manifest.
pub fn parse_manifest(source: &str) -> Result<DispatchManifest, String> {
    // We accept the whole source as YAML for now; A2's compiler will
    // eventually split front-matter from body, but the dispatcher only
    // cares about the structured portion.
    serde_yaml::from_str::<DispatchManifest>(source).map_err(|e| e.to_string())
}

/// Route the manifest to the right executor.
///
/// `sessions` is the shared browser-session map; the caller is
/// responsible for installing it via `app.manage()` exactly once.
pub async fn dispatch_skill(
    manifest: &DispatchManifest,
    sessions: &SessionMap,
) -> Result<DispatchOutcome, String> {
    if manifest.steps.is_empty() {
        return Ok(DispatchOutcome::NoSteps);
    }

    match manifest.preferred_execution_type {
        ExecutionType::SystemSoftware => dispatch_system_software(manifest).await,
        ExecutionType::Browser => dispatch_browser(manifest, sessions).await,
    }
}

async fn dispatch_system_software(
    manifest: &DispatchManifest,
) -> Result<DispatchOutcome, String> {
    let name = manifest
        .software_name
        .clone()
        .unwrap_or_else(|| manifest.name.clone());

    if !check_software_installed(&name) {
        return Ok(DispatchOutcome::SoftwareInstallRequired { software_name: name });
    }

    launch_software(&name)?;
    // The actual step execution for native software lives in A2's
    // engine.rs (it uses enigo / screenshots). We report a single
    // success marker so the UI shows "launched".
    Ok(DispatchOutcome::Executed {
        execution_type: ExecutionType::SystemSoftware,
        steps_run: 1,
        action_results: vec![ActionResult {
            action: "launch".into(),
            success: true,
            error: None,
            screenshot_b64: None,
        }],
    })
}

async fn dispatch_browser(
    manifest: &DispatchManifest,
    sessions: &SessionMap,
) -> Result<DispatchOutcome, String> {
    let browsers = detect_installed_browsers();
    let browser = match browsers.into_iter().find(|b| b.installed) {
        Some(b) => b,
        None => return Ok(DispatchOutcome::BrowserInstallRequired),
    };

    let mut session = start_session(&browser.browser_type, None).await?;
    let page = match manifest.browser_url.as_deref() {
        Some(url) if !url.is_empty() => session
            .browser
            .new_page(url)
            .await
            .map_err(|e| format!("导航失败: {}", e))?,
        _ => session
            .browser
            .new_page("about:blank")
            .await
            .map_err(|e| format!("新建页面失败: {}", e))?,
    };
    session.current_page = Some(page.clone());

    // Stash the session so subsequent commands can find it.
    let session_id = uuid::Uuid::new_v4().to_string();
    {
        let mut map = sessions.lock().await;
        map.insert(session_id.clone(), session);
    }

    let mut results = Vec::with_capacity(manifest.steps.len());
    for step in &manifest.steps {
        if let Some(action) = &step.action {
            match run_action(&page, action).await {
                Ok(r) => results.push(r),
                Err(e) => {
                    results.push(ActionResult {
                        action: format!("{:?}", action),
                        success: false,
                        error: Some(e),
                        screenshot_b64: None,
                    });
                    // We continue on error so partial progress is visible.
                }
            }
        }
    }

    Ok(DispatchOutcome::Executed {
        execution_type: ExecutionType::Browser,
        steps_run: results.len(),
        action_results: results,
    })
}
