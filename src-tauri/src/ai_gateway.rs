//! PC 端统一 AI 消息网关（0.3.0）
//!
//! ## 业务模型（2026-11-04 线上实测确认，别再理解反了）
//!
//! bbt-zb 平台的「AI 代聊」是**团长配置**的：团员给团长发消息（`chat/send`），
//! 服务端用团长的人设 / 话术库 / FAQ 生成 `autoReply`（senderType='ai'）。
//!
//! PC 端的多个微信私聊 / 群聊 / iLink 渠道，就是**多个团员**的消息来源。
//! 不管有多少个渠道、多少个团员，消息都**只向同一个团长发**：
//! 全部汇聚到「当前登录账号 ↔ 团长」这唯一一个平台会话，由团长的 AI 统一回复，
//! 再按「渠道 ↔ 团员」对应关系**原路分发**回去。
//!
//! 因此：
//!   * **不需要配置任何团员账号信息**（手机号/密码）。PC 端用什么账号登录，
//!     就用那个账号的登录态发消息；该账号本身是已绑定团长的团员即可。
//!   * 「配置对应关系」界面（互动页 / 订单页）里的渠道与团员映射，用于**回发路由**
//!     和消息标记，不用于选择 AI 身份。
//!   * `sessionId` 实测可选（不带也能触发 AI），能取到就带上，取不到就裸发。
//!
//! ## 身份自适应
//!   * 当前登录 = 团员（role=member，sessionList 返回 leader/session）→ 直接可用。
//!   * 当前登录 = 团长（sessionList 返回 list）→ 平台拒绝 chat/send「无权限」；
//!     若旧版配置里还留着团员账号则静默兜底一次，否则给出可操作的明确提示。
//!   * token 过期 / 未登录 → 用 config.json 持久化的账号密码自动重登一次。
//!
//! ## 对外接口（Tauri commands，见 commands.rs）
//!   * `ai_gateway_status`  登录身份 / 绑定团长 / 配额 / 渠道对应关系 / 提示
//!   * `ai_gateway_send`    多渠道统一入口：向团长发消息 → AI 回复 →（可选）原路回发
//!   * `ai_gateway_logs`    网关日志

use crate::bbt::{self, BbtConnection};
use crate::state::AppState;
use serde_json::{json, Value};
use std::sync::{Arc, Mutex};
use tauri::{AppHandle, Emitter, Manager};

const SESSION_TTL_MS: i64 = 10 * 60 * 1000;
const IDENTITY_TTL_MS: i64 = 60 * 1000;
const LOG_MAX: usize = 200;

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// 一次「向团长发消息」的结果
#[derive(Debug, Clone)]
pub struct AiAnswer {
    pub reply: String,
    pub session_id: Option<Value>,
    pub quota_left: Option<i64>,
    pub auto: bool,
}

/// 登录身份快照
#[derive(Debug, Clone, Default)]
pub struct Identity {
    pub role: String,
    pub leader: Value,
    pub ai_enabled: Option<bool>,
    pub session_id: Option<Value>,
    pub quota: Value,
}

pub struct AiGatewayState {
    /// (sessionId 原始 JSON 值, 时间戳)：线上 sessionId 是数字，必须保留原始类型
    session: Mutex<Option<(Value, i64)>>,
    identity: Mutex<Option<(Identity, i64)>>,
    pub logs: Mutex<Vec<Value>>,
}

impl AiGatewayState {
    pub fn new() -> Self {
        AiGatewayState {
            session: Mutex::new(None),
            identity: Mutex::new(None),
            logs: Mutex::new(Vec::new()),
        }
    }

    pub fn invalidate(&self) {
        *self.session.lock().unwrap() = None;
        *self.identity.lock().unwrap() = None;
    }

    pub fn cache_session(&self, sid: &Value) {
        *self.session.lock().unwrap() = Some((sid.clone(), now_ms()));
    }
}

fn log(app: &AppHandle, level: &str, msg: &str, extra: Value) {
    let entry = json!({ "ts": now_ms(), "level": level, "msg": msg, "extra": extra });
    if let Some(st) = app.try_state::<Arc<AiGatewayState>>() {
        let mut g = st.logs.lock().unwrap();
        g.push(entry.clone());
        if g.len() > LOG_MAX {
            let drop = g.len() - LOG_MAX;
            g.drain(0..drop);
        }
    }
    let _ = app.emit("ai-gateway-event", &entry);
}

