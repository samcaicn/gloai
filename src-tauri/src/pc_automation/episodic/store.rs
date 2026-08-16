// Copyright (c) 2026 tupAI
//
// tupAI v5 — `EpisodicStore` 抽象 + `InMemoryEpisodicStore` 实现 +
// `SqliteEpisodicStore` 桩。
//
// 设计决策(doc comment):
//   * trait 故意只暴露三个方法:`record` / `snapshot` / `len`。
//     query 逻辑(按 exec_id 查 / 按 outcome 过滤 / 子串相似)
//     全部留在 `query.rs` 中作为自由函数实现 — 这样:
//       (1) trait 不会随新 query 需求不断膨胀;
//       (2) 不同的 store impl 可以复用同一套查询;
//       (3) 测试 mock trait 只需要实现 3 个方法,而不是 7+ 个 query 形态。
//   * `record` 不返回 `Result`:理由是「情景记忆」是**辅助**系统,
//     写入失败不应该让 executor 整步失败(反思数据缺失是可接受的降级)。
//     真正要查日志的开发者可以通过 `snapshot()` 看到本地副本是否被写入,
//     或者接入未来的 `SqliteEpisodicStore` 时改用 `Result` 形态扩展 trait。
//   * `Send + Sync` 是为了让 `Arc<dyn EpisodicStore>` 可以挂到
//     `HermesAppState` / `UirapState` 之类的全局状态,被多线程 executor
//     共享访问。
//   * 持久化路径:本期仅 in-memory。`SqliteEpisodicStore` 留作
//     `#[allow(dead_code)]` 接口 + `unimplemented!()` stub,等
//     `HermesAppState` 决定挂载路径(直接复用 `commands::open_app_db`
//     还是新建 `episodes.db`)时再补。

use std::sync::Mutex;

use super::record::EpRecord;

/// 三级记忆中的「情景记忆」存储抽象。
///
/// 实现者必须保证:
///   * `record` 是幂等的吗?**否** — 重复插入会产生两条 record
///     (允许 executor 在 retry 链路上多次写)。
///   * `record` 是线程安全的吗?**是** — 实现者内部需要自带锁。
///   * `snapshot` 的返回顺序是 FIFO(插入顺序)吗?**是** — query 函数
///     依赖该顺序实现"最近 limit 条"语义。
pub trait EpisodicStore: Send + Sync {
    /// 写入一条情景记忆。失败应被静默降级,而不是 panic。
    fn record(&self, rec: EpRecord);

    /// 全量快照,按插入顺序返回。
    fn snapshot(&self) -> Vec<EpRecord>;

    /// 当前条目数。默认通过 `snapshot` 计算,具体实现可以 override 以
    /// 避免拷贝(例如 SQLite 直接 `SELECT COUNT(*)`)。
    fn len(&self) -> usize {
        self.snapshot().len()
    }

    /// 是否为空。`default` 形式以兼容 `len = 0` 时的早期 return。
    fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

/// 进程内 `Vec<EpRecord>`,用 `std::sync::Mutex` 保护。
///
/// 本期默认实现;适合 dev / test / 单进程场景。生产环境会被
/// `SqliteEpisodicStore` 替换以提供崩溃恢复 + 跨进程分析能力。
#[derive(Debug, Default)]
pub struct InMemoryEpisodicStore {
    inner: Mutex<Vec<EpRecord>>,
}

impl InMemoryEpisodicStore {
    pub fn new() -> Self {
        Self::default()
    }
}

impl EpisodicStore for InMemoryEpisodicStore {
    fn record(&self, rec: EpRecord) {
        // 取锁失败说明另一个持有锁的线程 panic 了。我们选择 `unwrap_or_else`
        // 把锁 poison 当成空 Vec 处理,避免让单次"反思记录"阻塞主流程 —
        // 这与 trait doc comment 中"record 不应让 executor 失败"的承诺一致。
        let mut guard = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        guard.push(rec);
    }

    fn snapshot(&self) -> Vec<EpRecord> {
        self.inner
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone()
    }

