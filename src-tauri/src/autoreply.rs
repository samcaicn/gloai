//! 微信/iLink 自动回复：多渠道入站消息 → 统一 AI 网关 → 原路回发（0.3.0）。
//!
//! ## 业务模型（别再理解反了）
//! bbt-zb 的 AI 代聊是团长配置的：团员给团长发消息（`chat/send`），服务端用团长
//! 的人设/话术库/FAQ 生成 `autoReply`。PC 端多个微信私聊 / 群聊 / iLink 渠道就是
//! **多个团员**的消息来源，不管多少个，**都只向同一个团长发消息**：全部汇聚到
//! 「当前登录账号 ↔ 团长」这唯一一个会话，由团长 AI 统一回复，再按渠道原路分发。
//!
//! 因此**不需要配置任何团员账号信息**（手机号/密码）。身份、sessionId、登录态
//! 恢复、配额、AI 回复解析全部由 [`crate::ai_gateway`] 统一处理；本模块只负责
//! 触发规则过滤、冷却/反检测、以及「哪个渠道进来的就回哪个渠道」的原路分发。
//!
//! 数据流：
//!   [微信私聊/群聊/iLink] → 规则过滤 → ai_gateway::ask_leader(向团长发消息)
//!     → 团长 AI 回复 → 原路回发（wxauto / iLink 对应 bot）
//!
//! 失败语义：配额用尽 / 人工接管 / 登录态过期 / 身份不符 —— 全部记入日志并推前端事件。

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
    /// 兼容旧版 config.json（0.2.x 曾要求填团员账号）。新版本绝不读取、不要求：
    /// AI 身份直接用当前登录连接；这两个字段仅保证旧配置能反序列化。
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
    /// 交互优化：私聊「真人思考」随机延迟下限（秒）
    pub think_min_sec: f64,
    /// 交互优化：私聊「真人思考」随机延迟上限（秒）
    pub think_max_sec: f64,
    /// 交互优化：群聊「真人思考」随机延迟下限（秒）
    pub group_think_min_sec: f64,
    /// 交互优化：群聊「真人思考」随机延迟上限（秒）
    pub group_think_max_sec: f64,
    /// 服务端写故障（如 D1 当日写入配额打满）时，是否给客户回一条中性兜底话术，
    /// 避免「消息进来了但完全没有回应」的黑洞。仅在该类故障下触发，其它失败仍不回复。
    pub fallback_on_fault: bool,
    /// 兜底话术（不涉业务判断，可在界面修改）
    pub fallback_text: String,
    /// 同一会话兜底回复最小间隔（秒）：故障持续期间最多每 N 秒回一条，防刷屏
    pub fallback_cooldown_sec: u64,
    // ---- 发送安全（0.4.x）：只收敛微信 PC 通道的行为特征，不做任何对抗检测 ----
    // 说明：iLink 是腾讯官方 Bot 通道，不属于「模拟人操作 PC 客户端」，不受这些限制。
    /// 微信通道每小时最多发起多少次发送（0 = 不限）。含失败尝试，避免故障期反复打 UI。
    pub hourly_send_budget: u32,
    /// 允许自动发送的本地时段起点 "HH:MM"（空 = 不限）
    pub active_hours_start: String,
    /// 允许自动发送的本地时段终点 "HH:MM"（空 = 不限）；起点 > 终点表示跨午夜
    pub active_hours_end: String,
    /// 连续回发失败达到该次数后急停微信通道（0 = 关闭急停）
    pub fail_streak_threshold: u32,
    /// 急停时长（秒）
    pub pause_sec: u64,
    /// 仅当目标会话已经是微信当前前台会话时才发送：不抢焦点、不点侧栏切会话。
    /// 关闭后每条回复都会 SetForegroundWindow + UIA 切会话，行为特征显著、易被客户端识别。
    pub require_foreground: bool,
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
            think_min_sec: 1.5,
            think_max_sec: 4.0,
            group_think_min_sec: 2.5,
            group_think_max_sec: 6.0,
            fallback_on_fault: true,
            fallback_text: "消息已收到，稍后回复您～".into(),
            fallback_cooldown_sec: 600,
            // 发送安全默认值：保守但不至于完全停摆。所有项都能在「回复规则 → 发送安全」改。
            hourly_send_budget: 30,
            active_hours_start: "09:00".into(),
            active_hours_end: "22:00".into(),
            fail_streak_threshold: 2,
            pause_sec: 1800,
            require_foreground: true,
        }
    }
}

