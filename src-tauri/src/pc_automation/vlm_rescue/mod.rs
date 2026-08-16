// Copyright (c) 2026 AIMarketing
//
// AIMarketing v5 §6.1 — VLM rescue facade.
//
// v2 model (uirap改造技术方案.md §6.1):
//   The rescue is the **pre-error escalation** path. The router
//   does NOT include it in its cascade; the executor invokes it
//   only after `RouterError::StructuredMiss`. The flow is:
//
//     1. Capture a screenshot of the failing step's region
//        (`screenshot::capture_focused_region`).
//     2. Build a prompt to send to the VLM:
//          a. First try the **intelligent** path
//             (`build_dynamic_prompt`) — call the configured
//             cloud LLM (text-only) to compose a tailored
//             prompt based on the failing step, intent, app
//             profile and past attempt context.
//          b. On ANY failure (no LLM wired up, network error,
//             rate-limit, empty response) silently fall back to
//             the **fixed** template (`build_prompt`). The
//             rescue loop is never blocked on the cloud LLM.
//     3. Hand the composed prompt + screenshot to the VLM
//        (vllm endpoint) and parse the JSON response into a
//        `VlmAction`.
//     4. Reject any `VlmAction` with `confidence < 0.6`.
//
// The whole pipeline is invoked through
// `tauri::async_runtime::spawn_blocking` because the VLM HTTP
// round-trip is the long pole and the `hermes::llm_service`
// Tauri command needs the runtime to be free of the caller.

use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Arc;

use crate::pc_automation::vlm_rescue::analyzer::{
    build_dynamic_prompt, is_action_acceptable, parse_ui_tars_response, DynamicPromptConfig,
    RescueContext, VlmAction, DEFAULT_CONFIDENCE_THRESHOLD,
};

pub mod analyzer;
pub mod screenshot;

// =============================================================================
// Public facade
// =============================================================================

/// Facade for the pre-error VLM rescue. NOT a tier in the
/// router cascade — the executor invokes this only on
/// `RouterError::StructuredMiss`.
///
/// Default thresholds per `uirap改造技术方案.md` §6.1:
///   * `max_attempts = 3`           — Doc1 §2.1 VLM 限频
///   * `confidence_threshold = 0.6` — Doc1 §6 VLM 限制
#[derive(Debug)]
pub struct VlmRescue {
    pub max_attempts: u32,
    pub confidence_threshold: f32,
    /// Cloud LLM config for the dynamic-prompt path. If left
    /// at `Default` the rescue silently uses the fixed
    /// template for every attempt.
    pub dynamic_prompt: DynamicPromptConfig,
    /// Live attempt counter; reset by the caller when a new skill run
    /// starts. The executor hands the same `Arc<Atomic32>` into every
    /// retry so the cap is shared across attempts.
    attempts: Arc<AtomicU32>,
}

impl Default for VlmRescue {
    fn default() -> Self {
        Self::new(3, DEFAULT_CONFIDENCE_THRESHOLD)
    }
}

impl VlmRescue {
    pub fn new(max_attempts: u32, confidence_threshold: f32) -> Self {
        Self {
            max_attempts,
            confidence_threshold,
            dynamic_prompt: DynamicPromptConfig::default(),
            attempts: Arc::new(AtomicU32::new(0)),
        }
    }

    /// Replace the dynamic-prompt config. The caller wires this
    /// to the configured cloud LLM (`hermes::llm_service`) at
    /// boot time.
    pub fn with_dynamic_prompt(mut self, cfg: DynamicPromptConfig) -> Self {
        self.dynamic_prompt = cfg;
        self
    }

    /// Shared attempt counter so the executor can scope the cap to
    /// a single skill run.
    pub fn shared_attempts(&self) -> Arc<AtomicU32> {
        Arc::clone(&self.attempts)
    }

    /// Reset the attempt counter. The executor calls this on the
    /// start of every skill run.
    pub fn reset_attempts(&self) {
        self.attempts.store(0, Ordering::SeqCst);
    }

    /// Current attempt count (for tests / observability).
    pub fn attempts(&self) -> u32 {
        self.attempts.load(Ordering::SeqCst)
    }

    /// Returns `true` if the rescue has already burned through the
    /// configured number of attempts. Callers should skip the rescue
    /// branch entirely once this returns `true`.
    pub fn exhausted(&self) -> bool {
        self.attempts.load(Ordering::SeqCst) >= self.max_attempts
    }

