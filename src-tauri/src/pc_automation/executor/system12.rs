// Copyright (c) 2026 AIMarketing
//
// ============================================================================
// System 1 / System 2 显式化(双过程路由) — AIMarketing v5 中期 9
// ============================================================================
//
// 受 Kahneman 双过程理论启发,把"找一个 selector → 选 strategy"
// 这个动作拆成两个层:
//
//   * **System 1**:快、便宜、不经过 router。命中条件:同一个
//     (intent, step_id, selector) 三元组在历史上成功过 ≥
//     `min_hit_count` 次,且平均延迟 / 信心分数都健康。命中后
//     主循环可以"信任"缓存的 strategy,跳过 router 域感知
//     cascade(本期留 TODO,需要改 MultiPrioritySelector 接口)。
//
//   * **System 2**:慢、贵、走完整 router 域感知 cascade。所有
//     "第一次见到" / "缓存不健康"的 step 都走 System 2。
//
// 设计要点:
//   * **零侵入集成**:AdaptiveExecutor 在每个 step 开始时调
//     `classify`,在 success / failure 之后调
//     `record_outcome`。本期不真正"跳过 router",只打 log +
//     维护缓存,后续 PR 可加短路逻辑。
//   * **缓存 key 三元组**:`{intent}::{step_id}::{selector_value}`。
//     不同 app profile 在 selector 字符串里就分得开,不必额外
//     加字段(避免 cache key 维度爆炸)。
//   * **confidence 公式**:`min(1.0, hit_count as f32 / 10.0)` —
//     命中越多越信;失败时 confidence *= 0.5,跌破 0.05 踢出。
//   * **TTL**:`max_age_days` 控制缓存有效期,过期 entry 会在
//     `lookup` 时按需清理。
//   * **不引入新 crate**;只依赖 std + serde。
//
// 后续(本期不实现):
//   * 把 `MultiPrioritySelector::try_locate` 改成"先查 cache 再
//     走 router"的双层接口;
//   * 把缓存跨进程持久化(rocksdb / sled),本期内存在重启时丢;
//   * 跨 step 的"intent → step 集合"提前预热。
// ============================================================================

use std::collections::HashMap;

use serde::{Deserialize, Serialize};

use crate::pc_automation::skill::types::SkillStep;
use crate::pc_automation::step::StepStrategy;

// ============================================================================
// 配置
// ============================================================================

/// System 1/2 路由配置。`Default` 给一组保守的初始值,后续可
/// 通过 `UirapState` 注入到 executor。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct System12Config {
    /// 是否启用 System 1 缓存(默认 true)。`false` 时 `classify`
    /// 永远返回 `System2`。
    pub enabled: bool,
    /// 缓存最大条目数(满了 `record` 时替换 confidence 最低
    /// 的条目)。默认 256。
    pub capacity: usize,
    /// 一个 entry 必须累计多少次成功命中才"信任"为 System 1。
    /// 默认 3 次(对应"已经被验证 3 次的稳定 selector")。
    pub min_hit_count: u32,
    /// 缓存有效期(天)。超过这个时间没被访问的 entry 视为
    /// 过期,`lookup` 时直接清掉。默认 7 天。
    pub max_age_days: i64,
}

impl Default for System12Config {
    fn default() -> Self {
        Self {
            enabled: true,
            capacity: 256,
            min_hit_count: 3,
            max_age_days: 7,
        }
    }
}

// ============================================================================
// 缓存条目
// ============================================================================

/// 一条缓存记录:某个 (intent, step, selector) 三元组的历史表现。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CachedSelector {
    pub selector_value: String,
    pub strategy_used: StepStrategy,
    pub app_profile: Option<String>,
    /// 累计成功命中次数(成功一次 +1,失败一次不影响 hit_count
    /// 但 confidence 会下调)。`hit_count >= min_hit_count` 是
    /// System 1 命中的必要条件之一。
    pub hit_count: u32,
    /// 平均延迟(毫秒),EMA 平滑(α=0.3)。
    pub avg_latency_ms: u64,
    pub last_used: i64,
    /// 0.0 - 1.0 之间的"信任分数",综合 hit_count 与近期成功率。
    /// 低于 0.3 的 entry 会被 System12Router 视为不健康、强制
    /// 走 System 2。
    pub confidence: f32,
}

