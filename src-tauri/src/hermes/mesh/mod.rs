// Copyright (c) 2026 AIMarketing
//
// hermes::mesh —— 基于安全设计的 P2P 组网子模块。
//
// 数据面三通道：
//   1. gossip 广播：AINL 状态消息（Hello/Dispatch/Status/Deliver/Heartbeat/...）
//   2. 直连 bi-stream（open_bi）：P1 点对点 RPC
//   3. blobs：P1 文档/文件内容寻址传送
//
// 身份：EndpointId（Ed25519 公钥）= mesh 设备身份；每条消息经 auth::SignedEnvelope 签名。
// 组网：join_code → SHA-256 → gossip TopicId；同 join_code 设备订阅同 topic 即成网。
// 编排：mesh 创建者 = 协调者（Orchestrator），其它 = 执行者（Executor）。
//
// 详见 .trae/documents/iroh-mesh-ainl-architecture.md。

pub mod ainl;

#[cfg(feature = "mesh")]
pub mod auth;
#[cfg(feature = "mesh")]
pub mod commands;
#[cfg(feature = "mesh")]
pub mod executor;
#[cfg(feature = "mesh")]
pub mod files;
#[cfg(feature = "mesh")]
pub mod orchestrator;
#[cfg(feature = "mesh")]
pub mod ticket;
#[cfg(feature = "mesh")]
pub mod transport;

#[cfg(feature = "mesh")]
use std::collections::HashMap;
#[cfg(feature = "mesh")]
use std::sync::Arc;

#[cfg(feature = "mesh")]
use iroh::EndpointId;
#[cfg(feature = "mesh")]
use iroh_gossip::api::{Event, GossipReceiver, GossipSender};
#[cfg(feature = "mesh")]
use n0_future::StreamExt;
#[cfg(feature = "mesh")]
use tauri::{AppHandle, Emitter};
#[cfg(feature = "mesh")]
use tokio::sync::{Mutex, RwLock};

#[cfg(feature = "mesh")]
use ainl::{ClientInfo, MeshMessage};
#[cfg(feature = "mesh")]
use auth::{verify_and_decode, ReplayGuard};
#[cfg(feature = "mesh")]
use executor::{Executor, SkillRunner, StubSkillRunner};
#[cfg(feature = "mesh")]
use orchestrator::Orchestrator;
#[cfg(feature = "mesh")]
use ticket::MeshTicket;
#[cfg(feature = "mesh")]
use transport::{broadcast_message, now_ms, MeshTransport};

/// 默认防重放窗口：±60s。
#[cfg(feature = "mesh")]
const REPLAY_WINDOW_MS: f64 = 60_000.0;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MeshRole {
    Coordinator,
    Executor,
}

#[derive(Debug, Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MeshStatus {
    pub role: String,
    pub endpoint_id: String,
    pub addr: String,
    pub join_code: String,
    pub peers: usize,
}

/// 活跃 mesh 实例。
#[cfg(feature = "mesh")]
pub struct MeshNode {
    transport: MeshTransport,
    sender: GossipSender,
    #[allow(dead_code)]
    executor: Arc<Executor>,
    orchestrator: Arc<Orchestrator>,
    peers: Arc<Mutex<HashMap<EndpointId, ClientInfo>>>,
    #[allow(dead_code)]
    nonce: Arc<Mutex<u64>>,
    role: MeshRole,
    join_code: String,
    #[allow(dead_code)]
    receiver_guard: tokio::task::JoinHandle<()>,
    /// Tauri 句柄, 用于 SkillSync 接收时 emit 事件给前端。
    app: AppHandle,
    /// 本机 ClientInfo (含 device_fingerprint / client_id), 广播 SkillSync 时
    /// 用其 client_id 标注 source_client_id。
    self_client: ClientInfo,
}

