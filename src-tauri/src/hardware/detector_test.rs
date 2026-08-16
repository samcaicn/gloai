// Copyright (c) 2026 MeeJoy

// Unit tests for the hardware detection module. The tests focus on the
// pure functions (version routing + fingerprint) so we don't need a real
// `System` snapshot to run them; one smoke test exercises
// `HardwareDetector::detect` to make sure the sysinfo wiring still
// compiles and runs.

use super::*;

#[test]
fn routes_low_memory_systems_to_unsupported() {
    let gpu = None;
    assert_eq!(
        match_hardware_version(1024, gpu),
        HardwareVersion::Unsupported
    );
}

#[test]
fn routes_no_gpu_to_cpu_only() {
    let gpu: Option<&GpuInfo> = None;
    assert_eq!(match_hardware_version(8192, gpu), HardwareVersion::CpuOnly);
}

#[test]
fn routes_integrated_gpu_to_integrated() {
    let gpu = GpuInfo {
        name: "Intel UHD 770".to_string(),
        vram_mb: Some(128),
        discrete: false,
    };
    assert_eq!(
        match_hardware_version(16384, Some(&gpu)),
        HardwareVersion::Integrated
    );
}

#[test]
fn routes_discrete_gpu_to_discrete() {
    let gpu = GpuInfo {
        name: "NVIDIA RTX 4090".to_string(),
        vram_mb: Some(24576),
        discrete: true,
    };
    assert_eq!(
        match_hardware_version(32768, Some(&gpu)),
        HardwareVersion::Discrete
    );
}

#[test]
fn fingerprint_is_stable_and_changes_with_inputs() {
    let a = build_fingerprint("Apple M2", 16384, "macos");
    let b = build_fingerprint("Apple M2", 16384, "macos");
    let c = build_fingerprint("Apple M3", 16384, "macos");
    let d = build_fingerprint("Apple M2", 16385, "macos");

    assert_eq!(a, b, "fingerprint should be deterministic");
    assert_ne!(a, c, "CPU change should change the fingerprint");
    assert_ne!(a, d, "memory change should change the fingerprint");
    assert_eq!(a.len(), 64, "SHA-256 hex output is 64 chars");
}

#[test]
fn detect_returns_a_populated_hardware_info() {
    // This is a smoke test — we don't care about the exact values,
    // only that the struct comes back fully populated and the routing
    // is consistent (recommended == matched by construction).
    let info = HardwareDetector::detect();
    assert!(!info.os_type.is_empty(), "os_type should be populated");
    assert!(
        !info.cpu.brand.is_empty(),
        "cpu brand should be populated"
    );
    assert!(
        info.cpu.core_count > 0,
        "core_count should be at least 1"
    );
    assert_eq!(
        info.recommended_version, info.matched_version,
        "recommended and matched should agree for now"
    );
    // Routing must be one of the four valid variants — we use a
    // match on the Debug representation rather than `matches!` to
    // stay compatible with `#[deny(unreachable_patterns)]`.
    let label = format!("{:?}", info.matched_version);
    assert!(
        [
            "CpuOnly",
            "Integrated",
            "Discrete",
            "Unsupported",
        ]
        .contains(&label.as_str()),
        "unexpected version label: {}",
        label,
    );
}
