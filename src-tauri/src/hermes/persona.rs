
//
// Persona management. The original TypeScript module defined a
// `Persona` type (name, avatar, prompt fragments, voice profile) and a
// `PersonaRegistry` to switch between them at runtime. The Rust port
// keeps the data model and a `RwLock` registry.
//
// When an `EncryptedStorage` handle is supplied via
// `with_encrypted_storage`, the full registry (personas map + active
// id) is transparently persisted to `<app_data_dir>/hermes_personas.enc`
// (AES-256-GCM) on every mutation.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;
use tokio::sync::RwLock;
use serde::{Deserialize, Serialize};

use crate::crypto::storage::EncryptedStorage;

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Persona {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub avatar: Option<String>,
    #[serde(default)]
    pub system_prompt: String,
    #[serde(default)]
    pub voice: Option<String>,
    #[serde(default)]
    pub description: Option<String>,
    #[serde(default)]
    pub tags: Vec<String>,
}

/// On-disk envelope for the encrypted persona file. Keeps the
/// personas map and the active-id pointer in a single blob so one
/// atomic write covers both.
#[derive(Serialize, Deserialize, Clone, Debug, Default)]
struct PersonaSnapshot {
    personas: HashMap<String, Persona>,
    active: Option<String>,
}

pub struct PersonaRegistry {
    inner: RwLock<HashMap<String, Persona>>,
    active: RwLock<Option<String>>,
    /// Optional encrypted-file persistence.
    storage: Option<Arc<EncryptedStorage>>,
    /// Path to `hermes_personas.enc`.
    path: Option<PathBuf>,
}

impl Default for PersonaRegistry {
    fn default() -> Self {
        Self {
            inner: RwLock::new(HashMap::new()),
            active: RwLock::new(None),
            storage: None,
            path: None,
        }
    }
}

impl PersonaRegistry {
    pub fn new() -> Self { Self::default() }

    pub fn shared() -> Arc<Self> { Arc::new(Self::default()) }

    /// Construct with an encrypted-storage handle. If the encrypted
    /// file already exists at `path`, the registry is decrypted and
    /// loaded; otherwise we start from an empty registry.
    pub fn with_encrypted_storage(
        storage: Option<Arc<EncryptedStorage>>,
        path: PathBuf,
    ) -> Arc<Self> {
        let mut snapshot = PersonaSnapshot::default();
        if let Some(ref s) = storage {
            match s.read_encrypted_file(&path) {
                Ok(Some(plaintext)) => {
                    match serde_json::from_slice::<PersonaSnapshot>(&plaintext) {
                        Ok(snap) => snapshot = snap,
                        Err(e) => {
                            log::warn!("[persona] failed to parse decrypted snapshot, starting fresh: {}", e);
                        }
                    }
                }
                Ok(None) => { /* first run */ }
                Err(e) => {
                    log::warn!("[persona] failed to read encrypted snapshot, starting fresh: {}", e);
                }
            }
        }
        Arc::new(Self {
            inner: RwLock::new(snapshot.personas),
            active: RwLock::new(snapshot.active),
            storage,
            path: Some(path),
        })
    }

    pub async fn register(&self, persona: Persona) {
        self.inner.write().await.insert(persona.id.clone(), persona);
        self.persist().await;
    }

    pub async fn get(&self, id: &str) -> Option<Persona> {
        self.inner.read().await.get(id).cloned()
    }

    pub async fn list(&self) -> Vec<Persona> {
        self.inner.read().await.values().cloned().collect()
    }

    pub async fn remove(&self, id: &str) -> bool {
        let removed = self.inner.write().await.remove(id).is_some();
        // Clear active pointer if it pointed at the removed persona.
        if removed {
            let mut active = self.active.write().await;
            if active.as_deref() == Some(id) {
                *active = None;
            }
            drop(active);
            self.persist().await;
        }
        removed
    }

    pub async fn activate(&self, id: &str) -> Result<Persona, String> {
        let p = self.inner.read().await.get(id).cloned()
            .ok_or_else(|| "persona not found".to_string())?;
        *self.active.write().await = Some(id.to_string());
        self.persist().await;
        Ok(p)
    }

    pub async fn active(&self) -> Option<Persona> {
        let id = self.active.read().await.clone()?;
        self.inner.read().await.get(&id).cloned()
    }

    /// Serialize the full registry (personas + active id) and write
    /// the encrypted blob atomically. Best-effort: errors are logged
    /// but don't roll back the in-memory mutation.
    async fn persist(&self) {
        let (Some(storage), Some(path)) = (&self.storage, &self.path) else {
            return;
        };
        let snapshot = PersonaSnapshot {
            personas: self.inner.read().await.clone(),
            active: self.active.read().await.clone(),
        };
        let json = match serde_json::to_vec(&snapshot) {
            Ok(bytes) => bytes,
            Err(e) => {
                log::warn!("[persona] serialize failed, skipping persist: {}", e);
                return;
            }
        };
        if let Err(e) = storage.write_encrypted_file(path, &json) {
            log::warn!("[persona] encrypted write failed: {}", e);
        }
    }
}

pub type SharedPersonaRegistry = Arc<PersonaRegistry>;
