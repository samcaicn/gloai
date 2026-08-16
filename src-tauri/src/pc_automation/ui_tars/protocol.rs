// Copyright (c) 2026 AIMarketing
//
// AIMarketing v5 §6.1 — UI-TARS 协议层(prompt 模板 + 响应解析)。
//
// 本文件提供 UI-TARS 协议层的不依赖 VLM 救援主流程的"协议工具":
//   * `COMPUTER_USE_TEMPLATE` — UI-TARS 事实标准
//     (ByteDance `COMPUTER_USE_DOUBAO`) prompt 模板
//   * `build_prompt`         — 固定模板填充,无网络 I/O
//   * `parse_ui_tars_response` — 从 `Thought: ... Action: ...` 字符串
//     解析出 `VlmAction` 的"协议层"代码
//
// 1. 原本和 vlm_rescue 的"救援主流程"(VlmAction / RescueContext /
//    build_dynamic_prompt 等)混在 vlm_rescue::analyzer 里。本文件
//    只关心"UI-TARS 协议文本"本身,不关心"如何救援"。
// 2. 抽出后,trajectory / 未来 SFT pipeline 可以直接用
//    `ui_tars::COMPUTER_USE_TEMPLATE` 做 prompt 校验。
//
// 向后兼容: `pc_automation::vlm_rescue::analyzer` 仍 re-export
//          `build_prompt` / `parse_ui_tars_response` /
//          `COMPUTER_USE_TEMPLATE` / `PARSER_DEFAULT_CONFIDENCE`,
//          老调用路径继续可用。

use serde::{Deserialize, Serialize};

// ============================================================================
// VlmAction / VlmTarget (UI-TARS 解析产物)
// ============================================================================
//
// 这两个类型原本在 vlm_rescue::analyzer 里,作为 VLM 救援主流程的输出
// 数据模型。本期为了"协议层"独立,**也搬到这里** —— 理由:它们是
// UI-TARS 协议"Thought + Action"双段解析的目标,与 VLM 救援主流程
// 解耦。这样 trajectory::export / SFT pipeline 也能直接引用。
//
// 向后兼容: vlm_rescue::analyzer 仍 re-export。

/// One concrete action the VLM proposes. Kept deliberately tiny so
/// the JSON contract between the LLM and our executor is a single
/// short object that fits any model's "function-call" response.
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct VlmAction {
    /// `click` | `double_click` | `right_click` | `input` | `wait` | `scroll` | `key`
    pub action: String,
    pub target: VlmTarget,
    /// Model's self-reported confidence in `[0, 1]`. Values below
    /// `VlmRescue::confidence_threshold` are rejected.
    pub confidence: f32,
    /// Free-form rationale (one sentence). Surfaced in the front-end
    /// so users understand *why* the executor picked this element.
    pub explanation: String,
}

/// Where to deliver the action. Pixel coordinates are screen-space
/// (top-left origin) so the caller doesn't have to know which display
/// the screenshot came from.
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct VlmTarget {
    /// `pixel` | `element` | `ocr_text`. Reserved for future
    /// structured targets; today we always get `pixel` and treat the
    /// coordinates as the click point.
    pub kind: String,
    pub x: i32,
    pub y: i32,
}

// ============================================================================
// UI-TARS response parser
// ============================================================================

/// 默认 confidence:UI-TARS 协议文本本身不写 confidence,
/// 我们用 0.5 兜底。这与 `VlmRescue::confidence_threshold = 0.6`
/// 配合,刚好触发阈值闸门的拒绝路径,在 stub 阶段方便测试。
pub const PARSER_DEFAULT_CONFIDENCE: f32 = 0.5;

/// UI-TARS 动作名 → `VlmAction.action` 字符串的映射。
///
/// 字段值映射遵循 VlmAction 的现有 wire 格式 (commit 351c659 落地);
/// 新增的 `drag` 与 `finished` 是 VlmAction.action 字符串的新取值,
/// 字段本身仍是 `String`,因此不破坏现有结构。
fn map_action_kind(kind: &str) -> Option<&'static str> {
    match kind {
        "click" => Some("click"),
        "left_double" => Some("double_click"),
        "right_single" => Some("right_click"),
        "drag" => Some("drag"),
        "hotkey" => Some("key"),
        "type" => Some("input"),
        "scroll" => Some("scroll"),
        "wait" => Some("wait"),
        "finished" => Some("finished"),
        _ => None,
    }
}

