//! 微信自动回复：iLink 入站消息 → bbt-zb 服务端 AI 生成 → iLink 回发。
//!
//! bbt-zb 平台的 AI 自动回复只在「团员 chat/send」时由服务端触发（同步返回
//! autoReply 并落库，senderType='ai'）。团长登录态没有 AI 生成端点，因此本模块
//! 采用双账号设计：
//!   * 主连接（AppState.conn）：团长账号，负责转发/人工回复（bridge.rs 管）。
//!   * 团员账号（本模块配置）：telephone+password 登录取 token，专用于
//!     chat/send 触发服务端 AI。所有微信联系人的消息汇聚到该团员与团长的
//!     平台会话（服务端按 personaPrompt/FAQ/话术库生成回复）。
//!
//! 数据流：
//!   [微信用户] → iLink(getupdates) → ilink_session 入站广播
//!     → 本模块过滤（私聊开关 / 群聊 at·all·off 模式 / 冷却）
//!     → chat/send{content} → result.data.autoReply.content
//!     → iLink sendmessage 回发（私聊回 peer，群聊回 group_id）
//!
//! 失败语义：配额用尽（响应 auto=true 无 autoReply）、人工接管中（takeoverMode）、
//! 团员身份缺失、会话失效（自动重登一次）——全部记入日志并推送前端事件。

use crate::bbt::{self, BbtConnection};
use crate::ilink_session::{SessionCmd, SessionManager};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tauri::{AppHandle, Emitter, Manager};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct AutoReplyConfig {
    pub enabled: bool,
    /// 团员账号（触发 bbt-zb 服务端 AI 的身份）。留空则仅当主账号 role==member 时可用。
    pub member_telephone: String,
    pub member_password: String,
    /// 私聊自动回复
    pub private_reply: bool,
    /// 群聊模式："off" | "at"（@机器人昵称触发）| "all"
    pub group_mode: String,
    /// @ 触发名单（bot 微信昵称/昵称片段，任一命中即触发；空则任何 @ 开头都触发）
    pub at_names: Vec<String>,
    /// 群聊 additional 关键词触发（mode=at 时，命中关键词也回复）
    pub group_keywords: Vec<String>,
    /// 同一会话最小回复间隔（秒），防连发刷屏
    pub cooldown_sec: u64,
    /// 回复前去掉 @xxx 前缀（把 @机器人 触发词从发给 AI 的文本里清掉）
    pub strip_at_prefix: bool,
    /// 语音转写文本也回复（kind=voice 且带转写）
    pub reply_voice: bool,
}

impl Default for AutoReplyConfig {
    fn default() -> Self {
        AutoReplyConfig {
            enabled: false,
            member_telephone: String::new(),
            member_password: String::new(),
            private_reply: true,
            group_mode: "at".into(),
            at_names: Vec::new(),
            group_keywords: vec!["在吗".into(), "机器人".into(), "你好".into()],
            cooldown_sec: 5,
            strip_at_prefix: true,
            reply_voice: false,
        }
    }
}

pub struct AutoReplyState {
    pub config: Mutex<AutoReplyConfig>,
    running: Mutex<bool>,
    /// 团员 token 运行时缓存（会话失效自动重登）
    member: Mutex<Option<BbtConnection>>,
    /// 最近日志（环形，最多 200 条）
    pub logs: Mutex<Vec<Value>>,
    /// peer -> 上次回复时间戳(ms)
    last_reply: Mutex<HashMap<String, i64>>,
}

impl AutoReplyState {
    pub fn new() -> Self {
        AutoReplyState {
            config: Mutex::new(AutoReplyConfig::default()),
            running: Mutex::new(false),
            member: Mutex::new(None),
            logs: Mutex::new(Vec::new()),
            last_reply: Mutex::new(HashMap::new()),
        }
    }

    fn log(&self, app: &AppHandle, level: &str, msg: &str, extra: Value) {
        let entry = json!({
            "ts": now_ms(),
            "level": level,
            "msg": msg,
            "extra": extra,
        });
        {
            let mut lg = self.logs.lock().unwrap();
            lg.push(entry.clone());
            if lg.len() > 200 {
                let cut = lg.len() - 200;
                lg.drain(0..cut);
            }
        }
        let _ = app.emit("autoreply-event", entry);
        eprintln!("[autoreply] {} {}", level, msg);
    }
}

