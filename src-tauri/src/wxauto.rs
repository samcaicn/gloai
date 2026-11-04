//! 微信 wxauto 通道：wechatauto Python sidecar（UI 自动化收发，私聊+群聊）。
//!
//! 架构：本模块 spawn `python wxauto_sidecar.py` 子进程（脚本内嵌于二进制，
//! 启动时写到 app_data_dir），通过 stdin/stdout 行 JSON 通信：
//!   * sidecar stdout {"type":"in",...}   → 组装 WxMsg 交 autoreply 统一管线
//!     （与 iLink 团员一样：过滤 → 团员 chat/send → 服务端 AI → 回发）
//!   * autoreply 回复经 send_text() 写 stdin {"cmd":"send",...} → sidecar
//!     quick_send 到微信（私聊/群聊），结果以 {"type":"sent",...} 回报。
//!   * 进程退出且开关仍开 → 5s 后自动重启（连续失败 5 次停，避免死循环）。
//!
//! 依赖：本机微信已登录（桌面端 4.x）；Python 解释器默认探测项目内置的
//! `.venv_bot`（见 `resolve_python`），找不到再回退 PATH 里的 python。

use crate::autoreply;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tauri::{AppHandle, Emitter, Manager};
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::process::{Child, Command};
use tokio::sync::mpsc;

static REQ_SEQ: AtomicU64 = AtomicU64::new(1);

/// 毫秒时间戳 → "HH:MM:SS.mmm"（本地时区），日志用。
fn fmt_ts(ms: i64) -> String {
    use std::time::SystemTime;
    let secs = ms / 1000;
    let millis = (ms % 1000) as u32;
    let local = SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    let offset = local - secs;
    let local_secs = secs + offset;
    let h = (local_secs / 3600) % 24;
    let m = (local_secs / 60) % 60;
    let s = local_secs % 60;
    format!("{:02}:{:02}:{:02}.{:03}", h, m, s, millis)
}

const SIDECAR_SRC: &str = include_str!("../python/wxauto_sidecar.py");
const OCR_ENGINE_SRC: &str = include_str!("../python/ocrengine.py");
const ASR_ENGINE_SRC: &str = include_str!("../python/asrengine.py");
/// 内置 venv 的相对位置（venv 根目录名 → Scripts → python.exe）
const VENV_PY_REL: &str = r".venv_bot\Scripts\python.exe";
const MAX_RESTARTS: u32 = 5;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct WxAutoConfig {
    /// 通道开关（关=停止 sidecar 且不收发）
    pub enabled: bool,
    /// Python 解释器路径；空 = 自动探测（项目内置 .venv_bot → PATH python）
    pub python_path: String,
    /// wechatauto vendor 目录；空 = 脚本内默认 C:\d\weauto\vendor
    pub vendor_path: String,
}

impl Default for WxAutoConfig {
    fn default() -> Self {
        WxAutoConfig {
            enabled: true,
            python_path: String::new(),
            vendor_path: String::new(),
        }
    }
}

struct ProcHandle {
    child: Child,
    stdin_tx: mpsc::Sender<String>,
    /// 请求-响应关联：req id → 等待者（sessions / send_image 等同步请求用）
    pending: Mutex<HashMap<String, tokio::sync::oneshot::Sender<Value>>>,
}

pub struct WxAutoState {
    pub config: Mutex<WxAutoConfig>,
    /// supervisor 是否在运行（无论子进程是否活着）
    running: std::sync::Mutex<bool>,
    /// 当前子进程句柄（发送 / 停止用）
    inner: std::sync::Mutex<Option<ProcHandle>>,
    /// 最近日志（环形 200）
    pub logs: std::sync::Mutex<Vec<Value>>,
    /// sidecar ready 后上报的自己 wxid / 昵称（自消息回环兜底过滤用）
    self_user: std::sync::Mutex<String>,
    self_nick: std::sync::Mutex<String>,
    /// 本轮 sidecar 是否已发过 ready（启动失败重启计数用）
    ready_seen: AtomicBool,
}

