// Copyright (c) 2026 tupAI
//
// 安全 P2P 传输层封装：Endpoint（QUIC + NAT 穿透 + dial-by-public-key）+ Gossip（广播树）
// + Router（协议分发）+ MemoryLookup（对端地址簿）。
//
// 用法镜像 gossip chat 示例：Endpoint::builder(presets::N0) → Gossip::builder().spawn
// → Router::builder(endpoint).accept(GOSSIP_ALPN, gossip).spawn → subscribe_and_join → split。
//
// 三个数据面通道在此汇聚：gossip 广播（本文件）、直连 bi-stream（open_bi，P1）、
// blobs（files.rs）。P0 仅启用 gossip。

use std::net::{Ipv4Addr, SocketAddrV4};
use std::time::Duration;

use bytes::Bytes;
use iroh::address_lookup::memory::MemoryLookup;
use iroh::endpoint::presets;
use iroh::protocol::Router;
use iroh::{Endpoint, EndpointAddr, EndpointId, RelayMode, SecretKey};
use iroh_gossip::api::{GossipReceiver, GossipSender};
use iroh_gossip::net::{Gossip, GOSSIP_ALPN};
use iroh_gossip::proto::TopicId;

use super::ainl::MeshMessage;
use super::auth::sign_and_encode;
use super::ticket::MeshTicket;

/// 安全 P2P 传输句柄。Clone 友好（内部均为 Arc）。一个 mesh 进程持有一个。
#[derive(Clone)]
pub struct MeshTransport {
    endpoint: Endpoint,
    gossip: Gossip,
    #[allow(dead_code)]
    router: Router,
    memory_lookup: MemoryLookup,
    secret_key: SecretKey,
}

impl MeshTransport {
    /// 启动 endpoint + gossip + router。使用 n0 生产 relay（N0 预设）。
    pub async fn start(secret_key: SecretKey) -> Result<Self, MeshTransportError> {
        let memory_lookup = MemoryLookup::new();
        let endpoint = Endpoint::builder(presets::N0)
            .secret_key(secret_key.clone())
            .address_lookup(memory_lookup.clone())
            .relay_mode(RelayMode::Default)
            .bind_addr(SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, 0))
            .map_err(|e| MeshTransportError::BindAddr(e.to_string()))?
            .bind()
            .await
            .map_err(|e| MeshTransportError::Bind(e.to_string()))?;

        let gossip = Gossip::builder().spawn(endpoint.clone());
        let router = Router::builder(endpoint.clone())
            .accept(GOSSIP_ALPN, gossip.clone())
            .spawn();

        // 等待 relay home，使 addr() 含 relay URL，便于 NAT 后的对端 dial。
        // 不阻塞过久（5s 超时即继续——纯局域网场景可无 relay）。
        match tokio::time::timeout(Duration::from_secs(5), endpoint.online()).await {
            Ok(()) => log::debug!("[mesh] endpoint online (relay established)"),
            Err(_) => log::warn!(
                "[mesh] endpoint did not come online within 5s; relay connectivity may be impaired"
            ),
        }

        Ok(Self { endpoint, gossip, router, memory_lookup, secret_key })
    }

    pub fn endpoint_id(&self) -> EndpointId {
        self.endpoint.id()
    }

    pub fn addr(&self) -> EndpointAddr {
        self.endpoint.addr()
    }

    pub fn secret_key(&self) -> &SecretKey {
        &self.secret_key
    }

    /// 作为协调者打开 topic（无 bootstrap，等待 joiner）。
    pub async fn open_as_coordinator(
        &self,
        topic: TopicId,
    ) -> Result<(GossipSender, GossipReceiver), MeshTransportError> {
        let topic_handle = self
            .gossip
            .subscribe_and_join(topic, vec![])
            .await
            .map_err(|e| MeshTransportError::Gossip(e.to_string()))?;
        Ok(topic_handle.split())
    }

    /// 作为 joiner 加入：把协调者地址塞进地址簿，再 subscribe_and_join。
    pub async fn join_mesh(
        &self,
        ticket: &MeshTicket,
    ) -> Result<(GossipSender, GossipReceiver), MeshTransportError> {
        self.memory_lookup.add_endpoint_info(ticket.coordinator.clone());
        let topic_handle = self
            .gossip
            .subscribe_and_join(ticket.topic_id, vec![ticket.coordinator.id])
            .await
            .map_err(|e| MeshTransportError::Gossip(e.to_string()))?;
        Ok(topic_handle.split())
    }
}

/// 生成时间戳（unix 毫秒）。
pub fn now_ms() -> f64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as f64)
        .unwrap_or(0.0)
}

/// 签名并广播一条 mesh 消息。`nonce` 由调用方持有并自增，保证单调。
pub async fn broadcast_message(
    sender: &GossipSender,
    secret_key: &SecretKey,
    nonce: &mut u64,
    msg: &MeshMessage,
) -> Result<(), MeshTransportError> {
    let encoded = sign_and_encode(secret_key, now_ms(), *nonce, msg)
        .map_err(|e| MeshTransportError::Encode(e.to_string()))?;
    *nonce += 1;
    sender
        .broadcast(Bytes::from(encoded))
        .await
        .map_err(|e| MeshTransportError::Gossip(e.to_string()))
}

#[derive(Debug, thiserror::Error)]
pub enum MeshTransportError {
    #[error("bind addr error: {0}")]
    BindAddr(String),
    #[error("bind error: {0}")]
    Bind(String),
    #[error("gossip error: {0}")]
    Gossip(String),
    #[error("encode error: {0}")]
    Encode(String),
}
