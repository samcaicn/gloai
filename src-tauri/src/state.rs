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

    /// 备份文件：每次写盘前保留上一份有效配置，供升级/写盘中断后自动恢复。
    fn backup_path(p: &std::path::Path) -> PathBuf {
        p.with_extension("json.bak")
    }

    /// 在 Tauri setup 阶段调用：解析配置目录并把磁盘配置载入内存。
    /// 读取优先级：config.json → config.json.bak（主文件缺失/损坏/空配置时）。
    /// 升级只替换 exe，配置目录固定，因此升级后这里会原样恢复全部本地配置。
    pub fn init(&self, app: &AppHandle) {
        if let Ok(dir) = app.path().app_config_dir() {
            let _ = std::fs::create_dir_all(&dir);
            let path = dir.join("config.json");
            let backup = Self::backup_path(&path);
            let read_value = |f: &PathBuf| -> Option<Value> {
                let s = std::fs::read_to_string(f).ok()?;
                // 防破解（weauto 移植）：发行版下验签；签名不匹配时降级接受 data
                // （配置丢失比绕过验签危害更大，见 guard::verify_config_file 注释）
                let inner = match crate::guard::verify_config_file(&s) {
                    Ok(v) => v,
                    Err(e) => {
                        self.tampered.store(true, std::sync::atomic::Ordering::Relaxed);
                        eprintln!("[guard] config 读取失败({e})，尝试备份");
                        return None;
                    }
                };
                let v: Value = serde_json::from_str(&inner).ok()?;
                if v.as_object().map(|o| o.is_empty()).unwrap_or(true) {
                    return None; // 空配置视为无效，继续找备份
                }
                Some(v)
            };
            let mut loaded = read_value(&path);
            if loaded.is_none() {
                if let Some(v) = read_value(&backup) {
                    eprintln!("[cache] 主配置缺失/损坏，已从 config.json.bak 恢复本地配置");
                    // 立即把备份内容写回主文件，避免下次再走恢复分支
                    if let Ok(s) = std::fs::read_to_string(&backup) {
                        let _ = util::write_atomic(&path, &s);
                    }
                    loaded = Some(v);
                }
            }
            if let Some(v) = loaded {
                *self.config.lock().unwrap() = v;
            }
            *self.config_path.lock().unwrap() = Some(path);
        }
    }

    /// 把内存中的配置写回磁盘（发行版下写 HMAC 签名信封格式）。
    /// 写主文件前先原子写一份 config.json.bak，保证升级/断电/写盘中断时可恢复。
    pub fn save_config(&self) -> Result<(), String> {
        let p = self.config_path.lock().unwrap();
        let p = p.as_ref().ok_or("config 路径未初始化")?;
        if let Ok(old) = std::fs::read_to_string(p) {
            if serde_json::from_str::<Value>(&old).is_ok() {
                let _ = util::write_atomic(&Self::backup_path(p), &old);
            }
        }
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
