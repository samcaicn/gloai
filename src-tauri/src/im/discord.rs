use super::gateway::{EventCallback, Gateway, GatewayEvent, GatewayStatus, IMMessage};

use chrono::Local;
use futures_util::{SinkExt, StreamExt};
use reqwest::Client;
use serde::{Deserialize, Serialize};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio_tungstenite::{connect_async, tungstenite::Message};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DiscordConfig {
    pub enabled: bool,
    pub bot_token: String,
    pub debug: Option<bool>,
}

impl Default for DiscordConfig {
    fn default() -> Self {
        DiscordConfig {
            enabled: false,
            bot_token: String::new(),
            debug: Some(false),
        }
    }
}

#[derive(Debug, Deserialize)]
struct DiscordUser {
    id: String,
    username: String,
    bot: Option<bool>,
}

#[derive(Debug, Deserialize)]
struct DiscordGuild {
    id: String,
    name: String,
}

#[derive(Debug, Deserialize)]
struct DiscordChannel {
    id: String,
    #[serde(rename = "type")]
    channel_type: i32,
    name: Option<String>,
    last_message_id: Option<String>,
}

#[derive(Debug, Deserialize)]
struct DiscordMessage {
    id: String,
    channel_id: String,
    author: DiscordMessageAuthor,
    content: String,
    timestamp: String,
    mention_everyone: bool,
    mentions: Vec<DiscordMessageAuthor>,
    attachments: Vec<DiscordAttachment>,
    embeds: Vec<DiscordEmbed>,
    message_reference: Option<DiscordMessageReference>,
}

#[derive(Debug, Deserialize)]
struct DiscordMessageAuthor {
    id: String,
    username: String,
    bot: Option<bool>,
}

#[derive(Debug, Deserialize)]
struct DiscordAttachment {
    id: String,
    filename: String,
    content_type: Option<String>,
    size: i32,
    url: String,
}

#[derive(Debug, Deserialize)]
struct DiscordEmbed {
    title: Option<String>,
    description: Option<String>,
    color: Option<i32>,
    fields: Vec<DiscordEmbedField>,
}

#[derive(Debug, Deserialize)]
struct DiscordEmbedField {
    name: String,
    value: String,
    inline: Option<bool>,
}

#[derive(Debug, Deserialize)]
struct DiscordMessageReference {
    message_id: Option<String>,
    channel_id: Option<String>,
}

#[derive(Debug, Deserialize)]
struct DiscordReady {
    v: i32,
    user: DiscordUser,
    guilds: Vec<DiscordGuild>,
    session_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct DiscordGatewayPayload {
    op: i32,
    d: serde_json::Value,
    s: Option<i64>,
    t: Option<String>,
}

const DISCORD_GATEWAY_URL: &str = "wss://gateway.discord.gg/?v=10&encoding=json";

const OP_HELLO: i32 = 10;
const OP_IDENTIFY: i32 = 2;
const OP_HEARTBEAT: i32 = 1;
const OP_HEARTBEAT_ACK: i32 = 11;
const OP_RESUME: i32 = 6;
const OP_RECONNECT: i32 = 7;
const OP_INVALID_SESSION: i32 = 9;

pub struct DiscordGateway {
    config: Arc<Mutex<DiscordConfig>>,
    status: Arc<Mutex<GatewayStatus>>,
    event_callback: Arc<Mutex<Option<EventCallback>>>,
    http_client: Client,
    last_channel_id: Arc<Mutex<Option<String>>>,
    ws_task: Arc<Mutex<Option<tokio::task::JoinHandle<()>>>>,
    stop_ws: Arc<Mutex<Option<tokio::sync::oneshot::Sender<()>>>>,
    session_id: Arc<Mutex<Option<String>>>,
    seq: Arc<Mutex<Option<i64>>>,
    heartbeat_interval: Arc<Mutex<Option<u64>>>,
}

impl DiscordGateway {
    pub fn new(config: DiscordConfig) -> Self {
        let http_client = Client::builder()
            .timeout(Duration::from_secs(30))
            .build()
            .expect("Failed to create HTTP client");

        Self {
            config: Arc::new(Mutex::new(config)),
            status: Arc::new(Mutex::new(GatewayStatus::default())),
            event_callback: Arc::new(Mutex::new(None)),
            http_client,
            last_channel_id: Arc::new(Mutex::new(None)),
            ws_task: Arc::new(Mutex::new(None)),
            stop_ws: Arc::new(Mutex::new(None)),
            session_id: Arc::new(Mutex::new(None)),
            seq: Arc::new(Mutex::new(None)),
            heartbeat_interval: Arc::new(Mutex::new(None)),
        }
    }

