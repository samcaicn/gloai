// Copyright (c) 2026 tupAI
//
// Adapter factory — single construction entry point (mirrors
// `hermes::im::channel_registry::build_adapter_from_binding`).

use std::sync::Arc;

use crate::acp::AcpClientService;
use crate::runtime_registry::adapter::AgentProviderAdapter;
use crate::runtime_registry::adapters::acp::AcpAdapter;
use crate::runtime_registry::adapters::cli_run::CliRunAdapter;
use crate::runtime_registry::adapters::custom_api::CustomApiAdapter;
use crate::runtime_registry::adapters::upstream::UpstreamAdapter;
use crate::runtime_registry::{RuntimeInstance, RuntimeKind};

pub mod acp;
pub mod cli_run;
pub mod custom_api;
pub mod upstream;

pub fn build_adapter(
    instance: &RuntimeInstance,
    acp: Option<Arc<AcpClientService>>,
) -> Option<Arc<dyn AgentProviderAdapter>> {
    match instance.kind {
        RuntimeKind::Acp => acp.map(|a| -> Arc<dyn AgentProviderAdapter> {
            Arc::new(AcpAdapter::new(instance.clone(), a))
        }),
        RuntimeKind::CliRun => Some(Arc::new(CliRunAdapter::new(instance.clone()))),
        RuntimeKind::CustomApi => Some(Arc::new(CustomApiAdapter::new(instance.clone()))),
        RuntimeKind::Upstream => Some(Arc::new(UpstreamAdapter::new(instance.clone()))),
    }
}
