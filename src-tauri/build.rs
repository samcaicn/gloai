use std::env;
use std::path::Path;
use std::process::Command;
use std::thread;
use std::time::Duration;

const MAX_RETRIES: u32 = 5;
const RETRY_DELAY_SECS: u64 = 5;

fn main() {
    let target_os = env::var("CARGO_CFG_TARGET_OS")
        .unwrap_or_else(|_| std::env::consts::OS.to_string());
    
    // Android 平台特殊处理
    if target_os == "android" {
        println!("cargo:warning=Android target detected");
        println!("cargo:warning=Android will use embedded goclaw library or remote service");
        
        // 创建空目录避免构建错误
        let resources_dir = Path::new("resources");
        let goclaw_dir = resources_dir.join("goclaw");
        if !goclaw_dir.exists() {
            std::fs::create_dir_all(&goclaw_dir).unwrap_or_else(|e| {
                println!("cargo:warning=Failed to create goclaw directory: {}", e);
            });
        }
        
        tauri_build::build();
        return;
    }
    
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
    
    // 使用 CARGO_CFG_TARGET_OS 获取目标操作系统（交叉编译时正确）
    let target_os = env::var("CARGO_CFG_TARGET_OS")
        .unwrap_or_else(|_| std::env::consts::OS.to_string());
    
    // 使用 CARGO_CFG_TARGET_ARCH 获取目标架构
    let target_arch = env::var("CARGO_CFG_TARGET_ARCH")
        .unwrap_or_else(|_| std::env::consts::ARCH.to_string());
    
    println!("cargo:warning=Target OS: {}, Target Arch: {}", target_os, target_arch);
    
    // Android 平台跳过下载
    if target_os == "android" {
        println!("cargo:warning=Android target - skipping goclaw binary download");
        println!("cargo:warning=Android will use embedded goclaw library");
        return Ok(());
    }
    
    // 获取构建目标（用于检测 universal binary）
    let build_target = env::var("CARGO_BUILD_TARGET").unwrap_or_default();
    
    let architectures = if target_os == "macos" && build_target == "universal-apple-darwin" {
        vec!["aarch64", "x86_64"]
    } else {
        vec![target_arch.as_str()]
    };
    
    for arch in architectures {
        let (filename, executable_name) = match (target_os.as_str(), arch) {
            ("macos", "aarch64") => ("goclaw_darwin_arm64.tar.gz".to_string(), "goclaw-arm64"),
            ("macos", "x86_64") => ("goclaw_darwin_amd64.tar.gz".to_string(), "goclaw-amd64"),
            ("windows", "x86_64") | ("windows", "x86") => {
                ("goclaw_windows_amd64.zip".to_string(), "goclaw.exe")
            }
            ("linux", "x86_64") => ("goclaw_linux_amd64.tar.gz".to_string(), "goclaw"),
            ("linux", "aarch64") => ("goclaw_linux_arm64.tar.gz".to_string(), "goclaw"),
            _ => return Err(format!("Unsupported target: {} {}", target_os, arch).into()),
        };
        
        let download_url = format!("{}/{}", base_url, filename);
        let profile = env::var("PROFILE").unwrap_or_else(|_| "release".to_string());
        
        // 对于交叉编译，使用正确的目标目录
        let target_dir = if let Ok(target) = env::var("CARGO_BUILD_TARGET") {
            if !target.is_empty() {
                Path::new("target").join(target).join(&profile).join("goclaw")
            } else {
                Path::new("target").join(&profile).join("goclaw")
            }
        } else {
            Path::new("target").join(&profile).join("goclaw")
        };
        
        std::fs::create_dir_all(&target_dir)?;
        
        let archive_path = target_dir.join(&filename);
        let executable_path = target_dir.join(executable_name);
        
        if executable_path.exists() {
            println!("cargo:warning=goclaw executable already exists at {}, skipping download", executable_path.display());
            println!("cargo:rerun-if-changed=build.rs");
            continue;
        }
    
        println!("cargo:warning=Downloading goclaw from {}", download_url);
        println!("cargo:warning=Saving to: {}", archive_path.display());
        
        // 根据目标操作系统选择下载方式
        if target_os == "windows" {
            download_and_extract_windows(&download_url, &archive_path, &target_dir)?;
        } else {
            download_and_extract_unix(&download_url, &archive_path, &target_dir)?;
        }
        
        // 重命名解压后的文件
        let extracted_name = if target_os == "windows" { "goclaw.exe" } else { "goclaw" };
        let extracted_path = target_dir.join(extracted_name);
        if extracted_path.exists() {
            std::fs::rename(&extracted_path, &executable_path)?;
            println!("cargo:warning=Renamed {} to {}", extracted_path.display(), executable_path.display());
        } else {
            println!("cargo:warning=Extracted file not found at {}", extracted_path.display());
            // 列出目录内容帮助调试
            if let Ok(entries) = std::fs::read_dir(&target_dir) {
                println!("cargo:warning=Directory contents:");
                for entry in entries {
                    if let Ok(entry) = entry {
                        println!("cargo:warning=  - {}", entry.file_name().to_string_lossy());
                    }
                }
            }
        }
        
        // 设置可执行权限（仅 Unix）
        #[cfg(unix)]
        {
            use std::fs::Permissions;
            use std::os::unix::fs::PermissionsExt;
            
            if executable_path.exists() {
                let permissions = Permissions::from_mode(0o755);
                std::fs::set_permissions(&executable_path, permissions)?;
            }
        }
        
        // 清理压缩包
        if archive_path.exists() {
            std::fs::remove_file(&archive_path)?;
        }
    }
    
    // 创建 macOS universal binary wrapper
    if target_os == "macos" && build_target == "universal-apple-darwin" {
        let profile = env::var("PROFILE").unwrap_or_else(|_| "release".to_string());
        let target_dir = Path::new("target").join(&profile).join("goclaw");
        let wrapper_path = target_dir.join("goclaw");
        
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
    
    // 复制到 resources 目录
    let profile = env::var("PROFILE").unwrap_or_else(|_| "release".to_string());
    let target_dir = if let Ok(target) = env::var("CARGO_BUILD_TARGET") {
        if !target.is_empty() {
            Path::new("target").join(target).join(&profile).join("goclaw")
        } else {
            Path::new("target").join(&profile).join("goclaw")
        }
    } else {
        Path::new("target").join(&profile).join("goclaw")
    };
    
    let resources_dir = Path::new("resources");
    let goclaw_dir = resources_dir.join("goclaw");
    
    std::fs::create_dir_all(&resources_dir)?;
    
    if target_dir.exists() {
        if goclaw_dir.exists() {
            std::fs::remove_dir_all(&goclaw_dir)?;
        }
        copy_dir_all(&target_dir, &goclaw_dir)?;
        println!("cargo:warning=Copied goclaw to {}", goclaw_dir.display());
        
        // 列出 resources/goclaw 目录内容
        if let Ok(entries) = std::fs::read_dir(&goclaw_dir) {
            println!("cargo:warning=resources/goclaw contents:");
            for entry in entries {
                if let Ok(entry) = entry {
                    println!("cargo:warning=  - {}", entry.file_name().to_string_lossy());
                }
            }
        }
    } else {
        println!("cargo:warning=target_dir does not exist: {}", target_dir.display());
    }
    
    println!("cargo:rerun-if-changed=build.rs");
    Ok(())
}

fn download_and_extract_windows(download_url: &str, archive_path: &Path, output_dir: &Path) -> Result<(), Box<dyn std::error::Error>> {
    println!("cargo:warning=Using PowerShell to download for Windows target");
    
    // 使用 PowerShell 下载
    let download_script = format!(
        "$ProgressPreference = 'SilentlyContinue'; \
         $maxAttempts = 3; \
         $attempt = 1; \
         while ($attempt -le $maxAttempts) {{ \
             try {{ \
                 Invoke-WebRequest -Uri '{}' -OutFile '{}' -UseBasicParsing -TimeoutSec 300; \
                 if (Test-Path '{}') {{ Write-Host 'Download successful'; exit 0; }} \
             }} catch {{ \
                 Write-Host \"Attempt $attempt failed: $_\"; \
                 Start-Sleep -Seconds 5; \
             }} \
             $attempt++; \
         }} \
         Write-Host 'All download attempts failed'; \
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
    
    // 验证文件大小
    if let Ok(metadata) = std::fs::metadata(archive_path) {
        println!("cargo:warning=Downloaded file size: {} bytes", metadata.len());
        if metadata.len() < 1000 {
            return Err("Downloaded file is too small, likely corrupted".into());
        }
    } else {
        return Err("Failed to get file metadata".into());
    }
    
    // 解压 ZIP 文件
    println!("cargo:warning=Extracting zip file");
    let extract_script = format!(
        "$ProgressPreference = 'SilentlyContinue'; \
         Expand-Archive -Path '{}' -DestinationPath '{}' -Force; \
         Write-Host 'Extraction complete'",
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
    
    Ok(())
}

fn download_and_extract_unix(download_url: &str, archive_path: &Path, output_dir: &Path) -> Result<(), Box<dyn std::error::Error>> {
    println!("cargo:warning=Using curl to download for Unix target");
    
    // 使用 curl 下载
    let status = Command::new("curl")
        .args(&[
            "-L",                           // 跟随重定向
            "-f",                           // 失败时返回错误码
            "--retry", "3",                 // 重试次数
            "--retry-delay", "5",           // 重试延迟
            "--connect-timeout", "30",      // 连接超时
            "--max-time", "300",            // 最大下载时间
            "-o", archive_path.to_str().unwrap(),
            download_url
        ])
        .status()?;
    
    println!("cargo:warning=Download status: {:?}", status);
    if !status.success() {
        return Err(format!("Failed to download goclaw: {:?}", status).into());
    }
    
    // 验证文件大小
    if let Ok(metadata) = std::fs::metadata(archive_path) {
        println!("cargo:warning=Downloaded file size: {} bytes", metadata.len());
        if metadata.len() < 1000 {
            return Err("Downloaded file is too small, likely corrupted".into());
        }
    } else {
        return Err("Failed to get file metadata".into());
    }
    
    // 解压 tar.gz 文件
    println!("cargo:warning=Extracting tar.gz file");
    let status = Command::new("tar")
        .args(&["-xzf", archive_path.to_str().unwrap(), "-C", output_dir.to_str().unwrap()])
        .status()?;
    
    println!("cargo:warning=Extract status: {:?}", status);
    if !status.success() {
        return Err(format!("Failed to extract goclaw: {:?}", status).into());
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
