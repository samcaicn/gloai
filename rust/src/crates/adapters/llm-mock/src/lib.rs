//! Scripted LLM adapter. Each `stream` call consumes the next scripted response.

use std::sync::Arc;

use async_trait::async_trait;
use dsh_core_types::{
    CallId, ContentBlock, ContentBlockType, FinishReason, GenerateOptions, LlmError, LlmModelInfo,
    LlmProviderInfo, LlmResolvedModelInfo, StreamChunk, TokenUsage,
};
use dsh_runtime_ports::{ChunkStream, LlmPort};
use parking_lot::Mutex;

#[derive(Clone, Debug)]
pub enum MockTurn {
    Text(String),
    Tool {
        name: String,
        arguments: String,
        then_text: String,
    },
    MaxTokens(String),
    Error {
        message: String,
        code: String,
    },
}

impl MockTurn {
    fn chunks(&self) -> Vec<StreamChunk> {
        match self {
            Self::Text(text) => text_chunks(text, FinishReason::Stop),
            Self::Tool {
                name, arguments, ..
            } => vec![
                StreamChunk::BlockStart {
                    index: 0,
                    block_type: ContentBlockType::ToolCall,
                },
                StreamChunk::ToolCallDelta {
                    index: 0,
                    id: CallId::new("mock-call-1"),
                    name: Some(name.clone()),
                    arguments_delta: arguments.clone(),
                },
                StreamChunk::BlockEnd {
                    index: 0,
                    block: ContentBlock::tool_call(
                        CallId::new("mock-call-1"),
                        name.clone(),
                        arguments.clone(),
                    ),
                },
                StreamChunk::Finish {
                    reason: FinishReason::ToolCalls,
                    replay_state: None,
                },
            ],
            Self::MaxTokens(text) => text_chunks(text, FinishReason::MaxTokens),
            Self::Error { message, code } => vec![StreamChunk::Finish {
                reason: FinishReason::Error {
                    failure: dsh_core_types::LlmFailure::new(message, code),
                },
                replay_state: None,
            }],
        }
    }
}

fn text_chunks(text: &str, reason: FinishReason) -> Vec<StreamChunk> {
    vec![
        StreamChunk::BlockStart {
            index: 0,
            block_type: ContentBlockType::Text,
        },
        StreamChunk::TextDelta {
            index: 0,
            text: text.to_string(),
        },
        StreamChunk::BlockEnd {
            index: 0,
            block: ContentBlock::text(text),
        },
        StreamChunk::Usage {
            usage: TokenUsage {
                input_tokens: 1,
                output_tokens: 1,
                cache_read_tokens: None,
                cache_write_tokens: None,
                reasoning_tokens: None,
            },
        },
        StreamChunk::Finish {
            reason,
            replay_state: None,
        },
    ]
}

pub struct MockLlm {
    provider: String,
    model: String,
    turns: Mutex<Vec<MockTurn>>,
    followups: Mutex<Vec<MockTurn>>,
}

impl MockLlm {
    pub fn new(turns: Vec<MockTurn>) -> Self {
        Self {
            provider: "mock".into(),
            model: "mock-model".into(),
            turns: Mutex::new(turns),
            followups: Mutex::new(Vec::new()),
        }
    }

    pub fn with_route(mut self, provider: impl Into<String>, model: impl Into<String>) -> Self {
        self.provider = provider.into();
        self.model = model.into();
        self
    }

    /// After a tool-call turn, the next stream uses this follow-up (usually text).
    pub fn then(self: Arc<Self>, turn: MockTurn) -> Arc<Self> {
        self.followups.lock().push(turn);
        self
    }
}

#[async_trait]
impl LlmPort for MockLlm {
    fn provider_info(&self, provider: &str) -> LlmProviderInfo {
        LlmProviderInfo {
            id: provider.to_string(),
            name: "Mock".into(),
        }
    }

