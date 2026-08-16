// Copyright (c) 2026 tupAI
//
// User-supplied HTTP agent backend (OpenAI-compatible /chat/completions).
//
// SECURITY: only http/https endpoints are accepted. Reject non-http(s)
// schemes to avoid file:// / gopher:// style SSRF via user config. The
// API key is read from instance metadata at runtime and never logged.

use async_trait::async_trait;
use reqwest::header::HeaderValue;

use crate::runtime_registry::adapter::AgentProviderAdapter;
use crate::runtime_registry::{DetectionResult, InvokeRequest, InvokeResponse, RuntimeInstance, RuntimeKind};

/// Normalize an instance id into the env var name holding its API key.
fn api_key_env(instance_id: &str) -> String {
    format!(
        "RR_API_KEY_{}",
        instance_id.to_uppercase().replace(['-', '.'], "_")
    )
}

pub struct CustomApiAdapter {
    instance: RuntimeInstance,
    endpoint: String,
    model: String,
    api_key: Option<String>,
}

impl CustomApiAdapter {
    pub fn new(instance: RuntimeInstance) -> Self {
        let endpoint = instance.endpoint.trim_end_matches('/').to_string();
        let model = instance.model.clone().unwrap_or_else(|| "default".into());
        // Key is supplied out-of-band (env var keyed by instance id); an
        // explicit `api_key` override at invoke time takes precedence.
        let api_key = std::env::var(api_key_env(&instance.id)).ok();
        Self {
            instance,
            endpoint,
            model,
            api_key,
        }
    }
}

#[async_trait]
impl AgentProviderAdapter for CustomApiAdapter {
    fn provider_id(&self) -> &str {
        &self.instance.provider_id
    }

    fn kind(&self) -> RuntimeKind {
        RuntimeKind::CustomApi
    }

    async fn detect(&self) -> DetectionResult {
        let ok = self.endpoint.starts_with("http://") || self.endpoint.starts_with("https://");
        DetectionResult {
            provider_id: self.instance.provider_id.clone(),
            installed: ok,
            version: None,
            binary_path: Some(self.endpoint.clone()),
            error: if ok {
                None
            } else {
                Some("endpoint must be http(s)".into())
            },
        }
    }

    async fn invoke(&self, req: InvokeRequest) -> Result<InvokeResponse, String> {
        let url = format!("{}/chat/completions", self.endpoint);
        let body = serde_json::json!({
            "model": req.model.clone().unwrap_or_else(|| self.model.clone()),
            "messages": [{ "role": "user", "content": req.prompt }],
            "stream": false,
        });
        let api_key = req.api_key.clone().or_else(|| self.api_key.clone());
        let client = reqwest::Client::new();
        let mut builder = client.post(url.as_str()).json(&body);
        if let Some(key) = api_key {
            if let Ok(v) = HeaderValue::from_str(&format!("Bearer {key}")) {
                builder = builder.header("Authorization", v);
            }
        }
        let resp = builder
            .send()
            .await
            .map_err(|e| format!("request failed: {e}"))?;
        let status = resp.status();
        let text = resp
            .text()
            .await
            .map_err(|e| format!("read body failed: {e}"))?;
        if !status.is_success() {
            return Ok(InvokeResponse {
                subagent_id: req.subagent_id,
                output: String::new(),
                exit_status: Some(status.as_u16() as i32),
                error: Some(text),
            });
        }
        // Best-effort extract the first choice's content.
        let content = serde_json::from_str::<serde_json::Value>(&text)
            .ok()
            .and_then(|v| {
                v["choices"]
                    .get(0)
                    .and_then(|c| c["message"]["content"].as_str().map(|s| s.to_string()))
            })
            .unwrap_or(text);
        Ok(InvokeResponse {
            subagent_id: req.subagent_id,
            output: content,
            exit_status: Some(0),
            error: None,
        })
    }

    async fn health(&self) -> bool {
        self.detect().await.installed
    }
}
