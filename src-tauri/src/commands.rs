//! Tauri command surface consumed only by the frontend adapter.

use std::process::Stdio;

use serde::{Deserialize, Serialize};
use tauri::{AppHandle, State};
use tauri_plugin_dialog::DialogExt;
use tokio::process::Command;
use crate::harness::{HarnessManager, HarnessStatus};
use crate::launch::{augmented_path, find_in_path, resolve_launch};
use crate::settings::{
    get_api_key, load_settings, save_settings, set_api_key, touch_recent_workspace, AppSettings,
};

pub struct AppState {
    pub settings: std::sync::Mutex<AppSettings>,
    pub harness: HarnessManager,
}

impl AppState {
    pub fn load() -> Result<Self, String> {
        Ok(Self {
            settings: std::sync::Mutex::new(load_settings()?),
            harness: HarnessManager::new(),
        })
    }

    pub fn fallback(error: &str) -> Self {
        eprintln!("load settings: {error}");
        Self {
            settings: std::sync::Mutex::new(AppSettings::default()),
            harness: HarnessManager::new(),
        }
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DoctorReport {
    pub node: Probe,
    pub dsh: Probe,
    pub npx: Probe,
    pub has_api_key: bool,
    pub keyring: bool,
    pub launch_program: String,
    pub launch_args: Vec<String>,
    pub launch_source: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Probe {
    pub found: bool,
    pub path: Option<String>,
    pub version: Option<String>,
    pub error: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SettingsPatch {
    pub harness_command: Option<String>,
    pub harness_args: Option<Vec<String>>,
    pub theme: Option<String>,
    pub locale: Option<String>,
    pub close_to_tray: Option<bool>,
    pub recent_workspaces: Option<Vec<crate::settings::RecentWorkspace>>,
}

fn lock_settings(state: &AppState) -> Result<std::sync::MutexGuard<'_, AppSettings>, String> {
    state
        .settings
        .lock()
        .map_err(|_| "设置锁已损坏".to_string())
}

#[tauri::command]
pub async fn get_settings(state: State<'_, AppState>) -> Result<AppSettings, String> {
    let mut snapshot = lock_settings(&state)?.clone();
    snapshot.api_key_fallback = None;
    Ok(snapshot)
}

#[tauri::command]
pub async fn save_settings_patch(
    state: State<'_, AppState>,
    patch: SettingsPatch,
) -> Result<AppSettings, String> {
    let mut settings = lock_settings(&state)?;
    if let Some(value) = patch.harness_command {
        settings.harness_command = value;
    }
    if let Some(value) = patch.harness_args {
        settings.harness_args = value;
    }
    if let Some(value) = patch.theme {
        settings.theme = value;
    }
    if let Some(value) = patch.locale {
        settings.locale = value;
    }
    if let Some(value) = patch.close_to_tray {
        settings.close_to_tray = value;
    }
    if let Some(value) = patch.recent_workspaces {
        settings.recent_workspaces = value;
    }
    save_settings(&settings)?;
    let mut snapshot = settings.clone();
    snapshot.api_key_fallback = None;
    Ok(snapshot)
}

#[tauri::command]
pub async fn get_api_key_present(state: State<'_, AppState>) -> Result<bool, String> {
    let settings = lock_settings(&state)?;
    Ok(get_api_key(&settings)?.is_some())
}

#[tauri::command]
pub async fn set_stored_api_key(
    state: State<'_, AppState>,
    key: Option<String>,
) -> Result<bool, String> {
    let mut settings = lock_settings(&state)?;
    set_api_key(&mut settings, key)
}

#[tauri::command]
pub async fn pick_workspace(app: AppHandle) -> Result<Option<String>, String> {
    let (tx, rx) = tokio::sync::oneshot::channel();
    app.dialog()
        .file()
        .set_title("选择工作区")
        .pick_folder(move |folder| {
            let path = folder.map(|file| file.to_string());
            let _ = tx.send(path);
        });
    Ok(rx.await.unwrap_or(None))
}

#[tauri::command]
pub async fn start_harness(
    app: AppHandle,
    state: State<'_, AppState>,
    workspace: String,
) -> Result<HarnessStatus, String> {
    let snapshot = {
        let mut settings = lock_settings(&state)?;
        touch_recent_workspace(&mut settings, &workspace);
        save_settings(&settings)?;
        settings.clone()
    };
    state.harness.start(&app, &snapshot, workspace).await
}

#[tauri::command]
pub async fn stop_harness(app: AppHandle, state: State<'_, AppState>) -> Result<(), String> {
    state.harness.stop(&app).await
}

#[tauri::command]
pub async fn harness_status(state: State<'_, AppState>) -> Result<HarnessStatus, String> {
    Ok(state.harness.snapshot().await)
}

#[tauri::command]
pub async fn doctor(state: State<'_, AppState>) -> Result<DoctorReport, String> {
    let settings = lock_settings(&state)?.clone();
    let path_var = augmented_path();
    let spec = resolve_launch(&settings, &path_var);
    let keyring_ok = keyring::Entry::new(
        crate::launch::KEYRING_SERVICE,
        crate::launch::KEYRING_USER,
    )
    .is_ok();
    Ok(DoctorReport {
        node: probe_binary("node", &path_var).await,
        dsh: probe_binary("dsh", &path_var).await,
        npx: probe_binary("npx", &path_var).await,
        has_api_key: get_api_key(&settings)?.is_some(),
        keyring: keyring_ok,
        launch_program: spec.program,
        launch_args: spec.args,
        launch_source: format!("{:?}", spec.source),
    })
}

async fn probe_binary(name: &str, path_var: &str) -> Probe {
    match find_in_path(name, path_var) {
        None => Probe {
            found: false,
            path: None,
            version: None,
            error: Some(format!("PATH 上找不到 {name}")),
        },
        Some(path) => {
            let display = path.display().to_string();
            match Command::new(&path)
                .arg("--version")
                .env("PATH", path_var)
                .stdout(Stdio::piped())
                .stderr(Stdio::piped())
                .output()
                .await
            {
                Ok(output) => {
                    let stdout = String::from_utf8_lossy(&output.stdout).trim().to_string();
                    let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
                    let version = if stdout.is_empty() { stderr } else { stdout };
                    Probe {
                        found: output.status.success() || !version.is_empty(),
                        path: Some(display),
                        version: if version.is_empty() {
                            None
                        } else {
                            Some(version.lines().next().unwrap_or(&version).to_string())
                        },
                        error: if output.status.success() {
                            None
                        } else {
                            Some(format!("{name} --version 退出码 {:?}", output.status.code()))
                        },
                    }
                }
                Err(error) => Probe {
                    found: true,
                    path: Some(display),
                    version: None,
                    error: Some(error.to_string()),
                },
            }
        }
    }
}

pub fn close_to_tray(state: &AppState) -> bool {
    state
        .settings
        .lock()
        .map(|settings| settings.close_to_tray)
        .unwrap_or(false)
}
