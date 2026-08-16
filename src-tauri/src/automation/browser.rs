// Copyright (c) 2026 tupAI
//
// tupAI P1 §3.4 — CDP-backed browser session management.
//
// We wrap `chromiumoxide::Browser` into a `BrowserSession` so the rest
// of the codebase can reason about "the browser I am currently driving"
// without leaking chromiumoxide types into the public API.
//
// Session storage is intentionally in-process (`Arc<Mutex<HashMap>>`) and
// the Tauri command layer puts the map into the global app state via
// `app.manage()`. This keeps the design simple while still allowing
// multiple concurrent automation runs if we ever need them.

use std::collections::HashMap;
use std::sync::Arc;

use chromiumoxide::browser::{Browser, BrowserConfig};
use futures::StreamExt;
use serde::{Deserialize, Serialize};
use tauri::async_runtime::Mutex;

use super::system_software::{check_software_installed, SoftwareInfo};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BrowserInfo {
    pub browser_type: String,
    pub installed: bool,
    pub version: Option<String>,
    pub executable_path: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BrowserSessionStatus {
    pub session_id: String,
    pub browser_type: String,
    pub current_url: Option<String>,
    pub is_alive: bool,
    pub last_action: Option<String>,
}

/// A live, CDP-attached browser. The inner `Browser` is the only field
/// callers actually need; the `current_page` is kept around so the
/// `browser_steps` module has a stable handle to operate on.
pub struct BrowserSession {
    pub browser: Browser,
    pub browser_type: String,
    pub current_page: Option<chromiumoxide::Page>,
    pub last_action: Option<String>,
    /// per-session 隔离 profile 目录路径，close_session 时据此清理。
    pub user_data_dir: Option<std::path::PathBuf>,
}

impl BrowserSession {
    fn new(browser: Browser, browser_type: String) -> Self {
        Self {
            browser,
            browser_type,
            current_page: None,
            last_action: None,
            user_data_dir: None,
        }
    }

    pub fn is_alive(&self) -> bool {
        // chromiumoxide does not expose a direct liveness probe; the
        // cheapest signal is "do we still hold a page object". For
        // our purposes (UI badge, dispatcher health check) this is
        // good enough. The actual `evaluate` call will fail loudly
        // when the browser is gone.
        self.current_page.is_some()
    }
}

/// Shared session map; managed by Tauri as global state.
pub type SessionMap = Arc<Mutex<HashMap<String, BrowserSession>>>;

/// Construct an empty session map. Convenience used by the Tauri
/// `setup` hook to register the shared state via `app.manage()`.
pub fn new_session_map() -> SessionMap {
    Arc::new(Mutex::new(HashMap::new()))
}

/// Curated list of browsers the system knows how to detect + launch.
const BROWSER_WHITELIST: &[&str] = &[
    "chrome",
    "google chrome",
    "microsoft edge",
    "edge",
    "chromium",
    "brave",
    "firefox",
];

/// Probe the host for installed Chromium-family browsers.
///
/// Detection reuses the cross-platform software detection logic and then
/// narrows the result to entries that look like browsers.
pub fn detect_installed_browsers() -> Vec<BrowserInfo> {
    BROWSER_WHITELIST
        .iter()
        .map(|name| BrowserInfo {
            browser_type: (*name).to_string(),
            installed: check_software_installed(name),
            version: None,
            executable_path: None,
        })
        .collect()
}

/// Browser type string -> (executable hint, optional display name).
///
/// We try a couple of common names because the registry/which probe
/// can return a friendly display name that is not a valid executable.
fn executable_for(browser_type: &str) -> Option<&'static str> {
    match browser_type.to_lowercase().as_str() {
        "chrome" | "google chrome" | "google-chrome" | "chromium" => Some("chrome"),
        "edge" | "microsoft edge" | "msedge" => Some("msedge"),
        "brave" | "brave browser" => Some("brave"),
        "firefox" => Some("firefox"),
        _ => None,
    }
}

