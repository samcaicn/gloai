// Copyright (c) 2026 MeeJoy
//
// 设备注册用硬件 ID 检索命令（持久化版 v3）。
//
// **核心修复**: v1 的文件缓存被 NSIS 卸载时删除 → 指纹丢失。
// v2 新增 Windows 注册表缓存层（卸载器不删注册表）+ 多硬件源。
// v3 修复 macOS 单硬件源缺陷：ioreg 失败直接跳 uuid-v4 随机兜底 →
//    跨启动指纹变化（违反"重装系统/重装软件指纹不变"铁律）。
//    新增 macOS 多硬件源降级链 + 家目录 dotfile 缓存 + 绝对路径。
//
// **持久化策略（多级缓存 + 多硬件源，保证软件重装/OS 重装后指纹不变）**：
//
// 1. 主缓存: `$APPDATA_DIR/.hardware_id` (app_data_dir, 跨重启)
// 2. 备份缓存: 平台持久路径 (跨 app 卸载/重装)
//    - Windows: `%APPDATA%\tupai\.hardware_id`
//    - macOS:   `~/Library/Application Support/tupai/.hardware_id`
//    - Linux:   `~/.local/share/tupai/.hardware_id` (或 $XDG_DATA_HOME)
// 2.5. 家目录 dotfile 缓存 (macOS/Linux): `~/.tupai_hardware_id`
//      不依赖 Library/Application Support 权限, App Translocation 不影响 HOME 根。
// 3. 注册表缓存 (仅 Windows): `HKCU\Software\tupai\hardware_id` (卸载器不删)
// 4. 硬件命令获取 (跨 OS 重装不变, 多源降级):
//    - Windows: SMBIOS UUID → 主板序列号 → MachineGuid
//    - Linux:   /etc/machine-id → 主板序列号(/sys/class/dmi/id/board_serial) → 产品 UUID(/sys/class/dmi/id/product_uuid)
//    - macOS:   ioreg IOPlatformUUID → ioreg IOPlatformSerialNumber → sysctl hw.uuid
//      (三源都读自固件/NVRAM, ioreg-uuid 与 sysctl-uuid 同值; 全部用绝对路径 /usr/sbin/...)
// 5. 最终兜底: uuid v4 + 写入所有缓存层 (至少同一次安装内稳定)
//
// 读取优先级: 主缓存 → 备份缓存 → 家目录 dotfile → 注册表缓存 → 硬件命令 → fallback
// 写入: 硬件命令成功后同步写入所有可用缓存层

use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Manager};
use tokio::process::Command;

const CACHE_FILENAME: &str = ".hardware_id";

// ── Brand-conditional names (P2-4) ──────────────────────────────────────
//
// The safeopc OEM build (feature `safeopc-brand`) uses a different
// publisher / bundle id from the AIMarketing build, so its persistent cache
// paths MUST also differ — otherwise a user who installs both would
// share the same hardware_id cache and confuse device-registration
// telemetry. Cfg-gated `const`s compile to the right value per brand
// with zero runtime cost.
#[cfg(feature = "safeopc-brand")]
const APP_CACHE_DIR_NAME: &str = "safeopc";
#[cfg(not(feature = "safeopc-brand"))]
const APP_CACHE_DIR_NAME: &str = "tupai";

#[cfg(feature = "safeopc-brand")]
const HOME_DOTFILE_NAME: &str = ".safeopc_hardware_id";
#[cfg(not(feature = "safeopc-brand"))]
const HOME_DOTFILE_NAME: &str = ".tupai_hardware_id";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HardwareId {
    pub hardware_id: String,
    pub platform: String,
    pub arch: String,
    pub os_version: String,
    pub is_fallback: bool,
    pub source: String,
}

