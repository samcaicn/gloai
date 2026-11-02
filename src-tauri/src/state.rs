use crate::bbt::BbtConnection;
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
}

impl AppState {
    pub fn new() -> Self {
        AppState {
            config: Mutex::new(json!({})),
            conn: Mutex::new(BbtConnection::default()),
            config_path: Mutex::new(None),
        }
    }

    /// 在 Tauri setup 阶段调用：解析配置目录并把磁盘配置载入内存。
    pub fn init(&self, app: &AppHandle) {
        if let Ok(dir) = app.path().app_config_dir() {
            let _ = std::fs::create_dir_all(&dir);
            let path = dir.join("config.json");
            if let Ok(s) = std::fs::read_to_string(&path) {
                if let Ok(v) = serde_json::from_str::<Value>(&s) {
                    *self.config.lock().unwrap() = v;
                }
            }
            *self.config_path.lock().unwrap() = Some(path);
        }
    }

    /// 把内存中的配置写回磁盘。
    pub fn save_config(&self) -> Result<(), String> {
        let p = self.config_path.lock().unwrap();
        let p = p.as_ref().ok_or("config 路径未初始化")?;
        let g = self.config.lock().unwrap();
        std::fs::write(p, serde_json::to_string_pretty(&*g).map_err(|e| e.to_string())?)
            .map_err(|e| e.to_string())
    }
}

impl Default for AppState {
    fn default() -> Self {
        Self::new()
    }
}
