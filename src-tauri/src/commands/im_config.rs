// Copyright (c) 2026 MeeJoy
//
// IM 渠道配置管理命令。
// 支持企业微信/飞书/钉钉/Webhook/Websocket 等渠道的增删改查与消息发送。
// 配置持久化到本地 app data 目录的 `im_config.json`。

use std::sync::Arc;

use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter, Manager, State};

use crate::agent_infra::mcp::im_bridge::ImBridge;
use crate::hermes::im::adapter_base::{IMAdapter, IMBinding, IMProvider};
use crate::hermes::im::channel_registry::{SharedAdapterPool, SharedChannelRegistry};
use crate::hermes::im::im_endpoints::ImChannelKind;

const CONFIG_FILE_NAME: &str = "im_config.json";

// ── app_secret 字段级加密（AES-256-GCM + 机器绑定密钥）──
// 落盘时加密 app_secret，读取时解密。其他字段（app_id/open_id）不加密。
// 密钥由固定应用 secret + hardware_fingerprint 经 Argon2id 派生，
// 机器绑定：不同机器密钥不同，拷贝 im_config.json 到其他机器无法解密。
use std::sync::OnceLock as _OnceLock;
use crate::crypto::storage::EncryptedStorage as _EncryptedStorage;

static IM_SECRET_STORAGE: _OnceLock<Option<_EncryptedStorage>> = _OnceLock::new();

fn im_secret_storage() -> Option<&'static _EncryptedStorage> {
    IM_SECRET_STORAGE.get_or_init(|| {
        let fingerprint = crate::commands::hardware::compute_hardware_fingerprint();
        match _EncryptedStorage::derive("tupai_im_secret_v1", &fingerprint) {
            Ok(storage) => Some(storage),
            Err(e) => {
                tracing::error!("[im_config] IM secret storage derivation failed: {}", e);
                None
            }
        }
    }).as_ref()
}

/// 加密 app_secret。已加密（enc:v1: 前缀）的不重复加密。
/// 加密失败时回退明文（比崩溃好）。
fn encrypt_app_secret(plaintext: &str) -> String {
    if plaintext.starts_with("enc:v1:") {
        return plaintext.to_string();
    }
    match im_secret_storage() {
        Some(storage) => match storage.encrypt_base64(plaintext.as_bytes()) {
            Ok(enc) => format!("enc:v1:{}", enc),
            Err(e) => {
                tracing::warn!("[im_config] encrypt app_secret failed: {}", e);
                plaintext.to_string()
            }
        },
        None => plaintext.to_string(),
    }
}

/// 解密 app_secret。非 enc:v1: 前缀的当明文返回（旧数据自动兼容）。
fn decrypt_app_secret(value: &str) -> String {
    if let Some(enc) = value.strip_prefix("enc:v1:") {
        match im_secret_storage() {
            Some(storage) => match storage.decrypt_base64_string(enc) {
                Ok(dec) => dec.as_str().to_string(),
                Err(e) => {
                    tracing::warn!("[im_config] decrypt app_secret failed: {}", e);
                    value.to_string()
                }
            },
            None => value.to_string(),
        }
    } else {
        value.to_string()
    }
}

/// 配置文件读-改-写锁。`im_config_set` / `im_config_remove` 在
/// `load_config` 与 `save_config` 之间持有此锁，避免并发更新丢失。
pub type ImConfigLock = Arc<tokio::sync::Mutex<()>>;