impl CachedSelector {
    /// 按 hit_count 重算 confidence。规则:`min(1.0,
    /// hit_count / 10.0)`。10 次成功 ≈ 满信心,1 次成功 ≈ 0.1。
    pub fn recompute_confidence(&mut self) {
        let base = (self.hit_count as f32 / 10.0).min(1.0);
        self.confidence = base.clamp(0.0, 1.0);
    }
}

// ============================================================================
// 缓存
// ============================================================================

/// System 1 缓存(单纯一个 HashMap + 配置项)。不依赖外部 crate。
///
/// **公开方法约定**:
///   * `lookup` —— 只读访问(顺手刷 last_used + 清理过期)。
///   * `record` —— 写入 / 更新某条 entry,根据 `outcome` 里的
///                 hit_count 决定是"成功 +1"还是"失败不变"。
///   * `invalidate` —— 主动失效。
///   * `stats` / `len` / `is_empty` —— 健康度 / 计数。
#[derive(Debug)]
pub struct System1Cache {
    entries: HashMap<String, CachedSelector>,
    capacity: usize,
    min_hit_count: u32,
    max_age_days: i64,
}

impl System1Cache {
    pub fn new(cfg: &System12Config) -> Self {
        Self {
            entries: HashMap::new(),
            capacity: cfg.capacity.max(1),
            min_hit_count: cfg.min_hit_count,
            max_age_days: cfg.max_age_days,
        }
    }

    /// 拼接缓存 key。`intent` 是 skill 级别的意图;`step_id`
    /// 和 `selector` 一并拼接以避免不同 step 撞 key。
    pub fn make_key(intent: &str, step_id: &str, selector: &str) -> String {
        format!("{intent}::{step_id}::{selector}")
    }

    /// 查一次缓存。命中条件:
    ///   1. entry 存在
    ///   2. `hit_count >= min_hit_count`
    ///   3. `confidence >= 0.3`(健康)
    ///   4. 未过期(`now - last_used <= max_age_days`)
    ///
    /// 返回 `Some(&CachedSelector)` 即"信任",主循环可以
    /// 走 System 1 快速路径。返回 `None` 走 System 2。
    ///
    /// **关键副作用**:每次 lookup 不管命中与否都会把
    /// `last_used` 刷成 `now_ms`(LRU 风格),并按需清理过期
    /// entry。
    pub fn lookup(
        &mut self,
        intent: &str,
        step_id: &str,
        selector: &str,
        now_ms: i64,
    ) -> Option<&CachedSelector> {
        let key = Self::make_key(intent, step_id, selector);
        // 借用后立即释放,避免后面 self 入借用冲突
        let exists = self.entries.contains_key(&key);
        if !exists {
            return None;
        }
        // 拿到 entry 后做几件事:检查过期 / 刷 last_used
        let entry = self.entries.get_mut(&key).expect("just checked");
        // 过期检查(以天为单位,1 天 = 86_400_000 ms)
        let age_ms = now_ms.saturating_sub(entry.last_used);
        let max_age_ms = self.max_age_days.saturating_mul(86_400_000);
        if age_ms > max_age_ms {
            // 过期:清掉,这次不算命中
            self.entries.remove(&key);
            return None;
        }
        // 刷时间
        entry.last_used = now_ms;
        // 健康度检查
        if entry.hit_count < self.min_hit_count {
            return None;
        }
        if entry.confidence < 0.3 {
            return None;
        }
        // 不可变借用放出去(调用方需要 &CachedSelector)。
        // 这里先把 self 的独占借用到这,直接返回 & 借用。
        // 注意:self 后面没有再被借用,rust 不会冲突。
        Some(self.entries.get(&key).expect("entry still exists"))
    }

