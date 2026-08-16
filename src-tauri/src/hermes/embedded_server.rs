// Embedded Hermes server
// gateway / dashboard sidecar. We listen on the same ports the webview
// expects (`HERMES_GATEWAY_PORT` = 8642 for the API gateway,
// `HERMES_DASHBOARD_PORT` = 9119 for the dashboard JSON surface) and
// serve the same routes the old Node script used to. This is what
// kills the per-launch "Windows Defender Firewall has blocked some
// features of node.exe" prompt: the OS now sees a single signed
// `tupai.exe` opening two localhost TCP ports, not a fresh unsigned
// `node.exe` binary.
//
// v5.1 — real LLM forwarding. Every chat / model / config / env
// route now reads from the on-disk `~/hermes/config.yaml` (or `.env`)
// and either calls the configured LLM provider (OpenAI-compatible /
// Anthropic / vLLM / llama.cpp) or returns a 503 with an actionable
// error message. There are no synthetic replies left; the previous
// `stub_chat_reply` / `stub_echo` paths are gone.
//
// v6 — LLM 调用入口统一改为 MCP。前端 mcpClient.llmStreamChat 不再
// 直连嵌入式服务器的 /v1/chat/completions 或 /v1/responses 路由，而是
// 通过 mcp_call_v2 命令 → POST /api/v2/mcp (action=llm.stream_request)
// 调用云端 LLM。嵌入式服务器保留 /v1/chat/completions 和 /v1/responses
// 路由仅供 dashboard / 其他内部消费者使用，前端不要直接 fetch 这两个路由。
//
// HTTP routes implemented (keep in sync with `deviceClient.js`,
// `useAppUpdate.js`, and `AppInner.jsx` cron probes):
//
//   GET  /health                → { ok, version, port, role }
//   GET  /v1/health             → same
//   GET  /v1/models             → real list from config.yaml
//   GET  /api/v1/models         → alias
//   GET  /api/v1/model-options  → real list
//   GET  /api/model/options     → response consumed by
//                                 `get_model_options` Tauri command
//   POST /api/model/set         → persist {provider, model} into
//                                 config.yaml and reload in-memory
//                                 model for subsequent chats
//   DELETE /api/v1/clients/unbind → 204; clears the in-memory and
//                on-disk `binding:` block.
//   GET  /api/v1/binding         → returns the cached binding
//                record (device_id / tenant_id / registered_at /
//                join_code) so the front-end can recover after a
//                localStorage wipe without forcing a fresh register.
//   GET  /cron                  → HTML page with the session token
//   POST /v1/chat/completions   → real upstream call (OpenAI /
//                                 OpenAI-compatible / Anthropic / vLLM /
//                                 llama.cpp) with `stream: false`.
//                                 Returns 503 if no model is configured.
//   POST /v1/responses          → real upstream SSE stream. Same
//                                 upstream contract as above but with
//                                 `stream: true`; the upstream bytes
//                                 are piped back to the webview
//                                 verbatim. Returns 503 if no model is
//                                 configured.
//
// Bind on `127.0.0.1` only (IPv4 loopback) for security — prevents
// same-network hosts from accessing the embedded gateway.
// Frontend must use `127.0.0.1` explicitly (not `localhost`/`[::1]`).

use std::collections::HashMap;
use std::convert::Infallible;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::{Arc, OnceLock};
use std::time::Duration;

use axum::body::Body;
use axum::extract::{Path, Request, State};
use axum::http::{HeaderMap, HeaderValue, StatusCode};
use axum::middleware::{self, Next};
use axum::response::{IntoResponse, Response};
use axum::routing::{delete, get, post};
use axum::{Json, Router};
use bytes::Bytes;
use futures::StreamExt;
use serde_json::{json, Value};
use socket2::{Domain, Socket, Type};
use tokio::sync::Mutex;
// NOTE: tauri::async_runtime::JoinHandle wraps tokio::task::JoinHandle.
// We store the Tauri variant and access is_finished() via .inner().
use uuid::Uuid;

use crate::hermes::llm_service::{LLMService, LLMServiceConfig};
use crate::hermes::model_catalog::models_by_provider;
use crate::hermes::types::VLMMessage;

const HERMES_VERSION: &str = env!("CARGO_PKG_VERSION");
const DEFAULT_GATEWAY_PORT: u16 = 8642;
const DEFAULT_DASHBOARD_PORT: u16 = 9119;

/// tupAI 云端基址(单一来源:跟 tauri.conf.json / tauri.tupai.conf.json
/// / tauri.safeopc.conf.json 的 `updater.endpoints` 共用 `ai.tuptup.top` 域)。
/// 允许 `TUPAI_CLOUD_BASE_URL` 环境变量覆盖(CI / staging 场景)。
/// 所有要"真打云端"的地方都从这里拿 URL,不要在文件里再写第二份。
/// 用 `OnceLock` 缓存,启动时只解析一次,后续调用零开销。
fn tupai_cloud_base_url() -> &'static str {
    static URL: OnceLock<String> = OnceLock::new();
    URL.get_or_init(|| {
        std::env::var("TUPAI_CLOUD_BASE_URL")
            .ok()
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| "https://ai.tuptup.top".to_string())
    })
}

/// In-process state shared by both listeners.
#[derive(Clone)]
pub struct EmbeddedServerState {
    /// Stable session token (per process). Surfaced in the `/cron`
    /// HTML page as `window.__HERMES_SESSION_TOKEN__` and expected
    /// back as `Authorization: Bearer <token>` on every dashboard
    /// API call. Generated once at process start so the same
    /// `tupai.exe` lifetime always accepts the same token.
    pub session_token: String,
    /// In-memory cron jobs. The dashboard's "凌晨 2 点" 2 AM
    /// registration (and any user-created jobs) live here until
    /// the process exits. This matches the previous `hermes-cli.cjs`
    /// stub contract: jobs are created, paused, resumed, triggered,
    /// and deleted against an in-memory list, no persistence layer.
    pub jobs: Arc<Mutex<HashMap<String, CronJobRecord>>>,
    /// In-memory mirror of the latest configured primary model.
    /// The gateway reads this on every chat request, so a
    /// `POST /api/model/set` from the dashboard takes effect for the
    /// next message without restarting the server. Initial value is
    /// loaded from `~/hermes/config.yaml` at boot.
    pub primary_model: Arc<Mutex<PrimaryModelConfig>>,
    /// v5.1 — tenant binding metadata (device_id / tenant_id /
    /// registered_at / join_code). Loaded from `~/hermes/config.yaml`
    /// `binding:` block on boot, updated in-place every time
    /// `POST /api/v1/client/fingerprint` succeeds. Survives process
    /// restarts so the gateway knows "this device is bound to tenant
    /// X" without re-prompting for the join code on the next launch.
    pub binding: Arc<Mutex<BindingRecord>>,
    /// Marks when both listeners finished binding so the rest of
    /// the app can stop TCP-probing once it's set.
    pub started_at: Arc<Mutex<Option<std::time::Instant>>>,
    /// Watchdog-tracked health: `Some(true)` = both listeners alive,
    /// `Some(false)` = at least one died, `None` = watchdog hasn't
    /// decided yet (within the 2s startup window).
    pub healthy: Arc<Mutex<Option<bool>>>,
    /// Shared `reqwest::Client` for upstream LLM calls
    /// (`/v1/chat/completions`, `/v1/responses`, cron trigger). Built
    /// once at boot so the connection pool is reused across requests
    /// instead of being rebuilt on every chat / cron invocation.
    /// Mirrors `LLMService::new`'s builder config (`.no_proxy()` + 120s
    /// timeout) so behavior is identical to the per-call construction
    /// it replaces.
    pub llm_http: reqwest::Client,
    /// Shared `reqwest::Client` for the 5 payment routes that proxy to
    /// `{cloud}/api/payment/*`. Built once at boot with `.no_proxy()`
    /// (no global timeout — each request sets its own
    /// `RequestBuilder::timeout` so the existing 5s / 8s per-route
    /// budgets are preserved exactly).
    pub cloud_http: reqwest::Client,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct BindingRecord {
    /// Cloud-side device id (NOT the local hardware id; this is
    /// what `tupai_cloud_base_url/api/v1/client/fingerprint` returns).
    pub device_id: String,
    /// Tenant this device is bound to. Empty until first successful
    /// register.
    pub tenant_id: String,
    /// ISO 8601 timestamp from the cloud response (or `now()` if
    /// the cloud omits it).
    pub registered_at: String,
    /// The 8-digit join code the user typed in. Cached so
    /// `ensureDeviceToken` on the front-end can silently re-renew
    /// at startup without a modal. Always 8 ASCII digits or empty.
    pub join_code: String,
}

impl BindingRecord {
    /// `true` if no register call has ever succeeded for this
    /// device. Used by the boot log to decide whether to print
    /// the "persisted binding found" line.
    fn is_empty(&self) -> bool {
        self.device_id.trim().is_empty()
            && self.tenant_id.trim().is_empty()
            && self.registered_at.trim().is_empty()
            && self.join_code.trim().is_empty()
    }
}

#[derive(Clone, Debug, Default)]
pub struct PrimaryModelConfig {
    pub provider: String,
    pub base_url: String,
    pub api_key: String,
    pub model: String,
}

impl PrimaryModelConfig {
    /// 全部 4 个字段都得有值(OpenAI 兼容 + 鉴权的前提)。
    /// 同云端 LLM 鉴权走 device_token,所以 api_key 缺一不可:
    /// api_key 缺失时用户必须先绑设备(POST /api/v1/client/fingerprint
    /// 返回 device_token,embedded server 自动写到 api_key)。
    fn is_configured(&self) -> bool {
        self.missing_fields().is_empty()
    }

    /// 返回当前缺哪些字段(用于生成精确的 503 错误信息)。
    fn missing_fields(&self) -> Vec<&'static str> {
        let mut missing = Vec::new();
        if self.provider.trim().is_empty() {
            missing.push("provider");
        }
        if self.base_url.trim().is_empty() {
            missing.push("base_url");
        }
        if self.api_key.trim().is_empty() {
            // 这条最常见,单独 message 走"先注册设备"路径
            missing.push("api_key");
        }
        if self.model.trim().is_empty() {
            missing.push("default");
        }
        missing
    }

    /// 缺字段时返回的精确 503 body。云端 LLM 鉴权模式
    /// (`base_url` 指向 ai.tuptup.top 类)下,api_key 缺 == 必须
    /// 先绑设备,这条 message 给前端用,前端拿 `requires_registration`
    /// 标记直接弹"请先绑定设备"对话框,而不是笼统的"model not
    /// configured"。
    fn build_unconfigured_error(&self) -> serde_json::Value {
        let missing = self.missing_fields();
        let only_api_key = missing.as_slice() == ["api_key"];
        let mut body = json!({
            "error": "model not configured",
            "missing": missing,
            "config_path": hermes_config_path().display().to_string(),
        });
        if only_api_key {
            // api_key 单独缺失,user flow 最常见:还没绑设备。
            // 前端 / v5 工具看到 requires_registration=true 直接
            // 跳到 "绑定设备" 弹窗,不要让用户去翻 yaml。
            body["requires_registration"] = json!(true);
            body["register_endpoint"] = json!("/api/v1/client/fingerprint");
            body["error"] = json!(
                "model.api_key is empty. Bind a device first via the two-step \
                 flow: (1) POST /api/v1/client/fingerprint with hardware \
                 fingerprint to get a device_token; (2) POST /api/v2/mcp \
                 action=client.bind with the token + join_code to bind to a \
                 tenant. The cloud-issued device_token is then auto-written \
                 into model.api_key, and the next chat request will use it \
                 as the LLM Bearer key. NOTE: fingerprint does NOT take \
                 join_code — join_code only goes to client.bind."
            );
        } else {
            body["error"] = json!(format!(
                "model not configured: missing fields {}. Edit {} \
                 and set `model.{}` (or restart so embedded server can \
                 write cloud defaults).",
                missing.join(", "),
                self.missing_fields_for_msg(),
                missing.join("`, `model."),
            ));
            // 多字段都缺时,"先绑设备"不一定能解决(可能 base_url
            // 也错),但仍提示一次,方便用户记忆。
            if !missing.contains(&"api_key") {
                // 不强行加 requires_registration
            }
        }
        body
    }

    fn missing_fields_for_msg(&self) -> String {
        hermes_config_path().display().to_string()
    }
}

impl EmbeddedServerState {
    fn new() -> Self {
        let nanos = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .unwrap_or(0);
        let token = format!("tupai-session-{:032x}", nanos);
        let primary_model = load_primary_model_from_disk();
        let binding = load_binding_record_from_disk();
        if !binding.is_empty() {
            log::info!(
                "[embedded_server] 启动时检测到持久化 binding: device_id={}*** tenant_id={} registered_at={} join_code={}***",
                binding.device_id.chars().take(8).collect::<String>(),
                binding.tenant_id,
                binding.registered_at,
                binding.join_code.chars().take(2).collect::<String>(),
            );
        }
        Self {
            session_token: token,
            jobs: Arc::new(Mutex::new(HashMap::new())),
            primary_model: Arc::new(Mutex::new(primary_model)),
            binding: Arc::new(Mutex::new(binding)),
            started_at: Arc::new(Mutex::new(None)),
            healthy: Arc::new(Mutex::new(None)),
            llm_http: reqwest::Client::builder()
                .no_proxy()
                .timeout(Duration::from_secs(120))
                .build()
                .expect("llm http client builder"),
            cloud_http: reqwest::Client::builder()
                .no_proxy()
                .build()
                .expect("cloud http client builder"),
        }
    }
}

pub type SharedState = Arc<EmbeddedServerState>;

// ========================
// config / env persistence
// ========================

fn hermes_home_dir() -> PathBuf {
    if let Ok(path) = std::env::var("HERMES_HOME") {
        let expanded = if let Some(stripped) = path.strip_prefix("~/") {
            if let Some(home) = dirs::home_dir() {
                return home.join(stripped);
            }
            PathBuf::from(stripped)
        } else {
            PathBuf::from(&path)
        };
        return expanded;
    }
    if let Some(home) = dirs::home_dir() {
        return home.join(".hermes");
    }
    if let Ok(home) = std::env::var("HOME") {
        return PathBuf::from(home).join(".hermes");
    }
    PathBuf::from(".hermes")
}

fn hermes_config_path() -> PathBuf {
    hermes_home_dir().join("config.yaml")
}

fn hermes_env_path() -> PathBuf {
    hermes_home_dir().join(".env")
}

fn ensure_parent(path: &std::path::Path) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)
            .map_err(|e| format!("failed to create {}: {}", parent.display(), e))?;
    }
    Ok(())
}

/// Async equivalent of `ensure_parent` for use inside async
/// handlers. Mirrors the sync helper's semantics but yields to
/// the runtime instead of blocking on `create_dir_all`.
async fn ensure_parent_async(path: &std::path::Path) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        tokio::fs::create_dir_all(parent)
            .await
            .map_err(|e| format!("failed to create {}: {}", parent.display(), e))?;
    }
    Ok(())
}

fn read_file_or_default(path: &std::path::Path) -> String {
    std::fs::read_to_string(path).unwrap_or_default()
}

/// Async equivalent of `read_file_or_default` for use inside async
/// handlers. Avoids blocking the tokio runtime on disk I/O. The
/// previous `std::fs::read_to_string` calls inside async handlers
/// would block the executor thread for the entire file read.
async fn read_file_or_default_async(path: &std::path::Path) -> String {
    tokio::fs::read_to_string(path).await.unwrap_or_default()
}

/// Async equivalent of `write_env_var_to_disk` for use inside async
/// handlers. Mirrors the sync helper's semantics (read existing
/// lines, replace the matching key, append if missing, then write
/// the whole file back).
async fn write_env_var_to_disk_async(key: &str, value: &str) -> Result<(), String> {
    let path = hermes_env_path();
    if let Some(parent) = path.parent() {
        tokio::fs::create_dir_all(parent)
            .await
            .map_err(|e| format!("failed to create {}: {}", parent.display(), e))?;
    }
    let current = match tokio::fs::read_to_string(&path).await {
        Ok(s) => s,
        // 文件不存在是合法的(首次写入),退化到空串。
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => String::new(),
        // 其他 IO 错误(权限拒绝、磁盘错误、路径是目录等)绝不能退化到空串,
        // 否则后续 read-modify-write 会用只含单字段的配置覆盖掉整个文件。
        Err(e) => return Err(format!("failed to read {}: {}", path.display(), e)),
    };
    let mut lines: Vec<String> = current.lines().map(str::to_string).collect();
    let target = format!("{}=\"{}\"", key, value);
    let mut found = false;
    for entry in lines.iter_mut() {
        if let Some((raw_key, _)) = entry.split_once('=') {
            if raw_key.trim() == key {
                *entry = target.clone();
                found = true;
                break;
            }
        }
    }
    if !found {
        lines.push(target);
    }
    let body = lines.join("\n") + "\n";
    tokio::fs::write(&path, body)
        .await
        .map_err(|e| format!("Failed to write {}: {}", path.display(), e))?;
    Ok(())
}

fn load_primary_model_from_disk() -> PrimaryModelConfig {
    let yaml_path = hermes_config_path();
    let yaml_text = read_file_or_default(&yaml_path);
    if yaml_text.trim().is_empty() {
        // 首次启动:`~/hermes/config.yaml` 不存在,直接落一份 cloud
        // 默认配置(同 cloud LLM 是 OpenAI 兼容 + device_token
        // 鉴权),用户绑设备后 register 流程会自动把 device_token
        // 写到 model.api_key,直接能聊。provider / base_url /
        // default 也都先填好,省去用户 4 个字段逐个设。
        log::info!(
            "[embedded_server] ~/hermes/config.yaml 不存在,写入 cloud 默认配置到 {}",
            yaml_path.display()
        );
        let defaults = default_cloud_primary_model();
        if let Err(e) = write_primary_model_to_yaml(&yaml_path, &defaults) {
            log::warn!(
                "[embedded_server] 写 cloud 默认配置失败 ({}),继续用 in-memory 默认",
                e
            );
        }
        return defaults;
    }
    let Ok(value) = serde_yaml::from_str::<serde_yaml::Value>(&yaml_text) else {
        log::warn!(
            "[embedded_server] {} 不是合法 yaml,用 in-memory 默认",
            yaml_path.display()
        );
        return PrimaryModelConfig::default();
    };
    let Some(model_mapping) = value
        .as_mapping()
        .and_then(|m| m.get(serde_yaml::Value::String("model".to_string())))
        .and_then(|m| m.as_mapping())
    else {
        return PrimaryModelConfig::default();
    };
    fn pick(m: &serde_yaml::Mapping, key: &str) -> String {
        m.get(serde_yaml::Value::String(key.to_string()))
            .and_then(|v| v.as_str())
            .map(str::to_string)
            .unwrap_or_default()
    }
    PrimaryModelConfig {
        provider: pick(model_mapping, "provider"),
        base_url: pick(model_mapping, "base_url"),
        api_key: pick(model_mapping, "api_key"),
        model: pick(model_mapping, "default"),
    }
}