/// 文件级锁，保护 `load_config` / `save_config` 的底层文件 I/O。
/// `im_config_get` / `im_send` 等命令未注入 `ImConfigLock`（避免改动
/// Tauri 命令签名影响 lib.rs 注册），用此 static 锁确保读/写互斥：
/// 读时持锁避免读到 save 中途的半截文件；写时持锁避免并发写覆盖。
/// 注意：`im_config_set` / `im_config_remove` 持有 `ImConfigLock` 期间
/// 会多次获取释放此锁（load 一次 + save 一次），不会死锁（每次获取后
/// 立即释放，不跨 load-modify-save 持有）。
static CONFIG_FILE_LOCK: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct IMSyncMessage {
    pub source: String,
    pub title: String,
    pub content: String,
    #[serde(default)]
    pub channel_id: Option<String>,
    /// M1：同步发送的目标接收方（user_id / room_id / open_id）。
    /// 为空时 im_sync_send 返回明确错误，不再对所有渠道传 target=""。
    #[serde(default)]
    pub target: Option<String>,
    #[serde(default)]
    pub metadata: serde_json::Value,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct ImChannelEntry {
    pub id: String,
    pub name: String,
    pub provider: IMProvider,
    #[serde(default)]
    pub metadata: serde_json::Value,
    #[serde(default)]
    pub enabled: bool,
    /// 后端自动回复开关（默认开）。开启时，入站消息由 Rust 后端直接
    /// 调 LLM 并回发，不依赖前端窗口挂载。前端检测到该渠道开启后跳过
    /// 自己的自动回复，避免双回复。
    #[serde(default = "default_true")]
    pub auto_reply: bool,
}

fn default_true() -> bool {
    true
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct ImConfigSnapshot {
    pub channels: Vec<ImChannelEntry>,
}

fn config_path(app: &AppHandle) -> Result<std::path::PathBuf, String> {
    let dir = app
        .path()
        .app_data_dir()
        .map_err(|e| format!("app_data_dir failed: {}", e))?;
    Ok(dir.join(CONFIG_FILE_NAME))
}

pub(crate) async fn load_config(app: &AppHandle) -> ImConfigSnapshot {
    // 中危修复：持文件级锁读取，避免与 save_config 的原子替换竞争，
    // 防止读到 save 中途的半截 JSON（即使原子 rename 也有窗口期，
    // 持锁序列化读/写最稳妥）。
    let _guard = CONFIG_FILE_LOCK.lock().await;
    let path = match config_path(app) {
        Ok(p) => p,
        Err(e) => {
            tracing::warn!("[im_config] cannot resolve config path: {}", e);
            return ImConfigSnapshot::default();
        }
    };
    match tokio::fs::read_to_string(&path).await {
        Ok(text) => serde_json::from_str(&text).unwrap_or_default(),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => ImConfigSnapshot::default(),
        Err(e) => {
            tracing::warn!("[im_config] read {} failed: {}", path.display(), e);
            ImConfigSnapshot::default()
        }
    }
}

/// 加载持久化配置并对所有 channel 的 metadata 做字段解密。
/// 解密 `app_secret` 和 `secret` 两个字段：若值以 `enc:v1:` 开头则用
/// EncryptedStorage 解密，否则原样返回（旧明文数据自动兼容）。
/// 供 init_im_channels / im_send / im_sync_send / im_config_get 等需要
/// 拿到明文凭据的运行时路径调用；落盘仍由 load_config（读出加密串）处理。
async fn load_config_decrypted(app: &AppHandle) -> ImConfigSnapshot {
    let mut config = load_config(app).await;
    for ch in &mut config.channels {
        if let Some(obj) = ch.metadata.as_object_mut() {
            // app_secret
            if let Some(secret) = obj.get("app_secret").and_then(|v| v.as_str()).map(|s| s.to_string()) {
                let decrypted = decrypt_app_secret(&secret);
                if decrypted != secret {
                    obj.insert("app_secret".to_string(), serde_json::Value::String(decrypted));
                }
            }
            // Bug 2: secret 字段（用户可能用 "appId:appSecret" 拼接格式存储凭据）
            // 也需解密，与 save 时的加密对称。
            if let Some(secret) = obj.get("secret").and_then(|v| v.as_str()).map(|s| s.to_string()) {
                let decrypted = decrypt_app_secret(&secret);
                if decrypted != secret {
                    obj.insert("secret".to_string(), serde_json::Value::String(decrypted));
                }
            }
            // token 字段（通用 QR 扫码 weixin/qqbot/wecom 渠道的凭据），
            // 与 app_secret/secret 对称加解密，避免凭据明文落盘。
            if let Some(token) = obj.get("token").and_then(|v| v.as_str()).map(|s| s.to_string()) {
                let decrypted = decrypt_app_secret(&token);
                if decrypted != token {
                    obj.insert("token".to_string(), serde_json::Value::String(decrypted));
                }
            }
        }
    }
    config
}

async fn save_config(app: &AppHandle, config: &ImConfigSnapshot) -> Result<(), String> {
    // 中危修复：持文件级锁写入，避免与 load_config 并发竞争。
    // 同时改为原子写入：先写 im_config.json.tmp 再 rename 原子替换，
    // 防止写中途崩溃导致 im_config.json 损坏（半截 JSON）。
    let _guard = CONFIG_FILE_LOCK.lock().await;
    let path = config_path(app)?;
    if let Some(parent) = path.parent() {
        tokio::fs::create_dir_all(parent)
            .await
            .map_err(|e| format!("create config dir failed: {}", e))?;
    }
    let tmp = path.with_extension("json.tmp");
    let text = serde_json::to_string_pretty(config).map_err(|e| format!("serialize config failed: {}", e))?;
    tokio::fs::write(&tmp, &text)
        .await
        .map_err(|e| format!("write config tmp failed: {}", e))?;
    tokio::fs::rename(&tmp, &path)
        .await
        .map_err(|e| format!("rename config tmp->final failed: {}", e))?;
    Ok(())
}

/// 把持久化配置项 `ImChannelEntry` 转为运行时 `IMBinding`。
/// `provider_tag` 只调用一次（Bug 13: 原先在 build_adapter 中调用了两次）。
///
/// 注意：`LongConnAdapter::new()` 从 `binding.metadata` 读取 endpoint/secret，
/// 因此这里必须把 `entry.provider` 中的 endpoint/secret 合并进 metadata，
/// 否则所有渠道都会得到空 endpoint，连接失败（Bug 1）。
fn entry_to_binding(entry: &ImChannelEntry) -> IMBinding {
    let provider_tag_str = provider_tag(&entry.provider);
    let mut metadata = entry.metadata.clone();
    // 配置无 metadata 字段时 #[serde(default)] 使默认值为 Value::Null，
    // 此时下方三处 if let Value::Object 不匹配会导致 endpoint/secret/url
    // 全部被丢弃（连接失败）。这里先把 Null 等非 Object 值归一化为空 Object。
    if !metadata.is_object() {
        metadata = serde_json::Value::Object(serde_json::Map::new());
    }
    // 将 provider 对象中的 endpoint/secret 合并到 metadata，供 LongConnAdapter 读取。
    // `entry.provider` 是 `IMProvider` 枚举，先序列化为 JSON Value 再提取字段，
    // 这样可统一覆盖 LongConn / WeCom / Feishu / DingTalk 等所有带 endpoint 的变体。
    if let Ok(serde_json::Value::Object(provider_obj)) = serde_json::to_value(&entry.provider) {
        if let Some(endpoint) = provider_obj.get("endpoint").and_then(|v| v.as_str()) {
            if !endpoint.is_empty() {
                if let serde_json::Value::Object(ref mut meta_map) = metadata {
                    meta_map.insert(
                        "endpoint".to_string(),
                        serde_json::Value::String(endpoint.to_string()),
                    );
                }
            }
        }
        if let Some(secret) = provider_obj.get("secret").and_then(|v| v.as_str()) {
            if !secret.is_empty() {
                if let serde_json::Value::Object(ref mut meta_map) = metadata {
                    meta_map.insert(
                        "secret".to_string(),
                        serde_json::Value::String(secret.to_string()),
                    );
                }
            }
        }
        // M2：WebSocket { url } 变体的 url 也需合并进 metadata，
        // 供 LongConnAdapter::new() 读取（否则 WS 渠道得到空 endpoint）。
        if let Some(url) = provider_obj.get("url").and_then(|v| v.as_str()) {
            if !url.is_empty() {
                if let serde_json::Value::Object(ref mut meta_map) = metadata {
                    meta_map.insert(
                        "url".to_string(),
                        serde_json::Value::String(url.to_string()),
                    );
                }
            }
        }
    }
    IMBinding {
        id: entry.id.clone(),
        provider: provider_tag_str,
        channel_id: entry.name.clone(),
        metadata,
    }
}

fn provider_tag(provider: &IMProvider) -> String {
    serde_json::to_value(provider)
        .ok()
        .and_then(|v| v.get("type").and_then(|t| t.as_str().map(str::to_string)))
        .unwrap_or_else(|| "custom".to_string())
}

/// Spawn 一个 forwarder 任务，订阅 adapter 的入站事件并 `app.emit("im_adapter_event", ev)`
/// 给前端。任务会在 adapter 被销毁（broadcast Sender drop）或 AppHandle 失效时自动退出。
///
/// **Bug B 修复**：原实现只把入站消息推进 broadcast::Sender，没有任何消费者订阅，
/// 导致 IM 渠道入站消息（用户在企业微信里 @ 机器人发来的消息）被静默丢弃，
/// LLM 永远收不到入站触发。本 forwarder 把 broadcast 桥接到 Tauri 事件，
/// 前端（WecomPage）通过 `listen('im_adapter_event')` 接收后调 `sendToLLM`
/// 触发一次 Hermes 会话（与现有「前端驱动 LLM」架构一致）。
///
/// 重复 spawn 安全：旧 adapter 被 `pool.replace` / `pool.remove_and_disconnect`
/// 后，其 `tx` (broadcast Sender) 被 drop，旧 forwarder 的 `rx.recv()` 返回 Err
/// 自动退出。新 adapter 由调用方重新 spawn 一份 forwarder。
///
/// TODO(低): spawn_inbound_forwarder 在 `LongConnAdapter::connect()` 返回后才
/// `adapter.subscribe()`，若 adapter 在 connect 完成前就已发出首屏事件（如
/// 服务端 hello / session id），这些事件在 subscribe 之前被 broadcast 丢弃，
/// forwarder 永远收不到。彻底修复需要改 `LongConnAdapter::connect` 返回
/// receiver（或在 connect 前 subscribe），但 `LongConnAdapter` 在
/// `websocket_adapter.rs`，不归本批次修改范围。当前影响：仅丢失连接建立瞬间
/// 的首屏事件，后续消息正常。
pub(crate) fn spawn_inbound_forwarder(app: AppHandle, adapter: Arc<dyn IMAdapter>, channel_id: String) {
    let _ = channel_id;
    let mut rx = adapter.subscribe();
    // adapter Arc 在 subscribe() 后即不再需要，显式 drop 之，确保 spawn 出去的
    // task 仅持有 broadcast::Receiver 与 AppHandle，不持有整个 Arc<dyn IMAdapter>，
    // 避免 adapter 被 pool 移除后因 task 仍持 Arc 而无法释放（内存泄漏 / 僵尸任务）。
    drop(adapter);
    tauri::async_runtime::spawn(async move {
        loop {
            match rx.recv().await {
                Ok(ev) => { let _ = app.emit("im_adapter_event", ev); }
                // 落后可恢复，继续接收后续消息，不退出。
                Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                // 所有 Sender 已 drop（adapter 被销毁）→ 退出。
                Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
            }
        }
    });
}

/// 启动 IM 渠道入站处理：forwarder（推给前端做镜像）+ 后端自动回复循环。
///
/// 当 `entry.auto_reply` 开启时，额外 spawn `auto_reply::spawn_inbound_reply_loop`
/// 让后端直接调 LLM 并回发（不依赖前端窗口挂载）。device_token 从
/// `HermesAppState` 取（Arc<RwLock<Option<String>>>），后端 loop 每次调用
/// LLM 前读最新值。
pub(crate) fn spawn_inbound_handlers(
    app: AppHandle,
    adapter: Arc<dyn IMAdapter>,
    channel_id: String,
    auto_reply: bool,
) {
    spawn_inbound_forwarder(app.clone(), adapter.clone(), channel_id.clone());
    if auto_reply {
        if let Some(state) = app.try_state::<crate::hermes::HermesAppState>() {
            let device_token = state.device_token.clone();
            crate::hermes::im::auto_reply::spawn_inbound_reply_loop(
                app,
                adapter,
                channel_id,
                device_token,
            );
        } else {
            tracing::warn!(
                "[im_config] HermesAppState not managed, skip backend auto-reply for channel {}",
                channel_id
            );
        }
    }
}

/// 应用启动时初始化 IM 渠道：从配置文件加载已保存的渠道，
/// 注册到 ChannelRegistry 并加入 im_bridge 白名单，并通过 AdapterPool
/// 预连接。日志计数为实际成功 connect 的数量。
///
/// Bug F2 修复：之前的实现用 `pool.get_or_connect(binding).await` 同步等待
/// 每个渠道握手完成，单个渠道 connect 内层 timeout 30s。如果用户保存的
/// 渠道 endpoint 不可达（典型：服务端反复返回 "Missing sec-websocket-key"），
/// 整个 setup 阶段会被卡 30s×N 渠道。同时坏渠道的 spawn task 在后台无限
/// 退避重试又占用 tokio runtime 工作线程，主线程 invoke 排队 → 用户感觉
/// "卡死"。
///
/// 修复：把 connect 移到 spawn task 中，init_im_channels 立即返回。用户的
/// 坏渠道在后台被 circuit breaker 自动熔断（websocket_adapter.rs
/// CIRCUIT_BREAKER_THRESHOLD=3），下次重启或用户 im_config_set 时再尝试。
/// 成功路径不变：connect 成功的 adapter 仍会 spawn forwarder 把入站消息
/// 推给前端。
pub async fn init_im_channels(
    app: &AppHandle,
    registry: SharedChannelRegistry,
    bridge: Arc<ImBridge>,
    pool: SharedAdapterPool,
) {
    let config = load_config_decrypted(app).await;
    let total_enabled = config.channels.iter().filter(|c| c.enabled).count();
    if total_enabled == 0 {
        tracing::info!("[im_config] no enabled channels, skipping init");
        return;
    }
    tracing::info!(
        "[im_config] spawning background init for {} enabled channels (non-blocking)",
        total_enabled
    );
    for entry in &config.channels {
        if !entry.enabled {
            continue;
        }
        let binding = entry_to_binding(entry);
        // 注册 + 白名单立即生效（这些操作都是本地内存操作，<1ms）
        registry.bind("default", binding.clone()).await;
        bridge.add_to_whitelist(&entry.id).await;

        // 后台异步 connect + 入池 + spawn forwarder。
        // 出错不阻塞 setup 阶段：circuit breaker 会在 3 次失败后停止重试。
        let app_clone = app.clone();
        let pool_clone = pool.clone();
        let entry_id = entry.id.clone();
        let auto_reply = entry.auto_reply;
        tokio::spawn(async move {
            match pool_clone.get_or_connect(binding).await {
                Ok(adapter) => {
                    tracing::info!("[im_config] channel {} connected", entry_id);
                    spawn_inbound_handlers(app_clone, adapter, entry_id, auto_reply);
                }
                Err(e) => {
                    tracing::warn!(
                        "[im_config] channel {} connect failed: {} (will retry via circuit breaker)",
                        entry_id, e
                    );
                }
            }
        });
    }
}

/// 加载持久化配置并同步到 ChannelRegistry。
#[tauri::command]
pub async fn im_config_get(
    app: AppHandle,
    _registry: State<'_, SharedChannelRegistry>,
) -> Result<ImConfigSnapshot, String> {
    // Bug 1: 复用 load_config_decrypted，统一解密 app_secret + secret 字段，
    // 避免与 init_im_channels / im_send 等路径的解密逻辑分叉。
    let config = load_config_decrypted(&app).await;
    Ok(config)
}

/// 保存/更新单个渠道配置。
#[tauri::command]
pub async fn im_config_set(
    app: AppHandle,
    registry: State<'_, SharedChannelRegistry>,
    bridge: State<'_, Arc<ImBridge>>,
    pool: State<'_, SharedAdapterPool>,
    config_lock: State<'_, ImConfigLock>,
    entry: ImChannelEntry,
) -> Result<ImConfigSnapshot, String> {
    if entry.id.is_empty() {
        return Err("channel id cannot be empty".to_string());
    }
    // 【铁律】强制覆盖 endpoint：所有 IM 长连接地址写死在 im_endpoints.rs
    // 抄自 openclaw 官方直连 URL。前端用户永远无法填、也无法绕过；
    // 对于没有官方 WS 长连接的渠道（企微/微信/QQ/WhatsApp/通用长连接），
    // 保留 entry.provider.endpoint 透传（用户自建网关）。
    //
    // 飞书 / Lark：写死的是 HTTP 引导 URL
    //   (https://open.feishu.cn/open-apis/connection/v1/connect)，
    //   `LongConnAdapter` 启动时先 POST 拿动态 WSS URL，再 dial。
    // 钉钉 Stream：写死的是直连 WSS URL
    //   (wss://wss-open-connection.dingtalk.com/connect)。
    let mut entry = entry;
    let provider_type = provider_tag(&entry.provider);
    if let Some(kind) = ImChannelKind::parse(&provider_type) {
        // 飞书 / 飞书国际版：HTTP 引导 URL
        if let Some(bootstrap) = kind.feishu_bootstrap_url() {
            tracing::info!(
                "[im_config_set] overriding bootstrap url for channel={} kind={:?} -> {}",
                entry.id, kind, bootstrap
            );
            entry.provider = override_provider_endpoint(&entry.provider, bootstrap);
        // 钉钉 Stream：直连 WSS URL
        } else if let Some(wss) = kind.dingtalk_wss_url() {
            tracing::info!(
                "[im_config_set] overriding wss url for channel={} kind={:?} -> {}",
                entry.id, kind, wss
            );
            entry.provider = override_provider_endpoint(&entry.provider, wss);
        // 企业微信智能机器人：直连 WSS URL（aibot_subscribe 协议）
        } else if let Some(wss) = kind.wecom_wss_url() {
            tracing::info!(
                "[im_config_set] overriding wecom wss url for channel={} kind={:?} -> {}",
                entry.id, kind, wss
            );
            entry.provider = override_provider_endpoint(&entry.provider, wss);
        // Telegram Bot API 基址
        } else if let Some(api_base) = kind.telegram_api_base() {
            tracing::info!(
                "[im_config_set] overriding telegram api base for channel={} kind={:?} -> {}",
                entry.id, kind, api_base
            );
            entry.provider = override_provider_endpoint(&entry.provider, api_base);
        }
        // Weixin/QqBot/WhatsApp/LongConn：用户自建网关，保留用户填的 endpoint。
    }
    // 持有 config_lock 保护 load → modify → save 读改写原子性，
    // 避免 im_config_set / im_config_remove 并发时丢失更新。
    let config = {
        let _guard = config_lock.lock().await;
        let mut config = load_config(&app).await;
        config.channels.retain(|c| c.id != entry.id);
        // 加密 app_secret 后写入文件（机器绑定 AES-256-GCM）
        let mut enc_entry = entry.clone();
        if let Some(obj) = enc_entry.metadata.as_object_mut() {
            // Bug 2: 加密 app_secret 和 secret 两个字段。
            // secret 用于 "appId:appSecret" 拼接格式存储凭据的场景，
            // 加密/解密需对称：save 时加密 secret，load_config_decrypted 时解密 secret。
            if let Some(secret) = obj.get("app_secret").and_then(|v| v.as_str()).map(|s| s.to_string()) {
                if !secret.starts_with("enc:v1:") {
                    obj.insert("app_secret".to_string(), serde_json::Value::String(encrypt_app_secret(&secret)));
                }
            }
            if let Some(secret) = obj.get("secret").and_then(|v| v.as_str()).map(|s| s.to_string()) {
                if !secret.starts_with("enc:v1:") {
                    obj.insert("secret".to_string(), serde_json::Value::String(encrypt_app_secret(&secret)));
                }
            }
            // token 字段（通用 QR 扫码 weixin/qqbot/wecom 渠道的凭据），
            // 与 app_secret/secret 对称加密，避免凭据明文落盘。
            if let Some(token) = obj.get("token").and_then(|v| v.as_str()).map(|s| s.to_string()) {
                if !token.starts_with("enc:v1:") {
                    obj.insert("token".to_string(), serde_json::Value::String(encrypt_app_secret(&token)));
                }
            }
        }
        config.channels.push(enc_entry);
        save_config(&app, &config).await?;
        config
    };

    // 同步到 registry:先解绑再重新绑定
    registry.unbind("default", &entry.id).await;
    if entry.enabled {
        let binding = entry_to_binding(&entry);
        registry.bind("default", binding.clone()).await;
        // 将渠道加入 im_bridge 白名单
        bridge.add_to_whitelist(&entry.id).await;
        // 更新 AdapterPool：先 disconnect 旧适配器，再插入新的并 connect。
        // Bug B：connect 成功后为新 adapter spawn forwarder（旧 forwarder 因旧 adapter
        // 的 broadcast Sender drop 而自动退出）。
        match pool.replace(binding).await {
            Ok(adapter) => {
                let auto_reply = entry.auto_reply;
                spawn_inbound_handlers(app.clone(), adapter, entry.id.clone(), auto_reply)
            }
            Err(e) => tracing::warn!("[im_config] channel {} connect failed: {}", entry.id, e),
        }
    } else {
        // 渠道被禁用：从 pool 移除并 disconnect 旧适配器。
        pool.remove_and_disconnect(&entry.id).await;
    }
    Ok(config)
}

/// 删除单个渠道配置。
#[tauri::command]
pub async fn im_config_remove(
    app: AppHandle,
    registry: State<'_, SharedChannelRegistry>,
    bridge: State<'_, Arc<ImBridge>>,
    _pool: State<'_, SharedAdapterPool>,
    config_lock: State<'_, ImConfigLock>,
    id: String,
) -> Result<ImConfigSnapshot, String> {
    // 持有 config_lock 保护 load → modify → save 读改写原子性。
    let config = {
        let _guard = config_lock.lock().await;
        let mut config = load_config(&app).await;
        config.channels.retain(|c| c.id != id);
        save_config(&app, &config).await?;
        config
    };
    registry.unbind("default", &id).await;
    // 中危修复：改用 revoke_channel 而非 remove_from_whitelist。
    // revoke_channel 会：移 confirmed + 移白名单 + 清 pending 队列 + disconnect adapter。
    // 原 remove_from_whitelist 只删白名单，pending 队列残留导致已入队的待确认请求
    // 不会被清理，且若 channel 之前已 confirm，confirmed 集合也不会清理。
    bridge.revoke_channel(&id).await;
    // revoke_channel 内部已调 pool.remove_and_disconnect，此处不再重复。
    Ok(config)
}

/// 通过指定渠道发送消息。`channel_id` 为渠道 entry.id。
#[tauri::command]
pub async fn im_send(
    app: AppHandle,
    _registry: State<'_, SharedChannelRegistry>,
    pool: State<'_, SharedAdapterPool>,
    channel_id: String,
    target: String,
    content: String,
) -> Result<String, String> {
    let config = load_config_decrypted(&app).await;
    let entry = config
        .channels
        .into_iter()
        .find(|c| c.id == channel_id && c.enabled)
        .ok_or_else(|| format!("channel {} not found or disabled", channel_id))?;
    // 从 AdapterPool 取已连接适配器（命中则复用，否则构造并 connect）。
    let binding = entry_to_binding(&entry);
    let adapter = pool
        .get_or_connect(binding)
        .await
        .map_err(|e| format!("no adapter for this provider: {}", e))?;
    match adapter.send(&target, &content).await {
        Ok(r) => {
            // 记录出站消息供回声去重（后端 auto_reply 循环据此跳过回显，
            // 防止前端 imSend 的消息被 IM 服务器回声后触发后端自动回复）。
            crate::hermes::im::auto_reply::record_outbound(&channel_id, &target, &content);
            Ok(r)
        }
        Err(e) => {
            // Bug 3: "not implemented" 表示适配器能力缺失（如该渠道不支持 send），
            // 不是连接故障，不应 disconnect——disconnect 会触发无意义的重连，
            // 浪费资源且能力缺失的渠道重连后也永远无法 send。
            // 仅对真正的连接/发送故障才 disconnect，以停止后台重连僵尸任务。
            if !e.to_lowercase().contains("not implemented") {
                // S1：send 失败后必须 disconnect 才能停止后台 spawn 的重连任务，
                // 否则 remove 只取出 Arc，cancel 永远不被置 true，任务无限重连成僵尸。
                pool.remove_and_disconnect(&channel_id).await;
            }
            Err(e)
        }
    }
}

/// 统一消息同步：把应用内事件自动转发到已启用/已绑定的本地 IM 渠道。
/// 如果 `msg.channel_id` 为 None，则向所有已启用渠道广播；否则只发给指定渠道。
#[tauri::command]
pub async fn im_sync_send(
    app: AppHandle,
    pool: State<'_, SharedAdapterPool>,
    msg: IMSyncMessage,
) -> Result<Vec<String>, String> {
    // M1：校验 target 非空，不再对所有渠道传 target=""。
    let target = msg.target.as_deref().unwrap_or("").trim();
    if target.is_empty() {
        return Err("target is required for sync send".to_string());
    }
    let config = load_config_decrypted(&app).await;
    let enabled: Vec<ImChannelEntry> = config.channels.into_iter().filter(|c| c.enabled).collect();
    if enabled.is_empty() {
        return Err("no enabled im channels".to_string());
    }
    let targets: Vec<ImChannelEntry> = match &msg.channel_id {
        Some(id) => enabled.into_iter().filter(|c| &c.id == id).collect(),
        None => enabled,
    };
    if targets.is_empty() {
        return Err(format!(
            "channel {} not found or disabled",
            msg.channel_id.unwrap_or_default()
        ));
    }
    let full_content = format!("[{}] {}\n{}", msg.source, msg.title, msg.content);
    let mut sent = Vec::new();
    let mut last_err: Option<String> = None;
    for entry in targets {
        let binding = entry_to_binding(&entry);
        // 从 AdapterPool 取已连接适配器（命中则复用，否则构造并 connect）。
        match pool.get_or_connect(binding).await {
            Ok(adapter) => match adapter.send(target, &full_content).await {
                Ok(_) => {
                    crate::hermes::im::auto_reply::record_outbound(&entry.id, target, &full_content);
                    sent.push(entry.id)
                }
                Err(e) => last_err = Some(e),
            },
            Err(e) => last_err = Some(format!("no adapter for channel {}: {}", entry.id, e)),
        }
    }
    if sent.is_empty() {
        return Err(last_err.unwrap_or_else(|| "no channel sent".to_string()));
    }
    Ok(sent)
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SkillParamFieldInfo {
    pub name: String,
    #[serde(rename = "type")]
    pub field_type: String,
    pub description: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub enum_values: Option<Vec<String>>,
    pub current_value: Option<serde_json::Value>,
}

/// 发送技能参数确认消息到 IM 渠道。
/// 前端在弹出 SkillParamModal 时调用，用户可在 IM 内回复确认/跳过。
#[tauri::command]
pub async fn im_send_skill_params(
    app: AppHandle,
    pool: State<'_, SharedAdapterPool>,
    channel_id: String,
    target: String,
    skill_name: String,
    skill_description: String,
    fields: Vec<SkillParamFieldInfo>,
    correlation_id: String,
) -> Result<String, String> {
    let config = load_config_decrypted(&app).await;
    let entry = config
        .channels
        .into_iter()
        .find(|c| c.id == channel_id && c.enabled)
        .ok_or_else(|| format!("channel {} not found or disabled", channel_id))?;

    // 构建消息
    let mut msg = format!("🎯 技能确认: {}\n", skill_name);
    if !skill_description.is_empty() {
        msg.push_str(&format!("\n{}\n", skill_description));
    }
    if !fields.is_empty() {
        msg.push_str("\n参数:\n");
        for f in &fields {
            let val = match &f.current_value {
                Some(v) => v.to_string(),
                None => "(待填写)".to_string(),
            };
            let desc = f.description.as_deref().unwrap_or("");
            msg.push_str(&format!("  • {}: {}  {}\n", f.name, val, desc));
        }
    }
    msg.push_str(&format!(
        "\n---\n确认码: {}\n回复【确认执行】确认，【跳过】跳过",
        correlation_id
    ));

    let binding = entry_to_binding(&entry);
    let adapter = pool
        .get_or_connect(binding)
        .await
        .map_err(|e| format!("no adapter: {}", e))?;
    adapter.send(&target, &msg).await
}

/// 列出当前 registry 中已绑定的渠道（运行时视角）。
#[tauri::command]
pub async fn im_channels(registry: State<'_, SharedChannelRegistry>) -> Result<Vec<IMBinding>, String> {
    Ok(registry.bindings_for("default").await)
}

/// 【铁律 helper】强制覆盖 IMProvider 的 endpoint 字段为后端硬编码的
/// 官方直连 URL。对于所有硬编码渠道（飞书/钉钉）必须用这个函数。
/// 对于用户自建网关的渠道（long_conn/weixin/qqbot/whatsapp/wecom），
/// caller 不调用本函数，直接保留 entry.provider.endpoint。
fn override_provider_endpoint(provider: &IMProvider, hardcoded: &str) -> IMProvider {
    match provider {
        IMProvider::LongConn { secret, .. } => IMProvider::LongConn {
            endpoint: hardcoded.to_string(),
            secret: secret.clone(),
        },
        IMProvider::WebSocket { .. } => IMProvider::WebSocket {
            url: hardcoded.to_string(),
        },
        IMProvider::WeCom { secret, .. } => IMProvider::WeCom {
            endpoint: hardcoded.to_string(),
            secret: secret.clone(),
        },
        IMProvider::Feishu { secret, .. } => IMProvider::Feishu {
            endpoint: hardcoded.to_string(),
            secret: secret.clone(),
        },
        IMProvider::FeishuLark { secret, .. } => IMProvider::FeishuLark {
            endpoint: hardcoded.to_string(),
            secret: secret.clone(),
        },
        IMProvider::DingTalk { secret, .. } => IMProvider::DingTalk {
            endpoint: hardcoded.to_string(),
            secret: secret.clone(),
        },
        IMProvider::Telegram { secret, .. } => IMProvider::Telegram {
            endpoint: hardcoded.to_string(),
            secret: secret.clone(),
        },
        IMProvider::Weixin { secret, .. } => IMProvider::Weixin {
            endpoint: hardcoded.to_string(),
            secret: secret.clone(),
        },
        IMProvider::QqBot { secret, .. } => IMProvider::QqBot {
            endpoint: hardcoded.to_string(),
            secret: secret.clone(),
        },
        // 兜底：如果 enum 新增了变体没匹配上，保留原值（不会 panic）
        _ => provider.clone(),
    }
}

// ── 前端桥接渠道注册表 ──
// 前端 TupaiChatScene 勾选的桥接渠道集合。后端 inbound auto_reply 循环
// 看到渠道在此集合中时**跳过**回复（由前端 runMainLLM 带技能上下文驱动），
// 避免双回复 + 避免丢失技能会话内容。集合以 channel_id → 最近心跳时刻 存储。
//
// 心跳 TTL：前端必须周期性调用 `im_set_bridged` 刷新（全量替换 + 刷新时间戳）。
// 若前端窗口被强制关闭（webview 销毁、React cleanup 未执行），心跳停止，
// 超过 TTL 后视为未桥接，后端自动回复自动恢复接管，避免渠道永久静默。
pub type SharedBridgedChannels = Arc<std::sync::RwLock<std::collections::HashMap<String, std::time::Instant>>>;

/// 桥接心跳 TTL：超过该时长无心跳则视该渠道已失联，后端恢复自动回复。
pub const BRIDGE_HEARTBEAT_TTL_SECS: u64 = 90;

pub fn new_shared_bridged_channels() -> SharedBridgedChannels {
    Arc::new(std::sync::RwLock::new(std::collections::HashMap::new()))
}

/// 前端上报当前桥接的 IM 渠道集合（全量覆盖 + 刷新心跳）。
/// 前端在 bridgedChannelIds 变化时调用，窗口卸载/会话切换时传空数组清除；
/// 前端挂载期间周期性调用（心跳）保持桥接状态不因 TTL 过期。
/// 后端 inbound auto_reply 循环根据此集合决定是否跳过某渠道的回复。
#[tauri::command]
pub async fn im_set_bridged(
    app: AppHandle,
    channels: Vec<String>,
) -> Result<(), String> {
    let shared = app
        .try_state::<SharedBridgedChannels>()
        .ok_or_else(|| "bridged channels state not initialized".to_string())?;
    let mut guard = shared
        .write()
        .map_err(|e| format!("bridged channels lock poisoned: {}", e))?;
    // 清理已超时的过期条目（防御：即使前端停止心跳也不残留）。
    let now = std::time::Instant::now();
    guard.retain(|_, ts| now.duration_since(*ts) < std::time::Duration::from_secs(BRIDGE_HEARTBEAT_TTL_SECS));
    // 全量替换：新集合刷新心跳，移除未包含的渠道。
    guard.clear();
    for c in &channels {
        guard.insert(c.clone(), now);
    }
    drop(guard);
    tracing::debug!("[im_config] im_set_bridged: {} channels", channels.len());
    Ok(())
}
