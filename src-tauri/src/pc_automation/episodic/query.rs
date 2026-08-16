// Copyright (c) 2026 tupAI
//
// tupAI v5 — `EpisodicStore` 之上的查询 API 自由函数集合。
//
// 设计决策(doc comment):
//   * query 是「读」操作,实现为模块级自由函数,而不是 trait 方法。
//     理由:
//       (1) trait 不会随新 query 需求不断膨胀;
//       (2) 不同的 store impl 可以复用同一套查询;
//       (3) 测试 mock trait 只需要实现 3 个方法,而不是 7+ 个 query 形态。
//   * 全量 `snapshot()` 是 O(N);本期 in-memory 实现下没问题,
//     后续切到 SQLite 后,`SqliteEpisodicStore` 应该 override `snapshot`
//     走 `WHERE` + `LIMIT` 路径,避免把所有行都加载到 Rust 侧内存。
//   * `query_similar` 本期**只用 intent 子串匹配**,不接 embedding。
//     后续接入 `usearch` / `sqlite-vec` 升级为稠密向量 ANN 检索时,
//     可以把 `query_similar` 改成"先 SQL 粗筛候选,再向量重排",
//     不需要动函数签名 — 这是把"易变算法"隔离在自由函数内的好处。

use super::record::EpRecord;
use super::store::EpisodicStore;

/// 写入一条情景记忆。底层失败时**不传播**给调用方 — 反思数据
/// 缺失不应阻塞主流程。
///
/// 实际行为由 `EpisodicStore::record` 实现定义;in-memory 版本
/// 就是 `Vec::push`,SQLite 版本会做 `INSERT` 并吞掉错误。
pub fn record(store: &dyn EpisodicStore, rec: EpRecord) {
    store.record(rec);
}

/// 按 `exec_id` 取出某次完整 skill 执行产生的所有 step record。
///
/// 用于:
///   * 前端"重放这次执行"的入口
///   * `trajectory::export::from_episodic` 把某次执行打包成训练数据
pub fn query_by_exec(store: &dyn EpisodicStore, exec_id: &str) -> Vec<EpRecord> {
    store
        .snapshot()
        .into_iter()
        .filter(|r| r.exec_id == exec_id)
        .collect()
}

/// 取出最近 `limit` 条失败 record(按 `timestamp` 倒序)。
///
/// "失败"的定义:`outcome` ∈ {`primary_miss`, `structured_miss`, `failed`}。
/// `vlm_rescued` **不**算失败 — 它代表 VLM 救回来了,这种 case
/// 仍然有价值(反思时能看到"是 VLM 救的"),但属于"反思训练"分类,
/// 不属于"事故复盘"分类,放在 `query_failures` 里会污染数据。
pub fn query_failures(store: &dyn EpisodicStore, limit: usize) -> Vec<EpRecord> {
    let mut buf: Vec<EpRecord> = store
        .snapshot()
        .into_iter()
        .filter(|r| matches!(r.outcome.as_str(), "primary_miss" | "structured_miss" | "failed"))
        .collect();
    // 按 timestamp 倒序:新的失败在前
    buf.sort_by_key(|b| std::cmp::Reverse(b.timestamp));
    buf.truncate(limit);
    buf
}

/// 按 `intent` 子串匹配取最多 `limit` 条 record。
///
/// 匹配规则:`r.intent.contains(intent)`(大小写敏感,因为 intent 字段
/// 在 SKILL.md 里就是大小写敏感的)。空 `intent` 等价于"返回空",
/// 调用方应该自己决定是否走"取全部"的退化路径。
///
/// TODO:接入 `usearch` / `sqlite-vec` 改为稠密向量 ANN 检索。
pub fn query_similar(store: &dyn EpisodicStore, intent: &str, limit: usize) -> Vec<EpRecord> {
    if intent.is_empty() {
        return Vec::new();
    }
    let mut buf: Vec<EpRecord> = store
        .snapshot()
        .into_iter()
        .filter(|r| r.intent.contains(intent))
        .collect();
    // 子串匹配下没有自然的"相似度分数",按 timestamp 倒序近似为"最近优先"
    buf.sort_by_key(|b| std::cmp::Reverse(b.timestamp));
    buf.truncate(limit);
    buf
}

