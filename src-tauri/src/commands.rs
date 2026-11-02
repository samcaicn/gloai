use crate::bbt;
use crate::bridge::{BridgeConfig, BridgeState};
use crate::ilink_session::SessionManager;
use crate::state::AppState;
use crate::update;
use serde_json::{json, Value};
use std::sync::Arc;
use tauri::{Manager, State};

/// 读取本地配置（首次启动时可能是空对象 {}）
#[tauri::command]
pub async fn load_config(state: State<'_, AppState>) -> Result<Value, String> {
    let g = state.config.lock().unwrap();
    Ok((*g).clone())
}

/// 写入本地配置并落盘
#[tauri::command]
pub async fn save_config(state: State<'_, AppState>, value: Value) -> Result<Value, String> {
    *state.config.lock().unwrap() = value.clone();
    state.save_config()?;
    Ok(value)
}

/// 清空本地配置（恢复为 {}）
#[tauri::command]
pub async fn reset_config(state: State<'_, AppState>) -> Result<Value, String> {
    *state.config.lock().unwrap() = json!({});
    state.save_config()?;
    Ok(json!({}))
}

/// 仅设置基础连接信息（不登录，适合已持有 token 的场景）
#[tauri::command]
pub async fn set_connection(
    state: State<'_, AppState>,
    base_url: String,
    token: Option<String>,
    telephone: Option<String>,
) -> Result<(), String> {
    let mut c = state.conn.lock().unwrap();
    c.base_url = base_url;
    c.token = token.filter(|s| !s.trim().is_empty());
    c.telephone = telephone;
    Ok(())
}

/// 登录 bbt-zb（action=account/login），取得 token 并写入内存连接状态
#[tauri::command]
pub async fn login(
    state: State<'_, AppState>,
    base_url: String,
    telephone: String,
    password: String,
) -> Result<Value, String> {
    let mut c = bbt::BbtConnection {
        base_url: base_url.clone(),
        token: None,
        telephone: Some(telephone.clone()),
    };
    let (token, account) = bbt::login(&c, &telephone, &password).await?;
    c.token = Some(token.clone());
    *state.conn.lock().unwrap() = c;
    Ok(json!({ "ok": true, "token": token, "account": account }))
}

/// 通用 action 调用（透传给 bbt-zb 单端点 API，便于扩展话术库等操作）
#[tauri::command]
pub async fn bbt_call(
    state: State<'_, AppState>,
    action: String,
    condition: Option<Value>,
) -> Result<Value, String> {
    let c = state.conn.lock().unwrap().clone();
    bbt::call(&c, &action, &condition.unwrap_or(json!({}))).await
}

/// 探活：检查 bbt-zb 是否可达（不要求已登录）
#[tauri::command]
pub async fn test_connection(state: State<'_, AppState>) -> Result<Value, String> {
    let c = state.conn.lock().unwrap().clone();
    if c.base_url.is_empty() {
        return Ok(json!({ "ok": false, "message": "未配置接口地址" }));
    }
    match bbt::health(&c).await {
        Ok(true) => Ok(json!({ "ok": true, "message": "连接成功" })),
        Ok(false) => Ok(json!({ "ok": false, "message": "服务返回非 200" })),
        Err(e) => Ok(json!({ "ok": false, "message": e })),
    }
}

/// 拉取远程聊天参数（chat/configGet）并写回本地
#[tauri::command]
pub async fn fetch_remote_config(state: State<'_, AppState>) -> Result<Value, String> {
    let c = state.conn.lock().unwrap().clone();
    if c.base_url.is_empty() {
        return Err("未配置接口地址".into());
    }
    let data = bbt::get_chat_config(&c).await?;
    // 远程值合并进本地配置（connection 字段保留）
    {
        let mut g = state.config.lock().unwrap();
        if let (Some(obj), Some(src)) = (g.as_object_mut(), data.as_object()) {
            for (k, v) in src {
                obj.insert(k.clone(), v.clone());
            }
        }
        let _ = state.save_config();
    }
    Ok(data)
}

