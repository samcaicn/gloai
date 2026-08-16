// Copyright (c) 2026 MeeJoy
//
// 长连接 IM 适配器。
//
// **禁止使用一次性 HTTP POST (Webhook)**。所有渠道必须通过长连接收发。
//   1. 启动时 spawn 一个后台任务，使用 tokio-tungstenite 建立
//      与中继网关的 WS 长连接（带心跳、断线重连）。
//   2. `send` 把消息放入一个 mpsc 队列，由后台写任务统一发出。
//   3. 读任务把入站消息以 `IMAdapterEvent { kind: "message", ... }`
//      通过 broadcast 推给订阅者。
//
// 平台鉴权（企业微信/飞书/钉钉/微信/QQ）和具体消息编解码由
// 中继网关负责，本进程只负责保持连接 + 转发。

use async_trait::async_trait;
use futures_util::{SinkExt, StreamExt};
use rand::Rng;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::{broadcast, mpsc, oneshot, Mutex, Notify};
use tokio::time::{interval, timeout};
use tokio_tungstenite::tungstenite::handshake::client::{generate_key, Request};
use tokio_tungstenite::tungstenite::http::HeaderValue;
use tokio_tungstenite::tungstenite::Message as WsMessage;

use super::adapter_base::{IMAdapter, IMAdapterEvent, IMBinding, IMProvider};

#[derive(Clone, Debug)]
pub struct LongConnAdapterOptions {
    pub heartbeat_ms: u64,
    pub reconnect_ms: u64,
}

impl Default for LongConnAdapterOptions {
    fn default() -> Self {
        Self {
            heartbeat_ms: 30_000,
            reconnect_ms: 5_000,
        }
    }
}

pub struct LongConnAdapter {
    pub binding: IMBinding,
    pub options: LongConnAdapterOptions,
    provider: IMProvider,
    tx: broadcast::Sender<IMAdapterEvent>,
    /// 写队列。`None` 表示尚未 connect。
    out_tx: Arc<Mutex<Option<mpsc::UnboundedSender<WsMessage>>>>,
    /// 后台连接任务取消标志。`disconnect()` 置为 true，后台内外层
    /// loop 检查后退出，避免设置 `out_tx = None` 后任务仍无限重连。
    cancel: Arc<AtomicBool>,
    /// M3：Notify 用于 disconnect() 即时唤醒后台 select! 内的等待，
    /// 避免 cancel 标志要等 tick(30s)/read 超时(60s) 才被检查。
    /// 与 `cancel: Arc<AtomicBool>` 双保险：Notify 唤醒 select!，AtomicBool 兜底 loop 检查。
    cancel_notify: Arc<Notify>,
    /// 连接代际计数器。每次 `connect()` 自增，后台任务记住自己的代际，
    /// 若发现代际变化（被新的 connect 取代）则退出，避免 disconnect 后
    /// 旧任务被新 connect 重置 cancel=false 唤醒而继续重连（Bug 2B）。
    generation: Arc<AtomicU64>,
}

impl LongConnAdapter {
    pub fn new(binding: IMBinding, options: LongConnAdapterOptions) -> Self {
        let (tx, _) = broadcast::channel(256);
        let provider = match &binding.metadata.get("endpoint").and_then(|v| v.as_str()) {
            Some(ep) if !ep.is_empty() => IMProvider::LongConn {
                endpoint: ep.to_string(),
                secret: binding
                    .metadata
                    .get("secret")
                    .and_then(|v| v.as_str())
                    .map(str::to_string),
            },
            _ => match &binding.metadata.get("url").and_then(|v| v.as_str()) {
                Some(url) if !url.is_empty() => IMProvider::WebSocket { url: url.to_string() },
                _ => IMProvider::LongConn {
                    endpoint: String::new(),
                    secret: None,
                },
            },
        };
        Self {
            binding,
            options,
            provider,
            tx,
            out_tx: Arc::new(Mutex::new(None)),
            cancel: Arc::new(AtomicBool::new(false)),
            cancel_notify: Arc::new(Notify::new()),
            generation: Arc::new(AtomicU64::new(0)),
        }
    }

