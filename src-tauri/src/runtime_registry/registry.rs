// Copyright (c) 2026 tupAI
//
// RuntimeRegistry — owns detected + user-added runtimes and the
// auto-generated sub-agents (`<app><n>`). Mirrors the structure of
// `hermes::im::channel_registry::{ChannelRegistry, AdapterPool}`.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;
use tokio::sync::RwLock;

use crate::acp::AcpClientService;
use crate::runtime_registry::adapter::AgentProviderAdapter;
use crate::runtime_registry::adapters::build_adapter;
use crate::runtime_registry::commands::{AddCustomAgentRequest, RegisterUpstreamRequest};
use crate::runtime_registry::detect::detect_builtins;
use crate::runtime_registry::{
    InvokeRequest, InvokeResponse, RuntimeInstance, RuntimeKind, RuntimeRegistrySnapshot,
    SubAgent, SubAgentStatus,
};

pub struct RuntimeRegistry {
    /// Optional — ACP is an optional feature. When `None`, ACP-backed
    /// sub-agents cannot be driven, but CliRun / CustomApi still work.
    acp: Option<Arc<AcpClientService>>,
    instances: RwLock<Vec<RuntimeInstance>>,
    subagents: RwLock<Vec<SubAgent>>,
    /// provider_id → next index for `<app><n>` numbering.
    counters: RwLock<HashMap<String, u32>>,
    /// App data dir for persisting user-added custom agents. Set at
    /// startup via `set_data_dir`; `None` disables persistence.
    data_dir: RwLock<Option<PathBuf>>,
}

impl RuntimeRegistry {
    pub fn new(acp: Option<Arc<AcpClientService>>) -> Self {
        Self {
            acp,
            instances: RwLock::new(Vec::new()),
            subagents: RwLock::new(Vec::new()),
            counters: RwLock::new(HashMap::new()),
            data_dir: RwLock::new(None),
        }
    }

    /// Re-scan PATH for built-in CLIs and (re)build sub-agents.
    /// Built-in sub-agents are named `<app>1` (one per detected provider).
    pub async fn scan(&self) {
        let detected = detect_builtins();
        let mut instances = self.instances.write().await;
        // keep user-managed instances (custom-api + upstream); refresh built-ins
        instances.retain(|i| is_managed_instance(&i.id));
        let mut subagents = self.subagents.write().await;
        subagents.retain(|s| is_managed_instance(&s.instance_id));
        let mut counters = self.counters.write().await;
        *counters = HashMap::new();

        for inst in &detected {
            let idx = next_index(&mut counters, &inst.provider_id);
            let sub_id = format!("{}{}", inst.provider_id, idx);
            instances.push(inst.clone());
            if inst.installed {
                subagents.push(SubAgent {
                    id: sub_id,
                    display_name: format!("{} #{}", inst.display_name, idx),
                    instance_id: inst.id.clone(),
                    provider_id: inst.provider_id.clone(),
                    kind: inst.kind,
                    status: SubAgentStatus::Available,
                    model: None,
                    available_models: Vec::new(),
                });
            }
        }
    }

    /// Spin up an additional parallel instance of a detected provider
    /// (e.g. `claude2`, `claude3`).
    pub async fn spawn_instance(&self, provider_id: &str) -> Option<SubAgent> {
        let guard = self.instances.read().await;
        let inst = guard
            .iter()
            .find(|i| i.provider_id == provider_id && i.installed)?
            .clone();
        drop(guard);
        let mut counters = self.counters.write().await;
        let idx = next_index(&mut counters, provider_id);
        let sub_id = format!("{}{}", provider_id, idx);
        let sa = SubAgent {
            id: sub_id.clone(),
            display_name: format!("{} #{}", inst.display_name, idx),
            instance_id: inst.id.clone(),
            provider_id: provider_id.to_string(),
            kind: inst.kind,
            status: SubAgentStatus::Available,
            model: None,
            available_models: Vec::new(),
        };
        self.subagents.write().await.push(sa.clone());
        Some(sa)
    }

