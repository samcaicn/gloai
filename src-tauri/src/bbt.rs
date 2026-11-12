use serde_json::{json, Value};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// 全局共享 HTTP client（连接池复用，避免每次请求重做 TLS 握手）。
/// 普通接口短超时：卡死快速失败，不傻等 30 秒。
static HTTP: OnceLock<reqwest::Client> = OnceLock::new();
/// AI 生成类接口专用长超时 client：`chat/send` 会同步等服务端调 LLM 生成
/// autoReply，实测 10s 会被掐断（表现为「微信收到消息但不回复」）。
/// 实测后端尾延迟偶发 >60s（2026-11-08 十轮自测中多轮首请求 70~127s 才返回），
/// 这里放宽到 120s，给生成排队留足余量，避免误判超时触发无谓重试/失败。
static HTTP_SLOW: OnceLock<reqwest::Client> = OnceLock::new();

fn http() -> &'static reqwest::Client {
    HTTP.get_or_init(|| {
        reqwest::Client::builder()
            .timeout(Duration::from_secs(10))
            .connect_timeout(Duration::from_secs(5))
            .pool_idle_timeout(Duration::from_secs(30))
            .tcp_keepalive(Duration::from_secs(30))
            .user_agent("bbt-zb-tauri/1.0")
            .build()
            .unwrap_or_else(|_| reqwest::Client::new())
    })
}

fn http_slow() -> &'static reqwest::Client {
    HTTP_SLOW.get_or_init(|| {
        reqwest::Client::builder()
            .timeout(Duration::from_secs(120))
            .connect_timeout(Duration::from_secs(5))
            .pool_idle_timeout(Duration::from_secs(30))
            .tcp_keepalive(Duration::from_secs(30))
            .user_agent("bbt-zb-tauri/1.0")
            .build()
            .unwrap_or_else(|_| reqwest::Client::new())
    })
}

/// 会触发服务端 AI 推理 / PC 端聊天的 action 需要长超时（含未来新增的 PC 专用接口）。
fn needs_long_timeout(action: &str) -> bool {
    action == "chat/send"
        || action == "member/aiOcr"
        || action.starts_with("pc/")
        || action.starts_with("pcChat")
        || action.starts_with("ai/")
        || action.contains("aiChat")
        || action.contains("aiReply")
}

/// bbt-zb 连接信息（仅存于内存；token/账号由前端并入本地 config.json 持久化）。
#[derive(Default, Clone)]
pub struct BbtConnection {
    pub base_url: String, // 例如 https://bbt-zb.pages.dev
    pub token: Option<String>,
    pub telephone: Option<String>,
    /// 持久化自 config.json 的登录密码（仅本地明文，用于 token 失效/重启后自动重登；不回传前端）。
    pub password: Option<String>,
}

impl BbtConnection {
    fn api_url(&self) -> String {
        format!("{}/api", self.base_url.trim_end_matches('/'))
    }
}

// ============================================================================
//  bbt-zb 真实接口契约（2026-11-02 由官方前端 pages/*.js 反查 + 实打 /api 验证）
//
//  * 单端点 action 派发：POST {base}/api
//    Content-Type: application/x-www-form-urlencoded
//    表单字段: action=<action 名>  token=<登录token，未登录留空>
//              condition=<JSON 字符串，动作参数>
//  * 响应: {"code":200,"message":"ok","result":{"status":bool,"msg":"...","data":...}}
//  * 会话失效: result.status==false 且 msg=="未登录" 或 msg 含 "登录已失效"
//  * 登录: action=account/login, condition={telephone, password}
//          -> result.data = { token, ...账号信息(role 等) }
//  * 聊天参数: chat/configGet 拉取 / chat/configSave 推送（字段见 CONFIG_KEYS）
// ============================================================================

/// 推送到 bbt-zb 的配置键（与 chat/configSave 的 condition 一一对应）。
pub const CONFIG_KEYS: &[&str] = &[
    "aiEnabled",
    "dailyLimit",
    "onlineStatus",
    "displayName",
    "personaPrompt",
    "chatPurpose",
    "faqMarkdown",
];

// ============================================================================
//  服务端「写故障」熔断（2026-11-08 实测：D1 当日写入配额打满后，
//  account/login / chat/send 等写操作 100% 返回「服务器繁忙」，读操作全部正常）
//
//  熔断目的不是修后端，而是避免故障期每条客户消息都去打一次注定失败的写接口：
//  既省掉无谓的 120s 等待与重试，也不给已经打满的配额继续添堵。
//  冷却到期自动放行一次探针；探针成功立即解除，仍失败则退避翻倍（60→300s 封顶）。
// ============================================================================

