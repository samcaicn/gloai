// Copyright (c) 2026 MeeJoy
//

use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
#[serde(rename_all = "snake_case", tag = "type")]
pub enum InlineNode {
    Text { value: String },
    Bold { value: String },
    Italic { value: String },
    Code { value: String },
    Link { text: String, href: String },
    Image { alt: String, src: String },
    Break,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
#[serde(rename_all = "snake_case", tag = "kind")]
pub enum BlockNode {
    Heading { level: u8, inlines: Vec<InlineNode> },
    Paragraph { inlines: Vec<InlineNode> },
    CodeBlock { language: Option<String>, content: String },
    List { ordered: bool, items: Vec<Vec<InlineNode>> },
    Quote { inlines: Vec<InlineNode> },
    Divider,
    Table { headers: Vec<String>, rows: Vec<Vec<String>> },
    Unknown { raw: String },
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct MarkdownDoc {
    pub blocks: Vec<BlockNode>,
}

impl MarkdownDoc {
    pub fn plain_text(&self) -> String {
        let mut out = String::new();
        for b in &self.blocks {
            match b {
                BlockNode::Heading { inlines, .. } | BlockNode::Paragraph { inlines } | BlockNode::Quote { inlines } => {
                    for i in inlines {
                        if let InlineNode::Text { value } | InlineNode::Bold { value } | InlineNode::Italic { value } | InlineNode::Code { value } = i {
                            out.push_str(value);
                        }
                    }
                    out.push('\n');
                }
                BlockNode::CodeBlock { content, .. } => { out.push_str(content); out.push('\n'); }
                BlockNode::List { items, .. } => { for it in items { for i in it { if let InlineNode::Text { value } = i { out.push_str(value); } } out.push('\n'); } }
                BlockNode::Table { rows, .. } => { for r in rows { out.push_str(&r.join(" | ")); out.push('\n'); } }
                _ => {}
            }
        }
        out
    }
}

/// Parse a small subset of Markdown into a `MarkdownDoc`. The parser
/// is intentionally minimal — it covers headings, paragraphs, code
/// fences, lists, blockquotes, dividers, and tables.
pub fn parse(markdown: &str) -> MarkdownDoc {
    let mut doc = MarkdownDoc::default();
    let mut lines = markdown.lines().peekable();
    while let Some(line) = lines.next() {
        let trimmed = line.trim_end();
        if trimmed.is_empty() { continue; }
        if let Some(rest) = trimmed.strip_prefix("```") {
            let language = rest.trim().to_string();
            let language = if language.is_empty() { None } else { Some(language) };
            let mut buf = String::new();
            // Defensive cap so an unterminated fence can't drag the
            // parser through the rest of the document. 10k lines is
            // well above any legitimate fenced block in our corpus.
            const MAX_FENCE_LINES: usize = 10_000;
            let mut lines_consumed: usize = 0;
            while let Some(next) = lines.peek() {
                if lines_consumed >= MAX_FENCE_LINES {
                    log::warn!("markdown: unterminated code fence exceeded {} lines; aborting block", MAX_FENCE_LINES);
                    break;
                }
                if next.trim_end().starts_with("```") { let _ = lines.next(); break; }
                buf.push_str(next); buf.push('\n');
                let _ = lines.next();
                lines_consumed += 1;
            }
            doc.blocks.push(BlockNode::CodeBlock { language, content: buf });
            continue;
        }
        if trimmed.starts_with('#') {
            let level = trimmed.chars().take_while(|c| *c == '#').count() as u8;
            let text = trimmed[level as usize..].trim().to_string();
            doc.blocks.push(BlockNode::Heading { level: level.clamp(1, 6), inlines: vec![InlineNode::Text { value: text }] });
            continue;
        }
        if trimmed.starts_with("---") || trimmed.starts_with("***") {
            doc.blocks.push(BlockNode::Divider);
            continue;
        }
        if let Some(rest) = trimmed.strip_prefix("> ") {
            doc.blocks.push(BlockNode::Quote { inlines: vec![InlineNode::Text { value: rest.to_string() }] });
            continue;
        }
        if trimmed.starts_with("- ") || trimmed.starts_with("* ") {
            let mut items: Vec<Vec<InlineNode>> = Vec::new();
            while let Some(next) = lines.peek() {
                if !(next.trim_start().starts_with("- ") || next.trim_start().starts_with("* ")) { break; }
                let raw = lines.next().unwrap();
                let text = raw.trim_start().trim_start_matches(['-', '*', ' ']).to_string();
                items.push(vec![InlineNode::Text { value: text }]);
            }
            doc.blocks.push(BlockNode::List { ordered: false, items });
            continue;
        }
        if trimmed.starts_with("|") {
            // crude table detection: gather lines starting with `|`
            let mut rows: Vec<Vec<String>> = Vec::new();
            while let Some(next) = lines.peek() {
                if !next.trim_start().starts_with('|') { break; }
                let line = lines.next().unwrap();
                let cells: Vec<String> = line.trim().trim_matches('|').split('|').map(|c| c.trim().to_string()).collect();
                rows.push(cells);
            }
            if !rows.is_empty() {
                let headers = rows.remove(0);
                // Filter out the `--- | ---` separator row if present.
                rows.retain(|r| !r.iter().all(|c| c.chars().all(|ch| ch == '-' || ch == ':')));
                doc.blocks.push(BlockNode::Table { headers, rows });
            }
            continue;
        }
        doc.blocks.push(BlockNode::Paragraph { inlines: vec![InlineNode::Text { value: trimmed.to_string() }] });
    }
    doc
}