impl WxAutoState {
    pub fn new() -> Self {
        WxAutoState {
            config: Mutex::new(WxAutoConfig::default()),
            running: std::sync::Mutex::new(false),
            inner: std::sync::Mutex::new(None),
            logs: std::sync::Mutex::new(Vec::new()),
            self_user: std::sync::Mutex::new(String::new()),
            self_nick: std::sync::Mutex::new(String::new()),
            ready_seen: AtomicBool::new(false),
        }
    }

    fn log(&self, app: &AppHandle, level: &str, msg: &str, extra: Value) {
        let entry = json!({
            "ts": autoreply::now_ms_pub(),
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
        let _ = app.emit("wxauto-event", entry);
        eprintln!("[wxauto] {} {}", level, msg);
        // 落盘到 app_data_dir/logs/wxauto.log（追加，UTF-8）
        if let Ok(dir) = app.path().app_data_dir() {
            let log_dir = dir.join("logs");
            let _ = std::fs::create_dir_all(&log_dir);
            let line = format!(
                "[{}] {} {}\n",
                fmt_ts(autoreply::now_ms_pub()),
                level,
                msg
            );
            if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(log_dir.join("wxauto.log")) {
                use std::io::Write;
                let _ = f.write_all(line.as_bytes());
            }
        }
    }
}

impl Default for WxAutoState {
    fn default() -> Self {
        Self::new()
    }
}

// ---------------------------------------------------------------------------
// 生命周期
// ---------------------------------------------------------------------------

/// 启动 supervisor（幂等）。开关关着时 supervisor 空转等待，开了自动拉起。
pub fn ensure_started(app: AppHandle) {
    {
        let st = app.state::<Arc<WxAutoState>>();
        let mut r = st.running.lock().unwrap();
        if *r {
            return;
        }
        *r = true;
    }
    tauri::async_runtime::spawn(async move {
        let mut restarts: u32 = 0;
        loop {
            let Some(st) = app.try_state::<Arc<WxAutoState>>() else {
                tokio::time::sleep(Duration::from_secs(3)).await;
                continue;
            };
            let st = Arc::clone(&st);
            let cfg = st.config.lock().unwrap().clone();
            if !cfg.enabled {
                // 开关关闭：清理残留进程，待机
                kill_inner(&st).await;
                tokio::time::sleep(Duration::from_secs(3)).await;
                restarts = 0;
                continue;
            }
            st.log(&app, "info", "启动微信通道（wechatauto sidecar）…", json!({}));
            match run_once(&app, &st, &cfg).await {
                Ok(()) => {
                    // run_once 正常返回 = 子进程退出（stdin 关闭/崩溃）
                    st.log(&app, "warn", "微信通道进程已退出", json!({}));
                }
                Err(e) => {
                    st.log(&app, "warn", &format!("微信通道启动失败: {}", e), json!({}));
                    restarts += 1;
                    if restarts >= MAX_RESTARTS {
                        st.log(&app, "warn", &format!("连续 {} 次启动失败，暂停自动重启（可关闭后再开重试）", restarts), json!({}));
                        // 关掉开关避免空转
                        {
                            let mut c = st.config.lock().unwrap();
                            c.enabled = false;
                            let snapshot = c.clone();
                            persist_config(&app, &snapshot);
                        }
                        continue;
                    }
                }
            }
            kill_inner(&st).await;
            // 开关仍开则延时重启
            let enabled = st.config.lock().unwrap().enabled;
            if !enabled {
                continue;
            }
            st.log(&app, "info", "5s 后重启微信通道…", json!({}));
            tokio::time::sleep(Duration::from_secs(5)).await;
        }
    });
}

fn persist_config(app: &AppHandle, cfg: &WxAutoConfig) {
    if let Some(state) = app.try_state::<crate::state::AppState>() {
        {
            let mut g = state.config.lock().unwrap();
            if let Some(obj) = g.as_object_mut() {
                obj.insert("wxauto".into(), serde_json::to_value(cfg).unwrap_or(json!({})));
            }
        }
        let _ = state.save_config();
    }
}

async fn kill_inner(st: &Arc<WxAutoState>) {
    let handle = st.inner.lock().unwrap().take();
    if let Some(mut h) = handle {
        let _ = h.child.kill().await;
    }
}

/// 解析 Python 解释器，按优先级返回首个可用的：
///   1. 配置里的 `python_path`（用户显式指定）
///   2. 环境变量 `BBT_PYTHON`
///   3. `<exe 所在目录>\.venv_bot\Scripts\python.exe`
///      —— 绿色版/免安装：把 `.venv_bot` 与 exe 放在同一目录
///   4. `<exe 所在目录>\..\..\..\.venv_bot\Scripts\python.exe`
///      —— dev：exe 在 `src-tauri\target\debug`，向上三级即项目根
///   5. Tauri 资源目录下 `\.venv_bot\Scripts\python.exe`
///      —— 若将来把 venv 作为 bundle resources 打进安装包
///   6. `"python"`（走 PATH）
fn resolve_python(app: &AppHandle, cfg: &WxAutoConfig) -> String {
    let explicit = cfg.python_path.trim();
    if !explicit.is_empty() {
        return explicit.to_string();
    }
    if let Ok(env_py) = std::env::var("BBT_PYTHON") {
        let env_py = env_py.trim();
        if !env_py.is_empty() && std::path::Path::new(env_py).exists() {
            return env_py.to_string();
        }
    }
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            let candidates = [
                dir.join(VENV_PY_REL),
                dir.parent()
                    .and_then(|p| p.parent())
                    .and_then(|p| p.parent())
                    .map(|p| p.join(VENV_PY_REL))
                    .unwrap_or_default(),
            ];
            for p in candidates.iter() {
                if p.exists() {
                    return p.to_string_lossy().to_string();
                }
            }
        }
    }
    if let Ok(resource_dir) = app.path().resource_dir() {
        let p = resource_dir.join(VENV_PY_REL);
        if p.exists() {
            return p.to_string_lossy().to_string();
        }
    }
    "python".to_string()
}

