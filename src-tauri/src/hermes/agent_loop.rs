// Copyright (c) 2026 AIMarketing
//
// Hermes ReAct Agent Loop — 工具调用主循环。
//
// 工作流程:
//   call_llm(messages, tools) → parse tool_calls → dispatch → append tool result → loop
//
// 关键设计:
//   • max_iterations=10 防止无限循环
//   • token_budget=6000 硬熔断
//   • 单轮多个 tool_call 并行执行
//   • tool 失败不中断循环，让 LLM 决定是否重试
//   • 所有 I/O 通过 mcp_call_v2_inner，不直接调 LLM

use serde::{Deserialize, Serialize};
use std::sync::Arc;

use super::agent_tools::{ToolResult, ToolSpec};
use super::tool_registry::ToolRegistry2;
use super::tool_schemas::builtin_tool_schemas;
use super::types::{VLMMessage, VLMToolCall, VLMResponse};

// ── 错误类型 ────────────────────────────────────────────────────────────────

#[derive(thiserror::Error, Debug)]
pub enum AgentLoopError {
    #[error("LLM 调用失败: {0}")]
    LlmError(String),
    #[error("工具执行失败: {0}")]
    ToolError(String),
    #[error("工具不存在: {0}")]
    ToolNotFound(String),
    #[error("超过最大迭代次数 ({0})")]
    MaxIterations(u32),
    #[error("Token 预算超限")]
    TokenBudgetExceeded { used: u32, limit: u32 },
    #[error("循环中止: {0}")]
    Stopped(String),
}

impl Serialize for AgentLoopError {
    fn serialize<S>(&self, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: serde::Serializer,
    {
        serializer.serialize_str(&self.to_string())
    }
}

// ── 配置 ───────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentLoopConfig {
    /// 最大 ReAct 迭代次数，防止无限循环
    pub max_iterations: u32,
    /// Token 预算上限（累计 completion_tokens），超限强制退出
    pub token_budget: u32,
    /// 单个 tool 调用的超时（秒）
    pub tool_timeout_secs: u64,
    /// 是否启用工具调用（可通过配置关闭，退化为纯聊天）
    pub tools_enabled: bool,
}

impl Default for AgentLoopConfig {
    fn default() -> Self {
        Self {
            max_iterations: 10,
            token_budget: 6000,
            tool_timeout_secs: 30,
            tools_enabled: true,
        }
    }
}

// ── AgentLoop ───────────────────────────────────────────────────────────────

/// ReAct 主循环持有者。无状态 — 每次 run() 接收独立的 messages vec。
pub struct AgentLoop {
    pub tools: Arc<std::sync::Mutex<ToolRegistry2>>,
    pub config: AgentLoopConfig,
}

impl AgentLoop {
    pub fn new(tools: Arc<std::sync::Mutex<ToolRegistry2>>) -> Self {
        Self {
            tools,
            config: AgentLoopConfig::default(),
        }
    }

    pub fn with_config(tools: Arc<std::sync::Mutex<ToolRegistry2>>, config: AgentLoopConfig) -> Self {
        Self { tools, config }
    }

