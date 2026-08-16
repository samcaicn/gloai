// Copyright (c) 2026 tupAI
//
// tupAI v5 §6.2 — 失败聚类(自进化层的"聚类"环节)。
//
// 设计决策(doc comment):
//   * 本期采用**简单启发式**聚类:按 `(app_profile, error_pattern 头 100 字符)`
//     二元组分组,而不是 embedding / DBSCAN 这种重型方案。理由:
//       (1) 聚类必须 100% 可解释、零网络依赖、可在 CI 跑过;
//       (2) 实际数据规模(单日失败条数 ~10² 量级)下,O(N) 分组远好于
//           O(N log N) 聚类算法的常数因子;
//       (3) 后续接 `usearch` / `sqlite-vec` 时,本模块可平滑升级到
//           "dbscan on intent embedding",函数签名不需要破坏。
//   * `intent_substring` 提取:从 group 内所有 intent 里取"最长公共子串"。
//     本期实现为"最长公共前缀(LCP)",原因是:
//       (1) 中文场景下,前缀比任意子串更稳定;
//       (2) 算法实现简单,无需 suffix tree / Z-algorithm 库;
//       (3) "如果前缀不存在,fallback 到 group 内第一条 intent"是合理降级。
//   * `error_pattern` 截取前 100 字符:避免长 stack trace 把聚类 key
//     撑爆,也避免一次性把敏感信息(文件路径、URL)带出日志边界。
//   * `members` 只存 `EpRecord.id`(轻量字符串),具体 record 通过
//     `EpisodicStore::snapshot()` 反查,避免本模块双向依赖 `episodic` 写入路径。

use serde::{Deserialize, Serialize};

use crate::pc_automation::episodic::EpRecord;

/// 反思聚类配置。
///
/// `cluster_similarity_threshold` 字段本期**未在算法中使用**,
/// 留作未来切换到 embedding 聚类时的"距离阈值"前置参数。
/// 标记为 `#[allow(dead_code)]` 以避免触发 unused-field 警告,
/// 同时在 doc comment 里说明留参意图,避免后续 reviewer 困惑。
#[derive(Debug, Clone)]
#[allow(dead_code)]
pub struct ReflectionConfig {
    /// 切换到 embedding 聚类时启用;本期 LCP 算法不消费该字段。
    pub cluster_similarity_threshold: f32,
    /// 单个 cluster 的最大成员数,超出后截断并保留最早的成员。
    pub max_cluster_size: usize,
    /// 是否调用 LLM 生成 `suggested_selector`。为 `false` 时
    /// 永远走本地启发式 fallback,跳过云端往返。
    pub suggest_selector: bool,
}

impl Default for ReflectionConfig {
    fn default() -> Self {
        Self {
            cluster_similarity_threshold: 0.6,
            max_cluster_size: 50,
            suggest_selector: true,
        }
    }
}

/// 一个失败聚类。
///
/// 描述:同一 `(app_profile, error_pattern 头 100 字符)` 命中的
/// 多个 `EpRecord` 的归并视图。`suggested_selector` 是可选的
/// 修复建议(本期 LLM 生成 + 失败 fallback)。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct FailureCluster {
    /// 聚类 uuid,本聚类唯一。
    pub cluster_id: String,
    /// 共用 intent 的最长公共子串(本期 = 最长公共前缀)。
    /// 可能为空(例如 group 内 intent 集合两两前缀全无交集)。
    pub intent_substring: String,
    /// 关联 `AppProfile::id`;`None` 表示跨应用失败。
    pub app_profile: Option<String>,
    /// 共用 error 模式:取 group 内第一条非空 `error` 的前 100 字符。
    pub error_pattern: String,
    /// 成员 `EpRecord.id` 列表(本聚类内)。
    pub members: Vec<String>,
    /// group 内最早 timestamp。
    pub first_seen: i64,
    /// group 内最晚 timestamp。
    pub last_seen: i64,
    /// 修复建议 selector(LLM 生成 / 本地启发式 fallback)。
    pub suggested_selector: Option<String>,
    /// 0.0-1.0;LCP 长度 / 最长 intent 长度近似表示。
    pub confidence: f32,
}