#[cfg(feature = "mesh")]
impl MeshNode {
    /// 作为协调者创建 mesh（生成 ticket 供他人加入）。
    pub async fn create(
        secret_key: iroh::SecretKey,
        join_code: String,
        self_client: ClientInfo,
        runner: Option<Arc<dyn SkillRunner>>,
        app: AppHandle,
    ) -> Result<(Arc<Self>, MeshTicket), MeshError> {
        let transport = MeshTransport::start(secret_key).await?;
        let topic = ticket::derive_topic_id(&join_code);
        let (sender, receiver) = transport.open_as_coordinator(topic).await?;
        let ticket = MeshTicket::new_for_coordinator(&join_code, transport.addr());
        let node = Self::build(
            transport,
            sender,
            receiver,
            self_client,
            runner,
            MeshRole::Coordinator,
            join_code,
            app,
        )
        .await;
        Ok((Arc::new(node), ticket))
    }

    /// 作为执行者加入已有 mesh。
    pub async fn join(
        secret_key: iroh::SecretKey,
        ticket: MeshTicket,
        self_client: ClientInfo,
        runner: Option<Arc<dyn SkillRunner>>,
        app: AppHandle,
    ) -> Result<Arc<Self>, MeshError> {
        let join_code = ticket.join_code.clone();
        let transport = MeshTransport::start(secret_key).await?;
        let (sender, receiver) = transport.join_mesh(&ticket).await?;
        let node = Self::build(
            transport,
            sender,
            receiver,
            self_client,
            runner,
            MeshRole::Executor,
            join_code,
            app,
        )
        .await;
        Ok(Arc::new(node))
    }

    #[allow(clippy::too_many_arguments)]
    async fn build(
        transport: MeshTransport,
        sender: GossipSender,
        receiver: GossipReceiver,
        self_client: ClientInfo,
        runner: Option<Arc<dyn SkillRunner>>,
        role: MeshRole,
        join_code: String,
        app: AppHandle,
    ) -> Self {
        let peers: Arc<Mutex<HashMap<EndpointId, ClientInfo>>> =
            Arc::new(Mutex::new(HashMap::new()));
        let nonce: Arc<Mutex<u64>> = Arc::new(Mutex::new(0));
        let runner: Arc<dyn SkillRunner> = runner.unwrap_or_else(|| Arc::new(StubSkillRunner));
        let replay: Arc<Mutex<ReplayGuard>> =
            Arc::new(Mutex::new(ReplayGuard::new(REPLAY_WINDOW_MS)));

        let executor = Arc::new(Executor::new(
            transport.clone(),
            nonce.clone(),
            runner.clone(),
        ));
        let orchestrator = Arc::new(Orchestrator::new(
            transport.clone(),
            self_client.clone(),
            peers.clone(),
            runner.clone(),
            nonce.clone(),
        ));

        let self_id = transport.endpoint_id();
        // 广播 Hello，让对端感知本机能力。
        {
            let mut n = nonce.lock().await;
            let hello = MeshMessage::Hello {
                client: self_client.clone(),
                sig: String::new(),
            };
            if let Err(e) =
                broadcast_message(&sender, transport.secret_key(), &mut n, &hello).await
            {
                log::warn!("[mesh] broadcast failed: {}", e);
            }
        }

        // 接收循环：验签 → 防重放 → 路由。transport.clone() 用于回送消息时签名。
        let guard = tokio::spawn(receiver_loop(
            receiver,
            sender.clone(),
            transport.clone(),
            executor.clone(),
            peers.clone(),
            nonce.clone(),
            replay.clone(),
            self_id,
            self_client.clone(),
            app.clone(),
        ));

        Self {
            transport,
            sender,
            executor,
            orchestrator,
            peers,
            nonce,
            role,
            join_code,
            receiver_guard: guard,
            app,
            self_client,
        }
    }

    /// 提交需求（仅协调者有意义；执行者调用返回错误）。
    pub async fn submit_requirement(&self, text: &str) -> Result<String, MeshError> {
        if self.role != MeshRole::Coordinator {
            return Err(MeshError::NotCoordinator);
        }
        self.orchestrator
            .submit_requirement(&self.sender, text)
            .await
            .map_err(MeshError::Other)
    }

