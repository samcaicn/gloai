// Copyright (c) 2026 MeeJoy
//
// 企业微信 (WeCom) 智能机器人长连接适配器。
//
// 抄自企微官方文档 https://developer.work.weixin.qq.com/document/path/101463
// 与 @wecom/aibot-node-sdk / wecom-aibot-python-sdk 实现思路一致：
//   1. WSS 连接到 wss://openws.work.weixin.qq.com
//   2. 发送 aibot_subscribe 鉴权帧（bot_id + secret）
//   3. 等待 errcode==0 响应（8s 超时）
//   4. 进入主循环：30s ping 心跳 + 接收 aibot_msg_callback/aibot_event_callback
//   5. 断线重连（jittered_backoff + circuit breaker 2 次熔断）
//
// 协议帧结构（全部为 JSON 文本帧）：
//   鉴权请求：{"cmd":"aibot_subscribe","headers":{"req_id":"..."},
//              "body":{"bot_id":"...","secret":"..."}}
//   鉴权响应：{"headers":{"req_id":"..."},"errcode":0,"errmsg":"ok"}
//   消息回调：{"cmd":"aibot_msg_callback","headers":{"req_id":"..."},"body":{...}}
//   事件回调：{"cmd":"aibot_event_callback","headers":{"req_id":"..."},"body":{...}}
//   主动推送：{"cmd":"aibot_send_msg","headers":{"req_id":"..."},
//              "body":{"chatid":"...","msgtype":"text","text":{"content":"..."}}}
//   心跳请求：文本帧 "ping"
//   心跳响应：文本帧 "pong"
//
// 连接限制：每个 bot 同一时间只能保持一个有效长连接。若收到
// aibot_event_callback.event.eventtype == "disconnected_event"，表示另一个
// 连接已接管本连接，本机应立即退出（不再重连，避免与对端互踢死循环）。
//
// 【铁律】endpoint URL 写死在 `im_endpoints.rs::wecom_wss_url()`，
// 用户永远无法填、也无法通过任何 UI 绕过——这是从 `im_config_set`
// 强制覆盖层 (`commands/im_config.rs::override_provider_endpoint`) 写入的。

use async_trait::async_trait;
use futures_util::{SinkExt, StreamExt};
use rand::Rng;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::{broadcast, mpsc, oneshot, Mutex, Notify};
use tokio::time::interval;
use tokio_tungstenite::tungstenite::handshake::client::{generate_key, Request};
use tokio_tungstenite::tungstenite::http::HeaderValue;
use tokio_tungstenite::tungstenite::Message as WsMessage;

use super::adapter_base::{IMAdapter, IMAdapterEvent, IMBinding, IMProvider};
use super::im_endpoints::ImChannelKind;

#[derive(Clone, Debug)]
pub struct WecomAdapterOptions {
    /// 心跳间隔。企微官方建议 30s。
    pub heartbeat_ms: u64,
    /// aibot_subscribe 鉴权响应等待超时。8s 足够覆盖网络往返。
    pub subscribe_timeout_ms: u64,
    /// 重连基准。失败后翻倍，封顶 60s。
    pub reconnect_base_ms: u64,
}

impl Default for WecomAdapterOptions {
    fn default() -> Self {
        Self {
            heartbeat_ms: 30_000,        // 30s — 抄企微官方文档建议
            subscribe_timeout_ms: 8_000, // 8s — 与 feishu_adapter 对齐
            reconnect_base_ms: 5_000,    // 5s
        }
    }
}

/// 企业微信智能机器人长连接适配器。
///
/// 实现完整的 aibot_subscribe 协议：WSS 握手 → 鉴权 → 心跳保活 →
/// 消息/事件回调接收 → 主动推送。断线自动重连（jittered backoff），
/// 连续 2 次失败触发 circuit breaker 退出（与 feishu/websocket adapter 一致）。
pub struct WecomAdapter {
    binding: IMBinding,
    provider: IMProvider,
    options: WecomAdapterOptions,
    tx: broadcast::Sender<IMAdapterEvent>,
    /// 写队列。`None` 表示尚未 connect。
    out_tx: Arc<Mutex<Option<mpsc::UnboundedSender<WsMessage>>>>,
    /// 后台连接任务取消标志。`disconnect()` 置为 true，后台内外层
    /// loop 检查后退出。
    cancel: Arc<AtomicBool>,
    /// Notify 用于 disconnect() 即时唤醒后台 select! 内的等待，
    /// 避免 cancel 标志要等 tick(30s)/read 超时才被检查。
    cancel_notify: Arc<Notify>,
    /// 连接代际计数器。每次 `connect()` 自增，后台任务记住自己的代际，
    /// 若发现代际变化（被新的 connect 取代）则退出。
    generation: Arc<AtomicU64>,
}

impl WecomAdapter {
    pub fn new(binding: IMBinding) -> Self {
        let (tx, _) = broadcast::channel(256);
        let endpoint = ImChannelKind::WeCom
            .wecom_wss_url()
            .unwrap_or("wss://openws.work.weixin.qq.com")
            .to_string();
        let provider = IMProvider::WeCom {
            endpoint,
            secret: None,
        };
        Self {
            binding,
            provider,
            options: WecomAdapterOptions::default(),
            tx,
            out_tx: Arc::new(Mutex::new(None)),
            cancel: Arc::new(AtomicBool::new(false)),
            cancel_notify: Arc::new(Notify::new()),
            generation: Arc::new(AtomicU64::new(0)),
        }
    }

