//! Model-facing read / write / edit / glob / grep tools.

use std::sync::Arc;

use async_trait::async_trait;
use dsh_core_types::JsonValue;
use dsh_events::Disposer;
use dsh_runtime_ports::{FsPort, FsWriteOp};
use dsh_system_prompt::{PromptSection, SystemPrompt};
use dsh_tool_contracts::{
    object_schema, ToolError, ToolExecutionInput, ToolExecutionResult, ToolHandler, ToolRegistry,
};
use serde_json::json;

use crate::READ_LIMIT;

pub fn install_fs_tools(
    registry: &ToolRegistry,
    prompt: &SystemPrompt,
    fs: Arc<dyn FsPort>,
) -> Vec<Disposer> {
    vec![
        prompt
            .section(PromptSection {
                name: "tool:read".into(),
                order: 100,
                text: "Use the read tool — not shell commands like cat — to inspect text files. Results include line numbers. Use offset and limit to continue reading large files.".into(),
                complete: false,
            })
            .expect("tool:read"),
        prompt
            .section(PromptSection {
                name: "tool:glob".into(),
                order: 103,
                text: "Use the glob tool — not shell find — to discover files by path pattern."
                    .into(),
                complete: false,
            })
            .expect("tool:glob"),
        registry.register(Arc::new(ReadTool {
            fs: Arc::clone(&fs),
        })),
        registry.register(Arc::new(WriteTool {
            fs: Arc::clone(&fs),
        })),
        registry.register(Arc::new(EditTool {
            fs: Arc::clone(&fs),
        })),
        registry.register(Arc::new(GlobTool {
            fs: Arc::clone(&fs),
        })),
        registry.register(Arc::new(GrepTool { fs })),
    ]
}

struct ReadTool {
    fs: Arc<dyn FsPort>,
}
struct WriteTool {
    fs: Arc<dyn FsPort>,
}
struct EditTool {
    fs: Arc<dyn FsPort>,
}
struct GlobTool {
    fs: Arc<dyn FsPort>,
}
struct GrepTool {
    fs: Arc<dyn FsPort>,
}

fn required_string<'a>(args: &'a JsonValue, field: &str, tool: &str) -> Result<&'a str, ToolError> {
    args.get(field)
        .and_then(JsonValue::as_str)
        .filter(|value| !value.trim().is_empty())
        .ok_or_else(|| ToolError::InvalidArgs {
            name: tool.into(),
            message: format!("{field} must be a non-empty string"),
        })
}

#[async_trait]
impl ToolHandler for ReadTool {
    fn name(&self) -> &str {
        "read"
    }
    fn description(&self) -> &str {
        "Read a UTF-8 text file and return line-numbered content."
    }
    fn parameters(&self) -> JsonValue {
        let mut properties = serde_json::Map::new();
        properties.insert("file_path".into(), json!({"type": "string"}));
        properties.insert("offset".into(), json!({"type": "number"}));
        properties.insert("limit".into(), json!({"type": "number"}));
        object_schema(properties, &["file_path"])
    }
    fn execution_mode(&self) -> dsh_tool_contracts::ExecutionMode {
        dsh_tool_contracts::ExecutionMode::Parallel
    }
    async fn execute(&self, input: ToolExecutionInput) -> Result<ToolExecutionResult, ToolError> {
        let path = self
            .fs
            .resolve(required_string(&input.arguments, "file_path", "read")?)
            .map_err(|e| ToolError::Execution(e.to_string()))?;
        let text = self
            .fs
            .read_text(&path)
            .await
            .map_err(|e| ToolError::Execution(e.to_string()))?;
        let offset = input
            .arguments
            .get("offset")
            .and_then(JsonValue::as_u64)
            .unwrap_or(1)
            .max(1) as usize;
        let limit = input
            .arguments
            .get("limit")
            .and_then(JsonValue::as_u64)
            .unwrap_or(READ_LIMIT as u64) as usize;
        let lines: Vec<&str> = text.lines().collect();
        let start = offset.saturating_sub(1).min(lines.len());
        let end = (start + limit).min(lines.len());
        let mut body = format!("<path>{}</path>\n", path.display());
        for (index, line) in lines[start..end].iter().enumerate() {
            body.push_str(&format!("{:>6}|{line}\n", start + index + 1));
        }
        Ok(ToolExecutionResult::text(body))
    }
}

#[async_trait]
impl ToolHandler for WriteTool {
    fn name(&self) -> &str {
        "write"
    }
    fn description(&self) -> &str {
        "Write a UTF-8 text file, creating or overwriting it."
    }
    fn parameters(&self) -> JsonValue {
        let mut properties = serde_json::Map::new();
        properties.insert("file_path".into(), json!({"type": "string"}));
        properties.insert("content".into(), json!({"type": "string"}));
        object_schema(properties, &["file_path", "content"])
    }
    async fn execute(&self, input: ToolExecutionInput) -> Result<ToolExecutionResult, ToolError> {
        let file_path = required_string(&input.arguments, "file_path", "write")?;
        let content = input
            .arguments
            .get("content")
            .and_then(JsonValue::as_str)
            .ok_or_else(|| ToolError::InvalidArgs {
                name: "write".into(),
                message: "content is required".into(),
            })?;
        let path = self
            .fs
            .resolve(file_path)
            .map_err(|e| ToolError::Execution(e.to_string()))?;
        let outcome = self
            .fs
            .write_text(&path, content)
            .await
            .map_err(|e| ToolError::Execution(e.to_string()))?;
        let verb = match outcome.operation {
            FsWriteOp::Create => "Created",
            FsWriteOp::Update => "Updated",
        };
        Ok(ToolExecutionResult::text(format!(
            "<path>{}</path>\n<type>file</type>\n<content>\n{verb} file.\n</content>",
            path.display()
        )))
    }
}