impl Default for AutoReplyState {
    fn default() -> Self {
        Self::new()
    }
}

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// 供其他模块（wxauto 等）记日志用的时间戳
pub fn now_ms_pub() -> i64 {
    now_ms()
}

/// 统一入站消息（多通道）：iLink 团员 / 微信 wxauto，都是「客户」。
#[derive(Debug, Clone)]
pub struct WxMsg {
    /// "ilink" | "wx"
    pub channel: String,
    /// ilink：该消息所属 bot 账号（回复走它）；wx：空
    pub bot_id: String,
    /// 回复目标：ilink 私聊=peer / 群聊=group_id；wx=会话 username（wxid_xxx / xxx@chatroom）
    pub peer: String,
    pub is_group: bool,
    /// "text" | "voice"
    pub kind: String,
    pub text: String,
    /// 发送者标识（wx=群成员昵称，ilink=空），仅日志用
    pub sender: String,
}

/// 启动自动回复任务（幂等）。每轮从 state 读最新配置，支持运行中改配置。
pub fn ensure_started(app: AppHandle) {
    {
        let st = app.state::<Arc<AutoReplyState>>();
        let mut r = st.running.lock().unwrap();
        if *r {
            return;
        }
        *r = true;
    }
    tauri::async_runtime::spawn(async move {
        loop {
            let Some(mgr) = app.try_state::<Arc<SessionManager>>() else {
                tokio::time::sleep(Duration::from_secs(3)).await;
                continue;
            };
            let Some(ar) = app.try_state::<Arc<AutoReplyState>>() else {
                tokio::time::sleep(Duration::from_secs(3)).await;
                continue;
            };
            let mgr = Arc::clone(&mgr);
            let mut inbound_rx = mgr.subscribe_inbound();
            let _ = &mgr;
            loop {
                // 每条事件都取最新配置（运行中改配置立即生效）
                match inbound_rx.recv().await {
                    Ok(ev) => {
                        let cfg = ar.config.lock().unwrap().clone();
                        if !cfg.enabled {
                            continue;
                        }
                        if let Err(e) = handle_inbound(&app, &ar, &ev).await {
                            ar.log(&app, "warn", &e, json!({}));
                        }
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(n)) => {
                        ar.log(&app, "warn", &format!("入站事件落后 {} 条", n), json!({}));
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
                }
            }
        }
    });
}

/// 解析 @ 前缀（微信 @ 消息文本形如 "@昵称 内容"，昵称后可能跟 \u2005/空格）。
fn strip_at(text: &str) -> String {
    let t = text.trim_start();
    if let Some(rest) = t.strip_prefix('@') {
        // @昵称以空白（含 \u2005）结束
        let trimmed = rest.trim_start_matches(|c: char| !c.is_whitespace());
        return trimmed.trim_start().to_string();
    }
    t.to_string()
}

fn at_triggered(text: &str, at_names: &[String], keywords: &[String]) -> bool {
    let t = text.trim_start();
    if t.starts_with('@') {
        if at_names.is_empty() {
            return true; // 未配置名单：任何 @ 开头都触发
        }
        let lower = t.to_lowercase();
        if at_names.iter().any(|n| !n.is_empty() && lower.contains(&n.to_lowercase())) {
            return true;
        }
    }
    keywords.iter().any(|k| !k.is_empty() && text.contains(k))
}

async fn handle_inbound(
    app: &AppHandle,
    ar: &Arc<AutoReplyState>,
    ev: &Value,
) -> Result<(), String> {
    if ev.get("kind").and_then(|v| v.as_str()) != Some("message")
        || ev.get("event").and_then(|v| v.as_str()) != Some("in")
    {
        return Ok(());
    }
    let rec = ev.get("record").cloned().unwrap_or(json!(null));
    let kind = rec.get("kind").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let text = rec.get("text").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let bot_id = rec.get("bot_id").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let peer = rec.get("peer").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let group_id = ev
        .get("group_id")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    // 回复目标：群聊回 group_id，私聊回 peer
    let reply_to = if !group_id.is_empty() { group_id.clone() } else { peer.clone() };
    let msg = WxMsg {
        channel: "ilink".into(),
        bot_id,
        peer: reply_to,
        is_group: !group_id.is_empty(),
        kind,
        text,
        sender: String::new(),
    };
    process_msg_with(app, ar, msg).await
}

/// wxauto 通道入口（wxauto.rs 调用）：微信私聊/群聊消息进统一管线。
pub async fn process_wx_msg(
    app: &AppHandle,
    peer: String,
    is_group: bool,
    text: String,
    sender: String,
) -> Result<(), String> {
    let Some(ar) = app.try_state::<Arc<AutoReplyState>>() else {
        return Err("自动回复模块未初始化".into());
    };
    let msg = WxMsg {
        channel: "wx".into(),
        bot_id: String::new(),
        peer,
        is_group,
        kind: "text".into(),
        text,
        sender,
    };
    process_msg_with(app, &ar, msg).await
}

/// wxauto 通道便捷入口（从 app 取状态）。
pub async fn process_msg(app: &AppHandle, msg: WxMsg) -> Result<(), String> {
    let Some(ar) = app.try_state::<Arc<AutoReplyState>>() else {
        return Err("自动回复模块未初始化".into());
    };
    process_msg_with(app, &ar, msg).await
}

/// 统一管线：过滤 → 团员 chat/send 触发服务端 AI → 按 channel 原路回发。
async fn process_msg_with(
    app: &AppHandle,
    ar: &Arc<AutoReplyState>,
    msg: WxMsg,
) -> Result<(), String> {
    let cfg = ar.config.lock().unwrap().clone();
    let to = msg.peer.clone();
    let mut text = msg.text.clone();
    let is_group = msg.is_group;
    let ch = msg.channel.as_str();

    // ---- 触发过滤 ----
    let trigger = match msg.kind.as_str() {
        "text" => true,
        "voice" => cfg.reply_voice && !text.trim().is_empty(),
        _ => false,
    };
    if !trigger || to.is_empty() {
        return Ok(());
    }
    if is_group {
        match cfg.group_mode.as_str() {
            "off" => return Ok(()),
            "at" => {
                if !at_triggered(&text, &cfg.at_names, &cfg.group_keywords) {
                    return Ok(());
                }
            }
            "all" => {}
            _ => return Ok(()),
        }
    } else if !cfg.private_reply {
        return Ok(());
    }
    if text.trim().is_empty() {
        return Ok(());
    }
    if cfg.strip_at_prefix {
        text = strip_at(&text);
    }
    if text.trim().is_empty() {
        return Ok(());
    }
    // 冷却（key = 通道|会话，wx 与 ilink 互不挤占）
    {
        let mut lr = ar.last_reply.lock().unwrap();
        let now = now_ms();
        let cd = (cfg.cooldown_sec.max(1) as i64) * 1000;
        let key = format!("{}|{}", ch, to);
        if let Some(t) = lr.get(&key) {
            if now - *t < cd {
                return Ok(());
            }
        }
        lr.insert(key, now);
        if lr.len() > 2000 {
            lr.clear();
        }
    }

    // ---- 团员身份：拿可用的 bbt-zb 连接（含 token）----
    let member_conn = ensure_member_conn(app, ar, &cfg).await?;

    // ---- 调 chat/send 触发服务端 AI ----
    let result = match bbt::call(&member_conn, "chat/send", &json!({ "content": text })).await {
        Ok(r) => r,
        Err(e) => {
            // 会话失效 → 重登一次再试
            if e.contains("失效") || e.contains("未登录") {
                ar.log(app, "info", "团员会话失效，重新登录…", json!({}));
                *ar.member.lock().unwrap() = None;
                let mc = ensure_member_conn(app, ar, &cfg).await?;
                bbt::call(&mc, "chat/send", &json!({ "content": text })).await?
            } else {
                return Err(format!("chat/send 失败: {}", e));
            }
        }
    };
    let ok = result.get("status").and_then(|s| s.as_bool()).unwrap_or(false);
    if !ok {
        let msg_s = result.get("msg").and_then(|m| m.as_str()).unwrap_or("chat/send 被拒绝");
        ar.log(app, "warn", &format!("bbt-zb 拒绝: {}", msg_s), json!({"channel": ch, "to": to, "text": text}));
        return Err(msg_s.to_string());
    }
    let data = result.get("data").cloned().unwrap_or(json!(null));
    // 缓存 sessionId（团员与团长的平台会话，日志排查用）
    if let Some(sid) = data.get("sessionId") {
        ar.log(app, "debug", &format!("平台会话 sessionId={}", sid), json!({}));
    }
    let auto = data.get("auto").and_then(|v| v.as_bool()).unwrap_or(false);
    let reply = data
        .get("autoReply")
        .and_then(|r| r.get("content"))
        .and_then(|c| c.as_str())
        .unwrap_or("")
        .to_string();
    if reply.trim().is_empty() {
        let reason = if auto {
            "AI 无回复：团员今日配额已用尽，或团长已开启人工接管"
        } else {
            "AI 无回复：服务端未返回 autoReply"
        };
        ar.log(app, "warn", reason, json!({"channel": ch, "to": to, "text": text}));
        return Err(reason.to_string());
    }

    // ---- 原路回发 ----
    let ch_label = if is_group { "群" } else { "私聊" };
    let send_res = match ch {
        "wx" => crate::wxauto::send_text(app, &to, &reply).await,
        _ => {
            // ilink：经该消息所属 bot 账号回发
            let Some(mgr) = app.try_state::<Arc<SessionManager>>() else {
                return Err("iLink 会话管理未初始化".into());
            };
            let (tx, rx) = tokio::sync::oneshot::channel();
            let sent = mgr
                .send_cmd(&msg.bot_id, SessionCmd::SendText { to: to.clone(), text: reply.clone(), reply: tx })
                .is_ok();
            if sent {
                rx.await
                    .unwrap_or_else(|_| Err("会话无响应".into()))
                    .map(|_| ())
            } else {
                Err("该账号未在线".into())
            }
        }
    };
    match send_res {
        Ok(_) => {
            ar.log(
                app,
                "info",
                &format!(
                    "[{}]已自动回复 {}{} ← AI({} 字)",
                    if ch == "wx" { "微信" } else { "iLink" },
                    ch_label,
                    if is_group && !msg.sender.is_empty() { format!("·{}", msg.sender) } else { String::new() },
                    reply.chars().count()
                ),
                json!({"channel": ch, "to": to, "question": text, "reply": reply}),
            );
            Ok(())
        }
        Err(e) => {
            ar.log(app, "warn", &format!("回发失败({}): {}", ch, e), json!({"channel": ch, "to": to, "reply": reply}));
            Err(e)
        }
    }
}

/// 取团员连接：优先用缓存 token；无则用配置的 telephone/password 登录。
async fn ensure_member_conn(
    app: &AppHandle,
    ar: &Arc<AutoReplyState>,
    cfg: &AutoReplyConfig,
) -> Result<BbtConnection, String> {
    let base_url = app
        .try_state::<crate::state::AppState>()
        .map(|s| s.conn.lock().unwrap().base_url.clone())
        .unwrap_or_default();
    if base_url.is_empty() {
        return Err("未配置 bbt-zb 接口地址".into());
    }
    if let Some(c) = ar.member.lock().unwrap().clone() {
        if c.base_url == base_url {
            return Ok(c);
        }
    }
    if cfg.member_telephone.trim().is_empty() || cfg.member_password.is_empty() {
        return Err("未配置团员账号（触发服务端 AI 需要团员身份调 chat/send）".into());
    }
    let mut c = BbtConnection {
        base_url,
        token: None,
        telephone: Some(cfg.member_telephone.clone()),
    };
    let (token, account) = bbt::login(&c, &cfg.member_telephone.trim(), &cfg.member_password).await?;
    let role = account.get("role").and_then(|r| r.as_str()).unwrap_or("").to_string();
    c.token = Some(token);
    *ar.member.lock().unwrap() = Some(c.clone());
    ar.log(
        app,
        "info",
        &format!("团员账号已登录（role={}）", if role.is_empty() { "?" } else { &role }),
        json!({}),
    );
    Ok(c)
}
