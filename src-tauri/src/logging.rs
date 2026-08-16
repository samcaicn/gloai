// Copyright (c) 2026 MeeJoy
//
// File-based logger that writes to `tupai.log` next to the running
// `tupai.exe` (so users can grab the log without hunting through
// `%APPDATA%`). We also install a panic hook that writes the panic
// info + backtrace to `tupai-panic.log` in the same folder.
//
// Why hand-rolled instead of `env_logger` / `simplelog`? Two reasons:
//   1. We want the log next to the binary, not under
//      `%APPDATA%\tupAI\logs`. The packaged NSIS installer drops
//      `tupai.exe` in `C:\Program Files\tupAI\` and the user
//      expects `tupai.log` to land right there.
//   2. The `log` crate is already in the dependency graph; the
//      only thing we need to add is this small file and one
//      `set_logger` call in `lib.rs::run()`.
//
// `windows_subsystem = "windows"` in `main.rs` means stdout is
// detached — we MUST persist to a file, the user can't see a
// terminal. This module is the only place that does that.

use std::fs::{File, OpenOptions};
use std::io::Write;
use std::path::PathBuf;
use std::sync::{Mutex, OnceLock};

use chrono::Local;

pub struct FileLogger {
    file: Mutex<Option<File>>,
    /// Public for `tupai_emit_log` so it can prepend a header on
    /// webview-side messages without re-resolving the path.
    pub path: PathBuf,
}

impl FileLogger {
    /// Resolve the log file path. Try next-to-binary first (matches
    /// the documented "tupai.log beside tupai.exe" contract), then
    /// fall back to `%LOCALAPPDATA%\ai.tupai.desktop\tupai.log` if
    /// the install directory isn't writable — NSIS drops the app
    /// into `C:\Program Files\Trace Auto\`, which non-admin
    /// processes can't write to. Without the fallback the logger
    /// silently dropped every line and there was no way to diagnose
    /// runtime issues on user machines.
    ///
    /// Result is cached in a `OnceLock` after the first call — previously
    /// `write_external` re-resolved the path on every log line (calling
    /// `current_exe()` + an `OpenOptions::open` writability probe each
    /// time), which on per-machine Windows installs hit the read-only
    /// `C:\Program Files\` path on every call and wasted ~50µs/line.
    pub fn path() -> PathBuf {
        static LOG_PATH: OnceLock<PathBuf> = OnceLock::new();
        LOG_PATH.get_or_init(resolve_log_path).clone()
    }

    pub fn init() -> std::io::Result<()> {
        let path = Self::path();
        let file = OpenOptions::new()
            .create(true)
            .append(true)
            .open(&path)?;
        // Single global instance — `log::set_logger` requires
        // `'static + Send + Sync` and we want every log line from
        // every thread (Tauri main, axum workers, etc.) to land in
        // the same file.
        let logger = Box::leak(Box::new(FileLogger {
            file: Mutex::new(Some(file)),
            path: path.clone(),
        }));
        // `set_logger` is idempotent in the sense that calling it
        // twice with different loggers panics; we never call it
        // from anywhere else.
        if let Err(e) = log::set_logger(logger) {
            eprintln!("[tupai] failed to set file logger: {e}");
        }
        // `Trace` in dev for maximum visibility (user requested "all logs"),
        // `Debug` in release (enough for production diagnosis without spam).
        #[cfg(debug_assertions)]
        log::set_max_level(log::LevelFilter::Trace);
        #[cfg(not(debug_assertions))]
        log::set_max_level(log::LevelFilter::Debug);

        // Stamp the file with a session marker so it is easy to
        // tell two consecutive runs apart when reading.
        // Mutex 中毒时用 `into_inner` 恢复内部值, 避免 `.expect()` 在
        // 中毒路径上触发二次 panic (release 模式 panic=abort 会直接闪退)。
        let mut g = match logger.file.lock() {
            Ok(g) => g,
            Err(poisoned) => {
                eprintln!(
                    "[tupai] logger mutex poisoned during init, recovering: {:?}",
                    poisoned
                );
                poisoned.into_inner()
            }
        };
        if let Some(f) = g.as_mut() {
            let _ = writeln!(
                f,
                "---- session start @ {} (pid={}) ----",
                Local::now().format("%Y-%m-%d %H:%M:%S%.3f"),
                std::process::id()
            );
            let _ = f.flush();
        }
        eprintln!("[tupai] logging to {}", logger.path.display());
        Ok(())
    }