impl FailureCluster {
    /// 新建一个聚类骨架(uuid 已生成,其它字段待聚类阶段填充)。
    pub fn new_skeleton(app_profile: Option<String>, error_pattern: String) -> Self {
        Self {
            cluster_id: uuid::Uuid::new_v4().to_string(),
            intent_substring: String::new(),
            app_profile,
            error_pattern,
            members: Vec::new(),
            first_seen: 0,
            last_seen: 0,
            suggested_selector: None,
            confidence: 0.0,
        }
    }
}

/// 把 `error` 字段截取前 100 字符作为聚类 key 的一部分。
///
/// 如果 `error` 为 `None`,返回固定哨兵 `"<no-error>"` 以便所有
/// "无错误"record 也能聚成一类(例如 `outcome = success` 但我们仍
/// 跑聚类时,这样不会丢)。
pub fn normalize_error_pattern(error: Option<&str>) -> String {
    match error {
        Some(e) if !e.is_empty() => {
            // 按字符截,避免在多字节字符中间切(中文安全)。
            let chars: Vec<char> = e.chars().take(100).collect();
            chars.into_iter().collect()
        }
        _ => "<no-error>".to_string(),
    }
}

/// 取一组字符串的最长公共前缀(LCP)。
///
/// 行为约定:
///   * 空集合 → 返回 `""`
///   * 任一元素为空 → 返回 `""`(LCP 不可能非空)
///   * 否则逐字符比较,直到第一个不匹配的位置。
///
/// 实现要点:把每个字符串一次性 `collect::<Vec<char>>()`,然后
/// 按字符索引比较。这样避免按字节切错(中文/emoji 安全),也避
/// 免在 inner loop 里反复扫描 `s.char_indices()`。
pub fn longest_common_prefix(strings: &[&str]) -> String {
    if strings.is_empty() {
        return String::new();
    }
    // 把所有字符串物化成 char 列表,一次扫描,内层循环 O(1) 索引访问。
    let as_chars: Vec<Vec<char>> = strings.iter().map(|s| s.chars().collect()).collect();
    let first = &as_chars[0];
    if first.is_empty() {
        return String::new();
    }
    let mut prefix = String::new();
    for (i, c) in first.iter().enumerate() {
        let mut matched = true;
        for s in &as_chars[1..] {
            if s.len() <= i || s[i] != *c {
                matched = false;
                break;
            }
        }
        if !matched {
            break;
        }
        prefix.push(*c);
    }
    prefix
}

/// 计算 `confidence`(LCP 长度 / 最长 intent 字符数,夹到 `[0, 1]`)。
///
/// 含义:相同前缀越长,代表"事故同质性"越高,修复建议越值得尝试。
/// 若 group 为空或 LCP 为空,confidence = 0。
pub fn compute_confidence(intents: &[&str], lcp: &str) -> f32 {
    if intents.is_empty() || lcp.is_empty() {
        return 0.0;
    }
    let max_len = intents
        .iter()
        .map(|s| s.chars().count())
        .max()
        .unwrap_or(0);
    if max_len == 0 {
        return 0.0;
    }
    let lcp_len = lcp.chars().count();
    let ratio = lcp_len as f32 / max_len as f32;
    ratio.clamp(0.0, 1.0)
}

