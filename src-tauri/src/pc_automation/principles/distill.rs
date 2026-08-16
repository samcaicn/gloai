// Copyright (c) 2026 tupAI
//
// tupAI v5 §6.2 — 离线原则提炼器(v6 第一阶段落地)。
//
// 设计决策(doc comment):
//   * 本期真正打通"输入 → 调 LLM → 解析
//     响应 → 写入 PrincipleStore"的回路。上一期 stub 走到了 LLM
//     调通就返回空,这一期把"拿到 LLM 字符串"之后的事补完:
//       1. 抽取响应里的 JSON 数组(容错 markdown ```json ... ```);
//       2. 反序列化为 `PrincipleDraft`(category / statement / 可选
//          `supportingIndices`);
//       3. category 字符串 → `PrincipleCategory` enum,未知值**丢弃**;
//       4. `supporting_records` 用 LLM 给的下标回填,缺省回填"全部
//          入参 record.id";
//       5. `store.add(p)` 逐条写入,沿用 store 自己的 statement 去重;
//       6. 把"最终入库的 id 列表"返回给上游(若上游想 metric)。
//   * "LLM 失败 / 解析失败 / 全部 category 未知 → 返回 Ok(vec![])"
//     仍然是有意设计:反思-提炼是**离线**任务,失败下次重试即可,
//     不应让 executor 运行时因为 offline distill 失败而 panic。
//   * 本期未做(留给后续 v6.1):
//     - prompt 模板的人设 / 分组(按 app_profile / error_pattern 切)
//       进一步工程化;
//     - "重复 statement 的相似度归并"(目前只做字面去重,store 自带);
//     - 蒸馏 metric(每轮收了多少 record / 产了多少 Principle)。
//   * `LlmCompleteFn` / `LlmMessage` 仍由 `pc_automation::ui_tars`
//     re-export,这里不再 `use ... vlm_rescue::analyzer::{...}` 避免
//     与 ui_tars 形成双向依赖。

use std::sync::Arc;

use crate::pc_automation::episodic::EpRecord;
use crate::pc_automation::ui_tars::llm::try_call_llm;
use crate::pc_automation::ui_tars::LlmCompleteFn;
// uirap v2: `LlmCompleteFn` / `LlmMessage` 仍由 `pc_automation::ui_tars`
// re-export,这里不再 `use ... vlm_rescue::analyzer::{LlmCompleteFn,
// LlmMessage}`(避免与 ui_tars 形成双向依赖)。

use super::store::PrincipleStore;
use super::types::{Principle, PrincipleCategory};

/// 离线提炼:把一组 `EpRecord`(一般是失败 record)转化为原则
/// 列表,并写入 `store`。
///
/// 行为约定:
///   * `records.is_empty()` → `Ok(vec![])`(边界,无东西可提炼)。
///   * `llm.is_none()` 或 LLM 调用失败 / 返回空 → `Ok(vec![])`,
///     **不**传播错误(offline 任务的失败不应让上游 panic)。
///   * LLM 返回合法 JSON 数组 → 逐条 `store.add(p)`,沿用 store 自带的
///     statement 去重;返回**最终入库的** `Principle` 列表(已带新 id)。
///   * LLM 返回的 JSON 解析失败 / 全部 category 未知 → `Ok(vec![])`,
///     同样静默(同上)。
///
/// 与上一期 stub 的差别:`try_call_llm` 拿到字符串之后,本函数会:
///   1. 抽出 JSON 数组(容错 markdown ```json ... ``` 围栏);
///   2. 反序列化为 `Vec<PrincipleDraft>`;
///   3. 逐条 `map_category` → `PrincipleCategory`,未知值跳过;
///   4. `supporting_records` 用 LLM 给的下标回填,缺省回填
///      `records.iter().map(|r| r.id.clone()).collect()`;
///   5. `store.add` 写入(去重 / uuid / confidence 初始化都走 store 逻辑)。
pub async fn distill_from_records(
    records: &[EpRecord],
    store: &dyn PrincipleStore,
    llm: Option<Arc<dyn LlmCompleteFn>>,
) -> Result<Vec<Principle>, String> {
    // 0. 边界:无输入 record → 直接空
    if records.is_empty() {
        return Ok(Vec::new());
    }

    // 1. 没传 LLM → 离线无网模式,直接返回空(语义:本任务没东西可提炼)
    let Some(_llm_marker) = llm.as_ref() else {
        return Ok(Vec::new());
    };

    // 2. 组装 prompt
    let prompt = build_distill_prompt(records);

    // 3. 调用 LLM(统一用 `try_call_llm`,任何错误
    //    → 返回 `None` → 静默返回空)
    let raw = match try_call_llm(llm.as_ref(), prompt).await {
        Some(s) => s,
        None => return Ok(Vec::new()),
    };

    // 4. 解析 LLM 响应 → drafts
    let drafts = parse_principles_response(&raw);
    if drafts.is_empty() {
        return Ok(Vec::new());
    }

    // 5. drafts → 真实 Principle → 写入 store
    let now_ms: i64 = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0);

    let mut written: Vec<Principle> = Vec::with_capacity(drafts.len());
    for draft in drafts {
        let Some(cat) = map_category(&draft.category) else {
            // 未知 category → 跳过,避免脏数据进 store
            continue;
        };
        if draft.statement.trim().is_empty() {
            // 空 statement → 跳过
            continue;
        }
        let mut p = Principle::new(cat, draft.statement.trim(), now_ms);
        p.supporting_records = resolve_supporting_records(records, &draft);
        // 写入并把"最终入库的 Principle"推入返回列表
        // (store.add 会自己补 uuid / 沿用旧 id / 合并 supporting_records)
        let id = store.add(p);
        if let Some(back) = store.get(&id) {
            written.push(back);
        }
    }
    Ok(written)
}

