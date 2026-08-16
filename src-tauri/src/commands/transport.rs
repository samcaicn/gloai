// Copyright (c) 2026 AIMarketing
//
// Tauri command surface for the 8642 transport.
//
// 暴露两个命令(由主线程在 `commands/mod.rs` 里注册):
//   * `transport_status`   — HTTP health 探针 + 最近一次错误
//   * `transport_reconnect` — 触发 WS 半边立刻重连(取消当前退避)
//
// 事件侧:WS 重连任务由主线程在 `lib.rs` 里 spawn,本文件
// 不主动 emit 事件 —— emit 逻辑在 `HermesWs` 重连循环里通过
// `AppHandle` 完成,事件名 `tupai-transport-event`。
//
// 鉴权 token 由 `auth::TransportToken::new(fingerprint)` 派生;
// fingerprint 在本命令里调 `commands::hardware::compute_hardware_fingerprint()`
// (该函数 v3 已在,本文件**只是使用**它,不动 `commands::hardware`)。

use std::sync::Arc;

use chrono::Utc;
use serde::Serialize;
use tauri::{AppHandle, Manager, State};

use crate::commands::hardware::compute_hardware_fingerprint;
use crate::hermes::auth::TransportToken;
use crate::hermes::transport::{
    HermesTransport, TransportError, TransportStatus,
};
use crate::hermes::HermesAppState;

const DEFAULT_BASE_URL: &str = "http://127.0.0.1:8642";

/// 全局 transport runtime 的句柄。
///
/// 主线程可以调用 `init_transport_runtime` 把 `TransportRuntime` 通过
/// `app.manage(...)` 挂上;`transport_status` / `transport_reconnect`
/// 命令会从 `HermesAppState` 里取它。`HermesAppState` 本身
/// (在 `hermes/mod.rs`) 不属于本 agent 的所有权,所以这里用一个
/// 独立的 Tauri state 容器 `<TransportRuntime>` 让命令可访问。
///
/// 为避免强加新的状态管理约定,本模块也支持"裸调用"路径:
/// 如果 `TransportRuntime` 没有被 `manage`,命令就退回到
/// `HermesTransport::default()` 临时构建一个(无 token,无保活)。
pub struct TransportRuntime {
    pub base_url: String,
    /// 持有 `Arc<tokio::sync::Notify>`,WS 重连任务和 `transport_reconnect`
    /// 命令共享这个 Notify。命令 notify_one() 即可让重连任务立即
    /// 跳出当前 backoff sleep,不必等满 60s。
    pub reconnect_notify: Arc<tokio::sync::Notify>,
}

impl TransportRuntime {
    pub fn new(base_url: impl Into<String>) -> Self {
        Self {
            base_url: base_url.into(),
            reconnect_notify: Arc::new(tokio::sync::Notify::new()),
        }
    }
}

impl Default for TransportRuntime {
    fn default() -> Self { Self::new(DEFAULT_BASE_URL) }
}

// -- 公共响应 ----------------------------------------------------------------

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TransportStatusResponse {
    /// 复刻 `hermes::transport::TransportStatus` 的字段,主线程
    /// 转发给前端时不用再 `pub use` 一遍。
    pub base_url: String,
    pub connected: bool,
    pub last_error: Option<String>,
    pub checked_at: chrono::DateTime<Utc>,
}

impl From<TransportStatus> for TransportStatusResponse {
    fn from(s: TransportStatus) -> Self {
        Self {
            base_url: s.base_url,
            connected: s.connected,
            last_error: s.last_error,
            checked_at: s.checked_at,
        }
    }
}

// -- 命令 --------------------------------------------------------------------

