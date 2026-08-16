// Copyright (c) 2026 MeeJoy
//
// IM 扫码 OAuth 命令 (目前仅支持飞书 / Lark)。
//
// 【铁律】所有 OAuth URL 写死在 `hermes::im::im_endpoints::ImChannelKind`
// 里的 `feishu_oauth_url()`。本模块不接收任何 URL 参数，也不允许前端
// 覆盖。扫码成功返回的 App ID / App Secret 由前端拿去 `im_config_set`，
// 后端 `im_config_set` 会用 `feishu_bootstrap_url()` 强制覆盖 endpoint。
//
// 飞书 OAuth device flow 协议 (抄自 oapi-sdk-go / Hermes-CN-Desktop
// `src/commands/im_onboarding.rs::begin_feishu`)：
//   Step 1: POST {feishu_oauth_url}
//           body: action=init
//           → {"supported_auth_methods":["client_secret",...]}
//   Step 2: POST {feishu_oauth_url}
//           body: action=begin
//                 &archetype=PersonalAgent
//                 &auth_method=client_secret
//                 &request_user_info=open_id
//           → {
//              "device_code": "...",
//              "user_code":   "ABCD-1234",
//              "verification_uri": "https://accounts.feishu.cn/oauth/v1/app/registration/...",
//              "verification_uri_complete": "https://...?user_code=ABCD-1234",
//              "interval": 5,
//              "expire_in": 600
//            }
//   Step 3: 轮询 POST {feishu_oauth_url}
//           body: action=poll&device_code=...
//           → {
//              "status":     "pending" | "scanned" | "completed" | "expired",
//              "app_id":     "cli_xxx",   // 仅 completed 时存在
//              "app_secret": "xxx",        // 仅 completed 时存在
//              "open_id":    "ou_xxx"      // 用户 open_id
//            }
//
// 前端流程：
//   1. 调 `im_oauth_begin_feishu(domain)` → 弹 QR 码 (verification_uri_complete)
//   2. 轮询 `im_oauth_poll_feishu(flow_id)` → 拿到 app_id + app_secret 后
//      调 `im_config_set({...})` 创建渠道
//   3. 后端 `im_config_set` → `init_im_channels` → 长连接自动建立
//   4. QR 弹窗自动关闭

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use once_cell::sync::Lazy;
use reqwest::Client;
use serde::{Deserialize, Serialize};
use tauri::State;

use crate::hermes::im::im_endpoints::ImChannelKind;

// -----------------------------------------------------------------------
// HTTP client (共享 + .no_proxy() + connect_timeout)
// -----------------------------------------------------------------------

/// 共享 reqwest Client。项目硬约束:所有连云端的 reqwest::Client::builder()
// 必须加 .no_proxy() 强制直连 (ai.tuptup.top / accounts.feishu.cn 都是境内 IP,
// 用户本地代理环境变量如 Clash 127.0.0.1:1082 若代理软件未运行会导致连不上)。
// connect_timeout 5s 快速触发失败,timeout 15s 总兜底。
static SHARED_HTTP_CLIENT: Lazy<Client> = Lazy::new(|| {
    Client::builder()
        .connect_timeout(Duration::from_secs(5))
        .timeout(Duration::from_secs(15))
        .no_proxy()
        .user_agent(concat!("AIMarketing/", env!("CARGO_PKG_VERSION")))
        .build()
        .unwrap_or_else(|_| Client::new())
});

/// 格式化 reqwest 错误,遍历 source() 链拼接完整原因。
/// reqwest::Error 默认 to_string() 只返回 "error sending request for url (...)",
/// 丢失底层 DNS/TLS/代理等真实错误,这里展开整条 source 链便于诊断。
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

/// 检测错误消息是否疑似"代理未运行"模式,命中时返回中文提示引导用户排查环境变量。
fn proxy_failure_hint(msg: &str) -> Option<&'static str> {
    let lower = msg.to_ascii_lowercase();
    let hit = lower.contains("tunnel")
        || lower.contains("proxy")
        || lower.contains("10061")
        || lower.contains("connection refused")
        || lower.contains("目标计算机积极拒绝")
        || lower.contains("no connection could be made");
    if hit {
        Some("网络连接失败:疑似本机代理环境变量 (HTTP_PROXY/HTTPS_PROXY) 指向未运行的代理。请清空环境变量或启动代理软件后重试。")
    } else {
        None
    }
}

