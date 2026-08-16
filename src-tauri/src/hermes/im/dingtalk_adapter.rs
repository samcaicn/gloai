// Copyright (c) 2026 MeeJoy
//
// 钉钉 Stream 模式直连长连接适配器。完全抄自
// open-dingtalk/dingtalk-stream-sdk-java (官方 SDK) 协议 + 钉钉官方文档
// (https://open.dingtalk.com/document/direction/stream-mode-protocol-access-description
//  与 https://opensource.dingtalk.com/developerpedia/docs/learn/stream/protocol/)。
//
// 协议步骤（官方协议，原文）：
//   步骤一：POST https://api.dingtalk.com/v1.0/gateway/connections/open
//           body: {
//             "clientId":     "${ClientID}",
//             "clientSecret": "${ClientSecret}",
//             "localIp":      "...",
//             "subscriptions":[
//               {"topic": "*",                          "type":"EVENT"},
//               {"topic":"/v1.0/im/bot/messages/get",   "type":"CALLBACK"}
//             ],
//             "ua":"tupai-im/1.0 (dingtalk stream)"
//           }
//           → 200 {"endpoint":"wss://wss-open-connection.dingtalk.com:443/connect",
//                  "ticket":"<90s 有效, 一次一连接>"}
//
//   步骤二：HTTP Upgrade
//           GET /connect?ticket=${ticket}
//           Host: wss-open-connection.dingtalk.com:443
//           → 101 Switching Protocols
//
//   数据帧（JSON text frame）：
//     {
//       "specVersion":"1.0",
//       "type":"EVENT" | "CALLBACK" | "SYSTEM",
//       "headers": {
//         "topic":     "/v1.0/im/bot/messages/get",
//         "messageId": "<uuid>",
//         "appId":     "<clientId>",
//         "time":      <epoch_ms>
//       },
//       "data": "<JSON string, 业务数据>"
//     }
//
//   ACK 帧（收到消息 3 秒内必须回；超时会被服务端重试）：
//     {
//       "code":   200,
//       "headers":{"messageId":"<原 messageId>"},
//       "message":"OK",
//       "data":   ""
//     }
//
// 【铁律】endpoint (api.dingtalk.com / wss-open-connection.dingtalk.com) 写死
// 在 `im_endpoints.rs::dingtalk_wss_url()` / `dingtalk_api_url()`。用户在
// 设置面板只填 ClientID + ClientSecret，无法填也无法绕过。

use async_trait::async_trait;
use futures_util::{SinkExt, StreamExt};
use serde::Deserialize;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::{broadcast, mpsc, oneshot, Mutex, Notify};
use tokio::time::{interval, timeout};
use tokio_tungstenite::tungstenite::handshake::client::{generate_key, Request};
use tokio_tungstenite::tungstenite::http::HeaderValue;
use tokio_tungstenite::tungstenite::Message as WsMessage;
use super::adapter_base::{IMAdapter, IMAdapterEvent, IMBinding, IMProvider};
use super::im_endpoints::ImChannelKind;

#[derive(Clone, Debug)]
pub struct DingTalkAdapterOptions {
    /// 客户端 → 服务端 ping 间隔。钉钉服务端 60s 没数据会断链。
    pub heartbeat_ms: u64,
    /// 单次 HTTP 注册 + WSS 握手超时。
    pub bootstrap_timeout_ms: u64,
    /// 重连基准。失败后翻倍，封顶 60s。
    pub reconnect_base_ms: u64,
    /// UA 标识（debug 用）
    pub ua: String,
}

impl Default for DingTalkAdapterOptions {
    fn default() -> Self {
        Self {
            heartbeat_ms: 30_000,        // 30s — 钉钉服务端 keepalive 比飞书更激进
            bootstrap_timeout_ms: 8_000, // 8s
            reconnect_base_ms: 5_000,    // 5s
            ua: "tupai-im/1.0 (dingtalk stream)".to_string(),
        }
    }
}

/// 钉钉 API 网关 (api.dingtalk.com) 写死的 endpoint。
pub const DINGTALK_API_BASE: &str = "https://api.dingtalk.com";

/// 钉钉动态注册流连接的接口路径 (Step 1)。
const DINGTALK_OPEN_CONNECTION_PATH: &str = "/v1.0/gateway/connections/open";

/// 钉钉 OAuth2 获取 access_token 接口路径。
const DINGTALK_OAUTH2_ACCESS_TOKEN_PATH: &str = "/v1.0/oauth2/accessToken";

/// 钉钉机器人单聊消息批量发送接口路径。
const DINGTALK_ROBOT_OTO_BATCH_SEND_PATH: &str = "/v1.0/robot/oToMessages/batchSend";

