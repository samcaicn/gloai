//! Default Agent driver over queued turns and step-boundary input.

mod tool_calls;

use std::sync::{Arc, OnceLock, Weak};

use async_trait::async_trait;
use dsh_agent_runtime::{Agent, AgentError, AgentOptions, CancelOptions, Cancellation, Inbox};
use dsh_agent_stream::BlockAssembler;
use dsh_core_types::{
    create_assistant_message, create_user_message, error_chain, AssistantProvenance, ContentBlock,
    FinishReason, GenerateOptions, LlmCallConfig, LlmError, LlmFailure, Message, MessageSource,
    SessionId, UserMessage,
};
use dsh_events::{
    AgentCancelCause, AgentStatus, BusEvent, EpochHeader, EventBus, InboxTarget, PreStepInput,
    RequestContext, RequestHeaderReason, SessionEventBody, SurfaceOp, TurnEndReason, TurnStopping,
};
use dsh_runtime_ports::LlmPort;
use dsh_session::Session;
use dsh_system_prompt::{
    join_context_sections, render_context_sections, render_prompt, PromptAssembly, SystemPrompt,
};
use dsh_tool_contracts::ToolRegistry;
use parking_lot::Mutex;
use tokio::sync::{Mutex as AsyncMutex, Notify};
use tokio_stream::StreamExt;
use tracing::debug;

use crate::tool_calls::execute_tool_calls;

pub const DEFAULT_MAX_PARALLEL_TOOL_CALLS: usize = 10;

#[derive(Clone)]
pub struct LoopRuntime {
    pub llm: Arc<dyn LlmPort>,
    pub tools: Arc<ToolRegistry>,
    pub prompt: Arc<SystemPrompt>,
    pub bus: EventBus,
    pub max_parallel_tools: usize,
}

enum Phase {
    Idle { last_turn: u32 },
    Running { turn: u32, step: u32 },
}

struct DriverState {
    phase: Phase,
    cancellation: Cancellation,
    wake_requested: bool,
}

pub struct ReactLoopAgent {
    this: OnceLock<Weak<Self>>,
    id: SessionId,
    options: AgentOptions,
    session: Arc<Session>,
    inbox: Arc<Inbox>,
    runtime: LoopRuntime,
    state: Mutex<DriverState>,
    idle: Notify,
    activity: AsyncMutex<u64>,
    request_header_logged: Mutex<bool>,
}

impl ReactLoopAgent {
    pub fn new(runtime: LoopRuntime, session: Arc<Session>, options: AgentOptions) -> Arc<Self> {
        let last_turn = session.last_turn();
        let agent = Arc::new(Self {
            this: OnceLock::new(),
            id: session.id(),
            options,
            inbox: Inbox::new(Arc::clone(&session)),
            session,
            runtime,
            state: Mutex::new(DriverState {
                phase: Phase::Idle { last_turn },
                cancellation: Cancellation::new(),
                wake_requested: false,
            }),
            idle: Notify::new(),
            activity: AsyncMutex::new(0),
            request_header_logged: Mutex::new(false),
        });
        let _ = agent.this.set(Arc::downgrade(&agent));
        agent
    }

    fn arc(&self) -> Arc<Self> {
        self.this
            .get()
            .and_then(Weak::upgrade)
            .expect("ReactLoopAgent is always constructed inside Arc")
    }

    fn send(&self, message: UserMessage, target: InboxTarget, wakeup: bool) {
        let waking_after_abort = {
            let state = self.state.lock();
            wakeup && !matches!(state.phase, Phase::Idle { .. }) && state.cancellation.is_cancelled()
        };
        let resolved = if waking_after_abort {
            InboxTarget::NextTurn
        } else {
            target
        };
        let start = match resolved {
            InboxTarget::NextTurn => self.inbox.next_turn().len(),
            InboxTarget::NextStep => self.inbox.next_step().len(),
        };
        let _ = self.inbox.splice(resolved, start, 0, vec![message]);
        if wakeup {
            self.wake(waking_after_abort);
        }
    }

    fn wake(&self, wake_after_abort: bool) {
        {
            let mut state = self.state.lock();
            if !matches!(state.phase, Phase::Idle { .. }) {
                if wake_after_abort
                    && !matches!(state.cancellation.cause(), Some(AgentCancelCause::Disposed))
                {
                    state.wake_requested = true;
                }
                return;
            }
            let last_turn = match state.phase {
                Phase::Idle { last_turn } => last_turn,
                Phase::Running { .. } => return,
            };
            state.phase = Phase::Running {
                turn: last_turn,
                step: 0,
            };
            state.cancellation = Cancellation::new();
            state.wake_requested = false;
        }
        let _ = self.runtime.bus.clone();
        let agent = self.arc();
        tokio::spawn(async move {
            let _ = agent
                .runtime
                .bus
                .emit(BusEvent::AgentStatus {
                    status: AgentStatus::Running,
                })
                .await;
            agent.kick().await;
        });
    }

