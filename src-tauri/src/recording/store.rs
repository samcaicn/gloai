// Copyright (c) 2026 AIMarketing
//
// 录制数据存储模块
//
// 存储路径: <app_data>/tupai/recording/<app_name>/YYYY-MM-DD.jsonl
// 文件格式: 每行一个RecordingBatch的JSON
// 保留策略: 最近30天

use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicI64, Ordering};

use chrono::Local;

use crate::recording::action::RecordingBatch;
use serde_json::Value;

/// 录制数据根目录
pub fn recording_dir() -> PathBuf {
    dirs::data_local_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("tupai")
        .join("recording")
}

/// 获取指定软件的录制目录
pub fn app_recording_dir(app_name: &str) -> PathBuf {
    // 对app_name进行安全处理，防止路径穿越
    let safe_name = sanitize_app_name(app_name);
    recording_dir().join(safe_name)
}

/// 安全化软件名称
/// 移除特殊字符，只保留字母、数字、中文、下划线、横线
fn sanitize_app_name(name: &str) -> String {
    let mut sanitized: String = name
        .chars()
        .filter(|c| {
            c.is_alphanumeric()
                || *c == '_'
                || *c == '-'
                || ('\u{4e00}' <= *c && *c <= '\u{9fff}') // 中文字符范围
        })
        .collect::<String>()
        .trim()
        .to_string()
        // 如果结果为空，使用默认名称
        .if_empty(|| "unknown_app".to_string());

    // 截断到 64 字符（按 char 截断，避免切断多字节字符边界）
    if sanitized.chars().count() > 64 {
        sanitized = sanitized.chars().take(64).collect();
    }

    // Windows 保留名检测（不区分大小写），命中则追加 '_' 防止非法目录名
    let upper = sanitized.to_uppercase();
    let is_reserved = matches!(
        upper.as_str(),
        "CON" | "PRN" | "AUX" | "NUL"
            | "COM1" | "COM2" | "COM3" | "COM4" | "COM5"
            | "COM6" | "COM7" | "COM8" | "COM9"
            | "LPT1" | "LPT2" | "LPT3" | "LPT4" | "LPT5"
            | "LPT6" | "LPT7" | "LPT8" | "LPT9"
    );
    if is_reserved {
        sanitized.push('_');
    }

    sanitized
}

trait IfEmpty {
    fn if_empty<F: FnOnce() -> Self>(self, f: F) -> Self;
}

impl IfEmpty for String {
    fn if_empty<F: FnOnce() -> Self>(self, f: F) -> Self {
        if self.is_empty() {
            f()
        } else {
            self
        }
    }
}

/// 获取当天的录制文件路径
pub fn today_file_path(app_name: &str) -> PathBuf {
    let date_str = Local::now().format("%Y-%m-%d").to_string();
    app_recording_dir(app_name).join(format!("{}.jsonl", date_str))
}

/// 确保录制目录存在
pub fn ensure_recording_dir() -> Result<(), String> {
    let dir = recording_dir();
    if !dir.exists() {
        fs::create_dir_all(&dir).map_err(|e| format!("create dir failed: {}", e))?;
    }
    Ok(())
}

/// 确保指定软件的录制目录存在
pub fn ensure_app_recording_dir(app_name: &str) -> Result<PathBuf, String> {
    let dir = app_recording_dir(app_name);
    if !dir.exists() {
        fs::create_dir_all(&dir).map_err(|e| format!("create app dir failed: {}", e))?;
    }
    Ok(dir)
}

/// 存储录制批次
pub fn save_batch(batch: &RecordingBatch) -> Result<PathBuf, String> {
    // 确保目录存在
    let dir = ensure_app_recording_dir(&batch.app_name)?;

    // 获取当天文件路径
    let file_path = today_file_path(&batch.app_name);

    // 序列化批次为JSON
    let line = serde_json::to_string(batch)
        .map_err(|e| format!("serialize batch failed: {}", e))?;

    // 追加写入文件（writeln! 直接写入内核缓冲区，不调用 FlushFileBuffers）
    let mut options = fs::OpenOptions::new();
    options.create(true).append(true);

    let mut file = options
        .open(&file_path)
        .map_err(|e| format!("open file failed: {}", e))?;

    writeln!(file, "{}", line)
        .map_err(|e| format!("write file failed: {}", e))?;

    // 旧文件清理：每小时最多执行一次（而非每次写入）
    maybe_prune_old_files(&dir);

    Ok(file_path)
}

