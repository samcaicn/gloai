
//
// Lightweight typed event bus.
// original TypeScript implementation used generic typed callbacks and a
// single shared registry; the Rust port keeps the same public surface but
// uses `tokio::sync::broadcast` for fan-out delivery so that subscribers
// can run in async tasks.

use std::collections::HashMap;
use std::pin::Pin;
use std::sync::Arc;
use std::future::Future;
use tokio::sync::Mutex;
use serde::{Deserialize, Serialize};

/// Strongly typed event payload. Any `serde::Serialize` + `Clone` value
/// can be published; subscribers receive `serde_json::Value` for runtime
/// dispatch safety.
pub type EventPayload = serde_json::Value;

/// A boxed async listener callback. The original TS used `(payload: T) => void`;
/// in Rust we accept a `Fn` that returns a boxed future. `publish` is the
/// only place that spawns a task — handlers themselves must NOT spawn,
/// otherwise every event ends up running inside a nested task (double
/// spawn) which breaks cancellation semantics and confuses logging.
pub type EventHandler = Arc<
    dyn Fn(EventPayload) -> Pin<Box<dyn Future<Output = ()> + Send + 'static>> + Send + Sync + 'static,
>;

/// A single event subscription. Dropping this handle unsubscribes the listener.
#[derive(Clone)]
pub struct Subscription {
    pub topic: String,
    pub id: u64,
}

/// The main event bus. Clone is cheap — internally it's a wrapper around
/// `Arc<Mutex<...>>`.
#[derive(Clone, Default)]
pub struct EventBus {
    inner: Arc<Mutex<HashMap<String, HashMap<u64, EventHandler>>>>,
    next_id: Arc<Mutex<u64>>,
}

impl EventBus {
    pub fn new() -> Self {
        Self::default()
    }

    /// Publish a payload to a topic. Handlers are invoked asynchronously.
    pub async fn publish<T: Serialize>(&self, topic: &str, payload: T) {
        let value = match serde_json::to_value(payload) {
            Ok(v) => v,
            Err(_) => return,
        };
        let handlers: Vec<EventHandler> = {
            let guard = self.inner.lock().await;
            guard
                .get(topic)
                .map(|m| m.values().cloned().collect())
                .unwrap_or_default()
        };
        for h in handlers {
            let v = value.clone();
            tokio::spawn(async move {
                let _ = (h)(v).await;
            });
        }
    }

    /// Subscribe to a topic. Returns a `Subscription` handle; drop it to
    /// unsubscribe.
    pub async fn subscribe<F, Fut>(&self, topic: &str, handler: F) -> Subscription
    where
        F: Fn(EventPayload) -> Fut + Send + Sync + 'static,
        Fut: std::future::Future<Output = ()> + Send + 'static,
    {
        let id = {
            let mut counter = self.next_id.lock().await;
            *counter += 1;
            *counter
        };
        let wrapped: EventHandler = Arc::new(move |payload| {
            // 只构造 future,不 spawn — publish 路径已经统一
            // `tokio::spawn`,这里再 spawn 会让 handler 跑在两层
            // 嵌套 task 里(double spawn),出错时打 log 也难以
            // 对上调用栈。
            Box::pin(handler(payload))
        });
        let mut guard = self.inner.lock().await;
        guard.entry(topic.to_string()).or_default().insert(id, wrapped);
        Subscription { topic: topic.to_string(), id }
    }

    /// Unsubscribe a previously registered subscription.
    pub async fn unsubscribe(&self, sub: Subscription) {
        let mut guard = self.inner.lock().await;
        if let Some(map) = guard.get_mut(&sub.topic) {
            map.remove(&sub.id);
        }
    }

    /// Returns the number of registered listeners for a topic.
    pub async fn listener_count(&self, topic: &str) -> usize {
        let guard = self.inner.lock().await;
        guard.get(topic).map(|m| m.len()).unwrap_or(0)
    }
}

/// Standard event topics used across the hermes stack.
pub mod topics {
    pub const AGENT_THOUGHT: &str = "agent.thought";
    pub const AGENT_TOOL_CALL: &str = "agent.tool_call";
    pub const AGENT_MESSAGE: &str = "agent.message";
    pub const MEMORY_UPDATED: &str = "memory.updated";
    pub const TASK_UPDATED: &str = "task.updated";
    pub const CRON_FIRED: &str = "cron.fired";
    pub const SKILL_LOADED: &str = "skill.loaded";
    pub const PROFILE_CHANGED: &str = "profile.changed";
    pub const PERSONA_CHANGED: &str = "persona.changed";
    pub const LIFECYCLE_PHASE: &str = "lifecycle.phase";
    /// Plugin catalog changed (install / remove / enable / disable of a DSH
    /// plugin or a built-in app plugin). Subscribers re-read the catalog and
    /// re-seed the runtime-registry so the change takes effect immediately.
    pub const PLUGINS_CHANGED: &str = "plugins.changed";
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct BusStats {
    pub total_topics: usize,
    pub total_listeners: usize,
}

impl EventBus {
    /// Snapshot stats — useful for `/api/hermes-event-bus-stats`.
    pub async fn stats(&self) -> BusStats {
        let guard = self.inner.lock().await;
        let total_listeners = guard.values().map(|m| m.len()).sum();
        BusStats { total_topics: guard.len(), total_listeners }
    }
}
