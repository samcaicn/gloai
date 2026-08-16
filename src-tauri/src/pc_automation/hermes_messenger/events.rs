// Copyright (c) 2026 tupAI
//
// tupAI v5 §6.2 — Hermes messenger protocol types.
//
// The original Doc1 design had the desktop client talk to a remote
// server over RabbitMQ (`pika.BlockingConnection`). tupAI has no
// remote server, so we keep the **wire shape** (`ClientRequest` /
// `ServerResponse`) but back the transport with a local
// `tokio::sync::mpsc` channel + an in-process response log
// (see `bus.rs`).
//
// Both enums use `#[serde(tag = "type", rename_all = "snake_case")]`
// so the JSON form is:
//
//   { "type": "skill_request", "intent": "...", "context": ... }
//   { "type": "vlm_request",   "screenshot_b64": "...", ... }
//   { "type": "skill_response","skill_data_b64": "...", ... }
//   { "type": "vlm_response",  "action": {...}, "explanation": "..." }
//
// That wire shape is intentionally stable so a future swap to a real
// transport (RabbitMQ / WebSocket / gRPC) only changes the impl in
// `bus.rs`, never the call sites in `AdaptiveExecutor` / `VlmRescue`.

use serde::{Deserialize, Serialize};

use crate::pc_automation::vlm_rescue::analyzer::VlmAction;

// =============================================================================
// Client → Server
// =============================================================================

#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ClientRequest {
    /// "I have intent X, give me a skill that can satisfy it."
    ///
    /// `context` is opaque to the messenger — the server (or
    /// `LocalSkillStorage` adapter) decides what to do with it.
    #[serde(rename_all = "camelCase")]
    SkillRequest {
        intent: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        context: Option<serde_json::Value>,
    },
    /// "The last UIA / CDP / OCR tier failed on this step — please
    /// look at the screenshot and tell me what to do next."
    #[serde(rename_all = "camelCase")]
    VlmRequest {
        /// base64-encoded PNG. The convention matches the
        /// `hermes_llm_complete` images-payload format.
        screenshot_b64: String,
        /// The serialized failing step. The server may echo it back
        /// in the response for observability.
        failed_step: serde_json::Value,
        intent: String,
    },
}

// =============================================================================
// Server → Client
// =============================================================================

#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ServerResponse {
    /// Encrypted skill bundle. The three fields together with the
    /// key (held client-side) reconstruct the plaintext skill.
    #[serde(rename_all = "camelCase")]
    SkillResponse {
        skill_data_b64: String,
        iv_b64: String,
        tag_b64: String,
    },
    /// The VLM's verdict on the rescue request.
    #[serde(rename_all = "camelCase")]
    VlmResponse {
        action: VlmAction,
        explanation: String,
    },
}

impl ClientRequest {
    /// Human-readable label for logs.
    #[allow(dead_code)] // diagnostic helper for the future log layer
    pub fn kind(&self) -> &'static str {
        match self {
            ClientRequest::SkillRequest { .. } => "skill_request",
            ClientRequest::VlmRequest { .. } => "vlm_request",
        }
    }
}

impl ServerResponse {
    #[allow(dead_code)] // diagnostic helper for the future log layer
    pub fn kind(&self) -> &'static str {
        match self {
            ServerResponse::SkillResponse { .. } => "skill_response",
            ServerResponse::VlmResponse { .. } => "vlm_response",
        }
    }
}
