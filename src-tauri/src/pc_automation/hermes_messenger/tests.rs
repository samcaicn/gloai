// Copyright (c) 2026 AIMarketing
//
// AIMarketing v5 §6.2 — unit tests for `hermes_messenger`.
//
// At least 3 tests, as required:
//   1. ClientRequest serialize / deserialize round-trip — the wire
//      shape is `{"type": "...", ...}` so we assert the tag lands
//      in the right place.
//   2. ServerResponse round-trip — same.
//   3. HermesMessenger::new() does not panic and exposes the
//      expected public fields (`tx` is open, `responses` is empty,
//      `take_receiver` works exactly once).

use serde_json::json;

use crate::pc_automation::hermes_messenger::events::{ClientRequest, ServerResponse};
use crate::pc_automation::hermes_messenger::HermesMessenger;
use crate::pc_automation::vlm_rescue::analyzer::{VlmAction, VlmTarget};

// ---------------------------------------------------------------------------
// 1. ClientRequest round-trip: serialize → deserialize must be the
//    identity, with the `type` discriminator rendered as snake_case.
// ---------------------------------------------------------------------------

#[test]
fn client_request_skill_request_serde_roundtrip() {
    let original = ClientRequest::SkillRequest {
        intent: "提交电商订单".to_string(),
        context: Some(json!({"locale": "zh-CN"})),
    };

    let s = serde_json::to_string(&original).expect("serialize ClientRequest");
    // Wire shape: `type` tag, snake_case variant name.
    assert!(
        s.contains("\"type\":\"skill_request\""),
        "skill_request variant must serialize as snake_case: {}",
        s
    );
    assert!(s.contains("提交电商订单"), "intent must survive: {}", s);

    let parsed: ClientRequest =
        serde_json::from_str(&s).expect("deserialize ClientRequest");
    match parsed {
        ClientRequest::SkillRequest { intent, context } => {
            assert_eq!(intent, "提交电商订单");
            assert_eq!(context.unwrap()["locale"], "zh-CN");
        }
        other => panic!("wrong variant after round-trip: {:?}", other),
    }
}

#[test]
fn client_request_vlm_request_serde_roundtrip() {
    let original = ClientRequest::VlmRequest {
        screenshot_b64: "iVBORw0KGgoAAAANSUhEUg==".to_string(),
        failed_step: json!({
            "id": "step_42",
            "selector": "uia:button?name=提交",
        }),
        intent: "提交订单".to_string(),
    };

    let s = serde_json::to_string(&original).expect("serialize");
    assert!(
        s.contains("\"type\":\"vlm_request\""),
        "vlm_request variant must serialize as snake_case: {}",
        s
    );
    assert!(s.contains("iVBORw0KGgo"), "screenshot_b64 must survive: {}", s);

    let parsed: ClientRequest = serde_json::from_str(&s).expect("deserialize");
    match parsed {
        ClientRequest::VlmRequest {
            screenshot_b64,
            failed_step,
            intent,
        } => {
            assert_eq!(screenshot_b64, "iVBORw0KGgoAAAANSUhEUg==");
            assert_eq!(failed_step["id"], "step_42");
            assert_eq!(intent, "提交订单");
        }
        other => panic!("wrong variant after round-trip: {:?}", other),
    }
}

#[test]
fn client_request_vlm_request_with_missing_context_field() {
    // SkillRequest's `context` is `Option<...>` so omitting it must
    // still parse (per `#[serde(default, skip_serializing_if = "Option::is_none")]`).
    let s = r#"{"type":"skill_request","intent":"打开设置"}"#;
    let parsed: ClientRequest = serde_json::from_str(s).expect("missing context must parse");
    match parsed {
        ClientRequest::SkillRequest { intent, context } => {
            assert_eq!(intent, "打开设置");
            assert!(context.is_none());
        }
        other => panic!("wrong variant: {:?}", other),
    }
}

// ---------------------------------------------------------------------------
// 2. ServerResponse round-trip: skill + vlm variants.
// ---------------------------------------------------------------------------

#[test]
fn server_response_skill_serde_roundtrip() {
    let original = ServerResponse::SkillResponse {
        skill_data_b64: "c2tpbGxfZGF0YQ==".to_string(),
        iv_b64: "aXZfMTI=".to_string(),
        tag_b64: "dGFnXzMy".to_string(),
    };

    let s = serde_json::to_string(&original).expect("serialize");
    assert!(
        s.contains("\"type\":\"skill_response\""),
        "skill_response variant must serialize as snake_case: {}",
        s
    );
    assert!(s.contains("\"skillDataB64\""), "must be camelCase wire: {}", s);
    assert!(s.contains("\"ivB64\""));
    assert!(s.contains("\"tagB64\""));

    let parsed: ServerResponse = serde_json::from_str(&s).expect("deserialize");
    match parsed {
        ServerResponse::SkillResponse {
            skill_data_b64,
            iv_b64,
            tag_b64,
        } => {
            assert_eq!(skill_data_b64, "c2tpbGxfZGF0YQ==");
            assert_eq!(iv_b64, "aXZfMTI=");
            assert_eq!(tag_b64, "dGFnXzMy");
        }
        other => panic!("wrong variant: {:?}", other),
    }
}

