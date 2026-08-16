// Copyright (c) 2026 AIMarketing
//
// UIRPA integration tests.
//
// Cross-module integration tests covering the full UIRPA stack:
//   * `pc_automation::skill`         — data + encrypt + storage
//   * `pc_automation::executor`      — multi-priority selector
//                                               + wait condition + retry
//   * `pc_automation::vlm_rescue`    — VlmAction parse
//   * `pc_automation::hermes_messenger` — message bus
//
// These tests are `#[test]`-style synchronous functions. They do
// not require a Tauri runtime: backends (UIA / CDP / OCR / LLM) are
// stubbed or replaced with the in-memory `Stub*Backend` types so the
// logic-only paths can be exercised deterministically.
//
// File layout:
//   * `tests/integration_uirpa.rs` — this file
//   * wired into the build via `#[path = "tests/integration_uirpa.rs"]`
//     from `pc_automation/mod.rs`
//
// Test map (per task description):
//   * test_skill_roundtrip             — Skill ↔ YAML ↔ disk ↔ Skill
//   * test_encrypt_decrypt_skill       — SkillDecryptor encrypt+decrypt
//   * test_template_render             — `{{name}}` template replacement
//   * test_multi_priority_selector_sort — 3 selectors, sorted desc
//   * test_retry_policy_exponential    — attempt 0/1/5/10 boundary
//   * test_skill_step_to_pc_step_convert — SkillStep ↔ PcStep roundtrip
//   * test_wait_condition_delay        — WaitCondition::Delay success
//   * test_vlm_action_parse            — VlmAction from JSON string
//
// All assertions carry a descriptive `msg` so a regression points
// at the right field at a glance.

#![allow(unused_imports)]

use std::path::Path;

use serde_json::json;
use tempfile::tempdir;

// ----------------------------------------------------------------
// 1. Skill (de)serialise round-trip — Skill → YAML → disk → Skill
// ----------------------------------------------------------------

#[test]
fn test_skill_roundtrip() {
    use crate::pc_automation::skill::types::{
        ElementSelector, Parameter, ParamType, Selector, SelectorKind, Skill, SkillAction,
        SkillStep,
    };

    // Build a minimal but representative Skill.
    let original = Skill {
        skill_id: "skill_roundtrip_001".into(),
        version: "1.0.0".into(),
        intent: "Round-trip test".into(),
        scene_fingerprint: Some("sha256:feedbeef".into()),
        created_at: chrono::Utc::now(),
        updated_at: chrono::Utc::now(),
        success_rate: 0.75,
        avg_execution_time_ms: 250,
        parameters: vec![Parameter {
            name: "customer".into(),
            param_type: ParamType::String,
            required: true,
            default: None,
        }],
        steps: vec![SkillStep {
            id: "step_1".into(),
            description: "Click submit".into(),
            intent: "submit order".into(),
            element_selector: ElementSelector {
                version: "1.0".into(),
                primary: Selector {
                    kind: SelectorKind::Uia,
                    value: "uia:controlType=Button;name=提交订单".into(),
                    stability_score: 0.95,
                    context: Some("main".into()),
                    match_threshold: None,
                    resolution: None,
                },
                fallbacks: vec![],
                iframe_context: None,
                shadow_root_context: None,
            },
            action: SkillAction::Click,
            parameter: None,
            wait_condition: None,
            post_action_validation: None,
            interaction: None,
        }],
        error_handlers: vec![],
        branches: vec![],
        name: "skill-roundtrip-001".into(),
        description: "Round-trip test".into(),
        license: None,
    };

    // 1. Serialise to YAML (the task description says "序列化为 YAML").
    let yaml = serde_yaml::to_string(&original)
        .expect("Skill → YAML serialisation must succeed");
    assert!(
        yaml.contains("skill_roundtrip_001"),
        "serialised YAML must carry the skill id: {}",
        yaml
    );
    assert!(
        yaml.contains("skill_id") || yaml.contains("skillId"),
        "YAML must carry the camelCase / snake_case field label: {}",
        yaml
    );

    // 2. Persist to a unique path under the system temp dir.
    let dir = tempdir().expect("tempdir must be available");
    let file = dir.path().join("roundtrip.yaml");
    std::fs::write(&file, &yaml).expect("write YAML to disk must succeed");

    // 3. Read back and deserialise.
    let read_back = std::fs::read_to_string(&file)
        .expect("read back from disk must succeed");
    let parsed: Skill = serde_yaml::from_str(&read_back)
        .expect("YAML → Skill deserialisation must succeed");

    // 4. Round-trip equality (semantic — chronos will be equal to a
    //    millisecond, but be defensive and compare the fields we care
    //    about).
    assert_eq!(parsed.skill_id, original.skill_id);
    assert_eq!(parsed.version, original.version);
    assert_eq!(parsed.intent, original.intent);
    assert_eq!(parsed.success_rate, original.success_rate);
    assert_eq!(parsed.parameters.len(), 1);
    assert_eq!(parsed.steps.len(), 1);
    assert_eq!(parsed.steps[0].id, "step_1");
    assert_eq!(
        parsed.steps[0].element_selector.primary.kind,
        SelectorKind::Uia
    );
}

