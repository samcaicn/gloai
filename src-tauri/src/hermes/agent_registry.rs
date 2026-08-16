
//
// A registry of named agents. The TypeScript module also held a
// capability bitmask. The Rust port uses a `RwLock<HashMap<...>>` plus
// a `Vec<String>` of capabilities.

use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use tokio::sync::RwLock;
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct AgentSpec {
    pub id: String,
    pub name: String,
    pub role: String,
    #[serde(default)]
    pub description: Option<String>,
    #[serde(default)]
    pub capabilities: Vec<String>,
    #[serde(default)]
    pub enabled: bool,
}

#[derive(Default)]
pub struct AgentRegistry {
    #[allow(dead_code)]
    inner: RwLock<HashMap<String, AgentSpec>>,
}

impl AgentRegistry {
    pub fn new() -> Self { Self::default() }

    pub async fn register(&self, spec: AgentSpec) {
        self.inner.write().await.insert(spec.id.clone(), spec);
    }

    pub async fn get(&self, id: &str) -> Option<AgentSpec> {
        self.inner.read().await.get(id).cloned()
    }

    pub async fn list(&self) -> Vec<AgentSpec> {
        self.inner.read().await.values().cloned().collect()
    }

    pub async fn list_enabled(&self) -> Vec<AgentSpec> {
        self.inner.read().await.values().filter(|a| a.enabled).cloned().collect()
    }

    pub async fn remove(&self, id: &str) -> bool {
        self.inner.write().await.remove(id).is_some()
    }

    pub async fn set_enabled(&self, id: &str, enabled: bool) -> bool {
        let mut g = self.inner.write().await;
        if let Some(spec) = g.get_mut(id) { spec.enabled = enabled; true } else { false }
    }

    pub async fn has_capability(&self, id: &str, capability: &str) -> bool {
        self.inner.read().await.get(id).map(|s| s.capabilities.iter().any(|c| c == capability)).unwrap_or(false)
    }

    pub async fn shared_capabilities(&self, ids: &[String]) -> HashSet<String> {
        let g = self.inner.read().await;
        let mut iter = ids.iter().filter_map(|id| g.get(id));
        let first = match iter.next() { Some(s) => s.capabilities.iter().cloned().collect::<HashSet<_>>(), None => return HashSet::new() };
        iter.fold(first, |mut acc, s| { acc.retain(|c| s.capabilities.contains(c)); acc })
    }
}

pub type SharedRegistry = Arc<AgentRegistry>;
