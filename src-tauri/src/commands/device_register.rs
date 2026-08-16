// Copyright (c) 2026 tupAI
//
// 设备注册 / 续期命令 - 两步架构：fingerprint 网关 + MCP 业务。
//
// 架构（攻击隔离设计，详见 CLAUDE.md "服务器 API 流程规则"）：
//   步骤1: POST /api/v1/client/fingerprint（匿名网关）→ 记指纹 + 签发 device_token
//   步骤2: MCP client.bind（带 token）→ 用 join_code 绑定租户 + 审批状态
//   步骤3: MCP client.bind.status（带 token）→ 轮询审批
//   续期: MCP client.renew（带 token）→ 启动时静默刷新
//
// 为什么 fingerprint 单独端点（不走 MCP）：
//   - 攻击隔离：fingerprint 是唯一匿名 + 重操作入口，单独端点可独立限流/熔断，
//     打爆它只影响新设备注册，不影响已注册设备的业务调用。
//   - MCP 端点所有 action 都要 Bearer token，无 token 请求在 auth 层秒拒（cheap），
//     攻击者拿不到 token 就只能打 fingerprint 这个轻量端点。
//
// 之前曾把 fingerprint 合并进 client.bind（匿名 bind），因 "单端点单往返" 简化，
// 但牺牲了攻击隔离——已于 2026-07 恢复两步架构。

use reqwest::Client;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::time::Duration;
use tauri::AppHandle;

const FINGERPRINT_URL: &str = "https://ai.tuptup.top/api/v1/client/fingerprint";
const MCP_V2_URL: &str = "https://ai.tuptup.top/api/v2/mcp";
/// fingerprint 端点超时：只记一行硬件指纹 + 签 token，本该 <500ms。
/// 给 30s 余量（含 connect + send + read body），失败快速触发重试。
const FINGERPRINT_TIMEOUT_SECS: u64 = 30;
/// client.bind 超时：涉及服务器端 join_code 查找 + 租户绑定 + 审批工作流创建。
/// 服务器 bind 正常应 <5s，但历史实测 34-77s 异常（已反馈服务器组排查）。
/// 45s 是合理上限：正常请求 <5s 完成，异常慢请求 45s 也足够返回。
/// 原先 90s 过长（3 次重试最长等 270s），降为 45s 后 3 次最长 135s。
const BIND_TIMEOUT_SECS: u64 = 45;
/// MCP v2 轻量查询超时（client.renew / client.bind.status）。
/// 这两个是纯查询，不涉及记录创建，30s 余量足够。
const MCP_TIMEOUT_SECS: u64 = 30;
/// TCP connect 阶段单独超时：5s 够用，失败快速触发重试，
/// 不会卡满总超时（避免代理未运行时让用户等满整个超时）。
const CONNECT_TIMEOUT_SECS: u64 = 5;
/// 设备注册 / 续期重试次数（含首次）=3：直连 → 直连 → 走系统代理。
/// 用户机器可能设了 HTTP_PROXY/HTTPS_PROXY 但代理未运行，第一/二次直连失败
/// 时第三次让 reqwest 自动读代理环境变量走代理（适用代理软件已开机的环境）。
const MAX_ATTEMPTS: u32 = 3;
/// 重试退避基数（ms），第 n 次重试前 sleep = RETRY_BASE_MS * 2^(n-1)。
/// 起始值 1000ms (1s) —— fingerprint 端点历史上报过 "operation timed out"
/// （reqwest 完整链：error sending request for url (https://ai.tuptup.top/api/v1/client/fingerprint)
///  -> operation timed out），首次失败后立即 0.3s 重试会撞同一波网络抖动；改为
/// 1s 起步 + 2^0/2^1/2^2 倍数退避 = 1s/2s/4s，给网络/服务器留喘息时间。
/// 同时用于 register_device 的 fingerprint+bind 两步，以及 renew_device_token。
const RETRY_BASE_MS: u64 = 1000;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RegisterResult {
    pub token: String,
    pub device_id: String,
    pub tenant_id: String,
    pub is_new_device: bool,
    /// 审批状态: "active" | "pending_approval" | "rejected" | "unknown"
    pub approval_status: String,
    /// 服务器返回的 next_step 原始值（透传,便于前端扩展）
    pub next_step: Option<String>,
    /// bind 请求 ID,用于轮询审批状态（pending 时非空）
    pub request_id: Option<String>,
}