// ----------------------------------------------------------------
// 2. Encrypt / decrypt — Skill plaintext → ciphertext → plaintext
// ----------------------------------------------------------------

#[test]
fn test_encrypt_decrypt_skill() {
    use crate::pc_automation::skill::decryptor::SkillDecryptor;

    // A deterministic 32-byte key (in production this is derived
    // from the user password via Argon2id; the test uses a raw
    // key per the v1 task spec "先用固定 master key 测试").
    let key = [0xABu8; 32];
    let decryptor = SkillDecryptor::new(key);

    // Plaintext that resembles a real Skill JSON body. We don't
    // construct a full `Skill` here so the test is robust to
    // schema churn in `skill::types`.
    let plaintext = br#"{"skillId":"enc_test","intent":"encrypt round-trip"}"#;

    // 1. Encrypt → (ciphertext, nonce, tag).
    let (ciphertext, nonce, tag) = decryptor
        .encrypt(plaintext)
        .expect("encryption must succeed");

    // The ciphertext must not equal the plaintext (sanity check).
    assert_ne!(
        ciphertext.as_slice(),
        &plaintext[..],
        "ciphertext must differ from plaintext"
    );
    // AES-GCM returns 16-byte tag + same-length ciphertext (we
    // split the tag off in the decryptor), so the ciphertext
    // length matches the plaintext length.
    assert_eq!(
        ciphertext.len(),
        plaintext.len(),
        "ciphertext length must match plaintext length"
    );

    // 2. Round-trip: decrypt with the same instance.
    let recovered = decryptor
        .decrypt(&ciphertext, &nonce, &tag)
        .expect("decryption must succeed");
    assert_eq!(
        recovered, plaintext,
        "decrypted plaintext must match the original"
    );

    // 3. Tampered ciphertext must fail the GCM tag check.
    let mut bad = ciphertext.clone();
    if let Some(byte) = bad.first_mut() {
        *byte ^= 0x01;
    }
    let tamper_err = decryptor
        .decrypt(&bad, &nonce, &tag)
        .expect_err("tampered ciphertext must fail to decrypt");
    assert!(
        tamper_err.to_lowercase().contains("decrypt failed")
            || tamper_err.to_lowercase().contains("tag"),
        "tamper error must mention decrypt/tag: {}",
        tamper_err
    );

    // 4. Persist ciphertext+nonce+tag to disk and read back.
    let dir = tempdir().expect("tempdir must be available");
    let path = dir.path().join("enc_test.bin");
    let mut blob: Vec<u8> = Vec::with_capacity(ciphertext.len() + nonce.len() + tag.len());
    blob.extend_from_slice(&nonce);
    blob.extend_from_slice(&tag);
    blob.extend_from_slice(&ciphertext);
    std::fs::write(&path, &blob).expect("write ciphertext to disk must succeed");

    let read_back = std::fs::read(&path).expect("read ciphertext from disk must succeed");
    let r_nonce: [u8; 12] = read_back[0..12].try_into().expect("nonce slice");
    let r_tag: [u8; 16] = read_back[12..28].try_into().expect("tag slice");
    let r_ct = &read_back[28..];
    let r_plain = decryptor
        .decrypt(r_ct, &r_nonce, &r_tag)
        .expect("disk-roundtrip decryption must succeed");
    assert_eq!(r_plain, plaintext);
}

// ----------------------------------------------------------------
// 3. Template render — `{{name}}` substitution
// ----------------------------------------------------------------

