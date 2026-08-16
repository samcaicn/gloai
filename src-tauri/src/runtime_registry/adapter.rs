// Copyright (c) 2026 tupAI
//
// `AgentProviderAdapter` — minimal provider abstraction. One impl per
// runtime backend. Mirrors `hermes::im::adapter_base::IMAdapter` so the
// two adapter systems stay recognisably consistent.

use async_trait::async_trait;

use crate::runtime_registry::{DetectionResult, InvokeRequest, InvokeResponse, RuntimeKind};

#[async_trait]
pub trait AgentProviderAdapter: Send + Sync {
    /// Stable provider id this adapter serves (e.g. `claude`).
    fn provider_id(&self) -> &str;

    /// Backend category.
    fn kind(&self) -> RuntimeKind;

    /// Probe availability (binary on PATH / endpoint reachable).
    async fn detect(&self) -> DetectionResult;

    /// Run a one-shot prompt. Streaming backends (ACP) should hook into
    /// the existing event emission; the skeleton returns the final
    /// aggregated output.
    async fn invoke(&self, req: InvokeRequest) -> Result<InvokeResponse, String>;

    /// Cheap liveness check.
    async fn health(&self) -> bool;
}