    fn endpoint(&self) -> String {
        match &self.provider {
            IMProvider::LongConn { endpoint, .. }
            | IMProvider::WeCom { endpoint, .. }
            | IMProvider::Feishu { endpoint, .. }
            | IMProvider::FeishuLark { endpoint, .. }
            | IMProvider::DingTalk { endpoint, .. }
            | IMProvider::Weixin { endpoint, .. }
            | IMProvider::QqBot { endpoint, .. }
            | IMProvider::Telegram { endpoint, .. } => endpoint.clone(),
            IMProvider::WebSocket { url } => url.clone(),
            IMProvider::Legacy => String::new(),
        }
    }

    /// 返回用于 `Authorization: Bearer <secret>` 头的 secret（Bug C）。
    /// 来自 `metadata.secret`，由 `entry_to_binding` 从 `provider.secret` 合并而来。
    fn secret(&self) -> Option<String> {
        match &self.provider {
            IMProvider::LongConn { secret, .. }
            | IMProvider::WeCom { secret, .. }
            | IMProvider::Feishu { secret, .. }
            | IMProvider::FeishuLark { secret, .. }
            | IMProvider::DingTalk { secret, .. }
            | IMProvider::Weixin { secret, .. }
            | IMProvider::QqBot { secret, .. }
            | IMProvider::Telegram { secret, .. } => secret.clone(),
            _ => None,
        }
    }

    /// 构造 WS handshake Request（修复 "Missing, duplicated or incorrect header
    /// sec-websocket-key" 错误）。
    ///
    /// 背景：tokio-tungstenite 0.21 的 `connect_async` 接收手动构造的
    /// `Request<()>` 时，并不会自动补齐 WebSocket 握手头（仅当传入 `Url`
    /// 时才自动补）。如果只 `Request::builder().method("GET").uri(...).body(())`
    /// 后塞一个 `Authorization`，握手请求会缺 `Sec-WebSocket-Key` /
    /// `Sec-WebSocket-Version` / `Upgrade` / `Connection` / `Host`，服务端
    /// 校验失败后返回 "Missing, duplicated or incorrect header sec-websocket-key"。
    ///
    /// 这里显式补齐全部 5 个 WS 握手头：
    ///   - Host：从 endpoint URI 的 authority 部分提取
    ///   - Upgrade: websocket
    ///   - Connection: Upgrade
    ///   - Sec-WebSocket-Version: 13
    ///   - Sec-WebSocket-Key: tungstenite::handshake::client::generate_key()
    ///     生成的 16 字节随机 base64 字符串（RFC 6455 要求）
    ///
    /// 参考 `hermes::ws_client::HermesWs::connect`（同步修两处 bug：
    /// 之前它和本函数都缺这些头）。
    fn build_connect_request(&self) -> Result<Request, String> {
        let endpoint = self.endpoint();
        // 从 endpoint URI 提取 host:port 作为 Host 头。若解析失败，
        // 退化为不设 Host 头（部分服务端仍可接受，但更稳的做法是
        // 显式报错让上游重配）。
        let host_header = url_host(&endpoint);
        let mut request = Request::builder()
            .method("GET")
            .uri(&endpoint)
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
        if let Some(secret) = self.secret() {
            if !secret.is_empty() {
                let hv = HeaderValue::from_str(&format!("Bearer {}", secret))
                    .map_err(|e| format!("invalid secret header value: {}", e))?;
                request.headers_mut().insert("Authorization", hv);
            }
        }
        Ok(request)
    }
}

#[async_trait]
impl IMAdapter for LongConnAdapter {
    fn provider(&self) -> &IMProvider {
        &self.provider
    }