/// Spawn a new Chromium browser and attach to it via CDP.
///
/// `chromiumoxide` spins up Chrome with `--remote-debugging-port=0` (OS
/// picks a free port) and returns a `Browser` we can drive. The first page
/// is opened eagerly so the caller can immediately call
/// `execute_browser_action` without having to materialize a tab first.
///
/// 健壮化（v1.9.6）：
///   - `with_head()`：chromiumoxide 默认 `HeadlessMode::True`，用户看不到
///     浏览器窗口会误以为"没启动"。这里强制有头模式，便于用户观察/干预。
///   - `user_data_dir`：per-session 隔离 profile，避免与用户已开浏览器或
///     并发会话共用 chromiumoxide-runner 默认目录导致 "profile in use"。
///   - `--no-default-browser-check`：抑制首启弹窗（其余首启参数已在
///     chromiumoxide DEFAULT_ARGS）。
///   - eager CDP 验证：launch 后立即 `new_page("about:blank")` + `evaluate("1")`
///     round-trip，任一失败即 kill 浏览器并返回描述性错误，避免把
///     "启动成功但 CDP 通道坏了" 的半残会话塞进 map。
pub async fn start_session(browser_type: &str, port: Option<u16>) -> Result<BrowserSession, String> {
    let exe = executable_for(browser_type)
        .ok_or_else(|| format!("未知的浏览器类型: {}", browser_type))?;

    // v1.9.6 重打：重试 3 次，覆盖 AV 扫描 / 端口冲突 / profile 锁瞬时失败。
    // 旧版单次失败即整体失败，Chrome 冷启慢或 AV 扫描时直接报错。
    let mut last_err = String::new();
    for attempt in 1..=3u32 {
        // per-session 隔离 profile 目录（close_session / 失败时清理）。
        let user_data_dir = std::env::temp_dir().join(format!("tupai-cdp-{}", uuid::Uuid::new_v4()));

        let mut builder = BrowserConfig::builder();
        // 关键：默认 headless 会让用户看不到浏览器窗口，强制有头。
        builder = builder.with_head();
        if let Some(path) = locate_browser_path(exe) {
            builder = builder.chrome_executable(path);
        }
        if let Some(p) = port {
            builder = builder.port(p);
        }
        // v1.9.6 重打：补 Windows 稳定性 args。chromiumoxide DEFAULT_ARGS 已含
        // --no-first-run 等，这里补几个关键的稳定性参数：
        // --no-default-browser-check 抑制首启弹窗；
        // --disable-features=Translate,MediaRouter 关翻译/媒体路由（减少子进程）；
        // --disable-background-networking 减少后台网络请求；
        // --disable-extensions 避免扩展干扰自动化；
        // --disable-gpu（仅 Windows）旧 GPU 驱动下 GPU 进程会崩。
        builder = builder
            .user_data_dir(&user_data_dir)
            .arg("--no-default-browser-check")
            .arg("--disable-features=Translate,MediaRouter")
            .arg("--disable-background-networking")
            .arg("--disable-extensions");
        #[cfg(target_os = "windows")]
        {
            builder = builder.arg("--disable-gpu");
        }
        let config = builder
            .build()
            .map_err(|e| format!("构造 BrowserConfig 失败: {}", e))?;

        match Browser::launch(config).await {
            Ok((browser, mut handler)) => {
                // chromiumoxide requires the handler task to be polled continuously;
                // without this the first `Page` method call will hang forever.
                // v1.9.6 重打：不在 error event 时 break——CDP 连接会产生瞬态 error
                // event（如 service worker target detach），这些是非致命的。break
                // 会导致 handler 停止轮询，后续所有 CDP 命令（page.evaluate 等）永久
                // 挂起。handler.next().await 是事件驱动的异步等待，不会 busy-loop；
                // 连接真正断开时 next() 返回 None，循环自然退出。
                let _handler_task = tauri::async_runtime::spawn(async move {
                    while let Some(_event) = handler.next().await {
                        // 持续轮询直到流自然结束；忽略瞬态 error event。
                    }
                });

                let mut session = BrowserSession::new(browser, browser_type.to_string());
                session.user_data_dir = Some(user_data_dir);

                // v1.9.6 重打：放宽 eager 验证为 best-effort——new_page/evaluate
                // 失败不 kill 浏览器，改为警告日志 + 让首次真实 action 重试。
                // 旧版 eager 验证过严：Chrome 冷启慢时 new_page+evaluate round-trip
                // 失败 → kill 浏览器 → 返回"CDP 通道验证失败" → 技能拿到空 targets
                // → status:failed, rounds:0（用户看到的 bug）。
                match session.browser.new_page("about:blank").await {
                    Ok(page) => {
                        match page.evaluate("1").await {
                            Ok(_) => {
                                session.current_page = Some(page);
                            }
                            Err(e) => {
                                log::warn!("[cdp] launch 后 evaluate 失败(不致命，首个 action 会重试): {}", e);
                                session.current_page = Some(page);
                            }
                        }
                    }
                    Err(e) => {
                        log::warn!("[cdp] launch 后 new_page 失败(不致命，首个 action 会重试): {}", e);
                        // 不 kill，保留 session 让首个 execute_browser_action_cmd 重试 new_page
                    }
                }
                log::info!("[cdp] start_session 成功 attempt={} type={}", attempt, browser_type);
                return Ok(session);
            }
            Err(e) => {
                let path_found = locate_browser_path(exe).is_some();
                last_err = format!(
                    "启动浏览器失败(尝试 {}/3): {}\nexe={}, 路径检测={}",
                    attempt,
                    e,
                    exe,
                    if path_found { "已找到" } else { "未找到（请检查安装路径或注册表 App Paths）" }
                );
                log::warn!("[cdp] {}", last_err);
                // 清理本次失败的 user_data_dir 避免磁盘泄漏
                let _ = std::fs::remove_dir_all(&user_data_dir);
                if attempt < 3 {
                    tokio::time::sleep(std::time::Duration::from_millis(500 * attempt as u64)).await;
                }
            }
        }
    }
    Err(format!(
        "{}\n请确保已安装 Chrome/Edge/Brave，且未被其他程序占用配置文件。",
        last_err
    ))
}