// -----------------------------------------------------------------------
// 共享状态：in-memory 扫码 flow 表。重启后 flow 失效，需重新开始。
// -----------------------------------------------------------------------

#[derive(Default)]
pub struct FeishuOAuthState {
    flows: Mutex<HashMap<String, FeishuOAuthFlow>>,
}

#[derive(Clone)]
struct FeishuOAuthFlow {
    /// 飞书 / Lark 域。"feishu" = 国内；"feishu_lark" = 国际。
    domain: String,
    /// 飞书 / Lark OAuth device code。
    device_code: String,
    /// 轮询间隔（秒）。Feishu 实际从 begin 响应里给，我们尊重服务端。
    interval: Duration,
    /// 流程过期时间。超时后 poll 返回 "expired" 状态。
    expires_at: Instant,
    /// 反劫持锚点：begin 时生成的随机 nonce，completed 时返回给前端校验。
    /// 防止 device_code 泄露后攻击者在自己手机上扫码把受害者客户端绑到
    /// 攻击者的飞书 App。前端拿到的 anchor 应与本次扫码会话一致才落库。
    initiator_anchor: String,
}

impl FeishuOAuthState {
    /// 清理已过期的 OAuth flow，避免崩溃/断网导致 flow 永驻内存。
    /// lib.rs 周期性调用（例如每 5 分钟）。
    ///
    /// 注意：`flows` 是 `std::sync::Mutex`，这里同步加锁，不要在 async
    /// 上下文长时间持有；retain 走 HashMap 内部遍历，单次百微秒级。
    pub fn cleanup_expired(&self) {
        let mut flows = match self.flows.lock() {
            Ok(g) => g,
            Err(e) => {
                tracing::warn!("[feishu-oauth] cleanup_expired lock failed: {}", e);
                return;
            }
        };
        let now = Instant::now();
        let before = flows.len();
        flows.retain(|_, flow| flow.expires_at > now);
        let removed = before - flows.len();
        if removed > 0 {
            tracing::info!("[feishu-oauth] cleanup_expired removed {} expired flows", removed);
        }
    }
}

// -----------------------------------------------------------------------
// Tauri command 入参 / 出参
// -----------------------------------------------------------------------

/// 域参数。"feishu" (国内) 或 "feishu_lark" (国际)。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum FeishuOAuthDomain {
    Feishu,
    FeishuLark,
}

