// Copyright (c) 2026 MeeJoy
//
// Recorded-event → Flowchart conversion.
//
// 录制数据 (RecordedEvent) 落到数据库/前端时只有 skill.md (YAML 步骤) 这一种形态，
// 前端流程图组件 (`FlowchartView`) 期望的是结构化的 {nodes, connections, judgments}。
// 这个模块负责把录制的事件序列转成可渲染、可编辑的流程图 JSON。
//
// 设计原则：
//   1. 节点按时间顺序串联，start → 连续操作节点 → end
//   2. 连续可打印按键 (a-z, 0-9, 标点) 合并为一个 type 节点，避免单字符节点
//   3. 不可打印按键 (Enter / Esc / F1 …) 单独生成 hotkey 节点
//   4. 鼠标点击：左/中/右键分别建节点，按钮文本通过 metadata 透传
//   5. 节点 id 用 `n{index}` 格式，方便前端做 React key 和 O(1) 查找
//   6. 任何时刻 RecordedEvent 为空 → 返回只含 {start, end} 的最小流程图
//   7. 截图/State 标记/MouseMove 不会成为节点（这些不构成确定性操作）
//   8. 保留原始事件引用 (`sourceEventIdx`)，编辑界面可定位回原始数据
//   9. 节点间连接默认线性串联 (a→b→c→…→end)，没有分支 —— 录制就是顺序的

use serde::{Deserialize, Serialize};

use crate::automation::recorder::{ClickElementInfo, RecordedEvent};

// ── dedup 调参 ────────────────────────────────────────────────────────
//
// dedup_clicks_by_element 的两个关键阈值：
//   1. MAX_SKIPPED_NON_ACTION_EVENTS：
//      向前找上一个被保留的 MouseClick 时，最多允许跳过多少个非操作事件
//      （MouseMove / Screenshot / State）。超过则视为"用户有意再次点击
//      同一按钮"（如确认对话框），不算重复。
//      阈值 5：录制时 MouseMove 采样间隔最小 120ms，5 个事件约 600ms，
//      加上 Screenshot 限流 500ms，约等于"用户两次有意点击"的最小间隔。
//   2. CLICK_DEDUP_COORD_FALLBACK_PX：
//      当两个 MouseClick 的 element 都为 None（UIA 查询失败 / 非 Windows
//      平台）时的坐标距离 fallback。距离 ≤ 此值视为同一按钮的重复点击。
//      比 recorder.rs 中的 CLICK_DEDUP_TOLERANCE_PX (6) 大——这里允许更大
//      容差，因为元素级去重已失效，坐标去重是最后防线。
const MAX_SKIPPED_NON_ACTION_EVENTS: usize = 5;
const CLICK_DEDUP_COORD_FALLBACK_PX: i32 = 20;

