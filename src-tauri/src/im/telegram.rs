use super::gateway::{Gateway, GatewayStatus, GatewayEvent, EventCallback, IMMessage, MessageDeduplicationCache};
use serde::{Deserialize, Serialize};
use std::sync::{Arc, Mutex};
use chrono::Local;
use reqwest::Client;
use std::time::Duration;
use async_trait::async_trait;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TelegramConfig {
    pub enabled: bool,
    pub bot_token: String,
    pub debug: Option<bool>,
    pub media_download_path: Option<String>,
}

impl Default for TelegramConfig {
    fn default() -> Self {
        TelegramConfig {
            enabled: false,
            bot_token: String::new(),
            debug: Some(false),
            media_download_path: None,
        }
    }
}

#[derive(Debug, Deserialize)]
struct TelegramMeResponse {
    ok: bool,
    result: Option<TelegramUser>,
}

#[derive(Debug, Deserialize)]
struct TelegramUser {
    id: i64,
    is_bot: bool,
    first_name: Option<String>,
    last_name: Option<String>,
    username: Option<String>,
}

#[derive(Debug, Deserialize)]
struct TelegramMessage {
    message_id: i64,
    chat: TelegramChat,
    from: Option<TelegramFrom>,
    text: Option<String>,
    caption: Option<String>,
    date: i64,
    photo: Option<Vec<TelegramPhoto>>,
    video: Option<TelegramVideo>,
    audio: Option<TelegramAudio>,
    voice: Option<TelegramVoice>,
    document: Option<TelegramDocument>,
    sticker: Option<TelegramSticker>,
    reply_to_message: Option<Box<TelegramMessage>>,
    #[serde(rename = "media_group_id")]
    media_group_id: Option<String>,
}

#[derive(Debug, Deserialize)]
struct TelegramPhoto {
    file_id: String,
    file_unique_id: String,
    width: Option<i32>,
    height: Option<i32>,
    file_size: Option<i32>,
}

#[derive(Debug, Deserialize)]
struct TelegramVideo {
    file_id: String,
    file_unique_id: String,
    width: Option<i32>,
    height: Option<i32>,
    duration: Option<i32>,
    mime_type: Option<String>,
    file_size: Option<i32>,
}

#[derive(Debug, Deserialize)]
struct TelegramAudio {
    file_id: String,
    file_unique_id: String,
    duration: Option<i32>,
    performer: Option<String>,
    title: Option<String>,
    mime_type: Option<String>,
    file_size: Option<i32>,
}

#[derive(Debug, Deserialize)]
struct TelegramVoice {
    file_id: String,
    file_unique_id: String,
    duration: Option<i32>,
    mime_type: Option<String>,
    file_size: Option<i32>,
}

#[derive(Debug, Deserialize)]
struct TelegramDocument {
    file_id: String,
    file_unique_id: String,
    file_name: Option<String>,
    mime_type: Option<String>,
    file_size: Option<i32>,
}

#[derive(Debug, Deserialize)]
struct TelegramSticker {
    file_id: String,
    file_unique_id: String,
    width: Option<i32>,
    height: Option<i32>,
    is_animated: Option<bool>,
    file_size: Option<i32>,
}

#[derive(Debug, Deserialize)]
struct TelegramChat {
    id: i64,
    #[serde(rename = "type")]
    chat_type: String,
}

#[derive(Debug, Deserialize)]
struct TelegramFrom {
    id: i64,
    is_bot: bool,
    first_name: Option<String>,
    last_name: Option<String>,
    username: Option<String>,
}

#[derive(Debug, Deserialize)]
struct TelegramUpdate {
    update_id: i64,
    message: Option<TelegramMessage>,
    edited_message: Option<TelegramMessage>,
}

#[derive(Debug, Deserialize)]
struct TelegramUpdatesResponse {
    ok: bool,
    result: Option<Vec<TelegramUpdate>>,
}

#[derive(Debug, Deserialize)]
struct TelegramFileResponse {
    ok: bool,
    result: Option<TelegramFile>,
}

