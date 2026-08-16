// Copyright (c) 2026 AIMarketing
//
// AIMarketing v5 §6.2 — `PrincipleStore` 抽象 + `InMemoryPrincipleStore` 默认实现。
//
// 设计决策(doc comment):
//   * trait 暴露 4 个方法:`list` / `get` / `add` / `validate`。
//     故意**不**暴露 `remove`:原则一旦被采纳就是"经验",不应该
//     被程序任意删除;若需要"撤销",应该走 `validate(success=false)`
//     让 `confidence` 衰减到 0,自然被上层搜索忽略。
//   * `add` 返回去重后的 id(statement 一致视为重复,沿用旧条目),
//     而**不**返回 `Result<String, String>`,理由:
//     (1) "重复添加"在原则库里是正常路径(offline distill 反复跑),
//         不应让上层 panic;
//     (2) 上层若关心"是否新建",可以比较"返回的 id == 自己生成的 id"。
//   * `validate` 返回 `Result<(), String>`:输入 id 找不到是真正的
//     错误,需要让 executor / 反思 agent 知道,而不是静默吞掉。
//   * `Send + Sync`:与 `EpisodicStore` 同理,要能挂到全局状态被
//     多线程 executor 共享。
//   * 线程安全:与 `InMemoryEpisodicStore` 一样,用 `std::sync::Mutex`
//     保护 `HashMap<id, Principle>`。原则数量在 10² 量级,
//     Mutex 锁竞争不构成瓶颈。

use std::collections::HashMap;
use std::sync::Mutex;

use super::types::Principle;

/// 原则库抽象。
///
/// 原则(`Principle`)是"经过反思提炼、可被复用"的工作流经验,粒度
/// 介于 `Skill`(完整 SOP)与 `EpRecord`(单步事实)之间。
pub trait PrincipleStore: Send + Sync {
    /// 列出所有原则(顺序未保证;调用方如需稳定顺序,自行 sort)。
    fn list(&self) -> Vec<Principle>;
    /// 按 id 取单条;找不到返回 `None`。
    fn get(&self, id: &str) -> Option<Principle>;
    /// 添加一条原则。
    ///
    /// **去重规则**:若库内已有 `statement` 字段完全相同的条目,
    /// 沿用旧 id 并把新条目的 `supporting_records` 合并进去;
    /// `confidence` 沿用旧值,新值丢弃(以防恶意/噪声把高 confidence
    /// 拉低)。
    ///
    /// 返回值:**最终入库的** id(新建或复用)。`p.id` 若为空,
    /// 函数会分配新 uuid 填回去。
    fn add(&self, p: Principle) -> String;
    /// 校验一条原则(`success=true` 增 `validation_count`,
    /// `success=false` 增 `invalidated_count`),并刷新
    /// `last_validated` 与 `confidence`。
    ///
    /// id 找不到 → 返回 `Err`,**不**静默忽略。
    fn validate(&self, id: &str, success: bool) -> Result<(), String>;
}

/// 进程内 `HashMap<String, Principle>`,用 `Mutex` 保护。
///
/// 本期默认实现。生产环境长期会切到 SQLite(同 `SqliteEpisodicStore`
/// 的演进路线),届时另起一个 `SqlitePrincipleStore` 即可,本 trait
/// 形态保持稳定。
#[derive(Debug, Default)]
pub struct InMemoryPrincipleStore {
    inner: Mutex<HashMap<String, Principle>>,
}

impl InMemoryPrincipleStore {
    pub fn new() -> Self {
        Self::default()
    }
}

impl PrincipleStore for InMemoryPrincipleStore {
    fn list(&self) -> Vec<Principle> {
        // 锁中毒(poison)时拿 inner,与 `InMemoryEpisodicStore` 风格一致。
        self.inner
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .values()
            .cloned()
            .collect()
    }

    fn get(&self, id: &str) -> Option<Principle> {
        self.inner
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(id)
            .cloned()
    }

    fn add(&self, mut p: Principle) -> String {
        let mut guard = self.inner.lock().unwrap_or_else(|e| e.into_inner());

        // 1. 按 statement 去重:命中已有条目 → 合并 supporting_records + 沿用 id。
        if let Some(existing) = guard
            .values()
            .find(|q| q.statement == p.statement && !q.statement.is_empty())
            .cloned()
        {
            let mut merged = existing;
            for rec in &p.supporting_records {
                if !merged.supporting_records.contains(rec) {
                    merged.supporting_records.push(rec.clone());
                }
            }
            // confidence / validation 计数沿用旧值,丢弃新值
            let id = merged.id.clone();
            guard.insert(id.clone(), merged);
            return id;
        }

        // 2. 全新条目:补 uuid,塞进去。
        if p.id.is_empty() {
            p.id = uuid::Uuid::new_v4().to_string();
        }
        let id = p.id.clone();
        guard.insert(id.clone(), p);
        id
    }

