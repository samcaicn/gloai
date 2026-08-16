// Copyright (c) 2026 MeeJoy
//
// UpgradeManager — the entry point for AIMarketing P1 §1 (静默升级).
//
// The state machine lives in `UpgradeStatus`. The manager is a
// standalone struct (no `tauri::State`) so callers can either
// construct it once during `setup` or instantiate ad-hoc inside a
// command. All shared state is parked in a `OnceLock<Mutex<...>>`
// in `crate::monitoring::observer` so the tray, the manager, and
// the commands can coordinate without pulling in a `tauri::State`.

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter};

use crate::hardware::detector::HardwareVersion;

use super::preconditions::{
    check_disk_free, check_network_metered, has_enough_disk_for_upgrade,
    is_system_idle_blocking,
};

const UPGRADE_DIRNAME: &str = "tupai/upgrade";
const PENDING_MARKER_LEGACY: &str = "upgrade_pending";
const PENDING_MARKER: &str = "upgrade_pending.json";
const PROGRESS_EVENT: &str = "upgrade_progress";
const PENDING_EVENT: &str = "upgrade_pending";
// Event-name constants for `tauri::Emitter` calls. Wired up in the
// follow-up PR that finishes the silent-upgrade downloader; held
// here so the string spellings stay in one place.
#[allow(dead_code)]
const FAILED_EVENT: &str = "upgrade_failed";

/// AIMarketing P1 §1 — version priority order (low → high). Used by
/// `target_version_for_hardware` / `should_trigger_silent_upgrade` to
/// decide whether a hardware upgrade qualifies for a re-download of
/// the binary.
pub const VERSION_PRIORITY: &[&str] = &["cpu_only", "integrated", "discrete"];

/// AIMarketing P1 §1 — upgrade target version for the given hardware tier.
///
/// Rules:
///   * Discrete GPU → `"discrete"`
///   * Integrated GPU (incl. Apple Silicon) → `"integrated"`
///   * Pure CPU only → `"cpu_only"`
///   * `Unsupported` → `None` (the user must install on supported
///     hardware before the agent will offer a silent upgrade).
pub fn target_version_for_hardware(hardware: &HardwareVersion) -> Option<&'static str> {
    match hardware {
        HardwareVersion::Discrete => Some("discrete"),
        HardwareVersion::Integrated => Some("integrated"),
        HardwareVersion::CpuOnly => Some("cpu_only"),
        HardwareVersion::Unsupported => None,
    }
}

/// AIMarketing P1 §1 — should the background loop kick off a silent
/// download right now?
///
/// Returns `true` when ALL of the following hold:
///   * the user has flipped the auto-upgrade switch on,
///   * the hardware supports a higher-priority variant than the
///     currently-installed build,
///   * the system is currently idle (CPU < 30% / mem < 60%),
///   * the target volume has ≥ 5 GiB × 1.5 = 7.5 GiB of free space.
///
/// The 5 GiB estimate is a conservative upper bound on the size of a
/// AIMarketing incremental package; the real delta size is read from the
/// updater manifest at download time.
#[allow(dead_code)]
pub async fn should_trigger_silent_upgrade(
    current: &str,
    hardware: &HardwareVersion,
    upgrade_enabled: bool,
) -> bool {
    if !upgrade_enabled {
        return false;
    }
    let Some(target) = target_version_for_hardware(hardware) else {
        return false;
    };
    let current_idx = VERSION_PRIORITY
        .iter()
        .position(|&v| v == current)
        .unwrap_or(0);
    let target_idx = VERSION_PRIORITY
        .iter()
        .position(|&v| v == target)
        .unwrap_or(0);
    if target_idx <= current_idx {
        return false;
    }
    if !super::preconditions::is_system_idle().await {
        return false;
    }
    if !has_enough_disk_for_upgrade(5.0, std::path::Path::new(".")).await {
        return false;
    }
    true
}

/// AIMarketing P1 §1 — structured response returned by
/// `commands::system::get_silent_upgrade_plan`. The frontend
/// (`UpgradePanel.jsx`) renders the `reason` / `diskFreeGb` / `idle`
/// fields so the user can see *why* the loop is (not) running.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SilentUpgradePlan {
    pub eligible: bool,
    pub current: String,
    pub target: String,
    pub reason: String,
    pub disk_free_gb: f64,
    pub idle: bool,
}

