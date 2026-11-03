mod state;
mod guard;
mod util;
mod autoreply;
mod wxauto;
mod bbt;
mod bridge;
mod commands;
mod ilink;
mod ilink_session;
mod update;

use std::sync::Arc;
use tauri::{Emitter, Manager};

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    // ── 隐藏 cmd 黑窗：分离控制台，不再弹黑窗（weauto 用 --noconsole，这边运行时 FreeConsole）──
    hide_console_window();

    tauri::Builder::default()
        .setup(|app| {
            let handle = app.handle().clone();
            // weauto 模式：启动时如有待应用升级包，拉起替换脚本（等本进程退出后
            // 覆盖 exe 并重启），随后本进程立即退出。
            match update::apply_pending_update(&handle) {
                Ok(true) => {
                    app.handle().exit(0);
                    return Ok(());
                }
                Ok(false) => {}
                Err(e) => {
                    eprintln!("应用待升级包失败: {}", e);
                }
            }
            // 防启动竞态：Tauri 2 的配置窗口先于 setup 完成加载，页面会立刻 invoke
            // 命令（bridge_get_config 等）。State 参数在命令解析期提取，state 未注册
            // 会直接 panic（CI 产物自测复现）。故先以默认值 manage 全部 state，
            // 再读盘填充配置。
            let cfg_dir = handle
                .path()
                .app_config_dir()
                .unwrap_or_else(|_| std::env::temp_dir());
            let sm = Arc::new(ilink_session::SessionManager::new(handle.clone(), cfg_dir.clone()));
            let bs = Arc::new(bridge::BridgeState::new());
            let ars = Arc::new(autoreply::AutoReplyState::new());
            let ws = Arc::new(wxauto::WxAutoState::new());
            app.manage(sm.clone());
            app.manage(bs.clone());
            app.manage(ars.clone());
            app.manage(ws.clone());

            // 启动时把磁盘上的本地配置载入内存
            let state = app.state::<state::AppState>();
            state.init(&handle);

            // 恢复连接信息 base_url / telephone / token：
            // token 现在随 config 落盘（见 commands::login），重启/升级后免重登，
            // 团员名册等透传动作可直接用会话态；token 若已过期，call_with_relogin
            // 会用落盘的 password 自动重登。
            if let Some(conn_v) = state.config.lock().unwrap().get("connection").cloned() {
                let mut c = state.conn.lock().unwrap();
                c.base_url = conn_v
                    .get("base_url")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string();
                c.telephone = conn_v
                    .get("telephone")
                    .and_then(|v| v.as_str())
                    .map(|s| s.to_string());
                if let Some(t) = conn_v.get("token").and_then(|v| v.as_str()) {
                    if !t.is_empty() {
                        c.token = Some(t.to_string());
                    }
                }
            }

            // iLink 多账号会话管理：载入已登录账号并恢复会话（本地保活）
            sm.init();

            // 防破解（weauto 移植）：发行版输出设备指纹与加固态（只打哈希，无 PII）
            eprintln!(
                "[guard] release={} device={:?} rollback={}",
                guard::is_release(),
                guard::device_id(&cfg_dir),
                guard::rollback_count(&cfg_dir),
            );

            // 团长聊天桥：配置持久化在 config.json 的 bridge 字段
            let bridge_cfg: bridge::BridgeConfig = serde_json::from_value(
                state.config.lock().unwrap().get("bridge").cloned().unwrap_or(serde_json::json!({})),
            )
            .unwrap_or_default();
            *bs.config.lock().unwrap() = bridge_cfg;
            // 微信自动回复：配置持久化在 config.json 的 autoreply 字段
            let ar_cfg: autoreply::AutoReplyConfig = serde_json::from_value(
                state.config.lock().unwrap().get("autoreply").cloned().unwrap_or(serde_json::json!({})),
            )
            .unwrap_or_default();
            *ars.config.lock().unwrap() = ar_cfg;
            // 顺序：state 已全部注册，此时拉起桥任务（任务内部每轮从 state 取最新配置）
            bridge::ensure_started(handle.clone());
            autoreply::ensure_started(handle.clone());

            // 微信 wxauto 通道：配置持久化在 config.json 的 wxauto 字段
            let wx_cfg: wxauto::WxAutoConfig = serde_json::from_value(
                state.config.lock().unwrap().get("wxauto").cloned().unwrap_or(serde_json::json!({})),
            )
            .unwrap_or_default();
            *ws.config.lock().unwrap() = wx_cfg.clone();
            if wx_cfg.enabled {
                wxauto::ensure_started(handle.clone());
            }

            // 自动静默更新（抄 weauto deferred autoupdate）：
            //   启动 15s 后首轮检查（避开 apply_pending 的替换窗口），此后每 6h 轮询；
            //   发现新版 → 后台下载 + Ed25519 验签 + 暂存 staged_update/ → emit 事件
            //   「重启后自动生效」；不打断当前使用，下次启动 apply_pending_update 自动替换。
            {
                let h = handle.clone();
                tauri::async_runtime::spawn(async move {
                    const FIRST_DELAY_SECS: u64 = 15;
                    const POLL_INTERVAL_SECS: u64 = 6 * 3600;
                    let base = update::DEFAULT_UPDATE_BASE;
                    let cur = h.package_info().version.to_string();
                    let mut first = true;
                    loop {
                        let wait = if first { FIRST_DELAY_SECS } else { POLL_INTERVAL_SECS };
                        tokio::time::sleep(std::time::Duration::from_secs(wait)).await;
                        first = false;
                        match update::check_update(base, &cur).await {
                            Ok(v) if v.get("update_available").and_then(|b| b.as_bool()).unwrap_or(false) => {
                                eprintln!("[auto-update] 发现新版 {}，后台静默下载", v["latest"]);
                                match update::prepare_update(base, &h).await {
                                    Ok(r) => {
                                        let _ = h.emit(
                                            "update-event",
                                            serde_json::json!({
                                                "kind": "staged",
                                                "version": r.get("version").cloned().unwrap_or(serde_json::json!(cur)),
                                                "message": r.get("message").cloned().unwrap_or_else(|| serde_json::json!("新版本已下载并通过验签，重启后自动替换并升级")),
                                            }),
                                        );
                                    }
                                    Err(e) => eprintln!("[auto-update] 静默下载失败: {}", e),
                                }
                            }
                            Ok(_) => eprintln!("[auto-update] 已是最新 {}", cur),
                            Err(e) => eprintln!("[auto-update] 检查失败: {}", e),
                        }
                    }
                });
            }
            Ok(())
        })
        .manage(state::AppState::new())
        .invoke_handler(tauri::generate_handler![
            commands::load_config,
            commands::save_config,
            commands::reset_config,
            commands::set_connection,
            commands::login,
            commands::bbt_call,
            commands::test_connection,
            commands::fetch_remote_config,
            commands::push_remote_config,
            commands::update_check,
            commands::update_prepare,
            // iLink 聊天
            commands::ilink_begin_login,
            commands::ilink_poll_login,
            commands::ilink_cancel_login,
            commands::ilink_list_accounts,
            commands::ilink_remove_account,
            commands::ilink_send_text,
            commands::ilink_send_image,
            commands::ilink_send_voice,
            commands::ilink_broadcast_text,
            commands::ilink_local_history,
            commands::ilink_read_media,
            // 团长聊天桥
            commands::bridge_get_config,
            commands::bridge_set_config,
            commands::bridge_set_enabled,
            // 微信自动回复
            commands::autoreply_get_config,
            commands::autoreply_set_config,
            commands::autoreply_set_enabled,
            commands::autoreply_logs,
            // 团员 iLink 二维码（hub 成员映射）
            commands::memberqr_get_map,
            commands::memberqr_set_map,
            // 微信 wxauto 通道
            commands::wxauto_get_config,
            commands::wxauto_set_config,
            commands::wxauto_start,
            commands::wxauto_stop,
            commands::wxauto_logs,
            // 统一渠道路由（订单消息 / 互动消息）
            commands::channel_targets,
            commands::channel_send_text,
            commands::channel_send_image,
            commands::fetch_image_b64,
        ])
        .run(tauri::generate_context!())
        .expect("error while running bbt-zb-tauri");
}

/// 隐藏 cmd 黑窗：分离本进程的控制台。weauto 用 PyInstaller --noconsole 解决，
/// Tauri 这边运行时调 kernel32!FreeConsole，把控制台窗口关掉。
fn hide_console_window() {
    #[cfg(windows)]
    {
        #[link(name = "kernel32")]
        extern "system" {
            fn FreeConsole() -> i32;
        }
        unsafe {
            let _ = FreeConsole();
        }
    }
}
