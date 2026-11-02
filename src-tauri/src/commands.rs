use crate::bbt;
use crate::bridge::{BridgeConfig, BridgeState};
use crate::ilink_session::{SessionCmd, SessionManager};
use crate::state::AppState;
use crate::update;
use serde_json::{json, Value};
use std::sync::Arc;
use tauri::{Manager, State};

/// 读取本地配置（首次启动时可能是空对象 {}）
#[tauri::command(rename_all = "snake_case")]
pub async fn load_config(state: State<'_, AppState>) -> Result<Value, String> {
    let g = state.config.lock().unwrap();
    Ok((*g).clone())
}

/// 写入本地配置并落盘
#[tauri::command(rename_all = "snake_case")]
pub async fn save_config(state: State<'_, AppState>, value: Value) -> Result<Value, String> {
    *state.config.lock().unwrap() = value.clone();
    state.save_config()?;
    Ok(value)
}

/// 清空本地配置（恢复为 {}）
#[tauri::command(rename_all = "snake_case")]
pub async fn reset_config(state: State<'_, AppState>) -> Result<Value, String> {
    *state.config.lock().unwrap() = json!({});
    state.save_config()?;
    Ok(json!({}))
}

/// 仅设置基础连接信息（不登录，适合已持有 token 的场景）
#[tauri::command(rename_all = "snake_case")]
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
#[tauri::command(rename_all = "snake_case")]
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
#[tauri::command(rename_all = "snake_case")]
pub async fn bbt_call(
    state: State<'_, AppState>,
    action: String,
    condition: Option<Value>,
) -> Result<Value, String> {
    let c = state.conn.lock().unwrap().clone();
    bbt::call(&c, &action, &condition.unwrap_or(json!({}))).await
}

/// 探活：检查 bbt-zb 是否可达（不要求已登录）
#[tauri::command(rename_all = "snake_case")]
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
#[tauri::command(rename_all = "snake_case")]
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
#[tauri::command(rename_all = "snake_case")]
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
#[tauri::command(rename_all = "snake_case")]
pub async fn update_check(base_url: String) -> Result<Value, String> {
    update::check_update(&base_url, env!("CARGO_PKG_VERSION")).await
}

/// 下载新版本 + 验签 + 暂存（重启后自动替换）
#[tauri::command(rename_all = "snake_case")]
pub async fn update_prepare(app: tauri::AppHandle, base_url: String) -> Result<Value, String> {
    update::prepare_update(&base_url, &app).await
}

// ============================================================================
//  iLink 多账号聊天（扫码登录 / 会话 / 收发 / 本地历史 / 媒体）
// ============================================================================

/// 发起扫码登录：返回 {login_id, content, svg}
#[tauri::command(rename_all = "snake_case")]
pub async fn ilink_begin_login(
    mgr: State<'_, Arc<SessionManager>>,
) -> Result<Value, String> {
    let (login_id, content, svg) = mgr.begin_login().await?;
    Ok(json!({"login_id": login_id, "content": content, "svg": svg}))
}

/// 轮询扫码状态一次（前端 1~2s 一次）：{status, svg?, content?, account?}
#[tauri::command(rename_all = "snake_case")]
pub async fn ilink_poll_login(
    mgr: State<'_, Arc<SessionManager>>,
    login_id: String,
) -> Result<Value, String> {
    mgr.poll_login(&login_id).await
}

#[tauri::command(rename_all = "snake_case")]
pub async fn ilink_cancel_login(
    mgr: State<'_, Arc<SessionManager>>,
    login_id: String,
) -> Result<(), String> {
    mgr.cancel_login(&login_id);
    Ok(())
}

#[tauri::command(rename_all = "snake_case")]
pub async fn ilink_list_accounts(mgr: State<'_, Arc<SessionManager>>) -> Result<Value, String> {
    Ok(json!(mgr.list_accounts()))
}

#[tauri::command(rename_all = "snake_case")]
pub async fn ilink_remove_account(
    mgr: State<'_, Arc<SessionManager>>,
    bot_id: String,
) -> Result<(), String> {
    mgr.remove_account(&bot_id);
    Ok(())
}

#[tauri::command(rename_all = "snake_case")]
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

#[tauri::command(rename_all = "snake_case")]
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
#[tauri::command(rename_all = "snake_case")]
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
#[tauri::command(rename_all = "snake_case")]
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
#[tauri::command(rename_all = "snake_case")]
pub async fn ilink_local_history(
    mgr: State<'_, Arc<SessionManager>>,
    bot_id: String,
    limit: Option<usize>,
) -> Result<Value, String> {
    Ok(json!(mgr.read_history(&bot_id, limit.unwrap_or(200))))
}

