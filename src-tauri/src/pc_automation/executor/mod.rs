// Copyright (c) 2026 tupAI
//
// ============================================================================
// AdaptiveExecutor — main loop for skill-driven execution (Doc1 §5)
// ============================================================================

use std::cell::RefCell;
use std::sync::Arc;
use std::time::Instant;

use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter};
use tokio::time::sleep;

use crate::pc_automation::logger as pc_log;
use crate::pc_automation::router::PcRouter;
use crate::pc_automation::router;
use crate::pc_automation::step::{RouterError, StepStrategy};
use crate::pc_automation::vlm_rescue::analyzer::{RescueContext, VlmAction};
use crate::pc_automation::vlm_rescue::screenshot;

// Submodules are declared `pub` so the integration test tree
// (`pc_automation::tests::integration_uirpa`, owned by
// Callers reach `executor::selector` / `executor::retry`
// / `executor::conditions` for cross-module assertions. The
// execution layer (commands::uirpa, the front-end bridge,
// etc.) only touches the `pub use` re-exports below, so this
// does not expand the runtime API surface beyond the tests'
// needs.
pub mod conditions;
pub mod error_handler;
pub mod prompt_registry;
pub mod retry;
pub mod selector;
pub mod state_graph;
pub mod system12;

// Re-export the skill data-model types the executor's public
// surface relies on. The integration test tree reaches them
// as `pc_automation::executor::Selector` etc.; the IPC layer
// imports them via the executor barrel for the same reason.
// We deliberately do NOT re-export *all* skill types — only
// the ones that the executor's public API or the integration
// tests actually touch — to keep the re-export surface
// narrow. `pub use` doubles as the local `use`, so this is
// the only place these names need to appear.
// `#[allow(unused_imports)]` because `Selector` and `WaitCondition`
// are only consumed by `#[cfg(test)]` modules; the executor's
// runtime code reaches them through the `skill::types` path.
#[allow(unused_imports)]
pub use crate::pc_automation::skill::types::{Selector, SelectorKind, Skill, SkillStep, WaitCondition};

use conditions::{evaluate_validation, evaluate_wait_condition};
use error_handler::{ErrorHandlerChain, ExecutionError, RecoveryAction};
use retry::RetryPolicy;
use selector::{LocatedElement, MultiPrioritySelector};
use state_graph::{GraphSnapshot, StepGraph};
use system12::{StepTier, System12Config, System12Router};

// ============================================================================
// Tauri event names — front-end subscribes to all of these
// ============================================================================

pub const EVENT_SKILL_STARTED: &str = "uirpa_skill_started";
pub const EVENT_STEP_STARTED: &str = "uirpa_step_started";
pub const EVENT_STEP_SUCCEEDED: &str = "uirpa_step_succeeded";
pub const EVENT_STEP_FAILED: &str = "uirpa_step_failed";
pub const EVENT_VLM_RESCUE: &str = "uirpa_vlm_rescue";
pub const EVENT_PAUSED_FOR_USER: &str = "uirpa_paused_for_user";
pub const EVENT_SKILL_COMPLETED: &str = "uirpa_skill_completed";
pub const EVENT_SKILL_FAILED: &str = "uirpa_skill_failed";

// ============================================================================
// Public data types — the IPC payload shape
// ============================================================================

#[derive(Debug, Clone, Copy, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum ExecutionStatus {
    #[default]
    Running,
    Succeeded,
    Failed,
    PausedForUser,
}

/// Final / in-flight summary of a single skill run. Mirrors
/// Doc1 §6 "skill run receipt" so the front-end can show
/// "✓ 完成 8/8 · 1.2s" without a second IPC roundtrip.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ExecutionReceipt {
    pub exec_id: String,
    pub skill_id: String,
    pub status: ExecutionStatus,
    pub started_at_unix_ms: i64,
    pub finished_at_unix_ms: Option<i64>,
    pub total_steps: usize,
    pub completed_steps: usize,
    pub vlm_rescue_count: u32,
    pub handler_recovery_count: u32,
    pub current_step_id: Option<String>,
    pub last_error: Option<String>,
    pub step_durations_ms: Vec<u64>,
}

impl ExecutionReceipt {
    // 入参用 `impl std::fmt::Display` 而非 `impl Into<String>`: 按项目约定
    // "字符串转换用 .to_string() 不用 .into()", 而在泛型 `T: Into<String>` 上
    // 无法调用 `.to_string()` (那要求 `T: Display`/`ToString`)。所有现存调用方
    // 都传 `&str` / `String` / `&String` (均实现 `Display`), 故 API 兼容性不变,
    // 同时 body 内能按约定使用 `.to_string()`。
    pub fn new(exec_id: impl std::fmt::Display, skill_id: impl std::fmt::Display) -> Self {
        Self {
            exec_id: exec_id.to_string(),
            skill_id: skill_id.to_string(),
            status: ExecutionStatus::Running,
            started_at_unix_ms: now_unix_ms(),
            finished_at_unix_ms: None,
            total_steps: 0,
            completed_steps: 0,
            vlm_rescue_count: 0,
            handler_recovery_count: 0,
            current_step_id: None,
            last_error: None,
            step_durations_ms: Vec::new(),
        }
    }

    pub fn fail(mut self, reason: impl std::fmt::Display) -> Self {
        self.status = ExecutionStatus::Failed;
        self.finished_at_unix_ms = Some(now_unix_ms());
        self.last_error = Some(reason.to_string());
        self
    }

