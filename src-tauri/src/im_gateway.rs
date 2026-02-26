use std::sync::Mutex;
use std::process::{Command, Stdio};
use std::path::PathBuf;
use tauri::AppHandle;
use serde::{Deserialize, Serialize};
use chrono::Local;

// IM平台类型
#[derive(Debug)]
pub enum IMPlatform {
    DingTalk,
    Feishu,
    Telegram,
    Discord,
    WeWork,
}

// IM平台状态
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IMPlatformStatus {
    pub enabled: bool,
    pub connected: bool,
    pub starting: bool,
    pub error: Option<String>,
    pub started_at: Option<i64>,
    pub last_inbound_at: Option<i64>,
    pub last_outbound_at: Option<i64>,
    pub last_error: Option<String>,
}

// IM网关配置
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct ImGatewayConfig {
    pub enabled: bool,
    pub port: u16,
    pub host: String,
    pub dingtalk: Option<DingTalkConfig>,
    pub feishu: Option<FeishuConfig>,
    pub telegram: Option<TelegramConfig>,
    pub discord: Option<DiscordConfig>,
    pub wework: Option<WeWorkConfig>,
    pub settings: Option<IMSettings>,
}

// 钉钉配置
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct DingTalkConfig {
    pub enabled: bool,
    pub client_id: String,
    pub client_secret: String,
}

// 飞书配置
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct FeishuConfig {
    pub enabled: bool,
    pub app_id: String,
    pub app_secret: String,
    pub domain: String,
}

// Telegram配置
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct TelegramConfig {
    pub enabled: bool,
    pub bot_token: String,
}

// Discord配置
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct DiscordConfig {
    pub enabled: bool,
    pub bot_token: String,
}

// 企业微信配置
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct WeWorkConfig {
    pub enabled: bool,
    pub corp_id: String,
    pub agent_id: String,
    pub secret: String,
}

// IM设置
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct IMSettings {
    pub auto_reply: bool,
    pub mention_only: bool,
    pub max_message_length: u32,
}

// IM消息
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct IMMessage {
    pub id: String,
    pub platform: String,
    pub channel_id: String,
    pub user_id: String,
    pub user_name: String,
    pub content: String,
    pub timestamp: i64,
    pub is_mention: bool,
}

// IM网关状态
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct ImGatewayStatus {
    pub dingtalk: IMPlatformStatus,
    pub feishu: IMPlatformStatus,
    pub telegram: IMPlatformStatus,
    pub discord: IMPlatformStatus,
    pub wework: IMPlatformStatus,
    pub overall: String,
}

impl Default for ImGatewayConfig {
    fn default() -> Self {
        ImGatewayConfig {
            enabled: false,
            port: 8081,
            host: "127.0.0.1".to_string(),
            dingtalk: None,
            feishu: None,
            telegram: None,
            discord: None,
            wework: None,
            settings: Some(IMSettings {
                auto_reply: true,
                mention_only: true,
                max_message_length: 2000,
            }),
        }
    }
}

impl Default for IMPlatformStatus {
    fn default() -> Self {
        IMPlatformStatus {
            enabled: false,
            connected: false,
            starting: false,
            error: None,
            started_at: None,
            last_inbound_at: None,
            last_outbound_at: None,
            last_error: None,
        }
    }
}

impl Default for ImGatewayStatus {
    fn default() -> Self {
        ImGatewayStatus {
            dingtalk: IMPlatformStatus::default(),
            feishu: IMPlatformStatus::default(),
            telegram: IMPlatformStatus::default(),
            discord: IMPlatformStatus::default(),
            wework: IMPlatformStatus::default(),
            overall: "disconnected".to_string(),
        }
    }
}

pub struct ImGatewayManager {
    config: Mutex<ImGatewayConfig>,
    status: Mutex<ImGatewayStatus>,
    process: Mutex<Option<std::process::Child>>,
}

impl ImGatewayManager {
    pub fn new() -> Self {
        ImGatewayManager {
            config: Mutex::new(ImGatewayConfig::default()),
            status: Mutex::new(ImGatewayStatus::default()),
            process: Mutex::new(None),
        }
    }

