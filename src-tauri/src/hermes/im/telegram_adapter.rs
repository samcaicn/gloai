// Copyright (c) 2026 MeeJoy
//
// Telegram Bot 适配器。使用 Bot API long polling 收发消息。
//
// 协议 (抄自 Telegram Bot API 文档 https://core.telegram.org/bots/api):
//   1) POST https://api.telegram.org/bot{token}/getUpdates (long polling, timeout=30)
//      → [{"update_id":..., "message":{"chat_id":..., "text":...}}]
//   2) POST https://api.telegram.org/bot{token}/sendMessage
//      body: {"chat_id":..., "text":...}
//   3) 心跳: getUpdates 本身就是 long polling,无额外 ping
//
// 【铁律】endpoint 写死在 im_endpoints.rs::ImChannelKind::telegram_api_base()。
// bot_token 由用户从 @BotFather 获取,存在 metadata.secret 里。

use async_trait::async_trait;
use serde::Deserialize;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::{broadcast, oneshot, Notify};

use super::adapter_base::{IMAdapter, IMAdapterEvent, IMBinding, IMProvider};
use super::im_endpoints::ImChannelKind;

/// Telegram Bot 适配器配置。
#[derive(Clone, Debug)]
pub struct TelegramAdapterOptions {
    /// long polling 超时秒数。Telegram 最大 50,我们用 30。
    pub poll_timeout_secs: u64,
    /// 重连基准间隔。失败后翻倍,封顶 60s。
    pub reconnect_base_ms: u64,
}

impl Default for TelegramAdapterOptions {
    fn default() -> Self {
        Self {
            poll_timeout_secs: 30,
            reconnect_base_ms: 5_000,
        }
    }
}

/// Telegram getUpdates 响应。
#[derive(Debug, Deserialize)]
struct TelegramUpdatesResponse {
    #[serde(default)]
    ok: bool,
    #[serde(default)]
    result: Vec<TelegramUpdate>,
}

#[derive(Debug, Deserialize)]
struct TelegramUpdate {
    #[serde(default)]
    update_id: i64,
    #[serde(default)]
    message: Option<TelegramMessage>,
}

#[derive(Debug, Deserialize)]
struct TelegramMessage {
    #[serde(default)]
    message_id: i64,
    #[serde(default)]
    chat: TelegramChat,
    #[serde(default)]
    text: Option<String>,
    #[serde(default)]
    from: Option<TelegramUser>,
}

#[derive(Debug, Default, Deserialize)]
struct TelegramChat {
    id: i64,
}

#[derive(Debug, Deserialize)]
struct TelegramUser {
    #[serde(default)]
    id: i64,
    #[serde(default)]
    first_name: String,
    #[serde(default)]
    username: Option<String>,
}

/// Telegram Bot 直连适配器 (long polling)。
pub struct TelegramAdapter {
    binding: IMBinding,
    provider: IMProvider,
    options: TelegramAdapterOptions,
    /// 已连接标志。connect 成功时置 true，disconnect/任务退出时置 false。
    /// 替代原先的 mock `out_tx`（Telegram 用 HTTP long polling，不走 WebSocket）。
    connected: Arc<AtomicBool>,
    cancel: Arc<AtomicBool>,
    cancel_notify: Arc<Notify>,
    generation: Arc<AtomicI64>,
    tx: broadcast::Sender<IMAdapterEvent>,
}

impl TelegramAdapter {
    pub fn new(binding: IMBinding) -> Self {
        let provider = IMProvider::Telegram {
            endpoint: ImChannelKind::Telegram
                .telegram_api_base()
                .unwrap_or("https://api.telegram.org")
                .to_string(),
            secret: None,
        };
        let (tx, _) = broadcast::channel(256);
        Self {
            binding,
            provider,
            options: TelegramAdapterOptions::default(),
            connected: Arc::new(AtomicBool::new(false)),
            cancel: Arc::new(AtomicBool::new(false)),
            cancel_notify: Arc::new(Notify::new()),
            generation: Arc::new(AtomicI64::new(0)),
            tx,
        }
    }

