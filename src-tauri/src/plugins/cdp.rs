// CDP 浏览器自动化插件。
//
// 把原本内联在 lib.rs setup 钩子里的 `ensure_cdp_browser` 工具，以插件方式加载。
// handler 逻辑逐字保留，仅把闭包体抽成独立 async 函数，行为完全不变。
// 这是"将现有工具迁移为插件"的范本：新增工具不必再改 lib.rs 的命令注册区。

use serde_json::Value;
use tauri::{AppHandle, Emitter};

use crate::commands::pc_automation;
use crate::hermes::agent_tools::{ToolResult, ToolSpec};
use crate::hermes::tool_schemas;
use crate::plugin_bus::{Plugin, PluginContext};

/// CDP 浏览器插件：按需启动并连接 CDP 浏览器。
pub struct CdpPlugin;

impl Plugin for CdpPlugin {
    fn name(&self) -> &str {
        "cdp"
    }

    fn register(&self, ctx: &PluginContext) {
        let app_handle = ctx.app.clone();
        ctx.tools.lock().unwrap().register(
            ToolSpec {
                name: "ensure_cdp_browser".to_string(),
                description: "确保 CDP 浏览器已连接".to_string(),
                parameters: tool_schemas::ensure_cdp_browser_schema()["function"]["parameters"].clone(),
            },
            move |args: Value| {
                let app_handle = app_handle.clone();
                Box::pin(ensure_cdp_browser_handler(app_handle, args))
            },
            true,
        );

        // ── cdp_action ──────────────────────────────────────
        // 通过 Chrome DevTools Protocol 控制浏览器（Electron/Chrome）。
        ctx.tools.lock().unwrap().register(
            ToolSpec {
                name: "cdp_action".to_string(),
                description: "通过 Chrome DevTools Protocol 控制浏览器（Electron/Chrome）。\
                适用于 Web 应用自动化。".to_string(),
                parameters: tool_schemas::cdp_action_schema()["function"]["parameters"].clone(),
            },
            move |args| {
                Box::pin(async move {
                    let action = args["action"]
                        .as_str()
                        .ok_or_else(|| "missing action".to_string())?
                        .to_string();
                    let target = args["target"].as_str().unwrap_or("").to_string();
                    let value = args["value"].as_str().unwrap_or("").to_string();

                    log::info!("[agent_loop] cdp_action: action={}, target={}", action, target);

                    let state = pc_automation::shared_state();
                    let cdp = &state.router.cdp;

                    if let Err(e) = cdp.attach_or_launch(None) {
                        return Ok(serde_json::json!({
                            "action": action,
                            "status": "error",
                            "error": format!("CDP attach failed: {}", e)
                        }));
                    }

                    use crate::pc_automation::cdp::types::{CdpAction, CdpSelector, CdpMouseButton};

                    let cdp_action = match action.as_str() {
                        "navigate" => CdpAction::Navigate(value.clone()),
                        "click" => CdpAction::Click {
                            sel: CdpSelector {
                                css: if target.starts_with("//") { None } else { Some(target.clone()) },
                                xpath: if target.starts_with("//") { Some(target.clone()) } else { None },
                                ..Default::default()
                            },
                            button: CdpMouseButton::Left,
                        },
                        "type" => CdpAction::Type {
                            sel: CdpSelector {
                                css: if target.starts_with("//") { None } else { Some(target.clone()) },
                                xpath: if target.starts_with("//") { Some(target.clone()) } else { None },
                                ..Default::default()
                            },
                            text: value.clone(),
                        },
                        "screenshot" => CdpAction::Evaluate(
                            "({screenshot: document.documentElement.outerHTML.substring(0, 5000)})".to_string()
                        ),
                        "evaluate" => CdpAction::Evaluate(value.clone()),
                        other => return Ok(serde_json::json!({
                            "action": action,
                            "status": "error",
                            "error": format!("unknown CDP action: {}", other)
                        })),
                    };

                    match cdp.send(cdp_action) {
                        Ok(result) => Ok(serde_json::json!({
                            "action": action,
                            "status": if result.success { "completed" } else { "error" },
                            "result": result.return_value,
                            "error": result.error,
                            "latency_ms": result.latency_ms,
                        })),
                        Err(e) => Ok(serde_json::json!({
                            "action": action,
                            "status": "error",
                            "error": e
                        })),
                    }
                })
            },
            true,
        );
    }
}

async fn ensure_cdp_browser_handler(app_handle: AppHandle, args: Value) -> ToolResult {
    let browser_type = args
        .get("browser_type")
        .and_then(|v| v.as_str())
        .map(|s| s.to_string());
    let notify_user = args
        .get("notify_user")
        .and_then(|v| v.as_bool())
        .unwrap_or(true);

    // 1) 检查 CDP 是否已连接
    let cdp_connected = pc_automation::check_cdp().unwrap_or(false);

    if cdp_connected {
        return Ok(serde_json::json!({
            "status": "already_connected",
            "message": "CDP 浏览器已连接",
        }));
    }

    // 2) 通知用户浏览器将重启（通过前端事件）
    if notify_user {
        let _ = app_handle.emit(
            "cdp-browser-launch-request",
            serde_json::json!({
                "reason": "技能执行需要浏览器控制",
                "browser_type": browser_type.as_deref().unwrap_or("auto"),
                "current_status": "CDP 未连接",
            }),
        );
    }

    // 3) 启动浏览器
    match pc_automation::launch_cdp_browser(browser_type.clone()).await {
        Ok(info) => {
            log::info!("[ensure_cdp] browser launched: {}", info);

            // 4) 等待 CDP 就绪（最多 8 秒）
            let mut tries = 0u8;
            loop {
                if tries >= 8 {
                    break;
                }
                tokio::time::sleep(std::time::Duration::from_secs(1)).await;
                tries += 1;
                if let Ok(true) = pc_automation::check_cdp() {
                    return Ok(serde_json::json!({
                        "status": "launched",
                        "browser": info,
                        "message": "CDP 浏览器已启动并连接就绪",
                    }));
                }
            }

            Ok(serde_json::json!({
                "status": "launched_not_ready",
                "browser": info,
                "message": "浏览器已启动但 CDP 尚未就绪，请稍后重试",
            }))
        }
        Err(e) => {
            log::error!("[ensure_cdp] launch failed: {}", e);
            Ok(serde_json::json!({
                "status": "error",
                "error": e,
            }))
        }
    }
}