    /// The main entry point. The executor invokes this when the
    /// router returned `RouterError::StructuredMiss`.
    ///
    /// `ctx` carries everything the prompt builder needs
    /// (step summary, intent, app profile, primary + fallback
    /// error messages, attempt index). `screenshot_png` is the
    /// raw PNG bytes (the caller is expected to have captured
    /// them via `screenshot::capture_full_screen` or
    /// `screenshot::capture_focused_region`).
    ///
    /// On success returns the validated `VlmAction`. On any failure
    /// (capture failed, prompt build error, VLM stubbed, JSON parse
    /// error, confidence too low) returns an `Err` with a descriptive
    /// message so the executor can log it verbatim.
    pub async fn try_rescue(
        &self,
        ctx: &RescueContext<'_>,
        screenshot_png: &[u8],
    ) -> Result<VlmAction, String> {
        // 1. 先做廉价输入校验,确保 fetch_add 不会被无效输入白白消耗一次额度。
        //    之前校验在 fetch_add 之后,空 screenshot / 空 intent 也会烧掉一次 rescue 配额,
        //    调用方误传空 buffer 几次就把 max_attempts 耗光。
        if screenshot_png.is_empty() {
            return Err("VLM rescue called with empty screenshot buffer".to_string());
        }
        if ctx.intent.trim().is_empty() {
            return Err("VLM rescue called with empty intent".to_string());
        }

        // 2. 修复 TOCTOU:之前先 `exhausted()` (load) 再 `fetch_add`,
        //    两个并发调用可同时通过闸门后各自自增,突破 max_attempts。
        //    改为先 fetch_add 拿到"本次的索引",再用它判断是否超限。
        //    若已耗尽则立刻回滚自增,避免 attempts() 把"被拒的请求"也计入。
        let attempt_idx = self.attempts.fetch_add(1, Ordering::SeqCst);
        if attempt_idx >= self.max_attempts {
            self.attempts.fetch_sub(1, Ordering::SeqCst);
            return Err(format!(
                "VLM rescue exhausted (max_attempts = {})",
                self.max_attempts
            ));
        }

        // 3. attempt counter 已在闸门处原子自增(见上方 fetch_add),
        //    所以即便 LLM 调用 panic 也不会丢失这次计数。

        // 3. Build the prompt. We try the intelligent path first
        //    (cloud LLM composes a tailored prompt); on any error
        //    we silently fall back to the fixed template.
        let prompt = build_dynamic_prompt(&self.dynamic_prompt, ctx).await;

        // 4. Hand the prompt off to the VLM. We deliberately do NOT
        //    call `hermes::llm_service::hermes_llm_complete` here:
        //    that command is wired as a `#[tauri::command]` (requires
        //    a `tauri::AppHandle`) and calling it from this module
        //    would create a circular dependency. Instead the executor
        //    is expected to lift the prompt via the Tauri command
        //    bridge. To keep the unit tests hermetic, the actual
        //    dispatch is hidden behind a stub.
        //
        //    `spawn_blocking` requires a `'static` closure, so we
        //    own the screenshot bytes (cheap: usually <1 MB) before
        //    crossing the thread boundary.
        let png_owned: Vec<u8> = screenshot_png.to_vec();
        let raw_response = tauri::async_runtime::spawn_blocking(move || {
            dispatch_vlm_stub(&prompt, &png_owned)
        })
        .await
        .map_err(|e| format!("vlm rescue join error: {}", e))??;

        // 把 UI-TARS 协议字符串解析回 `VlmAction`,再做阈值闸门。
        // 真实 VLM 上线后,这一步解析的就是真实模型的输出。
        let action = parse_ui_tars_response(&raw_response)?;

        // 5. Threshold gate.
        if !is_action_acceptable(&action, self.confidence_threshold) {
            return Err(format!(
                "VLM confidence {:.2} below threshold {:.2}",
                action.confidence, self.confidence_threshold
            ));
        }

        Ok(action)
    }
}

// =============================================================================
// Stub dispatch
// =============================================================================

/// 桩实现:返回符合 UI-TARS (ByteDance COMPUTER_USE_DOUBAO) 协议的字符串。
///
/// 真实 VLM 上线后,这部分会替换为 `hermes::llm_service::hermes_llm_complete`
/// 的 HTTP 调用;桩只保证**协议形态**对齐,让 `parse_ui_tars_response`
/// 解析路径在零网络环境下也能跑通。
///
/// 协议格式:
/// ```text
/// Thought: <中文思路>
/// Action: click(start_box='<|box_start|>x y<|box_end|>')
/// ```
fn dispatch_vlm_stub(prompt: &str, screenshot_png: &[u8]) -> Result<String, String> {
    // 校验 prompt 至少包含 UI-TARS 模板中的关键标识,捕捉 prompt builder 漏字段 bug。
    // 模板里固定出现 "User Instruction" 与 "Action Space",新模板同时也会带
    // "## 当前失败步骤"(当 step_summary 非空)。命中其中任一即视为合法 prompt。
    if !prompt.contains("User Instruction")
        && !prompt.contains("Action Space")
        && !prompt.contains("当前失败步骤")
    {
        return Err(
            "VLM prompt missing UI-TARS marker (expected 'User Instruction' / 'Action Space' / '当前失败步骤') — check build_dynamic_prompt/build_prompt"
                .to_string(),
        );
    }
    let _ = screenshot_png;

    // 从 prompt 中抽 step_summary(如果存在),用于更逼真的 Thought 文本;
    // 取不到时退化为通用措辞。坐标用 0,0 是 stub 固定值,
    // 真实 VLM 会在 screenshot 上识别坐标。
    let step_summary = prompt
        .split("## 当前失败步骤")
        .nth(1)
        .and_then(|s| s.lines().nth(1))
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "执行当前失败步骤".to_string());

    let x = 0;
    let y = 0;

    // 注意:桩返回的 confidence 故意**不**写在协议字符串里,
    // 由 `parse_ui_tars_response` 用 0.5 的默认值兜底(见 analyzer.rs),
    // 触发 `is_action_acceptable` 阈值闸门,方便测试 rescue loop 的拒绝路径。
    Ok(format!(
        "Thought: 用户想要 {step_summary}。当前 UIA 树和 OCR 都未匹配,推测按钮位于 ({x}, {y}) 附近。\n\
         Action: click(start_box='<|box_start|>{x} {y}<|box_end|>')"
    ))
}

// =============================================================================
// Tests (sibling file, included only on `cargo test`)
// =============================================================================

#[cfg(test)]
#[path = "tests.rs"]
mod tests;
