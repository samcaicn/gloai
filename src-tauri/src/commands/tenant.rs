// Copyright (c) 2026 AIMarketing
//
// 租户信息命令 — tenant_get / tenant_register / tenant_info。
//
// 租户信息持久化到 app_data_dir 下的 tenant.json（参考 im_config.json
// 的 load/save 模式：原子写 .tmp + rename，文件级 tokio Mutex 互斥）。
// 与 im_config.json / tupai.db 一致，tenant.json 直接放在 app_data_dir
// 根目录（不另建子目录）。
//
// 前端契约（src/web-ui/.../infrastructure/api/tupai/tenant.ts）：
//   tenantGet()                              → TenantInfo { id, name, plan?, tags? }
//   tenantRegister(input: { name, token? })  → TenantInfo { id, name, plan?, tags? }
//   tenantInfo()                             → TenantInfo { id, name, plan?, tags? }
//
// tenant_info 区别于 tenant_get：后者只读本地 tenant.json；前者同时调
// MCP v2 `tenant.get` 拿服务端 tags（按 token 识别租户），合并后返回。
// tags 为空数组表示服务器尚未配置或当前未通过 device_token 鉴权。
//
// 说明：当前无云端 tenant 注册 API，tenant_register 仅做本地注册
// （生成 tenant_id + 落盘）。如后续接入云端，参考 device_register.rs
// 的 reqwest 用法（必须 .no_proxy()）。

#![allow(dead_code)]

use serde::{Deserialize, Serialize};
use std::time::Duration;
use tauri::{AppHandle, Manager};

const TENANT_FILE_NAME: &str = "tenant.json";
/// MCP tenant.get 请求超时（秒）。tag 是元数据查询，给 10s 已非常宽松。
const MCP_TENANT_GET_TIMEOUT_SECS: u64 = 10;

/// 文件级锁，保护 load_tenant / save_tenant 的底层文件 I/O 互斥，
/// 避免并发读/写 tenant.json 时读到半截 JSON 或写覆盖。参考
/// im_config.rs 的 CONFIG_FILE_LOCK 模式。
static TENANT_FILE_LOCK: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

/// 前端 TenantInfo 契约：{ id, name, plan?, tags?, website?, logoText? }。
/// tags / website / logoText 字段从 MCP `tenant.get` 拉取，按 device_token 识别租户；
/// 失败/未配置时为 None。
#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct TenantInfo {
    pub id: String,
    pub name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub plan: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tags: Option<Vec<String>>,
    /// 租户在 MCP server 端的官网 / 落地页地址；
    /// 前端左上角 tag 文本会渲染为跳转到该地址的链接。
    /// MCP 拉取失败 / 未配置时为 None，此时 tag 退化为纯文本。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub website: Option<String>,
    /// 租户在 MCP server 端配置的 logo 文字（品牌名/简称）。
    /// 前端左上角优先展示此字段；未配置时回退到 tags[0] 再回退到本地租户名。
    /// MCP 拉取失败 / 未配置时为 None。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub logo_text: Option<String>,
}

/// tenant_register 的入参契约：{ name, token? }。
/// token 当前未使用（无云端 API），保留以匹配前端入参形状。
#[derive(Deserialize, Debug)]
pub struct TenantRegisterInput {
    pub name: String,
    #[serde(default)]
    pub token: Option<String>,
}

fn tenant_path(app: &AppHandle) -> Result<std::path::PathBuf, String> {
    let dir = app
        .path()
        .app_data_dir()
        .map_err(|e| format!("app_data_dir failed: {}", e))?;
    Ok(dir.join(TENANT_FILE_NAME))
}

pub async fn load_tenant(app: &AppHandle) -> TenantInfo {
    let _guard = TENANT_FILE_LOCK.lock().await;
    let path = match tenant_path(app) {
        Ok(p) => p,
        Err(e) => {
            log::warn!("[tenant] cannot resolve tenant path: {}", e);
            return TenantInfo::default();
        }
    };
    match tokio::fs::read_to_string(&path).await {
        Ok(text) => serde_json::from_str(&text).unwrap_or_default(),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => TenantInfo::default(),
        Err(e) => {
            log::warn!("[tenant] read {} failed: {}", path.display(), e);
            TenantInfo::default()
        }
    }
}

