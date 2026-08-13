//! Message value types and immutable construction helpers.

use serde::{Deserialize, Serialize};

use crate::brand::{CallId, MessageId};
use crate::content::{ContentBlock, ToolResultBlock};

/// Provider/model identity and adapter-private replay data for an assistant message.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AssistantProvenance {
    pub provider: String,
    pub model: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub replay_state: Option<serde_json::Value>,
}

/// Where a message came from. Discriminated on `kind`.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum MessageSource {
    User,
    Plugin {
        plugin: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        form: Option<String>,
    },
    Model {
        provider: String,
        model: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        replay_state: Option<serde_json::Value>,
    },
    Tool {
        #[serde(rename = "callId")]
        call_id: CallId,
    },
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Message {
    pub id: MessageId,
    pub role: MessageRole,
    pub content: Vec<ContentBlock>,
    pub source: MessageSource,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum MessageRole {
    System,
    User,
    Assistant,
}

pub type UserMessage = Message;
pub type AssistantMessage = Message;
pub type ToolResultMessage = Message;

pub fn create_user_message(content: Vec<ContentBlock>, source: MessageSource) -> UserMessage {
    Message {
        id: MessageId::generate(),
        role: MessageRole::User,
        content,
        source,
    }
}

pub fn create_assistant_message(
    content: Vec<ContentBlock>,
    provenance: AssistantProvenance,
) -> AssistantMessage {
    Message {
        id: MessageId::generate(),
        role: MessageRole::Assistant,
        content,
        source: MessageSource::Model {
            provider: provenance.provider,
            model: provenance.model,
            replay_state: provenance.replay_state,
        },
    }
}

pub fn create_tool_result_message(
    call_id: CallId,
    content: Vec<ContentBlock>,
    is_error: bool,
) -> ToolResultMessage {
    create_user_message(
        vec![ContentBlock::ToolResult(ToolResultBlock {
            tool_call_id: call_id.clone(),
            content,
            is_error: if is_error { Some(true) } else { None },
        })],
        MessageSource::Tool { call_id },
    )
}

pub fn human_text(text: impl Into<String>) -> UserMessage {
    create_user_message(vec![ContentBlock::text(text)], MessageSource::User)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tool_result_rides_in_a_user_role_message() {
        let msg = create_tool_result_message(CallId::new("c1"), vec![ContentBlock::text("ok")], false);
        assert_eq!(msg.role, MessageRole::User);
        assert!(matches!(msg.source, MessageSource::Tool { .. }));
        assert_eq!(msg.content.len(), 1);
    }
}
