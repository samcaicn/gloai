# Hermes Tool Calling 能力实现计划

> 文档版本: v1.0
> 日期: 2026-07-23
> 状态: 待实现

---

## 一、现状诊断

### 1.1 架构全景

```
┌──────────────────────────────────────────────────────────────────┐
│                        用户自然语言输入                            │
└──────────────────────────┬───────────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│ 1. Intent Discovery (缺失)                                        │
│    • LLM 分类 + skill.search 召回                                  │
│    • 返回: {intent, candidates[]}                                  │
└──────────────────────────┬───────────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│ 2. Tool Spec Builder (缺失)                                       │
│    • 把候选 skill + MCP actions + CDP/UIA 操作                     │
│      编译成 OpenAI tools schema                                    │
└──────────────────────────┬───────────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│ 3. LLM.stream_request (部分就绪)                                   │
│    ❌ 当前不传 tools 字段 → LLM 不知道能调什么                      │
│    ✅ OpenAI ↔ Responses API 翻译层完整                            │
└──────────────────────────┬───────────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│ 4. Tool Call Router (缺失 — 核心)                                 │
│    • 解析 function_call                                            │
│    • name → ToolRegistry2 handler                                 │
│    • 调度到: skill / MCP / CDP / UIA / VLM                        │
│    • 收集 output，拼成 role:tool 消息                              │
│    • 再次调 LLM，直到 finish_reason=stop                           │
└──────────────────────────┬───────────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│ 5. HermesAppState.tools (空壳)                                    │
│    ❌ ToolRegistry2::new() 后从未注册任何 handler                  │
└──────────────────────────┬───────────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│ 6. 执行器 (完整)                                                  │
│    ✅ execute_skill → McpRuntime                                  │
│    ✅ mcp_call_v2 → 任意 MCP action                               │
│    ✅ CDP/UIA/OCR/VLM → AdaptiveExecutor                          │
│    (但完全独立于 LLM chat 流，从未被 chat 调用过)                   │
└──────────────────────────────────────────────────────────────────┘
```

### 1.2 能力矩阵

| 能力 | 状态 | 文件 | 说明 |
|------|------|------|------|
| `VLMToolCall` 类型定义 | ✅ | `hermes/types.rs:47-58` | 已定义 |
| LLM 流式 tool_calls 解析 | ✅ | `llm_service.rs:614-654` | OpenAI → Responses 翻译完整 |
| `chattoolevent` 后端 emit | ✅ | `legacy.rs:1068-1101` | 单向 fire-and-forget |
| `ToolRegistry2` 数据结构 | ⚠️ | `tool_registry.rs` | 空壳，无 handler |
| `HermesAgent::call` | ⚠️ | `agent.rs:224` | 单次调用，无 loop |
| **ReAct Agent Loop** | ❌ | — | **完全缺失** |
| **Intent Discovery** | ❌ | — | **完全缺失**（只有离线 SessionAnalyzer） |
| 工具描述注入 LLM | ❌ | `mcp_proxy.rs:253` / `legacy.rs:938` | 永远不传 tools 字段 |
| 前端 tool 事件消费 | ❌ | `TupaiChatScene.tsx` | 完全忽略 tool 事件 |
| 技能/MCP/CDP/UIA 后端 | ✅ | `automation/` / `pc_automation/` | 完整但与 LLM 脱钩 |

### 1.3 证据链

**证据1: tools 字段从未传入**
```rust
// mcp_proxy.rs:253 — mcp_call_v2_inner 的 body 中没有 tools
let body = serde_json::json!({ "action": action, "params": params });
// params 由调用方构造，chat 流中从不含 tools

// legacy.rs:938 — chat_stream 也不含 tools
let body = serde_json::json!({
    "model": request_model,
    "input": input,
    "previous_response_id": previous_response_id,
    "stream": true
});  // ❌ 没有 tools: []
```

**证据2: ToolRegistry 从未注册**
```bash
$ rg "tools\.register|registry\.register" src-tauri/src --type=rust
# 0 匹配
```

**证据3: ReAct Loop 完全缺失**
```rust
// legacy.rs:1068-1101 — function_call_output 后直接 return
"function_call_output" => {
    // ... emit tool event ...
    let _ = app.emit("chattoolevent", tool_event);
}  // ❌ 收集后没有 loop，没有回填 messages
// 直接到 response.completed → return
```

