//! `DeepSeekAdapter`: fetch + SSE against a DeepSeek chat-completions endpoint.

use std::sync::Arc;
use std::time::Duration;

use async_trait::async_trait;
use dsh_core_types::{
    GenerateOptions, LlmError, LlmModelInfo, LlmProviderInfo, LlmResolvedModelInfo,
    ProviderRequestId, CONTEXT_WINDOW_EXCEEDED_CODE, QUOTA_EXCEEDED_CODE,
};
use dsh_runtime_ports::{ChunkStream, CredentialsPort, LlmPort};
use tracing::debug;

use crate::serialize::{serialize_request, RequestDefaults};
use crate::sse::parse_sse;
use crate::translate::translate;
use crate::types::WireErrorBody;

pub const DEFAULT_STREAM_IDLE_TIMEOUT_MS: u64 = 300_000;
pub const DEFAULT_CONTEXT_WINDOW: u32 = 1_000_000;
pub const DEFAULT_MAX_TOKENS: u32 = 256_000;

#[derive(Clone, Debug)]
pub struct DeepSeekCatalogModel {
    pub id: String,
    pub name: Option<String>,
    pub description: Option<String>,
    pub context_window: Option<u32>,
    pub max_tokens: Option<u32>,
}

#[derive(Clone, Debug)]
pub struct DeepSeekConnectionOptions {
    pub base_url: String,
    pub api_key_env: dsh_core_types::CredentialRef,
    pub defaults: RequestDefaults,
    pub max_tokens: u32,
    pub default_context_window: u32,
    pub models: Vec<DeepSeekCatalogModel>,
    pub stream_idle_timeout_ms: u64,
}

#[derive(Clone)]
pub struct DeepSeekAdapterOptions {
    pub connection: DeepSeekConnectionOptions,
    pub credentials: Arc<dyn CredentialsPort>,
}

pub struct DeepSeekAdapter {
    options: DeepSeekAdapterOptions,
    client: reqwest::Client,
}

impl DeepSeekAdapter {
    pub fn new(options: DeepSeekAdapterOptions) -> Result<Self, LlmError> {
        let client = reqwest::Client::builder()
            .timeout(Duration::from_millis(
                options.connection.stream_idle_timeout_ms,
            ))
            .build()
            .map_err(|error| LlmError::new(error.to_string(), "TRANSPORT"))?;
        Ok(Self { options, client })
    }
}

pub fn http_error_code(status: u16, detail: &str) -> &'static str {
    if status == 401 || status == 403 {
        return "AUTH";
    }
    if is_quota_exceeded(detail) {
        return QUOTA_EXCEEDED_CODE;
    }
    if status == 429 {
        return "RATE_LIMIT";
    }
    if status == 400 {
        if is_context_window_exceeded(detail) {
            return CONTEXT_WINDOW_EXCEEDED_CODE;
        }
        return "INVALID_REQUEST";
    }
    if status >= 500 {
        return "SERVER";
    }
    "HTTP_ERROR"
}

fn is_quota_exceeded(detail: &str) -> bool {
    let lower = detail.to_ascii_lowercase();
    lower.contains("quota") || lower.contains("insufficient_quota")
}

fn is_context_window_exceeded(detail: &str) -> bool {
    let lower = detail.to_ascii_lowercase();
    lower.contains("context length") || lower.contains("context_window") || lower.contains("maximum context")
}

fn model_info(provider: &str, model: &DeepSeekCatalogModel) -> LlmModelInfo {
    LlmModelInfo {
        provider: provider.to_string(),
        id: model.id.clone(),
        name: model.name.clone().unwrap_or_else(|| model.id.clone()),
        description: model.description.clone(),
        input_modalities: Some(vec!["text".into()]),
    }
}

#[async_trait]
impl LlmPort for DeepSeekAdapter {
    fn provider_info(&self, provider: &str) -> LlmProviderInfo {
        LlmProviderInfo {
            id: provider.to_string(),
            name: "DeepSeek".into(),
        }
    }

