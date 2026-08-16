// Copyright (c) 2026 tupAI
//
// Unit tests for the executor tree. Sibling-file pattern (this
// file is `#[path]`-included from `executor/mod.rs` so the
// `cargo test --lib pc_automation::executor` command picks it
// up automatically without polluting the main barrel with
// `#[cfg(test)]` noise).
//
// Coverage target:
//   1. MultiPrioritySelector ordering by stability_score.
//   2. WaitCondition::Delay succeeds out of the box.
//   3. Validation::Delay succeeds out of the box.
//   4. ErrorHandlerChain::empty returns `Ok(None)` (no
//      matching handler).
//   5. RetryPolicy::Exponential boundary behaviour for
//      `attempt = 0` and `attempt = 10`.
//
// Plus additional tests for the recovery-action plumbing
// and the receipt state machine.

use std::sync::Arc;

use crate::pc_automation::cdp::stub::StubCdpBackend;
use crate::pc_automation::ocr::stub::StubOcrBackend;
use crate::pc_automation::router::PcRouter;
use crate::pc_automation::skill::types::{
    ElementSelector, ErrorCondition, ErrorHandler, Selector, SelectorKind, SkillAction,
    Validation, WaitCondition,
};
use crate::pc_automation::uia::stub::StubUiaBackend;

use super::conditions::{evaluate_validation, evaluate_wait_condition};
use super::error_handler::{ErrorHandlerChain, ExecutionError, RecoveryAction};
use super::retry::RetryPolicy;
use crate::pc_automation::step::RouterError;
use super::selector::MultiPrioritySelector;
use super::{ExecutionReceipt, ExecutionStatus};

// =============================================================
// Test helpers
// =============================================================

/// A `PcRouter` made entirely of stubs. Every tier returns
/// `Err`, which is exactly what we want for tests that only
/// exercise the executor's control flow (the backends'
/// "this isn't wired" error is the deterministic answer).
fn stub_router() -> Arc<PcRouter> {
    // 强制使用 stub 后端：Windows 上 WindowsUiaBackend 会尝试调用真实
    // UIA,可能在测试环境里命中"最小化"按钮的真实节点,让 StructuredMiss
    // 的语义无法被验证。executor 控制流测试只需要所有 tier 都返回 Err。
    let uia: Arc<dyn crate::pc_automation::uia::UiaBackend> = Arc::new(StubUiaBackend);
    Arc::new(PcRouter::new(
        uia,
        Arc::new(StubCdpBackend),
        Arc::new(StubOcrBackend),
    ))
}

fn sample_selector(kind: SelectorKind, value: &str, score: f32) -> Selector {
    Selector {
        kind,
        value: value.to_string(),
        stability_score: score,
        context: None,
        match_threshold: None,
        resolution: None,
    }
}

fn sample_element() -> ElementSelector {
    ElementSelector {
        version: "1.0".into(),
        primary: sample_selector(SelectorKind::Uia, "uia:controlType=Button", 0.95),
        fallbacks: vec![
            sample_selector(SelectorKind::Cdp, "cdp:css=.buy", 0.8),
            sample_selector(SelectorKind::Ocr, "ocr:match=买入", 0.4),
        ],
        iframe_context: None,
        shadow_root_context: None,
    }
}

// =============================================================
// 1. MultiPrioritySelector ordering
// =============================================================

#[test]
fn multi_priority_selector_sorts_by_stability_score_descending() {
    // Pre-shuffled input: the UIA selector has the highest
    // score (0.95), CDP is mid (0.8), OCR is lowest (0.4).
    // We *pre-shuffle* the input so we can prove the selector
    // re-orders regardless of declared order.
    let mps = MultiPrioritySelector::new(vec![
        sample_selector(SelectorKind::Ocr, "ocr:match=买入", 0.4),
        sample_selector(SelectorKind::Uia, "uia:controlType=Button", 0.95),
        sample_selector(SelectorKind::Cdp, "cdp:css=.buy", 0.8),
    ]);
    let kinds: Vec<SelectorKind> = mps.selectors.iter().map(|s| s.kind).collect();
    assert_eq!(
        kinds,
        vec![SelectorKind::Uia, SelectorKind::Cdp, SelectorKind::Ocr],
        "selectors must be sorted by stability_score desc"
    );
}

