// Copyright (c) 2026 tupAI
//
// UIRPA SKILL.md round-trip.
//
// Implements the Anthropic Agent Skills (2025-12) open standard
// for skill file format: a YAML frontmatter block delimited by
// `---` fences, followed by a Markdown body that documents the
// skill's intent, parameters, steps, and error handlers.
//
// Reference: <https://www.anthropic.com/engineering/equipping-agents-for-the-real-world>
//
// The frontmatter shape we support is intentionally tiny (only
// three keys: `name`, `description`, `license`) and is parsed by
// a 30-line hand-rolled scanner instead of `serde_yaml` so we do
// not pull an extra dependency into the SKILL.md hot path. The
// body is free-form Markdown that the front-end will
// render; we only guarantee a minimum set of `##` sections so a
// round-trip preserves enough structure to be human-readable.
//
// File layout:
//   * `export.rs` — `to_skill_md` / `from_skill_md` + tests
//
// Validation rules (mirror the Anthropic Agent Skills 2025-12
// spec):
//   * `name`        : 1-64 chars, lowercase kebab-case,
//                     no leading/trailing hyphen, no consecutive hyphens
//   * `description` : 1-1024 chars
//   * `license`     : optional SPDX identifier, free-form string
//
// `from_skill_md` returns `Result<Skill, String>` per the project
// convention (errors are ferried verbatim to the front-end via
// `commands::*`).

use crate::pc_automation::skill::types::{ErrorHandler, Parameter, Skill, SkillStep};

/// The three keys we recognise in the SKILL.md frontmatter.
const KEY_NAME: &str = "name";
const KEY_DESCRIPTION: &str = "description";
const KEY_LICENSE: &str = "license";

/// Anthropic Agent Skills 2025-12 constraints.
const NAME_MAX: usize = 64;
const DESCRIPTION_MAX: usize = 1024;

/// Serialize a `Skill` to a SKILL.md document (YAML frontmatter
/// + Markdown body). The body always includes `## Intent`,
/// `## Parameters`, `## Steps`, and `## Error Handlers` sections
/// even when their corresponding vectors are empty — that keeps
/// the round-trip stable so a human reading the file always sees
/// the same skeleton.
pub fn to_skill_md(skill: &Skill) -> String {
    let mut out = String::new();
    out.push_str("---\n");
    out.push_str(&format!("{}: {}\n", KEY_NAME, skill.name));
    out.push_str(&format!(
        "{}: {}\n",
        KEY_DESCRIPTION, skill.description
    ));
    if let Some(license) = &skill.license {
        out.push_str(&format!("{}: {}\n", KEY_LICENSE, license));
    }
    out.push_str("---\n\n");
    out.push_str(&render_markdown_body(skill));
    out
}

/// Parse a SKILL.md document back into a `Skill`. The body is
/// parsed for the `## Intent` section to recover the skill's
/// `intent`; the rest of the body is currently discarded (the
/// executor / front-end reads the JSON fields directly). The
/// `name` / `description` / `license` keys are validated against
/// the Anthropic Agent Skills 2025-12 rules.
pub fn from_skill_md(content: &str) -> Result<Skill, String> {
    let (front, body) = split_front_matter(content)
        .ok_or_else(|| "SKILL.md 缺少 frontmatter 分隔符 (---)".to_string())?;

    let front_map = parse_simple_yaml(front)?;
    let name = front_map
        .get(KEY_NAME)
        .cloned()
        .ok_or_else(|| "SKILL.md frontmatter 缺少 name 字段".to_string())?;
    let description = front_map
        .get(KEY_DESCRIPTION)
        .cloned()
        .ok_or_else(|| "SKILL.md frontmatter 缺少 description 字段".to_string())?;
    let license = front_map.get(KEY_LICENSE).cloned();

    validate_name(&name)?;
    validate_description(&description)?;

    let intent = extract_intent_section(body).unwrap_or_default();

    Ok(Skill {
        // 复用 timestamp:SKILL.md 不携带创建时间,使用 epoch 占位,
        // 调用方在导入后可调用 storage::store 触发 updated_at 重写。
        skill_id: name.clone(),
        version: String::new(),
        intent,
        scene_fingerprint: None,
        created_at: chrono::DateTime::<chrono::Utc>::from_timestamp(0, 0)
            .unwrap_or_else(chrono::Utc::now),
        updated_at: chrono::DateTime::<chrono::Utc>::from_timestamp(0, 0)
            .unwrap_or_else(chrono::Utc::now),
        success_rate: 0.0,
        avg_execution_time_ms: 0,
        parameters: Vec::new(),
        steps: Vec::new(),
        error_handlers: Vec::new(),
        branches: Vec::new(),
        name,
        description,
        license,
    })
}

