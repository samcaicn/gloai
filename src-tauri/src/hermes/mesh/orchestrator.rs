// Copyright (c) 2026 tupAI
//
// AINL 协调者角色（P2P 下的去中心化编排）。
//
// AINL 原是服务器中心化编排（tupsaasmcp 的 AINLEngine）。安全设计的 P2P 下，mesh 创建者 =
// 初始协调者，运行本 Orchestrator。其它对端 = 执行者，握手时上报 ClientInfo。
// 协调者收到 submit_requirement → (P0 模板拆分 / P1 LlmService 驱动 clarify+DAG split)
// → ResourceMatcher 按 skill/load/trust 匹配 → Dispatch 给目标对端（或本地执行）。
//
// P0：模板拆分（单节点），非 LLM。P1：接 hermes::llm_service 走 LLM 拆分 + replanner。

use std::collections::HashMap;
use std::sync::Arc;

use iroh::EndpointId;
use iroh_gossip::api::GossipSender;
use tokio::sync::Mutex;

use super::ainl::{
    Assignment, ClientInfo, DAGRequirement, MeshMessage, NodeStatus, RequirementStatus, ReplanEvent,
    ReplanTrigger, TaskNode,
};
use super::executor::SkillRunner;
use super::transport::{broadcast_message, now_ms, MeshTransport};

/// 生成短唯一 id。
fn new_id(prefix: &str) -> String {
    format!("{}-{}", prefix, &uuid::Uuid::new_v4().to_string()[..8])
}

/// 单个节点的跟踪状态。
#[derive(Debug, Clone)]
struct NodeState {
    node: TaskNode,
    assigned_endpoint: String,
    retry_count: u32,
}

/// 一个 requirement 的跟踪状态。
#[derive(Debug, Clone)]
struct RequirementState {
    requirement: DAGRequirement,
    nodes: HashMap<String, NodeState>,
}

pub struct Orchestrator {
    transport: MeshTransport,
    self_client: ClientInfo,
    peers: Arc<Mutex<HashMap<EndpointId, ClientInfo>>>,
    requirements: Arc<Mutex<HashMap<String, RequirementState>>>,
    executor: Arc<dyn SkillRunner>,
    nonce: Arc<Mutex<u64>>,
}

impl Orchestrator {
    pub fn new(
        transport: MeshTransport,
        self_client: ClientInfo,
        peers: Arc<Mutex<HashMap<EndpointId, ClientInfo>>>,
        executor: Arc<dyn SkillRunner>,
        nonce: Arc<Mutex<u64>>,
    ) -> Self {
        Self {
            transport,
            self_client,
            peers,
            requirements: Arc::new(Mutex::new(HashMap::new())),
            executor,
            nonce,
        }
    }

    /// 提交需求：模板拆分 → 匹配 → 派发。返回 requirement_id。
    pub async fn submit_requirement(
        &self,
        sender: &GossipSender,
        text: &str,
    ) -> Result<String, String> {
        let now = now_ms();
        let req_id = new_id("req");
        // P0 模板拆分：单执行节点。skill_id 固定为 generic（P1 由 LLM 推断）。
        let mut node = TaskNode {
            node_id: new_id("node"),
            parent_id: String::new(),
            phase: 0,
            title: text.to_string(),
            description: text.to_string(),
            skill_id: "pc_automation.generic".to_string(),
            depends_on: vec![],
            priority: "high".to_string(),
            payload: serde_json::json!({ "text": text }),
            status: NodeStatus::Pending,
            assigned_to: String::new(),
            result: serde_json::Value::Null,
            created_at: now,
            updated_at: now,
        };
        let assignment = self.match_peer(&node).await;
        node.assigned_to = assignment.client_id.clone();
        node.status = NodeStatus::Dispatched;

        let requirement = DAGRequirement {
            requirement_id: req_id.clone(),
            tenant_id: self.self_client.tenant_id.clone(),
            text: text.to_string(),
            category: "automation".to_string(),
            clarified_context: serde_json::Value::Null,
            status: RequirementStatus::Dispatched,
            created_at: now,
            updated_at: now,
            nodes: vec![node.clone()],
        };

        // 跟踪 requirement 状态
        {
            let mut reqs = self.requirements.lock().await;
            let mut nodes_map = HashMap::new();
            nodes_map.insert(
                node.node_id.clone(),
                NodeState {
                    node: node.clone(),
                    assigned_endpoint: assignment.client_id.clone(),
                    retry_count: 0,
                },
            );
            reqs.insert(
                req_id.clone(),
                RequirementState {
                    requirement: requirement.clone(),
                    nodes: nodes_map,
                },
            );
        }

        let self_id = self.transport.endpoint_id().to_string();
        if node.assigned_to == self_id {
            // 分给自己：本地异步执行，完成后广播 Deliver/Fail。
            let executor = self.executor.clone();
            let sk = self.transport.secret_key().clone();
            let sender_clone = sender.clone();
            let nonce = self.nonce.clone();
            tokio::spawn(async move {
                run_local(executor, sender_clone, sk, nonce, node).await;
            });
        } else {
            // 分给对端：gossip 广播 Dispatch。
            let msg = MeshMessage::Dispatch { requirement, node };
            let mut n = self.nonce.lock().await;
            broadcast_message(sender, self.transport.secret_key(), &mut n, &msg)
                .await
                .map_err(|e| e.to_string())?;
        }
        Ok(req_id)
    }