/// LLM 输出的"原则草稿"——比 `Principle` 字段更少,只关心 LLM
/// 该给的 3 件事:category / statement / 可选 supportingIndices。
///
/// 注意:此结构只用于**反序列化 LLM 响应**,不入 store,也不 wire 给
/// 前端。所有 uuid / createdAt / confidence 等元数据都靠
/// `Principle::new` + `store.add` 自动填,避免 LLM 随手编造元数据
/// 导致脏数据。
#[derive(Debug, Clone, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PrincipleDraft {
    /// LLM 写的 category 字符串(期望 camelCase,大小写不敏感)。
    /// 未知值在 `map_category` 阶段被丢弃。
    pub category: String,
    /// 原则陈述,可中可英。
    pub statement: String,
    /// LLM 显式指定的"支撑 record 下标"(`0..records.len()`)。
    /// `None` 或缺字段时,fallback 为"全部入参 record"。
    #[serde(default)]
    pub supporting_indices: Option<Vec<usize>>,
}

/// 从 LLM 原始响应里抽出 JSON 数组,反序列化为 `Vec<PrincipleDraft>`。
///
/// 容错:
///   * 响应被 ```json ... ``` 或 ``` ... ``` 围栏包裹 → 剥掉;
///   * 响应头尾有 Thought: / 解释文字 → 用方括号配对找首个 JSON 数组;
///   * 解析失败 / 数组为空 → 返回空 Vec(不报错,符合 offline 语义)。
pub fn parse_principles_response(raw: &str) -> Vec<PrincipleDraft> {
    let json_str = extract_json_array(raw);
    let Some(json_str) = json_str else {
        return Vec::new();
    };
    serde_json::from_str::<Vec<PrincipleDraft>>(&json_str).unwrap_or_default()
}

/// 在 raw 文本里尽量找出"最像 JSON 数组"的子串。
///
/// 策略:剥掉 markdown 三连反引号围栏后,扫 `[` 配对到对应 `]`,
/// 找不到就返回 `None`。
fn extract_json_array(raw: &str) -> Option<String> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }
    // 1. 剥围栏 ```json ... ``` / ``` ...
    let stripped = strip_markdown_fence(trimmed);

    // 2. 找第一个 '[' 配对
    let bytes = stripped.as_bytes();
    let start = bytes.iter().position(|b| *b == b'[')?;
    let mut depth: i32 = 0;
    let mut end: Option<usize> = None;
    for (i, b) in bytes.iter().enumerate().skip(start) {
        match *b {
            b'[' => depth += 1,
            b']' => {
                depth -= 1;
                if depth == 0 {
                    end = Some(i);
                    break;
                }
            }
            _ => {}
        }
    }
    let end = end?;
    Some(stripped[start..=end].to_string())
}

