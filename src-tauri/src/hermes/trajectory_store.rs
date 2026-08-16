
//
// Trajectory store: append-only log of agent steps, persisted to the
// `hermes_trajectory_steps` sqlite table when a `HermesDb` is wired
// in, with an in-memory `HashMap` as the hot-path cache / fallback.
//

use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;
use serde::{Deserialize, Serialize};
use chrono::{DateTime, Utc};

use crate::hermes::persistence::{self, HermesDb};

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct TrajectoryStep {
    pub id: String,
    pub session_id: String,
    pub step: u32,
    pub kind: String,
    pub payload: serde_json::Value,
    pub ts: DateTime<Utc>,
}

pub struct TrajectoryStore {
    inner: RwLock<HashMap<String, Vec<TrajectoryStep>>>,
    /// Optional sqlite persistence. When `Some`, every append is
    /// mirrored to `hermes_trajectory_steps` and reads prefer sqlite.
    db: Option<Arc<HermesDb>>,
}

impl Default for TrajectoryStore {
    fn default() -> Self {
        Self { inner: RwLock::new(HashMap::new()), db: None }
    }
}

impl TrajectoryStore {
    pub fn new() -> Self { Self::default() }
    pub fn shared() -> Arc<Self> { Arc::new(Self::default()) }

    /// Construct with a sqlite handle. Existing trajectory rows are
    /// NOT pre-loaded (trajectories can be large); individual
    /// `list(session_id)` calls hit sqlite on demand and memoise
    /// into the in-memory cache.
    pub fn with_db(db: Arc<HermesDb>) -> Arc<Self> {
        Arc::new(Self {
            inner: RwLock::new(HashMap::new()),
            db: Some(db),
        })
    }

    pub async fn append(&self, step: TrajectoryStep) {
        // 先写缓存，再写 sqlite；sqlite 失败则从缓存移除（Bug 5）。
        // list 优先读 sqlite，若先写 sqlite 失败再写缓存，
        // 会导致缓存中的数据对读取不可见。
        if let Some(db) = &self.db {
            let step_id = step.id.clone();
            let session_id = step.session_id.clone();
            self.inner
                .write()
                .await
                .entry(session_id.clone())
                .or_default()
                .push(step.clone());
            if let Err(e) = persistence::insert_trajectory_step(db, &step) {
                log::warn!("[trajectory_store] sqlite insert failed (removing from cache): {}", e);
                let mut g = self.inner.write().await;
                if let Some(v) = g.get_mut(&session_id) {
                    v.retain(|s| s.id != step_id);
                }
            }
        } else {
            let session_id = step.session_id.clone();
            self.inner
                .write()
                .await
                .entry(session_id)
                .or_default()
                .push(step);
        }
    }

    pub async fn list(&self, session_id: &str) -> Vec<TrajectoryStep> {
        if let Some(db) = &self.db {
            match persistence::list_trajectory_steps(db, session_id) {
                Ok(rows) => return rows,
                Err(e) => log::warn!("[trajectory_store] sqlite list failed (using cache): {}", e),
            }
        }
        self.inner.read().await.get(session_id).cloned().unwrap_or_default()
    }

    pub async fn clear(&self, session_id: &str) {
        if let Some(db) = &self.db {
            if let Err(e) = persistence::clear_trajectory_steps(db, session_id) {
                log::warn!("[trajectory_store] sqlite clear failed (cache only): {}", e);
            }
        }
        self.inner.write().await.remove(session_id);
    }

    pub async fn total(&self) -> usize {
        if let Some(db) = &self.db {
            match persistence::count_trajectory_steps(db) {
                Ok(n) => return n,
                Err(e) => log::warn!("[trajectory_store] sqlite total failed (using cache): {}", e),
            }
        }
        self.inner.read().await.values().map(|v| v.len()).sum()
    }
}