    /// 写入 / 更新一条缓存。`outcome` 是一次访问的"模板":
    ///   * 若 `outcome.hit_count > 0` 视为"成功" → 内部 entry
    ///     的 `hit_count += 1`、confidence 按 `min(1.0, hit/10)`
    ///     重算、avg_latency_ms 用 EMA(α=0.3)更新。
    ///   * 若 `outcome.hit_count == 0` 视为"失败" → hit_count
    ///     不变;confidence *= 0.5(下限 0.0);跌破 0.05 直接
    ///     踢出缓存。
    ///
    /// 容量满时,按 confidence 升序找一条最差的踢掉。
    pub fn record(
        &mut self,
        intent: &str,
        step_id: &str,
        selector: &str,
        outcome: &CachedSelector,
        now_ms: i64,
    ) {
        let key = Self::make_key(intent, step_id, selector);
        // Save a clone of the key before it is moved into the
        // entry below — we may need it in the eviction branch at
        // the end of this function.
        let key_for_remove = key.clone();
        // 容量检查:满则按 confidence 升序找一个最差的踢掉
        if !self.entries.contains_key(&key) && self.entries.len() >= self.capacity {
            if let Some(worst_key) = self
                .entries
                .iter()
                .min_by(|a, b| {
                    a.1.confidence
                        .partial_cmp(&b.1.confidence)
                        .unwrap_or(std::cmp::Ordering::Equal)
                })
                .map(|(k, _)| k.clone())
            {
                self.entries.remove(&worst_key);
            }
        }
        let is_new = !self.entries.contains_key(&key);
        let entry = self
            .entries
            .entry(key)
            .or_insert_with(|| CachedSelector {
                selector_value: outcome.selector_value.clone(),
                strategy_used: outcome.strategy_used,
                app_profile: outcome.app_profile.clone(),
                hit_count: 0,
                avg_latency_ms: outcome.avg_latency_ms,
                last_used: now_ms,
                confidence: 0.0,
            });
        entry.last_used = now_ms;
        // EMA 更新 avg_latency_ms(α=0.3)
        let alpha = 0.3_f64;
        let new_latency = outcome.avg_latency_ms as f64;
        let old_latency = entry.avg_latency_ms as f64;
        if !is_new {
            entry.avg_latency_ms =
                (alpha * new_latency + (1.0 - alpha) * old_latency) as u64;
        }
        // 成功 / 失败分支
        let is_success = outcome.hit_count > 0;
        let should_remove = if is_success {
            entry.hit_count = entry.hit_count.saturating_add(1);
            entry.recompute_confidence();
            false
        } else {
            entry.confidence = (entry.confidence * 0.5).max(0.0);
            entry.confidence < 0.05
        };
        // Drop the entry borrow before mutating `self.entries`
        // again — both the `entry()` call above and the
        // `remove()` below borrow `self.entries` mutably, so
        // the live `entry` ref would otherwise block us.
        // `drop(&mut _)` is a no-op so use `let _ = …` to make
        // the intent explicit (see project_rules §4).
        let _ = entry;
        if should_remove {
            // 太低:踢出
            self.entries.remove(&key_for_remove);
        }
    }

    /// 主动让某条缓存失效(例如应用检测到 UI 改版、selector
    /// 失效)。本期 main loop 不调用,留作外部 API。
    pub fn invalidate(&mut self, intent: &str, step_id: &str, selector: &str) {
        let key = Self::make_key(intent, step_id, selector);
        self.entries.remove(&key);
    }

    /// 缓存健康度统计:返回 `(entries, average_confidence)`。
    /// `average_confidence` 为 0.0 表示缓存为空。
    pub fn stats(&self) -> (usize, f32) {
        if self.entries.is_empty() {
            return (0, 0.0);
        }
        let sum: f32 = self.entries.values().map(|e| e.confidence).sum();
        let avg = sum / self.entries.len() as f32;
        (self.entries.len(), avg)
    }

    /// 当前缓存的条目数(测试 / 诊断用)。
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    /// 是否为空(测试 / 诊断用)。
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }
}

// ============================================================================
// 分类
// ============================================================================

/// System 1 / System 2 分类结果。`System1` = 命中缓存,主循环
/// 可以(本期留 TODO)跳过 router 域感知 cascade;`System2` =
/// 走完整 router。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StepTier {
    System1,
    System2,
}

// ============================================================================
// Router(整合 System1Cache + 配置开关)
// ============================================================================