/// 清理超过30天的录制文件
const MAX_RETENTION_DAYS: i64 = 30;

fn prune_old_files(dir: &Path) -> Result<(), String> {
    let cutoff = Local::now() - chrono::Duration::days(MAX_RETENTION_DAYS);
    let cutoff_str = cutoff.format("%Y-%m-%d").to_string();

    let read_dir = fs::read_dir(dir)
        .map_err(|e| format!("read dir failed: {}", e))?;

    for entry in read_dir.flatten() {
        let path = entry.path();

        // 解析文件名 YYYY-MM-DD.jsonl
        let Some(name) = path.file_name().and_then(|s| s.to_str()) else { continue };
        let Some(date_str) = name.strip_suffix(".jsonl") else { continue };

        // 校验文件名是合法的 YYYY-MM-DD 日期，
        // 避免误删非日期命名的文件（如手写笔记、临时文件等）。
        if chrono::NaiveDate::parse_from_str(date_str, "%Y-%m-%d").is_err() {
            continue;
        }

        // 字符串比较，字典序等于日期序
        if date_str < cutoff_str.as_str() {
            let _ = fs::remove_file(&path);
        }
    }

    Ok(())
}

/// 全局上次清理时间戳（unix seconds），每小时最多执行一次 prune
static LAST_PRUNE_TS: AtomicI64 = AtomicI64::new(0);

/// 节流版 prune：每小时最多执行一次，避免每次写入都做目录扫描
fn maybe_prune_old_files(dir: &Path) {
    let now = Local::now().timestamp();
    let last = LAST_PRUNE_TS.load(Ordering::Relaxed);
    if now - last < 3600 {
        return;
    }
    LAST_PRUNE_TS.store(now, Ordering::Relaxed);
    let _ = prune_old_files(dir);
}

/// 读取指定软件的最近录制批次
pub fn read_recent_batches(app_name: &str, limit: usize) -> Result<Vec<RecordingBatch>, String> {
    let dir = app_recording_dir(app_name);

    if !dir.exists() {
        return Ok(Vec::new());
    }

    let read_dir = fs::read_dir(&dir)
        .map_err(|e| format!("read dir failed: {}", e))?;

    // 收集所有jsonl文件，按日期倒序
    let mut files: Vec<PathBuf> = read_dir
        .filter_map(|e| e.ok().map(|e| e.path()))
        .filter(|p| p.extension().and_then(|e| e.to_str()) == Some("jsonl"))
        .collect();

    files.sort_by(|a, b| b.file_name().cmp(&a.file_name()));

    let mut batches: Vec<RecordingBatch> = Vec::new();

    for path in files {
        if let Ok(content) = fs::read_to_string(&path) {
            // 从文件末尾向前读取，获取最近的批次
            for line in content.lines().rev() {
                if line.is_empty() {
                    continue;
                }
                if let Ok(batch) = serde_json::from_str::<RecordingBatch>(line) {
                    batches.push(batch);
                    if batches.len() >= limit {
                        return Ok(batches);
                    }
                }
            }
        }
    }

    Ok(batches)
}

/// 获取所有录制过的软件名称列表
pub fn list_recorded_apps() -> Result<Vec<String>, String> {
    let dir = recording_dir();

    if !dir.exists() {
        return Ok(Vec::new());
    }

    let read_dir = fs::read_dir(&dir)
        .map_err(|e| format!("read dir failed: {}", e))?;

    let apps: Vec<String> = read_dir
        .filter_map(|e| e.ok())
        .filter(|e| e.path().is_dir())
        .filter_map(|e| e.file_name().to_str().map(|s| s.to_string()))
        .collect();

    Ok(apps)
}

