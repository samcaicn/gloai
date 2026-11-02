// 自动更新模块 —— 移植 weauto 成熟模式（updater.py 的 Rust 版）：
//   1. 检查：GET {update_base}/updates/version.json（version/notes/exe_url/sig_url）
//   2. 准备：下载 exe + .sig（3 次重试）→ Ed25519 验签（对 exe 的 SHA-256）→
//      暂存到 temp_update/staged_update/，写 pending.json（不在当前进程内替换）
//   3. 应用：下次启动时发现 pending → 二次验签 → 生成 apply_update.bat
//      （等当前进程退出 → 覆盖 exe+sig → 清 pending → 重启）→ spawn 后本进程退出。
// 防投毒：升级包必须带与 exe 同名 .sig；公钥编译期嵌入，私钥只在 CI Secret / 本地
// tools/build_keys.json（已 gitignore）。

use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::io::Write;
use std::path::PathBuf;
use std::time::Duration;
use tauri::Manager;

/// 升级验签公钥（Ed25519，64 hex 字符 = 32 字节）。配对私钥见 tools/build_keys.json。
pub const UPDATER_VERIFY_KEY: &str =
    "51a16ab8efa4ca55173e739140150ca7daf70d95f0122e7925a7500ba4163393";

const HTTP_TIMEOUT: Duration = Duration::from_secs(120);

/// 国内访问 GitHub raw / release 常不稳定，沿用 weauto 的 gh-proxy 镜像回退：
/// 主源失败时逐个尝试镜像前缀拼接（与 weauto `get_proxy_url` 行为一致）。
const MIRROR_PREFIXES: &[&str] = &[
    "https://gh-proxy.com/",
    "https://gh.noki.eu.org/",
    "https://ghproxy.net/",
    "https://ghproxy.cc/",
    "https://ghfast.top/",
];

/// 返回 [原URL, 镜像1拼接, 镜像2拼接, ...]，供清单拉取 / 二进制下载逐级回退。
fn with_mirrors(url: &str) -> Vec<String> {
    let mut v = vec![url.to_string()];
    for m in MIRROR_PREFIXES {
        if !url.starts_with(m) {
            v.push(format!("{}{}", m, url));
        }
    }
    v
}

fn client() -> reqwest::Client {
    reqwest::Client::builder()
        .timeout(HTTP_TIMEOUT)
        .user_agent("bbt-zb-tauri-updater/1.0")
        .build()
        .unwrap_or_else(|_| reqwest::Client::new())
}

fn manifest_url(base: &str) -> String {
    format!("{}/updates/version.json", base.trim_end_matches('/'))
}

#[derive(serde::Deserialize)]
struct Manifest {
    version: String,
    #[serde(default)]
    notes: String,
    exe_url: String,
    sig_url: String,
}

/// 版本号比较（按 '.' 分段逐位比，长度不足补 0）。
fn version_ge(a: &str, b: &str) -> bool {
    let pa: Vec<u64> = a.split('.').filter_map(|x| x.trim().parse().ok()).collect();
    let pb: Vec<u64> = b.split('.').filter_map(|x| x.trim().parse().ok()).collect();
    for i in 0..pa.len().max(pb.len()) {
        let x = pa.get(i).copied().unwrap_or(0);
        let y = pb.get(i).copied().unwrap_or(0);
        if x != y {
            return x > y;
        }
    }
    true
}

/// 检查更新：返回 {update_available, current, latest, notes, exe_url}。
pub async fn check_update(base_url: &str, current_version: &str) -> Result<Value, String> {
    if base_url.trim().is_empty() {
        return Err("未配置升级源地址".into());
    }
    let candidates = with_mirrors(&manifest_url(base_url));
    let mut last_err = format!("所有升级源（含镜像）均不可用");
    let m: Manifest = 'outer: {
        for url in &candidates {
            match client().get(url).send().await {
                Ok(r) => match r.error_for_status() {
                    Ok(r) => match r.json::<Manifest>().await {
                        Ok(m) => break 'outer m,
                        Err(e) => last_err = format!("版本清单格式错误: {}", e),
                    },
                    Err(e) => last_err = format!("获取版本清单失败: {}", e),
                },
                Err(e) => last_err = format!("获取版本清单失败: {}", e),
            }
        }
        return Err(last_err);
    };
    let available = version_ge(&m.version, current_version) && m.version != current_version;
    Ok(json!({
        "update_available": available,
        "current": current_version,
        "latest": m.version,
        "notes": m.notes,
        "exe_url": m.exe_url,
    }))
}

