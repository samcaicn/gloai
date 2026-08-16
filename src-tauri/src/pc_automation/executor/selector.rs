// Copyright (c) 2026 tupAI
//
// Multi-priority selector. Doc1 §2.2 / §5 / uirap改造技术方案.md §4.
//
// The contract (v2, after the domain-aware router landed):
//   1. Sort selectors by `stability_score` descending (the
//      *declared* order is only a tie-breaker).
//   2. For each selector, hand the corresponding `PcStep` to
//      `PcRouter::execute_step`. The router runs the
//      domain-aware cascade **once** per selector:
//        * Desktop profile → UIA primary → OCR fallback
//        * Web     profile → CDP primary → OCR fallback
//      Both miss → `RouterError::StructuredMiss`.
//   3. The first `Ok` wins — return a `LocatedElement` with the
//      strategy the router used and the selector kind that the
//      caller originally asked for.
//   4. If every selector misses, return the LAST `RouterError`
//      verbatim (carries `StructuredMiss { primary, fallback }`
//      or `PrimaryMiss(reason)`). The `AdaptiveExecutor` is
//      responsible for inspecting this and either escalating to
//      VLM rescue (`StructuredMiss`) or going to the error-
//      handler chain (`PrimaryMiss`, e.g. parse error).

use std::time::Instant;

use serde::{Deserialize, Serialize};

use crate::pc_automation::router::PcRouter;
use crate::pc_automation::skill::types::{Selector, SelectorKind};
use crate::pc_automation::step::{PcStep, RouterError, StepStrategy};

/// What we managed to locate. Carries enough metadata for the
/// `AdaptiveExecutor` to log / emit a useful event without having
/// to re-resolve the target.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LocatedElement {
    pub strategy_used: StepStrategy,
    pub selector_kind: SelectorKind,
    pub action_taken: String,
    pub latency_ms: u64,
}

/// Holds the **sorted** selector list. The struct does not
/// enforce sorting at construction time; callers should use
/// `MultiPrioritySelector::new` which sorts internally.
#[derive(Debug, Clone)]
pub struct MultiPrioritySelector {
    pub selectors: Vec<Selector>,
    /// 录制时记录的坐标,作为元素查找失败后的 last-resort fallback。
    /// 由 `from_element` 从 `SelectorKind::Coordinate` 中提取。
    pub recorded_coords: Option<(i32, i32)>,
}

impl MultiPrioritySelector {
    /// Build a `MultiPrioritySelector` from an `ElementSelector`,
    /// sorting the combined primary + fallback list by
    /// `stability_score` descending. Stable so the caller's
    /// declared ordering is the tie-breaker.
    /// 同时从 selector 列表中提取 `Coordinate` 类型的录制坐标,供 router 的坐标 fallback 使用。
    pub fn from_element(element: &crate::pc_automation::skill::types::ElementSelector) -> Self {
        let mut selectors = Vec::with_capacity(1 + element.fallbacks.len());
        selectors.push(element.primary.clone());
        selectors.extend(element.fallbacks.iter().cloned());
        let recorded_coords = selectors.iter().find_map(extract_coords);
        sort_selectors(&mut selectors);
        Self { selectors, recorded_coords }
    }

    /// Build from an already-flattened list. Same sorting rule.
    pub fn new(mut selectors: Vec<Selector>) -> Self {
        let recorded_coords = selectors.iter().find_map(extract_coords);
        sort_selectors(&mut selectors);
        Self { selectors, recorded_coords }
    }

    /// Walk the sorted selector list, returning the first
    /// `StepOutcome` the `PcRouter` can produce.
    ///
    /// Returns the LAST router error verbatim when every
    /// selector misses:
    /// * `RouterError::StructuredMiss { primary, fallback }` —
    ///   the most common miss path; the executor escalates to
    ///   VLM rescue.
    /// * `RouterError::PrimaryMiss(reason)` — e.g. invalid
    ///   selector string; the executor skips VLM and goes to
    ///   the error-handler chain directly.
    pub async fn try_locate(
        &self,
        router: &PcRouter,
    ) -> Result<LocatedElement, RouterError> {
        let start = Instant::now();
        let mut last_err: Option<RouterError> = None;
        for (idx, sel) in self.selectors.iter().enumerate() {
            let step = PcStep {
                id: format!("mps-{idx}"),
                description: format!("mps selector: {}", sel.value),
                app_profile: None,
                strategy: kind_to_strategy(sel.kind),
                primary_selector: sel.value.clone(),
                fallback_selectors: Vec::new(),
                recorded_coords: self.recorded_coords,
            };
            match router.execute_step(&step).await {
                Ok(outcome) => {
                    return Ok(LocatedElement {
                        strategy_used: outcome.strategy_used,
                        selector_kind: sel.kind,
                        action_taken: outcome.action_taken,
                        latency_ms: start.elapsed().as_millis() as u64,
                    });
                }
                Err(e) => {
                    last_err = Some(e);
                    continue;
                }
            }
        }
        // All selectors missed. Return the last router error
        // verbatim so the executor can branch on its variant.
        Err(last_err.unwrap_or_else(|| RouterError::StructuredMiss {
            primary: "no selectors configured".to_string(),
            fallback: "no selectors configured".to_string(),
        }))
    }
}

fn sort_selectors(selectors: &mut [Selector]) {
    selectors.sort_by(|a, b| {
        // NaN-safe: NaN is treated as "less than everything
        // else" so it sinks to the bottom.
        b.stability_score
            .partial_cmp(&a.stability_score)
            .unwrap_or(std::cmp::Ordering::Less)
    });
}

/// 从 `Selector` 中提取录制坐标。仅当 `kind == Coordinate` 且 `value` 可解析为 `"x,y"` 时返回。
/// 录制端会同时记录元素选择器和坐标 fallback,这里负责把坐标从 selector 列表中捞出来,
/// 透传到 router 的 `try_uia` 坐标 fallback 分支。
fn extract_coords(sel: &Selector) -> Option<(i32, i32)> {
    if sel.kind != SelectorKind::Coordinate {
        return None;
    }
    let (x_str, y_str) = sel.value.split_once(',')?;
    let x: i32 = x_str.trim().parse().ok()?;
    let y: i32 = y_str.trim().parse().ok()?;
    Some((x, y))
}

/// 将 SelectorKind 映射为 StepStrategy，驱动 router 的 domain 选择。
///
/// 不跨域降级原则：
///   Uia → Desktop domain（UIA primary → OCR fallback）
///   Cdp → Web domain（CDP primary → OCR fallback）
///   Ocr → 直接走 OCR（跳过 primary，避免 UIA parse 白费时间）
///   Visual / Coordinate → Desktop domain（UIA primary + 坐标 fallback）
fn kind_to_strategy(kind: SelectorKind) -> StepStrategy {
    match kind {
        SelectorKind::Uia => StepStrategy::Uia,
        SelectorKind::Cdp => StepStrategy::Cdp,
        // OCR selector 直接走 OCR 快捷路径，不经过 UIA/CDP primary。
        // router.rs execute_step 检测到 strategy == Ocr 时会跳过 primary。
        SelectorKind::Ocr => StepStrategy::Ocr,
        // Visual / Coordinate 没有专属的 router tier，
        // 走 Desktop domain（UIA primary + 坐标 fallback）。
        // UIA parse 失败时会自动 fallback 到 recorded_coords 坐标点击。
        SelectorKind::Visual | SelectorKind::Coordinate => StepStrategy::Uia,
    }
}
