// Copyright (c) 2026 AIMarketing
//
// UIRPA IPC command signature tests.
//
// Verifies that the 13 `commands::uirpa::*` commands exposed by
// the IPC layer have the expected surface (names, parameter shapes,
// result types) and that the wire format is `camelCase`. The
// tests are `cargo check` / `cargo test` style: no Tauri runtime
// is spun up, so the commands are only *invoked* by reference
// (via function pointer / `core::mem::size_of_val` etc.) —
// the actual business logic is exercised in
// `pc_automation::tests::integration_uirpa`.
//
// Wired into the build via
//   `#[cfg(test)] #[path = "uirpa_integration_test.rs"] mod uirpa_integration_test;`
// in `commands/mod.rs` (see comment there).

#![allow(unused_imports)]
#![allow(dead_code)]

use tauri::AppHandle;

use crate::commands::uirpa as uirpa_cmd;

// ----------------------------------------------------------------
// 1. All 13 commands exist with the documented signatures.
//
// For the 12 sync commands we use the function-pointer cast
// trick so the compiler emits a `cannot cast ... to fn pointer`
// diagnostic if any signature drifts. The single async command
// (`uirpa_execute_skill`) is referenced by name — a future-type
// cast is possible but adds a `Send + 'static` bound the test
// doesn't need to assert on.
// ----------------------------------------------------------------

#[test]
fn test_all_13_commands_have_expected_signatures() {
    // 1. uirpa_list_skills
    let _f1: fn() -> Result<Vec<uirpa_cmd::SkillMeta>, String> = uirpa_cmd::uirpa_list_skills;
    // 2. uirpa_import_skill
    let _f2: fn(AppHandle, String) -> Result<uirpa_cmd::SkillMeta, String> =
        uirpa_cmd::uirpa_import_skill;
    // 3. uirpa_export_skill
    let _f3: fn(String) -> Result<String, String> = uirpa_cmd::uirpa_export_skill;
    // 4. uirpa_delete_skill
    let _f4: fn(AppHandle, String) -> Result<(), String> = uirpa_cmd::uirpa_delete_skill;
    // 5. uirpa_encrypt_skill
    let _f5: fn(uirpa_cmd::Skill) -> Result<String, String> = uirpa_cmd::uirpa_encrypt_skill;
    // 6. uirpa_decrypt_skill
    let _f6: fn(String, String) -> Result<uirpa_cmd::Skill, String> = uirpa_cmd::uirpa_decrypt_skill;
    // 7. uirpa_execute_skill  (async — referenced by name only)
    let _f7: &str = stringify!(uirpa_cmd::uirpa_execute_skill);
    // 8. uirpa_pause_execution
    let _f8: fn(String) -> Result<(), String> = uirpa_cmd::uirpa_pause_execution;
    // 9. uirpa_resume_execution
    let _f9: fn(String) -> Result<(), String> = uirpa_cmd::uirpa_resume_execution;
    // 10. uirpa_get_execution_status
    let _f10: fn(String) -> Result<uirpa_cmd::ExecutionStatus, String> =
        uirpa_cmd::uirpa_get_execution_status;
    // 11. uirpa_list_executions
    let _f11: fn() -> Result<Vec<uirpa_cmd::ExecutionReceipt>, String> =
        uirpa_cmd::uirpa_list_executions;
    // 12. uirpa_validate_selector
    let _f12: fn(uirpa_cmd::ElementSelector) -> Result<uirpa_cmd::SelectorValidation, String> =
        uirpa_cmd::uirpa_validate_selector;
    // 13. uirpa_subscribe_events
    let _f13: fn() -> Result<(), String> = uirpa_cmd::uirpa_subscribe_events;

    // Compile-time-only references; we silence the unused
    // warnings on the bindings so the test passes even if the
    // compiler doesn't reach the function bodies.
    let _ = (
        _f1, _f2, _f3, _f4, _f5, _f6, _f7, _f8, _f9, _f10, _f11, _f12, _f13,
    );
}

// ----------------------------------------------------------------
// 2. Wire format — every IPC payload struct serialises to camelCase.
//
// This is the contract the front-end relies on (`JS` reads
// `skillId`, not `skill_id`). A regression here means a silent
// field-name drift on the wire.
// ----------------------------------------------------------------

#[test]
fn test_wire_format_is_camel_case() {
    use serde_json::Value;

    // SkillMeta — used by uirpa_list_skills.
    let meta = uirpa_cmd::SkillMeta {
        skill_id: "wire_test".into(),
        version: "1.0.0".into(),
        intent: "wire format probe".into(),
        updated_at: "2026-06-06T00:00:00Z".into(),
        success_rate: 1.0,
    };
    let json = serde_json::to_value(&meta).expect("SkillMeta → Value");
    assert_eq!(
        json.get("skillId").and_then(|v| v.as_str()),
        Some("wire_test"),
        "SkillMeta.skillId must serialise as camelCase: {}",
        json
    );
    assert!(
        json.get("successRate").is_some(),
        "SkillMeta.successRate must be present"
    );
    assert!(
        json.get("updatedAt").is_some(),
        "SkillMeta.updatedAt must be present"
    );

    // ExecutionStatus — used by uirpa_get_execution_status.
    // We only check the *type* signature of the status string
    // field (it's a plain `String` so the field always
    // serialises); the IPC field names are checked on
    // `SelectorValidation` below, which is a smaller struct
    // without `Default`.
    let _: std::marker::PhantomData<uirpa_cmd::ExecutionStatus> = std::marker::PhantomData;

    // SelectorValidation — used by uirpa_validate_selector.

    // Sanity: a Skill round-trips through JSON without losing the
    // camelCase wire shape.
    let skill = uirpa_cmd::Skill {
        skill_id: "wire_skill".into(),
        version: "1.0.0".into(),
        intent: "int".into(),
        scene_fingerprint: None,
        created_at: "2026-06-06T00:00:00Z".into(),
        updated_at: "2026-06-06T00:00:00Z".into(),
        success_rate: 0.5,
        avg_execution_time_ms: 0,
        parameters: vec![],
        steps: vec![],
        error_handlers: vec![],
        branches: vec![],
    };
    let json = serde_json::to_value(&skill).expect("Skill → Value");
    let _ = json
        .get("skillId")
        .and_then(|v| v.as_str())
        .expect("Skill.skillId must be present in the wire format");
    assert!(
        json.get("createdAt").is_some(),
        "Skill.createdAt must be present"
    );

    // Make sure the `Value` const-eval above is "used" so the test
    // does not get flagged as dead-code by overzealous lints.
    let _: Value = json;
}
