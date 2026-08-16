// Copyright (c) 2026 MeeJoy
//
// 通用 IM 扫码登录命令。支持微信 (iLink)、企微、QQ Bot。
//
// 微信 iLink 协议 (抄自 BitFun weixin.rs + @tencent-weixin/openclaw-weixin):
//   Step 1: GET {base}/ilink/bot/get_bot_qrcode?bot_type=3
//           → {"qrcode":"...", "qrcode_img_content":"..."}
//   Step 2: GET {base}/ilink/bot/get_qrcode_status?qrcode=...
//           → {"status":"wait|scaned|confirmed|expired", "bot_token":"...", "ilink_bot_id":"...", "baseurl":"..."}
//
// 企微扫码 (抄自企业微信开放平台 SSO QR Connect):
//   用户在企微设置中填 corpid + agentid + secret,
//   扫码后获取 user_id,用于创建企微渠道。
//
// QQ Bot 扫码 (NTQQ 协议,抄自 Lagrange.Core):
//   Step 1: GET https://ssl.ptlogin2.qq.com/ptqrshow?appid=549000912&e=2&l=M&s=3&d=72&v=4
//           → QR 图片 (PNG bytes, base64 编码给前端)
//   Step 2: GET https://ssl.ptlogin2.qq.com/ptqrlogin?ptqrauth=...&...
//           → HTML 响应,解析 ptuiCB('0','0','...','0','登录成功!', 'uin')
//
// 【铁律】所有 URL 写死在 im_endpoints.rs,前端不允许传 URL。

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use base64::Engine;
use once_cell::sync::Lazy;
use reqwest::Client;
use serde::{Deserialize, Serialize};
use tauri::State;

use crate::hermes::im::im_endpoints::ImChannelKind;

// -----------------------------------------------------------------------
// HTTP client
// -----------------------------------------------------------------------

static SHARED_HTTP_CLIENT: Lazy<Client> = Lazy::new(|| {
    Client::builder()
        .connect_timeout(Duration::from_secs(5))
        .timeout(Duration::from_secs(30))
        .no_proxy()
        .user_agent(concat!("tupAI/", env!("CARGO_PKG_VERSION")))
        .build()
        .unwrap_or_else(|_| Client::new())
});

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