/// 下载并解密 CDN 媒体（media 为消息记录里的 media 对象），返回 {mime, data_b64}
#[tauri::command(rename_all = "snake_case")]
pub async fn ilink_read_media(
    mgr: State<'_, Arc<SessionManager>>,
    media: Value,
) -> Result<Value, String> {
    mgr.fetch_media(media).await
}

// ============================================================================
//  团长聊天桥（bbt-zb ↔ iLink 1 对多）
// ============================================================================

#[tauri::command(rename_all = "snake_case")]
pub async fn bridge_get_config(
    bridge: State<'_, Arc<BridgeState>>,
) -> Result<Value, String> {
    let c = bridge.config.lock().unwrap().clone();
    serde_json::to_value(c).map_err(|e| e.to_string())
}

/// 保存桥配置（合并进本地 config.json 的 bridge 字段并落盘）
#[tauri::command(rename_all = "snake_case")]
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
#[tauri::command(rename_all = "snake_case")]
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

#[tauri::command(rename_all = "snake_case")]
pub async fn autoreply_get_config(
    ar: State<'_, Arc<crate::autoreply::AutoReplyState>>,
) -> Result<Value, String> {
    let c = ar.config.lock().unwrap().clone();
    serde_json::to_value(c).map_err(|e| e.to_string())
}

/// 保存自动回复配置（合并进本地 config.json 的 autoreply 字段并落盘）。
/// 团员密码仅落本地盘，不回传前端（前端传回的空 password 视为「不修改」）。
#[tauri::command(rename_all = "snake_case")]
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

#[tauri::command(rename_all = "snake_case")]
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
#[tauri::command(rename_all = "snake_case")]
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
#[tauri::command(rename_all = "snake_case")]
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
#[tauri::command(rename_all = "snake_case")]
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

// ============================================================================
//  微信 wxauto 通道（wechatauto sidecar：私聊+群聊 → 统一 AI 回复管线）
// ============================================================================

#[tauri::command(rename_all = "snake_case")]
pub async fn wxauto_get_config(
    app: tauri::AppHandle,
    wx: State<'_, Arc<crate::wxauto::WxAutoState>>,
) -> Result<Value, String> {
    let c = wx.config.lock().unwrap().clone();
    let running = crate::wxauto::is_running(&app);
    let mut v = serde_json::to_value(c).map_err(|e| e.to_string())?;
    if let Some(o) = v.as_object_mut() {
        o.insert("running".into(), json!(running));
    }
    Ok(v)
}

#[tauri::command(rename_all = "snake_case")]
pub async fn wxauto_set_config(
    app: tauri::AppHandle,
    wx: State<'_, Arc<crate::wxauto::WxAutoState>>,
    value: Value,
) -> Result<Value, String> {
    let cfg: crate::wxauto::WxAutoConfig =
        serde_json::from_value(value).map_err(|e| format!("配置格式错误: {}", e))?;
    *wx.config.lock().unwrap() = cfg.clone();
    {
        let state = app.state::<AppState>();
        {
            let mut g = state.config.lock().unwrap();
            if let Some(obj) = g.as_object_mut() {
                obj.insert("wxauto".into(), serde_json::to_value(&cfg).unwrap_or(json!({})));
            }
        }
        let _ = state.save_config();
    }
    // 开关即生效：关=停子进程；开=supervisor 下一轮自动拉起
    if cfg.enabled {
        crate::wxauto::ensure_started(app.clone());
    } else {
        crate::wxauto::stop(&app).await;
    }
    serde_json::to_value(cfg).map_err(|e| e.to_string())
}

#[tauri::command(rename_all = "snake_case")]
pub async fn wxauto_start(app: tauri::AppHandle) -> Result<Value, String> {
    {
        let wx = app.state::<Arc<crate::wxauto::WxAutoState>>();
        wx.config.lock().unwrap().enabled = true;
        let cfg = wx.config.lock().unwrap().clone();
        {
            let state = app.state::<AppState>();
            {
                let mut g = state.config.lock().unwrap();
                if let Some(obj) = g.as_object_mut() {
                    obj.insert("wxauto".into(), serde_json::to_value(&cfg).unwrap_or(json!({})));
                }
            }
            let _ = state.save_config();
        }
    }
    crate::wxauto::ensure_started(app.clone());
    Ok(json!({"ok": true}))
}

