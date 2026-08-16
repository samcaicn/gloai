// PcAutomationPlugin —— 桌面 / 浏览器自动化工具（UIA / OCR / VLM 救援层）。
//
// 从 lib.rs 内联注册迁移而来：uia_action / pc_execute_step / vlm_query。
// 逻辑逐字保留，app handle 与 device_token 改为从 PluginContext 克隆。

use serde_json::Value;

use crate::hermes::agent_tools::ToolSpec;
use crate::hermes::tool_schemas;
use crate::plugin_bus::{Plugin, PluginContext};

pub struct PcAutomationPlugin;

impl Plugin for PcAutomationPlugin {
    fn name(&self) -> &str {
        "pc_automation"
    }

    fn register(&self, ctx: &PluginContext) {
        let mut tools = ctx.tools.lock().unwrap();

        // ── uia_action ──────────────────────────────────────
        // 通过 Windows UI Automation 控制桌面应用。
        tools.register(
            ToolSpec {
                name: "uia_action".to_string(),
                description: "通过 Windows UI Automation 控制桌面应用。适用于原生 Windows 应用。".to_string(),
                parameters: tool_schemas::uia_action_schema()["function"]["parameters"].clone(),
            },
            move |args| {
                Box::pin(async move {
                    let action = args["action"]
                        .as_str()
                        .ok_or_else(|| "missing action".to_string())?
                        .to_string();
                    let window_title = args["window_title"].as_str().unwrap_or("");
                    let control_id = args["control_id"].as_str().unwrap_or("");
                    let value = args["value"].as_str().unwrap_or("");

                    log::info!("[agent_loop] uia_action: action={}, window={}", action, window_title);

                    let state = crate::commands::pc_automation::shared_state();
                    let uia = &state.router.uia;

                    use crate::pc_automation::uia::types::UiaSelector;

                    match action.as_str() {
                        "click" => {
                            let sel = UiaSelector {
                                name: if window_title.is_empty() { None } else { Some(window_title.to_string()) },
                                automation_id: if control_id.is_empty() { None } else { Some(control_id.to_string()) },
                                ..Default::default()
                            };
                            match uia.find_by(&sel) {
                                Ok(Some(node)) => {
                                    match uia.click(&node) {
                                        Ok(()) => Ok(serde_json::json!({
                                            "action": "click",
                                            "status": "completed",
                                            "node_name": node.name,
                                        })),
                                        Err(e) => Ok(serde_json::json!({
                                            "action": "click",
                                            "status": "error",
                                            "error": e
                                        })),
                                    }
                                }
                                Ok(None) => Ok(serde_json::json!({
                                    "action": "click",
                                    "status": "error",
                                    "error": "control not found"
                                })),
                                Err(e) => Ok(serde_json::json!({
                                    "action": "click",
                                    "status": "error",
                                    "error": e
                                })),
                            }
                        }
                        "type" => {
                            let sel = UiaSelector {
                                name: if window_title.is_empty() { None } else { Some(window_title.to_string()) },
                                automation_id: if control_id.is_empty() { None } else { Some(control_id.to_string()) },
                                ..Default::default()
                            };
                            match uia.find_by(&sel) {
                                Ok(Some(node)) => {
                                    match uia.type_text(&node, value) {
                                        Ok(()) => Ok(serde_json::json!({
                                            "action": "type",
                                            "status": "completed",
                                            "text": value,
                                        })),
                                        Err(e) => Ok(serde_json::json!({
                                            "action": "type",
                                            "status": "error",
                                            "error": e
                                        })),
                                    }
                                }
                                Ok(None) => Ok(serde_json::json!({
                                    "action": "type",
                                    "status": "error",
                                    "error": "control not found"
                                })),
                                Err(e) => Ok(serde_json::json!({
                                    "action": "type",
                                    "status": "error",
                                    "error": e
                                })),
                            }
                        }
                        "hotkey" => {
                            if !window_title.is_empty() {
                                let sel = UiaSelector {
                                    name: Some(window_title.to_string()),
                                    ..Default::default()
                                };
                                if let Ok(Some(node)) = uia.find_by(&sel) {
                                    let _ = uia.click(&node);
                                }
                            }
                            Ok(serde_json::json!({
                                "action": "hotkey",
                                "status": "completed",
                                "keys": value,
                                "note": "hotkey dispatched via UIA focus + keyboard simulation"
                            }))
                        }
                        "wait" => {
                            let duration_ms = value.parse::<u64>().unwrap_or(1000);
                            tokio::time::sleep(tokio::time::Duration::from_millis(duration_ms)).await;
                            Ok(serde_json::json!({
                                "action": "wait",
                                "status": "completed",
                                "duration_ms": duration_ms,
                            }))
                        }
                        other => Ok(serde_json::json!({
                            "action": action,
                            "status": "error",
                            "error": format!("unknown UIA action: {}", other)
                        })),
                    }
                })
            },
            true,
        );

        // ── pc_execute_step ─────────────────────────────────
        // 三策略路由器 (CDP→UIA→OCR→VLM) 执行自动化步骤。
        tools.register(
            ToolSpec {
                name: "pc_execute_step".to_string(),
                description: "通过三策略路由器执行自动化步骤".to_string(),
                parameters: tool_schemas::pc_execute_step_schema()["function"]["parameters"].clone(),
            },
            move |args: Value| {
                Box::pin(async move {
                    let action = args.get("action").and_then(|v| v.as_str()).unwrap_or("screenshot");
                    let selector = args.get("selector").and_then(|v| v.as_str()).unwrap_or("");
                    let value = args.get("value").and_then(|v| v.as_str()).unwrap_or("");
                    let strategy = args.get("strategy").and_then(|v| v.as_str()).unwrap_or("auto");

                    use crate::commands::pc_automation::{PcStepView, execute_step, parse_screen};

                    if action == "parse_screen" || action == "screenshot" {
                        match parse_screen(None) {
                            Ok(elements) => {
                                return Ok(serde_json::json!({
                                    "action": action,
                                    "elements": elements,
                                    "count": elements.len(),
                                }));
                            }
                            Err(e) => {
                                return Ok(serde_json::json!({
                                    "action": action,
                                    "status": "error",
                                    "error": e,
                                }));
                            }
                        }
                    }

                    if action == "wait" {
                        let ms = value.parse::<u64>().unwrap_or(1000);
                        tokio::time::sleep(std::time::Duration::from_millis(ms)).await;
                        return Ok(serde_json::json!({
                            "action": "wait",
                            "ms": ms,
                        }));
                    }

                    let step_view = PcStepView {
                        id: format!("tool-{}", uuid::Uuid::new_v4()),
                        description: format!("{}:{}:{}", action, selector, value),
                        app_profile: None,
                        strategy: strategy.to_string(),
                        primary_selector: selector.to_string(),
                        fallback_selectors: vec![],
                        recorded_coords: None,
                    };

                    match execute_step(step_view).await {
                        Ok(result) => Ok(serde_json::json!({
                            "action": action,
                            "ok": result.ok,
                            "step_id": result.step_id,
                            "outcome": result.outcome,
                            "error": result.error,
                        })),
                        Err(e) => Ok(serde_json::json!({
                            "action": action,
                            "status": "error",
                            "error": e,
                        })),
                    }
                })
            },
            true,
        );

        // ── vlm_query ────────────────────────────────────────
        // 汇总 UIA 树 + CDP 上下文，调用 LLM 回答屏幕相关问题。
        let dt_for_vlm = ctx.device_token.clone();
        tools.register(
            ToolSpec {
                name: "vlm_query".to_string(),
                description: "用视觉语言模型分析屏幕截图并回答问题。用于复杂界面或 CDP/UIA 都无法处理的场景。".to_string(),
                parameters: tool_schemas::vlm_query_schema()["function"]["parameters"].clone(),
            },
            move |args| {
                let dt = dt_for_vlm.clone();
                Box::pin(async move {
                    let question = args["question"]
                        .as_str()
                        .ok_or_else(|| "missing question".to_string())?
                        .to_string();

                    log::info!("[agent_loop] vlm_query: question={}", question);

                    let pc_state = crate::commands::pc_automation::shared_state();
                    let uia = &pc_state.router.uia;

                    let screen_context = match uia.get_focused_window() {
                        Ok(Some(root)) => {
                            let mut ctx = format!("当前窗口: {}", root.name);
                            if let Ok(root_node) = uia.get_root() {
                                ctx.push_str(&format!("\n窗口树(根): name={}, control_type={}", root_node.name, root_node.control_type));
                            }
                            ctx
                        }
                        Ok(None) => "无活动窗口".to_string(),
                        Err(e) => format!("获取窗口信息失败: {}", e),
                    };

                    let cdp = &pc_state.router.cdp;
                    let browser_context = match cdp.attach_or_launch(None) {
                        Ok(_) => {
                            use crate::pc_automation::cdp::types::CdpAction;
                            match cdp.send(CdpAction::Evaluate(
                                "document.title + ' | ' + document.location.href".to_string()
                            )) {
                                Ok(result) if result.success => {
                                    result.return_value.unwrap_or_default()
                                }
                                _ => String::new(),
                            }
                        }
                        Err(_) => String::new(),
                    };

                    let client = reqwest::Client::builder()
                        .no_proxy()
                        .timeout(std::time::Duration::from_secs(60))
                        .build()
                        .map_err(|e| format!("HTTP client build failed: {}", e))?;

                    let token: Option<String> = dt.read().ok().and_then(|guard| guard.clone());

                    let prompt = format!(
                        "屏幕上下文:\n{}\n浏览器上下文:\n{}\n\n问题: {}",
                        screen_context, browser_context, question
                    );

                    let params = serde_json::json!({
                        "session_id": "vlm-query",
                        "messages": [{
                            "role": "user",
                            "content": prompt
                        }],
                        "stream": false,
                    });

                    match crate::commands::mcp_proxy::mcp_call_v2_inner(
                        &client, "llm.stream_request", params, token.as_deref(),
                    ).await {
                        Ok(resp) => {
                            let content = resp.get("data")
                                .and_then(|d| d.get("content"))
                                .and_then(|c| c.as_str())
                                .map(String::from)
                                .unwrap_or_else(|| serde_json::to_string(&resp).unwrap_or_default());
                            Ok(serde_json::json!({
                                "question": question,
                                "answer": content,
                                "screen_context": screen_context,
                            }))
                        }
                        Err(e) => {
                            Ok(serde_json::json!({
                                "question": question,
                                "status": "error",
                                "error": format!("LLM analysis failed: {}", e),
                                "screen_context": screen_context,
                            }))
                        }
                    }
                })
            },
            true,
        );
    }
}
