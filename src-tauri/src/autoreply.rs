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

/// 聊天对象（weauto「用户与监听」风格）：对单个私聊/群聊窗口的精细化配置。
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ListenEntry {
    /// 窗口名（微信昵称/群名，按子串匹配 peer 或 sender）
    pub name: String,
    /// "private" | "group"
    pub kind: String,
    /// 监测内容类型：text/voice/image/... 空 = 全部
    pub mon_types: Vec<String>,
    /// 监测关键字（与类型任一命中即算命中；都空 = 监测全部内容）
    pub mon_keywords: Vec<String>,
    /// 命中后动作："reply"（正常 AI 回复）| "ignore"（不回应，可整体屏蔽该对象）
    pub action: String,
}

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
    /// 聊天对象列表（私聊/群聊逐对象配置）
    pub listen_list: Vec<ListenEntry>,
    /// 群白名单（按群名子串匹配；空 = 不限制）
    pub group_whitelist: Vec<String>,
    /// 群黑名单（永不自动回复，优先级高于白名单）
    pub group_blacklist: Vec<String>,
    /// 禁词过滤（私聊+群聊，消息包含任一禁词则忽略）
    pub banned_words: Vec<String>,
    /// 群内频控窗口（秒）
    pub group_rate_window_sec: u64,
    /// 群内频控：窗口内最多回复条数（0 = 不限）
    pub group_rate_max_replies: u32,
}

impl Default for AutoReplyConfig {
    fn default() -> Self {
        AutoReplyConfig {
            enabled: true,
            member_telephone: String::new(),
            member_password: String::new(),
            private_reply: true,
            group_mode: "at".into(),
            at_names: Vec::new(),
            group_keywords: vec!["在吗".into(), "机器人".into(), "你好".into()],
            cooldown_sec: 15,
            strip_at_prefix: true,
            reply_voice: true,
            listen_list: Vec::new(),
            group_whitelist: Vec::new(),
            group_blacklist: Vec::new(),
            banned_words: Vec::new(),
            group_rate_window_sec: 60,
            group_rate_max_replies: 10,
        }
    }
}

pub struct AutoReplyState {
    pub config: Mutex<AutoReplyConfig>,
    running: Mutex<bool>,
    /// 团员 token 运行时缓存（会话失效自动重登）
    member: Mutex<Option<BbtConnection>>,
    /// 团员与团长平台会话的 sessionId 缓存（chat/send 必须带，否则 AI 回复不路由）。
    /// ⚠️ 线上 sessionId 是数字（如 7），必须保留原始 JSON 类型透传，
    /// 不能字符串化——官方前端直接回传数字。
    member_session: Mutex<Option<(Value, i64)>>, // (sessionId 原始值, 获取时间戳 ms)
    /// 最近日志（环形，最多 200 条）
    pub logs: Mutex<Vec<Value>>,
    /// peer -> 上次回复时间戳(ms)
    last_reply: Mutex<HashMap<String, i64>>,
    /// 群内频控：群 -> 窗口内回复时间戳列表(ms)
    group_rate: Mutex<HashMap<String, Vec<i64>>>,
}

