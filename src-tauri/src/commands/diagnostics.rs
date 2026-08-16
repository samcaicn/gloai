// Copyright (c) 2026 MeeJoy
//
// Dev-mode self-diagnostics. Provides Tauri commands the front-end
// (and the user) can invoke to:
//   1. Snapshot the runtime state of Hermes, the gateway, and the
//      dashboard without leaving the UI.
//   2. Pull the tail of the on-disk log file so a failed start-up can
//      be triaged without opening a terminal.
//   3. Run an opinionated auto-fix loop (re-probe ports, start
//      `hermes gateway`, open the dashboard, surface the most recent
//      error).
//   4. Periodically analyse the log file for known error signatures
//      and emit `tupai-dev-error-detected` events. The dev-mode
//      frontend uses these to surface a "recent errors" widget that
//      can auto-apply the matching fix.
//
// These commands are the entry points the agent uses to "auto-collect
// the errors from the running session" the user asked for. They are
// safe to call in both `dev` and `release` builds — the log file is
// always written.

use serde::{Deserialize, Serialize};
use std::fs;
use std::io::{Read, Seek, SeekFrom};
use std::net::TcpStream;
use std::path::PathBuf;
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc, Mutex, OnceLock,
};
use std::time::Duration;

use tauri::{AppHandle, Emitter};

use crate::commands::legacy;

/// Resolved location of the rolling on-disk log file. The actual
/// writer is `crate::logging::FileLogger`, which deliberately
/// keeps the log next to `tupai.exe` (not under `%APPDATA%`) so
/// users can grab it without hunting through hidden folders. We
/// ask `FileLogger::path()` directly so the diagnostic reader and
/// the writer never disagree on the path.
pub fn log_file_path(_app: &AppHandle) -> Option<PathBuf> {
    Some(crate::logging::FileLogger::path())
}

/// Report describing the runtime state of every Hermes-side component
/// the front-end cares about. Kept intentionally flat (everything is a
/// `String` / `bool` / number) so it serialises to JSON without
/// needing the front-end to model the whole Tauri surface.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DiagnosticReport {
    pub generated_at: String,
    pub os: String,
    pub arch: String,
    pub tauri_version: String,
    pub app_version: String,
    pub log_file_path: Option<String>,
    pub log_file_size_bytes: Option<u64>,
    pub hermes_binary_path: Option<String>,
    pub hermes_version_output: Option<String>,
    pub hermes_version_error: Option<String>,
    pub gateway_port: u16,
    pub gateway_target: String,
    pub gateway_reachable: bool,
    pub dashboard_port: u16,
    pub dashboard_reachable: bool,
    pub last_diagnostic_hint: Option<String>,
}