    /// Add a user-supplied HTTP agent backend.
    pub async fn add_custom_api(&self, req: AddCustomAgentRequest) -> Result<SubAgent, String> {
        if !(req.endpoint.starts_with("http://") || req.endpoint.starts_with("https://")) {
            return Err("endpoint must be http(s)".into());
        }
        let instance_id = format!("rt-user-{}", req.name);
        let idx = {
            let mut counters = self.counters.write().await;
            next_index(&mut counters, &req.name)
        };
        let sub_id = format!("{}{}", req.name, idx);
        let inst = RuntimeInstance {
            id: instance_id.clone(),
            provider_id: req.name.clone(),
            kind: RuntimeKind::CustomApi,
            display_name: req.name.clone(),
            endpoint: req.endpoint.clone(),
            installed: true,
            version: None,
            model: req.model.clone(),
            has_api_key: req.api_key.is_some(),
            cli_args_template: None,
            acp_client_id: None,
            available_models: Vec::new(),
        };
        self.instances.write().await.push(inst);
        let sa = SubAgent {
            id: sub_id.clone(),
            display_name: format!("{} #{}", req.name, idx),
            instance_id,
            provider_id: req.name.clone(),
            kind: RuntimeKind::CustomApi,
            status: SubAgentStatus::Available,
            model: None,
            available_models: Vec::new(),
        };
        self.subagents.write().await.push(sa.clone());
        // API key is held out-of-band (env var), never serialized to disk.
        if let Some(key) = req.api_key {
            std::env::set_var(api_key_env(&sa.instance_id), key);
        }
        self.persist_custom_agents().await;
        Ok(sa)
    }

    pub async fn remove_agent(&self, subagent_id: &str) -> bool {
        let mut subagents = self.subagents.write().await;
        let before = subagents.len();
        subagents.retain(|s| s.id != subagent_id);
        if subagents.len() == before {
            return false;
        }
        let still_used: std::collections::HashSet<String> =
            subagents.iter().map(|s| s.instance_id.clone()).collect();
        let mut instances = self.instances.write().await;
        instances.retain(|i| is_managed_instance(&i.id) && still_used.contains(&i.id));
        true
    }

    /// Register an upstream runtime (dsh / proma "main feature updates").
    /// `endpoint` is either an http(s) URL (OpenAI-compatible chat endpoint)
    /// or a local binary path. Returns the generated sub-agent (`<id><n>`).
    pub async fn register_upstream(
        &self,
        req: RegisterUpstreamRequest,
    ) -> Result<SubAgent, String> {
        let endpoint = req.endpoint.trim().to_string();
        let is_http = endpoint.starts_with("http://") || endpoint.starts_with("https://");
        let is_bin = !is_http && !endpoint.is_empty() && std::path::Path::new(&endpoint).is_file();
        if !is_http && !is_bin {
            return Err(
                "endpoint must be an http(s) URL or an existing binary path".into(),
            );
        }
        let instance_id = format!("rt-upstream-{}", req.id);
        let idx = {
            let mut counters = self.counters.write().await;
            next_index(&mut counters, &req.id)
        };
        let sub_id = format!("{}{}", req.id, idx);
        let inst = RuntimeInstance {
            id: instance_id.clone(),
            provider_id: req.id.clone(),
            kind: RuntimeKind::Upstream,
            display_name: req.display_name.clone(),
            endpoint,
            installed: true,
            version: None,
            model: req.model.clone(),
            has_api_key: req.api_key.is_some(),
            cli_args_template: req.cli_args_template.clone(),
            acp_client_id: None,
            available_models: Vec::new(),
        };
        self.instances.write().await.push(inst);
        let sa = SubAgent {
            id: sub_id.clone(),
            display_name: format!("{} #{}", req.display_name, idx),
            instance_id: instance_id.clone(),
            provider_id: req.id.clone(),
            kind: RuntimeKind::Upstream,
            status: SubAgentStatus::Available,
            model: None,
            available_models: Vec::new(),
        };
        self.subagents.write().await.push(sa.clone());
        if let Some(key) = req.api_key {
            std::env::set_var(api_key_env(&instance_id), key);
        }
        self.persist_upstream_runtimes().await;
        Ok(sa)
    }

    pub async fn snapshot(&self) -> RuntimeRegistrySnapshot {
        RuntimeRegistrySnapshot {
            instances: self.instances.read().await.clone(),
            subagents: self.subagents.read().await.clone(),
        }
    }

    pub async fn list_subagents(&self) -> Vec<SubAgent> {
        self.subagents.read().await.clone()
    }