/// 从 `<|box_start|>x y<|box_end|>` 字符串中提取 (x, y)。
///
/// 失败时返回 `(0, 0)` 兜底,让后续流程至少有可执行目标,
/// 真正的拒绝由阈值闸门完成。
fn parse_box(s: &str) -> (i32, i32) {
    // 兼容 `<|box_start|>x y<|box_end|>` 与宽松的 `<|box_start|>x<|box_end|>`
    let inner = s
        .replace("<|box_start|>", "")
        .replace("<|box_end|>", "")
        .trim()
        .to_string();
    let mut parts = inner.split_whitespace();
    let x = parts
        .next()
        .and_then(|t| t.parse::<i32>().ok())
        .unwrap_or(0);
    let y = parts
        .next()
        .and_then(|t| t.parse::<i32>().ok())
        .unwrap_or(0);
    (x, y)
}

/// 从 Action 行中提取动作名 (`click` / `type` / `hotkey` / `drag` /
/// `scroll` / `wait` / `finished` / `left_double` / `right_single`)。
///
/// 实现:取左括号 `(` 之前的字符,trim 后就是动作名。
fn extract_action_kind(action_line: &str) -> Option<String> {
    let head = action_line.split('(').next()?.trim();
    if head.is_empty() {
        None
    } else {
        Some(head.to_string())
    }
}

/// 从 Action 行中提取 `start_box='<|box_start|>x y<|box_end|>'` 的字面量。
///
/// 容错:既支持单引号也支持双引号;找不到时返回空串。
fn extract_start_box_literal(action_line: &str) -> String {
    // 找 `start_box=` 之后的引号包围内容
    let lower = action_line.find("start_box=");
    let Some(idx) = lower else {
        return String::new();
    };
    let after = &action_line[idx + "start_box=".len()..];
    let after = after.trim_start();
    let quote = after.chars().next();
    let Some(q) = quote else { return String::new() };
    if q != '\'' && q != '"' {
        return String::new();
    }
    let after_quote = &after[q.len_utf8()..];
    if let Some(end) = after_quote.find(q) {
        after_quote[..end].to_string()
    } else {
        String::new()
    }
}