/// `transport_status` —— 一发 HTTP /v1/health 探针。
///
/// 5s 超时由 `HermesTransport::health()` 内部控制。不可达时返回
/// `connected: false`,**不**抛错(离线降级是常态,不是异常)。
#[tauri::command]
pub async fn transport_status(
    app: AppHandle,
    _state: State<'_, HermesAppState>,
) -> Result<TransportStatusResponse, String> {
    let (base_url, token) = resolve_runtime(&app).await;
    let transport = build_transport(&base_url, token.as_deref());

    match transport.health().await {
        Ok(true) => {
            let status = TransportStatus {
                base_url: transport.base_url().to_string(),
                connected: true,
                last_error: None,
                checked_at: Utc::now(),
            };
            Ok(status.into())
        }
        Ok(false) => {
            let status = TransportStatus {
                base_url: transport.base_url().to_string(),
                connected: false,
                last_error: Some("health probe returned non-2xx or timed out".to_string()),
                checked_at: Utc::now(),
            };
            log::debug!("[transport_status] 8642 不可达,降级为离线模式");
            Ok(status.into())
        }
        Err(TransportError::Unreachable(msg)) => {
            // 这种分支实际上不会进 —— health() 把 unreachable 也吞成 Ok(false)。
            // 保留做防御。
            log::warn!("[transport_status] health 抛了 Unreachable: {}", msg);
            let status = TransportStatus {
                base_url: transport.base_url().to_string(),
                connected: false,
                last_error: Some(msg),
                checked_at: Utc::now(),
            };
            Ok(status.into())
        }
        Err(error) => {
            log::error!("[transport_status] 意外错误: {}", error);
            Err(error.to_string())
        }
    }
}

/// `transport_reconnect` —— 触发 WS 半边立刻重连,跳过退避 backoff。
///
/// HTTP 半边是 one-shot,不存在"重连"概念;这里只负责唤醒 WS。
/// 如果 WS 重连任务还没起,本命令是 no-op,直接返回 `Ok(())`。
#[tauri::command]
pub async fn transport_reconnect(
    app: AppHandle,
    _state: State<'_, HermesAppState>,
) -> Result<(), String> {
    let runtime = app.try_state::<TransportRuntime>();
    match runtime {
        Some(rt) => {
            rt.reconnect_notify.notify_one();
            log::info!("[transport_reconnect] 通知 WS 重连任务立即重连");
            Ok(())
        }
        None => {
            // 没挂 TransportRuntime,WS 任务大概率也没起。降级为"立即
            // 做一次 health 探针",让前端至少能看到当前连接状态。
            log::warn!("[transport_reconnect] TransportRuntime 未挂载,执行 ad-hoc health 探针");
            let (base_url, token) = (DEFAULT_BASE_URL.to_string(), None);
            let transport = build_transport(&base_url, token.as_deref());
            let ok = transport.health().await.unwrap_or(false);
            log::info!("[transport_reconnect] ad-hoc health = {}", ok);
            Ok(())
        }
    }
}

// -- 内部 helper -------------------------------------------------------------

/// 从 Tauri state 拿 runtime;拿不到就退化到默认 base_url。
async fn resolve_runtime(app: &AppHandle) -> (String, Option<String>) {
    let base_url = app
        .try_state::<TransportRuntime>()
        .map(|rt| rt.inner().base_url.clone())
        .unwrap_or_else(|| DEFAULT_BASE_URL.to_string());
    let token = derive_token();
    (base_url, Some(token))
}

fn derive_token() -> String {
    let fingerprint = compute_hardware_fingerprint();
    TransportToken::new(&fingerprint)
}

fn build_transport(base_url: &str, token: Option<&str>) -> HermesTransport {
    let transport = HermesTransport::new(base_url);
    match token {
        Some(t) => transport.with_token(t.to_string()),
        None => transport,
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn transport_runtime_default_uses_local_8642() {
        let rt = TransportRuntime::default();
        assert_eq!(rt.base_url, "http://127.0.0.1:8642");
    }

    #[test]
    fn status_response_serializes_camel_case() {
        let resp = TransportStatusResponse {
            base_url: "http://127.0.0.1:8642".to_string(),
            connected: true,
            last_error: None,
            checked_at: Utc::now(),
        };
        let json = serde_json::to_string(&resp).unwrap();
        assert!(json.contains("baseUrl"));
        assert!(json.contains("checkedAt"));
    }
}
