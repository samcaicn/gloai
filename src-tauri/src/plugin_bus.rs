// 插件总线 —— Path A 改造的基础层。
//
// 哲学来自 DeepSeek Harness / Cordis 的"一切皆插件"模型，但**不引入 dsh 运行时**：
// 我们在已有的 ToolRegistry2 / EventBus 之上提供一个 Cordis 式 PluginContext，
// 让能力通过 `Plugin::register(ctx)` 以可插拔方式加载，而不是集中在 lib.rs 里手写。
//
// 关键约束（来自现有架构，见 AGENTS.md / lib.rs）：
//   • tauri::generate_handler! 必须在编译期固定 IPC 命令列表，插件**不能**动态新增
//     #[tauri::command]。插件只能扩展"内部工具"(ToolRegistry2) 与事件订阅(EventBus)。
//   • ToolRegistry2 使用 std::sync::Mutex，register 是同步的，持锁不可跨 .await。
//   • EventBus 的 publish/subscribe 是 async。插件的同步 `register` 不能 .await，
//     因此**异步初始化 / 事件订阅统一放在 `on_start` 生命周期钩子里**（该钩子在
//     Tauri async runtime 内异步执行，对应 Cordis 插件的启动服务）。

use std::future::Future;
use std::pin::Pin;
use std::sync::{Arc, Mutex, RwLock};

use tauri::AppHandle;
use tauri::async_runtime;

use crate::hermes::event_bus::EventBus;
use crate::hermes::tool_registry::ToolRegistry2;

/// 插件运行上下文。Clone 很廉价（内部均为 Arc / AppHandle）。
/// 与 Cordis 的 `ctx`（service + event 协作）一一对应。
#[derive(Clone)]
pub struct PluginContext {
    pub app: AppHandle,
    pub tools: Arc<Mutex<ToolRegistry2>>,
    pub bus: Arc<EventBus>,
    /// 设备鉴权 token（HermesAppState.device_token 的克隆）。
    /// mcp_call / vlm_query 等需要带 Bearer 的请求使用。
    pub device_token: Arc<RwLock<Option<String>>>,
}

/// 一个可插拔能力单元。与 Cordis 的 Plugin 等价。
pub trait Plugin: Send + Sync {
    /// 插件唯一标识（用于启动日志 / 诊断）。
    fn name(&self) -> &str;

    /// 同步注册内部工具 / 资源。不要在此跨 .await；
    /// 需要异步初始化的部分请放到 `on_start`。
    fn register(&self, ctx: &PluginContext);

    /// 异步生命周期钩子：在所有插件 `register` 完成后由插件总线在
    /// Tauri async runtime 内依次调用。可在此安全地订阅事件总线
    /// （EventBus::subscribe 是 async）。默认空实现，插件按需覆盖。
    fn on_start(&self, _ctx: &PluginContext) -> Pin<Box<dyn Future<Output = ()> + Send + '_>> {
        Box::pin(async {})
    }
}

/// 插件管理器：持有全部插件并在启动时统一加载。生命周期与 App 一致。
pub struct PluginManager {
    plugins: Vec<Arc<dyn Plugin>>,
}

impl Default for PluginManager {
    fn default() -> Self {
        Self::new()
    }
}

impl PluginManager {
    pub fn new() -> Self {
        Self {
            plugins: Vec::new(),
        }
    }

    /// 注册一个插件（新增内置插件见 `crate::plugins::register_builtin_plugins`）。
    pub fn register_plugin(&mut self, plugin: Arc<dyn Plugin>) {
        self.plugins.push(plugin);
    }

    /// 已注册插件名列表（用于诊断 / 启动日志）。
    pub fn names(&self) -> Vec<String> {
        self.plugins.iter().map(|p| p.name().to_string()).collect()
    }

    /// 统一加载所有插件：先同步 `register`，再在 async runtime 内依次 `on_start`。
    pub fn load_all(&self, ctx: &PluginContext) {
        for plugin in &self.plugins {
            log::info!("[plugin-bus] loading plugin: {}", plugin.name());
            plugin.register(ctx);
        }
        // 异步生命周期钩子：在 Tauri async runtime 内启动，允许 .await（订阅事件总线等）。
        let plugins = self.plugins.clone();
        let ctx = ctx.clone();
        async_runtime::spawn(async move {
            for plugin in &plugins {
                plugin.on_start(&ctx).await;
            }
            log::info!("[plugin-bus] all plugins on_start completed");
        });
    }
}
