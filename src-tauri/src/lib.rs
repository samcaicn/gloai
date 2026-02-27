mod cowork;
mod crypto;
mod database;
mod dialog;
mod filesystem;
mod goclaw;
mod im;
mod im_gateway;
mod logger;
mod scheduler;
mod shell;
mod skills;
mod storage;
mod system;
mod tuptup;
mod update_manager;

use std::sync::{Arc, Mutex as StdMutex};
use tauri::{AppHandle, Emitter, Manager, State};
use tokio::sync::Mutex as TokioMutex;

pub use cowork::*;
pub use crypto::*;
pub use database::*;
pub use dialog::*;
pub use filesystem::*;
pub use goclaw::*;
pub use im_gateway::*;
pub use logger::*;
pub use scheduler::*;
pub use shell::*;
pub use skills::*;
pub use storage::*;
pub use system::*;
pub use tuptup::*;
pub use update_manager::*;

struct AppState {
    storage: Storage,
    kv_store: KvStore,
    skills_manager: Arc<TokioMutex<SkillsManager>>,
    database: Arc<TokioMutex<Database>>,
    system_manager: Arc<TokioMutex<SystemManager>>,
    goclaw_manager: Arc<TokioMutex<GoClawManager>>,
    cowork_manager: Arc<TokioMutex<CoworkManager>>,
    scheduler: Arc<TokioMutex<Scheduler>>,
    logger: Arc<TokioMutex<Logger>>,
    tuptup_service: Arc<TokioMutex<TuptupService>>,
}

#[tauri::command]
async fn initialize_app(app: AppHandle) -> Result<(), String> {
    println!("[App] Initializing application...");
    let state = app.state::<AppState>();

    let mut system_manager = state.system_manager.lock().await;
    system_manager.set_app_handle(app.clone());

    let goclaw_manager = state.goclaw_manager.lock().await;
    if let Err(e) = goclaw_manager.auto_start_if_enabled().await {
        println!("[App] GoClaw auto-start failed: {}", e);
    }

    println!("[App] Application initialized successfully");
    Ok(())
}

#[tauri::command]
async fn make_http_request(
    url: String,
    method: String,
    headers: std::collections::HashMap<String, String>,
    body: Option<String>,
) -> Result<serde_json::Value, String> {
    use reqwest::Client;

    let client = Client::new();
    let mut request = match method.as_str() {
        "GET" => client.get(&url),
        "POST" => client.post(&url),
        "PUT" => client.put(&url),
        "DELETE" => client.delete(&url),
        _ => return Err(format!("Unsupported method: {}", method)),
    };

    // 添加请求头
    for (key, value) in headers {
        request = request.header(key, value);
    }

    // 添加请求体
    if let Some(body_str) = body {
        request = request.body(body_str);
    }

    // 发送请求
    let response = request
        .send()
        .await
        .map_err(|e| format!("Request failed: {}", e))?;

    // 检查响应状态
    if !response.status().is_success() {
        let status = response.status();
        let text = response.text().await.unwrap_or_default();
        return Err(format!("HTTP {}: {}", status, text));
    }

    // 解析响应体
    let json: serde_json::Value = response
        .json()
        .await
        .map_err(|e| format!("Failed to parse JSON: {}", e))?;
    Ok(json)
}

#[tauri::command]
async fn kv_get(key: String, state: State<'_, AppState>) -> Result<Option<String>, String> {
    state.kv_store.get(&key).map_err(|e| e.to_string())
}

#[tauri::command]
async fn kv_set(key: String, value: String, state: State<'_, AppState>) -> Result<(), String> {
    state.kv_store.set(&key, &value).map_err(|e| e.to_string())
}

#[tauri::command]
async fn kv_remove(key: String, state: State<'_, AppState>) -> Result<(), String> {
    state.kv_store.remove(&key).map_err(|e| e.to_string())
}

#[tauri::command]
async fn skills_list(state: State<'_, AppState>) -> Result<Vec<Skill>, String> {
    let manager = state.skills_manager.lock().await;
    manager.load_skills().await.map_err(|e| e.to_string())
}

