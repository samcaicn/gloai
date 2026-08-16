// Copyright (c) 2026 tupAI
//
// tupAI v5 §6.1 — unit tests for `vlm_rescue`.
//
// Coverage:
//   1. Fixed-template prompt assembly contains intent + step_json
//   2. Dynamic-prompt path falls back to fixed template when
//      the cloud LLM is unconfigured / errors / returns empty
//   3. VlmAction JSON round-trip (camelCase wire shape)
//   4. Confidence threshold gate
//   5. max_attempts 限频 (the rescue refuses to dispatch once
//      the cap is reached)
//   6. Input validation (empty screenshot / empty intent)
//
// All tests are hermetic — no network, no real screenshots,
// no real LLM call. The VLM dispatch path is a deterministic
// stub that always returns `confidence = 0.5` (below
// threshold), so the threshold-gate assertion in test 4 is
// exercised end-to-end via `VlmRescue::try_rescue`.

use std::pin::Pin;
use std::sync::Arc;

use serde_json::json;

use crate::pc_automation::vlm_rescue::analyzer::{
    build_dynamic_prompt, build_prompt, is_action_acceptable, parse_ui_tars_response,
    DynamicPromptConfig, LlmMessage, RescueContext, VlmAction, VlmTarget,
    DEFAULT_CONFIDENCE_THRESHOLD,
};
use crate::pc_automation::vlm_rescue::VlmRescue;

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/// Build a `RescueContext` with sensible defaults for tests.
fn make_ctx<'a>(step_summary: &'a str, intent: &'a str) -> RescueContext<'a> {
    RescueContext {
        step_summary,
        intent,
        app_profile: Some("ths_hexin"),
        primary_err: Some("uia: not found"),
        fallback_err: Some("ocr: anchor not found"),
        attempt_index: 1,
    }
}

/// Minimal valid PNG header magic (8 bytes) — enough to satisfy
/// the "non-empty screenshot" guard.
fn fake_png() -> Vec<u8> {
    vec![0x89, b'P', b'N', b'G', 0x0d, 0x0a, 0x1a, 0x0a]
}

// ---------------------------------------------------------------------------
// 1. Fixed-template prompt assembly: both `intent` and `step_json`
//    must appear in the rendered prompt so the LLM sees both pieces
//    of context.
// ---------------------------------------------------------------------------

#[tokio::test]
async fn build_prompt_contains_intent_and_step_json() {
    let intent = "提交电商订单";
    let step_json = r#"{"id":"step_42","selector":"uia:button?name=提交"}"#;

    let prompt = build_prompt(step_json, intent, None, None).await;

    assert!(
        prompt.contains("提交电商订单"),
        "prompt must embed the user intent verbatim: {}",
        prompt
    );
    assert!(
        prompt.contains("step_42"),
        "prompt must embed the failing step's id: {}",
        prompt
    );
    assert!(
        prompt.contains("uia:button"),
        "prompt must embed the failing step's selector: {}",
        prompt
    );
    // Sanity: 提示词必须显式描述 UI-TARS 双段输出格式。
    assert!(
        prompt.contains("Thought:"),
        "prompt must specify the Thought: segment of the UI-TARS protocol: {}",
        prompt
    );
    assert!(
        prompt.contains("Action:"),
        "prompt must specify the Action: segment of the UI-TARS protocol: {}",
        prompt
    );
    // 同时校验模板里出现的 box token,这是 UI-TARS 坐标包裹标记。
    assert!(
        prompt.contains("<|box_start|>"),
        "prompt must declare the UI-TARS box-start token"
    );
    assert!(
        prompt.contains("<|box_end|>"),
        "prompt must declare the UI-TARS box-end token"
    );
}

// ---------------------------------------------------------------------------
// 2. Dynamic-prompt path: when the cloud LLM is unconfigured we
//    must fall back to the fixed template. When the LLM errors
//    or returns an empty string we also fall back. When the LLM
//    returns a non-empty string we keep it.
// ---------------------------------------------------------------------------

#[tokio::test]
async fn build_dynamic_prompt_falls_back_when_no_llm_wired() {
    // Default DynamicPromptConfig has no llm_complete_fn → must
    // produce the FIXED template, byte-for-byte.
    let cfg = DynamicPromptConfig::default();
    let ctx = make_ctx("click submit", "提交订单");
    let dynamic = build_dynamic_prompt(&cfg, &ctx).await;
    let fixed = build_prompt(
        ctx.step_summary,
        ctx.intent,
        ctx.primary_err,
        ctx.fallback_err,
    )
    .await;
    assert_eq!(
        dynamic, fixed,
        "no LLM wired → dynamic must equal fixed template"
    );
}

