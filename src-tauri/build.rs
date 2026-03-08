use std::env;
use std::path::Path;
use std::process::Command;
use std::thread;
use std::time::Duration;

const MAX_RETRIES: u32 = 5;
const RETRY_DELAY_SECS: u64 = 5;

fn main() {
    // 下载 goclaw 二进制文件（带重试机制）
    match download_goclaw_with_retry() {
        Ok(_) => {
            println!("cargo:warning=goclaw downloaded successfully");
        }
        Err(e) => {
            println!("cargo:warning=Failed to download goclaw after {} retries: {}", MAX_RETRIES, e);
            println!("cargo:warning=Build will continue without goclaw");
        }
    }
    
    // 确保 resources/goclaw 目录存在（即使 goclaw 下载失败）
    let resources_dir = Path::new("resources");
    let goclaw_dir = resources_dir.join("goclaw");
    if !goclaw_dir.exists() {
        std::fs::create_dir_all(&goclaw_dir).unwrap_or_else(|e| {
            println!("cargo:warning=Failed to create goclaw directory: {}", e);
        });
        println!("cargo:warning=Created empty goclaw directory for build");
    }
    
    // 构建 Tauri 应用
    tauri_build::build();
}

fn download_goclaw_with_retry() -> Result<(), Box<dyn std::error::Error>> {
    let mut retry_count = 0;
    
    loop {
        match download_goclaw() {
            Ok(_) => return Ok(()),
            Err(e) => {
                retry_count += 1;
                if retry_count >= MAX_RETRIES {
                    return Err(e);
                }
                println!("cargo:warning=Download attempt {}/{} failed: {}", retry_count, MAX_RETRIES, e);
                println!("cargo:warning=Retrying in {} seconds...", RETRY_DELAY_SECS);
                thread::sleep(Duration::from_secs(RETRY_DELAY_SECS));
            }
        }
    }
}

fn download_goclaw() -> Result<(), Box<dyn std::error::Error>> {
    let version = "0.3.4";
    let base_url = format!("https://github.com/smallnest/goclaw/releases/download/v{}", version);
    
    let target_os = env::var("CARGO_TARGET_OS").unwrap_or_else(|_| {
        std::env::consts::OS.to_string()
    });
    let target_arch = env::var("CARGO_TARGET_ARCH").unwrap_or_else(|_| {
        std::env::consts::ARCH.to_string()
    });
    
    let architectures = if target_os == "macos" && env::var("CARGO_BUILD_TARGET").unwrap_or_default() == "universal-apple-darwin" {
        vec!["aarch64", "x86_64"]
    } else {
        vec![target_arch.as_str()]
    };
    
    for arch in architectures {
        let (filename, executable_name) = match (target_os.as_str(), arch) {
            ("macos", "aarch64") => ("goclaw_darwin_arm64.tar.gz".to_string(), "goclaw-arm64"),
            ("macos", "x86_64") => ("goclaw_darwin_amd64.tar.gz".to_string(), "goclaw-amd64"),
            ("windows", "x86_64") => ("goclaw_windows_amd64.zip".to_string(), "goclaw.exe"),
            ("linux", "x86_64") => ("goclaw_linux_amd64.tar.gz".to_string(), "goclaw"),
            _ => return Err(format!("Unsupported target: {} {}", target_os, arch).into()),
        };
        
        let download_url = format!("{}/{}", base_url, filename);
        let output_dir = Path::new("target").join(env::var("PROFILE").unwrap()).join("goclaw");
        
        std::fs::create_dir_all(&output_dir)?;
        
        let archive_path = output_dir.join(&filename);
        let executable_path = output_dir.join(executable_name);
        
        if executable_path.exists() {
            println!("cargo:warning=goclaw executable already exists, skipping download");
            println!("cargo:rerun-if-changed=build.rs");
            continue;
        }
    
        println!("cargo:warning=Downloading goclaw from {}", download_url);
        println!("cargo:warning=Saving to: {}", archive_path.display());
        
        // 统一使用下载和解压函数，不再使用条件编译
        download_and_extract(&download_url, &archive_path, &output_dir)?;
        
        let extracted_path = output_dir.join(if cfg!(target_os = "windows") { "goclaw.exe" } else { "goclaw" });
        if extracted_path.exists() {
            std::fs::rename(extracted_path, &executable_path)?;
        }
        
        #[cfg(unix)]
        {
            use std::fs::Permissions;
            use std::os::unix::fs::PermissionsExt;
            
            let permissions = Permissions::from_mode(0o755);
            std::fs::set_permissions(&executable_path, permissions)?;
        }
        
        if archive_path.exists() {
            std::fs::remove_file(&archive_path)?;
        }
    }
    
    if target_os == "macos" && env::var("CARGO_BUILD_TARGET").unwrap_or_default() == "universal-apple-darwin" {
        let output_dir = Path::new("target").join(env::var("PROFILE").unwrap()).join("goclaw");
        let wrapper_path = output_dir.join("goclaw");
        
        let wrapper_content = r#"#!/bin/bash
ARCH=$(uname -m)
if [ "$ARCH" = "arm64" ]; then
    exec "$(dirname "$0")/goclaw-arm64" "$@"
elif [ "$ARCH" = "x86_64" ]; then
    exec "$(dirname "$0")/goclaw-amd64" "$@"
else
    echo "Unsupported architecture: $ARCH"
    exit 1
fi
"#;
        
        std::fs::write(&wrapper_path, wrapper_content)?;
        
        #[cfg(unix)]
        {
            use std::fs::Permissions;
            use std::os::unix::fs::PermissionsExt;
            
            let permissions = Permissions::from_mode(0o755);
            std::fs::set_permissions(&wrapper_path, permissions)?;
        }
    }
    
    let output_dir = Path::new("target").join(env::var("PROFILE").unwrap()).join("goclaw");
    let resources_dir = Path::new("resources");
    let goclaw_dir = resources_dir.join("goclaw");
    
    std::fs::create_dir_all(&resources_dir)?;
    
    if output_dir.exists() {
        if goclaw_dir.exists() {
            std::fs::remove_dir_all(&goclaw_dir)?;
        }
        copy_dir_all(&output_dir, &goclaw_dir)?;
    }
    
    println!("cargo:rerun-if-changed=build.rs");
    Ok(())
}

