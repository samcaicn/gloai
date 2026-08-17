# DeepSeek Harness（Rust）

[English](README.md) | 中文

Made by [BitFun](https://github.com/GCWing/BitFun/).

[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 的 Rust 实现：仅追加的会话日志、turn/step 智能体循环、能力端口，以及 DeepSeek 流式适配器。Crate 布局遵循 BitFun 后端分层（contracts → execution → services → adapters → assembly → apps）。

这不是 Cordis 移植。组装由 Cargo feature 与 assembly crate 完成；运行时扩展使用类型化事件与 waterfall。具体 OS 与供应商行为留在端口之后。

## 要求

- Rust 1.88+
- 真实调用需要 DeepSeek API key（`DEEPSEEK_API_KEY`）

## 快速开始

```sh
cp .env.example .env
cargo run -p dsh-cli -- --profile headless "Summarize this repository."
```

测试使用进程内 mock LLM，不需要 key：

```sh
cargo test --workspace
```

## 许可证

MIT。
