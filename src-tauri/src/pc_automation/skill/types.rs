// Copyright (c) 2026 tupAI
//
// UIRPA skill data model.
//
// Defines the wire-level data structures that describe a skill
// (`Skill`), its parameters, the multi-priority selector tree
// (`ElementSelector` / `Selector`), the wait + post-validation
// enums, the error-handler chain, and a `Branch` stub. Every
// type derives `Serialize`/`Deserialize` with
// `#[serde(rename_all = "camelCase")]` so the front-end can
// consume them without writing a custom mapper.
//
// The shape is intentionally close to the v5 `PcStep` data model
// but lifted to a tree: one step → many `Selector`s → many
// `ElementSelector`s. The convert layer (`convert.rs`) bridges
// between this richer model and the flat `PcStep` consumed by the
// existing three-strategy router.
//
// File layout:
//   * `types.rs`      — structs + enums
//   * `decryptor.rs`  — AES-256-GCM wrapper
//   * `storage.rs`    — local on-disk encrypted store
//   * `registry.rs`   — in-memory index + storage glue
//   * `template.rs`   — `{{name}}` parameter rendering
//   * `convert.rs`    — `SkillStep ↔ PcStep` adapter
//   * `tests.rs`      — unit tests
//   * `mod.rs`        — barrel / public re-exports

use serde::{Deserialize, Serialize};

/// The full skill. Mirrors Doc1 §2.3 + Doc2 v5 `skill.md` fields.
///
/// `parameters`, `steps`, `error_handlers`, `branches` are the
/// executable layer; everything else is metadata. `branches` is
/// reserved for a future "conditional / parallel" extension and is
/// a no-op in v1 (callers should leave it empty).
///
/// The three optional SKILL.md front-matter fields (`name`,
/// `description`, `license`) mirror the Anthropic Agent Skills
/// open standard (2025-12) so the same on-disk file is portable
/// across agent runtimes. They are field-defaulted (`String::default`
/// = empty, `Option::default` = `None`) so older JSON that does
/// not carry them still round-trips — see the migration note in
/// `export.rs::from_skill_md` for the validation policy.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Skill {
    pub skill_id: String,
    pub version: String,
    pub intent: String,
    pub scene_fingerprint: Option<String>,
    pub created_at: chrono::DateTime<chrono::Utc>,
    pub updated_at: chrono::DateTime<chrono::Utc>,
    pub success_rate: f32,
    pub avg_execution_time_ms: u64,
    pub parameters: Vec<Parameter>,
    pub steps: Vec<SkillStep>,
    pub error_handlers: Vec<ErrorHandler>,
    pub branches: Vec<Branch>,

    // --- SKILL.md frontmatter (Anthropic Agent Skills 2025-12) ---
    /// Skill 名称 (1-64 chars, kebab-case, 与目录名一致).
    #[serde(default)]
    pub name: String,
    /// 何时调用 + 能力描述 (1-1024 chars).
    #[serde(default)]
    pub description: String,
    /// 可选 SPDX 许可证标识 (例如 "Apache-2.0").
    #[serde(default)]
    pub license: Option<String>,
}

/// One skill parameter. `param_type` is renamed to `type` on the
/// wire (`#[serde(rename = "type")]`) so the front-end / JSON
/// consumer sees a familiar TypeScript-discriminant shape.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Parameter {
    pub name: String,
    #[serde(rename = "type")]
    pub param_type: ParamType,
    pub required: bool,
    pub default: Option<serde_json::Value>,
}

/// Three-way parameter type. Serialised in lower case so the
/// front-end can match it as a plain string.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ParamType {
    String,
    Number,
    Boolean,
}

/// A single executable step. Carries its own multi-priority
/// `ElementSelector` (primary + fallbacks), optional `parameter`
/// template (resolved against the skill's parameter map at run
/// time), and optional wait / post-validation hooks.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SkillStep {
    pub id: String,
    pub description: String,
    pub intent: String,
    pub element_selector: ElementSelector,
    pub action: SkillAction,
    pub parameter: Option<TemplateString>,
    pub wait_condition: Option<WaitCondition>,
    pub post_action_validation: Option<Validation>,
    /// Optional runtime prompt to the user (Track F "互动输入").
    /// When present, the executor pauses before the step's action,
    /// emits `automation:ask_user`, and waits for
    /// `automation_answer_prompt` before continuing. The answer is
    /// bound to `interaction.bind_to_var` so later steps can
    /// reference it via `TemplateString`. `None` for old skills —
    /// `#[serde(default)]` keeps deserialisation backward-compatible.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub interaction: Option<InteractionPrompt>,
}

/// A wrapper newtype around `String` so we can hang a custom
/// `Deserialize` / `Serialize` for templates later (e.g. handle
/// `{{a.b}}` dotted access). For v1 the inner string is stored
/// verbatim; rendering lives in `template::render_template`.
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(transparent)]
pub struct TemplateString(pub String);

impl TemplateString {
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl From<&str> for TemplateString {
    fn from(value: &str) -> Self {
        TemplateString(value.to_string())
    }
}

impl From<String> for TemplateString {
    fn from(value: String) -> Self {
        TemplateString(value)
    }
}

/// Multi-priority selector tree. The `MultiPrioritySelector` in
/// the executor walks `primary` first, then `fallbacks` in the
/// order they appear in this struct (callers should pre-sort by
/// `Selector::stability_score` descending).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ElementSelector {
    pub version: String,
    pub primary: Selector,
    pub fallbacks: Vec<Selector>,
    pub iframe_context: Option<String>,
    pub shadow_root_context: Option<String>,
}

