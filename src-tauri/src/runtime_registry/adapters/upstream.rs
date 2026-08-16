// Copyright (c) 2026 tupAI
//
// Upstream runtime adapter (dsh / proma "main feature updates").
//
// This is the integration seam called for in the original design: when an
// upstream project's functionality needs to be wired in, you describe it as
// a `RuntimeInstance` of kind `Upstream` and this adapter drives it — no
// changes to the registry or command surface required.
//
// Two transports are supported, chosen by the instance `endpoint`:
//   * http(s) URL  -> OpenAI-compatible /chat/completions (mirrors CustomApi)
//   * local binary -> subprocess with `cli_args_template` ({prompt}/{cwd})
//
// SECURITY: only http/https endpoints accepted for the HTTP transport; non-
// http(s) upstream endpoints must be existing binary paths. Prompts are
// passed as argv elements (never via a shell) for the subprocess transport.

use async_trait::async_trait;
use reqwest::header::HeaderValue;
use tokio::process::Command;

use crate::runtime_registry::adapter::AgentProviderAdapter;
use crate::runtime_registry::{DetectionResult, InvokeRequest, InvokeResponse, RuntimeInstance, RuntimeKind};

pub struct UpstreamAdapter {
    instance: RuntimeInstance,
}

impl UpstreamAdapter {
    pub fn new(instance: RuntimeInstance) -> Self {
        Self { instance }
    }
}

fn is_http(endpoint: &str) -> bool {
    endpoint.starts_with("http://") || endpoint.starts_with("https://")
}

#[async_trait]
impl AgentProviderAdapter for UpstreamAdapter {
    fn provider_id(&self) -> &str {
        &self.instance.provider_id
    }

    fn kind(&self) -> RuntimeKind {
        RuntimeKind::Upstream
    }

    async fn detect(&self) -> DetectionResult {
        let endpoint = &self.instance.endpoint;
        let is_bin = !endpoint.is_empty() && std::path::Path::new(endpoint).is_file();
        let ok = is_http(endpoint) || is_bin;
        DetectionResult {
            provider_id: self.instance.provider_id.clone(),
            installed: ok,
            version: None,
            binary_path: if is_bin { Some(endpoint.clone()) } else { None },
            error: if ok {
                None
            } else {
                Some("upstream endpoint must be an http(s) URL or an existing binary path".into())
            },
        }
    }

    async fn invoke(&self, req: InvokeRequest) -> Result<InvokeResponse, String> {
        if is_http(&self.instance.endpoint) {
            invoke_http(&self.instance, &req).await
        } else {
            invoke_cli(&self.instance, &req).await
        }
    }

    async fn health(&self) -> bool {
        self.detect().await.installed
    }
}

async fn invoke_http(
    instance: &RuntimeInstance,
    req: &InvokeRequest,
) -> Result<InvokeResponse, String> {
    let url = format!("{}/chat/completions", instance.endpoint.trim_end_matches('/'));
    let model = req
        .model
        .clone()
        .or_else(|| instance.model.clone())
        .unwrap_or_else(|| "default".into());
    let body = serde_json::json!({
        "model": model,
        "messages": [{ "role": "user", "content": req.prompt }],
        "stream": false,
    });
    let client = reqwest::Client::new();
    let mut builder = client.post(&url).json(&body);
    // Optional bearer override carried on the request (env fallback otherwise).
    if let Some(key) = req.api_key.clone() {
        if let Ok(v) = HeaderValue::from_str(&format!("Bearer {key}")) {
            builder = builder.header("Authorization", v);
        }
    }
    let resp = builder.send().await.map_err(|e| format!("request failed: {e}"))?;
    let status = resp.status();
    let text = resp.text().await.map_err(|e| format!("read body failed: {e}"))?;
    if !status.is_success() {
        return Ok(InvokeResponse {
            subagent_id: req.subagent_id.clone(),
            output: String::new(),
            exit_status: Some(status.as_u16() as i32),
            error: Some(text),
        });
    }
    let content = serde_json::from_str::<serde_json::Value>(&text)
        .ok()
        .and_then(|v| {
            v["choices"]
                .get(0)
                .and_then(|c| c["message"]["content"].as_str().map(|s| s.to_string()))
        })
        .unwrap_or(text);
    Ok(InvokeResponse {
        subagent_id: req.subagent_id.clone(),
        output: content,
        exit_status: Some(0),
        error: None,
    })
}

async fn invoke_cli(
    instance: &RuntimeInstance,
    req: &InvokeRequest,
) -> Result<InvokeResponse, String> {
    let cwd = req
        .workspace_path
        .clone()
        .or_else(|| std::env::current_dir().ok().map(|p| p.to_string_lossy().to_string()))
        .unwrap_or_default();

    let template = instance
        .cli_args_template
        .clone()
        .unwrap_or_else(|| vec!["{prompt}".into()]);

    let mut cmd = Command::new(&instance.endpoint);
    for a in &template {
        let substituted = a.replace("{prompt}", &req.prompt).replace("{cwd}", &cwd);
        cmd.arg(substituted);
    }
    if !cwd.is_empty() {
        cmd.current_dir(&cwd);
    }
    let output = cmd
        .output()
        .await
        .map_err(|e| format!("failed to spawn {}: {e}", instance.endpoint))?;
    let stdout = String::from_utf8_lossy(&output.stdout).to_string();
    let stderr = String::from_utf8_lossy(&output.stderr).to_string();
    let status = output.status.code();
    if !output.status.success() {
        return Ok(InvokeResponse {
            subagent_id: req.subagent_id.clone(),
            output: stdout,
            exit_status: status,
            error: Some(stderr),
        });
    }
    Ok(InvokeResponse {
        subagent_id: req.subagent_id.clone(),
        output: stdout,
        exit_status: status,
        error: None,
    })
}
