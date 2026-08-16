// Copyright (c) 2026 AIMarketing
//
// UIRPA template rendering.
//
// Renders `{{name}}` placeholders against a `serde_json::Map` of
// parameter values. The grammar is intentionally minimal:
//
//   * `{{name}}`     — replaced by the value of `params["name"]`
//                      (rendered as a string with type-specific
//                       formatting for numbers / booleans / null)
//   * `{{ name }}`   — same as above, whitespace tolerated
//   * `{{name.foo}}` — currently not supported; emits an error so
//                      callers know to switch to a single-level
//                      lookup. The schema is kept flat to avoid
//                      the JS-side `Proxy` boilerplate.
//   * unrecognised
//     `{{ ... }}`    — also an error: silently substituting ""
//                      hides typos and produces confusing
//                      locator strings downstream.
//
// Everything outside the `{{ ... }}` pair is passed through
// verbatim, so a template that contains no placeholders is
// returned unchanged.

use serde_json::{Map, Value};

/// Render `{{name}}` placeholders in `template` against `params`.
/// Returns the rendered string, or an error if a placeholder
/// references a missing / dotted / unknown parameter.
pub fn render_template(
    template: &str,
    params: &Map<String, Value>,
) -> Result<String, String> {
    let mut out = String::with_capacity(template.len());
    let bytes = template.as_bytes();
    let mut i = 0usize;
    while i < bytes.len() {
        // Look for the next "{{" run.
        if i + 1 < bytes.len() && bytes[i] == b'{' && bytes[i + 1] == b'{' {
            // Find the matching "}}".
            let close = find_close(&bytes[i + 2..])
                .ok_or_else(|| format!("template: unterminated '{{{{' starting at byte {}", i))?;
            let name_raw = &template[i + 2..i + 2 + close];
            let name = name_raw.trim();
            if name.is_empty() {
                return Err(format!(
                    "template: empty placeholder at byte {}",
                    i
                ));
            }
            if name.contains('.') {
                return Err(format!(
                    "template: dotted placeholder '{{{{{name}}}}}' is not supported in v1",
                ));
            }
            let value = params
                .get(name)
                .ok_or_else(|| {
                    format!(
                        "template: missing parameter '{}' (placeholder at byte {})",
                        name, i
                    )
                })?;
            out.push_str(&value_to_string(value));
            i += 2 + close + 2; // skip past "}}"
        } else {
            // Push the byte as a char. We work on bytes to keep
            // the inner loop branch-light; multi-byte UTF-8 will
            // still be valid once we extend `out` byte-by-byte
            // because we're copying the original byte sequence.
            let ch = template[i..].chars().next().unwrap();
            out.push(ch);
            i += ch.len_utf8();
        }
    }
    Ok(out)
}

/// Find the byte index of the closing `}}` inside `s` (relative
/// to `s`). Returns `None` if there is no closing `}}` before the
/// end of `s`.
fn find_close(s: &[u8]) -> Option<usize> {
    let mut i = 0;
    while i + 1 < s.len() {
        if s[i] == b'}' && s[i + 1] == b'}' {
            return Some(i);
        }
        // Walk one full UTF-8 codepoint so we don't match
        // `}}` that crosses a codepoint boundary. `chars()` is
        // defined on `&str`; for a byte slice we have to round
        // trip through `std::str::from_utf8` (unchecked is not
        // safe because we are scanning from an arbitrary
        // byte index, not the start). The caller has already
        // guaranteed the input is a valid `&str` because it
        // was sliced from a `&str`.
        let ch_len = std::str::from_utf8(&s[i..])
            .ok()?
            .chars()
            .next()?
            .len_utf8();
        i += ch_len;
    }
    None
}

/// Render a single `serde_json::Value` as a string. Numbers are
/// rendered via their `Display`, booleans as `true` / `false`,
/// `Null` as the empty string. Strings are returned as-is
/// (without the surrounding quotes).
fn value_to_string(value: &Value) -> String {
    match value {
        Value::Null => String::new(),
        Value::Bool(b) => b.to_string(),
        Value::Number(n) => n.to_string(),
        Value::String(s) => s.clone(),
        // Arrays / objects are not legal template values in v1
        // (we banned dotted lookup); render the JSON form so the
        // user at least sees something debuggable.
        other => other.to_string(),
    }
}
