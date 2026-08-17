//! Provider-neutral LLM failures. Policy decides whether they are retryable.

use serde::{Deserialize, Serialize};
use std::fmt;

use crate::brand::ProviderRequestId;

/// Serializable provider or transport failure facts.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LlmFailure {
    pub message: String,
    pub code: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub status: Option<u16>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub provider_retry_after_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub request_id: Option<ProviderRequestId>,
}

impl LlmFailure {
    pub fn new(message: impl Into<String>, code: impl Into<String>) -> Self {
        Self {
            message: message.into(),
            code: code.into(),
            status: None,
            provider_retry_after_ms: None,
            request_id: None,
        }
    }

    pub fn with_status(mut self, status: u16) -> Self {
        self.status = Some(status);
        self
    }
}

/// Typed failure used on the live path. `failure` is what the session log stores.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LlmError {
    pub failure: LlmFailure,
}

impl LlmError {
    pub fn new(message: impl Into<String>, code: impl Into<String>) -> Self {
        Self {
            failure: LlmFailure::new(message, code),
        }
    }

    pub fn from_failure(failure: LlmFailure) -> Self {
        Self { failure }
    }

    pub fn code(&self) -> &str {
        &self.failure.code
    }

    pub fn aborted() -> Self {
        Self::new("request aborted", "ABORTED")
    }

    pub fn missing_credential(name: &str) -> Self {
        Self::new(format!("missing credential `{name}`"), "MISSING_CREDENTIAL")
    }
}

impl fmt::Display for LlmError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{} ({})", self.failure.message, self.failure.code)
    }
}

impl std::error::Error for LlmError {}

/// Flatten an arbitrary error into a chain string under `UNKNOWN`.
pub fn error_chain(error: &dyn std::error::Error) -> String {
    let mut parts = vec![error.to_string()];
    let mut source = error.source();
    while let Some(item) = source {
        parts.push(item.to_string());
        source = item.source();
    }
    parts.join(": ")
}

pub const EMPTY_RESPONSE_CODE: &str = "EMPTY_RESPONSE";
pub const CONTEXT_WINDOW_EXCEEDED_CODE: &str = "CONTEXT_WINDOW_EXCEEDED";
pub const QUOTA_EXCEEDED_CODE: &str = "QUOTA_EXCEEDED";
pub const STREAM_CLOSED_CODE: &str = "STREAM_CLOSED";
pub const MALFORMED_RESPONSE_CODE: &str = "MALFORMED_RESPONSE";
pub const NO_ADAPTER_CODE: &str = "NO_ADAPTER";
pub const UNSUPPORTED_CONTENT_CODE: &str = "UNSUPPORTED_CONTENT";
pub const UNSUPPORTED_REASONING_EFFORT_CODE: &str = "UNSUPPORTED_REASONING_EFFORT";

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn display_includes_code() {
        let err = LlmError::new("nope", "AUTH");
        assert_eq!(err.to_string(), "nope (AUTH)");
    }
}
