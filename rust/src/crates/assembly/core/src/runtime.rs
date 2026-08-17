//! Boot the resolved spec into a live agent runtime.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;

use chrono::Utc;
use dsh_agent_loop::{LoopRuntime, ReactLoopAgent, DEFAULT_MAX_PARALLEL_TOOL_CALLS};
use dsh_agent_runtime::{Agent, AgentOptions, AgentRegistry};
use dsh_core_types::{flatten_text, human_text, MessageRole, SessionId};
use dsh_credentials::EnvCredentials;
use dsh_events::{Disposer, EventBus, SessionEventBody, TurnEndReason};
use dsh_fs::LocalFs;
use dsh_persist::JsonlPersist;
use dsh_runtime_ports::{
    CredentialsPort, FsPort, LlmPort, PluginRuntimeAvailability, PluginRuntimePort, PortBag,
    ShellPort, SubprocessPort, UnavailablePluginRuntime,
};
use dsh_session::{Session, SessionStore};
use dsh_shell::LocalShell;
use dsh_subprocess::LocalSubprocess;
use dsh_system_prompt::SystemPrompt;
use dsh_tool_contracts::ToolRegistry;
use parking_lot::Mutex;
use serde::Serialize;

use crate::spec::{LlmBackend, RuntimeSpec, DEFAULT_PERSONA};
use crate::CoreError;

/// Snapshot of the assembled runtime. Printed by `dsh --dump-config`.
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DumpConfig {
    pub profile: String,
    pub provider: String,
    pub model: String,
    pub llm: String,
    pub workspace: PathBuf,
    pub home: PathBuf,
    pub sessions_dir: PathBuf,
    pub tools: Vec<String>,
    pub plugin_runtime: String,
    pub credential_ref: String,
}

/// Result of one headless task.
#[derive(Clone, Debug)]
pub struct RunOutcome {
    pub session_id: SessionId,
    pub text: String,
    pub turn_reason: Option<TurnEndReason>,
    pub jsonl_path: PathBuf,
}

impl RunOutcome {
    /// Process exit code: `1` on model/tool failure, `130` on abort, otherwise `0`.
    pub fn exit_code(&self) -> i32 {
        match &self.turn_reason {
            Some(TurnEndReason::Error { .. }) => 1,
            Some(TurnEndReason::Aborted { .. }) => 130,
            _ => 0,
        }
    }
}

/// Wired product: ports, registries, and per-session agent handles.
pub struct ProductRuntime {
    spec: RuntimeSpec,
    ports: PortBag,
    bus: EventBus,
    agents: AgentRegistry,
    sessions: SessionStore,
    persist: Arc<JsonlPersist>,
    catalog_names: Vec<String>,
    _keep: Vec<Disposer>,
    live: Mutex<HashMap<String, Vec<Disposer>>>,
}

impl RuntimeSpec {
    /// Materialize ports, install tools, and hold their disposers.
    pub fn boot(self) -> Result<ProductRuntime, CoreError> {
        let credentials: Arc<dyn CredentialsPort> =
            Arc::new(EnvCredentials::load(&self.workspace, Some(&self.home))?);
        let fs: Arc<dyn FsPort> = Arc::new(LocalFs::new(&self.workspace));
        let subprocess: Arc<dyn SubprocessPort> = Arc::new(LocalSubprocess);
        let shell: Arc<dyn ShellPort> = Arc::new(LocalShell::new(Arc::clone(&subprocess)));
        let persist = Arc::new(JsonlPersist::new(&self.sessions_dir));
        let plugin_runtime: Arc<dyn PluginRuntimePort> = Arc::new(UnavailablePluginRuntime);
        let llm = build_llm(&self, Arc::clone(&credentials))?;
        let ports = PortBag {
            llm,
            credentials,
            fs,
            subprocess,
            shell,
            persist: persist.clone(),
            plugin_runtime,
        };
        let bus = EventBus::new();
        let catalog_tools = ToolRegistry::new();
        let catalog_prompt = SystemPrompt::with_identity_and_persona(DEFAULT_PERSONA);
        let keep = install_tools(
            &catalog_tools,
            &catalog_prompt,
            Arc::clone(&ports.fs),
            Arc::clone(&ports.shell),
            self.workspace.clone(),
        );
        Ok(ProductRuntime {
            catalog_names: catalog_tools.names(),
            spec: self,
            ports,
            agents: AgentRegistry::new(bus.clone()),
            bus,
            sessions: SessionStore::new(),
            persist,
            _keep: keep,
            live: Mutex::new(HashMap::new()),
        })
    }
}

