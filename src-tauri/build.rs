use std::process::Command;

/// `tauri_build::build()` reads `tauri.conf.json` and verifies the
/// bundle config. We don't ship any externalBin sidecars in v5
/// (the Hermes gateway is the in-process axum server), so this
/// build script's only jobs are:
///   1. Stamp TUPAI_GIT_SHA / TUPAI_BUILD_TIME / TUPAI_BUILD_PROFILE
///      build metadata into `app_lib::build_info` (see
///      `stamp_build_metadata`).
///   2. Verify the release profile is sane (v5 has no sidecar to
///      enforce — `enforce_real_sidecar_for_release` is a
///      one-line info log now).
///   3. Invoke `tauri_build::build()` which does the heavy
///      lifting (Tauri 2 bundle config validation + codegen).
///
/// History: the v0.1.0-tupai release pipeline bundled Node.js
/// (`node.exe`, ~70 MiB) as a Tauri 2 sidecar plus the Hermes
/// CLI source (`resources/hermes/hermes-cli.cjs`, ~5 KiB) as a
/// resource, and `start_detached_gateway` spawned
/// `node hermes-cli.cjs gateway start` to host the gateway. v5
/// deletes that whole path: the gateway is now an axum listener
/// inside `tupai.exe` itself (see `hermes::embedded_server`),
/// so the installer is a single ~15 MiB tupai.exe + a few
/// standard NSIS / .dmg / .deb metadata files. The previous
/// `stage_hermes_sidecar_stub` (4 KiB zero-byte stub for
/// tauri-build's existence check) is no longer needed.
fn main() {
    // v5: no sidecar staging. We just stamp build metadata, then
    // run the (now no-op) sidecar check for the release profile,
    // then delegate to tauri_build::build() for the rest.
    stamp_build_metadata();
    enforce_real_sidecar_for_release();
    tauri_build::build();
}

/// Stamp `TUPAI_GIT_SHA` / `TUPAI_BUILD_TIME` / `TUPAI_BUILD_PROFILE` as
/// `cargo:rustc-env` so `env!()` in `app_lib::build_info` reads them at
/// compile time. Picked up by:
///   * `app::about_metadata` (Settings → About 面板) — shows
///     "Built from <git_sha> at <build_time> (<profile>)"
///   * support bundle uploaded with crash reports
///
/// Resolution order for `git_sha`:
///   1. `TUPAI_GIT_SHA_OVERRIDE` env var (set by `release.yml` from
///      `github.event.pull_request.head.sha` / `github.sha` — covers
///      merge commits and tag pushes where `git rev-parse` may
///      produce a different ref than what the user pushed)
///   2. `git rev-parse HEAD` (local dev)
///   3. `GITHUB_SHA` env var (CI environment)
///   4. "unknown" (last-ditch fallback, e.g. tarball extract with
///      no .git dir)
///
/// Re-run triggers:
///   * git HEAD moved (`.git/HEAD`)
///   * any tracked file in the repo changed (`.git/index` is the
///     cheap stand-in — touched whenever files are added / committed)
fn stamp_build_metadata() {
    println!("cargo:rerun-if-changed=../.git/HEAD");
    println!("cargo:rerun-if-changed=../.git/index");
    println!("cargo:rerun-if-env-changed=TUPAI_GIT_SHA_OVERRIDE");
    println!("cargo:rerun-if-env-changed=GITHUB_SHA");

    let git_sha = std::env::var("TUPAI_GIT_SHA_OVERRIDE")
        .ok()
        .filter(|v| !v.is_empty())
        .or_else(|| {
            // Local dev: read HEAD directly. .git/HEAD is always
            // present in a working clone; CI may also have it
            // because release.yml does `actions/checkout@v4`
            // (no fetch-depth override, so the full ref is there).
            Command::new("git")
                .args(["rev-parse", "HEAD"])
                .output()
                .ok()
                .filter(|o| o.status.success())
                .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
                .filter(|s| !s.is_empty())
        })
        .or_else(|| {
            std::env::var("GITHUB_SHA")
                .ok()
                .filter(|v| !v.is_empty())
        })
        .unwrap_or_else(|| "unknown".to_string());

    // ISO 8601 / RFC 3339 UTC. `BUILD_TIME` instead of `BUILD_DATE`
    // because we want enough precision to distinguish "two CI runs
    // in the same minute" — useful for the "which CI build is
    // running on this user's machine?" triage.
    let build_time = std::env::var("TUPAI_BUILD_TIME_OVERRIDE")
        .ok()
        .filter(|v| !v.is_empty())
        .unwrap_or_else(|| {
            // 2026-06-08T15:30:42Z
            let now = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_secs())
                .unwrap_or(0);
            format_iso8601_utc(now)
        });

    let profile = std::env::var("PROFILE").unwrap_or_else(|_| "unknown".to_string());
    let target = std::env::var("TARGET").unwrap_or_else(|_| "unknown".to_string());
    let rustc_version = rustc_version_string().unwrap_or_else(|| "unknown".to_string());

    // Short SHA for log / about-panel display. Full SHA stays in
    // `TUPAI_GIT_SHA` for support bundle / crash reports.
    let short_sha = if git_sha.len() >= 7 { &git_sha[..7] } else { &git_sha };

    println!("cargo:rustc-env=TUPAI_GIT_SHA={}", git_sha);
    println!("cargo:rustc-env=TUPAI_GIT_SHA_SHORT={}", short_sha);
    println!("cargo:rustc-env=TUPAI_BUILD_TIME={}", build_time);
    println!("cargo:rustc-env=TUPAI_BUILD_PROFILE={}", profile);
    println!("cargo:rustc-env=TUPAI_BUILD_TARGET={}", target);
    println!("cargo:rustc-env=TUPAI_RUSTC_VERSION={}", rustc_version);
}

