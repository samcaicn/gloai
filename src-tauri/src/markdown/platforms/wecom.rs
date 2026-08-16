// Copyright (c) 2026 MeeJoy
//

use crate::markdown::ir::{BlockNode, InlineNode, MarkdownDoc};

pub fn render(doc: &MarkdownDoc) -> String {
    let mut out = String::new();
    for b in &doc.blocks {
        match b {
            BlockNode::Heading { inlines, .. } => {
                out.push_str("**");
                push_inlines(&mut out, inlines);
                out.push_str("**\n");
            }
            BlockNode::Paragraph { inlines } => { push_inlines(&mut out, inlines); out.push('\n'); }
            BlockNode::CodeBlock { content, .. } => { out.push_str(content); out.push('\n'); }
            BlockNode::List { items, .. } => { for it in items { push_inlines(&mut out, it); out.push('\n'); } }
            BlockNode::Quote { inlines } => { push_inlines(&mut out, inlines); out.push('\n'); }
            BlockNode::Divider => { out.push_str("---\n"); }
            BlockNode::Table { headers, rows } => {
                for r in rows { out.push_str(&r.join(" | ")); out.push('\n'); }
                let _ = headers;
            }
            BlockNode::Unknown { raw } => { out.push_str(raw); out.push('\n'); }
        }
    }
    out
}

fn push_inlines(out: &mut String, inlines: &[InlineNode]) {
    for i in inlines {
        match i {
            InlineNode::Text { value } | InlineNode::Bold { value } | InlineNode::Italic { value } | InlineNode::Code { value } => out.push_str(value),
            InlineNode::Link { text, href } => { out.push_str(text); out.push_str(" ("); out.push_str(href); out.push(')'); }
            InlineNode::Image { .. } => {}
            InlineNode::Break => out.push('\n'),
        }
    }
}
