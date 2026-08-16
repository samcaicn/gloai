// Copyright (c) 2026 tupAI
//
// tupAI P0 §1 — Skill metadata (skill.md ↔ MCP)
//
// `SkillManifest` mirrors the YAML front-matter / body shape that
// tupAI / Hermes use to describe a skill. The original
// `hermes::skill_manifest` covers the marketplace-facing fields
// (name, version, tags, entrypoints, IO, dependencies). The
// automation-driven `SkillManifest` here adds the *executable* layer
// — preferred execution type, software/browser routing, and the
// ordered list of `Step`s that the engine should run.
//
// Two intentionally different `SkillManifest` types now exist:
//
// - `crate::hermes::skill_manifest::SkillManifest` — marketplace metadata
//   (already used by `commands::agent::get_skills` / `install_skill`).
// - `crate::skill::manifest::SkillManifest` — execution-time representation
//   (this file), parsed from the `skill.md` body and consumed by
//   `AutomationEngine`.
//
// The marketplace variant can be losslessly mapped onto the
// execution variant when an automation flow needs to load a skill
// from the marketplace registry. We do that mapping in
// `McpRuntime::load_from_marketplace` (TODO: will provide the
// concrete record once the marketplace schema is final).

use borsh::{BorshDeserialize, BorshSerialize};
use serde::{Deserialize, Serialize};
use zeroize::Zeroize;

/// How the automation engine should drive a step.
#[derive(
    Debug,
    Clone,
    Copy,
    PartialEq,
    Eq,
    Serialize,
    Deserialize,
    BorshSerialize,
    BorshDeserialize,
    Default,
    Zeroize,
)]
#[serde(rename_all = "snake_case")]
pub enum ExecutionType {
    /// Open / control a native application (e.g. Notepad, Photoshop).
    #[default]
    SystemSoftware,
    /// Drive a browser via CDP (provided by A3).
    Browser,
}

/// A single automation step the engine will attempt. A step is
/// `serde::Deserialize` from YAML, and the same struct round-trips
/// into the MCP binary via `BorshSerialize` / `BorshDeserialize`.
///
/// v5 PCUI 路线（DEV.md §1.4）— 在 `dom_selector` / `visual_target`
/// 之上追加 3 个 v5 专属 selector 字段：
///   * `uia_selector` — 形如 `uia:controlType=Button;name=提交;...`,
///     由 `pc_automation::uia::parse_uia_selector` 解析.
///   * `cdp_selector` — 形如 `cdp:url=*xueqiu.com;css=.buy`,由
///     `pc_automation::cdp::parse_cdp_selector` 解析.
///   * `ocr_anchor` — 形如 `ocr:engine=paddleVl16;region=...;match=...`,
///     由 `pc_automation::ocr::parse_ocr_anchor` 解析.
/// 4 个 selector 字段全部 `Option<String>` + `skip_serializing_if =
/// "Option::is_none"`，旧 skill.md（只填 `dom_selector` 或
/// `visual_target`）反序列化不受影响。
#[derive(
    Debug,
    Clone,
    Default,
    Serialize,
    Deserialize,
    BorshSerialize,
    BorshDeserialize,
    Zeroize,
)]
pub struct Step {
    /// Stable identifier for the step (used in retry history / logs).
    pub id: String,
    /// Human-readable description (used by the floating panel).
    pub description: String,
    /// DOM selector hint (used by Browser / DOM-based fallback).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub dom_selector: Option<String>,
    /// Visual target hint (used by Vision / OCR fallback).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub visual_target: Option<String>,
    /// v5 PCUI — UIA selector (优先, Windows native UIA + macOS
    /// NSAccessibility + Linux AT-SPI2). 由 `pc_automation::uia::*
    /// ` 路径消费。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub uia_selector: Option<String>,
    /// v5 PCUI — CDP selector (浏览器 DOM,通过 chromiumoxide)。
    /// 由 `pc_automation::cdp::*` 路径消费。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cdp_selector: Option<String>,
    /// v5 PCUI — OCR anchor (L1 PP-OCRv5 / L2 PaddleOCR-VL-1.6)。
    /// 由 `pc_automation::ocr::*` 路径消费。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ocr_anchor: Option<String>,
    /// Optional input action (click, type, hotkey, wait).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub input: Option<InputAction>,
    /// v5 PCUI — 步骤执行前的等待延时（毫秒），从录制 flowchart 的
    /// meta.delayMs 透传。回放引擎在执行 input 前等待此时间，模拟人类操作节奏。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub delay_ms: Option<u64>,
    /// v5 PCUI — 鼠标移动轨迹点 [[x,y], ...]，从录制 flowchart 的
    /// meta.mouseTrajectory 透传。回放引擎在 Click 前沿此轨迹移动鼠标
    /// （加随机扰动），模拟人类操作。仅 Click 步骤使用；
    /// 点击本身按元素/坐标精确执行，不加随机。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mouse_trajectory: Option<Vec<Vec<i32>>>,
    /// v5 PCUI — LLM 提示词，从 flowchart 节点 meta 透传。
    /// 当 Type 步骤有此字段时，引擎先调用 MCP LLM 获取文本，
    /// 然后将返回的文本输入到目标输入框中（取代录制的静态文本）。
    /// 场景：表单自动填写、智能回复等。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub llm_prompt: Option<String>,
}