/// System 1/2 路由器。本期对外只暴露 `classify` 和
/// `record_outcome` 两个方法,主循环每 step 前后各调一次。
#[derive(Debug)]
pub struct System12Router {
    pub cache: System1Cache,
    pub enabled: bool,
}

impl System12Router {
    pub fn new(cfg: System12Config) -> Self {
        Self {
            cache: System1Cache::new(&cfg),
            enabled: cfg.enabled,
        }
    }

    /// 给一个 (intent, step) 二元组决定走 System 1 还是 System 2。
    /// 关键路径:
    ///   * `enabled = false` → 一律 System2
    ///   * step 没有 element_selector / primary → System2
    ///   * lookup 命中 → System1
    ///   * lookup miss → System2
    pub fn classify(
        &mut self,
        intent: &str,
        step: &SkillStep,
        now_ms: i64,
    ) -> StepTier {
        if !self.enabled {
            return StepTier::System2;
        }
        let selector_value = &step.element_selector.primary.value;
        if selector_value.is_empty() {
            return StepTier::System2;
        }
        match self.cache.lookup(intent, &step.id, selector_value, now_ms) {
            Some(_) => StepTier::System1,
            None => StepTier::System2,
        }
    }

    /// 记录一次执行结果(成功或失败都调)。这是给 executor 主
    /// 循环调的便捷方法 —— 内部把 success / failure 转成
    /// `CachedSelector` 模板再交给 `System1Cache::record`。
    pub fn record_outcome(
        &mut self,
        intent: &str,
        step: &SkillStep,
        selector: &str,
        strategy_used: StepStrategy,
        latency_ms: u64,
        success: bool,
        now_ms: i64,
    ) {
        // 构造 outcome 镜像:hit_count 用 1/0 标记成功/失败
        let outcome = CachedSelector {
            selector_value: selector.to_string(),
            strategy_used,
            app_profile: None,
            hit_count: if success { 1 } else { 0 },
            avg_latency_ms: latency_ms,
            last_used: now_ms,
            confidence: if success { 1.0 } else { 0.0 },
        };
        self.cache
            .record(intent, &step.id, selector, &outcome, now_ms);
    }
}

