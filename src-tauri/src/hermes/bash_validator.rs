
//
// Bash command validator. The TypeScript module parsed the command
// into tokens, then evaluated a small set of safety rules (no
// recursive `rm -rf /`, no `mkfs`, no `dd if=... of=/dev/...`).
// The Rust port re-implements the rules with a regex-based scanner.

use once_cell::sync::Lazy;
use regex::Regex;
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
// 序列化使用 camelCase，与前端 TypeScript / JS 命名习惯一致；
// 之前用 snake_case 会让前端按 OpenAI/JSON 习惯写
// `if (verdict === "needsApproval")` 时永远命中不到 NeedsApproval 分支，
// 静默绕过审批流。
#[serde(rename_all = "camelCase")]
pub enum BashVerdict {
    Allow,
    Deny,
    NeedsApproval,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct BashValidation {
    pub command: String,
    pub verdict: BashVerdict,
    pub reason: Option<String>,
}

static DANGEROUS: Lazy<Vec<Regex>> = Lazy::new(|| {
    vec![
        Regex::new(r"\brm\s+-rf\s+/\s*$").unwrap(),
        Regex::new(r"\bmkfs(\.[a-z0-9]+)?\b").unwrap(),
        Regex::new(r"\bdd\s+if=.*\s+of=/dev/").unwrap(),
        Regex::new(r":\(\)\s*\{.*\};:\s*#").unwrap(), // fork bomb
    ]
});

static NEEDS_APPROVAL: Lazy<Vec<Regex>> = Lazy::new(|| {
    vec![
        Regex::new(r"\bsudo\b").unwrap(),
        Regex::new(r"\bchmod\s+777\b").unwrap(),
        Regex::new(r"\bchown\s+-R\b").unwrap(),
    ]
});

pub fn validate(command: &str) -> BashValidation {
    for re in DANGEROUS.iter() {
        if re.is_match(command) {
            return BashValidation { command: command.to_string(), verdict: BashVerdict::Deny, reason: Some(format!("matched dangerous pattern: {}", re.as_str())) };
        }
    }
    for re in NEEDS_APPROVAL.iter() {
        if re.is_match(command) {
            return BashValidation { command: command.to_string(), verdict: BashVerdict::NeedsApproval, reason: Some(format!("requires approval: {}", re.as_str())) };
        }
    }
    BashValidation { command: command.to_string(), verdict: BashVerdict::Allow, reason: None }
}

#[tauri::command]
pub async fn hermes_bash_validate(command: String) -> Result<BashValidation, String> {
    Ok(validate(&command))
}