/// 推送聊天参数到 bbt-zb（chat/configSave）
#[tauri::command]
pub async fn push_remote_config(
    state: State<'_, AppState>,
    value: Value,
) -> Result<Value, String> {
    let c = state.conn.lock().unwrap().clone();
    if c.base_url.is_empty() {
        return Err("未配置接口地址".into());
    }
    bbt::save_chat_config(&c, &value).await
}

// ---------------- 自动更新（weauto 模式）----------------

/// 检查更新：GET {base}/updates/version.json，对比当前版本
#[tauri::command]
pub async fn update_check(base_url: String) -> Result<Value, String> {
    update::check_update(&base_url, env!("CARGO_PKG_VERSION")).await
}

/// 下载新版本 + 验签 + 暂存（重启后自动替换）
#[tauri::command]
pub async fn update_prepare(app: tauri::AppHandle, base_url: String) -> Result<Value, String> {
    update::prepare_update(&base_url, &app).await
}

// ============================================================================
//  iLink 多账号聊天（扫码登录 / 会话 / 收发 / 本地历史 / 媒体）
// ============================================================================

/// 发起扫码登录：返回 {login_id, content, svg}
#[tauri::command]
pub async fn ilink_begin_login(
    mgr: State<'_, Arc<SessionManager>>,
) -> Result<Value, String> {
    let (login_id, content, svg) = mgr.begin_login().await?;
    Ok(json!({"login_id": login_id, "content": content, "svg": svg}))
}

/// 轮询扫码状态一次（前端 1~2s 一次）：{status, svg?, content?, account?}
#[tauri::command]
pub async fn ilink_poll_login(
    mgr: State<'_, Arc<SessionManager>>,
    login_id: String,
) -> Result<Value, String> {
    mgr.poll_login(&login_id).await
}

#[tauri::command]
pub async fn ilink_cancel_login(
    mgr: State<'_, Arc<SessionManager>>,
    login_id: String,
) -> Result<(), String> {
    mgr.cancel_login(&login_id);
    Ok(())
}

#[tauri::command]
pub async fn ilink_list_accounts(mgr: State<'_, Arc<SessionManager>>) -> Result<Value, String> {
    Ok(json!(mgr.list_accounts()))
}

#[tauri::command]
pub async fn ilink_remove_account(
    mgr: State<'_, Arc<SessionManager>>,
    bot_id: String,
) -> Result<(), String> {
    mgr.remove_account(&bot_id);
    Ok(())
}

#[tauri::command]
pub async fn ilink_send_text(
    mgr: State<'_, Arc<SessionManager>>,
    bot_id: String,
    to: String,
    text: String,
) -> Result<Value, String> {
    let (tx, rx) = tokio::sync::oneshot::channel();
    mgr.send_cmd(&bot_id, crate::ilink_session::SessionCmd::SendText { to, text, reply: tx })
        .map_err(|e| e)?;
    let r = rx.await.map_err(|_| "会话无响应".to_string())?;
    r.map(|_| json!({"ok": true}))
}

#[tauri::command]
pub async fn ilink_send_image(
    mgr: State<'_, Arc<SessionManager>>,
    bot_id: String,
    to: String,
    data_b64: String,
    filename: String,
) -> Result<Value, String> {
    use base64::Engine as _;
    let data = base64::engine::general_purpose::STANDARD
        .decode(&data_b64)
        .map_err(|e| format!("图片 base64 解码失败: {}", e))?;
    let (tx, rx) = tokio::sync::oneshot::channel();
    mgr.send_cmd(
        &bot_id,
        crate::ilink_session::SessionCmd::SendImage { to, data, filename, reply: tx },
    )?;
    rx.await.map_err(|_| "会话无响应".to_string())?.map(|_| json!({"ok": true}))
}

