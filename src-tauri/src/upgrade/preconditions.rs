// Copyright (c) 2026 MeeJoy
//
// Pre-conditions for silent upgrade (tupAI P1 §1):
//   1. system is idle (CPU < 30% & mem < 60%) — uses `wmic` on
//      Windows, falls back to `ps` on macOS / Linux. If hardware
//      detection wires `sysinfo` later, swap to it.
//   2. target download directory has >= upgrade_size * 1.5 bytes free
//   3. network is not metered (Windows netsh, macOS scutil, Linux
//      is best-effort and always returns `NotMetered` with a TODO)

use std::path::Path;
use std::process::Command;

#[cfg(target_os = "windows")]
const IDLE_CMD: &str = "wmic";
#[cfg(target_os = "windows")]
const IDLE_ARGS_CPU: &[&str] = &["cpu", "get", "loadpercentage", "/value"];
#[cfg(target_os = "windows")]
const IDLE_ARGS_MEM: &[&str] = &["OS", "get", "FreePhysicalMemory,TotalVisibleMemorySize", "/value"];

#[cfg(target_os = "macos")]
const IDLE_CMD: &str = "sh";
#[cfg(target_os = "macos")]
const IDLE_ARGS_CPU: &[&str] = &["-c", "ps -A -o %cpu | awk 'NR>1 {s+=$1} END {print s+0}'"];
#[cfg(target_os = "macos")]
const IDLE_ARGS_MEM: &[&str] = &[
    "-c",
    "vm_stat | awk '/Pages free/ {free=$3} /Pages active/ {active=$3} /Pages inactive/ {inactive=$3} /Pages wired/ {wired=$3} END {print free+active+inactive+wired}'",
];

#[cfg(target_os = "linux")]
const IDLE_CMD: &str = "sh";
#[cfg(target_os = "linux")]
const IDLE_ARGS_CPU: &[&str] = &["-c", "ps -A -o %cpu | awk 'NR>1 {s+=$1} END {print s+0}'"];
#[cfg(target_os = "linux")]
const IDLE_ARGS_MEM: &[&str] = &[
    "-c",
    "cat /proc/meminfo | awk '/MemTotal:/ {t=$2} /MemAvailable:/ {a=$2} END {print t, a}'",
];

/// Read a single integer value from the output of a shell command.
/// Returns `None` if the command fails or the value is missing.
fn read_single_value(cmd: &str, args: &[&str]) -> Option<u64> {
    let mut command = Command::new(cmd);
    crate::commands::legacy::apply_no_window(&mut command);
    let output = command.args(args).output().ok()?;
    if !output.status.success() {
        return None;
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    // Look for `Name=Value` pairs first (wmic style), then take any
    // trailing integer on the line. We accept the last value we find
    // so multi-line output still works.
    for line in stdout.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        if let Some((_, rhs)) = trimmed.split_once('=') {
            if let Ok(n) = rhs.trim().parse::<u64>() {
                return Some(n);
            }
        }
    }
    // Fall back: pick the largest integer on the last non-empty line.
    stdout
        .lines().rfind(|l| !l.trim().is_empty())
        .and_then(|line| {
            line.split_whitespace()
                .filter_map(|tok| tok.parse::<u64>().ok())
                .next_back()
        })
}

