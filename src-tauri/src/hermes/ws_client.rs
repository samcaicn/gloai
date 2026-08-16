// Copyright (c) 2026 tupAI
//
// TransportLayer — WebSocket 客户端到 127.0.0.1:8642 Hermes.
//
// v4 §2.5 — 客户端 ↔ 服务器双向通道的 WS 半边。Server 端通过
//   WS /v1/skills/stream
// 主动 push 三类事件:`evaluation_complete` / `evolution_complete` /
// `proposal_withdrawn`,外加 `heartbeat` 和 `disconnected` 状态。
//
// 工作模式:
//   1. `new(url, tx)` 构建一个轻量 handle(只持 url + token + 发送端)。
//   2. 业务侧 spawn `reconnect_loop()` —— 一个长生命周期任务。
//   3. 任何收到的事件塞进 mpsc,业务侧从 channel 消费。
//
// 重连:指数退避 1s → 2s → 4s → 8s,封顶 60s。任一次成功连接都会重置
// 退避计数。`health()` 是 HTTP 半边的探针;WS 半边只看自己 socket
// 的 read EOF。
//
// 依赖:`tokio-tungstenite = "0.21"`(以及其传递依赖 `tungstenite`)。
// 主线程在整合阶段会往 Cargo.toml 追加 `tokio-tungstenite`。
//
// Surface is reserved for the main thread; allow dead_code until wired up.
#![allow(dead_code)]
// 该 crate 之前未在 v3 中使用,本模块是它的第一个调用方。

use std::time::Duration;

use futures::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use tokio::sync::mpsc;
use tokio::time::sleep;
use tokio_tungstenite::connect_async;
use tokio_tungstenite::tungstenite::{
    handshake::client::{generate_key, Request},
    http::HeaderValue,
    Message,
};

use super::transport::{SkillEvaluation, TransportError};

// ---------------------------------------------------------------------------
// 事件 (consumer-facing)
// ---------------------------------------------------------------------------

/// 业务侧从 channel 收到的事件。所有变体都带上下文(proposal_id / skill_id),
/// 让 `automation` / `skill::registry` 等其它 agent 可以无歧义路由。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum WsEvent {
    /// 评估完成,可触发 `commands::adopt_proposal`。
    #[serde(rename = "eval")]
    EvaluationComplete {
        proposal_id: String,
        evaluation: SkillEvaluation,
    },
    /// 深度进化完成(新版本生效)。`before` / `after` 是新旧版本的总分。
    #[serde(rename = "evolution")]
    EvolutionComplete {
        skill_id: String,
        before: f32,
        after: f32,
    },
    /// Server 撤回某个 proposal(可能是重复、超时、违规)。
    #[serde(rename = "withdraw")]
    ProposalWithdrawn { proposal_id: String, reason: String },
    /// Server 心跳(每 25s 一发),用于保持 NAT 映射。
    #[serde(rename = "heartbeat")]
    Heartbeat,
    /// 客户端检测到 socket 断开,触发自动重连。
    #[serde(rename = "disconnected")]
    Disconnected { reason: String },
}

// ---------------------------------------------------------------------------
// Server → Client 原始信封(与 `WsEvent` 的 `kind` 字段对齐)
// ---------------------------------------------------------------------------

#[derive(Debug, Deserialize)]
struct ServerFrame {
    kind: String,
    #[serde(default)]
    proposal_id: Option<String>,
    #[serde(default)]
    skill_id: Option<String>,
    #[serde(default)]
    reason: Option<String>,
    #[serde(default)]
    before: Option<f32>,
    #[serde(default)]
    after: Option<f32>,
    #[serde(default)]
    evaluation: Option<SkillEvaluation>,
}

// ---------------------------------------------------------------------------
// Client
// ---------------------------------------------------------------------------

const RECONNECT_BASE: Duration = Duration::from_secs(1);
const RECONNECT_MAX: Duration = Duration::from_secs(60);
const HEARTBEAT_INTERVAL: Duration = Duration::from_secs(25);

pub struct HermesWs {
    url: String,
    token: Option<String>,
    tx: mpsc::Sender<WsEvent>,
}

impl HermesWs {
    pub fn new(url: impl Into<String>, tx: mpsc::Sender<WsEvent>) -> Self {
        Self { url: url.into(), token: None, tx }
    }

    pub fn with_token(mut self, token: String) -> Self {
        self.token = Some(token);
        self
    }

    pub fn url(&self) -> &str { &self.url }