/// 拉起一次 sidecar 并读 stdout 直到进程退出。返回 Err=启动失败。
async fn run_once(app: &AppHandle, st: &Arc<WxAutoState>, cfg: &WxAutoConfig) -> Result<(), String> {
    st.ready_seen.store(false, Ordering::SeqCst);
    // 1) 写脚本到 app_data_dir（保证版本与二进制一致）
    let data_dir = app
        .path()
        .app_data_dir()
        .map_err(|e| format!("app_data_dir: {}", e))?;
    std::fs::create_dir_all(&data_dir).map_err(|e| format!("创建数据目录: {}", e))?;
    let script_path = data_dir.join("wxauto_sidecar.py");
    std::fs::write(&script_path, SIDECAR_SRC).map_err(|e| format!("写 sidecar 脚本: {}", e))?;
    // 离线 OCR / ASR 引擎随 sidecar 一起落盘（与脚本同目录，sidecar 直接 import）
    std::fs::write(data_dir.join("ocrengine.py"), OCR_ENGINE_SRC).map_err(|e| format!("写 OCR 引擎: {}", e))?;
    std::fs::write(data_dir.join("asrengine.py"), ASR_ENGINE_SRC).map_err(|e| format!("写 ASR 引擎: {}", e))?;
    // 模型目录：默认放在 app_data_dir 下的 ocr_models / asr_models，用户把权重丢进去即可
    let ocr_dir = data_dir.join("ocr_models");
    let asr_dir = data_dir.join("asr_models");
    let _ = std::fs::create_dir_all(&ocr_dir);
    let _ = std::fs::create_dir_all(&asr_dir);

    // 2) 找 python（配置 → BBT_PYTHON → 随程序的内置 venv → PATH）
    let py = resolve_python(app, cfg);

    // 3) spawn
    let mut cmd = Command::new(&py);
    cmd.arg(&script_path)
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped());
    if !cfg.vendor_path.trim().is_empty() {
        cmd.env("WX_VENDOR", cfg.vendor_path.trim());
    }
    // 离线 OCR / ASR 模型目录（默认 app_data_dir 下的 ocr_models / asr_models）
    cmd.env("OCR_MODEL_DIR", ocr_dir.to_string_lossy().to_string());
    cmd.env("ASR_MODEL_DIR", asr_dir.to_string_lossy().to_string());
    #[cfg(windows)]
    {
        // CREATE_NO_WINDOW：不在任务栏弹黑窗
        cmd.creation_flags(0x0800_0000);
    }
    let mut child = cmd.spawn().map_err(|e| format!("启动 python({}) 失败: {}", py, e))?;
    let stdout = child.stdout.take().ok_or("无 stdout")?;
    let stdin = child.stdin.take().ok_or("无 stdin")?;
    let stderr = child.stderr.take();

    // stdin 写线程：mpsc 队列 → 子进程
    let (tx, mut rx) = mpsc::channel::<String>(64);
    tauri::async_runtime::spawn(async move {
        use tokio::io::AsyncWriteExt;
        let mut w = stdin;
        while let Some(line) = rx.recv().await {
            if w.write_all(line.as_bytes()).await.is_err() {
                break;
            }
            if w.write_all(b"\n").await.is_err() {
                break;
            }
            let _ = w.flush().await;
        }
    });

    // stderr 日志线程
    if let Some(stderr) = stderr {
        tauri::async_runtime::spawn(async move {
            let mut lines = BufReader::new(stderr).lines();
            while let Ok(Some(l)) = lines.next_line().await {
                if !l.trim().is_empty() {
                    eprintln!("[wxauto-sidecar] {}", l);
                }
            }
        });
    }

    *st.inner.lock().unwrap() = Some(ProcHandle { child, stdin_tx: tx.clone(), pending: Mutex::new(HashMap::new()) });

    // 4) 读 stdout 事件循环
    let mut lines = BufReader::new(stdout).lines();
    loop {
        // 每行处理前检查 inner 还是否当前进程（stop 时 inner 被清，子进程被 kill）
        match lines.next_line().await {
            Ok(Some(line)) => {
                if line.trim().is_empty() {
                    continue;
                }
                let v: Value = match serde_json::from_str(&line) {
                    Ok(v) => v,
                    Err(_) => continue,
                };
                handle_sidecar_event(app, st, v).await;
            }
            Ok(None) => break, // EOF：进程退出
            Err(e) => {
                let _ = e;
                break;
            }
        }
    }
    // stdout EOF = 子进程已退出。从 inner 取出当前 child wait() 拿 exit code，
    // 判断是「启动早期崩溃」（算启动失败，restarts++，避免无限 5s 重启）
    // 还是「ready 后正常退出」（5s 后重启即可，不累加失败计数）。
    let proc = st.inner.lock().unwrap().take();
    let code = match proc {
        Some(mut h) => match h.child.wait().await {
            Ok(s) => Some(s.code().unwrap_or(-1)),
            Err(_) => None,
        },
        None => None, // 被外部 stop() kill 掉
    };
    let ready = st.ready_seen.load(Ordering::SeqCst);
    match (ready, code) {
        (true, _) => Ok(()), // 曾 ready 后退出，正常重启即可
        (false, Some(0)) => Err("sidecar 启动后立即退出（exit 0），未发出 ready".into()),
        (false, Some(c)) => Err(format!("sidecar 启动失败（exit code={}），未发出 ready", c)),
        (false, None) => Err("sidecar 启动后退出，无法获取 exit code".into()),
    }
}

