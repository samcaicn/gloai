// Copyright (c) 2026 tupAI
//
// UIRPA unit tests for the `pc_automation::skill`
// tree. Mirrors the `pc_automation/pc_automation_tests.rs`
// sibling-file pattern: the test module is `#[path]`-included
// from `mod.rs` so the production barrel stays clean.
//
// Coverage map:
//   * skill serialize roundtrip      → `skill_serde_roundtrip`
//   * decryptor encrypt/decrypt     → `decryptor_roundtrip`
//   * template render + missing     → `template_simple`,
//                                     `template_missing_param_errors`
//   * convert skill ↔ pc step        → `convert_skill_step_roundtrip`
//   * storage temp-dir CRUD          → `storage_store_load_delete`
//   * (bonus) registry, types re-exports, Branch stub, selector kind
//
// The storage test uses a `tempfile::tempdir()` so it leaves no
// artefacts behind; it is the only test that touches the file
// system.

use super::*;
use crate::pc_automation::skill::types::{
    ElementSelector, ParamType, Parameter, Selector, SelectorKind, Skill, SkillAction,
    SkillStep, TemplateString,
};
use crate::pc_automation::step::StepStrategy;
use serde_json::json;
use std::collections::HashMap;
use tempfile::tempdir;

// =============================================================
// 1. Skill (de)serialise round-trip
// =============================================================

#[test]
fn skill_serde_roundtrip() {
    let skill = Skill {
        skill_id: "skill_abc".to_string(),
        version: "1.0.0".to_string(),
        intent: "提交订单".to_string(),
        scene_fingerprint: Some("sha256:deadbeef".to_string()),
        created_at: chrono::Utc::now(),
        updated_at: chrono::Utc::now(),
        success_rate: 0.87,
        avg_execution_time_ms: 1234,
        parameters: vec![Parameter {
            name: "customer_name".to_string(),
            param_type: ParamType::String,
            required: true,
            default: None,
        }],
        steps: vec![SkillStep::single(
            "step_1",
            "点提交按钮",
            "uia:controlType=Button;name=提交",
        )],
        error_handlers: Vec::new(),
        branches: Vec::new(),
        name: "skill-abc".to_string(),
        description: "提交订单自动化技能".to_string(),
        license: None,
    };

    let json = serde_json::to_string(&skill).expect("serialize");
    // camelCase check — the front-end relies on this.
    assert!(json.contains("\"skillId\""), "expected camelCase, got: {}", json);
    assert!(json.contains("\"avgExecutionTimeMs\""));
    assert!(json.contains("\"sceneFingerprint\""));
    // The Parameter struct renames `param_type` to `type` on the wire.
    assert!(json.contains("\"type\":\"string\""));

    let back: Skill = serde_json::from_str(&json).expect("deserialize");
    assert_eq!(back.skill_id, skill.skill_id);
    assert_eq!(back.intent, skill.intent);
    assert_eq!(back.parameters.len(), 1);
    assert_eq!(back.parameters[0].name, "customer_name");
    assert_eq!(back.parameters[0].param_type, ParamType::String);
    assert_eq!(back.steps.len(), 1);
    assert_eq!(back.steps[0].element_selector.primary.kind, SelectorKind::Uia);
}

// =============================================================
// 2. SkillDecryptor encrypt / decrypt
// =============================================================

#[test]
fn decryptor_roundtrip() {
    let key = [7u8; 32];
    let d = SkillDecryptor::new(key);
    let plaintext = b"the quick brown fox jumps over the lazy dog";

    let (ciphertext, nonce, tag) = d.encrypt(plaintext).expect("encrypt");
    assert_eq!(ciphertext.len(), plaintext.len(), "ciphertext carries no tag");
    assert_eq!(nonce.len(), 12);
    assert_eq!(tag.len(), 16);
    assert_ne!(ciphertext, plaintext.to_vec(), "ciphertext must differ from plaintext");

    let back = d.decrypt(&ciphertext, &nonce, &tag).expect("decrypt");
    assert_eq!(back, plaintext);

    // Wrong key → tag verification fails.
    let bad = SkillDecryptor::new([9u8; 32]);
    let err = bad.decrypt(&ciphertext, &nonce, &tag);
    assert!(err.is_err(), "wrong key must fail to decrypt");

    // Wrong tag → tag verification fails.
    let mut bad_tag = tag;
    bad_tag[0] ^= 0xff;
    let err = d.decrypt(&ciphertext, &nonce, &bad_tag);
    assert!(err.is_err(), "tampered tag must fail to decrypt");
}