    /// 从 `binding.metadata` 提取 BotID。
    ///
    /// 存储路径（与 im_config 加密层 + UI 表单对齐）：
    ///   1. metadata.bot_id（单独字段，OAuth 扫码路径）
    ///   2. metadata.secret 拆分 "BotID:Secret" 格式取冒号前部分
    ///   3. metadata.app_id（兼容飞书写法，企微扫码可能复用）
    fn bot_id(&self) -> Option<String> {
        if let Some(v) = self.binding.metadata.get("bot_id").and_then(|v| v.as_str()) {
            if !v.is_empty() {
                return Some(v.to_string());
            }
        }
        if let Some(secret) = self.binding.metadata.get("secret").and_then(|v| v.as_str()) {
            if !secret.is_empty() {
                if let Some(idx) = secret.find(':') {
                    let bot_id = &secret[..idx];
                    if !bot_id.is_empty() {
                        return Some(bot_id.to_string());
                    }
                }
            }
        }
        if let Some(v) = self.binding.metadata.get("app_id").and_then(|v| v.as_str()) {
            if !v.is_empty() {
                return Some(v.to_string());
            }
        }
        None
    }

    /// 从 `binding.metadata` 提取 Secret。
    ///
    /// 存储路径：
    ///   1. metadata.app_secret（单独字段，与 im_config AES-256-GCM 加密对齐）
    ///   2. metadata.secret 拆分 "BotID:Secret" 格式取冒号后部分
    ///   3. metadata.secret 无冒号 + metadata.bot_id 存在 → 整体作为 secret
    fn bot_secret(&self) -> Option<String> {
        if let Some(v) = self.binding.metadata.get("app_secret").and_then(|v| v.as_str()) {
            if !v.is_empty() {
                return Some(v.to_string());
            }
        }
        if let Some(secret) = self.binding.metadata.get("secret").and_then(|v| v.as_str()) {
            if !secret.is_empty() {
                if let Some(idx) = secret.find(':') {
                    let s = &secret[idx + 1..];
                    if !s.is_empty() {
                        return Some(s.to_string());
                    }
                } else if self.binding.metadata.get("bot_id").is_some() {
                    // metadata.bot_id 单独存在 + metadata.secret 无冒号 →
                    // secret 整体是 BotSecret
                    return Some(secret.to_string());
                }
            }
        }
        None
    }

    /// 构造 WSS handshake Request（带标准 WS 握手头）。
    /// 抄自 feishu_adapter.rs::build_ws_request。
    fn build_ws_request(wss_url: &str) -> Result<Request, String> {
        let host_header = url_host(wss_url);
        let mut request = Request::builder()
            .method("GET")
            .uri(wss_url)
            .header("Upgrade", "websocket")
            .header("Connection", "Upgrade")
            .header("Sec-WebSocket-Version", "13")
            .header("Sec-WebSocket-Key", generate_key())
            .body(())
            .map_err(|e| format!("ws request build failed: {}", e))?;
        if let Some(h) = host_header {
            if let Ok(hv) = HeaderValue::from_str(&h) {
                request.headers_mut().insert("Host", hv);
            }
        }
        Ok(request)
    }

    /// 构造 aibot_subscribe 鉴权帧。
    /// 抄自企微官方文档订阅请求示例。
    fn build_subscribe_frame(bot_id: &str, secret: &str) -> Result<WsMessage, String> {
        let req_id = uuid::Uuid::new_v4().to_string();
        let frame = serde_json::json!({
            "cmd": "aibot_subscribe",
            "headers": { "req_id": req_id },
            "body": { "bot_id": bot_id, "secret": secret }
        });
        let text = serde_json::to_string(&frame)
            .map_err(|e| format!("encode subscribe frame: {}", e))?;
        Ok(WsMessage::Text(text))
    }
}

#[async_trait]
impl IMAdapter for WecomAdapter {
    fn provider(&self) -> &IMProvider {
        &self.provider
    }

