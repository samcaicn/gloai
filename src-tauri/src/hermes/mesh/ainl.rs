// Copyright (c) 2026 AIMarketing
//
// AINL (AI 协同生产) 任务流数据契约 — mesh 子模块。
//
// 移植自 tupsaasmcp/server/core/ainl_engine/types.py，字段与 Python 版一一对应，
// 序列化采用 camelCase 与项目其它 hermes 契约（evolution_signal.rs）保持一致。
//
// AINL 是为「多客户端任务 DAG 拆分 + 匹配 + 协调交付」设计的协议；本 crate 的
// Hermes TaskEnvelope/DelegateRequest 是进程内多智能体协调，不复用。
//
// 纯数据类型，不依赖传输层协议；消息签名/验签见 auth.rs。

use serde::{Deserialize, Serialize};

/// 单个任务节点的执行状态。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum NodeStatus {
    Pending,
    Ready,
    Dispatched,
    Running,
    Completed,
    Failed,
    Blocked,
    Skipped,
}

/// 需求（DAGRequirement）的整体状态机。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RequirementStatus {
    Draft,
    PendingClarification,
    Clarified,
    Decomposing,
    Decomposed,
    PendingSkillConfirmation,
    Dispatched,
    Completed,
    Failed,
}

/// 触发重规划的事件类型。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ReplanTrigger {
    TaskFailed,
    TaskTimeout,
    ConfidenceDrop,
    TrustDrop,
    AdminCommand,
    SkillDemotion,
}

/// AINL 任务图中的一个节点。`assigned_to` = 目标设备的 EndpointId（hex 字符串）。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TaskNode {
    pub node_id: String,
    pub parent_id: String,
    pub phase: i32,
    pub title: String,
    pub description: String,
    pub skill_id: String,
    pub depends_on: Vec<String>,
    pub priority: String,
    pub payload: serde_json::Value,
    pub status: NodeStatus,
    /// 目标执行者的 EndpointId（PublicKey hex）。空串表示尚未分配。
    pub assigned_to: String,
    pub result: serde_json::Value,
    pub created_at: f64,
    pub updated_at: f64,
}

/// 一个被拆分成 DAG 的需求。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DAGRequirement {
    pub requirement_id: String,
    pub tenant_id: String,
    pub text: String,
    pub category: String,
    pub clarified_context: serde_json::Value,
    pub status: RequirementStatus,
    pub created_at: f64,
    pub updated_at: f64,
    pub nodes: Vec<TaskNode>,
}

/// 握手时交换的对端能力信息（协调者据此做 ResourceMatcher 分配）。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClientInfo {
    pub client_id: String,
    pub tenant_id: String,
    pub device_fingerprint: String,
    pub current_load: i32,
    pub available_skills: Vec<String>,
    pub priority: String,
    pub first_seen_ts: f64,
    pub last_active_ts: f64,
}

/// 协调者分配任务节点到客户端的决策记录。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Assignment {
    pub node_id: String,
    pub client_id: String,
    pub score: f32,
    pub reason: String,
}

/// 重规划事件（驱动 Replanner 重新拆分/再分配）。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReplanEvent {
    pub trigger: ReplanTrigger,
    pub requirement_id: String,
    pub node_id: String,
    pub client_id: String,
    pub payload: serde_json::Value,
    pub created_at: f64,
}

