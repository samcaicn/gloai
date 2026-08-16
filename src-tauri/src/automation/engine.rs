// Copyright (c) 2026 tupAI
//
// tupAI P1 §2 — Automation engine + smart retry state machine.
//
// The engine drives a `SkillManifest` step-by-step, retrying each
// step up to 3 times (DOM -> Visual -> Mixed) before pausing for
// user takeover. It is intentionally *pluggable*: the three
// per-step executors are `pub` async functions that currently
// return `Ok(())` (mock) so the state machine can be unit-tested
// without a real display / browser hooked up.
//
// Future integration points (out of scope for A2):
//   * A3 will fill in `execute_step_with_dom` (browser/CDP).
//   * v5 pc_automation router fills in `execute_step_with_visual`
//     (UIA + CDP + OCR cascade).
//   * A6 will fill in `execute_step_with_mixed` (DOM + UIA
//     fallback; coordinates from a `Recorder::RecordedEvent`).
//
// All Tauri events are emitted with `app_handle.emit("…", …)` and
// the floating panel / toast listeners subscribe to them.

use std::sync::Arc;
use std::time::Duration;

use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter};
use tokio::time::sleep;

use crate::pc_automation::uia::backend::UiaBackend;
use crate::pc_automation::cua_driver::CuaDriverClient;
use crate::skill::manifest::{InputAction, SkillManifest, Step};
use crate::skill::runtime::McpRuntime;
use enigo::{Button, Coordinate, Direction, Enigo, Keyboard, Key, Mouse, Settings};

use super::state::{now_unix_secs, AutomationState, ExecutionRecord, ExecutionStatus};

const MAX_ATTEMPTS: u8 = 4;
const RETRY_BACKOFF: Duration = Duration::from_millis(1000);
const RESUME_TIMEOUT: Duration = Duration::from_secs(60 * 30); // 30 minutes

/// Reason used when the user explicitly cancelled the run.
pub const CANCELLED_BY_USER: &str = "cancelled_by_user";

/// Tauri event names. The floating panel listens to all three.
pub const EVENT_PROGRESS: &str = "automation_progress";
pub const EVENT_PAUSED: &str = "automation_paused";
pub const EVENT_RESUMED: &str = "automation_resumed";
pub const EVENT_COMPLETED: &str = "automation_completed";
pub const EVENT_FAILED: &str = "automation_failed";
/// tupAI P1 §2 — emitted right before each retry attempt. The
/// frontend uses it to surface "trying DOM" / "trying Visual" /
/// "trying Mixed" in the floating panel.
pub const EVENT_STRATEGY_CHANGED: &str = "automation_strategy_changed";
/// tupAI P1 §2 — emitted when all 3 strategies (DOM → Visual →
/// Mixed) fail. The frontend uses it to pop the *modal* takeover
/// dialog that is not closeable until the user clicks "继续" or
/// "取消执行". This is intentionally a separate event from
/// `EVENT_PAUSED` (which is the state-machine signal) so the
/// frontend can wire the modal without having to introspect
/// `phase === "paused_for_user"`.
pub const EVENT_PAUSED_FOR_USER: &str = "automation_paused_for_user";
/// tupAI single-step debug — emitted when the engine pauses before
/// a step because step mode is enabled or a breakpoint was hit.
pub const EVENT_PAUSED_FOR_DEBUG: &str = "automation_paused_for_debug";

/// 4-tier strategy ladder with intent-aware auto-switching,
/// retargeted at the `pc_automation` router.
///
/// Each attempt of a step uses a different strategy tier; on miss
/// the engine cascades the *same* step through the next tier
/// instead of looping. The router inside `pc_automation::router`
/// is the source of truth — the engine only selects which tier
/// gets the first shot, the router handles the cascade.
///
/// **Platform-specific 4-tier ladders:**
///
/// ```text
/// Windows:  Native(UIA) → CDP → OCR → LLM
/// macOS:    Native(AX/AS) → CDP → OCR → LLM
/// Linux:    CDP → OCR → LLM → LLM   (no native tier)
/// ```
///
/// **Intent-aware starting strategy** (`ExecutionIntent::from_step`):
/// The engine inspects each step's selectors to detect the operation
/// intent and starts from the matching tier, skipping wasted attempts:
///
/// | Intent             | Start at  |
/// |--------------------|-----------|
/// | LlmChat            | LLM       |
/// | WebAutomation      | CDP       |
/// | DesktopAutomation  | Native    |
/// | VisualAnchor       | OCR       |
/// | DirectReplay       | Native    |
///
/// `for_attempt` falls back to `Llm` for any attempt index
/// outside the 0..=2 range so a future bump to the retry cap
/// still resolves to a valid strategy. The cap is currently
/// `MAX_ATTEMPTS = 4` (one shot per tier).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RetryStrategy {
    /// Native automation tier — platform-specific:
    /// * Windows: UI Automation (UIA COM)
    /// * macOS:   AppleScript + AXUIElement (via terminator bridge)
    ///
    /// This is the fastest native path when the target app has a real
    /// accessibility tree. Replaces the old `Uia`-only variant.
    Native,
    /// CDP tier — Chrome DevTools Protocol DOM query (5-20ms,
    /// 100% accurate on Electron / Web / Chromium frames).
    Cdp,
    /// OCR tier — PP-OCRv5 fast path, falls through to PaddleOCR-VL-1.6
    /// for low confidence matches (30-400ms, lower accuracy).
    Ocr,
    /// LLM (VLM) tier — used when Native/CDP/OCR all miss.
    /// The actual replay re-uses the recorded `input` coordinates
    /// (see `perform_step_input`).
    Llm,
}