/// 剥掉 ```json ... ``` / ``` ... ``` markdown 围栏。
/// 没找到围栏就原样返回。
fn strip_markdown_fence(s: &str) -> &str {
    let trimmed = s.trim();
    // 1. 整段是单一围栏块(常见 LLM 输出形态)
    if let Some(rest) = trimmed.strip_prefix("```") {
        // 跳过可选语言标签(json / JSON / text 等直到换行)
        let after_lang = rest.find('\n').map(|i| &rest[i + 1..]).unwrap_or(rest);
        if let Some(body) = after_lang.strip_suffix("```") {
            return body.trim();
        }
    }
    // 2. 围栏夹在文本中间:取首段 ``` 之后到下一个 ``` 之前
    if let Some(open_idx) = trimmed.find("```") {
        let after_open = &trimmed[open_idx + 3..];
        // 跳过可选语言标签
        let body_start = after_open.find('\n').map(|i| i + 1).unwrap_or(0);
        let body = &after_open[body_start..];
        if let Some(close_idx) = body.find("```") {
            return body[..close_idx].trim();
        }
    }
    trimmed
}

/// LLM 写的 category 字符串 → `PrincipleCategory`。
///
/// 大小写不敏感、忽略首尾空白;LLM 写的合法值一共 4 个
/// (`selector` / `sequencing` / `recovery` / `timing`),其它一律 `None`。
fn map_category(s: &str) -> Option<PrincipleCategory> {
    let s = s.trim().to_ascii_lowercase();
    match s.as_str() {
        "selector" => Some(PrincipleCategory::Selector),
        "sequencing" => Some(PrincipleCategory::Sequencing),
        "recovery" => Some(PrincipleCategory::Recovery),
        "timing" => Some(PrincipleCategory::Timing),
        _ => None,
    }
}

/// 把 draft 里的 `supporting_indices` 解析成真实的 record id 列表。
///
/// 规则:
///   * `supporting_indices` 为 `Some` → 按下标取(越界跳过,不去补);
///   * `supporting_indices` 为 `None`(LLM 没写) → fallback 到"全部
///     入参 record.id",语义上等价于"这条原则是从这批 record 提炼
///     出来的"。
///   * 入参 `records` 为空 → 返回空 Vec(`distill_from_records` 已在
///     step 0 处理过,这里是兜底)。
fn resolve_supporting_records(
    records: &[EpRecord],
    draft: &PrincipleDraft,
) -> Vec<String> {
    match &draft.supporting_indices {
        Some(idxs) => idxs
            .iter()
            .filter_map(|i| records.get(*i).map(|r| r.id.clone()))
            .collect(),
        None => records.iter().map(|r| r.id.clone()).collect(),
    }
}

