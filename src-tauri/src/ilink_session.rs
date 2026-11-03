//! iLink 多账号会话管理：
//!   * 每个扫码登录的微信号 = 一个 Account，对应一个独立 tokio 任务（「单线」：
//!     该账号所有与 iLink 服务器的收发都串行经过这一条线）
//!   * 任务内部并行两路：getupdates 长轮询（独立 poller）+ 命令通道（发送/停止）
//!   * 本地保活：accounts.json 持久化 bot_token/sync_buf，重启自动恢复会话；
//!     messages/{bot_id}.jsonl 追加式本地消息存档
//!   * 对外通过 Tauri event（"ilink-event"）把扫码状态/入站消息/会话状态推给前端

use crate::ilink::{self, IlinkClient, WeixinMessage};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use tauri::{AppHandle, Emitter};
use tokio::sync::{broadcast, mpsc, oneshot};

// ---------------------------------------------------------------------------
// 数据结构
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AccountInfo {
    pub bot_id: String,
    #[serde(default)]
    pub label: String,
    pub bot_token: String,
    #[serde(default)]
    pub base_url: String,
    #[serde(default)]
    pub user_id: String,
    #[serde(default)]
    pub sync_buf: String,
    #[serde(default)]
    pub created_at: i64,
}

/// 本地消息记录（in/out 通用）
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChatRecord {
    pub id: String,
    pub bot_id: String,
    pub dir: String, // "in" / "out"
    pub peer: String,
    pub kind: String, // text / image / voice / file / video / other
    pub text: String,
    #[serde(default)]
    pub media: Option<Value>,
    pub ts: i64,
    #[serde(default)]
    pub status: String, // sent / failed / received
    #[serde(default)]
    pub playtime_ms: i64,
}

pub enum SessionCmd {
    SendText {
        to: String,
        text: String,
        reply: oneshot::Sender<Result<String, String>>,
    },
    SendImage {
        to: String,
        data: Vec<u8>,
        filename: String,
        reply: oneshot::Sender<Result<(), String>>,
    },
    SendVoice {
        to: String,
        data: Vec<u8>,
        encode_type: i64,
        sample_rate: i64,
        playtime_ms: i64,
        reply: oneshot::Sender<Result<(), String>>,
    },
    Stop,
}

struct SessionHandle {
    cmd_tx: mpsc::UnboundedSender<SessionCmd>,
    status: Arc<Mutex<String>>,
}

pub struct LoginSession {
    pub client: IlinkClient,
    pub qrcode: String,
    pub qr_content: String,
    pub poll_base: String,
    pub status: String,
}

pub struct SessionManager {
    app: AppHandle,
    dir: PathBuf,
    accounts: Mutex<Vec<AccountInfo>>,
    sessions: Mutex<HashMap<String, SessionHandle>>,
    logins: Mutex<HashMap<String, Arc<Mutex<LoginSession>>>>,
    /// 入站事件广播（bridge 订阅）
    inbound_tx: broadcast::Sender<Value>,
    /// 每个 bot 最近一次会话对象（bot_id → peer/is_group/ts），订单/互动消息按此定向
    last_peer: Mutex<HashMap<String, (String, bool, i64)>>,
    counter: AtomicU64,
    /// 匿名 client（只用于 CDN 下载，无需 bot token）
    cdn_client: IlinkClient,
}

static EVENT_NAME: &str = "ilink-event";

impl SessionManager {
    pub fn new(app: AppHandle, dir: PathBuf) -> Self {
        let (tx, _rx) = broadcast::channel(256);
        SessionManager {
            app,
            dir,
            accounts: Mutex::new(Vec::new()),
            sessions: Mutex::new(HashMap::new()),
            logins: Mutex::new(HashMap::new()),
            inbound_tx: tx,
            last_peer: Mutex::new(HashMap::new()),
            counter: AtomicU64::new(1),
            cdn_client: IlinkClient::new("", None, None),
        }
    }