async fn handle_sidecar_event(app: &AppHandle, st: &Arc<WxAutoState>, v: Value) {
    match v.get("type").and_then(|t| t.as_str()).unwrap_or("") {
        "status" => {
            let event = v.get("event").and_then(|e| e.as_str()).unwrap_or("");
            let self_nick = v.get("self").and_then(|s| s.as_str()).unwrap_or("");
            let self_user = v.get("self_user").and_then(|s| s.as_str()).unwrap_or("");
            let msg = v.get("msg").and_then(|m| m.as_str()).unwrap_or("");
            match event {
                "ready" => {
                    *st.self_nick.lock().unwrap() = self_nick.to_string();
                    *st.self_user.lock().unwrap() = self_user.to_string();
                    st.ready_seen.store(true, Ordering::SeqCst);
                    st.log(app, "info", &format!("微信通道就绪（账号：{}，wxid={}，私聊+群聊全会话监听）", self_nick, self_user), json!({}));
                }
                "error" => st.log(app, "warn", &format!("微信通道错误: {}", msg), json!({})),
                _ => {}
            }
        }
        "ping" => {}
        "in" => {
            let peer = v.get("peer").and_then(|p| p.as_str()).unwrap_or("").to_string();
            let text = v.get("text").and_then(|t| t.as_str()).unwrap_or("").to_string();
            let is_group = v.get("is_group").and_then(|g| g.as_bool()).unwrap_or(false);
            let sender = v.get("sender_name").and_then(|s| s.as_str()).unwrap_or("");
            // kind 由 sidecar 透传：text / image（离线 OCR 文本）/ voice（离线转写文本）
            let kind = v.get("kind").and_then(|k| k.as_str()).unwrap_or("text").to_string();
            // 群聊 @ 自己标志（sidecar 从 packed_info XML 解析）
            let mentioned_self = v.get("mentioned_self").and_then(|m| m.as_bool()).unwrap_or(false);
            // 纯图片/语音在 OCR/转写失败时文本可能为空：仍允许进入管线，由 autoreply
            // 规则层（空文本直接忽略）统一处理并记日志，避免静默吞掉事件。
            if peer.is_empty() {
                return;
            }
            // 兜底：self_nick 匹配到 sender 说明是自己发的消息（sidecar 已按 sender_id==2 过滤，
            // 双保险防止 wechatauto 库版本变化漏过滤导致回复自己死循环）
            let self_n = st.self_nick.lock().unwrap().clone();
            if !self_n.is_empty() && sender == self_n {
                return;
            }
            let msg = autoreply::WxMsg {
                channel: "wx".into(),
                bot_id: String::new(),
                peer,
                is_group,
                kind,
                text,
                sender: sender.to_string(),
                mentioned_self,
            };
            if let Err(e) = autoreply::process_msg(app, msg).await {
                st.log(app, "debug", &e, json!({}));
            }
        }
        "sent" | "sent_image" => {
            let peer = v.get("peer").and_then(|p| p.as_str()).unwrap_or("");
            let ok = v.get("ok").and_then(|o| o.as_bool()).unwrap_or(false);
            let msg = v.get("msg").and_then(|m| m.as_str()).unwrap_or("");
            let what = if v.get("type").and_then(|t| t.as_str()) == Some("sent_image") { "图片" } else { "消息" };
            // 请求-响应关联：resolve pending 等待者
            if let Some(req) = v.get("req").and_then(|r| r.as_str()) {
                let waiter = {
                    let guard = st.inner.lock().unwrap();
                    guard.as_ref().and_then(|h| h.pending.lock().unwrap().remove(req))
                };
                if let Some(tx) = waiter {
                    let _ = tx.send(v.clone());
                }
            }
            if ok {
                st.log(app, "info", &format!("微信{}已送达 {}", what, peer), json!({"peer": peer}));
            } else {
                st.log(app, "warn", &format!("微信{}发送失败 {}：{}", what, peer, msg), json!({"peer": peer}));
            }
        }
        "sessions" => {
            if let Some(req) = v.get("req").and_then(|r| r.as_str()) {
                let waiter = {
                    let guard = st.inner.lock().unwrap();
                    guard.as_ref().and_then(|h| h.pending.lock().unwrap().remove(req))
                };
                if let Some(tx) = waiter {
                    let _ = tx.send(v.clone());
                }
            }
        }
        _ => {}
    }
}

