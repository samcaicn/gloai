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
//! ## 身份模型（2026-11-07 用户纠正「这个理解是错的」，别再理解反了）
//!   * **PC 端登录的就是团长账号**（首页登录 → 拉团员名册 / 订单 / 配置 AI 人设，
//!     memberqr.ts / orderspage.ts 都依赖它）。这是正常且唯一预期的登录态，
//!     **绝不能**因为 role=leader 就报错，更不能要求「改用团员账号登录 PC 端」。
//!   * **每个微信私聊 / 群聊 / iLink 渠道 = 1 个团员**。发信主体是渠道对应的团员，
//!     不是登录账号；登录账号只提供平台登录态。所有渠道的消息统一「向团长发消息」，
//!     由团长 AI 回复后按「哪个渠道来的就回哪个渠道」原路分发。
//!     所以不存在「团长账号无法向自己发消息」这回事。
//!   * 因此**不做任何 role=leader 身份预检硬报错**，也不要求/填写任何团员账号信息
//!     （手机号/密码）。状态与自检只看：登录态、AI 代聊开关、今日配额。
//!   * token 过期 / 未登录 → 用 config.json 持久化的账号密码自动重登一次。
//!   * 兼容：极旧版本配置若残留团员账号，仅在平台明确返回「无权限」时静默尝试一次，
//!     不暴露给用户、不作为前置要求。
//!
//! ## 对外接口（Tauri commands，见 commands.rs）
//!   * `ai_gateway_status`  登录态 / 角色(仅展示) / 配额 / 渠道=团员 / 提示
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

/// 「向团长发消息」默认 action。2026-11-07 线上实测：
///   * 团员登录态：`chat/send` → status:true + `data.autoReply`（AI 回复）、`auto:true`、`quotaLeft` 递减。
///   * 团长登录态：`chat/send` → 「无权限」。
///   * 全量探测 47 个候选：`pc/*` 只有 `pc/create` / `pc/poll`（扫码登录）存在，
///     **后端目前没有 PC 专用聊天接口**，也不存在「PC 通道天然跳过配额」的代码路径。
/// 因此 PC 通道不受条数限制只能在团长侧配额配置 / 备用团员账号池上落地；
/// `ai_gateway.pc_chat_action`（或顶层 `pc_chat_action`）作为未来后端上线专用 action
/// 时的开关保留，未部署时会自动回退 `chat/send`，不丢客户消息。
const DEFAULT_CHAT_ACTION: &str = "chat/send";

/// 读取当前生效的聊天 action（配置覆盖优先，缺省回默认值）。
fn chat_action(app: &AppHandle) -> String {
    if let Some(state) = app.try_state::<AppState>() {
        let g = state.config.lock().unwrap();
        let configured = g
            .get("ai_gateway")
            .and_then(|v| v.get("pc_chat_action"))
            .or_else(|| g.get("pc_chat_action"))
            .and_then(|v| v.as_str())
            .map(|s| s.trim().to_string())
            .unwrap_or_default();
        if !configured.is_empty() {
            return configured;
        }
    }
    DEFAULT_CHAT_ACTION.to_string()
}

/// 备用团员账号池（可选）。config.json：
///   "ai_gateway": { "member_accounts": [ {"id":5,"telephone":"138...","password":"..."}, "138...:pwd" ] }
/// 每个微信私聊/群聊/iLink 渠道各是 1 个团员，平台配额按团员账号计。某个团员今日
/// 额度用尽时自动登录下一个团员继续「向团长发消息」（用户「换个团员 id 继续请求」）。
/// 未配置时仍可用下面的 member_ids() 按渠道 id 轮换请求参数。
fn member_accounts(app: &AppHandle) -> Vec<(Value, String, String)> {
    let Some(state) = app.try_state::<AppState>() else { return Vec::new() };
    let g = state.config.lock().unwrap();
    let mut out: Vec<(Value, String, String)> = Vec::new();
    if let Some(arr) = g
        .get("ai_gateway")
        .and_then(|a| a.get("member_accounts"))
        .and_then(|v| v.as_array())
    {
        for (i, v) in arr.iter().enumerate() {
            let (id, tel, pwd) = match v {
                Value::String(s) => {
                    let mut p = s.splitn(2, ':');
                    (json!(i + 1), p.next().unwrap_or("").trim().to_string(), p.next().unwrap_or("").to_string())
                }
                Value::Object(_) => (
                    v.get("id").cloned().unwrap_or_else(|| json!(i + 1)),
                    v.get("telephone").and_then(|x| x.as_str()).unwrap_or("").trim().to_string(),
                    v.get("password").and_then(|x| x.as_str()).unwrap_or("").to_string(),
                ),
                _ => continue,
            };
            if !tel.is_empty() && !pwd.is_empty() {
                out.push((id, tel, pwd));
            }
        }
    }
    // 兼容 0.2.x 残留的单团员配置（不作为前置要求）
    if out.is_empty() {
        let ar = g.get("autoreply").cloned().unwrap_or(json!({}));
        let tel = ar.get("member_telephone").and_then(|x| x.as_str()).unwrap_or("").trim().to_string();
        let pwd = ar.get("member_password").and_then(|x| x.as_str()).unwrap_or("").to_string();
        if !tel.is_empty() && !pwd.is_empty() && !pwd.starts_with("__test") {
            out.push((json!(1), tel, pwd));
        }
    }
    out
}

