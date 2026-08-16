// Copyright (c) 2026 AIMarketing
//
// CuaDriverClient — MCP JSON-RPC 2.0 客户端，通过 stdio 与 cua-driver
// sidecar 进程通信。
//
// 协议：行分隔 JSON-RPC 2.0（每行一个 JSON 对象）
//   请求：  {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{...}}
//   响应：  {"jsonrpc":"2.0","id":1,"result":{...}}
//   通知：  {"jsonrpc":"2.0","method":"notifications/initialized"}
//
// 生命周期：
//   1. 首次调用 → spawn 进程 → MCP 握手（initialize + initialized）
//   2. 后续调用 → 直接发送 tools/call 请求
//   3. 进程崩溃 → 读者任务退出 → 下次调用自动重启
//   4. 显式关闭 → kill 进程 → 标记为断开

use std::collections::HashMap;
use std::fs::OpenOptions;
use std::io::Write;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::{Child, ChildStdin, ChildStdout};
use tokio::sync::{oneshot, Mutex as TokioMutex};
use tokio::task::JoinHandle;

use super::resolve_binary_path;
use super::CuaInputAction;

// ── 日志宏 ────────────────────────────────────────────────────────
/// 内部日志宏，复用 pc_automation::logger。
macro_rules! pc_log_ {
    ($msg:expr) => {
        crate::pc_automation::logger::info($msg)
    };
    ($fmt:expr, $($arg:tt)*) => {
        crate::pc_automation::logger::info(&format!($fmt, $($arg)*))
    };
}

/// 追踪级日志宏（仅 CUA_DRIVER_TRACE=1 时由调用方开启，配合 dev 的
/// RUST_LOG=trace 可见）。用于逐条 JSON-RPC 请求/响应追踪。
macro_rules! pc_trace_ {
    ($msg:expr) => {
        crate::pc_automation::logger::trace($msg)
    };
    ($fmt:expr, $($arg:tt)*) => {
        crate::pc_automation::logger::trace(&format!($fmt, $($arg)*))
    };
}

// ── 追踪/分级辅助 ──────────────────────────────────────────────────

/// 是否开启 JSON-RPC 逐条追踪（env: CUA_DRIVER_TRACE=1|true）。
/// 默认关闭，避免生产环境日志膨胀；dev 调试时手动开启。
fn trace_enabled() -> bool {
    static FLAG: OnceLock<bool> = OnceLock::new();
    *FLAG.get_or_init(|| {
        std::env::var("CUA_DRIVER_TRACE")
            .map(|v| v == "1" || v.eq_ignore_ascii_case("true"))
            .unwrap_or(false)
    })
}

/// 将字符串裁剪到 `max` 个字符，超出部分以省略号 + 剩余长度标注，
/// 用于追踪日志中过长的参数/响应体。按字符裁剪，避免截断 UTF-8 多字节字符。
fn truncate(s: &str, max: usize) -> String {
    if s.chars().count() <= max {
        return s.to_string();
    }
    let taken: String = s.chars().take(max).collect();
    format!("{}…(+{} chars)", taken, s.chars().count() - max)
}

/// 解析 cua-driver 独立日志路径：优先主程序旁 `cua-driver.log`，
/// 不可写时回退 `%LOCALAPPDATA%\ai.tupai.desktop\cua-driver.log`。
/// 与 logging.rs 的 `tupai.log` 解析策略保持一致，避免 cua-driver
/// 的 stderr 被并入主日志刷屏、又能在崩溃时完整取证。
fn cua_driver_log_path() -> PathBuf {
    let beside_exe = std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|p| p.to_path_buf()))
        .unwrap_or_else(|| PathBuf::from("."))
        .join("cua-driver.log");
    if OpenOptions::new()
        .create(true)
        .append(true)
        .open(&beside_exe)
        .is_ok()
    {
        return beside_exe;
    }
    if let Some(local) = std::env::var_os("LOCALAPPDATA") {
        let dir = PathBuf::from(local).join("ai.tupai.desktop");
        let _ = std::fs::create_dir_all(&dir);
        return dir.join("cua-driver.log");
    }
    beside_exe
}

// ── 常量 ──────────────────────────────────────────────────────────

/// MCP 协议版本（Cua Driver 实现 2024-11-05）
const MCP_PROTOCOL_VERSION: &str = "2024-11-05";