    pub async fn status(&self) -> MeshStatus {
        let peers = self.peers.lock().await.len();
        MeshStatus {
            // 显式映射为稳定的小写蛇形串，避免依赖 Debug 派生格式（重命名/新增变体
            // 会破坏前端 MeshRole = 'coordinator' | 'executor' 契约）。
            role: match self.role {
                MeshRole::Coordinator => "coordinator",
                MeshRole::Executor => "executor",
            }
            .to_string(),
            endpoint_id: self.transport.endpoint_id().to_string(),
            // EndpointAddr 未实现 Display，用 Debug 格式化（含 EndpointId + 传输地址集）。
            addr: format!("{:?}", self.transport.addr()),
            join_code: self.join_code.clone(),
            peers,
        }
    }

    /// 当前已知对端的 ClientInfo 快照。
    pub async fn list_peers(&self) -> Vec<ClientInfo> {
        self.peers.lock().await.values().cloned().collect()
    }

    /// 广播一条 SkillSync 消息，把本地确认的技能升级同步给 mesh 全网对端。
    /// best-effort：mesh 未激活时调用方应先 short-circuit（这里不再判空）。
    /// 失败只 log warn，不阻断升级落盘流程（落盘是本地决定，同步是附加收益）。
    pub async fn broadcast_skill_sync(
        &self,
        skill_id: &str,
        skill_kind: &str,
        content: &str,
        version: &str,
    ) -> Result<(), MeshError> {
        let mut n = self.nonce.lock().await;
        let msg = MeshMessage::SkillSync {
            skill_id: skill_id.to_string(),
            skill_kind: skill_kind.to_string(),
            content: content.to_string(),
            source_client_id: self.self_client.client_id.clone(),
            ts: now_ms(),
            version: version.to_string(),
        };
        broadcast_message(&self.sender, self.transport.secret_key(), &mut n, &msg)
            .await
            .map_err(MeshError::Transport)
    }
}

/// 接收循环：解包 gossip 事件 → 验签 → 防重放 → 路由。
#[cfg(feature = "mesh")]
#[allow(clippy::too_many_arguments)]
async fn receiver_loop(
    mut receiver: GossipReceiver,
    sender: GossipSender,
    transport: MeshTransport,
    executor: Arc<Executor>,
    peers: Arc<Mutex<HashMap<EndpointId, ClientInfo>>>,
    nonce: Arc<Mutex<u64>>,
    replay: Arc<Mutex<ReplayGuard>>,
    self_id: EndpointId,
    self_client: ClientInfo,
    app: AppHandle,
) {
    loop {
        match receiver.try_next().await {
            Ok(Some(event)) => {
                if let Event::Received(msg) = event {
                    let (from, frame) = match verify_and_decode(msg.content.as_ref()) {
                        Ok(v) => v,
                        Err(e) => {
                            log::warn!("[mesh] verify_and_decode failed: {}", e);
                            continue;
                        }
                    };
                    {
                        let mut rg = replay.lock().await;
                        if let Err(e) =
                            rg.check_and_record(from, frame.ts, frame.nonce, now_ms())
                        {
                            log::warn!("[mesh] replay guard rejected: {}", e);
                            continue;
                        }
                    }
                    route_message(
                        from,
                        frame.msg,
                        &sender,
                        &transport,
                        &executor,
                        &peers,
                        &nonce,
                        self_id,
                        &self_client,
                        &app,
                    )
                    .await;
                }
            }
            Ok(None) => break, // 流正常结束
            Err(e) => {
                // 瞬时 gossip 错误不应终止接收循环：仅记录并继续，
                // 避免单次抖动让本节点对所有入站消息永久失聪。
                log::warn!("[mesh] gossip receiver error: {}", e);
                continue;
            }
        }
    }
}

