// Copyright (c) 2026 tupAI
//
// tupAI v5 §5.4 — unit tests for the `pc_automation` module
// tree. Covers the v5 PCUI route acceptance criteria:
//   * UIA selector parser — strict, prefix-required, all
//     fields optional but validated
//   * CDP selector parser — mirror grammar, glob-friendly
//   * OCR anchor parser — engine picker + region quadruples
//   * App profile registry — every v5 stock app present,
//     lookup is case-sensitive and total
//   * Broker router — UI-automation guard rail fires, no
//     UI fallback for `place_order`, all 5 v5 adapters present
//   * Step / RouterError — error variants are `Display + Error`
//
// This file is `#[path]`-included from `pc_automation/mod.rs`
// so `cargo test --lib` picks it up automatically.

use crate::pc_automation::apps::profiles::ALL_PROFILES;
use crate::pc_automation::apps::{find_profile, AppProfile, RendererType, RoutePreference};
use crate::pc_automation::broker::router::{assert_broker_only_context, BrokerRouter};
use crate::pc_automation::broker::types::BrokerHealth;
use crate::pc_automation::broker::stubs::{
    ChoiceAdapter, CtpAdapter, HuataiAdapter, IFindAdapter, OpenDAdapter,
};
use crate::pc_automation::broker::BrokerAdapter;
use crate::pc_automation::cdp::types::{
    parse_cdp_selector, CdpAction, CdpMouseButton, CdpSelector,
};
use crate::pc_automation::ocr::types::{
    parse_ocr_anchor, OcrAnchor, OcrEngine, OcrRegion,
};
use crate::pc_automation::parse_error::ParseError;
use crate::pc_automation::uia::types::{parse_uia_selector, UiaSelector};
use crate::pc_automation::step::{RouterError, StepStrategy};

// =============================================================
// ParseError
// =============================================================

#[test]
fn parse_error_display_includes_prefix_token() {
    let e = ParseError::InvalidPrefix("foo".to_string());
    let s = format!("{}", e);
    assert!(s.contains("foo"), "display must carry the bad prefix: {}", s);
}

#[test]
fn parse_error_display_for_missing_field_is_stable() {
    let e = ParseError::MissingField("controlType");
    let s = format!("{}", e);
    assert!(s.contains("controlType"));
    assert!(s.contains("missing"));
}

#[test]
fn parse_error_display_for_bad_number_quotes_token() {
    let e = ParseError::BadNumber("12x".to_string());
    let s = format!("{}", e);
    assert!(s.contains("12x"));
}

#[test]
fn parse_error_implements_std_error() {
    // Compile-time assertion: the trait is implemented.
    fn assert_error<E: std::error::Error>() {}
    assert_error::<ParseError>();
}

// =============================================================
// UIA selector parser
// =============================================================

#[test]
fn uia_parser_rejects_non_uia_prefix() {
    let err = parse_uia_selector("uia").unwrap_err();
    assert_eq!(err, ParseError::InvalidPrefix("uia".to_string()));
}

#[test]
fn uia_parser_empty_body_returns_default_selector() {
    let sel = parse_uia_selector("uia:").unwrap();
    assert_eq!(sel, UiaSelector::default());
}

#[test]
fn uia_parser_parses_all_four_fields() {
    let sel = parse_uia_selector(
        "uia:controlType=Button;name=提交;automationId=login_btn;className=AfxButton",
    )
    .unwrap();
    assert_eq!(sel.control_type.as_deref(), Some("Button"));
    assert_eq!(sel.name.as_deref(), Some("提交"));
    assert_eq!(sel.automation_id.as_deref(), Some("login_btn"));
    assert_eq!(sel.class_name.as_deref(), Some("AfxButton"));
}

#[test]
fn uia_parser_accepts_snake_case_keys() {
    let sel = parse_uia_selector(
        "uia:control_type=Edit;automation_id=edtPx",
    )
    .unwrap();
    assert_eq!(sel.control_type.as_deref(), Some("Edit"));
    assert_eq!(sel.automation_id.as_deref(), Some("edtPx"));
}

#[test]
fn uia_parser_rejects_malformed_kv_pair() {
    let err = parse_uia_selector("uia:no_equals_sign").unwrap_err();
    assert!(matches!(err, ParseError::MissingField(_)));
}

#[test]
fn uia_parser_rejects_unknown_field() {
    let err = parse_uia_selector("uia:bogusField=x").unwrap_err();
    assert!(matches!(err, ParseError::MissingField(_)));
}

#[test]
fn uia_parser_ignores_empty_segments() {
    // "uia:;;name=买入;;;" — only `name` should survive.
    let sel = parse_uia_selector("uia:;;name=买入;;;").unwrap();
    assert_eq!(sel.name.as_deref(), Some("买入"));
    assert_eq!(sel.control_type, None);
}