    pub fn pause_for_user(mut self, reason: impl std::fmt::Display) -> Self {
        self.status = ExecutionStatus::PausedForUser;
        self.finished_at_unix_ms = Some(now_unix_ms());
        self.last_error = Some(reason.to_string());
        self
    }

    pub fn complete(mut self) -> Self {
        self.status = ExecutionStatus::Succeeded;
        self.finished_at_unix_ms = Some(now_unix_ms());
        self
    }
}

// ============================================================================
// VLM rescue — facade trait.
// ============================================================================

/// Facade trait the executor uses to invoke the VLM rescue.
/// The real implementation is `pc_automation::vlm_rescue::VlmRescue`,
/// which receives a `RescueContext` (the failing step + intent +
/// app profile + tier errors + attempt index) and returns a
/// `VlmAction` (pixel coords + confidence + rationale).
///
/// The trait is `Send + Sync` so the executor can hold it in
/// an `Arc<dyn VlmRescue>`. `None` means "no VLM available";
/// the executor gracefully degrades to "go straight to the
/// error-handler chain" in that case.
pub trait VlmRescue: Send + Sync {
    fn try_rescue(
        &self,
        ctx: &crate::pc_automation::vlm_rescue::analyzer::RescueContext<'_>,
        screenshot_png: &[u8],
    ) -> futures::future::BoxFuture<'_, Result<VlmAction, String>>;

    /// True once the configured `max_attempts` cap is reached.
    /// Lets the executor short-circuit before burning another
    /// screenshot.
    fn exhausted(&self) -> bool;

    /// Current attempt count (mirrors `VlmRescue::attempts`).
    /// Used by the executor to populate
    /// `RescueContext::attempt_index` for the dynamic-prompt
    /// builder.
    fn attempts(&self) -> u32;
}

// ============================================================================
// The executor itself
// ============================================================================

/// The main executor. One instance lives in `UirpaState` and is
/// shared across all Tauri commands. The fields are `pub` so the
/// `commands::uirpa` layer can introspect them for diagnostics.
pub struct AdaptiveExecutor {
    pub router: Arc<PcRouter>,
    pub app: AppHandle,
    /// `None` while unavailable; the executor gracefully
    /// degrades to "no VLM rescue" in that case.
    pub vlm: Option<Arc<dyn VlmRescue>>,
    /// Default back-off between attempts of the *primary* step
    /// (i.e. before the chain is consulted). Per-step overrides
    /// land when the `Skill` schema grows a `retry_policy` field.
    pub retry_policy: RetryPolicy,
    /// Default ceiling for the inner retry loop. Doc1 §2.3 says
    /// "3 attempts" — the same as the v5 engine's `MAX_ATTEMPTS`.
    pub max_attempts: u32,
    /// System 1 / System 2 路由器 —— 缓存历史成功的 selector →
    /// strategy 映射,减少 router 调用次数。详见
    /// `executor::system12`。本期默认开启,可在配置中关掉。
    /// 用 `RefCell` 包裹是因为 `execute_skill` 拿的是 `&self`
    /// (公共 API 不变),需要 interior mutability 才能在主循环
    /// 多次调用 `classify` / `record_outcome`。
    pub system12: RefCell<System12Router>,
}

impl AdaptiveExecutor {
    pub fn new(router: Arc<PcRouter>, app: AppHandle) -> Self {
        Self {
            router,
            app,
            vlm: None,
            retry_policy: RetryPolicy::default(),
            max_attempts: 3,
            system12: RefCell::new(System12Router::new(System12Config::default())),
        }
    }

    pub fn with_vlm(mut self, vlm: Arc<dyn VlmRescue>) -> Self {
        self.vlm = Some(vlm);
        self
    }

    /// 替换默认的 System 1/2 路由器(测试 / 配置注入用)。
    pub fn with_system12(mut self, system12: System12Router) -> Self {
        self.system12 = RefCell::new(system12);
        self
    }

