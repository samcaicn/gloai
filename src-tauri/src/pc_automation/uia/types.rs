// Copyright (c) 2026 tupAI
//
// UIA types: the in-memory representation of a Windows UI Automation
// node, plus the selector grammar used to address one. The selector
// is intentionally recursive (`path: Vec<UiaSelector>`) so it can
// express "find a Pane whose name is X, inside a Pane whose
// automation_id is Y" with no extra DSL.

use serde::{Deserialize, Serialize};

use crate::pc_automation::parse_error::ParseError;

/// In-memory shape of a UIA node. Mirrors the fields the
/// `uiautomation` crate (and its macOS / Linux shims) actually
/// expose. `bounding_rect` is `(left, top, width, height)` in screen
/// coordinates. `runtime_id` is the opaque provider id the OS hands
/// out, kept as `i64` to be platform-agnostic.
#[derive(Serialize, Deserialize, Debug, Clone, Default)]
#[serde(rename_all = "camelCase")]
pub struct UiaNode {
    pub name: String,
    pub class_name: String,
    pub automation_id: String,
    pub control_type: String,
    pub bounding_rect: (i32, i32, u32, u32),
    pub children: Vec<UiaNode>,
    pub runtime_id: Option<i64>,
}

/// A single link in a UIA selector chain. All fields are optional so
/// the caller can express loose matches ("a Button whose name is
/// 提交") or tight ones ("a Button with AutomationId=login_btn").
#[derive(Serialize, Deserialize, Debug, Clone, Default, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct UiaSelector {
    pub control_type: Option<String>,
    pub name: Option<String>,
    /// 模糊匹配（子串包含，大小写敏感）。用于 UIA 识别降级链中
    /// 技能代码按可见文本模糊定位元素（如 `nameContains: "提交"`）。
    /// 与 `name` 互斥：同时给出时两个条件都需满足。
    pub name_contains: Option<String>,
    pub automation_id: Option<String>,
    pub class_name: Option<String>,
    /// Path from the focused window down to the target. Empty means
    /// "search from the focused window root".
    pub path: Vec<UiaSelector>,
}

/// Parse a `uia:` selector literal of the form:
///
/// ```text
/// uia:controlType=Button;name=提交;automationId=login_btn
/// ```
///
/// `path:` segments are not supported in this initial cut — the
/// v5 router only ever navigates a single level. We deliberately
/// keep the parser strict (no fuzzy fallbacks) so any malformed
/// selector is caught at recipe-eval time, not at click time.
pub fn parse_uia_selector(s: &str) -> Result<UiaSelector, ParseError> {
    const PREFIX: &str = "uia:";
    let body = s.strip_prefix(PREFIX).ok_or_else(|| {
        ParseError::InvalidPrefix(s.chars().take(4).collect::<String>())
    })?;

    let mut sel = UiaSelector::default();
    if body.is_empty() {
        return Ok(sel);
    }

    for kv in body.split(';') {
        if kv.is_empty() {
            continue;
        }
        let (k, v) = kv.split_once('=').ok_or(ParseError::MissingField("key=value"))?;
        match k.trim() {
            "controlType" | "control_type" => sel.control_type = Some(v.to_string()),
            "name" => sel.name = Some(v.to_string()),
            "nameContains" | "name_contains" => sel.name_contains = Some(v.to_string()),
            "automationId" | "automation_id" => sel.automation_id = Some(v.to_string()),
            "className" | "class_name" => sel.class_name = Some(v.to_string()),
            _other => {
                return Err(ParseError::MissingField("unknown_field"))
            }
        }
    }
    Ok(sel)
}
