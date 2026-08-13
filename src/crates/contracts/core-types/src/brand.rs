//! Opaque identifiers. Newtypes, not strings, at crate boundaries.

use serde::{Deserialize, Serialize};
use std::fmt;
use uuid::Uuid;

macro_rules! branded_string {
    ($name:ident, $doc:expr) => {
        #[doc = $doc]
        #[derive(Clone, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
        #[serde(transparent)]
        pub struct $name(String);

        impl $name {
            /// Wrap an already-issued identifier.
            pub fn new(raw: impl Into<String>) -> Self {
                Self(raw.into())
            }

            /// Borrow the raw identifier.
            pub fn as_str(&self) -> &str {
                &self.0
            }
        }

        impl fmt::Display for $name {
            fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                f.write_str(&self.0)
            }
        }

        impl AsRef<str> for $name {
            fn as_ref(&self) -> &str {
                &self.0
            }
        }

        impl From<$name> for String {
            fn from(value: $name) -> Self {
                value.0
            }
        }
    };
}

branded_string!(SessionId, "Identifies one session and its persistence artifacts.");
branded_string!(MessageId, "Stable identity of one conversation message.");
branded_string!(CallId, "Provider-issued tool-call id; pairs a call with its result.");
branded_string!(
    ProviderRequestId,
    "Opaque provider-issued request identifier for diagnostics."
);
branded_string!(
    ReasoningEffortId,
    "Adapter-owned reasoning effort accepted by GenerateOptions."
);
branded_string!(
    CredentialRef,
    "Named credential reference. Configuration stores this name, never a literal secret."
);

impl SessionId {
    /// Allocate a fresh random session id.
    pub fn generate() -> Self {
        Self::new(Uuid::new_v4().to_string())
    }
}

impl MessageId {
    /// Allocate a fresh random message id.
    pub fn generate() -> Self {
        Self::new(Uuid::new_v4().to_string())
    }
}

impl CallId {
    /// Allocate a harness-issued call id when the provider omitted one.
    pub fn generate_fallback(index: usize) -> Self {
        Self::new(format!("call-{index}"))
    }
}

impl CredentialRef {
    /// Default DeepSeek API key environment variable.
    pub fn deepseek_api_key() -> Self {
        Self::new("DEEPSEEK_API_KEY")
    }
}

impl ReasoningEffortId {
    pub fn off() -> Self {
        Self::new("off")
    }
    pub fn high() -> Self {
        Self::new("high")
    }
    pub fn max() -> Self {
        Self::new("max")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn session_id_is_not_a_bare_string_at_the_type_level() {
        let id = SessionId::new("s1");
        assert_eq!(id.as_str(), "s1");
        let json = serde_json::to_string(&id).unwrap();
        assert_eq!(json, "\"s1\"");
    }
}