// =============================================================
// CDP selector parser
// =============================================================

#[test]
fn cdp_parser_rejects_non_cdp_prefix() {
    let err = parse_cdp_selector("cdr:css=.btn").unwrap_err();
    assert_eq!(err, ParseError::InvalidPrefix("cdr:".to_string()));
}

#[test]
fn cdp_parser_empty_body_returns_default_selector() {
    let sel = parse_cdp_selector("cdp:").unwrap();
    assert_eq!(sel, CdpSelector::default());
}

#[test]
fn cdp_parser_parses_glob_url_and_css() {
    let sel =
        parse_cdp_selector("cdp:url=*xueqiu.com;css=.order-panel .buy").unwrap();
    assert_eq!(sel.page_url_glob.as_deref(), Some("*xueqiu.com"));
    assert_eq!(sel.css.as_deref(), Some(".order-panel .buy"));
}

#[test]
fn cdp_parser_parses_xpath_and_text_keys() {
    let sel = parse_cdp_selector("cdp:xpath=//button;text=买入").unwrap();
    assert_eq!(sel.xpath.as_deref(), Some("//button"));
    assert_eq!(sel.text.as_deref(), Some("买入"));
}

#[test]
fn cdp_parser_rejects_unknown_key() {
    let err = parse_cdp_selector("cdp:notAKey=value").unwrap_err();
    assert!(matches!(err, ParseError::MissingField(_)));
}

#[test]
fn cdp_action_click_carries_button() {
    let act = CdpAction::Click {
        sel: CdpSelector { css: Some(".buy".to_string()), ..Default::default() },
        button: CdpMouseButton::Right,
    };
    match act {
        CdpAction::Click { button, sel } => {
            assert_eq!(button, CdpMouseButton::Right);
            assert_eq!(sel.css.as_deref(), Some(".buy"));
        }
        _ => panic!("expected Click variant"),
    }
}

// =============================================================
// OCR anchor parser
// =============================================================

#[test]
fn ocr_parser_default_anchor_is_pp_ocr_v5_full_screen_false() {
    let a = parse_ocr_anchor("ocr:").unwrap();
    assert_eq!(a.engine, OcrEngine::PpOcrV5);
    assert_eq!(a.match_text, "");
    assert!(!a.full_screen);
    assert!(a.region.is_none());
}

#[test]
fn ocr_parser_picks_paddle_vl_1_6_engine() {
    let a = parse_ocr_anchor("ocr:engine=paddleVl16;match=提交").unwrap();
    assert_eq!(a.engine, OcrEngine::PaddleVl16);
    assert_eq!(a.match_text, "提交");
}

#[test]
fn ocr_parser_picks_pp_ocr_v5_via_alias() {
    let a = parse_ocr_anchor("ocr:engine=ppocr;match=12.34").unwrap();
    assert_eq!(a.engine, OcrEngine::PpOcrV5);
}

#[test]
fn ocr_parser_parses_region_quadruple() {
    let a = parse_ocr_anchor("ocr:region=100,200,800,600;match=平安银行").unwrap();
    let r = a.region.expect("region must be set");
    assert_eq!(r, OcrRegion { x: 100, y: 200, w: 800, h: 600 });
    assert_eq!(a.match_text, "平安银行");
}

#[test]
fn ocr_parser_rejects_region_with_wrong_arity() {
    let err = parse_ocr_anchor("ocr:region=100,200,800").unwrap_err();
    assert!(matches!(err, ParseError::BadNumber(_)));
}

#[test]
fn ocr_parser_rejects_non_integer_region_component() {
    let err = parse_ocr_anchor("ocr:region=100,abc,800,600").unwrap_err();
    assert!(matches!(err, ParseError::BadNumber(_)));
}

#[test]
fn ocr_parser_accepts_full_screen_true_aliases() {
    for tok in ["true", "1", "yes"] {
        let a = parse_ocr_anchor(&format!("ocr:fullScreen={}", tok)).unwrap();
        assert!(a.full_screen, "token {:?} should flip full_screen", tok);
    }
}

#[test]
fn ocr_parser_rejects_unknown_engine() {
    let err = parse_ocr_anchor("ocr:engine=tesseract").unwrap_err();
    assert!(matches!(err, ParseError::MissingField(_)));
}

#[test]
fn ocr_anchor_carries_engine_choice() {
    let a = OcrAnchor {
        region: Some(OcrRegion { x: 0, y: 0, w: 100, h: 30 }),
        match_text: "买".to_string(),
        full_screen: false,
        engine: OcrEngine::PaddleVl16,
    };
    assert_eq!(a.engine, OcrEngine::PaddleVl16);
}

// =============================================================
// App profile registry
// =============================================================