// ---------------------------------------------------------------------------
// 发送 / 状态 / 停止
// ---------------------------------------------------------------------------

/// 经 sidecar 向微信会话发文本（peer = wxid_xxx 或 xxx@chatroom）。
pub async fn send_text(app: &AppHandle, peer: &str, text: &str) -> Result<(), String> {
    let st = app.state::<Arc<WxAutoState>>();
    let tx = {
        let guard = st.inner.lock().unwrap();
        match guard.as_ref() {
            Some(h) => h.stdin_tx.clone(),
            None => return Err("微信通道未运行".into()),
        }
    };
    let line = json!({"cmd": "send", "peer": peer, "text": text}).to_string();
    tx.send(line).await.map_err(|_| "微信通道已关闭".to_string())
}

/// 同步请求：带 req id 写 stdin，等待 sidecar 回带 req 的响应（超时清理）。
async fn request(app: &AppHandle, mut payload: Value, timeout: Duration) -> Result<Value, String> {
    let req = format!("r{}-{}", autoreply::now_ms_pub(), REQ_SEQ.fetch_add(1, Ordering::Relaxed));
    payload["req"] = json!(req);
    let (tx, rx) = tokio::sync::oneshot::channel();
    {
        let st = app.state::<Arc<WxAutoState>>();
        let guard = st.inner.lock().unwrap();
        let h = guard.as_ref().ok_or("微信通道未运行")?;
        h.pending.lock().unwrap().insert(req.clone(), tx);
        h.stdin_tx
            .try_send(payload.to_string())
            .map_err(|_| "微信通道队列满或已关闭".to_string())?;
    }
    match tokio::time::timeout(timeout, rx).await {
        Ok(Ok(v)) => Ok(v),
        Ok(Err(_)) => Err("微信通道响应通道关闭".into()),
        Err(_) => {
            // 超时：清掉 pending 防泄漏
            let st = app.state::<Arc<WxAutoState>>();
            if let Some(h) = st.inner.lock().unwrap().as_ref() {
                h.pending.lock().unwrap().remove(&req);
            }
            Err("微信通道响应超时".into())
        }
    }
}