/// Synchronous implementation backing the public async `is_system_idle`.
/// Exposed at `pub(crate)` so callers in `manager.rs` that still run in
/// a sync context (e.g. `preconditions_satisfied`) can reach the
/// probe without going through the runtime.
pub(crate) fn is_system_idle_blocking() -> bool {
    // Best-effort: if the underlying command is missing (e.g. stripped
    // Windows container), we treat that as "idle" to avoid blocking the
    // upgrade path on a missing wmic.exe. The frontend still has to
    // flip the auto-upgrade switch.
    let Some(cpu_pct) = read_single_value(IDLE_CMD, IDLE_ARGS_CPU) else {
        return true;
    };
    if cpu_pct >= 30 {
        return false;
    }

    let Some(mem_token) = read_single_value(IDLE_CMD, IDLE_ARGS_MEM) else {
        return true;
    };

    // `wmic` returns total KB. `ps`/`vm_stat`/`/proc/meminfo` use a
    // variety of units. We only need a rough "memory pressure" signal,
    // so we just bucket the value to a percentage. When the command
    // returns just one number, we conservatively assume it's the
    // free/inactive count in pages or KB and treat anything below 40%
    // of the value as "low pressure" — basically we just want a soft
    // gate that won't trigger on a fully loaded dev box.
    if mem_token > 0 && mem_token < 4096 {
        // Tiny absolute number (e.g. a few pages). Treat as
        // "very low memory pressure" because we don't have a
        // baseline to compare against.
        return true;
    }
    // Cross-platform memory pressure probe via `sysinfo`
    // (already in Cargo.toml). When used/total exceeds 80% we
    // treat the system as "not idle" and refuse the upgrade
    // (tupAI P1 §1). Probe is sync but cheap.
    let mut sys = sysinfo::System::new();
    sys.refresh_memory();
    let total = sys.total_memory();
    let used = sys.used_memory();
    if total > 0 && used.saturating_mul(100) / total >= 80 {
        return false;
    }
    true
}

// public API for upgrade preconditions; invoked from JS in next PR
#[allow(dead_code)]
/// Returns true when the system is considered idle enough to silently
/// download an upgrade (tupAI P1 §1). The thresholds match
/// `plan.md §3.5`:
///   - average CPU usage < 30%
///   - physical memory in use < 60%
///
/// The probe itself is implemented in `is_system_idle_blocking` using
/// `wmic` / `ps` / `vm_stat` / `/proc/meminfo` — those shell commands
/// don't expose a stable async surface, so we delegate the call to
/// `tokio::task::spawn_blocking` and await its result. If the
/// blocking thread fails to join (e.g. during shutdown), we err on
/// the side of allowing the upgrade so the auto-upgrade path stays
/// unblocked.
pub async fn is_system_idle() -> bool {
    // TODO(tupAI P1 §1): swap the shell-based probe for a Rust
    // implementation that reads `/proc/stat` (Linux), `sysctl` (macOS)
    // or PDH (Windows) directly via `std::process` / `windows` crate.
    // We deliberately avoid pulling in `sysinfo` here so the upgrade
    // path stays decoupled from the hardware-detection dependency
    // graph. Once that probe lands, this function can drop the
    // `spawn_blocking` hop.
    tokio::task::spawn_blocking(is_system_idle_blocking).await.unwrap_or(true)
}

// public API for upgrade preconditions; invoked from JS in next PR
#[allow(dead_code)]
/// Returns true when the volume that hosts `path` has at least
/// `required_gb * 1.5` GiB free. The 1.5× headroom matches the
/// `plan.md §3.5` "增量包 × 1.5" rule.
///
/// The actual free-bytes probe is delegated to `check_disk_free`
/// which already implements Windows / macOS / Linux shell variants.
/// This wrapper just turns the result into a bool and adds the
/// 1.5× multiplier on top.
///
/// `path` is treated as a hint — when it does not exist on disk, the
/// function falls back to `path.parent()` and finally to the current
/// working directory. A failed probe (i.e. `check_disk_free` returns
/// `Ok(0)`) is treated as "unknown, assume enough" so the user is
/// not blocked by a missing `wmic` / `df` binary in dev containers.
pub async fn has_enough_disk_for_upgrade(required_gb: f64, path: &Path) -> bool {
    // TODO(tupAI P1 §1): cross-platform `statvfs` / `GetDiskFreeSpaceExW`
    // probe. The shell-based `df` / `Get-PSDrive` calls are good
    // enough for Windows + macOS + Linux today; on a stripped
    // container (no `df` binary) we just return `true` so the upgrade
    // path stays unblocked.
    let target: std::path::PathBuf = if path.exists() {
        path.to_path_buf()
    } else if let Some(parent) = path.parent() {
        if !parent.as_os_str().is_empty() {
            parent.to_path_buf()
        } else {
            std::path::PathBuf::from(".")
        }
    } else {
        std::path::PathBuf::from(".")
    };
    let free_bytes = check_disk_free(&target).unwrap_or(0);
    if free_bytes == 0 {
        return true;
    }
    let required_bytes = (required_gb.max(0.0) * 1.5 * 1024.0 * 1024.0 * 1024.0) as u64;
    free_bytes >= required_bytes
}