/// 钉钉 WebSocket 域名基址（仅作日志 / host 头使用；实际 endpoint 来自
/// step1 接口动态返回，路径固定为 `/connect`）。
pub const DINGTALK_WSS_BASE_HOST: &str = "wss-open-connection.dingtalk.com:443";

/// Step 1 响应 (抄自 dingtalk-stream-sdk-java app-stream-api)。
#[derive(Debug, Deserialize)]
struct OpenConnectionResponse {
    #[serde(default)]
    endpoint: String,
    #[serde(default)]
    ticket: String,
    /// 失败时存在
    #[serde(default)]
    code: Option<String>,
    #[serde(default)]
    message: Option<String>,
}

pub struct DingTalkAdapter {
    binding: IMBinding,
    provider: IMProvider,
    options: DingTalkAdapterOptions,
    kind: ImChannelKind,
    out_tx: Arc<Mutex<Option<mpsc::UnboundedSender<WsMessage>>>>,
    cancel: Arc<AtomicBool>,
    cancel_notify: Arc<Notify>,
    generation: Arc<AtomicU64>,
    tx: broadcast::Sender<IMAdapterEvent>,
    /// 钉钉 access_token 缓存（token + 过期时间）。提前 5 分钟刷新避免边界过期。
    token_cache: Arc<Mutex<Option<(String, Instant)>>>,
}

impl DingTalkAdapter {
    pub fn new(binding: IMBinding) -> Self {
        let kind = ImChannelKind::DingTalk;
        let provider = IMProvider::DingTalk {
            // endpoint 字段保留作为"目标 host"占位（im_config_set 会强制覆盖为
            // 动态 endpoint），实际连接 URL 来自 step1 响应。
            endpoint: format!("wss://{}", DINGTALK_WSS_BASE_HOST),
            secret: None,
        };
        Self::with_options(binding, DingTalkAdapterOptions::default(), kind, provider)
    }

    pub fn with_options(
        binding: IMBinding,
        options: DingTalkAdapterOptions,
        kind: ImChannelKind,
        provider: IMProvider,
    ) -> Self {
        let (tx, _) = broadcast::channel(256);
        Self {
            binding,
            provider,
            options,
            kind,
            out_tx: Arc::new(Mutex::new(None)),
            cancel: Arc::new(AtomicBool::new(false)),
            cancel_notify: Arc::new(Notify::new()),
            generation: Arc::new(AtomicU64::new(0)),
            tx,
            token_cache: Arc::new(Mutex::new(None)),
        }
    }

    /// 从 binding.metadata 拿 ClientID (旧称 AppKey)。
    ///
    /// 存储路径（与 FeishuAdapter 对齐）：
    ///   1. OAuth / 显式字段:metadata.client_id / app_id / app_key
    ///   2. 拼接格式:metadata.secret = "clientId:clientSecret" (单字段表单)
    ///   3. 兜底:channel_id 形如 "dingtalk-dingXXX" 时提取 "dingXXX"
    fn client_id(&self) -> Option<String> {
        // 1. 显式字段优先
        for key in &["client_id", "app_id", "app_key"] {
            if let Some(v) = self.binding.metadata.get(*key).and_then(|v| v.as_str()) {
                if !v.is_empty() {
                    return Some(v.to_string());
                }
            }
        }
        // 2. 拆分 metadata.secret "clientId:clientSecret" 格式
        if let Some(secret) = self.binding.metadata.get("secret").and_then(|v| v.as_str()) {
            if !secret.is_empty() {
                if let Some(idx) = secret.find(':') {
                    let cid = &secret[..idx];
                    if !cid.is_empty() {
                        return Some(cid.to_string());
                    }
                }
                // 无冒号且以 "ding" 开头:整个当作 client_id
                if secret.starts_with("ding") {
                    return Some(secret.to_string());
                }
            }
        }
        // 3. 兜底:从 channel_id 提取 "dingXXX"
        let cid = &self.binding.channel_id;
        cid.find("ding").map(|idx| cid[idx..].to_string())
    }

    /// 从 binding.metadata 拿 ClientSecret (旧称 AppSecret)。
    /// 优先显式字段,其次拆分 metadata.secret "clientId:clientSecret",
    /// 兼容无冒号的纯 secret 输入。
    fn client_secret(&self) -> Option<String> {
        // 1. 显式字段优先
        for key in &["client_secret", "app_secret"] {
            if let Some(v) = self.binding.metadata.get(*key).and_then(|v| v.as_str()) {
                if !v.is_empty() {
                    return Some(v.to_string());
                }
            }
        }
        // 2. 拆分 metadata.secret "clientId:clientSecret" 格式
        if let Some(secret) = self.binding.metadata.get("secret").and_then(|v| v.as_str()) {
            if !secret.is_empty() {
                if let Some(idx) = secret.find(':') {
                    let cs = &secret[idx + 1..];
                    if !cs.is_empty() {
                        return Some(cs.to_string());
                    }
                }
                // 无冒号且不以 "ding" 开头:整个当作 client_secret
                if !secret.starts_with("ding") {
                    return Some(secret.to_string());
                }
            }
        }
        None
    }

