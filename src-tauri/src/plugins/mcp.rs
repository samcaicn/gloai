// McpPlugin —— 云端 MCP 工具调用。
//
// 从 lib.rs 内联注册迁移而来：mcp_call。
// 逻辑逐字保留，device_token 改为从 PluginContext 克隆。

use crate::hermes::agent_tools::ToolSpec;
use crate::hermes::tool_schemas;
use crate::plugin_bus::{Plugin, PluginContext};

pub struct McpPlugin;

impl Plugin for McpPlugin {
    fn name(&self) -> &str {
        "mcp"
    }

    fn register(&self, ctx: &PluginContext) {
        let mut tools = ctx.tools.lock().unwrap();

        // ── mcp_call ─────────────────────────────────────────
        // 通过 mcp_call_v2_inner 调用云端 MCP 服务，Bearer 鉴权用 device_token。
        let dt_for_mcp = ctx.device_token.clone();
        tools.register(
            ToolSpec {
                name: "mcp_call".to_string(),
                description: "调用云端 MCP 工具，执行搜索、任务管理、日历、文档等操作。".to_string(),
                parameters: tool_schemas::mcp_call_schema()["function"]["parameters"].clone(),
            },
            move |args| {
                let dt = dt_for_mcp.clone();
                Box::pin(async move {
                    let action = args["action"]
                        .as_str()
                        .ok_or_else(|| "missing action".to_string())?
                        .to_string();
                    let params = args["params"].clone();

                    log::info!("[agent_loop] mcp_call: action={}", action);

                    let client = reqwest::Client::builder()
                        .no_proxy()
                        .timeout(std::time::Duration::from_secs(60))
                        .build()
                        .map_err(|e| format!("HTTP client build failed: {}", e))?;

                    let token: Option<String> = dt.read().ok().and_then(|guard| guard.clone());

                    match crate::commands::mcp_proxy::mcp_call_v2_inner(
                        &client, &action, params, token.as_deref(),
                    ).await {
                        Ok(resp) => Ok(resp),
                        Err(e) => {
                            log::warn!("[agent_loop] mcp_call failed: action={}, err={}", action, e);
                            Ok(serde_json::json!({
                                "action": action,
                                "status": "error",
                                "error": e
                            }))
                        }
                    }
                })
            },
                true,
        );
    }
}
