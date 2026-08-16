// Copyright (c) 2026 AIMarketing
//
// AIMarketing P1 §2 — Automation execution state machine.
//
// `ExecutionStatus` is the canonical state for an in-flight skill
// execution. The state is shared across the engine, the floating
// panel, and the toast listeners. The state machine transitions
// are:
//
//   Idle
//     -> Running                       (execute_skill starts)
//   Running
//     -> Retrying(0|1|2)              (DOM, then visual, then mixed)
//   Retrying(_)
//     -> Running                       (step succeeded)
//     -> PausedForUser                 (3 strategies failed)
//   PausedForUser
//     -> Running                       (user clicked "继续")
//     -> Failed(reason)                (user clicked "取消")
//   Running
//     -> Completed                     (all steps done)
//     -> Failed(reason)                (fatal error before retry)
//
// We persist a circular-bounded `ExecutionRecord` history (default
// 64 entries) so the floating panel can show the last few runs
// without re-running them.

use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

/// The state of a single execution request.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "phase", rename_all = "snake_case")]
#[derive(Default)]
pub enum ExecutionStatus {
    #[default]
    Idle,
    Running { current_step: usize, total_steps: usize },
    /// 0 = DOM attempt, 1 = visual attempt, 2 = mixed attempt.
    /// Index is the attempt counter for the *current* step.
    Retrying { current_step: usize, attempt: u8 },
    PausedForUser {
        current_step: usize,
        last_error: String,
    },
    /// The engine is paused because single-step mode is enabled or
    /// a breakpoint was hit. The front-end can resume one step via
    /// `step_over` or disable step mode / clear breakpoints.
    PausedForDebug {
        current_step: usize,
        reason: String,
    },
    Completed { total_steps: usize },
    Failed { reason: String },
}


impl ExecutionStatus {
    pub fn is_terminal(&self) -> bool {
        matches!(
            self,
            ExecutionStatus::Completed { .. } | ExecutionStatus::Failed { .. } | ExecutionStatus::Idle
        )
    }

    pub fn is_paused(&self) -> bool {
        matches!(
            self,
            ExecutionStatus::PausedForUser { .. } | ExecutionStatus::PausedForDebug { .. }
        )
    }

    pub fn is_debug_paused(&self) -> bool {
        matches!(self, ExecutionStatus::PausedForDebug { .. })
    }

    pub fn current_step(&self) -> usize {
        match self {
            ExecutionStatus::Running { current_step, .. } => *current_step,
            ExecutionStatus::Retrying { current_step, .. } => *current_step,
            ExecutionStatus::PausedForUser { current_step, .. } => *current_step,
            ExecutionStatus::PausedForDebug { current_step, .. } => *current_step,
            _ => 0,
        }
    }
}

/// One historical record (one row in the floating panel's
/// "execution history" list).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExecutionRecord {
    pub request_id: String,
    pub skill_id: String,
    pub skill_name: String,
    pub started_at: i64,
    pub finished_at: Option<i64>,
    pub final_status: ExecutionStatus,
    pub total_retries: u32,
}

impl ExecutionRecord {
    pub fn new(request_id: String, skill_id: String, skill_name: String) -> Self {
        Self {
            request_id,
            skill_id,
            skill_name,
            started_at: now_unix_secs(),
            finished_at: None,
            final_status: ExecutionStatus::Idle,
            total_retries: 0,
        }
    }
}

/// Centralized, mutex-protected state shared by every automation
/// command. Stored in `app.manage(...)` so commands can fetch it
/// without `Arc<…>` plumbing. The mutex is uncontended in the
/// common case (commands run sequentially per request), so the
/// `std::sync::Mutex` (not `tokio::sync::Mutex`) is correct.
pub struct AutomationState {
    /// Active request id -> status. Only one execution can be in
    /// the `PausedForUser` state at a time, but multiple `Running`
    /// tasks can technically overlap (we don't enforce serial
    /// execution in this iteration).
    pub active: Mutex<HashMap<String, ExecutionStatus>>,
    /// Notification side: when `pause_execution` is invoked, the
    /// task waiting on the per-request `Notify` is woken up.
    pub resume_notify: Mutex<HashMap<String, Arc<tokio::sync::Notify>>>,
    /// Cancellation flag per request. `cancel_execution` sets it
    /// to `true` and the engine checks it at step boundaries.
    pub cancel_flag: Mutex<HashMap<String, bool>>,
    /// Bounded history of completed runs.
    pub history: Mutex<VecDeque<ExecutionRecord>>,
    /// Maximum history size.
    pub history_limit: usize,
    /// Per-request step breakpoints (by step index). Checked by
    /// the engine before each step.
    pub breakpoints: Mutex<HashMap<String, HashSet<usize>>>,
    /// Per-request single-step mode flag. When `true` the engine
    /// pauses before every step and waits for `step_over`.
    pub step_mode: Mutex<HashMap<String, bool>>,
    /// Per-request notifier used by the single-step loop.
    pub step_notify: Mutex<HashMap<String, Arc<tokio::sync::Notify>>>,
}

impl Default for AutomationState {
    fn default() -> Self {
        Self {
            active: Mutex::new(HashMap::new()),
            resume_notify: Mutex::new(HashMap::new()),
            cancel_flag: Mutex::new(HashMap::new()),
            history: Mutex::new(VecDeque::new()),
            history_limit: 64,
            breakpoints: Mutex::new(HashMap::new()),
            step_mode: Mutex::new(HashMap::new()),
            step_notify: Mutex::new(HashMap::new()),
        }
    }
}

