// Copyright (c) 2026 MeeJoy
//
// 飞书 / Lark 直连长连接适配器。完全抄自 openclaw @openclaw/feishu
// 插件 + larksuite/oapi-sdk-go v3/ws/client.go 源码：
//   - HTTP POST 引导接口拿动态 WSS URL
//   - WSS 升级到飞书服务器
//   - 服务端发 binary frame（method + headers + payload JSON）
//   - 客户端定时（2 分钟）发 ping frame 保活
//   - 数据 frame method="data" 且 headers.type="event" 时 payload 是事件 JSON
//
// 【铁律】endpoint URL 写死在 `im_endpoints.rs::feishu_bootstrap_url()`，
// 用户永远无法填、也无法通过任何 UI 绕过——这是从 `im_config_set`
// 强制覆盖层 (`commands/im_config.rs::override_provider_endpoint`) 写入的。
//
// 协议细节（参考 oapi-sdk-go）：
//   POST {bootstrap_url}
//     Content-Type: application/json
//     locale: zh
//     {"AppID":"cli_xxx","AppSecret":"xxx"}
//   → 200 {"code":0,"msg":"success","data":{"url":"wss://...?conn_id=...&service_id=..."}}
//   → 非 0 / HTTP 4xx-5xx → 凭据失效，不重试
//   然后 dial(data.url)，默认 gorilla/websocket 设置。
//   - 心跳：每 ping_interval (默认 120s) 发
//     {"method":"ping","headers":{"type":"ping"},"payload":{}}
//   - 收到 data frame (method="data", headers.type="event")，
//     payload 直接是 v1/v2 事件 JSON。

use async_trait::async_trait;
use futures_util::{SinkExt, StreamExt};
use serde::Deserialize;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::{broadcast, mpsc, oneshot, Mutex, Notify};
use tokio::time::interval;
use tokio_tungstenite::tungstenite::handshake::client::{generate_key, Request};
use tokio_tungstenite::tungstenite::http::HeaderValue;
use tokio_tungstenite::tungstenite::Message as WsMessage;

use super::adapter_base::{IMAdapter, IMAdapterEvent, IMBinding, IMProvider};
use super::im_endpoints::ImChannelKind;

#[derive(Clone, Debug)]
pub struct FeishuAdapterOptions {
    /// Ping 间隔。openclaw / oapi-sdk-go 默认 120s = 2 分钟。
    pub heartbeat_ms: u64,
    /// HTTP 引导接口 + WSS 握手单步超时。8s 足够在用户机器上完成
    /// `open.feishu.cn` 的 TLS 握手 + JSON 解析。
    pub bootstrap_timeout_ms: u64,
    /// 重连基准。失败后翻倍，封顶 60s。
    pub reconnect_base_ms: u64,
}

impl Default for FeishuAdapterOptions {
    fn default() -> Self {
        Self {
            heartbeat_ms: 120_000,        // 2 min — 抄 oapi-sdk-go
            bootstrap_timeout_ms: 8_000,  // 8s — 与 websocket_adapter.rs 对齐
            reconnect_base_ms: 5_000,     // 5s
        }
    }
}

/// 飞书引导接口响应（简化版，只取用得到的字段）。
///
/// 完整响应（抄 oapi-sdk-go v3/ws/endpoint.go）形如：
/// ```json
/// {
///   "code": 0,
///   "msg": "success",
///   "data": {
///     "url": "wss://open.feishu.cn/...?conn_id=xxx&service_id=yyy",
///     "client_config": { "ping_interval": 120000, "reconnect_interval": 120000 }
///   }
/// }
/// ```
#[derive(Debug, Deserialize)]
struct FeishuBootstrapResponse {
    code: i32,
    #[serde(default)]
    msg: String,
    #[serde(default)]
    data: Option<FeishuBootstrapData>,
}

#[derive(Debug, Deserialize)]
struct FeishuBootstrapData {
    url: String,
}