#[async_trait]
impl ToolHandler for EditTool {
    fn name(&self) -> &str {
        "edit"
    }
    fn description(&self) -> &str {
        "Replace a unique substring in a UTF-8 text file. Use replace_all to replace every match."
    }
    fn parameters(&self) -> JsonValue {
        let mut properties = serde_json::Map::new();
        properties.insert("file_path".into(), json!({"type": "string"}));
        properties.insert("old_string".into(), json!({"type": "string"}));
        properties.insert("new_string".into(), json!({"type": "string"}));
        properties.insert("replace_all".into(), json!({"type": "boolean"}));
        object_schema(properties, &["file_path", "old_string", "new_string"])
    }
    async fn execute(&self, input: ToolExecutionInput) -> Result<ToolExecutionResult, ToolError> {
        let path = self
            .fs
            .resolve(required_string(&input.arguments, "file_path", "edit")?)
            .map_err(|e| ToolError::Execution(e.to_string()))?;
        let old = required_string(&input.arguments, "old_string", "edit")?;
        let new = input
            .arguments
            .get("new_string")
            .and_then(JsonValue::as_str)
            .ok_or_else(|| ToolError::InvalidArgs {
                name: "edit".into(),
                message: "new_string is required".into(),
            })?;
        let replace_all = input
            .arguments
            .get("replace_all")
            .and_then(JsonValue::as_bool)
            .unwrap_or(false);
        let outcome = self
            .fs
            .edit_text(&path, old, new, replace_all)
            .await
            .map_err(|e| ToolError::Execution(e.to_string()))?;
        Ok(ToolExecutionResult::text(format!(
            "Updated {} ({} replacement{}).",
            path.display(),
            outcome.replacements,
            if outcome.replacements == 1 { "" } else { "s" }
        )))
    }
}

#[async_trait]
impl ToolHandler for GlobTool {
    fn name(&self) -> &str {
        "glob"
    }
    fn description(&self) -> &str {
        "Find files whose paths match a glob pattern. Returns matching file paths, never directories."
    }
    fn parameters(&self) -> JsonValue {
        let mut properties = serde_json::Map::new();
        properties.insert("pattern".into(), json!({"type": "string"}));
        properties.insert("path".into(), json!({"type": "string"}));
        object_schema(properties, &["pattern"])
    }
    fn execution_mode(&self) -> dsh_tool_contracts::ExecutionMode {
        dsh_tool_contracts::ExecutionMode::Parallel
    }
    async fn execute(&self, input: ToolExecutionInput) -> Result<ToolExecutionResult, ToolError> {
        let pattern = required_string(&input.arguments, "pattern", "glob")?;
        let search = match input.arguments.get("path").and_then(JsonValue::as_str) {
            Some(path) => Some(
                self.fs
                    .resolve(path)
                    .map_err(|e| ToolError::Execution(e.to_string()))?,
            ),
            None => None,
        };
        let paths = self
            .fs
            .glob(pattern, search.as_deref())
            .await
            .map_err(|e| ToolError::Execution(e.to_string()))?;
        let body = paths
            .iter()
            .map(|path| path.display().to_string())
            .collect::<Vec<_>>()
            .join("\n");
        Ok(ToolExecutionResult::text(body))
    }
}

#[async_trait]
impl ToolHandler for GrepTool {
    fn name(&self) -> &str {
        "grep"
    }
    fn description(&self) -> &str {
        "Search file contents with a regular expression. Returns matching lines with line numbers."
    }
    fn parameters(&self) -> JsonValue {
        let mut properties = serde_json::Map::new();
        properties.insert("pattern".into(), json!({"type": "string"}));
        properties.insert("path".into(), json!({"type": "string"}));
        properties.insert("include".into(), json!({"type": "string"}));
        object_schema(properties, &["pattern"])
    }
    fn execution_mode(&self) -> dsh_tool_contracts::ExecutionMode {
        dsh_tool_contracts::ExecutionMode::Parallel
    }
    async fn execute(&self, input: ToolExecutionInput) -> Result<ToolExecutionResult, ToolError> {
        let pattern = required_string(&input.arguments, "pattern", "grep")?;
        let search = match input.arguments.get("path").and_then(JsonValue::as_str) {
            Some(path) => Some(
                self.fs
                    .resolve(path)
                    .map_err(|e| ToolError::Execution(e.to_string()))?,
            ),
            None => None,
        };
        let include = input.arguments.get("include").and_then(JsonValue::as_str);
        let matches = self
            .fs
            .grep(pattern, search.as_deref(), include)
            .await
            .map_err(|e| ToolError::Execution(e.to_string()))?;
        let body = matches
            .iter()
            .map(|item| format!("{}:{}:{}", item.path.display(), item.line_number, item.line))
            .collect::<Vec<_>>()
            .join("\n");
        Ok(ToolExecutionResult::text(body))
    }
}
