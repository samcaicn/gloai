// ACP 客户端配置结构 + JSON 文件读写。
//
// 配置文件路径: app_data_dir/acp_clients.json
// 结构与 BitFun 上游 `AcpClientConfigFile` 一致：
//   { "acpClients": { "<id>": { name, command, args, env, enabled, readonly, permissionMode } } }

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

/// ACP 客户端配置文件根结构。
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct AcpClientConfigFile {
    #[serde(default)]
    pub acp_clients: HashMap<String, AcpClientConfig>,
}

/// 单个 ACP 客户端配置（对应一个 CLI 工具）。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AcpClientConfig {
    #[serde(default)]
    pub name: Option<String>,
    pub command: String,
    #[serde(default)]
    pub args: Vec<String>,
    #[serde(default)]
    pub env: HashMap<String, String>,
    #[serde(default = "default_true")]
    pub enabled: bool,
    #[serde(default)]
    pub readonly: bool,
    #[serde(default)]
    pub permission_mode: AcpClientPermissionMode,
}

/// 权限模式：ask（每次询问）/ allow_once（允许一次）/ reject_once（拒绝一次）。
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
#[derive(Default)]
pub enum AcpClientPermissionMode {
    #[default]
    Ask,
    AllowOnce,
    RejectOnce,
}


/// 客户端运行时状态。
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum AcpClientStatus {
    Configured,
    Starting,
    Running,
    Stopped,
    Failed,
}

/// 前端展示用的客户端信息（合并配置 + 运行时状态）。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AcpClientInfo {
    pub id: String,
    pub name: String,
    pub command: String,
    pub args: Vec<String>,
    pub enabled: bool,
    pub readonly: bool,
    pub permission_mode: AcpClientPermissionMode,
    pub status: AcpClientStatus,
    pub tool_name: String,
    pub session_count: usize,
}

/// 环境探针单项（检测某 CLI 工具是否已安装）。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AcpRequirementProbeItem {
    pub name: String,
    pub installed: bool,
    #[serde(default)]
    pub version: Option<String>,
    #[serde(default)]
    pub path: Option<String>,
    #[serde(default)]
    pub error: Option<String>,
}

/// 客户端环境探针结果。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AcpClientRequirementProbe {
    pub id: String,
    pub tool: AcpRequirementProbeItem,
    #[serde(default)]
    pub adapter: Option<AcpRequirementProbeItem>,
    pub runnable: bool,
    #[serde(default)]
    pub notes: Vec<String>,
}

fn default_true() -> bool {
    true
}

// --- 内置 ACP 客户端预设 ---
// 与 BitFun `builtin_clients.rs` 保持一致，方便用户一键启用。

/// 内置 ACP 客户端预设。
pub struct BuiltinAcpClientPreset {
    pub id: &'static str,
    pub command: &'static str,
    pub args: &'static [&'static str],
    pub tool_command: &'static str,
    /// npm 包名（用于 install_acp_client_cli）。None = 用户自行安装。
    pub install_package: Option<&'static str>,
    /// npm adapter 包名（用于 predownload_acp_client_adapter）。None = 原生 ACP。
    pub adapter_package: Option<&'static str>,
    pub adapter_bin: Option<&'static str>,
}

/// 内置预设列表。
pub const BUILTIN_ACP_CLIENT_PRESETS: &[BuiltinAcpClientPreset] = &[
    BuiltinAcpClientPreset {
        id: "opencode",
        command: "opencode",
        args: &["acp"],
        tool_command: "opencode",
        install_package: Some("opencode-ai"),
        adapter_package: None,
        adapter_bin: None,
    },
    BuiltinAcpClientPreset {
        id: "omp",
        command: "omp",
        args: &["acp"],
        tool_command: "omp",
        install_package: None,
        adapter_package: None,
        adapter_bin: None,
    },
    BuiltinAcpClientPreset {
        id: "claude-code",
        command: "npx",
        args: &["--yes", "@zed-industries/claude-code-acp@latest"],
        tool_command: "claude",
        install_package: Some("@anthropic-ai/claude-code"),
        adapter_package: Some("@zed-industries/claude-code-acp"),
        adapter_bin: Some("claude-code-acp"),
    },
    BuiltinAcpClientPreset {
        id: "codex",
        command: "npx",
        args: &["--yes", "@zed-industries/codex-acp@latest"],
        tool_command: "codex",
        install_package: Some("@openai/codex"),
        adapter_package: Some("@zed-industries/codex-acp"),
        adapter_bin: Some("codex-acp"),
    },
];