    /// Discover model id + available models for an ACP provider the way the
    /// exe itself does — by opening a real ACP session and reading the
    /// `models` field from `session/new`. Updates the backing instance and
    /// its sub-agents. Best-effort and bounded by a timeout, so a
    /// missing/unauthed CLI never hangs the caller (the model simply stays
    /// `None` until discovered).
    pub async fn discover_models(&self, provider_id: &str) -> Result<(), String> {
        let acp = self
            .acp
            .clone()
            .ok_or_else(|| "ACP is not available".to_string())?;

        let (instance_id, client_id, cwd) = {
            let instances = self.instances.read().await;
            let inst = instances
                .iter()
                .find(|i| {
                    i.provider_id == provider_id
                        && i.kind == RuntimeKind::Acp
                        && i.installed
                })
                .ok_or_else(|| format!("no installed ACP provider '{provider_id}'"))?;
            let client_id = inst
                .acp_client_id
                .clone()
                .unwrap_or_else(|| inst.provider_id.clone());
            let cwd = std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."));
            (inst.id.clone(), client_id, cwd)
        };

        let opts = tokio::time::timeout(
            std::time::Duration::from_secs(45),
            acp.discover_client_models(&client_id, &cwd),
        )
        .await
        .map_err(|_| format!("model discovery for '{provider_id}' timed out"))??;

        {
            let mut instances = self.instances.write().await;
            if let Some(i) = instances.iter_mut().find(|i| i.id == instance_id) {
                i.model = opts.current_model_id.clone();
                i.available_models = opts.available_models.iter().map(|m| m.id.clone()).collect();
            }
        }
        {
            let mut subagents = self.subagents.write().await;
            for s in subagents
                .iter_mut()
                .filter(|s| s.instance_id == instance_id)
            {
                s.model = opts.current_model_id.clone();
                s.available_models = opts.available_models.iter().map(|m| m.id.clone()).collect();
            }
        }
        Ok(())
    }

    /// Resolve a sub-agent id → (instance, adapter).
    async fn resolve(
        &self,
        subagent_id: &str,
    ) -> Result<(RuntimeInstance, Arc<dyn AgentProviderAdapter>), String> {
        let subagents = self.subagents.read().await;
        let sa = subagents
            .iter()
            .find(|s| s.id == subagent_id)
            .ok_or_else(|| format!("unknown sub-agent: {subagent_id}"))?
            .clone();
        drop(subagents);
        let instances = self.instances.read().await;
        let inst = instances
            .iter()
            .find(|i| i.id == sa.instance_id)
            .ok_or_else(|| format!("missing instance for {subagent_id}"))?
            .clone();
        drop(instances);
        let adapter = build_adapter(&inst, self.acp.clone())
            .ok_or_else(|| format!("no adapter for kind {:?}", inst.kind))?;
        Ok((inst, adapter))
    }

    pub async fn invoke(&self, req: InvokeRequest) -> Result<InvokeResponse, String> {
        let (_inst, adapter) = self.resolve(&req.subagent_id).await?;
        {
            let mut subagents = self.subagents.write().await;
            if let Some(s) = subagents.iter_mut().find(|s| s.id == req.subagent_id) {
                s.status = SubAgentStatus::Busy;
            }
        }
        let result = adapter.invoke(req.clone()).await;
        {
            let mut subagents = self.subagents.write().await;
            if let Some(s) = subagents.iter_mut().find(|s| s.id == req.subagent_id) {
                s.status = SubAgentStatus::Available;
            }
        }
        result
    }
}

/// Normalize an instance id into the env var name used for its API key.
fn api_key_env(instance_id: &str) -> String {
    format!(
        "RR_API_KEY_{}",
        instance_id.to_uppercase().replace(['-', '.'], "_")
    )
}

/// Instances the registry manages itself (vs. built-in detected CLIs).
/// Built-in ids are `rt-<provider>`; managed ids are `rt-user-*` / `rt-upstream-*`.
fn is_managed_instance(id: &str) -> bool {
    id.starts_with("rt-user-") || id.starts_with("rt-upstream-")
}

#[derive(serde::Serialize, serde::Deserialize)]
struct PersistedCustomAgent {
    name: String,
    endpoint: String,
    model: Option<String>,
}

#[derive(serde::Serialize, serde::Deserialize)]
struct PersistedUpstream {
    id: String,
    display_name: String,
    endpoint: String,
    cli_args_template: Option<Vec<String>>,
    model: Option<String>,
}

impl RuntimeRegistry {
    /// Set the app data dir used to persist user-added custom agents.
    pub async fn set_data_dir(&self, dir: PathBuf) {
        *self.data_dir.write().await = Some(dir);
    }

    /// Load previously persisted custom API agents (called at startup).
    pub async fn load_custom_agents(&self) {
        let dir = self.data_dir.read().await.clone();
        let Some(dir) = dir else { return };
        let path = dir.join("runtime_registry_custom_agents.json");
        let Ok(content) = std::fs::read_to_string(&path) else {
            return;
        };
        let Ok(list) = serde_json::from_str::<Vec<PersistedCustomAgent>>(&content) else {
            log::warn!("[runtime-registry] failed to parse persisted custom agents");
            return;
        };
        for p in list {
            let _ = self
                .add_custom_api(AddCustomAgentRequest {
                    name: p.name,
                    endpoint: p.endpoint,
                    model: p.model,
                    api_key: None,
                })
                .await;
        }
    }

