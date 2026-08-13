//! Translate DeepSeek SSE payloads into harness StreamChunks.

use dsh_core_types::{
    CallId, ContentBlock, FinishReason, LlmError, StreamChunk, TokenUsage, EMPTY_RESPONSE_CODE,
    MALFORMED_RESPONSE_CODE,
};

use crate::sse::DONE;
use crate::types::{WireChunk, WireUsage};

struct OpenBlock {
    index: u32,
    kind: Kind,
    text: String,
    call_id: Option<String>,
    name: Option<String>,
}

#[derive(Clone, Copy)]
enum Kind {
    Text,
    Reasoning,
    ToolCall,
}

pub fn map_finish_reason(reason: &str) -> FinishReason {
    match reason {
        "stop" => FinishReason::Stop,
        "tool_calls" => FinishReason::ToolCalls,
        "length" => FinishReason::MaxTokens,
        other => FinishReason::Error {
            failure: dsh_core_types::LlmFailure::new(
                format!("model stopped: {other}"),
                other.to_uppercase(),
            ),
        },
    }
}

pub fn map_usage(usage: &WireUsage) -> TokenUsage {
    let cache_read = usage
        .prompt_tokens_details
        .as_ref()
        .and_then(|d| d.cached_tokens)
        .or(usage.prompt_cache_hit_tokens);
    TokenUsage {
        input_tokens: usage.prompt_tokens.saturating_sub(cache_read.unwrap_or(0)),
        output_tokens: usage.completion_tokens,
        cache_read_tokens: cache_read,
        cache_write_tokens: None,
        reasoning_tokens: usage
            .completion_tokens_details
            .as_ref()
            .and_then(|d| d.reasoning_tokens),
    }
}

fn close_block(block: &OpenBlock) -> ContentBlock {
    match block.kind {
        Kind::Text => ContentBlock::text(&block.text),
        Kind::Reasoning => ContentBlock::reasoning(&block.text),
        Kind::ToolCall => ContentBlock::tool_call(
            CallId::new(block.call_id.clone().unwrap_or_default()),
            block.name.clone().unwrap_or_default(),
            block.text.clone(),
        ),
    }
}

