//! Agent handle, durable inbox, and live registry.

mod inbox;

pub use inbox::Inbox;

use std::sync::Arc;

use async_trait::async_trait;
use dsh_core_types::{SessionId, UserMessage};
use dsh_events::{AgentCancelCause, AgentStatus, EventBus};
use dsh_session::Session;
use parking_lot::Mutex;
use thiserror::Error;
use tokio::sync::Notify;
use tokio_util::sync::CancellationToken;

#[derive(Debug, Error)]
pub enum AgentError {
    #[error("agent `{0}` already has active work")]
    Busy(String),
    #[error("agent `{0}` was not found")]
    NotFound(String),
    #[error("agent `{0}` has no provider/model")]
    MissingRoute(String),
}

#[derive(Clone, Debug, Default)]
pub struct AgentOptions {
    pub provider: Option<String>,
    pub model: Option<String>,
    pub max_tokens: Option<u32>,
}

pub use dsh_events::InboxTarget;

#[derive(Default)]
pub struct CancelOptions {
    pub keep_inbox: bool,
}

/// Public live-agent handle. Concrete driving belongs to `dsh-agent-loop`.
#[async_trait]
pub trait Agent: Send + Sync {
    fn id(&self) -> SessionId;
    fn options(&self) -> AgentOptions;
    fn session(&self) -> Arc<Session>;
    fn inbox(&self) -> Arc<Inbox>;
    fn status(&self) -> AgentStatus;
    fn followup(&self, input: UserMessage);
    fn steer(&self, input: UserMessage);
    fn inject(&self, input: UserMessage);
    fn cancel(&self, cause: AgentCancelCause, options: CancelOptions);
    async fn when_idle(&self);
}

pub struct Cancellation {
    pub token: CancellationToken,
    cause: Mutex<Option<AgentCancelCause>>,
}

impl Cancellation {
    pub fn new() -> Self {
        Self {
            token: CancellationToken::new(),
            cause: Mutex::new(None),
        }
    }

    pub fn cancel(&self, cause: AgentCancelCause) {
        let mut slot = self.cause.lock();
        if slot.is_none() {
            *slot = Some(cause);
        }
        self.token.cancel();
    }

    pub fn cause(&self) -> Option<AgentCancelCause> {
        self.cause.lock().clone()
    }

    pub fn is_cancelled(&self) -> bool {
        self.token.is_cancelled()
    }

    pub fn child(&self) -> Self {
        Self {
            token: self.token.child_token(),
            cause: Mutex::new(self.cause.lock().clone()),
        }
    }
}

impl Default for Cancellation {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Clone)]
pub struct IdleGate {
    notify: Arc<Notify>,
    generation: Arc<Mutex<u64>>,
}

impl Default for IdleGate {
    fn default() -> Self {
        Self {
            notify: Arc::new(Notify::new()),
            generation: Arc::new(Mutex::new(0)),
        }
    }
}

impl IdleGate {
    pub fn bump(&self) {
        *self.generation.lock() += 1;
        self.notify.notify_waiters();
    }

    pub async fn wait(&self) {
        loop {
            let seen = *self.generation.lock();
            let notified = self.notify.notified();
            let mut notified = std::pin::pin!(notified);
            notified.as_mut().enable();
            if *self.generation.lock() != seen {
                return;
            }
            notified.await;
        }
    }
}

#[derive(Default)]
pub struct AgentRegistry {
    agents: Mutex<std::collections::HashMap<String, Arc<dyn Agent>>>,
    bus: EventBus,
}

impl AgentRegistry {
    pub fn new(bus: EventBus) -> Self {
        Self {
            agents: Mutex::new(std::collections::HashMap::new()),
            bus,
        }
    }

    pub fn bus(&self) -> EventBus {
        self.bus.clone()
    }

    pub fn insert(&self, agent: Arc<dyn Agent>) {
        self.agents.lock().insert(agent.id().to_string(), agent);
    }

    pub fn get(&self, id: &SessionId) -> Result<Arc<dyn Agent>, AgentError> {
        self.agents
            .lock()
            .get(id.as_str())
            .cloned()
            .ok_or_else(|| AgentError::NotFound(id.to_string()))
    }

    pub fn remove(&self, id: &SessionId) -> Option<Arc<dyn Agent>> {
        self.agents.lock().remove(id.as_str())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use dsh_core_types::human_text;
    use dsh_events::SessionHeader;
    use dsh_session::Session;

    #[test]
    fn inbox_claim_takes_next_step_then_one_turn() {
        let session = Session::create(SessionHeader::new(SessionId::new("s"), 1)).unwrap();
        let inbox = Inbox::new(Arc::clone(&session));
        inbox
            .splice(InboxTarget::NextTurn, 0, 0, vec![human_text("t")])
            .unwrap();
        inbox
            .splice(InboxTarget::NextStep, 0, 0, vec![human_text("s")])
            .unwrap();
        let claimed = inbox.claim(InboxTarget::NextTurn, 1).unwrap();
        assert_eq!(claimed.len(), 2);
        assert!(inbox.next_turn().is_empty());
        assert!(inbox.next_step().is_empty());
    }
}
