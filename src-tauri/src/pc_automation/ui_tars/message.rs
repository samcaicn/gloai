// Copyright (c) 2026 tupAI
//
// tupAI v5 — UI-TARS 训练数据格式的"一行"消息(详见
// deepwiki.com/bytedance/UI-TARS/7-training-data-format)。
//
// 设计决策(doc comment):
//   * `UiTarsMessage` 是一行训练样本:`{role, content, loss_mask}`。
//     `loss_mask = 1` 标记这是模型需要预测的 assistant token,
//     `loss_mask = 0` 表示上下文(prompt / system / 反馈)。
//   * `loss_mask` 用 `i32` 而非 `u8` / `bool`:
//
//     deepwiki 协议里 `loss_mask` 是整数字段;i32 让前向兼容
//     "局部 loss" / "权重 loss" 之类的扩展(例如 UI-TARS v2 引入了
//     "thought token" 的部分 loss)。
//
// 抽出本文件的理由(uirap v2 合并精简):
//   原本定义在 `trajectory::message`,但本类型是 UI-TARS
//   协议本身的数据模型,与 trajectory 模块"把事件转成消息"
//   的转换逻辑无依赖关系;独立后 vlm_rescue / 未来 SFT 工具链
//   都能直接 `use crate::pc_automation::ui_tars::UiTarsMessage`。
//
// 命名约定: 与 `pc_automation::executor` 的 camelCase 一致。

use serde::{Deserialize, Serialize};

/// UI-TARS 训练样本的一行。`Serialize/Deserialize` 直接走 camelCase。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct UiTarsMessage {
    /// `"system" | "user" | "assistant"`。
    pub role: String,
    /// 文本内容。`content` 是协议里"字符串字段"的名字,所以保持原样。
    pub content: String,
    /// 训练时的 loss mask。0 = 上下文,1 = 需要学习的目标。
    pub loss_mask: i32,
}

impl UiTarsMessage {
    pub const ROLE_SYSTEM: &'static str = "system";
    pub const ROLE_USER: &'static str = "user";
    pub const ROLE_ASSISTANT: &'static str = "assistant";

    pub const LOSS_MASK_CONTEXT: i32 = 0;
    pub const LOSS_MASK_LEARN: i32 = 1;

    /// 构造一个上下文消息(`loss_mask = 0`)。
    pub fn context(role: impl Into<String>, content: impl Into<String>) -> Self {
        Self {
            role: role.into(),
            content: content.into(),
            loss_mask: Self::LOSS_MASK_CONTEXT,
        }
    }

    /// 构造一个 assistant 学习目标(`loss_mask = 1`)。
    pub fn learn(content: impl Into<String>) -> Self {
        Self {
            role: Self::ROLE_ASSISTANT.to_string(),
            content: content.into(),
            loss_mask: Self::LOSS_MASK_LEARN,
        }
    }
}

// ============================================================================
// Unit tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    /// `loss_mask` 与 `role` 的常量化约束不能漂移。
    #[test]
    fn ui_tars_message_helpers_set_loss_mask_correctly() {
        let ctx = UiTarsMessage::context("user", "hello");
        assert_eq!(ctx.loss_mask, 0);
        assert_eq!(ctx.role, "user");

        let learn = UiTarsMessage::learn("Action: click(...)");
        assert_eq!(learn.loss_mask, 1);
        assert_eq!(learn.role, "assistant");
    }

    /// camelCase wire 形状:camelCase 字段名 + role 常量稳定。
    #[test]
    fn ui_tars_message_serializes_with_camel_case_fields() {
        let m = UiTarsMessage {
            role: UiTarsMessage::ROLE_ASSISTANT.to_string(),
            content: "Action: click(...)".to_string(),
            loss_mask: 1,
        };
        let json = serde_json::to_string(&m).unwrap();
        // camelCase: lossMask 不是 loss_mask
        assert!(json.contains("\"lossMask\":1"), "got: {}", json);
        assert!(json.contains("\"role\":\"assistant\""));
        assert!(json.contains("\"content\":\"Action: click(...)\""));
    }
}