#[tauri::command]
async fn skills_enable(id: String, state: State<'_, AppState>) -> Result<(), String> {
    let manager = state.skills_manager.lock().await;
    manager
        .set_enabled(&id, true)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn skills_disable(id: String, state: State<'_, AppState>) -> Result<(), String> {
    let manager = state.skills_manager.lock().await;
    manager
        .set_enabled(&id, false)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn system_enable_auto_start(enable: bool, state: State<'_, AppState>) -> Result<(), String> {
    let manager = state.system_manager.lock().await;
    manager.enable_auto_start(enable)
}

#[tauri::command]
async fn system_is_auto_start_enabled(state: State<'_, AppState>) -> Result<bool, String> {
    let manager = state.system_manager.lock().await;
    manager.is_auto_start_enabled()
}

#[tauri::command]
async fn system_get_app_version(state: State<'_, AppState>) -> Result<String, String> {
    let manager = state.system_manager.lock().await;
    Ok(manager.get_app_version())
}

#[tauri::command]
async fn goclaw_get_config(state: State<'_, AppState>) -> Result<GoClawConfig, String> {
    let manager = state.goclaw_manager.lock().await;
    Ok(manager.get_config())
}

#[tauri::command]
async fn goclaw_set_config(config: GoClawConfig, state: State<'_, AppState>) -> Result<(), String> {
    let manager = state.goclaw_manager.lock().await;
    manager.set_config(config).map_err(|e| e.to_string())
}

#[tauri::command]
async fn goclaw_start(state: State<'_, AppState>) -> Result<(), String> {
    let manager = state.goclaw_manager.lock().await;
    manager.start().await.map_err(|e| e.to_string())
}

#[tauri::command]
async fn goclaw_stop(state: State<'_, AppState>) -> Result<(), String> {
    let manager = state.goclaw_manager.lock().await;
    manager.stop().await.map_err(|e| e.to_string())
}

#[tauri::command]
async fn goclaw_restart(state: State<'_, AppState>) -> Result<(), String> {
    let manager = state.goclaw_manager.lock().await;
    manager.restart().await.map_err(|e| e.to_string())
}

#[tauri::command]
async fn goclaw_is_running(state: State<'_, AppState>) -> Result<bool, String> {
    let manager = state.goclaw_manager.lock().await;
    Ok(manager.is_running())
}

#[tauri::command]
async fn goclaw_get_status(state: State<'_, AppState>) -> Result<GoClawStatus, String> {
    let manager = state.goclaw_manager.lock().await;
    Ok(manager.get_status())
}

#[tauri::command]
async fn goclaw_connect(state: State<'_, AppState>) -> Result<(), String> {
    let manager = state.goclaw_manager.lock().await;
    manager.connect_websocket().await.map_err(|e| e.to_string())
}

#[tauri::command]
async fn goclaw_disconnect(state: State<'_, AppState>) -> Result<(), String> {
    let manager = state.goclaw_manager.lock().await;
    manager.disconnect_websocket().await;
    Ok(())
}

#[tauri::command]
async fn goclaw_request(
    method: String,
    params: serde_json::Value,
    state: State<'_, AppState>,
) -> Result<serde_json::Value, String> {
    let manager = state.goclaw_manager.lock().await;
    manager
        .request(method, params)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn goclaw_send_message(
    content: String,
    state: State<'_, AppState>,
) -> Result<serde_json::Value, String> {
    let manager = state.goclaw_manager.lock().await;
    manager
        .send_message(content)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn goclaw_list_sessions(state: State<'_, AppState>) -> Result<serde_json::Value, String> {
    let manager = state.goclaw_manager.lock().await;
    manager.list_sessions().await.map_err(|e| e.to_string())
}

#[tauri::command]
async fn cowork_list_sessions(state: State<'_, AppState>) -> Result<Vec<CoworkSession>, String> {
    let manager = state.cowork_manager.lock().await;
    manager.list_sessions().await.map_err(|e| e.to_string())
}

#[tauri::command]
async fn cowork_create_session(
    title: String,
    cwd: Option<String>,
    system_prompt: Option<String>,
    execution_mode: Option<String>,
    state: State<'_, AppState>,
) -> Result<CoworkSession, String> {
    let manager = state.cowork_manager.lock().await;
    manager
        .create_session(title, cwd, system_prompt, execution_mode)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn cowork_delete_session(id: String, state: State<'_, AppState>) -> Result<(), String> {
    let manager = state.cowork_manager.lock().await;
    manager.delete_session(id).await.map_err(|e| e.to_string())
}

#[tauri::command]
async fn cowork_update_session(
    id: String,
    title: Option<String>,
    pinned: Option<bool>,
    status: Option<String>,
    cwd: Option<String>,
    system_prompt: Option<String>,
    execution_mode: Option<String>,
    state: State<'_, AppState>,
) -> Result<(), String> {
    let manager = state.cowork_manager.lock().await;
    manager
        .update_session(
            id,
            title,
            pinned,
            status,
            cwd,
            system_prompt,
            execution_mode,
        )
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn cowork_update_message(
    session_id: String,
    id: String,
    content: Option<String>,
    metadata: Option<String>,
    state: State<'_, AppState>,
) -> Result<(), String> {
    let manager = state.cowork_manager.lock().await;
    manager
        .update_message(session_id, id, content, metadata)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn cowork_get_config(state: State<'_, AppState>) -> Result<serde_json::Value, String> {
    let manager = state.cowork_manager.lock().await;
    manager.get_config().await.map_err(|e| e.to_string())
}

#[tauri::command]
async fn cowork_set_config(
    key: String,
    value: String,
    state: State<'_, AppState>,
) -> Result<(), String> {
    let manager = state.cowork_manager.lock().await;
    manager.set_config(key, value).await.map_err(|e| e.to_string())
}

#[tauri::command]
async fn cowork_update_user_memory(
    id: String,
    text: Option<String>,
    confidence: Option<f64>,
    status: Option<String>,
    is_explicit: Option<bool>,
    state: State<'_, AppState>,
) -> Result<(), String> {
    let manager = state.cowork_manager.lock().await;
    manager
        .update_user_memory(id, text, confidence, status, is_explicit)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn cowork_delete_user_memory(id: String, state: State<'_, AppState>) -> Result<bool, String> {
    let manager = state.cowork_manager.lock().await;
    manager.delete_user_memory(id).await.map_err(|e| e.to_string())
}

#[tauri::command]
async fn cowork_get_user_memory_stats(
    state: State<'_, AppState>,
) -> Result<serde_json::Value, String> {
    let manager = state.cowork_manager.lock().await;
    manager.get_user_memory_stats().await.map_err(|e| e.to_string())
}

#[tauri::command]
async fn cowork_list_messages(
    session_id: String,
    state: State<'_, AppState>,
) -> Result<Vec<CoworkMessage>, String> {
    let manager = state.cowork_manager.lock().await;
    manager.list_messages(session_id).await.map_err(|e| e.to_string())
}

#[tauri::command]
async fn cowork_add_message(
    session_id: String,
    msg_type: String,
    content: String,
    state: State<'_, AppState>,
) -> Result<CoworkMessage, String> {
    let manager = state.cowork_manager.lock().await;
    manager
        .add_message(session_id, msg_type, content)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn cowork_list_user_memories(state: State<'_, AppState>) -> Result<Vec<UserMemory>, String> {
    let manager = state.cowork_manager.lock().await;
    manager.list_user_memories().await.map_err(|e| e.to_string())
}

#[tauri::command]
async fn cowork_create_user_memory(
    text: String,
    confidence: f64,
    is_explicit: bool,
    state: State<'_, AppState>,
) -> Result<UserMemory, String> {
    let manager = state.cowork_manager.lock().await;
    manager
        .create_user_memory(text, confidence, is_explicit)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn cowork_send_message(
    session_id: String,
    content: String,
    state: State<'_, AppState>,
) -> Result<CoworkMessage, String> {
    let manager = state.cowork_manager.lock().await;
    manager
        .send_message(session_id, content)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn logger_log(
    level: String,
    message: String,
    state: State<'_, AppState>,
) -> Result<(), String> {
    let logger = state.logger.lock().await;
    let log_level = match level.as_str() {
        "debug" => LogLevel::Debug,
        "info" => LogLevel::Info,
        "warn" => LogLevel::Warn,
        "error" => LogLevel::Error,
        _ => LogLevel::Info,
    };
    logger.log(log_level, &message);
    Ok(())
}

#[tauri::command]
async fn logger_debug(message: String, state: State<'_, AppState>) -> Result<(), String> {
    let logger = state.logger.lock().await;
    logger.debug(&message);
    Ok(())
}

#[tauri::command]
async fn logger_info(message: String, state: State<'_, AppState>) -> Result<(), String> {
    let logger = state.logger.lock().await;
    logger.info(&message);
    Ok(())
}

#[tauri::command]
async fn logger_warn(message: String, state: State<'_, AppState>) -> Result<(), String> {
    let logger = state.logger.lock().await;
    logger.warn(&message);
    Ok(())
}

#[tauri::command]
async fn logger_error(message: String, state: State<'_, AppState>) -> Result<(), String> {
    let logger = state.logger.lock().await;
    logger.error(&message);
    Ok(())
}

#[tauri::command]
async fn skills_delete(id: String, state: State<'_, AppState>) -> Result<(), String> {
    let manager = state.skills_manager.lock().await;
    manager.delete_skill(&id).await.map_err(|e| e.to_string())
}

#[tauri::command]
async fn skills_get_root(state: State<'_, AppState>) -> Result<String, String> {
    let manager = state.skills_manager.lock().await;
    Ok(manager.get_skills_dir().to_string_lossy().into_owned())
}

#[tauri::command]
async fn window_minimize(app: AppHandle) -> Result<(), String> {
    if let Some(window) = app.get_webview_window("main") {
        window.minimize().map_err(|e| e.to_string())?;
    }
    Ok(())
}

#[tauri::command]
async fn window_toggle_maximize(app: AppHandle) -> Result<(), String> {
    if let Some(window) = app.get_webview_window("main") {
        if window.is_maximized().map_err(|e| e.to_string())? {
            window.unmaximize().map_err(|e| e.to_string())?;
        } else {
            window.maximize().map_err(|e| e.to_string())?;
        }
    }
    Ok(())
}

#[tauri::command]
async fn window_close(app: AppHandle) -> Result<(), String> {
    if let Some(window) = app.get_webview_window("main") {
        window.close().map_err(|e| e.to_string())?;
    }
    Ok(())
}

#[tauri::command]
async fn window_is_maximized(app: AppHandle) -> Result<bool, String> {
    if let Some(window) = app.get_webview_window("main") {
        window.is_maximized().map_err(|e| e.to_string())
    } else {
        Ok(false)
    }
}

#[tauri::command]
async fn skills_get_config(
    id: String,
    state: State<'_, AppState>,
) -> Result<Option<serde_json::Value>, String> {
    let manager = state.skills_manager.lock().await;
    manager
        .get_skill_config(&id)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn skills_set_config(
    id: String,
    settings: serde_json::Value,
    state: State<'_, AppState>,
) -> Result<(), String> {
    let manager = state.skills_manager.lock().await;
    manager
        .set_skill_config(&id, settings)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn skills_build_auto_routing_prompt(state: State<'_, AppState>) -> Result<String, String> {
    let manager = state.skills_manager.lock().await;
    manager
        .build_auto_routing_prompt()
        .await
        .map_err(|e| e.to_string())
}

// App Config commands
#[tauri::command]
async fn app_config_get(state: State<'_, AppState>) -> Result<serde_json::Value, String> {
    let kv = &state.kv_store;
    match kv.get("app_config") {
        Ok(Some(value)) => serde_json::from_str(&value).map_err(|e| e.to_string()),
        Ok(None) => Ok(serde_json::json!({"api_configs": {}})),
        Err(e) => Err(e.to_string()),
    }
}

#[tauri::command]
async fn app_config_set(
    config: serde_json::Value,
    state: State<'_, AppState>,
) -> Result<(), String> {
    let kv = &state.kv_store;
    let value = serde_json::to_string(&config).map_err(|e| e.to_string())?;
    kv.set("app_config", &value).map_err(|e| e.to_string())
}

// Tuptup config commands
#[tauri::command]
async fn tuptup_config_get(state: State<'_, AppState>) -> Result<serde_json::Value, String> {
    let kv = &state.kv_store;
    match kv.get("tuptup_config") {
        Ok(Some(value)) => serde_json::from_str(&value).map_err(|e| e.to_string()),
        Ok(None) => Ok(serde_json::json!({})),
        Err(e) => Err(e.to_string()),
    }
}

#[tauri::command]
async fn tuptup_config_set(
    config: serde_json::Value,
    state: State<'_, AppState>,
) -> Result<(), String> {
    let kv = &state.kv_store;
    let value = serde_json::to_string(&config).map_err(|e| e.to_string())?;
    kv.set("tuptup_config", &value).map_err(|e| e.to_string())
}

#[tauri::command]
async fn tuptup_get_user_info(state: State<'_, AppState>) -> Result<TuptupUserInfo, String> {
    let service = state.tuptup_service.lock().await;
    service.get_user_info("2").await.map_err(|e| e.to_string())
}

#[tauri::command]
async fn tuptup_get_token_balance(
    state: State<'_, AppState>,
) -> Result<TuptupTokenBalance, String> {
    let service = state.tuptup_service.lock().await;
    service
        .get_token_balance("2")
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn tuptup_get_plan(state: State<'_, AppState>) -> Result<TuptupPlan, String> {
    let service = state.tuptup_service.lock().await;
    service.get_plan("2").await.map_err(|e| e.to_string())
}

#[tauri::command]
async fn tuptup_get_overview(state: State<'_, AppState>) -> Result<TuptupOverview, String> {
    let service = state.tuptup_service.lock().await;
    service.get_overview("2").await.map_err(|e| e.to_string())
}

#[tauri::command]
async fn tuptup_get_smtp_config(state: State<'_, AppState>) -> Result<SmtpConfig, String> {
    let service = state.tuptup_service.lock().await;
    service
        .get_smtp_config("2")
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn tuptup_get_user_package(state: State<'_, AppState>) -> Result<UserPackage, String> {
    let service = state.tuptup_service.lock().await;
    service.get_user_package().await.map_err(|e| e.to_string())
}

#[tauri::command]
async fn tuptup_send_verification_email(
    email: String,
    state: State<'_, AppState>,
) -> Result<VerifyCodeResponse, String> {
    let mut service = state.tuptup_service.lock().await;
    service
        .send_verification_email(&email)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn tuptup_verify_code(
    email: String,
    code: String,
    state: State<'_, AppState>,
) -> Result<bool, String> {
    let service = state.tuptup_service.lock().await;
    Ok(service.verify_code(&email, &code).await)
}

#[tauri::command]
async fn tuptup_get_user_id_by_email(
    email: String,
    state: State<'_, AppState>,
) -> Result<Option<String>, String> {
    let service = state.tuptup_service.lock().await;
    service
        .get_user_id_by_email(&email)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn tuptup_get_package_status(state: State<'_, AppState>) -> Result<PackageStatus, String> {
    let service = state.tuptup_service.lock().await;
    let package = service
        .get_user_package()
        .await
        .map_err(|e| e.to_string())?;
    Ok(PackageStatus::from_package(&package))
}

#[tauri::command]
async fn tuptup_is_package_expired(state: State<'_, AppState>) -> Result<bool, String> {
    let service = state.tuptup_service.lock().await;
    let package = service
        .get_user_package()
        .await
        .map_err(|e| e.to_string())?;
    let status = PackageStatus::from_package(&package);
    Ok(status.is_expired)
}

#[tauri::command]
async fn tuptup_get_package_level(state: State<'_, AppState>) -> Result<i32, String> {
    let service = state.tuptup_service.lock().await;
    let package = service
        .get_user_package()
        .await
        .map_err(|e| e.to_string())?;
    let status = PackageStatus::from_package(&package);
    Ok(status.level)
}

#[tauri::command]
async fn crypto_encrypt(plaintext: String) -> Result<String, String> {
    let crypto = ClientCrypto::new();
    crypto.encrypt(&plaintext).map_err(|e| e.to_string())
}

#[tauri::command]
async fn crypto_decrypt(encrypted: String) -> Result<String, String> {
    let crypto = ClientCrypto::new();
    crypto.decrypt(&encrypted).map_err(|e| e.to_string())
}

// Platform and system commands
#[tauri::command]
async fn get_platform() -> Result<String, String> {
    Ok(std::env::consts::OS.to_string())
}

#[tauri::command]
async fn open_external(url: String) -> Result<(), String> {
    use open::that;
    that(&url).map_err(|e| format!("Failed to open URL: {}", e))
}

#[tauri::command]
async fn get_app_version(state: State<'_, AppState>) -> Result<String, String> {
    let manager = state.system_manager.lock().await;
    Ok(manager.get_app_version())
}

#[tauri::command]
async fn get_system_locale() -> Result<String, String> {
    Ok(sys_locale::get_locale().unwrap_or_else(|| "en-US".to_string()))
}

#[tauri::command]
async fn scheduler_start(state: State<'_, AppState>) -> Result<(), String> {
    let scheduler = state.scheduler.lock().await;
    scheduler.start().await
}

#[tauri::command]
async fn scheduler_stop(state: State<'_, AppState>) -> Result<(), String> {
    let scheduler = state.scheduler.lock().await;
    scheduler.stop().await
}

#[tauri::command]
async fn scheduler_is_running(state: State<'_, AppState>) -> Result<bool, String> {
    let scheduler = state.scheduler.lock().await;
    Ok(scheduler.is_running())
}

#[tauri::command]
async fn scheduler_list_tasks(state: State<'_, AppState>) -> Result<Vec<ScheduledTask>, String> {
    let scheduler = state.scheduler.lock().await;
    scheduler.list_tasks()
}

#[tauri::command]
async fn scheduler_create_task(
    id: String,
    name: String,
    cron_expression: String,
    state: State<'_, AppState>,
) -> Result<(), String> {
    let scheduler = state.scheduler.lock().await;
    scheduler.create_task(&id, &name, &cron_expression)
}

#[tauri::command]
async fn scheduler_delete_task(id: String, state: State<'_, AppState>) -> Result<(), String> {
    let scheduler = state.scheduler.lock().await;
    scheduler.delete_task(&id)
}

#[tauri::command]
async fn scheduler_update_task(
    id: String,
    enabled: bool,
    state: State<'_, AppState>,
) -> Result<(), String> {
    let scheduler = state.scheduler.lock().await;
    scheduler.update_task(&id, enabled)
}

#[tauri::command]
async fn scheduler_list_task_runs(
    task_id: Option<String>,
    state: State<'_, AppState>,
) -> Result<Vec<TaskRun>, String> {
    let scheduler = state.scheduler.lock().await;
    scheduler.list_task_runs(task_id.as_deref())
}

#[tauri::command]
async fn scheduler_execute_task(id: String, state: State<'_, AppState>) -> Result<String, String> {
    let scheduler = state.scheduler.lock().await;
    scheduler.execute_task(&id)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_http::init())
        .setup(|app| {
            println!("[setup] Starting application setup...");

            let storage = match Storage::new() {
                Ok(s) => {
                    println!("[setup] Storage created successfully");
                    s
                }
                Err(e) => {
                    println!("[setup] Failed to create Storage: {}", e);
                    return Err(e.to_string().into());
                }
            };

            let kv_store_path = storage.get_kv_store_path();
            println!("[setup] KV store path: {:?}", kv_store_path);

            let kv_store = match KvStore::new(kv_store_path) {
                Ok(k) => {
                    println!("[setup] KvStore created successfully");
                    k
                }
                Err(e) => {
                    println!("[setup] Failed to create KvStore: {}", e);
                    return Err(e.to_string().into());
                }
            };

            let skills_dir = storage.get_skills_dir();
            let skills_config_path = storage.get_skills_config_path();

            // 配置内置技能目录
            let bundled_skills_dir = if cfg!(dev) {
                // 开发模式：尝试多个可能的路径
                let current_dir = std::env::current_dir().expect("Failed to get current dir");

                // 可能的技能目录路径（按优先级）
                let possible_paths = [
                    // 当前目录下的 SKILLs（如果直接在项目根目录运行）
                    current_dir.join("SKILLs"),
                    // 上级目录的 SKILLs（如果在 src-tauri 目录运行）
                    current_dir.join("..").join("SKILLs"),
                    // 上上级目录的 SKILLs（如果在 src-tauri/src 目录运行）
                    current_dir.join("..").join("..").join("SKILLs"),
                ];

                let bundled_dir = possible_paths
                    .iter()
                    .find(|path| {
                        let canonical = path.canonicalize().ok();
                        canonical
                            .map(|p| p.join("skills.config.json").exists())
                            .unwrap_or(false)
                    })
                    .cloned()
                    .unwrap_or_else(|| current_dir.join("SKILLs"));

                println!(
                    "[skills] Dev mode: using bundled skills from: {:?}",
                    bundled_dir
                );
                bundled_dir
            } else {
                // 生产模式：使用应用资源目录
                let resource_dir = app
                    .path()
                    .resource_dir()
                    .expect("Failed to get resource dir");
                let bundled_dir = resource_dir.join("SKILLs");
                println!("[skills] Production mode: resource dir: {:?}", resource_dir);
                println!(
                    "[skills] Production mode: bundled skills dir: {:?}",
                    bundled_dir
                );
                bundled_dir
            };

            println!("[skills] User skills dir: {:?}", skills_dir);
            println!("[skills] Bundled skills dir: {:?}", bundled_skills_dir);

            let skills_manager = SkillsManager::new(skills_dir, skills_config_path)
                .with_bundled_skills(bundled_skills_dir);

            let app_data_dir = app
                .path()
                .app_data_dir()
                .expect("Failed to get app data dir");
            std::fs::create_dir_all(&app_data_dir).expect("Failed to create app data dir");

            let logger = Logger::new().expect("Failed to create Logger");

            let db_path = app_data_dir.join("glo.db");
            let database = Database::new(db_path).expect("Failed to create Database");

            let database_arc = Arc::new(TokioMutex::new(database));

            let mut system_manager = SystemManager::new();
            system_manager.set_app_handle(app.handle().clone());

            if let Err(e) = system_manager.setup_system_tray(app) {
                eprintln!("[System] Failed to setup system tray: {}", e);
            }

            let goclaw_manager = Arc::new(TokioMutex::new(GoClawManager::new(kv_store.clone())));
            let mut cowork_manager = CoworkManager::new(database_arc.clone());
            cowork_manager.set_goclaw_manager(goclaw_manager.clone());

            let scheduler = Scheduler::new(database_arc.clone());
            let tuptup_service = Arc::new(TokioMutex::new(TuptupService::new()));

            app.manage(AppState {
                storage,
                kv_store,
                skills_manager: Arc::new(TokioMutex::new(skills_manager)),
                database: database_arc,
                system_manager: Arc::new(TokioMutex::new(system_manager)),
                goclaw_manager,
                cowork_manager: Arc::new(TokioMutex::new(cowork_manager)),
                scheduler: Arc::new(TokioMutex::new(scheduler)),
                logger: Arc::new(TokioMutex::new(logger)),
                tuptup_service,
            });

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            initialize_app,
            kv_get,
            kv_set,
            kv_remove,
            skills_list,
            skills_enable,
            skills_disable,
            skills_delete,
            skills_get_root,
            skills_get_config,
            skills_set_config,
            skills_build_auto_routing_prompt,
            window_minimize,
            window_toggle_maximize,
            window_close,
            window_is_maximized,
            app_config_get,
            app_config_set,
            tuptup_config_get,
            tuptup_config_set,
            tuptup_get_user_info,
            tuptup_get_token_balance,
            tuptup_get_plan,
            tuptup_get_overview,
            get_platform,
            get_app_version,
            get_system_locale,
            system_enable_auto_start,
            system_is_auto_start_enabled,
            system_get_app_version,
            goclaw_get_config,
            goclaw_set_config,
            goclaw_start,
            goclaw_stop,
            goclaw_restart,
            goclaw_is_running,
            goclaw_get_status,
            goclaw_connect,
            goclaw_disconnect,
            goclaw_request,
            goclaw_send_message,
            goclaw_list_sessions,
            cowork_list_sessions,
            cowork_create_session,
            cowork_delete_session,
            cowork_update_session,
            cowork_list_messages,
            cowork_add_message,
            cowork_update_message,
            cowork_list_user_memories,
            cowork_create_user_memory,
            cowork_update_user_memory,
            cowork_delete_user_memory,
            cowork_get_user_memory_stats,
            cowork_get_config,
            cowork_set_config,
            cowork_send_message,
            logger_log,
            logger_debug,
            logger_info,
            logger_warn,
            logger_error,
            scheduler_start,
            scheduler_stop,
            scheduler_is_running,
            scheduler_list_tasks,
            scheduler_create_task,
            scheduler_delete_task,
            scheduler_update_task,
            scheduler_list_task_runs,
            scheduler_execute_task,
            dialog_select_directory,
            dialog_select_file,
            tuptup_get_smtp_config,
            tuptup_get_user_package,
            tuptup_send_verification_email,
            tuptup_verify_code,
            tuptup_get_user_id_by_email,
            tuptup_get_package_status,
            tuptup_is_package_expired,
            tuptup_get_package_level,
            crypto_encrypt,
            crypto_decrypt,
            make_http_request,
            open_external,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