/// client.bind.status 轮询结果
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BindStatusResult {
    pub status: String,
    /// 透传服务器原始响应
    pub raw: serde_json::Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RenewResult {
    pub token: Option<String>,
    pub valid: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RegisterError {
    pub code: String,
    pub message: String,
}

/// 获取平台和架构信息
fn get_platform_arch() -> (String, String) {
    let platform = if cfg!(target_os = "macos") {
        "darwin"
    } else if cfg!(target_os = "windows") {
        "windows"
    } else if cfg!(target_os = "linux") {
        "linux"
    } else {
        "unknown"
    };

    let arch = if cfg!(target_arch = "x86_64") {
        "x86_64"
    } else if cfg!(target_arch = "aarch64") {
        "aarch64"
    } else if cfg!(target_arch = "arm") {
        "arm"
    } else {
        "unknown"
    };

    (platform.to_string(), arch.to_string())
}

/// 格式化 reqwest 错误，遍历 std::error::Error::source() 链拼接完整错误原因。
/// reqwest::Error 默认 to_string() 只返回 "error sending request for url (...)"，丢失底层原因
/// （如 os error 10061 连接被拒绝），此处展开整条 source 链便于诊断。
fn format_reqwest_error(e: &reqwest::Error) -> String {
    let mut msg = format!("{}", e);
    let mut source = std::error::Error::source(e);
    while let Some(s) = source {
        msg.push_str(" -> ");
        msg.push_str(&format!("{}", s));
        source = s.source();
    }
    msg
}

/// 检测错误消息是否疑似「代理未运行」模式：
/// 用户机器可能设置了 Clash/V2Ray 环境变量 (HTTP_PROXY/HTTPS_PROXY) 但代理软件未启动，
/// reqwest 即便 .no_proxy() 也可能在某些环境下仍走代理（如 hyper-proxy feature 误启用）。
/// 命中关键词时返回中文提示，引导用户排查环境变量。
fn proxy_failure_hint(msg: &str) -> Option<&'static str> {
    let lower = msg.to_ascii_lowercase();
    let hit = lower.contains("tunnel")
        || lower.contains("proxy")
        || lower.contains("10061")
        || lower.contains("connection refused")
        || lower.contains("目标计算机积极拒绝")
        || lower.contains("no connection could be made");
    if hit {
        Some("网络连接失败：疑似本机代理环境变量 (HTTP_PROXY/HTTPS_PROXY) 指向未运行的代理（如 Clash 127.0.0.1:1082）。请在系统环境变量中清空 HTTP_PROXY/HTTPS_PROXY/ALL_PROXY，或启动代理软件后重试。")
    } else {
        None
    }
}

/// 启动时检测代理环境变量并 log::warn，便于诊断。
/// 不影响 reqwest 行为（已 .no_proxy() 强制直连），仅作日志提示。
fn warn_if_proxy_env_set() {
    for k in &["HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy"] {
        if let Ok(v) = std::env::var(k) {
            if !v.is_empty() {
                log::warn!(
                    "检测到 {}={} (reqwest 已 .no_proxy() 强制直连；如仍报网络错误，请清空此环境变量)",
                    k, v
                );
            }
        }
    }
}

/// 判定 RegisterError.code 是否为"网络层错误"（值得重试）。
/// 服务器侧错误（4xx/5xx/parse）由调用方决定如何处理，不重试。
fn is_network_error_code(code: &str) -> bool {
    matches!(code, "timeout" | "connect_failed" | "network_error")
}

/// 将审批/激活状态字符串归一化为 `"active"` / `"pending_approval"`。
///
/// 词汇与服务器 4-flow 设备生命周期对齐：
///   * `"active"` = 已审批通过（Flow 2/3），全量 MCP 放行
///   * `"pending_approval"` = 待审批 / 未绑定（Flow 1），只能调白名单 action
///
/// **服务器不会返回 `"rejected"`**：审批只有待审批/通过两态（CLAUDE.md 服务器 API 流程规则
/// 与 server sdk/client.py 注释核实）。任何被客户端当成的「拒绝」都是误读：
///   1. 未绑定设备调白名单外 action → `device_not_bound` → 归一化为 `pending_approval`（未绑定）
///   2. bind 请求失败（join_code 校验不通过等）→ 保留 token + `pending_approval`（可重试 bind）
/// 见 CLAUDE.md「服务器 API 流程规则」。
///
/// 用单词 token 精确匹配，**不**用 `contains` 子串匹配，修复安全隐患：
///   * `"inactive"` 不再因包含 `"active"` 被误判为已通过
///   * `"not_approved"` / `"unapproved"` / `"disapproved"` 不再因包含 `"approved"`
///     被误判为已通过（否则未审批设备会绕过 bind 步骤直接拿到 token）
///   * `"device_not_bound"` （含 "notbound"/unbound）→ `pending_approval`，不会误判
///
/// 服务器 **不会** 因审批返回 rejected，因此本函数极少触发 rejected 分支——它仅在服务器
/// 异常码（如 declined / disabled）出现时做防御性归一，正常情况下设备只会落
/// pending_approval 或 active。
///
/// 安全偏向：未知 / 空状态一律返回 `"pending_approval"`（保守不通过），仅在出现显式
/// approved/active/success token 且无负向修饰词时才返回 `"active"`。
fn normalize_activation_status(raw: &str) -> &'static str {
    let lower = raw.trim().to_ascii_lowercase();
    if lower.is_empty() {
        return "pending_approval";
    }
    let tokens: Vec<&str> = lower
        .split(|c: char| !c.is_alphanumeric())
        .filter(|t| !t.is_empty())
        .collect();
    // 1. 显式拒绝 / 吊销 / 禁用
    if tokens.iter().any(|t| matches!(*t,
        "rejected" | "reject" | "denied" | "deny" | "revoked" | "disapproved" | "disabled" | "no"
    )) {
        return "rejected";
    }
    // 2. 负向修饰词或未通过 / 待审批状态 → 保守 pending_approval
    if tokens.iter().any(|t| matches!(*t,
        "not" | "un" | "dis" | "non"
        | "inactive" | "unapproved" | "notapproved" | "unbound"
        | "pending" | "awaiting" | "waiting" | "approval"
    )) {
        return "pending_approval";
    }
    // 3. 已通过 token
    if tokens.iter().any(|t| matches!(*t,
        "approved" | "active" | "success" | "ok" | "bound" | "enabled" | "yes"
    )) {
        return "active";
    }
    // 4. 未知 → 保守 pending_approval
    "pending_approval"
}

/// 判定 fingerprint 响应的 activation 字段是否表明设备已审批通过。
///
/// 服务器对已存在设备返回 activation 状态（见 CLAUDE.md "服务器 API 流程规则"）。
/// 对于之前已审批通过的设备，activation 值为 "approved" / "active" 等，
/// 此时客户端应跳过 bind 步骤——服务器在 fingerprint 阶段已自动通过审批。
///
/// 委托 [`normalize_activation_status`] 做单词精确匹配（大小写无关），
/// 兼容 "approved" / "ACTIVE" / "approved_by_admin" 等变体。
fn is_activation_approved(activation: &str) -> bool {
    normalize_activation_status(activation) == "active"
}