/// Cua Driver 嵌入模式环境变量
const EMBEDDED_ENV: &str = "CUA_DRIVER_EMBEDDED";
const HOST_BUNDLE_ID_ENV: &str = "CUA_DRIVER_HOST_BUNDLE_ID";
const PERMISSION_MODE_ENV: &str = "CUA_DRIVER_PERMISSION_MODE";
const DANGEROUS_BYPASS_ENV: &str = "CUA_DRIVER_DANGEROUSLY_BYPASS_APPROVALS";

/// 请求超时（秒）。大部分操作应在 5 秒内完成；
/// 截图等重操作可能需要更长时间。
const REQUEST_TIMEOUT_SECS: u64 = 15;

// ── 健康状态 ──────────────────────────────────────────────────────

/// Cua Driver sidecar 的健康状态快照。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
#[derive(Default)]
pub struct CuaDriverHealth {
    /// 二进制是否找到
    pub available: bool,
    /// 进程是否正在运行且已完成 MCP 握手
    pub connected: bool,
    /// 二进制路径
    pub binary_path: Option<String>,
    /// 服务端版本（从 MCP initialize 响应获取）
    pub version: Option<String>,
    /// 可用工具数量（从 tools/list 获取）
    pub tools_count: Option<usize>,
    /// 最近一次错误
    pub last_error: Option<String>,
}


// ── 进程状态 ──────────────────────────────────────────────────────

/// sidecar 进程的运行时状态。保存在 `Mutex` 中，确保线程安全。
struct ProcessState {
    /// stdin 管道 — 用于向 sidecar 发送 JSON-RPC 请求
    stdin: ChildStdin,
    /// 子进程句柄 — 保持存活，防止进程被 kill
    _child: Child,
    /// 后台读者任务句柄 — 保持存活
    _reader_handle: JoinHandle<()>,
    /// MCP 握手是否完成
    initialized: bool,
}

// ── CuaDriverClient ──────────────────────────────────────────────

/// Cua Driver sidecar 客户端。
///
/// 全局单例，通过 `shared()` 获取。线程安全，支持并发请求。
/// 当 sidecar 不可用时，所有方法返回 `Err`，调用方应降级到 enigo。
pub struct CuaDriverClient {
    /// 解析后的二进制路径（只解析一次）
    binary_path: OnceLock<Option<PathBuf>>,
    /// sidecar 进程状态（Mutex 保护 stdin + initialized）
    process: TokioMutex<Option<ProcessState>>,
    /// 待响应的请求映射：request_id → oneshot sender
    pending: Arc<Mutex<HashMap<i64, oneshot::Sender<Value>>>>,
    /// 下一个请求 ID（原子递增）
    next_id: AtomicI64,
    /// 读者任务是否仍在运行（false = 进程已退出）
    reader_alive: Arc<AtomicBool>,
    /// 最近一次错误
    last_error: Arc<Mutex<Option<String>>>,
    /// 服务端版本（从 MCP initialize 响应提取）
    version: Arc<Mutex<Option<String>>>,
    /// 可用工具数量（从 tools/list 统计）
    tools_count: Arc<Mutex<Option<usize>>>,
}

impl CuaDriverClient {
    /// 获取全局单例。
    pub fn shared() -> Arc<CuaDriverClient> {
        static CLIENT: OnceLock<Arc<CuaDriverClient>> = OnceLock::new();
        CLIENT
            .get_or_init(|| {
                Arc::new(CuaDriverClient {
                    binary_path: OnceLock::new(),
                    process: TokioMutex::new(None),
                    pending: Arc::new(Mutex::new(HashMap::new())),
                    next_id: AtomicI64::new(1),
                    reader_alive: Arc::new(AtomicBool::new(false)),
                    last_error: Arc::new(Mutex::new(None)),
                    version: Arc::new(Mutex::new(None)),
                    tools_count: Arc::new(Mutex::new(None)),
                })
            })
            .clone()
    }

    /// 检查 cua-driver 二进制是否可用（不启动进程）。
    pub fn is_available(&self) -> bool {
        self.binary_path
            .get_or_init(resolve_binary_path)
            .is_some()
    }

    /// 获取二进制路径。
    fn binary(&self) -> Result<PathBuf, String> {
        self.binary_path
            .get_or_init(resolve_binary_path)
            .clone()
            .ok_or_else(|| "cua-driver binary not found".to_string())
    }

    // ── 连接管理 ──────────────────────────────────────────────────

