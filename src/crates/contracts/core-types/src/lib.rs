//! Stable DTOs shared by every layer. This crate stays behavior-light.

pub mod brand;
pub mod content;
pub mod error;
pub mod json;
pub mod llm;
pub mod message;

pub use brand::{CallId, CredentialRef, MessageId, ProviderRequestId, ReasoningEffortId, SessionId};
pub use content::{
    content_has_image, flatten_text, ContentBlock, ContentBlockType, ImageBlock, ReasoningBlock,
    TextBlock, ToolCallBlock, ToolResultBlock,
};
pub use error::{
    error_chain, LlmError, LlmFailure, CONTEXT_WINDOW_EXCEEDED_CODE, EMPTY_RESPONSE_CODE,
    MALFORMED_RESPONSE_CODE, NO_ADAPTER_CODE, QUOTA_EXCEEDED_CODE, STREAM_CLOSED_CODE,
    UNSUPPORTED_CONTENT_CODE, UNSUPPORTED_REASONING_EFFORT_CODE,
};
pub use json::JsonValue;
pub use llm::{
    FinishReason, GenerateOptions, LlmCallConfig, LlmModelInfo, LlmProviderInfo,
    LlmResolvedModelInfo, RequestPurpose, StreamChunk, TokenUsage, ToolSchema,
};
pub use message::{
    create_assistant_message, create_tool_result_message, create_user_message, human_text,
    AssistantMessage, AssistantProvenance, Message, MessageRole, MessageSource, ToolResultMessage,
    UserMessage,
};