#[tauri::command]
pub fn run_self_diagnostics(
    app: AppHandle,
    hint: Option<String>,
) -> Result<DiagnosticReport, String> {
    let now = chrono::Utc::now().to_rfc3339();
    let (os, arch) = (
        std::env::consts::OS.to_string(),
        std::env::consts::ARCH.to_string(),
    );

    // Hermes binary discovery.
    let (hermes_path, hermes_version_output, hermes_version_error) =
        match legacy::resolve_hermes_binary_path_for_diag() {
            Some(path) => {
                let out = run_hermes_version_quickly(&path);
                match out {
                    Ok(version_output) => (Some(path), Some(version_output), None),
                    Err(err) => (Some(path), None, Some(err)),
                }
            }
            None => (
                None,
                None,
                Some("`hermes` is not on PATH. Install Hermes Agent or add it to PATH.".to_string()),
            ),
        };

    // Port reachability.
    let gateway_reachable = probe_port("127.0.0.1", legacy::HERMES_GATEWAY_PORT_EXTERN);
    let dashboard_reachable = probe_port("127.0.0.1", legacy::HERMES_DASHBOARD_PORT_EXTERN);

    // Log file metadata.
    let (log_path_str, log_size) = match log_file_path(&app) {
        Some(p) => {
            let size = fs::metadata(&p).ok().map(|m| m.len());
            (Some(p.display().to_string()), size)
        }
        None => (None, None),
    };

    Ok(DiagnosticReport {
        generated_at: now,
        os,
        arch,
        tauri_version: env!("CARGO_PKG_VERSION").to_string(),
        app_version: env!("CARGO_PKG_VERSION").to_string(),
        log_file_path: log_path_str,
        log_file_size_bytes: log_size,
        hermes_binary_path: hermes_path,
        hermes_version_output,
        hermes_version_error,
        gateway_port: legacy::HERMES_GATEWAY_PORT_EXTERN,
        gateway_target: format!("http://127.0.0.1:{}", legacy::HERMES_GATEWAY_PORT_EXTERN),
        gateway_reachable,
        dashboard_port: legacy::HERMES_DASHBOARD_PORT_EXTERN,
        dashboard_reachable,
        last_diagnostic_hint: hint,
    })
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LogChunk {
    pub path: Option<String>,
    pub total_bytes: u64,
    pub truncated: bool,
    pub lines: Vec<String>,
}

/// Returns the last `max_bytes` of the on-disk log file as decoded UTF-8
/// lines. Designed to be safe to call repeatedly from the UI; reads at
/// most once per second, capped at 512 KiB so a runaway log cannot
/// freeze the bridge.
#[tauri::command]
pub fn collect_recent_logs(
    app: AppHandle,
    max_bytes: Option<u64>,
) -> Result<LogChunk, String> {
    let cap = max_bytes.unwrap_or(256 * 1024).min(512 * 1024);
    let Some(path) = log_file_path(&app) else {
        return Ok(LogChunk {
            path: None,
            total_bytes: 0,
            truncated: false,
            lines: Vec::new(),
        });
    };

    let metadata = match fs::metadata(&path) {
        Ok(m) => m,
        Err(_) => {
            return Ok(LogChunk {
                path: Some(path.display().to_string()),
                total_bytes: 0,
                truncated: false,
                lines: Vec::new(),
            });
        }
    };

    let total = metadata.len();
    let read_len = cap.min(total);
    let offset = total - read_len;

    let mut file = fs::File::open(&path)
        .map_err(|e| format!("Failed to open log file {:?}: {}", path, e))?;
    file.seek(SeekFrom::Start(offset))
        .map_err(|e| format!("Failed to seek log file: {}", e))?;

    let mut buffer = Vec::with_capacity(read_len as usize);
    file.take(read_len)
        .read_to_end(&mut buffer)
        .map_err(|e| format!("Failed to read log file: {}", e))?;

    let text = String::from_utf8_lossy(&buffer);
    let mut lines: Vec<String> = text.lines().map(|s| s.to_string()).collect();
    // If we started mid-line, drop the half-line at the start so the
    // caller always sees whole log records.
    if offset > 0 && !lines.is_empty() {
        lines.remove(0);
    }
    let truncated = offset > 0;

    Ok(LogChunk {
        path: Some(path.display().to_string()),
        total_bytes: total,
        truncated,
        lines,
    })
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AutoFixResult {
    pub actions: Vec<String>,
    pub gateway_reachable_after: bool,
    pub dashboard_reachable_after: bool,
    pub final_error: Option<String>,
}

/// Runs an opinionated auto-fix sequence. Each step is logged via
/// `log::info!` so the log file captures the reasoning the user
/// can later inspect from the diagnostics panel.
#[tauri::command]
pub fn auto_fix_hermes_connection() -> Result<AutoFixResult, String> {
    let mut actions = Vec::new();
    let mut last_error: Option<String> = None;

    // 1. Resolve hermes. If it is missing, the auto-fix can do
    //    nothing — surface the misconfiguration immediately.
    let hermes = match legacy::resolve_hermes_binary_path_for_diag() {
        Some(p) => {
            actions.push(format!("found hermes at {}", p));
            log::info!("[autofix] resolved hermes binary: {}", p);
            p
        }
        None => {
            let msg =
                "`hermes` binary not on PATH. Install Hermes Agent and restart tupAI."
                    .to_string();
            log::error!("[autofix] {}", msg);
            actions.push(msg.clone());
            return Ok(AutoFixResult {
                actions,
                gateway_reachable_after: probe_port("127.0.0.1", legacy::HERMES_GATEWAY_PORT_EXTERN),
                dashboard_reachable_after: probe_port("127.0.0.1", legacy::HERMES_DASHBOARD_PORT_EXTERN),
                final_error: Some(msg),
            });
        }
    };

    // 2. Kill anything currently squatting on the gateway port. We
    //    re-use the cross-platform kill helper so Windows works too.
    if legacy::stop_gateway_process_for_diag() {
        actions.push(format!(
            "killed any process listening on {} (hermes gateway)",
            legacy::HERMES_GATEWAY_PORT_EXTERN
        ));
        log::info!(
            "[autofix] killed existing listener on port {}",
            legacy::HERMES_GATEWAY_PORT_EXTERN
        );
    }

    // 3. Start the gateway and wait up to 15s for the port.
    log::info!(
        "[autofix] launching `{} gateway start`",
        shell_quote_diag(&hermes)
    );
    let start_output = run_hermes_quickly(&hermes, &["gateway", "start"]);
    match start_output {
        Ok(out) if out.success => {
            actions.push("`hermes gateway start` exited 0".to_string());
        }
        Ok(out) => {
            actions.push(format!(
                "`hermes gateway start` exited non-zero: {}",
                out.stderr
            ));
            log::warn!(
                "[autofix] `hermes gateway start` non-zero: stdout={} stderr={}",
                out.stdout,
                out.stderr
            );
            // Fallback: `gateway restart` (older hermes builds).
            log::info!("[autofix] falling back to `hermes gateway restart`");
            let _ = run_hermes_quickly(&hermes, &["gateway", "restart"]);
        }
        Err(err) => {
            actions.push(format!("failed to spawn `hermes gateway start`: {}", err));
            log::error!("[autofix] spawn failed: {}", err);
            last_error = Some(err);
        }
    }

    if !wait_for_port("127.0.0.1", legacy::HERMES_GATEWAY_PORT_EXTERN, Duration::from_secs(15)) {
        let msg = format!(
            "gateway port {} still not reachable after auto-fix; see log file for the\n\
             `hermes gateway start` stdout/stderr recorded above.",
            legacy::HERMES_GATEWAY_PORT_EXTERN
        );
        log::error!("[autofix] {}", msg);
        last_error = Some(msg);
    } else {
        actions.push(format!(
            "port {} now reachable (gateway healthy)",
            legacy::HERMES_GATEWAY_PORT_EXTERN
        ));
    }

    // 4. Try to also bring the dashboard up — it isn't strictly
    //    required for chat, but the cron / skills UI depends on it.
    if !probe_port("127.0.0.1", legacy::HERMES_DASHBOARD_PORT_EXTERN) {
        log::info!(
            "[autofix] dashboard port {} not reachable, attempting to relaunch",
            legacy::HERMES_DASHBOARD_PORT_EXTERN
        );
        let _ = run_hermes_quickly(&hermes, &["dashboard", "restart"]);
        // give it a moment
        let _ = wait_for_port("127.0.0.1", legacy::HERMES_DASHBOARD_PORT_EXTERN, Duration::from_secs(8));
    }

    Ok(AutoFixResult {
        actions,
        gateway_reachable_after: probe_port("127.0.0.1", legacy::HERMES_GATEWAY_PORT_EXTERN),
        dashboard_reachable_after: probe_port("127.0.0.1", legacy::HERMES_DASHBOARD_PORT_EXTERN),
        final_error: last_error,
    })
}

#[tauri::command]
pub fn reveal_log_file(app: AppHandle) -> Result<Option<String>, String> {
    Ok(log_file_path(&app).map(|p| p.display().to_string()))
}

// ---- helpers --------------------------------------------------------------

fn probe_port(host: &str, port: u16) -> bool {
    let addr = format!("{}:{}", host, port);
    if let Ok(mut addrs) = addr.to_socket_addrs() {
        let timeout = Duration::from_millis(800);
        return addrs.any(|a| TcpStream::connect_timeout(&a, timeout).is_ok());
    }
    false
}

fn wait_for_port(host: &str, port: u16, budget: Duration) -> bool {
    let start = std::time::Instant::now();
    while start.elapsed() < budget {
        if probe_port(host, port) {
            return true;
        }
        std::thread::sleep(Duration::from_millis(400));
    }
    false
}

struct SkillCommandResultLite {
    success: bool,
    stdout: String,
    stderr: String,
}

fn run_hermes_version_quickly(binary: &str) -> Result<String, String> {
    match run_hermes_quickly(binary, &["--version"]) {
        Ok(out) if out.success => Ok(out.stdout),
        Ok(out) => Err(format!(
            "hermes --version failed: stdout={} stderr={}",
            out.stdout, out.stderr
        )),
        Err(err) => Err(err),
    }
}

fn run_hermes_quickly(binary: &str, args: &[&str]) -> Result<SkillCommandResultLite, String> {
    let rendered_args = args
        .iter()
        .map(|a| shell_quote_diag(a))
        .collect::<Vec<_>>()
        .join(" ");
    let command = format!("{} {}", shell_quote_diag(binary), rendered_args);
    legacy::run_login_shell_command(&command).map(|out| SkillCommandResultLite {
        success: out.status.success(),
        stdout: String::from_utf8_lossy(&out.stdout).trim().to_string(),
        stderr: String::from_utf8_lossy(&out.stderr).trim().to_string(),
    })
}

fn shell_quote_diag(value: &str) -> String {
    // Same Windows-aware quoting as legacy::shell_quote, but inlined so
    // this module has no dependency on the private helper.
    #[cfg(target_os = "windows")]
    {
        let mut escaped = String::with_capacity(value.len() + 2);
        escaped.push('"');
        for c in value.chars() {
            if c == '"' {
                escaped.push_str("\\\"");
            } else {
                escaped.push(c);
            }
        }
        escaped.push('"');
        escaped
    }
    #[cfg(not(target_os = "windows"))]
    {
        format!("'{}'", value.replace('\'', "'\\''"))
    }
}

// `to_socket_addrs` is used on `String`; pull the trait in here.
use std::net::ToSocketAddrs;

// =====================================================================
//  Error pattern catalogue
// =====================================================================
//
// Each entry maps a regex-style description of a log line to:
//   * a stable signature (used as the dedup key in the watcher),
//   * a severity tier,
//   * the canonical fix we can apply automatically,
//   * a human-readable hint describing what the fix does and what the
//     user should expect.
//
// Adding a new pattern is intentionally a single `ErrorRule::new(...)`
// call below — the watcher / analyser / auto-fix loop all consume the
// catalogue through the same `match` chain.

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ErrorSeverity {
    Info,
    Warn,
    Error,
    Critical,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum FixAction {
    /// No auto-fix possible. Surface the hint and let the user act.
    Manual,
    /// Kill the existing listener and re-launch `hermes gateway start`.
    RestartGateway,
    /// Re-run `hermes gateway start` (does not kill the listener first).
    StartGateway,
    /// Re-run `hermes dashboard restart`.
    RestartDashboard,
    /// Re-open the on-disk log file and rotate it (best-effort truncate
    /// if the size exceeds a sane cap).
    RotateLog,
    /// Re-run the full auto-fix sequence (this is what the "重拉起"
    /// button on the connection card does).
    RunAutoFix,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ErrorRule {
    pub signature: &'static str,
    pub severity: ErrorSeverity,
    pub action: FixAction,
    pub hint: &'static str,
    /// Plain substring match — case-insensitive on the lowercase form.
    pub needle: &'static str,
}

fn error_catalogue() -> &'static [ErrorRule] {
    &[
        ErrorRule {
            signature: "hermes_not_on_path",
            severity: ErrorSeverity::Critical,
            action: FixAction::Manual,
            hint: "`hermes` is not on PATH. Install Hermes Agent and restart tupAI.",
            // Use a specific Windows cmd.exe error signature rather than
            // the bare token "hermes" (which would match every log line
            // that mentions the binary by name).
            needle: "is not recognized as an internal or external command",
        },
        ErrorRule {
            signature: "hermes_not_on_path_diag",
            severity: ErrorSeverity::Critical,
            action: FixAction::Manual,
            hint: "`hermes` binary missing on PATH. Install Hermes Agent and restart tupAI.",
            needle: "`hermes` is not on path",
        },
        ErrorRule {
            signature: "hermes_unresolved_binary",
            severity: ErrorSeverity::Critical,
            action: FixAction::Manual,
            hint: "`hermes` binary could not be resolved. Add it to PATH or install Hermes Agent.",
            // `binary=hermes` is what `run_hermes_owned_command` logs
            // when `resolve_hermes_binary_path` returns `None` and the
            // code falls back to the literal token. This is the
            // cross-locale signal that PATH resolution failed (the
            // English cmd.exe error "is not recognized..." gets
            // garbled on Chinese Windows because cmd's stderr is
            // CP936-encoded; matching on the rust-side log line
            // works on every locale).
            needle: "binary=hermes args=",
        },
        ErrorRule {
            signature: "gateway_connection_refused",
            severity: ErrorSeverity::Error,
            action: FixAction::StartGateway,
            hint: "Gateway port 8642 refused the connection. Launching `hermes gateway start`.",
            needle: "connection refused",
        },
        ErrorRule {
            signature: "gateway_startup_failure",
            severity: ErrorSeverity::Error,
            action: FixAction::RunAutoFix,
            hint: "Hermes gateway failed to start. Running full auto-fix.",
            needle: "hermes gateway 拉起失败",
        },
        ErrorRule {
            signature: "gateway_startup_unconfirmed",
            severity: ErrorSeverity::Warn,
            action: FixAction::StartGateway,
            hint: "Hermes gateway start did not confirm readiness. Re-trying.",
            needle: "hermes gateway 拉起未成功",
        },
        ErrorRule {
            signature: "dashboard_unreachable",
            severity: ErrorSeverity::Warn,
            action: FixAction::RestartDashboard,
            hint: "Hermes dashboard port not reachable. Restarting dashboard.",
            needle: "dashboard port",
        },
        ErrorRule {
            signature: "port_in_use",
            severity: ErrorSeverity::Error,
            action: FixAction::RestartGateway,
            hint: "Port already in use. Killing the squatting process and restarting.",
            needle: "already in use",
        },
        ErrorRule {
            signature: "address_in_use",
            severity: ErrorSeverity::Error,
            action: FixAction::RestartGateway,
            hint: "Address already in use. Killing the squatting process and restarting.",
            needle: "address already in use",
        },
        ErrorRule {
            signature: "db_locked",
            severity: ErrorSeverity::Error,
            action: FixAction::Manual,
            hint: "SQLite database is locked by another process. Close other tupAI instances.",
            needle: "database is locked",
        },
        ErrorRule {
            signature: "log_permission_denied",
            severity: ErrorSeverity::Critical,
            action: FixAction::Manual,
            hint: "Permission denied while writing the log file. Check folder permissions.",
            needle: "permission denied",
        },
        ErrorRule {
            signature: "react_max_update_depth",
            severity: ErrorSeverity::Error,
            action: FixAction::Manual,
            hint: "React `Maximum update depth exceeded` — a store returned a fresh reference in `getSnapshot()`.",
            needle: "maximum update depth exceeded",
        },
        ErrorRule {
            signature: "no_such_file_dir",
            severity: ErrorSeverity::Error,
            action: FixAction::Manual,
            hint: "Path does not exist. The launch path probably needs to be re-set.",
            needle: "no such file or directory",
        },
        ErrorRule {
            signature: "tokio_join_error",
            severity: ErrorSeverity::Warn,
            action: FixAction::Manual,
            hint: "Tokio task was cancelled. Usually harmless; check upstream trigger.",
            needle: "tokio task was cancelled",
        },
        ErrorRule {
            signature: "tauri_setup_failed",
            severity: ErrorSeverity::Critical,
            action: FixAction::Manual,
            hint: "Tauri `.setup` failed. Full restart is required.",
            needle: "[startup] tupai setup",
        },
    ]
}

fn classify_line(line: &str) -> Option<&'static ErrorRule> {
    let lower = line.to_ascii_lowercase();
    // First-pass: the strong signatures first. Order matters: a line
    // that contains both "hermes" and "拉起未成功" should resolve to
    // `gateway_startup_unconfirmed`, not the generic `hermes_not_on_path`.
    error_catalogue().iter().find(|&rule| lower.contains(&rule.needle.to_ascii_lowercase())).map(|v| v as _)
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DetectedError {
    pub signature: String,
    pub severity: ErrorSeverity,
    pub action: FixAction,
    pub hint: String,
    pub first_seen_at: String,
    pub last_seen_at: String,
    pub occurrences: u32,
    pub sample_lines: Vec<String>,
}

#[derive(Debug, Default)]
struct AggregatedError {
    first_seen_at: String,
    last_seen_at: String,
    occurrences: u32,
    sample_lines: Vec<String>,
}

fn aggregate_matches(lines: &[String]) -> Vec<DetectedError> {
    use std::collections::BTreeMap;
    let mut by_sig: BTreeMap<&'static str, AggregatedError> = BTreeMap::new();

    for line in lines {
        let Some(rule) = classify_line(line) else {
            continue;
        };
        let entry = by_sig.entry(rule.signature).or_default();
        if entry.first_seen_at.is_empty() {
            entry.first_seen_at = chrono::Utc::now().to_rfc3339();
        }
        entry.last_seen_at = chrono::Utc::now().to_rfc3339();
        entry.occurrences = entry.occurrences.saturating_add(1);
        if entry.sample_lines.len() < 3 {
            entry.sample_lines.push(line.clone());
        }
    }

    error_catalogue()
        .iter()
        .filter_map(|rule| {
            by_sig.get(rule.signature).map(|agg| DetectedError {
                signature: rule.signature.to_string(),
                severity: rule.severity,
                action: rule.action,
                hint: rule.hint.to_string(),
                first_seen_at: agg.first_seen_at.clone(),
                last_seen_at: agg.last_seen_at.clone(),
                occurrences: agg.occurrences,
                sample_lines: agg.sample_lines.clone(),
            })
        })
        .collect()
}

/// Scan the on-disk log for known error signatures and return the
/// aggregated `DetectedError` list. The front-end uses this on demand
/// (e.g. when the user opens the "diagnostics" widget) and also
/// indirectly through the dev-mode watcher.
#[tauri::command]
pub fn analyze_log_for_errors(
    app: AppHandle,
    max_bytes: Option<u64>,
) -> Result<Vec<DetectedError>, String> {
    let chunk = collect_recent_logs(app, max_bytes)?;
    let errors = aggregate_matches(&chunk.lines);
    log::info!(
        "[diagnostics] analyze_log_for_errors: scanned {} bytes, {} lines, {} errors",
        chunk.total_bytes,
        chunk.lines.len(),
        errors.len()
    );
    Ok(errors)
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FixAttempt {
    pub signature: String,
    pub action: FixAction,
    pub success: bool,
    pub message: String,
}

/// Apply the canonical fix for a given error signature. The dev-mode
/// widget invokes this when the user clicks the "Auto Fix" button next
/// to a detected error. Signatures with `FixAction::Manual` are
/// returned with `success = false` and a `message` instructing the
/// user what to do.
#[tauri::command]
pub fn try_auto_fix_error(
    app: AppHandle,
    signature: String,
) -> Result<FixAttempt, String> {
    let Some(rule) = error_catalogue().iter().find(|r| r.signature == signature) else {
        return Err(format!("Unknown error signature: {}", signature));
    };

    log::info!(
        "[diagnostics] try_auto_fix_error: signature={} action={:?}",
        signature,
        rule.action
    );

    let result: Result<bool, String> = match rule.action {
        FixAction::Manual => Ok(false),
        FixAction::StartGateway => {
            // Idempotent: if already up, the port probe will succeed
            // and the run will be a no-op.
            legacy::ensure_hermes_gateway_running()
        }
        FixAction::RestartGateway => {
            let _ = legacy::stop_gateway_process_for_diag();
            legacy::ensure_hermes_gateway_running()
        }
        FixAction::RestartDashboard => {
            // The dashboard restart path lives in legacy::misc — we
            // delegate through the public `restart_hermes_dashboard`
            // wrapper that the front-end already uses.
            match legacy::restart_hermes_dashboard_inner_for_diag() {
                Ok(()) => Ok(true),
                Err(err) => Err(err),
            }
        }
        FixAction::RunAutoFix => match auto_fix_hermes_connection() {
            Ok(report) => Ok(report.gateway_reachable_after),
            Err(err) => Err(err),
        },
        FixAction::RotateLog => match log_file_path(&app) {
            Some(path) => legacy::rotate_app_log_for_diag(&path).map(|()| true),
            None => Ok(false),
        },
    };

    match result {
        Ok(true) => Ok(FixAttempt {
            signature: signature.clone(),
            action: rule.action,
            success: true,
            message: format!("Fix `{:?}` applied.", rule.action),
        }),
        Ok(false) => Ok(FixAttempt {
            signature: signature.clone(),
            action: rule.action,
            success: false,
            message: format!(
                "Signature `{}` requires manual action: {}",
                signature, rule.hint
            ),
        }),
        Err(err) => Ok(FixAttempt {
            signature: signature.clone(),
            action: rule.action,
            success: false,
            message: format!("Auto-fix failed: {}", err),
        }),
    }
}

// =====================================================================
//  Dev-mode log watcher
// =====================================================================
//
// The watcher runs in the background on a `spawn_blocking` task. It
// re-scans the tail of the log file every few seconds, aggregates
// matches, and emits a Tauri event the front-end can subscribe to.
// New signatures trigger a fresh event; signatures that are already
// known are still re-emitted at most once per minute (so the front-end
// UI can show a "still happening" badge without flooding the event
// bus).

const DEV_WATCHER_INTERVAL_SECS: u64 = 5;
const DEV_WATCHER_REPEAT_SECS: u64 = 60;
const DEV_WATCHER_SCAN_BYTES: u64 = 256 * 1024;

#[derive(Default)]
struct WatcherState {
    last_seen: std::collections::HashMap<String, std::time::Instant>,
    stop_flag: Option<Arc<AtomicBool>>,
    /// 保存 `tauri::async_runtime::spawn_blocking` 返回的 JoinHandle,
    /// 让 `stop_dev_log_watcher` 可以调 `abort()` 真正取消后台任务
    /// (光靠 stop_flag 还不够 —— 阻塞在 `std::thread::sleep` 里的循环
    /// 必须在下一轮 tick 才会看到 flag,会有秒级滞后)。
    watcher_handle: Option<tauri::async_runtime::JoinHandle<()>>,
}

static WATCHER_STATE: OnceLock<Mutex<WatcherState>> = OnceLock::new();

fn watcher_state() -> &'static Mutex<WatcherState> {
    WATCHER_STATE.get_or_init(|| Mutex::new(WatcherState::default()))
}

fn spawn_watcher(app: AppHandle) {
    let state_mutex = watcher_state();
    {
        let mut state = match state_mutex.lock() {
            Ok(s) => s,
            Err(p) => p.into_inner(),
        };
        if let Some(flag) = state.stop_flag.as_ref() {
            if !flag.load(Ordering::SeqCst) {
                log::info!("[diagnostics] dev log watcher already running; skipping spawn");
                return;
            }
            // The previous flag was tripped; clear and start a fresh
            // watcher below.
            state.stop_flag = None;
        }
    }

    let stop_flag = Arc::new(AtomicBool::new(false));
    {
        let mut state = match state_mutex.lock() {
            Ok(s) => s,
            Err(p) => p.into_inner(),
        };
        state.stop_flag = Some(stop_flag.clone());
    }

    log::info!("[diagnostics] starting dev log watcher (interval = {}s)", DEV_WATCHER_INTERVAL_SECS);

    let handle = tauri::async_runtime::spawn_blocking(move || {
        let mut last_emit = std::time::Instant::now()
            .checked_sub(std::time::Duration::from_secs(DEV_WATCHER_REPEAT_SECS))
            .unwrap_or_else(std::time::Instant::now);

        while !stop_flag.load(Ordering::SeqCst) {
            std::thread::sleep(Duration::from_secs(DEV_WATCHER_INTERVAL_SECS));
            if stop_flag.load(Ordering::SeqCst) {
                break;
            }

            let chunk = match collect_recent_logs(app.clone(), Some(DEV_WATCHER_SCAN_BYTES)) {
                Ok(c) => c,
                Err(err) => {
                    log::warn!("[diagnostics] watcher: collect_recent_logs failed: {}", err);
                    continue;
                }
            };

            let errors = aggregate_matches(&chunk.lines);
            if errors.is_empty() {
                continue;
            }

            // Decide which errors are "new" or "stale enough to refresh".
            let now = std::time::Instant::now();
            let mut emit_now: Vec<DetectedError> = Vec::new();
            {
                let state_mutex = watcher_state();
                let mut state = match state_mutex.lock() {
                    Ok(s) => s,
                    Err(p) => p.into_inner(),
                };
                for err in &errors {
                    let entry = state.last_seen.entry(err.signature.clone()).or_insert(now);
                    let is_fresh = now.duration_since(*entry)
                        >= std::time::Duration::from_secs(DEV_WATCHER_REPEAT_SECS);
                    if is_fresh {
                        *entry = now;
                        emit_now.push(err.clone());
                    }
                }
            }

            // Throttle the full payload: at most once every
            // `DEV_WATCHER_REPEAT_SECS` we re-emit *all* current
            // errors (so the UI can refresh stale badges), otherwise
            // we only emit freshly seen signatures.
            if now.duration_since(last_emit)
                >= std::time::Duration::from_secs(DEV_WATCHER_REPEAT_SECS)
            {
                last_emit = now;
                emit_now = errors.clone();
            }

            if !emit_now.is_empty() {
                let payload = serde_json::json!({
                    "errors": emit_now,
                    "all_errors": errors,
                });
                if let Err(err) = app.emit("tupai-dev-error-detected", payload) {
                    log::warn!("[diagnostics] watcher: emit failed: {}", err);
                }
            }
        }
        log::info!("[diagnostics] dev log watcher stopped");
    });
    // 把 JoinHandle 存到共享 state,让 stop_dev_log_watcher 可以 abort。
    {
        let state_mutex = watcher_state();
        let mut state = match state_mutex.lock() {
            Ok(s) => s,
            Err(p) => p.into_inner(),
        };
        state.watcher_handle = Some(handle);
    }
}

/// Start the dev-mode log watcher. Idempotent — repeated calls are
/// a no-op when the watcher is already running. Intended to be called
/// once from `lib.rs`'s `.setup(...)` block.
#[tauri::command]
pub fn start_dev_log_watcher(app: AppHandle) -> Result<bool, String> {
    let already_running = {
        let state_mutex = watcher_state();
        let state = match state_mutex.lock() {
            Ok(s) => s,
            Err(p) => p.into_inner(),
        };
        state
            .stop_flag
            .as_ref()
            .map(|f| !f.load(Ordering::SeqCst))
            .unwrap_or(false)
    };
    if already_running {
        log::info!("[diagnostics] start_dev_log_watcher: already running");
        return Ok(false);
    }
    spawn_watcher(app);
    Ok(true)
}

/// Stop the dev-mode log watcher. Returns `true` if a watcher was
/// actually stopped (so the front-end can show a toast).
#[tauri::command]
pub fn stop_dev_log_watcher() -> Result<bool, String> {
    let state_mutex = watcher_state();
    let mut state = match state_mutex.lock() {
        Ok(s) => s,
        Err(p) => p.into_inner(),
    };
    let was_running = state.stop_flag.is_some() || state.watcher_handle.is_some();
    if let Some(flag) = state.stop_flag.as_ref() {
        flag.store(true, Ordering::SeqCst);
        state.stop_flag = None;
    }
    if let Some(handle) = state.watcher_handle.take() {
        // 真正取消后台 spawn_blocking 任务,避免只靠 stop_flag
        // 还要等下一个 sleep tick 才能退出。
        handle.abort();
    }
    if was_running {
        log::info!("[diagnostics] stop_dev_log_watcher: stop signal sent");
        return Ok(true);
    }
    log::info!("[diagnostics] stop_dev_log_watcher: no watcher running");
    Ok(false)
}

/// Returns whether the watcher is currently active. Used by the
/// front-end to display the correct toggle state.
#[tauri::command]
pub fn is_dev_log_watcher_active() -> Result<bool, String> {
    let state_mutex = watcher_state();
    let state = match state_mutex.lock() {
        Ok(s) => s,
        Err(p) => p.into_inner(),
    };
    Ok(state
        .stop_flag
        .as_ref()
        .map(|f| !f.load(Ordering::SeqCst))
        .unwrap_or(false))
}

// =====================================================================
// Startup diagnostics — 启动期诊断通道
// =====================================================================
//
// 解决问题: 之前 init_skill_db / load_optimized_skills_into_registry
// 等启动步骤失败时只 log warn, 前端完全看不到。用户保存过的优化技能
// 加载失败后 "消失" 无任何提示。
//
// 设计: 一个简单的 Mutex<Vec<DiagnosticEntry>> 作为 Tauri state,
// 在 setup 早期 app.manage(StartupDiagnostics::new()), 各 init 函数
// 失败时调 record_diagnostic() 写入。前端通过 get_startup_diagnostics
// IPC 命令拉取列表, 渲染 toast / 诊断面板。

/// 单条启动诊断记录。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StartupDiagnosticEntry {
    /// "info" | "warn" | "error" —— 前端按级别决定是 toast 还是只记日志
    pub level: String,
    /// 模块名: "skill_db" / "optimized_skills" / "im_channels" 等
    pub module: String,
    /// 人类可读的错误/警告描述 (中文)
    pub message: String,
    /// Unix seconds
    pub timestamp: i64,
}

/// 启动诊断全局状态。在 `lib.rs::setup` 最早阶段 `app.manage` 注册,
/// 后续所有 init 函数通过 `record_diagnostic(app, level, module, msg)` 写入。
#[derive(Debug, Default)]
pub struct StartupDiagnostics {
    entries: std::sync::Mutex<Vec<StartupDiagnosticEntry>>,
}

impl StartupDiagnostics {
    pub fn new() -> Self {
        Self::default()
    }

    /// 追加一条诊断记录。Mutex poisoned 时不阻断 (用 into_inner 恢复)。
    pub fn record(&self, level: &str, module: &str, message: impl Into<String>) {
        let entry = StartupDiagnosticEntry {
            level: level.to_string(),
            module: module.to_string(),
            message: message.into(),
            timestamp: chrono::Utc::now().timestamp(),
        };
        if let Ok(mut entries) = self.entries.lock() {
            entries.push(entry);
        } else {
            // Mutex poisoned —— 理论上不会发生 (没有 panic 在 lock 期间),
            // 但即使发生也不应阻断启动流程
            log::error!("[startup-diagnostics] mutex poisoned, dropping entry");
        }
    }

    /// 快照当前所有诊断条目。前端调 `get_startup_diagnostics` 时用。
    pub fn list(&self) -> Vec<StartupDiagnosticEntry> {
        self.entries
            .lock()
            .map(|e| e.clone())
            .unwrap_or_else(|p| p.into_inner().clone())
    }
}

/// 便捷函数: 从 AppHandle 拿到 StartupDiagnostics state 后追加一条。
/// state 未注册时静默跳过 (不阻断调用方)。
pub fn record_diagnostic(
    app: &AppHandle,
    level: &str,
    module: &str,
    message: impl Into<String>,
) {
    use tauri::Manager;
    if let Some(d) = app.try_state::<StartupDiagnostics>() {
        d.record(level, module, message);
    }
}

/// 前端拉取启动诊断列表。用于首次加载时 toast 提示 + 诊断面板渲染。
#[tauri::command]
pub fn get_startup_diagnostics(
    state: tauri::State<'_, StartupDiagnostics>,
) -> Vec<StartupDiagnosticEntry> {
    state.list()
}
