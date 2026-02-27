use super::gateway::{EventCallback, Gateway, GatewayEvent, GatewayStatus, IMMessage};
use async_trait::async_trait;
use chrono::Local;
use futures_util::{SinkExt, StreamExt};
use reqwest::Client;
use serde::{Deserialize, Serialize};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::mpsc;
use tokio_tungstenite::{connect_async, tungstenite::Message as WsMessage};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DingTalkConfig {
    pub enabled: bool,
    pub client_id: String,
    pub client_secret: String,
    pub agent_id: Option<String>,
    pub robot_code: Option<String>,
    pub message_type: Option<String>,
    pub media_download_path: Option<String>,
    pub debug: Option<bool>,
}

impl Default for DingTalkConfig {
    fn default() -> Self {
        DingTalkConfig {
            enabled: false,
            client_id: String::new(),
            client_secret: String::new(),
            agent_id: None,
            robot_code: None,
            message_type: Some("markdown".to_string()),
            media_download_path: None,
            debug: Some(false),
        }
    }
}

#[derive(Debug, Deserialize)]
struct DingTalkAccessTokenResponse {
    errcode: i32,
    errmsg: Option<String>,
    access_token: Option<String>,
    expire_in: Option<i64>,
}

#[derive(Debug, Deserialize)]
struct DingTalkUserResponse {
    errcode: i32,
    errmsg: Option<String>,
    result: Option<DingTalkUserResult>,
}

#[derive(Debug, Deserialize)]
struct DingTalkUserResult {
    user_id: Option<String>,
}

#[derive(Debug, Deserialize)]
struct DingTalkRobotResponse {
    errcode: i32,
    errmsg: Option<String>,
    msg_id: Option<String>,
}

#[derive(Debug, Deserialize)]
struct DingTalkStreamResponse {
    #[serde(rename = "connId")]
    conn_id: Option<String>,
    #[serde(rename = "topicIds")]
    topic_ids: Option<Vec<String>>,
}

#[derive(Debug, Deserialize)]
struct DingTalkMediaUploadResponse {
    errcode: i32,
    errmsg: Option<String>,
    media_id: Option<String>,
}

#[derive(Debug, Deserialize)]
struct DingTalkMediaResponse {
    errcode: i32,
    errmsg: Option<String>,
}

