// Copyright (c) 2026 AIMarketing
//
// UIA 窗口焦点 + 元素轮询器
//
// 对不支持 CDP 的原生 Windows 软件，通过轮询前台窗口标题变化
// 和焦点元素变化来记录用户操作。采用轻量级 Win32 API +
// UIA get_focused_element 轮询，避免复杂的 COM 事件订阅。
//
// 设计:
//   * 每 1 秒合并轮询：窗口标题 + UIA 焦点元素（单次 spawn_blocking）
//   * UIA 分两阶段：快速路径只读 5 个属性做去重，慢路径仅元素变化时读全量
//   * UIAutomation 实例每次轮询创建（UIAutomation 不 Send，无法跨线程共享）
//   * 只存储数据，不操作界面
//   * 在 recording runtime 的独立 async task 上运行

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

use tokio::time::interval;

use crate::recording::action::{ActionType, ElementSelector, FallbackSelector, RecordedAction};
use crate::recording::is_recording_enabled;

/// 合并轮询间隔：1 秒（窗口标题 + UIA 元素在同一 tick 内完成）
const POLL_INTERVAL_MS: u64 = 1000;

/// 单次 UIA 查询超时（防挂死）。
const UIA_POLL_TIMEOUT_MS: u64 = 200;

/// 合并轮询结果 — 传回 async 侧的数据
struct PollOutput {
    title_changed: bool,
    title: String,
    element_changed: bool,
    /// 仅在 element_changed 时填充
    element_info: Option<FullElementInfo>,
}

/// UIA 元素全量信息（仅在元素变化时构建）
struct FullElementInfo {
    name: String,
    class_name: String,
    automation_id: String,
    control_type: String,
    runtime_id: Option<i64>,
    help_text: String,
    item_status: String,
    is_enabled: bool,
    access_key: String,
    accelerator_key: String,
    process_id: u32,
}

/// UIA 窗口焦点 + 元素轮询异步任务
pub async fn run_uia_poller(stop_signal: Arc<AtomicBool>) {
    let mut tick = interval(Duration::from_millis(POLL_INTERVAL_MS));
    let mut first_tick = true;

    let mut last_title: String = String::new();
    let mut last_runtime_id: Option<i64> = None;
    let mut last_name_fp: String = String::new();

    while !stop_signal.load(Ordering::SeqCst) && is_recording_enabled() {
        tick.tick().await;
        if first_tick {
            first_tick = false;
            continue;
        }

        let prev_title = std::mem::take(&mut last_title);
        let prev_rid = last_runtime_id;
        let prev_fp = std::mem::take(&mut last_name_fp);

        // Clone for branches that need values after spawn_blocking moves originals
        let prev_title_for_err = prev_title.clone();
        let prev_fp_for_ok = prev_fp.clone();
        let prev_fp_for_err = prev_fp.clone();

        let poll_outcome = tokio::task::spawn_blocking(move || {
            poll_combined(prev_title, prev_rid, prev_fp)
        })
        .await;

        match poll_outcome {
            Ok(output) => {
                last_title = output.title;
                if output.title_changed {
                    let title = &last_title;
                    let app_name = extract_app_name_from_title(title);
                    let action = RecordedAction::new(&app_name, title, ActionType::Focus, "uia")
                        .with_target(ElementSelector {
                            selector_type: "uia_name".to_string(),
                            value: title.to_string(),
                            text_content: Some(title.to_string()),
                            bounds: None,
                            fallback_selectors: vec![],
                        });
                    crate::recording::recorder::add_action_to_buffer(action);
                }

                if let Some(info) = output.element_info {
                    let changed = if let Some(rid) = info.runtime_id {
                        Some(rid) != prev_rid
                    } else {
                        let fp = format!("{}|{}|{}", info.automation_id, info.control_type, info.class_name);
                        fp != prev_fp_for_ok
                    };

                    if changed {
                        last_runtime_id = info.runtime_id;
                        if info.runtime_id.is_none() {
                            last_name_fp = format!("{}|{}|{}", info.automation_id, info.control_type, info.class_name);
                        }
                        build_and_record_uia_click(&info, &last_title);
                    }
                }
            }
            Err(_) => {
                last_title = prev_title_for_err;
                last_runtime_id = prev_rid;
                last_name_fp = prev_fp_for_err;
            }
        }
    }
}