// =============================================================
// 3. Template rendering — happy path + missing param
// =============================================================

#[test]
fn template_simple() {
    let mut params = serde_json::Map::new();
    params.insert("name".to_string(), json!("Alice"));
    params.insert("age".to_string(), json!(30));
    params.insert("active".to_string(), json!(true));

    let cases = [
        ("hello {{name}}", "hello Alice"),
        ("age={{age}}", "age=30"),
        ("active={{active}}", "active=true"),
        ("{{name}} is {{age}}", "Alice is 30"),
        ("no placeholders here", "no placeholders here"),
        ("{{ name }} with whitespace", "Alice with whitespace"),
        ("", ""),
    ];
    for (template, expected) in cases {
        let got = render_template(template, &params).expect("render");
        assert_eq!(got, expected, "template: {:?}", template);
    }
}

#[test]
fn template_missing_param_errors() {
    let mut params = serde_json::Map::new();
    params.insert("name".to_string(), json!("Alice"));
    let err = render_template("hello {{missing}}", &params).unwrap_err();
    assert!(err.contains("missing parameter"), "got: {}", err);
    assert!(err.contains("missing"));
}

#[test]
fn template_dotted_param_errors() {
    let mut params = serde_json::Map::new();
    params.insert("name".to_string(), json!("Alice"));
    let err = render_template("hello {{name.first}}", &params).unwrap_err();
    assert!(err.contains("dotted placeholder"), "got: {}", err);
}

#[test]
fn template_unterminated_placeholder_errors() {
    let params = serde_json::Map::new();
    let err = render_template("hello {{name", &params).unwrap_err();
    assert!(err.contains("unterminated"));
}

// =============================================================
// 3b. Track F — interaction_vars flow into template rendering.
// Mirrors the merge that `executor::execute_skill` builds before
// calling `execute_skill_action`: skill static `params` + runtime
// `interaction_vars` (latter overrides former). Verifies a later
// step's `{{bind_to_var}}` placeholder is substituted with the
// user's prompt answer, and that interaction overrides params on
// key collision. This is the unit-testable core of Track F; the
// full path (enigo typing) is covered by integration tests.
// =============================================================

#[test]
fn template_interaction_vars_merge_and_override() {
    // Static skill params (declared in the skill's `parameters`).
    let mut params = serde_json::Map::new();
    params.insert("default_target".to_string(), json!("params-value"));
    params.insert("shared_key".to_string(), json!("from-params"));

    // Runtime interaction_vars (populated by `automation:ask_user`
    // prompts during execution — see `executor::execute_skill`).
    let mut interaction_vars = serde_json::Map::new();
    interaction_vars.insert("user_answer".to_string(), json!("Alice"));
    interaction_vars.insert("shared_key".to_string(), json!("from-interaction"));

    // Merge: interaction_vars overrides params (mirrors executor logic).
    let mut render_ctx = params.clone();
    for (k, v) in &interaction_vars {
        render_ctx.insert(k.clone(), v.clone());
    }

    // 1. interaction var is substituted (Track F core scenario).
    assert_eq!(
        render_template("hello {{user_answer}}", &render_ctx).unwrap(),
        "hello Alice",
    );

    // 2. static param is substituted (backward compatible).
    assert_eq!(
        render_template("target={{default_target}}", &render_ctx).unwrap(),
        "target=params-value",
    );

    // 3. collision: interaction wins over params.
    assert_eq!(
        render_template("{{shared_key}}", &render_ctx).unwrap(),
        "from-interaction",
    );

    // 4. both sources in one template (realistic step value).
    assert_eq!(
        render_template(
            "{{user_answer}} -> {{shared_key}} ({{default_target}})",
            &render_ctx,
        )
        .unwrap(),
        "Alice -> from-interaction (params-value)",
    );

    // 5. missing var still errors (fail-fast, not silent empty).
    let err = render_template("{{not_bound}}", &render_ctx).unwrap_err();
    assert!(err.contains("missing parameter"), "got: {}", err);
}

// =============================================================
// 4. SkillStep ↔ PcStep
// =============================================================