/// 自动探测最佳可用浏览器类型。按优先级 [chrome, msedge, brave, firefox]
/// 返回第一个能定位到 exe 的类型，供 `ensure_browser_session_cmd` /
/// `start_browser_session_cmd`（空 browser_type 时）使用。
///
/// v1.9.6 重打新增：让前端/技能不传 browserType 也能自动选浏览器，
/// 避免技能硬编码 brave→chrome 导致 Win11-only-Edge 机器启动失败。
pub fn detect_best_browser() -> Option<&'static str> {
    for bt in &["chrome", "msedge", "brave", "firefox"] {
        if let Some(exe) = executable_for(bt) {
            if locate_browser_path(exe).is_some() {
                return Some(bt);
            }
        }
    }
    None
}

/// Best-effort lookup of a browser executable path on disk. Returns
/// `None` if the lookup fails or the platform is unsupported; the caller
/// will then fall back to `Browser::launch`'s default discovery (which
/// inspects the well-known install locations itself).
///
/// 健壮化（v1.9.6）：改为 `exe` 感知——每个浏览器类型有自己的候选路径列表，
/// 用环境变量（PROGRAMFILES / PROGRAMFILES(X86) / LOCALAPPDATA）替代硬编码
/// `C:\Program Files`，并加注册表 App Paths 查询作为强兜底。Brave 装在
/// `%LOCALAPPDATA%\BraveSoftware\...`，旧逻辑完全检测不到。
#[allow(unused_variables)]
fn locate_browser_path(exe: &str) -> Option<std::path::PathBuf> {
    #[cfg(target_os = "windows")]
    {
        // 1. 按浏览器类型枚举候选路径（用环境变量展开，处理非系统盘/自定义
        //    ProgramFiles 目录/用户级安装）。
        let pf = std::env::var("PROGRAMFILES").ok();
        let pf86 = std::env::var("PROGRAMFILES(X86)").ok();
        let lad = std::env::var("LOCALAPPDATA").ok();
        let candidates: Vec<std::path::PathBuf> = match exe {
            "chrome" => {
                let mut v = vec![];
                if let Some(d) = &pf { v.push(std::path::PathBuf::from(d).join("Google").join("Chrome").join("Application").join("chrome.exe")); }
                if let Some(d) = &pf86 { v.push(std::path::PathBuf::from(d).join("Google").join("Chrome").join("Application").join("chrome.exe")); }
                if let Some(d) = &lad { v.push(std::path::PathBuf::from(d).join("Google").join("Chrome").join("Application").join("chrome.exe")); }
                v
            }
            "msedge" => {
                let mut v = vec![];
                if let Some(d) = &pf { v.push(std::path::PathBuf::from(d).join("Microsoft").join("Edge").join("Application").join("msedge.exe")); }
                if let Some(d) = &pf86 { v.push(std::path::PathBuf::from(d).join("Microsoft").join("Edge").join("Application").join("msedge.exe")); }
                v
            }
            "brave" => {
                // Brave 默认装在用户级 LOCALAPPDATA（非 Program Files）。
                let mut v = vec![];
                if let Some(d) = &lad { v.push(std::path::PathBuf::from(d).join("BraveSoftware").join("Brave-Browser").join("Application").join("brave.exe")); }
                if let Some(d) = &pf { v.push(std::path::PathBuf::from(d).join("BraveSoftware").join("Brave-Browser").join("Application").join("brave.exe")); }
                if let Some(d) = &pf86 { v.push(std::path::PathBuf::from(d).join("BraveSoftware").join("Brave-Browser").join("Application").join("brave.exe")); }
                v
            }
            "firefox" => {
                let mut v = vec![];
                if let Some(d) = &pf { v.push(std::path::PathBuf::from(d).join("Mozilla Firefox").join("firefox.exe")); }
                if let Some(d) = &pf86 { v.push(std::path::PathBuf::from(d).join("Mozilla Firefox").join("firefox.exe")); }
                v
            }
            _ => vec![],
        };
        for c in candidates.iter() {
            if c.exists() {
                return Some(c.clone());
            }
        }

        // 2. 注册表 App Paths 查询（HKLM + HKCU）——处理任意安装位置，
        //    包括 SYSTEM 账户下 LOCALAPPDATA 不可用的边界。镜像
        //    `hermes::cli_resolver::lookup_app_paths` 的实现模式。
        if let Some(p) = lookup_app_paths_windows(exe) {
            return Some(p);
        }

        // 3. `where {exe}` 作为最终回退（PATH 上的可执行文件）。
        //    CREATE_NO_WINDOW 避免弹出控制台窗口。
        {
            let mut command = std::process::Command::new("where");
            command
                .arg(exe)
                .stdin(std::process::Stdio::null())
                .stdout(std::process::Stdio::piped())
                .stderr(std::process::Stdio::null());
            crate::commands::legacy::apply_no_window(&mut command);
            if let Ok(out) = command.output() {
                if out.status.success() {
                    if let Some(line) = String::from_utf8_lossy(&out.stdout).lines().next() {
                        let p = std::path::PathBuf::from(line.trim());
                        if p.exists() {
                            return Some(p);
                        }
                    }
                }
            }
        }
    }
    #[cfg(target_os = "macos")]
    {
        let candidates = [
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/Applications/Chromium.app/Contents/MacOS/Chromium",
            "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
            "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser",
        ];
        for c in candidates.iter() {
            let p = std::path::PathBuf::from(c);
            if p.exists() {
                return Some(p);
            }
        }
    }
    #[cfg(target_os = "linux")]
    {
        if let Ok(out) = std::process::Command::new("which").arg(exe).output() {
            if out.status.success() {
                if let Some(line) = String::from_utf8_lossy(&out.stdout).lines().next() {
                    let p = std::path::PathBuf::from(line.trim());
                    if p.exists() {
                        return Some(p);
                    }
                }
            }
        }
    }
    None
}