/// 计算硬件 ID（核心逻辑，供 mesh 等同模块进程内调用，复用全部缓存/硬件源）。
///
/// 与 `get_hardware_id` 命令分离：mesh 模块需要进程内拿 hardware_id 做指纹派生，
/// 不能走 IPC 命令往返。本函数与原命令体逻辑完全一致，命令仅作薄包装。
pub async fn compute_hardware_id(app: &AppHandle) -> Result<HardwareId, String> {
    let (platform, arch) = detect_platform_arch();
    let os_version = detect_os_version().unwrap_or_default();

    // ── 1. 主缓存 (app_data_dir) ──
    if let Some(cached) = read_primary_cache(app).await {
        log::debug!("[hardware_id] primary cache hit: source={}", cached.source);
        return Ok(HardwareId {
            hardware_id: cached.hardware_id,
            platform,
            arch,
            os_version,
            is_fallback: cached.source == "uuid-v4",
            source: format!("cache:{}", cached.source),
        });
    }

    // ── 2. 备份缓存 (平台持久路径) ──
    if let Some(cached) = read_backup_cache().await {
        log::debug!("[hardware_id] backup cache hit: source={}", cached.source);
        write_primary_cache(app, &cached).await;
        write_home_dotfile_cache(&cached).await;
        return Ok(HardwareId {
            hardware_id: cached.hardware_id,
            platform,
            arch,
            os_version,
            is_fallback: cached.source == "uuid-v4",
            source: format!("backup:{}", cached.source),
        });
    }

    // ── 2.5. 家目录 dotfile 缓存 (macOS/Linux, 跨 app 卸载最稳健) ──
    // ~/.tupai_hardware_id 不依赖 Library/Application Support 权限,
    // App Translocation / 卸载器清理都不会动 HOME 根目录。
    if let Some(cached) = read_home_dotfile_cache().await {
        log::debug!("[hardware_id] home dotfile cache hit: source={}", cached.source);
        write_primary_cache(app, &cached).await;
        write_backup_cache(&cached).await;
        return Ok(HardwareId {
            hardware_id: cached.hardware_id,
            platform,
            arch,
            os_version,
            is_fallback: cached.source == "uuid-v4",
            source: format!("home-dotfile:{}", cached.source),
        });
    }

    // ── 3. 注册表缓存 (仅 Windows) ──
    #[cfg(target_os = "windows")]
    if let Some(cached) = read_registry_cache().await {
        log::debug!("[hardware_id] registry cache hit: source={}", cached.source);
        write_primary_cache(app, &cached).await;
        write_backup_cache(&cached).await;
        return Ok(HardwareId {
            hardware_id: cached.hardware_id,
            platform,
            arch,
            os_version,
            is_fallback: cached.source == "uuid-v4",
            source: format!("registry:{}", cached.source),
        });
    }

    // ── 4. 硬件命令获取 ID ──
    let (hardware_id, hw_source) = match platform.as_str() {
        "darwin" => match read_macos_hardware_id().await {
            Some(id) => (id.0, id.1),
            None => fallback_uuid(),
        },
        "windows" => match read_windows_hardware_id().await {
            Some(id) => (id.0, id.1),
            None => fallback_uuid(),
        },
        "linux" => match read_linux_hardware_id().await {
            Some(id) => (id.0, id.1),
            None => fallback_uuid(),
        },
        _ => fallback_uuid(),
    };

    let is_fallback = hw_source == "uuid-v4";

    let cached = CachedHardwareId {
        hardware_id: hardware_id.clone(),
        source: hw_source.clone(),
    };

    // ── 5. 持久化到所有缓存层 ──
    write_primary_cache(app, &cached).await;
    write_backup_cache(&cached).await;
    write_home_dotfile_cache(&cached).await;
    #[cfg(target_os = "windows")]
    write_registry_cache(&cached).await;

    log::info!(
        "[hardware_id] generated new id: source={}, is_fallback={}",
        hw_source, is_fallback
    );

    Ok(HardwareId {
        hardware_id,
        platform,
        arch,
        os_version,
        is_fallback,
        source: hw_source,
    })
}

/// Tauri command: 前端 `invoke('get_hardware_id')` 拿到的就是这个。
///
/// 薄包装：实际逻辑在 `compute_hardware_id`，分离后供 mesh 等同进程模块复用。
#[tauri::command]
pub async fn get_hardware_id(app: AppHandle) -> Result<HardwareId, String> {
    compute_hardware_id(&app).await
}

// ── 持久化缓存结构 ──────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
struct CachedHardwareId {
    hardware_id: String,
    source: String,
}

async fn read_primary_cache(app: &AppHandle) -> Option<CachedHardwareId> {
    let dir = app.path().app_data_dir().ok()?;
    let path = dir.join(CACHE_FILENAME);
    let content = tokio::fs::read_to_string(&path).await.ok()?;
    parse_cache_content(&content)
}

async fn write_primary_cache(app: &AppHandle, cached: &CachedHardwareId) {
    if let Ok(dir) = app.path().app_data_dir() {
        let path = dir.join(CACHE_FILENAME);
        let content = format!("{}\n{}\n", cached.hardware_id, cached.source);
        if let Err(e) = tokio::fs::create_dir_all(&dir).await {
            log::warn!("[hardware_id] create primary dir failed: {}", e);
            return;
        }
        if let Err(e) = tokio::fs::write(&path, &content).await {
            log::warn!("[hardware_id] write primary cache failed: {}", e);
        }
    }
}

fn backup_cache_path() -> Option<std::path::PathBuf> {
    // P2-3: cache the resolved path in a OnceLock — this function is called
    // up to 3 times per `get_hardware_id` invocation (read + write +
    // registry-miss path) and each call previously re-read APPDATA / HOME
    // and re-joined the path. Env var lookups are ~1µs but pointless when
    // the result never changes within a process.
    use std::sync::OnceLock;
    static CACHE: OnceLock<Option<std::path::PathBuf>> = OnceLock::new();
    CACHE
        .get_or_init(|| {
            let base = if cfg!(target_os = "windows") {
                std::env::var_os("APPDATA").map(std::path::PathBuf::from)
            } else if cfg!(target_os = "macos") {
                std::env::var_os("HOME").map(|h| {
                    std::path::PathBuf::from(h)
                        .join("Library")
                        .join("Application Support")
                })
            } else if cfg!(target_os = "linux") {
                std::env::var_os("XDG_DATA_HOME")
                    .map(std::path::PathBuf::from)
                    .or_else(|| {
                        std::env::var_os("HOME").map(|h| {
                            std::path::PathBuf::from(h).join(".local").join("share")
                        })
                    })
            } else {
                None
            }?;
            Some(base.join(APP_CACHE_DIR_NAME).join(CACHE_FILENAME))
        })
        .clone()
}

