//
// Delegation protocol: one agent can hand a task to another agent, with
// a priority hint and a context block. The TypeScript module also
// resolved cyclic dependencies via topological sort. The Rust port
// keeps the same data shapes and a simple depth-first resolver.

use std::collections::{HashMap, HashSet, VecDeque};
use serde::{Deserialize, Serialize};

use super::agent_registry::AgentSpec;

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct DelegationRequest {
    pub from: String,
    pub to: String,
    pub instruction: String,
    #[serde(default)]
    pub context: serde_json::Value,
    #[serde(default)]
    pub priority: u8,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct DelegationResult {
    pub request: DelegationRequest,
    pub accepted: bool,
    pub reason: Option<String>,
}

#[derive(Default)]
pub struct DelegationGraph {
    edges: HashMap<String, HashSet<String>>,
}

impl DelegationGraph {
    pub fn new() -> Self { Self::default() }

    pub fn add_edge(&mut self, from: &str, to: &str) {
        self.edges.entry(from.to_string()).or_default().insert(to.to_string());
    }

    pub fn can_delegate(&self, from: &str, to: &str) -> bool {
        self.edges.get(from).is_some_and(|s| s.contains(to))
    }

    /// Topological sort. Returns `None` on cycle.
    pub fn order(&self, agents: &[AgentSpec]) -> Option<Vec<String>> {
        let mut indeg: HashMap<String, usize> = agents.iter().map(|a| (a.id.clone(), 0)).collect();
        let mut adj: HashMap<String, Vec<String>> = HashMap::new();
        for (from, tos) in &self.edges {
            // 之前只有 `to` 出现在 `agents` 里才增 indeg,导致
            // 完全孤立的 `from`(没有出边 / 入边)压根没被加进 indeg,
            // 拓扑序末尾丢 agent。改为:把 `from` 也(若在 agents 里)
            // 加入 indeg,初始值 0;同时建 adj entry(可能空),保证
            // 后续 `adj.get(&n)` 永远拿到 Some。
            if indeg.contains_key(from) && !adj.contains_key(from) {
                adj.entry(from.clone()).or_default();
            }
            for to in tos {
                if indeg.contains_key(to) {
                    *indeg.get_mut(to).unwrap() += 1;
                    adj.entry(from.clone()).or_default().push(to.clone());
                }
            }
        }
        let mut queue: VecDeque<String> = indeg.iter().filter_map(|(k, v)| if *v == 0 { Some(k.clone()) } else { None }).collect();
        let mut order = Vec::new();
        while let Some(n) = queue.pop_front() {
            order.push(n.clone());
            if let Some(children) = adj.get(&n) {
                for c in children {
                    if let Some(d) = indeg.get_mut(c) {
                        *d -= 1;
                        if *d == 0 { queue.push_back(c.clone()); }
                    }
                }
            }
        }
        if order.len() == indeg.len() { Some(order) } else { None }
    }
}

pub fn evaluate(graph: &DelegationGraph, req: &DelegationRequest) -> DelegationResult {
    DelegationResult {
        request: req.clone(),
        accepted: graph.can_delegate(&req.from, &req.to),
        reason: if graph.can_delegate(&req.from, &req.to) { None } else { Some("edge not declared".into()) },
    }
}