fn update_dirs(app: &tauri::AppHandle) -> Result<(PathBuf, PathBuf, PathBuf), String> {
    let dir = app
        .path()
        .app_config_dir()
        .map_err(|_| "无法定位配置目录".to_string())?;
    let temp = dir.join("temp_update");
    let staged = temp.join("staged_update");
    Ok((temp, staged, dir))
}

/// 下载到文件（3 次重试，流式写盘）。
async fn download_to(url: &str, dest: &PathBuf) -> Result<u64, String> {    let mut last_err = String::new();
    for attempt in 0..3 {
        match try_download(url, dest).await {
            Ok(n) => return Ok(n),
            Err(e) => {
                last_err = e;
                if attempt < 2 {
                    tokio::time::sleep(Duration::from_secs(3 * (attempt as u64 + 1))).await;
                }
            }
        }
    }
    Err(last_err)
}

/// 下载（3 次重试）× 镜像回退：主 URL 不通则依次尝试 gh-proxy 镜像拼接。
async fn download_via(url: &str, dest: &PathBuf) -> Result<u64, String> {
    let mut last = format!("所有下载源（含镜像）均失败");
    for u in with_mirrors(url) {
        match download_to(&u, dest).await {
            Ok(n) => return Ok(n),
            Err(e) => last = e,
        }
    }
    Err(last)
}

async fn try_download(url: &str, dest: &PathBuf) -> Result<u64, String> {
    let resp = client()
        .get(url)
        .send()
        .await
        .map_err(|e| format!("下载失败: {}", e))?
        .error_for_status()
        .map_err(|e| format!("下载失败: {}", e))?;
    let mut file = std::fs::File::create(dest).map_err(|e| format!("写盘失败: {}", e))?;
    let mut n: u64 = 0;
    let mut stream = resp.bytes_stream();
    use futures_util::StreamExt;
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|e| format!("下载中断: {}", e))?;
        file.write_all(&chunk).map_err(|e| format!("写盘失败: {}", e))?;
        n += chunk.len() as u64;
    }
    file.flush().ok();
    Ok(n)
}

/// 验签：Ed25519(sig) 覆盖 exe 的 SHA-256 摘要。
fn verify_signature(exe_path: &PathBuf, sig_path: &PathBuf) -> Result<(), String> {
    let exe = std::fs::read(exe_path).map_err(|e| format!("读 exe 失败: {}", e))?;
    let sig = std::fs::read(sig_path).map_err(|e| format!("读 sig 失败: {}", e))?;
    let key_hex = UPDATER_VERIFY_KEY;
    let mut key_bytes = [0u8; 32];
    for i in 0..32 {
        key_bytes[i] = u8::from_str_radix(&key_hex[i * 2..i * 2 + 2], 16)
            .map_err(|_| "内嵌公钥格式错误".to_string())?;
    }
    let vk = VerifyingKey::from_bytes(&key_bytes).map_err(|e| format!("公钥解析失败: {}", e))?;
    let digest: [u8; 32] = Sha256::digest(&exe).into();
    let sig_arr: [u8; 64] = sig
        .as_slice()
        .try_into()
        .map_err(|_| "签名文件长度异常（应为 64 字节）".to_string())?;
    let signature = Signature::from_bytes(&sig_arr);
    vk.verify(&digest, &signature)
        .map_err(|_| "验签失败：升级包签名与内嵌公钥不匹配，已放弃安装".to_string())
}

/// 下载 + 验签 + 暂存（不替换当前进程）。返回给前端的结果 JSON。
pub async fn prepare_update(base_url: &str, app: &tauri::AppHandle) -> Result<Value, String> {
    if base_url.trim().is_empty() {
        return Err("未配置升级源地址".into());
    }
    let candidates = with_mirrors(&manifest_url(base_url));
    let mut last_err = format!("所有升级源（含镜像）均不可用");
    let m: Manifest = 'outer: {
        for url in &candidates {
            match client().get(url).send().await {
                Ok(r) => match r.error_for_status() {
                    Ok(r) => match r.json::<Manifest>().await {
                        Ok(m) => break 'outer m,
                        Err(e) => last_err = format!("版本清单格式错误: {}", e),
                    },
                    Err(e) => last_err = format!("获取版本清单失败: {}", e),
                },
                Err(e) => last_err = format!("获取版本清单失败: {}", e),
            }
        }
        return Err(last_err);
    };

    let (_temp, staged, _cfg) = update_dirs(app)?;
    std::fs::create_dir_all(&staged).map_err(|e| format!("创建暂存目录失败: {}", e))?;
    let exe_name = current_exe_name();
    let staged_exe = staged.join(&exe_name);
    let staged_sig = staged.join(format!("{}.sig", exe_name));

    download_via(&m.exe_url, &staged_exe).await?;
    download_via(&m.sig_url, &staged_sig).await?;
    verify_signature(&staged_exe, &staged_sig)?;

    // 写「待应用」标记
    let pending = json!({
        "staged_exe": staged_exe.to_string_lossy(),
        "staged_sig": staged_sig.to_string_lossy(),
        "version": m.version,
        "staged_at": std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0),
    });
    let (_temp, _staged, cfg) = update_dirs(app)?;
    std::fs::write(cfg.join("update_pending.json"), pending.to_string())
        .map_err(|e| format!("写待应用标记失败: {}", e))?;

    Ok(json!({
        "success": true,
        "version": m.version,
        "message": format!("新版本 {} 已下载并通过验签，重启后自动替换并升级", m.version),
    }))
}

