// Copyright (c) 2026 AIMarketing
//
// UIRPA `SkillStep ↔ PcStep` adapter.
//
// The whole point of UIRPA is to layer the rich skill data
// model (multi-priority selectors, error handlers, validation
// hooks) on top of the existing v5 three-strategy router without
// rewriting any backend. This file is the bridge:
//
//   * `to_pc_step`  — flatten a `SkillStep` into a v5 `PcStep`
//                     that the existing `PcRouter::execute_step`
//                     can dispatch.
//   * `from_pc_step`— reverse: take a legacy `PcStep` and
//                     produce a `SkillStep` so the recorder
//                     can import v5 skills and "upgrade" them
//                     to the UIRPA data model in place.
//   * `to_pc_steps` — split an entire `Skill` into a `Vec<PcStep>`
//                     ready for batch execution.
//
// The mapping is deliberately lossy on the way in: the
// `ElementSelector` may carry UIA / CDP / OCR / Visual /
// Coordinate selectors, but the v5 router only understands the
// first three. We map `Visual` and `Coordinate` to the `Uia`
// strategy so the executor at least has a "primary" hint to
// pass through; the executor's VLM-rescue / pixel-fallback
// paths read the richer metadata
// directly off the original `SkillStep`.

use crate::pc_automation::step::{PcStep, StepStrategy};
use crate::pc_automation::skill::types::{
    ElementSelector, Selector, SelectorKind, Skill, SkillAction, SkillStep,
};
use crate::recording::action::{self, ActionType, RecordedAction};

/// 把录制期的字符串 selector_type 映射为执行期的 SelectorKind 枚举。
/// 这是两套 selector 类型系统之间的桥梁。
/// - "css"/"xpath"/"text" → Cdp (CDP 可解析)
/// - "uia_id"/"uia_name"/"uia_class"/"uia_combined"/"uia_help"/"uia_access"/"uia_accel"/"uia_process" → Uia
/// - "ocr_text" → Ocr
/// - "coordinate"/"bounds" → Coordinate (value 格式 "x,y")
pub fn map_selector_type_to_kind(selector_type: &str) -> SelectorKind {
    match selector_type {
        "css" | "xpath" | "text" => SelectorKind::Cdp,
        "uia_id" | "uia_name" | "uia_class" | "uia_combined"
        | "uia_help" | "uia_access" | "uia_accel" | "uia_process" => SelectorKind::Uia,
        "ocr_text" => SelectorKind::Ocr,
        "coordinate" | "bounds" => SelectorKind::Coordinate,
        _ => SelectorKind::Uia, // 未知类型兜底为 UIA
    }
}

/// 把录制期的 `recording::action::ElementSelector`(字符串 selector_type)
/// 映射为执行期的 `skill::types::ElementSelector`(枚举 SelectorKind)。
/// 同时把 bounds 转为 Coordinate fallback,确保坐标信息不丢失。
pub fn recorded_selector_to_skill(
    recorded: &action::ElementSelector,
    context: Option<String>,
) -> ElementSelector {
    let primary = Selector {
        kind: map_selector_type_to_kind(&recorded.selector_type),
        value: recorded.value.clone(),
        stability_score: stability_for_type(&recorded.selector_type),
        context: context.clone(),
        match_threshold: None,
        resolution: None,
    };

    let mut fallbacks: Vec<Selector> = recorded
        .fallback_selectors
        .iter()
        .map(|f| Selector {
            kind: map_selector_type_to_kind(&f.selector_type),
            value: f.value.clone(),
            stability_score: stability_for_type(&f.selector_type),
            context: context.clone(),
            match_threshold: None,
            resolution: None,
        })
        .collect();

    // 如果有 bounds,追加一个 Coordinate fallback(坐标兜底)
    if let Some(bounds) = &recorded.bounds {
        fallbacks.push(Selector {
            kind: SelectorKind::Coordinate,
            value: format!("{},{}", bounds.x, bounds.y),
            stability_score: 0.1, // 坐标是 last-resort
            context,
            match_threshold: None,
            resolution: None,
        });
    }

    ElementSelector {
        version: "1.0".to_string(),
        primary,
        fallbacks,
        iframe_context: None,
        shadow_root_context: None,
    }
}

/// 按 selector_type 给经验权重(stability_score)。
/// 元素选择器优先(高分),坐标兜底(低分)。
fn stability_for_type(selector_type: &str) -> f32 {
    match selector_type {
        "uia_id" | "css" => 0.95,
        "uia_combined" | "xpath" => 0.85,
        "uia_name" | "uia_class" | "uia_help" => 0.7,
        "text" | "uia_access" | "uia_accel" => 0.5,
        "uia_process" => 0.3,
        "bounds" | "coordinate" => 0.1,
        _ => 0.5,
    }
}

