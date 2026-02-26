use super::gateway::{Gateway, GatewayStatus, GatewayEvent, EventCallback, IMMessage};
use serde::{Deserialize, Serialize};
use std::sync::{Arc, Mutex};
use chrono::Local;
use async_trait::async_trait;
use reqwest::Client;
use std::time::Duration;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WeWorkConfig {
    pub enabled: bool,
    pub webhook_url: String,
    pub debug: Option<bool>,
}

impl Default for WeWorkConfig {
    fn default() -> Self {
        WeWorkConfig {
            enabled: false,
            webhook_url: String::new(),
            debug: Some(false),
        }
    }
}

#[derive(Debug, Deserialize)]
struct WeWorkWebhookResponse {
    errcode: i32,
    errmsg: Option<String>,
}

pub struct WeWorkGateway {
    config: Arc<Mutex<WeWorkConfig>>,
    status: Arc<Mutex<GatewayStatus>>,
    event_callback: Arc<Mutex<Option<EventCallback>>>,
    http_client: Client,
}

impl WeWorkGateway {
    pub fn new(config: WeWorkConfig) -> Self {
        let http_client = Client::builder()
            .timeout(Duration::from_secs(30))
            .build()
            .expect("Failed to create HTTP client");

        Self {
            config: Arc::new(Mutex::new(config)),
            status: Arc::new(Mutex::new(GatewayStatus::default())),
            event_callback: Arc::new(Mutex::new(None)),
            http_client,
        }
    }

    pub fn set_config(&self, config: WeWorkConfig) {
        *self.config.lock().unwrap() = config;
    }

    pub fn get_config(&self) -> WeWorkConfig {
        self.config.lock().unwrap().clone()
    }

    fn emit_event(&self, event: GatewayEvent) {
        if let Some(callback) = &*self.event_callback.lock().unwrap() {
            callback(event);
        }
    }

    fn log(&self, message: &str) {
        if self.config.lock().unwrap().debug.unwrap_or(false) {
            println!("[WeWork Gateway] {}", message);
        }
    }

    // 使用群机器人 Webhook 发送消息
    async fn send_webhook_message(&self, content: &str, msg_type: &str) -> Result<(), String> {
        let webhook_url = {
            let config = self.config.lock().unwrap();
            config.webhook_url.clone()
        };

        if webhook_url.is_empty() {
            return Err("Webhook URL 不能为空".to_string());
        }

        let request = match msg_type {
            "text" => serde_json::json!({
                "msgtype": "text",
                "text": {
                    "content": content
                }
            }),
            "markdown" => serde_json::json!({
                "msgtype": "markdown",
                "markdown": {
                    "content": content
                }
            }),
            _ => serde_json::json!({
                "msgtype": "text",
                "text": {
                    "content": content
                }
            }),
        };

        let response = self.http_client
            .post(&webhook_url)
            .json(&request)
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {}", e))?
            .json::<WeWorkWebhookResponse>()
            .await
            .map_err(|e| format!("Failed to parse response: {}", e))?;

        if response.errcode == 0 {
            Ok(())
        } else {
            Err(format!("Failed to send message: {}", response.errmsg.unwrap_or_default()))
        }
    }

    // 发送文本消息
    pub async fn send_text_message(&self, content: &str) -> Result<(), String> {
        self.send_webhook_message(content, "text").await
    }

    // 发送 Markdown 消息
    pub async fn send_markdown_message(&self, content: &str) -> Result<(), String> {
        self.send_webhook_message(content, "markdown").await
    }
}

impl Clone for WeWorkGateway {
    fn clone(&self) -> Self {
        Self {
            config: Arc::new(Mutex::new(self.get_config())),
            status: Arc::new(Mutex::new(GatewayStatus::default())),
            event_callback: Arc::new(Mutex::new(None)),
            http_client: Client::builder()
                .timeout(Duration::from_secs(30))
                .build()
                .expect("Failed to create HTTP client"),
        }
    }
}

#[async_trait]
impl Gateway for WeWorkGateway {
    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
    
