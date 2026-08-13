//! Live event bus: emit (contained), waterfall (must call next), serial (no next).

use std::future::Future;
use std::pin::Pin;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

use parking_lot::RwLock;
use tracing::error;

pub type BoxFuture<T> = Pin<Box<dyn Future<Output = T> + Send + 'static>>;

/// Dropping the disposer unregisters the listener.
pub struct Disposer {
    unreg: Option<Box<dyn FnOnce() + Send>>,
}

impl Disposer {
    pub fn new(unreg: impl FnOnce() + Send + 'static) -> Self {
        Self {
            unreg: Some(Box::new(unreg)),
        }
    }
}

impl Drop for Disposer {
    fn drop(&mut self) {
        if let Some(unreg) = self.unreg.take() {
            unreg();
        }
    }
}

type NextFn<T> = Box<dyn FnOnce(T) -> BoxFuture<T> + Send>;
type WaterfallHandler<T> = Arc<dyn Fn(T, NextFn<T>) -> BoxFuture<T> + Send + Sync>;
type EmitHandler<T> = Arc<dyn Fn(T) -> BoxFuture<()> + Send + Sync>;
type SerialHandler<T> = Arc<dyn Fn(T) -> BoxFuture<()> + Send + Sync>;

struct Slot<H> {
    id: u64,
    handler: H,
}

/// Continuation that a waterfall listener MUST invoke to delegate.
pub struct Next<T> {
    inner: Option<NextFn<T>>,
}

impl<T> Next<T> {
    /// Delegate to the rest of the chain. Returning without this short-circuits.
    pub fn run(mut self, value: T) -> BoxFuture<T> {
        let inner = self.inner.take().expect("next.run called twice");
        inner(value)
    }
}

impl<T> Drop for Next<T> {
    fn drop(&mut self) {
        // Dropping without run() is the documented short-circuit.
        let _ = self.inner.take();
    }
}

struct Inner {
    next_id: AtomicU64,
}

/// Typed live bus. Observer failures on emit are logged and contained.
#[derive(Clone)]
pub struct EventBus {
    inner: Arc<Inner>,
    emit: Arc<RwLock<Vec<Slot<EmitHandler<BusEvent>>>>>,
    waterfall_pre_step: Arc<RwLock<Vec<Slot<WaterfallHandler<PreStepInput>>>>>,
    waterfall_request: Arc<RwLock<Vec<Slot<WaterfallHandler<dsh_core_types::LlmCallConfig>>>>>,
    serial_turn_stopping: Arc<RwLock<Vec<Slot<SerialHandler<TurnStopping>>>>>,
}

#[derive(Clone, Debug)]
pub enum BusEvent {
    SessionCreated {
        session_id: dsh_core_types::SessionId,
    },
    SessionEvent {
        event: crate::SessionEvent,
    },
    AgentStatus {
        status: AgentStatus,
    },
    AgentError {
        turn: u32,
        step: u32,
        message: String,
    },
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AgentStatus {
    Idle,
    Running,
}

#[derive(Clone, Debug)]
pub struct PreStepInput {
    pub messages: Vec<dsh_core_types::UserMessage>,
    pub turn: u32,
    pub step: u32,
}

#[derive(Clone, Debug)]
pub struct TurnStopping {
    pub turn: u32,
}

impl Default for EventBus {
    fn default() -> Self {
        Self::new()
    }
}

impl EventBus {
    pub fn new() -> Self {
        Self {
            inner: Arc::new(Inner {
                next_id: AtomicU64::new(1),
            }),
            emit: Arc::new(RwLock::new(Vec::new())),
            waterfall_pre_step: Arc::new(RwLock::new(Vec::new())),
            waterfall_request: Arc::new(RwLock::new(Vec::new())),
            serial_turn_stopping: Arc::new(RwLock::new(Vec::new())),
        }
    }

    fn next_id(&self) -> u64 {
        self.inner.next_id.fetch_add(1, Ordering::Relaxed)
    }

    pub fn on_emit<F, Fut>(&self, handler: F) -> Disposer
    where
        F: Fn(BusEvent) -> Fut + Send + Sync + 'static,
        Fut: Future<Output = ()> + Send + 'static,
    {
        let id = self.next_id();
        let wrapped: EmitHandler<BusEvent> = Arc::new(move |event| Box::pin(handler(event)));
        self.emit.write().push(Slot {
            id,
            handler: wrapped,
        });
        let emit = Arc::clone(&self.emit);
        Disposer::new(move || emit.write().retain(|slot| slot.id != id))
    }

    pub fn on_pre_step<F, Fut>(&self, handler: F) -> Disposer
    where
        F: Fn(PreStepInput, Next<PreStepInput>) -> Fut + Send + Sync + 'static,
        Fut: Future<Output = PreStepInput> + Send + 'static,
    {
        register_waterfall(&self.waterfall_pre_step, self.next_id(), handler)
    }

    pub fn on_request<F, Fut>(&self, handler: F) -> Disposer
    where
        F: Fn(dsh_core_types::LlmCallConfig, Next<dsh_core_types::LlmCallConfig>) -> Fut
            + Send
            + Sync
            + 'static,
        Fut: Future<Output = dsh_core_types::LlmCallConfig> + Send + 'static,
    {
        register_waterfall(&self.waterfall_request, self.next_id(), handler)
    }

