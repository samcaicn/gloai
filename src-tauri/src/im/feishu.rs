use super::gateway::{EventCallback, Gateway, GatewayEvent, GatewayStatus, IMMessage};
use chrono::Local;
use futures_util::{SinkExt, StreamExt};
use reqwest::Client;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::mpsc;
use tokio::sync::Mutex;
use tokio_tungstenite::{connect_async, tungstenite::Message as WsMessage};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeishuConfig {
    pub enabled: bool,
    pub app_id: String,
    pub app_secret: String,
    pub domain: Option<String>,
    pub encrypt_key: Option<String>,
    pub verification_token: Option<String>,
    pub render_mode: Option<String>,
    pub media_download_path: Option<String>,
    pub debug: Option<bool>,
}

impl Default for FeishuConfig {
    fn default() -> Self {
        FeishuConfig {
            enabled: false,
            app_id: String::new(),
            app_secret: String::new(),
            domain: Some("feishu".to_string()),
            encrypt_key: None,
            verification_token: None,
            render_mode: Some("text".to_string()),
            media_download_path: None,
            debug: Some(false),
        }
    }
}

#[derive(Debug, Deserialize)]
struct FeishuAccessTokenResponse {
    #[serde(rename = "msg")]
    message: Option<String>,
    code: i32,
    data: Option<FeishuAccessTokenData>,
}

#[derive(Debug, Deserialize)]
struct FeishuAccessTokenData {
    token: Option<String>,
    expire: Option<i64>,
}

#[derive(Debug, Deserialize)]
struct FeishuSendMessageResponse {
    #[serde(rename = "msg")]
    message: Option<String>,
    code: i32,
}

#[derive(Debug, Deserialize)]
struct FeishuUploadResponse {
    #[serde(rename = "msg")]
    message: Option<String>,
    code: i32,
    data: Option<FeishuUploadData>,
}

#[derive(Debug, Deserialize)]
struct FeishuUploadData {
    #[serde(rename = "file_key")]
    file_key: Option<String>,
}

