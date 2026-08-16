// Copyright (c) 2026 MeeJoy
//
// updater_client — 自建升级 HTTP 客户端,完全绕过 tauri-plugin-updater API。
//
// 原因: tauri-plugin-updater 内置 endpoint 无法动态注入 Bearer token,
// 导致 GET /api/update/* 始终返回 401 Unauthorized。
// 改用 MCP v2 (POST /api/v2/mcp action=update.check) 鉴权,
// 服务端从 COS 上的 latest.json 读取版本/下载链接/sha256 等信息返回。
// 本模块直接用 reqwest + std::process::Command 实现:
//   1. check_via_server  — POST /api/v2/mcp action=update.check 检查是否有新版本
//   2. download_to_local — 流式下载 + 边下边算 sha256 + emit 进度事件
//   3. install_silently  — 调 NSIS setup.exe /S /UPDATE=1 静默覆盖安装

use std::io::Write;
use std::path::Path;

use serde::Deserialize;
use tauri::{AppHandle, Emitter};
use sha2::{Digest, Sha256};

use crate::commands::legacy::apply_no_window;

/// 匹配 MCP `update.check` 响应中的 `data` 字段。
/// 服务端从 COS 上的 latest.json 读取,返回 snake_case 字段。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct ServerUpdateResponse {
    pub has_update: bool,
    pub current_version: String,
    pub latest_version: Option<String>,
    pub download_url: Option<String>,
    pub filename: Option<String>,
    pub size: Option<u64>,
    pub sha256: Option<String>,
    pub release_notes: Option<String>,
}

/// 编译期版本号 (Cargo.toml `version`)。
pub fn current_version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}

/// 从 Tauri 配置读品牌名 (productName),用于拼升级检查参数。
/// 多品牌场景: tupai (默认) / safeopc。读不到时兜底 "tupai"。
pub fn current_brand(app: &AppHandle) -> String {
    let config = app.config();
    config
        .product_name
        .clone()
        .unwrap_or_else(|| "tupai".to_string())
}

/// 通过 MCP v2 检查是否有新版本。
///
/// 端点: `POST https://ai.tuptup.top/api/v2/mcp`
/// Action: `update.check`
/// Params: `{ brand, target, arch, current_version }`
/// 鉴权: `Authorization: Bearer {device_token}`
///
/// - device_token 为空 → 返回 Err("missing device_token")
/// - 网络/HTTP 错误 → 返回 Err(message)
/// - 200 + has_update=false → 返回 Ok(None) (调用方据此判断)
/// - 200 + has_update=true → 返回 Ok(Some(response))
/// - 401/404 等 → 返回 Err(状态码+body)
pub async fn check_via_server(
    app: &AppHandle,
    device_token: &str,
) -> Result<Option<ServerUpdateResponse>, String> {
    if device_token.trim().is_empty() {
        return Err("missing device_token".to_string());
    }

    let brand = current_brand(app);
    let version = current_version();

    // 构建 MCP v2 请求参数
    let params = serde_json::json!({
        "brand": brand,
        "target": "windows-x86_64",
        "arch": "x86_64",
        "current_version": version,
    });

    // 通过 MCP v2 调用 update.check action
    let client = reqwest::Client::builder()
        .no_proxy()
        .connect_timeout(std::time::Duration::from_secs(5))
        .timeout(std::time::Duration::from_secs(15))
        .build()
        .map_err(|e| format!("build reqwest client failed: {}", e))?;

    let mcp_result = crate::commands::mcp_proxy::mcp_call_v2_inner(
        &client,
        "update.check",
        params,
        Some(device_token),
    )
    .await
    .map_err(|e| format!("update.check MCP call failed: {}", e))?;

    // 解析 MCP 响应: { ok: true, data: { has_update, ... } }
    let data = mcp_result
        .get("data")
        .ok_or_else(|| "update.check response missing 'data' field".to_string())?;

    let parsed: ServerUpdateResponse =
        serde_json::from_value(data.clone()).map_err(|e| {
            format!(
                "update.check parse response failed: {} (raw: {})",
                e,
                serde_json::to_string(&mcp_result).unwrap_or_default()
            )
        })?;

    if !parsed.has_update {
        return Ok(None);
    }
    Ok(Some(parsed))
}