    /// 确保 sidecar 进程已启动并完成 MCP 握手。
    ///
    /// 如果进程已死亡（reader_alive == false），自动重启。
    async fn ensure_connected(&self) -> Result<(), String> {
        let mut guard = self.process.lock().await;

        // 检查现有进程是否仍然存活
        if let Some(state) = guard.as_ref() {
            if state.initialized && self.reader_alive.load(Ordering::SeqCst) {
                return Ok(()); // 已连接，直接返回
            }
        }

        // 进程不存在或已死亡 → 清理 + 重启
        if guard.is_some() {
            pc_log_!("cua-driver process died or not initialized — restarting");
        }
        *guard = None;
        self.reader_alive.store(false, Ordering::SeqCst);
        // 重启后版本/工具数需重新探测。
        *self.version.lock().unwrap() = None;
        *self.tools_count.lock().unwrap() = None;

        // 解析二进制路径
        let bin_path = match self.binary() {
            Ok(p) => p,
            Err(e) => {
                // dev 下给出可操作提示：cua-driver 尚未构建，cua 会降级到 enigo。
                if cfg!(debug_assertions) {
                    pc_log_!(
                        "cua-driver 二进制未找到，已降级到 enigo。dev 调试 cua 请先构建: pnpm cua:build (或 node scripts/build-cua-driver.mjs)"
                    );
                }
                return Err(e);
            }
        };

        // spawn 子进程
        let mut cmd = tokio::process::Command::new(&bin_path);

        // 环境变量：嵌入模式 + 无审批
        cmd.env(EMBEDDED_ENV, "1");
        cmd.env(HOST_BUNDLE_ID_ENV, "ai.tupai.desktop");
        cmd.env(PERMISSION_MODE_ENV, "unrestricted");
        cmd.env(DANGEROUS_BYPASS_ENV, "1");

        // ── 调试透传 ──────────────────────────────────────────────
        // 把宿主进程的调试环境变量转发给 cua-driver sidecar，使其在
        // `tauri dev` 调试期间能按与主程序一致的级别打印内部日志，并在
        // 崩溃时输出 backtrace（其 stderr 会被 drain 到主日志的 debug 级）。
        //   * RUST_BACKTRACE: 子进程 panic 时输出栈回溯，便于定位 cua
        //     自身的崩溃；宿主未显式设置时默认开启 "1"。
        //   * RUST_LOG: 让 cua-driver 内部的 tracing 订阅按级别输出
        //     （dev 下主进程通常设 debug/trace），否则子进程静默。
        // 注：CUA_DRIVER_TRACE 仅控制宿主侧 JSON-RPC 逐条追踪，子进程
        // 不读取，故不转发。
        if let Ok(v) = std::env::var("RUST_BACKTRACE") {
            cmd.env("RUST_BACKTRACE", v);
        } else {
            cmd.env("RUST_BACKTRACE", "1");
        }
        if let Ok(v) = std::env::var("RUST_LOG") {
            cmd.env("RUST_LOG", v);
        }

        // stdio 管道
        cmd.stdin(std::process::Stdio::piped())
            .stdout(std::process::Stdio::piped())
            // stderr 必须 drain——否则子进程写满 stderr 管道缓冲（64KB）会
            // 阻塞在写 stderr 上，导致整个 sidecar 假死、所有调用超时。
            .stderr(std::process::Stdio::piped());

        // Windows: 隐藏控制台窗口
        #[cfg(target_os = "windows")]
        {
            const CREATE_NO_WINDOW: u32 = 0x08000000;
            cmd.creation_flags(CREATE_NO_WINDOW);
        }

        let mut child = cmd
            .spawn()
            .map_err(|e| {
                let msg = format!("failed to spawn cua-driver: {}", e);
                self.set_last_error(&msg);
                msg
            })?;

        // 记录子进程 PID，便于用 codelldb "attach" 到 cua-driver 进程调试。
        pc_log_!("cua-driver sidecar spawned, pid={}", child.id().unwrap_or(0));

        let stdin = child
            .stdin
            .take()
            .ok_or_else(|| "cua-driver stdin not available".to_string())?;
        let stdout = child
            .stdout
            .take()
            .ok_or_else(|| "cua-driver stdout not available".to_string())?;
        let stderr = child
            .stderr
            .take()
            .ok_or_else(|| "cua-driver stderr not available".to_string())?;

        // 启动后台读者任务（stdout 响应分发）
        let reader_alive = self.reader_alive.clone();
        let pending = self.pending.clone();
        let last_error = self.last_error.clone();
        let reader_handle = tokio::spawn(async move {
            reader_loop(stdout, reader_alive, pending, last_error).await;
        });

        // stderr drain 任务：读取子进程 stderr，写入独立日志文件
        // `cua-driver.log`（主程序旁，回退 LOCALAPPDATA），防止管道缓冲
        // 填满导致子进程阻塞（死锁）；同时仅把 error/warn 级转发到主
        // 日志，避免 cua-driver 的 debug/trace 刷屏 tupai.log。
        let last_error_stderr = self.last_error.clone();
        tokio::spawn(async move {
            let log_path = cua_driver_log_path();
            let mut file = OpenOptions::new()
                .create(true)
                .append(true)
                .open(&log_path)
                .ok();
            let mut reader = BufReader::new(stderr);
            let mut line = String::new();
            loop {
                line.clear();
                match reader.read_line(&mut line).await {
                    Ok(0) => break,
                    Ok(_) => {
                        let trimmed = line.trim();
                        if !trimmed.is_empty() {
                            // 完整写入独立文件（保留全部诊断）
                            if let Some(f) = file.as_mut() {
                                let _ = writeln!(f, "{}", trimmed);
                                let _ = f.flush();
                            }
                            // 主日志仅转发 error/warn（去噪）
                            let lower = trimmed.to_lowercase();
                            if lower.contains("error") {
                                crate::pc_automation::logger::error(&format!("cua-driver: {}", trimmed));
                            } else if lower.contains("warn") {
                                crate::pc_automation::logger::warn(&format!("cua-driver: {}", trimmed));
                            }
                        }
                    }
                    Err(e) => {
                        *last_error_stderr.lock().unwrap() =
                            Some(format!("cua-driver stderr read error: {}", e));
                        break;
                    }
                }
            }
        });

        let mut state = ProcessState {
            stdin,
            _child: child,
            _reader_handle: reader_handle,
            initialized: false,
        };

        // MCP 握手 — 失败时必须 kill 子进程，否则产生僵尸进程
        // (tokio::process::Child drop 不会自动 kill 进程)
        if let Err(e) = self.do_handshake(&mut state).await {
            // 清理 pending[0]（握手使用固定 ID 0）
            {
                let mut pending = self.pending.lock().unwrap();
                pending.remove(&0);
            }
            // kill 子进程并等待退出，防止僵尸进程
            let _ = state._child.start_kill();
            let _ = tokio::time::timeout(
                std::time::Duration::from_secs(3),
                state._child.wait(),
            ).await;
            self.reader_alive.store(false, Ordering::SeqCst);
            return Err(e);
        }

        state.initialized = true;
        *guard = Some(state);
        self.reader_alive.store(true, Ordering::SeqCst);

        pc_log_!("cua-driver sidecar connected and initialized");
        Ok(())
    }

