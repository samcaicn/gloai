// AiMarketing — Tauri 2 desktop client with official DSH WebUI.
//
// Architecture:
//   - Downloads DSH backend on first run (user click)
//   - Extracts to _up_/dsh/ directory
//   - Spawns `node apps/cli/lib/bin.js --profile web --port 3080` as child process
//   - WebView loads DSH WebUI from http://127.0.0.1:3080

use std::process::Child;
use std::sync::{Arc, Mutex};
#[cfg(windows)]
use std::os::windows::process::CommandExt;
use std::path::PathBuf;
use tauri::{Manager, State, Emitter};
use futures_util::StreamExt;

/// DSH backend download URL (configurable at build time)
const DSH_BACKEND_URL: &str = "http://127.0.0.1:8899/dsh-backend.tgz";
const DSH_BACKEND_VERSION: &str = "0.1.2-alpha.1";

/// Application state - only window/backend management, NO business logic.
pub struct AppState {
    /// Handle to the DSH backend child process
    dsh_backend: Mutex<Option<Child>>,
    /// Port the DSH backend is running on
    dsh_port: Mutex<Option<u16>>,
    /// Full URL with token (from DSH stdout)
    dsh_url: Arc<Mutex<Option<String>>>,
    /// DSH backend installation directory
    dsh_install_dir: Arc<Mutex<Option<PathBuf>>>,
}

impl AppState {
    pub fn new() -> Self {
        Self {
            dsh_backend: Mutex::new(None),
            dsh_port: Mutex::new(None),
            dsh_url: Arc::new(Mutex::new(None)),
            dsh_install_dir: Arc::new(Mutex::new(None)),
        }
    }

