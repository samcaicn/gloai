
//
// Render a `MarkdownDoc` to the IM-flavored Markdown dialect of a
// given platform. The TypeScript module selected a `PlatformAdapter`
// and called `adapter.render(doc)`. The Rust port mirrors that with
// a `render_to()` function and a `platforms/` submodule per target.

use super::ir::{BlockNode, InlineNode, MarkdownDoc};

pub fn render_to(markdown: &str, platform: &str) -> String {
    let doc = super::ir::parse(markdown);
    match platform {
        "discord" => crate::markdown::platforms::discord::render(&doc),
        "feishu" => crate::markdown::platforms::feishu::render(&doc),
        "qq" => crate::markdown::platforms::qq::render(&doc),
        "telegram" => crate::markdown::platforms::telegram::render(&doc),
        "wecom" => crate::markdown::platforms::wecom::render(&doc),
        "weixin" => crate::markdown::platforms::weixin::render(&doc),
        _ => render_generic(&doc),
    }
}

pub fn render_generic(doc: &MarkdownDoc) -> String {
    let mut out = String::new();
    for b in &doc.blocks {
        match b {
            BlockNode::Heading { level, inlines } => {
                out.push_str(&"#".repeat(*level as usize));
                out.push(' ');
                push_inlines(&mut out, inlines);
                out.push('\n');
            }
            BlockNode::Paragraph { inlines } => { push_inlines(&mut out, inlines); out.push_str("\n\n"); }
            BlockNode::CodeBlock { language, content } => {
                out.push_str("```");
                if let Some(l) = language { out.push_str(l); }
                out.push('\n');
                out.push_str(content);
                if !content.ends_with('\n') { out.push('\n'); }
                out.push_str("```\n");
            }
            BlockNode::List { ordered, items } => {
                for (i, it) in items.iter().enumerate() {
                    if *ordered { out.push_str(&format!("{}. ", i + 1)); } else { out.push_str("- "); }
                    push_inlines(&mut out, it);
                    out.push('\n');
                }
                out.push('\n');
            }
            BlockNode::Quote { inlines } => {
                let rendered = {
                    let mut tmp = String::new();
                    push_inlines(&mut tmp, inlines);
                    tmp
                };
                for line in rendered.lines() { out.push_str("> "); out.push_str(line); out.push('\n'); }
                out.push('\n');
            }
            BlockNode::Divider => { out.push_str("---\n\n"); }
            BlockNode::Table { headers, rows } => {
                out.push_str("| "); out.push_str(&headers.join(" | ")); out.push_str(" |\n");
                out.push_str("| "); out.push_str(&headers.iter().map(|_| "---".to_string()).collect::<Vec<_>>().join(" | ")); out.push_str(" |\n");
                for r in rows { out.push_str("| "); out.push_str(&r.join(" | ")); out.push_str(" |\n"); }
                out.push('\n');
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
