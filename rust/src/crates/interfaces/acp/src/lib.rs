//! Automation-only ACP v1 JSON-RPC over NDJSON stdio.

mod codec;
mod server;

pub use codec::{
    acp_prompt_to_text, prompt_has_unsupported, turn_end_to_stop_reason, PROTOCOL_VERSION,
};
pub use server::{serve, serve_stdio, AcpServer};

use thiserror::Error;

/// JSON-RPC / protocol failures returned to the client.
#[derive(Debug, Error)]
pub enum RpcError {
    #[error("{0}")]
    InvalidParams(String),
    #[error("{0}")]
    Internal(String),
    #[error("method not found: {0}")]
    MethodNotFound(String),
    #[error("parse error: {0}")]
    Parse(String),
}

impl RpcError {
    pub fn code(&self) -> i32 {
        match self {
            Self::Parse(_) => -32700,
            Self::InvalidParams(_) => -32602,
            Self::MethodNotFound(_) => -32601,
            Self::Internal(_) => -32603,
        }
    }
}