/// 发语音：data_b64 为已编码语音（silk/mp3 等），encode_type 4=SILK 2=MP3 3=WAV
#[tauri::command]
pub async fn ilink_send_voice(
    mgr: State<'_, Arc<SessionManager>>,
    bot_id: String,
    to: String,
    data_b64: String,
    encode_type: i64,
    sample_rate: i64,
    playtime_ms: i64,
) -> Result<Value, String> {
    use base64::Engine as _;
    let data = base64::engine::general_purpose::STANDARD
        .decode(&data_b64)
        .map_err(|e| format!("语音 base64 解码失败: {}", e))?;
    let (tx, rx) = tokio::sync::oneshot::channel();
    mgr.send_cmd(
        &bot_id,
        crate::ilink_session::SessionCmd::SendVoice {
            to,
            data,
            encode_type,
            sample_rate,
            playtime_ms,
            reply: tx,
        },
    )?;
    rx.await.map_err(|_| "会话无响应".to_string())?.map(|_| json!({"ok": true}))
}

/// 1 对多群发：多个 bot 账号给同一微信用户发同一条文本
#[tauri::command]
pub async fn ilink_broadcast_text(
    mgr: State<'_, Arc<SessionManager>>,
    bot_ids: Vec<String>,
    to: String,
    text: String,
) -> Result<Value, String> {
    let results = mgr.broadcast_text(&bot_ids, &to, &text).await;
    Ok(json!(results))
}

/// 读取本地消息存档（尾部 limit 条）
#[tauri::command]
pub async fn ilink_local_history(
    mgr: State<'_, Arc<SessionManager>>,
    bot_id: String,
    limit: Option<usize>,
) -> Result<Value, String> {
    Ok(json!(mgr.read_history(&bot_id, limit.unwrap_or(200))))
}

/// 下载并解密 CDN 媒体（media 为消息记录里的 media 对象），返回 {mime, data_b64}
#[tauri::command]
pub async fn ilink_read_media(
    mgr: State<'_, Arc<SessionManager>>,
    media: Value,
) -> Result<Value, String> {
    mgr.fetch_media(media).await
}

// ============================================================================
//  团长聊天桥（bbt-zb ↔ iLink 1 对多）
// ============================================================================

#[tauri::command]
pub async fn bridge_get_config(
    bridge: State<'_, Arc<BridgeState>>,
) -> Result<Value, String> {
    let c = bridge.config.lock().unwrap().clone();
    serde_json::to_value(c).map_err(|e| e.to_string())
}

/// 保存桥配置（合并进本地 config.json 的 bridge 字段并落盘）
#[tauri::command]
pub async fn bridge_set_config(
    app: tauri::AppHandle,
    bridge: State<'_, Arc<BridgeState>>,
    mgr: State<'_, Arc<SessionManager>>,
    value: Value,
) -> Result<Value, String> {
    let cfg: BridgeConfig = serde_json::from_value(value).map_err(|e| format!("配置格式错误: {}", e))?;
    *bridge.config.lock().unwrap() = cfg.clone();
    // 落盘到主配置
    let state = app.state::<AppState>();
    {
        let mut g = state.config.lock().unwrap();
        if let Some(obj) = g.as_object_mut() {
            obj.insert("bridge".into(), serde_json::to_value(&cfg).unwrap_or(json!({})));
        }
    }
    let _ = state.save_config();
    let _ = mgr; // 桥任务启动时自行取用
    crate::bridge::ensure_started(app.clone());
    serde_json::to_value(cfg).map_err(|e| e.to_string())
}

/// 仅切换启用开关（保留其他配置）
#[tauri::command]
pub async fn bridge_set_enabled(
    app: tauri::AppHandle,
    bridge: State<'_, Arc<BridgeState>>,
    enabled: bool,
) -> Result<Value, String> {
    {
        let mut c = bridge.config.lock().unwrap();
        c.enabled = enabled;
        let snapshot = c.clone();
        let state = app.state::<AppState>();
        {
            let mut g = state.config.lock().unwrap();
            if let Some(obj) = g.as_object_mut() {
                obj.insert("bridge".into(), serde_json::to_value(&snapshot).unwrap_or(json!({})));
            }
        }
        let _ = state.save_config();
        return serde_json::to_value(snapshot).map_err(|e| e.to_string());
    }
}

// ============================================================================
//  微信自动回复（iLink 入站 → bbt-zb 服务端 AI → iLink 回发）
// ============================================================================

