// Copyright (c) 2026 tupAI
//
// ACP-backed adapter. Wraps the existing `crate::acp::AcpClientService`
// (stdio JSON-RPC transport). Does NOT reimplement process management.

use async_trait::async_trait;
use std::path::PathBuf;
use std::sync::Arc;

use crate::acp::AcpClientService;
use crate::runtime_registry::adapter::AgentProviderAdapter;
use crate::runtime_registry::{DetectionResult, InvokeRequest, InvokeResponse, RuntimeInstance, RuntimeKind};

pub struct AcpAdapter {
    instance: RuntimeInstance,
    acp: Arc<AcpClientService>,
    /// Maps our provider id → acp client id (equal for built-ins).
    client_id: String,
}

impl AcpAdapter {
    pub fn new(instance: RuntimeInstance, acp: Arc<AcpClientService>) -> Self {
        // Drive the ACP client by its preset id (e.g. "claude-code"), not the
        // registry `provider_id` ("claude") — `default_config_for_builtin`
        // matches presets by exact id, so the provider_id would never resolve.
        let client_id = instance
            .acp_client_id
            .clone()
            .unwrap_or_else(|| instance.provider_id.clone());
        Self { instance, acp, client_id }
    }
}

#[async_trait]
impl AgentProviderAdapter for AcpAdapter {
    fn provider_id(&self) -> &str {
        &self.instance.provider_id
    }

    fn kind(&self) -> RuntimeKind {
        RuntimeKind::Acp
    }

    async fn detect(&self) -> DetectionResult {
        // Presence is already established by the registry's `which` scan;
        // here we just reflect it. (Could call acp.probe_requirements.)
        DetectionResult {
            provider_id: self.instance.provider_id.clone(),
            installed: self.instance.installed,
            version: self.instance.version.clone(),
            binary_path: if self.instance.endpoint.is_empty() {
                None
            } else {
                Some(self.instance.endpoint.clone())
            },
            error: None,
        }
    }

    async fn invoke(&self, req: InvokeRequest) -> Result<InvokeResponse, String> {
        // Drive a flow session synchronously and return the aggregated
        // assistant text (no event-streaming needed for one-shot invoke).
        let cwd = req
            .workspace_path
            .clone()
            .unwrap_or_else(|| std::env::current_dir().map(|p| p.to_string_lossy().to_string()).unwrap_or_default());
        let text = self
            .acp
            .run_dialog_turn_sync(
                self.client_id.clone(),
                PathBuf::from(cwd),
                req.prompt.clone(),
                req.timeout_seconds,
            )
            .await?;
        Ok(InvokeResponse {
            subagent_id: req.subagent_id,
            output: text,
            exit_status: None,
            error: None,
        })
    }

    async fn health(&self) -> bool {
        self.instance.installed
    }
}