    /// 确保 sidecar 启动并完成握手，带有限重试（spawn/握手瞬时失败时自愈）。
    /// 首次调用或进程崩溃后调用，最多重试 `MAX_START_ATTEMPTS` 次，每次间隔
    /// 递增退避。供启动预热（app 启动时后台拉起）与首次使用前调用，
    /// 保证「启动正常、运行流畅」。
    pub async fn ensure_started(&self) -> Result<(), String> {
        const MAX_START_ATTEMPTS: u32 = 3;
        let mut last_err = String::new();
        for attempt in 0..MAX_START_ATTEMPTS {
            match self.ensure_connected().await {
                Ok(()) => return Ok(()),
                Err(e) => {
                    last_err = e;
                    if attempt + 1 < MAX_START_ATTEMPTS {
                        // 退避：200ms / 500ms
                        let delay = std::time::Duration::from_millis(200 * (attempt as u64 + 1) + 300);
                        pc_log_!("cua-driver start attempt {} failed ({}), retrying", attempt + 1, last_err);
                        tokio::time::sleep(delay).await;
                    }
                }
            }
        }
        Err(format!("cua-driver failed to start after {} attempts: {}", MAX_START_ATTEMPTS, last_err))
    }

    /// MCP 握手：initialize → notifications/initialized → tools/list(统计工具数)
    async fn do_handshake(&self, state: &mut ProcessState) -> Result<(), String> {
        // 1. 发送 initialize 请求
        let init_request = json!({
            "jsonrpc": "2.0",
            "id": 0,  // 握手使用固定 ID 0
            "method": "initialize",
            "params": {
                "protocolVersion": MCP_PROTOCOL_VERSION,
                "capabilities": {},
                "clientInfo": {
                    "name": "tupai",
                    "version": env!("CARGO_PKG_VERSION"),
                }
            }
        });

        // 注册 pending（直接注册，不走 invoke_tool，因为互斥锁已持有）
        let (tx, rx) = oneshot::channel();
        {
            let mut pending = self.pending.lock().unwrap();
            pending.insert(0, tx);
        }

        self.write_line(state, &init_request).await?;

        // 等待 initialize 响应
        let init_response = tokio::time::timeout(
            std::time::Duration::from_secs(REQUEST_TIMEOUT_SECS),
            rx,
        )
        .await
        .map_err(|_| {
            self.set_last_error("MCP initialize timeout");
            "MCP handshake timeout".to_string()
        })?
        .map_err(|_| {
            self.set_last_error("MCP initialize channel closed");
            "MCP handshake channel closed".to_string()
        })?;

        // 从响应中提取版本信息并缓存
        if let Some(server_info) = init_response.get("serverInfo") {
            if let Some(version) = server_info.get("version").and_then(|v| v.as_str()) {
                *self.version.lock().unwrap() = Some(version.to_string());
                pc_log_!("cua-driver server version: {}", version);
            }
        }

        // 逐条追踪：记录 initialize 握手响应摘要
        if trace_enabled() {
            pc_trace_!(
                "cua-driver ← initialize response: {}",
                truncate(&init_response.to_string(), 1000)
            );
        }

        // 2. 发送 initialized 通知（无 id，无响应）
        let initialized_notification = json!({
            "jsonrpc": "2.0",
            "method": "notifications/initialized"
        });
        self.write_line(state, &initialized_notification).await?;

        // 3. tools/list — 统计可用工具数（供 health() 展示）。
        //    失败不阻断握手：工具数缺失仅影响诊断展示，不影响后续调用。
        let tool_list_id = self.next_id.fetch_add(1, Ordering::SeqCst);
        let (tool_tx, tool_rx) = oneshot::channel();
        {
            let mut pending = self.pending.lock().unwrap();
            pending.insert(tool_list_id, tool_tx);
        }
        let tools_request = json!({
            "jsonrpc": "2.0",
            "id": tool_list_id,
            "method": "tools/list"
        });
        let tools_result = async {
            self.write_line(state, &tools_request).await?;
            let resp = tokio::time::timeout(
                std::time::Duration::from_secs(REQUEST_TIMEOUT_SECS),
                tool_rx,
            )
            .await
            .map_err(|_| "tools/list timeout".to_string())?
            .map_err(|_| "tools/list channel closed".to_string())?;
            if let Some(arr) = resp.pointer("/result/tools").and_then(|t| t.as_array()) {
                let count = arr.len();
                *self.tools_count.lock().unwrap() = Some(count);
                pc_log_!("cua-driver exposes {} tools", count);
            }
            Ok::<(), String>(())
        }
        .await;
        if let Err(e) = tools_result {
            // 工具列表失败仅记日志，不视为握手失败。
            pc_log_!("cua-driver tools/list skipped: {}", e);
            self.pending.lock().unwrap().remove(&tool_list_id);
        }

        Ok(())
    }