    async fn kick(self: Arc<Self>) {
        let _guard = self.activity.lock().await;
        if let Err(error) = self.run_turns().await {
            debug!("agent driver contained error: {error}");
        }
        let mut replay = false;
        {
            let mut state = self.state.lock();
            if let Phase::Running { turn, .. } = state.phase {
                replay = state.wake_requested && self.inbox.has_pending();
                state.phase = Phase::Idle { last_turn: turn };
            }
        }
        self.idle.notify_waiters();
        let _ = self
            .runtime
            .bus
            .emit(BusEvent::AgentStatus {
                status: AgentStatus::Idle,
            })
            .await;
        if replay {
            self.wake(false);
        }
    }

    async fn run_turns(self: &Arc<Self>) -> Result<(), String> {
        while self.turn().await? {}
        Ok(())
    }

    async fn turn(self: &Arc<Self>) -> Result<bool, String> {
        let (turn, token) = {
            let mut state = self.state.lock();
            match &mut state.phase {
                Phase::Running { turn, step, .. } => {
                    *turn += 1;
                    *step = 0;
                    (*turn, state.cancellation.token.clone())
                }
                Phase::Idle { .. } => return Err("turn without driver reservation".into()),
            }
        };
        self.session
            .append(SessionEventBody::TurnStart { turn }, None, None)
            .map_err(|e| e.to_string())?;
        let mut turn_ends: Option<TurnEndReason> = None;
        let mut target = InboxTarget::NextTurn;
        let outcome: Result<bool, String> = async {
            loop {
                if token.is_cancelled() {
                    return Err(LlmError::aborted().to_string());
                }
                let step = {
                    let state = self.state.lock();
                    match state.phase {
                        Phase::Running { step, .. } => step + 1,
                        Phase::Idle { .. } => return Err("pre-step outside running phase".into()),
                    }
                };
                let decision = self.pre_step(target, turn, step).await?;
                match decision {
                    PreStep::Reject => {
                        turn_ends = Some(TurnEndReason::Blocked);
                        return Ok(false);
                    }
                    PreStep::Enter { messages, assembly } => {
                        if turn_ends.is_some() && messages.is_empty() {
                            break;
                        }
                        let first_step = matches!(self.state.lock().phase, Phase::Running { step: 0, .. });
                        if first_step && messages.is_empty() {
                            turn_ends = Some(TurnEndReason::Completed);
                            return Ok(false);
                        }
                        self.session
                            .append(SessionEventBody::StepStart { turn, step }, None, None)
                            .map_err(|e| e.to_string())?;
                        if let Phase::Running { step: s, .. } = &mut self.state.lock().phase {
                            *s = step;
                        }
                        for message in messages {
                            self.session
                                .append(
                                    SessionEventBody::UserMessage(message),
                                    Some(SurfaceOp::Append),
                                    None,
                                )
                                .map_err(|e| e.to_string())?;
                        }
                        let step_end = self.step(&assembly).await;
                        self.session
                            .append(SessionEventBody::StepEnd { turn, step }, None, None)
                            .map_err(|e| e.to_string())?;
                        let step_end = step_end?;
                        if turn_ends
                            .as_ref()
                            .is_none_or(|reason| !matches!(reason, TurnEndReason::MaxTokens))
                        {
                            turn_ends = step_end;
                        }
                        if token.is_cancelled() {
                            return Err(LlmError::aborted().to_string());
                        }
                        if turn_ends.is_some() && self.inbox.next_step().is_empty() {
                            self.runtime
                                .bus
                                .serial_turn_stopping(TurnStopping { turn })
                                .await;
                        }
                        if turn_ends.is_some() && self.inbox.next_step().is_empty() {
                            break;
                        }
                        target = InboxTarget::NextStep;
                    }
                }
            }
            Ok(true)
        }
        .await;

        let more = match &outcome {
            Ok(more) => *more,
            Err(error) => {
                if token.is_cancelled() {
                    turn_ends = Some(TurnEndReason::Aborted {
                        reason: self
                            .state
                            .lock()
                            .cancellation
                            .cause()
                            .unwrap_or(AgentCancelCause::User),
                    });
                } else {
                    turn_ends = Some(TurnEndReason::Error {
                        error: LlmFailure::new(error.clone(), "UNKNOWN"),
                    });
                    self.runtime
                        .bus
                        .emit(BusEvent::AgentError {
                            turn,
                            step: match self.state.lock().phase {
                                Phase::Running { step, .. } => step,
                                Phase::Idle { .. } => 0,
                            },
                            message: error.clone(),
                        })
                        .await;
                }
                false
            }
        };
        self.session
            .append(
                SessionEventBody::TurnEnd {
                    turn,
                    reason: turn_ends.unwrap_or(TurnEndReason::Completed),
                },
                None,
                None,
            )
            .map_err(|e| e.to_string())?;
        if !more || !self.inbox.has_pending() {
            return Ok(false);
        }
        {
            let mut state = self.state.lock();
            state.cancellation = Cancellation::new();
            state.wake_requested = false;
            if let Phase::Running { step, .. } = &mut state.phase {
                *step = 0;
            }
        }
        Ok(true)
    }