// -----------------------------------------------------------------
// 内部辅助函数
// -----------------------------------------------------------------

/// Locate the `---` fence pair at the head of the document and
/// return `(frontmatter, body)`. Returns `None` if the document
/// does not start with a frontmatter block.
fn split_front_matter(text: &str) -> Option<(&str, &str)> {
    // 兼容 Windows CRLF (`---\r\n`) 与 Unix LF (`---\n`)。
    let first_line = text.lines().next()?;
    if first_line.trim_end() != "---" {
        return None;
    }
    let rest = &text[first_line.len()..];
    let rest = rest.strip_prefix("\r\n").or_else(|| rest.strip_prefix('\n'))?;
    // 在 rest 中查找紧跟行首的 "---" 结束围栏。两种行尾都要兼容。
    // 用 str::find 而不是逐字节扫描,避免把 idx 推进到多字节 UTF-8
    // 字符中间 (之前的 idx += 1 会在中文 description 上 panic)。
    let find_fence = |haystack: &str| -> Option<usize> {
        let lf = haystack.find("\n---")?;
        let after = &haystack[lf + 4..];
        if after.is_empty() || after.starts_with('\n') || after.starts_with('\r') {
            Some(lf)
        } else {
            // 找到的 "---" 不是行尾的结束围栏,需要继续往后找。
            // 用 char_indices 跳过当前匹配,避免再次落在多字节字符中间。
            let next_byte = lf + 4;
            let mut start = next_byte;
            while let Some(rel) = haystack[start..].find("\n---") {
                let abs = start + rel;
                let after2 = &haystack[abs + 4..];
                if after2.is_empty() || after2.starts_with('\n') || after2.starts_with('\r') {
                    return Some(abs);
                }
                // 跳到匹配末尾,继续往后找; 但要注意 "\n---" 占 4 字节,
                // 下一轮 find 会从 abs+4 开始,自动落到下一个字符边界。
                start = abs + 4;
            }
            None
        }
    };
    let end = find_fence(rest)?;
    let (front, body_with_fence) = rest.split_at(end);
    let body = body_with_fence
        .strip_prefix("\n---")
        .unwrap_or(body_with_fence);
    let body = body
        .strip_prefix("\r\n")
        .or_else(|| body.strip_prefix('\n'))
        .unwrap_or("");
    Some((front, body))
}

/// Hand-rolled YAML-ish parser that only handles the frontmatter
/// shape we emit: one `key: value` per line, optional surrounding
/// double-quotes on the value. Anything more exotic (multi-line
/// scalars, anchors, flow style) returns an explicit error so the
/// caller knows we deliberately did not try.
fn parse_simple_yaml(front: &str) -> Result<std::collections::HashMap<String, String>, String> {
    let mut out = std::collections::HashMap::new();
    for (idx, raw_line) in front.lines().enumerate() {
        let line = raw_line.trim_end();
        if line.trim().is_empty() {
            continue;
        }
        let colon = line.find(':').ok_or_else(|| {
            format!("frontmatter 第 {} 行缺少 ':': {:?}", idx + 1, line)
        })?;
        let key = line[..colon].trim().to_string();
        if key.is_empty() {
            return Err(format!("frontmatter 第 {} 行 key 为空: {:?}", idx + 1, line));
        }
        let mut value = line[colon + 1..].trim().to_string();
        // 允许使用双引号包裹的值,这里统一剥离外层引号。
        if value.len() >= 2 && value.starts_with('"') && value.ends_with('"') {
            value = value[1..value.len() - 1].to_string();
        }
        out.insert(key, value);
    }
    Ok(out)
}

