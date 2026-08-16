// Copyright (c) 2026 tupAI
//
// BrowserSkill action vocabulary + result envelope.
//
// BrowserSkill (Tencent, https://github.com/Tencent/BrowserSkill) is a
// local CLI (`bsk`) + Chrome/Edge extension bridge that lets an AI Agent
// drive the user's *already-logged-in* real browser. It is NOT a
// perception primitive and does NOT replace the CDP tier in the
// `CDP -> UIA -> OCR -> VLM` router cascade — it is a complementary,
// high-level "browser agent driver" capability.
//
// This module defines the typed action set we surface over IPC and the
// `BskCliBackend` maps each variant to a `bsk ...` subprocess call. A
// `Raw` variant is provided so the front-end / skill layer can pass
// through exact `bsk` arguments for whatever version is installed
// (BrowserSkill is pre-1.0 and its CLI grammar shifts between releases).

use serde::{Deserialize, Serialize};

/// A single BrowserSkill action the backend dispatches to `bsk`.
///
/// Serde is camelCase and tagged by `action` so the IPC wire format
/// stays consistent with the rest of the `pc_automation` command surface.
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase", tag = "action")]
pub enum BrowserSkillAction {
    /// Navigate the Agent Window to a URL.
    /// -> `bsk navigate --url <url>`
    Navigate { url: String },
    /// Click an element matched by a CSS selector.
    /// -> `bsk click --selector <selector>`
    Click { selector: String },
    /// Type text into an element matched by a CSS selector.
    /// -> `bsk input --selector <selector> --value <text>`
    Type { selector: String, text: String },
    /// Scroll the page.
    /// -> `bsk scroll --direction <direction> --amount <amount>`
    Scroll { direction: String, amount: Option<u32> },
    /// Capture a screenshot of the current Agent Window.
    /// -> `bsk screenshot --output <output>`
    Screenshot { output: String },
    /// Extract structured content from the page using a query.
    /// -> `bsk extract --query <query>`
    Extract { query: String },
    /// Run a named BrowserSkill skill with key/value parameters.
    /// -> `bsk run <name> --<key> <value> ...`
    RunSkill {
        name: String,
        params: std::collections::HashMap<String, String>,
    },
    /// Execute an arbitrary browser script in the page context.
    /// -> `bsk evaluate --script <script>`
    Evaluate { script: String },
    /// Pass-through: run `bsk` with exact arguments (no mapping).
    /// Use this for operations the typed variants don't cover yet.
    Raw { args: Vec<String> },
}

impl BrowserSkillAction {
    /// Build the exact `bsk` argument vector for this action.
    ///
    /// NOTE: BrowserSkill's CLI grammar is pre-1.0 and changes between
    /// releases. The exact flag names below were derived from the 0.1.x
    /// docs; if a call fails, adjust the mapping here in ONE place. The
    /// `Raw` variant bypasses this entirely for forward-compat.
    pub fn to_bsk_args(&self) -> Vec<String> {
        match self {
            BrowserSkillAction::Navigate { url } => {
                vec!["navigate".into(), "--url".into(), url.clone()]
            }
            BrowserSkillAction::Click { selector } => {
                vec!["click".into(), "--selector".into(), selector.clone()]
            }
            BrowserSkillAction::Type { selector, text } => vec![
                "input".into(),
                "--selector".into(),
                selector.clone(),
                "--value".into(),
                text.clone(),
            ],
            BrowserSkillAction::Scroll { direction, amount } => {
                let mut v = vec!["scroll".into(), "--direction".into(), direction.clone()];
                if let Some(a) = amount {
                    v.push("--amount".into());
                    v.push(a.to_string());
                }
                v
            }
            BrowserSkillAction::Screenshot { output } => {
                vec!["screenshot".into(), "--output".into(), output.clone()]
            }
            BrowserSkillAction::Extract { query } => {
                vec!["extract".into(), "--query".into(), query.clone()]
            }
            BrowserSkillAction::RunSkill { name, params } => {
                let mut v = vec!["run".into(), name.clone()];
                for (k, val) in params {
                    v.push(format!("--{}", k));
                    v.push(val.clone());
                }
                v
            }
            BrowserSkillAction::Evaluate { script } => {
                vec!["evaluate".into(), "--script".into(), script.clone()]
            }
            BrowserSkillAction::Raw { args } => args.clone(),
        }
    }
}

/// Result envelope returned by every `exec`. Mirrors `CdpResult` so the
/// IPC surface can ferry errors verbatim without re-encoding them.
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct BrowserSkillResult {
    pub success: bool,
    pub stdout: String,
    pub stderr: String,
    pub exit_code: i32,
    pub latency_ms: u64,
}

/// Aggregate runtime status surfaced to the front-end so it can drive
/// BrowserSkill onboarding: install the CLI → install the extension →
/// ready. Non-destructive — only runs `bsk --version` + `bsk doctor`.
///
/// The browser extension CANNOT be auto-installed (Chrome/Edge block
/// silent injection). When `needsExtension` is true the front-end must
/// deep-link `extensionStoreUrl` and ask the user to click "Add".
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct BrowserSkillStatus {
    /// `bsk` CLI present and runnable.
    pub cli_installed: bool,
    /// Version string when `cliInstalled`, e.g. `bsk 0.1.10`.
    pub cli_version: Option<String>,
    /// `bsk doctor` reports the local daemon is running.
    pub daemon_running: bool,
    /// `bsk doctor` reports the browser extension is connected.
    pub extension_connected: bool,
    /// CLI missing — front-end should call `browser_skill_setup` (auto-install).
    pub needs_setup: bool,
    /// CLI ok but extension not connected — front-end should deep-link
    /// `extensionStoreUrl` and prompt the user to add the extension.
    pub needs_extension: bool,
    /// Chrome Web Store URL for the BrowserSkill extension (deep-link).
    pub extension_store_url: String,
    /// Last error message (CLI missing / install failed), if any.
    pub error: Option<String>,
}