async fn read_backup_cache() -> Option<CachedHardwareId> {
    let path = backup_cache_path()?;
    let content = tokio::fs::read_to_string(&path).await.ok()?;
    parse_cache_content(&content)
}

async fn write_backup_cache(cached: &CachedHardwareId) {
    if let Some(path) = backup_cache_path() {
        if let Some(parent) = path.parent() {
            if let Err(e) = tokio::fs::create_dir_all(parent).await {
                log::warn!("[hardware_id] create backup dir failed: {}", e);
                return;
            }
        }
        let content = format!("{}\n{}\n", cached.hardware_id, cached.source);
        if let Err(e) = tokio::fs::write(&path, &content).await {
            log::warn!("[hardware_id] write backup cache failed: {}", e);
        }
    }
}

// ── 家目录 dotfile 缓存 (跨 app 卸载/重装最稳健) ─────────
//
// macOS/Linux: ~/.tupai_hardware_id —— 直接放在 HOME 根目录,
// 不依赖 ~/Library/Application Support/<app>/ 权限 (App Translocation /
// 沙箱/卸载器清理都不会动 HOME 根)。Windows 已有注册表缓存, 不使用 dotfile。
fn home_dotfile_cache_path() -> Option<std::path::PathBuf> {
    // P2-3: cache via OnceLock — same rationale as `backup_cache_path`.
    use std::sync::OnceLock;
    static CACHE: OnceLock<Option<std::path::PathBuf>> = OnceLock::new();
    CACHE
        .get_or_init(|| {
            if cfg!(target_os = "windows") {
                return None;
            }
            std::env::var_os("HOME")
                .map(std::path::PathBuf::from)
                .map(|h| h.join(HOME_DOTFILE_NAME))
        })
        .clone()
}

async fn read_home_dotfile_cache() -> Option<CachedHardwareId> {
    let path = home_dotfile_cache_path()?;
    let content = tokio::fs::read_to_string(&path).await.ok()?;
    parse_cache_content(&content)
}

async fn write_home_dotfile_cache(cached: &CachedHardwareId) {
    if let Some(path) = home_dotfile_cache_path() {
        let content = format!("{}\n{}\n", cached.hardware_id, cached.source);
        if let Err(e) = tokio::fs::write(&path, &content).await {
            log::warn!("[hardware_id] write home dotfile cache failed: {}", e);
        }
    }
}

fn parse_cache_content(content: &str) -> Option<CachedHardwareId> {
    let mut lines = content.lines();
    let hardware_id = lines.next()?.trim().to_string();
    if hardware_id.is_empty() || hardware_id.len() < 8 {
        return None;
    }
    let source = lines.next().unwrap_or("unknown").trim().to_string();
    Some(CachedHardwareId {
        hardware_id,
        source,
    })
}

// ── Windows 注册表缓存 (卸载器不删) ─────────────────────

#[cfg(target_os = "windows")]
#[cfg(feature = "safeopc-brand")]
const REG_KEY: &str = r"SOFTWARE\safeopc";
#[cfg(target_os = "windows")]
#[cfg(not(feature = "safeopc-brand"))]
const REG_KEY: &str = r"SOFTWARE\tupai";
#[cfg(target_os = "windows")]
const REG_VALUE: &str = "hardware_id";

#[cfg(target_os = "windows")]
async fn read_registry_cache() -> Option<CachedHardwareId> {
    let output = Command::new("reg")
        .args(["query", REG_KEY, "/v", REG_VALUE])
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::null())
        .output()
        .await
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let text = String::from_utf8_lossy(&output.stdout);
    // reg query 输出: "    hardware_id    REG_SZ    <value>"
    for line in text.lines() {
        let trimmed = line.trim();
        if trimmed.contains("REG_SZ") {
            let parts: Vec<&str> = trimmed.splitn(3, "REG_SZ").collect();
            if parts.len() >= 2 {
                let val = parts[1].trim();
                if val.len() >= 8 {
                    return Some(CachedHardwareId {
                        hardware_id: val.to_string(),
                        source: "registry".to_string(),
                    });
                }
            }
        }
    }
    None
}

#[cfg(target_os = "windows")]
async fn write_registry_cache(cached: &CachedHardwareId) {
    let mut cmd = Command::new("reg");
    cmd.args([
        "add", REG_KEY,
        "/v", REG_VALUE,
        "/t", "REG_SZ",
        "/d", &cached.hardware_id,
        "/f",
    ]);
    crate::commands::legacy::apply_no_window_tokio(&mut cmd);
    if let Err(e) = cmd.output().await {
        log::warn!("[hardware_id] write registry cache failed: {}", e);
    }
}

// ── 平台 / 架构 / OS 版本探测 ──────────────────────────

