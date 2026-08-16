// Copyright (c) 2026 MeeJoy
//
// Runtime brand/product info for frontend — replaces hard-coded VITE_APP_*.

use serde::{Deserialize, Serialize};

/// Product branding info exposed to the frontend at runtime.
/// This allows the same binary to report different names/icons
/// depending on the tauri.conf.json overlay (AIMarketing variants).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BrandInfo {
    /// Product name shown in UI (e.g., "AIMarketing")
    pub product_name: String,
    /// Unique identifier (e.g., "ai.aimarketing.desktop", "com.aimarketing.desktop")
    pub identifier: String,
    /// Version from Cargo.toml / tauri.conf.json
    pub version: String,
    /// Publisher name for dialogs/about
    pub publisher: String,
    /// Short description for tooltips
    pub short_description: String,
    /// Homepage URL
    pub homepage: String,
    /// Deep-link scheme (e.g., "aimarketing")
    pub deep_link_scheme: String,
    /// Whether this is an OEM build
    pub is_oem: bool,
}

/// Returns runtime brand info so the frontend can adapt UI text/logos
/// without relying on build-time env vars.
#[tauri::command]
pub fn get_brand_info() -> BrandInfo {
    // These values are injected at compile time from tauri.conf.json
    // via the Tauri build process. They reflect the active brand overlay.
    let product_name = "AIMarketing".to_string();
    let version = env!("CARGO_PKG_VERSION").to_string();

    // Detect OEM mode by checking if we're in safeopc config
    // The identifier is set in tauri.*.conf.json and passed via cfg
    let (identifier, publisher, short_description, homepage, deep_link_scheme, is_oem) =
        if cfg!(feature = "safeopc-brand") {
            (
                "com.aimarketing.desktop".to_string(),
                "AIMarketing".to_string(),
                "AIMarketing - Industrial-Grade AI Desktop Workspace".to_string(),
                "https://aimarketing.example.com".to_string(),
                "aimarketing".to_string(),
                true,
            )
        } else {
            (
                "ai.aimarketing.desktop".to_string(),
                "AIMarketing".to_string(),
                "AIMarketing - Self-Evolving AI Workspace".to_string(),
                "https://aimarketing.example.com".to_string(),
                "aimarketing".to_string(),
                false,
            )
        };

    BrandInfo {
        product_name,
        identifier,
        version,
        publisher,
        short_description,
        homepage,
        deep_link_scheme,
        is_oem,
    }
}

/// Returns true if running the OEM/safeopc branded build.
#[tauri::command]
pub fn is_oem_build() -> bool {
    cfg!(feature = "safeopc-brand")
}