impl AutoReplyState {
    pub fn new() -> Self {
        AutoReplyState {
            config: Mutex::new(AutoReplyConfig::default()),
            running: Mutex::new(false),
            member: Mutex::new(None),
            member_session: Mutex::new(None),
            logs: Mutex::new(Vec::new()),
            last_reply: Mutex::new(HashMap::new()),
            group_rate: Mutex::new(HashMap::new()),
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
        // 落盘到 app_data_dir/logs/autoreply.log
        if let Ok(dir) = app.path().app_data_dir() {
            let log_dir = dir.join("logs");
            let _ = std::fs::create_dir_all(&log_dir);
            let line = format!("[{}] {} {}\n", now_ms(), level, msg);
            if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(log_dir.join("autoreply.log")) {
                use std::io::Write;
                let _ = f.write_all(line.as_bytes());
            }
        }
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

/// 反检测：返回 [0.0, max) 之间的随机浮点数（无外部依赖，用系统时间做种子）
fn rand_interval(max: f64) -> f64 {
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.subsec_nanos())
        .unwrap_or(0) as f64;
    (nanos % 1_000_000.0) / 1_000_000.0 * max
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
    /// "text" | "voice" | "image"
    pub kind: String,
    pub text: String,
    /// 发送者标识（wx=群成员昵称，ilink=空），仅日志用
    pub sender: String,
    /// wx 通道群消息：是否 @ 了自己（由 sidecar 从 packed_info XML 解析）
    pub mentioned_self: bool,
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
/// 仅剥离 "@昵称" 这一段（含其后空白），保留剩余正文；全文即昵称时返回空。
fn strip_at(text: &str) -> String {
    let t = text.trim_start();
    if let Some(rest) = t.strip_prefix('@') {
        // 找到 @昵称 后的第一个空白位置，去掉昵称段及其后空白，保留正文
        match rest.find(|c: char| c.is_whitespace()) {
            Some(i) => rest[i..].trim_start().to_string(),
            None => String::new(), // 整个文本就是 @昵称，无后续内容
        }
    } else {
        t.to_string()
    }
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
        mentioned_self: false,
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
        mentioned_self: false,
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

/// 规则过滤（weauto 风格）。返回 Some(原因) = 忽略该消息；None = 放行。
/// 优先级：聊天对象(ignore) > 群黑名单 > 群白名单 > 禁词 > 监测条件。
async fn filtered_by_rules(
    _ar: &Arc<AutoReplyState>,
    cfg: &AutoReplyConfig,
    msg: &WxMsg,
) -> Option<String> {
    let peer = msg.peer.trim();
    let sender = msg.sender.trim();
    let name_hit = |name: &str| {
        let n = name.trim();
        !n.is_empty() && (peer.contains(n) || sender.contains(n))
    };
    let text = msg.text.trim();

    // ① 聊天对象（listen_list）：命中的行按其配置决定放行/屏蔽/监测条件
    for e in &cfg.listen_list {
        if !name_hit(&e.name) {
            continue;
        }
        if e.action == "ignore" {
            return Some(format!("聊天对象「{}」设为不回应", e.name));
        }
        // 监测条件：类型与关键字都留空 = 全部内容；填了任一 → 只有命中才处理
        let type_hit = e.mon_types.iter().any(|t| !t.trim().is_empty() && t.trim() == msg.kind);
        let kw_hit = e
            .mon_keywords
            .iter()
            .any(|k| !k.trim().is_empty() && text.contains(k.trim()));
        if !e.mon_types.is_empty() || !e.mon_keywords.is_empty() {
            if !type_hit && !kw_hit {
                return Some(format!("聊天对象「{}」未命中监测条件", e.name));
            }
        }
        return None; // 命中的对象行优先级最高，直接放行
    }

    // ② 群黑名单（优先级高于白名单）
    if msg.is_group && cfg.group_blacklist.iter().any(|n| name_hit(n)) {
        return Some("群在黑名单中".into());
    }
    // ③ 群白名单（非空时仅名单内群参与）
    if msg.is_group && !cfg.group_whitelist.is_empty() && !cfg.group_whitelist.iter().any(|n| name_hit(n)) {
        return Some("群不在白名单中".into());
    }
    // ④ 禁词过滤（私聊+群聊）
    if cfg
        .banned_words
        .iter()
        .any(|w| !w.trim().is_empty() && text.contains(w.trim()))
    {
        return Some("消息包含禁词".into());
    }
    None
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
        // text：纯文本；image：图片经离线 OCR 得到的文本（sidecar 已转成文字）
        "text" | "image" => true,
        // voice：语音经离线转写得到的文本（需开启 reply_voice）
        "voice" => cfg.reply_voice && !text.trim().is_empty(),
        _ => false,
    };
    if !trigger || to.is_empty() {
        return Ok(());
    }
    // ---- 聊天对象 / 黑白名单 / 禁词过滤（weauto 规则）----
    if let Some(blocked) = filtered_by_rules(ar, &cfg, &msg).await {
        ar.log(
            app,
            "info",
            &format!("已按规则忽略「{}」：{}", to, blocked),
            json!({ "peer": to, "rule": blocked }),
        );
        return Ok(());
    }
    if is_group {
        // wx 通道群消息：sidecar 已从 XML 解析出 @ 自己，直接触发；
        // ilink 群消息没有 mentioned_self，走原 at_triggered 逻辑。
        let at_self = msg.mentioned_self;
        match cfg.group_mode.as_str() {
            "off" => return Ok(()),
            "at" => {
                if !at_self && !at_triggered(&text, &cfg.at_names, &cfg.group_keywords) {
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
    // 冷却（key = 通道|会话，wx 与 ilink 互不挤占）；自测通道不冷却，方便反复验证
    if ch != "test" {
        {
            let mut lr = ar.last_reply.lock().unwrap();
            let now = now_ms();
            // 冷却加 ±20% 随机抖动，避免固定间隔被识别为机器人
            let cd_base = cfg.cooldown_sec.max(1) as f64;
            let cd_jitter = cd_base * 0.8 + rand_interval(cd_base * 0.4);
            let cd = (cd_jitter * 1000.0) as i64;
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
        // 群内频控（防刷屏）：窗口时间内同一群最多回复 N 条（0 = 不限）
        if is_group && cfg.group_rate_max_replies > 0 && cfg.group_rate_window_sec > 0 {
            let mut gr = ar.group_rate.lock().unwrap();
            let now = now_ms();
            let win = (cfg.group_rate_window_sec as i64) * 1000;
            let e = gr.entry(to.clone()).or_default();
            e.retain(|t| now - *t < win);
            if e.len() >= cfg.group_rate_max_replies as usize {
                return Ok(());
            }
            e.push(now);
            if gr.len() > 500 {
                gr.clear();
            }
        }

        // ---- 反检测：随机思考延迟（模拟真人看到消息后反应时间）----
        // 私聊 1.5~4s，群聊 2.5~6s；短消息反应快，长消息反应慢。
        let think_min = if is_group { 2.5f64 } else { 1.5f64 };
        let think_max = if is_group { 6.0f64 } else { 4.0f64 };
        let think_secs =
            think_min + rand_interval(think_max - think_min) * (1.0 + text.len() as f64 / 100.0).min(2.0);
        ar.log(app, "debug", &format!("反检测：思考延迟 {:.1}s", think_secs), json!({"peer": to}));
        tokio::time::sleep(Duration::from_secs_f64(think_secs)).await;
    }

    // ---- 团员身份：拿可用的 bbt-zb 连接（含 token）----
    let member_conn = ensure_member_conn(app, ar, &cfg).await?;

    // ---- 取团员与团长的平台会话 sessionId（chat/send 必须带，否则 AI 回复不路由）----
    let session_id = match ensure_member_session(app, ar, &member_conn).await {
        Ok(s) => s,
        Err(e) => return Err(format!("获取团员会话失败: {}", e)),
    };

    // ---- 调 chat/send 触发服务端 AI（带 sessionId，保留线上数字类型）----
    let result = match bbt::call(
        &member_conn,
        "chat/send",
        &json!({ "content": text, "sessionId": session_id }),
    )
    .await
    {
        Ok(r) => r,
        Err(e) => {
            // 会话失效 → 清缓存、重登一次再试
            if e.contains("失效") || e.contains("未登录") {
                ar.log(app, "info", "团员会话失效，重新登录…", json!({}));
                *ar.member.lock().unwrap() = None;
                *ar.member_session.lock().unwrap() = None;
                let mc = ensure_member_conn(app, ar, &cfg).await?;
                let sid = ensure_member_session(app, ar, &mc).await?;
                bbt::call(&mc, "chat/send", &json!({ "content": text, "sessionId": sid })).await?
            } else {
                return Err(format!("chat/send 失败: {}", e));
            }
        }
    };
    let ok = result.get("status").and_then(|s| s.as_bool()).unwrap_or(false);
    if !ok {
        let msg_s = result.get("msg").and_then(|m| m.as_str()).unwrap_or("chat/send 被拒绝");
        // 「无权限」= 身份不是团员：清缓存，下次自测重新走身份预检
        if msg_s.contains("无权限") || msg_s.contains("权限") {
            *ar.member.lock().unwrap() = None;
            *ar.member_session.lock().unwrap() = None;
            let hint = format!(
                "{}（AI 回复必须用团员身份触发；请在 聊天配置 → 回复规则 填团员账号）",
                msg_s
            );
            ar.log(app, "warn", &format!("bbt-zb 拒绝: {}", hint), json!({"channel": ch, "to": to, "text": text}));
            return Err(hint);
        }
        ar.log(app, "warn", &format!("bbt-zb 拒绝: {}", msg_s), json!({"channel": ch, "to": to, "text": text}));
        return Err(msg_s.to_string());
    }
    let data = result.get("data").cloned().unwrap_or(json!(null));
    // chat/send 成功后服务端会回 sessionId（官方前端 sid = sd.sessionId）→ 回写缓存
    let new_sid = extract_session_id(&data);
    if let Some(sid) = &new_sid {
        *ar.member_session.lock().unwrap() = Some((sid.clone(), now_ms()));
        ar.log(app, "debug", &format!("平台会话 sessionId={}", sid), json!({}));
    }
    let auto = data.get("auto").and_then(|v| v.as_bool()).unwrap_or(false);
    // autoReply 兼容对象 {content} 与纯字符串两种形态
    let reply = match data.get("autoReply") {
        Some(Value::String(s)) => s.clone(),
        Some(v) => v.get("content").and_then(|c| c.as_str()).unwrap_or("").to_string(),
        _ => String::new(),
    };
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
        // 自测通道：不进微信/iLink，仅验证「AI 出回复」这段核心链路
        "test" => {
            ar.log(
                app,
                "info",
                &format!("[自测]AI 回复成功（{} 字，未发往微信/iLink）：{}", reply.chars().count(), reply),
                json!({"channel": ch, "to": to, "question": text, "reply": reply}),
            );
            Ok(())
        }
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
            if ch != "test" {
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
            }
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
    // 未配置团员账号 → fallback 用团长自己的连接（仅兜底：团长 token 实测调 chat/send
    // 返回「无权限」，真正触发 AI 仍需团员账号；走这条路径时 sessionList 的身份预检
    // 会给出明确提示）。团长 token 可能为空（刚重启、token 已失效）或过期：优先用
    // 内存 token，否则用持久化的 telephone/password 自动重登，确保重启/过期后可恢复。
    if cfg.member_telephone.trim().is_empty() || cfg.member_password.is_empty() {
        if let Some(state) = app.try_state::<crate::state::AppState>() {
            // 1) 内存中已有有效 token → 直接复用
            {
                let lc = state.conn.lock().unwrap();
                if !lc.token.as_ref().map(|t| t.is_empty()).unwrap_or(true) {
                    ar.log(app, "info", "未配置团员账号，使用团长连接调 chat/send", json!({}));
                    return Ok(lc.clone());
                }
            }
            // 2) 无 token：从 config.json 读持久化的账号密码自动重登
            let (tel, pwd) = {
                let g = state.config.lock().unwrap();
                let conn = g.get("connection").cloned().unwrap_or(json!({}));
                (
                    conn.get("telephone").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                    conn.get("password").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                )
            };
            if !tel.is_empty() && !pwd.is_empty() {
                let mut fresh = BbtConnection {
                    base_url: base_url.clone(),
                    token: None,
                    telephone: Some(tel.clone()),
                    password: Some(pwd.clone()),
                };
                match bbt::login(&fresh, &tel, &pwd).await {
                    Ok((token, _acct)) => {
                        fresh.token = Some(token.clone());
                        *state.conn.lock().unwrap() = fresh.clone();
                        ar.log(app, "info", "团长连接已用持久化账号自动重登", json!({}));
                        return Ok(fresh);
                    }
                    Err(e) => {
                        ar.log(app, "warn", &format!("团长自动重登失败: {}", e), json!({}));
                    }
                }
            }
        }
        return Err("未登录团长账号，且未配置团员账号".into());
    }
    let mut c = BbtConnection {
        base_url,
        token: None,
        telephone: Some(cfg.member_telephone.clone()),
        password: None,
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

/// 从 sessionList 的 data 里提取 sessionId（兼容数字/字符串，2026-11-04 实测线上是数字）。
/// 候选位置：{session:{sessionId}} / {session:{id}} / {sessionId} / {list:[{sessionId}]}
/// （前两种是团员视角，list 形态是团长视角；官方 features/chat.js 两种都处理）。
fn extract_session_id(data: &Value) -> Option<Value> {
    let norm = |v: &Value| -> Option<Value> {
        if let Some(n) = v.as_i64() {
            return Some(json!(n));
        }
        if let Some(s) = v.as_str() {
            let s = s.trim();
            if s.is_empty() {
                return None;
            }
            // 纯数字字符串归一成数字（与服务端严格比较兼容）
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

/// 取团员与团长的平台会话 sessionId（chat/send 必须带，否则 AI 回复不路由）。
/// 经 chat/sessionList 取（团员身份），缓存 10 分钟；失效时由调用方清除后重试。
/// ⚠️ 身份预检：团长 token 调 chat/send 平台返回「无权限」（2026-11-04 实测），
/// 因此 sessionList 返回 role=leader 时直接给出可操作错误，不再白跑。
async fn ensure_member_session(
    app: &AppHandle,
    ar: &Arc<AutoReplyState>,
    conn: &BbtConnection,
) -> Result<Value, String> {
    const TTL_MS: i64 = 10 * 60 * 1000;
    {
        let g = ar.member_session.lock().unwrap();
        if let Some((sid, ts)) = g.as_ref() {
            if now_ms() - *ts < TTL_MS {
                return Ok(sid.clone());
            }
        }
    }
    let r = bbt::call(conn, "chat/sessionList", &json!({})).await?;
    let data = bbt::data_of(&r)?;
    if data.get("role").and_then(|v| v.as_str()) == Some("leader") {
        return Err(
            "当前是团长身份：平台 chat/send 仅团员有权限（实测返回「无权限」）。\
             请在 聊天配置 → 回复规则 填写团员账号（手机号+密码）后再测"
                .into(),
        );
    }
    match extract_session_id(&data) {
        Some(sid) => {
            *ar.member_session.lock().unwrap() = Some((sid.clone(), now_ms()));
            ar.log(app, "debug", &format!("团员 sessionId={}", sid), json!({}));
            Ok(sid)
        }
        _ => Err("团员 sessionList 未返回可用 sessionId（团员账号是否已归属团长/在团内？）".into()),
    }
}

// ============================================================================
//  AI 消息自测（核心业务一键体检，tauri dev / 生产均可反复验证）
// ============================================================================

/// 分步体检 AI 回复链路：本地配置 → 团员登录 → 会话 sessionId →（可选）chat/send 实发 → AI 回复解析。
/// `send=false` 只做链路检查不消耗平台配额；`send=true` 真发一条测试问题并返回 AI 回复原文。
pub async fn self_test(app: &AppHandle, question: &str, send: bool) -> Value {
    let Some(ar) = app.try_state::<Arc<AutoReplyState>>() else {
        return json!({ "ok": false, "steps": [ { "name": "模块", "ok": false, "detail": "自动回复模块未初始化" } ], "reply": "" });
    };
    let cfg = ar.config.lock().unwrap().clone();
    let mut steps: Vec<Value> = Vec::new();
    let mut ok_all = true;
    macro_rules! step {
        ($name:expr, $ok:expr, $detail:expr) => {{
            let ok: bool = $ok;
            let detail: String = $detail;
            steps.push(json!({ "name": $name, "ok": ok, "detail": detail }));
            if !ok {
                ok_all = false;
            }
            ar.log(
                app,
                if ok { "info" } else { "warn" },
                &format!("[自测]{}：{}", $name, detail),
                json!({}),
            );
            ok
        }};
    }

    // ① 本地配置
    let base_url = app
        .try_state::<crate::state::AppState>()
        .map(|s| s.conn.lock().unwrap().base_url.clone())
        .unwrap_or_default();
    let has_member = !cfg.member_telephone.trim().is_empty() && !cfg.member_password.is_empty();
    if !step!(
        "本地配置",
        !base_url.is_empty() && has_member,
        if base_url.is_empty() {
            "未配置 bbt-zb 接口地址".into()
        } else if has_member {
            format!("接口 {} · 团员账号 {} 已配置", base_url, cfg.member_telephone.trim())
        } else {
            "团员账号未配置：AI 回复必须用团员身份（团长 token 平台返回「无权限」），请在下方填写".into()
        }
    ) {
        return json!({ "ok": false, "steps": steps, "reply": "" });
    }

    // ② 团员登录
    let conn = match ensure_member_conn(app, &ar, &cfg).await {
        Ok(c) => {
            if step!("团员登录", true, "token 可用（缓存或重新登录成功）".into()) {
                c
            } else {
                return json!({ "ok": false, "steps": steps, "reply": "" });
            }
        }
        Err(e) => {
            step!("团员登录", false, e);
            return json!({ "ok": false, "steps": steps, "reply": "" });
        }
    };

    // ③ 会话 sessionId
    let sid = match ensure_member_session(app, &ar, &conn).await {
        Ok(s) => {
            if step!("会话 sessionId", true, format!("sessionId={}（已缓存）", s)) {
                s
            } else {
                return json!({ "ok": false, "steps": steps, "reply": "" });
            }
        }
        Err(e) => {
            step!("会话 sessionId", false, e);
            return json!({ "ok": false, "steps": steps, "reply": "" });
        }
    };

    if !send {
        return json!({ "ok": ok_all, "steps": steps, "reply": "" });
    }

    // ④ 实发 chat/send
    let q = if question.trim().is_empty() { "你好，在吗？" } else { question.trim() };
    let result = match bbt::call(&conn, "chat/send", &json!({ "content": q, "sessionId": sid })).await {
        Ok(r) => r,
        Err(e) => {
            step!("发送测试问题", false, format!("chat/send 失败: {}", e));
            return json!({ "ok": false, "steps": steps, "reply": "" });
        }
    };
    if result.get("status").and_then(|s| s.as_bool()) != Some(true) {
        let msg_s = result.get("msg").and_then(|m| m.as_str()).unwrap_or("被拒绝").to_string();
        if msg_s.contains("权限") {
            *ar.member.lock().unwrap() = None;
            *ar.member_session.lock().unwrap() = None;
        }
        step!("发送测试问题", false, format!("平台拒绝：{}", msg_s));
        return json!({ "ok": false, "steps": steps, "reply": "" });
    }
    let data = result.get("data").cloned().unwrap_or(json!(null));
    let new_sid = extract_session_id(&data);
    if let Some(s) = &new_sid {
        *ar.member_session.lock().unwrap() = Some((s.clone(), now_ms()));
    }
    let auto = data.get("auto").and_then(|v| v.as_bool()).unwrap_or(false);
    let reply = match data.get("autoReply") {
        Some(Value::String(s)) => s.clone(),
        Some(v) => v.get("content").and_then(|c| c.as_str()).unwrap_or("").to_string(),
        _ => String::new(),
    };
    if reply.trim().is_empty() {
        let reason = if auto {
            "平台已受理但无 AI 回复：团员今日配额已用尽，或团长开启了人工接管".into()
        } else {
            "平台未返回 autoReply（检查平台聊天参数 aiEnabled / 人设配置）".into()
        };
        step!("AI 回复", false, reason);
        return json!({ "ok": false, "steps": steps, "reply": "" });
    }
    step!("发送测试问题", true, format!("已受理，sessionId={}", new_sid.map(|s| s.to_string()).unwrap_or_default()));
    step!("AI 回复", true, format!("{} 字", reply.chars().count()));
    json!({ "ok": true, "steps": steps, "reply": reply })
}

/// 注入一条模拟客户消息，走完整自动回复管线（规则过滤 → AI → 回发）。
/// channel=test：AI 回复只记日志、不真正发往微信/iLink，用于 tauri dev 端到端验证。
pub async fn inject(app: &AppHandle, text: String, peer: String, is_group: bool) -> Result<String, String> {
    let to = if peer.trim().is_empty() { "自测会话".to_string() } else { peer };
    let msg = WxMsg {
        channel: "test".into(),
        bot_id: String::new(),
        peer: to.clone(),
        is_group,
        kind: "text".into(),
        text,
        sender: String::new(),
        mentioned_self: false,
    };
    process_msg(app, msg).await?;
    // 从日志里捞最近的 [自测]AI 回复 作为结果回显
    let reply = {
        let ar_state = app.state::<Arc<AutoReplyState>>();
        let lg = ar_state.logs.lock().unwrap();
        lg.iter()
            .rev()
            .find(|e| e.get("msg").and_then(|m| m.as_str()).map(|m| m.contains("[自测]AI 回复成功")).unwrap_or(false))
            .and_then(|e| e.get("extra").and_then(|x| x.get("reply")).and_then(|r| r.as_str()))
            .unwrap_or("")
            .to_string()
    };
    Ok(reply)
}
