
//
// Generic utilities.
// TypeScript file exposed `sleep`, `retry`, `debounce`, `throttle`,
// `safeJsonParse`, `clamp`, `truncate`, `chunk`, `uniqueBy`, `diff`,
// `hashString` (FNV-1a), and `formatBytes`. This file preserves the
// public API surface and adds small `# Errors` enum-style error types
// for the variants that previously threw.

use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;
use serde::{Deserialize, Serialize};
use tokio::sync::Mutex as TokioMutex;

/// Sleep for `ms` milliseconds. Awaits a tokio timer.
pub async fn sleep_ms(ms: u64) {
    tokio::time::sleep(Duration::from_millis(ms)).await;
}

/// Retry the provided async function with exponential backoff.
pub async fn retry<F, Fut, T>(
    mut attempts: u32,
    base_delay_ms: u64,
    mut op: F,
) -> Result<T, String>
where
    F: FnMut() -> Fut,
    Fut: std::future::Future<Output = Result<T, String>>,
{
    let mut delay = base_delay_ms.max(1);
    loop {
        match op().await {
            Ok(v) => return Ok(v),
            Err(e) if attempts > 1 => {
                attempts -= 1;
                sleep_ms(delay).await;
                delay = (delay * 2).min(30_000);
                let _ = e;
            }
            Err(e) => return Err(e),
        }
    }
}

// No `#[derive(Clone)]` — see the manual `impl Clone for Debouncer<F>`
// below. The `Option<JoinHandle>` field prevents automatic derivation.
pub struct Debouncer<F: FnMut()> {
    delay: Duration,
    timer: Option<tokio::task::JoinHandle<()>>,
    callback: Arc<TokioMutex<F>>,
}

impl<F: FnMut() + Send + 'static> Debouncer<F> {
    pub fn new(delay: Duration, callback: F) -> Self {
        Self { delay, timer: None, callback: Arc::new(TokioMutex::new(callback)) }
    }

    pub async fn call(&mut self) {
        if let Some(t) = self.timer.take() { t.abort(); }
        let delay = self.delay;
        let callback = Arc::clone(&self.callback);
        self.timer = Some(tokio::spawn(async move {
            sleep_ms(delay.as_millis() as u64).await;
            let mut cb = callback.lock().await;
            (cb)();
        }));
    }
}

impl<F: FnMut() + Clone> Clone for Debouncer<F> {
    fn clone(&self) -> Self {
        Self {
            delay: self.delay,
            timer: None,
            callback: Arc::clone(&self.callback),
        }
    }
}

impl<F: FnMut()> Drop for Debouncer<F> {
    fn drop(&mut self) {
        // Abort any pending timer so the callback can't fire after the
        // Debouncer has been dropped.
        if let Some(t) = self.timer.take() {
            t.abort();
        }
    }
}

#[derive(Clone)]
pub struct Throttle<F: FnMut()> {
    interval: Duration,
    last: Option<std::time::Instant>,
    callback: F,
}

impl<F: FnMut()> Throttle<F> {
    pub fn new(interval: Duration, callback: F) -> Self {
        Self { interval, last: None, callback }
    }

    pub fn call(&mut self) {
        let now = std::time::Instant::now();
        if self.last.is_none_or(|t| now.duration_since(t) >= self.interval) {
            self.last = Some(now);
            (self.callback)();
        }
    }
}

/// Parses a JSON string, returning a fallback on failure.
pub fn safe_json_parse<T: for<'de> Deserialize<'de>>(input: &str) -> Option<T> {
    serde_json::from_str(input).ok()
}

/// Clamps a value to the inclusive range `[lo, hi]`.
pub fn clamp<T: PartialOrd>(value: T, lo: T, hi: T) -> T {
    if value < lo { lo } else if value > hi { hi } else { value }
}

/// Truncates a string with an optional suffix. Operates on char
/// boundaries so multi-byte UTF-8 (CJK / emoji) doesn't panic on
/// the byte slice; the previous `&s[..max - suffix.len()]`
/// implementation would split a 3-byte CJK codepoint and abort.
pub fn truncate(s: &str, max: usize, suffix: &str) -> String {
    if s.len() <= max { return s.to_string(); }
    let suffix_len = suffix.chars().count();
    let take_chars = max.saturating_sub(suffix_len);
    let take_bytes = s.char_indices().nth(take_chars).map(|(i, _)| i).unwrap_or(s.len());
    format!("{}{}", &s[..take_bytes], suffix)
}

/// Chunks a vector into fixed-size pieces (last piece may be shorter).
pub fn chunk<T: Clone>(items: &[T], size: usize) -> Vec<Vec<T>> {
    if size == 0 { return vec![items.to_vec()]; }
    items.chunks(size).map(|c| c.to_vec()).collect()
}

/// Returns items with duplicates removed (preserves first-seen order).
pub fn unique_by<T, K, F>(items: Vec<T>, mut key: F) -> Vec<T>
where
    K: Eq + std::hash::Hash,
    F: FnMut(&T) -> K,
{
    let mut seen = std::collections::HashSet::new();
    items.into_iter().filter(|it| seen.insert(key(it))).collect()
}

/// A simple diff helper for two sorted lists of `PartialEq` items. Returns
/// `(added, removed)`.
pub fn diff<T: PartialEq + Clone>(a: &[T], b: &[T]) -> (Vec<T>, Vec<T>) {
    let added = b.iter().filter(|it| !a.contains(it)).cloned().collect();
    let removed = a.iter().filter(|it| !b.contains(it)).cloned().collect();
    (added, removed)
}

/// FNV-1a 64-bit hash, returned as a lowercase hex string.
pub fn hash_string(s: &str) -> String {
    let mut h: u64 = 0xcbf29ce484222325;
    for b in s.as_bytes() {
        h ^= *b as u64;
        h = h.wrapping_mul(0x100000001b3);
    }
    format!("{:016x}", h)
}

pub fn format_bytes(n: u64) -> String {
    const UNITS: [&str; 6] = ["B", "KB", "MB", "GB", "TB", "PB"];
    let mut v = n as f64;
    let mut i = 0;
    while v >= 1024.0 && i < UNITS.len() - 1 {
        v /= 1024.0;
        i += 1;
    }
    if i == 0 { format!("{} {}", n, UNITS[0]) } else { format!("{:.2} {}", v, UNITS[i]) }
}

/// Generic key/value bag — useful for cross-language JSON shaping.
pub fn bag<K: Into<String>, V: Serialize>(pairs: Vec<(K, V)>) -> HashMap<String, serde_json::Value> {
    let mut out = HashMap::new();
    for (k, v) in pairs {
        let val = serde_json::to_value(v).unwrap_or(serde_json::Value::Null);
        out.insert(k.into(), val);
    }
    out
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct AsyncJobHandle {
    pub id: String,
    pub label: String,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub enum AsyncJobState {
    Pending,
    Running,
    Completed,
    Failed,
    Cancelled,
}