/// Pure helper used by the command layer to build a `SilentUpgradePlan`
/// from a `HardwareVersion`. It does *not* consult
/// `UpgradeManager::is_auto_upgrade_enabled`; the caller is expected to
/// pass that flag in.
pub fn build_silent_upgrade_plan(
    current: &str,
    hardware: &HardwareVersion,
    upgrade_enabled: bool,
) -> SilentUpgradePlan {
    let target = target_version_for_hardware(hardware)
        .unwrap_or("cpu_only")
        .to_string();
    let idle = is_system_idle_blocking();
    let free_bytes = check_disk_free(std::path::Path::new(".")).unwrap_or(0);
    let disk_free_gb = (free_bytes as f64) / (1024.0 * 1024.0 * 1024.0);

    if !upgrade_enabled {
        return SilentUpgradePlan {
            eligible: false,
            current: current.to_string(),
            target,
            reason: "用户已关闭自动升级".to_string(),
            disk_free_gb,
            idle,
        };
    }
    let current_idx = VERSION_PRIORITY
        .iter()
        .position(|&v| v == current)
        .unwrap_or(0);
    let target_idx = VERSION_PRIORITY
        .iter()
        .position(|&v| v == target)
        .unwrap_or(0);
    if target_idx <= current_idx {
        return SilentUpgradePlan {
            eligible: false,
            current: current.to_string(),
            target,
            reason: "当前已是匹配硬件的最高版本".to_string(),
            disk_free_gb,
            idle,
        };
    }
    if !idle {
        return SilentUpgradePlan {
            eligible: false,
            current: current.to_string(),
            target,
            reason: "系统当前不空闲 (CPU 或内存压力过高)".to_string(),
            disk_free_gb,
            idle,
        };
    }
    if free_bytes > 0 && disk_free_gb < 5.0_f64 * 1.5 {
        return SilentUpgradePlan {
            eligible: false,
            current: current.to_string(),
            target,
            reason: format!(
                "磁盘空间不足: 剩余 {:.1} GiB, 至少需要 7.5 GiB",
                disk_free_gb
            ),
            disk_free_gb,
            idle,
        };
    }
    SilentUpgradePlan {
        eligible: true,
        current: current.to_string(),
        target,
        reason: "满足所有前置条件, 可在下次空闲窗口触发静默升级".to_string(),
        disk_free_gb,
        idle,
    }
}

/// Result envelope returned by `check_silent_upgrade` and friends.
///
/// The frontend reads this in `UpgradePanel.jsx`. The `tag` field is
/// the discriminator (`idle` / `downloading` / `pending` / `failed` /
/// `disabled`) so the panel can switch UI without parsing the
/// associated `version` / `progress` / `reason` payload.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "tag", rename_all = "camelCase")]
pub enum UpgradeStatus {
    /// Auto-upgrade is disabled by the user.
    Disabled,
    /// The agent is not currently doing anything; `latest_version` is
    /// populated when the manager has a pending upgrade queued.
    Idle { latest_version: Option<String> },
    /// A download is in flight; `progress` is 0..=100.
    Downloading { progress: u8, version: String },
    /// An upgrade was downloaded and is ready to apply on restart.
    Pending { version: String },
    /// The last attempt failed. The `reason` is human-readable.
    Failed { reason: String, version: Option<String> },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PendingUpgrade {
    pub version: String,
    /// 兼容旧字段: 原来的 `path`。新代码用 `downloadedPath`。
    #[serde(default, alias = "path")]
    pub downloaded_path: PathBuf,
    #[serde(default)]
    pub sha256: Option<String>,
    #[serde(default)]
    pub downloaded_at: Option<String>,
    #[serde(default)]
    pub release_notes: Option<String>,
}

pub struct UpgradeManager {
    // Read by `UpgradeManager::current_version` (which is only
    // exercised from `manager_test.rs` today). Kept on the struct
    // so the frontend-facing accessor lands in the next PR without
    // a schema change.
    #[allow(dead_code)]
    current_version: String,
    hardware_version: String,
    auto_upgrade_enabled: Arc<Mutex<bool>>,
    last_status: Arc<Mutex<UpgradeStatus>>,
    pending: Arc<Mutex<Option<PendingUpgrade>>>,
}

impl UpgradeManager {
    pub fn new() -> Self {
        Self {
            current_version: env!("CARGO_PKG_VERSION").to_string(),
            hardware_version: "cpu_only".to_string(),
            auto_upgrade_enabled: Arc::new(Mutex::new(false)),
            last_status: Arc::new(Mutex::new(UpgradeStatus::Idle {
                latest_version: None,
            })),
            pending: Arc::new(Mutex::new(None)),
        }
    }