// ── Wire types ────────────────────────────────────────────────────────
//
// 字段名刻意走 camelCase，前端 FlowchartView 直接消费不做转换。
// 节点类型与 AutomationPage.jsx 的 NODE_STYLE 表完全对齐。

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FlowchartNode {
    pub id: String,
    /// 节点类型：start / end / process / decision / io
    /// 与前端的 NODE_STYLE map 一一对应。
    #[serde(rename = "type")]
    pub r#type: String,
    pub label: String,
    /// 可选：原始事件类型 (click / type / hotkey) 便于 UI 区分图标
    #[serde(skip_serializing_if = "Option::is_none")]
    pub action: Option<String>,
    /// 可选：录制时鼠标坐标 / 键盘按键
    #[serde(skip_serializing_if = "Option::is_none")]
    pub meta: Option<serde_json::Value>,
    /// 在源 events 数组中的索引，便于"跳回原始数据"按钮
    #[serde(skip_serializing_if = "Option::is_none")]
    pub source_event_idx: Option<u32>,
    /// 可选：识别层级 (["cdp"] / ["uia"]) — 前端据此显示来源协议
    #[serde(skip_serializing_if = "Option::is_none")]
    pub recognition: Option<Vec<String>>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FlowchartConnection {
    pub from: String,
    pub to: String,
    /// 分支标签 (yes/no/true/false)，纯线性流程时为 None
    #[serde(skip_serializing_if = "Option::is_none")]
    pub label: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Flowchart {
    pub title: String,
    pub layout: String,
    pub style: String,
    /// 操作源标识 (recorder / builtin / manual) — 区分来源
    pub source: String,
    pub nodes: Vec<FlowchartNode>,
    pub connections: Vec<FlowchartConnection>,
    /// 总步骤数（不含 start/end 框架节点），方便 UI 状态条展示
    pub step_count: u32,
}

// ── 转换入口 ──────────────────────────────────────────────────────────

/// 判断两个元素身份是否指向同一 UI 元素。
/// 优先用 automation_id（最精确），其次用 name + control_type + class_name 组合。
fn elements_match(a: &ClickElementInfo, b: &ClickElementInfo) -> bool {
    // automation_id 非空且一致 → 同一元素
    if !a.automation_id.is_empty() && !b.automation_id.is_empty() {
        return a.automation_id == b.automation_id;
    }
    // name 非空且一致 + control_type 一致 + class_name 一致 → 同一元素
    if !a.name.is_empty() && a.name == b.name {
        return a.control_type == b.control_type && a.class_name == b.class_name;
    }
    false
}

/// 基于元素身份去重连续点击事件（带时间窗口 + 坐标 fallback）。
///
/// 场景：用户双击 / 三连击同一按钮，或手抖同位置快速点击，但坐标偏差 > 6px
/// 导致 recorder 的坐标去重没生效。此处用 UIA 元素身份做第二层去重。
///
/// 规则：
///   1. 当前 MouseClick 与上一个被保留的 MouseClick 指向同一元素 → 后者跳过
///   2. 中间穿插的 KeyPress / BrowserAction → 视为"有其他操作"，不算重复
///   3. 中间穿插的 MouseMove / Screenshot / State → 跳过继续向前找，
///      但最多跳过 `MAX_SKIPPED_NON_ACTION_EVENTS` 个；超过视为"用户有意
///      再次点击同按钮"（如确认对话框），不算重复
///   4. element 都为 None（UIA 失败 / 非 Windows）→ 用坐标距离 fallback
///      （≤ `CLICK_DEDUP_COORD_FALLBACK_PX` 视为重复）
///   5. element 一个有一个没有 → 不算重复（无法判断，保守保留）
///
/// **性能优化**：原实现是 O(n²) — 对每个 click 都从 result 末尾向前遍历。
/// 长时间录制时（events 数量上千）会显著变慢。改为 O(n) — 只在遇到
/// `last_significant_event` 改变时更新状态：上一次 MouseClick / KeyPress /
/// BrowserAction 之间的非操作事件数。
pub fn dedup_clicks_by_element(events: &[RecordedEvent]) -> Vec<RecordedEvent> {
    // 单次扫描：用 last_click 记录上一个被保留的 MouseClick 的关键信息，
    // 配合 counter 记录距离上次"有意义操作"已积累的非操作事件数。
    let mut result: Vec<RecordedEvent> = Vec::with_capacity(events.len());
    let mut last_click: Option<LastClickState> = None;
    let mut non_action_since_last_significant: usize = 0;

    for ev in events {
        match ev {
            RecordedEvent::MouseClick {
                element,
                x,
                y,
                button: _,
            } => {
                let is_dup = is_dup_of_last_click(
                    last_click.as_ref(),
                    element.as_ref(),
                    *x,
                    *y,
                    non_action_since_last_significant,
                );
                if !is_dup {
                    last_click = Some(LastClickState {
                        element: element.clone(),
                        x: *x,
                        y: *y,
                    });
                    non_action_since_last_significant = 0;
                    result.push(ev.clone());
                }
                // 重复时不动 last_click（它指向上一个被保留的 click），
                // 但也不重置 non_action 计数 —— 让用户连续双击同按钮时
                // 第二次被去重，但若中间插了 5 个 MouseMove 后再点同按钮
                // 就不再去重（视为有意点击）。
            }
            RecordedEvent::KeyPress { .. } | RecordedEvent::BrowserAction { .. } => {
                // 有意义操作：清空 last_click 和 non_action 计数
                last_click = None;
                non_action_since_last_significant = 0;
                result.push(ev.clone());
            }
            _ => {
                // MouseMove / Screenshot / State：累加计数，达到上限后停
                non_action_since_last_significant =
                    non_action_since_last_significant.saturating_add(1);
                result.push(ev.clone());
            }
        }
    }
    result
}

struct LastClickState {
    element: Option<ClickElementInfo>,
    x: i32,
    y: i32,
}

fn is_dup_of_last_click(
    last: Option<&LastClickState>,
    curr_element: Option<&ClickElementInfo>,
    curr_x: i32,
    curr_y: i32,
    non_action_count: usize,
) -> bool {
    let last = match last {
        Some(l) => l,
        None => return false,
    };
    // 时间窗口检查：跳过太多非操作事件 → 视为用户有意再次点击
    if non_action_count > MAX_SKIPPED_NON_ACTION_EVENTS {
        return false;
    }
    match (last.element.as_ref(), curr_element) {
        (Some(prev_el), Some(curr_el)) => elements_match(prev_el, curr_el),
        (None, None) => {
            // 两个都没有元素身份 → 用坐标距离 fallback
            let dist = (curr_x - last.x).abs() + (curr_y - last.y).abs();
            dist <= CLICK_DEDUP_COORD_FALLBACK_PX
        }
        // 一个有一个没有 → 无法判断，保守视为不重复
        _ => false,
    }
}

/// 把录制事件序列转成可视化流程图。
///
/// 输入是 `Recorder::stop()` 返回的 `Vec<RecordedEvent>`，输出是
/// 带有 start / 操作节点 / end 框架的线性流程图。允许：
///   * 空 events → 最小框架 (start → end)
///   * 仅 MouseMove / Screenshot / State 事件 → 同样退化为最小框架
///   * 混合事件 → 按时间顺序生成对应节点
pub fn events_to_flowchart(events: &[RecordedEvent]) -> Flowchart {
    // 先做元素级去重：同一按钮的连续点击合并为一步
    let events: Vec<RecordedEvent> = dedup_clicks_by_element(events);
    let events = events.as_slice();
    let mut nodes: Vec<FlowchartNode> = Vec::new();
    let mut connections: Vec<FlowchartConnection> = Vec::new();
    let mut pending_text = String::new();
    let mut pending_text_first_idx: Option<u32> = None;
    // 累积 Delay 事件的毫秒数，在下一个操作节点创建时写入其 meta.delayMs
    let mut pending_delay_ms: u64 = 0;
    // 累积 MouseMove 坐标点，在下一个操作节点创建时写入其 meta.mouseTrajectory
    let mut pending_mouse_trajectory: Vec<(i32, i32)> = Vec::new();

    // 总是先放一个 start 节点
    nodes.push(FlowchartNode {
        id: "n0_start".to_string(),
        r#type: "start".to_string(),
        label: "开始录制".to_string(),
        action: None,
        meta: None,
        source_event_idx: None,
        recognition: None,
    });
    let mut prev_id: Option<String> = Some("n0_start".to_string());

    // 节点 id 计数器 —— 跳过 start
    let mut counter: u32 = 1;
    let mut step_count: u32 = 0;
    // 一次性 scanner：把 events 摊平成 step 节点，跳过无意义的 (move / screenshot / state)
    let mut iter_idx: usize = 0;
    while iter_idx < events.len() {
        let event = &events[iter_idx];
        match event {
            RecordedEvent::MouseClick { x, y, button, element } => {
                // 结束挂起的文本 buffer
                flush_text(
                    &mut pending_text,
                    &mut pending_text_first_idx,
                    &mut nodes,
                    &mut connections,
                    &mut prev_id,
                    &mut counter,
                    &mut step_count,
                    &mut pending_delay_ms,
                    &mut pending_mouse_trajectory,
                );
                let btn_display = match button.as_str() {
                    "left" => "左键",
                    "right" => "右键",
                    "middle" => "中键",
                    other => other,
                };
                let id = format!("n{}_{}", counter, make_short_id());
                counter += 1;
                // 优先使用元素名称作为标签（如"点击 [确定] Button 左键"），
                // 让流程图节点可读性大幅提升；无元素信息时回退到坐标。
                let label = match element {
                    Some(e) if !e.name.is_empty() => {
                        format!("点击 [{}] {} {}", e.name, e.control_type, btn_display)
                    }
                    _ => format!("点击 ({},{}) {}", x, y, btn_display),
                };
                let mut meta = serde_json::Map::new();
                meta.insert("x".to_string(), serde_json::json!(x));
                meta.insert("y".to_string(), serde_json::json!(y));
                meta.insert("button".to_string(), serde_json::json!(button));
                // 将元素身份信息写入 meta，供前端展示和后续执行定位
                if let Some(e) = element {
                    meta.insert("elementName".to_string(), serde_json::json!(e.name));
                    meta.insert("elementType".to_string(), serde_json::json!(e.control_type));
                    meta.insert("automationId".to_string(), serde_json::json!(e.automation_id));
                    meta.insert("className".to_string(), serde_json::json!(e.class_name));
                }
                // 写入累积的延时和鼠标轨迹
                apply_pending_meta(&mut meta, &mut pending_delay_ms, &mut pending_mouse_trajectory);
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
                    action: Some("click".to_string()),
                    meta: Some(serde_json::Value::Object(meta)),
                    source_event_idx: Some(iter_idx as u32),
                    recognition: None,
                });
                prev_id = Some(id);
                step_count += 1;
                iter_idx += 1;
            }
            RecordedEvent::KeyPress { key } => {
                // rdev 0.5 输出的 key 形如 "KeyA" / "Enter" / "\"a\"" / "Space"
                // 判定规则与 recorder.rs::is_printable_key 对齐
                if is_printable_key_inner(key) {
                    if pending_text_first_idx.is_none() {
                        pending_text_first_idx = Some(iter_idx as u32);
                    }
                    pending_text.push_str(&unescape_key_inner(key));
                    iter_idx += 1;
                    continue;
                }
                // 不可打印 → 先 flush 文本，然后单独成节点
                flush_text(
                    &mut pending_text,
                    &mut pending_text_first_idx,
                    &mut nodes,
                    &mut connections,
                    &mut prev_id,
                    &mut counter,
                    &mut step_count,
                    &mut pending_delay_ms,
                    &mut pending_mouse_trajectory,
                );
                let id = format!("n{}_{}", counter, make_short_id());
                counter += 1;
                let label = format!("按键 {}", key);
                let mut meta = serde_json::Map::new();
                meta.insert("key".to_string(), serde_json::json!(key));
                // 写入累积的延时和鼠标轨迹
                apply_pending_meta(&mut meta, &mut pending_delay_ms, &mut pending_mouse_trajectory);
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
                    recognition: None,
                });
                prev_id = Some(id);
                step_count += 1;
                iter_idx += 1;
            }
            RecordedEvent::BrowserAction { url, selector } => {
                flush_text(
                    &mut pending_text,
                    &mut pending_text_first_idx,
                    &mut nodes,
                    &mut connections,
                    &mut prev_id,
                    &mut counter,
                    &mut step_count,
                    &mut pending_delay_ms,
                    &mut pending_mouse_trajectory,
                );
                let id = format!("n{}_{}", counter, make_short_id());
                counter += 1;
                let mut label = "浏览器操作".to_string();
                if !url.is_empty() {
                    label.push_str(&format!(" {}", truncate_str(url, 32)));
                }
                if !selector.is_empty() {
                    label.push_str(&format!(" [{}]", truncate_str(selector, 24)));
                }
                let mut meta = serde_json::Map::new();
                meta.insert("url".to_string(), serde_json::json!(url));
                meta.insert("selector".to_string(), serde_json::json!(selector));
                // 写入累积的延时和鼠标轨迹
                apply_pending_meta(&mut meta, &mut pending_delay_ms, &mut pending_mouse_trajectory);
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
                    action: Some("browser".to_string()),
                    meta: Some(serde_json::Value::Object(meta)),
                    source_event_idx: Some(iter_idx as u32),
                    recognition: None,
                });
                prev_id = Some(id);
                step_count += 1;
                iter_idx += 1;
            }
            RecordedEvent::Delay { ms } => {
                // Delay 事件不生成独立节点，但记录到 pending_delay_ms，
                // 在下一个操作节点创建时写入其 meta.delayMs。
                // 这让回放引擎能在执行节点前模拟人类操作延时。
                if *ms > 0 {
                    pending_delay_ms = pending_delay_ms.saturating_add(*ms);
                }
                iter_idx += 1;
            }
            // MouseMove 事件：不生成独立节点，但收集坐标点写入下一个操作节点的
            // meta.mouseTrajectory，让回放引擎能在点击前模拟真实鼠标移动轨迹。
            // 随机扰动在 engine.rs 执行时添加，录制端只存原始坐标。
            RecordedEvent::MouseMove { x, y } => {
                pending_mouse_trajectory.push((*x, *y));
                iter_idx += 1;
            }
            // Screenshot / State 仍然跳过（无操作语义）
            RecordedEvent::Screenshot { .. }
            | RecordedEvent::State { .. } => {
                iter_idx += 1;
            }
        }
    }

    // 结尾：flush 残余文本，再加 end 节点
    flush_text(
        &mut pending_text,
        &mut pending_text_first_idx,
        &mut nodes,
        &mut connections,
        &mut prev_id,
        &mut counter,
        &mut step_count,
        &mut pending_delay_ms,
        &mut pending_mouse_trajectory,
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
        label: "结束录制".to_string(),
        action: None,
        meta: None,
        source_event_idx: None,
        recognition: None,
    });

    Flowchart {
        title: "录制流程图".to_string(),
        layout: "TB".to_string(),
        style: "business".to_string(),
        source: "recorder".to_string(),
        nodes,
        connections,
        step_count,
    }
}