#[test]
fn test_template_render() {
    use crate::pc_automation::skill::template::render_template;
    use serde_json::Map;

    let mut params = Map::new();
    params.insert("name".into(), json!("Alice"));
    params.insert("count".into(), json!(3));
    params.insert("ok".into(), json!(true));

    // 1. Single placeholder, no whitespace.
    let out = render_template("Hello, {{name}}!", &params)
        .expect("render must succeed");
    assert_eq!(out, "Hello, Alice!", "got: {:?}", out);

    // 2. Whitespace tolerated inside the braces.
    let out = render_template("count = {{ count }}", &params)
        .expect("render with whitespace must succeed");
    assert_eq!(out, "count = 3");

    // 3. Boolean renders as the literal "true".
    let out = render_template("ok={{ok}}", &params)
        .expect("render with bool must succeed");
    assert_eq!(out, "ok=true");

    // 4. No placeholders → passthrough.
    let out = render_template("plain text", &params)
        .expect("passthrough render must succeed");
    assert_eq!(out, "plain text");

    // 5. Multiple placeholders in one string.
    let out = render_template("{{name}}-{{count}}-{{ok}}", &params)
        .expect("multi-placeholder render must succeed");
    assert_eq!(out, "Alice-3-true");

    // 6. Missing parameter → Err.
    let err = render_template("{{unknown}}", &params)
        .expect_err("missing parameter must error");
    assert!(
        err.contains("unknown"),
        "error must name the missing param: {}",
        err
    );
}

// ----------------------------------------------------------------
// 4. Multi-priority selector sort — 3 selectors, sorted desc
// ----------------------------------------------------------------

#[test]
fn test_multi_priority_selector_sort() {
    use crate::pc_automation::executor::selector::MultiPrioritySelector;
    // `MultiPrioritySelector::new` consumes the `executor::Selector`
    // re-export (i.e. `skill_stub::Selector`); the new
    // `pc_automation::skill::types::Selector` is shape-identical but
    // is a distinct nominal type until the executor finishes switching
    // the executor over. Use the executor's type here so the test
    // exercises the live API.
    use crate::pc_automation::executor::{Selector, SelectorKind};

    // 3 selectors with deliberately unsorted stability scores.
    let s1 = Selector {
        kind: SelectorKind::Uia,
        value: "uia:low".into(),
        stability_score: 0.3,
        context: None,
        match_threshold: None,
        resolution: None,
    };
    let s2 = Selector {
        kind: SelectorKind::Cdp,
        value: "cdp:high".into(),
        stability_score: 0.95,
        context: None,
        match_threshold: None,
        resolution: None,
    };
    let s3 = Selector {
        kind: SelectorKind::Ocr,
        value: "ocr:mid".into(),
        stability_score: 0.6,
        context: None,
        match_threshold: None,
        resolution: None,
    };

    // Feed them in scrambled order.
    let mps = MultiPrioritySelector::new(vec![s1.clone(), s2.clone(), s3.clone()]);

    // After construction, selectors must be sorted by stability_score
    // descending: 0.95, 0.6, 0.3.
    assert_eq!(
        mps.selectors.len(),
        3,
        "all 3 selectors must be retained"
    );
    assert!(
        mps.selectors[0].stability_score >= mps.selectors[1].stability_score,
        "selectors[0] ({} >= selectors[1] ({})) must be >= selectors[1]",
        mps.selectors[0].stability_score,
        mps.selectors[1].stability_score
    );
    assert!(
        mps.selectors[1].stability_score >= mps.selectors[2].stability_score,
        "selectors[1] ({} >= selectors[2] ({})) must be >= selectors[2]",
        mps.selectors[1].stability_score,
        mps.selectors[2].stability_score
    );
    // Tied assertion: the head of the list must be the CDP/high one.
    assert!(
        (mps.selectors[0].stability_score - 0.95).abs() < f32::EPSILON,
        "first selector must be the 0.95 one, got {}",
        mps.selectors[0].stability_score
    );
    assert!(
        (mps.selectors[2].stability_score - 0.3).abs() < f32::EPSILON,
        "last selector must be the 0.3 one, got {}",
        mps.selectors[2].stability_score
    );
}

// ----------------------------------------------------------------
// 5. Retry policy exponential — attempt 0/1/5/10 boundary
// ----------------------------------------------------------------

