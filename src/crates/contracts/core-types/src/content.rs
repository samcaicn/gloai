//! Model-facing content blocks.

use crate::brand::CallId;
use serde::{Deserialize, Serialize};

/// Plain text visible to the end user or the model.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct TextBlock {
    pub text: String,
}

/// Reasoning / thinking content, distinct from visible text.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ReasoningBlock {
    pub text: String,
}

/// Durable raster image reference. The DeepSeek chat-completions adapter rejects this block.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ImageBlock {
    pub attachment_id: String,
}

/// A tool invocation requested by the model. `arguments` is the raw JSON string.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ToolCallBlock {
    pub id: CallId,
    pub name: String,
    pub arguments: String,
}

/// The result of a tool invocation, sent back to the model.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ToolResultBlock {
    pub tool_call_id: CallId,
    pub content: Vec<ContentBlock>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub is_error: Option<bool>,
}

/// Merge-extensible content blocks keyed by `type`.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "kebab-case")]
pub enum ContentBlock {
    Text(TextBlock),
    Reasoning(ReasoningBlock),
    Image(ImageBlock),
    ToolCall(ToolCallBlock),
    ToolResult(ToolResultBlock),
}

impl ContentBlock {
    pub fn text(text: impl Into<String>) -> Self {
        Self::Text(TextBlock { text: text.into() })
    }

    pub fn reasoning(text: impl Into<String>) -> Self {
        Self::Reasoning(ReasoningBlock { text: text.into() })
    }

    pub fn tool_call(id: CallId, name: impl Into<String>, arguments: impl Into<String>) -> Self {
        Self::ToolCall(ToolCallBlock {
            id,
            name: name.into(),
            arguments: arguments.into(),
        })
    }

    pub fn block_type(&self) -> ContentBlockType {
        match self {
            Self::Text(_) => ContentBlockType::Text,
            Self::Reasoning(_) => ContentBlockType::Reasoning,
            Self::Image(_) => ContentBlockType::Image,
            Self::ToolCall(_) => ContentBlockType::ToolCall,
            Self::ToolResult(_) => ContentBlockType::ToolResult,
        }
    }

    pub fn as_text(&self) -> Option<&str> {
        match self {
            Self::Text(block) => Some(&block.text),
            _ => None,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum ContentBlockType {
    Text,
    Reasoning,
    Image,
    ToolCall,
    ToolResult,
}

impl ContentBlockType {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Text => "text",
            Self::Reasoning => "reasoning",
            Self::Image => "image",
            Self::ToolCall => "tool-call",
            Self::ToolResult => "tool-result",
        }
    }
}

pub fn flatten_text(blocks: &[ContentBlock]) -> String {
    blocks
        .iter()
        .filter_map(ContentBlock::as_text)
        .collect::<Vec<_>>()
        .join("")
}

pub fn content_has_image(blocks: &[ContentBlock]) -> bool {
    blocks.iter().any(|block| matches!(block, ContentBlock::Image(_)))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn text_block_roundtrip_uses_kebab_type() {
        let block = ContentBlock::text("hi");
        let value = serde_json::to_value(&block).unwrap();
        assert_eq!(value, json!({"type": "text", "text": "hi"}));
        let back: ContentBlock = serde_json::from_value(value).unwrap();
        assert_eq!(back, block);
    }
}
