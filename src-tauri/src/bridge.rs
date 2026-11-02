//! bbt-zb 团长聊天 ↔ iLink 多账号 1 对多桥。
//!
//! 数据流：
//!   [团长] --chat/ownerReply--> bbt-zb 服务器 <--轮询 chat/history-- bridge
//!     bridge 把团长发出的新消息按路由表转发到 N 个 iLink 账号（每账号独立会话单线）
//!   [微信用户] --> iLink 服务器 --getupdates--> 本地会话 --> bridge --chat/ownerReply--> bbt-zb
//!
//! bbt-zb 的 chat/history / ownerReply 的 condition 字段官方契约未完全公开，
//! 这里做宽松解析（多候选字段名），全部可在 bridge 配置里改 action 名与开关。

use crate::bbt::{self, BbtConnection};
use crate::ilink_session::SessionManager;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::HashSet;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tauri::{AppHandle, Manager};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BridgeTarget {
    /// iLink 账号（bot_id）
    pub bot_id: String,
    /// 该账号要送达的微信联系人 user_id
    #[serde(default)]
    pub wechat_user_id: String,
    /// 对应 bbt-zb 会话 id（回传团长消息时用；群发模式下可留空）
    #[serde(default)]
    pub bbt_session_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct BridgeConfig {
    pub enabled: bool,
    /// 轮询 bbt-zb chat 的间隔（秒）
    pub poll_interval_sec: u64,
    pub session_list_action: String,
    pub history_action: String,
    /// 团长对外发消息的 action（chat/ownerReply 或 chat/send）
    pub send_action: String,
    /// 团长在 bbt-zb 发的新消息 → 转发给所有 target
    pub forward_leader_to_bots: bool,
    /// iLink 收到的入站消息 → 回传 bbt-zb 团长会话
    pub forward_inbound_to_bbt: bool,
    /// 定向路由表；空则群发模式跳过
    pub targets: Vec<BridgeTarget>,
}

impl Default for BridgeConfig {
    fn default() -> Self {
        BridgeConfig {
            enabled: false,
            poll_interval_sec: 5,
            session_list_action: "chat/sessionList".into(),
            history_action: "chat/history".into(),
            send_action: "chat/ownerReply".into(),
            forward_leader_to_bots: true,
            forward_inbound_to_bbt: true,
            targets: Vec::new(),
        }
    }
}

pub struct BridgeState {
    pub config: Mutex<BridgeConfig>,
    running: Mutex<bool>,
}

impl BridgeState {
    pub fn new() -> Self {
        BridgeState { config: Mutex::new(BridgeConfig::default()), running: Mutex::new(false) }
    }

    pub fn is_running(&self) -> bool {
        *self.running.lock().unwrap()
    }
}

impl Default for BridgeState {
    fn default() -> Self {
        Self::new()
    }
}

fn msg_id_of(m: &Value) -> String {
    for k in ["id", "msgId", "messageId", "message_id", "msg_id"] {
        if let Some(v) = m.get(k).and_then(|v| v.as_str()) {
            return v.to_string();
        }
        if let Some(v) = m.get(k).and_then(|v| v.as_i64()) {
            return v.to_string();
        }
    }
    String::new()
}

fn session_id_of(s: &Value) -> String {
    for k in ["sessionId", "session_id", "id"] {
        if let Some(v) = s.get(k).and_then(|v| v.as_str()) {
            return v.to_string();
        }
    }
    String::new()
}

fn msg_text_of(m: &Value) -> String {
    for k in ["content", "text", "contentText", "content_text", "msg"] {
        if let Some(v) = m.get(k).and_then(|v| v.as_str()) {
            return v.to_string();
        }
    }
    String::new()
}

/// 是否为「团长自己发出」的消息（多候选字段宽松判断）
fn is_from_leader(m: &Value) -> bool {
    for k in ["fromSelf", "from_self", "self", "isSelf"] {
        if let Some(v) = m.get(k).and_then(|v| v.as_bool()) {
            return v;
        }
    }
    for k in ["senderRole", "sender_role", "role"] {
        if let Some(v) = m.get(k).and_then(|v| v.as_str()) {
            let r = v.to_ascii_lowercase();
            if r == "leader" || r == "owner" || r == "self" {
                return true;
            }
        }
    }
    false
}

fn extract_msg_list(data: &Value) -> Vec<Value> {
    let list = data
        .get("list")
        .and_then(|l| l.as_array())
        .cloned()
        .or_else(|| data.as_array().cloned())
        .or_else(|| data.get("messages").and_then(|l| l.as_array()).cloned())
        .unwrap_or_default();
    list
}

/// 启动桥任务（幂等：已在跑则直接返回）。bridge 循环每轮从 Tauri state
/// 读取最新配置与 bbt-zb 连接（支持启动后才登录）。
pub fn ensure_started(app: AppHandle) {
    {
        let bs = app.state::<BridgeState>();
        let mut r = bs.running.lock().unwrap();
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
            let mgr = Arc::clone(&mgr);
            let Some(bridge) = app.try_state::<Arc<BridgeState>>() else {
                tokio::time::sleep(Duration::from_secs(3)).await;
                continue;
            };
            let mut inbound_rx = mgr.subscribe_inbound();
            let mut seen: HashSet<String> = HashSet::new();
            loop {
                let (enabled, interval, cfg, conn) = {
                    let c = bridge.config.lock().unwrap().clone();
                    let conn = app
                        .try_state::<crate::state::AppState>()
                        .map(|s| s.conn.lock().unwrap().clone())
                        .unwrap_or_default();
                    (c.enabled, c.poll_interval_sec.max(2), c, conn)
                };
                if !enabled {
                    // 关闭态：慢轮询等待开关
                    tokio::time::sleep(Duration::from_secs(3)).await;
                    continue;
                }

                // --- 方向 1：团长消息 → iLink ---
                if cfg.forward_leader_to_bots && !conn.base_url.is_empty() {
                    if let Err(e) = poll_bbt_once(&conn, &cfg, &mgr, &mut seen).await {
                        eprintln!("[bridge] bbt 轮询失败: {}", e);
                    }
                }

                // --- 方向 2：iLink 入站 → bbt-zb（非阻塞收一批） ---
                if cfg.forward_inbound_to_bbt {
                    loop {
                        match inbound_rx.try_recv() {
                            Ok(ev) => {
                                if let Err(e) = forward_inbound(&conn, &cfg, &ev).await {
                                    eprintln!("[bridge] 入站回传失败: {}", e);
                                }
                            }
                            Err(tokio::sync::broadcast::error::TryRecvError::Empty) => break,
                            Err(tokio::sync::broadcast::error::TryRecvError::Lagged(n)) => {
                                eprintln!("[bridge] 入站事件落后 {} 条", n);
                                break;
                            }
                            Err(tokio::sync::broadcast::error::TryRecvError::Closed) => break,
                        }
                    }
                }

                tokio::time::sleep(Duration::from_secs(interval)).await;
            }
        }
    });
}