/// 飞书 app_access_token 缓存。token 有效期 2 小时（7200s），
/// 提前 5 分钟刷新避免边界过期。
struct TokenCache {
    app_access_token: Option<String>,
    expires_at: Instant,
}

impl TokenCache {
    fn new() -> Self {
        Self {
            app_access_token: None,
            expires_at: Instant::now(),
        }
    }

    /// 缓存的 token 是否仍然有效（剩余有效期 > 5 分钟）。
    fn is_valid(&self) -> bool {
        if self.app_access_token.is_none() {
            return false;
        }
        match self.expires_at.checked_duration_since(Instant::now()) {
            Some(remaining) => remaining > Duration::from_secs(300),
            None => false,
        }
    }
}

/// 飞书 API 调用错误类型，用于 `call_api_with_retry` 区分 token 过期。
pub enum FeishuApiError {
    /// access_token 过期（错误码 99991677/99991663），需要刷新后重试。
    TokenExpired,
    /// 其他错误。
    Other(String),
}

/// 检查飞书 API 响应的 code 字段是否表示 token 过期。
/// - 99991677: access_token 过期 (TOKEN_EXPIRED)
/// - 99991663: token 过期 (token_expired)
pub fn is_token_expired_code(code: i64) -> bool {
    code == 99991677 || code == 99991663
}

/// 飞书 / Lark 直连长连接适配器。
///
/// `LongConnAdapter` 不适用于飞书 — 飞书的 WSS URL 是动态分配的，
/// 必须先 HTTP POST 拿 URL，再 dial。本类实现完整协议。
pub struct FeishuAdapter {
    binding: IMBinding,
    provider: IMProvider,
    options: FeishuAdapterOptions,
    kind: ImChannelKind,
    /// 写队列。`None` 表示尚未 connect。
    out_tx: Arc<Mutex<Option<mpsc::UnboundedSender<WsMessage>>>>,
    cancel: Arc<AtomicBool>,
    cancel_notify: Arc<Notify>,
    generation: Arc<AtomicU64>,
    tx: broadcast::Sender<IMAdapterEvent>,
    /// app_access_token 缓存（带过期时间，提前 5 分钟刷新）。
    token_cache: Arc<Mutex<TokenCache>>,
}

impl FeishuAdapter {
    pub fn new(binding: IMBinding) -> Self {
        // 【BUGFIX】之前从 binding.metadata.get("type") 读,但 entry_to_binding
        // 只把 endpoint/secret 合并进 metadata,type 字段没进 metadata。
        // 正确来源是 binding.provider 字符串(im_config.rs::provider_tag 提取)。
        // feishu_lark 扫码后 provider="feishu_lark",这里识别后走国际版 larksuite.com。
        let kind = match binding.provider.as_str() {
            "feishu_lark" | "lark" => ImChannelKind::FeishuLark,
            _ => ImChannelKind::Feishu,
        };
        let provider = IMProvider::Feishu {
            // 这里填的 endpoint 是 bootstrap URL（写死的），
            // 真正的 WSS URL 是 bootstrap 接口动态返回的。
            endpoint: kind
                .feishu_bootstrap_url()
                .unwrap_or("https://open.feishu.cn/open-apis/connection/v1/connect")
                .to_string(),
            secret: None,
        };
        Self::with_options(binding, FeishuAdapterOptions::default(), kind, provider)
    }

