// Copyright (c) 2026 AIMarketing
//
// 后台录制数据查询命令
//
// 把 recording::store 的函数暴露给前端：
//   * list_recorded_apps_cmd          — 列出所有有录制数据的软件
//   * get_recorded_flowchart_cmd      — 读取录制数据并转为流程图
//   * get_app_stats_cmd               — 获取软件的录制统计
//
// 录制全程自动静默运行，无开关按钮。用户在自动化界面点击软件时，
// 前端调用 get_recorded_flowchart_cmd 加载已存储的流程图数据。

use crate::automation::flowchart::Flowchart;
use crate::recording::{self, store::{self, AppRecordingStats}};

/// 列出所有有录制数据的软件名称
#[tauri::command]
pub fn list_recorded_apps_cmd() -> Result<Vec<String>, String> {
    store::list_recorded_apps()
}

/// 获取指定软件的录制统计
#[tauri::command]
pub fn get_app_stats_cmd(app_name: String) -> Result<AppRecordingStats, String> {
    store::get_app_stats(&app_name)
}

/// 读取指定软件的最近录制数据，转为流程图
/// limit: 读取的最大批次数（每批约5秒的数据）
///
/// 优先读取悬浮窗录制落库的流程图（`<app_dir>/flowchart.json`，已含多次
/// 录制去重合并）；若该文件不存在，回退到旧的批次合并逻辑。
#[tauri::command]
pub fn get_recorded_flowchart_cmd(
    app_name: String,
    limit: Option<usize>,
) -> Result<Flowchart, String> {
    // 优先：悬浮窗录制落库的流程图（teaching.rs::stop_recording 写入）。
    if let Some(val) = store::read_app_flowchart(&app_name) {
        if let Ok(fc) = serde_json::from_value::<Flowchart>(val) {
            if !fc.nodes.is_empty() {
                return Ok(fc);
            }
        }
    }

    // 回退：旧的批次合并逻辑。
    let batch_limit = limit.unwrap_or(50);
    let batches = store::read_recent_batches(&app_name, batch_limit)?;

    // 批次按时间从新到旧返回，需要反转为时间正序
    let mut batches = batches;
    batches.reverse();

    // 扁平化所有批次的动作，按时间顺序合并
    let mut all_actions: Vec<recording::action::RecordedAction> = Vec::new();
    for batch in &batches {
        all_actions.extend(batch.actions.clone());
    }

    Ok(recording::flowchart::actions_to_flowchart(&all_actions))
}