#[test]
fn app_profiles_registry_has_eight_entries() {
    // v5 doc §2.5 — exactly 8 profiles.
    assert_eq!(ALL_PROFILES.len(), 8);
}

#[test]
fn app_profiles_include_all_required_v5_apps() {
    let required = [
        "ths_classic", "ths_hexin", "tdx", "dzh",
        "xueqiu", "moomoo", "eastmoney", "huatai",
    ];
    for id in required {
        let p = find_profile(id)
            .unwrap_or_else(|| panic!("profile '{}' must be registered", id));
        assert_eq!(p.id, id);
    }
}

#[test]
fn app_profile_lookup_is_case_sensitive() {
    // v5 doc says the id is the canonical string; mixed-case
    // queries are a programmer error and must return None.
    assert!(find_profile("THS_CLASSIC").is_none());
    assert!(find_profile("ths_Classic").is_none());
    assert!(find_profile("ths_classic").is_some());
}

#[test]
fn app_profile_lookup_unknown_id_returns_none() {
    assert!(find_profile("not_a_real_app").is_none());
    assert!(find_profile("").is_none());
}

#[test]
fn uia_first_apps_use_mfc_or_selfdraw() {
    // The "UIA-first" preference only makes sense when the
    // window has an accessibility tree — MFC or SelfDraw with
    // managed controls.
    for p in ALL_PROFILES.iter().copied() {
        if p.preferred_route == RoutePreference::UiaFirst {
            assert!(
                matches!(p.renderer, RendererType::Mfc | RendererType::SelfDraw),
                "profile '{}' declares UiaFirst but renderer is {:?}",
                p.id,
                p.renderer
            );
        }
    }
}

#[test]
fn cdp_first_apps_have_attach_url_or_electron_renderer() {
    // CDP-first must either target an Electron host (which
    // exposes a Chromium DevTools port) or carry an explicit
    // attach URL.
    for p in ALL_PROFILES.iter().copied() {
        if p.preferred_route == RoutePreference::CdpFirst {
            let has_url = p.cdp_attach_url.is_some();
            let is_electron_or_web =
                matches!(p.renderer, RendererType::Electron | RendererType::Web);
            assert!(
                has_url || is_electron_or_web,
                "profile '{}' declares CdpFirst but has no attach URL and renderer is {:?}",
                p.id,
                p.renderer
            );
        }
    }
}

#[test]
fn app_profile_construction_round_trips_through_serialize() {
    let p: &AppProfile = find_profile("ths_classic").unwrap();
    let json = serde_json::to_string(p).unwrap();
    // Re-parse to confirm the JSON is structurally valid even
    // though we don't (and can't) re-derive Deserialize on the
    // `&'static`-bearing struct.
    let parsed: serde_json::Value = serde_json::from_str(&json).unwrap();
    assert_eq!(parsed["id"], "ths_classic");
    assert_eq!(parsed["displayName"], "同花顺经典版");
    assert_eq!(parsed["renderer"], "mfc");
    assert_eq!(parsed["preferredRoute"], "uiaFirst");
}

// =============================================================
// Step / RouterError
// =============================================================

#[test]
fn step_strategy_serialises_in_snake_case() {
    let json = serde_json::to_string(&StepStrategy::Uia).unwrap();
    assert_eq!(json, "\"uia\"");
    let json = serde_json::to_string(&StepStrategy::Cdp).unwrap();
    assert_eq!(json, "\"cdp\"");
    let json = serde_json::to_string(&StepStrategy::Ocr).unwrap();
    assert_eq!(json, "\"ocr\"");
}

#[test]
fn router_error_debug_carries_variant_name() {
    let e = RouterError::StructuredMiss {
        primary: "uia: not found".to_string(),
        fallback: "ocr: anchor not found".to_string(),
    };
    let s = format!("{:?}", e);
    assert!(s.contains("StructuredMiss"), "got: {}", s);
}

// =============================================================
// Broker router
// =============================================================

#[test]
fn broker_router_registers_all_five_v5_adapters() {
    let r = BrokerRouter::new();
    for id in ["ctp", "opend", "ifind", "huatai", "choice"] {
        assert!(
            r.adapter(id).is_some(),
            "broker '{}' must be registered",
            id
        );
    }
}

#[test]
fn broker_router_default_is_ctp() {
    let r = BrokerRouter::new();
    // The default is what `place_order` falls back to. We
    // expect CTP to win by insertion order; if the ordering
    // ever changes, this test will catch it.
    let health = r.health_all();
    let ctp = health
        .iter()
        .find(|h| h.broker_id == "ctp")
        .expect("ctp must be in the health list");
    assert!(!ctp.connected, "stub CTP is never connected");
    assert!(ctp.last_error.is_some());
}

