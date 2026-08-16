
//
// A simple LRU (Least Recently Used) cache ported from the aicoop/core package.
// The original used a Map's iteration order to track recency. This Rust version
// uses a `Mutex<HashMap<K, (V, u64)>>` with monotonic access counters — adequate
// for caching config lookups, model catalogs, and persona fragments where the
// total entry count is small (<10k) and the lock is held only briefly.

use std::collections::HashMap;
use std::hash::Hash;
use std::sync::Mutex;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy)]
pub struct LruOptions {
    pub max_entries: usize,
}

impl Default for LruOptions {
    fn default() -> Self {
        Self { max_entries: 256 }
    }
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct LruEntry<V> {
    pub value: V,
    pub last_used_seq: u64,
}

pub struct LruMap<K, V> {
    inner: Mutex<HashMap<K, LruEntry<V>>>,
    options: LruOptions,
    seq: Mutex<u64>,
    hits: Mutex<u64>,
    misses: Mutex<u64>,
    evictions: Mutex<u64>,
}

impl<K: Eq + Hash + Clone, V: Clone> LruMap<K, V> {
    pub fn new(options: LruOptions) -> Self {
        Self {
            inner: Mutex::new(HashMap::new()),
            options,
            seq: Mutex::new(0),
            hits: Mutex::new(0),
            misses: Mutex::new(0),
            evictions: Mutex::new(0),
        }
    }

    pub fn get(&self, key: &K) -> Option<V> {
        let mut guard = self.inner.lock().ok()?;
        if let Some(entry) = guard.get_mut(key) {
            let seq = {
                let mut s = self.seq.lock().ok()?;
                *s += 1;
                *s
            };
            entry.last_used_seq = seq;
            *self.hits.lock().ok()? += 1;
            Some(entry.value.clone())
        } else {
            *self.misses.lock().ok()? += 1;
            None
        }
    }

    pub fn set(&self, key: K, value: V) {
        let mut guard = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        let seq = {
            let mut s = self.seq.lock().unwrap_or_else(|e| e.into_inner());
            *s += 1;
            *s
        };
        guard.insert(key, LruEntry { value, last_used_seq: seq });

        if guard.len() > self.options.max_entries {
            if let Some(victim_key) = guard
                .iter()
                .min_by_key(|(_, e)| e.last_used_seq)
                .map(|(k, _)| k.clone())
            {
                guard.remove(&victim_key);
                *self.evictions.lock().unwrap_or_else(|e| e.into_inner()) += 1;
            }
        }
    }

    pub fn delete(&self, key: &K) -> bool {
        self.inner.lock().map(|mut g| g.remove(key).is_some()).unwrap_or(false)
    }

    pub fn clear(&self) {
        if let Ok(mut g) = self.inner.lock() { g.clear(); }
        *self.hits.lock().unwrap_or_else(|e| e.into_inner()) = 0;
        *self.misses.lock().unwrap_or_else(|e| e.into_inner()) = 0;
        *self.evictions.lock().unwrap_or_else(|e| e.into_inner()) = 0;
    }

    pub fn len(&self) -> usize {
        self.inner.lock().map(|g| g.len()).unwrap_or(0)
    }

    pub fn is_empty(&self) -> bool { self.len() == 0 }

    pub fn stats(&self) -> LruStats {
        LruStats {
            size: self.len(),
            capacity: self.options.max_entries,
            hits: *self.hits.lock().unwrap_or_else(|e| e.into_inner()),
            misses: *self.misses.lock().unwrap_or_else(|e| e.into_inner()),
            evictions: *self.evictions.lock().unwrap_or_else(|e| e.into_inner()),
        }
    }

    pub fn keys(&self) -> Vec<K> {
        self.inner.lock().map(|g| g.keys().cloned().collect()).unwrap_or_default()
    }
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct LruStats {
    pub size: usize,
    pub capacity: usize,
    pub hits: u64,
    pub misses: u64,
    pub evictions: u64,
}
