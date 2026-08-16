
//
// Detects "dilemmas" in an agent's situation: cases where the model is
// uncertain, two tools are equally good, or the user request is
// ambiguous. The TypeScript module used heuristic rules + an LLM
// call. The Rust port implements the heuristic rules and exposes
// `detect()` returning a list of `Dilemma`s.

use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
pub enum DilemmaType {
    Ambiguous,
    ToolChoice,
    Refusal,
    ConflictingGoals,
    UnknownUser,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq, Hash)]
pub struct Dilemma {
    pub kind: DilemmaType,
    pub summary: String,
    pub suggestion: Option<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct DilemmaConfig {
    pub enable_ambiguous_check: bool,
    pub enable_tool_choice_check: bool,
    pub min_alternatives: usize,
}

impl Default for DilemmaConfig {
    fn default() -> Self {
        Self { enable_ambiguous_check: true, enable_tool_choice_check: true, min_alternatives: 2 }
    }
}

#[derive(Default)]
pub struct DilemmaDetector {
    cfg: DilemmaConfig,
}


impl DilemmaDetector {
    pub fn new(cfg: DilemmaConfig) -> Self { Self { cfg } }

    pub fn detect(&self, text: &str, available_tools: &[String], _user_history_len: usize) -> Vec<Dilemma> {
        let mut out = Vec::new();
        let lc = text.to_lowercase();
        if self.cfg.enable_ambiguous_check && (lc.contains("maybe") || lc.contains("perhaps") || lc.ends_with('?')) {
            out.push(Dilemma {
                kind: DilemmaType::Ambiguous,
                summary: "User request contains uncertainty markers".into(),
                suggestion: Some("Ask one clarifying question".into()),
            });
        }
        if self.cfg.enable_tool_choice_check && available_tools.len() >= self.cfg.min_alternatives {
            out.push(Dilemma {
                kind: DilemmaType::ToolChoice,
                summary: format!("{} candidate tools available", available_tools.len()),
                suggestion: Some("Use the tool with highest prior success rate".into()),
            });
        }
        if lc.contains("don't") && lc.contains("do") {
            out.push(Dilemma {
                kind: DilemmaType::ConflictingGoals,
                summary: "Request contains conflicting instructions".into(),
                suggestion: Some("Re-read the request and pick the most recent intent".into()),
            });
        }
        out
    }
}

pub static DILEMMA_DETECTOR: std::sync::LazyLock<DilemmaDetector> = std::sync::LazyLock::new(DilemmaDetector::default);
