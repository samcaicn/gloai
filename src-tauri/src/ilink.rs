//! iLink（微信智联 Bot / ClawBot 协议）Rust 客户端。
//!
//! 逐行移植自开源项目 openilink/openilink-sdk-go（MIT），协议要点：
//!   * 生产端点: POST https://ilinkai.weixin.qq.com/ilink/bot/<endpoint>
//!   * 鉴权:     Authorization: Bearer <bot_token>（扫码登录后下发）
//!   * 收消息:   ilink/bot/getupdates 长轮询（get_updates_buf 游标，可断点续传）
//!   * 发消息:   ilink/bot/sendmessage（文本/图片/语音/文件/视频，单 item 一条）
//!   * 媒体:     ilink/bot/getuploadurl 取 CDN 预签名 → AES-128-ECB 加密上传
//!               → 响应头 x-encrypted-param 作为下载凭据；下载后同密钥解密
//!   * 登录:     GET /ilink/bot/get_bot_qrcode?bot_type=3 → 轮询 get_qrcode_status
//!               （wait / scaned / scaned_but_redirect / expired / confirmed）
//!   * 会话过期: 响应 ret==−14 或 errcode==−14（24h 窗口需对方互动续期）

use base64::Engine as _;
use cipher::block_padding::Pkcs7;
use cipher::{BlockDecryptMut, BlockEncryptMut, KeyInit};
use ecb::{Decryptor, Encryptor};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

pub const DEFAULT_BASE_URL: &str = "https://ilinkai.weixin.qq.com";
pub const DEFAULT_CDN_BASE_URL: &str = "https://novac2c.cdn.weixin.qq.com/c2c";
pub const DEFAULT_BOT_TYPE: &str = "3";
const ILINK_APP_ID: &str = "bot";
const ILINK_CHANNEL_VERSION: &str = "2.1.6";
const API_TIMEOUT: Duration = Duration::from_secs(15);
const LONG_POLL_TIMEOUT: Duration = Duration::from_secs(35);
const QR_POLL_TIMEOUT: Duration = Duration::from_secs(35);
const CDN_TIMEOUT: Duration = Duration::from_secs(60);