/// 把 HTTP 响应 body 里的敏感字段值替换为 ***，避免泄露到前端错误消息。
/// 覆盖: device_code / app_secret / secret / access_token / refresh_token
fn sanitize_sensitive_fields(text: &str) -> String {
    use once_cell::sync::Lazy;
    use regex::Regex;
    static RE: Lazy<Regex> = Lazy::new(|| {
        Regex::new(
            r#""(device_code|app_secret|secret|access_token|refresh_token)"\s*:\s*"[^"]*""#,
        )
        .expect("sanitize regex")
    });
    RE.replace_all(text, r#""$1":"***""#).to_string()
}

// -----------------------------------------------------------------------
// QR login state
// -----------------------------------------------------------------------

#[derive(Default)]
pub struct QrLoginState {
    flows: Mutex<HashMap<String, QrLoginFlow>>,
}

impl QrLoginState {
    /// 清理已过期的扫码 flow。同步实现 (用的是 std::sync::Mutex)。
    pub fn cleanup_expired(&self) {
        let mut flows = match self.flows.lock() {
            Ok(g) => g,
            Err(e) => {
                tracing::warn!("[qr-login] cleanup_expired lock failed: {}", e);
                return;
            }
        };
        let before = flows.len();
        let now = Instant::now();
        flows.retain(|_, flow| flow.expires_at > now);
        let removed = before - flows.len();
        if removed > 0 {
            tracing::info!("[qr-login] cleanup_expired removed {} expired flows", removed);
        }
    }
}

#[derive(Clone)]
struct QrLoginFlow {
    /// 平台类型: "weixin" | "wecom" | "qqbot"
    platform: String,
    /// iLink: qrcode 字符串; QQ Bot: ptqrauth token; WeCom: login_state
    qr_token: String,
    /// QR 图片 URL 或 base64 data URL (给前端渲染)
    qr_image: String,
    /// 流程过期时间
    expires_at: Instant,
    /// iLink: 刷新次数 (expired 后自动刷新,最多 3 次)
    refresh_count: u32,
    /// 反劫持锚点：begin 时生成的随机 nonce，completed 时返回给前端校验。
    /// 防止 qr_token 泄露后攻击者在自己手机上扫码把受害者客户端绑到
    /// 攻击者的 IM 账号。前端拿到的 anchor 应与本次扫码会话一致才落库。
    initiator_anchor: String,
}

// -----------------------------------------------------------------------
// 通用出参
// -----------------------------------------------------------------------

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct QrBeginResult {
    pub flow_id: String,
    pub platform: String,
    pub status: String,
    /// QR 图片 URL 或 base64 data URL (前端直接渲染)
    pub qr_image: String,
    /// 扫码内容字符串 (可选,用于 QRCodeSVG 生成)
    pub qr_data: Option<String>,
    pub expires_at_ms: u64,
    pub message: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct QrPollResult {
    pub flow_id: String,
    pub platform: String,
    /// "pending" | "scanned" | "completed" | "expired" | "error" | "pending_admin_approval"
    pub status: String,
    /// completed 时返回的凭据
    pub token: Option<String>,
    pub bot_id: Option<String>,
    pub base_url: Option<String>,
    /// QR 刷新后的新图片 (expired 时可能返回)
    pub qr_image: Option<String>,
    pub error: Option<String>,
    pub message: Option<String>,
    /// completed 时返回的随机 nonce，用于前端反劫持确认。
    pub initiator_anchor: Option<String>,
}

// -----------------------------------------------------------------------
// 微信 iLink QR 类型
// -----------------------------------------------------------------------

#[derive(Debug, Deserialize)]
struct IlinkQrCodeResponse {
    #[serde(default)]
    qrcode: Option<String>,
    #[serde(default)]
    qrcode_img_content: Option<String>,
}

#[derive(Debug, Deserialize)]
struct IlinkQrStatusResponse {
    #[serde(default)]
    status: Option<String>,
    #[serde(default)]
    bot_token: Option<String>,
    #[serde(default)]
    ilink_bot_id: Option<String>,
    #[serde(default)]
    baseurl: Option<String>,
}

// -----------------------------------------------------------------------
// 企微智能机器人扫码绑定类型 (aibot_subscribe 协议)
// 抄自 @wecom/wecom-openclaw-cli (ISC) dist/utils/qrcode.js
// -----------------------------------------------------------------------

/// GET /ai/qc/generate 响应
#[derive(Debug, Deserialize)]
struct WecomQrGenerateResponse {
    #[serde(default)]
    data: Option<WecomQrGenerateData>,
}

#[derive(Debug, Deserialize)]
struct WecomQrGenerateData {
    #[serde(default)]
    scode: Option<String>,
    #[serde(default)]
    auth_url: Option<String>,
}

/// GET /ai/qc/query_result 响应
#[derive(Debug, Deserialize)]
struct WecomQrQueryResponse {
    #[serde(default)]
    data: Option<WecomQrQueryData>,
}

#[derive(Debug, Deserialize)]
struct WecomQrQueryData {
    #[serde(default)]
    status: Option<String>,
    #[serde(default)]
    bot_info: Option<WecomBotInfo>,
}

#[derive(Debug, Deserialize)]
struct WecomBotInfo {
    #[serde(default)]
    botid: Option<String>,
    #[serde(default)]
    secret: Option<String>,
}

const ILINK_DEFAULT_BASE: &str = "https://ilinkai.weixin.qq.com";
const ILINK_BOT_TYPE: &str = "3";
const MAX_QR_REFRESH: u32 = 3;
const QR_SESSION_TIMEOUT_SECS: u64 = 300;

fn now_ms() -> i64 {
    chrono::Utc::now().timestamp_millis()
}

fn new_flow_id() -> String {
    use rand::Rng;
    let mut rng = rand::thread_rng();
    let bytes: [u8; 16] = rng.gen();
    format!(
        "qr-{}",
        bytes.iter().map(|b| format!("{:02x}", b)).collect::<String>()
    )
}

/// 企微扫码 API 的 plat 参数：darwin=1, win32=2, linux=3。
/// 抄自 @wecom/wecom-openclaw-cli (ISC) dist/utils/qrcode.js::getPlatCode()。
fn wecom_plat_code() -> u8 {
    if cfg!(target_os = "macos") {
        1
    } else if cfg!(target_os = "windows") {
        2
    } else if cfg!(target_os = "linux") {
        3
    } else {
        0
    }
}

// -----------------------------------------------------------------------
// Tauri commands
// -----------------------------------------------------------------------

/// 开始 IM 扫码登录。
/// platform: "weixin" | "wecom" | "qqbot"
#[tauri::command]
pub async fn im_qr_begin(
    state: State<'_, QrLoginState>,
    platform: String,
) -> Result<QrBeginResult, String> {
    match platform.as_str() {
        "weixin" => begin_weixin_qr(&state).await,
        "qqbot" => begin_qqbot_qr(&state).await,
        "wecom" => begin_wecom_qr(&state).await,
        _ => Err(format!("unsupported qr login platform: {}", platform)),
    }
}

/// 轮询 IM 扫码状态。
#[tauri::command]
pub async fn im_qr_poll(
    state: State<'_, QrLoginState>,
    flow_id: String,
) -> Result<QrPollResult, String> {
    let flow = {
        let g = state.flows.lock().map_err(|e| format!("qr state lock: {}", e))?;
        g.get(&flow_id).cloned()
    };
    let flow = flow.ok_or_else(|| format!("qr flow not found: {}", flow_id))?;
    let platform = flow.platform.clone();

    if Instant::now() > flow.expires_at {
        let _ = state.flows.lock().map(|mut g| g.remove(&flow_id));
        return Ok(QrPollResult {
            flow_id,
            platform,
            status: "expired".into(),
            token: None,
            bot_id: None,
            base_url: None,
            qr_image: None,
            error: Some("qr flow expired".into()),
            message: Some("扫码超时，请重新开始".into()),
            initiator_anchor: None,
        });
    }

    match platform.as_str() {
        "weixin" => poll_weixin_qr(&state, flow_id, flow).await,
        "qqbot" => poll_qqbot_qr(&state, flow_id, flow).await,
        "wecom" => poll_wecom_qr(&state, flow_id, flow).await,
        _ => Err(format!("unsupported platform: {}", platform)),
    }
}

/// 取消扫码流程。
#[tauri::command]
pub async fn im_qr_cancel(
    state: State<'_, QrLoginState>,
    flow_id: String,
) -> Result<bool, String> {
    let mut g = state.flows.lock().map_err(|e| format!("qr state lock: {}", e))?;
    Ok(g.remove(&flow_id).is_some())
}

// -----------------------------------------------------------------------
// 微信 iLink QR 实现
// -----------------------------------------------------------------------

async fn begin_weixin_qr(state: &State<'_, QrLoginState>) -> Result<QrBeginResult, String> {
    let base = ILINK_DEFAULT_BASE;
    let url = format!(
        "{}/ilink/bot/get_bot_qrcode?bot_type={}",
        base,
        urlencoding::encode(ILINK_BOT_TYPE)
    );
    let client = SHARED_HTTP_CLIENT.clone();
    let resp = client.get(&url).send().await.map_err(|e| {
        format!("微信扫码: {}", format_reqwest_error(&e))
    })?;
    let status = resp.status();
    if !status.is_success() {
        let body = resp.text().await.unwrap_or_default();
        return Err(format!("微信扫码 HTTP {}: {}", status, &sanitize_sensitive_fields(&body).chars().take(200).collect::<String>()));
    }
    let parsed: IlinkQrCodeResponse = resp.json().await.map_err(|e| {
        format!("微信扫码响应解析失败: {}", e)
    })?;
    let qrcode = parsed.qrcode.filter(|s| !s.is_empty())
        .ok_or_else(|| "微信扫码: missing qrcode".to_string())?;
    let qr_image = parsed.qrcode_img_content.filter(|s| !s.is_empty())
        .ok_or_else(|| "微信扫码: missing qrcode_img_content".to_string())?;

    let flow_id = new_flow_id();
    // 反劫持锚点：begin 阶段生成随机 nonce 存入 flow，completed 时回传前端。
    // 攻击者即便拿到 qr_token 在自己手机上扫码完成，前端因为 anchor
    // 校验失败（与本次扫码会话不匹配）会拒绝落库。
    let initiator_anchor = uuid::Uuid::new_v4().to_string();
    let flow = QrLoginFlow {
        platform: "weixin".into(),
        qr_token: qrcode,
        qr_image: qr_image.clone(),
        expires_at: Instant::now() + Duration::from_secs(QR_SESSION_TIMEOUT_SECS),
        refresh_count: 0,
        initiator_anchor,
    };
    {
        let mut g = state.flows.lock().map_err(|e| format!("qr state lock: {}", e))?;
        g.insert(flow_id.clone(), flow);
    }
    Ok(QrBeginResult {
        flow_id,
        platform: "weixin".into(),
        status: "pending".into(),
        qr_image,
        qr_data: None,
        expires_at_ms: now_ms() as u64 + QR_SESSION_TIMEOUT_SECS * 1000,
        message: Some("请使用微信扫码".into()),
    })
}

async fn poll_weixin_qr(
    state: &State<'_, QrLoginState>,
    flow_id: String,
    flow: QrLoginFlow,
) -> Result<QrPollResult, String> {
    let base = ILINK_DEFAULT_BASE;
    let qrcode_enc = urlencoding::encode(&flow.qr_token);
    let url = format!("{}/ilink/bot/get_qrcode_status?qrcode={}", base, qrcode_enc);
    let client = SHARED_HTTP_CLIENT.clone();
    let resp = client
        .get(&url)
        .header("iLink-App-ClientVersion", "1")
        .timeout(Duration::from_secs(36))
        .send()
        .await;

    let resp = match resp {
        Ok(r) => r,
        Err(e) => {
            if e.is_timeout() {
                return Ok(QrPollResult {
                    flow_id,
                    platform: "weixin".into(),
                    status: "pending".into(),
                    token: None, bot_id: None, base_url: None,
                    qr_image: None, error: None,
                    message: Some("waiting".into()),
                    initiator_anchor: None,
                });
            }
            return Ok(QrPollResult {
                flow_id,
                platform: "weixin".into(),
                status: "error".into(),
                token: None, bot_id: None, base_url: None,
                qr_image: None,
                error: Some(format!("微信扫码轮询: {}", format_reqwest_error(&e))),
                message: Some("轮询失败".into()),
                initiator_anchor: None,
            });
        }
    };

    let status_code = resp.status();
    if !status_code.is_success() {
        let body = resp.text().await.unwrap_or_default();
        let _ = state.flows.lock().map(|mut g| g.remove(&flow_id));
        return Ok(QrPollResult {
            flow_id,
            platform: "weixin".into(),
            status: "error".into(),
            token: None, bot_id: None, base_url: None,
            qr_image: None,
            error: Some(format!("HTTP {}: {}", status_code, &sanitize_sensitive_fields(&body).chars().take(200).collect::<String>())),
            message: Some("轮询失败".into()),
            initiator_anchor: None,
        });
    }

    let parsed: IlinkQrStatusResponse = resp.json().await.map_err(|e| {
        format!("微信扫码状态解析失败: {}", e)
    })?;
    let st = parsed.status.as_deref().unwrap_or("wait");

    match st {
        "wait" => Ok(QrPollResult {
            flow_id, platform: "weixin".into(), status: "pending".into(),
            token: None, bot_id: None, base_url: None,
            qr_image: None, error: None, message: Some("等待扫码".into()),
            initiator_anchor: None,
        }),
        "scaned" => Ok(QrPollResult {
            flow_id, platform: "weixin".into(), status: "scanned".into(),
            token: None, bot_id: None, base_url: None,
            qr_image: None, error: None, message: Some("已扫描，请在手机上确认".into()),
            initiator_anchor: None,
        }),
        "confirmed" => {
            let token = parsed.bot_token.filter(|s| !s.is_empty())
                .ok_or_else(|| "微信扫码: confirmed but bot_token missing".to_string())?;
            let bot_id = parsed.ilink_bot_id.filter(|s| !s.is_empty())
                .ok_or_else(|| "微信扫码: confirmed but ilink_bot_id missing".to_string())?;
            let base_url = parsed.baseurl.filter(|s| !s.is_empty())
                .unwrap_or_else(|| ILINK_DEFAULT_BASE.to_string());
            let _ = state.flows.lock().map(|mut g| g.remove(&flow_id));
            Ok(QrPollResult {
                flow_id, platform: "weixin".into(), status: "completed".into(),
                token: Some(token), bot_id: Some(bot_id), base_url: Some(base_url),
                qr_image: None, error: None,
                message: Some("微信扫码成功".into()),
                initiator_anchor: Some(flow.initiator_anchor.clone()),
            })
        }
        "expired" => {
            // 自动刷新 QR (最多 3 次)
            let over_limit = {
                let mut g = state.flows.lock().map_err(|e| format!("qr state lock: {}", e))?;
                if let Some(f) = g.get_mut(&flow_id) {
                    f.refresh_count += 1;
                    f.refresh_count > MAX_QR_REFRESH
                } else {
                    true
                }
            };
            if over_limit {
                let _ = state.flows.lock().map(|mut g| g.remove(&flow_id));
                return Ok(QrPollResult {
                    flow_id, platform: "weixin".into(), status: "error".into(),
                    token: None, bot_id: None, base_url: None,
                    qr_image: None, error: Some("QR expired too many times".into()),
                    message: Some("二维码已过期多次，请重新开始".into()),
                    initiator_anchor: None,
                });
            }
            // 刷新 QR
            let refresh_url = format!(
                "{}/ilink/bot/get_bot_qrcode?bot_type={}",
                ILINK_DEFAULT_BASE,
                urlencoding::encode(ILINK_BOT_TYPE)
            );
            let client = SHARED_HTTP_CLIENT.clone();
            let refresh_resp = client.get(&refresh_url).send().await;
            if let Ok(r) = refresh_resp {
                if r.status().is_success() {
                    if let Ok(parsed) = r.json::<IlinkQrCodeResponse>().await {
                        if let (Some(qrcode), Some(qr_image)) = (parsed.qrcode, parsed.qrcode_img_content) {
                            if !qrcode.is_empty() && !qr_image.is_empty() {
                                let mut g = state.flows.lock().map_err(|e| format!("qr state lock: {}", e))?;
                                if let Some(f) = g.get_mut(&flow_id) {
                                    f.qr_token = qrcode;
                                    f.qr_image = qr_image.clone();
                                    f.expires_at = Instant::now() + Duration::from_secs(QR_SESSION_TIMEOUT_SECS);
                                }
                                return Ok(QrPollResult {
                                    flow_id, platform: "weixin".into(), status: "expired".into(),
                                    token: None, bot_id: None, base_url: None,
                                    qr_image: Some(qr_image), error: None,
                                    message: Some("二维码已刷新".into()),
                                    initiator_anchor: None,
                                });
                            }
                        }
                    }
                }
            }
            let _ = state.flows.lock().map(|mut g| g.remove(&flow_id));
            Ok(QrPollResult {
                flow_id, platform: "weixin".into(), status: "error".into(),
                token: None, bot_id: None, base_url: None,
                qr_image: None, error: Some("QR refresh failed".into()),
                message: Some("二维码刷新失败，请重新开始".into()),
                initiator_anchor: None,
            })
        }
        _ => Ok(QrPollResult {
            flow_id, platform: "weixin".into(), status: "pending".into(),
            token: None, bot_id: None, base_url: None,
            qr_image: None, error: None, message: Some(st.to_string()),
            initiator_anchor: None,
        }),
    }
}

// -----------------------------------------------------------------------
// QQ Bot QR 实现 (NTQQ 协议)
// -----------------------------------------------------------------------

/// QQ ptlogin2 只接受浏览器 UA，自定义 UA 会被直接拒绝返回 403。
const QQ_BROWSER_UA: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

/// NTQQ 协议的 hash33 算法，用于从 qrsig 计算 ptqrtoken。
/// 抄自 Lagrange.Core: hash += (hash << 5) + s[i]，最后 & 0x7FFFFFFF。
fn hash33(s: &str) -> u32 {
    let mut hash: i32 = 0;
    for b in s.bytes() {
        hash = hash.wrapping_add(hash.wrapping_shl(5).wrapping_add(b as i32));
    }
    (hash & 0x7FFFFFFF) as u32
}

async fn begin_qqbot_qr(state: &State<'_, QrLoginState>) -> Result<QrBeginResult, String> {
    // NTQQ QR 登录: GET https://ssl.ptlogin2.qq.com/ptqrshow 获取 QR 图片
    let url = "https://ssl.ptlogin2.qq.com/ptqrshow?appid=549000912&e=2&l=M&s=3&d=72&v=4&t=0.1";
    let client = SHARED_HTTP_CLIENT.clone();
    let resp = client
        .get(url)
        .header("User-Agent", QQ_BROWSER_UA)
        .header("Referer", "https://xui.ptlogin2.qq.com/")
        .send()
        .await
        .map_err(|e| format!("QQ扫码: {}", format_reqwest_error(&e)))?;
    let status = resp.status();
    if !status.is_success() {
        let body = resp.text().await.unwrap_or_default();
        return Err(format!("QQ扫码 HTTP {}: {}", status, &sanitize_sensitive_fields(&body).chars().take(200).collect::<String>()));
    }
    // 获取 QR 图片 bytes,转 base64 data URL
    // 注意：先提取 headers（content_type + qrsig），再调 resp.bytes()，
    // 因为 bytes() 会 take ownership of resp。
    let headers = resp.headers().clone();
    let content_type = headers
        .get("content-type")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("image/png")
        .to_string();
    let bytes = resp.bytes().await.map_err(|e| format!("QQ扫码读取图片失败: {}", e))?;
    let b64 = base64::engine::general_purpose::STANDARD.encode(&bytes);
    let qr_image = format!("data:{};base64,{}", content_type, b64);

    // 从 Set-Cookie 中提取 qrsig (用于后续轮询)
    let qrsig = headers
        .get_all("set-cookie")
        .iter()
        .filter_map(|v| v.to_str().ok())
        .filter_map(|s| {
            if s.starts_with("qrsig=") {
                Some(s.trim_start_matches("qrsig=").split(';').next().unwrap_or("").to_string())
            } else {
                None
            }
        })
        .next()
        .unwrap_or_default();

    let flow_id = new_flow_id();
    // 反劫持锚点：begin 阶段生成随机 nonce 存入 flow，completed 时回传前端。
    let initiator_anchor = uuid::Uuid::new_v4().to_string();
    let flow = QrLoginFlow {
        platform: "qqbot".into(),
        qr_token: qrsig,
        qr_image: qr_image.clone(),
        expires_at: Instant::now() + Duration::from_secs(QR_SESSION_TIMEOUT_SECS),
        refresh_count: 0,
        initiator_anchor,
    };
    {
        let mut g = state.flows.lock().map_err(|e| format!("qr state lock: {}", e))?;
        g.insert(flow_id.clone(), flow);
    }
    Ok(QrBeginResult {
        flow_id,
        platform: "qqbot".into(),
        status: "pending".into(),
        qr_image,
        qr_data: None,
        expires_at_ms: now_ms() as u64 + QR_SESSION_TIMEOUT_SECS * 1000,
        message: Some("请使用 QQ 扫码".into()),
    })
}

async fn poll_qqbot_qr(
    state: &State<'_, QrLoginState>,
    flow_id: String,
    flow: QrLoginFlow,
) -> Result<QrPollResult, String> {
    // NTQQ QR 轮询: GET https://ssl.ptlogin2.qq.com/ptqrlogin?...
    let ptqr_auth = &flow.qr_token;
    if ptqr_auth.is_empty() {
        let _ = state.flows.lock().map(|mut g| g.remove(&flow_id));
        return Ok(QrPollResult {
            flow_id, platform: "qqbot".into(), status: "error".into(),
            token: None, bot_id: None, base_url: None, qr_image: None,
            error: Some("qrsig missing".into()),
            message: Some("二维码无效，请重新开始".into()),
            initiator_anchor: None,
        });
    }

    // ptqrtoken 必须用 hash33(qrsig) 计算，写 0 会导致永远拿不到正确状态。
    let ptqrtoken = hash33(ptqr_auth);
    let url = format!(
        "https://ssl.ptlogin2.qq.com/ptqrlogin?u1=https%3A//qun.qq.com&ptqrtoken={}&ptredirect=0&h=1&t=1&g=1&from_ui=1&ptui_version=10000&ptui_requestkey=0&webp=0&qrsig={}",
        ptqrtoken,
        urlencoding::encode(ptqr_auth)
    );
    let client = SHARED_HTTP_CLIENT.clone();
    let resp = client
        .get(&url)
        .header("User-Agent", QQ_BROWSER_UA)
        .header("Referer", "https://xui.ptlogin2.qq.com/")
        .timeout(Duration::from_secs(36))
        .send()
        .await;

    let resp = match resp {
        Ok(r) => r,
        Err(e) => {
            if e.is_timeout() {
                return Ok(QrPollResult {
                    flow_id, platform: "qqbot".into(), status: "pending".into(),
                    token: None, bot_id: None, base_url: None, qr_image: None,
                    error: None, message: Some("waiting".into()),
                    initiator_anchor: None,
                });
            }
            return Ok(QrPollResult {
                flow_id, platform: "qqbot".into(), status: "error".into(),
                token: None, bot_id: None, base_url: None, qr_image: None,
                error: Some(format!("QQ扫码轮询: {}", format_reqwest_error(&e))),
                message: Some("轮询失败".into()),
                initiator_anchor: None,
            });
        }
    };

    let status_code = resp.status();
    if !status_code.is_success() {
        let body = resp.text().await.unwrap_or_default();
        let _ = state.flows.lock().map(|mut g| g.remove(&flow_id));
        return Ok(QrPollResult {
            flow_id, platform: "qqbot".into(), status: "error".into(),
            token: None, bot_id: None, base_url: None, qr_image: None,
            error: Some(format!("HTTP {}: {}", status_code, &sanitize_sensitive_fields(&body).chars().take(200).collect::<String>())),
            message: Some("轮询失败".into()),
            initiator_anchor: None,
        });
    }

    let text = resp.text().await.unwrap_or_default();
    // 解析 ptuiCB 回调: ptuiCB('code','status','redirect','flag','msg', 'uin')
    // code: 0=成功, 65=QR过期, 66=未扫描, 67=已扫描等待确认
    let code = parse_ptui_cb_code(&text);
    match code.as_str() {
        "0" => {
            // 登录成功,提取 uin
            let uin = parse_ptui_cb_uin(&text);
            let _ = state.flows.lock().map(|mut g| g.remove(&flow_id));
            Ok(QrPollResult {
                flow_id, platform: "qqbot".into(), status: "completed".into(),
                token: Some(uin.clone()), bot_id: Some(uin), base_url: None,
                qr_image: None, error: None,
                message: Some("QQ 扫码成功".into()),
                initiator_anchor: Some(flow.initiator_anchor.clone()),
            })
        }
        "65" => {
            // QR 过期
            let _ = state.flows.lock().map(|mut g| g.remove(&flow_id));
            Ok(QrPollResult {
                flow_id, platform: "qqbot".into(), status: "expired".into(),
                token: None, bot_id: None, base_url: None, qr_image: None,
                error: Some("QR expired".into()),
                message: Some("二维码已过期，请重新开始".into()),
                initiator_anchor: None,
            })
        }
        "66" => Ok(QrPollResult {
            flow_id, platform: "qqbot".into(), status: "pending".into(),
            token: None, bot_id: None, base_url: None, qr_image: None,
            error: None, message: Some("等待扫码".into()),
            initiator_anchor: None,
        }),
        "67" => Ok(QrPollResult {
            flow_id, platform: "qqbot".into(), status: "scanned".into(),
            token: None, bot_id: None, base_url: None, qr_image: None,
            error: None, message: Some("已扫描，请在手机上确认".into()),
            initiator_anchor: None,
        }),
        _ => Ok(QrPollResult {
            flow_id, platform: "qqbot".into(), status: "pending".into(),
            token: None, bot_id: None, base_url: None, qr_image: None,
            error: None, message: Some(format!("未知状态: {}", code)),
            initiator_anchor: None,
        }),
    }
}

/// 从 ptuiCB 回调中提取 code (第一个参数)。
fn parse_ptui_cb_code(text: &str) -> String {
    // ptuiCB('66','0','...','0','二维码未失效', '0')
    if let Some(start) = text.find("ptuiCB(") {
        let rest = &text[start + 7..];
        if let Some(end) = rest.find("')") {
            let args = &rest[..end + 1];
            // 提取第一个引号内的内容
            if let Some(q1) = args.find('\'') {
                if let Some(q2) = args[q1 + 1..].find('\'') {
                    return args[q1 + 1..q1 + 1 + q2].to_string();
                }
            }
        }
    }
    "unknown".to_string()
}

/// 从 ptuiCB 回调中提取 uin (第六个参数)。
fn parse_ptui_cb_uin(text: &str) -> String {
    // ptuiCB('0','0','...','0','登录成功', '123456789')
    if let Some(start) = text.find("ptuiCB(") {
        let rest = &text[start + 7..];
        // 找到最后一个引号对
        let mut last_quote_pair: Option<String> = None;
        let mut in_quote = false;
        let mut current = String::new();
        for ch in rest.chars() {
            if ch == '\'' {
                if in_quote {
                    last_quote_pair = Some(current.clone());
                    current.clear();
                    in_quote = false;
                } else {
                    in_quote = true;
                    current.clear();
                }
            } else if in_quote {
                current.push(ch);
            }
        }
        if let Some(uin) = last_quote_pair {
            if uin.chars().all(|c| c.is_ascii_digit()) && !uin.is_empty() {
                return uin;
            }
        }
    }
    String::new()
}

// -----------------------------------------------------------------------
// 企微智能机器人扫码绑定 (aibot_subscribe 协议)
// 抄自 @wecom/wecom-openclaw-cli (ISC) dist/utils/qrcode.js
// 流程: GET /ai/qc/generate → 用户企微扫码 → GET /ai/qc/query_result
// -----------------------------------------------------------------------

async fn begin_wecom_qr(state: &State<'_, QrLoginState>) -> Result<QrBeginResult, String> {
    // 企微智能机器人扫码绑定 (aibot_subscribe 协议)。
    // 抄自 @wecom/wecom-openclaw-cli (ISC) dist/utils/qrcode.js::fetchQRCode()。
    // 流程: GET /ai/qc/generate → {scode, auth_url} → 用户企微扫码 → 轮询 query_result
    let base = ImChannelKind::WeCom
        .wecom_qr_generate_url()
        .unwrap_or("https://work.weixin.qq.com/ai/qc/generate");
    let url = format!("{}?source=wecom-cli&plat={}", base, wecom_plat_code());
    let client = SHARED_HTTP_CLIENT.clone();
    let resp = client.get(&url).send().await.map_err(|e| {
        format!("企微扫码: {}", format_reqwest_error(&e))
    })?;
    let status = resp.status();
    if !status.is_success() {
        let body = resp.text().await.unwrap_or_default();
        return Err(format!(
            "企微扫码 HTTP {}: {}",
            status,
            &sanitize_sensitive_fields(&body).chars().take(200).collect::<String>()
        ));
    }
    let parsed: WecomQrGenerateResponse = resp.json().await.map_err(|e| {
        format!("企微扫码响应解析失败: {}", e)
    })?;
    let data = parsed
        .data
        .ok_or_else(|| "企微扫码: missing data in response".to_string())?;
    let scode = data
        .scode
        .filter(|s| !s.is_empty())
        .ok_or_else(|| "企微扫码: missing scode".to_string())?;
    let auth_url = data
        .auth_url
        .filter(|s| !s.is_empty())
        .ok_or_else(|| "企微扫码: missing auth_url".to_string())?;

    let flow_id = new_flow_id();
    // 反劫持锚点：begin 阶段生成随机 nonce，completed 时回传前端做二次确认。
    let initiator_anchor = uuid::Uuid::new_v4().to_string();
    let flow = QrLoginFlow {
        platform: "wecom".into(),
        qr_token: scode, // 存 scode 用于轮询
        qr_image: auth_url.clone(),
        expires_at: Instant::now() + Duration::from_secs(QR_SESSION_TIMEOUT_SECS),
        refresh_count: 0,
        initiator_anchor,
    };
    {
        let mut g = state.flows.lock().map_err(|e| format!("qr state lock: {}", e))?;
        g.insert(flow_id.clone(), flow);
    }
    Ok(QrBeginResult {
        flow_id,
        platform: "wecom".into(),
        status: "pending".into(),
        // auth_url 是二维码内容字符串，QrLoginModal 会用 QRCodeSVG 渲染
        qr_image: auth_url,
        qr_data: None,
        expires_at_ms: now_ms() as u64 + QR_SESSION_TIMEOUT_SECS * 1000,
        message: Some("请使用企业微信扫码".into()),
    })
}

async fn poll_wecom_qr(
    state: &State<'_, QrLoginState>,
    flow_id: String,
    flow: QrLoginFlow,
) -> Result<QrPollResult, String> {
    // 企微智能机器人扫码轮询。
    // 抄自 @wecom/wecom-openclaw-cli (ISC) dist/utils/qrcode.js::pollResult()。
    // GET /ai/qc/query_result?scode=... → {status:"success", bot_info:{botid, secret}}
    let scode = &flow.qr_token;
    if scode.is_empty() {
        let _ = state.flows.lock().map(|mut g| g.remove(&flow_id));
        return Ok(QrPollResult {
            flow_id,
            platform: "wecom".into(),
            status: "error".into(),
            token: None, bot_id: None, base_url: None,
            qr_image: None,
            error: Some("企微扫码: scode 为空（旧版 flow），请重新扫码".into()),
            message: Some("请重新扫码".into()),
            initiator_anchor: None,
        });
    }
    let base = ImChannelKind::WeCom
        .wecom_qr_query_url()
        .unwrap_or("https://work.weixin.qq.com/ai/qc/query_result");
    let url = format!("{}?scode={}", base, urlencoding::encode(scode));
    let client = SHARED_HTTP_CLIENT.clone();
    let resp = client
        .get(&url)
        .timeout(Duration::from_secs(10))
        .send()
        .await;

    let resp = match resp {
        Ok(r) => r,
        Err(e) => {
            if e.is_timeout() {
                return Ok(QrPollResult {
                    flow_id, platform: "wecom".into(), status: "pending".into(),
                    token: None, bot_id: None, base_url: None,
                    qr_image: None, error: None, message: Some("waiting".into()),
                    initiator_anchor: None,
                });
            }
            return Ok(QrPollResult {
                flow_id, platform: "wecom".into(), status: "error".into(),
                token: None, bot_id: None, base_url: None,
                qr_image: None,
                error: Some(format!("企微扫码轮询: {}", format_reqwest_error(&e))),
                message: Some("轮询失败".into()),
                initiator_anchor: None,
            });
        }
    };

    let status_code = resp.status();
    if !status_code.is_success() {
        let body = resp.text().await.unwrap_or_default();
        let _ = state.flows.lock().map(|mut g| g.remove(&flow_id));
        return Ok(QrPollResult {
            flow_id, platform: "wecom".into(), status: "error".into(),
            token: None, bot_id: None, base_url: None,
            qr_image: None,
            error: Some(format!(
                "HTTP {}: {}",
                status_code,
                &sanitize_sensitive_fields(&body).chars().take(200).collect::<String>()
            )),
            message: Some("轮询失败".into()),
            initiator_anchor: None,
        });
    }

    let parsed: WecomQrQueryResponse = resp.json().await.map_err(|e| {
        format!("企微扫码状态解析失败: {}", e)
    })?;
    let data = match parsed.data {
        Some(d) => d,
        None => {
            return Ok(QrPollResult {
                flow_id, platform: "wecom".into(), status: "pending".into(),
                token: None, bot_id: None, base_url: None,
                qr_image: None, error: None, message: Some("等待扫码".into()),
                initiator_anchor: None,
            });
        }
    };
    let st = data.status.as_deref().unwrap_or("");
    match st {
        "success" => {
            let bot_info = data
                .bot_info
                .ok_or_else(|| "企微扫码: success but missing bot_info".to_string())?;
            let botid = bot_info
                .botid
                .filter(|s| !s.is_empty())
                .ok_or_else(|| "企微扫码: missing botid".to_string())?;
            let secret = bot_info
                .secret
                .filter(|s| !s.is_empty())
                .ok_or_else(|| "企微扫码: missing secret".to_string())?;
            // 扫码成功，移除 flow
            let _ = state.flows.lock().map(|mut g| g.remove(&flow_id));
            Ok(QrPollResult {
                flow_id,
                platform: "wecom".into(),
                status: "completed".into(),
                // token = bot_secret (前端 completed 分支会把 token 存入 provider.secret)
                token: Some(secret),
                bot_id: Some(botid),
                base_url: None,
                qr_image: None,
                error: None,
                message: Some("扫码成功，Bot ID 和 Secret 已自动获取".into()),
                initiator_anchor: Some(flow.initiator_anchor),
            })
        }
        "scanned" => Ok(QrPollResult {
            flow_id, platform: "wecom".into(), status: "scanned".into(),
            token: None, bot_id: None, base_url: None,
            qr_image: None, error: None,
            message: Some("已扫描，请在手机上确认".into()),
            initiator_anchor: None,
        }),
        _ => Ok(QrPollResult {
            flow_id, platform: "wecom".into(), status: "pending".into(),
            token: None, bot_id: None, base_url: None,
            qr_image: None, error: None, message: Some("等待扫码".into()),
            initiator_anchor: None,
        }),
    }
}
