// Copyright (c) 2026 AIMarketing
//
// AIMarketing v5 §6.2 — 「技能 + 反思」双层自进化(中期 6)。
//
// 父模块: `pc_automation::reflection`
// 子模块:
//   * `cluster` — `FailureCluster` / `ReflectionConfig` / `cluster_failures`
//   * `suggest` — `SuggestConfig` / `suggest_selector_for_cluster`
//
// 这一层是"自进化(中期)"的"反思"环节:消费 `episodic` 层的失败
// `EpRecord`,做失败聚类,然后为每个聚类生成修复建议 selector。
// 输出物(`FailureCluster.suggested_selector`)会被原则提炼器
// (`pc_automation::principles::distill`)进一步消费,合并为
// "Selector 选择经验 / 错误恢复经验"等 Principle,挂到
// `PrincipleStore` 供后续 executor 在路由前快速检索。
//
// 公开 API(`pub use`):
//   * `FailureCluster`
//   * `ReflectionConfig`
//   * `cluster_failures` — 主聚类函数
//   * `SuggestConfig`
//   * `suggest_selector_for_cluster` — 主建议函数

pub mod cluster;
pub mod suggest;