    /// Append a single line to the same file. Used by the
    /// `tupai_emit_log` Tauri command (so the webview can
    /// forward `console.error` and unhandledrejection events
    /// even when the diag overlay isn't visible).
    pub fn write_external(level: &str, target: &str, msg: &str) {
        let formatted = format!(
            "{} [{}] [{}] {}",
            Local::now().format("%Y-%m-%d %H:%M:%S%.3f"),
            level,
            target,
            msg
        );
        #[cfg(debug_assertions)]
        {
            eprintln!("{}", formatted);
        }
        let path = Self::path();
        let Ok(mut f) = OpenOptions::new().create(true).append(true).open(&path) else {
            return;
        };
        let _ = writeln!(f, "{}", formatted);
    }
}

impl log::Log for FileLogger {
    fn enabled(&self, m: &log::Metadata) -> bool {
        // Dev: allow Trace; Release: cap at Debug.
        #[cfg(debug_assertions)]
        {
            m.level() <= log::Level::Trace
        }
        #[cfg(not(debug_assertions))]
        {
            m.level() <= log::Level::Debug
        }
    }
    fn log(&self, record: &log::Record) {
        if !self.enabled(record.metadata()) {
            return;
        }
        // Suppress noisy dependency trace/debug logs (h2, hyper, reqwest, etc.)
        // so application-level logs and `[webview]` frontend logs stay readable.
        let target = record.target();
        const NOISY_TARGETS: [&str; 12] = [
            "h2", "hyper", "reqwest", "tracing", "rustls", "tokio_util",
            "hyper_util", "tungstenite", "async_tungstenite", "serde_json",
            "serde", "mio",
        ];
        if NOISY_TARGETS.iter().any(|t| target == *t || target.starts_with(&format!("{}::", t)))
            && record.level() > log::Level::Warn {
                return;
            }
        let formatted = format!(
            "{} [{}] [{}] {}",
            Local::now().format("%Y-%m-%d %H:%M:%S%.3f"),
            record.level(),
            record.target(),
            record.args()
        );
        // In dev builds, also print to stderr so `tauri dev` terminal shows logs.
        // Release builds use windows_subsystem = "windows" (no stdout/stderr),
        // so file-only is correct there.
        #[cfg(debug_assertions)]
        {
            eprintln!("{}", formatted);
        }
        let mut guard = match self.file.lock() {
            Ok(g) => g,
            Err(_) => return,
        };
        if let Some(f) = guard.as_mut() {
            let _ = writeln!(f, "{}", formatted);
            let _ = f.flush();
        }
    }
    fn flush(&self) {
        if let Ok(mut g) = self.file.lock() {
            if let Some(f) = g.as_mut() {
                let _ = f.flush();
            }
        }
    }
}

/// Resolve the regular log path (next-to-binary, fallback to LOCALAPPDATA).
/// Extracted from `FileLogger::path` so the `OnceLock` cache can hold a
/// `fn() -> PathBuf` without borrowing the struct.
fn resolve_log_path() -> PathBuf {
    let beside_exe = std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|p| p.to_path_buf()))
        .unwrap_or_else(|| PathBuf::from("."))
        .join("tupai.log");
    // Probe writability by opening for append (creates if missing).
    // `OpenOptions::open` errors on missing file, so use the same
    // create+append flags as `init` and just check the result.
    if OpenOptions::new()
        .create(true)
        .append(true)
        .open(&beside_exe)
        .is_ok()
    {
        return beside_exe;
    }
    // Fallback: Local AppData (always writable by the current user).
    if let Some(local) = std::env::var_os("LOCALAPPDATA") {
        let dir = PathBuf::from(local).join("ai.tupai.desktop");
        let _ = std::fs::create_dir_all(&dir);
        return dir.join("tupai.log");
    }
    beside_exe
}

