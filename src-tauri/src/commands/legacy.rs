// Copyright (c) 2026 tupAI
//
// Surface is reserved for the main thread; allow dead_code until wired up.
#![allow(dead_code)]

use rusqlite::{params, Connection};
use serde::{Deserialize, Serialize};
use once_cell::sync::Lazy;
use std::collections::{HashMap, HashSet};
use std::fs;
use std::io::Write;
use std::net::{TcpStream, ToSocketAddrs};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::Mutex as StdMutex;
use std::time::{SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Emitter, Manager};
use walkdir::WalkDir;

/// Windows process-creation flags applied to every child process the
/// Rust side of tupAI spawns. Without these, `Command::new("hermes.exe")`
/// or `Command::new("cmd.exe")` flashes a console window for a few
/// hundred milliseconds — visible to the user as a black/white
/// "PowerShell 窗口" popping up over the WebView. The flags together
/// mean:
///
///   * `CREATE_NO_WINDOW` (0x0800_0000)  — no console window is
///     created for the new process. PyInstaller-frozen Hermes and
///     cmd/pwsh-launched commands both stop flashing.
///   * `DETACHED_PROCESS` (0x0000_0008)  — the child does not share
///     the parent's console; signals (Ctrl+C) are not propagated.
///     Required for the gateway to outlive the parent shell.
///   * `CREATE_NEW_PROCESS_GROUP` (0x0000_0200) — own process group
///     so the gateway can be killed by group without affecting us.
///
/// `0x0000_0008 | 0x0800_0000 | 0x0000_0200` = 0x0800_0208.
/// On non-Windows targets this constant is dead (the cfg block below
/// gates its only use), so `#[allow(dead_code)]` is unnecessary.
#[cfg(target_os = "windows")]
const WINDOWS_NO_WINDOW_FLAGS: u32 = 0x0800_0000 | 0x0000_0008 | 0x0000_0200;

/// Apply `CREATE_NO_WINDOW | DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP`
/// to a `Command` on Windows so the child process never opens a console
/// window. No-op on macOS / Linux. Use this on *every* place that
/// shells out to `hermes`, `cmd.exe`, `powershell.exe`, or any other
/// command that might be a console-mode binary — otherwise the user
/// sees a black PowerShell/cmd window pop up over the WebView.
///
/// This used to be sprinkled inline at half a dozen call sites and
/// was missing from `run_login_shell_command` (the `hermes gateway
/// start` / `hermes config set` paths), which is why the bundled
/// sidecar was flashing a PowerShell window on Windows.
pub fn apply_no_window(command: &mut Command) -> &mut Command {
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(WINDOWS_NO_WINDOW_FLAGS);
    }
    command
}

/// Same as [`apply_no_window`] but for `tokio::process::Command`.
/// `tokio::process::Command` does not expose `creation_flags` directly,
/// but it does have `as_std_mut()` which lets us set the flag on the
/// inner `std::process::Command`. The flag persists in the inner
/// `Command` and tokio reads it back at `spawn()` time, so the child
/// process gets the same `CREATE_NO_WINDOW` treatment.
///
/// Used by:
///   * `commands::hardware_id`     (PowerShell `Get-CimInstance` call)
pub fn apply_no_window_tokio(
    command: &mut tokio::process::Command,
) -> &mut tokio::process::Command {
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        command
            .as_std_mut()
            .creation_flags(WINDOWS_NO_WINDOW_FLAGS);
    }
    command
}

const APP_DB_FILENAME: &str = "tupai.db";
const LEGACY_APP_DB_FILENAME: &str = "hermes-desktop-lite.db";

/// 环境变量名：用于在子进程启动时把数据目录切换到指定目录
/// (e.g. 用户在工作目录悬浮窗里点了「打开文件夹」)。
const OVERRIDE_DATA_DIR_ENV: &str = "TUPAI_DATA_DIR";

/// 解析当前进程应使用的应用数据目录。优先读取 `TUPAI_DATA_DIR`
/// 环境变量（由 `launch_new_instance` 命令在派生新进程时注入），
/// 否则回退到 Tauri 默认的 `app_data_dir()`。
fn resolve_app_data_dir(app: &tauri::AppHandle) -> Result<std::path::PathBuf, String> {
    if let Some(custom) = std::env::var_os(OVERRIDE_DATA_DIR_ENV) {
        let raw = custom.to_string_lossy().to_string();
        let trimmed = raw.trim();
        if !trimmed.is_empty() {
            let path = std::path::PathBuf::from(trimmed);
            if !path.exists() {
                std::fs::create_dir_all(&path).map_err(|e| {
                    format!(
                        "Failed to create override data dir {}: {}",
                        path.display(),
                        e
                    )
                })?;
            }
            return Ok(path);
        }
    }
    app.path()
        .app_data_dir()
        .map_err(|e| format!("Failed to get app_data_dir: {:?}", e))
}

#[derive(Serialize, Deserialize, Clone)]
pub struct ChatMessage {
    pub role: String,
    pub content: String,
}

#[derive(Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ChatToolEvent {
    pub request_id: Option<String>,
    pub phase: String,
    pub name: Option<String>,
    pub call_id: Option<String>,
    pub arguments: Option<String>,
    pub output: Option<String>,
    pub status: Option<String>,
}

#[derive(Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct TerminalOpenResult {
    pub session_id: String,
}

#[derive(Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct TerminalOutputEvent {
    pub session_id: String,
    pub data: String,
}

#[derive(Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct TerminalExitEvent {
    pub session_id: String,
}

