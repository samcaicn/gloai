# DeepSeek Harness Rust 架构

[English](architecture.md) | 中文

改动 crate 边界之前请先阅读本文。会话日志与循环语义遵循 DeepSeek Harness；crate 布局遵循 BitFun。

## 目标

1. 一个 Agent Runtime，多种交付形态（`headless` CLI、ACP stdio）。宿主消费端口和会话日志，不直接调用供应商或 OS 代码。
2. 模型可见即已记录。发给模型的历史只能来自 `derive_messages()`；循环在流式请求前断言二者相等。
3. 能力缝合必须完整：端口（定义）、Provider、Consumer（通常是面向模型的工具）。
4. 组装是编译期 delivery profile 加上运行时配置。本树没有 JS 插件加载器；未注册的 `PluginRuntimePort` 必须响亮失败。

分层、轮次流程与扩展点见 [architecture.md](architecture.md)。