impl RetryStrategy {
    /// Returns the strategy for the given attempt index, with
    /// **platform-aware 4-tier ladder** matching Hermes desktop's
    /// tiered execution model:
    ///
    /// * **Windows**:  Native(UIA) → CDP → OCR → LLM (4 tiers)
    /// * **macOS**:    Native(AXUIElement/AppleScript) → CDP → OCR → LLM (4 tiers)
    /// * **Linux**:    CDP → OCR → LLM → LLM (3 tiers; no native tier)
    ///
    /// Every platform gets a full 4-attempt ladder. On Linux the
    /// native tier is unavailable so CDP gets two shots (attempt 0
    /// and 1) before cascading to OCR.
    pub fn for_attempt(attempt: u32) -> Self {
        let os = std::env::consts::OS;

        match os {
            "windows" | "macos" => {
                // Full 4-tier ladder: Native → CDP → OCR → LLM
                match attempt {
                    0 => RetryStrategy::Native,
                    1 => RetryStrategy::Cdp,
                    2 => RetryStrategy::Ocr,
                    _ => RetryStrategy::Llm,
                }
            }
            _ => {
                // Linux and others: no native tier, CDP gets first shot
                match attempt {
                    0 => RetryStrategy::Cdp,
                    1 => RetryStrategy::Ocr,
                    _ => RetryStrategy::Llm,
                }
            }
        }
    }

    /// Returns `true` if this strategy is available on the
    /// current platform. Native automation is available on
    /// Windows and macOS; everything else runs everywhere.
    pub fn is_available_on_current_platform(self) -> bool {
        match self {
            RetryStrategy::Native => {
                matches!(std::env::consts::OS, "windows" | "macos")
            }
            _ => true,
        }
    }

    /// Human-readable label for the strategy, used in UI events.
    pub fn label(self) -> &'static str {
        match self {
            RetryStrategy::Native => {
                match std::env::consts::OS {
                    "windows" => "UIA",
                    "macos" => "AppleScript/AX",
                    _ => "Native",
                }
            }
            RetryStrategy::Cdp => "CDP",
            RetryStrategy::Ocr => "OCR",
            RetryStrategy::Llm => "LLM",
        }
    }
}

/// Execution intent detected from a step's properties. Drives the
/// **auto-switching** between LLM chat mode, tool-calling mode, and
/// the native/CDP/OCR fallback ladder.
///
/// The intent is inferred from which selectors / fields the step
/// carries, matching Hermes desktop's "progressive disclosure" model:
/// the step declares *what* it wants, the engine decides *how* to
/// get there.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ExecutionIntent {
    /// Step has `llm_prompt` — LLM generates text first, then the
    /// engine replays the `input` action. The LLM call is the
    /// *primary* operation; selectors are secondary.
    LlmChat,
    /// Step has `cdp_selector` — web/Electron DOM automation.
    /// CDP is the primary tool-calling path.
    WebAutomation,
    /// Step has `uia_selector` — native desktop automation.
    /// UIA (Windows) or AXUIElement/AppleScript (macOS) is primary.
    DesktopAutomation,
    /// Step has `ocr_anchor` but no DOM/UIA selector — visual
    /// anchor matching is the primary path.
    VisualAnchor,
    /// Step has only `input` (no selectors) — direct coordinate
    /// replay, no element re-resolution needed.
    DirectReplay,
}

impl ExecutionIntent {
    /// Detect the execution intent from a step's properties.
    ///
    /// Priority order (first match wins):
    ///   1. `llm_prompt` present → `LlmChat`
    ///   2. `cdp_selector` present → `WebAutomation`
    ///   3. `uia_selector` present → `DesktopAutomation`
    ///   4. `ocr_anchor` present → `VisualAnchor`
    ///   5. fallback → `DirectReplay`
    pub fn from_step(step: &Step) -> Self {
        if step.llm_prompt.is_some() {
            return ExecutionIntent::LlmChat;
        }
        if step.cdp_selector.is_some() || step.dom_selector.is_some() {
            return ExecutionIntent::WebAutomation;
        }
        if step.uia_selector.is_some() {
            return ExecutionIntent::DesktopAutomation;
        }
        if step.ocr_anchor.is_some() || step.visual_target.is_some() {
            return ExecutionIntent::VisualAnchor;
        }
        ExecutionIntent::DirectReplay
    }

    /// Pick the **starting** strategy for this intent on the current
    /// platform. The engine still cascades through the full ladder
    /// on failure, but starting from the intent-matched tier saves
    /// 1-2 wasted attempts on the common path.
    ///
    /// | Intent             | Windows           | macOS             | Linux  |
    /// |--------------------|--------------------|--------------------|--------|
    /// | LlmChat            | Llm                | Llm                | Llm    |
    /// | WebAutomation      | Cdp                | Cdp                | Cdp    |
    /// | DesktopAutomation  | Native(UIA)        | Native(AX/AS)      | Cdp*   |
    /// | VisualAnchor       | Ocr                | Ocr                | Ocr    |
    /// | DirectReplay       | Native             | Native             | Cdp    |
    ///
    /// * Linux has no native tier, so DesktopAutomation falls back to CDP.
    pub fn starting_strategy(self) -> RetryStrategy {
        let os = std::env::consts::OS;
        match self {
            ExecutionIntent::LlmChat => RetryStrategy::Llm,
            ExecutionIntent::WebAutomation => RetryStrategy::Cdp,
            ExecutionIntent::DesktopAutomation => {
                match os {
                    "windows" | "macos" => RetryStrategy::Native,
                    _ => RetryStrategy::Cdp, // Linux fallback
                }
            }
            ExecutionIntent::VisualAnchor => RetryStrategy::Ocr,
            ExecutionIntent::DirectReplay => {
                match os {
                    "windows" | "macos" => RetryStrategy::Native,
                    _ => RetryStrategy::Cdp,
                }
            }
        }
    }

