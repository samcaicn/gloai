// Copyright (c) 2026 AIMarketing
//
// mesh 消息签名与重放保护。
//
// 设计取舍：原计划用 join_code 派生的 AES-256-GCM 做应用层加密。但协议
// 连接本身已端到端加密（relay 只转发密文），且 gossip 广播仅在同 topic 成员间可见——
// 持有 join_code 才能派生 TopicId 并加入。因此 AES 层冗余，改为传输层协议原生
// SecretKey/PublicKey/Signature 对每条消息签名（与 gossip chat 示例一致），
// 提供「来源认证 + 完整性 + 防伪造」，防重放由 ReplayGuard 负责。
//
// 身份模型：EndpointId（Ed25519 公钥）= mesh 上的设备身份；Hello 握手自报
// device_fingerprint，由签名证明公钥归属。协调者据此建立 EndpointId ↔ 指纹 映射。

use std::collections::{HashMap, HashSet};

use iroh::{PublicKey, SecretKey, Signature};
use serde::{Deserialize, Serialize};

use super::ainl::MeshMessage;

/// Ed25519 签名长度（固定 64 字节）。
const SIGNATURE_LENGTH: usize = Signature::LENGTH;

/// 待签名的帧：时间戳 + nonce + 消息体。ts/nonce 一并签名以防重放与篡改。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MeshFrame {
    /// unix 毫秒。
    pub ts: f64,
    /// 单调递增的随机 nonce（防重放）。
    pub nonce: u64,
    pub msg: MeshMessage,
}

/// 签名信封：`data` = JSON(MeshFrame)，`signature` = secret_key.sign(data_bytes)。
/// 与 gossip chat 示例的 SignedMessage 结构同构。
///
/// 编码分两层（hybrid）：
/// - **信封外壳**用 postcard（`from`/`signature` 是原始字节，postcard 紧凑无歧义）。
///   外壳字段刻意用原始字节类型而非 `PublicKey`/`Signature`：gossip
///   实现的 `PublicKey::deserialize` 在二进制格式下走 `deserialize_any` 通用路径，
///   postcard 非自描述不实现 → `WontImplement`。
/// - **消息体** `data` 用 JSON 字符串。`MeshMessage` 是 `#[serde(tag="kind")]`
///   内部标签枚举，且其成员含 `serde_json::Value`（动态 JSON），两者反序列化都
///   依赖 `deserialize_any`（serde Content 缓冲），只有自描述格式（JSON）能处理；
///   postcard 无法承载，故消息体走 JSON。签名覆盖 `data.as_bytes()`——serde_json
///   紧凑序列化对同一结构确定性输出，双端可复现验签。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SignedEnvelope {
    /// 发送者公钥的原始字节（Ed25519, 32B）。边界处转 `PublicKey`。
    pub from: [u8; 32],
    /// JSON(MeshFrame) 字符串。验签通过后再 `serde_json::from_str` 二次解析。
    pub data: String,
    /// Ed25519 签名原始字节（64B，对 `data.as_bytes()` 签名）。边界处校验长度并转 `Signature`。
    pub signature: Vec<u8>,
}

/// 签名并编码为 postcard 字节流（用于 gossip broadcast / 直连流）。
pub fn sign_and_encode(
    secret_key: &SecretKey,
    ts: f64,
    nonce: u64,
    msg: &MeshMessage,
) -> Result<Vec<u8>, MeshAuthError> {
    let frame = MeshFrame { ts, nonce, msg: msg.clone() };
    let data = serde_json::to_string(&frame).map_err(|e| MeshAuthError::Json(e.to_string()))?;
    let signature = secret_key.sign(data.as_bytes());
    let envelope = SignedEnvelope {
        from: *secret_key.public().as_bytes(),
        data,
        signature: signature.to_bytes().to_vec(),
    };
    postcard::to_stdvec(&envelope).map_err(MeshAuthError::Decode)
}