    async fn list_models(&self, provider: &str) -> Result<Vec<LlmModelInfo>, LlmError> {
        Ok(vec![LlmModelInfo {
            provider: provider.to_string(),
            id: self.model.clone(),
            name: self.model.clone(),
            description: None,
            input_modalities: Some(vec!["text".into()]),
        }])
    }

    async fn resolve_model(
        &self,
        provider: &str,
        model: &str,
    ) -> Result<LlmResolvedModelInfo, LlmError> {
        Ok(LlmResolvedModelInfo {
            info: LlmModelInfo {
                provider: provider.to_string(),
                id: model.to_string(),
                name: model.to_string(),
                description: None,
                input_modalities: Some(vec!["text".into()]),
            },
            context_window: Some(128_000),
            default_max_tokens: Some(8_192),
        })
    }

    fn stream(&self, _request: GenerateOptions) -> ChunkStream {
        let turn = self
            .turns
            .lock()
            .pop()
            .or_else(|| self.followups.lock().pop());
        // Queue is FIFO: we stored in insertion order, so pop from front.
        let chunks = match turn {
            Some(turn) => {
                if let MockTurn::Tool { then_text, .. } = &turn {
                    self.followups
                        .lock()
                        .insert(0, MockTurn::Text(then_text.clone()));
                }
                turn.chunks()
            }
            None => text_chunks("(mock LLM has no remaining turns)", FinishReason::Stop),
        };
        Box::pin(async_stream::stream! {
            for chunk in chunks {
                yield Ok(chunk);
            }
        })
    }
}

/// Consume scripted turns in insertion order.
pub struct ScriptLlm {
    provider: String,
    model: String,
    remaining: Mutex<std::collections::VecDeque<MockTurn>>,
}

impl ScriptLlm {
    pub fn new(turns: Vec<MockTurn>) -> Self {
        Self {
            provider: "mock".into(),
            model: "mock-model".into(),
            remaining: Mutex::new(turns.into()),
        }
    }

    pub fn with_route(mut self, provider: impl Into<String>, model: impl Into<String>) -> Self {
        self.provider = provider.into();
        self.model = model.into();
        self
    }
}

#[async_trait]
impl LlmPort for ScriptLlm {
    fn provider_info(&self, provider: &str) -> LlmProviderInfo {
        LlmProviderInfo {
            id: provider.to_string(),
            name: "Mock".into(),
        }
    }

    async fn list_models(&self, provider: &str) -> Result<Vec<LlmModelInfo>, LlmError> {
        Ok(vec![LlmModelInfo {
            provider: provider.to_string(),
            id: self.model.clone(),
            name: self.model.clone(),
            description: None,
            input_modalities: Some(vec!["text".into()]),
        }])
    }

    async fn resolve_model(
        &self,
        provider: &str,
        model: &str,
    ) -> Result<LlmResolvedModelInfo, LlmError> {
        Ok(LlmResolvedModelInfo {
            info: LlmModelInfo {
                provider: provider.to_string(),
                id: model.to_string(),
                name: model.to_string(),
                description: None,
                input_modalities: Some(vec!["text".into()]),
            },
            context_window: Some(128_000),
            default_max_tokens: Some(8_192),
        })
    }

    fn stream(&self, _request: GenerateOptions) -> ChunkStream {
        let turn = self.remaining.lock().pop_front();
        let chunks = match turn {
            Some(MockTurn::Tool {
                name,
                arguments,
                then_text,
            }) => {
                self.remaining.lock().push_front(MockTurn::Text(then_text));
                MockTurn::Tool {
                    name,
                    arguments,
                    then_text: String::new(),
                }
                .chunks()
            }
            Some(other) => other.chunks(),
            None => text_chunks("(mock LLM has no remaining turns)", FinishReason::Stop),
        };
        Box::pin(async_stream::stream! {
            for chunk in chunks {
                yield Ok(chunk);
            }
        })
    }
}