impl AutomationState {
    pub fn new() -> Self {
        Self::default()
    }

    /// Update the status of a request. Returns the previous value
    /// so the caller can include it in a transition event.
    pub fn set_status(&self, request_id: &str, status: ExecutionStatus) -> Option<ExecutionStatus> {
        // 锁中毒时恢复(用 into_inner 取回内部数据)而不是 panic,
        // 避免任一持锁线程 panic 后整条自动化链路永久瘫痪。
        let mut active = self.active.lock().unwrap_or_else(|p| p.into_inner());
        active.insert(request_id.to_string(), status)
    }

    pub fn get_status(&self, request_id: &str) -> Option<ExecutionStatus> {
        let active = self.active.lock().unwrap_or_else(|p| p.into_inner());
        active.get(request_id).cloned()
    }

    /// Get or create a `Notify` for the given request id. The
    /// engine awaits on this to suspend until the user (or a
    /// timeout) resumes.
    pub fn resume_handle(&self, request_id: &str) -> std::sync::Arc<tokio::sync::Notify> {
        let mut map = self
            .resume_notify
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        map.entry(request_id.to_string())
            .or_insert_with(|| std::sync::Arc::new(tokio::sync::Notify::new()))
            .clone()
    }

    pub fn request_cancel(&self, request_id: &str) {
        let mut map = self
            .cancel_flag
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        map.insert(request_id.to_string(), true);
        // Also wake any paused task so it observes the cancel.
        if let Some(notify) = map_to_notify(&self.resume_notify, request_id) {
            notify.notify_one();
        }
    }

    pub fn is_cancelled(&self, request_id: &str) -> bool {
        let map = self
            .cancel_flag
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        map.get(request_id).copied().unwrap_or(false)
    }

    pub fn clear_cancel(&self, request_id: &str) {
        let mut map = self
            .cancel_flag
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        map.remove(request_id);
    }

    /// Wake the resume notifier (used by `resume_execution`).
    pub fn notify_resume(&self, request_id: &str) -> bool {
        let map = self
            .resume_notify
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        if let Some(notify) = map.get(request_id) {
            notify.notify_one();
            true
        } else {
            false
        }
    }

    /// Append a finished record to the bounded history. Trims from
    /// the front when over capacity.
    pub fn push_history(&self, record: ExecutionRecord) {
        let mut history = self.history.lock().unwrap_or_else(|p| p.into_inner());
        if history.len() >= self.history_limit {
            history.pop_front();
        }
        history.push_back(record);
    }

    pub fn snapshot_history(&self, limit: u32) -> Vec<ExecutionRecord> {
        let history = self.history.lock().unwrap_or_else(|p| p.into_inner());
        let take = (limit as usize).min(history.len());
        history.iter().rev().take(take).cloned().collect()
    }

    /// Remove all bookkeeping for a request (status, resume
    /// notifier, cancel flag). Called once an execution reaches
    /// a terminal state to keep the maps small.
    pub fn cleanup(&self, request_id: &str) {
        self.active
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .remove(request_id);
        self.resume_notify
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .remove(request_id);
        self.cancel_flag
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .remove(request_id);
        self.breakpoints
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .remove(request_id);
        self.step_mode
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .remove(request_id);
        self.step_notify
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .remove(request_id);
    }

    // --- single-step debugging -------------------------------------------------

    pub fn set_breakpoint(&self, request_id: &str, step_index: usize) -> bool {
        let mut map = self
            .breakpoints
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        map.entry(request_id.to_string())
            .or_default()
            .insert(step_index)
    }

    pub fn clear_breakpoint(&self, request_id: &str, step_index: usize) -> bool {
        let mut map = self
            .breakpoints
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        if let Some(set) = map.get_mut(request_id) {
            set.remove(&step_index)
        } else {
            false
        }
    }

    pub fn clear_all_breakpoints(&self, request_id: &str) {
        self.breakpoints
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .remove(request_id);
    }

    pub fn enable_step_mode(&self, request_id: &str) {
        self.step_mode
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .insert(request_id.to_string(), true);
    }

    pub fn disable_step_mode(&self, request_id: &str) {
        self.step_mode
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .remove(request_id);
        // Wake any engine waiting for step_over so it can run freely.
        self.notify_step(request_id);
    }

    pub fn is_step_mode(&self, request_id: &str) -> bool {
        self.step_mode
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .get(request_id)
            .copied()
            .unwrap_or(false)
    }

    pub fn has_breakpoint(&self, request_id: &str, step_index: usize) -> bool {
        self.breakpoints
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .get(request_id)
            .map(|set| set.contains(&step_index))
            .unwrap_or(false)
    }

    pub fn step_handle(&self, request_id: &str) -> Arc<tokio::sync::Notify> {
        let mut map = self
            .step_notify
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        map.entry(request_id.to_string())
            .or_insert_with(|| Arc::new(tokio::sync::Notify::new()))
            .clone()
    }

    pub fn notify_step(&self, request_id: &str) -> bool {
        let map = self
            .step_notify
            .lock()
            .unwrap_or_else(|p| p.into_inner());
        if let Some(notify) = map.get(request_id) {
            notify.notify_one();
            true
        } else {
            false
        }
    }
}

fn map_to_notify(
    map: &Mutex<HashMap<String, Arc<tokio::sync::Notify>>>,
    key: &str,
) -> Option<Arc<tokio::sync::Notify>> {
    map.lock()
        .unwrap_or_else(|p| p.into_inner())
        .get(key)
        .cloned()
}

pub fn now_unix_secs() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}
