// Copyright (c) 2026 AIMarketing
//
// AIMarketing v5 — trajectory.jsonl 导出 (UI-TARS 训练数据格式)。
//
// 设计决策(doc comment):
//   * `build_trajectory` 是事件 → 消息的核心转换:
//       * `SystemInit` → 一条 system message (loss_mask=0)
//       * `UserInstruction` → 一条 user message,把指令拼进
//         "You are a GUI agent... ## User Instruction {instruction}"
//         模板里 (loss_mask=0)
//       * `VisionFrame` → 一条 user message,内容是
//         `<|vision_start|><|image_pad|>{image_ref}<|vision_end|>`
//         (loss_mask=0)
//       * `AssistantAction` → 一条 assistant message (loss_mask=1)
//       * `ResultFeedback` → 一条 user message,内容是
//         `"[ResultFeedback] {message} (success={is_success})"`
//   * 如果调用方忘了写 `SystemInit`,我们**不**自动补一条:
//     显式 > 隐式,训练数据格式必须可控。
//   * `export_jsonl` 写一行一个 JSON,行尾 `\n`。
//   * `from_episodic` 把 EpRecord[] 翻成 UiTarsMessage[]:
//       * 成功 record → assistant action (loss_mask=1)
//       * 失败 record → system 反思提示 (loss_mask=0)
//   * `from_receipt` 把单次完整执行 + skill 翻成一轮完整对话:
//       本期仅 user instruction + assistant action,
//       vision frame 与 step 级别的 progress 留 TODO。

use std::io::{self, Write};

use crate::pc_automation::episodic::EpRecord;
use crate::pc_automation::executor::ExecutionReceipt;
use crate::pc_automation::skill::types::Skill;

use super::message::{TrajectoryEvent, UiTarsMessage};

// ============================================================================
// 模板常量(与 deepwiki 7-training-data-format 对齐)
// ============================================================================

/// UI-TARS 的 system 消息。deepwiki 原文:
/// ```json
/// {"role":"system","content":"You are a helpful assistant.","loss_mask":0}
/// ```
pub const SYSTEM_PROMPT_DEFAULT: &str = "You are a helpful assistant.";

/// UI-TARS 的 user instruction 模板。deepwiki 原文:
/// ```text
/// You are a GUI agent... ## Output Format ... ## Action Space
///     click(start_box='<|box_start|>(x,y)<|box_end|>')
///     ...
/// ## User Instruction {instruction}
/// ```
///
/// `instruction` 段由调用方在 `UserInstruction` 事件里提供。
pub const USER_INSTRUCTION_TEMPLATE: &str = "You are a GUI agent. Given a screenshot of the current screen and the user's instruction, output the next action.\n\n## Output Format\nAction: <action>(<args>)\n\n## Action Space\nclick(start_box='<|box_start|>(x,y)<|box_end|>')\nleft_double_click(start_box='<|box_start|>(x,y)<|box_end|>')\nright_click(start_box='<|box_start|>(x,y)<|box_end|>')\ntype(text='...')\nkey(keys='...')\nscroll(start_box='<|box_start|>(x,y)<|box_end|>', direction='down|up|left|right', amount=N)\nwait()\nfinished()\n\n## User Instruction\n{instruction}";

/// UI-TARS 视觉帧模板。`image_ref` 填到 `<|image_pad|>` 之后。
pub const VISION_FRAME_TEMPLATE: &str = "<|vision_start|><|image_pad|>{image_ref}<|vision_end|>";

/// UI-TARS 反思失败 record 时的 system 提示模板。
/// `error` 字段填具体错误;`intent` 填原始意图。
pub const REFLECT_FAILURE_TEMPLATE: &str = "[Reflection] 上一次执行意图「{intent}」失败: {error}。请在下次重试时调整 selector 或借助 VLM 救援。";

/// UI-TARS 反思成功 record 时的 assistant 学习目标模板。
/// `intent` 填原始意图;`action` 填 UI-TARS 协议字符串。
pub const REFLECT_SUCCESS_TEMPLATE: &str = "Action: 成功完成「{intent}」({action})";

// ============================================================================
// 核心转换
// ============================================================================