// ============================================================================
// 单元测试
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pc_automation::skill::types::{
        ElementSelector, Selector, SelectorKind, SkillAction,
    };

    fn make_step(id: &str, primary: &str) -> SkillStep {
        SkillStep {
            id: id.into(),
            description: format!("step {}", id),
            intent: String::new(),
            element_selector: ElementSelector {
                version: "1.0".into(),
                primary: Selector {
                    kind: SelectorKind::Uia,
                    value: primary.into(),
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

    fn cfg_with(min_hits: u32) -> System12Config {
        System12Config {
            enabled: true,
            capacity: 16,
            min_hit_count: min_hits,
            max_age_days: 7,
        }
    }

    // -------------------------------------------------------------
    // 8. cache lookup miss 返 None
    // -------------------------------------------------------------
    #[test]
    fn test_system1_cache_lookup_miss_returns_none() {
        let mut cache = System1Cache::new(&cfg_with(3));
        let now = 1_000_000_i64;
        assert!(cache.lookup("buy_stock", "s1", "uia:Buy", now).is_none());
        // 空的 cache:stats 应为 (0, 0.0)
        assert_eq!(cache.stats(), (0, 0.0));
    }

    // -------------------------------------------------------------
    // 9. record 多次后 lookup 命中
    // -------------------------------------------------------------
    #[test]
    fn test_system1_cache_records_and_lookups_after_min_hits() {
        let mut cache = System1Cache::new(&cfg_with(2));
        let now = 2_000_000_i64;
        let outcome = CachedSelector {
            selector_value: "uia:controlType=Button".into(),
            strategy_used: StepStrategy::Uia,
            app_profile: None,
            hit_count: 1,
            avg_latency_ms: 50,
            last_used: now,
            confidence: 1.0,
        };
        // 一次还不够
        cache.record("buy", "step1", "uia:controlType=Button", &outcome, now);
        assert_eq!(cache.stats().0, 1, "record 一次后 cache 有一条");
        assert!(
            cache.lookup("buy", "step1", "uia:controlType=Button", now).is_none(),
            "hit_count=1 < min_hit=2 必须 miss"
        );
        // 第二次
        cache.record("buy", "step1", "uia:controlType=Button", &outcome, now);
        // 第二次后 hit_count=2 == min_hit=2,confidence=0.2 < 0.3 健康度门槛
        // —— 这里 min_hit=2 命中但 confidence 阈值 0.3 还不够
        assert!(
            cache.lookup("buy", "step1", "uia:controlType=Button", now).is_none(),
            "hit_count=2 但 confidence 0.2 < 0.3 仍 miss"
        );
        // 第三次
        cache.record("buy", "step1", "uia:controlType=Button", &outcome, now);
        // hit_count=3,confidence=min(1, 0.3) = 0.3,刚好达健康线
        assert!(
            cache.lookup("buy", "step1", "uia:controlType=Button", now).is_some(),
            "hit_count=3 + confidence=0.3 应当命中"
        );
    }

    // -------------------------------------------------------------
    // 10. System12Router 第一次访问 → System2
    // -------------------------------------------------------------
    #[test]
    fn test_system12_classify_system2_on_first_visit() {
        let mut router = System12Router::new(cfg_with(2));
        let step = make_step("s1", "uia:Submit");
        let now = 3_000_000_i64;
        assert_eq!(
            router.classify("intent_buy", &step, now),
            StepTier::System2,
            "首次访问应走 System 2"
        );
        // 立即 classify,确认缓存仍未"信任"
        assert_eq!(router.classify("intent_buy", &step, now), StepTier::System2);
    }

    // -------------------------------------------------------------
    // 11. 多次 record_outcome 成功 → System1;多次失败 → System2
    // -------------------------------------------------------------
    #[test]
    fn test_system12_classify_system1_after_enough_hits() {
        let mut router = System12Router::new(cfg_with(2));
        let step = make_step("s2", "uia:Confirm");
        let now = 4_000_000_i64;
        // 三次成功(超过 min_hit=2),confidence = 0.3
        for _ in 0..3 {
            router.record_outcome(
                "intent_sell",
                &step,
                "uia:Confirm",
                StepStrategy::Uia,
                30,
                true,
                now,
            );
        }
        // 现在 classify 应当返回 System1
        assert_eq!(
            router.classify("intent_sell", &step, now),
            StepTier::System1,
            "三次成功 → System 1"
        );
        // 失败一次 → confidence *= 0.5 → 0.15 < 0.3,踢出
        router.record_outcome(
            "intent_sell",
            &step,
            "uia:Confirm",
            StepStrategy::Uia,
            30,
            false,
            now,
        );
        // 因为 confidence < 0.05 已被踢出(0.15 * 0.5 = 0.075 还
        // 没到踢出线;但 lookup 时 0.075 < 0.3 健康线已经 miss)
        // 再次 classify → System2
        assert_eq!(
            router.classify("intent_sell", &step, now),
            StepTier::System2,
            "失败后 confidence < 0.3 → 回到 System 2"
        );
    }

    // -------------------------------------------------------------
    // 12. record_outcome 更新 stats
    // -------------------------------------------------------------
    #[test]
    fn test_system12_record_outcome_updates_cache_stats() {
        let mut router = System12Router::new(cfg_with(2));
        let step_a = make_step("a", "uia:A");
        let step_b = make_step("b", "uia:B");
        let now = 5_000_000_i64;

        let (n0, _) = router.cache.stats();
        assert_eq!(n0, 0, "初始 entries=0");

        router.record_outcome("intent", &step_a, "uia:A", StepStrategy::Uia, 10, true, now);
        let (n1, _) = router.cache.stats();
        assert_eq!(n1, 1);

        router.record_outcome("intent", &step_b, "uia:B", StepStrategy::Cdp, 20, true, now);
        let (n2, avg) = router.cache.stats();
        assert_eq!(n2, 2);
        // avg confidence > 0
        assert!(avg > 0.0, "avg_confidence 应 > 0, got {}", avg);
        // enabled=false → 一律 System2
        router.enabled = false;
        assert_eq!(router.classify("intent", &step_a, now), StepTier::System2);
    }
}
