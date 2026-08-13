//! Bash capability: executor over `SubprocessPort` and the `bash` tool.

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
}

impl BashTool {
    pub fn new(shell: Arc<dyn ShellPort>) -> Self {
        Self { shell }
    }
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
        let workdir = input
            .arguments
            .get("workdir")
            .and_then(JsonValue::as_str)
            .map(std::path::PathBuf::from)
            .unwrap_or_else(std::env::current_dir)
            .unwrap_or_else(|_| std::path::PathBuf::from("."));
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

pub fn install(registry: &ToolRegistry, prompt: &SystemPrompt, shell: Arc<dyn ShellPort>) -> Vec<Disposer> {
    let section = prompt
        .section(PromptSection {
            name: "tool:bash".into(),
            order: 105,
            text: "Check the [exit code: N] marker on every bash result; investigate failures before moving on.".into(),
            complete: false,
        })
        .expect("tool:bash section");
    vec![section, registry.register(Arc::new(BashTool::new(shell)))]
}

#[cfg(test)]
mod tests {
    use super::*;
    use dsh_core_types::CallId;
    use dsh_subprocess::LocalSubprocess;

    #[tokio::test]
    async fn bash_echo() {
        let shell = Arc::new(LocalShell::new(Arc::new(LocalSubprocess)));
        let tool = BashTool::new(shell);
        let result = tool
            .execute(ToolExecutionInput {
                call_id: CallId::new("c"),
                name: "bash".into(),
                arguments: json!({
                    "command": "echo hello-shell",
                    "description": "Echo a marker"
                }),
            })
            .await
            .unwrap();
        assert!(!result.is_error);
        let text = dsh_core_types::flatten_text(&result.content);
        assert!(text.contains("hello-shell"));
        assert!(text.contains("[exit code: 0]"));
    }
}