    /// Drive `skill` to completion. This is the entry point the
    /// `uirpa_execute_skill` command calls. Returns the final
    /// `ExecutionReceipt`; emits progress events along the way.
    pub async fn execute_skill(
        &self,
        skill: &Skill,
        params: &serde_json::Map<String, serde_json::Value>,
    ) -> ExecutionReceipt {
        let exec_id = format!("exec-{}", uuid::Uuid::new_v4());
        let mut receipt = ExecutionReceipt::new(&exec_id, &skill.skill_id);
        receipt.total_steps = skill.steps.len();
        receipt.status = ExecutionStatus::Running;

        let _ = self.app.emit(
            EVENT_SKILL_STARTED,
            serde_json::json!({
                "execId": exec_id,
                "skillId": skill.skill_id,
                "totalSteps": skill.steps.len(),
            }),
        );

        let chain = ErrorHandlerChain::new(skill.error_handlers.clone());

        // ---- System 1/2 集成:给当前 skill 构造一个可 checkpoint
        // 的执行图,主循环每个 step 完成时调一次
        // `checkpoint_current`,未来切换到 graph 驱动时只需把
        // `for ... in skill.steps` 替换为 `while let Some(node) =
        // graph.next()`。
        let mut graph = self._build_graph(skill, &exec_id, now_unix_ms());

        // Track F — interaction-bound template variables. Populated
        // by `automation:ask_user` prompts; later steps' `SkillAction::Input`
        // / `Hotkey` values are rendered against this map (merged with the
        // skill's static `params`) so `{{bind_to_var}}` placeholders are
        // substituted with the user's answers at execution time. The render
        // happens in `execute_skill_action` (see `render_ctx` arg). Prompt
        // answers override static params when keys collide — runtime answers
        // win over static config.
        let mut interaction_vars: serde_json::Map<String, serde_json::Value> =
            serde_json::Map::new();

        for (index, step) in skill.steps.iter().enumerate() {
            receipt.current_step_id = Some(step.id.clone());
            let step_start = Instant::now();
            // 主循环每 step 开始时把图节点切到 Running(并
            // attempts++)。本期 main loop 还是 `for ... enumerate`
            // 驱动,但图状态已经正确维护,后续接续跑(recover)
            // 可以直接复用。
            graph.advance(now_unix_ms());

            // ---- System 1/2 分类 --------------------------------
            // System 1 命中:打 log,本期不真正"跳过 router"——
            // 需要改 `MultiPrioritySelector::try_locate` 接口才能
            // 真正短路,留 TODO。本期仍走完整 cascade,但缓存已经
            // 在记录,以便未来切换。
            let tier = self
                .system12
                .borrow_mut()
                .classify(&skill.intent, step, now_unix_ms());
            if self.system12.borrow().enabled && tier == StepTier::System1 {
                pc_log::info(&format!(
                    "step[{}] System 1 cache hit, would skip router (TODO: wire)",
                    step.id
                ));
            }

            let _ = self.app.emit(
                EVENT_STEP_STARTED,
                serde_json::json!({
                    "execId": exec_id,
                    "stepId": step.id,
                    "index": index,
                    "totalSteps": skill.steps.len(),
                    "description": step.description,
                }),
            );

            // ---- 1. wait condition --------------------------------
            if let Some(wait) = &step.wait_condition {
                if let Err(e) = evaluate_wait_condition(wait, &self.router).await {
                    // ---- System 1/2 + StateGraph:失败记入缓存 ----
                    self.record_step_failure(
                        &skill.intent,
                        step,
                        StepStrategy::Uia,
                    );
                    graph.mark_current_failed(
                        format!("wait condition failed: {}", e),
                        now_unix_ms(),
                    );
                    let _ = self.checkpoint_current_with_graph(&graph);
                    return self
                        .fail_step(receipt, step, format!("wait condition failed: {}", e))
                        .await;
                }
            }

            // ---- 1.5 interactive prompt (Track F "互动输入") ----
            // If the step carries an `InteractionPrompt`, pause here,
            // emit `automation:ask_user`, and wait for the front-end
            // to call `automation_answer_prompt` (delivered via
            // `prompt_registry`). The answer is bound to
            // `prompt.bind_to_var` in `interaction_vars` so later
            // steps can reference it. On timeout / cancel we fall
            // back to `default_value` (or Null) and continue.
            if let Some(prompt) = &step.interaction {
                // 校验 bind_to_var: 空字符串 (或纯空白) 会往 interaction_vars 插入
                // 空键, 后续步骤的模板 {{}} 无法引用, 静默失败。提前 fail_step 给出
                // 清晰错误, 与上方 wait_condition 失败的处理风格一致。
                if prompt.bind_to_var.trim().is_empty() {
                    graph.mark_current_failed(
                        "interaction prompt has empty bind_to_var".to_string(),
                        now_unix_ms(),
                    );
                    let _ = self.checkpoint_current_with_graph(&graph);
                    return self
                        .fail_step(
                            receipt,
                            step,
                            "interaction prompt has empty bind_to_var".to_string(),
                        )
                        .await;
                }
                use crate::pc_automation::skill::types::AskUserPayload;
                let correlation_id = if prompt.prompt_id.is_empty() {
                    format!("pmt_{}", uuid::Uuid::new_v4().simple())
                } else {
                    prompt.prompt_id.clone()
                };
                let payload = AskUserPayload {
                    correlation_id: correlation_id.clone(),
                    skill_id: skill.skill_id.clone(),
                    step_id: step.id.clone(),
                    prompt: prompt.clone(),
                };
                // Register the oneshot *before* emitting so a fast
                // front-end answer cannot race ahead of the
                // registration (deliver would otherwise find no
                // pending prompt and the answer would be lost).
                let rx = crate::pc_automation::executor::prompt_registry::register(
                    &correlation_id,
                );
                let _ = self.app.emit("automation:ask_user", &payload);
                // timeout_ms == 0 视为无超时 (直接等用户回答); >0 用
                // tokio::time::timeout 包裹。原实现无脑包 timeout, timeout_ms=0
                // 会被 tokio 当成立即超时, prompt 永远走 default 分支。两条路径
                // 共用同一份"取消/超时回退 default_value"逻辑, 用闭包提取避免重复。
                let fallback_to_default = || {
                    // 超时/取消路径必须清理 prompt_registry 中的 pending 条目,
                    // 否则 oneshot::Sender 永久驻留 HashMap 造成内存泄漏
                    // (deliver 路径会自行 remove, 但纯超时不会)。cancel 对已
                    // 移除的条目是 no-op, 三种子情况都安全。
                    crate::pc_automation::executor::prompt_registry::cancel(&correlation_id);
                    prompt
                        .default_value
                        .clone()
                        .unwrap_or(serde_json::Value::Null)
                };
                let answer = if prompt.timeout_ms == 0 {
                    // 0 = 无超时, 直接等用户回答。
                    match rx.await {
                        Ok(a) if !a.cancelled => a.value,
                        // Cancelled / sender dropped → 回退 default_value。
                        _ => fallback_to_default(),
                    }
                } else {
                    match tokio::time::timeout(
                        std::time::Duration::from_millis(prompt.timeout_ms),
                        rx,
                    )
                    .await
                    {
                        Ok(Ok(a)) if !a.cancelled => a.value,
                        // Cancelled / sender dropped / timed out → 回退 default_value。
                        _ => fallback_to_default(),
                    }
                };
                interaction_vars.insert(prompt.bind_to_var.clone(), answer);
            }

            // ---- 2. multi-priority select ------------------------
            let mps = MultiPrioritySelector::from_element(&step.element_selector);
            let located = match mps.try_locate(&self.router).await {
                Ok(l) => l,
                Err(router_err) => {
                    match router_err {
                        // Both structured tiers (domain primary + OCR) missed.
                        // This is the v5 trigger for VLM rescue — the router
                        // has done all it can; the executor must escalate to
                        // the pre-error VLM path. (uirap改造技术方案.md §4)
                        RouterError::StructuredMiss { primary, fallback } => {
                            match self
                                .handle_structured_miss(
                                    &chain,
                                    &skill.intent,
                                    step,
                                    &mps,
                                    &primary,
                                    &fallback,
                                    &exec_id,
                                    &mut receipt,
                                )
                                .await
                            {
                                Ok(l) => l,
                                Err(failed_receipt) => return failed_receipt,
                            }
                        }
                        // Primary tier never even got to run (e.g. parse
                        // error on the selector string). VLM rescue is not
                        // useful here — there is no screen region of
                        // interest yet. Go straight to the error-handler
                        // chain.
                        RouterError::PrimaryMiss(reason) => {
                            if !self
                                .recover_via_chain(
                                    &chain,
                                    &ExecutionError::SelectorMiss {
                                        attempts: 1,
                                        last_strategy: None,
                                    },
                                    step,
                                )
                                .await
                            {
                                // ---- System 1/2 + StateGraph 记录失败 ----
                                self.record_step_failure(
                                    &skill.intent,
                                    step,
                                    StepStrategy::Uia,
                                );
                                graph.mark_current_failed(
                                    format!("primary miss (no VLM path): {}", reason),
                                    now_unix_ms(),
                                );
                                let _ = self.checkpoint_current_with_graph(&graph);
                                return self
                                    .fail_step(
                                        receipt,
                                        step,
                                        format!("primary miss (no VLM path): {}", reason),
                                    )
                                    .await;
                            }
                            match mps.try_locate(&self.router).await {
                                Ok(l) => {
                                    receipt.handler_recovery_count += 1;
                                    l
                                }
                                Err(_) => {
                                    // ---- System 1/2 + StateGraph 记录失败 ----
                                    self.record_step_failure(
                                        &skill.intent,
                                        step,
                                        StepStrategy::Uia,
                                    );
                                    graph.mark_current_failed(
                                        "primary miss after handler recovery".to_string(),
                                        now_unix_ms(),
                                    );
                                    let _ = self.checkpoint_current_with_graph(&graph);
                                    return self
                                        .fail_step(
                                            receipt,
                                            step,
                                            "primary miss after handler recovery",
                                        )
                                        .await;
                                }
                            }
                        }
                    }
                }
            };

            // ---- 4.5 执行 SkillAction(Input/Hotkey/Wait) -------------
            // router 内部 try_uia/try_cdp 已隐式执行 Click,所以这里只处理非 Click 动作。
            // 元素已经被 click 过(获取了焦点),所以 Input/Hotkey 直接用 enigo 全局键盘输入。
            // 之前没这段代码 → 所有非 Click 动作都被静默忽略。
            //
            // 构建 render 上下文: skill 静态 params + 运行时 interaction_vars
            // (后者覆盖前者)。这样后续步骤的 Input/Hotkey 模板里 {{bind_to_var}}
            // 能被替换成用户在 InteractionPrompt 里回答的值。无模板的 value
            // 原样返回 (render_template 只扫描 {{...}}, 无占位符即 verbatim 返回)。
            let mut render_ctx = params.clone();
            // 应用 skill 声明的默认值: caller 未传 (params 中缺失) 但有 default
            // 的参数填入默认值。否则模板里的 {{param}} 占位符在用户省略该参数时
            // 会因 "missing parameter" 报错, 而不是用 skill 声明的 default 兜底。
            // 注意必须放在 interaction_vars 合并之前 —— 用户运行时回答 / 静态 default
            // 都不应被 caller 显式传入的 params 覆盖, 而后者已经包含在 clone() 里了。
            for p in &skill.parameters {
                if !render_ctx.contains_key(&p.name) {
                    if let Some(d) = &p.default {
                        render_ctx.insert(p.name.clone(), d.clone());
                    }
                }
            }
            for (k, v) in &interaction_vars {
                render_ctx.insert(k.clone(), v.clone());
            }
            if let Err(e) = self
                .execute_skill_action(step, located.strategy_used, &render_ctx)
                .await
            {
                if !self
                    .recover_via_chain(
                        &chain,
                        &ExecutionError::SelectorMiss {
                            attempts: 1,
                            last_strategy: Some(located.strategy_used),
                        },
                        step,
                    )
                    .await
                {
                    self.record_step_failure(
                        &skill.intent,
                        step,
                        located.strategy_used,
                    );
                    graph.mark_current_failed(
                        format!("action failed: {}", e),
                        now_unix_ms(),
                    );
                    let _ = self.checkpoint_current_with_graph(&graph);
                    return self
                        .fail_step(receipt, step, format!("action failed: {}", e))
                        .await;
                }
                receipt.handler_recovery_count += 1;
            }

            // ---- 5. post-action validation -----------------------
            if let Some(val) = &step.post_action_validation {
                if let Err(e) = evaluate_validation(val, &self.router).await {
                    if !self
                        .recover_via_chain(
                            &chain,
                            &ExecutionError::ValidationFail { reason: e.clone() },
                            step,
                        )
                        .await
                    {
                        // ---- System 1/2 + StateGraph 记录失败 ----
                        self.record_step_failure(
                            &skill.intent,
                            step,
                            located.strategy_used,
                        );
                        graph.mark_current_failed(
                            format!("validation failed: {}", e),
                            now_unix_ms(),
                        );
                        let _ = self.checkpoint_current_with_graph(&graph);
                        return self
                            .fail_step(receipt, step, format!("validation failed: {}", e))
                            .await;
                    }
                    receipt.handler_recovery_count += 1;
                }
            }

            receipt.completed_steps += 1;
            receipt
                .step_durations_ms
                .push(step_start.elapsed().as_millis() as u64);

            // ---- System 1/2 记录成功命中 ------------------------
            // 把这次成功的 selector / strategy / latency 记入缓存,
            // 下次相同 (intent, step, selector) 三元组访问时
            // 才有机会命中 System 1。`primary_selector_used` 从
            // 当前 step 的 primary selector 拿(本期 LocatedElement
            // 没带 selector 字符串,留 TODO 把字符串塞进
            // LocatedElement 后可改用 `located.selector_value`)。
            let primary_selector_used = step
                .element_selector
                .primary
                .value
                .clone();
            self.system12.borrow_mut().record_outcome(
                &skill.intent,
                step,
                &primary_selector_used,
                located.strategy_used,
                located.latency_ms,
                true,
                now_unix_ms(),
            );

            // ---- StateGraph 标记完成 -----------------------------
            graph.mark_current_completed(
                located.strategy_used,
                located.action_taken.clone(),
                now_unix_ms(),
            );
            // 写一份 checkpoint(外部可拉到/落盘)。
            let _ = self.checkpoint_current_with_graph(&graph);

            let _ = self.app.emit(
                EVENT_STEP_SUCCEEDED,
                serde_json::json!({
                    "execId": exec_id,
                    "stepId": step.id,
                    "index": index,
                    "latencyMs": located.latency_ms,
                    "strategyUsed": located.strategy_used,
                    "selectorKind": located.selector_kind,
                }),
            );
        }

        let _ = self.app.emit(
            EVENT_SKILL_COMPLETED,
            serde_json::json!({
                "execId": exec_id,
                "skillId": skill.skill_id,
                "completedSteps": receipt.completed_steps,
                "totalSteps": receipt.total_steps,
            }),
        );
        receipt.complete()
    }