    /// 向 sidecar stdin 写入一行 JSON。
    async fn write_line(
        &self,
        state: &mut ProcessState,
        value: &Value,
    ) -> Result<(), String> {
        let mut line = serde_json::to_string(value)
            .map_err(|e| format!("JSON serialize error: {}", e))?;
        line.push('\n');
        state
            .stdin
            .write_all(line.as_bytes())
            .await
            .map_err(|e| {
                let msg = format!("cua-driver stdin write failed: {}", e);
                self.set_last_error(&msg);
                msg
            })?;
        state.stdin.flush().await.map_err(|e| {
            let msg = format!("cua-driver stdin flush failed: {}", e);
            self.set_last_error(&msg);
            msg
        })?;
        Ok(())
    }

    // ── 核心调用 ──────────────────────────────────────────────────

    /// 调用 Cua Driver 的 MCP 工具。
    ///
    /// 内部流程：
    ///   1. 确保连接
    ///   2. 生成 request ID
    ///   3. 注册 oneshot sender
    ///   4. 写入 tools/call 请求
    ///   5. 等待响应（带超时）
    ///   6. 检查 isError 标志
    pub async fn invoke_tool(
        &self,
        name: &str,
        arguments: Value,
    ) -> Result<Value, String> {
        self.ensure_connected().await?;

        let id = self.next_id.fetch_add(1, Ordering::SeqCst);

        // 注册 pending sender
        let (tx, rx) = oneshot::channel();
        {
            let mut pending = self.pending.lock().unwrap();
            pending.insert(id, tx);
        }

        // 构造 tools/call 请求
        let request = json!({
            "jsonrpc": "2.0",
            "id": id,
            "method": "tools/call",
            "params": {
                "name": name,
                "arguments": arguments,
            }
        });

        // 逐条追踪（CUA_DRIVER_TRACE=1 时）：记录发出的调用名与参数摘要
        if trace_enabled() {
            pc_trace_!(
                "cua-driver → tools/call '{}' args={}",
                name,
                truncate(&arguments.to_string(), 500)
            );
        }

        // 写入请求（持有 mutex 仅用于写入）
        {
            let mut guard = self.process.lock().await;
            let state = guard
                .as_mut()
                .ok_or_else(|| "cua-driver not connected".to_string())?;
            self.write_line(state, &request).await?;
        }

        // 等待响应
        let response = tokio::time::timeout(
            std::time::Duration::from_secs(REQUEST_TIMEOUT_SECS),
            rx,
        )
        .await
        .map_err(|_| {
            // 超时 — 清理 pending
            let mut pending = self.pending.lock().unwrap();
            pending.remove(&id);
            let msg = format!("cua-driver tool '{}' timeout ({}s)", name, REQUEST_TIMEOUT_SECS);
            self.set_last_error(&msg);
            msg
        })?
        .map_err(|_| {
            let msg = format!("cua-driver tool '{}' channel closed", name);
            self.set_last_error(&msg);
            msg
        })?;

        // 逐条追踪（CUA_DRIVER_TRACE=1 时）：记录收到的响应摘要
        if trace_enabled() {
            pc_trace_!(
                "cua-driver ← response(id={}): {}",
                id,
                truncate(&response.to_string(), 1000)
            );
        }

        // 检查 JSON-RPC error
        if let Some(error) = response.get("error") {
            let msg = error
                .get("message")
                .and_then(|m| m.as_str())
                .unwrap_or("unknown RPC error");
            return Err(format!("cua-driver RPC error: {}", msg));
        }

        // 提取 result
        let result = response
            .get("result")
            .ok_or_else(|| "cua-driver: missing result field".to_string())?;

        // 检查 MCP isError 标志
        if result
            .get("isError")
            .and_then(|v| v.as_bool())
            .unwrap_or(false)
        {
            // 从 content 中提取错误文本
            let error_text = result
                .get("content")
                .and_then(|c| c.as_array())
                .and_then(|arr| arr.first())
                .and_then(|item| item.get("text"))
                .and_then(|t| t.as_str())
                .unwrap_or("unknown tool error");
            return Err(format!("cua-driver tool error: {}", error_text));
        }

        Ok(result.clone())
    }

