//! 通用小工具。
//!
//! 原子写文件：先写同目录临时文件，再 rename 替换目标。避免 `std::fs::write`
//! 直接覆盖目标时在「截断 → 写完」之间进程崩溃/被杀导致配置文件半截损坏。
//! 升级场景下尤其关键——配置损坏比「升级没生效」危害更大，必须不丢配置。

use std::path::Path;

/// 原子写：内容先落盘到 `<path>.tmp`（同目录），再 rename 到目标。
///
/// * 写完临时文件后 `sync_all` 尽量保证数据落盘（降低断电丢配置概率）。
/// * Windows 的 `std::fs::rename` 不能覆盖已存在目标，故失败时先删目标再 rename
///   （best-effort；配置写入为低频单进程操作，可接受该窗口）。
/// * Linux/macOS 的 rename 本身是原子替换已存在目标。
pub fn write_atomic(path: &Path, content: &str) -> Result<(), String> {
    let tmp = path.with_extension("tmp");
    std::fs::write(&tmp, content).map_err(|e| format!("写临时文件失败: {}", e))?;
    // 尽量落盘（临时文件）
    if let Ok(f) = std::fs::File::open(&tmp) {
        let _ = f.sync_all();
    }
    match std::fs::rename(&tmp, path) {
        Ok(_) => Ok(()),
        Err(_) => {
            // Windows: 目标存在时 rename 失败，先删目标再 rename
            let _ = std::fs::remove_file(path);
            std::fs::rename(&tmp, path).map_err(|e| format!("替换文件失败: {}", e))
        }
    }
}
