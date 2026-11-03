use serde_json::{json, Value};
use std::time::Duration;

/// bbt-zb 连接信息（仅存于内存；token/账号由前端并入本地 config.json 持久化）。
#[derive(Default, Clone)]
pub struct BbtConnection {
    pub base_url: String, // 例如 https://bbt-zb.pages.dev
    pub token: Option<String>,
    pub telephone: Option<String>,
}

impl BbtConnection {
    fn client() -> reqwest::Client {
        reqwest::Client::builder()
            .timeout(Duration::from_secs(30))
            .user_agent("bbt-zb-tauri/1.0")
            .build()
            .unwrap_or_else(|_| reqwest::Client::new())
    }

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

/// 调用一次 bbt-zb API，返回 result 对象 {status, msg, data?}。
/// 会话失效 / result.status==false 时不报错、原样返回给上层判断。
pub async fn call(conn: &BbtConnection, action: &str, condition: &Value) -> Result<Value, String> {
    if conn.base_url.trim().is_empty() {
        return Err("未配置接口地址".into());
    }
    let token = conn.token.clone().unwrap_or_default();
    let cond_str = serde_json::to_string(condition).unwrap_or_else(|_| "{}".into());
    let form = [
        ("action", action.to_string()),
        ("token", token),
        ("condition", cond_str),
    ];
    let resp = BbtConnection::client()
        .post(conn.api_url())
        .form(&form)
        .send()
        .await
        .map_err(|e| format!("网络错误: {}", e))?;

    let status = resp.status();
    let text = resp.text().await.unwrap_or_default();
    if !status.is_success() {
        return Err(format!("HTTP {}: {}", status, text));
    }
    let v: Value =
        serde_json::from_str(&text).map_err(|_| format!("响应非 JSON: {}", &text[..text.len().min(200)]))?;
    let result = v
        .get("result")
        .cloned()
        .ok_or_else(|| "响应缺少 result 字段".to_string())?;

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
    let data = data_of(&result)?;
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
    let resp = BbtConnection::client()
        .get(&url)
        .send()
        .await
        .map_err(|e| format!("网络错误: {}", e))?;
    Ok(resp.status().is_success())
}