/// 把一组 `EpRecord` 按 `(app_profile, normalize_error_pattern)`
/// 聚成 `FailureCluster` 列表。
///
/// 算法:
///   1. 用 `HashMap<(Option<String>, String), Vec<&EpRecord>>` 装桶
///   2. 对每个 bucket 构造一个 `FailureCluster`
///   3. `intent_substring` 来自 `longest_common_prefix` 桶内所有 intent
///   4. `members` 截断到 `cfg.max_cluster_size`
///   5. `confidence` 来自 `compute_confidence`
///
/// 本函数是纯函数,不带任何 LLM / 副作用;`suggested_selector`
/// 留 `None`,由调用方通过 `suggest::suggest_selector_for_cluster`
/// 单独补齐 — 这样聚类与建议解耦,方便单测。
pub fn cluster_failures(
    records: &[EpRecord],
    cfg: &ReflectionConfig,
) -> Vec<FailureCluster> {
    use std::collections::HashMap;

    // 桶 key = (app_profile, error_pattern 头 100 字符)
    let mut buckets: HashMap<(Option<String>, String), Vec<&EpRecord>> = HashMap::new();
    for r in records {
        let key = (
            r.app_profile.clone(),
            normalize_error_pattern(r.error.as_deref()),
        );
        buckets.entry(key).or_default().push(r);
    }

    let mut clusters: Vec<FailureCluster> = Vec::with_capacity(buckets.len());
    for ((app_profile, error_pattern), members_ref) in buckets {
        if members_ref.is_empty() {
            continue;
        }

        // 时间戳聚合
        let (mut first_seen, mut last_seen) = (i64::MAX, i64::MIN);
        for r in &members_ref {
            if r.timestamp < first_seen {
                first_seen = r.timestamp;
            }
            if r.timestamp > last_seen {
                last_seen = r.timestamp;
            }
        }
        if first_seen == i64::MAX {
            first_seen = 0;
        }
        if last_seen == i64::MIN {
            last_seen = 0;
        }

        // intent 公共前缀
        let intents: Vec<&str> = members_ref.iter().map(|r| r.intent.as_str()).collect();
        let lcp = longest_common_prefix(&intents);
        let confidence = compute_confidence(&intents, &lcp);

        // 成员 id 列表(截断到 max_cluster_size)
        let mut member_ids: Vec<String> =
            members_ref.iter().map(|r| r.id.clone()).collect();
        if member_ids.len() > cfg.max_cluster_size {
            member_ids.truncate(cfg.max_cluster_size);
        }

        let mut cluster = FailureCluster::new_skeleton(app_profile, error_pattern);
        cluster.intent_substring = lcp;
        cluster.members = member_ids;
        cluster.first_seen = first_seen;
        cluster.last_seen = last_seen;
        cluster.confidence = confidence;
        clusters.push(cluster);
    }

    // 按 last_seen 倒序排,把"最近发生的失败"放在最前,方便 UI 优先展示。
    clusters.sort_by_key(|b| std::cmp::Reverse(b.last_seen));
    clusters
}

