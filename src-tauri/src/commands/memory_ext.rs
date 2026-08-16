// Copyright (c) 2026 tupAI
//
// memory_clear 命令 — 清空当前应用数据库中的所有长记忆条目。
//
// 现有 get_memories / add_memory / delete_memory / update_memory /
// compact_memories 命令定义在 commands::legacy，操作 tupai.db 的
// memories 表。memory_clear 复用同一连接（open_app_db）与同一张表，
// 一次 DELETE 清空全部条目，与 delete_memory（按 id 删除单条）互补。
//
// 前端 src/web-ui/.../infrastructure/api/tupai/memory.ts 的 memoryClear()
// 调用 invoke('memory_clear') 无参数，故本命令仅接收 app。

#![allow(dead_code)]

use tauri::AppHandle;

use crate::commands::legacy::open_app_db;

/// 清空所有长记忆条目。
///
/// 前端契约：memoryClear(): Promise<void>（无参数）。
/// 复用 commands::legacy::open_app_db 打开 tupai.db，对 memories 表
/// 执行 DELETE（不带 WHERE），一次清空全部条目。使用 execute_batch
/// 避免零参数 params 的类型推断问题。
#[tauri::command]
pub async fn memory_clear(app: AppHandle) -> Result<(), String> {
    let conn = open_app_db(&app)?;
    conn.execute_batch("DELETE FROM memories")
        .map_err(|e| format!("清空 memories 失败: {}", e))?;
    Ok(())
}
