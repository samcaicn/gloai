use anyhow::Result;
use chrono;
use flate2::read::GzDecoder;
use std::fs::{create_dir_all, File, remove_file};
use std::io::{Write, Read};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;
use tar::Archive;
use tauri::{AppHandle, Manager};
use tokio::sync::{Mutex, AbortHandle};
use tokio::time::sleep;

#[derive(Debug)]
pub struct UpdateManager {
    pub app_handle: Arc<Mutex<Option<AppHandle>>>,
    update_check_interval: Duration,
    update_server_url: String,
    active_download: Arc<Mutex<Option<AbortHandle>>>,
}

#[derive(Debug)]
struct UpdateInfo {
    version: String,
    download_url: String,
    release_notes: String,
    sha256: Option<String>,
}

#[derive(Debug, Clone)]
pub struct UpdateDownloadProgress {
    pub received: u64,
    pub total: Option<u64>,
    pub percent: Option<f32>,
    pub speed: Option<u64>, // bytes per second
}

impl UpdateManager {
    pub fn new() -> Self {
        Self {
            app_handle: Arc::new(Mutex::new(None)),
            update_check_interval: Duration::from_secs(3600), // 1 hour
            update_server_url: "https://api.ggai.com/v1/update".to_string(),
            active_download: Arc::new(Mutex::new(None)),
        }
    }

    pub async fn set_app_handle(&self, app_handle: AppHandle) {
        let mut app_handle_lock = self.app_handle.lock().await;
        *app_handle_lock = Some(app_handle);
    }

    pub async fn start(&self) {
        let app_handle_clone = self.app_handle.clone();
        let interval = self.update_check_interval;
        let server_url = self.update_server_url.clone();

        tokio::spawn(async move {
            loop {
                let app_handle = app_handle_clone.lock().await;
                if let Some(app_handle) = &*app_handle {
                    let app_handle_clone = app_handle.clone();
                    drop(app_handle);

                    if let Err(e) = Self::check_for_updates(&app_handle_clone, &server_url).await {
                        eprintln!("Error checking for updates: {:?}", e);
                    }
                }
                sleep(interval).await;
            }
        });
    }

    async fn check_for_updates(app_handle: &AppHandle, server_url: &str) -> Result<()> {
        // 1. Get current app version
        let current_version = app_handle.package_info().version.to_string();
        let app_name = app_handle.package_info().name.to_string();
        let platform = Self::get_current_platform();

        // 2. Check for updates from API
        let update_info = Self::fetch_update_info(server_url, &app_name, &current_version, &platform).await?;

        // 3. If update available, download it
        if let Some(info) = update_info {
            if Self::is_newer_version(&info.version, &current_version) {
                println!("New version available: {}", info.version);
                
                // 发送更新可用通知
                if let Some(window) = app_handle.get_webview_window("main") {
                    window.emit("update_available", serde_json::json!({
                        "version": info.version,
                        "release_notes": info.release_notes
                    })).ok();
                }
                
                Self::download_update(app_handle, &info.download_url, info.sha256.as_deref()).await?;
            }
        }

        Ok(())
    }

    async fn fetch_update_info(
        server_url: &str,
        app_name: &str,
        current_version: &str,
        platform: &str,
    ) -> Result<Option<UpdateInfo>> {
        use reqwest::Client;

        let client = Client::new();
        let response = client
            .get(server_url)
            .query(&[
                ("app", app_name),
                ("version", current_version),
                ("platform", platform),
            ])
            .send()
            .await?;

        if response.status().is_success() {
            let result: serde_json::Value = response.json().await?;
            if result["update_available"].as_bool().unwrap_or(false) {
                let version = result["version"].as_str().unwrap_or("").to_string();
                let release_notes = result["release_notes"].as_str().unwrap_or("").to_string();

                // 根据平台获取对应的CDN下载地址
                let download_url = match platform {
                    "macos" => result["download"]["macos"]
                        .as_str()
                        .unwrap_or("")
                        .to_string(),
                    "windows" => result["download"]["windows"]
                        .as_str()
                        .unwrap_or("")
                        .to_string(),
                    "linux" => result["download"]["linux"]
                        .as_str()
                        .unwrap_or("")
                        .to_string(),
                    _ => "".to_string(),
                };

                // 获取SHA256校验和（可选，用于后续验证）
                let sha256 = match platform {
                    "macos" => result["download"]["macos_sha256"]
                        .as_str()
                        .map(|s| s.to_string()),
                    "windows" => result["download"]["windows_sha256"]
                        .as_str()
                        .map(|s| s.to_string()),
                    "linux" => result["download"]["linux_sha256"]
                        .as_str()
                        .map(|s| s.to_string()),
                    _ => None,
                };

                if !download_url.is_empty() {
                    return Ok(Some(UpdateInfo {
                        version,
                        download_url,
                        release_notes,
                        sha256,
                    }));
                }
            }
        }

        Ok(None)
    }

