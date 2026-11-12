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

/// 深合并两个 JSON：对象递归合并，数组/标量以新值覆盖。
/// 用于「局部更新」本地配置——避免参数面板保存时抹掉 autoreply / memberqr /
/// channel_settings 等由其它页面维护的节点（0.4.0 修复的配置丢失根因）。
fn merge_json(base: &mut Value, incoming: &Value) {
    match (base, incoming) {
        (Value::Object(b), Value::Object(i)) => {
            for (k, v) in i {
                match b.get_mut(k) {
                    Some(slot) => merge_json(slot, v),
                    None => {
                        b.insert(k.clone(), v.clone());
                    }
                }
            }
        }
        (b, i) => *b = i.clone(),
    }
}

/// 局部更新本地配置（深合并）并落盘。返回合并后的完整配置。
/// 所有「只改自己那几个字段」的 UI 都应走这里，不要走 save_config 全量覆盖。
#[tauri::command(rename_all = "snake_case")]
pub async fn patch_config(state: State<'_, AppState>, value: Value) -> Result<Value, String> {
    let out = {
        let mut g = state.config.lock().unwrap();
        merge_json(&mut *g, &value);
        g.clone()
    };
    state.save_config()?;
    Ok(out)
}

/// 本地缓存体检：配置文件/备份、渠道配置、绑定关系、iLink 登录态缓存。
/// 供「渠道配置」面板展示「已缓存到本机，升级/重启不丢失」的真实依据。
#[tauri::command(rename_all = "snake_case")]
pub async fn local_cache_status(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
) -> Result<Value, String> {
    let p: std::path::PathBuf = state.config_path.lock().unwrap().clone().unwrap_or_default();
    let bak = p.with_extension("json.bak");
    let saved_at = std::fs::metadata(&p)
        .and_then(|m| m.modified())
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0);
    let g = state.config.lock().unwrap().clone();
    let accounts = app
        .try_state::<Arc<SessionManager>>()
        .map(|m| m.list_accounts())
        .unwrap_or_default();
    let active = accounts
        .iter()
        .filter(|a| !a.get("expired").and_then(|v| v.as_bool()).unwrap_or(false))
        .count();
    let last_active = accounts
        .iter()
        .filter_map(|a| a.get("last_active_ms").and_then(|v| v.as_i64()))
        .max()
        .unwrap_or(0);
    Ok(json!({
        "config_path": p.to_string_lossy(),
        "config_exists": p.exists(),
        "backup_exists": bak.exists(),
        "saved_at": saved_at,
        "channel_settings": g.get("channel_settings").cloned().unwrap_or(json!({})),
        "autoreply_saved": g.get("autoreply").is_some(),
        "memberqr_bindings": g.get("memberqr").and_then(|m| m.get("map")).and_then(|v| v.as_object()).map(|m| m.len()).unwrap_or(0),
        "ilink_total": accounts.len(),
        "ilink_active": active,
        "ilink_expired": accounts.len().saturating_sub(active),
        "ilink_last_active_ms": last_active,
        "ilink_ttl_hours": 48
    }))
}

