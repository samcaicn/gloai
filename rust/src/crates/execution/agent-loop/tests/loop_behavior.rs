//! Loop behavior: text turns, tools, cancel, empty enter, max-tokens stickiness.

use std::collections::VecDeque;
use std::sync::{Arc, OnceLock};
use std::time::Duration;

use async_trait::async_trait;
use dsh_agent_loop::{LoopRuntime, ReactLoopAgent};
use dsh_agent_runtime::{Agent, AgentOptions, CancelOptions};
use dsh_core_types::{
    human_text, CallId, ContentBlock, ContentBlockType, FinishReason, GenerateOptions, JsonValue,
    LlmError, LlmModelInfo, LlmProviderInfo, LlmResolvedModelInfo, MessageSource, SessionId,
    StreamChunk,
};
use dsh_events::{AgentCancelCause, EventBus, SessionEventBody, SessionHeader, TurnEndReason};
use dsh_runtime_ports::{ChunkStream, LlmPort};
use dsh_session::Session;
use dsh_system_prompt::SystemPrompt;
use dsh_tool_contracts::{
    object_schema, ToolError, ToolExecutionInput, ToolExecutionResult, ToolHandler, ToolRegistry,
};
use parking_lot::Mutex;
use serde_json::json;
use tokio::sync::Notify;

fn runtime(llm: Arc<dyn LlmPort>) -> LoopRuntime {
    LoopRuntime {
        llm,
        tools: Arc::new(ToolRegistry::new()),
        prompt: Arc::new(SystemPrompt::with_identity_and_persona(
            "You are DeepSeek Harness.",
        )),
        bus: EventBus::new(),
        max_parallel_tools: 10,
    }
}

fn options() -> AgentOptions {
    AgentOptions {
        provider: Some("mock".into()),
        model: Some("mock-model".into()),
        max_tokens: None,
    }
}

fn session() -> Arc<Session> {
    Session::create(SessionHeader::new(SessionId::new("s-loop"), 1)).unwrap()
}

enum ScriptTurn {
    Text(String),
    Tool {
        name: String,
        arguments: String,
        then_text: String,
    },
    MaxTokens(String),
}

struct ScriptPort {
    remaining: Mutex<VecDeque<ScriptTurn>>,
}

impl ScriptPort {
    fn new(turns: Vec<ScriptTurn>) -> Self {
        Self {
            remaining: Mutex::new(turns.into()),
        }
    }
}

fn stub_info(provider: &str, model: &str) -> LlmResolvedModelInfo {
    LlmResolvedModelInfo {
        info: LlmModelInfo {
            provider: provider.to_string(),
            id: model.to_string(),
            name: model.to_string(),
            description: None,
            input_modalities: None,
        },
        context_window: None,
        default_max_tokens: None,
    }
}

#[async_trait]
impl LlmPort for ScriptPort {
    fn provider_info(&self, provider: &str) -> LlmProviderInfo {
        LlmProviderInfo {
            id: provider.to_string(),
            name: "Script".into(),
        }
    }
    async fn list_models(&self, _provider: &str) -> Result<Vec<LlmModelInfo>, LlmError> {
        Ok(Vec::new())
    }
    async fn resolve_model(
        &self,
        provider: &str,
        model: &str,
    ) -> Result<LlmResolvedModelInfo, LlmError> {
        Ok(stub_info(provider, model))
    }
    fn stream(&self, _request: GenerateOptions) -> ChunkStream {
        let turn = self.remaining.lock().pop_front();
        let chunks = match turn {
            Some(ScriptTurn::Tool {
                name,
                arguments,
                then_text,
            }) => {
                self.remaining
                    .lock()
                    .push_front(ScriptTurn::Text(then_text));
                tool_chunks(&name, &arguments)
            }
            Some(ScriptTurn::Text(text)) => text_chunks(&text, FinishReason::Stop),
            Some(ScriptTurn::MaxTokens(text)) => text_chunks(&text, FinishReason::MaxTokens),
            None => text_chunks("empty-script", FinishReason::Stop),
        };
        Box::pin(async_stream::stream! {
            for chunk in chunks {
                yield Ok(chunk);
            }
        })
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
        StreamChunk::Finish {
            reason,
            replay_state: None,
        },
    ]
}