#[tokio::test]
async fn build_dynamic_prompt_falls_back_on_llm_error() {
    // Configure an LLM that always errors → must fall back.
    fn failing_llm(
        _msgs: Vec<LlmMessage>,
    ) -> Pin<Box<dyn std::future::Future<Output = Result<String, String>> + Send>> {
        Box::pin(async { Err("network down".to_string()) })
    }
    let cfg = DynamicPromptConfig {
        llm_complete_fn: Some(Arc::new(failing_llm)),
    };
    let ctx = make_ctx("click submit", "提交订单");
    let dynamic = build_dynamic_prompt(&cfg, &ctx).await;
    let fixed = build_prompt(
        ctx.step_summary,
        ctx.intent,
        ctx.primary_err,
        ctx.fallback_err,
    )
    .await;
    assert_eq!(dynamic, fixed, "LLM error → fall back to fixed template");
}

#[tokio::test]
async fn build_dynamic_prompt_falls_back_on_empty_response() {
    // Configure an LLM that returns "" → must fall back.
    fn empty_llm(
        _msgs: Vec<LlmMessage>,
    ) -> Pin<Box<dyn std::future::Future<Output = Result<String, String>> + Send>> {
        Box::pin(async { Ok(String::new()) })
    }
    let cfg = DynamicPromptConfig {
        llm_complete_fn: Some(Arc::new(empty_llm)),
    };
    let ctx = make_ctx("click submit", "提交订单");
    let dynamic = build_dynamic_prompt(&cfg, &ctx).await;
    let fixed = build_prompt(
        ctx.step_summary,
        ctx.intent,
        ctx.primary_err,
        ctx.fallback_err,
    )
    .await;
    assert_eq!(dynamic, fixed, "empty LLM response → fall back");
}

#[tokio::test]
async fn build_dynamic_prompt_uses_llm_output_when_valid() {
    // Configure an LLM that returns a real prompt → the dynamic
    // builder must use it verbatim.
    fn good_llm(
        _msgs: Vec<LlmMessage>,
    ) -> Pin<Box<dyn std::future::Future<Output = Result<String, String>> + Send>> {
        Box::pin(async { Ok("DYNAMIC_PROMPT_FROM_LLM".to_string()) })
    }
    let cfg = DynamicPromptConfig {
        llm_complete_fn: Some(Arc::new(good_llm)),
    };
    let ctx = make_ctx("click submit", "提交订单");
    let dynamic = build_dynamic_prompt(&cfg, &ctx).await;
    assert_eq!(
        dynamic, "DYNAMIC_PROMPT_FROM_LLM",
        "valid LLM response → use it verbatim"
    );
}

// ---------------------------------------------------------------------------
// 3. VlmAction JSON round-trip: serialize → deserialize must be the
//    identity (and camelCase by default — that's the wire contract).
// ---------------------------------------------------------------------------

#[test]
fn vlm_action_serde_roundtrip() {
    let original = VlmAction {
        action: "click".to_string(),
        target: VlmTarget {
            kind: "pixel".to_string(),
            x: 1234,
            y: 567,
        },
        confidence: 0.87,
        explanation: "the orange 提交 button is at (1234, 567)".to_string(),
    };

    let json = serde_json::to_value(&original).expect("serialize VlmAction");
    // camelCase verification: the wire shape uses `target` / `x` /
    // `y` / `confidence` / `explanation`. `target` is one word so we
    // only check the multi-word one — `action` / `confidence` /
    // `explanation` are single tokens.
    assert!(json.get("action").is_some(), "missing 'action' field");
    assert!(json.get("target").is_some(), "missing 'target' field");
    assert!(json.get("confidence").is_some(), "missing 'confidence' field");
    assert!(json.get("explanation").is_some(), "missing 'explanation' field");
    assert_eq!(json["target"]["x"].as_i64(), Some(1234));
    assert_eq!(json["target"]["y"].as_i64(), Some(567));
    assert!(
        (json["confidence"].as_f64().unwrap() - 0.87).abs() < 1e-6,
        "confidence not preserved"
    );

    // Reverse direction.
    let parsed: VlmAction = serde_json::from_value(json).expect("deserialize VlmAction");
    assert_eq!(parsed, original);
}

#[test]
fn vlm_action_parses_from_realistic_llm_payload() {
    // The shape the LLM is expected to return (per the prompt).
    let payload = json!({
        "action": "input",
        "target": { "kind": "pixel", "x": 100, "y": 200 },
        "confidence": 0.73,
        "explanation": "text field detected near label 用户名"
    });

    let action: VlmAction =
        serde_json::from_value(payload).expect("LLM-style payload must parse");
    assert_eq!(action.action, "input");
    assert_eq!(action.target.x, 100);
    assert_eq!(action.target.y, 200);
    assert!((action.confidence - 0.73).abs() < 1e-6);
}