/// 错误文案里的固定标记：ai_gateway / autoreply 据此快速失败或走兜底回复。
pub const FAULT_TAG: &str = "服务端写入故障";

struct FaultState {
    until_ms: i64,
    reason: String,
    backoff_sec: i64,
}

static FAULT: OnceLock<Mutex<FaultState>> = OnceLock::new();

fn fault() -> &'static Mutex<FaultState> {
    FAULT.get_or_init(|| {
        Mutex::new(FaultState {
            until_ms: 0,
            reason: String::new(),
            backoff_sec: 0,
        })
    })
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// 只在「明确的写类 action」上熔断，读接口（订单 / 会话 / 配额）永远放行。
fn is_write_action(action: &str) -> bool {
    const WRITES: &[&str] = &[
        "account/login",
        "account/register",
        "chat/send",
        "chat/configSave",
        "chat/configSet",
        "pc/create",
        "member/aiOcr",
        "member/bind",
    ];
    let lower = action.to_ascii_lowercase();
    WRITES.contains(&action)
        || lower.ends_with("save")
        || lower.ends_with("/create")
        || lower.ends_with("/send")
}

/// 判定业务错误是不是「服务端写不了」而不是凭据/权限/参数问题。
/// 保守匹配：只认实测出现的繁忙类文案及数据库配额关键字，避免把「密码错误」熔断掉。
pub fn is_backend_fault_msg(msg: &str) -> bool {
    msg.contains("服务器繁忙")
        || msg.contains("系统繁忙")
        || msg.contains("繁忙")
        || msg.contains("数据库")
        || msg.contains("写入配额")
        || msg.contains("D1")
        || msg.contains("quota exceeded")
        || msg.contains("1015")
}

fn mark_fault(msg: &str) {
    let mut f = fault().lock().unwrap();
    f.backoff_sec = if f.backoff_sec <= 0 {
        60
    } else {
        (f.backoff_sec * 2).min(300)
    };
    f.until_ms = now_ms() + f.backoff_sec * 1000;
    f.reason = msg.chars().take(120).collect();
}

fn clear_fault() {
    let mut f = fault().lock().unwrap();
    f.until_ms = 0;
    f.backoff_sec = 0;
    f.reason.clear();
}

/// 手动解除熔断（后端恢复后用户点「立即重试」，不必等满 5 分钟退避）。
pub fn reset_fault() {
    clear_fault();
}

fn fault_remaining_sec() -> Option<i64> {
    let f = fault().lock().unwrap();
    let left = (f.until_ms - now_ms()) / 1000;
    if left > 0 { Some(left) } else { None }
}

/// 供 ai_gateway::status() 输出，前端据此显示醒目告警。
pub fn fault_status() -> Value {
    let (active, left, reason) = {
        let f = fault().lock().unwrap();
        let left = ((f.until_ms - now_ms()) / 1000).max(0);
        (f.until_ms > now_ms(), left, f.reason.clone())
    };
    let hint = if active {
        format!(
            "{}：服务端写接口暂不可用（读接口正常），{} 秒后自动重试；常见于数据库当日写入配额打满，北京时间早 8 点配额重置后恢复",
            FAULT_TAG, left
        )
    } else {
        String::new()
    };
    json!({ "active": active, "remaining_sec": left, "reason": reason, "hint": hint })
}