#[derive(Debug, Deserialize)]
struct TelegramFile {
    file_id: String,
    file_unique_id: String,
    file_size: Option<i32>,
    file_path: Option<String>,
}

#[derive(Debug, Serialize)]
struct SendMessageRequest {
    chat_id: i64,
    text: String,
    #[serde(rename = "parse_mode")]
    parse_mode: Option<String>,
    #[serde(rename = "reply_to_message_id")]
    reply_to_message_id: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TelegramMediaAttachment {
    pub media_type: String,
    pub file_id: String,
    pub file_name: Option<String>,
    pub mime_type: Option<String>,
    pub width: Option<i32>,
    pub height: Option<i32>,
    pub duration: Option<i32>,
    pub file_size: Option<i32>,
    pub local_path: Option<String>,
}

pub struct TelegramGateway {
    config: Arc<Mutex<TelegramConfig>>,
    status: Arc<Mutex<GatewayStatus>>,
    event_callback: Arc<Mutex<Option<EventCallback>>>,
    http_client: Client,
    last_update_id: Arc<Mutex<i64>>,
    polling_task: Arc<Mutex<Option<tokio::task::JoinHandle<()>>>>,
    stop_polling: Arc<Mutex<Option<tokio::sync::oneshot::Sender<()>>>>,
    last_chat_id: Arc<Mutex<Option<i64>>>,
    media_group_buffers: Arc<Mutex<std::collections::HashMap<String, Vec<IMMessage>>>>,
    deduplication_cache: Arc<Mutex<MessageDeduplicationCache>>,
}

impl TelegramGateway {
    pub fn new(config: TelegramConfig) -> Self {
        let http_client = Client::builder()
            .timeout(Duration::from_secs(60))
            .build()
            .expect("Failed to create HTTP client");

        Self {
            config: Arc::new(Mutex::new(config)),
            status: Arc::new(Mutex::new(GatewayStatus::default())),
            event_callback: Arc::new(Mutex::new(None)),
            http_client,
            last_update_id: Arc::new(Mutex::new(0)),
            polling_task: Arc::new(Mutex::new(None)),
            stop_polling: Arc::new(Mutex::new(None)),
            last_chat_id: Arc::new(Mutex::new(None)),
            media_group_buffers: Arc::new(Mutex::new(std::collections::HashMap::new())),
            deduplication_cache: Arc::new(Mutex::new(MessageDeduplicationCache::new())),
        }
    }

    pub fn set_config(&self, config: TelegramConfig) {
        *self.config.lock().unwrap() = config;
    }

    pub fn get_config(&self) -> TelegramConfig {
        self.config.lock().unwrap().clone()
    }

    fn get_api_url(&self, method: &str) -> String {
        let token = self.config.lock().unwrap().bot_token.clone();
        format!("https://api.telegram.org/bot{}/{}", token, method)
    }

    fn emit_event(&self, event: GatewayEvent) {
        if let Some(callback) = &*self.event_callback.lock().unwrap() {
            callback(event);
        }
    }

    fn log(&self, message: &str) {
        if self.config.lock().unwrap().debug.unwrap_or(false) {
            println!("[Telegram Gateway] {}", message);
        }
    }

    async fn get_me(&self) -> Result<TelegramUser, String> {
        let url = self.get_api_url("getMe");
        let response = self.http_client
            .get(&url)
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {}", e))?
            .json::<TelegramMeResponse>()
            .await
            .map_err(|e| format!("Failed to parse response: {}", e))?;

        if response.ok && response.result.is_some() {
            Ok(response.result.unwrap())
        } else {
            Err("Failed to get bot info".to_string())
        }
    }

    async fn get_file(&self, file_id: &str) -> Result<TelegramFile, String> {
        let url = self.get_api_url("getFile");
        let response = self.http_client
            .get(&url)
            .query(&[("file_id", file_id)])
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {}", e))?
            .json::<TelegramFileResponse>()
            .await
            .map_err(|e| format!("Failed to parse response: {}", e))?;

        if response.ok && response.result.is_some() {
            Ok(response.result.unwrap())
        } else {
            Err("Failed to get file".to_string())
        }
    }

