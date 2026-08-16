// ACP (Agent Client Protocol) — 简化 CLI 接入层。
//
// 从 BitFun 上游项目 (https://github.com/GCWing/BitFun) 的
// `src/crates/interfaces/acp/` 精简而来。BitFun 原实现依赖
// bitfun-core / bitfun-agent-tools / bitfun-events 等内部 crate，
// 本模块仅保留 CLI stdio 接入的核心路径：
//
//   - config: JSON 配置文件读写（acp_clients.json）
//   - service: 客户端进程生命周期 + 会话/对话管理
//   - commands: Tauri 命令暴露给前端 ACPClientAPI.ts
//
// 支持的 ACP CLI 工具（通过 stdio JSON-RPC 通信）：
//   - claude-code: `npx --yes @zed-industries/claude-code-acp@latest`
//   - codex:       `npx --yes @zed-industries/codex-acp@latest`
//   - opencode:    `opencode acp`
//   - omp:         `omp acp`
//   - 任意自定义 ACP 兼容 CLI

pub mod commands;
pub mod config;
pub mod service;

pub use service::AcpClientService;