    async fn connect(&self) -> Result<(), String> {
        let endpoint = self.endpoint();
        if endpoint.is_empty() {
            return Err("im long-conn endpoint is empty".to_string());
        }
        // Bug L：校验 URL scheme。tokio-tungstenite 只接受 ws:// / wss://。
        if !(endpoint.starts_with("ws://") || endpoint.starts_with("wss://")) {
            return Err(format!(
                "im long-conn endpoint must be ws:// or wss://, got: {}",
                endpoint
            ));
        }
        // Bug C：构造带 Authorization 头的 Request（在锁外构造，避免持锁 await）。
        let request = self.build_connect_request()?;

        let (out_tx, mut out_rx) = mpsc::unbounded_channel::<WsMessage>();
        // 幂等 + 竞态修复：检查 is_some() 与写入 Some(...) 放在同一个锁临界区内，
        // 避免两个并发 connect() 都看到 None 后各自起一个后台任务。
        let my_gen;
        {
            let mut g = self.out_tx.lock().await;
            if g.is_some() {
                return Ok(());
            }
            // 重置 cancel 标志（前一次 disconnect 后重新连接的场景）。
            self.cancel.store(false, Ordering::Release);
            // 自增代际：本次 connect 启动的后台任务以此代际为准，若随后又
            // 调用了 connect()（代际再次自增），旧任务检测到代际不一致即退出。
            my_gen = self.generation.fetch_add(1, Ordering::SeqCst) + 1;
            *g = Some(out_tx);
        }

        let binding_id = self.binding.id.clone();
        let channel_id = self.binding.channel_id.clone();
        let heartbeat = Duration::from_millis(self.options.heartbeat_ms.max(1_000));
        // Bug E：read 超时 = heartbeat * 2，超过则视为半开连接，主动重连。
        let read_timeout = heartbeat.checked_mul(2).unwrap_or(heartbeat);
        // Bug M：指数退避基准与封顶。
        let reconnect_base = Duration::from_millis(self.options.reconnect_ms.max(500));
        let reconnect_max = Duration::from_secs(60);
        let tx = self.tx.clone();
        let out_tx_holder = self.out_tx.clone();
        let cancel = self.cancel.clone();
        let cancel_notify = self.cancel_notify.clone();
        let generation = self.generation.clone();

        // Bug F：oneshot 通道把首次连接结果回灌给 connect() 调用方。
        // 调用方据此返回 Ok/Err，避免「立即返回 Ok 但 spawn task 内部反复重连失败」
        // 导致的"假成功"问题。首次失败时退出 spawn task（调用方返回 Err，adapter
        // 不被 AdapterPool 缓存，下次 get_or_connect 会重新构造）。
        let (first_result_tx, first_result_rx) = oneshot::channel::<Result<(), String>>();

        #[allow(unused_assignments)]
        tokio::spawn(async move {
            // 首次连接结果是否已上报。一旦上报，后续重连失败/成功都不再通知调用方
            // （调用方已返回，由 spawn task 自行重连）。
            let mut first_reported = false;
            // 用 Option 包裹 sender，避免在 Err/Ok 两个分支都被 move 而编译失败。
            // take() 确保只有先执行的那个分支真正发送。
            let mut first_result_tx_opt = Some(first_result_tx);
            // Bug M：当前退避时长，每次成功连接后重置回 base，失败翻倍。
            let mut backoff = reconnect_base;
            let mut disconnected_announced = false;
            // Circuit breaker: 连续失败达到阈值后停止重试一段时间。
            // 背景：用户机器上的 IM 渠道 aib8L2YE1tDTurqy-... 启动时反复
            // 返回 "Missing, duplicated or incorrect header sec-websocket-key"
            // （服务端对 WebSocket 握手头的格式要求严苛），每次重试都耗 30s
            // 拿不到响应又挂一轮。后台无限重试让 tokio runtime 一直忙碌，
            // 主线程操作 IM 入口时 JS invoke 排队、用户感觉"卡死"。
            // 修复：连续 N 次连接失败 → 进入 cooldown 期 → 任务退出，调用方
            // 标记为失败。下次用户重新 im_config_set 或重启时再尝试。
            let mut consecutive_failures: u32 = 0;
            // 中危修复：阈值从 3 降到 2，cooldown 从 300s 降到 60s。
            //
            // 直连 IM 网关时，对端故障（nginx 后的 upstream 死了 /
            // 网关进程 OOM / 防火墙丢包）几乎不会自愈。3 次失败 +
            // 5 分钟冷却期间用户看到的是"绑定卡住 90s + 静默 5 分钟"
            // —— 切到 2 次 + 60s 后：16s 内熔断，60s 后再尝试
            // 一次，期间 UI 不再被卡（circuit_breaker 事件已发）。
            const CIRCUIT_BREAKER_THRESHOLD: u32 = 2;
            // cooldown 时长：60s（之前 300s）。
            const CIRCUIT_BREAKER_COOLDOWN_SECS: u64 = 60;

            loop {
                // 外层 loop 检查 cancel / 代际：disconnect() 后退出；或被新的
                // connect() 取代（代际变化）后退出，避免旧任务继续重连（Bug 2B）。
                if cancel.load(Ordering::Acquire) || generation.load(Ordering::Acquire) != my_gen {
                    // M4：cancel 退出路径显式上报 Err，让 connect() 返回明确错误，
                    // 而非 RecvError → "task panicked" 的误导信息。
                    if let Some(tx0) = first_result_tx_opt.take() {
                        let _ = tx0.send(Err("cancelled by disconnect".into()));
                    }
                    // L1：统一由任务退出时发 "disconnected" 事件（去重）。
                    if !disconnected_announced {
                        disconnected_announced = true;
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
                    payload: serde_json::json!({ "endpoint": endpoint }),
                    ts: chrono::Utc::now().timestamp_millis(),
                });

                // Bug C：用带 Authorization 头的 Request 而非裸 URL。
                // connect_async 本身不可取消，包一层 timeout(30s) 并与 cancel_notify
                // 竞速：cancel 先到则丢弃 connect future（select! drop 即取消）并退出，
                // 避免 disconnect 后旧 connect 继续运行产生僵尸连接任务。
                let connect_result: Result<_, AuthTaggedError> = tokio::select! {
                    biased;
                    _ = cancel_notify.notified() => {
                        if let Some(tx0) = first_result_tx_opt.take() {
                            let _ = tx0.send(Err("cancelled by disconnect".into()));
                        }
                        if !disconnected_announced {
                            disconnected_announced = true;
                            let _ = tx.send(IMAdapterEvent {
                                binding_id: binding_id.clone(),
                                kind: "disconnected".into(),
                                payload: serde_json::Value::Null,
                                ts: chrono::Utc::now().timestamp_millis(),
                            });
                        }
                        return;
                    }
                    // 中危修复：8s 而不是 30s。
                    //
                    // 背景：长连接是客户端到 IM 服务器的直连。当对端
                    // （用户的 IM relay / WeCom 长连接网关）进程挂掉但
                    // TCP 还在 accept、TLS 也过得了时，原 30s 握手超时
                    // × 3 次熔断 = 至少 90s 才发现"服务端不会回 101"，
                    // 期间 tokio runtime 一直忙着等 IO，UI 侧 im_send
                    // 也会卡 30s 才给用户报错，体验差。
                    //
                    // 改 8s 后：8s × 2 = 16s 内熔断，给用户的反馈从
                    // "30s 才报错" 降到 "8s 内报错"，且不浪费资源在
                    // 必然失败的握手。
                    r = tokio::time::timeout(Duration::from_secs(8), tokio_tungstenite::connect_async(request.clone())) => {
                        match r {
                            Ok(Ok((s, _))) => Ok(s),
                            Ok(Err(e)) => {
                                // 区分 HTTP 401/403 → 视为凭据失效，不要进入 cooldown
                                // circuit breaker，避免 60s 后继续用坏 token 重试。
                                // tokio_tungstenite 0.21+ 的 Error::Http(Response) 变体
                                // 没有公开的 .status() 方法；通过 Display 字符串判断
                                // （Display 形如 "HTTP error: 401 Unauthorized"）。
                                let msg = e.to_string();
                                let is_auth_failure = msg.contains(" 401 ")
                                    || msg.contains(" 403 ")
                                    || msg.starts_with("HTTP error: 401")
                                    || msg.starts_with("HTTP error: 403");
                                Err(AuthTaggedError { msg, is_auth_failure })
                            }
                            Err(_elapsed) => Err(AuthTaggedError {
                                msg: "connect_async timeout after 8s".to_string(),
                                is_auth_failure: false,
                            }),
                        }
                    }
                };
                let ws_stream = match connect_result {
                    Ok(s) => s,
                    Err(AuthTaggedError { msg: err_msg, is_auth_failure }) => {
                        // 凭据失效：单独发一个事件，前端用来提示用户重新扫码/填 secret。
                        // 不计入 circuit breaker 失败计数，不进入 cooldown——避免 60s
                        // 后用同样的坏 token 继续重试浪费资源；正常 cooldown 60s 后
                        // 用户早就在 UI 上看到了错误，已经手动操作了。
                        if is_auth_failure {
                            let _ = tx.send(IMAdapterEvent {
                                binding_id: binding_id.clone(),
                                kind: "auth_error".into(),
                                payload: serde_json::json!({
                                    "stage": "connect",
                                    "error": err_msg,
                                    "hint": "token_invalid",
                                }),
                                ts: chrono::Utc::now().timestamp_millis(),
                            });
                            // 首次 connect 失败就退出，不再 retry。
                            if !first_reported {
                                if let Some(tx0) = first_result_tx_opt.take() {
                                    let _ = tx0.send(Err(err_msg.clone()));
                                }
                                let mut g = out_tx_holder.lock().await;
                                *g = None;
                                return;
                            }
                        }
                        // Bug F2：递增连续失败计数，达到阈值后进入 cooldown
                        // 退出，避免后台反复重试 + 让 tokio runtime 持续忙碌
                        //（参见上面 circuit breaker 注释）。
                        consecutive_failures = consecutive_failures.saturating_add(1);
                        let _ = tx.send(IMAdapterEvent {
                            binding_id: binding_id.clone(),
                            kind: "error".into(),
                            payload: serde_json::json!({
                                "stage": "connect",
                                "error": err_msg,
                                "consecutive_failures": consecutive_failures,
                            }),
                            ts: chrono::Utc::now().timestamp_millis(),
                        });
                        // Bug F：首次连接失败 → 上报调用方让其返回 Err，并退出 spawn task。
                        // 同时清空 out_tx，让 send() 立即失败而非无限排队（避免内存泄漏）。
                        if !first_reported {
                            // 注意：此处不设 first_reported=true，因为下方立即 return，
                            // 该赋值不会被读到（编译器会警告 unused assignment）。
                            if let Some(tx0) = first_result_tx_opt.take() {
                                let _ = tx0.send(Err(err_msg.clone()));
                            }
                            // 清空 out_tx：调用方会丢弃 adapter，send() 不应继续 enqueue。
                            let mut g = out_tx_holder.lock().await;
                            *g = None;
                            return;
                        }
                        // Bug F2：连续失败达到阈值 → 停止重试，发 circuit_breaker 事件。
                        // 用户可以从日志看到这个渠道被熔断，需手动重启应用或
                        // 修改配置后保存以触发新一轮 connect 尝试。
                        if consecutive_failures >= CIRCUIT_BREAKER_THRESHOLD {
                            tracing::warn!(
                                "[im] channel {} circuit breaker tripped after {} \
                                 consecutive failures (last: {}), exiting reconnect loop. \
                                 Will retry on next config change or app restart.",
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
                            // 不上报调用方（早已返回），直接退出 spawn task。
                            // 关键：清空 out_tx，让后续 send() 立即失败而非无限入队
                            // （后台任务已退出，积压消息永远发不出去 → 内存泄漏）。
                            // 同时 send() 失败会触发 im_bridge 的 remove_and_disconnect
                            // → 下次 get_or_connect 重建 adapter，实现 circuit breaker
                            // 后的自动恢复（无需用户手动重启 / 改配置）。
                            {
                                let mut g = out_tx_holder.lock().await;
                                *g = None;
                            }
                            if !disconnected_announced {
                                disconnected_announced = true;
                                let _ = tx.send(IMAdapterEvent {
                                    binding_id: binding_id.clone(),
                                    kind: "disconnected".into(),
                                    payload: serde_json::Value::Null,
                                    ts: chrono::Utc::now().timestamp_millis(),
                                });
                            }
                            return;
                        }
                        // 后续重连失败 → 退避后重试，不上报调用方（已返回）。
                        // 加 jitter：避免多渠道/多实例在服务端恢复瞬间同时重连
                        // （Thundering Herd），参考 OpenClaw SDK 的 backoff+jitter。
                        tokio::time::sleep(jittered_backoff(backoff)).await;
                        // Bug M：失败翻倍，封顶 60s。
                        backoff = (backoff * 2).min(reconnect_max);
                        continue;
                    }
                };

                // Bug M：成功连接 → 重置退避 + 重置连续失败计数（circuit breaker 解除）。
                backoff = reconnect_base;
                consecutive_failures = 0;

                let _ = tx.send(IMAdapterEvent {
                    binding_id: binding_id.clone(),
                    kind: "connected".into(),
                    payload: serde_json::Value::Null,
                    ts: chrono::Utc::now().timestamp_millis(),
                });

                // Bug F：首次连接成功 → 上报调用方让其返回 Ok。
                if !first_reported {
                    first_reported = true;
                    if let Some(tx0) = first_result_tx_opt.take() {
                        let _ = tx0.send(Ok(()));
                    }
                    // 注意：此处不 return，继续进入收发循环；后续断连由本任务自愈。
                }

                let (mut write, mut read) = ws_stream.split();
                let mut tick = interval(heartbeat);
                tick.tick().await; // 立刻 tick 一次跳过

                loop {
                    // 内层 loop 检查 cancel / 代际：disconnect() 后尽快退出收发循环；
                    // 被新 connect 取代时也退出。
                    if cancel.load(Ordering::Acquire) || generation.load(Ordering::Acquire) != my_gen {
                        if !disconnected_announced {
                            disconnected_announced = true;
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
                        // 0) M3：disconnect() 即时唤醒，不再等 tick(30s)/read 超时(60s)
                        _ = cancel_notify.notified() => {
                            if !disconnected_announced {
                                disconnected_announced = true;
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
                                if !disconnected_announced {
                                    disconnected_announced = true;
                                    let _ = tx.send(IMAdapterEvent {
                                        binding_id: binding_id.clone(),
                                        kind: "disconnected".into(),
                                        payload: serde_json::Value::Null,
                                        ts: chrono::Utc::now().timestamp_millis(),
                                    });
                                }
                                break;
                            }
                        }
                        // 2) 心跳 ping
                        _ = tick.tick() => {
                            if let Err(e) = write.send(WsMessage::Ping(Vec::new())).await {
                                let _ = tx.send(IMAdapterEvent {
                                    binding_id: binding_id.clone(),
                                    kind: "error".into(),
                                    payload: serde_json::json!({ "stage": "ping", "error": e.to_string() }),
                                    ts: chrono::Utc::now().timestamp_millis(),
                                });
                                if !disconnected_announced {
                                    disconnected_announced = true;
                                    let _ = tx.send(IMAdapterEvent {
                                        binding_id: binding_id.clone(),
                                        kind: "disconnected".into(),
                                        payload: serde_json::Value::Null,
                                        ts: chrono::Utc::now().timestamp_millis(),
                                    });
                                }
                                break;
                            }
                        }
                        // 3) 入站消息（Bug E：包一层 read 超时，检测半开连接）
                        incoming = timeout(read_timeout, read.next()) => {
                            match incoming {
                                Ok(Some(Ok(WsMessage::Text(text)))) => {
                                    let parsed: serde_json::Value = serde_json::from_str(&text)
                                        .unwrap_or(serde_json::Value::String(text));
                                    // 扁平化 payload：把入站消息的字段直接提到顶层，
                                    // 让前端能从 event.payload 直接读 target/text/content。
                                    // 中继网关发送格式: { "op":"message", "target":"...", "text":"..." }
                                    // 或: { "from":"...", "content":"...", "from_name":"..." }
                                    let flat_payload = if let Some(obj) = parsed.as_object() {
                                        // 如果已有 target/text 字段，直接用
                                        if obj.contains_key("target") || obj.contains_key("text") || obj.contains_key("content") || obj.contains_key("from") {
                                            parsed.clone()
                                        } else if let Some(data) = obj.get("data") {
                                            // 嵌套在 data 字段内的情况
                                            data.clone()
                                        } else {
                                            parsed.clone()
                                        }
                                    } else {
                                        // 纯文本消息，包装为 text 字段
                                        serde_json::json!({ "text": parsed })
                                    };
                                    let _ = tx.send(IMAdapterEvent {
                                        binding_id: binding_id.clone(),
                                        kind: "message".into(),
                                        payload: flat_payload,
                                        ts: chrono::Utc::now().timestamp_millis(),
                                    });
                                }
                                Ok(Some(Ok(WsMessage::Binary(bin)))) => {
                                    let _ = tx.send(IMAdapterEvent {
                                        binding_id: binding_id.clone(),
                                        kind: "message".into(),
                                        payload: serde_json::json!({
                                            "channel_id": channel_id,
                                            "data": bin,
                                        }),
                                        ts: chrono::Utc::now().timestamp_millis(),
                                    });
                                }
                                Ok(Some(Ok(WsMessage::Close(_)))) | Ok(None) => {
                                    if !disconnected_announced {
                                        disconnected_announced = true;
                                        let _ = tx.send(IMAdapterEvent {
                                            binding_id: binding_id.clone(),
                                            kind: "disconnected".into(),
                                            payload: serde_json::Value::Null,
                                            ts: chrono::Utc::now().timestamp_millis(),
                                        });
                                    }
                                    break;
                                }
                                Ok(Some(Ok(_))) => { /* Pong/Frame ignored */ }
                                Ok(Some(Err(e))) => {
                                    let _ = tx.send(IMAdapterEvent {
                                        binding_id: binding_id.clone(),
                                        kind: "error".into(),
                                        payload: serde_json::json!({ "stage": "read", "error": e.to_string() }),
                                        ts: chrono::Utc::now().timestamp_millis(),
                                    });
                                    break;
                                }
                                // Bug E：read 超时 → 半开连接，主动断开重连。
                                Err(_) => {
                                    let _ = tx.send(IMAdapterEvent {
                                        binding_id: binding_id.clone(),
                                        kind: "error".into(),
                                        payload: serde_json::json!({ "stage": "read_timeout", "timeout_ms": read_timeout.as_millis() as u64 }),
                                        ts: chrono::Utc::now().timestamp_millis(),
                                    });
                                    break;
                                }
                            }
                        }
                    }
                }

                // Bug D：连接断了之后**不**清空 out_tx。
                // 原实现清空 out_tx 会 drop 掉 Sender，导致 spawn task 内部的 out_rx
                // 永久返回 None，重连成功后出站分支被永久禁用，直到下次主动 send
                // 触发 get_or_connect 重建 adapter。现在保留 out_tx，让重连成功后
                // out_rx 继续消费积压的出站消息（Bug 2A 的预期行为）。
                // 仅 disconnect() 会清空 out_tx（用户主动断开）。
                // disconnect() 在等待重连期间被调用 → 退出；
                // 或被新的 connect() 取代（代际变化）→ 退出（Bug 2B）。
                if cancel.load(Ordering::Acquire) || generation.load(Ordering::Acquire) != my_gen {
                    if !disconnected_announced {
                        disconnected_announced = true;
                        let _ = tx.send(IMAdapterEvent {
                            binding_id: binding_id.clone(),
                            kind: "disconnected".into(),
                            payload: serde_json::Value::Null,
                            ts: chrono::Utc::now().timestamp_millis(),
                        });
                    }
                    return;
                }
                // accept-then-close 场景：连接成功后被服务端断开（Close/None、
                // send/ping/read 错误、read 超时等）。此时 backoff 已在连接成功时
                // 被重置为 reconnect_base、consecutive_failures 被重置为 0，若不
                // 递增则服务端反复 accept-then-close 时 circuit breaker 永不触发。
                consecutive_failures = consecutive_failures.saturating_add(1);
                backoff = (backoff * 2).min(reconnect_max);
                if consecutive_failures >= CIRCUIT_BREAKER_THRESHOLD {
                    tracing::warn!(
                        "[im] channel {} circuit breaker tripped after {} \
                         consecutive accept-then-close disconnects, exiting reconnect loop. \
                         Will retry on next config change or app restart.",
                        binding_id, consecutive_failures
                    );
                    let _ = tx.send(IMAdapterEvent {
                        binding_id: binding_id.clone(),
                        kind: "circuit_breaker".into(),
                        payload: serde_json::json!({
                            "consecutive_failures": consecutive_failures,
                            "cooldown_secs": CIRCUIT_BREAKER_COOLDOWN_SECS,
                        }),
                        ts: chrono::Utc::now().timestamp_millis(),
                    });
                    {
                        let mut g = out_tx_holder.lock().await;
                        *g = None;
                    }
                    if !disconnected_announced {
                        disconnected_announced = true;
                        let _ = tx.send(IMAdapterEvent {
                            binding_id: binding_id.clone(),
                            kind: "disconnected".into(),
                            payload: serde_json::Value::Null,
                            ts: chrono::Utc::now().timestamp_millis(),
                        });
                    }
                    return;
                }
                // M3：退避期间也能被 disconnect() 即时唤醒，不再死等 backoff(最坏 60s)。
                tokio::select! {
                    biased;
                    _ = cancel_notify.notified() => {
                        if !disconnected_announced {
                            disconnected_announced = true;
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
            }
        });

        // Bug F：等待首次连接结果，再返回给调用方。
        // M4：RecvError 表示 spawn task 未发送任何结果就退出（被 disconnect
        // 抢赢竞态），返回明确错误而非误导性的 "task panicked"。
        first_result_rx
            .await
            .map_err(|_| "connect cancelled before first result".to_string())?
    }

    async fn disconnect(&self) -> Result<(), String> {
        // 先置 cancel，让后台内外层 loop 在下一次检查时退出（即使
        // out_tx 清空后 recv() 不再返回 Some，任务也不会无限重连）。
        self.cancel.store(true, Ordering::Release);
        let mut g = self.out_tx.lock().await;
        *g = None; // 关闭写队列；后台任务会自然结束本轮循环
        drop(g);
        // M3：notify_waiters 即时唤醒后台 select! 内的等待，
        // 不再等 tick(30s)/read 超时(60s) 才检查 cancel。
        self.cancel_notify.notify_waiters();
        // L1：不主动发 "disconnected" 事件，让后台任务退出时统一发，
        // 避免与任务退出路径重复发事件。
        Ok(())
    }

    async fn send(&self, target: &str, content: &str) -> Result<String, String> {
        let g = self.out_tx.lock().await;
        let tx = g.as_ref().ok_or_else(|| "im long-conn not connected".to_string())?;
        let frame = serde_json::json!({
            "op": "send",
            "target": target,
            "content": content,
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

pub type SharedLongConnAdapter = Arc<LongConnAdapter>;

/// 给退避时长加上 0~25% 的随机抖动，避免多个客户端在服务端恢复瞬间
/// 同时重连造成惊群效应（Thundering Herd）。参考 OpenClaw SDK 与
/// WebSocket 重连最佳实践（exponential backoff + jitter）。
fn jittered_backoff(base: Duration) -> Duration {
    let mut rng = rand::thread_rng();
    let jitter_ms = rng.gen_range(0..=base.as_millis() as u64 / 4);
    base + Duration::from_millis(jitter_ms)
}

/// 从 `ws://host:port/path` 或 `wss://host:port/path` 中提取
/// `host:port` 作为 HTTP `Host` 头的内容。解析失败返回 `None`。
///
/// 不依赖 `url` crate（避免给该文件新增依赖），用最小字符串切分
/// 即可：去掉 scheme，剩下的第一个 `/` 之前就是 authority。
fn url_host(endpoint: &str) -> Option<String> {
    let rest = endpoint
        .strip_prefix("wss://")
        .or_else(|| endpoint.strip_prefix("ws://"))?;
    // 找到 path/query/fragment 起点
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
        assert_eq!(url_host("wss://example.com/relay"), Some("example.com".into()));
        assert_eq!(
            url_host("wss://example.com:8443/path?q=1"),
            Some("example.com:8443".into())
        );
        assert_eq!(url_host("ws://127.0.0.1:9000/"), Some("127.0.0.1:9000".into()));
        assert_eq!(url_host("wss://gateway.com"), Some("gateway.com".into()));
    }

    #[test]
    fn url_host_returns_none_on_invalid() {
        assert_eq!(url_host("http://nope"), None);
        assert_eq!(url_host(""), None);
        assert_eq!(url_host("wss://"), None);
    }
}

/// 内部错误类型，标记 connect 阶段的失败是否属于"凭据失效"（401/403）。
/// 凭据失效时不上 circuit breaker、不入 cooldown，只发一次 auth_error 事件。
struct AuthTaggedError {
    msg: String,
    is_auth_failure: bool,
}
