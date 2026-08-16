
//
// Rough token-count estimator. The TypeScript module used the
// `tiktoken` BPE table for OpenAI models. The Rust port uses a simple
// heuristic: 1 token ≈ 4 characters for English text, with a small
// adjustment for non-ASCII bytes. This is "good enough" for context
// pruning decisions in hermes.

use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct Estimate {
    pub chars: usize,
    pub tokens: usize,
}

pub fn estimate(text: &str) -> Estimate {
    let mut chars = 0usize;
    let mut non_ascii = 0usize;
    for c in text.chars() {
        chars += 1;
        if c as u32 > 127 { non_ascii += 1; }
    }
    let bytes = text.len();
    // bytes/3.5 already includes all bytes; add extra cost for non-ASCII
    // since they tend to tokenize into more tokens than ASCII.
    let ascii_bytes = bytes.saturating_sub(non_ascii * 3);
    let tokens = ((ascii_bytes as f32 / 4.0) + (non_ascii as f32 * 1.5)) as usize;
    Estimate { chars, tokens }
}

pub fn estimate_messages(messages: &[super::types::VLMMessage]) -> Estimate {
    let mut total = Estimate::default();
    for m in messages {
        let e = estimate(&m.content);
        total.chars += e.chars + 8; // role overhead
        total.tokens += e.tokens + 4;
    }
    total
}
