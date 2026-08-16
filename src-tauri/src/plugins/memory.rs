// MemoryPlugin —— 本地长记忆检索。
//
// 从 lib.rs 内联注册迁移而来：memory_search。
// 逻辑逐字保留，app handle 改为从 PluginContext 克隆。

use crate::hermes::agent_tools::ToolSpec;
use crate::hermes::tool_schemas;
use crate::plugin_bus::{Plugin, PluginContext};

pub struct MemoryPlugin;

impl Plugin for MemoryPlugin {
    fn name(&self) -> &str {
        "memory"
    }

    fn register(&self, ctx: &PluginContext) {
        let mut tools = ctx.tools.lock().unwrap();

        // ── memory_search ───────────────────────────────────
        // 通过 memory_evolution 命令搜索本地长时记忆。
        let app_h = ctx.app.clone();
        tools.register(
            ToolSpec {
                name: "memory_search".to_string(),
                description: "搜索本地的长记忆，查找之前完成的任务、操作步骤或决策记录。".to_string(),
                parameters: tool_schemas::memory_search_schema()["function"]["parameters"].clone(),
            },
            move |args| {
                let app_h = app_h.clone();
                Box::pin(async move {
                    let query = args["query"]
                        .as_str()
                        .ok_or_else(|| "missing query".to_string())?
                        .to_string();
                    let limit = args["limit"].as_u64().map(|n| n as usize);

                    log::info!("[agent_loop] memory_search: query={}", query);

                    match crate::commands::memory_evolution::memory_search(
                        app_h.clone(), query.clone(), None, limit,
                    ) {
                        Ok(results) => Ok(serde_json::json!({
                            "query": query,
                            "results": results,
                            "count": results.len(),
                        })),
                        Err(e) => {
                            log::warn!("[agent_loop] memory_search failed: query={}, err={}", query, e);
                            Ok(serde_json::json!({
                                "query": query,
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