/// 经 sidecar 向微信会话发图片（peer = wxid_xxx 或 xxx@chatroom，b64 = png/jpg base64）。
pub async fn send_image(app: &AppHandle, peer: &str, b64: &str) -> Result<(), String> {
    let v = request(app, json!({"cmd": "send_image", "peer": peer, "b64": b64}), Duration::from_secs(90)).await?;
    let ok = v.get("ok").and_then(|o| o.as_bool()).unwrap_or(false);
    let msg = v.get("msg").and_then(|m| m.as_str()).unwrap_or("");
    if ok {
        Ok(())
    } else {
        Err(format!("微信发图失败: {}", msg))
    }
}

/// 微信会话列表（私聊+群聊，来自微信本地 session.db）。
pub async fn list_sessions(app: &AppHandle, limit: u32) -> Result<Vec<Value>, String> {
    let v = request(app, json!({"cmd": "sessions", "limit": limit}), Duration::from_secs(20)).await?;
    Ok(v.get("sessions").and_then(|s| s.as_array()).cloned().unwrap_or_default())
}

/// 手动停止（关子进程；supervisor 因开关判断不会立刻重启）。
pub async fn stop(app: &AppHandle) {
    let st = app.state::<Arc<WxAutoState>>();
    kill_inner(&st).await;
    st.log(app, "info", "微信通道已停止", json!({}));
}

pub fn is_running(app: &AppHandle) -> bool {
    app.try_state::<Arc<WxAutoState>>()
        .map(|st| *st.running.lock().unwrap())
        .unwrap_or(false)
}