/// 写入本地配置并落盘（全量覆盖，仅用于确实要替换整份配置的场景）
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
    c.base_url = base_url.clone();
    c.token = token.as_ref().filter(|s| !s.trim().is_empty()).cloned();
    c.telephone = telephone.clone();
    drop(c);
    // 持久化连接信息（重启后自动回复/桥等模块仍可用）：base_url / telephone 必存；
    // token 一并落盘（HMAC 信封仅防篡改、本地明文，与 member_password 同级别），
    // 重启后团长连接可直接复用、无需重新登录。
    {
        let mut g = state.config.lock().unwrap();
        if let Some(obj) = g.as_object_mut() {
            let mut conn = json!({});
            if let Some(existing) = obj.get("connection") {
                if let Some(o) = existing.as_object() {
                    conn = serde_json::Value::Object(o.clone());
                }
            }
            conn["base_url"] = json!(base_url);
            if let Some(t) = &telephone {
                conn["telephone"] = json!(t);
            }
            if let Some(tok) = &token {
                if !tok.trim().is_empty() {
                    conn["token"] = json!(tok);
                }
            }
            obj.insert("connection".into(), conn);
        }
    }
    let _ = state.save_config();
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
        password: Some(password.clone()),
    };
    let (token, account) = bbt::login(&c, &telephone, &password).await?;
    c.token = Some(token.clone());
    *state.conn.lock().unwrap() = c.clone();
    // 持久化 base_url / telephone / token / password：
    // - token 落盘使升级/重启后免重登（解决「刷新团员名册检测不到登录」）；
    // - password 落盘使 token 过期时 call_with_relogin 能自动重登，避免再次丢失登录。
    //   凭证仅存于本机 config.json（本地可信环境），与 base_url 同处理。
    {
        let mut g = state.config.lock().unwrap();
        if let Some(obj) = g.as_object_mut() {
            let mut conn = json!({});
            if let Some(existing) = obj.get("connection") {
                if let Some(o) = existing.as_object() {
                    conn = serde_json::Value::Object(o.clone());
                }
            }
            conn["base_url"] = json!(c.base_url);
            conn["telephone"] = json!(c.telephone);
            conn["token"] = json!(token);
            conn["password"] = json!(password);
            obj.insert("connection".into(), conn);
        }
    }
    let _ = state.save_config();
    Ok(json!({ "ok": true, "token": token, "account": account }))
}

/// 通用 action 调用（透传给 bbt-zb 单端点 API，便于扩展话术库等操作）
#[tauri::command(rename_all = "snake_case")]
pub async fn bbt_call(
    app: tauri::AppHandle,
    action: String,
    condition: Option<Value>,
) -> Result<Value, String> {
    call_with_relogin(&app, &action, &condition.unwrap_or(json!({}))).await
}

/// 重登串行化：团员名册一次并发拉 N 个团，若 token 恰好过期，N 个请求会
/// 同时收到「会话已失效」并各自发一次 `account/login`（互相覆盖 token、无谓
/// 打服务端写配额）。这里用一把进程级锁 + token 版本比对，把风暴合并成一次。
static RELOGIN_LOCK: std::sync::OnceLock<tokio::sync::Mutex<()>> = std::sync::OnceLock::new();

fn relogin_lock() -> &'static tokio::sync::Mutex<()> {
    RELOGIN_LOCK.get_or_init(|| tokio::sync::Mutex::new(()))
}

fn current_conn(app: &tauri::AppHandle) -> bbt::BbtConnection {
    let st = app.state::<AppState>();
    let out = st.conn.lock().unwrap_or_else(|p| p.into_inner()).clone();
    out
}