/// 流式下载安装包到本地,边下载边算 sha256,完成后校验。
///
/// - 下载过程中 emit `bitfun-update-progress` 事件 `{downloaded, total}`
/// - expected_sha256 为空时跳过校验 (不推荐,但服务端可能未提供)
/// - sha256 不匹配 → 删除已下载文件 + 返回 Err
pub async fn download_to_local(
    url: &str,
    expected_sha256: Option<&str>,
    dest_path: &Path,
    app: &AppHandle,
) -> Result<(), String> {
    use futures_util::StreamExt;

    if url.is_empty() {
        return Err("download_url is empty".to_string());
    }

    // 确保父目录存在
    if let Some(parent) = dest_path.parent() {
        std::fs::create_dir_all(parent)
            .map_err(|e| format!("create download dir failed: {}", e))?;
    }

    let client = reqwest::Client::builder()
        .no_proxy()
        .connect_timeout(std::time::Duration::from_secs(10))
        .timeout(std::time::Duration::from_secs(600))
        .build()
        .map_err(|e| format!("build download client failed: {}", e))?;

    let resp = client
        .get(url)
        .send()
        .await
        .map_err(|e| format!("download request failed: {}", e))?;

    if !resp.status().is_success() {
        return Err(format!("download HTTP {}", resp.status().as_u16()));
    }

    let total = resp.content_length();
    let mut stream = resp.bytes_stream();

    let mut file = std::fs::File::create(dest_path)
        .map_err(|e| format!("create local file failed: {}", e))?;
    let mut hasher = Sha256::new();
    let mut downloaded: u64 = 0;
    let mut last_emit: u64 = 0;

    while let Some(chunk_result) = stream.next().await {
        let chunk = chunk_result
            .map_err(|e| format!("download stream chunk failed: {}", e))?;
        use std::io::Write;
        file.write_all(&chunk)
            .map_err(|e| format!("write chunk to file failed: {}", e))?;
        hasher.update(&chunk);
        downloaded += chunk.len() as u64;

        // 每 1MB emit 一次进度事件,避免高频事件淹没前端
        if downloaded.saturating_sub(last_emit) >= 1_048_576 || total == Some(downloaded) {
            let _ = app.emit(
                "bitfun-update-progress",
                serde_json::json!({
                    "downloaded": downloaded,
                    "total": total,
                }),
            );
            last_emit = downloaded;
        }
    }

    // 最终 emit 一次确保前端拿到 100%
    let _ = app.emit(
        "bitfun-update-progress",
        serde_json::json!({
            "downloaded": downloaded,
            "total": total.unwrap_or(downloaded),
        }),
    );

    file.flush()
        .map_err(|e| format!("flush download file failed: {}", e))?;
    drop(file);

    // sha256 校验
    if let Some(expected) = expected_sha256 {
        let expected = expected.trim().to_lowercase();
        if !expected.is_empty() {
            let actual = format!("{:x}", hasher.finalize());
            if actual != expected {
                let _ = std::fs::remove_file(dest_path);
                return Err(format!(
                    "sha256 mismatch: expected {}, got {}",
                    expected, actual
                ));
            }
        }
    }

    Ok(())
}

/// 调 NSIS 安装包静默安装: `setup.exe /S /UPDATE=1`
///
/// `/S` — NSIS 静默安装 (无 UI)
/// `/UPDATE=1` — Tauri NSIS 模板识别的更新标记,跳过安装向导直接覆盖
///
/// spawn 后立即返回 Ok,安装程序会独立运行。调用方负责随后 `app.restart()`。
/// Windows 下用 `apply_no_window` 防止黑窗闪现。
pub fn install_silently(setup_exe_path: &Path) -> Result<(), String> {
    if !setup_exe_path.exists() {
        return Err(format!(
            "setup exe not found: {}",
            setup_exe_path.display()
        ));
    }

    let mut cmd = std::process::Command::new(setup_exe_path);
    cmd.args(["/S", "/UPDATE=1"]);
    apply_no_window(&mut cmd);

    cmd.spawn()
        .map_err(|e| format!("spawn NSIS installer failed: {}", e))?;

    Ok(())
}
