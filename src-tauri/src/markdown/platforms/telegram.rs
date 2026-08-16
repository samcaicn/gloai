// Copyright (c) 2026 MeeJoy
//

use crate::markdown::ir::{BlockNode, InlineNode, MarkdownDoc};

pub fn render(doc: &MarkdownDoc) -> String {
    let mut out = String::new();
    for b in &doc.blocks {
        match b {
            BlockNode::Heading { inlines, .. } => { push_inlines(&mut out, inlines); out.push('\n'); }
            BlockNode::Paragraph { inlines } => { push_inlines(&mut out, inlines); out.push('\n'); }
            BlockNode::CodeBlock { language, content } => {
                out.push_str("```");
                if let Some(l) = language { out.push_str(l); }
                out.push('\n');
                out.push_str(content);
                out.push_str("```\n");
            }
            BlockNode::List { items, .. } => { for it in items { push_inlines(&mut out, it); out.push('\n'); } }
            BlockNode::Quote { inlines } => { push_inlines(&mut out, inlines); out.push('\n'); }
            BlockNode::Divider => { out.push_str("---\n"); }
            BlockNode::Table { headers, rows } => {
                out.push_str("```\n");
                out.push_str(&headers.join(" | "));
                out.push('\n');
                out.push_str(&headers.iter().map(|_| "---".to_string()).collect::<Vec<_>>().join(" | "));
                out.push('\n');
                for r in rows { out.push_str(&r.join(" | ")); out.push('\n'); }
                out.push_str("```\n");
            }
            BlockNode::Unknown { raw } => { out.push_str(raw); out.push('\n'); }
        }
    }
    out
}

fn push_inlines(out: &mut String, inlines: &[InlineNode]) {
    for i in inlines {
        match i {
            InlineNode::Text { value } => out.push_str(value),
            InlineNode::Bold { value } => { out.push('*'); out.push_str(value); out.push('*'); }
            InlineNode::Italic { value } => { out.push('_'); out.push_str(value); out.push('_'); }
            InlineNode::Code { value } => { out.push('`'); out.push_str(value); out.push('`'); }
            InlineNode::Link { text, href } => { out.push('['); out.push_str(text); out.push_str("]("); out.push_str(href); out.push(')'); }
            InlineNode::Image { .. } => {}
            InlineNode::Break => out.push('\n'),
        }
    }
}