fn detect_platform_arch() -> (String, String) {
    let platform = if cfg!(target_os = "macos") {
        "darwin".to_string()
    } else if cfg!(target_os = "windows") {
        "windows".to_string()
    } else if cfg!(target_os = "linux") {
        "linux".to_string()
    } else if cfg!(target_os = "android") {
        "android".to_string()
    } else if cfg!(target_os = "ios") {
        "ios".to_string()
    } else {
        "unknown".to_string()
    };

    let arch = if cfg!(target_arch = "x86_64") {
        "x86_64".to_string()
    } else if cfg!(target_arch = "aarch64") {
        "aarch64".to_string()
    } else if cfg!(target_arch = "arm") {
        "arm".to_string()
    } else {
        "unknown".to_string()
    };

    (platform, arch)
}

// ── OS version string formatting (pure, testable) ───────────────────────
//
// Extracted from the platform-gated `detect_os_version` branches so the
// field-assembly logic (Windows release_id fallback, empty-field handling)
// can be unit-tested on any platform without shelling out to sw_vers or
// reading the registry. Both `detect_os_version` (here, hardware_id field)
// and `detect_os_version_string` (system.rs::check_os_compatibility) call
// these so the two surfaces stay consistent.

/// Assemble a Windows display string from the four registry fields read
/// from `HKLM\SOFTWARE\Microsoft\Windows NT\CurrentVersion`.
///
/// `display_version` (22H2/23H2/24H2) is preferred; on older Win10 (<2004)
/// it is absent and we fall back to `release_id` (e.g. "2009"). Returns
/// `None` only when every field is empty. Pure & platform-agnostic so it
/// can be exercised by `cargo test` on any host.
pub(crate) fn format_windows_version(
    product_name: &str,
    display_version: &str,
    release_id: &str,
    current_build: &str,
) -> Option<String> {
    let product = product_name.trim();
    let display = display_version.trim();
    let release = release_id.trim();
    let build = current_build.trim();

    let version_tag = if !display.is_empty() {
        display
    } else if !release.is_empty() {
        release
    } else {
        ""
    };

    let mut parts: Vec<&str> = Vec::new();
    if !product.is_empty() {
        parts.push(product);
    }
    if !version_tag.is_empty() {
        parts.push(version_tag);
    }
    let head = parts.join(" ");
    if !build.is_empty() {
        Some(format!("{} (build {})", head, build))
    } else if !head.is_empty() {
        Some(head)
    } else {
        None
    }
}

/// Format a macOS `sw_vers -productVersion` output into a human-readable
/// display string for the compatibility report. Empty input yields
/// "macOS (unknown version)". Pure & platform-agnostic.
pub(crate) fn format_macos_version_string(product_version: &str) -> String {
    let trimmed = product_version.trim();
    if trimmed.is_empty() {
        "macOS (unknown version)".to_string()
    } else {
        format!("macOS {}", trimmed)
    }
}

#[cfg(target_os = "macos")]
fn detect_os_version() -> Option<String> {
    // 绝对路径: GUI 应用从 Finder/Dock 启动时 PATH 极简, 相对路径可能找不到。
    let output = std::process::Command::new("/usr/bin/sw_vers")
        .arg("-productVersion")
        .output()
        .ok()?;
    if output.status.success() {
        Some(std::str::from_utf8(&output.stdout).ok()?.trim().to_string())
    } else {
        None
    }
}

#[cfg(target_os = "windows")]
fn detect_os_version() -> Option<String> {
    // 读 HKLM\SOFTWARE\Microsoft\Windows NT\CurrentVersion 拼出
    // "Windows 11 Pro 23H2 (build 22631)" 这样的版本字符串。失败返回 None
    // (硬件 ID 仍能生成, 只是 os_version 字段为空)。
    //
    // DisplayVersion (22H2/23H2/24H2) 在 Win10 2004+ 才有, 旧版本用 ReleaseId
    // (如 2009)。两者都读不到时退化为只显示 ProductName + build。
    use winreg::enums::{HKEY_LOCAL_MACHINE, KEY_READ};
    use winreg::RegKey;
    let hklm = RegKey::predef(HKEY_LOCAL_MACHINE);
    let key = hklm
        .open_subkey_with_flags(
            r"SOFTWARE\Microsoft\Windows NT\CurrentVersion",
            KEY_READ,
        )
        .ok()?;
    let product_name: String = key.get_value("ProductName").unwrap_or_default();
    let display_version: String = key.get_value("DisplayVersion").unwrap_or_default();
    let release_id: String = key.get_value("ReleaseId").unwrap_or_default();
    let current_build: String = key.get_value("CurrentBuild").unwrap_or_default();

    format_windows_version(&product_name, &display_version, &release_id, &current_build)
}

#[cfg(target_os = "linux")]
fn detect_os_version() -> Option<String> {
    std::fs::read_to_string("/etc/os-release")
        .ok()
        .and_then(|content| {
            content
                .lines()
                .find(|line| line.starts_with("PRETTY_NAME="))
                .map(|line| {
                    line.trim_start_matches("PRETTY_NAME=")
                        .trim_matches('"')
                        .to_string()
                })
        })
}

#[cfg(not(any(target_os = "macos", target_os = "windows", target_os = "linux")))]
fn detect_os_version() -> Option<String> {
    None
}