    /// Returns the attempt offset for this intent's starting strategy.
    /// The engine uses this to skip straight to the right tier instead
    /// of always starting from attempt 0.
    pub fn starting_attempt(self) -> u32 {
        let os = std::env::consts::OS;
        let has_native = matches!(os, "windows" | "macos");
        match (self, has_native) {
            (ExecutionIntent::LlmChat, _) => 3,       // Llm = last tier
            (ExecutionIntent::WebAutomation, _) => 1,  // Cdp = 2nd tier
            (ExecutionIntent::DesktopAutomation, true) => 0, // Native = 1st
            (ExecutionIntent::DesktopAutomation, false) => 0, // Cdp = 1st (Linux)
            (ExecutionIntent::VisualAnchor, _) => 2,  // Ocr = 3rd tier
            (ExecutionIntent::DirectReplay, true) => 0, // Native = 1st
            (ExecutionIntent::DirectReplay, false) => 0, // Cdp = 1st (Linux)
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProgressPayload {
    pub request_id: String,
    pub skill_id: String,
    pub skill_name: String,
    pub current_step: usize,
    pub total_steps: usize,
    pub step_id: String,
    pub description: String,
    pub status: ExecutionStatus,
    pub timestamp: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PausedPayload {
    pub request_id: String,
    pub skill_id: String,
    pub skill_name: String,
    pub current_step: usize,
    pub total_steps: usize,
    pub step_id: String,
    pub description: String,
    pub last_error: String,
    pub timestamp: i64,
}

/// The engine. A single instance is shared across the app via
/// `app.manage(Arc<AutomationEngine>)` (set up by `lib.rs`).
pub struct AutomationEngine {
    state: Arc<AutomationState>,
    app_handle: AppHandle,
}

impl AutomationEngine {
    pub fn new(state: Arc<AutomationState>, app_handle: AppHandle) -> Self {
        Self { state, app_handle }
    }

    pub fn state(&self) -> &Arc<AutomationState> {
        &self.state
    }

    pub fn app_handle(&self) -> &AppHandle {
        &self.app_handle
    }

    /// Run a manifest to completion. This is the *async* entry
    /// point. Synchronous commands spawn this on the Tauri runtime
    /// and return the `request_id` immediately so the front-end can
    /// poll / show a toast.
    pub async fn run(&self, request_id: String, skill_id: String, manifest: SkillManifest) {
        let total = manifest.steps.len();
        let mut record = ExecutionRecord::new(
            request_id.clone(),
            skill_id.clone(),
            manifest.name.clone(),
        );

        // Mark running.
        self.update_status(
            &request_id,
            ExecutionStatus::Running {
                current_step: 0,
                total_steps: total,
            },
        );

        for (index, step) in manifest.steps.iter().enumerate() {
            if self.state.is_cancelled(&request_id) {
                self.fail(
                    &request_id,
                    &mut record,
                    CANCELLED_BY_USER.to_string(),
                );
                return;
            }

            // Single-step debugging: pause before the step if the user
            // enabled step mode or set a breakpoint on this index.
            if self.state.is_step_mode(&request_id) || self.state.has_breakpoint(&request_id, index) {
                let reason = if self.state.is_step_mode(&request_id) {
                    "single-step mode".to_string()
                } else {
                    format!("breakpoint at step {}", index)
                };
                self.pause_for_debug(
                    &request_id,
                    &skill_id,
                    &manifest.name,
                    index,
                    total,
                    step,
                    reason,
                )
                .await;
                if self.state.is_cancelled(&request_id) {
                    self.fail(&request_id, &mut record, CANCELLED_BY_USER.to_string());
                    return;
                }
            }

            let success = self
                .execute_step_with_retry(&request_id, &skill_id, &manifest.name, index, step, total)
                .await;

            if !success {
                // All attempts failed. Wait for the user to resume
                // (or cancel). The resume notify is shared with
                // `commands::automation::resume_execution` /
                // `cancel_execution`.
                let last_error = format!(
                    "step '{}' failed after {} attempts",
                    step.id, MAX_ATTEMPTS
                );
                self.update_status(
                    &request_id,
                    ExecutionStatus::PausedForUser {
                        current_step: index,
                        last_error: last_error.clone(),
                    },
                );
                let _ = self.app_handle.emit(
                    EVENT_PAUSED,
                    PausedPayload {
                        request_id: request_id.clone(),
                        skill_id: skill_id.clone(),
                        skill_name: manifest.name.clone(),
                        current_step: index,
                        total_steps: total,
                        step_id: step.id.clone(),
                        description: step.description.clone(),
                        last_error: last_error.clone(),
                        timestamp: now_unix_secs(),
                    },
                );

                // tupAI P1 §2 — frontend takeover dialog trigger.
                // Emitted alongside `EVENT_PAUSED` so the floating
                // panel can pop a non-dismissable modal that
                // requires the user to either "继续" or "取消".
                let last_strategy =
                    format!("{:?}", RetryStrategy::for_attempt(MAX_ATTEMPTS as u32 - 1));
                let _ = self.app_handle.emit(
                    EVENT_PAUSED_FOR_USER,
                    serde_json::json!({
                        "request_id": request_id,
                        "skill_id": skill_id,
                        "skill_name": manifest.name,
                        "step_index": index,
                        "step_id": step.id,
                        "step_description": step.description,
                        "attempts": MAX_ATTEMPTS,
                        "last_strategy": last_strategy,
                        "error": last_error,
                        "message": "连续 3 次执行失败，请手动完成该步骤后点击继续",
                    }),
                );

                let notify = self.state.resume_handle(&request_id);
                let cancelled = self.wait_for_resume_or_cancel(&request_id, notify).await;
                if cancelled {
                    self.fail(
                        &request_id,
                        &mut record,
                        CANCELLED_BY_USER.to_string(),
                    );
                    return;
                }
                // User resumed — go around the loop. The next
                // iteration will re-attempt the *current* step.
                continue;
            }
        }

        // All steps done.
        let status = ExecutionStatus::Completed { total_steps: total };
        record.final_status = status.clone();
        record.finished_at = Some(now_unix_secs());
        self.state.set_status(&request_id, status.clone());
        self.state.push_history(record);
        let _ = self.app_handle.emit(
            EVENT_COMPLETED,
            ProgressPayload {
                request_id: request_id.clone(),
                skill_id: skill_id.clone(),
                skill_name: manifest.name.clone(),
                current_step: total,
                total_steps: total,
                step_id: "<done>".into(),
                description: "all steps completed".into(),
                status,
                timestamp: now_unix_secs(),
            },
        );
        self.state.clear_cancel(&request_id);
    }

    /// Inner step driver. Retries `MAX_ATTEMPTS` times, picking a
    /// different strategy each attempt. Returns `true` on success.
    ///
    /// **Intent-aware auto-switching**: The step's properties are
    /// inspected to detect the execution intent (LLM chat, web
    /// automation, desktop automation, visual anchor, or direct
    /// replay). The starting strategy is chosen to match the intent,
    /// skipping wasted attempts on tiers that can't help. On failure
    /// the engine cascades through the full ladder.
    async fn execute_step_with_retry(
        &self,
        request_id: &str,
        skill_id: &str,
        skill_name: &str,
        index: usize,
        step: &Step,
        total: usize,
    ) -> bool {
        // Detect execution intent from step properties — this drives
        // the auto-switching between LLM chat / tool-calling / fallback.
        let intent = ExecutionIntent::from_step(step);
        let start_attempt = intent.starting_attempt();

        // 如果步骤有 llm_prompt，先调用 LLM 获取文本，替换 Type 步骤的静态文本
        let resolved_step = if step.llm_prompt.is_some() {
            match self.resolve_llm_prompt(step).await {
                Some(s) => s,
                None => return false, // LLM 调用失败，步骤无法继续
            }
        } else {
            step.clone()
        };

        // Emit intent detection event so the UI can show
        // "意图: 桌面自动化 → 起始策略: Native(UIA)"
        let _ = self.app_handle.emit(
            EVENT_STRATEGY_CHANGED,
            serde_json::json!({
                "request_id": request_id,
                "step_index": index,
                "step_id": step.id,
                "intent": intent,
                "starting_strategy": intent.starting_strategy(),
                "starting_attempt": start_attempt,
            }),
        );

        for attempt in 0..MAX_ATTEMPTS {
            if self.state.is_cancelled(request_id) {
                return false;
            }

            // tupAI P1 §2 — tell the UI which strategy this
            // attempt is going to use *before* we actually run
            // it, so the panel can show "尝试 2/4 — 视觉定位".
            let strategy = RetryStrategy::for_attempt(attempt as u32);
            let _ = self.app_handle.emit(
                EVENT_STRATEGY_CHANGED,
                serde_json::json!({
                    "request_id": request_id,
                    "step_index": index,
                    "step_id": step.id,
                    "attempt": attempt,
                    "strategy": strategy,
                    "strategy_label": strategy.label(),
                }),
            );

            self.update_status(
                request_id,
                ExecutionStatus::Retrying {
                    current_step: index,
                    attempt,
                },
            );
            let _ = self.app_handle.emit(
                EVENT_PROGRESS,
                ProgressPayload {
                    request_id: request_id.to_string(),
                    skill_id: skill_id.to_string(),
                    skill_name: skill_name.to_string(),
                    current_step: index,
                    total_steps: total,
                    step_id: step.id.clone(),
                    description: step.description.clone(),
                    status: ExecutionStatus::Retrying {
                        current_step: index,
                        attempt,
                    },
                    timestamp: now_unix_secs(),
                },
            );

            let result = match strategy {
                RetryStrategy::Native => execute_step_with_native(&resolved_step).await,
                RetryStrategy::Cdp => execute_step_with_cdp(&resolved_step).await,
                RetryStrategy::Ocr => execute_step_with_ocr(&resolved_step).await,
                RetryStrategy::Llm => execute_step_with_llm(&resolved_step).await,
            };

            if result.is_ok() {
                return true;
            }

            // Backoff between attempts (skip after last).
            if attempt + 1 < MAX_ATTEMPTS {
                sleep(RETRY_BACKOFF).await;
            }
        }
        false
    }

    fn update_status(&self, request_id: &str, status: ExecutionStatus) {
        self.state.set_status(request_id, status);
    }

    /// 解析 LLM prompt：当 step 有 llm_prompt 时，调用 MCP LLM 获取文本，
    /// 然后创建一个新的 Step，其 Type input 的文本替换为 LLM 返回的结果。
    /// 用于输入框自动填写场景：引擎先调用 LLM 生成文本，再输入到目标输入框。
    async fn resolve_llm_prompt(&self, step: &Step) -> Option<Step> {
        let prompt = step.llm_prompt.as_ref()?;
        let _ = self.app_handle.emit(
            EVENT_PROGRESS,
            serde_json::json!({
                "request_id": "",
                "step_id": step.id,
                "message": format!("正在调用 LLM 生成输入内容..."),
            }),
        );

        // 调用 MCP LLM（通过 hermes llm_service）
        let llm_text = crate::hermes::llm_service::hermes_llm_simple_complete(
            prompt.clone(),
        )
        .await
        .ok()?;

        // 创建新的 Step，替换 Type input 的文本
        let mut resolved = step.clone();
        if let Some(InputAction::Type { text }) = resolved.input.as_mut() {
            *text = llm_text;
        } else {
            // 如果 input 不是 Type，将 LLM 返回的文本包装为 Type
            resolved.input = Some(InputAction::Type { text: llm_text });
        }
        Some(resolved)
    }

    fn fail(&self, request_id: &str, record: &mut ExecutionRecord, reason: String) {
        let status = ExecutionStatus::Failed { reason: reason.clone() };
        record.final_status = status.clone();
        record.finished_at = Some(now_unix_secs());
        self.state.set_status(request_id, status);
        self.state.push_history(record.clone());
        let _ = self.app_handle.emit(
            EVENT_FAILED,
            serde_json::json!({
                "request_id": request_id,
                "reason": reason,
                "timestamp": now_unix_secs(),
            }),
        );
    }

    /// Pause execution before a step because single-step mode is
    /// enabled or a breakpoint was hit. Emits `EVENT_PAUSED_FOR_DEBUG`
    /// and waits until the user calls `step_over` or cancels.
    async fn pause_for_debug(
        &self,
        request_id: &str,
        skill_id: &str,
        skill_name: &str,
        index: usize,
        total: usize,
        step: &Step,
        reason: String,
    ) {
        self.update_status(
            request_id,
            ExecutionStatus::PausedForDebug {
                current_step: index,
                reason: reason.clone(),
            },
        );
        let _ = self.app_handle.emit(
            EVENT_PAUSED_FOR_DEBUG,
            serde_json::json!({
                "request_id": request_id,
                "skill_id": skill_id,
                "skill_name": skill_name,
                "current_step": index,
                "total_steps": total,
                "step_id": step.id,
                "step_description": step.description,
                "reason": reason,
                "timestamp": now_unix_secs(),
            }),
        );

        let notify = self.state.step_handle(request_id);
        if self.state.is_cancelled(request_id) {
            return;
        }
        let notified = notify.notified();
        tokio::pin!(notified);
        tokio::select! {
            _ = &mut notified => {
                if self.state.is_cancelled(request_id) {
                    return;
                }
                let _ = self.app_handle.emit(
                    EVENT_RESUMED,
                    serde_json::json!({
                        "request_id": request_id,
                        "timestamp": now_unix_secs(),
                    }),
                );
            }
            _ = sleep(RESUME_TIMEOUT) => {
                self.state.request_cancel(request_id);
            }
        }
    }

    /// Wait until either `notify_one` is called (resume) or the
    /// cancel flag is set. Times out after 30 minutes as a safety
    /// net (the user can also call `cancel_execution` to abort).
    async fn wait_for_resume_or_cancel(
        &self,
        request_id: &str,
        notify: Arc<tokio::sync::Notify>,
    ) -> bool {
        if self.state.is_cancelled(request_id) {
            return true;
        }
        let notified = notify.notified();
        tokio::pin!(notified);
        tokio::select! {
            _ = &mut notified => {
                if self.state.is_cancelled(request_id) {
                    return true;
                }
                let _ = self.app_handle.emit(
                    EVENT_RESUMED,
                    serde_json::json!({
                        "request_id": request_id,
                        "timestamp": now_unix_secs(),
                    }),
                );
                false
            }
            _ = sleep(RESUME_TIMEOUT) => {
                self.state.request_cancel(request_id);
                true
            }
        }
    }
}

// =============================================================
// Step executors (real replay — enigo input injection)
// =============================================================
//
// Each tier performs the step's concrete `input` action (recorded
// click / type / hotkey / wait coordinates) via `enigo`, which
// injects real mouse / keyboard events at the OS level. The
// recognition ladder (CDP → UIA → OCR → LLM) selects *which tier
// gets the first shot*; the actual replay re-uses the coordinates
// captured at record time (rdev global listener), so a recorded
// flow replays deterministically.
//
// When a step has no `input` (e.g. only a selector was captured),
// the tier returns `Err` and the engine cascades to the next tier.

/// Map a modifier token (ctrl/shift/alt/win/...) to an `enigo::Key`.
/// Returns `None` for the main (non-modifier) key.
fn map_modifier(token: &str) -> Option<Key> {
    match token.trim().to_ascii_lowercase().as_str() {
        "ctrl" | "control" | "lcontrol" | "rcontrol" => Some(Key::Control),
        "shift" | "lshift" | "rshift" => Some(Key::Shift),
        "alt" | "lalt" | "ralt" => Some(Key::Alt),
        "win" | "meta" | "super" | "cmd" | "command" | "lwin" | "rwin" | "windows" => {
            Some(Key::Meta)
        }
        _ => None,
    }
}

/// Map the main (non-modifier) hotkey token to an `enigo::Key`.
/// Single characters use `Key::Unicode` so CJK / symbols also work.
fn map_main_key(token: &str) -> Key {
    match token.trim().to_ascii_lowercase().as_str() {
        "enter" | "return" => Key::Return,
        "esc" | "escape" => Key::Escape,
        "tab" => Key::Tab,
        "space" => Key::Space,
        "backspace" | "bs" => Key::Backspace,
        "delete" | "del" => Key::Delete,
        "up" => Key::UpArrow,
        "down" => Key::DownArrow,
        "left" => Key::LeftArrow,
        "right" => Key::RightArrow,
        "home" => Key::Home,
        "end" => Key::End,
        "pageup" => Key::PageUp,
        "pagedown" => Key::PageDown,
        #[cfg(target_os = "windows")]
        "insert" => Key::Insert,
        #[cfg(not(target_os = "windows"))]
        "insert" => Key::Unicode(' '),
        "caps" | "capslock" => Key::CapsLock,
        other => {
            if let Some(c) = other.chars().next() {
                Key::Unicode(c)
            } else {
                Key::Unicode(' ')
            }
        }
    }
}

/// Cua Driver 优先的异步输入执行。
///
/// 优先通过 Cua Driver sidecar 执行输入（后台输入、跨平台），
/// 失败时降级到 enigo（前台输入）。
///
/// 对于 Click：Cua Driver 执行后台点击，如果需要鼠标轨迹模拟，
/// 先用 Cua Driver 的 move_cursor 沿轨迹移动，再执行点击。
/// 对于 Type/Hotkey：Cua Driver 执行后台键盘输入。
/// 对于 Wait：直接 sleep。
///
/// 降级条件：
///   * Cua Driver 二进制不可用
///   * Cua Driver 调用失败（进程崩溃、超时等）
///   * 需要鼠标轨迹模拟（Cua Driver 不支持轨迹回放）
pub async fn perform_step_input_cua(step: &Step) -> Result<(), String> {
    let input = step
        .input
        .as_ref()
        .ok_or_else(|| format!("step '{}' has no input action to replay", step.id))?;

    // 1) 模拟动作间延时
    if let Some(delay_ms) = step.delay_ms {
        if delay_ms > 0 {
            let d = humanized_delay_ms(delay_ms, 25, 200, 8000);
            tokio::time::sleep(std::time::Duration::from_millis(d)).await;
        }
    }

    let cua = CuaDriverClient::shared();
    let cua_available = cua.is_available();

    match input {
        InputAction::Click { x, y } => {
            // 鼠标轨迹模拟 — Cua Driver 不支持轨迹回放，降级到 enigo
            if step.mouse_trajectory.is_some() {
                let step_clone = step.clone();
                return tauri::async_runtime::spawn_blocking(move || {
                    perform_step_input(&step_clone)
                })
                .await
                .map_err(|e| format!("join error: {}", e))?;
            }

            // Cua Driver 优先
            if cua_available {
                match cua.click(*x, *y).await {
                    Ok(()) => return Ok(()),
                    Err(e) => {
                        log::warn!(target: "automation", "cua-driver click failed ({}), falling back to enigo", e);
                    }
                }
            }

            // 降级到 enigo
            let (x, y) = (*x, *y);
            tauri::async_runtime::spawn_blocking(move || enigo_click_sync(x, y))
                .await
                .map_err(|e| format!("join error: {}", e))?
        }
        InputAction::Type { text } => {
            if cua_available {
                match cua.type_text(text).await {
                    Ok(()) => return Ok(()),
                    Err(e) => {
                        log::warn!(target: "automation", "cua-driver type_text failed ({}), falling back to enigo", e);
                    }
                }
            }

            // 降级到 enigo
            let text = text.clone();
            tauri::async_runtime::spawn_blocking(move || {
                let mut enigo = Enigo::new(&Settings::default())
                    .map_err(|e| format!("初始化输入设备失败: {}", e))?;
                enigo.text(&text).map_err(|e| format!("输入文本失败: {}", e))
            })
            .await
            .map_err(|e| format!("join error: {}", e))?
        }
        InputAction::Hotkey { keys } => {
            // Cua Driver 的 hotkey 工具接受 "ctrl+c" 格式
            if cua_available {
                match cua.hotkey(keys).await {
                    Ok(()) => return Ok(()),
                    Err(e) => {
                        log::warn!(target: "automation", "cua-driver hotkey failed ({}), falling back to enigo", e);
                    }
                }
            }

            // 降级到 enigo
            let keys = keys.clone();
            tauri::async_runtime::spawn_blocking(move || {
                let step_dummy = Step {
                    id: String::new(),
                    description: String::new(),
                    dom_selector: None,
                    visual_target: None,
                    uia_selector: None,
                    cdp_selector: None,
                    ocr_anchor: None,
                    input: Some(InputAction::Hotkey { keys }),
                    delay_ms: None,
                    mouse_trajectory: None,
                    llm_prompt: None,
                };
                perform_step_input(&step_dummy)
            })
            .await
            .map_err(|e| format!("join error: {}", e))?
        }
        InputAction::Wait { ms } => {
            tokio::time::sleep(std::time::Duration::from_millis(*ms)).await;
            Ok(())
        }
    }
}

/// enigo 同步点击（供降级路径使用）。
fn enigo_click_sync(x: i32, y: i32) -> Result<(), String> {
    let mut enigo = Enigo::new(&Settings::default())
        .map_err(|e| format!("初始化输入设备失败: {}", e))?;
    enigo.move_mouse(x, y, Coordinate::Abs)
        .map_err(|e| format!("移动鼠标失败: {}", e))?;
    enigo.button(Button::Left, Direction::Press)
        .map_err(|e| format!("按下鼠标失败: {}", e))?;
    enigo.button(Button::Left, Direction::Release)
        .map_err(|e| format!("释放鼠标失败: {}", e))?;
    Ok(())
}

/// Perform a single step's concrete `input` action by injecting
/// real OS input via `enigo`. This is the shared "replay click
/// engine" used by every recognition tier — when the recorded
/// `input` is present it executes immediately; otherwise it
/// returns `Err` so the engine can cascade to the next tier.
///
/// **人类操作模拟**：
///   * `step.delay_ms`：步骤执行前等待，模拟动作间延时
///   * `step.mouse_trajectory`：Click 前沿轨迹移动鼠标（加随机扰动），
///     模拟人类手部运动。点击本身按元素/坐标精确执行，不加随机。
pub fn perform_step_input(step: &Step) -> Result<(), String> {
    let input = step
        .input
        .as_ref()
        .ok_or_else(|| format!("step '{}' has no input action to replay", step.id))?;
    let mut enigo = Enigo::new(&Settings::default())
        .map_err(|e| format!("初始化输入设备失败: {}", e))?;

    // 1) 模拟动作间延时（从录制 flowchart 的 meta.delayMs 透传，加拟人抖动）
    if let Some(delay_ms) = step.delay_ms {
        if delay_ms > 0 {
            let d = humanized_delay_ms(delay_ms, 25, 200, 8000);
            std::thread::sleep(std::time::Duration::from_millis(d));
        }
    }

    match input {
        InputAction::Click { x, y } => {
            // 2) 沿鼠标轨迹移动（加随机扰动，模拟人类手部运动）
            //    点击本身按坐标精确执行，不加随机
            if let Some(trajectory) = &step.mouse_trajectory {
                replay_mouse_trajectory(&mut enigo, trajectory)?;
            }
            enigo.move_mouse(*x, *y, Coordinate::Abs)
                .map_err(|e| format!("移动鼠标失败: {}", e))?;
            enigo.button(Button::Left, Direction::Press)
                .map_err(|e| format!("按下鼠标失败: {}", e))?;
            enigo.button(Button::Left, Direction::Release)
                .map_err(|e| format!("释放鼠标失败: {}", e))?;
            Ok(())
        }
        InputAction::Type { text } => {
            enigo.text(text).map_err(|e| format!("输入文本失败: {}", e))?;
            Ok(())
        }
        InputAction::Hotkey { keys } => {
            let tokens: Vec<String> = keys
                .split('+')
                .map(|s| s.trim().to_string())
                .filter(|s| !s.is_empty())
                .collect();
            // 末位为主键，其余为修饰键
            let split_at = tokens.len().saturating_sub(1);
            let (mods, main) = tokens.split_at(split_at);
            let mut mapped: Vec<Key> = Vec::new();
            for m in mods {
                if let Some(k) = map_modifier(m) {
                    mapped.push(k);
                }
            }
            if let Some(main_key) = main.first() {
                mapped.push(map_main_key(main_key));
            }
            for k in &mapped {
                enigo.key(*k, Direction::Press).map_err(|e| format!("按下按键失败: {}", e))?;
            }
            // 短暂稳定延迟，确保操作系统登记组合键
            std::thread::sleep(std::time::Duration::from_millis(30));
            for k in mapped.iter().rev() {
                enigo.key(*k, Direction::Release).map_err(|e| format!("释放按键失败: {}", e))?;
            }
            Ok(())
        }
        InputAction::Wait { ms } => {
            std::thread::sleep(std::time::Duration::from_millis(*ms));
            Ok(())
        }
    }
}

/// 沿录制轨迹移动鼠标，加随机扰动模拟人类手部运动。
///
/// 轨迹点格式：[[x1,y1],[x2,y2],...]（从 flowchart 的 meta.mouseTrajectory 透传）。
/// 每个轨迹点会加 ±RAND_PX 像素的随机偏移（模拟手抖），但最终点击
/// 不加随机——由 `perform_step_input` 的 Click 分支精确移动到目标坐标。
const TRAJECTORY_RANDOM_PX: i32 = 5;

fn replay_mouse_trajectory(
    enigo: &mut Enigo,
    trajectory: &[Vec<i32>],
) -> Result<(), String> {
    use rand::Rng;
    let mut rng = rand::thread_rng();
    for point in trajectory {
        if point.len() < 2 {
            continue;
        }
        let x = point[0];
        let y = point[1];
        // 加随机扰动：±TRAJECTORY_RANDOM_PX 像素，模拟人类手部微抖
        let rx = x + rng.gen_range(-TRAJECTORY_RANDOM_PX..=TRAJECTORY_RANDOM_PX);
        let ry = y + rng.gen_range(-TRAJECTORY_RANDOM_PX..=TRAJECTORY_RANDOM_PX);
        enigo.move_mouse(rx, ry, Coordinate::Abs)
            .map_err(|e| format!("轨迹移动鼠标失败: {}", e))?;
        // 轨迹点之间微小延时（5-15ms），模拟人类鼠标移动速度
        let step_delay = rng.gen_range(5..=15);
        std::thread::sleep(std::time::Duration::from_millis(step_delay));
    }
    Ok(())
}

/// 拟人化延时采样：把单值延时（录制停顿或固定间隔）转成带随机抖动的区间，模拟人类操作节奏。
///
/// 返回 `[base ± jitter]` 内的随机毫秒数：
/// - `jitter_pct` 默认 ±25% —— 让每次回放的动作间停顿都有自然差异，
///   避免被 bot 检测识别为"每步固定延时"（固定重复 = 非人）。
/// - `min_ms` 保证最小停顿，防止打完一个动作后毫无间隔地连发下一步。
/// - `max_ms` 兜底上限，防止长停顿（如用户暂停后离开）被回放成几十秒。
///
/// 用法：
/// - 回放循环的子步骤间：`humanized_delay_ms(300)`
/// - 录制透传的 `Step.delay_ms`：`humanized_delay_ms(*delay_ms, 30, 200, 5000)`
pub fn humanized_delay_ms(
    base_ms: u64,
    jitter_pct: u32,
    min_ms: u64,
    max_ms: u64,
) -> u64 {
    if base_ms == 0 {
        return min_ms;
    }
    use rand::Rng;
    let mut rng = rand::thread_rng();
    let jitter = (base_ms as f64 * jitter_pct as f64 / 100.0) as u64;
    let lo = base_ms.saturating_sub(jitter).max(min_ms);
    let hi = base_ms.saturating_add(jitter).min(max_ms).max(lo + 1);
    rng.gen_range(lo..=hi)
}

/// Native automation tier — first attempt on Windows & macOS.
///
/// Platform-specific behavior:
/// * **Windows**: UIA via `TerminatorUiaBackend` (COM UIAutomation)
/// * **macOS**:   AppleScript (osascript) for app control + AXUIElement
///   via `TerminatorUiaBackend` for element finding/clicking
/// * **Linux**:   Not available — returns `Err` so the engine
///   cascades to CDP immediately
///
/// 如果 step 有 uia_selector，先走元素精确查找：
///   - 命中 → click(node)（元素精确定位，窗口位移也能找到）
///   - 未命中/失败 → 坐标回放（enigo 点击录制坐标）
/// 如果 step 无 uia_selector，直接坐标回放（零回归，与旧行为一致）。
pub async fn execute_step_with_native(step: &Step) -> Result<(), String> {
    let os = std::env::consts::OS;

    // Platform guard: Native automation is Windows + macOS only.
    // On Linux we fail fast so the engine cascades to CDP.
    if os != "windows" && os != "macos" {
        return Err(format!(
            "Native automation is not available on {}",
            os
        ));
    }

    // ── macOS AppleScript fast path ──────────────────────────
    // On macOS, try AppleScript for simple app-level operations
    // (activate, type, keystroke) before falling through to the
    // terminator AXUIElement path. AppleScript is faster (~5ms)
    // than walking the AX tree and handles common cases like
    // "type text in the frontmost window" without needing a
    // selector.
    #[cfg(target_os = "macos")]
    {
        if let Some(()) = try_macos_applescript(step)? {
            return Ok(());
        }
    }

    // ── Element-based automation (UIA / AXUIElement) ─────────
    // Both Windows and macOS use the terminator bridge for
    // accessibility-tree-based element finding and clicking.
    if let Some(sel_str) = &step.uia_selector {
        if let Ok(sel) = crate::pc_automation::uia::types::parse_uia_selector(sel_str) {
            let backend = crate::pc_automation::terminator_bridge::uia_backend::TerminatorUiaBackend;
            if let Ok(Some(node)) = backend.find_by(&sel) {
                if backend.click(&node).is_ok() {
                    return Ok(());
                }
            }
        }
    }

    // ── Coordinate replay fallback ───────────────────────────
    // 元素查找失败或无 uia_selector → Cua Driver 坐标回放
    // （优先 Cua Driver 后台输入，降级 enigo 前台输入）
    perform_step_input_cua(step).await
}

/// CDP tier — second attempt. Same replay path; in a browser /
/// Chromium context the step's `cdp_selector` could re-locate, but
/// the recorded coordinates are authoritative for desktop replay.
pub async fn execute_step_with_cdp(step: &Step) -> Result<(), String> {
    perform_step_input_cua(step).await
}

/// OCR tier — third attempt. Same replay path; the recorded anchor
/// coordinates are reused.
pub async fn execute_step_with_ocr(step: &Step) -> Result<(), String> {
    perform_step_input_cua(step).await
}

/// LLM (VLM) tier — final attempt. Top of the recognition ladder;
/// when Native/CDP/OCR all miss this is the last chance to act. It
/// reuses the recorded `input` coordinates for the actual replay.
pub async fn execute_step_with_llm(step: &Step) -> Result<(), String> {
    perform_step_input_cua(step).await
}

/// macOS AppleScript fast path. Returns `Ok(Some(()))` if the step
/// was handled by AppleScript, `Ok(None)` if AppleScript couldn't
/// handle it (caller should try the AXUIElement path), or `Err` on
/// subprocess failure.
///
/// Handles:
/// * `Type` — `keystroke` via System Events
/// * `Hotkey` — `key code` via System Events
/// * `Click` — not handled (AppleScript can't click arbitrary
///   coordinates without extra tooling; falls through to enigo)
#[cfg(target_os = "macos")]
fn try_macos_applescript(step: &Step) -> Result<Option<()>, String> {
    use std::process::Command;

    let Some(input) = &step.input else {
        return Ok(None);
    };

    let script = match input {
        InputAction::Type { text } => {
            // Escape double quotes and backslashes for AppleScript string
            let escaped = text
                .replace('\\', "\\\\")
                .replace('"', "\\\"");
            format!(
                r#"tell application "System Events" to keystroke "{}""#,
                escaped
            )
        }
        InputAction::Hotkey { keys } => {
            // Parse "cmd+c" style hotkeys into AppleScript key code.
            // This is a best-effort path; complex hotkeys fall through
            // to enigo via the coordinate replay path.
            let tokens: Vec<&str> = keys.split('+').map(|s| s.trim()).collect();
            if tokens.is_empty() {
                return Ok(None);
            }
            // Build keystroke command for the main key with modifiers
            let main_key = tokens.last().unwrap_or(&"");
            let modifiers: Vec<&str> = if tokens.len() > 1 {
                tokens[..tokens.len() - 1].iter().copied().collect()
            } else {
                vec![]
            };

            let mut mod_list: Vec<String> = Vec::new();
            for m in &modifiers {
                match m.to_lowercase().as_str() {
                    "cmd" | "command" => mod_list.push("command down".to_string()),
                    "ctrl" | "control" => mod_list.push("control down".to_string()),
                    "alt" | "option" => mod_list.push("option down".to_string()),
                    "shift" => mod_list.push("shift down".to_string()),
                    _ => {}
                }
            }

            let escaped_key = main_key.replace('"', "\\\"");
            if mod_list.is_empty() {
                format!(
                    r#"tell application "System Events" to keystroke "{}""#,
                    escaped_key
                )
            } else {
                format!(
                    r#"tell application "System Events" to keystroke "{}" using {{{}}}"#,
                    escaped_key,
                    mod_list.join(", ")
                )
            }
        }
        InputAction::Click { .. } => {
            // AppleScript can't click arbitrary coords easily — fall through
            return Ok(None);
        }
        InputAction::Wait { ms } => {
            std::thread::sleep(std::time::Duration::from_millis(*ms));
            return Ok(Some(()));
        }
    };

    let output = Command::new("/usr/bin/osascript")
        .args(["-e", &script])
        .output()
        .map_err(|e| format!("osascript spawn failed: {}", e))?;

    if output.status.success() {
        log::debug!(
            "[automation/macos] AppleScript succeeded for step '{}'",
            step.id
        );
        Ok(Some(()))
    } else {
        let stderr = String::from_utf8_lossy(&output.stderr);
        log::warn!(
            "[automation/macos] AppleScript failed for step '{}': {}",
            step.id,
            stderr.trim()
        );
        // Fall through to AXUIElement / enigo path
        Ok(None)
    }
}

/// Helper for `commands::automation::execute_skill` — runs an
/// `McpRuntime` on the Tauri async runtime and returns the
/// generated `request_id` immediately. The actual execution is
/// spawned in the background.
///
/// The spawned task is wrapped in `catch_unwind` so a panic in
/// one skill execution never takes down the whole process.
/// Any panic is logged and re-emitted as a failed execution so
/// the front-end sees a clean error instead of a silent crash.
pub fn spawn_execution(
    engine: Arc<AutomationEngine>,
    request_id: String,
    skill_id: String,
    runtime: McpRuntime,
) {
    let manifest = runtime.manifest().clone();
    runtime.destroy();
    let engine_clone = engine.clone();
    let request_id_clone = request_id.clone();
    let skill_id_clone = skill_id.clone();
    let skill_name = manifest.name.clone();
    tauri::async_runtime::spawn(async move {
        use futures::FutureExt;

        // Catch panics so a misbehaving skill can't crash the app.
        // This is the "sandbox boundary" for skill execution.
        let result = std::panic::AssertUnwindSafe(async {
            engine_clone.run(request_id, skill_id, manifest).await;
        })
        .catch_unwind()
        .await;

        if let Err(panic_info) = result {
            let msg = if let Some(s) = panic_info.downcast_ref::<&str>() {
                format!("skill execution panicked: {}", s)
            } else if let Some(s) = panic_info.downcast_ref::<String>() {
                format!("skill execution panicked: {}", s)
            } else {
                "skill execution panicked (unknown payload)".to_string()
            };
            log::error!("[automation/engine] {}", msg);
            // Best-effort: emit a failure event so the UI knows.
            let _ = engine_clone.app_handle().emit(
                EVENT_FAILED,
                serde_json::json!({
                    "request_id": request_id_clone,
                    "skill_id": skill_id_clone,
                    "skill_name": skill_name,
                    "reason": msg,
                    "timestamp": now_unix_secs(),
                }),
            );
        }
    });
}