#[cfg(feature = "mesh")]
#[allow(clippy::too_many_arguments)]
async fn route_message(
    from: EndpointId,
    msg: MeshMessage,
    sender: &GossipSender,
    transport: &MeshTransport,
    executor: &Arc<Executor>,
    peers: &Arc<Mutex<HashMap<EndpointId, ClientInfo>>>,
    nonce: &Arc<Mutex<u64>>,
    self_id: EndpointId,
    self_client: &ClientInfo,
    app: &AppHandle,
) {
    match msg {
        MeshMessage::Hello { client, .. } => {
            let was_new = {
                let mut p = peers.lock().await;
                let new = !p.contains_key(&from);
                p.insert(from, client);
                new
            };
            // 收到新对端时回送自身 Hello（双向感知）。
            if was_new {
                let mut n = nonce.lock().await;
                let hello = MeshMessage::Hello {
                    client: self_client.clone(),
                    sig: String::new(),
                };
                if let Err(e) =
                    broadcast_message(sender, transport.secret_key(), &mut n, &hello).await
                {
                    log::warn!("[mesh] broadcast failed: {}", e);
                }
            }
        }
        MeshMessage::Heartbeat { load, .. } => {
            let mut p = peers.lock().await;
            if let Some(info) = p.get_mut(&from) {
                info.current_load = load;
                info.last_active_ts = now_ms();
            }
        }
        MeshMessage::Dispatch { requirement, node } => {
            if node.assigned_to == self_id.to_string() {
                executor.handle_dispatch(sender, requirement, node).await;
            }
        }
        MeshMessage::SkillSync {
            skill_id,
            skill_kind,
            content,
            source_client_id,
            ts,
            version,
        } => {
            handle_skill_sync(app, &skill_id, &skill_kind, &content, &source_client_id, ts, &version, from)
                .await;
        }
        // P1：Accept/StatusUpdate/Deliver/Fail/Interrupt/Replan → orchestrator.handle_peer_event
        // P1：FileOffer/BrowserSnapshotOffer → files/snapshot 处理
        _ => {}
    }
}

/// 收到对端广播的 SkillSync：复用 `UpgradeWriter` 把升级内容落盘到本地
/// (与本地确认升级走完全相同的路径——mcp/automation → skills_optimized,
/// builtin → skills_overrides), 然后 emit `mesh://skill-received` 事件
/// 供前端 toast 提示用户"收到对端技能升级"。
///
/// 落盘失败不阻断 emit (即使文件没写成, 用户也应知道有人尝试同步);
/// emit 失败只 log (mesh 场景下事件送达不是硬性承诺)。
/// In-memory version tracking for received SkillSync messages.
/// Key: skill_id, Value: (version, ts). Used to decide whether an
/// incoming sync should overwrite the local copy.
#[cfg(feature = "mesh")]
static SKILL_SYNC_VERSIONS: once_cell::sync::Lazy<std::sync::Mutex<std::collections::HashMap<String, (String, f64)>>> =
    once_cell::sync::Lazy::new(|| std::sync::Mutex::new(std::collections::HashMap::new()));

/// Parse a semver-like version string into a comparable tuple.
/// Returns (major, minor, patch); unparsable parts default to 0.
#[cfg(feature = "mesh")]
fn parse_version_tuple(v: &str) -> (u32, u32, u32) {
    let parts: Vec<&str> = v.split('.').collect();
    let major = parts.first().and_then(|s| s.parse().ok()).unwrap_or(0);
    let minor = parts.get(1).and_then(|s| s.parse().ok()).unwrap_or(0);
    let patch = parts.get(2).and_then(|s| s.parse().ok()).unwrap_or(0);
    (major, minor, patch)
}