// ── 平台特定的硬件 ID 提取 ──────────────────────────────
//
// macOS 多硬件源降级链 (与 Windows 对齐, 避免单点失败导致 uuid-v4 随机兜底):
//   1. ioreg IOPlatformUUID        (首选, 硬件级, 跨 OS 重装不变)
//   2. ioreg IOPlatformSerialNumber (同一 ioreg 输出的硬件序列号, 免额外进程)
//   3. sysctl hw.uuid               (不同二进制, 同一 UUID 值; ioreg 失败时的关键备份)
// 都失败才走 uuid-v4 兜底 (每次随机, 仅作最后保证非空)
//
// 关键设计: ioreg-uuid 与 sysctl-uuid 返回同一个 Platform UUID (都读自固件/NVRAM),
// 所以即使 ioreg 二进制失败、sysctl 成功, 跨启动指纹仍然稳定。
// 所有命令用绝对路径 (/usr/sbin/...) —— GUI 应用从 Finder/Dock 启动时 PATH 极简,
// 相对路径 "ioreg" 可能找不到; App Translocation (从 DMG 直接运行) 更会加剧。

/// macOS: 多硬件源降级链, 返回 (id, source)。
#[cfg(target_os = "macos")]
async fn read_macos_hardware_id() -> Option<(String, String)> {
    // 1. ioreg (一次调用, 提取 UUID + Serial 两个字段)
    if let Some(text) = run_macos_ioreg().await {
        // 1a. IOPlatformUUID (首选)
        if let Some(uuid) = extract_ioplatform_uuid_impl(&text) {
            log::debug!("[hardware_id] macOS ioreg IOPlatformUUID hit");
            return Some((uuid, "ioreg-uuid".to_string()));
        }
        log::warn!("[hardware_id] macOS ioreg 输出中未找到 IOPlatformUUID, 尝试 IOPlatformSerialNumber");
        // 1b. IOPlatformSerialNumber (同一 ioreg 输出, 免额外进程)
        if let Some(serial) = extract_ioplatform_serial_impl(&text) {
            log::warn!("[hardware_id] macOS ioreg IOPlatformSerialNumber hit (UUID 缺失, 用序列号兜底)");
            return Some((serial, "ioreg-serial".to_string()));
        }
        log::warn!("[hardware_id] macOS ioreg 输出中未找到 IOPlatformSerialNumber");
    } else {
        log::warn!("[hardware_id] macOS ioreg 命令执行失败, 尝试 sysctl hw.uuid");
    }

    // 2. sysctl hw.uuid (不同二进制, 同一 UUID; ioreg 失败时的关键备份)
    if let Some(uuid) = read_macos_sysctl_hw_uuid().await {
        log::warn!("[hardware_id] macOS sysctl hw.uuid hit (ioreg 失败的备份)");
        return Some((uuid, "sysctl-uuid".to_string()));
    }
    log::warn!("[hardware_id] macOS sysctl hw.uuid 也失败, 将使用 uuid-v4 兜底");

    None
}

#[cfg(target_os = "macos")]
async fn run_macos_ioreg() -> Option<String> {
    let output = Command::new("/usr/sbin/ioreg")
        .arg("-rd1")
        .arg("-c")
        .arg("IOPlatformExpertDevice")
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .output()
        .await
        .map_err(|e| log::warn!("[hardware_id] ioreg spawn 失败: {}", e))
        .ok()?;
    if !output.status.success() {
        log::warn!(
            "[hardware_id] ioreg 退出非零: {:?}, stderr={}",
            output.status.code(),
            String::from_utf8_lossy(&output.stderr).trim()
        );
        return None;
    }
    Some(String::from_utf8_lossy(&output.stdout).into_owned())
}

/// macOS: sysctl -n hw.uuid (与 IOPlatformUUID 同值, 不同二进制)
#[cfg(target_os = "macos")]
async fn read_macos_sysctl_hw_uuid() -> Option<String> {
    let output = Command::new("/usr/sbin/sysctl")
        .arg("-n")
        .arg("hw.uuid")
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .output()
        .await
        .map_err(|e| log::warn!("[hardware_id] sysctl spawn 失败: {}", e))
        .ok()?;
    if !output.status.success() {
        log::warn!(
            "[hardware_id] sysctl hw.uuid 退出非零: {:?}, stderr={}",
            output.status.code(),
            String::from_utf8_lossy(&output.stderr).trim()
        );
        return None;
    }
    let raw = String::from_utf8_lossy(&output.stdout).trim().to_string();
    // hw.uuid 返回标准 UUID 格式 (36 字符) 或裸 UUID (32 字符); 过滤空值/错误回显
    if raw.len() >= 8 && !raw.starts_with("sysctl:") && !raw.contains("unknown") {
        Some(raw)
    } else {
        log::warn!("[hardware_id] sysctl hw.uuid 返回值异常: {:?}", raw);
        None
    }
}

#[allow(dead_code)]
fn extract_ioplatform_uuid_impl(text: &str) -> Option<String> {
    extract_quoted_ioreg_field(text, "IOPlatformUUID", 8)
}

/// 从 ioreg 输出中提取 "IOPlatformSerialNumber" = "..." 字段。
/// 与 UUID 不同, 序列号较短 (Mac 序列号 8-12 字符), 最小长度放宽到 4。
#[allow(dead_code)]
fn extract_ioplatform_serial_impl(text: &str) -> Option<String> {
    extract_quoted_ioreg_field(text, "IOPlatformSerialNumber", 4)
}