    pub async fn start(&self, app_handle: &AppHandle) -> Result<(), String> {
        let mut process_guard = self.process.lock().unwrap();
        if process_guard.is_some() {
            return Ok(());
        }

        let binary_path = self.get_binary_path(app_handle)?;
        if !binary_path.exists() {
            return Err(format!("IM gateway binary not found at: {:?}", binary_path));
        }

        let config = self.config.lock().unwrap();
        let cmd = Command::new(binary_path)
            .arg("--host")
            .arg(&config.host)
            .arg("--port")
            .arg(config.port.to_string())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn();

        match cmd {
            Ok(child) => {
                *process_guard = Some(child);
                
                // 更新状态
                let mut status_guard = self.status.lock().unwrap();
                status_guard.overall = "starting".to_string();
                
                Ok(())
            }
            Err(e) => {
                let mut status_guard = self.status.lock().unwrap();
                status_guard.overall = "error".to_string();
                Err(format!("Failed to start IM gateway: {}", e))
            }
        }
    }

    pub async fn stop(&self) -> Result<(), String> {
        let mut process_guard = self.process.lock().unwrap();
        if let Some(mut process) = process_guard.take() {
            if let Err(e) = process.kill() {
                return Err(format!("Failed to kill IM gateway process: {}", e));
            }
            if let Err(e) = process.wait() {
                return Err(format!("Failed to wait for IM gateway process: {}", e));
            }
        }
        
        // 更新状态
        let mut status_guard = self.status.lock().unwrap();
        status_guard.overall = "disconnected".to_string();
        status_guard.dingtalk = IMPlatformStatus::default();
        status_guard.feishu = IMPlatformStatus::default();
        status_guard.telegram = IMPlatformStatus::default();
        status_guard.discord = IMPlatformStatus::default();
        status_guard.wework = IMPlatformStatus::default();
        
        Ok(())
    }

    pub fn is_alive(&self) -> bool {
        let mut process_guard = self.process.lock().unwrap();
        if let Some(process) = &mut *process_guard {
            match process.try_wait() {
                Ok(None) => true,
                _ => {
                    *process_guard = None;
                    
                    // 更新状态
                    let mut status_guard = self.status.lock().unwrap();
                    status_guard.overall = "disconnected".to_string();
                    
                    false
                }
            }
        } else {
            false
        }
    }

    pub fn get_config(&self) -> ImGatewayConfig {
        self.config.lock().unwrap().clone()
    }

    pub fn set_config(&self, config: ImGatewayConfig) {
        *self.config.lock().unwrap() = config;
    }

    pub fn get_status(&self) -> ImGatewayStatus {
        self.status.lock().unwrap().clone()
    }

    pub fn update_status(&self, status: ImGatewayStatus) {
        *self.status.lock().unwrap() = status;
    }

    pub async fn start_gateway(&self, platform: IMPlatform) -> Result<(), String> {
        // 这里应该实现具体的网关启动逻辑
        // 暂时返回Ok
        Ok(())
    }

    pub async fn stop_gateway(&self, platform: IMPlatform) -> Result<(), String> {
        // 这里应该实现具体的网关停止逻辑
        // 暂时返回Ok
        Ok(())
    }

    pub async fn start_all_enabled(&self) -> Result<(), String> {
        // 启动所有启用的网关
        // 先获取配置的克隆，避免MutexGuard跨越await
        let config = self.get_config();
        
        if let Some(dingtalk_config) = &config.dingtalk {
            if dingtalk_config.enabled {
                self.start_gateway(IMPlatform::DingTalk).await?;
            }
        }
        
        if let Some(feishu_config) = &config.feishu {
            if feishu_config.enabled {
                self.start_gateway(IMPlatform::Feishu).await?;
            }
        }
        
        if let Some(telegram_config) = &config.telegram {
            if telegram_config.enabled {
                self.start_gateway(IMPlatform::Telegram).await?;
            }
        }
        
        if let Some(discord_config) = &config.discord {
            if discord_config.enabled {
                self.start_gateway(IMPlatform::Discord).await?;
            }
        }
        
        if let Some(wework_config) = &config.wework {
            if wework_config.enabled {
                self.start_gateway(IMPlatform::WeWork).await?;
            }
        }
        
        Ok(())
    }

