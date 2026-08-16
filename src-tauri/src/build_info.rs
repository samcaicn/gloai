// Copyright (c) 2026 MeeJoy
//
// build_info — compile-time build metadata stamped by build.rs.
//
// These are `pub const`s (not `LazyLock`/runtime reads) so they end
// up in the binary's read-only `.rodata` segment — no per-process
// init cost, and they survive `strip = "symbols"` because they're
// referenced from `app::about_metadata` and the support bundle
// upload (so the linker keeps them in the final binary even with
// aggressive stripping).
//
// Resolved by build.rs in this priority order:
//   - `TUPAI_GIT_SHA`         : full SHA, for support bundle / crash report
//   - `TUPAI_GIT_SHA_SHORT`   : 7-char SHA, for About panel / log lines
//   - `TUPAI_BUILD_TIME`      : ISO 8601 UTC, e.g. "2026-06-08T15:30:42Z"
//   - `TUPAI_BUILD_PROFILE`   : "debug" or "release" (cargo $PROFILE)
//   - `TUPAI_BUILD_TARGET`    : target triple, e.g. "x86_64-pc-windows-msvc"
//   - `TUPAI_RUSTC_VERSION`   : e.g. "rustc 1.85.0 (e66198dcb 2025-01-16)"
//
// If you need a "what commit is this binary?" answer in a hurry,
// `GIT_SHA` is the canonical source — paste into `git show` to
// resolve the full state.

/// Full commit SHA the binary was built from. Falls back to
/// "unknown" when build.rs can't determine it (e.g. tarball
/// extract with no `.git` directory).
pub const GIT_SHA: &str = env!("TUPAI_GIT_SHA", "TUPAI_GIT_SHA env var not set by build.rs");

/// First 7 chars of `GIT_SHA`, suitable for log lines / About
/// panel display. Falls back to the full `GIT_SHA` (or "unknown")
/// when the SHA is shorter than 7 chars.
pub const GIT_SHA_SHORT: &str = env!("TUPAI_GIT_SHA_SHORT", "TUPAI_GIT_SHA_SHORT env var not set by build.rs");

/// Build time in ISO 8601 / RFC 3339 UTC, e.g. `2026-06-08T15:30:42Z`.
pub const BUILD_TIME: &str = env!("TUPAI_BUILD_TIME", "TUPAI_BUILD_TIME env var not set by build.rs");

/// Cargo profile: `"debug"` or `"release"`.
pub const BUILD_PROFILE: &str = env!("TUPAI_BUILD_PROFILE", "TUPAI_BUILD_PROFILE env var not set by build.rs");

/// Build target triple, e.g. `"x86_64-pc-windows-msvc"`.
pub const BUILD_TARGET: &str = env!("TUPAI_BUILD_TARGET", "TUPAI_BUILD_TARGET env var not set by build.rs");

/// Full `rustc --version` string used for the build.
pub const RUSTC_VERSION: &str = env!("TUPAI_RUSTC_VERSION", "TUPAI_RUSTC_VERSION env var not set by build.rs");

/// One-line summary suitable for the About panel / support bundle
/// header: `tupai 1.2.3 (release / 7a3f19b / x86_64-pc-windows-msvc / 2026-06-08T15:30:42Z)`.
pub fn one_line(app_version: &str) -> String {
    format!(
        "tupai {} ({} / {} / {} / {})",
        app_version,
        BUILD_PROFILE,
        GIT_SHA_SHORT,
        BUILD_TARGET,
        BUILD_TIME,
    )
}

/// Full multi-line dump for the support bundle / crash report
/// header. Stays human-readable so support can paste it into a
/// ticket without re-formatting.
pub fn dump(app_version: &str) -> String {
    format!(
        "tupai {app_version}\n  profile    : {profile}\n  git_sha    : {sha}\n  target     : {target}\n  build_time : {time}\n  rustc      : {rustc}",
        app_version = app_version,
        profile = BUILD_PROFILE,
        sha = GIT_SHA,
        target = BUILD_TARGET,
        time = BUILD_TIME,
        rustc = RUSTC_VERSION,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn git_sha_is_seven_or_more() {
        // Either the env var is "unknown" (build.rs fallback) or
        // it is a hex SHA of length 7+ (short or full).
        assert!(
            GIT_SHA == "unknown" || GIT_SHA.len() >= 7,
            "GIT_SHA must be 'unknown' or at least 7 chars, got {:?}",
            GIT_SHA
        );
    }

    #[test]
    fn build_time_is_iso8601_utc() {
        // 20 chars: "YYYY-MM-DDTHH:MM:SSZ"
        assert_eq!(BUILD_TIME.len(), 20, "BUILD_TIME must be 20-char ISO 8601 UTC, got {:?}", BUILD_TIME);
        assert!(BUILD_TIME.ends_with('Z'), "BUILD_TIME must end with Z, got {:?}", BUILD_TIME);
        assert_eq!(BUILD_TIME.chars().nth(4), Some('-'));
        assert_eq!(BUILD_TIME.chars().nth(7), Some('-'));
        assert_eq!(BUILD_TIME.chars().nth(10), Some('T'));
    }

    #[test]
    fn build_profile_is_known() {
        assert!(
            BUILD_PROFILE == "debug" || BUILD_PROFILE == "release",
            "BUILD_PROFILE must be debug or release, got {:?}",
            BUILD_PROFILE
        );
    }

    #[test]
    fn one_line_includes_all_fields() {
        let line = one_line("1.2.3");
        assert!(line.contains("1.2.3"));
        assert!(line.contains(BUILD_PROFILE));
        assert!(line.contains(BUILD_TARGET));
        assert!(line.contains(BUILD_TIME));
    }
}
