// Copyright (c) 2026 tupAI
//
// tupAI v5 §6.1 — VLM 救援主流程。
//
// uirap v2 合并精简后,本文件只保留"VLM 救援"的主流程相关
// 符号:救援上下文(RescueContext)、救援策略(DynamicPromptConfig /
// build_dynamic_prompt)、阈值工具(DEFAULT_CONFIDENCE_THRESHOLD /
// is_action_acceptable)。
//
// 已下沉到 `pc_automation::ui_tars` 的"UI-TARS 协议层"符号
// (VlmAction / VlmTarget / COMPUTER_USE_TEMPLATE / build_prompt /
// parse_ui_tars_response / LlmCompleteFn / LlmCompleteFut / LlmMessage)
// 在本文件末尾 re-export,以保持老 import 路径(以及
// `pc_automation::vlm_rescue` 模块外的 `use ... vlm_rescue::analyzer
// ::build_prompt`)继续可用。
//
// `build_dynamic_prompt` 现在统一用 `pc_automation::ui_tars::llm
// ::try_call_llm` 调用云端 LLM,与 reflection::suggest /
// principles::distill 共用同一个 fallback 助手。

// ============================================================================
// RescueContext (救援上下文)
// ============================================================================

/// Context the executor hands to the dynamic-prompt builder. Pure
/// data — the cloud LLM reads it and produces a tailored prompt.
#[derive(Debug, Clone)]
pub struct RescueContext<'a> {
    /// One-line description of the failing step (built by the
    /// executor from the `SkillStep`).
    pub step_summary: &'a str,
    /// User-facing task description (e.g. "提交电商订单").
    pub intent: &'a str,
    /// Optional `AppProfile` id (e.g. `"ths_hexin"`). The LLM
    /// can use it to bias the prompt toward the renderer's
    /// quirks (Electron vs MFC).
    pub app_profile: Option<&'a str>,
    /// Tier error messages from the router (`primary` and
    /// `fallback`). Gives the LLM a hint of *why* the structured
    /// selectors missed so the prompt can compensate.
    pub primary_err: Option<&'a str>,
    pub fallback_err: Option<&'a str>,
    /// How many rescue attempts have already burned on this
    /// step in this skill run.
    pub attempt_index: u32,
}

// ============================================================================
// 阈值工具
// ============================================================================

/// Threshold below which a VLM action is rejected. Duplicated here
/// (it is also stored on `VlmRescue`) so callers that just have a
/// `VlmAction` in hand don't need to construct a `VlmRescue` to
/// validate it.
pub const DEFAULT_CONFIDENCE_THRESHOLD: f32 = 0.6;

/// Returns `true` iff `action.confidence >= threshold`.
pub fn is_action_acceptable(action: &VlmAction, threshold: f32) -> bool {
    action.confidence >= threshold
}

// ============================================================================
// Dynamic prompt 配置
// ============================================================================

/// Configuration for the dynamic-prompt LLM. Defaults to
/// `None` for the optional fields so the executor can opt out
/// (in which case we short-circuit to `build_prompt`).
#[derive(Clone, Default)]
pub struct DynamicPromptConfig {
    /// If `None`, `build_dynamic_prompt` short-circuits to
    /// `build_prompt` without ever touching the network. This
    /// is the right setting for offline / hermetic test runs.
    pub llm_complete_fn: Option<std::sync::Arc<dyn LlmCompleteFn>>,
}

impl std::fmt::Debug for DynamicPromptConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("DynamicPromptConfig")
            .field(
                "llm_complete_fn",
                &self.llm_complete_fn.as_ref().map(|_| "..."),
            )
            .finish()
    }
}

// ============================================================================
// DYNAMIC PROMPT (云端 LLM 调用主流程)
//
// uirap v2 关键变化: 不再内联 "match llm(messages).await { ... }"
// 模板,改用 `pc_automation::ui_tars::llm::try_call_llm` 一个
// 统一入口,与 reflection::suggest / principles::distill 共用。
// ============================================================================

/// The INTELLIGENT path. Calls the cloud LLM (text-only) to
/// compose a tailored prompt. On ANY error (no LLM wired up,
/// network error, rate-limit, non-UTF-8 response, empty
/// response) we return the fixed-template prompt so the rescue
/// loop is never blocked on the cloud LLM.
///
/// 云端 LLM 的角色:用 `COMPUTER_USE_TEMPLATE`(UI-TARS 协议)
/// 生成更具体的提示词,模板的 `## User Instruction` 段落
/// 必须保留,且必须用 `Thought: ... Action: ...` 双段输出。
#[allow(dead_code)] // 5.2
pub async fn build_dynamic_prompt(
    cfg: &DynamicPromptConfig,
    ctx: &RescueContext<'_>,
) -> String {
    // 1. 组装"请云端 LLM 用 UI-TARS 模板生成更具体提示词"的消息。
    let compose_request = build_compose_request(ctx);

    // 2. 统一调用入口(uirap v2 合并精简)。
    if let Some(response) =
        crate::pc_automation::ui_tars::try_call_llm(cfg.llm_complete_fn.as_ref(), compose_request)
            .await
    {
        return response;
    }

    // 3. 任意失败 → 静默 fallback 到固定模板。
    log_dynamic_fallback("llm_unavailable_or_failed", "");
    build_prompt(
        ctx.step_summary,
        ctx.intent,
        ctx.primary_err,
        ctx.fallback_err,
    )
    .await
}