/// One concrete selector. `kind` discriminates how the executor
/// should interpret `value` (UIA / CDP / OCR / Visual /
/// Coordinate). `stability_score` is the `0.0..=1.0` confidence
/// the recorder placed in this selector — the executor sorts
/// the whole `fallbacks` list by this number before walking.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Selector {
    #[serde(rename = "type")]
    pub kind: SelectorKind,
    pub value: String,
    pub stability_score: f32,
    pub context: Option<String>,
    pub match_threshold: Option<f32>,
    pub resolution: Option<String>,
}

/// Which automation strategy the selector should be dispatched
/// to. `Uia` and `Cdp` and `Ocr` map onto the existing
/// `pc_automation::*::parse_*` parsers; `Visual` and `Coordinate`
/// are reserved for future VLM / pixel-fallback work and are
/// treated as no-op selectors.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum SelectorKind {
    Uia,
    Cdp,
    Ocr,
    Visual,
    Coordinate,
}

/// The atomic action a step performs once an element has been
/// located. `Click` / `Input` / `Hotkey` / `Wait` mirror the
/// v5 input action vocabulary. `Value` is the literal-or-template
/// to apply (text for `Input`, key combo for `Hotkey`).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum SkillAction {
    Click,
    Input { value: String },
    Wait { ms: u64 },
    Hotkey { keys: String },
}

/// Pre-step wait. The executor holds here until the predicate is
/// satisfied or the per-variant timeout elapses.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", tag = "type")]
pub enum WaitCondition {
    ElementVisible { selector: ElementSelector, timeout_ms: u64 },
    ElementAttributeEquals {
        selector: ElementSelector,
        attribute: String,
        value: String,
        timeout_ms: u64,
    },
    OcrTextPresent { text: String, region: Option<OcrRegion>, timeout_ms: u64 },
    Delay { ms: u64 },
}

/// Post-step assertion. The execution fails if the predicate is
/// not satisfied right after the action returns.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", tag = "type")]
pub enum Validation {
    ElementValueEquals { selector: ElementSelector, value: String },
    OcrTextPresent { text: String, region: Option<OcrRegion> },
    PageUrlContains { substring: String },
    Delay { ms: u64 },
}

/// Pixel rectangle carried by OCR wait / validation conditions.
/// Same shape as `pc_automation::ocr::OcrRegion` but we keep a
/// local copy so the skill data model has no inbound edge to
/// `pc_automation::ocr` (we are the *bottom* of that stack).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
#[derive(Default)]
pub struct OcrRegion {
    pub x: i32,
    pub y: i32,
    pub w: i32,
    pub h: i32,
}


/// One error-recovery arm. Triggered when `condition` matches
/// (e.g. an OCR text appears, a selector misses N times in a
/// row, or a post-validation fails). The handler executes
/// `action` against `element_selector` up to `retry_count` times.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ErrorHandler {
    pub condition: ErrorCondition,
    pub action: SkillAction,
    pub element_selector: ElementSelector,
    pub retry_count: u32,
}

/// The set of failure shapes a handler can react to.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", tag = "type")]
pub enum ErrorCondition {
    OcrTextPresent { text: String },
    SelectorMiss { after_attempts: u32 },
    ValidationFail { validation: Box<Validation> },
}

/// Reserved for a future "conditional / parallel" extension.
/// The schema is forward-compatible, but no
/// executor path reads it yet. The `condition` is a free-form
/// string for now (e.g. `"success_rate < 0.5"`); it will be
/// replaced by a typed predicate enum in a follow-up PR.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Branch {
    pub condition: String,
    pub steps: Vec<SkillStep>,
}

// =============================================================
// Track F — interactive input during automation execution.
// A `SkillStep` carrying an `InteractionPrompt` pauses the
// executor, emits `automation:ask_user`, and waits for the
// front-end to call `automation_answer_prompt` before continuing.
// =============================================================

/// A runtime prompt to the user. When a `SkillStep` carries one,
/// the executor pauses, emits `automation:ask_user`, and waits for
/// `automation_answer_prompt` before continuing.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InteractionPrompt {
    /// Stable id for correlating the answer back. If empty, the
    /// executor generates one (`pmt_<uuid>`).
    #[serde(default)]
    pub prompt_id: String,
    pub question: String,
    pub input_type: PromptInputType,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub choices: Vec<PromptChoice>,
    /// Template variable name to bind the answer to (referenced by
    /// later steps' `TemplateString`).
    pub bind_to_var: String,
    #[serde(default)]
    pub default_value: Option<serde_json::Value>,
    #[serde(default = "default_prompt_timeout_ms")]
    pub timeout_ms: u64,
}

fn default_prompt_timeout_ms() -> u64 {
    60_000
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PromptChoice {
    pub id: String,
    pub label: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum PromptInputType {
    Text,
    Choice,
    MultiChoice,
    Confirm,
}

/// Payload emitted via `app.emit("automation:ask_user", ...)`.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AskUserPayload {
    pub correlation_id: String,
    pub skill_id: String,
    pub step_id: String,
    pub prompt: InteractionPrompt,
}

/// Answer received from the front-end via `automation_answer_prompt`.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PromptAnswer {
    pub correlation_id: String,
    /// For Text: the typed string. For Choice: the choice.id. For
    /// MultiChoice: array of selected choice ids. For Confirm: `"true"` / `"false"`.
    pub value: serde_json::Value,
    /// True if the user dismissed/cancelled — executor then uses
    /// `default_value` or fails.
    #[serde(default)]
    pub cancelled: bool,
}

/// Lightweight metadata for the `list` call. Only the fields the
/// front-end shows in the skill list — body is NOT decrypted
/// here, so this is cheap.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SkillMeta {
    pub skill_id: String,
    pub version: String,
    pub intent: String,
    pub updated_at: chrono::DateTime<chrono::Utc>,
    pub success_rate: f32,
}
