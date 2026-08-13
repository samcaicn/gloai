//! Delivery-profile assembly. `ProductRuntime::resolve` then `RuntimeSpec::boot`.

mod runtime;
mod spec;

pub use runtime::{last_assistant_text, last_turn_reason, DumpConfig, ProductRuntime, RunOutcome};
pub use spec::{
    default_home, DeliveryProfile, LlmBackend, RuntimeRequest, RuntimeSpec, DEFAULT_BASE_URL,
    DEFAULT_MODEL, DEFAULT_PERSONA, DEFAULT_PROVIDER,
};

use dsh_runtime_ports::PortError;
use dsh_session::SessionError;
use dsh_system_prompt::PromptError;
use thiserror::Error;

/// Failures while resolving a spec or booting a runtime.
#[derive(Debug, Error)]
pub enum CoreError {
    #[error("{0}")]
    Invalid(String),
    #[error(transparent)]
    Session(#[from] SessionError),
    #[error(transparent)]
    Prompt(#[from] PromptError),
    #[error(transparent)]
    Port(#[from] PortError),
    #[error(transparent)]
    Llm(#[from] dsh_core_types::LlmError),
    #[error(transparent)]
    Agent(#[from] dsh_agent_runtime::AgentError),
    #[error(transparent)]
    Io(#[from] std::io::Error),
}