/// 构建 reqwest HTTP client。
/// `use_proxy=false` → `.no_proxy()` 强制直连（ai.tuptup.top 是境内 IP，默认走直连最快）。
/// `use_proxy=true` → 不调 `.no_proxy()`，让 reqwest 读取 HTTP_PROXY/HTTPS_PROXY/ALL_PROXY 环境变量
///   走系统代理（用户代理软件运行中时，某些环境下直连反而不通，需要走代理）。
/// `timeout_secs` 由调用方按端点特性决定：fingerprint 轻量给 15s，MCP bind 重活给 30s。
fn build_http_client(use_proxy: bool, timeout_secs: u64) -> Result<Client, RegisterError> {
    let mut b = Client::builder()
        .connect_timeout(Duration::from_secs(CONNECT_TIMEOUT_SECS))
        .timeout(Duration::from_secs(timeout_secs))
        .user_agent(concat!("tupAI/", env!("CARGO_PKG_VERSION")));
    if !use_proxy {
        b = b.no_proxy();
    }
    b.build().map_err(|e| RegisterError {
        code: "client_build_failed".to_string(),
        message: format_reqwest_error(&e),
    })
}

/// 调用 MCP v2 API: POST /api/v2/mcp { action, params } + Bearer token。
/// 新架构下所有 MCP action 都要 token（client.bind / client.renew / client.bind.status）：
/// token 由前置的 /api/v1/client/fingerprint 签发，fingerprint 是唯一匿名入口。
/// `device_token` 为空字符串时省略 Authorization 头（仅防御性保留，正常流程不会触发）。
/// 返回解析后的 JSON 响应。
async fn mcp_v2_call(
    client: &Client,
    action: &str,
    params: &serde_json::Value,
    device_token: &str,
) -> Result<serde_json::Value, RegisterError> {
    let body = serde_json::json!({ "action": action, "params": params });
    let mut req = client
        .post(MCP_V2_URL)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json");
    if !device_token.is_empty() {
        req = req.header("Authorization", format!("Bearer {}", device_token));
    }
    let resp = req.json(&body).send().await.map_err(|e| {
        let detail = format_reqwest_error(&e);
        RegisterError {
            code: if e.is_timeout() { "timeout" }
                else if e.is_connect() { "connect_failed" }
                else { "network_error" }.to_string(),
            message: format!("MCP {} 请求失败: {}", action, detail),
        }
    })?;

    let status = resp.status();
    let text = resp.text().await.unwrap_or_default();
    if !status.is_success() {
        let code_str = serde_json::from_str::<serde_json::Value>(&text)
            .ok()
            .and_then(|j| j.get("error").or_else(|| j.get("code")).and_then(|v| v.as_str()).map(String::from))
            .unwrap_or_else(|| format!("http_{}", status.as_u16()));
        let msg_str = serde_json::from_str::<serde_json::Value>(&text)
            .ok()
            .and_then(|j| j.get("message").or_else(|| j.get("detail")).and_then(|v| v.as_str()).map(String::from))
            .unwrap_or_else(|| text.chars().take(300).collect());
        return Err(RegisterError {
            code: code_str,
            message: format!("MCP {} 失败 (HTTP {}): {}", action, status.as_u16(), msg_str),
        });
    }

    serde_json::from_str::<serde_json::Value>(&text).map_err(|e| RegisterError {
        code: "parse_error".to_string(),
        message: format!("MCP {} 响应解析失败: {}", action, e),
    })
}

/// fingerprint 端点返回结果
#[derive(Debug, Clone)]
struct FingerprintResult {
    device_token: String,
    device_id: String,
    tenant_id: String,
    is_new_device: bool,
    /// 服务器返回的设备激活/审批状态（已审批设备重新 fingerprint 时非空）。
    /// 服务器 fingerprint 响应包含 activation 字段（见 CLAUDE.md "服务器 API 流程规则"），
    /// 对于之前已审批通过的设备，服务器会在此字段返回 "approved"/"active" 等，
    /// 客户端据此跳过 bind 步骤（服务器已自动通过审批）。
    activation: String,
}