**证据4: 前端忽略 tool 事件**
```typescript
// TupaiChatScene.tsx:942-969 — stream 消费只处理 content/error
for await (const chunk of stream) {
  if (chunk.type === 'content') { typer.push(fullContent); }
  else if (chunk.type === 'error') { ... }
  // ❌ 没有 else if (chunk.type === 'tool_event')
}
```

---

## 二、实现架构

### 2.1 总体架构

```
用户消息
   │
   ├──────────────────────────────────────────────────────────┐
   │ Intent Router (可选，非阻塞)                              │
   │ • LLM 轻量分类 intent                                   │
   │ • skill.search 召回候选                                  │
   │ • 预构建 tool_schemas 缓存                              │
   └──────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  AgentLoop.run(messages, session_id, token)                  │
│  ─────────────────────────────────────────────────────────  │
│  while iter < max_iterations:                                │
│    1. tools = registry.list_schemas()                       │
│    2. resp = call_llm(messages, tools)                      │
│    3. append assistant msg                                  │
│    4. if resp.tool_calls.is_empty(): break                  │
│    5. for tc in resp.tool_calls:                            │
│         result = dispatch(tc.name, tc.arguments)             │
│         append tool msg (role:tool, tool_call_id, output)   │
│    6. if token_budget exhausted: break                      │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
                    Final Text / Error
```

### 2.2 模块依赖关系

```
hermes/
├── mod.rs                    # HermesAppState 新增 tools 字段初始化
├── tool_registry.rs          # 已有框架 → 填充 handler
├── tool_schemas.rs           # 【新建】OpenAI tool schema 编译器
├── agent_loop.rs             # 【新建】ReAct 主循环
├── intent_router.rs          # 【新建】运行时意图发现
└── llm_service.rs            # 改造：tools 参数真实传入

commands/
├── mcp_proxy.rs              # 改造：支持 tools 透传
├── legacy.rs                 # 改造：chat 流改为 ReAct
└── skill.rs                  # 改造：加 call_id 支持

src/
└── lib.rs                    # 改造：setup_hook 中注册所有 tool handler
```

---

## 三、详细实现

### 3.1 Phase 1: 核心框架

#### 3.1.1 新建 `hermes/tool_schemas.rs`