    fn len(&self) -> usize {
        self.inner
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .len()
    }
}

/// SQLite 持久化版的 stub。
///
/// 设计意图:把 rusqlite 表 schema 锁定在 doc comment 里,后续真接入时
/// 不用再纠结字段顺序。schema:
///
/// ```sql
/// CREATE TABLE IF NOT EXISTS episodes (
///     id              TEXT PRIMARY KEY,
///     timestamp_ms    INTEGER NOT NULL,
///     exec_id         TEXT NOT NULL,
///     step_id         TEXT NOT NULL,
///     app_profile     TEXT,
///     intent          TEXT NOT NULL,
///     selector_used   TEXT,
///     strategy_used   TEXT NOT NULL,
///     outcome         TEXT NOT NULL,
///     error           TEXT,
///     screenshot_hash TEXT,
///     vlm_prompt      TEXT,
///     vlm_response    TEXT,
///     latency_ms      INTEGER NOT NULL
/// );
/// CREATE INDEX IF NOT EXISTS idx_episodes_exec_id   ON episodes(exec_id);
/// CREATE INDEX IF NOT EXISTS idx_episodes_outcome   ON episodes(outcome);
/// CREATE INDEX IF NOT EXISTS idx_episodes_intent    ON episodes(intent);
/// ```
///
/// `#[allow(dead_code)]` 是为了让本期没有调用方时编译通过
/// (任务约束:不引入新 crate,但 trait impl 必须留接口)。
#[allow(dead_code)]
pub struct SqliteEpisodicStore {
    // TODO: `rusqlite::Connection` 字段 — 等本期决定 mount 路径
    // (复用 `commands::open_app_db` 还是新建 `episodes.db`)时再填。
    // 我们刻意不在签名里写 `Connection`,因为本期的 `pc_automation`
    // 树不依赖 `rusqlite` 符号(隔离编译边界)。
    _placeholder: (),
}

#[allow(dead_code)]
impl SqliteEpisodicStore {
    /// 打开(必要时创建)SQLite 数据库。
    /// TODO:接受 `&Path`,内部 `Connection::open(path)` 并 `execute_batch` schema。
    pub fn open(_path: impl AsRef<std::path::Path>) -> Result<Self, String> {
        Err("SqliteEpisodicStore::open 尚未实现 — 留待 HermesAppState 决定 mount 路径时落地".to_string())
    }
}

impl EpisodicStore for SqliteEpisodicStore {
    fn record(&self, _rec: EpRecord) {
        // TODO: INSERT INTO episodes (...) VALUES (...);
        unimplemented!("SqliteEpisodicStore::record — 等待 rusqlite 接入时实现");
    }

    fn snapshot(&self) -> Vec<EpRecord> {
        // TODO: SELECT * FROM episodes ORDER BY timestamp_ms ASC;
        unimplemented!("SqliteEpisodicStore::snapshot — 等待 rusqlite 接入时实现");
    }

    fn len(&self) -> usize {
        // TODO: SELECT COUNT(*) FROM episodes;
        unimplemented!("SqliteEpisodicStore::len — 等待 rusqlite 接入时实现");
    }
}

// ============================================================================
// Unit tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pc_automation::episodic::query;

    /// 写一条 → snapshot 能拿出来,字段一致。
    /// 这是「trait 不丢数据」最基本的 round-trip 验证。
    #[test]
    fn test_record_in_memory_store_round_trip() {
        let store = InMemoryEpisodicStore::new();
        let mut rec = EpRecord::new(1_700_000_000_000, "exec-1", "step-1", "提交订单", "success");
        rec.strategy_used = "uia".to_string();
        rec.selector_used = Some("uia:button".to_string());
        rec.latency_ms = 42;

        // 公开 API 入口
        query::record(&store, rec.clone());

        let snap = store.snapshot();
        assert_eq!(snap.len(), 1, "snapshot.len 应该是 1");
        assert_eq!(snap[0], rec, "snapshot[0] 必须字段一致");
        assert_eq!(store.len(), 1);
        assert!(!store.is_empty());
    }

    /// 多线程并发 `record` 不丢数据。`Mutex<Vec<_>>` 应该是
    /// 自带线程安全的,本测试就是把这个承诺钉死。
    #[test]
    fn test_record_is_thread_safe() {
        use std::sync::Arc;
        use std::thread;

        let store = Arc::new(InMemoryEpisodicStore::new());
        let mut handles = Vec::new();
        for i in 0..8 {
            let s = Arc::clone(&store);
            handles.push(thread::spawn(move || {
                for j in 0..16 {
                    let rec = EpRecord::new(
                        1_700_000_000_000 + (i * 16 + j) as i64,
                        "exec-shared",
                        format!("step-{}-{}", i, j),
                        "并发写入",
                        "success",
                    );
                    query::record(s.as_ref(), rec);
                }
            }));
        }
        for h in handles {
            h.join().unwrap();
        }
        assert_eq!(store.len(), 8 * 16, "8 线程 × 16 条 必须全部到位");
    }
}
