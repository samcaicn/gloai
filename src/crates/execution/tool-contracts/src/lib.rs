//! Tool registry, argument validation, and the pre/execute/post pipeline.

use std::sync::Arc;

use async_trait::async_trait;
use dsh_core_types::{CallId, ContentBlock, JsonValue, ToolSchema};
use dsh_events::Disposer;
use indexmap::IndexMap;
use parking_lot::RwLock;
use serde_json::json;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum ToolError {
    #[error("unknown tool `{0}`")]
    Unknown(String),
    #[error("invalid arguments for `{name}`: {message}")]
    InvalidArgs { name: String, message: String },
    #[error("{0}")]
    Execution(String),
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ExecutionMode {
    Exclusive,
    Parallel,
}

#[derive(Clone, Debug)]
pub struct ToolExecutionInput {
    pub call_id: CallId,
    pub name: String,
    pub arguments: JsonValue,
}

#[derive(Clone, Debug)]
pub struct ToolExecutionResult {
    pub content: Vec<ContentBlock>,
    pub is_error: bool,
    pub concludes_turn: bool,
    pub meta: Option<JsonValue>,
    pub error: Option<ToolErrorIdentity>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ToolErrorIdentity {
    pub name: String,
    pub code: String,
}

impl ToolExecutionResult {
    pub fn text(text: impl Into<String>) -> Self {
        Self {
            content: vec![ContentBlock::text(text)],
            is_error: false,
            concludes_turn: false,
            meta: None,
            error: None,
        }
    }

    pub fn error_text(text: impl Into<String>, code: impl Into<String>) -> Self {
        Self {
            content: vec![ContentBlock::text(text)],
            is_error: true,
            concludes_turn: false,
            meta: None,
            error: Some(ToolErrorIdentity {
                name: "ToolError".into(),
                code: code.into(),
            }),
        }
    }
}

#[async_trait]
pub trait ToolHandler: Send + Sync {
    fn name(&self) -> &str;
    fn description(&self) -> &str;
    fn parameters(&self) -> JsonValue;
    fn execution_mode(&self) -> ExecutionMode {
        ExecutionMode::Exclusive
    }
    async fn execute(&self, input: ToolExecutionInput) -> Result<ToolExecutionResult, ToolError>;
}

pub struct ToolDefinition {
    pub handler: Arc<dyn ToolHandler>,
}

#[derive(Clone, Default)]
pub struct ToolRegistry {
    tools: Arc<RwLock<IndexMap<String, Arc<dyn ToolHandler>>>>,
}

impl ToolRegistry {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn register(&self, handler: Arc<dyn ToolHandler>) -> Disposer {
        let name = handler.name().to_string();
        self.tools.write().insert(name.clone(), handler);
        let tools = Arc::clone(&self.tools);
        Disposer::new(move || {
            tools.write().shift_remove(&name);
        })
    }

    pub fn schemas(&self) -> Vec<ToolSchema> {
        self.tools
            .read()
            .values()
            .map(|tool| ToolSchema {
                name: tool.name().to_string(),
                description: tool.description().to_string(),
                parameters: tool.parameters(),
            })
            .collect()
    }

    pub fn names(&self) -> Vec<String> {
        self.tools.read().keys().cloned().collect()
    }

    pub fn execution_mode(&self, name: &str) -> ExecutionMode {
        self.tools
            .read()
            .get(name)
            .map(|t| t.execution_mode())
            .unwrap_or(ExecutionMode::Exclusive)
    }

    pub fn get(&self, name: &str) -> Result<Arc<dyn ToolHandler>, ToolError> {
        self.tools
            .read()
            .get(name)
            .cloned()
            .ok_or_else(|| ToolError::Unknown(name.to_string()))
    }

    pub async fn execute(&self, input: ToolExecutionInput) -> ToolExecutionResult {
        let handler = match self.get(&input.name) {
            Ok(h) => h,
            Err(err) => return ToolExecutionResult::error_text(err.to_string(), "UNKNOWN_TOOL"),
        };
        if let Err(err) = validate_args(handler.parameters(), &input.arguments) {
            return ToolExecutionResult::error_text(
                format!("invalid arguments for `{}`: {err}", input.name),
                "INVALID_ARGS",
            );
        }
        match handler.execute(input).await {
            Ok(result) => result,
            Err(err) => ToolExecutionResult::error_text(err.to_string(), "TOOL_FAILED"),
        }
    }
}

pub fn parse_arguments(raw: &str) -> JsonValue {
    if raw.is_empty() {
        return json!({});
    }
    serde_json::from_str(raw).unwrap_or_else(|_| JsonValue::String(raw.to_string()))
}

pub fn validate_args(schema: JsonValue, value: &JsonValue) -> Result<(), String> {
    let compiled = jsonschema::validator_for(&schema).map_err(|e| e.to_string())?;
    compiled.validate(value).map_err(|e| e.to_string())?;
    Ok(())
}

pub fn object_schema(
    properties: serde_json::Map<String, JsonValue>,
    required: &[&str],
) -> JsonValue {
    json!({
        "type": "object",
        "additionalProperties": false,
        "properties": properties,
        "required": required,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Echo;

    #[async_trait]
    impl ToolHandler for Echo {
        fn name(&self) -> &str {
            "echo"
        }
        fn description(&self) -> &str {
            "Echo"
        }
        fn parameters(&self) -> JsonValue {
            object_schema(
                serde_json::Map::from_iter([("text".into(), json!({"type": "string"}))]),
                &["text"],
            )
        }
        async fn execute(
            &self,
            input: ToolExecutionInput,
        ) -> Result<ToolExecutionResult, ToolError> {
            let text = input.arguments["text"].as_str().unwrap_or_default();
            Ok(ToolExecutionResult::text(text.to_string()))
        }
    }

    #[tokio::test]
    async fn validates_and_executes() {
        let registry = ToolRegistry::new();
        let _d = registry.register(Arc::new(Echo));
        let result = registry
            .execute(ToolExecutionInput {
                call_id: CallId::new("c"),
                name: "echo".into(),
                arguments: json!({"text": "hi"}),
            })
            .await;
        assert!(!result.is_error);
        assert_eq!(dsh_core_types::content::flatten_text(&result.content), "hi");
    }

    #[tokio::test]
    async fn unknown_tool_is_an_error_result() {
        let registry = ToolRegistry::new();
        let result = registry
            .execute(ToolExecutionInput {
                call_id: CallId::new("c"),
                name: "nope".into(),
                arguments: json!({}),
            })
            .await;
        assert!(result.is_error);
    }
}