/// 本机「渠道 ↔ 团员」映射里出现过的团员 id（memberqr.map 的 key / 选中团员），
/// 用于 PC 专用接口或后端支持 memberId 时按渠道指定团员身份。
fn member_ids(app: &AppHandle) -> Vec<Value> {
    let Some(state) = app.try_state::<AppState>() else { return Vec::new() };
    let g = state.config.lock().unwrap();
    let mut out: Vec<Value> = Vec::new();
    if let Some(m) = g.get("memberqr").and_then(|m| m.get("map")).and_then(|m| m.as_object()) {
        for k in m.keys() {
            let v = k.parse::<i64>().map(|n| json!(n)).unwrap_or_else(|_| json!(k.clone()));
            if !out.contains(&v) { out.push(v); }
        }
    }
    if let Some(s) = g.get("channel_settings").and_then(|c| c.get("selected")).and_then(|s| s.get("member")) {
        let v = match s { Value::Null => None, _ => Some(s.clone()) };
        if let Some(v) = v {
            let t = v.as_str().map(|x| x.to_string()).unwrap_or_else(|| v.to_string());
            if !t.is_empty() && t != "null" {
                let n = t.parse::<i64>().map(|i| json!(i)).unwrap_or_else(|_| json!(t));
                if !out.contains(&n) { out.push(n); }
            }
        }
    }
    // 没绑定映射时，按监听渠道序号生成 id，保证「换团员 id」仍能发起不同身份的请求
    if out.is_empty() {
        let n = g.get("autoreply").and_then(|a| a.get("listen_list")).and_then(|v| v.as_array()).map(|a| a.len()).unwrap_or(0);
        for i in 1..=n.max(1).min(8) { out.push(json!(i as i64)); }
    }
    out
}

/// 从一次平台响应里解析 AI 回复（兼容 autoReply 为对象或字符串）
fn parse_reply(result: &Value) -> Option<AiAnswer> {
    if result.get("status").and_then(|s| s.as_bool()) != Some(true) { return None }
    let data = result.get("data").cloned().unwrap_or(json!(null));
    let reply = match data.get("autoReply") {
        Some(Value::String(s)) => s.clone(),
        Some(v) => v.get("content").and_then(|c| c.as_str()).unwrap_or("").to_string(),
        _ => String::new(),
    };
    if reply.trim().is_empty() { return None }
    Some(AiAnswer {
        reply,
        session_id: extract_session_id(&data),
        quota_left: data.get("quotaLeft").and_then(|v| v.as_i64()),
        auto: data.get("auto").and_then(|v| v.as_bool()).unwrap_or(false),
    })
}

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