    async fn download_file(&self, file_path: &str) -> Result<Vec<u8>, String> {
        let token = self.config.lock().unwrap().bot_token.clone();
        let url = format!("https://api.telegram.org/file/bot{}/{}", token, file_path);
        
        let response = self.http_client
            .get(&url)
            .send()
            .await
            .map_err(|e| format!("Download failed: {}", e))?;
        
        let bytes = response.bytes()
            .await
            .map_err(|e| format!("Failed to read bytes: {}", e))?;
        
        Ok(bytes.to_vec())
    }

    fn extract_media_from_message(&self, message: &TelegramMessage) -> Vec<TelegramMediaAttachment> {
        let mut attachments = Vec::new();

        if let Some(photos) = &message.photo {
            if let Some(photo) = photos.last() {
                attachments.push(TelegramMediaAttachment {
                    media_type: "photo".to_string(),
                    file_id: photo.file_id.clone(),
                    file_name: None,
                    mime_type: Some("image/jpeg".to_string()),
                    width: photo.width,
                    height: photo.height,
                    duration: None,
                    file_size: photo.file_size,
                    local_path: None,
                });
            }
        }

        if let Some(video) = &message.video {
            attachments.push(TelegramMediaAttachment {
                media_type: "video".to_string(),
                file_id: video.file_id.clone(),
                file_name: None,
                mime_type: video.mime_type.clone(),
                width: video.width,
                height: video.height,
                duration: video.duration,
                file_size: video.file_size,
                local_path: None,
            });
        }

        if let Some(audio) = &message.audio {
            attachments.push(TelegramMediaAttachment {
                media_type: "audio".to_string(),
                file_id: audio.file_id.clone(),
                file_name: audio.title.clone(),
                mime_type: audio.mime_type.clone(),
                width: None,
                height: None,
                duration: audio.duration,
                file_size: audio.file_size,
                local_path: None,
            });
        }

        if let Some(voice) = &message.voice {
            attachments.push(TelegramMediaAttachment {
                media_type: "voice".to_string(),
                file_id: voice.file_id.clone(),
                file_name: None,
                mime_type: voice.mime_type.clone(),
                width: None,
                height: None,
                duration: voice.duration,
                file_size: voice.file_size,
                local_path: None,
            });
        }

        if let Some(doc) = &message.document {
            attachments.push(TelegramMediaAttachment {
                media_type: "document".to_string(),
                file_id: doc.file_id.clone(),
                file_name: doc.file_name.clone(),
                mime_type: doc.mime_type.clone(),
                width: None,
                height: None,
                duration: None,
                file_size: doc.file_size,
                local_path: None,
            });
        }

        if let Some(sticker) = &message.sticker {
            attachments.push(TelegramMediaAttachment {
                media_type: "sticker".to_string(),
                file_id: sticker.file_id.clone(),
                file_name: None,
                mime_type: Some("image/webp".to_string()),
                width: sticker.width,
                height: sticker.height,
                duration: None,
                file_size: sticker.file_size,
                local_path: None,
            });
        }

        attachments
    }

    async fn send_text_message(&self, chat_id: i64, text: &str, reply_to_message_id: Option<i64>) -> Result<(), String> {
        self.send_message_with_retry(chat_id, text, reply_to_message_id, 3).await
    }

