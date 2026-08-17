//! Explicit resolve step. Defaults are applied here, never inside `run()`.

use std::path::PathBuf;
use std::str::FromStr;

use dsh_core_types::CredentialRef;
use dsh_persist::session_dir;

use crate::CoreError;

pub const DEFAULT_PROVIDER: &str = "deepseek";
pub const DEFAULT_MODEL: &str = "deepseek-chat";
pub const DEFAULT_BASE_URL: &str = "https://api.deepseek.com";
pub const DEFAULT_PERSONA: &str = "You are DeepSeek Harness.";

/// Compile-time delivery profile. Runtime wiring still reads `RuntimeSpec` fields.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DeliveryProfile {
    Headless,
    Acp,
    Test,
}

impl DeliveryProfile {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Headless => "headless",
            Self::Acp => "acp",
            Self::Test => "test",
        }
    }
}

impl FromStr for DeliveryProfile {
    type Err = CoreError;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        match value {
            "headless" => Ok(Self::Headless),
            "acp" => Ok(Self::Acp),
            "test" => Ok(Self::Test),
            other => Err(CoreError::Invalid(format!("unknown profile `{other}`"))),
        }
    }
}

/// Which `LlmPort` implementation the spec will boot.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LlmBackend {
    DeepSeek,
    Mock,
}

impl LlmBackend {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::DeepSeek => "deepseek",
            Self::Mock => "mock",
        }
    }
}

impl FromStr for LlmBackend {
    type Err = CoreError;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        match value {
            "deepseek" => Ok(Self::DeepSeek),
            "mock" => Ok(Self::Mock),
            other => Err(CoreError::Invalid(format!("unknown llm backend `{other}`"))),
        }
    }
}

/// Unresolved launch request. Empty fields are filled by [`ProductRuntime::resolve`].
#[derive(Clone, Debug, Default)]
pub struct RuntimeRequest {
    pub profile: Option<DeliveryProfile>,
    pub llm: Option<LlmBackend>,
    pub provider: Option<String>,
    pub model: Option<String>,
    pub home: Option<PathBuf>,
    pub workspace: Option<PathBuf>,
    pub credential: Option<CredentialRef>,
    pub base_url: Option<String>,
    #[cfg(feature = "llm-mock")]
    pub mock_turns: Vec<dsh_llm_mock::MockTurn>,
}

/// Fully resolved launch spec. `boot` is the only consumer of these defaults.
#[derive(Clone, Debug)]
pub struct RuntimeSpec {
    pub profile: DeliveryProfile,
    pub llm: LlmBackend,
    pub provider: String,
    pub model: String,
    pub home: PathBuf,
    pub workspace: PathBuf,
    pub sessions_dir: PathBuf,
    pub credential: CredentialRef,
    pub base_url: String,
    #[cfg(feature = "llm-mock")]
    pub mock_turns: Vec<dsh_llm_mock::MockTurn>,
}

/// Harness home: `DSH_HOME`, else `~/.dsh-rust`. Missing home is an error.
pub fn default_home() -> Result<PathBuf, CoreError> {
    if let Ok(home) = std::env::var("DSH_HOME") {
        if home.trim().is_empty() {
            return Err(CoreError::Invalid(
                "DSH_HOME is set but empty; unset it or provide a directory".into(),
            ));
        }
        return Ok(PathBuf::from(home));
    }
    dirs::home_dir()
        .map(|home| home.join(".dsh-rust"))
        .ok_or_else(|| CoreError::Invalid("cannot resolve home directory; set DSH_HOME".into()))
}

impl crate::ProductRuntime {
    /// Fill every default explicitly. Never called from `run()`.
    pub fn resolve(request: RuntimeRequest) -> Result<RuntimeSpec, CoreError> {
        let profile = request.profile.unwrap_or(DeliveryProfile::Headless);
        let llm = request.llm.unwrap_or(match profile {
            DeliveryProfile::Test => LlmBackend::Mock,
            DeliveryProfile::Headless | DeliveryProfile::Acp => LlmBackend::DeepSeek,
        });
        if profile == DeliveryProfile::Test && llm != LlmBackend::Mock {
            return Err(CoreError::Invalid(
                "profile `test` requires llm backend `mock`".into(),
            ));
        }
        match llm {
            LlmBackend::DeepSeek if !cfg!(feature = "llm-deepseek") => {
                return Err(CoreError::Invalid(
                    "llm backend `deepseek` is not compiled into this delivery profile".into(),
                ));
            }
            LlmBackend::Mock if !cfg!(feature = "llm-mock") => {
                return Err(CoreError::Invalid(
                    "llm backend `mock` is not compiled into this delivery profile".into(),
                ));
            }
            _ => {}
        }
        let provider = request
            .provider
            .or_else(|| std::env::var("DSH_PROVIDER").ok().filter(|v| !v.is_empty()))
            .unwrap_or_else(|| match llm {
                LlmBackend::Mock => "mock".into(),
                LlmBackend::DeepSeek => DEFAULT_PROVIDER.into(),
            });
        let model = request
            .model
            .or_else(|| std::env::var("DSH_MODEL").ok().filter(|v| !v.is_empty()))
            .unwrap_or_else(|| match llm {
                LlmBackend::Mock => "mock-model".into(),
                LlmBackend::DeepSeek => DEFAULT_MODEL.into(),
            });
        if provider.is_empty() || model.is_empty() {
            return Err(CoreError::Invalid(
                "provider and model must be non-empty after resolve".into(),
            ));
        }
        let home = match request.home {
            Some(home) => home,
            None => default_home()?,
        };
        let workspace = match request.workspace {
            Some(path) => path,
            None => std::env::current_dir()?,
        };
        if !workspace.is_dir() {
            return Err(CoreError::Invalid(format!(
                "workspace {} is not a directory",
                workspace.display()
            )));
        }
        let credential = request
            .credential
            .unwrap_or_else(CredentialRef::deepseek_api_key);
        let base_url = request
            .base_url
            .or_else(|| {
                std::env::var("DEEPSEEK_BASE_URL")
                    .ok()
                    .filter(|value| !value.is_empty())
            })
            .unwrap_or_else(|| DEFAULT_BASE_URL.to_string());
        Ok(RuntimeSpec {
            profile,
            llm,
            provider,
            model,
            sessions_dir: session_dir(&home),
            home,
            workspace,
            credential,
            base_url,
            #[cfg(feature = "llm-mock")]
            mock_turns: request.mock_turns,
        })
    }
}