/// 查找内置预设。
pub fn builtin_preset(client_id: &str) -> Option<&'static BuiltinAcpClientPreset> {
    BUILTIN_ACP_CLIENT_PRESETS.iter().find(|p| p.id == client_id)
}

/// 获取内置客户端的默认配置。
pub fn default_config_for_builtin(client_id: &str) -> Option<AcpClientConfig> {
    let preset = builtin_preset(client_id)?;
    Some(AcpClientConfig {
        name: None,
        command: preset.command.to_string(),
        args: preset.args.iter().map(|s| s.to_string()).collect(),
        env: HashMap::new(),
        enabled: true,
        readonly: false,
        permission_mode: AcpClientPermissionMode::Ask,
    })
}

// --- 配置文件读写 ---

/// 配置文件名。
pub const CONFIG_FILENAME: &str = "acp_clients.json";

/// 解析配置 JSON Value → AcpClientConfigFile。
/// 兼容两种格式：
///   1. { "acpClients": { ... } } — 标准格式
///   2. { "<id>": { ... } } — 仅客户端 map（自动包裹 acpClients）
pub fn parse_config_value(value: serde_json::Value) -> Result<AcpClientConfigFile, String> {
    if value.get("acpClients").is_some() {
        serde_json::from_value(value)
            .map_err(|e| format!("Invalid ACP client config: {e}"))
    } else if value.is_object() {
        serde_json::from_value(serde_json::json!({ "acpClients": value }))
            .map_err(|e| format!("Invalid ACP client config map: {e}"))
    } else {
        Err("ACP client config must be an object".to_string())
    }
}

/// 配置文件路径。
pub fn config_file_path(app_data_dir: &Path) -> PathBuf {
    app_data_dir.join(CONFIG_FILENAME)
}

/// 从文件加载配置。文件不存在时返回空配置（并自动写入默认内置客户端）。
pub fn load_config_file(app_data_dir: &Path) -> Result<AcpClientConfigFile, String> {
    let path = config_file_path(app_data_dir);
    if !path.exists() {
        let default_config = default_config_file();
        // 写入默认配置，方便用户首次使用
        let json = serde_json::to_string_pretty(&default_config)
            .map_err(|e| format!("Failed to render default ACP config: {e}"))?;
        std::fs::write(&path, json.as_bytes())
            .map_err(|e| format!("Failed to write default ACP config to {}: {e}", path.display()))?;
        return Ok(default_config);
    }
    let content = std::fs::read_to_string(&path)
        .map_err(|e| format!("Failed to read ACP config {}: {e}", path.display()))?;
    let value: serde_json::Value = serde_json::from_str(&content)
        .map_err(|e| format!("Failed to parse ACP config JSON: {e}"))?;
    parse_config_value(value)
}

/// 保存配置到文件。
pub fn save_config_file(app_data_dir: &Path, config: &AcpClientConfigFile) -> Result<(), String> {
    let path = config_file_path(app_data_dir);
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)
            .map_err(|e| format!("Failed to create config dir {}: {e}", parent.display()))?;
    }
    let json = serde_json::to_string_pretty(config)
        .map_err(|e| format!("Failed to render ACP config: {e}"))?;
    std::fs::write(&path, json.as_bytes())
        .map_err(|e| format!("Failed to write ACP config to {}: {e}", path.display()))?;
    Ok(())
}

/// 默认配置文件（空配置，不预置任何客户端——用户按需启用）。
fn default_config_file() -> AcpClientConfigFile {
    AcpClientConfigFile {
        acp_clients: HashMap::new(),
    }
}