/// 调用 /api/v1/client/fingerprint（匿名网关）。
/// 提交硬件指纹 + 能力标签 + 客户端信息，服务器记一行设备记录并签发 device_token。
/// 这是唯一不需要 Bearer token 的 HTTP 调用——token 由本端点签发，
/// 之后所有 MCP action（client.bind / client.renew / 业务调用）都凭此 token 鉴权。
///
/// fingerprint 字段格式：hardware_id 的 SHA-256 hex（64 字符）。
/// 服务器 OpenAPI schema 明确要求 "64-char SHA-256 hex"，原始 UUID 会被拒为 invalid_fingerprint。
/// 用 SHA-256 而非原始 UUID 的原因：统一长度 + 避免泄露原始硬件标识符。
///
/// 能力标签 capability_tags 告诉服务器这台设备支持哪些识别层，
/// 服务器可据此决定分发哪些技能（如无 OCR 能力的设备不推 OCR 技能）。
async fn call_fingerprint(
    client: &Client,
    hw: &super::hardware_id::HardwareId,
    platform: &str,
    arch: &str,
) -> Result<FingerprintResult, RegisterError> {
    // SHA-256(software_id) → 64-char hex，服务器要求此格式
    let fingerprint_hex: String = {
        let mut hasher = Sha256::new();
        hasher.update(hw.hardware_id.as_bytes());
        hasher.finalize().iter().map(|b| format!("{:02x}", b)).collect()
    };

    let body = serde_json::json!({
        "fingerprint": fingerprint_hex,
        "capability_tags": ["cdp", "uia", "ocr", "vlm", "llm"],
        "client_info": {
            "platform": platform,
            "arch": arch,
            "brand": "tupai",
            "app_version": "2.0.0",
            "os_version": hw.os_version,
            "is_fallback": hw.is_fallback,
            "source": hw.source,
        },
    });

    let resp = client
        .post(FINGERPRINT_URL)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .json(&body)
        .send()
        .await
        .map_err(|e| {
            let detail = format_reqwest_error(&e);
            RegisterError {
                code: if e.is_timeout() { "timeout" }
                    else if e.is_connect() { "connect_failed" }
                    else { "network_error" }.to_string(),
                message: format!("fingerprint 请求失败: {}", detail),
            }
        })?;

    let status = resp.status();
    let text = resp.text().await.unwrap_or_default();
    if !status.is_success() {
        let code_str = serde_json::from_str::<serde_json::Value>(&text)
            .ok()
            .and_then(|j| j.get("error").or_else(|| j.get("code")).and_then(|v| v.as_str()).map(String::from))
            .unwrap_or_else(|| format!("http_{}", status.as_u16()));
        let msg_str = serde_json::from_str::<serde_json::Value>(&text)
            .ok()
            .and_then(|j| j.get("message").or_else(|| j.get("detail")).and_then(|v| v.as_str()).map(String::from))
            .unwrap_or_else(|| text.chars().take(300).collect());
        return Err(RegisterError {
            code: code_str,
            message: format!("fingerprint 失败 (HTTP {}): {}", status.as_u16(), msg_str),
        });
    }

    let v: serde_json::Value = serde_json::from_str(&text).map_err(|e| RegisterError {
        code: "parse_error".to_string(),
        message: format!("fingerprint 响应解析失败: {}", e),
    })?;

    // 服务器可能用信封 { ok, data: {...} } 或裸对象返回，两种都兼容
    let data = v.get("data").unwrap_or(&v);

    // 服务器用 success 字段显式标识成败：success=false 时 device_token 为 null，
    // error 字段给出失败原因（如 invalid_fingerprint / rate_limited / device_banned）。
    // 优先检查 success 字段，比靠 device_token 空判断更精确。
    let success = data.get("success").and_then(|v| v.as_bool()).unwrap_or(true);
    if !success {
        let err_code = data.get("error")
            .and_then(|v| v.as_str())
            .unwrap_or("fingerprint_failed")
            .to_string();
        return Err(RegisterError {
            code: err_code.clone(),
            message: format!("fingerprint 被服务器拒绝: {}", err_code),
        });
    }

    let device_token = data.get("device_token")
        .or_else(|| data.get("token"))
        .and_then(|v| v.as_str())
        .unwrap_or_default()
        .to_string();
    if device_token.is_empty() {
        return Err(RegisterError {
            code: "fingerprint_no_token".to_string(),
            message: "fingerprint 响应未包含 device_token".to_string(),
        });
    }

    // 解析 activation 字段：服务器对已存在设备返回激活/审批状态。
    // 兼容三种格式：字符串 / 对象 { status: "..." } / 布尔值。
    let activation = {
        let act = data.get("activation");
        if let Some(s) = act.and_then(|v| v.as_str()) {
            s.to_string()
        } else if let Some(s) = act.and_then(|v| v.get("status")).and_then(|v| v.as_str()) {
            s.to_string()
        } else if let Some(b) = act.and_then(|v| v.as_bool()) {
            if b { "active".to_string() } else { "inactive".to_string() }
        } else {
            String::new()
        }
    };

    Ok(FingerprintResult {
        device_token,
        device_id: data.get("device_id")
            .or_else(|| data.get("client_id"))
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .to_string(),
        tenant_id: data.get("tenant_id")
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .to_string(),
        is_new_device: data.get("is_new_device")
            .and_then(|v| v.as_bool())
            .unwrap_or(false),
        activation,
    })
}

/// renew_device_token 并发保护锁：串行化续期请求，避免并发刷新竞态。
/// 使用文件内 static 避免 State 结构改动影响 lib.rs（其他 agent 在改）。
static RENEW_LOCK: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

