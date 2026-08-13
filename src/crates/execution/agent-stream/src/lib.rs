//! Incremental chunk-to-message assembler. Same algorithm as dsh-llm `BlockAssembler`.

use std::collections::HashMap;

use dsh_core_types::{
    CallId, ContentBlock, ContentBlockType, FinishReason, Message, StreamChunk, TokenUsage,
    create_assistant_message, AssistantProvenance,
};

struct PartialBlock {
    block_type: ContentBlockType,
    text: String,
    tool_call_id: Option<CallId>,
    tool_call_name: Option<String>,
    tool_call_arguments: String,
    closed: Option<ContentBlock>,
}

/// Incrementally assembles raw `StreamChunk`s into complete `ContentBlock`s.
pub struct BlockAssembler {
    partials: HashMap<u32, PartialBlock>,
    order: Vec<u32>,
    usage: Option<TokenUsage>,
    finish: Option<FinishReason>,
    replay_state: Option<serde_json::Value>,
}

impl Default for BlockAssembler {
    fn default() -> Self {
        Self::new()
    }
}

impl BlockAssembler {
    pub fn new() -> Self {
        Self {
            partials: HashMap::new(),
            order: Vec::new(),
            usage: None,
            finish: None,
            replay_state: None,
        }
    }

    pub fn push(&mut self, chunk: &StreamChunk) {
        match chunk {
            StreamChunk::BlockStart { index, block_type } => {
                if !self.partials.contains_key(index) {
                    self.order.push(*index);
                    self.partials.insert(
                        *index,
                        PartialBlock {
                            block_type: *block_type,
                            text: String::new(),
                            tool_call_id: None,
                            tool_call_name: None,
                            tool_call_arguments: String::new(),
                            closed: None,
                        },
                    );
                }
            }
            StreamChunk::TextDelta { index, text } => {
                let partial = self.ensure(*index, ContentBlockType::Text);
                if partial.closed.is_some() {
                    return;
                }
                partial.text.push_str(text);
            }
            StreamChunk::ReasoningDelta { index, text } => {
                let partial = self.ensure(*index, ContentBlockType::Reasoning);
                if partial.closed.is_some() {
                    return;
                }
                partial.text.push_str(text);
            }
            StreamChunk::ToolCallDelta {
                index,
                id,
                name,
                arguments_delta,
            } => {
                let partial = self.ensure(*index, ContentBlockType::ToolCall);
                if partial.closed.is_some() {
                    return;
                }
                partial.tool_call_id = Some(id.clone());
                if let Some(name) = name {
                    partial.tool_call_name = Some(name.clone());
                }
                partial.tool_call_arguments.push_str(arguments_delta);
            }
            StreamChunk::BlockEnd { index, block } => {
                let partial = self.ensure(*index, block.block_type());
                if partial.closed.is_some() {
                    return;
                }
                partial.closed = Some(block.clone());
            }
            StreamChunk::Usage { usage } => {
                self.usage = Some(usage.clone());
            }
            StreamChunk::Finish {
                reason,
                replay_state,
            } => {
                self.finish = Some(reason.clone());
                self.replay_state = replay_state.clone();
            }
        }
    }

    fn ensure(&mut self, index: u32, block_type: ContentBlockType) -> &mut PartialBlock {
        if !self.partials.contains_key(&index) {
            self.order.push(index);
            self.partials.insert(
                index,
                PartialBlock {
                    block_type,
                    text: String::new(),
                    tool_call_id: None,
                    tool_call_name: None,
                    tool_call_arguments: String::new(),
                    closed: None,
                },
            );
        }
        self.partials.get_mut(&index).expect("just inserted")
    }

    fn assemble(partial: &PartialBlock, index: u32) -> ContentBlock {
        if let Some(block) = &partial.closed {
            return block.clone();
        }
        match partial.block_type {
            ContentBlockType::Text => ContentBlock::text(partial.text.clone()),
            ContentBlockType::Reasoning => ContentBlock::reasoning(partial.text.clone()),
            ContentBlockType::ToolCall => ContentBlock::tool_call(
                partial
                    .tool_call_id
                    .clone()
                    .unwrap_or_else(|| CallId::generate_fallback(index as usize)),
                partial.tool_call_name.clone().unwrap_or_default(),
                partial.tool_call_arguments.clone(),
            ),
            other => panic!("cannot assemble incomplete block of type {other:?}"),
        }
    }

    pub fn blocks(&self) -> Vec<ContentBlock> {
        self.order
            .iter()
            .map(|index| {
                let partial = self.partials.get(index).expect("order tracks partials");
                Self::assemble(partial, *index)
            })
            .collect()
    }

    pub fn usage(&self) -> Option<&TokenUsage> {
        self.usage.as_ref()
    }

    pub fn finish(&self) -> FinishReason {
        self.finish.clone().unwrap_or(FinishReason::Stop)
    }

    pub fn replay_state(&self) -> Option<&serde_json::Value> {
        self.replay_state.as_ref()
    }

    pub fn message(&self, provider: impl Into<String>, model: impl Into<String>) -> Message {
        create_assistant_message(
            self.blocks(),
            AssistantProvenance {
                provider: provider.into(),
                model: model.into(),
                replay_state: self.replay_state.clone(),
            },
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use dsh_core_types::CallId;

    #[test]
    fn assembles_text_deltas_and_ignores_stragglers_after_block_end() {
        let mut assembler = BlockAssembler::new();
        assembler.push(&StreamChunk::BlockStart {
            index: 0,
            block_type: ContentBlockType::Text,
        });
        assembler.push(&StreamChunk::TextDelta {
            index: 0,
            text: "hel".into(),
        });
        assembler.push(&StreamChunk::TextDelta {
            index: 0,
            text: "lo".into(),
        });
        assembler.push(&StreamChunk::BlockEnd {
            index: 0,
            block: ContentBlock::text("hello"),
        });
        assembler.push(&StreamChunk::TextDelta {
            index: 0,
            text: "ignored".into(),
        });
        assembler.push(&StreamChunk::Finish {
            reason: FinishReason::Stop,
            replay_state: None,
        });
        assert_eq!(assembler.blocks(), vec![ContentBlock::text("hello")]);
        assert!(matches!(assembler.finish(), FinishReason::Stop));
    }

    #[test]
    fn delta_only_tool_call_gets_fallback_id() {
        let mut assembler = BlockAssembler::new();
        assembler.push(&StreamChunk::ToolCallDelta {
            index: 3,
            id: CallId::new("c1"),
            name: Some("bash".into()),
            arguments_delta: "{\"x\":1}".into(),
        });
        let blocks = assembler.blocks();
        match &blocks[0] {
            ContentBlock::ToolCall(call) => {
                assert_eq!(call.id.as_str(), "c1");
                assert_eq!(call.name, "bash");
                assert_eq!(call.arguments, "{\"x\":1}");
            }
            other => panic!("unexpected {other:?}"),
        }
    }
}