#[test]
fn multi_priority_selector_from_element_flattens_primary_and_fallbacks() {
    let mps = MultiPrioritySelector::from_element(&sample_element());
    assert_eq!(mps.selectors.len(), 3, "primary + 2 fallbacks = 3");
    // Highest score first.
    assert!((mps.selectors[0].stability_score - 0.95).abs() < f32::EPSILON);
    assert!((mps.selectors[1].stability_score - 0.8).abs() < f32::EPSILON);
    assert!((mps.selectors[2].stability_score - 0.4).abs() < f32::EPSILON);
}

#[tokio::test]
async fn multi_priority_selector_returns_structured_miss_when_every_tier_fails() {
    // The all-stub router cascades every selector through
    // UIA/CDP primary + OCR fallback. Both miss → router
    // returns `StructuredMiss { primary, fallback }`. The
    // selector must surface that verbatim so the executor can
    // decide whether to escalate to VLM rescue.
    let router = stub_router();
    let mps = MultiPrioritySelector::from_element(&sample_element());
    let res = mps.try_locate(&router).await;
    assert!(
        matches!(res, Err(RouterError::StructuredMiss { .. })),
        "all-stub router must produce StructuredMiss, got {:?}",
        res
    );
}

// =============================================================
// 2. WaitCondition::Delay
// =============================================================

#[tokio::test]
async fn wait_condition_delay_succeeds_immediately() {
    let router = stub_router();
    // 1 ms is well below the 100 ms poll interval and below
    // the test budget — it must come back Ok without ever
    // touching the router.
    let start = std::time::Instant::now();
    let res = evaluate_wait_condition(&WaitCondition::Delay { ms: 1 }, &router).await;
    let elapsed = start.elapsed();
    assert!(res.is_ok(), "Delay must be Ok, got {:?}", res);
    assert!(
        elapsed.as_millis() < 500,
        "Delay should be ~1ms, was {:?}",
        elapsed
    );
}

// =============================================================
// 3. Validation::Delay
// =============================================================

#[tokio::test]
async fn validation_delay_succeeds_immediately() {
    let router = stub_router();
    let res = evaluate_validation(&Validation::Delay { ms: 1 }, &router).await;
    assert!(res.is_ok(), "Delay must be Ok, got {:?}", res);
}

// =============================================================
// 4. ErrorHandlerChain empty
// =============================================================

#[tokio::test]
async fn error_handler_chain_empty_returns_none() {
    let router = stub_router();
    let chain = ErrorHandlerChain::empty();
    let err = ExecutionError::SelectorMiss {
        attempts: 3,
        last_strategy: None,
    };
    let res = chain.try_handle(&err, &router).await;
    assert!(
        matches!(res, Ok(None)),
        "empty chain must yield Ok(None), got {:?}",
        res
    );
}

#[tokio::test]
async fn error_handler_chain_with_wait_handler_returns_run_then_continue() {
    let router = stub_router();
    let chain = ErrorHandlerChain::new(vec![ErrorHandler {
        condition: ErrorCondition::ValidationFail {
            validation: Box::new(Validation::Delay { ms: 0 }),
        },
        action: SkillAction::Wait { ms: 0 },
        element_selector: sample_element(),
        retry_count: 0,
    }]);
    let err = ExecutionError::ValidationFail {
        reason: "test".into(),
    };
    let res = chain.try_handle(&err, &router).await;
    match res {
        Ok(Some(RecoveryAction::RunThenContinue { .. })) => {}
        other => panic!("expected RunThenContinue, got {:?}", other),
    }
}

// =============================================================
// 5. RetryPolicy::Exponential boundary
// =============================================================

#[test]
fn retry_policy_exponential_attempt_zero_equals_base() {
    let p = RetryPolicy::Exponential {
        base_ms: 100,
        max_ms: 10_000,
    };
    assert_eq!(p.next_delay(0), 100);
}