/// 解码并验签。返回 (发送者公钥, 帧)。调用方需自行用 ReplayGuard 校验 ts/nonce。
pub fn verify_and_decode(bytes: &[u8]) -> Result<(PublicKey, MeshFrame), MeshAuthError> {
    let envelope: SignedEnvelope =
        postcard::from_bytes(bytes).map_err(MeshAuthError::Decode)?;
    let from = PublicKey::from_bytes(&envelope.from)
        .map_err(|e| MeshAuthError::InvalidKey(e.to_string()))?;
    // 签名是定长 64B；Vec 反序列化后必须校验长度，防止恶意短/长字节。
    if envelope.signature.len() != SIGNATURE_LENGTH {
        return Err(MeshAuthError::InvalidKey(format!(
            "signature length {} != {SIGNATURE_LENGTH}",
            envelope.signature.len()
        )));
    }
    let mut sig_bytes = [0u8; SIGNATURE_LENGTH];
    sig_bytes.copy_from_slice(&envelope.signature);
    let sig = Signature::from_bytes(&sig_bytes);
    from.verify(envelope.data.as_bytes(), &sig)
        .map_err(MeshAuthError::Verify)?;
    let frame: MeshFrame =
        serde_json::from_str(&envelope.data).map_err(|e| MeshAuthError::Json(e.to_string()))?;
    Ok((from, frame))
}

#[derive(Debug, thiserror::Error)]
pub enum MeshAuthError {
    #[error("postcard decode error: {0}")]
    Decode(#[from] postcard::Error),
    #[error("json (de)serialize error: {0}")]
    Json(String),
    #[error("signature verify error: {0}")]
    Verify(iroh::SignatureError),
    #[error("invalid public key bytes: {0}")]
    InvalidKey(String),
    #[error("replay: nonce {nonce} already seen from {from}")]
    Replay { from: String, nonce: u64 },
    #[error("stale: ts {ts} outside window (now {now}, window {window_ms}ms)")]
    Stale { ts: f64, now: f64, window_ms: f64 },
}

/// 防重放：按发送者记录近期 nonce，按时间窗淘汰。
pub struct ReplayGuard {
    /// from -> (nonce 集合, 已见最大 ts)
    seen: HashMap<PublicKey, (HashSet<u64>, f64)>,
    window_ms: f64,
}

impl ReplayGuard {
    pub fn new(window_ms: f64) -> Self {
        Self { seen: HashMap::new(), window_ms }
    }

    /// 校验并记录。`now` = 当前 unix 毫秒。
    pub fn check_and_record(
        &mut self,
        from: PublicKey,
        ts: f64,
        nonce: u64,
        now: f64,
    ) -> Result<(), MeshAuthError> {
        if (ts - now).abs() > self.window_ms {
            return Err(MeshAuthError::Stale {
                ts,
                now,
                window_ms: self.window_ms,
            });
        }
        let entry = self.seen.entry(from).or_insert_with(|| (HashSet::new(), ts));
        if !entry.0.insert(nonce) {
            return Err(MeshAuthError::Replay {
                from: from.to_string(),
                nonce,
            });
        }
        entry.1 = entry.1.max(ts);
        self.gc(now);
        Ok(())
    }

    /// 淘汰超过窗口的旧记录，避免内存无限增长。
    fn gc(&mut self, now: f64) {
        let cutoff = now - self.window_ms;
        self.seen.retain(|_, (_, last_ts)| *last_ts >= cutoff);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::hermes::mesh::ainl::{ClientInfo, MeshMessage};

    #[test]
    fn sign_verify_round_trip() {
        let sk = SecretKey::generate();
        let msg = MeshMessage::Hello {
            client: ClientInfo {
                client_id: "c1".into(),
                tenant_id: "t1".into(),
                device_fingerprint: "fp".into(),
                current_load: 0,
                available_skills: vec![],
                priority: "normal".into(),
                first_seen_ts: 0.0,
                last_active_ts: 0.0,
            },
            sig: "".into(),
        };
        let encoded = sign_and_encode(&sk, 1000.0, 42, &msg).unwrap();
        let (from, frame) = verify_and_decode(&encoded).unwrap();
        assert_eq!(from, sk.public());
        assert_eq!(frame.nonce, 42);
        assert_eq!(frame.msg, msg);
    }

    #[test]
    fn replay_guard_rejects_duplicate() {
        let mut guard = ReplayGuard::new(10_000.0);
        let pk = SecretKey::generate().public();
        guard.check_and_record(pk, 1000.0, 1, 1000.0).unwrap();
        let err = guard.check_and_record(pk, 1001.0, 1, 1001.0).unwrap_err();
        assert!(matches!(err, MeshAuthError::Replay { .. }));
    }

    #[test]
    fn replay_guard_rejects_stale() {
        let mut guard = ReplayGuard::new(10_000.0);
        let pk = SecretKey::generate().public();
        let err = guard.check_and_record(pk, 1000.0, 1, 100_000.0).unwrap_err();
        assert!(matches!(err, MeshAuthError::Stale { .. }));
    }
}
