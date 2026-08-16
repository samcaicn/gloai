// Copyright (c) 2026 tupAI
//
// tupAI v5 §6.2 — 原则库(PrincipleStore)的数据类型。
//
// 设计决策(doc comment):
//   * `Principle` 是一条"经过反思提炼、可供后续 executor 在路由前
//     检索复用"的工作流经验。语义粒度介于 `Skill`("一份完整 SOP")
//     和 `EpRecord`("某次执行的某一步实际发生了什么")之间。
//   * 字段命名:`supporting_records` 只存 `EpRecord.id`(轻量),
//     真正的 record 通过 `EpisodicStore::snapshot()` 反查。
//     这样 `PrincipleStore` 不需要反过来引用 episodic 的写入路径,
//     模块依赖单向(`principles -> episodic`)。
//   * `confidence` 是 `[0, 1]` 浮点,语义:
//
//       confidence = validation_count / (validation_count + invalidated_count)
//
//     全通过 = 1.0; 全失败 = 0.0; 还没验证 = `initial_confidence`
//     (由 `add` 写入时给定,默认 0.5)。
//   * `PrincipleCategory` 本期 4 个值,与任务 7 列出的语义对齐。
//     后续 v6 引入新分类时,追加 enum variant + serde 字段即可,
//     wire shape (camelCase) 不变。

use serde::{Deserialize, Serialize};

/// 原则的分类。本期 4 种,后续 v6 可扩展。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum PrincipleCategory {
    /// selector 选择经验("CSS > UIA > OCR 顺序")。
    Selector,
    /// 步骤顺序经验("先点 A 再点 B")。
    Sequencing,
    /// 错误恢复经验("OCR 失败回退到 VLM")。
    Recovery,
    /// 等待时机经验("页面加载后等 1s 再操作")。
    Timing,
}

impl PrincipleCategory {
    /// 分类的 wire-stable 字符串名(用于 search/key match 等场景,
    /// 避免调用方硬编码 serde 后的 camelCase 串)。
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Selector => "selector",
            Self::Sequencing => "sequencing",
            Self::Recovery => "recovery",
            Self::Timing => "timing",
        }
    }
}

/// 一条原则。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Principle {
    /// 全局唯一 uuid。`uuid::Uuid::new_v4().to_string()`。
    pub id: String,
    pub category: PrincipleCategory,
    /// 原则陈述,中文/英文皆可。例如:
    /// "Always click 确认 button before typing into form"。
    pub statement: String,
    /// 支撑本原则的 `EpRecord.id` 列表(轻量引用,具体内容靠
    /// `EpisodicStore::snapshot()` 反查)。
    pub supporting_records: Vec<String>,
    /// 0.0-1.0 置信度。
    pub confidence: f32,
    /// 创建时间(unix 毫秒)。
    pub created_at: i64,
    /// 最近一次校验时间(unix 毫秒);`0` 表示从未被校验。
    pub last_validated: i64,
    /// 通过校验的累计次数。
    pub validation_count: u32,
    /// 失败校验的累计次数。
    pub invalidated_count: u32,
}

impl Principle {
    /// 一个最小构造器,只填必填字段,其它字段给合理 default。
    ///
    /// 调用方(executor / distill)用这个填好 `statement` / `category`
    /// 后,再 `store.add(p)` 拿到最终 id。`id` 字段会在 `add` 阶段
    /// 被覆盖,所以这里允许传空串。
    pub fn new(
        category: PrincipleCategory,
        statement: impl Into<String>,
        created_at: i64,
    ) -> Self {
        Self {
            id: String::new(),
            category,
            statement: statement.into(),
            supporting_records: Vec::new(),
            confidence: 0.5,
            created_at,
            last_validated: 0,
            validation_count: 0,
            invalidated_count: 0,
        }
    }