/// 通用 ioreg 字段提取: 查找 `"FieldName" = "value"`, 返回引号内 value。
/// `min_len` 为 value 最小长度 (UUID 8, 序列号 4)。
fn extract_quoted_ioreg_field(text: &str, field: &str, min_len: usize) -> Option<String> {
    let needle = format!("\"{}\"", field);
    let idx = text.find(&needle)?;
    let after = &text[idx + needle.len()..];
    let eq_idx = after.find('=')?;
    let after_eq = after[eq_idx + 1..].trim_start();
    let mut chars = after_eq.chars();
    if chars.next() != Some('"') {
        return None;
    }
    let rest = after_eq.trim_start_matches('"');
    let end = rest.find('"')?;
    let candidate = rest[..end].trim();
    if candidate.len() >= min_len {
        Some(candidate.to_string())
    } else {
        None
    }
}

#[cfg(not(target_os = "macos"))]
async fn read_macos_hardware_id() -> Option<(String, String)> {
    None
}

/// Windows 多硬件源: SMBIOS UUID → 主板序列号 → MachineGuid
/// 都是硬件级标识, 跨 OS 重装不变 (SMBIOS/主板序列号)
/// MachineGuid 是 OS 级, 跨 app 卸载不变但 OS 重装会变
#[cfg(target_os = "windows")]
async fn read_windows_hardware_id() -> Option<(String, String)> {
    // 1. SMBIOS UUID (首选, 硬件级)
    if let Some(uuid) = read_windows_uuid().await {
        return Some((uuid, "cim".to_string()));
    }
    log::warn!("[hardware_id] SMBIOS UUID not available, trying motherboard serial");

    // 2. 主板序列号 (硬件级, 跨 OS 重装不变)
    if let Some(serial) = read_windows_board_serial().await {
        return Some((serial, "board-serial".to_string()));
    }
    log::warn!("[hardware_id] board serial not available, trying MachineGuid");

    // 3. MachineGuid (OS 级, 跨 app 卸载不变, OS 重装会变)
    if let Some(guid) = read_windows_machine_guid().await {
        return Some((guid, "machine-guid".to_string()));
    }
    log::warn!("[hardware_id] all Windows hardware sources failed");
    None
}

/// Windows: Get-CimInstance Win32_ComputerSystemProduct.UUID (SMBIOS UUID)
#[cfg(target_os = "windows")]
async fn read_windows_uuid() -> Option<String> {
    let candidates: &[&str] = &["powershell.exe", "powershell", "pwsh.exe", "pwsh"];
    for cmd in candidates {
        if let Some(uuid) = try_windows_cim(cmd).await {
            return Some(uuid);
        }
    }
    None
}

#[cfg(target_os = "windows")]
async fn try_windows_cim(cmd: &str) -> Option<String> {
    let mut command = Command::new(cmd);
    command
        .args([
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            "(Get-CimInstance Win32_ComputerSystemProduct).UUID",
        ])
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::null());
    crate::commands::legacy::apply_no_window_tokio(&mut command);
    let output = command.output().await.ok()?;
    if !output.status.success() {
        return None;
    }
    let raw = std::str::from_utf8(&output.stdout).ok()?;
    let candidate = raw
        .lines()
        .map(str::trim)
        .find(|line| !line.is_empty() && line.len() >= 8)?;
    if candidate.eq_ignore_ascii_case("FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF") {
        return None;
    }
    Some(candidate.to_string())
}

/// Windows: 主板序列号 (Get-CimInstance Win32_BaseBoard.SerialNumber)
/// 硬件级标识, 跨 OS 重装不变
#[cfg(target_os = "windows")]
async fn read_windows_board_serial() -> Option<String> {
    let candidates: &[&str] = &["powershell.exe", "powershell", "pwsh.exe", "pwsh"];
    for cmd in candidates {
        let mut command = Command::new(cmd);
        command
            .args([
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "(Get-CimInstance Win32_BaseBoard).SerialNumber",
            ])
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::null());
        crate::commands::legacy::apply_no_window_tokio(&mut command);
        let output = command.output().await.ok()?;
        if !output.status.success() {
            continue;
        }
        let raw = std::str::from_utf8(&output.stdout).ok()?;
        let candidate = raw
            .lines()
            .map(str::trim)
            .find(|line| !line.is_empty())?;
        // 过滤掉 "To be filled by O.E.M." 等占位符
        let lower = candidate.to_ascii_lowercase();
        if lower.contains("to be filled")
            || lower.contains("o.e.m")
            || lower.contains("default")
            || lower.contains("none")
            || candidate.len() < 4
        {
            continue;
        }
        return Some(candidate.to_string());
    }
    None
}