    /// 从 metadata 获取 bot_token。
    fn bot_token(&self) -> Option<String> {
        if let Some(v) = self.binding.metadata.get("secret").and_then(|v| v.as_str()) {
            if !v.is_empty() {
                return Some(v.to_string());
            }
        }
        if let Some(v) = self.binding.metadata.get("bot_token").and_then(|v| v.as_str()) {
            if !v.is_empty() {
                return Some(v.to_string());
            }
        }
        None
    }

    fn api_url(&self, method: &str, token: &str) -> String {
        let base = ImChannelKind::Telegram
            .telegram_api_base()
            .unwrap_or("https://api.telegram.org");
        format!("{}/bot{}/{}", base, token, method)
    }
}

/// 格式化 reqwest 错误 (抄自 im_oauth.rs)。
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
impl IMAdapter for TelegramAdapter {
    fn provider(&self) -> &IMProvider {
        &self.provider
    }

    async fn connect(&self) -> Result<(), String> {
        let binding_id = self.binding.id.clone();

        
        // 幂等 + 竞态修复：compare_exchange 原子地检查并设置，避免两个并发
        // connect() 都看到 false 后各自起一个后台任务（与原 out_tx Mutex 临界区
        // 等价）。connect 成功后保持 true，disconnect/任务退出时置 false。
        if self
            .connected
            .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
            .is_err()
        {
            return Ok(());
        }
        self.cancel.store(false, Ordering::Release);
        let my_gen = self.generation.fetch_add(1, Ordering::SeqCst) + 1;

        let (first_result_tx, first_result_rx) = oneshot::channel::<Result<(), String>>();

        let cancel = self.cancel.clone();
        let cancel_notify = self.cancel_notify.clone();
        let generation = self.generation.clone();
        let tx = self.tx.clone();
        let connected = self.connected.clone();
        let token = self.bot_token();
        let poll_timeout = self.options.poll_timeout_secs;
        let reconnect_base = Duration::from_millis(self.options.reconnect_base_ms.max(500));
        let reconnect_max = Duration::from_secs(60);
        let api_base = ImChannelKind::Telegram
            .telegram_api_base()
            .unwrap_or("https://api.telegram.org")
            .to_string();

        tokio::spawn(async move {
            let mut first_reported = false;
            let mut first_result_tx_opt = Some(first_result_tx);
            let mut backoff = reconnect_base;
            let disconnected_announced = false;
            let mut last_update_id: i64 = 0;
            let mut consecutive_failures: u32 = 0;
            const CIRCUIT_BREAKER_THRESHOLD: u32 = 5;
            const CIRCUIT_BREAKER_COOLDOWN_SECS: u64 = 120;

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

                let token = match &token {
                    Some(t) => t.clone(),
                    None => {
                        let err = "telegram bot_token missing".to_string();
                        if !first_reported {
                            if let Some(tx0) = first_result_tx_opt.take() {
                                let _ = tx0.send(Err(err.clone()));
                            }
                        }
                        let _ = tx.send(IMAdapterEvent {
                            binding_id: binding_id.clone(),
                            kind: "auth_error".into(),
                            payload: serde_json::json!({"error": err, "hint": "token_missing"}),
                            ts: chrono::Utc::now().timestamp_millis(),
                        });
                        connected.store(false, Ordering::SeqCst);
                        return;
                    }
                };

                let _ = tx.send(IMAdapterEvent {
                    binding_id: binding_id.clone(),
                    kind: "connecting".into(),
                    payload: serde_json::json!({"stage": "telegram_long_poll"}),
                    ts: chrono::Utc::now().timestamp_millis(),
                });

                // long poll getUpdates
                let url = format!("{}/bot{}/getUpdates", api_base, token);
                let client = match reqwest::Client::builder()
                    .timeout(Duration::from_secs(poll_timeout + 10))
                    .build()
                {
                    Ok(c) => c,
                    Err(e) => {
                        let err = format!("http client build: {}", e);
                        if !first_reported {
                            if let Some(tx0) = first_result_tx_opt.take() {
                                let _ = tx0.send(Err(err.clone()));
                            }
                        }
                        tokio::time::sleep(backoff).await;
                        backoff = (backoff * 2).min(reconnect_max);
                        continue;
                    }
                };

                let poll_result = tokio::select! {
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
                        connected.store(false, Ordering::SeqCst);
                        return;
                    }
                    r = client.post(&url).json(&serde_json::json!({
                        "offset": last_update_id + 1,
                        "timeout": poll_timeout,
                        "allowed_updates": ["message"],
                    })).send() => r,
                };

                let resp = match poll_result {
                    Ok(r) => r,
                    Err(e) => {
                        let detail = format_reqwest_error(&e);
                        consecutive_failures += 1;
                        let _ = tx.send(IMAdapterEvent {
                            binding_id: binding_id.clone(),
                            kind: "error".into(),
                            payload: serde_json::json!({
                                "stage": "telegram_poll",
                                "error": detail,
                                "consecutive_failures": consecutive_failures,
                            }),
                            ts: chrono::Utc::now().timestamp_millis(),
                        });
                        if !first_reported {
                            if let Some(tx0) = first_result_tx_opt.take() {
                                let _ = tx0.send(Err(format!("telegram poll: {}", detail)));
                            }
                            connected.store(false, Ordering::SeqCst);
                            return;
                        }
                        if consecutive_failures >= CIRCUIT_BREAKER_THRESHOLD {
                            let _ = tx.send(IMAdapterEvent {
                                binding_id: binding_id.clone(),
                                kind: "circuit_breaker".into(),
                                payload: serde_json::json!({
                                    "consecutive_failures": consecutive_failures,
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
                            connected.store(false, Ordering::SeqCst);
                            return;
                        }
                        tokio::time::sleep(backoff).await;
                        backoff = (backoff * 2).min(reconnect_max);
                        continue;
                    }
                };

                let status = resp.status();
                if status.as_u16() == 401 {
                    let _ = tx.send(IMAdapterEvent {
                        binding_id: binding_id.clone(),
                        kind: "auth_error".into(),
                        payload: serde_json::json!({
                            "stage": "telegram_poll",
                            "error": "401 Unauthorized: invalid bot_token",
                            "hint": "token_invalid",
                        }),
                        ts: chrono::Utc::now().timestamp_millis(),
                    });
                    if !first_reported {
                        if let Some(tx0) = first_result_tx_opt.take() {
                            let _ = tx0.send(Err("telegram auth failed: invalid bot_token".into()));
                        }
                    }
                    connected.store(false, Ordering::SeqCst);
                    return;
                }
                if !status.is_success() {
                    let body = resp.text().await.unwrap_or_default();
                    consecutive_failures += 1;
                    let http_err = format!("telegram http {}: {}", status, &body.chars().take(200).collect::<String>());
                    let _ = tx.send(IMAdapterEvent {
                        binding_id: binding_id.clone(),
                        kind: "error".into(),
                        payload: serde_json::json!({
                            "stage": "telegram_poll",
                            "error": http_err,
                            "consecutive_failures": consecutive_failures,
                        }),
                        ts: chrono::Utc::now().timestamp_millis(),
                    });
                    if !first_reported {
                        if let Some(tx0) = first_result_tx_opt.take() {
                            let _ = tx0.send(Err(http_err));
                        }
                        connected.store(false, Ordering::SeqCst);
                        return;
                    }
                    tokio::time::sleep(backoff).await;
                    backoff = (backoff * 2).min(reconnect_max);
                    continue;
                }

                let parsed: TelegramUpdatesResponse = match resp.json().await {
                    Ok(p) => p,
                    Err(e) => {
                        consecutive_failures += 1;
                        let parse_err = format!("telegram parse: {}", e);
                        let _ = tx.send(IMAdapterEvent {
                            binding_id: binding_id.clone(),
                            kind: "error".into(),
                            payload: serde_json::json!({
                                "stage": "telegram_poll",
                                "error": parse_err,
                                "consecutive_failures": consecutive_failures,
                            }),
                            ts: chrono::Utc::now().timestamp_millis(),
                        });
                        if !first_reported {
                            if let Some(tx0) = first_result_tx_opt.take() {
                                let _ = tx0.send(Err(parse_err));
                            }
                            connected.store(false, Ordering::SeqCst);
                            return;
                        }
                        tokio::time::sleep(backoff).await;
                        continue;
                    }
                };

                // 成功,重置退避
                backoff = reconnect_base;
                consecutive_failures = 0;

                if !first_reported {
                    first_reported = true;
                    if let Some(tx0) = first_result_tx_opt.take() {
                        let _ = tx0.send(Ok(()));
                    }
                    let _ = tx.send(IMAdapterEvent {
                        binding_id: binding_id.clone(),
                        kind: "connected".into(),
                        payload: serde_json::json!({"endpoint": &api_base}),
                        ts: chrono::Utc::now().timestamp_millis(),
                    });
                }

                // 处理收到的消息
                for update in parsed.result {
                    if update.update_id > last_update_id {
                        last_update_id = update.update_id;
                    }
                    if let Some(msg) = update.message {
                        let chat_id = msg.chat.id;
                        let text = msg.text.unwrap_or_default();
                        let username = msg
                            .from
                            .as_ref()
                            .and_then(|u| u.username.as_deref())
                            .unwrap_or("");
                        let payload = serde_json::json!({
                            "chat_id": chat_id,
                            "text": text,
                            "from": {
                                "id": msg.from.as_ref().map(|u| u.id).unwrap_or(0),
                                "username": username,
                                "first_name": msg.from.as_ref().map(|u| u.first_name.as_str()).unwrap_or(""),
                            },
                            "message_id": msg.message_id,
                        });
                        let _ = tx.send(IMAdapterEvent {
                            binding_id: binding_id.clone(),
                            kind: "message".into(),
                            payload,
                            ts: chrono::Utc::now().timestamp_millis(),
                        });
                    }
                }
            }
        });

        match first_result_rx.await {
            Ok(r) => r,
            Err(_) => Err("telegram connect task dropped".to_string()),
        }
    }

    async fn disconnect(&self) -> Result<(), String> {
        self.cancel.store(true, Ordering::Release);
        self.cancel_notify.notify_waiters();
        self.connected.store(false, Ordering::SeqCst);
        Ok(())
    }

    async fn send(&self, target: &str, content: &str) -> Result<String, String> {
        let token = self
            .bot_token()
            .ok_or_else(|| "telegram bot_token missing".to_string())?;
        let url = self.api_url("sendMessage", &token);
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(15))
            .build()
            .map_err(|e| format!("http client build: {}", e))?;
        let resp = client
            .post(&url)
            .json(&serde_json::json!({
                "chat_id": target,
                "text": content,
            }))
            .send()
            .await
            .map_err(|e| format!("telegram send: {}", format_reqwest_error(&e)))?;
        let status = resp.status();
        if !status.is_success() {
            let body = resp.text().await.unwrap_or_default();
            return Err(format!(
                "telegram send http {}: {}",
                status,
                &body.chars().take(200).collect::<String>()
            ));
        }
        Ok("ok".to_string())
    }

    fn subscribe(&self) -> broadcast::Receiver<IMAdapterEvent> {
        self.tx.subscribe()
    }
}

pub type SharedTelegramAdapter = Arc<TelegramAdapter>;
