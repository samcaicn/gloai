// Copyright (c) 2026 tupAI
//
// tupAI v5 §6.1 — UI-TARS 协议共享层。
//
// 父模块: `pc_automation::ui_tars`
// 子模块:
//   * `message`  — `UiTarsMessage`(UI-TARS 训练数据格式的"一行")
//   * `protocol` — `COMPUTER_USE_TEMPLATE` / `build_prompt` /
//                  `parse_ui_tars_response` 等"协议层"工具
//   * `llm`      — `LlmCompleteFn` / `LlmCompleteFut` / `LlmMessage`
//                  + `try_call_llm` 统一 fallback 助手
//
// 抽出本模块的目的(uirap v2 合并精简):
//   1. `UiTarsMessage` 原本在 `trajectory::message`,被
//      `trajectory::export` + 未来 SFT pipeline 共用;
//   2. `COMPUTER_USE_TEMPLATE` / `build_prompt` /
//      `parse_ui_tars_response` 原本在 `vlm_rescue::analyzer`,
//      是"协议层"而非"VLM 救援层",应独立;
//   3. `LlmCompleteFn` / `try_call_llm` 原本只在 vlm_rescue
//      实现,在 reflection::suggest / principles::distill
//      各复制一份"if llm { try {...} else fallback }"模板,
//      抽出后三个调用方都用同一个 helper。
//
// 向后兼容: `pc_automation::vlm_rescue::analyzer::*` 仍 re-export
//          上述符号,所以 `use ... vlm_rescue::analyzer::build_prompt`
//          这类老路径继续可用;新代码请用 `use ... ui_tars::...`。
//
// 命名约定: 与 `pc_automation::executor` 的 camelCase 一致。

pub mod llm;
pub mod message;
pub mod protocol;

#[allow(unused_imports)]  // pre-existing; consumed by #[cfg(test)] modules in callers
pub use llm::{try_call_llm, LlmCompleteFn, LlmCompleteFut, LlmMessage};
pub use message::UiTarsMessage;
#[allow(unused_imports)]  // pre-existing; consumed by #[cfg(test)] modules in callers
pub use protocol::{
    build_prompt, parse_ui_tars_response, VlmAction, VlmTarget, COMPUTER_USE_TEMPLATE,
    PARSER_DEFAULT_CONFIDENCE,
};
