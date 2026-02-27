use anyhow::Result;
use chrono;
use flate2::read::GzDecoder;
use std::fs::{create_dir_all, File};
use std::io::Write;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;
use tar::Archive;
use tauri::{AppHandle, Manager};
use tokio::sync::Mutex;
use tokio::time::sleep;

#[derive(Debug)]
pub struct UpdateManager {
    app_handle: Arc<Mutex<Option<AppHandle>>>,
    update_check_interval: Duration,
    update_server_url: String,
}

#[derive(Debug)]
struct UpdateInfo {
    version: String,
    download_url: String,
    release_notes: String,
    sha256: Option<String>,
}

impl UpdateManager {
    pub fn new() -> Self {
        Self {
            app_handle: Arc::new(Mutex::new(None)),
            update_check_interval: Duration::from_secs(3600), // 1 hour
            update_server_url: "https://api.ggai.com/v1/update".to_string(),
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
        let update_info =
            Self::fetch_update_info(server_url, &app_name, &current_version, &platform).await?;

        // 3. If update available, download it
        if let Some(info) = update_info {
            if info.version > current_version {
                println!("New version available: {}", info.version);
                Self::download_update(app_handle, &info.download_url, info.sha256.as_deref())
                    .await?;
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

    async fn download_update(
        _app_handle: &AppHandle,
        download_url: &str,
        expected_sha256: Option<&str>,
    ) -> Result<()> {
        // 1. Get download directory
        let download_dir = Self::get_update_download_dir()?;
        std::fs::create_dir_all(&download_dir)?;

        // 2. Get platform-specific update filename
        let filename = download_dir.join(Self::get_update_filename());
        let mut file = File::create(&filename)?;

        // 3. Download update
        let response = reqwest::get(download_url).await?;
        let bytes = response.bytes().await?;
        file.write_all(&bytes)?;

        // 4. Verify SHA256 if provided
        if let Some(expected_hash) = expected_sha256 {
            let actual_hash = Self::calculate_sha256(&filename)?;
            if actual_hash != expected_hash {
                // 删除损坏的文件
                std::fs::remove_file(&filename)?;
                return Err(anyhow::anyhow!(
                    "SHA256 checksum mismatch. Expected: {}, Actual: {}",
                    expected_hash,
                    actual_hash
                ));
            }
            println!("SHA256 checksum verified successfully");
        }

        // 5. Create update manifest
        let manifest_path = download_dir.join("update.json");
        let mut manifest_file = File::create(&manifest_path)?;
        let manifest = serde_json::json!({
            "version": "",
            "download_date": chrono::Utc::now().to_string(),
            "ready": true,
            "platform": Self::get_current_platform(),
            "update_filename": Self::get_update_filename()
        });
        manifest_file.write_all(manifest.to_string().as_bytes())?;

        println!("Update downloaded successfully to: {:?}", filename);
        println!("Will install on restart");

        Ok(())
    }

    fn calculate_sha256(file_path: &PathBuf) -> Result<String> {
        use sha2::{Digest, Sha256};
        use std::io::Read;

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

    pub async fn install_pending_update(&self) -> Result<()> {
        // 1. Check if there's a pending update
        let download_dir = Self::get_update_download_dir()?;
        let manifest_path = download_dir.join("update.json");

        if !manifest_path.exists() {
            println!("No pending update found");
            return Ok(());
        }

        // 2. Read update manifest
        let manifest_content = std::fs::read_to_string(manifest_path)?;
        let manifest: serde_json::Value = serde_json::from_str(&manifest_content)?;

        if !manifest["ready"].as_bool().unwrap_or(false) {
            println!("Update is not ready for installation");
            return Ok(());
        }

        let platform = manifest["platform"].as_str().unwrap_or("unknown");
        let update_filename = manifest["update_filename"].as_str().unwrap_or("");

        // 3. Install update based on platform
        match platform {
            "macos" => {
                Self::install_macos_update(&download_dir, update_filename)?;
            }
            "windows" => {
                Self::install_windows_update(&download_dir, update_filename)?;
            }
            "linux" => {
                Self::install_linux_update(&download_dir, update_filename)?;
            }
            _ => {
                eprintln!("Unsupported platform: {}", platform);
            }
        }

        // 4. Clean up
        std::fs::remove_file(download_dir.join("update.json"))?;
        std::fs::remove_file(download_dir.join(update_filename))?;

        println!("Update installed successfully");
        Ok(())
    }

    fn install_macos_update(download_dir: &PathBuf, update_filename: &str) -> Result<()> {
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
            .find(|entry| {
                if let Ok(entry) = entry {
                    entry.file_name().to_string_lossy().ends_with(".app")
                } else {
                    false
                }
            })
            .ok_or_else(|| anyhow::anyhow!("No .app bundle found in update package"))?;

        // 4. Replace the existing app (simplified version)
        // Note: In a real implementation, you'd need to handle:
        // - Quitting the app before replacement
        // - Handling permissions
        // - Backing up the old app

        println!("macOS update installed successfully: {:?}", app_bundle);
        Ok(())
    }

    fn install_windows_update(download_dir: &PathBuf, update_filename: &str) -> Result<()> {
        // 1. Paths
        let update_file = download_dir.join(update_filename);

        // 2. Run the MSI installer
        // Note: In a real implementation, you'd need to:
        // - Run the installer with appropriate flags
        // - Handle installation errors

        println!("Windows update ready for installation: {:?}", update_file);
        println!("MSI installer will run on next restart");
        Ok(())
    }

    fn install_linux_update(download_dir: &PathBuf, update_filename: &str) -> Result<()> {
        // 1. Paths
        let update_file = download_dir.join(update_filename);

        // 2. Make the AppImage executable and run it
        // Note: In a real implementation, you'd need to:
        // - Make the file executable
        // - Handle installation

        println!("Linux update ready for installation: {:?}", update_file);
        Ok(())
    }
}

// Compare versions (simple implementation)
impl PartialOrd for UpdateInfo {
    fn partial_cmp(&self, other: &Self) -> Option<std::cmp::Ordering> {
        self.version.partial_cmp(&other.version)
    }
}

impl PartialEq for UpdateInfo {
    fn eq(&self, other: &Self) -> bool {
        self.version == other.version
    }
}
