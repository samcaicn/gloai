// Copyright (c) 2026 MeeJoy

// Hardware-related Tauri commands.
//
// These commands are thin wrappers around `crate::hardware::detector` —
// they format the result for the UI and persist the user-selected
// version in `<app_data>/hardware_version.json` so the choice survives
// restarts.

use crate::hardware::detector::{
    build_fingerprint, HardwareDetector, HardwareInfo, HardwareVersion, SystemInfo,
};
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::{Path, PathBuf};
use tauri::Manager;
use thiserror::Error;

const HARDWARE_VERSION_FILENAME: &str = "hardware_version.json";

#[derive(Debug, Error)]
pub enum HardwareCommandError {
    #[error("未知的硬件版本: {0}")]
    UnknownVersion(String),
    #[error("IO 错误: {0}")]
    Io(String),
    #[error("配置错误: {0}")]
    Config(String),
}

impl From<std::io::Error> for HardwareCommandError {
    fn from(error: std::io::Error) -> Self {
        HardwareCommandError::Io(error.to_string())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
struct HardwareVersionConfig {
    #[serde(default)]
    selected_version: Option<String>,
    #[serde(default)]
    last_detected: Option<String>,
}

/// Run a full hardware probe and return the result. The matching
/// `HardwareVersion` is computed from the probe (CPU + memory + GPU).
#[tauri::command]
pub fn detect_hardware() -> HardwareInfo {
    HardwareDetector::detect()
}

/// Return the matched hardware version for the current machine as a
/// short string (`cpu_only` / `integrated` / `discrete` / `unsupported`).
#[tauri::command]
pub fn match_hardware_version() -> String {
    let info = HardwareDetector::detect();
    format!("{}", info.matched_version)
}

/// Return the recommended hardware version. Today this is the same as
/// `match_hardware_version`; once we ship per-tier installers the
/// recommendation may diverge (e.g. recommend `discrete` for users that
/// explicitly want to run the 70B model regardless of detected GPU).
#[tauri::command]
pub fn get_recommended_version() -> String {
    let info = HardwareDetector::detect();
    format!("{}", info.recommended_version)
}

/// Persist the user-selected version. This is what the Settings UI
/// updates when the user manually picks a tier.
#[tauri::command]
pub fn set_hardware_version(
    app: tauri::AppHandle,
    version: String,
) -> Result<(), String> {
    let parsed = parse_version(&version).map_err(|e| e.to_string())?;
    let config_path = hardware_version_path(&app).map_err(|e| e.to_string())?;
    let mut config: HardwareVersionConfig = match fs::read_to_string(&config_path) {
        Ok(content) => serde_json::from_str(&content).unwrap_or_default(),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            HardwareVersionConfig::default()
        }
        Err(error) => return Err(format!("读取硬件版本配置失败: {}", error)),
    };
    let info = HardwareDetector::detect();
    config.last_detected = Some(format!("{}", info.matched_version));
    config.selected_version = Some(format!("{}", parsed));
    write_config(&config_path, &config).map_err(|e| e.to_string())?;
    Ok(())
}

/// Lighter-weight system-info probe used by the Settings UI.
#[tauri::command]
pub fn get_system_info() -> SystemInfo {
    HardwareDetector::system_info()
}

/// Best-effort "selected version" lookup. Returns `None` if the user
/// hasn't picked one yet — callers should fall back to the matched /
/// recommended version.
#[allow(dead_code)] // re-exported for other modules that need to read the user's choice (currently only consumed by the Settings UI via `set_hardware_version`); lib.rs's `invoke_handler!` doesn't see this helper as a #[tauri::command] entry point.
pub fn read_selected_version(
    app_data_dir: &Path,
) -> Result<Option<HardwareVersion>, HardwareCommandError> {
    let config_path = app_data_dir.join(HARDWARE_VERSION_FILENAME);
    match fs::read_to_string(&config_path) {
        Ok(content) => {
            let config: HardwareVersionConfig = serde_json::from_str(&content)
                .map_err(|error| HardwareCommandError::Config(error.to_string()))?;
            Ok(config
                .selected_version
                .as_deref()
                .and_then(|raw| parse_version(raw).ok()))
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(error) => Err(HardwareCommandError::Io(error.to_string())),
    }
}

/// Convenience: build a stable fingerprint from the current hardware
/// probe. Used by the crypto layer at startup; the function exists so
/// other modules can also use the same fingerprint the UI sees (for
/// cross-device pairing in the future).
pub fn compute_hardware_fingerprint() -> String {
    let info = HardwareDetector::detect();
    build_fingerprint(&info.cpu.brand, info.memory_total_mb, &info.os_type)
}

fn parse_version(raw: &str) -> Result<HardwareVersion, HardwareCommandError> {
    match raw.trim().to_ascii_lowercase().as_str() {
        "cpu_only" | "cpu" | "cpu-only" => Ok(HardwareVersion::CpuOnly),
        "integrated" | "igpu" => Ok(HardwareVersion::Integrated),
        "discrete" | "dgpu" => Ok(HardwareVersion::Discrete),
        "unsupported" => Ok(HardwareVersion::Unsupported),
        other => Err(HardwareCommandError::UnknownVersion(other.to_string())),
    }
}

fn hardware_version_path(app: &tauri::AppHandle) -> Result<PathBuf, HardwareCommandError> {
    let dir = app
        .path()
        .app_data_dir()
        .map_err(|e| HardwareCommandError::Io(e.to_string()))?;
    Ok(dir.join(HARDWARE_VERSION_FILENAME))
}

fn write_config(
    path: &Path,
    config: &HardwareVersionConfig,
) -> Result<(), HardwareCommandError> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let serialized = serde_json::to_string_pretty(config)
        .map_err(|error| HardwareCommandError::Config(error.to_string()))?;
    fs::write(path, serialized)?;
    Ok(())
}