#[test]
fn test_retry_policy_exponential() {
    use crate::pc_automation::executor::retry::RetryPolicy;

    // base = 100 ms, max = 10_000 ms → doubles 0→100, 1→200, 2→400,
    // 3→800, 4→1600, 5→3200, 6→6400, 7→12800 (clamped to 10_000).
    let policy = RetryPolicy::Exponential {
        base_ms: 100,
        max_ms: 10_000,
    };

    // 0 → base
    assert_eq!(
        policy.next_delay(0),
        100,
        "attempt 0 must return base_ms (100)"
    );
    // 1 → 2 * base
    assert_eq!(
        policy.next_delay(1),
        200,
        "attempt 1 must return 2 * base (200)"
    );
    // 5 → 32 * base = 3200
    assert_eq!(
        policy.next_delay(5),
        3200,
        "attempt 5 must return 32 * base (3200)"
    );
    // 10 → 1024 * base = 102_400 → clamped to max (10_000)
    assert_eq!(
        policy.next_delay(10),
        10_000,
        "attempt 10 must clamp to max_ms (10_000)"
    );

    // Fixed policy is a sanity anchor.
    let fixed = RetryPolicy::Fixed { delay_ms: 250 };
    assert_eq!(fixed.next_delay(0), 250);
    assert_eq!(fixed.next_delay(7), 250, "Fixed must be constant");
}

// ----------------------------------------------------------------
// 6. SkillStep ↔ PcStep roundtrip
// ----------------------------------------------------------------

#[test]
fn test_skill_step_to_pc_step_convert() {
    use crate::pc_automation::skill::convert::{from_pc_step, to_pc_step};
    use crate::pc_automation::skill::types::{
        ElementSelector, Selector, SelectorKind, SkillAction, SkillStep,
    };
    use crate::pc_automation::step::{PcStep, StepStrategy};

    // Build a SkillStep that will survive the roundtrip cleanly:
    // single primary selector, no fallbacks, no hooks, default action.
    let step = SkillStep {
        id: "rt_step".into(),
        description: "roundtrip".into(),
        intent: "intent".into(),
        element_selector: ElementSelector {
            version: "1.0".into(),
            primary: Selector {
                kind: SelectorKind::Cdp,
                value: "cdp:css=#submit".into(),
                stability_score: 0.9,
                context: Some("main".into()),
                match_threshold: None,
                resolution: None,
            },
            fallbacks: vec![],
            iframe_context: None,
            shadow_root_context: None,
        },
        action: SkillAction::Click,
        parameter: None,
        wait_condition: None,
        post_action_validation: None,
        interaction: None,
    };

    // SkillStep → PcStep
    let pc = to_pc_step(&step);
    assert_eq!(pc.id, "rt_step");
    assert_eq!(pc.primary_selector, "cdp:css=#submit");
    assert_eq!(pc.strategy, StepStrategy::Cdp);
    assert!(pc.fallback_selectors.is_empty());

    // PcStep → SkillStep → PcStep (roundtrip)
    let back = from_pc_step(&pc);
    let pc2 = to_pc_step(&back);
    assert_eq!(pc2.id, pc.id, "roundtripped id must match");
    assert_eq!(
        pc2.primary_selector, pc.primary_selector,
        "roundtripped primary_selector must match"
    );
    assert_eq!(
        pc2.strategy, pc.strategy,
        "roundtripped strategy must match"
    );
    assert_eq!(
        pc2.fallback_selectors, pc.fallback_selectors,
        "roundtripped fallback list must match"
    );

    // Additional spot check: a fresh PcStep → SkillStep mapping.
    let raw_pc = PcStep {
        id: "raw_pc".into(),
        description: "raw".into(),
        app_profile: None,
        strategy: StepStrategy::Ocr,
        primary_selector: "ocr:match=平安银行".into(),
        fallback_selectors: vec!["uia:name=行情".into()],
        recorded_coords: None,
    };
    let upgraded = from_pc_step(&raw_pc);
    assert_eq!(upgraded.id, "raw_pc");
    assert_eq!(
        upgraded.element_selector.primary.kind,
        SelectorKind::Ocr,
        "Ocr strategy must map to SelectorKind::Ocr"
    );
    assert_eq!(upgraded.element_selector.primary.value, "ocr:match=平安银行");
    assert_eq!(upgraded.element_selector.fallbacks.len(), 1);
    assert_eq!(upgraded.element_selector.fallbacks[0].value, "uia:name=行情");
}

// ----------------------------------------------------------------
// 7. WaitCondition::Delay — succeeds without backends
// ----------------------------------------------------------------

