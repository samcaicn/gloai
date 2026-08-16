
//
// High-level orchestrator that runs a small team of agents concurrently
// and merges their outputs. The TypeScript implementation spawned a
// tokio-equivalent "child" task per agent; the Rust port uses
// `tokio::spawn` and a barrier to await all results.

use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::{Mutex, RwLock};
use serde::{Deserialize, Serialize};

use super::agent::HermesAgent;
use super::types::*;

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct MultiAgentRunSpec {
    pub name: String,
    pub agents: Vec<String>,
    pub messages: VLMMessage,
    #[serde(default)]
    pub parallel: bool,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct MultiAgentResult {
    pub name: String,
    pub outputs: HashMap<String, VLMResponse>,
    pub errors: HashMap<String, String>,
}

#[derive(Default)]
pub struct MultiAgent {
    pub agents: RwLock<HashMap<String, Arc<HermesAgent>>>,
    pub runs: Mutex<Vec<MultiAgentResult>>,
}

impl MultiAgent {
    pub fn new() -> Self { Self::default() }

    pub async fn register(&self, id: impl Into<String>, agent: Arc<HermesAgent>) {
        self.agents.write().await.insert(id.into(), agent);
    }

    pub async fn run(&self, spec: MultiAgentRunSpec) -> MultiAgentResult {
        let agents = self.agents.read().await.clone();
        let mut outputs: HashMap<String, VLMResponse> = HashMap::new();
        let mut errors: HashMap<String, String> = HashMap::new();
        let mut handles = Vec::new();
        for id in spec.agents.iter() {
            if let Some(agent) = agents.get(id) {
                let agent = agent.clone();
                let msg = spec.messages.clone();
                let id_owned = id.clone();
                if spec.parallel {
                    handles.push((id_owned.clone(), tokio::spawn(async move {
                        let res = agent.call(vec![msg], None).await;
                        (id_owned, res)
                    })));
                } else {
                    let res = agent.call(vec![msg.clone()], None).await;
                    match res {
                        Ok(v) => { outputs.insert(id_owned, v); }
                        Err(e) => { errors.insert(id_owned, e); }
                    }
                }
            } else {
                errors.insert(id.clone(), "agent not registered".into());
            }
        }
        for (agent_id, h) in handles {
            match h.await {
                Ok((id, res)) => match res {
                    Ok(v) => { outputs.insert(id, v); }
                    Err(e) => { errors.insert(id, e); }
                },
                Err(join_err) => {
                    log::error!("[multi_agent] agent '{}' task join error: {}", agent_id, join_err);
                    errors.insert(agent_id, format!("agent panicked: {}", join_err));
                }
            }
        }
        MultiAgentResult { name: spec.name, outputs, errors }
    }

    pub async fn append(&self, r: MultiAgentResult) {
        self.runs.lock().await.push(r);
    }

    pub async fn history(&self) -> Vec<MultiAgentResult> {
        self.runs.lock().await.clone()
    }
}
