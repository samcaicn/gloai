mod state;
mod guard;
mod autoreply;
mod wxauto;
mod bbt;
mod bridge;
mod commands;
mod ilink;
mod ilink_session;
mod update;

use std::sync::Arc;
use tauri::Manager;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
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

            // 恢复连接信息 base_url / telephone（token 不落盘，需重新登录；
            // base_url 恢复后 autoreply 等依赖接口地址的模块在重启后仍可用）
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
