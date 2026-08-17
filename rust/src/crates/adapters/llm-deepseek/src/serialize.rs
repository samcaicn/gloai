//! Serialize harness messages into DeepSeek chat completions.

use dsh_core_types::{
    content_has_image, flatten_text, ContentBlock, GenerateOptions, LlmError, Message, MessageRole,
    RequestPurpose, UNSUPPORTED_CONTENT_CODE, UNSUPPORTED_REASONING_EFFORT_CODE,
};

use crate::types::{
    StreamOptions, Thinking, WireFunction, WireMessage, WireRequest, WireTool, WireToolCall,
    WireToolFn,
};

#[derive(Clone, Debug, Default)]
pub struct RequestDefaults {
    pub thinking: Option<String>,
    pub reasoning_effort: Option<String>,
}

fn flatten_text_blocks(blocks: &[ContentBlock]) -> String {
    flatten_text(blocks)
}

fn assert_text_only(blocks: &[ContentBlock]) -> Result<(), LlmError> {
    if content_has_image(blocks) {
        return Err(LlmError::new(
            "The DeepSeek chat-completions adapter does not support image content.",
            UNSUPPORTED_CONTENT_CODE,
        ));
    }
    Ok(())
}

fn serialize_assistant(message: &Message) -> WireMessage {
    let text = flatten_text_blocks(&message.content);
    let reasoning: String = message
        .content
        .iter()
        .filter_map(|block| match block {
            ContentBlock::Reasoning(block) => Some(block.text.as_str()),
            _ => None,
        })
        .collect();
    let tool_calls: Vec<WireToolCall> = message
        .content
        .iter()
        .filter_map(|block| match block {
            ContentBlock::ToolCall(call) => Some(WireToolCall {
                id: call.id.to_string(),
                kind: "function".into(),
                function: WireFunction {
                    name: call.name.clone(),
                    arguments: call.arguments.clone(),
                },
            }),
            _ => None,
        })
        .collect();
    WireMessage::Assistant {
        content: text,
        reasoning_content: if !tool_calls.is_empty() && !reasoning.is_empty() {
            Some(reasoning)
        } else {
            None
        },
        tool_calls: if tool_calls.is_empty() {
            None
        } else {
            Some(tool_calls)
        },
    }
}

pub fn serialize_messages(messages: &[Message]) -> Result<Vec<WireMessage>, LlmError> {
    let mut wire = Vec::new();
    for message in messages {
        assert_text_only(&message.content)?;
        match message.role {
            MessageRole::System => {
                wire.push(WireMessage::System {
                    content: flatten_text_blocks(&message.content),
                });
            }
            MessageRole::Assistant => wire.push(serialize_assistant(message)),
            MessageRole::User => {
                let tool_results: Vec<_> = message
                    .content
                    .iter()
                    .filter_map(|block| match block {
                        ContentBlock::ToolResult(result) => Some(result),
                        _ => None,
                    })
                    .collect();
                let text = flatten_text_blocks(&message.content);
                if !text.is_empty() || tool_results.is_empty() {
                    wire.push(WireMessage::User { content: text });
                }
                for result in tool_results {
                    let content = flatten_text(&result.content);
                    wire.push(WireMessage::Tool {
                        tool_call_id: result.tool_call_id.to_string(),
                        content: if content.is_empty() {
                            "(no output)".into()
                        } else {
                            content
                        },
                    });
                }
            }
        }
    }
    Ok(wire)
}

