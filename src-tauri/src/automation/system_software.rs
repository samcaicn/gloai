// Copyright (c) 2026 AIMarketing
//
// AIMarketing P1 §3.3 — Cross-platform installed-software detection and launch.
//
// Detection strategy per platform (matches plan §3.3):
// - Windows: enumerate HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall
//   and match the `DisplayName` field via `winreg`.
// - macOS:   probe `/Applications/{name}.app` (case-insensitive).
// - Linux:   run `which {name}` and check the exit code.
//
// Launch strategy per platform:
// - Windows: `cmd /c start "" {name}` (the empty quoted segment stops `start`
//   from treating a quoted name as a window title).
// - macOS:   `open -a {name}`.
// - Linux:   spawn `{name}` detached.
//
// All public entry points are best-effort: they return `bool` for detection
// and `Result<(), String>` for launch so the caller (the dispatcher) can
// decide what to do on a miss (e.g. emit a `software_install_prompt` event).

use serde::{Deserialize, Serialize};
#[cfg(target_os = "windows")]
use winreg::enums::*;
#[cfg(target_os = "windows")]
use winreg::RegKey;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SoftwareInfo {
    pub name: String,
    pub installed: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub source: Option<String>,
}

/// 全量扫描本地已安装软件（用于建立本地索引）
/// 返回包含 exe 路径 + 安装位置的清单，前端据此做 UIA/CDP 能力判定
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalSoftwareEntry {
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub exe_path: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub install_location: Option<String>,
}

/// Returns whether `software_name` is installed on the host.
///
/// The match is intentionally `contains` rather than `==` so that common
/// display variants (e.g. "Microsoft Edge" vs "Edge") still hit.
pub fn check_software_installed(software_name: &str) -> bool {
    if software_name.trim().is_empty() {
        return false;
    }
    let needle = software_name.to_lowercase();

    #[cfg(target_os = "windows")]
    {
        windows_check(&needle)
    }
    #[cfg(target_os = "macos")]
    {
        macos_check(&needle)
    }
    #[cfg(target_os = "linux")]
    {
        linux_check(software_name)
    }
    #[cfg(not(any(target_os = "windows", target_os = "macos", target_os = "linux")))]
    {
        let _ = needle;
        false
    }
}

/// Enumerate every supported software the user might own.
///
/// Used by the Settings UI to render the "installed software" table. The
/// list is deliberately small (a curated whitelist) because the Windows
/// registry is huge and the macOS /Applications scan would be expensive.
pub fn list_installed_software() -> Vec<SoftwareInfo> {
    const WHITELIST: &[&str] = &[
        "chrome",
        "google chrome",
        "microsoft edge",
        "edge",
        "firefox",
        "brave",
        "notepad",
        "notepad++",
        "wechat",
        "dingtalk",
        "feishu",
        "lark",
        "vscode",
        "visual studio code",
        "terminal",
        "iterm",
    ];

    WHITELIST
        .iter()
        .map(|name| SoftwareInfo {
            name: (*name).to_string(),
            installed: check_software_installed(name),
            source: Some(platform_name().to_string()),
        })
        .collect()
}