    async fn send_message_with_retry(&self, chat_id: i64, text: &str, reply_to_message_id: Option<i64>, max_retries: u32) -> Result<(), String> {
        let url = self.get_api_url("sendMessage");
        
        let mut last_error = String::new();
        
        for attempt in 1..=max_retries {
            let request = SendMessageRequest {
                chat_id,
                text: text.to_string(),
                parse_mode: Some("Markdown".to_string()),
                reply_to_message_id,
            };

            let result = self.http_client
                .post(&url)
                .json(&request)
                .send()
                .await;

            match result {
                Ok(response) => {
                    if response.status().is_success() {
                        return Ok(());
                    }
                    last_error = format!("HTTP error: {}", response.status());
                }
                Err(e) => {
                    last_error = e.to_string();
                }
            }
            
            if attempt < max_retries {
                self.log(&format!("Send message failed (attempt {}/{}): {}", attempt, max_retries, last_error));
                tokio::time::sleep(Duration::from_secs(2)).await;
            }
        }
        
        // Try without markdown if failed
        let request = serde_json::json!({
            "chat_id": chat_id,
            "text": text,
            "reply_to_message_id": reply_to_message_id,
        });

        self.http_client
            .post(&url)
            .json(&request)
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {}", e))?;

        Ok(())
    }

    async fn send_media(&self, chat_id: i64, media_type: &str, file_id: &str, caption: Option<&str>, reply_to_message_id: Option<i64>) -> Result<(), String> {
        let method = match media_type {
            "photo" => "sendPhoto",
            "video" => "sendVideo",
            "audio" => "sendAudio",
            "voice" => "sendVoice",
            "document" => "sendDocument",
            "sticker" => "sendSticker",
            _ => return Err(format!("Unknown media type: {}", media_type)),
        };

        let url = self.get_api_url(method);
        
        let mut request = serde_json::json!({
            "chat_id": chat_id,
            media_type: file_id,
        });
        
        if let Some(caption_text) = caption {
            request["caption"] = serde_json::json!(caption_text);
            request["parse_mode"] = serde_json::json!("Markdown");
        }
        
        if let Some(reply_to) = reply_to_message_id {
            request["reply_to_message_id"] = serde_json::json!(reply_to);
        }

        self.http_client
            .post(&url)
            .json(&request)
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {}", e))?;

        Ok(())
    }

    fn split_message(&self, text: &str, max_length: usize) -> Vec<String> {
        let mut chunks = Vec::new();
        let mut remaining = text.to_string();

        while !remaining.is_empty() {
            if remaining.len() <= max_length {
                chunks.push(remaining.clone());
                break;
            }

            // Try to split at newline first
            if let Some(pos) = remaining[..max_length].rfind('\n') {
                chunks.push(remaining[..pos].to_string());
                remaining = remaining[pos+1..].to_string();
            } else if let Some(pos) = remaining[..max_length].rfind(' ') {
                chunks.push(remaining[..pos].to_string());
                remaining = remaining[pos+1..].to_string();
            } else {
                chunks.push(remaining[..max_length].to_string());
                remaining = remaining[max_length..].to_string();
            }
        }

        chunks
    }