fn resolve_thinking(
    options: &GenerateOptions,
    defaults: &RequestDefaults,
) -> Result<(Option<String>, Option<String>), LlmError> {
    if options.purpose == Some(RequestPurpose::SessionTitle) {
        return Ok((Some("disabled".into()), None));
    }
    let effort = match &options.reasoning_effort {
        None => defaults.reasoning_effort.clone(),
        Some(value) => {
            let effort = value.as_str();
            if !matches!(effort, "off" | "high" | "max") {
                return Err(LlmError::new(
                    format!("DeepSeek does not support reasoning effort \"{effort}\""),
                    UNSUPPORTED_REASONING_EFFORT_CODE,
                ));
            }
            Some(effort.to_string())
        }
    };
    if defaults.thinking.as_deref() == Some("disabled")
        && effort.as_deref().is_some_and(|e| e != "off")
    {
        return Err(LlmError::new(
            format!(
                "DeepSeek deployment does not support reasoning effort \"{}\"",
                effort.unwrap_or_default()
            ),
            UNSUPPORTED_REASONING_EFFORT_CODE,
        ));
    }
    match effort.as_deref() {
        Some("off") => Ok((Some("disabled".into()), None)),
        Some("high" | "max") => Ok((Some("enabled".into()), effort)),
        _ => Ok((defaults.thinking.clone(), None)),
    }
}

pub fn serialize_request(
    options: &GenerateOptions,
    defaults: &RequestDefaults,
) -> Result<WireRequest, LlmError> {
    let mut messages = Vec::new();
    if let Some(system) = &options.system {
        messages.push(WireMessage::System {
            content: system.clone(),
        });
    }
    messages.extend(serialize_messages(&options.messages)?);
    let tools = options.tools.as_ref().map(|tools| {
        tools
            .iter()
            .map(|tool| WireTool {
                kind: "function".into(),
                function: WireToolFn {
                    name: tool.name.clone(),
                    description: tool.description.clone(),
                    parameters: tool.parameters.clone(),
                },
            })
            .collect()
    });
    let (thinking, reasoning_effort) = resolve_thinking(options, defaults)?;
    Ok(WireRequest {
        model: options.model.clone(),
        messages,
        stream: true,
        stream_options: StreamOptions {
            include_usage: true,
        },
        thinking: thinking.map(|kind| Thinking { kind }),
        reasoning_effort,
        tools: tools.filter(|tools: &Vec<_>| !tools.is_empty()),
        temperature: options.temperature,
        max_tokens: options.max_tokens,
        stop: options.stop.clone(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use dsh_core_types::{
        create_assistant_message, create_tool_result_message, human_text, AssistantProvenance,
        CallId, ContentBlock, ToolSchema,
    };

    #[test]
    fn tool_call_turn_replays_empty_content_and_reasoning() {
        let message = create_assistant_message(
            vec![
                ContentBlock::reasoning("think"),
                ContentBlock::tool_call(CallId::new("c1"), "bash", "{}"),
            ],
            AssistantProvenance {
                provider: "deepseek".into(),
                model: "deepseek-chat".into(),
                replay_state: None,
            },
        );
        let wire = serialize_messages(&[message]).unwrap();
        match &wire[0] {
            WireMessage::Assistant {
                content,
                reasoning_content,
                tool_calls,
            } => {
                assert_eq!(content, "");
                assert_eq!(reasoning_content.as_deref(), Some("think"));
                assert_eq!(tool_calls.as_ref().unwrap().len(), 1);
            }
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn tool_result_becomes_role_tool() {
        let user = human_text("hi");
        let result =
            create_tool_result_message(CallId::new("c1"), vec![ContentBlock::text("ok")], false);
        let wire = serialize_messages(&[user, result]).unwrap();
        assert!(matches!(wire[0], WireMessage::User { .. }));
        assert!(matches!(wire[1], WireMessage::Tool { .. }));
    }

    #[test]
    fn request_omits_empty_tools() {
        let req = serialize_request(
            &GenerateOptions {
                provider: "deepseek".into(),
                model: "deepseek-chat".into(),
                messages: vec![human_text("hi")],
                reasoning_effort: None,
                system: Some("sys".into()),
                tools: Some(Vec::<ToolSchema>::new()),
                temperature: None,
                max_tokens: None,
                stop: None,
                session_id: None,
                purpose: None,
            },
            &RequestDefaults::default(),
        )
        .unwrap();
        assert!(req.tools.is_none());
        assert!(matches!(req.messages[0], WireMessage::System { .. }));
    }
}