/// Launch `software_name` using the platform's standard shell verb.
///
/// Returns an error if the OS reports failure; the caller should treat
/// "name not installed" as a recoverable case (emit install prompt).
pub fn launch_software(software_name: &str) -> Result<(), String> {
    if software_name.trim().is_empty() {
        return Err("软件名称不能为空".to_string());
    }
    let name = software_name.trim();

    #[cfg(target_os = "windows")]
    {
        let mut command = std::process::Command::new(name);
        command
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null());
        // `cmd /c start` is the canonical "open with default handler"
        // call on Windows. Without CREATE_NO_WINDOW it pops a
        // black console over the WebView every time the user clicks
        // "打开" on a software entry in the Automation panel.
        crate::commands::legacy::apply_no_window(&mut command);
        let status = command
            .status()
            .map_err(|e| format!("启动失败: {}", e))?;
        if status.success() {
            Ok(())
        } else {
            Err(format!("启动退出码: {:?}", status.code()))
        }
    }
    #[cfg(target_os = "macos")]
    {
        let status = std::process::Command::new("open")
            .arg("-a")
            .arg(name)
            .status()
            .map_err(|e| format!("open 调用失败: {}", e))?;
        if status.success() {
            Ok(())
        } else {
            Err(format!("open 退出码: {:?}", status.code()))
        }
    }
    #[cfg(target_os = "linux")]
    {
        // Detached background spawn; we do not wait on it.
        std::process::Command::new(name)
            .spawn()
            .map(|_| ())
            .map_err(|e| format!("spawn 失败: {}", e))
    }
    #[cfg(not(any(target_os = "windows", target_os = "macos", target_os = "linux")))]
    {
        Err("当前平台不支持启动系统软件".to_string())
    }
}

// --- platform helpers ---

#[cfg(target_os = "windows")]
fn windows_check(needle_lower: &str) -> bool {
    // HKLM is the canonical place for system-wide installs. We also peek
    // HKCU in case the user installed per-user (e.g. portable apps).
    let hives = [HKEY_LOCAL_MACHINE, HKEY_CURRENT_USER];
    for hive in &hives {
        if let Ok(sub) = RegKey::predef(*hive).open_subkey(
            "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
        ) {
            if scan_subkeys_for(&sub, needle_lower) {
                return true;
            }
        }
        // WOW6432Node hosts 32-bit installs on a 64-bit OS.
        if let Ok(sub) = RegKey::predef(*hive).open_subkey(
            "SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
        ) {
            if scan_subkeys_for(&sub, needle_lower) {
                return true;
            }
        }
    }
    false
}

#[cfg(target_os = "windows")]
fn scan_subkeys_for(parent: &RegKey, needle_lower: &str) -> bool {
    for key_name in parent.enum_keys().flatten() {
        if let Ok(child) = parent.open_subkey(&key_name) {
            if let Ok(name) = child.get_value::<String, _>("DisplayName") {
                if name.to_lowercase().contains(needle_lower) {
                    return true;
                }
            }
        }
    }
    false
}

#[cfg(target_os = "macos")]
fn macos_check(needle_lower: &str) -> bool {
    let app_name = format!("{}.app", needle_lower);
    let path = std::path::Path::new("/Applications").join(&app_name);
    if path.exists() {
        return true;
    }
    // /Applications/{Name}.app (capitalized fallback).
    let mut capitalized = String::new();
    if let Some(first) = needle_lower.chars().next() {
        capitalized.push(first.to_ascii_uppercase());
        capitalized.push_str(&needle_lower[1..]);
    }
    let cap_path = std::path::Path::new("/Applications").join(format!("{}.app", capitalized));
    cap_path.exists()
}

#[cfg(target_os = "linux")]
fn linux_check(software_name: &str) -> bool {
    // `which` returns 0 when an executable is found on PATH.
    std::process::Command::new("which")
        .arg(software_name)
        .output()
        .map(|out| out.status.success())
        .unwrap_or(false)
}

fn platform_name() -> &'static str {
    #[cfg(target_os = "windows")]
    {
        "windows"
    }
    #[cfg(target_os = "macos")]
    {
        "macos"
    }
    #[cfg(target_os = "linux")]
    {
        "linux"
    }
    #[cfg(not(any(target_os = "windows", target_os = "macos", target_os = "linux")))]
    {
        "unknown"
    }
}

// ── 全量扫描本地已安装软件（用于建立本地索引） ──────────────
//
// 与白名单驱动的 `list_installed_software` 不同，本函数遍历所有 Uninstall 注册表项，
// 过滤系统组件/驱动/更新补丁，返回 GUI 应用清单（含 exe 路径 + 安装位置）。
// 前端 `localSoftwareIndex.js` 据此判定 UIA / CDP 能力并存入 localStorage。

