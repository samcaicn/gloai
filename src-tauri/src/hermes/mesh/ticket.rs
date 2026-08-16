// Copyright (c) 2026 AIMarketing
//
// mesh 入场凭据：MeshTicket / join_code 派生。
//
// 纯 P2P 模型（用户选定）：join_code 即 mesh 入场凭据，云端不进入数据路径。
// join_code 经 SHA-256 确定性派生出 gossip TopicId——所有持有同一 join_code 的设备
// 订阅同一 topic 即成网。完整 MeshTicket 额外携带协调者的 EndpointAddr 作为 bootstrap
// 引导（需至少一个已知对端才能 dial），通过 QR / 剪贴板 / deeplink 分享。
//
// 编码采用 postcard + base32（无填充），与 gossip chat 示例的 Ticket 同构。

use std::collections::BTreeSet;
use std::str::FromStr;

use iroh::{EndpointAddr, TransportAddr};
use iroh_gossip::proto::TopicId;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

/// 纯 P2P 入场凭据。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct MeshTicket {
    /// gossip topic，由 join_code 派生。
    pub topic_id: TopicId,
    /// 引导协调者的地址（EndpointId + 直连/relay 地址）。
    pub coordinator: EndpointAddr,
    /// 人类可读入场码（8 位数字），用于显示/口述核对。
    pub join_code: String,
}

impl MeshTicket {
    /// 协调者创建 mesh 时构造自身 ticket。
    pub fn new_for_coordinator(join_code: &str, my_addr: EndpointAddr) -> Self {
        Self {
            topic_id: derive_topic_id(join_code),
            coordinator: my_addr,
            join_code: join_code.to_string(),
        }
    }

    fn to_bytes(&self) -> Result<Vec<u8>, postcard::Error> {
        postcard::to_stdvec(self)
    }

    fn from_bytes(bytes: &[u8]) -> Result<Self, postcard::Error> {
        postcard::from_bytes(bytes)
    }

    /// 编码为 base32 字符串（小写、无填充），适合 QR / 分享。
    pub fn encode(&self) -> String {
        let bytes = match self.to_bytes() {
            Ok(b) => b,
            Err(e) => {
                log::error!("[mesh] MeshTicket serialization failed: {}", e);
                return String::new();
            }
        };
        let mut text = data_encoding::BASE32_NOPAD.encode(&bytes[..]);
        text.make_ascii_lowercase();
        text
    }
}

/// 由 join_code 派生 joiner 的 ticket（无协调者地址——加入时由对端 Hello 补全）。
pub fn derive_topic_id(join_code: &str) -> TopicId {
    let hash = Sha256::digest(join_code.as_bytes());
    // SHA-256 输出恰为 32 字节，与 TopicId 内部 [u8; 32] 对齐。
    // iroh-gossip 0.101 的 TopicId::new 要求 Digest trait bounds（不可用），
    // 改用 from_bytes([u8; 32])。GenericArray<u8, U32> -> [u8; 32] via Into。
    TopicId::from_bytes(hash.into())
}

impl std::fmt::Display for MeshTicket {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.encode())
    }
}

impl FromStr for MeshTicket {
    type Err = MeshTicketError;
    fn from_str(s: &str) -> Result<Self, Self::Err> {
        let bytes = data_encoding::BASE32_NOPAD
            .decode(s.to_ascii_uppercase().as_bytes())
            .map_err(MeshTicketError::Base32)?;
        Self::from_bytes(&bytes).map_err(MeshTicketError::Decode)
    }
}

#[derive(Debug, thiserror::Error)]
pub enum MeshTicketError {
    #[error("base32 decode error: {0}")]
    Base32(#[from] data_encoding::DecodeError),
    #[error("postcard decode error: {0}")]
    Decode(#[from] postcard::Error),
}

/// 用 join_code 与协调者地址拼装一个最小可用的 EndpointAddr（当对端只给了 EndpointId
/// 而无直连地址时，dial 会经 relay/Pkarr 解析）。此处保留以便测试构造。
#[allow(dead_code)]
pub fn endpoint_addr_from_id(id: iroh::EndpointId) -> EndpointAddr {
    EndpointAddr {
        id,
        addrs: BTreeSet::<TransportAddr>::new(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use iroh::SecretKey;

    #[test]
    fn topic_id_is_deterministic() {
        let a = derive_topic_id("12345678");
        let b = derive_topic_id("12345678");
        let c = derive_topic_id("87654321");
        assert_eq!(a.as_bytes(), b.as_bytes());
        assert_ne!(a.as_bytes(), c.as_bytes());
    }

    #[test]
    fn ticket_encode_decode_round_trip() {
        let sk = SecretKey::generate();
        let addr = EndpointAddr {
            id: sk.public(),
            addrs: BTreeSet::new(),
        };
        let ticket = MeshTicket::new_for_coordinator("12345678", addr);
        let encoded = ticket.encode();
        let decoded: MeshTicket = encoded.parse().expect("decode ticket");
        assert_eq!(ticket, decoded);
    }
}