// Stream 消息格式
#[derive(Debug, Clone, Serialize, Deserialize)]
struct DingTalkStreamMessage {
    #[serde(rename = "messageId")]
    message_id: String,
    data: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct DingTalkInboundMessage {
    #[serde(rename = "msgId")]
    msg_id: String,
    msgtype: Option<String>,
    #[serde(rename = "createAt")]
    create_at: Option<i64>,
    text: Option<DingTalkTextContent>,
    content: Option<DingTalkContent>,
    image: Option<DingTalkImageContent>,
    voice: Option<DingTalkVoiceContent>,
    file: Option<DingTalkFileContent>,
    #[serde(rename = "conversationType")]
    conversation_type: Option<String>,
    #[serde(rename = "conversationId")]
    conversation_id: Option<String>,
    #[serde(rename = "openConversationId")]
    open_conversation_id: Option<String>,
    sender_staff_id: Option<String>,
    #[serde(rename = "senderNick")]
    sender_nick: Option<String>,
    #[serde(rename = "senderCorpId")]
    sender_corp_id: Option<String>,
    #[serde(rename = "robotCode")]
    robot_code: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct DingTalkTextContent {
    content: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct DingTalkContent {
    #[serde(rename = "downloadCode")]
    download_code: Option<String>,
    #[serde(rename = "fileName")]
    file_name: Option<String>,
    recognition: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct DingTalkImageContent {
    #[serde(rename = "downloadCode")]
    download_code: Option<String>,
    #[serde(rename = "fileSize")]
    file_size: Option<String>,
    #[serde(rename = "fileId")]
    file_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct DingTalkVoiceContent {
    #[serde(rename = "downloadCode")]
    download_code: Option<String>,
    #[serde(rename = "fileSize")]
    file_size: Option<String>,
    #[serde(rename = "fileId")]
    file_id: Option<String>,
    #[serde(rename = "duration")]
    duration: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct DingTalkFileContent {
    #[serde(rename = "downloadCode")]
    download_code: Option<String>,
    #[serde(rename = "fileName")]
    file_name: Option<String>,
    #[serde(rename = "fileSize")]
    file_size: Option<String>,
    #[serde(rename = "fileId")]
    file_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DingTalkMediaAttachment {
    pub media_type: String,
    pub file_id: Option<String>,
    pub download_code: Option<String>,
    pub file_name: Option<String>,
    pub file_size: Option<String>,
    pub duration: Option<String>,
    pub local_path: Option<String>,
}

#[derive(Debug, Clone)]
struct DingTalkConversation {
    conversation_type: String,
    conversation_id: String,
    open_conversation_id: Option<String>,
    sender_staff_id: Option<String>,
}

pub struct DingTalkGateway {
    config: Arc<Mutex<DingTalkConfig>>,
    status: Arc<Mutex<GatewayStatus>>,
    event_callback: Arc<Mutex<Option<EventCallback>>>,
    http_client: Client,
    access_token: Arc<Mutex<Option<String>>>,
    token_expires_at: Arc<Mutex<Option<i64>>>,
    ws_sender: Arc<Mutex<Option<mpsc::Sender<String>>>>,
    last_conversation: Arc<Mutex<Option<DingTalkConversation>>>,
    ws_task: Arc<Mutex<Option<tokio::task::JoinHandle<()>>>>,
    stop_ws: Arc<Mutex<Option<tokio::sync::oneshot::Sender<()>>>>,
    reconnect_delay_ms: Arc<Mutex<u64>>,
    is_stopping: Arc<Mutex<bool>>,
}

impl DingTalkGateway {
    pub fn new(config: DingTalkConfig) -> Self {
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
            ws_sender: Arc::new(Mutex::new(None)),
            last_conversation: Arc::new(Mutex::new(None)),
            ws_task: Arc::new(Mutex::new(None)),
            stop_ws: Arc::new(Mutex::new(None)),
            reconnect_delay_ms: Arc::new(Mutex::new(3000)),
            is_stopping: Arc::new(Mutex::new(false)),
        }
    }

    pub fn set_config(&self, config: DingTalkConfig) {
        *self.config.lock().unwrap() = config;
    }

    pub fn get_config(&self) -> DingTalkConfig {
        self.config.lock().unwrap().clone()
    }

    fn emit_event(&self, event: GatewayEvent) {
        if let Some(callback) = &*self.event_callback.lock().unwrap() {
            callback(event);
        }
    }

    fn log(&self, message: &str) {
        if self.config.lock().unwrap().debug.unwrap_or(false) {
            println!("[DingTalk Gateway] {}", message);
        }
    }

    pub async fn get_access_token(&self) -> Result<String, String> {
        let (client_id, client_secret) = {
            let config = self.config.lock().unwrap();
            (config.client_id.clone(), config.client_secret.clone())
        };

        let now = chrono::Utc::now().timestamp();
        if let Some(expires_at) = *self.token_expires_at.lock().unwrap() {
            if expires_at - now > 300 {
                if let Some(token) = &*self.access_token.lock().unwrap() {
                    return Ok(token.clone());
                }
            }
        }

        let url = "https://api.dingtalk.com/v1.0/oauth2/accessToken";
        let request = serde_json::json!({
            "appKey": client_id,
            "appSecret": client_secret,
        });

        let response = self
            .http_client
            .post(url)
            .json(&request)
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {}", e))?
            .json::<DingTalkAccessTokenResponse>()
            .await
            .map_err(|e| format!("Failed to parse response: {}", e))?;

        if response.errcode == 0 && response.access_token.is_some() {
            let token = response.access_token.unwrap();
            let expires_in = response.expire_in.unwrap_or(7200);
            let expires_at = now + expires_in;

            *self.access_token.lock().unwrap() = Some(token.clone());
            *self.token_expires_at.lock().unwrap() = Some(expires_at);

            Ok(token)
        } else {
            Err(format!(
                "Failed to get access token: {}",
                response.errmsg.unwrap_or_default()
            ))
        }
    }

    async fn upload_media(&self, file_path: &str, media_type: &str) -> Result<String, String> {
        let access_token = self.get_access_token().await?;

        let file_data = tokio::fs::read(file_path)
            .await
            .map_err(|e| format!("Failed to read file: {}", e))?;

        let file_name = std::path::Path::new(file_path)
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("file");

        let part = reqwest::multipart::Part::bytes(file_data).file_name(file_name.to_string());

        let form = reqwest::multipart::Form::new()
            .part("media", part)
            .text("type".to_string(), media_type.to_string());

        let url = format!(
            "https://api.dingtalk.com/v1.0/robot/media/upload?access_token={}&agentId={}",
            access_token,
            self.config
                .lock()
                .unwrap()
                .agent_id
                .clone()
                .unwrap_or_default()
        );

        let response = self
            .http_client
            .post(&url)
            .multipart(form)
            .send()
            .await
            .map_err(|e| format!("Upload failed: {}", e))?
            .json::<DingTalkMediaUploadResponse>()
            .await
            .map_err(|e| format!("Failed to parse response: {}", e))?;

        if response.errcode == 0 && response.media_id.is_some() {
            Ok(response.media_id.unwrap())
        } else {
            Err(format!(
                "Upload failed: {}",
                response.errmsg.unwrap_or_default()
            ))
        }
    }

    async fn download_media(&self, download_code: &str) -> Result<Vec<u8>, String> {
        let access_token = self.get_access_token().await?;

        let url = format!(
            "https://api.dingtalk.com/v1.0/robot/media/download?access_token={}&downloadCode={}",
            access_token, download_code
        );

        let response = self
            .http_client
            .get(&url)
            .send()
            .await
            .map_err(|e| format!("Download failed: {}", e))?;

        let bytes = response
            .bytes()
            .await
            .map_err(|e| format!("Failed to read bytes: {}", e))?;

        Ok(bytes.to_vec())
    }

    // 使用 OpenAPI 发送消息到指定用户
    async fn send_message_to_user(
        &self,
        user_id: &str,
        content: &str,
        msg_type: &str,
    ) -> Result<(), String> {
        let access_token = self.get_access_token().await?;
        let agent_id = self
            .config
            .lock()
            .unwrap()
            .agent_id
            .clone()
            .unwrap_or_default();

        let url = format!(
            "https://api.dingtalk.com/v1.0/robot/oToMessages/batchSend?access_token={}",
            access_token
        );

        let msg_param = match msg_type {
            "text" => serde_json::json!({
                "msgtype": "text",
                "text": {
                    "content": content
                }
            }),
            "markdown" => serde_json::json!({
                "msgtype": "markdown",
                "markdown": {
                    "title": "通知",
                    "text": content
                }
            }),
            _ => serde_json::json!({
                "msgtype": "text",
                "text": {
                    "content": content
                }
            }),
        };

        let request = serde_json::json!({
            "robotCode": self.config.lock().unwrap().robot_code.clone().unwrap_or_default(),
            "userIds": [user_id],
            "msgKey": msg_type,
            "msgParam": msg_param.to_string(),
        });

        let response = self
            .http_client
            .post(&url)
            .json(&request)
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {}", e))?
            .json::<DingTalkRobotResponse>()
            .await
            .map_err(|e| format!("Failed to parse response: {}", e))?;

        if response.errcode == 0 {
            Ok(())
        } else {
            Err(format!(
                "Send message failed: {}",
                response.errmsg.unwrap_or_default()
            ))
        }
    }

    // 发送群消息
    async fn send_message_to_group(
        &self,
        open_conversation_id: &str,
        content: &str,
        msg_type: &str,
    ) -> Result<(), String> {
        let access_token = self.get_access_token().await?;

        let url = format!(
            "https://api.dingtalk.com/v1.0/robot/groupMessages/send?access_token={}",
            access_token
        );

        let msg_param = match msg_type {
            "text" => serde_json::json!({
                "msgtype": "text",
                "text": {
                    "content": content
                }
            }),
            "markdown" => serde_json::json!({
                "msgtype": "markdown",
                "markdown": {
                    "title": "通知",
                    "text": content
                }
            }),
            _ => serde_json::json!({
                "msgtype": "text",
                "text": {
                    "content": content
                }
            }),
        };

        let request = serde_json::json!({
            "robotCode": self.config.lock().unwrap().robot_code.clone().unwrap_or_default(),
            "openConversationId": open_conversation_id,
            "msgKey": msg_type,
            "msgParam": msg_param.to_string(),
        });

        let response = self
            .http_client
            .post(&url)
            .json(&request)
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {}", e))?
            .json::<DingTalkRobotResponse>()
            .await
            .map_err(|e| format!("Failed to parse response: {}", e))?;

        if response.errcode == 0 {
            Ok(())
        } else {
            Err(format!(
                "Send message failed: {}",
                response.errmsg.unwrap_or_default()
            ))
        }
    }

    fn extract_media_from_message(
        &self,
        msg: &DingTalkInboundMessage,
    ) -> Vec<DingTalkMediaAttachment> {
        let mut attachments = Vec::new();

        if let Some(image) = &msg.image {
            if let Some(download_code) = &image.download_code {
                attachments.push(DingTalkMediaAttachment {
                    media_type: "image".to_string(),
                    file_id: image.file_id.clone(),
                    download_code: Some(download_code.clone()),
                    file_name: None,
                    file_size: image.file_size.clone(),
                    duration: None,
                    local_path: None,
                });
            }
        }

        if let Some(voice) = &msg.voice {
            if let Some(download_code) = &voice.download_code {
                attachments.push(DingTalkMediaAttachment {
                    media_type: "voice".to_string(),
                    file_id: voice.file_id.clone(),
                    download_code: Some(download_code.clone()),
                    file_name: None,
                    file_size: voice.file_size.clone(),
                    duration: voice.duration.clone(),
                    local_path: None,
                });
            }
        }

        if let Some(file) = &msg.file {
            if let Some(download_code) = &file.download_code {
                attachments.push(DingTalkMediaAttachment {
                    media_type: "file".to_string(),
                    file_id: file.file_id.clone(),
                    download_code: Some(download_code.clone()),
                    file_name: file.file_name.clone(),
                    file_size: file.file_size.clone(),
                    duration: None,
                    local_path: None,
                });
            }
        }

        attachments
    }

    async fn handle_inbound_message(&self, data: &str) -> Result<(), String> {
        let msg: DingTalkInboundMessage =
            serde_json::from_str(data).map_err(|e| format!("Failed to parse message: {}", e))?;

        let content = msg
            .text
            .as_ref()
            .and_then(|t| t.content.clone())
            .or_else(|| msg.content.as_ref().and_then(|c| c.recognition.clone()))
            .unwrap_or_default();

        let attachments = self.extract_media_from_message(&msg);

        if content.is_empty() && attachments.is_empty() {
            return Ok(());
        }

        let conversation_type = msg
            .conversation_type
            .clone()
            .unwrap_or_else(|| "1".to_string());

        // 保存会话信息
        if let Some(conversation_id) = &msg.conversation_id {
            *self.last_conversation.lock().unwrap() = Some(DingTalkConversation {
                conversation_type: conversation_type.clone(),
                conversation_id: conversation_id.clone(),
                open_conversation_id: msg.open_conversation_id.clone(),
                sender_staff_id: msg.sender_staff_id.clone(),
            });
        }

        let im_message = IMMessage {
            id: msg.msg_id.clone(),
            platform: "dingtalk".to_string(),
            channel_id: msg.conversation_id.clone().unwrap_or_default(),
            user_id: msg.sender_staff_id.clone().unwrap_or_default(),
            user_name: msg.sender_nick.clone().unwrap_or_default(),
            content,
            timestamp: msg
                .create_at
                .unwrap_or_else(|| chrono::Utc::now().timestamp()),
            is_mention: false,
        };

        let mut status = self.status.lock().unwrap();
        status.last_inbound_at = Some(Local::now().timestamp_millis());

        self.emit_event(GatewayEvent::Message(im_message));

        Ok(())
    }

    // 启动 WebSocket Stream 连接
    async fn start_stream_connection(&self) -> Result<(), String> {
        let access_token = self.get_access_token().await?;

        let url = format!(
            "wss://api.dingtalk.com/v1.0/robot/stream?token={}",
            access_token
        );

        self.log(&format!("Connecting to Stream: {}", url));

        let (ws_stream, _) = connect_async(&url)
            .await
            .map_err(|e| format!("WebSocket connection failed: {}", e))?;

        self.log("WebSocket Stream connected");

        let (mut ws_sender, mut ws_receiver) = ws_stream.split();
        let (tx, mut rx) = mpsc::channel::<String>(100);
        *self.ws_sender.lock().unwrap() = Some(tx);

        // 重置停止标志
        *self.is_stopping.lock().unwrap() = false;

        // 发送连接成功消息
        let connect_msg = serde_json::json!({
            "type": "connect",
            "data": {}
        });
        let _ = ws_sender
            .send(WsMessage::Text(connect_msg.to_string()))
            .await;

        let event_callback = Arc::clone(&self.event_callback);
        let status = Arc::clone(&self.status);
        let is_stopping = Arc::clone(&self.is_stopping);
        let self_ref = self.clone();

        // 启动消息处理任务
        let handle = tokio::spawn(async move {
            let mut heartbeat_interval = tokio::time::interval(Duration::from_secs(30));

            loop {
                tokio::select! {
                    // 处理 WebSocket 消息
                    Some(msg) = ws_receiver.next() => {
                        match msg {
                            Ok(WsMessage::Text(text)) => {
                                self_ref.log(&format!("Received: {}", &text[..text.len().min(100)]));

                                // 解析 Stream 消息
                                if let Ok(stream_msg) = serde_json::from_str::<DingTalkStreamMessage>(&text) {
                                    // 发送确认回执
                                    let ack = serde_json::json!({
                                        "messageId": stream_msg.message_id,
                                        "type": "ack",
                                        "data": { "success": true }
                                    });
                                    let _ = ws_sender.send(WsMessage::Text(ack.to_string())).await;

                                    // 处理消息
                                    let _ = self_ref.handle_inbound_message(&stream_msg.data).await;
                                }
                            }
                            Ok(WsMessage::Close(_)) => {
                                self_ref.log("WebSocket closed by server");
                                break;
                            }
                            Ok(WsMessage::Ping(data)) => {
                                let _ = ws_sender.send(WsMessage::Pong(data)).await;
                            }
                            Err(e) => {
                                self_ref.log(&format!("WebSocket error: {}", e));
                                break;
                            }
                            _ => {}
                        }
                    }

                    // 发送心跳
                    _ = heartbeat_interval.tick() => {
                        let heartbeat = serde_json::json!({
                            "type": "heartbeat",
                            "data": {}
                        });
                        if ws_sender.send(WsMessage::Text(heartbeat.to_string())).await.is_err() {
                            break;
                        }
                    }

                    // 检查是否需要停止
                    _ = tokio::time::sleep(Duration::from_millis(100)) => {
                        if *is_stopping.lock().unwrap() {
                            break;
                        }
                    }
                }
            }

            // 连接断开，更新状态
            let mut s = status.lock().unwrap();
            s.connected = false;
            if let Some(cb) = &*event_callback.lock().unwrap() {
                cb(GatewayEvent::Disconnected);
            }

            self_ref.log("WebSocket Stream disconnected");
        });

        *self.ws_task.lock().unwrap() = Some(handle);

        Ok(())
    }

    // 自动重连
    async fn reconnect(&self) -> Result<(), String> {
        if *self.is_stopping.lock().unwrap() {
            return Ok(());
        }

        let delay = *self.reconnect_delay_ms.lock().unwrap();
        self.log(&format!("Reconnecting in {}ms...", delay));

        tokio::time::sleep(Duration::from_millis(delay)).await;

        if *self.is_stopping.lock().unwrap() {
            return Ok(());
        }

        // 指数退避
        let new_delay = std::cmp::min(delay * 2, 60000);
        *self.reconnect_delay_ms.lock().unwrap() = new_delay;

        self.start_stream_connection().await
    }
}

impl Clone for DingTalkGateway {
    fn clone(&self) -> Self {
        Self {
            config: Arc::clone(&self.config),
            status: Arc::clone(&self.status),
            event_callback: Arc::clone(&self.event_callback),
            http_client: Client::builder()
                .timeout(Duration::from_secs(60))
                .build()
                .expect("Failed to create HTTP client"),
            access_token: Arc::clone(&self.access_token),
            token_expires_at: Arc::clone(&self.token_expires_at),
            ws_sender: Arc::clone(&self.ws_sender),
            last_conversation: Arc::clone(&self.last_conversation),
            ws_task: Arc::new(Mutex::new(None)),
            stop_ws: Arc::new(Mutex::new(None)),
            reconnect_delay_ms: Arc::new(Mutex::new(3000)),
            is_stopping: Arc::clone(&self.is_stopping),
        }
    }
}

#[async_trait::async_trait]
impl Gateway for DingTalkGateway {
    fn as_any(&self) -> &dyn std::any::Any {
        self
    }

    async fn start(&self) -> Result<(), String> {
        let (config_enabled, client_id, client_secret) = {
            let config = self.config.lock().unwrap();
            (
                config.enabled,
                config.client_id.clone(),
                config.client_secret.clone(),
            )
        };

        if !config_enabled {
            return Ok(());
        }

        if client_id.is_empty() || client_secret.is_empty() {
            let mut status = self.status.lock().unwrap();
            status.error = Some("缺少必要的配置: client_id 或 client_secret".to_string());
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

        // 获取访问令牌
        match self.get_access_token().await {
            Ok(token) => {
                self.log(&format!(
                    "Access token obtained: {}...",
                    &token[..token.len().min(10)]
                ));
            }
            Err(e) => {
                let mut status = self.status.lock().unwrap();
                status.starting = false;
                status.error = Some(format!("获取访问令牌失败: {}", e));
                status.last_error = status.error.clone();
                self.emit_event(GatewayEvent::Error(status.error.clone().unwrap()));
                self.emit_event(GatewayEvent::StatusChanged(status.clone()));
                return Err(e);
            }
        }

        // 启动 Stream 连接
        match self.start_stream_connection().await {
            Ok(_) => {
                let mut status = self.status.lock().unwrap();
                status.starting = false;
                status.connected = true;
                status.started_at = Some(Local::now().timestamp_millis());
                self.emit_event(GatewayEvent::StatusChanged(status.clone()));
                self.emit_event(GatewayEvent::Connected);
                self.log("Stream connection started");
            }
            Err(e) => {
                let mut status = self.status.lock().unwrap();
                status.starting = false;
                status.error = Some(format!("Stream连接失败: {}", e));
                status.last_error = status.error.clone();
                self.emit_event(GatewayEvent::Error(status.error.clone().unwrap()));
                self.emit_event(GatewayEvent::StatusChanged(status.clone()));
                return Err(e);
            }
        }

        Ok(())
    }

    async fn stop(&self) -> Result<(), String> {
        *self.is_stopping.lock().unwrap() = true;

        // 停止 WebSocket 任务
        if let Some(handle) = self.ws_task.lock().unwrap().take() {
            handle.abort();
        }

        let mut status = self.status.lock().unwrap();

        if !status.connected && !status.starting {
            return Ok(());
        }

        *self.access_token.lock().unwrap() = None;
        *self.token_expires_at.lock().unwrap() = None;
        *self.ws_sender.lock().unwrap() = None;

        status.connected = false;
        status.starting = false;
        status.error = None;
        self.emit_event(GatewayEvent::StatusChanged(status.clone()));
        self.emit_event(GatewayEvent::Disconnected);

        self.log("Gateway stopped");

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

        self.get_access_token().await?;

        // 获取最后会话信息
        let conversation = self.last_conversation.lock().unwrap().clone();

        if let Some(conv) = conversation {
            let msg_type = self
                .config
                .lock()
                .unwrap()
                .message_type
                .clone()
                .unwrap_or_else(|| "text".to_string());

            if conv.conversation_type == "1" {
                // 单聊
                if let Some(user_id) = conv.sender_staff_id {
                    self.send_message_to_user(&user_id, text, &msg_type).await?;
                }
            } else if let Some(open_conv_id) = conv.open_conversation_id {
                // 群聊
                self.send_message_to_group(&open_conv_id, text, &msg_type)
                    .await?;
            }
        } else {
            return Err("没有可用的会话".to_string());
        }

        let mut status = self.status.lock().unwrap();
        status.last_outbound_at = Some(Local::now().timestamp_millis());

        Ok(true)
    }

    async fn send_message(&self, conversation_id: &str, text: &str) -> Result<bool, String> {
        if !self.is_connected() {
            return Err("网关未连接".to_string());
        }

        self.get_access_token().await?;

        let msg_type = self
            .config
            .lock()
            .unwrap()
            .message_type
            .clone()
            .unwrap_or_else(|| "text".to_string());

        // 获取最后会话信息
        let conversation = self.last_conversation.lock().unwrap().clone();

        if let Some(conv) = conversation {
            if conv.conversation_id == conversation_id {
                if conv.conversation_type == "1" {
                    // 单聊
                    if let Some(user_id) = conv.sender_staff_id {
                        self.send_message_to_user(&user_id, text, &msg_type).await?;
                    }
                } else if let Some(open_conv_id) = conv.open_conversation_id {
                    // 群聊
                    self.send_message_to_group(&open_conv_id, text, &msg_type)
                        .await?;
                }
            }
        }

        let mut status = self.status.lock().unwrap();
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

        self.get_access_token().await?;

        // 上传媒体文件
        let media_id = self.upload_media(file_path, "file").await?;

        // TODO: 发送媒体消息需要使用不同的 API
        // 这里简化处理，实际应该根据文件类型选择不同的发送方式

        let mut status = self.status.lock().unwrap();
        status.last_outbound_at = Some(Local::now().timestamp_millis());

        Ok(true)
    }

    async fn reconnect_if_needed(&self) -> Result<(), String> {
        if !self.is_connected() && !*self.is_stopping.lock().unwrap() {
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
        Err("DingTalk 不支持编辑消息".to_string())
    }

    async fn delete_message(
        &self,
        _conversation_id: &str,
        _message_id: &str,
    ) -> Result<bool, String> {
        Err("DingTalk 不支持删除消息".to_string())
    }

    async fn get_message_history(
        &self,
        _conversation_id: &str,
        _limit: u32,
    ) -> Result<Vec<IMMessage>, String> {
        Err("DingTalk 不支持获取历史消息".to_string())
    }

    fn set_event_callback(&self, callback: Option<EventCallback>) {
        *self.event_callback.lock().unwrap() = callback;
    }
}