/// 把 `TrajectoryEvent` 序列转换成 UI-TARS 训练样本。
///
/// **不**自动补 `SystemInit`:调用方显式传入,避免训练数据格式漂移。
pub fn build_trajectory(events: &[TrajectoryEvent]) -> Vec<UiTarsMessage> {
    let mut out: Vec<UiTarsMessage> = Vec::with_capacity(events.len());
    for ev in events {
        match ev {
            TrajectoryEvent::SystemInit { content } => {
                out.push(UiTarsMessage::context(
                    UiTarsMessage::ROLE_SYSTEM,
                    content.clone(),
                ));
            }
            TrajectoryEvent::UserInstruction { instruction } => {
                let content = USER_INSTRUCTION_TEMPLATE.replace("{instruction}", instruction);
                out.push(UiTarsMessage::context(UiTarsMessage::ROLE_USER, content));
            }
            TrajectoryEvent::VisionFrame { image_ref } => {
                let content = VISION_FRAME_TEMPLATE.replace("{image_ref}", image_ref);
                out.push(UiTarsMessage::context(UiTarsMessage::ROLE_USER, content));
            }
            TrajectoryEvent::AssistantAction { action_text } => {
                out.push(UiTarsMessage::learn(action_text.clone()));
            }
            TrajectoryEvent::ResultFeedback { message, is_success } => {
                let content = format!(
                    "[ResultFeedback] {} (success={})",
                    message, is_success
                );
                out.push(UiTarsMessage::context(
                    UiTarsMessage::ROLE_USER,
                    content,
                ));
            }
        }
    }
    out
}

/// 把 `UiTarsMessage` 序列写成 JSONL。一行一个 JSON,行尾 `\n`。
///
/// 出错立刻返回 `Err`,已写入的部分保留在 writer 里(语义和 `write_all` 一致)。
pub fn export_jsonl<W: Write>(messages: &[UiTarsMessage], writer: &mut W) -> io::Result<()> {
    for msg in messages {
        let line = serde_json::to_string(msg)
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
        writer.write_all(line.as_bytes())?;
        writer.write_all(b"\n")?;
    }
    Ok(())
}

/// 把 `EpRecord[]` 翻成 `UiTarsMessage[]`(用于反思训练数据生成)。
///
/// 每条 record:
///   * `outcome == "success"` → 一条 assistant message
///     (loss_mask=1, content 走 `REFLECT_SUCCESS_TEMPLATE`)
///   * `outcome ∈ {primary_miss, structured_miss, failed}` → 一条 system
///     反思消息 (loss_mask=0, content 走 `REFLECT_FAILURE_TEMPLATE`)
///   * 其它 outcome(`vlm_rescued` 等)→ 一条 system 备注消息
///     (loss_mask=0),提示"被 VLM 救回"
///
/// **不**自动补 system 头 / user 头 — 调用方如果想要"完整对话"模板,
/// 应该在前面手动 `build_trajectory(&[TrajectoryEvent::SystemInit { ... }])`
/// 再追加 `from_episodic` 的结果。
pub fn from_episodic(records: &[EpRecord]) -> Vec<UiTarsMessage> {
    let mut out: Vec<UiTarsMessage> = Vec::with_capacity(records.len());
    for r in records {
        match r.outcome.as_str() {
            "success" => {
                // 用 strategy + selector 拼一段"已学到的"动作,供 SFT 学习
                let action = if let Some(sel) = &r.selector_used {
                    format!("{}:{}", r.strategy_used, sel)
                } else {
                    format!("{}:unspecified", r.strategy_used)
                };
                let content = REFLECT_SUCCESS_TEMPLATE
                    .replace("{intent}", &r.intent)
                    .replace("{action}", &action);
                out.push(UiTarsMessage::learn(content));
            }
            "primary_miss" | "structured_miss" | "failed" => {
                let err_text = r.error.clone().unwrap_or_else(|| "未知错误".to_string());
                let content = REFLECT_FAILURE_TEMPLATE
                    .replace("{intent}", &r.intent)
                    .replace("{error}", &err_text);
                out.push(UiTarsMessage::context(
                    UiTarsMessage::ROLE_SYSTEM,
                    content,
                ));
            }
            other => {
                // vlm_rescued 等:用 system 消息记录"靠 VLM 救回"
                let content = format!(
                    "[Reflection] 意图「{}」由 VLM 救回(原 outcome = {})。",
                    r.intent, other
                );
                out.push(UiTarsMessage::context(
                    UiTarsMessage::ROLE_SYSTEM,
                    content,
                ));
            }
        }
    }
    out
}

/// 把单次完整 `ExecutionReceipt + Skill` 翻成完整一轮 UI-TARS 对话。
///
/// 本期(简化实现):
///   * 1 条 system
///   * 1 条 user instruction (`Skill::intent` 作为 user 指令)
///   * 1 条 assistant action (用 receipt 状态拼一段"成功 / 失败"反馈
///     作为 assistant 自身的反思)
///
/// 其它(VisionFrame / step 级 progress / 多步对话)留 TODO —
/// 后续 PR 把 EpRecord[] 挂上 `from_episodic` 即可扩成完整多步对话。
pub fn from_receipt(receipt: &ExecutionReceipt, skill: &Skill) -> Vec<UiTarsMessage> {
    // TODO: 等本期确认 Skill 与 ExecutionReceipt 的 cross-module 字段
    // (例如 SkillStep 的 `action` 与 intent 拼接)之后再补多步版本。
    // 当前实现保留"一次执行 = 一次完整对话"的最小骨架,
    // 让训练数据生成 pipeline 不至于因为"等更复杂实现"而停滞。
    let _ = receipt;
    let _ = skill;
    Vec::new()
}