#[test]
fn test_wait_condition_delay() {
    use crate::pc_automation::executor::conditions::evaluate_wait_condition;
    use crate::pc_automation::executor::WaitCondition;
    use crate::pc_automation::router::PcRouter;
    use std::sync::Arc;

    // Build a minimal PcRouter from the stub backends so the
    // evaluator can be called. The Delay path never touches the
    // router — the router is just there to satisfy the signature.
    use crate::pc_automation::cdp::StubCdpBackend;
    use crate::pc_automation::ocr::StubOcrBackend;
    #[cfg(target_os = "windows")]
    use crate::pc_automation::uia::WindowsUiaBackend;
    use crate::pc_automation::uia::StubUiaBackend;

    let uia: Arc<dyn crate::pc_automation::uia::UiaBackend> = {
        #[cfg(target_os = "windows")]
        { Arc::new(WindowsUiaBackend) }
        #[cfg(not(target_os = "windows"))]
        { Arc::new(StubUiaBackend) }
    };
    let router = PcRouter::new(
        uia,
        Arc::new(StubCdpBackend) as Arc<dyn crate::pc_automation::cdp::CdpBackend>,
        Arc::new(StubOcrBackend) as Arc<dyn crate::pc_automation::ocr::OcrBackend>,
    );

    // 1. Zero-delay Delay: should return Ok(()) essentially
    //    immediately. We pass a 0 ms sleep so we don't add 1 ms
    //    jitter to the test wall-clock.
    let cond = WaitCondition::Delay { ms: 0 };
    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .expect("tokio runtime must build");
    let result = rt.block_on(evaluate_wait_condition(&cond, &router));
    assert!(
        result.is_ok(),
        "Delay {{ ms: 0 }} must succeed without touching backends: {:?}",
        result
    );

    // 2. 10 ms delay: also succeeds, in roughly that time.
    let cond2 = WaitCondition::Delay { ms: 10 };
    let result2 = rt.block_on(evaluate_wait_condition(&cond2, &router));
    assert!(
        result2.is_ok(),
        "Delay {{ ms: 10 }} must succeed: {:?}",
        result2
    );
}

// ----------------------------------------------------------------
// 8. VlmAction — parse from JSON string
// ----------------------------------------------------------------

#[test]
fn test_vlm_action_parse() {
    use crate::pc_automation::vlm_rescue::analyzer::{
        is_action_acceptable, VlmAction, DEFAULT_CONFIDENCE_THRESHOLD,
    };

    // 1. Minimal valid VlmAction payload.
    let json_str = r#"{
        "action": "click",
        "target": { "kind": "pixel", "x": 100, "y": 250 },
        "confidence": 0.82,
        "explanation": "button is at 100,250"
    }"#;
    let parsed: VlmAction = serde_json::from_str(json_str)
        .expect("VlmAction must parse from a well-formed JSON string");
    assert_eq!(parsed.action, "click");
    assert_eq!(parsed.target.kind, "pixel");
    assert_eq!(parsed.target.x, 100);
    assert_eq!(parsed.target.y, 250);
    assert!(
        (parsed.confidence - 0.82).abs() < f32::EPSILON,
        "confidence must round-trip: {}",
        parsed.confidence
    );
    assert_eq!(parsed.explanation, "button is at 100,250");

    // 2. Confidence gate: above threshold is accepted, below is not.
    assert!(
        is_action_acceptable(&parsed, DEFAULT_CONFIDENCE_THRESHOLD),
        "confidence 0.82 must clear the 0.6 threshold"
    );
    let mut low = parsed.clone();
    low.confidence = 0.4;
    assert!(
        !is_action_acceptable(&low, DEFAULT_CONFIDENCE_THRESHOLD),
        "confidence 0.4 must NOT clear the 0.6 threshold"
    );

    // 3. Round-trip through serde_json::Value.
    let value = serde_json::to_value(&parsed).expect("VlmAction → Value");
    assert_eq!(value["action"], "click");
    assert_eq!(value["target"]["x"], 100);
    let again: VlmAction = serde_json::from_value(value).expect("Value → VlmAction");
    assert_eq!(again, parsed);

    // 4. Malformed JSON must error gracefully.
    let bad = r#"{ "action": "click", "target": { "kind": "pixel", "x": 1 } }"#; // missing y
    let err = serde_json::from_str::<VlmAction>(bad)
        .expect_err("missing field 'y' must fail to parse");
    assert!(
        err.to_string().contains("y")
            || err.to_string().contains("target"),
        "error must mention the missing field: {}",
        err
    );
}