```rust
// Copyright (c) 2026 tupAI
//
// Tool schema compiler — 把所有可用能力翻译成 OpenAI function calling schema。
// AgentLoop 在每次 LLM 调用前用这些 schema 注入 tools 字段。

use serde::{Deserialize, Serialize};

// ── execute_skill ────────────────────────────────────────────────────────────

pub fn execute_skill_schema() -> serde_json::Value {
    serde_json::json!({
        "type": "function",
        "function": {
            "name": "execute_skill",
            "description": "执行一个已安装的技能。技能会按预定义的步骤序列自动完成操作，\
                如打开应用、填写表单、点击按钮等。适合重复性多步骤任务。",
            "parameters": {
                "type": "object",
                "properties": {
                    "skill_id": {
                        "type": "string",
                        "description": "技能 ID，如 'wechat-publisher', 'open-notepad'"
                    },
                    "params": {
                        "type": "object",
                        "description": "技能输入参数，JSON 对象",
                        "additionalProperties": true
                    }
                },
                "required": ["skill_id"]
            }
        }
    })
}

// ── mcp_call ───────────────────────────────────────────────────────────────

pub fn mcp_call_schema() -> serde_json::Value {
    serde_json::json!({
        "type": "function",
        "function": {
            "name": "mcp_call",
            "description": "调用云端 MCP 工具，执行搜索、任务管理、日历、文档等操作。",
            "parameters": {
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "description": "MCP action 名称，如 'skill.search', 'task.poll_pending'",
                        "enum": [
                            "skill.search",
                            "skill.scene_tags",
                            "skill.top_by_tags",
                            "model.list",
                            "task.poll_pending",
                            "task.complete",
                            "calendar.list",
                            "calendar.create",
                            "doc.read",
                            "doc.write"
                        ]
                    },
                    "params": {
                        "type": "object",
                        "description": "action 参数",
                        "additionalProperties": true
                    }
                },
                "required": ["action", "params"]
            }
        }
    })
}

// ── CDP 操作 ────────────────────────────────────────────────────────────────

pub fn cdp_action_schema() -> serde_json::Value {
    serde_json::json!({
        "type": "function",
        "function": {
            "name": "cdp_action",
            "description": "通过 Chrome DevTools Protocol 控制浏览器（Electron/Chrome）。\
                适用于 Web 应用自动化。",
            "parameters": {
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "enum": ["navigate", "click", "type", "screenshot", "evaluate"]
                    },
                    "target": {
                        "type": "string",
                        "description": "CSS 选择器或 XPath"
                    },
                    "value": {
                        "type": "string",
                        "description": "操作值（如输入文本、URL）"
                    }
                },
                "required": ["action"]
            }
        }
    })
}

// ── UIA 操作 ───────────────────────────────────────────────────────────────

pub fn uia_action_schema() -> serde_json::Value {
    serde_json::json!({
        "type": "function",
        "function": {
            "name": "uia_action",
            "description": "通过 Windows UI Automation 控制桌面应用。适用于原生 Windows 应用。",
            "parameters": {
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "enum": ["click", "type", "hotkey", "wait"]
                    },
                    "window_title": {
                        "type": "string",
                        "description": "窗口标题（部分匹配）"
                    },
                    "control_id": {
                        "type": "string",
                        "description": "控件 AutomationId"
                    },
                    "value": {
                        "type": "string",
                        "description": "操作值"
                    }
                },
                "required": ["action"]
            }
        }
    })
}

// ── VLM 查询 ───────────────────────────────────────────────────────────────

pub fn vlm_query_schema() -> serde_json::Value {
    serde_json::json!({
        "type": "function",
        "function": {
            "name": "vlm_query",
            "description": "用视觉语言模型分析屏幕截图并回答问题。用于复杂界面或 CDP/UIA 都无法处理的场景。",
            "parameters": {
                "type": "object",
                "properties": {
                    "question": {
                        "type": "string",
                        "description": "要回答的问题"
                    },
                    "region": {
                        "type": "object",
                        "description": "截图区域坐标 {x, y, width, height}，不填则截全屏"
                    }
                },
                "required": ["question"]
            }
        }
    })
}

// ── 记忆搜索 ───────────────────────────────────────────────────────────────

pub fn memory_search_schema() -> serde_json::Value {
    serde_json::json!({
        "type": "function",
        "function": {
            "name": "memory_search",
            "description": "搜索本地的长记忆，查找之前完成的任务、操作步骤或决策记录。",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "搜索关键词"
                    },
                    "limit": {
                        "type": "integer",
                        "description": "返回结果数量上限",
                        "default": 5
                    }
                },
                "required": ["query"]
            }
        }
    })
}

// ── 聚合 ─────────────────────────────────────────────────────────────────

/// 返回所有内置工具的 OpenAI schema 数组
pub fn builtin_tool_schemas() -> Vec<serde_json::Value> {
    vec![
        execute_skill_schema(),
        mcp_call_schema(),
        cdp_action_schema(),
        uia_action_schema(),
        vlm_query_schema(),
        memory_search_schema(),
    ]
}
```

#### 3.1.2 新建 `hermes/agent_loop.rs`

```rust
// Copyright (c) 2026 tupAI
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

use crate::hermes::agent_tools::{ToolResult, ToolSpec};
use crate::hermes::tool_registry::ToolRegistry2;
use crate::hermes::tool_schemas::builtin_tool_schemas;
use crate::hermes::types::{VLMMessage, VLMToolCall, VLMResponse};
use crate::commands::mcp_proxy::mcp_call_v2_inner;

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
    pub tools: Arc<ToolRegistry2>,
    pub config: AgentLoopConfig,
}

impl AgentLoop {
    pub fn new(tools: Arc<ToolRegistry2>) -> Self {
        Self {
            tools,
            config: AgentLoopConfig::default(),
        }
    }

    pub fn with_config(tools: Arc<ToolRegistry2>, config: AgentLoopConfig) -> Self {
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
                content: resp.content.clone(),
                tool_calls: resp.tool_calls.clone(),
                ..Default::default()
            };
            messages.push(assistant_msg);

            let Some(tool_calls) = &resp.tool_calls else {
                return Ok(resp.content.clone().unwrap_or_default());
            };

            if tool_calls.is_empty() {
                return Ok(resp.content.clone().unwrap_or_default());
            }

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

    async fn run_once(&self, messages: &mut [VLMMessage], token: Option<&str>) -> Result<String, AgentLoopError> {
        let schemas = self.get_tool_schemas();
        let resp = self
            .call_llm(messages, &schemas, "", token)
            .await
            .map_err(AgentLoopError::LlmError)?;
        messages.push(VLMMessage {
            role: "assistant".to_string(),
            content: resp.content.clone(),
            ..Default::default()
        });
        Ok(resp.content.unwrap_or_default())
    }

    async fn execute_tools_parallel(
        tool_calls: &[VLMToolCall],
        tools: &Arc<ToolRegistry2>,
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
                    let result = tokio::time::timeout(
                        Duration::from_secs(timeout_secs),
                        tools.lock().unwrap().invoke(&tc.function.name, args),
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
                serde_json::Value::Object(map)
            })
            .collect();

        let params = serde_json::json!({
            "session_id": session_id,
            "messages": msgs,
            "stream": false,
            "tools": tools,
        });

        let resp = mcp_call_v2_inner(&client, "llm.stream_request", params, token).await?;

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
```

