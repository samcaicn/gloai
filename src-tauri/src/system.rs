use std::env;
use std::process::Command;
use tauri::{
    image::Image,
    menu::{Menu, MenuItem},
    tray::{MouseButton, MouseButtonState, TrayIcon, TrayIconBuilder, TrayIconEvent},
    AppHandle, Emitter, Manager,
};

#[cfg(target_os = "windows")]
use winreg::{enums::HKEY_CURRENT_USER, RegKey};

#[cfg(target_os = "macos")]
use std::fs::File;
#[cfg(target_os = "macos")]
use std::io::Write;

#[cfg(target_os = "linux")]
use std::fs::File;
#[cfg(target_os = "linux")]
use std::io::Write;

pub struct SystemManager {
    app_handle: Option<AppHandle>,
    tray: Option<TrayIcon>,
}

impl SystemManager {
    pub fn new() -> Self {
        SystemManager {
            app_handle: None,
            tray: None,
        }
    }

    pub fn set_app_handle(&mut self, app_handle: AppHandle) {
        self.app_handle = Some(app_handle);
    }

    pub fn setup_system_tray(&mut self, app: &mut tauri::App) -> Result<(), String> {
        let app_handle = app.handle().clone();

        let show_item = MenuItem::with_id(app, "show", "打开窗口", true, None::<&str>)
            .map_err(|e| e.to_string())?;
        let new_task_item = MenuItem::with_id(app, "new_task", "新建任务", true, None::<&str>)
            .map_err(|e| e.to_string())?;
        let settings_item = MenuItem::with_id(app, "settings", "设置", true, None::<&str>)
            .map_err(|e| e.to_string())?;
        let quit_item = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)
            .map_err(|e| e.to_string())?;

        let menu = Menu::with_items(
            app,
            &[&show_item, &new_task_item, &settings_item, &quit_item],
        )
        .map_err(|e| e.to_string())?;

        let icon = Self::get_tray_icon(&app_handle)?;

        let tray = TrayIconBuilder::new()
            .icon(icon)
            .menu(&menu)
            .tooltip("Glo AI Assistant")
            .on_menu_event(move |app, event| match event.id.as_ref() {
                "show" => {
                    if let Some(window) = app.get_webview_window("main") {
                        let _ = window.show();
                        let _ = window.set_focus();
                    }
                }
                "new_task" => {
                    if let Some(window) = app.get_webview_window("main") {
                        let _ = window.show();
                        let _ = window.set_focus();
                        let _ = window.emit("app:newTask", ());
                    }
                }
                "settings" => {
                    if let Some(window) = app.get_webview_window("main") {
                        let _ = window.show();
                        let _ = window.set_focus();
                        let _ = window.emit("app:openSettings", ());
                    }
                }
                "quit" => {
                    app.exit(0);
                }
                _ => {}
            })
            .on_tray_icon_event(|tray, event| {
                if let TrayIconEvent::Click {
                    button: MouseButton::Left,
                    button_state: MouseButtonState::Up,
                    ..
                } = event
                {
                    let app = tray.app_handle();
                    if let Some(window) = app.get_webview_window("main") {
                        let _ = window.show();
                        let _ = window.set_focus();
                    }
                }
            })
            .build(app)
            .map_err(|e| e.to_string())?;

        self.tray = Some(tray);
        println!("[System] System tray setup completed");