    fn now_ms(&self) -> i64 {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis() as i64)
            .unwrap_or(0)
    }

    fn next_id(&self, prefix: &str) -> String {
        let n = self.counter.fetch_add(1, Ordering::Relaxed);
        format!("{}-{}-{:04}", prefix, self.now_ms(), n)
    }

    fn emit(&self, payload: Value) {
        let _ = self.app.emit(EVENT_NAME, payload);
    }

    // ---------------- 持久化 ----------------

    fn accounts_path(&self) -> PathBuf {
        self.dir.join("accounts.json")
    }

    fn load_accounts(&self) {
        if let Ok(s) = std::fs::read_to_string(self.accounts_path()) {
            if let Ok(v) = serde_json::from_str::<Vec<AccountInfo>>(&s) {
                *self.accounts.lock().unwrap() = v;
            }
        }
    }

    fn save_accounts(&self) {
        let list = self.accounts.lock().unwrap().clone();
        let s = serde_json::to_string_pretty(&list).unwrap_or_else(|_| "[]".into());
        let _ = std::fs::write(self.accounts_path(), s);
    }

    fn update_account<F: FnOnce(&mut AccountInfo)>(&self, bot_id: &str, f: F) {
        let mut list = self.accounts.lock().unwrap();
        if let Some(a) = list.iter_mut().find(|a| a.bot_id == bot_id) {
            f(a);
            drop(list);
            self.save_accounts();
        }
    }

    fn messages_dir(&self) -> PathBuf {
        self.dir.join("messages")
    }

    fn media_dir(&self) -> PathBuf {
        self.dir.join("media")
    }

    fn append_record(&self, rec: &ChatRecord) {
        let dir = self.messages_dir();
        let _ = std::fs::create_dir_all(&dir);
        let path = dir.join(format!("{}.jsonl", rec.bot_id));
        if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(&path) {
            use std::io::Write;
            if let Ok(line) = serde_json::to_string(rec) {
                let _ = writeln!(f, "{}", line);
            }
        }
    }

    /// 读取某账号最近 n 条本地消息（尾部）
    pub fn read_history(&self, bot_id: &str, limit: usize) -> Vec<ChatRecord> {
        let path = self.messages_dir().join(format!("{}.jsonl", bot_id));
        let Ok(content) = std::fs::read_to_string(&path) else {
            return Vec::new();
        };
        let mut out: Vec<ChatRecord> = content
            .lines()
            .filter_map(|l| serde_json::from_str(l).ok())
            .collect();
        if out.len() > limit {
            out = out.split_off(out.len() - limit);
        }
        out
    }

    // ---------------- 启动 / 恢复 ----------------

    pub fn init(self: &Arc<Self>) {
        self.load_accounts();
        let accounts = self.accounts.lock().unwrap().clone();
        eprintln!(
            "[ilink] 本地持久化恢复 {} 个账号会话（{}）",
            accounts.len(),
            self.accounts_path().display()
        );
        for acc in accounts {
            self.emit(json!({
                "kind": "account", "event": "restored",
                "bot_id": acc.bot_id, "label": acc.label,
            }));
            self.spawn_session(acc);
        }
    }

    fn spawn_session(self: &Arc<Self>, acc: AccountInfo) {
        let this = Arc::clone(self);
        let acc2 = acc.clone();
        tauri::async_runtime::spawn(async move {
            this.session_task(acc2).await;
        });
    }

    // ---------------- 扫码登录 ----------------

    /// 发起一次扫码登录：取二维码，渲染成 SVG，返回 (login_id, 二维码内容, svg)
    pub async fn begin_login(self: &Arc<Self>) -> Result<(String, String, String), String> {
        let client = IlinkClient::new("", None, None);
        let qr = client.fetch_qr_code().await?;
        let login_id = self.next_id("login");
        let svg = qr_svg(&qr.qrcode_img_content);
        let ls = Arc::new(Mutex::new(LoginSession {
            client,
            qrcode: qr.qrcode,
            qr_content: qr.qrcode_img_content.clone(),
            poll_base: ilink::DEFAULT_BASE_URL.to_string(),
            status: "wait".into(),
        }));
        self.logins.lock().unwrap().insert(login_id.clone(), ls);
        self.emit(json!({"kind": "login", "event": "qr", "login_id": login_id, "content": qr.qrcode_img_content, "svg": svg}));
        Ok((login_id, qr.qrcode_img_content, svg))
    }

    /// 轮询一次扫码状态。返回 {status, svg?, account?}
    pub async fn poll_login(self: &Arc<Self>, login_id: &str) -> Result<Value, String> {
        // 取出登录会话的 client + 当前状态快照（释放锁后再 await，避免跨 .await 持有 MutexGuard）
        let (client, qrcode, poll_base) = {
            let map = self.logins.lock().unwrap();
            let ls = map.get(login_id).cloned().ok_or("登录会话不存在或已过期")?;
            let l = ls.lock().unwrap();
            (l.client.clone(), l.qrcode.clone(), l.poll_base.clone())
        };
        let st = client.poll_qr_status(&qrcode, &poll_base).await?;
        let mut out = json!({"status": st.status});
        match st.status.as_str() {
            "wait" => {}
            "scaned" => {
                if let Some(ls) = self.logins.lock().unwrap().get(login_id) {
                    ls.lock().unwrap().status = "scanned".into();
                }
            }
            "scaned_but_redirect" => {
                if !st.redirect_host.is_empty() {
                    if let Some(ls) = self.logins.lock().unwrap().get(login_id) {
                        ls.lock().unwrap().poll_base = format!("https://{}", st.redirect_host);
                    }
                }
            }
            "expired" => {
                // 重新取码（对齐 Go 版 LoginWithQR 的自动刷新）
                let qr = client.fetch_qr_code().await?;
                let svg = qr_svg(&qr.qrcode_img_content);
                if let Some(ls) = self.logins.lock().unwrap().get(login_id) {
                    let mut l = ls.lock().unwrap();
                    l.qrcode = qr.qrcode;
                    l.qr_content = qr.qrcode_img_content.clone();
                    l.status = "wait".into();
                }
                out["svg"] = json!(svg);
                out["content"] = json!(qr.qrcode_img_content);
            }
            "confirmed" => {
                if st.ilink_bot_id.is_empty() {
                    return Err("服务端未返回 bot_id".into());
                }
                let acc = AccountInfo {
                    bot_id: st.ilink_bot_id.clone(),
                    label: format!("微信{}", st.ilink_user_id.clone().chars().take(4).collect::<String>()),
                    bot_token: st.bot_token.clone(),
                    base_url: if st.baseurl.is_empty() { ilink::DEFAULT_BASE_URL.into() } else { st.baseurl.clone() },
                    user_id: st.ilink_user_id.clone(),
                    sync_buf: String::new(),
                    created_at: self.now_ms(),
                };
                {
                    let mut list = self.accounts.lock().unwrap();
                    list.retain(|a| a.bot_id != acc.bot_id);
                    list.push(acc.clone());
                }
                self.save_accounts();
                self.spawn_session(acc.clone());
                self.logins.lock().unwrap().remove(login_id);
                out["account"] = json!({
                    "bot_id": acc.bot_id, "label": acc.label, "user_id": acc.user_id,
                });
            }
            _ => {}
        }
        self.emit(json!({"kind": "login", "event": "status", "login_id": login_id, "status": st.status}));
        Ok(out)
    }

    pub fn cancel_login(&self, login_id: &str) {
        self.logins.lock().unwrap().remove(login_id);
    }

    // ---------------- 账号管理 ----------------

    /// bot 最近会话对象（peer, is_group）；无记录返回 None
    pub fn last_peer_of(&self, bot_id: &str) -> Option<(String, bool)> {
        let lp = self.last_peer.lock().unwrap();
        lp.get(bot_id).map(|(p, g, _)| (p.clone(), *g))
    }

    pub fn list_accounts(&self) -> Vec<Value> {
        let sessions = self.sessions.lock().unwrap();
        self.accounts
            .lock()
            .unwrap()
            .iter()
            .map(|a| {
                let status = sessions
                    .get(&a.bot_id)
                    .map(|s| s.status.lock().unwrap().clone())
                    .unwrap_or_else(|| "offline".into());
                json!({
                    "bot_id": a.bot_id, "label": a.label, "user_id": a.user_id,
                    "base_url": a.base_url, "status": status,
                })
            })
            .collect()
    }

    pub fn remove_account(self: &Arc<Self>, bot_id: &str) {
        if let Some(h) = self.sessions.lock().unwrap().get(bot_id) {
            let _ = h.cmd_tx.send(SessionCmd::Stop);
        }
        self.accounts.lock().unwrap().retain(|a| a.bot_id != bot_id);
        self.save_accounts();
        // 本地消息保留（不删档）
    }

    pub fn send_cmd(&self, bot_id: &str, cmd: SessionCmd) -> Result<(), String> {
        let sessions = self.sessions.lock().unwrap();
        let h = sessions.get(bot_id).ok_or("该账号未在线")?;
        h.cmd_tx.send(cmd).map_err(|_| "会话已停止".to_string())
    }

    /// 跨账号向指定微信用户发文本（1→N 群发入口之一）
    pub async fn broadcast_text(
        self: &Arc<Self>,
        bot_ids: &[String],
        to: &str,
        text: &str,
    ) -> Vec<Value> {
        let mut results = Vec::new();
        for bot_id in bot_ids {
            let (tx, rx) = oneshot::channel();
            let ok = self
                .send_cmd(
                    bot_id,
                    SessionCmd::SendText { to: to.to_string(), text: text.to_string(), reply: tx },
                )
                .is_ok();
            let r = if ok { rx.await.unwrap_or_else(|_| Err("会话无响应".into())) } else { Err("该账号未在线".into()) };
            results.push(json!({"bot_id": bot_id, "ok": r.is_ok(), "error": r.err()}));
        }
        results
    }

    pub fn subscribe_inbound(&self) -> broadcast::Receiver<Value> {
        self.inbound_tx.subscribe()
    }

    // ---------------- 会话任务（单线） ----------------

    async fn session_task(self: Arc<Self>, acc: AccountInfo) {
        let bot_id = acc.bot_id.clone();
        let client = Arc::new(IlinkClient::new(
            &acc.bot_token,
            Some(if acc.base_url.is_empty() { ilink::DEFAULT_BASE_URL.into() } else { acc.base_url.clone() }),
            None,
        ));
        let status = Arc::new(Mutex::new("running".into()));
        let (cmd_tx, mut cmd_rx) = mpsc::unbounded_channel::<SessionCmd>();
        self.sessions.lock().unwrap().insert(
            bot_id.clone(),
            SessionHandle { cmd_tx, status: Arc::clone(&status) },
        );
        self.emit(json!({"kind": "account", "event": "status", "bot_id": bot_id, "status": "running"}));

        // --- 内部长轮询 poller（独立任务，不被发送命令打断） ---
        let (poll_tx, mut poll_rx) = mpsc::unbounded_channel::<Result<(String, Vec<WeixinMessage>), String>>();
        {
            let p_client = Arc::clone(&client);
            let mut buf = acc.sync_buf.clone();
            let p_status = Arc::clone(&status);
            tauri::async_runtime::spawn(async move {
                let mut failures: u32 = 0;
                let mut next_timeout_ms: u64 = 0;
                loop {
                    match p_client.get_updates(&buf, next_timeout_ms).await {
                        Ok(resp) => {
                            if ilink::is_session_expired(resp.ret, resp.errcode) {
                                *p_status.lock().unwrap() = "session_expired".into();
                                let _ = poll_tx.send(Err("__SESSION_EXPIRED__".into()));
                                break;
                            }
                            if resp.ret != 0 || resp.errcode != 0 {
                                failures += 1;
                                let _ = poll_tx.send(Err(format!("ret={} errcode={} {}", resp.ret, resp.errcode, resp.errmsg)));
                                let delay = if failures >= 3 { failures = 0; 30 } else { 2 };
                                tokio::time::sleep(std::time::Duration::from_secs(delay)).await;
                                continue;
                            }
                            failures = 0;
                            if resp.longpolling_timeout_ms > 0 {
                                next_timeout_ms = resp.longpolling_timeout_ms as u64;
                            }
                            if !resp.get_updates_buf.is_empty() {
                                buf = resp.get_updates_buf.clone();
                            }
                            let _ = poll_tx.send(Ok((buf.clone(), resp.msgs)));
                        }
                        Err(e) => {
                            failures += 1;
                            let _ = poll_tx.send(Err(e));
                            let delay = if failures >= 3 { failures = 0; 30 } else { 2 };
                            tokio::time::sleep(std::time::Duration::from_secs(delay)).await;
                        }
                    }
                }
            });
        }

        let mut closed = false;
        while !closed {
            tokio::select! {
                cmd = cmd_rx.recv() => {
                    match cmd {
                        None | Some(SessionCmd::Stop) => { closed = true; }
                        Some(SessionCmd::SendText { to, text, reply }) => {
                            let r = self.do_send_text(&client, &to, &text).await;
                            self.record_out(&bot_id, &to, "text", &text, None, 0, &r);
                            let _ = reply.send(r.map(|_| "ok".into()));
                        }
                        Some(SessionCmd::SendImage { to, data, filename, reply }) => {
                            let r = client.send_image(&to, &self.ctx_of(&client, &to), &data, &filename).await;
                            self.record_out(&bot_id, &to, "image", "", Some(json!({"filename": filename, "size": data.len()})), 0, &r);
                            let _ = reply.send(r);
                        }
                        Some(SessionCmd::SendVoice { to, data, encode_type, sample_rate, playtime_ms, reply }) => {
                            let r = client.send_voice(&to, &self.ctx_of(&client, &to), &data, encode_type, sample_rate, playtime_ms).await;
                            self.record_out(&bot_id, &to, "voice", "", Some(json!({"size": data.len()})), playtime_ms, &r);
                            let _ = reply.send(r);
                        }
                    }
                }
                ev = poll_rx.recv() => {
                    match ev {
                        None => { // poller 退出（会话过期）
                            closed = true;
                        }
                        Some(Ok((buf, msgs))) => {
                            self.update_account(&bot_id, |a| a.sync_buf = buf);
                            for msg in msgs {
                                self.handle_inbound(&client, &bot_id, msg).await;
                            }
                        }
                        Some(Err(e)) => {
                            if e == "__SESSION_EXPIRED__" {
                                self.emit(json!({"kind": "account", "event": "status", "bot_id": bot_id, "status": "session_expired"}));
                                closed = true;
                            } else {
                                self.emit(json!({"kind": "account", "event": "error", "bot_id": bot_id, "error": e}));
                            }
                        }
                    }
                }
            }
        }

        self.sessions.lock().unwrap().remove(&bot_id);
        let st = status.lock().unwrap().clone();
        self.emit(json!({"kind": "account", "event": "status", "bot_id": bot_id, "status": if st == "session_expired" { "session_expired".to_string() } else { "offline".to_string() }}));
    }

    fn ctx_of(&self, client: &IlinkClient, to: &str) -> String {
        client.get_context_token(to).unwrap_or_default()
    }

    async fn do_send_text(&self, client: &IlinkClient, to: &str, text: &str) -> Result<String, String> {
        let ctx = self.ctx_of(client, to);
        if ctx.is_empty() {
            return Err("无 context_token：请让对方先给该微信号发一条消息（微信 24h 被动窗口限制）".into());
        }
        client.send_text(to, text, &ctx).await
    }

    fn record_out(
        &self,
        bot_id: &str,
        to: &str,
        kind: &str,
        text: &str,
        media: Option<Value>,
        playtime_ms: i64,
        result: &Result<impl std::fmt::Debug, String>,
    ) {
        let rec = ChatRecord {
            id: self.next_id("m"),
            bot_id: bot_id.to_string(),
            dir: "out".into(),
            peer: to.to_string(),
            kind: kind.into(),
            text: text.into(),
            media,
            ts: self.now_ms(),
            status: if result.is_ok() { "sent".into() } else { "failed".into() },
            playtime_ms,
        };
        self.append_record(&rec);
        self.emit(json!({"kind": "message", "event": "out", "record": rec}));
    }

    async fn handle_inbound(&self, client: &IlinkClient, bot_id: &str, msg: WeixinMessage) {
        // 记录该 bot 最近会话对象（群消息记群，私聊记对方）；订单/互动消息定向用
        {
            let peer = if msg.group_id.is_empty() { msg.from_user_id.clone() } else { msg.group_id.clone() };
            if !peer.is_empty() {
                let mut lp = self.last_peer.lock().unwrap();
                lp.insert(bot_id.to_string(), (peer, !msg.group_id.is_empty(), self.now_ms()));
                if lp.len() > 500 {
                    lp.clear();
                }
            }
        }
        // 缓存 context token（主动 Push 的凭据）
        if !msg.context_token.is_empty() && !msg.from_user_id.is_empty() {
            client.set_context_token(&msg.from_user_id, &msg.context_token);
        }
        // 群消息：回复目标是群本身，group_id 也要缓存一份凭据（autoreply 按 group_id 回发）
        if !msg.context_token.is_empty() && !msg.group_id.is_empty() {
            client.set_context_token(&msg.group_id, &msg.context_token);
        }
        // 只处理用户消息（message_type==1）；生成中的分片（state==1）等 finish 再入档
        if msg.message_type != 0 && msg.message_type != 1 {
            return;
        }

        for (idx, item) in msg.item_list.iter().enumerate() {
            let (kind, text, media, playtime) = match item.item_type {
                1 => (
                    "text",
                    item.text_item.as_ref().map(|t| t.text.clone()).unwrap_or_default(),
                    None,
                    0,
                ),
                2 => ("image", String::new(), item.image_item.as_ref().and_then(|i| i.media.as_ref()).map(|m| serde_json::to_value(m).unwrap_or(json!(null))), 0),
                3 => (
                    "voice",
                    item.voice_item.as_ref().map(|v| v.text.clone()).unwrap_or_default(),
                    item.voice_item.as_ref().and_then(|v| v.media.as_ref()).map(|m| serde_json::to_value(m).unwrap_or(json!(null))),
                    item.voice_item.as_ref().map(|v| v.playtime).unwrap_or(0),
                ),
                4 => ("file", item.file_item.as_ref().map(|f| f.file_name.clone()).unwrap_or_default(), item.file_item.as_ref().and_then(|f| f.media.as_ref()).map(|m| serde_json::to_value(m).unwrap_or(json!(null))), 0),
                5 => ("video", String::new(), item.video_item.as_ref().and_then(|v| v.media.as_ref()).map(|m| serde_json::to_value(m).unwrap_or(json!(null))), 0),
                _ => continue,
            };
            if kind == "text" && text.is_empty() {
                continue;
            }
            let rec = ChatRecord {
                id: self.next_id("m"),
                bot_id: bot_id.to_string(),
                dir: "in".into(),
                peer: msg.from_user_id.clone(),
                kind: kind.into(),
                text,
                media,
                ts: if msg.create_time_ms > 0 { msg.create_time_ms } else { self.now_ms() },
                status: "received".into(),
                playtime_ms: playtime,
            };
            let _ = idx;
            self.append_record(&rec);
            let payload = json!({
                "kind": "message",
                "event": "in",
                "record": rec,
                "message_id": msg.message_id,
                "session_id": msg.session_id,
                "group_id": msg.group_id,
                "from_user_id": msg.from_user_id,
            });
            self.emit(payload.clone());
            let _ = self.inbound_tx.send(payload);
        }
    }

    // ---------------- 媒体下载（懒加载，供前端展示） ----------------

    pub async fn fetch_media(&self, media: Value) -> Result<Value, String> {
        let m: ilink::CdnMedia = serde_json::from_value(media).map_err(|e| e.to_string())?;
        // 先查本地缓存
        let dir = self.media_dir();
        let _ = std::fs::create_dir_all(&dir);
        let cache_key = format!("{:x}", md5::Md5::digest(format!("{}|{}", m.encrypt_query_param, m.aes_key).as_bytes()));
        let ext_by_mime = |bytes: &[u8]| -> (&'static str, &'static str) {
            if bytes.starts_with(&[0xFF, 0xD8, 0xFF]) {
                ("jpg", "image/jpeg")
            } else if bytes.starts_with(&[0x89, b'P', b'N', b'G']) {
                ("png", "image/png")
            } else if bytes.starts_with(b"GIF8") {
                ("gif", "image/gif")
            } else if bytes.len() > 12 && &bytes[0..4] == b"RIFF" && &bytes[8..12] == b"WEBP" {
                ("webp", "image/webp")
            } else if bytes.starts_with(b"#!SILK") || bytes.starts_with(b"\x02#!SILK") {
                ("silk", "audio/silk")
            } else if bytes.starts_with(b"AMR") {
                ("amr", "audio/amr")
            } else {
                ("bin", "application/octet-stream")
            }
        };
        let cached = dir.join(&cache_key);
        let (data, ext, mime) = if let Ok(bytes) = std::fs::read(&cached) {
            let (ext, mime) = ext_by_mime(&bytes);
            (bytes, ext, mime)
        } else {
            let bytes = self.cdn_client.download_media(&m).await?;
            let (ext, mime) = ext_by_mime(&bytes);
            let _ = std::fs::write(dir.join(format!("{}.{}", cache_key, ext)), &bytes);
            let _ = std::fs::write(&cached, &bytes); // 无扩展名缓存索引
            (bytes, ext, mime)
        };
        use base64::Engine as _;
        Ok(json!({
            "mime": mime,
            "ext": ext,
            "data_b64": base64::engine::general_purpose::STANDARD.encode(&data),
            "size": data.len(),
        }))
    }
}

use md5::Digest;

/// 二维码内容 → SVG（qrcode crate，无外部 JS 依赖）
pub fn qr_svg(content: &str) -> String {
    use qrcode::render::svg;
    use qrcode::QrCode;
    match QrCode::with_error_correction_level(content.as_bytes(), qrcode::EcLevel::M) {
        Ok(code) => code
            .render::<svg::Color>()
            .dark_color(svg::Color("#111111"))
            .light_color(svg::Color("#ffffff"))
            .quiet_zone(true)
            .build(),
        Err(_) => String::new(),
    }
}