/// 渲染"请云端 LLM 用 UI-TARS 模板生成更具体提示词"的消息。
///
/// 我们要求 LLM:
///   1. 复用 `COMPUTER_USE_TEMPLATE` 主体
///   2. 把 `## User Instruction` 段落填充为针对本次失败的具体指令
///   3. 强制使用 `Thought: ... Action: ...` 双段输出格式
#[allow(dead_code)] // 5.2
fn build_compose_request(ctx: &RescueContext<'_>) -> String {
    let profile_line = match ctx.app_profile {
        Some(p) => format!("应用: {p}"),
        None => "应用: 未知".to_string(),
    };
    let primary_line = match ctx.primary_err {
        Some(e) => format!("primary 错误: {e}"),
        None => "primary 错误: (无)".to_string(),
    };
    let fallback_line = match ctx.fallback_err {
        Some(e) => format!("fallback 错误: {e}"),
        None => "fallback 错误: (无)".to_string(),
    };
    format!(
        "你是一名资深的 UI 自动化测试工程师。请根据下面的救援上下文,为一个 \
         视觉大模型 (VLM) 撰写一段 **提示词 (prompt)**,让它分析屏幕截图并告诉 \
         我们的执行器下一步该点哪里 / 输入什么。\n\
         \n\
         ## 输出协议要求 (UI-TARS / COMPUTER_USE_DOUBAO)\n\
         - 必须使用以下固定模板 (ByteDance UI-TARS 训练数据格式):\n\
         ```\n\
         {template}\n\
         ```\n\
         - 把 `{{instruction}}` 替换为针对本次失败的具体指令\n\
         - 严格使用 `Thought: ... Action: ...` 双段格式\n\
         - 坐标用 `<|box_start|>x y<|box_end|>` 包裹\n\
         \n\
         ## 救援上下文\n\
         - 任务意图: {intent}\n\
         - 失败步骤: {step}\n\
         - {profile_line}\n\
         - 救援尝试序号: #{attempt}\n\
         - {primary_line}\n\
         - {fallback_line}\n\
         \n\
         ## 提示词要求\n\
         1. 用中文撰写 `## User Instruction` 段落,语气专业、简洁。\n\
         2. 必须明确告诉 VLM:\n\
            - 用户的任务意图 (从上面复制)\n\
            - 刚刚失败的那一步想做什么 (从 step_summary 推断)\n\
            - 应用类型与渲染器特点 (Electron / MFC / 自绘等)\n\
            - 如果上一次救援有错,告诉 VLM 不要再犯同样的错\n\
         3. 不要修改模板的 `## Output Format` 与 `## Action Space` 段落。\n\
         4. 不要包含任何额外的 markdown 代码块标记 (\"```\"),直接输出纯文本 prompt。\n\
         \n\
         请直接输出提示词,不要输出其它解释。",
        template = crate::pc_automation::ui_tars::COMPUTER_USE_TEMPLATE,
        intent = ctx.intent,
        step = ctx.step_summary,
        profile_line = profile_line,
        attempt = ctx.attempt_index,
        primary_line = primary_line,
        fallback_line = fallback_line,
    )
}

/// Centralized fallback logger. Kept as a no-op stub so the
/// module can stay hermetic during unit tests; production
/// wiring will route through `pc_automation::logger` once the
/// cloud LLM service handle is plumbed in.
#[allow(dead_code)] // 5.2
fn log_dynamic_fallback(reason: &str, detail: &str) {
    let _ = (reason, detail);
}

// ============================================================================
// 向后兼容 re-exports(uirap v2 合并精简)
// ============================================================================
//
// `pc_automation::ui_tars` 是这些符号的"权威位置",但老调用
// 路径 `use crate::pc_automation::vlm_rescue::analyzer::build_prompt`
// 等继续可用,避免一次性全量迁移。
// `#[allow(unused_imports)]` because `LlmMessage` and `VlmTarget`
// are only consumed by `#[cfg(test)]` modules; the runtime code
// reaches them through the `ui_tars` path.
#[allow(unused_imports)]
pub use crate::pc_automation::ui_tars::{
    build_prompt, parse_ui_tars_response, LlmCompleteFn, LlmMessage, VlmAction, VlmTarget,
};
