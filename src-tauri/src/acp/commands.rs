// ACP Tauri 命令层 —— 把 AcpClientService 的方法暴露给前端。
//
// 命令签名与前端 `ACPClientAPI.ts` 的调用契约一一对应：
//   - 参数名用 snake_case, Tauri 自动映射到前端的 camelCase
//   - 返回值统一为 Result<T, String>, 错误以字符串形式回到前端
//   - State<'_, Arc<AcpClientService>> 通过 `.manage()` 注入

use std::sync::Arc;

use serde::Deserialize;
use tauri::State;

use super::config::{AcpClientInfo, AcpClientRequirementProbe};
use super::service::{
    AcpClientPermissionResponse, AcpSessionOptions, AcpAvailableCommand, AcpClientIdRequest,
    CancelAcpDialogTurnRequest, CreateAcpFlowSessionRequest, CreateAcpFlowSessionResponse,
    GetAcpSessionOptionsRequest, SetAcpSessionModelRequest, StartAcpDialogTurnRequest,
    SubmitAcpPermissionResponseRequest, AcpClientService,
};

/// probe_acp_client_requirements 的请求参数。
/// 前端可能发送空对象 {}（本地探针）或 { remoteConnectionId, forceRefresh }。
/// 简化实现只支持本地探针，remote 字段保留兼容性但暂不使用。
#[derive(Debug, Clone, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProbeAcpClientRequirementsRequest {
    #[serde(default)]
    pub remote_connection_id: Option<String>,
    #[serde(default)]
    pub force_refresh: bool,
}

/// 加载 ACP 客户端 JSON 配置（返回原始 JSON 字符串）。
#[tauri::command]
pub async fn load_acp_json_config(
    state: State<'_, Arc<AcpClientService>>,
) -> Result<String, String> {
    state.load_json_config().await
}

/// 保存 ACP 客户端 JSON 配置（并触发客户端重新初始化）。
#[tauri::command]
pub async fn save_acp_json_config(
    state: State<'_, Arc<AcpClientService>>,
    json_config: String,
) -> Result<(), String> {
    state.save_json_config(&json_config).await
}

/// 根据配置文件初始化所有已启用的 ACP 客户端（停止已禁用/已移除的）。
#[tauri::command]
pub async fn initialize_acp_clients(
    state: State<'_, Arc<AcpClientService>>,
) -> Result<(), String> {
    state.initialize_all().await
}

/// 获取所有 ACP 客户端列表（合并配置 + 运行时状态）。
#[tauri::command]
pub async fn get_acp_clients(
    state: State<'_, Arc<AcpClientService>>,
) -> Result<Vec<AcpClientInfo>, String> {
    state.get_clients().await
}

/// 停止指定的 ACP 客户端连接。
#[tauri::command]
pub async fn stop_acp_client(
    state: State<'_, Arc<AcpClientService>>,
    request: AcpClientIdRequest,
) -> Result<(), String> {
    state.stop_client(&request.client_id).await
}

/// 探测 ACP 客户端环境要求（CLI 工具是否已安装等）。
/// 简化实现只支持本地探针；remote_connection_id 保留兼容性。
#[tauri::command]
pub async fn probe_acp_client_requirements(
    state: State<'_, Arc<AcpClientService>>,
    request: ProbeAcpClientRequirementsRequest,
) -> Result<Vec<AcpClientRequirementProbe>, String> {
    let _ = request; // 简化实现忽略 remote/force_refresh
    state.probe_requirements().await
}

/// 预下载 ACP 客户端 adapter（npm 包，用于 npx 启动）。
#[tauri::command]
pub async fn predownload_acp_client_adapter(
    state: State<'_, Arc<AcpClientService>>,
    request: AcpClientIdRequest,
) -> Result<(), String> {
    state.predownload_adapter(&request.client_id).await
}

/// 安装 ACP 客户端 CLI（npm install -g）。
#[tauri::command]
pub async fn install_acp_client_cli(
    state: State<'_, Arc<AcpClientService>>,
    request: AcpClientIdRequest,
) -> Result<(), String> {
    state.install_client_cli(&request.client_id).await
}

/// 创建 ACP 流程会话（前端 FlowSession 概念，对应一个工作目录 + 客户端）。
#[tauri::command]
pub async fn create_acp_flow_session(
    state: State<'_, Arc<AcpClientService>>,
    request: CreateAcpFlowSessionRequest,
) -> Result<CreateAcpFlowSessionResponse, String> {
    state.create_flow_session(request).await
}

/// 启动 ACP 对话轮次（异步：发送 prompt → 流式 emit Tauri 事件）。
#[tauri::command]
pub async fn start_acp_dialog_turn(
    state: State<'_, Arc<AcpClientService>>,
    request: StartAcpDialogTurnRequest,
) -> Result<(), String> {
    state.start_dialog_turn(request).await
}

/// 取消 ACP 对话轮次。
#[tauri::command]
pub async fn cancel_acp_dialog_turn(
    state: State<'_, Arc<AcpClientService>>,
    request: CancelAcpDialogTurnRequest,
) -> Result<(), String> {
    state.cancel_dialog_turn(request).await
}

/// 获取 ACP 会话选项（当前模型 / 可用模型 / 上下文用量）。
#[tauri::command]
pub async fn get_acp_session_options(
    state: State<'_, Arc<AcpClientService>>,
    request: GetAcpSessionOptionsRequest,
) -> Result<AcpSessionOptions, String> {
    state.get_session_options(request).await
}

/// 获取 ACP 会话可用命令（如 /clear, /compact 等）。
#[tauri::command]
pub async fn get_acp_session_commands(
    state: State<'_, Arc<AcpClientService>>,
    request: GetAcpSessionOptionsRequest,
) -> Result<Vec<AcpAvailableCommand>, String> {
    state.get_session_commands(request).await
}

/// 设置 ACP 会话当前使用的模型。
#[tauri::command]
pub async fn set_acp_session_model(
    state: State<'_, Arc<AcpClientService>>,
    request: SetAcpSessionModelRequest,
) -> Result<AcpSessionOptions, String> {
    state.set_session_model(request).await
}

/// 提交 ACP 权限请求响应（前端用户点击 允许/拒绝）。
#[tauri::command]
pub async fn submit_acp_permission_response(
    state: State<'_, Arc<AcpClientService>>,
    request: SubmitAcpPermissionResponseRequest,
) -> Result<AcpClientPermissionResponse, String> {
    state.submit_permission_response(request).await
}
