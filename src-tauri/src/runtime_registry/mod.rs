// Copyright (c) 2026 tupAI
//
// runtime_registry — unified agent-runtime registry (the "dsh-bridge" evolution).
//
// Goal (mirrors Multica's unified-runtime + agents-as-teammates model):
//   1. `which`-detect locally installed coding-agent CLIs
//      (opencode / claude / codex / kimi / trae) on startup.
//   2. Each detected CLI is wrapped by a minimal `AgentProviderAdapter`
//      and auto-registered as a callable sub-agent named `<app><n>`
//      (e.g. `claude1`, `opencode1`).
//   3. Users can add their own agent backends via an HTTP API
//      (OpenAI-compatible chat completions).
//   4. Upstream runtimes (dsh / proma "main feature updates") plug in
//      through the same `AgentProviderAdapter` seam — add a variant +
//      one arm in `adapters::build_adapter`, nothing else.
//
// This module deliberately reuses the existing `crate::acp` transport
// layer for ACP-compatible CLIs (claude-code / codex / opencode) and
// shells out via `tokio::process` only for non-ACP CLIs (kimi / trae).

pub mod adapter;
pub mod adapters;
pub mod commands;
pub mod detect;
pub mod registry;

use serde::{Deserialize, Serialize};
use std::sync::Arc;

pub use adapter::AgentProviderAdapter;
pub use registry::RuntimeRegistry;

/// Runtime backend category. Drives which `AgentProviderAdapter` is built.
#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum RuntimeKind {
    /// ACP stdio JSON-RPC CLI (claude-code / codex / opencode / omp).
    Acp,
    /// Plain subprocess with a run/`--print` mode (kimi / trae / generic).
    CliRun,
    /// User-supplied HTTP API (OpenAI-compatible chat completions).
    CustomApi,
    /// Upstream runtimes (dsh / proma "main feature updates") plug in through
    /// the same `AgentProviderAdapter` seam — driven inline by
    /// `adapters::upstream` (http(s) endpoint or local binary).
    Upstream,
}

/// Static description of a provider we know how to detect / drive.
#[derive(Clone, Debug)]
pub struct ProviderSpec {
    pub id: &'static str,
    /// Primary binary name used for `which` detection.
    pub binary: &'static str,
    /// Fallback binary names to also probe (e.g. `trae-cli` vs `trae`).
    pub aliases: &'static [&'static str],
    pub display_name: &'static str,
    pub kind: RuntimeKind,
    /// For `Acp`: the `crate::acp` client id (matches BuiltinAcpClientPreset.id).
    pub acp_client_id: Option<&'static str>,
    /// For `CliRun` / `Upstream` subprocess: argv template; `{prompt}` and
    /// `{cwd}` are substituted at invoke time.
    pub cli_args_template: &'static [&'static str],
}

/// Result of probing one provider's availability.
#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct DetectionResult {
    pub provider_id: String,
    pub installed: bool,
    #[serde(default)]
    pub version: Option<String>,
    #[serde(default)]
    pub binary_path: Option<String>,
    #[serde(default)]
    pub error: Option<String>,
}

/// A concrete, addressable runtime backend (detected OR user-added).
#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeInstance {
    pub id: String,
    pub provider_id: String,
    pub kind: RuntimeKind,
    pub display_name: String,
    /// For CLI kinds: resolved binary path. For CustomApi: base URL.
    pub endpoint: String,
    pub installed: bool,
    #[serde(default)]
    pub version: Option<String>,
    /// CustomApi only: model id to hit.
    #[serde(default)]
    pub model: Option<String>,
    /// CustomApi only: whether an API key is configured (never the secret).
    #[serde(default)]
    pub has_api_key: bool,
    /// CliRun / Upstream subprocess: argv template; `{prompt}`/`{cwd}`
    /// substituted at invoke. Authoritative source is `detect`/`register`.
    #[serde(default)]
    pub cli_args_template: Option<Vec<String>>,
    /// ACP only: the `crate::acp` client id that actually drives this
    /// provider (e.g. built-in preset `claude-code`). Mirrors the preset
    /// id, NOT the registry `provider_id` (`claude`). Used to spin up the
    /// right CLI when discovering models / invoking.
    #[serde(default)]
    pub acp_client_id: Option<String>,
    /// ACP only: model ids reported by the CLI at `session/new` — exactly
    /// how the exe discovers its own model list. Populated by
    /// `RuntimeRegistry::discover_models`. Empty until discovered.
    #[serde(default)]
    pub available_models: Vec<String>,
}

/// A callable sub-agent surfaced to the chat UI, named `<app><n>`.
#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct SubAgent {
    /// e.g. `claude1`, `opencode1`, `myapi1`.
    pub id: String,
    pub display_name: String,
    /// Backing runtime instance id.
    pub instance_id: String,
    pub provider_id: String,
    pub kind: RuntimeKind,
    pub status: SubAgentStatus,
    /// ACP only: model id discovered the same way the exe does — via
    /// `session/new` `models.current_model_id`. `None` until discovered.
    #[serde(default)]
    pub model: Option<String>,
    /// ACP only: candidate model ids reported by the CLI. Mirrors
    /// `RuntimeInstance::available_models`.
    #[serde(default)]
    pub available_models: Vec<String>,
}

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum SubAgentStatus {
    Available,
    Busy,
    Offline,
}

/// Payload to invoke a sub-agent (one-shot prompt).
#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct InvokeRequest {
    pub subagent_id: String,
    pub prompt: String,
    #[serde(default)]
    pub workspace_path: Option<String>,
    #[serde(default)]
    pub model: Option<String>,
    #[serde(default)]
    pub timeout_seconds: Option<u64>,
    /// Optional API-key override (CustomApi). Takes precedence over the
    /// env fallback `RR_API_KEY_<instance_id>`. Never serialized to disk.
    #[serde(default)]
    pub api_key: Option<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct InvokeResponse {
    pub subagent_id: String,
    pub output: String,
    #[serde(default)]
    pub exit_status: Option<i32>,
    #[serde(default)]
    pub error: Option<String>,
}

/// Public view returned to the frontend.
#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeRegistrySnapshot {
    pub instances: Vec<RuntimeInstance>,
    pub subagents: Vec<SubAgent>,
}

pub type SharedRuntimeRegistry = Arc<RuntimeRegistry>;