// ---------------------------------------------------------------------------
// 协议类型（字段名严格对齐 Go 结构体的 json tag）
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct BaseInfo {
    #[serde(rename = "channel_version", skip_serializing_if = "String::is_empty", default)]
    pub channel_version: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct CdnMedia {
    #[serde(rename = "encrypt_query_param", skip_serializing_if = "String::is_empty", default)]
    pub encrypt_query_param: String,
    #[serde(rename = "aes_key", skip_serializing_if = "String::is_empty", default)]
    pub aes_key: String,
    #[serde(rename = "encrypt_type", skip_serializing_if = "is_zero", default)]
    pub encrypt_type: i64,
    /// 服务端直接下发的完整下载地址；有值时优先于 encrypt_query_param 拼接
    #[serde(rename = "full_url", skip_serializing_if = "String::is_empty", default)]
    pub full_url: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct TextItem {
    #[serde(default)]
    pub text: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ImageItem {
    #[serde(rename = "media", default)]
    pub media: Option<CdnMedia>,
    #[serde(rename = "thumb_media", default)]
    pub thumb_media: Option<CdnMedia>,
    #[serde(rename = "aeskey", skip_serializing_if = "String::is_empty", default)]
    pub aes_key: String,
    #[serde(rename = "url", skip_serializing_if = "String::is_empty", default)]
    pub url: String,
    #[serde(rename = "mid_size", skip_serializing_if = "is_zero", default)]
    pub mid_size: i64,
    #[serde(rename = "thumb_size", skip_serializing_if = "is_zero", default)]
    pub thumb_size: i64,
    #[serde(rename = "thumb_height", skip_serializing_if = "is_zero", default)]
    pub thumb_height: i64,
    #[serde(rename = "thumb_width", skip_serializing_if = "is_zero", default)]
    pub thumb_width: i64,
    #[serde(rename = "hd_size", skip_serializing_if = "is_zero", default)]
    pub hd_size: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct VoiceItem {
    #[serde(rename = "media", default)]
    pub media: Option<CdnMedia>,
    #[serde(rename = "encode_type", skip_serializing_if = "is_zero", default)]
    pub encode_type: i64,
    #[serde(rename = "bits_per_sample", skip_serializing_if = "is_zero", default)]
    pub bits_per_sample: i64,
    #[serde(rename = "sample_rate", skip_serializing_if = "is_zero", default)]
    pub sample_rate: i64,
    #[serde(rename = "playtime", skip_serializing_if = "is_zero", default)]
    pub playtime: i64,
    /// 语音转文字结果（服务端下发）
    #[serde(default)]
    pub text: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct FileItem {
    #[serde(rename = "media", default)]
    pub media: Option<CdnMedia>,
    #[serde(rename = "file_name", skip_serializing_if = "String::is_empty", default)]
    pub file_name: String,
    #[serde(rename = "md5", skip_serializing_if = "String::is_empty", default)]
    pub md5: String,
    #[serde(rename = "len", skip_serializing_if = "String::is_empty", default)]
    pub len: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct VideoItem {
    #[serde(rename = "media", default)]
    pub media: Option<CdnMedia>,
    #[serde(rename = "video_size", skip_serializing_if = "is_zero", default)]
    pub video_size: i64,
    #[serde(rename = "play_length", skip_serializing_if = "is_zero", default)]
    pub play_length: i64,
    #[serde(rename = "video_md5", skip_serializing_if = "String::is_empty", default)]
    pub video_md5: String,
    #[serde(rename = "thumb_media", default)]
    pub thumb_media: Option<CdnMedia>,
    #[serde(rename = "thumb_size", skip_serializing_if = "is_zero", default)]
    pub thumb_size: i64,
    #[serde(rename = "thumb_height", skip_serializing_if = "is_zero", default)]
    pub thumb_height: i64,
    #[serde(rename = "thumb_width", skip_serializing_if = "is_zero", default)]
    pub thumb_width: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct RefMessage {
    #[serde(rename = "message_item", default)]
    pub message_item: Option<Box<MessageItem>>,
    #[serde(default)]
    pub title: String,
}

/// item_type: 1=文本 2=图片 3=语音 4=文件 5=视频
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct MessageItem {
    #[serde(rename = "type", skip_serializing_if = "is_zero", default)]
    pub item_type: i64,
    #[serde(rename = "create_time_ms", skip_serializing_if = "is_zero", default)]
    pub create_time_ms: i64,
    #[serde(rename = "update_time_ms", skip_serializing_if = "is_zero", default)]
    pub update_time_ms: i64,
    #[serde(rename = "is_completed", skip_serializing_if = "std::ops::Not::not", default)]
    pub is_completed: bool,
    #[serde(rename = "msg_id", skip_serializing_if = "String::is_empty", default)]
    pub msg_id: String,
    #[serde(rename = "ref_msg", default)]
    pub ref_msg: Option<RefMessage>,
    #[serde(rename = "text_item", default)]
    pub text_item: Option<TextItem>,
    #[serde(rename = "image_item", default)]
    pub image_item: Option<ImageItem>,
    #[serde(rename = "voice_item", default)]
    pub voice_item: Option<VoiceItem>,
    #[serde(rename = "file_item", default)]
    pub file_item: Option<FileItem>,
    #[serde(rename = "video_item", default)]
    pub video_item: Option<VideoItem>,
}

/// message_type: 1=用户 2=Bot；message_state: 0=new 1=generating 2=finish
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct WeixinMessage {
    #[serde(default)]
    pub seq: i64,
    #[serde(rename = "message_id", default)]
    pub message_id: i64,
    #[serde(rename = "from_user_id", default)]
    pub from_user_id: String,
    #[serde(rename = "to_user_id", default)]
    pub to_user_id: String,
    #[serde(rename = "client_id", default)]
    pub client_id: String,
    #[serde(rename = "create_time_ms", default)]
    pub create_time_ms: i64,
    #[serde(rename = "update_time_ms", default)]
    pub update_time_ms: i64,
    #[serde(rename = "delete_time_ms", default)]
    pub delete_time_ms: i64,
    #[serde(rename = "session_id", default)]
    pub session_id: String,
    #[serde(rename = "group_id", default)]
    pub group_id: String,
    #[serde(rename = "message_type", default)]
    pub message_type: i64,
    #[serde(rename = "message_state", default)]
    pub message_state: i64,
    #[serde(rename = "item_list", default)]
    pub item_list: Vec<MessageItem>,
    #[serde(rename = "context_token", default)]
    pub context_token: String,
}

impl WeixinMessage {
    /// 提取可读文本：文本 item（含引用前缀）→ 语音转写。
    pub fn extract_text(&self) -> String {
        for item in &self.item_list {
            if item.item_type == 1 {
                if let Some(t) = &item.text_item {
                    let mut text = t.text.clone();
                    if let Some(r) = &item.ref_msg {
                        if let Some(ri) = &r.message_item {
                            if ri.item_type != 2 && ri.item_type != 3 && ri.item_type != 4 && ri.item_type != 5 {
                                let ref_body = ri.text_item.as_ref().map(|x| x.text.clone()).unwrap_or_default();
                                if !r.title.is_empty() || !ref_body.is_empty() {
                                    text = format!("[引用: {} | {}]\n{}", r.title, ref_body, text);
                                }
                            }
                        }
                    }
                    return text;
                }
            }
        }
        for item in &self.item_list {
            if item.item_type == 3 {
                if let Some(v) = &item.voice_item {
                    if !v.text.is_empty() {
                        return v.text.clone();
                    }
                }
            }
        }
        String::new()
    }
}

#[derive(Debug, Serialize)]
struct GetUpdatesReq {
    get_updates_buf: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    base_info: Option<BaseInfo>,
}

#[derive(Debug, Deserialize, Default)]
pub struct GetUpdatesResp {
    #[serde(default)]
    pub ret: i64,
    #[serde(default)]
    pub errcode: i64,
    #[serde(default)]
    pub errmsg: String,
    #[serde(default)]
    pub msgs: Vec<WeixinMessage>,
    #[serde(rename = "get_updates_buf", default)]
    pub get_updates_buf: String,
    #[serde(rename = "sync_buf", default)]
    pub sync_buf: String,
    #[serde(rename = "longpolling_timeout_ms", default)]
    pub longpolling_timeout_ms: i64,
}

#[derive(Debug, Serialize)]
struct SendMessageReq {
    msg: WeixinMessage,
    #[serde(skip_serializing_if = "Option::is_none")]
    base_info: Option<BaseInfo>,
}

#[derive(Debug, Serialize)]
struct GetConfigReq {
    ilink_user_id: String,
    context_token: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    base_info: Option<BaseInfo>,
}

#[derive(Debug, Deserialize, Default)]
pub struct GetConfigResp {
    #[serde(default)]
    pub ret: i64,
    #[serde(default)]
    pub errmsg: String,
    #[serde(rename = "typing_ticket", default)]
    pub typing_ticket: String,
}

#[derive(Debug, Serialize)]
struct SendTypingReq {
    ilink_user_id: String,
    typing_ticket: String,
    status: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    base_info: Option<BaseInfo>,
}

/// media_type: 1=图片 2=视频 3=文件 4=语音
#[derive(Debug, Serialize, Default)]
pub struct GetUploadUrlReq {
    #[serde(rename = "filekey", skip_serializing_if = "String::is_empty")]
    pub filekey: String,
    #[serde(rename = "media_type", skip_serializing_if = "is_zero")]
    pub media_type: i64,
    #[serde(rename = "to_user_id", skip_serializing_if = "String::is_empty")]
    pub to_user_id: String,
    #[serde(rename = "rawsize", skip_serializing_if = "is_zero")]
    pub rawsize: i64,
    #[serde(rename = "rawfilemd5", skip_serializing_if = "String::is_empty")]
    pub rawfilemd5: String,
    #[serde(rename = "filesize", skip_serializing_if = "is_zero")]
    pub filesize: i64,
    #[serde(rename = "no_need_thumb")]
    pub no_need_thumb: bool,
    #[serde(rename = "aeskey", skip_serializing_if = "String::is_empty")]
    pub aeskey: String,
    #[serde(rename = "base_info", skip_serializing_if = "Option::is_none")]
    pub base_info: Option<BaseInfo>,
}

#[derive(Debug, Deserialize, Default)]
pub struct GetUploadUrlResp {
    #[serde(default)]
    pub ret: i64,
    #[serde(default)]
    pub errmsg: String,
    #[serde(rename = "upload_param", default)]
    pub upload_param: String,
    #[serde(rename = "upload_full_url", default)]
    pub upload_full_url: String,
}

#[derive(Debug, Deserialize, Default)]
pub struct QrCodeResponse {
    #[serde(default)]
    pub qrcode: String,
    #[serde(rename = "qrcode_img_content", default)]
    pub qrcode_img_content: String,
}

#[derive(Debug, Deserialize, Default)]
pub struct QrStatusResponse {
    #[serde(default)]
    pub status: String,
    #[serde(rename = "bot_token", default)]
    pub bot_token: String,
    #[serde(rename = "ilink_bot_id", default)]
    pub ilink_bot_id: String,
    #[serde(rename = "baseurl", default)]
    pub baseurl: String,
    #[serde(rename = "ilink_user_id", default)]
    pub ilink_user_id: String,
    #[serde(rename = "redirect_host", default)]
    pub redirect_host: String,
}

fn is_zero(v: &i64) -> bool {
    *v == 0
}

// ---------------------------------------------------------------------------
// AES-128-ECB + PKCS#7（与 Go 版逐位等价）
// ---------------------------------------------------------------------------

pub fn encrypt_aes_ecb(plaintext: &[u8], key: &[u8]) -> Result<Vec<u8>, String> {
    let enc = Encryptor::<aes::Aes128>::new_from_slice(key).map_err(|e| e.to_string())?;
    Ok(enc.encrypt_padded_vec_mut::<Pkcs7>(plaintext))
}

pub fn decrypt_aes_ecb(ciphertext: &[u8], key: &[u8]) -> Result<Vec<u8>, String> {
    let dec = Decryptor::<aes::Aes128>::new_from_slice(key).map_err(|e| e.to_string())?;
    dec.decrypt_padded_vec_mut::<Pkcs7>(ciphertext)
        .map_err(|e| format!("解密失败: {}", e))
}

/// CDNMedia.aes_key 的两种野外编码：
///  * base64(raw 16B)          —— 图片
///  * base64(hex 字符串 32 字符) —— 文件/语音/视频
pub fn parse_aes_key(aes_key_b64: &str) -> Result<Vec<u8>, String> {
    let decoded = decode_base64_flexible(aes_key_b64)?;
    if decoded.len() == 16 {
        return Ok(decoded);
    }
    if decoded.len() == 32 && decoded.iter().all(|c| c.is_ascii_hexdigit()) {
        return hex::decode(&decoded).map_err(|e| format!("hex aes_key: {}", e));
    }
    Err(format!(
        "aes_key 解码长度非法（{} 字节），应为 16 原始字节或 32 字符 hex",
        decoded.len()
    ))
}

fn decode_base64_flexible(s: &str) -> Result<Vec<u8>, String> {
    const STD: base64::engine::GeneralPurpose = base64::engine::general_purpose::STANDARD;
    const STD_NO_PAD: base64::engine::GeneralPurpose = base64::engine::general_purpose::STANDARD_NO_PAD;
    const URL: base64::engine::GeneralPurpose = base64::engine::general_purpose::URL_SAFE;
    const URL_NO_PAD: base64::engine::GeneralPurpose = base64::engine::general_purpose::URL_SAFE_NO_PAD;
    for enc in [&STD, &STD_NO_PAD, &URL, &URL_NO_PAD] {
        if let Ok(b) = enc.decode(s) {
            return Ok(b);
        }
    }
    Err(format!("非法 base64: {}", s))
}

/// hex key → base64(hex)，即 CDNMedia.aes_key 的组包格式
pub fn media_aes_key(hex_key: &str) -> String {
    STD.encode(hex_key.as_bytes())
}

const STD: base64::engine::GeneralPurpose = base64::engine::general_purpose::STANDARD;

// ---------------------------------------------------------------------------
// 客户端
// ---------------------------------------------------------------------------

#[derive(Clone)]
pub struct IlinkClient {
    pub base_url: String,
    pub cdn_base_url: String,
    pub token: String,
    bot_type: String,
    version: String,
    http: reqwest::Client,
    /// userID → contextToken（Push 主动发消息的凭据，来自对方最近一条入站消息）
    context_tokens: Arc<Mutex<HashMap<String, String>>>,
}

impl IlinkClient {
    pub fn new(token: &str, base_url: Option<String>, cdn_base_url: Option<String>) -> Self {
        IlinkClient {
            base_url: base_url.unwrap_or_else(|| DEFAULT_BASE_URL.to_string()),
            cdn_base_url: cdn_base_url.unwrap_or_else(|| DEFAULT_CDN_BASE_URL.to_string()),
            token: token.to_string(),
            bot_type: DEFAULT_BOT_TYPE.to_string(),
            version: ILINK_CHANNEL_VERSION.to_string(),
            http: reqwest::Client::new(),
            context_tokens: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    pub fn set_token(&mut self, token: &str) {
        self.token = token.to_string();
    }

    pub fn set_context_token(&self, user_id: &str, token: &str) {
        self.context_tokens
            .lock()
            .unwrap()
            .insert(user_id.to_string(), token.to_string());
    }

    pub fn get_context_token(&self, user_id: &str) -> Option<String> {
        self.context_tokens.lock().unwrap().get(user_id).cloned()
    }

    fn base_info(&self) -> BaseInfo {
        BaseInfo { channel_version: self.version.clone() }
    }

    fn common_headers(&self, rb: reqwest::RequestBuilder) -> reqwest::RequestBuilder {
        let rb = rb
            .header("iLink-App-Id", ILINK_APP_ID)
            .header("iLink-App-ClientVersion", encode_client_version(&self.version).to_string());
        rb
    }

    fn auth_headers(&self, rb: reqwest::RequestBuilder, body_len: usize) -> reqwest::RequestBuilder {
        let mut rb = self
            .common_headers(rb)
            .header("Content-Type", "application/json")
            .header("AuthorizationType", "ilink_bot_token")
            .header("Content-Length", body_len.to_string())
            .header("X-WECHAT-UIN", random_wechat_uin());
        if !self.token.trim().is_empty() {
            rb = rb.header("Authorization", format!("Bearer {}", self.token));
        }
        rb
    }

    async fn post(&self, endpoint: &str, body: &serde_json::Value, timeout: Duration) -> Result<Vec<u8>, String> {
        let data = serde_json::to_vec(body).map_err(|e| e.to_string())?;
        let url = format!("{}/{}", self.base_url.trim_end_matches('/'), endpoint);
        let rb = self.auth_headers(self.http.post(&url).timeout(timeout), data.len());
        let resp = rb.body(data).send().await.map_err(|e| format!("网络错误: {}", e))?;
        let status = resp.status();
        let bytes = resp.bytes().await.map_err(|e| format!("读取响应: {}", e))?.to_vec();
        if !status.is_success() {
            return Err(format!("HTTP {} {}", status, String::from_utf8_lossy(&bytes[..bytes.len().min(200)])));
        }
        Ok(bytes.to_vec())
    }

    async fn get_raw(&self, url: &str, timeout: Duration) -> Result<Vec<u8>, String> {
        let rb = self.common_headers(self.http.get(url).timeout(timeout));
        let resp = rb.send().await.map_err(|e| format!("网络错误: {}", e))?;
        let status = resp.status();
        let bytes = resp.bytes().await.map_err(|e| format!("读取响应: {}", e))?.to_vec();
        if !status.is_success() {
            return Err(format!("HTTP {} {}", status, String::from_utf8_lossy(&bytes[..bytes.len().min(200)])));
        }
        Ok(bytes.to_vec())
    }

    // --- 收消息 ---

    /// getupdates 长轮询。超时视为空轮询（对齐 Go 版 is_timeout → 空响应）。
    pub async fn get_updates(&self, buf: &str, timeout_ms: u64) -> Result<GetUpdatesResp, String> {
        let req = json!({
            "get_updates_buf": buf,
            "base_info": self.base_info(),
        });
        let timeout = if timeout_ms > 0 { Duration::from_millis(timeout_ms) } else { LONG_POLL_TIMEOUT };
        match self.post("ilink/bot/getupdates", &req, timeout).await {
            Ok(body) => serde_json::from_slice(&body).map_err(|e| format!("getupdates 解析: {}", e)),
            Err(e) => {
                // 长轮询超时属于正常（Go 版对 timeout 返回空响应重试）
                if e.contains("operation timed out") || e.contains("timed out") || e.contains("deadline") {
                    Ok(GetUpdatesResp::default())
                } else {
                    Err(e)
                }
            }
        }
    }

    // --- 发消息 ---

    pub async fn send_message(&self, msg: WeixinMessage) -> Result<(), String> {
        let req = json!({ "msg": msg, "base_info": self.base_info() });
        self.post("ilink/bot/sendmessage", &req, API_TIMEOUT).await.map(|_| ())
    }

    fn make_msg(to: &str, context_token: &str, item: MessageItem) -> WeixinMessage {
        WeixinMessage {
            to_user_id: to.to_string(),
            client_id: generate_client_id(),
            message_type: 2,  // MsgTypeBot
            message_state: 2, // StateFinish
            context_token: context_token.to_string(),
            item_list: vec![item],
            ..Default::default()
        }
    }

    /// 发文本。context_token 必须来自对方最近的入站消息（24h 窗口）。
    pub async fn send_text(&self, to: &str, text: &str, context_token: &str) -> Result<String, String> {
        let msg = Self::make_msg(
            to,
            context_token,
            MessageItem {
                item_type: 1,
                text_item: Some(TextItem { text: text.to_string() }),
                ..Default::default()
            },
        );
        let client_id = msg.client_id.clone();
        self.send_message(msg).await?;
        Ok(client_id)
    }

    /// 用缓存的 context_token 主动推文本（对方从未发过消息时返回 ErrNoContextToken）。
    pub async fn push_text(&self, to: &str, text: &str) -> Result<String, String> {
        let token = self
            .get_context_token(to)
            .ok_or("该联系人尚未与本账号互动过（无 context_token，微信 24h 窗口限制），请等对方先发一条消息")?;
        self.send_text(to, text, &token).await
    }

    /// 发图片（上传 CDN + AES 加密 + sendmessage）。
    pub async fn send_image(&self, to: &str, context_token: &str, data: &[u8], _filename: &str) -> Result<(), String> {
        let uploaded = self.upload_file(data, to, 1).await?; // MediaImage
        let msg = Self::make_msg(
            to,
            context_token,
            MessageItem {
                item_type: 2,
                image_item: Some(ImageItem {
                    media: Some(CdnMedia {
                        encrypt_query_param: uploaded.download_encrypted_query_param,
                        aes_key: media_aes_key(&uploaded.aes_key),
                        encrypt_type: 1,
                        ..Default::default()
                    }),
                    mid_size: uploaded.ciphertext_size,
                    ..Default::default()
                }),
                ..Default::default()
            },
        );
        self.send_message(msg).await
    }

    /// 发语音（SILK 等已编码数据直传，encode_type: 4=SILK 2=MP3 3=WAV）。
    pub async fn send_voice(
        &self,
        to: &str,
        context_token: &str,
        data: &[u8],
        encode_type: i64,
        sample_rate: i64,
        playtime_ms: i64,
    ) -> Result<(), String> {
        let uploaded = self.upload_file(data, to, 4).await?; // MediaVoice
        let msg = Self::make_msg(
            to,
            context_token,
            MessageItem {
                item_type: 3,
                voice_item: Some(VoiceItem {
                    media: Some(CdnMedia {
                        encrypt_query_param: uploaded.download_encrypted_query_param,
                        aes_key: media_aes_key(&uploaded.aes_key),
                        encrypt_type: 1,
                        ..Default::default()
                    }),
                    encode_type,
                    bits_per_sample: 16,
                    sample_rate,
                    playtime: playtime_ms,
                    ..Default::default()
                }),
                ..Default::default()
            },
        );
        self.send_message(msg).await
    }

    // --- 其他 ---

    pub async fn get_config(&self, user_id: &str, context_token: &str) -> Result<GetConfigResp, String> {
        let req = json!({
            "ilink_user_id": user_id,
            "context_token": context_token,
            "base_info": self.base_info(),
        });
        let body = self.post("ilink/bot/getconfig", &req, Duration::from_secs(10)).await?;
        serde_json::from_slice(&body).map_err(|e| format!("getconfig 解析: {}", e))
    }

    pub async fn send_typing(&self, user_id: &str, typing_ticket: &str, status: i64) -> Result<(), String> {
        let req = json!({
            "ilink_user_id": user_id,
            "typing_ticket": typing_ticket,
            "status": status,
            "base_info": self.base_info(),
        });
        self.post("ilink/bot/sendtyping", &req, Duration::from_secs(10)).await.map(|_| ())
    }

    // --- 扫码登录 ---

    pub async fn fetch_qr_code(&self) -> Result<QrCodeResponse, String> {
        let url = format!(
            "{}/ilink/bot/get_bot_qrcode?bot_type={}",
            self.base_url.trim_end_matches('/'),
            self.bot_type
        );
        let body = self.get_raw(&url, Duration::from_secs(15)).await?;
        serde_json::from_slice(&body).map_err(|e| format!("二维码响应解析: {}", e))
    }

    /// 轮询扫码状态；网络错误/超时按 "wait" 处理（长轮询中断属正常）。
    pub async fn poll_qr_status(&self, qrcode: &str, base_url: &str) -> Result<QrStatusResponse, String> {
        let url = format!(
            "{}/ilink/bot/get_qrcode_status?qrcode={}",
            base_url.trim_end_matches('/'),
            urlencoding_lite(qrcode)
        );
        match self.get_raw(&url, QR_POLL_TIMEOUT).await {
            Ok(body) => serde_json::from_slice(&body).map_err(|e| format!("扫码状态解析: {}", e)),
            Err(e) => {
                if e.contains("timed out") || e.contains("deadline") || e.contains("网络错误") {
                    Ok(QrStatusResponse { status: "wait".into(), ..Default::default() })
                } else {
                    Err(e)
                }
            }
        }
    }

    // --- CDN 媒体 ---

    /// 全链路上传：MD5 → 随机 AES key → getuploadurl → AES-128-ECB 加密 → CDN POST。
    pub async fn upload_file(
        &self,
        plaintext: &[u8],
        to_user_id: &str,
        media_type: i64,
    ) -> Result<UploadResult, String> {
        use md5::Digest;
        let raw_size = plaintext.len() as i64;
        let raw_md5 = hex::encode(md5::Md5::digest(plaintext));
        let file_size = aes_ecb_padded_size(plaintext.len()) as i64;
        let mut fk = [0u8; 16];
        rand::thread_rng().fill_bytes(&mut fk);
        let filekey = hex::encode(fk);
        let mut ak = [0u8; 16];
        rand::thread_rng().fill_bytes(&mut ak);
        let aes_key_hex = hex::encode(ak);

        let req = GetUploadUrlReq {
            filekey: filekey.clone(),
            media_type,
            to_user_id: to_user_id.to_string(),
            rawsize: raw_size,
            rawfilemd5: raw_md5,
            filesize: file_size,
            no_need_thumb: true,
            aeskey: aes_key_hex.clone(),
            base_info: Some(self.base_info()),
        };
        let body = self
            .post("ilink/bot/getuploadurl", &serde_json::to_value(&req).map_err(|e| e.to_string())?, API_TIMEOUT)
            .await?;
        let resp: GetUploadUrlResp = serde_json::from_slice(&body).map_err(|e| format!("getuploadurl 解析: {}", e))?;
        if resp.ret != 0 {
            return Err(format!("getuploadurl 失败 ret={} {}", resp.ret, resp.errmsg));
        }
        let cdn_url = if !resp.upload_full_url.trim().is_empty() {
            resp.upload_full_url.trim().to_string()
        } else if !resp.upload_param.is_empty() {
            format!(
                "{}/upload?encrypted_query_param={}&filekey={}",
                self.cdn_base_url.trim_end_matches('/'),
                urlencoding_lite(&resp.upload_param),
                urlencoding_lite(&filekey)
            )
        } else {
            return Err("getuploadurl 未返回上传地址".into());
        };

        let ciphertext = encrypt_aes_ecb(plaintext, &ak)?;
        // 上传重试 3 次，4xx 不重试
        let mut last_err = String::new();
        for attempt in 1..=3 {
            match self.upload_to_cdn(&cdn_url, &ciphertext).await {
                Ok(p) => {
                    return Ok(UploadResult {
                        file_key: filekey,
                        download_encrypted_query_param: p,
                        aes_key: aes_key_hex,
                        file_size: raw_size,
                        ciphertext_size: ciphertext.len() as i64,
                    })
                }
                Err(e) => {
                    if e.starts_with("HTTP 4") {
                        return Err(e);
                    }
                    last_err = e;
                    if attempt < 3 {
                        tokio::time::sleep(Duration::from_secs(2)).await;
                    }
                }
            }
        }
        Err(format!("CDN 上传 3 次均失败: {}", last_err))
    }

    async fn upload_to_cdn(&self, cdn_url: &str, body: &[u8]) -> Result<String, String> {
        let resp = self
            .http
            .post(cdn_url)
            .timeout(CDN_TIMEOUT)
            .header("Content-Type", "application/octet-stream")
            .body(body.to_vec())
            .send()
            .await
            .map_err(|e| format!("网络错误: {}", e))?;
        let status = resp.status();
        let err_msg = resp.headers().get("x-error-message").and_then(|v| v.to_str().ok()).map(String::from);
        if status != reqwest::StatusCode::OK {
            let text = resp.text().await.unwrap_or_default();
            return Err(format!(
                "HTTP {} {}",
                status,
                err_msg.unwrap_or(text)
            ));
        }
        let param = resp
            .headers()
            .get("x-encrypted-param")
            .and_then(|v| v.to_str().ok())
            .map(String::from)
            .ok_or("CDN 响应缺少 x-encrypted-param 头")?;
        Ok(param)
    }

    fn resolve_download_url(&self, media: &CdnMedia) -> Result<String, String> {
        if !media.full_url.is_empty() {
            return Ok(media.full_url.clone());
        }
        if !media.encrypt_query_param.is_empty() {
            return Ok(format!(
                "{}/download?encrypted_query_param={}",
                self.cdn_base_url.trim_end_matches('/'),
                urlencoding_lite(&media.encrypt_query_param)
            ));
        }
        Err("CDN 媒体缺少 full_url 与 encrypt_query_param".into())
    }

    /// 下载并解密 CDN 媒体（图片/语音/文件通用）。
    pub async fn download_media(&self, media: &CdnMedia) -> Result<Vec<u8>, String> {
        let key = parse_aes_key(&media.aes_key)?;
        let url = self.resolve_download_url(media)?;
        let bytes = self.get_raw(&url, CDN_TIMEOUT).await.map_err(|e| format!("CDN 下载: {}", e))?;
        decrypt_aes_ecb(&bytes, &key).map_err(|e| format!("CDN 解密: {}", e))
    }

    /// 下载原始字节（不解密）。
    pub async fn download_media_raw(&self, media: &CdnMedia) -> Result<Vec<u8>, String> {
        let url = self.resolve_download_url(media)?;
        self.get_raw(&url, CDN_TIMEOUT).await.map_err(|e| format!("CDN 下载: {}", e))
    }
}

#[derive(Debug, Clone)]
pub struct UploadResult {
    pub file_key: String,
    pub download_encrypted_query_param: String,
    pub aes_key: String,
    pub file_size: i64,
    pub ciphertext_size: i64,
}

// ---------------------------------------------------------------------------
// 工具
// ---------------------------------------------------------------------------

/// semver → u32: major<<16 | minor<<8 | patch（对齐 Go encodeClientVersion）
pub fn encode_client_version(version: &str) -> u32 {
    let mut it = version.split('.');
    let major: u32 = it.next().and_then(|s| s.parse().ok()).unwrap_or(0);
    let minor: u32 = it.next().and_then(|s| s.parse().ok()).unwrap_or(0);
    let patch: u32 = it.next().and_then(|s| s.parse().ok()).unwrap_or(0);
    (major & 0xff) << 16 | (minor & 0xff) << 8 | (patch & 0xff)
}

fn random_wechat_uin() -> String {
    let mut b = [0u8; 4];
    rand::thread_rng().fill_bytes(&mut b);
    let n = u32::from_be_bytes(b);
    STD.encode(n.to_string().as_bytes())
}

pub fn generate_client_id() -> String {
    let mut b = [0u8; 4];
    rand::thread_rng().fill_bytes(&mut b);
    let ts = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0);
    format!("openclaw-weixin:{}-{}", ts, hex::encode(b))
}

fn aes_ecb_padded_size(n: usize) -> usize {
    ((n + 1 + 15) / 16) * 16
}

/// 极简 percent-encode（仅用于 query 参数，字符集对齐 Go url.QueryEscape 的保守子集）
fn urlencoding_lite(s: &str) -> String {
    let mut out = String::with_capacity(s.len() * 2);
    for b in s.as_bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' | b'*' | b'\'' | b'(' | b')' => {
                out.push(*b as char)
            }
            b' ' => out.push_str("%20"),
            _ => out.push_str(&format!("%{:02X}", b)),
        }
    }
    out
}

/// 判断 getupdates 响应是否为会话过期（errcode/ret == -14）
pub fn is_session_expired(ret: i64, errcode: i64) -> bool {
    ret == -14 || errcode == -14
}