    /// Handle `RouterError::StructuredMiss` — the cascade
    /// (domain primary + OCR fallback) missed on every
    /// selector. The v5 flow is:
    ///
    ///   1. **VLM rescue**. We capture a screenshot
    ///      of the failing region, build a `RescueContext` (so
    ///      the cloud LLM can intelligently draft a prompt, or
    ///      we fall back to the fixed template), and ask the
    ///      VLM for pixel coords + action. If the VLM returns
    ///      a `VlmAction` with `confidence >= threshold` we
    ///      wrap it in a `LocatedElement` with
    ///      `strategy_used = StepStrategy::Vlm` and proceed.
    ///
    ///   2. **Error-handler chain**. If VLM is
    ///      unavailable, exhausted, or the rescue failed, the
    ///      executor walks the chain. A matching handler
    ///      returns a `RecoveryAction` and the executor
    ///      re-runs the primary step.
    ///
    ///   3. **Fail the step**. If no handler matched, the
    ///      executor marks the step failed and emits
    ///      `uirpa_step_failed` + `uirpa_skill_failed`.
    ///
    /// Returns `Ok(LocatedElement)` on success (VLM or
    /// chain-retry hit). Returns `Err(ExecutionReceipt)` on
    /// failure — the receipt is the *failed* state, so the
    /// caller can `return` it directly.
    async fn handle_structured_miss(
        &self,
        chain: &ErrorHandlerChain,
        skill_intent: &str,
        step: &SkillStep,
        mps: &MultiPrioritySelector,
        primary_err: &str,
        fallback_err: &str,
        exec_id: &str,
        receipt: &mut ExecutionReceipt,
    ) -> Result<LocatedElement, ExecutionReceipt> {
        // ---- 1. VLM rescue -------------------------------------
        if let Some(vlm) = &self.vlm {
            if !vlm.exhausted() {
                // Build the rescue context. The cloud LLM (or the
                // fixed-template fallback) reads this to compose a
                // tailored prompt.
                let ctx = RescueContext {
                    step_summary: &step.description,
                    intent: &step.intent,
                    app_profile: None,
                    primary_err: Some(primary_err),
                    fallback_err: Some(fallback_err),
                    attempt_index: vlm.attempts(),
                };

                // Capture a screenshot. On failure we hand an
                // empty buffer to the rescue; the rescue's own
                // input-validation guard will reject it with a
                // deterministic error string (no surprise panic).
                let screenshot_png =
                    screenshot::capture_focused_region().await.unwrap_or_default();

                match vlm.try_rescue(&ctx, &screenshot_png).await {
                    Ok(action) => {
                        receipt.vlm_rescue_count += 1;

                        // VLM rescue 返回像素坐标，必须在此处执行真实点击。
                        // 主循环 execute_skill_action 对 Click 动作是 no-op
                        // (假设 router 已隐式 click)，所以 VLM 路径的点击
                        // 不能依赖 execute_skill_action，必须在这里用 cua_click
                        // 真正点击 VLM 识别出的坐标。
                        // cua_click 优先使用 Cua Driver（后台输入），
                        // 不可用时降级到 enigo（前台输入）。
                        let vlm_x = action.target.x;
                        let vlm_y = action.target.y;
                        let click_outcome = router::cua_click(vlm_x, vlm_y).await;

                        let click_ok = match click_outcome {
                            Ok(()) => {
                                pc_log::info(&format!(
                                    "VLM rescue click executed at ({}, {}) for step[{}]",
                                    action.target.x, action.target.y, step.id
                                ));
                                true
                            }
                            Err(click_err) => {
                                pc_log::warn(&format!(
                                    "VLM rescue click failed for step[{}]: {} — falling through to error chain",
                                    step.id, click_err
                                ));
                                false
                            }
                        };

                        if click_ok {
                            let _ = self.app.emit(
                                EVENT_VLM_RESCUE,
                                serde_json::json!({
                                    "execId": exec_id,
                                    "stepId": step.id,
                                    "latencyMs": 0u64,
                                    "strategyUsed": "vlm",
                                    "action": &action,
                                }),
                            );
                            return Ok(vlm_action_to_located(&action));
                        }
                        // 点击失败 → fall through to the error-handler chain below
                    }
                    Err(vlm_err) => {
                        pc_log::warn(&format!(
                            "VLM rescue failed for step[{}]: {}",
                            step.id, vlm_err
                        ));
                        // Fall through to the error-handler chain.
                    }
                }
            } else {
                pc_log::warn(&format!(
                    "VLM rescue exhausted for step[{}], escalating to error chain",
                    step.id
                ));
            }
        }

        // ---- 2. Error-handler chain -----------------------------
        if self
            .recover_via_chain(
                chain,
                &ExecutionError::SelectorMiss {
                    attempts: self.max_attempts,
                    last_strategy: Some(StepStrategy::Ocr),
                },
                step,
            )
            .await
        {
            match mps.try_locate(&self.router).await {
                Ok(l) => {
                    receipt.handler_recovery_count += 1;
                    return Ok(l);
                }
                Err(e) => {
                    // ---- System 1/2 + StateGraph 记录失败 ----
                    self.record_step_failure(
                        skill_intent,
                        step,
                        StepStrategy::Ocr,
                    );
                    return Err(self
                        .fail_step(
                            // `mem::take` moves the current receipt
                            // out and leaves a default placeholder
                            // behind so the `&mut receipt` borrow
                            // is released. Cheaper than constructing
                            // a fresh `ExecutionReceipt` here.
                            std::mem::take(receipt),
                            step,
                            format!("selector miss after handler recovery: {}", e),
                        )
                        .await);
                }
            }
        }

        // ---- 3. Give up -----------------------------------------
        // ---- System 1/2 + StateGraph 记录失败 ----
        self.record_step_failure(
            skill_intent,
            step,
            StepStrategy::Ocr,
        );
        Err(self
            .fail_step(
                // `mem::take` moves the current receipt out and
                // leaves a default placeholder behind so the
                // `&mut receipt` borrow is released. See the
                // matching site above.
                std::mem::take(receipt),
                step,
                format!(
                    "structured miss (primary={}, fallback={}); VLM unavailable and no handler matched",
                    primary_err, fallback_err
                ),
            )
            .await)
    }