fn download_and_extract(download_url: &str, archive_path: &Path, output_dir: &Path) -> Result<(), Box<dyn std::error::Error>> {
    println!("cargo:warning=Downloading goclaw...");
    
    // Windows 使用 PowerShell
    if cfg!(target_os = "windows") {
        println!("cargo:warning=Using PowerShell to download");
        
        let download_script = format!(
            "$ProgressPreference = 'SilentlyContinue'; \
             $maxAttempts = 3; \
             $attempt = 1; \
             while ($attempt -le $maxAttempts) {{ \
                 try {{ \
                     Invoke-WebRequest -Uri '{}' -OutFile '{}' -UseBasicParsing -TimeoutSec 120; \
                     if (Test-Path '{}') {{ exit 0; }} \
                 }} catch {{ \
                     Write-Host \"Attempt $attempt failed: $_\"; \
                     Start-Sleep -Seconds 5; \
                 }} \
                 $attempt++; \
             }} \
             exit 1",
            download_url,
            archive_path.display(),
            archive_path.display()
        );
        
        let status = Command::new("powershell")
            .args(&["-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", &download_script])
            .status()?;
        
        println!("cargo:warning=Download status: {:?}", status);
        if !status.success() {
            return Err(format!("Failed to download goclaw after retries: {:?}", status).into());
        }
        
        if let Ok(metadata) = std::fs::metadata(archive_path) {
            println!("cargo:warning=Downloaded file size: {} bytes", metadata.len());
            if metadata.len() < 1000 {
                return Err("Downloaded file is too small, likely corrupted".into());
            }
        } else {
            return Err("Failed to get file metadata".into());
        }
        
        println!("cargo:warning=Extracting zip file");
        let extract_script = format!(
            "$ProgressPreference = 'SilentlyContinue'; \
             Expand-Archive -Path '{}' -DestinationPath '{}' -Force",
            archive_path.display(),
            output_dir.display()
        );
        
        let status = Command::new("powershell")
            .args(&["-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", &extract_script])
            .status()?;
        
        println!("cargo:warning=Extract status: {:?}", status);
        if !status.success() {
            return Err(format!("Failed to extract goclaw: {:?}", status).into());
        }
    } else {
        // Unix 使用 curl
        println!("cargo:warning=Using curl to download");
        
        let status = Command::new("curl")
            .args(&[
                "-L",
                "-f",
                "--retry", "3",
                "--retry-delay", "5",
                "--connect-timeout", "30",
                "--max-time", "300",
                "-o", archive_path.to_str().unwrap(),
                download_url
            ])
            .status()?;
        
        println!("cargo:warning=Download status: {:?}", status);
        if !status.success() {
            return Err(format!("Failed to download goclaw: {:?}", status).into());
        }
        
        if let Ok(metadata) = std::fs::metadata(archive_path) {
            println!("cargo:warning=Downloaded file size: {} bytes", metadata.len());
            if metadata.len() < 1000 {
                return Err("Downloaded file is too small, likely corrupted".into());
            }
        } else {
            return Err("Failed to get file metadata".into());
        }
        
        println!("cargo:warning=Extracting tar.gz file");
        let status = Command::new("tar")
            .args(&["-xzf", archive_path.to_str().unwrap(), "-C", output_dir.to_str().unwrap()])
            .status()?;
        
        println!("cargo:warning=Extract status: {:?}", status);
        if !status.success() {
            return Err(format!("Failed to extract goclaw: {:?}", status).into());
        }
    }
    
    Ok(())
}

fn copy_dir_all(src: &Path, dst: &Path) -> Result<(), Box<dyn std::error::Error>> {
    std::fs::create_dir_all(dst)?;
    for entry in std::fs::read_dir(src)? {
        let entry = entry?;
        let path = entry.path();
        if path.is_dir() {
            copy_dir_all(&path, &dst.join(entry.file_name()))?;
        } else {
            std::fs::copy(&path, &dst.join(entry.file_name()))?;
        }
    }
    Ok(())
}