### 3.2 Phase 2: 联通现有模块

#### 3.2.1 改造 `hermes/llm_service.rs`

修复 `_tools` 参数被忽略的问题：

```rust
// 位置: llm_service.rs:108 附近
async fn openai_complete_collect(
    &self,
    messages: Vec<VLMMessage>,
    tools: Option<Vec<serde_json::Value>>,  // ← 不再是 _tools
) -> Result<VLMResponse, String> {
    let mut body = serde_json::json!({
        "model": self.cfg.model,
        "messages": messages,
        "max_tokens": self.cfg.max_tokens,
        "temperature": self.cfg.temperature,
    });
    // ← 把 tools 真实传入 body
    if let Some(ts) = tools {
        body["tools"] = serde_json::Value::Array(ts);
    }
    // ...
}
```

#### 3.2.2 改造 `commands/legacy.rs` — chat 流接入 AgentLoop

```rust
// 改动位置: legacy.rs execute_stream 函数
"response.completed" => {
    let final_content = if !tool_results.is_empty() {
        let agent_loop = app
            .try_state::<Arc<hermes::agent_loop::AgentLoop>>()
            .cloned();
        match agent_loop {
            Some(loop_) => {
                let mut agent_messages: Vec<VLMMessage> = messages.to_vec();
                let result = loop_.run(
                    &mut agent_messages,
                    &session_id,
                    token.as_deref(),
                ).await;
                match result {
                    Ok(text) => text,
                    Err(e) => format!("[AgentLoop 错误] {}", e),
                }
            }
            None => {
                log::warn!("[legacy] AgentLoop 未注册，降级为纯聊天模式");
                String::new()
            }
        }
    } else {
        String::new()
    };
    let _ = app.emit("chatdone", serde_json::json!({ "requestId": request_id }));
    return Ok(latest_response_id);
}
```

### 3.3 Phase 3: Tool Registry 注册

#### 3.3.1 改造 `lib.rs` — setup_hook 中注册所有工具

```rust
// 位置: lib.rs setup_hook 中 HermesAppState 初始化之后

let hermes_state = app.state::<hermes::HermesAppState>();
let tools = hermes_state.tools.clone();
let agent_loop = Arc::new(hermes::AgentLoop::new(tools.clone()));
app.manage(agent_loop.clone());

{
    let mut tools_guard = tools.lock().unwrap();

    // ── execute_skill ───────────────────────────────────────────────────
    tools_guard.register(
        ToolSpec {
            name: "execute_skill".to_string(),
            description: "执行一个已安装的技能".to_string(),
            parameters: hermes::tool_schemas::execute_skill_schema()
                ["function"]["parameters"]
                .clone(),
        },
        |args| {
            Box::pin(async move {
                let skill_id = args["skill_id"]
                    .as_str()
                    .ok_or_else(|| "missing skill_id".to_string())?
                    .to_string();
                let params = args["params"]
                    .as_object()
                    .cloned()
                    .unwrap_or_default();
                let receipt = crate::commands::skill::execute_skill_sync(
                    app.clone(), skill_id, params,
                ).await
                .map_err(|e| e.to_string())?;
                Ok(serde_json::to_value(receipt).unwrap_or_default())
            })
        },
        true,
    );

    // ── mcp_call ───────────────────────────────────────────────────────
    tools_guard.register(
        ToolSpec {
            name: "mcp_call".to_string(),
            description: "调用云端 MCP 工具".to_string(),
            parameters: hermes::tool_schemas::mcp_call_schema()
                ["function"]["parameters"]
                .clone(),
        },
        |args| {
            Box::pin(async move {
                let action = args["action"]
                    .as_str()
                    .ok_or_else(|| "missing action".to_string())?
                    .to_string();
                let params = args["params"].clone();
                let result = crate::commands::mcp_proxy::mcp_call_v2_inner(
                    &reqwest::Client::new(),
                    &action,
                    params,
                    None,
                )
                .await?;
                Ok(result)
            })
        },
        true,
    );
}

log::info!("[setup] Hermes AgentLoop 初始化完成，已注册 {} 个工具", tools.lock().unwrap().list().len());
```