    /// Try to recover via the handler chain. Returns `true` if a
    /// handler matched (regardless of what its action did); the
    /// caller is then responsible for re-attempting the primary
    /// step. Returns `false` if no handler matched.
    async fn recover_via_chain(
        &self,
        chain: &ErrorHandlerChain,
        err: &ExecutionError,
        step: &SkillStep,
    ) -> bool {
        match chain.try_handle(err, &self.router).await {
            Ok(Some(RecoveryAction::Abort)) => false,
            Ok(Some(RecoveryAction::PauseForUser { reason })) => {
                let _ = self.app.emit(
                    EVENT_PAUSED_FOR_USER,
                    serde_json::json!({
                        "stepId": step.id,
                        "reason": reason,
                    }),
                );
                false
            }
            Ok(Some(_)) => {
                // RetryPrimary / RunThenContinue — caller re-tries.
                true
            }
            Ok(None) => false,
            Err(e) => {
                pc_log::warn(&format!("handler chain error: {}", e));
                false
            }
        }
    }

    /// Convenience wrapper: emit `uirpa_step_failed`, then
    /// `uirpa_skill_failed`, then return the failed receipt.
    async fn fail_step(
        &self,
        receipt: ExecutionReceipt,
        step: &SkillStep,
        reason: impl Into<String>,
    ) -> ExecutionReceipt {
        let reason = reason.into();
        let _ = self.app.emit(
            EVENT_STEP_FAILED,
            serde_json::json!({
                "execId": receipt.exec_id,
                "stepId": step.id,
                "reason": reason,
            }),
        );
        let _ = self.app.emit(
            EVENT_SKILL_FAILED,
            serde_json::json!({
                "execId": receipt.exec_id,
                "skillId": receipt.skill_id,
                "reason": reason,
            }),
        );
        // Tiny grace so the front-end has a chance to subscribe
        // to `uirpa_skill_failed` before the receipt is
        // returned. Best-effort only — the runtime is not
        // expected to hold this open.
        sleep(std::time::Duration::from_millis(10)).await;
        receipt.fail(reason)
    }

