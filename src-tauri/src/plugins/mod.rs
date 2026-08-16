// 内置插件注册表。新增插件只需：
//   1) 在 plugins/ 下新增一个模块（实现 crate::plugin_bus::Plugin）；
//   2) 在此文件 `pub mod` 声明它，并在 `register_builtin_plugins` 追加一行。
//
// 插件机制详见 crate::plugin_bus。这里只负责"装配"哪些插件随 App 启动。

pub mod cdp;
pub mod mcp;
pub mod memory;
pub mod pc_automation;
pub mod skill;
pub mod system;

use std::sync::Arc;

use crate::plugin_bus::PluginManager;

/// 注册所有内置插件。setup 钩子调用此函数完成装配。
pub fn register_builtin_plugins(mgr: &mut PluginManager) {
    mgr.register_plugin(Arc::new(cdp::CdpPlugin));
    mgr.register_plugin(Arc::new(mcp::McpPlugin));
    mgr.register_plugin(Arc::new(memory::MemoryPlugin));
    mgr.register_plugin(Arc::new(pc_automation::PcAutomationPlugin));
    mgr.register_plugin(Arc::new(skill::SkillPlugin));
    mgr.register_plugin(Arc::new(system::SystemPlugin));
}