    pub fn set_config(&self, config: DiscordConfig) {
        *self.config.lock().unwrap() = config;
    }

    pub fn get_config(&self) -> DiscordConfig {
        self.config.lock().unwrap().clone()
    }

    fn get_api_url(&self, endpoint: &str) -> String {
        format!("https://discord.com/api/v10{}", endpoint)
    }

    fn get_headers(&self) -> reqwest::header::HeaderMap {
        let token = self.config.lock().unwrap().bot_token.clone();
        let mut headers = reqwest::header::HeaderMap::new();
        headers.insert(
            reqwest::header::AUTHORIZATION,
            format!("Bot {}", token).parse().unwrap(),
        );
        headers.insert(
            reqwest::header::CONTENT_TYPE,
            "application/json".parse().unwrap(),
        );
        headers
    }

    fn emit_event(&self, event: GatewayEvent) {
        if let Some(callback) = &*self.event_callback.lock().unwrap() {
            callback(event);
        }
    }

    fn log(&self, message: &str) {
        if self.config.lock().unwrap().debug.unwrap_or(false) {
            println!("[Discord Gateway] {}", message);
        }
    }

    async fn get_current_user(&self) -> Result<DiscordUser, String> {
        let url = self.get_api_url("/users/@me");
        let response = self
            .http_client
            .get(&url)
            .headers(self.get_headers())
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {}", e))?;

        if response.status().is_success() {
            response
                .json::<DiscordUser>()
                .await
                .map_err(|e| format!("Failed to parse response: {}", e))
        } else {
            Err(format!("Failed to get user: {}", response.status()))
        }
    }

    async fn send_message(&self, channel_id: &str, content: &str) -> Result<(), String> {
        let url = self.get_api_url(&format!("/channels/{}/messages", channel_id));

        #[derive(Serialize)]
        struct SendMessageRequest {
            content: String,
        }

        let request = SendMessageRequest {
            content: content.to_string(),
        };

        self.http_client
            .post(&url)
            .headers(self.get_headers())
            .json(&request)
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {}", e))?;

        Ok(())
    }

    fn create_identify_payload(&self) -> String {
        let token = self.config.lock().unwrap().bot_token.clone();
        let payload = serde_json::json!({
            "op": OP_IDENTIFY,
            "d": {
                "token": token,
                "intents": 513,
                "properties": {
                    "os": "desktop",
                    "browser": "GloAI",
                    "device": "GloAI"
                }
            }
        });
        payload.to_string()
    }

    fn create_heartbeat_payload(&self) -> String {
        let seq = *self.seq.lock().unwrap();
        let payload = serde_json::json!({
            "op": OP_HEARTBEAT,
            "d": seq
        });
        payload.to_string()
    }

