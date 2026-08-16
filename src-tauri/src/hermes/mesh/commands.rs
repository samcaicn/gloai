// Copyright (c) 2026 AIMarketing
//
// mesh Tauri IPC 命令。前端通过 invoke('mesh_*') 调用。
//
// 注册：lib.rs 中 app.manage(MeshHandle::default()) + invoke_handler 添加 generate_handler! 列表。
// 身份自动化：tenant_id / device_fingerprint 由后端从既有设备注册状态自动派生
// （tenant.json + hardware_id 经 SHA-256，与服务器设备身份一致），前端无需也不应传入。
//
// 当 `mesh` feature 未启用时，所有命令返回 Err("mesh feature not enabled")。
// 这使得 generate_handler! 列表可以无条件引用这些路径，而 iroh/iroh-gossip
// 等重依赖不会被编译进二进制。

use tauri::{AppHandle, State};

use super::ainl::ClientInfo;
use super::{MeshHandle, MeshStatus};

#[derive(Debug, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MeshCreateResult {
    pub status: MeshStatus,
    pub ticket: String,
}

// ── Real command implementations (mesh feature ON) ───────────────────

#[cfg(feature = "mesh")]
mod mesh_impl {
    use super::*;
    use sha2::{Digest, Sha256};
    use tauri::Emitter;
    use super::super::transport::now_ms;

    fn maybe_emit_firewall_warning(app: &AppHandle, err_str: &str) {
        let lower = err_str.to_ascii_lowercase();
        let looks_like_firewall = lower.contains("address already in use")
            || lower.contains("permission denied")
            || lower.contains("network is unreachable")
            || lower.contains("timed out")
            || lower.contains("connection refused")
            || lower.contains("bind");
        if looks_like_firewall {
            log::warn!("[mesh] transport error looks firewall/network related: {}", err_str);
            let _ = app.emit(
                "mesh://firewall-warning",
                serde_json::json!({ "error": err_str, "platform": std::env::consts::OS }),
            );
        }
    }

    async fn derive_identity(app: &AppHandle) -> Result<(String, String), String> {
        let tenant_id = crate::commands::tenant::load_tenant(app).await.id;
        let hw = crate::commands::hardware_id::get_hardware_id(app.clone()).await?;
        let fingerprint_hex: String = {
            let mut hasher = Sha256::new();
            hasher.update(hw.hardware_id.as_bytes());
            hasher.finalize().iter().map(|b| format!("{:02x}", b)).collect()
        };
        Ok((tenant_id, fingerprint_hex))
    }

    #[tauri::command]
    pub async fn mesh_create(
        app: AppHandle,
        handle: State<'_, MeshHandle>,
        join_code: String,
        available_skills: Vec<String>,
    ) -> Result<MeshCreateResult, String> {
        if handle.get().await.is_some() {
            return Err("mesh already active; leave first".into());
        }
        let (tenant_id, device_fingerprint) = derive_identity(&app).await?;
        let secret_key = iroh::SecretKey::generate();
        let self_client = ClientInfo {
            client_id: device_fingerprint.clone(),
            tenant_id,
            device_fingerprint,
            current_load: 0,
            available_skills,
            priority: "normal".into(),
            first_seen_ts: now_ms(),
            last_active_ts: now_ms(),
        };
        let (node, ticket) = super::super::MeshNode::create(secret_key, join_code, self_client, None, app.clone())
            .await
            .map_err(|e| { let s = e.to_string(); maybe_emit_firewall_warning(&app, &s); s })?;
        let status = node.status().await;
        let ticket_str = ticket.encode();
        handle.set(node).await;
        Ok(MeshCreateResult { status, ticket: ticket_str })
    }

    #[tauri::command]
    pub async fn mesh_join(
        app: AppHandle,
        handle: State<'_, MeshHandle>,
        ticket: String,
        available_skills: Vec<String>,
    ) -> Result<MeshStatus, String> {
        if handle.get().await.is_some() {
            return Err("mesh already active; leave first".into());
        }
        let (tenant_id, device_fingerprint) = derive_identity(&app).await?;
        let ticket = ticket.parse::<super::super::ticket::MeshTicket>().map_err(|e| e.to_string())?;
        let secret_key = iroh::SecretKey::generate();
        let self_client = ClientInfo {
            client_id: device_fingerprint.clone(),
            tenant_id,
            device_fingerprint,
            current_load: 0,
            available_skills,
            priority: "normal".into(),
            first_seen_ts: now_ms(),
            last_active_ts: now_ms(),
        };
        let node = super::super::MeshNode::join(secret_key, ticket, self_client, None, app.clone())
            .await
            .map_err(|e| { let s = e.to_string(); maybe_emit_firewall_warning(&app, &s); s })?;
        let status = node.status().await;
        handle.set(node).await;
        Ok(status)
    }