#[derive(Serialize, Deserialize)]
pub struct ChatResponse {
    pub content: String,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct GatewayInfo {
    pub target: String,
    pub version: Option<String>,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct HermesVersionInfo {
    pub installed_display: Option<String>,
    pub installed_version: Option<String>,
    pub latest_tag: Option<String>,
    pub latest_name: Option<String>,
    pub latest_display: Option<String>,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct HermesUpdateResult {
    pub success: bool,
    pub stdout: String,
    pub stderr: String,
}

static ACTIVE_CHAT_STREAM_ABORTS: Lazy<StdMutex<HashMap<String, tokio::sync::oneshot::Sender<()>>>> =
    Lazy::new(|| StdMutex::new(HashMap::new()));

/// Drop guard：确保 `chat_stream` 任意 panic / 提前 return 都会把
/// `requestId` 从全局 map 中清掉，避免 oneshot 通道永久驻留。
struct ChatStreamAbortGuard {
    request_id: String,
}

impl Drop for ChatStreamAbortGuard {
    fn drop(&mut self) {
        if let Ok(mut active_aborts) = ACTIVE_CHAT_STREAM_ABORTS.lock() {
            active_aborts.remove(&self.request_id);
        }
    }
}

fn parse_installed_hermes_version(output: &str) -> (Option<String>, Option<String>) {
    let first_line = output.lines().next().map(|line| line.trim().to_string());
    let installed_version = first_line.as_ref().and_then(|line| {
        let marker = "Hermes Agent ";
        line.strip_prefix(marker).map(|rest| {
            if let Some((version, _)) = rest.split_once(' ') {
                version.to_string()
            } else {
                rest.to_string()
            }
        })
    });

    (first_line, installed_version)
}

fn strip_hermes_prefix(value: &str) -> String {
    value
        .trim()
        .strip_prefix("Hermes Agent ")
        .unwrap_or(value.trim())
        .to_string()
}

fn resolve_windows_shell_with<F>(shell_env: Option<&str>, path_exists: F) -> String
where
    F: Fn(&Path) -> bool,
{
    // Honour a user-supplied override first.
    let env_shell = shell_env
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(PathBuf::from);

    if let Some(shell) = env_shell.as_ref() {
        if shell.is_absolute() && path_exists(shell.as_path()) {
            return shell.display().to_string();
        }
    }

    // Windows. Prefer PowerShell 7 (pwsh.exe) over Windows PowerShell 5
    // (powershell.exe), and fall back to cmd.exe. We deliberately use the
    // base file name (without path) so PATH resolution happens at spawn
    // time, matching the original Unix shell resolution contract.
    for candidate in ["pwsh.exe", "powershell.exe", "cmd.exe"] {
        if path_exists(Path::new(candidate)) {
            return candidate.to_string();
        }
    }

    // 之前这里会把 env_shell 原样回退，但当 env_shell 指向
    // 不存在的路径（如 git-bash 互操作时 $SHELL=/usr/bin/bash
    // 在 stock Windows 上不存在）时，spawn 会失败报
    // "系统找不到指定的文件"。这里只接受"绝对路径 + 确实存在"
    // 的 env_shell，否则一律回到 cmd.exe。
    "cmd.exe".to_string()
}

fn resolve_windows_shell() -> String {
    // Honour $COMSPEC on Windows; otherwise fall through to the candidate
    // list. (The original TypeScript module on the gloai side used
    // $SHELL — that variable is undefined on stock Windows installs.)
    let shell_env = std::env::var("COMSPEC")
        .ok()
        .or_else(|| std::env::var("SHELL").ok());
    resolve_windows_shell_with(shell_env.as_deref(), |path| {
        // For bare executable names (no path component), defer to the
        // platform's PATH lookup via `which`-like resolution. ConPTY /
        // CreateProcessW will resolve them when we hand the command off.
        if path.components().count() == 1 {
            std::env::var("PATH")
                .ok()
                .and_then(|paths| {
                    std::env::split_paths(&paths).find(|dir| {
                        let mut p = dir.join(path);
                        if p.extension().is_none() {
                            p.set_extension("exe");
                        }
                        p.exists()
                    })
                })
                .is_some()
                || path_exists_via_cmd(path)
        } else {
            path.exists()
        }
    })
}

fn path_exists_via_cmd(path: &Path) -> bool {
    // Last-ditch: ask cmd.exe to locate the executable via its PATHEXT-aware
    // search. Cheap and avoids re-implementing the algorithm in Rust.
    let mut cmd = Command::new("where.exe");
    apply_no_window(&mut cmd);
    cmd.arg(path)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .stdin(Stdio::null())
        .output()
        .map(|out| out.status.success())
        .unwrap_or(false)
}

/// Locate a real `cmd.exe` (Windows command interpreter) we can
/// invoke with `/C <command>`. The historical `run_login_shell_command`
/// trusted `$COMSPEC` / `$SHELL`, which on modern Windows installs
/// often points at PowerShell; PowerShell's `-Command` tokeniser then
/// re-interprets our `cmd.exe`-flavoured quoting (`"path with
/// space\tool.exe"`) and strips the surrounding double quotes,
/// turning the command into a literal string and giving the
/// `not recognised as an internal or external command` error we
/// hit in v0.1.0-tupai.
///
/// Returns `Some(path)` when one of the well-known `cmd.exe`
/// locations exists, `None` otherwise (caller falls back to
/// `resolve_windows_shell`).
fn resolve_cmd_exe() -> Option<String> {
    // 1. `%COMSPEC%` when set to an actual cmd.exe (i.e. the path
    //    resolves to `...\cmd.exe`, not `...\powershell.exe`).
    if let Ok(value) = std::env::var("COMSPEC") {
        let trimmed = value.trim();
        let lower = trimmed.to_lowercase();
        if lower.ends_with("cmd.exe") {
            let p = PathBuf::from(trimmed);
            if p.is_file() {
                return Some(p.to_string_lossy().to_string());
            }
        }
    }
    // 2. `%SystemRoot%` — present on every Windows install since
    //    NT 4.0, even containers / nano-server variants that
    //    strip PowerShell.
    if let Ok(root) = std::env::var("SystemRoot") {
        let candidate = PathBuf::from(&root).join("System32").join("cmd.exe");
        if candidate.is_file() {
            return Some(candidate.to_string_lossy().to_string());
        }
    }
    // 3. Hard-coded fallbacks for the rare cases where both env
    //    vars are stripped (e.g. the binary is launched from a
    //    service with a custom environment block).
    for raw in [
        r"C:\Windows\System32\cmd.exe",
        r"C:\Windows\SysWOW64\cmd.exe",
        r"C:\WinNT\System32\cmd.exe",
    ] {
        let p = PathBuf::from(raw);
        if p.is_file() {
            return Some(p.to_string_lossy().to_string());
        }
    }
    None
}

/// Rewrite a `cmd.exe`-flavoured command line for PowerShell's
/// `-Command` mode. The two shells tokenise completely
/// differently, and the cheapest way to make sure a Windows path
/// like `C:\Users\alice\node.exe` survives intact is:
///
///   * peel the command into tokens, respecting `cmd`'s `"..."`
///     quoting and backslash escaping rules (handled by
///     `shell_quote` upstream);
///   * rebuild it as a PowerShell call-expression: `& 'tok1'
///     'tok2' ... 'tokN'`. PowerShell's single-quoted strings
///     are literal — no `$variable` or escape-sequence
///     interpretation — so Windows backslashes round-trip
///     cleanly. The leading `&` is the call operator, which
///     tells PowerShell to invoke the next token as a command
///     rather than evaluating it as an expression (e.g. a
///     bare `C:\Users\...` would otherwise be parsed as a
///     drive-relative path lookup).
fn rewrite_command_for_powershell(command: &str) -> String {
    let tokens = tokenize_cmd_line(command);
    if tokens.is_empty() {
        return "exit 1".to_string();
    }
    let mut out = String::with_capacity(command.len() + 4);
    out.push_str("& ");
    for (idx, token) in tokens.iter().enumerate() {
        if idx > 0 {
            out.push(' ');
        }
        out.push('\'');
        // PowerShell single-quoted strings escape an internal
        // single quote by doubling it: `'it''s'`. The
        // tokeniser never produces backslashes here (it
        // already resolved them as escapes), so we only need
        // to handle the quote-doubling rule.
        for ch in token.chars() {
            if ch == '\'' {
                out.push_str("''");
            } else {
                out.push(ch);
            }
        }
        out.push('\'');
    }
    out
}

/// Minimal `cmd.exe` tokeniser. Honours:
///   * `"..."` quoted runs (literal — backslashes preserved
///     verbatim, no special handling inside the quotes);
///   * backslash-escaped characters outside quotes (each `\X`
///     yields `X`);
///   * whitespace as the inter-token separator.
///
/// This is intentionally less powerful than the full
/// `CommandLineToArgvW` algorithm — we only need to recover
/// the original arguments the caller passed to
/// `shell_quote`, and that helper does *not* produce any
/// backslashes that escape an `&` / `|` / `>`. (The few
/// shell-metacharacter commands we run, such as
/// `start "" /B ... > log 2>&1`, are deliberately kept on
/// the `cmd.exe` branch — `resolve_cmd_exe()` returns
/// `Some` for every normal Windows install, so we only hit
/// the PowerShell fallback on machines with no cmd.exe at
/// all, where the user can install PowerShell 7 and the
/// shell-builtin commands won't matter.)
fn tokenize_cmd_line(command: &str) -> Vec<String> {
    let mut tokens: Vec<String> = Vec::new();
    let mut current = String::new();
    let mut in_quotes = false;
    let mut chars = command.chars().peekable();
    while let Some(c) = chars.next() {
        match c {
            '"' => {
                in_quotes = !in_quotes;
            }
            '\\' if !in_quotes => {
                // Outside quotes, `\X` is a literal `X`
                // (cmd.exe's quoting helper `shell_quote`
                // doesn't emit any backslashes that aren't
                // immediately followed by `"` or `\`).
                if let Some(&next) = chars.peek() {
                    if next == '"' || next == '\\' {
                        current.push(next);
                        chars.next();
                    } else {
                        current.push('\\');
                    }
                } else {
                    current.push('\\');
                }
            }
            c if c.is_whitespace() && !in_quotes => {
                if !current.is_empty() {
                    tokens.push(std::mem::take(&mut current));
                }
            }
            c => current.push(c),
        }
    }
    if !current.is_empty() {
        tokens.push(current);
    }
    tokens
}

/// Cross-platform shell picker used by the in-app terminal. Picks a
/// platform-appropriate interactive shell that portable-pty / ConPTY
/// can spawn.
fn resolve_interactive_shell() -> String {
    #[cfg(target_os = "windows")]
    {
        resolve_windows_shell()
    }
    #[cfg(not(target_os = "windows"))]
    {
        resolve_unix_shell()
    }
}

#[allow(dead_code)] // Unix-only path-resolution helper; the cfg-gated callers in `resolve_interactive_shell` / `run_login_shell_command` are stripped on Windows builds, but the function is also referenced by the test module below (`#[cfg(test)]`).
fn resolve_unix_shell_with<F>(shell_env: Option<&str>, path_exists: F) -> String
where
    F: Fn(&Path) -> bool,
{
    let env_shell = shell_env
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(PathBuf::from);

    if let Some(shell) = env_shell.as_ref() {
        if shell.is_absolute() && path_exists(shell.as_path()) {
            return shell.display().to_string();
        }
    }

    for candidate in [
        "/bin/bash",
        "/usr/bin/bash",
        "/bin/zsh",
        "/usr/bin/zsh",
        "/bin/sh",
        "/usr/bin/sh",
    ] {
        let path = Path::new(candidate);
        if path_exists(path) {
            return candidate.to_string();
        }
    }

    // 之前把 env_shell 原样 fallback；env_shell 不存在时 spawn 会失败。
    // 同上：只接受"绝对路径 + 确实存在"的 env_shell，否则回到 /bin/sh。
    "/bin/sh".to_string()
}

#[allow(dead_code)] // Unix-only shell resolver; the cfg-gated callers in `resolve_interactive_shell` / `run_login_shell_command` are stripped on Windows builds, leaving only the test-only `resolve_unix_shell_with` reachable (and that's also `#[cfg(test)]`).
fn resolve_unix_shell() -> String {
    let shell_env = std::env::var("SHELL").ok();
    resolve_unix_shell_with(shell_env.as_deref(), Path::exists)
}

/// Cross-platform shell executor used to launch Hermes CLI side commands.
///
/// On Unix we keep the historical `login` shell semantics (`bash -lc`) so
/// that PATH and the user's normal rc files stay available. On Windows we
/// route through `cmd.exe /C <command>` (always present) or, when
/// available, PowerShell — `bash -lc` would simply fail on stock Windows.
pub fn run_login_shell_command(command: &str) -> Result<std::process::Output, String> {
    #[cfg(target_os = "windows")]
    {
        // Resolve a real `cmd.exe` rather than trusting `$COMSPEC` /
        // `$SHELL` to point at PowerShell. The commands we run
        // through here are built with `cmd.exe`-flavoured quoting
        // (`start "" /B`, `> log 2>&1`, plain `"path with
        // space\tool.exe" "arg"`), and PowerShell's `-Command`
        // mode re-parses them with its own tokeniser — which
        // routinely drops the surrounding `"..."` on a path that
        // contains backslashes (e.g. `"\Users\User\..."` ends up
        // seen as a literal escape sequence, not a quoted token),
        // giving the classic `'node.exe' is not recognised as an
        // internal or external command` error.
        //
        // We try a few well-known locations and then fall back to
        // `resolve_windows_shell()` for exotic setups (GitHub
        // Actions runners, containers without `%SystemRoot%`,
        // etc.). When that also lands on PowerShell, the legacy
        // PowerShell branch below kicks in for the few cases where
        // we genuinely need it (interactive `where` lookups, etc.).
        let cmd_path = resolve_cmd_exe().unwrap_or_else(resolve_windows_shell);
        let lower = cmd_path.to_lowercase();
        if lower.ends_with("pwsh.exe") || lower.ends_with("powershell.exe") {
            // PowerShell path: rewrite the command as a `&` call
            // with single-quoted arguments. PowerShell's single-
            // quote mode doesn't interpret escape sequences, so a
            // Windows path like `C:\Users\alice\node.exe` survives
            // intact. The `&` operator is what tells PowerShell to
            // treat the next token as a *command* rather than an
            // expression to evaluate.
            let rewritten = rewrite_command_for_powershell(command);
            let mut cmd = Command::new(&cmd_path);
            apply_no_window(&mut cmd);
            return cmd
                .args(["-NoProfile", "-NonInteractive", "-Command", &rewritten])
                .output()
                .map_err(|e| {
                    format!("Failed to run `{}` with {}: {}", command, cmd_path, e)
                });
        }
        // cmd.exe /C <command> — without CREATE_NO_WINDOW the
        // bundled sidecar (or any shell-launched hermes subcommand)
        // pops a black console for a few hundred ms.
        let mut cmd = Command::new(&cmd_path);
        apply_no_window(&mut cmd);
        cmd.args(["/C", command])
            .output()
            .map_err(|e| format!("Failed to run `{}` with {}: {}", command, cmd_path, e))
    }
    #[cfg(not(target_os = "windows"))]
    {
        let shell = resolve_unix_shell();
        Command::new(&shell)
            .args(["-lc", command])
            .output()
            .map_err(|e| format!("Failed to run `{}` with {}: {}", command, shell, e))
    }
}

fn extract_gateway_version(
    json: Option<&serde_json::Value>,
    headers: &reqwest::header::HeaderMap,
) -> Option<String> {
    let from_json = json.and_then(|value| {
        value
            .get("version")
            .or_else(|| value.get("agent_version"))
            .or_else(|| value.get("gateway_version"))
            .or_else(|| value.get("app").and_then(|app| app.get("version")))
            .or_else(|| {
                value
                    .get("data")
                    .and_then(|data| data.get(0))
                    .and_then(|item| item.get("version"))
            })
            .and_then(|v| v.as_str())
            .map(|v| v.to_string())
    });

    if from_json.is_some() {
        return from_json;
    }

    headers
        .get("x-hermes-version")
        .or_else(|| headers.get("x-agent-version"))
        .or_else(|| headers.get("x-gateway-version"))
        .and_then(|v| v.to_str().ok())
        .map(|v| v.to_string())
        .or_else(|| {
            headers
                .get("server")
                .and_then(|v| v.to_str().ok())
                .and_then(|server| {
                    let lower = server.to_lowercase();
                    if lower.contains("hermes") {
                        Some(server.to_string())
                    } else {
                        None
                    }
                })
        })
}

fn default_gateway_host() -> String {
    "127.0.0.1".to_string()
}

fn default_gateway_port() -> u16 {
    8642
}

fn gateway_chat_url(app: &AppHandle) -> String {
    let cfg = load_config_from_disk(app);
    format!(
        "http://{}:{}/v1/chat/completions",
        cfg.gateway_host.trim(),
        cfg.gateway_port
    )
}

fn gateway_responses_url(app: &AppHandle) -> String {
    let cfg = load_config_from_disk(app);
    format!(
        "http://{}:{}/v1/responses",
        cfg.gateway_host.trim(),
        cfg.gateway_port
    )
}

#[tauri::command]
pub fn test_gateway_connection(host: String, port: u16) -> Result<serde_json::Value, String> {
    let host = host.trim();
    if host.is_empty() {
        return Err("Host is empty".to_string());
    }

    let addrs = (host, port)
        .to_socket_addrs()
        .map_err(|e| format!("Unable to resolve {}:{}: {}", host, port, e))?;

    let timeout = std::time::Duration::from_secs(2);
    for addr in addrs {
        if TcpStream::connect_timeout(&addr, timeout).is_ok() {
            return Ok(serde_json::json!({
                "ok": true,
                "target": format!("{}:{}", host, port)
            }));
        }
    }

    Err(format!("Unable to connect to {}:{}", host, port))
}

#[tauri::command]
pub async fn get_gateway_info(host: String, port: u16) -> Result<GatewayInfo, String> {
    let host = host.trim();
    if host.is_empty() {
        return Err("Host is empty".to_string());
    }

    let target = format!("{}:{}", host, port);
    let base_url = format!("http://{}", target);
    let client = reqwest::Client::builder()
        .no_proxy()
        .timeout(std::time::Duration::from_secs(2))
        .build()
        .map_err(|e| e.to_string())?;

    let version_paths = ["/v1/models", "/version", "/health", "/status", "/"];
    let mut version = None;

    for path in version_paths {
        let url = format!("{}{}", base_url, path);
        if let Ok(response) = client.get(&url).send().await {
            let headers = response.headers().clone();
            let json = response.json::<serde_json::Value>().await.ok();
            version = extract_gateway_version(json.as_ref(), &headers);
            if version.is_some() {
                break;
            }
        }
    }

    Ok(GatewayInfo { target, version })
}

#[tauri::command]
pub async fn get_hermes_version_info() -> Result<HermesVersionInfo, String> {
    let installed_output = run_login_shell_command("hermes --version")
        .ok()
        .filter(|output| output.status.success())
        .map(|output| String::from_utf8_lossy(&output.stdout).to_string());

    let (installed_display, installed_version) = installed_output
        .as_deref()
        .map(parse_installed_hermes_version)
        .unwrap_or((None, None));

    let client = reqwest::Client::builder()
        .no_proxy()
        .timeout(std::time::Duration::from_secs(4))
        .build()
        .map_err(|e| e.to_string())?;

    let latest_release = client
        .get("https://api.github.com/repos/NousResearch/hermes-agent/releases/latest")
        .header("User-Agent", "tupai")
        .send()
        .await
        .ok();

    let latest_release_json = if let Some(response) = latest_release {
        response.json::<serde_json::Value>().await.ok()
    } else {
        None
    };

    let latest_tag = latest_release_json
        .as_ref()
        .and_then(|value| value.get("tag_name"))
        .and_then(|value| value.as_str())
        .map(|value| value.to_string());

    let latest_name = latest_release_json
        .as_ref()
        .and_then(|value| value.get("name"))
        .and_then(|value| value.as_str())
        .map(|value| value.to_string());
    let latest_display = latest_name
        .as_deref()
        .map(strip_hermes_prefix)
        .or_else(|| latest_tag.clone());

    Ok(HermesVersionInfo {
        installed_display,
        installed_version,
        latest_tag,
        latest_name,
        latest_display,
    })
}

#[tauri::command]
pub fn update_hermes_agent() -> Result<HermesUpdateResult, String> {
    // The gateway is the in-process axum server (see
    // `hermes::embedded_server`); the agent update flow is not
    // yet implemented as an axum route (e.g.
    // POST /api/v1/agents/update). For now we return a
    // structured "not available" success so the front-end
    // update button does not spin forever trying to spawn a
    // non-existent process.
    Ok(HermesUpdateResult {
        success: false,
        stdout: String::new(),
        stderr: "v5 embedded mode: agent update is not wired up. \
                 The previous `hermes update` shell command is gone \
                 — the gateway is an in-process axum server. \
                 An HTTP route will replace this in a follow-up PR."
            .to_string(),
    })
}

// ========================
// 非流式对话
// ========================
fn resolve_chat_request_model(model: Option<String>) -> String {
    model
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| "hermes-agent".to_string())
}

#[tauri::command]
pub async fn chat(
    app: AppHandle,
    messages: Vec<ChatMessage>,
    model: Option<String>,
) -> Result<ChatResponse, String> {
    let client = reqwest::Client::builder()
        .no_proxy()
        .timeout(std::time::Duration::from_secs(60))
        .build()
        .map_err(|e| e.to_string())?;
    let gateway_url = gateway_chat_url(&app);
    let request_model = resolve_chat_request_model(model);
    let body = serde_json::json!({
        "model": request_model,
        "messages": messages,
        "stream": false
    });
    let res = client
        .post(&gateway_url)
        .json(&body)
        .send()
        .await
        .map_err(|e| {
            let _ = app.emit(
                "chatterror",
                format!("连接失败: {}（目标 {}）", e, gateway_url),
            );
            e.to_string()
        })?;
    if !res.status().is_success() {
        let status = res.status();
        let body = res.text().await.unwrap_or_default();
        // 503 + requires_registration → 结构化错误,前端自动弹
        // JoinCodeModal 后 retry。
        if status.as_u16() == 503 {
            if let Ok(parsed) = serde_json::from_str::<serde_json::Value>(&body) {
                if parsed.get("requires_registration").and_then(|v| v.as_bool()) == Some(true) {
                    return Err(serde_json::json!({
                        "code": "needs_registration",
                        "message": parsed
                            .get("error")
                            .and_then(|v| v.as_str())
                            .unwrap_or("device not registered; bind first"),
                        "register_endpoint": parsed
                            .get("register_endpoint")
                            .and_then(|v| v.as_str())
                            .unwrap_or("/api/v1/clients/register"),
                    })
                    .to_string());
                }
            }
        }
        return Err(format!("Hermes Chat API 请求失败: HTTP {} {}", status, body));
    }
    let json: serde_json::Value = res.json().await.map_err(|e| e.to_string())?;
    let content = json["choices"][0]["message"]["content"]
        .as_str()
        .unwrap_or("")
        .to_string();
    Ok(ChatResponse { content })
}

// ========================
// 流式对话（Phase 1 SSE）
// ========================

/// SSE 流式聊天命令
/// 每次收到一个 token 就通过 app.emit 发送到前端
#[tauri::command]
pub async fn chat_stream(
    app: AppHandle,
    messages: Vec<ChatMessage>,
    previous_response_id: Option<String>,
    replay_history: bool,
    model: Option<String>,
    request_id: Option<String>,
) -> Result<Option<String>, String> {
    use reqwest::Client;
    use tokio::sync::oneshot;
    use tokio::time::Duration;

    let gateway_url = gateway_responses_url(&app);
    let client = Client::builder()
        .no_proxy()
        .timeout(Duration::from_secs(60)) // 连接超时
        .build()
        .map_err(|e| e.to_string())?;
    let (cancel_tx, mut cancel_rx) = oneshot::channel::<()>();
    let active_request_id = request_id
        .clone()
        .unwrap_or_else(|| {
            let now = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map(|duration| duration.as_millis())
                .unwrap_or_else(|error| {
                    log::warn!(
                        "[legacy] SystemTime 早于 UNIX_EPOCH,request_id 退化: {}",
                        error
                    );
                    0
                });
            format!("legacy-{}", now)
        });

    {
        let mut active_aborts = ACTIVE_CHAT_STREAM_ABORTS
            .lock()
            .map_err(|_| "无法锁定聊天取消状态".to_string())?;

        if let Some(previous_abort) = active_aborts.remove(&active_request_id) {
            let _ = previous_abort.send(());
        }

        active_aborts.insert(active_request_id.clone(), cancel_tx);
    }

    // Drop guard：正常返回 / 错误 / panic 路径都会触发清理。
    let _abort_guard = ChatStreamAbortGuard {
        request_id: active_request_id.clone(),
    };

    async fn execute_stream(
        app: &AppHandle,
        client: &Client,
        gateway_url: &str,
        messages: &[ChatMessage],
        previous_response_id: Option<String>,
        replay_history: bool,
        request_model: &str,
        request_id: Option<&str>,
        cancel_rx: &mut oneshot::Receiver<()>,
    ) -> Result<Option<String>, String> {
        use futures::StreamExt;

        let input = if replay_history {
            serde_json::to_value(messages).map_err(|e| e.to_string())?
        } else {
            serde_json::Value::String(
                messages
                    .last()
                    .map(|message| message.content.clone())
                    .unwrap_or_default(),
            )
        };

        let body = serde_json::json!({
            "model": request_model,
            "input": input,
            "previous_response_id": previous_response_id,
            "stream": true
        });

        let res = client
            .post(gateway_url)
            .header("Content-Type", "application/json")
            .json(&body)
            .send()
            .await
            .map_err(|e| format!("连接失败: {}（目标 {}）", e, gateway_url))?;

        if !res.status().is_success() {
            let status = res.status();
            let body = res.text().await.unwrap_or_else(|error| {
                log::warn!(
                    "[legacy] chat_stream: 读取错误响应 body 失败: {}",
                    error
                );
                String::new()
            });
            // 特殊路径:503 + {"requires_registration": true} → 不要
            // 把 body 原样塞进 Err 让前端去解析,直接给结构化错误
            // `needs_registration`,前端拿到后能自动弹 JoinCodeModal
            // 并 retry。
            if status.as_u16() == 503 {
                if let Ok(parsed) = serde_json::from_str::<serde_json::Value>(&body) {
                    if parsed.get("requires_registration").and_then(|v| v.as_bool()) == Some(true) {
                        return Err(serde_json::json!({
                            "code": "needs_registration",
                            "message": parsed
                                .get("error")
                                .and_then(|v| v.as_str())
                                .unwrap_or("device not registered; bind first"),
                            "register_endpoint": parsed
                                .get("register_endpoint")
                                .and_then(|v| v.as_str())
                                .unwrap_or("/api/v1/clients/register"),
                        })
                        .to_string());
                    }
                }
            }
            return Err(format!("Hermes Responses API 请求失败: HTTP {} {}", status, body));
        }

        let mut stream = res.bytes_stream();
        let mut buffer = String::new();
        let mut latest_response_id = None;

        loop {
            let next_chunk = tokio::select! {
                _ = &mut *cancel_rx => {
                    let _ = app.emit("chatdone", serde_json::json!({ "requestId": request_id }));
                    return Ok(latest_response_id);
                }
                chunk = stream.next() => chunk,
            };

            let Some(chunk_result) = next_chunk else {
                break;
            };

            match chunk_result {
                Ok(chunk) => {
                    buffer.push_str(&String::from_utf8_lossy(&chunk));
                    buffer = buffer.replace("\r\n", "\n");

                    while let Some(pos) = buffer.find("\n\n") {
                        let block = buffer[..pos].trim().to_string();
                        buffer.drain(..pos + 2);

                        if block.is_empty() {
                            continue;
                        }

                        let mut event_type = String::new();
                        let mut data_lines = Vec::new();

                        for raw_line in block.lines() {
                            let line = raw_line.trim_end();
                            if let Some(value) = line.strip_prefix("event: ") {
                                event_type = value.trim().to_string();
                            } else if let Some(value) = line.strip_prefix("data: ") {
                                data_lines.push(value.to_string());
                            }
                        }

                        if data_lines.is_empty() {
                            continue;
                        }

                        let data = data_lines.join("\n");

                        if data == "[DONE]" {
                            let _ = app.emit("chatdone", serde_json::json!({ "requestId": request_id }));
                            return Ok(latest_response_id);
                        }

                        if event_type.is_empty() {
                            continue;
                        }

                        let Ok(json) = serde_json::from_str::<serde_json::Value>(&data) else {
                            continue;
                        };

                        if let Some(response_id) = json["response"]["id"].as_str() {
                            latest_response_id = Some(response_id.to_string());
                        }

                        match event_type.as_str() {
                            "response.output_text.delta" => {
                                if let Some(content) = json["delta"].as_str() {
                                    let _ = app.emit(
                                        "chattoken",
                                        serde_json::json!({
                                            "requestId": request_id,
                                            "token": content
                                        }),
                                    );
                                }
                            }
                            "response.output_item.added" => {
                                let item = &json["item"];
                                let item_type = item["type"].as_str().unwrap_or_default();

                                if item_type == "function_call" {
                                    let tool_event = ChatToolEvent {
                                        request_id: request_id.map(|value| value.to_string()),
                                        phase: "started".to_string(),
                                        name: item["name"].as_str().map(|value| value.to_string()),
                                        call_id: item["call_id"].as_str().map(|value| value.to_string()),
                                        arguments: item["arguments"].as_str().map(|value| value.to_string()),
                                        output: None,
                                        status: item["status"].as_str().map(|value| value.to_string()),
                                    };
                                    let _ = app.emit("chattoolevent", tool_event);
                                } else if item_type == "function_call_output" {
                                    let output = item["output"]
                                        .as_array()
                                        .map(|parts| {
                                            parts
                                                .iter()
                                                .filter_map(|part| part["text"].as_str())
                                                .collect::<Vec<_>>()
                                                .join("\n")
                                        })
                                        .filter(|value| !value.trim().is_empty());

                                    let tool_event = ChatToolEvent {
                                        request_id: request_id.map(|value| value.to_string()),
                                        phase: "completed".to_string(),
                                        name: None,
                                        call_id: item["call_id"].as_str().map(|value| value.to_string()),
                                        arguments: None,
                                        output,
                                        status: item["status"].as_str().map(|value| value.to_string()),
                                    };
                                    let _ = app.emit("chattoolevent", tool_event);
                                }
                            }
                            "response.failed" => {
                                let message = json["response"]["error"]["message"]
                                    .as_str()
                                    .or_else(|| json["error"]["message"].as_str())
                                    .unwrap_or("Hermes Responses API failed")
                                    .to_string();
                                return Err(message);
                            }
                            "response.completed" => {
                                // Phase 2: 当存在 function_call 事件时，通过 AgentLoop 的 ToolRegistry2 执行工具。
                                // AgentLoop 未注册时降级为原有行为（纯聊天模式），不影响现有功能。
                                let has_tool_calls = json["response"]["output"]
                                    .as_array()
                                    .map(|items| items.iter().any(|i| i["type"] == "function_call"))
                                    .unwrap_or(false);
                                if has_tool_calls {
                                    log::info!("[legacy] response.completed with function_calls — 通过 ToolRegistry2 执行工具");
                                    if let Some(output_items) = json["response"]["output"].as_array() {
                                        // 从 response.output 提取所有 function_call 项
                                        let tool_calls: Vec<crate::hermes::types::VLMToolCall> = output_items
                                            .iter()
                                            .filter(|item| item["type"].as_str() == Some("function_call"))
                                            .filter_map(|item| {
                                                let id = item["call_id"].as_str().unwrap_or_default().to_string();
                                                let name = item["name"].as_str().unwrap_or_default().to_string();
                                                let arguments = item["arguments"].as_str().unwrap_or_default().to_string();
                                                if id.is_empty() || name.is_empty() {
                                                    None
                                                } else {
                                                    Some(crate::hermes::types::VLMToolCall {
                                                        id,
                                                        kind: "function".to_string(),
                                                        function: crate::hermes::types::VLMToolFunction {
                                                            name,
                                                            arguments,
                                                        },
                                                    })
                                                }
                                            })
                                            .collect();

                                        if !tool_calls.is_empty() {
                                            // 尝试通过 AgentLoop 的 ToolRegistry2 执行工具
                                            let agent_loop_state = app.try_state::<std::sync::Arc<crate::hermes::agent_loop::AgentLoop>>();
                                            if let Some(agent_loop) = agent_loop_state {
                                                let tools = agent_loop.tools.clone();
                                                // 逐个执行工具（串行，避免并发问题）
                                                for tc in &tool_calls {
                                                    let args: serde_json::Value = serde_json::from_str(&tc.function.arguments)
                                                        .unwrap_or(serde_json::Value::Null);
                                                    log::info!(
                                                        "[legacy] 执行工具: name={}, call_id={}",
                                                        tc.function.name, tc.id
                                                    );
                                                    // 发送 tool started 事件
                                                    let start_event = ChatToolEvent {
                                                        request_id: request_id.map(|v| v.to_string()),
                                                        phase: "started".to_string(),
                                                        name: Some(tc.function.name.clone()),
                                                        call_id: Some(tc.id.clone()),
                                                        arguments: Some(tc.function.arguments.clone()),
                                                        output: None,
                                                        status: Some("running".to_string()),
                                                    };
                                                    let _ = app.emit("chattoolevent", start_event);

                                                    // 执行工具（注意：不在 std::sync::MutexGuard 内 await）
                                                    let tool_fn = {
                                                        let guard = tools.lock().unwrap();
                                                        guard.get_fn(&tc.function.name)
                                                    };
                                                    let result = match tool_fn {
                                                        Some(f) => f(args).await,
                                                        None => Err(format!("tool not found: {}", tc.function.name)),
                                                    };

                                                    let output_str = match &result {
                                                        Ok(v) => serde_json::to_string(v).unwrap_or_else(|_| "{}".to_string()),
                                                        Err(e) => serde_json::json!({ "error": e }).to_string(),
                                                    };

                                                    log::info!(
                                                        "[legacy] 工具执行完成: name={}, success={}",
                                                        tc.function.name,
                                                        result.is_ok()
                                                    );

                                                    // 发送 tool completed 事件
                                                    let complete_event = ChatToolEvent {
                                                        request_id: request_id.map(|v| v.to_string()),
                                                        phase: "completed".to_string(),
                                                        name: Some(tc.function.name.clone()),
                                                        call_id: Some(tc.id.clone()),
                                                        arguments: None,
                                                        output: Some(output_str.clone()),
                                                        status: Some(if result.is_ok() { "completed" } else { "error" }.to_string()),
                                                    };
                                                    let _ = app.emit("chattoolevent", complete_event);

                                                    // 将工具执行结果作为 token 发送给前端（让用户看到工具输出）
                                                    let truncated: String = output_str.chars().take(200).collect();
                                                    let _ = app.emit(
                                                        "chattoken",
                                                        serde_json::json!({
                                                            "requestId": request_id,
                                                            "token": format!("\n🔧 {} → {}\n", tc.function.name, truncated)
                                                        }),
                                                    );
                                                }
                                            } else {
                                                log::warn!("[legacy] AgentLoop 未注册，跳过工具执行");
                                            }
                                        }
                                    }
                                }
                                let _ = app.emit("chatdone", serde_json::json!({ "requestId": request_id }));
                                return Ok(latest_response_id);
                            }
                            _ => {}
                        }
                    }
                }
                Err(e) => {
                    return Err(format!("流式响应中断: {}", e));
                }
            }
        }

        let _ = app.emit("chatdone", serde_json::json!({ "requestId": request_id }));
        Ok(latest_response_id)
    }

    let request_model = resolve_chat_request_model(model.clone());

    let result = match execute_stream(
        &app,
        &client,
        &gateway_url,
        &messages,
        previous_response_id.clone(),
        replay_history,
        &request_model,
        request_id.as_deref(),
        &mut cancel_rx,
    )
    .await
    {
        Ok(response_id) => Ok(response_id),
        Err(error_message)
            if previous_response_id.is_some()
                && error_message.contains("Previous response not found") =>
        {
            execute_stream(
                &app,
                &client,
                &gateway_url,
                &messages,
                None,
                true,
                &request_model,
                request_id.as_deref(),
                &mut cancel_rx,
            )
            .await
        }
        Err(error_message) => {
            let _ = app.emit(
                "chatterror",
                serde_json::json!({
                    "requestId": request_id,
                    "message": error_message.clone()
                }),
            );
            Err(error_message)
        }
    };

    // Drop guard (`_abort_guard`) 会在 return 时自动清理。
    result
}

#[tauri::command]
pub fn cancel_chat_stream(request_id: Option<String>) -> Result<(), String> {
    let mut active_aborts = ACTIVE_CHAT_STREAM_ABORTS
        .lock()
        .map_err(|_| "无法锁定聊天取消状态".to_string())?;

    if let Some(request_id) = request_id {
        if let Some(cancel_tx) = active_aborts.remove(&request_id) {
            let _ = cancel_tx.send(());
        }
        return Ok(());
    }

    for (_, cancel_tx) in active_aborts.drain() {
        let _ = cancel_tx.send(());
    }

    Ok(())
}

// ========================
// 记忆相关命令（Phase 1-2）
// ========================

use std::sync::Mutex;

// MemoryEntry 统一使用 commands::types::MemoryEntry（V2 扩展版），
// 支持 version/parent_id/task_type/confidence/outcome 等自动记忆升级字段。
// 旧 IPC 命令的 source 参数仍接收 String，内部转 Option<String> 存储。
pub use crate::commands::types::MemoryEntry;

// 记忆存储（旧数据可通过 migrate_memories_to_db 迁移至数据库）
static MEMORIES: std::sync::LazyLock<Mutex<Vec<MemoryEntry>>> = std::sync::LazyLock::new(|| {
    Mutex::new(vec![
        MemoryEntry {
            id: "mem_1".to_string(),
            summary: "用户角色".to_string(),
            content: "用户是全栈开发者，熟悉 React、Rust、Tauri、Python。偏好简洁直接的回复。".to_string(),
            source: Some("对话".to_string()),
            created_at: "2026-04-15".to_string(),
            updated_at: "2026-04-15".to_string(),
            importance: "hot".to_string(),
            access_count: 5,
            last_accessed_at: None,
            workspace_path: None,
            ..Default::default()
        },
        MemoryEntry {
            id: "mem_2".to_string(),
            summary: "当前项目".to_string(),
            content: "tupAI — Tauri + React 桌面客户端，连接本地 Hermes agent HTTP API (localhost:8642)。".to_string(),
            source: Some("对话".to_string()),
            created_at: "2026-04-15".to_string(),
            updated_at: "2026-04-15".to_string(),
            importance: "warm".to_string(),
            access_count: 2,
            last_accessed_at: None,
            workspace_path: None,
            ..Default::default()
        },
        MemoryEntry {
            id: "mem_3".to_string(),
            summary: "偏好设置".to_string(),
            content: "用户偏好深色主题，使用中文交流。".to_string(),
            source: Some("配置".to_string()),
            created_at: "2026-04-13".to_string(),
            updated_at: "2026-04-13".to_string(),
            importance: "cold".to_string(),
            access_count: 0,
            last_accessed_at: None,
            workspace_path: None,
            ..Default::default()
        },
    ])
});

// ========================
// 任务定义与存储
// ========================

#[derive(Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct Task {
    pub id: String,
    pub title: String,
    pub description: String,
    pub status: String, // "pending" | "in_progress" | "completed" | "expired"
    pub due_date: Option<String>,
    pub workspace_path: Option<String>,
    pub created_at: String,
    pub updated_at: String,
    pub completed_at: Option<String>,
}

// 任务存储（旧数据可通过 migrate_tasks_to_db 迁移至数据库）
static TASKS: std::sync::LazyLock<Mutex<Vec<Task>>> = std::sync::LazyLock::new(|| {
    Mutex::new(vec![
        Task {
            id: "task_1".to_string(),
            title: "完成 tupAI SSE 流式响应".to_string(),
            description: "实现 chat_stream 命令，支持逐 token 流式输出到前端".to_string(),
            status: "in_progress".to_string(),
            due_date: Some("2026-04-15".to_string()),
            workspace_path: None,
            created_at: "2026-04-15T00:00:00Z".to_string(),
            updated_at: "2026-04-15T00:00:00Z".to_string(),
            completed_at: None,
        },
        Task {
            id: "task_2".to_string(),
            title: "集成 Skills Tab 详情弹窗".to_string(),
            description: "点击技能卡片弹出详情，支持启用/禁用".to_string(),
            status: "pending".to_string(),
            due_date: Some("2026-04-16".to_string()),
            workspace_path: None,
            created_at: "2026-04-15T00:00:00Z".to_string(),
            updated_at: "2026-04-15T00:00:00Z".to_string(),
            completed_at: None,
        },
        Task {
            id: "task_3".to_string(),
            title: "实现对话搜索功能".to_string(),
            description: "支持按关键词搜索历史对话".to_string(),
            status: "pending".to_string(),
            due_date: None,
            workspace_path: None,
            created_at: "2026-04-15T00:00:00Z".to_string(),
            updated_at: "2026-04-15T00:00:00Z".to_string(),
            completed_at: None,
        },
    ])
});


// ========================
// 配置相关命令（Phase 2-2 / 3-3）
// ========================

#[derive(Serialize, Deserialize, Clone)]
pub struct AppConfig {
    pub theme: String,
    pub language: String,
    pub current_agent: String,
    #[serde(default = "default_gateway_host")]
    pub gateway_host: String,
    #[serde(default = "default_gateway_port")]
    pub gateway_port: u16,
    #[serde(default)]
    pub user_nickname: String,
    #[serde(default = "default_workspace_path")]
    pub workspace_path: String,
    #[serde(default = "default_workspaces")]
    pub workspaces: Vec<Workspace>,
    /// Computer Use（桌面自动化）开关。默认关闭；开启后后端才会在
    /// ReAct 工具链中优先使用 Cua Driver / 桌面输入能力。
    #[serde(default)]
    pub computer_use_enabled: bool,
}

impl Default for AppConfig {
    fn default() -> Self {
        Self {
            theme: "system".to_string(),
            language: "zh".to_string(),
            current_agent: "hermes-agent".to_string(),
            gateway_host: default_gateway_host(),
            gateway_port: default_gateway_port(),
            user_nickname: String::new(),
            workspace_path: default_workspace_path(),
            workspaces: default_workspaces(),
            computer_use_enabled: false,
        }
    }
}

fn default_workspace_path() -> String {
    normalize_workspace_path(Some("~/AI/hermes-workspace"))
        .unwrap_or_else(|| expand_home_path("~/AI/hermes-workspace").display().to_string())
}

fn default_workspaces() -> Vec<Workspace> {
    vec![Workspace {
        id: "default".to_string(),
        name: "默认工作区".to_string(),
        path: default_workspace_path(),
        icon: "📁".to_string(),
    }]
}

fn sanitize_workspace_entry(mut workspace: Workspace) -> Workspace {
    workspace.path = normalize_workspace_path(Some(&workspace.path)).unwrap_or(workspace.path);
    if workspace.icon.trim().is_empty() {
        workspace.icon = "📁".to_string();
    }
    if workspace.name.trim().is_empty() {
        workspace.name = Path::new(&workspace.path)
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or("工作区")
            .to_string();
    }
    workspace
}

fn sanitize_app_config(mut cfg: AppConfig) -> AppConfig {
    cfg.user_nickname = cfg.user_nickname.trim().to_string();

    if cfg.workspaces.is_empty() {
        cfg.workspaces = default_workspaces();
    } else {
        cfg.workspaces = cfg
            .workspaces
            .into_iter()
            .map(sanitize_workspace_entry)
            .collect();
    }

    let normalized_workspace_path =
        normalize_workspace_path(Some(&cfg.workspace_path)).unwrap_or_else(default_workspace_path);

    if cfg
        .workspaces
        .iter()
        .any(|workspace| workspace.path == normalized_workspace_path)
    {
        cfg.workspace_path = normalized_workspace_path;
    } else {
        cfg.workspace_path = cfg
            .workspaces
            .first()
            .map(|workspace| workspace.path.clone())
            .unwrap_or_else(default_workspace_path);
    }

    cfg
}

fn get_config_path(app: &tauri::AppHandle) -> Result<std::path::PathBuf, String> {
    let dir = resolve_app_data_dir(app)?;
    std::fs::create_dir_all(&dir)
        .map_err(|e| format!("Failed to create app data dir: {}", e))?;
    Ok(dir.join("config.json"))
}

pub(crate) fn load_config_from_disk(app: &tauri::AppHandle) -> AppConfig {
    let path = match get_config_path(app) {
        Ok(p) => p,
        Err(_) => return AppConfig::default(),
    };
    if path.exists() {
        if let Ok(content) = std::fs::read_to_string(&path) {
            if let Ok(cfg) = serde_json::from_str::<AppConfig>(&content) {
                return sanitize_app_config(cfg);
            }
        }
    }
    AppConfig::default()
}

fn save_config_to_disk(app: &tauri::AppHandle, cfg: &AppConfig) -> Result<(), String> {
    let path = get_config_path(app)?;
    let content = serde_json::to_string_pretty(cfg).map_err(|e| e.to_string())?;
    std::fs::write(&path, content)
        .map_err(|e| format!("Failed to save config {}: {}", path.display(), e))
}

#[tauri::command]
pub fn get_config(app: tauri::AppHandle) -> Result<AppConfig, String> {
    Ok(load_config_from_disk(&app))
}

#[tauri::command]
pub fn set_config(
    app: tauri::AppHandle,
    key: Option<String>,
    value: Option<String>,
    request: Option<serde_json::Value>,
) -> Result<(), String> {
    // Support both tupai-style {key, value} and BitFun-style {request: {path, value}}.
    let (key, value) = if let Some(req) = &request {
        let path = req
            .get("path")
            .and_then(|v| v.as_str())
            .ok_or("missing required key path in request")?;
        let key = match path {
            "themes.current" | "theme" => "theme",
            "app.language" | "i18n.language" | "language" => "language",
            "app.agent" | "current_agent" | "agent" => "current_agent",
            "ai.computer_use_enabled" | "computer_use_enabled" => "computer_use_enabled",
            _ => {
                // Unknown path — accept silently (no matching AppConfig field).
                log::debug!("[set_config] Ignoring unknown config path: {}", path);
                return Ok(());
            }
        };
        let val = req.get("value").ok_or("missing required key value in request")?;
        let value = match val {
            serde_json::Value::String(s) => s.clone(),
            serde_json::Value::Bool(b) => b.to_string(),
            serde_json::Value::Number(n) => n.to_string(),
            _ => {
                log::debug!("[set_config] Ignoring non-scalar value for path: {}", path);
                return Ok(());
            }
        };
        (key.to_string(), value)
    } else {
        (
            key.ok_or("missing required key key")?,
            value.ok_or("missing required key value")?,
        )
    };

    let mut cfg = load_config_from_disk(&app);
    match key.as_str() {
        "theme" => cfg.theme = value,
        "language" => cfg.language = value,
        "current_agent" | "agent" => cfg.current_agent = value,
        "user_nickname" => cfg.user_nickname = value.trim().to_string(),
        "gateway_host" => cfg.gateway_host = value.trim().to_string(),
        "gateway_port" => {
            cfg.gateway_port = value
                .trim()
                .parse::<u16>()
                .map_err(|_| format!("Invalid gateway_port: {}", value))?
        }
        "workspace_path" => {
            cfg.workspace_path = normalize_workspace_path(Some(&value))
                .ok_or_else(|| format!("Invalid workspace_path: {}", value))?
        }
        "computer_use_enabled" => {
            cfg.computer_use_enabled = match value.as_str() {
                "true" => true,
                "false" => false,
                other => {
                    return Err(format!("Invalid computer_use_enabled: {}", other));
                }
            }
        }
        _ => return Err(format!("Unknown config key: {}", key)),
    }
    let cfg = sanitize_app_config(cfg);
    save_config_to_disk(&app, &cfg)
}

// ========================
// 会话相关命令（Phase 2-1）
// ========================

#[derive(Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct Session {
    pub id: String,
    pub title: String,
    pub pinned: bool,
    pub updated_at: String,
    pub workspace_path: Option<String>,
    #[serde(default)]
    pub preview: Option<String>,
    #[serde(default)]
    pub model: Option<String>,
}

