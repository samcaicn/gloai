
//
// Shared types for the hermes core. The TypeScript `types.ts` is the
// single source of truth for message/task/conversation shapes used by
// the agents, the LLM service, the trajectory store, and the
// multi-agent scheduler. This Rust port mirrors the field set so
// front-end and back-end can round-trip JSON cleanly.

use serde::{Deserialize, Serialize};
use chrono::{DateTime, Utc};

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct HermesConfig {
    #[serde(default)]
    pub api_url: String,
    #[serde(default)]
    pub api_key: Option<String>,
    #[serde(default)]
    pub model: String,
    #[serde(default)]
    pub temperature: f32,
    #[serde(default)]
    pub max_tokens: u32,
    #[serde(default)]
    pub workspace: Option<String>,
    #[serde(default)]
    pub persona_id: Option<String>,
    #[serde(default)]
    pub profile_id: Option<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct VLMMessage {
    pub role: String,
    pub content: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tool_call_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tool_calls: Option<Vec<VLMToolCall>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub images: Option<Vec<VLMImageRef>>,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct VLMToolCall {
    pub id: String,
    #[serde(rename = "type")]
    pub kind: String,
    pub function: VLMToolFunction,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct VLMToolFunction {
    pub name: String,
    pub arguments: String,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct VLMImageRef {
    pub url: String,
    #[serde(default)]
    pub detail: Option<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct VLMResponse {
    #[serde(default)]
    pub content: Option<String>,
    #[serde(default)]
    pub tool_calls: Option<Vec<VLMToolCall>>,
    #[serde(default)]
    pub finish_reason: Option<String>,
    #[serde(default)]
    pub usage: Option<VLMUsage>,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct VLMUsage {
    pub prompt_tokens: u32,
    pub completion_tokens: u32,
    pub total_tokens: u32,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct ToolCall {
    pub name: String,
    pub args: serde_json::Value,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct Task {
    pub id: String,
    pub instruction: String,
    #[serde(default = "default_priority")]
    pub priority: u8,
    #[serde(default = "default_status")]
    pub status: String,
    #[serde(default)]
    pub created_at: Option<i64>,
    #[serde(default)]
    pub started_at: Option<i64>,
    #[serde(default)]
    pub completed_at: Option<i64>,
    #[serde(default)]
    pub result: Option<serde_json::Value>,
    #[serde(default)]
    pub error: Option<String>,
    #[serde(default)]
    pub parent: Option<String>,
    #[serde(default)]
    pub agent_id: Option<String>,
}

fn default_priority() -> u8 { 5 }
fn default_status() -> String { "pending".to_string() }

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct Conversation {
    pub id: String,
    pub title: String,
    pub messages: Vec<VLMMessage>,
    #[serde(default)]
    pub created_at: DateTime<Utc>,
    #[serde(default)]
    pub updated_at: DateTime<Utc>,
    #[serde(default)]
    pub tags: Vec<String>,
    #[serde(default)]
    pub pinned: bool,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct Lesson {
    pub id: String,
    pub summary: String,
    pub detail: String,
    #[serde(default)]
    pub created_at: DateTime<Utc>,
    #[serde(default)]
    pub tags: Vec<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq, Eq, Hash)]
pub enum AgentStatus {
    #[default]
    Idle,
    Thinking,
    Acting,
    WaitingForTool,
    Error,
    Stopped,
    Disabled,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct AgentEvent {
    pub agent_id: String,
    pub kind: String,
    pub payload: serde_json::Value,
    pub ts: i64,
}