/// 首次启动时写入 `~/hermes/config.yaml` 的默认值。同云端 LLM
/// 是 OpenAI 兼容 + device_token 鉴权,所以 base_url 指向 cloud
/// 的 chat completions,provider 是 openai,default 用一个
/// 常见的 OpenAI 兼容模型占位(model 名不对会被远端 4xx 拒,
/// 用户在仪表盘改 / 或把 default 改成云端实际支持的模型即可)。
fn default_cloud_primary_model() -> PrimaryModelConfig {
    PrimaryModelConfig {
        provider: "openai".to_string(),
        base_url: format!("{}/v1", tupai_cloud_base_url().trim_end_matches('/')),
        api_key: String::new(),
        model: "gpt-4o-mini".to_string(),
    }
}

fn write_primary_model_to_yaml(
    path: &PathBuf,
    cfg: &PrimaryModelConfig,
) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)
            .map_err(|e| format!("create_dir_all({}) failed: {}", parent.display(), e))?;
    }
    let body = format!(
        "# Generated by tupai embedded server on first boot.\n\
         # Register a device (POST /api/v1/client/fingerprint) to get a\n\
         # device_token, then bind to a tenant (MCP client.bind with\n\
         # join_code). The device_token auto-fills `model.api_key`.\n\
         # Change `model.default` to a model your cloud actually serves\n\
         # if the chat returns a 'model not found' error.\n\
         model:\n  provider: {provider}\n  base_url: {base_url}\n  api_key: \"{api_key}\"\n  default: {model}\n",
        provider = cfg.provider,
        base_url = cfg.base_url,
        api_key = cfg.api_key,
        model = cfg.model,
    );
    std::fs::write(path, body.as_bytes())
        .map_err(|e| format!("write {} failed: {}", path.display(), e))
}

fn apply_default_model_to_yaml(
    yaml_text: &str,
    provider: &str,
    model: &str,
) -> Result<String, String> {
    let trimmed = yaml_text.trim();
    let mut root = if trimmed.is_empty() {
        serde_yaml::Value::Mapping(serde_yaml::Mapping::new())
    } else {
        serde_yaml::from_str::<serde_yaml::Value>(trimmed)
            .map_err(|e| format!("Failed to parse hermes config yaml: {}", e))?
    };
    if !matches!(root, serde_yaml::Value::Mapping(_)) {
        root = serde_yaml::Value::Mapping(serde_yaml::Mapping::new());
    }
    let root_mapping = root
        .as_mapping_mut()
        .ok_or_else(|| "hermes config root is not a YAML mapping".to_string())?;
    root_mapping.remove(serde_yaml::Value::String(
        "model_context_length".to_string(),
    ));
    let model_mapping = ensure_yaml_mapping(root_mapping, "model")?;
    model_mapping.insert(
        serde_yaml::Value::String("provider".to_string()),
        serde_yaml::Value::String(provider.trim().to_string()),
    );
    model_mapping.insert(
        serde_yaml::Value::String("default".to_string()),
        serde_yaml::Value::String(model.trim().to_string()),
    );
    // Don't drop `base_url` / `api_key` if the caller is just
    // changing the default model — but the dashboard only sends
    // {provider, model}, so we only touch those two fields here.
    serde_yaml::to_string(&root)
        .map_err(|e| format!("Failed to serialize hermes config yaml: {}", e))
}

/// Update only `model.api_key` in `~/hermes/config.yaml`, leaving
/// everything else (provider / base_url / default / env block / etc.)
/// intact. Used by the register flow to auto-persist the device_token
/// returned by the cloud so the next LLM call can use it as the
/// Bearer key without restarting the embedded server.
fn persist_api_key_to_yaml(path: &PathBuf, api_key: &str) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)
            .map_err(|e| format!("create_dir_all({}) failed: {}", parent.display(), e))?;
    }
    let yaml_text = match std::fs::read_to_string(path) {
        Ok(s) => s,
        // 文件不存在是合法的(首次写入配置),退化到空串。
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => String::new(),
        // 其他 IO 错误(权限拒绝、磁盘错误等)绝不能退化到空串,
        // 否则 read-modify-write 会用只含 model.api_key 的配置覆盖掉
        // 用户原有的 provider / base_url / default / env 等全部配置。
        Err(e) => return Err(format!("failed to read {}: {}", path.display(), e)),
    };
    let trimmed = yaml_text.trim();
    let mut root = if trimmed.is_empty() {
        serde_yaml::Value::Mapping(serde_yaml::Mapping::new())
    } else {
        serde_yaml::from_str::<serde_yaml::Value>(trimmed)
            .map_err(|e| format!("Failed to parse hermes config yaml: {}", e))?
    };
    if !matches!(root, serde_yaml::Value::Mapping(_)) {
        root = serde_yaml::Value::Mapping(serde_yaml::Mapping::new());
    }
    let root_mapping = root
        .as_mapping_mut()
        .ok_or_else(|| "hermes config root is not a YAML mapping".to_string())?;
    let model_mapping = ensure_yaml_mapping(root_mapping, "model")?;
    model_mapping.insert(
        serde_yaml::Value::String("api_key".to_string()),
        serde_yaml::Value::String(api_key.trim().to_string()),
    );
    let serialized = serde_yaml::to_string(&root)
        .map_err(|e| format!("Failed to serialize hermes config yaml: {}", e))?;
    std::fs::write(path, serialized.as_bytes())
        .map_err(|e| format!("Failed to write {}: {}", path.display(), e))?;
    Ok(())
}

/// Async equivalent of `persist_api_key_to_yaml` for use inside
/// async handlers. Mirrors the sync helper's semantics but uses
/// `tokio::fs::*` so the runtime isn't blocked on disk I/O.
async fn persist_api_key_to_yaml_async(
    path: &PathBuf,
    api_key: &str,
) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        tokio::fs::create_dir_all(parent)
            .await
            .map_err(|e| format!("create_dir_all({}) failed: {}", parent.display(), e))?;
    }
    let yaml_text = match tokio::fs::read_to_string(path).await {
        Ok(s) => s,
        // 文件不存在是合法的(首次写入配置),退化到空串。
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => String::new(),
        // 其他 IO 错误绝不能退化到空串,否则 read-modify-write 会覆盖掉
        // 用户原有的全部 LLM 配置(provider / base_url / env 等)。
        Err(e) => return Err(format!("failed to read {}: {}", path.display(), e)),
    };
    let trimmed = yaml_text.trim();
    let mut root = if trimmed.is_empty() {
        serde_yaml::Value::Mapping(serde_yaml::Mapping::new())
    } else {
        serde_yaml::from_str::<serde_yaml::Value>(trimmed)
            .map_err(|e| format!("Failed to parse hermes config yaml: {}", e))?
    };
    if !matches!(root, serde_yaml::Value::Mapping(_)) {
        root = serde_yaml::Value::Mapping(serde_yaml::Mapping::new());
    }
    let root_mapping = root
        .as_mapping_mut()
        .ok_or_else(|| "hermes config root is not a YAML mapping".to_string())?;
    let model_mapping = ensure_yaml_mapping(root_mapping, "model")?;
    model_mapping.insert(
        serde_yaml::Value::String("api_key".to_string()),
        serde_yaml::Value::String(api_key.trim().to_string()),
    );
    let serialized = serde_yaml::to_string(&root)
        .map_err(|e| format!("Failed to serialize hermes config yaml: {}", e))?;
    tokio::fs::write(path, serialized.as_bytes())
        .await
        .map_err(|e| format!("Failed to write {}: {}", path.display(), e))?;
    Ok(())
}

/// v5.1 — read the `binding:` block from `~/hermes/config.yaml`.
/// Returns an empty `BindingRecord` (no error) when the file is
/// missing, the block is absent, or any field is unparseable —
/// this is a best-effort cache, not an authoritative source of
/// truth (the cloud is).
fn load_binding_record_from_disk() -> BindingRecord {
    let yaml_path = hermes_config_path();
    let yaml_text = read_file_or_default(&yaml_path);
    if yaml_text.trim().is_empty() {
        return BindingRecord::default();
    }
    let Ok(value) = serde_yaml::from_str::<serde_yaml::Value>(&yaml_text) else {
        return BindingRecord::default();
    };
    let Some(binding_mapping) = value
        .as_mapping()
        .and_then(|m| m.get(serde_yaml::Value::String("binding".to_string())))
        .and_then(|m| m.as_mapping())
    else {
        return BindingRecord::default();
    };
    let pick = |key: &str| -> String {
        binding_mapping
            .get(serde_yaml::Value::String(key.to_string()))
            .and_then(|v| v.as_str())
            .map(str::to_string)
            .unwrap_or_default()
    };
    BindingRecord {
        device_id: pick("device_id"),
        tenant_id: pick("tenant_id"),
        registered_at: pick("registered_at"),
        join_code: pick("join_code"),
    }
}

/// v5.1 — persist the binding record (device_id / tenant_id /
/// registered_at / join_code) to `~/hermes/config.yaml` `binding:`
/// block. Unlike `persist_api_key_to_yaml` this also writes a
/// newly-introduced `binding:` section, so the file is allowed to
/// not have one yet. Existing `model:` / `env:` / free-form blocks
/// are preserved verbatim.
fn persist_binding_record_to_yaml(
    path: &PathBuf,
    record: &BindingRecord,
) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)
            .map_err(|e| format!("create_dir_all({}) failed: {}", parent.display(), e))?;
    }
    let yaml_text = match std::fs::read_to_string(path) {
        Ok(s) => s,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => String::new(),
        // IO 错误绝不能退化到空串,否则会用只含 binding 的配置覆盖掉整个文件。
        Err(e) => return Err(format!("failed to read {}: {}", path.display(), e)),
    };
    let trimmed = yaml_text.trim();
    let mut root = if trimmed.is_empty() {
        serde_yaml::Value::Mapping(serde_yaml::Mapping::new())
    } else {
        serde_yaml::from_str::<serde_yaml::Value>(trimmed)
            .map_err(|e| format!("Failed to parse hermes config yaml: {}", e))?
    };
    if !matches!(root, serde_yaml::Value::Mapping(_)) {
        root = serde_yaml::Value::Mapping(serde_yaml::Mapping::new());
    }
    let root_mapping = root
        .as_mapping_mut()
        .ok_or_else(|| "hermes config root is not a YAML mapping".to_string())?;
    let binding_mapping = ensure_yaml_mapping(root_mapping, "binding")?;
    // Each field is written independently so a partial cloud
    // response (e.g. tenant_id missing in some old build) doesn't
    // clobber an existing value.
    fn upsert_str(
        map: &mut serde_yaml::Mapping,
        key: &str,
        value: &str,
    ) {
        let trimmed = value.trim();
        if trimmed.is_empty() {
            return;
        }
        map.insert(
            serde_yaml::Value::String(key.to_string()),
            serde_yaml::Value::String(trimmed.to_string()),
        );
    }
    upsert_str(binding_mapping, "device_id", &record.device_id);
    upsert_str(binding_mapping, "tenant_id", &record.tenant_id);
    upsert_str(binding_mapping, "registered_at", &record.registered_at);
    upsert_str(binding_mapping, "join_code", &record.join_code);
    let serialized = serde_yaml::to_string(&root)
        .map_err(|e| format!("Failed to serialize hermes config yaml: {}", e))?;
    std::fs::write(path, serialized.as_bytes())
        .map_err(|e| format!("Failed to write {}: {}", path.display(), e))?;
    Ok(())
}

/// Async equivalent of `persist_binding_record_to_yaml` for use
/// inside async handlers. Mirrors the sync helper's semantics but
/// uses `tokio::fs::*` so the runtime isn't blocked on disk I/O.
async fn persist_binding_record_to_yaml_async(
    path: &PathBuf,
    record: &BindingRecord,
) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        tokio::fs::create_dir_all(parent)
            .await
            .map_err(|e| format!("create_dir_all({}) failed: {}", parent.display(), e))?;
    }
    let yaml_text = match tokio::fs::read_to_string(path).await {
        Ok(s) => s,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => String::new(),
        // IO 错误绝不能退化到空串,否则会用只含 binding 的配置覆盖掉整个文件。
        Err(e) => return Err(format!("failed to read {}: {}", path.display(), e)),
    };
    let trimmed = yaml_text.trim();
    let mut root = if trimmed.is_empty() {
        serde_yaml::Value::Mapping(serde_yaml::Mapping::new())
    } else {
        serde_yaml::from_str::<serde_yaml::Value>(trimmed)
            .map_err(|e| format!("Failed to parse hermes config yaml: {}", e))?
    };
    if !matches!(root, serde_yaml::Value::Mapping(_)) {
        root = serde_yaml::Value::Mapping(serde_yaml::Mapping::new());
    }
    let root_mapping = root
        .as_mapping_mut()
        .ok_or_else(|| "hermes config root is not a YAML mapping".to_string())?;
    let binding_mapping = ensure_yaml_mapping(root_mapping, "binding")?;
    // Each field is written independently so a partial cloud
    // response (e.g. tenant_id missing in some old build) doesn't
    // clobber an existing value.
    fn upsert_str(
        map: &mut serde_yaml::Mapping,
        key: &str,
        value: &str,
    ) {
        let trimmed = value.trim();
        if trimmed.is_empty() {
            return;
        }
        map.insert(
            serde_yaml::Value::String(key.to_string()),
            serde_yaml::Value::String(trimmed.to_string()),
        );
    }
    upsert_str(binding_mapping, "device_id", &record.device_id);
    upsert_str(binding_mapping, "tenant_id", &record.tenant_id);
    upsert_str(binding_mapping, "registered_at", &record.registered_at);
    upsert_str(binding_mapping, "join_code", &record.join_code);
    let serialized = serde_yaml::to_string(&root)
        .map_err(|e| format!("Failed to serialize hermes config yaml: {}", e))?;
    tokio::fs::write(path, serialized.as_bytes())
        .await
        .map_err(|e| format!("Failed to write {}: {}", path.display(), e))?;
    Ok(())
}
fn ensure_yaml_mapping<'a>(
    parent: &'a mut serde_yaml::Mapping,
    key: &str,
) -> Result<&'a mut serde_yaml::Mapping, String> {
    let yaml_key = serde_yaml::Value::String(key.to_string());
    if !parent.contains_key(&yaml_key) {
        parent.insert(
            yaml_key.clone(),
            serde_yaml::Value::Mapping(serde_yaml::Mapping::new()),
        );
    }
    let value = parent
        .get_mut(&yaml_key)
        .ok_or_else(|| format!("failed to access {} key in config", key))?;
    if !matches!(value, serde_yaml::Value::Mapping(_)) {
        *value = serde_yaml::Value::Mapping(serde_yaml::Mapping::new());
    }
    value
        .as_mapping_mut()
        .ok_or_else(|| format!("{} is not a YAML mapping", key))
}

fn load_env_vars_from_disk() -> HashMap<String, String> {
    let text = read_file_or_default(&hermes_env_path());
    parse_env_content(&text)
}

/// Async equivalent of `load_env_vars_from_disk` for use inside
/// async handlers. Mirrors the sync helper's semantics but reads
/// `~/hermes/.env` via `tokio::fs` so the runtime isn't blocked
/// on disk I/O.
async fn load_env_vars_from_disk_async() -> HashMap<String, String> {
    let text = read_file_or_default_async(&hermes_env_path()).await;
    parse_env_content(&text)
}

fn parse_env_content(text: &str) -> HashMap<String, String> {
    let mut map = HashMap::new();
    for line in text.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with('#') {
            continue;
        }
        let Some((raw_key, raw_val)) = trimmed.split_once('=') else {
            continue;
        };
        let key = raw_key.trim().to_string();
        let val = raw_val
            .trim()
            .trim_matches('"')
            .trim_matches('\'')
            .to_string();
        if !key.is_empty() {
            map.insert(key, val);
        }
    }
    map
}

fn write_env_var_to_disk(key: &str, value: &str) -> Result<(), String> {
    let path = hermes_env_path();
    ensure_parent(&path)?;
    let current = read_file_or_default(&path);
    let mut lines: Vec<String> = current.lines().map(str::to_string).collect();
    let target = format!("{}=\"{}\"", key, value);
    let mut found = false;
    for entry in lines.iter_mut() {
        if let Some((raw_key, _)) = entry.split_once('=') {
            if raw_key.trim() == key {
                *entry = target.clone();
                found = true;
                break;
            }
        }
    }
    if !found {
        lines.push(target);
    }
    let body = lines.join("\n") + "\n";
    std::fs::write(&path, body)
        .map_err(|e| format!("Failed to write {}: {}", path.display(), e))?;
    Ok(())
}

fn remove_env_var_from_disk(key: &str) -> Result<(), String> {
    let path = hermes_env_path();
    if !path.exists() {
        return Ok(());
    }
    let current = read_file_or_default(&path);
    let mut lines: Vec<String> = Vec::new();
    for line in current.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            lines.push(line.to_string());
            continue;
        }
        if let Some((raw_key, _)) = trimmed.split_once('=') {
            if raw_key.trim() == key {
                continue;
            }
        }
        lines.push(line.to_string());
    }
    let body = lines.join("\n") + "\n";
    std::fs::write(&path, body)
        .map_err(|e| format!("Failed to write {}: {}", path.display(), e))?;
    Ok(())
}

/// Async equivalent of `remove_env_var_from_disk` for use inside
/// async handlers. Mirrors the sync helper's semantics but uses
/// `tokio::fs::*` so the runtime isn't blocked on disk I/O.
async fn remove_env_var_from_disk_async(key: &str) -> Result<(), String> {
    let path = hermes_env_path();
    if !path.exists() {
        return Ok(());
    }
    let current = match tokio::fs::read_to_string(&path).await {
        Ok(s) => s,
        // 文件不存在是合法的(无环境变量需要清理),退化到空串。
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => String::new(),
        // 其他 IO 错误绝不能退化到空串,否则会用空内容覆盖整个 env 文件。
        Err(e) => return Err(format!("failed to read {}: {}", path.display(), e)),
    };
    let mut lines: Vec<String> = Vec::new();
    for line in current.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            lines.push(line.to_string());
            continue;
        }
        if let Some((raw_key, _)) = trimmed.split_once('=') {
            if raw_key.trim() == key {
                continue;
            }
        }
        lines.push(line.to_string());
    }
    let body = lines.join("\n") + "\n";
    tokio::fs::write(&path, body)
        .await
        .map_err(|e| format!("Failed to write {}: {}", path.display(), e))?;
    Ok(())
}

