//! Pure translation between harness turn endings and ACP wire blocks.

use dsh_events::TurnEndReason;

/// ACP major protocol version this server speaks.
pub const PROTOCOL_VERSION: u32 = 1;

/// Map a harness turn ending to ACP's terminal reason vocabulary.
pub fn turn_end_to_stop_reason(reason: &TurnEndReason) -> &'static str {
    match reason {
        TurnEndReason::Completed => "end_turn",
        TurnEndReason::MaxTokens => "max_tokens",
        TurnEndReason::Aborted { .. } => "end_turn",
        TurnEndReason::Interrupted => "cancelled",
        TurnEndReason::Blocked | TurnEndReason::Error { .. } => "end_turn",
    }
}

/// One ACP prompt block. Unknown types are rejected by [`prompt_has_unsupported`].
#[derive(Clone, Debug, serde::Deserialize)]
pub struct PromptBlock {
    #[serde(rename = "type")]
    pub kind: String,
    #[serde(default)]
    pub text: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub uri: String,
}

/// Flatten baseline ACP prompt blocks to text.
pub fn acp_prompt_to_text(prompt: &[PromptBlock]) -> String {
    prompt
        .iter()
        .filter_map(|block| match block.kind.as_str() {
            "text" => Some(block.text.clone()),
            "resource_link" => Some(format!(
                "\n[resource_link name={} uri={}]\n",
                serde_json::to_string(&block.name).unwrap_or_default(),
                serde_json::to_string(&block.uri).unwrap_or_default()
            )),
            _ => None,
        })
        .collect()
}

/// Whether a prompt carries content beyond the ACP baseline.
pub fn prompt_has_unsupported(prompt: &[PromptBlock]) -> bool {
    prompt
        .iter()
        .any(|block| block.kind != "text" && block.kind != "resource_link")
}

#[cfg(test)]
mod tests {
    use super::*;
    use dsh_core_types::LlmFailure;
    use dsh_events::AgentCancelCause;

    #[test]
    fn maps_turn_endings() {
        assert_eq!(
            turn_end_to_stop_reason(&TurnEndReason::Completed),
            "end_turn"
        );
        assert_eq!(
            turn_end_to_stop_reason(&TurnEndReason::MaxTokens),
            "max_tokens"
        );
        assert_eq!(
            turn_end_to_stop_reason(&TurnEndReason::Aborted {
                reason: AgentCancelCause::User,
            }),
            "end_turn"
        );
        assert_eq!(
            turn_end_to_stop_reason(&TurnEndReason::Interrupted),
            "cancelled"
        );
        assert_eq!(
            turn_end_to_stop_reason(&TurnEndReason::Error {
                error: LlmFailure::new("failed", "UNKNOWN"),
            }),
            "end_turn"
        );
    }

    #[test]
    fn drops_unsupported_blocks_from_text() {
        let prompt = [PromptBlock {
            kind: "image".into(),
            text: String::new(),
            name: String::new(),
            uri: String::new(),
        }];
        assert!(acp_prompt_to_text(&prompt).is_empty());
        assert!(prompt_has_unsupported(&prompt));
    }
}