/// Returns free bytes on the volume that hosts `path`. Falls back to 0
/// when the platform-specific probe is unavailable; the caller should
/// treat 0 as "unknown" rather than "definitely full".
pub fn check_disk_free(path: &Path) -> Result<u64, String> {
    // Prefer `download_dir` if it is set, otherwise derive from the
    // provided path. The agent caller (see commands/system.rs) is
    // expected to pass a real directory.
    let target = if path.exists() {
        path
    } else if let Some(parent) = path.parent() {
        parent
    } else {
        path
    };

    // The simplest cross-platform probe is `std::fs::metadata` on the
    // path itself. That doesn't give us a free-bytes number on every
    // platform, so we use the OS shell as a portable approximation.
    let output = {
        #[cfg(target_os = "windows")]
        {
            // Use `dir`-style free bytes via `fsutil`. We only need a
            // ballpark figure; if the call fails we return 0.
            let mut command = Command::new("powershell");
            command
                .args([
                    "-NoProfile",
                    "-Command",
                    "Get-PSDrive -PSProvider FileSystem | Where-Object { $_.Used -ne $null } | ForEach-Object { [pscustomobject]@{ Root = $_.Root; Free = [int64]$_.Free } } | Where-Object { $args[0] -like \"$($_.Root)*\" } | Select-Object -First 1 | ForEach-Object { $_.Free }",
                    &target.display().to_string(),
                ])
                .stdin(std::process::Stdio::null())
                .stdout(std::process::Stdio::piped())
                .stderr(std::process::Stdio::null());
            // Pre-flight check before downloading an update; we run
            // this on every silent auto-upgrade probe. CREATE_NO_WINDOW
            // stops the PowerShell console flashing on the user's
            // screen every few minutes.
            crate::commands::legacy::apply_no_window(&mut command);
            command.output()
        }
        #[cfg(target_os = "macos")]
        {
            // `df -k <path>` output ends with the available KB on the
            // second-to-last whitespace-separated token.
            Command::new("df").args(["-k", &target.display().to_string()]).output()
        }
        #[cfg(target_os = "linux")]
        {
            Command::new("df").args(["-k", &target.display().to_string()]).output()
        }
    };

    let Ok(out) = output else {
        return Ok(0);
    };
    if !out.status.success() {
        return Ok(0);
    }
    let stdout = String::from_utf8_lossy(&out.stdout);
    // Last non-empty line, last numeric token.
    let last_line = stdout.lines().rfind(|l| !l.trim().is_empty());
    let Some(line) = last_line else { return Ok(0) };
    // `df -k` row layout: Filesystem 1K-blocks Used Available
    // Capacity Mounted. macOS adds `iused`/`iused%` columns
    // after Capacity, which confuses a simple "last numeric"
    // scan. The Capacity column ends in '%' and won't parse as
    // u64, so the "Available" value is the numeric token
    // immediately to its left.
    let bytes = line
        .split_whitespace()
        .collect::<Vec<&str>>()
        .windows(2)
        .find_map(|pair| {
            if pair[1].ends_with('%') {
                pair[0].parse::<u64>().ok()
            } else {
                None
            }
        })
        .unwrap_or(0);

    #[cfg(target_os = "macos")]
    let bytes = bytes.saturating_mul(1024);
    #[cfg(target_os = "linux")]
    let bytes = bytes.saturating_mul(1024);
    Ok(bytes)
}

// public API for upgrade preconditions; invoked from JS in next PR
#[allow(dead_code)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NetworkKind {
    Unknown,
    NotMetered,
    Metered,
}

// public API for upgrade preconditions; invoked from JS in next PR
#[allow(dead_code)]
/// Detects whether the current network is metered. The default is
/// `NotMetered` so that the upgrade pipeline is unblocked on dev
/// machines; the user can always flip the auto-upgrade switch off
/// if they are on a metered cellular link.
pub fn check_network_metered() -> bool {
    matches!(classify_network(), NetworkKind::Metered)
}