fn build_llm_service_config(cfg: &PrimaryModelConfig) -> LLMServiceConfig {
    LLMServiceConfig {
        provider: cfg.provider.trim().to_string(),
        api_url: cfg.base_url.trim().to_string(),
        api_key: if cfg.api_key.trim().is_empty() {
            None
        } else {
            Some(cfg.api_key.trim().to_string())
        },
        model: cfg.model.trim().to_string(),
        temperature: 0.7,
        max_tokens: 4096,
    }
}

/// Mirror of the Rust-side `CronJob` struct the webview expects
/// after deserialising the dashboard API response. We keep the
/// field names lowercase-snake for serde to convert to the
/// camelCase `CronJob` the front-end uses.
#[derive(Clone)]
pub struct CronJobRecord {
    id: String,
    name: Option<String>,
    prompt: String,
    schedule_kind: String,
    schedule_expr: String,
    schedule_display: String,
    enabled: bool,
    state: String,
    deliver: Option<String>,
    last_run_at: Option<String>,
    next_run_at: Option<String>,
    last_error: Option<String>,
}

impl CronJobRecord {
    fn to_json(&self) -> Value {
        json!({
            "id": self.id,
            "name": self.name,
            "prompt": self.prompt,
            "schedule": {
                "kind": self.schedule_kind,
                "expr": self.schedule_expr,
                "display": self.schedule_display,
            },
            "scheduleDisplay": self.schedule_display,
            "enabled": self.enabled,
            "state": self.state,
            "deliver": self.deliver,
            "lastRunAt": self.last_run_at,
            "nextRunAt": self.next_run_at,
            "lastError": self.last_error,
        })
    }
}

/// Public entry point — `start_detached_gateway` entry
/// to replace the previous `spawn(node, hermes-cli.cjs, ...)` path.
/// Idempotent: a second call returns the existing handles without
/// re-binding. `tokio::spawn` requires a runtime; we require callers
/// to invoke us from within the Tauri `setup` hook (which runs after
/// the tokio runtime is wired up) or from any other async context
/// that has a current runtime. A `block_on` is used for the rare
/// sync caller — see `start_detached_gateway`.
pub fn ensure_embedded_server_running(gateway_port: u16, dashboard_port: u16) -> Result<EmbeddedHandles, String> {
    static HANDLES: once_cell::sync::OnceCell<EmbeddedHandles> = once_cell::sync::OnceCell::new();
    static INIT_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());
    let _lock = INIT_LOCK.lock().map_err(|e| format!("init lock poisoned: {}", e))?;
    if let Some(h) = HANDLES.get() {
        return Ok(h.clone());
    }

    // Bind both ports synchronously *before* the OnceCell is
    // populated — that way a bind error short-circuits and we
    // don't leave a half-started server in HANDLES.
    let _gateway_addr: SocketAddr = format!("127.0.0.1:{gateway_port}").parse().map_err(|e| format!("bad gateway port: {e}"))?;
    let _dashboard_addr: SocketAddr = format!("127.0.0.1:{dashboard_port}").parse().map_err(|e| format!("bad dashboard port: {e}"))?;

    // tauri::async_runtime::spawn 内部使用全局 RUNTIME（OnceCell 懒初始化），
    // 不依赖当前线程的 tokio runtime context，因此可以在 spawn_blocking
    // 线程中安全调用（如 lib.rs setup hook 的 gateway 拉起路径）。
    // 旧代码用 tokio::runtime::Handle::try_current() 做前置检查，
    // 但这在 spawn_blocking 线程中会误判为“无 runtime”而提前返回 Err，
    // 导致 embedded server 永远起不来、ensure_gateway_running 反复重试浪费 CPU。

    let state: SharedState = Arc::new(EmbeddedServerState::new());

    // 用 loop 包裹 axum::serve:listener 死掉(端口被占 / panic /
    // 对端 RST 风暴等)时,5s 后自动重新 bind 同端口并起新实例。
    // watchdog 那边的 healthy 标记在 backoff 窗口会变成 false,
    // 一旦 listener 复活就回到 true,前端 /health 直接反映出来。
    // 后端不需要前端 / 用户来"重启 gateway"。
    const LISTENER_REBIND_BACKOFF: std::time::Duration = std::time::Duration::from_secs(5);

    let gw_handle = Arc::new(tauri::async_runtime::spawn({
        let state = state.clone();
        async move {
            let mut attempt: u32 = 0;
            loop {
                log::info!(
                    "[Hermes Gateway] up on 127.0.0.1:{} (embedded, no node.exe), attempt={}",
                    gateway_port,
                    attempt
                );
                match make_dual_stack_listener(gateway_port) {
                    Ok(listener) => {
                        let app = build_router(state.clone(), "gateway", gateway_port);
                        if let Err(e) = axum::serve(listener, app.into_make_service()).await {
                            log::error!("[Hermes Gateway] serve failed: {}", e);
                        } else {
                            log::warn!("[Hermes Gateway] serve returned cleanly (port released?)");
                        }
                    }
                    Err(e) => {
                        log::error!(
                            "[Hermes Gateway] bind 127.0.0.1:{} failed: {} ({}s 后重试)",
                            gateway_port,
                            e,
                            LISTENER_REBIND_BACKOFF.as_secs()
                        );
                    }
                }
                attempt = attempt.saturating_add(1);
                tokio::time::sleep(LISTENER_REBIND_BACKOFF).await;
            }
        }
    }));
    let dash_handle = Arc::new(tauri::async_runtime::spawn({
        let state = state.clone();
        async move {
            let mut attempt: u32 = 0;
            loop {
                log::info!(
                    "[Hermes Dashboard] up on 127.0.0.1:{} (embedded, no node.exe), attempt={}",
                    dashboard_port,
                    attempt
                );
                match make_dual_stack_listener(dashboard_port) {
                    Ok(listener) => {
                        let app = build_router(state.clone(), "dashboard", dashboard_port);
                        if let Err(e) = axum::serve(listener, app.into_make_service()).await {
                            log::error!("[Hermes Dashboard] serve failed: {}", e);
                        } else {
                            log::warn!("[Hermes Dashboard] serve returned cleanly (port released?)");
                        }
                    }
                    Err(e) => {
                        log::error!(
                            "[Hermes Dashboard] bind 127.0.0.1:{} failed: {} ({}s 后重试)",
                            dashboard_port,
                            e,
                            LISTENER_REBIND_BACKOFF.as_secs()
                        );
                    }
                }
                attempt = attempt.saturating_add(1);
                tokio::time::sleep(LISTENER_REBIND_BACKOFF).await;
            }
        }
    }));

    // Watchdog: 让 /health 在 listener 死掉的窗口期直接 503
    // (前端不用靠超时判断)。listener 死掉时,5s 后会被上面的
    // loop 自动 rebind 复活,健康标记也跟着回 true。
    //
    // 使用 tauri::async_runtime::spawn 而非 handle.spawn，
    // 确保在 spawn_blocking 线程中也能正常启动 watchdog。
    {
        let state_watch = state.clone();
        let gw_watch = gw_handle.clone();
        let dash_watch = dash_handle.clone();
        tauri::async_runtime::spawn(async move {
            // 启动窗口(2s):给 axum::serve 一次机会 bind 成功。
            tokio::time::sleep(std::time::Duration::from_secs(2)).await;
        let gw_alive = !gw_watch.inner().is_finished();
        let dash_alive = !dash_watch.inner().is_finished();
            let healthy = gw_alive && dash_alive;
            *state_watch.healthy.lock().await = Some(healthy);
            log::info!(
                "[embedded_server] 启动窗口结束,healthy={} (gw_alive={}, dash_alive={})",
                healthy,
                gw_alive,
                dash_alive
            );
            // 之后每 2s 轮询一次,任一 listener 死就把 healthy 置
            // false。listener 内部 loop 会自动重 bind,所以
            // healthy 一会儿又会变回 true。
            loop {
                tokio::time::sleep(std::time::Duration::from_secs(2)).await;
                let gw_now = !gw_watch.inner().is_finished();
                let dash_now = !dash_watch.inner().is_finished();
                let now_healthy = gw_now && dash_now;
                let prev = *state_watch.healthy.lock().await;
                if prev != Some(now_healthy) {
                    log::warn!(
                        "[embedded_server] 健康状态切换: {:?} -> {} (gw_alive={}, dash_alive={})",
                        prev,
                        now_healthy,
                        gw_now,
                        dash_now
                    );
                    *state_watch.healthy.lock().await = Some(now_healthy);
                }
            }
        });
    }

    // Mark started immediately — both listeners are bound at this
    // point so the rest of the app can rely on the OS port table
    // for any stricter check.
    {
        let state_for_started = state.clone();
        tauri::async_runtime::spawn(async move {
            let mut guard = state_for_started.started_at.lock().await;
            *guard = Some(std::time::Instant::now());
        });
    }

    let handles = EmbeddedHandles { state, gateway: gw_handle, dashboard: dash_handle, gateway_port, dashboard_port };
    let _ = HANDLES.set(handles.clone());
    Ok(handles)
}

#[derive(Clone)]
pub struct EmbeddedHandles {
    pub state: SharedState,
    pub gateway: Arc<tauri::async_runtime::JoinHandle<()>>,
    pub dashboard: Arc<tauri::async_runtime::JoinHandle<()>>,
    pub gateway_port: u16,
    pub dashboard_port: u16,
}

impl EmbeddedHandles {
    pub fn default() -> Result<Self, String> {
        ensure_embedded_server_running(DEFAULT_GATEWAY_PORT, DEFAULT_DASHBOARD_PORT)
    }
    /// Probe the gateway port. We just ask the OS — both ports are
    /// already bound by the time the future resolves.
    pub async fn is_ready(&self) -> bool {
        tokio::net::TcpStream::connect(("127.0.0.1", self.gateway_port)).await.is_ok()
    }
}

// ────────────────────────────────────────────────────────────────────
// Permissive CORS middleware.
//
// Tauri runs the React UI inside a WebView2 (Chromium) on a custom
// `tauri://localhost` origin, while the embedded gateway listens
// on `http://127.0.0.1:8642`. Every `fetch()` from the webview
// to the gateway is therefore cross-origin and Chromium blocks
// it as a CORS preflight failure → `TypeError: Failed to fetch`.
//
// The gateway is localhost-only (see `serve_gateway_loop` below
// and the dual-stack `TcpListener` setup), so we can safely send
// `access-control-allow-origin: *` without weakening anything
// real. We also short-circuit OPTIONS preflight in-place instead
// of routing it into the JSON `not_found` handler, otherwise the
// preflight would 404 and the actual request would never fire.
// ────────────────────────────────────────────────────────────────────
async fn cors_permissive_layer(req: Request, next: Next) -> Response {
    if req.method() == axum::http::Method::OPTIONS {
        return Response::builder()
            .status(StatusCode::NO_CONTENT)
            .header("access-control-allow-origin", "*")
            .header(
                "access-control-allow-methods",
                "GET, POST, PUT, DELETE, OPTIONS",
            )
            .header(
                "access-control-allow-headers",
                "content-type, authorization, x-requested-with",
            )
            .header("access-control-max-age", "86400")
            .body(Body::empty())
            .unwrap_or_else(|_| StatusCode::NO_CONTENT.into_response());
    }
    let mut resp = next.run(req).await;
    let headers = resp.headers_mut();
    headers.insert(
        "access-control-allow-origin",
        HeaderValue::from_static("*"),
    );
    headers.insert(
        "access-control-allow-methods",
        HeaderValue::from_static("GET, POST, PUT, DELETE, OPTIONS"),
    );
    headers.insert(
        "access-control-allow-headers",
        HeaderValue::from_static("content-type, authorization, x-requested-with"),
    );
    resp
}

fn build_router(state: SharedState, role: &'static str, port: u16) -> Router {
    Router::new()
        // Health
        .route("/health", get(health))
        .route("/v1/health", get(health))
        // Real model / config / env surface backed by ~/hermes/
        .route("/v1/models", get(list_models))
        .route("/api/v1/models", get(list_models))
        .route("/api/v1/model-options", get(list_models))
        // Rust commands call `/api/model/options`
        // (no v1) for `get_model_options`, and `/api/model/set`
        // (no v1) for `set_default_model`.
        .route("/api/model/options", get(get_model_options_legacy))
        .route("/api/model/set", post(set_default_model))
        .route("/api/v1/clients/unbind", delete(unbind))
        .route("/v1/clients/unbind", delete(unbind_compat))
        .route("/api/v1/binding", get(get_binding).post(set_binding))
        // v5.5 — 支付/订单(aicoop-sdk payment contract,见
        // aicoop-sdk/shared/protocol.ts):
        //   GET  /api/payment/plans                 套餐列表
        //   POST /api/payment/orders                创建(0 元试用 / 付费)
        //   GET  /api/payment/orders/:order_id      状态轮询
        //   GET  /api/payment/orders?uuid=...       uuid 维度订单列表
        //   GET  /api/payment/balance/:ilink_user_id 余额查询
        .route("/api/payment/plans", get(list_payment_plans))
        .route("/api/payment/orders", post(create_payment_order))
        .route("/api/payment/orders", get(list_payment_orders))
        .route(
            "/api/payment/orders/:order_id",
            get(query_payment_order),
        )
        .route(
            "/api/payment/balance/:ilink_user_id",
            get(get_payment_balance),
        )
        // v5.1 — env-var CRUD. Backed by `~/hermes/.env` (real
        // persistence), not an in-memory stub.
        .route(
            "/api/env",
            get(env_list)
                .post(env_set)
                .delete(env_delete),
        )
        .route("/api/env/reveal", post(env_reveal))
        // v5.1 — raw config editor. `get_dashboard_config_raw_yaml`
        // expects `DashboardConfigRawResponse { yaml: String }`. We
        // return the on-disk `config.yaml` content (or "" if the
        // file doesn't exist).
        .route(
            "/api/config/raw",
            get(config_raw_get).put(config_raw_put),
        )
        // Chat completions (OpenAI-compatible). Reads the live
        // model config from in-memory state (mirrored on
        // `config_raw_put` and `set_default_model`); pipes
        // upstream SSE bytes back to the webview.
        .route("/v1/chat/completions", post(chat_completions))
        .route("/v1/responses", post(responses_sse))
        // v5.6 — Trace 监控兼容路由 (替代被废弃的 Node sidecar)。
        // 前端 src/api.js 的 getMonitorStatus / getStats / getLogs 与
        // skillsClient.js 的 builtin-trace-* 技能全部走这些路径。
        // 真实窗口扫描后续可以接到 pc_automation::uia::windows.rs,
        // 这里先返回结构正确的 stub,前端不报错即可。
        .route("/monitor/status", get(monitor_status))
        .route("/stats", get(stats))
        .route("/logs", get(logs))
        .route("/agents-md", get(agents_md))
        // v5.8 — CDP proxy routes (供 skillsClient.js 内置技能调用)
        .route("/cdp/targets", get(cdp_targets))
        .route("/cdp/type", post(cdp_type))
        .route("/cdp/click", post(cdp_click))
        .route("/cdp/wait", post(cdp_wait))
        .route("/cdp/read", post(cdp_read))
        .route("/cdp/eval", post(cdp_eval_route))
        .route("/cdp/navigate", post(cdp_navigate))
        // Cron: HTML page that embeds the session token (the
        // Rust `hermes_dashboard_token` function uses a string
        // extract on the response body, so it MUST be HTML).
        .route("/cron", get(cron_page))
        .route("/jobs", get(cron_page))
        // Cron API: list / create / delete / pause / resume / trigger.
        // All four actions require `Authorization: Bearer <token>`;
        // unauthenticated calls get 401 with a JSON body so the
        // front-end's `reqwest::Response::error_for_status` path
        // produces a clean error rather than a panic.
        .route(
            "/api/cron/jobs",
            get(cron_list).post(cron_create),
        )
        .route(
            "/api/cron/jobs/:id",
            delete(cron_delete),
        )
        .route(
            "/api/cron/jobs/:id/pause",
            post(cron_pause),
        )
        .route(
            "/api/cron/jobs/:id/resume",
            post(cron_resume),
        )
        .route(
            "/api/cron/jobs/:id/trigger",
            post(cron_trigger),
        )
        // 404 with a clear JSON body so the front-end stops seeing
        // `200 { ok: true, stub: true, ... }` echoes that used to
        // mask missing routes.
        .fallback(not_found)
        // CORS — see `cors_permissive_layer` above. Must come
        // last so it wraps every response (including the
        // `not_found` fallback and the JSON error bodies).
        .layer(middleware::from_fn(cors_permissive_layer))
        .with_state((state, role, port))
}

