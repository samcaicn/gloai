// Copyright (c) 2026 AIMarketing
//
// 系统通知命令 — 任务完成展示（OS 级桌面通知）。
//
// 背景：前端 `useDialogCompletionNotify` 在「对话轮次完成 + 窗口失焦」
// 时调用 `systemAPI.sendSystemNotification(title, body)` → invoke
// `send_system_notification`，用于「任务完成展示」。但 tupai 后端此前
// 未实现该命令，导致后台任务完成时收不到桌面通知。
//
// 本模块用 tauri-plugin-notification 发系统通知（Windows toast /
// macOS 通知中心 / Linux libnotify）。插件已在 Cargo.toml 声明，
// 需在 lib.rs 用 `.plugin(tauri_plugin_notification::init())` 初始化。
//
// 前端契约（service-api/SystemAPI.ts）：
//   send_system_notification({ request: { title, body } }) → void

#![allow(dead_code)]

use serde::Deserialize;
use tauri::AppHandle;
use tauri_plugin_notification::NotificationExt;

/// `send_system_notification` 入参：`{ request: { title, body? } }`。
#[derive(Deserialize, Debug)]
pub struct NotificationRequest {
    pub title: String,
    #[serde(default)]
    pub body: Option<String>,
}

/// 发送一条 OS 级桌面通知。任务完成 / 后台事件通知统一走这里。
///
/// best-effort：通知失败（用户未授权 / 平台不支持）只记日志并返回
/// 错误字符串，调用方（前端）已 try/catch 兜底，不会中断主流程。
#[tauri::command]
pub async fn send_system_notification(
    app: AppHandle,
    request: NotificationRequest,
) -> Result<(), String> {
    let title = request.title.trim();
    if title.is_empty() {
        return Err("通知标题不能为空".to_string());
    }
    let mut builder = app.notification().builder().title(title);
    if let Some(body) = request.body.as_deref().filter(|s| !s.is_empty()) {
        builder = builder.body(body);
    }
    builder
        .show()
        .map_err(|e| format!("发送系统通知失败: {}", e))?;
    log::debug!("[notification] sent: {}", title);
    Ok(())
}