    pub fn with_options(
        binding: IMBinding,
        options: FeishuAdapterOptions,
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
            token_cache: Arc::new(Mutex::new(TokenCache::new())),
        }
    }

    /// 从 `binding.metadata` 取 AppID。
    ///
    /// 存储路径（两条都需要支持）：
    ///   1. OAuth 扫码:metadata.app_id = "cli_xxx" (单独字段) +
    ///      metadata.secret = "cli_xxx:app_secret" (拼接,供 LongConn 兼容)
    ///   2. 手动填写:metadata.secret = "cli_xxx:app_secret" (单字段拼接)
    ///      或 metadata.secret = "cli_xxx" (兼容旧数据纯 app_id)
    ///   3. 兜底:channel_id 形如 "feishu-cli_xxx" 时提取
    fn app_id(&self) -> Option<String> {
        // 1. OAuth 扫码路径:metadata.app_id 单独字段
        if let Some(v) = self.binding.metadata.get("app_id").and_then(|v| v.as_str()) {
            if !v.is_empty() {
                return Some(v.to_string());
            }
        }
        // 2. 拆分 metadata.secret "appId:appSecret" 格式
        if let Some(secret) = self.binding.metadata.get("secret").and_then(|v| v.as_str()) {
            if !secret.is_empty() {
                if let Some(idx) = secret.find(':') {
                    let app_id = &secret[..idx];
                    if !app_id.is_empty() {
                        return Some(app_id.to_string());
                    }
                }
                // 无 ":" 且以 "cli_" 开头:整个 secret 当作 app_id
                if secret.starts_with("cli_") {
                    return Some(secret.to_string());
                }
            }
        }
        // 3. 兜底:从 channel_id 提取 "cli_xxx"
        let cid = &self.binding.channel_id;
        cid.find("cli_").map(|idx| cid[idx..].to_string())
    }

    /// 从 `binding.metadata` 取 AppSecret。
    /// 优先 metadata.app_secret (OAuth 扫码路径单独字段),
    /// 其次拆分 metadata.secret "appId:appSecret" 格式取冒号后部分,
    /// 兼容无冒号的纯 secret 输入。
    fn app_secret(&self) -> Option<String> {
        // 1. OAuth 扫码路径:metadata.app_secret 单独字段
        if let Some(v) = self.binding.metadata.get("app_secret").and_then(|v| v.as_str()) {
            if !v.is_empty() {
                return Some(v.to_string());
            }
        }
        // 2. 拆分 metadata.secret "appId:appSecret" 格式
        if let Some(secret) = self.binding.metadata.get("secret").and_then(|v| v.as_str()) {
            if !secret.is_empty() {
                if let Some(idx) = secret.find(':') {
                    let app_secret = &secret[idx + 1..];
                    if !app_secret.is_empty() {
                        return Some(app_secret.to_string());
                    }
                }
                // 无冒号:不是 "cli_" 开头时整个当作 app_secret (纯 secret 输入)
                if !secret.starts_with("cli_") {
                    return Some(secret.to_string());
                }
            }
        }
        None
    }

    /// HTTP POST 引导接口拿动态 WSS URL。抄 oapi-sdk-go v3/ws/client.go:
    /// `func (c *Client) getConnURL(ctx context.Context) (url string, err error)`
    async fn bootstrap_wss_url(&self) -> Result<String, String> {
        let url = self
            .kind
            .feishu_bootstrap_url()
            .ok_or_else(|| format!("no bootstrap url for kind={:?}", self.kind))?;
        let app_id = self
            .app_id()
            .ok_or_else(|| "feishu app_id missing".to_string())?;
        let app_secret = self
            .app_secret()
            .ok_or_else(|| "feishu app_secret missing".to_string())?;

        let body = serde_json::json!({
            "AppID": app_id,
            "AppSecret": app_secret,
        });

        let client = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(5))
            .timeout(Duration::from_millis(self.options.bootstrap_timeout_ms))
            .no_proxy()
            .user_agent(concat!("tupAI/", env!("CARGO_PKG_VERSION")))
            .build()
            .map_err(|e| format!("http client build failed: {}", e))?;

        let resp = client
            .post(url)
            .header("Content-Type", "application/json")
            .header("locale", "zh")
            .header("User-Agent", "tupai-im/1.0 (feishu direct long-conn)")
            .json(&body)
            .send()
            .await
            .map_err(|e| format!("feishu bootstrap http failed: {}", format_reqwest_error(&e)))?;

        let status = resp.status();
        if status.as_u16() == 401 || status.as_u16() == 403 {
            return Err(format!(
                "feishu bootstrap auth failed ({}): invalid app_id or app_secret",
                status
            ));
        }
        if !status.is_success() {
            let body = resp.text().await.unwrap_or_default();
            return Err(format!(
                "feishu bootstrap http {}: {}",
                status,
                body.chars().take(200).collect::<String>()
            ));
        }

        let parsed: FeishuBootstrapResponse = resp
            .json()
            .await
            .map_err(|e| format!("feishu bootstrap parse failed: {}", e))?;

        if parsed.code != 0 {
            return Err(format!(
                "feishu bootstrap code={} msg={}",
                parsed.code, parsed.msg
            ));
        }
        let wss = parsed
            .data
            .ok_or_else(|| "feishu bootstrap missing data".to_string())?
            .url;
        if !wss.starts_with("ws://") && !wss.starts_with("wss://") {
            return Err(format!(
                "feishu bootstrap returned non-ws url: {}",
                wss
            ));
        }
        Ok(wss)
    }

    /// 构造 WSS handshake Request（带标准 WS 握手头）。
    fn build_ws_request(wss_url: &str) -> Result<Request, String> {
        // 从 URL 提取 host:port 作为 Host 头
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

    /// 获取 app_access_token（带缓存，过期前 5 分钟自动刷新）。
    ///
    /// 飞书 API：POST /open-apis/auth/v3/app_access_token/internal
    ///   body: {"app_id":"cli_xxx","app_secret":"xxx"}
    ///   resp: {"code":0,"app_access_token":"a-xxx","expire":7200}
    /// token 有效期 2 小时（7200s），提前 5 分钟刷新避免边界过期。
    pub async fn get_app_access_token(&self) -> Result<String, String> {
        let mut cache = self.token_cache.lock().await;
        // 双重检查：可能其他任务已刷新
        if cache.is_valid() {
            return Ok(cache.app_access_token.clone().unwrap());
        }

        let app_id = self
            .app_id()
            .ok_or_else(|| "feishu app_id missing".to_string())?;
        let app_secret = self
            .app_secret()
            .ok_or_else(|| "feishu app_secret missing".to_string())?;
        let url = self
            .kind
            .feishu_app_access_token_url()
            .ok_or_else(|| format!("no app_access_token url for kind={:?}", self.kind))?;

        let body = serde_json::json!({
            "app_id": app_id,
            "app_secret": app_secret,
        });

        let client = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(5))
            .timeout(Duration::from_secs(10))
            .no_proxy()
            .user_agent(concat!("tupAI/", env!("CARGO_PKG_VERSION")))
            .build()
            .map_err(|e| format!("http client build failed: {}", e))?;

        let resp = client
            .post(url)
            .header("Content-Type", "application/json; charset=utf-8")
            .json(&body)
            .send()
            .await
            .map_err(|e| {
                format!(
                    "feishu app_access_token http failed: {}",
                    format_reqwest_error(&e)
                )
            })?;

        let status = resp.status();
        if !status.is_success() {
            let body = resp.text().await.unwrap_or_default();
            return Err(format!(
                "feishu app_access_token http {}: {}",
                status,
                body.chars().take(200).collect::<String>()
            ));
        }

        let parsed: serde_json::Value = resp
            .json()
            .await
            .map_err(|e| format!("feishu app_access_token parse failed: {}", e))?;

        let code = parsed.get("code").and_then(|v| v.as_i64()).unwrap_or(-1);
        if code != 0 {
            let msg = parsed.get("msg").and_then(|v| v.as_str()).unwrap_or("");
            return Err(format!("feishu app_access_token code={} msg={}", code, msg));
        }

        let token = parsed
            .get("app_access_token")
            .and_then(|v| v.as_str())
            .ok_or_else(|| "feishu app_access_token missing in response".to_string())?
            .to_string();

        let expire = parsed
            .get("expire")
            .and_then(|v| v.as_u64())
            .unwrap_or(7200);

        // 提前 5 分钟刷新（expire - 300s），最小保留 60s
        let ttl = Duration::from_secs(expire.saturating_sub(300).max(60));
        cache.app_access_token = Some(token.clone());
        cache.expires_at = Instant::now() + ttl;

        Ok(token)
    }

    /// 使缓存的 token 失效，强制下次获取时刷新。
    pub async fn invalidate_token(&self) {
        let mut cache = self.token_cache.lock().await;
        cache.app_access_token = None;
    }

    /// 调用飞书 API，带 token 自动刷新重试。
    ///
    /// - 第一次用缓存的 token 调用
    /// - 如果返回 token 过期错误（99991677/99991663），刷新 token 后重试一次
    /// - 仍失败则返回错误
    ///
    /// `api_call` 接收 token 字符串，返回结果或 `FeishuApiError`。
    /// 调用方需自行解析飞书响应 JSON 并用 `is_token_expired_code` 判断是否
    /// 为 token 过期错误。
    pub async fn call_api_with_retry<F, Fut, T>(&self, api_call: F) -> Result<T, String>
    where
        F: Fn(&str) -> Fut + Send,
        Fut: std::future::Future<Output = Result<T, FeishuApiError>> + Send,
        T: Send,
    {
        let token = self.get_app_access_token().await?;
        match api_call(&token).await {
            Ok(t) => Ok(t),
            Err(FeishuApiError::TokenExpired) => {
                tracing::warn!(
                    "[feishu] api returned token_expired (99991677), refreshing and retrying"
                );
                self.invalidate_token().await;
                let new_token = self.get_app_access_token().await?;
                match api_call(&new_token).await {
                    Ok(t) => Ok(t),
                    Err(FeishuApiError::TokenExpired) => {
                        Err("feishu token still expired after refresh".to_string())
                    }
                    Err(FeishuApiError::Other(e)) => Err(e),
                }
            }
            Err(FeishuApiError::Other(e)) => Err(e),
        }
    }
}

