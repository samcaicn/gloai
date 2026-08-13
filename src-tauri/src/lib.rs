mod commands;
mod harness;
mod launch;
mod settings;

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use tauri::menu::{MenuBuilder, MenuItemBuilder};
use tauri::tray::TrayIconBuilder;
use tauri::{Emitter, Manager, WindowEvent};
use tauri_plugin_window_state::StateFlags;

use commands::AppState;

fn focus_main_window(app: &tauri::AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    }
}

fn window_state_flags() -> StateFlags {
    StateFlags::SIZE | StateFlags::POSITION | StateFlags::MAXIMIZED | StateFlags::FULLSCREEN
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let state = AppState::load().unwrap_or_else(|error| AppState::fallback(&error));

    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            focus_main_window(app);
        }))
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(
            tauri_plugin_window_state::Builder::default()
                .with_state_flags(window_state_flags())
                .with_filter(|label| label == "main")
                .build(),
        )
        .manage(state)
        .invoke_handler(tauri::generate_handler![
            commands::get_settings,
            commands::save_settings_patch,
            commands::get_api_key_present,
            commands::set_stored_api_key,
            commands::pick_workspace,
            commands::start_harness,
            commands::stop_harness,
            commands::harness_status,
            commands::doctor,
        ])
        .setup(|app| {
            let handle = app.handle().clone();
            let show_item = MenuItemBuilder::with_id("show", "显示窗口").build(app)?;
            let quit_item = MenuItemBuilder::with_id("quit", "退出").build(app)?;
            let menu = MenuBuilder::new(app)
                .items(&[&show_item, &quit_item])
                .build()?;
            let quitting = Arc::new(AtomicBool::new(false));
            let quitting_for_menu = quitting.clone();
            TrayIconBuilder::new()
                .icon(app.default_window_icon().cloned().ok_or_else(|| {
                    std::io::Error::new(std::io::ErrorKind::NotFound, "missing tray icon")
                })?)
                .tooltip("DeepSeek Harness")
                .menu(&menu)
                .show_menu_on_left_click(true)
                .on_menu_event(move |app, event| match event.id().as_ref() {
                    "show" => focus_main_window(app),
                    "quit" => {
                        quitting_for_menu.store(true, Ordering::SeqCst);
                        let app = app.clone();
                        tauri::async_runtime::spawn(async move {
                            if let Some(state) = app.try_state::<AppState>() {
                                let _ = state.harness.stop(&app).await;
                            }
                            app.exit(0);
                        });
                    }
                    _ => {}
                })
                .build(app)?;

            if let Some(window) = app.get_webview_window("main") {
                let app_handle = handle.clone();
                let quitting_for_window = quitting.clone();
                window.on_window_event(move |event| {
                    if let WindowEvent::CloseRequested { api, .. } = event {
                        if quitting_for_window.load(Ordering::SeqCst) {
                            return;
                        }
                        let app = app_handle.clone();
                        let close_to_tray = app
                            .try_state::<AppState>()
                            .map(|state| commands::close_to_tray(&state))
                            .unwrap_or(false);
                        if close_to_tray {
                            api.prevent_close();
                            if let Some(window) = app.get_webview_window("main") {
                                let _ = window.hide();
                            }
                            return;
                        }
                        quitting_for_window.store(true, Ordering::SeqCst);
                        api.prevent_close();
                        tauri::async_runtime::spawn(async move {
                            if let Some(state) = app.try_state::<AppState>() {
                                let _ = state.harness.stop(&app).await;
                            }
                            app.exit(0);
                        });
                    }
                });
            }

            let _ = handle.emit("host://ready", true);
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