    /// 重新计算 `confidence = val / (val + inv)`。若两者均为 0,
    /// 返回 0.5(中性值,表示"尚未校验")。
    pub fn recompute_confidence(&mut self) {
        let v = self.validation_count as f32;
        let i = self.invalidated_count as f32;
        let total = v + i;
        if total <= 0.0 {
            self.confidence = 0.5;
        } else {
            self.confidence = (v / total).clamp(0.0, 1.0);
        }
    }
}

// ============================================================================
// Unit tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    /// `PrincipleCategory::as_str` 必须稳定返回 camelCase 串,
    /// 且与 `serde(rename_all = "camelCase")` 一致。
    #[test]
    fn test_category_as_str_matches_serde() {
        // 1. as_str 的 wire 字符串
        assert_eq!(PrincipleCategory::Selector.as_str(), "selector");
        assert_eq!(PrincipleCategory::Sequencing.as_str(), "sequencing");
        assert_eq!(PrincipleCategory::Recovery.as_str(), "recovery");
        assert_eq!(PrincipleCategory::Timing.as_str(), "timing");

        // 2. serde 序列化的字符串与之完全一致
        for cat in [
            PrincipleCategory::Selector,
            PrincipleCategory::Sequencing,
            PrincipleCategory::Recovery,
            PrincipleCategory::Timing,
        ] {
            let s = serde_json::to_string(&cat).unwrap();
            // serde 会给 enum 加上引号
            let expected = format!("\"{}\"", cat.as_str());
            assert_eq!(s, expected, "serde 形态必须等于 as_str");
        }
    }

    /// `Principle` 必须以 camelCase wire shape 序列化(字段名检查)。
    #[test]
    fn test_principle_serde_camel_case() {
        let mut p = Principle::new(PrincipleCategory::Selector, "css 优先于 uia", 1_700_000_000_000);
        p.id = "p-1".to_string();
        p.supporting_records = vec!["r1".to_string(), "r2".to_string()];
        p.confidence = 0.8;
        p.last_validated = 1_700_000_001_000;
        p.validation_count = 4;
        p.invalidated_count = 1;

        let v: serde_json::Value = serde_json::to_value(&p).unwrap();
        assert!(v.get("supportingRecords").is_some());
        assert!(v.get("createdAt").is_some());
        assert!(v.get("lastValidated").is_some());
        assert!(v.get("validationCount").is_some());
        assert!(v.get("invalidatedCount").is_some());
        // 不应出现 snake_case
        let raw = serde_json::to_string(&p).unwrap();
        assert!(!raw.contains("supporting_records"));
        assert!(!raw.contains("created_at"));
        assert!(!raw.contains("last_validated"));
    }

    /// `recompute_confidence` 行为:
    ///   * 全通过 → 1.0
    ///   * 全失败 → 0.0
    ///   * 4 通过 1 失败 → 0.8
    ///   * 都没 → 0.5(中性)
    #[test]
    fn test_recompute_confidence() {
        let mut p = Principle::new(PrincipleCategory::Recovery, "x", 0);
        // 初始 0.5
        assert!((p.confidence - 0.5).abs() < 1e-6);

        p.validation_count = 5;
        p.invalidated_count = 0;
        p.recompute_confidence();
        assert!((p.confidence - 1.0).abs() < 1e-6, "全通过应为 1.0,实际 {}", p.confidence);

        p.validation_count = 0;
        p.invalidated_count = 3;
        p.recompute_confidence();
        assert!((p.confidence - 0.0).abs() < 1e-6, "全失败应为 0.0,实际 {}", p.confidence);

        p.validation_count = 4;
        p.invalidated_count = 1;
        p.recompute_confidence();
        assert!((p.confidence - 0.8).abs() < 1e-6, "4/5 应为 0.8,实际 {}", p.confidence);

        p.validation_count = 0;
        p.invalidated_count = 0;
        p.recompute_confidence();
        assert!((p.confidence - 0.5).abs() < 1e-6, "无样本应回 0.5,实际 {}", p.confidence);
    }
}
