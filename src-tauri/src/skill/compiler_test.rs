// Copyright (c) 2026 AIMarketing
//
// AIMarketing P0 §1 — Compiler round-trip tests.
//
// These tests live in a dedicated file (per the project
// `plan.md §3.2` task spec) so the engine's behavioural tests in
// `automation::engine_test` aren't tangled with the schema's
// serialization tests. The file is gated by `#[cfg(test)]` from
// the parent `mod.rs`.

use crate::skill::compiler::{compile_skill_md, decompile_mcp, decompile_to_skill_md};
use crate::skill::manifest::{ExecutionType, SkillManifest};

const SAMPLE_SOFTWARE: &str = r#"
name: greet
description: Greet the user via notepad
preferred_execution_type: system_software
software_name: notepad.exe
steps:
  - id: launch
    description: Launch notepad
  - id: type
    description: Type greeting
    visual_target: Editor
    input:
      type: type
      text: "Hello from AIMarketing"
"#;

const SAMPLE_BROWSER: &str = r#"
name: open-docs
description: Open the AIMarketing docs page
preferred_execution_type: browser
browser_url: https://example.com/docs
steps:
  - id: navigate
    description: Navigate to the URL
    dom_selector: "body"
    input:
      type: wait
      ms: 200
"#;

#[test]
fn compile_then_decompile_round_trip() {
    let compiled = compile_skill_md(SAMPLE_SOFTWARE).expect("compile must succeed");
    assert!(!compiled.mcp_binary.is_empty());
    assert_eq!(compiled.manifest.name, "greet");
    assert_eq!(compiled.manifest.steps.len(), 2);

    let decompiled = decompile_mcp(&compiled.mcp_binary).expect("decompile must succeed");
    assert_eq!(decompiled.manifest.name, "greet");
    assert_eq!(decompiled.manifest.steps.len(), 2);
    assert_eq!(decompiled.manifest.steps[0].id, "launch");
    assert_eq!(decompiled.manifest.steps[1].id, "type");
    assert_eq!(decompiled.original_size, compiled.mcp_binary.len());
}

#[test]
fn decompile_to_skill_md_round_trip_preserves_execution_type() {
    let compiled = compile_skill_md(SAMPLE_BROWSER).expect("compile must succeed");
    let yaml = decompile_to_skill_md(&compiled.mcp_binary).expect("yaml must decode");
    let reparsed = SkillManifest::from_skill_md(&yaml).expect("reparse must succeed");
    assert_eq!(reparsed.name, "open-docs");
    assert_eq!(reparsed.preferred_execution_type, ExecutionType::Browser);
    assert_eq!(reparsed.browser_url.as_deref(), Some("https://example.com/docs"));
    assert_eq!(reparsed.steps.len(), 1);
    assert_eq!(reparsed.steps[0].dom_selector.as_deref(), Some("body"));
}

#[test]
fn rejects_corrupted_magic() {
    let compiled = compile_skill_md(SAMPLE_SOFTWARE).unwrap();
    let mut corrupted = compiled.mcp_binary.clone();
    corrupted[0] = 0x00;
    let err = decompile_mcp(&corrupted).expect_err("bad magic must fail");
    assert!(err.contains("magic"), "unexpected error: {}", err);
}

#[test]
fn rejects_truncated_mcp() {
    let err = decompile_mcp(&[0x4D, 0x43, 0x50, 0x31]).expect_err("empty body must fail");
    assert!(err.contains("too short"), "unexpected error: {}", err);
}

#[test]
fn rejects_manifest_missing_required_field() {
    // Browser type but no browser_url — validation must reject.
    let src = r#"
name: bad
preferred_execution_type: browser
steps:
  - id: open
    description: open
"#;
    let err = compile_skill_md(src).expect_err("must reject");
    assert!(err.contains("browser_url"), "unexpected error: {}", err);
}
