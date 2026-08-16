
//
// Built-in theme catalog. The TypeScript module exposed a `THEMES`
// constant listing the themes bundled with hermes-slate-desk. The
// Rust port keeps the same data, but since `ThemeEntry::id` /
// `name` / `accent` are `String` (so the front-end can extend them
// at runtime via the config), we can't materialise the catalog in a
// `const` (string allocation is not a const operation). Use
// `once_cell::sync::Lazy` to expose the same constant pointer.

use once_cell::sync::Lazy;
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct ThemeEntry {
    pub id: String,
    pub name: String,
    pub accent: String,
    pub mode: ThemeMode,
}

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ThemeMode {
    Light,
    Dark,
    Auto,
}

pub static THEMES: Lazy<Vec<ThemeEntry>> = Lazy::new(|| {
    vec![
        ThemeEntry { id: "hermes-light".to_string(), name: "Hermes Light".to_string(), accent: "#0a84ff".to_string(), mode: ThemeMode::Light },
        ThemeEntry { id: "hermes-dark".to_string(), name: "Hermes Dark".to_string(), accent: "#5e5ce6".to_string(), mode: ThemeMode::Dark },
        ThemeEntry { id: "hermes-auto".to_string(), name: "System".to_string(), accent: "#34c759".to_string(), mode: ThemeMode::Auto },
        ThemeEntry { id: "tupai-aurora".to_string(), name: "tupAI Aurora".to_string(), accent: "#bf5af2".to_string(), mode: ThemeMode::Dark },
        ThemeEntry { id: "tupai-paper".to_string(), name: "tupAI Paper".to_string(), accent: "#ff9f0a".to_string(), mode: ThemeMode::Light },
    ]
});

pub fn find(id: &str) -> Option<&'static ThemeEntry> {
    THEMES.iter().find(|t| t.id == id)
}