/// 把录制期的 `RecordedAction` 转换为执行期的 `SkillStep`。
/// 这是录制→回放的关键转换路径,让录制数据能被 AdaptiveExecutor 直接消费。
pub fn recorded_action_to_skill_step(action: &RecordedAction) -> Option<SkillStep> {
    let target = action.target.as_ref()?;
    let element_selector = recorded_selector_to_skill(target, Some(action.app_name.clone()));

    let skill_action = match action.action_type {
        ActionType::Click | ActionType::DoubleClick | ActionType::RightClick => SkillAction::Click,
        ActionType::Type => {
            SkillAction::Input { value: action.action_data.clone().unwrap_or_default() }
        }
        ActionType::KeyDown => {
            SkillAction::Hotkey { keys: action.action_data.clone().unwrap_or_default() }
        }
        // Scroll/MouseMove/Focus/Select 无直接对应的 SkillAction,暂用 Wait
        ActionType::Scroll | ActionType::MouseMove | ActionType::Focus | ActionType::Select => {
            SkillAction::Wait { ms: 0 }
        }
    };

    Some(SkillStep {
        id: action.id.clone(),
        description: format!("{:?} on {}", action.action_type, action.app_name),
        intent: String::new(),
        element_selector,
        action: skill_action,
        parameter: None,
        wait_condition: None,
        post_action_validation: None,
        interaction: None,
    })
}

/// Convert a single `SkillStep` into the flat `PcStep` shape the
/// v5 router consumes. The primary `Selector` becomes
/// `primary_selector`; every fallback becomes a plain
/// `fallback_selectors` entry (in the order they appear).
pub fn to_pc_step(skill_step: &SkillStep) -> PcStep {
    let strategy = selector_kind_to_strategy(skill_step.element_selector.primary.kind);
    let primary_selector = skill_step.element_selector.primary.value.clone();
    let fallback_selectors = skill_step
        .element_selector
        .fallbacks
        .iter()
        .map(|s| s.value.clone())
        .collect();

    // 从 selector 中提取录制坐标（Coordinate 类型的 selector value 格式为 "x,y"）。
    // 优先从 primary 提取，其次从 fallbacks 中找第一个 Coordinate 类型。
    let recorded_coords = extract_coords(&skill_step.element_selector.primary)
        .or_else(|| {
            skill_step
                .element_selector
                .fallbacks
                .iter()
                .find_map(extract_coords)
        });

    PcStep {
        id: skill_step.id.clone(),
        description: skill_step.description.clone(),
        app_profile: skill_step.element_selector.primary.context.clone(),
        strategy,
        primary_selector,
        fallback_selectors,
        recorded_coords,
    }
}

/// 从 Selector 中提取坐标。仅当 kind == Coordinate 且 value 可解析为 "x,y" 时返回。
fn extract_coords(sel: &Selector) -> Option<(i32, i32)> {
    if sel.kind != SelectorKind::Coordinate {
        return None;
    }
    let (x_str, y_str) = sel.value.split_once(',')?;
    let x: i32 = x_str.trim().parse().ok()?;
    let y: i32 = y_str.trim().parse().ok()?;
    Some((x, y))
}

/// Convert an entire `Skill` into a `Vec<PcStep>`, ready to feed
/// into `PcRouter::execute_step` one at a time. `error_handlers`
/// and `branches` are intentionally dropped here — they live
/// above the router in `AdaptiveExecutor` and are not part of
/// the per-step flat contract.
pub fn to_pc_steps(skill: &Skill) -> Vec<PcStep> {
    skill.steps.iter().map(to_pc_step).collect()
}

