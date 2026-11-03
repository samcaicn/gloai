use crate::bbt::BbtConnection;
use crate::util;
use serde_json::{json, Value};
use std::path::PathBuf;
use std::sync::Mutex;
use tauri::{AppHandle, Manager};

/// 全局可变状态：本地配置（JSON 对象）+ bbt-zb 连接信息 + 配置文件路径。
///
/// 设计上刻意保持“薄”：Rust 只负责本地持久化与把请求转发给 bbt-zb，
/// 参数的具体结构（schema / 默认值）完全由前端定义 —— 这与 weauto 的
/// aichat-h5 前端自管配置 UI 的思路一致（基于 weauto 骨架）。
pub struct AppState {
    pub config: Mutex<Value>,
    pub conn: Mutex<BbtConnection>,
    pub config_path: Mutex<Option<PathBuf>>,
    /// 配置文件被篡改标记（发行版下验签失败置位，供 UI/日志提示）
    pub tampered: std::sync::atomic::AtomicBool,
}

impl AppState {
    pub fn new() -> Self {
        AppState {
            config: Mutex::new(json!({})),
            conn: Mutex::new(BbtConnection::default()),
            config_path: Mutex::new(None),
            tampered: std::sync::atomic::AtomicBool::new(false),
        }
    }

    /// 在 Tauri setup 阶段调用：解析配置目录并把磁盘配置载入内存。
    pub fn init(&self, app: &AppHandle) {
        if let Ok(dir) = app.path().app_config_dir() {
            let _ = std::fs::create_dir_all(&dir);
            let path = dir.join("config.json");
            if let Ok(s) = std::fs::read_to_string(&path) {
                // 防破解（weauto 移植）：发行版下验签，篡改即回退默认配置
                match crate::guard::verify_config_file(&s) {
                    Ok(inner) => {
                        if let Ok(v) = serde_json::from_str::<Value>(&inner) {
                            *self.config.lock().unwrap() = v;
                        }
                    }
                    Err(e) => {
                        self.tampered.store(true, std::sync::atomic::Ordering::Relaxed);
                        eprintln!("[guard] config.json 验签失败({e})，已回退默认配置");
                    }
                }
            }
            *self.config_path.lock().unwrap() = Some(path);
        }
    }

    /// 把内存中的配置写回磁盘（发行版下写 HMAC 签名信封格式）。
    pub fn save_config(&self) -> Result<(), String> {
        let p = self.config_path.lock().unwrap();
        let p = p.as_ref().ok_or("config 路径未初始化")?;
        let g = self.config.lock().unwrap();
        let plain = serde_json::to_string_pretty(&*g).map_err(|e| e.to_string())?;
        let text = if crate::guard::is_release() {
            let mut env = serde_json::json!({ "data": &*g });
            let sig = crate::guard::config_sig(
                &serde_json::to_string(&env).map_err(|e| e.to_string())?,
            );
            if let Some(obj) = env.as_object_mut() {
                obj.insert(crate::guard::CONFIG_ENV_KEY.into(), json!(sig));
            }
            serde_json::to_string_pretty(&env).map_err(|e| e.to_string())?
        } else {
            plain
        };
        util::write_atomic(p, &text).map_err(|e| e.to_string())
    }
}

impl Default for AppState {
    fn default() -> Self {
        Self::new()
    }
}
