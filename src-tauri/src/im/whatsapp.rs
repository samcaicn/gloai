use super::gateway::{EventCallback, Gateway, GatewayEvent, GatewayStatus, IMMessage};

use chrono::Local;
use reqwest::Client;
use serde::{Deserialize, Serialize};
use std::path::Path;
use std::sync::Mutex;
use std::time::Duration;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WhatsAppConfig {
    pub enabled: bool,
    pub phone_number_id: Option<String>,
    pub access_token: Option<String>,
    pub debug: Option<bool>,
    pub media_download_path: Option<String>,
}

impl Default for WhatsAppConfig {
    fn default() -> Self {
        WhatsAppConfig {
            enabled: false,
            phone_number_id: None,
            access_token: None,
            debug: Some(false),
            media_download_path: None,
        }
    }
}

#[derive(Debug, Deserialize)]
struct WhatsAppMediaUploadResponse {
    id: String,
}

#[derive(Debug, Deserialize)]
struct WhatsAppMediaInfo {
    id: String,
    mime_type: String,
    file_size: Option<i64>,
    url: Option<String>,
}

#[derive(Debug, Deserialize)]
struct WhatsAppUser {
    id: String,
    name: Option<String>,
}

#[allow(dead_code)]
#[derive(Debug, Deserialize)]
struct WhatsAppMessage {
    id: String,
    from: String,
    timestamp: String,
    #[serde(rename = "type")]
    message_type: String,
    text: Option<WhatsAppTextMessage>,
    image: Option<WhatsAppMediaMessage>,
    video: Option<WhatsAppMediaMessage>,
    audio: Option<WhatsAppMediaMessage>,
    document: Option<WhatsAppMediaMessage>,
}

#[allow(dead_code)]
#[derive(Debug, Deserialize)]
struct WhatsAppTextMessage {
    body: String,
}

#[allow(dead_code)]
#[derive(Debug, Deserialize)]
struct WhatsAppMediaMessage {
    id: Option<String>,
    mime_type: Option<String>,
    caption: Option<String>,
    filename: Option<String>,
}

#[allow(dead_code)]
#[derive(Debug, Deserialize)]
struct WhatsAppWebhookEntry {
    id: String,
    changes: Vec<WhatsAppWebhookChange>,
}

#[allow(dead_code)]
#[derive(Debug, Deserialize)]
struct WhatsAppWebhookChange {
    value: WhatsAppWebhookValue,
    field: String,
}

#[allow(dead_code)]
#[derive(Debug, Deserialize)]
struct WhatsAppWebhookValue {
    messaging_product: String,
    metadata: WhatsAppMetadata,
    messages: Option<Vec<WhatsAppMessage>>,
}

#[allow(dead_code)]
#[derive(Debug, Deserialize)]
struct WhatsAppMetadata {
    display_phone_number: String,
    phone_number_id: String,
}

#[allow(dead_code)]
pub struct WhatsAppGateway {
    config: Mutex<WhatsAppConfig>,
    status: Mutex<GatewayStatus>,
    event_callback: Mutex<Option<EventCallback>>,
    http_client: Client,
    phone_number_id: Mutex<Option<String>>,
    last_chat_id: Mutex<Option<String>>,
}

#[allow(dead_code)]
impl WhatsAppGateway {
    pub fn new(config: WhatsAppConfig) -> Self {
        let http_client = Client::builder()
            .timeout(Duration::from_secs(30))
            .build()
            .expect("Failed to create HTTP client");

        Self {
            config: Mutex::new(config),
            status: Mutex::new(GatewayStatus::default()),
            event_callback: Mutex::new(None),
            http_client,
            phone_number_id: Mutex::new(None),
            last_chat_id: Mutex::new(None),
        }
    }

    pub fn set_config(&self, config: WhatsAppConfig) {
        *self.config.lock().unwrap() = config;
    }