#[derive(
    Debug,
    Clone,
    Serialize,
    Deserialize,
    BorshSerialize,
    BorshDeserialize,
    Zeroize,
)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum InputAction {
    Click { x: i32, y: i32 },
    Type { text: String },
    Hotkey { keys: String },
    Wait { ms: u64 },
}

impl Default for InputAction {
    fn default() -> Self {
        // `#[default]` requires a unit variant; pick Wait as the
        // no-op default so `SkillManifest` / `Step` can still derive
        // `Default` without forcing callers to construct a click.
        InputAction::Wait { ms: 0 }
    }
}

/// Supported platform identifiers, matching Hermes desktop convention:
/// `macos` (Darwin), `linux`, `windows`.
///
/// A skill with an empty `platforms` list runs on all platforms (default).
/// When non-empty, the skill is only loaded / executed on platforms whose
/// `std::env::consts::OS` matches one of the entries.
#[derive(
    Debug,
    Clone,
    Copy,
    PartialEq,
    Eq,
    Hash,
    Serialize,
    Deserialize,
    BorshSerialize,
    BorshDeserialize,
    Zeroize,
)]
#[serde(rename_all = "lowercase")]
pub enum SkillPlatform {
    Macos,
    Linux,
    Windows,
}

impl SkillPlatform {
    /// Current compile-time platform.
    pub fn current() -> Self {
        match std::env::consts::OS {
            "macos" => SkillPlatform::Macos,
            "linux" => SkillPlatform::Linux,
            "windows" => SkillPlatform::Windows,
            _ => {
                log::warn!(
                    "[skill/manifest] unknown platform '{}', treating as linux",
                    std::env::consts::OS
                );
                SkillPlatform::Linux
            }
        }
    }

    pub fn as_str(&self) -> &'static str {
        match self {
            SkillPlatform::Macos => "macos",
            SkillPlatform::Linux => "linux",
            SkillPlatform::Windows => "windows",
        }
    }
}

/// The full skill manifest. YAML form looks like:
///
/// ```yaml
/// name: open-notepad
/// description: Launch notepad and type a greeting
/// platforms: [windows]
/// preferred_execution_type: system_software
/// software_name: notepad.exe
/// steps:
///   - id: launch
///     description: Launch notepad
///   - id: type
///     description: Type greeting
///     dom_selector: null
///     visual_target: "Editor"
///     input: { type: type, text: "Hello from tupAI" }
/// ```
#[derive(
    Debug,
    Clone,
    Default,
    Serialize,
    Deserialize,
    BorshSerialize,
    BorshDeserialize,
    Zeroize,
)]
pub struct SkillManifest {
    pub name: String,
    #[serde(default)]
    pub description: Option<String>,
    /// Platforms this skill is compatible with. Empty (default) means all
    /// platforms. Matches Hermes desktop `platforms` field semantics.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub platforms: Vec<SkillPlatform>,
    pub preferred_execution_type: ExecutionType,
    /// Required when `preferred_execution_type == SystemSoftware`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub software_name: Option<String>,
    /// Required when `preferred_execution_type == Browser`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub browser_url: Option<String>,
    pub steps: Vec<Step>,
}

impl SkillManifest {
    /// Returns `true` if this skill is compatible with the current
    /// platform. An empty `platforms` list means "all platforms".
    ///
    /// Mirrors Hermes desktop's `platforms` field behavior — skills
    /// that declare `platforms: [windows]` are automatically hidden
    /// on macOS / Linux and vice versa.
    pub fn is_compatible_with_current_platform(&self) -> bool {
        if self.platforms.is_empty() {
            return true;
        }
        let current = SkillPlatform::current();
        self.platforms.contains(&current)
    }