    // ----------------------------------------------------------------
    // StateGraph / System 1/2 辅助方法(本期对内 API,不进 commands)
    // ----------------------------------------------------------------

    /// 构造一个 StateGraph(线性展开 Skill 的 steps)。主循环每
    /// 次 `execute_skill` 都会构造一个新的;未来支持 resume 时可
    /// 改用 `StepGraph::restore`。
    pub fn _build_graph(&self, skill: &Skill, exec_id: &str, now_ms: i64) -> StepGraph {
        StepGraph::from_skill_linear(skill, exec_id, now_ms)
    }

    /// 取一份当前执行快照(主循环每 step 完成时调一次)。
    /// 本期主循环自己持有 graph 并把快照通过
    /// `checkpoint_current_with_graph` 写出来 —— 这个 `&self` 版本
    /// 保留作为"外部(测试 / 持久化层)手动打 checkpoint"的入口,
    /// 内部用 `None` 占位(实际数据由 `checkpoint_current_with_graph`
    /// 携带)。
    pub fn checkpoint_current(
        &self,
        _exec_id: &str,
        _skill_id: &str,
    ) -> Option<GraphSnapshot> {
        // 本期 executor 内部持有 graph,外部 API 只能拿 None。
        // 集成路径请走主循环 —— 它每 step 完成后会调
        // `checkpoint_current_with_graph`,真实数据在那里。
        None
    }

