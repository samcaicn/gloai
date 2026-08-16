// Copyright (c) 2026 tupAI
//
// 录制动作 (RecordedAction) → 流程图 (Flowchart) 转换
//
// 后台录制系统存储的是 RecordedAction（含 protocol 字段标记来源），
// 前端流程图组件期望的是结构化的 Flowchart JSON。
// 此模块负责把录制的动作序列转成可渲染的流程图。
//
// 与 automation/flowchart.rs::events_to_flowchart 的区别：
//   * 输入：RecordedAction（后台录制，含 protocol 字段）
//   * 输出：复用 Flowchart 类型，附带 recognition 字段标记来源
//   * 合并连续 Type 动作为单个输入节点
//   * Focus 动作（UIA 窗口切换）作为 process 节点展示

use serde_json;

use crate::automation::flowchart::{Flowchart, FlowchartConnection, FlowchartNode};
use crate::recording::action::{ActionType, RecordedAction};

/// 把录制动作序列转成可视化流程图
///
/// 输入是 `read_recent_batches` 返回的 `RecordedAction` 数组，
/// 输出是带 start / 操作节点 / end 框架的线性流程图。
///
/// 去重策略（两层）:
///   1. 批内去重: `RecordingBatch::new` 每5s批次内按 dedup_hash 去重
///   2. 跨批连续去重: 本函数在转流程图前，移除连续相同动作
///      （同一 selector + action_type 连续出现只保留第一个）
///   注意：非连续的相同动作会保留（如 Click A → Click B → Click A）
pub fn actions_to_flowchart(actions: &[RecordedAction]) -> Flowchart {
    // 跨批连续去重：移除相邻的重复动作（同 dedup_hash）
    // 避免5s批次边界导致同一操作被分成两个节点
    let deduped: Vec<&RecordedAction> = {
        let mut result: Vec<&RecordedAction> = Vec::with_capacity(actions.len());
        let mut last_hash: Option<u64> = None;
        for action in actions {
            let hash = action.dedup_hash();
            if Some(hash) != last_hash {
                result.push(action);
                last_hash = Some(hash);
            }
        }
        result
    };

    let mut nodes: Vec<FlowchartNode> = Vec::new();
    let mut connections: Vec<FlowchartConnection> = Vec::new();
    let mut pending_text = String::new();
    let mut pending_text_first_idx: Option<u32> = None;
    let mut pending_text_protocol: String = String::new();

    // start 节点
    nodes.push(FlowchartNode {
        id: "n0_start".to_string(),
        r#type: "start".to_string(),
        label: "开始".to_string(),
        action: None,
        meta: None,
        source_event_idx: None,
        recognition: None,
    });
    let mut prev_id: Option<String> = Some("n0_start".to_string());

    let mut counter: u32 = 1;
    let mut step_count: u32 = 0;
    let mut iter_idx: usize = 0;

    while iter_idx < deduped.len() {
        let action = deduped[iter_idx];
        match action.action_type {
            ActionType::Click | ActionType::DoubleClick | ActionType::RightClick => {
                flush_text_buf(
                    &mut pending_text,
                    &mut pending_text_first_idx,
                    &mut pending_text_protocol,
                    &mut nodes,
                    &mut connections,
                    &mut prev_id,
                    &mut counter,
                    &mut step_count,
                );

                let (action_str, btn_label) = match action.action_type {
                    ActionType::Click => ("click", ""),
                    ActionType::DoubleClick => ("dblclick", "双击"),
                    ActionType::RightClick => ("contextmenu", "右键"),
                    _ => unreachable!(),
                };

                let target_label = action
                    .target
                    .as_ref()
                    .map(|t| {
                        if let Some(text) = &t.text_content {
                            if !text.is_empty() {
                                return truncate_str(text, 32);
                            }
                        }
                        truncate_str(&t.value, 32)
                    })
                    .unwrap_or_default();

                let id = format!("n{}_{}", counter, make_short_id());
                counter += 1;
                // 标签格式：点击 元素名 [定位方式]
                // 如 "点击 提交 [css]" / "点击 保存按钮 [uia_id]" / "双击 搜索 [xpath]"
                let selector_tag = action.target.as_ref().map(|t| {
                    match t.selector_type.as_str() {
                        "css" => "css",
                        "xpath" => "xpath",
                        "uia_id" => "uia_id",
                        "uia_name" => "uia_name",
                        "uia_class" => "uia_class",
                        "text" => "text",
                        "bounds" => "bounds",
                        other => other,
                    }
                });
                let label = if target_label.is_empty() {
                    format!("{}{}", btn_label, "点击")
                } else {
                    match selector_tag {
                        Some(tag) if tag != "bounds" => format!("{}点击 {} [{}]", btn_label, target_label, tag),
                        _ => format!("{}点击 {}", btn_label, target_label),
                    }
                };

                let mut meta = serde_json::Map::new();
                if let Some(t) = &action.target {
                    meta.insert("selector".to_string(), serde_json::json!(t.value));
                    meta.insert("selectorType".to_string(), serde_json::json!(t.selector_type));
                    if let Some(text) = &t.text_content {
                        meta.insert("text".to_string(), serde_json::json!(text));
                    }
                    // 不再存储 bounds 坐标信息 — 流程图仅记录元素选择器
                    // UIA 选择器详情：从 action_data 中解析 uia: 选择器字符串
                    if action.protocol == "uia" {
                        if let Some(data) = &action.action_data {
                            if data.starts_with("uia:") {
                                meta.insert("uiaSelector".to_string(), serde_json::json!(data));
                            }
                        }
                    }
                }

                if let Some(pid) = &prev_id {
                    connections.push(FlowchartConnection {
                        from: pid.clone(),
                        to: id.clone(),
                        label: None,
                    });
                }
                nodes.push(FlowchartNode {
                    id: id.clone(),
                    r#type: "process".to_string(),
                    label,
                    action: Some(action_str.to_string()),
                    meta: Some(serde_json::Value::Object(meta)),
                    source_event_idx: Some(iter_idx as u32),
                    recognition: Some(vec![action.protocol.clone()]),
                });
                prev_id = Some(id);
                step_count += 1;
                iter_idx += 1;
            }
            ActionType::Type => {
                // 连续 Type 动作合并为一个输入节点
                let data = action.action_data.clone().unwrap_or_default();
                if pending_text_first_idx.is_none() {
                    pending_text_first_idx = Some(iter_idx as u32);
                    pending_text_protocol = action.protocol.clone();
                }
                if !pending_text.is_empty() {
                    pending_text.push(' ');
                }
                pending_text.push_str(&data);
                iter_idx += 1;
            }
            ActionType::KeyDown => {
                flush_text_buf(
                    &mut pending_text,
                    &mut pending_text_first_idx,
                    &mut pending_text_protocol,
                    &mut nodes,
                    &mut connections,
                    &mut prev_id,
                    &mut counter,
                    &mut step_count,
                );

                let key = action.action_data.clone().unwrap_or_else(|| "Unknown".to_string());
                let id = format!("n{}_{}", counter, make_short_id());
                counter += 1;
                let label = format!("按键 {}", truncate_str(&key, 24));

                let mut meta = serde_json::Map::new();
                meta.insert("key".to_string(), serde_json::json!(key));

                if let Some(pid) = &prev_id {
                    connections.push(FlowchartConnection {
                        from: pid.clone(),
                        to: id.clone(),
                        label: None,
                    });
                }
                nodes.push(FlowchartNode {
                    id: id.clone(),
                    r#type: "io".to_string(),
                    label,
                    action: Some("hotkey".to_string()),
                    meta: Some(serde_json::Value::Object(meta)),
                    source_event_idx: Some(iter_idx as u32),
                    recognition: Some(vec![action.protocol.clone()]),
                });
                prev_id = Some(id);
                step_count += 1;
                iter_idx += 1;
            }
            ActionType::Focus => {
                flush_text_buf(
                    &mut pending_text,
                    &mut pending_text_first_idx,
                    &mut pending_text_protocol,
                    &mut nodes,
                    &mut connections,
                    &mut prev_id,
                    &mut counter,
                    &mut step_count,
                );

                // Focus 通常是 UIA 窗口切换
                let window_title = action
                    .target
                    .as_ref()
                    .map(|t| t.value.clone())
                    .or_else(|| action.action_data.clone())
                    .unwrap_or_else(|| "窗口切换".to_string());

                let id = format!("n{}_{}", counter, make_short_id());
                counter += 1;
                let label = format!("切换到 {}", truncate_str(&window_title, 32));

                let mut meta = serde_json::Map::new();
                meta.insert("window".to_string(), serde_json::json!(window_title));
                meta.insert("app".to_string(), serde_json::json!(action.app_name));

                if let Some(pid) = &prev_id {
                    connections.push(FlowchartConnection {
                        from: pid.clone(),
                        to: id.clone(),
                        label: None,
                    });
                }
                nodes.push(FlowchartNode {
                    id: id.clone(),
                    r#type: "process".to_string(),
                    label,
                    action: Some("focus".to_string()),
                    meta: Some(serde_json::Value::Object(meta)),
                    source_event_idx: Some(iter_idx as u32),
                    recognition: Some(vec![action.protocol.clone()]),
                });
                prev_id = Some(id);
                step_count += 1;
                iter_idx += 1;
            }
            ActionType::Scroll | ActionType::Select | ActionType::MouseMove => {
                // Scroll/Select 作为轻量操作节点；MouseMove 跳过
                if matches!(action.action_type, ActionType::MouseMove) {
                    iter_idx += 1;
                    continue;
                }

                flush_text_buf(
                    &mut pending_text,
                    &mut pending_text_first_idx,
                    &mut pending_text_protocol,
                    &mut nodes,
                    &mut connections,
                    &mut prev_id,
                    &mut counter,
                    &mut step_count,
                );

                let (action_str, label_prefix) = match action.action_type {
                    ActionType::Scroll => ("scroll", "滚动"),
                    ActionType::Select => ("select", "选择"),
                    _ => unreachable!(),
                };

                let target_label = action
                    .target
                    .as_ref()
                    .map(|t| truncate_str(&t.value, 32))
                    .unwrap_or_default();
                let data = action.action_data.clone().unwrap_or_default();

                let id = format!("n{}_{}", counter, make_short_id());
                counter += 1;
                let label = if target_label.is_empty() {
                    format!("{} ({})", label_prefix, truncate_str(&data, 24))
                } else {
                    format!("{} {}", label_prefix, target_label)
                };

                let mut meta = serde_json::Map::new();
                if let Some(t) = &action.target {
                    meta.insert("selector".to_string(), serde_json::json!(t.value));
                }
                if !data.is_empty() {
                    meta.insert("data".to_string(), serde_json::json!(data));
                }

                if let Some(pid) = &prev_id {
                    connections.push(FlowchartConnection {
                        from: pid.clone(),
                        to: id.clone(),
                        label: None,
                    });
                }
                nodes.push(FlowchartNode {
                    id: id.clone(),
                    r#type: "process".to_string(),
                    label,
                    action: Some(action_str.to_string()),
                    meta: Some(serde_json::Value::Object(meta)),
                    source_event_idx: Some(iter_idx as u32),
                    recognition: Some(vec![action.protocol.clone()]),
                });
                prev_id = Some(id);
                step_count += 1;
                iter_idx += 1;
            }
        }
    }

    // 收尾：flush 残余文本 + end 节点
    flush_text_buf(
        &mut pending_text,
        &mut pending_text_first_idx,
        &mut pending_text_protocol,
        &mut nodes,
        &mut connections,
        &mut prev_id,
        &mut counter,
        &mut step_count,
    );

    let end_id = format!("n{}_end", counter);
    if let Some(pid) = &prev_id {
        connections.push(FlowchartConnection {
            from: pid.clone(),
            to: end_id.clone(),
            label: None,
        });
    }
    nodes.push(FlowchartNode {
        id: end_id,
        r#type: "end".to_string(),
        label: "结束".to_string(),
        action: None,
        meta: None,
        source_event_idx: None,
        recognition: None,
    });

    Flowchart {
        title: "后台录制流程".to_string(),
        layout: "TB".to_string(),
        style: "business".to_string(),
        source: "recorder".to_string(),
        nodes,
        connections,
        step_count,
    }
}

