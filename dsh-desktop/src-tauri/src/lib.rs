// AiMarketing — Tauri 2 desktop client with official DSH WebUI.
//
// Architecture:
//   - Spawns `npx @deepseek-ai/dsh web` as a child process
//   - Waits for the backend to be ready
//   - Navigates the WebView to the backend URL (serves the official WebUI)
//
// 【铁律】所有业务逻辑由 Cordis 插件处理，dsh-desktop 只做：
//   1. 启动 DSH backend
//   2. 打开 WebView
//   3. 窗口管理
// 插件列表：
//   - dsh-plugin-autoskill  (自进化引擎)
//   - dsh-plugin-evolution  (进化追踪)
//   - dsh-plugin-memory     (记忆系统)
//   - dsh-plugin-skill      (技能系统)
//   - dsh-plugin-storage    (数据存储)
//   - dsh-plugin-watermark  (去水印)

use std::process::Child;
use std::sync::Mutex;
#[cfg(windows)]
use std::os::windows::process::CommandExt;
use std::path::PathBuf;
use tauri::{Manager, State};

/// Application state - only window/backend management, NO business logic.
pub struct AppState {
    /// Handle to the DSH backend child process
    dsh_backend: Mutex<Option<Child>>,
    /// Port the DSH backend is running on
    dsh_port: Mutex<Option<u16>>,
}

impl AppState {
    pub fn new() -> Self {
        Self {
            dsh_backend: Mutex::new(None),
            dsh_port: Mutex::new(None),
        }
    }

    /// Resolve DSH bin.js path via multiple candidates.
    fn find_dsh() -> Option<(PathBuf, PathBuf)> {
        let mut candidates: Vec<PathBuf> = Vec::new();

        // 1) NSIS install directory / portable layout
        if let Ok(exe) = std::env::current_exe() {
            if let Some(dir) = exe.parent() {
                candidates.push(dir.join("_up_/dsh/bin.js"));
                candidates.push(dir.join("dsh/bin.js"));
                candidates.push(dir.join("../.dsh-portable/node_modules/@deepseek-ai/dsh/lib/bin.js"));
            }
        }

        // 2) CatPaw runtime (dev fallback)
        if let Some(home) = std::env::var_os("USERPROFILE").or_else(|| std::env::var_os("HOME")) {
            let base = PathBuf::from(home).join(".meituan-catpaw/runtimes/node/versions");
            if let Ok(versions) = std::fs::read_dir(&base) {
                for entry in versions.flatten() {
                    let p = entry.path().join("node_modules/@deepseek-ai/dsh/lib/bin.js");
                    candidates.push(p);
                    candidates.push(entry.path().join("node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js"));
                }
            }
        }

        // 3) Global npm prefix
        for prefix in &[
            PathBuf::from("C:/Users/Administrator/AppData/Roaming/npm"),
            PathBuf::from("C:/Program Files/nodejs"),
            PathBuf::from("C:/Program Files (x86)/nodejs"),
        ] {
            candidates.push(prefix.join("node_modules/@deepseek-ai/dsh/lib/bin.js"));
        }

        for dsh_bin in &candidates {
            if dsh_bin.exists() {
                if let Some(node_modules) = dsh_bin.parent() {
                    if let Some(dsh_scope) = node_modules.parent() {
                        if let Some(dsh_root) = dsh_scope.parent() {
                            let node_exe = if let Some(nd) = dsh_root.parent() {
                                let n = nd.join("node.exe");
                                if n.exists() { Some(n) } else { Self::find_node_in_path() }
                            } else {
                                Self::find_node_in_path()
                            };
                            if let Some(node) = node_exe {
                                return Some((node, dsh_bin.clone()));
                            }
                        }
                    }
                }
            }
        }
        None
    }

    /// Try to find node.exe in PATH or common locations.
    fn find_node_in_path() -> Option<PathBuf> {
        if let Ok(path_var) = std::env::var("PATH") {
            for dir in path_var.split(';') {
                let p = PathBuf::from(dir).join("node.exe");
                if p.exists() { return Some(p); }
            }
        }
        for p in &[
            PathBuf::from("C:/Program Files/nodejs/node.exe"),
            PathBuf::from("C:/Program Files (x86)/nodejs/node.exe"),
            PathBuf::from("C:/Users/Administrator/AppData/Roaming/npm/node.exe"),
        ] {
            if p.exists() { return Some(p.clone()); }
        }
        None
    }