/// 合并窗口标题 + UIA 焦点元素轮询（单次 std::thread）
///
/// 在一个线程内完成所有 Win32/COM 调用：
///   1. GetForegroundWindow + GetWindowTextW（~1ms）
///   2. UIAutomation::new + get_focused_element + 5 个快速属性（~10-20ms）
///   3. 仅元素变化时读取剩余属性（~5-10ms 额外）
fn poll_combined(
    prev_title: String,
    prev_rid: Option<i64>,
    prev_fp: String,
) -> PollOutput {
    use std::sync::mpsc;

    let (tx, rx) = mpsc::sync_channel(1);

    std::thread::Builder::new()
        .name("tupai-uia-poll".into())
        .spawn(move || {
            // 1. 窗口标题
            let (title, _hwnd_ok) = get_foreground_window_title();
            let title_changed = !title.is_empty() && title != prev_title;

            // 2. UIA 快速路径：只读 5 个属性做去重
            let mut element_changed = false;
            let mut element_info = None;

            if let Ok(uia) = uiautomation::core::UIAutomation::new() {
                if let Ok(element) = uia.get_focused_element() {
                    let name = element.get_name().unwrap_or_default();
                    let class_name = element.get_classname().unwrap_or_default();
                    let automation_id = element.get_automation_id().unwrap_or_default();
                    let control_type = element.get_control_type().map(|ct| ct.to_string()).unwrap_or_default();
                    let runtime_id = element.get_runtime_id().ok()
                        .and_then(|ids| ids.first().copied())
                        .map(|id| id as i64);

                    // 去重检查
                    let is_same = match runtime_id {
                        Some(rid) => Some(rid) == prev_rid,
                        None => {
                            let fp = format!("{}|{}|{}", automation_id, control_type, class_name);
                            fp == prev_fp
                        }
                    };

                    if !is_same {
                        // 慢路径：读取全量属性
                        let help_text = element.get_help_text().unwrap_or_default();
                        let item_status = element.get_item_status().unwrap_or_default();
                        let is_enabled = element.is_enabled().unwrap_or(true);
                        let access_key = element.get_access_key().unwrap_or_default();
                        let accelerator_key = element.get_accelerator_key().unwrap_or_default();
                        let process_id = element.get_process_id().unwrap_or(0);

                        element_changed = true;
                        element_info = Some(FullElementInfo {
                            name,
                            class_name,
                            automation_id,
                            control_type,
                            runtime_id,
                            help_text,
                            item_status,
                            is_enabled,
                            access_key,
                            accelerator_key,
                            process_id,
                        });
                    }
                }
            }

            let _ = tx.send(PollOutput {
                title_changed,
                title,
                element_changed,
                element_info,
            });
        })
        .ok();

    rx.recv_timeout(Duration::from_millis(UIA_POLL_TIMEOUT_MS))
        .ok()
        .unwrap_or(PollOutput {
            title_changed: false,
            title: String::new(),
            element_changed: false,
            element_info: None,
        })
}

/// 构建 UIA Click 动作并写入缓冲区
fn build_and_record_uia_click(info: &FullElementInfo, window_title: &str) {
    let (selector_type, selector_value) =
        if !info.automation_id.is_empty() {
            ("uia_id".to_string(), info.automation_id.clone())
        } else if !info.control_type.is_empty() && !info.name.is_empty() {
            ("uia_name".to_string(), info.name.clone())
        } else if !info.control_type.is_empty() && !info.help_text.is_empty() {
            ("uia_help".to_string(), info.help_text.clone())
        } else if !info.class_name.is_empty() && !info.name.is_empty() {
            ("uia_class".to_string(), info.class_name.clone())
        } else if !info.class_name.is_empty() {
            ("uia_class".to_string(), info.class_name.clone())
        } else if !info.access_key.is_empty() {
            ("uia_access".to_string(), info.access_key.clone())
        } else if !info.accelerator_key.is_empty() {
            ("uia_accel".to_string(), info.accelerator_key.clone())
        } else {
            return;
        };

    let mut fallbacks: Vec<FallbackSelector> = Vec::new();

    if selector_type != "uia_id" && !info.automation_id.is_empty() {
        fallbacks.push(FallbackSelector {
            selector_type: "uia_id".to_string(),
            value: info.automation_id.clone(),
        });
    }
    if !info.control_type.is_empty() && !info.name.is_empty() {
        fallbacks.push(FallbackSelector {
            selector_type: "uia_combined".to_string(),
            value: format!("uia:controlType={};name={}", info.control_type, info.name),
        });
    }
    if !info.control_type.is_empty() && !info.help_text.is_empty() {
        fallbacks.push(FallbackSelector {
            selector_type: "uia_help".to_string(),
            value: format!("uia:controlType={};helpText={}", info.control_type, info.help_text),
        });
    }
    if !info.class_name.is_empty() && !info.name.is_empty() {
        fallbacks.push(FallbackSelector {
            selector_type: "uia_class".to_string(),
            value: format!("uia:className={};name={}", info.class_name, info.name),
        });
    }
    if selector_type != "uia_class" && !info.class_name.is_empty() {
        fallbacks.push(FallbackSelector {
            selector_type: "uia_class".to_string(),
            value: info.class_name.clone(),
        });
    }
    if selector_type != "uia_access" && !info.access_key.is_empty() {
        fallbacks.push(FallbackSelector {
            selector_type: "uia_access".to_string(),
            value: info.access_key.clone(),
        });
    }
    if selector_type != "uia_accel" && !info.accelerator_key.is_empty() {
        fallbacks.push(FallbackSelector {
            selector_type: "uia_accel".to_string(),
            value: info.accelerator_key.clone(),
        });
    }
    if info.process_id > 0 && !info.control_type.is_empty() {
        fallbacks.push(FallbackSelector {
            selector_type: "uia_process".to_string(),
            value: format!("uia:processId={};controlType={}", info.process_id, info.control_type),
        });
    }

    let app_name = if window_title.is_empty() {
        "unknown_app".to_string()
    } else {
        extract_app_name_from_title(window_title)
    };

    let mut action = RecordedAction::new(&app_name, window_title, ActionType::Click, "uia")
        .with_target(ElementSelector {
            selector_type,
            value: selector_value,
            text_content: if info.name.is_empty() { None } else { Some(info.name.clone()) },
            bounds: None,
            fallback_selectors: fallbacks,
        });

    let uia_selector_str = build_uia_selector_string(info);
    action = action.with_data(uia_selector_str);

    crate::recording::recorder::add_action_to_buffer(action);
}