/// 登录账号角色快照（仅展示用）
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
    // 网关是最容易「静默失败」的一层（autoReply 为空 / 无权限 / 配额用尽）。
    // 以前只存内存环形缓冲，应用一重启日志全丢，事后「查日志」只剩微信与
    // 管线两层，无法还原原因。这里同步落盘到 logs/ai_gateway.log。
    if let Ok(dir) = app.path().app_data_dir() {
        let log_dir = dir.join("logs");
        let _ = std::fs::create_dir_all(&log_dir);
        let line = format!(
            "[{}] {} {}\n",
            crate::autoreply::fmt_ts(now_ms()),
            level,
            msg
        );
        if let Ok(mut f) = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(log_dir.join("ai_gateway.log"))
        {
            use std::io::Write;
            let _ = f.write_all(line.as_bytes());
        }
    }
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
/// pub(crate)：payment_verify 等模块需要拿同一个团长连接去查 leader/orders。
pub(crate) async fn connection(app: &AppHandle) -> Result<BbtConnection, String> {
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

/// 探测登录账号角色（缓存 1 分钟，仅用于展示，不参与可用性判定）。
/// 团员视角能拿到 leader + session + aiEnabled；团长视角 sessionList 返回 list（每个团员一个会话）。
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
    // 线上 sessionList 的 leader.aiEnabled 是数字 1/0（不是 bool），需兼容两种；
    // 早期只认 bool 会解析成 None，导致状态条误显示「AI 代聊状态未知」。
    let parse_flag = |v: &Value| -> Option<bool> {
        match v {
            Value::Bool(b) => Some(*b),
            Value::Number(n) => n.as_i64().map(|i| i != 0),
            Value::String(s) => match s.trim() {
                "1" | "true" | "on" => Some(true),
                "0" | "false" | "off" | "" => Some(false),
                _ => None,
            },
            _ => None,
        }
    };
    let ai_enabled = leader
        .get("aiEnabled")
        .and_then(parse_flag)
        .or_else(|| d.get("aiEnabled").and_then(parse_flag));
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
    // total = 渠道总数 = 团员总数（每个私聊 / 群聊 / iLink 渠道各代表 1 个团员）
    json!({
        "wx_private": wx_private,
        "wx_group": wx_group,
        "ilink": ilink,
        "member": mapped,
        "mapped": mapped,
        "total": wx_private + wx_group + ilink
    })
}

/// 网关状态（前端互动页 / 聊天配置页展示）
pub async fn status(app: &AppHandle) -> Value {
    // 服务端写故障是「登录态/配额都正常，但发消息必失败」的特殊态：
    // 只用 identity（读接口）判断会误报一切正常，必须单独暴露给前端。
    let fault = bbt::fault_status();
    let fault_active = fault.get("active").and_then(|v| v.as_bool()).unwrap_or(false);
    let id = match identity(app, true).await {
        Ok(v) => v,
        Err(e) => {
            return json!({
                "ok": false, "logged_in": false, "role": "", "leader": null,
                "ai_enabled": null, "session_id": null, "quota": null,
                "channels": {}, "hint": e, "backend_fault": fault
            });
        }
    };
    let leader_name = id.leader.get("name").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let left = id.quota.get("left").and_then(|v| v.as_i64());

    // 关键：role **只用于文案描述，绝不作为可用性判定**。
    // PC 端登录团长账号是正常态（名册 / 订单 / AI 人设都靠它）；每个微信私聊 / 群聊 /
    // iLink 渠道各自代表 1 个团员，发信主体是渠道而不是登录账号。
    // 因此历史那种「role=leader → 报错要求换团员账号登录」的判定是错误理解，已删除。
    let channels = local_channels(app);
    let total = channels.get("total").and_then(|v| v.as_i64()).unwrap_or(0);
    let role_desc = match id.role.as_str() {
        "leader" => "团长账号登录",
        "member" => "团员账号登录",
        "deputy" => "团副账号登录",
        _ => "已登录",
    };
    let leader_label = if leader_name.is_empty() {
        "本团团长".to_string()
    } else {
        leader_name.clone()
    };
    let (hint, ok) = if fault_active {
        (
            format!(
                "{}｜期间客户消息由本机兜底话术回复（可在「回复规则」关闭），AI 回复暂不可用；后端恢复后点「立即重试」可马上解除熔断",
                fault.get("hint").and_then(|v| v.as_str()).unwrap_or("服务端写接口暂不可用")
            ),
            false,
        )
    } else if id.ai_enabled == Some(false) {
        ("团长后台尚未开启 AI 代聊：请在「自动回复设置」里开启".to_string(), false)
    } else if left == Some(0) {
        // 用户 2026-11-07：「团员的 10 条限制到了，换个团员 / PC 通道不要限制」。
        // 配额 0 不再判为不可用：ask_leader 会自动登录备用团员账号继续请求。
        // 后端目前没有 PC 专用聊天接口，所以同时提示在团长后台调高每日条数才是一劳永逸。
        (format!(
            "当前团员今日 AI 配额已用尽；将自动换备用团员继续请求（{} 个备用账号）。要彻底不限额，请在团长后台「自动回复设置」把「每人每日免费条数」调高",
            member_accounts(app).len()
        ), true)
    } else {
        (
            format!(
                "{}（正常）· 共 {} 个渠道 = {} 个团员：每个微信私聊 / 群聊 / iLink 各是 1 个团员，消息统一发往团长「{}」，由团长 AI 回复后原路分发",
                role_desc, total, total, leader_label
            ),
            true,
        )
    };
    json!({
        "ok": ok,
        "logged_in": true,
        "role": id.role,
        "leader": id.leader,
        "ai_enabled": id.ai_enabled,
        "session_id": id.session_id,
        "quota": id.quota,
        "channels": channels,
        "chat_action": chat_action(app),
        "member_pool": member_accounts(app).len(),
        "member_ids": member_ids(app),
        "backend_fault": fault,
        "hint": hint
    })
}