async fn save_tenant(app: &AppHandle, info: &TenantInfo) -> Result<(), String> {
    let _guard = TENANT_FILE_LOCK.lock().await;
    let path = tenant_path(app)?;
    if let Some(parent) = path.parent() {
        tokio::fs::create_dir_all(parent)
            .await
            .map_err(|e| format!("create tenant dir failed: {}", e))?;
    }
    // 原子写：先写 .tmp 再 rename，防止写中途崩溃导致 tenant.json 损坏。
    let tmp = path.with_extension("json.tmp");
    let text = serde_json::to_string_pretty(info)
        .map_err(|e| format!("serialize tenant failed: {}", e))?;
    tokio::fs::write(&tmp, &text)
        .await
        .map_err(|e| format!("write tenant tmp failed: {}", e))?;
    tokio::fs::rename(&tmp, &path)
        .await
        .map_err(|e| format!("rename tenant tmp->final failed: {}", e))?;
    Ok(())
}

/// 调 MCP v2 `tenant.get` 拉取租户元数据（含 tags / website）。
///
/// 行为：
///   - 走 system-proxy 直连（与 device_register 一致：ai.tuptup.top 境内 IP）
///   - 不带 token 时不携带 Authorization（服务器按 ip 识别匿名租户，可能返回空 tags）
///   - 网络/解析失败 → 返回空元数据，不影响 tenant_info 整体返回
///
/// 返回 `TenantMetadata` 而非 Option：调用方拿到后可直接取 tags / website，
/// 字段缺失时为 None，无需处理 Result。
async fn fetch_tenant_metadata_via_mcp(token: Option<&str>) -> TenantMetadata {
    // 1) 读 localStorage 等价物：从 app_data_dir 拿 device_token。
    //    实际项目中 device_token 也保存在 app_data_dir/device.json 之类，
    //    此处不引入新的持久化层，简化为不带 token 的匿名调用。
    //    匿名 tenant.get 在服务器侧未配置时返回 data.tags=[]。
    let url = "https://ai.tuptup.top/api/v2/mcp";
    let body = serde_json::json!({
        "action": "tenant.get",
        "params": {},
    });

    let client = match reqwest::Client::builder()
        .no_proxy()
        .connect_timeout(Duration::from_secs(5))
        .timeout(Duration::from_secs(MCP_TENANT_GET_TIMEOUT_SECS))
        .user_agent(concat!("AIMarketing/", env!("CARGO_PKG_VERSION")))
        .build()
    {
        Ok(c) => c,
        Err(e) => {
            log::warn!("[tenant] build mcp client failed: {}", e);
            return TenantMetadata::default();
        }
    };

    let mut req = client
        .post(url)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .json(&body);
    if let Some(t) = token.filter(|s| !s.is_empty()) {
        req = req.bearer_auth(t);
    }

    let resp = match req.send().await {
        Ok(r) => r,
        Err(e) => {
            log::warn!("[tenant] mcp tenant.get send failed: {}", e);
            return TenantMetadata::default();
        }
    };

    let status = resp.status();
    let text = resp.text().await.unwrap_or_default();
    if !status.is_success() {
        log::warn!(
            "[tenant] mcp tenant.get http {}: {}",
            status.as_u16(),
            text.chars().take(200).collect::<String>()
        );
        return TenantMetadata::default();
    }

    let v: serde_json::Value = match serde_json::from_str(&text) {
        Ok(v) => v,
        Err(e) => {
            log::warn!("[tenant] mcp tenant.get parse failed: {}", e);
            return TenantMetadata::default();
        }
    };

    // MCP 信封: { ok: true, data: { tags: [...], website: "..." } } 或裸对象
    if !v.get("ok").and_then(|x| x.as_bool()).unwrap_or(true) {
        return TenantMetadata::default();
    }
    let data = v.get("data").unwrap_or(&v);

    let tags = data
        .get("tags")
        .and_then(|t| t.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|x| x.as_str().map(String::from))
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();

    // website_url: 服务器返回的租户官网地址（新格式）。
    // 兼容 website_url / website / url 三种字段名。
    let website_raw = data
        .get("website_url")
        .or_else(|| data.get("website"))
        .or_else(|| data.get("url"))
        .and_then(|x| x.as_str())
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty());
    let website = website_raw.and_then(|raw| {
        // 仅放行 http(s) 协议，避免 javascript: / data: 等被前端直接当链接渲染。
        if raw.starts_with("http://") || raw.starts_with("https://") {
            Some(raw)
        } else {
            log::warn!("[tenant] ignoring non-http(s) website url: {}", raw);
            None
        }
    });

    // logo_text: 服务器配置的租户 logo 文字（品牌名/简称）。
    // 兼容 logo_text / logoText / brand_name / name 四种字段名（服务器可能用任一）。
    let logo_text = data
        .get("logo_text")
        .or_else(|| data.get("logoText"))
        .or_else(|| data.get("brand_name"))
        .or_else(|| data.get("name"))
        .and_then(|x| x.as_str())
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty());

    // 可选字段：updated_at / updated_by（用于调试/日志，不暴露给前端）
    let _updated_at = data.get("updated_at").and_then(|x| x.as_f64());
    let _updated_by = data.get("updated_by").and_then(|x| x.as_str());
    if let (Some(ts), Some(by)) = (_updated_at, _updated_by) {
        log::debug!("[tenant] MCP tenant.get updated_at={} by={}", ts, by);
    }

    TenantMetadata { tags, website, logo_text }
}

