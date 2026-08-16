// Copyright (c) 2026 AIMarketing
//
// AIMarketing v5 §6.2 — 原则检索(在执行某步前快速命中相关经验)。
//
// 设计决策(doc comment):
//   * 简单实现:**category 一致 + statement 子串匹配**。
//     之所以不上 embedding:
//     (1) 原则库规模在 10² 量级,字面匹配召回足够;
//     (2) 离线 / 单元测试不依赖任何网络或额外 crate;
//     (3) 后续 v6 切到向量召回时,函数签名不变,只换内部实现。
//   * 匹配规则:
//     (a) `principle.category == ctx.strategy` 推断的 category → 命中;
//         例如 `strategy = "uia" | "cdp" | "ocr"` 视作 `Selector` 类别;
//         `strategy = "vlm"` 视作 `Recovery` 类别。这是粗粒度启发式,
//         真正的细粒度匹配靠 statement 关键词。
//     (b) `principle.statement` 与 `ctx.intent` 做 `contains` (子串);
//     (c) 可选 `ctx.app_profile` 出现在 `principle.statement` 中
//         (例如 "在 ths_hexin 中 ...") 也算命中。
//   * 排序:按 `confidence` 倒序、`validation_count` 倒序,稳定排序。
//   * 限制:返回前 `top_k` 条(默认不限,本模块不持有 top_k 字段,
//     由调用方 `.truncate()`)。

use super::types::{Principle, PrincipleCategory};

/// 检索上下文。借引用避免把数据复制进模块。
#[derive(Debug, Clone)]
pub struct PrincipleContext<'a> {
    pub intent: &'a str,
    pub app_profile: Option<&'a str>,
    /// `Uia | Cdp | Ocr | Vlm` 之一。沿用 `pc_automation::step::StepStrategy`
    /// 的小写串,以便与 `EpRecord::strategy_used` 直接对照。
    pub strategy: &'a str,
}

/// 把 `strategy` 字符串映射到 `PrincipleCategory`(粗启发式)。
///
/// 映射规则:
///   * `uia` / `cdp` / `ocr` → `Selector`(三 tier 都是"如何选元素"层)
///   * `vlm`               → `Recovery`(VLM 本身就是"前序 tier 都 miss 后的兜底")
///   * 其它                 → `None`(不按 category 过滤,只走 statement 匹配)
fn strategy_to_category(strategy: &str) -> Option<PrincipleCategory> {
    match strategy.to_ascii_lowercase().as_str() {
        "uia" | "cdp" | "ocr" => Some(PrincipleCategory::Selector),
        "vlm" => Some(PrincipleCategory::Recovery),
        _ => None,
    }
}

/// 单条原则与检索上下文的"相关度打分"(越高越相关)。
///
/// 评分规则(总分上限 1.0):
///   * category 命中目标:           +0.4
///   * statement 包含 intent 子串:   +0.4
///   * statement 包含 app_profile:    +0.2
///
/// 任意一项缺失(空 intent / 空 strategy / 空 app_profile)对应
/// 那 0.4 / 0.4 / 0.2 不会加分,而不是扣分。
fn relevance_score(p: &Principle, ctx: &PrincipleContext<'_>) -> f32 {
    let mut score = 0.0_f32;

    // 1. category
    if let Some(target) = strategy_to_category(ctx.strategy) {
        if p.category == target {
            score += 0.4;
        }
    }

    // 2. statement 包含 intent
    if !ctx.intent.is_empty() && p.statement.contains(ctx.intent) {
        score += 0.4;
    }

    // 3. statement 包含 app_profile
    if let Some(app) = ctx.app_profile {
        if !app.is_empty() && p.statement.contains(app) {
            score += 0.2;
        }
    }

    score.clamp(0.0, 1.0)
}

/// 主入口:在 `principles` 库里按 `ctx` 检索相关经验。
///
/// 返回所有"有任意相关度命中"的条目,按 `confidence` 倒序、
/// `validation_count` 倒序稳定排序。空 `principles` → 空 Vec。
pub fn search_relevant(
    principles: &[Principle],
    ctx: &PrincipleContext<'_>,
) -> Vec<Principle> {
    // 1. 打分
    let mut scored: Vec<(f32, &Principle)> = principles
        .iter()
        .map(|p| (relevance_score(p, ctx), p))
        .filter(|(s, _)| *s > 0.0)
        .collect();

    // 2. 排序:相关度 → confidence → validation_count
    scored.sort_by(|a, b| {
        b.0.partial_cmp(&a.0)
            .unwrap_or(std::cmp::Ordering::Equal)
            .then(b.1.confidence.partial_cmp(&a.1.confidence).unwrap_or(std::cmp::Ordering::Equal))
            .then(b.1.validation_count.cmp(&a.1.validation_count))
    });

    // 3. 投影
    scored.into_iter().map(|(_, p)| p.clone()).collect()
}

