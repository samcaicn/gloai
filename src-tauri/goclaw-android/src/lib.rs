use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jstring};
use serde_json::json;
use std::sync::{Arc, Mutex};

static STATE: once_cell::sync::Lazy<Arc<Mutex<GoClawState>>> = once_cell::sync::Lazy::new(|| {
    Arc::new(Mutex::new(GoClawState::default()))
});

#[derive(Default, serde::Serialize, serde::Deserialize)]
struct GoClawState {
    config: String,
    running: bool,
}

fn init_logger() {
    let _ = android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Debug)
            .with_tag("GoClawNative"),
    );
}

#[no_mangle]
pub extern "system" fn Java_com_ggclaw_app_GoClawService_initGoClaw(
    mut env: JNIEnv,
    _class: JClass,
    config: JString,
) -> jstring {
    init_logger();
    log::debug!("initGoClaw called");
    
    let config_str = match env.get_string(&config) {
        Ok(s) => s.into(),
        Err(e) => {
            log::error!("Failed to get config string: {}", e);
            let result = json!({"error": format!("Failed to get config: {}", e)}).to_string();
            return env.new_string(&result).unwrap().into_raw();
        }
    };
    
    let mut state = STATE.lock().unwrap();
    state.config = config_str;
    state.running = true;
    
    let result = json!({
        "status": "initialized",
        "config": state.config
    }).to_string();
    
    log::debug!("initGoClaw result: {}", result);
    env.new_string(&result).unwrap().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_ggclaw_app_GoClawService_startGoClaw(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    init_logger();
    log::debug!("startGoClaw called");
    
    let state = STATE.lock().unwrap();
    
    if !state.running {
        let result = json!({"error": "not initialized"}).to_string();
        return env.new_string(&result).unwrap().into_raw();
    }
    
    let result = json!({"status": "started"}).to_string();
    log::debug!("startGoClaw result: {}", result);
    env.new_string(&result).unwrap().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_ggclaw_app_GoClawService_stopGoClaw(
    _env: JNIEnv,
    _class: JClass,
) {
    init_logger();
    log::debug!("stopGoClaw called");
    
    let mut state = STATE.lock().unwrap();
    state.running = false;
}

#[no_mangle]
pub extern "system" fn Java_com_ggclaw_app_GoClawService_sendMessage(
    mut env: JNIEnv,
    _class: JClass,
    message: JString,
) -> jstring {
    init_logger();
    log::debug!("sendMessage called");
    
    let state = STATE.lock().unwrap();
    
    if !state.running {
        let result = json!({"error": "service not running"}).to_string();
        return env.new_string(&result).unwrap().into_raw();
    }
    
    let msg_str = match env.get_string(&message) {
        Ok(s) => s.into(),
        Err(e) => {
            log::error!("Failed to get message string: {}", e);
            let result = json!({"error": format!("Failed to get message: {}", e)}).to_string();
            return env.new_string(&result).unwrap().into_raw();
        }
    };
    
    let result = json!({
        "received": msg_str,
        "processed": true
    }).to_string();
    
    log::debug!("sendMessage result: {}", result);
    env.new_string(&result).unwrap().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_ggclaw_app_GoClawService_getStatus(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    init_logger();
    log::debug!("getStatus called");
    
    let state = STATE.lock().unwrap();
    
    let result = json!({
        "running": state.running,
        "config": state.config
    }).to_string();
    
    log::debug!("getStatus result: {}", result);
    env.new_string(&result).unwrap().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_ggclaw_app_GoClawService_freeString(
    _env: JNIEnv,
    _class: JClass,
    _str: JString,
) {
}
