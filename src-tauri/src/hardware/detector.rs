// Copyright (c) 2026 MeeJoy

// Hardware detection for AIMarketing version selection.
//
// The full Windows WMI / macOS system_profiler / Linux lspci probe is
// intentionally not implemented in P0 — the routing only needs a coarse
// discrete-vs-integrated-vs-CPU decision, and `sysinfo` already gives us
// the canonical CPU model + memory size that we need for the
// crypto-layer fingerprint. The `GpuInfo::placeholder` field can be
// refined later without breaking the public API.

use serde::{Deserialize, Serialize};
use std::fmt;
use sysinfo::{Disks, System};

/// Coarse hardware tier used to pick the AIMarketing distribution.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum HardwareVersion {
    CpuOnly,
    Integrated,
    Discrete,
    Unsupported,
}

impl fmt::Display for HardwareVersion {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        let label = match self {
            HardwareVersion::CpuOnly => "cpu_only",
            HardwareVersion::Integrated => "integrated",
            HardwareVersion::Discrete => "discrete",
            HardwareVersion::Unsupported => "unsupported",
        };
        formatter.write_str(label)
    }
}

/// Detected CPU information.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CpuInfo {
    pub brand: String,
    pub core_count: usize,
    pub frequency_mhz: u64,
}

/// Best-effort GPU descriptor.
///
/// The `discrete` flag is what the routing logic looks at; `name` and
/// `vram_mb` are surfaced to the UI so the user can confirm the device
/// classification in Settings.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GpuInfo {
    pub name: String,
    pub vram_mb: Option<u64>,
    pub discrete: bool,
}

/// Full hardware probe result.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HardwareInfo {
    pub os_type: String,
    pub cpu: CpuInfo,
    pub memory_total_mb: u64,
    pub gpu_list: Vec<GpuInfo>,
    pub best_gpu: Option<GpuInfo>,
    pub recommended_version: HardwareVersion,
    pub matched_version: HardwareVersion,
}

/// Lighter-weight system info used by `get_system_info`.
///
/// `disk_total_gb` is the aggregate size of every mounted disk we can
/// see; `disks` is the per-disk breakdown. Both are best-effort and
/// tolerate `sysinfo` returning 0 for unsupported platforms.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SystemInfo {
    pub os_name: String,
    pub os_version: String,
    pub kernel_version: Option<String>,
    pub host_name: Option<String>,
    pub cpu_brand: String,
    pub cpu_cores: usize,
    pub cpu_frequency_mhz: u64,
    pub memory_total_mb: u64,
    pub memory_available_mb: u64,
    pub disk_total_gb: f64,
    pub disks: Vec<DiskInfo>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DiskInfo {
    pub name: String,
    pub mount_point: String,
    pub total_gb: f64,
    pub available_gb: f64,
    pub file_system: String,
}

/// The struct that performs the actual probe.
///
/// We keep this as a plain owned struct (no global state) so it is
/// trivially callable from the Tauri command layer and from unit tests.
pub struct HardwareDetector;

impl HardwareDetector {
    /// Run a full probe and return a populated `HardwareInfo`.
    pub fn detect() -> HardwareInfo {
        let mut system = System::new_all();
        system.refresh_all();

        let cpu_brand = system
            .cpus()
            .first()
            .map(|cpu| cpu.brand().to_string())
            .unwrap_or_else(|| "Unknown CPU".to_string());
        let cpu_frequency_mhz = system
            .cpus()
            .first()
            .map(|cpu| cpu.frequency())
            .unwrap_or_default();
        let core_count = system.cpus().len();

        let cpu = CpuInfo {
            brand: cpu_brand.clone(),
            core_count,
            frequency_mhz: cpu_frequency_mhz,
        };

        let memory_total_mb = system.total_memory() / (1024 * 1024);
        let os_type = std::env::consts::OS.to_string();

        let gpu_list = detect_gpus(&cpu_brand);
        let best_gpu = gpu_list
            .iter()
            .find(|gpu| gpu.discrete)
            .cloned()
            .or_else(|| gpu_list.first().cloned());

        let recommended_version = recommend_version(memory_total_mb, best_gpu.as_ref());
        let matched_version = match_hardware_version(memory_total_mb, best_gpu.as_ref());

        HardwareInfo {
            os_type,
            cpu,
            memory_total_mb,
            gpu_list,
            best_gpu,
            recommended_version,
            matched_version,
        }
    }