    /// 简易 ResourceMatcher：找具备该 skill 且负载最低的对端；无则指派给本机。
    async fn match_peer(&self, node: &TaskNode) -> Assignment {
        let peers = self.peers.lock().await;
        let mut best: Option<(EndpointId, ClientInfo)> = None;
        for (id, info) in peers.iter() {
            if info.available_skills.iter().any(|s| s == &node.skill_id) {
                match &best {
                    None => best = Some((*id, info.clone())),
                    Some((_, b)) if info.current_load < b.current_load => {
                        best = Some((*id, info.clone()));
                    }
                    _ => {}
                }
            }
        }
        match best {
            Some((id, info)) => Assignment {
                node_id: node.node_id.clone(),
                client_id: id.to_string(),
                score: 1.0 - (info.current_load as f32 / 100.0),
                reason: format!("matched skill {} on load {}", node.skill_id, info.current_load),
            },
            None => Assignment {
                node_id: node.node_id.clone(),
                client_id: self.transport.endpoint_id().to_string(),
                score: 0.5,
                reason: "no remote peer with skill; execute locally".to_string(),
            },
        }
    }

    /// 处理执行者回执（Accept/StatusUpdate/Deliver/Fail/Replan/Interrupt）。
    pub async fn handle_peer_event(
        &self,
        sender: &GossipSender,
        from: EndpointId,
        msg: MeshMessage,
    ) {
        match msg {
            MeshMessage::Accept { node_id } => {
                let mut reqs = self.requirements.lock().await;
                for req in reqs.values_mut() {
                    if let Some(ns) = req.nodes.get_mut(&node_id) {
                        ns.node.status = NodeStatus::Running;
                        ns.node.updated_at = now_ms();
                        log::info!(
                            "[mesh:orchestrator] node {} accepted by {}",
                            node_id, from
                        );
                        return;
                    }
                }
            }
            MeshMessage::StatusUpdate { node_id, status, result } => {
                let mut reqs = self.requirements.lock().await;
                for req in reqs.values_mut() {
                    if let Some(ns) = req.nodes.get_mut(&node_id) {
                        ns.node.status = status;
                        ns.node.updated_at = now_ms();
                        if let Some(r) = &result { ns.node.result = r.clone(); }
                        return;
                    }
                }
            }
            MeshMessage::Deliver { node_id, result, .. } => {
                let mut reqs = self.requirements.lock().await;
                for req in reqs.values_mut() {
                    if let Some(ns) = req.nodes.get_mut(&node_id) {
                        ns.node.status = NodeStatus::Completed;
                        ns.node.result = result.clone();
                        ns.node.updated_at = now_ms();
                        log::info!(
                            "[mesh:orchestrator] node {} delivered by {}",
                            node_id, from
                        );
                        let all_done = req.nodes.values().all(|ns| ns.node.status == NodeStatus::Completed);
                        if all_done {
                            req.requirement.status = RequirementStatus::Completed;
                        }
                        return;
                    }
                }
            }
            MeshMessage::Fail { node_id, error } => {
                log::warn!("[mesh:orchestrator] node {} failed by {}: {}", node_id, from, error);
                drop(msg);
                self.handle_node_failure(sender, &node_id, error).await;
            }
            MeshMessage::Replan { event } => {
                log::info!("[mesh:orchestrator] replan event: trigger={:?} req={}", event.trigger, event.requirement_id);
                self.trigger_replan(sender, &event).await;
            }
            MeshMessage::Interrupt { requirement_id, reason } => {
                let mut reqs = self.requirements.lock().await;
                if let Some(req) = reqs.get_mut(&requirement_id) {
                    req.requirement.status = RequirementStatus::Failed;
                    for ns in req.nodes.values_mut() {
                        if ns.node.status != NodeStatus::Completed {
                            ns.node.status = NodeStatus::Skipped;
                        }
                    }
                    log::warn!("[mesh:orchestrator] requirement {} interrupted: {}", requirement_id, reason);
                }
            }
            _ => {}
        }
    }