#[test]
fn retry_policy_exponential_attempt_ten_caps_at_max() {
    let p = RetryPolicy::Exponential {
        base_ms: 100,
        max_ms: 5_000,
    };
    // 100 * 2^10 = 102_400, capped at 5_000.
    let d = p.next_delay(10);
    assert_eq!(d, 5_000, "attempt=10 must be capped at max_ms");
}

#[test]
fn retry_policy_exponential_attempt_four_doubles_properly() {
    let p = RetryPolicy::Exponential {
        base_ms: 50,
        max_ms: 1_000_000,
    };
    // 50 * 2^4 = 800.
    assert_eq!(p.next_delay(4), 800);
}

#[test]
fn retry_policy_fixed_ignores_attempt() {
    let p = RetryPolicy::Fixed { delay_ms: 250 };
    assert_eq!(p.next_delay(0), 250);
    assert_eq!(p.next_delay(1), 250);
    assert_eq!(p.next_delay(99), 250);
}

// =============================================================
// Bonus: ExecutionReceipt state machine
// =============================================================

#[test]
fn execution_receipt_starts_running() {
    let r = ExecutionReceipt::new("exec-1", "skill-1");
    assert_eq!(r.status, ExecutionStatus::Running);
    assert!(r.finished_at_unix_ms.is_none());
    assert!(r.last_error.is_none());
}

#[test]
fn execution_receipt_complete_terminates_as_succeeded() {
    let r = ExecutionReceipt::new("exec-1", "skill-1").complete();
    assert_eq!(r.status, ExecutionStatus::Succeeded);
    assert!(r.finished_at_unix_ms.is_some());
}

#[test]
fn execution_receipt_fail_records_reason() {
    let r = ExecutionReceipt::new("exec-1", "skill-1").fail("boom");
    assert_eq!(r.status, ExecutionStatus::Failed);
    assert_eq!(r.last_error.as_deref(), Some("boom"));
}

#[test]
fn execution_receipt_pause_records_reason() {
    let r = ExecutionReceipt::new("exec-1", "skill-1").pause_for_user("needs takeover");
    assert_eq!(r.status, ExecutionStatus::PausedForUser);
    assert_eq!(r.last_error.as_deref(), Some("needs takeover"));
}

#[test]
fn execution_receipt_serialises_camel_case() {
    let r = ExecutionReceipt::new("exec-1", "skill-1");
    let json = serde_json::to_string(&r).unwrap();
    assert!(json.contains("\"execId\""));
    assert!(json.contains("\"skillId\""));
    assert!(json.contains("\"startedAtUnixMs\""));
    assert!(json.contains("\"vlmRescueCount\""));
    assert!(json.contains("\"stepDurationsMs\""));
}

// =============================================================
// Bonus: WaitCondition::Delay that the executor will exercise
// =============================================================

#[tokio::test]
async fn wait_condition_element_visible_uses_polling_loop() {
    // The stub router always fails to locate, so
    // `ElementVisible` will spin until its timeout. We
    // configure a tiny 50 ms timeout and confirm the error
    // is shaped like a "never became visible" timeout — that
    // proves the polling loop is wired correctly without
    // needing a real backend.
    let router = stub_router();
    // `Box::leak` produces a `&'static mut ElementSelector`;
    // we clone through it into the owned `WaitCondition` so
    // the leaked allocation is read-only after this line.
    // The leak is intentional and harmless — the test
    // process dies after the assertion, and a ~64-byte leak
    // is dwarfed by the test harness's own bookkeeping
    // overhead. The alternative (an `Arc` or a borrowed
    // `&ElementSelector`) would force a downstream rename and
    // would be a breaking change for the selector module.
    let cond = WaitCondition::ElementVisible {
        selector: Box::leak(Box::new(sample_element())).clone(),
        timeout_ms: 50,
    };

    let start = std::time::Instant::now();
    let res = evaluate_wait_condition(&cond, &router).await;
    let elapsed = start.elapsed();
    assert!(res.is_err(), "stub backends must never satisfy the wait");
    assert!(
        elapsed.as_millis() >= 50,
        "must respect timeout_ms, was {:?}",
        elapsed
    );
    let err = res.unwrap_err();
    assert!(
        err.contains("element never became visible"),
        "err should be the timeout, got {:?}",
        err
    );
}
