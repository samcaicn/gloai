// Copyright (c) 2026 AIMarketing

// Multi-GPU status query.
//
// Wraps the existing `HardwareDetector` with a UI-friendly report that
// aggregates VRAM across every detected GPU and picks the most capable
// one as the default for inference routing. We deliberately do NOT add
// a new GPU probe here — `GpuInfo` is owned by
// `crate::hardware::detector` and the spec says "do not change
// the HardwareDetector / GpuInfo types".

use crate::hardware::detector::{GpuInfo, HardwareDetector};
use serde::Serialize;

/// Per-GPU status report surfaced to the front-end.
///
/// `vram_gb` is derived from `GpuInfo::vram_mb` (which itself is an
/// `Option<u64>` because the Apple Silicon path doesn't expose VRAM).
/// For GPUs without a VRAM reading we return 0.0 — the UI can hide
/// the column on `null`/`0` devices.
#[allow(dead_code)]
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GpuStatusEntry {
    pub name: String,
    pub vram_gb: f64,
    pub discrete: bool,
    /// `true` when the detector could not measure VRAM (e.g. Apple
    /// Silicon). Lets the UI render "shared" instead of "0 GB".
    pub vram_unknown: bool,
}

/// Aggregate report — what the front-end sees.
#[allow(dead_code)]
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GpuStatusReport {
    pub gpu_count: u32,
    pub gpus: Vec<GpuStatusEntry>,
    pub total_vram_gb: f64,
    /// Index into `gpus` for the device we'd route an inference
    /// request to. `None` when no GPU was detected.
    pub recommended_for_inference: Option<u32>,
}

#[allow(dead_code)]
fn vram_mb_to_gb(vram_mb: Option<u64>) -> (f64, bool) {
    match vram_mb {
        Some(mb) => (mb as f64 / 1024.0, false),
        None => (0.0, true),
    }
}

#[allow(dead_code)]
fn to_status_entry(gpu: &GpuInfo) -> GpuStatusEntry {
    let (vram_gb, vram_unknown) = vram_mb_to_gb(gpu.vram_mb);
    GpuStatusEntry {
        name: gpu.name.clone(),
        vram_gb,
        discrete: gpu.discrete,
        vram_unknown,
    }
}

/// Return a structured multi-GPU status report.
///
/// Implementation note: the underlying `HardwareDetector` is called
/// synchronously because it owns no global state. The front-end caches
/// the result for a few seconds, so we do not need to memoize here.
#[allow(dead_code)]
#[tauri::command]
pub fn get_gpu_status() -> GpuStatusReport {
    let info = HardwareDetector::detect();

    let gpus: Vec<GpuStatusEntry> = info.gpu_list.iter().map(to_status_entry).collect();
    let total_vram_gb: f64 = gpus
        .iter()
        .filter(|entry| !entry.vram_unknown)
        .map(|entry| entry.vram_gb)
        .sum();

    // Pick the discrete GPU with the largest known VRAM; fall back to
    // the first GPU with a known VRAM; otherwise `None`. We prefer
    // `discrete` so integrated graphics do not get auto-routed onto.
    let mut best_index: Option<u32> = None;
    let mut best_score: i128 = -1;
    for (index, gpu) in info.gpu_list.iter().enumerate() {
        let vram = match gpu.vram_mb {
            Some(mb) => mb as i128,
            None => continue,
        };
        // Score: VRAM in MB + a huge bonus for discrete so we never
        // route to integrated by accident.
        let score = vram + if gpu.discrete { 1 << 40 } else { 0 };
        if score > best_score {
            best_score = score;
            best_index = Some(index as u32);
        }
    }

    GpuStatusReport {
        gpu_count: gpus.len() as u32,
        gpus,
        total_vram_gb,
        recommended_for_inference: best_index,
    }
}