    /// 主循环内每 step 完成时调一次,生成并"提交"一份快照。
    /// 本期只打 log(不真正落盘),留给后续 PR 接持久化。
    pub fn checkpoint_current_with_graph(&self, graph: &StepGraph) -> GraphSnapshot {
        let mut owned = graph.clone();
        let snap = owned.checkpoint(now_unix_ms());
        pc_log::info(&format!(
            "StateGraph checkpoint: exec_id={} cursor={}/{} checkpoint_at={:?}",
            snap.exec_id,
            snap.cursor,
            snap.order.len(),
            snap.last_checkpoint_at
        ));
        snap
    }

    /// 从快照恢复。语义上等于"重建一个 executor 的执行图
    /// 状态",但 executor 实例本身是无状态的(graph 存在外部
    /// 持久化层),所以本期只做"快照合法性"校验。
    pub fn restore_from_snapshot(snap: GraphSnapshot) -> Result<(), String> {
        StepGraph::restore(snap).map(|_| ())
    }

    /// 把 step 失败结果记入 System 1/2 缓存(失败权重下调
    /// confidence)。strategy 拿不到时默认 `Uia` —— 这是
    /// 一个"不准确的占位",后续 PR 可在 `LocatedElement` 里
    /// 塞 selector 字符串 + strategy 后改用真值。
    fn record_step_failure(
        &self,
        intent: &str,
        step: &SkillStep,
        strategy: StepStrategy,
    ) {
        let primary_selector = step.element_selector.primary.value.clone();
        self.system12.borrow_mut().record_outcome(
            intent,
            step,
            &primary_selector,
            strategy,
            0,
            false,
            now_unix_ms(),
        );
    }

    /// 执行 SkillStep 的 SkillAction(Input/Hotkey/Wait)。
    /// Click 已由 router 的 try_uia/try_cdp 隐式执行(它们内部调 click),
    /// 所以这里只处理非 Click 动作。元素已被 click 过 → 已 focus,
    /// 因此 Input/Hotkey 直接用 enigo 全局键盘输入即可。
    ///
    /// `render_ctx` 是 skill 静态 `params` 与运行时 `interaction_vars` 的合并
    /// (后者覆盖前者),用于把 Input/Hotkey 的模板 `{{bind_to_var}}` /
    /// `{{param}}` 占位符替换成实际值。无占位符的 value 原样返回
    /// (`render_template` 只扫描 `{{...}}`,无占位符即 verbatim 返回)。
    /// 渲染失败 (缺占位符对应的变量) → 步骤失败并带清晰错误信息。
    async fn execute_skill_action(
        &self,
        step: &SkillStep,
        _strategy: StepStrategy,
        render_ctx: &serde_json::Map<String, serde_json::Value>,
    ) -> Result<(), String> {
        use crate::pc_automation::skill::template::render_template;
        use crate::pc_automation::skill::types::SkillAction;
        match &step.action {
            SkillAction::Click => {
                // 已由 router 内部 click 执行,这里什么都不做。
                Ok(())
            }
            SkillAction::Input { value } => {
                // 先渲染模板 ({{param}} / {{bind_to_var}}) 再输入。
                // 元素已 focus,直接用 enigo 输入渲染后的文本。
                let text = render_template(value, render_ctx)
                    .map_err(|e| format!("input template render: {}", e))?;
                tokio::task::spawn_blocking(move || enigo_type_text(&text))
                    .await
                    .map_err(|e| format!("input join: {}", e))?
            }
            SkillAction::Hotkey { keys } => {
                // keys 是 "+":分隔的键名组合,如 "Ctrl+Enter"。
                // 同样支持模板渲染 (例如 {{confirm_key}}+Enter)。
                let keys = render_template(keys, render_ctx)
                    .map_err(|e| format!("hotkey template render: {}", e))?;
                tokio::task::spawn_blocking(move || enigo_hotkey(&keys))
                    .await
                    .map_err(|e| format!("hotkey join: {}", e))?
            }
            SkillAction::Wait { ms } => {
                sleep(std::time::Duration::from_millis(*ms)).await;
                Ok(())
            }
        }
    }
}

