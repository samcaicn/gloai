
//
// Periodic report sender. The TypeScript module bundled the most
// recent hermes events into a JSON payload and POST'd them to a
// configured endpoint. The Rust port keeps the data shape and a
// `send()` helper.

use std::time::Duration;
use reqwest::Client as HttpClient;
use serde::{Deserialize, Serialize};

/// 上报失败时的最大尝试次数（1 次首发 + 2 次重试 = 3 次总尝试）。
/// 与 mcp_proxy 的 MAX_ATTEMPTS=2 不同：上报是 fire-and-forget，
/// 多一次重试换更可靠的遥测，成本只是 200ms*2 的 backoff。
const MAX_ATTEMPTS: u32 = 3;
const BACKOFF_MS: u64 = 200;

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct ReportPayload {
    pub install_id: String,
    pub session_id: Option<String>,
    pub events: Vec<serde_json::Value>,
    pub schema_version: u32,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct SendResult {
    pub success: bool,
    pub status: Option<u16>,
    pub body: Option<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct SenderConfig {
    pub endpoint: String,
    pub api_key: Option<String>,
}

pub struct ReportSender {
    cfg: SenderConfig,
    http: HttpClient,
}

impl ReportSender {
    pub fn new(cfg: SenderConfig) -> Self {
        // ai.tuptup.top 是境内 IP，强制直连：用户机器可能设置了
        // Clash 代理环境变量但代理软件未运行，会导致 os error 10061
        // 连接被拒，上报静默失败。
        let http = HttpClient::builder()
            .no_proxy()
            .timeout(Duration::from_secs(15))
            .build()
            .expect("http client builder");
        Self { cfg, http }
    }

    /// 发送一次上报。失败时按 200ms backoff 重试，最多
    /// `MAX_ATTEMPTS` 次总尝试。返回最后一次结果。
    pub async fn send(&self, payload: ReportPayload) -> SendResult {
        let mut last: Option<SendResult> = None;
        for attempt in 1..=MAX_ATTEMPTS {
            let mut req = self.http.post(&self.cfg.endpoint).json(&payload);
            if let Some(k) = &self.cfg.api_key {
                req = req.bearer_auth(k);
            }
            match req.send().await {
                Ok(resp) => {
                    let status = resp.status().as_u16();
                    let body = resp.text().await.unwrap_or_default();
                    let result = SendResult {
                        success: status < 400,
                        status: Some(status),
                        body: Some(body),
                    };
                    // 2xx/3xx 视为成功，立即返回；4xx/5xx 也返回
                    // （上报端点返回 4xx 多半是 payload 格式问题，
                    // 重试无意义）。
                    return result;
                }
                Err(e) => {
                    // 仅传输层错误（连接拒绝 / TLS / 超时）才重试。
                    last = Some(SendResult {
                        success: false,
                        status: None,
                        body: Some(format!("{e}")),
                    });
                    if attempt < MAX_ATTEMPTS {
                        tokio::time::sleep(Duration::from_millis(BACKOFF_MS)).await;
                    }
                }
            }
        }
        last.unwrap_or(SendResult {
            success: false,
            status: None,
            body: Some("report send exhausted attempts".to_string()),
        })
    }
}

pub static REPORT_SENDER: std::sync::OnceLock<ReportSender> = std::sync::OnceLock::new();

/// 在进程启动时初始化全局 `REPORT_SENDER`。幂等：第二次调用
/// 静默返回 false（`OnceLock::set` 语义）。应在 `hermes::mod.rs`
/// 的 `HermesAppState::new()` / `with_persistence()` 里调用，
/// 传入从云端基址派生的上报端点。
///
/// TODO: 目前没有任何代码路径调用 `ReportSender::send`，整个上报
/// 模块仍是死代码。需要在以下"执行结束处"接入 `spawn_report`：
///   1. agent 一次 run 结束后（agent.rs / agent_events.rs）
///   2. cron job 触发完成（cron.rs）
///   3. evolution run 完成（evolution.rs）
pub fn init_report_sender(cfg: SenderConfig) -> bool {
    REPORT_SENDER.set(ReportSender::new(cfg)).is_ok()
}

/// 非阻塞上报：若 `REPORT_SENDER` 已初始化，则在后台 tokio task
/// 里发送；未初始化则静默丢弃（上报是 best-effort，不应影响主流程）。
/// 这是给"执行结束处"调用的便利函数，避免每个调用点都手写
/// `tokio::spawn` + `OnceLock::get`。
pub fn spawn_report(payload: ReportPayload) {
    if let Some(sender) = REPORT_SENDER.get() {
        // sender 是 &'static（REPORT_SENDER 是 static OnceLock），
        // 可以安全 move 进 spawned future。
        tokio::spawn(async move {
            let _ = sender.send(payload).await;
        });
    }
}
