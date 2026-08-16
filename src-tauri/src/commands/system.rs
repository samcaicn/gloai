// Copyright (c) 2026 MeeJoy
//
// Tauri commands for the silent-upgrade + monitoring rollout.
// These are the *only* surface the front-end talks to. The
// internal state machines live in `crate::upgrade` and
// `crate::monitoring` so we keep this file thin and easy to
// audit.

use std::sync::Arc;

use serde::Serialize;
use tauri::{AppHandle, State};

use crate::hardware::detector::HardwareDetector;
use crate::monitoring::observer::ActivityEntry;
use crate::monitoring::{
    is_monitoring_enabled as is_monitoring_enabled_flag,
    recent_activity,
    set_monitoring_enabled as set_monitoring_enabled_flag,
};
use crate::upgrade::{build_silent_upgrade_plan, UpgradeManager, UpgradeStatus};

/// Manager state registered in `lib.rs::setup`. The `Arc` wrapper
/// keeps the manager cheaply cloneable across Tauri command
/// boundaries.
pub type UpgradeManagerState = Arc<UpgradeManager>;

#[tauri::command]
pub fn check_silent_upgrade(
    manager: State<'_, UpgradeManagerState>,
) -> Result<UpgradeStatus, String> {
    Ok(manager.status())
}

#[tauri::command]
pub fn trigger_silent_upgrade_now(
    app: AppHandle,
    manager: State<'_, UpgradeManagerState>,
) -> Result<(), String> {
    manager.trigger_now(&app);
    Ok(())
}

#[tauri::command]
pub fn set_auto_upgrade_enabled(
    enabled: bool,
    manager: State<'_, UpgradeManagerState>,
) -> Result<(), String> {
    manager.set_auto_upgrade_enabled(enabled);
    Ok(())
}

#[tauri::command]
pub fn install_pending_upgrade_now(
    manager: State<'_, UpgradeManagerState>,
) -> Result<(), String> {
    manager.install_pending_upgrade()
}

#[tauri::command]
pub fn set_monitoring_enabled(enabled: bool) -> Result<(), String> {
    set_monitoring_enabled_flag(enabled);
    Ok(())
}

#[tauri::command]
pub fn get_recent_activity_log(limit: u32) -> Result<Vec<ActivityEntry>, String> {
    if limit == 0 {
        return Ok(Vec::new());
    }
    let capped = limit.min(1000);
    Ok(recent_activity(capped))
}

/// Returns whether the monitoring switch is currently on. Used by
/// the front-end to reflect the live state (the `set_*` command
/// is fire-and-forget). Not part of the documented surface but
/// useful to keep the API symmetric.
#[tauri::command]
pub fn get_monitoring_enabled() -> Result<bool, String> {
    Ok(is_monitoring_enabled_flag())
}

/// Returns a `SilentUpgradePlan` describing whether
/// the agent qualifies for a background silent download right now,
/// and (when not) *why* the loop is blocked. The frontend renders
/// the reason / `diskFreeGb` / `idle` fields directly so the user
/// can see what would need to change.
///
/// The current "version tier" is taken from `manager.hardware_version`
/// (the value the manager was constructed with; today the default is
/// `"cpu_only"` and the hardware layer can refine it later). The
/// *target* tier is derived from the latest `HardwareDetector::detect`
/// result.
#[tauri::command]
pub fn get_silent_upgrade_plan(
    manager: State<'_, UpgradeManagerState>,
) -> Result<crate::upgrade::SilentUpgradePlan, String> {
    let current = manager.hardware_version().to_string();
    let info = HardwareDetector::detect();
    let plan = build_silent_upgrade_plan(&current, &info.matched_version, manager.is_auto_upgrade_enabled());
    Ok(plan)
}

// ============================================================================
// 新增: 自建升级流水线命令 (绕过 Tauri updater API)
// ============================================================================

/// 匹配前端 `CheckForUpdatesResponse` 接口 (camelCase)。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CheckForUpdatesResponse {
    pub update_available: bool,
    pub current_version: String,
    pub latest_version: Option<String>,
    pub release_notes: Option<String>,
    pub release_date: Option<String>,
}