// ============================================================================
// Unit tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pc_automation::principles::types::PrincipleCategory;

    fn p_with_conf(stmt: &str, cat: PrincipleCategory, conf: f32) -> Principle {
        let mut p = Principle::new(cat, stmt, 1_700_000_000_000);
        p.confidence = conf;
        p
    }

    /// category 命中必须把对应原则推上第一(其它完全匹配的并列时,
    /// 按 confidence 倒序决胜)。
    #[test]
    fn test_search_relevant_matches_by_category() {
        let principles = vec![
            p_with_conf(
                "无关原则",
                PrincipleCategory::Timing,
                0.99,
            ),
            p_with_conf(
                "css 优先于 uia",
                PrincipleCategory::Selector,
                0.7,
            ),
        ];
        let ctx = PrincipleContext {
            intent: "",
            app_profile: None,
            strategy: "uia",
        };
        let out = search_relevant(&principles, &ctx);
        // 只有 Selector 类能命中(uia → Selector)
        assert_eq!(out.len(), 1);
        assert_eq!(out[0].category, PrincipleCategory::Selector);
    }

    /// statement 子串匹配 intent。
    #[test]
    fn test_search_relevant_matches_by_keyword_substring() {
        let principles = vec![
            p_with_conf(
                "Always click 确认 button before typing into form",
                PrincipleCategory::Sequencing,
                0.8,
            ),
            p_with_conf(
                "Use OCR anchor when UIA fails",
                PrincipleCategory::Recovery,
                0.6,
            ),
        ];
        let ctx = PrincipleContext {
            intent: "确认",
            app_profile: None,
            strategy: "uia",
        };
        let out = search_relevant(&principles, &ctx);
        // 第一条 statement 包含 "确认" → 命中
        assert_eq!(out.len(), 1, "只有 statement 包含 intent 才命中");
        assert!(out[0].statement.contains("确认"));
    }

    /// app_profile 命中:statement 中出现应用名 → 加分。
    #[test]
    fn test_search_relevant_app_profile_match_boosts_rank() {
        let principles = vec![
            // 没提应用,confidence 较低
            p_with_conf("css 优先于 uia", PrincipleCategory::Selector, 0.7),
            // 提了应用,confidence 稍高
            p_with_conf(
                "在 ths_hexin 中优先使用 css selector",
                PrincipleCategory::Selector,
                0.75,
            ),
        ];
        let ctx = PrincipleContext {
            intent: "",
            app_profile: Some("ths_hexin"),
            strategy: "uia",
        };
        let out = search_relevant(&principles, &ctx);
        // 两条都因 category=Selector 命中;提应用的应该排第一
        assert_eq!(out.len(), 2);
        assert!(out[0].statement.contains("ths_hexin"));
    }

    /// VLM strategy 视作 Recovery category。
    #[test]
    fn test_search_relevant_vlm_maps_to_recovery() {
        let principles = vec![p_with_conf(
            "OCR 失败时回退 VLM",
            PrincipleCategory::Recovery,
            0.9,
        )];
        let ctx = PrincipleContext {
            intent: "",
            app_profile: None,
            strategy: "vlm",
        };
        let out = search_relevant(&principles, &ctx);
        assert_eq!(out.len(), 1, "vlm strategy 应召回 Recovery 类原则");
    }

    /// 空 intent / 空 strategy → 不应误命中(每个分量缺失就跳过那一项)。
    #[test]
    fn test_search_relevant_empty_fields_no_false_positive() {
        let principles = vec![p_with_conf(
            "Always click 确认 button",
            PrincipleCategory::Sequencing,
            0.99,
        )];
        let ctx = PrincipleContext {
            intent: "",
            app_profile: None,
            strategy: "nonsense-strategy",
        };
        // strategy 不在已知 mapping,category 不命中;intent 空也不命中
        // → 应返回空
        let out = search_relevant(&principles, &ctx);
        assert!(out.is_empty(), "空 ctx 字段 + 未知 strategy 必须返回空");
    }

    /// 排序:`confidence` 高的优先;并列时 `validation_count` 高的优先。
    #[test]
    fn test_search_relevant_sorts_by_confidence_then_validation() {
        let mut a = p_with_conf("alpha", PrincipleCategory::Selector, 0.9);
        a.validation_count = 1;
        let mut b = p_with_conf("beta", PrincipleCategory::Selector, 0.9);
        b.validation_count = 5;
        let mut c = p_with_conf("gamma", PrincipleCategory::Selector, 0.5);
        c.validation_count = 100;

        let ctx = PrincipleContext {
            intent: "",
            app_profile: None,
            strategy: "uia",
        };
        let out = search_relevant(&[a, b, c], &ctx);
        assert_eq!(out.len(), 3);
        // confidence 0.9 的 a/b 排前;并列时 b 排第一
        assert!(out[0].statement == "beta" && out[1].statement == "alpha");
        assert_eq!(out[2].statement, "gamma");
    }
}