### 3.4 Phase 4: 意图发现（可选增强）

#### 3.4.1 新建 `hermes/intent_router.rs`

```rust
// Copyright (c) 2026 tupAI
//
// 运行时意图发现 — 在 ReAct Loop 之前运行（可选，非阻塞）。

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IntentTag {
    pub tag: String,
    pub confidence: f32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IntentRoutingResult {
    pub primary_intent: String,
    pub intent_tags: Vec<IntentTag>,
    pub candidate_skills: Vec<CandidateSkill>,
    pub suggested_tools: Vec<String>,
    pub confidence: f32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CandidateSkill {
    pub skill_id: String,
    pub name: String,
    pub description: String,
    pub score: f32,
}

pub struct IntentRouter;

impl IntentRouter {
    pub async fn route(
        &self,
        user_message: &str,
        token: Option<&str>,
    ) -> Result<IntentRoutingResult, String> {
        let (primary_intent, intent_tags, confidence) = self.classify_intent(user_message, token).await?;
        let candidate_skills = self.search_skills(&primary_intent, token).await;
        let suggested_tools = self.suggest_tools(&primary_intent);

        Ok(IntentRoutingResult {
            primary_intent,
            intent_tags,
            candidate_skills,
            suggested_tools,
            confidence,
        })
    }

    async fn classify_intent(
        &self,
        message: &str,
        _token: Option<&str>,
    ) -> Result<(String, Vec<IntentTag>, f32), String> {
        // TODO: 实现 LLM 分类
        Ok((
            "general".to_string(),
            vec![IntentTag { tag: "general".to_string(), confidence: 0.5 }],
            0.5,
        ))
    }

    async fn search_skills(&self, intent: &str, _token: Option<&str>) -> Vec<CandidateSkill> {
        // TODO: 调用 skill.search MCP
        Vec::new()
    }

    fn suggest_tools(&self, intent: &str) -> Vec<String> {
        match intent {
            "browser_automation" => vec!["cdp_action", "vlm_query"],
            "desktop_automation" => vec!["uia_action", "vlm_query"],
            "information_query" => vec!["mcp_call", "memory_search"],
            "skill_execution" => vec!["execute_skill"],
            _ => vec![
                "execute_skill",
                "mcp_call",
                "memory_search",
                "cdp_action",
                "uia_action",
            ],
        }
    }
}
```

### 3.5 Phase 5: 前端适配

#### 3.5.1 改造 `TupaiChatScene.tsx`

```typescript
// 添加 tool event 监听和显示

const [toolCallInProgress, setToolCallInProgress] = useState<{
  name: string;
  callId: string;
} | null>(null);
const toolResultsRef = useRef<Array<{ callId: string; output: string }>>([]);

useEffect(() => {
  const handleToolEvent = (event: ChatToolEvent) => {
    if (event.phase === 'started') {
      setToolCallInProgress({
        name: event.name || 'unknown',
        callId: event.callId || '',
      });
    } else if (event.phase === 'completed') {
      if (event.callId && event.output) {
        toolResultsRef.current.push({
          callId: event.callId,
          output: Array.isArray(event.output)
            ? event.output.join('\n')
            : String(event.output),
        });
      }
      setToolCallInProgress(null);
    }
  };

  const unlisten = listen<ChatToolEvent>('chattoolevent', handleToolEvent);
  return () => { unlisten.then(fn => fn()); };
}, []);
```

#### 3.5.2 改造 `llm.ts`

```typescript
export interface LlmRequest {
  sessionId: string;
  messages: LlmMessage[];
  model?: string;
  tools?: ToolSchema[];  // ← 新增
}

const params: Record<string, unknown> = {
  session_id: req.sessionId,
  messages: req.messages,
  stream: true,
};
if (req.tools && req.tools.length > 0) {
  params.tools = req.tools;
}
```