    /// Persist current custom-API agents to
    /// `<data_dir>/runtime_registry_custom_agents.json`.
    async fn persist_custom_agents(&self) {
        let dir = self.data_dir.read().await.clone();
        let Some(dir) = dir else { return };
        let instances = self.instances.read().await;
        let list: Vec<PersistedCustomAgent> = instances
            .iter()
            .filter(|i| i.kind == RuntimeKind::CustomApi)
            .map(|i| PersistedCustomAgent {
                name: i.provider_id.clone(),
                endpoint: i.endpoint.clone(),
                model: i.model.clone(),
            })
            .collect();
        drop(instances);
        let path = dir.join("runtime_registry_custom_agents.json");
        if let Ok(s) = serde_json::to_string_pretty(&list) {
            let _ = std::fs::write(&path, s);
        }
    }

    /// Load previously persisted upstream runtimes (called at startup).
    pub async fn load_upstream_runtimes(&self) {
        let dir = self.data_dir.read().await.clone();
        let Some(dir) = dir else { return };
        let path = dir.join("runtime_registry_upstream.json");
        let Ok(content) = std::fs::read_to_string(&path) else {
            return;
        };
        let Ok(list) = serde_json::from_str::<Vec<PersistedUpstream>>(&content) else {
            log::warn!("[runtime-registry] failed to parse persisted upstream runtimes");
            return;
        };
        for p in list {
            let _ = self
                .register_upstream(RegisterUpstreamRequest {
                    id: p.id,
                    display_name: p.display_name,
                    endpoint: p.endpoint,
                    cli_args_template: p.cli_args_template,
                    model: p.model,
                    api_key: None,
                })
                .await;
        }
    }

    /// Persist current upstream runtimes to
    /// `<data_dir>/runtime_registry_upstream.json`.
    async fn persist_upstream_runtimes(&self) {
        let dir = self.data_dir.read().await.clone();
        let Some(dir) = dir else { return };
        let instances = self.instances.read().await;
        let list: Vec<PersistedUpstream> = instances
            .iter()
            .filter(|i| i.kind == RuntimeKind::Upstream)
            .map(|i| PersistedUpstream {
                id: i.provider_id.clone(),
                display_name: i.display_name.clone(),
                endpoint: i.endpoint.clone(),
                cli_args_template: i.cli_args_template.clone(),
                model: i.model.clone(),
            })
            .collect();
        drop(instances);
        let path = dir.join("runtime_registry_upstream.json");
        if let Ok(s) = serde_json::to_string_pretty(&list) {
            let _ = std::fs::write(&path, s);
        }
    }
}

fn next_index(counters: &mut HashMap<String, u32>, key: &str) -> u32 {
    let n = counters.entry(key.to_string()).or_insert(0);
    *n += 1;
    *n
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn next_index_increments_per_provider_and_is_independent() {
        let mut c = HashMap::new();
        assert_eq!(next_index(&mut c, "claude"), 1);
        assert_eq!(next_index(&mut c, "claude"), 2);
        assert_eq!(next_index(&mut c, "opencode"), 1);
        assert_eq!(next_index(&mut c, "claude"), 3);
    }

    #[tokio::test]
    async fn custom_agent_numbering_and_remove() {
        let reg = RuntimeRegistry::new(None);
        let a = reg
            .add_custom_api(AddCustomAgentRequest {
                name: "myapi".into(),
                endpoint: "https://example.com/v1".into(),
                model: None,
                api_key: None,
            })
            .await
            .unwrap();
        assert_eq!(a.id, "myapi1");
        let b = reg
            .add_custom_api(AddCustomAgentRequest {
                name: "myapi".into(),
                endpoint: "https://other.com/v1".into(),
                model: None,
                api_key: None,
            })
            .await
            .unwrap();
        assert_eq!(b.id, "myapi2");

        // managed instance survives a rescan
        reg.scan().await;
        assert!(reg
            .list_subagents()
            .await
            .iter()
            .any(|s| s.id == "myapi2"));

        assert!(reg.remove_agent(&a.id).await);
        let subs = reg.list_subagents().await;
        assert!(!subs.iter().any(|s| s.id == "myapi1"));
        assert!(subs.iter().any(|s| s.id == "myapi2"));
    }

    #[tokio::test]
    async fn upstream_registration_rejects_bad_endpoint() {
        let reg = RuntimeRegistry::new(None);
        let err = reg
            .register_upstream(RegisterUpstreamRequest {
                id: "dsh".into(),
                display_name: "DSH".into(),
                endpoint: "not-a-url".into(),
                cli_args_template: None,
                model: None,
                api_key: None,
            })
            .await;
        assert!(err.is_err());
    }
}