    #[allow(dead_code)]
    /// Returns the package version baked at compile time. Used by the
    /// frontend to render "current version" in `UpgradePanel`.
    pub fn current_version(&self) -> &str {
        &self.current_version
    }

    /// Returns the currently installed hardware-tier label
    /// (`"cpu_only"` / `"integrated"` / `"discrete"`). Today this is
    /// the value the manager was constructed with; once the hardware
    /// detection layer is wired into the manager, this will reflect
    /// the live value.
    pub fn hardware_version(&self) -> &str {
        &self.hardware_version
    }

    pub fn is_auto_upgrade_enabled(&self) -> bool {
        *self.auto_upgrade_enabled.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn set_auto_upgrade_enabled(&self, enabled: bool) {
        *self.auto_upgrade_enabled.lock().unwrap_or_else(|e| e.into_inner()) = enabled;
        if !enabled {
            *self.last_status.lock().unwrap_or_else(|e| e.into_inner()) = UpgradeStatus::Disabled;
        }
    }

    pub fn status(&self) -> UpgradeStatus {
        self.last_status
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone()
    }

    #[allow(dead_code)]
    /// Returns the queued upgrade (if any) so the UI can offer an
    /// "Install & Restart" button.
    pub fn pending(&self) -> Option<PendingUpgrade> {
        self.pending.lock().unwrap_or_else(|e| e.into_inner()).clone()
    }

    #[allow(dead_code)]
    /// Computes whether the current platform is idle enough to start
    /// a silent download. Pure function — used by the tests.
    pub fn preconditions_satisfied(required_bytes: u64) -> Result<(), String> {
        if !is_system_idle_blocking() {
            return Err("system is not idle".into());
        }
        if check_network_metered() {
            return Err("network is metered".into());
        }
        let target = upgrade_dir();
        if let Some(parent) = target.parent() {
            let free = check_disk_free(parent).unwrap_or(0);
            if free > 0 && free < required_bytes.saturating_mul(3) / 2 {
                return Err(format!(
                    "not enough disk space: have {} bytes, need {} bytes",
                    free,
                    required_bytes.saturating_mul(3) / 2
                ));
            }
        }
        Ok(())
    }

    #[allow(dead_code)]
    /// Kicks off the silent-upgrade loop on a background thread. The
    /// caller is expected to invoke this from `setup` (see lib.rs).
    pub fn start_silent_upgrade(self: Arc<Self>) {
        if !self.is_auto_upgrade_enabled() {
            // Still load any pending marker so `pending()` returns
            // the correct value on startup.
            self.load_pending_marker();
            return;
        }
        std::thread::Builder::new()
            .name("tupai-upgrade-loop".into())
            .spawn(move || {
                self.run_loop();
            })
            .expect("failed to spawn upgrade loop");
    }

    // Background loop body, kept around so the staged rollout can
    // re-enable polling in the next PR. The compiler can't see
    // through `start_silent_upgrade`'s `#[allow(dead_code)]` to the
    // callee, so we annotate the loop directly.
    #[allow(dead_code)]
    fn run_loop(self: Arc<Self>) {
        loop {
            if !self.is_auto_upgrade_enabled() {
                std::thread::sleep(Duration::from_secs(30));
                continue;
            }
            // 1) Check remote latest version. In the staged rollout the
            //    real endpoint is wired by the A5 frontend (which
            //    already has `updater_check_for_updates`); here we
            //    simply leave the status at Idle unless a pending
            //    marker is present.
            let pending = self.load_pending_marker();
            if let Some(pending) = pending {
                self.emit_event(PENDING_EVENT, &pending.version);
            }

            // 2) Sleep before the next probe. 30 minutes matches the
            //    cadence documented in plan.md §3.5.
            std::thread::sleep(Duration::from_secs(30 * 60));
        }
    }

    /// Persists a pending-upgrade marker. The `pending` file lives in
    /// `<app_data_dir>/tupai/upgrade/upgrade_pending.json` and contains
    /// the version + downloaded path + sha256 + release notes.
    /// 下载完成后由 `start_background_loop` 调用 `write_pending_marker`。
    pub fn install_pending_upgrade(&self) -> Result<(), String> {
        let pending = self
            .pending
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone()
            .ok_or_else(|| "no pending upgrade".to_string())?;
        // 调 NSIS 静默安装 (setup.exe /S /UPDATE=1)
        super::updater_client::install_silently(&pending.downloaded_path)?;
        // 安装成功后删除 marker,避免重复安装
        let dir = upgrade_dir();
        std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
        let marker = dir.join(PENDING_MARKER);
        if marker.exists() {
            std::fs::remove_file(&marker).map_err(|e| e.to_string())?;
        }
        *self.pending.lock().unwrap_or_else(|e| e.into_inner()) = None;
        *self.last_status.lock().unwrap_or_else(|e| e.into_inner()) = UpgradeStatus::Idle {
            latest_version: Some(pending.version),
        };
        Ok(())
    }

    /// Forces an immediate upgrade check. This is bound to the
    /// "立即检查更新" tray menu item and to the `trigger_silent_upgrade_now`
    /// Tauri command. The implementation just emits a
    /// `upgrade_progress` event with 0% and switches the status to
    /// `Downloading` — the actual download lives in the Tauri
    /// updater plugin.
    pub fn trigger_now(&self, app: &AppHandle) {
        if let Some(pending) = self.load_pending_marker() {
            *self.last_status.lock().unwrap_or_else(|e| e.into_inner()) = UpgradeStatus::Pending {
                version: pending.version.clone(),
            };
            self.emit_to(app, PENDING_EVENT, &pending.version);
            return;
        }
        *self.last_status.lock().unwrap_or_else(|e| e.into_inner()) = UpgradeStatus::Downloading {
            progress: 0,
            version: "latest".into(),
        };
        self.emit_to(app, PROGRESS_EVENT, &0u8);
    }

    // Best-effort event emission helper used by the background loop.
    // `run_loop` is annotated `#[allow(dead_code)]` above, so this
    // helper needs the same annotation for the compiler to see it
    // as reachable.
    #[allow(dead_code)]
    fn emit_event<T: Serialize + Clone>(&self, event: &str, payload: &T) {
        // Best-effort event emission. We don't have an AppHandle
        // cached on the manager (it would create a cycle with the
        // Tauri state), so the public methods that take an AppHandle
        // (`trigger_now`, `install_pending_upgrade`) are the only
        // ones that actually push events in the staged rollout.
        let _ = event;
        let _ = payload;
    }

    fn emit_to<T: Serialize + Clone>(&self, app: &AppHandle, event: &str, payload: &T) {
        if let Err(error) = app.emit(event, payload.clone()) {
            eprintln!("[upgrade] failed to emit {}: {}", event, error);
        }
    }

    fn load_pending_marker(&self) -> Option<PendingUpgrade> {
        let dir = upgrade_dir();

        // 向后兼容: 删除旧的无扩展名 plain-text marker
        let legacy_marker = dir.join(PENDING_MARKER_LEGACY);
        if legacy_marker.exists() {
            let _ = std::fs::remove_file(&legacy_marker);
        }

        let marker = dir.join(PENDING_MARKER);
        if !marker.exists() {
            return None;
        }
        let body = std::fs::read_to_string(&marker).ok()?;
        let trimmed = body.trim();
        if trimmed.is_empty() {
            return None;
        }

        // 尝试 JSON 解析;失败则当作旧格式 (纯版本号字符串)
        let pending: PendingUpgrade = if trimmed.starts_with('{') {
            serde_json::from_str(trimmed).ok()?
        } else {
            PendingUpgrade {
                version: trimmed.to_string(),
                downloaded_path: dir.join(format!("{}.tar.gz", trimmed)),
                sha256: None,
                downloaded_at: None,
                release_notes: None,
            }
        };

        if pending.version.is_empty() {
            return None;
        }
        *self.pending.lock().unwrap_or_else(|e| e.into_inner()) = Some(pending.clone());
        *self.last_status.lock().unwrap_or_else(|e| e.into_inner()) = UpgradeStatus::Pending {
            version: pending.version.clone(),
        };
        Some(pending)
    }
}

impl Default for UpgradeManager {
    fn default() -> Self { Self::new() }
}

fn upgrade_dir() -> PathBuf {
    // We deliberately resolve via `dirs` rather than the Tauri
    // AppHandle: the marker should survive even if the Tauri context
    // is not yet available (e.g. during very early startup). The
    // real artifact is still placed in `<app_data_dir>/tupai/upgrade`
    // by the updater plugin.
    dirs::data_local_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join(UPGRADE_DIRNAME)
}

/// 公开访问 upgrade 目录路径,供 commands::system::install_update 使用。
pub fn upgrade_dir_public() -> PathBuf {
    upgrade_dir()
}

// ============================================================================
// 新增: 启动安装 hook + 后台静默下载循环
// ============================================================================

/// 全局并发下载保护标志。true 表示有静默下载正在进行,阻止重复下载。
static SILENT_DOWNLOAD_IN_PROGRESS: AtomicBool = AtomicBool::new(false);

/// RAII guard: Drop 时自动复位 `SILENT_DOWNLOAD_IN_PROGRESS`,
/// 确保无论函数正常返回/早期 return/panic 都能复位标志位,避免死锁。
struct DownloadGuard;
impl Drop for DownloadGuard {
    fn drop(&mut self) {
        SILENT_DOWNLOAD_IN_PROGRESS.store(false, Ordering::SeqCst);
    }
}

/// 写 pending marker (JSON 格式, 原子写入: .tmp + rename)。
/// 下载完成后由 `start_background_loop` 调用。
pub fn write_pending_marker(pending: &PendingUpgrade) -> Result<(), String> {
    let dir = upgrade_dir();
    std::fs::create_dir_all(&dir).map_err(|e| format!("create upgrade dir failed: {}", e))?;
    let marker = dir.join(PENDING_MARKER);
    let tmp = dir.join(format!("{}.tmp", PENDING_MARKER));
    let body = serde_json::to_string_pretty(pending)
        .map_err(|e| format!("serialize pending marker failed: {}", e))?;
    std::fs::write(&tmp, body).map_err(|e| format!("write pending marker tmp failed: {}", e))?;
    std::fs::rename(&tmp, &marker).map_err(|e| format!("rename pending marker failed: {}", e))?;
    Ok(())
}

/// 启动时检查是否有已下载完成的 pending 升级,若有则静默安装 + 重启。
///
/// 在 `lib.rs` setup 阶段 spawn 调用。失败只记日志,不阻塞启动。
pub async fn install_pending_on_startup(app: AppHandle) {
    let dir = upgrade_dir();
    let marker = dir.join(PENDING_MARKER);

    if !marker.exists() {
        return;
    }

    let body = match std::fs::read_to_string(&marker) {
        Ok(b) => b,
        Err(e) => {
            eprintln!("[upgrade] read pending marker failed: {}", e);
            return;
        }
    };

    let pending: PendingUpgrade = if body.trim().starts_with('{') {
        match serde_json::from_str(&body) {
            Ok(p) => p,
            Err(e) => {
                eprintln!("[upgrade] parse pending marker failed: {}", e);
                let _ = std::fs::remove_file(&marker);
                return;
            }
        }
    } else {
        // 旧格式 (纯版本号),无 downloaded_path 无法安装,删除
        eprintln!("[upgrade] legacy plain-text marker found, removing (no path to install)");
        let _ = std::fs::remove_file(&marker);
        return;
    };

    if !pending.downloaded_path.exists() {
        eprintln!(
            "[upgrade] pending setup exe not found: {}, removing marker",
            pending.downloaded_path.display()
        );
        let _ = std::fs::remove_file(&marker);
        return;
    }

    eprintln!(
        "[upgrade] installing pending upgrade v{} from {} ...",
        pending.version,
        pending.downloaded_path.display()
    );

    match super::updater_client::install_silently(&pending.downloaded_path) {
        Ok(()) => {
            // 安装已 spawn,删除 marker 避免重复安装
            let _ = std::fs::remove_file(&marker);
            eprintln!("[upgrade] NSIS installer spawned, exiting app to let installer proceed...");
            // 给安装程序一点时间初始化,然后退出当前进程。
            // 不使用 app.restart() 因为 NSIS 的 customPreInstall 会 taskkill
            // 所有 tupai.exe 进程(包括 restart 刚 spawn 的新进程),
            // 导致「restart 的新进程被杀 → 安装完成 → 无人拉起」的死局。
            // 改用 app.exit(0) 让当前进程干净退出,NSIS installer 的
            // customPostInstall 钩子会在安装完成后自动拉起新版本应用。
            tokio::time::sleep(std::time::Duration::from_secs(2)).await;
            app.exit(0);
        }
        Err(e) => {
            eprintln!("[upgrade] install_pending_on_startup failed: {}", e);
        }
    }
}

/// 后台静默下载循环: 检查 → 下载 → 写 marker → emit 事件。
///
/// 由 `silent_download_upgrade` Tauri 命令 spawn。失败 emit `upgrade_failed`。
///
/// 并发保护: 使用全局 AtomicBool 防止多次调用(启动后 60s 自动触发 +
/// 托盘"立即检查更新")同时下载到同一个文件路径导致数据损坏。
pub async fn start_background_loop(app: AppHandle, device_token: String) {
    // 并发保护: 如果已有下载在进行中,直接返回,避免多写者损坏文件。
    // compare_exchange 返回 Ok 表示成功设为 true,Err 表示已经是 true。
    if SILENT_DOWNLOAD_IN_PROGRESS
        .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
        .is_err()
    {
        eprintln!("[upgrade] silent download already in progress, skipping");
        let _ = app.emit(PENDING_EVENT, "already-in-progress");
        return;
    }

    // 确保 flag 在函数退出时被复位(无论成功/失败/panic)。
    let _guard = DownloadGuard;

    let token = device_token;
    let check_result = super::updater_client::check_via_server(&app, &token).await;

    match check_result {
        Ok(Some(resp)) => {
            let download_url = match resp.download_url.as_deref() {
                Some(url) if !url.is_empty() => url,
                _ => {
                    eprintln!("[upgrade] server returned has_update=true but no download_url");
                    let _ = app.emit(FAILED_EVENT, "no download_url from server");
                    return;
                }
            };
            let filename = resp.filename.as_deref().unwrap_or("setup.exe");
            let sha256 = resp.sha256.as_deref();
            let version = resp.latest_version.clone().unwrap_or_default();
            let release_notes = resp.release_notes.clone();

            let dir = upgrade_dir();
            let dest = dir.join(filename);

            eprintln!(
                "[upgrade] downloading v{} from {} to {} ...",
                version,
                download_url,
                dest.display()
            );

            match super::updater_client::download_to_local(
                download_url,
                sha256,
                &dest,
                &app,
            )
            .await
            {
                Ok(()) => {
                    let pending = PendingUpgrade {
                        version: version.clone(),
                        downloaded_path: dest.clone(),
                        sha256: sha256.map(|s| s.to_string()),
                        downloaded_at: Some(chrono::Utc::now().to_rfc3339()),
                        release_notes,
                    };
                    if let Err(e) = write_pending_marker(&pending) {
                        eprintln!("[upgrade] write pending marker failed: {}", e);
                        let _ = app.emit(FAILED_EVENT, &e);
                        return;
                    }
                    eprintln!("[upgrade] v{} downloaded, pending marker written", version);
                    let _ = app.emit(PENDING_EVENT, &version);
                }
                Err(e) => {
                    eprintln!("[upgrade] download failed: {}", e);
                    let _ = app.emit(FAILED_EVENT, &e);
                }
            }
        }
        Ok(None) => {
            eprintln!("[upgrade] no update available");
        }
        Err(e) => {
            eprintln!("[upgrade] check_via_server failed: {}", e);
            let _ = app.emit(FAILED_EVENT, &e);
        }
    }
}