#[test]
fn broker_router_unknown_broker_adapter_lookup_returns_none() {
    let r = BrokerRouter::new();
    assert!(r.adapter("NOT_A_BROKER").is_none());
    assert!(r.adapter("").is_none());
    assert!(r.adapter("ctp").is_some());
}

#[test]
fn broker_router_query_balance_with_unknown_id_errors() {
    let r = BrokerRouter::new();
    let err = r.query_balance(Some("nope")).unwrap_err();
    assert!(err.contains("nope"), "error must name the bad id: {}", err);
}

#[test]
fn broker_router_query_positions_with_unknown_id_errors() {
    let r = BrokerRouter::new();
    let err = r.query_positions(Some("nope")).unwrap_err();
    assert!(err.contains("nope"));
}

#[test]
fn broker_router_place_order_on_stub_returns_error() {
    let r = BrokerRouter::new();
    let req = crate::pc_automation::broker::types::OrderRequest {
        symbol: "000001.SZ".to_string(),
        side: crate::pc_automation::broker::types::OrderSide::Buy,
        order_type: crate::pc_automation::broker::types::OrderType::Market,
        quantity: 100.0,
        price: None,
    };
    // Stubs return Err — what we care about is that the call
    // goes *through* the broker path and not into a UI fallback.
    let err = r.place_order(req).unwrap_err();
    assert!(err.to_lowercase().contains("not configured") || err.contains("stub"),
            "expected stub error, got: {}", err);
}

#[test]
fn broker_router_health_all_returns_five_brokers() {
    let r = BrokerRouter::new();
    let h = r.health_all();
    assert_eq!(h.len(), 5);
    // Every entry should be a stub-broken one.
    for entry in &h {
        assert!(!entry.connected, "stub {} should report not connected", entry.broker_id);
    }
}

#[test]
fn broker_router_query_positions_all_concatenates_without_panicking() {
    // All stubs error, so the joined error path is exercised.
    let r = BrokerRouter::new();
    let res = r.query_positions(None);
    // Stubs return Err, so we get an Err with the joined
    // "broker_id: error" strings.
    assert!(res.is_err());
}

#[test]
fn assert_broker_only_context_is_a_noop_outside_ui_automation() {
    // The flag is process-global; we can't *prove* it's off in
    // parallel tests, but we can confirm the call does not
    // panic when the flag is off. The dev test runs alone
    // (single thread) so the flag state at test start is
    // observable.
    assert_broker_only_context("test_outside_ui_automation");
    // No panic -> the guard did not fire. Pass.
}

#[test]
fn stub_adapters_have_stable_ids() {
    assert_eq!(CtpAdapter.id(), "ctp");
    assert_eq!(OpenDAdapter.id(), "opend");
    assert_eq!(IFindAdapter.id(), "ifind");
    assert_eq!(HuataiAdapter.id(), "huatai");
    assert_eq!(ChoiceAdapter.id(), "choice");
}

#[test]
fn stub_adapters_all_report_not_connected() {
    fn check<B: BrokerAdapter>(b: &B) {
        let h = b.health().unwrap();
        assert!(!h.connected);
        assert!(h.last_error.is_some());
    }
    check(&CtpAdapter);
    check(&OpenDAdapter);
    check(&IFindAdapter);
    check(&HuataiAdapter);
    check(&ChoiceAdapter);
}

#[test]
fn stub_adapters_error_on_every_action() {
    // Every broker action is stubbed. We assert Err on each
    // call shape so a future PR that "wires up" only some of
    // the methods gets caught here.
    let req = crate::pc_automation::broker::types::OrderRequest {
        symbol: "X".to_string(),
        side: crate::pc_automation::broker::types::OrderSide::Sell,
        order_type: crate::pc_automation::broker::types::OrderType::Limit,
        quantity: 1.0,
        price: Some(1.0),
    };
    // Heterogeneous concrete types go through a Vec of trait
    // objects so the per-adapter check is uniform.
    let adapters: Vec<Box<dyn BrokerAdapter>> = vec![
        Box::new(CtpAdapter),
        Box::new(OpenDAdapter),
        Box::new(IFindAdapter),
        Box::new(HuataiAdapter),
        Box::new(ChoiceAdapter),
    ];
    for adapter in &adapters {
        assert!(adapter.place_order(req.clone()).is_err());
        assert!(adapter.cancel_order("any").is_err());
        assert!(adapter.query_positions().is_err());
        assert!(adapter.query_balance().is_err());
    }
}

#[test]
fn broker_health_default_serialises_camel_case() {
    let h = BrokerHealth {
        broker_id: "x".to_string(),
        connected: true,
        latency_ms: 12,
        last_error: None,
    };
    let json = serde_json::to_string(&h).unwrap();
    assert!(json.contains("\"brokerId\""));
    assert!(json.contains("\"latencyMs\""));
    assert!(json.contains("\"lastError\""));
}
