// Copyright (c) 2026 AIMarketing
//
// Upload guard — intercepts POST requests to external URLs and
// prompts the user for confirmation via a dialog.
// Does NOT block GET reads; only monitors outgoing data.

use tauri::AppHandle;
use tauri_plugin_dialog::{DialogExt, MessageDialogButtons, MessageDialogKind};

/// Check whether a URL is in the silent-allow list.
/// - Our own server (ai.tuptup.top, kuaiju2c.tuptup.top)
/// - LAN / loopback subnets
fn is_silent_allow(url: &str) -> bool {
    // Dev / loopback
    if url.starts_with("http://127.0.0.1")
        || url.starts_with("http://localhost")
        || url.starts_with("http://[::1]")
    {
        return true;
    }
    // LAN subnets
    if url.starts_with("http://10.")
        || url.starts_with("http://172.16.")
        || url.starts_with("http://172.17.")
        || url.starts_with("http://172.18.")
        || url.starts_with("http://172.19.")
        || url.starts_with("http://172.20.")
        || url.starts_with("http://172.21.")
        || url.starts_with("http://172.22.")
        || url.starts_with("http://172.23.")
        || url.starts_with("http://172.24.")
        || url.starts_with("http://172.25.")
        || url.starts_with("http://172.26.")
        || url.starts_with("http://172.27.")
        || url.starts_with("http://172.28.")
        || url.starts_with("http://172.29.")
        || url.starts_with("http://172.30.")
        || url.starts_with("http://172.31.")
        || url.starts_with("http://192.168.")
    {
        return true;
    }
    // Our own servers (HTTPS)
    if let Some(host) = extract_host(url) {
        if host == "ai.tuptup.top"
            || host == "kuaiju2c.tuptup.top"
            || host.ends_with(".ai.tuptup.top")
            || host.ends_with(".kuaiju2c.tuptup.top")
        {
            return true;
        }
    }
    false
}

fn extract_host(url: &str) -> Option<String> {
    let after_proto = url.find("://")?;
    let rest = &url[after_proto + 3..];
    let host_end = rest.find(['/', ':', '?', '#'])
        .unwrap_or(rest.len());
    Some(rest[..host_end].to_lowercase())
}

/// Prompt the user with a dialog to confirm uploading data to `url`.
/// - Silent-allow listed URLs → return `true` immediately (no dialog)
/// - Everything else → show a `Yes/No` dialog; returns user's choice
pub async fn confirm_upload(app: AppHandle, url: &str) -> Result<bool, String> {
    if is_silent_allow(url) {
        return Ok(true);
    }

    let (tx, rx) = tokio::sync::oneshot::channel::<bool>();

    app.dialog()
        .message(format!(
            "A skill wants to send data to:\n\n{url}\n\nAllow?"
        ))
        .title("Upload Confirmation")
        .kind(MessageDialogKind::Warning)
        .buttons(MessageDialogButtons::YesNo)
        .show(move |yes| {
            let _ = tx.send(yes);
        });

    rx.await.map_err(|_| "dialog closed without response".to_string())
}