    pub fn get_config(&self) -> WhatsAppConfig {
        self.config.lock().unwrap().clone()
    }

    fn get_api_url(&self, endpoint: &str) -> String {
        let phone_number_id = self
            .config
            .lock()
            .unwrap()
            .phone_number_id
            .clone()
            .unwrap_or_default();
        format!(
            "https://graph.facebook.com/v18.0/{}{}",
            phone_number_id, endpoint
        )
    }

    fn get_headers(&self) -> reqwest::header::HeaderMap {
        let token = self
            .config
            .lock()
            .unwrap()
            .access_token
            .clone()
            .unwrap_or_default();
        let mut headers = reqwest::header::HeaderMap::new();
        headers.insert(
            reqwest::header::AUTHORIZATION,
            format!("Bearer {}", token).parse().unwrap(),
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
            println!("[WhatsApp Gateway] {}", message);
        }
    }

    async fn verify_credentials(&self) -> Result<WhatsAppUser, String> {
        let url = format!("https://graph.facebook.com/v18.0/me");

        let response = self
            .http_client
            .get(&url)
            .headers(self.get_headers())
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {}", e))?;

        if response.status().is_success() {
            response
                .json::<WhatsAppUser>()
                .await
                .map_err(|e| format!("Failed to parse response: {}", e))
        } else {
            Err(format!(
                "Failed to verify credentials: {}",
                response.status()
            ))
        }
    }