    /// 获取钉钉 access_token（带缓存，过期前 5 分钟自动刷新）。
    ///
    /// 钉钉 API：POST {api_base}/v1.0/oauth2/accessToken
    ///   body: {"appKey":"dingXXX","appSecret":"xxx"}
    ///   resp: {"accessToken":"...","expireIn":7200}
    /// token 有效期 2 小时（7200s），提前 5 分钟刷新避免边界过期。
    async fn get_access_token(&self) -> Result<String, String> {
        // 1. 检查缓存（剩余有效期 > 5 分钟）
        {
            let cache = self.token_cache.lock().await;
            if let Some((token, expires_at)) = cache.as_ref() {
                let valid = expires_at
                    .checked_duration_since(Instant::now())
                    .map(|d| d > Duration::from_secs(300))
                    .unwrap_or(false);
                if valid {
                    return Ok(token.clone());
                }
            }
        }

        // 2. 缓存失效，重新获取
        let client_id = self
            .client_id()
            .ok_or_else(|| "dingtalk client_id missing".to_string())?;
        let client_secret = self
            .client_secret()
            .ok_or_else(|| "dingtalk client_secret missing".to_string())?;

        let url = format!("{}{}", DINGTALK_API_BASE, DINGTALK_OAUTH2_ACCESS_TOKEN_PATH);
        let body = serde_json::json!({
            "appKey": client_id,
            "appSecret": client_secret,
        });

        let http = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(5))
            .timeout(Duration::from_secs(10))
            .no_proxy()
            .user_agent(concat!("tupAI/", env!("CARGO_PKG_VERSION")))
            .build()
            .map_err(|e| format!("dingtalk access_token http client build: {}", e))?;

        let resp = http
            .post(&url)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .json(&body)
            .send()
            .await
            .map_err(|e| {
                format!(
                    "dingtalk access_token http: {}",
                    format_reqwest_error(&e)
                )
            })?;

        let status = resp.status();
        if status.as_u16() == 401 || status.as_u16() == 403 {
            return Err(format!(
                "dingtalk access_token auth failed ({}): invalid client_id or client_secret",
                status
            ));
        }
        if !status.is_success() {
            let body = resp.text().await.unwrap_or_default();
            return Err(format!(
                "dingtalk access_token http {}: {}",
                status,
                body.chars().take(200).collect::<String>()
            ));
        }

        let parsed: serde_json::Value = resp
            .json()
            .await
            .map_err(|e| format!("dingtalk access_token parse: {}", e))?;

        let token = parsed
            .get("accessToken")
            .and_then(|v| v.as_str())
            .ok_or_else(|| "dingtalk access_token missing in response".to_string())?
            .to_string();

        let expire_in = parsed
            .get("expireIn")
            .and_then(|v| v.as_u64())
            .unwrap_or(7200);

        // 提前 5 分钟刷新（expire - 300s），最小保留 60s
        let ttl = Duration::from_secs(expire_in.saturating_sub(300).max(60));
        let expires_at = Instant::now() + ttl;

        let mut cache = self.token_cache.lock().await;
        *cache = Some((token.clone(), expires_at));

