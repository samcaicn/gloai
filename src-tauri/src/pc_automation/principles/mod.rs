// Copyright (c) 2026 AIMarketing
//
// AIMarketing v5 §6.2 — 「技能 + 反思」双层自进化(中期 7):原则库。
//
// 父模块: `pc_automation::principles`
// 子模块:
//   * `types`   — `Principle` / `PrincipleCategory`
//   * `store`   — `PrincipleStore` trait + `InMemoryPrincipleStore`
//   * `search`  — `PrincipleContext` / `search_relevant`
//   * `distill` — `distill_from_records`(本期 stub)
//
// 原则库是"自进化(中期)"的"经验沉淀"环节:反思层(`reflection::*`)
// 把失败聚类成 `FailureCluster`,distill 层再把 cluster / 原始
// record 提炼为 `Principle`,挂到 `PrincipleStore` 供后续
// executor 在路由前通过 `search_relevant` 快速命中。
//
// 公开 API(`pub use`):
//   * `Principle` / `PrincipleCategory`
//   * `PrincipleStore` / `InMemoryPrincipleStore`
//   * `PrincipleContext` / `search_relevant`
//   * `distill_from_records` (本期 stub)

pub mod distill;
pub mod search;
pub mod store;
pub mod types;
