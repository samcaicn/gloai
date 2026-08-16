// Copyright (c) 2026 tupAI
//
// Subprocess adapter for non-ACP CLIs (kimi / trae / generic `--print`).
//
// SECURITY: prompts are passed as a single argv element via
// `Command::arg(...)` — NEVER through a shell. Shell interpolation would
// be a command-injection vector. The CLI's own auth/credentials are read
// from its own config/env; this adapter never handles secrets.

use async_trait::async_trait;
use tokio::process::Command;

use crate::runtime_registry::adapter::AgentProviderAdapter;
use crate::runtime_registry::{DetectionResult, InvokeRequest, InvokeResponse, RuntimeInstance, RuntimeKind};

pub struct CliRunAdapter {
    instance: RuntimeInstance,
    /// Resolved binary path (endpoint field holds it for CLI kinds).
    binary: String,
    /// argv after the binary; `{prompt}` and `{cwd}` substituted at invoke.
    args_template: Vec<String>,
}

impl CliRunAdapter {
    pub fn new(instance: RuntimeInstance) -> Self {
        // Prefer the authoritative template carried on the instance
        // (from `detect::builtin_provider_specs`); fall back to a per-provider
        // default for unknown CLIs. Tuning lives in `detect`.
        let args_template = instance
            .cli_args_template
            .clone()
            .unwrap_or_else(|| default_cli_args(&instance.provider_id));
        Self {
            binary: instance.endpoint.clone(),
            instance,
            args_template,
        }
    }
}

fn default_cli_args(provider_id: &str) -> Vec<String> {
    match provider_id {
        "kimi" => vec!["-p".into(), "{prompt}".into()],
        "trae" => vec!["run".into(), "{prompt}".into(), "--working-dir".into(), "{cwd}".into()],
        _ => vec!["{prompt}".into()],
    }
}

#[async_trait]
impl AgentProviderAdapter for CliRunAdapter {
    fn provider_id(&self) -> &str {
        &self.instance.provider_id
    }

    fn kind(&self) -> RuntimeKind {
        RuntimeKind::CliRun
    }

    async fn detect(&self) -> DetectionResult {
        DetectionResult {
            provider_id: self.instance.provider_id.clone(),
            installed: self.instance.installed,
            version: self.instance.version.clone(),
            binary_path: Some(self.binary.clone()),
            error: None,
        }
    }

    async fn invoke(&self, req: InvokeRequest) -> Result<InvokeResponse, String> {
        let cwd = req
            .workspace_path
            .clone()
            .or_else(|| std::env::current_dir().ok().map(|p| p.to_string_lossy().to_string()))
            .unwrap_or_default();

        let mut cmd = Command::new(&self.binary);
        for a in &self.args_template {
            let substituted = a
                .replace("{prompt}", &req.prompt)
                .replace("{cwd}", &cwd);
            cmd.arg(substituted);
        }
        if !cwd.is_empty() {
            cmd.current_dir(&cwd);
        }
        // Capture stdout; no shell — prompt is a single argv element.
        let output = cmd
            .output()
            .await
            .map_err(|e| format!("failed to spawn {}: {e}", self.binary))?;
        let stdout = String::from_utf8_lossy(&output.stdout).to_string();
        let stderr = String::from_utf8_lossy(&output.stderr).to_string();
        let status = output.status.code();
        if !output.status.success() {
            return Ok(InvokeResponse {
                subagent_id: req.subagent_id,
                output: stdout,
                exit_status: status,
                error: Some(stderr),
            });
        }
        Ok(InvokeResponse {
            subagent_id: req.subagent_id,
            output: stdout,
            exit_status: status,
            error: None,
        })
    }

    async fn health(&self) -> bool {
        self.instance.installed
    }
}
