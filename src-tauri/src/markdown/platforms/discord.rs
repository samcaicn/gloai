// Copyright (c) 2026 MeeJoy
//

use crate::markdown::ir::{BlockNode, InlineNode, MarkdownDoc};

pub fn render(doc: &MarkdownDoc) -> String {
    let mut out = String::new();
    for b in &doc.blocks {
        match b {
            BlockNode::Heading { level, inlines } => {
                out.push_str(&"#".repeat(*level as usize));
                out.push(' ');
                push_inlines(&mut out, inlines);
                out.push('\n');
            }
            BlockNode::Paragraph { inlines } => { push_inlines(&mut out, inlines); out.push('\n'); }
            BlockNode::CodeBlock { language, content } => {
                out.push_str("```");
                if let Some(l) = language { out.push_str(l); }
                out.push('\n');
                out.push_str(content);
                out.push_str("```\n");
            }
            BlockNode::List { ordered, items } => {
                for (i, it) in items.iter().enumerate() {
                    if *ordered { out.push_str(&format!("{}. ", i + 1)); } else { out.push_str("- "); }
                    push_inlines(&mut out, it);
                    out.push('\n');
                }
            }
            BlockNode::Quote { inlines } => {
                let mut tmp = String::new();
                push_inlines(&mut tmp, inlines);
                for line in tmp.lines() { out.push_str("> "); out.push_str(line); out.push('\n'); }
            }
            BlockNode::Divider => out.push_str("___\n"),
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
            InlineNode::Text { value } | InlineNode::Bold { value } | InlineNode::Italic { value } | InlineNode::Code { value } => out.push_str(value),
            InlineNode::Link { text, href } => { out.push('['); out.push_str(text); out.push_str("]("); out.push_str(href); out.push(')'); }
            InlineNode::Image { alt, src } => { out.push_str("!["); out.push_str(alt); out.push_str("]("); out.push_str(src); out.push(')'); }
            InlineNode::Break => out.push('\n'),
        }
    }
}