/// Resolve a writable file path for crash/panic logs. Try
/// next-to-binary first (matches the documented "log beside
/// tupai.exe" contract) and fall back to `%LOCALAPPDATA%` when
/// the install directory isn't writable — NSIS drops the app
/// into `C:\Program Files\Trace Auto\` which non-admin processes
/// can't write to, and without the fallback the panic log is
/// silently dropped (the user only sees a brief window flash with
/// no way to diagnose the crash).
///
/// Cached in `OnceLock` — `install_panic_hook` is called once at startup,
/// but the hook closure may call this multiple times if multiple panics
/// fire in quick succession (e.g. cascading panic-in-panic).
fn panic_log_path() -> PathBuf {
    static PANIC_PATH: OnceLock<PathBuf> = OnceLock::new();
    PANIC_PATH
        .get_or_init(|| {
            if let Some(beside_exe) = std::env::current_exe()
                .ok()
                .and_then(|p| p.parent().map(|p| p.to_path_buf()))
            {
                let candidate = beside_exe.join("tupai-panic.log");
                if OpenOptions::new()
                    .create(true)
                    .append(true)
                    .open(&candidate)
                    .is_ok()
                {
                    return candidate;
                }
            }
            if let Some(local) = std::env::var_os("LOCALAPPDATA") {
                let dir = PathBuf::from(local).join("ai.tupai.desktop");
                let _ = std::fs::create_dir_all(&dir);
                return dir.join("tupai-panic.log");
            }
            PathBuf::from("tupai-panic.log")
        })
        .clone()
}

/// Resolve a writable path for the **unbuffered** startup-marker
/// log. Used by `startup_marker!()` to write a one-line marker
/// after every major Tauri init step, flushing to disk after
/// every line so a native crash (process killed by AV / WebView2
/// abort / segfault in the C++ runtime) still leaves a forensic
/// trail pointing to the last successful stage.
///
/// We can't use the regular `tupai.log` here because
/// `FileLogger` is a `log::Log` impl that holds a `Mutex<Option<File>>`
/// — if the process dies inside the webview2 init code the
/// mutex guard is never released and the buffered line is never
/// flushed. A standalone file opened/closed per write is the
/// only reliable post-mortem channel.
///
/// Cached in `OnceLock` — `write_startup_marker` is called dozens of times
/// during boot, and each call previously re-resolved the path.
fn startup_marker_path() -> PathBuf {
    static STARTUP_PATH: OnceLock<PathBuf> = OnceLock::new();
    STARTUP_PATH
        .get_or_init(|| {
            if let Some(beside_exe) = std::env::current_exe()
                .ok()
                .and_then(|p| p.parent().map(|p| p.to_path_buf()))
            {
                let candidate = beside_exe.join("tupai-startup.log");
                if OpenOptions::new()
                    .create(true)
                    .append(true)
                    .open(&candidate)
                    .is_ok()
                {
                    return candidate;
                }
            }
            if let Some(local) = std::env::var_os("LOCALAPPDATA") {
                let dir = PathBuf::from(local).join("ai.tupai.desktop");
                let _ = std::fs::create_dir_all(&dir);
                return dir.join("tupai-startup.log");
            }
            PathBuf::from("tupai-startup.log")
        })
        .clone()
}

/// Write a single line to `tupai-startup.log`, flushing after
/// every line. **Always use this** (not `log::info!` /
/// `FileLogger::write_external`) for the early-startup marker
/// chain in `lib.rs::run()` — those callsites need to survive
/// a native crash in Tauri / WebView2 init where the buffered
/// logger is destroyed before its flush.
pub fn write_startup_marker(stage: &str) {
    let line = format!(
        "{} [{}] {}",
        Local::now().format("%Y-%m-%d %H:%M:%S%.3f"),
        std::process::id(),
        stage
    );
    let path = startup_marker_path();
    if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(&path) {
        let _ = writeln!(f, "{}", line);
        let _ = f.flush();
    }
    // Mirror to the regular log for in-app diagnostics.
    log::info!("[startup-stage] {}", stage);
}

/// Write panics to `tupai-panic.log`. The default panic hook still
/// fires (so the user sees the standard Windows "this app has
/// stopped working" dialog in dev); in release builds
/// (`windows_subsystem = "windows"`) the dialog is suppressed but
/// the file is still written.
pub fn install_panic_hook() {
    let default_hook = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        let path = panic_log_path();
        if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(&path) {
            let _ = writeln!(
                f,
                "==== panic @ {} (pid={}) ====",
                Local::now().format("%Y-%m-%d %H:%M:%S%.3f"),
                std::process::id()
            );
            let _ = writeln!(f, "log path = {}", path.display());
            let _ = writeln!(f, "{info:#?}");
            // `force_capture` ensures we always get a backtrace in the
            // panic log regardless of RUST_BACKTRACE env var.
            let _ = writeln!(f, "backtrace:\n{}", std::backtrace::Backtrace::force_capture());
            let _ = writeln!(f);
            let _ = f.flush();
        }
        default_hook(info);
    }));
}