/// 从 sessionList / chat/send 的 data 里取 sessionId。
/// 兼容 {session:{sessionId}} / {session:{id}} / {sessionId} / {list[0].sessionId}，
/// 数字与字符串都保留原始类型（线上是数字）。
pub fn extract_session_id(data: &Value) -> Option<Value> {
    let norm = |v: &Value| -> Option<Value> {
        if let Some(n) = v.as_i64() {
            return Some(json!(n));
        }
        if let Some(s) = v.as_str() {
            let s = s.trim();
            if s.is_empty() {
                return None;
            }
            return Some(s.parse::<i64>().map(|n| json!(n)).unwrap_or_else(|_| json!(s)));
        }
        None
    };
    let sess = data.get("session");
    for c in [
        sess.and_then(|s| s.get("sessionId")),
        sess.and_then(|s| s.get("id")),
        data.get("sessionId"),
        data.get("list")
            .and_then(|l| l.as_array())
            .and_then(|l| l.first())
            .and_then(|s| s.get("sessionId")),
    ] {
        if let Some(v) = c.and_then(norm) {
            return Some(v);
        }
    }
    None
}

/// 当前登录连接（内存 token 优先；无 token 用持久化账号密码自动重登）
async fn connection(app: &AppHandle) -> Result<BbtConnection, String> {
    let state = app.try_state::<AppState>().ok_or("AppState 未初始化")?;
    let c = state.conn.lock().unwrap().clone();
    if c.base_url.trim().is_empty() {
        return Err("未配置 bbt-zb 接口地址".into());
    }
    if c.token.as_ref().map(|t| !t.trim().is_empty()).unwrap_or(false) {
        return Ok(c);
    }
    relogin(app).await
}

/// 用 config.json 里持久化的 telephone/password 重登（token 过期 / 无 token 时）
async fn relogin(app: &AppHandle) -> Result<BbtConnection, String> {
    let state = app.try_state::<AppState>().ok_or("AppState 未初始化")?;
    let (base, tel, pwd) = {
        let c = state.conn.lock().unwrap();
        let g = state.config.lock().unwrap();
        let saved = g.get("connection").cloned().unwrap_or(json!({}));
        (
            c.base_url.clone(),
            saved.get("telephone").and_then(|v| v.as_str()).unwrap_or("").to_string(),
            saved.get("password").and_then(|v| v.as_str()).unwrap_or("").to_string(),
        )
    };
    if base.trim().is_empty() {
        return Err("未配置 bbt-zb 接口地址".into());
    }
    if tel.is_empty() || pwd.is_empty() {
        return Err("登录态已过期：本机未保存账号密码，请在登录浮层重新登录一次".into());
    }
    let c = BbtConnection {
        base_url: base,
        token: None,
        telephone: Some(tel.clone()),
        password: Some(pwd.clone()),
    };
    let (token, _acct) = bbt::login(&c, &tel, &pwd).await?;
    let mut fresh = c.clone();
    fresh.token = Some(token);
    *state.conn.lock().unwrap() = fresh.clone();
    if let Some(st) = app.try_state::<Arc<AiGatewayState>>() {
        st.invalidate();
    }
    log(app, "info", "登录态已自动恢复", json!({}));
    Ok(fresh)
}

/// sessionId 缓存（取不到就返回 None，chat/send 实测不带也能触发 AI）
async fn session_of(app: &AppHandle, conn: &BbtConnection) -> Option<Value> {
    if let Some(st) = app.try_state::<Arc<AiGatewayState>>() {
        if let Some((sid, ts)) = st.session.lock().unwrap().clone() {
            if now_ms() - ts < SESSION_TTL_MS {
                return Some(sid);
            }
        }
    }
    let r = bbt::call(conn, "chat/sessionList", &json!({})).await.ok()?;
    let d = bbt::data_of(&r).ok()?;
    let sid = extract_session_id(&d)?;
    if let Some(st) = app.try_state::<Arc<AiGatewayState>>() {
        st.cache_session(&sid);
    }
    Some(sid)
}

