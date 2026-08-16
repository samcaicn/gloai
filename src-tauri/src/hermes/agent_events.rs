//
// Typed event union for the
// agent runtime (`ThoughtEvent`, `ToolCallEvent`, `MessageEvent`,
// `ErrorEvent`, etc.) and a `dispatch()` helper. The Rust port exposes
// the same variants as an enum and provides a `dispatch` that fans out
// to subscribers on the shared `EventBus`.

use serde::{Deserialize, Serialize};

use super::event_bus::EventBus;
use super::types::AgentEvent;

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum AgentEventKind {
    Thought { content: String },
    ToolCall { name: String, args: serde_json::Value },
    Message { role: String, content: String },
    Error { message: String },
    Lifecycle { phase: String },
    Custom { payload: serde_json::Value },
}

pub fn topic_for(kind: &AgentEventKind) -> &'static str {
    match kind {
        AgentEventKind::Thought { .. } => "agent.thought",
        AgentEventKind::ToolCall { .. } => "agent.tool_call",
        AgentEventKind::Message { .. } => "agent.message",
        AgentEventKind::Error { .. } => "agent.error",
        AgentEventKind::Lifecycle { .. } => "lifecycle.phase",
        AgentEventKind::Custom { .. } => "agent.custom",
    }
}

pub async fn dispatch(bus: &EventBus, agent_id: &str, kind: AgentEventKind) {
    let topic = topic_for(&kind);
    let event = AgentEvent {
        agent_id: agent_id.to_string(),
        kind: topic.to_string(),
        payload: serde_json::to_value(&kind).unwrap_or(serde_json::Value::Null),
        ts: chrono::Utc::now().timestamp_millis(),
    };
    bus.publish(topic, event).await;
}
