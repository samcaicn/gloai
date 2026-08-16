// Copyright (c) 2026 MeeJoy

// Encrypted local storage with AES-256-GCM + Argon2id key derivation.
//
// This is the P0 §4 layer that the rest of AIMarketing (skill runtime, model
// catalog, ...) will read/write through. We intentionally keep the API
// surface tiny:
//
// * `EncryptedStorage::derive`    — build a handle from password + fingerprint
// * `EncryptedStorage::encrypt`   — `&[u8] -> Vec<u8>` (prepends nonce)
// * `EncryptedStorage::decrypt`   — `&[u8] -> Zeroizing<Vec<u8>>`
// * `EncryptedStorage::with_decrypted` — callback that wraps a single
//   decrypt/use/zeroize round-trip
// * `EncryptedStorage::wipe_directory` — best-effort scrub of every
//   regular file in a directory (used by `wipe_all_local_data`)
//
// The key is derived once at construction time and held in a
// `Zeroizing<[u8; 32]>` so it is wiped on drop.

use aes_gcm::{
    aead::{Aead, KeyInit},
    Aes256Gcm, Nonce,
};
use argon2::Argon2;
use base64::{engine::general_purpose, Engine as _};
use std::fmt;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use thiserror::Error;
use zeroize::{Zeroize, Zeroizing};

const STORAGE_VERSION: u32 = 1;
const NONCE_LEN: usize = 12;
const KEY_LEN: usize = 32;
const VERSION_LEN: usize = 4;
const OVERHEAD_LEN: usize = VERSION_LEN + NONCE_LEN;

/// Errors raised by the encrypted storage layer.
#[derive(Debug, Error)]
pub enum StorageError {
    #[error("密码长度不合法")]
    InvalidPassword,
    #[error("Argon2id 派生失败: {0}")]
    KeyDerivation(String),
    #[error("加密失败: {0}")]
    Encryption(String),
    #[error("解密失败: {0}")]
    Decryption(String),
    #[error("密文格式错误: {0}")]
    Malformed(String),
    #[error("IO 错误: {0}")]
    Io(String),
}

impl From<std::io::Error> for StorageError {
    fn from(error: std::io::Error) -> Self {
        StorageError::Io(error.to_string())
    }
}

/// The opaque handle. Holds the 32-byte AES key (zeroized on drop).
pub struct EncryptedStorage {
    key: Zeroizing<[u8; KEY_LEN]>,
}

impl fmt::Debug for EncryptedStorage {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        // Never print the key — only the fact that we hold one.
        formatter.debug_struct("EncryptedStorage").finish_non_exhaustive()
    }
}

impl EncryptedStorage {
    /// Derive a 32-byte AES key from a password + a stable hardware
    /// fingerprint using Argon2id with default parameters.
    pub fn derive(password: &str, hardware_fingerprint: &str) -> Result<Self, StorageError> {
        if password.is_empty() {
            return Err(StorageError::InvalidPassword);
        }
        if hardware_fingerprint.is_empty() {
            return Err(StorageError::InvalidPassword);
        }

        let mut key = Zeroizing::new([0u8; KEY_LEN]);
        Argon2::default()
            .hash_password_into(
                password.as_bytes(),
                hardware_fingerprint.as_bytes(),
                key.as_mut_slice(),
            )
            .map_err(|error| StorageError::KeyDerivation(error.to_string()))?;
        Ok(Self { key })
    }

    /// Construct a storage handle from a 32-byte raw key. Used by tests
    /// and by the (future) "load saved key" flow.
    #[allow(dead_code)] // public crypto API; used in subsequent PRs
    pub fn from_raw_key(raw_key: [u8; KEY_LEN]) -> Self {
        Self {
            key: Zeroizing::new(raw_key),
        }
    }