pub fn translate(payloads: impl IntoIterator<Item = String>) -> Result<Vec<StreamChunk>, LlmError> {
    let mut next_index = 0_u32;
    let mut text_block: Option<usize> = None;
    let mut reasoning_block: Option<usize> = None;
    let mut tool_blocks = std::collections::HashMap::<u32, usize>::new();
    let mut order: Vec<OpenBlock> = Vec::new();
    let mut pending_finish: Option<FinishReason> = None;
    let mut pending_usage: Option<TokenUsage> = None;
    let mut out = Vec::new();

    let open = |kind: Kind, next_index: &mut u32, order: &mut Vec<OpenBlock>| {
        let block = OpenBlock {
            index: *next_index,
            kind,
            text: String::new(),
            call_id: None,
            name: None,
        };
        *next_index += 1;
        order.push(block);
        order.len() - 1
    };

    let mut saw_done = false;
    for payload in payloads {
        if payload == DONE {
            for block in &order {
                out.push(StreamChunk::BlockEnd {
                    index: block.index,
                    block: close_block(block),
                });
            }
            if let Some(usage) = pending_usage.take() {
                out.push(StreamChunk::Usage { usage });
            }
            let reason = pending_finish.unwrap_or(FinishReason::Stop);
            let reason = if matches!(reason, FinishReason::Stop) && order.is_empty() {
                FinishReason::Error {
                    failure: dsh_core_types::LlmFailure::new(
                        "model returned a completed response with no content",
                        EMPTY_RESPONSE_CODE,
                    ),
                }
            } else {
                reason
            };
            out.push(StreamChunk::Finish {
                reason,
                replay_state: None,
            });
            saw_done = true;
            break;
        }
        let chunk: WireChunk = serde_json::from_str(&payload).map_err(|_| {
            LlmError::new(
                format!("malformed SSE payload: {}", payload.chars().take(120).collect::<String>()),
                MALFORMED_RESPONSE_CODE,
            )
        })?;
        for choice in chunk.choices.unwrap_or_default() {
            let delta = choice.delta.unwrap_or_default();
            if let Some(reasoning) = delta.reasoning_content {
                if !reasoning.is_empty() {
                    if reasoning_block.is_none() {
                        let idx = open(Kind::Reasoning, &mut next_index, &mut order);
                        reasoning_block = Some(idx);
                        out.push(StreamChunk::BlockStart {
                            index: order[idx].index,
                            block_type: dsh_core_types::ContentBlockType::Reasoning,
                        });
                    }
                    let idx = reasoning_block.expect("opened");
                    order[idx].text.push_str(&reasoning);
                    out.push(StreamChunk::ReasoningDelta {
                        index: order[idx].index,
                        text: reasoning,
                    });
                }
            }
            if let Some(content) = delta.content {
                if !content.is_empty() {
                    if text_block.is_none() {
                        let idx = open(Kind::Text, &mut next_index, &mut order);
                        text_block = Some(idx);
                        out.push(StreamChunk::BlockStart {
                            index: order[idx].index,
                            block_type: dsh_core_types::ContentBlockType::Text,
                        });
                    }
                    let idx = text_block.expect("opened");
                    order[idx].text.push_str(&content);
                    out.push(StreamChunk::TextDelta {
                        index: order[idx].index,
                        text: content,
                    });
                }
            }
            for call in delta.tool_calls.unwrap_or_default() {
                let idx = if let Some(&existing) = tool_blocks.get(&call.index) {
                    existing
                } else {
                    let idx = open(Kind::ToolCall, &mut next_index, &mut order);
                    tool_blocks.insert(call.index, idx);
                    out.push(StreamChunk::BlockStart {
                        index: order[idx].index,
                        block_type: dsh_core_types::ContentBlockType::ToolCall,
                    });
                    idx
                };
                if let Some(id) = call.id {
                    order[idx].call_id = Some(id);
                }
                if let Some(name) = call.function.as_ref().and_then(|f| f.name.clone()) {
                    order[idx].name = Some(name);
                }
                let fragment = call
                    .function
                    .as_ref()
                    .and_then(|f| f.arguments.clone())
                    .unwrap_or_default();
                order[idx].text.push_str(&fragment);
                out.push(StreamChunk::ToolCallDelta {
                    index: order[idx].index,
                    id: CallId::new(order[idx].call_id.clone().unwrap_or_default()),
                    name: order[idx].name.clone(),
                    arguments_delta: fragment,
                });
            }
            if let Some(reason) = choice.finish_reason {
                pending_finish = Some(map_finish_reason(&reason));
            }
        }
        if let Some(usage) = chunk.usage {
            pending_usage = Some(map_usage(&usage));
        }
    }
    if !saw_done {
        return Err(LlmError::new(
            "SSE payload stream ended without [DONE]",
            dsh_core_types::STREAM_CLOSED_CODE,
        ));
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn subtracts_cache_hits_from_input_tokens() {
        let usage = map_usage(&WireUsage {
            prompt_tokens: 100,
            completion_tokens: 10,
            prompt_cache_hit_tokens: Some(40),
            prompt_tokens_details: None,
            completion_tokens_details: None,
        });
        assert_eq!(usage.input_tokens, 60);
        assert_eq!(usage.cache_read_tokens, Some(40));
    }

    #[test]
    fn empty_completion_is_empty_response() {
        let chunks = translate(vec![DONE.to_string()]).unwrap();
        match chunks.last() {
            Some(StreamChunk::Finish { reason, .. }) => match reason {
                FinishReason::Error { failure } => {
                    assert_eq!(failure.code, EMPTY_RESPONSE_CODE);
                }
                other => panic!("{other:?}"),
            },
            other => panic!("{other:?}"),
        }
    }
}
