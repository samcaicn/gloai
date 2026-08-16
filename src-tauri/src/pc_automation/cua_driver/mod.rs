// Copyright (c) 2026 AIMarketing
//
// ============================================================================
// Cua Driver 集成层 — Sidecar MCP over stdio
// ============================================================================
//
// Cua Driver 是 trycua/cua 项目的跨平台输入自动化组件，提供：
//   * 后台输入（PostMessage / CGEventPostToPid / XSendEvent）
//   * 跨平台 UIA（Windows UIAutomation / macOS AXUIElement / Linux AT-SPI）
//   * 屏幕截图、光标定位
//   * 安全策略（YAML/Rego 策略 + 会话授权）
//   * 录制/回放（watch-and-learn 演示模式增强）
//
// 本模块通过 **Sidecar 模式** 集成 Cua Driver：
//   1. 以子进程方式启动 `cua-driver` 二进制
//   2. 通过 stdio 上的 JSON-RPC 2.0（MCP 协议）通信
//   3. 提供 click / type_text / press_key / hotkey / scroll / screenshot
//      等异步方法，替代 enigo 作为主要输入路径
//   4. 当 Cua Driver 不可用时自动降级到 enigo
//
// 为什么选择 Sidecar 而非 C ABI 嵌入：
//   * Cua Driver 有独立的依赖树（uniffi / cursor-overlay / pip-preview
//     / platform-* crates），直接嵌入会与 Tauri 依赖冲突
//   * MCP JSON-RPC 是 Cua Driver 的天然接口，无需 FFI 桥接
//   * 进程隔离：Cua Driver 崩溃不会影响宿主应用
//   * 跨平台：各平台独立编译，无条件编译分支
//
// 架构概览：
//
//   ┌──────────────┐     ┌───────────────────┐     ┌─────────────┐
//   │  Tauri 前端   │◄──►│  Rust 后端          │◄──►│  cua-driver  │
//   │  (React)     │     │  CuaDriverClient   │     │  (Sidecar)   │
//   │              │     │  - spawn process   │     │  MCP Server  │
//   │  health chk  │     │  - JSON-RPC 2.0    │     │  (stdio)     │
//   │  tool invoke │     │  - tool dispatch   │     │              │
//   └──────────────┘     └───────────────────┘     └─────────────┘
//
// 二进制查找顺序：
//   1. CUA_DRIVER_PATH 环境变量（覆盖）
//   2. 开发路径：up/cua/target/{debug,release}/cua-driver[.exe]
//   3. 可执行文件同目录：cua-driver[.exe]
//   4. Tauri 资源目录（生产环境）
// ============================================================================

pub mod client;
pub mod uia_enhance;

pub use client::CuaDriverClient;
pub use client::CuaDriverHealth;

use std::path::PathBuf;

/// 解析 cua-driver 二进制路径。
///
/// 查找顺序见模块文档。返回 `None` 表示未找到可用二进制。
pub fn resolve_binary_path() -> Option<PathBuf> {
    // 1. 环境变量覆盖
    if let Ok(path) = std::env::var("CUA_DRIVER_PATH") {
        let p = PathBuf::from(path);
        if p.is_file() {
            return Some(p);
        }
    }

    // 2. 开发路径 — up/cua/target/{debug,release}/
    let exe_ext = if cfg!(windows) { ".exe" } else { "" };
    let bin_name = format!("cua-driver{}", exe_ext);

    // 工作区根目录（src-tauri 的父目录）
    let workspace_root = env!("CARGO_MANIFEST_DIR")
        .trim_end_matches("src-tauri")
        .trim_end_matches(std::path::MAIN_SEPARATOR_STR);

    for profile in &["debug", "release"] {
        let dev_path = PathBuf::from(workspace_root)
            .join("up")
            .join("cua")
            .join("target")
            .join(profile)
            .join(&bin_name);
        if dev_path.is_file() {
            return Some(dev_path);
        }
    }

    // 3. Tauri sidecar 目录 — CI 构建的二进制存放位置
    //    文件名格式: cua-driver-<target-triple>[.exe]
    //    target-triple 例: x86_64-pc-windows-msvc, aarch64-apple-darwin
    let target_triple = std::env::consts::ARCH.to_string()
        + "-"
        + match std::env::consts::OS {
            "windows" => "pc-windows-msvc",
            "macos" => "apple-darwin",
            "linux" => "unknown-linux-gnu",
            _ => "unknown",
        };
    let sidecar_name = format!("cua-driver-{}{}", target_triple, exe_ext);

    // 在 src-tauri/binaries/ 目录查找
    let sidecar_path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("binaries")
        .join(&sidecar_name);
    if sidecar_path.is_file() {
        return Some(sidecar_path);
    }

    // 4. 可执行文件同目录
    if let Ok(exe) = std::env::current_exe() {
        if let Some(parent) = exe.parent() {
            // 先找无 target-triple 后缀的
            let sibling = parent.join(&bin_name);
            if sibling.is_file() {
                return Some(sibling);
            }
            // 再找有 target-triple 后缀的
            let sibling_targeted = parent.join(&sidecar_name);
            if sibling_targeted.is_file() {
                return Some(sibling_targeted);
            }
        }
    }

    // 5. PATH 查找（最后手段）
    if let Ok(path_dirs) = std::env::var("PATH") {
        for dir in path_dirs.split(if cfg!(windows) { ';' } else { ':' }) {
            let p = PathBuf::from(dir).join(&bin_name);
            if p.is_file() {
                return Some(p);
            }
        }
    }

    None
}

/// Cua Driver 输入动作类型。用于统一描述 click / type / hotkey 等操作，
/// 供 `CuaDriverClient::perform_input` 分发。
#[derive(Debug, Clone)]
pub enum CuaInputAction {
    /// 左键单击屏幕坐标
    Click { x: i32, y: i32 },
    /// 左键双击
    DoubleClick { x: i32, y: i32 },
    /// 右键单击
    RightClick { x: i32, y: i32 },
    /// 输入文本
    TypeText { text: String },
    /// 按下单个键（如 "Return", "Escape"）
    PressKey { key: String },
    /// 组合键（如 "ctrl+c"）
    Hotkey { keys: String },
    /// 滚动
    Scroll { dx: i32, dy: i32 },
    /// 移动鼠标
    MoveCursor { x: i32, y: i32 },
    /// 等待
    Wait { ms: u64 },
}