pub struct AutoReplyState {
    pub config: Mutex<AutoReplyConfig>,
    running: Mutex<bool>,
    /// 最近日志（环形，最多 200 条）
    pub logs: Mutex<Vec<Value>>,
    /// peer -> 上次回复时间戳(ms)
    last_reply: Mutex<HashMap<String, i64>>,
    /// 群内频控：群 -> 窗口内回复时间戳列表(ms)
    group_rate: Mutex<HashMap<String, Vec<i64>>>,
    /// 渠道|会话 -> 上次「故障兜底」回复时间戳(ms)
    last_fallback: Mutex<HashMap<String, i64>>,
    /// 微信通道近 1h 实际发起发送的时间戳(ms)：发送预算滑动窗口
    wx_send_history: Mutex<Vec<i64>>,
    /// 微信通道连续回发失败次数（成功一次即清零）
    wx_fail_streak: Mutex<u32>,
    /// 微信通道急停截止时间戳(ms)，0 = 未急停
    wx_paused_until: Mutex<i64>,
    /// 闸门告警节流：上次 warn 时间戳(ms)，避免故障期每条消息刷屏
    last_gate_warn: Mutex<i64>,
    /// 最近一次被闸门拦下的原因（状态页展示）
    last_gate_reason: Mutex<String>,
}

/// 毫秒时间戳 → "HH:MM:SS.mmm"（本机本地时间）。
/// 之前 autoReply 日志直接写 epoch 毫秒、wxauto 日志用 epoch%86400（=UTC），
/// 用户排查「微信有消息没反应」时看到的 14:59 与本机 22:59 对不上。
/// Windows 下用 GetLocalTime 取本地墙钟，非 Windows 回退 UTC 时分。
pub fn fmt_ts(ms: i64) -> String {
    #[cfg(windows)]
    {
        #[repr(C)]
        #[allow(non_snake_case)]
        struct SystemTime {
            wYear: u16, wMonth: u16, wDayOfWeek: u16, wDay: u16,
            wHour: u16, wMinute: u16, wSecond: u16, wMilliseconds: u16,
        }
        extern "system" {
            fn GetLocalTime(lp_system_time: *mut SystemTime);
        }
        let mut st = SystemTime {
            wYear: 0, wMonth: 0, wDayOfWeek: 0, wDay: 0,
            wHour: 0, wMinute: 0, wSecond: 0, wMilliseconds: 0,
        };
        unsafe { GetLocalTime(&mut st) };
        let _ = ms;
        return format!(
            "{:02}:{:02}:{:02}.{:03}",
            st.wHour, st.wMinute, st.wSecond, st.wMilliseconds
        );
    }
    #[cfg(not(windows))]
    {
        let secs = ms / 1000;
        let millis = (ms % 1000) as u32;
        format!(
            "{:02}:{:02}:{:02}.{:03}",
            (secs / 3600) % 24,
            (secs / 60) % 60,
            secs % 60,
            millis
        )
    }
}

impl AutoReplyState {
    pub fn new() -> Self {
        AutoReplyState {
            config: Mutex::new(AutoReplyConfig::default()),
            running: Mutex::new(false),
            logs: Mutex::new(Vec::new()),
            last_reply: Mutex::new(HashMap::new()),
            group_rate: Mutex::new(HashMap::new()),
            last_fallback: Mutex::new(HashMap::new()),
            wx_send_history: Mutex::new(Vec::new()),
            wx_fail_streak: Mutex::new(0),
            wx_paused_until: Mutex::new(0),
            last_gate_warn: Mutex::new(0),
            last_gate_reason: Mutex::new(String::new()),
        }
    }

    // ---- 发送安全闸门（0.4.x）------------------------------------------------
    // 只针对微信 PC 通道：iLink 是官方 Bot 通道，不存在「模拟人操作客户端」的特征。
    // 这里全是行为侧收敛，不做 Hook / 注入 / 抹输入标志等对抗检测实现。

    /// 只读检查：返回 Some(原因) = 本条消息不进入发送流程。
    /// 顺序：急停 → 允许时段 → 每小时预算。放在冷却/思考延迟/AI 请求之前，
    /// 故障期既不抢微信窗口，也不白打后端 chat/send（后端写配额同样吃紧）。
    fn send_gate(&self, cfg: &AutoReplyConfig, ch: &str, now: i64) -> Option<String> {
        if ch != "wx" {
            return None;
        }
        let until = *self.wx_paused_until.lock().unwrap();
        if until > now {
            return Some(format!(
                "微信通道已急停（剩余 {}s）：连续回发失败，先确认微信登录态，再点「解除急停」",
                (until - now) / 1000 + 1
            ));
        }
        if let (Some(s), Some(e)) = (
            parse_hhmm(&cfg.active_hours_start),
            parse_hhmm(&cfg.active_hours_end),
        ) {
            let cur = local_minute_now();
            if !within_active_hours(cur, s, e) {
                return Some(format!(
                    "本机时间 {} 不在允许发送时段 {}–{}",
                    fmt_minute(cur), cfg.active_hours_start, cfg.active_hours_end
                ));
            }
        }
        if cfg.hourly_send_budget > 0
            && self.budget_used(now) >= cfg.hourly_send_budget as usize
        {
            return Some(format!(
                "近 1 小时已发起 {} 次微信发送，触及预算上限 {}",
                self.budget_used(now), cfg.hourly_send_budget
            ));
        }
        None
    }