---

## 四、Feature Flag 设计

```rust
#[cfg(feature = "agent_loop")]
{
    let agent_loop = Arc::new(hermes::AgentLoop::new(tools));
    app.manage(agent_loop.clone());
}

#[cfg(not(feature = "agent_loop"))]
{
    log::info!("[setup] AgentLoop 未启用 (feature flag off)，chat 流保持兼容模式");
}
```

默认关闭，Cargo.toml 中不声明 `agent_loop` feature，老 chat 流完全不受影响。

---

## 五、实现顺序与里程碑

### 阶段一: 核心框架 (1-2天)
- [ ] `hermes/tool_schemas.rs` — 工具 schema 定义
- [ ] `hermes/agent_loop.rs` — ReAct 主循环
- [ ] 改造 `llm_service.rs` — 真实传入 tools 参数

### 阶段二: 联通 (1天)
- [ ] 改造 `lib.rs` — setup_hook 中注册工具 handler
- [ ] 改造 `legacy.rs` — chat 流接入 AgentLoop
- [ ] 验证: cargo test 通过

### 阶段三: 前端 (0.5天)
- [ ] 改造 `llm.ts` — tools 参数透传
- [ ] 改造 `TupaiChatScene.tsx` — tool 事件监听
- [ ] 端到端测试

### 阶段四: 意图路由 (1天，可选)
- [ ] `hermes/intent_router.rs`
- [ ] 与 AgentLoop 集成

---

## 六、风险缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| `legacy.rs` 改 ReAct 导致现有 chat 回归 | 中 | 高 | Feature flag，默认关闭 |
| 工具调用超时阻塞循环 | 中 | 中 | `tokio::time::timeout(30s)` |
| Token 预算超限 | 低 | 低 | 硬熔断 `max_iterations=10` |
| MCP llm.stream_request 不支持 tools | 中 | 高 | 先验证 dev-mode mock |

---

## 七、测试计划

### 7.1 单元测试
```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_agent_loop_max_iterations() {
        let tools = Arc::new(ToolRegistry2::new());
        let loop_ = AgentLoop::with_config(
            tools,
            AgentLoopConfig { max_iterations: 2, ..Default::default() },
        );
        let mut messages = vec![
            VLMMessage { role: "user".into(), content: "Hello".into(), ..Default::default() }
        ];
        // mock LLM 始终返回 tool_calls
        let result = loop_.run(&mut messages, "test", None).await;
        assert!(matches!(result, Err(AgentLoopError::MaxIterations(2))));
    }

    #[tokio::test]
    async fn test_tool_registry_invoke() {
        let mut registry = ToolRegistry2::new();
        registry.register(
            ToolSpec { name: "test".into(), description: "".into(), parameters: serde_json::json!({}) },
            |_args| Box::pin(async { Ok(serde_json::json!({"ok": true})) }),
            true,
        );
        let result = registry.invoke("test", serde_json::json!({})).await;
        assert!(result.is_ok());
    }
}
```

### 7.2 集成测试
- Mock `mcp_call_v2_inner` 返回带 `tool_calls` 的 LLM 响应
- 验证 AgentLoop 正确解析、执行、回填
- 验证超过 max_iterations 熔断

### 7.3 端到端测试
- 用户消息 "帮我打开微信" → AgentLoop 调用 execute_skill → 验证技能执行

---

## 八、附录: 关键文件索引

| 文件 | 改动 | 行数 |
|------|------|------|
| `src-tauri/src/hermes/tool_schemas.rs` | 新建 | ~200 |
| `src-tauri/src/hermes/agent_loop.rs` | 新建 | ~250 |
| `src-tauri/src/hermes/intent_router.rs` | 新建 | ~120 |
| `src-tauri/src/hermes/llm_service.rs` | 改造 | 1-2 |
| `src-tauri/src/hermes/mod.rs` | 改造 | 1 |
| `src-tauri/src/lib.rs` | 改造 | ~60 |
| `src-tauri/src/commands/legacy.rs` | 改造 | ~30 |
| `src-tauri/src/commands/skill.rs` | 改造 | ~10 |
| `src/web-ui/.../llm.ts` | 改造 | ~5 |
| `src/web-ui/.../TupaiChatScene.tsx` | 改造 | ~30 |

**总改动量**: 新建 ~570 行，改造 ~130 行