/// Windows: MachineGuid (注册表 HKLM\SOFTWARE\Microsoft\Cryptography\MachineGuid)
/// OS 级标识, 跨 app 卸载不变, OS 重装会变
#[cfg(target_os = "windows")]
async fn read_windows_machine_guid() -> Option<String> {
    let mut command = Command::new("reg");
    command
        .args([
            "query",
            r"HKLM\SOFTWARE\Microsoft\Cryptography",
            "/v",
            "MachineGuid",
        ])
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::null());
    crate::commands::legacy::apply_no_window_tokio(&mut command);
    let output = command.output().await.ok()?;
    if !output.status.success() {
        return None;
    }
    let text = String::from_utf8_lossy(&output.stdout);
    for line in text.lines() {
        let trimmed = line.trim();
        if trimmed.contains("REG_SZ") {
            let parts: Vec<&str> = trimmed.splitn(3, "REG_SZ").collect();
            if parts.len() >= 2 {
                let val = parts[1].trim();
                if val.len() >= 8 {
                    return Some(val.to_string());
                }
            }
        }
    }
    None
}

#[cfg(not(target_os = "windows"))]
async fn read_windows_hardware_id() -> Option<(String, String)> {
    None
}

/// Linux 多硬件源: machine-id → 主板序列号 → product UUID
#[cfg(target_os = "linux")]
async fn read_linux_hardware_id() -> Option<(String, String)> {
    // 1. /etc/machine-id (系统级, 跨用户; OS 重装会变)
    if let Some(id) = read_linux_machine_id().await {
        return Some((id, "machine-id".to_string()));
    }

    // 2. 主板序列号 (硬件级, 跨 OS 重装不变)
    if let Some(serial) = read_dmi_file("/sys/class/dmi/id/board_serial").await {
        return Some((serial, "board-serial".to_string()));
    }

    // 3. 产品 UUID (硬件级)
    if let Some(uuid) = read_dmi_file("/sys/class/dmi/id/product_uuid").await {
        return Some((uuid, "product-uuid".to_string()));
    }

    None
}

#[cfg(target_os = "linux")]
async fn read_dmi_file(path: &str) -> Option<String> {
    let content = tokio::fs::read_to_string(path).await.ok()?;
    let trimmed = content.trim();
    if trimmed.is_empty() || trimmed.len() < 4 {
        return None;
    }
    // 过滤占位符
    let lower = trimmed.to_ascii_lowercase();
    if lower.contains("to be filled") || lower.contains("o.e.m") || lower.contains("default") {
        return None;
    }
    Some(trimmed.to_string())
}

#[cfg(target_os = "linux")]
async fn read_linux_machine_id() -> Option<String> {
    use tokio::fs;
    let primary = fs::read_to_string("/etc/machine-id").await.ok();
    let from_primary = primary.as_deref().and_then(extract_machine_id_line);
    if let Some(id) = from_primary {
        return Some(id);
    }
    let fallback = fs::read_to_string("/var/lib/dbus/machine-id")
        .await
        .ok()
        .as_deref()
        .and_then(extract_machine_id_line);
    fallback
}

#[allow(dead_code)]
fn extract_machine_id_line(content: &str) -> Option<String> {
    content
        .lines()
        .map(str::trim)
        .find(|line| !line.is_empty() && line.len() >= 16)
        .map(|s| s.to_string())
}

#[cfg(not(target_os = "linux"))]
async fn read_linux_hardware_id() -> Option<(String, String)> {
    None
}

// ── 兜底 ──────────────────────────────────────────────

fn fallback_uuid() -> (String, String) {
    let id = uuid::Uuid::new_v4().to_string();
    (id, "uuid-v4".to_string())
}