    /// Spawn the DSH backend process on the FIXED port 3080.
    pub fn start_dsh_backend(&self) -> Result<u16, String> {
        let mut backend = self.dsh_backend.lock().map_err(|e| e.to_string())?;
        let fixed_port: u16 = 3080;

        if backend.is_some() {
            if let Some(port) = self.dsh_port.lock().ok().and_then(|p| *p) {
                return Ok(port);
            }
            return Ok(fixed_port);
        }

        match std::net::TcpListener::bind(format!("127.0.0.1:{}", fixed_port)) {
            Ok(l) => {
                drop(l);
            }
            Err(_) => {
                log::info!("[dsh] port {} already in use, reusing existing instance", fixed_port);
                if let Ok(mut p) = self.dsh_port.lock() {
                    *p = Some(fixed_port);
                }
                return Ok(fixed_port);
            }
        }

        let (node_exe, dsh_bin) = Self::find_dsh().ok_or("Cannot find node + @deepseek-ai/dsh. Please install: npm i -g @deepseek-ai/dsh")?;

        self.ensure_aimarketing_profile()?;

        #[cfg(windows)]
        let child = {
            let port_str = fixed_port.to_string();
            let node_path = node_exe.parent().map(|p| p.to_string_lossy().to_string()).unwrap_or_default();
            std::process::Command::new(&node_exe)
                .arg(&dsh_bin)
                .arg("--profile").arg("aimarketing")
                .arg("--host").arg("127.0.0.1")
                .arg("--port").arg(&port_str)
                .arg("--no-open")
                .env("PATH", &node_path)
                .stdin(std::process::Stdio::null())
                .stdout(std::process::Stdio::null())
                .stderr(std::process::Stdio::null())
                .creation_flags(0x00000008 | 0x00000200)
                .spawn()
                .map_err(|e| format!("Failed to start DSH backend: {}", e))?
        };

        #[cfg(not(windows))]
        let child = {
            let port_str = fixed_port.to_string();
            std::process::Command::new(&node_exe)
                .arg(&dsh_bin)
                .arg("--profile").arg("aimarketing")
                .arg("--host").arg("127.0.0.1")
                .arg("--port").arg(&port_str)
                .arg("--no-open")
                .stdin(std::process::Stdio::null())
                .stdout(std::process::Stdio::piped())
                .stderr(std::process::Stdio::piped())
                .spawn()
                .map_err(|e| format!("Failed to start DSH backend: {}", e))?
        };

        log::info!("[dsh] backend starting on 127.0.0.1:{} via {:?}", fixed_port, node_exe);

        *backend = Some(child);
        if let Ok(mut p) = self.dsh_port.lock() {
            *p = Some(fixed_port);
        }
        log::info!("[dsh] backend started on 127.0.0.1:{}", fixed_port);
        Ok(fixed_port)
    }

    /// Ensure the aimarketing profile exists.
    fn ensure_aimarketing_profile(&self) -> Result<(), String> {
        let home = std::env::var("USERPROFILE").or_else(|_| std::env::var("HOME")).map_err(|_| "no home dir")?;
        let profile_dir = PathBuf::from(&home).join(".dsh/profiles/aimarketing");
        if profile_dir.exists() {
            return Ok(());
        }
        std::fs::create_dir_all(&profile_dir).map_err(|e| format!("create profile dir: {}", e))?;
        let pkg = r#"{
  "name": "dsh-profile-aimarketing",
  "private": true,
  "dsh": {
    "profile": {
      "bundles": [
        "@deepseek-ai/dsh-base",
        "@deepseek-ai/dsh-web-app"
      ]
    }
  }
}"#;
        std::fs::write(profile_dir.join("package.json"), pkg)
            .map_err(|e| format!("write package.json: {}", e))?;
        log::info!("[dsh] created aimarketing profile at {:?}", profile_dir);
        Ok(())
    }

    /// Wait for the backend to be ready by polling the TCP port.
    pub fn wait_for_backend(&self, port: u16, timeout_secs: u64) -> bool {
        let start = std::time::Instant::now();
        let timeout = std::time::Duration::from_secs(timeout_secs);
        while start.elapsed() < timeout {
            if std::net::TcpStream::connect(format!("127.0.0.1:{}", port)).is_ok() {
                return true;
            }
            std::thread::sleep(std::time::Duration::from_millis(500));
        }
        false
    }

    /// Get the DSH backend port (if running).
    pub fn dsh_port(&self) -> Option<u16> {
        self.dsh_port.lock().ok().and_then(|p| *p)
    }
}

