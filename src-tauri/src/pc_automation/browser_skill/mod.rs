// Copyright (c) 2026 tupAI
//
// BrowserSkill 集成层 —— 腾讯开源浏览器自动化工具
// (https://github.com/Tencent/BrowserSkill) 的本地 CLI (`bsk`) 桥接。
//
// 定位：独立的「浏览器 Agent 驱动」后端，**不**进入
// `CDP -> UIA -> OCR -> VLM` 感知级联，也**不**替代 CDP。
// CDP 是挂到任意 Electron/Chromium 窗口 DevTools 的底层感知原语；
// BrowserSkill 是操作「用户已登录的真实浏览器」的高层 skill 驱动
// （复用登录态、独立 Agent 窗口、不干扰用户）。两者并行互补。
//
// 通过子进程方式调用 `bsk` CLI（与 cua_driver sidecar 模式类似），
// 经 Tauri 命令 `browser_skill_*` 暴露给前端。
//
// 自动安装：CLI 缺失时由 `BskCliBackend::ensure_installed` 运行官方
// 一键安装脚本（装到 ~/.local/bin）；浏览器扩展无法静默安装，只能由
// `status` 检测 + 前端深链商店引导用户手动添加。

pub mod backend;
pub mod stub;
pub mod types;

// Re-export the trait + impls so downstream code (commands module,
// integration tests) can `use pc_automation::browser_skill::{...}`
// without reaching into sub-modules. `#[allow(unused_imports)]`
// because the stub is only consumed by `#[cfg(test)]` modules.
#[allow(unused_imports)]
pub use backend::{BskCliBackend, BrowserSkillBackend};
#[allow(unused_imports)]
pub use stub::StubBrowserSkillBackend;
#[allow(unused_imports)]
pub use types::{BrowserSkillAction, BrowserSkillResult, BrowserSkillStatus};