/// Windows 注册表 App Paths 查询（HKLM + HKCU）。
///
/// 查询 `SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\{exe}.exe`
/// 的 default 值（即浏览器可执行文件的绝对路径）。这是 Windows 上最可靠
/// 的浏览器定位方式——无论装在哪个盘/目录，只要安装时注册了 App Paths
/// （Chrome/Edge/Brave 的官方安装器都会注册）就能查到。
///
/// 镜像 `hermes::cli_resolver::CliResolver::lookup_app_paths` 的实现，
/// 但这里是 free function（不依赖 CliResolver 实例）。
#[cfg(target_os = "windows")]
fn lookup_app_paths_windows(exe: &str) -> Option<std::path::PathBuf> {
    use winreg::enums::{HKEY_CURRENT_USER, HKEY_LOCAL_MACHINE};
    use winreg::RegKey;

    let hives = [HKEY_LOCAL_MACHINE, HKEY_CURRENT_USER];
    // 同时尝试 `{exe}` 和 `{exe}.exe` 两种键名。
    let key_names = [format!("{}.exe", exe), exe.to_string()];
    for hive in &hives {
        let root = RegKey::predef(*hive);
        let app_paths = match root.open_subkey(
            "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths",
        ) {
            Ok(k) => k,
            Err(_) => continue,
        };
        for key_name in &key_names {
            if let Ok(subkey) = app_paths.open_subkey(key_name) {
                if let Ok(default_val) = subkey.get_value::<String, _>("") {
                    let path = std::path::PathBuf::from(&default_val);
                    if path.exists() {
                        return Some(path);
                    }
                }
            }
        }
    }
    None
}