    async fn list_models(&self, provider: &str) -> Result<Vec<LlmModelInfo>, LlmError> {
        Ok(self
            .options
            .connection
            .models
            .iter()
            .map(|model| model_info(provider, model))
            .collect())
    }

    async fn resolve_model(
        &self,
        provider: &str,
        model: &str,
    ) -> Result<LlmResolvedModelInfo, LlmError> {
        let configured = self
            .options
            .connection
            .models
            .iter()
            .find(|entry| entry.id == model);
        let context_window = configured
            .and_then(|entry| entry.context_window)
            .unwrap_or(self.options.connection.default_context_window);
        let default_max_tokens = configured
            .and_then(|entry| entry.max_tokens)
            .unwrap_or(self.options.connection.max_tokens);
        Ok(LlmResolvedModelInfo {
            info: configured
                .map(|entry| model_info(provider, entry))
                .unwrap_or_else(|| LlmModelInfo {
                    provider: provider.to_string(),
                    id: model.to_string(),
                    name: model.to_string(),
                    description: None,
                    input_modalities: Some(vec!["text".into()]),
                }),
            context_window: Some(context_window),
            default_max_tokens: Some(default_max_tokens),
        })
    }

    fn stream(&self, request: GenerateOptions) -> ChunkStream {
        let adapter = self.client.clone();
        let options = self.options.clone();
        Box::pin(async_stream::stream! {
            match stream_once(&adapter, &options, request).await {
                Ok(chunks) => {
                    for chunk in chunks {
                        yield Ok(chunk);
                    }
                }
                Err(error) => yield Err(error),
            }
        })
    }
}

async fn stream_once(
    client: &reqwest::Client,
    options: &DeepSeekAdapterOptions,
    request: GenerateOptions,
) -> Result<Vec<dsh_core_types::StreamChunk>, LlmError> {
    let api_key = options
        .credentials
        .resolve(&options.connection.api_key_env)
        .await?;
    let body = serialize_request(&request, &options.connection.defaults)?;
    let url = format!(
        "{}/chat/completions",
        options.connection.base_url.trim_end_matches('/')
    );
    debug!("deepseek POST {url} model={}", request.model);
    let response = client
        .post(url)
        .bearer_auth(api_key)
        .json(&body)
        .send()
        .await
        .map_err(|error| LlmError::new(error.to_string(), "TRANSPORT"))?;
    let status = response.status();
    let request_id = response
        .headers()
        .get("x-request-id")
        .or_else(|| response.headers().get("x-deepseek-request-id"))
        .and_then(|value| value.to_str().ok())
        .map(ProviderRequestId::new);
    if !status.is_success() {
        let text = response.text().await.unwrap_or_default();
        let parsed: Option<WireErrorBody> = serde_json::from_str(&text).ok();
        let detail = parsed
            .as_ref()
            .and_then(|body| body.error.as_ref())
            .map(|error| {
                [
                    error.code.clone().unwrap_or_default(),
                    error.kind.clone().unwrap_or_default(),
                    error.message.clone().unwrap_or_default(),
                ]
                .join(" ")
            })
            .unwrap_or(text.clone());
        let mut failure = dsh_core_types::LlmFailure::new(
            if detail.trim().is_empty() {
                format!("HTTP {status}")
            } else {
                detail.clone()
            },
            http_error_code(status.as_u16(), &detail),
        )
        .with_status(status.as_u16());
        failure.request_id = request_id;
        return Err(LlmError::from_failure(failure));
    }
    let payloads = parse_sse(response.bytes_stream()).await?;
    translate(payloads)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn maps_auth_and_rate_limit() {
        assert_eq!(http_error_code(401, ""), "AUTH");
        assert_eq!(http_error_code(429, ""), "RATE_LIMIT");
        assert_eq!(http_error_code(400, "maximum context length"), CONTEXT_WINDOW_EXCEEDED_CODE);
        assert_eq!(http_error_code(400, "insufficient_quota"), QUOTA_EXCEEDED_CODE);
    }
}
