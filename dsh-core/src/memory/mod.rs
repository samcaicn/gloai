// Memory operations — adapted from safeopcapp MemoryOps.
//
// Hot-path cache (RwLock<Vec>) + SQLite persistence.
// Reads prefer SQLite (source of truth), fallback to cache.
// Writes go to cache first, then SQLite.

pub mod ops;

pub use ops::{MemoryDecay, MemoryOps, MemoryStats};