// WebSocket 消息格式
#[derive(Debug, Clone, Serialize, Deserialize)]
struct FeishuWebSocketMessage {
    #[serde(rename = "type")]
    msg_type: String,
    #[serde(rename = "app_id")]
    app_id: Option<String>,
    #[serde(rename = "tenant_key")]
    tenant_key: Option<String>,
    #[serde(rename = "create_time")]
    create_time: Option<String>,
    event: Option<FeishuEvent>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct FeishuEvent {
    #[serde(rename = "type")]
    event_type: String,
    #[serde(rename = "app_id")]
    app_id: Option<String>,
    #[serde(rename = "tenant_key")]
    tenant_key: Option<String>,
    message: Option<FeishuMessageEvent>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct FeishuMessageEvent {
    message_id: String,
    root_id: Option<String>,
    parent_id: Option<String>,
    #[serde(rename = "chat_id")]
    chat_id: String,
    #[serde(rename = "chat_type")]
    chat_type: String,
    #[serde(rename = "message_type")]
    message_type: String,
    content: String,
    mentions: Option<Vec<FeishuMention>>,
    sender: FeishuSender,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct FeishuMention {
    key: String,
    id: FeishuUserId,
    name: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct FeishuUserId {
    #[serde(rename = "open_id")]
    open_id: Option<String>,
    #[serde(rename = "user_id")]
    user_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct FeishuSender {
    #[serde(rename = "sender_id")]
    sender_id: FeishuUserId,
    #[serde(rename = "sender_type")]
    sender_type: String,
    #[serde(rename = "tenant_key")]
    tenant_key: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeishuMediaAttachment {
    pub media_type: String,
    pub file_key: Option<String>,
    pub image_key: Option<String>,
}

pub struct FeishuGateway {
    config: Arc<Mutex<FeishuConfig>>,
    status: Arc<Mutex<GatewayStatus>>,
    event_callback: Arc<Mutex<Option<EventCallback>>>,
    http_client: Client,
    access_token: Arc<Mutex<Option<String>>>,
    token_expires_at: Arc<Mutex<Option<i64>>>,
    last_chat_id: Arc<Mutex<Option<String>>>,
    last_user_id: Arc<Mutex<Option<String>>>,
    ws_task: Arc<Mutex<Option<tokio::task::JoinHandle<()>>>>,
    is_stopping: Arc<Mutex<bool>>,
    reconnect_delay_ms: Arc<Mutex<u64>>,
}

impl FeishuGateway {
    pub fn new(config: FeishuConfig) -> Self {
        let http_client = Client::builder()
            .timeout(Duration::from_secs(60))
            .build()
            .expect("Failed to create HTTP client");

        Self {
            config: Arc::new(Mutex::new(config)),
            status: Arc::new(Mutex::new(GatewayStatus::default())),
            event_callback: Arc::new(Mutex::new(None)),
            http_client,
            access_token: Arc::new(Mutex::new(None)),
            token_expires_at: Arc::new(Mutex::new(None)),
            last_chat_id: Arc::new(Mutex::new(None)),
            last_user_id: Arc::new(Mutex::new(None)),
            ws_task: Arc::new(Mutex::new(None)),
            is_stopping: Arc::new(Mutex::new(false)),
            reconnect_delay_ms: Arc::new(Mutex::new(3000)),
        }
    }

    pub fn set_config(&self, config: FeishuConfig) {
        *self.config.blocking_lock() = config;
    }

    pub fn get_config(&self) -> FeishuConfig {
        self.config.blocking_lock().clone()
    }

    fn get_base_url(&self) -> String {
        let domain = self
            .config
            .blocking_lock()
            .domain
            .clone()
            .unwrap_or_else(|| "feishu".to_string());
        match domain.as_str() {
            "lark" => "https://open.larkoffice.com".to_string(),
            _ => "https://open.feishu.cn".to_string(),
        }
    }

    fn emit_event(&self, event: GatewayEvent) {
        if let Some(callback) = &*self.event_callback.blocking_lock() {
            callback(event);
        }
    }

    fn log(&self, message: &str) {
        if self.config.blocking_lock().debug.unwrap_or(false) {
            println!("[Feishu Gateway] {}", message);
        }
    }

    pub async fn get_access_token(&self) -> Result<String, String> {
        let (app_id, app_secret) = {
            let config = self.config.lock().await;
            (config.app_id.clone(), config.app_secret.clone())
        };

        let now = chrono::Utc::now().timestamp();
        if let Some(expires_at) = *self.token_expires_at.lock().await {
            if expires_at - now > 300 {
                if let Some(token) = &*self.access_token.lock().await {
                    return Ok(token.clone());
                }
            }
        }

        let base_url = self.get_base_url();
        let url = format!(
            "{}/open-apis/auth/v3/tenant_access_token/internal",
            base_url
        );

        let request = serde_json::json!({
            "app_id": app_id,
            "app_secret": app_secret,
        });

        let response = self
            .http_client
            .post(&url)
            .json(&request)
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {}", e))?
            .json::<FeishuAccessTokenResponse>()
            .await
            .map_err(|e| format!("Failed to parse response: {}", e))?;

        if response.code == 0 && response.data.is_some() {
            let data = response.data.unwrap();
            if let Some(token) = data.token {
                let expires_in = data.expire.unwrap_or(7200);
                let expires_at = now + expires_in;

                *self.access_token.lock().await = Some(token.clone());
                *self.token_expires_at.lock().await = Some(expires_at);

                Ok(token)
            } else {
                Err("No token in response".to_string())
            }
        } else {
            Err(format!(
                "Failed to get access token: {}",
                response.message.unwrap_or_default()
            ))
        }
    }

    async fn upload_image(&self, file_path: &str) -> Result<String, String> {
        let access_token = self.get_access_token().await?;
        let base_url = self.get_base_url();

        let file_data = tokio::fs::read(file_path)
            .await
            .map_err(|e| format!("Failed to read file: {}", e))?;

        let file_name = std::path::Path::new(file_path)
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("image.jpg");

        let part = reqwest::multipart::Part::bytes(file_data)
            .file_name(file_name.to_string())
            .mime_str("image/jpeg")
            .map_err(|e| format!("Invalid mime type: {}", e))?;

        let form = reqwest::multipart::Form::new().part("image", part);

        let url = format!(
            "{}/open-apis/im/v1/images?access_token={}&image_type=message",
            base_url, access_token
        );

        let response = self
            .http_client
            .post(&url)
            .multipart(form)
            .send()
            .await
            .map_err(|e| format!("Upload failed: {}", e))?
            .json::<FeishuUploadResponse>()
            .await
            .map_err(|e| format!("Failed to parse response: {}", e))?;

        if response.code == 0 && response.data.is_some() {
            if let Some(file_key) = response.data.unwrap().file_key {
                Ok(file_key)
            } else {
                Err("No file key in response".to_string())
            }
        } else {
            Err(format!(
                "Upload failed: {}",
                response.message.unwrap_or_default()
            ))
        }
    }

    async fn upload_file(&self, file_path: &str) -> Result<String, String> {
        let access_token = self.get_access_token().await?;
        let base_url = self.get_base_url();

        let file_data = tokio::fs::read(file_path)
            .await
            .map_err(|e| format!("Failed to read file: {}", e))?;

        let file_name = std::path::Path::new(file_path)
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("file");

        let part = reqwest::multipart::Part::bytes(file_data).file_name(file_name.to_string());

        let form = reqwest::multipart::Form::new().part("file", part);

        let url = format!(
            "{}/open-apis/im/v1/files?access_token={}&file_type=message",
            base_url, access_token
        );

        let response = self
            .http_client
            .post(&url)
            .multipart(form)
            .send()
            .await
            .map_err(|e| format!("Upload failed: {}", e))?
            .json::<FeishuUploadResponse>()
            .await
            .map_err(|e| format!("Failed to parse response: {}", e))?;

        if response.code == 0 && response.data.is_some() {
            if let Some(file_key) = response.data.unwrap().file_key {
                Ok(file_key)
            } else {
                Err("No file key in response".to_string())
            }
        } else {
            Err(format!(
                "Upload failed: {}",
                response.message.unwrap_or_default()
            ))
        }
    }

    // 启动 WebSocket 长连接
    async fn start_websocket_connection(&self) -> Result<(), String> {
        let access_token = self.get_access_token().await?;
        let base_url = self.get_base_url();

        // 飞书 WebSocket 连接地址
        let ws_url = format!(
            "{}/open-apis/event/callback/ws?token={}",
            base_url, access_token
        );

        self.log(&format!("Connecting to WebSocket: {}", ws_url));

        let (ws_stream, _) = connect_async(&ws_url)
            .await
            .map_err(|e| format!("WebSocket connection failed: {}", e))?;

        self.log("WebSocket connected");

        let (ws_sender, mut ws_receiver) = ws_stream.split();
        let ws_sender = Arc::new(Mutex::new(ws_sender));

        // 重置停止标志
        *self.is_stopping.lock().await = false;
        *self.reconnect_delay_ms.lock().await = 3000;

        let event_callback = Arc::clone(&self.event_callback);
        let status = Arc::clone(&self.status);
        let is_stopping = Arc::clone(&self.is_stopping);
        let self_ref = self.clone();
        let ws_sender_clone = Arc::clone(&ws_sender);

        // 启动消息处理任务
        let handle = tokio::spawn(async move {
            let mut heartbeat_interval = tokio::time::interval(Duration::from_secs(30));
            let mut should_reconnect = false;

            loop {
                tokio::select! {
                    // 处理 WebSocket 消息
                    Some(msg) = ws_receiver.next() => {
                        match msg {
                            Ok(WsMessage::Text(text)) => {
                                self_ref.log(&format!("Received: {}", &text[..text.len().min(200)]));

                                // 解析 WebSocket 消息
                                if let Ok(ws_msg) = serde_json::from_str::<FeishuWebSocketMessage>(&text) {
                                    if ws_msg.msg_type == "event_callback" {
                                        if let Some(event) = ws_msg.event {
                                            if event.event_type == "im.message.receive_v1" {
                                                if let Some(message) = event.message {
                                                    let _ = self_ref.handle_message_event(&message).await;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            Ok(WsMessage::Close(_)) => {
                                self_ref.log("WebSocket closed by server");
                                should_reconnect = true;
                                break;
                            }
                            Ok(WsMessage::Ping(data)) => {
                                let _ = ws_sender_clone.lock().await.send(WsMessage::Pong(data)).await;
                            }
                            Err(e) => {
                                self_ref.log(&format!("WebSocket error: {}", e));
                                should_reconnect = true;
                                break;
                            }
                            _ => {}
                        }
                    }

                    // 发送心跳
                    _ = heartbeat_interval.tick() => {
                        let heartbeat = serde_json::json!({
                            "type": "ping"
                        });
                        if ws_sender_clone.lock().await.send(WsMessage::Text(heartbeat.to_string())).await.is_err() {
                            should_reconnect = true;
                            break;
                        }
                    }

                    // 检查是否需要停止
                    _ = tokio::time::sleep(Duration::from_millis(100)) => {
                        if *is_stopping.lock().await {
                            break;
                        }
                    }
                }
            }

            // 连接断开，更新状态
            let mut s = status.lock().await;
            s.connected = false;
            drop(s);

            if let Some(cb) = &*event_callback.lock().await {
                cb(GatewayEvent::Disconnected);
            }

            self_ref.log("WebSocket disconnected");

            // 尝试重连
            if should_reconnect && !*is_stopping.lock().await {
                // 执行重连
                let _ = self_ref.reconnect();
            }
        });

        *self.ws_task.lock().await = Some(handle);

        Ok(())
    }

    async fn handle_message_event(&self, msg: &FeishuMessageEvent) -> Result<(), String> {
        // 保存会话信息
        *self.last_chat_id.lock().await = Some(msg.chat_id.clone());
        *self.last_user_id.lock().await = msg.sender.sender_id.open_id.clone();

        // 解析消息内容
        let content =
            if let Ok(content_json) = serde_json::from_str::<serde_json::Value>(&msg.content) {
                content_json
                    .get("text")
                    .and_then(|t| t.as_str())
                    .map(|s| s.to_string())
                    .unwrap_or_else(|| msg.content.clone())
            } else {
                msg.content.clone()
            };

        let im_message = IMMessage {
            id: msg.message_id.clone(),
            platform: "feishu".to_string(),
            channel_id: msg.chat_id.clone(),
            user_id: msg.sender.sender_id.open_id.clone().unwrap_or_default(),
            user_name: String::new(), // 飞书事件不直接提供用户名，需要额外查询
            content,
            timestamp: chrono::Utc::now().timestamp(),
            is_mention: msg
                .mentions
                .as_ref()
                .map(|m| !m.is_empty())
                .unwrap_or(false),
        };

        let mut status = self.status.lock().await;
        status.last_inbound_at = Some(Local::now().timestamp_millis());
        drop(status);

        self.emit_event(GatewayEvent::Message(im_message));

        Ok(())
    }

    // 自动重连 - 使用阻塞方式在单独线程中执行
    fn reconnect(&self) -> Result<(), String> {
        if *self.is_stopping.blocking_lock() {
            return Ok(());
        }

        let delay = *self.reconnect_delay_ms.blocking_lock();
        self.log(&format!("Reconnecting in {}ms...", delay));

        // 指数退避
        let new_delay = std::cmp::min(delay * 2, 60000);
        *self.reconnect_delay_ms.blocking_lock() = new_delay;

        // 使用 tokio::spawn 在运行时中执行重连
        let self_clone = self.clone();
        tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(delay)).await;

            if *self_clone.is_stopping.lock().await {
                return;
            }

            // 重新启动连接
            let _ = self_clone.start().await;
        });

        Ok(())
    }

    async fn send_message_api(
        &self,
        receive_id_type: &str,
        receive_id: &str,
        content: &str,
    ) -> Result<(), String> {
        let base_url = self.get_base_url();
        let url = format!("{}/open-apis/im/v1/messages", base_url);

        let access_token = self.get_access_token().await?;

        let request = serde_json::json!({
            "receive_id_type": receive_id_type,
            "receive_id": receive_id,
            "msg_type": "text",
            "content": serde_json::json!({ "text": content }),
        });

        let mut headers = reqwest::header::HeaderMap::new();
        headers.insert(
            "Authorization",
            format!("Bearer {}", access_token).parse().unwrap(),
        );
        headers.insert(
            "Content-Type",
            "application/json; charset=utf-8".parse().unwrap(),
        );

        self.http_client
            .post(&url)
            .headers(headers)
            .json(&request)
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {}", e))?
            .json::<FeishuSendMessageResponse>()
            .await
            .map_err(|e| format!("Failed to parse response: {}", e))?;

        Ok(())
    }

    async fn send_image_message_api(
        &self,
        receive_id_type: &str,
        receive_id: &str,
        image_key: &str,
    ) -> Result<(), String> {
        let base_url = self.get_base_url();
        let url = format!("{}/open-apis/im/v1/messages", base_url);

        let access_token = self.get_access_token().await?;

        let request = serde_json::json!({
            "receive_id_type": receive_id_type,
            "receive_id": receive_id,
            "msg_type": "image",
            "content": serde_json::json!({ "image_key": image_key }),
        });

        let mut headers = reqwest::header::HeaderMap::new();
        headers.insert(
            "Authorization",
            format!("Bearer {}", access_token).parse().unwrap(),
        );
        headers.insert(
            "Content-Type",
            "application/json; charset=utf-8".parse().unwrap(),
        );

        self.http_client
            .post(&url)
            .headers(headers)
            .json(&request)
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {}", e))?;

        Ok(())
    }

    async fn send_file_message_api(
        &self,
        receive_id_type: &str,
        receive_id: &str,
        file_key: &str,
    ) -> Result<(), String> {
        let base_url = self.get_base_url();
        let url = format!("{}/open-apis/im/v1/messages", base_url);

        let access_token = self.get_access_token().await?;

        let request = serde_json::json!({
            "receive_id_type": receive_id_type,
            "receive_id": receive_id,
            "msg_type": "file",
            "content": serde_json::json!({ "file_key": file_key }),
        });

        let mut headers = reqwest::header::HeaderMap::new();
        headers.insert(
            "Authorization",
            format!("Bearer {}", access_token).parse().unwrap(),
        );
        headers.insert(
            "Content-Type",
            "application/json; charset=utf-8".parse().unwrap(),
        );

        self.http_client
            .post(&url)
            .headers(headers)
            .json(&request)
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {}", e))?;

        Ok(())
    }
}

impl Clone for FeishuGateway {
    fn clone(&self) -> Self {
        Self {
            config: Arc::clone(&self.config),
            status: Arc::clone(&self.status),
            event_callback: Arc::clone(&self.event_callback),
            http_client: self.http_client.clone(),
            access_token: Arc::clone(&self.access_token),
            token_expires_at: Arc::clone(&self.token_expires_at),
            last_chat_id: Arc::clone(&self.last_chat_id),
            last_user_id: Arc::clone(&self.last_user_id),
            ws_task: Arc::new(Mutex::new(None)),
            is_stopping: Arc::clone(&self.is_stopping),
            reconnect_delay_ms: Arc::new(Mutex::new(3000)),
        }
    }
}

#[async_trait::async_trait]
impl Gateway for FeishuGateway {
    fn as_any(&self) -> &dyn std::any::Any {
        self
    }

    async fn start(&self) -> Result<(), String> {
        let (config_enabled, app_id, app_secret) = {
            let config = self.config.lock().await;
            (
                config.enabled,
                config.app_id.clone(),
                config.app_secret.clone(),
            )
        };

        if !config_enabled {
            return Ok(());
        }

        if app_id.is_empty() || app_secret.is_empty() {
            let mut status = self.status.lock().await;
            status.error = Some("缺少必要的配置: app_id 或 app_secret".to_string());
            status.last_error = status.error.clone();
            let error_msg = status.error.clone().unwrap();
            self.emit_event(GatewayEvent::Error(error_msg.clone()));
            self.emit_event(GatewayEvent::StatusChanged(status.clone()));
            return Err(error_msg);
        }

        {
            let mut status = self.status.lock().await;
            status.starting = true;
            status.error = None;
            status.last_error = None;
            self.emit_event(GatewayEvent::StatusChanged(status.clone()));
        }

        match self.get_access_token().await {
            Ok(token) => {
                self.log(&format!(
                    "Access token obtained: {}...",
                    &token[..token.len().min(10)]
                ));
            }
            Err(e) => {
                let mut status = self.status.lock().await;
                status.starting = false;
                status.error = Some(format!("获取访问令牌失败: {}", e));
                status.last_error = status.error.clone();
                self.emit_event(GatewayEvent::Error(status.error.clone().unwrap()));
                self.emit_event(GatewayEvent::StatusChanged(status.clone()));
                return Err(e);
            }
        }

        match self.start_websocket_connection().await {
            Ok(_) => {
                self.log("WebSocket connection started");
            }
            Err(e) => {
                let mut status = self.status.lock().await;
                status.starting = false;
                status.error = Some(format!("WebSocket连接失败: {}", e));
                status.last_error = status.error.clone();
                self.emit_event(GatewayEvent::Error(status.error.clone().unwrap()));
                self.emit_event(GatewayEvent::StatusChanged(status.clone()));
                return Err(e);
            }
        }

        let mut status = self.status.lock().await;
        status.starting = false;
        status.connected = true;
        status.started_at = Some(Local::now().timestamp_millis());
        self.emit_event(GatewayEvent::StatusChanged(status.clone()));
        self.emit_event(GatewayEvent::Connected);

        Ok(())
    }

    async fn stop(&self) -> Result<(), String> {
        *self.is_stopping.lock().await = true;

        // 停止 WebSocket 任务
        if let Some(handle) = self.ws_task.lock().await.take() {
            handle.abort();
        }

        let mut status = self.status.lock().await;

        if !status.connected && !status.starting {
            return Ok(());
        }

        *self.access_token.lock().await = None;
        *self.token_expires_at.lock().await = None;

        status.connected = false;
        status.starting = false;
        status.error = None;
        self.emit_event(GatewayEvent::StatusChanged(status.clone()));
        self.emit_event(GatewayEvent::Disconnected);

        self.log("Gateway stopped");

        Ok(())
    }

    fn is_connected(&self) -> bool {
        self.status.blocking_lock().connected
    }

    fn get_status(&self) -> GatewayStatus {
        self.status.blocking_lock().clone()
    }

    async fn send_notification(&self, text: &str) -> Result<bool, String> {
        if !self.is_connected() {
            return Err("网关未连接".to_string());
        }

        let chat_id = self.last_chat_id.lock().await.clone();

        if let Some(chat_id) = chat_id {
            self.send_message_api("chat_id", &chat_id, text).await?;

            let mut status = self.status.lock().await;
            status.last_outbound_at = Some(Local::now().timestamp_millis());

            Ok(true)
        } else {
            Err("没有可用的聊天".to_string())
        }
    }

    async fn send_message(&self, conversation_id: &str, text: &str) -> Result<bool, String> {
        if !self.is_connected() {
            return Err("网关未连接".to_string());
        }

        self.send_message_api("chat_id", conversation_id, text)
            .await?;

        let mut status = self.status.lock().await;
        status.last_outbound_at = Some(Local::now().timestamp_millis());

        Ok(true)
    }

    async fn send_media_message(
        &self,
        conversation_id: &str,
        file_path: &str,
    ) -> Result<bool, String> {
        if !self.is_connected() {
            return Err("网关未连接".to_string());
        }

        let path = std::path::Path::new(file_path);
        let extension = path
            .extension()
            .and_then(|e| e.to_str())
            .unwrap_or("")
            .to_lowercase();

        match extension.as_str() {
            "jpg" | "jpeg" | "png" | "gif" | "bmp" | "webp" => {
                let image_key = self.upload_image(file_path).await?;
                self.send_image_message_api("chat_id", conversation_id, &image_key)
                    .await?;
            }
            _ => {
                let file_key = self.upload_file(file_path).await?;
                self.send_file_message_api("chat_id", conversation_id, &file_key)
                    .await?;
            }
        }

        let mut status = self.status.lock().await;
        status.last_outbound_at = Some(Local::now().timestamp_millis());

        Ok(true)
    }

    async fn reconnect_if_needed(&self) -> Result<(), String> {
        if !self.is_connected() && !*self.is_stopping.lock().await {
            self.start().await
        } else {
            Ok(())
        }
    }

    async fn edit_message(
        &self,
        _conversation_id: &str,
        _message_id: &str,
        _new_text: &str,
    ) -> Result<bool, String> {
        Err("飞书不支持编辑消息".to_string())
    }

    async fn delete_message(
        &self,
        _conversation_id: &str,
        _message_id: &str,
    ) -> Result<bool, String> {
        Err("飞书不支持删除消息".to_string())
    }

    async fn get_message_history(
        &self,
        _conversation_id: &str,
        _limit: u32,
    ) -> Result<Vec<IMMessage>, String> {
        Err("飞书暂不支持获取历史消息".to_string())
    }

    fn set_event_callback(&self, callback: Option<EventCallback>) {
        *self.event_callback.blocking_lock() = callback;
    }
}