    /// 滑动窗口：近 1 小时实际发起的微信发送次数（含失败尝试）。
    fn budget_used(&self, now: i64) -> usize {
        let mut h = self.wx_send_history.lock().unwrap();
        h.retain(|t| now - *t < 3_600_000);
        h.len()
    }

    /// 真正调用发送前原子占位，防止并发消息一起挤过预算检查；超预算返回 false。
    fn reserve_send(&self, cfg: &AutoReplyConfig, ch: &str, now: i64) -> bool {
        if ch != "wx" {
            return true;
        }
        let mut h = self.wx_send_history.lock().unwrap();
        h.retain(|t| now - *t < 3_600_000);
        if cfg.hourly_send_budget > 0 && h.len() >= cfg.hourly_send_budget as usize {
            return false;
        }
        h.push(now);
        true
    }

    /// 记录微信发送结果；返回 Some(急停截止 ms) 表示本次触发急停（供调用方打日志）。
    /// 成功一次即清零连败计数，避免偶发一次失败就累计到急停。
    fn note_wx_send(&self, cfg: &AutoReplyConfig, ch: &str, ok: bool, now: i64) -> Option<i64> {
        if ch != "wx" {
            return None;
        }
        let mut streak = self.wx_fail_streak.lock().unwrap();
        if ok {
            *streak = 0;
            return None;
        }
        *streak += 1;
        if cfg.fail_streak_threshold > 0 && *streak >= cfg.fail_streak_threshold {
            *streak = 0;
            let until = now + (cfg.pause_sec.max(1) as i64) * 1000;
            *self.wx_paused_until.lock().unwrap() = until;
            return Some(until);
        }
        None
    }

    /// 人工确认微信恢复后解除急停（前端「解除急停」按钮）。
    pub fn resume_wx(&self) {
        *self.wx_paused_until.lock().unwrap() = 0;
        *self.wx_fail_streak.lock().unwrap() = 0;
    }

    /// 闸门跳过统一留痕：30s 内最多一条 warn，其余 debug，避免故障期日志刷屏。
    fn log_gate(&self, app: &AppHandle, to: &str, reason: &str) {
        *self.last_gate_reason.lock().unwrap() = format!("「{}」{}", to, reason);
        let now = now_ms();
        let warn = {
            let mut last = self.last_gate_warn.lock().unwrap();
            let w = now - *last >= 30_000;
            if w {
                *last = now;
            }
            w
        };
        self.log(
            app,
            if warn { "warn" } else { "debug" },
            &format!("发送安全：已跳过「{}」：{}", to, reason),
            json!({"peer": to, "reason": reason, "kind": "send_gate"}),
        );
    }

    /// 状态页用：当前闸门是否放行、预算用量、急停剩余、最近一次拦截原因。
    pub fn safety_status(&self) -> Value {
        let cfg = self.config.lock().unwrap().clone();
        let now = now_ms();
        let until = *self.wx_paused_until.lock().unwrap();
        let used = self.budget_used(now);
        let streak = *self.wx_fail_streak.lock().unwrap();
        let reason = self.last_gate_reason.lock().unwrap().clone();
        let cur = local_minute_now();
        let in_hours = match (
            parse_hhmm(&cfg.active_hours_start),
            parse_hhmm(&cfg.active_hours_end),
        ) {
            (Some(s), Some(e)) => within_active_hours(cur, s, e),
            _ => true,
        };
        json!({
            "paused": until > now,
            "paused_remaining_sec": if until > now { (until - now) / 1000 + 1 } else { 0 },
            "hourly_budget": cfg.hourly_send_budget,
            "budget_used": used,
            "local_time": fmt_minute(cur),
            "active_hours": format!("{}–{}", cfg.active_hours_start, cfg.active_hours_end),
            "within_active_hours": in_hours,
            "fail_streak": streak,
            "fail_streak_threshold": cfg.fail_streak_threshold,
            "pause_sec": cfg.pause_sec,
            "require_foreground": cfg.require_foreground,
            "last_reason": reason,
        })
    }

