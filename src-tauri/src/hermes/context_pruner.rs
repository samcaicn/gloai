
//
// Context pruner. Drops the oldest messages first until the total
// estimated token count is within `max_tokens`. The TypeScript module
// kept a few "pinned" messages that are never dropped.

use serde::{Deserialize, Serialize};

use super::types::VLMMessage;
use super::context_estimator::estimate_messages;

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct PruneOptions {
    pub max_tokens: usize,
    pub keep_last: usize,
    pub keep_system: bool,
}

impl Default for PruneOptions {
    fn default() -> Self { Self { max_tokens: 4096, keep_last: 4, keep_system: true } }
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct PruneResult {
    pub kept: Vec<VLMMessage>,
    pub dropped: usize,
    pub total_tokens: usize,
}

pub fn prune(messages: Vec<VLMMessage>, options: &PruneOptions) -> PruneResult {
    if messages.is_empty() {
        return PruneResult { kept: Vec::new(), dropped: 0, total_tokens: 0 };
    }
    let total = estimate_messages(&messages).tokens;
    if total <= options.max_tokens {
        return PruneResult { kept: messages, dropped: 0, total_tokens: total };
    }
    // Always keep the last `keep_last` and any "system" messages.
    let mut keep_indices: Vec<bool> = vec![false; messages.len()];
    for (i, m) in messages.iter().enumerate() {
        if options.keep_system && m.role == "system" { keep_indices[i] = true; }
    }
    let n = messages.len();
    for v in &mut keep_indices[n.saturating_sub(options.keep_last)..n] {
        *v = true;
    }
    // Compute how many tokens are forced to stay.
    let mut forced_tokens = 0;
    for (i, m) in messages.iter().enumerate() {
        if keep_indices[i] { forced_tokens += estimate_messages(std::slice::from_ref(m)).tokens; }
    }
    let mut budget = options.max_tokens.saturating_sub(forced_tokens);
    let mut kept: Vec<VLMMessage> = Vec::new();
    let mut dropped = 0usize;
    // Iterate forward: drop oldest messages first (index 0 = oldest)
    for (i, m) in messages.iter().enumerate() {
        if keep_indices[i] { kept.push(m.clone()); continue; }
        let t = estimate_messages(std::slice::from_ref(m)).tokens;
        if budget >= t {
            kept.push(m.clone());
            budget -= t;
        } else {
            dropped += 1;
        }
    }
    // `total_tokens` 返回 *裁剪后* 实际保留的 token 数,
    // 而非裁剪前的原始 total。调用方据此判断裁剪是否达标,
    // 避免 `total_tokens <= max_tokens` 永远为假的错误结论。
    let kept_tokens = estimate_messages(&kept).tokens;
    PruneResult { kept, dropped, total_tokens: kept_tokens }
}