/// Stop the session and remove it from the shared map.
pub async fn close_session(
    map: &SessionMap,
    session_id: &str,
) -> Result<(), String> {
    // 锁内只 remove 取出 session,立即释放锁;
    // 锁外 drop session.browser / current_page,避免持锁 drop 阻塞其他锁请求。
    // (browser drop 内部可能触发 handler 通道关闭、wait future 等耗时操作。)
    let session = {
        let mut guard = map.lock().await;
        guard.remove(session_id)
    };
    if let Some(mut s) = session {
        // `Browser` does not implement `Drop`-close (chromiumoxide 的 Drop 不杀进程),
        // 必须 `kill().await` 显式终止子进程, 否则每次关闭会话都泄漏一个
        // Chrome/Brave 子进程。先取走 page 再 kill, 避免持有 page 时 kill 报错。
        drop(s.current_page.take());
        let _ = s.browser.kill().await;
        drop(s.browser);
        // 清理 per-session 隔离 profile 目录（start_session 创建的临时目录）。
        // `let _ =` 忽略被占用文件导致的删除失败（浏览器刚关闭，部分文件
        // 可能还来不及释放句柄）。
        if let Some(dir) = &s.user_data_dir {
            let _ = std::fs::remove_dir_all(dir);
        }
    }
    Ok(())
}

/// Convert a `BrowserInfo` into the `SoftwareInfo` shape used by the
/// settings UI; bridges the two vocabularies so we don't have to
/// duplicate code.
pub fn browsers_to_software(browsers: &[BrowserInfo]) -> Vec<SoftwareInfo> {
    browsers
        .iter()
        .map(|b| SoftwareInfo {
            name: b.browser_type.clone(),
            installed: b.installed,
            source: Some("browser".to_string()),
        })
        .collect()
}
