
//
// Reflection step: produce a structured "self-critique" from a recent
// transcript. The TypeScript module called the LLM with a fixed
// reflection prompt. The Rust port captures the same prompt as a
// constant and offers a `reflect()` function that the main agent
// runtime can call.

use serde::{Deserialize, Serialize};

pub const REFLECTION_PROMPT: &str = "You are reflecting on the previous turn of an agent conversation. \
    Identify: (1) whether the agent's last action achieved the user's goal, (2) any new constraints \
    or preferences revealed, and (3) one concrete improvement the agent should make on its next turn. \
    Respond in plain text with three short paragraphs.";

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct Reflection {
    pub outcome: String,
    pub new_constraints: Vec<String>,
    pub improvement: String,
    pub created_at: i64,
}

pub fn empty_reflection() -> Reflection {
    Reflection { created_at: chrono::Utc::now().timestamp_millis(), ..Default::default() }
}

pub fn parse_reflection(text: &str) -> Reflection {
    let mut r = empty_reflection();
    let mut section = 0usize;
    for line in text.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() { continue; }
        match section {
            0 => r.outcome.push_str(trimmed),
            1 => r.new_constraints.push(trimmed.to_string()),
            2 => r.improvement.push_str(trimmed),
            _ => {}
        }
        if trimmed.ends_with('.') { section = (section + 1).min(2); }
    }
    r
}
