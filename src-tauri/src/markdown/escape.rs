// Copyright (c) 2026 MeeJoy
//

pub fn escape_markdown(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for ch in text.chars() {
        if matches!(ch, '\\' | '`' | '*' | '_' | '{' | '}' | '[' | ']' | '(' | ')' | '#' | '+' | '-' | '.' | '!' | '|') {
            out.push('\\');
        }
        out.push(ch);
    }
    out
}

pub fn escape_html(text: &str) -> String {
    text.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&#39;")
}

pub fn escape_mrkdwn(text: &str) -> String {
    // Telegram MRKDWN only requires escaping a small set inside
    // `*bold*`, `_italic_`, `[link](url)`.
    text.replace('*', r"\*").replace('_', r"\_").replace('`', r"\`").replace('[', r"\[")
}