/// Render the Markdown body. Always includes the four canonical
/// sections so the round-trip is deterministic and human-readable.
fn render_markdown_body(skill: &Skill) -> String {
    let mut out = String::new();
    out.push_str("## Intent\n\n");
    out.push_str(skill.intent.trim());
    out.push_str("\n\n");

    out.push_str("## Parameters\n\n");
    if skill.parameters.is_empty() {
        out.push_str("_无_\n\n");
    } else {
        for p in &skill.parameters {
            out.push_str(&format!("- `{}` ({}): {}\n", p.name, param_type_label(p), param_doc(p)));
        }
        out.push('\n');
    }

    out.push_str("## Steps\n\n");
    if skill.steps.is_empty() {
        out.push_str("_无_\n\n");
    } else {
        for (i, step) in skill.steps.iter().enumerate() {
            out.push_str(&format!("{}. {}\n", i + 1, render_step_line(step)));
        }
        out.push('\n');
    }

    out.push_str("## Error Handlers\n\n");
    if skill.error_handlers.is_empty() {
        out.push_str("_无_\n");
    } else {
        for (i, h) in skill.error_handlers.iter().enumerate() {
            out.push_str(&format!("{}. {}\n", i + 1, render_error_handler_line(h)));
        }
    }
    out
}

fn param_type_label(p: &Parameter) -> &'static str {
    match p.param_type {
        crate::pc_automation::skill::types::ParamType::String => "string",
        crate::pc_automation::skill::types::ParamType::Number => "number",
        crate::pc_automation::skill::types::ParamType::Boolean => "boolean",
    }
}

fn param_doc(p: &Parameter) -> String {
    let required = if p.required { "必填" } else { "可选" };
    match &p.default {
        Some(v) => format!("{}, 默认 {}", required, v),
        None => required.to_string(),
    }
}

fn render_step_line(step: &SkillStep) -> String {
    let action = match &step.action {
        crate::pc_automation::skill::types::SkillAction::Click => "click".to_string(),
        crate::pc_automation::skill::types::SkillAction::Input { value } => {
            format!("input(\"{}\")", value)
        }
        crate::pc_automation::skill::types::SkillAction::Wait { ms } => {
            format!("wait({}ms)", ms)
        }
        crate::pc_automation::skill::types::SkillAction::Hotkey { keys } => {
            format!("hotkey({})", keys)
        }
    };
    if step.description.is_empty() {
        format!("[{}] {}", step.id, action)
    } else {
        format!("[{}] {} — {}", step.id, action, step.description)
    }
}

fn render_error_handler_line(h: &ErrorHandler) -> String {
    let action = match &h.action {
        crate::pc_automation::skill::types::SkillAction::Click => "click".to_string(),
        crate::pc_automation::skill::types::SkillAction::Input { value } => {
            format!("input(\"{}\")", value)
        }
        crate::pc_automation::skill::types::SkillAction::Wait { ms } => {
            format!("wait({}ms)", ms)
        }
        crate::pc_automation::skill::types::SkillAction::Hotkey { keys } => {
            format!("hotkey({})", keys)
        }
    };
    format!("触发 {:?} → {} (重试 {} 次)", h.condition, action, h.retry_count)
}

/// Extract the text under a `## Intent` heading (the first one
/// we find). Returns `None` if the section is absent.
fn extract_intent_section(body: &str) -> Option<String> {
    let mut lines = body.lines();
    while let Some(line) = lines.next() {
        if line.trim().eq_ignore_ascii_case("## intent") {
            // 跳过空行,收集直到下一个 ## / 空段落结束。
            let mut buf = String::new();
            for inner in lines.by_ref() {
                if inner.trim_start().starts_with("## ") {
                    break;
                }
                if !buf.is_empty() {
                    buf.push('\n');
                }
                buf.push_str(inner);
            }
            return Some(buf.trim().to_string());
        }
    }
    None
}

