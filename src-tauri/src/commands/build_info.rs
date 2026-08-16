// Copyright (c) 2026 MeeJoy
//
// build_info tauri command — exposes the compile-time build
// metadata stamped by `build.rs` (see `src/build_info.rs`) to the
// React frontend.
//
// Why expose it via IPC instead of baking into tauri.conf.json:
//   - `tauri.conf.json` is shipped verbatim to all users, so any
//     value you put there is also "leaked" to anyone who unpacks
//     the installer. Build metadata (git SHA, build time, target
//     triple) is OK to share — that's the point of "About" panels
//     and crash-report support bundles — but the IPC return value
//     keeps it out of `out/_/index.html` where it would otherwise
//     show up in `view-source:`.
//   - Tauri's `tauri::command` boundary serializes the return
//     value as JSON, so frontend can `await invoke('get_build_info')`
//     and get a typed object back.
//
// Returned JSON shape:
//   {
//     "git_sha":       "7a3f19b8c4d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5",
//     "git_sha_short": "7a3f19b",
//     "build_time":    "2026-06-08T15:30:42Z",
//     "build_profile": "release",
//     "build_target":  "x86_64-pc-windows-msvc",
//     "rustc_version": "rustc 1.85.0 (...)"
//   }

use serde::Serialize;

use crate::build_info as meta;

#[derive(Debug, Clone, Serialize)]
pub struct BuildInfo {
    pub git_sha: &'static str,
    pub git_sha_short: &'static str,
    pub build_time: &'static str,
    pub build_profile: &'static str,
    pub build_target: &'static str,
    pub rustc_version: &'static str,
}

#[tauri::command]
pub fn get_build_info() -> BuildInfo {
    BuildInfo {
        git_sha: meta::GIT_SHA,
        git_sha_short: meta::GIT_SHA_SHORT,
        build_time: meta::BUILD_TIME,
        build_profile: meta::BUILD_PROFILE,
        build_target: meta::BUILD_TARGET,
        rustc_version: meta::RUSTC_VERSION,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn get_build_info_returns_consistent_fields() {
        let info = get_build_info();
        assert_eq!(info.git_sha, meta::GIT_SHA);
        assert_eq!(info.git_sha_short, meta::GIT_SHA_SHORT);
        assert_eq!(info.build_time, meta::BUILD_TIME);
        assert_eq!(info.build_profile, meta::BUILD_PROFILE);
        assert_eq!(info.build_target, meta::BUILD_TARGET);
        assert_eq!(info.rustc_version, meta::RUSTC_VERSION);
    }
}