    /// Encrypt `plaintext` and return the framed ciphertext.
    pub fn encrypt(&self, plaintext: &[u8]) -> Result<Vec<u8>, StorageError> {
        let cipher = Aes256Gcm::new_from_slice(self.key.as_slice())
            .map_err(|error| StorageError::Encryption(error.to_string()))?;

        // Random nonce per encryption. `getrandom` is already a
        // transitive dependency (via `aes-gcm` → `getrandom 0.2`).
        let mut nonce_bytes = [0u8; NONCE_LEN];
        getrandom::getrandom(&mut nonce_bytes)
            .map_err(|error| StorageError::Encryption(format!("生成随机 nonce 失败: {}", error)))?;
        let nonce = Nonce::from_slice(&nonce_bytes);

        let ciphertext = cipher
            .encrypt(nonce, plaintext)
            .map_err(|error| StorageError::Encryption(error.to_string()))?;

        let mut output = Vec::with_capacity(OVERHEAD_LEN + ciphertext.len());
        output.extend_from_slice(&STORAGE_VERSION.to_le_bytes());
        output.extend_from_slice(&nonce_bytes);
        output.extend_from_slice(&ciphertext);
        Ok(output)
    }

    /// Decrypt framed ciphertext into a zeroizing buffer. The caller
    /// is responsible for consuming the buffer before letting it go
    /// out of scope (Drop will zeroize).
    pub fn decrypt(&self, framed: &[u8]) -> Result<Zeroizing<Vec<u8>>, StorageError> {
        if framed.len() < OVERHEAD_LEN {
            return Err(StorageError::Malformed(format!(
                "密文过短: 期望至少 {} 字节，得到 {}",
                OVERHEAD_LEN,
                framed.len()
            )));
        }
        let mut version_bytes = [0u8; VERSION_LEN];
        version_bytes.copy_from_slice(&framed[..VERSION_LEN]);
        let version = u32::from_le_bytes(version_bytes);
        if version != STORAGE_VERSION {
            return Err(StorageError::Malformed(format!(
                "不支持的密文版本 {}（仅支持 {}）",
                version, STORAGE_VERSION
            )));
        }
        let nonce = Nonce::from_slice(&framed[VERSION_LEN..OVERHEAD_LEN]);
        let body = &framed[OVERHEAD_LEN..];

        let cipher = Aes256Gcm::new_from_slice(self.key.as_slice())
            .map_err(|error| StorageError::Decryption(error.to_string()))?;
        let plaintext = cipher
            .decrypt(nonce, body)
            .map_err(|error| StorageError::Decryption(error.to_string()))?;
        Ok(Zeroizing::new(plaintext))
    }

    /// Convenience: decrypt → run callback → zeroize.
    ///
    /// The callback returns a `Result<R, E>` and receives a
    /// `&[u8]` view of the zeroizing buffer. The buffer is wiped as
    /// soon as the callback returns, regardless of success or failure.
    #[allow(dead_code)] // public crypto API; used in subsequent PRs
    pub fn with_decrypted<R, E, F>(
        &self,
        framed: &[u8],
        callback: F,
    ) -> Result<R, E>
    where
        E: From<StorageError>,
        F: FnOnce(&[u8]) -> Result<R, E>,
    {
        let mut plaintext = self.decrypt(framed)?;
        let result = callback(plaintext.as_slice());
        plaintext.zeroize();
        result
    }