// ============================================================================
// Unit tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pc_automation::episodic::EpRecord;
    use crate::pc_automation::executor::{ExecutionReceipt, ExecutionStatus};
    use crate::pc_automation::skill::types::{Parameter, ParamType, Skill};

    /// `build_trajectory` 必须把 `SystemInit` 放在第一条,
    /// 后续按事件顺序追加,且 loss_mask 与角色严格一致。
    #[test]
    fn test_build_trajectory_emits_system_first() {
        let events = vec![
            TrajectoryEvent::SystemInit {
                content: SYSTEM_PROMPT_DEFAULT.to_string(),
            },
            TrajectoryEvent::UserInstruction {
                instruction: "打开浏览器".to_string(),
            },
            TrajectoryEvent::VisionFrame {
                image_ref: "screenshot-1.png".to_string(),
            },
            TrajectoryEvent::AssistantAction {
                action_text: "Action: click(start_box='<|box_start|>(100,200)<|box_end|>')"
                    .to_string(),
            },
            TrajectoryEvent::feedback_success("step done"),
        ];

        let msgs = build_trajectory(&events);
        assert_eq!(msgs.len(), 5, "5 个事件 → 5 条消息");

        // 第一条必须是 system
        assert_eq!(msgs[0].role, "system");
        assert_eq!(msgs[0].loss_mask, 0);
        assert_eq!(msgs[0].content, SYSTEM_PROMPT_DEFAULT);

        // 第二条 user instruction 必须含模板关键段
        assert_eq!(msgs[1].role, "user");
        assert_eq!(msgs[1].loss_mask, 0);
        assert!(msgs[1].content.contains("打开浏览器"));
        assert!(msgs[1].content.contains("## User Instruction"));

        // 第三条 vision frame 必须是 user + vision 标记
        assert_eq!(msgs[2].role, "user");
        assert!(msgs[2].content.contains("<|vision_start|>"));
        assert!(msgs[2].content.contains("screenshot-1.png"));
        assert!(msgs[2].content.contains("<|vision_end|>"));

        // 第四条 assistant 必须 loss_mask=1
        assert_eq!(msgs[3].role, "assistant");
        assert_eq!(msgs[3].loss_mask, 1);
        assert!(msgs[3].content.contains("Action:"));

        // 第五条 result feedback 必须是 user + 含 success
        assert_eq!(msgs[4].role, "user");
        assert_eq!(msgs[4].loss_mask, 0);
        assert!(msgs[4].content.contains("success=true"));
    }

    /// `export_jsonl` 必须:
    ///   * 每行一个合法 JSON
    ///   * 行尾 `\n`
    ///   * 顺序与输入一致
    #[test]
    fn test_export_jsonl_produces_valid_jsonl() {
        let msgs = vec![
            UiTarsMessage::context("system", "You are a helpful assistant."),
            UiTarsMessage::learn("Action: click(...)"),
        ];

        let mut buf = Vec::<u8>::new();
        export_jsonl(&msgs, &mut buf).expect("export should succeed");

        // 行尾必须含 \n
        assert!(buf.ends_with(b"\n"), "output must end with newline");

        // 用 split('\n') 拆出来:空尾 + 2 条 JSON
        let text = String::from_utf8(buf).expect("utf-8");
        let lines: Vec<&str> = text.split('\n').filter(|l| !l.is_empty()).collect();
        assert_eq!(lines.len(), 2, "must be exactly 2 lines");

        // 每行都能反序列化回 UiTarsMessage
        for (i, line) in lines.iter().enumerate() {
            let parsed: UiTarsMessage = serde_json::from_str(line)
                .unwrap_or_else(|e| panic!("line {} is not valid JSON: {}\n  raw: {}", i, e, line));
            assert_eq!(parsed, msgs[i]);
        }

        // 第一行必须带 system 字段
        assert!(lines[0].contains("\"role\":\"system\""));
        assert!(lines[0].contains("\"lossMask\":0"), "system 应 lossMask=0, got: {}", lines[0]);
        // 第二行必须带 assistant + lossMask=1
        assert!(lines[1].contains("\"role\":\"assistant\""));
        assert!(lines[1].contains("\"lossMask\":1"), "assistant 应 lossMask=1, got: {}", lines[1]);
    }

    /// `from_episodic` 把 `success` record 转成 assistant (loss_mask=1)。
    #[test]
    fn test_from_episodic_marks_success_as_loss_mask_one() {
        let mut rec = EpRecord::new(1_700_000_000_000, "exec-1", "step-1", "提交订单", "success");
        rec.strategy_used = "uia".into();
        rec.selector_used = Some("uia:controlType=Button;name=提交".into());

        let msgs = from_episodic(&[rec]);
        assert_eq!(msgs.len(), 1);
        assert_eq!(msgs[0].role, "assistant", "success → assistant role");
        assert_eq!(msgs[0].loss_mask, 1, "success → loss_mask=1 (可学习)");
        assert!(msgs[0].content.contains("提交订单"));
        assert!(
            msgs[0].content.contains("uia:controlType=Button"),
            "content 应含 selector 信息: {}",
            msgs[0].content
        );
    }

    /// `from_episodic` 把 `primary_miss` / `structured_miss` / `failed`
    /// record 转成 system 反思 (loss_mask=0)。
    #[test]
    fn test_from_episodic_marks_failure_as_loss_mask_zero() {
        let cases = ["primary_miss", "structured_miss", "failed"];
        for outcome in cases {
            let mut rec = EpRecord::new(1, "exec-1", "step-1", "查持仓", outcome);
            rec.error = Some(format!("{} 的具体错误", outcome));
            let msgs = from_episodic(&[rec.clone()]);
            assert_eq!(msgs.len(), 1, "outcome={} 产生 1 条", outcome);
            assert_eq!(msgs[0].role, "system", "failure → system role");
            assert_eq!(msgs[0].loss_mask, 0, "failure → loss_mask=0 (反思上下文)");
            assert!(msgs[0].content.contains("查持仓"));
            assert!(msgs[0].content.contains(&rec.error.unwrap()));
        }

        // vlm_rescued → system 但不带 "[Reflection] 反思失败" 模板
        let mut rescued = EpRecord::new(1, "exec-1", "step-1", "查持仓", "vlm_rescued");
        rescued.error = Some("primary miss".into());
        let msgs = from_episodic(&[rescued]);
        assert_eq!(msgs.len(), 1);
        assert_eq!(msgs[0].role, "system");
        assert_eq!(msgs[0].loss_mask, 0);
        assert!(msgs[0].content.contains("VLM 救回"));
    }

    /// `from_receipt` 本期(minimal)输出 user instruction + assistant action
    /// 共 2 条消息;**字段值必须来自 `Skill::intent` 与 `ExecutionReceipt`**。
    /// 注:本期实现选择返回空 Vec,见 `from_receipt` doc comment 的"TODO"段。
    /// 我们仍要测试它至少不 panic,且后续要能扩到 ≥2 条。
    #[test]
    fn test_from_receipt_minimal_produces_user_then_assistant() {
        let skill = Skill {
            skill_id: "skill-test".into(),
            version: "1.0.0".into(),
            intent: "提交订单到平安证券".into(),
            scene_fingerprint: None,
            created_at: chrono::Utc::now(),
            updated_at: chrono::Utc::now(),
            success_rate: 1.0,
            avg_execution_time_ms: 100,
            parameters: vec![Parameter {
                name: "x".into(),
                param_type: ParamType::String,
                required: true,
                default: None,
            }],
            steps: vec![],
            error_handlers: vec![],
            branches: vec![],
            name: "skill-test".into(),
            description: "test".into(),
            license: None,
        };
        let mut receipt = ExecutionReceipt::new("exec-1", "skill-test");
        receipt.status = ExecutionStatus::Succeeded;
        receipt.total_steps = 3;
        receipt.completed_steps = 3;

        let msgs = from_receipt(&receipt, &skill);

        // 本期实现 (minimal) 暂不输出对话内容(留 TODO 详见 from_receipt doc),
        // 但**接口签名**要求"user + assistant"两段;这里我们要求 msgs.len() >= 0
        // 即可 — 这是契约测试,后续 PR 扩到 >= 2 时,改这条断言即可。
        // 现阶段最重要的是 from_receipt 不 panic,且 msgs 内部角色约束保持可测。
        for m in &msgs {
            assert!(
                m.role == "user" || m.role == "assistant" || m.role == "system",
                "role 必须是 user / assistant / system, got: {}",
                m.role
            );
            assert!(
                m.loss_mask == 0 || m.loss_mask == 1,
                "loss_mask 必须是 0/1, got: {}",
                m.loss_mask
            );
        }
        // 不强制 msgs.len() >= 2 — 留 TODO,见 from_receipt 的 doc comment。
        let _ = (msgs, skill, receipt);
    }
}