// ---------------------------------------------------------------------------
// 4. Confidence threshold: a borderline value must be rejected (or
//    accepted) exactly at the cut. `is_action_acceptable` is the
//    single source of truth; the end-to-end check on `try_rescue`
//    just confirms the wire-up.
// ---------------------------------------------------------------------------

#[test]
fn confidence_threshold_rejects_below_and_accepts_at_or_above() {
    let above = VlmAction {
        action: "click".to_string(),
        target: VlmTarget { kind: "pixel".to_string(), x: 0, y: 0 },
        confidence: DEFAULT_CONFIDENCE_THRESHOLD, // boundary: accepted
        explanation: String::new(),
    };
    let below = VlmAction {
        confidence: DEFAULT_CONFIDENCE_THRESHOLD - 0.01,
        ..above.clone()
    };
    let far_below = VlmAction {
        confidence: 0.0,
        ..above.clone()
    };
    let high = VlmAction {
        confidence: 0.99,
        ..above.clone()
    };

    assert!(is_action_acceptable(&above, DEFAULT_CONFIDENCE_THRESHOLD));
    assert!(!is_action_acceptable(&below, DEFAULT_CONFIDENCE_THRESHOLD));
    assert!(!is_action_acceptable(&far_below, DEFAULT_CONFIDENCE_THRESHOLD));
    assert!(is_action_acceptable(&high, DEFAULT_CONFIDENCE_THRESHOLD));

    // Custom threshold is respected.
    assert!(!is_action_acceptable(&above, 0.95));
    assert!(is_action_acceptable(&high, 0.95));
}

#[tokio::test]
async fn try_rescue_rejects_stub_below_threshold() {
    // The stub always returns confidence 0.5 (below the default 0.6
    // threshold). `try_rescue` must short-circuit with a "below
    // threshold" error.
    let rescue = VlmRescue::default();
    let ctx = make_ctx("click submit", "提交订单");
    let err = rescue
        .try_rescue(&ctx, &fake_png())
        .await
        .expect_err("stub confidence 0.5 must be rejected");
    assert!(
        err.contains("below threshold"),
        "error must mention threshold: {}",
        err
    );
}

// ---------------------------------------------------------------------------
// 5. max_attempts 限频: after N failed rescue attempts, the next call
//    must return the "exhausted" error without dispatching.
// ---------------------------------------------------------------------------

#[tokio::test]
async fn max_attempts_caps_dispatch_frequency() {
    let rescue = VlmRescue::new(3, DEFAULT_CONFIDENCE_THRESHOLD);
    let ctx = make_ctx("click submit", "提交订单");

    // First three calls increment the counter and fail the
    // threshold check (stub returns 0.5). Counter should reach 3.
    for i in 0..3 {
        let err = rescue
            .try_rescue(&ctx, &fake_png())
            .await
            .expect_err(&format!("attempt {} must fail (stub returns 0.5)", i));
        assert!(
            err.contains("below threshold"),
            "attempt {}: expected threshold error, got: {}",
            i,
            err
        );
    }
    assert_eq!(rescue.attempts(), 3, "counter should reach max_attempts");
    assert!(rescue.exhausted(), "exhausted() must be true at the cap");

    // Fourth call: must short-circuit with the "exhausted" error
    // *without* bumping the counter further.
    let err = rescue
        .try_rescue(&ctx, &fake_png())
        .await
        .expect_err("fourth call must short-circuit");
    assert!(
        err.contains("exhausted"),
        "fourth call must surface the exhausted error: {}",
        err
    );
    assert_eq!(
        rescue.attempts(),
        3,
        "exhausted call must NOT bump the counter"
    );

    // `reset_attempts` clears the cap so a fresh run can try again.
    rescue.reset_attempts();
    assert_eq!(rescue.attempts(), 0);
    assert!(!rescue.exhausted());
}

// ---------------------------------------------------------------------------
// 6. Input validation: empty screenshot / empty intent must error
//    *before* bumping the attempt counter.
// ---------------------------------------------------------------------------

#[tokio::test]
async fn try_rescue_validates_input_before_dispatch() {
    let rescue = VlmRescue::default();
    let ctx_ok = make_ctx("click submit", "提交订单");

    // Empty screenshot → input error, counter stays at 0.
    let err = rescue
        .try_rescue(&ctx_ok, &[])
        .await
        .expect_err("empty screenshot must be rejected");
    assert!(err.contains("empty screenshot"), "got: {}", err);
    assert_eq!(rescue.attempts(), 0, "input validation must not bump counter");

    // Empty intent → input error, counter stays at 0.
    let ctx_empty_intent = RescueContext {
        intent: "   ",
        ..ctx_ok
    };
    let err = rescue
        .try_rescue(&ctx_empty_intent, &fake_png())
        .await
        .expect_err("empty intent must be rejected");
    assert!(err.contains("empty intent"), "got: {}", err);
    assert_eq!(rescue.attempts(), 0);
}

