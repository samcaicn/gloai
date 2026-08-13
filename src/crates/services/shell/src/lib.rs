//! Bash capability: executor over `SubprocessPort` and the `bash` tool.

use std::path::{Path, PathBuf};
use std::sync::Arc;

use async_trait::async_trait;
use dsh_core_types::JsonValue;
use dsh_events::Disposer;
use dsh_runtime_ports::{PortResult, ShellPort, ShellRequest, SubprocessPort, SubprocessResult};
use dsh_system_prompt::{PromptSection, SystemPrompt};
use dsh_tool_contracts::{
    object_schema, ToolError, ToolExecutionInput, ToolExecutionResult, ToolHandler, ToolRegistry,
};
use serde_json::json;

pub const DEFAULT_TIMEOUT_MS: u64 = 30_000;
pub const MAX_TIMEOUT_MS: u64 = 600_000;

pub struct LocalShell {
    subprocess: Arc<dyn SubprocessPort>,
}

impl LocalShell {
    pub fn new(subprocess: Arc<dyn SubprocessPort>) -> Self {
        Self { subprocess }
    }
}

#[async_trait]
impl ShellPort for LocalShell {
    async fn exec(&self, request: ShellRequest) -> PortResult<SubprocessResult> {
        self.subprocess
            .run(dsh_runtime_ports::SubprocessRequest {
                program: "bash".into(),
                args: vec!["-lc".into(), request.command],
                cwd: request.cwd,
                timeout_ms: request.timeout_ms.clamp(1, MAX_TIMEOUT_MS),
                stdin: None,
            })
            .await
    }
}

pub struct BashTool {
    shell: Arc<dyn ShellPort>,
    workspace: PathBuf,
}

impl BashTool {
    pub fn new(shell: Arc<dyn ShellPort>, workspace: impl Into<PathBuf>) -> Self {
        Self {
            shell,
            workspace: workspace.into(),
        }
    }
}

fn normalize(path: &Path) -> PathBuf {
    let mut out = PathBuf::new();
    for component in path.components() {
        match component {
            std::path::Component::ParentDir => {
                let _ = out.pop();
            }
            std::path::Component::CurDir => {}
            other => out.push(other.as_os_str()),
        }
    }
    out
}

fn resolve_workdir(workspace: &Path, requested: Option<&str>) -> Result<PathBuf, ToolError> {
    let candidate = match requested {
        None => workspace.to_path_buf(),
        Some(path) => {
            let path = PathBuf::from(path);
            if path.is_absolute() {
                path
            } else {
                workspace.join(path)
            }
        }
    };
    let normalized = normalize(&candidate);
    let root = normalize(workspace);
    if !normalized.starts_with(&root) {
        return Err(ToolError::InvalidArgs {
            name: "bash".into(),
            message: "workdir is outside the session workspace".into(),
        });
    }
    Ok(normalized)
}

#[async_trait]
impl ToolHandler for BashTool {
    fn name(&self) -> &str {
        "bash"
    }

    fn description(&self) -> &str {
        "Execute a bash command in the session workspace and return stdout, stderr, and the exit code."
    }

    fn parameters(&self) -> JsonValue {
        let mut properties = serde_json::Map::new();
        properties.insert("command".into(), json!({"type": "string"}));
        properties.insert("description".into(), json!({"type": "string"}));
        properties.insert("timeoutMs".into(), json!({"type": "number"}));
        properties.insert("workdir".into(), json!({"type": "string"}));
        object_schema(properties, &["command", "description"])
    }

    fn execution_mode(&self) -> dsh_tool_contracts::ExecutionMode {
        dsh_tool_contracts::ExecutionMode::Exclusive
    }

    async fn execute(&self, input: ToolExecutionInput) -> Result<ToolExecutionResult, ToolError> {
        let command = input
            .arguments
            .get("command")
            .and_then(JsonValue::as_str)
            .ok_or_else(|| ToolError::InvalidArgs {
                name: "bash".into(),
                message: "command is required".into(),
            })?;
        let timeout_ms = input
            .arguments
            .get("timeoutMs")
            .and_then(JsonValue::as_u64)
            .unwrap_or(DEFAULT_TIMEOUT_MS);
        let requested = input.arguments.get("workdir").and_then(JsonValue::as_str);
        let workdir = resolve_workdir(&self.workspace, requested)?;
        let result = self
            .shell
            .exec(ShellRequest {
                command: command.to_string(),
                cwd: workdir,
                timeout_ms,
            })
            .await
            .map_err(|error| ToolError::Execution(error.to_string()))?;
        let mut body = result.stdout;
        if !result.stderr.is_empty() {
            if !body.is_empty() {
                body.push('\n');
            }
            body.push_str(&result.stderr);
        }
        if result.timed_out {
            body.push_str("\n[exit code: timeout]");
            return Ok(ToolExecutionResult::error_text(body, "TIMEOUT"));
        }
        body.push_str(&format!("\n[exit code: {}]", result.exit_code));
        if result.exit_code == 0 {
            Ok(ToolExecutionResult::text(body))
        } else {
            Ok(ToolExecutionResult::error_text(body, "NONZERO_EXIT"))
        }
    }
}

pub fn install(
    registry: &ToolRegistry,
    prompt: &SystemPrompt,
    shell: Arc<dyn ShellPort>,
    workspace: PathBuf,
) -> Vec<Disposer> {
    let section = prompt
        .section(PromptSection {
            name: "tool:bash".into(),
            order: 105,
            text: "Check the [exit code: N] marker on every bash result; investigate failures before moving on.".into(),
            complete: false,
        })
        .expect("tool:bash section");
    vec![
        section,
        registry.register(Arc::new(BashTool::new(shell, workspace))),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;
    use dsh_core_types::CallId;
    use dsh_subprocess::LocalSubprocess;

    #[tokio::test]
    async fn bash_echo_uses_workspace_cwd() {
        let dir = tempfile::tempdir().unwrap();
        let shell = Arc::new(LocalShell::new(Arc::new(LocalSubprocess)));
        let tool = BashTool::new(shell, dir.path());
        let result = tool
            .execute(ToolExecutionInput {
                call_id: CallId::new("c"),
                name: "bash".into(),
                arguments: json!({
                    "command": "pwd",
                    "description": "Print workspace"
                }),
            })
            .await
            .unwrap();
        assert!(!result.is_error);
        let text = dsh_core_types::flatten_text(&result.content);
        assert!(
            text.contains(
                &dir.path()
                    .canonicalize()
                    .unwrap()
                    .to_string_lossy()
                    .into_owned()
            ) || text.contains(&dir.path().to_string_lossy().into_owned())
        );
        assert!(text.contains("[exit code: 0]"));
    }

    #[tokio::test]
    async fn rejects_workdir_escape() {
        let dir = tempfile::tempdir().unwrap();
        let shell = Arc::new(LocalShell::new(Arc::new(LocalSubprocess)));
        let tool = BashTool::new(shell, dir.path());
        let err = tool
            .execute(ToolExecutionInput {
                call_id: CallId::new("c"),
                name: "bash".into(),
                arguments: json!({
                    "command": "pwd",
                    "description": "Escape",
                    "workdir": "../"
                }),
            })
            .await
            .unwrap_err();
        assert!(err.to_string().contains("outside"));
    }
}
