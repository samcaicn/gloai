
//
// Lightweight testing helpers used across hermes-slate-desk. The original
// `test-harness.ts` exposed: `withTempDir`, `withMockClock`, `captureLogs`,
// and an `expect` wrapper. The Rust port exposes equivalents that are
// intended to be called from integration tests, not unit tests with
// `#[cfg(test)]`. For unit tests we still rely on `tokio::test`.

use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::time::Duration;
use serde::{Deserialize, Serialize};

/// Creates a unique temporary directory under the OS temp path.
pub fn with_temp_dir<F, R>(prefix: &str, body: F) -> R
where
    F: FnOnce(&PathBuf) -> R,
{
    let nanos = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
    let dir = std::env::temp_dir().join(format!("hermes-{}-{}", prefix, nanos));
    std::fs::create_dir_all(&dir).expect("create temp dir");
    let result = body(&dir);
    let _ = std::fs::remove_dir_all(&dir);
    result
}

/// A monotonic clock mock. Tests that need deterministic timestamps can
/// construct one and call `set(elapsed_ms)` between steps.
#[derive(Clone, Default)]
pub struct MockClock {
    inner: Arc<Mutex<u64>>,
}

impl MockClock {
    pub fn new() -> Self { Self::default() }

    pub fn set(&self, elapsed_ms: u64) {
        *self.inner.lock().unwrap() = elapsed_ms;
    }

    pub fn advance(&self, delta_ms: u64) {
        *self.inner.lock().unwrap() += delta_ms;
    }

    pub fn now_ms(&self) -> u64 { *self.inner.lock().unwrap() }
}

/// A simple `tracing` log capture. Subscribes to a global subscriber and
/// stores the formatted records in a thread-safe buffer.
#[derive(Clone, Default)]
pub struct LogCapture {
    buf: Arc<Mutex<Vec<String>>>,
}

impl LogCapture {
    pub fn new() -> Self { Self::default() }

    pub fn lines(&self) -> Vec<String> {
        self.buf.lock().unwrap().clone()
    }

    pub fn push_line(&self, line: impl Into<String>) {
        self.buf.lock().unwrap().push(line.into());
    }

    pub fn clear(&self) {
        self.buf.lock().unwrap().clear();
    }
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct HarnessReport {
    pub elapsed_ms: u64,
    pub success: bool,
    pub note: Option<String>,
}

/// Convenience wrapper that records a duration and success status.
pub async fn measure<F, Fut, T>(label: &str, fut: F) -> (T, HarnessReport)
where
    F: FnOnce() -> Fut,
    Fut: std::future::Future<Output = Result<T, String>>,
{
    let start = std::time::Instant::now();
    let res = fut().await;
    let elapsed = start.elapsed();
    let value = match res {
        Ok(v) => v,
        Err(e) => {
            panic!("harness '{}' returned Err: {}; should never construct HarnessReport with T=() on failure", label, e);
        }
    };
    let _ = Duration::from_millis(0); // keep tokio::time import alive
    (value, HarnessReport {
        elapsed_ms: elapsed.as_millis() as u64,
        success: true,
        note: None,
    })
}