    /// 单次连接尝试。失败立刻返回 `Err` —— 调用方负责退避重试。
    pub async fn connect(&self) -> Result<(), TransportError> {
        // Build the WS request manually so we can attach the
        // `Authorization` header *before* handing it to the
        // handshake. `connect(&url)` in tokio-tungstenite 0.21 no
        // longer exposes a mutable builder surface.
        //
        // 修复"Missing, duplicated or incorrect header sec-websocket-key"：
        // tokio-tungstenite 0.21 接收手动构造的 Request 时不会自动补齐
        // WebSocket 握手头（Host / Upgrade / Connection / Sec-WebSocket-Version /
        // Sec-WebSocket-Key）。必须由调用方自己显式添加。
        // `generate_key()` 生成 16 字节随机 base64（RFC 6455 要求）。
        let host_header = url_host(&self.url);
        let mut request = Request::builder()
            .method("GET")
            .uri(&self.url)
            .header("Upgrade", "websocket")
            .header("Connection", "Upgrade")
            .header("Sec-WebSocket-Version", "13")
            .header("Sec-WebSocket-Key", generate_key())
            .body(())
            .map_err(|e| {
                TransportError::Unreachable(format!("ws request build failed: {}", e))
            })?;
        if let Some(h) = host_header {
            if let Ok(hv) = HeaderValue::from_str(&h) {
                request.headers_mut().insert("Host", hv);
            }
        }
        if let Some(t) = &self.token {
            request.headers_mut().insert(
                "Authorization",
                HeaderValue::from_str(&format!("Bearer {}", t))
                    .map_err(|e| TransportError::Auth(e.to_string()))?,
            );
        }
        // `connect_async(request).await` returns
        // `(WebSocket, Response)`; destructure so `await` is on the
        // future, not on the tuple.
        let (mut ws, _response) = connect_async(request)
            .await
            .map_err(|e| TransportError::Unreachable(format!("ws connect failed: {}", e)))?;

        // 业务侧不需要 ping 帧 —— server 自己会推 heartbeat。如果客户端
        // 想反向往 server 写消息,可以在这里加一个 ws.send(...) 分支。
        let mut hb = tokio::time::interval(HEARTBEAT_INTERVAL);
        hb.tick().await; // skip the immediate first tick
        loop {
            tokio::select! {
                _ = hb.tick() => {
                    // 客户端不发任何应用层数据;保留这个分支以便将来扩展。
                }
                frame = ws.next() => {
                    match frame {
                        Some(Ok(Message::Text(text))) => {
                            if let Some(ev) = parse_frame(&text) {
                                if self.tx.send(ev).await.is_err() {
                                    // 业务侧 channel 已关闭,优雅退出
                                    return Ok(());
                                }
                            }
                        }
                        Some(Ok(Message::Ping(p))) => {
                            let _ = ws.send(Message::Pong(p)).await;
                        }
                        Some(Ok(Message::Close(_))) | None => {
                            // EOF / server 主动关闭 -> 退出,reconnect_loop 接管
                            return Ok(());
                        }
                        Some(Ok(_)) => {
                            // Binary / Pong / Frame: 忽略
                        }
                        Some(Err(e)) => {
                            log::debug!("[ws] read error: {}", e);
                            return Ok(());
                        }
                    }
                }
            }
        }
    }

