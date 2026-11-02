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
//! 依赖：本机微信已登录（桌面端 4.x）；Python 解释器默认探测
//! C:/d/weauto/.venv_bot/Scripts/python.exe（wechatauto 依赖齐全）。

use crate::autoreply;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tauri::{AppHandle, Emitter, Manager};
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::process::{Child, Command};
use tokio::sync::mpsc;

const SIDECAR_SRC: &str = include_str!("../python/wxauto_sidecar.py");
const DEFAULT_PY: &str = r"C:\d\weauto\.venv_bot\Scripts\python.exe";
const MAX_RESTARTS: u32 = 5;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct WxAutoConfig {
    /// 通道开关（关=停止 sidecar 且不收发）
    pub enabled: bool,
    /// Python 解释器路径；空 = 自动探测（默认 weauto venv → PATH python）
    pub python_path: String,
    /// wechatauto vendor 目录；空 = 脚本内默认 C:\d\weauto\vendor
    pub vendor_path: String,
}

impl Default for WxAutoConfig {
    fn default() -> Self {
        WxAutoConfig {
            enabled: false,
            python_path: String::new(),
            vendor_path: String::new(),
        }
    }
}

struct ProcHandle {
    child: Child,
    stdin_tx: mpsc::Sender<String>,
}

pub struct WxAutoState {
    pub config: Mutex<WxAutoConfig>,
    /// supervisor 是否在运行（无论子进程是否活着）
    running: std::sync::Mutex<bool>,
    /// 当前子进程句柄（发送 / 停止用）
    inner: std::sync::Mutex<Option<ProcHandle>>,
    /// 最近日志（环形 200）
    pub logs: std::sync::Mutex<Vec<Value>>,
}

impl WxAutoState {
    pub fn new() -> Self {
        WxAutoState {
            config: Mutex::new(WxAutoConfig::default()),
            running: std::sync::Mutex::new(false),
            inner: std::sync::Mutex::new(None),
            logs: std::sync::Mutex::new(Vec::new()),
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

/// 拉起一次 sidecar 并读 stdout 直到进程退出。返回 Err=启动失败。
async fn run_once(app: &AppHandle, st: &Arc<WxAutoState>, cfg: &WxAutoConfig) -> Result<(), String> {
    // 1) 写脚本到 app_data_dir（保证版本与二进制一致）
    let data_dir = app
        .path()
        .app_data_dir()
        .map_err(|e| format!("app_data_dir: {}", e))?;
    std::fs::create_dir_all(&data_dir).map_err(|e| format!("创建数据目录: {}", e))?;
    let script_path = data_dir.join("wxauto_sidecar.py");
    std::fs::write(&script_path, SIDECAR_SRC).map_err(|e| format!("写 sidecar 脚本: {}", e))?;

    // 2) 找 python
    let py = if !cfg.python_path.trim().is_empty() {
        cfg.python_path.trim().to_string()
    } else if std::path::Path::new(DEFAULT_PY).exists() {
        DEFAULT_PY.to_string()
    } else {
        "python".to_string()
    };

    // 3) spawn
    let mut cmd = Command::new(&py);
    cmd.arg(&script_path)
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped());
    if !cfg.vendor_path.trim().is_empty() {
        cmd.env("WX_VENDOR", cfg.vendor_path.trim());
    }
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

    *st.inner.lock().unwrap() = Some(ProcHandle { child, stdin_tx: tx.clone() });

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
    Ok(())
}

async fn handle_sidecar_event(app: &AppHandle, st: &Arc<WxAutoState>, v: Value) {
    match v.get("type").and_then(|t| t.as_str()).unwrap_or("") {
        "status" => {
            let event = v.get("event").and_then(|e| e.as_str()).unwrap_or("");
            let self_nick = v.get("self").and_then(|s| s.as_str()).unwrap_or("");
            let msg = v.get("msg").and_then(|m| m.as_str()).unwrap_or("");
            match event {
                "ready" => st.log(app, "info", &format!("微信通道就绪（账号：{}，私聊+群聊全会话监听）", self_nick), json!({})),
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
            if peer.is_empty() || text.trim().is_empty() {
                return;
            }
            let msg = autoreply::WxMsg {
                channel: "wx".into(),
                bot_id: String::new(),
                peer,
                is_group,
                kind: "text".into(),
                text,
                sender: sender.to_string(),
            };
            if let Err(e) = autoreply::process_msg(app, msg).await {
                st.log(app, "debug", &e, json!({}));
            }
        }
        "sent" => {
            let peer = v.get("peer").and_then(|p| p.as_str()).unwrap_or("");
            let ok = v.get("ok").and_then(|o| o.as_bool()).unwrap_or(false);
            let msg = v.get("msg").and_then(|m| m.as_str()).unwrap_or("");
            if ok {
                st.log(app, "info", &format!("微信已送达 {}", peer), json!({"peer": peer}));
            } else {
                st.log(app, "warn", &format!("微信发送失败 {}：{}", peer, msg), json!({"peer": peer}));
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