// ============================================================
// DSH backend commands
// ============================================================

#[tauri::command]
fn get_dsh_url(state: State<'_, AppState>) -> Result<String, String> {
    match state.dsh_port() {
        Some(port) => Ok(format!("http://127.0.0.1:{}", port)),
        None => Err("DSH backend not running".to_string()),
    }
}

// ============================================================
// Window control commands
// ============================================================

#[tauri::command]
fn minimize_window(win: tauri::WebviewWindow) -> Result<(), String> {
    win.minimize().map_err(|e| e.to_string())
}

#[tauri::command]
fn toggle_maximize(win: tauri::WebviewWindow) -> Result<(), String> {
    let is_max = win.is_maximized().map_err(|e| e.to_string())?;
    if is_max {
        win.unmaximize()
    } else {
        win.maximize()
    }
    .map_err(|e| e.to_string())
}

#[tauri::command]
fn close_window(win: tauri::WebviewWindow) -> Result<(), String> {
    win.close().map_err(|e| e.to_string())
}

// ============================================================
// Task-board plugin auto-install (NSIS integration)
// ============================================================

fn ensure_task_board_installed() -> Result<(), String> {
    let profile_dir = std::env::home_dir()
        .ok_or("Cannot find home directory")?
        .join(".dsh/profiles/web");
    let pkg_json = profile_dir.join("package.json");

    if pkg_json.exists() {
        if let Ok(content) = std::fs::read_to_string(&pkg_json) {
            if content.contains("@linxin666/dsh-client-ui-task-board") {
                log::info!("[dsh] task-board plugin already in web profile");
                return Ok(());
            }
        }
    }

    log::info!("[dsh] installing task-board plugin to web profile...");
    let output = std::process::Command::new("npx")
        .args([
            "@deepseek-ai/dsh",
            "plugin",
            "--profile",
            "web",
            "add",
            "@linxin666/dsh-client-ui-task-board@latest",
        ])
        .current_dir(&profile_dir)
        .output()
        .map_err(|e| format!("Failed to run dsh plugin: {}", e))?;

    if output.status.success() {
        log::info!("[dsh] task-board plugin installed successfully");
        Ok(())
    } else {
        let stderr = String::from_utf8_lossy(&output.stderr);
        log::warn!("[dsh] task-board install returned: {}", stderr);
        Ok(())
    }
}

// ============================================================
// Tauri entry point
// ============================================================

pub fn run() {
    tauri::Builder::default()
        .setup(|app| {
            let state = AppState::new();

            // Ensure task-board plugin is installed (NSIS integration)
            if let Err(e) = ensure_task_board_installed() {
                log::warn!("[dsh] task-board auto-install skipped: {}", e);
            }

            // Start DSH backend SYNCHRONOUSLY
            match state.start_dsh_backend() {
                Ok(port) => {
                    if state.wait_for_backend(port, 60) {
                        log::info!("[dsh] backend ready on http://127.0.0.1:{}", port);
                    } else {
                        log::error!("[dsh] backend did not become ready on port {}", port);
                    }
                }
                Err(e) => {
                    log::error!("[dsh] failed to start backend: {}", e);
                }
            }

            app.manage(state);
            log::info!("[dsh] setup complete, launching app");
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            // DSH backend
            get_dsh_url,
            // Window control only - all business logic is in plugins
            minimize_window,
            toggle_maximize,
            close_window,
        ])
        .on_window_event(|_window, event| {
            if let tauri::WindowEvent::CloseRequested { .. } = event {
                // Backend process will be cleaned up by OS
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