    /// 服务端写故障（读正常、写全挂）时取一条兜底话术；其它任何失败原因都返回 None，
    /// 绝不擅自替团长回复业务内容。同一会话按 fallback_cooldown_sec 限频。
    fn fault_fallback(&self, reason: &str, key: &str) -> Option<String> {
        if !reason.contains(crate::bbt::FAULT_TAG) {
            return None;
        }
        let cfg = self.config.lock().unwrap().clone();
        if !cfg.fallback_on_fault || cfg.fallback_text.trim().is_empty() {
            return None;
        }
        let now = now_ms();
        let gap = (cfg.fallback_cooldown_sec as i64) * 1000;
        let allowed = {
            let mut m = self.last_fallback.lock().unwrap();
            let ok = m.get(key).map(|t| now - *t >= gap).unwrap_or(true);
            if ok {
                m.insert(key.to_string(), now);
            }
            ok
        };
        if allowed {
            Some(cfg.fallback_text.clone())
        } else {
            None
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
            let line = format!("[{}] {} {}\n", fmt_ts(now_ms()), level, msg);
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

/// "HH:MM" → 当天分钟数；空串 / 非法 → None（视为「不限时段」）。
fn parse_hhmm(s: &str) -> Option<u32> {
    let t = s.trim();
    if t.is_empty() {
        return None;
    }
    let (h, m) = t.split_once(':').unwrap_or((t, "0"));
    let h: u32 = h.trim().parse().ok()?;
    let m: u32 = m.trim().parse().ok()?;
    if h > 23 || m > 59 {
        return None;
    }
    Some(h * 60 + m)
}

/// 时段判定：起止相等 = 全天；起点 > 终点 = 跨午夜（如 22:00–08:00）。
fn within_active_hours(now: u32, start: u32, end: u32) -> bool {
    if start == end {
        return true;
    }
    if start < end {
        now >= start && now < end
    } else {
        now >= start || now < end
    }
}

/// 本机本地时间的「当天分钟数」。Windows 直接读 GetLocalTime（不用硬编码时区，
/// 也不引 chrono）；其它平台回退 UTC 分钟。
fn local_minute_now() -> u32 {
    #[cfg(windows)]
    {
        #[repr(C)]
        #[allow(non_snake_case)]
        struct SystemTime {
            wYear: u16, wMonth: u16, wDayOfWeek: u16, wDay: u16,
            wHour: u16, wMinute: u16, wSecond: u16, wMilliseconds: u16,
        }
        extern "system" {
            fn GetLocalTime(lp_system_time: *mut SystemTime);
        }
        let mut st = SystemTime {
            wYear: 0, wMonth: 0, wDayOfWeek: 0, wDay: 0,
            wHour: 0, wMinute: 0, wSecond: 0, wMilliseconds: 0,
        };
        unsafe { GetLocalTime(&mut st) };
        return st.wHour as u32 * 60 + st.wMinute as u32;
    }
    #[cfg(not(windows))]
    {
        ((now_ms() / 60_000) % 1440) as u32
    }
}

fn fmt_minute(m: u32) -> String {
    format!("{:02}:{:02}", m / 60, m % 60)
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

    // 所有「静默忽略」点统一留痕：以前这些分支直接 return，用户遇到
    // 「微信有消息但没反应」时日志一片空白，无法分辨是消息没进来还是被过滤。
    macro_rules! skip {
        ($reason:expr) => {{
            ar.log(
                app,
                "debug",
                &format!("已忽略「{}」：{}", to, $reason),
                json!({"peer": to.clone(), "kind": msg.kind.clone(), "reason": $reason}),
            );
            return Ok(());
        }};
    }

    // ---- 触发过滤 ----
    let trigger = match msg.kind.as_str() {
        // text：纯文本；image：图片经离线 OCR 得到的文本（sidecar 已转成文字）
        "text" | "image" => true,
        // voice：语音经离线转写得到的文本（需开启 reply_voice）
        "voice" => cfg.reply_voice && !text.trim().is_empty(),
        _ => false,
    };
    if !trigger || to.is_empty() {
        skip!("消息类型不触发（仅文本/图片，语音需开启 reply_voice）或会话为空");
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
            "off" => skip!("群回复已关闭（group_mode=off）"),
            "at" => {
                if !at_self && !at_triggered(&text, &cfg.at_names, &cfg.group_keywords) {
                    skip!("群消息未 @ 自己且未命中关键词");
                }
            }
            "all" => {}
            _ => skip!("未知群模式"),
        }
    } else if !cfg.private_reply {
        skip!("私聊自动回复已关闭");
    }
    if text.trim().is_empty() {
        skip!("消息文本为空（图片 OCR / 语音转写可能失败）");
    }
    // 图片消息：文本来自离线 OCR，加轻量语境说明让团长 AI 知道这是客户图片里的
    // 文字（纯文本消息不加，保持客户发言原样），AI 据此能给出更得体的回复。
    if msg.kind == "image" {
        text = format!("（客户发来一张图片，以下是图片中识别出的文字）\n{}", text);
    }
    if cfg.strip_at_prefix {
        text = strip_at(&text);
    }
    if text.trim().is_empty() {
        skip!("去掉 @ 前缀后文本为空");
    }

    // ---- 发送安全闸门（只管微信 PC 通道）----
    // 必须放在冷却 / 思考延迟 / 团长 AI 请求之前：急停、非作息时段或超预算时，
    // 既不抢微信窗口，也不白打后端 chat/send（今日 D1 写配额本来就吃紧）。
    // iLink 是腾讯官方 Bot 通道，不属于「模拟人操作 PC 客户端」，不受这些限制。
    if ch == "wx" {
        if let Some(reason) = ar.send_gate(&cfg, ch, now_ms()) {
            ar.log_gate(app, &to, &reason);
            return Ok(());
        }
        // 仅当目标会话已经是微信当前前台会话才发送：不 SetForegroundWindow、
        // 不点侧栏切会话。微信 4.x 非当前会话本来就发不出去（SEARCH_BOX_FOUND=False），
        // 与其每条消息都抢焦点留下明显特征，不如明确跳过并在状态条提示。
        if cfg.require_foreground {
            match crate::wxauto::check_foreground(app, &to).await {
                Ok(v) if v.ok => {}
                Ok(v) => {
                    let cur = if !v.foreground {
                        "微信窗口不在前台".to_string()
                    } else if v.current.is_empty() {
                        "未读到当前会话名".to_string()
                    } else {
                        v.current
                    };
                    let reason = format!(
                        "目标会话「{}」未在前台（当前：{}）：请手动切到该会话；本条不发送以免抢焦点/自动切会话",
                        v.target, cur
                    );
                    ar.log_gate(app, &to, &reason);
                    return Ok(());
                }
                Err(e) => {
                    let reason = format!("前台会话探测失败（{}），安全起见本条不发送", e);
                    ar.log_gate(app, &to, &reason);
                    return Ok(());
                }
            }
        }
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
                    ar.log(
                        app,
                        "debug",
                        &format!("已忽略「{}」：冷却中（约 {}s 内已回复过）", to, cfg.cooldown_sec),
                        json!({"peer": to, "cooldown_sec": cfg.cooldown_sec}),
                    );
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
                ar.log(
                    app,
                    "debug",
                    &format!("已忽略「{}」：群内频控（{}s 内最多 {} 条）", to, cfg.group_rate_window_sec, cfg.group_rate_max_replies),
                    json!({"peer": to}),
                );
                return Ok(());
            }
            e.push(now);
            if gr.len() > 500 {
                gr.clear();
            }
        }

        // ---- 反检测：随机思考延迟（模拟真人看到消息后反应时间）----
        // 0.4.0 起可在「互动消息 → 渠道配置 / 交互优化」里调整；配置随本地
        // config.json 持久化，升级/重启后保持。设为 0 则即时回复（自测常用）。
        let (think_min, think_max) = if is_group {
            (cfg.group_think_min_sec.max(0.0), cfg.group_think_max_sec.max(cfg.group_think_min_sec.max(0.0)))
        } else {
            (cfg.think_min_sec.max(0.0), cfg.think_max_sec.max(cfg.think_min_sec.max(0.0)))
        };
        let think_secs =
            think_min + rand_interval(think_max - think_min) * (1.0 + text.len() as f64 / 100.0).min(2.0);
        if think_secs > 0.05 {
            ar.log(app, "debug", &format!("反检测：思考延迟 {:.1}s", think_secs), json!({"peer": to}));
            tokio::time::sleep(Duration::from_secs_f64(think_secs)).await;
        }
    }