#[test]
fn server_response_vlm_serde_roundtrip() {
    let original = ServerResponse::VlmResponse {
        action: VlmAction {
            action: "click".to_string(),
            target: VlmTarget {
                kind: "pixel".to_string(),
                x: 200,
                y: 300,
            },
            confidence: 0.82,
            explanation: "orange button at (200, 300)".to_string(),
        },
        explanation: "the only clickable element near the prompt".to_string(),
    };

    let s = serde_json::to_string(&original).expect("serialize");
    assert!(
        s.contains("\"type\":\"vlm_response\""),
        "vlm_response variant must serialize as snake_case: {}",
        s
    );

    let parsed: ServerResponse = serde_json::from_str(&s).expect("deserialize");
    match parsed {
        ServerResponse::VlmResponse {
            action,
            explanation,
        } => {
            assert_eq!(action.action, "click");
            assert_eq!(action.target.x, 200);
            assert_eq!(action.target.y, 300);
            assert!((action.confidence - 0.82).abs() < 1e-6);
            assert_eq!(explanation, "the only clickable element near the prompt");
        }
        other => panic!("wrong variant: {:?}", other),
    }
}

// ---------------------------------------------------------------------------
// 3. HermesMessenger::new() smoke test: doesn't panic, fields are
//    in the expected state, `take_receiver` is single-use.
// ---------------------------------------------------------------------------

#[test]
fn hermes_messenger_new_does_not_panic() {
    // The constructor must not panic and must return a messenger
    // whose public state is sensible.
    let m = HermesMessenger::new();
    assert!(!m.tx.is_closed(), "tx must be open right after new()");
    assert!(m.responses_snapshot().is_empty(), "response log starts empty");
    assert!(
        m.take_receiver().is_some(),
        "first take_receiver() must yield the receiver"
    );
}

#[test]
fn hermes_messenger_take_receiver_is_single_use() {
    let m = HermesMessenger::new();
    let first = m.take_receiver();
    assert!(first.is_some(), "first take must yield a receiver");
    let second = m.take_receiver();
    assert!(second.is_none(), "second take must yield None");
}

#[test]
fn hermes_messenger_send_and_record_response() {
    // Sanity: send() works while the receiver is parked, and
    // record_response() lands in the log.
    let m = HermesMessenger::new();
    // We deliberately do NOT take the receiver here — sending
    // would block / drop on a closed channel. But the bus must
    // accept the send while the receiver is still owned.
    let _ = m.take_receiver();
    let err = m
        .send(ClientRequest::SkillRequest {
            intent: "test".to_string(),
            context: None,
        })
        .expect_err("send must fail when receiver is dropped");
    assert!(
        err.contains("channel closed"),
        "send must surface channel-closed error: {}",
        err
    );

    // record_response is best-effort and always succeeds.
    m.record_response(ServerResponse::SkillResponse {
        skill_data_b64: "x".to_string(),
        iv_b64: "y".to_string(),
        tag_b64: "z".to_string(),
    });
    let snap = m.responses_snapshot();
    assert_eq!(snap.len(), 1);
    assert_eq!(snap[0].kind(), "skill_response");
}

#[tokio::test]
async fn hermes_messenger_request_skill_returns_stub_error() {
    // The v1 cut explicitly does NOT wire the dispatcher; the
    // stub must surface a deterministic error string.
    let m = HermesMessenger::new();
    let err = m
        .request_skill("提交订单")
        .await
        .expect_err("stub must return Err");
    assert!(
        err.contains("not wired"),
        "stub error must mention 'not wired': {}",
        err
    );
    assert!(
        err.contains("LocalSkillStorage"),
        "stub error must point callers at LocalSkillStorage: {}",
        err
    );
}

#[tokio::test]
async fn hermes_messenger_request_vlm_returns_stub_error() {
    let m = HermesMessenger::new();
    let failed_step = json!({"id": "step_42", "selector": "uia:button?name=提交"});
    let err = m
        .request_vlm("iVBORw0KGgoAAAANSUhEUg==", &failed_step, "提交订单")
        .await
        .expect_err("stub must return Err");
    assert!(
        err.contains("not wired"),
        "stub error must mention 'not wired': {}",
        err
    );
    assert!(
        err.contains("VlmRescue"),
        "stub error must point callers at VlmRescue: {}",
        err
    );
}