// -----------------------------------------------------------------
// 校验
// -----------------------------------------------------------------

fn validate_name(name: &str) -> Result<(), String> {
    let len = name.chars().count();
    if len == 0 {
        return Err("SKILL.md name 字段不能为空".to_string());
    }
    if len > NAME_MAX {
        return Err(format!("SKILL.md name 字段长度 {} 超过上限 {}", len, NAME_MAX));
    }
    // kebab-case:首末字符必须为小写字母或数字;中间可包含一个连字符;
    // 禁止连续连字符;禁止大写 / 下划线 / 空格 / 其它符号。
    let chars: Vec<char> = name.chars().collect();
    if !is_kebab_char(chars[0]) {
        return Err(format!("SKILL.md name 首字符必须是字母或数字: {:?}", name));
    }
    if !is_kebab_char(*chars.last().unwrap()) {
        return Err(format!("SKILL.md name 末字符必须是字母或数字: {:?}", name));
    }
    let mut prev_hyphen = false;
    for c in &chars {
        if *c == '-' {
            if prev_hyphen {
                return Err(format!("SKILL.md name 包含连续连字符: {:?}", name));
            }
            prev_hyphen = true;
        } else {
            prev_hyphen = false;
            if !is_kebab_char(*c) {
                return Err(format!(
                    "SKILL.md name 含非法字符 {:?};只允许小写字母 / 数字 / 连字符",
                    c
                ));
            }
        }
    }
    Ok(())
}

fn is_kebab_char(c: char) -> bool {
    c.is_ascii_lowercase() || c.is_ascii_digit()
}

fn validate_description(description: &str) -> Result<(), String> {
    let len = description.chars().count();
    if len == 0 {
        return Err("SKILL.md description 字段不能为空".to_string());
    }
    if len > DESCRIPTION_MAX {
        return Err(format!(
            "SKILL.md description 字段长度 {} 超过上限 {}",
            len, DESCRIPTION_MAX
        ));
    }
    Ok(())
}