/// 构建 UIA 选择器字符串
fn build_uia_selector_string(info: &FullElementInfo) -> String {
    let mut parts = Vec::with_capacity(10);
    if !info.control_type.is_empty() {
        parts.push(format!("controlType={}", info.control_type));
    }
    if !info.name.is_empty() {
        parts.push(format!("name={}", info.name));
    }
    if !info.automation_id.is_empty() {
        parts.push(format!("automationId={}", info.automation_id));
    }
    if !info.class_name.is_empty() {
        parts.push(format!("className={}", info.class_name));
    }
    if !info.help_text.is_empty() {
        parts.push(format!("helpText={}", info.help_text));
    }
    if !info.item_status.is_empty() {
        parts.push(format!("itemStatus={}", info.item_status));
    }
    if !info.access_key.is_empty() {
        parts.push(format!("accessKey={}", info.access_key));
    }
    if !info.accelerator_key.is_empty() {
        parts.push(format!("acceleratorKey={}", info.accelerator_key));
    }
    parts.push(format!("isEnabled={}", info.is_enabled));
    parts.push(format!("processId={}", info.process_id));
    format!("uia:{}", parts.join(";"))
}

/// 从窗口标题提取软件名称
fn extract_app_name_from_title(title: &str) -> String {
    if title.is_empty() {
        return "unknown_app".to_string();
    }
    let parts: Vec<&str> = title.splitn(2, '-').collect();
    let name = parts[0].trim();
    if !name.is_empty() {
        return name.to_string();
    }
    "unknown_app".to_string()
}

/// 调用 Win32 API 获取前台窗口标题
#[cfg(target_os = "windows")]
fn get_foreground_window_title() -> (String, bool) {
    use windows::Win32::UI::WindowsAndMessaging::{GetForegroundWindow, GetWindowTextW};

    unsafe {
        let hwnd = GetForegroundWindow();
        if hwnd.0.is_null() {
            return (String::new(), false);
        }

        let mut buf = [0u16; 512];
        let len = GetWindowTextW(hwnd, &mut buf);
        if len == 0 {
            return (String::new(), true);
        }

        let title = String::from_utf16_lossy(&buf[..len as usize]);
        (title, true)
    }
}

#[cfg(not(target_os = "windows"))]
fn get_foreground_window_title() -> (String, bool) {
    (String::new(), false)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_extract_app_name_from_title() {
        assert_eq!(extract_app_name_from_title("同花顺 iFinD - 沪深300"), "同花顺 iFinD");
        assert_eq!(extract_app_name_from_title("AIMarketing - AIMarketing"), "AIMarketing");
        assert_eq!(extract_app_name_from_title("记事本"), "记事本");
        assert_eq!(extract_app_name_from_title(""), "unknown_app");
    }

    #[test]
    fn test_extract_app_name_strips_whitespace() {
        assert_eq!(extract_app_name_from_title("  Excel  - Book1"), "Excel");
    }
}