#[test]
fn convert_skill_step_roundtrip() {
    // Build a SkillStep with primary + two fallbacks.
    let step = SkillStep {
        id: "step_x".to_string(),
        description: "提交订单按钮".to_string(),
        intent: "提交".to_string(),
        element_selector: ElementSelector {
            version: "1.0".to_string(),
            primary: Selector {
                kind: SelectorKind::Cdp,
                value: "cdp:css=.btn-primary".to_string(),
                stability_score: 0.95,
                context: Some("main".to_string()),
                match_threshold: None,
                resolution: None,
            },
            fallbacks: vec![
                Selector {
                    kind: SelectorKind::Uia,
                    value: "uia:name=提交".to_string(),
                    stability_score: 0.8,
                    context: None,
                    match_threshold: None,
                    resolution: None,
                },
                Selector {
                    kind: SelectorKind::Ocr,
                    value: "ocr:match=提交".to_string(),
                    stability_score: 0.5,
                    context: None,
                    match_threshold: Some(0.8),
                    resolution: None,
                },
            ],
            iframe_context: None,
            shadow_root_context: None,
        },
        action: SkillAction::Click,
        parameter: Some(TemplateString::from("{{customer_name}}")),
        wait_condition: None,
        post_action_validation: None,
        interaction: None,
    };

    // to_pc_step → primary_selector + fallback_selectors list
    let pc = to_pc_step(&step);
    assert_eq!(pc.id, "step_x");
    assert_eq!(pc.description, "提交订单按钮");
    assert_eq!(pc.strategy, StepStrategy::Cdp);
    assert_eq!(pc.primary_selector, "cdp:css=.btn-primary");
    assert_eq!(pc.app_profile.as_deref(), Some("main"));
    assert_eq!(pc.fallback_selectors.len(), 2);
    assert_eq!(pc.fallback_selectors[0], "uia:name=提交");
    assert_eq!(pc.fallback_selectors[1], "ocr:match=提交");

    // from_pc_step → ElementSelector round-trip
    let back = from_pc_step(&pc);
    assert_eq!(back.id, "step_x");
    assert_eq!(back.description, "提交订单按钮");
    assert_eq!(
        back.element_selector.primary.kind,
        SelectorKind::Cdp,
        "strategy must round-trip to SelectorKind"
    );
    assert_eq!(back.element_selector.primary.value, "cdp:css=.btn-primary");
    assert_eq!(back.element_selector.fallbacks.len(), 2);
    // Legacy fallback kinds are inferred from the original
    // strategy, since v5 had no per-fallback type.
    for f in &back.element_selector.fallbacks {
        assert_eq!(f.kind, SelectorKind::Cdp);
    }
    // No action vocabulary → default Wait.
    assert!(matches!(back.action, SkillAction::Wait { ms: 0 }));
}

#[test]
fn to_pc_steps_splits_all_steps() {
    let mut skill = Skill::single_step("s1", "intent", "uia:name=hello");
    // `SkillStep::single` always produces a Uia primary selector
    // (it's a quick-builder for tests / demo data). To exercise
    // the strategy mapping for a non-Uia step, hand-build the
    // second one with `SelectorKind::Cdp` directly.
    let cdp_step = SkillStep {
        id: "step_2".to_string(),
        description: "second".to_string(),
        intent: String::new(),
        element_selector: ElementSelector {
            version: "1.0".to_string(),
            primary: Selector {
                kind: SelectorKind::Cdp,
                value: "cdp:css=.ok".to_string(),
                stability_score: 1.0,
                context: None,
                match_threshold: None,
                resolution: None,
            },
            fallbacks: Vec::new(),
            iframe_context: None,
            shadow_root_context: None,
        },
        action: SkillAction::Wait { ms: 0 },
        parameter: None,
        wait_condition: None,
        post_action_validation: None,
        interaction: None,
    };
    skill.steps.push(cdp_step);
    let pcs = to_pc_steps(&skill);
    assert_eq!(pcs.len(), 2);
    assert_eq!(pcs[0].id, "step_1");
    assert_eq!(pcs[0].strategy, StepStrategy::Uia);
    assert_eq!(pcs[1].id, "step_2");
    assert_eq!(pcs[1].strategy, StepStrategy::Cdp);
}

// =============================================================
// 5. LocalSkillStorage — store / load / delete in a temp dir
// =============================================================

#[test]
fn storage_store_load_delete() {
    let dir = tempdir().expect("tempdir");
    let storage = LocalSkillStorage::new(dir.path());

    let skill = Skill::single_step("skill_xyz", "测试", "uia:name=测试");
    let password = b"correct horse battery staple";

    let path = storage.store(&skill, password).expect("store");
    assert!(path.exists(), "encrypted file must be on disk");
    assert!(path.ends_with("skill_xyz.enc"));

    // Load + decrypt.
    let back = storage.load(&path, password).expect("load");
    assert_eq!(back.skill_id, skill.skill_id);
    assert_eq!(back.intent, skill.intent);
    assert_eq!(back.steps.len(), 1);
    assert_eq!(
        back.steps[0].element_selector.primary.value,
        "uia:name=测试"
    );

    // Wrong password → opaque error.
    let err = storage.load(&path, b"wrong");
    assert!(err.is_err(), "wrong password must fail");

    // list() returns the metadata.
    let metas = storage.list(password).expect("list");
    assert_eq!(metas.len(), 1);
    assert_eq!(metas[0].skill_id, "skill_xyz");
    assert_eq!(metas[0].intent, "测试");

    // Delete is idempotent.
    storage.delete("skill_xyz").expect("delete");
    assert!(!path.exists());
    storage.delete("skill_xyz").expect("delete again is fine");
}