fn tool_chunks(name: &str, arguments: &str) -> Vec<StreamChunk> {
    vec![
        StreamChunk::BlockStart {
            index: 0,
            block_type: ContentBlockType::ToolCall,
        },
        StreamChunk::ToolCallDelta {
            index: 0,
            id: CallId::new("loop-call-1"),
            name: Some(name.to_string()),
            arguments_delta: arguments.to_string(),
        },
        StreamChunk::BlockEnd {
            index: 0,
            block: ContentBlock::tool_call(CallId::new("loop-call-1"), name, arguments),
        },
        StreamChunk::Finish {
            reason: FinishReason::ToolCalls,
            replay_state: None,
        },
    ]
}

struct Echo;

#[async_trait]
impl ToolHandler for Echo {
    fn name(&self) -> &str {
        "echo"
    }
    fn description(&self) -> &str {
        "Echo"
    }
    fn parameters(&self) -> JsonValue {
        object_schema(
            serde_json::Map::from_iter([("text".into(), json!({"type": "string"}))]),
            &["text"],
        )
    }
    async fn execute(&self, input: ToolExecutionInput) -> Result<ToolExecutionResult, ToolError> {
        let text = input.arguments["text"].as_str().unwrap_or_default();
        Ok(ToolExecutionResult::text(text.to_string()))
    }
}

#[tokio::test]
async fn text_turn_derives_history_from_the_log() {
    let llm = Arc::new(ScriptPort::new(vec![ScriptTurn::Text("hello-loop".into())]));
    let agent = ReactLoopAgent::new(runtime(llm), session(), options());
    agent.followup(human_text("hi"));
    agent.when_idle().await;
    let messages = agent.session().derive_messages();
    assert_eq!(messages.len(), 2);
    assert_eq!(
        dsh_core_types::flatten_text(&messages[1].content),
        "hello-loop"
    );
    assert!(agent
        .session()
        .events()
        .iter()
        .any(|event| matches!(event.body, SessionEventBody::RequestHeader { .. })));
}

#[tokio::test]
async fn tool_roundtrip_then_final_text() {
    let llm = Arc::new(ScriptPort::new(vec![ScriptTurn::Tool {
        name: "echo".into(),
        arguments: "{\"text\":\"pong\"}".into(),
        then_text: "done".into(),
    }]));
    let tools = Arc::new(ToolRegistry::new());
    let _keep = tools.register(Arc::new(Echo));
    let mut loop_runtime = runtime(llm);
    loop_runtime.tools = tools;
    let agent = ReactLoopAgent::new(loop_runtime, session(), options());
    agent.followup(human_text("use echo"));
    agent.when_idle().await;
    let text = agent
        .session()
        .derive_messages()
        .into_iter()
        .rev()
        .find_map(|message| {
            if matches!(message.source, MessageSource::Model { .. }) {
                Some(dsh_core_types::flatten_text(&message.content))
            } else {
                None
            }
        })
        .unwrap();
    assert_eq!(text, "done");
}

#[tokio::test]
async fn empty_first_enter_closes_the_turn_without_a_step() {
    let llm = Arc::new(ScriptPort::new(vec![ScriptTurn::Text(
        "should-not-run".into(),
    )]));
    let loop_runtime = runtime(llm);
    let _keep = loop_runtime.bus.on_pre_step(|mut input, _next| async move {
        input.messages.clear();
        input
    });
    let agent = ReactLoopAgent::new(loop_runtime, session(), options());
    agent.followup(human_text("x"));
    agent.when_idle().await;
    let events = agent.session().events();
    assert!(events
        .iter()
        .any(|event| matches!(event.body, SessionEventBody::TurnStart { .. })));
    assert!(events.iter().any(|event| matches!(
        event.body,
        SessionEventBody::TurnEnd {
            reason: TurnEndReason::Completed,
            ..
        }
    )));
    assert!(!events
        .iter()
        .any(|event| matches!(event.body, SessionEventBody::StepStart { .. })));
}