    async fn start_polling(&self) {
        let (tx, mut rx) = tokio::sync::oneshot::channel::<()>();
        *self.stop_polling.lock().unwrap() = Some(tx);

        let http_client = self.http_client.clone();
        let config = self.get_config();
        let status = Arc::clone(&self.status);
        let last_update_id = Arc::clone(&self.last_update_id);
        let event_callback = Arc::clone(&self.event_callback);
        let last_chat_id = Arc::clone(&self.last_chat_id);
        let media_group_buffers = Arc::clone(&self.media_group_buffers);
        let deduplication_cache = Arc::clone(&self.deduplication_cache);

        let token = config.bot_token.clone();
        let debug = config.debug.unwrap_or(false);
        
        let get_api_url = move |method: &str| -> String {
            format!("https://api.telegram.org/bot{}/{}", token, method)
        };

        let handle = tokio::spawn(async move {
            let mut offset: i64 = 0;
            
            loop {
                tokio::select! {
                    _ = &mut rx => {
                        if debug { println!("[Telegram Gateway] Polling stopped"); }
                        break;
                    }
                    _ = tokio::time::sleep(Duration::from_secs(1)) => {
                        let url = get_api_url("getUpdates");
                        
                        let result: Result<Vec<TelegramUpdate>, String> = async {
                            let response = http_client
                                .get(&url)
                                .query(&[
                                ("offset", offset.to_string()),
                                ("timeout", "30".to_string()),
                                ("allowed_updates", "message,edited_message".to_string()),
                                ])
                                .send()
                                .await
                                .map_err(|e| format!("HTTP request failed: {}", e))?
                                .json::<TelegramUpdatesResponse>()
                                .await
                                .map_err(|e| format!("Failed to parse response: {}", e))?;

                            if response.ok {
                                Ok(response.result.unwrap_or_default())
                            } else {
                                Err("Failed to get updates".to_string())
                            }
                        }.await;

                        match result {
                            Ok(updates) => {
                                if !updates.is_empty() {
                                    for update in &updates {
                                        offset = update.update_id + 1;
                                    }
                                    
                                    if let Some(callback) = &*event_callback.lock().unwrap() {
                                        for update in updates {
                                            if let Some(message) = update.message {
                                                if message.from.as_ref().map(|f| f.is_bot).unwrap_or(true) {
                                                    continue;
                                                }

                                                let sender_name = message.from.as_ref()
                                                    .map(|f| f.first_name.clone().unwrap_or_default())
                                                    .unwrap_or_else(|| "Unknown".to_string());
                                                
                                                let sender_id = message.from.as_ref()
                                                    .map(|f| f.id.to_string())
                                                    .unwrap_or_else(|| "unknown".to_string());

                                                let content = message.text.clone().or(message.caption.clone()).unwrap_or_default();
                                                
                                                let attachments = Self::extract_media_from_message_static(&message);
                                                
                                                let im_message = IMMessage {
                                                    id: message.message_id.to_string(),
                                                    platform: "telegram".to_string(),
                                                    channel_id: message.chat.id.to_string(),
                                                    user_id: sender_id,
                                                    user_name: sender_name,
                                                    content: content.to_string(),
                                                    timestamp: message.date,
                                                    is_mention: false,
                                                };

                                                // Store last chat ID
                                                *last_chat_id.lock().unwrap() = Some(message.chat.id);

                                                // Check for duplicate messages
                                                let message_id = message.message_id.to_string();
                                                let timestamp = message.date;
                                                let is_duplicate = {
                                                    let mut cache = deduplication_cache.lock().unwrap();
                                                    cache.check_and_mark(&message_id, timestamp, 60)
                                                };
                                                
                                                if is_duplicate {
                                                    if debug { println!("[Telegram Gateway] Skipping duplicate message: {}", message_id); }
                                                    continue;
                                                }

                                                callback(GatewayEvent::Message(im_message));
                                            }
                                        }
                                    }
                                }
                            }
                            Err(e) => {
                                if debug { println!("[Telegram Gateway] Error getting updates: {}", e); }
                            }
                        }
                    }
                }
            }
        });

        *self.polling_task.lock().unwrap() = Some(handle);
    }

    fn extract_media_from_message_static(message: &TelegramMessage) -> Vec<TelegramMediaAttachment> {
        let mut attachments = Vec::new();

        if let Some(photos) = &message.photo {
            if let Some(photo) = photos.last() {
                attachments.push(TelegramMediaAttachment {
                    media_type: "photo".to_string(),
                    file_id: photo.file_id.clone(),
                    file_name: None,
                    mime_type: Some("image/jpeg".to_string()),
                    width: photo.width,
                    height: photo.height,
                    duration: None,
                    file_size: photo.file_size,
                    local_path: None,
                });
            }
        }

        if let Some(video) = &message.video {
            attachments.push(TelegramMediaAttachment {
                media_type: "video".to_string(),
                file_id: video.file_id.clone(),
                file_name: None,
                mime_type: video.mime_type.clone(),
                width: video.width,
                height: video.height,
                duration: video.duration,
                file_size: video.file_size,
                local_path: None,
            });
        }

        if let Some(doc) = &message.document {
            attachments.push(TelegramMediaAttachment {
                media_type: "document".to_string(),
                file_id: doc.file_id.clone(),
                file_name: doc.file_name.clone(),
                mime_type: doc.mime_type.clone(),
                width: None,
                height: None,
                duration: None,
                file_size: doc.file_size,
                local_path: None,
            });
        }

        attachments
    }