/// 设备注册 Tauri 命令（两步架构：fingerprint → bind，已审批设备跳过 bind）
///
/// 步骤1: POST /api/v1/client/fingerprint（匿名）→ 记指纹 + 签发 device_token
///   body: { fingerprint, capability_tags, client_info }
///   → { device_token, device_id, tenant_id?, is_new_device, activation }
///
/// 已审批设备快捷路径：如果 fingerprint 响应的 activation 字段表明设备已审批通过
///   （"approved"/"active" 等），服务器已在 fingerprint 阶段自动通过审批，
///   直接返回 approved + token，跳过 bind 步骤。此时 join_code 可为空。
///   场景：已审批设备 token 失效后重新注册，只需一次 fingerprint 往返。
///
///   步骤2（仅未审批设备）: POST /api/v2/mcp action=client.bind（带 Bearer token）→ 绑定租户 + 审批状态
///   body: { action: "client.bind", params: { join_code, device_token } }
///   Authorization: Bearer <device_token>
///   → { status: "pending_approval"|"approved", request_id }
///   注：服务器设备生命周期**没有** "rejected" 状态（详见本文件 normalize_activation_status
///   注释）。bind 请求失败返回 ok:false + error，那是绑定请求失败，非设备被拒。
///
/// join_code 格式：8 位数字字符串（服务器验证规则：must be 8 digits）。
/// 已审批设备（activation=approved）可省略 join_code；未审批设备如果 join_code 为空，
/// 返回 token + pending 让前端尝试 MCP 验证（MCP 成功即置绿，无需 join_code）。
/// device_token 必须同时放在 params 和 Authorization header 里（服务器校验 params 里的 device_token）。
///
/// token 来自步骤1的 fingerprint，bind 步骤不再签发 token（只返回审批状态）。
/// pending 时前端用 request_id 轮询 check_bind_status。
///
/// 网络策略：两步各自独立重试（直连 → 直连 → 走系统代理，3 次）。
/// fingerprint 失败则整体失败（没 token 走不到 bind）；bind 失败则保留 token
/// 但 approval_status=unknown（前端可让用户重试 bind 而不必重新 fingerprint）。
#[tauri::command]
pub async fn register_device(app: AppHandle, join_code: String) -> Result<RegisterResult, RegisterError> {
    // ── 前端输入格式校验 ──────────────────────────────────
    // join_code 允许为空：已审批设备重新注册时不需要 join_code，
    // 服务器在 fingerprint 阶段会通过 activation 字段表明已审批状态，
    // 此时跳过 bind 步骤直接返回 approved。
    // 如果设备未审批且 join_code 为空，返回 token + pending 让前端尝试 MCP 验证，
    // MCP 成功即置绿，无需 join_code。
    let code = join_code.trim();

    // Dev-mode: join_code 为 "dev" / "mock" 时直接返回假 token，跳过网络请求
    #[cfg(debug_assertions)]
    if matches!(code, "dev" | "mock") {
        let hw = super::hardware_id::get_hardware_id(app)
            .await
            .map_err(|e| RegisterError {
                code: "hardware_id_failed".to_string(),
                message: e,
            })?;
        let device_id = format!("dev-{}", &hw.hardware_id[..8.min(hw.hardware_id.len())]);
        log::info!("[dev-mode] register_device mocked: device_id={}", device_id);
        return Ok(RegisterResult {
            token: "dev-token-mock".to_string(),
            device_id,
            tenant_id: "dev-tenant".to_string(),
            is_new_device: true,
            approval_status: "active".to_string(),
            next_step: Some("complete".to_string()),
            request_id: None,
        });
    }

    warn_if_proxy_env_set();

    // 获取硬件 ID（fingerprint 提交给服务器识别设备）
    // get_hardware_id 现在带持久化缓存: 优先读缓存文件,
    // 缓存不存在时才运行系统命令, 命令失败时用缓存/uuid 兜底
    let hw = super::hardware_id::get_hardware_id(app)
        .await
        .map_err(|e| RegisterError {
            code: "hardware_id_failed".to_string(),
            message: e,
        })?;

    let (platform, arch) = get_platform_arch();

    // ── 步骤1: fingerprint 匿名网关签发 token ────────────
    let mut last_err: Option<RegisterError> = None;
    let mut fp: Option<FingerprintResult> = None;
    for attempt in 1..=MAX_ATTEMPTS {
        let use_proxy = attempt == MAX_ATTEMPTS;
        let client = match build_http_client(use_proxy, FINGERPRINT_TIMEOUT_SECS) {
            Ok(c) => c,
            Err(e) => return Err(e),
        };
        match call_fingerprint(&client, &hw, &platform, &arch).await {
            Ok(r) => { fp = Some(r); break; }
            Err(e) => {
                let mode = if use_proxy { "system-proxy" } else { "direct" };
                log::warn!("register_device fingerprint 第 {} 次尝试 ({}) 失败: {}", attempt, mode, e.message);
                last_err = Some(e);
                if attempt < MAX_ATTEMPTS {
                    let backoff = RETRY_BASE_MS * 2u64.pow(attempt - 1);
                    tokio::time::sleep(Duration::from_millis(backoff)).await;
                }
            }
        }
    }
    let fp = match fp {
        Some(r) => r,
        None => {
            let e = last_err.unwrap_or_else(|| RegisterError {
                code: "register_failed".to_string(),
                message: "所有尝试失败但未记录错误".to_string(),
            });
            let message = match proxy_failure_hint(&e.message) {
                Some(hint) => format!("{} [{}]", hint, e.message),
                None => e.message,
            };
            return Err(RegisterError { code: e.code, message });
        }
    };

    log::info!(
        "register_device fingerprint 完成: device_id={}, is_new_device={}, activation={}",
        fp.device_id, fp.is_new_device, fp.activation
    );

    // ── 已审批设备跳过 bind ──────────────────────────────
    // 服务器在 fingerprint 响应中通过 activation 字段返回设备激活/审批状态。
    // 对于之前已审批通过的设备，activation 为 "approved"/"active" 等，
    // 服务器已自动通过审批，无需再走 bind 步骤（省去 join_code 绑定 + 审批轮询）。
    // 这使得已审批设备重新注册时只需一次 fingerprint 往返即可拿到新 token。
    if is_activation_approved(&fp.activation) {
        log::info!(
            "register_device 设备已审批 (activation={}), 跳过 bind 步骤",
            fp.activation
        );
        return Ok(RegisterResult {
            token: fp.device_token,
            device_id: fp.device_id,
            tenant_id: fp.tenant_id,
            is_new_device: fp.is_new_device,
            approval_status: "active".to_string(),
            next_step: Some("complete".to_string()),
            request_id: None,
        });
    }

    // 未审批设备：fingerprint 已签发 token，返回 token + pending 让前端尝试 MCP 验证。
    //
    // 服务器通常会在 fingerprint 阶段直接签发可用 token——即使 activation 未标记
    // approved，token 也可能被 MCP 放行。前端拿到 token 后调 client.renew 验证：
    //   · MCP valid=true  → token 可用，无需 join_code 即可置绿
    //   · MCP valid=false → token 不可用，前端标记 pending，用户需输 join_code
    //
    // 只有用户主动输入 join_code 时才走下面的 client.bind 绑定租户流程。
    if code.is_empty() {
        log::info!(
            "register_device 设备未审批 (activation={}), join_code 为空，返回 token 让前端尝试 MCP 验证",
            fp.activation
        );
        return Ok(RegisterResult {
            token: fp.device_token,
            device_id: fp.device_id,
            tenant_id: fp.tenant_id,
            is_new_device: fp.is_new_device,
            approval_status: "pending_approval".to_string(),
            next_step: Some("mcp_verify".to_string()),
            request_id: None,
        });
    }

    // ── 步骤2: MCP client.bind 带 token 绑定租户 ─────────
    let bind_params = serde_json::json!({
        "join_code": code,
        "device_token": fp.device_token,
    });

    let mut last_err: Option<RegisterError> = None;
    let mut bind_resp: Option<serde_json::Value> = None;
    for attempt in 1..=MAX_ATTEMPTS {
        let use_proxy = attempt == MAX_ATTEMPTS;
        let client = match build_http_client(use_proxy, BIND_TIMEOUT_SECS) {
            Ok(c) => c,
            Err(e) => return Err(e),
        };
        match mcp_v2_call(&client, "client.bind", &bind_params, &fp.device_token).await {
            Ok(v) => { bind_resp = Some(v); break; }
            Err(e) => {
                let mode = if use_proxy { "system-proxy" } else { "direct" };
                log::warn!("register_device client.bind 第 {} 次尝试 ({}) 失败: {}", attempt, mode, e.message);
                last_err = Some(e);
                if attempt < MAX_ATTEMPTS {
                    let backoff = RETRY_BASE_MS * 2u64.pow(attempt - 1);
                    tokio::time::sleep(Duration::from_millis(backoff)).await;
                }
            }
        }
    }

    // bind 失败：token 已签发但租户绑定未完成。返回 token + approval_status=unknown，
    // 让前端可让用户重试 bind（不必重新 fingerprint）。
    let (approval_status, request_id, raw_status) = match bind_resp {
        Some(v) => {
            log::info!("register_device client.bind 响应: {}", v);
            // MCP 响应信封: { ok: bool, data: {...}, error: {code, message}|null, id: "..." }
            let ok = v.get("ok").and_then(|v| v.as_bool()).unwrap_or(false);
            if !ok {
                // 服务器拒绝 bind（如 join_code 格式不对 / 已过期 / 已用过）。
                //
                // 服务器 4-flow 设备生命周期**不包含** "rejected" 状态——审批只有
                // pending_approval（待审批/未绑定）与 approved（已通过）两态（见
                // server sdk/client.py 注释：该类注释中遗留的 "rejected" 是历史残留，
                // 服务端永不返回）。bind 的 `ok:false` 只是本次绑定请求失败
                // （join_code 未通过校验等），绝不是"设备被判拒绝"。
                //
                // 因此这里**绝不**把 approval_status 标成 "rejected"（否则前端会渲染
                // 「设备已被拒绝」误导用户）。正确做法：保留 token，把绑定失败当成
                // 未绑定（pending_approval），并把服务器返回的真实原因放进 next_step
                // 供前端提示，用户可直接重试 bind，无需重新 fingerprint。
                let err_msg = v.get("error")
                    .and_then(|e| e.get("message"))
                    .and_then(|v| v.as_str())
                    .unwrap_or("bind 失败");
                let err_code = v.get("error")
                    .and_then(|e| e.get("code"))
                    .and_then(|v| v.as_str())
                    .unwrap_or("bind_failed");
                log::warn!("register_device client.bind 服务器拒绝: {} ({})", err_msg, err_code);
                return Ok(RegisterResult {
                    token: fp.device_token,
                    device_id: fp.device_id,
                    tenant_id: fp.tenant_id,
                    is_new_device: fp.is_new_device,
                    approval_status: "pending_approval".to_string(),
                    next_step: Some(err_msg.to_string()),
                    request_id: None,
                });
            }
            // ok=true: 从 data 里取审批状态
            let data = v.get("data").unwrap_or(&v);
            let raw_status = data.get("status")
                .or_else(|| data.get("approval_status"))
                .and_then(|v| v.as_str())
                .unwrap_or("");
            let request_id = data.get("request_id")
                .or_else(|| data.get("bind_id"))
                .and_then(|v| v.as_str())
                .map(|s| s.to_string());
            let st = raw_status.to_ascii_lowercase();
            // bind 成功但服务器未返回显式状态 → 视为已通过（保持原行为：bind 成功即已绑）。
            // 其余状态委托 normalize_activation_status 做单词精确匹配，避免 "inactive" /
            // "not_approved" 等被 contains 子串误判为已通过。
            let approval_status = if st.is_empty() {
                "active".to_string()
            } else {
                normalize_activation_status(&st).to_string()
            };
            (approval_status, request_id, raw_status.to_string())
        }
        None => {
            let e = last_err.unwrap_or_else(|| RegisterError {
                code: "bind_failed".to_string(),
                message: "所有 bind 尝试失败但未记录错误".to_string(),
            });
            let message = match proxy_failure_hint(&e.message) {
                Some(hint) => format!("{} [{}]", hint, e.message),
                None => e.message,
            };
            // bind 网络失败：token 有效但绑定未完成。返回 token 让前端可重试 bind。
            log::warn!("register_device client.bind 三次失败，返回 token + approval=unknown: {}", message);
            ("unknown".to_string(), None, String::new())
        }
    };

    log::info!(
        "register_device 完成: approval_status={}, request_id={:?}, got_token={}",
        approval_status, request_id, !fp.device_token.is_empty()
    );

    Ok(RegisterResult {
        token: fp.device_token,
        device_id: fp.device_id,
        tenant_id: fp.tenant_id,
        is_new_device: fp.is_new_device,
        approval_status,
        next_step: Some(raw_status),
        request_id,
    })
}