    async fn send_message(&self, to: &str, text: &str) -> Result<(), String> {
        let url = self.get_api_url("/messages");

        #[derive(Serialize)]
        struct SendMessageRequest {
            messaging_product: String,
            to: String,
            #[serde(rename = "type")]
            message_type: String,
            text: serde_json::Value,
        }

        let request = SendMessageRequest {
            messaging_product: "whatsapp".to_string(),
            to: to.to_string(),
            message_type: "text".to_string(),
            text: serde_json::json!({ "body": text }),
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

    async fn upload_media(&self, file_path: &str) -> Result<String, String> {
        let file_bytes = tokio::fs::read(file_path)
            .await
            .map_err(|e| format!("Failed to read file: {}", e))?;

        let file_name = Path::new(file_path)
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("file")
            .to_string();

        let mime_type = match Path::new(file_path)
            .extension()
            .and_then(|e| e.to_str())
            .unwrap_or("")
            .to_lowercase()
            .as_str()
        {
            "jpg" | "jpeg" => "image/jpeg",
            "png" => "image/png",
            "gif" => "image/gif",
            "webp" => "image/webp",
            "mp4" => "video/mp4",
            "mp3" => "audio/mpeg",
            "ogg" => "audio/ogg",
            "pdf" => "application/pdf",
            "doc" => "application/msword",
            "docx" => "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            _ => "application/octet-stream",
        };

        let url = "https://graph.facebook.com/v18.0/me/media";

        let part = reqwest::multipart::Part::bytes(file_bytes)
            .file_name(file_name)
            .mime_str(mime_type)
            .map_err(|e| format!("Invalid mime type: {}", e))?;

        let form = reqwest::multipart::Form::new()
            .text("messaging_product", "whatsapp")
            .part("file", part);

        let response = self
            .http_client
            .post(url)
            .headers(self.get_headers())
            .multipart(form)
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {}", e))?
            .json::<WhatsAppMediaUploadResponse>()
            .await
            .map_err(|e| format!("Failed to parse response: {}", e))?;

        Ok(response.id)
    }

    async fn get_media_info(&self, media_id: &str) -> Result<WhatsAppMediaInfo, String> {
        let url = format!("https://graph.facebook.com/v18.0/{}", media_id);

        let response = self
            .http_client
            .get(&url)
            .headers(self.get_headers())
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {}", e))?
            .json::<WhatsAppMediaInfo>()
            .await
            .map_err(|e| format!("Failed to parse response: {}", e))?;

        Ok(response)
    }

    async fn download_media(&self, media_id: &str, save_path: &str) -> Result<String, String> {
        let media_info = self.get_media_info(media_id).await?;

        if let Some(url) = media_info.url {
            let response = self
                .http_client
                .get(&url)
                .send()
                .await
                .map_err(|e| format!("HTTP request failed: {}", e))?;

            let bytes = response
                .bytes()
                .await
                .map_err(|e| format!("Failed to read response bytes: {}", e))?;

            tokio::fs::write(save_path, &bytes)
                .await
                .map_err(|e| format!("Failed to write file: {}", e))?;

            Ok(save_path.to_string())
        } else {
            Err("Media URL not available".to_string())
        }
    }

    async fn send_image_message(
        &self,
        to: &str,
        media_id: &str,
        caption: Option<&str>,
    ) -> Result<(), String> {
        let url = self.get_api_url("/messages");

        #[derive(Serialize)]
        struct SendImageRequest {
            messaging_product: String,
            to: String,
            #[serde(rename = "type")]
            message_type: String,
            image: serde_json::Value,
        }

        let mut image_obj = serde_json::json!({ "id": media_id });
        if let Some(c) = caption {
            image_obj["caption"] = serde_json::json!(c);
        }

        let request = SendImageRequest {
            messaging_product: "whatsapp".to_string(),
            to: to.to_string(),
            message_type: "image".to_string(),
            image: image_obj,
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

    async fn send_video_message(
        &self,
        to: &str,
        media_id: &str,
        caption: Option<&str>,
    ) -> Result<(), String> {
        let url = self.get_api_url("/messages");

        #[derive(Serialize)]
        struct SendVideoRequest {
            messaging_product: String,
            to: String,
            #[serde(rename = "type")]
            message_type: String,
            video: serde_json::Value,
        }

        let mut video_obj = serde_json::json!({ "id": media_id });
        if let Some(c) = caption {
            video_obj["caption"] = serde_json::json!(c);
        }

        let request = SendVideoRequest {
            messaging_product: "whatsapp".to_string(),
            to: to.to_string(),
            message_type: "video".to_string(),
            video: video_obj,
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

    async fn send_audio_message(&self, to: &str, media_id: &str) -> Result<(), String> {
        let url = self.get_api_url("/messages");

        #[derive(Serialize)]
        struct SendAudioRequest {
            messaging_product: String,
            to: String,
            #[serde(rename = "type")]
            message_type: String,
            audio: serde_json::Value,
        }

        let request = SendAudioRequest {
            messaging_product: "whatsapp".to_string(),
            to: to.to_string(),
            message_type: "audio".to_string(),
            audio: serde_json::json!({ "id": media_id }),
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

    async fn send_document_message(
        &self,
        to: &str,
        media_id: &str,
        caption: Option<&str>,
        filename: Option<&str>,
    ) -> Result<(), String> {
        let url = self.get_api_url("/messages");

        #[derive(Serialize)]
        struct SendDocumentRequest {
            messaging_product: String,
            to: String,
            #[serde(rename = "type")]
            message_type: String,
            document: serde_json::Value,
        }

        let mut doc_obj = serde_json::json!({ "id": media_id });
        if let Some(c) = caption {
            doc_obj["caption"] = serde_json::json!(c);
        }
        if let Some(f) = filename {
            doc_obj["filename"] = serde_json::json!(f);
        }

        let request = SendDocumentRequest {
            messaging_product: "whatsapp".to_string(),
            to: to.to_string(),
            message_type: "document".to_string(),
            document: doc_obj,
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

    async fn send_sticker_message(&self, to: &str, media_id: &str) -> Result<(), String> {
        let url = self.get_api_url("/messages");

        #[derive(Serialize)]
        struct SendStickerRequest {
            messaging_product: String,
            to: String,
            #[serde(rename = "type")]
            message_type: String,
            sticker: serde_json::Value,
        }

        let request = SendStickerRequest {
            messaging_product: "whatsapp".to_string(),
            to: to.to_string(),
            message_type: "sticker".to_string(),
            sticker: serde_json::json!({ "id": media_id }),
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

    pub async fn send_media_message(
        &self,
        to: &str,
        file_path: &str,
        caption: Option<&str>,
    ) -> Result<bool, String> {
        let path = Path::new(file_path);
        let extension = path
            .extension()
            .and_then(|e| e.to_str())
            .unwrap_or("")
            .to_lowercase();

        let media_id = self.upload_media(file_path).await?;

        match extension.as_str() {
            "jpg" | "jpeg" | "png" | "gif" | "webp" => {
                self.send_image_message(to, &media_id, caption).await?;
            }
            "mp4" | "avi" | "mov" => {
                self.send_video_message(to, &media_id, caption).await?;
            }
            "mp3" | "wav" | "ogg" | "aac" => {
                self.send_audio_message(to, &media_id).await?;
            }
            "pdf" | "doc" | "docx" | "xls" | "xlsx" | "ppt" | "pptx" | "txt" | "zip" | "rar" => {
                let filename = path
                    .file_name()
                    .and_then(|n| n.to_str())
                    .map(|s| s.to_string());
                self.send_document_message(to, &media_id, caption, filename.as_deref())
                    .await?;
            }
            _ => return Err("不支持的文件类型".to_string()),
        }

        Ok(true)
    }

    pub async fn send_interactive_list_message(
        &self,
        to: &str,
        title: &str,
        description: &str,
        button_text: &str,
    ) -> Result<bool, String> {
        let url = self.get_api_url("/messages");

        #[derive(Serialize)]
        struct SendInteractiveRequest {
            messaging_product: String,
            to: String,
            #[serde(rename = "type")]
            message_type: String,
            interactive: serde_json::Value,
        }

        let request = SendInteractiveRequest {
            messaging_product: "whatsapp".to_string(),
            to: to.to_string(),
            message_type: "interactive".to_string(),
            interactive: serde_json::json!({
                "type": "list",
                "header": {
                    "type": "text",
                    "text": title
                },
                "body": {
                    "text": description
                },
                "action": {
                    "button": button_text,
                    "sections": []
                }
            }),
        };

        self.http_client
            .post(&url)
            .headers(self.get_headers())
            .json(&request)
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {}", e))?;

        Ok(true)
    }

    pub async fn send_location_message(
        &self,
        to: &str,
        latitude: f64,
        longitude: f64,
        title: Option<&str>,
    ) -> Result<bool, String> {
        let url = self.get_api_url("/messages");

        #[derive(Serialize)]
        struct SendLocationRequest {
            messaging_product: String,
            to: String,
            #[serde(rename = "type")]
            message_type: String,
            location: serde_json::Value,
        }

        let mut location_obj = serde_json::json!({
            "latitude": latitude,
            "longitude": longitude
        });

        if let Some(t) = title {
            location_obj["name"] = serde_json::json!(t);
        }

        let request = SendLocationRequest {
            messaging_product: "whatsapp".to_string(),
            to: to.to_string(),
            message_type: "location".to_string(),
            location: location_obj,
        };

        self.http_client
            .post(&url)
            .headers(self.get_headers())
            .json(&request)
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {}", e))?;

        Ok(true)
    }

    pub async fn send_template_message(
        &self,
        to: &str,
        template_name: &str,
        language_code: &str,
        components: Option<Vec<TemplateComponent>>,
    ) -> Result<bool, String> {
        let url = self.get_api_url("/messages");

        #[derive(Serialize)]
        struct SendTemplateRequest {
            messaging_product: String,
            to: String,
            #[serde(rename = "type")]
            message_type: String,
            template: serde_json::Value,
        }

        let mut template_obj = serde_json::json!({
            "name": template_name,
            "language": {
                "code": language_code
            }
        });

        if let Some(components) = components {
            template_obj["components"] = serde_json::json!(components);
        }

        let request = SendTemplateRequest {
            messaging_product: "whatsapp".to_string(),
            to: to.to_string(),
            message_type: "template".to_string(),
            template: template_obj,
        };

        self.http_client
            .post(&url)
            .headers(self.get_headers())
            .json(&request)
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {}", e))?;

        let mut status = self.status.lock().unwrap();
        status.last_outbound_at = Some(Local::now().timestamp_millis());

        Ok(true)
    }

    pub async fn send_interactive_buttons_message(
        &self,
        to: &str,
        header_text: Option<&str>,
        body_text: &str,
        footer_text: Option<&str>,
        buttons: Vec<InteractiveButton>,
    ) -> Result<bool, String> {
        let url = self.get_api_url("/messages");

        #[derive(Serialize)]
        struct SendInteractiveButtonsRequest {
            messaging_product: String,
            to: String,
            #[serde(rename = "type")]
            message_type: String,
            interactive: serde_json::Value,
        }

        let mut interactive_obj = serde_json::json!({
            "type": "button",
            "body": {
                "text": body_text
            },
            "action": {
                "buttons": buttons
            }
        });

        if let Some(header) = header_text {
            interactive_obj["header"] = serde_json::json!({
                "type": "text",
                "text": header
            });
        }

        if let Some(footer) = footer_text {
            interactive_obj["footer"] = serde_json::json!({
                "text": footer
            });
        }

        let request = SendInteractiveButtonsRequest {
            messaging_product: "whatsapp".to_string(),
            to: to.to_string(),
            message_type: "interactive".to_string(),
            interactive: interactive_obj,
        };

        self.http_client
            .post(&url)
            .headers(self.get_headers())
            .json(&request)
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {}", e))?;

        Ok(true)
    }

    pub async fn send_interactive_catalog_message(
        &self,
        to: &str,
        body_text: &str,
        footer_text: Option<&str>,
        catalog_id: &str,
        product_id: Option<&str>,
    ) -> Result<bool, String> {
        let url = self.get_api_url("/messages");

        #[derive(Serialize)]
        struct SendInteractiveCatalogRequest {
            messaging_product: String,
            to: String,
            #[serde(rename = "type")]
            message_type: String,
            interactive: serde_json::Value,
        }

        let mut interactive_obj = serde_json::json!({
            "type": "catalog_message",
            "body": {
                "text": body_text
            },
            "action": {
                "catalog_id": catalog_id
            }
        });

        if let Some(pid) = product_id {
            interactive_obj["action"]["product_id"] = serde_json::json!(pid);
        }

        if let Some(footer) = footer_text {
            interactive_obj["footer"] = serde_json::json!({
                "text": footer
            });
        }

        let request = SendInteractiveCatalogRequest {
            messaging_product: "whatsapp".to_string(),
            to: to.to_string(),
            message_type: "interactive".to_string(),
            interactive: interactive_obj,
        };

        self.http_client
            .post(&url)
            .headers(self.get_headers())
            .json(&request)
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {}", e))?;

        Ok(true)
    }
}

#[allow(dead_code)]
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TemplateComponent {
    #[serde(rename = "type")]
    pub component_type: String,
    pub sub_type: Option<String>,
    pub index: Option<String>,
    pub parameters: Vec<TemplateParameter>,
}

#[allow(dead_code)]
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TemplateParameter {
    #[serde(rename = "type")]
    pub parameter_type: String,
    pub text: Option<String>,
    pub image: Option<serde_json::Value>,
    pub currency: Option<serde_json::Value>,
    pub date_time: Option<serde_json::Value>,
}

#[allow(dead_code)]
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InteractiveButton {
    #[serde(rename = "type")]
    pub button_type: String,
    pub id: String,
    pub title: String,
}

impl Clone for WhatsAppGateway {
    fn clone(&self) -> Self {
        Self {
            config: Mutex::new(self.get_config()),
            status: Mutex::new(GatewayStatus::default()),
            event_callback: Mutex::new(None),
            http_client: Client::builder()
                .timeout(Duration::from_secs(30))
                .build()
                .expect("Failed to create HTTP client"),
            phone_number_id: Mutex::new(None),
            last_chat_id: Mutex::new(None),
        }
    }
}

#[async_trait::async_trait]
impl Gateway for WhatsAppGateway {
    fn as_any(&self) -> &dyn std::any::Any {
        self
    }

    async fn start(&self) -> Result<(), String> {
        let (config_enabled, phone_number_id, access_token) = {
            let config = self.config.lock().unwrap();
            (
                config.enabled,
                config.phone_number_id.clone(),
                config.access_token.clone(),
            )
        };

        if !config_enabled {
            return Ok(());
        }

        if phone_number_id.is_none()
            || phone_number_id
                .as_ref()
                .map(|s| s.is_empty())
                .unwrap_or(true)
        {
            let mut status = self.status.lock().unwrap();
            status.error = Some("缺少必要的配置: phone_number_id".to_string());
            status.last_error = status.error.clone();
            let error_msg = status.error.clone().unwrap();
            self.emit_event(GatewayEvent::Error(error_msg.clone()));
            self.emit_event(GatewayEvent::StatusChanged(status.clone()));
            return Err(error_msg);
        }

        if access_token.is_none() || access_token.as_ref().map(|s| s.is_empty()).unwrap_or(true) {
            let mut status = self.status.lock().unwrap();
            status.error = Some("缺少必要的配置: access_token".to_string());
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

        // Verify credentials
        match self.verify_credentials().await {
            Ok(user) => {
                self.log(&format!(
                    "WhatsApp API verified for: {}",
                    user.name.unwrap_or_default()
                ));
            }
            Err(e) => {
                let mut status = self.status.lock().unwrap();
                status.starting = false;
                status.error = Some(format!("验证凭据失败: {}", e));
                status.last_error = status.error.clone();
                self.emit_event(GatewayEvent::Error(status.error.clone().unwrap()));
                self.emit_event(GatewayEvent::StatusChanged(status.clone()));
                return Err(e);
            }
        }

        // Store phone number ID
        if let Some(phone_id) = phone_number_id {
            *self.phone_number_id.lock().unwrap() = Some(phone_id);
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

        let chat_id = self.last_chat_id.lock().unwrap().clone();

        if let Some(chat_id) = chat_id {
            self.send_message(&chat_id, text).await?;
            Ok(true)
        } else {
            Err("需要指定收件人".to_string())
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

        self.send_media_message(conversation_id, file_path, None)
            .await?;

        let mut status = self.status.lock().unwrap();
        status.last_outbound_at = Some(Local::now().timestamp_millis());

        Ok(true)
    }

    async fn reconnect_if_needed(&self) -> Result<(), String> {
        if !self.is_connected() {
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
        Err("WhatsApp 不支持编辑消息".to_string())
    }

    async fn delete_message(
        &self,
        _conversation_id: &str,
        _message_id: &str,
    ) -> Result<bool, String> {
        Err("WhatsApp 不支持删除消息".to_string())
    }

    async fn get_message_history(
        &self,
        conversation_id: &str,
        limit: u32,
    ) -> Result<Vec<IMMessage>, String> {
        if !self.is_connected() {
            return Err("网关未连接".to_string());
        }

        // WhatsApp Webhook 不存储消息，需要通过其他方式获取
        // 这里返回空列表，因为 WhatsApp API 不支持获取历史消息
        let _ = conversation_id;
        let _ = limit;

        Ok(vec![])
    }

    fn set_event_callback(&self, callback: Option<EventCallback>) {
        *self.event_callback.lock().unwrap() = callback;
    }
}