    async fn stop_polling(&self) {
        if let Some(tx) = self.stop_polling.lock().unwrap().take() {
            let _ = tx.send(());
        }
        
        let handle_opt = self.polling_task.lock().unwrap().take();
        if let Some(handle) = handle_opt {
            let _ = handle.await;
        }
    }
}

impl Clone for TelegramGateway {
    fn clone(&self) -> Self {
        Self {
            config: Arc::clone(&self.config),
            status: Arc::clone(&self.status),
            event_callback: Arc::clone(&self.event_callback),
            http_client: self.http_client.clone(),
            last_update_id: Arc::clone(&self.last_update_id),
            polling_task: Arc::clone(&self.polling_task),
            stop_polling: Arc::clone(&self.stop_polling),
            last_chat_id: Arc::clone(&self.last_chat_id),
            media_group_buffers: Arc::clone(&self.media_group_buffers),
            deduplication_cache: Arc::clone(&self.deduplication_cache),
        }
    }
}

#[async_trait]
impl Gateway for TelegramGateway {
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

        match self.get_me().await {
            Ok(bot_info) => {
                self.log(&format!("Bot verified: @{}", bot_info.username.unwrap_or_default()));
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

        self.start_polling().await;

        let mut status = self.status.lock().unwrap();
        status.starting = false;
        status.connected = true;
        status.started_at = Some(Local::now().timestamp_millis());
        self.emit_event(GatewayEvent::StatusChanged(status.clone()));
        self.emit_event(GatewayEvent::Connected);

        Ok(())
    }

    async fn stop(&self) -> Result<(), String> {
        self.stop_polling().await;

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

    async fn send_message(&self, conversation_id: &str, text: &str) -> Result<bool, String> {
        if !self.is_connected() {
            return Err("网关未连接".to_string());
        }

        let chat_id: i64 = conversation_id.parse()
            .map_err(|_| "无效的会话ID".to_string())?;

        self.send_text_message(chat_id, text, None).await?;

        let mut status = self.status.lock().unwrap();
        status.last_outbound_at = Some(Local::now().timestamp_millis());

        Ok(true)
    }

    async fn send_media_message(&self, conversation_id: &str, file_path: &str) -> Result<bool, String> {
        if !self.is_connected() {
            return Err("网关未连接".to_string());
        }

        let chat_id: i64 = conversation_id.parse()
            .map_err(|_| "无效的会话ID".to_string())?;

        let path = std::path::Path::new(file_path);
        let extension = path.extension()
            .and_then(|e| e.to_str())
            .unwrap_or("")
            .to_lowercase();

        let (media_type, msg_type) = match extension.as_str() {
            "jpg" | "jpeg" | "png" | "gif" | "bmp" | "webp" => ("photo", "photo"),
            "mp4" | "avi" | "mov" | "webm" => ("video", "video"),
            "mp3" | "ogg" | "wav" | "m4a" | "aac" => ("audio", "audio"),
            _ => ("document", "document"),
        };

        let file_bytes = tokio::fs::read(file_path).await
            .map_err(|e| format!("读取文件失败: {}", e))?;

        let file_name = path.file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("file")
            .to_string();

        self.send_media(chat_id, msg_type, &file_name, Some(&file_name), None).await?;

        let mut status = self.status.lock().unwrap();
        status.last_outbound_at = Some(Local::now().timestamp_millis());

        Ok(true)
    }

    async fn send_notification(&self, text: &str) -> Result<bool, String> {
        if !self.is_connected() {
            return Err("网关未连接".to_string());
        }

        let chat_id = self.last_chat_id.lock().unwrap().ok_or_else(|| "未找到聊天ID".to_string())?;

        let chunks = self.split_message(text, 4000);
        
        for chunk in chunks {
            self.send_text_message(chat_id, &chunk, None).await?;
        }
        
        let mut status = self.status.lock().unwrap();
        status.last_outbound_at = Some(Local::now().timestamp_millis());
        
        Ok(true)
    }

    async fn reconnect_if_needed(&self) -> Result<(), String> {
        // Telegram polling 不需要主动重连，失败会自动重试
        Ok(())
    }

    async fn edit_message(&self, conversation_id: &str, message_id: &str, new_text: &str) -> Result<bool, String> {
        if !self.is_connected() {
            return Err("网关未连接".to_string());
        }

        let chat_id: i64 = conversation_id.parse()
            .map_err(|_| "无效的会话ID".to_string())?;

        let message_id: i64 = message_id.parse()
            .map_err(|_| "无效的消息ID".to_string())?;

        let url = self.get_api_url("editMessageText");
        
        let request = serde_json::json!({
            "chat_id": chat_id,
            "message_id": message_id,
            "text": new_text,
        });

        let response = self.http_client
            .post(&url)
            .json(&request)
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {}", e))?;

        if !response.status().is_success() {
            return Err(format!("Failed to edit message: {}", response.status()));
        }

        let mut status = self.status.lock().unwrap();
        status.last_outbound_at = Some(Local::now().timestamp_millis());

        Ok(true)
    }

    async fn delete_message(&self, conversation_id: &str, message_id: &str) -> Result<bool, String> {
        if !self.is_connected() {
            return Err("网关未连接".to_string());
        }

        let chat_id: i64 = conversation_id.parse()
            .map_err(|_| "无效的会话ID".to_string())?;

        let message_id: i64 = message_id.parse()
            .map_err(|_| "无效的消息ID".to_string())?;

        let url = self.get_api_url("deleteMessage");
        
        let request = serde_json::json!({
            "chat_id": chat_id,
            "message_id": message_id,
        });

        let response = self.http_client
            .post(&url)
            .json(&request)
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {}", e))?;

        if !response.status().is_success() {
            return Err(format!("Failed to delete message: {}", response.status()));
        }

        Ok(true)
    }

    async fn get_message_history(&self, conversation_id: &str, limit: u32) -> Result<Vec<IMMessage>, String> {
        if !self.is_connected() {
            return Err("网关未连接".to_string());
        }

        let chat_id: i64 = conversation_id.parse()
            .map_err(|_| "无效的会话ID".to_string())?;

        let url = self.get_api_url("getChatHistory");
        
        let request = serde_json::json!({
            "chat_id": chat_id,
            "limit": limit,
        });

        #[derive(Deserialize)]
        struct GetHistoryResponse {
            ok: bool,
            result: Option<Vec<TelegramMessage>>,
        }

        let response = self.http_client
            .post(&url)
            .json(&request)
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {}", e))?
            .json::<GetHistoryResponse>()
            .await
            .map_err(|e| format!("Failed to parse response: {}", e))?;

        if !response.ok || response.result.is_none() {
            return Err("Failed to get message history".to_string());
        }

        let messages: Vec<IMMessage> = response.result.unwrap()
            .into_iter()
            .map(|msg| {
                let content = msg.text.clone().unwrap_or_else(|| {
                    if let Some(photo) = &msg.photo {
                        photo.last().map(|p| p.file_id.clone()).unwrap_or_else(|| "[Photo]".to_string())
                    } else if let Some(video) = &msg.video {
                        video.file_id.clone()
                    } else if let Some(document) = &msg.document {
                        document.file_id.clone()
                    } else if let Some(audio) = &msg.audio {
                        audio.file_id.clone()
                    } else {
                        "[Media]".to_string()
                    }
                });

                IMMessage {
                    id: msg.message_id.to_string(),
                    platform: "telegram".to_string(),
                    channel_id: chat_id.to_string(),
                    user_id: msg.from.as_ref().map(|u| u.id.to_string()).unwrap_or_default(),
                    user_name: msg.from.as_ref().and_then(|u| u.first_name.as_ref()).map(|s| s.clone()).unwrap_or_else(|| "Unknown".to_string()),
                    content,
                    timestamp: msg.date,
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