/// 解析 UI-TARS (ByteDance COMPUTER_USE_DOUBAO) 协议字符串为
/// `VlmAction`,供 `VlmRescue::try_rescue` 使用。
///
/// 协议格式:
/// ```text
/// Thought: <中文思路>
/// Action: click(start_box='<|box_start|>x y<|box_end|>')
/// ```
///
/// 提取字段:
///   * `thought`   — `Thought:` 之后到 `Action:` 之前的内容
///   * `action_kind` — `click` / `type` / `hotkey` / `drag` / `scroll` /
///                     `wait` / `finished` / `left_double` / `right_single`
///   * `coordinates` — 从 `start_box='...'` 提取的 (x, y)
///   * `confidence` — 0.0-1.0 之间的 `f32`,协议文本无显式 confidence
///                    时使用 `PARSER_DEFAULT_CONFIDENCE` 兜底
///
/// 错误信息用中文,便于在 VLM rescue 回路失败时直接回显到前端。
pub fn parse_ui_tars_response(response: &str) -> Result<VlmAction, String> {
    let trimmed = response.trim();
    if trimmed.is_empty() {
        return Err("VLM 响应为空".to_string());
    }

    // 1. 切分 Thought / Action 段落
    //    容错:大小写不敏感、忽略首尾空白、允许 Thought 在前或 Action 在前
    let lower = trimmed.to_ascii_lowercase();
    let thought_pos = lower.find("thought:");
    let action_pos = lower.find("action:");

    let (thought, action_line) = match (thought_pos, action_pos) {
        (Some(t), Some(a)) if t < a => {
            let thought = trimmed[t + "thought:".len()..a].trim().to_string();
            let action = trimmed[a + "action:".len()..].trim().to_string();
            (thought, action)
        }
        (Some(t), Some(a)) => {
            // Action 出现在 Thought 之前(LLM 输出顺序颠倒)。
            // 之前这里用 `unreachable!()` 会在真实业务输入下直接 panic。
            // 改为按 Action 在前的方式切分:thought 取 Action 之后到结尾的内容,
            // action 取 Thought 之前到 Action 末尾的内容。
            let action = trimmed[a + "action:".len()..t].trim().to_string();
            let thought = trimmed[t + "thought:".len()..].trim().to_string();
            (thought, action)
        }
        (Some(t), None) => {
            // 仅有 Thought,没 Action — 协议不完整
            return Err(format!(
                "VLM 响应缺少 Action 段:thought='{}'",
                trimmed[t + "thought:".len()..].trim()
            ));
        }
        (None, Some(a)) => {
            // 仅有 Action,没 Thought — 视为可解析,但 thought 留空
            (String::new(), trimmed[a + "action:".len()..].trim().to_string())
        }
        (None, None) => {
            return Err(format!(
                "VLM 响应既不包含 Thought: 也不包含 Action:,无法解析: {}",
                trimmed
            ));
        }
    };

    // 2. 取 Action 行的第一行(同一段只解析首个 Action 调用)
    let action_line = action_line.lines().next().unwrap_or("").trim();
    if action_line.is_empty() {
        return Err("VLM 响应 Action 段为空".to_string());
    }

    // 3. 动作名
    let raw_kind = extract_action_kind(action_line)
        .ok_or_else(|| format!("VLM 响应 Action 段无法提取动作名: '{}'", action_line))?;
    let mapped_kind = map_action_kind(&raw_kind).ok_or_else(|| {
        format!(
            "VLM 响应使用了未知的动作名 '{}' (期望 click/left_double/right_single/drag/hotkey/type/scroll/wait/finished)",
            raw_kind
        )
    })?;

    // 4. 坐标(从 start_box 提取)
    let box_literal = extract_start_box_literal(action_line);
    let (x, y) = if box_literal.is_empty() {
        (0, 0)
    } else {
        parse_box(&box_literal)
    };

    Ok(VlmAction {
        action: mapped_kind.to_string(),
        target: VlmTarget {
            kind: "pixel".to_string(),
            x,
            y,
        },
        confidence: PARSER_DEFAULT_CONFIDENCE,
        explanation: thought,
    })
}

// ============================================================================
// FIXED-TEMPLATE PROMPT
// ============================================================================

/// UI-TARS (ByteDance) 事实标准 `COMPUTER_USE_DOUBAO` 风格模板。
/// 参考: deepwiki.com/bytedance/UI-TARS/7-training-data-format。
///
/// 输出协议必须用 `Thought: ... Action: ...` 双段格式;
/// 坐标用 `<|box_start|>x y<|box_end|>` 包裹。
pub const COMPUTER_USE_TEMPLATE: &str = r#"You are a GUI agent. You are given a task and your action history, with screenshots. You need to perform the next action to complete the task.

## Output Format
Thought: 使用中文描述当前任务分析和下一步计划
Action: 调用动作空间中的方法,如 click(start_box='<|box_start|>x y<|box_end|>')

## Action Space
click(start_box='<|box_start|>x y<|box_end|>')
left_double(start_box='<|box_start|>x y<|box_end|>')
right_single(start_box='<|box_start|>x y<|box_end|>')
drag(start_box='<|box_start|>x1 y1<|box_end|>', end_box='<|box_start|>x2 y2<|box_end|>')
hotkey(key='ctrl c')
type(content='xxx')
scroll(start_box='<|box_start|>x y<|box_end|>', direction='down or up or right or left')
wait()
finished(content='xxx')

## User Instruction
{instruction}
"#;