    #[tauri::command]
    pub async fn mesh_leave(handle: State<'_, MeshHandle>) -> Result<(), String> {
        handle.clear().await;
        Ok(())
    }

    #[tauri::command]
    pub async fn mesh_status(handle: State<'_, MeshHandle>) -> Result<Option<MeshStatus>, String> {
        match handle.get().await {
            Some(node) => Ok(Some(node.status().await)),
            None => Ok(None),
        }
    }

    #[tauri::command]
    pub async fn mesh_submit_requirement(
        handle: State<'_, MeshHandle>,
        text: String,
    ) -> Result<String, String> {
        let node = handle.get().await.ok_or("mesh not active")?;
        node.submit_requirement(&text).await.map_err(|e| e.to_string())
    }

    #[tauri::command]
    pub async fn mesh_list_peers(
        handle: State<'_, MeshHandle>,
    ) -> Result<Vec<ClientInfo>, String> {
        match handle.get().await {
            Some(node) => Ok(node.list_peers().await),
            None => Ok(vec![]),
        }
    }

    #[tauri::command]
    pub async fn mesh_send_file(
        handle: State<'_, MeshHandle>,
        path: String,
    ) -> Result<String, String> {
        let node = handle.get().await.ok_or("mesh not active")?;
        let (_hash, offer) = super::super::files::build_file_offer(&path)
            .await
            .map_err(|e| e.to_string())?;
        node.broadcast_file_offer(offer).await.map_err(|e| e.to_string())
    }

    #[tauri::command]
    pub async fn mesh_download_file(
        _handle: State<'_, MeshHandle>,
        blob_hash: String,
        dest: String,
    ) -> Result<(), String> {
        super::super::files::download(&blob_hash, &dest)
            .await
            .map_err(|e| e.to_string())
    }
}

#[cfg(feature = "mesh")]
pub use mesh_impl::*;

// ── Stub command implementations (mesh feature OFF) ──────────────────

#[cfg(not(feature = "mesh"))]
mod mesh_stub {
    use super::*;

    const MESH_DISABLED: &str = "mesh feature not enabled; rebuild with --features mesh";

    #[tauri::command]
    pub async fn mesh_create(
        _app: AppHandle, _handle: State<'_, MeshHandle>,
        _join_code: String, _available_skills: Vec<String>,
    ) -> Result<MeshCreateResult, String> { Err(MESH_DISABLED.into()) }

    #[tauri::command]
    pub async fn mesh_join(
        _app: AppHandle, _handle: State<'_, MeshHandle>,
        _ticket: String, _available_skills: Vec<String>,
    ) -> Result<MeshStatus, String> { Err(MESH_DISABLED.into()) }

    #[tauri::command]
    pub async fn mesh_leave(_handle: State<'_, MeshHandle>) -> Result<(), String> { Ok(()) }

    #[tauri::command]
    pub async fn mesh_status(_handle: State<'_, MeshHandle>) -> Result<Option<MeshStatus>, String> { Ok(None) }

    #[tauri::command]
    pub async fn mesh_submit_requirement(
        _handle: State<'_, MeshHandle>, _text: String,
    ) -> Result<String, String> { Err(MESH_DISABLED.into()) }

    #[tauri::command]
    pub async fn mesh_list_peers(
        _handle: State<'_, MeshHandle>,
    ) -> Result<Vec<ClientInfo>, String> { Ok(vec![]) }

    #[tauri::command]
    pub async fn mesh_send_file(
        _handle: State<'_, MeshHandle>, _path: String,
    ) -> Result<String, String> { Err(MESH_DISABLED.into()) }

    #[tauri::command]
    pub async fn mesh_download_file(
        _handle: State<'_, MeshHandle>, _blob_hash: String, _dest: String,
    ) -> Result<(), String> { Err(MESH_DISABLED.into()) }
}

#[cfg(not(feature = "mesh"))]
pub use mesh_stub::*;