/// 从 MCP `tenant.get` 拉回来的元数据子集。
/// 失败时所有字段为 None / 空 Vec，不影响 `tenant_info` 整体返回。
#[derive(Default)]
struct TenantMetadata {
    tags: Vec<String>,
    website: Option<String>,
    logo_text: Option<String>,
}

/// 获取当前租户信息。
///
/// 未注册时返回空 TenantInfo（id="" / name="" / plan=None），
/// 前端可据 id 是否为空判断是否已注册。
#[tauri::command]
pub async fn tenant_get(app: AppHandle) -> Result<TenantInfo, String> {
    Ok(load_tenant(&app).await)
}

/// 注册新租户（本地注册）。
///
/// 生成 tenant_id（UUID，"tenant_" 前缀，参考 add_memory 的 "mem_"
/// 前缀命名风格），plan 默认 "free"，落盘到 tenant.json 并返回
/// TenantInfo。name 为空时返回错误。token 当前未使用。
#[tauri::command]
pub async fn tenant_register(
    app: AppHandle,
    input: TenantRegisterInput,
) -> Result<TenantInfo, String> {
    let name = input.name.trim().to_string();
    if name.is_empty() {
        return Err("租户名称不能为空".to_string());
    }
    if input.token.is_some() {
        log::debug!(
            "[tenant] tenant_register 收到 token 但当前无云端 API，已忽略"
        );
    }
    let info = TenantInfo {
        id: format!("tenant_{}", uuid::Uuid::new_v4()),
        name,
        plan: Some("free".to_string()),
        tags: None,
        website: None,
        logo_text: None,
    };
    save_tenant(&app, &info).await?;
    Ok(info)
}

/// 合并租户信息：本地 tenant.json + MCP `tenant.get` 拉取的 tags / website。
///
/// 设计：tags / website 是元数据，每次调用都实时拉（不缓存）—— 管理员在
/// 控制台修改后，前端刷新立即看到新值。前端轮询频率由调用方控制，
/// 这里不引入自动刷新。
///
/// 行为：
///   - 始终调 MCP `tenant.get`（即使本地未注册）—— 服务器可按 device_token
///     识别租户并返回品牌名/网址，无需本地先注册
///   - MCP 失败/超时 → 返回本地信息（可能为空），tags=None / website=None
///   - 成功 → 返回本地信息 + tags + website（缺一即 None）
#[tauri::command]
pub async fn tenant_info(
    app: AppHandle,
    token: Option<String>,
) -> Result<TenantInfo, String> {
    let mut info = load_tenant(&app).await;
    // 即使本地未注册（id 空），也调 MCP 拉取品牌信息——
    // 服务器可按 device_token 识别租户，返回 logo_text / website
    let meta = fetch_tenant_metadata_via_mcp(token.as_deref()).await;
    info.tags = if meta.tags.is_empty() { None } else { Some(meta.tags) };
    info.website = meta.website;
    info.logo_text = meta.logo_text;
    Ok(info)
}