/// 获取指定软件的录制统计
pub fn get_app_stats(app_name: &str) -> Result<AppRecordingStats, String> {
    let dir = app_recording_dir(app_name);

    if !dir.exists() {
        return Ok(AppRecordingStats {
            app_name: app_name.to_string(),
            total_batches: 0,
            total_actions: 0,
            first_record_date: None,
            last_record_date: None,
        });
    }

    let read_dir = fs::read_dir(&dir)
        .map_err(|e| format!("read dir failed: {}", e))?;

    let files: Vec<PathBuf> = read_dir
        .filter_map(|e| e.ok().map(|e| e.path()))
        .filter(|p| p.extension().and_then(|e| e.to_str()) == Some("jsonl"))
        .collect();

    if files.is_empty() {
        return Ok(AppRecordingStats {
            app_name: app_name.to_string(),
            total_batches: 0,
            total_actions: 0,
            first_record_date: None,
            last_record_date: None,
        });
    }

    // 统计批次和动作数量
    let mut total_batches = 0;
    let mut total_actions = 0;
    let mut dates: Vec<String> = Vec::new();

    for path in &files {
        if let Some(name) = path.file_name().and_then(|s| s.to_str()) {
            if let Some(date) = name.strip_suffix(".jsonl") {
                dates.push(date.to_string());
            }
        }

        if let Ok(content) = fs::read_to_string(path) {
            for line in content.lines() {
                if let Ok(batch) = serde_json::from_str::<RecordingBatch>(line) {
                    total_batches += 1;
                    total_actions += batch.dedup_count;
                }
            }
        }
    }

    dates.sort();

    Ok(AppRecordingStats {
        app_name: app_name.to_string(),
        total_batches,
        total_actions,
        first_record_date: dates.first().cloned(),
        last_record_date: dates.last().cloned(),
    })
}

/// 软件录制统计
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AppRecordingStats {
    pub app_name: String,
    pub total_batches: usize,
    pub total_actions: usize,
    pub first_record_date: Option<String>,
    pub last_record_date: Option<String>,
}

// ── 悬浮窗录制流程图持久化 ─────────────────────────────────────────────
//
// 悬浮窗 `RecorderToolbar` 走 teaching.rs 录制，结果（Flowchart）原本只写入
// `proposal_store`，与 `get_recorded_flowchart_cmd` 读取的 `recording::store`
// 批次数据互不相连 —— 导致录完关闭悬浮窗后流程图读不到。这里把 Flowchart
// 单独落库到 `<app_dir>/flowchart.json`，并支持多次录制去重合并，使现有加载
// 路径（recordingLoad → get_recorded_flowchart_cmd）直接读得到。

/// 软件流程图文件路径: <app_dir>/flowchart.json
pub fn flowchart_path(app_name: &str) -> PathBuf {
    app_recording_dir(app_name).join("flowchart.json")
}

fn node_fingerprint(n: &Value) -> String {
    let meta = n.get("meta").map(|m| m.to_string()).unwrap_or_default();
    [
        n.get("type").and_then(|v| v.as_str()).unwrap_or(""),
        n.get("label").and_then(|v| v.as_str()).unwrap_or(""),
        n.get("action").and_then(|v| v.as_str()).unwrap_or(""),
        &meta,
    ]
    .join("\u{1f}")
}

fn conn_fingerprint(c: &Value) -> String {
    [
        c.get("from").and_then(|v| v.as_str()).unwrap_or(""),
        c.get("to").and_then(|v| v.as_str()).unwrap_or(""),
        c.get("label").and_then(|v| v.as_str()).unwrap_or(""),
    ]
    .join("\u{1f}")
}