// ── 单元测试 ──────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extract_ioplatform_uuid_basic() {
        let text = r#"
        |   "IOPlatformUUID" = "550E8400-E29B-41D4-A716-446655440000"
        |   "IORegistryPlanes" = ...
        "#;
        assert_eq!(
            extract_ioplatform_uuid_impl(text).as_deref(),
            Some("550E8400-E29B-41D4-A716-446655440000")
        );
    }

    #[test]
    fn extract_ioplatform_uuid_missing() {
        let text = "no key here";
        assert_eq!(extract_ioplatform_uuid_impl(text), None);
    }

    #[test]
    fn extract_ioplatform_serial_basic() {
        // 真实 ioreg 输出片段: 序列号较短 (Mac 序列号 8-12 字符)
        let text = r#"
        |   "IOPlatformSerialNumber" = "C02XJ1ABJGH7"
        |   "IOPlatformUUID" = "550E8400-E29B-41D4-A716-446655440000"
        "#;
        assert_eq!(
            extract_ioplatform_serial_impl(text).as_deref(),
            Some("C02XJ1ABJGH7")
        );
    }

    #[test]
    fn extract_ioplatform_serial_missing() {
        let text = r#"
        |   "IOPlatformUUID" = "550E8400-E29B-41D4-A716-446655440000"
        "#;
        assert_eq!(extract_ioplatform_serial_impl(text), None);
    }

    #[test]
    fn extract_quoted_ioreg_field_min_len_enforced() {
        // 序列号 min_len=4: 3 字符应被拒
        let text = r#" "IOPlatformSerialNumber" = "ABC" "#;
        assert_eq!(extract_ioplatform_serial_impl(text), None);
        // 4 字符通过
        let text = r#" "IOPlatformSerialNumber" = "ABCD" "#;
        assert_eq!(extract_ioplatform_serial_impl(text).as_deref(), Some("ABCD"));
    }

    #[test]
    fn extract_quoted_ioreg_field_uuid_vs_serial_independent() {
        // 同时含 UUID 和 Serial: 两个提取器互不干扰
        let text = r#"
        |   "IOPlatformSerialNumber" = "C02XJ1ABJGH7"
        |   "IOPlatformUUID" = "550E8400-E29B-41D4-A716-446655440000"
        "#;
        assert_eq!(
            extract_ioplatform_uuid_impl(text).as_deref(),
            Some("550E8400-E29B-41D4-A716-446655440000")
        );
        assert_eq!(
            extract_ioplatform_serial_impl(text).as_deref(),
            Some("C02XJ1ABJGH7")
        );
    }

    #[test]
    fn extract_machine_id_line_basic() {
        let content = "abcdef0123456789abcdef0123456789\n";
        assert_eq!(
            extract_machine_id_line(content).as_deref(),
            Some("abcdef0123456789abcdef0123456789")
        );
    }

    #[test]
    fn extract_machine_id_line_with_spaces() {
        let content = "\n   abcdef0123456789abcdef0123456789   \n";
        assert_eq!(
            extract_machine_id_line(content).as_deref(),
            Some("abcdef0123456789abcdef0123456789")
        );
    }

    #[test]
    fn extract_machine_id_line_short_rejected() {
        let content = "tooshort\n";
        assert_eq!(extract_machine_id_line(content), None);
    }

    #[test]
    fn parse_cache_content_valid() {
        let content = "550E8400-E29B-41D4-A716-446655440000\ncim\n";
        let cached = parse_cache_content(content);
        assert_eq!(cached.unwrap().hardware_id, "550E8400-E29B-41D4-A716-446655440000");
    }

    #[test]
    fn parse_cache_content_too_short() {
        let content = "short\nunknown\n";
        assert!(parse_cache_content(content).is_none());
    }

    #[test]
    fn parse_cache_content_empty() {
        assert!(parse_cache_content("").is_none());
    }

    #[tokio::test]
    async fn fallback_uuid_works() {
        let (id, source) = fallback_uuid();
        assert_eq!(source, "uuid-v4");
        assert_eq!(id.len(), 36);
        assert!(uuid::Uuid::parse_str(&id).is_ok());
    }

    // ── OS version string formatting (format_windows_version / format_macos_version_string) ──

    #[test]
    fn format_macos_version_string_basic() {
        assert_eq!(format_macos_version_string("13.6"), "macOS 13.6");
        assert_eq!(format_macos_version_string("14.5"), "macOS 14.5");
        assert_eq!(format_macos_version_string("15.1"), "macOS 15.1");
    }

    #[test]
    fn format_macos_version_string_tahoe_26() {
        // macOS 26 (Tahoe, 2025) 引入新版号方案 (跳过 16..=25)。代码不做版号→
        // 营销名映射, 仅原样透传, 故 26.x 应原样输出。此测试固化该预期, 防止未来
        // 误加版号解析导致 Tahoe 机器 os_version 异常/闪退。
        assert_eq!(format_macos_version_string("26.0"), "macOS 26.0");
        assert_eq!(format_macos_version_string("26.1"), "macOS 26.1");
    }

    #[test]
    fn format_macos_version_string_empty_and_whitespace() {
        assert_eq!(format_macos_version_string(""), "macOS (unknown version)");
        assert_eq!(format_macos_version_string("   "), "macOS (unknown version)");
        // 带前后空白应被 trim
        assert_eq!(format_macos_version_string("  13.6\n"), "macOS 13.6");
    }

    #[test]
    fn format_windows_version_win11_23h2() {
        let s = format_windows_version("Windows 11 Pro", "23H2", "2009", "22631");
        assert_eq!(s.as_deref(), Some("Windows 11 Pro 23H2 (build 22631)"));
    }

    #[test]
    fn format_windows_version_win11_24h2() {
        let s = format_windows_version("Windows 11 Pro", "24H2", "2009", "26100");
        assert_eq!(s.as_deref(), Some("Windows 11 Pro 24H2 (build 26100)"));
    }

    #[test]
    fn format_windows_version_win10_22h2() {
        let s = format_windows_version("Windows 10 Pro", "22H2", "2009", "19045");
        assert_eq!(s.as_deref(), Some("Windows 10 Pro 22H2 (build 19045)"));
    }

    #[test]
    fn format_windows_version_display_version_fallback_to_release_id() {
        // Win10 <2004 无 DisplayVersion, 应回退到 ReleaseId (如 2009)
        let s = format_windows_version("Windows 10 Pro", "", "2009", "18363");
        assert_eq!(s.as_deref(), Some("Windows 10 Pro 2009 (build 18363)"));
    }

    #[test]
    fn format_windows_version_missing_build() {
        let s = format_windows_version("Windows 11 Pro", "23H2", "2009", "");
        assert_eq!(s.as_deref(), Some("Windows 11 Pro 23H2"));
    }

    #[test]
    fn format_windows_version_all_empty_returns_none() {
        assert_eq!(format_windows_version("", "", "", ""), None);
        // 仅空白也应视为空
        assert_eq!(format_windows_version("  ", " ", "", "\n"), None);
    }
}
