// Copyright (c) 2026 MeeJoy
//
// tupAI P1 §5 — tray 常驻
//
// We deliberately keep the tray menu *tiny* (4 entries, per
// plan.md §3.5). The Tauri 2 tray API requires the `tray-icon`
// feature on the `tauri` crate (already enabled in Cargo.toml),
// and the menu is built via `tauri::menu::Menu` so the entries
// are properly localised by the OS.

use std::sync::{Arc, OnceLock};

use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter, Manager, Wry};

use crate::commands::floating_window::FloatingWindowState;
use crate::upgrade::UpgradeManager;

/// Shared manager handle so the tray callbacks can reach the
/// upgrade state without having to fish it out of the Tauri
/// state. The handle is set exactly once during `setup_tray`.
static MANAGER: OnceLock<Arc<UpgradeManager>> = OnceLock::new();

/// 悬浮聊天浮窗的固定 id —— 与前端 ChatFloaterButton / ChatFloaterWindow
/// 约定的 `FLOATER_ID` 保持一致。托盘点击时按这个 id 做 toggle。
const CHAT_FLOATER_ID: &str = "chat-floater";

/// 托盘"悬浮聊天"点击的 toggle 行为：
///   * state 中无该 id 的 entry           → 新建浮窗（fw_open）
///   * entry 存在但已 docked/minimized    → 还原（fw_restore）
///   * entry 存在且 visible              → 贴边隐藏（fw_minimize）
///
/// 通过直接读写 FloatingWindowState 避免循环 invoke 自身的 IPC 桥。
fn toggle_chat_floater(app: &AppHandle) {
    // 一次 try_state 同时拿到 entry 与可调用方法的 State 句柄，
    // 避免重复 lookup。State<T> derefs 到 &T，可直接调方法。
    let Some(state) = app.try_state::<FloatingWindowState>() else {
        return;
    };
    let entry = state
        .snapshot()
        .into_iter()
        .find(|e| e.id == CHAT_FLOATER_ID);

    match entry {
        None => {
            // 第一次唤起：走 fw_open。FloatingWindowState::open 内部会
            // 懒建 webview + show + focus，state 也会自动 emit。
            // 尺寸与前端 ChatFloaterButton.tsx 的 fwOpen 调用保持一致
            // （width 240 / height 400），避免从托盘唤起时浮窗大小不一。
            let _ = state.open(
                app,
                crate::commands::floating_window::OpenWindowInput {
                    id: CHAT_FLOATER_ID.to_string(),
                    title: Some("快速聊天".to_string()),
                    width: 240,
                    height: 400,
                    min_width: 280,
                    min_height: 180,
                    position: None,
                    anchor: None,
                    payload: None,
                },
            );
        }
        Some(e) if e.docked => {
            // 贴边中：还原回正常位置（fw_restore 同步清 docked + show）。
            let _ = state.restore(app, CHAT_FLOATER_ID);
        }
        Some(_) => {
            // 已显示：贴边隐藏（保持输入内容，下次唤起 fw_restore 即可）。
            let _ = state.minimize(app, CHAT_FLOATER_ID);
        }
    }
}

/// Initialises the system tray. Returns `Err` if the icon cannot be
/// registered (typically because the OS refused the icon image).
pub fn setup_tray(app: &AppHandle<Wry>) -> Result<(), String> {
    let manager = app
        .try_state::<Arc<UpgradeManager>>()
        .ok_or_else(|| "UpgradeManager not registered in Tauri state".to_string())?
        .inner()
        .clone();
    MANAGER
        .set(manager.clone())
        .map_err(|_| "tray already initialised".to_string())?;

    let open_main = MenuItem::with_id(app, "open_main", "打开主界面", true, None::<&str>)
        .map_err(|e| e.to_string())?;
    let chat_floater =
        MenuItem::with_id(app, "chat_floater", "悬浮聊天", true, None::<&str>)
            .map_err(|e| e.to_string())?;
    let check_updates = MenuItem::with_id(app, "check_updates", "立即检查更新", true, None::<&str>)
        .map_err(|e| e.to_string())?;
    let quit = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)
        .map_err(|e| e.to_string())?;

    let menu = Menu::with_items(app, &[&open_main, &chat_floater, &check_updates, &quit])
        .map_err(|e| e.to_string())?;

    let _tray = TrayIconBuilder::with_id("tupai-tray")
        .tooltip("tupAI")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .icon(app.default_window_icon().cloned().unwrap_or_else(|| {
            tauri::image::Image::new_owned(vec![0, 0, 0, 0], 1, 1)
        }))
        .on_menu_event(move |app, event| {
            let id = event.id.as_ref();
            match id {
                "open_main" => {
                    if let Some(win) = app.get_webview_window("main") {
                        let _ = win.show();
                        let _ = win.set_focus();
                    }
                }
                "chat_floater" => {
                    toggle_chat_floater(app);
                }
                "check_updates" => {
                    // 托盘菜单无法直接访问 localStorage 中的 device_token,
                    // 而 `manager.trigger_now()` 只更新状态不真实下载。
                    // 改为 emit 事件给前端, 前端拿到 token 后调用
                    // `silent_download_upgrade` 真实走 检查→下载→写marker 流程。
                    let _ = app.emit("tray:check-updates-requested", ());
                    if let Some(tray) = app.tray_by_id("tupai-tray") {
                        let _ = tray.set_tooltip(Some("tupAI · 正在检查更新…"));
                    }
                }
                "quit" => {
                    app.exit(0);
                }
                _ => {}
            }
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                let app = tray.app_handle();
                if let Some(win) = app.get_webview_window("main") {
                    let _ = win.show();
                    let _ = win.set_focus();
                }
            }
        })
        .build(app)
        .map_err(|e| e.to_string())?;

    Ok(())
}