    async fn connect(&self) -> Result<(), String> {
        let endpoint = match &self.provider {
            IMProvider::WeCom { endpoint, .. } => endpoint.clone(),
            _ => return Err("wecom adapter provider type mismatch".to_string()),
        };
        if endpoint.is_empty() {
            return Err("wecom wss endpoint is empty".to_string());
        }
        if !(endpoint.starts_with("ws://") || endpoint.starts_with("wss://")) {
            return Err(format!(
                "wecom wss endpoint must be ws:// or wss://, got: {}",
                endpoint
            ));
        }

        let bot_id = self
            .bot_id()
            .ok_or_else(|| "wecom bot_id missing".to_string())?;
        let bot_secret = self
            .bot_secret()
            .ok_or_else(|| "wecom bot secret missing".to_string())?;

        let (out_tx, mut out_rx) = mpsc::unbounded_channel::<WsMessage>();
        let my_gen;
        {
            let mut g = self.out_tx.lock().await;
            if g.is_some() {
                return Ok(());
            }
            self.cancel.store(false, Ordering::Release);
            my_gen = self.generation.fetch_add(1, Ordering::SeqCst) + 1;
            *g = Some(out_tx);
        }

        let (first_result_tx, first_result_rx) = oneshot::channel::<Result<(), String>>();

        let binding_id = self.binding.id.clone();
        let cancel = self.cancel.clone();
        let cancel_notify = self.cancel_notify.clone();
        let generation = self.generation.clone();
        let out_tx_holder = self.out_tx.clone();
        let tx = self.tx.clone();
        let heartbeat = Duration::from_millis(self.options.heartbeat_ms.max(1_000));
        let subscribe_timeout = Duration::from_millis(self.options.subscribe_timeout_ms);
        let reconnect_base = Duration::from_millis(self.options.reconnect_base_ms.max(500));
        let reconnect_max = Duration::from_secs(60);

        tokio::spawn(async move {
            let mut first_reported = false;
            let mut first_result_tx_opt = Some(first_result_tx);
            let mut backoff = reconnect_base;
            let disconnected_announced = false;
            const CIRCUIT_BREAKER_THRESHOLD: u32 = 2;
            const CIRCUIT_BREAKER_COOLDOWN_SECS: u64 = 60;
            let mut consecutive_failures: u32 = 0;

            loop {
                // 外层 loop 检查 cancel / 代际
                if cancel.load(Ordering::Acquire)
                    || generation.load(Ordering::Acquire) != my_gen
                {
                    if let Some(tx0) = first_result_tx_opt.take() {
                        let _ = tx0.send(Err("cancelled by disconnect".into()));
                    }
                    if !disconnected_announced {
                        let _ = tx.send(IMAdapterEvent {
                            binding_id: binding_id.clone(),
                            kind: "disconnected".into(),
                            payload: serde_json::Value::Null,
                            ts: chrono::Utc::now().timestamp_millis(),
                        });
                    }
                    return;
                }

                let _ = tx.send(IMAdapterEvent {
                    binding_id: binding_id.clone(),
                    kind: "connecting".into(),
                    payload: serde_json::json!({
                        "endpoint": endpoint,
                        "stage": "wecom_long_conn",
                    }),
                    ts: chrono::Utc::now().timestamp_millis(),
                });

                // 1. WSS 握手（8s 超时，可被 cancel 中断）
                let request = match Self::build_ws_request(&endpoint) {
                    Ok(r) => r,
                    Err(e) => {
                        // 构造请求失败：不可恢复，直接退出
                        if let Some(tx0) = first_result_tx_opt.take() {
                            let _ = tx0.send(Err(e.clone()));
                        }
                        let _ = tx.send(IMAdapterEvent {
                            binding_id: binding_id.clone(),
                            kind: "error".into(),
                            payload: serde_json::json!({
                                "stage": "ws_request_build",
                                "error": e,
                            }),
                            ts: chrono::Utc::now().timestamp_millis(),
                        });
                        let mut g = out_tx_holder.lock().await;
                        *g = None;
                        return;
                    }
                };

                let connect_result: Result<_, String> = tokio::select! {
                    biased;
                    _ = cancel_notify.notified() => {
                        if let Some(tx0) = first_result_tx_opt.take() {
                            let _ = tx0.send(Err("cancelled by disconnect".into()));
                        }
                        if !disconnected_announced {
                            let _ = tx.send(IMAdapterEvent {
                                binding_id: binding_id.clone(),
                                kind: "disconnected".into(),
                                payload: serde_json::Value::Null,
                                ts: chrono::Utc::now().timestamp_millis(),
                            });
                        }
                        return;
                    }
                    r = tokio::time::timeout(Duration::from_secs(8), tokio_tungstenite::connect_async(request)) => {
                        match r {
                            Ok(Ok((s, _))) => Ok(s),
                            Ok(Err(e)) => {
                                let msg = e.to_string();
                                Err(msg)
                            }
                            Err(_) => Err("wecom wss connect timeout after 8s".to_string()),
                        }
                    }
                };

                let ws_stream = match connect_result {
                    Ok(s) => s,
                    Err(err_msg) => {
                        // 检测 HTTP 401/403 鉴权失败（WSS 握手层）
                        let is_auth = err_msg.contains(" 401 ")
                            || err_msg.contains(" 403 ")
                            || err_msg.starts_with("HTTP error: 401")
                            || err_msg.starts_with("HTTP error: 403");
                        if is_auth {
                            let _ = tx.send(IMAdapterEvent {
                                binding_id: binding_id.clone(),
                                kind: "auth_error".into(),
                                payload: serde_json::json!({
                                    "stage": "wecom_wss_handshake",
                                    "error": err_msg,
                                    "hint": "check bot_id/secret or network",
                                }),
                                ts: chrono::Utc::now().timestamp_millis(),
                            });
                            if !first_reported {
                                if let Some(tx0) = first_result_tx_opt.take() {
                                    let _ = tx0.send(Err(err_msg.clone()));
                                }
                                let mut g = out_tx_holder.lock().await;
                                *g = None;
                                return;
                            }
                        }
                        consecutive_failures = consecutive_failures.saturating_add(1);
                        let _ = tx.send(IMAdapterEvent {
                            binding_id: binding_id.clone(),
                            kind: "error".into(),
                            payload: serde_json::json!({
                                "stage": "wecom_wss_handshake",
                                "error": err_msg,
                                "consecutive_failures": consecutive_failures,
                            }),
                            ts: chrono::Utc::now().timestamp_millis(),
                        });
                        if !first_reported {
                            if let Some(tx0) = first_result_tx_opt.take() {
                                let _ = tx0.send(Err(err_msg.clone()));
                            }
                            let mut g = out_tx_holder.lock().await;
                            *g = None;
                            return;
                        }
                        if consecutive_failures >= CIRCUIT_BREAKER_THRESHOLD {
                            tracing::warn!(
                                "[wecom] channel {} circuit breaker tripped after {} \
                                 consecutive failures (last: {}), exiting reconnect loop.",
                                binding_id, consecutive_failures, err_msg
                            );
                            let _ = tx.send(IMAdapterEvent {
                                binding_id: binding_id.clone(),
                                kind: "circuit_breaker".into(),
                                payload: serde_json::json!({
                                    "consecutive_failures": consecutive_failures,
                                    "last_error": err_msg,
                                    "cooldown_secs": CIRCUIT_BREAKER_COOLDOWN_SECS,
                                }),
                                ts: chrono::Utc::now().timestamp_millis(),
                            });
                            // 清空 out_tx：让后续 send() 立即失败触发 adapter 重建
                            {
                                let mut g = out_tx_holder.lock().await;
                                *g = None;
                            }
                            if !disconnected_announced {
                                let _ = tx.send(IMAdapterEvent {
                                    binding_id: binding_id.clone(),
                                    kind: "disconnected".into(),
                                    payload: serde_json::Value::Null,
                                    ts: chrono::Utc::now().timestamp_millis(),
                                });
                            }
                            return;
                        }
                        // 退避后重试（加 jitter + 可被 cancel 中断）
                        tokio::select! {
                            biased;
                            _ = cancel_notify.notified() => {
                                if !disconnected_announced {
                                    let _ = tx.send(IMAdapterEvent {
                                        binding_id: binding_id.clone(),
                                        kind: "disconnected".into(),
                                        payload: serde_json::Value::Null,
                                        ts: chrono::Utc::now().timestamp_millis(),
                                    });
                                }
                                return;
                            }
                            _ = tokio::time::sleep(jittered_backoff(backoff)) => {}
                        }
                        backoff = (backoff * 2).min(reconnect_max);
                        continue;
                    }
                };

                // 2. WSS 握手成功 → 发送 aibot_subscribe 鉴权帧
                let (mut write, mut read) = ws_stream.split();

                let subscribe_frame = match Self::build_subscribe_frame(&bot_id, &bot_secret) {
                    Ok(f) => f,
                    Err(e) => {
                        if !first_reported {
                            if let Some(tx0) = first_result_tx_opt.take() {
                                let _ = tx0.send(Err(e.clone()));
                            }
                        }
                        let _ = tx.send(IMAdapterEvent {
                            binding_id: binding_id.clone(),
                            kind: "error".into(),
                            payload: serde_json::json!({
                                "stage": "subscribe_frame_build",
                                "error": e,
                            }),
                            ts: chrono::Utc::now().timestamp_millis(),
                        });
                        let _ = write.close().await;
                        let mut g = out_tx_holder.lock().await;
                        *g = None;
                        return;
                    }
                };

                if let Err(e) = write.send(subscribe_frame).await {
                    let err_msg = format!("send subscribe frame failed: {}", e);
                    consecutive_failures = consecutive_failures.saturating_add(1);
                    let _ = tx.send(IMAdapterEvent {
                        binding_id: binding_id.clone(),
                        kind: "error".into(),
                        payload: serde_json::json!({
                            "stage": "subscribe_send",
                            "error": err_msg,
                            "consecutive_failures": consecutive_failures,
                        }),
                        ts: chrono::Utc::now().timestamp_millis(),
                    });
                    if !first_reported {
                        if let Some(tx0) = first_result_tx_opt.take() {
                            let _ = tx0.send(Err(err_msg.clone()));
                        }
                        let mut g = out_tx_holder.lock().await;
                        *g = None;
                        return;
                    }
                    let _ = write.close().await;
                    tokio::select! {
                        biased;
                        _ = cancel_notify.notified() => {
                            if !disconnected_announced {
                                let _ = tx.send(IMAdapterEvent {
                                    binding_id: binding_id.clone(),
                                    kind: "disconnected".into(),
                                    payload: serde_json::Value::Null,
                                    ts: chrono::Utc::now().timestamp_millis(),
                                });
                            }
                            return;
                        }
                        _ = tokio::time::sleep(jittered_backoff(backoff)) => {}
                    }
                    backoff = (backoff * 2).min(reconnect_max);
                    continue;
                }

                // 3. 等待 aibot_subscribe 响应（带超时，可被 cancel 中断）
                //    期望响应：{"headers":{"req_id":"..."},"errcode":0,"errmsg":"ok"}
                let subscribe_result = tokio::select! {
                    biased;
                    _ = cancel_notify.notified() => {
                        if let Some(tx0) = first_result_tx_opt.take() {
                            let _ = tx0.send(Err("cancelled by disconnect".into()));
                        }
                        if !disconnected_announced {
                            let _ = tx.send(IMAdapterEvent {
                                binding_id: binding_id.clone(),
                                kind: "disconnected".into(),
                                payload: serde_json::Value::Null,
                                ts: chrono::Utc::now().timestamp_millis(),
                            });
                        }
                        return;
                    }
                    r = tokio::time::timeout(subscribe_timeout, read.next()) => {
                        match r {
                            Ok(Some(Ok(WsMessage::Text(text)))) => {
                                match serde_json::from_str::<serde_json::Value>(&text) {
                                    Ok(v) => {
                                        let errcode = v.get("errcode").and_then(|x| x.as_i64()).unwrap_or(-1);
                                        if errcode == 0 {
                                            Ok(())
                                        } else {
                                            let errmsg = v.get("errmsg").and_then(|x| x.as_str()).unwrap_or("unknown");
                                            Err(format!("aibot_subscribe failed: errcode={} errmsg={}", errcode, errmsg))
                                        }
                                    }
                                    Err(_) => Err(format!(
                                        "aibot_subscribe response parse failed: {}",
                                        text.chars().take(200).collect::<String>()
                                    )),
                                }
                            }
                            Ok(Some(Ok(_))) => Err("aibot_subscribe expected text response, got non-text".to_string()),
                            Ok(Some(Err(e))) => Err(format!("aibot_subscribe response read error: {}", e)),
                            Ok(None) => Err("aibot_subscribe response: stream closed by peer".to_string()),
                            Err(_) => Err(format!(
                                "aibot_subscribe response timeout after {}ms",
                                subscribe_timeout.as_millis()
                            )),
                        }
                    }
                };

                if let Err(err_msg) = subscribe_result {
                    // 鉴权失败（errcode != 0）：视为凭据失效，不重试（避免被服务端限流）
                    let is_auth = err_msg.contains("aibot_subscribe failed")
                        || err_msg.contains("errcode=");
                    if is_auth {
                        let _ = tx.send(IMAdapterEvent {
                            binding_id: binding_id.clone(),
                            kind: "auth_error".into(),
                            payload: serde_json::json!({
                                "stage": "aibot_subscribe",
                                "error": err_msg,
                                "hint": "check bot_id and secret",
                            }),
                            ts: chrono::Utc::now().timestamp_millis(),
                        });
                        if !first_reported {
                            if let Some(tx0) = first_result_tx_opt.take() {
                                let _ = tx0.send(Err(err_msg.clone()));
                            }
                            let _ = write.close().await;
                            let mut g = out_tx_holder.lock().await;
                            *g = None;
                            return;
                        }
                        // 后续鉴权失败也直接退出，不重试
                        let _ = write.close().await;
                        let mut g = out_tx_holder.lock().await;
                        *g = None;
                        if !disconnected_announced {
                            let _ = tx.send(IMAdapterEvent {
                                binding_id: binding_id.clone(),
                                kind: "disconnected".into(),
                                payload: serde_json::Value::Null,
                                ts: chrono::Utc::now().timestamp_millis(),
                            });
                        }
                        return;
                    }
                    // 其他错误（网络超时、流断开等）：计入 circuit breaker 重试
                    consecutive_failures = consecutive_failures.saturating_add(1);
                    let _ = tx.send(IMAdapterEvent {
                        binding_id: binding_id.clone(),
                        kind: "error".into(),
                        payload: serde_json::json!({
                            "stage": "aibot_subscribe",
                            "error": err_msg,
                            "consecutive_failures": consecutive_failures,
                        }),
                        ts: chrono::Utc::now().timestamp_millis(),
                    });
                    if !first_reported {
                        if let Some(tx0) = first_result_tx_opt.take() {
                            let _ = tx0.send(Err(err_msg.clone()));
                        }
                        let _ = write.close().await;
                        let mut g = out_tx_holder.lock().await;
                        *g = None;
                        return;
                    }
                    if consecutive_failures >= CIRCUIT_BREAKER_THRESHOLD {
                        let _ = tx.send(IMAdapterEvent {
                            binding_id: binding_id.clone(),
                            kind: "circuit_breaker".into(),
                            payload: serde_json::json!({
                                "consecutive_failures": consecutive_failures,
                                "last_error": err_msg,
                                "cooldown_secs": CIRCUIT_BREAKER_COOLDOWN_SECS,
                            }),
                            ts: chrono::Utc::now().timestamp_millis(),
                        });
                        let _ = write.close().await;
                        {
                            let mut g = out_tx_holder.lock().await;
                            *g = None;
                        }
                        if !disconnected_announced {
                            let _ = tx.send(IMAdapterEvent {
                                binding_id: binding_id.clone(),
                                kind: "disconnected".into(),
                                payload: serde_json::Value::Null,
                                ts: chrono::Utc::now().timestamp_millis(),
                            });
                        }
                        return;
                    }
                    let _ = write.close().await;
                    tokio::select! {
                        biased;
                        _ = cancel_notify.notified() => {
                            if !disconnected_announced {
                                let _ = tx.send(IMAdapterEvent {
                                    binding_id: binding_id.clone(),
                                    kind: "disconnected".into(),
                                    payload: serde_json::Value::Null,
                                    ts: chrono::Utc::now().timestamp_millis(),
                                });
                            }
                            return;
                        }
                        _ = tokio::time::sleep(jittered_backoff(backoff)) => {}
                    }
                    backoff = (backoff * 2).min(reconnect_max);
                    continue;
                }

                // 4. 鉴权成功 → 重置退避 + 进入主循环
                backoff = reconnect_base;
                consecutive_failures = 0;

                let _ = tx.send(IMAdapterEvent {
                    binding_id: binding_id.clone(),
                    kind: "connected".into(),
                    payload: serde_json::json!({ "endpoint": endpoint }),
                    ts: chrono::Utc::now().timestamp_millis(),
                });

                if !first_reported {
                    first_reported = true;
                    if let Some(tx0) = first_result_tx_opt.take() {
                        let _ = tx0.send(Ok(()));
                    }
                }

                let mut tick = interval(heartbeat);
                tick.tick().await; // 立即 tick 一次跳过

                // 企微心跳：发送文本帧 "ping"，服务端回文本帧 "pong"
                let ping_frame = WsMessage::Text("ping".to_string());
                let mut kicked_by_new_connection = false;

                loop {
                    if cancel.load(Ordering::Acquire)
                        || generation.load(Ordering::Acquire) != my_gen
                    {
                        if !disconnected_announced {
                            let _ = tx.send(IMAdapterEvent {
                                binding_id: binding_id.clone(),
                                kind: "disconnected".into(),
                                payload: serde_json::Value::Null,
                                ts: chrono::Utc::now().timestamp_millis(),
                            });
                        }
                        return;
                    }
                    tokio::select! {
                        biased;
                        _ = cancel_notify.notified() => {
                            if !disconnected_announced {
                                let _ = tx.send(IMAdapterEvent {
                                    binding_id: binding_id.clone(),
                                    kind: "disconnected".into(),
                                    payload: serde_json::Value::Null,
                                    ts: chrono::Utc::now().timestamp_millis(),
                                });
                            }
                            return;
                        }
                        // 1) 出站队列
                        Some(msg) = out_rx.recv() => {
                            if let Err(e) = write.send(msg).await {
                                let _ = tx.send(IMAdapterEvent {
                                    binding_id: binding_id.clone(),
                                    kind: "error".into(),
                                    payload: serde_json::json!({ "stage": "send", "error": e.to_string() }),
                                    ts: chrono::Utc::now().timestamp_millis(),
                                });
                                break;
                            }
                        }
                        // 2) 心跳 ping（30s）
                        _ = tick.tick() => {
                            if let Err(e) = write.send(ping_frame.clone()).await {
                                let _ = tx.send(IMAdapterEvent {
                                    binding_id: binding_id.clone(),
                                    kind: "error".into(),
                                    payload: serde_json::json!({ "stage": "ping", "error": e.to_string() }),
                                    ts: chrono::Utc::now().timestamp_millis(),
                                });
                                break;
                            }
                        }
                        // 3) 入站消息
                        ws_msg = read.next() => {
                            match ws_msg {
                                Some(Ok(WsMessage::Text(text))) => {
                                    // 1) "pong" → 心跳响应，忽略
                                    if text == "pong" {
                                        continue;
                                    }
                                    // 2) JSON: aibot_msg_callback / aibot_event_callback / 响应
                                    let parsed: serde_json::Value = match serde_json::from_str(&text) {
                                        Ok(v) => v,
                                        Err(_) => {
                                            // 非 JSON 文本帧：作为 raw message 转发（便于调试）
                                            let _ = tx.send(IMAdapterEvent {
                                                binding_id: binding_id.clone(),
                                                kind: "message".into(),
                                                payload: serde_json::json!({
                                                    "raw": text,
                                                    "stage": "wecom_unknown_text",
                                                }),
                                                ts: chrono::Utc::now().timestamp_millis(),
                                            });
                                            continue;
                                        }
                                    };
                                    let cmd = parsed.get("cmd").and_then(|v| v.as_str()).unwrap_or("");
                                    match cmd {
                                        "aibot_msg_callback" => {
                                            // 消息回调：转发 body 给订阅者
                                            let body = parsed.get("body").cloned().unwrap_or(serde_json::Value::Null);
                                            let _ = tx.send(IMAdapterEvent {
                                                binding_id: binding_id.clone(),
                                                kind: "message".into(),
                                                payload: body,
                                                ts: chrono::Utc::now().timestamp_millis(),
                                            });
                                        }
                                        "aibot_event_callback" => {
                                            // 事件回调：检查是否为 disconnected_event
                                            let event_type = parsed
                                                .get("body")
                                                .and_then(|b| b.get("event"))
                                                .and_then(|e| e.get("eventtype"))
                                                .and_then(|v| v.as_str())
                                                .unwrap_or("");
                                            if event_type == "disconnected_event" {
                                                // 另一个连接已接管本连接，本机应退出（不重连）
                                                tracing::warn!(
                                                    "[wecom] channel {} received disconnected_event: \
                                                     another connection took over, exiting reconnect loop",
                                                    binding_id
                                                );
                                                let _ = tx.send(IMAdapterEvent {
                                                    binding_id: binding_id.clone(),
                                                    kind: "kicked".into(),
                                                    payload: serde_json::json!({
                                                        "reason": "disconnected_event",
                                                        "hint": "another connection took over this bot",
                                                    }),
                                                    ts: chrono::Utc::now().timestamp_millis(),
                                                });
                                                kicked_by_new_connection = true;
                                                break;
                                            }
                                            // 其他事件（enter_chat / template_card_event / feedback_event）：
                                            // 转发为 event 事件
                                            let body = parsed.get("body").cloned().unwrap_or(serde_json::Value::Null);
                                            let _ = tx.send(IMAdapterEvent {
                                                binding_id: binding_id.clone(),
                                                kind: "event".into(),
                                                payload: body,
                                                ts: chrono::Utc::now().timestamp_millis(),
                                            });
                                        }
                                        "" => {
                                            // 无 cmd 字段：可能是 aibot_send_msg / aibot_respond_msg 的
                                            // 异步响应（{"headers":{"req_id":"..."},"errcode":0,"errmsg":"ok"}）
                                            let _ = tx.send(IMAdapterEvent {
                                                binding_id: binding_id.clone(),
                                                kind: "response".into(),
                                                payload: parsed,
                                                ts: chrono::Utc::now().timestamp_millis(),
                                            });
                                        }
                                        _ => {
                                            // 其他命令响应：转发为 response 事件
                                            let _ = tx.send(IMAdapterEvent {
                                                binding_id: binding_id.clone(),
                                                kind: "response".into(),
                                                payload: parsed,
                                                ts: chrono::Utc::now().timestamp_millis(),
                                            });
                                        }
                                    }
                                }
                                Some(Ok(WsMessage::Binary(bin))) => {
                                    // 企微协议通常不使用 binary 帧，兜底解析为 UTF-8 文本
                                    if let Ok(text) = std::str::from_utf8(&bin) {
                                        let _ = tx.send(IMAdapterEvent {
                                            binding_id: binding_id.clone(),
                                            kind: "message".into(),
                                            payload: serde_json::json!({
                                                "raw": text,
                                                "stage": "wecom_binary",
                                            }),
                                            ts: chrono::Utc::now().timestamp_millis(),
                                        });
                                    }
                                }
                                Some(Ok(WsMessage::Ping(p))) => {
                                    // 协议层 ping → 回 pong
                                    let _ = write.send(WsMessage::Pong(p)).await;
                                }
                                Some(Ok(WsMessage::Close(_))) | None => {
                                    tracing::info!("[wecom] ws closed by peer");
                                    break;
                                }
                                Some(Ok(_)) => { /* Pong/Frame 忽略 */ }
                                Some(Err(e)) => {
                                    let _ = tx.send(IMAdapterEvent {
                                        binding_id: binding_id.clone(),
                                        kind: "error".into(),
                                        payload: serde_json::json!({ "stage": "recv", "error": e.to_string() }),
                                        ts: chrono::Utc::now().timestamp_millis(),
                                    });
                                    break;
                                }
                            }
                        }
                    }
                }

                // 内层 loop 退出
                // 若被新连接踢掉：直接退出，不重连（避免与对端互踢死循环）
                if kicked_by_new_connection {
                    if !disconnected_announced {
                        let _ = tx.send(IMAdapterEvent {
                            binding_id: binding_id.clone(),
                            kind: "disconnected".into(),
                            payload: serde_json::json!({
                                "reason": "kicked_by_new_connection"
                            }),
                            ts: chrono::Utc::now().timestamp_millis(),
                        });
                    }
                    let mut g = out_tx_holder.lock().await;
                    *g = None;
                    return;
                }

                // 检查 cancel / 代际
                if cancel.load(Ordering::Acquire)
                    || generation.load(Ordering::Acquire) != my_gen
                {
                    if !disconnected_announced {
                        let _ = tx.send(IMAdapterEvent {
                            binding_id: binding_id.clone(),
                            kind: "disconnected".into(),
                            payload: serde_json::Value::Null,
                            ts: chrono::Utc::now().timestamp_millis(),
                        });
                    }
                    return;
                }
                // 退避后重连（加 jitter + 可被 cancel 中断）
                tokio::select! {
                    biased;
                    _ = cancel_notify.notified() => {
                        if !disconnected_announced {
                            let _ = tx.send(IMAdapterEvent {
                                binding_id: binding_id.clone(),
                                kind: "disconnected".into(),
                                payload: serde_json::Value::Null,
                                ts: chrono::Utc::now().timestamp_millis(),
                            });
                        }
                        return;
                    }
                    _ = tokio::time::sleep(jittered_backoff(backoff)) => {}
                }
                // 进入下一轮外层 loop（重连）
            }
        });

        match first_result_rx.await {
            Ok(r) => r,
            Err(_) => Err("wecom connect task dropped".to_string()),
        }
    }

