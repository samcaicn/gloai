// Copyright (c) 2026 MeeJoy
//
// Runtime brand/product info for frontend — replaces hard-coded VITE_APP_*.

use serde::{Deserialize, Serialize};

/// Product branding info exposed to the frontend at runtime.
/// This allows the same binary to report different names/icons
/// depending on the tauri.conf.json overlay (tupai vs safeopc).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BrandInfo {
    /// Product name shown in UI (e.g., "tupai", "safeopc")
    pub product_name: String,
    /// Unique identifier (e.g., "ai.tupai.desktop", "com.safeopc.desktop")
    pub identifier: String,
    /// Version from Cargo.toml / tauri.conf.json
    pub version: String,
    /// Publisher name for dialogs/about
    pub publisher: String,
    /// Short description for tooltips
    pub short_description: String,
    /// Homepage URL
    pub homepage: String,
    /// Deep-link scheme (e.g., "tupai", "safeopc")
    pub deep_link_scheme: String,
    /// Whether this is an OEM/safeopc build
    pub is_oem: bool,
}

/// Returns runtime brand info so the frontend can adapt UI text/logos
/// without relying on build-time env vars.
#[tauri::command]
pub fn get_brand_info() -> BrandInfo {
    // These values are injected at compile time from tauri.conf.json
    // via the Tauri build process. They reflect the active brand overlay.
    let product_name = env!("CARGO_PKG_NAME").to_string();
    let version = env!("CARGO_PKG_VERSION").to_string();

    // Detect OEM mode by checking if we're in safeopc config
    // The identifier is set in tauri.*.conf.json and passed via cfg
    let (identifier, publisher, short_description, homepage, deep_link_scheme, is_oem) =
        if cfg!(feature = "safeopc-brand") {
            (
                "com.safeopc.desktop".to_string(),
                "SafeOPC".to_string(),
                "SafeOPC - Industrial-Grade AI Desktop Workspace".to_string(),
                "https://safeopc.example.com".to_string(),
                "safeopc".to_string(),
                true,
            )
        } else {
            (
                "ai.tupai.desktop".to_string(),
                "tupAI".to_string(),
                "tupAI - Self-Evolving AI Workspace".to_string(),
                "https://tuptup.top".to_string(),
                "tupai".to_string(),
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