    /// 运行 ReAct 循环直到 LLM 不再请求工具。
    ///
    /// `messages` 会边循环边追加，最终包含完整的 assistant+tool 对话历史。
    /// 返回 LLM 最终的文本回复（非 tool_call 的纯文本消息）。
    pub async fn run(
        &self,
        messages: &mut Vec<VLMMessage>,
        session_id: &str,
        token: Option<&str>,
    ) -> Result<String, AgentLoopError> {
        if !self.config.tools_enabled {
            return self.run_once(messages, token).await;
        }

        let mut total_tokens: u32 = 0;
        let mut iterations: u32 = 0;

        loop {
            iterations += 1;
            if iterations > self.config.max_iterations {
                return Err(AgentLoopError::MaxIterations(iterations));
            }

            let tool_schemas = self.get_tool_schemas();
            let resp = self
                .call_llm(messages, &tool_schemas, session_id, token)
                .await
                .map_err(AgentLoopError::LlmError)?;

            if let Some(usage) = &resp.usage {
                total_tokens += usage.completion_tokens;
                if total_tokens > self.config.token_budget {
                    return Err(AgentLoopError::TokenBudgetExceeded {
                        used: total_tokens,
                        limit: self.config.token_budget,
                    });
                }
            }

            let assistant_msg = VLMMessage {
                role: "assistant".to_string(),
                content: resp.content.clone().unwrap_or_default(),
                tool_calls: resp.tool_calls.clone(),
                ..Default::default()
            };
            messages.push(assistant_msg);

            let tool_calls = match &resp.tool_calls {
                Some(tc) if !tc.is_empty() => tc,
                _ => return Ok(resp.content.clone().unwrap_or_default()),
            };

            log::info!(
                "[agent_loop] iter={} 调用 {} 个工具: {}",
                iterations,
                tool_calls.len(),
                tool_calls.iter().map(|tc| tc.function.name.clone()).collect::<Vec<_>>().join(", ")
            );

            let tool_results: Vec<(VLMToolCall, ToolResult)> =
                Self::execute_tools_parallel(tool_calls, &self.tools, self.config.tool_timeout_secs)
                    .await;

            for (tc, result) in tool_results {
                let output_str = match result {
                    Ok(v) => serde_json::to_string(&v).unwrap_or_else(|_| "{}".to_string()),
                    Err(e) => serde_json::json!({ "error": e }).to_string(),
                };
                messages.push(VLMMessage {
                    role: "tool".to_string(),
                    content: output_str,
                    tool_call_id: Some(tc.id.clone()),
                    name: Some(tc.function.name.clone()),
                    ..Default::default()
                });
            }
        }
    }

    async fn run_once(&self, messages: &mut Vec<VLMMessage>, token: Option<&str>) -> Result<String, AgentLoopError> {
        let schemas = self.get_tool_schemas();
        let resp = self
            .call_llm(messages, &schemas, "", token)
            .await
            .map_err(AgentLoopError::LlmError)?;
        messages.push(VLMMessage {
            role: "assistant".to_string(),
            content: resp.content.clone().unwrap_or_default(),
            ..Default::default()
        });
        Ok(resp.content.unwrap_or_default())
    }

    async fn execute_tools_parallel(
        tool_calls: &[VLMToolCall],
        tools: &Arc<std::sync::Mutex<ToolRegistry2>>,
        timeout_secs: u64,
    ) -> Vec<(VLMToolCall, ToolResult)> {
        use futures::future;
        use std::time::Duration;

        let futures: Vec<_> = tool_calls
            .iter()
            .cloned()
            .map(|tc| {
                let tools = tools.clone();
                async move {
                    let args: serde_json::Value = serde_json::from_str(&tc.function.arguments)
                        .unwrap_or(serde_json::Value::Null);
                    // 在锁内获取 Arc<ToolFn>，立即释放锁，再在锁外 .await
                    // 避免跨 await 持有 std::sync::MutexGuard（Send 违规 + 死锁风险）
                    let tool_fn = {
                        let guard = tools.lock().unwrap();
                        guard.get_fn(&tc.function.name)
                    };
                    let result = tokio::time::timeout(
                        Duration::from_secs(timeout_secs),
                        async {
                            match tool_fn {
                                Some(f) => f(args).await,
                                None => Err(format!("tool not found: {}", tc.function.name)),
                            }
                        },
                    )
                    .await;
                    (tc, result)
                }
            })
            .collect();

        let results = future::join_all(futures).await;
        results
            .into_iter()
            .map(|(tc, result)| {
                let result = match result {
                    Ok(r) => r,
                    Err(_) => Err(format!("工具 {} 执行超时 ({}s)", tc.function.name, timeout_secs)),
                };
                (tc, result)
            })
            .collect()
    }

