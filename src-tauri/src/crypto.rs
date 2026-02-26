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
        Self {
            secret: "gs_7a8b9c0d1e2f3g4h5i6j7k8l9m0n1o2".to_string(),
        }
    }

    pub fn with_secret(secret: &str) -> Self {
        Self { secret: secret.to_string() }
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

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct SmtpConfig {
    pub host: String,
    pub port: u16,
    pub secure: bool,
    pub username: String,
    pub password: String,
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
    <style>
        body {{ font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }}
        .container {{ max-width: 480px; margin: 0 auto; padding: 40px 20px; }}
        .code-box {{ 
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            border-radius: 12px;
            padding: 30px;
            text-align: center;
            margin: 30px 0;
        }}
        .code {{ 
            font-size: 36px; 
            font-weight: bold; 
            color: white;
            letter-spacing: 8px;
        }}
        .footer {{ color: #666; font-size: 12px; margin-top: 30px; }}
    </style>
</head>
<body>
    <div class="container">
        <h2>验证码通知</h2>
        <p>您好，您正在进行身份验证，请使用以下验证码完成操作：</p>
        <div class="code-box">
            <div class="code">{}</div>
        </div>
        <p>验证码有效期为 <strong>5分钟</strong>，请尽快使用。</p>
        <p>如果您没有进行此操作，请忽略此邮件。</p>
        <div class="footer">
            <p>此邮件由系统自动发送，请勿回复。</p>
        </div>
    </div>
</body>
</html>"#,
        code
    )
}

pub fn get_verification_email_text(code: &str) -> String {
    format!(
        r#"验证码通知

您好，您正在进行身份验证，请使用以下验证码完成操作：

验证码：{}

验证码有效期为 5 分钟，请尽快使用。

如果您没有进行此操作，请忽略此邮件。

此邮件由系统自动发送，请勿回复。"#,
        code
    )
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