/// 合并两次流程图，对操作节点做指纹去重。返回合并后的流程图 JSON。
/// 与前端 `flowchartAdapter.mergeFlowcharts` 语义一致：
///   * prev / next 任一为空 → 返回另一个
///   * 仅保留一个 start / 一个 end（优先 prev）
///   * next 中与 prev 指纹不重复的操作节点追加到末尾，并重新生成 id
///   * 用一条桥接边把 prev 最后一个操作节点连到 next 的第一个新节点
pub fn merge_flowcharts(prev: &Value, next: &Value) -> Value {
    let empty = serde_json::json!({ "nodes": [], "connections": [] });
    let prev = if prev.is_object() { prev } else { &empty };
    let next = if next.is_object() { next } else { &empty };
    let prev_nodes = prev
        .get("nodes")
        .and_then(|v| v.as_array())
        .cloned()
        .unwrap_or_default();
    let next_nodes = next
        .get("nodes")
        .and_then(|v| v.as_array())
        .cloned()
        .unwrap_or_default();
    if prev_nodes.is_empty() {
        return next.clone();
    }
    if next_nodes.is_empty() {
        return prev.clone();
    }

    let find = |nodes: &[Value], t: &str| {
        nodes
            .iter()
            .find(|n| n.get("type").and_then(|v| v.as_str()) == Some(t))
            .cloned()
    };
    let is_frame = |n: &Value| -> bool {
        let t = n.get("type").and_then(|v| v.as_str()).unwrap_or("");
        t == "start" || t == "end"
    };
    let prev_start = find(&prev_nodes, "start");
    let prev_end = find(&prev_nodes, "end");
    let prev_ops: Vec<Value> = prev_nodes.iter().filter(|n| !is_frame(n)).cloned().collect();
    let next_start = find(&next_nodes, "start");
    let next_end = find(&next_nodes, "end");
    let next_ops: Vec<Value> = next_nodes.iter().filter(|n| !is_frame(n)).cloned().collect();

    let mut prev_key: std::collections::HashMap<String, ()> = std::collections::HashMap::new();
    for n in &prev_ops {
        prev_key.insert(node_fingerprint(n), ());
    }

    let mut new_ops: Vec<Value> = Vec::new();
    for n in &next_ops {
        if !prev_key.contains_key(&node_fingerprint(n)) {
            new_ops.push(n.clone());
        }
    }

    let ts = chrono::Utc::now().timestamp_millis();
    let mut id_remap: std::collections::HashMap<String, String> = std::collections::HashMap::new();
    let mut remapped_ops: Vec<Value> = Vec::new();
    for (idx, op) in new_ops.iter().enumerate() {
        let old_id = op
            .get("id")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        let new_id = format!("merge-{}-{}-{}", ts, idx, old_id);
        id_remap.insert(old_id.clone(), new_id.clone());
        let mut no = op.clone();
        if let Some(obj) = no.as_object_mut() {
            obj.insert("id".to_string(), Value::String(new_id));
        }
        remapped_ops.push(no);
    }

    let mut merged_nodes: Vec<Value> = Vec::new();
    if let Some(s) = &prev_start {
        merged_nodes.push(s.clone());
    } else if let Some(s) = &next_start {
        merged_nodes.push(s.clone());
    }
    for n in &prev_ops {
        merged_nodes.push(n.clone());
    }
    for n in &remapped_ops {
        merged_nodes.push(n.clone());
    }
    if let Some(e) = &prev_end {
        merged_nodes.push(e.clone());
    } else if let Some(e) = &next_end {
        merged_nodes.push(e.clone());
    }

    let all_ids: std::collections::HashSet<String> = merged_nodes
        .iter()
        .filter_map(|n| n.get("id").and_then(|v| v.as_str()).map(|s| s.to_string()))
        .collect();

    let mut conn_keys: std::collections::HashSet<String> = std::collections::HashSet::new();
    let mut merged_conns: Vec<Value> = Vec::new();

    let has_new = !remapped_ops.is_empty();
    let prev_start_id = prev_start
        .as_ref()
        .and_then(|n| n.get("id").and_then(|v| v.as_str()))
        .map(|s| s.to_string());
    let prev_end_id = prev_end
        .as_ref()
        .and_then(|n| n.get("id").and_then(|v| v.as_str()))
        .map(|s| s.to_string());
    let is_skeleton = |c: &Value| -> bool {
        if !has_new {
            return false;
        }
        let Some(ps) = &prev_start_id else { return false };
        let Some(pe) = &prev_end_id else { return false };
        let f = c.get("from").and_then(|v| v.as_str()).unwrap_or("");
        let t = c.get("to").and_then(|v| v.as_str()).unwrap_or("");
        f == ps && t == pe && c.get("label").is_none()
    };

    let prev_conns = prev
        .get("connections")
        .and_then(|v| v.as_array())
        .cloned()
        .unwrap_or_default();
    for c in &prev_conns {
        let f = c.get("from").and_then(|v| v.as_str()).unwrap_or("");
        let t = c.get("to").and_then(|v| v.as_str()).unwrap_or("");
        if !all_ids.contains(f) || !all_ids.contains(t) {
            continue;
        }
        if is_skeleton(c) {
            continue;
        }
        let k = conn_fingerprint(c);
        if conn_keys.contains(&k) {
            continue;
        }
        conn_keys.insert(k);
        merged_conns.push(c.clone());
    }
    let next_conns = next
        .get("connections")
        .and_then(|v| v.as_array())
        .cloned()
        .unwrap_or_default();
    for c in &next_conns {
        let mut from = c.get("from").and_then(|v| v.as_str()).unwrap_or("").to_string();
        let mut to = c.get("to").and_then(|v| v.as_str()).unwrap_or("").to_string();
        if next_start
            .as_ref()
            .map(|n| n.get("id").and_then(|v| v.as_str()))
            == Some(Some(from.as_str()))
            && prev_start.is_some()
        {
            if let Some(ps) = &prev_start_id {
                from = ps.clone();
            }
        }
        if let Some(ne) = &next_end {
            if to == ne.get("id").and_then(|v| v.as_str()).unwrap_or("") {
                if let Some(pe) = &prev_end_id {
                    to = pe.clone();
                }
            }
        }
        if let Some(rf) = id_remap.get(&from) {
            from = rf.clone();
        }
        if let Some(rt) = id_remap.get(&to) {
            to = rt.clone();
        }
        if !all_ids.contains(&from) || !all_ids.contains(&to) {
            continue;
        }
        let mut rc = c.clone();
        if let Some(obj) = rc.as_object_mut() {
            obj.insert("from".to_string(), Value::String(from.clone()));
            obj.insert("to".to_string(), Value::String(to.clone()));
        }
        let k = conn_fingerprint(&rc);
        if conn_keys.contains(&k) {
            continue;
        }
        conn_keys.insert(k);
        merged_conns.push(rc);
    }

    if has_new {
        let last_prev = prev_ops
            .last()
            .or(prev_start.as_ref())
            .and_then(|n| n.get("id").and_then(|v| v.as_str()))
            .map(|s| s.to_string());
        let first_new = remapped_ops
            .first()
            .and_then(|n| n.get("id").and_then(|v| v.as_str()))
            .map(|s| s.to_string());
        if let (Some(lp), Some(fn_)) = (last_prev, first_new) {
            if lp != fn_ {
                let bridge = serde_json::json!({ "from": lp, "to": fn_ });
                let bk = conn_fingerprint(&bridge);
                if !conn_keys.contains(&bk) {
                    conn_keys.insert(bk);
                    merged_conns.push(bridge);
                }
            }
        }
    }

    let step_count = merged_nodes
        .iter()
        .filter(|n| {
            let t = n.get("type").and_then(|v| v.as_str()).unwrap_or("");
            t != "start" && t != "end"
        })
        .count() as u32;

    let title = prev
        .get("title")
        .and_then(|v| v.as_str())
        .unwrap_or("flowchart")
        .to_string();
    let layout = prev
        .get("layout")
        .and_then(|v| v.as_str())
        .unwrap_or("TB")
        .to_string();
    let style = prev.get("style").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let source = prev
        .get("source")
        .and_then(|v| v.as_str())
        .unwrap_or("recorder")
        .to_string();

    serde_json::json!({
        "title": title,
        "layout": layout,
        "style": style,
        "source": source,
        "nodes": merged_nodes,
        "connections": merged_conns,
        // Flowchart 结构体带 `#[serde(rename_all = "camelCase")]`，
        // 序列化字段为 stepCount。此处必须用 camelCase，
        // 否则 read_app_flowchart 反序列化会失败并回退到 jsonl 路径，
        // 而悬浮窗录制不写 jsonl → 流程图加载不出来。
        "stepCount": step_count,
    })
}

/// 保存/合并某软件的流程图（多次录制自动去重合并）。
pub fn save_app_flowchart(app_name: &str, fc: &Value) -> Result<PathBuf, String> {
    let dir = ensure_app_recording_dir(app_name)?;
    let path = dir.join("flowchart.json");
    let merged = if path.exists() {
        match std::fs::read_to_string(&path) {
            Ok(s) => match serde_json::from_str::<Value>(&s) {
                Ok(existing) => merge_flowcharts(&existing, fc),
                Err(_) => fc.clone(),
            },
            Err(_) => fc.clone(),
        }
    } else {
        fc.clone()
    };
    let s = serde_json::to_string_pretty(&merged).map_err(|e| format!("serialize flowchart failed: {}", e))?;
    std::fs::write(&path, s).map_err(|e| format!("write flowchart failed: {}", e))?;
    Ok(path)
}

/// 读取某软件已保存的流程图（悬浮窗录制落库的结果）。
pub fn read_app_flowchart(app_name: &str) -> Option<Value> {
    let path = flowchart_path(app_name);
    if !path.exists() {
        return None;
    }
    std::fs::read_to_string(&path)
        .ok()
        .and_then(|s| serde_json::from_str::<Value>(&s).ok())
}