/// mesh 消息信封。gossip 广播与直连流统一使用，`#[serde(tag="kind")]` 与
/// `EvolutionSignal` 风格一致。实际链路上由 auth.rs 的 `SignedEnvelope` 包裹签名
/// （信封外壳 postcard，消息体 JSON 字符串——见 auth.rs `SignedEnvelope` 注释）。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum MeshMessage {
    /// 握手 + 身份证明（携带自身能力）。sig 字段为对 `client` 的附加证明，可空。
    Hello {
        client: ClientInfo,
        sig: String,
    },
    /// 协调者 → 执行者：派发一个任务节点（附带所属需求上下文）。
    Dispatch {
        requirement: DAGRequirement,
        node: TaskNode,
    },
    /// 执行者 → 协调者：接受任务。
    Accept {
        node_id: String,
    },
    /// 执行者 → 协调者：状态更新。
    StatusUpdate {
        node_id: String,
        status: NodeStatus,
        result: Option<serde_json::Value>,
    },
    /// 执行者 → 协调者：交付结果（大结果走 blob，hash 附在 blob_hash）。
    Deliver {
        node_id: String,
        result: serde_json::Value,
        blob_hash: Option<String>,
    },
    /// 重规划事件。
    Replan {
        event: ReplanEvent,
    },
    /// 任务失败通知。
    Fail {
        node_id: String,
        error: String,
    },
    /// 中断整个需求。
    Interrupt {
        requirement_id: String,
        reason: String,
    },
    /// 周期心跳，刷新对端负载视图。
    Heartbeat {
        client_id: String,
        load: i32,
        ts: f64,
    },
    /// 文件/文档分发通告（内容走 blobs，按 hash 拉取）。
    FileOffer {
        blob_hash: String,
        size: u64,
        name: String,
        mime: String,
        meta: serde_json::Value,
    },
    /// 浏览器自动化快照移交通告（快照内容走 blobs）。
    BrowserSnapshotOffer {
        session_id: String,
        blob_hash: String,
        url: String,
    },
    /// 技能升级同步 (Phase 3)。任一对端确认升级一个技能后广播给 mesh 全网;
    /// 对端接收后复用 `UpgradeWriter` 落盘到本地 (与本地确认同路径),
    /// 并 emit `mesh://skill-received` 事件供前端 toast 提示。
    #[serde(rename_all = "camelCase")]
    SkillSync {
        skill_id: String,
        /// "mcp" / "automation" / "builtin" —— 与 `SkillKind::as_str` 一致,
        /// 未知值在接收侧降级到 mcp (最安全, 落盘到文件即可见)。
        skill_kind: String,
        /// 新版本 SKILL.md 正文。
        content: String,
        /// 来源 client_id (设备指纹 hex), 供前端展示来源。
        source_client_id: String,
        /// 升级时间戳 (unix ms)。
        ts: f64,
        /// 语义版本号 (如 "3.0.0")。接收端用它做去重: 当多个节点同时广播
        /// 同一 skill_id 时,高版本覆盖低版本;版本相同则 ts 更新者胜。
        /// 空串或缺失表示未知版本,退化为先到者胜。
        #[serde(default)]
        version: String,
    },
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 与 evolution_signal.rs 的 serde_tag_kind_round_trip 对齐：确保 tag 信封
    /// 往返一致，且 kind 标签为 camelCase。
    #[test]
    fn serde_tag_kind_round_trip() {
        let now = 1_720_000_000_000.0;
        let node = TaskNode {
            node_id: "n1".into(),
            parent_id: "".into(),
            phase: 0,
            title: "打开记事本".into(),
            description: "在目标机器打开记事本并写入日期".into(),
            skill_id: "pc_automation.open_notepad".into(),
            depends_on: vec![],
            priority: "high".into(),
            payload: serde_json::json!({"target": "notepad.exe"}),
            status: NodeStatus::Pending,
            assigned_to: "".into(),
            result: serde_json::Value::Null,
            created_at: now,
            updated_at: now,
        };
        let req = DAGRequirement {
            requirement_id: "r1".into(),
            tenant_id: "t1".into(),
            text: "打开记事本并写入日期".into(),
            category: "automation".into(),
            clarified_context: serde_json::Value::Null,
            status: RequirementStatus::Dispatched,
            created_at: now,
            updated_at: now,
            nodes: vec![node.clone()],
        };
        let msg = MeshMessage::Dispatch {
            requirement: req,
            node: node.clone(),
        };
        let json = serde_json::to_string(&msg).expect("serialize");
        assert!(
            json.contains(r#""kind":"dispatch""#),
            "expected camelCase tag 'dispatch', got: {json}"
        );
        let back: MeshMessage = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(msg, back);

        // snake_case 枚举值校验
        let su = MeshMessage::StatusUpdate {
            node_id: "n1".into(),
            status: NodeStatus::Running,
            result: None,
        };
        let su_json = serde_json::to_string(&su).unwrap();
        assert!(su_json.contains(r#""status":"running""#), "got: {su_json}");
    }

    #[test]
    fn skill_sync_serde_round_trip() {
        let msg = MeshMessage::SkillSync {
            skill_id: "open-notepad".into(),
            skill_kind: "automation".into(),
            content: "---\nname: open-notepad\n---\nbody".into(),
            source_client_id: "fp-ab12".into(),
            ts: 1_720_000_000_000.0,
            version: "2.1.0".into(),
        };
        let json = serde_json::to_string(&msg).expect("serialize");
        assert!(
            json.contains(r#""kind":"skillSync""#),
            "expected camelCase tag 'skillSync', got: {json}"
        );
        assert!(
            json.contains(r#""version":"2.1.0""#),
            "expected version field, got: {json}"
        );
        let back: MeshMessage = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(msg, back);
    }

    #[test]
    fn skill_sync_default_version_is_empty() {
        // version field is #[serde(default)], so missing version deserializes to ""
        let json = r#"{"kind":"skillSync","skillId":"x","skillKind":"mcp","content":"c","sourceClientId":"cl","ts":0.0}"#;
        let msg: MeshMessage = serde_json::from_str(json).expect("deserialize without version");
        match msg {
            MeshMessage::SkillSync { version, .. } => assert_eq!(version, ""),
            _ => panic!("expected SkillSync"),
        }
    }

    #[test]
    fn client_info_round_trip() {
        let c = ClientInfo {
            client_id: "c1".into(),
            tenant_id: "t1".into(),
            device_fingerprint: "fp".into(),
            current_load: 0,
            available_skills: vec!["a".into(), "b".into()],
            priority: "normal".into(),
            first_seen_ts: 0.0,
            last_active_ts: 0.0,
        };
        let json = serde_json::to_string(&c).unwrap();
        let back: ClientInfo = serde_json::from_str(&json).unwrap();
        assert_eq!(c, back);
        assert!(json.contains("availableSkills"), "camelCase: {json}");
    }
}