    async fn start(&self) -> Result<(), String> {
        let (config_enabled, webhook_url) = {
            let config = self.config.lock().unwrap();
            (config.enabled, config.webhook_url.clone())
        };
        
        if !config_enabled {
            return Ok(());
        }

        if webhook_url.is_empty() {
            let mut status = self.status.lock().unwrap();
            status.error = Some("缺少必要的配置: Webhook URL".to_string());
            status.last_error = status.error.clone();
            let error_msg = status.error.clone().unwrap();
            self.emit_event(GatewayEvent::Error(error_msg.clone()));
            self.emit_event(GatewayEvent::StatusChanged(status.clone()));
            return Err(error_msg);
        }
        
        {
            let mut status = self.status.lock().unwrap();
            status.starting = true;
            status.error = None;
            status.last_error = None;
            self.emit_event(GatewayEvent::StatusChanged(status.clone()));
        }

        // 测试 Webhook URL 是否有效
        match self.send_text_message("企业微信网关已启动").await {
            Ok(_) => {
                self.log("Webhook 测试成功");
            }
            Err(e) => {
                let mut status = self.status.lock().unwrap();
                status.starting = false;
                status.error = Some(format!("Webhook 测试失败: {}", e));
                status.last_error = status.error.clone();
                self.emit_event(GatewayEvent::Error(status.error.clone().unwrap()));
                self.emit_event(GatewayEvent::StatusChanged(status.clone()));
                return Err(e);
            }
        }

        let mut status = self.status.lock().unwrap();
        status.starting = false;
        status.connected = true;
        status.started_at = Some(Local::now().timestamp_millis());
        self.emit_event(GatewayEvent::StatusChanged(status.clone()));
        self.emit_event(GatewayEvent::Connected);

        self.log("企业微信网关已启动（Webhook 模式）");

        Ok(())
    }

    async fn stop(&self) -> Result<(), String> {
        let mut status = self.status.lock().unwrap();

        if !status.connected && !status.starting {
            return Ok(());
        }

        status.connected = false;
        status.starting = false;
        status.error = None;
        self.emit_event(GatewayEvent::StatusChanged(status.clone()));
        self.emit_event(GatewayEvent::Disconnected);

        self.log("企业微信网关已停止");

        Ok(())
    }

    fn is_connected(&self) -> bool {
        self.status.lock().unwrap().connected
    }

    fn get_status(&self) -> GatewayStatus {
        self.status.lock().unwrap().clone()
    }

    async fn send_notification(&self, text: &str) -> Result<bool, String> {
        if !self.is_connected() {
            return Err("网关未连接".to_string());
        }

        self.send_text_message(text).await?;
        
        let mut status = self.status.lock().unwrap();
        status.last_outbound_at = Some(Local::now().timestamp_millis());
        
        Ok(true)
    }

    async fn send_message(&self, _conversation_id: &str, text: &str) -> Result<bool, String> {
        if !self.is_connected() {
            return Err("网关未连接".to_string());
        }

        self.send_text_message(text).await?;
        
        let mut status = self.status.lock().unwrap();
        status.last_outbound_at = Some(Local::now().timestamp_millis());
        
        Ok(true)
    }

    async fn send_media_message(&self, _conversation_id: &str, _file_path: &str) -> Result<bool, String> {
        Err("企业微信 Webhook 不支持发送媒体消息".to_string())
    }

    async fn reconnect_if_needed(&self) -> Result<(), String> {
        if !self.is_connected() {
            self.start().await
        } else {
            Ok(())
        }
    }

    async fn edit_message(&self, _conversation_id: &str, _message_id: &str, _new_text: &str) -> Result<bool, String> {
        Err("企业微信 Webhook 不支持编辑消息".to_string())
    }

    async fn delete_message(&self, _conversation_id: &str, _message_id: &str) -> Result<bool, String> {
        Err("企业微信 Webhook 不支持删除消息".to_string())
    }

    async fn get_message_history(&self, _conversation_id: &str, _limit: u32) -> Result<Vec<IMMessage>, String> {
        Err("企业微信 Webhook 不支持获取历史消息".to_string())
    }

    fn set_event_callback(&self, callback: Option<EventCallback>) {
        *self.event_callback.lock().unwrap() = callback;
    }
}