/// 把挂起的文本 buffer 物化为一个 type 节点
fn flush_text_buf(
    buf: &mut String,
    first_idx: &mut Option<u32>,
    protocol: &mut String,
    nodes: &mut Vec<FlowchartNode>,
    connections: &mut Vec<FlowchartConnection>,
    prev_id: &mut Option<String>,
    counter: &mut u32,
    step_count: &mut u32,
) {
    if buf.is_empty() {
        return;
    }
    let id = format!("n{}_{}", counter, make_short_id());
    *counter += 1;
    let label = format!("输入 \"{}\"", truncate_str(buf, 48));

    let mut meta = serde_json::Map::new();
    meta.insert("text".to_string(), serde_json::json!(buf.as_str()));

    if let Some(pid) = prev_id {
        connections.push(FlowchartConnection {
            from: pid.clone(),
            to: id.clone(),
            label: None,
        });
    }
    let proto = if protocol.is_empty() { "cdp" } else { protocol.as_str() };
    nodes.push(FlowchartNode {
        id,
        r#type: "io".to_string(),
        label,
        action: Some("type".to_string()),
        meta: Some(serde_json::Value::Object(meta)),
        source_event_idx: *first_idx,
        recognition: Some(vec![proto.to_string()]),
    });
    *prev_id = nodes.last().map(|n| n.id.clone());
    *step_count += 1;
    buf.clear();
    *first_idx = None;
    protocol.clear();
}