    async fn disconnect(&self) -> Result<(), String> {
        self.cancel.store(true, Ordering::Release);
        self.cancel_notify.notify_waiters();
        let mut g = self.out_tx.lock().await;
        *g = None;
        Ok(())
    }

    async fn send(&self, target: &str, content: &str) -> Result<String, String> {
        let g = self.out_tx.lock().await;
        let tx = g
            .as_ref()
            .ok_or_else(|| "wecom long-conn not connected".to_string())?;

        // target 格式约定（与 im_bridge 上层路由对齐）：
        //   "group:<chatid>"  → 群聊（body.chatid = <chatid>）
        //   "user:<userid>"   → 单聊（body.from.userid = <userid>）
        //   其他              → 默认当作群聊 chatid
        let (chatid, userid) = if let Some(rest) = target.strip_prefix("group:") {
            (Some(rest.to_string()), None)
        } else if let Some(rest) = target.strip_prefix("user:") {
            (None, Some(rest.to_string()))
        } else {
            (Some(target.to_string()), None)
        };

        let req_id = uuid::Uuid::new_v4().to_string();
        let mut body = serde_json::json!({
            "msgtype": "text",
            "text": { "content": content }
        });
        if let Some(c) = chatid {
            body["chatid"] = serde_json::Value::String(c);
        }
        if let Some(u) = userid {
            body["from"] = serde_json::json!({ "userid": u });
        }

        let frame = serde_json::json!({
            "cmd": "aibot_send_msg",
            "headers": { "req_id": req_id },
            "body": body
        });
        let text = serde_json::to_string(&frame).map_err(|e| format!("encode: {}", e))?;
        tx.send(WsMessage::Text(text))
            .map_err(|e| format!("enqueue: {}", e))?;
        Ok("queued".to_string())
    }