/// 组装"请 LLM 从 record 列表提炼原则"的 prompt 字符串。
///
/// 关键输出约束:
///   * **必须**输出 JSON 数组,每条形如
///     `{"category":"...","statement":"...","supportingIndices":[0,1]}`;
///   * `category` 限定 4 个值之一,见 prompt 内列表;
///   * `supportingIndices` 可省略;省略时默认引用全部入参 record。
///
/// 仅生成文本(供 `distill_from_records` 调用),
/// 不实现解析外的副作用。**仅**为单元测试中的"prompt 应包含
/// 关键字段"断言暴露一个稳定入口。
pub fn build_distill_prompt(records: &[EpRecord]) -> String {
    let mut buf = String::from(
        "你是一名资深的 UI 自动化测试工程师。下面是一批近期失败的 \
         `EpRecord`,请你从中提炼出可复用的「工作流经验(Principle)」,\
         分类到以下 4 类之一:\n  - selector    (selector 选择经验)\n  \
         - sequencing  (步骤顺序经验)\n  - recovery    (错误恢复经验)\n  \
         - timing      (等待时机经验)\n\n## 失败 record 列表\n",
    );
    for (i, r) in records.iter().enumerate() {
        buf.push_str(&format!(
            "[{i}] ts={ts} app={app:?} intent='{intent}' strategy='{strategy}' \
             outcome='{outcome}' error='{error}'\n",
            ts = r.timestamp,
            app = r.app_profile,
            intent = r.intent,
            strategy = r.strategy_used,
            outcome = r.outcome,
            error = r.error.as_deref().unwrap_or(""),
        ));
    }
    buf.push_str(
        "\n## 输出要求 (严格遵守,否则下游无法解析)\n\
         请**只**输出一段 JSON 数组(可包裹在 ```json ... ``` 内),\
         每条形如:\n  {\"category\":\"selector|sequencing|recovery|timing\",\
         \"statement\":\"<经验陈述,中文优先>\",\"supportingIndices\":[0,1]}\n\
         说明:\n  - category 严格使用上述 4 个值之一,不要造新值;\n  \
         - supportingIndices 可省略;省略时表示「从全部入参 record 提炼」;\n  \
         - 不要输出 JSON 以外的解释文字;如果实在提炼不出,返回 [].\n",
    );
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
    use crate::pc_automation::principles::store::InMemoryPrincipleStore;
    use crate::pc_automation::principles::types::PrincipleCategory;
    use crate::pc_automation::ui_tars::LlmMessage;
    use std::pin::Pin;
    use std::sync::Arc;

    fn mk_record(ts: i64, intent: &str, app: Option<&str>, err: Option<&str>) -> EpRecord {
        let mut r = EpRecord::new(ts, "exec-1", format!("step-{ts}"), intent, "failed");
        r.app_profile = app.map(|s| s.to_string());
        r.strategy_used = "uia".to_string();
        r.error = err.map(|s| s.to_string());
        r
    }

    /// `llm = None` → 必须返回空 Vec,不能 panic。
    /// 这是"offline 安全网"。
    #[tokio::test]
    async fn test_distill_from_records_with_no_llm_returns_empty() {
        let store = InMemoryPrincipleStore::new();
        let records = vec![mk_record(
            100,
            "提交订单到平安证券",
            Some("ths_hexin"),
            Some("uia: not found"),
        )];
        let out = distill_from_records(&records, &store, None)
            .await
            .expect("离线无 LLM 必须返回 Ok");
        assert!(out.is_empty(), "无 LLM 时必须返回空 Vec");
        // 也不能往 store 写东西
        assert!(store.list().is_empty());
    }

    /// 空 records → 必须返回空 Vec,不能 panic,也不应发请求给 LLM
    /// (本期 stub 也没有 LLM,只验证不调 LLM 即可)。
    #[tokio::test]
    async fn test_distill_from_records_empty_input() {
        let store = InMemoryPrincipleStore::new();
        let out = distill_from_records(&[], &store, None).await.unwrap();
        assert!(out.is_empty());
        assert!(store.list().is_empty());
    }

    /// 传一个 stub LLM 返回合法 JSON 数组 → 必须解析、写入 store、
    /// 并在返回值里把"最终入库的 Principle"原样交还。
    /// 这是 v6 第一阶段的关键回归点:以前 stub 走到 LLM 就返回空,
    /// 现在的契约是"JSON 合法 → 至少 1 条入库"。
    #[tokio::test]
    async fn test_distill_from_records_with_stub_llm_writes_principles() {
        // 1. stub LLM:返回 1 条 selector 原则
        fn stub_llm(
            _msgs: Vec<LlmMessage>,
        ) -> Pin<Box<dyn std::future::Future<Output = Result<String, String>> + Send>>
        {
            Box::pin(async {
                Ok("[{\"category\":\"selector\",\"statement\":\"css 优先于 uia\"}]".to_string())
            })
        }
        let llm: Arc<dyn LlmCompleteFn> = Arc::new(stub_llm);

        let store = InMemoryPrincipleStore::new();
        let records = vec![
            mk_record(100, "提交订单到平安证券", Some("ths_hexin"), Some("uia: not found")),
            mk_record(200, "查询股票行情", Some("ths_hexin"), Some("uia: not found")),
        ];

        let out = distill_from_records(&records, &store, Some(llm))
            .await
            .expect("合法 JSON 路径不能报错");

        // 1. 返回列表里应有 1 条
        assert_eq!(out.len(), 1, "应解析出 1 条 Principle");
        assert_eq!(out[0].category, PrincipleCategory::Selector);
        assert_eq!(out[0].statement, "css 优先于 uia");
        assert!(!out[0].id.is_empty(), "store.add 必须补 uuid");

        // 2. store 里也应有 1 条
        assert_eq!(store.list().len(), 1);

        // 3. supporting_records 应回填"全部入参 record"(LLM 没给下标)
        let rec_ids: Vec<String> = records.iter().map(|r| r.id.clone()).collect();
        assert_eq!(out[0].supporting_records, rec_ids);
    }

    /// 端到端:LLM 在 ```json ... ``` 围栏里返回 JSON → 必须剥围栏并解析。
    /// 这是日常 LLM 响应的实际形态(模型几乎总是包 markdown fence)。
    #[tokio::test]
    async fn test_distill_from_records_strips_markdown_fence() {
        fn stub_llm(
            _msgs: Vec<LlmMessage>,
        ) -> Pin<Box<dyn std::future::Future<Output = Result<String, String>> + Send>>
        {
            Box::pin(async {
                Ok(
                    "下面是提炼结果:\n```json\n[\
                     {\"category\":\"timing\",\"statement\":\"页面加载后等 1s 再操作\"},\
                     {\"category\":\"recovery\",\"statement\":\"OCR 失败回退到 VLM\"}\
                     ]\n```\n请审阅。".to_string(),
                )
            })
        }
        let llm: Arc<dyn LlmCompleteFn> = Arc::new(stub_llm);
        let store = InMemoryPrincipleStore::new();
        let records = vec![mk_record(1, "x", Some("app"), Some("err"))];

        let out = distill_from_records(&records, &store, Some(llm))
            .await
            .expect("围栏路径不能报错");
        assert_eq!(out.len(), 2);
        assert_eq!(out[0].category, PrincipleCategory::Timing);
        assert_eq!(out[1].category, PrincipleCategory::Recovery);
    }

    /// LLM 给了 `supportingIndices` → 只回填指定下标对应的 record,
    /// 不回填全部。验证我们没"贪心"地把全部 record 都挂上。
    #[tokio::test]
    async fn test_distill_from_records_uses_supporting_indices_when_given() {
        fn stub_llm(
            _msgs: Vec<LlmMessage>,
        ) -> Pin<Box<dyn std::future::Future<Output = Result<String, String>> + Send>>
        {
            Box::pin(async {
                Ok(
                    "[{\"category\":\"selector\",\"statement\":\"x\",\"supportingIndices\":[1]}]"
                        .to_string(),
                )
            })
        }
        let llm: Arc<dyn LlmCompleteFn> = Arc::new(stub_llm);
        let store = InMemoryPrincipleStore::new();
        let records = vec![
            mk_record(1, "a", None, Some("e1")),
            mk_record(2, "b", None, Some("e2")),
            mk_record(3, "c", None, Some("e3")),
        ];
        let out = distill_from_records(&records, &store, Some(llm))
            .await
            .unwrap();
        assert_eq!(out.len(), 1);
        // 应只挂 record[1] 的 id
        assert_eq!(out[0].supporting_records, vec![records[1].id.clone()]);
    }

    /// LLM 返回含未知 category → 那条被丢弃,合法那条照常入库。
    /// (避免脏数据进 store。)
    #[tokio::test]
    async fn test_distill_from_records_skips_unknown_category() {
        fn stub_llm(
            _msgs: Vec<LlmMessage>,
        ) -> Pin<Box<dyn std::future::Future<Output = Result<String, String>> + Send>>
        {
            Box::pin(async {
                Ok(
                    "[\
                     {\"category\":\"mystery\",\"statement\":\"unknown-cat 应被丢\"},\
                     {\"category\":\"timing\",\"statement\":\"合法条目\"}\
                     ]".to_string(),
                )
            })
        }
        let llm: Arc<dyn LlmCompleteFn> = Arc::new(stub_llm);
        let store = InMemoryPrincipleStore::new();
        let records = vec![mk_record(1, "x", None, Some("err"))];
        let out = distill_from_records(&records, &store, Some(llm))
            .await
            .unwrap();
        assert_eq!(out.len(), 1, "未知 category 应被丢弃");
        assert_eq!(out[0].statement, "合法条目");
        assert_eq!(out[0].category, PrincipleCategory::Timing);
        assert_eq!(store.list().len(), 1);
    }

    /// LLM 返回的 JSON 数组为空 → 返回空 Vec,不能 panic。
    #[tokio::test]
    async fn test_distill_from_records_empty_json_array() {
        fn stub_llm(
            _msgs: Vec<LlmMessage>,
        ) -> Pin<Box<dyn std::future::Future<Output = Result<String, String>> + Send>>
        {
            Box::pin(async { Ok("[]".to_string()) })
        }
        let llm: Arc<dyn LlmCompleteFn> = Arc::new(stub_llm);
        let store = InMemoryPrincipleStore::new();
        let records = vec![mk_record(1, "x", None, Some("err"))];
        let out = distill_from_records(&records, &store, Some(llm))
            .await
            .unwrap();
        assert!(out.is_empty());
        assert!(store.list().is_empty(), "空 JSON 数组不应写 store");
    }

    /// LLM 返回的完全不是 JSON(纯文字) → 静默返回空,不报错。
    #[tokio::test]
    async fn test_distill_from_records_swallows_invalid_json() {
        fn stub_llm(
            _msgs: Vec<LlmMessage>,
        ) -> Pin<Box<dyn std::future::Future<Output = Result<String, String>> + Send>>
        {
            Box::pin(async { Ok("我不知道怎么回答这个问题".to_string()) })
        }
        let llm: Arc<dyn LlmCompleteFn> = Arc::new(stub_llm);
        let store = InMemoryPrincipleStore::new();
        let records = vec![mk_record(1, "x", None, Some("err"))];
        let out = distill_from_records(&records, &store, Some(llm))
            .await
            .expect("非法 JSON 必须被吞掉");
        assert!(out.is_empty());
        assert!(store.list().is_empty());
    }

    /// `parse_principles_response` 单元测试:纯 JSON 数组。
    #[test]
    fn test_parse_principles_response_plain_array() {
        let raw = r#"[{"category":"selector","statement":"a"},{"category":"timing","statement":"b"}]"#;
        let drafts = parse_principles_response(raw);
        assert_eq!(drafts.len(), 2);
        assert_eq!(drafts[0].category, "selector");
        assert_eq!(drafts[1].statement, "b");
    }

    /// `parse_principles_response`:```json ... ``` 围栏 + 头尾解释文字。
    #[test]
    fn test_parse_principles_response_strips_fence_and_chatter() {
        let raw = "好的,以下是结果:\n```json\n[{\"category\":\"recovery\",\"statement\":\"c\"}]\n```\n希望对你有帮助";
        let drafts = parse_principles_response(raw);
        assert_eq!(drafts.len(), 1);
        assert_eq!(drafts[0].category, "recovery");
    }

    /// `parse_principles_response`:无语言标签的 ``` ... ``` 围栏。
    #[test]
    fn test_parse_principles_response_strips_fence_no_lang() {
        let raw = "```\n[{\"category\":\"sequencing\",\"statement\":\"d\"}]\n```";
        let drafts = parse_principles_response(raw);
        assert_eq!(drafts.len(), 1);
        assert_eq!(drafts[0].category, "sequencing");
    }

    /// `parse_principles_response`:无方括号 → 返回空。
    #[test]
    fn test_parse_principles_response_no_bracket_returns_empty() {
        let drafts = parse_principles_response("啥也没有");
        assert!(drafts.is_empty());
    }

    /// `map_category`:大小写不敏感 + 未知值 → None。
    #[test]
    fn test_map_category_case_insensitive_and_unknown() {
        assert_eq!(map_category("Selector"), Some(PrincipleCategory::Selector));
        assert_eq!(map_category("  TIMING  "), Some(PrincipleCategory::Timing));
        assert_eq!(map_category("unknown"), None);
        assert_eq!(map_category(""), None);
    }

    /// `resolve_supporting_records`:`None` → 全部入参 id;
    /// `Some([0, 2])` → 只取下标 0 / 2;越界下标静默跳过。
    #[test]
    fn test_resolve_supporting_records() {
        let records = vec![
            mk_record(1, "a", None, Some("e1")),
            mk_record(2, "b", None, Some("e2")),
            mk_record(3, "c", None, Some("e3")),
        ];
        // 1. None → 全部
        let draft_all = PrincipleDraft {
            category: "selector".to_string(),
            statement: "x".to_string(),
            supporting_indices: None,
        };
        let ids: Vec<String> = records.iter().map(|r| r.id.clone()).collect();
        assert_eq!(resolve_supporting_records(&records, &draft_all), ids);

        // 2. Some([0, 2]) → 只 0 和 2
        let draft_pick = PrincipleDraft {
            category: "selector".to_string(),
            statement: "x".to_string(),
            supporting_indices: Some(vec![0, 2]),
        };
        assert_eq!(
            resolve_supporting_records(&records, &draft_pick),
            vec![records[0].id.clone(), records[2].id.clone()]
        );

        // 3. 越界(99) → 静默跳过
        let draft_oob = PrincipleDraft {
            category: "selector".to_string(),
            statement: "x".to_string(),
            supporting_indices: Some(vec![0, 99]),
        };
        assert_eq!(
            resolve_supporting_records(&records, &draft_oob),
            vec![records[0].id.clone()]
        );
    }

    /// 传一个会**报错**的 LLM → 必须静默返回空 Vec(offline 任务的
    /// 失败不应让上游 panic)。这是关键的不变性。
    #[tokio::test]
    async fn test_distill_from_records_swallows_llm_error() {
        fn failing_llm(
            _msgs: Vec<LlmMessage>,
        ) -> Pin<Box<dyn std::future::Future<Output = Result<String, String>> + Send>>
        {
            Box::pin(async { Err("rate-limited".to_string()) })
        }
        let llm: Arc<dyn LlmCompleteFn> = Arc::new(failing_llm);
        let store = InMemoryPrincipleStore::new();
        let records = vec![mk_record(1, "x", None, Some("err"))];
        let out = distill_from_records(&records, &store, Some(llm))
            .await
            .expect("LLM 错误必须被吞掉");
        assert!(out.is_empty());
    }

    /// 传一个会**返回空字符串**的 LLM → 同样静默返回空 Vec。
    #[tokio::test]
    async fn test_distill_from_records_swallows_empty_response() {
        fn empty_llm(
            _msgs: Vec<LlmMessage>,
        ) -> Pin<Box<dyn std::future::Future<Output = Result<String, String>> + Send>>
        {
            Box::pin(async { Ok(String::new()) })
        }
        let llm: Arc<dyn LlmCompleteFn> = Arc::new(empty_llm);
        let store = InMemoryPrincipleStore::new();
        let records = vec![mk_record(1, "x", None, Some("err"))];
        let out = distill_from_records(&records, &store, Some(llm))
            .await
            .expect("空 LLM 响应必须被吞掉");
        assert!(out.is_empty());
    }

    /// `build_distill_prompt` 必须包含关键字段(intent / strategy /
    /// outcome),便于后续 v6 接 LLM 时直接复用。
    #[test]
    fn test_build_distill_prompt_contains_records() {
        let records = vec![
            mk_record(100, "提交订单", Some("ths_hexin"), Some("uia: not found")),
            mk_record(200, "撤单", Some("ths_hexin"), Some("uia: not found")),
        ];
        let prompt = build_distill_prompt(&records);
        // 头部说明
        assert!(prompt.contains("Principle"));
        // 两条 record 的关键字段都被拼进 prompt
        assert!(prompt.contains("提交订单"));
        assert!(prompt.contains("撤单"));
        assert!(prompt.contains("ths_hexin"));
        assert!(prompt.contains("uia: not found"));
        // 类别说明
        assert!(prompt.contains("selector"));
        assert!(prompt.contains("sequencing"));
        assert!(prompt.contains("recovery"));
        assert!(prompt.contains("timing"));
    }

    /// store 泛型参数 `&dyn PrincipleStore` 必须被接受,这里
    /// 用 InMemoryPrincipleStore 实测编译通过。
    /// (间接验证 trait object 形态对。)
    #[test]
    fn test_distill_trait_object_accepted() {
        let store: &dyn PrincipleStore = &InMemoryPrincipleStore::new();
        // 直接 add 一条,验证 trait 工作
        let mut p = Principle::new(PrincipleCategory::Selector, "x", 0);
        p.supporting_records = vec!["r1".to_string()];
        let id = store.add(p);
        assert!(!id.is_empty());
        // 再 add 一次,验证去重
        let p2 = Principle::new(PrincipleCategory::Selector, "x", 0);
        let id2 = store.add(p2);
        assert_eq!(id, id2);
    }

    /// 编译期:我们刻意不依赖 `InMemoryEpisodicStore`,但导入链
    /// 留下以备 v6 落"按 store 增量 distill"时复用。
    #[test]
    fn _episodic_import_linked() {
        // 用一下,避免 import 被 rustfmt 误删
        let _store = InMemoryEpisodicStore::new();
    }
}
