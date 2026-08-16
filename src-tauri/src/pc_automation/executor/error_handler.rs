// Copyright (c) 2026 AIMarketing
//
// Error-handler chain. Doc1 §2.3 / §3.4.
//
// A skill can attach any number of `ErrorHandler` entries. When a
// step fails the executor walks the chain in order; the first
// handler whose `condition` matches the observed `ExecutionError`
// wins. The winning handler returns a `RecoveryAction` that the
// executor then carries out (re-try the step, click a different
// button, abort, etc.).
//
// We intentionally model the chain as a **list** (not a tree) —
// Doc1 §2.3 keeps the recovery graph flat for v1.

use std::time::Instant;

use serde::{Deserialize, Serialize};

use crate::pc_automation::router::PcRouter;
use crate::pc_automation::skill::types::{ErrorCondition, ErrorHandler, SkillAction};
use crate::pc_automation::step::{RouterError, StepStrategy};

use super::conditions::evaluate_validation;
use super::selector::{LocatedElement, MultiPrioritySelector};

/// Why the executor is calling the chain. Same variant set as
/// `ErrorCondition` so the chain can do structural matching
/// (the chain tests `matches!(cond, ErrorCondition::SelectorMiss { .. })`
/// against the runtime `ExecutionError`).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", tag = "kind")]
pub enum ExecutionError {
    SelectorMiss {
        attempts: u32,
        last_strategy: Option<StepStrategy>,
    },
    ValidationFail { reason: String },
    BackendError { tier: String, reason: String },
    Timeout { waited_ms: u64 },
}

impl std::fmt::Display for ExecutionError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ExecutionError::SelectorMiss { attempts, last_strategy } => write!(
                f,
                "selector miss after {} attempts (last_strategy={:?})",
                attempts, last_strategy
            ),
            ExecutionError::ValidationFail { reason } => {
                write!(f, "validation failed: {}", reason)
            }
            ExecutionError::BackendError { tier, reason } => {
                write!(f, "backend error in {}: {}", tier, reason)
            }
            ExecutionError::Timeout { waited_ms } => {
                write!(f, "timed out after {}ms", waited_ms)
            }
        }
    }
}

impl std::error::Error for ExecutionError {}

/// What the chain wants the executor to do next. The executor
/// itself owns the `Retry` loop so the chain can stay pure.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", tag = "kind")]
pub enum RecoveryAction {
    /// Re-run the primary step. The handler's `retry_count`
    /// becomes the new ceiling for the inner retry loop.
    RetryPrimary { max_attempts: u32 },
    /// Run a different step (e.g. "first dismiss the cookie
    /// banner, then re-run the original step").
    RunThenContinue {
        action: SkillAction,
        then_retry_primary: bool,
    },
    /// Give up on the skill run; the executor marks the
    /// receipt as `Failed` and emits `uirpa_step_failed`.
    Abort,
    /// Pause the run for user takeover — maps to
    /// `ExecutionStatus::PausedForUser` in the IPC layer.
    PauseForUser { reason: String },
}

/// Handler chain. Order matters: first match wins.
#[derive(Debug, Clone, Default)]
pub struct ErrorHandlerChain {
    pub handlers: Vec<ErrorHandler>,
}

impl ErrorHandlerChain {
    pub fn new(handlers: Vec<ErrorHandler>) -> Self {
        Self { handlers }
    }

    pub fn empty() -> Self {
        Self::default()
    }

    /// Walk the chain. Returns:
    /// * `Ok(Some(action))` — a handler matched.
    /// * `Ok(None)` — no handler matched; the caller should
    ///   surface the error to the user / abort.
    /// * `Err(_)` — a matching handler ran its action and the
    ///   action itself errored. The string is the action's
    ///   error message, prefixed with the handler index for
    ///   log readability.
    pub async fn try_handle(
        &self,
        error: &ExecutionError,
        router: &PcRouter,
    ) -> Result<Option<RecoveryAction>, String> {
        if self.handlers.is_empty() {
            return Ok(None);
        }
        for (idx, handler) in self.handlers.iter().enumerate() {
            if !condition_matches(&handler.condition, error) {
                continue;
            }
            // Matched. The real SkillAction variants in
            // `pc_automation::skill::types` are
            // `Click` / `Input { value }` / `Wait { ms }` /
            // `Hotkey { keys }` — they don't carry a selector
            // because the handler's `element_selector` IS the
            // target. We use that to try locating the element
            // before reporting success.
            let mps = MultiPrioritySelector::from_element(&handler.element_selector);
            let _start = Instant::now();
            let located: Result<LocatedElement, RouterError> = mps.try_locate(router).await;

            let base_action = match &handler.action {
                SkillAction::Click
                | SkillAction::Input { .. }
                | SkillAction::Hotkey { .. } => {
                    if located.is_err() {
                        return Err(format!(
                            "handler[{}]: recovery action locator missed",
                            idx
                        ));
                    }
                    RecoveryAction::RunThenContinue {
                        action: handler.action.clone(),
                        then_retry_primary: true,
                    }
                }
                SkillAction::Wait { .. } => RecoveryAction::RunThenContinue {
                    action: handler.action.clone(),
                    then_retry_primary: true,
                },
            };

            // The handler's `retry_count` is the new ceiling
            // for the primary step's retry loop. Zero means
            // "do not retry, just continue".
            if handler.retry_count == 0 {
                return Ok(Some(RecoveryAction::RunThenContinue {
                    action: handler.action.clone(),
                    then_retry_primary: false,
                }));
            }
            return Ok(Some(base_action.with_max_attempts(handler.retry_count)));
        }
        Ok(None)
    }
}

impl RecoveryAction {
    fn with_max_attempts(self, n: u32) -> Self {
        match self {
            RecoveryAction::RetryPrimary { .. } => RecoveryAction::RetryPrimary { max_attempts: n },
            RecoveryAction::RunThenContinue { action, .. } => {
                RecoveryAction::RunThenContinue { action, then_retry_primary: n > 0 }
            }
            other => other,
        }
    }
}

fn condition_matches(cond: &ErrorCondition, err: &ExecutionError) -> bool {
    match (cond, err) {
        (ErrorCondition::SelectorMiss { after_attempts }, ExecutionError::SelectorMiss { attempts, .. }) => {
            *attempts >= *after_attempts
        }
        (ErrorCondition::OcrTextPresent { .. }, _) => {
            // OCR text trigger needs the current screen content
            // — out of scope (no screenshot path inside
            // the chain). We never match this variant today;
            // a follow-up PR wires it through VlmRescue.
            false
        }
        (ErrorCondition::ValidationFail { .. }, ExecutionError::ValidationFail { .. }) => true,
        _ => false,
    }
}

/// Convenience: run a `Validation` synchronously through the
/// router. Used by the executor when the chain needs to
/// pre-check whether to fire (e.g. `OcrTextPresent` triggers).
#[allow(dead_code)]
pub(crate) async fn run_validation(
    val: &crate::pc_automation::skill::types::Validation,
    router: &PcRouter,
) -> Result<(), String> {
    evaluate_validation(val, router).await
}
