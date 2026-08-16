
//
// User profile. A flat key/value bag with a few well-known fields
// (nickname, language, timezone) plus arbitrary `prefs`.
//
// When an `EncryptedStorage` handle is supplied via
// `with_encrypted_storage`, the profile is transparently persisted
// to `<app_data_dir>/hermes_profile.enc` (AES-256-GCM). Every
// `update` / `replace` re-encrypts the whole profile atomically.

use std::path::PathBuf;
use std::sync::Arc;
use tokio::sync::RwLock;
use serde::{Deserialize, Serialize};

use crate::crypto::storage::EncryptedStorage;

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct Profile {
    #[serde(default)]
    pub nickname: Option<String>,
    #[serde(default)]
    pub language: Option<String>,
    #[serde(default)]
    pub timezone: Option<String>,
    #[serde(default)]
    pub email: Option<String>,
    #[serde(default)]
    pub avatar: Option<String>,
    #[serde(default)]
    pub prefs: HashMap<String, serde_json::Value>,
    #[serde(default)]
    pub updated_at: Option<String>,
}

use std::collections::HashMap;

pub struct ProfileStore {
    inner: RwLock<Profile>,
    /// Optional encrypted-file persistence. `None` in tests / when
    /// the hardware fingerprint is unavailable.
    storage: Option<Arc<EncryptedStorage>>,
    /// Path to `hermes_profile.enc`. Only meaningful when `storage`
    /// is `Some`.
    path: Option<PathBuf>,
}

impl Default for ProfileStore {
    fn default() -> Self {
        Self {
            inner: RwLock::new(Profile::default()),
            storage: None,
            path: None,
        }
    }
}

impl ProfileStore {
    pub fn new() -> Self { Self::default() }
    pub fn shared() -> Arc<Self> { Arc::new(Self::default()) }

    /// Construct with an encrypted-storage handle. If the encrypted
    /// file already exists at `path`, the profile is decrypted and
    /// loaded into the in-memory state; otherwise we start from a
    /// blank profile and the first `update` / `replace` will create
    /// the file.
    pub fn with_encrypted_storage(
        storage: Option<Arc<EncryptedStorage>>,
        path: PathBuf,
    ) -> Arc<Self> {
        let mut profile = Profile::default();
        if let Some(ref s) = storage {
            match s.read_encrypted_file(&path) {
                Ok(Some(plaintext)) => {
                    match serde_json::from_slice::<Profile>(&plaintext) {
                        Ok(p) => profile = p,
                        Err(e) => {
                            log::warn!("[profile] failed to parse decrypted profile, starting fresh: {}", e);
                        }
                    }
                }
                Ok(None) => {
                    // First run — no file yet. Leave profile as default.
                }
                Err(e) => {
                    log::warn!("[profile] failed to read encrypted profile, starting fresh: {}", e);
                }
            }
        }
        Arc::new(Self {
            inner: RwLock::new(profile),
            storage,
            path: Some(path),
        })
    }

    pub async fn current(&self) -> Profile { self.inner.read().await.clone() }

    pub async fn update<F: FnOnce(&mut Profile)>(&self, f: F) -> Profile {
        let mut g = self.inner.write().await;
        f(&mut g);
        g.updated_at = Some(chrono::Utc::now().to_rfc3339());
        let snapshot = g.clone();
        // Drop the guard before doing (potentially blocking) file I/O
        // so other readers aren't blocked behind the write.
        drop(g);
        self.persist(&snapshot);
        snapshot
    }

    pub async fn replace(&self, profile: Profile) {
        let snapshot = profile;
        *self.inner.write().await = snapshot.clone();
        self.persist(&snapshot);
    }

    /// Synchronous encrypted-file write. `write_encrypted_file` is
    /// atomic (tmp + rename) and the payload is small (a few KB),
    /// so a brief block on disk I/O is acceptable inside the async
    /// caller. Errors are logged but not surfaced — a failed
    /// persistence write doesn't roll back the in-memory update.
    fn persist(&self, profile: &Profile) {
        let (Some(storage), Some(path)) = (&self.storage, &self.path) else {
            return;
        };
        let json = match serde_json::to_vec(profile) {
            Ok(bytes) => bytes,
            Err(e) => {
                log::warn!("[profile] serialize failed, skipping persist: {}", e);
                return;
            }
        };
        if let Err(e) = storage.write_encrypted_file(path, &json) {
            log::warn!("[profile] encrypted write failed: {}", e);
        }
    }
}