impl FeishuOAuthDomain {
    fn as_kind(&self) -> ImChannelKind {
        match self {
            Self::Feishu => ImChannelKind::Feishu,
            Self::FeishuLark => ImChannelKind::FeishuLark,
        }
    }
    /// 返回前端 channelType 标签 ("feishu" / "feishu_lark"),
    /// 用于扫码完成后落库时 provider.type 字段对齐。
    /// 【BUGFIX】之前固定返回 "feishu",导致 feishu_lark tab 下扫码的渠道
    /// 在前端 channelMatchesTab 匹配时落到 feishu tab,用户以为渠道丢了。
    fn as_platform(&self) -> &'static str {
        match self {
            Self::Feishu => "feishu",
            Self::FeishuLark => "feishu_lark",
        }
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FeishuOAuthBeginResult {
    pub flow_id: String,
    pub platform: String,
    pub status: String,
    pub qr_url: Option<String>,
    pub scan_data: Option<String>,
    pub user_code: Option<String>,
    pub interval_seconds: u64,
    pub expires_at_ms: u64,
    pub message: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FeishuOAuthPollResult {
    pub flow_id: String,
    pub platform: String,
    /// "pending" / "scanned" / "completed" / "expired" / "error"
    pub status: String,
    pub app_id: Option<String>,
    pub app_secret: Option<String>,
    pub open_id: Option<String>,
    pub error: Option<String>,
    pub message: Option<String>,
    /// 反劫持锚点：仅 completed 时返回 Some，前端做二次确认弹窗。
    pub initiator_anchor: Option<String>,
}

#[derive(Debug, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
struct FeishuRegistrationResponse {
    #[serde(default)]
    device_code: Option<String>,
    #[serde(default)]
    user_code: Option<String>,
    #[serde(default)]
    verification_uri: Option<String>,
    #[serde(default)]
    verification_uri_complete: Option<String>,
    #[serde(default)]
    interval: Option<u64>,
    #[serde(default)]
    expire_in: Option<u64>,
    #[serde(default)]
    supported_auth_methods: Option<Vec<String>>,
    #[serde(default)]
    status: Option<String>,
    #[serde(default)]
    app_id: Option<String>,
    #[serde(default)]
    app_secret: Option<String>,
    #[serde(default)]
    open_id: Option<String>,
    #[serde(default)]
    error: Option<String>,
    #[serde(default)]
    error_description: Option<String>,
}

// -----------------------------------------------------------------------
// HTTP helper
// -----------------------------------------------------------------------

/// 默认超时秒数（飞书实际给 600s = 10min，我们封顶）。
const FEISHU_DEFAULT_TIMEOUT_SECS: u64 = 600;
/// 默认轮询间隔（飞书实际给 5s）。
const FEISHU_DEFAULT_POLL_SECS: u64 = 5;

fn feishu_oauth_url(domain: &FeishuOAuthDomain) -> Option<&'static str> {
    domain.as_kind().feishu_oauth_url()
}

/// 把响应体里的敏感凭据字段值替换为 `***`，防止错误返回前端时泄露
/// device_code / app_secret / access_token 等。
///
/// 用正则匹配 `"field":"value"` 形式（容忍空格 / 大小写），value 替换
/// 为 `***`。对非 JSON 文本（如 HTML 错误页）安全无副作用。
fn sanitize_response_body(text: &str) -> String {
    use regex::Regex;
    use std::sync::OnceLock;
    static RE: OnceLock<Regex> = OnceLock::new();
    let re = RE.get_or_init(|| {
        Regex::new(
            r#"(?i)("(?:device_code|app_secret|secret|access_token|refresh_token|user_code)"\s*:\s*)"[^"]*""#,
        )
        .expect("sanitize_response_body regex is constant and valid")
    });
    re.replace_all(text, r#"$1"***""#).into_owned()
}

/// 连接级错误重试次数（含首次共 3 次）。
const FEISHU_HTTP_RETRY_ATTEMPTS: u32 = 3;

async fn feishu_registration_post(
    client: &Client,
    oauth_url: &str,
    body: &[(&str, &str)],
) -> Result<FeishuRegistrationResponse, String> {
    // 连接级重试：DNS 抖动 / TLS 握手瞬时失败时指数退避重试，
    // HTTP 4xx/5xx 不重试（业务错误重试也没用，反而放大流量）。
    let mut last_connect_err: Option<reqwest::Error> = None;
    let resp = {
        let mut got_resp: Option<reqwest::Response> = None;
        for attempt in 0..FEISHU_HTTP_RETRY_ATTEMPTS {
            let send_result = client
                .post(oauth_url)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .form(body)
                .send()
                .await;
            match send_result {
                Ok(resp) => {
                    got_resp = Some(resp);
                    break;
                }
                Err(e) if e.is_connect() || e.is_timeout() => {
                    last_connect_err = Some(e);
                    if attempt + 1 < FEISHU_HTTP_RETRY_ATTEMPTS {
                        // 指数退避: 300ms / 600ms / ...
                        let backoff_ms = 300u64 * 2u64.pow(attempt);
                        tokio::time::sleep(Duration::from_millis(backoff_ms)).await;
                        continue;
                    }
                    // 已是最后一次尝试，落到下面的兜底错误返回
                    break;
                }
                Err(e) => {
                    // 非连接级错误（如请求构造失败、body 编码错）直接返回，不重试
                    let detail = format_reqwest_error(&e);
                    if let Some(hint) = proxy_failure_hint(&detail) {
                        return Err(format!("飞书 OAuth 网络错误: {} | {}", detail, hint));
                    }
                    return Err(format!("飞书 OAuth 网络错误: {}", detail));
                }
            }
        }
        match got_resp {
            Some(r) => r,
            None => {
                let e = last_connect_err
                    .ok_or_else(|| "飞书 OAuth 连接失败: 未知错误".to_string())?;
                let detail = format_reqwest_error(&e);
                if let Some(hint) = proxy_failure_hint(&detail) {
                    return Err(format!(
                        "飞书 OAuth 连接失败（重试{}次）: {} | {}",
                        FEISHU_HTTP_RETRY_ATTEMPTS, detail, hint
                    ));
                }
                return Err(format!(
                    "飞书 OAuth 连接失败（重试{}次）: {}",
                    FEISHU_HTTP_RETRY_ATTEMPTS, detail
                ));
            }
        }
    };

    let status = resp.status();
    let text = resp.text().await.unwrap_or_default();
    if !status.is_success() {
        // 飞书 OAuth device flow 走 RFC 8628 风格:轮询时未扫码会返回
        //   HTTP 400 + {"error":"authorization_pending","code":20094}
        // 这是正常的 pending 状态,不是 HTTP 错误。slow_down / expired_token /
        // access_denied 同理。这里尝试解析 body,若是 device flow pending 类
        // 错误码则视为正常响应返回 Ok,交由上层 poll 状态机映射。
        // 对 init/begin 步骤也安全:此时 device_code/supported_auth_methods
        // 均为 None,调用方会因缺失必填字段而 Err,不会误判成功。
        if let Ok(parsed) = serde_json::from_str::<FeishuRegistrationResponse>(&text) {
            if let Some(err) = parsed.error.as_deref() {
                let err_lower = err.to_ascii_lowercase();
                let is_device_flow_state = matches!(
                    err_lower.as_str(),
                    "authorization_pending" | "slow_down" | "expired_token" | "access_denied"
                );
                if is_device_flow_state {
                    return Ok(parsed);
                }
            }
        }
        return Err(format!(
            "飞书 OAuth HTTP {}: {}",
            status,
            sanitize_response_body(&text).chars().take(200).collect::<String>()
        ));
    }
    serde_json::from_str(&text).map_err(|e| {
        format!(
            "飞书 OAuth 响应解析失败: {} (body={})",
            e,
            sanitize_response_body(&text).chars().take(200).collect::<String>()
        )
    })
}

fn new_flow_id() -> String {
    use rand::Rng;
    let mut rng = rand::thread_rng();
    let bytes: [u8; 16] = rng.gen();
    format!("feishu-oauth-{}", hex_encode(&bytes))
}

fn hex_encode(bytes: &[u8]) -> String {
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        s.push_str(&format!("{:02x}", b));
    }
    s
}

// -----------------------------------------------------------------------
// Tauri commands
// -----------------------------------------------------------------------

/// 开始飞书 OAuth 扫码流程。
/// 调 `https://accounts.feishu.cn/oauth/v1/app/registration` (URL 来自
/// `ImChannelKind::feishu_oauth_url()`)，返回 `verification_uri_complete`
/// 让前端生成 QR 码。
#[tauri::command]
pub async fn im_oauth_begin_feishu(
    state: State<'_, FeishuOAuthState>,
    domain: FeishuOAuthDomain,
) -> Result<FeishuOAuthBeginResult, String> {
    let oauth_url = feishu_oauth_url(&domain)
        .ok_or_else(|| format!("no feishu oauth url for domain={:?}", domain))?;
    let client = SHARED_HTTP_CLIENT.clone();
    let platform = domain.as_platform().to_string();

    // Step 1: action=init — 检查支持的 auth methods
    let init = feishu_registration_post(
        &client,
        oauth_url,
        &[("action", "init")],
    )
    .await?;
    let supported = init
        .supported_auth_methods
        .as_ref()
        .map(|arr| arr.iter().any(|m| m == "client_secret"))
        .unwrap_or(false);
    if !supported {
        return Err(format!(
            "feishu oauth: server does not support client_secret auth (supported={:?})",
            init.supported_auth_methods
        ));
    }

    // Step 2: action=begin — 拿 device_code + QR URL
    let begin = feishu_registration_post(
        &client,
        oauth_url,
        &[
            ("action", "begin"),
            ("archetype", "PersonalAgent"),
            ("auth_method", "client_secret"),
            ("request_user_info", "open_id"),
        ],
    )
    .await?;

    let device_code = begin
        .device_code
        .clone()
        .filter(|s| !s.is_empty())
        .ok_or_else(|| "feishu oauth: missing device_code in begin response".to_string())?;

    let mut qr_url = begin
        .verification_uri_complete
        .clone()
        .filter(|s| !s.is_empty())
        .or_else(|| begin.verification_uri.clone())
        .unwrap_or_default();

    // 在 QR URL 上追加 tupa 标记，方便服务端做来源统计。
    if !qr_url.is_empty() {
        let marker = "from=tupai&tp=tupai";
        if qr_url.contains('?') {
            qr_url.push('&');
        } else {
            qr_url.push('?');
        }
        qr_url.push_str(marker);
    }

    let interval_secs = begin.interval.unwrap_or(FEISHU_DEFAULT_POLL_SECS).max(1);
    let expire_secs = begin
        .expire_in
        .unwrap_or(FEISHU_DEFAULT_TIMEOUT_SECS)
        .min(FEISHU_DEFAULT_TIMEOUT_SECS);
    let interval = Duration::from_secs(interval_secs);
    let timeout = Duration::from_secs(expire_secs);
    let expires_at = Instant::now() + timeout;
    let expires_at_ms_i: i64 =
        chrono::Utc::now().timestamp_millis() + timeout.as_millis() as i64;
    let expires_at_ms: u64 = expires_at_ms_i.max(0) as u64;

    let flow_id = new_flow_id();
    // 反劫持锚点：begin 阶段生成随机 nonce 存入 flow，completed 时回传前端。
    // 攻击者即便拿到 device_code 在自己手机上扫码完成，前端因为 anchor
    // 校验失败（与本次扫码会话不匹配）会拒绝落库。
    let initiator_anchor = uuid::Uuid::new_v4().to_string();
    let flow = FeishuOAuthFlow {
        domain: match domain {
            FeishuOAuthDomain::Feishu => "feishu".to_string(),
            FeishuOAuthDomain::FeishuLark => "feishu_lark".to_string(),
        },
        device_code: device_code.clone(),
        interval,
        expires_at,
        initiator_anchor,
    };

    {
        let mut g = state.flows.lock().map_err(|e| format!("oauth state lock: {}", e))?;
        g.insert(flow_id.clone(), flow);
    }

    Ok(FeishuOAuthBeginResult {
        flow_id,
        platform,
        status: "pending".to_string(),
        qr_url: (!qr_url.is_empty()).then(|| qr_url.clone()),
        scan_data: (!qr_url.is_empty()).then_some(qr_url),
        user_code: begin.user_code.clone(),
        interval_seconds: interval_secs,
        expires_at_ms,
        message: Some("请使用飞书 / Lark 手机端扫码并确认授权".to_string()),
    })
}

/// 轮询飞书 OAuth 扫码状态。
/// 状态机：pending → scanned → completed（或 expired / error）
/// completed 时返回 app_id + app_secret，前端拿去调 `im_config_set`。
#[tauri::command]
pub async fn im_oauth_poll_feishu(
    state: State<'_, FeishuOAuthState>,
    flow_id: String,
) -> Result<FeishuOAuthPollResult, String> {
    let flow = {
        let g = state.flows.lock().map_err(|e| format!("oauth state lock: {}", e))?;
        g.get(&flow_id).cloned()
    };
    let flow = flow.ok_or_else(|| format!("oauth flow not found: {}", flow_id))?;
    // platform 跟随 flow.domain:feishu_lark 扫码的渠道 type 必须是 "feishu_lark",
    // 否则前端 channelMatchesTab 在 feishu_lark tab 下匹配不到该渠道。
    let platform = flow.domain.clone();

    if Instant::now() > flow.expires_at {
        // 超时：从表中删除，避免脏数据。
        let _ = state.flows.lock().map(|mut g| g.remove(&flow_id));
        return Ok(FeishuOAuthPollResult {
            flow_id: flow_id.clone(),
            platform: platform.clone(),
            status: "expired".to_string(),
            app_id: None,
            app_secret: None,
            open_id: None,
            error: Some("oauth flow expired (over 10min)".to_string()),
            message: Some("扫码超时，请重新开始".to_string()),
            initiator_anchor: None,
        });
    }

    let oauth_url = match flow.domain.as_str() {
        "feishu_lark" => ImChannelKind::FeishuLark.feishu_oauth_url(),
        _ => ImChannelKind::Feishu.feishu_oauth_url(),
    }
    .ok_or_else(|| "feishu oauth url missing".to_string())?;

    let client = SHARED_HTTP_CLIENT.clone();
    let poll_result = feishu_registration_post(
        &client,
        oauth_url,
        &[("action", "poll"), ("device_code", &flow.device_code)],
    )
    .await;
    let resp = match poll_result {
        Ok(r) => r,
        Err(e) => {
            return Ok(FeishuOAuthPollResult {
                flow_id: flow_id.clone(),
                platform: platform.clone(),
                status: "error".to_string(),
                app_id: None,
                app_secret: None,
                open_id: None,
                error: Some(e),
                message: Some("轮询失败".to_string()),
                initiator_anchor: None,
            });
        }
    };

    // device flow 在 pending 类状态(未扫码 / 慢速 / 过期 / 拒绝)时飞书返回
    //   HTTP 400 + {"error":"authorization_pending"} (见 feishu_registration_post
    //   的 4xx 容错分支),此时 resp.status 为 None,用 resp.error 作为状态源。
    let status = resp
        .status
        .clone()
        .or_else(|| resp.error.clone())
        .unwrap_or_else(|| "pending".to_string());
    // 状态映射抄自 lark-cli internal/auth/device_flow.go::PollDeviceToken
    // 与飞书 /oauth/v1/app/registration 返回的 status 字段对齐。
    // 飞书租户管理员审批场景会返回 pending_admin_approval / admin_approval /
    // pending_approval，若原样透传前端会落入 error 兜底误判，这里统一映射到
    // "pending_admin_approval" 让前端走"等待管理员审批"分支。
    let result_status = match status.as_str() {
        "completed" | "complete" | "success" | "authorized" | "ok" => "completed",
        "scanned" | "scanning" | "user_scanned" => "scanned",
        "expired" | "timeout" | "expire" | "expired_token" => "expired",
        "denied" | "rejected" | "access_denied" => "denied",
        "slow_down" | "slowdown" => "slow_down",
        "pending" | "authorization_pending" => "pending",
        "pending_admin_approval" | "admin_approval" | "pending_approval" => {
            "pending_admin_approval"
        }
        other => other,
    }
    .to_string();

    // completed：清理 flow 并返回凭据
    if result_status == "completed" {
        let _ = state.flows.lock().map(|mut g| g.remove(&flow_id));
        return Ok(FeishuOAuthPollResult {
            flow_id: flow_id.clone(),
            platform: platform.clone(),
            status: "completed".to_string(),
            app_id: resp.app_id.clone(),
            app_secret: resp.app_secret.clone(),
            open_id: resp.open_id.clone(),
            error: None,
            message: Some("扫码成功，已拿到 App 凭据".to_string()),
            // 反劫持锚点回传前端做二次确认弹窗
            initiator_anchor: Some(flow.initiator_anchor.clone()),
        });
    }

    // 终态（expired / denied / error）：清理 flow，前端会清空 QR
    if matches!(result_status.as_str(), "expired" | "denied" | "error") {
        let _ = state.flows.lock().map(|mut g| g.remove(&flow_id));
    }

    Ok(FeishuOAuthPollResult {
        flow_id: flow_id.clone(),
        platform: platform.clone(),
        status: result_status,
        app_id: resp.app_id,
        app_secret: resp.app_secret,
        open_id: resp.open_id,
        error: resp.error_description.or(resp.error),
        message: None,
        initiator_anchor: None,
    })
}

/// 取消正在进行的 OAuth 流程。
#[tauri::command]
pub async fn im_oauth_cancel_feishu(
    state: State<'_, FeishuOAuthState>,
    flow_id: String,
) -> Result<bool, String> {
    let mut g = state.flows.lock().map_err(|e| format!("oauth state lock: {}", e))?;
    Ok(g.remove(&flow_id).is_some())
}