    async fn start_gateway(&self) -> Result<(), String> {
        let (tx, mut rx) = tokio::sync::oneshot::channel::<()>();
        *self.stop_ws.lock().unwrap() = Some(tx);

        let _http_client = self.http_client.clone();
        let config = self.get_config();
        let event_callback = Arc::clone(&self.event_callback);
        let _status = Arc::clone(&self.status);
        let seq = Arc::clone(&self.seq);
        let _session_id = Arc::clone(&self.session_id);
        let heartbeat_interval = Arc::clone(&self.heartbeat_interval);
        let log_fn = std::sync::Arc::new(move |msg: &str| {
            if config.debug.unwrap_or(false) {
                println!("[Discord Gateway] {}", msg);
            }
        });

        let handle = tokio::spawn(async move {
            if let Ok((ws_stream, _)) = connect_async(DISCORD_GATEWAY_URL).await {
                log_fn("Connected to Discord Gateway");

                let (mut write, mut read) = ws_stream.split();

                let mut _heartbeat_interval_ms: u64 = 41250;
                let mut _last_seq: Option<i64> = None;

                loop {
                    tokio::select! {
                        _ = &mut rx => {
                            break;
                        }
                        msg = read.next() => {
                            if let Some(Ok(Message::Text(text))) = msg {
                                if let Ok(payload) = serde_json::from_str::<DiscordGatewayPayload>(&text) {
                                    if let Some(s) = payload.s {
                                        *seq.lock().unwrap() = Some(s);
                                        _last_seq = Some(s);
                                    }

                                    match payload.op {
                                        OP_HELLO => {
                                            if let Some(interval) = payload.d.get("heartbeat_interval").and_then(|v| v.as_u64()) {
                                                _heartbeat_interval_ms = interval;
                                                *heartbeat_interval.lock().unwrap() = Some(interval);

                                                let identify = serde_json::json!({
                                                    "op": OP_IDENTIFY,
                                                    "d": {
                                                        "token": config.bot_token,
                                                        "intents": 513,
                                                        "properties": {
                                                            "os": "desktop",
                                                            "browser": "GloAI",
                                                            "device": "GloAI"
                                                        }
                                                    }
                                                });
                                                let _ = write.send(Message::Text(identify.to_string().into())).await;
                                                log_fn("Sent IDENTIFY");
                                            }
                                        }
                                        OP_HEARTBEAT_ACK => {
                                            log_fn("Heartbeat acknowledged");
                                        }
                                        OP_RECONNECT | OP_INVALID_SESSION => {
                                            log_fn("Need to reconnect");
                                            break;
                                        }
                                        _ => {
                                            if let Some(event_type) = payload.t {
                                                if event_type == "MESSAGE_CREATE" {
                                                    if let Some(msg_data) = payload.d.get("content").and_then(|v| v.as_str()) {
                                                        let channel_id = payload.d.get("channel_id").and_then(|v| v.as_str()).unwrap_or("");
                                                        let user_id = payload.d.get("author").and_then(|a| a.get("id")).and_then(|v| v.as_str()).unwrap_or("");
                                                        let username = payload.d.get("author").and_then(|a| a.get("username")).and_then(|v| v.as_str()).unwrap_or("");

                                                        if !msg_data.is_empty() && user_id != config.bot_token {
                                                            if let Some(callback) = &*event_callback.lock().unwrap() {
                                                                callback(GatewayEvent::Message(IMMessage {
                                                                    id: payload.d.get("id").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                                                                    platform: "discord".to_string(),
                                                                    channel_id: channel_id.to_string(),
                                                                    user_id: user_id.to_string(),
                                                                    user_name: username.to_string(),
                                                                    content: msg_data.to_string(),
                                                                    timestamp: chrono::Utc::now().timestamp(),
                                                                    is_mention: false,
                                                                }));
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        });

        *self.ws_task.lock().unwrap() = Some(handle);
        Ok(())
    }

    fn stop_gateway(&self) {
        if let Some(tx) = self.stop_ws.lock().unwrap().take() {
            let _ = tx.send(());
        }

        if let Some(handle) = self.ws_task.lock().unwrap().take() {
            let _ = handle.abort();
        }
    }
}

impl Clone for DiscordGateway {
    fn clone(&self) -> Self {
        Self {
            config: Arc::clone(&self.config),
            status: Arc::clone(&self.status),
            event_callback: Arc::clone(&self.event_callback),
            http_client: self.http_client.clone(),
            last_channel_id: Arc::clone(&self.last_channel_id),
            ws_task: Arc::clone(&self.ws_task),
            stop_ws: Arc::clone(&self.stop_ws),
            session_id: Arc::clone(&self.session_id),
            seq: Arc::clone(&self.seq),
            heartbeat_interval: Arc::clone(&self.heartbeat_interval),
        }
    }
}

#[async_trait::async_trait]
impl Gateway for DiscordGateway {
    fn as_any(&self) -> &dyn std::any::Any {
        self
    }

    async fn start(&self) -> Result<(), String> {
        let (config_enabled, bot_token) = {
            let config = self.config.lock().unwrap();
            (config.enabled, config.bot_token.clone())
        };

        if !config_enabled {
            return Ok(());
        }

        if bot_token.is_empty() {
            let mut status = self.status.lock().unwrap();
            status.error = Some("缺少必要的配置: bot_token".to_string());
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

        match self.get_current_user().await {
            Ok(user) => {
                self.log(&format!("Bot verified: {}#{}", user.username, user.id));
            }
            Err(e) => {
                let mut status = self.status.lock().unwrap();
                status.starting = false;
                status.error = Some(format!("验证 bot token 失败: {}", e));
                status.last_error = status.error.clone();
                self.emit_event(GatewayEvent::Error(status.error.clone().unwrap()));
                self.emit_event(GatewayEvent::StatusChanged(status.clone()));
                return Err(e);
            }
        }

        match self.start_gateway().await {
            Ok(_) => {
                self.log("Gateway started");
            }
            Err(e) => {
                let mut status = self.status.lock().unwrap();
                status.starting = false;
                status.error = Some(format!("Gateway启动失败: {}", e));
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

        Ok(())
    }

    async fn stop(&self) -> Result<(), String> {
        self.stop_gateway();

        let mut status = self.status.lock().unwrap();

        if !status.connected && !status.starting {
            return Ok(());
        }

        status.connected = false;
        status.starting = false;
        status.error = None;
        self.emit_event(GatewayEvent::StatusChanged(status.clone()));
        self.emit_event(GatewayEvent::Disconnected);

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

        let channel_id = self.last_channel_id.lock().unwrap().clone();

        if let Some(channel_id) = channel_id {
            self.send_message(&channel_id, text).await?;
            let mut status = self.status.lock().unwrap();
            status.last_outbound_at = Some(Local::now().timestamp_millis());
            Ok(true)
        } else {
            Err("没有可用的聊天频道".to_string())
        }
    }

    async fn send_message(&self, conversation_id: &str, text: &str) -> Result<bool, String> {
        if !self.is_connected() {
            return Err("网关未连接".to_string());
        }

        self.send_message(conversation_id, text).await?;

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

        let file_bytes = tokio::fs::read(file_path)
            .await
            .map_err(|e| format!("读取文件失败: {}", e))?;

        let file_name = std::path::Path::new(file_path)
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("file")
            .to_string();

        let extension = std::path::Path::new(file_path)
            .extension()
            .and_then(|e| e.to_str())
            .unwrap_or("")
            .to_lowercase();

        let content_type = match extension.as_str() {
            "jpg" | "jpeg" | "png" | "gif" | "bmp" | "webp" => "image/png",
            "mp4" | "avi" | "mov" | "webm" => "video/mp4",
            "mp3" | "wav" | "ogg" => "audio/mpeg",
            _ => "application/octet-stream",
        };

        let url = format!(
            "https://discord.com/api/v10/channels/{}/messages",
            conversation_id
        );

        let part = reqwest::multipart::Part::bytes(file_bytes)
            .file_name(file_name.clone())
            .mime_str(content_type)
            .map_err(|e| format!("Invalid mime type: {}", e))?;

        let form = reqwest::multipart::Form::new()
            .text("content", "")
            .part("file", part);

        let response = self
            .http_client
            .post(&url)
            .header(
                "Authorization",
                format!("Bot {}", self.config.lock().unwrap().bot_token.clone()),
            )
            .multipart(form)
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {}", e))?;

        if !response.status().is_success() {
            return Err(format!("Failed to send media: {}", response.status()));
        }

        let mut status = self.status.lock().unwrap();
        status.last_outbound_at = Some(Local::now().timestamp_millis());

        Ok(true)
    }

    async fn reconnect_if_needed(&self) -> Result<(), String> {
        if !self.is_connected() {
            self.stop_gateway();
            self.start().await
        } else {
            Ok(())
        }
    }

    async fn edit_message(
        &self,
        conversation_id: &str,
        message_id: &str,
        new_text: &str,
    ) -> Result<bool, String> {
        if !self.is_connected() {
            return Err("网关未连接".to_string());
        }

        let url = format!(
            "https://discord.com/api/v10/channels/{}/messages/{}",
            conversation_id, message_id
        );

        #[derive(Serialize)]
        struct EditMessageRequest {
            content: String,
        }

        let request = EditMessageRequest {
            content: new_text.to_string(),
        };

        let response = self
            .http_client
            .patch(&url)
            .header(
                "Authorization",
                format!("Bot {}", self.config.lock().unwrap().bot_token.clone()),
            )
            .json(&request)
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {}", e))?;

        if !response.status().is_success() {
            return Err(format!("Failed to edit message: {}", response.status()));
        }

        Ok(true)
    }

    async fn delete_message(
        &self,
        conversation_id: &str,
        message_id: &str,
    ) -> Result<bool, String> {
        if !self.is_connected() {
            return Err("网关未连接".to_string());
        }

        let url = format!(
            "https://discord.com/api/v10/channels/{}/messages/{}",
            conversation_id, message_id
        );

        let response = self
            .http_client
            .delete(&url)
            .header(
                "Authorization",
                format!("Bot {}", self.config.lock().unwrap().bot_token.clone()),
            )
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {}", e))?;

        if !response.status().is_success() {
            return Err(format!("Failed to delete message: {}", response.status()));
        }

        Ok(true)
    }

    async fn get_message_history(
        &self,
        conversation_id: &str,
        limit: u32,
    ) -> Result<Vec<IMMessage>, String> {
        if !self.is_connected() {
            return Err("网关未连接".to_string());
        }

        let url = format!(
            "https://discord.com/api/v10/channels/{}/messages?limit={}",
            conversation_id, limit
        );

        #[derive(Deserialize)]
        struct DiscordMessage {
            id: String,
            author: DiscordUser,
            content: String,
            timestamp: String,
            message_type: Option<i32>,
        }

        #[derive(Deserialize)]
        struct DiscordUser {
            id: String,
            username: String,
            bot: Option<bool>,
        }

        let response = self
            .http_client
            .get(&url)
            .header(
                "Authorization",
                format!("Bot {}", self.config.lock().unwrap().bot_token.clone()),
            )
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {}", e))?
            .json::<Vec<DiscordMessage>>()
            .await
            .map_err(|e| format!("Failed to parse response: {}", e))?;

        let timestamp = chrono::Utc::now().timestamp();

        let messages: Vec<IMMessage> = response
            .into_iter()
            .map(|msg| {
                let _is_bot = msg.author.bot.unwrap_or(false);
                IMMessage {
                    id: msg.id,
                    platform: "discord".to_string(),
                    channel_id: conversation_id.to_string(),
                    user_id: msg.author.id,
                    user_name: msg.author.username,
                    content: msg.content,
                    timestamp,
                    is_mention: false,
                }
            })
            .collect();

        Ok(messages)
    }

    fn set_event_callback(&self, callback: Option<EventCallback>) {
        *self.event_callback.lock().unwrap() = callback;
    }
}
