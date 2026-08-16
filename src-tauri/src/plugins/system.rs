// 系统诊断插件。
//
// 演示插件如何通过一个工具（tool handler 是 async）访问事件总线（ctx.bus），
// 以及如何通过 `on_start` 生命周期钩子在启动时订阅事件总线——这是 Cordis 式
// "service + event 协作"的核心：插件不直接新增 IPC 命令，而是扩展内部工具能力
// 并参与事件总线。对应 on_start 与 register 的分工：同步资源注册放 register，
// 异步订阅放 on_start。
//
// 该插件为纯增量诊断能力，不影响任何现有行为。

use serde_json::Value;

use std::future::Future;

use crate::hermes::agent_tools::ToolSpec;
use crate::plugin_bus::{Plugin, PluginContext};

/// 系统诊断插件：暴露 plugin_bus_status 工具，并在启动时订阅诊断事件。
pub struct SystemPlugin;

/// 插件内部使用的诊断事件主题。plugin_bus_status 发布到此主题，
/// on_start 的订阅者会收到，形成"发布—订阅"闭环演示。
const DIAGNOSTIC_TOPIC: &str = "plugin:diagnostic";

impl Plugin for SystemPlugin {
    fn name(&self) -> &str {
        "system"
    }

    fn register(&self, ctx: &PluginContext) {
        // handler 是 async，因此把 Arc 句柄 clone 进闭包，在 async 块内安全使用。
        let bus = ctx.bus.clone();
        let tools = ctx.tools.clone();
        ctx.tools.lock().unwrap().register(
            ToolSpec {
                name: "plugin_bus_status".to_string(),
                description: "返回插件总线状态：已注册内部工具数量，并向诊断事件总线发布一条事件".to_string(),
                parameters: serde_json::json!({
                    "type": "object",
                    "properties": {},
                }),
            },
            move |_args: Value| {
                let bus = bus.clone();
                let tools = tools.clone();
                Box::pin(async move {
                    // 读取工具表（std::sync::Mutex，持锁不跨 await，读后立刻释放）。
                    let tools_registered = tools.lock().unwrap().list().len();

                    // 发布一条诊断事件到 DIAGNOSTIC_TOPIC；on_start 的订阅者会收到。
                    // publish 内部已统一 tokio::spawn，不会在 handler 内嵌套 spawn。
                    let _ = bus
                        .publish(
                            DIAGNOSTIC_TOPIC,
                            serde_json::json!({
                                "from": "plugin_bus_status",
                                "tools_registered": tools_registered,
                            }),
                        )
                        .await;

                    Ok(serde_json::json!({
                        "status": "ok",
                        "plugin_bus": "active",
                        "tools_registered": tools_registered,
                    }))
                })
            },
            true,
        );
    }

    fn on_start(&self, ctx: &PluginContext) -> std::pin::Pin<Box<dyn Future<Output = ()> + Send + '_>> {
        let bus = ctx.bus.clone();
        Box::pin(async move {
            // 异步生命周期钩子：安全地订阅事件总线（subscribe 是 async）。
            bus.subscribe(DIAGNOSTIC_TOPIC, |payload: Value| async move {
                log::info!("[system-plugin] diagnostic event received: {}", payload);
            })
            .await;
            log::info!("[system-plugin] subscribed to topic '{}'", DIAGNOSTIC_TOPIC);
        })
    }
}