fn url_host(endpoint: &str) -> Option<String> {
    // 简化版：找 "://" 后的第一个 "/" 之前的内容作为 host:port
    let after = endpoint.split("://").nth(1)?;
    let host = after.split('/').next()?;
    if host.is_empty() {
        None
    } else {
        Some(host.to_string())
    }
}

/// 格式化 reqwest 错误,遍历 source() 链拼接完整原因。
/// reqwest::Error 默认 to_string() 只返回 "error sending request for url (...)",
/// 丢失底层 DNS/TLS/代理等真实错误。抄自 im_oauth.rs / device_register.rs。
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

#[async_trait]
impl IMAdapter for FeishuAdapter {
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

        let binding_id_clone = binding_id.clone();
        let cancel = self.cancel.clone();
        let cancel_notify = self.cancel_notify.clone();
        let generation = self.generation.clone();
        let out_tx_holder = self.out_tx.clone();
        let tx = self.tx.clone();
        let heartbeat = Duration::from_millis(self.options.heartbeat_ms.max(1_000));
        let bootstrap_timeout = Duration::from_millis(self.options.bootstrap_timeout_ms);
        let reconnect_base = Duration::from_millis(self.options.reconnect_base_ms.max(500));
        let reconnect_max = Duration::from_secs(60);
        let kind = self.kind;
        let app_id = self.app_id();
        let app_secret = self.app_secret();

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
                        "stage": "feishu_long_conn",
                    }),
                    ts: chrono::Utc::now().timestamp_millis(),
                });

                // 1. HTTP 引导拿 WSS URL（带超时，可被 cancel 中断）
                let wss_url = tokio::select! {
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
                        if app_id.is_none() || app_secret.is_none() {
                            return Err("app_id/app_secret missing".to_string());
                        }
                        // 直接调内部方法（不通过 &self 借用跨越 await）
                        let url = match kind.feishu_bootstrap_url() {
                            Some(u) => u,
                            None => return Err(format!("no bootstrap url for kind={:?}", kind)),
                        };
                        let body = serde_json::json!({
                            "AppID": app_id.as_deref().unwrap(),
                            "AppSecret": app_secret.as_deref().unwrap(),
                        });
                        let client = match reqwest::Client::builder()
                            .timeout(bootstrap_timeout)
                            .no_proxy()
                            .user_agent(concat!("tupAI/", env!("CARGO_PKG_VERSION")))
                            .build()
                        {
                            Ok(c) => c,
                            Err(e) => return Err(format!("http client build: {}", e)),
                        };
                        let resp = match client
                            .post(url)
                            .header("Content-Type", "application/json")
                            .header("locale", "zh")
                            .json(&body)
                            .send().await
                        {
                            Ok(r) => r,
                            Err(e) => return Err(format!("bootstrap http: {}", format_reqwest_error(&e))),
                        };
                        let status = resp.status();
                        if status.as_u16() == 401 || status.as_u16() == 403 {
                            return Err(format!(
                                "feishu bootstrap auth failed ({}): invalid app_id or app_secret",
                                status
                            ));
                        }
                        if !status.is_success() {
                            let body = resp.text().await.unwrap_or_default();
                            return Err(format!("feishu bootstrap http {}: {}", status, body.chars().take(200).collect::<String>()));
                        }
                        let parsed: FeishuBootstrapResponse = match resp.json().await {
                            Ok(p) => p,
                            Err(e) => return Err(format!("feishu bootstrap parse: {}", e)),
                        };
                        if parsed.code != 0 {
                            return Err(format!("feishu bootstrap code={} msg={}", parsed.code, parsed.msg));
                        }
                        match parsed.data {
                            Some(d) if d.url.starts_with("ws://") || d.url.starts_with("wss://") => Ok(d.url),
                            Some(d) => Err(format!("feishu bootstrap non-ws url: {}", d.url)),
                            None => Err("feishu bootstrap missing data".to_string()),
                        }
                    } => r,
                };

                let wss_url = match wss_url {
                    Ok(u) => u,
                    Err(err_msg) => {
                        let is_auth = err_msg.contains("auth failed")
                            || err_msg.contains("invalid app_id")
                            || err_msg.contains("app_secret");
                        if is_auth {
                            let _ = tx.send(IMAdapterEvent {
                                binding_id: binding_id.clone(),
                                kind: "auth_error".into(),
                                payload: serde_json::json!({
                                    "stage": "feishu_bootstrap",
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
                                "stage": "feishu_bootstrap",
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

                // 2. 升级 WSS
                let request = match Self::build_ws_request(&wss_url) {
                    Ok(r) => r,
                    Err(e) => {
                        tracing::error!("[feishu] build ws request failed: {}", e);
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
                                    Err(format!("feishu wss auth failed: {}", msg))
                                } else {
                                    Err(format!("feishu wss connect: {}", msg))
                                }
                            }
                            Err(_) => Err("feishu wss connect timeout after 8s".to_string()),
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
                                    "stage": "feishu_wss",
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
                                "stage": "feishu_wss",
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
                    payload: serde_json::json!({ "endpoint": wss_url }),
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

                // 飞书帧结构（参考 oapi-sdk-go）：
                // {"method":"ping","headers":{"type":"ping"},"payload":{}}
                // {"method":"data","headers":{"type":"event","message_id":"...","sum":1,"seq":0},"payload":{...事件 JSON...}}
                let ping_frame = WsMessage::Text(
                    serde_json::json!({
                        "method": "ping",
                        "headers": { "type": "ping" },
                        "payload": {}
                    })
                    .to_string(),
                );

                let _ = binding_id_clone; // 抑制 unused 警告

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
                        ws_msg = tokio::time::timeout(Duration::from_secs(150), read.next()) => {
                            match ws_msg {
                                Ok(Some(Ok(WsMessage::Text(text)))) => {
                                    // 解析飞书 frame
                                    if let Ok(frame) = serde_json::from_str::<serde_json::Value>(&text) {
                                        let method = frame.get("method").and_then(|v| v.as_str()).unwrap_or("");
                                        let kind_hdr = frame.get("headers")
                                            .and_then(|h| h.get("type"))
                                            .and_then(|v| v.as_str())
                                            .unwrap_or("");
                                        if method == "data" && kind_hdr == "event" {
                                            let payload = frame.get("payload").cloned().unwrap_or(serde_json::Value::Null);
                                            // 派发为 message 事件
                                            let _ = tx.send(IMAdapterEvent {
                                                binding_id: binding_id.clone(),
                                                kind: "message".into(),
                                                payload,
                                                ts: chrono::Utc::now().timestamp_millis(),
                                            });
                                        }
                                        // pong / control frame 不透传，仅日志
                                        if method == "control" && kind_hdr == "pong" {
                                            tracing::debug!("[feishu] pong received");
                                        }
                                    }
                                }
                                Ok(Some(Ok(WsMessage::Binary(bin)))) => {
                                    // 飞书 SDK 也支持 binary 帧
                                    if let Ok(text) = std::str::from_utf8(&bin) {
                                        if let Ok(frame) = serde_json::from_str::<serde_json::Value>(text) {
                                            let method = frame.get("method").and_then(|v| v.as_str()).unwrap_or("");
                                            let kind_hdr = frame.get("headers")
                                                .and_then(|h| h.get("type"))
                                                .and_then(|v| v.as_str())
                                                .unwrap_or("");
                                            if method == "data" && kind_hdr == "event" {
                                                let payload = frame.get("payload").cloned().unwrap_or(serde_json::Value::Null);
                                                let _ = tx.send(IMAdapterEvent {
                                                    binding_id: binding_id.clone(),
                                                    kind: "message".into(),
                                                    payload,
                                                    ts: chrono::Utc::now().timestamp_millis(),
                                                });
                                            }
                                        }
                                    }
                                }
                                Ok(Some(Ok(WsMessage::Ping(p)))) => {
                                    // 协议层 ping → 回 pong
                                    let _ = write.send(WsMessage::Pong(p)).await;
                                }
                                Ok(Some(Ok(WsMessage::Close(_)))) | Ok(None) => {
                                    tracing::info!("[feishu] ws closed by peer");
                                    break;
                                }
                                Ok(Some(Ok(_))) => { /* Pong / Frame 忽略 */ }
                                Ok(Some(Err(e))) => {
                                    let _ = tx.send(IMAdapterEvent {
                                        binding_id: binding_id.clone(),
                                        kind: "error".into(),
                                        payload: serde_json::json!({ "stage": "recv", "error": e.to_string() }),
                                        ts: chrono::Utc::now().timestamp_millis(),
                                    });
                                    break;
                                }
                                Err(_) => {
                                    // read 超时（150s 略大于 heartbeat 120s）→ 半开连接
                                    tracing::warn!(
                                        "[feishu] ws read timeout after 150s, treating as half-open, reconnecting"
                                    );
                                    let _ = tx.send(IMAdapterEvent {
                                        binding_id: binding_id.clone(),
                                        kind: "disconnected".into(),
                                        payload: serde_json::json!({
                                            "reason": "read_timeout",
                                            "timeout_secs": 150,
                                        }),
                                        ts: chrono::Utc::now().timestamp_millis(),
                                    });
                                    break;
                                }
                            }
                        }
                    }
                }
                // 进入下一轮外层 loop（重连）
            }
        });

        match first_result_rx.await {
            Ok(r) => r,
            Err(_) => Err("feishu connect task dropped".to_string()),
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
        // 飞书发消息走 REST：POST /open-apis/im/v1/messages?receive_id_type=chat_id
        //   Headers: Authorization: Bearer {app_access_token}, Content-Type: application/json; charset=utf-8
        //   Body: {"receive_id": chat_id, "msg_type": "text", "content": "{\"text\":\"...\"}"}
        // 用 call_api_with_retry 包装，自动处理 token 过期重试。
        let base = match self.kind {
            ImChannelKind::Feishu => "https://open.feishu.cn",
            ImChannelKind::FeishuLark => "https://open.larksuite.com",
            _ => {
                return Err(format!(
                    "unsupported kind for feishu send: {:?}",
                    self.kind
                ));
            }
        };
        let url = format!("{}/open-apis/im/v1/messages?receive_id_type=chat_id", base);

        // content 是纯文本；飞书要求 content 字段是 JSON 字符串 {"text":"..."}
        let content_json = serde_json::json!({ "text": content }).to_string();
        let receive_id = target.to_string();
        let url_for_closure = url.clone();

        let message_id = self
            .call_api_with_retry(|token: &str| {
                let token = token.to_string();
                let url = url_for_closure.clone();
                let receive_id = receive_id.clone();
                let content_json = content_json.clone();
                async move {
                    let client = reqwest::Client::builder()
                        .connect_timeout(Duration::from_secs(5))
                        .timeout(Duration::from_secs(15))
                        .no_proxy()
                        .user_agent(concat!("tupAI/", env!("CARGO_PKG_VERSION")))
                        .build()
                        .map_err(|e| {
                            FeishuApiError::Other(format!("http client build failed: {}", e))
                        })?;

                    let body = serde_json::json!({
                        "receive_id": receive_id,
                        "msg_type": "text",
                        "content": content_json,
                    });

                    let resp = client
                        .post(&url)
                        .header("Authorization", format!("Bearer {}", token))
                        .header("Content-Type", "application/json; charset=utf-8")
                        .json(&body)
                        .send()
                        .await
                        .map_err(|e| {
                            FeishuApiError::Other(format!(
                                "feishu send http failed: {}",
                                format_reqwest_error(&e)
                            ))
                        })?;

                    let status = resp.status();
                    if !status.is_success() {
                        let body = resp.text().await.unwrap_or_default();
                        return Err(FeishuApiError::Other(format!(
                            "feishu send http {}: {}",
                            status,
                            body.chars().take(200).collect::<String>()
                        )));
                    }

                    let parsed: serde_json::Value = resp
                        .json()
                        .await
                        .map_err(|e| {
                            FeishuApiError::Other(format!("feishu send parse failed: {}", e))
                        })?;

                    let code = parsed
                        .get("code")
                        .and_then(|v| v.as_i64())
                        .unwrap_or(-1);
                    if code != 0 {
                        if is_token_expired_code(code) {
                            return Err(FeishuApiError::TokenExpired);
                        }
                        let msg = parsed
                            .get("msg")
                            .and_then(|v| v.as_str())
                            .unwrap_or("");
                        return Err(FeishuApiError::Other(format!(
                            "feishu send code={} msg={}",
                            code, msg
                        )));
                    }

                    // 优先返回 message_id，缺失时返回完整响应 JSON
                    let mid = parsed
                        .get("data")
                        .and_then(|d| d.get("message_id"))
                        .and_then(|v| v.as_str())
                        .map(|s| s.to_string())
                        .unwrap_or_else(|| parsed.to_string());
                    Ok(mid)
                }
            })
            .await?;

        Ok(message_id)
    }

    fn subscribe(&self) -> broadcast::Receiver<IMAdapterEvent> {
        self.tx.subscribe()
    }
}

pub type SharedFeishuAdapter = Arc<FeishuAdapter>;
