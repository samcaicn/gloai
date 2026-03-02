use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use tokio::sync::{oneshot, Mutex as AsyncMutex};
use tokio_tungstenite::{connect_async, tungstenite::protocol::Message};

use crate::logger::Logger;
use crate::storage::KvStore;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GoClawConfig {
    pub enabled: bool,
    pub binary_path: Option<String>,
    pub auto_start: bool,
    pub ws_url: String,
    pub http_url: String,
    pub auto_reconnect: bool,
    pub start_timeout_ms: u64,
    pub ws_connect_timeout_ms: u64,
}

impl Default for GoClawConfig {
    fn default() -> Self {
        GoClawConfig {
            enabled: true,
            binary_path: None,
            auto_start: true,
            ws_url: "ws://127.0.0.1:28789/ws".to_string(),
            http_url: "http://127.0.0.1:28788".to_string(),
            auto_reconnect: true,
            start_timeout_ms: 5000,
            ws_connect_timeout_ms: 10000,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GoClawStatus {
    pub running: bool,
    pub connected: bool,
    pub binary_path: Option<String>,
    pub ws_url: String,
    pub http_url: String,
    pub error: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct JsonRpcRequest {
    jsonrpc: String,
    id: String,
    method: String,
    params: serde_json::Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct JsonRpcResponse {
    jsonrpc: String,
    id: Option<String>,
    result: Option<serde_json::Value>,
    error: Option<JsonRpcError>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct JsonRpcError {
    code: i32,
    message: String,
    data: Option<serde_json::Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct JsonRpcNotification {
    jsonrpc: String,
    method: String,
    params: serde_json::Value,
}

type PendingRequests =
    Arc<Mutex<HashMap<String, oneshot::Sender<Result<serde_json::Value, String>>>>>;
type NotificationCallback =
    Arc<Mutex<Option<Box<dyn Fn(String, serde_json::Value) + Send + Sync>>>>;

struct WebSocketConnection {
    write: Arc<
        AsyncMutex<
            futures_util::stream::SplitSink<
                tokio_tungstenite::WebSocketStream<
                    tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>,
                >,
                Message,
            >,
        >,
    >,
    _task: tokio::task::JoinHandle<()>,
}

pub struct GoClawManager {
    kv_store: KvStore,
    config: Arc<Mutex<GoClawConfig>>,
    process: Arc<Mutex<Option<Child>>>,
    pending_requests: PendingRequests,
    request_id: Arc<Mutex<u64>>,
    ws_connection: Arc<AsyncMutex<Option<WebSocketConnection>>>,
    notification_callback: NotificationCallback,
    last_error: Arc<Mutex<Option<String>>>,
    logger: Arc<Mutex<Logger>>,
}

impl GoClawManager {
    pub fn new(kv_store: KvStore, logger: Logger) -> Self {
        let config = if let Ok(Some(json)) = kv_store.get("goclaw_config") {
            serde_json::from_str(&json).unwrap_or_else(|_| GoClawConfig::default())
        } else {
            GoClawConfig::default()
        };

        GoClawManager {
            kv_store,
            config: Arc::new(Mutex::new(config)),
            process: Arc::new(Mutex::new(None)),
            pending_requests: Arc::new(Mutex::new(HashMap::new())),
            request_id: Arc::new(Mutex::new(0)),
            ws_connection: Arc::new(AsyncMutex::new(None)),
            notification_callback: Arc::new(Mutex::new(None)),
            last_error: Arc::new(Mutex::new(None)),
            logger: Arc::new(Mutex::new(logger)),
        }
    }

    fn save_config(&self) -> anyhow::Result<()> {
        let config = self.config.lock().unwrap();
        let json = serde_json::to_string(&*config)?;
        self.kv_store.set("goclaw_config", &json)?;
        Ok(())
    }

    pub fn get_config(&self) -> GoClawConfig {
        self.config.lock().unwrap().clone()
    }

    pub fn set_config(&self, config: GoClawConfig) -> anyhow::Result<()> {
        *self.config.lock().unwrap() = config;
        self.save_config()?;
        Ok(())
    }

    pub fn get_status(&self) -> GoClawStatus {
        let config = self.config.lock().unwrap();
        let running = self.is_running();
        let connected = self.ws_connection.blocking_lock().is_some();
        let error = self.last_error.lock().unwrap().clone();

        let binary_path = self
            .find_binary()
            .ok()
            .map(|p| p.to_string_lossy().to_string());

        GoClawStatus {
            running,
            connected,
            binary_path,
            ws_url: config.ws_url.clone(),
            http_url: config.http_url.clone(),
            error,
        }
    }

    pub fn set_notification_callback<F>(&self, callback: F)
    where
        F: Fn(String, serde_json::Value) + Send + Sync + 'static,
    {
        *self.notification_callback.lock().unwrap() = Some(Box::new(callback));
    }

    fn find_binary(&self) -> anyhow::Result<PathBuf> {
        let config = self.config.lock().unwrap();

        // 首先检查配置中指定的路径
        if let Some(path) = &config.binary_path {
            let path = Path::new(path);
            if path.exists() {
                self.logger.lock().unwrap().info(&format!("Using configured binary path: {:?}", path));
                return Ok(path.to_path_buf());
            } else {
                self.logger.lock().unwrap().warn(&format!("Configured binary path does not exist: {:?}", path));
            }
        }

        let binary_names = Self::get_binary_names();
        self.logger.lock().unwrap().info(&format!("Searching for binary names: {:?}", binary_names));

        // 检查构建目录（target/goclaw）
        if let Ok(current_dir) = std::env::current_dir() {
            // 检查当前目录下的 target 目录
            let target_dir = current_dir.join("target");
            if target_dir.is_dir() {
                // 检查 debug 和 release 目录
                for profile in &["debug", "release"] {
                    let goclaw_dir = target_dir.join(profile).join("goclaw");
                    if goclaw_dir.is_dir() {
                        self.logger.lock().unwrap().info(&format!("Checking build directory: {:?}", goclaw_dir));
                        for name in &binary_names {
                            let path = goclaw_dir.join(name);
                            if path.exists() {
                                self.logger.lock().unwrap().info(&format!("Found binary in build directory: {:?}", path));
                                return Ok(path);
                            }
                        }
                    }
                }
            }
        }

        // 检查 Tauri 资源目录
        if let Ok(exe_dir) = std::env::current_exe() {
            if let Some(dir) = exe_dir.parent() {
                // 检查 Resources/goclaw 目录（针对打包后的应用）
                let resources_dir = dir.join("Resources");
                if resources_dir.is_dir() {
                        self.logger.lock().unwrap().info(&format!("Checking Resources directory contents: {:?}", std::fs::read_dir(&resources_dir).unwrap_or_else(|_| std::fs::read_dir("/").unwrap()).collect::<Result<Vec<_>, _>>()));
                        let goclaw_dir = resources_dir.join("goclaw");
                        if goclaw_dir.is_dir() {
                            self.logger.lock().unwrap().info(&format!("Checking Resources/goclaw directory: {:?}", goclaw_dir));
                            self.logger.lock().unwrap().info(&format!("Resources/goclaw directory contents: {:?}", std::fs::read_dir(&goclaw_dir).unwrap_or_else(|_| std::fs::read_dir("/").unwrap()).collect::<Result<Vec<_>, _>>()));
                            for name in &binary_names {
                                let path = goclaw_dir.join(name);
                                self.logger.lock().unwrap().info(&format!("Checking binary path: {:?}, exists: {:?}", path, path.exists()));
                                if path.exists() {
                                    self.logger.lock().unwrap().info(&format!("Found binary in Resources/goclaw directory: {:?}", path));
                                    return Ok(path);
                                }
                            }
                        } else {
                            self.logger.lock().unwrap().info(&format!("Resources/goclaw directory does not exist: {:?}", goclaw_dir));
                        }
                    }
            }
        }

        // 检查应用目录
        if let Ok(exe_dir) = std::env::current_exe() {
            self.logger.lock().unwrap().info(&format!("Current executable directory: {:?}", exe_dir));
            if let Some(dir) = exe_dir.parent() {
                // 检查当前目录
                for name in &binary_names {
                    let path = dir.join(name);
                    if path.exists() {
                        self.logger.lock().unwrap().info(&format!("Found binary in executable directory: {:?}", path));
                        return Ok(path);
                    }
                }

                // 检查 goclaw 子目录
                let goclaw_dir = dir.join("goclaw");
                if goclaw_dir.is_dir() {
                    self.logger.lock().unwrap().info(&format!("Checking goclaw subdirectory: {:?}", goclaw_dir));
                    for name in &binary_names {
                        let path = goclaw_dir.join(name);
                        if path.exists() {
                            self.logger.lock().unwrap().info(&format!("Found binary in goclaw subdirectory: {:?}", path));
                            return Ok(path);
                        }
                    }
                }

                // 检查上一级目录（针对 universal 构建）
                if let Some(parent_dir) = dir.parent() {
                    self.logger.lock().unwrap().info(&format!("Checking parent directory: {:?}", parent_dir));
                    for name in &binary_names {
                        let path = parent_dir.join(name);
                        if path.exists() {
                            self.logger.lock().unwrap().info(&format!("Found binary in parent directory: {:?}", path));
                            return Ok(path);
                        }
                    }

                    // 检查 Resources 目录（针对 universal 构建）
                    let resources_dir = parent_dir.join("Resources");
                    if resources_dir.is_dir() {
                        self.logger.lock().unwrap().info(&format!("Checking Resources directory: {:?}", resources_dir));
                        for name in &binary_names {
                            let path = resources_dir.join(name);
                            if path.exists() {
                                self.logger.lock().unwrap().info(&format!("Found binary in Resources directory: {:?}", path));
                                return Ok(path);
                            }
                        }

                        // 检查 Resources/goclaw 目录
                        let resources_goclaw_dir = resources_dir.join("goclaw");
                        if resources_goclaw_dir.is_dir() {
                            self.logger.lock().unwrap().info(&format!("Checking Resources/goclaw directory: {:?}", resources_goclaw_dir));
                            for name in &binary_names {
                                let path = resources_goclaw_dir.join(name);
                                if path.exists() {
                                    self.logger.lock().unwrap().info(&format!("Found binary in Resources/goclaw directory: {:?}", path));
                                    return Ok(path);
                                }
                            }
                        }
                    }

                    // 检查更深层次的目录结构
                    if let Some(grandparent_dir) = parent_dir.parent() {
                        self.logger.lock().unwrap().info(&format!("Checking grandparent directory: {:?}", grandparent_dir));
                        for name in &binary_names {
                            let path = grandparent_dir.join(name);
                            if path.exists() {
                                self.logger.lock().unwrap().info(&format!("Found binary in grandparent directory: {:?}", path));
                                return Ok(path);
                            }
                        }

                        // 检查 grandparent/goclaw 目录
                        let grandparent_goclaw_dir = grandparent_dir.join("goclaw");
                        if grandparent_goclaw_dir.is_dir() {
                            self.logger.lock().unwrap().info(&format!("Checking grandparent/goclaw directory: {:?}", grandparent_goclaw_dir));
                            for name in &binary_names {
                                let path = grandparent_goclaw_dir.join(name);
                                if path.exists() {
                                    self.logger.lock().unwrap().info(&format!("Found binary in grandparent/goclaw directory: {:?}", path));
                                    return Ok(path);
                                }
                            }
                        }

                        // 检查 grandparent/Resources 目录
                        let grandparent_resources_dir = grandparent_dir.join("Resources");
                        if grandparent_resources_dir.is_dir() {
                            self.logger.lock().unwrap().info(&format!("Checking grandparent/Resources directory: {:?}", grandparent_resources_dir));
                            for name in &binary_names {
                                let path = grandparent_resources_dir.join(name);
                                if path.exists() {
                                    self.logger.lock().unwrap().info(&format!("Found binary in grandparent/Resources directory: {:?}", path));
                                    return Ok(path);
                                }
                            }

                            // 检查 grandparent/Resources/goclaw 目录
                            let grandparent_resources_goclaw_dir = grandparent_resources_dir.join("goclaw");
                            if grandparent_resources_goclaw_dir.is_dir() {
                                self.logger.lock().unwrap().info(&format!("Checking grandparent/Resources/goclaw directory: {:?}", grandparent_resources_goclaw_dir));
                                for name in &binary_names {
                                    let path = grandparent_resources_goclaw_dir.join(name);
                                    if path.exists() {
                                        self.logger.lock().unwrap().info(&format!("Found binary in grandparent/Resources/goclaw directory: {:?}", path));
                                        return Ok(path);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 检查用户主目录
        if let Some(home) = dirs::home_dir() {
            self.logger.lock().unwrap().info(&format!("Checking home directory: {:?}", home));
            let search_paths = vec![
                home.join(".glo"),
                home.join(".glo").join("goclaw"),
                home.join("goclaw"),
                home.join("bin"),
                home.join("local").join("bin"),
            ];

            for base_dir in search_paths {
                if !base_dir.is_dir() {
                    continue;
                }
                self.logger.lock().unwrap().info(&format!("Checking directory: {:?}", base_dir));
                for name in &binary_names {
                    let path = base_dir.join(name);
                    if path.exists() {
                        self.logger.lock().unwrap().info(&format!("Found binary: {:?}", path));
                        return Ok(path);
                    }
                }

                // 检查 darwin 子目录
                let darwin_dir = base_dir.join("darwin");
                if darwin_dir.is_dir() {
                    self.logger.lock().unwrap().info(&format!("Checking darwin subdirectory: {:?}", darwin_dir));
                    for name in &binary_names {
                        let path = darwin_dir.join(name);
                        if path.exists() {
                            self.logger.lock().unwrap().info(&format!("Found binary in darwin subdirectory: {:?}", path));
                            return Ok(path);
                        }
                    }
                }
            }
        }

        // 检查系统路径
        if cfg!(target_os = "macos") {
            let app_paths = vec![
                PathBuf::from("/Applications/goclaw.app/Contents/MacOS/goclaw"),
                PathBuf::from("/Applications/goclaw.app/Contents/MacOS/goclaw-universal"),
                PathBuf::from("/Applications/goclaw.app/Contents/MacOS/goclaw-arm64"),
                PathBuf::from("/Applications/goclaw.app/Contents/MacOS/goclaw-amd64"),
                PathBuf::from("/usr/local/bin/goclaw"),
                PathBuf::from("/opt/homebrew/bin/goclaw"),
            ];
            for path in app_paths {
                if path.exists() {
                    self.logger.lock().unwrap().info(&format!("Found binary in system path: {:?}", path));
                    return Ok(path);
                }
            }
        }

        Err(anyhow::anyhow!(
            "GoClaw binary not found. Searched paths: build directory, ~/.glo, ~/.glo/goclaw, application directory, system paths"
        ))
    }

    fn get_binary_names() -> Vec<String> {
        if cfg!(target_os = "windows") {
            vec!["goclaw.exe".to_string()]
        } else if cfg!(target_os = "macos") {
            let arch = std::env::consts::ARCH;
            if arch == "aarch64" {
                vec!["goclaw-arm64".to_string(), "goclaw-universal".to_string(), "goclaw".to_string()]
            } else {
                vec!["goclaw-amd64".to_string(), "goclaw-universal".to_string(), "goclaw".to_string()]
            }
        } else {
            vec!["goclaw".to_string()]
        }
    }

    pub async fn start(&self) -> anyhow::Result<()> {
        let (is_enabled, timeout_ms) = {
            let config = self.config.lock().unwrap();
            (config.enabled, config.start_timeout_ms)
        };

        if !is_enabled {
            let err = "GoClaw is disabled in config".to_string();
            *self.last_error.lock().unwrap() = Some(err.clone());
            return Err(anyhow::anyhow!(err));
        }

        if self.is_running() {
            self.logger.lock().unwrap().info("Already running, skipping start");
            return Ok(());
        }

        self.logger.lock().unwrap().info("Starting GoClaw service...");
        
        let binary_path = match self.find_binary() {
            Ok(path) => {
                self.logger.lock().unwrap().info(&format!("Found GoClaw binary at: {:?}", path));
                path
            },
            Err(e) => {
                let err = format!("Failed to find GoClaw binary: {}", e);
                *self.last_error.lock().unwrap() = Some(err.clone());
                return Err(anyhow::anyhow!(err));
            }
        };

        if !binary_path.exists() {
            let err = format!("GoClaw binary not found at: {:?}", binary_path);
            *self.last_error.lock().unwrap() = Some(err.clone());
            return Err(anyhow::anyhow!(err));
        }

        if !binary_path.is_file() {
            let err = format!("GoClaw path is not a file: {:?}", binary_path);
            *self.last_error.lock().unwrap() = Some(err.clone());
            return Err(anyhow::anyhow!(err));
        }

        // 检查文件权限
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            
            if let Ok(permissions) = binary_path.metadata() {
                let perms = permissions.permissions();
                self.logger.lock().unwrap().info(&format!("Binary permissions: {:o}", perms.mode()));
                if (perms.mode() & 0o111) == 0 {
                    let err = format!("GoClaw binary is not executable: {:?}", binary_path);
                    *self.last_error.lock().unwrap() = Some(err.clone());
                    return Err(anyhow::anyhow!(err));
                }
            }
        }

        self.logger.lock().unwrap().info(&format!("Starting GoClaw from: {:?}", binary_path));
        self.logger.lock().unwrap().info(&format!("Command: {:?} start", binary_path));

        // 尝试不同的启动命令
        let mut child = match Command::new(&binary_path)
            .arg("start")
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
        {
            Ok(c) => c,
            Err(e) => {
                self.logger.lock().unwrap().warn(&format!("Failed to start with 'start' argument: {}, trying without argument...", e));
                // 尝试不使用 start 参数
                match Command::new(&binary_path)
                    .stdin(Stdio::null())
                    .stdout(Stdio::piped())
                    .stderr(Stdio::piped())
                    .spawn()
                {
                    Ok(c) => {
                        self.logger.lock().unwrap().info("Started without 'start' argument");
                        c
                    },
                    Err(e) => {
                        let err = format!("Failed to start GoClaw: {}", e);
                        *self.last_error.lock().unwrap() = Some(err.clone());
                        return Err(anyhow::anyhow!(err));
                    }
                }
            }
        };

        let pid = child.id();
        self.logger.lock().unwrap().info(&format!("Started with PID: {}", pid));

        // 检查进程是否立即退出
        tokio::time::sleep(Duration::from_millis(1000)).await;
        match child.try_wait() {
            Ok(Some(status)) => {
                // 尝试读取标准输出和标准错误
                let stdout = child.stdout.take().and_then(|stdout| std::io::read_to_string(stdout).ok());
                let stderr = child.stderr.take().and_then(|stderr| std::io::read_to_string(stderr).ok());
                self.logger.lock().unwrap().error(&format!("Process stdout: {:?}", stdout));
                self.logger.lock().unwrap().error(&format!("Process stderr: {:?}", stderr));
                let err = format!("GoClaw process exited immediately with status: {:?}", status);
                *self.last_error.lock().unwrap() = Some(err.clone());
                return Err(anyhow::anyhow!(err));
            }
            Ok(None) => {
                self.logger.lock().unwrap().info("Process is still running");
                // 进程仍在运行，继续
            }
            Err(e) => {
                let err = format!("Error checking GoClaw process status: {}", e);
                *self.last_error.lock().unwrap() = Some(err.clone());
                return Err(anyhow::anyhow!(err));
            }
        }

        *self.process.lock().unwrap() = Some(child);
        *self.last_error.lock().unwrap() = None;

        let timeout = Duration::from_millis(timeout_ms);
        let start_time = std::time::Instant::now();
        self.logger.lock().unwrap().info(&format!("Waiting for service to be ready (timeout: {}ms)", timeout_ms));

        while start_time.elapsed() < timeout {
            if self.check_port_available() {
                self.logger.lock().unwrap().info("Service is ready");
                return Ok(());
            }
            self.logger.lock().unwrap().info("Service not ready yet, waiting...");
            tokio::time::sleep(Duration::from_millis(500)).await;
        }

        // 检查进程状态
        let mut process_guard = self.process.lock().unwrap();
        if let Some(ref mut child) = *process_guard {
            match child.try_wait() {
                Ok(Some(status)) => {
                    // 尝试读取标准输出和标准错误
                    let stdout = child.stdout.take().and_then(|stdout| std::io::read_to_string(stdout).ok());
                    let stderr = child.stderr.take().and_then(|stderr| std::io::read_to_string(stderr).ok());
                    self.logger.lock().unwrap().error(&format!("Process stdout: {:?}", stdout));
                    self.logger.lock().unwrap().error(&format!("Process stderr: {:?}", stderr));
                    let err = format!("GoClaw process exited during startup with status: {:?}", status);
                    *self.last_error.lock().unwrap() = Some(err.clone());
                    return Err(anyhow::anyhow!(err));
                }
                Ok(None) => {
                    // 进程仍在运行，但端口不可用
                    self.logger.lock().unwrap().info("Started but service may not be ready yet");
                }
                Err(e) => {
                    let err = format!("Error checking GoClaw process status: {}", e);
                    *self.last_error.lock().unwrap() = Some(err.clone());
                    return Err(anyhow::anyhow!(err));
                }
            }
        }

        Ok(())
    }

    fn check_port_available(&self) -> bool {
        let config = self.config.lock().unwrap();

        if let Some(addr) = config.ws_url.strip_prefix("ws://") {
            let host_port = addr.split('/').next().unwrap_or(addr);
            if let Some((host, port)) = host_port.rsplit_once(':') {
                if let Ok(port) = port.parse::<u16>() {
                    if let Ok(socket) = std::net::TcpListener::bind(format!("{}:{}", host, port)) {
                        drop(socket);
                        return false;
                    }
                    return true;
                }
            }
        }

        false
    }

    pub async fn stop(&self) -> anyhow::Result<()> {
        {
            let mut ws_conn = self.ws_connection.lock().await;
            *ws_conn = None;
        }

        let mut process_guard = self.process.lock().unwrap();
        if let Some(mut child) = process_guard.take() {
            println!("[GoClaw] Stopping GoClaw...");

            let _ = child.kill();
            let _ = child.wait();

            println!("[GoClaw] GoClaw stopped");
        }
        Ok(())
    }

    pub fn is_running(&self) -> bool {
        let mut process_guard = self.process.lock().unwrap();
        if let Some(ref mut child) = *process_guard {
            match child.try_wait() {
                Ok(None) => true,
                _ => false,
            }
        } else {
            false
        }
    }

    pub async fn restart(&self) -> anyhow::Result<()> {
        println!("[GoClaw] Restarting...");

        let was_connected = {
            let conn = self.ws_connection.lock().await;
            conn.is_some()
        };

        self.stop().await?;
        tokio::time::sleep(Duration::from_millis(1000)).await;
        self.start().await?;

        if was_connected {
            tokio::time::sleep(Duration::from_millis(2000)).await;
            let _ = self.connect_websocket().await;
        }

        Ok(())
    }

    pub async fn auto_start_if_enabled(&self) -> anyhow::Result<()> {
        let (should_start, auto_reconnect) = {
            let config = self.config.lock().unwrap();
            (config.enabled && config.auto_start, config.auto_reconnect)
        };

        if should_start {
            println!("[GoClaw] Auto-start enabled, starting...");

            match self.start().await {
                Ok(_) => {
                    if auto_reconnect {
                        tokio::time::sleep(Duration::from_millis(2000)).await;
                        if let Err(e) = self.connect_websocket().await {
                            println!("[GoClaw] Auto-connect failed: {}", e);
                        }
                    }
                }
                Err(e) => {
                    println!("[GoClaw] Auto-start failed: {}", e);
                    *self.last_error.lock().unwrap() = Some(e.to_string());
                }
            }
        }
        Ok(())
    }

    fn next_request_id(&self) -> String {
        let mut id = self.request_id.lock().unwrap();
        *id += 1;
        id.to_string()
    }

    pub async fn connect_websocket(&self) -> anyhow::Result<()> {
        let mut ws_conn = self.ws_connection.lock().await;

        if ws_conn.is_some() {
            return Ok(());
        }

        let (ws_url, timeout_ms) = {
            let config = self.config.lock().unwrap();
            (config.ws_url.clone(), config.ws_connect_timeout_ms)
        };

        println!("[GoClaw] Connecting to WebSocket: {}", ws_url);

        let ws_url_clone = ws_url.clone();
        let connect_future = async { connect_async(&ws_url_clone).await };

        let result = tokio::time::timeout(Duration::from_millis(timeout_ms), connect_future).await;

        let (ws_stream, _) = match result {
            Ok(Ok(s)) => s,
            Ok(Err(e)) => {
                let err = format!("WebSocket connection failed: {}", e);
                *self.last_error.lock().unwrap() = Some(err.clone());
                return Err(anyhow::anyhow!(err));
            }
            Err(_) => {
                let err = "WebSocket connection timeout".to_string();
                *self.last_error.lock().unwrap() = Some(err.clone());
                return Err(anyhow::anyhow!(err));
            }
        };

        let (write, mut read) = ws_stream.split();

        let pending_requests = self.pending_requests.clone();
        let notification_callback = self.notification_callback.clone();
        let ws_connection = self.ws_connection.clone();
        let last_error = self.last_error.clone();

        let task = tokio::spawn(async move {
            while let Some(msg) = read.next().await {
                match msg {
                    Ok(Message::Text(text)) => {
                        if let Ok(response) = serde_json::from_str::<JsonRpcResponse>(&text) {
                            if let Some(response_id) = &response.id {
                                let mut pending = pending_requests.lock().unwrap();
                                if let Some(tx) = pending.remove(response_id) {
                                    if let Some(error) = response.error {
                                        let _ = tx.send(Err(error.message));
                                    } else if let Some(result) = response.result {
                                        let _ = tx.send(Ok(result));
                                    }
                                }
                            }
                        } else if let Ok(notification) =
                            serde_json::from_str::<JsonRpcNotification>(&text)
                        {
                            let callback = notification_callback.lock().unwrap();
                            if let Some(cb) = &*callback {
                                cb(notification.method, notification.params);
                            }
                        }
                    }
                    Ok(Message::Close(_)) => {
                        println!("[GoClaw] WebSocket closed by server");
                        break;
                    }
                    Err(e) => {
                        println!("[GoClaw] WebSocket error: {}", e);
                        *last_error.lock().unwrap() = Some(format!("WebSocket error: {}", e));
                        break;
                    }
                    _ => {}
                }
            }

            let mut conn = ws_connection.lock().await;
            *conn = None;
            println!("[GoClaw] WebSocket disconnected");
        });

        *ws_conn = Some(WebSocketConnection {
            write: Arc::new(AsyncMutex::new(write)),
            _task: task,
        });

        println!("[GoClaw] WebSocket connected successfully");
        *self.last_error.lock().unwrap() = None;
        Ok(())
    }

    pub async fn disconnect_websocket(&self) {
        let mut ws_conn = self.ws_connection.lock().await;
        *ws_conn = None;
        println!("[GoClaw] WebSocket disconnected");
    }

    pub async fn request(
        &self,
        method: String,
        params: serde_json::Value,
    ) -> anyhow::Result<serde_json::Value> {
        self.connect_websocket().await?;

        let id = self.next_request_id();

        let request = JsonRpcRequest {
            jsonrpc: "2.0".to_string(),
            id: id.clone(),
            method,
            params,
        };

        let (tx, rx) = oneshot::channel();
        {
            let mut pending = self.pending_requests.lock().unwrap();
            pending.insert(id, tx);
        }

        let ws_conn = self.ws_connection.lock().await;
        if let Some(conn) = &*ws_conn {
            let mut write = conn.write.lock().await;
            write
                .send(Message::Text(serde_json::to_string(&request)?))
                .await?;
        } else {
            return Err(anyhow::anyhow!("WebSocket not connected"));
        }
        drop(ws_conn);

        let result = tokio::time::timeout(Duration::from_secs(30), rx)
            .await
            .map_err(|_| anyhow::anyhow!("Request timeout"))?
            .map_err(|e| anyhow::anyhow!("Channel error: {}", e))?;

        result.map_err(|e| anyhow::anyhow!(e))
    }

    pub async fn send_message(&self, content: String) -> anyhow::Result<serde_json::Value> {
        let params = serde_json::json!({ "content": content });
        self.request("agent".to_string(), params).await
    }

    pub async fn list_sessions(&self) -> anyhow::Result<serde_json::Value> {
        self.request("sessions.list".to_string(), serde_json::json!({}))
            .await
    }

    pub async fn health_check(&self) -> bool {
        if !self.is_running() {
            return false;
        }

        let conn = self.ws_connection.lock().await;
        conn.is_some()
    }
}

impl Drop for GoClawManager {
    fn drop(&mut self) {
        let mut process_guard = self.process.lock().unwrap();
        if let Some(ref mut child) = *process_guard {
            let _ = child.kill();
        }
    }
}
