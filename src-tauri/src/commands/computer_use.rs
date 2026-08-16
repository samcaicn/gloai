// Copyright (c) 2026 tupAI
//
// Computer Use（桌面自动化）状态与权限命令。
//
// 前端 SessionConfig.tsx 调用这两个命令（此前后端缺失，导致设置页静默失败）：
//   - computer_use_get_status       → 开关 + 无障碍/录屏权限 + Cua Driver 健康
//   - computer_use_open_system_settings → 打开系统设置对应权限页（引导用户授权）
//
// 权限检测策略（低开销、尽力而为）：
//   - macOS 无障碍：osascript 查 UI elements enabled（复用 system.rs 逻辑）。
//   - Windows / Linux：无障碍/录屏一般无系统级硬门禁，直接报告可用，
//     通过 Cua Driver 实际调用是否成功来判断（connected 反映可用性）。
//   - 录屏权限在 macOS 上难以低开销探测，返回 platformNote 提示用户。

use tauri::AppHandle;
use serde::Serialize;

/// `computer_use_get_status` 返回体，与前端 ComputerUseStatusPayload 对齐。
#[derive(Serialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ComputerUseStatusPayload {
    /// Computer Use 功能是否开启（来自配置 ai.computer_use_enabled）。
    pub computer_use_enabled: bool,
    /// 无障碍权限是否已授予（macOS 真实检测；其他平台默认 true）。
    pub accessibility_granted: bool,
    /// 录屏权限是否已授予（尽力而为；macOS 无法低成本探测时提示）。
    pub screen_capture_granted: bool,
    /// 平台提示（如 macOS 需手动授予录屏权限）。
    pub platform_note: Option<String>,
    /// Cua Driver sidecar 健康详情（二进制可用性 / 连接 / 版本 / 工具数）。
    pub cua_driver: CuaDriverStatusView,
}

/// Cua Driver sidecar 健康详情（前端可据此渲染状态点）。
#[derive(Serialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct CuaDriverStatusView {
    pub available: bool,
    pub connected: bool,
    pub binary_path: Option<String>,
    pub version: Option<String>,
    pub tools_count: Option<usize>,
    pub last_error: Option<String>,
}

impl From<crate::pc_automation::cua_driver::CuaDriverHealth> for CuaDriverStatusView {
    fn from(h: crate::pc_automation::cua_driver::CuaDriverHealth) -> Self {
        CuaDriverStatusView {
            available: h.available,
            connected: h.connected,
            binary_path: h.binary_path,
            version: h.version,
            tools_count: h.tools_count,
            last_error: h.last_error,
        }
    }
}

/// 读取配置中的 computer_use_enabled 开关。
fn read_computer_use_enabled(app: &AppHandle) -> bool {
    let cfg = crate::commands::legacy::load_config_from_disk(app);
    cfg.computer_use_enabled
}

/// 查询 Computer Use 状态：开关 + 系统权限 + Cua Driver 健康。
///
/// 幂等、低开销：不启动 sidecar，仅读取健康快照（含进程是否已在运行）。
#[tauri::command]
pub async fn computer_use_get_status(
    app: AppHandle,
) -> Result<ComputerUseStatusPayload, String> {
    let enabled = read_computer_use_enabled(&app);

    // 无障碍权限：macOS 真实检测；其他平台无硬门禁 → true。
    let accessibility_granted = crate::commands::system::check_macos_accessibility_impl();

    // 录屏权限：macOS 难以低成本探测，返回平台提示；其他平台视为可用。
    let (screen_capture_granted, platform_note) = match std::env::consts::OS {
        "macos" => (
            false,
            Some("macOS 请前往 系统设置 → 隐私与安全性 → 屏幕录制，勾选 tupai 以启用桌面自动化".to_string()),
        ),
        _ => (true, None),
    };

    // Cua Driver 健康（只读，不触发启动）。
    let cua = crate::pc_automation::cua_driver::CuaDriverClient::shared();
    let cua_driver = CuaDriverStatusView::from(cua.health().await);

    Ok(ComputerUseStatusPayload {
        computer_use_enabled: enabled,
        accessibility_granted,
        screen_capture_granted,
        platform_note,
        cua_driver,
    })
}

/// 打开系统设置中的对应权限页，引导用户授予无障碍 / 录屏权限。
///
/// `pane`: "accessibility" | "screen_capture"。
/// 非目标平台直接返回 Ok（无操作），避免前端报错。
#[tauri::command]
pub fn computer_use_open_system_settings(pane: String) -> Result<(), String> {
    match pane.as_str() {
        "accessibility" => {
            #[cfg(target_os = "macos")]
            {
                std::process::Command::new("open")
                    .args([
                        "-b",
                        "com.apple.systempreferences",
                        "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility",
                    ])
                    .spawn()
                    .map_err(|e| format!("failed to open System Settings: {}", e))?;
            }
            #[cfg(target_os = "windows")]
            {
                std::process::Command::new("cmd")
                    .args(["/C", "start", "ms-settings:easeofaccess"])
                    .spawn()
                    .map_err(|e| format!("failed to open Settings: {}", e))?;
            }
            #[cfg(target_os = "linux")]
            {
                std::process::Command::new("gnome-control-center")
                    .arg("universal-access")
                    .spawn()
                    .map_err(|e| format!("failed to open accessibility settings: {}", e))?;
            }
            Ok(())
        }
        "screen_capture" => {
            #[cfg(target_os = "macos")]
            {
                std::process::Command::new("open")
                    .args([
                        "-b",
                        "com.apple.systempreferences",
                        "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture",
                    ])
                    .spawn()
                    .map_err(|e| format!("failed to open System Settings: {}", e))?;
            }
            #[cfg(target_os = "windows")]
            {
                std::process::Command::new("cmd")
                    .args(["/C", "start", "ms-settings:privacy-appaccesscamera"])
                    .spawn()
                    .map_err(|e| format!("failed to open Settings: {}", e))?;
            }
            #[cfg(target_os = "linux")]
            {
                std::process::Command::new("gnome-control-center")
                    .arg("privacy")
                    .spawn()
                    .map_err(|e| format!("failed to open privacy settings: {}", e))?;
            }
            Ok(())
        }
        _ => Err(format!("unknown computer use settings pane: {}", pane)),
    }
}