    pub async fn download_update(
        app_handle: &AppHandle,
        download_url: &str,
        expected_sha256: Option<&str>,
    ) -> Result<()> {
        // 1. Get download directory
        let download_dir = Self::get_update_download_dir()?;
        std::fs::create_dir_all(&download_dir)?;

        // 2. Get platform-specific update filename
        let update_filename = Self::get_update_filename();
        let filename = download_dir.join(&update_filename);
        let temp_filename = filename.with_extension("download");

        // 3. Download update with progress
        let mut file = File::create(&temp_filename)?;
        let client = reqwest::Client::new();
        let mut response = client.get(download_url).send().await?;
        
        let total = response.content_length();
        let mut received: u64 = 0;
        let mut last_received: u64 = 0;
        let mut last_time = std::time::Instant::now();
        
        while let Some(chunk) = response.chunk().await? {
            received += chunk.len() as u64;
            file.write_all(&chunk)?;
            
            // Calculate speed
            let now = std::time::Instant::now();
            let elapsed = now.duration_since(last_time);
            let speed = if elapsed.as_secs() > 0 {
                Some(((received - last_received) as u64) / elapsed.as_secs())
            } else {
                None
            };
            
            // Calculate percentage
            let percent = total.map(|t| (received as f32 / t as f32) * 100.0);
            
            // Send progress update
            if let Some(window) = app_handle.get_webview_window("main") {
                window.emit("update_progress", serde_json::json!({
                    "received": received,
                    "total": total,
                    "percent": percent,
                    "speed": speed
                })).ok();
            }
            
            last_received = received;
            last_time = now;
        }

        // 4. Verify SHA256 if provided
        if let Some(expected_hash) = expected_sha256 {
            let actual_hash = Self::calculate_sha256(&temp_filename)?;
            if actual_hash != expected_hash {
                // 删除损坏的文件
                remove_file(&temp_filename)?;
                return Err(anyhow::anyhow!(
                    "SHA256 checksum mismatch. Expected: {}, Actual: {}",
                    expected_hash,
                    actual_hash
                ));
            }
            println!("SHA256 checksum verified successfully");
        }

        // 5. Rename to final filename
        if temp_filename.exists() {
            if filename.exists() {
                remove_file(&filename)?;
            }
            std::fs::rename(&temp_filename, &filename)?;
        }

        // 6. Create update manifest
        let manifest_path = download_dir.join("update.json");
        let mut manifest_file = File::create(&manifest_path)?;
        let manifest = serde_json::json!({
            "version": "latest",
            "download_date": chrono::Utc::now().to_string(),
            "ready": true,
            "platform": Self::get_current_platform(),
            "update_filename": update_filename
        });
        manifest_file.write_all(manifest.to_string().as_bytes())?;

        // 7. Send download complete notification
        if let Some(window) = app_handle.get_webview_window("main") {
            window.emit("update_downloaded", serde_json::json!({
                "message": "Update downloaded successfully. Will install on restart."
            })).ok();
        }

        println!("Update downloaded successfully to: {:?}", filename);
        println!("Will install on restart");

        Ok(())
    }

    pub async fn cancel_download(&self) -> Result<()> {
        let mut active_download = self.active_download.lock().await;
        if let Some(abort_handle) = active_download.take() {
            abort_handle.abort();
            println!("Download cancelled");
        }
        Ok(())
    }

    fn calculate_sha256(file_path: &PathBuf) -> Result<String> {
        use sha2::{Digest, Sha256};

        let mut file = File::open(file_path)?;
        let mut hasher = Sha256::new();
        let mut buffer = [0; 8192];

        loop {
            let bytes_read = file.read(&mut buffer)?;
            if bytes_read == 0 {
                break;
            }
            hasher.update(&buffer[..bytes_read]);
        }

        let hash = hasher.finalize();
        Ok(hex::encode(hash))
    }

    fn get_update_filename() -> String {
        #[cfg(target_os = "macos")]
        return "update.app.tar.gz".to_string();

        #[cfg(target_os = "windows")]
        return "update.msi".to_string();

        #[cfg(target_os = "linux")]
        return "update.AppImage".to_string();

        #[cfg(not(any(target_os = "macos", target_os = "windows", target_os = "linux")))]
        return "update.zip".to_string();
    }