/// 检查服务器是否有新版本。device_token 从前端 localStorage 传入。
#[tauri::command]
pub async fn check_for_updates(
    app: AppHandle,
    device_token: String,
) -> Result<CheckForUpdatesResponse, String> {
    let resp = crate::upgrade::updater_client::check_via_server(&app, &device_token).await?;

    let current_version = crate::upgrade::updater_client::current_version().to_string();

    match resp {
        Some(server_resp) => Ok(CheckForUpdatesResponse {
            update_available: true,
            current_version,
            latest_version: server_resp.latest_version,
            release_notes: server_resp.release_notes,
            release_date: None,
        }),
        None => Ok(CheckForUpdatesResponse {
            update_available: false,
            current_version,
            latest_version: None,
            release_notes: None,
            release_date: None,
        }),
    }
}

/// 下载并安装更新 (用户手动触发): 检查 → 下载 → 静默安装 → 重启。
#[tauri::command]
pub async fn install_update(
    app: AppHandle,
    device_token: String,
) -> Result<(), String> {
    let resp = crate::upgrade::updater_client::check_via_server(&app, &device_token)
        .await?
        .ok_or_else(|| "no update available".to_string())?;

    let download_url = resp.download_url.as_deref()
        .filter(|u| !u.is_empty())
        .ok_or_else(|| "server returned no download_url".to_string())?;

    let filename = resp.filename.as_deref().unwrap_or("setup.exe");
    let sha256 = resp.sha256.as_deref();

    let dir = crate::upgrade::manager::upgrade_dir_public();
    let dest = dir.join(filename);

    crate::upgrade::updater_client::download_to_local(download_url, sha256, &dest, &app).await?;

    crate::upgrade::updater_client::install_silently(&dest)?;

    // 给安装程序一点时间初始化,然后退出当前进程。
    // 不使用 app.restart() 因为 NSIS customPreInstall 会 taskkill 所有
    // tupai.exe 进程(含 restart spawn 的新进程),导致升级后应用无人拉起。
    // app.exit(0) 让当前进程干净退出,NSIS customPostInstall 钩子
    // 会在安装完成后自动拉起新版本应用。
    tokio::time::sleep(std::time::Duration::from_secs(2)).await;
    app.exit(0);
    Ok(())
}

/// 重启应用 (安装完成后调用)。
#[tauri::command]
pub fn restart_app(app: AppHandle) -> Result<(), String> {
    app.restart()
}

/// 仅允许 http(s)/mailto 协议的外链，拒绝 javascript:/data:/file: 等可执行/本地协议。
/// 作为 [`open_external`] 的单点防御：即使前端漏校验，后端也不放行危险协议。
fn is_safe_external_url(url: &str) -> bool {
    let lower = url.trim().to_ascii_lowercase();
    lower.starts_with("http://")
        || lower.starts_with("https://")
        || lower.starts_with("mailto:")
}

/// 在系统默认浏览器打开 URL。
/// 使用 Tauri 的 opener plugin，避免 WebView 内部打开。
#[tauri::command]
pub async fn open_external(_app: AppHandle, url: String) -> Result<(), String> {
    let trimmed = url.trim();
    if trimmed.is_empty() {
        return Err("empty url".to_string());
    }
    // 协议白名单：单点防御，拒绝 javascript:/data:/file: 等可执行/本地协议。
    if !is_safe_external_url(trimmed) {
        return Err(format!("blocked unsafe url scheme: {}", trimmed));
    }
    // Tauri v2: use opener plugin (tauri-plugin-opener)
    tauri_plugin_opener::open_url(trimmed, None::<&str>)
        .map_err(|e| format!("failed to open url: {}", e))
}

/// 静默下载升级 (启动后 60s 由前端触发): 后台检查 + 下载 + 写 pending marker。
/// 下载完成后下次启动时由 `install_pending_on_startup` 静默安装。
#[tauri::command]
pub async fn silent_download_upgrade(
    app: AppHandle,
    device_token: String,
) -> Result<(), String> {
    let app_clone = app.clone();
    let token = device_token;
    tauri::async_runtime::spawn(async move {
        crate::upgrade::manager::start_background_loop(app_clone, token).await;
    });
    Ok(())
}

