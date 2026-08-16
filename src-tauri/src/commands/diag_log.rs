// Copyright (c) 2026 MeeJoy
//
// Webview → Rust log forwarder. The bundled `main.jsx` `diagLog`
// function calls this Tauri command so JavaScript-side errors
// (uncaught exceptions, failed `console.error` calls, React
// render failures) end up in the same `tupai.log` next to the
// binary that the Rust side writes to. This means a single
// `tupai.log` covers both halves of the app — useful when the
// user reports "新建笔记 / 发起会话 挂" and we need the matching
// stack trace without asking them to also open DevTools and
// grab the JS console.
//
// The command is intentionally fire-and-forget: it returns `()`
// and never throws, so a logging failure can never break the
// webview's own error path. We swallow I/O errors and just
// `log::warn!` if the file is unwritable for some reason.

use serde::Deserialize;

#[derive(Deserialize)]
pub struct EmitLogArgs {
    /// One of "info" / "warn" / "error" / "debug" (matches the
    /// webview's diag severity). Falls back to "info" on
    /// unexpected values.
    #[serde(default)]
    pub level: Option<String>,
    /// Caller tag — usually `main.jsx`, `chat`, `notebook`, etc.
    /// so we can grep for which subsystem the error came from.
    #[serde(default)]
    pub target: Option<String>,
    /// The message body. We don't try to pretty-print it; the
    /// webview already stringifies objects before passing them
    /// in (because Tauri command args are JSON, not arbitrary
    /// JS values).
    #[serde(default)]
    pub message: Option<String>,
}

#[tauri::command]
pub fn tupai_emit_log(args: EmitLogArgs) {
    let level = args.level.as_deref().unwrap_or("info").to_uppercase();
    let target = args.target.unwrap_or_else(|| "webview".to_string());
    let message = args.message.unwrap_or_default();

    // Mirror to the Rust `log` crate so anything that has hooked
    // `log::Log` (i.e. our `FileLogger`) gets the same line.
    let truncated: String = message.chars().take(8000).collect();
    // We use `log::log!` instead of the `error!/warn!/info!/debug!`
    // sugar macros because the latter hard-code their own target
    // (the current module path), whereas we want the target
    // string to reflect the JS call site (e.g. "main.jsx",
    // "chat", "notebook"). `log!` is the only variant that takes
    // a runtime `target:` expression.
    let level_enum = match level.as_str() {
        "ERROR" => log::Level::Error,
        "WARN" => log::Level::Warn,
        "DEBUG" => log::Level::Debug,
        _ => log::Level::Info,
    };
    log::log!(target: target.as_str(), level_enum, "{}", truncated);
}
