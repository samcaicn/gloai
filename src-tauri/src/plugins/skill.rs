// SkillPlugin —— 技能相关的 Agent 工具。
//
// 从 lib.rs 内联注册迁移而来：execute_skill / search_and_install_skill。
// 逻辑逐字保留，仅把对 setup 作用域的闭包捕获改为从 PluginContext 克隆，
// 行为不变。详见 plugin_bus.rs 的约束说明。

use serde_json::Value;

use crate::hermes::agent_tools::ToolSpec;
use crate::hermes::tool_schemas;
use crate::plugin_bus::{Plugin, PluginContext};

pub struct SkillPlugin;

impl Plugin for SkillPlugin {
    fn name(&self) -> &str {
        "skill"
    }

    fn register(&self, ctx: &PluginContext) {
        let mut tools = ctx.tools.lock().unwrap();

        // ── execute_skill ────────────────────────────────────
        // 通过 crate::commands::skill::execute_skill 派发已安装技能执行。
        let app_h = ctx.app.clone();
        tools.register(
            ToolSpec {
                name: "execute_skill".to_string(),
                description: "执行一个已安装的技能。技能会按预定义的步骤序列自动完成操作，\
                如打开应用、填写表单、点击按钮等。适合重复性多步骤任务。".to_string(),
                parameters: tool_schemas::execute_skill_schema()["function"]["parameters"].clone(),
            },
            move |args| {
                let app_h = app_h.clone();
                Box::pin(async move {
                    let skill_id = args["skill_id"]
                        .as_str()
                        .ok_or_else(|| "missing skill_id".to_string())?
                        .to_string();
                    let _params = args.get("params").cloned().unwrap_or(Value::Null);

                    log::info!("[agent_loop] execute_skill: skill_id={}", skill_id);

                    match crate::commands::skill::execute_skill(app_h.clone(), skill_id.clone()) {
                        Ok(request_id) => Ok(serde_json::json!({
                            "skill_id": skill_id,
                            "request_id": request_id,
                            "status": "dispatched",
                            "message": "技能已启动，执行进度通过事件推送"
                        })),
                        Err(e) => {
                            log::warn!("[agent_loop] execute_skill failed: skill_id={}, err={}", skill_id, e);
                            Ok(serde_json::json!({
                                "skill_id": skill_id,
                                "status": "error",
                                "error": e
                            }))
                        }
                    }
                })
            },
            true,
        );

        // ── search_and_install_skill ─────────────────────────
        // 搜索技能市场并安装（最佳匹配）。
        let app_skill = ctx.app.clone();
        tools.register(
            ToolSpec {
                name: "search_and_install_skill".to_string(),
                description: "搜索技能市场并安装技能".to_string(),
                parameters: tool_schemas::search_and_install_skill_schema()["function"]["parameters"].clone(),
            },
            move |args: Value| {
                let app = app_skill.clone();
                Box::pin(async move {
                    let query = args.get("query").and_then(|v| v.as_str()).unwrap_or("");
                    let auto_install = args.get("auto_install").and_then(|v| v.as_bool()).unwrap_or(true);
                    let limit = args.get("limit").and_then(|v| v.as_u64()).unwrap_or(5) as usize;

                    if query.is_empty() {
                        return Ok(serde_json::json!({
                            "status": "error",
                            "error": "query is required",
                        }));
                    }

                    match crate::commands::skill_multi_market::search_multi_market(
                        app.clone(),
                        query.to_string(),
                        None,
                    ).await {
                        Ok(results) => {
                            let filtered: Vec<_> = results
                                .into_iter()
                                .filter(|r| !r.download_command.is_empty())
                                .take(limit)
                                .collect();

                            if filtered.is_empty() {
                                return Ok(serde_json::json!({
                                    "status": "no_results",
                                    "query": query,
                                    "message": "未找到匹配的技能",
                                }));
                            }

                            let mut installed = Vec::new();

                            if auto_install && !filtered.is_empty() {
                                let best = &filtered[0];
                                match crate::commands::skill_multi_market::download_market_skill(
                                    app.clone(),
                                    best.source.clone(),
                                    best.id.clone(),
                                    best.download_command.clone(),
                                ).await {
                                    Ok(dl_result) => {
                                        installed.push(serde_json::json!({
                                            "skill_id": best.id,
                                            "name": best.name,
                                            "source": best.source,
                                            "success": dl_result.success,
                                            "local_path": dl_result.local_path,
                                        }));
                                    }
                                    Err(e) => {
                                        log::warn!("[search_and_install] download failed: {}", e);
                                    }
                                }
                            }

                            Ok(serde_json::json!({
                                "status": "ok",
                                "query": query,
                                "results": filtered.iter().map(|r| {
                                    serde_json::json!({
                                        "skill_id": r.id,
                                        "name": r.name,
                                        "description": r.description,
                                        "source": r.source,
                                        "has_download": !r.download_command.is_empty(),
                                    })
                                }).collect::<Vec<_>>(),
                                "installed": installed,
                            }))
                        }
                        Err(e) => {
                            log::warn!("[search_and_install] search failed: {}", e);
                            Ok(serde_json::json!({
                                "status": "error",
                                "error": e,
                            }))
                        }
                    }
                })
            },
                true,
        );
    }
}