#[tauri::command(rename_all = "snake_case")]
pub async fn wxauto_stop(app: tauri::AppHandle) -> Result<Value, String> {
    {
        let wx = app.state::<Arc<crate::wxauto::WxAutoState>>();
        wx.config.lock().unwrap().enabled = false;
        let cfg = wx.config.lock().unwrap().clone();
        {
            let state = app.state::<AppState>();
            {
                let mut g = state.config.lock().unwrap();
                if let Some(obj) = g.as_object_mut() {
                    obj.insert("wxauto".into(), serde_json::to_value(&cfg).unwrap_or(json!({})));
                }
            }
            let _ = state.save_config();
        }
    }
    crate::wxauto::stop(&app).await;
    Ok(json!({"ok": true}))
}

/// wxauto 通道最近日志
#[tauri::command(rename_all = "snake_case")]
pub async fn wxauto_logs(
    wx: State<'_, Arc<crate::wxauto::WxAutoState>>,
    limit: Option<usize>,
) -> Result<Value, String> {
    let lg = wx.logs.lock().unwrap();
    let n = limit.unwrap_or(50).min(lg.len());
    Ok(json!(lg[lg.len() - n..].to_vec()))
}

// ============================================================================
//  统一渠道路由（订单消息 / 互动消息页）：微信私聊、微信群聊、iLink、团员
//  target 约定：
//    wx_private / wx_group → 微信会话 id（wxid_xxx / xxx@chatroom，sidecar 会话列表）
//    ilink                 → bot_id（发送走该 bot 最近会话对象）
//    member                → bbt-zb 团员 id（发送经 memberqr 绑定映射找 bot）
// ============================================================================

fn read_member_map(app: &tauri::AppHandle) -> serde_json::Map<String, Value> {
    app.try_state::<AppState>()
        .and_then(|state| {
            let g = state.config.lock().unwrap();
            g.get("memberqr").and_then(|m| m.get("map")).cloned()
        })
        .and_then(|v| v.as_object().cloned())
        .unwrap_or_default()
}

/// iLink 侧发送：经指定 bot 的最近会话对象回发
async fn ilink_route_send(
    app: &tauri::AppHandle,
    bot_id: &str,
    text: Option<&str>,
    image: Option<(&str, &str)>, // (data_b64, filename)
) -> Result<Value, String> {
    let mgr = app
        .try_state::<Arc<SessionManager>>()
        .ok_or("iLink 会话管理未初始化")?;
    let (to, _is_group) = mgr
        .last_peer_of(bot_id)
        .ok_or("该 iLink 账号还没有会话对象（等对方先来一条消息）")?;
    match (text, image) {
        (Some(t), _) => {
            let (tx, rx) = tokio::sync::oneshot::channel();
            mgr.send_cmd(bot_id, SessionCmd::SendText { to, text: t.to_string(), reply: tx })?;
            rx.await.map_err(|_| "会话无响应".to_string())?.map(|_| json!({"ok": true}))
        }
        (None, Some((b64, filename))) => {
            use base64::Engine as _;
            let data = base64::engine::general_purpose::STANDARD
                .decode(b64)
                .map_err(|e| format!("图片 base64 解码失败: {}", e))?;
            let (tx, rx) = tokio::sync::oneshot::channel();
            mgr.send_cmd(bot_id, SessionCmd::SendImage { to, data, filename: filename.to_string(), reply: tx })?;
            rx.await.map_err(|_| "会话无响应".to_string())?.map(|_| json!({"ok": true}))
        }
        (None, None) => Err("空消息".into()),
    }
}

