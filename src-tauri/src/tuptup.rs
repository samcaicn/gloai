use crate::crypto::{
    generate_6_digit_code, get_verification_email_template, get_verification_email_text,
    ClientCrypto, SmtpConfig, SmtpConfigResponse, VerifyCodeResponse,
};
use lettre::{
    message::{header::ContentType, MultiPart, SinglePart},
    AsyncSmtpTransport, AsyncTransport, Message, Tokio1Executor,
};
use reqwest::Client;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::sync::Mutex;
use chrono::{DateTime, Utc};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TuptupUserInfo {
    pub user_id: Option<String>,
    pub username: Option<String>,
    pub email: Option<String>,
    pub vip_level: Option<i32>,
    pub plan: Option<TuptupPlan>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TuptupPlan {
    pub level: Option<i32>,
    pub name: Option<String>,
    pub expires_at: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TuptupTokenBalance {
    pub balance: Option<f64>,
    pub currency: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TuptupOverview {
    pub user_id: Option<String>,
    pub username: Option<String>,
    pub email: Option<String>,
    pub vip_level: Option<i32>,
    pub level: Option<i32>,
    pub plan: Option<TuptupPlan>,
    pub token_balance: Option<f64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UserPackage {
    pub package_id: Option<String>,
    pub package_name: Option<String>,
    pub features: Option<Vec<String>>,
    pub limits: Option<serde_json::Value>,
    pub expires_at: Option<String>,
    pub used_quota: Option<serde_json::Value>,
    pub level: Option<i32>,
    pub is_expired: Option<bool>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PackageStatus {
    pub is_expired: bool,
    pub level: i32,
    pub level_name: String,
    pub expires_at: Option<String>,
    pub days_remaining: Option<i64>,
}

impl PackageStatus {
    pub fn from_package(pkg: &UserPackage) -> Self {
        let is_expired = pkg.is_expired.unwrap_or_else(|| {
            if let Some(expires_at) = &pkg.expires_at {
                if let Ok(expiry) = chrono::DateTime::parse_from_rfc3339(expires_at) {
                    return chrono::Utc::now() > expiry.with_timezone(&chrono::Utc);
                }
            }
            false
        });

        let days_remaining = if let Some(expires_at) = &pkg.expires_at {
            if let Ok(expiry) = chrono::DateTime::parse_from_rfc3339(expires_at) {
                let duration = expiry.with_timezone(&chrono::Utc) - chrono::Utc::now();
                Some(duration.num_days())
            } else {
                None
            }
        } else {
            None
        };

        let level = pkg.level.unwrap_or(0);
        let level_name = match level {
            0 => "免费版".to_string(),
            1 => "基础版".to_string(),
            2 => "标准版".to_string(),
            3 => "专业版".to_string(),
            4 => "企业版".to_string(),
            _ => format!("VIP{}", level),
        };

        Self {
            is_expired,
            level,
            level_name,
            expires_at: pkg.expires_at.clone(),
            days_remaining,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VerificationCode {
    pub code: String,
    pub email: String,
    pub expires_at: DateTime<Utc>,
}

pub struct TuptupService {
    client: Client,
    base_url: String,
    app_key: String,
    app_secret: String,
    crypto: ClientCrypto,
    cached_smtp: Arc<Mutex<Option<SmtpConfig>>>,
    verification_codes: Arc<Mutex<HashMap<String, VerificationCode>>>,
}

impl TuptupService {
    pub fn new() -> Self {
        // 优先使用 apiKey 和 apiSecret 环境变量，保持向后兼容
        let app_key = std::env::var("apiKey").unwrap_or_else(|_| 
            std::env::var("GGCLAW_APP_KEY").unwrap_or_else(|_| "gk_981279d245764a1cb53738da".to_string())
        );
        let app_secret = std::env::var("apiSecret").unwrap_or_else(|_| 
            std::env::var("GGCLAW_APP_SECRET").unwrap_or_else(|_| "gs_7a8b9c0d1e2f3g4h5i6j7k8l9m0n1o2".to_string())
        );
        
        Self {
            client: Client::new(),
            base_url: "https://claw.hncea.cc".to_string(),
            app_key,
            app_secret: app_secret.clone(),
            crypto: ClientCrypto::with_secret(&app_secret),
            cached_smtp: Arc::new(Mutex::new(None)),
            verification_codes: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    fn get_timestamp() -> i64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_millis() as i64
    }

    fn generate_signature(timestamp: i64, app_key: &str, app_secret: &str) -> String {
        let input = format!("{}{}{}", timestamp, app_key, app_secret);
        let mut hasher = Sha256::new();
        hasher.update(input.as_bytes());
        format!("{:x}", hasher.finalize())
    }

    #[allow(dead_code)]
    async fn encrypt_request(&self, body: &serde_json::Value) -> anyhow::Result<String> {
        let json_str = serde_json::to_string(body)?;
        self.crypto.encrypt(&json_str)
    }

    async fn decrypt_response(&self, encrypted: &str) -> anyhow::Result<serde_json::Value> {
        let decrypted = self.crypto.decrypt(encrypted)?;
        serde_json::from_str(&decrypted).map_err(|e| anyhow::anyhow!("JSON parse error: {}", e))
    }

    pub async fn get_user_info(&self, user_id: &str) -> anyhow::Result<TuptupUserInfo> {
        let timestamp = Self::get_timestamp();
        let signature = Self::generate_signature(timestamp, &self.app_key, &self.app_secret);

        let response = self
            .client
            .get(format!("{}/api/client/user/info", self.base_url))
            .header("X-App-Key", &self.app_key)
            .header("X-User-Id", user_id)
            .header("X-Timestamp", timestamp.to_string())
            .header("X-Signature", signature)
            .header("X-Encryption", "aes-256-gcm")
            .send()
            .await?;

        let text = response.text().await?;
        if text.starts_with('{') {
            let user_info: TuptupUserInfo = serde_json::from_str(&text)?;
            Ok(user_info)
        } else {
            let decrypted = self.decrypt_response(&text).await?;
            let user_info: TuptupUserInfo = serde_json::from_value(decrypted)?;
            Ok(user_info)
        }
    }

    pub async fn get_token_balance(&self, user_id: &str) -> anyhow::Result<TuptupTokenBalance> {
        let timestamp = Self::get_timestamp();
        let signature = Self::generate_signature(timestamp, &self.app_key, &self.app_secret);

        let response = self
            .client
            .get(format!("{}/api/client/user/token-balance", self.base_url))
            .header("X-App-Key", &self.app_key)
            .header("X-User-Id", user_id)
            .header("X-Timestamp", timestamp.to_string())
            .header("X-Signature", signature)
            .header("X-Encryption", "aes-256-gcm")
            .send()
            .await?;

        let text = response.text().await?;
        if text.starts_with('{') {
            let balance: TuptupTokenBalance = serde_json::from_str(&text)?;
            Ok(balance)
        } else {
            let decrypted = self.decrypt_response(&text).await?;
            let balance: TuptupTokenBalance = serde_json::from_value(decrypted)?;
            Ok(balance)
        }
    }

    pub async fn get_plan(&self, user_id: &str) -> anyhow::Result<TuptupPlan> {
        let timestamp = Self::get_timestamp();
        let signature = Self::generate_signature(timestamp, &self.app_key, &self.app_secret);

        let response = self
            .client
            .get(format!("{}/api/client/user/plan", self.base_url))
            .header("X-App-Key", &self.app_key)
            .header("X-User-Id", user_id)
            .header("X-Timestamp", timestamp.to_string())
            .header("X-Signature", signature)
            .header("X-Encryption", "aes-256-gcm")
            .send()
            .await?;

        let text = response.text().await?;
        if text.starts_with('{') {
            let plan: TuptupPlan = serde_json::from_str(&text)?;
            Ok(plan)
        } else {
            let decrypted = self.decrypt_response(&text).await?;
            let plan: TuptupPlan = serde_json::from_value(decrypted)?;
            Ok(plan)
        }
    }

    pub async fn get_overview(&self, user_id: &str) -> anyhow::Result<TuptupOverview> {
        let timestamp = Self::get_timestamp();
        let signature = Self::generate_signature(timestamp, &self.app_key, &self.app_secret);

        let response = self
            .client
            .get(format!("{}/api/client/user/overview", self.base_url))
            .header("X-App-Key", &self.app_key)
            .header("X-User-Id", user_id)
            .header("X-Timestamp", timestamp.to_string())
            .header("X-Signature", signature)
            .header("X-Encryption", "aes-256-gcm")
            .send()
            .await?;

        let text = response.text().await?;
        if text.starts_with('{') {
            let overview: TuptupOverview = serde_json::from_str(&text)?;
            Ok(overview)
        } else {
            let decrypted = self.decrypt_response(&text).await?;
            let overview: TuptupOverview = serde_json::from_value(decrypted)?;
            Ok(overview)
        }
    }

    pub async fn get_smtp_config(&self, _user_id: &str) -> anyhow::Result<SmtpConfig> {
        let response = self
            .client
            .get(format!("{}/api/client/smtp/config", self.base_url))
            .header("X-API-Key", &self.app_key)
            .send()
            .await?;

        let text = response.text().await?;
        println!("[SMTP] Response: {}", text);
        
        if text.starts_with('{') {
            let resp: SmtpConfigResponse = serde_json::from_str(&text)?;
            if !resp.success {
                return Err(anyhow::anyhow!("SMTP config request failed: {:?}", resp.message));
            }
            let config = resp.data.ok_or_else(|| anyhow::anyhow!("SMTP config data is null"))?;
            
            let mut cached = self.cached_smtp.lock().await;
            *cached = Some(config.clone());
            
            return Ok(config);
        }
        
        let decrypted = self.decrypt_response(&text).await?;
        let config: SmtpConfig = serde_json::from_value(decrypted)?;
        
        let mut cached = self.cached_smtp.lock().await;
        *cached = Some(config.clone());

        Ok(config)
    }

    pub async fn get_user_package(&self) -> anyhow::Result<UserPackage> {
        let timestamp = Self::get_timestamp();
        let signature = Self::generate_signature(timestamp, &self.app_key, &self.app_secret);

        let response = self
            .client
            .get(format!("{}/api/client/user/package", self.base_url))
            .header("X-App-Key", &self.app_key)
            .header("X-User-Id", "2")
            .header("X-Timestamp", timestamp.to_string())
            .header("X-Signature", signature)
            .header("X-Encryption", "aes-256-gcm")
            .send()
            .await?;

        let text = response.text().await?;
        if text.starts_with('{') {
            let pkg: UserPackage = serde_json::from_str(&text)?;
            Ok(pkg)
        } else {
            let decrypted = self.decrypt_response(&text).await?;
            let pkg: UserPackage = serde_json::from_value(decrypted)?;
            Ok(pkg)
        }
    }

    pub async fn send_verification_email(
        &mut self,
        email: &str,
    ) -> anyhow::Result<VerifyCodeResponse> {
        let smtp_config = SmtpConfig {
            host: "smtp.qq.com".to_string(),
            port: 465,
            secure: true,
            username: "tuptup@qq.com".to_string(),
            password: "tjzshkfodawpebfi".to_string(),
        };

        let code = generate_6_digit_code();
        let code_id = uuid::Uuid::new_v4().to_string();
        let expires_at = Utc::now() + chrono::Duration::minutes(5);

        println!("[SMTP] Sending verification code {} to {}", code, email);

        let email_message = Message::builder()
            .from(smtp_config.username.parse()?)
            .to(email.parse()?)
            .subject("Code")
            .body(get_verification_email_text(&code))?;

        let mailer: AsyncSmtpTransport<Tokio1Executor> = 
            AsyncSmtpTransport::<Tokio1Executor>::relay(&smtp_config.host)?
                .credentials(lettre::transport::smtp::authentication::Credentials::new(
                    smtp_config.username.clone(),
                    smtp_config.password.clone(),
                ))
                .port(smtp_config.port)
                .build();

        println!("[SMTP] Connecting to {}:{}...", smtp_config.host, smtp_config.port);
        
        match mailer.send(email_message).await {
            Ok(_) => {
                println!("[SMTP] Email sent successfully to {}", email);
                let mut codes = self.verification_codes.lock().await;
                codes.insert(email.to_string(), VerificationCode {
                    code: code.clone(),
                    email: email.to_string(),
                    expires_at,
                });
                
                Ok(VerifyCodeResponse {
                    success: true,
                    code_id: Some(code_id),
                    expires_at: Some(expires_at.to_rfc3339()),
                    message: Some(format!("验证码已发送到 {}", email)),
                })
            }
            Err(e) => {
                println!("[SMTP] Failed to send email: {}", e);
                Ok(VerifyCodeResponse {
                    success: false,
                    code_id: None,
                    expires_at: None,
                    message: Some(format!("发送邮件失败: {}", e)),
                })
            }
        }
    }

    pub async fn verify_code(&self, email: &str, code: &str) -> bool {
        let mut codes = self.verification_codes.lock().await;
        if let Some(stored) = codes.get(email) {
            if stored.code == code && stored.expires_at > Utc::now() {
                codes.remove(email);
                return true;
            }
        }
        false
    }

    pub async fn get_user_id_by_email(&self, email: &str) -> anyhow::Result<Option<String>> {
        let timestamp = Self::get_timestamp();
        let signature = Self::generate_signature(timestamp, &self.app_key, &self.app_secret);

        println!("[Tuptup] Looking up user by email: {}", email);
        
        let response = self
            .client
            .get(format!("{}/api/client/user/lookup", self.base_url))
            .header("X-App-Key", &self.app_key)
            .header("X-Email", email)
            .header("X-Timestamp", timestamp.to_string())
            .header("X-Signature", signature)
            .header("X-Encryption", "aes-256-gcm")
            .send()
            .await?;

        println!("[Tuptup] Response status: {}", response.status());
        let text = response.text().await?;
        println!("[Tuptup] Response text: {}", text);
        
        if text.starts_with('{') {
            let json: serde_json::Value = serde_json::from_str(&text)?;
            println!("[Tuptup] Parsed JSON: {:?}", json);
            let user_id = json.get("user_id").and_then(|v| v.as_str()).map(|s| s.to_string());
            println!("[Tuptup] Extracted user_id: {:?}", user_id);
            Ok(user_id)
        } else {
            println!("[Tuptup] Response is encrypted, trying to decrypt...");
            let decrypted = self.decrypt_response(&text).await?;
            println!("[Tuptup] Decrypted response: {:?}", decrypted);
            let user_id = decrypted.get("user_id").and_then(|v| v.as_str()).map(|s| s.to_string());
            println!("[Tuptup] Extracted user_id: {:?}", user_id);
            Ok(user_id)
        }
    }

    pub fn get_cached_smtp(&self) -> Arc<Mutex<Option<SmtpConfig>>> {
        self.cached_smtp.clone()
    }
}

impl Default for TuptupService {
    fn default() -> Self {
        Self::new()
    }
}
