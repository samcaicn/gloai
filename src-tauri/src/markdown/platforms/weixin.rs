// Copyright (c) 2026 MeeJoy
//

use crate::markdown::ir::{BlockNode, InlineNode, MarkdownDoc};
use crate::markdown::escape::escape_html;

pub fn render(doc: &MarkdownDoc) -> String {
    let mut out = String::new();
    for b in &doc.blocks {
        match b {
            BlockNode::Heading { level, inlines } => {
                out.push_str(&format!("<h{}>", level));
                push_inlines(&mut out, inlines);
                out.push_str(&format!("</h{}>", level));
            }
            BlockNode::Paragraph { inlines } => { out.push_str("<p>"); push_inlines(&mut out, inlines); out.push_str("</p>"); }
            BlockNode::CodeBlock { content, .. } => { out.push_str("<pre>"); out.push_str(&escape_html(content)); out.push_str("</pre>"); }
            BlockNode::List { ordered, items } => {
                if *ordered { out.push_str("<ol>"); } else { out.push_str("<ul>"); }
                for it in items { out.push_str("<li>"); push_inlines(&mut out, it); out.push_str("</li>"); }
                if *ordered { out.push_str("</ol>"); } else { out.push_str("</ul>"); }
            }
            BlockNode::Quote { inlines } => { out.push_str("<blockquote>"); push_inlines(&mut out, inlines); out.push_str("</blockquote>"); }
            BlockNode::Divider => { out.push_str("<hr/>"); }
            BlockNode::Table { headers, rows } => {
                out.push_str("<table><thead><tr>");
                for h in headers { out.push_str("<th>"); out.push_str(&escape_html(h)); out.push_str("</th>"); }
                out.push_str("</tr></thead><tbody>");
                for r in rows {
                    out.push_str("<tr>");
                    for c in r { out.push_str("<td>"); out.push_str(&escape_html(c)); out.push_str("</td>"); }
                    out.push_str("</tr>");
                }
                out.push_str("</tbody></table>");
            }
            BlockNode::Unknown { raw } => { out.push_str(&escape_html(raw)); }
        }
    }
    out
}

fn push_inlines(out: &mut String, inlines: &[InlineNode]) {
    for i in inlines {
        match i {
            InlineNode::Text { value } => out.push_str(&escape_html(value)),
            InlineNode::Bold { value } => { out.push_str("<strong>"); out.push_str(&escape_html(value)); out.push_str("</strong>"); }
            InlineNode::Italic { value } => { out.push_str("<em>"); out.push_str(&escape_html(value)); out.push_str("</em>"); }
            InlineNode::Code { value } => { out.push_str("<code>"); out.push_str(&escape_html(value)); out.push_str("</code>"); }
            InlineNode::Link { text, href } => { out.push_str(&format!("<a href=\"{}\">{}</a>", escape_html(href), escape_html(text))); }
            InlineNode::Image { src, alt } => { out.push_str(&format!("<img src=\"{}\" alt=\"{}\"/>", escape_html(src), escape_html(alt))); }
            InlineNode::Break => out.push_str("<br/>"),
        }
    }
}