        Ok(())
    }

    fn get_tray_icon(_app_handle: &AppHandle) -> Result<Image<'static>, String> {
        let icon_bytes = include_bytes!("../icons/32x32.png");
        let img = image::load_from_memory(icon_bytes)
            .map_err(|e| format!("Failed to load icon: {}", e))?;
        let rgba = img.to_rgba8();
        let (width, height) = rgba.dimensions();
        Ok(Image::new_owned(rgba.into_raw(), width, height))
    }

    pub fn enable_auto_start(&self, enable: bool) -> Result<(), String> {
        #[cfg(target_os = "windows")]
        {
            let hkcu = RegKey::predef(HKEY_CURRENT_USER);
            let (key, _) = hkcu
                .create_subkey("Software\\Microsoft\\Windows\\CurrentVersion\\Run")
                .map_err(|e| e.to_string())?;

            if enable {
                let exe_path = env::current_exe().map_err(|e| e.to_string())?;
                key.set_value("Glo", &exe_path.to_str().unwrap_or("Glo"))
                    .map_err(|e| e.to_string())?;
            } else {
                key.delete_value("Glo").unwrap_or(());
            }
            Ok(())
        }

        #[cfg(target_os = "macos")]
        {
            let launch_agent_dir = dirs::home_dir()
                .ok_or("Failed to get home directory".to_string())?
                .join("Library")
                .join("LaunchAgents");

            let plist_path = launch_agent_dir.join("com.glo.app.plist");

            if enable {
                std::fs::create_dir_all(&launch_agent_dir).map_err(|e| e.to_string())?;

                let exe_path = env::current_exe().map_err(|e| e.to_string())?;
                let plist_content = format!(
                    r#"<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.glo.app</string>
    <key>Program</key>
    <string>{}</string>
    <key>RunAtLoad</key>
    <true/>
</dict>
</plist>"#,
                    exe_path.to_str().unwrap_or("")
                );

                let mut file = File::create(&plist_path).map_err(|e| e.to_string())?;
                file.write_all(plist_content.as_bytes())
                    .map_err(|e| e.to_string())?;

                Command::new("launchctl")
                    .arg("load")
                    .arg(plist_path)
                    .output()
                    .map_err(|e| e.to_string())?;
            } else {
                if plist_path.exists() {
                    Command::new("launchctl")
                        .arg("unload")
                        .arg(&plist_path)
                        .output()
                        .unwrap_or_else(|_| std::process::Output {
                            status: std::process::ExitStatus::default(),
                            stdout: Vec::new(),
                            stderr: Vec::new(),
                        });

                    std::fs::remove_file(plist_path).map_err(|e| e.to_string())?;
                }
            }
            Ok(())
        }

        #[cfg(target_os = "linux")]
        {
            let autostart_dir = dirs::home_dir()
                .ok_or("Failed to get home directory".to_string())?
                .join(".config")
                .join("autostart");

            let desktop_path = autostart_dir.join("glo.desktop");

            if enable {
                std::fs::create_dir_all(&autostart_dir).map_err(|e| e.to_string())?;

                let exe_path = env::current_exe().map_err(|e| e.to_string())?;
                let desktop_content = format!("[Desktop Entry]\nName=Glo\nExec={}\nType=Application\nX-GNOME-Autostart-enabled=true\n", exe_path.to_str().unwrap_or(""));

                let mut file = File::create(&desktop_path).map_err(|e| e.to_string())?;
                file.write_all(desktop_content.as_bytes())
                    .map_err(|e| e.to_string())?;
            } else {
                if desktop_path.exists() {
                    std::fs::remove_file(desktop_path).map_err(|e| e.to_string())?;
                }
            }
            Ok(())
        }
    }

    pub fn is_auto_start_enabled(&self) -> Result<bool, String> {
        #[cfg(target_os = "windows")]
        {
            let hkcu = RegKey::predef(HKEY_CURRENT_USER);
            let key = hkcu
                .open_subkey("Software\\Microsoft\\Windows\\CurrentVersion\\Run")
                .map_err(|e| e.to_string())?;
            let value: Result<String, _> = key.get_value("Glo");
            Ok(value.is_ok())
        }

        #[cfg(target_os = "macos")]
        {
            let plist_path = dirs::home_dir()
                .ok_or("Failed to get home directory".to_string())?
                .join("Library")
                .join("LaunchAgents")
                .join("com.glo.app.plist");
            Ok(plist_path.exists())
        }

        #[cfg(target_os = "linux")]
        {
            let desktop_path = dirs::home_dir()
                .ok_or("Failed to get home directory".to_string())?
                .join(".config")
                .join("autostart")
                .join("glo.desktop");
            Ok(desktop_path.exists())
        }
    }

    pub fn open_at_login(&self, enable: bool) -> Result<(), String> {
        self.enable_auto_start(enable)
    }

    pub fn get_app_version(&self) -> String {
        env!("CARGO_PKG_VERSION").to_string()
    }

    pub fn get_app_name(&self) -> String {
        env!("CARGO_PKG_NAME").to_string()
    }
}

impl Default for SystemManager {
    fn default() -> Self {
        Self::new()
    }
}