/// 轮询 bind 审批状态 Tauri 命令
/// 服务器流程:
///   POST /api/v2/mcp action=client.bind.status { request_id, device_token } → { status: "approved" | "pending_approval" | "rejected" }
///   Authorization: Bearer <device_token>
/// 新架构下 bind.status 必须带 device_token（token 在步骤1 fingerprint 时已签发，
/// 轮询 bind.status 时一定有 token）。device_token 同时放在 params 和 Authorization header 里。
#[tauri::command]
pub async fn check_bind_status(
    request_id: String,
    device_token: String,
) -> Result<BindStatusResult, RegisterError> {
    if request_id.is_empty() {
        return Err(RegisterError {
            code: "invalid_args".to_string(),
            message: "request_id 不能为空".to_string(),
        });
    }

    let client = build_http_client(false, MCP_TIMEOUT_SECS)?;
    // device_token 可选：pending 审批阶段可能还没有 token，
    // 仅凭 request_id 即可查询状态。
    let mut params = serde_json::json!({
        "request_id": request_id,
    });
    if !device_token.is_empty() {
        params["device_token"] = serde_json::Value::String(device_token.clone());
    }

    let resp = mcp_v2_call(&client, "client.bind.status", &params, &device_token).await?;

    // MCP 信封: { ok, data, error, id } — 状态字段在 data 里
    let data = resp.get("data").unwrap_or(&resp);
    let status = if resp.get("ok").and_then(|v| v.as_bool()).unwrap_or(true) {
        data.get("status")
            .or_else(|| data.get("approval_status"))
            .and_then(|v| v.as_str())
            .unwrap_or("unknown")
            .to_string()
    } else {
        // ok=false: 服务器报错（如 request_id 无效），用 error.message 作为状态
        resp.get("error")
            .and_then(|e| e.get("message"))
            .and_then(|v| v.as_str())
            .unwrap_or("error")
            .to_string()
    };

    log::info!("check_bind_status: request_id={}, status={}", request_id, status);

    Ok(BindStatusResult {
        status,
        raw: resp,
    })
}

