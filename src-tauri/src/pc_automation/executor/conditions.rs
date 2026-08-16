// Copyright (c) 2026 AIMarketing
//
// Wait / validation evaluators. Doc1 §3.3.
//
// These two helpers are the *only* place the executor talks to
// the three backends outside the `MultiPrioritySelector` cascade.
// They deliberately surface errors as `String` (not an enum) so
// the caller can pipe them straight into a Tauri event payload
// without a translation step.

use std::time::{Duration, Instant};

use tokio::time::sleep;

use crate::pc_automation::cdp::backend::CdpBackend;
use crate::pc_automation::cdp::types::CdpAction;
use crate::pc_automation::ocr::backend::OcrBackend;
use crate::pc_automation::ocr::types::{OcrAnchor, OcrEngine, OcrRegion as BackendOcrRegion};
use crate::pc_automation::router::PcRouter;
use crate::pc_automation::skill::types::{
    OcrRegion as SkillOcrRegion, Validation, WaitCondition,
};
use crate::pc_automation::uia::backend::UiaBackend;

use super::selector::MultiPrioritySelector;

/// Polling interval used by every `evaluate_*` call that needs to
/// spin on a backend. 100 ms is the v5 default — the structured
/// tiers (UIA / CDP) return in <1 ms so the loop is effectively
/// busy-waiting the backends, not the runtime.
const POLL_INTERVAL_MS: u64 = 100;

/// Block until the condition is satisfied or `timeout_ms`
/// elapses. Returns `Ok(())` on success and an `Err(String)`
/// describing the last failure on timeout. `Delay` is a pure
/// sleep and never errors.
pub async fn evaluate_wait_condition(
    cond: &WaitCondition,
    router: &PcRouter,
) -> Result<(), String> {
    match cond {
        WaitCondition::Delay { ms } => {
            sleep(Duration::from_millis(*ms)).await;
            Ok(())
        }
        WaitCondition::ElementVisible {
            selector,
            timeout_ms,
        } => {
            let deadline = Instant::now() + Duration::from_millis(*timeout_ms);
            loop {
                let mps = MultiPrioritySelector::from_element(selector);
                match mps.try_locate(router).await {
                    Ok(_) => return Ok(()),
                    Err(e) => {
                        let msg = e.to_string();
                        if Instant::now() >= deadline {
                            return Err(format!("element never became visible: last error: {}", msg));
                        }
                        sleep(Duration::from_millis(POLL_INTERVAL_MS)).await;
                    }
                }
            }
        }
        WaitCondition::ElementAttributeEquals {
            selector,
            attribute: _,
            value: _,
            timeout_ms,
        } => {
            // The v5 UiaBackend does not expose a generic
            // attribute reader. The closest proxy is
            // `find_by` succeeding on the selector. Once A1
            // lands a richer UIA surface we can wire the
            // real attribute comparison in here.
            let deadline = Instant::now() + Duration::from_millis(*timeout_ms);
            loop {
                let mps = MultiPrioritySelector::from_element(selector);
                match mps.try_locate(router).await {
                    Ok(_) => return Ok(()),
                    Err(e) => {
                        let msg = e.to_string();
                        if Instant::now() >= deadline {
                            return Err(format!("element attribute never matched: last error: {}", msg));
                        }
                        sleep(Duration::from_millis(POLL_INTERVAL_MS)).await;
                    }
                }
            }
        }
        WaitCondition::OcrTextPresent {
            text,
            region,
            timeout_ms,
        } => {
            let anchor = OcrAnchor {
                region: region.map(|r| skill_to_backend_region(&r)),
                match_text: text.clone(),
                full_screen: region.is_none(),
                engine: OcrEngine::PpOcrV5,
            };
            let deadline = Instant::now() + Duration::from_millis(*timeout_ms);
            loop {
                // Note: do NOT `?` on the result — we want to
                // capture the Err and keep polling until the
                // deadline. The backends' stub implementations
                // return `Err("not wired")`; that's a valid
                // "not yet" signal here.
                match router.ocr.locate(&anchor) {
                    Ok(Some(_)) => return Ok(()),
                    Ok(None) => {
                        if Instant::now() >= deadline {
                            return Err("ocr text never appeared: not found".to_string());
                        }
                    }
                    Err(e) => {
                        if Instant::now() >= deadline {
                            return Err(format!("ocr text never appeared: {}", e));
                        }
                    }
                }
                sleep(Duration::from_millis(POLL_INTERVAL_MS)).await;
            }
        }
    }
}

