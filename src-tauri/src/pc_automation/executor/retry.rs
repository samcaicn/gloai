// Copyright (c) 2026 AIMarketing
//
// Retry policy used between attempts of a single step or
// between iterations of an `ErrorHandler` chain. Doc1 §2.3
// specifies two flavours: a fixed delay and a capped
// exponential back-off. We model both with the same `next_delay`
// entry point so the caller (e.g. `AdaptiveExecutor`) does not
// have to branch on the variant.

use serde::{Deserialize, Serialize};

/// Back-off strategy. Used by the executor loop *and* the
/// error-handler chain (handler-retry). Pure data, no I/O.
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", tag = "kind")]
pub enum RetryPolicy {
    /// Constant delay between attempts.
    Fixed { delay_ms: u64 },
    /// Capped exponential: `base_ms * 2^attempt`, clamped to
    /// `max_ms`. Overflow-safe via saturating math.
    Exponential { base_ms: u64, max_ms: u64 },
}

impl RetryPolicy {
    /// Returns the delay to wait **before** the next attempt.
    ///
    /// * `attempt` is the **zero-based** index of the attempt that
    ///   just failed (so `attempt = 0` is "the first try failed;
    ///   how long should I wait before attempt 1?").
    /// * For `Fixed`, every call returns the same `delay_ms`.
    /// * For `Exponential`, the formula is
    ///   `min(base_ms * 2^attempt, max_ms)`, with a cap on
    ///   `attempt` at 30 to prevent absurd values when callers
    ///   pass a long-running loop counter.
    pub fn next_delay(&self, attempt: u32) -> u64 {
        match self {
            RetryPolicy::Fixed { delay_ms } => *delay_ms,
            RetryPolicy::Exponential { base_ms, max_ms } => {
                // Cap the exponent so a runaway `attempt` cannot
                // turn into a saturating-huge `u64`. 30 bits
                // gives us `2^30 * base_ms` worst case, which is
                // still well above the `max_ms` cap.
                let exp = attempt.min(30);
                let shifted = match exp {
                    0 => *base_ms,
                    1 => base_ms.saturating_mul(2),
                    2 => base_ms.saturating_mul(4),
                    3 => base_ms.saturating_mul(8),
                    4 => base_ms.saturating_mul(16),
                    5 => base_ms.saturating_mul(32),
                    6 => base_ms.saturating_mul(64),
                    7 => base_ms.saturating_mul(128),
                    8 => base_ms.saturating_mul(256),
                    9 => base_ms.saturating_mul(512),
                    10 => base_ms.saturating_mul(1024),
                    11 => base_ms.saturating_mul(2048),
                    12 => base_ms.saturating_mul(4096),
                    13 => base_ms.saturating_mul(8192),
                    14 => base_ms.saturating_mul(16384),
                    15 => base_ms.saturating_mul(32768),
                    16 => base_ms.saturating_mul(65536),
                    17 => base_ms.saturating_mul(131072),
                    18 => base_ms.saturating_mul(262144),
                    19 => base_ms.saturating_mul(524288),
                    20 => base_ms.saturating_mul(1_048_576),
                    21 => base_ms.saturating_mul(2_097_152),
                    22 => base_ms.saturating_mul(4_194_304),
                    23 => base_ms.saturating_mul(8_388_608),
                    24 => base_ms.saturating_mul(16_777_216),
                    25 => base_ms.saturating_mul(33_554_432),
                    26 => base_ms.saturating_mul(67_108_864),
                    27 => base_ms.saturating_mul(134_217_728),
                    28 => base_ms.saturating_mul(268_435_456),
                    29 => base_ms.saturating_mul(536_870_912),
                    _ => base_ms.saturating_mul(1_073_741_824),
                };
                shifted.min(*max_ms)
            }
        }
    }
}

impl Default for RetryPolicy {
    fn default() -> Self {
        // Matches Doc1 §2.3 — start at 1s, double, cap at 30s.
        RetryPolicy::Exponential {
            base_ms: 1_000,
            max_ms: 30_000,
        }
    }
}