async fn poll_bbt_once(
    conn: &BbtConnection,
    cfg: &BridgeConfig,
    mgr: &Arc<SessionManager>,
    seen: &mut HashSet<String>,
) -> Result<(), String> {
    // 1) 会话列表
    let result = bbt::call(conn, &cfg.session_list_action, &json!({})).await?;
    let data = result.get("data").cloned().unwrap_or(json!(null));
    let sessions = extract_msg_list(&data);
    if sessions.is_empty() {
        return Ok(());
    }

    // 2) 逐会话拉历史（官方缓存 20s，间隔别太密）
    for s in sessions {
        let sid = session_id_of(&s);
        if sid.is_empty() {
            continue;
        }
        let result = match bbt::call(conn, &cfg.history_action, &json!({"sessionId": sid})).await {
            Ok(r) => r,
            Err(_) => match bbt::call(conn, &cfg.history_action, &json!({"id": sid})).await {
                Ok(r) => r,
                Err(e) => {
                    eprintln!("[bridge] history({}) 失败: {}", sid, e);
                    continue;
                }
            },
        };
        let data = result.get("data").cloned().unwrap_or(json!(null));
        for m in extract_msg_list(&data) {
            let mid = msg_id_of(&m);
            if mid.is_empty() || seen.contains(&mid) {
                continue;
            }
            seen.insert(mid.clone());
            if seen.len() > 5000 {
                // 简单防膨胀
                seen.clear();
                seen.insert(mid);
            }
            if !is_from_leader(&m) {
                continue; // 团员的消息不上报（避免回环），只看团长发出的
            }
            let text = msg_text_of(&m);
            if text.trim().is_empty() {
                continue;
            }
            // 路由：优先按 bbt_session_id 匹配；无匹配 target 时群发到全部 target
            let matched: Vec<&BridgeTarget> = cfg
                .targets
                .iter()
                .filter(|t| t.bbt_session_id == sid || t.bbt_session_id.is_empty())
                .collect();
            let matched: Vec<&BridgeTarget> = if matched.is_empty() {
                cfg.targets.iter().collect()
            } else {
                matched
            };
            for t in matched {
                let results = mgr
                    .broadcast_text(&[t.bot_id.clone()], &t.wechat_user_id, &text)
                    .await;
                eprintln!("[bridge] 团长→iLink 转发 {:?} session={} → {}", results, sid, t.wechat_user_id);
            }
        }
    }
    Ok(())
}

async fn forward_inbound(
    conn: &BbtConnection,
    cfg: &BridgeConfig,
    ev: &Value,
) -> Result<(), String> {
    if conn.base_url.is_empty() {
        return Ok(());
    }
    let rec = ev.get("record").cloned().unwrap_or(json!(null));
    let peer = rec.get("peer").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let kind = rec.get("kind").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let bot_id = rec.get("bot_id").and_then(|v| v.as_str()).unwrap_or("").to_string();
    if peer.is_empty() {
        return Ok(());
    }
    // 找该微信联系人对应的 bbt 会话
    let target = cfg
        .targets
        .iter()
        .find(|t| t.wechat_user_id == peer && t.bot_id == bot_id);
    let sid = match target {
        Some(t) if !t.bbt_session_id.is_empty() => t.bbt_session_id.clone(),
        _ => return Ok(()), // 未配置路由则只留在本地聊天台
    };
    // 组内容：文本直接发；语音带转写文本；图片占位
    let content = match kind.as_str() {
        "text" => rec.get("text").and_then(|v| v.as_str()).unwrap_or("").to_string(),
        "voice" => {
            let t = rec.get("text").and_then(|v| v.as_str()).unwrap_or("");
            if t.is_empty() {
                format!("[语音消息({}ms)]", rec.get("playtime_ms").and_then(|v| v.as_i64()).unwrap_or(0))
            } else {
                format!("[语音转写] {}", t)
            }
        }
        "image" => "[图片消息]".to_string(),
        other => format!("[{}消息]", other),
    };
    if content.trim().is_empty() {
        return Ok(());
    }
    let result = bbt::call(
        conn,
        &cfg.send_action,
        &json!({"sessionId": sid, "content": content}),
    )
    .await?;
    eprintln!("[bridge] iLink({})→bbt 回传 session={} status={}", peer, sid, result.get("status").cloned().unwrap_or(json!(null)));
    Ok(())
}
