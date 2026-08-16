
//
// Installer logic. The TypeScript module was responsible for
// downloading a hermes agent binary from GitHub releases and
// unpacking it into the user data dir. The Rust port exposes the
// same DTOs; the actual download / extract is implemented by the
// main thread using `ureq` or `reqwest::Client`.

use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct InstallTarget {
    pub version: String,
    pub asset_url: String,
    pub sha256: String,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct InstallResult {
    pub success: bool,
    pub path: Option<String>,
    pub error: Option<String>,
}

pub fn parse_release_tag(tag: &str) -> Option<String> {
    tag.trim_start_matches('v').split('+').next().map(String::from)
}