#[derive(Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct Message {
    pub id: String,
    pub session_id: String,
    pub role: String,
    pub content: String,
    pub timestamp: i64,
    #[serde(default)]
    pub created_at: String,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct SavedAttachment {
    pub path: String,
}

struct TerminalSession {
    writer: Box<dyn Write + Send>,
    master: Box<dyn portable_pty::MasterPty + Send>,
    child: Box<dyn portable_pty::Child + Send>,
}

static TERMINAL_SESSIONS: std::sync::LazyLock<Mutex<HashMap<String, TerminalSession>>> =
    std::sync::LazyLock::new(|| Mutex::new(HashMap::new()));

fn build_attachment_output_path(
    workspace_path: Option<&str>,
    file_name: &str,
    is_image: bool,
) -> Result<PathBuf, String> {
    let normalized_workspace = normalize_workspace_path(workspace_path)
        .ok_or_else(|| "Workspace path is required".to_string())?;
    let workspace_dir = PathBuf::from(normalized_workspace);
    let target_dir = if is_image {
        workspace_dir.join("img")
    } else {
        workspace_dir.join("files")
    };

    std::fs::create_dir_all(&target_dir).map_err(|e| {
        format!(
            "Failed to create attachment directory {}: {}",
            target_dir.display(),
            e
        )
    })?;

    let safe_name = sanitize_attachment_name(file_name);
    let timestamp = chrono::Local::now()
        .format("%Y%m%d-%H%M%S-%3f")
        .to_string();

    Ok(target_dir.join(format!("{}-{}", timestamp, safe_name)))
}

fn get_sessions_db_path(app: &tauri::AppHandle) -> Result<std::path::PathBuf, String> {
    let dir = resolve_app_data_dir(app)?;
    std::fs::create_dir_all(&dir)
        .map_err(|e| format!("Failed to create app data dir: {}", e))?;
    Ok(dir.join("sessions.db"))
}

fn ensure_sessions_schema(conn: &Connection) -> Result<(), String> {
    conn.execute_batch(
        r#"
        PRAGMA foreign_keys = ON;
        CREATE TABLE IF NOT EXISTS sessions (
            id TEXT PRIMARY KEY,
            title TEXT NOT NULL,
            agent_id TEXT,
            workspace_path TEXT,
            pinned INTEGER DEFAULT 0,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            message_count INTEGER DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS messages (
            id TEXT PRIMARY KEY,
            session_id TEXT NOT NULL,
            role TEXT NOT NULL,
            content TEXT NOT NULL,
            created_at TEXT NOT NULL,
            FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id);
        CREATE INDEX IF NOT EXISTS idx_sessions_workspace ON sessions(workspace_path);
        "#,
    )
    .map_err(|e| format!("Failed to initialize sessions db schema: {}", e))?;

    let _ = conn.execute("ALTER TABLE sessions ADD COLUMN last_response_id TEXT", []);
    let _ = conn.execute("ALTER TABLE sessions ADD COLUMN model TEXT", []);

    Ok(())
}

fn update_session_model_in_connection(
    conn: &Connection,
    session_id: &str,
    model: Option<String>,
    updated_at: &str,
) -> Result<(), String> {
    let next_model = model
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty());

    conn.execute(
        "UPDATE sessions SET model = ?1, updated_at = ?2 WHERE id = ?3",
        params![next_model, updated_at, session_id],
    )
    .map_err(|e| e.to_string())?;

    Ok(())
}

fn open_sessions_db(app: &tauri::AppHandle) -> Result<Connection, String> {
    let path = get_sessions_db_path(app)?;
    let conn = Connection::open(&path)
        .map_err(|e| format!("Failed to open sessions db {}: {}", path.display(), e))?;

    ensure_sessions_schema(&conn)
        .map_err(|e| format!("Failed to initialize sessions db {}: {}", path.display(), e))?;

    Ok(conn)
}

fn normalize_workspace_path(path: Option<&str>) -> Option<String> {
    let raw = path?.trim();

    if raw.is_empty() {
        return None;
    }

    if raw == "~" {
        return home_dir_legacy();
    }

    if let Some(rest) = raw.strip_prefix("~/") {
        if let Some(home) = home_dir_legacy() {
            // 修复 Windows 路径分隔符:USERPROFILE 是 `C:\Users\Alice`,
            // 之前用 `format!("{}/{}", home.trim_end_matches('/'), rest)`
            // 会产生 `C:\Users\Alice/myworkspace` 混合分隔符,
            // 某些字符串比较 / Win32 API 不稳定。
            // 改为同时剥离尾部 `\` 与 `/`,并用平台原生分隔符拼接。
            let home = home.trim_end_matches(['/', '\\']);
            let sep = std::path::MAIN_SEPARATOR;
            return Some(format!("{}{}{}", home, sep, rest));
        }
    }

    Some(raw.to_string())
}

fn home_dir_legacy() -> Option<String> {
    #[cfg(not(target_os = "windows"))]
    { std::env::var("HOME").ok() }
    #[cfg(target_os = "windows")]
    { std::env::var("USERPROFILE").or_else(|_| std::env::var("HOME")).ok() }
}

fn resolve_terminal_cwd(
    app: &tauri::AppHandle,
    workspace_path: Option<String>,
) -> Result<PathBuf, String> {
    let requested = workspace_path.unwrap_or_else(|| load_config_from_disk(app).workspace_path);
    let normalized = normalize_workspace_path(Some(&requested))
        .ok_or_else(|| "Workspace path is required".to_string())?;
    let path = PathBuf::from(normalized);
    std::fs::create_dir_all(&path)
        .map_err(|e| format!("Failed to create terminal cwd {}: {}", path.display(), e))?;
    path.canonicalize()
        .map_err(|e| format!("Failed to resolve terminal cwd {}: {}", path.display(), e))
}

fn sanitize_attachment_name(name: &str) -> String {
    let trimmed = name.trim();
    let candidate = if trimmed.is_empty() {
        "attachment"
    } else {
        trimmed
    };

    candidate
        .chars()
        .map(|ch| match ch {
            '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|' => '_',
            _ => ch,
        })
        .collect()
}

fn workspace_matches(session_path: Option<&str>, workspace_filter: Option<&str>) -> bool {
    if let Some(filter_path) = workspace_filter {
        return session_path
            .and_then(|path| normalize_workspace_path(Some(path)))
            .map(|path| path == filter_path)
            .unwrap_or(true);
    }

    true
}

fn now_unix_timestamp() -> Result<i64, String> {
    Ok(SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|e| e.to_string())?
        .as_secs() as i64)
}

fn now_rfc3339() -> String {
    chrono::Utc::now().to_rfc3339()
}

fn parse_rfc3339_to_unix(value: &str) -> i64 {
    chrono::DateTime::parse_from_rfc3339(value)
        .map(|dt| dt.timestamp())
        .unwrap_or_default()
}

#[tauri::command]
pub fn get_sessions(
    app: tauri::AppHandle,
    workspace_filter: Option<String>,
) -> Result<Vec<Session>, String> {
    let normalized_workspace = normalize_workspace_path(workspace_filter.as_deref());
    let conn = open_sessions_db(&app)?;
    let mut stmt = conn
        .prepare(
            r#"
            SELECT
                s.id,
                s.title,
                s.pinned,
                s.workspace_path,
                s.updated_at,
                s.model,
                (
                    SELECT m.content
                    FROM messages m
                    WHERE m.session_id = s.id
                    ORDER BY m.created_at DESC
                    LIMIT 1
                ) AS preview
            FROM sessions s
            ORDER BY s.updated_at DESC
            "#,
        )
        .map_err(|e| e.to_string())?;
    let rows = stmt
        .query_map([], |row| {
            let pinned: i64 = row.get(2)?;
            Ok(Session {
                id: row.get(0)?,
                title: row.get(1)?,
                pinned: pinned != 0,
                workspace_path: row.get(3)?,
                updated_at: row.get(4)?,
                model: row.get(5)?,
                preview: row.get(6)?,
            })
        })
        .map_err(|e| e.to_string())?;

    let mut sessions = Vec::new();
    for row in rows {
        let session = row.map_err(|e| e.to_string())?;
        if workspace_matches(
            session.workspace_path.as_deref(),
            normalized_workspace.as_deref(),
        ) {
            sessions.push(session);
        }
    }

    sessions.sort_by(|left, right| right.updated_at.cmp(&left.updated_at));
    Ok(sessions)
}

#[tauri::command]
pub fn create_session(
    app: tauri::AppHandle,
    title: Option<String>,
    agent_id: Option<String>,
    workspace_path: Option<String>,
    model: Option<String>,
    request: Option<serde_json::Value>,
) -> Result<serde_json::Value, String> {
    // Support both tupai-style flat params {title, agent_id, ...} and
    // BitFun-style {request: {sessionName, agentType, workspacePath, ...}}.
    let (title, agent_id, workspace_path, model, is_bitfun) = if let Some(req) = &request {
        let title = req
            .get("sessionName")
            .and_then(|v| v.as_str())
            .unwrap_or("Untitled")
            .to_string();
        let agent_id = req
            .get("agentType")
            .and_then(|v| v.as_str())
            .unwrap_or("default")
            .to_string();
        let ws = req
            .get("workspacePath")
            .and_then(|v| v.as_str())
            .map(|s| s.to_string());
        let mdl = req
            .get("model")
            .and_then(|v| v.as_str())
            .map(|s| s.to_string());
        (title, agent_id, ws, mdl, true)
    } else {
        (
            title.ok_or("missing required key title")?,
            agent_id.ok_or("missing required key agent_id")?,
            workspace_path,
            model,
            false,
        )
    };

    let conn = open_sessions_db(&app)?;
    let now = now_rfc3339();
    let normalized_workspace = normalize_workspace_path(workspace_path.as_deref());
    let normalized_model = model
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty());
    let session = Session {
        id: uuid::Uuid::new_v4().to_string(),
        title: title.clone(),
        pinned: false,
        updated_at: now.clone(),
        workspace_path: normalized_workspace.clone(),
        preview: None,
        model: normalized_model.clone(),
    };

    conn.execute(
        r#"
        INSERT INTO sessions (id, title, agent_id, workspace_path, pinned, created_at, updated_at, message_count, model)
        VALUES (?1, ?2, ?3, ?4, 0, ?5, ?5, 0, ?6)
        "#,
        params![
            session.id,
            session.title,
            agent_id,
            normalized_workspace,
            now,
            normalized_model
        ],
    )
    .map_err(|e| e.to_string())?;

    if is_bitfun {
        // BitFun-compatible response with sessionId/sessionName/agentType fields.
        Ok(serde_json::json!({
            "sessionId": session.id,
            "sessionName": session.title,
            "agentType": agent_id,
            "id": session.id,
            "title": session.title,
            "pinned": session.pinned,
            "updatedAt": session.updated_at,
            "workspacePath": session.workspace_path,
            "preview": session.preview,
            "model": session.model,
        }))
    } else {
        serde_json::to_value(session).map_err(|e| e.to_string())
    }
}

