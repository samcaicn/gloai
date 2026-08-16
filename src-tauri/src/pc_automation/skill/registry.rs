// Copyright (c) 2026 tupAI
//
// UIRPA in-memory skill registry.
//
// The "hot path" of skill execution looks like:
//
//   * Front-end wants to execute a skill by id.
//   * The registry has an in-memory `HashMap<skill_id, Skill>`
//     populated from `LocalSkillStorage`.
//   * The executor pulls the `Skill` straight out of the map —
//     no disk I/O on the execute path.
//   * A background task (`refresh`) re-reads the disk to catch
//     changes from other processes (e.g. a future CLI importer
//     or the front-end `import` button while the executor is
//     idle).
//
// Writes (insert / delete / update_success_rate) go through
// the storage layer first; the in-memory map is updated only
// after the disk write succeeds. That keeps the two views
// consistent at the price of one extra round-trip per CRUD op.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::RwLock;

use crate::pc_automation::skill::storage::LocalSkillStorage;
use crate::pc_automation::skill::types::{Skill, SkillMeta};

pub struct SkillRegistry {
    storage: LocalSkillStorage,
    cache: RwLock<HashMap<String, Skill>>,
}

impl SkillRegistry {
    /// Bind the registry to a storage dir. The in-memory cache
    /// starts empty — callers should call `refresh()` to load
    /// what's already on disk.
    pub fn new(app_data_dir: &std::path::Path) -> Self {
        Self {
            storage: LocalSkillStorage::new(app_data_dir),
            cache: RwLock::new(HashMap::new()),
        }
    }

    /// Direct access to the underlying storage.
    pub fn storage(&self) -> &LocalSkillStorage {
        &self.storage
    }

    /// Encrypt + persist a skill, then put it in the in-memory
    /// cache. Returns the on-disk path.
    pub fn insert(&self, skill: &Skill, password: &[u8]) -> Result<PathBuf, String> {
        let path = self.storage.store(skill, password)?;
        let mut cache = self.cache.write().map_err(|e| format!("cache lock: {}", e))?;
        cache.insert(skill.skill_id.clone(), skill.clone());
        Ok(path)
    }

    /// Look up a skill by id from the in-memory cache.
    pub fn get(&self, skill_id: &str) -> Option<Skill> {
        let cache = self.cache.read().ok()?;
        cache.get(skill_id).cloned()
    }

    /// Read the metadata list from disk (decrypt metadata only,
    /// not the body). Does *not* update the in-memory cache.
    pub fn list(&self, password: &[u8]) -> Result<Vec<SkillMeta>, String> {
        self.storage.list(password)
    }

    /// Remove a skill from both the cache and disk.
    pub fn delete(&self, skill_id: &str) -> Result<(), String> {
        self.storage.delete(skill_id)?;
        let mut cache = self.cache.write().map_err(|e| format!("cache lock: {}", e))?;
        cache.remove(skill_id);
        Ok(())
    }

    /// Bump a skill's `success_rate` (0.0..=1.0) and updated_at
    /// in memory. The new value is **not** re-encrypted to disk
    /// by this method — that is the executor's job once it has
    /// decided the run succeeded / failed. The point of the
    /// in-memory mutation is to make the next `list()` /
    /// `get()` see the fresh number.
    pub fn update_success_rate(&self, skill_id: &str, new_rate: f32) -> Result<(), String> {
        let mut cache = self.cache.write().map_err(|e| format!("cache lock: {}", e))?;
        let skill = cache.get_mut(skill_id).ok_or_else(|| {
            format!("update_success_rate: skill_id '{}' not in cache", skill_id)
        })?;
        skill.success_rate = new_rate.clamp(0.0, 1.0);
        skill.updated_at = crate::pc_automation::skill::storage::now_utc();
        Ok(())
    }

    /// Reload every `.enc` file in the storage dir into the
    /// in-memory cache. Cheap because the password is used
    /// exactly once per file to decrypt the metadata + body,
    /// and the cache is then swappable in O(n).
    pub fn refresh(&self, password: &[u8]) -> Result<usize, String> {
        let mut next: HashMap<String, Skill> = HashMap::new();
        for meta in self.storage.list(password)? {
            let path = self.storage.path_for(&meta.skill_id);
            match self.storage.load(&path, password) {
                Ok(skill) => {
                    next.insert(skill.skill_id.clone(), skill);
                }
                Err(e) => {
                    // A single bad file should not poison the
                    // whole refresh. Log via eprintln so the
                    // dev-mode log watcher picks it up.
                    eprintln!(
                        "[skill_registry] refresh skipped {}: {}",
                        meta.skill_id, e
                    );
                }
            }
        }
        let count = next.len();
        let mut cache = self.cache.write().map_err(|e| format!("cache lock: {}", e))?;
        *cache = next;
        Ok(count)
    }
}