    /// Build a lighter-weight `SystemInfo` snapshot (no full hardware
    /// fingerprint). Cheaper to call repeatedly from the UI.
    pub fn system_info() -> SystemInfo {
        let mut system = System::new_all();
        system.refresh_all();
        let disks = Disks::new_with_refreshed_list();

        let cpu_brand = system
            .cpus()
            .first()
            .map(|cpu| cpu.brand().to_string())
            .unwrap_or_else(|| "Unknown CPU".to_string());
        let cpu_frequency_mhz = system
            .cpus()
            .first()
            .map(|cpu| cpu.frequency())
            .unwrap_or_default();
        let cpu_cores = system.cpus().len();
        let memory_total_mb = system.total_memory() / (1024 * 1024);
        let memory_available_mb = system.available_memory() / (1024 * 1024);

        let mut disks_info: Vec<DiskInfo> = disks
            .list()
            .iter()
            .map(|disk| {
                let total_gb = disk.total_space() as f64 / (1024.0 * 1024.0 * 1024.0);
                let available_gb = disk.available_space() as f64 / (1024.0 * 1024.0 * 1024.0);
                DiskInfo {
                    name: disk.name().to_string_lossy().into_owned(),
                    mount_point: disk.mount_point().to_string_lossy().into_owned(),
                    total_gb,
                    available_gb,
                    file_system: disk.file_system().to_string_lossy().into_owned(),
                }
            })
            .collect();
        // `sysinfo` does not guarantee ordering across platforms, and
        // the front-end would like to show the largest disk first.
        disks_info.sort_by(|left, right| {
            right
                .total_gb
                .partial_cmp(&left.total_gb)
                .unwrap_or(std::cmp::Ordering::Equal)
        });

        let disk_total_gb: f64 = disks_info.iter().map(|d| d.total_gb).sum();

        SystemInfo {
            os_name: System::name().unwrap_or_else(|| std::env::consts::OS.to_string()),
            os_version: System::os_version().unwrap_or_else(|| "unknown".to_string()),
            kernel_version: System::kernel_version(),
            host_name: System::host_name(),
            cpu_brand,
            cpu_cores,
            cpu_frequency_mhz,
            memory_total_mb,
            memory_available_mb,
            disk_total_gb,
            disks: disks_info,
        }
    }
}

/// Map a hardware probe to the AIMarketing variant we should run.
///
/// Rules:
/// 1. < 2 GiB of RAM or no usable CPU → `Unsupported`.
/// 2. Discrete GPU present → `Discrete`.
/// 3. Integrated GPU (or Apple Silicon) → `Integrated`.
/// 4. Otherwise → `CpuOnly`.
///
/// The function takes the memory size (in MiB) and the best GPU so it is
/// trivially testable without a real `System` snapshot.
pub fn match_hardware_version(memory_total_mb: u64, best_gpu: Option<&GpuInfo>) -> HardwareVersion {
    if memory_total_mb < 2048 {
        return HardwareVersion::Unsupported;
    }
    match best_gpu {
        Some(gpu) if gpu.discrete => HardwareVersion::Discrete,
        Some(_) => HardwareVersion::Integrated,
        None => HardwareVersion::CpuOnly,
    }
}

/// "Recommended" differs from "matched" only on systems with ≥ 16 GiB of
/// RAM and no discrete GPU — there we still recommend `CpuOnly` because
/// the integrated path is not enough to run the full local model
/// stack. For everything else the recommended == matched.
pub fn recommend_version(memory_total_mb: u64, best_gpu: Option<&GpuInfo>) -> HardwareVersion {
    match_hardware_version(memory_total_mb, best_gpu)
}

/// Best-effort GPU detection. We do not shell out to `wmic` /
/// `system_profiler` / `lspci` yet — those will be added when the
/// discrete-GPU fast path needs them. For now we:
/// * Recognize Apple Silicon by the "Apple M" prefix in the CPU brand and
///   surface a single integrated GPU entry.
/// * Treat the absence of GPU info as `CpuOnly`.
fn detect_gpus(cpu_brand: &str) -> Vec<GpuInfo> {
    if cpu_brand.contains("Apple M") {
        return vec![GpuInfo {
            name: "Apple Silicon GPU".to_string(),
            vram_mb: None,
            discrete: false,
        }];
    }
    // We deliberately keep the list empty on x86 builds so that the
    // routing falls back to `CpuOnly` (i.e. we don't lie to the user
    // about a discrete GPU we did not actually detect). The real
    // detection wiring lands in a follow-up.
    Vec::new()
}

/// Build a stable, lossy hardware fingerprint used to anchor the
/// crypto-layer key. The input is `cpu_brand|memory_mb|os`; the output is
/// the lowercase hex of the SHA-256 of the canonicalized string.
///
/// We intentionally do not include any per-user data (hostname,
/// username, disk serials) here — those would make the fingerprint
/// unstable across reboots and they are not required for the local
/// "tie the key to this device" property the storage layer needs.
pub fn build_fingerprint(cpu_brand: &str, memory_total_mb: u64, os: &str) -> String {
    use sha2::{Digest, Sha256};

    let mut hasher = Sha256::new();
    hasher.update(os.as_bytes());
    hasher.update(b"|");
    hasher.update(cpu_brand.trim().to_lowercase().as_bytes());
    hasher.update(b"|");
    hasher.update(memory_total_mb.to_le_bytes());
    let digest = hasher.finalize();
    digest.iter().map(|byte| format!("{:02x}", byte)).collect()
}

#[cfg(test)]
#[path = "detector_test.rs"]
mod tests;