// =============================================================
// 6. Registry: insert / get / list / delete
// =============================================================

#[test]
fn registry_insert_get_delete() {
    let dir = tempdir().expect("tempdir");
    let reg = SkillRegistry::new(dir.path());
    let password = b"pw";

    // Fresh cache → get returns None.
    assert!(reg.get("ghost").is_none());

    let skill = Skill::single_step("reg_1", "测试", "uia:name=按钮");
    reg.insert(&skill, password).expect("insert");
    assert!(reg.storage().path_for("reg_1").exists());

    // Get reads from the cache populated by insert.
    let got = reg.get("reg_1").expect("get after insert");
    assert_eq!(got.skill_id, "reg_1");

    // list() reads from disk and decrypts metadata.
    let metas = reg.list(password).expect("list");
    assert_eq!(metas.len(), 1);
    assert_eq!(metas[0].skill_id, "reg_1");

    // update_success_rate is in-memory; second get sees the new value.
    reg.update_success_rate("reg_1", 0.42).expect("update");
    let got = reg.get("reg_1").expect("get after update");
    assert!((got.success_rate - 0.42).abs() < 1e-6);

    // delete removes from cache + disk.
    reg.delete("reg_1").expect("delete");
    assert!(reg.get("reg_1").is_none());
    assert!(!reg.storage().path_for("reg_1").exists());
}

#[test]
fn registry_refresh_loads_from_disk() {
    let dir = tempdir().expect("tempdir");
    let reg = SkillRegistry::new(dir.path());
    let password = b"pw";

    // Insert via one registry, then create a *second* registry
    // pointing at the same dir to simulate a fresh process.
    let skill = Skill::single_step("reg_refresh", "测试", "uia:name=ok");
    reg.insert(&skill, password).expect("insert");

    let reg2 = SkillRegistry::new(dir.path());
    assert!(reg2.get("reg_refresh").is_none(), "fresh cache");
    let count = reg2.refresh(password).expect("refresh");
    assert_eq!(count, 1);
    assert!(reg2.get("reg_refresh").is_some());
}

// =============================================================
// 7. Branch stub: serialises and is otherwise inert
// =============================================================

#[test]
fn branch_struct_roundtrips() {
    use crate::pc_automation::skill::types::Branch;
    let branch = Branch {
        condition: "success_rate < 0.5".to_string(),
        steps: vec![SkillStep::single("br_step", "branch step", "uia:name=ok")],
    };
    let json = serde_json::to_string(&branch).expect("serialize");
    assert!(json.contains("\"condition\""));
    assert!(json.contains("\"steps\""));
    let back: Branch = serde_json::from_str(&json).expect("deserialize");
    assert_eq!(back.condition, "success_rate < 0.5");
    assert_eq!(back.steps.len(), 1);
}

// =============================================================
// 8. Public API surface (smoke test for the re-exports in mod.rs)
// =============================================================

#[test]
fn reexports_are_in_scope() {
    // Touch every public name from `pc_automation::skill::*` to
    // make sure the barrel re-exports stay in sync with the
    // modules. If a future PR renames a type and forgets to
    // re-export it, this test fails to compile.
    let _: Box<dyn FnOnce() -> Result<Skill, String>> = Box::new(|| {
        Ok(Skill::single_step("e", "i", "uia:name=ok"))
    });
    let _: Vec<Parameter> = Vec::new();
    let _: ParamType = ParamType::Boolean;
    let mut map: HashMap<String, Skill> = HashMap::new();
    map.insert("k".to_string(), Skill::single_step("k", "i", "uia:name=k"));
    assert_eq!(map.len(), 1);
    let _ = SkillDecryptor::new([1u8; 32]);
    let _ = LocalSkillStorage::new(std::path::Path::new("."));
    let _ = SkillRegistry::new(std::path::Path::new("."));
    let _ = render_template;
    let _ = to_pc_step;
    let _ = to_pc_steps;
    let _ = from_pc_step;
}