    // ── 便捷方法 ──────────────────────────────────────────────────

    /// 左键单击屏幕坐标。
    pub async fn click(&self, x: i32, y: i32) -> Result<(), String> {
        self.invoke_tool("click", json!({ "x": x, "y": y }))
            .await?;
        Ok(())
    }

    /// 左键双击屏幕坐标。
    pub async fn double_click(&self, x: i32, y: i32) -> Result<(), String> {
        self.invoke_tool("double_click", json!({ "x": x, "y": y }))
            .await?;
        Ok(())
    }

    /// 右键单击屏幕坐标。
    pub async fn right_click(&self, x: i32, y: i32) -> Result<(), String> {
        self.invoke_tool("right_click", json!({ "x": x, "y": y }))
            .await?;
        Ok(())
    }

    /// 输入文本。
    pub async fn type_text(&self, text: &str) -> Result<(), String> {
        self.invoke_tool("type_text", json!({ "text": text }))
            .await?;
        Ok(())
    }

    /// 按下单个键（如 "Return", "Escape", "Tab"）。
    pub async fn press_key(&self, key: &str) -> Result<(), String> {
        self.invoke_tool("press_key", json!({ "key": key }))
            .await?;
        Ok(())
    }

    /// 组合键（如 "ctrl+c", "alt+tab"）。
    pub async fn hotkey(&self, keys: &str) -> Result<(), String> {
        self.invoke_tool("hotkey", json!({ "keys": keys }))
            .await?;
        Ok(())
    }

    /// 滚动。
    pub async fn scroll(&self, dx: i32, dy: i32) -> Result<(), String> {
        self.invoke_tool("scroll", json!({ "dx": dx, "dy": dy }))
            .await?;
        Ok(())
    }

