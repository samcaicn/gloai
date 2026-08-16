// Copyright (c) 2026 tupAI
//
// 录制动作数据结构
//
// 每个 RecordedAction 代表用户的一个操作动作，
// 包含动作类型、目标元素选择器、时间戳等信息。

use serde::{Deserialize, Serialize};
use std::hash::{Hash, Hasher};
use std::collections::hash_map::DefaultHasher;
use std::sync::atomic::{AtomicU64, Ordering};

/// 单调递增计数器，替代 UUID v4（避免每次创建都调用 crypto RNG）
static ACTION_COUNTER: AtomicU64 = AtomicU64::new(1);
static BATCH_COUNTER: AtomicU64 = AtomicU64::new(1);

/// 生成唯一动作 ID（~10ns vs UUID v4 的 ~1-10us）
/// 格式: "a-{timestamp_hex}-{counter_hex}"，进程内唯一 + 时间可排序
fn next_action_id() -> String {
    let ts = chrono::Local::now().timestamp_millis() as u64;
    let seq = ACTION_COUNTER.fetch_add(1, Ordering::Relaxed);
    format!("a-{:013x}-{:08x}", ts, seq)
}

/// 用户操作动作类型
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "camelCase")]
pub enum ActionType {
    /// 鼠标点击
    Click,
    /// 鼠标双击
    DoubleClick,
    /// 鼠标右键点击
    RightClick,
    /// 键盘输入文本
    Type,
    /// 按下单个按键(如Enter/Tab/Esc)
    KeyDown,
    /// 滚动
    Scroll,
    /// 鼠标移动(用于hover等)
    MouseMove,
    /// 焦点切换
    Focus,
    /// 选择变化(下拉框/checkbox等)
    Select,
}

/// 目标元素选择器
/// CDP: css selector / xpath
/// UIA: AutomationId / Name / ClassName
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ElementSelector {
    /// 选择器类型: css / xpath / uia_id / uia_name / uia_class / ocr_text
    pub selector_type: String,
    /// 选择器值
    pub value: String,
    /// 元素文本内容(可选，用于辅助定位)
    pub text_content: Option<String>,
    /// 元素位置(可选，用于fallback)
    pub bounds: Option<ElementBounds>,
    /// 备选选择器列表（回放时主选择器失效可逐条尝试）
    /// 优先级从高到低排列，如 [css#id, xpath, text, bounds]
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub fallback_selectors: Vec<FallbackSelector>,
}

/// 备选选择器：type + value，独立于主选择器
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FallbackSelector {
    pub selector_type: String,
    pub value: String,
}

/// 元素边界位置
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ElementBounds {
    pub x: i32,
    pub y: i32,
    pub width: i32,
    pub height: i32,
}

/// 录制的动作
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RecordedAction {
    /// 全局唯一ID
    pub id: String,
    /// 录制时间戳(unix毫秒)
    pub timestamp: i64,
    /// 来源软件名(从CDP target title或UIA window title提取)
    pub app_name: String,
    /// 来源页面URL或窗口标题
    pub context: String,
    /// 动作类型
    pub action_type: ActionType,
    /// 目标元素选择器
    pub target: Option<ElementSelector>,
    /// 动作参数(如Type的文本内容、Scroll的偏移量)
    pub action_data: Option<String>,
    /// 来源协议: cdp / uia
    pub protocol: String,
}

impl RecordedAction {
    /// 创建新动作，自动生成ID和时间戳
    pub fn new(
        app_name: impl Into<String>,
        context: impl Into<String>,
        action_type: ActionType,
        protocol: impl Into<String>,
    ) -> Self {
        Self {
            id: next_action_id(),
            // 使用 Local 与 store.rs 的文件名日期保持一致，避免 UTC/Local 时间不一致
            timestamp: chrono::Local::now().timestamp_millis(),
            app_name: app_name.into(),
            context: context.into(),
            action_type,
            target: None,
            action_data: None,
            protocol: protocol.into(),
        }
    }

    /// 设置目标元素
    pub fn with_target(mut self, target: ElementSelector) -> Self {
        self.target = Some(target);
        self
    }

    /// 设置动作数据
    pub fn with_data(mut self, data: impl Into<String>) -> Self {
        self.action_data = Some(data.into());
        self
    }

    /// 计算用于去重的hash
    /// hash = selector_type + value + action_type + action_data
    pub fn dedup_hash(&self) -> u64 {
        let mut hasher = DefaultHasher::new();

        // 选择器参与hash
        if let Some(t) = &self.target {
            t.selector_type.hash(&mut hasher);
            t.value.hash(&mut hasher);
        }

        // 动作类型和数据参与hash
        self.action_type.hash(&mut hasher);
        self.action_data.hash(&mut hasher);

        // context参与hash(同一页面内的动作才算重复)
        self.context.hash(&mut hasher);

        hasher.finish()
    }

    /// 判断两个动作是否可去重
    /// 规则: 同selector + 同action_type + 同action_data + 同context
    pub fn is_duplicate_of(&self, other: &RecordedAction) -> bool {
        self.dedup_hash() == other.dedup_hash()
    }
}

/// 录制批次
/// 每5秒积累的动作集合，去重后存储
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RecordingBatch {
    /// 批次ID
    pub id: String,
    /// 批次开始时间
    pub start_time: i64,
    /// 批次结束时间
    pub end_time: i64,
    /// 来源软件名
    pub app_name: String,
    /// 去重后的动作列表
    pub actions: Vec<RecordedAction>,
    /// 去重前的动作数量
    pub raw_count: usize,
    /// 去重后的动作数量
    pub dedup_count: usize,
}

impl RecordingBatch {
    /// 创建新批次
    pub fn new(app_name: impl Into<String>, actions: Vec<RecordedAction>) -> Self {
        // 使用 Local 与 RecordedAction::new 及 store.rs 的文件名日期保持一致，
        // 避免 UTC/Local 时间不一致导致批次时间戳与文件名日期错位 8 小时。
        let now = chrono::Local::now().timestamp_millis();
        let raw_count = actions.len();

        // 去重: 保留最新动作，丢弃重复
        let mut seen_hashes: std::collections::HashSet<u64> = std::collections::HashSet::new();
        let mut deduped: Vec<RecordedAction> = Vec::new();

        // 从最新到最老遍历，保留最新的
        for action in actions.into_iter().rev() {
            let hash = action.dedup_hash();
            if !seen_hashes.contains(&hash) {
                seen_hashes.insert(hash);
                deduped.push(action);
            }
        }

        // 恢复时间顺序
        deduped.reverse();
        let dedup_count = deduped.len();

        Self {
            id: {
                let ts = chrono::Local::now().timestamp_millis() as u64;
                let seq = BATCH_COUNTER.fetch_add(1, Ordering::Relaxed);
                format!("b-{:013x}-{:08x}", ts, seq)
            },
            start_time: now - 5000, // 假设批次周期5秒
            end_time: now,
            app_name: app_name.into(),
            actions: deduped,
            raw_count,
            dedup_count,
        }
    }
}