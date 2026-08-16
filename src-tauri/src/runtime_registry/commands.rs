// Copyright (c) 2026 tupAI
//
// Tauri command surface for the runtime registry.

use serde::{Deserialize, Serialize};

use crate::runtime_registry::registry::RuntimeRegistry;
use crate::runtime_registry::{InvokeRequest, RuntimeRegistrySnapshot, SubAgent};

#[derive(Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct AddCustomAgentRequest {
    pub name: String,
    pub endpoint: String,
    #[serde(default)]
    pub model: Option<String>,
    #[serde(default)]
    pub api_key: Option<String>,
}

/// Register an upstream runtime (dsh / proma). `endpoint` is an http(s)
/// URL (OpenAI-compatible) or a local binary path; `cliArgsTemplate`
/// supplies the subprocess argv when `endpoint` is a binary.
#[derive(Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct RegisterUpstreamRequest {
    pub id: String,
    pub display_name: String,
    pub endpoint: String,
    #[serde(default)]
    pub cli_args_template: Option<Vec<String>>,
    #[serde(default)]
    pub model: Option<String>,
    #[serde(default)]
    pub api_key: Option<String>,
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct InvokeCommandResponse {
    pub subagent_id: String,
    pub output: String,
    #[serde(default)]
    pub exit_status: Option<i32>,
    #[serde(default)]
    pub error: Option<String>,
}

#[tauri::command]
pub async fn rr_scan_runtimes(
    registry: tauri::State<'_, RuntimeRegistry>,
) -> Result<(), String> {
    registry.scan().await;
    Ok(())
}

#[tauri::command]
pub async fn rr_list_runtimes(
    registry: tauri::State<'_, RuntimeRegistry>,
) -> Result<RuntimeRegistrySnapshot, String> {
    Ok(registry.snapshot().await)
}

#[tauri::command]
pub async fn rr_list_subagents(
    registry: tauri::State<'_, RuntimeRegistry>,
) -> Result<Vec<SubAgent>, String> {
    Ok(registry.list_subagents().await)
}

#[tauri::command]
pub async fn rr_spawn_instance(
    registry: tauri::State<'_, RuntimeRegistry>,
    provider_id: String,
) -> Result<Option<SubAgent>, String> {
    Ok(registry.spawn_instance(&provider_id).await)
}

#[tauri::command]
pub async fn rr_add_custom_agent(
    registry: tauri::State<'_, RuntimeRegistry>,
    request: AddCustomAgentRequest,
) -> Result<SubAgent, String> {
    registry.add_custom_api(request).await
}

#[tauri::command]
pub async fn rr_remove_agent(
    registry: tauri::State<'_, RuntimeRegistry>,
    subagent_id: String,
) -> Result<bool, String> {
    Ok(registry.remove_agent(&subagent_id).await)
}

#[tauri::command]
pub async fn rr_register_upstream(
    registry: tauri::State<'_, RuntimeRegistry>,
    request: RegisterUpstreamRequest,
) -> Result<SubAgent, String> {
    registry.register_upstream(request).await
}

/// Discover model id + available models for an ACP provider the way the exe
/// does (open a real ACP session, read `models`). Returns the refreshed
/// snapshot so the UI can re-render immediately.
#[tauri::command]
pub async fn rr_discover_models(
    registry: tauri::State<'_, RuntimeRegistry>,
    provider_id: String,
) -> Result<RuntimeRegistrySnapshot, String> {
    registry.discover_models(&provider_id).await?;
    Ok(registry.snapshot().await)
}

#[tauri::command]
pub async fn rr_invoke_subagent(
    registry: tauri::State<'_, RuntimeRegistry>,
    request: InvokeRequest,
) -> Result<InvokeCommandResponse, String> {
    let resp = registry.invoke(request).await?;
    Ok(InvokeCommandResponse {
        subagent_id: resp.subagent_id,
        output: resp.output,
        exit_status: resp.exit_status,
        error: resp.error,
    })
}