fn build_llm(
    spec: &RuntimeSpec,
    credentials: Arc<dyn CredentialsPort>,
) -> Result<Arc<dyn LlmPort>, CoreError> {
    match spec.llm {
        LlmBackend::DeepSeek => {
            #[cfg(feature = "llm-deepseek")]
            {
                use dsh_llm_deepseek::{
                    DeepSeekAdapter, DeepSeekAdapterOptions, DeepSeekCatalogModel,
                    DeepSeekConnectionOptions, RequestDefaults, DEFAULT_CONTEXT_WINDOW,
                    DEFAULT_MAX_TOKENS, DEFAULT_STREAM_IDLE_TIMEOUT_MS,
                };
                let adapter = DeepSeekAdapter::new(DeepSeekAdapterOptions {
                    connection: DeepSeekConnectionOptions {
                        base_url: spec.base_url.clone(),
                        api_key_env: spec.credential.clone(),
                        defaults: RequestDefaults::default(),
                        max_tokens: DEFAULT_MAX_TOKENS,
                        default_context_window: DEFAULT_CONTEXT_WINDOW,
                        models: vec![
                            DeepSeekCatalogModel {
                                id: "deepseek-chat".into(),
                                name: Some("DeepSeek Chat".into()),
                                description: None,
                                context_window: Some(DEFAULT_CONTEXT_WINDOW),
                                max_tokens: Some(DEFAULT_MAX_TOKENS),
                            },
                            DeepSeekCatalogModel {
                                id: "deepseek-reasoner".into(),
                                name: Some("DeepSeek Reasoner".into()),
                                description: None,
                                context_window: Some(DEFAULT_CONTEXT_WINDOW),
                                max_tokens: Some(DEFAULT_MAX_TOKENS),
                            },
                        ],
                        stream_idle_timeout_ms: DEFAULT_STREAM_IDLE_TIMEOUT_MS,
                    },
                    credentials,
                })?;
                Ok(Arc::new(adapter))
            }
            #[cfg(not(feature = "llm-deepseek"))]
            {
                let _ = credentials;
                Err(CoreError::Invalid(
                    "llm backend `deepseek` is not compiled into this delivery profile".into(),
                ))
            }
        }
        LlmBackend::Mock => {
            #[cfg(feature = "llm-mock")]
            {
                use dsh_llm_mock::{MockTurn, ScriptLlm};
                let turns = if spec.mock_turns.is_empty() {
                    vec![MockTurn::Text("Hello from the mock LLM.".into())]
                } else {
                    spec.mock_turns.clone()
                };
                Ok(Arc::new(
                    ScriptLlm::new(turns).with_route(spec.provider.clone(), spec.model.clone()),
                ))
            }
            #[cfg(not(feature = "llm-mock"))]
            {
                let _ = credentials;
                Err(CoreError::Invalid(
                    "llm backend `mock` is not compiled into this delivery profile".into(),
                ))
            }
        }
    }
}

fn install_tools(
    registry: &ToolRegistry,
    prompt: &SystemPrompt,
    fs: Arc<dyn FsPort>,
    shell: Arc<dyn ShellPort>,
    workspace: PathBuf,
) -> Vec<Disposer> {
    let mut keep = Vec::new();
    keep.push(prompt.variable("cwd", workspace.display().to_string()));
    keep.extend(dsh_fs::install(registry, prompt, fs));
    keep.extend(dsh_shell::install(registry, prompt, shell, workspace));
    prompt.set_tools(registry.schemas());
    keep
}

impl ProductRuntime {
    pub fn spec(&self) -> &RuntimeSpec {
        &self.spec
    }

    pub fn ports(&self) -> &PortBag {
        &self.ports
    }

    pub fn bus(&self) -> EventBus {
        self.bus.clone()
    }

    pub fn agents(&self) -> &AgentRegistry {
        &self.agents
    }

    pub fn dump_config(&self) -> DumpConfig {
        let plugin_runtime = match self.ports.plugin_runtime.availability() {
            PluginRuntimeAvailability::Unavailable { .. } => "unavailable".to_string(),
            PluginRuntimeAvailability::Ready => "ready".to_string(),
        };
        DumpConfig {
            profile: self.spec.profile.as_str().to_string(),
            provider: self.spec.provider.clone(),
            model: self.spec.model.clone(),
            llm: self.spec.llm.as_str().to_string(),
            workspace: self.spec.workspace.clone(),
            home: self.spec.home.clone(),
            sessions_dir: self.spec.sessions_dir.clone(),
            tools: self.catalog_names.clone(),
            plugin_runtime,
            credential_ref: self.spec.credential.to_string(),
        }
    }