// ============================================================================
// Unit tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pc_automation::episodic::record::EpRecord;

    fn mk_record(
        id: &str,
        ts: i64,
        app: Option<&str>,
        intent: &str,
        error: Option<&str>,
    ) -> EpRecord {
        let mut r = EpRecord::new(ts, "exec-1", format!("step-{}", id), intent, "failed");
        r.id = id.to_string();
        r.app_profile = app.map(|s| s.to_string());
        r.error = error.map(|s| s.to_string());
        r
    }

    /// 两个 record 的 `(app_profile, error_pattern)` 完全相同
    /// → 必须聚到同一 cluster,且 `members` 包含两个 id。
    #[test]
    fn test_cluster_failures_groups_by_app_and_error() {
        let cfg = ReflectionConfig::default();
        let records = vec![
            mk_record(
                "r1",
                100,
                Some("ths_hexin"),
                "提交订单到平安证券",
                Some("uia:button?name=提交 not found"),
            ),
            mk_record(
                "r2",
                200,
                Some("ths_hexin"),
                "提交订单到华泰证券",
                Some("uia:button?name=提交 not found"),
            ),
        ];
        let clusters = cluster_failures(&records, &cfg);
        assert_eq!(clusters.len(), 1, "app+error 一致必须聚成 1 个");
        assert_eq!(clusters[0].members.len(), 2);
        assert!(clusters[0].members.contains(&"r1".to_string()));
        assert!(clusters[0].members.contains(&"r2".to_string()));
        assert_eq!(clusters[0].app_profile.as_deref(), Some("ths_hexin"));
        assert_eq!(clusters[0].first_seen, 100);
        assert_eq!(clusters[0].last_seen, 200);
    }

    /// intent 公共前缀 = "提交订单到";即使后面 broker 名不同,
    /// LCP 仍能稳定抽出"共同意图"。
    #[test]
    fn test_cluster_intent_substring_extracts_common_prefix() {
        let cfg = ReflectionConfig::default();
        let records = vec![
            mk_record(
                "r1",
                100,
                Some("ths_hexin"),
                "提交订单到平安证券",
                Some("err-x"),
            ),
            mk_record(
                "r2",
                200,
                Some("ths_hexin"),
                "提交订单到华泰证券",
                Some("err-x"),
            ),
            mk_record(
                "r3",
                300,
                Some("ths_hexin"),
                "提交订单到国泰君安",
                Some("err-x"),
            ),
        ];
        let clusters = cluster_failures(&records, &cfg);
        assert_eq!(clusters.len(), 1);
        assert_eq!(
            clusters[0].intent_substring, "提交订单到",
            "三个 intent 的 LCP 应是 '提交订单到'(5 字符),实际: {}",
            clusters[0].intent_substring
        );
        // confidence = 5 / 9 ≈ 0.5556
        assert!(
            (clusters[0].confidence - 5.0 / 9.0).abs() < 1e-6,
            "confidence 应为 5/9,实际 {}",
            clusters[0].confidence
        );
    }

    /// `max_cluster_size` 必须截断 `members`,但 bucket 本身仍算 1 个聚类。
    #[test]
    fn test_cluster_respects_max_size() {
        let cfg = ReflectionConfig {
            max_cluster_size: 3,
            ..ReflectionConfig::default()
        };
        let mut records = Vec::new();
        for i in 0..10 {
            records.push(mk_record(
                &format!("r{i}"),
                100 + i,
                Some("ths_hexin"),
                "提交订单",
                Some("err-shared"),
            ));
        }
        let clusters = cluster_failures(&records, &cfg);
        assert_eq!(clusters.len(), 1);
        assert_eq!(
            clusters[0].members.len(),
            3,
            "max_cluster_size=3 必须把 members 截到 3"
        );
        // first_seen / last_seen 不受截断影响
        assert_eq!(clusters[0].first_seen, 100);
        assert_eq!(clusters[0].last_seen, 109);
    }

    /// `error` 为 `None` 时,normalize 出来是 "<no-error>",
    /// 所有"无错误"record 会聚到同一桶(用途:把 `outcome = success` 但
    /// 仍然要纳入聚类的场景统一处理)。
    #[test]
    fn test_cluster_handles_missing_error() {
        let cfg = ReflectionConfig::default();
        let records = vec![
            mk_record("r1", 100, Some("app-a"), "intent-1", None),
            mk_record("r2", 200, Some("app-a"), "intent-2", None),
        ];
        let clusters = cluster_failures(&records, &cfg);
        assert_eq!(clusters.len(), 1);
        assert_eq!(clusters[0].error_pattern, "<no-error>");
    }

    /// `longest_common_prefix` 的边界:空集合、空元素、零交集。
    #[test]
    fn test_longest_common_prefix_edge_cases() {
        assert_eq!(longest_common_prefix(&[]), "");
        assert_eq!(longest_common_prefix(&[""]), "");
        assert_eq!(longest_common_prefix(&["", "abc"]), "");
        assert_eq!(longest_common_prefix(&["abc"]), "abc");
        assert_eq!(longest_common_prefix(&["abc", "abd"]), "ab");
        // 中文: 同前缀
        assert_eq!(
            longest_common_prefix(&["提交订单到A", "提交订单到B"]),
            "提交订单到"
        );
    }

    /// `compute_confidence` 行为:L=LCP, M=最长 intent 字符数
    /// → L/M,夹到 [0, 1]。
    #[test]
    fn test_compute_confidence_monotonic() {
        let i1 = "提交订单到平安证券"; // 9 chars
        let i2 = "提交订单到华泰证券"; // 9 chars
        // LCP = "提交订单到"(5 字符), max_len = 9
        let lcp = longest_common_prefix(&[i1, i2]);
        let c = compute_confidence(&[i1, i2], &lcp);
        assert!(
            (c - 5.0 / 9.0).abs() < 1e-6,
            "5/9 ≈ 0.5556,实际 {}",
            c
        );
        // 空 LCP → 0
        assert_eq!(compute_confidence(&[i1, i2], ""), 0.0);
        // 空集合 → 0
        assert_eq!(compute_confidence(&[], "abc"), 0.0);
    }
}