/// 调用一次 bbt-zb API，返回 result 对象 {status, msg, data?}。
/// 会话失效 / result.status==false 时不报错、原样返回给上层判断。
pub async fn call(conn: &BbtConnection, action: &str, condition: &Value) -> Result<Value, String> {
    if conn.base_url.trim().is_empty() {
        return Err("未配置接口地址".into());
    }
    // 熔断期内写类请求直接快速失败，不再打后端（错误文案带 FAULT_TAG 供上层识别）。
    if is_write_action(action) {
        if let Some(left) = fault_remaining_sec() {
            let reason = fault().lock().unwrap().reason.clone();
            return Err(format!(
                "{}（熔断中，{} 秒后自动重试）：{}｜服务端读接口正常、写接口暂不可用，多为数据库当日写入配额打满；不是密码错误，请勿反复重试",
                FAULT_TAG, left, reason
            ));
        }
    }
    let token = conn.token.clone().unwrap_or_default();
    let cond_str = serde_json::to_string(condition).unwrap_or_else(|_| "{}".into());
    let form = [
        ("action", action.to_string()),
        ("token", token),
        ("condition", cond_str),
    ];
    let client = if needs_long_timeout(action) { http_slow() } else { http() };
    // 后端（Cloudflare Pages）在中国网络下偶发连接超时（实测约 40% 概率 5s connect timeout）。
    // 单次网络错误直接判失败会导致「消息收到但不回复」。对纯网络错误做有限重试 + 递增退避：
    // 后端瞬时抖动通常在第 2~3 次就通。重试安全——网络错误发生在请求尚未抵达服务端前
    // （connect 阶段），不会重复触发服务端写入；写故障熔断（fault）在上方已先行拦截。
    let max_retry: u32 = 4;
    let mut last_net_err = String::new();
    let mut resp_opt: Option<reqwest::Response> = None;
    for attempt in 0..=max_retry {
        match client.post(conn.api_url()).form(&form).send().await {
            Ok(r) => {
                resp_opt = Some(r);
                break;
            }
            Err(e) => {
                last_net_err = format!("网络错误: {}", e);
                if attempt < max_retry {
                    tokio::time::sleep(std::time::Duration::from_millis(400 * (attempt as u64 + 1))).await;
                    continue;
                }
            }
        }
    }
    let resp = match resp_opt {
        Some(r) => r,
        None => return Err(last_net_err),
    };

    let status = resp.status();
    let text = resp.text().await.unwrap_or_default();
    if !status.is_success() {
        let raw = format!("HTTP {}: {}", status, text.chars().take(200).collect::<String>());
        // 后端写挂时 Workers 也可能直接抛 5xx / 429 而不是返回「服务器繁忙」JSON，
        // 这类响应同样要熔断，否则每条客户消息都会白等一整个生成超时。
        if is_write_action(action) && (status.is_server_error() || status.as_u16() == 429) {
            mark_fault(&raw);
            return Err(format!("{}：{}｜服务端写接口报错，PC 端已自动熔断，稍后自动重试", FAULT_TAG, raw));
        }
        return Err(raw);
    }
    let v: Value = serde_json::from_str(&text)
        .map_err(|_| format!("响应非 JSON: {}", text.chars().take(200).collect::<String>()))?;
    let result = v
        .get("result")
        .cloned()
        .ok_or_else(|| "响应缺少 result 字段".to_string())?;

    // 写故障感知：status=false + 繁忙/数据库类文案 → 开启/续期熔断；
    // 写类 action 一旦成功 → 立即解除熔断并清零退避（后端恢复后无需等冷却）。
    let ok = result.get("status").and_then(|s| s.as_bool()) == Some(true);
    if ok {
        if is_write_action(action) {
            clear_fault();
        }
    } else if let Some(m) = result.get("msg").and_then(|m| m.as_str()) {
        if is_backend_fault_msg(m) {
            mark_fault(m);
        }
    }

    // 会话失效统一处理（对齐官方前端 core.js 的 onSessionExpired）
    let expired = result.get("status").and_then(|s| s.as_bool()) == Some(false)
        && result
            .get("msg")
            .and_then(|m| m.as_str())
            .map(|m| m == "未登录" || m.contains("登录已失效"))
            .unwrap_or(false);
    if expired {
        return Err("会话已失效，请重新登录".into());
    }
    Ok(result)
}

/// 从 result 中取出业务数据 result.data（status=false 时返回 Err(msg)）。
pub fn data_of(result: &Value) -> Result<Value, String> {
    let ok = result.get("status").and_then(|s| s.as_bool()).unwrap_or(false);
    if !ok {
        let msg = result
            .get("msg")
            .and_then(|m| m.as_str())
            .unwrap_or("操作失败")
            .to_string();
        return Err(msg);
    }
    Ok(result.get("data").cloned().unwrap_or(json!(null)))
}

/// 登录：action=account/login -> (token, 账号信息)
pub async fn login(
    conn: &BbtConnection,
    telephone: &str,
    password: &str,
) -> Result<(String, Value), String> {
    let result = call(
        conn,
        "account/login",
        &json!({ "telephone": telephone, "password": password }),
    )
    .await?;
    let data = match data_of(&result) {
        Ok(d) => d,
        Err(e) => {
            // 服务端写入故障（如 D1 当日写入配额打满）时，登录会稳定返回「服务器繁忙」，
            // 而同接口对不存在账号仍能返回「账号不存在」（读正常、写故障）。
            // 这里给出可操作的提示，避免用户误当密码错误反复重试。
            if is_backend_fault_msg(&e) {
                return Err(format!(
                    "{}：{}｜服务端登录落库失败（读正常、写故障，多为数据库当日写入配额打满，UTC 0 点/北京时间早上 8 点重置）。非密码错误，请勿反复重试，可稍后再试或到 Cloudflare 控制台查看 D1 用量。",
                    FAULT_TAG, e
                ));
            }
            return Err(e);
        }
    };
    let token = data
        .get("token")
        .and_then(|t| t.as_str())
        .ok_or_else(|| "响应中未找到 token".to_string())?
        .to_string();
    Ok((token, data))
}

