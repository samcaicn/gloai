// Copyright (c) 2026 AIMarketing
//
// v5 broker / finance app registry. Pure `static` data — no
// `lazy_static`, no `OnceCell`, no runtime config. Adding a new
// profile is a code change; the router looks each one up by id
// through `find_profile`.
//
// The renderer type drives which tier the router tries first:
//   * `Mfc` / `SelfDraw`  → UIA-first (UIA still wins for
//                            a well-written MFC control set; OCR
//                            is the final fallback for self-drawn).
//   * `Electron`          → CDP-first (UIA still works for some
//                            controls, but DOM is the source of
//                            truth).
//   * `Web`               → CDP-only (no UIA tree).

use crate::pc_automation::apps::types::{
    AppProfile, OcrAnchorPreset, RendererType, RoutePreference,
};
use crate::pc_automation::ocr::types::OcrRegion;

/// 同花顺经典版 (THS Classic) — MFC, UIA is reasonably complete.
pub static THS_CLASSIC: AppProfile = AppProfile {
    id: "ths_classic",
    display_name: "同花顺经典版",
    renderer: RendererType::Mfc,
    preferred_route: RoutePreference::UiaFirst,
    uia_class_roots: &["Afx:","#32770"],
    cdp_attach_url: None,
    ocr_anchors: &[],
};

/// 同花顺 iFinD / 核新 — Electron host, CDP-first.
pub static THS_HEXIN: AppProfile = AppProfile {
    id: "ths_hexin",
    display_name: "同花顺 iFinD",
    renderer: RendererType::Electron,
    preferred_route: RoutePreference::CdpFirst,
    uia_class_roots: &["Chrome_WidgetWin_0","Chrome_RenderWidgetHostHWND"],
    cdp_attach_url: None,
    ocr_anchors: &[],
};

/// 通达信 — MFC, fairly stable UIA tree on the main quote grid.
pub static TDX: AppProfile = AppProfile {
    id: "tdx",
    display_name: "通达信",
    renderer: RendererType::Mfc,
    preferred_route: RoutePreference::UiaFirst,
    uia_class_roots: &["TdxW_Main","Afx:"],
    cdp_attach_url: None,
    ocr_anchors: &[],
};

/// 大智慧 — MFC, UIA tree is patchy, OCR fallback configured.
pub static DZH: AppProfile = AppProfile {
    id: "dzh",
    display_name: "大智慧",
    renderer: RendererType::Mfc,
    preferred_route: RoutePreference::UiaFirst,
    uia_class_roots: &["DzhMainFrame","Afx:"],
    cdp_attach_url: None,
    ocr_anchors: &[OcrAnchorPreset {
        name: "buy_button",
        region: OcrRegion { x: 0, y: 0, w: 0, h: 0 },
        match_text: "买  入",
    }],
};

/// 东方财富 (Eastmoney) — Electron / CEF.
pub static EASTMONEY: AppProfile = AppProfile {
    id: "eastmoney",
    display_name: "东方财富",
    renderer: RendererType::Electron,
    preferred_route: RoutePreference::CdpFirst,
    uia_class_roots: &["Chrome_WidgetWin_0","Chrome_RenderWidgetHostHWND"],
    cdp_attach_url: Some("https://emweb.eastmoney.com/"),
    ocr_anchors: &[],
};

/// 华泰证券 — self-drawn (GDI), UIA tree is empty, OCR is
/// the only practical tier.
pub static HUATAI: AppProfile = AppProfile {
    id: "huatai",
    display_name: "华泰证券",
    renderer: RendererType::SelfDraw,
    preferred_route: RoutePreference::OcrFirst,
    uia_class_roots: &[],
    cdp_attach_url: None,
    ocr_anchors: &[],
};

/// 雪球 — pure web, CDP only.
pub static XUEQIU: AppProfile = AppProfile {
    id: "xueqiu",
    display_name: "雪球",
    renderer: RendererType::Web,
    preferred_route: RoutePreference::CdpFirst,
    uia_class_roots: &[],
    cdp_attach_url: Some("https://xueqiu.com/"),
    ocr_anchors: &[],
};

/// 富途 (Moomoo) — Electron host.
pub static MOOMOO: AppProfile = AppProfile {
    id: "moomoo",
    display_name: "富途牛牛",
    renderer: RendererType::Electron,
    preferred_route: RoutePreference::CdpFirst,
    uia_class_roots: &["Chrome_WidgetWin_0","Chrome_RenderWidgetHostHWND"],
    cdp_attach_url: None,
    ocr_anchors: &[],
};

/// Static array so `ALL_PROFILES.len()` is a `const`. Iteration
/// order matches the "most popular first" expectation.
pub static ALL_PROFILES: &[&AppProfile] = &[
    &THS_HEXIN,
    &THS_CLASSIC,
    &EASTMONEY,
    &HUATAI,
    &XUEQIU,
    &MOOMOO,
    &TDX,
    &DZH,
];

/// O(log n) lookup by id via simple linear scan. The registry is
/// small enough (8 entries today) that a binary search would be
/// premature; the linear scan keeps the symbol footprint low and
/// gives the linker one less thing to special-case.
pub fn find_profile(id: &str) -> Option<&'static AppProfile> {
    ALL_PROFILES.iter().copied().find(|p| p.id == id)
}