/// 统一 bbt 调用：会话失效时用本地保存的账号密码自动重登一次再重试。
/// 订单页一键拉取 / 团员名册 / 远程参数同步等所有透传动作共用，
/// 避免 token 过期后功能中断（重登失败才把「会话已失效」抛回前端）。
pub(crate) async fn call_with_relogin(
    app: &tauri::AppHandle,
    action: &str,
    condition: &Value,
) -> Result<Value, String> {
    let c = current_conn(app);
    let old_token = c.token.clone();
    match bbt::call(&c, action, condition).await {
        Ok(r) => return Ok(r),
        Err(e) if e.contains("失效") || e.contains("未登录") => {
            // 只在「重登」这一小段持锁；重试放锁外，避免 N 个请求排队等网络。
            let retry_conn = {
                let _guard = relogin_lock().lock().await;
                let cur = current_conn(app);
                // 别的并发请求已经重登成功 → 直接复用新 token，不再重复登录。
                if cur.token != old_token && cur.token.as_deref().unwrap_or("").len() > 0 {
                    cur
                } else {
                    // 从本地 config.json 读保存的账号密码（前端登录时随配置落盘）
                    let (tel, pwd) = {
                        let st = app.state::<AppState>();
                        let g = st.config.lock().unwrap_or_else(|p| p.into_inner());
                        let conn = g.get("connection").cloned().unwrap_or(json!({}));
                        (
                            conn.get("telephone").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                            conn.get("password").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                        )
                    };
                    if tel.is_empty() || pwd.is_empty() {
                        return Err(e);
                    }
                    let base = cur.base_url.clone();
                    // 重新登录刷新 token（密码错误等失败场景原样抛回）
                    let mut fresh = bbt::BbtConnection {
                        base_url: base.clone(),
                        token: None,
                        telephone: Some(tel.clone()),
                        password: Some(pwd.clone()),
                    };
                    let (token, _account) =
                        bbt::login(&fresh, &tel, &pwd).await.map_err(|_| e.clone())?;
                    fresh.token = Some(token.clone());
                    {
                        let st = app.state::<AppState>();
                        let mut cc = st.conn.lock().unwrap_or_else(|p| p.into_inner());
                        cc.base_url = base;
                        cc.token = Some(token.clone());
                        cc.telephone = Some(tel);
                    }
                    // 新 token 必须同步落盘：否则重启后又拿旧 token 走一遍重登，
                    // 「本地缓存 / 免登录」在升级后形同虚设。
                    {
                        let st = app.state::<AppState>();
                        {
                            let mut g = st.config.lock().unwrap_or_else(|p| p.into_inner());
                            if let Some(obj) = g.as_object_mut() {
                                let mut conn = obj
                                    .get("connection")
                                    .and_then(|v| v.as_object())
                                    .cloned()
                                    .unwrap_or_default();
                                conn.insert("token".into(), json!(token));
                                obj.insert("connection".into(), Value::Object(conn));
                            }
                        }
                        let _ = st.save_config();
                    }
                    fresh
                }
            };
            bbt::call(&retry_conn, action, condition).await
        }
        Err(e) => Err(e),
    }
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
pub async fn fetch_remote_config(app: tauri::AppHandle) -> Result<Value, String> {
    let state = app.state::<AppState>();
    let c = state.conn.lock().unwrap().clone();
    if c.base_url.is_empty() {
        return Err("未配置接口地址".into());
    }
    let data = call_with_relogin(&app, "chat/configGet", &json!({})).await?;
    let data = bbt::data_of(&data)?;
    // 远程值合并进本地配置（connection 字段保留）
    {
        let st = app.state::<AppState>();
        let mut g = st.config.lock().unwrap();
        if let (Some(obj), Some(src)) = (g.as_object_mut(), data.as_object()) {
            for (k, v) in src {
                obj.insert(k.clone(), v.clone());
            }
        }
        let _ = st.save_config();
    }
    Ok(data)
}

/// 推送聊天参数到 bbt-zb（chat/configSave）
#[tauri::command(rename_all = "snake_case")]
pub async fn push_remote_config(
    app: tauri::AppHandle,
    value: Value,
) -> Result<Value, String> {
    let state = app.state::<AppState>();
    let c = state.conn.lock().unwrap().clone();
    if c.base_url.is_empty() {
        return Err("未配置接口地址".into());
    }
    let condition = bbt::build_config_save_condition(&value);
    let result = call_with_relogin(&app, "chat/configSave", &condition).await?;
    bbt::data_of(&result)
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
    member_id: Option<String>,
) -> Result<Value, String> {
    let (login_id, content, svg) = mgr.begin_login(member_id).await?;
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
    // 把现有配置序列化成 JSON，然后 merge incoming（前端可能只传部分字段）
    let mut merged = serde_json::to_value(&cur).unwrap_or(json!({}));
    if let Some(obj) = merged.as_object_mut() {
        if let Some(incoming_obj) = value.as_object() {
            for (k, v) in incoming_obj {
                obj.insert(k.clone(), v.clone());
            }
        }
    }
    let incoming: crate::autoreply::AutoReplyConfig =
        serde_json::from_value(merged).map_err(|e| format!("配置格式错误: {}", e))?;
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

/// 发送安全状态：微信通道急停 / 每小时预算用量 / 允许时段 / 连败计数 / 最近拦截原因。
#[tauri::command(rename_all = "snake_case")]
pub async fn autoreply_safety_status(
    ar: State<'_, Arc<crate::autoreply::AutoReplyState>>,
) -> Result<Value, String> {
    Ok(ar.safety_status())
}

/// 人工确认微信客户端恢复后解除急停（同时清零连败计数），立即恢复自动回复。
#[tauri::command(rename_all = "snake_case")]
pub async fn autoreply_safety_resume(
    ar: State<'_, Arc<crate::autoreply::AutoReplyState>>,
) -> Result<Value, String> {
    ar.resume_wx();
    Ok(ar.safety_status())
}

/// 一次性临时放开发送时段限制 N 分钟（默认 90），**到点自动失效**回落到常规
/// active_hours，无需人工改回。用于调试/验收，根治「临时窗口跨天遗留」：
/// 11-10 凌晨把 active_hours 改成 00:00–00:40 后没恢复，11-11 全天只有凌晨
/// 40 分钟能回复，用户晚间发的付款截图被发送安全闸门直接拦掉。
#[tauri::command(rename_all = "snake_case")]
pub async fn autoreply_temp_allow(
    app: tauri::AppHandle,
    ar: State<'_, Arc<crate::autoreply::AutoReplyState>>,
    minutes: Option<u64>,
) -> Result<Value, String> {
    let mins = minutes.unwrap_or(90).clamp(1, 24 * 60);
    let until = (crate::autoreply::now_ms_pub() / 1000) as u64 + mins * 60;
    let cur = {
        let mut guard = ar.config.lock().unwrap();
        guard.temp_allow_until = until;
        guard.clone()
    };
    let state = app.state::<AppState>();
    {
        let mut g = state.config.lock().unwrap();
        if let Some(obj) = g.as_object_mut() {
            obj.insert("autoreply".into(), serde_json::to_value(&cur).unwrap_or(json!({})));
        }
    }
    let _ = state.save_config();
    let mut v = ar.safety_status();
    if let Some(o) = v.as_object_mut() {
        o.insert("minutes".into(), json!(mins));
    }
    Ok(v)
}

/// AI 消息自测：分步体检 登录态→团长 AI 代聊开关→配额→渠道/团员→（实发）向团长发消息→AI 回复。
/// 不做 role 预检：PC 端登录团长账号是正常态，每个渠道各代表 1 个团员。
/// send=false 只查链路不消耗平台配额；send=true 真发一条测试问题。
#[tauri::command(rename_all = "snake_case")]
pub async fn autoreply_self_test(
    app: tauri::AppHandle,
    question: Option<String>,
    send: Option<bool>,
) -> Result<Value, String> {
    let q = question.unwrap_or_default();
    Ok(crate::autoreply::self_test(&app, &q, send.unwrap_or(true)).await)
}

/// 注入一条模拟客户消息走完整自动回复管线（channel=test：AI 回复只记日志、不外发）。
#[tauri::command(rename_all = "snake_case")]
pub async fn autoreply_inject(
    app: tauri::AppHandle,
    text: String,
    peer: Option<String>,
    group: Option<bool>,
) -> Result<Value, String> {
    let reply = crate::autoreply::inject(&app, text, peer.unwrap_or_default(), group.unwrap_or(false))
        .await?;
    Ok(json!({ "ok": true, "reply": reply }))
}

// ============================================================================
//  PC 端统一 AI 消息网关（0.3.0）
//  多个微信私聊/群聊/iLink 渠道 = 多个团员，消息都只向同一个团长发，
//  由团长 AI 统一回复；channel/target 只用于「哪个渠道来的就回哪个渠道」。
// ============================================================================

/// 网关状态：登录态 / 账号角色（仅展示）、AI 代聊开关、今日配额、渠道=团员数量。
#[tauri::command(rename_all = "snake_case")]
pub async fn ai_gateway_status(app: tauri::AppHandle) -> Result<Value, String> {
    Ok(crate::ai_gateway::status(&app).await)
}

/// 手动解除「服务端写故障」熔断（后端写入配额重置后点「立即重试」，不必等满退避）。
#[tauri::command(rename_all = "snake_case")]
pub async fn ai_gateway_fault_reset() -> Result<Value, String> {
    crate::bbt::reset_fault();
    Ok(crate::bbt::fault_status())
}

/// 统一入口：向团长发一条消息，返回团长的 AI 回复。
/// channel/target 仅用于日志标记与回发路由；auto_send=true 时按渠道原路回发。
#[tauri::command(rename_all = "snake_case")]
pub async fn ai_gateway_send(
    app: tauri::AppHandle,
    text: String,
    channel: Option<String>,
    target: Option<String>,
    auto_send: Option<bool>,
    member_id: Option<Value>,
) -> Result<Value, String> {
    let ch = channel.unwrap_or_else(|| "test".into());
    let to = target.unwrap_or_default();
    let route = if to.is_empty() { ch.clone() } else { format!("{}|{}", ch, to) };
    // member_id：按渠道指定团员身份（每个微信私聊/群聊/iLink 渠道 = 1 个团员），
    // 可用于「换个团员 id 继续请求」和后端 PC 专用聊天接口。
    let ans = crate::ai_gateway::ask_leader_ex(&app, &text, &route, member_id.clone()).await?;
    let mut sent = false;
    let mut send_err = String::new();
    if auto_send.unwrap_or(false) && !to.is_empty() {
        match crate::ai_gateway::dispatch(&app, &ch, &to, &ans.reply).await {
            Ok(_) => sent = true,
            Err(e) => send_err = e,
        }
    }
    Ok(json!({
        "ok": true,
        "reply": ans.reply,
        "session_id": ans.session_id,
        "quota_left": ans.quota_left,
        "auto": ans.auto,
        "channel": ch,
        "target": to,
        "member_id": member_id,
        "sent": sent,
        "send_error": send_err
    }))
}

/// 网关日志（尾部 limit 条）
#[tauri::command(rename_all = "snake_case")]
pub async fn ai_gateway_logs(
    ag: State<'_, Arc<crate::ai_gateway::AiGatewayState>>,
    limit: Option<usize>,
) -> Result<Value, String> {
    let lg = ag.logs.lock().unwrap();
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
//  团员 ↔ 微信私聊/群聊 配对（每个微信会话 = 1 个团员；界面手动配对并落盘）
//  配置节点：config.wechat_pairing.map : member_id -> {wxid, wxname, is_group, member_name}
//  用途：入站微信消息携带的是 wxid/@chatroom，配对后可精确反查「该团员」，
//        直接服务于 payment_verify「根据该团员的订单值比对图片支付金额」。
// ============================================================================

/// 读取团员→微信会话配对（member_id -> {wxid, wxname, is_group, member_name}）
#[tauri::command(rename_all = "snake_case")]
pub async fn wechat_pairing_get(app: tauri::AppHandle) -> Result<Value, String> {
  let state = app.state::<AppState>();
  let g = state.config.lock().unwrap();
  let map = g
    .get("wechat_pairing")
    .and_then(|v| v.get("map"))
    .cloned()
    .unwrap_or(json!({}));
  Ok(map)
}

/// 写入团员→微信会话配对（传入完整 map 对象覆盖保存）
#[tauri::command(rename_all = "snake_case")]
pub async fn wechat_pairing_set(app: tauri::AppHandle, map: Value) -> Result<Value, String> {
  let state = app.state::<AppState>();
  {
    let mut g = state.config.lock().unwrap();
    if let Some(obj) = g.as_object_mut() {
      let entry = obj.entry("wechat_pairing".to_string()).or_insert(json!({}));
      if let Some(e) = entry.as_object_mut() {
        e.insert("map".into(), map.clone());
      }
    }
  }
  let _ = state.save_config();
  Ok(map)
}

// ============================================================================
//  图片支付校验 · 本地订单种子（leader/orders 无权限时的本地回退）

/// 写入本地订单种子：后端 leader/orders 需团长 token，而 PC 以团员账号登录拿不到订单，
/// 故用本地种子让确定性支付校验可用。参数 `orders` 为 JSON 字符串，形态
/// `[{buyerName, amountYuan}, ...]` 或 `{"orders":[...]}`。
/// （用 String 而非 Value 接收，规避 Tauri v2 对 Value 参数的「bytes payload」序列化冲突。）
#[tauri::command(rename_all = "snake_case")]
pub async fn payment_verify_seed_set(app: tauri::AppHandle, orders: String) -> Result<Value, String> {
    let v: Value = serde_json::from_str(&orders).map_err(|e| format!("订单 JSON 解析失败: {}", e))?;
    let n = crate::payment_verify::save_seed_orders(&app, &v)?;
    Ok(json!({ "ok": true, "count": n }))
}

/// 读取本地订单种子（无则返回空数组）。
#[tauri::command(rename_all = "snake_case")]
pub async fn payment_verify_seed_get(app: tauri::AppHandle) -> Result<Value, String> {
    let dir = app
        .path()
        .app_data_dir()
        .map_err(|e| format!("取 app_data_dir 失败: {}", e))?;
    let p = dir.join("payment_orders_seed.json");
    let v = if p.exists() {
        serde_json::from_str(&std::fs::read_to_string(&p).unwrap_or_default())
            .unwrap_or(json!([]))
    } else {
        json!([])
    };
    Ok(json!({ "ok": true, "seed": v }))
}

/// 只读诊断：直接跑一遍图片支付校验（OCR 文本 → 订单比对），返回判定，
/// 不调用 AI 网关（不写 chat/send）、不碰微信，用于验证订单种子/比对逻辑。
#[tauri::command(rename_all = "snake_case")]
pub async fn payment_verify_test(
    app: tauri::AppHandle,
    ocr_text: String,
    peer: String,
    sender: String,
) -> Result<Value, String> {
    match crate::payment_verify::verify_image(&app, &ocr_text, &peer, &sender, false).await {
        Some(v) => Ok(json!({
            "ok": true,
            "verdict": "payment_verify",
            "matched": v.matched,
            "paid": v.paid,
            "expected": v.expected,
            "reply": v.reply
        })),
        None => Ok(json!({
            "ok": true,
            "verdict": null,
            "note": "非支付截图 / 未匹配到订单 / 订单接口无权限且无种子 → autoreply 回确定性核对提示（payment_image_only=true 时不再走 AI）"
        })),
    }
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

/// 图片 OCR 验收入口：从微信本地库取最近一张图片，离线 OCR 后返回识别文本。
/// inject=true 时把识别结果按「图片消息」注入自动回复管线（团长 AI → 原路回发）。
/// 返回 {ok, peer, local_id, path, text, msg}。
#[tauri::command(rename_all = "snake_case")]
pub async fn wxauto_ocr_test(
    app: tauri::AppHandle,
    peer: Option<String>,
    inject: Option<bool>,
) -> Result<Value, String> {
    crate::wxauto::ocr_test(&app, peer, inject).await
}

/// 直接发送一条文本到指定微信会话（不经过 AI），用于验收「自动切到前台」回发。
/// peer 传 wxid / @chatroom；worker 内部会反查显示名并强制把目标会话切到前台再发。
#[tauri::command(rename_all = "snake_case")]
pub async fn wxauto_send_test(
    app: tauri::AppHandle,
    peer: String,
    text: String,
) -> Result<Value, String> {
    match crate::wxauto::send_text(&app, &peer, &text).await {
        Ok(_) => Ok(json!({ "ok": true, "peer": peer, "msg": "已发送（目标会话应已被切到前台）" })),
        Err(e) => Ok(json!({ "ok": false, "peer": peer, "msg": e })),
    }
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
pub async fn channel_targets(app: tauri::AppHandle, force: Option<bool>) -> Result<Value, String> {
    use std::collections::HashSet;
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

    // 2) iLink 账号快照（后面与「已绑定团员映射」合并成下拉目标）
    let (ilink_raw, mut ilink_note): (Vec<Value>, String) =
        match app.try_state::<Arc<SessionManager>>() {
            Some(mgr) => (mgr.list_accounts(), String::new()),
            None => (Vec::new(), "iLink 会话管理未初始化（管理器未注册）".into()),
        };

    // 3) 团员名册：统一走本地缓存（多团并发拉取 / TTL 内存命中 / 磁盘持久化 /
    //    离线回退）。以前这里每次调用都串行打 ledger + N 次 listMembers，
    //    订单页 / 互动页 / 配对页各刷一次 = 3×(1+N) 次 RTT，网络一抖就「识别不到」。
    let roster = crate::roster_cache::get(&app, force.unwrap_or(false)).await;
    let members: Vec<Value> = roster.members;
    let member_note = roster.note;
    let member_cache = roster.cache;
    // 4) iLink 下拉目标：以「已绑定团员」映射为准（扫码成功即由后端落盘），
    //    叠加 accounts.json 里的在线账号状态。
    //    这样即便 accounts.json 被清、会话过期或账号不在线，订单页/互动页下拉
    //    仍能列出「哪个团员已绑定」并选中发送，而不是只有一句「无已绑定账号」。
    let map = read_member_map(&app);
    let acc_by_bot: std::collections::HashMap<String, &Value> = ilink_raw
        .iter()
        .filter_map(|a| {
            a.get("bot_id")
                .and_then(|v| v.as_str())
                .map(|b| (b.to_string(), a))
        })
        .collect();
    let mut seen: HashSet<String> = HashSet::new();
    let mut ilink_accounts: Vec<Value> = Vec::new();

    // 4a) 已绑定团员优先：label 用团员名，value 用 bot_id（发送路由需要 bot_id）
    for (member_id, bot_val) in &map {
        let Some(bot) = bot_val.as_str().filter(|s| !s.is_empty()) else {
            continue;
        };
        let acc = acc_by_bot.get(bot).copied();
        let status = acc
            .and_then(|a| a.get("status"))
            .and_then(|v| v.as_str())
            .unwrap_or("offline");
        let expired = acc
            .and_then(|a| a.get("expired"))
            .and_then(|v| v.as_bool())
            .unwrap_or(false)
            || status == "session_expired";
        let name = members
            .iter()
            .find(|m| {
                m.get("id").map(|v| match v {
                    Value::String(s) => s == member_id,
                    Value::Number(n) => n.to_string() == *member_id,
                    _ => false,
                }).unwrap_or(false)
            })
            .and_then(|m| {
                m.get("name")
                    .or_else(|| m.get("realName"))
                    .or_else(|| m.get("nickname"))
                    .or_else(|| m.get("telephoneMask"))
            })
            .and_then(|v| v.as_str())
            .filter(|s| !s.is_empty())
            .unwrap_or(member_id.as_str())
            .to_string();
        ilink_accounts.push(json!({
            "id": bot,
            "bot_id": bot,
            "user_id": acc.and_then(|a| a.get("user_id")).and_then(|v| v.as_str()).unwrap_or(""),
            "member_id": member_id,
            "name": name,
            "label": name,
            "status": status,
            "expired": expired,
            "online": status == "running",
            "last_active_ms": acc.and_then(|a| a.get("last_active_ms")).and_then(|v| v.as_i64()).unwrap_or(0),
            "ttl_hours": 48,
        }));
        seen.insert(bot.to_string());
    }

    // 4b) 有账号但没绑定映射的（历史扫码、或映射丢失）：仍列出，保证不漏
    for a in &ilink_raw {
        let Some(bot) = a.get("bot_id").and_then(|v| v.as_str()).filter(|s| !s.is_empty()) else {
            continue;
        };
        if seen.contains(bot) {
            continue;
        }
        let label = a
            .get("label")
            .and_then(|v| v.as_str())
            .filter(|s| !s.is_empty())
            .or_else(|| a.get("user_id").and_then(|v| v.as_str()))
            .unwrap_or(bot)
            .to_string();
        let status = a.get("status").and_then(|v| v.as_str()).unwrap_or("offline");
        ilink_accounts.push(json!({
            "id": bot,
            "bot_id": bot,
            "user_id": a.get("user_id").and_then(|v| v.as_str()).unwrap_or(""),
            "member_id": "",
            "name": label,
            "label": label,
            "status": status,
            "expired": a.get("expired").and_then(|v| v.as_bool()).unwrap_or(false) || status == "session_expired",
            "online": status == "running",
            "last_active_ms": a.get("last_active_ms").and_then(|v| v.as_i64()).unwrap_or(0),
            "ttl_hours": 48,
        }));
        seen.insert(bot.to_string());
    }

    if ilink_accounts.is_empty() && ilink_note.is_empty() {
        ilink_note = "尚无已绑定团员（到「团员二维码」页扫码，扫码成功即自动绑定）".into();
    } else if ilink_accounts.is_empty() {
        ilink_note = format!("{}；尚无已绑定团员", ilink_note);
    }

    Ok(json!({
        "wx_private": wx_private,
        "wx_group": wx_group,
        "wx_note": wx_note,
        "ilink": ilink_accounts,
        "ilink_note": ilink_note,
        "member": members,
        "member_note": member_note,
        "member_cache": member_cache,
        "member_map": map,
    }))
}

/// 团员名册（含团列表 / 按团分组 / 合并去重成员 / 缓存状态）。
/// 名册页切团是纯内存操作；force=true 才同步回源，失败自动回退本地缓存。
#[tauri::command(rename_all = "snake_case")]
pub async fn member_roster(app: tauri::AppHandle, force: Option<bool>) -> Result<Value, String> {
    let r = crate::roster_cache::get(&app, force.unwrap_or(false)).await;
    Ok(json!({
        "groups": r.groups,
        "by_group": r.by_group,
        "members": r.members,
        "note": r.note,
        "cache": r.cache,
    }))
}

/// 名册缓存诊断状态（不触网）。
#[tauri::command(rename_all = "snake_case")]
pub async fn roster_cache_status(app: tauri::AppHandle) -> Result<Value, String> {
    Ok(crate::roster_cache::status(&app))
}

/// 清空名册缓存（换账号 / 排障用），下次取名册会重新回源。
#[tauri::command(rename_all = "snake_case")]
pub async fn roster_cache_clear(app: tauri::AppHandle) -> Result<Value, String> {
    crate::roster_cache::clear(&app);
    Ok(json!({ "ok": true }))
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
    let client = reqwest::Client::new();
    let resp = client
        .get(&url)
        .timeout(std::time::Duration::from_secs(15))
        .send()
        .await
        .map_err(|e| format!("拉取图片失败: {}", e))?;
    if !resp.status().is_success() {
        return Err(format!("拉取图片 HTTP {}", resp.status()));
    }
    let bytes = resp.bytes().await.map_err(|e| e.to_string())?;
    use base64::Engine as _;
    Ok(base64::engine::general_purpose::STANDARD.encode(&bytes))
}