/// 用 enigo 在当前焦点元素中输入文本。
/// 必须在 spawn_blocking 中调用(enigo 是同步阻塞)。
fn enigo_type_text(text: &str) -> Result<(), String> {
    use enigo::{Enigo, Keyboard, Settings};
    let mut enigo = Enigo::new(&Settings::default())
        .map_err(|e| format!("初始化输入设备失败: {}", e))?;
    enigo.text(text).map_err(|e| format!("输入文本失败: {}", e))?;
    Ok(())
}

/// 用 enigo 按下热键组合(如 "Ctrl+Enter")。
/// 必须在 spawn_blocking 中调用。
fn enigo_hotkey(keys: &str) -> Result<(), String> {
    use enigo::{Enigo, Keyboard, Key, Settings};
    let mut enigo = Enigo::new(&Settings::default())
        .map_err(|e| format!("初始化输入设备失败: {}", e))?;
    let parts: Vec<&str> = keys.split('+').map(|s| s.trim()).filter(|s| !s.is_empty()).collect();
    if parts.is_empty() {
        return Err(format!("empty hotkey: {}", keys));
    }
    let enigo_keys: Vec<Key> = parts.iter().map(|s| parse_enigo_key(s)).collect::<Result<Vec<_>, _>>().map_err(|e| format!("hotkey parse: {}", e))?;
    // 按下所有键。若中途某键 Press 失败, 必须把已按下的键反向 Release,
    // 否则键盘粘连 (如 Ctrl+Shift+Enter 中 Enter 失败, Ctrl+Shift 保持按下,
    // 后续输入全部变成热键组合)。
    let mut pressed: Vec<Key> = Vec::with_capacity(enigo_keys.len());
    for k in &enigo_keys {
        if let Err(e) = enigo.key(*k, enigo::Direction::Press) {
            // 尽力释放已按下的键, 释放失败不再追加错误信息
            for rk in pressed.iter().rev() {
                let _ = enigo.key(*rk, enigo::Direction::Release);
            }
            return Err(format!("press key 失败: {}", e));
        }
        pressed.push(*k);
    }
    // 反向松开
    for k in enigo_keys.iter().rev() {
        enigo.key(*k, enigo::Direction::Release).map_err(|e| format!("release key 失败: {}", e))?;
    }
    Ok(())
}

/// 把字符串键名映射到 enigo Key。仅覆盖常用键,未知键名返回错误。
fn parse_enigo_key(name: &str) -> Result<enigo::Key, String> {
    use enigo::Key;
    match name.to_lowercase().as_str() {
        "ctrl" | "control" => Ok(Key::Control),
        "shift" => Ok(Key::Shift),
        "alt" | "option" => Ok(Key::Alt),
        "meta" | "win" | "cmd" | "super" => Ok(Key::Meta),
        "enter" | "return" => Ok(Key::Return),
        "tab" => Ok(Key::Tab),
        "esc" | "escape" => Ok(Key::Escape),
        "space" => Ok(Key::Space),
        "backspace" => Ok(Key::Backspace),
        "delete" | "del" => Ok(Key::Delete),
        "up" => Ok(Key::UpArrow),
        "down" => Ok(Key::DownArrow),
        "left" => Ok(Key::LeftArrow),
        "right" => Ok(Key::RightArrow),
        "home" => Ok(Key::Home),
        "end" => Ok(Key::End),
        "pageup" => Ok(Key::PageUp),
        "pagedown" => Ok(Key::PageDown),
        #[cfg(target_os = "windows")]
        "insert" => Ok(Key::Insert),
        #[cfg(not(target_os = "windows"))]
        "insert" => Err("insert key not supported on this platform".to_string()),
        "caps" | "capslock" => Ok(Key::CapsLock),
        s if s.len() > 1 && s.starts_with('f') && s[1..].parse::<u8>().is_ok() => {
            let n: u8 = s[1..].parse().unwrap_or(0);
            match n {
                1 => Ok(Key::F1), 2 => Ok(Key::F2), 3 => Ok(Key::F3), 4 => Ok(Key::F4),
                5 => Ok(Key::F5), 6 => Ok(Key::F6), 7 => Ok(Key::F7), 8 => Ok(Key::F8),
                9 => Ok(Key::F9), 10 => Ok(Key::F10), 11 => Ok(Key::F11), 12 => Ok(Key::F12),
                _ => Err(format!("unsupported function key: F{}", n)),
            }
        }
        // 单字符走 Unicode,兼容 Ctrl+A/S/C/V 等常用热键
        s if s.chars().count() == 1 => {
            Ok(Key::Unicode(s.chars().next().unwrap()))
        }
        _ => Err(format!("unsupported key: {}", name)),
    }
}

fn now_unix_ms() -> i64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// Convert a `VlmAction` (pixel coords + confidence + rationale)
/// into the `LocatedElement` shape the executor's main loop
/// consumes. We tag the element with `StepStrategy::Vlm` and
/// `SelectorKind::Visual` so the downstream `uirpa_step_succeeded`
/// event accurately reflects the rescue path.
fn vlm_action_to_located(action: &VlmAction) -> LocatedElement {
    LocatedElement {
        strategy_used: StepStrategy::Vlm,
        selector_kind: SelectorKind::Visual,
        action_taken: format!(
            "vlm:click(x={}, y={}, conf={:.2})",
            action.target.x, action.target.y, action.confidence
        ),
        latency_ms: 0,
    }
}

// tupAI v5 §5.4 — sibling-file test pattern so the main barrel
// stays free of `#[cfg(test)]` noise.
#[cfg(test)]
#[path = "tests.rs"]
mod tests;
