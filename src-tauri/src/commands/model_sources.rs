// Copyright (c) 2026 MeeJoy
//
// Model source listing commands.
//
// Surfaces the tuptup cloud model catalog to the Settings UI
// via the local embedded Hermes gateway at 127.0.0.1:<gateway_port>/v1/models.
// The gateway returns a curated list from hermes::model_catalog.
// It is loopback-only, so calling it from the Rust side avoids
// the WebView2 CORS preflight that would otherwise block fetch() from tauri://localhost.

use serde::{Deserialize, Serialize};
use std::time::Duration;

use crate::hermes::embedded_server::gateway_socket_addr;

const CONNECT_TIMEOUT_SECS: u64 = 4;
const REQUEST_TIMEOUT_SECS: u64 = 10;

fn build_http_client() -> Result<reqwest::Client, String> {
    reqwest::Client::builder()
        .no_proxy()
        .connect_timeout(Duration::from_secs(CONNECT_TIMEOUT_SECS))
        .timeout(Duration::from_secs(REQUEST_TIMEOUT_SECS))
        .build()
        .map_err(|e| format!("reqwest client build failed: {e}"))
}

/// One tuptup cloud model entry returned by the local Hermes
/// /v1/models endpoint (the inner models[] array). We mirror
/// the upstream shape so the front-end can render it directly.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TupaiCloudModel {
    pub id: String,
    #[serde(default)]
    pub display_name: Option<String>,
    #[serde(default)]
    pub provider: Option<String>,
    #[serde(default)]
    pub category: Option<String>,
}

/// Aggregated result. `providers` mirrors what /v1/models returns
/// under the `providers[]` key; `active_model`/`active_provider`
/// are the user's currently-pinned selection.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TupaiCloudModelList {
    pub models: Vec<TupaiCloudModel>,
    #[serde(default)]
    pub active_model: Option<String>,
    #[serde(default)]
    pub active_provider: Option<String>,
    /// Loopback source we read from — surfaced in the UI so the
    /// user knows it's the embedded gateway, not the public cloud.
    pub source: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct HermesListModelsEnvelope {
    #[serde(default)]
    models: Vec<serde_json::Value>,
    #[serde(default)]
    model: Option<String>,
    #[serde(default)]
    provider: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct HermesProviderEntry {
    id: String,
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    models: Vec<serde_json::Value>,
}

/// Fetch the curated tuptup cloud model list from the local
/// embedded Hermes gateway. The gateway is bound to
/// 127.0.0.1:<gateway_port> (default 8642) and serves
/// /v1/models from the in-memory model_catalog. Returns the
/// flat models[] plus the active pin.
#[tauri::command]
pub async fn list_tupai_cloud_models() -> Result<TupaiCloudModelList, String> {
    let port = gateway_socket_addr().port();
    let url = format!("http://127.0.0.1:{port}/v1/models");
    let client = build_http_client()?;
    let response = client
        .get(&url)
        .send()
        .await
        .map_err(|e| format!("tupAI 云端模型列表请求失败 ({url}): {e}"))?;

    if !response.status().is_success() {
        let status = response.status();
        return Err(format!(
            "tupAI 云端模型列表返回非 2xx 状态: {status} ({url})"
        ));
    }

    let envelope: HermesListModelsEnvelope = response
        .json()
        .await
        .map_err(|e| format!("tupAI 云端模型列表响应解析失败: {e}"))?;

    let mut out: Vec<TupaiCloudModel> = Vec::new();
    let mut seen: std::collections::HashSet<String> = std::collections::HashSet::new();
    for raw in envelope.models.into_iter() {
        let id = raw
            .get("id")
            .and_then(|v| v.as_str())
            .map(|s| s.to_string());
        let Some(id) = id else { continue };
        if !seen.insert(id.clone()) {
            continue;
        }
        let display_name = raw
            .get("display_name")
            .and_then(|v| v.as_str())
            .map(|s| s.to_string());
        let provider = raw
            .get("provider")
            .and_then(|v| v.as_str())
            .map(|s| s.to_string());
        let category = raw
            .get("category")
            .and_then(|v| v.as_str())
            .map(|s| s.to_string());
        out.push(TupaiCloudModel {
            id,
            display_name,
            provider,
            category,
        });
    }

    Ok(TupaiCloudModelList {
        models: out,
        active_model: envelope.model,
        active_provider: envelope.provider,
        source: url,
    })
}