        Ok(token)
    }

    /// 使缓存的 access_token 失效，强制下次获取时刷新。
    async fn invalidate_token(&self) {
        let mut cache = self.token_cache.lock().await;
        *cache = None;
    }

    /// Step 1: HTTP POST 拿动态 endpoint + ticket。抄自官方协议：
    ///   POST {api_base}/v1.0/gateway/connections/open
    ///   body: {"clientId","clientSecret","subscriptions","ua","localIp"}
    ///   → {"endpoint":"wss://...","ticket":"..."}
    #[allow(dead_code)]
    async fn fetch_ticket(
        &self,
        client_id: &str,
        client_secret: &str,
        bootstrap_timeout: Duration,
    ) -> Result<(String, String), String> {
        let url = format!("{}{}", DINGTALK_API_BASE, DINGTALK_OPEN_CONNECTION_PATH);
        let body = serde_json::json!({
            "clientId": client_id,
            "clientSecret": client_secret,
            "localIp": local_ip_placeholder(),
            "subscriptions": [
                { "topic": "*",                        "type": "EVENT" },
                { "topic": "/v1.0/im/bot/messages/get","type": "CALLBACK" },
            ],
            "ua": &self.options.ua,
        });

        let client = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(5))
            .timeout(bootstrap_timeout)
            .no_proxy()
            .user_agent(concat!("tupAI/", env!("CARGO_PKG_VERSION")))
            .build()
            .map_err(|e| format!("http client build: {}", e))?;

        let resp = client
            .post(&url)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", &self.options.ua)
            .json(&body)
            .send()
            .await
            .map_err(|e| format!("dingtalk open connection http: {}", format_reqwest_error(&e)))?;

        let status = resp.status();
        if status.as_u16() == 401 || status.as_u16() == 403 {
            return Err(format!(
                "dingtalk auth failed ({}): invalid client_id or client_secret",
                status
            ));
        }
        if !status.is_success() {
            let body = resp.text().await.unwrap_or_default();
            return Err(format!(
                "dingtalk open connection http {}: {}",
                status,
                body.chars().take(200).collect::<String>()
            ));
        }

        let parsed: OpenConnectionResponse = resp
            .json()
            .await
            .map_err(|e| format!("dingtalk open connection parse: {}", e))?;

        if let (Some(code), _) = (&parsed.code, parsed.endpoint.is_empty()) {
            // 业务错误（如 invalid credential）
            if code != "0" && !code.eq_ignore_ascii_case("OK") {
                return Err(format!(
                    "dingtalk open connection code={} message={}",
                    code,
                    parsed.message.clone().unwrap_or_default()
                ));
            }
        }
        if parsed.endpoint.is_empty() || parsed.ticket.is_empty() {
            return Err(format!(
                "dingtalk open connection missing endpoint/ticket: endpoint={} ticket_len={}",
                parsed.endpoint,
                parsed.ticket.len()
            ));
        }
        if !parsed.endpoint.starts_with("ws://") && !parsed.endpoint.starts_with("wss://") {
            return Err(format!(
                "dingtalk open connection returned non-ws endpoint: {}",
                parsed.endpoint
            ));
        }
        Ok((parsed.endpoint, parsed.ticket))
    }
}

// 关闭 `impl DingTalkAdapter` 块，下方为模块级 free functions

/// 构造带 ticket 的 WSS handshake request。
/// 抄自 Hermes-CN-Desktop `src/commands/ws_proxy.rs::build_gateway_ws_url_with_ticket`：
///   url = "{endpoint}?ticket={urlencoding::encode(ticket)}"
fn build_ws_request(endpoint: &str, ticket: &str) -> Result<Request, String> {
    let url = if endpoint.contains('?') {
        format!("{}&ticket={}", endpoint, urlencoding::encode(ticket))
    } else {
        format!("{}?ticket={}", endpoint, urlencoding::encode(ticket))
    };

    let host_header = url_host(&url);
    let mut request = Request::builder()
        .method("GET")
        .uri(&url)
        .header("Upgrade", "websocket")
        .header("Connection", "Upgrade")
        .header("Sec-WebSocket-Version", "13")
        .header("Sec-WebSocket-Key", generate_key())
        .header("User-Agent", "tupai-im/1.0 (dingtalk stream)")
        .body(())
        .map_err(|e| format!("ws request build failed: {}", e))?;
    if let Some(h) = host_header {
        if let Ok(hv) = HeaderValue::from_str(&h) {
            request.headers_mut().insert("Host", hv);
        }
    }
    Ok(request)
}

fn url_host(endpoint: &str) -> Option<String> {
    let after = endpoint.split("://").nth(1)?;
    let host = after.split('/').next()?;
    if host.is_empty() {
        None
    } else {
        Some(host.to_string())
    }
}

/// 格式化 reqwest 错误,遍历 source() 链拼接完整原因。
/// 抄自 im_oauth.rs / device_register.rs / feishu_adapter.rs。
fn format_reqwest_error(e: &reqwest::Error) -> String {
    use std::error::Error as _;
    let mut msg = format!("{}", e);
    let mut source = e.source();
    while let Some(s) = source {
        msg.push_str(" -> ");
        msg.push_str(&format!("{}", s));
        source = s.source();
    }
    msg
}

fn local_ip_placeholder() -> String {
    // 钉钉 SDK 这里填客户端 IP 方便定位问题；非必填，给个空串即可。
    String::new()
}

#[async_trait]
impl IMAdapter for DingTalkAdapter {
    fn provider(&self) -> &IMProvider {
        &self.provider
    }

