// Copyright (c) 2026 AIMarketing
//
// App-profile types. All fields are `&'static str` / `&'static
// [...]` so the entire registry is a literal — no `String`
// allocation, no `OnceCell`, no `lazy_static`. This is fine
// because the set of supported Chinese brokers is small and
// fixed; adding a new one is a code change, not a runtime
// config knob.

use serde::{Deserialize, Serialize};

use crate::pc_automation::ocr::types::OcrRegion;

/// Renderer classification. Drives both which tier the router
/// tries first AND how the UIA/CDP health probes are dispatched.
#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum RendererType {
    /// MFC / Win32 native (同花顺经典版, 通达信, 大智慧).
    Mfc,
    /// Electron / CEF host (同花顺 iFinD, 富途, Choice).
    Electron,
    /// Browser-rendered web app (雪球).
    Web,
    /// Self-drawn GDI / Direct2D (华泰, 中信建投, 平安).
    SelfDraw,
}

/// Which tier the router should hit first when this profile is
/// active. Default ladder is CDP → UIA → OCR (CDP first; UIA
/// second for native controls; OCR as the cross-domain fallback);
/// the preference just changes the primary tier.
#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum RoutePreference {
    UiaFirst,
    CdpFirst,
    OcrFirst,
}

/// A pre-located OCR anchor. Bundled into the profile so a
/// recorder or recipe can say "click the 持仓 tab in 大智慧" by
/// name without re-specifying the bounding box.
#[derive(Serialize, Debug, Clone, Copy)]
#[serde(rename_all = "camelCase")]
pub struct OcrAnchorPreset {
    pub name: &'static str,
    pub region: OcrRegion,
    pub match_text: &'static str,
}

/// One trading / finance app. Held in `static` storage so the
/// router and the recorder can both `&'static` it.
///
/// `Deserialize` is deliberately omitted: the fields use
/// `&'static` slices that serde cannot materialize from JSON,
/// and the registry is hardcoded by design (adding a new app is
/// a code change, not a runtime config knob). If a future need
/// loads profiles from disk, switch these to `String` / `Vec<_>`
/// and re-derive.
#[derive(Serialize, Debug, Clone, Copy)]
#[serde(rename_all = "camelCase")]
pub struct AppProfile {
    pub id: &'static str,
    pub display_name: &'static str,
    pub renderer: RendererType,
    pub preferred_route: RoutePreference,
    /// Substrings the focus-tracker uses to attribute a UIA root
    /// to this profile (window class names, process names, etc.).
    pub uia_class_roots: &'static [&'static str],
    /// If the renderer is Electron / Web, this is the URL the
    /// CDP backend attaches to. `None` for native apps.
    pub cdp_attach_url: Option<&'static str>,
    pub ocr_anchors: &'static [OcrAnchorPreset],
}

/// Look up a profile by its id (e.g. `"ths_classic"`). The
/// implementation lives in `profiles.rs` so this file stays
/// type-only.
pub fn find_profile(id: &str) -> Option<&'static AppProfile> {
    crate::pc_automation::apps::profiles::find_profile(id)
}