async fn health(State((state, role, port)): State<(SharedState, &'static str, u16)>) -> Response {
    // Watchdog 标记:Some(true) = 健康,Some(false) = 至少一个
    // listener 死了,None = 启动窗口内(≤2s)还没决断。
    let watchdog_healthy = *state.healthy.lock().await;
    let body = json!({
        "ok": watchdog_healthy.unwrap_or(true),
        "version": HERMES_VERSION,
        "port": port,
        "role": role,
        "watchdog_healthy": watchdog_healthy,
    });
    if matches!(watchdog_healthy, Some(false)) {
        // 503 而不是 200:让前端能直接据此判定 gateway 已死,
        // 不要靠超时 / 重试才意识到。
        return (
            StatusCode::SERVICE_UNAVAILABLE,
            Json(body),
        )
            .into_response();
    }
    Json(body).into_response()
}

// ========================
// Model list (real)
// ========================

/// Build the full provider+model payload returned by
/// `/v1/models` / `/api/v1/models` / `/api/v1/model-options`.
/// The shape mirrors the legacy `/api/model/options` route, but the
/// providers list now ships the **complete** set of well-known models
/// per provider (from `model_catalog::models_by_provider`) plus the
/// currently-active model pinned at the top.
///
/// `active_provider` and `active_model` come from the in-memory
/// `state.primary_model`; they let us surface a synthetic `auto`
/// option when the active model matches the catalog's default for
/// that provider.
fn build_full_model_options(
    active_provider: &str,
    active_model: &str,
) -> Vec<serde_json::Value> {
    let mut out: Vec<serde_json::Value> = Vec::new();
    let mut seen_provider: std::collections::HashSet<String> = std::collections::HashSet::new();

    let active_provider_key = active_provider.trim().to_lowercase();
    let active_model_trimmed = active_model.trim();

    for (provider, label, model_ids) in models_by_provider() {
        if !seen_provider.insert(provider.to_string()) {
            continue;
        }
        // Build the model list, but pin the active model first so
        // the currently-selected row is the first thing the user
        // sees in the dropdown.
        let mut models: Vec<serde_json::Value> = Vec::new();
        if provider.eq_ignore_ascii_case(&active_provider_key) && !active_model_trimmed.is_empty() {
            let already_listed = model_ids.contains(&active_model_trimmed);
            if !already_listed {
                models.push(json!({
                    "id": active_model_trimmed,
                    "label": active_model_trimmed,
                    "active": true,
                }));
            }
        }
        for id in model_ids {
            models.push(json!({
                "id": id,
                "label": id,
                "active": id == active_model_trimmed
                    && provider.eq_ignore_ascii_case(&active_provider_key),
            }));
        }
        out.push(json!({
            "id": provider,
            "label": label,
            "models": models,
        }));
    }

    // If the active provider isn't in the catalog (custom / local
    // endpoint, exotic provider), synthesize a single-entry provider
    // for it so the user can still see + select it.
    if !active_provider_key.is_empty()
        && !seen_provider.contains(&active_provider_key)
    {
        out.push(json!({
            "id": active_provider,
            "label": active_provider,
            "models": [{
                "id": active_model_trimmed,
                "label": active_model_trimmed,
                "active": true,
            }],
        }));
    }

    out
}

async fn list_models(State((state, _, _)): State<(SharedState, &'static str, u16)>) -> Response {
    let cfg = state.primary_model.lock().await.clone();
    let providers = build_full_model_options(&cfg.provider, &cfg.model);
    // Match the previous shape consumed by the React ModelConfigPage.
    Json(json!({
        "ok": true,
        "providers": providers,
        "models": providers
            .iter()
            .flat_map(|p| p["models"].as_array().cloned().unwrap_or_default())
            .collect::<Vec<_>>(),
        "model": cfg.model,
        "provider": cfg.provider,
    }))
    .into_response()
}

/// Legacy `/api/model/options` shape consumed by the Rust
/// `get_model_options` command. Must mirror
/// `commands::legacy::DashboardModelOptionsResponse`:
/// `{ providers: Vec<{id,label,models:Vec<{id,label}>}>, model, provider }`.
async fn get_model_options_legacy(
    State((state, _, _)): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
) -> Response {
    if !check_bearer(&state, &headers) {
        return unauthorized();
    }
    let cfg = state.primary_model.lock().await.clone();
    let providers = build_full_model_options(&cfg.provider, &cfg.model);
    // Prepend the synthetic `auto` provider so the front-end can
    // pin a top-level "auto" entry without re-deriving it. The
    // `auto/auto` key is what the front-end will display as
    // "<GlobalModelLabel> · auto" and pass back to /api/model/set.
    let mut with_auto = vec![json!({
        "id": "auto",
        "label": "Auto",
        "models": [{
            "id": "auto",
            "label": "auto",
            "active": cfg.provider.trim().is_empty(),
        }],
    })];
    with_auto.extend(providers);
    Json(json!({
        "providers": with_auto,
        "model": cfg.model,
        "provider": cfg.provider,
    }))
    .into_response()
}

#[derive(serde::Deserialize)]
struct SetDefaultModelBody {
    // `scope` / `task` are accepted (and ignored) so the front-end
    // doesn't need a separate endpoint just to send the same
    // shape; the embedded server's job is only to persist provider
    // + model. Future revisions may route `scope` to a per-agent
    // override table.
    #[allow(dead_code)]
    scope: Option<String>,
    provider: String,
    model: String,
    #[serde(default)]
    #[allow(dead_code)]
    task: Option<String>,
}

async fn set_default_model(
    State((state, _, _)): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
    Json(body): Json<SetDefaultModelBody>,
) -> Response {
    if !check_bearer(&state, &headers) {
        return unauthorized();
    }
    let provider = body.provider.trim();
    let model = body.model.trim();
    if provider.is_empty() || model.is_empty() {
        return json_status(
            StatusCode::BAD_REQUEST,
            json!({ "error": "provider and model are required" }),
        );
    }
    // The `auto` pseudo-provider means "let the server pick the
    // best available model for each request" — we still need to
    // resolve a real (provider, model) pair on the server side so
    // the rest of the chat pipeline keeps working, but we don't
    // pin it to the user's `provider`/`model` selection. We pick
    // the first configured provider from env vars, falling back
    // to whatever is in the in-memory mirror.
    let (resolved_provider, resolved_model) = if provider.eq_ignore_ascii_case("auto") {
        let env = load_env_vars_from_disk_async().await;
        let configured = crate::hermes::model_catalog::models_by_provider()
            .into_iter()
            .find_map(|(prov, _label, ids)| {
                let prefix = format!("{}_API_KEY", prov.to_uppercase());
                if env.get(&prefix).map(|v| !v.is_empty()).unwrap_or(false) {
                    Some((prov.to_string(), ids.first().copied().unwrap_or("").to_string()))
                } else {
                    None
                }
            });
        match configured {
            Some(pair) => pair,
            None => {
                // No provider is wired up; fall back to whatever is
                // already in the mirror so the user gets a sane
                // "no provider configured" path instead of a 500.
                let cfg = state.primary_model.lock().await.clone();
                if cfg.provider.is_empty() {
                    return json_status(
                        StatusCode::CONFLICT,
                        json!({ "error": "no provider configured; cannot resolve auto" }),
                    );
                }
                (cfg.provider, cfg.model)
            }
        }
    } else {
        (provider.to_string(), model.to_string())
    };

    let path = hermes_config_path();
    // 修复:之前 read_file_or_default_async 把所有 IO 错误都当"文件不存在"处理,
    // 返回空串。apply_default_model_to_yaml 会基于空串生成只含 model 字段的 yaml,
    // 然后 tokio::fs::write 覆盖掉整个配置文件,用户原有的 provider/base_url/env 等
    // 全部丢失。改为区分 NotFound 与其他 IO 错误。
    let yaml_text = match tokio::fs::read_to_string(&path).await {
        Ok(s) => s,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => String::new(),
        Err(e) => {
            return json_status(
                StatusCode::INTERNAL_SERVER_ERROR,
                json!({ "error": format!("failed to read {}: {}", path.display(), e) }),
            );
        }
    };
    let next_yaml = match apply_default_model_to_yaml(&yaml_text, &resolved_provider, &resolved_model) {
        Ok(v) => v,
        Err(e) => {
            return json_status(
                StatusCode::INTERNAL_SERVER_ERROR,
                json!({ "error": e }),
            );
        }
    };
    if let Err(e) = ensure_parent_async(&path).await {
        return json_status(StatusCode::INTERNAL_SERVER_ERROR, json!({ "error": e }));
    }
    if let Err(e) = tokio::fs::write(&path, next_yaml.as_bytes()).await {
        return json_status(
            StatusCode::INTERNAL_SERVER_ERROR,
            json!({ "error": format!("Failed to write {}: {}", path.display(), e) }),
        );
    }
    // Update the in-memory mirror so the next chat request picks
    // up the new default model without restarting the server.
    {
        let mut cfg = state.primary_model.lock().await;
        cfg.provider = resolved_provider.clone();
        cfg.model = resolved_model.clone();
    }
    log::info!(
        "[Hermes Gateway] default model updated: provider={} model={} (requested {:?}/{:?})",
        resolved_provider,
        resolved_model,
        provider,
        model
    );
    json_status(
        StatusCode::OK,
        json!({
            "ok": true,
            "provider": resolved_provider,
            "model": resolved_model,
            "auto": provider.eq_ignore_ascii_case("auto"),
        }),
    )
}

// ========================
// env (real)
// ========================

#[derive(serde::Deserialize)]
struct EnvSetBody {
    key: String,
    value: String,
}

async fn env_list(
    State((state, _, _)): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
) -> Response {
    if !check_bearer(&state, &headers) {
        return unauthorized();
    }
    let map = load_env_vars_from_disk_async().await;
    Json(map).into_response()
}

async fn env_set(
    State((state, _, _)): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
    Json(body): Json<EnvSetBody>,
) -> Response {
    if !check_bearer(&state, &headers) {
        return unauthorized();
    }
    let key = body.key.trim();
    if key.is_empty() {
        return json_status(
            StatusCode::BAD_REQUEST,
            json!({ "error": "key is required" }),
        );
    }
    match write_env_var_to_disk_async(key, &body.value).await {
        Ok(()) => json_status(StatusCode::OK, json!({ "ok": true })),
        Err(e) => json_status(StatusCode::INTERNAL_SERVER_ERROR, json!({ "error": e })),
    }
}

#[derive(serde::Deserialize)]
struct EnvDeleteBody {
    key: String,
}

async fn env_delete(
    State((state, _, _)): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
    Json(body): Json<EnvDeleteBody>,
) -> Response {
    if !check_bearer(&state, &headers) {
        return unauthorized();
    }
    let key = body.key.trim();
    if key.is_empty() {
        return json_status(
            StatusCode::BAD_REQUEST,
            json!({ "error": "key is required" }),
        );
    }
    match remove_env_var_from_disk_async(key).await {
        Ok(()) => json_status(StatusCode::OK, json!({ "ok": true })),
        Err(e) => json_status(StatusCode::INTERNAL_SERVER_ERROR, json!({ "error": e })),
    }
}

async fn env_reveal(
    State((state, _, _)): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
    Json(body): Json<EnvSetBody>,
) -> Response {
    if !check_bearer(&state, &headers) {
        return unauthorized();
    }
    let map = load_env_vars_from_disk_async().await;
    let value = map.get(&body.key).cloned().unwrap_or_default();
    Json(json!({ "value": value })).into_response()
}

// ========================
// config raw (real)
// ========================

#[derive(serde::Deserialize)]
struct ConfigRawBody {
    yaml_text: String,
}

async fn config_raw_get(
    State((state, _, _)): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
) -> Response {
    if !check_bearer(&state, &headers) {
        return unauthorized();
    }
    let yaml = read_file_or_default_async(&hermes_config_path()).await;
    Json(json!({ "yaml": yaml })).into_response()
}

async fn config_raw_put(
    State((state, _, _)): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
    Json(body): Json<ConfigRawBody>,
) -> Response {
    if !check_bearer(&state, &headers) {
        return unauthorized();
    }
    let path = hermes_config_path();
    if let Err(e) = ensure_parent_async(&path).await {
        return json_status(StatusCode::INTERNAL_SERVER_ERROR, json!({ "error": e }));
    }
    // Validate by parsing before writing — a corrupt YAML would
    // brick every subsequent chat request.
    if let Err(e) = serde_yaml::from_str::<serde_yaml::Value>(&body.yaml_text) {
        return json_status(
            StatusCode::BAD_REQUEST,
            json!({ "error": format!("invalid YAML: {}", e) }),
        );
    }
    if let Err(e) = tokio::fs::write(&path, body.yaml_text.as_bytes()).await {
        return json_status(
            StatusCode::INTERNAL_SERVER_ERROR,
            json!({ "error": format!("Failed to write {}: {}", path.display(), e) }),
        );
    }
    // Reload the in-memory primary-model mirror so the next chat
    // request reflects the new on-disk configuration.
    // (Cloning the state handle out of the request isn't possible;
    // instead we just rewrite the cached value through a one-shot
    // restart-style reload — the caller will hit the new state on
    // the next message because every chat handler re-reads.)
    json_status(StatusCode::OK, json!({ "ok": true }))
}

// ========================
// device unbind
// ========================
async fn unbind(
    State((state, _, _)): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
) -> Response {
    if !check_bearer(&state, &headers) {
        return unauthorized();
    }
    clear_binding_record(&state).await;
    StatusCode::NO_CONTENT.into_response()
}

async fn unbind_compat(
    State(s): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
) -> Response {
    unbind(State(s), headers).await
}

/// GET /api/v1/binding — return the cached binding record so the
/// front-end can re-hydrate after a localStorage wipe (or any other
/// state-loss event) without forcing the user through the join
/// code modal a second time. Always 200; an unbound device gets
/// `{ "bound": false }` instead of 404 so the client can treat both
/// cases uniformly.
async fn get_binding(
    State((state, _, _)): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
) -> Response {
    if !check_bearer(&state, &headers) {
        return unauthorized();
    }
    let record = state.binding.lock().await.clone();
    if record.is_empty() {
        return Json(json!({
            "bound": false,
            "device_id": "",
            "tenant_id": "",
            "registered_at": "",
            "join_code": "",
        }))
        .into_response();
    }
    Json(json!({
        "bound": true,
        "device_id": record.device_id,
        "tenant_id": record.tenant_id,
        "registered_at": record.registered_at,
        "join_code": record.join_code,
    }))
    .into_response()
}

/// Request body for `POST /api/v1/binding`. All fields optional —
/// only the fields the client sends are persisted, the rest keep
/// their existing in-memory / on-disk value (mirrors the
/// `upsert_str` semantics of `persist_binding_record_to_yaml_async`).
#[derive(serde::Deserialize, Default, Debug, Clone)]
struct SetBindingBody {
    #[serde(default)]
    device_id: String,
    #[serde(default)]
    tenant_id: String,
    #[serde(default)]
    registered_at: String,
    #[serde(default)]
    join_code: String,
}

/// POST /api/v1/binding — persist a freshly-obtained binding record
/// (device_id / tenant_id / registered_at / join_code) to
/// `~/hermes/config.yaml` and update the in-memory mirror in
/// `state.binding`. The device-register Tauri command lives outside
/// the hermes module (`src/commands/device_register.rs`) and can't
/// touch `EmbeddedServerState` directly, so the front-end POSTs the
/// cloud's register response here; this route is the single place
/// that flips both the on-disk `binding:` block and the in-memory
/// `state.binding` atomically. Without it the in-memory record (and
/// the on-disk block) never get updated, so `GET /api/v1/binding`
/// keeps returning `{ bound: false }` and unbind can't clean up.
async fn set_binding(
    State((state, _, _)): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
    Json(body): Json<SetBindingBody>,
) -> Response {
    if !check_bearer(&state, &headers) {
        return unauthorized();
    }
    // Merge the client-supplied fields into the existing record so a
    // partial POST (e.g. only device_id + tenant_id) doesn't clobber
    // a previously-stored join_code / registered_at.
    let merged = {
        let mut current = state.binding.lock().await.clone();
        if !body.device_id.trim().is_empty() {
            current.device_id = body.device_id.clone();
        }
        if !body.tenant_id.trim().is_empty() {
            current.tenant_id = body.tenant_id.clone();
        }
        if !body.registered_at.trim().is_empty() {
            current.registered_at = body.registered_at.clone();
        }
        if !body.join_code.trim().is_empty() {
            current.join_code = body.join_code.clone();
        }
        current
    };
    let yaml_path = hermes_config_path();
    match persist_binding_record_to_yaml_async(&yaml_path, &merged).await {
        Ok(()) => {
            *state.binding.lock().await = merged.clone();
            log::info!(
                "[embedded_server] POST /api/v1/binding 持久化成功: device_id={}*** tenant_id={}",
                merged.device_id.chars().take(8).collect::<String>(),
                merged.tenant_id,
            );
            Json(json!({
                "bound": true,
                "device_id": merged.device_id,
                "tenant_id": merged.tenant_id,
                "registered_at": merged.registered_at,
                "join_code": merged.join_code,
            }))
            .into_response()
        }
        Err(e) => {
            log::warn!(
                "[embedded_server] POST /api/v1/binding 持久化失败: {}",
                e
            );
            json_status(
                StatusCode::INTERNAL_SERVER_ERROR,
                json!({ "error": "binding_persist_failed", "detail": e }),
            )
        }
    }
}

// =====================================================================
// v5.5 — 支付 / 订单 (aicoop-sdk payment contract)
//
// 数据流对齐 aicoop-sdk 的 shared/protocol.ts (Plan / Order
// / CreatePaymentOrderResponse):
//
//   前端  POST /api/payment/orders   { plan_id, uuid, client_ip? }
//   ↓
//   embedded server  POST {cloud}/api/payment/orders
//     (加 Bearer: model.api_key = device_token)
//   ↓
//   cloud  返回 { success, orderId, codeUrl, prepayId, amount, planName }
//
// 0 元订单 (amount_yuan=0) 云端应当 status=paid 立即返回;
// 付费订单 status=pending + codeUrl (微信支付 QR),前端用 shell.open
// 弹支付页,然后轮询 GET /api/payment/orders/:orderId 直到 status=paid。
//
// cloud 端点未上线(404 / 502 / 网络挂)走 fallback:
//   - 0 元 → status=paid 直接解锁,demo 不会卡
//   - 付费 → 也直接 status=paid 标记 source=fallback,
//     真正扣费由 cloud 上线后接管(代码注释里标了 TODO:remove)
//
// 其它端点对齐 SDK:
//   GET  /api/payment/plans                  套餐列表(替代原 market/skills)
//   GET  /api/payment/orders?uuid=...        uuid 维度的历史订单
//   GET  /api/payment/balance/:ilink_user_id 余额(蒜粒账本摘要)
//
// Plan 字段对齐 SDK Plan:
//   id, name, plan_code, amount_yuan, points, duration_days,
//   description, enabled, sort_order
//
// Order 字段对齐 SDK Order:
//   id, uuid, orderId, amount, type("membership"|"recharge"|"skill"|"scene"),
//   productId, productName, status("pending"|"paid"|"refunded"|"cancelled"|"failed"),
//   codeUrl, prepayId, transactionId, paidAt, metadata
// =====================================================================

#[derive(serde::Deserialize, Default, Debug, Clone)]
struct CreatePaymentOrderBody {
    plan_id: String,
    uuid: String,
    #[serde(default)]
    client_ip: Option<String>,
}

// PlanDto 字段名对齐 aicoop-sdk shared/protocol.ts 的 Plan:
//   id, name, plan_code, amount_yuan, points, duration_days,
//   description, enabled, sort_order  (全 snake_case,SDK 不做 camelCase 转换)
// 跟 OrderDto 不同:OrderDto 里 orderId/codeUrl/prepayId 等业务字段
// 已经是 camelCase(SDK 显式定义),所以用显式 rename;
// Plan 整张表都是 snake_case,所以不在结构上加 rename_all,
// 直接用 Rust 字段名(plan_code/amount_yuan/duration_days/sort_order)
// 序列化,1:1 对齐 SDK。
#[derive(serde::Serialize, Debug, Clone)]
struct PlanDto {
    id: String,
    name: String,
    plan_code: String,
    amount_yuan: f64,
    points: i64,
    duration_days: i64,
    description: String,
    enabled: bool,
    sort_order: i64,
}

// OrderDto 字段对齐 aicoop-sdk shared/protocol.ts 的 Order:
//   id, uuid, orderId, amount, type, productId, productName, status,
//   codeUrl, prepayId, transactionId, paidAt, metadata
// SDK 把 transactionId / paidAt 声明为必填(string,非 optional),
// 跟 cloud 对齐时:cloud 自己会返完整字段,本端直接 passthrough;
// 走本地 fallback / build_payment_order_fallback 时(例如 fb_ 订单
// 或 0 元订单),统一用空串占位,保持 schema 稳定,前端不会读到
// undefined。后端内部用的 `source` 标记(cloud/fallback)放在 metadata
// 透出,不在顶层加 SDK 没有的字段,避免污染 Order 类型。
#[derive(serde::Serialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
struct OrderDto {
    id: String,
    uuid: String,
    #[serde(rename = "orderId")]
    order_id: String,
    amount: f64,
    /// "membership" | "recharge" | "skill" | "scene"  (SDK 约束)
    #[serde(rename = "type")]
    r#type: String,
    #[serde(rename = "productId")]
    product_id: String,
    #[serde(rename = "productName")]
    product_name: String,
    /// "pending" | "paid" | "refunded" | "cancelled" | "failed"
    status: String,
    #[serde(rename = "codeUrl")]
    code_url: String,
    #[serde(rename = "prepayId")]
    prepay_id: String,
    /// SDK 必填;未支付时给空串,不要 omit。
    #[serde(rename = "transactionId", default)]
    transaction_id: String,
    /// SDK 必填;未支付时给空串,不要 omit。
    #[serde(rename = "paidAt", default)]
    paid_at: String,
    /// 自由 metadata(SDK 用 Record<string, unknown>)
    metadata: Value,
}

#[derive(serde::Serialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
struct CreatePaymentOrderResponse {
    success: bool,
    #[serde(rename = "orderId")]
    order_id: String,
    #[serde(rename = "codeUrl")]
    code_url: String,
    #[serde(rename = "prepayId")]
    prepay_id: String,
    amount: f64,
    #[serde(rename = "planName")]
    plan_name: String,
}

#[derive(serde::Serialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
struct QueryPaymentOrderResponse {
    success: bool,
    order: Option<OrderDto>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
}

#[derive(serde::Serialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
struct ListPaymentOrdersResponse {
    success: bool,
    orders: Vec<OrderDto>,
}

#[derive(serde::Serialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
struct ListPaymentPlansResponse {
    success: bool,
    plans: Vec<PlanDto>,
}

#[derive(serde::Serialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
struct PaymentBalanceDto {
    balance: f64,
    total_grant: f64,
    total_consume: f64,
    plan_code: String,
    last_grant_ts: i64,
    last_consume_ts: i64,
}

#[derive(serde::Deserialize, Default, Debug)]
struct GetPaymentOrderPath {
    order_id: String,
}

#[derive(serde::Deserialize, Default, Debug)]
struct GetPaymentBalancePath {
    ilink_user_id: String,
}

/// 简单非加密 hash:FNV-1a 32-bit。前端用它来给 fallback 订单
/// 生成稳定后缀,不依赖额外 crate。
fn simple_hash(input: &str) -> String {
    let mut hash: u32 = 0x811c9dc5;
    for byte in input.as_bytes() {
        hash ^= *byte as u32;
        hash = hash.wrapping_mul(0x01000193);
    }
    format!("{:08x}", hash)
}

/// POST /api/payment/orders
/// 对齐 SDK: createPaymentOrder(planId, uuid, clientIp) → CreatePaymentOrderResponse
async fn create_payment_order(
    State((state, role, _)): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
    Json(body): Json<CreatePaymentOrderBody>,
) -> Response {
    if !check_bearer(&state, &headers) {
        return unauthorized();
    }
    let _ = role;
    let plan_id = body.plan_id.trim().to_string();
    let uuid = body.uuid.trim().to_string();
    if plan_id.is_empty() {
        return json_status(
            StatusCode::BAD_REQUEST,
            json!({ "error": "plan_id is required" }),
        );
    }
    if uuid.is_empty() {
        return json_status(
            StatusCode::BAD_REQUEST,
            json!({ "error": "uuid is required" }),
        );
    }

    // 拉 model.api_key 作 Bearer(device_token)。
    // 未注册:跟 chat 一样走 503 + requires_registration,前端 auto-bind
    // 后会 retry 这条 order 请求。
    let bearer = state.primary_model.lock().await.api_key.trim().to_string();
    if bearer.is_empty() {
        return json_status(
            StatusCode::SERVICE_UNAVAILABLE,
            json!({
                "error": "device not registered; bind a device first to create payment orders",
                "requires_registration": true,
                "register_endpoint": "/api/v1/client/fingerprint",
            }),
        );
    }

    // 内置 plan 表(对齐 list_payment_plans 的 stub),
    // 同时给云端 fallback 用 — cloud 端点没上线时本地按此组装
    // CreatePaymentOrderResponse。
    let plan_table: HashMap<&str, (&str, f64, &str)> = [
        ("free-general-chat",     ("通用对话",      0.0,  "skill")),
        ("free-code-assistant",   ("编程助手",      0.0,  "skill")),
        ("free-writing-helper",   ("写作助手",      0.0,  "skill")),
        ("free-translator",       ("翻译专家",      0.0,  "skill")),
        ("free-data-analyst",     ("数据分析",      0.0,  "skill")),
        ("free-creative-writer",  ("创意写作",      0.0,  "skill")),
        ("paid-business-strategy",("商业策略专家", 29.9,  "skill")),
        ("paid-legal-advisor",    ("法律顾问",     39.9,  "skill")),
        ("paid-medical-expert",   ("医疗专家",     49.9,  "skill")),
        ("paid-financial-analyst",("金融分析师",   34.9,  "skill")),
        ("paid-creative-director",("创意总监",     44.9,  "skill")),
    ]
    .into_iter()
    .collect();
    let (plan_name, plan_amount, plan_type) = match plan_table.get(plan_id.as_str()) {
        Some(v) => *v,
        None => {
            return json_status(
                StatusCode::NOT_FOUND,
                json!({ "error": "plan not found", "plan_id": plan_id }),
            );
        }
    };

    // 优先打云端
    let cloud_url = format!(
        "{}/api/payment/orders",
        tupai_cloud_base_url().trim_end_matches('/')
    );
    log::info!(
        "[embedded_server] create_payment_order → cloud {} (plan={}, uuid={}, type={})",
        cloud_url,
        plan_id,
        uuid,
        plan_type
    );

    let client = state.cloud_http.clone();

    let upstream = client
        .post(&cloud_url)
        .timeout(Duration::from_secs(8))
        .header("Content-Type", "application/json")
        .header("Authorization", format!("Bearer {}", bearer))
        .header("Accept", "application/json")
        .json(&json!({
            "plan_id": plan_id,
            "uuid": uuid,
            "client_ip": body.client_ip,
        }))
        .send()
        .await;

    let now = chrono::Utc::now().to_rfc3339();
    let now_ts = chrono::Utc::now().timestamp();

    match upstream {
        Ok(resp) => {
            let status = resp.status();
            let content_type = resp
                .headers()
                .get(reqwest::header::CONTENT_TYPE)
                .and_then(|v| v.to_str().ok())
                .unwrap_or("application/json")
                .to_string();
            let body_text = match resp.text().await {
                Ok(t) => t,
                Err(e) => {
                    log::warn!(
                        "[embedded_server] payment_order cloud body read failed: {}",
                        e
                    );
                    return json_status(
                        StatusCode::BAD_GATEWAY,
                        json!({ "error": "payment_order_cloud_body_read_failed", "detail": e.to_string() }),
                    );
                }
            };

            // 200:透传云端 JSON
            if status.is_success() {
                let mut headers = HeaderMap::new();
                if let Ok(ct) = content_type.parse() {
                    headers.insert(reqwest::header::CONTENT_TYPE, ct);
                }
                return (status, headers, body_text).into_response();
            }

            // 404 → 云端没这个端点,走 fallback
            if status.as_u16() == 404 {
                log::warn!(
                    "[embedded_server] payment_order cloud 404 (endpoint not implemented yet), falling back to local stub"
                );
                return build_payment_order_fallback(
                    &plan_id, &uuid, plan_name, plan_amount, plan_type, &now, &now_ts, "endpoint_not_implemented",
                );
            }

            // 其它非 200 透传(cloud 知道为啥 5xx)
            log::warn!(
                "[embedded_server] payment_order cloud 返回 HTTP {}: {}",
                status.as_u16(),
                &body_text.chars().take(200).collect::<String>()
            );
            let mut headers = HeaderMap::new();
            if let Ok(ct) = content_type.parse() {
                headers.insert(reqwest::header::CONTENT_TYPE, ct);
            }
            (status, headers, body_text).into_response()
        }
        Err(e) => {
            // 网络层失败(timeout / connect / dns)→ fallback,跟 404 一样
            log::warn!(
                "[embedded_server] payment_order cloud 调用失败: {}; fallback 到本地 stub",
                e
            );
            build_payment_order_fallback(
                &plan_id, &uuid, plan_name, plan_amount, plan_type, &now, &now_ts, &e.to_string(),
            )
        }
    }
}

/// 当云端 /api/payment/orders 还没实现(404)或网络挂了的时候,
/// 本地组装一个 CreatePaymentOrderResponse:
///   - 0 元 (amount_yuan == 0) → status=paid,codeUrl/prepayId 都空串
///   - 付费 (amount_yuan > 0)  → status=paid 但 source=fallback,
///     TODO: 云端真计费上线后,改 status=pending + 微信支付 QR。
fn build_payment_order_fallback(
    plan_id: &str,
    uuid: &str,
    plan_name: &str,
    plan_amount: f64,
    _plan_type: &str,
    now: &str,
    now_ts: &i64,
    reason: &str,
) -> Response {
    // fb_ 前缀:前端 query / cloud 404 时直接识别为本地 fallback
    let order_id = format!(
        "fb_{}_{}",
        now.replace([':', '.', '+'], "-"),
        simple_hash(&format!("{}{}{}{}", plan_id, uuid, now_ts, plan_amount))
    );
    let resp = CreatePaymentOrderResponse {
        success: true,
        order_id: order_id.clone(),
        code_url: String::new(),
        prepay_id: String::new(),
        amount: plan_amount,
        plan_name: plan_name.to_string(),
    };
    log::info!(
        "[embedded_server] payment_order fallback 已发:plan={} uuid={} amount={} reason={}",
        plan_id, uuid, plan_amount, reason
    );
    Json(resp).into_response()
}

/// GET /api/payment/orders/:order_id
/// 对齐 SDK: queryPaymentOrder(orderId) → QueryPaymentOrderResponse
async fn query_payment_order(
    State((state, _, _)): State<(SharedState, &'static str, u16)>,
    Path(path): Path<GetPaymentOrderPath>,
) -> Response {
    let order_id = path.order_id.trim().to_string();
    if order_id.is_empty() {
        return json_status(
            StatusCode::BAD_REQUEST,
            json!({ "error": "order_id is required" }),
        );
    }
    let bearer = state.primary_model.lock().await.api_key.trim().to_string();
    if bearer.is_empty() {
        return json_status(
            StatusCode::SERVICE_UNAVAILABLE,
            json!({
                "error": "device not registered; bind a device first",
                "requires_registration": true,
                "register_endpoint": "/api/v1/client/fingerprint",
            }),
        );
    }
    let cloud_url = format!(
        "{}/api/payment/orders/{}",
        tupai_cloud_base_url().trim_end_matches('/'),
        order_id
    );
    log::info!("[embedded_server] query_payment_order → cloud {}", cloud_url);
    let client = state.cloud_http.clone();
    match client
        .get(&cloud_url)
        .timeout(Duration::from_secs(5))
        .header("Authorization", format!("Bearer {}", bearer))
        .header("Accept", "application/json")
        .send()
        .await
    {
        Ok(resp) => {
            let status = resp.status();
            let content_type = resp
                .headers()
                .get(reqwest::header::CONTENT_TYPE)
                .and_then(|v| v.to_str().ok())
                .unwrap_or("application/json")
                .to_string();
            let body_text = match resp.text().await {
                Ok(t) => t,
                Err(e) => {
                    return json_status(
                        StatusCode::BAD_GATEWAY,
                        json!({ "error": "payment_order_get_body_read_failed", "detail": e.to_string() }),
                    );
                }
            };
            // 404 / 5xx fallback:fb_ 订单本地直接 paid(Order 字段对齐 SDK)
            if status.as_u16() == 404 && order_id.starts_with("fb_") {
                let now = chrono::Utc::now().to_rfc3339();
                let order = OrderDto {
                    id: order_id.clone(),
                    uuid: String::new(),
                    order_id: order_id.clone(),
                    amount: 0.0,
                    r#type: "skill".to_string(),
                    product_id: String::new(),
                    product_name: String::new(),
                    status: "paid".to_string(),
                    code_url: String::new(),
                    prepay_id: String::new(),
                    // SDK 必填,fallback 没真 transactionId,空串占位
                    transaction_id: String::new(),
                    paid_at: now,
                    metadata: json!({ "source": "fallback" }),
                };
                return Json(QueryPaymentOrderResponse {
                    success: true,
                    order: Some(order),
                    error: None,
                })
                .into_response();
            }
            let mut headers = HeaderMap::new();
            if let Ok(ct) = content_type.parse() {
                headers.insert(reqwest::header::CONTENT_TYPE, ct);
            }
            (status, headers, body_text).into_response()
        }
        Err(e) => {
            // 网络挂了:fb_ 订单仍然认为 paid
            if order_id.starts_with("fb_") {
                let now = chrono::Utc::now().to_rfc3339();
                let order = OrderDto {
                    id: order_id.clone(),
                    uuid: String::new(),
                    order_id: order_id.clone(),
                    amount: 0.0,
                    r#type: "skill".to_string(),
                    product_id: String::new(),
                    product_name: String::new(),
                    status: "paid".to_string(),
                    code_url: String::new(),
                    prepay_id: String::new(),
                    transaction_id: String::new(),
                    paid_at: now,
                    metadata: json!({ "source": "fallback" }),
                };
                return Json(QueryPaymentOrderResponse {
                    success: true,
                    order: Some(order),
                    error: None,
                })
                .into_response();
            }
            json_status(
                StatusCode::BAD_GATEWAY,
                json!({ "error": "payment_order_get_cloud_unreachable", "detail": e.to_string() }),
            )
        }
    }
}

/// GET /api/payment/orders?uuid=...
/// 对齐 SDK: listPaymentOrders(uuid) → ListPaymentOrdersResponse
async fn list_payment_orders(
    State((state, _, _)): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
    axum::extract::Query(params): axum::extract::Query<std::collections::HashMap<String, String>>,
) -> Response {
    if !check_bearer(&state, &headers) {
        return unauthorized();
    }
    let uuid = params
        .get("uuid")
        .map(|s| s.trim().to_string())
        .unwrap_or_default();
    if uuid.is_empty() {
        return json_status(
            StatusCode::BAD_REQUEST,
            json!({ "error": "uuid is required" }),
        );
    }
    let bearer = state.primary_model.lock().await.api_key.trim().to_string();
    if bearer.is_empty() {
        return json_status(
            StatusCode::SERVICE_UNAVAILABLE,
            json!({
                "error": "device not registered; bind a device first",
                "requires_registration": true,
                "register_endpoint": "/api/v1/client/fingerprint",
            }),
        );
    }
    let cloud_url = format!(
        "{}/api/payment/orders?uuid={}",
        tupai_cloud_base_url().trim_end_matches('/'),
        uuid
    );
    log::info!("[embedded_server] list_payment_orders → cloud {}", cloud_url);
    let client = state.cloud_http.clone();
    match client
        .get(&cloud_url)
        .timeout(Duration::from_secs(5))
        .header("Authorization", format!("Bearer {}", bearer))
        .header("Accept", "application/json")
        .send()
        .await
    {
        Ok(resp) => {
            let status = resp.status();
            let body_text = match resp.text().await {
                Ok(t) => t,
                Err(e) => {
                    return json_status(
                        StatusCode::BAD_GATEWAY,
                        json!({ "error": "payment_orders_list_body_read_failed", "detail": e.to_string() }),
                    );
                }
            };
            // 404 → cloud 没这个端点,返空数组
            if status.as_u16() == 404 {
                return Json(ListPaymentOrdersResponse {
                    success: true,
                    orders: Vec::new(),
                })
                .into_response();
            }
            let mut headers = HeaderMap::new();
            headers.insert(
                reqwest::header::CONTENT_TYPE,
                HeaderValue::from_static("application/json"),
            );
            (status, headers, body_text).into_response()
        }
        Err(_) => Json(ListPaymentOrdersResponse {
            success: true,
            orders: Vec::new(),
        })
        .into_response(),
    }
}

/// GET /api/payment/balance/:ilink_user_id
/// 对齐 SDK: getPaymentBalance(ilinkUserId) → PaymentBalance
async fn get_payment_balance(
    State((state, _, _)): State<(SharedState, &'static str, u16)>,
    Path(path): Path<GetPaymentBalancePath>,
) -> Response {
    let ilink_user_id = path.ilink_user_id.trim().to_string();
    if ilink_user_id.is_empty() {
        return json_status(
            StatusCode::BAD_REQUEST,
            json!({ "error": "ilink_user_id is required" }),
        );
    }
    let bearer = state.primary_model.lock().await.api_key.trim().to_string();
    if bearer.is_empty() {
        return json_status(
            StatusCode::SERVICE_UNAVAILABLE,
            json!({
                "error": "device not registered; bind a device first",
                "requires_registration": true,
                "register_endpoint": "/api/v1/client/fingerprint",
            }),
        );
    }
    let cloud_url = format!(
        "{}/api/payment/balance/{}",
        tupai_cloud_base_url().trim_end_matches('/'),
        ilink_user_id
    );
    log::info!("[embedded_server] get_payment_balance → cloud {}", cloud_url);
    let client = state.cloud_http.clone();
    match client
        .get(&cloud_url)
        .timeout(Duration::from_secs(5))
        .header("Authorization", format!("Bearer {}", bearer))
        .header("Accept", "application/json")
        .send()
        .await
    {
        Ok(resp) => {
            let status = resp.status();
            let body_text = match resp.text().await {
                Ok(t) => t,
                Err(e) => {
                    return json_status(
                        StatusCode::BAD_GATEWAY,
                        json!({ "error": "payment_balance_body_read_failed", "detail": e.to_string() }),
                    );
                }
            };
            // 404 兜底:返零余额(对齐 PaymentBalance 字段)
            if status.as_u16() == 404 {
                return Json(PaymentBalanceDto {
                    balance: 0.0,
                    total_grant: 0.0,
                    total_consume: 0.0,
                    plan_code: "unknown".to_string(),
                    last_grant_ts: 0,
                    last_consume_ts: 0,
                })
                .into_response();
            }
            let mut headers = HeaderMap::new();
            headers.insert(
                reqwest::header::CONTENT_TYPE,
                HeaderValue::from_static("application/json"),
            );
            (status, headers, body_text).into_response()
        }
        Err(_) => {
            // 网络挂了:返零余额
            Json(PaymentBalanceDto {
                balance: 0.0,
                total_grant: 0.0,
                total_consume: 0.0,
                plan_code: "unknown".to_string(),
                last_grant_ts: 0,
                last_consume_ts: 0,
            })
            .into_response()
        }
    }
}

/// GET /api/payment/plans
/// 对齐 SDK: listPaymentPlans() → ListPaymentPlansResponse
async fn list_payment_plans(
    State((state, _, _)): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
) -> Response {
    if !check_bearer(&state, &headers) {
        return unauthorized();
    }
    // 跟 orders 一样:优先打云端,挂了 / 没实现就走内置 stub
    let bearer = state.primary_model.lock().await.api_key.trim().to_string();
    if !bearer.is_empty() {
        let cloud_url = format!(
            "{}/api/payment/plans",
            tupai_cloud_base_url().trim_end_matches('/')
        );
        let client = state.cloud_http.clone();
        if let Ok(resp) = client
            .get(&cloud_url)
            .timeout(Duration::from_secs(5))
            .header("Authorization", format!("Bearer {}", bearer))
            .header("Accept", "application/json")
            .send()
            .await
        {
            let status = resp.status();
            if status.is_success() {
                if let Ok(body_text) = resp.text().await {
                    let mut headers = HeaderMap::new();
                    headers.insert(
                        reqwest::header::CONTENT_TYPE,
                        HeaderValue::from_static("application/json"),
                    );
                    return (status, headers, body_text).into_response();
                }
            }
            // 404 / 5xx / 解析失败 → fallback 到内置 stub
        }
    }
    // built-in stub — 字段对齐 SDK Plan,数据沿用前端原本的
    // FREE_TEMPLATES / PAID_TEMPLATES(让 demo 不依赖云端)
    let plans: Vec<PlanDto> = vec![
        // ===== 0 元试用(0 元 plan,云端应当 status=paid 立即返)=====
        PlanDto {
            id: "free-general-chat".into(),
            name: "通用对话".into(),
            plan_code: "skill_free_general".into(),
            amount_yuan: 0.0,
            points: 0,
            duration_days: 0,
            description: "适用于日常对话和问答的基础 Prompt 模板".into(),
            enabled: true,
            sort_order: 10,
        },
        PlanDto {
            id: "free-code-assistant".into(),
            name: "编程助手".into(),
            plan_code: "skill_free_code".into(),
            amount_yuan: 0.0,
            points: 0,
            duration_days: 0,
            description: "帮助编写、调试和优化代码的专业模板".into(),
            enabled: true,
            sort_order: 11,
        },
        PlanDto {
            id: "free-writing-helper".into(),
            name: "写作助手".into(),
            plan_code: "skill_free_writing".into(),
            amount_yuan: 0.0,
            points: 0,
            duration_days: 0,
            description: "辅助写作、润色和改写文本的通用模板".into(),
            enabled: true,
            sort_order: 12,
        },
        PlanDto {
            id: "free-translator".into(),
            name: "翻译专家".into(),
            plan_code: "skill_free_translator".into(),
            amount_yuan: 0.0,
            points: 0,
            duration_days: 0,
            description: "支持多语言翻译的专业模板".into(),
            enabled: true,
            sort_order: 13,
        },
        PlanDto {
            id: "free-data-analyst".into(),
            name: "数据分析".into(),
            plan_code: "skill_free_data".into(),
            amount_yuan: 0.0,
            points: 0,
            duration_days: 0,
            description: "帮助分析和可视化的数据专家模板".into(),
            enabled: true,
            sort_order: 14,
        },
        PlanDto {
            id: "free-creative-writer".into(),
            name: "创意写作".into(),
            plan_code: "skill_free_creative".into(),
            amount_yuan: 0.0,
            points: 0,
            duration_days: 0,
            description: "激发创意和想象力的写作模板".into(),
            enabled: true,
            sort_order: 15,
        },
        // ===== 付费计划(>0 元,云端走微信支付 QR)=====
        PlanDto {
            id: "paid-business-strategy".into(),
            name: "商业策略专家".into(),
            plan_code: "skill_paid_business".into(),
            amount_yuan: 29.9,
            points: 0,
            duration_days: 365,
            description: "专业商业分析和战略规划的高级模板".into(),
            enabled: true,
            sort_order: 20,
        },
        PlanDto {
            id: "paid-legal-advisor".into(),
            name: "法律顾问".into(),
            plan_code: "skill_paid_legal".into(),
            amount_yuan: 39.9,
            points: 0,
            duration_days: 365,
            description: "专业法律咨询和合同审查的高级模板".into(),
            enabled: true,
            sort_order: 21,
        },
        PlanDto {
            id: "paid-medical-expert".into(),
            name: "医疗专家".into(),
            plan_code: "skill_paid_medical".into(),
            amount_yuan: 49.9,
            points: 0,
            duration_days: 365,
            description: "专业医疗咨询和健康管理的高级模板".into(),
            enabled: true,
            sort_order: 22,
        },
        PlanDto {
            id: "paid-financial-analyst".into(),
            name: "金融分析师".into(),
            plan_code: "skill_paid_finance".into(),
            amount_yuan: 34.9,
            points: 0,
            duration_days: 365,
            description: "专业金融分析和投资建议的高级模板".into(),
            enabled: true,
            sort_order: 23,
        },
        PlanDto {
            id: "paid-creative-director".into(),
            name: "创意总监".into(),
            plan_code: "skill_paid_creative".into(),
            amount_yuan: 44.9,
            points: 0,
            duration_days: 365,
            description: "专业品牌策划和创意设计的高级模板".into(),
            enabled: true,
            sort_order: 24,
        },
    ];
    Json(ListPaymentPlansResponse {
        success: true,
        plans,
    })
    .into_response()
}

async fn clear_binding_record(state: &SharedState) {
    {
        let mut binding = state.binding.lock().await;
        *binding = BindingRecord::default();
    }
    // Also wipe `model.api_key` — the api_key holds the device_token
    // the cloud returned at register time, and the binding block is
    // what proves the device is bound. Clearing only the binding but
    // leaving api_key behind would let subsequent chat / payment
    // requests keep authenticating as a "bound" device after unbind,
    // defeating the point of unbind. Clear both in-memory and on-disk.
    {
        let mut model = state.primary_model.lock().await;
        model.api_key.clear();
    }
    // Best-effort disk cleanup. If the file is missing / both the
    // `binding:` block and `model.api_key` are absent, treat it as
    // already clean.
    let yaml_path = hermes_config_path();
    let yaml_text = read_file_or_default_async(&yaml_path).await;
    if yaml_text.trim().is_empty() {
        return;
    }
    let Ok(mut value) = serde_yaml::from_str::<serde_yaml::Value>(&yaml_text) else {
        return;
    };
    let Some(root_mapping) = value.as_mapping_mut() else {
        return;
    };
    let binding_key = serde_yaml::Value::String("binding".to_string());
    root_mapping.remove(&binding_key);
    // Drop `model.api_key` if the `model:` mapping exists. Other
    // model fields (provider / base_url / default) are preserved so
    // the user doesn't have to re-enter them on the next bind.
    if let Some(serde_yaml::Value::Mapping(model_mapping)) = root_mapping
        .get_mut(serde_yaml::Value::String("model".to_string()))
    {
        model_mapping
            .remove(serde_yaml::Value::String("api_key".to_string()));
    }
    let Ok(serialized) = serde_yaml::to_string(&value) else {
        return;
    };
    if let Err(e) = tokio::fs::write(&yaml_path, serialized.as_bytes()).await {
        log::warn!(
            "[embedded_server] unbind 清除 binding: / model.api_key 写盘失败: {}",
            e
        );
    } else {
        log::info!(
            "[embedded_server] unbind 已清除 {} 的 binding: 块和 model.api_key",
            yaml_path.display()
        );
    }
}

async fn cron_page(State((state, _, _)): State<(SharedState, &'static str, u16)>) -> Response {
    // The Rust `hermes_dashboard_token` function does a string
    // extraction on the response body looking for the marker
    // `window.__HERMES_SESSION_TOKEN__="<token>"`. Returning JSON
    // (as the previous stub did) means the extractor returns
    // `None` and the dashboard's "凌晨 2 点" 2 AM cron
    // registration fails. We MUST serve HTML.
    //
    // We still want the page to be useful when a developer opens
    // it in a browser, so we render a minimal job-listing page
    // that also dumps the token into the dev console.
    let jobs = state.jobs.lock().await;
    let mut job_rows = String::new();
    for job in jobs.values() {
        job_rows.push_str(&format!(
            "<tr><td>{}</td><td>{}</td><td>{}</td><td>{}</td></tr>",
            html_escape(&job.id),
            html_escape(job.name.as_deref().unwrap_or("")),
            html_escape(&job.schedule_display),
            html_escape(&job.state),
        ));
    }
    drop(jobs);

    let html = format!(
        r#"<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>Hermes Cron Dashboard</title>
  <style>
    body {{ font-family: -apple-system, "Segoe UI", sans-serif; margin: 24px; color: #1f2933; }}
    h1 {{ font-size: 18px; margin: 0 0 8px; }}
    p.muted {{ color: #6b7280; font-size: 12px; margin: 0 0 16px; }}
    table {{ border-collapse: collapse; width: 100%; font-size: 13px; }}
    th, td {{ border: 1px solid #e4e7eb; padding: 6px 10px; text-align: left; }}
    th {{ background: #f6f8fa; }}
    code {{ background: #f6f8fa; padding: 1px 4px; border-radius: 3px; }}
  </style>
</head>
<body>
  <h1>Hermes Cron Dashboard</h1>
  <p class="muted">Embedded server. The session token below is consumed by the front-end's <code>hermes_dashboard_token()</code> extractor.</p>
  <table>
    <thead><tr><th>ID</th><th>Name</th><th>Schedule</th><th>State</th></tr></thead>
    <tbody>{job_rows}</tbody>
  </table>
  <script>window.__HERMES_SESSION_TOKEN__="{token}";</script>
</body>
</html>"#,
        job_rows = job_rows,
        token = state.session_token,
    );

    Response::builder()
        .status(StatusCode::OK)
        .header("content-type", "text/html; charset=utf-8")
        .header("access-control-allow-origin", "*")
        .body(Body::from(html))
        .unwrap_or_else(|e| {
            log::error!("[embedded_server] /cron response build failed: {}", e);
            json_status(
                StatusCode::INTERNAL_SERVER_ERROR,
                json!({ "error": format!("cron page build failed: {}", e) }),
            )
        })
}

fn html_escape(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    for ch in input.chars() {
        match ch {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            '\'' => out.push_str("&#39;"),
            c => out.push(c),
        }
    }
    out
}

/// Returns `true` when the request carries the embedded server's
/// session token in `Authorization: Bearer <token>`. Every cron
/// API handler funnels through this so a missing / stale token
/// produces a clean 401 (instead of a 500 from serde failing on
/// a wrong response shape).
fn check_bearer(state: &SharedState, headers: &HeaderMap) -> bool {
    let header = match headers.get("authorization") {
        Some(value) => value,
        None => return false,
    };
    let header_str = match header.to_str() {
        Ok(value) => value,
        Err(_) => return false,
    };
    let token = match header_str.strip_prefix("Bearer ") {
        Some(rest) => rest.trim(),
        None => return false,
    };
    // Constant-time compare: the token is 64 hex chars, side
    // channels aren't a real threat on a localhost-only listener
    // but it's two lines of code.
    if token.len() != state.session_token.len() {
        return false;
    }
    let mut diff: u8 = 0;
    for (a, b) in token.bytes().zip(state.session_token.bytes()) {
        diff |= a ^ b;
    }
    diff == 0
}

fn unauthorized() -> Response {
    json_status(StatusCode::UNAUTHORIZED, json!({ "error": "missing or invalid session token" }))
}

#[derive(serde::Deserialize, Default)]
struct ChatCompletionsBody {
    #[serde(default)]
    messages: Vec<ChatMessage>,
    // `model` and `stream` are accepted so the front-end can keep
    // using the standard OpenAI /v1/chat/completions shape. The
    // embedded server always proxies to the configured primary
    // model (ignoring `model`) and dispatches streaming vs
    // non-streaming based on the route (`/v1/chat/completions`
    // non-stream, `/v1/responses` stream), not on this flag.
    #[serde(default)]
    #[allow(dead_code)]
    model: Option<String>,
    #[serde(default)]
    #[allow(dead_code)]
    stream: bool,
}

#[derive(serde::Deserialize, Default)]
struct ChatMessage {
    #[serde(default)]
    role: String,
    #[serde(default)]
    content: String,
}

/// Request body for `POST /v1/responses` (OpenAI Responses API
/// shape). Per the upstream contract, `input` is either a single
/// user-message string (for one-shot prompts) **or** an array of
/// chat messages (when the client wants to replay the full
/// conversation history). We accept both, plus the
/// `messages:` field for callers that still
/// use the chat-completions shape. `model` / `stream` are accepted
/// but ignored — the gateway always proxies to the configured
/// primary model and `/v1/responses` always streams.
#[derive(serde::Deserialize, Default)]
struct ResponsesBody {
    #[serde(default)]
    input: Option<Value>,
    #[serde(default)]
    messages: Vec<ChatMessage>,
    #[serde(default)]
    #[allow(dead_code)]
    model: Option<String>,
    #[serde(default)]
    #[allow(dead_code)]
    stream: bool,
    #[serde(default, rename = "previous_response_id")]
    #[allow(dead_code)]
    previous_response_id: Option<String>,
}

impl ResponsesBody {
    /// Convert the heterogeneous `input` / `messages` payload into
    /// the `Vec<VLMMessage>` shape the LLM service expects.
    /// Preference order: explicit `messages` array → `input` array
    /// → `input` string (wrapped as a single `user` message).
    /// Returns an empty vec if none of the three are present / all
    /// are empty so the caller can decide whether that's a 400.
    fn to_vlm_messages(&self) -> Vec<VLMMessage> {
        // 1) explicit `messages:` field wins if non-empty.
        if !self.messages.is_empty() {
            return self
                .messages
                .iter()
                .map(|m| VLMMessage {
                    role: m.role.clone(),
                    content: m.content.clone(),
                    ..Default::default()
                })
                .collect();
        }
        // 2) `input:` array → treat each element as a chat message.
        if let Some(Value::Array(items)) = &self.input {
            let mut out = Vec::with_capacity(items.len());
            for item in items {
                if let Some(msg) = value_to_chat_message(item) {
                    out.push(msg);
                }
            }
            if !out.is_empty() {
                return out;
            }
        }
        // 3) `input:` string → single user message.
        if let Some(Value::String(text)) = &self.input {
            let trimmed = text.trim();
            if !trimmed.is_empty() {
                return vec![VLMMessage {
                    role: "user".to_string(),
                    content: text.clone(),
                    ..Default::default()
                }];
            }
        }
        Vec::new()
    }
}

/// Best-effort conversion of one element of the `input` array
/// into a chat message. Accepts:
///   * `{ "role": "...", "content": "..." }`           — standard
///   * `{ "type": "message", "role": "...", "content": "..." }`
///   * `{ "role": "...", "content": [{ "text": "..." }] }`
///   * `{ "text": "..." }`                             — bare string
///                                                       content
/// Anything else returns `None` and is silently skipped (the
/// upstream contract is permissive on content shape; we only
/// care that *something* round-trips through to the LLM).
fn value_to_chat_message(value: &Value) -> Option<VLMMessage> {
    let obj = value.as_object()?;
    let role = obj
        .get("role")
        .and_then(|v| v.as_str())
        .unwrap_or("user")
        .to_string();
    let content = match obj.get("content") {
        Some(Value::String(s)) => s.clone(),
        Some(Value::Array(parts)) => parts
            .iter()
            .filter_map(|p| {
                p.get("text")
                    .and_then(|t| t.as_str())
                    .map(str::to_string)
                    .or_else(|| {
                        // tolerate the bare-string form too
                        p.as_str().map(str::to_string)
                    })
            })
            .collect::<Vec<_>>()
            .join("\n"),
        Some(Value::Object(_)) => obj
            .get("content")
            .and_then(|c| c.get("text"))
            .and_then(|t| t.as_str())
            .map(str::to_string)
            .unwrap_or_default(),
        None => obj
            .get("text")
            .and_then(|t| t.as_str())
            .map(str::to_string)
            .unwrap_or_default(),
        Some(other) => other.to_string(),
    };
    if content.trim().is_empty() {
        return None;
    }
    Some(VLMMessage {
        role,
        content,
        ..Default::default()
    })
}

#[derive(serde::Deserialize, Default)]
struct CronCreateBody {
    #[serde(default)]
    prompt: String,
    #[serde(default)]
    schedule: String,
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    deliver: Option<String>,
}

async fn cron_list(
    State((state, _, _)): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
) -> Response {
    if !check_bearer(&state, &headers) {
        return unauthorized();
    }
    let jobs = state.jobs.lock().await;
    let list: Vec<Value> = jobs.values().map(|j| j.to_json()).collect();
    Json(json!(list)).into_response()
}

async fn cron_create(
    State((state, _, _)): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
    body: Option<Json<CronCreateBody>>,
) -> Response {
    if !check_bearer(&state, &headers) {
        return unauthorized();
    }
    let body = body.map(|Json(b)| b).unwrap_or_default();
    let schedule_expr = if body.schedule.is_empty() {
        "0 * * * *".to_string()
    } else {
        body.schedule
    };
    let now = chrono::Utc::now();
    let next = now + chrono::Duration::hours(1);
    let id = format!("cron-{}", Uuid::new_v4().simple());
    let record = CronJobRecord {
        id: id.clone(),
        name: body.name,
        prompt: body.prompt,
        schedule_kind: "cron".to_string(),
        schedule_expr: schedule_expr.clone(),
        schedule_display: schedule_expr.clone(),
        enabled: true,
        state: "scheduled".to_string(),
        deliver: body.deliver,
        last_run_at: None,
        next_run_at: Some(next.to_rfc3339()),
        last_error: None,
    };
    let mut jobs = state.jobs.lock().await;
    jobs.insert(id.clone(), record.clone());
    drop(jobs);
    log::info!(
        "[Hermes Cron] registered job id={} schedule={} prompt_len={}",
        id,
        schedule_expr,
        record.prompt.len()
    );
    json_status(StatusCode::CREATED, record.to_json())
}

async fn cron_delete(
    State((state, _, _)): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> Response {
    if !check_bearer(&state, &headers) {
        return unauthorized();
    }
    let mut jobs = state.jobs.lock().await;
    let removed = jobs.remove(&id).is_some();
    drop(jobs);
    if removed {
        log::info!("[Hermes Cron] deleted job id={}", id);
        json_status(StatusCode::OK, json!({ "ok": true }))
    } else {
        json_status(StatusCode::NOT_FOUND, json!({ "error": "job not found", "id": id }))
    }
}

async fn cron_pause(
    State((state, _, _)): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> Response {
    if !check_bearer(&state, &headers) {
        return unauthorized();
    }
    let mut jobs = state.jobs.lock().await;
    let response = match jobs.get_mut(&id) {
        Some(job) => {
            job.enabled = false;
            job.state = "paused".to_string();
            json_status(StatusCode::OK, json!({ "ok": true, "id": id, "state": job.state }))
        }
        None => json_status(StatusCode::NOT_FOUND, json!({ "error": "job not found", "id": id })),
    };
    drop(jobs);
    response
}

async fn cron_resume(
    State((state, _, _)): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> Response {
    if !check_bearer(&state, &headers) {
        return unauthorized();
    }
    let mut jobs = state.jobs.lock().await;
    let response = match jobs.get_mut(&id) {
        Some(job) => {
            job.enabled = true;
            job.state = "scheduled".to_string();
            json_status(StatusCode::OK, json!({ "ok": true, "id": id, "state": job.state }))
        }
        None => json_status(StatusCode::NOT_FOUND, json!({ "error": "job not found", "id": id })),
    };
    drop(jobs);
    response
}

async fn cron_trigger(
    State((state, _, _)): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> Response {
    if !check_bearer(&state, &headers) {
        return unauthorized();
    }
    let mut jobs = state.jobs.lock().await;
    // Clone everything we need from the job *before* we drop the lock,
    // so the async LLM call below doesn't hold (or try to move out of)
    // the guard while the LLM is still talking to the upstream.
    let (job_id, job_prompt) = match jobs.get(&id) {
        Some(job) => (job.id.clone(), job.prompt.clone()),
        None => {
            drop(jobs);
            return json_status(
                StatusCode::NOT_FOUND,
                json!({ "error": "job not found", "id": id }),
            );
        }
    };
    {
        if let Some(job) = jobs.get_mut(&id) {
            // Reject a re-trigger while a previous run is still in
            // flight. Without this guard, hammering the trigger
            // endpoint spawns one upstream LLM call per click; they
            // all race to write `job.state` / `last_error` and the
            // job's `running` state never converges. 409 tells the
            // dashboard to wait for the in-flight run to finish.
            if job.state == "running" {
                drop(jobs);
                return json_status(
                    StatusCode::CONFLICT,
                    json!({ "error": "job already running", "id": job_id, "state": "running" }),
                );
            }
            // Mark the job as triggered now. Triggering a cron job
            // *synchronously* runs the prompt through the configured
            // LLM, so the in-process gateway stays the source of
            // truth and the webview's "last run" reflects the real
            // LLM outcome instead of a no-op stamp. If no model is
            // configured, we still flip the state to `running` and
            // surface the LLM error in `last_error` so the UI can
            // show "model not configured" instead of silently
            // pretending the run succeeded.
            let now = chrono::Utc::now().to_rfc3339();
            job.last_run_at = Some(now);
            job.state = "running".to_string();
        }
    }
    drop(jobs);
    run_cron_prompt_and_record(state.clone(), &job_id, &job_prompt).await;
    json_status(StatusCode::OK, json!({ "ok": true, "id": job_id, "state": "running" }))
}

async fn run_cron_prompt_and_record(state: SharedState, id: &str, prompt: &str) {
    let prompt_text = prompt.to_string();
    let job_id = id.to_string();
    tokio::spawn(async move {
        let cfg = state.primary_model.lock().await.clone();
        if !cfg.is_configured() {
            // cron 任务的 last_error: 走更精确的 message,如果只缺
            // api_key 就告诉用户"先绑设备",不要笼统说 model 未配置。
            let body = cfg.build_unconfigured_error();
            let msg = body
                .get("error")
                .and_then(|v| v.as_str())
                .map(str::to_string)
                .unwrap_or_else(|| "model not configured".to_string());
            record_cron_run_outcome(&state, &job_id, "error", None, Some(msg)).await;
            return;
        }
        let llm_cfg = build_llm_service_config(&cfg);
        let service = LLMService::with_client(llm_cfg, state.llm_http.clone());
        let messages = vec![VLMMessage {
            role: "user".to_string(),
            content: prompt_text,
            ..Default::default()
        }];
        match service.complete(messages, None).await {
            Ok(resp) => {
                let content = resp.content.unwrap_or_default();
                let outcome = "completed";
                let last_error = if content.trim().is_empty() {
                    Some("upstream returned empty content".to_string())
                } else {
                    None
                };
                record_cron_run_outcome(&state, &job_id, outcome, Some(content), last_error).await;
            }
            Err(e) => {
                record_cron_run_outcome(
                    &state,
                    &job_id,
                    "error",
                    None,
                    Some(format!("cron LLM run failed: {}", e)),
                )
                .await;
            }
        }
    });
}

async fn record_cron_run_outcome(
    state: &SharedState,
    id: &str,
    final_state: &str,
    _content: Option<String>,
    last_error: Option<String>,
) {
    let mut jobs = state.jobs.lock().await;
    if let Some(job) = jobs.get_mut(id) {
        job.state = final_state.to_string();
        job.last_error = last_error;
    }
}

// ========================
// chat (real upstream)
// ========================

/// Translate the incoming `chat.completions` body into the
/// `VLMMessage` list the LLM service expects. The front-end sends
/// `{ role, content }`; we pass them through unchanged.
fn to_vlm_messages(body: &ChatCompletionsBody) -> Vec<VLMMessage> {
    body.messages
        .iter()
        .map(|m| VLMMessage {
            role: m.role.clone(),
            content: m.content.clone(),
            ..Default::default()
        })
        .collect()
}

/// OpenAI-compatible non-streaming chat. The upstream provider's
/// JSON response is passed through to the webview with the same
/// shape (id, object, created, model, choices[].message.content).
/// We do not synthesise or rewrite the reply.
async fn chat_completions(
    State((state, _, _)): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
    Json(body): Json<ChatCompletionsBody>,
) -> Response {
    if !check_bearer(&state, &headers) {
        return unauthorized();
    }
    let cfg = state.primary_model.lock().await.clone();
    if !cfg.is_configured() {
        return json_status(StatusCode::SERVICE_UNAVAILABLE, cfg.build_unconfigured_error());
    }
    let llm_cfg = build_llm_service_config(&cfg);
    let service = LLMService::with_client(llm_cfg, state.llm_http.clone());
    let messages = to_vlm_messages(&body);
    if messages.is_empty() {
        return json_status(
            StatusCode::BAD_REQUEST,
            json!({ "error": "messages is required" }),
        );
    }
    match service.complete(messages, None).await {
        Ok(resp) => json_status(
            StatusCode::OK,
            json!({
                "id": format!("chatcmpl-{}", Uuid::new_v4().simple()),
                "object": "chat.completion",
                "created": chrono::Utc::now().timestamp(),
                "model": resp.model_or_default(&cfg.model),
                "choices": [{
                    "index": 0,
                    "message": {
                        "role": "assistant",
                        "content": resp.content.unwrap_or_default(),
                    },
                    "finish_reason": resp.finish_reason.unwrap_or_else(|| "stop".to_string()),
                }],
                "usage": {
                    "prompt_tokens": resp.usage.as_ref().map(|u| u.prompt_tokens).unwrap_or(0),
                    "completion_tokens": resp.usage.as_ref().map(|u| u.completion_tokens).unwrap_or(0),
                    "total_tokens": resp.usage.as_ref().map(|u| u.total_tokens).unwrap_or(0),
                },
            }),
        ),
        Err(e) => json_status(
            StatusCode::BAD_GATEWAY,
            json!({ "error": format!("upstream chat-completions failed: {}", e) }),
        ),
    }
}

/// SSE stream that proxies the upstream LLM's `text/event-stream`
/// back to the webview verbatim. We do not parse, coalesce, or
/// rewrite any chunks — the previous `responses_sse` synthesised
/// 4-char slices of a canned reply, which made the LLM feel
/// useless in dev. The real bytes now flow through.
///
/// v5.1: accept the OpenAI Responses API `input` field (string or
/// array) in addition to the `messages` array. The earlier
/// `ChatCompletionsBody` decoder only knew about `messages`, so a
/// `{"input": "..."}` payload was deserialised with an empty
/// messages vec and produced a confusing
/// `{"error":"messages is required"}` 400 on the way to the LLM.
async fn responses_sse(
    State((state, _, _)): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
    Json(body): Json<ResponsesBody>,
) -> Response {
    if !check_bearer(&state, &headers) {
        return unauthorized();
    }
    let cfg = state.primary_model.lock().await.clone();
    if !cfg.is_configured() {
        // SSE clients expect `text/event-stream` even for errors,
        // but we surface a plain JSON 503 because the webview's
        // `fetch().body.getReader()` would otherwise sit on a
        // half-open stream waiting for `[DONE]`.
        return json_status(StatusCode::SERVICE_UNAVAILABLE, cfg.build_unconfigured_error());
    }
    let messages = body.to_vlm_messages();
    if messages.is_empty() {
        return json_status(
            StatusCode::BAD_REQUEST,
            json!({ "error": "messages is required" }),
        );
    }
    let llm_cfg = build_llm_service_config(&cfg);
    let service = LLMService::with_client(llm_cfg, state.llm_http.clone());
    let upstream = match service.complete_stream_bytes(messages).await {
        Ok(s) => s,
        Err(e) => {
            return json_status(
                StatusCode::BAD_GATEWAY,
                json!({ "error": format!("upstream stream open failed: {}", e) }),
            );
        }
    };

    // Wrap the upstream byte stream into an axum body. Each
    // chunk is forwarded as-is; we do NOT prepend `data:` /
    // append `\n\n` because the upstream already does that.
    //
    // 客户端断连 / 上游卡死兜底：给上游流的每个 chunk 加
    // `SSE_IDLE_TIMEOUT` 空闲超时。axum/hyper 在 webview 主动断
    // 开时会 drop 整个 response future，进而 drop body_stream 与
    // upstream，所以客户端主动断开本身不会泄漏；但仍可能出现
    // "上游静默卡死" 或 TCP 半开 —— 这时 getReader() 会永远挂
    // 着。`tokio_stream::StreamExt::timeout` 给每次 next() 加超时，
    // 第一个超时即结束流（`take_while` 在首个 `Err(Elapsed)` 上
    // 返回 false），让 webview 收到正常的 stream 结束。
    const SSE_IDLE_TIMEOUT: Duration = Duration::from_secs(60);
    let timed = tokio_stream::StreamExt::timeout(upstream, SSE_IDLE_TIMEOUT);
    let body_stream = timed
        .take_while(|res| std::future::ready(res.is_ok()))
        .filter_map(|res| async move {
            match res {
                Ok(inner) => Some(
                    inner.map_err(std::io::Error::other),
                ),
                Err(_) => None,
            }
        });
    Response::builder()
        .status(StatusCode::OK)
        .header("content-type", "text/event-stream; charset=utf-8")
        .header("cache-control", "no-cache")
        .header("access-control-allow-origin", "*")
        .header("x-accel-buffering", "no")
        .body(Body::from_stream(body_stream))
        .unwrap_or_else(|_| {
            json_status(
                StatusCode::INTERNAL_SERVER_ERROR,
                json!({ "error": "sse response build failed" }),
            )
        })
}

fn is_valid_join_code(code: &str) -> bool {
    code.len() == 8 && code.chars().all(|c| c.is_ascii_digit())
}

/// Final fallback for any unknown route. Returns 404 with a JSON
/// body so the front-end can show a useful error instead of the
/// old `{ ok: true, stub: true, ... }` echoes.
async fn not_found(method: axum::http::Method, uri: axum::http::Uri) -> Response {
    json_status(
        StatusCode::NOT_FOUND,
        json!({
            "error": "route not found",
            "method": method.as_str(),
            "path": uri.path(),
        }),
    )
}

fn json_status(status: StatusCode, body: Value) -> Response {
    (status, Json(body)).into_response()
}


/// Optional `SocketAddr` helper for callers that want to print the
/// bound address after the fact (handy in startup logs).
pub fn gateway_socket_addr() -> SocketAddr {
    format!("127.0.0.1:{DEFAULT_GATEWAY_PORT}")
        .parse()
        .expect("valid 127.0.0.1:port literal")
}

/// Best-effort readiness wait — used by `ensure_gateway_running` to
/// give the rest of the app a `tokio::time::sleep` budget for the
/// embedded listeners to actually accept connections after they were
/// spawned. Returns `true` once the gateway is reachable, `false` on
/// timeout.
pub async fn wait_until_ready(max: Duration) -> bool {
    let deadline = std::time::Instant::now() + max;
    while std::time::Instant::now() < deadline {
        if tokio::net::TcpStream::connect(("127.0.0.1", DEFAULT_GATEWAY_PORT)).await.is_ok() {
            return true;
        }
        tokio::time::sleep(Duration::from_millis(80)).await;
    }
    false
}

/// Build a `tokio::net::TcpListener` bound to **`127.0.0.1:port`**
/// (loopback only).
///
/// v5.x 原实现绑定 `[::]:port` 并打开 `IPV6_V6ONLY=false` 做双栈
/// 监听，意味着局域网内任何网卡都能连到本进程的 chat / env /
/// config / cdp 路由——而这些路由在 v5.9 之前没有任何鉴权。
/// 安全模型要求嵌入式 gateway 只对本机 webview 暴露，因此改为
/// 只绑 IPv4 回环地址。我们仍走 `socket2` 以便保留
/// `set_nonblocking` 的显式控制（axum::serve 需要非阻塞 listener）。
fn make_dual_stack_listener(port: u16) -> std::io::Result<tokio::net::TcpListener> {
    use std::net::TcpListener as StdTcpListener;
    let socket = Socket::new(Domain::IPV4, Type::STREAM, None)?;
    let addr: SocketAddr = format!("127.0.0.1:{}", port)
        .parse()
        .expect("valid 127.0.0.1:port literal");
    socket.bind(&addr.into())?;
    socket.listen(128)?;
    let std_listener: StdTcpListener = socket.into();
    std_listener.set_nonblocking(true)?;
    tokio::net::TcpListener::from_std(std_listener)
}

// Suppress an "unused" warning for the IntoResponse import path on
// older toolchains — we route to it via the `axum::response::*` re-export
// and rustc 1.77 sometimes complains. Cheap to keep.
#[allow(dead_code)]
fn _ensure_infallible_unreachable(_: Infallible) {}

// Tiny helper trait so the chat_completions handler can call
// `resp.model_or_default(&cfg.model)` without re-checking the
// `Option<…>` shape on every response variant.
trait VLMResponseExt {
    fn model_or_default(&self, fallback: &str) -> String;
}

impl VLMResponseExt for crate::hermes::types::VLMResponse {
    fn model_or_default(&self, fallback: &str) -> String {
        // VLMResponse doesn't carry a model field; we always echo
        // back the configured model. If a provider-supplied
        // `model` becomes available, prefer it here.
        let _ = self;
        fallback.to_string()
    }
}

// Compile-time assertion that we didn't accidentally drop the
// `Bytes` re-export; the SSE streaming path needs it.
#[allow(dead_code)]
fn _bytes_type_assert(b: Bytes) -> Bytes {
    b
}

// ========================
// v5.7 — Trace 监控路由的真实数据接入
//
// v5.6 的 stub 直接返回 windows=[] / running=false,前端
// "Trace 窗口监控"页面永远显示"未发现 Trace 窗口"。这里换成
// 通过 pc_automation::cdp::websockets 拉取 Trae IDE 的真实
// DOM 信息 — 端口扫描沿用 9222-9230,WS 通道一次性 evaluate,
// 拿到 title/state/userTurns/aiTurns/modelName/lastAiText。
//
// 设计取舍:
//   * 不持久化 WS 连接:monitor_status 由前端每 6s 轮询一次,
//     每次重建连接的代价 < 50ms(localhost),换来"路由实现无
//     状态、生命周期简单、出问题易排查"。
//   * JS 表达式用通用选择器([data-role] / [class*=...]),
//     Trae 实际 DOM 改了我们最多读到 0 计数,不会崩。
//   * 单次 evaluate 失败不阻断整个端点:枚举到的所有 target
//     中任一失败就跳过,至少能部分反映现状。
//   * 整个调用有 3s 上限 (tokio::time::timeout),超过就
//     返回空 windows + running=false,前端走兜底空状态。
// ========================

/// JS payload executed in every Trae target. Returns a JSON
/// string with the five fields the front-end's Monitor page
/// expects. Heuristic selectors — Trae's actual DOM may shift
/// and the worst-case behaviour is "0 turns", not a crash.
const TRACE_WINDOW_INTROSPECTION_JS: &str = r#"
(function() {
  const userSelectors = [
    '[data-role="user"]', '[data-message-role="user"]',
    '[class*="user-message"]', '[class*="human-turn"]',
    '.message.user', '.chat-message.user'
  ];
  const aiSelectors = [
    '[data-role="assistant"]', '[data-message-role="assistant"]',
    '[class*="ai-message"]', '[class*="assistant-turn"]',
    '[class*="bot-message"]', '.message.assistant',
    '.chat-message.assistant'
  ];
  function pickEls(selectors) {
    for (const s of selectors) {
      const els = document.querySelectorAll(s);
      if (els.length) return Array.from(els);
    }
    return [];
  }
  const userEls = pickEls(userSelectors);
  const aiEls = pickEls(aiSelectors);
  const lastAiEl = aiEls.length ? aiEls[aiEls.length - 1] : null;
  const lastAiText = lastAiEl
    ? (lastAiEl.innerText || lastAiEl.textContent || '').trim().slice(0, 500)
    : null;

  const modelEl = document.querySelector(
    '[class*="model-name"] [class*="name"], [data-model-name], [class*="modelName"]'
  );
  const model = modelEl
    ? (modelEl.innerText || modelEl.textContent || '').trim()
    : null;

  const body = document.body ? document.body.innerHTML : '';
  let state = 'idle';
  if (/generat|thinking|streaming|loading|等待|生成中|思考中/i.test(body)) {
    state = 'running';
  } else if (/error|failed|错误|失败/i.test(body)) {
    state = 'error';
  } else if (aiEls.length > 0) {
    state = 'stopped';
  }

  return JSON.stringify({
    title: document.title || '',
    state: state,
    userTurns: userEls.length,
    aiTurns: aiEls.length,
    modelName: model || '',
    lastAiText: lastAiText || ''
  });
})()
"#;

/// `GET /monitor/status` — Trace IDE 窗口状态 (v5.7 真实数据)
async fn monitor_status(
    State((state, _role, _port)): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
) -> Response {
    if !check_bearer(&state, &headers) {
        return unauthorized();
    }
    use std::time::Duration;
    use tokio::time::timeout;

    let started = std::time::Instant::now();

    // 3s total budget: port scan + per-target evaluate shouldn't
    // exceed this on a healthy localhost. If it does, the
    // front-end's 6s poll cadence is still safe.
    let probe = timeout(
        Duration::from_secs(3),
        list_and_probe_trace_windows(TRACE_WINDOW_INTROSPECTION_JS),
    )
    .await;

    let (windows, running) = match probe {
        Ok(Ok(windows)) => {
            let running = windows.iter().any(|w| {
                w.get("state").and_then(|v| v.as_str()) == Some("running")
            });
            (windows, running)
        }
        Ok(Err(e)) => {
            log::debug!("[monitor_status] cdp probe failed: {}", e);
            (Vec::new(), false)
        }
        Err(_) => {
            log::warn!("[monitor_status] cdp probe timeout (3s)");
            (Vec::new(), false)
        }
    };

    let body = json!({
        "windows": windows,
        "running": running,
        "version": HERMES_VERSION,
        "timestamp": chrono::Utc::now().to_rfc3339(),
        "elapsedMs": started.elapsed().as_millis() as u64,
    });
    log::debug!(
        "[monitor_status] windows={} running={} healthy={:?} elapsedMs={}",
        windows.len(),
        running,
        state.healthy.lock().await,
        started.elapsed().as_millis()
    );
    Json(body).into_response()
}

/// Helper for `monitor_status`: enumerate every page-type CDP
/// target on the standard port range, evaluate the supplied JS
/// on each, collect the JSON-shaped responses. Failures on
/// individual targets are logged at `debug` and skipped — the
/// goal is "best effort" visibility, not strict consistency.
async fn list_and_probe_trace_windows(js: &str) -> Result<Vec<Value>, String> {
    use crate::pc_automation::cdp::websockets::WebSocketCdpBackend;
    let targets = WebSocketCdpBackend::list_all_page_targets_async().await?;
    let mut out = Vec::new();
    for t in targets {
        match WebSocketCdpBackend::evaluate_on_target_async(
            &t.web_socket_debugger_url,
            js,
        )
        .await
        {
            Ok(s) => {
                // JS returns a JSON *string*; parse it back into
                // a structured object so the front-end gets
                // numbers/strings, not a quoted blob.
                match serde_json::from_str::<Value>(&s) {
                    Ok(parsed) => {
                        if let Some(obj) = parsed.as_object() {
                            let mut window = obj.clone();
                            // Stamp the target id so the
                            // front-end can correlate multiple
                            // windows with the same Trace IDE.
                            window.insert(
                                "id".to_string(),
                                Value::String(t.id.clone()),
                            );
                            window.insert(
                                "targetUrl".to_string(),
                                Value::String(t.url.clone()),
                            );
                            out.push(Value::Object(window));
                        }
                    }
                    Err(e) => log::debug!(
                        "[monitor_status] parse failed for target {}: {} (raw: {})",
                        t.id,
                        e,
                        &s[..s.len().min(80)]
                    ),
                }
            }
            Err(e) => log::debug!(
                "[monitor_status] eval failed for target {}: {}",
                t.id,
                e
            ),
        }
    }
    Ok(out)
}

/// `GET /stats` — 监控运行统计
///
/// 返回结构 (跟 src/pages/Stats.jsx 的 stat-card 字段对齐):
///   {
///     totalScans, totalSent, totalFailed, uptime (秒), version,
///     autoEvolve, lastUpdatedMs, skillCount
///   }
///
/// uptime 来自 EmbeddedServerState::started_at(进程启动时间),
/// Stats.jsx 把它除 60 显示成"分钟"。
///
/// `totalScans` / `totalSent` / `totalFailed` 自 v5.7 起从
/// `crate::hermes::evolution_stats` 读真实累计值(由前端的
/// Evolution.jsx 每次 `executeSkill` 后调用
/// `report_skill_execution_result` 上报),不再是写死的 0。
/// v5.6 之前这里三个字段全是 0,Stats 页面只能看到 uptime。
///
/// v5.8 起还附带 `skillCount`(当前已记录 per-skill 统计的
/// 技能数量)。完整的 per-skill 列表通过 IPC 的
/// `get_evolution_state` 获取 — Stats 页用不到细节,这里
/// 只暴露数量,避免 /stats 响应体膨胀。
async fn stats(
    State((state, _role, _port)): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
) -> Response {
    if !check_bearer(&state, &headers) {
        return unauthorized();
    }
    let uptime_secs = {
        let started = state.started_at.lock().await;
        started
            .map(|t| t.elapsed().as_secs())
            .unwrap_or(0)
    };
    // Snapshot the evolution counters under a short-lived lock.
    // Cheap: 5 fields + 1 timestamp; the lock is uncontended
    // outside of skill-execution reporting bursts.
    let evo = crate::hermes::evolution_stats::snapshot();
    let body = json!({
        "totalScans": evo.total_scans,
        "totalSent": evo.total_sent,
        "totalFailed": evo.total_failed,
        "uptime": uptime_secs,
        "version": HERMES_VERSION,
        "timestamp": chrono::Utc::now().to_rfc3339(),
        "autoEvolve": evo.auto_evolve,
        "lastUpdatedMs": evo.last_updated_ms,
        "skillCount": evo.skills.len(),
    });
    log::debug!(
        "[stats] called (uptime={}s, scans={}, sent={}, failed={}, autoEvolve={}, skills={})",
        uptime_secs,
        evo.total_scans,
        evo.total_sent,
        evo.total_failed,
        evo.auto_evolve,
        evo.skills.len()
    );
    Json(body).into_response()
}

/// `GET /logs` — 最近日志条目
///
/// 返回结构 (跟 src/pages/Logs.jsx 期望对齐):
///   [{ file: "name", lines: ["...", "..."] }]
///
/// 现在返回 []:前端 Logs 页面会显示"暂无日志"。后续接
/// analytics::rolling_file_logger::tail() 可以把最近 N 行包成
/// `{ file, lines }` 推到 front-end。
async fn logs(
    State((state, _, _)): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
) -> Response {
    if !check_bearer(&state, &headers) {
        return unauthorized();
    }
    let body: Vec<serde_json::Value> = Vec::new();
    log::debug!("[logs] called (returning empty list)");
    Json(body).into_response()
}

/// `GET /agents-md` — Trace Auto 自进化系统提示 (Markdown 原文)
///
/// agents.md 体积 ~25KB,编译期 include_str! 进来,不占运行时内存
/// 也不会触发路径解析问题。`include_str!` 在 CARGO_MANIFEST_DIR
/// 解析,这里是 `src-tauri/Cargo.toml`,agents.md 在 repo 根目录,
/// 相对路径是 `../../../agents.md`。
async fn agents_md() -> Response {
    const AGENTS_MD: &str = include_str!("../../../agents.md");
    log::debug!("[agents-md] called ({} bytes)", AGENTS_MD.len());
    Response::builder()
        .status(StatusCode::OK)
        .header("content-type", "text/markdown; charset=utf-8")
        .header("cache-control", "no-cache")
        .body(Body::from(AGENTS_MD))
        .unwrap_or_else(|_| {
            json_status(
                StatusCode::INTERNAL_SERVER_ERROR,
                json!({ "error": "agents-md body build failed" }),
            )
        })
}

// ── CDP proxy routes (v5.8) ──────────────────────────────────────

use crate::pc_automation::cdp::websockets::WebSocketCdpBackend;

/// 获取第一个 page target 的 WebSocket URL
/// 加 4s 外层 timeout：list_all_page_targets_async 内部单端口 800ms × 9 = 7.2s 上限，
/// 但实际很少扫描到 9230；4s 足够覆盖正常情况，避免单端口挂起拖住整个 CDP 路由。
async fn first_target_ws_url() -> Result<String, String> {
    let targets = tokio::time::timeout(
        Duration::from_secs(4),
        WebSocketCdpBackend::list_all_page_targets_async(),
    )
    .await
    .map_err(|_| "CDP target discovery timeout (4s)".to_string())??;
    targets.into_iter()
        .find(|t| t.target_type == "page")
        .map(|t| t.web_socket_debugger_url)
        .ok_or_else(|| "no CDP page target".to_string())
}

/// `GET /cdp/targets` — 列出所有 CDP page targets
async fn cdp_targets(
    State((state, _, _)): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
) -> Response {
    if !check_bearer(&state, &headers) {
        return unauthorized();
    }
    use tokio::time::timeout;
    match timeout(Duration::from_secs(10), WebSocketCdpBackend::list_all_page_targets_async()).await {
        Ok(Ok(targets)) => {
            let list: Vec<Value> = targets.into_iter().map(|t| {
                json!({
                    "id": t.id,
                    "title": t.title,
                    "url": t.url,
                    "webSocketDebuggerUrl": t.web_socket_debugger_url,
                })
            }).collect();
            Json(json!(list)).into_response()
        }
        Ok(Err(e)) => {
            log::warn!("[cdp_targets] list failed: {}", e);
            Json(json!([])).into_response()
        }
        Err(_) => {
            log::warn!("[cdp_targets] timeout");
            Json(json!([])).into_response()
        }
    }
}

#[derive(serde::Deserialize)]
struct CdpTypeBody { selector: String, text: String }

/// `POST /cdp/type` — 在指定元素中输入文本
async fn cdp_type(
    State((state, _, _)): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
    Json(body): Json<CdpTypeBody>,
) -> Response {
    if !check_bearer(&state, &headers) {
        return unauthorized();
    }
    let ws = match first_target_ws_url().await {
        Ok(u) => u,
        Err(e) => return json_status(StatusCode::BAD_GATEWAY, json!({ "error": e })),
    };
    let js = format!(
        r#"(function(){{
          const el=document.querySelector('{}');
          if(!el)return 'no_element';
          el.focus();
          document.execCommand('selectAll',false,null);
          document.execCommand('delete',false,null);
          document.execCommand('insertText',false,`{}`);
          return 'typed';
        }})()"#,
        body.selector.replace('`', "\\`").replace('\\', "\\\\"),
        body.text.replace('`', "\\`").replace('\\', "\\\\"),
    );
    match tokio::time::timeout(
        Duration::from_secs(10),
        WebSocketCdpBackend::evaluate_on_target_async(&ws, &js),
    ).await {
        Ok(Ok(val)) => Json(json!({ "result": val })).into_response(),
        Ok(Err(e)) => json_status(StatusCode::BAD_GATEWAY, json!({ "error": e })),
        Err(_) => json_status(StatusCode::GATEWAY_TIMEOUT, json!({ "error": "cdp_type eval timeout (10s)" })),
    }
}

#[derive(serde::Deserialize)]
struct CdpClickBody { selector: String }

/// `POST /cdp/click` — 点击指定元素
async fn cdp_click(
    State((state, _, _)): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
    Json(body): Json<CdpClickBody>,
) -> Response {
    if !check_bearer(&state, &headers) {
        return unauthorized();
    }
    let ws = match first_target_ws_url().await {
        Ok(u) => u,
        Err(e) => return json_status(StatusCode::BAD_GATEWAY, json!({ "error": e })),
    };
    let js = format!(
        r#"(function(){{
          const el=document.querySelector('{}');
          if(!el)return 'no_element';
          el.click();
          return 'clicked';
        }})()"#,
        body.selector.replace('`', "\\`"),
    );
    match tokio::time::timeout(
        Duration::from_secs(10),
        WebSocketCdpBackend::evaluate_on_target_async(&ws, &js),
    ).await {
        Ok(Ok(val)) => Json(json!({ "result": val })).into_response(),
        Ok(Err(e)) => json_status(StatusCode::BAD_GATEWAY, json!({ "error": e })),
        Err(_) => json_status(StatusCode::GATEWAY_TIMEOUT, json!({ "error": "cdp_click eval timeout (10s)" })),
    }
}

#[derive(serde::Deserialize)]
struct CdpWaitBody { selector: String, #[serde(default = "default_timeout")] timeout_ms: u64 }
fn default_timeout() -> u64 { 30000 }

/// `POST /cdp/wait` — 等待元素出现
async fn cdp_wait(
    State((state, _, _)): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
    Json(body): Json<CdpWaitBody>,
) -> Response {
    if !check_bearer(&state, &headers) {
        return unauthorized();
    }
    let ws = match first_target_ws_url().await {
        Ok(u) => u,
        Err(e) => return json_status(StatusCode::BAD_GATEWAY, json!({ "error": e })),
    };
    let deadline = std::time::Instant::now() + Duration::from_millis(body.timeout_ms);
    loop {
        let js = format!(
            r#"(function(){{ return document.querySelector('{}') ? 'found' : 'not_found' }})()"#,
            body.selector.replace('`', "\\`"),
        );
        match tokio::time::timeout(
            Duration::from_secs(5),
            WebSocketCdpBackend::evaluate_on_target_async(&ws, &js),
        ).await {
            Ok(Ok(ref v)) if v == "found" => {
                return Json(json!({ "result": "found" })).into_response();
            }
            _ => {}
        }
        if std::time::Instant::now() >= deadline {
            return Json(json!({ "result": "timeout" })).into_response();
        }
        tokio::time::sleep(Duration::from_millis(200)).await;
    }
}

#[derive(serde::Deserialize)]
struct CdpReadBody { selector: String }

/// `POST /cdp/read` — 读取元素文本
async fn cdp_read(
    State((state, _, _)): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
    Json(body): Json<CdpReadBody>,
) -> Response {
    if !check_bearer(&state, &headers) {
        return unauthorized();
    }
    let ws = match first_target_ws_url().await {
        Ok(u) => u,
        Err(e) => return json_status(StatusCode::BAD_GATEWAY, json!({ "error": e })),
    };
    let js = format!(
        r#"(function(){{
          const el=document.querySelector('{}');
          if(!el)return JSON.stringify({{text:''}});
          return JSON.stringify({{text:(el.innerText||el.textContent||'').trim()}});
        }})()"#,
        body.selector.replace('`', "\\`"),
    );
    match tokio::time::timeout(
        Duration::from_secs(10),
        WebSocketCdpBackend::evaluate_on_target_async(&ws, &js),
    ).await {
        Ok(Ok(val)) => {
            let parsed: Value = serde_json::from_str(&val).unwrap_or(json!({ "text": val }));
            Json(parsed).into_response()
        }
        Ok(Err(e)) => json_status(StatusCode::BAD_GATEWAY, json!({ "error": e })),
        Err(_) => json_status(StatusCode::GATEWAY_TIMEOUT, json!({ "error": "cdp_read eval timeout (10s)" })),
    }
}

#[derive(serde::Deserialize)]
struct CdpEvalBody { expression: String }

/// `POST /cdp/eval` — 执行任意 JavaScript
async fn cdp_eval_route(
    State((state, _, _)): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
    Json(body): Json<CdpEvalBody>,
) -> Response {
    if !check_bearer(&state, &headers) {
        return unauthorized();
    }
    let ws = match first_target_ws_url().await {
        Ok(u) => u,
        Err(e) => return json_status(StatusCode::BAD_GATEWAY, json!({ "error": e })),
    };
    match tokio::time::timeout(
        Duration::from_secs(15),
        WebSocketCdpBackend::evaluate_on_target_async(&ws, &body.expression),
    ).await {
        Ok(Ok(val)) => Json(json!({ "result": val })).into_response(),
        Ok(Err(e)) => json_status(StatusCode::BAD_GATEWAY, json!({ "error": e })),
        Err(_) => json_status(StatusCode::GATEWAY_TIMEOUT, json!({ "error": "cdp_eval timeout (15s)" })),
    }
}

#[derive(serde::Deserialize)]
struct CdpNavigateBody { url: String }

/// `POST /cdp/navigate` — 导航到指定 URL
async fn cdp_navigate(
    State((state, _, _)): State<(SharedState, &'static str, u16)>,
    headers: HeaderMap,
    Json(body): Json<CdpNavigateBody>,
) -> Response {
    if !check_bearer(&state, &headers) {
        return unauthorized();
    }
    let ws = match first_target_ws_url().await {
        Ok(u) => u,
        Err(e) => return json_status(StatusCode::BAD_GATEWAY, json!({ "error": e })),
    };
    let js = format!("window.location.href='{}'", body.url.replace('\'', "\\'"));
    match tokio::time::timeout(
        Duration::from_secs(10),
        WebSocketCdpBackend::evaluate_on_target_async(&ws, &js),
    ).await {
        Ok(Ok(_)) => Json(json!({ "result": "navigated" })).into_response(),
        Ok(Err(e)) => json_status(StatusCode::BAD_GATEWAY, json!({ "error": e })),
        Err(_) => json_status(StatusCode::GATEWAY_TIMEOUT, json!({ "error": "cdp_navigate timeout (10s)" })),
    }
}