    /// Resolve DSH bin.js path. Returns None if backend needs to be downloaded.
    fn find_dsh() -> Option<(PathBuf, PathBuf)> {
        let mut candidates: Vec<PathBuf> = Vec::new();

        // 1) Previously downloaded backend
        if let Ok(dir) = std::env::current_exe() {
            if let Some(parent) = dir.parent() {
                candidates.push(parent.join("_up_/dsh/apps/cli/lib/bin.js"));
                candidates.push(parent.join("dsh/apps/cli/lib/bin.js"));
            }
        }

        // 2) Development source (D:\code\dsh\deepseek-harness-src)
        candidates.push(PathBuf::from("D:/code/dsh/deepseek-harness-src/apps/cli/lib/bin.js"));

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
                let node_exe = Self::find_node_near(dsh_bin)
                    .or_else(Self::find_node_in_path);
                if let Some(node) = node_exe {
                    return Some((node, dsh_bin.clone()));
                }
            }
        }
        None
    }

    /// Check if DSH backend is available locally.
    pub fn is_dsh_available() -> bool {
        Self::find_dsh().is_some()
    }

    /// Download and extract DSH backend to _up_/dsh/ directory.
    pub async fn download_dsh_backend(
        app_handle: tauri::AppHandle,
        state: State<'_, AppState>,
    ) -> Result<(), String> {
        let exe_path = std::env::current_exe()
            .map_err(|e| format!("Cannot find exe: {}", e))?;
        let exe_dir = exe_path.parent()
            .ok_or("Cannot find exe parent")?
            .to_path_buf();

        let install_dir = exe_dir.join("_up_/dsh");
        let tgz_path = exe_dir.join("dsh-backend.tgz");

        // Download with progress
        log::info!("[dsh] downloading backend v{} from {}", DSH_BACKEND_VERSION, DSH_BACKEND_URL);
        let client = reqwest::Client::new();
        let response = client.get(DSH_BACKEND_URL)
            .send()
            .await
            .map_err(|e| format!("Download failed: {}", e))?;

        let total_size = response.content_length().unwrap_or(0);
        let mut downloaded: u64 = 0;
        let mut stream = response.bytes_stream();

        // Download to file
        let mut file = std::fs::File::create(&tgz_path)
            .map_err(|e| format!("Cannot create file: {}", e))?;

        while let Some(chunk) = stream.next().await {
            let chunk = chunk.map_err(|e| format!("Download error: {}", e))?;
            std::io::Write::write_all(&mut file, &chunk)
                .map_err(|e| format!("Write error: {}", e))?;
            downloaded += chunk.len() as u64;

            // Emit progress event
            if total_size > 0 {
                let progress = (downloaded as f64 / total_size as f64 * 100.0) as u32;
                let _ = app_handle.emit("dsh-download-progress", progress);
            }
        }
        drop(file);
        log::info!("[dsh] download complete: {} bytes", downloaded);

        // Extract tgz
        log::info!("[dsh] extracting backend to {:?}", install_dir);
        let _ = app_handle.emit("dsh-download-progress", 101); // Extracting

        if install_dir.exists() {
            std::fs::remove_dir_all(&install_dir)
                .map_err(|e| format!("Cannot clean old dir: {}", e))?;
        }
        std::fs::create_dir_all(&install_dir)
            .map_err(|e| format!("Cannot create dir: {}", e))?;

        // Extract using tar (Windows 10+ has tar.exe)
        let output = std::process::Command::new("tar")
            .args(["-xzf", &tgz_path.to_string_lossy(), "-C", &install_dir.to_string_lossy()])
            .output()
            .map_err(|e| format!("Extract failed: {}", e))?;

        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            return Err(format!("Extract error: {}", stderr));
        }

        // Cleanup tgz
        let _ = std::fs::remove_file(&tgz_path);

        // Verify extraction
        let bin_js = install_dir.join("apps/cli/lib/bin.js");
        if !bin_js.exists() {
            return Err("Extraction incomplete: bin.js not found".to_string());
        }

        // Store install dir in state
        if let Ok(mut dir) = state.dsh_install_dir.lock() {
            *dir = Some(install_dir.clone());
        }

        let _ = app_handle.emit("dsh-download-progress", 100);
        log::info!("[dsh] backend installed to {:?}", install_dir);
        Ok(())
    }

    /// Try to find node.exe near the bin.js path.
    fn find_node_near(dsh_bin: &PathBuf) -> Option<PathBuf> {
        if let Some(dir) = dsh_bin.parent() {
            // Check sibling directories and ancestors for node.exe
            let mut current = Some(dir);
            while let Some(d) = current {
                let node = d.join("node.exe");
                if node.exists() { return Some(node); }
                // Check common Node.js locations relative to exe
                let node = d.join("nodejs/node.exe");
                if node.exists() { return Some(node); }
                current = d.parent();
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

        // Resolve symlinks (important for pnpm global symlinks)
        let dsh_bin_resolved = std::fs::canonicalize(&dsh_bin).unwrap_or_else(|_| dsh_bin.clone());
        log::info!("[dsh] resolved bin: {:?}", dsh_bin_resolved);

        self.ensure_web_profile()?;

        let port_str = fixed_port.to_string();
        let mut cmd = std::process::Command::new(&node_exe);
        cmd.arg(&dsh_bin_resolved)
            .arg("--profile").arg("web")
            .arg("--host").arg("127.0.0.1")
            .arg("--port").arg(&port_str)
            .arg("--no-open")
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::null())
            .current_dir(dsh_bin_resolved.parent().and_then(|p| p.parent()).unwrap_or(std::path::Path::new(".")));
        
        #[cfg(windows)]
        cmd.creation_flags(0x00000008 | 0x00000200);
        
        let mut child = cmd.spawn()
            .map_err(|e| format!("Failed to start DSH backend: {}", e))?;

        log::info!("[dsh] backend starting on 127.0.0.1:{}", fixed_port);

        // Capture stdout to get token URL
        if let Some(stdout) = child.stdout.take() {
            use std::io::{BufRead, BufReader};
            let dsh_url = self.dsh_url.clone();
            std::thread::spawn(move || {
                let reader = BufReader::new(stdout);
                for line in reader.lines().flatten() {
                    log::info!("[dsh stdout] {}", line);
                    if let Some(start) = line.find("http://") {
                        let url = line[start..].trim().to_string();
                        log::info!("[dsh] token URL: {}", url);
                        if let Ok(mut u) = dsh_url.lock() {
                            *u = Some(url);
                        }
                        break;
                    }
                }
            });
        }

        *backend = Some(child);
        if let Ok(mut p) = self.dsh_port.lock() {
            *p = Some(fixed_port);
        }
        log::info!("[dsh] backend started on 127.0.0.1:{}", fixed_port);
        Ok(fixed_port)
    }

    /// Ensure the web profile exists.
    fn ensure_web_profile(&self) -> Result<(), String> {
        let home = std::env::var("USERPROFILE").or_else(|_| std::env::var("HOME")).map_err(|_| "no home dir")?;
        let profile_dir = PathBuf::from(&home).join(".dsh/profiles/web");
        if profile_dir.exists() {
            return Ok(());
        }
        std::fs::create_dir_all(&profile_dir).map_err(|e| format!("create profile dir: {}", e))?;
        let pkg = r#"{
  "name": "dsh-profile-web",
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
        log::info!("[dsh] created web profile at {:?}", profile_dir);
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

    /// Get the DSH URL (with token if available).
    pub fn get_dsh_url_with_token(&self) -> Option<String> {
        if let Ok(u) = self.dsh_url.lock() {
            if let Some(url) = u.as_ref() {
                return Some(url.clone());
            }
        }
        if let Ok(p) = self.dsh_port.lock() {
            if let Some(port) = *p {
                return Some(format!("http://127.0.0.1:{}", port));
            }
        }
        None
    }
}

// ============================================================
// DSH backend commands
// ============================================================

#[tauri::command]
fn get_dsh_url(state: State<'_, AppState>) -> Result<String, String> {
    state.get_dsh_url_with_token()
        .ok_or_else(|| "DSH backend not running".to_string())
}

/// Check if DSH backend is available
#[tauri::command]
fn check_dsh_available() -> bool {
    AppState::is_dsh_available()
}

/// Download DSH backend
#[tauri::command]
async fn download_dsh_backend(
    app_handle: tauri::AppHandle,
    state: State<'_, AppState>,
) -> Result<(), String> {
    AppState::download_dsh_backend(app_handle, state).await
}

// ============================================================
// Window control commands
// ============================================================

// Window control is handled by the browser via CSS/JS
// These Tauri commands are disabled due to API compatibility issues

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

#[cfg_attr(target_os = "android", tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .setup(|app| {
            let state = AppState::new();

            // Ensure task-board plugin is installed (NSIS integration)
            if let Err(e) = ensure_task_board_installed() {
                log::warn!("[dsh] task-board auto-install skipped: {}", e);
            }

            // Check if DSH backend is available
            let dsh_available = AppState::is_dsh_available();

            if dsh_available {
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
            } else {
                // Backend not available - emit event for frontend to show download UI
                log::info!("[dsh] backend not found, waiting for user to download");
                let _ = app.emit("dsh-needs-download", ());
            }

            // Navigate WebView to the token URL once available
            let app_handle = app.handle().clone();
            let nav_state = state.dsh_url.clone();
            std::thread::spawn(move || {
                for _ in 0..60 {
                    if let Ok(url) = nav_state.lock() {
                        if let Some(token_url) = url.as_ref() {
                            if let Some(win) = app_handle.get_webview_window("main") {
                                log::info!("[dsh] navigating to token URL: {}", token_url);
                                match token_url.parse() {
                                    Ok(url) => { let _ = win.navigate(url); }
                                    Err(e) => log::error!("[dsh] failed to parse URL: {}", e),
                                }
                            }
                            break;
                        }
                    }
                    std::thread::sleep(std::time::Duration::from_millis(500));
                }
            });

            app.manage(state);
            log::info!("[dsh] setup complete, launching app");
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            // DSH backend
            get_dsh_url,
            check_dsh_available,
            download_dsh_backend,
        ])
        .on_window_event(|_window, event| {
            if let tauri::WindowEvent::CloseRequested { .. } = event {
                // Backend process will be cleaned up by OS
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
