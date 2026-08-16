// AcpClientService — 简化的 ACP 客户端服务。
//
// 管理 ACP CLI 客户端的完整生命周期：
//   1. 配置读写（acp_clients.json）
//   2. 客户端进程启动/停止（spawn CLI → stdio JSON-RPC）
//   3. 会话创建（newSession / loadSession）
//   4. 对话轮次（send prompt → stream events → emit Tauri events）
//   5. 权限请求处理（前端 submit_acp_permission_response）
//
// 从 BitFun `src/crates/interfaces/acp/src/client/manager.rs` 精简而来，
// 去掉了 remote SSH / session persistence / tool registry / round tracker 等
// 重依赖，保留 CLI stdio 接入的核心路径。

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::Arc;
use std::time::Duration;

use agent_client_protocol::schema::{
    CancelNotification, ClientCapabilities, Implementation, InitializeRequest, NewSessionRequest,
    PermissionOption, PermissionOptionId, ProtocolVersion, RequestPermissionOutcome,
    RequestPermissionRequest, RequestPermissionResponse, SelectedPermissionOutcome, StopReason,
};
use agent_client_protocol::util::MatchDispatch;
use agent_client_protocol::{
    ActiveSession, Agent, ByteStreams, Client, ConnectionTo, Dispatch, SessionMessage,
};
use futures::io::{AsyncRead as FuturesAsyncRead, AsyncWrite as FuturesAsyncWrite};
use log::{info, warn};
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter, Manager};
use tokio::process::{Child, Command};
use tokio::sync::{oneshot, Mutex, RwLock};
use tokio_util::compat::{TokioAsyncReadCompatExt, TokioAsyncWriteCompatExt};

use super::config::{
    builtin_preset, default_config_for_builtin, load_config_file, parse_config_value,
    save_config_file, AcpClientConfig, AcpClientInfo, AcpClientRequirementProbe, AcpClientStatus,
    AcpRequirementProbeItem,
};

const CLIENT_STARTUP_TIMEOUT_SECS: u64 = 60;
const CLIENT_STARTUP_TIMEOUT: Duration = Duration::from_secs(CLIENT_STARTUP_TIMEOUT_SECS);
const PERMISSION_TIMEOUT: Duration = Duration::from_secs(600);
const SESSION_CLOSE_TIMEOUT: Duration = Duration::from_secs(5);

type AcpOutgoingStream = std::pin::Pin<Box<dyn FuturesAsyncWrite + Send>>;
type AcpIncomingStream = std::pin::Pin<Box<dyn FuturesAsyncRead + Send>>;