    // ---- 图片支付校验（确定性，优先于 AI）----
    // OCR 识别出付款截图时，按发消息这位团员的订单金额核验：一致回「收到 感谢X」、
    // 不一致回「貌似有些不太对，辛苦核实下付款人和金额」；非支付截图 / 找不到该团员
    // 订单 / 订单接口无权限 → 返回 None，继续走下面的 AI 流程。仅支付截图会打网络。
    let payment_reply: Option<String> = if msg.kind == "image" {
        match crate::payment_verify::verify_image(app, &text, &to, &msg.sender, is_group).await {
            Some(v) => {
                // 把核验结果（金额比对）写进日志，便于排查付款核验；字段被读取也消除 dead-code 警告
                ar.log(
                    app,
                    if v.matched { "info" } else { "warn" },
                    &format!(
                        "[{}] 图片支付校验：团员付款 ¥{:.2} {} 订单合计 ¥{:.2} → {}",
                        ch,
                        v.paid,
                        if v.matched { "=" } else { "≠" },
                        v.expected,
                        v.reply
                    ),
                    json!({
                        "channel": ch, "to": to, "kind": "payment_verify",
                        "matched": v.matched, "paid": v.paid, "expected": v.expected,
                        "reply": v.reply
                    }),
                );
                Some(v.reply)
            }
            None => None,
        }
    } else {
        None
    };