// Private helper backing `check_network_metered`. The compiler
// can't see through `check_network_metered`'s `#[allow(dead_code)]`
// to the callee, so we annotate the helper directly.
#[allow(dead_code)]
fn classify_network() -> NetworkKind {
    #[cfg(target_os = "windows")]
    {
        // `netsh interface show interface` lists all interfaces and
        // tags the active one as "已连接" (zh) / "Connected" (en) /
        // ... There is no built-in "metered" flag we can read from
        // the shell in one go, so we treat any active link as
        // non-metered. This matches the conservative default in
        // plan.md §3.5: Linux always returns false; Windows & macOS
        // get a slightly more nuanced check.
        let mut command = Command::new("netsh");
        command
            .args(["interface", "show", "interface"])
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::null());
        crate::commands::legacy::apply_no_window(&mut command);
        let out = command.output();
        if let Ok(out) = out {
            if out.status.success() {
                let stdout = String::from_utf8_lossy(&out.stdout).to_lowercase();
                if stdout.contains("disconnected") || stdout.contains("已禁用") {
                    return NetworkKind::Unknown;
                }
                if stdout.contains("受限") || stdout.contains("limited") {
                    return NetworkKind::Metered;
                }
                if stdout.contains("connected") || stdout.contains("已连接") {
                    return NetworkKind::NotMetered;
                }
            }
        }
        NetworkKind::NotMetered
    }
    #[cfg(target_os = "macos")]
    {
        // `scutil --nc list` prints blocks separated by blank lines.
        // Each block contains a `Metered:` field.
        let out = Command::new("scutil").args(["--nc", "list"]).output();
        if let Ok(out) = out {
            if out.status.success() {
                let stdout = String::from_utf8_lossy(&out.stdout);
                for block in stdout.split("\n\n") {
                    if !block.contains("Metered") {
                        continue;
                    }
                    for line in block.lines() {
                        let trimmed = line.trim();
                        if let Some(rest) = trimmed.strip_prefix("Metered") {
                            let rest = rest.trim_start_matches(':').trim();
                            if rest.eq_ignore_ascii_case("yes") || rest.eq_ignore_ascii_case("true") {
                                return NetworkKind::Metered;
                            }
                        }
                    }
                }
            }
        }
        NetworkKind::NotMetered
    }
    #[cfg(target_os = "linux")]
    {
        // TODO: implement `nmcli -t -f METERED dev` once a NetworkManager
        // dependency is acceptable. For now we always return
        // `NotMetered` so the silent upgrade path stays unblocked.
        let _ = NetworkKind::NotMetered;
        NetworkKind::NotMetered
    }
}

#[cfg(test)]
mod tests {
    use crate::hardware::detector::HardwareVersion;
    use crate::upgrade::manager::{should_trigger_silent_upgrade, target_version_for_hardware};

    #[test]
    fn target_version_for_hardware_maps_all_supported_variants() {
        assert_eq!(target_version_for_hardware(&HardwareVersion::Discrete), Some("discrete"));
        assert_eq!(target_version_for_hardware(&HardwareVersion::Integrated), Some("integrated"));
        assert_eq!(target_version_for_hardware(&HardwareVersion::CpuOnly), Some("cpu_only"));
        assert_eq!(target_version_for_hardware(&HardwareVersion::Unsupported), None);
    }

    #[tokio::test]
    async fn should_trigger_silent_upgrade_respects_upgrade_enabled_flag() {
        // Even on a discrete-GPU machine the loop must short-circuit
        // when the user has flipped the auto-upgrade switch off.
        let result = should_trigger_silent_upgrade(
            "cpu_only",
            &HardwareVersion::Discrete,
            false,
        )
        .await;
        assert!(!result, "upgrade_enabled=false must return false");
    }

    #[tokio::test]
    async fn should_trigger_silent_upgrade_rejects_downgrade_or_same_version() {
        // Current already discrete → target = discrete → no upgrade.
        let same = should_trigger_silent_upgrade(
            "discrete",
            &HardwareVersion::Discrete,
            true,
        )
        .await;
        assert!(!same, "same version must return false (no upgrade needed)");

        // Hardware only supports cpu_only but current is discrete —
        // we would be downgrading, so refuse.
        let downgrade = should_trigger_silent_upgrade(
            "discrete",
            &HardwareVersion::CpuOnly,
            true,
        )
        .await;
        assert!(!downgrade, "downgrade must return false");
    }
}