// --- 请求/响应类型（前端 ACPClientAPI.ts 契约） ---

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateAcpFlowSessionRequest {
    pub client_id: String,
    pub session_name: Option<String>,
    pub workspace_path: String,
    #[serde(default)]
    pub remote_connection_id: Option<String>,
    #[serde(default)]
    pub remote_ssh_host: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateAcpFlowSessionResponse {
    pub session_id: String,
    pub session_name: String,
    pub agent_type: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StartAcpDialogTurnRequest {
    pub session_id: String,
    pub client_id: String,
    pub user_input: String,
    #[serde(default)]
    pub original_user_input: Option<String>,
    pub turn_id: String,
    #[serde(default)]
    pub workspace_path: Option<String>,
    #[serde(default)]
    pub remote_connection_id: Option<String>,
    #[serde(default)]
    pub remote_ssh_host: Option<String>,
    #[serde(default)]
    pub timeout_seconds: Option<u64>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CancelAcpDialogTurnRequest {
    pub session_id: String,
    pub client_id: String,
    #[serde(default)]
    pub workspace_path: Option<String>,
    #[serde(default)]
    pub remote_connection_id: Option<String>,
    #[serde(default)]
    pub remote_ssh_host: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GetAcpSessionOptionsRequest {
    pub session_id: String,
    pub client_id: String,
    #[serde(default)]
    pub workspace_path: Option<String>,
    #[serde(default)]
    pub remote_connection_id: Option<String>,
    #[serde(default)]
    pub remote_ssh_host: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SetAcpSessionModelRequest {
    pub session_id: String,
    pub client_id: String,
    #[serde(default)]
    pub workspace_path: Option<String>,
    #[serde(default)]
    pub remote_connection_id: Option<String>,
    #[serde(default)]
    pub remote_ssh_host: Option<String>,
    pub model_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AcpSessionModelOption {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub description: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct AcpContextUsage {
    #[serde(default)]
    pub used: u64,
    #[serde(default)]
    pub size: u64,
    #[serde(default)]
    pub cost: Option<serde_json::Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct AcpSessionOptions {
    #[serde(default)]
    pub current_model_id: Option<String>,
    #[serde(default)]
    pub available_models: Vec<AcpSessionModelOption>,
    #[serde(default)]
    pub model_config_id: Option<String>,
    #[serde(default)]
    pub context_usage: Option<AcpContextUsage>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AcpAvailableCommand {
    pub name: String,
    pub description: String,
    #[serde(default)]
    pub input_hint: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AcpPlanEntry {
    pub content: String,
    pub priority: String,
    pub status: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SubmitAcpPermissionResponseRequest {
    pub permission_id: String,
    pub approve: bool,
    #[serde(default)]
    pub option_id: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AcpClientPermissionResponse {
    pub permission_id: String,
    pub resolved: bool,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AcpClientIdRequest {
    pub client_id: String,
    #[serde(default)]
    pub remote_connection_id: Option<String>,
}

struct PendingPermission {
    sender: oneshot::Sender<RequestPermissionResponse>,
    options: Vec<PermissionOption>,
}

/// ACP 客户端连接（一个 CLI 进程 + ACP 连接）。
struct AcpClientConnection {
    client_id: String,
    config: AcpClientConfig,
    status: Arc<RwLock<AcpClientStatus>>,
    connection: Arc<RwLock<Option<ConnectionTo<Agent>>>>,
    child: Arc<Mutex<Option<Child>>>,
    sessions: Arc<Mutex<HashMap<String, ActiveSession<'static, Agent>>>>,
    shutdown_tx: Arc<Mutex<Option<oneshot::Sender<()>>>>,
}

impl AcpClientConnection {
    fn new(client_id: String, config: AcpClientConfig) -> Self {
        Self {
            client_id,
            config,
            status: Arc::new(RwLock::new(AcpClientStatus::Configured)),
            connection: Arc::new(RwLock::new(None)),
            child: Arc::new(Mutex::new(None)),
            sessions: Arc::new(Mutex::new(HashMap::new())),
            shutdown_tx: Arc::new(Mutex::new(None)),
        }
    }

    async fn connection(&self) -> Result<ConnectionTo<Agent>, String> {
        self.connection
            .read()
            .await
            .clone()
            .ok_or_else(|| "ACP client connection is not initialized".to_string())
    }
}

/// ACP 会话状态。
#[derive(Clone)]
struct AcpSessionState {
    client_id: String,
    cwd: PathBuf,
    session_name: String,
    current_model_id: Option<String>,
    available_models: Vec<AcpSessionModelOption>,
    context_usage: Option<AcpContextUsage>,
    commands: Vec<AcpAvailableCommand>,
}

/// ACP 客户端服务 — 全局单例，通过 Tauri State 注入。
pub struct AcpClientService {
    app_data_dir: PathBuf,
    app_handle: AppHandle,
    /// connection_id → client connection
    clients: Arc<Mutex<HashMap<String, Arc<AcpClientConnection>>>>,
    /// session_id → session state
    sessions: Arc<Mutex<HashMap<String, AcpSessionState>>>,
    /// permission_id → pending permission
    pending_permissions: Arc<Mutex<HashMap<String, PendingPermission>>>,
}

impl AcpClientService {
    pub fn new(app_handle: AppHandle) -> Result<Self, String> {
        let app_data_dir = app_handle
            .path()
            .app_data_dir()
            .map_err(|e| format!("resolve app_data_dir failed: {e}"))?;
        Ok(Self {
            app_data_dir,
            app_handle,
            clients: Arc::new(Mutex::new(HashMap::new())),
            sessions: Arc::new(Mutex::new(HashMap::new())),
            pending_permissions: Arc::new(Mutex::new(HashMap::new())),
        })
    }

    // --- 配置读写 ---

    pub async fn load_json_config(&self) -> Result<String, String> {
        let config = load_config_file(&self.app_data_dir)?;
        let value = serde_json::to_value(&config)
            .map_err(|e| format!("Failed to render ACP config: {e}"))?;
        let canonical = parse_config_value(value)?;
        serde_json::to_string_pretty(&canonical)
            .map_err(|e| format!("Failed to render ACP config: {e}"))
    }

    pub async fn save_json_config(&self, json_config: &str) -> Result<(), String> {
        let value: serde_json::Value = serde_json::from_str(json_config)
            .map_err(|e| format!("Invalid ACP client JSON config: {e}"))?;
        let config = parse_config_value(value)?;
        save_config_file(&self.app_data_dir, &config)?;
        // 重新初始化客户端
        self.initialize_all().await?;
        Ok(())
    }

    // --- 客户端管理 ---

    pub async fn initialize_all(&self) -> Result<(), String> {
        let config = load_config_file(&self.app_data_dir)?;
        let configured_ids: std::collections::HashSet<String> =
            config.acp_clients.keys().cloned().collect();

        // 停止不再配置或已禁用的客户端
        let clients = self.clients.lock().await;
        let to_stop: Vec<String> = clients
            .iter()
            .filter(|(connection_id, conn)| {
                let client_id = &conn.client_id;
                let should_stop = !configured_ids.contains(client_id)
                    || config
                        .acp_clients
                        .get(client_id)
                        .map(|c| !c.enabled)
                        .unwrap_or(true);
                let _ = connection_id;
                should_stop
            })
            .map(|(id, _)| id.clone())
            .collect();
        drop(clients);

        for connection_id in to_stop {
            let _ = self.stop_connection(&connection_id).await;
        }

        Ok(())
    }

    pub async fn get_clients(&self) -> Result<Vec<AcpClientInfo>, String> {
        let config = load_config_file(&self.app_data_dir)?;
        let clients = self.clients.lock().await;
        let sessions = self.sessions.lock().await;

        let mut result = Vec::new();
        for (id, client_config) in &config.acp_clients {
            // 查找此 client_id 的活跃连接
            let active_connection = clients
                .values()
                .find(|conn| conn.client_id == *id);
            let (status, session_count) = if let Some(conn) = active_connection {
                let status = *conn.status.read().await;
                let count = conn.sessions.lock().await.len();
                (status, count)
            } else {
                (AcpClientStatus::Configured, 0)
            };

            // session_count 也包含已创建但连接可能已断开的会话
            let total_sessions = session_count
                + sessions
                    .values()
                    .filter(|s| s.client_id == *id)
                    .count();

            let tool_name = builtin_preset(id)
                .map(|p| p.tool_command.to_string())
                .unwrap_or_else(|| id.clone());

            result.push(AcpClientInfo {
                id: id.clone(),
                name: client_config
                    .name
                    .clone()
                    .unwrap_or_else(|| id.clone()),
                command: client_config.command.clone(),
                args: client_config.args.clone(),
                enabled: client_config.enabled,
                readonly: client_config.readonly,
                permission_mode: client_config.permission_mode,
                status,
                tool_name,
                session_count: total_sessions,
            });
        }
        Ok(result)
    }

    pub async fn stop_client(&self, client_id: &str) -> Result<(), String> {
        let connection_ids: Vec<String> = {
            let clients = self.clients.lock().await;
            clients
                .iter()
                .filter(|(_, conn)| conn.client_id == client_id)
                .map(|(id, _)| id.clone())
                .collect()
        };
        for connection_id in connection_ids {
            let _ = self.stop_connection(&connection_id).await;
        }
        Ok(())
    }

    async fn stop_connection(&self, connection_id: &str) -> Result<(), String> {
        let conn = {
            let mut clients = self.clients.lock().await;
            clients.remove(connection_id)
        };
        if let Some(conn) = conn {
            *conn.status.write().await = AcpClientStatus::Stopped;
            // 先取出并 drop shutdown_tx：spawned task 中的 shutdown_rx 会因此返回，
            // connect_with 闭包正常退出，避免直接 kill 子进程导致 JSON-RPC 流被截断。
            let _ = conn.shutdown_tx.lock().await.take();
            // 关闭所有会话 — ActiveSession 没有 close() 方法，直接 drop 即可
            conn.sessions.lock().await.clear();
            // 终止子进程（兜底：若 spawned task 仍卡在 init 等阶段，强制结束）
            let mut child_guard = conn.child.lock().await;
            if let Some(mut child) = child_guard.take() {
                let _ = child.kill().await;
            }
            info!("ACP client stopped: connection_id={}", connection_id);
        }
        Ok(())
    }

    // --- 环境探针 ---

    pub async fn probe_requirements(&self) -> Result<Vec<AcpClientRequirementProbe>, String> {
        let config = load_config_file(&self.app_data_dir)?;
        let mut probes = Vec::new();
        for (id, client_config) in &config.acp_clients {
            if !client_config.enabled {
                continue;
            }
            let preset = builtin_preset(id);
            let tool_item = probe_executable(&client_config.command).await;

            let adapter_item = if let Some(preset) = preset {
                if let (Some(package), Some(_bin)) = (preset.adapter_package, preset.adapter_bin) {
                    // npx adapter — 检查 npx 是否可用
                    let npx_item = probe_executable("npx").await;
                    if npx_item.installed {
                        Some(AcpRequirementProbeItem {
                            name: package.to_string(),
                            installed: true,
                            version: None,
                            path: Some("npx auto-install".to_string()),
                            error: None,
                        })
                    } else {
                        Some(AcpRequirementProbeItem {
                            name: package.to_string(),
                            installed: false,
                            version: None,
                            path: None,
                            error: Some("npx is not available on PATH".to_string()),
                        })
                    }
                } else {
                    None
                }
            } else {
                None
            };

            let runnable = tool_item.installed
                && adapter_item
                    .as_ref()
                    .map(|a| a.installed)
                    .unwrap_or(true);

            let mut notes = Vec::new();
            if !tool_item.installed {
                if let Some(preset) = preset {
                    if let Some(install_pkg) = preset.install_package {
                        notes.push(format!(
                            "Install with: npm install -g {}",
                            install_pkg
                        ));
                    }
                }
            }

            probes.push(AcpClientRequirementProbe {
                id: id.clone(),
                tool: tool_item,
                adapter: adapter_item,
                runnable,
                notes,
            });
        }
        Ok(probes)
    }

    pub async fn predownload_adapter(&self, client_id: &str) -> Result<(), String> {
        let preset = builtin_preset(client_id)
            .ok_or_else(|| format!("Unknown ACP client: {}", client_id))?;
        if let (Some(package), Some(bin)) = (preset.adapter_package, preset.adapter_bin) {
            // npm exec --yes --package=<package> -- <bin> --help
            let npm_path = find_executable("npm")
                .ok_or_else(|| "npm is not available on PATH".to_string())?;
            let mut cmd = Command::new(&npm_path);
            cmd.args([
                "exec",
                "--yes",
                &format!("--package={}", package),
                "--",
                bin,
                "--help",
            ]);
            crate::commands::legacy::apply_no_window_tokio(&mut cmd);
            cmd.stdout(Stdio::piped()).stderr(Stdio::piped());
            let output = cmd
                .output()
                .await
                .map_err(|e| format!("Failed to run npm exec: {e}"))?;
            if !output.status.success() {
                return Err(format!(
                    "Failed to predownload ACP adapter '{}': {}",
                    package,
                    String::from_utf8_lossy(&output.stderr)
                ));
            }
            info!("ACP adapter predownloaded: {}", package);
        }
        Ok(())
    }

    pub async fn install_client_cli(&self, client_id: &str) -> Result<(), String> {
        let preset = builtin_preset(client_id)
            .ok_or_else(|| format!("Unknown ACP client: {}", client_id))?;
        let package = preset
            .install_package
            .ok_or_else(|| format!("ACP client '{}' is user-managed (no installer)", client_id))?;
        let npm_path = find_executable("npm")
            .ok_or_else(|| "npm is not available on PATH".to_string())?;
        let mut cmd = Command::new(&npm_path);
        cmd.args(["install", "-g", package]);
        crate::commands::legacy::apply_no_window_tokio(&mut cmd);
        cmd.stdout(Stdio::piped()).stderr(Stdio::piped());
        let output = cmd
            .output()
            .await
            .map_err(|e| format!("Failed to run npm install: {e}"))?;
        if !output.status.success() {
            return Err(format!(
                "Failed to install ACP agent CLI '{}': {}",
                package,
                String::from_utf8_lossy(&output.stderr)
            ));
        }
        info!("ACP agent CLI installed: {}", package);
        Ok(())
    }

    // --- 会话管理 ---

    pub async fn create_flow_session(
        &self,
        request: CreateAcpFlowSessionRequest,
    ) -> Result<CreateAcpFlowSessionResponse, String> {
        let session_id = uuid::Uuid::new_v4().to_string();
        let session_name = request.session_name.unwrap_or_else(|| "Untitled".to_string());
        let agent_type = request.client_id.clone();

        let cwd = PathBuf::from(&request.workspace_path);

        self.sessions.lock().await.insert(
            session_id.clone(),
            AcpSessionState {
                client_id: request.client_id.clone(),
                cwd,
                session_name: session_name.clone(),
                current_model_id: None,
                available_models: Vec::new(),
                context_usage: None,
                commands: Vec::new(),
            },
        );

        Ok(CreateAcpFlowSessionResponse {
            session_id,
            session_name,
            agent_type,
        })
    }

    pub async fn get_session_options(
        &self,
        request: GetAcpSessionOptionsRequest,
    ) -> Result<AcpSessionOptions, String> {
        let sessions = self.sessions.lock().await;
        let session = sessions
            .get(&request.session_id)
            .ok_or_else(|| format!("ACP session not found: {}", request.session_id))?;
        Ok(AcpSessionOptions {
            current_model_id: session.current_model_id.clone(),
            available_models: session.available_models.clone(),
            model_config_id: None,
            context_usage: session.context_usage.clone(),
        })
    }

    pub async fn get_session_commands(
        &self,
        request: GetAcpSessionOptionsRequest,
    ) -> Result<Vec<AcpAvailableCommand>, String> {
        let sessions = self.sessions.lock().await;
        let session = sessions
            .get(&request.session_id)
            .ok_or_else(|| format!("ACP session not found: {}", request.session_id))?;
        Ok(session.commands.clone())
    }

    pub async fn set_session_model(
        &self,
        request: SetAcpSessionModelRequest,
    ) -> Result<AcpSessionOptions, String> {
        let mut sessions = self.sessions.lock().await;
        let session = sessions
            .get_mut(&request.session_id)
            .ok_or_else(|| format!("ACP session not found: {}", request.session_id))?;
        session.current_model_id = Some(request.model_id.clone());
        Ok(AcpSessionOptions {
            current_model_id: session.current_model_id.clone(),
            available_models: session.available_models.clone(),
            model_config_id: None,
            context_usage: session.context_usage.clone(),
        })
    }

    // --- 对话轮次 ---

    pub async fn start_dialog_turn(&self, request: StartAcpDialogTurnRequest) -> Result<(), String> {
        let app_handle = self.app_handle.clone();
        let session_id = request.session_id.clone();
        let turn_id = request.turn_id.clone();
        let client_id = request.client_id.clone();
        let timeout_seconds = request.timeout_seconds;
        let user_input = request.user_input;

        // 获取会话状态
        let session_state = {
            let sessions = self.sessions.lock().await;
            sessions
                .get(&session_id)
                .cloned()
                .ok_or_else(|| format!("ACP session not found: {}", session_id))?
        };

        let cwd = session_state.cwd.clone();

        // 启动或复用客户端连接
        let connection_id = format!("{}::session::{}", client_id, session_id);
        let conn = self
            .ensure_client_connection(&connection_id, &client_id, &cwd)
            .await?;

        // 创建或复用 ACP 会话
        {
            let mut sessions = conn.sessions.lock().await;
            if !sessions.contains_key(&session_id) {
                let cx = conn.connection().await?;
                let new_session_response = cx
                    .send_request(NewSessionRequest::new(&cwd))
                    .block_task()
                    .await
                    .map_err(|e| format!("ACP newSession failed: {e}"))?;

                // 更新模型信息
                {
                    let mut session_states = self.sessions.lock().await;
                    if let Some(state) = session_states.get_mut(&session_id) {
                        if let Some(models) = &new_session_response.models {
                            state.current_model_id =
                                Some(models.current_model_id.0.to_string());
                            state.available_models = models
                                .available_models
                                .iter()
                                .map(|model| AcpSessionModelOption {
                                    id: model.model_id.0.to_string(),
                                    name: model.name.clone(),
                                    description: model.description.clone(),
                                })
                                .collect();
                        }
                    }
                }

                let active = cx
                    .attach_session(new_session_response, Vec::new())
                    .map_err(|e| format!("ACP attach_session failed: {e}"))?;
                sessions.insert(session_id.clone(), active);
            }
        }

        // 发送 prompt 并流式读取更新
        let sessions_arc = conn.sessions.clone();
        let session_states_arc = self.sessions.clone();
        let app_handle_clone = app_handle.clone();
        let session_id_clone = session_id.clone();
        let turn_id_clone = turn_id.clone();
        let client_id_clone = client_id.clone();

        tokio::spawn(async move {
            // 从 map 中取出 active session，避免在 read_update().await 期间持锁
            // 导致 cancel_dialog_turn / stop_connection 等阻塞死锁。
            let mut active = {
                let mut sessions = sessions_arc.lock().await;
                match sessions.remove(&session_id_clone) {
                    Some(a) => a,
                    None => {
                        let _ = app_handle.emit(
                            "agentic://dialog-turn-failed",
                            serde_json::json!({
                                "sessionId": session_id,
                                "turnId": turn_id,
                                "error": "ACP session was not initialized",
                                "errorCategory": null,
                                "errorDetail": null,
                                "subagentParentInfo": null,
                            }),
                        );
                        return;
                    }
                }
            };

            let prompt_future = async {
                active
                    .send_prompt(&user_input)
                    .map_err(|e| format!("ACP send_prompt failed: {e}"))?;

                loop {
                    let message = active
                        .read_update()
                        .await
                        .map_err(|e| format!("ACP read_update failed: {e}"))?;

                    match message {
                        SessionMessage::SessionMessage(dispatch) => {
                            handle_dispatch(
                                dispatch,
                                &app_handle_clone,
                                &session_id_clone,
                                &turn_id_clone,
                                &client_id_clone,
                                &session_states_arc,
                            )
                            .await;
                        }
                        SessionMessage::StopReason(stop_reason) => {
                            let event_name = if matches!(stop_reason, StopReason::Cancelled) {
                                "agentic://dialog-turn-cancelled"
                            } else {
                                "agentic://dialog-turn-completed"
                            };
                            let _ = app_handle_clone.emit(
                                event_name,
                                serde_json::json!({
                                    "sessionId": session_id_clone,
                                    "turnId": turn_id_clone,
                                    "subagentParentInfo": null,
                                    "partialRecoveryReason": null,
                                }),
                            );
                            break;
                        }
                        _ => {}
                    }
                }
                Ok::<(), String>(())
            };

            let result = if let Some(secs) = timeout_seconds.filter(|s| *s > 0) {
                tokio::time::timeout(Duration::from_secs(secs), prompt_future)
                    .await
                    .map_err(|_| format!("ACP client timed out after {}s", secs))
                    .and_then(|r| r)
            } else {
                prompt_future.await
            };

            // 把 active session 放回 map（包括出错/超时的情况，便于后续清理或重试）
            {
                let mut sessions = sessions_arc.lock().await;
                sessions.insert(session_id_clone.clone(), active);
            }

            if let Err(error) = result {
                let _ = app_handle.emit(
                    "agentic://dialog-turn-failed",
                    serde_json::json!({
                        "sessionId": session_id,
                        "turnId": turn_id,
                        "error": error,
                        "errorCategory": null,
                        "errorDetail": null,
                        "subagentParentInfo": null,
                    }),
                );
            }
        });

        Ok(())
    }

    pub async fn cancel_dialog_turn(&self, request: CancelAcpDialogTurnRequest) -> Result<(), String> {
        let connection_id = format!("{}::session::{}", request.client_id, request.session_id);
        // 不锁定 conn.sessions：start_dialog_turn 在 prompt loop 期间会把 active session
        // 取出 map，此时 get_mut 找不到。直接用 connection 发送 CancelNotification 即可。
        let conn = {
            let clients = self.clients.lock().await;
            clients.get(&connection_id).cloned()
        };
        if let Some(conn) = conn {
            if let Ok(cx) = conn.connection().await {
                let _ = cx.send_notification_to(
                    Agent,
                    CancelNotification::new(request.session_id.clone()),
                );
            }
        }
        Ok(())
    }

    /// Blocking one-shot dialog turn used by the runtime-registry adapter.
    ///
    /// Mirrors the `send_prompt` + `read_update` loop in `start_dialog_turn`
    /// but awaits completion and returns the accumulated assistant text
    /// instead of streaming via events. Reuses a long-lived connection per
    /// `client_id` (so we don't spawn a fresh CLI process per invocation)
    /// and opens a fresh ACP session within it.
    pub async fn run_dialog_turn_sync(
        &self,
        client_id: String,
        cwd: PathBuf,
        user_input: String,
        timeout_seconds: Option<u64>,
    ) -> Result<String, String> {
        let session_key = format!("rr-sync-{}", uuid::Uuid::new_v4());
        let connection_id = format!("{}::session::rr-registry", client_id);
        let conn = self
            .ensure_client_connection(&connection_id, &client_id, &cwd)
            .await?;

        let mut active = {
            let mut sessions = conn.sessions.lock().await;
            if !sessions.contains_key(&session_key) {
                let cx = conn.connection().await?;
                let new_session_response = cx
                    .send_request(NewSessionRequest::new(&cwd))
                    .block_task()
                    .await
                    .map_err(|e| format!("ACP newSession failed: {e}"))?;
                let attached = cx
                    .attach_session(new_session_response, Vec::new())
                    .map_err(|e| format!("ACP attach_session failed: {e}"))?;
                sessions.insert(session_key.clone(), attached);
            }
            match sessions.remove(&session_key) {
                Some(a) => a,
                None => return Err("ACP session init failed".into()),
            }
        };

        let accumulated = Arc::new(Mutex::new(String::new()));
        let acc = accumulated.clone();
        let prompt_future = async {
            active
                .send_prompt(&user_input)
                .map_err(|e| format!("ACP send_prompt failed: {e}"))?;
            loop {
                let message = active
                    .read_update()
                    .await
                    .map_err(|e| format!("ACP read_update failed: {e}"))?;
                match message {
                    SessionMessage::SessionMessage(dispatch) => {
                        let _ = MatchDispatch::new(dispatch)
                            .if_notification(
                                |notification: agent_client_protocol::schema::SessionNotification| {
                                    let acc = acc.clone();
                                    async move {
                                        if let agent_client_protocol::schema::SessionUpdate::AgentMessageChunk(
                                            chunk,
                                        ) = notification.update
                                        {
                                            if let Some(text) = content_chunk_text(&chunk) {
                                                let mut buf = acc.lock().await;
                                                buf.push_str(&text);
                                            }
                                        }
                                        Ok(())
                                    }
                                },
                            )
                            .await
                            .otherwise_ignore();
                    }
                    SessionMessage::StopReason(stop_reason) => {
                        if matches!(stop_reason, StopReason::Cancelled) {
                            return Err("ACP dialog turn cancelled".into());
                        }
                        break;
                    }
                    _ => {}
                }
            }
            Ok::<(), String>(())
        };

        let result = if let Some(secs) = timeout_seconds.filter(|s| *s > 0) {
            tokio::time::timeout(Duration::from_secs(secs), prompt_future)
                .await
                .map_err(|_| format!("ACP client timed out after {}s", secs))
                .and_then(|r| r)
        } else {
            prompt_future.await
        };

        {
            let mut sessions = conn.sessions.lock().await;
            sessions.insert(session_key, active);
        }

        result?;
        let text = accumulated.lock().await.clone();
        if text.trim().is_empty() {
            Ok("[ACP 已完成本轮，但无文本输出（可能需在对应 ACP 客户端交互确认权限）]".to_string())
        } else {
            Ok(text)
        }
    }

    /// Discover the CLI's model id + available models the same way the exe
    /// does: spin up the ACP client, open a session (`session/new`), and read
    /// the `models` field from the response. No prompt is sent. The temporary
    /// connection is torn down afterwards so we don't leave a CLI process
    /// running purely for a probe.
    ///
    /// This mirrors exactly what `claude`/`codex`/`opencode` do at startup to
    /// learn which model they're on — so the runtime registry reports the
    /// model id the exe itself would report, not a hardcoded guess.
    pub async fn discover_client_models(
        &self,
        client_id: &str,
        cwd: &Path,
    ) -> Result<AcpSessionOptions, String> {
        let connection_id = format!("{}::discover::{}", client_id, uuid::Uuid::new_v4());
        let conn = self
            .ensure_client_connection(&connection_id, client_id, cwd)
            .await?;

        let result: Result<AcpSessionOptions, String> = async {
            let cx = conn.connection().await?;
            let new_session_response = cx
                .send_request(NewSessionRequest::new(cwd))
                .block_task()
                .await
                .map_err(|e| format!("ACP newSession failed: {e}"))?;
            let options = if let Some(models) = &new_session_response.models {
                AcpSessionOptions {
                    current_model_id: Some(models.current_model_id.0.to_string()),
                    available_models: models
                        .available_models
                        .iter()
                        .map(|m| AcpSessionModelOption {
                            id: m.model_id.0.to_string(),
                            name: m.name.clone(),
                            description: m.description.clone(),
                        })
                        .collect(),
                    model_config_id: None,
                    context_usage: None,
                }
            } else {
                AcpSessionOptions {
                    current_model_id: None,
                    available_models: Vec::new(),
                    model_config_id: None,
                    context_usage: None,
                }
            };
            Ok(options)
        }
        .await;

        // 探针连接不应保留 CLI 进程；及时清理。
        let _ = self.stop_connection(&connection_id).await;
        result
    }

    // --- 权限处理 ---

    pub async fn submit_permission_response(
        &self,
        request: SubmitAcpPermissionResponseRequest,
    ) -> Result<AcpClientPermissionResponse, String> {
        let mut pending = self.pending_permissions.lock().await;
        let permission = pending
            .remove(&request.permission_id)
            .ok_or_else(|| format!("ACP permission request not found: {}", request.permission_id))?;

        let option_id: PermissionOptionId = request
            .option_id
            .map(PermissionOptionId::new)
            .unwrap_or_else(|| select_permission_option_id(&permission.options, request.approve));
        let response = RequestPermissionResponse::new(RequestPermissionOutcome::Selected(
            SelectedPermissionOutcome::new(option_id),
        ));
        let _ = permission.sender.send(response);
        Ok(AcpClientPermissionResponse {
            permission_id: request.permission_id,
            resolved: true,
        })
    }

    // --- 内部方法 ---

    async fn ensure_client_connection(
        &self,
        connection_id: &str,
        client_id: &str,
        _cwd: &Path,
    ) -> Result<Arc<AcpClientConnection>, String> {
        // 检查已有连接
        {
            let clients = self.clients.lock().await;
            if let Some(conn) = clients.get(connection_id) {
                let status = *conn.status.read().await;
                if matches!(status, AcpClientStatus::Running) {
                    return Ok(conn.clone());
                }
            }
        }

        // 加载配置
        let config_file = load_config_file(&self.app_data_dir)?;
        let client_config = config_file
            .acp_clients
            .get(client_id)
            .cloned()
            .or_else(|| default_config_for_builtin(client_id))
            .ok_or_else(|| format!("ACP client '{}' not found in config", client_id))?;

        if !client_config.enabled {
            return Err(format!("ACP client '{}' is disabled", client_id));
        }

        let conn = Arc::new(AcpClientConnection::new(
            client_id.to_string(),
            client_config.clone(),
        ));
        *conn.status.write().await = AcpClientStatus::Starting;
        self.clients
            .lock()
            .await
            .insert(connection_id.to_string(), conn.clone());

        // 启动 CLI 进程
        let (transport, child) = match self
            .start_local_transport(client_id, &client_config)
            .await
        {
            Ok(result) => result,
            Err(e) => {
                let _ = self.stop_connection(connection_id).await;
                return Err(e);
            }
        };
        *conn.child.lock().await = Some(child);

        // 连接 ACP
        let pending_permissions = self.pending_permissions.clone();
        let app_handle = self.app_handle.clone();
        let (cx_tx, cx_rx) = oneshot::channel();
        let (shutdown_tx, shutdown_rx) = oneshot::channel::<()>();

        let conn_for_task = conn.clone();
        let connection_id_owned = connection_id.to_string();

        tokio::spawn(async move {
            let result = Client
                .builder()
                .name("tupai-acp-client")
                .on_receive_request(
                    {
                        let pending_permissions = pending_permissions.clone();
                        let app_handle = app_handle.clone();
                        async move |request: RequestPermissionRequest,
                                    responder,
                                    cx: ConnectionTo<Agent>| {
                            let permission_id = uuid::Uuid::new_v4().to_string();
                            let (tx, rx) = oneshot::channel();
                            {
                                let mut pending = pending_permissions.lock().await;
                                pending.insert(
                                    permission_id.clone(),
                                    PendingPermission {
                                        sender: tx,
                                        options: request.options.clone(),
                                    },
                                );
                            }
                            // 通知前端有权限请求
                            let session_id_str = request.session_id.0.as_ref().to_string();
                            let _ = app_handle.emit(
                                "agentic://acp-permission-request",
                                serde_json::json!({
                                    "permissionId": permission_id,
                                    "sessionId": session_id_str,
                                    "toolCall": request.tool_call,
                                    "options": request.options,
                                }),
                            );
                            let _ = cx.spawn(async move {
                                match tokio::time::timeout(PERMISSION_TIMEOUT, rx).await {
                                    Ok(Ok(response)) => {
                                        let _ = responder.respond_with_result(Ok(response));
                                    }
                                    Ok(Err(_)) => {
                                        let _ = responder.respond_with_result(Err(
                                            agent_client_protocol::util::internal_error(
                                                "permission responder dropped",
                                            ),
                                        ));
                                    }
                                    Err(_) => {
                                        let _ = responder.respond_with_result(Err(
                                            agent_client_protocol::util::internal_error(
                                                "permission request timed out",
                                            ),
                                        ));
                                    }
                                }
                                Ok(())
                            });
                            Ok(())
                        }
                    },
                    agent_client_protocol::on_receive_request!(),
                )
                .connect_with(transport, async move |cx| {
                    let init = InitializeRequest::new(ProtocolVersion::V1)
                        .client_capabilities(ClientCapabilities::new())
                        .client_info(Implementation::new(
                            "tupai-desktop",
                            env!("CARGO_PKG_VERSION"),
                        ));
                    let init_response = cx.send_request(init).block_task().await?;
                    let _ = cx_tx.send((cx, init_response.agent_capabilities));
                    let _ = shutdown_rx.await;
                    Ok(())
                })
                .await;

            if let Err(error) = result {
                warn!(
                    "ACP client connection ended with error: id={} error={:?}",
                    connection_id_owned, error
                );
                *conn_for_task.status.write().await = AcpClientStatus::Failed;
            } else {
                *conn_for_task.status.write().await = AcpClientStatus::Stopped;
            }
            *conn_for_task.connection.write().await = None;
            conn_for_task.sessions.lock().await.clear();
        });

        // 等待初始化完成
        let (cx, _agent_capabilities) = match tokio::time::timeout(CLIENT_STARTUP_TIMEOUT, cx_rx)
            .await
        {
            Ok(Ok(result)) => result,
            Ok(Err(_)) => {
                let _ = self.stop_connection(connection_id).await;
                return Err(format!(
                    "ACP client '{}' exited before initialization completed",
                    client_id
                ));
            }
            Err(_) => {
                let _ = self.stop_connection(connection_id).await;
                return Err(format!(
                    "ACP client '{}' startup timed out after {}s",
                    client_id, CLIENT_STARTUP_TIMEOUT_SECS
                ));
            }
        };

        *conn.connection.write().await = Some(cx);
        *conn.status.write().await = AcpClientStatus::Running;
        // 把 shutdown_tx 存到 conn 上，保持其生命周期与连接一致。
        // stop_connection 取出并 drop 它时，shutdown_rx 才会返回，结束 connect_with 闭包。
        // 注意：原先 `let _ = shutdown_tx;` 会立即 drop，导致连接刚初始化就被关闭。
        *conn.shutdown_tx.lock().await = Some(shutdown_tx);

        info!("ACP client started: id={} connection_id={}", client_id, connection_id);
        Ok(conn)
    }

    async fn start_local_transport(
        &self,
        client_id: &str,
        config: &AcpClientConfig,
    ) -> Result<(ByteStreams<AcpOutgoingStream, AcpIncomingStream>, Child), String> {
        let program = find_executable(&config.command)
            .unwrap_or_else(|| PathBuf::from(&config.command));

        let mut command = Command::new(&program);
        crate::commands::legacy::apply_no_window_tokio(&mut command);
        command
            .args(&config.args)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit());

        // 应用环境变量
        for (key, value) in &config.env {
            command.env(key, value);
        }

        let mut child = command
            .spawn()
            .map_err(|e| format!("Failed to spawn ACP client '{}': {}", client_id, e))?;

        let stdout = child
            .stdout
            .take()
            .ok_or_else(|| format!("ACP client '{}' stdout is unavailable", client_id))?;
        let stdin = child
            .stdin
            .take()
            .ok_or_else(|| format!("ACP client '{}' stdin is unavailable", client_id))?;

        Ok((
            ByteStreams::new(
                Box::pin(stdin.compat_write()),
                Box::pin(stdout.compat()),
            ),
            child,
        ))
    }
}

// --- 辅助函数 ---

fn select_permission_option_id(
    options: &[PermissionOption],
    approve: bool,
) -> PermissionOptionId {
    let target_kind = if approve {
        agent_client_protocol::schema::PermissionOptionKind::AllowOnce
    } else {
        agent_client_protocol::schema::PermissionOptionKind::RejectOnce
    };
    options
        .iter()
        .find(|opt| opt.kind == target_kind)
        .map(|opt| opt.option_id.clone())
        .or_else(|| options.first().map(|opt| opt.option_id.clone()))
        .unwrap_or_else(|| PermissionOptionId::new(""))
}

async fn handle_dispatch(
    dispatch: Dispatch,
    app_handle: &AppHandle,
    session_id: &str,
    turn_id: &str,
    client_id: &str,
    session_states: &Arc<Mutex<HashMap<String, AcpSessionState>>>,
) {
    let _ = MatchDispatch::new(dispatch)
        .if_notification(|notification: agent_client_protocol::schema::SessionNotification| {
            let app_handle = app_handle.clone();
            let session_id = session_id.to_string();
            let turn_id = turn_id.to_string();
            let client_id = client_id.to_string();
            let session_states = session_states.clone();
            async move {
                use agent_client_protocol::schema::SessionUpdate;
                match notification.update {
                    SessionUpdate::AgentMessageChunk(chunk) => {
                        if let Some(text) = content_chunk_text(&chunk) {
                            let _ = app_handle.emit(
                                "agentic://text-chunk",
                                serde_json::json!({
                                    "sessionId": session_id,
                                    "turnId": turn_id,
                                    "text": text,
                                    "subagentParentInfo": null,
                                }),
                            );
                        }
                    }
                    SessionUpdate::AgentThoughtChunk(chunk) => {
                        if let Some(text) = content_chunk_text(&chunk) {
                            let _ = app_handle.emit(
                                "agentic://text-chunk",
                                serde_json::json!({
                                    "sessionId": session_id,
                                    "turnId": turn_id,
                                    "text": text,
                                    "contentType": "thinking",
                                    "isThinkingEnd": false,
                                    "subagentParentInfo": null,
                                }),
                            );
                        }
                    }
                    SessionUpdate::ToolCall(tool_call) => {
                        let _ = app_handle.emit(
                            "agentic://tool-event",
                            serde_json::json!({
                                "sessionId": session_id,
                                "turnId": turn_id,
                                "toolEvent": serde_json::to_value(&tool_call).unwrap_or_default(),
                                "subagentParentInfo": null,
                            }),
                        );
                    }
                    SessionUpdate::ToolCallUpdate(tool_call_update) => {
                        let _ = app_handle.emit(
                            "agentic://tool-event",
                            serde_json::json!({
                                "sessionId": session_id,
                                "turnId": turn_id,
                                "toolEvent": serde_json::to_value(&tool_call_update).unwrap_or_default(),
                                "subagentParentInfo": null,
                            }),
                        );
                    }
                    SessionUpdate::UsageUpdate(usage) => {
                        // 同步更新 session 状态中的 context_usage
                        {
                            let mut states = session_states.lock().await;
                            if let Some(state) = states.get_mut(&session_id) {
                                state.context_usage = Some(AcpContextUsage {
                                    used: usage.used,
                                    size: usage.size,
                                    cost: None,
                                });
                            }
                        }
                        let _ = app_handle.emit(
                            "agentic://acp-context-usage-updated",
                            serde_json::json!({
                                "sessionId": session_id,
                                "turnId": turn_id,
                                "clientId": client_id,
                                "used": usage.used,
                                "size": usage.size,
                                "cost": null,
                                "subagentParentInfo": null,
                            }),
                        );
                    }
                    SessionUpdate::AvailableCommandsUpdate(update) => {
                        let commands: Vec<AcpAvailableCommand> = update
                            .available_commands
                            .iter()
                            .map(|cmd| AcpAvailableCommand {
                                name: cmd.name.clone(),
                                description: cmd.description.clone(),
                                input_hint: None,
                            })
                            .collect();
                        // 缓存到 session 状态，供 get_session_commands 返回
                        {
                            let mut states = session_states.lock().await;
                            if let Some(state) = states.get_mut(&session_id) {
                                state.commands = commands.clone();
                            }
                        }
                        let _ = app_handle.emit(
                            "agentic://acp-available-commands-updated",
                            serde_json::json!({
                                "sessionId": session_id,
                                "clientId": client_id,
                                "commands": commands,
                            }),
                        );
                    }
                    SessionUpdate::Plan(plan) => {
                        let entries: Vec<AcpPlanEntry> = plan
                            .entries
                            .iter()
                            .map(|e| AcpPlanEntry {
                                content: e.content.clone(),
                                priority: format!("{:?}", e.priority).to_lowercase(),
                                status: format!("{:?}", e.status).to_lowercase(),
                            })
                            .collect();
                        let _ = app_handle.emit(
                            "agentic://acp-plan-updated",
                            serde_json::json!({
                                "sessionId": session_id,
                                "turnId": turn_id,
                                "clientId": client_id,
                                "entries": entries,
                            }),
                        );
                    }
                    SessionUpdate::ConfigOptionUpdate(_) => {
                        let _ = app_handle.emit(
                            "agentic://acp-session-options-changed",
                            serde_json::json!({
                                "sessionId": session_id,
                                "clientId": client_id,
                            }),
                        );
                    }
                    _ => {}
                }
                Ok(())
            }
        })
        .await
        .otherwise_ignore();
}

fn content_chunk_text(
    chunk: &agent_client_protocol::schema::ContentChunk,
) -> Option<String> {
    use agent_client_protocol::schema::ContentBlock;
    match &chunk.content {
        ContentBlock::Text(text) => Some(text.text.clone()),
        _ => None,
    }
}

fn find_executable(command: &str) -> Option<PathBuf> {
    let command_path = PathBuf::from(command);
    if command_path.components().count() > 1 {
        return executable_file(&command_path).then_some(command_path);
    }

    for directory in command_search_paths() {
        for candidate in executable_candidates(&directory, command) {
            if executable_file(&candidate) {
                return Some(candidate);
            }
        }
    }
    None
}

fn command_search_paths() -> Vec<PathBuf> {
    let mut paths = Vec::new();
    if let Some(env_path) = std::env::var_os("PATH") {
        for directory in std::env::split_paths(&env_path) {
            if directory.as_os_str().is_empty() {
                continue;
            }
            if directory.is_dir() {
                paths.push(directory);
            }
        }
    }
    paths
}

fn executable_candidates(directory: &Path, command: &str) -> Vec<PathBuf> {
    #[cfg(target_os = "windows")]
    {
        let command_path = PathBuf::from(command);
        if command_path.extension().is_some() {
            return vec![directory.join(command)];
        }
        let extensions = std::env::var_os("PATHEXT")
            .unwrap_or_else(|| std::ffi::OsString::from(".EXE;.BAT;.CMD"));
        extensions
            .to_string_lossy()
            .split(';')
            .filter(|ext| !ext.is_empty())
            .map(|ext| directory.join(format!("{}{}", command, ext)))
            .collect()
    }
    #[cfg(not(target_os = "windows"))]
    {
        vec![directory.join(command)]
    }
}

fn executable_file(path: &Path) -> bool {
    path.is_file()
}

async fn probe_executable(command: &str) -> AcpRequirementProbeItem {
    let mut item = AcpRequirementProbeItem {
        name: command.to_string(),
        installed: false,
        version: None,
        path: None,
        error: None,
    };

    if let Some(found_path) = find_executable(command) {
        item.installed = true;
        item.path = Some(found_path.to_string_lossy().to_string());

        // 尝试获取版本
        let mut probe_cmd = Command::new(&found_path);
        crate::commands::legacy::apply_no_window_tokio(&mut probe_cmd);
        if let Ok(output) = probe_cmd.arg("--version").output().await {
            if output.status.success() {
                let version_text = String::from_utf8_lossy(&output.stdout);
                let version = version_text
                    .lines()
                    .map(str::trim)
                    .find(|line| !line.is_empty())
                    .map(ToString::to_string);
                item.version = version;
            }
        }
    } else {
        item.error = Some(format!("'{}' not found on PATH", command));
    }

    item
}