/// 设备 token 静默续期 Tauri 命令
/// 服务器流程：
///   POST /api/v2/mcp action=client.renew { device_token, hardware_id }
///     → { valid: true, device_token?: "new" }
///   或 → { valid: false, reason: "expired" | "revoked" | "unknown_device" }
/// 或 HTTP 401/403 同样判 valid=false。
///
/// 返回 RenewResult：
///   - valid=false → 前端清空 localStorage，标记设备失效，要求重新注册
///   - valid=true + token != existing → 写新 token 进 localStorage
///   - valid=true + token 相同/无 token → 保持不变
///
/// 网络错误 / 5xx / timeout 保守判 valid=true 保持现状（避免静默登出影响用户），
/// 下次 ensureDeviceToken 触发时再试。
#[tauri::command]
pub async fn renew_device_token(app: AppHandle, existing_token: String) -> Result<RenewResult, RegisterError> {
    // 并发保护：串行化续期请求，避免并发刷新竞态
    let _renew_guard = RENEW_LOCK.lock().await;

    if existing_token.is_empty() {
        return Ok(RenewResult { token: None, valid: false });
    }

    warn_if_proxy_env_set();

    let hw = super::hardware_id::get_hardware_id(app)
        .await
        .map_err(|e| RegisterError {
            code: "hardware_id_failed".to_string(),
            message: e,
        })?;

    // MCP v2 client.renew：仅传 device_token + hardware_id 让服务器识别。
    let renew_params = serde_json::json!({
        "device_token": existing_token,
        "hardware_id": hw.hardware_id,
    });

    // 重试策略：直连 → 直连 → 走系统代理（与 register_device 一致）。
    // 网络错误（timeout/connect/network）继续重试；服务器侧错误
    // （4xx/5xx/parse）立即返回 —— 4xx 说明 token 真无效，应判 valid=false
    // 让用户重新注册；5xx/parse 是服务器临时故障，保守判 valid=true。
    let mut last_network_err: Option<RegisterError> = None;
    let mut renew_resp: Option<serde_json::Value> = None;
    for attempt in 1..=MAX_ATTEMPTS {
        let use_proxy = attempt == MAX_ATTEMPTS;
        let client = match build_http_client(use_proxy, MCP_TIMEOUT_SECS) {
            Ok(c) => c,
            Err(e) => return Err(e),
        };
        match mcp_v2_call(&client, "client.renew", &renew_params, &existing_token).await {
            Ok(v) => { renew_resp = Some(v); break; }
            Err(e) => {
                let mode = if use_proxy { "system-proxy" } else { "direct" };
                log::warn!("renew_device_token client.renew 第 {} 次尝试 ({}) 失败: {}", attempt, mode, e.message);
                if is_network_error_code(&e.code) {
                    // 网络错误 → 继续重试
                    last_network_err = Some(e);
                    if attempt < MAX_ATTEMPTS {
                        let backoff = RETRY_BASE_MS * 2u64.pow(attempt - 1);
                        tokio::time::sleep(Duration::from_millis(backoff)).await;
                    }
                } else {
                    // 服务器侧错误（4xx/5xx/parse）→ 立即返回
                    // 4xx → token 真无效 → valid=false（让前端清空 localStorage）
                    // 5xx/parse → 服务器临时故障 → 保守 valid=true（保留现有 token）
                    let valid = !e.code.starts_with("http_4");
                    log::warn!(
                        "renew_device_token 服务器侧错误 (code={}), 立即返回 valid={}",
                        e.code, valid
                    );
                    return Ok(RenewResult { token: None, valid });
                }
            }
        }
    }
    let renew_resp = match renew_resp {
        Some(v) => v,
        None => {
            // 三次都网络错误（DNS / proxy 死 / 服务器全挂），保守判 valid=true，
            // 保留现有 token 不动用户。
            if let Some(e) = last_network_err {
                log::warn!("renew_device_token 三次网络错误，保守判 valid=true: {}", e.message);
            }
            return Ok(RenewResult { token: None, valid: true });
        }
    };

    // MCP 信封: { ok, data, error, id } — renew 结果在 data 里
    let renew_ok = renew_resp.get("ok").and_then(|v| v.as_bool()).unwrap_or(true);
    let renew_data = renew_resp.get("data").unwrap_or(&renew_resp);

    // ok=false: 服务器拒绝（如 token expired / revoked）
    if !renew_ok {
        let err_code = renew_resp.get("error")
            .and_then(|e| e.get("code"))
            .and_then(|v| v.as_str())
            .unwrap_or("");
        let err_msg = renew_resp.get("error")
            .and_then(|e| e.get("message"))
            .and_then(|v| v.as_str())
            .unwrap_or("renew failed");
        // expired/revoked/unknown_device → token 真失效 → valid=false
        let token_invalid = err_code.contains("expired")
            || err_code.contains("revoked")
            || err_code.contains("unknown")
            || err_code.contains("invalid");
        log::warn!("renew_device_token ok=false: code={} msg={} → valid={}", err_code, err_msg, !token_invalid);
        return Ok(RenewResult { token: None, valid: !token_invalid });
    }

    // ok=true: 从 data 里取 valid / device_token
    if let Some(valid) = renew_data.get("valid").and_then(|v| v.as_bool()) {
        if !valid {
            let reason = renew_data.get("reason")
                .or_else(|| renew_data.get("code"))
                .and_then(|v| v.as_str())
                .unwrap_or("invalid")
                .to_string();
            log::warn!("renew_device_token 服务器判 valid=false: reason={}", reason);
            return Ok(RenewResult { token: None, valid: false });
        }
    }

    // 服务器返回新 token
    let new_token = renew_data.get("device_token")
        .or_else(|| renew_data.get("token"))
        .and_then(|v| v.as_str())
        .map(|s| s.to_string());
    if let Some(t) = new_token {
        if t != existing_token {
            return Ok(RenewResult { token: Some(t), valid: true });
        }
    }

    // token 仍有效（valid=true 且 token 未变）
    Ok(RenewResult { token: None, valid: true })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn is_network_error_codes() {
        assert!(is_network_error_code("timeout"));
        assert!(is_network_error_code("connect_failed"));
        assert!(is_network_error_code("network_error"));
        assert!(!is_network_error_code("http_400"));
        assert!(!is_network_error_code("http_500"));
        assert!(!is_network_error_code("parse_error"));
        assert!(!is_network_error_code(""));
    }

    #[test]
    fn normalize_activation_status_exact_not_substring() {
        // 已通过：显式 approved/active/success token（含 _by_admin 等变体）
        assert_eq!(normalize_activation_status("approved"), "active");
        assert_eq!(normalize_activation_status("ACTIVE"), "active");
        assert_eq!(normalize_activation_status("approved_by_admin"), "active");
        assert_eq!(normalize_activation_status("success"), "active");
        assert_eq!(normalize_activation_status("Bound"), "active");
        // 关键安全回归：含 approved/active 子串但语义为"未通过"——不得判为 active
        assert_eq!(normalize_activation_status("inactive"), "pending_approval");
        assert_eq!(normalize_activation_status("not_approved"), "pending_approval");
        assert_eq!(normalize_activation_status("unapproved"), "pending_approval");
        assert_eq!(normalize_activation_status("dis-approved"), "pending_approval");
        assert_eq!(normalize_activation_status("not_active"), "pending_approval");
        // 显式拒绝
        assert_eq!(normalize_activation_status("rejected"), "rejected");
        assert_eq!(normalize_activation_status("denied"), "rejected");
        assert_eq!(normalize_activation_status("revoked"), "rejected");
        // 待审批
        assert_eq!(normalize_activation_status("pending"), "pending_approval");
        assert_eq!(normalize_activation_status("awaiting_approval"), "pending_approval");
        assert_eq!(normalize_activation_status("pending_approval"), "pending_approval");
        assert_eq!(normalize_activation_status("unbound"), "pending_approval");
        // 空 / 未知 → 保守 pending_approval
        assert_eq!(normalize_activation_status(""), "pending_approval");
        assert_eq!(normalize_activation_status("   "), "pending_approval");
        assert_eq!(normalize_activation_status("foobar"), "pending_approval");
        // is_activation_approved 仅 active 为 true
        assert!(is_activation_approved("approved"));
        assert!(is_activation_approved("active"));
        assert!(!is_activation_approved("inactive"));
        assert!(!is_activation_approved("not_approved"));
        assert!(!is_activation_approved(""));
        assert!(!is_activation_approved("pending"));
        assert!(!is_activation_approved("pending_approval"));
        assert!(!is_activation_approved("unbound"));
    }

    #[test]
    fn proxy_failure_hint_detects_keywords() {
        assert!(proxy_failure_hint("tunnel connection failed").is_some());
        assert!(proxy_failure_hint("proxy connect timeout").is_some());
        assert!(proxy_failure_hint("os error 10061").is_some());
        assert!(proxy_failure_hint("connection refused").is_some());
        assert!(proxy_failure_hint("目标计算机积极拒绝").is_some());
        assert!(proxy_failure_hint("no connection could be made").is_some());
        assert!(proxy_failure_hint("Connection refused").is_some());
        assert!(proxy_failure_hint("dns resolution failed").is_none());
        assert!(proxy_failure_hint("normal http error").is_none());
    }

    #[test]
    fn get_platform_arch_returns_known_values() {
        let (platform, arch) = get_platform_arch();
        assert!(
            ["windows", "macos", "linux", "unknown"].contains(&platform.as_str()),
            "unexpected platform: {}",
            platform
        );
        assert!(
            ["x86_64", "aarch64", "arm", "unknown"].contains(&arch.as_str()),
            "unexpected arch: {}",
            arch
        );
    }

    #[test]
    fn check_bind_status_rejects_empty_request_id() {
        let rt = tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async {
            let result = check_bind_status(String::new(), "tok".into()).await;
            assert!(result.is_err());
            let err = result.unwrap_err();
            assert_eq!(err.code, "invalid_args");
            assert!(err.message.contains("request_id"));
        });
    }

    #[test]
    fn check_bind_status_allows_empty_device_token() {
        // device_token 为空不应被拦截（pending 审批阶段还没 token）
        // 这里只验证参数校验层不拒绝空 token；
        // 实际 MCP 调用会因网络不通而失败，但那是预期行为。
        let rt = tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async {
            let result = check_bind_status("req-123".into(), String::new()).await;
            // 会因为 MCP 请求失败而返回错误，不是 invalid_args
            match result {
                Ok(_) => {}
                Err(e) => {
                    assert_ne!(
                        e.code, "invalid_args",
                        "empty device_token should not be rejected as invalid_args"
                    );
                }
            }
        });
    }

    #[test]
    fn register_result_serialization_camel_case() {
        let r = RegisterResult {
            token: "tok".into(),
            device_id: "dev".into(),
            tenant_id: "t".into(),
            is_new_device: true,
            approval_status: "pending_approval".into(),
            next_step: Some("awaiting".into()),
            request_id: Some("req-1".into()),
        };
        let json = serde_json::to_value(&r).unwrap();
        assert!(json.get("token").is_some());
        assert!(json.get("deviceId").is_some());
        assert!(json.get("tenantId").is_some());
        assert!(json.get("isNewDevice").is_some());
        assert!(json.get("approvalStatus").is_some());
        assert!(json.get("requestId").is_some());
        // 确保不是 snake_case
        assert!(json.get("device_id").is_none());
        assert!(json.get("approval_status").is_none());
    }

    #[test]
    fn renew_result_default_when_no_token() {
        let json = serde_json::json!({ "valid": true });
        let token = json.get("device_token")
            .or_else(|| json.get("token"))
            .and_then(|v| v.as_str())
            .map(|s| s.to_string());
        assert!(token.is_none());
    }
}