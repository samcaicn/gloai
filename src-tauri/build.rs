use std::env;
use std::fs::File;
use std::io::Write;
use std::path::Path;
use std::process::Command;

fn main() {
    // 构建 Tauri 应用
    tauri_build::build();
    
    // 下载 goclaw 二进制文件
    download_goclaw().expect("Failed to download goclaw");
}

fn download_goclaw() -> Result<(), Box<dyn std::error::Error>> {
    let version = "v0.1.3";
    let base_url = format!("https://github.com/smallnest/goclaw/releases/download/{}", version);
    
    // 确定目标操作系统和架构
    let target_os = env::var("CARGO_TARGET_OS").expect("CARGO_TARGET_OS not set");
    let target_arch = env::var("CARGO_TARGET_ARCH").expect("CARGO_TARGET_ARCH not set");
    
    // 根据操作系统和架构确定文件名
    let (filename, executable_name) = match (target_os.as_str(), target_arch.as_str()) {
        ("macos", "aarch64") => ("goclaw_0.1.3_darwin_arm64.tar.gz", "goclaw"),
        ("macos", "x86_64") => ("goclaw_0.1.3_darwin_amd64.tar.gz", "goclaw"),
        ("windows", "x86_64") => ("goclaw_0.1.3_windows_amd64.zip", "goclaw.exe"),
        ("linux", "x86_64") => ("goclaw_0.1.3_linux_amd64.tar.gz", "goclaw"),
        _ => return Err(format!("Unsupported target: {} {}", target_os, target_arch).into()),
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
        return Ok(());
    }
    
    // 下载文件
    println!("Downloading goclaw from {}", download_url);
    
    #[cfg(target_os = "windows")]
    {
        // Windows 使用 PowerShell 下载
        let status = Command::new("powershell")
            .args(&["-Command", &format!("Invoke-WebRequest -Uri '{}' -OutFile '{}'", download_url, archive_path.display())])
            .status()?;
        
        if !status.success() {
            return Err("Failed to download goclaw".into());
        }
        
        // 解压 zip 文件
        let status = Command::new("powershell")
            .args(&["-Command", &format!("Expand-Archive -Path '{}' -DestinationPath '{}'", archive_path.display(), output_dir.display())])
            .status()?;
        
        if !status.success() {
            return Err("Failed to extract goclaw".into());
        }
    }
    
    #[cfg(not(target_os = "windows"))]
    {
        // Unix-like 系统使用 curl 下载
        let status = Command::new("curl")
            .args(&["-L", "-o", archive_path.to_str().unwrap(), &download_url])
            .status()?;
        
        if !status.success() {
            return Err("Failed to download goclaw".into());
        }
        
        // 解压 tar.gz 文件
        let status = Command::new("tar")
            .args(&["-xzf", archive_path.to_str().unwrap(), "-C", output_dir.to_str().unwrap()])
            .status()?;
        
        if !status.success() {
            return Err("Failed to extract goclaw".into());
        }
    }
    
    // 设置可执行权限
    #[cfg(unix)]
    {
        use std::fs::Permissions;
        use std::os::unix::fs::PermissionsExt;
        
        let mut permissions = Permissions::default();
        permissions.set_mode(0o755);
        std::fs::set_permissions(&executable_path, permissions)?;
    }
    
    // 清理下载的归档文件
    std::fs::remove_file(archive_path)?;
    
    println!("cargo:rerun-if-changed=build.rs");
    Ok(())
}