// ============================================================================
// Unit tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pc_automation::episodic::record::EpRecord;
    use crate::pc_automation::episodic::store::InMemoryEpisodicStore;

    /// 混合写多条,按 `exec_id` 过滤只取该次执行的 record。
    /// 隐含断言:同 exec_id 下多 step 都能取全,跨 exec_id 严格隔离。
    #[test]
    fn test_query_by_exec_returns_only_matching() {
        let store = InMemoryEpisodicStore::new();

        // exec-A:3 step
        for i in 0..3 {
            let mut r = EpRecord::new(
                1_700_000_000_000 + i,
                "exec-A",
                format!("step-A-{}", i),
                "提交订单",
                "success",
            );
            r.strategy_used = "uia".into();
            record(&store, r);
        }
        // exec-B:2 step
        for i in 0..2 {
            let r = EpRecord::new(
                1_700_000_000_100 + i,
                "exec-B",
                format!("step-B-{}", i),
                "撤单",
                "failed",
            );
            record(&store, r);
        }

        let a = query_by_exec(&store, "exec-A");
        assert_eq!(a.len(), 3, "exec-A 必须有 3 条");
        assert!(a.iter().all(|r| r.exec_id == "exec-A"));

        let b = query_by_exec(&store, "exec-B");
        assert_eq!(b.len(), 2, "exec-B 必须有 2 条");
        assert!(b.iter().all(|r| r.exec_id == "exec-B"));

        // 不存在的 exec 返回空 Vec
        let none = query_by_exec(&store, "exec-NOPE");
        assert!(none.is_empty());
    }

    /// `query_failures` 必须:
    ///   * 排除 `success` 和 `vlm_rescued`
    ///   * 按 timestamp 倒序
    ///   * `limit` 截断
    #[test]
    fn test_query_failures_filters_outcome() {
        let store = InMemoryEpisodicStore::new();

        let mk = |ts, outcome: &str| {
            let mut r = EpRecord::new(
                ts,
                "exec-X",
                format!("step-{}", ts),
                "查持仓",
                outcome,
            );
            r.error = Some(format!("err-{}", ts));
            r
        };

        // 故意交错写入,timestamp 小的反而后写
        record(&store, mk(100, "success"));
        record(&store, mk(200, "primary_miss"));
        record(&store, mk(300, "structured_miss"));
        record(&store, mk(400, "vlm_rescued")); // 不应出现
        record(&store, mk(500, "failed"));
        record(&store, mk(600, "primary_miss"));

        let failures = query_failures(&store, 100);
        // 期望 4 条: 200 primary_miss, 300 structured_miss, 500 failed, 600 primary_miss
        assert_eq!(failures.len(), 4, "vlm_rescued/success 不应混入");
        // 倒序: 600, 500, 300, 200
        let ts: Vec<i64> = failures.iter().map(|r| r.timestamp).collect();
        assert_eq!(ts, vec![600, 500, 300, 200]);

        // limit=2 取最新两条
        let top2 = query_failures(&store, 2);
        assert_eq!(top2.len(), 2);
        assert_eq!(top2[0].timestamp, 600);
        assert_eq!(top2[1].timestamp, 500);
    }

    /// `query_similar` 必须按 `intent` 子串匹配,
    /// 且 limit 截断 + timestamp 倒序。
    #[test]
    fn test_query_similar_substring_match() {
        let store = InMemoryEpisodicStore::new();

        let mk = |ts: i64, intent: &str, outcome: &str| {
            EpRecord::new(ts, "exec-1", format!("s-{}", ts), intent, outcome)
        };

        record(&store, mk(100, "提交订单到平安证券", "success"));
        record(&store, mk(200, "查询股票行情", "success"));
        record(&store, mk(300, "提交订单到华泰证券", "failed"));
        record(&store, mk(400, "撤单", "success"));

        // "订单" 命中 #1 和 #3
        let r1 = query_similar(&store, "订单", 10);
        assert_eq!(r1.len(), 2);
        let ts: Vec<i64> = r1.iter().map(|r| r.timestamp).collect();
        assert_eq!(ts, vec![300, 100], "应按 timestamp 倒序");

        // "证券" 也命中 #1 和 #3
        let r2 = query_similar(&store, "证券", 10);
        assert_eq!(r2.len(), 2);

        // "股票" 仅命中 #2
        let r3 = query_similar(&store, "股票", 10);
        assert_eq!(r3.len(), 1);
        assert_eq!(r3[0].timestamp, 200);

        // 不存在 → 空
        assert!(query_similar(&store, "不存在的意图", 10).is_empty());

        // 空 query → 空(避免误返回全表)
        assert!(query_similar(&store, "", 10).is_empty());

        // limit 截断
        let r4 = query_similar(&store, "订单", 1);
        assert_eq!(r4.len(), 1);
        assert_eq!(r4[0].timestamp, 300, "limit=1 取最新");
    }
}