/// 探测登录身份（缓存 1 分钟）。团员视角能拿到 leader + session + aiEnabled。
pub async fn identity(app: &AppHandle, refresh: bool) -> Result<Identity, String> {
    if !refresh {
        if let Some(st) = app.try_state::<Arc<AiGatewayState>>() {
            if let Some((id, ts)) = st.identity.lock().unwrap().clone() {
                if now_ms() - ts < IDENTITY_TTL_MS {
                    return Ok(id);
                }
            }
        }
    }
    let conn = connection(app).await?;
    let r = bbt::call(&conn, "chat/sessionList", &json!({})).await?;
    let d = bbt::data_of(&r)?;
    let leader = d.get("leader").cloned().unwrap_or(json!(null));
    let mut role = d.get("role").and_then(|v| v.as_str()).unwrap_or("").to_string();
    if role.is_empty() {
        role = if d.get("list").is_some() { "leader" } else { "unknown" }.into();
    }
    let ai_enabled = leader
        .get("aiEnabled")
        .and_then(|v| v.as_bool())
        .or_else(|| d.get("aiEnabled").and_then(|v| v.as_bool()));
    let session_id = extract_session_id(&d);
    if let Some(s) = &session_id {
        if let Some(st) = app.try_state::<Arc<AiGatewayState>>() {
            st.cache_session(s);
        }
    }
    let quota = bbt::call(&conn, "chat/quota", &json!({}))
        .await
        .ok()
        .and_then(|r| bbt::data_of(&r).ok())
        .unwrap_or(json!(null));
    let id = Identity { role, leader, ai_enabled, session_id, quota };
    if let Some(st) = app.try_state::<Arc<AiGatewayState>>() {
        *st.identity.lock().unwrap() = Some((id.clone(), now_ms()));
    }
    Ok(id)
}

/// 本地「渠道 ↔ 团员」对应关系统计：聊天对象列表（微信私聊/群聊）、
/// 已扫码的 iLink 账号、已绑定的团员映射。全部来自本机配置，不发平台请求。
fn local_channels(app: &AppHandle) -> Value {
    let (wx_private, wx_group, mapped) = {
        let Some(state) = app.try_state::<AppState>() else {
            return json!({});
        };
        let g = state.config.lock().unwrap();
        let list = g
            .get("autoreply")
            .and_then(|a| a.get("listen_list"))
            .and_then(|v| v.as_array())
            .cloned()
            .unwrap_or_default();
        let p = list.iter().filter(|e| e.get("kind").and_then(|v| v.as_str()) == Some("private")).count();
        let grp = list.iter().filter(|e| e.get("kind").and_then(|v| v.as_str()) == Some("group")).count();
        let map = g
            .get("memberqr")
            .and_then(|m| m.get("map"))
            .and_then(|v| v.as_object())
            .map(|m| m.len())
            .unwrap_or(0);
        (p, grp, map)
    };
    let ilink = app
        .try_state::<Arc<crate::ilink_session::SessionManager>>()
        .map(|m| m.list_accounts().len())
        .unwrap_or(0);
    json!({
        "wx_private": wx_private,
        "wx_group": wx_group,
        "ilink": ilink,
        "member": mapped,
        "mapped": mapped
    })
}

/// 网关状态（前端互动页 / 聊天配置页展示）
pub async fn status(app: &AppHandle) -> Value {
    let id = match identity(app, true).await {
        Ok(v) => v,
        Err(e) => {
            return json!({
                "ok": false, "logged_in": false, "role": "", "leader": null,
                "ai_enabled": null, "session_id": null, "quota": null,
                "channels": {}, "hint": e
            });
        }
    };
    let leader_name = id.leader.get("name").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let left = id.quota.get("left").and_then(|v| v.as_i64());
    let (hint, ok) = match id.role.as_str() {
        "member" => {
            if id.ai_enabled == Some(false) {
                ("当前登录为团员，但团长尚未开启 AI 代聊（后台「自动回复设置」里开启）".to_string(), false)
            } else if left == Some(0) {
                ("今日 AI 回复配额已用尽（每团员每日限额由团长设定，可在后台调高）".to_string(), false)
            } else {
                (format!(
                    "已绑定团长「{}」：所有微信私聊 / 群聊 / iLink 渠道的消息都会发往团长，由团长的 AI 统一回复后原路返回",
                    if leader_name.is_empty() { "—" } else { &leader_name }
                ), true)
            }
        }
        "leader" => (
            "当前登录的是团长账号。AI 代聊是「团员 → 团长」方向，团长账号无法向自己发消息；请用已绑定团长的团员账号登录 PC 端".into(),
            false,
        ),
        _ => ("无法确定登录身份，请确认账号已归属某个团长".into(), false),
    };
    // 渠道对应关系 = PC 端本地配置界面里配好的映射（不依赖平台 leader/* 接口：
    // 团员登录态下那些接口全部「无权限」，且对应关系本来就与身份无关）。
    let channels = local_channels(app);
    json!({
        "ok": ok,
        "logged_in": true,
        "role": id.role,
        "leader": id.leader,
        "ai_enabled": id.ai_enabled,
        "session_id": id.session_id,
        "quota": id.quota,
        "channels": channels,
        "hint": hint
    })
}