/// The fixed prompt template. Used as the fallback when the
/// cloud LLM is unavailable and as the structural skeleton the
/// dynamic-prompt builder follows.
///
/// The function is `async` to match `build_dynamic_prompt`'s
/// signature so callers can swap them transparently. The fixed
/// path does no I/O.
///
/// 接收 step_summary + intent + 可选 primary_err / fallback_err,
/// 按 UI-TARS COMPUTER_USE_DOUBAO 模板填充。可选错误信息
/// 会以"## 上次救援失败原因"小节追加,避免对模板主体造成污染。
pub async fn build_prompt(
    step_summary: &str,
    intent: &str,
    primary_err: Option<&str>,
    fallback_err: Option<&str>,
) -> String {
    let base = COMPUTER_USE_TEMPLATE.replace("{instruction}", intent);

    // 可选上下文: 失败步骤摘要 + 上次错误信息
    let mut extra = String::new();
    if !step_summary.trim().is_empty() {
        extra.push_str(&format!("\n## 当前失败步骤\n{}\n", step_summary));
    }
    let mut err_lines: Vec<String> = Vec::new();
    if let Some(e) = primary_err {
        err_lines.push(format!("- primary 错误: {}", e));
    }
    if let Some(e) = fallback_err {
        err_lines.push(format!("- fallback 错误: {}", e));
    }
    if !err_lines.is_empty() {
        extra.push_str(&format!("\n## 上次救援失败原因\n{}\n", err_lines.join("\n")));
    }

    format!("{}{}", base, extra)
}

// ============================================================================
// Unit tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_click_extracts_thought_and_box() {
        let s = "Thought: 用户想要提交订单。\nAction: click(start_box='<|box_start|>495 30<|box_end|>')";
        let a = parse_ui_tars_response(s).unwrap();
        assert_eq!(a.action, "click");
        assert_eq!(a.target.x, 495);
        assert_eq!(a.target.y, 30);
        assert!(a.explanation.contains("提交订单"));
        assert!((a.confidence - PARSER_DEFAULT_CONFIDENCE).abs() < 1e-6);
    }

    #[test]
    fn parse_type_does_not_require_box() {
        let s = "Action: type(content='hello world')";
        let a = parse_ui_tars_response(s).unwrap();
        assert_eq!(a.action, "input"); // UI-TARS `type` → wire `input`
        assert_eq!(a.target.x, 0);
        assert_eq!(a.target.y, 0);
    }

    #[test]
    fn parse_hotkey_and_drag_and_finished() {
        let hk = parse_ui_tars_response("Action: hotkey(key='ctrl c')").unwrap();
        assert_eq!(hk.action, "key");
        let dr = parse_ui_tars_response(
            "Action: drag(start_box='<|box_start|>10 10<|box_end|>', end_box='<|box_start|>20 20<|box_end|>')",
        )
        .unwrap();
        assert_eq!(dr.action, "drag");
        assert_eq!(dr.target.x, 10);
        let done = parse_ui_tars_response("Action: finished(content='任务完成')").unwrap();
        assert_eq!(done.action, "finished");
    }

    #[test]
    fn parse_invalid_response_yields_chinese_error() {
        let err = parse_ui_tars_response("").unwrap_err();
        assert!(err.contains("VLM 响应为空"), "got: {}", err);

        let err = parse_ui_tars_response("Just some text").unwrap_err();
        assert!(err.contains("既不包含 Thought: 也不包含 Action:"), "got: {}", err);

        let err = parse_ui_tars_response("Thought: only thought").unwrap_err();
        assert!(err.contains("缺少 Action 段"), "got: {}", err);
    }

    #[tokio::test]
    async fn build_prompt_includes_intent_and_step() {
        let p = build_prompt("点击提交", "提交订单", None, None).await;
        assert!(p.contains("## User Instruction\n提交订单"), "got: {}", p);
        assert!(p.contains("## 当前失败步骤\n点击提交"), "got: {}", p);
    }

    #[tokio::test]
    async fn build_prompt_appends_error_section_when_provided() {
        let p = build_prompt("s", "i", Some("e1"), Some("e2")).await;
        assert!(p.contains("- primary 错误: e1"));
        assert!(p.contains("- fallback 错误: e2"));
    }

    #[tokio::test]
    async fn build_prompt_omits_error_section_when_all_none() {
        let p = build_prompt("s", "i", None, None).await;
        assert!(!p.contains("## 上次救援失败原因"), "got: {}", p);
    }
}