    fn get_update_download_dir() -> Result<PathBuf> {
        use directories_next::ProjectDirs;
        let project_dirs = ProjectDirs::from("com", "ggai", "ggai")
            .ok_or_else(|| anyhow::anyhow!("Failed to get project directories"))?;
        let app_dir = project_dirs.data_dir();
        Ok(app_dir.join("updates"))
    }

    fn get_current_platform() -> String {
        #[cfg(target_os = "macos")]
        return "macos".to_string();

        #[cfg(target_os = "windows")]
        return "windows".to_string();

        #[cfg(target_os = "linux")]
        return "linux".to_string();

        #[cfg(not(any(target_os = "macos", target_os = "windows", target_os = "linux")))]
        return "unknown".to_string();
    }

    pub async fn install_pending_update(&self) -> Result<bool> {
        // 1. Check if there's a pending update
        let download_dir = Self::get_update_download_dir()?;
        let manifest_path = download_dir.join("update.json");

        if !manifest_path.exists() {
            println!("No pending update found");
            return Ok(false);
        }

        // 2. Read update manifest
        let manifest_content = std::fs::read_to_string(manifest_path)?;
        let manifest: serde_json::Value = serde_json::from_str(&manifest_content)?;

        if !manifest["ready"].as_bool().unwrap_or(false) {
            println!("Update is not ready for installation");
            return Ok(false);
        }

        let platform = manifest["platform"].as_str().unwrap_or("unknown");
        let update_filename = manifest["update_filename"].as_str().unwrap_or("");

        // 3. Install update based on platform with error handling and rollback
        let success = match platform {
            "macos" => {
                match Self::install_macos_update(&download_dir, update_filename) {
                    Ok(_) => true,
                    Err(e) => {
                        eprintln!("Failed to install macOS update: {:?}", e);
                        false
                    }
                }
            }
            "windows" => {
                match Self::install_windows_update(&download_dir, update_filename) {
                    Ok(_) => true,
                    Err(e) => {
                        eprintln!("Failed to install Windows update: {:?}", e);
                        false
                    }
                }
            }
            "linux" => {
                match Self::install_linux_update(&download_dir, update_filename) {
                    Ok(_) => true,
                    Err(e) => {
                        eprintln!("Failed to install Linux update: {:?}", e);
                        false
                    }
                }
            }
            _ => {
                eprintln!("Unsupported platform: {}", platform);
                false
            }
        };

        // 4. Clean up regardless of success or failure
        // This ensures that failed updates don't block future update attempts
        remove_file(download_dir.join("update.json")).ok();
        remove_file(download_dir.join(update_filename)).ok();
        
        // Clean up extract directory
        let extract_dir = download_dir.join("extract");
        if extract_dir.exists() {
            std::fs::remove_dir_all(extract_dir).ok();
        }

        if success {
            println!("Update installed successfully");
        } else {
            println!("Update installation failed");
        }

        Ok(success)
    }

    #[cfg(target_os = "macos")]
    fn install_macos_update(download_dir: &PathBuf, update_filename: &str) -> Result<()> {
        use std::process::Command;

        // 1. Paths
        let update_file = download_dir.join(update_filename);
        let extract_dir = download_dir.join("extract");

        // 2. Extract the .app.tar.gz file
        create_dir_all(&extract_dir)?;

        let file = File::open(update_file)?;
        let decoder = GzDecoder::new(file);
        let mut archive = Archive::new(decoder);
        archive.unpack(&extract_dir)?;

        // 3. Find the .app bundle
        let app_bundle = extract_dir
            .read_dir()?
            .find_map(|entry| entry.ok())
            .filter(|entry| entry.file_name().to_string_lossy().ends_with(".app"))
            .ok_or_else(|| anyhow::anyhow!("No .app bundle found in update package"))?;

        let app_bundle_path = app_bundle.path();
        let app_bundle_name = app_bundle.file_name();

        // 4. Get current app path
        let current_exe = std::env::current_exe()?;
        let current_app_path = current_exe
            .ancestors()
            .nth(2)
            .ok_or_else(|| anyhow::anyhow!("Failed to find current app path"))?;

        // 5. Create backup
        let backup_path = current_app_path.with_extension("app.backup");
        if backup_path.exists() {
            std::fs::remove_dir_all(&backup_path)?;
        }
        std::fs::rename(&current_app_path, &backup_path)?;

        // 6. Copy new app with error handling
        let copy_result = Command::new("cp")
            .args(["-R", app_bundle_path.to_str().unwrap(), current_app_path.parent().unwrap().to_str().unwrap()])
            .status()?;

        if !copy_result.success() {
            // Restore from backup
            if current_app_path.exists() {
                std::fs::remove_dir_all(&current_app_path).ok();
            }
            if backup_path.exists() {
                std::fs::rename(&backup_path, &current_app_path)?;
            }
            return Err(anyhow::anyhow!("Failed to copy new app bundle"));
        }

        // 7. Set executable permissions
        let executable_path = current_app_path.join("Contents").join("MacOS").join("ggclaw");
        let chmod_result = Command::new("chmod")
            .args(["+x", executable_path.to_str().unwrap()])
            .status()?;

        if !chmod_result.success() {
            // Restore from backup
            if current_app_path.exists() {
                std::fs::remove_dir_all(&current_app_path).ok();
            }
            if backup_path.exists() {
                std::fs::rename(&backup_path, &current_app_path)?;
            }
            return Err(anyhow::anyhow!("Failed to set executable permissions"));
        }

        // 8. Clean up backup
        if backup_path.exists() {
            std::fs::remove_dir_all(&backup_path).ok();
        }

        println!("macOS update installed successfully: {:?}", app_bundle_name);
        Ok(())
    }