    /// Validate cross-field invariants and return a human-readable
    /// error if the manifest is malformed. This is the gate the
    /// compiler uses before serialization; the engine also calls
    /// it again at execution time to defend against tampered MCPs.
    pub fn validate(&self) -> Result<(), String> {
        if self.name.trim().is_empty() {
            return Err("skill name is required".to_string());
        }
        if self.steps.is_empty() {
            return Err(format!("skill '{}' has no steps", self.name));
        }
        match self.preferred_execution_type {
            ExecutionType::SystemSoftware => {
                if self.software_name.as_deref().unwrap_or("").is_empty() {
                    return Err(format!(
                        "skill '{}' is SystemSoftware but software_name is missing",
                        self.name
                    ));
                }
            }
            ExecutionType::Browser => {
                if self.browser_url.as_deref().unwrap_or("").is_empty() {
                    return Err(format!(
                        "skill '{}' is Browser but browser_url is missing",
                        self.name
                    ));
                }
            }
        }
        Ok(())
    }

    /// Parse from a `skill.md` (YAML) string. We intentionally use
    /// `serde_yaml` (already a project dependency) so the schema
    /// matches the Hermes / aicoop conventions.
    pub fn from_skill_md(source: &str) -> Result<Self, String> {
        serde_yaml::from_str::<SkillManifest>(source)
            .map_err(|e| format!("invalid skill.md: {}", e))
    }

    /// Re-serialize to canonical YAML. The compiler uses this when
    /// `decompile_skill` is called (e.g. for debugging the MCP).
    pub fn to_skill_md(&self) -> Result<String, String> {
        serde_yaml::to_string(self).map_err(|e| format!("yaml serialize failed: {}", e))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_manifest() -> SkillManifest {
        SkillManifest {
            name: "open-notepad".into(),
            description: Some("Launch notepad and type a greeting".into()),
            platforms: vec![],
            preferred_execution_type: ExecutionType::SystemSoftware,
            software_name: Some("notepad.exe".into()),
            browser_url: None,
            steps: vec![
                Step {
                    id: "launch".into(),
                    description: "Launch notepad".into(),
                    dom_selector: None,
                    visual_target: None,
                    uia_selector: None,
                    cdp_selector: None,
                    ocr_anchor: None,
                    input: None,
                    delay_ms: None,
                    mouse_trajectory: None,
                    llm_prompt: None,
                },
                Step {
                    id: "type".into(),
                    description: "Type greeting".into(),
                    dom_selector: None,
                    visual_target: Some("Editor".into()),
                    uia_selector: None,
                    cdp_selector: None,
                    ocr_anchor: None,
                    input: Some(InputAction::Type {
                        text: "Hello".into(),
                    }),
                    delay_ms: None,
                    mouse_trajectory: None,
                    llm_prompt: None,
                },
            ],
        }
    }

    #[test]
    fn validate_accepts_well_formed_manifest() {
        assert!(sample_manifest().validate().is_ok());
    }

    #[test]
    fn validate_rejects_browser_without_url() {
        let mut m = sample_manifest();
        m.preferred_execution_type = ExecutionType::Browser;
        m.software_name = None;
        m.browser_url = None;
        assert!(m.validate().is_err());
    }

    #[test]
    fn validate_rejects_empty_steps() {
        let mut m = sample_manifest();
        m.steps.clear();
        assert!(m.validate().is_err());
    }

    #[test]
    fn round_trip_yaml_preserves_execution_type_and_steps() {
        let m = sample_manifest();
        let yaml = m.to_skill_md().unwrap();
        let m2 = SkillManifest::from_skill_md(&yaml).unwrap();
        assert_eq!(m2.name, m.name);
        assert_eq!(m2.preferred_execution_type, m.preferred_execution_type);
        assert_eq!(m2.steps.len(), m.steps.len());
        assert_eq!(m2.steps[1].id, "type");
    }

    #[test]
    fn empty_platforms_means_compatible_everywhere() {
        let m = sample_manifest();
        assert!(m.platforms.is_empty());
        assert!(m.is_compatible_with_current_platform());
    }

    #[test]
    fn platforms_field_round_trips_yaml() {
        let mut m = sample_manifest();
        m.platforms = vec![SkillPlatform::Windows, SkillPlatform::Macos];
        let yaml = m.to_skill_md().unwrap();
        let m2 = SkillManifest::from_skill_md(&yaml).unwrap();
        assert_eq!(m2.platforms.len(), 2);
        assert!(m2.platforms.contains(&SkillPlatform::Windows));
        assert!(m2.platforms.contains(&SkillPlatform::Macos));
    }
}
