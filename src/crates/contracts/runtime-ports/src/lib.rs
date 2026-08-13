//! Thin runtime ports. This crate contains DTOs and traits only.

use std::path::{Path, PathBuf};
use std::sync::Arc;

use async_trait::async_trait;
use dsh_core_types::{
    CredentialRef, GenerateOptions, LlmCallConfig, LlmError, LlmModelInfo, LlmProviderInfo,
    LlmResolvedModelInfo, SessionId, StreamChunk,
};
use dsh_events::SessionHeader;
use serde::{Deserialize, Serialize};
use tokio_stream::Stream;

pub type PortResult<T> = Result<T, PortError>;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, thiserror::Error)]
#[error("{kind:?}: {message}")]
pub struct PortError {
    pub kind: PortErrorKind,
    pub message: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PortErrorKind {
    NotAvailable,
    NotFound,
    InvalidRequest,
    PermissionDenied,
    Cancelled,
    Timeout,
    Backend,
}

impl PortError {
    pub fn new(kind: PortErrorKind, message: impl Into<String>) -> Self {
        Self {
            kind,
            message: message.into(),
        }
    }

    pub fn not_available(what: &str) -> Self {
        Self::new(PortErrorKind::NotAvailable, format!("{what} is not registered"))
    }
}

impl From<LlmError> for PortError {
    fn from(value: LlmError) -> Self {
        let kind = match value.code() {
            "ABORTED" => PortErrorKind::Cancelled,
            "MISSING_CREDENTIAL" | "AUTH" => PortErrorKind::PermissionDenied,
            _ => PortErrorKind::Backend,
        };
        Self::new(kind, value.failure.message)
    }
}

pub type ChunkStream = PinBoxStream<Result<StreamChunk, LlmError>>;
pub type PinBoxStream<T> = std::pin::Pin<Box<dyn Stream<Item = T> + Send>>;

#[async_trait]
pub trait LlmPort: Send + Sync {
    fn provider_info(&self, provider: &str) -> LlmProviderInfo;
    async fn list_models(&self, provider: &str) -> Result<Vec<LlmModelInfo>, LlmError>;
    async fn resolve_model(
        &self,
        provider: &str,
        model: &str,
    ) -> Result<LlmResolvedModelInfo, LlmError>;
    async fn prepare_call(
        &self,
        config: LlmCallConfig,
    ) -> Result<PreparedLlmCall, LlmError> {
        Ok(PreparedLlmCall {
            config,
            adapter_defaults: None,
            context_window: None,
        })
    }
    fn stream(&self, request: GenerateOptions) -> ChunkStream;
}

#[derive(Clone, Debug)]
pub struct PreparedLlmCall {
    pub config: LlmCallConfig,
    pub adapter_defaults: Option<dsh_events::AdapterDefaults>,
    pub context_window: Option<u32>,
}

#[async_trait]
pub trait CredentialsPort: Send + Sync {
    async fn resolve(&self, reference: &CredentialRef) -> Result<String, LlmError>;
}

#[async_trait]
pub trait FsPort: Send + Sync {
    fn workspace_root(&self) -> &Path;
    fn resolve(&self, path: &str) -> PortResult<PathBuf>;
    async fn read_text(&self, path: &Path) -> PortResult<String>;
    async fn write_text(&self, path: &Path, content: &str) -> PortResult<FsWriteOutcome>;
    async fn edit_text(
        &self,
        path: &Path,
        old: &str,
        new: &str,
        replace_all: bool,
    ) -> PortResult<FsEditOutcome>;
    async fn glob(&self, pattern: &str, search_path: Option<&Path>) -> PortResult<Vec<PathBuf>>;
    async fn grep(
        &self,
        pattern: &str,
        search_path: Option<&Path>,
        include: Option<&str>,
    ) -> PortResult<Vec<GrepMatch>>;
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FsWriteOutcome {
    pub path: PathBuf,
    pub operation: FsWriteOp,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FsWriteOp {
    Create,
    Update,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FsEditOutcome {
    pub path: PathBuf,
    pub replacements: usize,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct GrepMatch {
    pub path: PathBuf,
    pub line_number: u32,
    pub line: String,
}

#[async_trait]
pub trait SubprocessPort: Send + Sync {
    async fn run(&self, request: SubprocessRequest) -> PortResult<SubprocessResult>;
}

#[derive(Clone, Debug)]
pub struct SubprocessRequest {
    pub program: String,
    pub args: Vec<String>,
    pub cwd: PathBuf,
    pub timeout_ms: u64,
    pub stdin: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SubprocessResult {
    pub exit_code: i32,
    pub stdout: String,
    pub stderr: String,
    pub timed_out: bool,
}

#[async_trait]
pub trait ShellPort: Send + Sync {
    async fn exec(&self, request: ShellRequest) -> PortResult<SubprocessResult>;
}

#[derive(Clone, Debug)]
pub struct ShellRequest {
    pub command: String,
    pub cwd: PathBuf,
    pub timeout_ms: u64,
}

#[async_trait]
pub trait SessionPersistPort: Send + Sync {
    async fn save(&self, header: &SessionHeader, events_jsonl: &str) -> PortResult<()>;
    async fn load(&self, id: &SessionId) -> PortResult<Option<(SessionHeader, String)>>;
}

/// Unregistered plugin host. Callers must fail loud, never skip.
#[async_trait]
pub trait PluginRuntimePort: Send + Sync {
    fn availability(&self) -> PluginRuntimeAvailability;
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PluginRuntimeAvailability {
    Unavailable { reason: String },
    Ready,
}

pub struct UnavailablePluginRuntime;

#[async_trait]
impl PluginRuntimePort for UnavailablePluginRuntime {
    fn availability(&self) -> PluginRuntimeAvailability {
        PluginRuntimeAvailability::Unavailable {
            reason: "plugin runtime is not registered in this delivery profile".to_string(),
        }
    }
}

/// Shared handle to every port a product runtime assembled.
#[derive(Clone)]
pub struct PortBag {
    pub llm: Arc<dyn LlmPort>,
    pub credentials: Arc<dyn CredentialsPort>,
    pub fs: Arc<dyn FsPort>,
    pub subprocess: Arc<dyn SubprocessPort>,
    pub shell: Arc<dyn ShellPort>,
    pub persist: Arc<dyn SessionPersistPort>,
    pub plugin_runtime: Arc<dyn PluginRuntimePort>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn missing_plugin_runtime_is_unavailable() {
        let port = UnavailablePluginRuntime;
        assert!(matches!(
            port.availability(),
            PluginRuntimeAvailability::Unavailable { .. }
        ));
    }
}