// =============================================================
// 单元测试
// =============================================================

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pc_automation::skill::types::{
        ElementSelector, ErrorCondition, ParamType, Parameter, Selector, SelectorKind, Skill,
        SkillAction, SkillStep,
    };

    /// 构造一个最小可用的 `Skill` 用于测试。
    fn make_test_skill() -> Skill {
        Skill {
            skill_id: "my-skill".into(),
            version: "1.0.0".into(),
            intent: "提交订单".into(),
            scene_fingerprint: Some("sha256:abc".into()),
            created_at: chrono::Utc::now(),
            updated_at: chrono::Utc::now(),
            success_rate: 0.9,
            avg_execution_time_ms: 100,
            parameters: vec![Parameter {
                name: "customer".into(),
                param_type: ParamType::String,
                required: true,
                default: None,
            }],
            steps: vec![SkillStep::single(
                "step_1",
                "点提交按钮",
                "uia:controlType=Button;name=提交",
            )],
            error_handlers: vec![],
            branches: vec![],
            name: "my-skill".into(),
            description: "当用户想提交订单时使用此技能".into(),
            license: Some("Apache-2.0".into()),
        }
    }

    #[test]
    fn test_to_skill_md_produces_valid_frontmatter() {
        let skill = make_test_skill();
        let md = to_skill_md(&skill);

        // 必须以 --- 起始,且包含第二个 --- 作为 frontmatter 收尾。
        assert!(md.starts_with("---\n"), "must start with frontmatter fence");
        let second_fence = md[4..].find("\n---").expect("closing fence missing");
        let front = &md[4..4 + second_fence];
        // 三个 frontmatter 字段必须存在。
        assert!(front.contains("name: my-skill"), "frontmatter: {}", front);
        assert!(front.contains("description: "), "frontmatter: {}", front);
        assert!(front.contains("license: Apache-2.0"), "frontmatter: {}", front);
        // license 字段在 license=None 时不应出现。
        let mut skill2 = skill.clone();
        skill2.license = None;
        let md2 = to_skill_md(&skill2);
        assert!(!md2.contains("license:"), "license=None 时不应输出 license 行");
    }

    #[test]
    fn test_from_skill_md_round_trip() {
        let skill = make_test_skill();
        let md = to_skill_md(&skill);
        let parsed = from_skill_md(&md).expect("round-trip must succeed");

        // 关键 SKILL.md 字段一致。
        assert_eq!(parsed.name, skill.name);
        assert_eq!(parsed.description, skill.description);
        assert_eq!(parsed.license, skill.license);
        // body 里的 ## Intent 段被回填到 intent。
        assert_eq!(parsed.intent, skill.intent);
    }

    #[test]
    fn test_from_skill_md_missing_name_errors() {
        let md = "---\ndescription: 仅含 description\n---\n\n## Intent\n\nx\n";
        let err = from_skill_md(md).expect_err("缺少 name 必须报错");
        assert!(err.contains("name"), "错误信息应提及 name: {}", err);
    }

    #[test]
    fn test_from_skill_md_invalid_yaml_errors() {
        // 一行没有冒号 → 触发我们的极简 parser 报错。
        let md = "---\nthis line has no colon\n---\n";
        let err = from_skill_md(md).expect_err("非法 frontmatter 必须报错");
        assert!(
            err.contains("缺少 ':'") || err.contains("frontmatter"),
            "错误信息应说明 frontmatter 解析失败: {}",
            err
        );

        // name 字段含大写 → 触发 kebab-case 校验。
        let md = "---\nname: BadName\ndescription: x\n---\n";
        let err = from_skill_md(md).expect_err("非法 name 必须报错");
        assert!(err.contains("name"), "错误信息应提及 name: {}", err);

        // 完全没有 frontmatter 分隔符。
        let md = "no fences at all";
        let err = from_skill_md(md).expect_err("缺少 frontmatter 必须报错");
        assert!(err.contains("frontmatter"), "错误信息应提及 frontmatter: {}", err);
    }

    #[test]
    fn test_markdown_body_includes_intent_and_steps() {
        let mut skill = make_test_skill();
        skill.intent = "批量重命名文件".into();
        skill.steps = vec![
            SkillStep::single("step_1", "打开文件管理器", "uia:name=Files"),
            SkillStep::single("step_2", "选中目标文件", "uia:name=Target"),
        ];
        skill.error_handlers = vec![crate::pc_automation::skill::types::ErrorHandler {
            condition: ErrorCondition::SelectorMiss { after_attempts: 3 },
            action: SkillAction::Click,
            element_selector: ElementSelector {
                version: "1.0".into(),
                primary: Selector {
                    kind: SelectorKind::Uia,
                    value: "uia:name=Retry".into(),
                    stability_score: 0.9,
                    context: None,
                    match_threshold: None,
                    resolution: None,
                },
                fallbacks: vec![],
                iframe_context: None,
                shadow_root_context: None,
            },
            retry_count: 2,
        }];
        let md = to_skill_md(&skill);

        // 四个必需 section 必须出现。
        assert!(md.contains("## Intent"), "missing ## Intent");
        assert!(md.contains("## Parameters"), "missing ## Parameters");
        assert!(md.contains("## Steps"), "missing ## Steps");
        assert!(md.contains("## Error Handlers"), "missing ## Error Handlers");

        // intent 内容、每个 step 的 description、错误处理器条目都要出现。
        assert!(md.contains("批量重命名文件"), "intent 文本未出现");
        assert!(md.contains("打开文件管理器"), "step 1 description 缺失");
        assert!(md.contains("选中目标文件"), "step 2 description 缺失");
        assert!(md.contains("重试 2 次"), "error handler 重试次数缺失");
    }
}
