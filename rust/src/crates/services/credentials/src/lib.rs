//! Resolve `CredentialRef` from the process environment, after optional `.env` load.

use std::path::Path;

use async_trait::async_trait;
use dsh_core_types::{CredentialRef, LlmError};
use dsh_runtime_ports::CredentialsPort;

pub struct EnvCredentials {
    loaded: bool,
}

impl EnvCredentials {
    /// Load `.env` from `dir` then `home` if present. Missing files are ignored;
    /// a present but unreadable file fails loud.
    pub fn load(dir: &Path, home: Option<&Path>) -> Result<Self, LlmError> {
        let mut loaded = false;
        for candidate in [dir.join(".env")]
            .into_iter()
            .chain(home.map(|path| path.join(".env")))
        {
            if !candidate.exists() {
                continue;
            }
            dotenvy::from_path_override(&candidate).map_err(|error| {
                LlmError::new(
                    format!("failed to read {}: {error}", candidate.display()),
                    "INVALID_CREDENTIAL_STORE",
                )
            })?;
            loaded = true;
        }
        Ok(Self { loaded })
    }

    pub fn process_only() -> Self {
        Self { loaded: false }
    }

    pub fn loaded_dotenv(&self) -> bool {
        self.loaded
    }
}

#[async_trait]
impl CredentialsPort for EnvCredentials {
    async fn resolve(&self, reference: &CredentialRef) -> Result<String, LlmError> {
        std::env::var(reference.as_str())
            .ok()
            .filter(|value| !value.is_empty())
            .ok_or_else(|| LlmError::missing_credential(reference.as_str()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    #[tokio::test]
    async fn missing_credential_fails_loud() {
        let creds = EnvCredentials::process_only();
        let err = creds
            .resolve(&CredentialRef::new("DSH_TEST_MISSING_KEY_XYZ"))
            .await
            .unwrap_err();
        assert_eq!(err.code(), "MISSING_CREDENTIAL");
    }

    #[tokio::test]
    async fn dotenv_file_supplies_the_named_ref() {
        let dir = tempfile::tempdir().unwrap();
        let mut file = std::fs::File::create(dir.path().join(".env")).unwrap();
        writeln!(file, "DSH_TEST_DOTENV_KEY=from-file").unwrap();
        let creds = EnvCredentials::load(dir.path(), None).unwrap();
        let value = creds
            .resolve(&CredentialRef::new("DSH_TEST_DOTENV_KEY"))
            .await
            .unwrap();
        assert_eq!(value, "from-file");
        assert!(creds.loaded_dotenv());
    }
}