// ============================================================================
// OS-compatibility helpers (P1-2 / P1-6)
//
// These commands let the frontend detect platform-specific permission /
// capability gaps that would otherwise cause silent UX degradation:
//   * macOS accessibility permission — required by rdev (global key/mouse
//     hooks), tauri-plugin-global-shortcut, and enigo (input simulation).
//     On macOS 14+ Sequoia the permission prompt is stricter; without it
//     hotkeys silently fail.
//   * Windows OCR language pack — pc_automation/ocr/windows.rs uses
//     Windows.Media.Ocr which needs at least one recognizer language
//     installed. The previous NSIS customInstall macro auto-installed
//     OCR packs at install time but required admin (incompatible with
//     perUser install). We now check at first-run and prompt the user.
// ============================================================================

/// Result of `check_os_compatibility` — a single IPC payload covering both
/// macOS accessibility + Windows OCR so the frontend can render one banner
/// per missing capability without two round-trips.
#[derive(Debug, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OsCompatibilityReport {
    /// true if macOS Accessibility permission is granted (non-macOS = true).
    pub macos_accessibility_granted: bool,
    /// true if at least one Windows OCR recognizer language is installed
    /// (non-Windows = true).
    pub windows_ocr_available: bool,
    /// List of available Windows OCR language tags (BCP-47, e.g. "en-US").
    /// Empty on non-Windows or when no OCR pack is installed.
    pub windows_ocr_languages: Vec<String>,
    /// Detected OS display string (e.g. "Windows 11 Pro 23H2 (build 22631)").
    pub os_version: String,
}

/// One-shot compatibility check. Cheap (no IO except registry / osascript);
/// the frontend calls it on first launch and after the user returns from
/// System Settings (e.g. after granting accessibility permission).
#[tauri::command]
pub fn check_os_compatibility(app: AppHandle) -> Result<OsCompatibilityReport, String> {
    Ok(OsCompatibilityReport {
        macos_accessibility_granted: check_macos_accessibility_impl(),
        windows_ocr_available: check_windows_ocr_impl().is_some(),
        windows_ocr_languages: check_windows_ocr_impl().unwrap_or_default(),
        os_version: detect_os_version_string(&app),
    })
}

/// Open the platform's permission/settings UI so the user can grant
/// accessibility (macOS) or install OCR language packs (Windows).
/// No-op on Linux.
#[tauri::command]
pub fn open_os_permission_panel(target: String) -> Result<(), String> {
    match target.as_str() {
        "macos-accessibility" => {
            #[cfg(target_os = "macos")]
            {
                std::process::Command::new("open")
                    .args(["-b", "com.apple.systempreferences",
                           "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility"])
                    .spawn()
                    .map_err(|e| format!("failed to open System Settings: {}", e))?;
            }
            #[cfg(not(target_os = "macos"))]
            {
                let _ = target;
            }
            Ok(())
        }
        "windows-ocr" => {
            #[cfg(target_os = "windows")]
            {
                // Open Settings → Time & Language → Language → Administrative language options.
                // There's no direct deep-link to "OCR language packs" but this lands the user
                // on the language page where they can add a language with OCR support.
                std::process::Command::new("cmd")
                    .args(["/C", "start", "ms-settings:regionlanguage"])
                    .spawn()
                    .map_err(|e| format!("failed to open Settings: {}", e))?;
            }
            #[cfg(not(target_os = "windows"))]
            {
                let _ = target;
            }
            Ok(())
        }
        _ => Err(format!("unknown permission target: {}", target)),
    }
}

// ── platform impls ──────────────────────────────────────────────────────