    /// 长生命周期任务:不停重连。指数退避 1s/2s/4s/8s/...封顶 60s。
    /// 成功 connect 后再次断开,从 1s 重新开始。
    pub async fn reconnect_loop(&self) {
        let mut backoff = RECONNECT_BASE;
        loop {
            log::info!("[ws] connecting to {}", self.url);
            let connected_ok = match self.connect().await {
                Ok(()) => {
                    // socket 关闭,通知业务侧 + 重置退避
                    let _ = self
                        .tx
                        .send(WsEvent::Disconnected {
                            reason: "socket closed".to_string(),
                        })
                        .await;
                    true
                }
                Err(error) => {
                    log::debug!("[ws] connect failed: {}", error);
                    let _ = self
                        .tx
                        .send(WsEvent::Disconnected {
                            reason: format!("connect failed: {}", error),
                        })
                        .await;
                    false
                }
            };
            log::info!("[ws] reconnecting in {:?}", backoff);
            sleep(backoff).await;
            // 之前 backoff *= 2 永远发生在 loop 末尾 → 成功连接后
            // 下一次也会翻倍,导致长期重连时退避永远在涨。改成:
            // 成功一次后退避重置回 BASE,失败才翻倍。
            if connected_ok {
                backoff = RECONNECT_BASE;
            } else {
                backoff = (backoff * 2).min(RECONNECT_MAX);
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 内部
// ---------------------------------------------------------------------------

/// 从 `ws://host:port/path` 或 `wss://host:port/path` 中提取
/// `host:port` 作为 HTTP `Host` 头的内容。解析失败返回 `None`。
fn url_host(endpoint: &str) -> Option<String> {
    let rest = endpoint
        .strip_prefix("wss://")
        .or_else(|| endpoint.strip_prefix("ws://"))?;
    let end = rest
        .find(['/', '?', '#'])
        .unwrap_or(rest.len());
    let authority = &rest[..end];
    if authority.is_empty() {
        None
    } else {
        Some(authority.to_string())
    }
}

fn parse_frame(text: &str) -> Option<WsEvent> {
    let frame: ServerFrame = match serde_json::from_str(text) {
        Ok(f) => f,
        Err(error) => {
            log::debug!("[ws] bad frame: {} (raw: {})", error, text);
            return None;
        }
    };
    Some(match frame.kind.as_str() {
        "eval" | "evaluation_complete" => WsEvent::EvaluationComplete {
            proposal_id: frame.proposal_id?,
            evaluation: frame.evaluation?,
        },
        "evolution" | "evolution_complete" => WsEvent::EvolutionComplete {
            skill_id: frame.skill_id?,
            before: frame.before.unwrap_or(0.0),
            after: frame.after.unwrap_or(0.0),
        },
        "withdraw" | "proposal_withdrawn" => WsEvent::ProposalWithdrawn {
            proposal_id: frame.proposal_id?,
            reason: frame.reason.unwrap_or_else(|| "(no reason)".to_string()),
        },
        "heartbeat" => WsEvent::Heartbeat,
        other => {
            log::debug!("[ws] unknown frame kind: {}", other);
            return None;
        }
    })
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_eval_frame() {
        // The wire format for `evaluation` matches
        // `hermes::transport::SkillEvaluation` which uses
        // `#[serde(rename_all = "camelCase")]` (so `proposalId`,
        // not `proposal_id`) and `EvalVerdict` uses
        // `#[serde(rename_all = "PascalCase")]` (so `Accept`).
        let raw = r#"{
            "kind": "eval",
            "proposal_id": "p-1",
            "evaluation": {
                "proposalId": "p-1",
                "total": 0.91,
                "verdict": "Accept",
                "scores": {
                    "safety": 0.9,
                    "success": 0.92,
                    "generalization": 0.85,
                    "dedup": 0.9,
                    "cost": 0.95
                },
                "evaluatedAt": "2026-06-06T00:00:00Z",
                "degraded": false
            }
        }"#;
        let ev = parse_frame(raw).expect("eval frame must parse");
        match ev {
            WsEvent::EvaluationComplete { proposal_id, evaluation } => {
                assert_eq!(proposal_id, "p-1");
                assert!((evaluation.total - 0.91).abs() < 1e-3);
            }
            other => panic!("expected EvaluationComplete, got {:?}", other),
        }
    }

    #[test]
    fn parse_evolution_frame() {
        let raw = r#"{"kind":"evolution","skill_id":"s-7","before":0.62,"after":0.83}"#;
        match parse_frame(raw).unwrap() {
            WsEvent::EvolutionComplete { skill_id, before, after } => {
                assert_eq!(skill_id, "s-7");
                assert!((before - 0.62).abs() < 1e-3);
                assert!((after - 0.83).abs() < 1e-3);
            }
            other => panic!("expected EvolutionComplete, got {:?}", other),
        }
    }

    #[test]
    fn parse_withdraw_frame() {
        let raw = r#"{"kind":"withdraw","proposal_id":"p-2","reason":"duplicate"}"#;
        match parse_frame(raw).unwrap() {
            WsEvent::ProposalWithdrawn { proposal_id, reason } => {
                assert_eq!(proposal_id, "p-2");
                assert_eq!(reason, "duplicate");
            }
            other => panic!("expected ProposalWithdrawn, got {:?}", other),
        }
    }

    #[test]
    fn parse_heartbeat_frame() {
        let raw = r#"{"kind":"heartbeat"}"#;
        assert!(matches!(parse_frame(raw).unwrap(), WsEvent::Heartbeat));
    }

    #[test]
    fn unknown_kind_returns_none() {
        let raw = r#"{"kind":"banana"}"#;
        assert!(parse_frame(raw).is_none());
    }

    #[test]
    fn serialize_uses_kind_tag() {
        let ev = WsEvent::Heartbeat;
        let json = serde_json::to_string(&ev).unwrap();
        assert!(json.contains("\"kind\":\"heartbeat\""));
    }
}