    async fn connect(&self) -> Result<(), String> {
        let binding_id = self.binding.id.clone();
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

        let cancel = self.cancel.clone();
        let cancel_notify = self.cancel_notify.clone();
        let generation = self.generation.clone();
        let out_tx_holder = self.out_tx.clone();
        let tx = self.tx.clone();
        let heartbeat = Duration::from_millis(self.options.heartbeat_ms.max(5_000));
        let bootstrap_timeout = Duration::from_millis(self.options.bootstrap_timeout_ms);
        let reconnect_base = Duration::from_millis(self.options.reconnect_base_ms.max(500));
        let reconnect_max = Duration::from_secs(60);
        let kind = self.kind;
        let client_id = self.client_id();
        let client_secret = self.client_secret();
        let ua = self.options.ua.clone();

        tokio::spawn(async move {
            let mut first_reported = false;
            let mut first_result_tx_opt = Some(first_result_tx);
            let mut backoff = reconnect_base;
            let disconnected_announced = false;
            const CIRCUIT_BREAKER_THRESHOLD: u32 = 2;
            const CIRCUIT_BREAKER_COOLDOWN_SECS: u64 = 60;
            let mut consecutive_failures: u32 = 0;

            loop {
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
                        "kind": format!("{:?}", kind),
                        "stage": "dingtalk_stream",
                    }),
                    ts: chrono::Utc::now().timestamp_millis(),
                });

                // Step 1: HTTP POST 拿 endpoint + ticket
                let ticket_result = tokio::select! {
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
                    r = async {
                        if client_id.is_none() || client_secret.is_none() {
                            return Err("client_id/client_secret missing".to_string());
                        }
                        let url = format!("{}{}", DINGTALK_API_BASE, DINGTALK_OPEN_CONNECTION_PATH);
                        let body = serde_json::json!({
                            "clientId": client_id.as_deref().unwrap(),
                            "clientSecret": client_secret.as_deref().unwrap(),
                            "localIp": "",
                            "subscriptions": [
                                { "topic": "*",                          "type": "EVENT" },
                                { "topic": "/v1.0/im/bot/messages/get",  "type": "CALLBACK" },
                            ],
                            "ua": &ua,
                        });
                        let client = match reqwest::Client::builder()
                            .connect_timeout(Duration::from_secs(5))
                            .timeout(bootstrap_timeout)
                            .no_proxy()
                            .user_agent(concat!("tupAI/", env!("CARGO_PKG_VERSION")))
                            .build()
                        {
                            Ok(c) => c,
                            Err(e) => return Err(format!("http client build: {}", e)),
                        };
                        let resp = match client
                            .post(&url)
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json")
                            .header("User-Agent", &ua)
                            .json(&body)
                            .send().await
                        {
                            Ok(r) => r,
                            Err(e) => return Err(format!("dingtalk open http: {}", format_reqwest_error(&e))),
                        };
                        let status = resp.status();
                        if status.as_u16() == 401 || status.as_u16() == 403 {
                            return Err(format!(
                                "dingtalk auth failed ({}): invalid client_id or client_secret",
                                status
                            ));
                        }
                        if !status.is_success() {
                            let body = resp.text().await.unwrap_or_default();
                            return Err(format!(
                                "dingtalk open http {}: {}",
                                status,
                                body.chars().take(200).collect::<String>()
                            ));
                        }
                        let parsed: OpenConnectionResponse = match resp.json().await {
                            Ok(p) => p,
                            Err(e) => return Err(format!("dingtalk open parse: {}", e)),
                        };
                        if let Some(code) = &parsed.code {
                            if code != "0" && !code.eq_ignore_ascii_case("OK") {
                                return Err(format!(
                                    "dingtalk open code={} message={}",
                                    code,
                                    parsed.message.clone().unwrap_or_default()
                                ));
                            }
                        }
                        if parsed.endpoint.is_empty() || parsed.ticket.is_empty() {
                            return Err("dingtalk open missing endpoint/ticket".to_string());
                        }
                        if !parsed.endpoint.starts_with("ws://") && !parsed.endpoint.starts_with("wss://") {
                            return Err(format!("dingtalk open non-ws endpoint: {}", parsed.endpoint));
                        }
                        Ok((parsed.endpoint, parsed.ticket))
                    } => r,
                };