    /// Create an agent whose filesystem and bash cwd are `cwd`.
    pub async fn create_agent(&self, cwd: PathBuf) -> Result<Arc<ReactLoopAgent>, CoreError> {
        if !cwd.is_dir() {
            return Err(CoreError::Invalid(format!(
                "workspace {} is not a directory",
                cwd.display()
            )));
        }
        let cwd = cwd.canonicalize()?;
        let tools = Arc::new(ToolRegistry::new());
        let prompt = Arc::new(SystemPrompt::with_identity_and_persona(DEFAULT_PERSONA));
        let fs: Arc<dyn FsPort> = Arc::new(LocalFs::new(&cwd));
        let keep = install_tools(
            &tools,
            &prompt,
            fs,
            Arc::clone(&self.ports.shell),
            cwd.clone(),
        );
        let mut header =
            dsh_events::SessionHeader::new(SessionId::generate(), Utc::now().timestamp_millis());
        header.cwd = Some(cwd.display().to_string());
        header.origin = Some(self.spec.profile.as_str().to_string());
        let session = self.sessions.create(header)?;
        let runtime = LoopRuntime {
            llm: Arc::clone(&self.ports.llm),
            tools,
            prompt,
            bus: self.bus.clone(),
            max_parallel_tools: DEFAULT_MAX_PARALLEL_TOOL_CALLS,
        };
        let agent = ReactLoopAgent::new(
            runtime,
            session,
            AgentOptions {
                provider: Some(self.spec.provider.clone()),
                model: Some(self.spec.model.clone()),
                max_tokens: None,
            },
        );
        self.agents.insert(Arc::clone(&agent) as Arc<dyn Agent>);
        self.live.lock().insert(agent.id().to_string(), keep);
        self.bus
            .emit(dsh_events::BusEvent::SessionCreated {
                session_id: agent.id(),
            })
            .await;
        Ok(agent)
    }

    /// Run one user task to idle, persist JSONL, and return the last assistant text.
    pub async fn run_task(&self, task: &str) -> Result<RunOutcome, CoreError> {
        if task.trim().is_empty() {
            return Err(CoreError::Invalid("task text must be non-empty".into()));
        }
        let agent = self.create_agent(self.spec.workspace.clone()).await?;
        agent.followup(human_text(task));
        agent.when_idle().await;
        self.persist.save_session(agent.session().as_ref()).await?;
        Ok(RunOutcome {
            jsonl_path: self.persist.path_for(&agent.id()),
            session_id: agent.id(),
            text: last_assistant_text(agent.session().as_ref()),
            turn_reason: last_turn_reason(agent.session().as_ref()),
        })
    }
}

/// Latest `turn/end` reason in the session log.
pub fn last_turn_reason(session: &Session) -> Option<TurnEndReason> {
    session
        .events()
        .into_iter()
        .rev()
        .find_map(|event| match event.body {
            SessionEventBody::TurnEnd { reason, .. } => Some(reason),
            _ => None,
        })
}

/// Latest assistant surface text, walking `derive_messages` from the tail.
pub fn last_assistant_text(session: &Session) -> String {
    session
        .derive_messages()
        .into_iter()
        .rev()
        .find_map(|message| {
            if message.role == MessageRole::Assistant {
                Some(flatten_text(&message.content))
            } else {
                None
            }
        })
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{DeliveryProfile, LlmBackend, ProductRuntime, RuntimeRequest};
    use dsh_runtime_ports::PluginRuntimeAvailability;

    fn request(workspace: PathBuf, home: PathBuf) -> RuntimeRequest {
        RuntimeRequest {
            profile: Some(DeliveryProfile::Test),
            llm: Some(LlmBackend::Mock),
            home: Some(home),
            workspace: Some(workspace),
            #[cfg(feature = "llm-mock")]
            mock_turns: vec![dsh_llm_mock::MockTurn::Text("assembled-ok".into())],
            ..RuntimeRequest::default()
        }
    }

    #[tokio::test]
    async fn dump_config_lists_tools_and_unavailable_plugin_runtime() {
        let workspace = tempfile::tempdir().unwrap();
        let home = tempfile::tempdir().unwrap();
        let runtime = ProductRuntime::resolve(request(
            workspace.path().to_path_buf(),
            home.path().to_path_buf(),
        ))
        .unwrap()
        .boot()
        .unwrap();
        let dump = runtime.dump_config();
        assert_eq!(dump.profile, "test");
        assert_eq!(dump.llm, "mock");
        assert!(dump.tools.contains(&"read".into()));
        assert!(dump.tools.contains(&"bash".into()));
        assert_eq!(dump.plugin_runtime, "unavailable");
        assert!(matches!(
            runtime.ports().plugin_runtime.availability(),
            PluginRuntimeAvailability::Unavailable { .. }
        ));
    }

    #[tokio::test]
    async fn mock_headless_task_persists_jsonl() {
        let workspace = tempfile::tempdir().unwrap();
        let home = tempfile::tempdir().unwrap();
        let runtime = ProductRuntime::resolve(request(
            workspace.path().to_path_buf(),
            home.path().to_path_buf(),
        ))
        .unwrap()
        .boot()
        .unwrap();
        let outcome = runtime.run_task("hello").await.unwrap();
        assert_eq!(outcome.text, "assembled-ok");
        assert_eq!(outcome.exit_code(), 0);
        assert!(outcome.jsonl_path.is_file());
    }

    #[test]
    fn test_profile_rejects_deepseek() {
        let workspace = tempfile::tempdir().unwrap();
        let home = tempfile::tempdir().unwrap();
        let err = ProductRuntime::resolve(RuntimeRequest {
            profile: Some(DeliveryProfile::Test),
            llm: Some(LlmBackend::DeepSeek),
            home: Some(home.path().to_path_buf()),
            workspace: Some(workspace.path().to_path_buf()),
            ..RuntimeRequest::default()
        })
        .unwrap_err();
        assert!(err.to_string().contains("mock"));
    }
}