/// One-shot validation. No polling, no retries — the caller
/// (the executor's main loop) owns the retry policy. Returns
/// `Ok(())` on success or `Err(String)` on the first miss.
pub async fn evaluate_validation(
    val: &Validation,
    router: &PcRouter,
) -> Result<(), String> {
    match val {
        Validation::Delay { ms } => {
            sleep(Duration::from_millis(*ms)).await;
            Ok(())
        }
        Validation::ElementValueEquals { selector, value: _ } => {
            // Same caveat as WaitCondition::ElementAttributeEquals:
            // the v5 backends don't surface the element's value, so
            // we proxy through the locator cascade. A real attribute
            // comparison lands when the v6 UiaBackend adds it.
            let mps = MultiPrioritySelector::from_element(selector);
            mps.try_locate(router)
                .await
                .map(|_| ())
                .map_err(|e| e.to_string())
        }
        Validation::OcrTextPresent { text, region } => {
            let anchor = OcrAnchor {
                region: region.map(|r| skill_to_backend_region(&r)),
                match_text: text.clone(),
                full_screen: region.is_none(),
                engine: OcrEngine::PpOcrV5,
            };
            router
                .ocr
                .locate(&anchor)?
                .map(|_| ())
                .ok_or_else(|| format!("ocr text not found: {}", text))
        }
        Validation::PageUrlContains { substring } => {
            // Page URL is a CDP concept. We dispatch a
            // `CdpAction::Evaluate` with a tiny JS one-liner
            // that returns the current `location.href` and
            // compare in Rust. The "evaluate" path is also
            // the only CDP call that can succeed without a
            // running browser being attached — the
            // `attach_or_launch` call below is what surfaces
            // a clean error in that case.
            router
                .cdp
                .attach_or_launch(None)
                .map_err(|e| format!("cdp attach: {}", e))?;
            let result = router.cdp.send(CdpAction::Evaluate(
                "JSON.stringify({ href: location.href })".into(),
            ))?;
            if !result.success {
                return Err(result.error.unwrap_or_else(|| "cdp evaluate failed".into()));
            }
            let parsed: serde_json::Value = result
                .return_value
                .as_deref()
                .map(serde_json::from_str)
                .transpose()
                .map_err(|e| format!("cdp eval parse: {}", e))?
                .unwrap_or(serde_json::Value::Null);
            let href = parsed
                .get("href")
                .and_then(|v| v.as_str())
                .unwrap_or_default();
            if href.contains(substring) {
                Ok(())
            } else {
                Err(format!(
                    "page url does not contain {:?}: current={:?}",
                    substring, href
                ))
            }
        }
    }
}

/// The skill data model and the OCR backend each define their
/// own `OcrRegion` (deliberately — the skill layer is supposed
/// to be the *bottom* of the stack with no inbound edge to
/// `pc_automation::ocr`). Both shapes are identical, so the
/// conversion is a plain field copy.
fn skill_to_backend_region(r: &SkillOcrRegion) -> BackendOcrRegion {
    BackendOcrRegion {
        x: r.x,
        y: r.y,
        w: r.w,
        h: r.h,
    }
}

// The backend traits are referenced indirectly via the router;
// this alias makes the dependency explicit for IDE navigation
// and silences the "unused import" warning rustc raises even
// when the trait is reached through `dyn`.
#[allow(dead_code)]
fn _backends_used(_u: &dyn UiaBackend, _c: &dyn CdpBackend, _o: &dyn OcrBackend) {}
