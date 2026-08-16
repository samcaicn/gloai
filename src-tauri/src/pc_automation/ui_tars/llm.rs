// Copyright (c) 2026 tupAI
//
// tupAI v5 §6.1 — 云端 LLM 共享层。
//
// 本文件统一了 3 个原本独立的"if llm
// { try { ... } else fallback }"模板到 `try_call_llm`:
//
//   1. vlm_rescue::analyzer::build_dynamic_prompt
//      → 失败 fallback 到固定模板 build_prompt
//   2. reflection::suggest::suggest_selector_for_cluster
//      → 失败 fallback 到本地启发式
//   3. principles::distill::distill_from_records
//      → 失败 fallback 到空 Vec
//
// 3 个调用方现在都用同一个 `try_call_llm(llm, prompt).await` 入口,
// 未来再加"云端 LLM 协助"的代码(比如 trajectory::from_receipt
// 真正落地时)直接复用,不会出现 4 份复制粘贴。
//
// 向后兼容: `LlmCompleteFn` / `LlmCompleteFut` / `LlmMessage` 三个
//          类型仍由 `pc_automation::vlm_rescue::analyzer` re-export,
//          老 import 路径继续可用。

use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;

/// Async future alias for the LLM call. `Pin<Box<dyn Future>>`
/// keeps the signature object-safe.
pub type LlmCompleteFut = Pin<Box<dyn Future<Output = Result<String, String>> + Send>>;

/// Trait alias for the text-only LLM completion function. We
/// take a trait object (not the concrete `LLMService`) so the
/// vlm_rescue module does not have to depend on
/// `hermes::llm_service` and so tests can inject a stub.
pub trait LlmCompleteFn: Fn(Vec<LlmMessage>) -> LlmCompleteFut + Send + Sync {}

impl<T> LlmCompleteFn for T where T: Fn(Vec<LlmMessage>) -> LlmCompleteFut + Send + Sync {}

/// One message in the conversation we hand to the cloud LLM.
/// We only ever send a single `user` message today; the type
/// is generic enough that follow-up PRs can add a system
/// prompt without changing the call site.
#[derive(Debug, Clone)]
pub struct LlmMessage {
    pub role: &'static str,
    pub content: String,
}

impl LlmMessage {
    pub fn user(content: impl Into<String>) -> Self {
        Self {
            role: "user",
            content: content.into(),
        }
    }
}

/// Call the cloud LLM with a single user message and return
/// the response text on success.
///
/// Returns `None` on **any** of the following:
///   * `llm` is `None`(本任务离线 / 测试场景)
///   * the call returned `Err(_)`(网络 / rate-limit / parse)
///   * the call returned `Ok("")` 或纯空白(空响应)
///   * `join` / `spawn_blocking` 等包装 future 自身出错
///
/// 调用方对 `None` 的处理完全由业务决定:
///   * `build_dynamic_prompt` 走固定模板
///   * `suggest_selector_for_cluster` 走本地启发式
///   * `distill_from_records` 返回空 Vec
///
/// 这种"三层 LLM 调用 + 三种 fallback"原本各有一段 8-15 行的
/// `match llm(messages).await { ... }` 重复代码,本文件
/// 统一到本函数,新增调用方只要写一行 `try_call_llm(...)?`
/// 即可。
pub async fn try_call_llm(
    llm: Option<&Arc<dyn LlmCompleteFn>>,
    prompt: impl Into<String>,
) -> Option<String> {
    let llm = llm?;
    let messages = vec![LlmMessage::user(prompt.into())];
    match llm(messages).await {
        Ok(s) if !s.trim().is_empty() => Some(s),
        _ => None,
    }
}

// ============================================================================
// Unit tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    /// 测试桩 LLM:返回 "stub-ok"
    fn stub_ok(_: Vec<LlmMessage>) -> LlmCompleteFut {
        Box::pin(async { Ok("stub-ok".to_string()) })
    }

    /// 测试桩 LLM:返回空字符串
    fn stub_empty(_: Vec<LlmMessage>) -> LlmCompleteFut {
        Box::pin(async { Ok("   ".to_string()) })
    }

    /// 测试桩 LLM:返回 Err
    fn stub_err(_: Vec<LlmMessage>) -> LlmCompleteFut {
        Box::pin(async { Err("rate-limit".to_string()) })
    }

    #[tokio::test]
    async fn try_call_llm_none_when_llm_is_none() {
        let r: Option<String> = try_call_llm(None, "anything").await;
        assert!(r.is_none());
    }

    #[tokio::test]
    async fn try_call_llm_some_on_ok() {
        let llm: Arc<dyn LlmCompleteFn> = Arc::new(stub_ok);
        let r = try_call_llm(Some(&llm), "hello").await;
        assert_eq!(r.as_deref(), Some("stub-ok"));
    }

    #[tokio::test]
    async fn try_call_llm_none_on_empty() {
        let llm: Arc<dyn LlmCompleteFn> = Arc::new(stub_empty);
        let r = try_call_llm(Some(&llm), "hello").await;
        assert!(r.is_none());
    }

    #[tokio::test]
    async fn try_call_llm_none_on_error() {
        let llm: Arc<dyn LlmCompleteFn> = Arc::new(stub_err);
        let r = try_call_llm(Some(&llm), "hello").await;
        assert!(r.is_none());
    }
}