    // ---- 统一 AI 网关：不管哪个渠道、哪个团员，消息都只向同一个团长发 ----
    // 身份 / sessionId / 登录态恢复 / AI 回复解析全部由网关处理；
    // route 仅作日志标记（渠道|会话），不参与身份选择。
    let route = format!("{}|{}", ch, to);
    // reply_kind: "payment_verify" | "ai" | "fallback"，用于日志区分回复来源
    let (reply, reply_kind) = if let Some(pr) = payment_reply {
        ar.log(
            app,
            "info",
            &format!("[{}] 图片支付校验已判定（略过团长 AI）：{}", ch, pr),
            json!({"channel": ch, "to": to, "reply": pr, "kind": "payment_verify"}),
        );
        (pr, "payment_verify")
    } else {
        match crate::ai_gateway::ask_leader(app, &text, &route).await {
            Ok(ans) => (ans.reply, "ai"),
            Err(e) => {
                // 「微信有消息但没反应」的可观测性：把会话名、渠道、失败原因一起写进
                // 事件流和 autoreply.log，前端 ⑤ 事件流按 to/peer 都能定位到这次消息。
                ar.log(
                    app,
                    "warn",
                    &format!("本次 AI 未回复「{}」（{}）：{}", to, ch, e),
                    json!({"channel": ch, "to": to, "peer": to, "text": text, "reason": e}),
                );
                // 后端写故障是确定性、持续性的：AI 一定回不来，与其让客户消息黑洞，
                // 不如回一句中性话术。其它失败（配额/权限/人工接管）一律保持不回复。
                let key = format!("{}|{}", ch, to);
                match ar.fault_fallback(&e, &key) {
                    Some(fb) => {
                        ar.log(
                            app,
                            "info",
                            &format!("[{}] 服务端写故障 → 兜底回复「{}」：{}", ch, to, fb),
                            json!({"channel": ch, "to": to, "reply": fb, "kind": "fallback", "reason": e}),
                        );
                        (fb, "fallback")
                    }
                    None => return Err(e),
                }
            }
        }
    };

    // ---- 原路回发 ----
    // 真正发之前再原子占位一次：上面的闸门是只读检查，并发的多条消息可能一起挤过来。
    if !ar.reserve_send(&cfg, ch, now_ms()) {
        ar.log_gate(app, &to, "发送预算已被并发消息占满，本条不发送");
        return Ok(());
    }
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
        "wx" => crate::wxauto::send_text_ex(app, &to, &reply, cfg.require_foreground).await,
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

    // 连续回发失败 = 微信登录态/窗口大概率已经异常（用户报过「被要求重新登录」）。
    // 达到阈值立刻急停微信通道，避免继续对着失效客户端反复注入输入；iLink 不受影响。
    let send_ok = send_res.is_ok();
    // 前台会话被切走属于「主动跳过」，不是客户端故障，不计入连败急停。
    let fg_mismatch = send_res
        .as_ref()
        .err()
        .map(|e| e.contains("NOT_FOREGROUND"))
        .unwrap_or(false);
    let pause_until = if fg_mismatch {
        None
    } else {
        ar.note_wx_send(&cfg, ch, send_ok, now_ms())
    };
    if let Some(until) = pause_until {
        ar.log(
            app,
            "warn",
            &format!(
                "微信通道连续 {} 次回发失败 → 已自动急停 {}s（约 {} 秒后自动恢复；也可在界面点「解除急停」）",
                cfg.fail_streak_threshold,
                cfg.pause_sec,
                (until - now_ms()) / 1000
            ),
            json!({"channel": ch, "to": to, "kind": "send_pause", "paused_until": until}),
        );
    }

    match send_res {
        Ok(_) => {
            if ch != "test" {
                let kind_label = match reply_kind {
                    "payment_verify" => "支付校验",
                    "fallback" => "故障兜底",
                    _ => "AI",
                };
                ar.log(
                    app,
                    "info",
                    &format!(
                        "[{}]已自动回复 {}{} ← {}({} 字)",
                        if ch == "wx" { "微信" } else { "iLink" },
                        ch_label,
                        if is_group && !msg.sender.is_empty() { format!("·{}", msg.sender) } else { String::new() },
                        kind_label,
                        reply.chars().count()
                    ),
                    json!({
                        "channel": ch, "to": to, "question": text,
                        "reply": reply, "kind": reply_kind
                    }),
                );
            }
            Ok(())
        }
        Err(e) => {
            if fg_mismatch {
                // 回复已经生成，但微信窗口/会话在等待 AI 期间被切走：不是客户端故障，
                // 明确写清「回复未发出」，且不累计到连败急停。
                ar.log(
                    app,
                    "warn",
                    &format!("回复已生成但目标会话已不在微信前台，本条未发送（{}）：请保持该会话在前台", e),
                    json!({"channel": ch, "to": to, "reply": reply, "kind": "foreground_skip"}),
                );
            } else {
                ar.log(app, "warn", &format!("回发失败({}): {}", ch, e), json!({"channel": ch, "to": to, "reply": reply}));
            }
            Err(e)
        }
    }
}

// ============================================================================
//  AI 消息自测（核心业务一键体检，tauri dev / 生产均可反复验证）
// ============================================================================