    /// Encrypt `plaintext` and atomically write to `path`.
    #[allow(dead_code)] // public crypto API; used in subsequent PRs
    pub fn write_encrypted_file(
        &self,
        path: &Path,
        plaintext: &[u8],
    ) -> Result<(), StorageError> {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
        }
        let framed = self.encrypt(plaintext)?;
        let tmp = path.with_extension("enc.tmp");
        // 用 RAII guard 保证任何错误路径都清理 tmp 文件,
        // 避免 .enc.tmp 残留累积。
        struct TmpGuard<'a>(&'a Path, bool);
        impl<'a> Drop for TmpGuard<'a> {
            fn drop(&mut self) {
                if self.1 { let _ = fs::remove_file(self.0); }
            }
        }
        let mut guard = TmpGuard(&tmp, true);
        {
            let mut file = fs::File::create(&tmp)?;
            // 写失败 / sync 失败时 guard 的 Drop 会清理 tmp。
            if let Err(e) = file.write_all(&framed) {
                return Err(StorageError::Io(e.to_string()));
            }
            if let Err(e) = file.sync_all() {
                return Err(StorageError::Io(e.to_string()));
            }
        }
        match fs::rename(&tmp, path) {
            Ok(()) => {
                // rename 成功,tmp 已不存在,关闭 guard 清理。
                guard.1 = false;
                Ok(())
            }
            Err(e) => Err(StorageError::Io(e.to_string())),
        }
    }

    /// Read and decrypt a file from `path`. Returns `Ok(None)` if the
    /// file does not exist (so the caller can distinguish "not yet
    /// provisioned" from "decryption failed").
    #[allow(dead_code)] // public crypto API; used in subsequent PRs
    pub fn read_encrypted_file(
        &self,
        path: &Path,
    ) -> Result<Option<Zeroizing<Vec<u8>>>, StorageError> {
        match fs::read(path) {
            Ok(bytes) => self.decrypt(&bytes).map(Some),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(None),
            Err(error) => Err(StorageError::Io(error.to_string())),
        }
    }

    /// Best-effort wipe: delete every regular file under `directory`
    /// (recursively). Returns the number of files removed.
    ///
    /// We do not recursively remove the directory itself — the caller
    /// may want to keep the empty skeleton in place so that subsequent
    /// `write_encrypted_file` calls succeed.
    pub fn wipe_directory(directory: &Path) -> Result<usize, StorageError> {
        if !directory.exists() {
            return Ok(0);
        }
        let mut removed = 0usize;
        for entry in fs::read_dir(directory)? {
            let entry = entry?;
            let path = entry.path();
            let file_type = entry.file_type()?;
            if file_type.is_dir() {
                removed += Self::wipe_directory(&path)?;
                let _ = fs::remove_dir(&path);
            } else if file_type.is_file() {
                fs::remove_file(&path)?;
                removed += 1;
            }
        }
        Ok(removed)
    }

    /// Encode a cleartext value to base64 (for the `encrypt_data` /
    /// `decrypt_data` Tauri commands that operate on strings instead
    /// of byte buffers).
    pub fn encrypt_base64(&self, plaintext: &[u8]) -> Result<String, StorageError> {
        let framed = self.encrypt(plaintext)?;
        Ok(general_purpose::STANDARD.encode(framed))
    }

    /// Decode a base64 ciphertext, decrypt, and re-encode the
    /// plaintext as UTF-8.
    pub fn decrypt_base64_string(
        &self,
        framed_base64: &str,
    ) -> Result<Zeroizing<String>, StorageError> {
        let framed = general_purpose::STANDARD
            .decode(framed_base64.trim())
            .map_err(|error| StorageError::Malformed(format!("base64 解码失败: {}", error)))?;
        let bytes = self.decrypt(&framed)?;
        let text = std::str::from_utf8(&bytes)
            .map_err(|error| StorageError::Malformed(format!("明文不是有效 UTF-8: {}", error)))?
            .to_string();
        Ok(Zeroizing::new(text))
    }
}

/// Helper used by the Tauri command layer to resolve the "skill" data
/// directory (`<app_data_dir>/skill`). The path is created on demand.
#[allow(dead_code)] // public crypto API; used in subsequent PRs
pub fn ensure_skill_data_dir(app_data_dir: &Path) -> Result<PathBuf, StorageError> {
    let target = app_data_dir.join("skill");
    fs::create_dir_all(&target)?;
    Ok(target)
}

#[cfg(test)]
#[path = "storage_test.rs"]
mod tests;