#[tauri::command]
pub fn delete_session(app: tauri::AppHandle, id: String) -> Result<(), String> {
    let conn = open_sessions_db(&app)?;
    conn.execute(
        "DELETE FROM messages WHERE session_id = ?1",
        params![id.clone()],
    )
    .map_err(|e| e.to_string())?;
    conn.execute("DELETE FROM sessions WHERE id = ?1", params![id])
        .map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
pub fn toggle_pin_session(app: tauri::AppHandle, id: String) -> Result<(), String> {
    let conn = open_sessions_db(&app)?;
    conn.execute(
        "UPDATE sessions SET pinned = CASE WHEN pinned = 0 THEN 1 ELSE 0 END WHERE id = ?1",
        params![id],
    )
    .map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
pub fn update_session_title(
    app: tauri::AppHandle,
    id: String,
    title: String,
) -> Result<(), String> {
    let conn = open_sessions_db(&app)?;
    conn.execute(
        "UPDATE sessions SET title = ?1, updated_at = ?2 WHERE id = ?3",
        params![title, now_rfc3339(), id],
    )
    .map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
pub fn update_session_model(
    app: tauri::AppHandle,
    id: String,
    model: Option<String>,
) -> Result<(), String> {
    let conn = open_sessions_db(&app)?;
    update_session_model_in_connection(&conn, &id, model, &now_rfc3339())
}

/// 更新单个会话的默认工作区位置。
///
/// - `session_id`: 目标会话 ID
/// - `new_workspace_path`: 新工作区路径（会被规范化）
/// - `move_data`: 是否把旧工作区下该会话的附件数据迁移到新工作区
///
/// 返回 `SessionWorkspaceUpdateResult`，包含旧/新路径与迁移统计。
/// 若新工作区未在 config.json 中注册，会自动注册（复用 create_workspace 逻辑）。
#[derive(Serialize)]
pub struct SessionWorkspaceUpdateResult {
    pub session_id: String,
    pub old_workspace_path: Option<String>,
    pub new_workspace_path: String,
    pub workspace_registered: bool,
    pub moved_files: usize,
    pub moved_dirs: usize,
}

#[tauri::command]
pub fn update_session_workspace(
    app: tauri::AppHandle,
    session_id: String,
    new_workspace_path: String,
    move_data: Option<bool>,
) -> Result<SessionWorkspaceUpdateResult, String> {
    let trimmed = new_workspace_path.trim();
    if trimmed.is_empty() {
        return Err("new_workspace_path is required".to_string());
    }

    let new_normalized = normalize_workspace_path(Some(trimmed))
        .ok_or_else(|| "Failed to normalize new workspace path".to_string())?;

    let conn = open_sessions_db(&app)?;

    // 读取旧 workspace_path
    let old_workspace_path: Option<String> = conn
        .query_row(
            "SELECT workspace_path FROM sessions WHERE id = ?1",
            params![session_id],
            |row| row.get::<_, Option<String>>(0),
        )
        .map_err(|e| format!("Failed to query session workspace_path: {}", e))?;

    // 更新 sessions.workspace_path
    conn.execute(
        "UPDATE sessions SET workspace_path = ?1, updated_at = ?2 WHERE id = ?3",
        params![Some(new_normalized.clone()), now_rfc3339(), session_id],
    )
    .map_err(|e| format!("Failed to update session workspace_path: {}", e))?;

    // 若新工作区未注册,自动注册到 config.json
    let cfg = load_config_from_disk(&app);
    let workspace_registered = cfg
        .workspaces
        .iter()
        .any(|ws| ws.path == new_normalized);
    if !workspace_registered {
        let name = Path::new(&new_normalized)
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("workspace")
            .to_string();
        // 复用 create_workspace 的注册逻辑(含创建目录)
        let _ = create_workspace(
            app.clone(),
            name,
            new_normalized.clone(),
            Some("📁".to_string()),
        );
    }

    // 可选:迁移该会话在旧工作区下的附件数据
    let do_move = move_data.unwrap_or(true);
    let mut moved_files = 0usize;
    let mut moved_dirs = 0usize;

    if do_move {
        if let Some(old_path) = &old_workspace_path {
            let old_normalized = normalize_workspace_path(Some(old_path));
            if let Some(old_norm) = old_normalized {
                if old_norm != new_normalized {
                    let (f, d) = migrate_session_attachments(
                        &old_norm,
                        &new_normalized,
                        &session_id,
                    );
                    moved_files = f;
                    moved_dirs = d;
                }
            }
        }
    }

    Ok(SessionWorkspaceUpdateResult {
        session_id,
        old_workspace_path,
        new_workspace_path: new_normalized,
        workspace_registered,
        moved_files,
        moved_dirs,
    })
}

/// 把旧工作区下与 session 相关的附件目录迁移到新工作区。
///
/// 当前附件统一存放在 `<workspace>/img/` 与 `<workspace>/files/` 下,
/// 没有按 session_id 分子目录。因此这里采用"整目录合并"策略:
/// 把旧目录下所有文件搬到新目录(合并),而非仅搬单个会话的文件。
/// 这样能保证会话切换工作区后,历史附件跟随到新工作区。
fn migrate_session_attachments(
    old_workspace: &str,
    new_workspace: &str,
    _session_id: &str,
) -> (usize, usize) {
    let old_dir = PathBuf::from(old_workspace);
    let new_dir = PathBuf::from(new_workspace);
    let mut moved_files = 0usize;
    let mut moved_dirs = 0usize;

    let subdirs = ["img", "files"];
    for sub in &subdirs {
        let src = old_dir.join(sub);
        let dst = new_dir.join(sub);
        if !src.exists() {
            continue;
        }
        if let Err(e) = std::fs::create_dir_all(&dst) {
            log::warn!(
                "[migrate_session_attachments] create_dir_all {} failed: {}",
                dst.display(),
                e
            );
            continue;
        }
        let entries = match std::fs::read_dir(&src) {
            Ok(e) => e,
            Err(e) => {
                log::warn!(
                    "[migrate_session_attachments] read_dir {} failed: {}",
                    src.display(),
                    e
                );
                continue;
            }
        };
        for entry in entries.flatten() {
            let from = entry.path();
            let to = dst.join(entry.file_name());
            if from == to {
                continue;
            }
            // 若目标已存在同名文件,跳过避免覆盖(用户可手动处理)
            if to.exists() {
                continue;
            }
            let meta = match entry.metadata() {
                Ok(m) => m,
                Err(_) => continue,
            };
            if meta.is_dir() {
                if std::fs::rename(&from, &to).is_ok() {
                    moved_dirs += 1;
                }
            } else if std::fs::rename(&from, &to).is_ok() {
                moved_files += 1;
            }
        }
    }

    (moved_files, moved_dirs)
}

#[tauri::command]
pub fn get_session_response_id(
    app: tauri::AppHandle,
    session_id: String,
) -> Result<Option<String>, String> {
    let conn = open_sessions_db(&app)?;
    conn.query_row(
        "SELECT last_response_id FROM sessions WHERE id = ?1",
        params![session_id],
        |row| row.get::<_, Option<String>>(0),
    )
    .map_err(|e| e.to_string())
}

#[tauri::command]
pub fn set_session_response_id(
    app: tauri::AppHandle,
    session_id: String,
    response_id: Option<String>,
) -> Result<(), String> {
    let conn = open_sessions_db(&app)?;
    conn.execute(
        "UPDATE sessions SET last_response_id = ?1 WHERE id = ?2",
        params![response_id, session_id],
    )
    .map_err(|e| e.to_string())?;
    Ok(())
}

// ========================
// 记忆与任务数据库（Phase 1: SQLite 持久化）
// ========================

/// 获取应用主数据库路径（统一数据库，包含 memories, tasks）
fn get_app_db_path(app: &tauri::AppHandle) -> Result<std::path::PathBuf, String> {
    let dir = resolve_app_data_dir(app)?;
    std::fs::create_dir_all(&dir)
        .map_err(|e| format!("Failed to create app data dir: {}", e))?;
    let db_path = dir.join(APP_DB_FILENAME);
    let legacy_db_path = dir.join(LEGACY_APP_DB_FILENAME);

    if !db_path.exists() && legacy_db_path.exists() {
        std::fs::copy(&legacy_db_path, &db_path)
            .map_err(|e| format!("Failed to migrate legacy app db: {}", e))?;
    }

    Ok(db_path)
}

/// 确保应用数据库 Schema 存在（包含 memories 和 tasks 表）
pub fn ensure_app_schema(conn: &Connection) -> Result<(), String> {
    conn.execute_batch(
        r#"
        PRAGMA foreign_keys = ON;

        -- memories 表（记忆条目，V2 扩展：version/lineage/outcome/confidence）
        CREATE TABLE IF NOT EXISTS memories (
            id TEXT PRIMARY KEY,
            summary TEXT NOT NULL,
            content TEXT NOT NULL,
            source TEXT NOT NULL DEFAULT '对话',
            workspace_path TEXT,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            importance TEXT NOT NULL DEFAULT 'warm',
            access_count INTEGER DEFAULT 0,
            last_accessed_at TEXT,
            version INTEGER DEFAULT 1,
            parent_id TEXT,
            parent_version INTEGER,
            task_type TEXT,
            tool_used TEXT,
            confidence REAL DEFAULT 0,
            session_id TEXT,
            channel_id TEXT,
            outcome TEXT
        );
        CREATE INDEX IF NOT EXISTS idx_memories_workspace ON memories(workspace_path);
        CREATE INDEX IF NOT EXISTS idx_memories_importance ON memories(importance);
        CREATE INDEX IF NOT EXISTS idx_memories_created_at ON memories(created_at DESC);
        CREATE INDEX IF NOT EXISTS idx_memories_access_count ON memories(access_count DESC);
        CREATE INDEX IF NOT EXISTS idx_memories_task_type ON memories(task_type);
        CREATE INDEX IF NOT EXISTS idx_memories_parent ON memories(parent_id);

        -- memory_lineage 表（记忆版本族谱：parent → child）
        CREATE TABLE IF NOT EXISTS memory_lineage (
            parent_id TEXT NOT NULL,
            parent_version INTEGER NOT NULL,
            child_id TEXT NOT NULL,
            child_version INTEGER NOT NULL,
            merged_at TEXT NOT NULL,
            PRIMARY KEY (parent_id, parent_version, child_id, child_version)
        );
        CREATE INDEX IF NOT EXISTS idx_memory_lineage_parent ON memory_lineage(parent_id);
        CREATE INDEX IF NOT EXISTS idx_memory_lineage_child ON memory_lineage(child_id);

        -- tasks 表（任务）
        CREATE TABLE IF NOT EXISTS tasks (
            id TEXT PRIMARY KEY,
            title TEXT NOT NULL,
            description TEXT DEFAULT '',
            status TEXT NOT NULL DEFAULT 'pending',
            due_date TEXT,
            workspace_path TEXT,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            completed_at TEXT
        );
        CREATE INDEX IF NOT EXISTS idx_tasks_workspace ON tasks(workspace_path);
        CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status);
        CREATE INDEX IF NOT EXISTS idx_tasks_due_date ON tasks(due_date);
        CREATE INDEX IF NOT EXISTS idx_tasks_created_at ON tasks(created_at DESC);
        "#,
    )
    .map_err(|e| format!("Failed to initialize app db schema: {}", e))?;

    // 迁移：为旧数据库添加新列（如果缺失）
    // 使用 PRAGMA table_info 检查列是否存在，避免重复添加
    let mut columns = Vec::new();
    let mut stmt = conn.prepare("PRAGMA table_info(memories)").map_err(|e| format!("Failed to prepare pragma: {}", e))?;
    let rows = stmt.query_map([], |row| {
        let name: String = row.get(1)?;
        Ok(name)
    }).map_err(|e| format!("Failed to query table info: {}", e))?;
    for row in rows {
        columns.push(row.map_err(|e| format!("Failed to read column: {}", e))?);
    }
    if !columns.contains(&"last_accessed_at".to_string()) {
        conn.execute("ALTER TABLE memories ADD COLUMN last_accessed_at TEXT", [])
            .map_err(|e| format!("Failed to add column last_accessed_at: {}", e))?;
    }
    // V2 扩展列：旧库补齐
    for (col, ddl) in [
        ("version", "ALTER TABLE memories ADD COLUMN version INTEGER DEFAULT 1"),
        ("parent_id", "ALTER TABLE memories ADD COLUMN parent_id TEXT"),
        ("parent_version", "ALTER TABLE memories ADD COLUMN parent_version INTEGER"),
        ("task_type", "ALTER TABLE memories ADD COLUMN task_type TEXT"),
        ("tool_used", "ALTER TABLE memories ADD COLUMN tool_used TEXT"),
        ("confidence", "ALTER TABLE memories ADD COLUMN confidence REAL DEFAULT 0"),
        ("session_id", "ALTER TABLE memories ADD COLUMN session_id TEXT"),
        ("channel_id", "ALTER TABLE memories ADD COLUMN channel_id TEXT"),
        ("outcome", "ALTER TABLE memories ADD COLUMN outcome TEXT"),
    ] {
        if !columns.contains(&col.to_string()) {
            conn.execute(ddl, [])
                .map_err(|e| format!("Failed to add column {}: {}", col, e))?;
        }
    }

    // 检查 tasks 表的 completed_at 列
    let mut task_columns = Vec::new();
    let mut stmt = conn.prepare("PRAGMA table_info(tasks)").map_err(|e| format!("Failed to prepare pragma: {}", e))?;
    let rows = stmt.query_map([], |row| {
        let name: String = row.get(1)?;
        Ok(name)
    }).map_err(|e| format!("Failed to query table info: {}", e))?;
    for row in rows {
        task_columns.push(row.map_err(|e| format!("Failed to read column: {}", e))?);
    }
    if !task_columns.contains(&"completed_at".to_string()) {
        conn.execute("ALTER TABLE tasks ADD COLUMN completed_at TEXT", [])
            .map_err(|e| format!("Failed to add column completed_at: {}", e))?;
    }

    Ok(())
}

/// 打开应用数据库连接（自动初始化 schema）
pub fn open_app_db(app: &tauri::AppHandle) -> Result<Connection, String> {
    let path = get_app_db_path(app)?;
    let conn = Connection::open(&path)
        .map_err(|e| format!("Failed to open app db {}: {}", path.display(), e))?;

    ensure_app_schema(&conn)
        .map_err(|e| format!("Failed to initialize app db {}: {}", path.display(), e))?;

    Ok(conn)
}

// ========================
// 记忆相关命令（数据库版）
// ========================

#[tauri::command]
pub fn get_memories(app: tauri::AppHandle, workspace_filter: Option<String>) -> Result<Vec<MemoryEntry>, String> {
    let normalized_workspace = normalize_workspace_path(workspace_filter.as_deref());
    let conn = open_app_db(&app)?;
    let mut stmt = conn
        .prepare(
            r#"
            SELECT id, summary, content, source, created_at, updated_at,
                   importance, access_count, last_accessed_at, workspace_path,
                   COALESCE(version, 1), parent_id, parent_version,
                   task_type, tool_used, COALESCE(confidence, 0),
                   session_id, channel_id, outcome
            FROM memories
            WHERE workspace_path IS NULL OR workspace_path = ?1
            ORDER BY
                CASE importance
                    WHEN 'hot' THEN 1
                    WHEN 'warm' THEN 2
                    WHEN 'cold' THEN 3
                    ELSE 4
                END,
                access_count DESC,
                created_at DESC
            "#,
        )
        .map_err(|e| e.to_string())?;

    let rows = stmt
        .query_map(params![normalized_workspace], |row| {
            Ok(MemoryEntry {
                id: row.get(0)?,
                summary: row.get(1)?,
                content: row.get(2)?,
                source: Some(row.get::<_, String>(3)?),
                created_at: row.get(4)?,
                updated_at: row.get(5)?,
                importance: row.get(6)?,
                access_count: row.get(7)?,
                last_accessed_at: row.get(8)?,
                workspace_path: row.get(9)?,
                version: row.get::<_, Option<i64>>(10)?.unwrap_or(1),
                parent_id: row.get(11)?,
                parent_version: row.get(12)?,
                task_type: row.get(13)?,
                tool_used: row.get(14)?,
                confidence: row.get::<_, Option<f32>>(15)?.unwrap_or(0.0),
                session_id: row.get(16)?,
                channel_id: row.get(17)?,
                outcome: row.get(18)?,
            })
        })
        .map_err(|e| e.to_string())?;

    let memories: Vec<MemoryEntry> = rows.collect::<Result<_, _>>().map_err(|e| e.to_string())?;
    Ok(memories)
}

#[tauri::command]
pub fn add_memory(
    app: tauri::AppHandle,
    summary: String,
    content: String,
    source: String,
    workspace_path: Option<String>,
) -> Result<MemoryEntry, String> {
    let conn = open_app_db(&app)?;
    let now = now_rfc3339();
    let id = format!("mem_{}", uuid::Uuid::new_v4());
    let normalized_workspace = normalize_workspace_path(workspace_path.as_deref());

    conn.execute(
        r#"
        INSERT INTO memories
            (id, summary, content, source, workspace_path, created_at, updated_at, importance, access_count)
        VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?6, 'warm', 0)
        "#,
        params![id, &summary, &content, &source, normalized_workspace, now],
    )
    .map_err(|e| e.to_string())?;

    Ok(MemoryEntry {
        id,
        summary,
        content,
        source: Some(source),
        created_at: now.clone(),
        updated_at: now,
        importance: "warm".to_string(),
        access_count: 0,
        last_accessed_at: None,
        workspace_path: normalized_workspace,
        ..Default::default()
    })
}

#[tauri::command]
pub fn update_memory(
    app: tauri::AppHandle,
    id: String,
    summary: String,
    content: String,
) -> Result<(), String> {
    let conn = open_app_db(&app)?;
    let now = now_rfc3339();

    conn.execute(
        "UPDATE memories SET summary = ?1, content = ?2, updated_at = ?3 WHERE id = ?4",
        params![summary, content, now, id],
    )
    .map_err(|e| e.to_string())?;

    Ok(())
}

#[tauri::command]
pub fn delete_memory(app: tauri::AppHandle, id: String) -> Result<(), String> {
    let conn = open_app_db(&app)?;
    conn.execute("DELETE FROM memories WHERE id = ?1", params![id])
        .map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
pub fn increment_memory_access(app: tauri::AppHandle, id: String) -> Result<(), String> {
    let conn = open_app_db(&app)?;
    let now = now_rfc3339();

    conn.execute(
        r#"
        UPDATE memories
        SET access_count = access_count + 1,
            last_accessed_at = ?1,
            importance = CASE
                WHEN access_count + 1 >= 3 THEN 'hot'
                WHEN access_count + 1 >= 1 THEN 'warm'
                ELSE 'cold'
            END
        WHERE id = ?2
        "#,
        params![now, id],
    )
    .map_err(|e| e.to_string())?;

    Ok(())
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct CompactMemoriesResult {
    pub updated: i64,
    pub hot: i64,
    pub warm: i64,
    pub cold: i64,
    pub workspace: Option<String>,
}

#[tauri::command]
pub fn compact_memories(app: tauri::AppHandle, workspace_filter: Option<String>) -> Result<CompactMemoriesResult, String> {
    let conn = open_app_db(&app)?;
    let normalized_workspace = normalize_workspace_path(workspace_filter.as_deref());

    let rows_affected = conn
        .execute(
            r#"
            UPDATE memories
            SET importance = CASE
                WHEN access_count >= 3 THEN 'hot'
                WHEN access_count >= 1 THEN 'warm'
                ELSE 'cold'
            END
            WHERE workspace_path IS NULL OR workspace_path = ?1
            "#,
            params![normalized_workspace],
        )
        .map_err(|e| e.to_string())?;

    // 之前只返回 "记忆整合完成，更新了 N 条记录" 字符串，
    // 前端要做结构化展示（"X 条 hot / Y 条 warm / Z 条 cold"）
    // 只能正则解析。这里顺手按 importance 分桶回读计数。
    let mut stmt = conn
        .prepare(
            r#"
            SELECT importance, COUNT(*) FROM memories
            WHERE workspace_path IS NULL OR workspace_path = ?1
            GROUP BY importance
            "#,
        )
        .map_err(|e| e.to_string())?;

    let mut hot = 0_i64;
    let mut warm = 0_i64;
    let mut cold = 0_i64;
    let rows = stmt
        .query_map(params![normalized_workspace], |row| {
            let importance: Option<String> = row.get(0)?;
            let count: i64 = row.get(1)?;
            Ok((importance, count))
        })
        .map_err(|e| e.to_string())?;
    for row in rows {
        if let Ok((Some(bucket), count)) = row {
            match bucket.as_str() {
                "hot" => hot = count,
                "warm" => warm = count,
                "cold" => cold = count,
                _ => {}
            }
        }
    }

    Ok(CompactMemoriesResult {
        updated: rows_affected as i64,
        hot,
        warm,
        cold,
        workspace: normalized_workspace,
    })
}

// ========================
// 任务相关命令（数据库版）
// ========================

#[tauri::command]
pub fn get_tasks(app: tauri::AppHandle, workspace_filter: Option<String>) -> Result<Vec<Task>, String> {
    let normalized_workspace = normalize_workspace_path(workspace_filter.as_deref());
    let conn = open_app_db(&app)?;
    let mut stmt = conn
        .prepare(
            r#"
            SELECT id, title, description, status, due_date, created_at, updated_at, completed_at, workspace_path
            FROM tasks
            WHERE workspace_path IS NULL OR workspace_path = ?1
            ORDER BY
                CASE status
                    WHEN 'in_progress' THEN 1
                    WHEN 'pending' THEN 2
                    WHEN 'completed' THEN 3
                    ELSE 4
                END,
                due_date ASC,
                created_at DESC
            "#,
        )
        .map_err(|e| e.to_string())?;

    let rows = stmt
        .query_map(params![normalized_workspace], |row| {
            Ok(Task {
                id: row.get(0)?,
                title: row.get(1)?,
                description: row.get(2)?,
                status: row.get(3)?,
                due_date: row.get(4)?,
                created_at: row.get(5)?,
                updated_at: row.get(6)?,
                completed_at: row.get(7)?,
                workspace_path: row.get(8)?,
            })
        })
        .map_err(|e| e.to_string())?;

    let tasks: Vec<Task> = rows.collect::<Result<_, _>>().map_err(|e| e.to_string())?;
    Ok(tasks)
}

#[tauri::command]
pub fn create_task(
    app: tauri::AppHandle,
    title: String,
    description: String,
    due_date: Option<String>,
    workspace_path: Option<String>,
) -> Result<Task, String> {
    let conn = open_app_db(&app)?;
    let now = now_rfc3339();
    let id = format!("task_{}", uuid::Uuid::new_v4());
    let normalized_workspace = normalize_workspace_path(workspace_path.as_deref());

    conn.execute(
        r#"
        INSERT INTO tasks
            (id, title, description, status, due_date, workspace_path, created_at, updated_at)
        VALUES (?1, ?2, ?3, 'pending', ?4, ?5, ?6, ?6)
        "#,
        params![id, &title, &description, due_date, normalized_workspace, now],
    )
    .map_err(|e| e.to_string())?;

    Ok(Task {
        id,
        title,
        description,
        status: "pending".to_string(),
        due_date,
        created_at: now.clone(),
        updated_at: now,
        completed_at: None,
        workspace_path: normalized_workspace,
    })
}

#[tauri::command]
pub fn update_task(
    app: tauri::AppHandle,
    id: String,
    status: String,
) -> Result<(), String> {
    let conn = open_app_db(&app)?;
    let now = now_rfc3339();
    let completed_at = if status == "completed" { Some(now.clone()) } else { None };

    conn.execute(
        "UPDATE tasks SET status = ?1, updated_at = ?2, completed_at = ?3 WHERE id = ?4",
        params![status, now, completed_at, id],
    )
    .map_err(|e| e.to_string())?;

    Ok(())
}

#[tauri::command]
pub fn delete_task(app: tauri::AppHandle, id: String) -> Result<(), String> {
    let conn = open_app_db(&app)?;
    conn.execute("DELETE FROM tasks WHERE id = ?1", params![id])
        .map_err(|e| e.to_string())?;
    Ok(())
}

// ========================
// 数据迁移命令（从内存 → 数据库）
// ========================

#[derive(Serialize, Deserialize)]
pub struct MigrationResult {
    pub migrated: i32,
    pub skipped: i32,
    pub message: String,
}

#[tauri::command]
pub fn migrate_memories_to_db(app: tauri::AppHandle) -> Result<MigrationResult, String> {
    use std::sync::MutexGuard;

    // 读取内存中的旧数据
    let memories_guard: MutexGuard<Vec<MemoryEntry>> = MEMORIES.lock().map_err(|e| e.to_string())?;
    let old_memories = memories_guard.clone();
    drop(memories_guard); // 提前释放锁

    let conn = open_app_db(&app)?;
    let mut migrated = 0;
    let mut skipped = 0;

    for memory in &old_memories {
        // 检查是否已存在（避免重复迁移）
        let exists: bool = conn
            .query_row(
                "SELECT 1 FROM memories WHERE id = ?1",
                params![memory.id],
                |_| Ok(true),
            )
            .unwrap_or(false);

        if !exists {
            // 转换 workspace_path：旧数据没有该字段，设为 None（全局）
            conn.execute(
                r#"
                INSERT INTO memories
                    (id, summary, content, source, workspace_path, created_at, updated_at, importance, access_count)
                VALUES (?1, ?2, ?3, ?4, NULL, ?5, ?5, ?6, ?7)
                "#,
                params![
                    memory.id,
                    memory.summary,
                    memory.content,
                    memory.source.as_deref().unwrap_or("对话"),
                    memory.created_at,
                    memory.importance,
                    memory.access_count,
                ],
            )
            .map_err(|e| format!("Failed to migrate memory {}: {}", memory.id, e))?;
            migrated += 1;
        } else {
            skipped += 1;
        }
    }

    // 迁移成功后清空内存里的 seed 数据，避免 needs_migration() 一直
    // 看到非空 MEMORIES 而误报"需要迁移"。
    if migrated > 0 {
        if let Ok(mut guard) = MEMORIES.lock() {
            guard.clear();
        }
    }

    Ok(MigrationResult {
        migrated,
        skipped,
        message: format!("记忆数据迁移完成：新增 {} 条，跳过 {} 条", migrated, skipped),
    })
}

#[tauri::command]
pub fn migrate_tasks_to_db(app: tauri::AppHandle) -> Result<MigrationResult, String> {
    use std::sync::MutexGuard;

    // 读取内存中的旧数据
    let tasks_guard: MutexGuard<Vec<Task>> = TASKS.lock().map_err(|e| e.to_string())?;
    let old_tasks = tasks_guard.clone();
    drop(tasks_guard);

    let conn = open_app_db(&app)?;
    let mut migrated = 0;
    let mut skipped = 0;

    for task in &old_tasks {
        let exists: bool = conn
            .query_row(
                "SELECT 1 FROM tasks WHERE id = ?1",
                params![task.id],
                |_| Ok(true),
            )
            .unwrap_or(false);

        if !exists {
            // 旧任务数据没有 created_at/updated_at/completed_at，使用默认时间
            let now = now_rfc3339();
            conn.execute(
                r#"
                INSERT INTO tasks
                    (id, title, description, status, due_date, workspace_path, created_at, updated_at, completed_at)
                VALUES (?1, ?2, ?3, ?4, ?5, NULL, ?6, ?6, NULL)
                "#,
                params![
                    task.id,
                    task.title,
                    task.description,
                    task.status,
                    task.due_date,
                    now,
                ],
            )
            .map_err(|e| format!("Failed to migrate task {}: {}", task.id, e))?;
            migrated += 1;
        } else {
            skipped += 1;
        }
    }

    // 同 memories：迁移成功就清空内存里的 seed。
    if migrated > 0 {
        if let Ok(mut guard) = TASKS.lock() {
            guard.clear();
        }
    }

    Ok(MigrationResult {
        migrated,
        skipped,
        message: format!("任务数据迁移完成：新增 {} 条，跳过 {} 条", migrated, skipped),
    })
}

#[tauri::command]
pub fn needs_migration(app: tauri::AppHandle) -> Result<bool, String> {
    let conn = open_app_db(&app)?;

    // 检查 memories 表是否存在且有数据
    let memories_count: i64 = conn
        .query_row("SELECT COUNT(*) FROM memories", [], |row| row.get(0))
        .unwrap_or(0);

    // 如果数据库中没有 memories 数据，但内存中有 → 需要迁移
    let mem = MEMORIES.lock().map_err(|e| e.to_string())?;
    let has_memories = !mem.is_empty();
    drop(mem);

    Ok(has_memories && memories_count == 0)
}

// ========================
// 消息相关命令（Phase 2-1）
// ========================

#[tauri::command]
pub fn get_messages(app: tauri::AppHandle, session_id: String) -> Result<Vec<Message>, String> {
    let conn = open_sessions_db(&app)?;
    let mut stmt = conn
        .prepare(
            r#"
            SELECT id, session_id, role, content, created_at
            FROM messages
            WHERE session_id = ?1
            ORDER BY created_at ASC
            "#,
        )
        .map_err(|e| e.to_string())?;
    let rows = stmt
        .query_map(params![session_id], |row| {
            let created_at: String = row.get(4)?;
            Ok(Message {
                id: row.get(0)?,
                session_id: row.get(1)?,
                role: row.get(2)?,
                content: row.get(3)?,
                timestamp: parse_rfc3339_to_unix(&created_at),
                created_at,
            })
        })
        .map_err(|e| e.to_string())?;

    let mut messages = Vec::new();
    for row in rows {
        messages.push(row.map_err(|e| e.to_string())?);
    }
    Ok(messages)
}

#[tauri::command]
pub fn add_message(
    app: tauri::AppHandle,
    session_id: String,
    role: String,
    content: String,
) -> Result<Message, String> {
    let timestamp = now_unix_timestamp()?;
    let created_at = now_rfc3339();
    let message_id = uuid::Uuid::new_v4().to_string();
    let mut conn = open_sessions_db(&app)?;
    let tx = conn.transaction().map_err(|e| e.to_string())?;

    let updated = tx
        .execute(
            "UPDATE sessions SET updated_at = ?1, message_count = COALESCE(message_count, 0) + 1 WHERE id = ?2",
            params![created_at.clone(), session_id.clone()],
        )
        .map_err(|e| e.to_string())?;

    if updated == 0 {
        return Err(format!("Session not found: {}", session_id));
    }

    tx.execute(
        "INSERT INTO messages (id, session_id, role, content, created_at) VALUES (?1, ?2, ?3, ?4, ?5)",
        params![
            message_id.clone(),
            session_id.clone(),
            role.clone(),
            content.clone(),
            created_at.clone(),
        ],
    )
    .map_err(|e| e.to_string())?;

    tx.commit().map_err(|e| e.to_string())?;

    let message = Message {
        id: message_id,
        session_id,
        role,
        content,
        timestamp,
        created_at,
    };

    Ok(message)
}

#[tauri::command]
pub fn save_pasted_attachment(
    workspace_path: Option<String>,
    file_name: String,
    data_base64: String,
    is_image: bool,
) -> Result<SavedAttachment, String> {
    use base64::engine::general_purpose::STANDARD;
    use base64::Engine;

    let output_path = build_attachment_output_path(workspace_path.as_deref(), &file_name, is_image)?;
    let bytes = STANDARD
        .decode(data_base64.as_bytes())
        .map_err(|e| format!("Failed to decode pasted attachment: {}", e))?;

    std::fs::write(&output_path, bytes)
        .map_err(|e| format!("Failed to write pasted attachment {}: {}", output_path.display(), e))?;

    Ok(SavedAttachment {
        path: output_path.display().to_string(),
    })
}

#[tauri::command]
pub fn import_attachment_from_path(
    workspace_path: Option<String>,
    source_path: String,
) -> Result<SavedAttachment, String> {
    let source = PathBuf::from(source_path.trim());
    if !source.exists() {
        return Err(format!("Attachment source does not exist: {}", source.display()));
    }

    let file_name = source
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("attachment");
    let extension = source
        .extension()
        .and_then(|ext| ext.to_str())
        .unwrap_or("")
        .to_lowercase();
    let is_image = matches!(
        extension.as_str(),
        "png" | "jpg" | "jpeg" | "webp" | "gif" | "bmp" | "tiff" | "svg" | "heic"
    );

    let output_path =
        build_attachment_output_path(workspace_path.as_deref(), file_name, is_image)?;
    std::fs::copy(&source, &output_path).map_err(|e| {
        format!(
            "Failed to copy attachment from {} to {}: {}",
            source.display(),
            output_path.display(),
            e
        )
    })?;

    Ok(SavedAttachment {
        path: output_path.display().to_string(),
    })
}

#[derive(Serialize, Deserialize, Clone)]
pub struct FileAttachmentUploadRequest {
    pub id: String,
    pub file_name: String,
    pub data_url: String,
    pub mime_type: String,
    pub file_size: u64,
}

#[tauri::command]
pub fn upload_file_attachments(
    workspace_path: Option<String>,
    request: Vec<FileAttachmentUploadRequest>,
) -> Result<Vec<SavedAttachment>, String> {
    use base64::engine::general_purpose::STANDARD;
    use base64::Engine;

    let mut results = Vec::new();

    for item in request {
        let output_path =
            build_attachment_output_path(workspace_path.as_deref(), &item.file_name, false)?;

        let data_url = item.data_url;
        let comma_pos = data_url
            .find(',')
            .ok_or_else(|| format!("Invalid data URL for file: {}", item.file_name))?;
        let base64_data = &data_url[comma_pos + 1..];

        let bytes = STANDARD
            .decode(base64_data.as_bytes())
            .map_err(|e| format!("Failed to decode file attachment {}: {}", item.file_name, e))?;

        std::fs::write(&output_path, bytes)
            .map_err(|e| format!("Failed to write file attachment {}: {}", output_path.display(), e))?;

        results.push(SavedAttachment {
            path: output_path.display().to_string(),
        });
    }

    Ok(results)
}

// ========================
// 工作区相关命令（Phase 3）
// ========================

#[derive(Serialize, Deserialize, Clone)]
pub struct Workspace {
    pub id: String,
    pub name: String,
    pub path: String,
    pub icon: String,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct WorkspaceSwitchResult {
    pub workspace: Workspace,
    pub gateway_restarted: bool,
}

fn apply_workspace_to_hermes(path: &str) -> Result<bool, String> {
    // v5 embedded mode: Hermes CLI sub-commands are no longer available.
    // The workspace path is already persisted in AppConfig by the caller;
    // the embedded Hermes server reads it from there. We no longer need
    // to call `config set terminal.cwd` or `gateway restart` via the
    // (now-removed) node sidecar.
    log::info!(
        "[apply_workspace_to_hermes] v5 embedded mode: workspace path '{}' persisted to AppConfig only (no Hermes CLI restart)",
        path
    );
    Ok(false)
}

#[tauri::command]
pub fn get_workspaces(app: tauri::AppHandle) -> Result<Vec<Workspace>, String> {
    Ok(load_config_from_disk(&app).workspaces)
}

#[tauri::command]
pub fn create_workspace(
    app: tauri::AppHandle,
    name: String,
    path: String,
    icon: Option<String>,
) -> Result<Workspace, String> {
    let mut cfg = load_config_from_disk(&app);
    let workspace = sanitize_workspace_entry(Workspace {
        id: uuid::Uuid::new_v4().to_string(),
        name,
        path,
        icon: icon.unwrap_or_else(|| "📁".to_string()),
    });

    std::fs::create_dir_all(&workspace.path)
        .map_err(|e| format!("Failed to create workspace directory {}: {}", workspace.path, e))?;

    cfg.workspaces.push(workspace.clone());
    let cfg = sanitize_app_config(cfg);
    save_config_to_disk(&app, &cfg)?;
    Ok(workspace)
}

#[tauri::command]
pub fn update_workspace(
    app: tauri::AppHandle,
    workspace_id: String,
    name: String,
    path: String,
    icon: Option<String>,
) -> Result<Workspace, String> {
    let mut cfg = load_config_from_disk(&app);
    let current_workspace_path = cfg.workspace_path.clone();

    let index = cfg
        .workspaces
        .iter()
        .position(|workspace| workspace.id == workspace_id)
        .ok_or_else(|| "Workspace not found".to_string())?;

    let updated = sanitize_workspace_entry(Workspace {
        id: workspace_id,
        name,
        path,
        icon: icon.unwrap_or_else(|| "📁".to_string()),
    });

    std::fs::create_dir_all(&updated.path)
        .map_err(|e| format!("Failed to create workspace directory {}: {}", updated.path, e))?;

    let was_current = cfg.workspaces[index].path == current_workspace_path;
    cfg.workspaces[index] = updated.clone();
    if was_current {
        cfg.workspace_path = updated.path.clone();
    }
    let cfg = sanitize_app_config(cfg);
    save_config_to_disk(&app, &cfg)?;

    if was_current {
        let _ = apply_workspace_to_hermes(&updated.path);
    }

    Ok(updated)
}

#[tauri::command]
pub fn delete_workspace(app: tauri::AppHandle, workspace_id: String) -> Result<Vec<Workspace>, String> {
    let mut cfg = load_config_from_disk(&app);
    if cfg.workspaces.len() <= 1 {
        return Err("At least one workspace must remain".to_string());
    }

    let workspace = cfg
        .workspaces
        .iter()
        .find(|item| item.id == workspace_id)
        .cloned()
        .ok_or_else(|| "Workspace not found".to_string())?;
    let was_current = cfg.workspace_path == workspace.path;
    cfg.workspaces.retain(|item| item.id != workspace_id);

    if was_current {
        cfg.workspace_path = cfg
            .workspaces
            .first()
            .map(|item| item.path.clone())
            .unwrap_or_else(default_workspace_path);
        let _ = apply_workspace_to_hermes(&cfg.workspace_path);
    }

    let cfg = sanitize_app_config(cfg);
    let result = cfg.workspaces.clone();
    save_config_to_disk(&app, &cfg)?;
    Ok(result)
}

/// 把当前进程作为子进程派生出去，让用户在新的软件副本中工作。
///
/// - `folder_path` 应是一个已存在的目录。
///   - 新副本会把 `config.json` / `sessions.db` / `tupai.db`
///     全部写到该目录下，实现"每个文件夹一个独立工作区"。
///   - 子进程通过环境变量 `TUPAI_DATA_DIR` 告知
///     `resolve_app_data_dir` 使用该目录。
///
/// 失败时返回错误字符串，由前端 toast 提示用户。
#[tauri::command]
pub fn launch_new_instance(folder_path: String) -> Result<u32, String> {
    let trimmed = folder_path.trim();
    if trimmed.is_empty() {
        return Err("Folder path is required".to_string());
    }
    let path = std::path::PathBuf::from(trimmed);
    if !path.exists() {
        return Err(format!(
            "Target folder does not exist: {}",
            path.display()
        ));
    }
    if !path.is_dir() {
        return Err(format!(
            "Target path is not a directory: {}",
            path.display()
        ));
    }
    // 目标目录为空时主动初始化一份空 config.json，避免子进程启动时
    // 误以为有数据可读。
    if let Err(err) = std::fs::create_dir_all(&path) {
        return Err(format!(
            "Failed to ensure target folder {}: {}",
            path.display(),
            err
        ));
    }
    let config_marker = path.join("config.json");
    if !config_marker.exists() {
        if let Err(err) = std::fs::write(&config_marker, b"{}") {
            return Err(format!(
                "Failed to seed config.json in {}: {}",
                path.display(),
                err
            ));
        }
    }

    let exe = std::env::current_exe()
        .map_err(|e| format!("Failed to resolve current executable: {}", e))?;

    // 复制父进程环境，把数据目录覆盖到目标目录
    let mut command = Command::new(&exe);
    command.env(OVERRIDE_DATA_DIR_ENV, &path);

    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        // 0x00000008 = DETACHED_PROCESS
        // 0x00000200 = CREATE_NEW_PROCESS_GROUP
        // 新进程拥有独立的进程组，关闭主进程不会影响子进程
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        const DETACHED_PROCESS: u32 = 0x0000_0008;
        const CREATE_NEW_PROCESS_GROUP: u32 = 0x0000_0200;
        command.creation_flags(CREATE_NO_WINDOW | DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP);
    }

    let child = command
        .spawn()
        .map_err(|e| format!("Failed to spawn new instance: {}", e))?;

    Ok(child.id())
}

#[tauri::command]
pub fn set_workspace(
    app: tauri::AppHandle,
    workspace_id: String,
) -> Result<WorkspaceSwitchResult, String> {
    let mut cfg = load_config_from_disk(&app);
    let workspace = cfg
        .workspaces
        .iter()
        .find(|item| item.id == workspace_id)
        .cloned()
        .ok_or_else(|| "Workspace not found".to_string())?;

    cfg.workspace_path = workspace.path.clone();
    let cfg = sanitize_app_config(cfg);
    save_config_to_disk(&app, &cfg)?;
    let gateway_restarted = apply_workspace_to_hermes(&workspace.path)?;

    Ok(WorkspaceSwitchResult {
        workspace,
        gateway_restarted,
    })
}

#[tauri::command]
pub fn get_current_workspace(app: tauri::AppHandle) -> Result<Workspace, String> {
    let cfg = load_config_from_disk(&app);
    let workspace = cfg
        .workspaces
        .iter()
        .find(|workspace| workspace.path == cfg.workspace_path)
        .cloned()
        .ok_or_else(|| "Workspace not found".to_string())?;
    Ok(workspace)
}

#[tauri::command]
pub fn create_terminal_session(
    app: tauri::AppHandle,
    workspace_path: Option<String>,
) -> Result<TerminalOpenResult, String> {
    use portable_pty::{native_pty_system, CommandBuilder, PtySize};
    use std::io::Read;

    let cwd = resolve_terminal_cwd(&app, workspace_path)?;
    let shell = resolve_interactive_shell();
    let pty_system = native_pty_system();
    let pair = pty_system
        .openpty(PtySize {
            rows: 24,
            cols: 80,
            pixel_width: 0,
            pixel_height: 0,
        })
        .map_err(|e| format!("Failed to create PTY: {}", e))?;

    #[allow(unused_variables)]
    let shell_lower = shell.to_ascii_lowercase();
    let mut command = CommandBuilder::new(shell);
    // Login-interactive flags differ per shell family. Picking the right
    // set ensures the spawned shell reads its profile (rc / PowerShell
    // profile / cmd AutoRun) and stays interactive.
    #[cfg(target_os = "windows")]
    {
        // powershell.exe takes -NoLogo to skip the banner; cmd.exe
        // doesn't accept any of the unix-style flags.
        if shell_lower.contains("powershell") {
            command.arg("-NoLogo");
        }
    }
    #[cfg(not(target_os = "windows"))]
    {
        command.arg("-il");
    }
    command.cwd(cwd.clone());
    command.env("TERM", "xterm-256color");

    let mut child = pair
        .slave
        .spawn_command(command)
        .map_err(|e| format!("Failed to spawn shell: {}", e))?;
    drop(pair.slave);

    // 注意：take_writer / try_clone_reader 失败时不能让 child 泄漏。
    // portable_pty 的 child Box 在 Drop 时只 close 句柄、不会 kill 子进程，
    // 否则会留下孤儿 cmd.exe / pwsh.exe 进程。
    let writer = match pair.master.take_writer() {
        Ok(writer) => writer,
        Err(e) => {
            let _ = child.kill();
            let _ = child.wait();
            return Err(format!("Failed to create PTY writer: {}", e));
        }
    };
    let mut reader = match pair.master.try_clone_reader() {
        Ok(reader) => reader,
        Err(e) => {
            let _ = child.kill();
            let _ = child.wait();
            return Err(format!("Failed to create PTY reader: {}", e));
        }
    };

    let session_id = uuid::Uuid::new_v4().to_string();
    let app_handle = app.clone();
    let output_session_id = session_id.clone();

    std::thread::spawn(move || {
        let mut buffer = [0_u8; 4096];
        loop {
            match reader.read(&mut buffer) {
                Ok(0) => break,
                Ok(size) => {
                    let payload = TerminalOutputEvent {
                        session_id: output_session_id.clone(),
                        data: String::from_utf8_lossy(&buffer[..size]).to_string(),
                    };
                    let _ = app_handle.emit("terminal-output", payload);
                }
                Err(_) => break,
            }
        }

        let _ = app_handle.emit(
            "terminal-exit",
            TerminalExitEvent {
                session_id: output_session_id,
            },
        );
    });

    TERMINAL_SESSIONS
        .lock()
        .map_err(|e| e.to_string())?
        .insert(
            session_id.clone(),
            TerminalSession {
                writer,
                master: pair.master,
                child,
            },
        );

    Ok(TerminalOpenResult { session_id })
}

#[tauri::command]
pub fn write_terminal_input(session_id: String, data: String) -> Result<(), String> {
    let mut sessions = TERMINAL_SESSIONS.lock().map_err(|e| e.to_string())?;
    let session = sessions
        .get_mut(&session_id)
        .ok_or_else(|| "Terminal session not found".to_string())?;
    session
        .writer
        .write_all(data.as_bytes())
        .map_err(|e| format!("Failed to write terminal input: {}", e))?;
    session
        .writer
        .flush()
        .map_err(|e| format!("Failed to flush terminal input: {}", e))?;
    Ok(())
}

#[tauri::command]
pub fn resize_terminal_session(session_id: String, cols: u16, rows: u16) -> Result<(), String> {
    use portable_pty::PtySize;

    let mut sessions = TERMINAL_SESSIONS.lock().map_err(|e| e.to_string())?;
    let session = sessions
        .get_mut(&session_id)
        .ok_or_else(|| "Terminal session not found".to_string())?;
    session
        .master
        .resize(PtySize {
            rows,
            cols,
            pixel_width: 0,
            pixel_height: 0,
        })
        .map_err(|e| format!("Failed to resize terminal session: {}", e))
}

#[tauri::command]
pub fn close_terminal_session(app: tauri::AppHandle, session_id: String) -> Result<(), String> {
    let mut sessions = TERMINAL_SESSIONS.lock().map_err(|e| e.to_string())?;
    if let Some(mut session) = sessions.remove(&session_id) {
        let _ = session.child.kill();
        let _ = session.child.wait();
        let _ = app.emit(
            "terminal-exit",
            TerminalExitEvent {
                session_id,
            },
        );
    }
    Ok(())
}

// ========================
// 技能相关命令（Phase 3）
// ========================
// 注: `get_agents` / `Agent` / `AGENTS` 及 `get_skills` /
// `get_skill_detail` / `toggle_skill` / `get_toolsets` 5 个命令已
// 迁移至 `commands::agent`。本模块保留 `SkillInfo` / `SkillDetail` /
// `ToolsetInfo` struct 及共享 helper(被 `get_market_skills` 等复用)。

const HERMES_SKILLS_INDEX_URL: &str =
    "https://hermes-agent.nousresearch.com/docs/api/skills-index.json";
const HERMES_WEB_PANEL_BASE_URL: &str = "http://127.0.0.1:9119";

#[derive(Serialize, Deserialize, Clone)]
pub struct SkillInfo {
    pub name: String,
    pub description: String,
    pub category: Option<String>,
    pub enabled: bool,
    pub source: String,
    pub trust: String,
    pub identifier: Option<String>,
    pub version: Option<String>,
    pub tags: Vec<String>,
    pub path: String,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct SkillDetail {
    pub skill: SkillInfo,
    pub content_preview: String,
    /// Full SKILL.md content (not truncated). Used by the chat scene as the
    /// LLM system prompt when a skill is activated.
    pub content: String,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct ToolsetInfo {
    pub name: String,
    pub label: String,
    pub description: String,
    pub enabled: bool,
    pub configured: bool,
    pub tools: Vec<String>,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct MarketSkillInfo {
    pub name: String,
    pub description: String,
    pub source: String,
    pub identifier: String,
    pub trust_level: String,
    pub repo: String,
    pub path: String,
    pub category: Option<String>,
    pub tags: Vec<String>,
    pub installed: bool,
    pub installed_source: Option<String>,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct SkillCommandResult {
    pub success: bool,
    pub stdout: String,
    pub stderr: String,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct CronScheduleInfo {
    pub kind: String,
    pub expr: String,
    pub display: String,
}

#[derive(Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct CronJob {
    pub id: String,
    pub name: Option<String>,
    pub prompt: String,
    pub schedule: CronScheduleInfo,
    pub schedule_display: String,
    pub enabled: bool,
    pub state: String,
    pub deliver: Option<String>,
    pub last_run_at: Option<String>,
    pub next_run_at: Option<String>,
    pub last_error: Option<String>,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateCronJobInput {
    pub prompt: String,
    pub schedule: String,
    pub name: Option<String>,
    pub deliver: Option<String>,
}

#[derive(Serialize, Deserialize)]
pub struct CronActionResult {
    pub ok: bool,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct DashboardLogsResponse {
    pub file: String,
    pub lines: Vec<String>,
}

#[derive(Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct DashboardEnvVarInfo {
    pub is_set: bool,
    pub redacted_value: Option<String>,
    pub description: String,
    pub url: Option<String>,
    pub category: String,
    pub is_password: bool,
    pub tools: Vec<String>,
    pub advanced: bool,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct DashboardEnvRevealResponse {
    pub key: String,
    pub value: String,
}

#[derive(Serialize, Deserialize, Clone, Default)]
#[serde(rename_all = "camelCase")]
pub struct DashboardPrimaryModelConfig {
    pub model: String,
    pub provider: String,
    pub base_url: String,
    pub api_key: String,
    pub context_length: Option<u64>,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct DashboardModelOption {
    pub id: String,
    pub label: String,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct DashboardModelProvider {
    pub id: String,
    pub label: String,
    pub models: Vec<DashboardModelOption>,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct DashboardModelOptionsResponse {
    pub providers: Vec<DashboardModelProvider>,
    pub model: String,
    pub provider: String,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct HermesDashboardRestartResult {
    pub success: bool,
    pub command: String,
    pub message: String,
}

const HERMES_DASHBOARD_PORT: u16 = 9119;
const HERMES_GATEWAY_PORT: u16 = 8642;

// Public port aliases used by peer modules (notably
// `commands::diagnostics`). The original constants stay private to
// `legacy` so the rest of the file's helper surface isn't leaked.
pub const HERMES_DASHBOARD_PORT_EXTERN: u16 = HERMES_DASHBOARD_PORT;
pub const HERMES_GATEWAY_PORT_EXTERN: u16 = HERMES_GATEWAY_PORT;

/// Public alias used by the diagnostics module. The gateway is the
/// in-process axum server — there is no `hermes` CLI binary in the
/// bundle. We return `Some("(embedded)")` so the diagnostic report
/// can record that the runtime is the embedded one (and the path
/// probe in the auto-fix flow doesn't bail out with a confusing
/// "`hermes` not on PATH" message).
pub fn resolve_hermes_binary_path_for_diag() -> Option<String> {
    Some("(embedded)".to_string())
}

/// Public alias used by the diagnostics module. Same as
/// `stop_gateway_process_internal` but reachable from outside
/// `commands::legacy`.
pub fn stop_gateway_process_for_diag() -> bool {
    stop_gateway_process_internal()
}

/// Public alias used by the diagnostics module. Same as
/// `restart_dashboard_process_internal` (used by the legacy
/// `restart_hermes_dashboard` Tauri command) but reachable from
/// outside `commands::legacy`.
pub fn restart_hermes_dashboard_inner_for_diag() -> Result<(), String> {
    restart_dashboard_process_internal();
    Ok(())
}

/// Public alias used by the diagnostics module. Best-effort rotate
/// the on-disk log file: if the file is larger than 1 MiB, truncate
/// it to the last 64 KiB. Otherwise this is a no-op.
///
/// This is intentionally conservative — we never delete the log file
/// outright, and we keep the tail so the user can still see what
/// just happened. The caller passes the resolved log path so this
/// helper has no dependency on the global Tauri app handle.
pub fn rotate_app_log_for_diag(path: &Path) -> Result<(), String> {
    use std::io::{Read, Seek, SeekFrom, Write};
    let metadata = match fs::metadata(path) {
        Ok(m) => m,
        Err(_) => return Ok(()),
    };
    if metadata.len() <= 1024 * 1024 {
        return Ok(());
    }
    let keep = 64u64 * 1024;
    let total = metadata.len();
    let offset = total - keep.min(total);
    let mut file = fs::File::open(path)
        .map_err(|e| format!("rotate: open {:?} failed: {}", path, e))?;
    file.seek(SeekFrom::Start(offset))
        .map_err(|e| format!("rotate: seek failed: {}", e))?;
    let mut tail = Vec::with_capacity(keep as usize);
    file.take(keep)
        .read_to_end(&mut tail)
        .map_err(|e| format!("rotate: read failed: {}", e))?;
    let mut out = fs::File::create(path)
        .map_err(|e| format!("rotate: truncate {:?} failed: {}", path, e))?;
    out.write_all(b"<<log rotated by tupai diagnostics>>\n")
        .map_err(|e| format!("rotate: write header failed: {}", e))?;
    out.write_all(&tail)
        .map_err(|e| format!("rotate: write tail failed: {}", e))?;
    Ok(())
}

fn format_skill_command_output(result: &SkillCommandResult) -> Option<String> {
    let stdout = result.stdout.trim();
    if !stdout.is_empty() {
        return Some(stdout.to_string());
    }

    let stderr = result.stderr.trim();
    if !stderr.is_empty() {
        return Some(stderr.to_string());
    }

    None
}

fn extract_dashboard_error_detail(body: &str) -> Option<String> {
    serde_json::from_str::<serde_json::Value>(body)
        .ok()
        .and_then(|value| value.get("detail").and_then(|detail| detail.as_str()).map(str::to_string))
}

/// Locate the absolute path of the `hermes` CLI on the current PATH.
///
/// The previous globsai implementation hard-coded `command -v hermes`,
/// which is a POSIX shell builtin and silently returns empty on
/// Windows. We now use the platform's native resolver:
///   * Unix  -> `command -v hermes` (unchanged)
///   * Win   -> `where hermes` (built-in to cmd.exe / Windows)
/// and additionally fall back to `hermes` as a bare name so PATH lookup
/// can happen at spawn time on systems where the helper is missing
/// (e.g. minimal Windows containers).
///
/// Resolved tuple of the Hermes runtime components. The Tauri
/// sidecar is now **Node.js** (see `tauri.conf.json` —
/// `externalBin: ["binaries/node"]`) and the CLI logic is the
/// plain CJS source shipped as a Tauri resource
/// (`resources/hermes/hermes-cli.cjs`, ~5 KiB). This is the
/// "Node source, not a binary" deployment the user asked for:
/// end users can `cat "C:\Program Files\...\resources\hermes\hermes-cli.cjs"`
/// after install to see exactly what the gateway does.
///
/// `node_path` is the absolute path to `node.exe` (Tauri 2
/// sidecar, ~70 MiB). `cli_source` is the absolute path to the
/// CJS bundle (the CLI logic). Both must be present for the
/// gateway / dashboard / WhatsApp subcommands to work.
#[derive(Debug, Clone)]
pub struct HermesRuntime {
    pub node_path: String,
    pub cli_source: String,
}

/// Locate the bundled Node sidecar and the Hermes CLI source
/// resource. Returns `None` when either is missing — callers
/// translate that into a user-facing "Hermes 服务不可用" error.
pub fn resolve_hermes_runtime() -> Option<HermesRuntime> {
    let node_path = resolve_hermes_binary_path()?;
    let cli_source = resolve_hermes_cli_source()?;
    Some(HermesRuntime { node_path, cli_source })
}

/// Resolve the **Node.js** binary that Tauri ships as a sidecar
/// (`binaries/node-<target>(.exe)`). Historically this function
/// pointed at a single `hermes(.exe)` binary; post v0.1.0-tupai
/// the sidecar is the Node runtime and the CLI is a CJS resource
/// resolved separately by [`resolve_hermes_cli_source`].
///
/// Cross-platform resolution strategy:
/// 0. **Bundled sidecar** (`<exe-dir>/node(.exe)`) — when tupAI
///    is installed via the NSIS / .dmg / .deb bundle, the Node
///    runtime lives next to `tupai(.exe)` as a Tauri 2 sidecar
///    (`bundle.externalBin: ["binaries/node"]` in
///    `tauri.conf.json`). We probe this first so the bundled
///    install is self-contained — no separate Node install
///    required on the user machine.
/// 1. Probe well-known install locations (Homebrew / nvm / system)
///    before relying on `where` / `command -v`. Tauri's child
///    processes inherit a minimal PATH on Windows; the user's
///    installed `node` is almost always under one of the
///    locations below.
/// 2. Fall back to the shell-level probe so the user can still
///    wire Node through a non-standard PATH or nvm shim.
/// 3. Final fallback: the literal `node` token so the shell does
///    the lookup. This matches the previous behaviour and is
///    the contract the rest of the module relies on.
pub fn resolve_hermes_binary_path() -> Option<String> {
    if let Ok(exe_path) = std::env::current_exe() {
        if let Some(exe_dir) = exe_path.parent() {
            // 0a. Same-dir probe (the canonical Tauri 2 sidecar
            //     location — `binaries/node-<target>(.exe)` is
            //     renamed to `node(.exe)` and dropped next to
            //     `tupai(.exe)` by the bundler).
            for sidecar_name in &["node", "node.exe"] {
                let candidate = exe_dir.join(sidecar_name);
                if candidate.is_file() {
                    return Some(candidate.to_string_lossy().to_string());
                }
            }
            // 0b. Tauri 2 resource-dir probe. The `binaries/`
            //     subtree of the Tauri config is also exposed as
            //     a resource (defensive — most builds put the
            //     sidecar in 0a).
            for sidecar_name in &[
                "node",
                "node.exe",
                "binaries/node",
                "binaries/node.exe",
            ] {
                let candidate = exe_dir.join("resources").join(sidecar_name);
                if candidate.is_file() {
                    return Some(candidate.to_string_lossy().to_string());
                }
            }
        }
    }

    let candidates: &[&str] = &[
        // Unix: Homebrew (Apple Silicon first, then Intel)
        "/opt/homebrew/bin/node",
        "/usr/local/bin/node",
        // Unix: nvm / fnm — the common user-level install path
        ".nvm/versions/node/current/bin/node",
        ".local/share/fnm/node-versions/*/installation/bin/node",
        // Windows: nvm-windows / fnm / scoop / winget installs.
        // HOMEPATH looks like `\Users\alice` — we resolve against
        // the current user's home directory so it works on
        // localized Windows installs.
        "AppData\\Local\\fnm_multishells\\node.exe",
        "AppData\\Roaming\\nvm\\node.exe",
        "scoop\\apps\\node\\current\\node.exe",
        "AppData\\Local\\Programs\\node\\node.exe",
        ".local\\bin\\node.exe",
    ];

    let home = std::env::var_os("HOME")
        .or_else(|| std::env::var_os("USERPROFILE"))
        .map(PathBuf::from);

    for candidate in candidates {
        let mut path = PathBuf::from(candidate);
        if path.is_relative() {
            if let Some(home_dir) = home.as_ref() {
                path = home_dir.join(candidate);
            } else {
                continue;
            }
        }
        if path.is_file() {
            return Some(path.to_string_lossy().to_string());
        }
    }

    // PATH-based probe — last-ditch. `where` may emit multiple lines
    // (PATHEXT matches); `command -v` emits one. The caller is expected
    // to take the first non-empty line.
    #[cfg(target_os = "windows")]
    let probe = "where node";
    #[cfg(not(target_os = "windows"))]
    let probe = "command -v node";

    if let Ok(output) = run_login_shell_command(probe) {
        if output.status.success() {
            let raw = String::from_utf8_lossy(&output.stdout);
            if let Some(candidate) = raw
                .lines()
                .map(str::trim)
                .find(|line| !line.is_empty() && !line.contains("INFO:"))
            {
                return Some(candidate.to_string());
            }
        }
    }

    None
}

/// Locate the Hermes CLI source bundle (`hermes-cli.cjs`).
///
/// In the bundled install, Tauri's `bundle.resources` rule copies
/// the file to `<exe-dir>/resources/hermes/hermes-cli.cjs`. The
/// resolver also accepts two dev-mode locations so a developer
/// running `cargo tauri dev` (no NSIS step) can still bring up
/// the gateway:
///
///   * `<cwd>/hermes-cli.cjs`           — when running from
///                                        `src-tauri/` directly
///   * `<cwd>/resources/hermes/...`     — alongside the built
///                                        web assets during dev
///
/// Returns the absolute path to the CJS file, or `None` when the
/// source is not found (caller logs a clear "Hermes 服务不可用"
/// error).
pub fn resolve_hermes_cli_source() -> Option<String> {
    // 0. Bundled resource: Tauri 2 drops the file at
    //    `<exe-dir>/resources/hermes/hermes-cli.cjs`.
    if let Ok(exe_path) = std::env::current_exe() {
        if let Some(exe_dir) = exe_path.parent() {
            let bundled = exe_dir
                .join("resources")
                .join("hermes")
                .join("hermes-cli.cjs");
            if bundled.is_file() {
                return Some(bundled.to_string_lossy().to_string());
            }
        }
    }

    // 1. Dev mode: hermes-build/dist sits one level up from
    //    src-tauri/. The CJS bundle is at
    //    `../hermes-build/src/hermes/dist/hermes-cli.cjs` from
    //    the `src-tauri/` cwd. We try a few likely cwds to be
    //    forgiving.
    let cwd_candidates = [
        "resources/hermes/hermes-cli.cjs",
        "../hermes-build/src/hermes/dist/hermes-cli.cjs",
        "../src/hermes/dist/hermes-cli.cjs",
    ];
    for rel in cwd_candidates {
        let p = PathBuf::from(rel);
        if p.is_file() {
            if let Ok(abs) = p.canonicalize() {
                return Some(abs.to_string_lossy().to_string());
            }
        }
    }

    // 2. Tauri's `app.path().resource_dir()` is the official
    //    runtime resolver for `bundle.resources`. We don't have
    //    an AppHandle in this free function, so callers that
    //    need the exact `app.path()` resolution should pass
    //    that result into `start_detached_gateway` directly.
    //    For everything else (the front-end "重拉起" button,
    //    diagnostics, agent toolsets), the cwd probe above is
    //    good enough.
    None
}

/// Build a platform-appropriate detached launch command for the
/// Hermes dashboard. We deliberately keep the shell-string
/// approach so the user retains full control over which `node`
/// and `hermes-cli.cjs` get executed. The command is
///   `<node> <hermes-cli.cjs> dashboard start --port <9119> --no-open`
/// piped to a log file.
fn build_dashboard_launch_command(node_path: &str, cli_source: &str) -> String {
    #[cfg(target_os = "windows")]
    {
        // `%TEMP%` is the closest Windows analogue to `/tmp`. `start "" /B`
        // forks the dashboard without blocking the parent shell. The
        // leading empty title argument is required by `start` so the first
        // quoted token is not interpreted as the new window's title.
        let log = "%TEMP%\\hermes-dashboard.log";
        format!(
            "start \"\" /B {} {} dashboard start --port {} --no-open > {} 2>&1",
            shell_quote(node_path),
            shell_quote(cli_source),
            HERMES_DASHBOARD_PORT,
            log
        )
    }
    #[cfg(not(target_os = "windows"))]
    {
        format!(
            "nohup {} {} dashboard start --port {} --no-open >/tmp/hermes-dashboard.log 2>&1 &",
            shell_quote(node_path),
            shell_quote(cli_source),
            HERMES_DASHBOARD_PORT
        )
    }
}

/// Build a platform-appropriate "kill anything listening on PORT"
/// fragment. We rely on the shell, but the syntax is OS-specific.
fn build_port_kill_command(port: u16) -> String {
    #[cfg(target_os = "windows")]
    {
        // For each LISTEN line containing :PORT, the PID is the 5th token
        // of `netstat -ano`. We feed it into `taskkill /F /PID`. Errors
        // from `findstr` when nothing matches are non-fatal — the `for /f`
        // simply iterates zero lines and we fall through to the echo.
        format!(
            "for /f \"tokens=5\" %p in ('netstat -ano ^| findstr :{port} ^| findstr LISTENING') do taskkill /F /PID %p >NUL 2>&1",
            port = port
        )
    }
    #[cfg(not(target_os = "windows"))]
    {
        format!(
            "pids=$(lsof -tiTCP:{port} -sTCP:LISTEN 2>/dev/null); if [ -n \"$pids\" ]; then kill $pids; fi;",
            port = port
        )
    }
}

pub fn restart_gateway_process() -> Result<bool, String> {
    // The embedded server is the gateway. `ensure_embedded_server_running`
    // is idempotent — it returns the existing handles if the listeners are
    // already bound — so a "restart" is just "make sure it's running". If
    // the OS port table has somehow lost the bind, this rebinds.
    start_detached_gateway()?;
    Ok(true)
  }

  /// Cross-platform launch of the Hermes gateway. This no longer
  /// spawns a `node` sidecar: we just spin up the in-process
  /// axum-based embedded server on the standard gateway port
  /// (8642). The previous Node implementation required a
  /// `node.exe` binary + a `hermes-cli.cjs` script in the bundle,
  /// which made installer bloat + Windows Defender prompts a
  /// constant problem (every fresh `node.exe` triggers a firewall
  /// popup, every `cwd` mismatch crashed the re-attached
  /// grandchild process). The embedded server has
  /// none of those failure modes.
  ///
  /// Idempotent: a second call returns the existing
  /// `EmbeddedHandles` without rebinding. The TCP probe loop in
  /// `ensure_gateway_running` confirms the port is accepting
  /// connections before declaring success.
  pub fn start_detached_gateway() -> Result<(), String> {
    let handles = crate::hermes::embedded_server::ensure_embedded_server_running(
        HERMES_GATEWAY_PORT,
        HERMES_DASHBOARD_PORT,
    )
    .map_err(|e| format!("Failed to start embedded Hermes server: {}", e))?;
    log::info!(
      "[Hermes Gateway] embedded server up on [::]:{} (gateway) / [::]:{} (dashboard)",
      handles.gateway_port, handles.dashboard_port
    );
    Ok(())
  }

  /// Ensure the Hermes gateway HTTP server is reachable. Idempotent —
  /// returns `true` immediately if a TCP probe to 127.0.0.1:8642 already
  /// succeeds. Otherwise starts the in-process embedded server
  /// (no `node` sidecar, no shell spawn) and waits up to 15s for
  /// the port to start accepting connections.
  ///
  /// This is the entry point used by the front-end "重拉起" button and
  /// the auto-recovery loop, so it must work on Windows / macOS / Linux
  /// without any Unix-only tools.
  ///
  /// **No console window on Windows.** The embedded server spins up
  /// an axum listener inside the `tupai.exe` process — there is no
  /// child process to flash a console. The shell-level
  /// `hermes gateway start` fallback that used to live here was
  /// removed: we no longer ship a `hermes` CLI binary in the
  /// bundle, so the only path that can bring the port up is the
  /// embedded one.
  pub fn ensure_gateway_running() -> Result<bool, String> {
    if check_gateway_running_internal() {
      return Ok(true);
    }

    log::warn!("[Hermes Gateway] 未检测到端口 {}, 尝试拉起...", HERMES_GATEWAY_PORT);

    // No shell-level start (the hermes CLI is gone). Spin up
    // the embedded axum server directly. start_detached_gateway is
    // idempotent so a duplicate call is a no-op.
    start_detached_gateway()?;

    // Wait up to 10s for the gateway port to accept connections.
    // The embedded server binds in a tokio task, so we need a
    // short grace period for the happy-eyeballs probe to succeed.
    // 200ms timeout × 50 attempts = 10s 上限。
    for attempt in 0..50 {
      if let Ok(mut addrs) = ("127.0.0.1", HERMES_GATEWAY_PORT).to_socket_addrs() {
        if addrs.any(|addr| TcpStream::connect_timeout(&addr, std::time::Duration::from_millis(200)).is_ok()) {
          log::info!("[Hermes Gateway] ✅ embedded server ready (attempt {})", attempt);
          return Ok(true);
        }
      }
      std::thread::sleep(std::time::Duration::from_millis(200));
    }

    Err(format!(
        "Hermes gateway did not become reachable on 127.0.0.1:{} within 10 seconds. \
         The embedded server failed to bind — check the dev console for the actual error.",
        HERMES_GATEWAY_PORT
    ))
  }

  /// Ensure the Hermes dashboard HTTP server is reachable. Same
  /// idempotent contract as `ensure_gateway_running` but uses the
  /// dashboard port (9119) and the `dashboard` subcommand.
  pub fn ensure_dashboard_running() -> HermesDashboardRestartResult {
    if check_dashboard_running_internal() {
      return HermesDashboardRestartResult {
        success: true,
        command: String::new(),
        message: "Hermes Dashboard already running.".to_string(),
      };
    }
    restart_dashboard_process_internal()
  }

  pub fn restart_dashboard_process_internal() -> HermesDashboardRestartResult {
    // The dashboard shares the embedded server. The previous
    // implementation spawned `node hermes-cli.cjs dashboard --port
    // 9119` via a shell, which required the `node` sidecar and
    // `hermes-cli.cjs` resource to be in the bundle. With the
    // embedded server the dashboard port (9119) is bound by the
    // same axum instance the gateway uses, so we just make sure the
    // listeners are up.
    match start_detached_gateway() {
        Ok(()) => {
            // Probe the dashboard port; the embedded server's
            // happy-eyeballs bind usually takes <100ms, but give
            // 15s budget for cold-start.
            let address = ("127.0.0.1", HERMES_DASHBOARD_PORT);
            let timeout = std::time::Duration::from_secs(2);
            let mut started = false;
            for _ in 0..30 {
                if let Ok(mut addrs) = address.to_socket_addrs() {
                    if addrs.any(|addr| TcpStream::connect_timeout(&addr, timeout).is_ok()) {
                        started = true;
                        break;
                    }
                }
                std::thread::sleep(std::time::Duration::from_millis(500));
            }
            HermesDashboardRestartResult {
                success: started,
                command: format!("embedded_server::ensure_embedded_server_running({}, {})", HERMES_GATEWAY_PORT, HERMES_DASHBOARD_PORT),
                message: if started {
                    "Hermes Dashboard restarted successfully (embedded).".to_string()
                } else {
                    "Dashboard did not become reachable on port 9119 within 15 seconds (embedded bind failed).".to_string()
                },
            }
        }
        Err(e) => HermesDashboardRestartResult {
            success: false,
            command: "embedded_server::ensure_embedded_server_running".to_string(),
            message: format!("Failed to start embedded server: {}", e),
        },
    }
  }

  fn check_gateway_running_internal() -> bool {
    let address = ("127.0.0.1", HERMES_GATEWAY_PORT);
    let timeout = std::time::Duration::from_secs(2);
    if let Ok(mut addrs) = address.to_socket_addrs() {
      return addrs.any(|addr| TcpStream::connect_timeout(&addr, timeout).is_ok());
    }
    false
  }

  fn check_dashboard_running_internal() -> bool {
    let addresses = [
      ("127.0.0.1", HERMES_DASHBOARD_PORT),
      ("localhost", HERMES_DASHBOARD_PORT),
    ];
    let timeout = std::time::Duration::from_secs(3);

    log::debug!("[check_dashboard_running] 开始检测 Dashboard 状态...");

    for addr in addresses.iter() {
      log::debug!("[check_dashboard_running] 尝试连接: {}:{}", addr.0, addr.1);
      if let Ok(mut addrs) = addr.to_socket_addrs() {
        for socket_addr in addrs.clone() {
          log::debug!("[check_dashboard_running] 解析到地址: {}", socket_addr);
        }
        if addrs.any(|socket_addr| {
          let result = TcpStream::connect_timeout(&socket_addr, timeout);
          log::debug!("[check_dashboard_running] 连接 {} 结果: {:?}", socket_addr, result.is_ok());
          result.is_ok()
        }) {
          log::debug!("[check_dashboard_running] ✅ 检测到 Dashboard 运行");
          return true;
        }
      } else {
        log::debug!("[check_dashboard_running] ❌ 地址解析失败: {}:{}", addr.0, addr.1);
      }
    }
    log::debug!("[check_dashboard_running] ❌ 所有地址均无法连接");
    false
  }

  fn stop_dashboard_process_internal() -> bool {
    stop_port_listener_internal(HERMES_DASHBOARD_PORT, "dashboard")
  }

  fn stop_gateway_process_internal() -> bool {
    stop_port_listener_internal(HERMES_GATEWAY_PORT, "gateway")
  }

  fn stop_port_listener_internal(port: u16, label: &str) -> bool {
    let command = build_port_kill_command(port);
    match run_login_shell_command(&command) {
      Ok(output) => {
        let stdout = String::from_utf8_lossy(&output.stdout);
        let stderr = String::from_utf8_lossy(&output.stderr);
        log::info!("[stop_{}] stdout: {}", label, stdout);
        if !output.status.success() {
          log::warn!("[stop_{}] stderr: {}", label, stderr);
        }
        output.status.success()
      }
      Err(e) => {
        log::error!("[stop_{}] Error: {}", label, e);
        false
      }
    }
  }

fn extract_hermes_dashboard_token(html: &str) -> Option<String> {
    let marker = "window.__HERMES_SESSION_TOKEN__=\"";
    let start = html.find(marker)? + marker.len();
    let rest = &html[start..];
    let end = rest.find('"')?;
    Some(rest[..end].to_string())
}

async fn hermes_dashboard_client() -> Result<reqwest::Client, String> {
    reqwest::Client::builder()
        .no_proxy()
        .timeout(std::time::Duration::from_secs(12))
        .build()
        .map_err(|e| format!("Failed to create Hermes dashboard client: {}", e))
}

async fn hermes_dashboard_token(client: &reqwest::Client) -> Result<String, String> {
    let html = client
        .get(format!("{}/cron", HERMES_WEB_PANEL_BASE_URL))
        .send()
        .await
        .map_err(|e| format!("Failed to load Hermes dashboard page: {}", e))?
        .text()
        .await
        .map_err(|e| format!("Failed to read Hermes dashboard page: {}", e))?;

    extract_hermes_dashboard_token(&html)
        .ok_or_else(|| "Hermes dashboard session token not found in /cron page".to_string())
}

async fn hermes_dashboard_api_request(
    method: reqwest::Method,
    path: &str,
    body: Option<serde_json::Value>,
) -> Result<reqwest::Response, String> {
    let client = hermes_dashboard_client().await?;
    let token = hermes_dashboard_token(&client).await?;
    let url = format!("{}{}", HERMES_WEB_PANEL_BASE_URL, path);
    let mut request = client
        .request(method, &url)
        .header("Authorization", format!("Bearer {}", token));

    if let Some(payload) = body {
        request = request.json(&payload);
    }

    let response = request
        .send()
        .await
        .map_err(|e| format!("Failed to call Hermes dashboard API {}: {}", path, e))?;

    if response.status().is_success() {
        return Ok(response);
    }

    let status = response.status();
    let body_text = response.text().await.unwrap_or_default();
    let detail = extract_dashboard_error_detail(&body_text).unwrap_or_else(|| body_text.clone());
    Err(format!(
        "Hermes dashboard API {} failed: HTTP {} {}",
        path, status, detail
    ))
}

async fn hermes_dashboard_public_api_request(
    method: reqwest::Method,
    path: &str,
    body: Option<serde_json::Value>,
) -> Result<reqwest::Response, String> {
    let client = hermes_dashboard_client().await?;
    let url = format!("{}{}", HERMES_WEB_PANEL_BASE_URL, path);
    let mut request = client.request(method, &url);

    if let Some(payload) = body {
        request = request.json(&payload);
    }

    let response = request
        .send()
        .await
        .map_err(|e| format!("Failed to call Hermes dashboard API {}: {}", path, e))?;

    if response.status().is_success() {
        return Ok(response);
    }

    let status = response.status();
    let body_text = response.text().await.unwrap_or_default();
    let detail = extract_dashboard_error_detail(&body_text).unwrap_or_else(|| body_text.clone());
    Err(format!(
        "Hermes dashboard API {} failed: HTTP {} {}",
        path, status, detail
    ))
}

#[derive(Deserialize, Default)]
struct SkillFrontmatter {
    name: Option<String>,
    description: Option<String>,
    category: Option<String>,
    version: Option<String>,
    tags: Option<Vec<String>>,
    metadata: Option<SkillFrontmatterMetadata>,
}

#[derive(Deserialize, Default)]
struct SkillFrontmatterMetadata {
    hermes: Option<SkillFrontmatterHermesMetadata>,
}

#[derive(Deserialize, Default)]
struct SkillFrontmatterHermesMetadata {
    tags: Option<Vec<String>>,
}

#[derive(Deserialize, Default)]
struct HubLockFileData {
    #[serde(default)]
    installed: HashMap<String, HubInstalledSkill>,
}

#[derive(Deserialize, Clone, Default)]
struct HubInstalledSkill {
    #[serde(default)]
    identifier: String,
    #[serde(default, rename = "source")]
    _source: String,
    #[serde(default)]
    trust_level: String,
}

#[derive(Deserialize)]
struct SkillsIndexPayload {
    skills: Vec<SkillsIndexEntry>,
}

#[derive(Deserialize)]
struct SkillsIndexEntry {
    name: String,
    description: String,
    source: String,
    identifier: String,
    trust_level: String,
    #[serde(default)]
    repo: String,
    #[serde(default)]
    path: String,
    #[serde(default)]
    tags: Vec<String>,
}

fn expand_home_path(value: &str) -> PathBuf {
    if value == "~" {
        return dirs::home_dir().unwrap_or_else(|| PathBuf::from(value));
    }

    if let Some(stripped) = value.strip_prefix("~/") {
        if let Some(home) = dirs::home_dir() {
            return home.join(stripped);
        }
    }

    PathBuf::from(value)
}

fn get_hermes_home_dir() -> PathBuf {
    if let Ok(path) = std::env::var("HERMES_HOME") {
        return expand_home_path(&path);
    }

    if let Some(home) = dirs::home_dir() {
        return home.join(".hermes");
    }

    if let Ok(home) = std::env::var("HOME") {
        return expand_home_path(&home).join(".hermes");
    }

    PathBuf::from(".hermes")
}

/// 暴露给前端：`~` 展开、tupAI 默认工作区解析、
/// 终端 cwd fallback 都依赖这个。renderer 里 `process.env` 拿不到
/// 真实 home（没有 Node 主进程），必须由 Rust 端代为查询。
#[tauri::command]
pub fn get_home_dir() -> Result<String, String> {
    let path = dirs::home_dir()
        .or_else(|| std::env::var("HOME").ok().map(PathBuf::from))
        .or_else(|| {
            let drive = std::env::var("HOMEDRIVE").ok();
            let path = std::env::var("HOMEPATH").ok();
            match (drive, path) {
                (Some(d), Some(p)) if !d.is_empty() && !p.is_empty() => {
                    Some(PathBuf::from(format!("{}{}", d, p)))
                }
                _ => None,
            }
        })
        .ok_or_else(|| "无法定位用户主目录".to_string())?;
    Ok(path.to_string_lossy().to_string())
}

/// 把 `~/foo/bar` 之类的字面量展开为绝对路径。
/// 前端在把工作区路径交给 Tauri 命令前调用，避免 Windows 上
/// Rust 把 `~` 当成字面目录名而文件操作失败。
#[tauri::command]
pub fn expand_home_path_command(value: String) -> Result<String, String> {
    Ok(expand_home_path(&value).to_string_lossy().to_string())
}

pub fn get_hermes_skills_dir() -> PathBuf {
    get_hermes_home_dir().join("skills")
}

fn get_hermes_config_path() -> PathBuf {
    get_hermes_home_dir().join("config.yaml")
}

fn get_hermes_env_path() -> PathBuf {
    get_hermes_home_dir().join(".env")
}

fn collect_configured_model_candidates_from_env_content(content: &str) -> Vec<String> {
    let mut models = Vec::new();
    let mut seen = HashSet::new();

    for raw_line in content.lines() {
        let line = raw_line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }

        let Some((key, value)) = line.split_once('=') else {
            continue;
        };

        if !key.trim().ends_with("_DEFAULT_MODEL") {
            continue;
        }

        let normalized_value = value.trim().trim_matches('"').trim_matches('\'');
        if normalized_value.is_empty() {
            continue;
        }

        if seen.insert(normalized_value.to_string()) {
            models.push(normalized_value.to_string());
        }
    }

    models
}

fn collect_configured_model_candidates(
    config_yaml: &str,
    env_content: &str,
) -> Result<Vec<String>, String> {
    let mut models = Vec::new();
    let mut seen = HashSet::new();

    let config = if config_yaml.trim().is_empty() {
        serde_yaml::Value::Mapping(serde_yaml::Mapping::new())
    } else {
        serde_yaml::from_str::<serde_yaml::Value>(config_yaml)
            .map_err(|e| format!("Failed to parse Hermes config yaml: {}", e))?
    };

    let current_model = config
        .as_mapping()
        .and_then(|mapping| mapping.get(yaml_string_key("model")))
        .and_then(|value| value.as_mapping())
        .and_then(|mapping| mapping.get(yaml_string_key("default")))
        .map(yaml_string_value)
        .unwrap_or_default();

    if !current_model.is_empty() && seen.insert(current_model.clone()) {
        models.push(current_model);
    }

    for model in collect_configured_model_candidates_from_env_content(env_content) {
        if seen.insert(model.clone()) {
            models.push(model);
        }
    }

    Ok(models)
}

fn load_bundled_skill_names() -> HashSet<String> {
    let manifest_path = get_hermes_skills_dir().join(".bundled_manifest");
    std::fs::read_to_string(&manifest_path)
        .ok()
        .map(|content| {
            content
                .lines()
                .filter_map(|line| {
                    line.split_once(':')
                        .map(|(name, _)| name.trim().to_string())
                })
                .filter(|name| !name.is_empty())
                .collect()
        })
        .unwrap_or_default()
}

fn load_hub_installed_skills() -> HashMap<String, HubInstalledSkill> {
    let lock_path = get_hermes_skills_dir().join(".hub").join("lock.json");

    std::fs::read_to_string(&lock_path)
        .ok()
        .and_then(|content| serde_json::from_str::<HubLockFileData>(&content).ok())
        .map(|data| data.installed)
        .unwrap_or_default()
}

pub fn load_hermes_config_yaml() -> Result<serde_yaml::Value, String> {
    let config_path = get_hermes_config_path();

    if !config_path.exists() {
        return Ok(serde_yaml::Value::Mapping(serde_yaml::Mapping::new()));
    }

    let content = std::fs::read_to_string(&config_path)
        .map_err(|e| format!("Failed to read {}: {}", config_path.display(), e))?;
    serde_yaml::from_str::<serde_yaml::Value>(&content)
        .map_err(|e| format!("Failed to parse {}: {}", config_path.display(), e))
}

pub fn extract_disabled_skills(config: &serde_yaml::Value) -> HashSet<String> {
    config
        .get("skills")
        .and_then(|skills| skills.get("disabled"))
        .and_then(|disabled| disabled.as_sequence())
        .map(|items| {
            items
                .iter()
                .filter_map(|item| item.as_str().map(|value| value.to_string()))
                .collect()
        })
        .unwrap_or_default()
}

pub fn save_disabled_skills(disabled: &HashSet<String>) -> Result<(), String> {
    let config_path = get_hermes_config_path();
    let mut config = load_hermes_config_yaml()?;

    if !matches!(config, serde_yaml::Value::Mapping(_)) {
        config = serde_yaml::Value::Mapping(serde_yaml::Mapping::new());
    }

    let root = config
        .as_mapping_mut()
        .ok_or_else(|| "Invalid config root".to_string())?;

    let skills_key = serde_yaml::Value::String("skills".to_string());
    if !root.contains_key(&skills_key) {
        root.insert(
            skills_key.clone(),
            serde_yaml::Value::Mapping(serde_yaml::Mapping::new()),
        );
    }

    let skills_value = root
        .get_mut(&skills_key)
        .ok_or_else(|| "Failed to access skills config".to_string())?;

    if !matches!(skills_value, serde_yaml::Value::Mapping(_)) {
        *skills_value = serde_yaml::Value::Mapping(serde_yaml::Mapping::new());
    }

    let skills_mapping = skills_value
        .as_mapping_mut()
        .ok_or_else(|| "Invalid skills config".to_string())?;

    let mut disabled_list: Vec<String> = disabled.iter().cloned().collect();
    disabled_list.sort();

    skills_mapping.insert(
        serde_yaml::Value::String("disabled".to_string()),
        serde_yaml::Value::Sequence(
            disabled_list
                .into_iter()
                .map(serde_yaml::Value::String)
                .collect(),
        ),
    );

    let content = serde_yaml::to_string(&config)
        .map_err(|e| format!("Failed to serialize config.yaml: {}", e))?;
    std::fs::write(&config_path, content)
        .map_err(|e| format!("Failed to write {}: {}", config_path.display(), e))
}

fn split_skill_frontmatter(content: &str) -> (Option<&str>, &str) {
    if !content.starts_with("---") {
        return (None, content);
    }

    let rest = &content[3..];
    if let Some(offset) = rest.find("\n---") {
        let frontmatter = rest[..offset].trim_matches('\n');
        let body = rest[offset + 4..].trim_start_matches('\n');
        return (Some(frontmatter), body);
    }

    (None, content)
}

fn parse_skill_frontmatter(content: &str) -> SkillFrontmatter {
    let (frontmatter, _) = split_skill_frontmatter(content);
    frontmatter
        .and_then(|value| serde_yaml::from_str::<SkillFrontmatter>(value).ok())
        .unwrap_or_default()
}

fn summarize_skill_body(content: &str) -> String {
    let (_, body) = split_skill_frontmatter(content);
    let normalized = body
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty())
        .take(16)
        .collect::<Vec<_>>()
        .join("\n");

    if normalized.len() <= 1500 {
        normalized
    } else {
        format!("{}...", normalized.chars().take(1500).collect::<String>())
    }
}

fn collect_skill_tags(frontmatter: &SkillFrontmatter) -> Vec<String> {
    let mut tags = frontmatter.tags.clone().unwrap_or_default();

    if let Some(metadata_tags) = frontmatter
        .metadata
        .as_ref()
        .and_then(|metadata| metadata.hermes.as_ref())
        .and_then(|hermes| hermes.tags.clone())
    {
        tags.extend(metadata_tags);
    }

    tags.sort();
    tags.dedup();
    tags
}

fn derive_skill_category(relative_dir: &Path, frontmatter: &SkillFrontmatter) -> Option<String> {
    if let Some(category) = frontmatter.category.clone() {
        let trimmed = category.trim().to_string();
        if !trimmed.is_empty() {
            return Some(trimmed);
        }
    }

    let components: Vec<String> = relative_dir
        .components()
        .filter_map(|component| {
            component
                .as_os_str()
                .to_str()
                .map(|value| value.to_string())
        })
        .collect();

    if components.len() > 1 {
        Some(components[..components.len() - 1].join("/"))
    } else {
        None
    }
}

fn build_skill_info(
    skill_file: &Path,
    disabled_skills: &HashSet<String>,
    bundled_skills: &HashSet<String>,
    hub_skills: &HashMap<String, HubInstalledSkill>,
) -> Result<SkillInfo, String> {
    let skills_dir = get_hermes_skills_dir();
    let relative_file = skill_file
        .strip_prefix(&skills_dir)
        .map_err(|e| format!("Failed to derive relative skill path: {}", e))?;
    let relative_dir = relative_file
        .parent()
        .ok_or_else(|| "Skill file has no parent directory".to_string())?;
    let content = std::fs::read_to_string(skill_file)
        .map_err(|e| format!("Failed to read {}: {}", skill_file.display(), e))?;
    let frontmatter = parse_skill_frontmatter(&content);
    let skill_name = frontmatter
        .name
        .clone()
        .or_else(|| {
            relative_dir
                .file_name()
                .and_then(|name| name.to_str())
                .map(|name| name.to_string())
        })
        .ok_or_else(|| "Unable to determine skill name".to_string())?;

    let hub_entry = hub_skills.get(&skill_name);
    let source = if hub_entry.is_some() {
        "hub".to_string()
    } else if bundled_skills.contains(&skill_name) {
        "builtin".to_string()
    } else {
        "local".to_string()
    };

    let trust = hub_entry
        .map(|entry| entry.trust_level.clone())
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| source.clone());

    Ok(SkillInfo {
        name: skill_name.clone(),
        description: frontmatter
            .description
            .clone()
            .unwrap_or_else(|| "No description available.".to_string()),
        category: derive_skill_category(relative_dir, &frontmatter),
        enabled: !disabled_skills.contains(&skill_name),
        source,
        trust,
        identifier: hub_entry
            .map(|entry| entry.identifier.clone())
            .filter(|value| !value.trim().is_empty()),
        version: frontmatter.version.clone(),
        tags: collect_skill_tags(&frontmatter),
        path: skill_file.display().to_string(),
    })
}

pub fn collect_installed_skills() -> Result<Vec<SkillInfo>, String> {
    let skills_dir = get_hermes_skills_dir();
    if !skills_dir.exists() {
        return Ok(Vec::new());
    }

    let disabled_skills = extract_disabled_skills(&load_hermes_config_yaml()?);
    let bundled_skills = load_bundled_skill_names();
    let hub_skills = load_hub_installed_skills();
    let mut skill_map = HashMap::new();

    for entry in WalkDir::new(&skills_dir)
        .into_iter()
        .filter_map(Result::ok)
        .filter(|entry| entry.file_type().is_file())
    {
        if entry.file_name() != "SKILL.md" {
            continue;
        }

        let path = entry.path();
        if path
            .components()
            .any(|component| component.as_os_str().to_string_lossy().starts_with('.'))
        {
            continue;
        }

        let skill = build_skill_info(path, &disabled_skills, &bundled_skills, &hub_skills)?;
        skill_map.insert(skill.name.clone(), skill);
    }

    let mut skills: Vec<SkillInfo> = skill_map.into_values().collect();
    skills.sort_by(|left, right| left.name.cmp(&right.name));
    Ok(skills)
}

pub fn load_installed_skill_detail(name: &str) -> Result<SkillDetail, String> {
    let skills = collect_installed_skills()?;
    let skill = skills
        .into_iter()
        .find(|skill| skill.name == name)
        .ok_or_else(|| format!("Skill not found: {}", name))?;
    let content = std::fs::read_to_string(&skill.path)
        .map_err(|e| format!("Failed to read {}: {}", skill.path, e))?;

    Ok(SkillDetail {
        skill,
        content_preview: summarize_skill_body(&content),
        content,
    })
}

fn shell_quote(value: &str) -> String {
    #[cfg(target_os = "windows")]
    {
        // cmd.exe quoting: wrap in double quotes and escape embedded
        // double quotes with `\"`. Backslashes are kept as-is — they are
        // only special inside the quoted run, not before the quote. We
        // also need to escape the trailing backslash if the value ends
        // with `\`, otherwise the closing `"` is consumed.
        let mut escaped = String::with_capacity(value.len() + 2);
        escaped.push('"');
        let mut chars = value.chars().peekable();
        while let Some(c) = chars.next() {
            match c {
                '"' => escaped.push_str("\\\""),
                '\\' => {
                    // If the next char is a `"` or another backslash,
                    // double it; if this is the trailing backslash, double
                    // it as well.
                    match chars.peek() {
                        Some('"') | Some('\\') => {
                            escaped.push('\\');
                            escaped.push('\\');
                        }
                        None => {
                            escaped.push('\\');
                            escaped.push('\\');
                        }
                        _ => escaped.push('\\'),
                    }
                }
                _ => escaped.push(c),
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

fn run_hermes_owned_command(arguments: Vec<String>) -> Result<SkillCommandResult, String> {
    run_hermes_owned_command_impl(arguments, /*async_mode=*/ false)
}

async fn run_hermes_owned_command_async(arguments: Vec<String>) -> Result<SkillCommandResult, String> {
    // v5: the hermes CLI binary is gone — we use the in-process
    // axum-based embedded server. Sub-commands that used to
    // delegate to a spawned `node hermes-cli.cjs ...` (skills
    // install / uninstall, gateway restart, whatsapp --qr, etc.)
    // are no longer reachable. We return a structured
    // "not_available_in_embedded" success with an explanatory
    // message so the front-end can display a friendly message
    // instead of crashing. The actual work is now served via
    // the embedded server's HTTP routes (see
    // `hermes::embedded_server`).
    let label = arguments.join(" ");
    log::warn!(
        "[run_hermes_owned_command] no-op in v5 (embedded mode): `{}`",
        label
    );
    tokio::task::spawn_blocking(move || run_hermes_owned_command_impl(arguments, /*async_mode=*/ true))
        .await
        .map_err(|e| format!("Failed to join hermes sub-command task: {}", e))?
}

/// Shared implementation for `run_hermes_owned_command` (sync)
/// and `run_hermes_owned_command_async` (async wrapper above).
///
/// This used to spawn `<node> <hermes-cli.cjs> <args>`
/// as a real child process. With the node sidecar removed from
/// the bundle (see PR "砍掉 node sidecar") the function no
/// longer spawns anything — it returns a structured
/// `SkillCommandResult` with `success=false` and a `stderr`
/// explaining the migration. The front-end surfaces this as
/// the existing "Hermes 服务未连接" banner. Real work goes
/// through the embedded server's HTTP API instead.
fn run_hermes_owned_command_impl(
    arguments: Vec<String>,
    _async_mode: bool,
) -> Result<SkillCommandResult, String> {
    let label = arguments.join(" ");
    let msg = format!(
        "Hermes CLI sub-commands are unavailable in v5 (embedded mode). \
         Command `{}` is no longer routed to a `node` sidecar. \
         Use the embedded HTTP server (http://127.0.0.1:8642) instead.",
        label
    );
    log::debug!("[run_hermes_owned_command_impl] v5 stub: {}", msg);
    Ok(SkillCommandResult {
        success: false,
        stdout: String::new(),
        stderr: msg,
    })
}

pub fn run_hermes_command(arguments: &[&str]) -> Result<SkillCommandResult, String> {
    run_hermes_owned_command(arguments.iter().map(|value| value.to_string()).collect())
}

fn strip_toolset_prefix(value: &str) -> String {
    value
        .trim_start_matches(|character: char| !character.is_alphanumeric())
        .trim()
        .to_string()
}

fn strip_ansi_codes(value: &str) -> String {
    let mut result = String::new();
    let mut chars = value.chars().peekable();

    while let Some(character) = chars.next() {
        if character == '\u{1b}' {
            if matches!(chars.peek(), Some('[')) {
                chars.next();
                for next in chars.by_ref() {
                    if ('@'..='~').contains(&next) {
                        break;
                    }
                }
            }
            continue;
        }

        result.push(character);
    }

    result
}

pub fn parse_toolsets_list(output: &str) -> Vec<ToolsetInfo> {
    output
        .lines()
        .filter_map(|line| {
            let trimmed = line.trim();
            if trimmed.is_empty() || trimmed.ends_with(':') {
                return None;
            }

            let parts = trimmed.split_whitespace().collect::<Vec<_>>();
            if parts.len() < 4 {
                return None;
            }

            let enabled = match parts.get(1).copied() {
                Some("enabled") => true,
                Some("disabled") => false,
                _ => return None,
            };

            let name = parts.get(2)?.to_string();
            let rest = parts[3..].join(" ");
            let description = strip_toolset_prefix(&rest);

            Some(ToolsetInfo {
                name,
                label: description.clone(),
                description,
                enabled,
                configured: enabled,
                tools: Vec::new(),
            })
        })
        .collect()
}

fn derive_market_category(entry: &SkillsIndexEntry) -> Option<String> {
    entry
        .path
        .split('/')
        .next()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
}

#[tauri::command]
pub async fn get_market_skills() -> Result<Vec<MarketSkillInfo>, String> {
    let installed_skills = tokio::task::spawn_blocking(collect_installed_skills)
        .await
        .map_err(|e| format!("Hermes skills scan task failed: {}", e))??;
    let skills_dir = get_hermes_skills_dir();
    let installed_by_name = installed_skills
        .iter()
        .map(|skill| (skill.name.clone(), skill.source.clone()))
        .collect::<HashMap<_, _>>();
    let installed_by_identifier = installed_skills
        .iter()
        .filter_map(|skill| {
            skill.identifier
                .clone()
                .map(|identifier| (identifier, skill.source.clone()))
        })
        .collect::<HashMap<_, _>>();
    let installed_by_path = installed_skills
        .iter()
        .filter_map(|skill| {
            Path::new(&skill.path)
                .strip_prefix(&skills_dir)
                .ok()
                .and_then(|relative_file| relative_file.parent())
                .map(|relative_dir| {
                    (
                        relative_dir.to_string_lossy().replace('\\', "/"),
                        skill.source.clone(),
                    )
                })
        })
        .collect::<HashMap<_, _>>();

    let client = reqwest::Client::builder()
        .no_proxy()
        .timeout(std::time::Duration::from_secs(15))
        .build()
        .map_err(|e| e.to_string())?;
    let payload = client
        .get(HERMES_SKILLS_INDEX_URL)
        .send()
        .await
        .map_err(|e| format!("Failed to fetch skills index: {}", e))?
        .json::<SkillsIndexPayload>()
        .await
        .map_err(|e| format!("Failed to parse skills index: {}", e))?;

    let mut skills = payload
        .skills
        .into_iter()
        .map(|entry| {
            let category = derive_market_category(&entry);
            let installed_source = installed_by_identifier
                .get(&entry.identifier)
                .cloned()
                .or_else(|| installed_by_name.get(&entry.name).cloned())
                .or_else(|| installed_by_path.get(&entry.path).cloned());

            MarketSkillInfo {
                name: entry.name,
                description: entry.description,
                source: entry.source,
                identifier: entry.identifier,
                trust_level: entry.trust_level,
                repo: entry.repo,
                path: entry.path,
                category,
                tags: entry.tags,
                installed: installed_source.is_some(),
                installed_source,
            }
        })
        .collect::<Vec<_>>();

    skills.sort_by(|left, right| left.name.cmp(&right.name));
    Ok(skills)
}

#[tauri::command]
pub async fn install_skill(identifier: String) -> Result<SkillCommandResult, String> {
    run_hermes_owned_command_async(vec![
        "skills".to_string(),
        "install".to_string(),
        identifier,
        "--yes".to_string(),
    ])
    .await
}

#[tauri::command]
pub async fn uninstall_skill(name: String) -> Result<SkillCommandResult, String> {
    run_hermes_owned_command_async(vec![
        "skills".to_string(),
        "uninstall".to_string(),
        name,
    ])
    .await
}

#[tauri::command]
pub async fn check_skill_updates(name: Option<String>) -> Result<SkillCommandResult, String> {
    let mut args = vec!["skills".to_string(), "check".to_string()];
    if let Some(value) = name {
        args.push(value);
    }

    run_hermes_owned_command_async(args).await
}

#[tauri::command]
pub async fn update_skill(name: Option<String>) -> Result<SkillCommandResult, String> {
    let mut args = vec!["skills".to_string(), "update".to_string()];
    if let Some(value) = name {
        args.push(value);
    }

    run_hermes_owned_command_async(args).await
}

#[tauri::command]
pub async fn inspect_market_skill(identifier: String) -> Result<SkillCommandResult, String> {
    let result = run_hermes_owned_command_async(vec![
        "skills".to_string(),
        "inspect".to_string(),
        identifier,
    ])
    .await?;

    Ok(SkillCommandResult {
        success: result.success,
        stdout: strip_ansi_codes(&result.stdout),
        stderr: strip_ansi_codes(&result.stderr),
    })
}

#[tauri::command]
pub async fn get_cron_jobs() -> Result<Vec<CronJob>, String> {
    hermes_dashboard_api_request(reqwest::Method::GET, "/api/cron/jobs", None)
        .await?
        .json::<Vec<CronJob>>()
        .await
        .map_err(|e| format!("Failed to parse cron jobs: {}", e))
}

#[tauri::command]
pub async fn create_cron_job(input: CreateCronJobInput) -> Result<CronJob, String> {
    let response = hermes_dashboard_api_request(
        reqwest::Method::POST,
        "/api/cron/jobs",
        Some(serde_json::json!({
            "prompt": input.prompt,
            "schedule": input.schedule,
            "name": input.name,
            "deliver": input.deliver,
        })),
    )
    .await
    .map_err(|error| {
        format!("Cron job creation failed: {}", error)
    })?;

    response
        .json::<CronJob>()
        .await
        .map_err(|e| format!("Failed to parse created cron job: {}", e))
}


#[tauri::command]
pub fn restart_hermes_dashboard() -> Result<HermesDashboardRestartResult, String> {
  Ok(restart_dashboard_process_internal())
}

#[tauri::command]
pub fn restart_hermes_gateway() -> Result<bool, String> {
  restart_gateway_process()
}

#[tauri::command]
pub fn ensure_hermes_gateway_running() -> Result<bool, String> {
  ensure_gateway_running()
}

#[tauri::command]
pub fn ensure_hermes_dashboard_running() -> Result<HermesDashboardRestartResult, String> {
  Ok(ensure_dashboard_running())
}

#[tauri::command]
pub fn check_gateway_running() -> Result<bool, String> {
  Ok(check_gateway_running_internal())
}

#[tauri::command]
pub fn check_dashboard_running() -> Result<bool, String> {
  Ok(check_dashboard_running_internal())
}

#[tauri::command]
pub fn stop_hermes_dashboard() -> Result<bool, String> {
  Ok(stop_dashboard_process_internal())
}

#[tauri::command]
pub fn stop_hermes_gateway() -> Result<bool, String> {
  Ok(stop_gateway_process_internal())
}

  #[tauri::command]
  pub async fn pause_cron_job(id: String) -> Result<CronActionResult, String> {
    hermes_dashboard_api_request(
        reqwest::Method::POST,
        &format!("/api/cron/jobs/{}/pause", id),
        None,
    )
    .await?
    .json::<CronActionResult>()
    .await
    .map_err(|e| format!("Failed to parse pause response: {}", e))
}

#[tauri::command]
pub async fn resume_cron_job(id: String) -> Result<CronActionResult, String> {
    hermes_dashboard_api_request(
        reqwest::Method::POST,
        &format!("/api/cron/jobs/{}/resume", id),
        None,
    )
    .await?
    .json::<CronActionResult>()
    .await
    .map_err(|e| format!("Failed to parse resume response: {}", e))
}

#[tauri::command]
pub async fn trigger_cron_job(id: String) -> Result<CronActionResult, String> {
    hermes_dashboard_api_request(
        reqwest::Method::POST,
        &format!("/api/cron/jobs/{}/trigger", id),
        None,
    )
    .await?
    .json::<CronActionResult>()
    .await
    .map_err(|e| format!("Failed to parse trigger response: {}", e))
}

#[tauri::command]
pub async fn delete_cron_job(id: String) -> Result<CronActionResult, String> {
    hermes_dashboard_api_request(
        reqwest::Method::DELETE,
        &format!("/api/cron/jobs/{}", id),
        None,
    )
    .await?
    .json::<CronActionResult>()
    .await
    .map_err(|e| format!("Failed to parse delete response: {}", e))
}

#[tauri::command]
pub async fn get_dashboard_logs(
    file: String,
    lines: u32,
    level: String,
    component: String,
) -> Result<DashboardLogsResponse, String> {
    let path = format!(
        "/api/logs?file={}&lines={}&level={}&component={}",
        file, lines, level, component
    );

    hermes_dashboard_api_request(reqwest::Method::GET, &path, None)
        .await?
        .json::<DashboardLogsResponse>()
        .await
        .map_err(|e| format!("Failed to parse logs response: {}", e))
}

#[derive(Serialize, Deserialize)]
struct DashboardConfigRawResponse {
    yaml: String,
}

fn yaml_string_key(key: &str) -> serde_yaml::Value {
    serde_yaml::Value::String(key.to_string())
}

fn yaml_string_value(value: &serde_yaml::Value) -> String {
    match value {
        serde_yaml::Value::String(inner) => inner.trim().to_string(),
        serde_yaml::Value::Null => String::new(),
        _ => value.as_str().unwrap_or_default().trim().to_string(),
    }
}

fn yaml_u64_value(value: &serde_yaml::Value) -> Option<u64> {
    if let Some(number) = value.as_u64() {
        return Some(number);
    }

    if let Some(number) = value.as_i64() {
        return (number >= 0).then_some(number as u64);
    }

    value
        .as_str()
        .and_then(|inner| inner.trim().parse::<u64>().ok())
}

fn ensure_yaml_mapping<'a>(
    mapping: &'a mut serde_yaml::Mapping,
    key: &str,
) -> Result<&'a mut serde_yaml::Mapping, String> {
    let yaml_key = yaml_string_key(key);

    if !mapping.contains_key(&yaml_key) {
        mapping.insert(
            yaml_key.clone(),
            serde_yaml::Value::Mapping(serde_yaml::Mapping::new()),
        );
    }

    let value = mapping
        .get_mut(&yaml_key)
        .ok_or_else(|| format!("Missing YAML mapping for `{}`", key))?;

    if !matches!(value, serde_yaml::Value::Mapping(_)) {
        *value = serde_yaml::Value::Mapping(serde_yaml::Mapping::new());
    }

    value
        .as_mapping_mut()
        .ok_or_else(|| format!("Invalid YAML mapping for `{}`", key))
}

fn set_yaml_string(mapping: &mut serde_yaml::Mapping, key: &str, value: &str) {
    let yaml_key = yaml_string_key(key);

    if value.trim().is_empty() {
        mapping.remove(&yaml_key);
        return;
    }

    mapping.insert(yaml_key, serde_yaml::Value::String(value.trim().to_string()));
}

fn set_yaml_u64(mapping: &mut serde_yaml::Mapping, key: &str, value: Option<u64>) {
    let yaml_key = yaml_string_key(key);

    if let Some(number) = value {
        mapping.insert(yaml_key, serde_yaml::Value::Number(number.into()));
    } else {
        mapping.remove(&yaml_key);
    }
}

fn extract_primary_model_config_from_yaml(
    yaml_text: &str,
) -> Result<DashboardPrimaryModelConfig, String> {
    let trimmed = yaml_text.trim();
    let mut root = if trimmed.is_empty() {
        serde_yaml::Value::Mapping(serde_yaml::Mapping::new())
    } else {
        serde_yaml::from_str::<serde_yaml::Value>(trimmed)
            .map_err(|e| format!("Failed to parse dashboard config yaml: {}", e))?
    };

    if !matches!(root, serde_yaml::Value::Mapping(_)) {
        root = serde_yaml::Value::Mapping(serde_yaml::Mapping::new());
    }

    let root_mapping = root
        .as_mapping()
        .ok_or_else(|| "Dashboard config root is not a YAML mapping".to_string())?;
    let model_mapping = root_mapping
        .get(yaml_string_key("model"))
        .and_then(|value| value.as_mapping());

    Ok(DashboardPrimaryModelConfig {
        model: model_mapping
            .and_then(|mapping| mapping.get(yaml_string_key("default")))
            .map(yaml_string_value)
            .unwrap_or_default(),
        provider: model_mapping
            .and_then(|mapping| mapping.get(yaml_string_key("provider")))
            .map(yaml_string_value)
            .unwrap_or_default(),
        base_url: model_mapping
            .and_then(|mapping| mapping.get(yaml_string_key("base_url")))
            .map(yaml_string_value)
            .unwrap_or_default(),
        api_key: model_mapping
            .and_then(|mapping| mapping.get(yaml_string_key("api_key")))
            .map(yaml_string_value)
            .unwrap_or_default(),
        context_length: root_mapping
            .get(yaml_string_key("model_context_length"))
            .and_then(yaml_u64_value)
            .or_else(|| {
                model_mapping
                    .and_then(|mapping| mapping.get(yaml_string_key("context_length")))
                    .and_then(yaml_u64_value)
            }),
    })
}

fn apply_primary_model_config_to_yaml(
    yaml_text: &str,
    next_config: &DashboardPrimaryModelConfig,
) -> Result<String, String> {
    let trimmed = yaml_text.trim();
    let mut root = if trimmed.is_empty() {
        serde_yaml::Value::Mapping(serde_yaml::Mapping::new())
    } else {
        serde_yaml::from_str::<serde_yaml::Value>(trimmed)
            .map_err(|e| format!("Failed to parse dashboard config yaml: {}", e))?
    };

    if !matches!(root, serde_yaml::Value::Mapping(_)) {
        root = serde_yaml::Value::Mapping(serde_yaml::Mapping::new());
    }

    let root_mapping = root
        .as_mapping_mut()
        .ok_or_else(|| "Dashboard config root is not a YAML mapping".to_string())?;
    let model_mapping = ensure_yaml_mapping(root_mapping, "model")?;

    set_yaml_string(model_mapping, "default", &next_config.model);
    set_yaml_string(model_mapping, "provider", &next_config.provider);
    set_yaml_string(model_mapping, "base_url", &next_config.base_url);
    set_yaml_string(model_mapping, "api_key", &next_config.api_key);
    set_yaml_u64(root_mapping, "model_context_length", next_config.context_length);

    serde_yaml::to_string(&root).map_err(|e| format!("Failed to serialize dashboard config yaml: {}", e))
}

fn fallback_model_options_from_config() -> Result<DashboardModelOptionsResponse, String> {
    let yaml_text = std::fs::read_to_string(get_hermes_config_path()).unwrap_or_default();
    let primary = extract_primary_model_config_from_yaml(&yaml_text)?;
    let provider = primary.provider.trim().to_string();
    let model = primary.model.trim().to_string();

    // Build a full provider+model list from the static model
    // catalog so the front-end dropdown isn't limited to the one
    // active model. We then pin the currently-active (provider,
    // model) at the top of the list, mirroring the live
    // `/api/model/options` shape from the embedded server.
    use crate::hermes::model_catalog::models_by_provider;

    let mut providers: Vec<DashboardModelProvider> = Vec::new();
    let mut emitted_providers: std::collections::HashSet<String> = std::collections::HashSet::new();
    for (prov_id, prov_label, model_ids) in models_by_provider() {
        if !emitted_providers.insert(prov_id.to_string()) {
            continue;
        }
        let mut models: Vec<DashboardModelOption> = Vec::new();
        if prov_id.eq_ignore_ascii_case(&provider) && !model.is_empty() {
            let already_listed = model_ids.iter().any(|m| *m == model);
            if !already_listed {
                models.push(DashboardModelOption {
                    id: model.clone(),
                    label: model.clone(),
                });
            }
        }
        for id in model_ids {
            models.push(DashboardModelOption {
                id: (*id).to_string(),
                label: (*id).to_string(),
            });
        }
        providers.push(DashboardModelProvider {
            id: prov_id.to_string(),
            label: prov_label,
            models,
        });
    }

    // Custom / local endpoint that's not in the catalog: synthesize
    // a single-entry provider so the dropdown still shows the
    // active model.
    if !provider.is_empty() && !emitted_providers.contains(&provider.to_lowercase()) {
        if !emitted_providers.insert(provider.to_lowercase()) {
            // already in the set
        }
        let models = if model.is_empty() {
            Vec::new()
        } else {
            vec![DashboardModelOption {
                id: model.clone(),
                label: model.clone(),
            }]
        };
        providers.push(DashboardModelProvider {
            id: provider.clone(),
            label: provider.clone(),
            models,
        });
    }

    // Prepend a synthetic `auto` entry so the front-end can pin
    // the "auto" choice at the top of the dropdown without
    // re-deriving it locally.
    let mut with_auto: Vec<DashboardModelProvider> = vec![DashboardModelProvider {
        id: "auto".to_string(),
        label: "Auto".to_string(),
        models: vec![DashboardModelOption {
            id: "auto".to_string(),
            label: "auto".to_string(),
        }],
    }];
    with_auto.extend(providers);

    Ok(DashboardModelOptionsResponse {
        providers: with_auto,
        model,
        provider,
    })
}

fn apply_default_model_to_yaml(
    yaml_text: &str,
    provider: &str,
    model: &str,
) -> Result<String, String> {
    let trimmed = yaml_text.trim();
    let mut root = if trimmed.is_empty() {
        serde_yaml::Value::Mapping(serde_yaml::Mapping::new())
    } else {
        serde_yaml::from_str::<serde_yaml::Value>(trimmed)
            .map_err(|e| format!("Failed to parse dashboard config yaml: {}", e))?
    };

    if !matches!(root, serde_yaml::Value::Mapping(_)) {
        root = serde_yaml::Value::Mapping(serde_yaml::Mapping::new());
    }

    let root_mapping = root
        .as_mapping_mut()
        .ok_or_else(|| "Dashboard config root is not a YAML mapping".to_string())?;
    root_mapping.remove(yaml_string_key("model_context_length"));
    let model_mapping = ensure_yaml_mapping(root_mapping, "model")?;

    set_yaml_string(model_mapping, "provider", provider);
    set_yaml_string(model_mapping, "default", model);
    model_mapping.remove(yaml_string_key("base_url"));
    model_mapping.remove(yaml_string_key("context_length"));

    serde_yaml::to_string(&root).map_err(|e| format!("Failed to serialize dashboard config yaml: {}", e))
}

fn save_default_model_to_config(provider: &str, model: &str) -> Result<(), String> {
    let config_path = get_hermes_config_path();
    if let Some(parent) = config_path.parent() {
        std::fs::create_dir_all(parent)
            .map_err(|e| format!("Failed to create {}: {}", parent.display(), e))?;
    }

    let yaml_text = std::fs::read_to_string(&config_path).unwrap_or_default();
    let next_yaml = apply_default_model_to_yaml(&yaml_text, provider, model)?;
    std::fs::write(&config_path, next_yaml)
        .map_err(|e| format!("Failed to write {}: {}", config_path.display(), e))
}

async fn get_dashboard_config_raw_yaml() -> Result<String, String> {
    hermes_dashboard_api_request(reqwest::Method::GET, "/api/config/raw", None)
        .await?
        .json::<DashboardConfigRawResponse>()
        .await
        .map(|response| response.yaml)
        .map_err(|e| format!("Failed to parse raw config response: {}", e))
}

async fn save_dashboard_config_raw_yaml(yaml_text: String) -> Result<CronActionResult, String> {
    hermes_dashboard_api_request(
        reqwest::Method::PUT,
        "/api/config/raw",
        Some(serde_json::json!({
            "yaml_text": yaml_text,
        })),
    )
    .await?
    .json::<CronActionResult>()
    .await
    .map_err(|e| format!("Failed to parse raw config save response: {}", e))
}

#[tauri::command]
pub async fn get_dashboard_primary_model_config() -> Result<DashboardPrimaryModelConfig, String> {
    let yaml_text = get_dashboard_config_raw_yaml().await?;
    extract_primary_model_config_from_yaml(&yaml_text)
}

#[tauri::command]
pub async fn save_dashboard_primary_model_config(
    config: DashboardPrimaryModelConfig,
) -> Result<CronActionResult, String> {
    let current_yaml = get_dashboard_config_raw_yaml().await?;
    let next_yaml = apply_primary_model_config_to_yaml(&current_yaml, &config)?;
    save_dashboard_config_raw_yaml(next_yaml).await
}

#[tauri::command]
pub async fn get_model_options() -> Result<DashboardModelOptionsResponse, String> {
    match hermes_dashboard_public_api_request(reqwest::Method::GET, "/api/model/options", None).await {
        Ok(response) => response
            .json::<DashboardModelOptionsResponse>()
            .await
            .map_err(|e| format!("Failed to parse model options response: {}", e)),
        Err(_) => tokio::task::spawn_blocking(fallback_model_options_from_config)
            .await
            .map_err(|e| format!("Dashboard model options fallback task failed: {}", e))?,
    }
}

#[tauri::command]
pub async fn set_default_model(provider: String, model: String) -> Result<CronActionResult, String> {
    let provider = provider.trim().to_string();
    let model = model.trim().to_string();

    if provider.is_empty() || model.is_empty() {
        return Err("Provider and model are required".to_string());
    }

    let payload = serde_json::json!({
        "scope": "main",
        "provider": provider,
        "model": model,
        "task": "",
    });

    if hermes_dashboard_public_api_request(reqwest::Method::POST, "/api/model/set", Some(payload))
        .await
        .is_ok()
    {
        return Ok(CronActionResult { ok: true });
    }

    // For the `auto` pseudo-provider we don't have a real (provider,
    // model) to persist on the offline fallback path; the embedded
    // server's `/api/model/set` will resolve it. Skip the blocking
    // write entirely so we don't end up pinning `auto/auto` to disk.
    if provider.eq_ignore_ascii_case("auto") {
        return Ok(CronActionResult { ok: true });
    }

    let provider_for_blocking = provider.clone();
    let model_for_blocking = model.clone();
    tokio::task::spawn_blocking(move || {
        save_default_model_to_config(&provider_for_blocking, &model_for_blocking)
    })
    .await
    .map_err(|e| format!("Default model config save task failed: {}", e))??;
    Ok(CronActionResult { ok: true })
}

#[tauri::command]
pub fn get_configured_model_candidates() -> Result<Vec<String>, String> {
    let config_yaml = std::fs::read_to_string(get_hermes_config_path()).unwrap_or_default();
    let env_content = std::fs::read_to_string(get_hermes_env_path()).unwrap_or_default();
    collect_configured_model_candidates(&config_yaml, &env_content)
}

#[tauri::command]
pub async fn get_dashboard_env_vars() -> Result<HashMap<String, DashboardEnvVarInfo>, String> {
    hermes_dashboard_api_request(reqwest::Method::GET, "/api/env", None)
        .await?
        .json::<HashMap<String, DashboardEnvVarInfo>>()
        .await
        .map_err(|e| format!("Failed to parse env response: {}", e))
}

#[tauri::command]
pub async fn set_dashboard_env_var(
    key: String,
    value: String,
) -> Result<CronActionResult, String> {
    hermes_dashboard_api_request(
        reqwest::Method::PUT,
        "/api/env",
        Some(serde_json::json!({
            "key": key,
            "value": value,
        })),
    )
    .await?
    .json::<CronActionResult>()
    .await
    .map_err(|e| format!("Failed to parse env set response: {}", e))
}

#[tauri::command]
pub async fn delete_dashboard_env_var(key: String) -> Result<CronActionResult, String> {
    hermes_dashboard_api_request(
        reqwest::Method::DELETE,
        "/api/env",
        Some(serde_json::json!({
            "key": key,
        })),
    )
    .await?
    .json::<CronActionResult>()
    .await
    .map_err(|e| format!("Failed to parse env delete response: {}", e))
}

#[tauri::command]
pub async fn reveal_dashboard_env_var(key: String) -> Result<DashboardEnvRevealResponse, String> {
    hermes_dashboard_api_request(
        reqwest::Method::POST,
        "/api/env/reveal",
        Some(serde_json::json!({
            "key": key,
        })),
    )
    .await?
    .json::<DashboardEnvRevealResponse>()
    .await
        .map_err(|e| format!("Failed to parse env reveal response: {}", e))
}

// ============================================================
// BitFun clone-layer compatibility stubs
//
// The BitFun frontend (infrastructure/api/service-api/*) calls
// these Tauri commands that don't exist in tupai's backend.
// Rather than failing on every call, these stubs return
// sensible defaults so the BitFun initialization flow
// completes without errors.
// ============================================================

// --- i18n commands ---

#[tauri::command]
pub fn i18n_set_language(
    app: tauri::AppHandle,
    request: Option<serde_json::Value>,
) -> Result<String, String> {
    // BitFun frontend sends {request: {language: "zh-CN"}}.
    // Persist it via AppConfig.language so it survives restarts.
    let language = if let Some(req) = &request {
        req.get("language")
            .and_then(|v| v.as_str())
            .unwrap_or("zh-CN")
            .to_string()
    } else {
        "zh-CN".to_string()
    };
    let mut cfg = load_config_from_disk(&app);
    cfg.language = language.clone();
    let cfg = sanitize_app_config(cfg);
    save_config_to_disk(&app, &cfg)?;
    Ok(language)
}

#[tauri::command]
pub fn i18n_get_current_language(app: tauri::AppHandle) -> Result<String, String> {
    Ok(load_config_from_disk(&app).language)
}

#[tauri::command]
pub fn i18n_get_supported_languages() -> Result<Vec<serde_json::Value>, String> {
    Ok(vec![
        serde_json::json!({
            "id": "zh-CN",
            "name": "简体中文",
            "englishName": "Chinese (Simplified)",
            "nativeName": "简体中文",
            "rtl": false
        }),
        serde_json::json!({
            "id": "en-US",
            "name": "English",
            "englishName": "English",
            "nativeName": "English",
            "rtl": false
        }),
    ])
}

#[tauri::command]
pub fn i18n_get_config(app: tauri::AppHandle) -> Result<serde_json::Value, String> {
    let cfg = load_config_from_disk(&app);
    Ok(serde_json::json!({
        "currentLanguage": cfg.language,
        "fallbackLanguage": "en-US",
        "autoDetect": false
    }))
}

#[tauri::command]
pub fn i18n_set_config(
    app: tauri::AppHandle,
    config: Option<serde_json::Value>,
) -> Result<String, String> {
    // Extract language from config and persist it.
    if let Some(cfg) = &config {
        if let Some(lang) = cfg.get("currentLanguage").and_then(|v| v.as_str()) {
            let mut app_cfg = load_config_from_disk(&app);
            app_cfg.language = lang.to_string();
            let app_cfg = sanitize_app_config(app_cfg);
            save_config_to_disk(&app, &app_cfg)?;
            return Ok(lang.to_string());
        }
    }
    Ok(load_config_from_disk(&app).language)
}

// --- Persisted sessions stubs (BitFun FlowChatStore) ---

#[tauri::command]
pub async fn list_persisted_sessions(
    _app: tauri::AppHandle,
    request: Option<serde_json::Value>,
) -> Result<Vec<serde_json::Value>, String> {
    log::debug!(
        "[list_persisted_sessions] returning empty list (BitFun stub); request={:?}",
        request
    );
    Ok(Vec::new())
}

#[tauri::command]
pub async fn list_persisted_sessions_page(
    _app: tauri::AppHandle,
    request: Option<serde_json::Value>,
) -> Result<serde_json::Value, String> {
    log::debug!(
        "[list_persisted_sessions_page] returning empty page (BitFun stub); request={:?}",
        request
    );
    // 前端 SessionMetadataPage 期望字段：sessions / totalTopLevelCount /
    // loadedTopLevelCount / nextCursor / hasMore。必须严格匹配，否则
    // FlowChatStore.processPersistedSessionMetadataList 访问 page.sessions.map
    // 时会抛 "Cannot read properties of undefined (reading 'map')"。
    Ok(serde_json::json!({
        "sessions": [],
        "totalTopLevelCount": 0,
        "loadedTopLevelCount": 0,
        "nextCursor": null,
        "hasMore": false
    }))
}

// --- Terminal shells stub ---

#[tauri::command]
pub fn terminal_get_shells() -> Result<Vec<serde_json::Value>, String> {
    // Return default Windows shells so the BitFun settings page doesn't error.
    Ok(vec![
        serde_json::json!({"id": "powershell", "name": "PowerShell", "default": true}),
        serde_json::json!({"id": "cmd", "name": "Command Prompt", "default": false}),
    ])
}

// --- Runtime logging info stub ---

#[tauri::command]
pub fn get_runtime_logging_info() -> Result<serde_json::Value, String> {
    Ok(serde_json::json!({
        "effectiveLevel": "info",
        "sessionLogDir": "",
        "previousUnexpectedExit": {
            "detected": false,
            "sessionLogDir": ""
        }
    }))
}

// --- Git stub ---

#[tauri::command]
pub fn git_is_repository(_path: Option<String>) -> Result<bool, String> {
    // tupai doesn't implement Git integration; return false so the
    // BitFun GitStateManager doesn't attempt further Git operations.
    Ok(false)
}

// --- Batch config fetch stub ---

#[tauri::command]
pub fn get_configs(
    app: tauri::AppHandle,
    request: Option<serde_json::Value>,
) -> Result<serde_json::Value, String> {
    // BitFun frontend sends {request: {paths: ["app.logging.level", ...]}}.
    // Return the full AppConfig so the frontend can extract what it needs;
    // the BitFun layer already falls back to individual get_config calls
    // if this returns an empty object.
    let cfg = load_config_from_disk(&app);
    let mut result = serde_json::Map::new();
    if let Some(req) = &request {
        if let Some(paths) = req.get("paths").and_then(|v| v.as_array()) {
            for path_val in paths {
                if let Some(path) = path_val.as_str() {
                    let value = match path {
                        "app.logging.level" => serde_json::json!("info"),
                        "app.logging.include_sensitive_diagnostics" => serde_json::json!(false),
                        "theme" | "themes.current" => serde_json::json!(cfg.theme),
                        "language" | "app.language" => serde_json::json!(cfg.language),
                        "ai.computer_use_enabled" | "computer_use_enabled" => {
                            serde_json::json!(cfg.computer_use_enabled)
                        }
                        _ => serde_json::Value::Null,
                    };
                    result.insert(path.to_string(), value);
                }
            }
        }
    }
    Ok(serde_json::Value::Object(result))
}

// --- LSP extensions stub ---

#[tauri::command]
pub fn lsp_get_supported_extensions() -> Result<serde_json::Value, String> {
    // tupai doesn't implement LSP; return empty structure matching BitFun's
    // SupportedExtensionsResponse {extensionToLanguage, supportedLanguages}.
    Ok(serde_json::json!({
        "extensionToLanguage": {},
        "supportedLanguages": []
    }))
}

#[cfg(test)]
mod dashboard_model_config_tests {
    use super::*;

    #[test]
    fn extract_primary_model_config_from_yaml_reads_nested_model_section() {
        let yaml = r#"
model:
  default: qwen2.5:14b
  provider: custom
  base_url: http://127.0.0.1:11434/v1
  api_key: ollama
model_context_length: 32768
display:
  personality: helpful
"#;

        let config = extract_primary_model_config_from_yaml(yaml).expect("expected model config");

        assert_eq!(config.model, "qwen2.5:14b");
        assert_eq!(config.provider, "custom");
        assert_eq!(config.base_url, "http://127.0.0.1:11434/v1");
        assert_eq!(config.api_key, "ollama");
        assert_eq!(config.context_length, Some(32768));
    }

    #[test]
    fn apply_primary_model_config_to_yaml_updates_target_fields_only() {
        let yaml = r#"
model:
  default: claude-3-7-sonnet
  provider: anthropic
  base_url: https://api.anthropic.com
  api_key: hidden-key
model_context_length: 200000
display:
  personality: helpful
"#;
        let next_config = DashboardPrimaryModelConfig {
            model: "qwen2.5:14b".to_string(),
            provider: "custom".to_string(),
            base_url: "http://127.0.0.1:11434/v1".to_string(),
            api_key: "ollama".to_string(),
            context_length: Some(32768),
        };

        let updated_yaml =
            apply_primary_model_config_to_yaml(yaml, &next_config).expect("expected updated yaml");
        let updated_config =
            extract_primary_model_config_from_yaml(&updated_yaml).expect("expected parsed config");

        assert_eq!(updated_config.model, "qwen2.5:14b");
        assert_eq!(updated_config.provider, "custom");
        assert_eq!(updated_config.base_url, "http://127.0.0.1:11434/v1");
        assert_eq!(updated_config.api_key, "ollama");
        assert_eq!(updated_config.context_length, Some(32768));
        assert!(updated_yaml.contains("display:"));
        assert!(updated_yaml.contains("personality: helpful"));
    }
}

// ========================
// 文件操作 API（Phase 3）
// ========================

#[derive(Serialize, Deserialize, Clone)]
pub struct FileEntry {
    pub name: String,
    pub path: String,
    pub is_dir: bool,
    pub r#type: String, // "file" | "directory"
    pub size: u64,
    pub modified: String,
}

#[derive(Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct FilePreview {
    pub kind: String,
    pub name: String,
    pub path: String,
    pub mime: Option<String>,
    pub extension: Option<String>,
    pub size: u64,
    pub modified: String,
    pub content: Option<String>,
    pub data_url: Option<String>,
}

fn detect_text_mime(extension: &str) -> Option<&'static str> {
    match extension {
        "txt" | "log" | "md" | "markdown" | "json" | "jsonl" | "yaml" | "yml" | "toml" | "ini"
        | "conf" | "cfg" | "xml" | "html" | "htm" | "css" | "scss" | "less" | "js" | "jsx"
        | "ts" | "tsx" | "mjs" | "cjs" | "rs" | "py" | "java" | "kt" | "swift" | "go" | "rb"
        | "php" | "sh" | "zsh" | "bash" | "fish" | "sql" | "csv" | "tsv" => Some("text/plain"),
        _ => None,
    }
}

fn detect_image_mime(extension: &str) -> Option<&'static str> {
    match extension {
        "png" => Some("image/png"),
        "jpg" | "jpeg" => Some("image/jpeg"),
        "webp" => Some("image/webp"),
        "gif" => Some("image/gif"),
        "bmp" => Some("image/bmp"),
        "svg" => Some("image/svg+xml"),
        _ => None,
    }
}

fn classify_file_preview_kind(name: &str, bytes: &[u8]) -> &'static str {
    let extension = Path::new(name)
        .extension()
        .and_then(|ext| ext.to_str())
        .map(|ext| ext.to_lowercase())
        .unwrap_or_default();

    if detect_image_mime(&extension).is_some() {
        return "image";
    }

    if extension == "pdf" {
        return "pdf";
    }

    if matches!(
        extension.as_str(),
        "doc" | "docx" | "xls" | "xlsx" | "ppt" | "pptx" | "pages" | "numbers" | "key"
    ) {
        return "office";
    }

    if detect_text_mime(&extension).is_some() {
        return "text";
    }

    if !bytes.is_empty() && bytes.iter().all(|byte| *byte != 0) && std::str::from_utf8(bytes).is_ok() {
        return "text";
    }

    "binary"
}

fn build_file_preview(relative: &Path, target: &Path, metadata: &std::fs::Metadata) -> Result<FilePreview, String> {
    let name = target
        .file_name()
        .and_then(|value| value.to_str())
        .unwrap_or_default()
        .to_string();
    let extension = Path::new(&name)
        .extension()
        .and_then(|value| value.to_str())
        .map(|value| value.to_lowercase());
    let modified = metadata
        .modified()
        .ok()
        .map(|time| chrono::DateTime::<chrono::Utc>::from(time).to_rfc3339())
        .unwrap_or_else(now_rfc3339);
    let bytes = std::fs::read(target)
        .map_err(|e| format!("Failed to read file {}: {}", target.display(), e))?;
    let kind = classify_file_preview_kind(&name, &bytes).to_string();
    let mime = extension
        .as_deref()
        .and_then(|ext| detect_image_mime(ext).or_else(|| detect_text_mime(ext)))
        .map(str::to_string)
        .or_else(|| {
            if kind == "pdf" {
                Some("application/pdf".to_string())
            } else if kind == "office" || kind == "binary" {
                Some("application/octet-stream".to_string())
            } else {
                None
            }
        });

    let (content, data_url) = match kind.as_str() {
        "text" => (
            Some(String::from_utf8_lossy(&bytes).to_string()),
            None,
        ),
        "image" => {
            let encoded = {
                use base64::Engine;
                base64::engine::general_purpose::STANDARD.encode(&bytes)
            };
            let mime_type = mime
                .clone()
                .unwrap_or_else(|| "application/octet-stream".to_string());
            (
                None,
                Some(format!("data:{};base64,{}", mime_type, encoded)),
            )
        }
        _ => (None, None),
    };

    Ok(FilePreview {
        kind,
        name,
        path: relative.to_string_lossy().replace('\\', "/"),
        mime,
        extension,
        size: metadata.len(),
        modified,
        content,
        data_url,
    })
}

fn resolve_workspace_root(workspace_path: Option<String>) -> Result<PathBuf, String> {
    let normalized = normalize_workspace_path(workspace_path.as_deref())
        .ok_or_else(|| "Workspace path is required".to_string())?;
    let root = PathBuf::from(normalized);
    std::fs::create_dir_all(&root)
        .map_err(|e| format!("Failed to create workspace root {}: {}", root.display(), e))?;
    root.canonicalize()
        .map_err(|e| format!("Failed to resolve workspace root {}: {}", root.display(), e))
}

fn resolve_workspace_relative_path(
    workspace_path: Option<String>,
    relative_path: &str,
    allow_missing: bool,
) -> Result<(PathBuf, PathBuf, PathBuf), String> {
    use std::path::Component;

    let root = resolve_workspace_root(workspace_path)?;
    let trimmed = relative_path.trim();
    let relative = if trimmed.is_empty() {
        PathBuf::new()
    } else {
        let candidate = PathBuf::from(trimmed);
        if candidate.is_absolute() {
            candidate
                .strip_prefix(&root)
                .map_err(|_| "Path is outside the current workspace".to_string())?
                .to_path_buf()
        } else {
            candidate
        }
    };

    if relative.components().any(|component| {
        matches!(
            component,
            Component::ParentDir | Component::RootDir | Component::Prefix(_)
        )
    }) {
        return Err("Path is outside the current workspace".to_string());
    }

    let target = root.join(&relative);
    if !allow_missing && !target.exists() {
        return Err(format!("Path does not exist: {}", target.display()));
    }

    Ok((root, target, relative))
}

#[tauri::command]
pub fn list_directory(
    path: String,
    workspace_path: Option<String>,
) -> Result<Vec<FileEntry>, String> {
    let (_root, target, relative) = resolve_workspace_relative_path(workspace_path, &path, true)?;
    if !target.exists() {
        std::fs::create_dir_all(&target)
            .map_err(|e| format!("Failed to create directory {}: {}", target.display(), e))?;
    }
    if !target.is_dir() {
        return Err(format!("Not a directory: {}", target.display()));
    }

    let mut entries = std::fs::read_dir(&target)
        .map_err(|e| format!("Failed to read directory {}: {}", target.display(), e))?
        .filter_map(Result::ok)
        .map(|entry| {
            let metadata = entry.metadata().ok();
            let is_dir = metadata.as_ref().map(|item| item.is_dir()).unwrap_or(false);
            let entry_name = entry.file_name().to_string_lossy().to_string();
            let entry_relative = if relative.as_os_str().is_empty() {
                PathBuf::from(&entry_name)
            } else {
                relative.join(&entry_name)
            };
            let modified = metadata
                .as_ref()
                .and_then(|item| item.modified().ok())
                .map(|time| chrono::DateTime::<chrono::Utc>::from(time).to_rfc3339())
                .unwrap_or_else(now_rfc3339);

            FileEntry {
                name: entry_name,
                path: entry_relative.to_string_lossy().replace('\\', "/"),
                is_dir,
                r#type: if is_dir { "directory" } else { "file" }.to_string(),
                size: metadata.as_ref().map(|item| item.len()).unwrap_or(0),
                modified,
            }
        })
        .collect::<Vec<_>>();

    entries.sort_by(|left, right| match (left.is_dir, right.is_dir) {
        (true, false) => std::cmp::Ordering::Less,
        (false, true) => std::cmp::Ordering::Greater,
        _ => left.name.to_lowercase().cmp(&right.name.to_lowercase()),
    });

    Ok(entries)
}

#[tauri::command]
pub fn read_file(path: String, workspace_path: Option<String>) -> Result<String, String> {
    let (_root, target, _relative) = resolve_workspace_relative_path(workspace_path, &path, false)?;
    let bytes = std::fs::read(&target)
        .map_err(|e| format!("Failed to read file {}: {}", target.display(), e))?;
    Ok(String::from_utf8_lossy(&bytes).to_string())
}

#[tauri::command]
pub fn get_file_preview(path: String, workspace_path: Option<String>) -> Result<FilePreview, String> {
    let (_root, target, relative) =
        resolve_workspace_relative_path(workspace_path, &path, false)?;

    if target.is_dir() {
        return Err(format!("Not a file: {}", target.display()));
    }

    let metadata = target
        .metadata()
        .map_err(|e| format!("Failed to read metadata {}: {}", target.display(), e))?;

    build_file_preview(&relative, &target, &metadata)
}

#[tauri::command]
pub fn open_file_external(path: String, workspace_path: Option<String>) -> Result<serde_json::Value, String> {
    let (_root, target, relative) =
        resolve_workspace_relative_path(workspace_path, &path, false)?;

    #[cfg(target_os = "macos")]
    let result = Command::new("open").arg(&target).status();

    #[cfg(target_os = "linux")]
    let result = Command::new("xdg-open").arg(&target).status();

    #[cfg(target_os = "windows")]
    let result = {
        let mut cmd = std::process::Command::new("cmd");
        apply_no_window(&mut cmd);
        cmd.args(["/C", "start", "", &target.display().to_string()]).status()
    };

    #[cfg(not(any(target_os = "macos", target_os = "linux", target_os = "windows")))]
    let result: Result<std::process::ExitStatus, std::io::Error> =
        Err(std::io::Error::new(std::io::ErrorKind::Unsupported, "Unsupported platform"));

    let status = result
        .map_err(|e| format!("Failed to open {} externally: {}", target.display(), e))?;

    if !status.success() {
        return Err(format!("External open command failed for {}", target.display()));
    }

    Ok(serde_json::json!({
        "success": true,
        "path": relative.to_string_lossy().replace('\\', "/")
    }))
}

#[tauri::command]
pub fn write_file(
    path: String,
    content: String,
    workspace_path: Option<String>,
) -> Result<serde_json::Value, String> {
    let (_root, target, relative) = resolve_workspace_relative_path(workspace_path, &path, true)?;

    let filename = target.file_name().and_then(|n| n.to_str()).unwrap_or("");
    let protected = ["config.json", "sessions.db", "memories.db", ".hermes.env"];
    if protected.contains(&filename) {
        return Err(format!("Cannot overwrite protected file: {}", filename));
    }

    if let Some(parent) = target.parent() {
        std::fs::create_dir_all(parent)
            .map_err(|e| format!("Failed to create parent directory {}: {}", parent.display(), e))?;
    }
    std::fs::write(&target, content)
        .map_err(|e| format!("Failed to write file {}: {}", target.display(), e))?;
    Ok(serde_json::json!({ "success": true, "path": relative.to_string_lossy().replace('\\', "/") }))
}

#[tauri::command]
pub fn delete_file(
    path: String,
    workspace_path: Option<String>,
) -> Result<serde_json::Value, String> {
    let (_root, target, relative) = resolve_workspace_relative_path(workspace_path, &path, false)?;
    if target.is_dir() {
        std::fs::remove_dir_all(&target)
            .map_err(|e| format!("Failed to delete directory {}: {}", target.display(), e))?;
    } else {
        std::fs::remove_file(&target)
            .map_err(|e| format!("Failed to delete file {}: {}", target.display(), e))?;
    }
    Ok(serde_json::json!({ "success": true, "path": relative.to_string_lossy().replace('\\', "/") }))
}

#[tauri::command]
pub fn create_directory(
    path: String,
    workspace_path: Option<String>,
) -> Result<serde_json::Value, String> {
    let (_root, target, relative) = resolve_workspace_relative_path(workspace_path, &path, true)?;
    std::fs::create_dir_all(&target)
        .map_err(|e| format!("Failed to create directory {}: {}", target.display(), e))?;
    Ok(serde_json::json!({ "success": true, "path": relative.to_string_lossy().replace('\\', "/") }))
}

#[cfg(test)]
mod tests {
    #[cfg(unix)]
    use super::resolve_unix_shell_with;
    use super::{
        build_dashboard_launch_command, extract_dashboard_error_detail,
        classify_file_preview_kind, collect_configured_model_candidates,
        collect_configured_model_candidates_from_env_content, ensure_sessions_schema,
        resolve_chat_request_model, update_session_model_in_connection,
    };
    use rusqlite::{params, Connection};
    #[cfg(unix)]
    use std::path::Path;

    // `resolve_unix_shell_with` is a Unix-only helper (uses
    // `Path::is_absolute()` which on Windows treats `"/custom/bin/zsh"`
    // as relative, falling through to `/bin/sh`); the tests must
    // therefore only run on Unix hosts.
    #[cfg(unix)]
    #[test]
    fn resolve_unix_shell_with_prefers_env_shell_when_available() {
        let shell = resolve_unix_shell_with(Some("/custom/bin/zsh"), |path| {
            path == Path::new("/custom/bin/zsh")
        });

        assert_eq!(shell, "/custom/bin/zsh");
    }

    #[cfg(unix)]
    #[test]
    fn resolve_unix_shell_with_falls_back_to_bash_then_sh() {
        let shell = resolve_unix_shell_with(None, |path| path == Path::new("/bin/bash"));
        assert_eq!(shell, "/bin/bash");

        let shell = resolve_unix_shell_with(Some(""), |path| path == Path::new("/bin/sh"));
        assert_eq!(shell, "/bin/sh");
    }

    #[test]
    fn extract_dashboard_error_detail_reads_detail_field_from_json() {
        let detail = extract_dashboard_error_detail(
            r#"{"detail":"Cron expressions require 'croniter' package. Install with: pip install croniter"}"#,
        );

        assert_eq!(
            detail.as_deref(),
            Some("Cron expressions require 'croniter' package. Install with: pip install croniter")
        );
    }

    #[test]
    fn build_dashboard_launch_command_uses_expected_flags() {
        let command = build_dashboard_launch_command(
            "/usr/local/bin/hermes",
            "/usr/local/lib/hermes-cli.cjs",
        );

        assert!(command.contains("dashboard"));
        assert!(command.contains("--port 9119"));
        assert!(command.contains("--no-open"));
        assert!(command.contains("/usr/local/bin/hermes"));
    }

    #[test]
    fn classify_file_preview_kind_detects_image_office_pdf_and_text() {
        assert_eq!(classify_file_preview_kind("notes.md", b"# hi"), "text");
        assert_eq!(classify_file_preview_kind("photo.png", b"\x89PNG"), "image");
        assert_eq!(classify_file_preview_kind("report.pdf", b"%PDF-1.7"), "pdf");
        assert_eq!(classify_file_preview_kind("sheet.xlsx", b"PK\x03\x04"), "office");
    }

    #[test]
    fn classify_file_preview_kind_treats_unknown_binary_as_binary() {
        assert_eq!(classify_file_preview_kind("archive.bin", b"\x00\x01\x02"), "binary");
    }

    #[test]
    fn session_model_schema_migration_adds_model_column() {
        let conn = Connection::open_in_memory().expect("in-memory db");

        ensure_sessions_schema(&conn).expect("schema initialized");

        let mut stmt = conn
            .prepare("PRAGMA table_info(sessions)")
            .expect("pragma prepared");
        let columns = stmt
            .query_map([], |row| row.get::<_, String>(1))
            .expect("pragma query")
            .collect::<Result<Vec<_>, _>>()
            .expect("column names");

        assert!(columns.iter().any(|column| column == "model"));
    }

    #[test]
    fn session_model_update_only_changes_target_row() {
        let conn = Connection::open_in_memory().expect("in-memory db");
        ensure_sessions_schema(&conn).expect("schema initialized");

        conn.execute(
            "INSERT INTO sessions (id, title, agent_id, workspace_path, pinned, created_at, updated_at, message_count, model) VALUES (?1, ?2, ?3, ?4, 0, ?5, ?5, 0, ?6)",
            params!["session-a", "A", "hermes-agent", Option::<String>::None, "2026-04-23T00:00:00Z", Option::<String>::None],
        ).expect("insert session a");
        conn.execute(
            "INSERT INTO sessions (id, title, agent_id, workspace_path, pinned, created_at, updated_at, message_count, model) VALUES (?1, ?2, ?3, ?4, 0, ?5, ?5, 0, ?6)",
            params!["session-b", "B", "hermes-agent", Option::<String>::None, "2026-04-23T00:00:00Z", Some("gpt-4.1")],
        ).expect("insert session b");

        update_session_model_in_connection(
            &conn,
            "session-a",
            Some("qwen2.5:14b".to_string()),
            "2026-04-23T12:00:00Z",
        )
        .expect("update model");

        let session_a_model: Option<String> = conn
            .query_row(
                "SELECT model FROM sessions WHERE id = ?1",
                params!["session-a"],
                |row| row.get(0),
            )
            .expect("select model a");
        let session_b_model: Option<String> = conn
            .query_row(
                "SELECT model FROM sessions WHERE id = ?1",
                params!["session-b"],
                |row| row.get(0),
            )
            .expect("select model b");

        assert_eq!(session_a_model.as_deref(), Some("qwen2.5:14b"));
        assert_eq!(session_b_model.as_deref(), Some("gpt-4.1"));
    }

    #[test]
    fn response_request_model_prefers_explicit_model_and_falls_back_to_agent() {
        assert_eq!(
            resolve_chat_request_model(Some("MiniMax-M2.7".to_string())),
            "MiniMax-M2.7"
        );
        assert_eq!(resolve_chat_request_model(Some("   ".to_string())), "hermes-agent");
        assert_eq!(resolve_chat_request_model(None), "hermes-agent");
    }

    #[test]
    fn collect_configured_model_candidates_from_env_content_reads_default_model_keys() {
        let models = collect_configured_model_candidates_from_env_content(
            r#"
OPENAI_DEFAULT_MODEL=gpt-4.1
# ANTHROPIC_DEFAULT_MODEL=claude-3-5-sonnet
DEEPSEEK_DEFAULT_MODEL=deepseek-chat
OPENAI_DEFAULT_MODEL=gpt-4.1
"#,
        );

        assert_eq!(models, vec!["gpt-4.1".to_string(), "deepseek-chat".to_string()]);
    }

    #[test]
    fn collect_configured_model_candidates_merges_config_and_env_models_without_duplicates() {
        let models = collect_configured_model_candidates(
            r#"
model:
  default: Localkey
"#,
            r#"
OPENAI_DEFAULT_MODEL=gpt-4.1
DEEPSEEK_DEFAULT_MODEL=deepseek-chat
OPENAI_DEFAULT_MODEL=gpt-4.1
"#,
        )
        .expect("model candidates");

        assert_eq!(
            models,
            vec![
                "Localkey".to_string(),
                "gpt-4.1".to_string(),
                "deepseek-chat".to_string()
            ]
        );
    }
}

#[cfg(test)]
mod db_integration_tests {
    use tauri::AppHandle;

    // 创建一个模拟的 AppHandle（用于测试）
    fn mock_app_handle() -> AppHandle {
        // 注意：完整测试需要 Tauri app 实例，此处仅验证编译
        // 实际集成测试应在 `cargo tauri dev` 中手动验证
        unimplemented!("Integration tests require running Tauri app")
    }

    #[test]
    fn test_schema_initialization() {
        // 验证 schema SQL 能正确执行（编译时检查）
        let schema = include_str!("../../schema/app.sql");
        assert!(schema.contains("CREATE TABLE IF NOT EXISTS memories"));
        assert!(schema.contains("CREATE TABLE IF NOT EXISTS tasks"));
        assert!(schema.contains("CREATE INDEX"));
    }
}
