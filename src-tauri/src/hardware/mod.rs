// Copyright (c) 2026 MeeJoy

// tupAI P0 §2 — Hardware detection
//
// Cross-platform hardware probe used to pick the appropriate tupAI
// distribution (pure CPU / integrated GPU / discrete GPU) and to derive a
// stable hardware fingerprint that anchors the `crypto::storage` key.
//
// The public surface is intentionally small:
// * `HardwareDetector::detect`  — full probe (CPU, memory, GPU, OS)
// * `match_hardware_version`    — maps the probe to a tupAI variant
// * `build_fingerprint`         — stable hash input for crypto layer
//
// We rely on `sysinfo` for the cross-platform basics (CPU brand / cores /
// memory) and `os_info`-style strings for the OS label, since the heavy
// GPU probing is left for the future when a discrete detection module is
// needed. Today we derive the GPU list from the CPU brand string
// (Apple Silicon) or fall back to a single "integrated" entry, which is
// more than enough to drive the version router.

pub mod detector;