    /// 移动鼠标到屏幕坐标（不点击）。
    pub async fn move_cursor(&self, x: i32, y: i32) -> Result<(), String> {
        self.invoke_tool("move_cursor", json!({ "x": x, "y": y }))
            .await?;
        Ok(())
    }

    /// 获取屏幕尺寸。
    pub async fn get_screen_size(&self) -> Result<(i32, i32), String> {
        let result = self.invoke_tool("get_screen_size", json!({})).await?;
        // 只解析一次 JSON 文本，同时提取 width 和 height
        let parsed = result
            .pointer("/content/0/text")
            .and_then(|t| t.as_str())
            .and_then(|s| serde_json::from_str::<Value>(s).ok())
            .ok_or_else(|| "cua-driver: failed to parse screen size".to_string())?;
        let width = parsed
            .get("width")
            .and_then(|w| w.as_i64())
            .ok_or_else(|| "cua-driver: missing width in screen size".to_string())?;
        let height = parsed
            .get("height")
            .and_then(|h| h.as_i64())
            .ok_or_else(|| "cua-driver: missing height in screen size".to_string())?;
        Ok((width as i32, height as i32))
    }

    /// 获取无障碍树（UIA / AXUIElement / AT-SPI）。
    pub async fn get_accessibility_tree(&self) -> Result<Value, String> {
        let result = self
            .invoke_tool("get_accessibility_tree", json!({}))
            .await?;
        // 从 content[0].text 中提取 JSON
        let text = result
            .pointer("/content/0/text")
            .and_then(|t| t.as_str())
            .ok_or_else(|| "cua-driver: missing accessibility tree text".to_string())?;
        serde_json::from_str::<Value>(text)
            .map_err(|e| format!("cua-driver: failed to parse accessibility tree: {}", e))
    }

    /// 获取窗口状态（含截图、元素树、element tokens）。
    pub async fn get_window_state(
        &self,
        window_id: Option<i64>,
        pid: Option<i64>,
    ) -> Result<Value, String> {
        let mut args = json!({});
        if let Some(wid) = window_id {
            args["window_id"] = json!(wid);
        }
        if let Some(p) = pid {
            args["pid"] = json!(p);
        }
        let result = self.invoke_tool("get_window_state", args).await?;
        let text = result
            .pointer("/content/0/text")
            .and_then(|t| t.as_str())
            .ok_or_else(|| "cua-driver: missing window state text".to_string())?;
        serde_json::from_str::<Value>(text)
            .map_err(|e| format!("cua-driver: failed to parse window state: {}", e))
    }

    /// 执行通用输入动作。替代 engine.rs 中的 perform_step_input。
    pub async fn perform_input(&self, action: &CuaInputAction) -> Result<(), String> {
        match action {
            CuaInputAction::Click { x, y } => self.click(*x, *y).await,
            CuaInputAction::DoubleClick { x, y } => self.double_click(*x, *y).await,
            CuaInputAction::RightClick { x, y } => self.right_click(*x, *y).await,
            CuaInputAction::TypeText { text } => self.type_text(text).await,
            CuaInputAction::PressKey { key } => self.press_key(key).await,
            CuaInputAction::Hotkey { keys } => self.hotkey(keys).await,
            CuaInputAction::Scroll { dx, dy } => self.scroll(*dx, *dy).await,
            CuaInputAction::MoveCursor { x, y } => self.move_cursor(*x, *y).await,
            CuaInputAction::Wait { ms } => {
                tokio::time::sleep(std::time::Duration::from_millis(*ms)).await;
                Ok(())
            }
        }
    }

    // ── 健康检查 ──────────────────────────────────────────────────

    /// 获取当前健康状态。
    pub async fn health(&self) -> CuaDriverHealth {
        let bin = self
            .binary_path
            .get_or_init(resolve_binary_path)
            .clone();

        let connected = {
            let guard = self.process.lock().await;
            guard
                .as_ref()
                .is_some_and(|s| s.initialized && self.reader_alive.load(Ordering::SeqCst))
        };

        CuaDriverHealth {
            available: bin.is_some(),
            connected,
            binary_path: bin.as_ref().map(|p| p.display().to_string()),
            version: self.version.lock().unwrap().clone(),
            tools_count: *self.tools_count.lock().unwrap(),
            last_error: self.last_error.lock().unwrap().clone(),
        }
    }