/// 拉取聊天参数：action=chat/configGet -> data
pub async fn get_chat_config(conn: &BbtConnection) -> Result<Value, String> {
    let result = call(conn, "chat/configGet", &json!({})).await?;
    data_of(&result)
}

/// 构造 chat/configSave 的 condition（键过滤 + 类型归一，与官方前端提交形态一致）
pub fn build_config_save_condition(params: &Value) -> Value {
    let mut condition = json!({});
    for k in CONFIG_KEYS {
        if let Some(v) = params.get(*k) {
            condition[*k] = normalize_config_value(*k, v);
        }
    }
    condition
}

/// 推送聊天参数：action=chat/configSave
pub async fn save_chat_config(conn: &BbtConnection, params: &Value) -> Result<Value, String> {
    let condition = build_config_save_condition(params);
    let result = call(conn, "chat/configSave", &condition).await?;
    data_of(&result)
}

/// 类型归一（对齐官方前端提交形态）：aiEnabled 布尔→1/0，dailyLimit→字符串。
fn normalize_config_value(key: &str, v: &Value) -> Value {
    match key {
        "aiEnabled" => match v {
            Value::Bool(b) => json!(if *b { 1 } else { 0 }),
            Value::Number(n) => json!(if n.as_i64().unwrap_or(0) != 0 { 1 } else { 0 }),
            other => other.clone(),
        },
        "dailyLimit" => match v {
            Value::Number(n) => json!(n.to_string()),
            other => other.clone(),
        },
        _ => v.clone(),
    }
}

/// 探活：GET {base}/ 返回 200 即可达。
pub async fn health(conn: &BbtConnection) -> Result<bool, String> {
    if conn.base_url.trim().is_empty() {
        return Err("未配置接口地址".into());
    }
    let url = conn.base_url.trim_end_matches('/').to_string();
    let resp = http()
        .get(&url)
        .send()
        .await
        .map_err(|e| format!("网络错误: {}", e))?;
    Ok(resp.status().is_success())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn write_actions_are_scoped() {
        assert!(is_write_action("account/login"));
        assert!(is_write_action("chat/send"));
        assert!(is_write_action("chat/configSave"));
        assert!(is_write_action("pc/create"));
        assert!(is_write_action("leader/orderSave"));
        // 读接口绝不能被熔断挡住：订单/账本/会话/配额在写故障期间仍要能查
        assert!(!is_write_action("leader/orders"));
        assert!(!is_write_action("leader/ledger"));
        assert!(!is_write_action("chat/sessionList"));
        assert!(!is_write_action("chat/quota"));
        assert!(!is_write_action("chat/configGet"));
    }

    #[test]
    fn fault_msg_matches_only_backend_write_failures() {
        assert!(is_backend_fault_msg("服务器繁忙，请稍后重试"));
        assert!(is_backend_fault_msg("D1 写入配额已用尽"));
        assert!(is_backend_fault_msg("数据库写入失败"));
        assert!(!is_backend_fault_msg("密码错误"));
        assert!(!is_backend_fault_msg("账号不存在"));
        assert!(!is_backend_fault_msg("未登录"));
        assert!(!is_backend_fault_msg("无权限"));
    }

    #[test]
    fn needs_long_timeout_covers_ai_actions() {
        assert!(needs_long_timeout("chat/send"));
        assert!(needs_long_timeout("pc/create"));
        assert!(!needs_long_timeout("chat/sessionList"));
    }

    #[test]
    fn fault_state_machine_opens_backs_off_and_resets() {
        reset_fault();
        assert_eq!(fault_status()["active"].as_bool(), Some(false));
        assert!(fault_remaining_sec().is_none());

        mark_fault("服务器繁忙，请稍后重试");
        let s = fault_status();
        assert_eq!(s["active"].as_bool(), Some(true));
        assert!(s["remaining_sec"].as_i64().unwrap_or(0) >= 59);
        assert!(s["reason"].as_str().unwrap_or("").contains("繁忙"));
        let first = s["remaining_sec"].as_i64().unwrap_or(0);

        // 连续故障退避翻倍，不再以固定频率反复打已经打满的写接口
        mark_fault("服务器繁忙，请稍后重试");
        let second = fault_status()["remaining_sec"].as_i64().unwrap_or(0);
        assert!(second > first, "连续写故障应退避翻倍：{} -> {}", first, second);

        reset_fault();
        assert_eq!(fault_status()["active"].as_bool(), Some(false));
        assert!(fault_remaining_sec().is_none());
    }
}
