// Copyright (c) 2026 AIMarketing
//
// AIMarketing v5 — `trajectory::message` 现在的内容是 UI-TARS 协议的
// "半结构化事件"层(`UiTarsMessage` 已被下沉到
// `pc_automation::ui_tars::message`)。
//
// 设计决策(doc comment):
//   * `TrajectoryEvent` 是一个"半结构化"的事件枚举:调用方用
//     `build_trajectory` 把一串事件翻成 UI-TARS 训练样本。理由是
//     直接构造 `UiTarsMessage` 会强迫调用方知道 system prompt 模板
//     和 user message 模板(都从 deepwiki 抓的),而
//     `TrajectoryEvent → UiTarsMessage` 转换是稳定不变的。
//   * 模板与 deepwiki 完全对齐:
//       * system:    "You are a helpful assistant."
//       * user #1:   "You are a GUI agent... ## User Instruction {instruction}"
//       * user #2:   "<|vision_start|><|image_pad|>...<|vision_end|>"
//       * assistant: "Action: click(start_box='<|box_start|>(x,y)<|box_end|>')"
//   * uirap v2: 抽出 `UiTarsMessage` 到 `pc_automation::ui_tars::message`,
//     此处 re-export 保持 `pc_automation::trajectory::UiTarsMessage`
//     老路径继续可用;`TrajectoryEvent` 是 trajectory 模块的"业务
//     抽象",不依赖 ui_tars 模块的实现细节,所以保留在这里。
//
// serde 标注:
//   * `tag = "kind"` — 用 `kind` 字段做变体 tag(便于 TypeScript 判别联合)
//   * `rename_all = "camelCase"` — variant 名转 camelCase(`SystemInit` → `systemInit`)
//   * `rename_all_fields = "camelCase"` — inline 字段名也转 camelCase
//     (`action_text` → `actionText` / `is_success` → `isSuccess`)。
//     这是 serde 1.0.166+ 的特性,本 crate 使用的 `serde = "1.0"` 拉
//     最新 1.x minor,天然支持。

use serde::{Deserialize, Serialize};

// ============================================================================
// 半结构化事件
// ============================================================================

/// 一次 skill 执行过程中可以记录的事件。本期支持 5 种,
/// 都是"用户/系统/prompt/反馈"四元组的最小切分。
///
/// 设计意图:executor 在 `EVENT_STEP_STARTED` / `EVENT_STEP_SUCCEEDED`
/// 等 hook 处直接 `TrajectoryEvent::VisionFrame(...)` 之类,
/// 不需要关心 UI-TARS 模板长什么样。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(
    tag = "kind",
    rename_all = "camelCase",
    rename_all_fields = "camelCase"
)]
pub enum TrajectoryEvent {
    /// 整段对话的 system 消息。本期固定为"You are a helpful assistant.",
    /// 但保留 String 字段供后续 per-skill system prompt 扩展。
    SystemInit { content: String },
    /// 用户的本轮指令(对应 `## User Instruction {instruction}`)。
    UserInstruction { instruction: String },
    /// 视觉帧 — 通常是截图 base64 / 文件路径 / URL。
    /// `image_ref` 序列化为 `<|vision_start|><|image_pad|>{image_ref}<|vision_end|>`
    /// 注入到 user message 的 content 字段。
    VisionFrame { image_ref: String },
    /// assistant 的动作 — 已经是 UI-TARS 协议字符串
    /// (例如 `"Action: click(start_box='<|box_start|>(495,30)<|box_end|>')"`)。
    /// 转换时设 `loss_mask = 1`。
    AssistantAction { action_text: String },
    /// 执行结果反馈(成功 / 失败 / VLM 救回来)。
    /// 转换时作为 user 消息发出,`loss_mask = 0`。
    ResultFeedback { message: String, is_success: bool },
}

impl TrajectoryEvent {
    /// 便利构造器:成功反馈。
    pub fn feedback_success(message: impl Into<String>) -> Self {
        TrajectoryEvent::ResultFeedback {
            message: message.into(),
            is_success: true,
        }
    }

    /// 便利构造器:失败反馈。
    pub fn feedback_failure(message: impl Into<String>) -> Self {
        TrajectoryEvent::ResultFeedback {
            message: message.into(),
            is_success: false,
        }
    }
}

// ============================================================================
// Re-export
// ============================================================================
//
// `UiTarsMessage` 的权威位置是 `pc_automation::ui_tars::message`,
// 但 `pc_automation::trajectory::UiTarsMessage` 老路径继续可用。
pub use crate::pc_automation::ui_tars::UiTarsMessage;

// ============================================================================
// Unit tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    /// TrajectoryEvent 的 enum tag 必须是 camelCase 的 `kind`,
    /// 这样 front-end 的 TypeScript `kind === "systemInit" | ...` 判别能 work。
    #[test]
    fn trajectory_event_serializes_with_camel_case_kind_tag() {
        let v = TrajectoryEvent::SystemInit {
            content: "You are a helpful assistant.".to_string(),
        };
        let json = serde_json::to_string(&v).unwrap();
        // `tag = "kind"` → JSON 形如 `{"kind":"systemInit","content":"..."}`
        assert!(
            json.contains("\"kind\":\"systemInit\""),
            "must use kind=systemInit tag, got: {}",
            json
        );

        let v2 = TrajectoryEvent::AssistantAction {
            action_text: "Action: click(...)".to_string(),
        };
        let json2 = serde_json::to_string(&v2).unwrap();
        assert!(json2.contains("\"kind\":\"assistantAction\""));
        assert!(json2.contains("\"actionText\""));
    }
}