#[cfg(feature = "mesh")]
async fn handle_skill_sync(
    app: &AppHandle,
    skill_id: &str,
    skill_kind: &str,
    content: &str,
    source_client_id: &str,
    ts: f64,
    version: &str,
    from: EndpointId,
) {
    // ── 版本号去重 ──
    // 当多个节点同时广播同一 skill_id 时,高版本覆盖低版本;
    // 版本相同则 ts 更新者胜。空 version 退化为先到者胜。
    let should_apply = {
        let known = SKILL_SYNC_VERSIONS.lock().unwrap();
        match known.get(skill_id) {
            Some((prev_ver, prev_ts)) => {
                let incoming = parse_version_tuple(version);
                let existing = parse_version_tuple(prev_ver);
                if incoming > existing {
                    // Higher version → always apply
                    true
                } else if incoming == existing && ts > *prev_ts {
                    // Same version, newer timestamp → apply
                    true
                } else {
                    log::info!(
                        "[mesh] SkillSync 忽略旧版本: skill_id={} incoming={}=({}.{}.{}) ts={} vs existing={}=({}.{}.{}) ts={}",
                        skill_id, version, incoming.0, incoming.1, incoming.2, ts,
                        prev_ver, existing.0, existing.1, existing.2, prev_ts
                    );
                    false
                }
            }
            None => true, // First seen → apply
        }
    };

    if should_apply {
        SKILL_SYNC_VERSIONS.lock().unwrap().insert(skill_id.to_string(), (version.to_string(), ts));
    }

    let kind = crate::hermes::evolution_signal::SkillKind::from_str_lossy(skill_kind);
    let outcome = if should_apply {
        crate::autoskill::upgrade_writer::UpgradeWriter::upgrade(app, skill_id, kind, content)
    } else {
        Ok(crate::autoskill::upgrade_writer::UpgradeOutcome::Skipped {
            reason: format!("older version or stale timestamp (version={}, ts={})", version, ts),
        })
    };

    let write_ok = match &outcome {
        Ok(crate::autoskill::upgrade_writer::UpgradeOutcome::Applied { targets }) => {
            log::info!(
                "[mesh] SkillSync 落盘: skill_id={} kind={} from={} targets={:?}",
                skill_id,
                skill_kind,
                from,
                targets
            );
            true
        }
        Ok(crate::autoskill::upgrade_writer::UpgradeOutcome::Skipped { reason }) => {
            log::info!(
                "[mesh] SkillSync 跳过: skill_id={} kind={} reason={}",
                skill_id,
                skill_kind,
                reason
            );
            true
        }
        Err(e) => {
            log::warn!(
                "[mesh] SkillSync 落盘失败: skill_id={} kind={} err={}",
                skill_id,
                skill_kind,
                e
            );
            false
        }
    };

    let _ = app.emit(
        "mesh://skill-received",
        serde_json::json!({
            "skillId": skill_id,
            "skillKind": skill_kind,
            "sourceClientId": source_client_id,
            "sourceEndpointId": from.to_string(),
            "ts": ts,
            "applied": write_ok,
        }),
    );
}

#[cfg(feature = "mesh")]
#[derive(Debug, thiserror::Error)]
pub enum MeshError {
    #[error("transport error: {0}")]
    Transport(#[from] transport::MeshTransportError),
    #[error("only coordinator can submit requirements")]
    NotCoordinator,
    #[error("{0}")]
    Other(String),
}

/// Tauri 全局状态：可选的活跃 mesh（mesh 按需 create/join，未激活时为 None）。
/// 注册方式同 automation::browser::SessionMap（lib.rs app.manage）。
#[cfg(feature = "mesh")]
#[derive(Default, Clone)]
pub struct MeshHandle {
    inner: Arc<RwLock<Option<Arc<MeshNode>>>>,
}

#[cfg(feature = "mesh")]
impl MeshHandle {
    pub async fn get(&self) -> Option<Arc<MeshNode>> {
        self.inner.read().await.clone()
    }
    pub async fn set(&self, node: Arc<MeshNode>) {
        *self.inner.write().await = Some(node);
    }
    pub async fn clear(&self) {
        *self.inner.write().await = None;
    }
}

/// Stub MeshHandle — always reports no active mesh.
#[cfg(not(feature = "mesh"))]
#[derive(Default, Clone)]
pub struct MeshHandle {
    _inner: std::sync::Arc<tokio::sync::RwLock<()>>,
}

#[cfg(not(feature = "mesh"))]
impl MeshHandle {
    pub async fn get(&self) -> Option<()> {
        None
    }
    pub async fn set(&self, _node: ()) {}
    pub async fn clear(&self) {}
}
