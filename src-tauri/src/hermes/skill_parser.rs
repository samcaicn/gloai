
//
// Skill file parser. The original TypeScript module split the SKILL.md
// file into YAML front-matter and Markdown body. The Rust port uses
// a small line-based splitter and `serde_yaml` for the manifest.

use serde::{Deserialize, Serialize};

use super::skill_manifest::SkillManifest;

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct ParsedSkill {
    pub manifest: SkillManifest,
    pub body: String,
}

pub fn split_front_matter(text: &str) -> Option<(&str, &str)> {
    // 兼容 Windows CRLF (`---\r\n`) 与 Unix LF (`---\n`)。
    // 不直接用字面 `---\n` 字节序列比较，避免 Git autocrlf 下 SKILL.md
    // 全部解析失败。
    let first_line = text.lines().next()?;
    if first_line.trim_end() != "---" { return None; }
    let rest = &text[first_line.len()..];
    // 跳过首行后的换行（\n 或 \r\n）
    let rest = rest.strip_prefix("\r\n").or_else(|| rest.strip_prefix('\n'))?;
    // 找到下一个独占一行的 `---`（允许 \r\n 或 \n 前缀）
    let mut idx = 0usize;
    let mut end = None;
    while idx < rest.len() {
        if rest[idx..].starts_with("\r\n---") {
            // 后面必须紧跟行尾或换行，避免误命中 `---abc`
            let after = &rest[idx + 5..];
            if after.is_empty() || after.starts_with('\n') || after.starts_with('\r') {
                end = Some(idx);
                break;
            }
        }
        if rest[idx..].starts_with("\n---") {
            let after = &rest[idx + 4..];
            if after.is_empty() || after.starts_with('\n') || after.starts_with('\r') {
                end = Some(idx);
                break;
            }
        }
        idx += 1;
    }
    let end = end?;
    let (front, body_with_fence) = rest.split_at(end);
    // 剥离收尾分隔符 + 紧随其后的换行（兼容 \r\n 与 \n）
    let body = body_with_fence
        .strip_prefix("\r\n---")
        .or_else(|| body_with_fence.strip_prefix("\n---"))
        .unwrap_or(body_with_fence);
    let body = body
        .strip_prefix("\r\n")
        .or_else(|| body.strip_prefix('\n'))
        .unwrap_or(body);
    Some((front, body))
}

pub fn parse_skill(text: &str) -> Result<ParsedSkill, String> {
    let (front, body) = split_front_matter(text).ok_or_else(|| "missing front matter".to_string())?;
    let manifest: SkillManifest = serde_yaml::from_str(front).map_err(|e| e.to_string())?;
    Ok(ParsedSkill { manifest, body: body.to_string() })
}