#[tauri::command]
pub async fn autoreply_get_config(
    ar: State<'_, Arc<crate::autoreply::AutoReplyState>>,
) -> Result<Value, String> {
    let c = ar.config.lock().unwrap().clone();
    serde_json::to_value(c).map_err(|e| e.to_string())
}

/// 保存自动回复配置（合并进本地 config.json 的 autoreply 字段并落盘）。
/// 团员密码仅落本地盘，不回传前端（前端传回的空 password 视为「不修改」）。
#[tauri::command]
pub async fn autoreply_set_config(
    app: tauri::AppHandle,
    ar: State<'_, Arc<crate::autoreply::AutoReplyState>>,
    value: Value,
) -> Result<Value, String> {
    let mut cur = ar.config.lock().unwrap().clone();
    let incoming: crate::autoreply::AutoReplyConfig =
        serde_json::from_value(value).map_err(|e| format!("配置格式错误: {}", e))?;
    let keep_pwd = incoming.member_password.is_empty() && !cur.member_password.is_empty();
    let old_pwd = cur.member_password.clone();
    cur = incoming;
    if keep_pwd {
        cur.member_password = old_pwd;
    }
    *ar.config.lock().unwrap() = cur.clone();
    let state = app.state::<AppState>();
    {
        let mut g = state.config.lock().unwrap();
        if let Some(obj) = g.as_object_mut() {
            // 与 bridge 一致：完整落盘到本机 config.json（含团员密码，仅本机文件）
            obj.insert("autoreply".into(), serde_json::to_value(&cur).unwrap_or(json!({})));
        }
    }
    let _ = state.save_config();
    serde_json::to_value(cur).map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn autoreply_set_enabled(
    app: tauri::AppHandle,
    ar: State<'_, Arc<crate::autoreply::AutoReplyState>>,
    enabled: bool,
) -> Result<Value, String> {
    {
        let mut c = ar.config.lock().unwrap();
        c.enabled = enabled;
        let snapshot = c.clone();
        let state = app.state::<AppState>();
        {
            let mut g = state.config.lock().unwrap();
            if let Some(obj) = g.as_object_mut() {
                obj.insert("autoreply".into(), serde_json::to_value(&snapshot).unwrap_or(json!({})));
            }
        }
        let _ = state.save_config();
    }
    let c = ar.config.lock().unwrap().clone();
    serde_json::to_value(c).map_err(|e| e.to_string())
}

/// 最近自动回复日志（尾部 limit 条）
#[tauri::command]
pub async fn autoreply_logs(
    ar: State<'_, Arc<crate::autoreply::AutoReplyState>>,
    limit: Option<usize>,
) -> Result<Value, String> {
    let lg = ar.logs.lock().unwrap();
    let n = limit.unwrap_or(50).min(lg.len());
    Ok(json!(lg[lg.len() - n..].to_vec()))
}

// ============================================================================
//  团员 iLink 二维码（hub：团员 ↔ iLink 机器人账号 映射持久化）
//  每个团员对应一个 iLink 机器人账号（bot_id）。扫码确认后记录映射，
//  以便「已绑定账号」区能回显团员身份、并在过期重建后保持关联。
// ============================================================================

/// 读取团员→机器人映射（member_id -> bot_id）
#[tauri::command]
pub async fn memberqr_get_map(app: tauri::AppHandle) -> Result<Value, String> {
    let state = app.state::<AppState>();
    let g = state.config.lock().unwrap();
    let map = g
        .get("memberqr")
        .and_then(|v| v.get("map"))
        .cloned()
        .unwrap_or(json!({}));
    Ok(map)
}

/// 写入团员→机器人映射（member_id -> bot_id）。传入完整 map 对象覆盖保存。
#[tauri::command]
pub async fn memberqr_set_map(app: tauri::AppHandle, map: Value) -> Result<Value, String> {
    let state = app.state::<AppState>();
    {
        let mut g = state.config.lock().unwrap();
        if let Some(obj) = g.as_object_mut() {
            let entry = obj.entry("memberqr".to_string()).or_insert(json!({}));
            if let Some(e) = entry.as_object_mut() {
                e.insert("map".into(), map.clone());
            }
        }
    }
    let _ = state.save_config();
    Ok(map)
}