                let (endpoint, ticket) = match ticket_result {
                    Ok(v) => v,
                    Err(err_msg) => {
                        let is_auth = err_msg.contains("auth failed")
                            || err_msg.contains("invalid client_id")
                            || err_msg.contains("invalid client_secret");
                        if is_auth {
                            let _ = tx.send(IMAdapterEvent {
                                binding_id: binding_id.clone(),
                                kind: "auth_error".into(),
                                payload: serde_json::json!({
                                    "stage": "dingtalk_open",
                                    "error": err_msg,
                                    "hint": "token_invalid",
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
                                "stage": "dingtalk_open",
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
                        tokio::time::sleep(backoff).await;
                        backoff = (backoff * 2).min(reconnect_max);
                        continue;
                    }
                };

                // Step 2: WSS 握手 (带 ticket query)
                let request = match build_ws_request(&endpoint, &ticket) {
                    Ok(r) => r,
                    Err(e) => {
                        tracing::error!("[dingtalk] build ws request failed: {}", e);
                        if !first_reported {
                            if let Some(tx0) = first_result_tx_opt.take() {
                                let _ = tx0.send(Err(e));
                            }
                            let mut g = out_tx_holder.lock().await;
                            *g = None;
                            return;
                        }
                        tokio::time::sleep(backoff).await;
                        backoff = (backoff * 2).min(reconnect_max);
                        continue;
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
                            Ok(Ok((s, _resp))) => Ok(s),
                            Ok(Err(e)) => {
                                let msg = e.to_string();
                                let is_auth = msg.contains(" 401 ")
                                    || msg.contains(" 403 ")
                                    || msg.starts_with("HTTP error: 401")
                                    || msg.starts_with("HTTP error: 403");
                                if is_auth {
                                    Err(format!("dingtalk wss auth failed: {}", msg))
                                } else {
                                    Err(format!("dingtalk wss connect: {}", msg))
                                }
                            }
                            Err(_) => Err("dingtalk wss connect timeout after 8s".to_string()),
                        }
                    }
                };
                let ws_stream = match connect_result {
                    Ok(s) => s,
                    Err(err_msg) => {
                        let is_auth = err_msg.contains("auth failed");
                        if is_auth {
                            let _ = tx.send(IMAdapterEvent {
                                binding_id: binding_id.clone(),
                                kind: "auth_error".into(),
                                payload: serde_json::json!({
                                    "stage": "dingtalk_wss",
                                    "error": err_msg,
                                    "hint": "ticket_invalid_or_expired",
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
                                "stage": "dingtalk_wss",
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
                        tokio::time::sleep(backoff).await;
                        backoff = (backoff * 2).min(reconnect_max);
                        continue;
                    }
                };

                backoff = reconnect_base;
                consecutive_failures = 0;

                let _ = tx.send(IMAdapterEvent {
                    binding_id: binding_id.clone(),
                    kind: "connected".into(),
                    payload: serde_json::json!({
                        "endpoint": endpoint,
                        "ticket_prefix": ticket.chars().take(8).collect::<String>(),
                    }),
                    ts: chrono::Utc::now().timestamp_millis(),
                });

                if !first_reported {
                    first_reported = true;
                    if let Some(tx0) = first_result_tx_opt.take() {
                        let _ = tx0.send(Ok(()));
                    }
                }

                let (mut write, mut read) = ws_stream.split();
                let mut tick = interval(heartbeat);
                tick.tick().await; // 跳过立即 tick

                // 钉钉保活：发业务层 ping JSON frame
                // 抄自 dingtalk-stream-sdk-java app-stream-protocol UpstreamMessage.Ping
                let ping_frame = WsMessage::Text(
                    serde_json::json!({
                        "code":    200,
                        "headers": { "topic": "ping" },
                        "message": "OK",
                        "data":    ""
                    })
                    .to_string(),
                );

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
                        ws_msg = timeout(Duration::from_secs(90), read.next()) => {
                            // heartbeat 30s, 90s 无数据视为断链（略大于 heartbeat 2 倍）
                            let ws_msg = match ws_msg {
                                Ok(inner) => inner,
                                Err(_) => {
                                    tracing::warn!(
                                        "[dingtalk] ws read timeout after 90s, treating as disconnected"
                                    );
                                    let _ = tx.send(IMAdapterEvent {
                                        binding_id: binding_id.clone(),
                                        kind: "disconnected".into(),
                                        payload: serde_json::json!({
                                            "stage": "recv",
                                            "error": "read timeout after 90s"
                                        }),
                                        ts: chrono::Utc::now().timestamp_millis(),
                                    });
                                    break;
                                }
                            };
                            match ws_msg {
                                Some(Ok(WsMessage::Text(text))) => {
                                    // 解析钉钉 down frame
                                    // {"specVersion":"1.0","type":"CALLBACK",
                                    //  "headers":{"topic":"/v1.0/im/bot/messages/get","messageId":"..."},
                                    //  "data":"<json string>"}
                                    if let Ok(frame) = serde_json::from_str::<serde_json::Value>(&text) {
                                        let msg_type = frame.get("type").and_then(|v| v.as_str()).unwrap_or("");
                                        let topic = frame.get("headers")
                                            .and_then(|h| h.get("topic"))
                                            .and_then(|v| v.as_str())
                                            .unwrap_or("");
                                        let message_id = frame.get("headers")
                                            .and_then(|h| h.get("messageId"))
                                            .and_then(|v| v.as_str())
                                            .unwrap_or("")
                                            .to_string();

                                        // 1) 派发业务数据 (CALLBACK / EVENT)
                                        if msg_type == "CALLBACK" || msg_type == "EVENT" {
                                            // data 是 JSON 字符串，尝试解析
                                            let payload = if let Some(data_str) = frame.get("data").and_then(|v| v.as_str()) {
                                                serde_json::from_str::<serde_json::Value>(data_str)
                                                    .unwrap_or_else(|_| serde_json::Value::String(data_str.to_string()))
                                            } else {
                                                frame.get("data").cloned().unwrap_or(serde_json::Value::Null)
                                            };
                                            let _ = tx.send(IMAdapterEvent {
                                                binding_id: binding_id.clone(),
                                                kind: "message".into(),
                                                payload: serde_json::json!({
                                                    "topic": topic,
                                                    "message_id": message_id,
                                                    "type": msg_type,
                                                    "data": payload,
                                                }),
                                                ts: chrono::Utc::now().timestamp_millis(),
                                            });
                                        } else if msg_type == "SYSTEM" {
                                            // 系统消息透传
                                            let _ = tx.send(IMAdapterEvent {
                                                binding_id: binding_id.clone(),
                                                kind: "system".into(),
                                                payload: serde_json::json!({
                                                    "topic": topic,
                                                    "message_id": message_id,
                                                    "data": frame.get("data").cloned().unwrap_or(serde_json::Value::Null),
                                                }),
                                                ts: chrono::Utc::now().timestamp_millis(),
                                            });
                                        }

                                        // 2) ACK (3 秒内必须回) — 抄自 dingtalk-stream-sdk-java
                                        //    UpstreamMessage.Builder.buildResponse(messageId, 200, "OK")
                                        if !message_id.is_empty()
                                            && (msg_type == "CALLBACK" || msg_type == "EVENT")
                                        {
                                            let ack = WsMessage::Text(
                                                serde_json::json!({
                                                    "code":    200,
                                                    "headers":{ "messageId": message_id },
                                                    "message": "OK",
                                                    "data":    ""
                                                })
                                                .to_string(),
                                            );
                                            if let Err(e) = write.send(ack).await {
                                                tracing::warn!("[dingtalk] ack send failed: {}", e);
                                            }
                                        }
                                    }
                                }
                                Some(Ok(WsMessage::Binary(bin))) => {
                                    if let Ok(text) = std::str::from_utf8(&bin) {
                                        if let Ok(frame) = serde_json::from_str::<serde_json::Value>(text) {
                                            let msg_type = frame.get("type").and_then(|v| v.as_str()).unwrap_or("");
                                            let topic = frame.get("headers")
                                                .and_then(|h| h.get("topic"))
                                                .and_then(|v| v.as_str())
                                                .unwrap_or("");
                                            let message_id = frame.get("headers")
                                                .and_then(|h| h.get("messageId"))
                                                .and_then(|v| v.as_str())
                                                .unwrap_or("")
                                                .to_string();
                                            if msg_type == "CALLBACK" || msg_type == "EVENT" {
                                                let payload = if let Some(data_str) = frame.get("data").and_then(|v| v.as_str()) {
                                                    serde_json::from_str::<serde_json::Value>(data_str)
                                                        .unwrap_or_else(|_| serde_json::Value::String(data_str.to_string()))
                                                } else {
                                                    frame.get("data").cloned().unwrap_or(serde_json::Value::Null)
                                                };
                                                let _ = tx.send(IMAdapterEvent {
                                                    binding_id: binding_id.clone(),
                                                    kind: "message".into(),
                                                    payload: serde_json::json!({
                                                        "topic": topic,
                                                        "message_id": message_id,
                                                        "type": msg_type,
                                                        "data": payload,
                                                    }),
                                                    ts: chrono::Utc::now().timestamp_millis(),
                                                });
                                                if !message_id.is_empty() {
                                                    let ack = WsMessage::Text(
                                                        serde_json::json!({
                                                            "code":    200,
                                                            "headers":{ "messageId": message_id },
                                                            "message": "OK",
                                                            "data":    ""
                                                        })
                                                        .to_string(),
                                                    );
                                                    let _ = write.send(ack).await;
                                                }
                                            }
                                        }
                                    }
                                }
                                Some(Ok(WsMessage::Ping(p))) => {
                                    let _ = write.send(WsMessage::Pong(p)).await;
                                }
                                Some(Ok(WsMessage::Close(_))) | None => {
                                    tracing::info!("[dingtalk] ws closed by peer");
                                    break;
                                }
                                Some(Ok(_)) => { /* Pong / Frame 忽略 */ }
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
                // 进入下一轮外层 loop（重连，重新 fetch ticket）
            }
        });

        match first_result_rx.await {
            Ok(r) => r,
            Err(_) => Err("dingtalk connect task dropped".to_string()),
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
        // 钉钉发送消息走 REST：
        //   POST {api_base}/v1.0/robot/oToMessages/batchSend
        //   Header: x-acs-dingtalk-access-token: {token}
        //   Body: {"robotCode": clientId, "chatIds": [target], "msgKey": "sampleText",
        //          "msgParam": "{\"content\":\"...\"}"}
        // target 是 chat_id，content 是纯文本。
        // token 过期（401）时刷新后重试一次。
        let client_id = self
            .client_id()
            .ok_or_else(|| "dingtalk client_id missing".to_string())?;

        let http = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(5))
            .timeout(Duration::from_secs(15))
            .no_proxy()
            .user_agent(concat!("tupAI/", env!("CARGO_PKG_VERSION")))
            .build()
            .map_err(|e| format!("dingtalk send http client build: {}", e))?;

        let send_url = format!("{}{}", DINGTALK_API_BASE, DINGTALK_ROBOT_OTO_BATCH_SEND_PATH);
        // msgParam 是 JSON 字符串：{"content":"..."}，由 serde 自动转义
        let msg_param = serde_json::json!({ "content": content }).to_string();
        let body = serde_json::json!({
            "robotCode": client_id,
            "chatIds": [target],
            "msgKey": "sampleText",
            "msgParam": msg_param,
        });

        let mut token = self.get_access_token().await?;

        for attempt in 0..2u32 {
            let resp = http
                .post(&send_url)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("x-acs-dingtalk-access-token", &token)
                .json(&body)
                .send()
                .await
                .map_err(|e| format!("dingtalk send http: {}", format_reqwest_error(&e)))?;

            let status = resp.status();
            // 401 → token 过期，刷新后重试一次
            if status.as_u16() == 401 && attempt == 0 {
                tracing::warn!("[dingtalk] send got 401, refreshing access_token and retrying");
                self.invalidate_token().await;
                token = self.get_access_token().await?;
                continue;
            }

            if !status.is_success() {
                let body = resp.text().await.unwrap_or_default();
                return Err(format!(
                    "dingtalk send http {}: {}",
                    status,
                    body.chars().take(200).collect::<String>()
                ));
            }

            let body = resp.text().await.unwrap_or_default();
            return Ok(body);
        }

        Err("dingtalk send failed after token refresh retry".to_string())
    }

    fn subscribe(&self) -> broadcast::Receiver<IMAdapterEvent> {
        self.tx.subscribe()
    }
}

pub type SharedDingTalkAdapter = Arc<DingTalkAdapter>;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dingtalk_constants_match_official_sdk() {
        // 抄自 dingtalk-stream-sdk-java StreamClient.java
        //   - API base: api.dingtalk.com
        //   - Open connection path: /v1.0/gateway/connections/open
        //   - WSS host: wss-open-connection.dingtalk.com
        assert_eq!(DINGTALK_API_BASE, "https://api.dingtalk.com");
        assert_eq!(
            DINGTALK_OPEN_CONNECTION_PATH,
            "/v1.0/gateway/connections/open"
        );
        assert_eq!(DINGTALK_WSS_BASE_HOST, "wss-open-connection.dingtalk.com:443");
        assert_eq!(
            ImChannelKind::DingTalk.dingtalk_wss_url(),
            Some("wss://wss-open-connection.dingtalk.com/connect")
        );
    }

    #[test]
    fn url_encode_handles_uuid_ticket() {
        // urlencoding::encode 把 a/b/c 等保留字编码；uuid 由安全字符组成应原样
        let ticket = "7724109a-ea43-4aa2-b803-87d82c5aaee6";
        assert_eq!(urlencoding::encode(ticket).into_owned(), ticket);
        // 含 & = / 编码
        let encoded = urlencoding::encode("a&b=c").into_owned();
        assert!(encoded.contains("%26") || encoded.contains("&amp;"));
    }

    #[test]
    fn build_ws_request_appends_ticket() {
        let r = build_ws_request(
            "wss://wss-open-connection.dingtalk.com:443/connect",
            "abc-123",
        )
        .unwrap();
        let uri = r.uri().to_string();
        assert!(uri.contains("ticket=abc-123"), "uri was {}", uri);
        assert!(uri.contains("wss-open-connection.dingtalk.com"));
    }
}