    async fn pre_step(&self, target: InboxTarget, turn: u32, step: u32) -> Result<PreStep, String> {
        let claimed = self.inbox.claim(target, turn).map_err(|e| e.to_string())?;
        let assembly = self.runtime.prompt.assemble().map_err(|e| e.to_string())?;
        let sections = render_context_sections(&assembly).map_err(|e| e.to_string())?;
        let joined = join_context_sections(&sections);
        let mut messages = claimed;
        if !joined.is_empty() {
            messages.push(create_user_message(
                vec![ContentBlock::text(joined)],
                MessageSource::Plugin {
                    plugin: "runtime-context".into(),
                    form: Some("snapshot".into()),
                },
            ));
        }
        let input = self
            .runtime
            .bus
            .waterfall_pre_step(PreStepInput {
                messages,
                turn,
                step,
            })
            .await;
        let rejected = input.messages.iter().any(|m| {
            matches!(
                &m.source,
                MessageSource::Plugin { plugin, .. } if plugin == "reject"
            )
        });
        if rejected {
            return Ok(PreStep::Reject);
        }
        Ok(PreStep::Enter {
            messages: input.messages,
            assembly,
        })
    }

    async fn step(&self, assembly: &PromptAssembly) -> Result<Option<TurnEndReason>, String> {
        let (turn, step, token) = {
            let state = self.state.lock();
            match &state.phase {
                Phase::Running { turn, step, .. } => (*turn, *step, state.cancellation.token.clone()),
                Phase::Idle { .. } => return Err("step outside running phase".into()),
            }
        };
        let system = render_prompt(assembly).map_err(|e| e.to_string())?;
        if token.is_cancelled() {
            return Err(LlmError::aborted().to_string());
        }
        let derived = self.session.derive_messages();
        let request = self
            .build_request(turn, step, assembly, &system, derived.clone())
            .await?;
        if request.messages != derived {
            return Err("model-visible history is not reconstructable from the session log".into());
        }
        let mut assembler = BlockAssembler::new();
        let mut chunk_seqs = Vec::new();
        let mut stream = self.runtime.llm.stream(request.clone());
        while let Some(item) = stream.next().await {
            if token.is_cancelled() {
                return Err(LlmError::aborted().to_string());
            }
            let chunk = item.map_err(|e| e.to_string())?;
            let event = self
                .session
                .append(
                    SessionEventBody::AssistantChunk {
                        turn,
                        step,
                        chunk: chunk.clone(),
                    },
                    None,
                    None,
                )
                .map_err(|e| e.to_string())?;
            chunk_seqs.push(event.seq);
            assembler.push(&chunk);
        }
        let finish = assembler.finish();
        match &finish {
            FinishReason::Error { failure } | FinishReason::Aborted { failure } => {
                return Err(LlmError::from_failure(failure.clone()).to_string());
            }
            FinishReason::MaxTokens | FinishReason::Stop | FinishReason::ToolCalls => {}
        }
        let message = create_assistant_message(
            assembler.blocks(),
            AssistantProvenance {
                provider: request.provider.clone(),
                model: request.model.clone(),
                replay_state: assembler.replay_state().cloned(),
            },
        );
        self.session
            .append(
                SessionEventBody::AssistantMessage {
                    turn,
                    step,
                    message: message.clone(),
                    usage: assembler.usage().cloned(),
                },
                Some(SurfaceOp::Append),
                Some(chunk_seqs),
            )
            .map_err(|e| e.to_string())?;
        if matches!(finish, FinishReason::MaxTokens) {
            return Ok(Some(TurnEndReason::MaxTokens));
        }
        let tool_calls: Vec<_> = message
            .content
            .iter()
            .filter_map(|block| match block {
                ContentBlock::ToolCall(call) => Some(call.clone()),
                _ => None,
            })
            .collect();
        if tool_calls.is_empty() {
            return Ok(Some(TurnEndReason::Completed));
        }
        let concluded = execute_tool_calls(
            Arc::clone(&self.session),
            Arc::clone(&self.inbox),
            Arc::clone(&self.runtime.tools),
            turn,
            step,
            tool_calls,
            token,
            self.runtime.max_parallel_tools,
        )
        .await?;
        if concluded {
            Ok(Some(TurnEndReason::Completed))
        } else {
            Ok(None)
        }
    }