/// 静默兜底：旧版配置里若还留着团员账号（0.2.x 的 member_telephone/password），
/// 且当前登录是团长身份时，用它登录一次再发。新版本 UI 不再暴露、也不要求填写。
async fn legacy_member_conn(app: &AppHandle) -> Option<BbtConnection> {
    let state = app.try_state::<AppState>()?;
    let (base, tel, pwd) = {
        let c = state.conn.lock().unwrap();
        let g = state.config.lock().unwrap();
        let ar = g.get("autoreply").cloned().unwrap_or(json!({}));
        (
            c.base_url.clone(),
            ar.get("member_telephone").and_then(|v| v.as_str()).unwrap_or("").to_string(),
            ar.get("member_password").and_then(|v| v.as_str()).unwrap_or("").to_string(),
        )
    };
    if tel.is_empty() || pwd.is_empty() || base.is_empty() {
        return None;
    }
    let c = BbtConnection { base_url: base, token: None, telephone: Some(tel.clone()), password: Some(pwd.clone()) };
    let (token, _) = bbt::login(&c, &tel, &pwd).await.ok()?;
    let mut fresh = c.clone();
    fresh.token = Some(token);
    Some(fresh)
}

/// 核心：**所有渠道消息统一「向团长发消息」，拿团长 AI 回复**。
/// `route` 仅作日志标记（如 "wx|凯哥" / "ilink|bot_xxx"），不影响身份选择。
pub async fn ask_leader(app: &AppHandle, text: &str, route: &str) -> Result<AiAnswer, String> {
    let text = text.trim();
    if text.is_empty() {
        return Err("消息内容为空".into());
    }
    let mut conn = connection(app).await?;

    let mut cond = json!({ "content": text });
    if let Some(sid) = session_of(app, &conn).await {
        cond["sessionId"] = sid;
    }

    // chat/send：网络抖动（国内出口到 pages.dev）自动重试一次，避免客户消息丢失
    let mut result = bbt::call(&conn, "chat/send", &cond).await;
    if let Err(e) = &result {
        if e.contains("网络错误") || e.contains("timed out") || e.contains("timeout") {
            log(app, "info", &format!("网络抖动，重试一次：{}", e), json!({ "route": route }));
            tokio::time::sleep(std::time::Duration::from_millis(800)).await;
            result = bbt::call(&conn, "chat/send", &cond).await;
        }
    }
    // 登录态过期（平台返回 status=false + 「未登录/登录已过期/登录已失效」）→ 重登重试一次
    let expired = result
        .as_ref()
        .ok()
        .map(|r| {
            r.get("status").and_then(|s| s.as_bool()) == Some(false)
                && r.get("msg")
                    .and_then(|m| m.as_str())
                    .map(|m| m.contains("登录") || m.contains("未登录"))
                    .unwrap_or(false)
        })
        .unwrap_or_else(|| result.as_ref().err().map(|e| e.contains("失效")).unwrap_or(false));
    if expired {
        log(app, "info", "登录态失效，自动重登后重试", json!({ "route": route }));
        conn = relogin(app).await?;
        let mut c2 = json!({ "content": text });
        if let Some(sid) = session_of(app, &conn).await {
            c2["sessionId"] = sid;
        }
        result = bbt::call(&conn, "chat/send", &c2).await;
    }

    // 团长身份（或旧配置兜底）
    let mut denied = false;
    if let Ok(r) = &result {
        if r.get("status").and_then(|s| s.as_bool()) == Some(false) {
            let m = r.get("msg").and_then(|m| m.as_str()).unwrap_or("");
            if m.contains("权限") {
                denied = true;
            }
        }
    }
    if denied {
        if let Some(alt) = legacy_member_conn(app).await {
            let mut c2 = json!({ "content": text });
            if let Some(sid) = session_of(app, &alt).await {
                c2["sessionId"] = sid;
            }
            let r2 = bbt::call(&alt, "chat/send", &c2).await;
            if r2.as_ref().map(|r| r.get("status").and_then(|s| s.as_bool()) == Some(true)).unwrap_or(false) {
                log(app, "info", "已用旧版配置的团员账号兜底发送", json!({ "route": route }));
                result = r2;
            }
        }
    }

    let result = result?;
    if result.get("status").and_then(|s| s.as_bool()) != Some(true) {
        let m = result.get("msg").and_then(|m| m.as_str()).unwrap_or("平台拒绝").to_string();
        let hint = if m.contains("权限") {
            format!("{}：当前登录可能是团长账号，而 AI 代聊是「团员 → 团长」方向。请用已绑定团长的团员账号登录 PC 端", m)
        } else {
            m
        };
        log(app, "warn", &format!("向团长发消息失败：{}", hint), json!({ "route": route, "text": text }));
        return Err(hint);
    }

    let data = result.get("data").cloned().unwrap_or(json!(null));
    let new_sid = extract_session_id(&data);
    if let Some(s) = &new_sid {
        if let Some(st) = app.try_state::<Arc<AiGatewayState>>() {
            st.cache_session(s);
        }
    }
    let auto = data.get("auto").and_then(|v| v.as_bool()).unwrap_or(false);
    let quota_left = data.get("quotaLeft").and_then(|v| v.as_i64());
    // autoReply 兼容 {content} 对象与纯字符串
    let reply = match data.get("autoReply") {
        Some(Value::String(s)) => s.clone(),
        Some(v) => v.get("content").and_then(|c| c.as_str()).unwrap_or("").to_string(),
        _ => String::new(),
    };
    if reply.trim().is_empty() {
        let reason = if auto {
            "团长 AI 未回复：今日配额可能已用尽（每团员每日限额由团长设定，可在后台调高），或团长开启了「人工接管」".to_string()
        } else {
            "团长 AI 未回复：请检查团长后台「自动回复设置」是否开启 AI 代聊，或今日配额是否已用尽".to_string()
        };
        log(app, "warn", &reason, json!({ "route": route, "text": text, "data": data }));
        return Err(reason);
    }
    log(
        app,
        "info",
        &format!("[{}] 向团长发消息 → AI 回复 {} 字", route, reply.chars().count()),
        json!({ "route": route, "question": text, "reply": reply, "quota_left": quota_left }),
    );
    Ok(AiAnswer { reply, session_id: new_sid, quota_left, auto })
}