fn truncate_str(s: &str, max: usize) -> String {
    if s.chars().count() <= max {
        s.to_string()
    } else {
        let truncated: String = s.chars().take(max).collect();
        format!("{}…", truncated)
    }
}

/// 生成 8 位 hex 后缀，避免跨多次录制 / 同时间戳的节点 id 冲突。
/// 全局 AtomicU64 单调递增，不截断（64 位空间足够整个进程生命周期使用），
/// 解决原来 4 位 hex（& 0xFFFF）截断后多次录制 ID 撞车的问题。
fn make_short_id() -> String {
    use std::sync::atomic::{AtomicU64, Ordering};
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let n = COUNTER.fetch_add(1, Ordering::Relaxed);
    format!("{:08x}", n)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::recording::action::{ElementSelector, RecordedAction};

    fn make_click(app: &str, protocol: &str, text: &str) -> RecordedAction {
        RecordedAction::new(app, "test", ActionType::Click, protocol)
            .with_target(ElementSelector {
                selector_type: "css".to_string(),
                value: "#btn".to_string(),
                text_content: Some(text.to_string()),
                bounds: None,
                fallback_selectors: vec![],
            })
    }

    fn make_click_with_selector(
        app: &str,
        protocol: &str,
        text: &str,
        selector: &str,
    ) -> RecordedAction {
        RecordedAction::new(app, "test", ActionType::Click, protocol)
            .with_target(ElementSelector {
                selector_type: "css".to_string(),
                value: selector.to_string(),
                text_content: Some(text.to_string()),
                bounds: None,
                fallback_selectors: vec![],
            })
    }

    fn make_type(app: &str, protocol: &str, text: &str) -> RecordedAction {
        RecordedAction::new(app, "test", ActionType::Type, protocol)
            .with_data(text)
    }

    fn make_focus(app: &str, window: &str) -> RecordedAction {
        RecordedAction::new(app, window, ActionType::Focus, "uia")
            .with_target(ElementSelector {
                selector_type: "uia_name".to_string(),
                value: window.to_string(),
                text_content: Some(window.to_string()),
                bounds: None,
                fallback_selectors: vec![],
            })
    }

    #[test]
    fn empty_actions_produces_minimal() {
        let fc = actions_to_flowchart(&[]);
        assert_eq!(fc.step_count, 0);
        assert_eq!(fc.nodes.len(), 2);
        assert_eq!(fc.nodes[0].r#type, "start");
        assert_eq!(fc.nodes[1].r#type, "end");
    }

    #[test]
    fn click_produces_process_node_with_recognition() {
        let actions = vec![make_click("TestApp", "cdp", "提交")];
        let fc = actions_to_flowchart(&actions);
        assert_eq!(fc.step_count, 1);
        assert_eq!(fc.nodes.len(), 3);
        let click_node = &fc.nodes[1];
        assert_eq!(click_node.r#type, "process");
        assert_eq!(click_node.action.as_deref(), Some("click"));
        assert_eq!(
            click_node.recognition.as_deref(),
            Some(&["cdp".to_string()][..])
        );
    }

    #[test]
    fn consecutive_types_merge() {
        let actions = vec![
            make_type("App", "cdp", "hello"),
            make_type("App", "cdp", "world"),
        ];
        let fc = actions_to_flowchart(&actions);
        assert_eq!(fc.step_count, 1);
        assert_eq!(fc.nodes.len(), 3);
        let type_node = &fc.nodes[1];
        assert_eq!(type_node.action.as_deref(), Some("type"));
        let text = type_node.meta.as_ref().unwrap().get("text").unwrap().as_str().unwrap();
        assert!(text.contains("hello"));
        assert!(text.contains("world"));
    }

    #[test]
    fn uia_focus_creates_process_node() {
        let actions = vec![make_focus("记事本", "记事本 - 无标题")];
        let fc = actions_to_flowchart(&actions);
        assert_eq!(fc.step_count, 1);
        let focus_node = &fc.nodes[1];
        assert_eq!(focus_node.action.as_deref(), Some("focus"));
        assert_eq!(
            focus_node.recognition.as_deref(),
            Some(&["uia".to_string()][..])
        );
        assert!(focus_node.label.contains("记事本"));
    }

    #[test]
    fn mixed_cdp_uia_actions() {
        let actions = vec![
            make_focus("Excel", "Excel - Book1"),
            make_click("Excel", "uia", "保存"),
            make_type("Excel", "uia", "数据"),
        ];
        let fc = actions_to_flowchart(&actions);
        assert_eq!(fc.step_count, 3);
        assert_eq!(fc.nodes.len(), 5); // start + focus + click + type + end
    }

    #[test]
    fn cross_batch_consecutive_dedup() {
        // 模拟跨批次：同一按钮在不同5s批次中被点击
        // 批内已去重，但跨批连续相同动作应被合并
        let click1 = make_click("App", "cdp", "提交");
        let click2 = make_click("App", "cdp", "提交"); // 跨批连续重复
        let click3 = make_click("App", "cdp", "提交"); // 跨批连续重复
        let actions = vec![click1, click2, click3];
        let fc = actions_to_flowchart(&actions);
        // 3个连续相同点击 → 只保留1个节点
        assert_eq!(fc.step_count, 1);
        assert_eq!(fc.nodes.len(), 3); // start + 1 click + end
    }

    #[test]
    fn non_consecutive_duplicates_kept() {
        // 非连续的相同动作应保留（用户在不同位置点击同一按钮）
        // 注意: dedup_hash 基于 selector 而非 text_content，
        // 所以不同按钮必须用不同 selector value
        let click_a = make_click_with_selector("App", "cdp", "按钮A", "#btnA");
        let click_b = make_click_with_selector("App", "cdp", "按钮B", "#btnB");
        let click_a2 = make_click_with_selector("App", "cdp", "按钮A", "#btnA");
        let actions = vec![click_a, click_b, click_a2];
        let fc = actions_to_flowchart(&actions);
        assert_eq!(fc.step_count, 3); // 3个操作节点都保留
    }

    #[test]
    fn uia_click_with_selector_in_meta() {
        // UIA 点击节点应包含 uiaSelector 字段和 [uia_id] 标签
        let action = RecordedAction::new("Excel", "test", ActionType::Click, "uia")
            .with_target(ElementSelector {
                selector_type: "uia_id".to_string(),
                value: "save_btn".to_string(),
                text_content: Some("保存".to_string()),
                bounds: None,
                fallback_selectors: vec![],
            })
            .with_data("uia:controlType=Button;name=保存;automationId=save_btn".to_string());
        let fc = actions_to_flowchart(&[action]);
        assert_eq!(fc.step_count, 1);
        let click_node = &fc.nodes[1];
        assert_eq!(click_node.r#type, "process");
        assert_eq!(click_node.action.as_deref(), Some("click"));
        // 标签包含选择器类型标记
        assert!(click_node.label.contains("保存"));
        assert!(click_node.label.contains("[uia_id]"));
        // meta 包含 uiaSelector
        let meta = click_node.meta.as_ref().unwrap();
        assert_eq!(meta.get("selectorType").unwrap().as_str().unwrap(), "uia_id");
        assert_eq!(meta.get("selector").unwrap().as_str().unwrap(), "save_btn");
        assert!(meta.get("uiaSelector").is_some());
    }

    #[test]
    fn css_click_label_contains_selector_type() {
        // CDP 点击节点标签应包含 [css]
        let action = make_click("App", "cdp", "提交");
        let fc = actions_to_flowchart(&[action]);
        let click_node = &fc.nodes[1];
        assert!(click_node.label.contains("[css]"));
    }
}
