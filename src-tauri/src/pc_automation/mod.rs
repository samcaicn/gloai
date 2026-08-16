// Copyright (c) 2026 tupAI
//
// tupAI v5 — PCUI 路线（UIA + CDP + OCR 三层路由器 + 券商 API）
//
// Three-strategy router for desktop / browser automation:
//   1. CDP   — Chrome DevTools Protocol (browser / Electron) — primary for Web domain
//   2. UIA   — Windows UI Automation  (fastest, structured) — primary for Desktop domain
//   3. OCR   — PaddleOCR-VL-1.6       (self-drawn Chinese UIs)
//
// The router takes a `PcStep` and picks the primary tier by
// domain (CDP for Web, UIA for Desktop), cascading to OCR on
// miss. VLM is the post-error escalation (executor only), NOT
// a tier in the cascade. Each backend trait returns
// `Result<_, String>` so the public IPC surface can ferry
// errors to the front-end verbatim without re-encoding them.
//
// See `tupAI 完整开发文档.md` §1.4 for the tier ordering and the
// rationale for dropping the v3 YOLO / TinyClick / ScreenParser
// stack.

pub mod apps;
pub mod broker;
pub mod cdp;
// Cua Driver 集成层 — Sidecar MCP over stdio。
// 通过子进程方式启动 cua-driver 二进制，使用 JSON-RPC 2.0
// （MCP 协议）通信。替代 enigo 作为主要输入路径，enigo 降级
// 为 fallback。详见 `cua_driver/mod.rs` 架构文档。
pub mod cua_driver;
// Adaptive executor + multi-priority selector.
// See `pc_automation/executor/mod.rs` for the public API and
// `uirap改造技术方案.md` §5 for the main-loop algorithm. The
// module is self-contained: it only depends on the existing
// `router` / `step` / backends and on
// `pc_automation::skill::types`.
pub mod executor;
// tupAI v5 — 三级记忆架构(episodic 层)。
// 详见 `pc_automation/episodic/mod.rs`。
// 当前: in-memory `InMemoryEpisodicStore` + SQLite stub。
pub mod episodic;
pub mod logger;
pub mod ocr;
pub mod parse_error;
// tupAI v5 — terminator_bridge: cross-platform automation adapter.
// Bridges terminator's `Desktop` / `UIElement` to tupai's existing
// `UiaBackend` / `OcrBackend` traits. Replaces the Windows-only
// `WindowsUiaBackend` with a cross-platform implementation that
// works on Windows (UIAutomation), macOS (AXUIElement), and Linux
// (AT-SPI). See `terminator_bridge/mod.rs` for architecture details
// and `docs/集成规范.md` for the integration specification.
pub mod terminator_bridge;
// tupAI v5 §6.2 — 「技能 + 反思」双层自进化(中期 6 + 7)。
//   * `principles` — 原则库(PrincipleStore / search_relevant /
//                    distill_from_records),自进化的"经验沉淀"层。
//   * `reflection` — 失败聚类 + 修复建议(FailureCluster /
//                    suggest_selector_for_cluster),自进化的"反思"层。
// 两者均消费 `pc_automation::episodic` 的 `EpRecord`,输出物会被
// executor 在路由前通过 `search_relevant` 命中复用。
pub mod principles;
pub mod reflection;
pub mod router;
// tupAI v5 — flat screen-content parser. Composes UIA + OCR
// (Windows; macOS / Linux ship a stub) into a single
// `Vec<ScreenElement>` for the front-end / VLM consumer.
// See `pc_automation/screen_parser/mod.rs` for the public
// API and the v3-drop rationale.
pub mod screen_parser;
// Skill data layer.
// See `pc_automation/skill/mod.rs` for the public API. Owns
// the `Skill` / `SkillStep` / `ElementSelector` data model,
// AES-256-GCM `SkillDecryptor`, `LocalSkillStorage` (writes
// encrypted `.enc` files under `<app_data>/skills/`),
// `SkillRegistry` (in-memory index + storage glue), the
// `{{name}}` template renderer, and the `SkillStep ↔ PcStep`
// adapter that lets the executor feed skills into the v5
// three-strategy router.
pub mod skill;
pub mod step;
// tupAI v5 — UI-TARS 训练数据格式的 trajectory.jsonl 导出。
// 详见 `pc_automation/trajectory/mod.rs`。
// 当前: `build_trajectory` / `export_jsonl` / `from_episodic` / `from_receipt`。
pub mod trajectory;
// tupAI v5 — UI-TARS 协议共享层(`UiTarsMessage` / 模板 / 解析器 /
// LLM fallback 助手)。3 个原本各复制一份 LLM 调用模板的模块
// (vlm_rescue / reflection / principles)现在都通过本模块的
// `try_call_llm` 调用,uirap v2 合并精简产物。
// 详见 `pc_automation/ui_tars/mod.rs`。
pub mod ui_tars;
pub mod uia;

// VLM rescue + Hermes messenger.
// See `pc_automation/vlm_rescue/mod.rs` for the VLM tier
// (cross-platform screenshot stub, prompt assembly, JSON
// `VlmAction` parsing, `max_attempts=3` 限频, `confidence
// >= 0.6` 阈值) and `pc_automation/hermes_messenger/mod.rs`
// for the in-process `tokio::mpsc` bus that keeps the Doc1
// `ClientRequest` / `ServerResponse` wire shape stable for
// a future real-server migration. Both modules are owned
// — see the UIRPA design doc.
//
// uirap v2: vlm_rescue::analyzer 里的 UI-TARS 协议层已下沉到
// `pc_automation::ui_tars`,此处只保留"救援主流程"相关的类型
// (VlmAction / VlmTarget / RescueContext / DynamicPromptConfig
// / build_dynamic_prompt / is_action_acceptable /
// DEFAULT_CONFIDENCE_THRESHOLD)。已下沉的符号(COMPUTER_USE_TEMPLATE
// / build_prompt / parse_ui_tars_response / LlmCompleteFn /
// LlmCompleteFut / LlmMessage)仍由 analyzer re-export,老调用路径
// 继续可用。
pub mod vlm_rescue;
pub mod hermes_messenger;

// tupAI v5 §5.4 — unit tests. Sibling-file pattern so the
// main barrel stays free of `#[cfg(test)]` noise.
#[cfg(test)]
#[path = "pc_automation_tests.rs"]
mod pc_automation_tests;

// UIRPA cross-module integration tests.
// The test file lives in a `tests/` subdirectory so
// it doesn't clutter the existing sibling-file pattern above.
#[cfg(test)]
#[path = "tests/integration_uirpa.rs"]
mod integration_uirpa;