/// 全量扫描本地已安装软件，返回含路径的清单
pub fn list_all_installed_software() -> Vec<LocalSoftwareEntry> {
    #[cfg(target_os = "windows")]
    {
        windows_list_all()
    }
    #[cfg(target_os = "macos")]
    {
        macos_list_all()
    }
    #[cfg(target_os = "linux")]
    {
        Vec::new()
    }
    #[cfg(not(any(target_os = "windows", target_os = "macos", target_os = "linux")))]
    {
        Vec::new()
    }
}

#[cfg(target_os = "windows")]
fn windows_list_all() -> Vec<LocalSoftwareEntry> {
    let mut out: Vec<LocalSoftwareEntry> = Vec::new();
    let mut seen: std::collections::HashSet<String> = std::collections::HashSet::new();

    let hives = [HKEY_LOCAL_MACHINE, HKEY_CURRENT_USER];
    let paths = [
        "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
        "SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
    ];

    for hive in &hives {
        for path in &paths {
            let sub = match RegKey::predef(*hive).open_subkey(path) {
                Ok(s) => s,
                Err(_) => continue,
            };
            for key_name in sub.enum_keys().flatten() {
                let child = match sub.open_subkey(&key_name) {
                    Ok(c) => c,
                    Err(_) => continue,
                };
                // 跳过 SystemComponent=1 的（系统库/驱动）
                if let Ok(1u32) = child.get_value::<u32, _>("SystemComponent") {
                    continue;
                }
                // 跳过 ReleaseType 含 Update / Hotfix / Security Update 的
                if let Ok(rt) = child.get_value::<String, _>("ReleaseType") {
                    let rtl = rt.to_lowercase();
                    if rtl.contains("update") || rtl.contains("hotfix") || rtl.contains("security") {
                        continue;
                    }
                }
                let name: String = match child.get_value("DisplayName") {
                    Ok(n) => n,
                    Err(_) => continue,
                };
                let name = name.trim().to_string();
                if name.len() < 2 {
                    continue;
                }
                // 跳过 ParentKeyName 的（更新补丁子项）
                if child.get_value::<String, _>("ParentKeyName").is_ok() {
                    continue;
                }
                // 去重（同一软件在 HKLM/HKCU/WOW6432Node 都可能出现）
                let key = name.to_lowercase();
                if !seen.insert(key.clone()) {
                    continue;
                }
                let exe_path: Option<String> = child
                    .get_value::<String, _>("DisplayIcon")
                    .ok()
                    .map(|s| {
                        // DisplayIcon 形如 "C:\path\app.exe,0" — 去掉逗号后的图标索引
                        s.split(',').next().unwrap_or(&s).trim().trim_matches('"').to_string()
                    })
                    .filter(|s| !s.is_empty());
                let install_location: Option<String> = child
                    .get_value::<String, _>("InstallLocation")
                    .ok()
                    .map(|s| s.trim().to_string())
                    .filter(|s| !s.is_empty());
                out.push(LocalSoftwareEntry {
                    name,
                    exe_path,
                    install_location,
                });
            }
        }
    }
    out
}

#[cfg(target_os = "macos")]
fn macos_list_all() -> Vec<LocalSoftwareEntry> {
    // 扫描 /Applications 目录下的 .app
    let mut out = Vec::new();
    let entries = match std::fs::read_dir("/Applications") {
        Ok(e) => e,
        Err(_) => return out,
    };
    for entry in entries.flatten() {
        let path = entry.path();
        let name = match path.file_stem().and_then(|s| s.to_str()) {
            Some(n) => n.to_string(),
            None => continue,
        };
        if name.starts_with('.') {
            continue;
        }
        let exe_path = path.join("Contents").join("MacOS");
        out.push(LocalSoftwareEntry {
            name,
            exe_path: exe_path.to_str().map(|s| s.to_string()),
            install_location: path.to_str().map(|s| s.to_string()),
        });
    }
    out
}