/// 兼容兜底：极旧版本配置里可能残留团员账号（0.2.x 的 member_telephone/password）。
/// 仅在平台明确返回「无权限」时静默尝试一次；新版本 UI 不暴露、不要求填写，
/// 也不是前置条件 —— PC 端登录团长账号本来就是正常态。
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

/// 当前团员额度用尽时，**换一个团员继续请求**。
/// 用户 2026-11-07：「团员的 10 条限制到了，换个团员」。平台配额按团员账号计
/// （bbt_chat_daily.memberId），所以换账号即换额度；配额 0 不再是终点：
///   ① 配置了 `ai_gateway.member_accounts` → 依次登录下一个团员，用其登录态再发
///      （平台配额是按团员账号计的，换账号即换额度）；
///   ② 未配置账号池 → 用当前登录态按「渠道 ↔ 团员」映射轮换 memberId 再发
///      （后端 PC 专用接口支持 memberId 时即可生效）；
///   ③ 仍无回复 → 明确报出已尝试的团员 id，便于继续排障。
async fn rotate_member_retry(
    app: &AppHandle,
    text: &str,
    route: &str,
    action: &str,
    conn: &BbtConnection,
) -> Result<AiAnswer, String> {
    let mut tried: Vec<String> = Vec::new();
    // ① 备用团员账号池
    for (id, tel, pwd) in member_accounts(app) {
        let c = BbtConnection {
            base_url: conn.base_url.clone(),
            token: None,
            telephone: Some(tel.clone()),
            password: Some(pwd.clone()),
        };
        let (token, _) = match bbt::login(&c, &tel, &pwd).await {
            Ok(v) => v,
            Err(e) => { tried.push(format!("{}（登录失败 {}）", id, e)); continue }
        };
        let mut fresh = c.clone();
        fresh.token = Some(token);
        // 先查额度再发：chat/send 即使被配额拦下也会往团长会话写一条消息，
        // 不能为了「换团员」把会话刷满测试消息。
        let left = bbt::call(&fresh, "chat/quota", &json!({}))
            .await
            .ok()
            .and_then(|r| bbt::data_of(&r).ok())
            .and_then(|d| d.get("left").and_then(|v| v.as_i64()));
        if left == Some(0) {
            tried.push(format!("{}（额度 0）", id));
            continue;
        }
        let cond = json!({ "content": text, "memberId": id });
        if let Ok(r) = bbt::call(&fresh, action, &cond).await {
            if let Some(ans) = parse_reply(&r) {
                log(app, "info", &format!("[{}] 当前账号额度用尽，换团员 {} 继续请求 → AI 回复 {} 字",
                    route, id, ans.reply.chars().count()), json!({ "route": route, "member_id": id, "reply": ans.reply }));
                return Ok(ans);
            }
        }
        tried.push(id.to_string());
        if tried.len() >= 6 { break }
    }
    // ② 按渠道团员 id 轮换：只在配置了 PC 专用接口时做。
    // 官方 chat/send 服务端按 token 固定 sessionId，忽略 memberId，盲重试只会
    // 往团长会话写重复消息；PC 专用接口（不受条数限制）才需要按团员 id 路由。
    if action != DEFAULT_CHAT_ACTION {
        for id in member_ids(app).into_iter().take(3) {
            let mut cond = json!({ "content": text, "memberId": id });
            if let Some(sid) = session_of(app, conn).await { cond["sessionId"] = sid; }
            if let Ok(r) = bbt::call(conn, action, &cond).await {
                if let Some(ans) = parse_reply(&r) {
                    log(app, "info", &format!("[{}] 换团员 id={} 继续请求 → AI 回复 {} 字",
                        route, id, ans.reply.chars().count()), json!({ "route": route, "member_id": id }));
                    return Ok(ans);
                }
            }
            tried.push(id.to_string());
        }
    }
    Err(format!(
        "已换 {} 个团员继续请求仍未拿到 AI 回复（{}）：请等次日配额重置、在团长后台调高「每人每日免费条数」，或在 config.json 的 ai_gateway.member_accounts 补充还有额度的备用团员账号",
        tried.len(), tried.join("/")
    ))
}