    /// 处理节点失败：尝试重规划（重新分配给其他对端或本地执行）。
    async fn handle_node_failure(&self, sender: &GossipSender, node_id: &str, error: String) {
        let (node, req_id, retry_count) = {
            let mut reqs = self.requirements.lock().await;
            let mut found = None;
            for req in reqs.values_mut() {
                if let Some(ns) = req.nodes.get_mut(node_id) {
                    ns.node.status = NodeStatus::Failed;
                    ns.node.updated_at = now_ms();
                    ns.retry_count += 1;
                    found = Some((ns.node.clone(), req.requirement.requirement_id.clone(), ns.retry_count));
                    break;
                }
            }
            drop(reqs);
            match found {
                Some(v) => v,
                None => { log::warn!("[mesh:orchestrator] node {} not found", node_id); return; }
            }
        };

        const MAX_RETRIES: u32 = 3;
        if retry_count >= MAX_RETRIES {
            let mut reqs = self.requirements.lock().await;
            if let Some(req) = reqs.get_mut(&req_id) {
                req.requirement.status = RequirementStatus::Failed;
                log::error!("[mesh:orchestrator] requirement {} failed after {} retries", req_id, retry_count);
            }
            return;
        }

        log::info!("[mesh:orchestrator] replanning node {} (retry {}/{})", node_id, retry_count, MAX_RETRIES);
        let mut new_node = node.clone();
        new_node.status = NodeStatus::Pending;
        let assignment = self.match_peer(&new_node).await;
        new_node.assigned_to = assignment.client_id.clone();
        new_node.status = NodeStatus::Dispatched;
        new_node.updated_at = now_ms();

        {
            let mut reqs = self.requirements.lock().await;
            if let Some(req) = reqs.get_mut(&req_id) {
                if let Some(ns) = req.nodes.get_mut(node_id) {
                    ns.node = new_node.clone();
                    ns.assigned_endpoint = assignment.client_id.clone();
                }
            }
        }

        let self_id = self.transport.endpoint_id().to_string();
        if new_node.assigned_to == self_id {
            let executor = self.executor.clone();
            let sk = self.transport.secret_key().clone();
            let sender_clone = sender.clone();
            let nonce = self.nonce.clone();
            tokio::spawn(async move { run_local(executor, sender_clone, sk, nonce, new_node).await; });
        } else {
            let now = now_ms();
            let replan_event = ReplanEvent {
                trigger: ReplanTrigger::TaskFailed,
                requirement_id: req_id.clone(),
                node_id: node_id.to_string(),
                client_id: assignment.client_id.clone(),
                payload: serde_json::json!({"error": error, "retry": retry_count}),
                created_at: now,
            };
            { let mut n = self.nonce.lock().await; let _ = broadcast_message(sender, self.transport.secret_key(), &mut n, &MeshMessage::Replan { event: replan_event }).await; }
            let reqs = self.requirements.lock().await;
            let requirement = if let Some(req) = reqs.get(&req_id) { req.requirement.clone() } else { return; };
            drop(reqs);
            let mut n = self.nonce.lock().await;
            let _ = broadcast_message(sender, self.transport.secret_key(), &mut n, &MeshMessage::Dispatch { requirement, node: new_node }).await;
        }
    }

    async fn trigger_replan(&self, sender: &GossipSender, event: &ReplanEvent) {
        self.handle_node_failure(sender, &event.node_id, format!("replan: {:?}", event.trigger)).await;
    }
}

/// 本地执行：跑 SkillRunner → 广播 Running → Deliver/Fail。
async fn run_local(
    executor: Arc<dyn SkillRunner>,
    sender: GossipSender,
    sk: iroh::SecretKey,
    nonce: Arc<Mutex<u64>>,
    node: TaskNode,
) {
    {
        let mut n = nonce.lock().await;
        let msg = MeshMessage::StatusUpdate {
            node_id: node.node_id.clone(),
            status: NodeStatus::Running,
            result: None,
        };
        let _ = broadcast_message(&sender, &sk, &mut n, &msg).await;
    }
    match executor.run(&node).await {
        Ok(result) => {
            let mut n = nonce.lock().await;
            let msg = MeshMessage::Deliver {
                node_id: node.node_id.clone(),
                result,
                blob_hash: None,
            };
            let _ = broadcast_message(&sender, &sk, &mut n, &msg).await;
        }
        Err(e) => {
            let mut n = nonce.lock().await;
            let msg = MeshMessage::Fail { node_id: node.node_id.clone(), error: e };
            let _ = broadcast_message(&sender, &sk, &mut n, &msg).await;
        }
    }
}