fn current_exe_name() -> String {
    std::env::current_exe()
        .ok()
        .and_then(|p| p.file_name().map(|s| s.to_string_lossy().to_string()))
        .unwrap_or_else(|| "bbt-zb-tauri.exe".into())
}

/// 启动时调用：存在待应用更新 → 二次验签 → 写替换 bat → spawn → 返回 true（调用方应退出）。
pub fn apply_pending_update(app: &tauri::AppHandle) -> Result<bool, String> {
    let (_temp, _staged, cfg) = update_dirs(app)?;
    let pending_path = cfg.join("update_pending.json");
    if !pending_path.exists() {
        return Ok(false);
    }
    let pending: Value = serde_json::from_str(
        &std::fs::read_to_string(&pending_path).map_err(|e| format!("读待应用标记失败: {}", e))?,
    )
    .map_err(|e| format!("待应用标记格式错误: {}", e))?;

    let staged_exe = PathBuf::from(
        pending
            .get("staged_exe")
            .and_then(|v| v.as_str())
            .ok_or("标记缺少 staged_exe")?,
    );
    let staged_sig = PathBuf::from(
        pending
            .get("staged_sig")
            .and_then(|v| v.as_str())
            .ok_or("标记缺少 staged_sig")?,
    );
    if !staged_exe.exists() || !staged_sig.exists() {
        let _ = std::fs::remove_file(&pending_path);
        return Ok(false); // 暂存丢失，静默跳过
    }
    // 安装前二次验签（防暂存区被篡改）
    verify_signature(&staged_exe, &staged_sig)?;

    let current = std::env::current_exe().map_err(|e| format!("取当前进程路径失败: {}", e))?;
    let temp = current
        .parent()
        .map(|p| p.to_path_buf())
        .unwrap_or_else(|| cfg.clone());
    let bat = temp.join("apply_update.bat");
    let logf = temp.join("apply_update.log");
    let pid = std::process::id();
    let cur_sig = current.with_extension("exe.sig");

    let script = format!(
        "@echo off\r\nchcp 65001 >nul\r\nset LOG={log}\r\nset CURRENT={cur}\r\nset STAGED={se}\r\nset STAGEDSIG={ss}\r\nset CURSIG={cs}\r\nset PENDING={pd}\r\necho [%date% %time%] apply update start >> \"%LOG%\"\r\n:waitloop\r\ntasklist /FI \"PID eq {pid}\" | find \"{pid}\" >nul 2>&1\r\nif not errorlevel 1 (\r\n  timeout /t 1 /nobreak >nul\r\n  goto waitloop\r\n)\r\ncopy /y \"%STAGED%\" \"%CURRENT%\" >> \"%LOG%\" 2>&1\r\ncopy /y \"%STAGEDSIG%\" \"%CURSIG%\" >> \"%LOG%\" 2>&1\r\nif exist \"%PENDING%\" del /f /q \"%PENDING%\"\r\necho [%date% %time%] replaced, restarting >> \"%LOG%\"\r\nstart \"\" \"%CURRENT%\"\r\n",
        log = logf.to_string_lossy(),
        cur = current.to_string_lossy(),
        se = staged_exe.to_string_lossy(),
        ss = staged_sig.to_string_lossy(),
        cs = cur_sig.to_string_lossy(),
        pd = pending_path.to_string_lossy(),
        pid = pid,
    );
    std::fs::write(&bat, script).map_err(|e| format!("写替换脚本失败: {}", e))?;

    // 脱离式拉起 bat（bat 会等本进程退出后替换并重启）
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        std::process::Command::new("cmd")
            .args(["/c", &bat.to_string_lossy()])
            .creation_flags(0x00000008 | 0x00000200) // DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP
            .spawn()
            .map_err(|e| format!("拉起替换脚本失败: {}", e))?;
    }
    #[cfg(not(windows))]
    {
        std::process::Command::new("cmd")
            .args(["/c", &bat.to_string_lossy()])
            .spawn()
            .map_err(|e| format!("拉起替换脚本失败: {}", e))?;
    }
    Ok(true)
}