/// 核心：**所有渠道消息统一「向团长发消息」，拿团长 AI 回复**。
/// `route` 仅作日志标记（如 "wx|凯哥" / "ilink|bot_xxx"）；`member_id` 可选，
/// 用于按渠道指定团员身份（PC 专用接口 / 换团员 id 继续请求）。
pub async fn ask_leader(app: &AppHandle, text: &str, route: &str) -> Result<AiAnswer, String> {
    ask_leader_ex(app, text, route, None).await
}

pub async fn ask_leader_ex(
    app: &AppHandle,
    text: &str,
    route: &str,
    member_id: Option<Value>,
) -> Result<AiAnswer, String> {
    let text = text.trim();
    if text.is_empty() {
        return Err("消息内容为空".into());
    }
    // 熔断期内连读接口都不打，直接快速失败：写接口注定不可用，AI 回复必然拿不到，
    // 提前走自动回复的兜底话术，客户不用白等一整个生成超时。
    // 注意：图片支付校验是本地确定性逻辑（只读订单），不受此限制，故障期间仍可正常核验。
    let fs = bbt::fault_status();
    if fs.get("active").and_then(|v| v.as_bool()) == Some(true) {
        return Err(format!(
            "{}（熔断中，{} 秒后自动重试）：{}｜服务端写接口暂不可用，本次未向团长发消息",
            bbt::FAULT_TAG,
            fs.get("remaining_sec").and_then(|v| v.as_i64()).unwrap_or(0),
            fs.get("reason").and_then(|v| v.as_str()).unwrap_or("服务器繁忙")
        ));
    }
    let mut conn = connection(app).await?;
    // 后端为 PC 端专开的聊天接口名可配置；缺省仍是官方团员侧 chat/send。
    let action = chat_action(app);
    log(app, "debug", &format!("向团长发消息 action={}", action), json!({ "route": route }));

    let mut cond = json!({ "content": text });
    if let Some(sid) = session_of(app, &conn).await {
        cond["sessionId"] = sid;
    }
    // 每个渠道 = 1 个团员：显式指定团员 id 时带上，供 PC 专用接口 / 后端按团员路由。
    if let Some(mid) = &member_id {
        cond["memberId"] = mid.clone();
    }

    // AI 生成类请求走长超时（见 bbt::needs_long_timeout，现 120s）；后端尾延迟实测
    // 偶发 >60s。生成排队 / 网络抖动导致的超时，先用**同一登录态重发**（最多 2 次），
    // 不触发重登（重登反而更慢、且可能把同一句话重复写进团长会话）。2026-11-08 十轮
    // 自测证实：第一轮超时后立刻重发，第二轮往往在数秒内被服务端正常响应。
    let mut result = bbt::call(&conn, &action, &cond).await;
    for attempt in 1..=2 {
        let is_transient = result
            .as_ref()
            .err()
            .map(|e| {
                // 服务端写故障是确定性失败，重发只会重复打已经打满的数据库；
                // 只有网络抖动/超时才值得重发。
                !e.contains(bbt::FAULT_TAG)
                    && (e.contains("网络错误") || e.contains("timed out") || e.contains("timeout"))
            })
            .unwrap_or(false);
        if !is_transient {
            break;
        }
        log(
            app,
            "info",
            &format!("网络抖动/超时，第 {} 次重发：{}", attempt, result.as_ref().err().unwrap()),
            json!({ "route": route }),
        );
        tokio::time::sleep(std::time::Duration::from_millis(800)).await;
        result = bbt::call(&conn, &action, &cond).await;
    }
    // 配置了 PC 专用接口但后端还没部署（返回「未知 action」）→ 自动回退官方 chat/send，
    // 保证升级过渡期不丢客户消息。
    if action != DEFAULT_CHAT_ACTION
        && result
            .as_ref()
            .ok()
            .and_then(|r| r.get("msg"))
            .and_then(|m| m.as_str())
            .map(|m| m.contains("未知 action"))
            .unwrap_or(false)
    {
        log(app, "warn", "配置的 PC 端聊天接口后端未部署，已回退 chat/send", json!({ "route": route, "action": action }));
        result = bbt::call(&conn, DEFAULT_CHAT_ACTION, &cond).await;
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
        .unwrap_or_else(|| {
            result
                .as_ref()
                .err()
                .map(|e| e.contains("失效") && !e.contains(bbt::FAULT_TAG))
                .unwrap_or(false)
        });
    if expired {
        log(app, "info", "登录态失效，自动重登后重试", json!({ "route": route }));
        conn = relogin(app).await?;
        let mut c2 = json!({ "content": text });
        if let Some(sid) = session_of(app, &conn).await {
            c2["sessionId"] = sid;
        }
        result = bbt::call(&conn, &action, &c2).await;
    }

    // 平台明确返回「无权限」时，仅对极旧配置的残留团员账号做一次静默兼容兜底
    // （不是前置要求，也不影响「团长账号登录是正常态」这一身份模型）。
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
            let r2 = bbt::call(&alt, &action, &c2).await;
            if r2.as_ref().map(|r| r.get("status").and_then(|s| s.as_bool()) == Some(true)).unwrap_or(false) {
                log(app, "info", "已用旧版配置的团员账号兜底发送", json!({ "route": route }));
                result = r2;
            }
        }
    }

    let result = result?;
    if result.get("status").and_then(|s| s.as_bool()) != Some(true) {
        let m = result.get("msg").and_then(|m| m.as_str()).unwrap_or("平台拒绝").to_string();
        let hint = if bbt::is_backend_fault_msg(&m) {
            // 首次撞上写故障（熔断刚建立）也要带标记，自动回复才能走兜底话术，
            // 而不是让客户消息直接黑洞。
            format!(
                "{}：{}｜服务端写入暂不可用（读接口正常），PC 端已自动熔断并降低重试频率",
                bbt::FAULT_TAG, m
            )
        } else if m.contains("权限") {
            format!(
                "{}：团长登录态需要使用后端为 PC 端专开的聊天接口（在 config.json 配置 ai_gateway.pc_chat_action 为后端提供的 action 名）；也可确认团长后台 AI 代聊已开启并重新登录刷新令牌",
                m
            )
        } else {
            m.clone()
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
        // 「微信有消息但没反应」最常见原因：平台没有生成 autoReply。
        // 以前一律报「检查 AI 代聊开关」，用户无从分辨是配额用尽还是配置问题；
        // 这里按 quotaLeft / auto 拆成可执行的三种结论。
        // chat/send 的响应不一定带 quotaLeft，缺失时补查一次 chat/quota 再判定。
        let left = match quota_left {
            Some(v) => Some(v),
            None => bbt::call(&conn, "chat/quota", &json!({}))
                .await
                .ok()
                .and_then(|r| bbt::data_of(&r).ok())
                .and_then(|d| d.get("left").and_then(|v| v.as_i64())),
        };
        let quota_exhausted = left == Some(0);
        let reason = match (left, auto) {
            (Some(0), _) => "当前团员今日 AI 配额已用尽（剩余 0 条），平台不再生成自动回复".to_string(),
            (_, false) => format!(
                "团长 AI 代聊未生效（auto=false{}）：请确认团长后台「自动回复设置」已开启 AI 代聊、未处于人工接管",
                left.map(|q| format!("，今日剩余 {} 条", q)).unwrap_or_default()
            ),
            (_, true) => format!(
                "团长 AI 已受理但未产出回复（auto=true{}）：可能是平台侧生成失败或触发了敏感词/人工接管，稍后重试或查看团长后台会话记录",
                left.map(|q| format!("，今日剩余 {} 条", q)).unwrap_or_default()
            ),
        };
        // 用户 2026-11-07：「团员的 10 条限制到了，换个团员」。只有**配额用尽**才
        // 换团员重试：代聊关闭 / 生成失败 / 敏感词这些原因换账号只会把同一句话重复
        // 写进团长会话，还会掩盖真实故障。后端没有 PC 专用聊天接口，轮换走的是
        // config.json 的 ai_gateway.member_accounts 备用团员账号池。
        if quota_exhausted {
            match rotate_member_retry(app, text, route, &action, &conn).await {
                Ok(ans) => return Ok(ans),
                Err(e) => {
                    log(app, "warn", &format!("[{}] {}；{}", route, reason, e), json!({
                        "route": route, "text": text, "auto": auto,
                        "quota_left": left, "data": data, "rotate": e,
                    }));
                    return Err(format!("{}；{}", reason, e));
                }
            }
        }
        log(app, "warn", &format!("[{}] {}", route, reason), json!({
            "route": route,
            "text": text,
            "auto": auto,
            "quota_left": left,
            "data": data,
        }));
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
