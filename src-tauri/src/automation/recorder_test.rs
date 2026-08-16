// Copyright (c) 2026 MeeJoy
//
// Tests for the manual-teaching recorder (P2 §1).
//
// We don't spawn the rdev background thread here (it would try to grab
// global input on the test machine).  Instead we drive the recorder via
// `push` (a public helper intended for non-input event sources such as
// the browser-action hook) and verify the generated skill.md.

use crate::automation::recorder::{
    generate_skill_md, RecordedEvent, Recorder, RecordingStatus,
};

#[test]
fn recorder_starts_and_stops_cleanly() {
    let recorder = Recorder::new();
    // Initial state: idle.
    match recorder.status().expect("status") {
        RecordingStatus::Idle => {}
        other => panic!("expected Idle, got {:?}", other),
    }

    let _ = recorder.push(RecordedEvent::MouseClick {
        x: 10,
        y: 20,
        button: "left".into(),
        element: None,
    });
    // We didn't call start, so push should be a no-op.
    match recorder.status().expect("status") {
        RecordingStatus::Idle => {}
        other => panic!("expected Idle, got {:?}", other),
    }
}

#[test]
fn generate_skill_md_produces_yaml_for_clicks_and_keys() {
    let events = vec![
        RecordedEvent::MouseClick {
            x: 100,
            y: 200,
            button: "left".into(),
            element: None,
        },
        RecordedEvent::KeyPress {
            key: "\"h\"".into(),
        },
        RecordedEvent::KeyPress {
            key: "\"i\"".into(),
        },
        RecordedEvent::KeyPress {
            key: "Return".into(),
        },
    ];
    let yaml = generate_skill_md(&events);
    assert!(yaml.contains("name: new_skill"));
    assert!(yaml.contains("software_name: \"recorded\""));
    assert!(yaml.contains("steps:"));
    // Click step exists (action type renamed: `action:` -> `input:`).
    assert!(yaml.contains("type: click"), "yaml was:\n{}", yaml);
    assert!(yaml.contains("x: 100"));
    assert!(yaml.contains("y: 200"));
    // The two printable keys should collapse into a single `type` step.
    // (action type renamed: `type_text` -> `type`.)
    assert!(yaml.contains("type: type"), "yaml was:\n{}", yaml);
    assert!(yaml.contains("text: \"hi\""));
    // Return should be emitted as a hotkey step.
    assert!(yaml.contains("type: hotkey"), "yaml was:\n{}", yaml);
}

#[test]
fn generate_skill_md_handles_browser_action() {
    let events = vec![RecordedEvent::BrowserAction {
        url: "https://example.com".into(),
        selector: "#login".into(),
    }];
    let yaml = generate_skill_md(&events);
    // `InputAction` has no browser variant, so browser actions are
    // emitted as description-only steps.
    assert!(yaml.contains("browser: url=https://example.com"), "yaml was:\n{}", yaml);
    assert!(yaml.contains("selector=#login"));
}

#[test]
fn generate_skill_md_empty_input_is_well_formed() {
    let yaml = generate_skill_md(&[]);
    // We always emit a header + an empty `steps:` list, even with no
    // events.  This keeps the contract simple for callers.
    assert!(yaml.contains("steps:"));
    assert!(!yaml.contains("- id:"));
}
