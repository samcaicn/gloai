// Copyright (c) 2026 MeeJoy
//
// Tests for the self-healing framework (P2 §2).

use crate::automation::healing::{FailureContext, HealResult, HealingEngine};

fn ctx_with_xy(x: i32, y: i32) -> FailureContext {
    FailureContext {
        step_index: 1,
        description: "clicked on stale coordinates".into(),
        expected_x: Some(x),
        expected_y: Some(y),
        expected_text: None,
    }
}

#[test]
fn light_heal_recovers_coordinate_drift() {
    let engine = HealingEngine::new();
    engine.set_mode("light").unwrap();
    let result = engine.attempt_heal("skill-1", &ctx_with_xy(420, 318)).unwrap();
    match result {
        HealResult::Healed { offset_x, offset_y, .. } => {
            // Fake vision lookup bounds the offset to |x| ≤ 10, |y| ≤ 6.
            assert!(offset_x.abs() <= 10, "offset_x out of range: {}", offset_x);
            assert!(offset_y.abs() <= 6, "offset_y out of range: {}", offset_y);
        }
        other => panic!("expected Healed, got {:?}", other),
    }
}

#[test]
fn fuzzy_text_fallback_heals_when_no_coordinates() {
    let engine = HealingEngine::new();
    engine.set_mode("light").unwrap();
    let ctx = FailureContext {
        step_index: 2,
        description: "label changed".into(),
        expected_x: None,
        expected_y: None,
        expected_text: Some("Submit".into()),
    };
    let result = engine.attempt_heal("skill-2", &ctx).unwrap();
    match result {
        HealResult::Healed { reason, .. } => {
            assert!(reason.contains("fuzzy-text"), "reason was: {}", reason);
        }
        other => panic!("expected Healed, got {:?}", other),
    }
}

#[test]
fn off_mode_always_reports_failed() {
    let engine = HealingEngine::new();
    engine.set_mode("off").unwrap();
    let result = engine.attempt_heal("skill-3", &ctx_with_xy(10, 10)).unwrap();
    match result {
        HealResult::Failed { reason } => {
            assert!(reason.contains("off"), "reason was: {}", reason);
        }
        other => panic!("expected Failed, got {:?}", other),
    }
}

#[test]
fn retry_budget_eventually_returns_needs_reparse() {
    let engine = HealingEngine::new();
    engine.set_mode("light").unwrap();

    // Build a context that the light healer can NOT recover from
    // (no coordinates, no text).  After 3 attempts we should see
    // NeedsReparse.
    let ctx = FailureContext {
        step_index: 5,
        description: "irrecoverable".into(),
        expected_x: None,
        expected_y: None,
        expected_text: None,
    };

    // We don't care about intermediate `Failed` results — only the
    // terminal outcome.
    let mut final_outcome: Option<HealResult> = None;
    for _ in 0..6 {
        match engine.attempt_heal("skill-4", &ctx).unwrap() {
            HealResult::NeedsReparse { .. } => {
                final_outcome = Some(HealResult::NeedsReparse { reason: "x".into() });
                break;
            }
            _ => continue,
        }
    }
    assert!(
        matches!(final_outcome, Some(HealResult::NeedsReparse { .. })),
        "healer never escalated to NeedsReparse"
    );
}

#[test]
fn history_records_heal_outcomes() {
    let engine = HealingEngine::new();
    engine.set_mode("light").unwrap();

    let ctx = ctx_with_xy(123, 456);
    let _ = engine.attempt_heal("skill-h", &ctx).unwrap();
    let history = engine.history(10).unwrap();
    assert!(!history.is_empty());
    assert_eq!(history[0].skill_id, "skill-h");
    assert_eq!(history[0].step_index, 1);
}

// AIMarketing P2 §2 — deep 模式路由 + 凌晨 2 点归集支撑

#[test]
fn set_mode_deep_round_trips_through_current_mode() {
    let engine = HealingEngine::new();
    engine.set_mode("deep").unwrap();
    assert_eq!(engine.current_mode(), "deep");
}

#[test]
fn set_mode_unknown_falls_back_to_light() {
    let engine = HealingEngine::new();
    engine.set_mode("not-a-real-mode").unwrap();
    assert_eq!(engine.current_mode(), "light");
}

#[test]
fn attempt_deep_heal_returns_deep_pending_variant() {
    let engine = HealingEngine::new();
    engine.set_mode("deep").unwrap();
    let ctx = FailureContext {
        step_index: 7,
        description: "ui element unrecognisable".into(),
        expected_x: Some(200),
        expected_y: Some(150),
        expected_text: Some("Submit".into()),
    };
    let result = engine.attempt_deep_heal("skill-deep", &ctx);
    match result {
        HealResult::DeepPending { skill_id, reason } => {
            assert_eq!(skill_id, "skill-deep");
            assert!(
                reason.contains("deep"),
                "reason should mention deep re-parse, got: {}",
                reason
            );
        }
        other => panic!("expected DeepPending, got {:?}", other),
    }

    // 写回 history（凌晨 2 点归集会从这里读取）
    let history = engine.history(10).unwrap();
    assert!(!history.is_empty());
    let last = &history[0];
    assert_eq!(last.skill_id, "skill-deep");
    assert_eq!(last.outcome, "deep_pending");
}