/// 分步体检 AI 消息网关：登录态 → 身份/绑定团长 → 配额 →（可选）真实发一条并解析 AI 回复。
/// `send=false` 只做链路检查不消耗平台配额；`send=true` 真发一条测试问题并返回 AI 回复原文。
pub async fn self_test(app: &AppHandle, question: &str, send: bool) -> Value {
    let Some(ar) = app.try_state::<Arc<AutoReplyState>>() else {
        return json!({ "ok": false, "steps": [ { "name": "模块", "ok": false, "detail": "自动回复模块未初始化" } ], "reply": "" });
    };
    let mut steps: Vec<Value> = Vec::new();
    macro_rules! step {
        ($name:expr, $ok:expr, $detail:expr) => {{
            let ok: bool = $ok;
            let detail: String = $detail;
            steps.push(json!({ "name": $name, "ok": ok, "detail": detail }));
            ar.log(
                app,
                if ok { "info" } else { "warn" },
                &format!("[自测]{}：{}", $name, detail),
                json!({}),
            );
            ok
        }};
    }

    // ① 登录态（网关统一探测）。**不做 role 预检**：PC 端登录团长账号是正常态，
    //    每个微信私聊 / 群聊 / iLink 渠道各代表 1 个团员，发信主体是渠道而非登录账号。
    let st = crate::ai_gateway::status(app).await;
    let logged = st.get("logged_in").and_then(|v| v.as_bool()).unwrap_or(false);
    let hint = st.get("hint").and_then(|v| v.as_str()).unwrap_or("").to_string();
    if !step!("登录态", logged, hint.clone()) {
        return json!({ "ok": false, "steps": steps, "reply": "" });
    }

    // ② 团长 AI 代聊是否开启
    let ai_on = st.get("ai_enabled").and_then(|v| v.as_bool()).unwrap_or(true);
    if !step!("团长 AI 代聊", ai_on, if ai_on { "已开启".into() } else { "团长后台未开启 AI 代聊".into() }) {
        return json!({ "ok": false, "steps": steps, "reply": "" });
    }

    // ③ 今日配额（0 = 已用尽）
    let left = st.get("quota").and_then(|q| q.get("left")).and_then(|v| v.as_i64());
    let quota_ok = left.map(|n| n > 0).unwrap_or(true);
    step!(
        "今日配额",
        quota_ok,
        left.map(|n| format!("剩余 {} 条", n)).unwrap_or_else(|| "平台未返回配额（不阻断）".into())
    );

    // ④ 渠道 = 团员（仅展示，不阻断：渠道只决定发信主体与回发路由）
    let chs = st.get("channels").cloned().unwrap_or(json!({}));
    let total = chs.get("total").and_then(|v| v.as_i64()).unwrap_or(0);
    step!(
        "渠道 / 团员",
        true,
        format!(
            "共 {} 个渠道 = {} 个团员（每个渠道各 1 个团员）：微信私聊 {} · 群聊 {} · iLink {}（已绑定映射 {}）",
            total,
            total,
            chs.get("wx_private").and_then(|v| v.as_i64()).unwrap_or(0),
            chs.get("wx_group").and_then(|v| v.as_i64()).unwrap_or(0),
            chs.get("ilink").and_then(|v| v.as_i64()).unwrap_or(0),
            chs.get("mapped").and_then(|v| v.as_i64()).unwrap_or(0)
        )
    );

    if !send {
        let ok = steps.iter().all(|s| s.get("ok").and_then(|v| v.as_bool()).unwrap_or(false));
        return json!({ "ok": ok, "steps": steps, "reply": "" });
    }

    // ⑤ 真实发一条「向团长发消息」，拿 AI 回复
    let q = if question.trim().is_empty() { "你好，在吗？" } else { question.trim() };
    match crate::ai_gateway::ask_leader(app, q, "self_test").await {
        Ok(ans) => {
            step!("发送测试问题", true, format!("已受理，剩余配额 {:?}", ans.quota_left));
            step!("团长 AI 回复", true, format!("{} 字", ans.reply.chars().count()));
            json!({ "ok": true, "steps": steps, "reply": ans.reply })
        }
        Err(e) => {
            step!("团长 AI 回复", false, e);
            json!({ "ok": false, "steps": steps, "reply": "" })
        }
    }
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fault_fallback_only_on_backend_write_fault() {
        let st = AutoReplyState::new();
        // 配额 / 凭据 / 权限等业务失败绝不擅自替团长回复
        assert!(st.fault_fallback("密码错误", "wx|张三").is_none());
        assert!(st.fault_fallback("当前团员今日 AI 配额已用尽", "wx|张三").is_none());
        assert!(st.fault_fallback("无权限", "wx|张三").is_none());

        assert_eq!(
            st.fault_fallback("服务端写入故障（熔断中）：服务器繁忙", "wx|张三"),
            Some("消息已收到，稍后回复您～".to_string())
        );
        // 同一会话冷却期内不刷屏，换个会话仍可兜底
        assert!(st.fault_fallback("服务端写入故障", "wx|张三").is_none());
        assert!(st.fault_fallback("服务端写入故障", "wx|李四").is_some());

        // 用户可在界面关掉兜底，立即生效
        st.config.lock().unwrap().fallback_on_fault = false;
        assert!(st.fault_fallback("服务端写入故障", "wx|王五").is_none());
    }

    #[test]
    fn parse_hhmm_and_active_hours() {
        assert_eq!(parse_hhmm("09:00"), Some(540));
        assert_eq!(parse_hhmm("22:30"), Some(1350));
        assert_eq!(parse_hhmm("9:0"), Some(540));
        assert_eq!(parse_hhmm(""), None);
        assert_eq!(parse_hhmm("25:00"), None);
        assert_eq!(fmt_minute(1351), "22:31");
        // 白天窗口
        let (s, e) = (9 * 60, 22 * 60);
        assert!(within_active_hours(12 * 60, s, e));
        assert!(!within_active_hours(8 * 60, s, e));
        assert!(!within_active_hours(22 * 60, s, e)); // 终点不含
        // 跨午夜窗口 22:00–08:00
        assert!(within_active_hours(23 * 60, 22 * 60, 8 * 60));
        assert!(within_active_hours(7 * 60, 22 * 60, 8 * 60));
        assert!(!within_active_hours(12 * 60, 22 * 60, 8 * 60));
        // 起止相等 = 全天
        assert!(within_active_hours(3 * 60, 0, 0));
    }

    #[test]
    fn send_gate_blocks_pause_hours_and_budget() {
        let st = AutoReplyState::new();
        let now = now_ms();
        let mut cfg = st.config.lock().unwrap().clone();

        // ① 急停：只拦微信，iLink 官方通道照常放行
        *st.wx_paused_until.lock().unwrap() = now + 60_000;
        assert!(st.send_gate(&cfg, "wx", now).unwrap().contains("急停"));
        assert!(st.send_gate(&cfg, "ilink", now).is_none());
        *st.wx_paused_until.lock().unwrap() = 0;

        // ② 作息：构造一个必然不含「当前时刻」的 60 分钟窗口
        let cur = local_minute_now();
        cfg.active_hours_start = fmt_minute((cur + 60) % 1440);
        cfg.active_hours_end = fmt_minute((cur + 120) % 1440);
        assert!(st.send_gate(&cfg, "wx", now).unwrap().contains("时段"));
        assert!(st.send_gate(&cfg, "ilink", now).is_none());
        cfg.active_hours_start.clear();
        cfg.active_hours_end.clear(); // 留空 = 不限

        // ③ 预算：滑窗只算近 1 小时
        cfg.hourly_send_budget = 2;
        *st.wx_send_history.lock().unwrap() = vec![now - 1_000, now - 2_000];
        assert!(st.send_gate(&cfg, "wx", now).unwrap().contains("预算"));
        *st.wx_send_history.lock().unwrap() = vec![now - 3_700_000, now - 3_800_000];
        assert!(st.send_gate(&cfg, "wx", now).is_none());
        assert_eq!(st.budget_used(now), 0, "过期记录应被清出滑窗");

        // ④ 发送前原子占位：第 3 次被挡住；非微信通道不占预算
        assert!(st.reserve_send(&cfg, "wx", now));
        assert!(st.reserve_send(&cfg, "wx", now + 1));
        assert!(!st.reserve_send(&cfg, "wx", now + 2));
        assert!(st.reserve_send(&cfg, "ilink", now + 3));
        assert_eq!(st.budget_used(now + 3), 2);
    }

    #[test]
    fn consecutive_failures_pause_wx_channel_and_resume() {
        let st = AutoReplyState::new();
        let now = now_ms();
        let mut cfg = st.config.lock().unwrap().clone();
        cfg.fail_streak_threshold = 2;
        cfg.pause_sec = 1800;
        // 与运行时刻解耦：时段闸门另有用例覆盖，这里只验证急停状态机
        cfg.active_hours_start.clear();
        cfg.active_hours_end.clear();

        assert!(st.note_wx_send(&cfg, "wx", false, now).is_none(), "首次失败不急停");
        let until = st.note_wx_send(&cfg, "wx", false, now + 1).expect("第二次失败应触发急停");
        assert!(until > now + 1);
        assert!(st.send_gate(&cfg, "wx", now + 2).unwrap().contains("急停"));
        assert!(st.note_wx_send(&cfg, "ilink", false, now + 3).is_none(), "iLink 失败不累计");

        st.resume_wx();
        assert_eq!(*st.wx_paused_until.lock().unwrap(), 0);
        assert_eq!(*st.wx_fail_streak.lock().unwrap(), 0);
        assert!(st.send_gate(&cfg, "wx", now + 4).is_none());

        // 成功一次清零连败计数
        assert!(st.note_wx_send(&cfg, "wx", false, now).is_none());
        assert!(st.note_wx_send(&cfg, "wx", true, now + 1).is_none());
        assert!(st.note_wx_send(&cfg, "wx", false, now + 2).is_none());
        assert_eq!(*st.wx_fail_streak.lock().unwrap(), 1);

        let s = st.safety_status();
        assert_eq!(s["hourly_budget"], 30);
        assert_eq!(s["require_foreground"], true);
        assert!(s["paused"].as_bool().unwrap() == false);
    }
}
