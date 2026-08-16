// Copyright (c) 2026 tupAI
//
// tupAI P1 — 后台录制模块
//
// 自动监测本地支持CDP/UIA的软件，录制用户操作步骤，
// 每5秒自动去重后存储在软件名的活动入口目录。
//
// 设计:
//   * 全局开关控制录制启停
//   * 自动发现CDP端口(9222-9230)和UIA窗口
//   * 录制click/type/scroll/keydown等动作
//   * 5秒周期去重(selector+action hash)
//   * 存储到 <app_data>/tupai/recording/<app_name>/

pub mod action;
pub mod flowchart;
pub mod recorder;
pub mod store;
#[cfg(windows)]
pub mod uia_poller;

use std::sync::atomic::{AtomicBool, Ordering};
use tauri::AppHandle;

/// Global "recording on/off" switch. Default enabled — 全自动静默后台录制
/// 支持的 UIA/CDP 软件，5s 存盘，去重到流程图（每个软件分别存）。
static RECORDING_ENABLED: AtomicBool = AtomicBool::new(true);

/// Check if recording is currently enabled.
pub fn is_recording_enabled() -> bool {
    RECORDING_ENABLED.load(Ordering::SeqCst)
}

/// Initialize the recording module during app setup.
/// Called from lib.rs setup hook.
pub fn init_recording(app: &AppHandle<tauri::Wry>) {
    // 检查录制目录是否存在，不存在则创建
    if let Err(e) = store::ensure_recording_dir() {
        eprintln!("[recording] failed to create recording dir: {}", e);
    }

    // 如果默认启用，则启动录制
    if is_recording_enabled() {
        recorder::start_recording(app.clone());
    }
}

/// Shutdown the recording module during app exit.
/// 只 flush 缓冲区数据到磁盘，不 drop runtime — 退出时 drop 多线程
/// tokio runtime 会 panic "Cannot drop a runtime in a context where
/// blocking is not allowed"，导致软件闪退。进程退出后 OS 自动回收资源。
pub fn shutdown_recording() {
    recorder::flush_for_exit();
}