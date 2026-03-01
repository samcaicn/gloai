use std::env;
use std::path::Path;
use std::process::Command;

fn main() {
    // 构建 Tauri 应用
    tauri_build::build();
    
    // 下载 goclaw 二进制文件（失败时不中断构建）
    if let Err(e) = download_goclaw() {
        println!("cargo:warning=Failed to download goclaw: {}", e);
        println!("cargo:warning=Build will continue without goclaw");
    }
}

fn download_goclaw() -> Result<(), Box<dyn std::error::Error>> {
    let version = "v0.3.3"; // 使用最新版本
    let base_url = format!("https://github.com/smallnest/goclaw/releases/download/{}", version);
    
    // 确定目标操作系统和架构
    let target_os = env::var("CARGO_TARGET_OS").unwrap_or_else(|_| {
        // 如果 CARGO_TARGET_OS 未设置，使用当前系统的 OS
        std::env::consts::OS.to_string()
    });
    let target_arch = env::var("CARGO_TARGET_ARCH").unwrap_or_else(|_| {
        // 如果 CARGO_TARGET_ARCH 未设置，使用当前系统的架构
        std::env::consts::ARCH.to_string()
    });
    
    // 处理 universal 构建
    let architectures = if target_os == "macos" && env::var("CARGO_BUILD_TARGET").unwrap_or_default() == "universal-apple-darwin" {
        // 为 universal 构建下载两种架构的二进制文件
        vec!["aarch64", "x86_64"]
    } else {
        // 为普通构建只下载当前架构的二进制文件
        vec![target_arch.as_str()]
    };
    
    for arch in architectures {
        // 根据操作系统和架构确定文件名
        let (filename, executable_name) = match (target_os.as_str(), arch) {
            ("macos", "aarch64") => ("goclaw_0.3.3_darwin_arm64.tar.gz", "goclaw-arm64"),
            ("macos", "x86_64") => ("goclaw_0.3.3_darwin_amd64.tar.gz", "goclaw-amd64"),
            ("windows", "x86_64") => ("goclaw_0.3.3_windows_amd64.zip", "goclaw.exe"),
            ("linux", "x86_64") => ("goclaw_0.3.3_linux_amd64.tar.gz", "goclaw"),
            _ => return Err(format!("Unsupported target: {} {}", target_os, arch).into()),
        };
        
        let download_url = format!("{}/{}", base_url, filename);
        let output_dir = Path::new("target").join(env::var("PROFILE").unwrap()).join("goclaw");
        
        // 创建输出目录
        std::fs::create_dir_all(&output_dir)?;
        
        let archive_path = output_dir.join(&filename);
        let executable_path = output_dir.join(executable_name);
        
        // 如果可执行文件已经存在，跳过下载
        if executable_path.exists() {
            println!("cargo:rerun-if-changed=build.rs");
            continue;
        }
    
        // 下载文件
        println!("cargo:warning=Downloading goclaw from {}", download_url);
        println!("cargo:warning=Saving to: {}", archive_path.display());
        
        #[cfg(target_os = "windows")]
        {
            // Windows 使用 PowerShell 下载
            println!("cargo:warning=Using PowerShell to download");
            let status = Command::new("powershell")
                .args(&["-Command", &format!("Invoke-WebRequest -Uri '{}' -OutFile '{}' -Verbose", download_url, archive_path.display())])
                .status()?;
            
            println!("cargo:warning=Download status: {:?}", status);
            if !status.success() {
                return Err(format!("Failed to download goclaw: {:?}", status).into());
            }
            
            // 检查文件大小
            if let Ok(metadata) = std::fs::metadata(&archive_path) {
                println!("cargo:warning=Downloaded file size: {} bytes", metadata.len());
            } else {
                println!("cargo:warning=Failed to get file metadata");
            }
            
            // 解压 zip 文件
            println!("cargo:warning=Extracting zip file");
            let status = Command::new("powershell")
                .args(&["-Command", &format!("Expand-Archive -Path '{}' -DestinationPath '{}' -Verbose", archive_path.display(), output_dir.display())])
                .status()?;
            
            println!("cargo:warning=Extract status: {:?}", status);
            if !status.success() {
                return Err(format!("Failed to extract goclaw: {:?}", status).into());
            }
        }
        
        #[cfg(not(target_os = "windows"))]
        {
            // Unix-like 系统使用 curl 下载
            println!("cargo:warning=Using curl to download");
            let status = Command::new("curl")
                .args(&["-v", "-L", "-o", archive_path.to_str().unwrap(), &download_url])
                .status()?;
            
            println!("cargo:warning=Download status: {:?}", status);
            if !status.success() {
                return Err(format!("Failed to download goclaw: {:?}", status).into());
            }
            
            // 检查文件大小
            if let Ok(metadata) = std::fs::metadata(&archive_path) {
                println!("cargo:warning=Downloaded file size: {} bytes", metadata.len());
            } else {
                println!("cargo:warning=Failed to get file metadata");
            }
            
            // 解压 tar.gz 文件
            println!("cargo:warning=Extracting tar.gz file");
            let status = Command::new("tar")
                .args(&["-xzvf", archive_path.to_str().unwrap(), "-C", output_dir.to_str().unwrap()])
                .status()?;
            
            println!("cargo:warning=Extract status: {:?}", status);
            if !status.success() {
                return Err(format!("Failed to extract goclaw: {:?}", status).into());
            }
        }
        
        // 重命名解压后的文件
        let extracted_path = output_dir.join(if cfg!(target_os = "windows") { "goclaw.exe" } else { "goclaw" });
        if extracted_path.exists() {
            std::fs::rename(extracted_path, &executable_path)?;
        }
        
        // 设置可执行权限
        #[cfg(unix)]
        {
            use std::fs::Permissions;
            use std::os::unix::fs::PermissionsExt;
            
            let permissions = Permissions::from_mode(0o755);
            std::fs::set_permissions(&executable_path, permissions)?;
        }
        
        // 清理下载的归档文件
        std::fs::remove_file(archive_path)?;
    }
    
    // 为 universal 构建创建一个包装脚本，根据架构选择合适的二进制文件
    if target_os == "macos" && env::var("CARGO_BUILD_TARGET").unwrap_or_default() == "universal-apple-darwin" {
        let output_dir = Path::new("target").join(env::var("PROFILE").unwrap()).join("goclaw");
        let wrapper_path = output_dir.join("goclaw");
        
        let wrapper_content = r#"#!/bin/bash

# 检测当前架构
ARCH=$(uname -m)

# 根据架构选择合适的二进制文件
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
        
        // 设置包装脚本的可执行权限
        #[cfg(unix)]
        {
            use std::fs::Permissions;
            use std::os::unix::fs::PermissionsExt;
            
            let permissions = Permissions::from_mode(0o755);
            std::fs::set_permissions(&wrapper_path, permissions)?;
        }
    }
    
    // 复制 goclaw 目录到 Tauri 资源目录
    let output_dir = Path::new("target").join(env::var("PROFILE").unwrap()).join("goclaw");
    let tauri_resources_dir = Path::new("target").join(env::var("CARGO_BUILD_TARGET").unwrap_or_default()).join(env::var("PROFILE").unwrap()).join("resources");
    let tauri_goclaw_dir = tauri_resources_dir.join("goclaw");
    
    // 创建 Tauri 资源目录
    std::fs::create_dir_all(&tauri_resources_dir)?;
    
    // 复制 goclaw 目录
    if output_dir.exists() {
        // 如果目标目录存在，先删除
        if tauri_goclaw_dir.exists() {
            std::fs::remove_dir_all(&tauri_goclaw_dir)?;
        }
        // 复制目录
        copy_dir_all(&output_dir, &tauri_goclaw_dir)?;
    }
    
    println!("cargo:rerun-if-changed=build.rs");
    Ok(())
}

// 复制目录的辅助函数
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