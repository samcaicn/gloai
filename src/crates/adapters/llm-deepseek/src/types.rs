//! DeepSeek chat-completions wire format.

use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct WireRequest {
    pub model: String,
    pub messages: Vec<WireMessage>,
    pub stream: bool,
    pub stream_options: StreamOptions,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub thinking: Option<Thinking>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reasoning_effort: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tools: Option<Vec<WireTool>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub temperature: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_tokens: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stop: Option<Vec<String>>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct StreamOptions {
    pub include_usage: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Thinking {
    #[serde(rename = "type")]
    pub kind: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(tag = "role")]
pub enum WireMessage {
    #[serde(rename = "system")]
    System { content: String },
    #[serde(rename = "user")]
    User { content: String },
    #[serde(rename = "assistant")]
    Assistant {
        content: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        reasoning_content: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        tool_calls: Option<Vec<WireToolCall>>,
    },
    #[serde(rename = "tool")]
    Tool {
        tool_call_id: String,
        content: String,
    },
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct WireToolCall {
    pub id: String,
    #[serde(rename = "type")]
    pub kind: String,
    pub function: WireFunction,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct WireFunction {
    pub name: String,
    pub arguments: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct WireTool {
    #[serde(rename = "type")]
    pub kind: String,
    pub function: WireToolFn,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct WireToolFn {
    pub name: String,
    pub description: String,
    pub parameters: serde_json::Value,
}

#[derive(Clone, Debug, Deserialize)]
pub struct WireChunk {
    pub choices: Option<Vec<WireChoice>>,
    pub usage: Option<WireUsage>,
}

#[derive(Clone, Debug, Deserialize)]
pub struct WireChoice {
    pub delta: Option<WireDelta>,
    pub finish_reason: Option<String>,
}

#[derive(Clone, Debug, Deserialize, Default)]
pub struct WireDelta {
    pub content: Option<String>,
    pub reasoning_content: Option<String>,
    pub tool_calls: Option<Vec<WireToolCallDelta>>,
}

#[derive(Clone, Debug, Deserialize)]
pub struct WireToolCallDelta {
    pub index: u32,
    pub id: Option<String>,
    pub function: Option<WireFunctionDelta>,
}

#[derive(Clone, Debug, Deserialize, Default)]
pub struct WireFunctionDelta {
    pub name: Option<String>,
    pub arguments: Option<String>,
}

#[derive(Clone, Debug, Deserialize, Default)]
pub struct WireUsage {
    pub prompt_tokens: u32,
    pub completion_tokens: u32,
    pub prompt_cache_hit_tokens: Option<u32>,
    pub prompt_tokens_details: Option<PromptTokenDetails>,
    pub completion_tokens_details: Option<CompletionTokenDetails>,
}

#[derive(Clone, Debug, Deserialize, Default)]
pub struct PromptTokenDetails {
    pub cached_tokens: Option<u32>,
}

#[derive(Clone, Debug, Deserialize, Default)]
pub struct CompletionTokenDetails {
    pub reasoning_tokens: Option<u32>,
}

#[derive(Clone, Debug, Deserialize)]
pub struct WireErrorBody {
    pub error: Option<WireError>,
}

#[derive(Clone, Debug, Deserialize)]
pub struct WireError {
    pub message: Option<String>,
    pub code: Option<String>,
    #[serde(rename = "type")]
    pub kind: Option<String>,
}