/// 检测录制节点序列中的小循环（重复子序列）。
///
/// 在线性流程图生成后，扫描操作节点序列，找出重复出现的子序列模式。
/// 例如 [A, B, C, A, B, C] 会被识别为长度 3、重复 2 次的循环。
///
/// 检测条件：
///   * 子序列长度 ≥ 2（单节点重复不算循环，那是去重范畴）
///   * 重复次数 ≥ 2
///   * 子序列总长度 ≤ 8（太长的子序列不太可能是循环）
///   * 用节点指纹（type+label+action+meta）做比较，与 merge_flowcharts 一致
///
/// 返回检测到的循环提议列表，前端据此弹窗让用户确认是否合并。
pub fn detect_small_loops(nodes: &[FlowchartNode]) -> Vec<LoopMergeProposal> {
    // 只分析操作节点（排除 start/end 框架）
    let ops: Vec<&FlowchartNode> = nodes
        .iter()
        .filter(|n| n.r#type != "start" && n.r#type != "end")
        .collect();
    if ops.len() < 4 {
        return Vec::new();
    }

    let fingerprints: Vec<String> = ops.iter().map(|n| flowchart_node_fingerprint(n)).collect();
    let n = fingerprints.len();
    let mut proposals = Vec::new();

    // 尝试所有可能的子序列长度（从短到长，优先检测短循环）
    let max_len = (n / 2).min(8);
    for pat_len in 2..=max_len {
        let mut i = 0;
        while i + pat_len <= n {
            // 检查从 i 开始是否有重复的 pat_len 长度子序列
            let pattern = &fingerprints[i..i + pat_len];
            let mut repeat_count = 1;
            let mut j = i + pat_len;
            while j + pat_len <= n && &fingerprints[j..j + pat_len] == pattern {
                repeat_count += 1;
                j += pat_len;
            }
            if repeat_count >= 2 {
                // 检查这个提议不与已有提议重叠
                let end_idx = i + pat_len * repeat_count;
                let overlaps = proposals.iter().any(|p: &LoopMergeProposal| {
                    let p_start = p.node_indices.first().copied().unwrap_or(usize::MAX);
                    let p_end = p.node_indices.last().copied().unwrap_or(0);
                    // 重叠检查：新区间 [i, end_idx-1] 与旧区间 [p_start, p_end] 有交集
                    !(end_idx <= p_start || i > p_end)
                });
                if !overlaps {
                    let node_ids: Vec<String> = (i..end_idx).map(|idx| ops[idx].id.clone()).collect();
                    let node_indices: Vec<usize> = (i..end_idx).collect();
                    let loop_label = format!(
                        "循环 ×{} ({}步)",
                        repeat_count,
                        pat_len
                    );
                    proposals.push(LoopMergeProposal {
                        node_ids,
                        node_indices,
                        pattern_length: pat_len,
                        repeat_count,
                        loop_label,
                    });
                }
                // 跳过已检测的区域，避免子模式重复检测
                i = end_idx;
                continue;
            }
            i += 1;
        }
    }

    proposals
}

/// 小循环合并提议，前端据此弹窗让用户确认。
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LoopMergeProposal {
    /// 涉及的节点 ID 列表（按录制顺序）
    pub node_ids: Vec<String>,
    /// 在操作节点数组中的索引（调试用）
    #[serde(skip_serializing)]
    pub node_indices: Vec<usize>,
    /// 循环体长度（单次重复的步数）
    pub pattern_length: usize,
    /// 重复次数
    pub repeat_count: usize,
    /// 循环标签，如 "循环 ×2 (3步)"
    pub loop_label: String,
}

/// 节点指纹（与 store.rs 的 node_fingerprint 同算法）。
fn flowchart_node_fingerprint(n: &FlowchartNode) -> String {
    let meta = n
        .meta
        .as_ref()
        .map(|m| m.to_string())
        .unwrap_or_default();
    [n.r#type.as_str(), n.label.as_str(), n.action.as_deref().unwrap_or(""), &meta]
        .join("\u{1f}")
}

// ── helpers ───────────────────────────────────────────────────────────

/// 将累积的延时 (delayMs) 和鼠标轨迹 (mouseTrajectory) 写入节点 meta，然后重置。
///
/// 每个操作节点（click / hotkey / browser / type）创建时调用一次，
/// 确保 Delay 和 MouseMove 事件的数据不丢失，而是附加到紧随其后的操作节点上。
/// 回放引擎读取 meta.delayMs 在步骤前等待、读取 meta.mouseTrajectory
/// 在点击前模拟真实鼠标移动轨迹（含随机扰动）。
fn apply_pending_meta(
    meta: &mut serde_json::Map<String, serde_json::Value>,
    pending_delay_ms: &mut u64,
    pending_mouse_trajectory: &mut Vec<(i32, i32)>,
) {
    if *pending_delay_ms > 0 {
        meta.insert("delayMs".to_string(), serde_json::json!(*pending_delay_ms));
        *pending_delay_ms = 0;
    }
    if !pending_mouse_trajectory.is_empty() {
        // 序列化为 [[x1,y1],[x2,y2],...] 格式，前端和 engine.rs 都方便消费
        let trajectory: Vec<Vec<i32>> = pending_mouse_trajectory
            .iter()
            .map(|(x, y)| vec![*x, *y])
            .collect();
        meta.insert("mouseTrajectory".to_string(), serde_json::json!(trajectory));
        pending_mouse_trajectory.clear();
    }
}

/// 把挂起的文本 buffer 物化为一个 type 节点，并清空 buffer。
/// 在以下情况触发：
///   * 遇到非可打印按键
///   * 遇到 MouseClick
///   * 遇到 BrowserAction
///   * 全部 events 走完
fn flush_text(
    buf: &mut String,
    first_idx: &mut Option<u32>,
    nodes: &mut Vec<FlowchartNode>,
    connections: &mut Vec<FlowchartConnection>,
    prev_id: &mut Option<String>,
    counter: &mut u32,
    step_count: &mut u32,
    pending_delay_ms: &mut u64,
    pending_mouse_trajectory: &mut Vec<(i32, i32)>,
) {
    if buf.is_empty() {
        return;
    }
    let id = format!("n{}_{}", counter, make_short_id());
    *counter += 1;
    let label = format!("输入 \"{}\"", truncate_str(buf, 48));
    let mut meta = serde_json::Map::new();
    meta.insert("text".to_string(), serde_json::json!(buf.as_str()));
    // 写入累积的延时和鼠标轨迹
    apply_pending_meta(&mut meta, pending_delay_ms, pending_mouse_trajectory);
    if let Some(pid) = prev_id {
        connections.push(FlowchartConnection {
            from: pid.clone(),
            to: id.clone(),
            label: None,
        });
    }
    nodes.push(FlowchartNode {
        id,
        r#type: "io".to_string(),
        label,
        action: Some("type".to_string()),
        meta: Some(serde_json::Value::Object(meta)),
        source_event_idx: *first_idx,
        recognition: None,
    });
    *prev_id = nodes.last().map(|n| n.id.clone());
    *step_count += 1;
    buf.clear();
    *first_idx = None;
}

/// 与 recorder.rs 的 is_printable_key 完全对齐：单字符 (含 rdev 加的引号) → 可打印。
fn is_printable_key_inner(key: &str) -> bool {
    let stripped = key.trim_matches('"');
    stripped.chars().count() == 1
}

fn unescape_key_inner(key: &str) -> String {
    key.trim_matches('"').to_string()
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

// 类型链接检查：仅用于在编译期验证 rdev 依赖在 flowchart 模块可见，
// 实际运行不调用。button_name 现在已在 recorder.rs 公开，未来需要
// 重新做"Button 枚举 → 字符串"转换时可直接 `use crate::automation::recorder::button_name`。
#[allow(dead_code)]
fn _button_type_check(_b: rdev::Button) {}

#[cfg(test)]
mod tests {
    use super::*;

    fn click(x: i32, y: i32, btn: &str) -> RecordedEvent {
        RecordedEvent::MouseClick {
            x,
            y,
            button: btn.into(),
            element: None,
        }
    }
    fn click_with_element(x: i32, y: i32, btn: &str, el: ClickElementInfo) -> RecordedEvent {
        RecordedEvent::MouseClick {
            x,
            y,
            button: btn.into(),
            element: Some(el),
        }
    }
    fn element(name: &str, ctrl: &str, auto_id: &str, class: &str) -> ClickElementInfo {
        ClickElementInfo {
            name: name.into(),
            control_type: ctrl.into(),
            automation_id: auto_id.into(),
            class_name: class.into(),
        }
    }
    fn key(k: &str) -> RecordedEvent {
        RecordedEvent::KeyPress { key: k.into() }
    }
    fn movee(x: i32, y: i32) -> RecordedEvent {
        RecordedEvent::MouseMove { x, y }
    }
    fn screenshot() -> RecordedEvent {
        RecordedEvent::Screenshot { data: vec![0] }
    }
    fn state(name: &str) -> RecordedEvent {
        RecordedEvent::State {
            name: name.into(),
            payload: None,
        }
    }

    #[test]
    fn empty_events_produces_minimal_framework() {
        let fc = events_to_flowchart(&[]);
        assert_eq!(fc.source, "recorder");
        assert_eq!(fc.step_count, 0);
        // start + end 共 2 个节点
        assert_eq!(fc.nodes.len(), 2);
        assert_eq!(fc.nodes[0].r#type, "start");
        assert_eq!(fc.nodes[1].r#type, "end");
        assert_eq!(fc.connections.len(), 1);
        assert_eq!(fc.connections[0].from, fc.nodes[0].id);
        assert_eq!(fc.connections[0].to, fc.nodes[1].id);
    }

    #[test]
    fn only_mouse_move_screenshot_state_yields_minimal() {
        let events = vec![
            movee(10, 10),
            RecordedEvent::Screenshot { data: vec![0] },
            RecordedEvent::State {
                name: "x".into(),
                payload: None,
            },
        ];
        let fc = events_to_flowchart(&events);
        assert_eq!(fc.step_count, 0);
        assert_eq!(fc.nodes.len(), 2);
    }

    #[test]
    fn click_produces_process_node() {
        let fc = events_to_flowchart(&[click(100, 200, "left")]);
        assert_eq!(fc.step_count, 1);
        // start + click + end = 3
        assert_eq!(fc.nodes.len(), 3);
        let middle = &fc.nodes[1];
        assert_eq!(middle.r#type, "process");
        assert_eq!(middle.action.as_deref(), Some("click"));
        assert!(middle.label.contains("100"));
        assert!(middle.label.contains("200"));
        assert!(middle.label.contains("左键"));
    }

    #[test]
    fn printable_keys_collapse_into_one_type_node() {
        let fc = events_to_flowchart(&[key("\"h\""), key("\"i\""), key("\"!\"")]);
        assert_eq!(fc.step_count, 1);
        // start + type + end = 3
        assert_eq!(fc.nodes.len(), 3);
        let middle = &fc.nodes[1];
        assert_eq!(middle.r#type, "io");
        assert_eq!(middle.action.as_deref(), Some("type"));
        let text = middle.meta.as_ref().unwrap().get("text").unwrap().as_str().unwrap();
        assert_eq!(text, "hi!");
    }

    #[test]
    fn non_printable_key_becomes_hotkey_node() {
        let fc = events_to_flowchart(&[key("Enter"), key("Escape")]);
        assert_eq!(fc.step_count, 2);
        // start + hotkey1 + hotkey2 + end = 4
        assert_eq!(fc.nodes.len(), 4);
        assert_eq!(fc.nodes[1].action.as_deref(), Some("hotkey"));
        assert_eq!(fc.nodes[1].label, "按键 Enter");
        assert_eq!(fc.nodes[2].label, "按键 Escape");
    }

    #[test]
    fn mixed_sequence_links_in_order() {
        let events = vec![
            click(50, 50, "left"),
            key("\"a\""),
            key("\"b\""),
            key("Enter"),
            key("\"c\""),
        ];
        let fc = events_to_flowchart(&events);
        // 节点: start + click + type(ab) + hotkey(Enter) + type(c) + end = 6
        assert_eq!(fc.nodes.len(), 6);
        assert_eq!(fc.step_count, 4);
        // connections: start→click, click→type, type→hotkey, hotkey→type, type→end = 5
        assert_eq!(fc.connections.len(), 5);
        // 第一条: start → click
        assert_eq!(fc.connections[0].from, fc.nodes[0].id);
        assert_eq!(fc.connections[0].to, fc.nodes[1].id);
        // 最后一条: type(c) → end
        let last = fc.connections.last().unwrap();
        assert_eq!(last.to, fc.nodes.last().unwrap().id);
    }

    #[test]
    fn connections_have_no_labels_by_default() {
        let fc = events_to_flowchart(&[click(10, 10, "left")]);
        for c in &fc.connections {
            assert!(c.label.is_none(), "linear recorder flow has no branch labels");
        }
    }

    #[test]
    fn source_event_idx_traces_back_to_input() {
        // 故意插入 movee 让"原始索引"不等于"节点索引"，确保 source_event_idx 真指向源数组
        let events = vec![
            movee(1, 1),
            click(20, 30, "left"),
            RecordedEvent::Screenshot { data: vec![0] },
            key("\"x\""),
        ];
        let fc = events_to_flowchart(&events);
        // click 节点
        let click_node = fc.nodes.iter().find(|n| n.action.as_deref() == Some("click")).unwrap();
        assert_eq!(click_node.source_event_idx, Some(1));
        // type 节点
        let type_node = fc.nodes.iter().find(|n| n.action.as_deref() == Some("type")).unwrap();
        assert_eq!(type_node.source_event_idx, Some(3));
    }

    #[test]
    fn browser_action_node_shape() {
        let events = vec![RecordedEvent::BrowserAction {
            url: "https://example.com/path".into(),
            selector: ".submit".into(),
        }];
        let fc = events_to_flowchart(&events);
        assert_eq!(fc.nodes.len(), 3);
        let middle = &fc.nodes[1];
        assert_eq!(middle.action.as_deref(), Some("browser"));
        assert!(middle.label.contains("https://example.com"));
        assert!(middle.label.contains(".submit"));
    }

    #[test]
    fn node_ids_are_unique() {
        let events = vec![
            click(1, 1, "left"),
            click(2, 2, "left"),
            click(3, 3, "right"),
            key("\"a\""),
            key("\"b\""),
            key("Enter"),
        ];
        let fc = events_to_flowchart(&events);
        let mut seen = std::collections::HashSet::new();
        for n in &fc.nodes {
            assert!(seen.insert(n.id.clone()), "duplicate node id: {}", n.id);
        }
    }

    // ── dedup_clicks_by_element 边界测试 ──────────────────────────────

    #[test]
    fn dedup_same_element_consecutive_clicks_collapsed() {
        // 同一按钮（automation_id 一致）连续点击 → 合并为 1 个
        let el = element("确定", "Button", "btnOk", "Button");
        let events = vec![
            click_with_element(100, 100, "left", el.clone()),
            click_with_element(105, 108, "left", el.clone()),
            click_with_element(110, 115, "left", el),
        ];
        let deduped = dedup_clicks_by_element(&events);
        assert_eq!(deduped.len(), 1, "3 same-element clicks should collapse to 1");
    }

    #[test]
    fn dedup_different_elements_kept() {
        // 不同 automation_id → 都保留
        let el_a = element("确定", "Button", "btnOk", "Button");
        let el_b = element("取消", "Button", "btnCancel", "Button");
        let events = vec![
            click_with_element(100, 100, "left", el_a),
            click_with_element(200, 200, "left", el_b),
        ];
        let deduped = dedup_clicks_by_element(&events);
        assert_eq!(deduped.len(), 2, "different elements should not dedup");
    }

    #[test]
    fn dedup_keypress_between_clicks_prevents_dedup() {
        // 中间有 KeyPress → 不算重复（用户做了其他操作）
        let el = element("确定", "Button", "btnOk", "Button");
        let events = vec![
            click_with_element(100, 100, "left", el.clone()),
            key("Enter"),
            click_with_element(105, 108, "left", el),
        ];
        let deduped = dedup_clicks_by_element(&events);
        assert_eq!(deduped.len(), 3, "keypress between clicks prevents dedup");
    }

    #[test]
    fn dedup_too_many_non_action_events_prevents_dedup() {
        // 中间穿插 > MAX_SKIPPED_NON_ACTION_EVENTS (5) 个 MouseMove/Screenshot/State
        // → 视为用户有意再次点击，不算重复
        let el = element("确定", "Button", "btnOk", "Button");
        let mut events = vec![click_with_element(100, 100, "left", el.clone())];
        // 插入 6 个非操作事件（超过阈值）
        for i in 0..6 {
            events.push(movee(100 + i, 100 + i));
        }
        events.push(click_with_element(105, 108, "left", el));
        let deduped = dedup_clicks_by_element(&events);
        // 第二个 click 不应被去重
        let click_count = deduped
            .iter()
            .filter(|e| matches!(e, RecordedEvent::MouseClick { .. }))
            .count();
        assert_eq!(click_count, 2, "too many non-action events between clicks → keep both");
    }

    #[test]
    fn dedup_few_non_action_events_still_dedup() {
        // 中间穿插 ≤ MAX_SKIPPED_NON_ACTION_EVENTS (5) 个非操作事件 → 仍去重
        let el = element("确定", "Button", "btnOk", "Button");
        let mut events = vec![click_with_element(100, 100, "left", el.clone())];
        for i in 0..5 {
            events.push(movee(100 + i, 100 + i));
        }
        events.push(click_with_element(105, 108, "left", el));
        let deduped = dedup_clicks_by_element(&events);
        let click_count = deduped
            .iter()
            .filter(|e| matches!(e, RecordedEvent::MouseClick { .. }))
            .count();
        assert_eq!(click_count, 1, "≤5 non-action events between clicks → dedup");
    }

    #[test]
    fn dedup_coord_fallback_when_both_element_none() {
        // 两个 click 都没有 element，坐标距离 ≤ 20 → 视为重复
        let events = vec![
            click(100, 100, "left"),
            click(110, 105, "left"), // 曼哈顿距离 15 ≤ 20
        ];
        let deduped = dedup_clicks_by_element(&events);
        assert_eq!(deduped.len(), 1, "coord fallback should dedup nearby clicks");
    }

    #[test]
    fn dedup_coord_fallback_far_apart_kept() {
        // 两个 click 都没有 element，但坐标距离 > 20 → 保留
        let events = vec![
            click(100, 100, "left"),
            click(200, 200, "left"), // 曼哈顿距离 200 > 20
        ];
        let deduped = dedup_clicks_by_element(&events);
        assert_eq!(deduped.len(), 2, "far-apart clicks should not dedup");
    }

    #[test]
    fn dedup_mixed_element_none_and_some_kept() {
        // 一个有 element，一个没有 → 保守保留（无法判断）
        let el = element("确定", "Button", "btnOk", "Button");
        let events = vec![
            click_with_element(100, 100, "left", el),
            click(105, 108, "left"), // element=None
        ];
        let deduped = dedup_clicks_by_element(&events);
        assert_eq!(deduped.len(), 2, "mixed element Some/None should not dedup");
    }

    #[test]
    fn dedup_same_name_different_control_type_kept() {
        // name 相同但 control_type 不同 → 不算同一元素
        // 场景：同名"确定"但一个是 Button，一个是 MenuItem
        let el_a = element("确定", "Button", "", "Button");
        let el_b = element("确定", "MenuItem", "", "MenuItem");
        let events = vec![
            click_with_element(100, 100, "left", el_a),
            click_with_element(200, 200, "left", el_b),
        ];
        let deduped = dedup_clicks_by_element(&events);
        assert_eq!(deduped.len(), 2, "same name diff control_type should not dedup");
    }

    #[test]
    fn dedup_across_screenshot_state_events_dedup() {
        // 中间穿插 Screenshot / State（≤5个）→ 仍去重
        let el = element("确定", "Button", "btnOk", "Button");
        let events = vec![
            click_with_element(100, 100, "left", el.clone()),
            screenshot(),
            state("marker"),
            click_with_element(105, 108, "left", el),
        ];
        let deduped = dedup_clicks_by_element(&events);
        let click_count = deduped
            .iter()
            .filter(|e| matches!(e, RecordedEvent::MouseClick { .. }))
            .count();
        assert_eq!(click_count, 1, "screenshot/state between clicks → still dedup");
    }

    #[test]
    fn dedup_empty_events_returns_empty() {
        let deduped = dedup_clicks_by_element(&[]);
        assert!(deduped.is_empty());
    }

    #[test]
    fn dedup_only_non_click_events_unchanged() {
        let events = vec![
            movee(1, 1),
            key("Enter"),
            screenshot(),
            state("x"),
        ];
        let deduped = dedup_clicks_by_element(&events);
        assert_eq!(deduped.len(), events.len(), "non-click events should pass through");
    }

    #[test]
    fn dedup_three_different_then_same_as_first_kept() {
        // 第三个 click 与第一个同元素，但中间有第二个不同元素 click
        // → 中间遇到 MouseClick（不同元素）就 return false，第三个保留
        let el_a = element("确定", "Button", "btnOk", "Button");
        let el_b = element("取消", "Button", "btnCancel", "Button");
        let events = vec![
            click_with_element(100, 100, "left", el_a.clone()),
            click_with_element(200, 200, "left", el_b),
            click_with_element(105, 108, "left", el_a), // 与第一个同元素
        ];
        let deduped = dedup_clicks_by_element(&events);
        assert_eq!(deduped.len(), 3, "different click between same-element clicks → keep all");
    }

    // ── 小循环检测测试 ──────────────────────────────────────────

    fn make_node(id: &str, ty: &str, label: &str, action: Option<&str>) -> FlowchartNode {
        FlowchartNode {
            id: id.to_string(),
            r#type: ty.to_string(),
            label: label.to_string(),
            action: action.map(|s| s.to_string()),
            meta: None,
            source_event_idx: None,
            recognition: None,
        }
    }

    #[test]
    fn detect_loop_simple_repeat() {
        // A→B→A→B → 长度2重复2次
        let nodes = vec![
            make_node("n1", "process", "点击A", Some("click")),
            make_node("n2", "process", "点击B", Some("click")),
            make_node("n3", "process", "点击A", Some("click")),
            make_node("n4", "process", "点击B", Some("click")),
        ];
        let proposals = detect_small_loops(&nodes);
        assert_eq!(proposals.len(), 1);
        assert_eq!(proposals[0].pattern_length, 2);
        assert_eq!(proposals[0].repeat_count, 2);
        assert_eq!(proposals[0].node_ids.len(), 4);
    }

    #[test]
    fn detect_loop_triple_repeat() {
        // A→B→C→A→B→C → 长度3重复2次
        let nodes = vec![
            make_node("n1", "process", "A", Some("click")),
            make_node("n2", "process", "B", Some("click")),
            make_node("n3", "process", "C", Some("click")),
            make_node("n4", "process", "A", Some("click")),
            make_node("n5", "process", "B", Some("click")),
            make_node("n6", "process", "C", Some("click")),
        ];
        let proposals = detect_small_loops(&nodes);
        assert_eq!(proposals.len(), 1);
        assert_eq!(proposals[0].pattern_length, 3);
        assert_eq!(proposals[0].repeat_count, 2);
    }

    #[test]
    fn detect_loop_ignores_short_sequences() {
        // 只有3个操作节点 → 太短，不检测
        let nodes = vec![
            make_node("n1", "process", "A", Some("click")),
            make_node("n2", "process", "B", Some("click")),
            make_node("n3", "process", "A", Some("click")),
        ];
        let proposals = detect_small_loops(&nodes);
        assert!(proposals.is_empty());
    }

    #[test]
    fn detect_loop_no_repeat_returns_empty() {
        // 无重复 → 空列表
        let nodes = vec![
            make_node("n1", "process", "A", Some("click")),
            make_node("n2", "process", "B", Some("click")),
            make_node("n3", "process", "C", Some("click")),
            make_node("n4", "process", "D", Some("click")),
        ];
        let proposals = detect_small_loops(&nodes);
        assert!(proposals.is_empty());
    }

    #[test]
    fn detect_loop_ignores_start_end_nodes() {
        // start/end 不参与循环检测
        let nodes = vec![
            make_node("n0", "start", "开始", None),
            make_node("n1", "process", "A", Some("click")),
            make_node("n2", "process", "B", Some("click")),
            make_node("n3", "process", "A", Some("click")),
            make_node("n4", "process", "B", Some("click")),
            make_node("n5", "end", "结束", None),
        ];
        let proposals = detect_small_loops(&nodes);
        assert_eq!(proposals.len(), 1);
        assert_eq!(proposals[0].node_ids.len(), 4);
    }

    #[test]
    fn detect_loop_three_repeats() {
        // A→B→A→B→A→B → 长度2重复3次
        let nodes = vec![
            make_node("n1", "process", "A", Some("click")),
            make_node("n2", "process", "B", Some("click")),
            make_node("n3", "process", "A", Some("click")),
            make_node("n4", "process", "B", Some("click")),
            make_node("n5", "process", "A", Some("click")),
            make_node("n6", "process", "B", Some("click")),
        ];
        let proposals = detect_small_loops(&nodes);
        assert_eq!(proposals.len(), 1);
        assert_eq!(proposals[0].repeat_count, 3);
    }
}
