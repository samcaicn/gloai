use aes_gcm::{
    aead::{Aead, KeyInit},
    Aes256Gcm, Nonce,
};
use base64::{engine::general_purpose::STANDARD as BASE64, Engine};
use rand::{Rng, RngCore};
use sha2::{Digest, Sha256};

const GCM_IV_LENGTH: usize = 12;

#[derive(Debug, Clone)]
pub struct ClientCrypto {
    secret: String,
}

impl ClientCrypto {
    pub fn new() -> Self {
        // 从环境变量获取密钥，若不存在则使用默认值
        let secret = std::env::var("GGCLAW_SECRET").unwrap_or_else(|_| "gs_7a8b9c0d1e2f3g4h5i6j7k8l9m0n1o2".to_string());
        Self {
            secret,
        }
    }

    pub fn with_secret(secret: &str) -> Self {
        Self {
            secret: secret.to_string(),
        }
    }

    fn derive_key(&self) -> [u8; 32] {
        let mut hasher = Sha256::new();
        hasher.update(self.secret.as_bytes());
        let result = hasher.finalize();
        let mut key = [0u8; 32];
        key.copy_from_slice(&result);
        key
    }

    pub fn encrypt(&self, plaintext: &str) -> anyhow::Result<String> {
        let key = self.derive_key();
        let cipher = Aes256Gcm::new_from_slice(&key)
            .map_err(|e| anyhow::anyhow!("Failed to create cipher: {}", e))?;

        let mut iv = [0u8; GCM_IV_LENGTH];
        rand::thread_rng().fill_bytes(&mut iv);

        let nonce = Nonce::from_slice(&iv);
        let ciphertext = cipher
            .encrypt(nonce, plaintext.as_bytes())
            .map_err(|_| anyhow::anyhow!("Encryption failed"))?;

        let mut result = Vec::with_capacity(GCM_IV_LENGTH + ciphertext.len());
        result.extend_from_slice(&iv);
        result.extend_from_slice(&ciphertext);

        Ok(BASE64.encode(&result))
    }

    pub fn decrypt(&self, encrypted_base64: &str) -> anyhow::Result<String> {
        let key = self.derive_key();
        let cipher = Aes256Gcm::new_from_slice(&key)
            .map_err(|e| anyhow::anyhow!("Failed to create cipher: {}", e))?;

        let encrypted = BASE64.decode(encrypted_base64)?;

        if encrypted.len() < GCM_IV_LENGTH {
            return Err(anyhow::anyhow!("Encrypted data too short"));
        }

        let (iv, ciphertext) = encrypted.split_at(GCM_IV_LENGTH);
        let nonce = Nonce::from_slice(iv);

        let plaintext = cipher
            .decrypt(nonce, ciphertext)
            .map_err(|_| anyhow::anyhow!("Decryption failed"))?;

        String::from_utf8(plaintext).map_err(|e| anyhow::anyhow!("Invalid UTF-8: {}", e))
    }

    pub fn generate_nonce() -> String {
        let mut nonce = [0u8; 6];
        rand::thread_rng().fill_bytes(&mut nonce);
        hex::encode(nonce)
    }

    pub fn generate_signature(api_key: &str, timestamp: i64, nonce: &str) -> String {
        let input = format!("{}{}{}", api_key, timestamp, nonce);
        let mut hasher = Sha256::new();
        hasher.update(input.as_bytes());
        format!("{:x}", hasher.finalize())
    }
}

impl Default for ClientCrypto {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, Default)]
pub struct SmtpConfig {
    #[serde(default)]
    pub host: String,
    #[serde(default)]
    pub port: u16,
    #[serde(rename = "useSsl", default, deserialize_with = "deserialize_use_ssl")]
    pub secure: bool,
    #[serde(default)]
    pub username: String,
    #[serde(default, deserialize_with = "deserialize_nullable_string")]
    pub password: String,
}

fn deserialize_nullable_string<'de, D>(deserializer: D) -> Result<String, D::Error>
where
    D: serde::Deserializer<'de>,
{
    use serde::de::{self, Visitor};
    struct NullableStringVisitor;
    impl<'de> Visitor<'de> for NullableStringVisitor {
        type Value = String;
        fn expecting(&self, formatter: &mut std::fmt::Formatter) -> std::fmt::Result {
            formatter.write_str("a string or null")
        }
        fn visit_str<E>(self, v: &str) -> Result<Self::Value, E>
        where
            E: de::Error,
        {
            Ok(v.to_string())
        }
        fn visit_string<E>(self, v: String) -> Result<Self::Value, E>
        where
            E: de::Error,
        {
            Ok(v)
        }
        fn visit_none<E>(self) -> Result<Self::Value, E>
        where
            E: de::Error,
        {
            Ok(String::new())
        }
        fn visit_unit<E>(self) -> Result<Self::Value, E>
        where
            E: de::Error,
        {
            Ok(String::new())
        }
    }
    deserializer.deserialize_any(NullableStringVisitor)
}

fn deserialize_use_ssl<'de, D>(deserializer: D) -> Result<bool, D::Error>
where
    D: serde::Deserializer<'de>,
{
    use serde::de::{self, Visitor};
    struct UseSslVisitor;
    impl<'de> Visitor<'de> for UseSslVisitor {
        type Value = bool;
        fn expecting(&self, formatter: &mut std::fmt::Formatter) -> std::fmt::Result {
            formatter.write_str("an integer or a boolean")
        }
        fn visit_i64<E>(self, v: i64) -> Result<Self::Value, E>
        where
            E: de::Error,
        {
            Ok(v != 0)
        }
        fn visit_u64<E>(self, v: u64) -> Result<Self::Value, E>
        where
            E: de::Error,
        {
            Ok(v != 0)
        }
        fn visit_bool<E>(self, v: bool) -> Result<Self::Value, E>
        where
            E: de::Error,
        {
            Ok(v)
        }
    }
    deserializer.deserialize_any(UseSslVisitor)
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct SmtpConfigResponse {
    pub code: i32,
    pub data: Option<SmtpConfig>,
    pub success: bool,
    pub message: Option<String>,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct VerifyCodeRequest {
    pub email: String,
    pub user_id: String,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct VerifyCodeResponse {
    pub success: bool,
    pub code_id: Option<String>,
    pub expires_at: Option<String>,
    pub message: Option<String>,
}

pub fn generate_6_digit_code() -> String {
    let code: u32 = rand::thread_rng().gen_range(100000..=999999);
    format!("{:06}", code)
}

pub fn get_verification_email_template(code: &str) -> String {
    format!(
        r#"<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
</head>
<body style="font-family: Arial, sans-serif; padding: 20px;">
    <h2>TinyClaw</h2>
    <p>Your security code is:</p>
    <h1 style="color: #333; font-size: 32px; letter-spacing: 4px;">{}</h1>
    <p style="color: #666; font-size: 12px;">This code expires in 5 minutes.</p>
</body>
</html>"#,
        code
    )
}

pub fn get_verification_email_text(code: &str) -> String {
    format!("{}\n\nExpires in 5 minutes.", code)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_encrypt_decrypt() {
        let crypto = ClientCrypto::new();
        let plaintext = r#"{"email":"test@example.com","user_id":"123"}"#;
        let encrypted = crypto.encrypt(plaintext).unwrap();
        let decrypted = crypto.decrypt(&encrypted).unwrap();
        assert_eq!(plaintext, decrypted);
    }

    #[test]
    fn test_generate_code() {
        let code = generate_6_digit_code();
        assert_eq!(code.len(), 6);
        let num: u32 = code.parse().unwrap();
        assert!(num >= 100000 && num <= 999999);
    }
}