    fn subscribe(&self) -> broadcast::Receiver<IMAdapterEvent> {
        self.tx.subscribe()
    }
}

pub type SharedWecomAdapter = Arc<WecomAdapter>;

/// 给退避时长加上 0~25% 的随机抖动，避免多个客户端在服务端恢复瞬间
/// 同时重连造成惊群效应（Thundering Herd）。抄自 websocket_adapter.rs。
fn jittered_backoff(base: Duration) -> Duration {
    let mut rng = rand::thread_rng();
    let jitter_ms = rng.gen_range(0..=base.as_millis() as u64 / 4);
    base + Duration::from_millis(jitter_ms)
}

/// 从 `wss://host:port/path` 中提取 `host:port` 作为 HTTP `Host` 头。
/// 抄自 websocket_adapter.rs::url_host。
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn url_host_extracts_authority() {
        assert_eq!(
            url_host("wss://openws.work.weixin.qq.com"),
            Some("openws.work.weixin.qq.com".into())
        );
        assert_eq!(
            url_host("wss://openws.work.weixin.qq.com:8443/path?q=1"),
            Some("openws.work.weixin.qq.com:8443".into())
        );
        assert_eq!(url_host("ws://127.0.0.1:9000/"), Some("127.0.0.1:9000".into()));
        assert_eq!(url_host("http://nope"), None);
        assert_eq!(url_host(""), None);
        assert_eq!(url_host("wss://"), None);
    }

    #[test]
    fn jittered_backoff_within_bounds() {
        let base = Duration::from_secs(10);
        for _ in 0..100 {
            let jb = jittered_backoff(base);
            assert!(jb >= base, "jittered backoff below base");
            assert!(jb <= base + Duration::from_millis(2500), "jittered backoff above 125%");
        }
    }

    #[test]
    fn bot_id_extracts_from_bot_id_field() {
        let binding = IMBinding {
            id: "test".into(),
            provider: "wecom".into(),
            channel_id: "ch".into(),
            metadata: serde_json::json!({ "bot_id": "BOT123", "app_secret": "SEC456" }),
        };
        let adapter = WecomAdapter::new(binding);
        assert_eq!(adapter.bot_id(), Some("BOT123".into()));
        assert_eq!(adapter.bot_secret(), Some("SEC456".into()));
    }

    #[test]
    fn bot_id_extracts_from_secret_colon_format() {
        let binding = IMBinding {
            id: "test".into(),
            provider: "wecom".into(),
            channel_id: "ch".into(),
            metadata: serde_json::json!({ "secret": "BOT123:SEC456" }),
        };
        let adapter = WecomAdapter::new(binding);
        assert_eq!(adapter.bot_id(), Some("BOT123".into()));
        assert_eq!(adapter.bot_secret(), Some("SEC456".into()));
    }

    #[test]
    fn bot_id_extracts_from_bot_id_plus_plain_secret() {
        let binding = IMBinding {
            id: "test".into(),
            provider: "wecom".into(),
            channel_id: "ch".into(),
            metadata: serde_json::json!({ "bot_id": "BOT123", "secret": "SEC456" }),
        };
        let adapter = WecomAdapter::new(binding);
        assert_eq!(adapter.bot_id(), Some("BOT123".into()));
        assert_eq!(adapter.bot_secret(), Some("SEC456".into()));
    }

    #[test]
    fn build_subscribe_frame_is_valid_json() {
        let frame = WecomAdapter::build_subscribe_frame("BOT123", "SEC456").unwrap();
        match frame {
            WsMessage::Text(t) => {
                let v: serde_json::Value = serde_json::from_str(&t).unwrap();
                assert_eq!(v.get("cmd").and_then(|x| x.as_str()), Some("aibot_subscribe"));
                assert_eq!(
                    v.get("body").and_then(|b| b.get("bot_id")).and_then(|x| x.as_str()),
                    Some("BOT123")
                );
                assert_eq!(
                    v.get("body").and_then(|b| b.get("secret")).and_then(|x| x.as_str()),
                    Some("SEC456")
                );
                assert!(v.get("headers").and_then(|h| h.get("req_id")).is_some());
            }
            _ => panic!("expected Text frame"),
        }
    }
}