    #[cfg(not(target_os = "macos"))]
    fn install_macos_update(_download_dir: &PathBuf, _update_filename: &str) -> Result<()> {
        Err(anyhow::anyhow!("macOS update not supported on this platform"))
    }

    #[cfg(target_os = "windows")]
    fn install_windows_update(download_dir: &PathBuf, update_filename: &str) -> Result<()> {
        use std::process::Command;

        // 1. Paths
        let update_file = download_dir.join(update_filename);

        // 2. Run the MSI installer silently
        let install_result = Command::new("msiexec.exe")
            .args(["/i", update_file.to_str().unwrap(), "/qn", "/norestart"])
            .status()?;

        if !install_result.success() {
            eprintln!("Windows update installation failed with exit code: {:?}", install_result.code());
            return Err(anyhow::anyhow!("Failed to install Windows update"));
        }

        println!("Windows update installed successfully: {:?}", update_file);
        Ok(())
    }

    #[cfg(not(target_os = "windows"))]
    fn install_windows_update(_download_dir: &PathBuf, _update_filename: &str) -> Result<()> {
        Err(anyhow::anyhow!("Windows update not supported on this platform"))
    }

    #[cfg(target_os = "linux")]
    fn install_linux_update(download_dir: &PathBuf, update_filename: &str) -> Result<()> {
        use std::process::Command;

        // 1. Paths
        let update_file = download_dir.join(update_filename);

        // 2. Make the AppImage executable
        let chmod_result = Command::new("chmod")
            .args(["+x", update_file.to_str().unwrap()])
            .status()?;

        if !chmod_result.success() {
            eprintln!("Failed to make AppImage executable");
            return Err(anyhow::anyhow!("Failed to make AppImage executable"));
        }

        // 3. Run the AppImage installer
        let install_result = Command::new(update_file.to_str().unwrap())
            .status()?;

        if !install_result.success() {
            eprintln!("Linux update installation failed with exit code: {:?}", install_result.code());
            return Err(anyhow::anyhow!("Failed to install Linux update"));
        }

        println!("Linux update installed successfully: {:?}", update_file);
        Ok(())
    }

    #[cfg(not(target_os = "linux"))]
    fn install_linux_update(_download_dir: &PathBuf, _update_filename: &str) -> Result<()> {
        Err(anyhow::anyhow!("Linux update not supported on this platform"))
    }

    fn is_newer_version(new_version: &str, current_version: &str) -> bool {
        // Simple version comparison (major.minor.patch)
        let new_parts: Vec<u32> = new_version.split('.').map(|s| s.parse().unwrap_or(0)).collect();
        let current_parts: Vec<u32> = current_version.split('.').map(|s| s.parse().unwrap_or(0)).collect();
        
        for (new, current) in new_parts.iter().zip(current_parts.iter()) {
            if new > current {
                return true;
            } else if new < current {
                return false;
            }
        }
        
        // If all parts are equal, check if new version has more parts
        new_parts.len() > current_parts.len()
    }
}

// Compare versions
impl PartialOrd for UpdateInfo {
    fn partial_cmp(&self, other: &Self) -> Option<std::cmp::Ordering> {
        if UpdateManager::is_newer_version(&self.version, &other.version) {
            Some(std::cmp::Ordering::Greater)
        } else if UpdateManager::is_newer_version(&other.version, &self.version) {
            Some(std::cmp::Ordering::Less)
        } else {
            Some(std::cmp::Ordering::Equal)
        }
    }
}

impl PartialEq for UpdateInfo {
    fn eq(&self, other: &Self) -> bool {
        self.version == other.version
    }
}