#[tauri::command(rename_all = "snake_case")]
pub async fn channel_targets(app: tauri::AppHandle) -> Result<Value, String> {
    // 1) 微信：sidecar 会话列表（私聊/群聊分组）
    let mut wx_private: Vec<Value> = Vec::new();
    let mut wx_group: Vec<Value> = Vec::new();
    let mut wx_note = String::new();
    if crate::wxauto::is_running(&app) {
        match crate::wxauto::list_sessions(&app, 100).await {
            Ok(rows) => {
                for s in rows {
                    let is_group = s.get("is_group").and_then(|g| g.as_bool()).unwrap_or(false);
                    if is_group {
                        wx_group.push(s);
                    } else {
                        wx_private.push(s);
                    }
                }
            }
            Err(e) => wx_note = e,
        }
    } else {
        wx_note = "微信通道未开启（AI 自动回复面板 → 微信通道开关）".into();
    }

    // 2) iLink：已绑定账号
    let ilink_accounts = app
        .try_state::<Arc<SessionManager>>()
        .map(|mgr| json!(mgr.list_accounts()))
        .unwrap_or(json!([]));

    // 3) 团员：bbt-zb 名册（先 ledger 取团，再 listMembers）
    let mut members: Vec<Value> = Vec::new();
    let mut member_note = String::new();
    if let Some(state) = app.try_state::<AppState>() {
        let conn = state.conn.lock().unwrap().clone();
        if conn.token.as_deref().unwrap_or("").is_empty() {
            member_note = "请先在「团员二维码」页完成团长登录".into();
        } else {
            let group_id = match bbt::call(&conn, "leader/ledger", &json!({})).await {
                Ok(r) => {
                    let list = r.pointer("/result/data/list").and_then(|l| l.as_array()).cloned().unwrap_or_default();
                    list.first()
                        .and_then(|g0| g0.get("id"))
                        .and_then(|i| i.as_i64())
                        .unwrap_or(0)
                }
                Err(e) => {
                    member_note = format!("拉取团列表失败: {}", e);
                    0
                }
            };
            if group_id > 0 {
                match bbt::call(&conn, "leader/listMembers", &json!({"groupId": group_id})).await {
                    Ok(r) => {
                        members = r
                            .pointer("/result/data/list")
                            .and_then(|l| l.as_array())
                            .cloned()
                            .unwrap_or_default();
                    }
                    Err(e) => member_note = format!("拉取名册失败: {}", e),
                }
            }
        }
    } else {
        member_note = "AppState 未初始化".into();
    }
    // 附上绑定映射，前端标注「已绑定」
    let map = read_member_map(&app);

    Ok(json!({
        "wx_private": wx_private,
        "wx_group": wx_group,
        "wx_note": wx_note,
        "ilink": ilink_accounts,
        "member": members,
        "member_map": map,
    }))
}

#[tauri::command(rename_all = "snake_case")]
pub async fn channel_send_text(
    app: tauri::AppHandle,
    channel: String,
    target: String,
    text: String,
) -> Result<Value, String> {
    match channel.as_str() {
        "wx_private" | "wx_group" | "wx" => {
            crate::wxauto::send_text(&app, &target, &text).await?;
            Ok(json!({"ok": true}))
        }
        "ilink" => ilink_route_send(&app, &target, Some(&text), None).await,
        "member" => {
            let bot = read_member_map(&app)
                .get(&target)
                .and_then(|v| v.as_str())
                .ok_or("该团员还没有绑定 iLink 账号（团员二维码页扫码绑定）")?
                .to_string();
            ilink_route_send(&app, &bot, Some(&text), None).await
        }
        _ => Err(format!("未知渠道: {}", channel)),
    }
}

#[tauri::command(rename_all = "snake_case")]
pub async fn channel_send_image(
    app: tauri::AppHandle,
    channel: String,
    target: String,
    data_b64: String,
    filename: Option<String>,
) -> Result<Value, String> {
    let filename = filename.unwrap_or_else(|| "order.png".into());
    match channel.as_str() {
        "wx_private" | "wx_group" | "wx" => {
            crate::wxauto::send_image(&app, &target, &data_b64).await?;
            Ok(json!({"ok": true}))
        }
        "ilink" => ilink_route_send(&app, &target, None, Some((&data_b64, &filename))).await,
        "member" => {
            let bot = read_member_map(&app)
                .get(&target)
                .and_then(|v| v.as_str())
                .ok_or("该团员还没有绑定 iLink 账号（团员二维码页扫码绑定）")?
                .to_string();
            ilink_route_send(&app, &bot, None, Some((&data_b64, &filename))).await
        }
        _ => Err(format!("未知渠道: {}", channel)),
    }
}

/// 拉取远程图片转 base64（订单图合成用，绕开 webview canvas 跨域污染）
#[tauri::command(rename_all = "snake_case")]
pub async fn fetch_image_b64(url: String) -> Result<String, String> {
    let resp = reqwest::get(&url).await.map_err(|e| format!("拉取图片失败: {}", e))?;
    if !resp.status().is_success() {
        return Err(format!("拉取图片 HTTP {}", resp.status()));
    }
    let bytes = resp.bytes().await.map_err(|e| e.to_string())?;
    use base64::Engine as _;
    Ok(base64::engine::general_purpose::STANDARD.encode(&bytes))
}