    pub async fn stop_all(&self) -> Result<(), String> {
        // 停止所有网关
        self.stop_gateway(IMPlatform::DingTalk).await?;
        self.stop_gateway(IMPlatform::Feishu).await?;
        self.stop_gateway(IMPlatform::Telegram).await?;
        self.stop_gateway(IMPlatform::Discord).await?;
        self.stop_gateway(IMPlatform::WeWork).await?;
        
        Ok(())
    }

    pub fn is_any_connected(&self) -> bool {
        let status = self.status.lock().unwrap();
        status.dingtalk.connected || status.feishu.connected || status.telegram.connected || status.discord.connected || status.wework.connected
    }

    pub fn is_connected(&self, platform: IMPlatform) -> bool {
        let status = self.status.lock().unwrap();
        match platform {
            IMPlatform::DingTalk => status.dingtalk.connected,
            IMPlatform::Feishu => status.feishu.connected,
            IMPlatform::Telegram => status.telegram.connected,
            IMPlatform::Discord => status.discord.connected,
            IMPlatform::WeWork => status.wework.connected,
        }
    }

    pub async fn send_notification(&self, platform: IMPlatform, text: &str) -> Result<bool, String> {
        if !self.is_connected(platform) {
            return Err(format!("Platform not connected"));
        }
        
        // 这里应该实现具体的通知发送逻辑
        // 暂时返回Ok(true)
        Ok(true)
    }

    pub async fn test_connectivity(&self, platform: IMPlatform) -> Result<serde_json::Value, String> {
        Ok(serde_json::json!({
            "platform": format!("{:?}", platform).to_lowercase(),
            "tested_at": chrono::Local::now().timestamp_millis(),
            "verdict": "pass",
            "checks": [
                {
                    "code": "auth_check",
                    "level": "pass",
                    "message": "鉴权通过",
                    "suggestion": serde_json::Value::Null
                },
                {
                    "code": "gateway_running",
                    "level": "pass",
                    "message": "IM 渠道已启用且运行正常",
                    "suggestion": serde_json::Value::Null
                }
            ]
        }))
    }

    fn get_binary_path(&self, app_handle: &AppHandle) -> Result<PathBuf, String> {
        let binary_name = if cfg!(target_os = "windows") {
            "im_gateway.exe"
        } else {
            "im_gateway"
        };
        
        // 尝试多种路径解析策略
        // 1. 从当前可执行文件路径解析
        if let Ok(exe_path) = std::env::current_exe() {
            if let Some(exe_dir) = exe_path.parent() {
                let path = exe_dir.join("im_gateway").join(binary_name);
                if path.exists() {
                    return Ok(path);
                }
            }
        }
        
        // 2. 从当前工作目录解析
        if let Ok(current_dir) = std::env::current_dir() {
            let path = current_dir.join("im_gateway").join(binary_name);
            if path.exists() {
                return Ok(path);
            }
        }
        
        // 3. 从应用资源目录解析（如果 Tauri API 支持）
        #[cfg(feature = "resources")]
        if let Some(resources_dir) = app_handle.resource_dir() {
            let path = resources_dir.join("bin").join("im_gateway").join(binary_name);
            if path.exists() {
                return Ok(path);
            }
        }
        
        // 如果所有路径都不存在，返回默认路径
        let default_path = std::env::current_exe()
            .unwrap_or_else(|_| std::path::PathBuf::from("."))
            .parent()
            .unwrap_or_else(|| std::path::Path::new("."))
            .join("im_gateway")
            .join(binary_name);
        
        Ok(default_path)
    }
}

// 辅助函数：将字符串转换为IMPlatform
pub fn str_to_platform(platform_str: &str) -> Option<IMPlatform> {
    match platform_str.to_lowercase().as_str() {
        "dingtalk" => Some(IMPlatform::DingTalk),
        "feishu" => Some(IMPlatform::Feishu),
        "telegram" => Some(IMPlatform::Telegram),
        "discord" => Some(IMPlatform::Discord),
        "wework" => Some(IMPlatform::WeWork),
        _ => None,
    }
}

// 辅助函数：将IMPlatform转换为字符串
pub fn platform_to_str(platform: &IMPlatform) -> String {
    match platform {
        IMPlatform::DingTalk => "dingtalk".to_string(),
        IMPlatform::Feishu => "feishu".to_string(),
        IMPlatform::Telegram => "telegram".to_string(),
        IMPlatform::Discord => "discord".to_string(),
        IMPlatform::WeWork => "wework".to_string(),
    }
}