/// Format a Unix timestamp as `YYYY-MM-DDTHH:MM:SSZ`. Hand-rolled
/// because pulling in `chrono` just for this is overkill (and
/// chrono is already a [dependencies] entry, but we don't want
/// `build.rs` to depend on the lib's dependency tree — build
/// scripts re-link the whole graph if you add a dep).
fn format_iso8601_utc(unix_secs: u64) -> String {
    // Days from 1970-01-01 → year/month/day via a small civil-from-days
    // algorithm. (Howard Hinnant's `civil_from_days`.)
    let z = (unix_secs / 86_400) as i64;
    let secs_in_day = (unix_secs % 86_400) as u32;
    let hour = secs_in_day / 3600;
    let minute = (secs_in_day % 3600) / 60;
    let second = secs_in_day % 60;

    let z2 = z + 719_468;
    let era = if z2 >= 0 { z2 } else { z2 - 146_096 } / 146_097;
    let doe = (z2 - era * 146_097) as u64;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe as i64 + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = if m <= 2 { y + 1 } else { y };

    format!(
        "{:04}-{:02}-{:02}T{:02}:{:02}:{:02}Z",
        y, m, d, hour, minute, second
    )
}

fn rustc_version_string() -> Option<String> {
    let out = Command::new("rustc").args(["--version"]).output().ok()?;
    if !out.status.success() {
        return None;
    }
    Some(String::from_utf8_lossy(&out.stdout).trim().to_string())
}

fn enforce_real_sidecar_for_release() {
    // v5: no sidecar to enforce. The Hermes gateway is the
    // in-process axum server (`hermes::embedded_server`), which
    // links into `tupai.exe` directly. There is no `node(.exe)`
    // sidecar binary, no `resources/hermes/hermes-cli.cjs`
    // resource, and no separate runtime to download. The
    // installer is a single ~15 MiB tupai.exe + a few platform
    // standard NSIS / .dmg / .deb metadata files. We keep the
    // function around (called from `main` at build-time) as a
    // no-op so the rest of build.rs doesn't need a cfg-gate.
    let profile = std::env::var("PROFILE").unwrap_or_default();
    if profile != "release" {
        return;
    }
    // Optional sanity: if the user *happens* to still have a
    // leftover `binaries/node-*` from a previous build, that's
    // a non-issue. The bundler no longer includes externalBin,
    // and the embedded server doesn't spawn any child, so the
    // stale binary is dead weight at most.
    //
    // We log a one-line note to stderr so anyone tailing `cargo
    // build --release` can confirm the v5 path is active.
    eprintln!(
        "[build.rs] v5 embedded-server build: no node sidecar, no \
         hermes-cli.cjs resource. Gateway runs in-process on :{} and :{}.",
        std::env::var("HERMES_GATEWAY_PORT").unwrap_or_else(|_| "8642".into()),
        std::env::var("HERMES_DASHBOARD_PORT").unwrap_or_else(|_| "9119".into()),
    );
}