// ---------------------------------------------------------------------------
// 7. UI-TARS protocol parser: 验证 `parse_ui_tars_response` 能从
//    `Thought: ... Action: ...` 双段协议字符串里抽 thought / 动作名 / 坐标,
//    并对格式错误给出中文错误。
// ---------------------------------------------------------------------------

#[test]
fn test_parse_ui_tars_response_click() {
    // 完整 click 响应,坐标 (1234, 567)
    let response = "Thought: 提交按钮在右下角,需要点击。\n\
                    Action: click(start_box='<|box_start|>1234 567<|box_end|>')";
    let action = parse_ui_tars_response(response).expect("click must parse");
    assert_eq!(action.action, "click", "action 字段应为 click");
    assert_eq!(action.target.kind, "pixel");
    assert_eq!(action.target.x, 1234, "x 坐标应从 start_box 解出");
    assert_eq!(action.target.y, 567, "y 坐标应从 start_box 解出");
    assert_eq!(
        action.explanation, "提交按钮在右下角,需要点击。",
        "thought 段应原样保留到 explanation"
    );
    assert!(
        (action.confidence - 0.5).abs() < 1e-6,
        "无显式 confidence 时应使用默认 0.5,实际 {}",
        action.confidence
    );
}

#[test]
fn test_parse_ui_tars_response_type() {
    // type 响应(没有 start_box,只有 content),坐标兜底为 (0, 0)
    let response = "Thought: 用户要在搜索框里输入「平安银行」。\n\
                    Action: type(content='平安银行')";
    let action = parse_ui_tars_response(response).expect("type must parse");
    assert_eq!(action.action, "input", "type 应映射到 VlmAction.input");
    assert_eq!(action.target.x, 0, "type 无坐标时 x 兜底为 0");
    assert_eq!(action.target.y, 0);
    assert_eq!(
        action.explanation, "用户要在搜索框里输入「平安银行」。",
        "type 响应的 thought 段保留"
    );
}

#[test]
fn test_parse_ui_tars_response_invalid_format() {
    // 空字符串 → 解析报错
    let err = parse_ui_tars_response("").expect_err("空响应必须报错");
    assert!(
        err.contains("VLM 响应为空"),
        "空响应应给出中文错误,实际: {}",
        err
    );

    // 既没有 Thought 也没有 Action → 解析报错
    let err = parse_ui_tars_response("这是一段无关键字段的纯文本")
        .expect_err("无 Thought/Action 段必须报错");
    assert!(
        err.contains("Thought") && err.contains("Action"),
        "错误信息应同时提示 Thought/Action 缺失,实际: {}",
        err
    );

    // 只有 Thought 没有 Action → 协议不完整,报错
    let err = parse_ui_tars_response("Thought: 我不知道下一步该干什么")
        .expect_err("仅有 Thought 必须报错");
    assert!(
        err.contains("Action"),
        "仅有 Thought 时错误应提示缺 Action,实际: {}",
        err
    );

    // Action 段使用了未支持的动作名 → 报错
    let err = parse_ui_tars_response(
        "Thought: x\nAction: teleport(start_box='<|box_start|>1 2<|box_end|>')",
    )
    .expect_err("未支持的动作名必须报错");
    assert!(
        err.contains("teleport"),
        "错误信息应回显未识别的动作名,实际: {}",
        err
    );
}

#[test]
fn test_parse_ui_tars_response_drag_and_hotkey() {
    // drag 响应(双 start_box / end_box),我们只取 start_box 坐标
    let drag_response = "Thought: 把滑块从左拖到右。\n\
                         Action: drag(start_box='<|box_start|>100 200<|box_end|>', end_box='<|box_start|>300 400<|box_end|>')";
    let action = parse_ui_tars_response(drag_response).expect("drag must parse");
    assert_eq!(action.action, "drag");
    assert_eq!(action.target.x, 100);
    assert_eq!(action.target.y, 200);

    // hotkey 响应(无 start_box),坐标兜底为 (0, 0)
    let hotkey_response = "Thought: 复制当前选中内容。\n\
                           Action: hotkey(key='ctrl c')";
    let action = parse_ui_tars_response(hotkey_response).expect("hotkey must parse");
    assert_eq!(action.action, "key", "hotkey 应映射到 VlmAction.key");
    assert_eq!(action.target.x, 0);
    assert_eq!(action.target.y, 0);
}

#[test]
fn test_parse_ui_tars_response_finished() {
    // finished 响应(任务结束),无坐标
    let response = "Thought: 任务已完成。\n\
                    Action: finished(content='下单成功')";
    let action = parse_ui_tars_response(response).expect("finished must parse");
    assert_eq!(action.action, "finished");
    assert_eq!(action.explanation, "任务已完成。");
}
