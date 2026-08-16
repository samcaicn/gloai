// Copyright (c) 2026 MeeJoy
//
// 会话持久化命令（对话消息即时落盘 + 启动恢复）。
// 每个 session_id 对应 <app_data>/sessions/<session_id>.json，
// 原子写入（.tmp + rename），内容为前端 messages 数组的 JSON 直出。

use std::path::{Path, PathBuf};

use tauri::{AppHandle, Manager};

/// session_id 安全化：只保留字母/数字/-/_，防止路径穿越
fn sanitize_session_id(id: &str) -> String {
    let mut safe_id: String = id
        .chars()
        .map(|c| if c.is_alphanumeric() || c == '-' || c == '_' { c } else { '_' })
        .collect();
    // 截断到 64 字符,防止超长 session_id 导致文件路径过长
    // (Windows MAX_PATH=260,app_data_dir 前缀已占去一截)。
    // 安全:safe_id 此时只含 ASCII 字母/数字/-/_,每字符 1 字节,
    // truncate(64) 不会切在多字节 char 中间。
    safe_id.truncate(64);
    safe_id
}

async fn sessions_dir(app: &AppHandle) -> Result<PathBuf, String> {
    let dir = app
        .path()
        .app_data_dir()
        .map_err(|e| format!("app_data_dir failed: {}", e))?
        .join("sessions");
    tokio::fs::create_dir_all(&dir)
        .await
        .map_err(|e| format!("create sessions dir failed: {}", e))?;
    Ok(dir)
}

async fn session_path(app: &AppHandle, session_id: &str) -> Result<PathBuf, String> {
    let dir = sessions_dir(app).await?;
    let safe_id = sanitize_session_id(session_id);
    Ok(dir.join(format!("{}.json", safe_id)))
}

/// 原子写入：先写 .tmp 再 rename，防止断电/崩溃导致半截文件。
/// 使用 tokio::fs 避免在 async 命令里阻塞 tokio worker 线程
/// (会话文件可能含完整消息历史, 高频自动保存时阻塞会影响其他 async 任务)。
async fn atomic_write(path: &Path, data: &[u8]) -> Result<(), String> {
    let tmp = path.with_extension("tmp");
    tokio::fs::write(&tmp, data)
        .await
        .map_err(|e| format!("write tmp failed: {}", e))?;
    tokio::fs::rename(&tmp, path)
        .await
        .map_err(|e| format!("rename tmp→json failed: {}", e))?;
    Ok(())
}

/// 保存会话消息到磁盘（messages 是前端 JSON 数组的直出）
#[tauri::command]
pub async fn chat_session_save(app: AppHandle, session_id: String, messages: String) -> Result<(), String> {
    let path = session_path(&app, &session_id).await?;
    // messages 已经是 JSON 字符串，直接写入，无需反序列化再序列化
    atomic_write(&path, messages.as_bytes()).await?;

    // Phase 1: 会话保存后触发 Hermes 自进化分析 (非阻塞, session_end 钩子)。
    // try_trigger_analysis 用 AtomicBool 保证同一时刻只有一个分析在跑,
    // 避免高频自动保存导致重复 LLM 调用; orchestrator 未注册时静默跳过。
    // 失败不阻塞保存 (try_trigger_analysis 内部 spawn + catch 所有错误)。
    let _ = crate::hermes::evolution_orchestrator::try_trigger_analysis(&app, "session_save");

    Ok(())
}

/// 从磁盘加载会话消息，返回 JSON 字符串
#[tauri::command]
pub async fn chat_session_load(app: AppHandle, session_id: String) -> Result<String, String> {
    let path = session_path(&app, &session_id).await?;
    if !path.exists() {
        return Ok("[]".to_string());
    }
    let raw = tokio::fs::read_to_string(&path)
        .await
        .map_err(|e| format!("read session failed: {}", e))?;
    Ok(raw)
}

/// 删除会话文件（用户关闭活动入口时调用）
#[tauri::command]
pub async fn chat_session_delete(app: AppHandle, session_id: String) -> Result<(), String> {
    let path = session_path(&app, &session_id).await?;
    if path.exists() {
        tokio::fs::remove_file(&path)
            .await
            .map_err(|e| format!("delete session failed: {}", e))?;
    }
    // 清理可能残留的 .tmp 文件
    let tmp = path.with_extension("tmp");
    if tmp.exists() {
        let _ = tokio::fs::remove_file(&tmp).await;
    }
    Ok(())
}