/// 按渠道原路回发（供 ai_gateway_send 的 auto_send 使用；自动回复管线有自己的分发逻辑）
pub async fn dispatch(app: &AppHandle, channel: &str, target: &str, text: &str) -> Result<(), String> {
    match channel {
        "wx_private" | "wx_group" | "wx" => crate::wxauto::send_text(app, target, text).await,
        "ilink" | "member" => {
            let state = app.try_state::<AppState>().ok_or("AppState 未初始化")?;
            let bot_id = if channel == "member" {
                let g = state.config.lock().unwrap();
                g.get("memberqr")
                    .and_then(|m| m.get("map"))
                    .and_then(|m| m.get(target))
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string()
            } else {
                target.to_string()
            };
            if bot_id.is_empty() {
                return Err("该团员未绑定 iLink 账号，无法回发".into());
            }
            let Some(mgr) = app.try_state::<Arc<crate::ilink_session::SessionManager>>() else {
                return Err("iLink 会话管理未初始化".into());
            };
            let (tx, rx) = tokio::sync::oneshot::channel();
            use crate::ilink_session::SessionCmd;
            let sent = mgr
                .send_cmd(&bot_id, SessionCmd::SendText { to: target.to_string(), text: text.to_string(), reply: tx })
                .is_ok();
            if !sent {
                return Err("该 iLink 账号未在线".into());
            }
            rx.await.unwrap_or_else(|_| Err("会话无响应".into())).map(|_| ())
        }
        _ => Err(format!("未知渠道：{}", channel)),
    }
}
