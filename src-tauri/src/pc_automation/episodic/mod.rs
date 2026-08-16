// Copyright (c) 2026 AIMarketing
//
// AIMarketing v5 — 三级记忆架构中的「情景记忆(episodic memory)」层。
//
// 父模块: `pc_automation::episodic`
// 子模块:
//   * `record` — `EpRecord` 数据模型
//   * `store`  — `EpisodicStore` trait + `InMemoryEpisodicStore` /
//                `SqliteEpisodicStore`(stub)
//   * `query`  — 4 个查询 API 自由函数
//
// 三级记忆(AIMarketing v5 §1.4 路线图):
//   * episodic(本模块) — "某次执行某一步实际发生了什么"
//   * semantic          — "从 episodic 提炼出的 skill 模式" (后续 v6)
//   * procedural        — "用户的工作流偏好" (后续 v6)
//
// 公开 API(`pub use`):
//   * `EpRecord`            — record 层数据
//   * `EpisodicStore`       — 抽象
//   * `InMemoryEpisodicStore` — 默认实现
//   * `SqliteEpisodicStore` — 持久化 stub(本期不挂载)
//   * `query::*` 4 个 API   — 反思检索入口

pub mod query;
pub mod record;
pub mod store;

pub use query::query_by_exec;
pub use record::EpRecord;
pub use store::{EpisodicStore, InMemoryEpisodicStore};
