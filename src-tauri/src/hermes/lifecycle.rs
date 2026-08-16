
//
// Agent lifecycle phases: `boot -> ready -> thinking -> acting -> done
// -> idle` (or `error`). The TypeScript module also exposed hooks to
// subscribe to phase transitions. The Rust port keeps the enum and a
// small state machine.

use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
#[derive(Default)]
pub enum LifecyclePhase {
    Boot,
    Ready,
    Thinking,
    Acting,
    Done,
    #[default]
    Idle,
    Error,
    Stopped,
}


#[derive(Default)]
pub struct Lifecycle {
    current: std::sync::Mutex<LifecyclePhase>,
    history: std::sync::Mutex<Vec<(LifecyclePhase, i64)>>,
}

impl Lifecycle {
    pub fn new() -> Self { Self::default() }

    pub fn current(&self) -> LifecyclePhase { *self.current.lock().unwrap_or_else(|e| e.into_inner()) }

    pub fn transition(&self, to: LifecyclePhase) -> LifecyclePhase {
        let mut cur = self.current.lock().unwrap_or_else(|e| e.into_inner());
        let prev = *cur;
        *cur = to;
        self.history.lock().unwrap_or_else(|e| e.into_inner()).push((to, chrono::Utc::now().timestamp_millis()));
        prev
    }

    pub fn history(&self) -> Vec<(LifecyclePhase, i64)> { self.history.lock().unwrap_or_else(|e| e.into_inner()).clone() }

    pub fn is_terminal(&self) -> bool {
        matches!(self.current(), LifecyclePhase::Done | LifecyclePhase::Idle | LifecyclePhase::Error | LifecyclePhase::Stopped)
    }
}