    /// 关闭 sidecar 进程。
    ///
    /// 先发送 shutdown 通知，等待最多 3 秒优雅退出；
    /// 超时后强制 kill，防止无限挂起。
    pub async fn shutdown(&self) {
        let mut guard = self.process.lock().await;
        if let Some(mut state) = guard.take() {
            // 尝试优雅关闭：发送 shutdown 通知
            let _ = state
                .stdin
                .write_all(b"{\"jsonrpc\":\"2.0\",\"method\":\"shutdown\"}\n")
                .await;
            let _ = state.stdin.flush().await;
            // 等待进程退出（3 秒超时）
            let exited = tokio::time::timeout(
                std::time::Duration::from_secs(3),
                state._child.wait(),
            ).await;
            // 超时未退出 → 强制 kill
            if exited.is_err() {
                pc_log_!("cua-driver did not exit gracefully — killing");
                let _ = state._child.start_kill();
                let _ = state._child.wait().await;
            }
        }
        self.reader_alive.store(false, Ordering::SeqCst);
        pc_log_!("cua-driver sidecar shut down");
    }

    // ── 内部辅助 ──────────────────────────────────────────────────

    fn set_last_error(&self, msg: &str) {
        *self.last_error.lock().unwrap() = Some(msg.to_string());
    }
}

// ── 后台读者任务 ──────────────────────────────────────────────────

/// 后台任务：逐行读取 sidecar stdout，解析 JSON-RPC 响应，
/// 并通过 oneshot channel 将响应分发给对应的等待者。
///
/// 当 stdout 关闭（进程退出）时，清理所有 pending 请求并设置
/// reader_alive = false。
async fn reader_loop(
    stdout: ChildStdout,
    reader_alive: Arc<AtomicBool>,
    pending: Arc<Mutex<HashMap<i64, oneshot::Sender<Value>>>>,
    last_error: Arc<Mutex<Option<String>>>,
) {
    let mut reader = BufReader::new(stdout);
    let mut line = String::new();

    loop {
        line.clear();
        match reader.read_line(&mut line).await {
            Ok(0) => {
                // EOF — 进程已退出
                break;
            }
            Ok(_) => {
                let trimmed = line.trim();
                if trimmed.is_empty() {
                    continue;
                }
                // 解析 JSON-RPC 响应
                let parsed: Result<Value, _> = serde_json::from_str(trimmed);
                match parsed {
                    Ok(value) => {
                        // 提取 id（通知没有 id，跳过）
                        if let Some(id) = value.get("id").and_then(|i| i.as_i64()) {
                            let sender = {
                                let mut pending = pending.lock().unwrap();
                                pending.remove(&id)
                            };
                            if let Some(sender) = sender {
                                let _ = sender.send(value);
                            }
                        }
                        // 通知（无 id）— 静默忽略
                    }
                    Err(e) => {
                        pc_log_!(&format!(
                            "cua-driver: failed to parse stdout line: {} ({})",
                            e,
                            trimmed.chars().take(100).collect::<String>()
                        ));
                    }
                }
            }
            Err(e) => {
                *last_error.lock().unwrap() =
                    Some(format!("cua-driver stdout read error: {}", e));
                break;
            }
        }
    }

    // 进程退出 — 清理所有 pending 请求
    reader_alive.store(false, Ordering::SeqCst);
    let mut pending = pending.lock().unwrap();
    for (_, sender) in pending.drain() {
        let _ = sender.send(json!({
            "error": {
                "code": -32000,
                "message": "cua-driver process exited",
            }
        }));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn truncate_short_string_is_unchanged() {
        assert_eq!(truncate("hello", 80), "hello");
    }

    #[test]
    fn truncate_empty_string_is_unchanged() {
        assert_eq!(truncate("", 10), "");
    }

    #[test]
    fn truncate_ascii_over_limit_appends_suffix() {
        let out = truncate("abcdefghij", 4);
        assert_eq!(out, "abcd…(+6 chars)");
    }

    #[test]
    fn truncate_counts_unicode_codepoints_not_bytes() {
        // "中文测试" = 4 个字符，max=2 应保留前 2 个字符而非前 2 字节
        let out = truncate("中文测试", 2);
        assert_eq!(out, "中文…(+2 chars)");
    }

    #[test]
    fn truncate_exact_boundary_is_not_cut() {
        assert_eq!(truncate("abcd", 4), "abcd");
    }

    #[test]
    fn cua_driver_log_path_is_writable() {
        // 验证独立日志路径可创建/追加，确保 cua-driver 的 stderr 有处可写
        let p = cua_driver_log_path();
        assert!(
            OpenOptions::new().create(true).append(true).open(&p).is_ok(),
            "cua-driver log path must be writable: {:?}",
            p
        );
    }
}