    fn validate(&self, id: &str, success: bool) -> Result<(), String> {
        let mut guard = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        let entry = guard
            .get_mut(id)
            .ok_or_else(|| format!("找不到原则 id='{}',无法校验", id))?;
        // unix 毫秒;不依赖 chrono(本 trait 不需要)
        let now_ms: i64 = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis() as i64)
            .unwrap_or(0);
        entry.last_validated = now_ms;
        if success {
            entry.validation_count = entry.validation_count.saturating_add(1);
        } else {
            entry.invalidated_count = entry.invalidated_count.saturating_add(1);
        }
        entry.recompute_confidence();
        Ok(())
    }
}

// ============================================================================
// Unit tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pc_automation::principles::types::PrincipleCategory;

    fn p(statement: &str, category: PrincipleCategory) -> Principle {
        Principle::new(category, statement, 1_700_000_000_000)
    }

    /// `add` + `get` round-trip:新条目必须可被 get 拿到。
    #[test]
    fn test_principle_store_add_and_get_round_trip() {
        let store = InMemoryPrincipleStore::new();
        let mut x = p("css 优先于 uia", PrincipleCategory::Selector);
        x.supporting_records = vec!["r1".to_string()];
        let id = store.add(x.clone());
        assert!(!id.is_empty(), "add 必须返回非空 id");
        let back = store.get(&id).expect("刚刚 add 的应能 get 出来");
        assert_eq!(back.statement, x.statement);
        assert_eq!(back.category, x.category);
        assert_eq!(back.supporting_records, vec!["r1".to_string()]);
    }

    /// `add` 去重:同一 statement 第二次 add 必须返回旧 id,
    /// 且 supporting_records 合并去重。
    #[test]
    fn test_principle_store_dedupes_by_statement() {
        let store = InMemoryPrincipleStore::new();
        let mut a = p("先点 确认 再点 提交", PrincipleCategory::Sequencing);
        a.supporting_records = vec!["r1".to_string(), "r2".to_string()];
        let id_a = store.add(a);
        assert!(!id_a.is_empty());

        // 第二次 add,带新 supporting record
        let mut b = p("先点 确认 再点 提交", PrincipleCategory::Sequencing);
        b.supporting_records = vec!["r2".to_string(), "r3".to_string()];
        let id_b = store.add(b);
        // 必须复用旧 id
        assert_eq!(id_a, id_b, "同一 statement 必须复用旧 id");

        let back = store.get(&id_a).expect("去重后仍可 get");
        // supporting_records 应当 3 个不重复:r1, r2, r3
        let recs = back.supporting_records;
        assert_eq!(recs.len(), 3, "r2 重复,只该出现一次");
        assert!(recs.contains(&"r1".to_string()));
        assert!(recs.contains(&"r2".to_string()));
        assert!(recs.contains(&"r3".to_string()));
    }

    /// `validate` 必须:
    ///   * 增对应计数
    ///   * 刷新 last_validated
    ///   * 找不到 → 报错
    #[test]
    fn test_principle_store_validate_increments_counts() {
        let store = InMemoryPrincipleStore::new();
        let id = store.add(p("页面加载后等 1s 再操作", PrincipleCategory::Timing));

        // 1. 校验通过
        store.validate(&id, true).expect("校验应成功");
        let back = store.get(&id).unwrap();
        assert_eq!(back.validation_count, 1);
        assert_eq!(back.invalidated_count, 0);
        assert!(back.last_validated > 0, "last_validated 应被刷新");
        assert!((back.confidence - 1.0).abs() < 1e-6);

        // 2. 校验失败
        store.validate(&id, false).unwrap();
        let back = store.get(&id).unwrap();
        assert_eq!(back.validation_count, 1);
        assert_eq!(back.invalidated_count, 1);
        assert!((back.confidence - 0.5).abs() < 1e-6, "1/2 = 0.5");

        // 3. 找不到的 id 必须报错
        let err = store
            .validate("not-exist", true)
            .expect_err("不存在的 id 必须报错");
        assert!(err.contains("找不到"), "错误信息应含 '找不到': {}", err);
    }

    /// `list` 必须返回所有 add 的条目(顺序未保证,但 set 相等)。
    #[test]
    fn test_principle_store_list_returns_all() {
        let store = InMemoryPrincipleStore::new();
        store.add(p("a", PrincipleCategory::Selector));
        store.add(p("b", PrincipleCategory::Recovery));
        store.add(p("c", PrincipleCategory::Timing));
        let list = store.list();
        assert_eq!(list.len(), 3);
        let stmts: Vec<String> = list.iter().map(|p| p.statement.clone()).collect();
        assert!(stmts.contains(&"a".to_string()));
        assert!(stmts.contains(&"b".to_string()));
        assert!(stmts.contains(&"c".to_string()));
    }

    /// `add` 必须给空 id 的 Principle 分配新 uuid。
    /// 这条测试锁定"`Principle::new` 不分配 id,靠 store.add 补"
    /// 的契约。
    #[test]
    fn test_principle_store_add_assigns_id_when_empty() {
        let store = InMemoryPrincipleStore::new();
        // `Principle::new` 会留空 id
        let x = Principle::new(PrincipleCategory::Selector, "x", 0);
        assert!(x.id.is_empty(), "Principle::new 必须把 id 留空");
        let id = store.add(x);
        assert!(!id.is_empty(), "store.add 必须补 uuid");
        // id 长度 ≈ 36(标准 uuid v4)
        assert!(id.len() >= 32);
    }
}
