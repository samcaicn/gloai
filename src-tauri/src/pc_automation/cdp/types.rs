// Copyright (c) 2026 AIMarketing
//
// CDP selector / action vocabulary. Mirrors the small subset of the
// DevTools Protocol that the v5 router needs — anything more
// complicated (network interception, frame management) belongs in
// the dedicated `agent_browser` package, not here.

use serde::{Deserialize, Serialize};

use crate::pc_automation::parse_error::ParseError;

/// Mouse button. Mirrored to keep the IPC payload platform-neutral
/// (a `u32` would force the front-end to remember the encoding).
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum CdpMouseButton {
    Left,
    Right,
    Middle,
}

/// How to find a DOM node. All fields are optional; the resolver
/// applies them as a conjunction (logical AND).
#[derive(Serialize, Deserialize, Debug, Clone, Default, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct CdpSelector {
    /// Glob the resolver runs against `document.location.href` to
    /// make sure the action lands on the expected page / webview.
    pub page_url_glob: Option<String>,
    pub css: Option<String>,
    pub xpath: Option<String>,
    pub text: Option<String>,
}

/// One CDP action the backend can dispatch. `Wait` is its own
/// variant so the router can fail fast on a slow target rather than
/// blocking inside an `Evaluate`.
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum CdpAction {
    Navigate(String),
    Click { sel: CdpSelector, button: CdpMouseButton },
    Type { sel: CdpSelector, text: String },
    Wait { sel: CdpSelector, timeout_ms: u64 },
    Evaluate(String),
}

/// Parse a `cdp:` selector literal. The grammar intentionally
/// mirrors the UIA grammar (`key=value;key=value`) so a recipe can
/// mix `uia:...` and `cdp:...` steps without context switching.
pub fn parse_cdp_selector(s: &str) -> Result<CdpSelector, ParseError> {
    const PREFIX: &str = "cdp:";
    let body = s.strip_prefix(PREFIX).ok_or_else(|| {
        ParseError::InvalidPrefix(s.chars().take(4).collect::<String>())
    })?;

    let mut sel = CdpSelector::default();
    if body.is_empty() {
        return Ok(sel);
    }

    for kv in body.split(';') {
        if kv.is_empty() {
            continue;
        }
        let (k, v) = kv.split_once('=').ok_or(ParseError::MissingField("key=value"))?;
        match k.trim() {
            "url" | "pageUrl" | "page_url" => sel.page_url_glob = Some(v.to_string()),
            "css" => sel.css = Some(v.to_string()),
            "xpath" => sel.xpath = Some(v.to_string()),
            "text" => sel.text = Some(v.to_string()),
            _other => {
                return Err(ParseError::MissingField("unknown_field"))
            }
        }
    }
    Ok(sel)
}
