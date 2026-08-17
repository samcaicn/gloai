//! Append-only session event vocabulary. Message history is derived from this log.

use dsh_core_types::{
    GenerateOptions, JsonValue, LlmCallConfig, LlmFailure, Message, SessionId, StreamChunk,
    TokenUsage, ToolSchema, UserMessage,
};
use serde::{Deserialize, Deserializer, Serialize, Serializer};

/// On-disk session format version. Unreleased harness: pinned at 0, no compatibility.
pub const SESSION_FORMAT_VERSION: u32 = 0;

/// Immutable validated storage metadata, kept outside the conversation event log.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionHeader {
    pub version: u32,
    pub id: SessionId,
    pub created_at: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cwd: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub parent_session: Option<SessionId>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub seed_length: Option<usize>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub origin: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub delegation_depth: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub agent_preset: Option<String>,
}

impl SessionHeader {
    pub fn new(id: SessionId, created_at: i64) -> Self {
        Self {
            version: SESSION_FORMAT_VERSION,
            id,
            created_at,
            cwd: None,
            parent_session: None,
            seed_length: None,
            origin: None,
            delegation_depth: None,
            agent_preset: None,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum AgentCancelCause {
    User,
    Parent,
    Hook { reason: String },
    Disposed,
    Legacy,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "kebab-case")]
pub enum TurnEndReason {
    Completed,
    Aborted { reason: AgentCancelCause },
    Blocked,
    Error { error: LlmFailure },
    MaxTokens,
    Interrupted,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct TodoItem {
    pub content: String,
    pub status: TodoStatus,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TodoStatus {
    Pending,
    InProgress,
    Completed,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EpochHeader {
    pub config: LlmCallConfig,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub adapter_defaults: Option<AdapterDefaults>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub system: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tools: Option<Vec<ToolSchema>>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct AdapterDefaults {
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub reasoning_effort: bool,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub max_tokens: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum RequestHeaderReason {
    Initial,
    Resume,
    Change,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RequestContext {
    pub provider: String,
    pub model: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub context_window: Option<u32>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum InboxTarget {
    #[serde(rename = "next-turn")]
    NextTurn,
    #[serde(rename = "next-step")]
    NextStep,
}

/// How a session event entered the ordered surface.
/// Wire form matches DeepSeek Harness: `"append"` or `{ op: "replace", start, end }`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SurfaceOp {
    Append,
    Replace {
        op: ReplaceTag,
        start: u64,
        end: u64,
    },
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum ReplaceTag {
    Replace,
}

impl Serialize for SurfaceOp {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        match self {
            Self::Append => serializer.serialize_str("append"),
            Self::Replace { start, end, .. } => {
                #[derive(Serialize)]
                struct Repl {
                    op: &'static str,
                    start: u64,
                    end: u64,
                }
                Repl {
                    op: "replace",
                    start: *start,
                    end: *end,
                }
                .serialize(serializer)
            }
        }
    }
}

impl<'de> Deserialize<'de> for SurfaceOp {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let value = serde_json::Value::deserialize(deserializer)?;
        match value {
            serde_json::Value::String(op) if op == "append" => Ok(Self::Append),
            serde_json::Value::Object(map) => {
                let op = map.get("op").and_then(serde_json::Value::as_str);
                let start = map.get("start").and_then(serde_json::Value::as_u64);
                let end = map.get("end").and_then(serde_json::Value::as_u64);
                match (op, start, end) {
                    (Some("replace"), Some(start), Some(end)) => Ok(Self::Replace {
                        op: ReplaceTag::Replace,
                        start,
                        end,
                    }),
                    _ => Err(serde::de::Error::custom(
                        "surfaceOp replace requires op, start, and end",
                    )),
                }
            }
            other => Err(serde::de::Error::custom(format!(
                "invalid surfaceOp: {other}"
            ))),
        }
    }
}

impl SurfaceOp {
    pub fn append() -> Self {
        Self::Append
    }
}

/// Event-type keys whose events produce LLM messages.
pub const SURFACE_EVENT_TYPES: &[&str] = &["user/message", "assistant/message", "tool/result"];

/// One immutable entry in the session log.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionEvent {
    #[serde(flatten)]
    pub body: SessionEventBody,
    pub seq: u64,
    pub time: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ignorable: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub surface_op: Option<SurfaceOp>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub source_event_seqs: Option<Vec<u64>>,
}

impl SessionEvent {
    pub fn event_type(&self) -> &'static str {
        self.body.event_type()
    }

    pub fn is_surface_eligible(&self) -> bool {
        SURFACE_EVENT_TYPES.contains(&self.event_type())
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", content = "data")]
pub enum SessionEventBody {
    #[serde(rename = "turn/start")]
    TurnStart { turn: u32 },
    #[serde(rename = "turn/end")]
    TurnEnd { turn: u32, reason: TurnEndReason },
    #[serde(rename = "step/start")]
    StepStart { turn: u32, step: u32 },
    #[serde(rename = "step/end")]
    StepEnd { turn: u32, step: u32 },
    #[serde(rename = "user/message")]
    UserMessage(UserMessage),
    #[serde(rename = "assistant/chunk")]
    AssistantChunk {
        turn: u32,
        step: u32,
        chunk: StreamChunk,
    },
    #[serde(rename = "assistant/message")]
    AssistantMessage {
        turn: u32,
        step: u32,
        message: Message,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        usage: Option<TokenUsage>,
    },
    #[serde(rename = "tool/call")]
    ToolCall {
        turn: u32,
        step: u32,
        #[serde(rename = "callId")]
        call_id: dsh_core_types::CallId,
        name: String,
        arguments: String,
    },
    #[serde(rename = "tool/result")]
    ToolResult {
        turn: u32,
        step: u32,
        message: Message,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        error: Option<ToolErrorIdentity>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        meta: Option<JsonValue>,
    },
    #[serde(rename = "todo/write")]
    TodoWrite { todos: Vec<TodoItem> },
    #[serde(rename = "request/header")]
    RequestHeader {
        header: EpochHeader,
        reason: RequestHeaderReason,
    },
    #[serde(rename = "request/context")]
    RequestContext(RequestContext),
    #[serde(rename = "session/end-seed")]
    SessionEndSeed {},
    #[serde(rename = "agent/inbox/spliced")]
    InboxSpliced {
        target: InboxTarget,
        start: usize,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        #[serde(rename = "removedCount")]
        removed_count: Option<usize>,
        inserted: Vec<UserMessage>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        outcome: Option<String>,
    },
}

impl SessionEventBody {
    pub fn event_type(&self) -> &'static str {
        match self {
            Self::TurnStart { .. } => "turn/start",
            Self::TurnEnd { .. } => "turn/end",
            Self::StepStart { .. } => "step/start",
            Self::StepEnd { .. } => "step/end",
            Self::UserMessage(_) => "user/message",
            Self::AssistantChunk { .. } => "assistant/chunk",
            Self::AssistantMessage { .. } => "assistant/message",
            Self::ToolCall { .. } => "tool/call",
            Self::ToolResult { .. } => "tool/result",
            Self::TodoWrite { .. } => "todo/write",
            Self::RequestHeader { .. } => "request/header",
            Self::RequestContext(_) => "request/context",
            Self::SessionEndSeed { .. } => "session/end-seed",
            Self::InboxSpliced { .. } => "agent/inbox/spliced",
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ToolErrorIdentity {
    pub name: String,
    pub code: String,
}

/// Reconstruct the GenerateOptions header fields from the latest request/header.
pub fn request_from_header(header: &EpochHeader, messages: Vec<Message>) -> GenerateOptions {
    GenerateOptions {
        provider: header.config.provider.clone(),
        model: header.config.model.clone(),
        messages,
        reasoning_effort: header.config.reasoning_effort.clone(),
        system: header.system.clone(),
        tools: header.tools.clone(),
        temperature: header
            .config
            .temperature
            .as_ref()
            .and_then(serde_json::Number::as_f64),
        max_tokens: header.config.max_tokens,
        stop: header.config.stop.clone(),
        session_id: None,
        purpose: None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use dsh_core_types::human_text;

    #[test]
    fn turn_start_roundtrips_with_slash_type() {
        let event = SessionEvent {
            body: SessionEventBody::TurnStart { turn: 1 },
            seq: 0,
            time: 1,
            ignorable: None,
            surface_op: None,
            source_event_seqs: None,
        };
        let value = serde_json::to_value(&event).unwrap();
        assert_eq!(value["type"], "turn/start");
        assert_eq!(value["data"]["turn"], 1);
        let back: SessionEvent = serde_json::from_value(value).unwrap();
        assert_eq!(back, event);
    }

    #[test]
    fn user_message_is_surface_eligible() {
        let event = SessionEvent {
            body: SessionEventBody::UserMessage(human_text("hi")),
            seq: 0,
            time: 1,
            ignorable: None,
            surface_op: Some(SurfaceOp::Append),
            source_event_seqs: None,
        };
        assert!(event.is_surface_eligible());
        let value = serde_json::to_value(&event).unwrap();
        assert_eq!(value["surfaceOp"], "append");
        let back: SessionEvent = serde_json::from_value(value).unwrap();
        assert_eq!(back.surface_op, Some(SurfaceOp::Append));
    }
}