#[tokio::test]
async fn cancel_aborts_an_in_flight_turn() {
    let release = Arc::new(Notify::new());
    let llm = Arc::new(HangLlm {
        release: Arc::clone(&release),
    });
    let agent = ReactLoopAgent::new(runtime(llm), session(), options());
    agent.followup(human_text("hang"));
    tokio::time::sleep(Duration::from_millis(20)).await;
    agent.cancel(AgentCancelCause::User, CancelOptions::default());
    release.notify_waiters();
    agent.when_idle().await;
    assert!(agent.session().events().iter().any(|event| matches!(
        event.body,
        SessionEventBody::TurnEnd {
            reason: TurnEndReason::Aborted { .. },
            ..
        }
    )));
}

#[tokio::test]
async fn max_tokens_is_sticky_across_a_later_completed_step() {
    let slot: Arc<OnceLock<Arc<ReactLoopAgent>>> = Arc::new(OnceLock::new());
    let llm = Arc::new(InjectingLlm {
        remaining: Mutex::new(
            vec![
                ScriptTurn::MaxTokens("partial".into()),
                ScriptTurn::Text("later".into()),
            ]
            .into(),
        ),
        agent: Arc::clone(&slot),
    });
    let agent = ReactLoopAgent::new(runtime(llm), session(), options());
    let _ = slot.set(Arc::clone(&agent));
    agent.followup(human_text("go"));
    agent.when_idle().await;
    let reason = agent
        .session()
        .events()
        .into_iter()
        .rev()
        .find_map(|event| match event.body {
            SessionEventBody::TurnEnd { reason, .. } => Some(reason),
            _ => None,
        });
    assert!(matches!(reason, Some(TurnEndReason::MaxTokens)));
}

struct HangLlm {
    release: Arc<Notify>,
}

#[async_trait]
impl LlmPort for HangLlm {
    fn provider_info(&self, provider: &str) -> LlmProviderInfo {
        LlmProviderInfo {
            id: provider.to_string(),
            name: "Hang".into(),
        }
    }
    async fn list_models(&self, _provider: &str) -> Result<Vec<LlmModelInfo>, LlmError> {
        Ok(Vec::new())
    }
    async fn resolve_model(
        &self,
        provider: &str,
        model: &str,
    ) -> Result<LlmResolvedModelInfo, LlmError> {
        Ok(stub_info(provider, model))
    }
    fn stream(&self, _request: GenerateOptions) -> ChunkStream {
        let release = Arc::clone(&self.release);
        Box::pin(async_stream::stream! {
            release.notified().await;
            yield Err(LlmError::aborted());
        })
    }
}

struct InjectingLlm {
    remaining: Mutex<VecDeque<ScriptTurn>>,
    agent: Arc<OnceLock<Arc<ReactLoopAgent>>>,
}

#[async_trait]
impl LlmPort for InjectingLlm {
    fn provider_info(&self, provider: &str) -> LlmProviderInfo {
        LlmProviderInfo {
            id: provider.to_string(),
            name: "Inject".into(),
        }
    }
    async fn list_models(&self, _provider: &str) -> Result<Vec<LlmModelInfo>, LlmError> {
        Ok(Vec::new())
    }
    async fn resolve_model(
        &self,
        provider: &str,
        model: &str,
    ) -> Result<LlmResolvedModelInfo, LlmError> {
        Ok(stub_info(provider, model))
    }
    fn stream(&self, _request: GenerateOptions) -> ChunkStream {
        let turn = self.remaining.lock().pop_front();
        if matches!(&turn, Some(ScriptTurn::MaxTokens(_))) {
            if let Some(agent) = self.agent.get() {
                agent.inject(human_text("more"));
            }
        }
        let chunks = match turn {
            Some(ScriptTurn::Text(text)) => text_chunks(&text, FinishReason::Stop),
            Some(ScriptTurn::MaxTokens(text)) => text_chunks(&text, FinishReason::MaxTokens),
            Some(ScriptTurn::Tool { .. }) | None => text_chunks("empty-script", FinishReason::Stop),
        };
        Box::pin(async_stream::stream! {
            for chunk in chunks {
                yield Ok(chunk);
            }
        })
    }
}