#[cfg(target_os = "macos")]
pub(crate) fn check_macos_accessibility_impl() -> bool {
    // Use AppleScript via osascript to ask System Events whether UI elements
    // are enabled — this requires the calling process to hold Accessibility
    // permission. Returns "true" / "false" on stdout.
    //
    // We avoid pulling in a Cocoa/objc binding dep just for one boolean;
    // the osascript round-trip is ~20ms and only runs on user-triggered
    // checks (first-launch + return-from-settings).
    let output = std::process::Command::new("/usr/bin/osascript")
        .args(["-e", "tell application \"System Events\" to UI elements enabled"])
        .output();
    match output {
        Ok(out) if out.status.success() => {
            let raw = String::from_utf8_lossy(&out.stdout).trim().to_ascii_lowercase();
            raw == "true"
        }
        _ => false,
    }
}

#[cfg(not(target_os = "macos"))]
pub(crate) fn check_macos_accessibility_impl() -> bool {
    true
}

#[cfg(target_os = "windows")]
fn check_windows_ocr_impl() -> Option<Vec<String>> {
    // Read the registry key that lists installed OCR language capabilities.
    // Path: HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Capabilities\Language\OCR
    // Each value name is a BCP-47 language tag (e.g. "en-US", "zh-CN").
    //
    // This mirrors what Windows.Media.Ocr.OcrEngine::AvailableRecognizerLanguages
    // returns, but without the WinRT binding overhead (which would require
    // initializing the WinRT runtime just for a registry read).
    use winreg::enums::{HKEY_LOCAL_MACHINE, KEY_READ};
    use winreg::RegKey;
    let hklm = RegKey::predef(HKEY_LOCAL_MACHINE);
    let key = hklm
        .open_subkey_with_flags(
            r"SOFTWARE\Microsoft\Windows\CurrentVersion\Capabilities\Language\OCR",
            KEY_READ,
        )
        .ok()?;
    let mut langs: Vec<String> = key
        .enum_values()
        .filter_map(|r| r.ok())
        .map(|(name, _)| name)
        .collect();
    langs.sort();
    if langs.is_empty() {
        None
    } else {
        Some(langs)
    }
}

#[cfg(not(target_os = "windows"))]
fn check_windows_ocr_impl() -> Option<Vec<String>> {
    None
}

/// Best-effort OS version string for the compatibility report.
/// Reuses hardware_id's detect_os_version when available.
fn detect_os_version_string(_app: &AppHandle) -> String {
    // hardware_id::detect_os_version is module-private; rather than expose
    // it, we shell out to the same sources here. Cheap (~1ms) and only
    // called on user-triggered checks.
    #[cfg(target_os = "macos")]
    {
        let raw = std::process::Command::new("/usr/bin/sw_vers")
            .arg("-productVersion")
            .output()
            .ok()
            .and_then(|out| String::from_utf8(out.stdout).ok());
        return super::hardware_id::format_macos_version_string(raw.as_deref().unwrap_or(""));
    }
    #[cfg(target_os = "windows")]
    {
        use winreg::enums::{HKEY_LOCAL_MACHINE, KEY_READ};
        use winreg::RegKey;
        let hklm = RegKey::predef(HKEY_LOCAL_MACHINE);
        if let Ok(key) = hklm.open_subkey_with_flags(
            r"SOFTWARE\Microsoft\Windows NT\CurrentVersion",
            KEY_READ,
        ) {
            let product: String = key.get_value("ProductName").unwrap_or_default();
            let disp: String = key.get_value("DisplayVersion").unwrap_or_default();
            let release: String = key.get_value("ReleaseId").unwrap_or_default();
            let build: String = key.get_value("CurrentBuild").unwrap_or_default();
            if let Some(formatted) =
                super::hardware_id::format_windows_version(&product, &disp, &release, &build)
            {
                return formatted;
            }
        }
        "Windows (unknown version)".to_string()
    }
    #[cfg(target_os = "linux")]
    {
        if let Ok(content) = std::fs::read_to_string("/etc/os-release") {
            for line in content.lines() {
                if let Some(rest) = line.strip_prefix("PRETTY_NAME=") {
                    return rest.trim_matches('"').to_string();
                }
            }
        }
        return "Linux (unknown distro)".to_string();
    }
    #[cfg(not(any(target_os = "macos", target_os = "windows", target_os = "linux")))]
    {
        return std::env::consts::OS.to_string();
    }
}