/// Upgrade a legacy v5 `PcStep` into the UIRPA `SkillStep` shape.
/// The resulting step is intentionally minimal: it carries the
/// selector tree and the strategy hint, but no wait / validation
/// hooks (the legacy format has nowhere to read those from).
pub fn from_pc_step(pc: &PcStep) -> SkillStep {
    let kind = strategy_to_selector_kind(pc.strategy);
    let primary = Selector {
        kind,
        value: pc.primary_selector.clone(),
        stability_score: 1.0, // legacy selectors were the only choice
        context: pc.app_profile.clone(),
        match_threshold: None,
        resolution: None,
    };
    let mut fallbacks: Vec<Selector> = pc
        .fallback_selectors
        .iter()
        .map(|raw| Selector {
            kind,
            value: raw.clone(),
            // Legacy fallbacks were peer-ranked; the modern
            // recorder would have given them individual scores,
            // but the legacy schema doesn't carry that.
            stability_score: 0.5,
            context: None,
            match_threshold: None,
            resolution: None,
        })
        .collect();

    // 把 recorded_coords 塞回为 Coordinate fallback,避免 PcStep→SkillStep→PcStep 往返丢失坐标。
    if let Some((x, y)) = pc.recorded_coords {
        fallbacks.push(Selector {
            kind: SelectorKind::Coordinate,
            value: format!("{},{}", x, y),
            stability_score: 0.1, // 坐标是 last-resort fallback
            context: pc.app_profile.clone(),
            match_threshold: None,
            resolution: None,
        });
    }
    let element_selector = ElementSelector {
        version: "1.0".to_string(),
        primary,
        fallbacks,
        iframe_context: None,
        shadow_root_context: None,
    };

    SkillStep {
        id: pc.id.clone(),
        description: pc.description.clone(),
        intent: String::new(),
        element_selector,
        // Legacy `PcStep` carried no action vocabulary, so the
        // default is a no-op `Wait { ms: 0 }` — the executor
        // will treat it as "locate, do nothing".
        action: SkillAction::Wait { ms: 0 },
        parameter: None,
        wait_condition: None,
        post_action_validation: None,
        interaction: None,
    }
}

fn selector_kind_to_strategy(kind: SelectorKind) -> StepStrategy {
    match kind {
        SelectorKind::Uia => StepStrategy::Uia,
        SelectorKind::Cdp => StepStrategy::Cdp,
        SelectorKind::Ocr => StepStrategy::Ocr,
        // Visual / Coordinate are not yet plumbed through the
        // v5 router. We pick UIA as the "best-effort primary
        // hint" so the executor has *something* to pass; the
        // richer metadata is read directly from the original
        // `SkillStep`.
        SelectorKind::Visual | SelectorKind::Coordinate => StepStrategy::Uia,
    }
}

fn strategy_to_selector_kind(strategy: StepStrategy) -> SelectorKind {
    match strategy {
        StepStrategy::Uia => SelectorKind::Uia,
        StepStrategy::Cdp => SelectorKind::Cdp,
        StepStrategy::Ocr => SelectorKind::Ocr,
        // VLM is a pre-error escalation tier; the executor
        // never puts it in a `SkillStep` (the rescue path
        // builds a transient `LocatedElement` directly).
        // Map it to `Visual` so any leftover conversion site
        // produces a usable `SelectorKind`.
        StepStrategy::Vlm => SelectorKind::Visual,
    }
}

// -----------------------------------------------------------------
// Convenience constructors used by the unit tests + the executor
// (selector.rs consumes these).
// -----------------------------------------------------------------

impl SkillStep {
    /// Quick builder for tests and for code that needs a
    /// single-primary, no-fallback step. Mirrors the v5 `PcStep`
    /// shape closely.
    pub fn single(id: impl Into<String>, description: impl Into<String>, selector_value: impl Into<String>) -> Self {
        Self {
            id: id.into(),
            description: description.into(),
            intent: String::new(),
            element_selector: ElementSelector {
                version: "1.0".into(),
                primary: Selector {
                    kind: SelectorKind::Uia,
                    value: selector_value.into(),
                    stability_score: 1.0,
                    context: None,
                    match_threshold: None,
                    resolution: None,
                },
                fallbacks: Vec::new(),
                iframe_context: None,
                shadow_root_context: None,
            },
            action: SkillAction::Wait { ms: 0 },
            parameter: None,
            wait_condition: None,
            post_action_validation: None,
            interaction: None,
        }
    }
}

impl Skill {
    /// Build a single-step skill for tests / demo data. Mirrors
    /// the v5 `PcStep` shape: one parameter-less step pointing
    /// at `selector_value`.
    pub fn single_step(
        skill_id: impl Into<String>,
        intent: impl Into<String>,
        selector_value: impl Into<String>,
    ) -> Self {
        let step = SkillStep::single("step_1", "default", selector_value);
        let skill_id = skill_id.into();
        Self {
            skill_id: skill_id.clone(),
            version: "1.0.0".into(),
            intent: intent.into(),
            scene_fingerprint: None,
            created_at: chrono::Utc::now(),
            updated_at: chrono::Utc::now(),
            success_rate: 1.0,
            avg_execution_time_ms: 0,
            parameters: Vec::new(),
            steps: vec![step],
            error_handlers: Vec::new(),
            branches: Vec::new(),
            // SKILL.md frontmatter 字段:测试/demo 不强制要求填写,
            // 默认留空即可,序列化时由调用方补全。
            name: skill_id,
            description: String::new(),
            license: None,
        }
    }
}
