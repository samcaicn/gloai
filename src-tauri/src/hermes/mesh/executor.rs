// Copyright (c) 2026 AIMarketing
//
// AINL 执行者角色：接收协调者 Dispatch → 本机执行 TaskNode → 广播回执。
//
// SkillRunner 是 mesh 与 pc_automation 之间的解耦边界：
//   - P0：StubSkillRunner，返回占位结果，验证全链路连通。
//   - P1：PcAutomationRunner，把 TaskNode.skill_id 映射到 pc_automation::skill::Skill，
//         调 AdaptiveExecutor::execute_skill（src/pc_automation/executor/mod.rs）执行。

use std::sync::Arc;

use async_trait::async_trait;
use iroh_gossip::api::GossipSender;
use tokio::sync::Mutex;

use super::ainl::{DAGRequirement, MeshMessage, NodeStatus, TaskNode};
use super::transport::{broadcast_message, MeshTransport};

/// 任务执行边界。mesh 不直接依赖 pc_automation，便于测试与演进。
#[async_trait]
pub trait SkillRunner: Send + Sync {
    async fn run(&self, node: &TaskNode) -> Result<serde_json::Value, String>;
}

/// P0 占位执行器：原样回显，证明 gossip 任务链路端到端可用。
pub struct StubSkillRunner;

#[async_trait]
impl SkillRunner for StubSkillRunner {
    async fn run(&self, node: &TaskNode) -> Result<serde_json::Value, String> {
        Ok(serde_json::json!({
            "executed": true,
            "runner": "stub",
            "skill_id": node.skill_id,
            "title": node.title,
        }))
    }
}

/// 执行者：处理派发到本机的任务节点。
pub struct Executor {
    transport: MeshTransport,
    nonce: Arc<Mutex<u64>>,
    runner: Arc<dyn SkillRunner>,
}

impl Executor {
    pub fn new(
        transport: MeshTransport,
        nonce: Arc<Mutex<u64>>,
        runner: Arc<dyn SkillRunner>,
    ) -> Self {
        Self { transport, nonce, runner }
    }

    /// 处理一条 Dispatch（协调者 → 本机）。广播 Accept → Running → Deliver/Fail。
    pub async fn handle_dispatch(
        &self,
        sender: &GossipSender,
        _requirement: DAGRequirement,
        mut node: TaskNode,
    ) {
        let sk = self.transport.secret_key();
        // Accept
        {
            let mut n = self.nonce.lock().await;
            let msg = MeshMessage::Accept { node_id: node.node_id.clone() };
            let _ = broadcast_message(sender, sk, &mut n, &msg).await;
        }
        // Running
        {
            let mut n = self.nonce.lock().await;
            let msg = MeshMessage::StatusUpdate {
                node_id: node.node_id.clone(),
                status: NodeStatus::Running,
                result: None,
            };
            let _ = broadcast_message(sender, sk, &mut n, &msg).await;
        }
        // 执行
        node.status = NodeStatus::Running;
        match self.runner.run(&node).await {
            Ok(result) => {
                let mut n = self.nonce.lock().await;
                let msg = MeshMessage::Deliver {
                    node_id: node.node_id.clone(),
                    result,
                    blob_hash: None,
                };
                let _ = broadcast_message(sender, sk, &mut n, &msg).await;
            }
            Err(e) => {
                let mut n = self.nonce.lock().await;
                let msg = MeshMessage::Fail { node_id: node.node_id.clone(), error: e };
                let _ = broadcast_message(sender, sk, &mut n, &msg).await;
            }
        }
    }
}