    pub fn on_turn_stopping<F, Fut>(&self, handler: F) -> Disposer
    where
        F: Fn(TurnStopping) -> Fut + Send + Sync + 'static,
        Fut: Future<Output = ()> + Send + 'static,
    {
        let id = self.next_id();
        let wrapped: SerialHandler<TurnStopping> = Arc::new(move |event| Box::pin(handler(event)));
        self.serial_turn_stopping.write().push(Slot {
            id,
            handler: wrapped,
        });
        let list = Arc::clone(&self.serial_turn_stopping);
        Disposer::new(move || list.write().retain(|slot| slot.id != id))
    }

    pub async fn emit(&self, event: BusEvent) {
        let handlers: Vec<_> = self.emit.read().iter().map(|s| Arc::clone(&s.handler)).collect();
        for handler in handlers {
            let fut = handler(event.clone());
            if let Err(error) = futures::FutureExt::catch_unwind(std::panic::AssertUnwindSafe(fut)).await
            {
                error!("emit listener panicked: {error:?}");
            }
        }
    }

    pub async fn waterfall_pre_step(&self, input: PreStepInput) -> PreStepInput {
        let handlers: Vec<_> = self
            .waterfall_pre_step
            .read()
            .iter()
            .map(|s| Arc::clone(&s.handler))
            .collect();
        run_waterfall(handlers, input).await
    }

    pub async fn waterfall_request(
        &self,
        config: dsh_core_types::LlmCallConfig,
    ) -> dsh_core_types::LlmCallConfig {
        let handlers: Vec<_> = self
            .waterfall_request
            .read()
            .iter()
            .map(|s| Arc::clone(&s.handler))
            .collect();
        run_waterfall(handlers, config).await
    }

    pub async fn serial_turn_stopping(&self, event: TurnStopping) {
        let handlers: Vec<_> = self
            .serial_turn_stopping
            .read()
            .iter()
            .map(|s| Arc::clone(&s.handler))
            .collect();
        for handler in handlers {
            handler(event.clone()).await;
        }
    }
}

fn register_waterfall<T, F, Fut>(
    list: &Arc<RwLock<Vec<Slot<WaterfallHandler<T>>>>>,
    id: u64,
    handler: F,
) -> Disposer
where
    T: Send + 'static,
    F: Fn(T, Next<T>) -> Fut + Send + Sync + 'static,
    Fut: Future<Output = T> + Send + 'static,
{
    let wrapped: WaterfallHandler<T> = Arc::new(move |value, next_fn| {
        Box::pin(handler(value, Next { inner: Some(next_fn) }))
    });
    list.write().push(Slot {
        id,
        handler: wrapped,
    });
    let list = Arc::clone(list);
    Disposer::new(move || list.write().retain(|slot| slot.id != id))
}

fn run_waterfall<T: Send + 'static>(handlers: Vec<WaterfallHandler<T>>, value: T) -> BoxFuture<T> {
    let handlers = Arc::new(handlers);
    fn invoke<T: Send + 'static>(
        handlers: Arc<Vec<WaterfallHandler<T>>>,
        index: usize,
        value: T,
    ) -> BoxFuture<T> {
        Box::pin(async move {
            if index >= handlers.len() {
                return value;
            }
            let handler = Arc::clone(&handlers[index]);
            let rest = Arc::clone(&handlers);
            let next: NextFn<T> = Box::new(move |v| invoke(rest, index + 1, v));
            handler(value, next).await
        })
    }
    invoke(handlers, 0, value)
}

pub trait WaterfallFn<T>: Send + Sync {}
pub trait SerialFn<T>: Send + Sync {}

#[cfg(test)]
mod tests {
    use super::*;
    use dsh_core_types::human_text;

    #[tokio::test]
    async fn waterfall_requires_next_or_short_circuits() {
        let bus = EventBus::new();
        let _keep = bus.on_pre_step(|mut input, _next| async move {
            input.messages.clear();
            input
        });
        let out = bus
            .waterfall_pre_step(PreStepInput {
                messages: vec![human_text("keep me")],
                turn: 1,
                step: 1,
            })
            .await;
        assert!(out.messages.is_empty());
    }

    #[tokio::test]
    async fn waterfall_next_delegates() {
        let bus = EventBus::new();
        let _a = bus.on_pre_step(|input, next| async move { next.run(input).await });
        let out = bus
            .waterfall_pre_step(PreStepInput {
                messages: vec![human_text("x")],
                turn: 1,
                step: 1,
            })
            .await;
        assert_eq!(out.messages.len(), 1);
    }

    #[tokio::test]
    async fn disposer_unregisters() {
        let bus = EventBus::new();
        let hit = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let flag = Arc::clone(&hit);
        let d = bus.on_emit(move |_| {
            let flag = Arc::clone(&flag);
            async move {
                flag.store(true, std::sync::atomic::Ordering::SeqCst);
            }
        });
        drop(d);
        bus.emit(BusEvent::AgentStatus {
            status: AgentStatus::Idle,
        })
        .await;
        assert!(!hit.load(std::sync::atomic::Ordering::SeqCst));
    }
}