    async fn build_request(
        &self,
        _turn: u32,
        _step: u32,
        assembly: &PromptAssembly,
        system: &str,
        messages: Vec<Message>,
    ) -> Result<GenerateOptions, String> {
        let logged = *self.request_header_logged.lock();
        let persisted = self.session.request_header();
        let seed = if logged {
            persisted
                .as_ref()
                .map(|header| header.config.clone())
                .unwrap_or_default()
        } else {
            LlmCallConfig {
                provider: self.options.provider.clone().unwrap_or_default(),
                model: self.options.model.clone().unwrap_or_default(),
                reasoning_effort: None,
                temperature: None,
                max_tokens: self.options.max_tokens,
                stop: None,
            }
        };
        let proposed = self.runtime.bus.waterfall_request(seed).await;
        if proposed.provider.is_empty() || proposed.model.is_empty() {
            return Err(AgentError::MissingRoute(self.id.to_string()).to_string());
        }
        let prepared = self.runtime.llm.prepare_call(proposed.clone()).await.ok();
        let config = prepared
            .as_ref()
            .map(|call| call.config.clone())
            .unwrap_or(proposed);
        let header = EpochHeader {
            config: config.clone(),
            adapter_defaults: prepared.as_ref().and_then(|call| call.adapter_defaults.clone()),
            system: if system.is_empty() {
                None
            } else {
                Some(system.to_string())
            },
            tools: if assembly.tools.is_empty() {
                None
            } else {
                Some(assembly.tools.clone())
            },
        };
        {
            let mut logged_flag = self.request_header_logged.lock();
            if !*logged_flag {
                let reason = if persisted.is_none() {
                    RequestHeaderReason::Initial
                } else {
                    RequestHeaderReason::Resume
                };
                self.session
                    .append(
                        SessionEventBody::RequestHeader {
                            header: header.clone(),
                            reason,
                        },
                        None,
                        None,
                    )
                    .map_err(|e| e.to_string())?;
                *logged_flag = true;
            } else if persisted.as_ref() != Some(&header) {
                self.session
                    .append(
                        SessionEventBody::RequestHeader {
                            header: header.clone(),
                            reason: RequestHeaderReason::Change,
                        },
                        None,
                        None,
                    )
                    .map_err(|e| e.to_string())?;
            }
        }
        let request_context = RequestContext {
            provider: config.provider.clone(),
            model: config.model.clone(),
            context_window: prepared.as_ref().and_then(|call| call.context_window),
        };
        if self.session.request_context().as_ref() != Some(&request_context) {
            self.session
                .append(
                    SessionEventBody::RequestContext(request_context),
                    None,
                    None,
                )
                .map_err(|e| e.to_string())?;
        }
        Ok(GenerateOptions {
            provider: config.provider,
            model: config.model,
            messages,
            reasoning_effort: config.reasoning_effort,
            system: header.system,
            tools: header.tools,
            temperature: config.temperature.as_ref().and_then(serde_json::Number::as_f64),
            max_tokens: config.max_tokens,
            stop: config.stop,
            session_id: Some(self.session.id()),
            purpose: None,
        })
    }
}

enum PreStep {
    Reject,
    Enter {
        messages: Vec<UserMessage>,
        assembly: PromptAssembly,
    },
}

#[async_trait]
impl Agent for ReactLoopAgent {
    fn id(&self) -> SessionId {
        self.id.clone()
    }

    fn options(&self) -> AgentOptions {
        self.options.clone()
    }

    fn session(&self) -> Arc<Session> {
        Arc::clone(&self.session)
    }

    fn inbox(&self) -> Arc<Inbox> {
        Arc::clone(&self.inbox)
    }

    fn status(&self) -> AgentStatus {
        match self.state.lock().phase {
            Phase::Idle { .. } => AgentStatus::Idle,
            Phase::Running { .. } => AgentStatus::Running,
        }
    }

    fn followup(&self, input: UserMessage) {
        self.send(input, InboxTarget::NextTurn, true);
    }

    fn steer(&self, input: UserMessage) {
        self.send(input, InboxTarget::NextStep, true);
    }

    fn inject(&self, input: UserMessage) {
        self.send(input, InboxTarget::NextStep, false);
    }

    fn cancel(&self, cause: AgentCancelCause, options: CancelOptions) {
        if !options.keep_inbox {
            let _ = self.inbox.clear();
            self.state.lock().wake_requested = false;
        }
        self.state.lock().cancellation.cancel(cause);
    }

    async fn when_idle(&self) {
        loop {
            if matches!(self.state.lock().phase, Phase::Idle { .. }) {
                let _ = self.activity.lock().await;
                if matches!(self.state.lock().phase, Phase::Idle { .. }) {
                    return;
                }
            }
            self.idle.notified().await;
        }
    }
}