    fn get_tool_schemas(&self) -> Vec<serde_json::Value> {
        let registered: Vec<serde_json::Value> = self
            .tools
            .lock().unwrap()
            .list()
            .into_iter()
            .map(|spec: ToolSpec| {
                serde_json::json!({
                    "type": "function",
                    "function": {
                        "name": spec.name,
                        "description": spec.description,
                        "parameters": spec.parameters,
                    }
                })
            })
            .collect();

        let mut all = builtin_tool_schemas();
        all.extend(registered);
        all
    }

    async fn call_llm(
        &self,
        messages: &[VLMMessage],
        tools: &[serde_json::Value],
        session_id: &str,
        token: Option<&str>,
    ) -> Result<VLMResponse, String> {
        use std::time::Duration;

        let client = reqwest::Client::builder()
            .no_proxy()
            .timeout(Duration::from_secs(120))
            .build()
            .map_err(|e| format!("HTTP client build failed: {}", e))?;

        let msgs: Vec<serde_json::Value> = messages
            .iter()
            .map(|m| {
                let mut map = serde_json::Map::new();
                map.insert("role".to_string(), serde_json::Value::String(m.role.clone()));
                map.insert("content".to_string(), serde_json::Value::String(m.content.clone()));
                if let Some(name) = &m.name {
                    map.insert("name".to_string(), serde_json::Value::String(name.clone()));
                }
                if let Some(tcid) = &m.tool_call_id {
                    map.insert("tool_call_id".to_string(), serde_json::Value::String(tcid.clone()));
                }
                if let Some(tcs) = &m.tool_calls {
                    map.insert("tool_calls".to_string(), serde_json::to_value(tcs).unwrap_or_default());
                }
                serde_json::Value::Object(map)
            })
            .collect();

        let params = serde_json::json!({
            "session_id": session_id,
            "messages": msgs,
            "stream": false,
            "tools": tools,
        });

        let resp = crate::commands::mcp_proxy::mcp_call_v2_inner(&client, "llm.stream_request", params, token).await?;

        let content = resp
            .get("data")
            .and_then(|d| d.get("content"))
            .and_then(|c| c.as_str())
            .map(String::from);

        let tool_calls = resp
            .get("data")
            .and_then(|d| d.get("tool_calls"))
            .and_then(|tc| serde_json::from_value(tc.clone()).ok());

        Ok(VLMResponse {
            content,
            tool_calls,
            finish_reason: None,
            usage: None,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn agent_loop_config_default_values() {
        let config = AgentLoopConfig::default();
        assert_eq!(config.max_iterations, 10);
        assert_eq!(config.token_budget, 6000);
        assert_eq!(config.tool_timeout_secs, 30);
        assert!(config.tools_enabled);
    }

    #[test]
    fn agent_loop_new_creates_with_default_config() {
        let tools = Arc::new(std::sync::Mutex::new(ToolRegistry2::new()));
        let loop_ = AgentLoop::new(tools);
        assert_eq!(loop_.config.max_iterations, 10);
    }

    #[test]
    fn get_tool_schemas_returns_builtins_plus_registered() {
        let tools = Arc::new(std::sync::Mutex::new(ToolRegistry2::new()));
        let loop_ = AgentLoop::new(tools);
        let schemas = loop_.get_tool_schemas();
        assert!(schemas.len() >= 6, "should have at least 6 builtin schemas, got {}", schemas.len());
    }

    #[tokio::test]
    async fn tool_registry_invoke_works() {
        let mut registry = ToolRegistry2::new();
        registry.register(
            ToolSpec { name: "test_tool".into(), description: "test".into(), parameters: serde_json::json!({}) },
            |_args| Box::pin(async { Ok(serde_json::json!({"ok": true})) }),
            true,
        );
        let result = registry.invoke("test_tool", serde_json::json!({})).await;
        assert!(result.is_ok());
        assert_eq!(result.unwrap()["ok"], true);
    }

    #[tokio::test]
    async fn tool_registry_invoke_not_found() {
        let mut registry = ToolRegistry2::new();
        let result = registry.invoke("nonexistent", serde_json::json!({})).await;
        assert!(result.is_err());
    }
}
